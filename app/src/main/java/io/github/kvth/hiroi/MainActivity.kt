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
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
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
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import io.github.kvth.hiroi.anki.AnkiConnect
import io.github.kvth.hiroi.anki.AnkiSettings
import io.github.kvth.hiroi.anki.CardFormat
import io.github.kvth.hiroi.anki.NoteTypeStyles
import io.github.kvth.hiroi.anki.AnkiTemplates
import io.github.kvth.hiroi.anki.YomitanCardFormat
import io.github.kvth.hiroi.anki.YomitanSettings
import io.github.kvth.hiroi.audio.AudioSettings
import io.github.kvth.hiroi.audio.AudioSource
import io.github.kvth.hiroi.audio.AudioSources
import io.github.kvth.hiroi.dictionary.DictionaryDb
import io.github.kvth.hiroi.dictionary.DictionaryInfo
import io.github.kvth.hiroi.dictionary.DictionarySettings
import io.github.kvth.hiroi.dictionary.Grouping
import io.github.kvth.hiroi.marks.MarksActivity
import io.github.kvth.hiroi.settings.SettingsFile
import io.github.kvth.hiroi.share.ShareTargets
import io.github.kvth.hiroi.translation.OnDeviceTranslation
import io.github.kvth.hiroi.translation.TargetLanguage
import io.github.kvth.hiroi.translation.TranslationServices
import io.github.kvth.hiroi.translation.TranslationSettings
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
        findViewById<CheckBox>(R.id.freeze_screen).apply {
            val prefs = getSharedPreferences(LookupActivity.PREFS, MODE_PRIVATE)
            isChecked = prefs.getBoolean(LookupActivity.KEY_FREEZE, true)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(LookupActivity.KEY_FREEZE, checked).apply() }
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
        setUpAudio()
        setUpTranslation()
        val shareTargets = findViewById<Button>(R.id.share_targets)
        fun showShareTargets() {
            shareTargets.text = getString(R.string.share_targets, ShareTargets.load(this).joinToString(", ") { it.name }.ifEmpty { getString(R.string.sort_none) })
        }
        showShareTargets()
        shareTargets.setOnClickListener { ShareTargets.edit(this) { showShareTargets() } }
        findViewById<Button>(R.id.export_settings).setOnClickListener { askExportSettings() }
        findViewById<Button>(R.id.import_settings).setOnClickListener {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            @Suppress("DEPRECATION")
            startActivityForResult(pick, REQUEST_IMPORT_SETTINGS)
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
    /**
     * A sentence on a picture, like a photo someone posted with a caption on it: a sunset over
     * mountains and a lake, the text in front with an outline and a shadow. Apps don't expose such
     * text, so it's something to try OCR on.
     */
    private fun exampleImage(): Bitmap {
        val density = resources.displayMetrics.density
        val text = getString(R.string.example_image_text)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24f, resources.displayMetrics)
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(4 * density, 0f, 2 * density, 0x99000000.toInt())
        }
        val outline = Paint(fill).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3 * density
            strokeJoin = Paint.Join.ROUND
            color = 0xCC1B1A33.toInt()
        }
        val bounds = Rect().also { fill.getTextBounds(text, 0, text.length, it) }
        val width = bounds.width() + 48 * density
        val height = width * 0.62f
        val bitmap = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val frame = RectF(0f, 0f, width, height)
        canvas.clipPath(Path().apply { addRoundRect(frame, 12 * density, 12 * density, Path.Direction.CW) })
        // Evening sky, a low sun, two ranges of mountains, a lake reflecting the light
        paint.shader = LinearGradient(0f, 0f, 0f, height * 0.7f, intArrayOf(0xFF27346B.toInt(), 0xFF8B4F8F.toInt(), 0xFFF29E6D.toInt(), 0xFFFFD49A.toInt()), floatArrayOf(0f, 0.35f, 0.75f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(frame, paint)
        paint.shader = RadialGradient(width * 0.7f, height * 0.58f, height * 0.35f, intArrayOf(0xFFFFF3C4.toInt(), 0x00FFE29A), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(width * 0.7f, height * 0.58f, height * 0.35f, paint)
        paint.shader = null
        paint.color = 0xFFFFF0B8.toInt()
        canvas.drawCircle(width * 0.7f, height * 0.58f, height * 0.09f, paint)
        fun ridge(color: Int, base: Float, peaks: List<Pair<Float, Float>>) {
            paint.color = color
            canvas.drawPath(Path().apply {
                moveTo(0f, height * base)
                for ((x, y) in peaks) lineTo(width * x, height * y)
                lineTo(width, height * base)
                lineTo(width, height)
                lineTo(0f, height)
                close()
            }, paint)
        }
        ridge(0xFF6A4C7E.toInt(), 0.62f, listOf(0.1f to 0.5f, 0.22f to 0.42f, 0.38f to 0.55f, 0.55f to 0.44f, 0.72f to 0.56f, 0.86f to 0.47f))
        ridge(0xFF3B2F5A.toInt(), 0.7f, listOf(0.08f to 0.62f, 0.2f to 0.66f, 0.33f to 0.56f, 0.48f to 0.68f, 0.64f to 0.6f, 0.8f to 0.69f, 0.93f to 0.62f))
        paint.shader = LinearGradient(0f, height * 0.72f, 0f, height, 0xFF5B5D8F.toInt(), 0xFF2A2B4E.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, height * 0.72f, width, height, paint)
        paint.shader = null
        paint.color = 0x66FFE7B0
        for (i in 0 until 6) {
            val y = height * (0.75f + i * 0.04f)
            val half = width * (0.07f - i * 0.008f)
            canvas.drawRect(width * 0.7f - half, y, width * 0.7f + half, y + 1.5f * density, paint)
        }
        // Birds
        paint.color = 0xCC2A2346.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        for ((x, y) in listOf(0.2f to 0.2f, 0.26f to 0.16f, 0.31f to 0.23f)) {
            val bx = width * x
            val by = height * y
            canvas.drawPath(Path().apply {
                moveTo(bx - 6 * density, by)
                quadTo(bx - 3 * density, by - 4 * density, bx, by)
                quadTo(bx + 3 * density, by - 4 * density, bx + 6 * density, by)
            }, paint)
        }
        // The caption, in the lower middle as on a posted photo
        val x = (width - bounds.width()) / 2 - bounds.left
        val y = height * 0.86f - bounds.bottom
        canvas.drawText(text, x, y, outline)
        canvas.drawText(text, x, y, fill)
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
        findViewById<CheckBox>(R.id.cards_dedup_styles).apply {
            isChecked = anki.dedupStyles
            setOnCheckedChangeListener { _, checked ->
                anki.dedupStyles = checked
                showNoteTypeStyles()
            }
        }
        findViewById<Button>(R.id.cards_note_type_css).setOnClickListener { exportNoteTypeStyles() }
        showNoteTypeStyles()
        findViewById<CheckBox>(R.id.cards_screenshot_crop).apply {
            isChecked = anki.screenshotCrop
            setOnCheckedChangeListener { _, checked -> anki.screenshotCrop = checked }
        }
        findViewById<Button>(R.id.cards_screenshot_format).setOnClickListener {
            val formats = AnkiSettings.SCREENSHOT_FORMATS
            AlertDialog.Builder(this)
                .setSingleChoiceItems(formats.map { getString(screenshotFormatLabel(it)) }.toTypedArray(), formats.indexOf(anki.screenshotFormat)) { dialog, which ->
                    anki.screenshotFormat = formats[which]
                    showScreenshotOptions()
                    dialog.dismiss()
                }
                .show()
        }
        findViewById<SeekBar>(R.id.cards_screenshot_quality).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                anki.screenshotQuality = value
                showScreenshotOptions()
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        showScreenshotOptions()
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
     * The markers a field can use, in the order of Yomitan's field menu: its standard ones (and
     * Hiroi's {sentence-translation}), one per dictionary, then the user's own (inline templates
     * their templates add).
     */
    private fun markerChoices(): List<String> {
        val default = AnkiTemplates.defaultTemplates(this)
        val custom = anki.fieldTemplates?.let { AnkiTemplates.inlineMarkers(it) - AnkiTemplates.inlineMarkers(default).toSet() }.orEmpty()
        return (AnkiTemplates.standardMarkers(this) + AnkiTemplates.HIROI_MARKERS + AnkiTemplates.dynamicMarkers(dictionaries.enabledDictionaries()) + custom).distinct()
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
        anki.screenshotFormat = imported.screenshotFormat
        anki.screenshotQuality = imported.screenshotQuality
        showScreenshotOptions()
        findViewById<EditText>(R.id.cards_tags).setText(format.tags.joinToString(" "))
        showDuplicateOptions()
        showAnkiChoices()
    }

    /** Whether the stylesheets kept for the note type changed since they were copied. */
    private fun showNoteTypeStyles() {
        val styles = NoteTypeStyles(this)
        findViewById<TextView>(R.id.cards_note_type_css_status).setText(
            when {
                styles.sheets.isEmpty() -> R.string.dedup_status_empty
                styles.changedSinceCopied -> R.string.dedup_status_changed
                else -> R.string.dedup_status_ok
            },
        )
    }

    /** Shows the note type's section, to copy or share it. */
    private fun exportNoteTypeStyles() {
        val styles = NoteTypeStyles(this)
        val section = styles.section()
        if (section == null) {
            AlertDialog.Builder(this).setTitle(R.string.dedup_export_title).setMessage(R.string.dedup_export_empty).setPositiveButton(R.string.done, null).show()
            return
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            this.text = section
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setPadding(pad, 0, pad, 0)
        }
        val size = android.text.format.Formatter.formatShortFileSize(this, section.toByteArray().size.toLong())
        AlertDialog.Builder(this)
            .setTitle(R.string.dedup_export_title)
            .setMessage(getString(R.string.dedup_export_message, styles.sheets.size, size))
            .setView(object : ScrollView(this) {
                // At most part of the screen, so the buttons stay on it
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = super.onMeasure(
                    widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec((resources.displayMetrics.heightPixels * 0.45f).toInt(), MeasureSpec.AT_MOST),
                )
            }.apply { addView(text) })
            .setPositiveButton(R.string.dedup_copy) { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.dedup_export_title), section))
                styles.copied()
                showNoteTypeStyles()
            }
            .setNeutralButton(R.string.dedup_share) { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, section), null))
                styles.copied()
                showNoteTypeStyles()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun screenshotFormatLabel(format: String) = if (format == "jpeg") R.string.cards_screenshot_jpeg else R.string.cards_screenshot_png

    /** {screenshot}'s format, and its JPEG quality (only for JPEG). */
    private fun showScreenshotOptions() {
        val jpeg = anki.screenshotFormat == "jpeg"
        findViewById<Button>(R.id.cards_screenshot_format).text = getString(R.string.cards_screenshot_format, getString(screenshotFormatLabel(anki.screenshotFormat)))
        val label = findViewById<TextView>(R.id.cards_screenshot_quality_label)
        val quality = findViewById<SeekBar>(R.id.cards_screenshot_quality)
        label.text = getString(R.string.cards_screenshot_quality, anki.screenshotQuality)
        quality.progress = anki.screenshotQuality
        label.visibility = if (jpeg) View.VISIBLE else View.GONE
        quality.visibility = if (jpeg) View.VISIBLE else View.GONE
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
        if (requestCode == REQUEST_EXPORT_SETTINGS && resultCode == RESULT_OK && uri != null) exportSettings(uri)
        if (requestCode == REQUEST_IMPORT_SETTINGS && resultCode == RESULT_OK && uri != null) importSettings(uri)
    }

    /** Whether the settings file being exported gets the API keys (asked before picking the file) */
    private var exportKeys = false

    /** Asks whether the API keys go in the settings file, then where to save it. */
    private fun askExportSettings() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val keys = CheckBox(this).apply { setText(R.string.settings_export_keys) }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(keys)
            addView(TextView(this@MainActivity).apply { setText(R.string.settings_export_keys_hint) })
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_export)
            .setView(form)
            .setPositiveButton(R.string.settings_export) { _, _ ->
                exportKeys = keys.isChecked
                val create = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json")
                    .putExtra(Intent.EXTRA_TITLE, "hiroi-settings-${java.time.LocalDate.now()}.json")
                @Suppress("DEPRECATION")
                startActivityForResult(create, REQUEST_EXPORT_SETTINGS)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun exportSettings(uri: Uri) {
        val message = runCatching {
            contentResolver.openOutputStream(uri, "wt")!!.use { SettingsFile.export(this, it, exportKeys) }
            getString(if (exportKeys) R.string.settings_exported_keys else R.string.settings_exported)
        }.getOrElse {
            Log.w(TAG, "Settings export failed", it)
            getString(R.string.settings_failed, it.message ?: it.javaClass.simpleName)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /** Reads a settings file, asks before replacing the settings, then shows them. */
    private fun importSettings(uri: Uri) {
        val backup = runCatching { contentResolver.openInputStream(uri)!!.use(SettingsFile::read) }.getOrElse {
            Toast.makeText(this, getString(R.string.settings_failed, it.message ?: it.javaClass.simpleName), Toast.LENGTH_LONG).show()
            return
        }
        val about = listOfNotNull(
            getString(R.string.settings_import_from, backup.exported.substringBefore('T'), backup.app),
            getString(R.string.settings_import_no_keys).takeIf { !backup.includesKeys },
        ).joinToString(" ")
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_import)
            .setMessage(getString(R.string.settings_import_confirm, about))
            .setPositiveButton(R.string.settings_import_button) { _, _ ->
                val message = runCatching {
                    val missing = SettingsFile.apply(this, backup)
                    if (missing.isEmpty()) getString(R.string.settings_imported) else getString(R.string.settings_imported_missing, missing.joinToString(", "))
                }.getOrElse {
                    Log.w(TAG, "Settings import failed", it)
                    getString(R.string.settings_failed, it.message ?: it.javaClass.simpleName)
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                // Every section shows the new settings. Not recreate(): it restores the old
                // switches and sliders, whose listeners would save them over the imported ones.
                finish()
                startActivity(Intent(this, MainActivity::class.java))
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        showNoteTypeStyles()
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
        showGrouping()
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
            info.images.takeIf { it > 0 }?.let { getString(R.string.count_images, it) },
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

    /** Yomitan's audio settings: the 🔊 button, its sources in order, auto-play, volume and fallback sound. */
    private fun setUpAudio() {
        val audio = AudioSettings(this)
        findViewById<CheckBox>(R.id.audio_enabled).apply {
            isChecked = audio.enabled
            setOnCheckedChangeListener { _, checked -> audio.enabled = checked }
        }
        findViewById<CheckBox>(R.id.audio_auto_play).apply {
            isChecked = audio.autoPlay
            setOnCheckedChangeListener { _, checked -> audio.autoPlay = checked }
        }
        val sources = findViewById<Button>(R.id.audio_sources)
        fun showSources() {
            sources.text = getString(R.string.audio_sources, audio.sources.joinToString(", ") { AudioSources.label(this, AudioSource(it.type)) }.ifEmpty { getString(R.string.sort_none) })
        }
        showSources()
        sources.setOnClickListener { AudioSources.edit(this, audio) { showSources() } }
        val volumeLabel = findViewById<TextView>(R.id.audio_volume_label)
        findViewById<SeekBar>(R.id.audio_volume).apply {
            progress = audio.volume
            volumeLabel.text = getString(R.string.audio_volume, audio.volume)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    audio.volume = value
                    volumeLabel.text = getString(R.string.audio_volume, value)
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }
        val fallback = findViewById<Button>(R.id.audio_fallback)
        val fallbackLabels = mapOf("none" to R.string.audio_fallback_none, "click" to R.string.audio_fallback_click, "bloop" to R.string.audio_fallback_bloop)
        fun showFallback() { fallback.text = getString(R.string.audio_fallback, getString(fallbackLabels.getValue(audio.fallbackSound))) }
        showFallback()
        fallback.setOnClickListener {
            val sounds = AudioSettings.FALLBACK_SOUNDS
            AlertDialog.Builder(this)
                .setSingleChoiceItems(sounds.map { getString(fallbackLabels.getValue(it)) }.toTypedArray(), sounds.indexOf(audio.fallbackSound)) { dialog, which ->
                    audio.fallbackSound = sounds[which]
                    showFallback()
                    dialog.dismiss()
                }
                .show()
        }
    }

    /** The language to translate into, the services in their order, automatic translation and ML Kit's model. */
    private fun setUpTranslation() {
        val settings = TranslationSettings(this)
        val target = findViewById<Button>(R.id.translation_target)
        fun showTarget() { target.text = getString(R.string.translation_target, TargetLanguage.of(settings.target).name) }
        showTarget()
        target.setOnClickListener {
            val languages = TargetLanguage.ALL
            AlertDialog.Builder(this)
                .setSingleChoiceItems(languages.map { it.name }.toTypedArray(), languages.indexOfFirst { it.code == settings.target }) { dialog, which ->
                    settings.target = languages[which].code
                    showTarget()
                    showModel()
                    dialog.dismiss()
                }
                .show()
        }
        val services = findViewById<Button>(R.id.translation_services)
        fun showServices() {
            services.text = getString(
                R.string.translation_services,
                settings.services.joinToString(", ") { TranslationServices.label(this, it, withDetail = false) }.ifEmpty { getString(R.string.sort_none) },
            )
        }
        showServices()
        services.setOnClickListener { TranslationServices.edit(this, settings) { showServices() } }
        findViewById<CheckBox>(R.id.translation_auto).apply {
            isChecked = settings.auto
            setOnCheckedChangeListener { _, checked -> settings.auto = checked }
        }
        findViewById<Button>(R.id.translation_model_download).setOnClickListener { modelAction(R.string.translation_model_downloading) { OnDeviceTranslation.download(it) } }
        findViewById<Button>(R.id.translation_model_delete).setOnClickListener { modelAction(R.string.translation_model_deleting) { OnDeviceTranslation.deleteModels() } }
        showModel()
    }

    /** Whether ML Kit's model for the target language is on the phone. */
    private fun showModel(state: String? = null) {
        val status = findViewById<TextView>(R.id.translation_model_status)
        val target = TranslationSettings(this).target
        val language = TargetLanguage.of(target).name
        if (state != null) {
            status.text = getString(R.string.translation_model_status, language, state)
            return
        }
        status.text = getString(R.string.translation_model_status, language, getString(R.string.translation_model_checking))
        translationWorker.execute {
            val ready = runCatching { OnDeviceTranslation.isReady(target) }.getOrDefault(false)
            runOnUiThread {
                if (isDestroyed || TranslationSettings(this).target != target) return@runOnUiThread
                status.text = getString(R.string.translation_model_status, language, getString(if (ready) R.string.translation_model_ready else R.string.translation_model_missing))
                findViewById<Button>(R.id.translation_model_download).isEnabled = !ready
                findViewById<Button>(R.id.translation_model_delete).isEnabled = ready
            }
        }
    }

    /** Downloads or deletes ML Kit's models in the background, then shows how that went. */
    private fun modelAction(working: Int, action: (target: String) -> Unit) {
        val target = TranslationSettings(this).target
        findViewById<Button>(R.id.translation_model_download).isEnabled = false
        findViewById<Button>(R.id.translation_model_delete).isEnabled = false
        showModel(getString(working))
        translationWorker.execute {
            val result = runCatching { action(target) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.exceptionOrNull()?.let { showModel(getString(R.string.translation_model_failed, it.message ?: it.javaClass.simpleName)) } ?: showModel()
            }
        }
    }

    private val groupingLabels = mapOf(
        "split" to R.string.grouping_split, "group" to R.string.grouping_group,
        "term" to R.string.grouping_term, "merge" to R.string.grouping_merge,
    )

    /** Yomitan's result grouping mode; for related terms, the primary and secondary dictionaries. */
    private fun showGrouping() {
        val settings = DictionarySettings(this)
        val mode = settings.resultGrouping
        val related = mode == "merge"
        val titles = dictionaries.dictionaries().map { it.title }
        findViewById<Button>(R.id.result_grouping).apply {
            text = getString(R.string.grouping_mode, getString(groupingLabels.getValue(mode)))
            setOnClickListener {
                val modes = Grouping.MODES
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.grouping_title)
                    .setSingleChoiceItems(modes.map { getString(groupingLabels.getValue(it)) }.toTypedArray(), modes.indexOf(settings.resultGrouping)) { dialog, which ->
                        settings.resultGrouping = modes[which]
                        showGrouping()
                        dialog.dismiss()
                    }
                    .show()
            }
        }
        for (id in listOf(R.id.related_hint, R.id.main_dictionary, R.id.secondary_dictionaries)) {
            findViewById<View>(id).visibility = if (related) View.VISIBLE else View.GONE
        }
        findViewById<Button>(R.id.main_dictionary).apply {
            text = getString(R.string.grouping_main, settings.mainDictionary.ifEmpty { getString(R.string.sort_none) })
            setOnClickListener {
                val choices = listOf("") + titles
                AlertDialog.Builder(this@MainActivity)
                    .setSingleChoiceItems(choices.map { it.ifEmpty { getString(R.string.sort_none) } }.toTypedArray(), choices.indexOf(settings.mainDictionary)) { dialog, which ->
                        settings.mainDictionary = choices[which]
                        showGrouping()
                        dialog.dismiss()
                    }
                    .show()
            }
        }
        findViewById<Button>(R.id.secondary_dictionaries).apply {
            val secondary = settings.secondaryDictionaries.filter { it in titles }
            text = getString(R.string.grouping_secondary, secondary.joinToString(", ").ifEmpty { getString(R.string.sort_none) })
            setOnClickListener {
                val checked = BooleanArray(titles.size) { titles[it] in settings.secondaryDictionaries }
                AlertDialog.Builder(this@MainActivity)
                    .setMultiChoiceItems(titles.toTypedArray(), checked) { _, which, on ->
                        settings.secondaryDictionaries = if (on) settings.secondaryDictionaries + titles[which] else settings.secondaryDictionaries - titles[which]
                    }
                    .setPositiveButton(R.string.done) { _, _ -> showGrouping() }
                    .setOnDismissListener { showGrouping() }
                    .show()
            }
        }
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
        const val REQUEST_EXPORT_SETTINGS = 5
        const val REQUEST_IMPORT_SETTINGS = 6

        const val EXAMPLE_ASSET = "example-dictionary.zip"
        const val EXAMPLE_DICTIONARY = "Hiroi example dictionary" // title in scripts/make-example-dictionary.py

        /** Imports and deletes run one at a time, and outlive the activity if the user leaves. */
        val background = Executors.newSingleThreadExecutor()

        /** For ML Kit's translation models, apart from imports (which can take minutes) */
        private val translationWorker = Executors.newSingleThreadExecutor()
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
