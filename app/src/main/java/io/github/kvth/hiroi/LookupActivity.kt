package io.github.kvth.hiroi

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Insets
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.Selection
import android.text.Spannable
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.kvth.hiroi.anki.AnkiSettings
import io.github.kvth.hiroi.anki.CardCreator
import io.github.kvth.hiroi.anki.CardData
import io.github.kvth.hiroi.anki.DuplicateNoteException
import io.github.kvth.hiroi.anki.Furigana
import io.github.kvth.hiroi.dictionary.DictionaryDb
import io.github.kvth.hiroi.dictionary.LookupResult
import io.github.kvth.hiroi.dictionary.Translator
import io.github.kvth.hiroi.ocr.ScreenOcr
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
    private lateinit var textView: TextView
    private lateinit var resultsScroll: ScrollView
    private lateinit var resultsList: LinearLayout
    private lateinit var textActions: List<Button>

    private val dictionaries by lazy { DictionaryDb.get(this) }
    private val translator by lazy { Translator(dictionaries) }
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var resultsHtml: ResultsHtml
    private lateinit var resultsWeb: WebView
    private lateinit var resultsWebFrame: FrameLayout
    private var shownFound: Lookup.Found? = null
    private var shownBlock: TextBlock? = null // the box whose text the panel shows, for the on-screen highlight
    private val dictionaryStyles by lazy { dictionaries.styles() } // read on the lookup thread
    private var lookupGeneration = 0

    private val dp by lazy { resources.displayMetrics.density }
    private var systemBars = Insets.NONE
    private var panelAtTop = false
    private var hasText = false
    private val scanner: ExecutorService = Executors.newSingleThreadExecutor() // OCR, apart from lookups
    private var scanning = false
    private var pendingTap: Pair<Int, Int>? = null // a tap outside the boxes while scanning
    private var captureGeneration = 0 // tells a scan's result from an older capture's

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge is the default from Android 15 on, but still has to be requested on 14.
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
        // Theme colours are resolved here; the HTML itself is made on the lookup thread.
        resultsHtml = ResultsHtml(
            ResultsHtml.Colors(
                css(themeColor(android.R.attr.textColorPrimary)),
                css(themeColor(android.R.attr.textColorSecondary)),
                css(themeColor(android.R.attr.colorAccent)),
            ),
        )
        setContentView(buildContent())
        startScan()
        showHint()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        overlay.blocks = CaptureStore.blocks
        startScan()
        showHint()
    }

    override fun onStart() {
        super.onStart()
        showing = true
    }

    override fun onStop() {
        showing = false
        super.onStop()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        scanner.shutdownNow()
        if (isFinishing) {
            CaptureStore.onClosed?.invoke()
            CaptureStore.onClosed = null
            CaptureStore.screenshot = null // ~10 MB
        }
        super.onDestroy()
    }

    private fun buildContent(): FrameLayout {
        val root = FrameLayout(this)
        overlay = OverlayView(this, CaptureStore.blocks, ::showBlock, ::onEmptyTap)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        textView = TextView(this).apply {
            setTextIsSelectable(true)
            textSize = 22f
            setLineSpacing(0f, 1.2f)
        }
        listenForWordTaps()
        scroll = maxHeightScrollView(0.3f)
        scroll.addView(textView)

        resultsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * dp).toInt(), 0, 0)
        }
        resultsWeb = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            // Only Hiroi's own HTML: nothing from the network or files, script just for the Anki buttons.
            settings.javaScriptEnabled = true
            settings.blockNetworkLoads = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            addJavascriptInterface(AnkiBridge(), "Hiroi")
        }
        val maxResultsHeight = (resources.displayMetrics.heightPixels * 0.45f).toInt()
        resultsWebFrame = object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(maxResultsHeight, MeasureSpec.AT_MOST),
            )
        }
        resultsWebFrame.addView(resultsWeb, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
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
        val copy = action(R.string.copy) { copy(selectedOrAllText()) }
        val lookUp = action(R.string.look_up) { lookUp(selectedOrAllText(), choose = false) }
        lookUp.setOnLongClickListener { lookUp(selectedOrAllText(), choose = true); true }
        action(R.string.all) { showAll() }
        action(R.string.close) { finish() }
        textActions = listOf(copy, lookUp)

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true // keep taps on the panel from reaching the overlay
            setOnClickListener { if (!hasText) moveHint() }
            elevation = 8 * dp
            background = GradientDrawable().apply {
                setColor(themeColor(android.R.attr.colorBackground))
                cornerRadius = 16 * dp
            }
            val pad = (16 * dp).toInt()
            setPadding(pad, (12 * dp).toInt(), pad, (4 * dp).toInt())
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(resultsScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(resultsWebFrame, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        root.addView(panel)
        placePanel()

        root.setOnApplyWindowInsetsListener { _, insets ->
            systemBars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
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
                    moveHint()
                    return true
                }
                val offset = charAt(textView, e.x, e.y)
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

    /** Tapping the hint moves the bar to the other edge, uncovering what's under it. */
    private fun moveHint() {
        panelAtTop = !panelAtTop
        placePanel()
    }

    /** The index of the character under (x, y), not the nearest gap between characters. */
    private fun charAt(view: TextView, x: Float, y: Float): Int {
        val layout = view.layout ?: return -1
        if (view.text.isEmpty()) return -1
        val line = layout.getLineForVertical((y - view.totalPaddingTop + view.scrollY).toInt())
        val lineX = x - view.totalPaddingLeft + view.scrollX
        var offset = layout.getOffsetForHorizontal(line, lineX)
        if (offset > layout.getLineStart(line) && layout.getPrimaryHorizontal(offset) > lineX) offset--
        return offset.coerceAtMost(view.text.length - 1)
    }

    private fun lookUpAt(offset: Int) {
        if (!hasText) return
        val text = textView.text.toString()
        val generation = ++lookupGeneration
        showResultsText(getString(R.string.looking_up))
        worker.execute {
            val started = SystemClock.elapsedRealtime()
            // Formatting big structured-content glossaries takes a while too, so it happens here as well.
            val outcome = runCatching {
                when {
                    !dictionaries.hasDictionaries() -> Lookup.Message(getString(R.string.no_dictionaries))
                    else -> when (val result = translator.lookUp(text, offset)) {
                        null -> Lookup.Message(getString(R.string.not_japanese))
                        else -> if (result.terms.isEmpty() && result.kanji.isEmpty()) {
                            Lookup.Message(getString(R.string.no_match))
                        } else {
                            val cards = AnkiSettings(this).cardFormat.complete
                            Lookup.Found(result, text, resultsHtml.document(result, ankiStatus(result), dictionaryStyles, cards))
                        }
                    }
                }
            }.getOrElse {
                Log.e(TAG, "Lookup failed", it)
                Lookup.Message(getString(R.string.lookup_failed, it.message ?: it.javaClass.simpleName))
            }
            Log.d(TAG, "Lookup at $offset took ${SystemClock.elapsedRealtime() - started} ms")
            runOnUiThread {
                if (generation == lookupGeneration && !isDestroyed) showLookup(outcome)
            }
        }
    }

    /** Asks Anki which of the headwords already have a note, if a deck is set up. */
    private fun ankiStatus(result: LookupResult): AnkiStatus? {
        val anki = AnkiSettings(this)
        if (!anki.enabled || result.terms.isEmpty()) return null
        return try {
            AnkiStatus(anki.client().wordsInDeck(anki.deck, anki.field, result.terms.map { it.expression }.distinct()))
        } catch (e: Exception) {
            Log.w(TAG, "Anki check failed", e)
            AnkiStatus(emptySet(), getString(R.string.anki_unreachable, e.message ?: e.javaClass.simpleName))
        }
    }

    private sealed interface Lookup {
        class Message(val text: String) : Lookup

        /** [text] is the panel text the lookup ran on (for a card's sentence); [html] the results page. */
        class Found(val result: LookupResult, val text: String, val html: String) : Lookup
    }

    private fun showLookup(lookup: Lookup) {
        when (lookup) {
            is Lookup.Message -> showResultsText(lookup.text)
            is Lookup.Found -> {
                // Select the matched word so Copy and Look up act on it. The selection only shows
                // while the text has focus, which it lacks when the tap was on a box.
                textView.requestFocus()
                Selection.setSelection(textView.text as Spannable, lookup.result.start, lookup.result.end)
                highlightOnScreen(lookup.result.start, lookup.result.end)
                shownFound = lookup
                resultsScroll.visibility = View.GONE
                resultsWebFrame.visibility = View.VISIBLE
                resultsWeb.loadDataWithBaseURL(null, lookup.html, "text/html", "utf-8", null)
            }
        }
    }

    private fun showResultsText(text: CharSequence) {
        shownFound = null
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
        val block = shownBlock
        val characters = block?.characters
        if (block == null || characters == null) {
            overlay.highlight = emptyList()
            return
        }
        // One rectangle per line the word runs over.
        val lines = mutableListOf<RectF>()
        for (i in start until end) {
            val rect = characters.getOrNull(block.textOffset + i) ?: continue
            val last = lines.lastOrNull()
            if (last != null && rect.centerY() in last.top..last.bottom) last.union(rect) else lines += RectF(rect)
        }
        overlay.highlight = lines
    }

    /** The results page's "＋ Anki" buttons, by result index. */
    private inner class AnkiBridge {
        @JavascriptInterface
        fun addCard(index: Int) {
            runOnUiThread { this@LookupActivity.addCard(index) }
        }
    }

    /** Makes a note from result [index] with the card format; its button then shows how it went. */
    private fun addCard(index: Int) {
        val found = shownFound ?: return
        val term = found.result.terms.getOrNull(index) ?: return
        fun button(label: Int, enabled: Boolean) =
            resultsWeb.evaluateJavascript("setCard($index, '${getString(label)}', $enabled)", null)
        button(R.string.anki_adding, false)
        val start = found.result.start
        val data = CardData(
            term, found.text, start, start + term.source.length,
            documentTitle = appLabel(CaptureStore.sourcePackage),
            furigana = { sentence ->
                Furigana.forText(sentence) { text, i ->
                    translator.lookUp(text, i)?.terms?.firstOrNull()?.let { Furigana.TermGroupLike(it.source, it.expression, it.reading) }
                }
            },
        )
        val screenshot = CaptureStore.screenshot
        worker.execute {
            val result = runCatching { CardCreator(AnkiSettings(this)).add(data, screenshot) }
            runOnUiThread {
                if (isDestroyed || shownFound !== found) return@runOnUiThread
                result.fold(
                    {
                        button(R.string.anki_added, false)
                        resultsWeb.evaluateJavascript("inAnki($index, '${getString(R.string.anki_in_deck)}')", null)
                    },
                    { error ->
                        if (error is DuplicateNoteException) {
                            button(R.string.anki_duplicate, false)
                        } else {
                            Log.w(TAG, "Adding a card failed", error)
                            button(R.string.anki_add, true)
                            Toast.makeText(this, getString(R.string.anki_add_failed, error.message ?: error.javaClass.simpleName), Toast.LENGTH_LONG).show()
                        }
                    },
                )
            }
        }
    }

    private fun css(color: Int) = String.format("#%06X", color and 0xFFFFFF)

    /** The app's name, e.g. for the card's {document-title}; the package name if it can't be read. */
    private fun appLabel(packageName: String?): String {
        if (packageName == null) return ""
        return runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString() }
            .getOrDefault(packageName)
    }

    private fun placePanel() {
        val margin = (8 * dp).toInt()
        panel.layoutParams = FrameLayout.LayoutParams(
            MATCH_PARENT, WRAP_CONTENT, if (panelAtTop) Gravity.TOP else Gravity.BOTTOM,
        ).apply {
            setMargins(
                systemBars.left + margin, systemBars.top + margin,
                systemBars.right + margin, systemBars.bottom + margin,
            )
        }
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
        setPanelText(hint, atTop = hintAtTop(), isText = false)
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
        // Look the tapped word up right away when the app reported where its characters are.
        val offset = CharacterLocator.characterAt(block, x, y)
        Log.d(TAG, "Tapped character $offset in a box")
        // After the TextView has its text, which the selection needs.
        if (offset != null) textView.post { lookUpAt(offset) }
    }

    private fun showAll() {
        overlay.selected = null
        val lines = mutableListOf<String>()
        for (block in overlay.blocks) if (lines.lastOrNull() != block.text) lines += block.text
        setPanelText(lines.joinToString("\n"), atTop = false, isText = lines.isNotEmpty())
    }

    private fun setPanelText(text: String, atTop: Boolean, isText: Boolean) {
        hasText = isText
        lookupGeneration++
        shownBlock = null
        shownFound = null
        overlay.highlight = emptyList()
        resultsScroll.visibility = View.GONE
        resultsWebFrame.visibility = View.GONE
        textView.textSize = if (isText) 22f else 16f
        // The hint itself isn't selectable, so a tap on it reaches the panel, which then moves.
        textView.setTextIsSelectable(isText)
        textView.text = text
        textActions.forEach { it.isEnabled = isText }
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
        val tap = pendingTap
        pendingTap = null
        val hit = tap?.let { (x, y) -> overlay.blockAt(x, y) }
        when {
            hit != null -> showBlock(hit, tap.first, tap.second) // tapped an image while it was being read
            !hasText -> showHint() // new count, no more "looking"
        }
    }

    private fun selectedOrAllText(): String {
        val start = minOf(textView.selectionStart, textView.selectionEnd)
        val end = maxOf(textView.selectionStart, textView.selectionEnd)
        val text = textView.text
        return if (start >= 0 && start < end) text.subSequence(start, end).toString() else text.toString()
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        // Android 13+ shows its own clipboard confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
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
        }
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
        private const val PREFS = "hiroi"
        private const val KEY_DICTIONARY = "dictionary_component"

        /** True while the overlay is on screen; captures then skip Hiroi's own windows. */
        @Volatile
        var showing = false
            private set

        fun intent(context: Context): Intent =
            Intent(context, LookupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
