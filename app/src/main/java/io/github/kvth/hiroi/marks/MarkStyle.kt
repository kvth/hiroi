package io.github.kvth.hiroi.marks

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.widget.TextView

/** How marked words look, as AnkiMark's "Style" and "Background strength". */
data class MarkStyle(val underline: Boolean = false, val opacity: Int = 35) {

    /** Marks [rect] (one line of a word) in [color]. */
    fun draw(canvas: Canvas, rect: RectF, color: Int, paint: Paint, dp: Float) {
        if (underline) {
            paint.color = color or 0xFF000000.toInt()
            canvas.drawRect(rect.left, rect.bottom - 2 * dp, rect.right, rect.bottom, paint)
        } else {
            paint.color = (color and 0xFFFFFF) or ((opacity.coerceIn(5, 100) * 255 / 100) shl 24)
            canvas.drawRoundRect(rect, 2 * dp, 2 * dp, paint)
        }
    }

    companion object {
        fun of(settings: MarkSettings) = MarkStyle(settings.style == "underline", settings.opacity)
    }
}

/**
 * A TextView that marks character ranges like the overlay marks words on screen, and the
 * looked-up word like the overlay's highlight.
 */
class MarkedTextView(context: Context) : TextView(context) {
    /** [start] to [end] in the text, in [color] */
    class Mark(val start: Int, val end: Int, val color: Int)

    var marks: List<Mark> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var markStyle = MarkStyle()

    /** The looked-up word, start to end; for views that don't show it as a selection. */
    var highlight: Pair<Int, Int>? = null
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint()
    private val highlightFill = Paint().apply { color = 0x66FFB300 }
    private val highlightLine = Paint().apply { color = 0xFFFF8F00.toInt() }
    private val rect = RectF()
    private val dp = resources.displayMetrics.density

    /** The index of the character under (x, y), not the nearest gap between characters; -1 if none. */
    fun characterAt(x: Float, y: Float): Int {
        val layout = layout ?: return -1
        if (text.isEmpty()) return -1
        val line = layout.getLineForVertical((y - totalPaddingTop + scrollY).toInt())
        val lineX = x - totalPaddingLeft + scrollX
        var offset = layout.getOffsetForHorizontal(line, lineX)
        if (offset > layout.getLineStart(line) && layout.getPrimaryHorizontal(offset) > lineX) offset--
        return offset.coerceAtMost(text.length - 1)
    }

    override fun onDraw(canvas: Canvas) {
        if (layout != null && (marks.isNotEmpty() || highlight != null)) {
            canvas.save()
            canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
            for (mark in marks) forEachLine(mark.start, mark.end) { markStyle.draw(canvas, rect, mark.color, paint, dp) }
            highlight?.let { (start, end) ->
                forEachLine(start, end) {
                    canvas.drawRect(rect, highlightFill)
                    canvas.drawRect(rect.left, rect.bottom - 2 * dp, rect.right, rect.bottom, highlightLine)
                }
            }
            canvas.restore()
        }
        super.onDraw(canvas)
    }

    /** Sets [rect] to each line's part of [start]..[end] in turn. */
    private inline fun forEachLine(start: Int, end: Int, draw: () -> Unit) {
        val layout = layout
        val stop = minOf(end, text.length)
        if (start >= stop) return
        for (line in layout.getLineForOffset(start)..layout.getLineForOffset(stop - 1)) {
            val from = maxOf(start, layout.getLineStart(line))
            val to = minOf(stop, layout.getLineEnd(line))
            val left = layout.getPrimaryHorizontal(from)
            val right = if (to >= layout.getLineEnd(line)) layout.getLineRight(line) else layout.getPrimaryHorizontal(to)
            rect.set(minOf(left, right), layout.getLineTop(line).toFloat(), maxOf(left, right), layout.getLineBottom(line).toFloat())
            draw()
        }
    }
}
