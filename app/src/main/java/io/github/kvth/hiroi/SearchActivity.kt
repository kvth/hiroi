package io.github.kvth.hiroi

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.kvth.hiroi.dictionary.Meanings
import io.github.kvth.hiroi.dictionary.Wildcard
import io.github.kvth.hiroi.japanese.Japanese
import io.github.kvth.hiroi.japanese.Romaji
import io.github.kvth.hiroi.marks.KnownWords
import io.github.kvth.hiroi.marks.MarkSettings
import io.github.kvth.hiroi.marks.MarkStyle
import io.github.kvth.hiroi.marks.MarkedTextView
import io.github.kvth.hiroi.translation.TranslationStrip
import java.util.concurrent.Executors

/**
 * Searches the dictionaries, like Yomitan's search page: results for the text typed, from its
 * first Japanese character, as it is typed. The text is shown below the search box too, where
 * tapping a character looks up the word starting there. Selected text from other apps can be
 * sent here ("Hiroi search" in the selection menu).
 */
class SearchActivity : Activity() {

    private lateinit var lookup: DictionaryLookup
    private lateinit var input: EditText
    private lateinit var queryView: MarkedTextView
    private lateinit var queryScroll: ScrollView
    private lateinit var message: TextView
    private lateinit var translation: TranslationStrip

    private val main = Handler(Looper.getMainLooper())
    private val marker = Executors.newSingleThreadExecutor()
    private val dp by lazy { resources.displayMetrics.density }
    private val searchSoon = Runnable { search() }

    private val prefs by lazy { getSharedPreferences("hiroi", MODE_PRIVATE) }

    /** Typed romaji is searched as kana (taberu → たべる, TEREBI → テレビ), like Yomitan's search box. */
    private var romaji: Boolean
        get() = prefs.getBoolean("search_romaji", true)
        set(value) = prefs.edit().putBoolean("search_romaji", value).apply()

    /** The app the text came from, for a card's {document-title}; empty when typed here. */
    private var source = ""

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lookup = DictionaryLookup(this, { source }, { null })
        val pad = (16 * dp).toInt()

