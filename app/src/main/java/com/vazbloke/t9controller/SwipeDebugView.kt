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

    private val subTextPaint = Paint().apply {
        color = Color.parseColor("#55FFFFFF")
        textSize = 25f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val weightTextPaint = Paint().apply {
        color = Color.parseColor("#A3FF00")
        textSize = 30f
        isAntiAlias = true
    }

    private val typeTextPaint = Paint().apply {
        color = Color.parseColor("#FFA500") // Orange for heuristic text
        textSize = 40f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }

    // NEW: Neon glow for registered peaks!
    private val glowPaint = Paint().apply {
        color = Color.parseColor("#66A3FF00") // Transparent Neon Green
        style = Paint.Style.FILL
        isAntiAlias = true
        maskFilter = BlurMaskFilter(50f, BlurMaskFilter.Blur.NORMAL) // Android's native glow effect
    }

    private val pointPaint = Paint().apply { color = Color.RED; style = Paint.Style.FILL; isAntiAlias = true }

    private var swipePath = listOf<PointF>()
    private var registeredPeaks = listOf<PointF>()
    private var probabilityMaps = listOf<Map<Char, Float>>()
    private var detectionType = ""

    fun updateJoyT9Debug(rawPath: List<PointF>, peaks: List<PointF>, probs: List<Map<Char, Float>>, type: String) {
        this.swipePath = rawPath
        this.registeredPeaks = peaks
        this.probabilityMaps = probs
        this.detectionType = type
        invalidate()
    }

    fun clear() {
        swipePath = emptyList(); registeredPeaks = emptyList(); probabilityMaps = emptyList(); detectionType = ""
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val size = Math.min(width, height) * 0.7f
        val left = (width - size) / 2f
        val top = (height - size) / 2f + 40f // Shifted down slightly to make room for heuristic text
        val step = size / 3f

        // Map internal [-1, 1] coordinates to screen space
        fun getScreenPt(pt: PointF): PointF {
            val sx = left + ((pt.x + 1f) / 2f) * size
            val sy = top + ((pt.y + 1f) / 2f) * size
            return PointF(sx, sy)
        }

        // Draw Heuristic Text at the top
        if (detectionType.isNotEmpty()) {
            canvas.drawText("[$detectionType]", width / 2f, 80f, typeTextPaint)
        }

        // Draw Glowing Highlights for Registered Peaks FIRST (so they stay behind text/grid)
        for (peak in registeredPeaks) {
            val screenPt = getScreenPt(peak)
            // Draw a massive glowing circle behind the selected zone
            canvas.drawCircle(screenPt.x, screenPt.y, step * 0.6f, glowPaint)
        }

        // Draw 3x3 Grid
        for (i in 0..3) {
            canvas.drawLine(left + (i * step), top, left + (i * step), top + size, gridPaint)
            canvas.drawLine(left, top + (i * step), left + size, top + (i * step), gridPaint)
        }

        // Draw Numbers 1-9 AND Subtext
        val digits = listOf('1','2','3','4','5','6','7','8','9')
        val subTexts = listOf("jkl", "abc", "def", "ghi", "...", "mno", "pqrs", "tuv", "wxyz")
        var idx = 0
        for (y in 0..2) {
            for (x in 0..2) {
                val cx = left + (x * step) + (step / 2f)
                val cy = top + (y * step) + (step / 2f) + 10f 
                canvas.drawText(digits[idx].toString(), cx, cy, textPaint)
                canvas.drawText(subTexts[idx], cx, cy + 35f, subTextPaint)
                idx++
            }
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
            
            // Draw the current thumb position as a red dot
            canvas.drawCircle(lastPt.x, lastPt.y, 15f, pointPaint)
        }

        // Draw Probabilistic Weights on the sides
        var textY = 140f
        for ((index, map) in probabilityMaps.withIndex()) {
            val top3 = map.entries.sortedByDescending { it.value }.take(3)
            val display = "T${index+1}: " + top3.joinToString(", ") { "${it.key}(${(it.value * 100).toInt()}%)" }

            if (index % 2 == 0) {
                weightTextPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(display, 20f, textY, weightTextPaint)
            } else {
                weightTextPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(display, width - 20f, textY, weightTextPaint)
                textY += 60f 
            }
        }
    }
}