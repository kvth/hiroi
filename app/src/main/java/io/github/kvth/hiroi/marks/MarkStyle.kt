package io.github.kvth.hiroi.marks

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.widget.TextView
import kotlin.math.ceil

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
 * looked-up word like the overlay's highlight; with [furigana], readings over its words.
 */
class MarkedTextView(context: Context) : TextView(context) {
    /** [start] to [end] in the text, in [color] */
    class Mark(val start: Int, val end: Int, val color: Int)

    /** [reading] over [start] to [end] in the text, as furigana */
    class Ruby(val start: Int, val end: Int, val reading: String)

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

    /**
     * Leaves room above each line for [rubies]. It stays while the text changes, so the text
     * doesn't move down and back up as its readings are found again.
     */
    var furigana = false
        set(value) {
            if (field == value) return
            field = value
            makeRoom()
        }

    /** Readings to draw over the text, in the room [furigana] leaves */
    var rubies: List<Ruby> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** The room above each line for furigana, in px: extra line spacing, and padding above the first line */
    private var room = 0

    private val paint = Paint()
    private val rubyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val highlightFill = Paint().apply { color = 0x66FFB300 }
    private val highlightLine = Paint().apply { color = 0xFFFF8F00.toInt() }
    private val rect = RectF()
    private val dp = resources.displayMetrics.density

    /** The index of the character under (x, y), not the nearest gap between characters; -1 if none. */
    fun characterAt(x: Float, y: Float): Int {
        val layout = layout ?: return -1
        if (text.isEmpty()) return -1
        val lineY = y - totalPaddingTop + scrollY
        var line = layout.getLineForVertical(lineY.toInt())
        // In the furigana over the next line: that line's
        if (room > 0 && line < layout.lineCount - 1 && lineY >= layout.getLineBottom(line) - room) line++
        val lineX = x - totalPaddingLeft + scrollX
        var offset = layout.getOffsetForHorizontal(line, lineX)
        if (offset > layout.getLineStart(line) && layout.getPrimaryHorizontal(offset) > lineX) offset--
        return offset.coerceAtMost(text.length - 1)
    }

    override fun onDraw(canvas: Canvas) {
        if (layout != null && (marks.isNotEmpty() || highlight != null || room > 0 && rubies.isNotEmpty())) {
            canvas.save()
            canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
            for (mark in marks) forEachLine(mark.start, mark.end) { _, _ -> markStyle.draw(canvas, rect, mark.color, paint, dp) }
            highlight?.let { (start, end) ->
                forEachLine(start, end) { _, _ ->
                    canvas.drawRect(rect, highlightFill)
                    canvas.drawRect(rect.left, rect.bottom - 2 * dp, rect.right, rect.bottom, highlightLine)
                }
            }
            if (room > 0) drawRubies(canvas)
            canvas.restore()
        }
        super.onDraw(canvas)
    }

    /**
     * Each reading centred over its characters, in the room above their line, squeezed to fit
     * them up to a point. A word broken over lines gets a share of its reading on each.
     */
    private fun drawRubies(canvas: Canvas) {
        rubyPaint.color = currentTextColor
        rubyPaint.typeface = typeface
        rubyPaint.textLocales = super.getPaint().textLocales // [paint] is the marks'
        val descent = rubyPaint.fontMetrics.descent
        for (ruby in rubies) {
            val length = ruby.end - ruby.start
            if (length <= 0 || ruby.reading.isEmpty()) continue
            var shown = 0
            forEachLine(ruby.start, ruby.end) { from, to ->
                val share = if (to >= ruby.end) ruby.reading.length else (ruby.reading.length * (to - ruby.start) / length).coerceIn(shown, ruby.reading.length)
                val part = ruby.reading.substring(shown, share)
                shown = share
                if (part.isNotEmpty()) {
                    rubyPaint.textScaleX = 1f
                    val width = rubyPaint.measureText(part)
                    if (width > rect.width()) rubyPaint.textScaleX = maxOf(MIN_RUBY_SCALE_X, rect.width() / width)
                    val x = rect.centerX() - rubyPaint.measureText(part) / 2
                    canvas.drawText(part, x, rect.top - descent, rubyPaint)
                }
            }
        }
        rubyPaint.textScaleX = 1f
    }

    /** Sets the line spacing and padding for [furigana] (or back), for the text size set now. */
    private fun makeRoom() {
        rubyPaint.textSize = textSize * RUBY_SIZE
        val wanted = if (!furigana) 0 else rubyPaint.fontMetrics.let { ceil(it.descent - it.ascent).toInt() }
        if (wanted == room) return
        setLineSpacing(lineSpacingExtra - room + wanted, lineSpacingMultiplier)
        setPadding(paddingLeft, paddingTop - room + wanted, paddingRight, paddingBottom)
        room = wanted
    }

    /**
     * Sets [rect] to each line's part of [start]..[end] in turn, which is [draw]n with its start
     * and end in the text. The room for the next line's furigana isn't part of a line.
     */
    private inline fun forEachLine(start: Int, end: Int, draw: (from: Int, to: Int) -> Unit) {
        val layout = layout
        val stop = minOf(end, text.length)
        if (start >= stop) return
        for (line in layout.getLineForOffset(start)..layout.getLineForOffset(stop - 1)) {
            val from = maxOf(start, layout.getLineStart(line))
            val to = minOf(stop, layout.getLineEnd(line))
            val left = layout.getPrimaryHorizontal(from)
            val right = if (to >= layout.getLineEnd(line)) layout.getLineRight(line) else layout.getPrimaryHorizontal(to)
            val bottom = layout.getLineBottom(line) - if (line < layout.lineCount - 1) room else 0
            rect.set(minOf(left, right), layout.getLineTop(line).toFloat(), maxOf(left, right), bottom.toFloat())
            draw(from, to)
        }
    }

    private companion object {
        /** Furigana's size, of the text's */
        const val RUBY_SIZE = 0.5f

        /** How narrow a reading longer than its characters gets, before it's wider than them */
        const val MIN_RUBY_SCALE_X = 0.6f
    }
}
