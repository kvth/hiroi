package io.github.kvth.hiroi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
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
    private val onEmptyTap: () -> Unit,
) : View(context) {

    var blocks: List<TextBlock> = blocks
        set(value) {
            field = value
            selected = null
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
            canvas.drawRoundRect(rect, radius, radius, outline)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            getLocationOnScreen(screenOffset)
            val x = event.x.toInt() + screenOffset[0]
            val y = event.y.toInt() + screenOffset[1]
            // Containers and their children overlap; the smallest box is the one the user means.
            val hit = blocks
                .filter { it.bounds.contains(x, y) }
                .minByOrNull { it.bounds.width().toLong() * it.bounds.height() }
            if (hit != null) onBlockTap(hit, x, y) else onEmptyTap()
        }
        return true
    }
}
