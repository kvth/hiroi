package io.github.kvth.hiroi

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.Selection
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.kvth.hiroi.anki.YomitanNoteData
import io.github.kvth.hiroi.japanese.Japanese
import io.github.kvth.hiroi.marks.KnownWords
import io.github.kvth.hiroi.marks.MarkSettings
import io.github.kvth.hiroi.marks.MarkStyle
import io.github.kvth.hiroi.marks.MarkedTextView
import io.github.kvth.hiroi.marks.MarksActivity
import io.github.kvth.hiroi.ocr.ScreenOcr
import io.github.kvth.hiroi.share.ShareMenu
import io.github.kvth.hiroi.translation.TranslationStrip
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Transparent screen over the current app: tap a text box, then tap a word in it to look it up in
 * the imported dictionaries, or select text to copy it or send it to a dictionary app.
 */
class LookupActivity : Activity() {

    private lateinit var overlay: OverlayView
    private lateinit var panel: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var textView: MarkedTextView
    private lateinit var resultsScroll: ScrollView
    private lateinit var resultsList: LinearLayout
    private lateinit var textActions: List<Button>
    private lateinit var marksButton: Button

    private lateinit var lookup: DictionaryLookup
    private lateinit var resultsWebFrame: FrameLayout
    private var shownBlock: TextBlock? = null // the box whose text the panel shows, for the on-screen highlight
    private var lookedUp: Pair<Int, Int>? = null // the looked-up word in the panel's text, whose sentence is translated
    private var panelSource = R.string.scope_box // where the panel's text is from: a box, a region, all boxes
    private lateinit var translation: TranslationStrip
    private lateinit var scopeRow: LinearLayout // what the strip translates, when there's a choice
    private var translateScope = TranslateScope.SENTENCE // kept while the overlay is open

