package io.github.kvth.hiroi

import android.accessibilityservice.AccessibilityService
import android.app.assist.AssistStructure
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH
import android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX
import android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
import io.github.kvth.hiroi.japanese.Japanese

/**
 * Collects the text of every visible node in all windows. Hiroi's own windows count too (its
 * example section), except while the overlay is showing, so the overlay never reads itself.
 */
object AccessibilityCapture {
    fun capture(service: AccessibilityService): List<TextBlock> {
        val out = mutableListOf<TextBlock>()
        for (window in service.windows) {
            val root = window.root ?: continue
            if (root.packageName == service.packageName && LookupActivity.showing) continue
            collect(root, out)
        }
        return out
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<TextBlock>) {
        if (!node.isVisibleToUser) return
        val text = node.text?.toString()
        val description = node.contentDescription?.toString()
        if (!text.isNullOrBlank() || !description.isNullOrBlank()) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            out += if (!text.isNullOrBlank()) TextBlock(text, bounds, characterBounds(node, text)) else TextBlock(description!!, bounds)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collect(it, out) }
        }
    }

    /**
     * Where each character of [text] is on screen, so a tap on a box can look up the word under it
     * (CharacterLocator). Asked for now, while the app is visible: once the overlay covers it,
     * Android no longer lets the service query its window. Only for Japanese text, to keep the
     * capture quick; null if the app doesn't report positions.
     */
    private fun characterBounds(node: AccessibilityNodeInfo, text: String): List<RectF?>? {
        if (EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY !in node.availableExtraData) return null
        if (text.none(Japanese::isJapanese)) return null
        val args = Bundle().apply {
            putInt(EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
            putInt(EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, minOf(text.length, MAX_CHARACTERS))
        }
        if (!node.refreshWithExtraData(EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args)) return null
        val array: Array<out Parcelable?>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            node.extras.getParcelableArray(EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, RectF::class.java)
        } else {
            @Suppress("DEPRECATION")
            node.extras.getParcelableArray(EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)
        }
        return array?.map { it as? RectF }
    }

    private const val MAX_CHARACTERS = 5000
}

/**
 * Fallback when the accessibility service is off: the view tree the system hands to the assistant.
 * Only covers the foreground app (no status bar or notifications) and ignores view transforms.
 */
object AssistStructureCapture {
    fun capture(structure: AssistStructure): List<TextBlock> {
        val out = mutableListOf<TextBlock>()
        for (i in 0 until structure.windowNodeCount) {
            val window = structure.getWindowNodeAt(i)
            collect(window.rootViewNode, window.left, window.top, out)
        }
        return out
    }

    /** [originX]/[originY] is where the parent's content (after its scroll offset) starts on screen. */
    private fun collect(node: AssistStructure.ViewNode, originX: Int, originY: Int, out: MutableList<TextBlock>) {
        if (node.visibility != View.VISIBLE) return
        val x = originX + node.left
        val y = originY + node.top
        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()
        if (!text.isNullOrBlank()) {
            out += TextBlock(text, Rect(x, y, x + node.width, y + node.height))
        }
        for (i in 0 until node.childCount) {
            collect(node.getChildAt(i), x - node.scrollX, y - node.scrollY, out)
        }
    }
}
