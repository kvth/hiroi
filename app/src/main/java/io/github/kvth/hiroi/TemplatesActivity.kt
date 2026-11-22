package io.github.kvth.hiroi

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.kvth.hiroi.anki.AnkiSettings
import io.github.kvth.hiroi.anki.AnkiTemplates
import io.github.kvth.hiroi.anki.CardData
import io.github.kvth.hiroi.anki.CardFormat
import io.github.kvth.hiroi.anki.Furigana
import io.github.kvth.hiroi.anki.YomitanRenderer
import io.github.kvth.hiroi.dictionary.DictionaryDb
import io.github.kvth.hiroi.dictionary.DictionarySettings
import io.github.kvth.hiroi.dictionary.Translator
import io.github.kvth.hiroi.japanese.Japanese
import java.util.concurrent.Executors

/**
 * Yomitan's "Anki card templates" page: the Handlebars templates that fill the card fields, to
 * edit, reset to Yomitan's default, and try out on a word, plus the glossary and tag layout.
 */
class TemplatesActivity : Activity() {

    private val anki by lazy { AnkiSettings(this) }
    private val background = Executors.newSingleThreadExecutor()
    private val dp by lazy { resources.displayMetrics.density }
    private lateinit var editor: EditText
    private lateinit var result: TextView
    private lateinit var default: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        default = AnkiTemplates.defaultTemplates(this)
        val pad = (16 * dp).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad)
        }
        fun text(id: Int, size: Float = 14f) = TextView(this).apply {
            setText(id)
            textSize = size
            setPadding(0, (8 * dp).toInt(), 0, 0)
            content.addView(this)
        }
        text(R.string.templates_intro).text = getString(R.string.templates_intro, AnkiTemplates.version(this))

        content.addView(CheckBox(this).apply {
            setText(R.string.templates_compact_glossaries)
            isChecked = anki.compactGlossaries
            setOnCheckedChangeListener { _, checked -> anki.compactGlossaries = checked }
        })
        content.addView(CheckBox(this).apply {
            setText(R.string.templates_compact_tags)
            isChecked = anki.compactTags
            setOnCheckedChangeListener { _, checked -> anki.compactTags = checked }
        })

        // Test: like Yomitan's, a word to look up and a field value with markers to fill for it.
        text(R.string.templates_test_title, 16f).setTypeface(null, Typeface.BOLD)
        val word = EditText(this).apply {
            setHint(R.string.templates_test_word)
            setText(getString(R.string.templates_test_word_default))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val field = EditText(this).apply {
            setHint(R.string.templates_test_field)
            setText("{expression} {furigana} {glossary-first}")
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        content.addView(word, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        content.addView(field, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        content.addView(Button(this).apply {
            setText(R.string.templates_test)
            setOnClickListener { test(word.text.toString(), field.text.toString()) }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        result = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            visibility = View.GONE
        }
        content.addView(result)

        text(R.string.templates_editor_title, 16f).setTypeface(null, Typeface.BOLD)
        val buttons = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        buttons.addView(Button(this).apply {
            setText(R.string.templates_reset)
            setOnClickListener {
                AlertDialog.Builder(this@TemplatesActivity)
                    .setMessage(R.string.templates_reset_confirm)
                    .setPositiveButton(R.string.templates_reset) { _, _ -> editor.setText(default) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        })
        content.addView(buttons)
        editor = EditText(this).apply {
            setText(anki.fieldTemplates ?: default)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(true)
            minLines = 20
        }
        content.addView(editor, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(content)
        })
    }

    /** Saved as they are typed, like Yomitan's; Yomitan's own text counts as using the default. */
    override fun onPause() {
        super.onPause()
        val text = editor.text.toString()
        anki.fieldTemplates = text.takeIf { it != default && it.isNotBlank() }
    }

    override fun onDestroy() {
        background.shutdownNow()
        super.onDestroy()
    }

    /** Fills [fieldValue] for the first word of [text] with the templates in the editor, without media. */
    private fun test(text: String, fieldValue: String) {
        val templates = editor.text.toString().takeIf { it != default }
        result.visibility = View.VISIBLE
        result.setText(R.string.looking_up)
        background.execute {
            val output = runCatching {
                val db = DictionaryDb.get(this)
                val settings = DictionarySettings(this)
                val translator = Translator(db, { settings.frequencySort(db) }, { settings.grouping() }) { settings.partsOfSpeechFilterOff }
                val start = text.indexOfFirst(Japanese::isJapanese)
                val term = (if (start < 0) null else translator.lookUp(text, start))?.terms?.firstOrNull()
                    ?: return@runCatching getString(R.string.templates_test_no_word)
                val data = CardData(
                    term, text, start, start + term.source.length, documentTitle = getString(R.string.app_name),
                    parse = { sentence ->
                        Furigana.forText(sentence) { t, i -> translator.lookUp(t, i)?.terms?.firstOrNull()?.let { Furigana.TermGroupLike(it.source, it.expression, it.reading) } }
                    },
                )
                val format = CardFormat(deck = "Test", model = "Test", fields = mapOf("Test" to fieldValue))
                val note = YomitanRenderer(this, anki, format, templates).render(data, null, null)
                val media = List(note.requirements.length()) { note.requirements.getJSONObject(it).optString("type") }.distinct()
                buildString {
                    append(note.fields["Test"].orEmpty())
                    if (note.errors.isNotEmpty()) append("\n\n").append(getString(R.string.templates_test_errors, note.errors.joinToString("\n")))
                    if (media.isNotEmpty()) append("\n\n").append(getString(R.string.templates_test_media, media.joinToString(", ")))
                }
            }.getOrElse { getString(R.string.templates_test_failed, it.message ?: it.javaClass.simpleName) }
            runOnUiThread { if (!isDestroyed) result.text = output }
        }
    }
}
