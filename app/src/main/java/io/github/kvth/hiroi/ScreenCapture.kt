package io.github.kvth.hiroi

import android.accessibilityservice.AccessibilityService
import android.app.assist.AssistStructure
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH
import android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX
import android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
import android.view.accessibility.AccessibilityWindowInfo
import io.github.kvth.hiroi.japanese.Japanese

/** The text on screen, and what the app it's from showed about where it's from (for cards) */
class Capture(val blocks: List<TextBlock>, val source: ScreenSource = ScreenSource())

/**
 * Collects the text of every visible node in all windows. Hiroi's own windows count too (its
 * example section), except while the overlay is showing, so the overlay never reads itself.
 * The app in front (its active window) also tells where its text is from ([ScreenSource]).
 */
object AccessibilityCapture {
    fun capture(service: AccessibilityService): Capture {
        val found = mutableListOf<Found>()
        val source = ScreenSourceCollector()
        var windowTitle: String? = null
        for (window in service.windows) {
            val root = window.root ?: continue
            if (root.packageName == service.packageName && LookupActivity.showing) continue
            val app = window.isActive && window.type == AccessibilityWindowInfo.TYPE_APPLICATION
            if (app) windowTitle = window.title?.toString()
            collect(root, found, source.takeIf { app }, false)
        }
        val characters = found.map { it.text?.let { text -> characterBounds(it.node, text) } }.toMutableList()
        // Chrome (and its WebView) loads where a page's characters are on the first request, and
        // answers that with the whole node's bounds for each one: ask those again once it has them.
        val started = SystemClock.uptimeMillis()
        var waiting = characters.indices.filter { placeholder(characters[it]) }
        while (waiting.isNotEmpty() && SystemClock.uptimeMillis() - started < LOAD_TIMEOUT_MS) {
            Thread.sleep(LOAD_POLL_MS)
            for (i in waiting) characters[i] = characterBounds(found[i].node, found[i].text!!)
            waiting = waiting.filter { placeholder(characters[it]) }
        }
        if (waiting.isNotEmpty()) Log.d(TAG, "${waiting.size} boxes without character positions after ${LOAD_TIMEOUT_MS} ms")
        val blocks = found.mapIndexed { i, f ->
            if (f.text != null) TextBlock(f.text, f.bounds, characters[i]?.takeUnless { i in waiting }) else TextBlock(f.description!!, f.bounds)
        }
        val joined = blocks.indices.groupBy { found[it].lines ?: -1 - it }.values.flatMap { group -> joinLines(group.map(blocks::get)) }
        return Capture(joined, source.result(windowTitle))
    }

    /**
     * A node with text (or, for want of it, a description) and where it is. Nodes that are the
     * [lines] of one web page element share its number.
     */
    private class Found(val node: AccessibilityNodeInfo, val text: String?, val description: String?, val bounds: Rect, val lines: Int? = null)

