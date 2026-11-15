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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.kvth.hiroi.dictionary.DictionaryDb
import io.github.kvth.hiroi.dictionary.LookupResult
import io.github.kvth.hiroi.dictionary.Translator
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
    private lateinit var resultsView: TextView
    private lateinit var textActions: List<Button>

    private val dictionaries by lazy { DictionaryDb.get(this) }
    private val translator by lazy { Translator(dictionaries) }
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var formatter: ResultsFormatter
    private var lookupGeneration = 0

    private val dp by lazy { resources.displayMetrics.density }
    private var systemBars = Insets.NONE
    private var panelAtTop = false
    private var hasText = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge is the default from Android 15 on, but still has to be requested on 14.
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
        // Theme colours are resolved here; the formatter itself runs on the lookup thread.
        formatter = ResultsFormatter(themeColor(android.R.attr.textColorSecondary), themeColor(android.R.attr.colorAccent))
        setContentView(buildContent())
        showHint()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        overlay.blocks = CaptureStore.blocks
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
        if (isFinishing) {
            CaptureStore.onClosed?.invoke()
            CaptureStore.onClosed = null
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

        resultsView = TextView(this).apply {
            setTextIsSelectable(true)
            textSize = 16f
            setLineSpacing(0f, 1.1f)
            setPadding(0, (8 * dp).toInt(), 0, 0)
        }
        resultsScroll = maxHeightScrollView(0.4f)
        resultsScroll.addView(resultsView)
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
            setOnClickListener {
                // Tapping the hint moves the bar to the other edge, uncovering the boxes under it.
                if (!hasText) {
                    panelAtTop = !panelAtTop
                    placePanel()
                }
            }
            elevation = 8 * dp
            background = GradientDrawable().apply {
                setColor(themeColor(android.R.attr.colorBackground))
                cornerRadius = 16 * dp
            }
            val pad = (16 * dp).toInt()
            setPadding(pad, (12 * dp).toInt(), pad, (4 * dp).toInt())
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(resultsScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
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
                val offset = charAt(textView, e.x, e.y)
                // Run after the TextView has handled the tap, which would clear our selection.
                if (offset >= 0) textView.post { lookUpAt(offset) }
                return false
            }
        })
        textView.setOnTouchListener { _, event ->
            taps.onTouchEvent(event)
            false
        }
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
                            Lookup.Found(result, formatter.format(result))
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

    private sealed interface Lookup {
        class Message(val text: String) : Lookup
        class Found(val result: LookupResult, val text: CharSequence) : Lookup
    }

    private fun showLookup(lookup: Lookup) {
        when (lookup) {
            is Lookup.Message -> showResultsText(lookup.text)
            is Lookup.Found -> {
                // Select the matched word so Copy and Look up act on it.
                Selection.setSelection(textView.text as Spannable, lookup.result.start, lookup.result.end)
                showResultsText(lookup.text)
            }
        }
    }

    private fun showResultsText(text: CharSequence) {
        resultsView.text = text
        resultsScroll.visibility = View.VISIBLE
        resultsScroll.scrollTo(0, 0)
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
        setPanelText(
            if (count == 0) getString(R.string.hint_none) else getString(R.string.hint_tap, count),
            atTop = hintAtTop(), isText = false,
        )
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

    private fun showBlock(block: TextBlock) {
        overlay.selected = block
        // Keep the panel away from the text being read.
        setPanelText(block.text, atTop = block.bounds.centerY() > resources.displayMetrics.heightPixels / 2, isText = true)
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
        resultsScroll.visibility = View.GONE
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

    private fun onEmptyTap() {
        if (hasText) showHint() else finish()
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
