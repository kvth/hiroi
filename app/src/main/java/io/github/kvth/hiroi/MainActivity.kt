package io.github.kvth.hiroi

import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.kvth.hiroi.anki.AnkiConnect
import io.github.kvth.hiroi.anki.AnkiSettings
import io.github.kvth.hiroi.anki.CardFormat
import io.github.kvth.hiroi.anki.Markers
import io.github.kvth.hiroi.anki.YomitanSettings
import io.github.kvth.hiroi.dictionary.DictionaryDb
import io.github.kvth.hiroi.dictionary.DictionaryInfo
import io.github.kvth.hiroi.marks.MarksActivity
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors

/** Setup screen: shows what is enabled, links to the system settings pages and manages dictionaries. */
class MainActivity : Activity() {

    private val dictionaries by lazy { DictionaryDb.get(this) }
    private val anki by lazy { AnkiSettings(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.open_accessibility).setOnClickListener {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.open_app_info).setOnClickListener {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        findViewById<Button>(R.id.open_assistant).setOnClickListener {
            open(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS), Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
        findViewById<Button>(R.id.open_search).setOnClickListener { startActivity(SearchActivity.intent(this)) }
        findViewById<Button>(R.id.import_dictionary).setOnClickListener {
            // Files apps disagree on the zip MIME type, so accept anything and let the import check it.
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            @Suppress("DEPRECATION")
            startActivityForResult(pick, REQUEST_IMPORT)
        }
        findViewById<Button>(R.id.cards_import).setOnClickListener {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            @Suppress("DEPRECATION")
            startActivityForResult(pick, REQUEST_YOMITAN_SETTINGS)
        }
        findViewById<Button>(R.id.import_example).setOnClickListener {
            importDictionary { assets.open(EXAMPLE_ASSET) }
        }
        val examples = findViewById<LinearLayout>(R.id.example_sentences)
        // One view per sentence, so each gets its own box in the overlay.
        for (sentence in resources.getStringArray(R.array.example_sentences)) {
            examples.addView(TextView(this).apply {
                text = sentence
                textSize = 20f
                setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
            })
        }
        findViewById<ImageView>(R.id.example_image).setImageBitmap(exampleImage())
        setUpAnki()
        if (savedInstanceState == null) importFrom(intent)
    }

    /** A sentence drawn into a bitmap: an image to try OCR on, since apps don't expose that as text. */
    private fun exampleImage(): Bitmap {
        val density = resources.displayMetrics.density
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 22f, resources.displayMetrics)
            typeface = Typeface.DEFAULT_BOLD
        }
        val text = getString(R.string.example_image_text)
        val padding = 16 * density
        val bounds = Rect().also { paint.getTextBounds(text, 0, text.length, it) }
        val bitmap = Bitmap.createBitmap(
            (bounds.width() + 2 * padding).toInt(), (bounds.height() + 2 * padding).toInt(), Bitmap.Config.ARGB_8888,
        )
        Canvas(bitmap).apply {
            drawColor(0xFF2E5A88.toInt())
            drawText(text, padding - bounds.left, padding - bounds.top, paint)
        }
        return bitmap
    }

    private fun setUpAnki() {
        findViewById<EditText>(R.id.anki_url).apply {
            setText(anki.url)
            onChange { anki.url = it }
        }
        findViewById<EditText>(R.id.anki_key).apply {
            setText(anki.apiKey)
            onChange { anki.apiKey = it }
        }
        findViewById<Button>(R.id.anki_deck).setOnClickListener {
            askAnki({ it.deckNames() }) { decks ->
                choose(R.string.anki_choose_deck, decks) { deck ->
                    if (deck != anki.deck) anki.field = ""
                    anki.deck = deck
                }
            }
        }
        findViewById<Button>(R.id.anki_field).setOnClickListener {
            val deck = anki.deck
            askAnki({ it.fieldNames(deck) }) { fields ->
                if (fields.isEmpty()) {
                    showAnkiStatus(getString(R.string.anki_no_fields))
                } else {
                    choose(R.string.anki_choose_field, fields) { anki.field = it }
                }
            }
        }
        setUpCards()
        findViewById<Button>(R.id.marks_open).setOnClickListener { startActivity(Intent(this, MarksActivity::class.java)) }
        showAnkiChoices()
    }

