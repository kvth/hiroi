package io.github.kvth.hiroi

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import io.github.kvth.hiroi.japanese.Japanese

/**
 * A piece of on-screen text and where it is, in screen coordinates. [characters] are the screen
 * positions of its source text's characters, if known; [textOffset] is where [text] starts within
 * that source text. [ocr] marks text read from the screenshot rather than from the app.
 */
data class TextBlock(
    val text: String,
    val bounds: Rect,
    val characters: List<RectF?>? = null,
    val textOffset: Int = 0,
    val ocr: Boolean = false,
)

/** Screen rectangles of [text]'s characters [start] to [end], one per line; empty without character positions. */
fun TextBlock.lineRects(start: Int, end: Int): List<RectF> {
    val characters = characters ?: return emptyList()
    val lines = mutableListOf<RectF>()
    for (i in start until end) {
        val rect = characters.getOrNull(textOffset + i) ?: continue
        val last = lines.lastOrNull()
        if (last != null && rect.centerY() in last.top..last.bottom) last.union(rect) else lines += RectF(rect)
    }
    return lines
}

/** Only text with Japanese in it gets a box; the rest (English labels, numbers, icons) is noise here. */
fun TextBlock.hasJapanese() = text.any(Japanese::isJapanese)

/** Hands the latest capture from the trigger (accessibility button or assistant) to [LookupActivity]. */
object CaptureStore {
    @Volatile
    var blocks: List<TextBlock> = emptyList()
        private set

    /** The app the text came from (for a card's {document-title}). */
    @Volatile
    var sourcePackage: String? = null

    /** The screen as it was when the overlay was triggered, for OCR; null if there is none. */
    @Volatile
    var screenshot: Bitmap? = null

    /** Called once when [LookupActivity] goes away, so an assistant session can end itself. */
    @Volatile
    var onClosed: (() -> Unit)? = null

    fun update(raw: List<TextBlock>) {
        blocks = raw
            .map { it.copy(text = it.text.trim(), textOffset = it.textOffset + it.text.length - it.text.trimStart().length) }
            .filter { it.hasJapanese() && !it.bounds.isEmpty }
            .distinctBy { it.text to it.bounds }
            .let(::withoutRepeats)
    }

    /** Drops containers that only repeat the text of a box inside them (a toolbar around its title). */
    private fun withoutRepeats(blocks: List<TextBlock>): List<TextBlock> = blocks.filterNot { outer ->
        blocks.any { inner ->
            inner !== outer && inner.text == outer.text && outer.bounds.contains(inner.bounds) && outer.bounds != inner.bounds
        }
    }
}
