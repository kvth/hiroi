package io.github.kvth.hiroi

import android.graphics.Rect
import android.graphics.RectF

/**
 * A piece of on-screen text and where it is, in screen coordinates. [characters] are the screen
 * positions of its source text's characters, if the app reports them; [textOffset] is where [text]
 * starts within that source text.
 */
data class TextBlock(
    val text: String,
    val bounds: Rect,
    val characters: List<RectF?>? = null,
    val textOffset: Int = 0,
)

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
            .map { it.copy(text = it.text.trim(), textOffset = it.textOffset + it.text.length - it.text.trimStart().length) }
            .filter { it.text.isNotEmpty() && !it.bounds.isEmpty }
            .distinctBy { it.text to it.bounds }
    }
}
