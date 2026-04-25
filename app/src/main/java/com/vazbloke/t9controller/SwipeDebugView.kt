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
        color = Color.parseColor("#884488FF") // Semi-transparent blue
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

    private val textPaint = Paint().apply {
        color = Color.parseColor("#44FFFFFF") // Faint white for keyboard map
        textSize = 40f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private var swipePath = listOf<PointF>()
    private var inflectionPoints = listOf<PointF>()

    // The exact spatial map the algorithm uses
    private val keyboardLayout = mapOf(
        'q' to PointF(0f, 0f), 'w' to PointF(1f, 0f), 'e' to PointF(2f, 0f), 'r' to PointF(3f, 0f), 't' to PointF(4f, 0f), 'y' to PointF(5f, 0f), 'u' to PointF(6f, 0f), 'i' to PointF(7f, 0f), 'o' to PointF(8f, 0f), 'p' to PointF(9f, 0f),
        'a' to PointF(0.5f, 1f), 's' to PointF(1.5f, 1f), 'd' to PointF(2.5f, 1f), 'f' to PointF(3.5f, 1f), 'g' to PointF(4.5f, 1f), 'h' to PointF(5.5f, 1f), 'j' to PointF(6.5f, 1f), 'k' to PointF(7.5f, 1f), 'l' to PointF(8.5f, 1f),
        'z' to PointF(1.5f, 2f), 'x' to PointF(2.5f, 2f), 'c' to PointF(3.5f, 2f), 'v' to PointF(4.5f, 2f), 'b' to PointF(5.5f, 2f), 'n' to PointF(6.5f, 2f), 'm' to PointF(7.5f, 2f)
    )

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

        val scaleX = width / 10f
        val scaleY = height / 3f

        // 1. Draw the Keyboard Map
        for ((char, point) in keyboardLayout) {
            val screenX = (point.x + 0.5f) * scaleX
            // Adjust Y slightly so text centers well
            val screenY = (point.y + 0.5f) * scaleY + (textPaint.textSize / 3)
            canvas.drawText(char.uppercaseChar().toString(), screenX, screenY, textPaint)
        }

        if (swipePath.isEmpty()) return

        // 2. Draw the continuous path
        val drawPath = Path()
        swipePath.forEachIndexed { index, point ->
            val screenX = (point.x + 0.5f) * scaleX
            val screenY = (point.y + 0.5f) * scaleY

            if (index == 0) drawPath.moveTo(screenX, screenY)
            else drawPath.lineTo(screenX, screenY)
        }
        canvas.drawPath(drawPath, pathPaint)

        // 3. Draw the inflection points
        for (point in inflectionPoints) {
            val screenX = (point.x + 0.5f) * scaleX
            val screenY = (point.y + 0.5f) * scaleY
            canvas.drawCircle(screenX, screenY, 12f, pointPaint)
        }
    }
}