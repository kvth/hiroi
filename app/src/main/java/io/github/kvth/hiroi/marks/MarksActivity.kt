package io.github.kvth.hiroi.marks

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.format.DateUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import io.github.kvth.hiroi.R
import io.github.kvth.hiroi.anki.AnkiBackend
import io.github.kvth.hiroi.anki.AnkiSettings
import java.time.LocalDate
import java.util.concurrent.Executors

/**
 * Settings for marking the words you have in Anki, the same as AnkiMark's: rules, base filter,
 * fields and field script, display, the apps it's off for, syncing and backup.
 */
class MarksActivity : Activity() {

    private val settings by lazy { MarkSettings(this) }
    private val anki by lazy { AnkiSettings(this) }
    private val background = Executors.newSingleThreadExecutor()
    private val dp by lazy { resources.displayMetrics.density }

    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var message: TextView
    private lateinit var syncButton: Button
    private lateinit var syncMinutesButton: Button
    private lateinit var rulesBox: LinearLayout
    private lateinit var fieldsBox: LinearLayout
    private lateinit var appsBox: LinearLayout
    private lateinit var baseQuery: EditText
    private lateinit var script: EditText
    private lateinit var testResults: TextView
    private var ruleResults: Map<String, Result<Int>> = emptyMap()
    private var loadedFields: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * dp).toInt()
            setPadding(pad, pad / 2, pad, pad)
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(content)
        })
        build()
    }

    override fun onResume() {
        super.onResume()
        // The overlay's switches change these too.
        if (::appsBox.isInitialized) renderApps()
        showStatus()
    }

    override fun onDestroy() {
        background.shutdownNow()
        super.onDestroy()
    }

    private fun build() {
        content.removeAllViews()
        hint(R.string.marks_intro)
        message = TextView(this).apply { visibility = View.GONE; setPadding(0, (8 * dp).toInt(), 0, 0) }
        content.addView(message)
        check(R.string.marks_enabled, settings.enabled) { settings.enabled = it }
        status = TextView(this)
        content.addView(status)
        syncButton = button(R.string.marks_sync) { sync() }
        content.addView(syncButton, wrap())

        title(R.string.marks_base_title)
        hint(R.string.marks_base_hint)
        baseQuery = input(settings.baseQuery, R.string.marks_base_placeholder, mono = true) {
            settings.baseQuery = it
            ruleResults = emptyMap()
            renderRules()
            showStatus()
        }
        content.addView(button(R.string.marks_use_deck) {
            askAnki({ it.deckNames() }) { decks ->
                AlertDialog.Builder(this).setTitle(R.string.anki_choose_deck)
                    .setItems(decks.toTypedArray()) { _, which -> baseQuery.setText(AnkiBackend.deckQuery(decks[which])) }
                    .show()
            }
        }, wrap())

        title(R.string.marks_rules_title)
        hint(R.string.marks_rules_hint)
        rulesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(rulesBox)
        renderRules()
        row(
            button(R.string.marks_add_rule) { addRule(MarkRule("", getString(R.string.marks_new_rule), "", "#408cff")) },
            button(R.string.marks_check) { checkRules() },
        )
        row(
            button(R.string.marks_examples) {
                val examples = MarkSettings.EXAMPLES
                AlertDialog.Builder(this).setTitle(R.string.marks_examples)
                    .setItems(examples.map { "${it.name}   ${it.query.ifEmpty { getString(R.string.marks_everything) }}" }.toTypedArray()) { _, which ->
                        addRule(examples[which])
                    }
                    .show()
            },
            button(R.string.marks_reset) {
                AlertDialog.Builder(this).setMessage(R.string.marks_reset_confirm)
                    .setPositiveButton(R.string.marks_reset) { _, _ ->
                        settings.rules = MarkSettings.DEFAULT_RULES
                        ruleResults = emptyMap()
                        renderRules()
                        showStatus()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            },
        )

        title(R.string.marks_fields_title)
        hint(R.string.marks_fields_hint)
        fieldsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(fieldsBox)
        renderFields()
        content.addView(button(R.string.marks_load_fields) { loadFields() }, wrap())

        subtitle(R.string.marks_script_title)
        hint(R.string.marks_script_hint)
        content.addView(mono("function (note) {"))
        script = input(settings.fieldScript, R.string.marks_script_placeholder, mono = true, lines = 8) {
            settings.fieldScript = it
            showStatus()
        }
        content.addView(mono("}"))
        content.addView(button(R.string.marks_script_example) {
            val current = script.text.toString()
            if (current.isBlank() || current == MarkSettings.FORMS_SCRIPT) {
                script.setText(MarkSettings.FORMS_SCRIPT)
            } else {
                AlertDialog.Builder(this).setMessage(R.string.marks_script_replace)
                    .setPositiveButton(android.R.string.ok) { _, _ -> script.setText(MarkSettings.FORMS_SCRIPT) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }, wrap())
        val testSearch = EditText(this).apply {
            setHint(R.string.marks_test_search)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val testRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        testRow.addView(button(R.string.marks_test) { testFields(testSearch.text.toString()) })
        testRow.addView(testSearch, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        content.addView(testRow)
        testResults = mono("").apply { visibility = View.GONE }
        content.addView(testResults)

        title(R.string.marks_display_title)
        val style = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        for ((value, label) in listOf("background" to R.string.marks_style_background, "underline" to R.string.marks_style_underline)) {
            style.addView(RadioButton(this).apply {
                id = View.generateViewId()
                setText(label)
                isChecked = settings.style == value
                setOnCheckedChangeListener { _, checked -> if (checked) settings.style = value }
            })
        }
        content.addView(style)
        val strength = TextView(this)
        fun showStrength() { strength.text = getString(R.string.marks_strength, settings.opacity) }
        showStrength()
        content.addView(strength)
        content.addView(SeekBar(this).apply {
            max = (80 - 10) / 5
            progress = (settings.opacity - 10) / 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    settings.opacity = 10 + value * 5
                    showStrength()
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        })
        check(R.string.marks_conjugations, settings.conjugations) { settings.conjugations = it }
        hint(R.string.marks_conjugations_hint)
        check(R.string.marks_kana_variants, settings.kanaVariants) { settings.kanaVariants = it }
        hint(R.string.marks_kana_variants_hint)
        val minLen = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        minLen.addView(TextView(this).apply { setText(R.string.marks_kana_min_len) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        minLen.addView(EditText(this).apply {
            setText(settings.kanaMinLen.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            minEms = 2
            onChange { text -> text.toIntOrNull()?.let { settings.kanaMinLen = it } }
        })
        content.addView(minLen)

        title(R.string.marks_apps_title)
        hint(R.string.marks_apps_hint)
        appsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(appsBox)
        renderApps()
        content.addView(button(R.string.marks_add_app) { addApp() }, wrap())

        title(R.string.marks_sync_title)
        syncMinutesButton = button(0) { chooseSyncMinutes() }
        showSyncMinutes()
        content.addView(syncMinutesButton, wrap())
        hint(R.string.marks_sync_hint)
        val batch = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        batch.addView(TextView(this).apply { setText(R.string.marks_batch) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        batch.addView(EditText(this).apply {
            setText(settings.notesBatch.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            minEms = 3
            onChange { text -> text.toIntOrNull()?.let { settings.notesBatch = it } }
        })
        content.addView(batch)
        hint(R.string.marks_batch_hint)

        title(R.string.marks_backup_title)
        hint(R.string.marks_backup_hint)
        row(
            button(R.string.marks_export) {
                val create = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json")
                    .putExtra(Intent.EXTRA_TITLE, "hiroi-marks-${LocalDate.now()}.json")
                @Suppress("DEPRECATION")
                startActivityForResult(create, REQUEST_EXPORT)
            },
            button(R.string.marks_import) {
                val open = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                @Suppress("DEPRECATION")
                startActivityForResult(open, REQUEST_IMPORT)
            },
        )
        showStatus()
    }

    // ---- Status and sync ------------------------------------------------------

    private fun showStatus() {
        if (!::status.isInitialized) return
        val signature = settings.syncSignature()
        val noBase = settings.baseQuery.isBlank()
        background.execute {
            val info = KnownWords.syncInfo(this)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val lines = mutableListOf(
                    if (info == null) getString(R.string.marks_not_synced)
                    else getString(R.string.marks_status, info.words, info.notes, DateUtils.getRelativeTimeSpanString(info.time)),
                )
                if (info != null && info.signature != signature) lines += getString(R.string.marks_stale)
                if (noBase) lines += getString(R.string.marks_no_base)
                status.text = lines.joinToString("\n")
            }
        }
    }

    private fun sync() {
        syncButton.isEnabled = false
        syncButton.setText(R.string.marks_syncing)
        showMessage("")
        KnownWords.sync(this) { result ->
            if (isDestroyed) return@sync
            syncButton.isEnabled = true
            syncButton.setText(R.string.marks_sync)
            result.fold(
                { showMessage(getString(R.string.marks_synced, it.words, it.notes)) },
                { showMessage(getString(R.string.marks_sync_failed, it.message ?: it.javaClass.simpleName), error = true) },
            )
            showStatus()
        }
    }

    private fun showMessage(text: String, error: Boolean = false) {
        message.text = text
        message.setTextColor(if (error) ERROR_COLOR else status.currentTextColor)
        message.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---- Rules ----------------------------------------------------------------

    private fun renderRules() {
        rulesBox.removeAllViews()
        val rules = settings.rules
        rules.forEachIndexed { i, rule ->
            val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(CheckBox(this).apply {
                isChecked = rule.enabled
                setOnCheckedChangeListener { _, checked -> updateRule(rule.id) { it.copy(enabled = checked) } }
            })
            top.addView(View(this).apply {
                background = swatch(rule.argb)
                setOnClickListener { pickColor(rule.color) { color -> updateRule(rule.id) { it.copy(color = color) }; renderRules() } }
            }, LinearLayout.LayoutParams((28 * dp).toInt(), (28 * dp).toInt()).apply { marginEnd = (8 * dp).toInt() })
            top.addView(EditText(this).apply {
                setText(rule.name)
                setHint(R.string.marks_rule_name)
                inputType = InputType.TYPE_CLASS_TEXT
                onChange { name -> updateRule(rule.id) { it.copy(name = name) } }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            top.addView(small("▲", enabled = i > 0) { moveRule(i, -1) })
            top.addView(small("▼", enabled = i < rules.size - 1) { moveRule(i, 1) })
            top.addView(small("✕") {
                settings.rules = settings.rules.filter { it.id != rule.id }
                renderRules()
                showStatus()
            })
            rulesBox.addView(top)
            rulesBox.addView(EditText(this).apply {
                setText(rule.query)
                setHint(R.string.marks_rule_query)
                typeface = Typeface.MONOSPACE
                textSize = 14f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                onChange { query ->
                    updateRule(rule.id) { it.copy(query = query) }
                    showStatus()
                }
            })
            ruleResults[rule.id]?.let { result ->
                rulesBox.addView(TextView(this).apply {
                    textSize = 13f
                    text = result.fold({ resources.getQuantityString(R.plurals.marks_rule_notes, it, it) }, { it.message })
                    if (result.isFailure) setTextColor(ERROR_COLOR)
                })
            }
        }
    }

    private fun updateRule(id: String, change: (MarkRule) -> MarkRule) {
        settings.rules = settings.rules.map { if (it.id == id) change(it) else it }
    }

    private fun moveRule(i: Int, by: Int) {
        val rules = settings.rules.toMutableList()
        rules.add(i + by, rules.removeAt(i))
        settings.rules = rules
        renderRules()
    }

    private fun addRule(rule: MarkRule) {
        settings.rules = settings.rules + rule.copy(id = MarkRule.newId(), enabled = true)
        renderRules()
        showStatus()
    }

    private fun checkRules() {
        val base = settings.baseQuery
        val rules = settings.rules
        askAnki({ MarkSync(it) { _, _ -> emptyList() }.checkRules(base, rules) }) {
            ruleResults = it
            renderRules()
        }
    }

    /** The colours of AnkiMark's rules and examples, and a few more; or any "#rrggbb". */
    private fun pickColor(current: String, onPicked: (String) -> Unit) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * dp).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val hex = EditText(this).apply {
            setText(current)
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        var dialog: AlertDialog? = null
        PALETTE.chunked(6).forEach { colors ->
            val line = LinearLayout(this)
            for (color in colors) {
                line.addView(View(this).apply {
                    background = swatch(MarkRule.parseColor(color))
                    setOnClickListener {
                        onPicked(color)
                        dialog?.dismiss()
                    }
                }, LinearLayout.LayoutParams(0, (40 * dp).toInt(), 1f).apply { setMargins((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt()) })
            }
            form.addView(line)
        }
        form.addView(hex)
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.marks_color)
            .setView(form)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val color = hex.text.toString().trim().let { if (it.startsWith("#")) it else "#$it" }
                if (MarkRule.isColor(color)) onPicked(color.lowercase())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun swatch(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = 6 * dp
    }

    // ---- Fields and field script ------------------------------------------------

    private fun renderFields() {
        fieldsBox.removeAllViews()
        val chosen = settings.fields
        // Keep chosen fields visible even if the sample didn't contain them.
        val names = (loadedFields + chosen).distinct()
        if (names.isEmpty()) {
            fieldsBox.addView(TextView(this).apply { setText(R.string.marks_fields_none) })
            return
        }
        for (name in names) {
            fieldsBox.addView(CheckBox(this).apply {
                text = name
                isChecked = name in chosen
                setOnCheckedChangeListener { _, checked ->
                    val now = settings.fields
                    settings.fields = if (checked) names.filter { it in now || it == name } else now - name
                    showStatus()
                }
            })
        }
    }

    private fun loadFields() {
        val query = MarkSettings.unionQuery(settings.baseQuery, settings.rules)
        if (query == null) {
            showMessage(getString(R.string.marks_need_search), error = true)
            return
        }
        askAnki({ client -> client.notesInfo(client.findNotes(query).take(300)).flatMap { it.fields.keys }.distinct() }) {
            loadedFields = it
            renderFields()
        }
    }

    /** The words of a few notes, from the fields and from the field script, to try them on. */
    private fun testFields(search: String) {
        val query = MarkSettings.ruleQuery(MarkSettings.unionQuery(settings.baseQuery, settings.rules).orEmpty(), search)
        if (query == null) {
            showMessage(getString(R.string.marks_need_search), error = true)
            return
        }
        val fields = settings.fields
        val code = settings.fieldScript.trim()
        askAnki({ client ->
            val notes = client.notesInfo(client.findNotes(query).take(5))
            val scripted = if (code.isEmpty()) emptyList() else FieldScript(this).use { it.run(code, notes) }
            notes to scripted
        }) { (notes, scripted) ->
            testResults.visibility = View.VISIBLE
            testResults.text = if (notes.isEmpty()) getString(R.string.marks_test_none) else notes.mapIndexed { i, note ->
                val words = { list: List<String> -> list.ifEmpty { listOf("—") }.joinToString("、") }
                buildString {
                    append(getString(R.string.marks_test_note, note.id))
                    append('\n').append(getString(R.string.marks_test_fields, words(MarkSync.noteWords(note, fields))))
                    val r = scripted.getOrNull(i)
                    if (r != null) {
                        val script = r.error ?: words(r.words.orEmpty().flatMap(NoteWords::extractWords).distinct())
                        append('\n').append(getString(R.string.marks_test_script, script))
                    }
                }
            }.joinToString("\n\n")
        }
    }

    // ---- Apps it's off for ------------------------------------------------------

    private fun renderApps() {
        appsBox.removeAllViews()
        val apps = settings.excludedApps.sortedBy { appLabel(it).lowercase() }
        if (apps.isEmpty()) appsBox.addView(TextView(this).apply { setText(R.string.marks_apps_none) })
        for (app in apps) {
            val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            line.addView(TextView(this).apply { text = getString(R.string.marks_app, appLabel(app), app) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            line.addView(small("✕") {
                settings.setOffFor(app, false)
                renderApps()
            })
            appsBox.addView(line)
        }
    }

    private fun addApp() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != packageName && it !in settings.excludedApps }
            .map { it to appLabel(it) }
            .sortedBy { it.second.lowercase() }
        AlertDialog.Builder(this)
            .setTitle(R.string.marks_add_app)
            .setItems(apps.map { it.second }.toTypedArray()) { _, which ->
                settings.setOffFor(apps[which].first, true)
                renderApps()
            }
            .show()
    }

    private fun appLabel(packageName: String): String =
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString() }.getOrDefault(packageName)

    // ---- Syncing ----------------------------------------------------------------

    private fun showSyncMinutes() {
        syncMinutesButton.text = getString(R.string.marks_sync_auto, syncLabel(settings.syncMinutes))
    }

    private fun syncLabel(minutes: Int) = when (minutes) {
        0 -> getString(R.string.marks_sync_manual)
        else -> resources.getQuantityString(R.plurals.marks_sync_minutes, minutes, minutes)
    }

    private fun chooseSyncMinutes() {
        val choices = MarkSettings.SYNC_MINUTES
        AlertDialog.Builder(this)
            .setTitle(R.string.marks_sync_title)
            .setSingleChoiceItems(choices.map(::syncLabel).toTypedArray(), choices.indexOf(settings.syncMinutes)) { dialog, which ->
                settings.syncMinutes = choices[which]
                dialog.dismiss()
                showSyncMinutes()
            }
            .show()
    }

    // ---- Backup -----------------------------------------------------------------

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        try {
            when (requestCode) {
                REQUEST_EXPORT -> {
                    val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "dev"
                    contentResolver.openOutputStream(uri, "wt")!!.use { it.write((settings.export(version).toString(2) + "\n").toByteArray()) }
                    showMessage(getString(R.string.marks_exported))
                }
                REQUEST_IMPORT -> {
                    val parsed = MarkSettings.parseImport(contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
                    settings.apply(parsed)
                    ruleResults = emptyMap()
                    loadedFields = emptyList()
                    build()
                    val rules = parsed.rules?.let { resources.getQuantityString(R.plurals.marks_imported_rules, it.size, it.size) }.orEmpty()
                    val warning = if (parsed.fieldScript.orEmpty().isNotBlank()) " " + getString(R.string.marks_imported_script) else ""
                    showMessage(getString(R.string.marks_imported, rules) + warning)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Backup failed", e)
            showMessage(getString(R.string.marks_backup_failed, e.message ?: e.javaClass.simpleName), error = true)
        }
    }

    // ---- Helpers ------------------------------------------------------------------

    private fun <T> askAnki(call: (AnkiBackend) -> T, onResult: (T) -> Unit) {
        showMessage(getString(R.string.anki_asking))
        val client = anki.client(60_000)
        background.execute {
            val result = runCatching { call(client) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.fold(
                    { showMessage(""); onResult(it) },
                    { showMessage(getString(R.string.anki_unreachable, it.message ?: it.javaClass.simpleName), error = true) },
                )
            }
        }
    }

    private fun title(text: Int) {
        content.addView(TextView(this).apply {
            setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium)
            setText(text)
            setPadding(0, (24 * dp).toInt(), 0, 0)
        })
    }

    private fun subtitle(text: Int) {
        content.addView(TextView(this).apply {
            setText(text)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (16 * dp).toInt(), 0, 0)
        })
    }

    private fun hint(text: Int) {
        content.addView(TextView(this).apply {
            setText(text)
            textSize = 13f
            alpha = 0.75f
            setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
        })
    }

    private fun mono(text: String) = TextView(this).apply {
        this.text = text
        typeface = Typeface.MONOSPACE
        textSize = 13f
    }

    private fun check(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
        content.addView(CheckBox(this).apply {
            setText(label)
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChange(value); showStatus() }
        })
    }

    private fun input(value: String, hint: Int, mono: Boolean = false, lines: Int = 1, save: (String) -> Unit) = EditText(this).apply {
        setText(value)
        setHint(hint)
        if (mono) {
            typeface = Typeface.MONOSPACE
            textSize = 14f
        }
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            (if (lines > 1) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
        if (lines > 1) {
            minLines = lines
            gravity = Gravity.TOP or Gravity.START
            setHorizontallyScrolling(false)
        }
        onChange(save)
        content.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun button(label: Int, onClick: () -> Unit) = Button(this).apply {
        if (label != 0) setText(label)
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun small(label: String, enabled: Boolean = true, onClick: () -> Unit) =
        Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            minWidth = 0
            minimumWidth = 0
            setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
            isEnabled = enabled
            setOnClickListener { onClick() }
        }

    private fun row(vararg views: View) {
        val line = LinearLayout(this)
        for (view in views) line.addView(view, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        content.addView(line)
    }

    private fun wrap() = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)

    private fun EditText.onChange(save: (String) -> Unit) = addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = save(s.toString())
    })

    private companion object {
        const val TAG = "HiroiMarks"
        const val REQUEST_EXPORT = 1
        const val REQUEST_IMPORT = 2
        const val ERROR_COLOR = 0xFFE5484D.toInt()

        val PALETTE = listOf(
            "#e5484d", "#ff6b3d", "#ffa600", "#f5d90a", "#28c85a", "#00b3b3",
            "#408cff", "#6e56cf", "#b45cff", "#e93d82", "#9a9aa3", "#8d6e63",
        )
    }
}