    private fun setUpCards() {
        findViewById<Button>(R.id.cards_deck).setOnClickListener {
            askAnki({ it.deckNames() }) { decks -> choose(R.string.anki_choose_deck, decks) { deck -> anki.cardFormat = anki.cardFormat.copy(deck = deck) } }
        }
        findViewById<Button>(R.id.cards_model).setOnClickListener {
            askAnki({ it.modelNames() }) { models ->
                choose(R.string.anki_choose_model, models) { model ->
                    if (model.isEmpty()) {
                        anki.cardFormat = anki.cardFormat.copy(model = "", fields = emptyMap())
                    } else {
                        askAnki({ it.modelFieldNames(model) }) { names ->
                            // Keep templates for fields that stay, guess the rest from their names.
                            val old = anki.cardFormat.fields
                            val guessed = CardFormat.guessTemplates(names)
                            anki.cardFormat = anki.cardFormat.copy(model = model, fields = names.associateWithTo(LinkedHashMap()) { old[it] ?: guessed.getValue(it) })
                            showAnkiChoices()
                        }
                    }
                }
            }
        }
        findViewById<Button>(R.id.cards_fields).setOnClickListener { editFieldTemplates() }
        findViewById<EditText>(R.id.cards_tags).apply {
            setText(anki.cardFormat.tags.joinToString(" "))
            onChange { text -> anki.cardFormat = anki.cardFormat.copy(tags = text.split(Regex("\\s+")).filter { it.isNotEmpty() }) }
        }
        findViewById<CheckBox>(R.id.cards_prevent_duplicates).apply {
            isChecked = anki.cardFormat.preventDuplicates
            setOnCheckedChangeListener { _, checked -> anki.cardFormat = anki.cardFormat.copy(preventDuplicates = checked) }
        }
        findViewById<CheckBox>(R.id.cards_word_audio).apply {
            isChecked = anki.wordAudio
            setOnCheckedChangeListener { _, checked -> anki.wordAudio = checked }
        }
    }

