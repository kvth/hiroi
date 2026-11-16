package io.github.kvth.hiroi.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import io.github.kvth.hiroi.TextBlock
import io.github.kvth.hiroi.japanese.Japanese

/**
 * Reads text in a screenshot with ML Kit's on-device Japanese recognizer (bundled model, no
 * network or Play Services). Each recognized line becomes a [TextBlock] with a box per character,
 * so tapping it works like tapping app text.
 */
object ScreenOcr {
    private val recognizer by lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }

    /** Blocks until ML Kit is done, so call it off the main thread. */
    fun recognize(screenshot: Bitmap): List<TextBlock> {
        val text = Tasks.await(recognizer.process(InputImage.fromBitmap(screenshot, 0)))
        return text.textBlocks.flatMap { it.lines }.mapNotNull(::toBlock)
    }

    /**
     * Builds the line's text from its symbols (characters) rather than taking Line.text, so every
     * character has its box. ML Kit splits lines into elements; a space goes between two elements
     * only where both sides are Latin (words), never inside Japanese.
     */
    private fun toBlock(line: Text.Line): TextBlock? {
        val bounds = line.boundingBox ?: return null
        val text = StringBuilder()
        val characters = mutableListOf<RectF?>()
        for (element in line.elements) {
            val symbols = element.symbols
            if (symbols.isEmpty() || symbols.any { it.boundingBox == null }) {
                return TextBlock(line.text, bounds, ocr = true) // no character boxes: tap opens the panel
            }
            val first = symbols.first().text.firstOrNull()
            if (text.isNotEmpty() && first != null && !Japanese.isJapanese(text.last()) && !Japanese.isJapanese(first)) {
                text.append(' ')
                characters += null
            }
            for (symbol in symbols) {
                text.append(symbol.text)
                repeat(symbol.text.length) { characters += RectF(symbol.boundingBox) }
            }
        }
        if (text.isBlank()) return null
        return TextBlock(text.toString(), bounds, characters, ocr = true)
    }

    /**
     * Whether [block] (from OCR) repeats text the app already provides: it mostly lies inside an
     * app box that has the same text. Lying inside isn't enough: apps like Instagram describe a
     * whole picture ("写真、…"), and the text in the picture must still count.
     */
    fun duplicates(block: TextBlock, appText: List<TextBlock>): Boolean {
        val area = block.bounds.width().toLong() * block.bounds.height()
        if (area <= 0) return true
        val text = compact(block.text)
        return appText.any { other ->
            val overlap = Rect()
            overlap.setIntersect(block.bounds, other.bounds) &&
                overlap.width().toLong() * overlap.height() * 2 >= area &&
                compact(other.text).let { it.contains(text) || text.contains(it) }
        }
    }

    private fun compact(text: String) = text.filterNot { it.isWhitespace() }
}
