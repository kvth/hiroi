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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import io.github.kvth.hiroi.anki.AnkiConnect
import io.github.kvth.hiroi.anki.AnkiSettings
import io.github.kvth.hiroi.anki.CardFormat
import io.github.kvth.hiroi.anki.AnkiTemplates
import io.github.kvth.hiroi.anki.YomitanCardFormat
import io.github.kvth.hiroi.anki.YomitanSettings
import io.github.kvth.hiroi.dictionary.DictionaryDb
import io.github.kvth.hiroi.dictionary.DictionaryInfo
import io.github.kvth.hiroi.dictionary.DictionarySettings
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
        findViewById<Button>(R.id.sort_dictionary).setOnClickListener { chooseSortDictionary() }
        findViewById<Button>(R.id.sort_order).setOnClickListener {
            val settings = DictionarySettings(this)
            settings.sortAscending = !settings.sortAscending
            showSorting()
        }
        findViewById<CheckBox>(R.id.average_frequencies).apply {
            isChecked = DictionarySettings(this@MainActivity).averageFrequencies
            setOnCheckedChangeListener { _, checked -> DictionarySettings(this@MainActivity).averageFrequencies = checked }
        }
        findViewById<Button>(R.id.export_collection).setOnClickListener {
            val create = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "hiroi-dictionaries-${java.time.LocalDate.now()}.json")
            @Suppress("DEPRECATION")
            startActivityForResult(create, REQUEST_EXPORT_COLLECTION)
        }
        findViewById<Button>(R.id.import_collection).setOnClickListener {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            @Suppress("DEPRECATION")
            startActivityForResult(pick, REQUEST_IMPORT_COLLECTION)
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
        findViewById<Button>(R.id.cards_templates).setOnClickListener { startActivity(Intent(this, TemplatesActivity::class.java)) }
        findViewById<EditText>(R.id.cards_tags).apply {
            setText(anki.cardFormat.tags.joinToString(" "))
            onChange { text -> anki.cardFormat = anki.cardFormat.copy(tags = text.split(Regex("\\s+")).filter { it.isNotEmpty() }) }
        }
        findViewById<CheckBox>(R.id.cards_check_duplicates).setOnCheckedChangeListener { _, checked ->
            anki.cardFormat = anki.cardFormat.copy(checkForDuplicates = checked)
            showDuplicateOptions()
        }
        findViewById<CheckBox>(R.id.cards_check_all_models).setOnCheckedChangeListener { _, checked ->
            anki.cardFormat = anki.cardFormat.copy(checkAllModels = checked)
        }
        findViewById<Button>(R.id.cards_duplicate_behavior).setOnClickListener {
            pickOne(R.string.cards_check_duplicates, CardFormat.DUPLICATE_BEHAVIORS, ::behaviorLabel, anki.cardFormat.duplicateBehavior) {
                anki.cardFormat = anki.cardFormat.copy(duplicateBehavior = it)
                showDuplicateOptions()
            }
        }
        findViewById<Button>(R.id.cards_duplicate_scope).setOnClickListener {
            pickOne(R.string.cards_check_duplicates, CardFormat.DUPLICATE_SCOPES, ::scopeLabel, anki.cardFormat.duplicateScope) {
                anki.cardFormat = anki.cardFormat.copy(duplicateScope = it)
                showDuplicateOptions()
            }
        }
        showDuplicateOptions()
        findViewById<CheckBox>(R.id.cards_word_audio).apply {
            isChecked = anki.wordAudio
            setOnCheckedChangeListener { _, checked -> anki.wordAudio = checked }
        }
    }

    /** Yomitan's duplicate options for cards: whether to check, what to do, where to look, which note types. */
    private fun showDuplicateOptions() {
        val format = anki.cardFormat
        findViewById<CheckBox>(R.id.cards_check_duplicates).isChecked = format.checkForDuplicates
        findViewById<CheckBox>(R.id.cards_check_all_models).apply {
            isChecked = format.checkAllModels
            isEnabled = format.checkForDuplicates
        }
        findViewById<Button>(R.id.cards_duplicate_behavior).apply {
            text = getString(R.string.cards_duplicate_behavior, behaviorLabel(format.duplicateBehavior))
            isEnabled = format.checkForDuplicates
        }
        findViewById<Button>(R.id.cards_duplicate_scope).apply {
            text = getString(R.string.cards_duplicate_scope, scopeLabel(format.duplicateScope))
            isEnabled = format.checkForDuplicates
        }
    }

    private fun behaviorLabel(behavior: String) = getString(
        when (behavior) {
            "prevent" -> R.string.cards_duplicate_prevent
            "overwrite" -> R.string.cards_duplicate_overwrite
            else -> R.string.cards_duplicate_new
        },
    )

    private fun scopeLabel(scope: String) = getString(
        when (scope) {
            "deck" -> R.string.cards_scope_deck
            "deck-root" -> R.string.cards_scope_deck_root
            else -> R.string.cards_scope_collection
        },
    )

    private fun pickOne(title: Int, values: List<String>, label: (String) -> String, current: String, onPicked: (String) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(values.map(label).toTypedArray(), values.indexOf(current)) { dialog, which ->
                dialog.dismiss()
                onPicked(values[which])
            }
            .show()
    }

    /**
     * The markers a field can use, in the order of Yomitan's field menu: its standard ones, one
     * per dictionary, then the user's own (inline templates their templates add).
     */
    private fun markerChoices(): List<String> {
        val default = AnkiTemplates.defaultTemplates(this)
        val custom = anki.fieldTemplates?.let { AnkiTemplates.inlineMarkers(it) - AnkiTemplates.inlineMarkers(default).toSet() }.orEmpty()
        return (AnkiTemplates.standardMarkers(this) + AnkiTemplates.dynamicMarkers(dictionaries.enabledDictionaries()) + custom).distinct()
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
        // With "overwrite the existing note", each field also says how (Yomitan's overwrite mode).
        val overwriting = format.checkForDuplicates && format.duplicateBehavior == "overwrite"
        val modeLabels = resources.getStringArray(R.array.cards_overwrite_modes)
        val modes = HashMap<String, Spinner>()
        val markers = markerChoices()
        val inputs = format.fields.map { (name, template) ->
            form.addView(TextView(this).apply { text = name; setPadding(0, pad / 2, 0, 0) })
            val input = EditText(this).apply {
                setText(template)
                textSize = 14f
            }
            // Like Yomitan's field menu: pick a marker to put in at the cursor.
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = "{…}"
                minWidth = 0
                minimumWidth = 0
                contentDescription = getString(R.string.cards_marker_menu)
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.cards_marker_menu)
                        .setItems(markers.map { "{$it}" }.toTypedArray()) { _, which ->
                            val start = maxOf(input.selectionStart, 0)
                            val end = maxOf(input.selectionEnd, 0)
                            input.text.replace(minOf(start, end), maxOf(start, end), "{${markers[which]}}")
                        }
                        .show()
                }
            })
            form.addView(row)
            if (overwriting) {
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(TextView(this).apply { setText(R.string.cards_overwrite_mode); textSize = 12f })
                modes[name] = Spinner(this).apply {
                    adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, modeLabels)
                    setSelection(CardFormat.OVERWRITE_MODES.indexOf(format.overwriteMode(name)))
                    row.addView(this)
                }
                form.addView(row)
            }
            name to input
        }
        form.addView(TextView(this).apply {
            setText(R.string.cards_fields_help)
            textSize = 12f
            setPadding(0, pad, 0, pad)
        })
        AlertDialog.Builder(this)
            .setTitle(format.model)
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton(R.string.save) { _, _ ->
                anki.cardFormat = anki.cardFormat.copy(
                    fields = inputs.associateTo(LinkedHashMap()) { (name, input) -> name to input.text.toString() },
                    overwriteModes = if (!overwriting) anki.cardFormat.overwriteModes else
                        modes.mapValues { (_, spinner) -> CardFormat.OVERWRITE_MODES[spinner.selectedItemPosition] },
                )
                showAnkiChoices()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** A dictionary collection (Dexie export) starts with {"formatName":"dexie"; Yomitan's settings don't. */
    private fun isCollection(uri: Uri): Boolean = runCatching {
        val start = ByteArray(200)
        val read = contentResolver.openInputStream(uri)!!.use { it.read(start) }
        String(start, 0, maxOf(read, 0)).replace(Regex("\\s"), "").contains("\"formatName\":\"dexie\"")
    }.getOrDefault(false)

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
            1 -> useCardFormat(formats.single())
            else -> AlertDialog.Builder(this)
                .setTitle(R.string.cards_import_choose)
                .setItems(formats.map { "${it.name} (${it.format.model} → ${it.format.deck})" }.toTypedArray()) { _, which ->
                    useCardFormat(formats[which])
                }
                .show()
        }
    }

    /** A card format from Yomitan, with its profile's templates and glossary and tag layout. */
    private fun useCardFormat(imported: YomitanCardFormat) {
        val format = imported.format
        anki.cardFormat = format
        anki.fieldTemplates = imported.templates
        anki.compactGlossaries = imported.compactGlossaries
        anki.compactTags = imported.compactTags
        findViewById<EditText>(R.id.cards_tags).setText(format.tags.joinToString(" "))
        showDuplicateOptions()
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
                val templates = anki.fieldTemplates
                val unknown = AnkiTemplates.unknownMarkers(format, templates ?: AnkiTemplates.defaultTemplates(this), dictionaries.enabledDictionaries())
                val lines = listOfNotNull(
                    ready,
                    getString(if (templates == null) R.string.cards_templates_default else R.string.cards_templates_custom, AnkiTemplates.version(this)),
                    unknown.takeIf { it.isNotEmpty() }?.let { getString(R.string.cards_unsupported, it.joinToString(" ") { m -> "{$m}" }) },
                )
                lines.joinToString("\n")
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
            // A JSON file is a dictionary collection or a Yomitan settings export (scripts/emulator.sh yomitan);
            // anything else a dictionary.
            when {
                intent.type != "application/json" && !uri.path.orEmpty().endsWith(".json") -> importDictionary { contentResolver.openInputStream(uri)!! }
                isCollection(uri) -> importCollection(uri)
                else -> importYomitanSettings(uri)
            }
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
        if (requestCode == REQUEST_EXPORT_COLLECTION && resultCode == RESULT_OK && uri != null) exportCollection(uri)
        if (requestCode == REQUEST_IMPORT_COLLECTION && resultCode == RESULT_OK && uri != null) importCollection(uri)
    }

    /** Runs [work] in the background with its progress in the import status line, keeping the screen on. */
    private fun dictionaryWork(start: Int, work: ((String) -> Unit) -> String) {
        val status = findViewById<TextView>(R.id.import_status)
        val buttons = listOf(R.id.import_dictionary, R.id.export_collection, R.id.import_collection).map { findViewById<Button>(it) }
        buttons.forEach { it.isEnabled = false }
        status.visibility = View.VISIBLE
        status.setText(start)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        background.execute {
            val message = try {
                work { progress -> runOnUiThread { status.text = progress } }
            } catch (e: Exception) {
                Log.w(TAG, "Dictionary collection failed: ${e.message}", e)
                getString(R.string.collection_failed, e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                buttons.forEach { it.isEnabled = true }
                status.text = message
                showDictionaries()
            }
        }
    }

    /** All dictionaries as one file, in Yomitan's format, so Yomitan (or Hiroi on another phone) can import them. */
    private fun exportCollection(uri: Uri) = dictionaryWork(R.string.collection_exporting) { progress ->
        contentResolver.openOutputStream(uri, "wt")!!.use { out ->
            dictionaries.exportCollection(out) { progress(getString(R.string.collection_export_progress, it)) }
        }
        getString(R.string.collection_exported, dictionaries.dictionaries().size)
    }

    /** Dictionaries from a collection file (Yomitan's export or Hiroi's), added after the ones here. */
    private fun importCollection(uri: Uri) = dictionaryWork(R.string.collection_importing) { progress ->
        val result = contentResolver.openInputStream(uri)!!.use { input ->
            dictionaries.importCollection(input) { progress(getString(R.string.collection_import_progress, it)) }
        }
        listOfNotNull(
            getString(R.string.collection_imported, result.added.size, result.added.joinToString(", ")),
            result.skipped.takeIf { it.isNotEmpty() }?.let { getString(R.string.collection_skipped, it.joinToString(", ")) },
        ).joinToString("\n")
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
        showSorting()
        for (info in installed) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            // On or off, as Yomitan's switch per dictionary
            row.addView(CheckBox(this).apply {
                isChecked = info.enabled
                contentDescription = getString(R.string.dictionary_enabled, info.title)
                setOnCheckedChangeListener { _, checked -> setDictionaryEnabled(info, checked) }
            })
            row.addView(
                TextView(this).apply {
                    text = describe(info)
                    alpha = if (info.enabled) 1f else 0.5f
                },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
            )
            // The order is the priority, as in Yomitan: results show higher dictionaries first.
            for ((label, by) in listOf("▲" to -1, "▼" to 1)) {
                row.addView(
                    Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                        text = label
                        minWidth = 0
                        minimumWidth = 0
                        contentDescription = getString(if (by < 0) R.string.dictionary_up else R.string.dictionary_down)
                        isEnabled = if (by < 0) info != installed.first() else info != installed.last()
                        setOnClickListener { moveDictionary(info, by) }
                    },
                )
            }
            row.addView(
                Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                    setText(R.string.remove)
                    setOnClickListener { confirmDelete(info) }
                },
            )
            list.addView(row)
        }
    }

    private fun setDictionaryEnabled(info: DictionaryInfo, enabled: Boolean) {
        background.execute {
            dictionaries.setEnabled(info.id, enabled)
            runOnUiThread { if (!isDestroyed) showDictionaries() }
        }
    }

    private fun moveDictionary(info: DictionaryInfo, by: Int) {
        background.execute {
            dictionaries.move(info.id, by)
            runOnUiThread { if (!isDestroyed) showDictionaries() }
        }
    }

    private fun describe(info: DictionaryInfo): String = buildString {
        append(info.title)
        if (info.revision.isNotEmpty()) append(" (").append(info.revision).append(')')
        val counts = listOfNotNull(
            info.terms.takeIf { it > 0 }?.let { getString(R.string.count_terms, it) },
            info.meta.takeIf { it > 0 }?.let { getString(R.string.count_meta, it) },
            info.kanji.takeIf { it > 0 }?.let { getString(R.string.count_kanji, it) },
            info.takeIf { it.frequencies > 0 }?.let {
                getString(
                    R.string.count_frequencies,
                    getString(
                        when (it.frequencyMode) {
                            "rank-based" -> R.string.frequency_rank
                            "occurrence-based" -> R.string.frequency_occurrence
                            else -> R.string.frequency_unknown
                        },
                    ),
                )
            },
        )
        if (counts.isNotEmpty()) append('\n').append(counts.joinToString(" · "))
    }

    /** The frequency list results are sorted by, and its order (picked automatically until chosen). */
    private fun showSorting() {
        val settings = DictionarySettings(this)
        val dictionary = findViewById<Button>(R.id.sort_dictionary)
        val order = findViewById<Button>(R.id.sort_order)
        background.execute {
            val sort = settings.frequencySort(dictionaries)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                dictionary.text = getString(R.string.sort_dictionary, sort?.dictionary ?: getString(R.string.sort_none))
                order.setText(if (sort?.ascending != false) R.string.sort_ascending else R.string.sort_descending)
                order.isEnabled = sort != null
            }
        }
    }

    private fun chooseSortDictionary() {
        val lists = dictionaries.enabledDictionaries().filter { it.frequencies > 0 }.map { it.title }
        if (lists.isEmpty()) {
            AlertDialog.Builder(this).setMessage(R.string.sort_no_lists).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val choices = listOf(getString(R.string.sort_none)) + lists
        val current = DictionarySettings(this).sortDictionary
        AlertDialog.Builder(this)
            .setTitle(R.string.sort_title)
            .setSingleChoiceItems(choices.toTypedArray(), if (current.isNullOrEmpty()) 0 else lists.indexOf(current) + 1) { dialog, which ->
                dialog.dismiss()
                val title = if (which == 0) "" else lists[which - 1]
                background.execute {
                    DictionarySettings(this).choose(dictionaries, title)
                    runOnUiThread { if (!isDestroyed) showSorting() }
                }
            }
            .show()
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
        const val REQUEST_EXPORT_COLLECTION = 3
        const val REQUEST_IMPORT_COLLECTION = 4

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
