package io.github.kvth.hiroi

import kotlin.math.hypot

/**
 * Finds which character of a text block was tapped, from the character positions captured with
 * it (see AccessibilityCapture.characterBounds). Standard text views and most Compose text report
 * them; for apps that don't (some WebViews, games, custom views) this gives null, and the user taps
 * the word in the panel instead.
 */
object CharacterLocator {
    /** Index into [block]'s text of the character at screen point ([x], [y]). */
    fun characterAt(block: TextBlock, x: Int, y: Int): Int? {
        val characters = block.characters ?: return null
        // The character under the tap, or the nearest one within half a line (taps between lines).
        var best = -1
        var bestDistance = Float.MAX_VALUE
        for ((i, rect) in characters.withIndex()) {
            if (rect == null || rect.isEmpty) continue
            val dx = maxOf(rect.left - x, 0f, x - rect.right)
            val dy = maxOf(rect.top - y, 0f, y - rect.bottom)
            val distance = hypot(dx, dy)
            if (distance < bestDistance && distance <= rect.height() / 2) {
                best = i
                bestDistance = distance
            }
        }
        val offset = best - block.textOffset
        return offset.takeIf { best >= 0 && it in block.text.indices }
    }
}