    /**
     * [source]: the app in front's window, whose nodes also go there; [inToolbar]: [node] is in its
     * app bar; [web]: it's in a web page; [lines]: it's a line of the element with that number.
     */
    private fun collect(
        node: AccessibilityNodeInfo,
        out: MutableList<Found>,
        source: ScreenSourceCollector?,
        inToolbar: Boolean,
        web: Boolean = false,
        lines: Int? = null,
    ) {
        if (!node.isVisibleToUser) return
        val inWeb = web || node.className == "android.webkit.WebView"
        val text = node.text?.toString()
        // An icon's description (back arrow, status bar icons) isn't text on screen.
        val description = node.contentDescription?.toString()
            ?.takeUnless { node.className?.contains("Image") == true }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val hasText = !text.isNullOrBlank() || !description.isNullOrBlank()
        if (hasText) {
            out += Found(node, text?.takeIf { it.isNotBlank() }, description, bounds, lines)
        }
        var toolbar = inToolbar
        if (source != null) {
            val view = SourceNode(
                node.className?.toString(), node.viewIdResourceName?.substringAfter(":id/"), text, node.contentDescription?.toString(),
                node.stateDescription?.toString(), bounds.box(), node.isEditable, node.isShowingHintText,
            )
            toolbar = inToolbar || source.toolbar(view)
            source.visit(view, toolbar)
        }
        // Chrome reports a block's lines as nodes of their own when the page sets each line
        // apart, as mokuro's speech bubbles do: one text-less node with only lines in it.
        val childLines = out.size.takeIf {
            inWeb && !hasText && node.childCount > 1 && (0 until node.childCount).all { i -> node.getChild(i)?.let(::isLine) == true }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collect(it, out, source, toolbar, inWeb, childLines) }
        }
    }

    /** Plain text with nothing in it: not a link, a button or a field */
    private fun isLine(node: AccessibilityNodeInfo) =
        node.isVisibleToUser && node.childCount == 0 && !node.text.isNullOrBlank() && !node.isClickable && !node.isEditable

    /**
     * Joins the [lines] of one element into a block, if they read on from each other: each line
     * within three quarters of a character of the next (the next column of a bubble, the next
     * row; mokuro sets a column in a smaller font where it's narrower), so paragraphs, a line
     * apart, stay apart. Japanese lines without character positions stay as they are (others,
     * such as a bubble's …, aren't asked for theirs).
     */
    private fun joinLines(lines: List<TextBlock>): List<TextBlock> {
        if (lines.size < 2) return lines
        val characters = lines.flatMap { it.characters.orEmpty().filterNotNull() }
        if (lines.any { it.characters == null && it.hasJapanese() } || characters.isEmpty()) return lines
        val slack = characters.map { minOf(it.width(), it.height()) }.sorted()[characters.size / 2] * 3 / 4
        val readsOn = lines.zipWithNext().all { (a, b) ->
            RectF.intersects(RectF(a.bounds).apply { inset(-slack, -slack) }, RectF(b.bounds))
        }
        if (!readsOn) return lines
        val text = StringBuilder()
        val joined = mutableListOf<RectF?>()
        val bounds = Rect(lines[0].bounds)
        for (line in lines) {
            val first = line.text.first()
            // A space only between two words in Latin script, never inside Japanese
            if (text.isNotEmpty() && text.last().isLetterOrDigit() && first.isLetterOrDigit() &&
                !Japanese.isJapanese(text.last()) && !Japanese.isJapanese(first)
            ) {
                text.append(' ')
                joined += null
            }
            text.append(line.text)
            joined += line.text.indices.map { line.characters?.getOrNull(it) }
            bounds.union(line.bounds)
        }
        return listOf(TextBlock(text.toString(), bounds, joined))
    }

    /** Every character in the same place, which is no answer: Chrome's before it has loaded them. */
    private fun placeholder(characters: List<RectF?>?): Boolean =
        characters != null && characters.size > 1 && characters.all { it == characters[0] }

    /**
     * Where each character of [text] is on screen, so a tap on a box can look up the word under it
     * (CharacterLocator). Asked for now, while the app is visible: once the overlay covers it,
     * Android no longer lets the service query its window. Only for Japanese text, to keep the
     * capture quick; null if the app doesn't report positions.
     *
     * Apps built on AndroidX's accessibility classes, Chrome and its WebView among them, offer
     * them under AndroidX's names for the same request ([COMPAT_KEYS]), the framework's apps
     * under the framework's.
     */
    private fun characterBounds(node: AccessibilityNodeInfo, text: String): List<RectF?>? {
        val keys = listOf(FRAMEWORK_KEYS, COMPAT_KEYS).firstOrNull { it.key in node.availableExtraData } ?: return null
        if (text.none(Japanese::isJapanese)) return null
        val args = Bundle().apply {
            putInt(keys.start, 0)
            putInt(keys.length, minOf(text.length, MAX_CHARACTERS))
        }
        if (!node.refreshWithExtraData(keys.key, args)) return null
        val array: Array<out Parcelable?>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            node.extras.getParcelableArray(keys.key, RectF::class.java)
        } else {
            @Suppress("DEPRECATION")
            node.extras.getParcelableArray(keys.key)
        }
        return array?.map { it as? RectF }
    }

    /** The extra data request for character positions: its key, and its arguments' */
    private class LocationKeys(val key: String, val start: String, val length: String)

    private val FRAMEWORK_KEYS = LocationKeys(
        EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH,
    )

    /** AccessibilityNodeInfoCompat's names */
    private val COMPAT_KEYS = LocationKeys(
        "android.core.view.accessibility.extra.DATA_TEXT_CHARACTER_LOCATION_KEY",
        "android.core.view.accessibility.extra.DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX",
        "android.core.view.accessibility.extra.DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH",
    )

    private const val TAG = "HiroiCapture"
    private const val MAX_CHARACTERS = 5000
    private const val LOAD_POLL_MS = 50L
    private const val LOAD_TIMEOUT_MS = 600L
}

/**
 * Fallback when the accessibility service is off: the view tree the system hands to the assistant.
 * Only covers the foreground app (no status bar or notifications) and ignores view transforms.
 */
object AssistStructureCapture {
    fun capture(structure: AssistStructure): Capture {
        val out = mutableListOf<TextBlock>()
        val source = ScreenSourceCollector()
        var windowTitle: String? = null
        for (i in 0 until structure.windowNodeCount) {
            val window = structure.getWindowNodeAt(i)
            windowTitle = windowTitle ?: window.title?.toString()
            collect(window.rootViewNode, window.left, window.top, out, source, false)
        }
        return Capture(out, source.result(windowTitle))
    }

    /** [originX]/[originY] is where the parent's content (after its scroll offset) starts on screen. */
    private fun collect(node: AssistStructure.ViewNode, originX: Int, originY: Int, out: MutableList<TextBlock>, source: ScreenSourceCollector, inToolbar: Boolean) {
        if (node.visibility != View.VISIBLE) return
        val x = originX + node.left
        val y = originY + node.top
        val bounds = Rect(x, y, x + node.width, y + node.height)
        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()
        if (!text.isNullOrBlank()) {
            out += TextBlock(text, bounds)
        }
        val view = SourceNode(
            node.className, node.idEntry, node.text?.toString(), node.contentDescription?.toString(), null, bounds.box(), editable = node.inputType != 0,
        )
        val toolbar = inToolbar || source.toolbar(view)
        source.visit(view, toolbar)
        for (i in 0 until node.childCount) {
            collect(node.getChildAt(i), x - node.scrollX, y - node.scrollY, out, source, toolbar)
        }
    }
}

fun Rect.box() = Box(left, top, right, bottom)

fun Box.rect() = Rect(left, top, right, bottom)