    private val dp by lazy { resources.displayMetrics.density }
    private var systemBars = Insets.NONE
    private var panelAtTop = false
    private var hasText = false
    private lateinit var chip: TextView // the hint in short, in a corner, until a box is tapped
    private var hintExpanded = false // the hint as the full bar (with the Marks button) instead of the chip
    private var chipGravity = Gravity.TOP or Gravity.RIGHT
    private val scanner: ExecutorService = Executors.newSingleThreadExecutor() // OCR, apart from lookups
    private var scanning = false
    private var pendingTap: Pair<Int, Int>? = null // a tap outside the boxes while scanning
    private var captureGeneration = 0 // tells a scan's result from an older capture's
    private val marker: ExecutorService = Executors.newSingleThreadExecutor() // words from Anki to mark
    private var markGeneration = 0
    private var markStats: MarkStats? = null // null: nothing to mark (not synced, or off here)
    private var marksAvailable = false // synced at least once, so the Marks button has something to switch
    private val onKnownWordsChanged: () -> Unit = { refreshMarks() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge is the default from Android 15 on, but still has to be requested on 14.
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
        lookup = DictionaryLookup(this, { appLabel(CaptureStore.sourcePackage) }, { CaptureStore.screenshot }, ::selectedText) {
            // The region dragged out, else the box tapped; all boxes ("All") are the whole screen
            overlay.region ?: shownBlock?.bounds
        }
        setContentView(buildContent())
        overlay.frozen = frozenScreen()
        startScan()
        showHint()
        KnownWords.listeners += onKnownWordsChanged
        refreshMarks()
        KnownWords.syncIfDue(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        overlay.blocks = CaptureStore.blocks
        overlay.frozen = frozenScreen()
        startScan()
        showHint()
        refreshMarks()
        KnownWords.syncIfDue(this)
    }

    override fun onStart() {
        super.onStart()
        showing = true
    }

    override fun onStop() {
        showing = false
        // The keyboard Hiroi hid for the capture may show again, for the app it belongs to.
        HiroiAccessibilityService.instance?.restoreKeyboard()
        super.onStop()
    }

    override fun onDestroy() {
        lookup.close()
        translation.close()
        scanner.shutdownNow()
        marker.shutdownNow()
        KnownWords.listeners -= onKnownWordsChanged
        if (isFinishing) {
            CaptureStore.onClosed?.invoke()
            CaptureStore.onClosed = null
            CaptureStore.screenshot = null // ~10 MB
        }
        super.onDestroy()
    }

    private fun buildContent(): FrameLayout {
        val root = FrameLayout(this)
        overlay = OverlayView(this, CaptureStore.blocks, ::showBlock, ::onEmptyTap, ::showRegion)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        textView = MarkedTextView(this).apply {
            setTextIsSelectable(true)
            textSize = 22f
            setLineSpacing(0f, 1.2f)
        }
        listenForWordTaps()
        scroll = maxHeightScrollView(0.3f)
        scroll.addView(textView)
        translation = TranslationStrip(this, ::translationSource)
        scopeRow = LinearLayout(this).apply { visibility = View.GONE }

        resultsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * dp).toInt(), 0, 0)
        }
        val maxResultsHeight = (resources.displayMetrics.heightPixels * 0.45f).toInt()
        resultsWebFrame = object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(maxResultsHeight, MeasureSpec.AT_MOST),
            )
        }
        resultsWebFrame.addView(lookup.web, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        resultsWebFrame.visibility = View.GONE
        resultsScroll = maxHeightScrollView(0.4f)
        resultsScroll.addView(resultsList)
        resultsScroll.visibility = View.GONE

        val buttons = LinearLayout(this).apply { gravity = Gravity.END }
        fun action(label: Int, onClick: () -> Unit) =
            Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                setText(label)
                setOnClickListener { onClick() }
                buttons.addView(this)
            }
        marksButton = action(R.string.marks_button) { showMarksMenu() }
        // Long-press copies right away what the menu would start with
        val share = action(R.string.share) { showShareMenu() }
        share.setOnLongClickListener { shareScopes().let { (scopes, chosen) -> ShareMenu.copy(this, scopes[chosen].text) }; true }
        val lookUp = action(R.string.look_up) { lookUp(selectedOrAllText(), choose = false) }
        lookUp.setOnLongClickListener { lookUp(selectedOrAllText(), choose = true); true }
        action(R.string.all) { showAll() }
        action(R.string.close) { finish() }
        textActions = listOf(share, lookUp)

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true // keep taps on the panel from reaching the overlay
            setOnClickListener { if (!hasText) shrinkHint() }
            elevation = 8 * dp
            background = GradientDrawable().apply {
                setColor(themeColor(android.R.attr.colorBackground))
                cornerRadius = 16 * dp
            }
            val pad = (16 * dp).toInt()
            setPadding(pad, (12 * dp).toInt(), pad, (4 * dp).toInt())
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(scopeRow, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(translation.view, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(resultsScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(resultsWebFrame, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        root.addView(panel)
        chip = TextView(this).apply {
            elevation = 8 * dp
            background = GradientDrawable().apply {
                setColor(themeColor(android.R.attr.colorBackground))
                cornerRadius = 20 * dp
            }
            setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
            textSize = 14f
            setOnClickListener {
                hintExpanded = true
                showHint()
            }
        }
        root.addView(chip)
        placePanel()

        root.setOnApplyWindowInsetsListener { _, insets ->
            systemBars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            overlay.liveBars = insets.getInsets(WindowInsets.Type.systemBars())
            placePanel()
            insets
        }
        return root
    }

    private fun maxHeightScrollView(fractionOfScreen: Float): ScrollView {
        val maxHeight = (resources.displayMetrics.heightPixels * fractionOfScreen).toInt()
        return object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST),
            )
        }
    }

    /** A plain tap on a character looks up the word starting there; long-press still selects text. */
    @SuppressLint("ClickableViewAccessibility")
    private fun listenForWordTaps() {
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (!hasText) {
                    shrinkHint()
                    return true
                }
                val offset = textView.characterAt(e.x, e.y)
                // Run after the TextView has handled the tap, which would clear our selection.
                if (offset >= 0) textView.post { lookUpAt(offset) }
                return false
            }
        })
        textView.setOnTouchListener { _, event ->
            taps.onTouchEvent(event)
            // The hint isn't selectable, so take its touches ourselves, or the tap's end never arrives.
            !hasText
        }
    }

    /** Tapping the hint shrinks it back to the chip, uncovering what's under it. */
    private fun shrinkHint() {
        hintExpanded = false
        showHint()
    }

    private fun lookUpAt(offset: Int) {
        if (!hasText) return
        showResultsText(getString(R.string.looking_up))
        lookup.lookUp(textView.text.toString(), offset, ::showLookup)
    }

    private fun showLookup(outcome: DictionaryLookup.Lookup) {
        when (outcome) {
            is DictionaryLookup.Lookup.Message -> {
                lookedUp = null
                showResultsText(outcome.text)
            }
            is DictionaryLookup.Lookup.Found -> {
                lookedUp = outcome.result.start to outcome.result.end
                // Select the matched word so Copy and Look up act on it. The selection only shows
                // while the text has focus, which it lacks when the tap was on a box.
                textView.requestFocus()
                Selection.setSelection(textView.text as Spannable, outcome.result.start, outcome.result.end)
                highlightOnScreen(outcome.result.start, outcome.result.end)
                resultsScroll.visibility = View.GONE
                resultsWebFrame.visibility = View.VISIBLE
                lookup.show(outcome)
            }
        }
        refreshTranslation()
    }

    /** What the translation strip translates, as Share's scopes: a sentence, the panel's text, or all boxes. */
    private enum class TranslateScope { SENTENCE, PANEL, ALL }

    /** What the translation strip translates, in the scope chosen ([scopeTexts]). Nothing while the panel shows the hint. */
    private fun translationSource(): String? =
        if (!hasText) null else scopeTexts().getValue(translateScope).takeIf { it.isNotEmpty() }

    /**
     * The text of each scope: for a sentence, the text selected in the panel, else the sentence
     * of the looked-up word, else all of the panel's text; the panel's text (a box, a region or
     * all boxes); the text of all boxes.
     */
    private fun scopeTexts(): Map<TranslateScope, String> {
        val text = textView.text.toString()
        val start = minOf(textView.selectionStart, textView.selectionEnd)
        val end = maxOf(textView.selectionStart, textView.selectionEnd)
        val word = lookedUp
        val sentence = when {
            start >= 0 && start < end && (start to end) != word -> text.substring(start, end)
            word != null && word.second <= text.length -> YomitanNoteData.sentence(text, word.first, word.second).let { (s, e) -> text.substring(s, e) }
            else -> text
        }
        return mapOf(TranslateScope.SENTENCE to sentence.trim(), TranslateScope.PANEL to text.trim(), TranslateScope.ALL to allBoxesText().trim())
    }

    /**
     * The translation strip for the panel's text now, with a choice of what it translates above
     * it: only the scopes whose text differs, the one chosen (or the one with the same text) marked.
     * Choosing one translates it.
     */
    private fun refreshTranslation() {
        translation.refresh()
        scopeRow.removeAllViews()
        scopeRow.visibility = View.GONE
        if (!hasText || translation.view.visibility != View.VISIBLE) return
        val texts = scopeTexts()
        val options = buildList {
            if (texts[TranslateScope.SENTENCE] != texts[TranslateScope.PANEL]) add(TranslateScope.SENTENCE)
            if (texts[TranslateScope.PANEL] != texts[TranslateScope.ALL]) add(TranslateScope.PANEL)
            add(TranslateScope.ALL)
        }
        if (options.size < 2) return
        val chosen = options.firstOrNull { it == translateScope } ?: options.first { texts[it] == texts[translateScope] }
        for (scope in options) {
            val label = when (scope) {
                TranslateScope.SENTENCE -> R.string.scope_sentence
                TranslateScope.PANEL -> panelSource
                TranslateScope.ALL -> R.string.scope_all
            }
            scopeRow.addView(TextView(this).apply {
                setText(label)
                textSize = 14f
                setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
                isSelected = scope == chosen
                setTextColor(themeColor(if (isSelected) android.R.attr.textColorPrimary else android.R.attr.textColorSecondary))
                typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                if (isSelected) {
                    background = GradientDrawable().apply {
                        setColor(themeColor(android.R.attr.colorControlHighlight))
                        cornerRadius = 12 * dp
                    }
                }
                setOnClickListener {
                    translateScope = scope
                    refreshTranslation()
                    translation.view.performClick()
                }
            })
        }
        scopeRow.visibility = View.VISIBLE
    }

    /** The text of all boxes, a line each, the same line once where boxes repeat it. */
    private fun allBoxesText(): String {
        val lines = mutableListOf<String>()
        for (block in overlay.blocks) if (lines.lastOrNull() != block.text) lines += block.text
        return lines.joinToString("\n")
    }

    private fun showResultsText(text: CharSequence) {
        resultsWebFrame.visibility = View.GONE
        resultsList.removeAllViews()
        resultsList.addView(TextView(this).apply {
            setTextIsSelectable(true)
            textSize = 16f
            this.text = text
        })
        resultsScroll.visibility = View.VISIBLE
        resultsScroll.scrollTo(0, 0)
    }

    /** Marks the matched characters in the box over the app too, where their positions are known. */
    private fun highlightOnScreen(start: Int, end: Int) {
        overlay.highlight = shownBlock?.lineRects(start, end).orEmpty()
    }

    /** The app's name, e.g. for the card's {document-title}; the package name if it can't be read. */
    private fun appLabel(packageName: String?): String {
        if (packageName == null) return ""
        return runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString() }
            .getOrDefault(packageName)
    }

    /** The panel at the top or bottom edge, the chip in its corner, both clear of the system bars. */
    private fun placePanel() {
        val margin = (8 * dp).toInt()
        fun FrameLayout.LayoutParams.clearOfBars() = apply {
            setMargins(
                systemBars.left + margin, systemBars.top + margin,
                systemBars.right + margin, systemBars.bottom + margin,
            )
        }
        panel.layoutParams = FrameLayout.LayoutParams(
            MATCH_PARENT, WRAP_CONTENT, if (panelAtTop) Gravity.TOP else Gravity.BOTTOM,
        ).clearOfBars()
        chip.layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, chipGravity).clearOfBars()
    }

    /** The panel while a box's text (or the full hint) shows, else the chip. */
    private fun showPanelOrChip() {
        val full = hasText || hintExpanded
        panel.visibility = if (full) View.VISIBLE else View.GONE
        chip.visibility = if (full) View.GONE else View.VISIBLE
    }

    private fun showHint() {
        overlay.selected = null
        val count = overlay.blocks.size
        val hint = when {
            count > 0 && scanning -> getString(R.string.hint_tap_scanning, count)
            count > 0 -> getString(R.string.hint_tap, count)
            scanning -> getString(R.string.hint_none_scanning)
            else -> getString(R.string.hint_none)
        }
        setPanelText(withCoverage(hint), atTop = hintAtTop(), isText = false)
        chip.text = chipText(count)
        val corner = chipCorner()
        if (corner != chipGravity) {
            chipGravity = corner
            placePanel()
        }
    }

    /** The hint in short: the boxes found (or that there are none yet) and how much of their text is marked. */
    private fun chipText(count: Int): String {
        val parts = mutableListOf(
            when {
                count > 0 -> resources.getQuantityString(R.plurals.chip_boxes, count, count) + if (scanning) "…" else ""
                scanning -> getString(R.string.hint_none_scanning)
                else -> getString(R.string.hint_none)
            },
        )
        val stats = markStats
        if (marksAvailable && stats != null && stats.total > 0) {
            parts += getString(R.string.chip_marked, percent(stats.rules.sumOf { it.chars }, stats.total))
        }
        return parts.joinToString(" · ")
    }

    /** The corner where the chip hides the least of the boxes (by area); on a tie the first of top right, top left, bottom right, bottom left. */
    private fun chipCorner(): Int {
        chip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val margin = (8 * dp).toInt()
        val width = chip.measuredWidth + margin
        val height = chip.measuredHeight + margin
        val screenWidth = window.decorView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val screenHeight = window.decorView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        fun hidden(gravity: Int): Long {
            val left = if (gravity and Gravity.RIGHT == Gravity.RIGHT) screenWidth - systemBars.right - width else systemBars.left
            val top = if (gravity and Gravity.TOP == Gravity.TOP) systemBars.top else screenHeight - systemBars.bottom - height
            val area = Rect(left, top, left + width, top + height)
            val overlap = Rect()
            return overlay.blocks.sumOf { if (overlap.setIntersect(it.bounds, area)) overlap.width().toLong() * overlap.height() else 0L }
        }
        return listOf(Gravity.TOP or Gravity.RIGHT, Gravity.TOP or Gravity.LEFT, Gravity.BOTTOM or Gravity.RIGHT, Gravity.BOTTOM or Gravity.LEFT)
            .minBy(::hidden)
    }

    /**
     * The hint plus how much of the screen's Japanese text is marked, in characters, as AnkiMark's
     * popup shows it for a page: in total, and per rule with its number of different words (notes).
     */
    private fun withCoverage(hint: String): CharSequence {
        val stats = markStats
        if (!marksAvailable) return hint
        val out = SpannableStringBuilder(hint).append('\n')
        if (stats == null) return out.append(getString(R.string.marks_off_here))
        if (stats.total == 0) return out.append(getString(R.string.marks_none))
        val known = stats.rules.sumOf { it.chars }
        out.append(getString(R.string.marks_coverage, percent(known, stats.total), stats.unique))
        for (rule in stats.rules) {
            out.append("  ")
            val start = out.length
            out.append("■")
            out.setSpan(ForegroundColorSpan(rule.color), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.append(" ").append(getString(R.string.marks_rule_count, rule.name, rule.words, percent(rule.chars, stats.total)))
        }
        return out
    }

    private fun percent(part: Int, total: Int): String {
        val p = part * 100.0 / total
        return if (p > 0 && p < 1) "<1%" else "${Math.round(p)}%"
    }

    /** Per enabled rule: its colour, the Japanese characters it marks and its number of different words. */
    private class MarkStats(val total: Int, val unique: Int, val rules: List<RuleStats>)

    private class RuleStats(val name: String, val color: Int, val chars: Int, val words: Int)

    /**
     * Finds the words from Anki in every box (off the main thread: the word list may need
     * reading first), then marks them on screen and in the panel.
     */
    private fun refreshMarks() {
        val generation = ++markGeneration
        val blocks = overlay.blocks
        val app = CaptureStore.sourcePackage
        marker.execute {
            val settings = MarkSettings(this)
            val available = KnownWords.syncInfo(this) != null
            val matcher = if (settings.isOffFor(app)) null else KnownWords.matcher(this, settings)
            val rules = settings.rules.filter { it.enabled }
            val colors = rules.associate { it.id to it.argb }
            val marks = mutableListOf<OverlayView.Mark>()
            var stats: MarkStats? = null
            if (matcher != null) {
                val chars = HashMap<String, Int>()
                val notes = HashMap<String, MutableSet<Long>>()
                for (block in blocks) {
                    for (m in matcher.find(block.text)) {
                        val rule = m.entry.ruleId
                        chars[rule] = (chars[rule] ?: 0) + block.text.substring(m.start, m.end).count(Japanese::isJapanese)
                        notes.getOrPut(rule) { HashSet() } += m.entry.noteId
                        val lines = block.lineRects(m.start, m.end)
                        if (lines.isNotEmpty()) marks += OverlayView.Mark(lines, colors[rule] ?: continue)
                    }
                }
                stats = MarkStats(
                    total = blocks.sumOf { block -> block.text.count(Japanese::isJapanese) },
                    unique = notes.values.flatten().toSet().size,
                    rules = rules.filter { (chars[it.id] ?: 0) > 0 }.map { RuleStats(it.name, it.argb, chars.getValue(it.id), notes.getValue(it.id).size) },
                )
            }
            val style = MarkStyle.of(settings)
            runOnUiThread {
                if (generation != markGeneration || isDestroyed) return@runOnUiThread
                marksAvailable = available
                markStats = stats
                overlay.markStyle = style
                overlay.marks = marks
                if (hasText) markPanel() else showHint()
            }
        }
    }

    /** Marks the words from Anki in the panel's text too, which also covers boxes without character positions. */
    private fun markPanel() {
        textView.marks = emptyList()
        if (!hasText) return
        val text = textView.text.toString()
        val app = CaptureStore.sourcePackage
        marker.execute {
            val marks = KnownWords.marksIn(this, text, app)
            val style = MarkStyle.of(MarkSettings(this))
            runOnUiThread {
                if (isDestroyed || !hasText || textView.text.toString() != text) return@runOnUiThread
                textView.markStyle = style
                textView.marks = marks
            }
        }
    }

    /** Switches for the marks, like AnkiMark's popup: everywhere, and for the app the text came from. */
    private fun showMarksMenu() {
        val settings = MarkSettings(this)
        val app = CaptureStore.sourcePackage
        val items = listOfNotNull(getString(R.string.marks_on), app?.let { getString(R.string.marks_on_in, appLabel(it)) })
        val checked = booleanArrayOf(settings.enabled, app == null || app !in settings.excludedApps)
        AlertDialog.Builder(this)
            .setTitle(R.string.marks_button)
            .setMultiChoiceItems(items.toTypedArray(), checked.copyOf(items.size)) { _, which, on ->
                if (which == 0) settings.enabled = on else settings.setOffFor(app!!, !on)
                refreshMarks()
            }
            .setNeutralButton(R.string.marks_sync) { _, _ -> syncMarks() }
            .setNegativeButton(R.string.marks_settings) { _, _ -> startActivity(Intent(this, MarksActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .setPositiveButton(R.string.done, null)
            .show()
    }

    private fun syncMarks() {
        Toast.makeText(this, R.string.marks_syncing, Toast.LENGTH_SHORT).show()
        KnownWords.sync(this) { result ->
            val message = result.fold(
                { getString(R.string.marks_synced, it.words, it.notes) },
                { getString(R.string.marks_sync_failed, it.message ?: it.javaClass.simpleName) },
            )
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            if (!isDestroyed) refreshMarks()
        }
    }

    /** The hint panel goes to the edge where it hides less of the boxes (by area). */
    private fun hintAtTop(): Boolean {
        val screen = window.decorView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val height = (panel.height.takeIf { it > 0 } ?: (120 * dp).toInt()) + (16 * dp).toInt()
        fun hidden(from: Int, to: Int) = overlay.blocks.sumOf { block ->
            val overlap = minOf(block.bounds.bottom, to) - maxOf(block.bounds.top, from)
            if (overlap > 0) overlap.toLong() * block.bounds.width() else 0L
        }
        val top = hidden(systemBars.top, systemBars.top + height)
        val bottom = hidden(screen - systemBars.bottom - height, screen - systemBars.bottom)
        return bottom > top
    }

    private fun showBlock(block: TextBlock, x: Int, y: Int) {
        overlay.selected = block
        // Keep the panel away from the text being read.
        setPanelText(block.text, atTop = block.bounds.centerY() > resources.displayMetrics.heightPixels / 2, isText = true)
        shownBlock = block
        // A box without character positions may cover a picture (Instagram describes whole posts):
        // while its text is being read, look up what's under the tap when that's done.
        if (scanning && block.characters == null) pendingTap = x to y
        // Look the tapped word up right away when the app reported where its characters are.
        val offset = CharacterLocator.characterAt(block, x, y)
        Log.d(TAG, "Tapped character $offset in a box")
        // After the TextView has its text, which the selection needs.
        if (offset != null) textView.post { lookUpAt(offset) }
    }

    private fun showAll() {
        overlay.selected = null
        val text = allBoxesText()
        setPanelText(text, atTop = false, isText = text.isNotEmpty())
        panelSource = R.string.scope_all
        refreshTranslation()
    }

    /** The text inside a region dragged out on the screen, which then stays outlined. */
    private fun showRegion(region: Rect) {
        val text = regionText(overlay.blocks, region)
        if (text.isEmpty()) {
            overlay.region = null
            Toast.makeText(this, if (scanning) R.string.region_empty_scanning else R.string.region_empty, Toast.LENGTH_SHORT).show()
            return
        }
        overlay.selected = null
        setPanelText(text, atTop = region.centerY() > resources.displayMetrics.heightPixels / 2, isText = true)
        panelSource = R.string.scope_region
        overlay.region = region
        refreshTranslation()
    }

    /**
     * What the Share menu offers: the looked-up word, its sentence, the panel's text (a box, a
     * region or all), the selection; and which it starts with (the selection, else the sentence).
     */
    private fun shareScopes(): Pair<List<ShareMenu.Scope>, Int> {
        val text = textView.text.toString()
        val word = lookedUp?.takeIf { it.second <= text.length }
        val start = minOf(textView.selectionStart, textView.selectionEnd)
        val end = maxOf(textView.selectionStart, textView.selectionEnd)
        val selection = if (start >= 0 && start < end && (start to end) != word) text.substring(start, end) else ""
        val sentence = word?.let { (s, e) -> YomitanNoteData.sentence(text, s, e).let { (a, b) -> text.substring(a, b).trim() } }.orEmpty()
        val scopes = listOf(
            ShareMenu.Scope(getString(R.string.scope_word), word?.let { text.substring(it.first, it.second) }.orEmpty()),
            ShareMenu.Scope(getString(R.string.scope_sentence), sentence),
            ShareMenu.Scope(getString(panelSource), text.trim()),
            ShareMenu.Scope(getString(R.string.scope_selection), selection),
        )
        val chosen = when {
            selection.isNotEmpty() -> 3
            sentence.isNotEmpty() -> 1
            else -> 2
        }
        return scopes to chosen
    }

    private fun showShareMenu() {
        val (scopes, chosen) = shareScopes()
        ShareMenu.show(this, scopes, chosen)
    }

    private fun setPanelText(text: CharSequence, atTop: Boolean, isText: Boolean) {
        hasText = isText
        lookup.cancel()
        shownBlock = null
        lookedUp = null
        panelSource = R.string.scope_box
        overlay.region = null
        overlay.highlight = emptyList()
        resultsScroll.visibility = View.GONE
        resultsWebFrame.visibility = View.GONE
        textView.textSize = if (isText) 22f else 16f
        // The hint itself isn't selectable, so a tap on it reaches the panel, which then moves.
        textView.setTextIsSelectable(isText)
        textView.text = text
        // The hint has nothing to copy; it gets the switches for the marks instead.
        textActions.forEach { it.visibility = if (isText) View.VISIBLE else View.GONE }
        marksButton.visibility = if (!isText && marksAvailable) View.VISIBLE else View.GONE
        markPanel()
        refreshTranslation()
        showPanelOrChip()
        scroll.scrollTo(0, 0)
        if (panelAtTop != atTop) {
            panelAtTop = atTop
            placePanel()
        }
    }

    /** Outside every box: remembered while images are still being scanned, else go back or close. */
    private fun onEmptyTap(x: Int, y: Int) {
        when {
            scanning -> pendingTap = x to y
            hasText -> showHint()
            else -> finish()
        }
    }

    /**
     * The screenshot taken when Hiroi was triggered, to show while the overlay is open (unless
     * that's turned off). Not one of a different screen size (the phone turned since), nor a black
     * one: apps that forbid screenshots give that, and there the live screen is better.
     */
    private fun frozenScreen(): Bitmap? {
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_FREEZE, true)) return null
        val picture = CaptureStore.screenshot ?: return null
        val screen = windowManager.currentWindowMetrics.bounds
        if (picture.width != screen.width() || picture.height != screen.height()) return null
        val black = (1..9).all { i -> (1..9).all { j ->
            val pixel = picture.getPixel(picture.width * i / 10, picture.height * j / 10)
            Color.red(pixel) < 16 && Color.green(pixel) < 16 && Color.blue(pixel) < 16
        } }
        return picture.takeUnless { black }
    }

    /**
     * Reads the screenshot in the background as soon as the overlay opens, on its own thread so
     * lookups don't wait. Japanese lines from images are added as dashed boxes when it's done.
     */
    private fun startScan() {
        scanning = false
        pendingTap = null
        val generation = ++captureGeneration
        val screenshot = CaptureStore.screenshot ?: return
        scanning = true
        val appText = overlay.blocks
        scanner.execute {
            val started = SystemClock.elapsedRealtime()
            val lines = runCatching {
                ScreenOcr.recognize(screenshot).filter { it.hasJapanese() && !ScreenOcr.duplicates(it, appText) }
            }.onFailure { Log.e(TAG, "OCR failed", it) }.getOrDefault(emptyList())
            Log.d(TAG, "OCR took ${SystemClock.elapsedRealtime() - started} ms: ${lines.size} new lines")
            runOnUiThread { if (generation == captureGeneration && !isDestroyed) scanDone(lines) }
        }
    }

    private fun scanDone(lines: List<TextBlock>) {
        scanning = false
        overlay.addBlocks(lines)
        if (lines.isNotEmpty()) refreshMarks()
        val tap = pendingTap
        pendingTap = null
        val hit = tap?.let { (x, y) -> overlay.blockAt(x, y) }?.takeIf { it.ocr }
        when {
            hit != null -> showBlock(hit, tap.first, tap.second) // tapped an image while it was being read
            !hasText -> showHint() // new count, no more "looking"
        }
    }

    /** The panel's selection: the looked-up word, or what the user selected. */
    private fun selectedText(): String {
        val start = minOf(textView.selectionStart, textView.selectionEnd)
        val end = maxOf(textView.selectionStart, textView.selectionEnd)
        return if (start >= 0 && start < end) textView.text.subSequence(start, end).toString() else ""
    }

    private fun selectedOrAllText(): String {
        val start = minOf(textView.selectionStart, textView.selectionEnd)
        val end = maxOf(textView.selectionStart, textView.selectionEnd)
        val text = textView.text
        return if (start >= 0 && start < end) text.subSequence(start, end).toString() else text.toString()
    }


    /** Sends [text] to the remembered dictionary app, or asks which one to use when [choose] is set or none is saved. */
    private fun lookUp(text: String, choose: Boolean) {
        val base = Intent(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        val saved = prefs.getString(KEY_DICTIONARY, null)?.let(ComponentName::unflattenFromString)
        if (!choose && saved != null) {
            try {
                startActivity(Intent(base).setComponent(saved))
                return
            } catch (e: ActivityNotFoundException) {
                prefs.edit().remove(KEY_DICTIONARY).apply()
            }
        }

        val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(base, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(base, 0)
        }.filter { it.activityInfo.packageName != packageName } // Hiroi's own search: the overlay already is that
        if (apps.isEmpty()) {
            Toast.makeText(this, R.string.no_dictionary, Toast.LENGTH_LONG).show()
            return
        }
        val labels = apps.map {
            val action = it.loadLabel(packageManager).toString()
            val app = it.activityInfo.applicationInfo.loadLabel(packageManager).toString()
            if (action == app) app else "$action · $app"
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_dictionary)
            .setItems(labels.toTypedArray()) { _, which ->
                val info = apps[which].activityInfo
                val component = ComponentName(info.packageName, info.name)
                prefs.edit().putString(KEY_DICTIONARY, component.flattenToString()).apply()
                startActivity(Intent(base).setComponent(component))
            }
            .show()
    }

    private fun themeColor(attr: Int): Int {
        val attrs = obtainStyledAttributes(intArrayOf(attr))
        try {
            return attrs.getColor(0, Color.WHITE)
        } finally {
            attrs.recycle()
        }
    }

    companion object {
        private const val TAG = "HiroiLookup"
        const val PREFS = "hiroi"

        /** Show the screen as it was when Hiroi was triggered, not the live one */
        const val KEY_FREEZE = "freeze_screen"
        private const val KEY_DICTIONARY = "dictionary_component"

        /** Whether the overlay is open showing the screen frozen as it was, which a new trigger keeps. */
        fun keepsFrozen(context: Context) = showing && CaptureStore.screenshot != null &&
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FREEZE, true)

        /** True while the overlay is on screen; captures then skip Hiroi's own windows. */
        @Volatile
        var showing = false
            private set

        fun intent(context: Context): Intent =
            Intent(context, LookupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
