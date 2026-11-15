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

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0x33000000)
        getLocationOnScreen(screenOffset)
        val radius = 4 * dp
        for (block in blocks) {
            rect.set(block.bounds)
            rect.offset(-screenOffset[0].toFloat(), -screenOffset[1].toFloat())
            canvas.drawRoundRect(rect, radius, radius, if (block === selected) selectedFill else fill)
            canvas.drawRoundRect(rect, radius, radius, if (block.ocr) ocrOutline else outline)
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
