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

/** A TextView that marks character ranges like the overlay marks words on screen. */
class MarkedTextView(context: Context) : TextView(context) {
    /** [start] to [end] in the text, in [color] */
    class Mark(val start: Int, val end: Int, val color: Int)

    var marks: List<Mark> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var markStyle = MarkStyle()

    private val paint = Paint()
    private val rect = RectF()
    private val dp = resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val layout = layout
        if (marks.isNotEmpty() && layout != null) {
            canvas.save()
            canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
            for (mark in marks) {
                val end = minOf(mark.end, text.length)
                if (mark.start >= end) continue
                for (line in layout.getLineForOffset(mark.start)..layout.getLineForOffset(end - 1)) {
                    val start = maxOf(mark.start, layout.getLineStart(line))
                    val stop = minOf(end, layout.getLineEnd(line))
                    val left = layout.getPrimaryHorizontal(start)
                    val right = if (stop >= layout.getLineEnd(line)) layout.getLineRight(line) else layout.getPrimaryHorizontal(stop)
                    rect.set(minOf(left, right), layout.getLineTop(line).toFloat(), maxOf(left, right), layout.getLineBottom(line).toFloat())
                    markStyle.draw(canvas, rect, mark.color, paint, dp)
                }
            }
            canvas.restore()
        }
        super.onDraw(canvas)
    }
}
