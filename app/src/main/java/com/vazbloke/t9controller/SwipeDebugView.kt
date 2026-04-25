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
        color = Color.parseColor("#4488FF") // Bright blue line
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val pointPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val centerPaint = Paint().apply {
        color = Color.DKGRAY
        style = Paint.Style.FILL
    }

    private var swipePath = listOf<PointF>()
    private var inflectionPoints = listOf<PointF>()

    fun updatePath(rawPath: List<PointF>, inflections: List<PointF>) {
        this.swipePath = rawPath
        this.inflectionPoints = inflections
        invalidate()
    }

    fun clear() {
        this.swipePath = emptyList()
        this.inflectionPoints = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f

        // Draw a subtle center dot representing the joystick's resting position
        canvas.drawCircle(centerX, centerY, 10f, centerPaint)

        if (swipePath.isEmpty()) return

        // Map joystick bounds (-1 to 1) to the canvas size
        val scaleX = width / 2.5f
        val scaleY = height / 2.5f

        val drawPath = Path()
        swipePath.forEachIndexed { index, point ->
            val screenX = centerX + (point.x * scaleX)
            val screenY = centerY + (point.y * scaleY)

            if (index == 0) drawPath.moveTo(screenX, screenY)
            else drawPath.lineTo(screenX, screenY)
        }
        canvas.drawPath(drawPath, pathPaint)

        for (point in inflectionPoints) {
            val screenX = centerX + (point.x * scaleX)
            val screenY = centerY + (point.y * scaleY)
            canvas.drawCircle(screenX, screenY, 12f, pointPaint)
        }
    }
}