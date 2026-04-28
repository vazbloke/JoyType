package com.vazbloke.t9controller

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class SwipeDebugView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val pathPaint = Paint().apply {
        color = Color.parseColor("#4488FF")
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#33FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val textPaint = Paint().apply {
        color = Color.parseColor("#88FFFFFF")
        textSize = 60f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val weightTextPaint = Paint().apply {
        color = Color.parseColor("#A3FF00")
        textSize = 30f
        isAntiAlias = true
    }

    private val pointPaint = Paint().apply { color = Color.RED; style = Paint.Style.FILL; isAntiAlias = true }

    private var swipePath = listOf<PointF>()
    private var inflectionPoints = listOf<PointF>()
    private var probabilityMaps = listOf<Map<Char, Float>>()

    fun updateJoyT9Debug(rawPath: List<PointF>, inflections: List<PointF>, probs: List<Map<Char, Float>>) {
        this.swipePath = rawPath
        this.inflectionPoints = inflections
        this.probabilityMaps = probs
        invalidate()
    }

    fun clear() {
        swipePath = emptyList(); inflectionPoints = emptyList(); probabilityMaps = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val size = Math.min(width, height) * 0.7f
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        val step = size / 3f

        // Draw 3x3 Grid
        for (i in 0..3) {
            canvas.drawLine(left + (i * step), top, left + (i * step), top + size, gridPaint)
            canvas.drawLine(left, top + (i * step), left + size, top + (i * step), gridPaint)
        }

        // Draw Numbers 1-9
        val digits = listOf('1','2','3','4','5','6','7','8','9')
        var idx = 0
        for (y in 0..2) {
            for (x in 0..2) {
                val cx = left + (x * step) + (step / 2f)
                val cy = top + (y * step) + (step / 2f) + 20f
                canvas.drawText(digits[idx++].toString(), cx, cy, textPaint)
            }
        }

        // Map internal [-1, 1] coordinates to screen space
        fun getScreenPt(pt: PointF): PointF {
            val sx = left + ((pt.x + 1f) / 2f) * size
            val sy = top + ((pt.y + 1f) / 2f) * size
            return PointF(sx, sy)
        }

        // Draw Path
        if (swipePath.isNotEmpty()) {
            val drawPath = Path()
            val firstPt = getScreenPt(swipePath[0])
            drawPath.moveTo(firstPt.x, firstPt.y)

            for (i in 0 until swipePath.size - 1) {
                val p1 = getScreenPt(swipePath[i])
                val p2 = getScreenPt(swipePath[i + 1])
                val midX = (p1.x + p2.x) / 2f
                val midY = (p1.y + p2.y) / 2f
                if (i == 0) drawPath.lineTo(midX, midY) else drawPath.quadTo(p1.x, p1.y, midX, midY)
            }
            val lastPt = getScreenPt(swipePath.last())
            drawPath.lineTo(lastPt.x, lastPt.y)
            canvas.drawPath(drawPath, pathPaint)
        }

        // Draw Inflections
        for (point in inflectionPoints) {
            val p = getScreenPt(point)
            canvas.drawCircle(p.x, p.y, 15f, pointPaint)
        }

        // Draw Probabilistic Weights on the sides
        var textY = 100f
        for ((index, map) in probabilityMaps.withIndex()) {
            val top3 = map.entries.sortedByDescending { it.value }.take(3)
            val display = "Input ${index+1}: " + top3.joinToString(", ") { "${it.key}(${(it.value * 100).toInt()}%)" }

            // Alternate left and right side of the screen
            if (index % 2 == 0) {
                weightTextPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(display, 20f, textY, weightTextPaint)
            } else {
                weightTextPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(display, width - 20f, textY, weightTextPaint)
                textY += 60f // Step down after drawing both sides
            }
        }
    }
}