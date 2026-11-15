package io.github.kvth.hiroi

import android.accessibilityservice.AccessibilityService
import android.app.assist.AssistStructure
import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

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
        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()
        if (!text.isNullOrBlank()) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            out += TextBlock(text, bounds)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collect(it, out) }
        }
    }
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
