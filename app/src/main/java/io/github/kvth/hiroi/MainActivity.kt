package io.github.kvth.hiroi

import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import io.github.kvth.hiroi.anki.AnkiBackend
import io.github.kvth.hiroi.anki.AnkiDroidApi
import io.github.kvth.hiroi.anki.AnkiSettings
import io.github.kvth.hiroi.anki.CardFormat
import io.github.kvth.hiroi.anki.NoteTypeStyles
import io.github.kvth.hiroi.anki.AnkiMarkers
import io.github.kvth.hiroi.anki.AnkiTemplates
import io.github.kvth.hiroi.anki.YomitanCardFormat
import io.github.kvth.hiroi.anki.YomitanSettings
import io.github.kvth.hiroi.audio.AndroidLocalAudio
import io.github.kvth.hiroi.audio.AudioSettings
import io.github.kvth.hiroi.audio.AudioSource
import io.github.kvth.hiroi.audio.AudioSources
import io.github.kvth.hiroi.audio.LocalAudio
import io.github.kvth.hiroi.audio.LocalRecording
import io.github.kvth.hiroi.dictionary.DictionaryDb
import io.github.kvth.hiroi.dictionary.DictionaryFacts
import io.github.kvth.hiroi.dictionary.DictionaryInfo
import io.github.kvth.hiroi.dictionary.DictionarySettings
import io.github.kvth.hiroi.dictionary.ResultTabs
import io.github.kvth.hiroi.dictionary.DictionaryUpdates
import io.github.kvth.hiroi.dictionary.Grouping
import io.github.kvth.hiroi.dictionary.HiroiCollection
import io.github.kvth.hiroi.dictionary.choose
import io.github.kvth.hiroi.dictionary.frequencySort
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