    /** A form with a template per field of the note type, like Yomitan's card format editor. */
    private fun editFieldTemplates() {
        val format = anki.cardFormat
        if (format.fields.isEmpty()) {
            showCardStatus(getString(R.string.cards_incomplete))
            return
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val inputs = format.fields.map { (name, template) ->
            form.addView(TextView(this).apply { text = name; setPadding(0, pad / 2, 0, 0) })
            name to EditText(this).apply {
                setText(template)
                textSize = 14f
                form.addView(this)
            }
        }
        form.addView(TextView(this).apply {
            text = getString(R.string.cards_fields_help, Markers.supported.sorted().joinToString(" ") { "{$it}" })
            textSize = 12f
            setPadding(0, pad, 0, pad)
        })
        AlertDialog.Builder(this)
            .setTitle(format.model)
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton(R.string.save) { _, _ ->
                anki.cardFormat = anki.cardFormat.copy(fields = inputs.associateTo(LinkedHashMap()) { (name, input) -> name to input.text.toString() })
                showAnkiChoices()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Takes a card format from a Yomitan settings export; with several profiles, the user picks one. */
    private fun importYomitanSettings(uri: Uri) {
        val formats = try {
            YomitanSettings.cardFormats(contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            showCardStatus(getString(R.string.cards_import_failed, e.message ?: e.javaClass.simpleName))
            return
        }
        when (formats.size) {
            0 -> showCardStatus(getString(R.string.cards_import_none))
            1 -> useCardFormat(formats.single().second)
            else -> AlertDialog.Builder(this)
                .setTitle(R.string.cards_import_choose)
                .setItems(formats.map { (name, format) -> "$name (${format.model} → ${format.deck})" }.toTypedArray()) { _, which ->
                    useCardFormat(formats[which].second)
                }
                .show()
        }
    }

    private fun useCardFormat(format: CardFormat) {
        anki.cardFormat = format
        findViewById<EditText>(R.id.cards_tags).setText(format.tags.joinToString(" "))
        findViewById<CheckBox>(R.id.cards_prevent_duplicates).isChecked = format.preventDuplicates
        showAnkiChoices()
    }

    private fun showCardStatus(text: String) {
        findViewById<TextView>(R.id.cards_status).text = text
    }

    /** A list to pick from, with "none" first to switch the Anki check off. */
    private fun choose(title: Int, items: List<String>, onChosen: (String) -> Unit) {
        val choices = listOf(getString(R.string.anki_none)) + items
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(choices.toTypedArray()) { _, which ->
                onChosen(if (which == 0) "" else items[which - 1])
                showAnkiChoices()
            }
            .show()
    }

    private fun <T> askAnki(call: (AnkiConnect) -> T, onResult: (T) -> Unit) {
        showAnkiStatus(getString(R.string.anki_asking))
        val client = anki.client()
        background.execute {
            val result = runCatching { call(client) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.fold(
                    { showAnkiChoices(); onResult(it) },
                    { showAnkiStatus(getString(R.string.anki_unreachable, it.message ?: it.javaClass.simpleName)) },
                )
            }
        }
    }

    private fun showAnkiChoices() {
        val none = getString(R.string.anki_none)
        findViewById<Button>(R.id.anki_deck).text = getString(R.string.anki_deck, anki.deck.ifEmpty { none })
        findViewById<Button>(R.id.anki_field).apply {
            text = getString(R.string.anki_field, anki.field.ifEmpty { none })
            isEnabled = anki.deck.isNotEmpty()
        }
        showAnkiStatus(if (anki.enabled) getString(R.string.anki_ready, anki.deck, anki.field) else "")

        val format = anki.cardFormat
        findViewById<Button>(R.id.cards_deck).text = getString(R.string.cards_deck, format.deck.ifEmpty { none })
        findViewById<Button>(R.id.cards_model).text = getString(R.string.cards_model, format.model.ifEmpty { none })
        findViewById<Button>(R.id.cards_fields).isEnabled = format.fields.isNotEmpty()
        showCardStatus(
            if (!format.complete) {
                getString(R.string.cards_incomplete)
            } else {
                val ready = getString(R.string.cards_ready, format.model, format.deck, format.fields.values.count { it.isNotBlank() })
                val unsupported = format.unsupported
                if (unsupported.isEmpty()) ready else ready + "\n" + getString(R.string.cards_unsupported, unsupported.joinToString(" ") { "{$it}" })
            },
        )
    }

    private fun showAnkiStatus(text: String) {
        findViewById<TextView>(R.id.anki_status).apply {
            this.text = text
            visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun EditText.onChange(save: (String) -> Unit) = addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = save(s.toString())
    })

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        importFrom(intent)
    }

    /** "Open with" / "Share" a dictionary zip, e.g. right after downloading it in a browser. */
    private fun importFrom(intent: Intent) {
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        } ?: return
        // file:// URIs only in debug builds, where scripts/emulator.sh imports through the app's own cache.
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (uri.scheme == ContentResolver.SCHEME_CONTENT || (uri.scheme == ContentResolver.SCHEME_FILE && debuggable)) {
            // A JSON file is a Yomitan settings export (scripts/emulator.sh yomitan); anything else a dictionary.
            if (intent.type == "application/json" || uri.path.orEmpty().endsWith(".json")) importYomitanSettings(uri)
            else importDictionary { contentResolver.openInputStream(uri)!! }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQUEST_IMPORT && resultCode == RESULT_OK && uri != null) {
            importDictionary { contentResolver.openInputStream(uri)!! }
        }
        if (requestCode == REQUEST_YOMITAN_SETTINGS && resultCode == RESULT_OK && uri != null) importYomitanSettings(uri)
    }

    override fun onResume() {
        super.onResume()
        setStatus(R.id.accessibility_status, accessibilityServiceEnabled())
        setStatus(R.id.assistant_status, getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT))
        showDictionaries()
    }

    private fun importDictionary(open: () -> InputStream) {
        val status = findViewById<TextView>(R.id.import_status)
        val button = findViewById<Button>(R.id.import_dictionary)
        button.isEnabled = false
        status.visibility = View.VISIBLE
        status.setText(R.string.import_copying)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        background.execute {
            val message = try {
                // ZipFile needs random access, so copy the picked document first.
                val zip = File(cacheDir, "import.zip")
                try {
                    open().use { input -> zip.outputStream().use { input.copyTo(it) } }
                    val info = dictionaries.import(zip) { progress -> runOnUiThread { status.text = progress } }
                    Log.i(TAG, "Imported ${info.title}: ${info.terms} terms, ${info.meta} meta, ${info.kanji} kanji")
                    getString(R.string.import_done, info.title)
                } finally {
                    zip.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Import failed: ${e.message}", e)
                getString(R.string.import_failed, e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                button.isEnabled = true
                status.text = message
                showDictionaries()
            }
        }
    }

    private fun showDictionaries() {
        val list = findViewById<LinearLayout>(R.id.dictionary_list)
        list.removeAllViews()
        val installed = dictionaries.dictionaries()
        findViewById<Button>(R.id.import_example).visibility =
            if (installed.any { it.title == EXAMPLE_DICTIONARY }) View.GONE else View.VISIBLE
        if (installed.isEmpty()) {
            list.addView(TextView(this).apply { setText(R.string.no_dictionaries_installed) })
        }
        for (info in installed) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(
                TextView(this).apply { text = describe(info) },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
            )
            row.addView(
                Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                    setText(R.string.remove)
                    setOnClickListener { confirmDelete(info) }
                },
            )
            list.addView(row)
        }
    }

    private fun describe(info: DictionaryInfo): String = buildString {
        append(info.title)
        if (info.revision.isNotEmpty()) append(" (").append(info.revision).append(')')
        val counts = listOfNotNull(
            info.terms.takeIf { it > 0 }?.let { getString(R.string.count_terms, it) },
            info.meta.takeIf { it > 0 }?.let { getString(R.string.count_meta, it) },
            info.kanji.takeIf { it > 0 }?.let { getString(R.string.count_kanji, it) },
        )
        if (counts.isNotEmpty()) append('\n').append(counts.joinToString(" · "))
    }

    private fun confirmDelete(info: DictionaryInfo) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.remove_confirm, info.title))
            .setPositiveButton(R.string.remove) { _, _ ->
                background.execute {
                    dictionaries.delete(info.id)
                    runOnUiThread { if (!isDestroyed) showDictionaries() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Asks the system rather than checking for a running service, which binds a moment after the app starts. */
    private fun accessibilityServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val component = ComponentName(this, HiroiAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    private fun setStatus(id: Int, enabled: Boolean) {
        findViewById<TextView>(id).setText(if (enabled) R.string.status_on else R.string.status_off)
    }

    private companion object {
        const val TAG = "HiroiImport"
        const val REQUEST_IMPORT = 1
        const val REQUEST_YOMITAN_SETTINGS = 2
        const val EXAMPLE_ASSET = "example-dictionary.zip"
        const val EXAMPLE_DICTIONARY = "Hiroi example dictionary" // title in scripts/make-example-dictionary.py

        /** Imports and deletes run one at a time, and outlive the activity if the user leaves. */
        val background = Executors.newSingleThreadExecutor()
    }

    /** Settings pages differ between vendors, so try each intent until one opens. */
    private fun open(vararg intents: Intent) {
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
    }
}
