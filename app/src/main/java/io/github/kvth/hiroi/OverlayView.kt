package io.github.kvth.hiroi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Insets
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import io.github.kvth.hiroi.marks.MarkStyle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Dims the screen and outlines every captured text block; reports which block was tapped. */
@SuppressLint("ViewConstructor")
class OverlayView(
    context: Context,
    blocks: List<TextBlock>,
    /** The tapped block and the tap's screen coordinates */
    private val onBlockTap: (TextBlock, Int, Int) -> Unit,
    /** A tap outside every box, with its screen coordinates */
    private val onEmptyTap: (Int, Int) -> Unit,
    /** A region dragged out on the screen, in screen coordinates */
    private val onRegion: (Rect) -> Unit = {},
) : View(context) {

    var blocks: List<TextBlock> = blocks
        set(value) {
            field = value
            selected = null
        }

    /** Adds boxes (from OCR) without losing the current selection. */
    fun addBlocks(more: List<TextBlock>) {
        if (more.isEmpty()) return
        val keep = selected
        blocks = blocks + more
        selected = keep
    }

    /** The looked-up word's characters on screen, one rectangle per line. */
    var highlight: List<RectF> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var selected: TextBlock? = null
        set(value) {
            field = value
            invalidate()
        }

    /** The region whose text the panel shows (or the one being dragged out), in screen coordinates */
    var region: Rect? = null
        set(value) {
            field = value
            invalidate()
        }

    /** A word marked in its rule's [color] (from Anki, see marks/), one rectangle per line. */
    class Mark(val lines: List<RectF>, val color: Int)

    var marks: List<Mark> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /**
     * The screen as it was when Hiroi was triggered, shown instead of the live one so that what
     * the boxes are about stays (a toast or notification that times out, a video, an animation).
     * Null shows the live screen.
     */
    var frozen: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    /** The system bars, which stay live: the system draws them over the overlay anyway. */
    var liveBars: Insets = Insets.NONE
        set(value) {
            field = value
            invalidate()
        }

    /** How marks look: a background of [opacity] percent, or an underline. */
    var markStyle = MarkStyle()
        set(value) {
            field = value
            invalidate()
        }

    private val dp = resources.displayMetrics.density
    private val screenOffset = IntArray(2)
    private val rect = RectF()

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * dp
        color = 0xCC4A90FF.toInt()
    }
    private val ocrOutline = Paint(outline).apply { pathEffect = DashPathEffect(floatArrayOf(6 * dp, 4 * dp), 0f) }
    private val fill = Paint().apply { color = 0x1A4A90FF }
    private val selectedFill = Paint().apply { color = 0x664A90FF }
    private val ocrBackground = Paint().apply { color = 0xEEFFFFFF.toInt() }
    private val ocrText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A1A1A.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val markPaint = Paint()
    private val highlightFill = Paint().apply { color = 0x66FFB300 }
    private val highlightLine = Paint().apply { color = 0xFFFF8F00.toInt() }
    private val regionFill = Paint().apply { color = 0x22FFB300 }
    private val regionOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * dp
        color = 0xFFFF8F00.toInt()
        pathEffect = DashPathEffect(floatArrayOf(8 * dp, 5 * dp), 0f)
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(screenOffset)
        frozen?.let { picture ->
            canvas.save()
            canvas.clipRect(liveBars.left, liveBars.top, width - liveBars.right, height - liveBars.bottom)
            canvas.drawBitmap(picture, -screenOffset[0].toFloat(), -screenOffset[1].toFloat(), null)
            canvas.restore()
        }
        canvas.drawColor(0x33000000)
        val radius = 4 * dp
        for (block in blocks) {
            rect.set(block.bounds)
            rect.offset(-screenOffset[0].toFloat(), -screenOffset[1].toFloat())
            if (block.ocr) drawRecognizedText(canvas, block)
            canvas.drawRoundRect(rect, radius, radius, if (block === selected) selectedFill else fill)
            canvas.drawRoundRect(rect, radius, radius, if (block.ocr) ocrOutline else outline)
        }
        for (mark in marks) {
            for (line in mark.lines) {
                rect.set(line)
                rect.offset(-screenOffset[0].toFloat(), -screenOffset[1].toFloat())
                markStyle.draw(canvas, rect, mark.color, markPaint, dp)
            }
        }
        region?.let {
            rect.set(it)
            rect.offset(-screenOffset[0].toFloat(), -screenOffset[1].toFloat())
            canvas.drawRect(rect, regionFill)
            canvas.drawRect(rect, regionOutline)
        }
        for (line in highlight) {
            rect.set(line)
            rect.offset(-screenOffset[0].toFloat(), -screenOffset[1].toFloat())
            canvas.drawRect(rect, highlightFill)
            canvas.drawRect(rect.left, rect.bottom - 2 * dp, rect.right, rect.bottom, highlightLine)
        }
    }

    /**
     * OCR text drawn over the picture where it was found, each character in its own box, so it's
     * visible what was read (works for vertical text too, whose boxes run downwards).
     */
    private fun drawRecognizedText(canvas: Canvas, block: TextBlock) {
        val characters = block.characters
        val dx = -screenOffset[0].toFloat()
        val dy = -screenOffset[1].toFloat()
        val area = RectF(block.bounds).apply { offset(dx, dy) }
        canvas.drawRect(area, ocrBackground)
        if (characters == null) {
            ocrText.textSize = minOf(area.height() * 0.8f, area.width() / maxOf(block.text.length, 1) * 1.1f)
            canvas.drawText(block.text, area.centerX(), area.centerY() - (ocrText.ascent() + ocrText.descent()) / 2, ocrText)
            return
        }
        for (i in block.text.indices) {
            val box = characters.getOrNull(block.textOffset + i) ?: continue
            ocrText.textSize = maxOf(box.height(), box.width()) * 0.9f
            val x = box.centerX() + dx
            val y = box.centerY() + dy - (ocrText.ascent() + ocrText.descent()) / 2
            canvas.drawText(block.text, i, i + 1, x, y, ocrText)
        }
    }

    private val dragSlop = ViewConfiguration.get(context).scaledTouchSlop * 2
    private var downX = 0
    private var downY = 0
    private var dragging = false
    private var regionBefore: Rect? = null

    /** A tap picks a box; a drag selects the region between where it started and ended. */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        getLocationOnScreen(screenOffset)
        val x = event.x.toInt() + screenOffset[0]
        val y = event.y.toInt() + screenOffset[1]
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                dragging = false
                regionBefore = region
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (abs(x - downX) > dragSlop || abs(y - downY) > dragSlop)) dragging = true
                if (dragging) region = Rect(min(downX, x), min(downY, y), max(downX, x), max(downY, y))
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    region?.let(onRegion)
                } else {
                    val hit = blockAt(x, y)
                    if (hit != null) onBlockTap(hit, x, y) else onEmptyTap(x, y)
                }
            }
            MotionEvent.ACTION_CANCEL -> if (dragging) region = regionBefore
        }
        return true
    }

    /**
     * The box at screen point ([x], [y]); containers and their children overlap, so the smallest,
     * and of boxes as small (a link around its text), one that knows where its characters are.
     */
    fun blockAt(x: Int, y: Int): TextBlock? = blocks
        .filter { it.bounds.contains(x, y) }
        .minWithOrNull(compareBy<TextBlock> { it.bounds.width().toLong() * it.bounds.height() }.thenBy { it.characters == null })
}
