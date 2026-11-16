package io.github.kvth.hiroi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** Dims the screen and outlines every captured text block; reports which block was tapped. */
@SuppressLint("ViewConstructor")
class OverlayView(
    context: Context,
    blocks: List<TextBlock>,
    /** The tapped block and the tap's screen coordinates */
    private val onBlockTap: (TextBlock, Int, Int) -> Unit,
    /** A tap outside every box, with its screen coordinates */
    private val onEmptyTap: (Int, Int) -> Unit,
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
    private val highlightFill = Paint().apply { color = 0x66FFB300 }
    private val highlightLine = Paint().apply { color = 0xFFFF8F00.toInt() }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0x33000000)
        getLocationOnScreen(screenOffset)
        val radius = 4 * dp
        for (block in blocks) {
            rect.set(block.bounds)
            rect.offset(-screenOffset[0].toFloat(), -screenOffset[1].toFloat())
            if (block.ocr) drawRecognizedText(canvas, block)
            canvas.drawRoundRect(rect, radius, radius, if (block === selected) selectedFill else fill)
            canvas.drawRoundRect(rect, radius, radius, if (block.ocr) ocrOutline else outline)
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

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            getLocationOnScreen(screenOffset)
            val x = event.x.toInt() + screenOffset[0]
            val y = event.y.toInt() + screenOffset[1]
            val hit = blockAt(x, y)
            if (hit != null) onBlockTap(hit, x, y) else onEmptyTap(x, y)
        }
        return true
    }

    /** The box at screen point ([x], [y]); containers and their children overlap, so the smallest. */
    fun blockAt(x: Int, y: Int): TextBlock? = blocks
        .filter { it.bounds.contains(x, y) }
        .minByOrNull { it.bounds.width().toLong() * it.bounds.height() }
}
