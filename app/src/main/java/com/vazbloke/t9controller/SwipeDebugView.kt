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

        canvas.drawCircle(centerX, centerY, 10f, centerPaint)

        if (swipePath.isEmpty()) return

        val scaleX = width / 2.5f
        val scaleY = height / 2.5f

        // Helper to convert virtual PointF to Screen Coordinates
        fun getScreenPt(pt: PointF): PointF {
            return PointF(centerX + (pt.x * scaleX), centerY + (pt.y * scaleY))
        }

        val drawPath = Path()

        if (swipePath.size == 1) {
            val p = getScreenPt(swipePath[0])
            drawPath.moveTo(p.x, p.y)
            drawPath.lineTo(p.x + 1f, p.y + 1f) // Draw a tiny dot
        } else {
            val firstPt = getScreenPt(swipePath[0])
            drawPath.moveTo(firstPt.x, firstPt.y)

            // 3. BEZIER SPLINE RENDERING
            // This loops through the path and creates mathematically smooth curves
            // instead of rigid straight lines.
            for (i in 0 until swipePath.size - 1) {
                val p1 = getScreenPt(swipePath[i])
                val p2 = getScreenPt(swipePath[i + 1])

                // Calculate the midpoint between the current point and the next
                val midX = (p1.x + p2.x) / 2f
                val midY = (p1.y + p2.y) / 2f

                if (i == 0) {
                    // Start the line smoothly
                    drawPath.lineTo(midX, midY)
                } else {
                    // Pull the curve using the current point as the anchor
                    drawPath.quadTo(p1.x, p1.y, midX, midY)
                }
            }

            // Connect the final segment
            val lastPt = getScreenPt(swipePath.last())
            drawPath.lineTo(lastPt.x, lastPt.y)
        }

        canvas.drawPath(drawPath, pathPaint)

        // Draw the algorithm's detected inflection points
        for (point in inflectionPoints) {
            val p = getScreenPt(point)
            canvas.drawCircle(p.x, p.y, 12f, pointPaint)
        }
    }
}