/**
 * Setup screen: shows what is enabled, links to the system settings pages and manages dictionaries.
 * A home screen lists the settings' pages ([Page]); one shows at a time, back returns to the list.
 */
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
        findViewById<Button>(R.id.check_updates).setOnClickListener { checkForUpdates() }
        findViewById<Button>(R.id.export_collection).setOnClickListener {
            val create = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, "hiroi-dictionaries-${java.time.LocalDate.now()}.zip")
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
        showPage(savedInstanceState?.getString(KEY_PAGE)?.let(Page::valueOf) ?: pageFrom(intent))
        if (savedInstanceState == null) importFrom(intent)
    }

    /** The settings' pages, with their titles */
    private enum class Page(val view: Int, val title: Int) {
        SETUP(R.id.page_setup, R.string.page_setup),
        DICTIONARIES(R.id.page_dictionaries, R.string.page_dictionaries),
        RESULTS(R.id.page_results, R.string.page_results),
        AUDIO(R.id.page_audio, R.string.audio_title),
        TRANSLATION(R.id.page_translation, R.string.translation_title),
        ANKI(R.id.page_anki, R.string.page_anki),
        MARKS(R.id.page_marks, R.string.marks_section),
        SHARING(R.id.page_sharing, R.string.share_title),
        BACKUP(R.id.page_backup, R.string.settings_backup_title),
        USAGE(R.id.page_usage, R.string.page_usage),
    }

    /** The page showing; null while the home screen is */
    private var page: Page? = null

    /** The page an intent's [EXTRA_PAGE] names, e.g. `usage` (scripts/emulator.sh app) */
    private fun pageFrom(intent: Intent): Page? =
        intent.getStringExtra(EXTRA_PAGE)?.let { name -> Page.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }

    /** Shows [shown], from its top, or the home screen if null. */
    private fun showPage(shown: Page?) {
        page = shown
        findViewById<View>(R.id.home).visibility = if (shown == null) View.VISIBLE else View.GONE
        for (p in Page.entries) findViewById<View>(p.view).visibility = if (p == shown) View.VISIBLE else View.GONE
        setTitle(shown?.title ?: R.string.app_name)
        actionBar?.setDisplayHomeAsUpEnabled(shown != null)
        when (shown) {
            null -> showHome()
            else -> {
                if (shown == Page.SETUP) placeSetupSteps(onHome = false)
                // A database copied there by hand meanwhile
                if (shown == Page.AUDIO) showLocalAudio()
                findViewById<ScrollView>(shown.view).scrollTo(0, 0)
            }
        }
    }

    /**
     * The home screen. Until the accessibility service is on, it has the setup steps, then the
     * pages that are steps too (dictionaries, Anki, trying it) with their numbers, then the rest;
     * after that a list of all pages, each with a line on what's in it.
     */
    private fun showHome() {
        val ready = accessibilityServiceEnabled()
        placeSetupSteps(onHome = !ready)
        val list = findViewById<LinearLayout>(R.id.home_pages)
        list.removeAllViews()
        fun row(page: Page, summary: String, title: String = getString(page.title)) = list.addView(pageRow(title, summary) { showPage(page) })
        val installed = dictionaries.dictionaries().size
        val dictionariesSummary = if (installed == 0) getString(R.string.page_dictionaries_none)
        else resources.getQuantityString(R.plurals.page_dictionaries_summary, installed, installed)
        if (ready) {
            val assistant = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
            row(Page.SETUP, getString(R.string.page_setup_summary, getString(R.string.status_on), getString(if (assistant) R.string.status_on else R.string.status_off)))
            row(Page.DICTIONARIES, dictionariesSummary)
        } else {
            row(Page.DICTIONARIES, dictionariesSummary, getString(R.string.step_dictionaries))
            row(Page.ANKI, getString(R.string.page_anki_summary), getString(R.string.step_anki))
            row(Page.USAGE, getString(R.string.page_usage_summary), getString(R.string.step_example))
        }
        row(Page.RESULTS, getString(R.string.page_results_summary))
        row(Page.AUDIO, getString(R.string.page_audio_summary))
        row(Page.TRANSLATION, getString(R.string.page_translation_summary))
        if (ready) row(Page.ANKI, getString(R.string.page_anki_summary))
        row(Page.MARKS, getString(R.string.page_marks_summary))
        row(Page.SHARING, getString(R.string.page_sharing_summary))
        row(Page.BACKUP, getString(R.string.page_backup_summary))
        if (ready) row(Page.USAGE, getString(R.string.page_usage_summary))
    }

    /** The setup steps on the home screen, or on their own page. */
    private fun placeSetupSteps(onHome: Boolean) {
        val steps = findViewById<View>(R.id.setup_steps)
        val home = findViewById<LinearLayout>(R.id.home_setup)
        val into = if (onHome) home else findViewById<LinearLayout>(R.id.page_setup_content)
        if (steps.parent !== into) {
            (steps.parent as ViewGroup).removeView(steps)
            into.addView(steps)
        }
        home.visibility = if (onHome) View.VISIBLE else View.GONE
    }

    /** A home screen row: a page's [title] and [summary], opening it on a tap. */
    private fun pageRow(title: String, summary: String, open: () -> Unit): View {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
            background = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).run {
                getDrawable(0).also { recycle() }
            }
            setOnClickListener { open() }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = title
                    setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium)
                })
                addView(TextView(this@MainActivity).apply { text = summary })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "›"
                textSize = 24f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (page != null) showPage(null) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != android.R.id.home) return super.onOptionsItemSelected(item)
        showPage(null)
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PAGE, page?.name)
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
        findViewById<Button>(R.id.anki_backend).setOnClickListener {
            pickOne(R.string.anki_backend_title, AnkiSettings.BACKENDS, ::backendLabel, anki.backend) {
                anki.backend = it
                showAnkiBackend()
                if (anki.ankiDroid && AnkiDroidApi.installed(this) && !AnkiDroidApi.hasPermission(this)) askAnkiDroidPermission()
            }
        }
        findViewById<Button>(R.id.anki_permission).setOnClickListener { askAnkiDroidPermission() }
        showAnkiBackend()
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

    private fun backendLabel(backend: String) =
        getString(if (backend == "ankidroid") R.string.anki_backend_ankidroid else R.string.anki_backend_ankiconnect)

    /** The backend, with AnkiConnect's address and key, or whether Hiroi may use AnkiDroid. */
    private fun showAnkiBackend() {
        val ankiDroid = anki.ankiDroid
        findViewById<Button>(R.id.anki_backend).text = getString(R.string.anki_backend, backendLabel(anki.backend))
        val allowed = AnkiDroidApi.hasPermission(this)
        val installed = AnkiDroidApi.installed(this)
        findViewById<TextView>(R.id.anki_backend_hint).text = if (!ankiDroid) {
            getString(R.string.anki_backend_ankiconnect_hint)
        } else {
            getString(R.string.anki_backend_ankidroid_hint) + "\n" + getString(
                when {
                    !installed -> R.string.ankidroid_missing
                    !allowed -> R.string.ankidroid_permission_missing
                    else -> R.string.ankidroid_ready
                },
            )
        }
        findViewById<Button>(R.id.anki_permission).visibility = if (ankiDroid && installed && !allowed) View.VISIBLE else View.GONE
        for (id in listOf(R.id.anki_url, R.id.anki_key)) findViewById<View>(id).visibility = if (ankiDroid) View.GONE else View.VISIBLE
    }

    private fun askAnkiDroidPermission() = requestPermissions(arrayOf(AnkiDroidApi.PERMISSION), REQUEST_ANKIDROID)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_ANKIDROID) return
        showAnkiBackend()
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) showAnkiStatus(getString(R.string.ankidroid_permission_denied))
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
        findViewById<CheckBox>(R.id.cards_update_note_type).apply {
            isChecked = anki.updateNoteTypeCss
            setOnCheckedChangeListener { _, checked ->
                anki.updateNoteTypeCss = checked
                showNoteTypeStyles()
            }
        }
        findViewById<Button>(R.id.cards_note_type_css).setOnClickListener { exportNoteTypeStyles() }
        findViewById<Button>(R.id.cards_update_note_type_now).setOnClickListener { updateNoteTypeStyles() }
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
        val custom = anki.fieldTemplates?.let { AnkiMarkers.inlineMarkers(it) - AnkiMarkers.inlineMarkers(default).toSet() }.orEmpty()
        return (AnkiTemplates.standardMarkers(this) + AnkiMarkers.HIROI_MARKERS + AnkiMarkers.dynamicMarkers(dictionaries.enabledDictionaries()) + custom).distinct()
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

    /**
     * Whether the stylesheets kept for the note type changed since they were copied, or, with
     * Hiroi updating it, since Hiroi last put them there.
     */
    private fun showNoteTypeStyles() {
        val styles = NoteTypeStyles(this)
        val model = anki.cardFormat.model
        val updating = anki.dedupStyles && anki.updateNoteTypeCss
        findViewById<CheckBox>(R.id.cards_update_note_type).isEnabled = anki.dedupStyles
        findViewById<Button>(R.id.cards_update_note_type_now).isEnabled = styles.sheets.isNotEmpty() && model.isNotEmpty()
        findViewById<TextView>(R.id.cards_note_type_css_status).text = when {
            styles.sheets.isEmpty() -> getString(R.string.dedup_status_empty)
            updating && model.isNotEmpty() ->
                getString(if (styles.needsApplying(model)) R.string.dedup_status_pending else R.string.dedup_status_applied, model)
            styles.changedSinceCopied -> getString(R.string.dedup_status_changed)
            else -> getString(R.string.dedup_status_ok)
        }
    }

    /** Puts the stylesheets into the card format's note type now; how it went shows below the button. */
    private fun updateNoteTypeStyles() {
        val status = findViewById<TextView>(R.id.cards_note_type_css_status)
        val model = anki.cardFormat.model
        if (model.isEmpty()) {
            status.setText(R.string.dedup_update_no_model)
            return
        }
        val styles = NoteTypeStyles(this)
        val client = anki.client()
        status.setText(R.string.anki_asking)
        background.execute {
            val result = runCatching { styles.apply(client, model) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                showNoteTypeStyles()
                result.fold(
                    { changed -> if (changed != null) status.text = getString(if (changed) R.string.dedup_update_done else R.string.dedup_update_unchanged, model) },
                    { status.text = getString(R.string.anki_unreachable, it.message ?: it.javaClass.simpleName) },
                )
            }
        }
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

    private fun <T> askAnki(call: (AnkiBackend) -> T, onResult: (T) -> Unit) {
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
                val unknown = AnkiMarkers.unknownMarkers(format, templates ?: AnkiTemplates.defaultTemplates(this), dictionaries.enabledDictionaries())
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
        pageFrom(intent)?.let(::showPage)
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
            // A JSON file is a Yomitan settings export (scripts/emulator.sh yomitan); anything else a
            // dictionary, or a collection of them.
            if (intent.type != "application/json" && !uri.path.orEmpty().endsWith(".json")) {
                showPage(Page.DICTIONARIES)
                importDictionary(sizeOf(uri)) { contentResolver.openInputStream(uri)!! }
            } else {
                showPage(Page.ANKI)
                importYomitanSettings(uri)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQUEST_IMPORT && resultCode == RESULT_OK && uri != null) {
            importDictionary(sizeOf(uri)) { contentResolver.openInputStream(uri)!! }
        }
        if (requestCode == REQUEST_YOMITAN_SETTINGS && resultCode == RESULT_OK && uri != null) importYomitanSettings(uri)
        if (requestCode == REQUEST_EXPORT_COLLECTION && resultCode == RESULT_OK && uri != null) exportCollection(uri)
        if (requestCode == REQUEST_IMPORT_COLLECTION && resultCode == RESULT_OK && uri != null) importCollection(uri)
        if (requestCode == REQUEST_EXPORT_SETTINGS && resultCode == RESULT_OK && uri != null) exportSettings(uri)
        if (requestCode == REQUEST_IMPORT_SETTINGS && resultCode == RESULT_OK && uri != null) importSettings(uri)
        if (requestCode == REQUEST_LOCAL_AUDIO && resultCode == RESULT_OK && uri != null) importLocalAudio(uri)
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

    /** Runs [work] in the background with its progress in the import status line and bar, keeping the screen on. */
    private fun dictionaryWork(start: Int, work: ((DictionaryDb.Progress) -> Unit) -> String) {
        val buttons = listOf(R.id.import_dictionary, R.id.check_updates, R.id.export_collection, R.id.import_collection).map { findViewById<Button>(it) }
        buttons.forEach { it.isEnabled = false }
        val progress = startProgress(start)
        background.execute {
            val message = try {
                work(progress)
            } catch (e: Exception) {
                Log.w(TAG, "Dictionary work failed: ${e.message}", e)
                getString(R.string.collection_failed, e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                buttons.forEach { it.isEnabled = true }
                endProgress(message)
                showDictionaries()
            }
        }
    }

    /**
     * Shows [start] in the import status line, with the bar going, and keeps the screen on; what
     * it returns, called from any thread, shows how far the work is: a few times a second at most,
     * as some work tells it after each 64 kB.
     */
    private fun startProgress(start: Int, statusView: Int = R.id.import_status, barView: Int = R.id.import_progress): (DictionaryDb.Progress) -> Unit {
        val status = findViewById<TextView>(statusView)
        val bar = findViewById<ProgressBar>(barView)
        status.visibility = View.VISIBLE
        status.setText(start)
        bar.visibility = View.VISIBLE
        bar.isIndeterminate = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        var shown = 0L
        var known = false
        return { progress ->
            val now = SystemClock.elapsedRealtime()
            // At once when the bar goes from unknown to known or back, so a long last step shows as one
            if (now - shown >= 100 || (progress.total > 0) != known) {
                shown = now
                known = progress.total > 0
                val determinate = known
                runOnUiThread {
                    if (!isDestroyed) {
                        bar.isIndeterminate = !determinate
                        if (determinate) {
                            val permille = (progress.done * 1000 / progress.total).toInt().coerceIn(0, 1000)
                            bar.setProgress(permille, true)
                            status.text = getString(R.string.progress_percent, progress.text, permille / 10)
                        } else {
                            status.text = progress.text
                        }
                    }
                }
            }
        }
    }

    /** Shows [message] in the import status line, in place of the progress, and lets the screen turn off. */
    private fun endProgress(message: String, statusView: Int = R.id.import_status, barView: Int = R.id.import_progress) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        findViewById<ProgressBar>(barView).visibility = View.GONE
        findViewById<TextView>(statusView).text = message
    }

    /** The size of the file at [uri], or 0 if its app doesn't say */
    private fun sizeOf(uri: Uri): Long = runCatching {
        if (uri.scheme == ContentResolver.SCHEME_FILE) File(uri.path!!).length()
        else contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }
    }.getOrNull() ?: 0L

    private fun fileSize(bytes: Long): String = android.text.format.Formatter.formatShortFileSize(this, bytes)

    /** All dictionaries as one file (their own files, zipped), for Hiroi on another phone or a copy to keep. */
    private fun exportCollection(uri: Uri) = dictionaryWork(R.string.collection_exporting) { progress ->
        val count = contentResolver.openOutputStream(uri, "wt")!!.use { out ->
            dictionaries.exportCollection(out) { done, total -> progress(DictionaryDb.Progress(getString(R.string.collection_export_progress, done, total), done, total)) }
        }
        getString(R.string.collection_exported, count)
    }

    /** Dictionaries from a collection Hiroi exported, added after the ones here. */
    private fun importCollection(uri: Uri) = dictionaryWork(R.string.collection_importing) { progress ->
        contentResolver.openInputStream(uri)!!.use { input -> importCollection(input, sizeOf(uri), progress) }
    }

    /** Imports the collection in [input], of [size] bytes (0 if unknown); returns what to show. */
    private fun importCollection(input: InputStream, size: Long, progress: (DictionaryDb.Progress) -> Unit): String {
        val started = SystemClock.elapsedRealtime()
        val result = dictionaries.importCollection(input) { read ->
            progress(
                if (size > 0) DictionaryDb.Progress(getString(R.string.collection_import_progress_of, fileSize(read), fileSize(size)), read, size)
                else DictionaryDb.Progress(getString(R.string.collection_import_progress, fileSize(read))),
            )
        }
        Log.i(TAG, "Imported a collection of ${result.added.size} dictionaries in ${SystemClock.elapsedRealtime() - started} ms")
        return listOfNotNull(
            getString(R.string.collection_imported, result.added.size, result.added.joinToString(", ")),
            result.skipped.takeIf { it.isNotEmpty() }?.let { getString(R.string.collection_skipped, it.joinToString(", ")) },
        ).joinToString("\n")
    }

    override fun onResume() {
        super.onResume()
        setStatus(R.id.accessibility_status, accessibilityServiceEnabled())
        setStatus(R.id.assistant_status, getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT))
        showAnkiBackend()
        showNoteTypeStyles()
        showDictionaries()
        if (page == null) showHome()
    }

    /** Imports the dictionary, or collection, that [open] reads, of [size] bytes (0 if unknown). */
    private fun importDictionary(size: Long = 0, open: () -> InputStream) {
        val button = findViewById<Button>(R.id.import_dictionary)
        button.isEnabled = false
        val progress = startProgress(R.string.import_copying)
        background.execute {
            val message = try {
                open().buffered(1 shl 16).use { input ->
                    if (HiroiCollection.isCollection(input)) {
                        // Read as it comes: a collection can be gigabytes, too much to copy first
                        importCollection(input, size, progress)
                    } else {
                        // ZipFile needs random access, so copy the picked dictionary first.
                        val zip = File(cacheDir, "import.zip")
                        try {
                            zip.outputStream().use { out ->
                                val buffer = ByteArray(1 shl 16)
                                var copied = 0L
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    out.write(buffer, 0, n)
                                    copied += n
                                    progress(DictionaryDb.Progress(getString(R.string.import_copying), copied, size))
                                }
                            }
                            val started = SystemClock.elapsedRealtime()
                            val info = dictionaries.import(zip, progress)
                            Log.i(TAG, "Imported ${info.title} in ${SystemClock.elapsedRealtime() - started} ms: ${info.terms} terms, ${info.meta} meta, ${info.kanji} kanji")
                            getString(R.string.import_done, info.title)
                        } finally {
                            zip.delete()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Import failed: ${e.message}", e)
                getString(R.string.import_failed, e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                button.isEnabled = true
                endProgress(message)
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
                    text = describe(info) + (updates[info.id]?.let { "\n" + getString(R.string.update_available_line, it.revision) } ?: "")
                    alpha = if (info.enabled) 1f else 0.5f
                    // Its details and settings, as Yomitan's per dictionary
                    setOnClickListener { showDictionaryDetails(info) }
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
            // Yomitan's menu per dictionary
            row.addView(
                Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                    text = "⋮"
                    minWidth = 0
                    minimumWidth = 0
                    contentDescription = getString(R.string.dictionary_more, DictionarySettings(this@MainActivity).name(info.title))
                    setOnClickListener { showDictionaryMenu(this, info, installed.size) }
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

    /** As Yomitan's dictionary menu: details, rename, move to a place, delete. */
    private fun showDictionaryMenu(anchor: View, info: DictionaryInfo, count: Int) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.dictionary_details)
            menu.add(0, 2, 1, R.string.dictionary_rename)
            // Only dictionaries with definitions have a tab to show them in
            if (info.terms > 0) menu.add(0, 5, 2, R.string.dictionary_tab)
            menu.add(0, 3, 3, R.string.dictionary_move_to).isEnabled = count > 1
            menu.add(0, 4, 4, R.string.dictionary_delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> showDictionaryDetails(info)
                    2 -> renameDictionaryAlias(info)
                    5 -> askTab(info)
                    3 -> askMoveTo(info, count)
                    4 -> confirmDelete(info)
                }
                true
            }
        }.show()
    }

    /** Asks for the name to show for [info] (Yomitan's alias): Reset names it by its title again. */
    private fun renameDictionaryAlias(info: DictionaryInfo) {
        val settings = DictionarySettings(this)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            setSingleLine()
            setText(settings.name(info.title))
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dictionary_rename_title)
            .setMessage(getString(R.string.dictionary_rename_message, info.title))
            .setView(LinearLayout(this).apply {
                setPadding(pad, 0, pad, 0)
                addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP_CONTENT))
            })
            .setPositiveButton(R.string.dictionary_rename_save) { _, _ ->
                // As Yomitan, a blank name changes nothing
                if (input.text.isNotBlank()) settings.setAlias(info.title, input.text.toString())
                showDictionaries()
            }
            .setNeutralButton(R.string.dictionary_rename_reset) { _, _ ->
                settings.setAlias(info.title, null)
                showDictionaries()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Asks for the tab to show [info]'s definitions in ([ResultTabs]): Reset puts it in its language's again. */
    private fun askTab(info: DictionaryInfo) {
        background.execute {
            val language = dictionaries.languageTabs(enabledOnly = false)[info.title] ?: ResultTabs.JAPANESE
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val settings = DictionarySettings(this)
                val pad = (20 * resources.displayMetrics.density).toInt()
                val input = EditText(this).apply {
                    setSingleLine()
                    setText(settings.tabOverrides[info.title] ?: language)
                    selectAll()
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.dictionary_tab_title)
                    .setMessage(getString(R.string.dictionary_tab_message, settings.name(info.title), language))
                    .setView(LinearLayout(this).apply {
                        setPadding(pad, 0, pad, 0)
                        addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP_CONTENT))
                    })
                    .setPositiveButton(R.string.dictionary_rename_save) { _, _ -> settings.setTab(info.title, input.text.toString(), language) }
                    .setNeutralButton(R.string.dictionary_rename_reset) { _, _ -> settings.setTab(info.title, null, language) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    /** Asks for the place (1 to [count]) to move [info] to, as Yomitan's "Move to…". */
    private fun askMoveTo(info: DictionaryInfo, count: Int) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val place = dictionaries.dictionaries().indexOfFirst { it.id == info.id } + 1
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(place.toString())
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dictionary_move_title)
            .setMessage(getString(R.string.dictionary_move_message, DictionarySettings(this).name(info.title), count))
            .setView(LinearLayout(this).apply {
                setPadding(pad, 0, pad, 0)
                addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP_CONTENT))
            })
            .setPositiveButton(R.string.dictionary_move) { _, _ ->
                val to = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                background.execute {
                    dictionaries.moveTo(info.id, to.coerceIn(1, count) - 1)
                    runOnUiThread { if (!isDestroyed) showDictionaries() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun describe(info: DictionaryInfo): String = buildString {
        val name = DictionarySettings(this@MainActivity).name(info.title)
        append(name)
        if (info.revision.isNotEmpty()) append(" (").append(info.revision).append(')')
        if (name != info.title) append('\n').append(getString(R.string.dictionary_title_line, info.title))
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
    private fun showAudioSources() {
        val audio = AudioSettings(this)
        findViewById<Button>(R.id.audio_sources).text =
            getString(R.string.audio_sources, audio.sources.joinToString(", ") { AudioSources.label(this, AudioSource(it.type)) }.ifEmpty { getString(R.string.sort_none) })
    }

    /** The local audio database's size and recordings per source, or that there's none; its buttons as fit */
    private fun showLocalAudio() {
        background.execute {
            val file = AndroidLocalAudio.file(this)
            val counts = runCatching { AndroidLocalAudio.open(this)?.counts() }.getOrNull()
            // A database put there by hand: the first audio source, once (removing it there stays)
            val added = counts != null && AudioSettings(this).offerLocal("${file.length()}:${file.lastModified()}")
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (added) showAudioSources()
                val status = findViewById<TextView>(R.id.local_audio_status)
                status.visibility = View.VISIBLE
                status.text = if (counts == null) getString(R.string.local_audio_none) else {
                    val order = AudioSettings(this).localOrder
                    val sources = LocalAudio.sorted(counts.keys.map { LocalRecording(it, null, null, "") }, order).map { it.source }
                    getString(R.string.local_audio_info, fileSize(file.length()), sources.joinToString(" · ") { "${LocalAudio.sourceName(it)} ${"%,d".format(counts.getValue(it))}" })
                }
                findViewById<Button>(R.id.local_audio_order).isEnabled = counts != null && counts.size > 1
                findViewById<Button>(R.id.local_audio_remove).isEnabled = file.exists()
            }
        }
    }

    /**
     * Copies the local audio database at [uri] (android.db, made by the Local Audio Server add-on)
     * into Hiroi's files, checks it's one, and puts it first among the audio sources.
     */
    private fun importLocalAudio(uri: Uri) {
        val progress = startProgress(R.string.local_audio_copying, R.id.local_audio_status, R.id.local_audio_progress)
        background.execute {
            val target = AndroidLocalAudio.file(this)
            val copy = File(target.path + ".copying")
            val result = runCatching {
                val total = sizeOf(uri)
                val text = getString(R.string.local_audio_copying)
                contentResolver.openInputStream(uri)!!.use { input ->
                    copy.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 16)
                        var done = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            progress(DictionaryDb.Progress(text, done, total))
                        }
                    }
                }
                AndroidLocalAudio(copy).close() // throws if it isn't one
                AndroidLocalAudio.close()
                if (target.exists() && !target.delete()) throw java.io.IOException("Couldn't replace ${target.name}")
                if (!copy.renameTo(target)) throw java.io.IOException("Couldn't move ${copy.name} into place")
                val audio = AudioSettings(this)
                audio.offerLocal("${target.length()}:${target.lastModified()}")
            }
            if (result.isFailure) copy.delete()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                endProgress(
                    result.fold({ getString(R.string.local_audio_ready) }, { getString(R.string.local_audio_failed, it.message ?: it.javaClass.simpleName) }),
                    R.id.local_audio_status, R.id.local_audio_progress,
                )
                showAudioSources()
                if (result.isSuccess) showLocalAudio()
            }
        }
    }

    /** The order of the local audio database's sources, the first tried first */
    private fun orderLocalAudio() {
        background.execute {
            val counts = runCatching { AndroidLocalAudio.open(this)?.counts() }.getOrNull() ?: return@execute
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val audio = AudioSettings(this)
                val dp = resources.displayMetrics.density
                val list = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
                }
                fun render() {
                    list.removeAllViews()
                    val order = LocalAudio.sorted(counts.keys.map { LocalRecording(it, null, null, "") }, audio.localOrder).map { it.source }
                    order.forEachIndexed { i, source ->
                        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                        row.addView(TextView(this).apply { text = "${i + 1}. ${LocalAudio.sourceName(source)}" }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                        for ((label, by) in listOf("▲" to -1, "▼" to 1)) {
                            row.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                                text = label
                                minWidth = 0
                                minimumWidth = 0
                                isEnabled = i + by in order.indices
                                setOnClickListener {
                                    audio.localOrder = order.toMutableList().apply { add(i + by, removeAt(i)) }
                                    render()
                                }
                            })
                        }
                        list.addView(row)
                    }
                }
                render()
                AlertDialog.Builder(this)
                    .setTitle(R.string.local_audio_order_title)
                    .setView(list)
                    .setPositiveButton(R.string.done, null)
                    .setOnDismissListener { showLocalAudio() }
                    .show()
            }
        }
    }

    /** Deletes Hiroi's copy of the local audio database, after asking */
    private fun removeLocalAudio() {
        val file = AndroidLocalAudio.file(this)
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.local_audio_remove_confirm, fileSize(file.length())))
            .setPositiveButton(R.string.local_audio_remove) { _, _ ->
                AndroidLocalAudio.close()
                file.delete()
                findViewById<TextView>(R.id.local_audio_status).text = getString(R.string.local_audio_none)
                showLocalAudio()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

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
        showAudioSources()
        findViewById<Button>(R.id.audio_sources).setOnClickListener { AudioSources.edit(this, audio) { showAudioSources() } }
        showLocalAudio()
        findViewById<Button>(R.id.local_audio_choose).setOnClickListener {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            startActivityForResult(pick, REQUEST_LOCAL_AUDIO)
        }
        findViewById<Button>(R.id.local_audio_order).setOnClickListener { orderLocalAudio() }
        findViewById<Button>(R.id.local_audio_remove).setOnClickListener { removeLocalAudio() }
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

    /** Updates found by the last check, by dictionary */
    private val updates = java.util.concurrent.ConcurrentHashMap<Long, DictionaryUpdates.Update>()

    /** Asks each dictionary's update link for its latest revision, then offers the newer ones. */
    private fun checkForUpdates(only: DictionaryInfo? = null) {
        val status = findViewById<TextView>(R.id.import_status)
        val button = findViewById<Button>(R.id.check_updates)
        status.visibility = View.VISIBLE
        status.setText(R.string.updates_checking)
        button.isEnabled = false
        background.execute {
            val installed = (only?.let(::listOf) ?: dictionaries.dictionaries())
            val sources = installed.mapNotNull { info -> DictionaryUpdates.source(dictionaries.summary(info.id))?.let { info to it } }
            val found = LinkedHashMap<DictionaryInfo, DictionaryUpdates.Update>()
            val current = mutableListOf<String>()
            val failed = mutableListOf<String>()
            for ((info, source) in sources) {
                runCatching { DictionaryUpdates.check(source) }.fold(
                    { update -> if (update != null) found[info] = update else current += info.title },
                    { error ->
                        Log.w(TAG, "Update check of ${info.title} failed", error)
                        failed += "${info.title} (${error.message ?: error.javaClass.simpleName})"
                    },
                )
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                button.isEnabled = true
                for (info in installed) updates.remove(info.id)
                for ((info, update) in found) updates[info.id] = update
                status.text = when {
                    sources.isEmpty() -> getString(R.string.updates_none_updatable)
                    else -> listOfNotNull(
                        found.takeIf { it.isNotEmpty() }?.let { getString(R.string.updates_available, it.entries.joinToString(", ") { (i, u) -> "${i.title} → ${u.revision}" }) },
                        current.takeIf { it.isNotEmpty() }?.let { getString(R.string.updates_up_to_date, it.joinToString(", ")) },
                        failed.takeIf { it.isNotEmpty() }?.let { getString(R.string.updates_failed, it.joinToString(", ")) },
                    ).joinToString("\n")
                }
                showDictionaries()
                if (found.isNotEmpty()) confirmUpdate(found.keys.toList())
            }
        }
    }

    /** Asks before downloading (saying so on mobile data), then updates [list]. */
    private fun confirmUpdate(list: List<DictionaryInfo>) {
        val names = list.joinToString(", ") { info -> "${info.title} → ${updates[info.id]?.revision}" }
        val metered = getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered == true
        AlertDialog.Builder(this)
            .setTitle(R.string.updates_confirm_title)
            .setMessage(getString(R.string.updates_confirm, names) + if (metered) "\n\n" + getString(R.string.updates_metered) else "")
            .setPositiveButton(R.string.updates_update) { _, _ -> updateDictionaries(list) }
            .setNegativeButton(R.string.updates_later, null)
            .show()
    }

    /**
     * Downloads each new version and imports it in place of the old one; settings that name the
     * old one by its title (an update can change it: Jitendex and JMdict have their date in it)
     * then name the new one.
     */
    private fun updateDictionaries(list: List<DictionaryInfo>) = dictionaryWork(R.string.updates_updating) { progress ->
        val done = mutableListOf<String>()
        for (old in list) {
            val update = updates[old.id] ?: continue
            val zip = File(cacheDir, "update.zip")
            try {
                DictionaryUpdates.download(update.downloadUrl, zip) { bytes, total ->
                    progress(
                        if (total > 0) DictionaryDb.Progress(getString(R.string.updates_downloading_of, old.title, fileSize(bytes), fileSize(total)), bytes, total)
                        else DictionaryDb.Progress(getString(R.string.updates_downloading, old.title, fileSize(bytes))),
                    )
                }
                val info = dictionaries.replace(old.id, zip, progress)
                renameDictionary(old.title, info.title)
                updates.remove(old.id)
                done += info.title
            } finally {
                zip.delete()
            }
        }
        getString(R.string.updates_done, done.joinToString(", "))
    }

    /** Settings that name dictionary [old] name [new]: sorting, grouping, the part of speech switch, and the card format's per-dictionary markers. */
    private fun renameDictionary(old: String, new: String) {
        if (old == new) return
        DictionarySettings(this).rename(old, new)
        val format = anki.cardFormat
        val markers = listOf("single-glossary-", "single-frequency-number-").associate { "{$it${AnkiMarkers.kebabCase(old)}}" to "{$it${AnkiMarkers.kebabCase(new)}}" }
        val fields = format.fields.mapValuesTo(LinkedHashMap()) { (_, value) -> markers.entries.fold(value) { v, (from, to) -> v.replace(from, to) } }
        if (fields != format.fields) anki.cardFormat = format.copy(fields = fields)
    }

    /**
     * A dictionary's details, as Yomitan's: its switches (prefix wildcards, part of speech
     * filtering, deinflections), what its index.json and file say about it, and its updates.
     */
    private fun showDictionaryDetails(info: DictionaryInfo) {
        background.execute {
            val facts = dictionaries.facts(info.id)
            val summary = dictionaries.summary(info.id)
            runOnUiThread { if (!isDestroyed) showDictionaryDetails(info, facts, summary) }
        }
    }

    private fun showDictionaryDetails(info: DictionaryInfo, facts: DictionaryFacts?, summary: String?) {
        val settings = DictionarySettings(this)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val source = DictionaryUpdates.source(summary)
        val index = summary?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
        fun hint(text: String) = TextView(this).apply {
            this.text = text
            alpha = 0.7f
            setPadding(0, 0, 0, pad / 2)
        }
        fun switch(label: Int, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit = {}) = CheckBox(this).apply {
            setText(label)
            isChecked = on
            isEnabled = enabled
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(TextView(this@MainActivity).apply {
                setPadding(0, 0, 0, pad / 2)
                text = source?.let { getString(R.string.dictionary_settings_updates, android.net.Uri.parse(it.indexUrl).host) }
                    ?: getString(R.string.dictionary_settings_no_updates)
            })
            // Every dictionary has them in Hiroi, so it's not a switch
            addView(switch(R.string.dictionary_prefix_wildcards, on = true, enabled = false))
            addView(hint(getString(R.string.dictionary_prefix_wildcards_hint)))
            if (info.terms > 0) {
                addView(switch(R.string.dictionary_parts_of_speech, info.title !in settings.partsOfSpeechFilterOff) { on ->
                    settings.partsOfSpeechFilterOff = if (on) settings.partsOfSpeechFilterOff - info.title else settings.partsOfSpeechFilterOff + info.title
                })
                addView(hint(getString(R.string.dictionary_parts_of_speech_hint)))
                addView(switch(R.string.dictionary_deinflections, info.title !in settings.deinflectionsOff) { on ->
                    settings.deinflectionsOff = if (on) settings.deinflectionsOff - info.title else settings.deinflectionsOff + info.title
                })
                val forms = when (val n = info.forms) {
                    null -> getString(R.string.dictionary_deinflections_unknown)
                    0 -> getString(R.string.dictionary_deinflections_none)
                    else -> getString(R.string.dictionary_deinflections_count, n)
                }
                addView(hint(getString(R.string.dictionary_deinflections_hint) + " " + forms))
            }
            addView(View(this@MainActivity).apply { setBackgroundColor(0x33888888) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { setMargins(0, pad / 4, 0, pad / 2) })
            addView(TextView(this@MainActivity).apply {
                setTextIsSelectable(true)
                autoLinkMask = android.text.util.Linkify.WEB_URLS
                text = dictionaryDetails(info, facts, index).joinToString("\n") { (label, value) -> getString(R.string.dictionary_detail, getString(label), value) }
            })
        }
        val update = updates[info.id]
        AlertDialog.Builder(this)
            .setTitle(listOf(settings.name(info.title), info.revision).filter { it.isNotEmpty() }.joinToString(" "))
            .setView(ScrollView(this).apply { addView(form) })
            .apply {
                when {
                    update != null -> setPositiveButton(getString(R.string.dictionary_update_to, update.revision)) { _, _ -> confirmUpdate(listOf(info)) }
                    source != null -> setNeutralButton(R.string.updates_check) { _, _ -> checkForUpdates(info) }
                }
            }
            .setNegativeButton(R.string.done, null)
            .show()
    }

    /** The rows of Yomitan's dictionary details that [info] has, label and value: those of its index.json, then its counts. */
    private fun dictionaryDetails(info: DictionaryInfo, facts: DictionaryFacts?, index: org.json.JSONObject?): List<Pair<Int, String>> {
        val rows = ArrayList<Pair<Int, String>>()
        for ((label, key) in listOf(
            R.string.detail_author to "author", R.string.detail_url to "url", R.string.detail_description to "description",
            R.string.detail_attribution to "attribution", R.string.detail_source_language to "sourceLanguage", R.string.detail_target_language to "targetLanguage",
        )) {
            index?.optString(key)?.takeIf { it.isNotBlank() }?.let { rows += label to it }
        }
        val kanjiMeta = facts?.kanjiMeta
        for ((label, count) in listOf(
            R.string.detail_terms to info.terms,
            R.string.detail_term_meta to (if (kanjiMeta != null) info.meta - kanjiMeta else info.meta),
            R.string.detail_kanji to info.kanji,
            R.string.detail_kanji_meta to (kanjiMeta ?: 0),
            R.string.detail_tags to (facts?.tags ?: 0),
            R.string.detail_media to info.images,
        )) {
            if (count > 0) rows += label to count.toString()
        }
        info.frequencyMode?.let { rows += R.string.detail_frequency_mode to it }
        // Hiroi lists a dictionary once its import finished, and can search any with a wildcard first
        rows += R.string.detail_prefix_wildcards to "true"
        rows += R.string.detail_import_success to "true"
        return rows
    }

    private fun confirmDelete(info: DictionaryInfo) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.dictionary_delete_confirm, DictionarySettings(this).name(info.title)))
            .setPositiveButton(R.string.dictionary_delete) { _, _ ->
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
        const val EXTRA_PAGE = "page"
        const val KEY_PAGE = "page"
        const val REQUEST_IMPORT = 1
        const val REQUEST_YOMITAN_SETTINGS = 2
        const val REQUEST_EXPORT_COLLECTION = 3
        const val REQUEST_IMPORT_COLLECTION = 4
        const val REQUEST_EXPORT_SETTINGS = 5
        const val REQUEST_IMPORT_SETTINGS = 6
        const val REQUEST_ANKIDROID = 7
        const val REQUEST_LOCAL_AUDIO = 8

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
