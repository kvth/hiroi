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

/**
 * The text inside [region] (screen coordinates), top to bottom: of boxes with character
 * positions, from the first character whose centre is inside to the last (so a sentence
 * wrapped over lines reads on, as a selection would); of
 * others, the whole text if at least half of the box is inside. A box's text that another
 * picked box's text already has (a container around its items) comes once.
 */
fun regionText(blocks: List<TextBlock>, region: Rect): String {
    val area = RectF(region)
    class Piece(val block: TextBlock, val text: String)
    val pieces = blocks.mapNotNull { block ->
        val characters = block.characters
        val text = if (characters != null && block.text.indices.any { characters.getOrNull(block.textOffset + it) != null }) {
            val inside = block.text.indices.filter { i ->
                characters.getOrNull(block.textOffset + i)?.let { area.contains(it.centerX(), it.centerY()) } == true
            }
            if (inside.isEmpty()) "" else block.text.substring(inside.first(), inside.last() + 1)
        } else {
            val inside = Rect(block.bounds)
            val covered = if (inside.intersect(region)) inside.width().toLong() * inside.height() else 0L
            block.text.takeIf { covered * 2 >= block.bounds.width().toLong() * block.bounds.height() }.orEmpty()
        }
        text.trim().takeIf { it.isNotEmpty() }?.let { Piece(block, it) }
    }
    return pieces
        .filterNot { piece -> pieces.any { other -> other.text.length > piece.text.length && piece.text in other.text } }
        .sortedWith(compareBy({ it.block.bounds.top }, { it.block.bounds.left }))
        .map { it.text }
        .distinct()
        .joinToString("\n")
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
