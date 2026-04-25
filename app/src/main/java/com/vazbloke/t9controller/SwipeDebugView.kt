package com.vazbloke.t9controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View

class SwipeDebugView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val pathPaint = Paint().apply {
        color = Color.parseColor("#4488FF") // Light blue line
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val pointPaint = Paint().apply {
        color = Color.RED // Red dots for inflection points
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val gridPaint = Paint().apply {
        color = Color.DKGRAY
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private var swipePath = listOf<PointF>()
    private var inflectionPoints = listOf<PointF>()

    fun updatePath(rawPath: List<PointF>, inflections: List<PointF>) {
        this.swipePath = rawPath
        this.inflectionPoints = inflections
        invalidate() // Request a redraw
    }

    fun clear() {
        this.swipePath = emptyList()
        this.inflectionPoints = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // The virtual grid is X: 0 to 9, Y: 0 to 2
        // We add a little padding (10f and 3f respectively) so the lines don't clip the edges
        val scaleX = width / 10f
        val scaleY = height / 3f

        // Optional: Draw virtual grid rows for reference
        for (i in 0..2) {
            val y = (i + 0.5f) * scaleY
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
        }

        if (swipePath.isEmpty()) return

        // 1. Draw the continuous path
        val drawPath = Path()
        swipePath.forEachIndexed { index, point ->
            val screenX = (point.x + 0.5f) * scaleX
            val screenY = (point.y + 0.5f) * scaleY

            if (index == 0) drawPath.moveTo(screenX, screenY)
            else drawPath.lineTo(screenX, screenY)
        }
        canvas.drawPath(drawPath, pathPaint)

        // 2. Draw the inflection points (corners)
        for (point in inflectionPoints) {
            val screenX = (point.x + 0.5f) * scaleX
            val screenY = (point.y + 0.5f) * scaleY
            canvas.drawCircle(screenX, screenY, 15f, pointPaint)
        }
    }
}