        input = EditText(this).apply {
            setHint(R.string.search_hint)
            textSize = 20f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, action, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (action == EditorInfo.IME_ACTION_SEARCH || enter) {
                    main.removeCallbacks(searchSoon)
                    search()
                    hideKeyboard()
                    true
                } else {
                    false
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    // Search as you type, once typing pauses (and the IME has committed its conversion).
                    main.removeCallbacks(searchSoon)
                    main.postDelayed(searchSoon, SEARCH_DELAY_MS)
                }
            })
        }
        val clear = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "✕"
            minWidth = 0
            minimumWidth = 0
            contentDescription = getString(R.string.search_clear)
            setOnClickListener {
                input.setText("")
                input.requestFocus()
                getSystemService(InputMethodManager::class.java).showSoftInput(input, 0)
            }
        }
        val romajiToggle = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "a→あ"
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            fun showState() {
                alpha = if (romaji) 1f else 0.4f
                contentDescription = getString(if (romaji) R.string.search_romaji_on else R.string.search_romaji_off)
                tooltipText = contentDescription
            }
            showState()
            setOnClickListener {
                romaji = !romaji
                showState()
                Toast.makeText(this@SearchActivity, contentDescription, Toast.LENGTH_SHORT).show()
                search()
            }
        }
        val searchRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(romajiToggle)
            addView(clear)
        }

        queryView = MarkedTextView(this).apply {
            textSize = 22f
            setLineSpacing(0f, 1.2f)
            setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
        }
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val offset = queryView.characterAt(e.x, e.y)
                if (offset >= 0) {
                    hideKeyboard()
                    lookUpAt(offset)
                }
                return true
            }
        })
        queryView.setOnTouchListener { _, event -> taps.onTouchEvent(event); true }
        val maxQueryHeight = (resources.displayMetrics.heightPixels * 0.25f).toInt()
        queryScroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(maxQueryHeight, MeasureSpec.AT_MOST),
            )
        }
        queryScroll.addView(queryView)
        // Translates what was searched
        translation = TranslationStrip(this) { queryView.text.toString().trim().takeIf { it.any(Japanese::isJapanese) } }

        message = TextView(this).apply {
            textSize = 16f
            setPadding(0, (8 * dp).toInt(), 0, 0)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(searchRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(queryScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(translation.view, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(message, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(lookup.web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        // The insets become the frame's padding, so the content keeps its own.
        setContentView(FrameLayout(this).apply {
            fitsSystemWindows = true
            addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        })
        show(Shown.Hint)
        takeText(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeText(intent)
    }

    override fun onDestroy() {
        main.removeCallbacks(searchSoon)
        marker.shutdownNow()
        lookup.close()
        translation.close()
        super.onDestroy()
    }

    /** Text selected in another app (PROCESS_TEXT) or handed over by Hiroi itself (EXTRA_QUERY). */
    private fun takeText(intent: Intent) {
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT) ?: intent.getStringExtra(EXTRA_QUERY)
        source = referrer?.host?.takeIf { it != packageName }?.let(::appLabel).orEmpty()
        if (text.isNullOrBlank()) {
            input.requestFocus()
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            return
        }
        input.setText(text.toString().trim())
        input.setSelection(input.text.length)
        main.removeCallbacks(searchSoon)
        search()
    }

    /**
     * Searches the box's text (as kana, if typed in romaji), from its first Japanese character;
     * text in Latin letters that isn't romaji by meaning (eat, essen), and romaji that finds
     * nothing too.
     */
    private fun search() {
        val typed = input.text.toString().trim()
        val text = if (romaji && Romaji.hasRomaji(typed)) Romaji.toKana(typed) else typed
        queryView.text = text
        queryView.highlight = null
        queryView.marks = emptyList()
        queryScroll.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        val first = text.indexOfFirst(Japanese::isJapanese)
        val wildcard = Wildcard.parse(text)
        when {
            text.isEmpty() -> {
                lookup.cancel()
                show(Shown.Hint)
            }
            wildcard != null && wildcard.affix.isEmpty() -> {
                lookup.cancel()
                show(Shown.Message, getString(R.string.search_wildcard_empty))
            }
            wildcard != null -> lookUpWildcard(wildcard)
            // Latin letters only: English, German… or romaji that's all kana now
            typed.none(Japanese::isJapanese) && Meanings.words(typed).isNotEmpty() ->
                lookUpLatin(typed, kana = text.takeIf { first == 0 && it.none { c -> c in 'a'..'z' || c in 'A'..'Z' } })
            first < 0 -> {
                lookup.cancel()
                show(Shown.Message, getString(R.string.search_not_japanese))
            }
            else -> lookUpAt(first)
        }
        translation.refresh()
        markQuery(text)
    }

    private fun lookUpAt(offset: Int) {
        val text = queryView.text.toString()
        show(Shown.Message, getString(R.string.looking_up))
        lookup.lookUp(text, offset) { showOutcome(text, it, 0) }
    }

    /**
     * Text in Latin letters, in sections: the word [kana] (the query typed as romaji) is all of,
     * the words of its meaning, and what it might have been meant as. The query box shows it as typed.
     */
    private fun lookUpLatin(query: String, kana: String?) {
        queryView.text = query
        show(Shown.Message, getString(R.string.looking_up))
        lookup.lookUpLatin(query, kana, correctRomaji = romaji) { showOutcome(query, it, 0) }
    }

    /** Words starting or ending with the text around the asterisk, which is highlighted. */
    private fun lookUpWildcard(wildcard: Wildcard) {
        val text = queryView.text.toString()
        show(Shown.Message, getString(R.string.looking_up))
        lookup.lookUpWildcard(wildcard) { showOutcome(text, it, wildcard.start) }
    }

    /** [outcome] of looking up in [text], unless it changed meanwhile; the result's text starts at [shift] in it. */
    private fun showOutcome(text: String, outcome: DictionaryLookup.Lookup, shift: Int) {
        if (queryView.text.toString() != text) return
        when (outcome) {
            is DictionaryLookup.Lookup.Message -> show(Shown.Message, outcome.text)
            is DictionaryLookup.Lookup.Found -> {
                queryView.highlight = outcome.result.start + shift to outcome.result.end + shift
                show(Shown.Results)
                lookup.show(outcome)
            }
        }
    }

    /** The words you have in Anki, marked in the query as in the overlay's panel. */
    private fun markQuery(text: String) {
        if (text.isEmpty()) return
        marker.execute {
            val marks = KnownWords.marksIn(this, text)
            val style = MarkStyle.of(MarkSettings(this))
            runOnUiThread {
                if (isDestroyed || queryView.text.toString() != text) return@runOnUiThread
                queryView.markStyle = style
                queryView.marks = marks
            }
        }
    }

    private enum class Shown { Hint, Message, Results }

    private fun show(what: Shown, text: String = "") {
        message.text = if (what == Shown.Hint) getString(R.string.search_intro) else text
        message.visibility = if (what == Shown.Results) View.GONE else View.VISIBLE
        lookup.web.visibility = if (what == Shown.Results) View.VISIBLE else View.INVISIBLE
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun appLabel(packageName: String): String =
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString() }.getOrDefault(packageName)

    companion object {
        private const val SEARCH_DELAY_MS = 300L
        const val EXTRA_QUERY = "io.github.kvth.hiroi.QUERY"

        fun intent(context: Context, query: String? = null): Intent =
            Intent(context, SearchActivity::class.java).apply { if (query != null) putExtra(EXTRA_QUERY, query) }
    }
}
