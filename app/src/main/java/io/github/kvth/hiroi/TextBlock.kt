package io.github.kvth.hiroi

import android.graphics.Rect

/** A piece of on-screen text and where it is, in screen coordinates. */
data class TextBlock(val text: String, val bounds: Rect)

/** Hands the latest capture from the trigger (accessibility button or assistant) to [LookupActivity]. */
object CaptureStore {
    @Volatile
    var blocks: List<TextBlock> = emptyList()
        private set

    /** Called once when [LookupActivity] goes away, so an assistant session can end itself. */
    @Volatile
    var onClosed: (() -> Unit)? = null

    fun update(raw: List<TextBlock>) {
        blocks = raw
            .map { it.copy(text = it.text.trim()) }
            .filter { it.text.isNotEmpty() && !it.bounds.isEmpty }
            .distinctBy { it.text to it.bounds }
    }
}
