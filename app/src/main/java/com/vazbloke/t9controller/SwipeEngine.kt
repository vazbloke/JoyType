package com.vazbloke.t9controller

// --- Swipe Prediction Engine ---
import android.graphics.PointF
import kotlin.math.*

class SwipeEngine {

    private val keyboardLayout = mapOf(
        'q' to PointF(0f, 0f), 'w' to PointF(1f, 0f), 'e' to PointF(2f, 0f), 'r' to PointF(3f, 0f), 't' to PointF(4f, 0f), 'y' to PointF(5f, 0f), 'u' to PointF(6f, 0f), 'i' to PointF(7f, 0f), 'o' to PointF(8f, 0f), 'p' to PointF(9f, 0f),
        'a' to PointF(0.5f, 1f), 's' to PointF(1.5f, 1f), 'd' to PointF(2.5f, 1f), 'f' to PointF(3.5f, 1f), 'g' to PointF(4.5f, 1f), 'h' to PointF(5.5f, 1f), 'j' to PointF(6.5f, 1f), 'k' to PointF(7.5f, 1f), 'l' to PointF(8.5f, 1f),
        'z' to PointF(1.5f, 2f), 'x' to PointF(2.5f, 2f), 'c' to PointF(3.5f, 2f), 'v' to PointF(4.5f, 2f), 'b' to PointF(5.5f, 2f), 'n' to PointF(6.5f, 2f), 'm' to PointF(7.5f, 2f)
    )

    private val dictionary = listOf("hello", "world", "good", "game", "odin", "test", "help", "rest", "the", "there", "their")

    // Calculates the anchor point on the QWERTY grid based on the joystick's initial tilt (-1 to 1)
    private fun getStartAnchorChars(joystickX: Float, joystickY: Float): Set<Char> {
        // Map joystick [-1, 1] to Keyboard [0, 9] and [0, 2]
        val virtualX = ((joystickX + 1f) / 2f) * 9f
        val virtualY = ((joystickY + 1f) / 2f) * 2f
        val anchorPoint = PointF(virtualX, virtualY)

        // Return the 4 closest letters to allow for sloppy starting positions
        return keyboardLayout.entries
            .sortedBy { getDistance(it.value, anchorPoint.x, anchorPoint.y) }
            .take(4)
            .map { it.key }
            .toSet()
    }

    fun decodeSwipe(rawPath: List<PointF>, startJoyX: Float, startJoyY: Float): List<String> {
        val startChars = getStartAnchorChars(startJoyX, startJoyY)

        // Handle micro-swipes (just tapping L1 on a letter)
        if (rawPath.size < 3) {
            return dictionary.filter { startChars.contains(it.first()) && it.length == 1 }
        }

        val smoothed = smoothPath(rawPath)
        val inflections = extractInflectionPoints(smoothed)
        val userAngles = calculateAngles(inflections)

        // Score words based on how closely their angle sequences match the user's
        return dictionary
            .filter { startChars.contains(it.first()) }
            .map { word -> word to scoreWordShape(word, userAngles) }
            .filter { it.second < 2.5f } // Threshold: discard terrible matches
            .sortedBy { it.second }      // Lowest score is the best match
            .map { it.first }
    }

    private fun scoreWordShape(word: String, userAngles: List<Float>): Float {
        // Remove consecutive duplicate letters ("hello" -> "helo") because
        // you don't draw a corner for double letters.
        val collapsedWord = word.replace(Regex("(.)\\1+"), "$1")
        val idealAngles = getIdealAnglesForWord(collapsedWord)

        var penaltyScore = 0f
        val maxLen = max(userAngles.size, idealAngles.size)

        // Greedily compare the angles.
        for (i in 0 until maxLen) {
            val userAng = userAngles.getOrNull(i)
            val idealAng = idealAngles.getOrNull(i)

            if (userAng != null && idealAng != null) {
                penaltyScore += angleDiff(userAng, idealAng)
            } else {
                // Penalize for missing or extra corners (approx 90 degrees penalty)
                penaltyScore += PI.toFloat() / 2f
            }
        }
        return penaltyScore
    }

    private fun getIdealAnglesForWord(word: String): List<Float> {
        if (word.length < 2) return emptyList()
        val points = word.mapNotNull { keyboardLayout[it] }

        val rawAngles = calculateAngles(points)
        val collapsedAngles = mutableListOf<Float>()

        // If a word makes a straight line (e.g., "WER"), we collapse the matching angles
        // into a single vector so it expects one long, straight swipe.
        if (rawAngles.isNotEmpty()) {
            collapsedAngles.add(rawAngles.first())
            for (i in 1 until rawAngles.size) {
                if (angleDiff(rawAngles[i], collapsedAngles.last()) > 0.4f) { // ~20 degrees
                    collapsedAngles.add(rawAngles[i])
                }
            }
        }
        return collapsedAngles
    }

    private fun calculateAngles(points: List<PointF>): List<Float> {
        val angles = mutableListOf<Float>()
        for (i in 0 until points.size - 1) {
            val dx = points[i+1].x - points[i].x
            val dy = points[i+1].y - points[i].y
            angles.add(atan2(dy, dx)) // Returns radians
        }
        return angles
    }

    private fun angleDiff(a: Float, b: Float): Float {
        var diff = abs(a - b)
        while (diff > PI) diff -= (2 * PI).toFloat()
        return abs(diff)
    }

    // (Keep your existing smoothPath and extractInflectionPoints functions here)
    fun smoothPath(path: List<PointF>): List<PointF> {
        if (path.size < 3) return path
        val smoothed = mutableListOf<PointF>()
        smoothed.add(path.first())
        for (i in 1 until path.size - 1) {
            val avgX = (path[i-1].x + path[i].x + path[i+1].x) / 3f
            val avgY = (path[i-1].y + path[i].y + path[i+1].y) / 3f
            smoothed.add(PointF(avgX, avgY))
        }
        smoothed.add(path.last())
        return smoothed
    }

    fun extractInflectionPoints(path: List<PointF>): List<PointF> {
        val points = mutableListOf<PointF>()
        points.add(path.first())
        var lastInflectionIndex = 0
        val step = 3

        for (i in step until path.size - step) {
            val prev = path[i - step]
            val curr = path[i]
            val next = path[i + step]

            val v1x = curr.x - prev.x
            val v1y = curr.y - prev.y
            val v2x = next.x - curr.x
            val v2y = next.y - curr.y

            val dotProduct = (v1x * v2x) + (v1y * v2y)
            val mag1 = sqrt(v1x*v1x + v1y*v1y)
            val mag2 = sqrt(v2x*v2x + v2y*v2y)

            if (mag1 > 0.1f && mag2 > 0.1f) {
                val cosTheta = (dotProduct / (mag1 * mag2)).coerceIn(-1.0f, 1.0f)
                val angle = acos(cosTheta)

                if (angle > 1.3f && (i - lastInflectionIndex) > step) {
                    points.add(curr)
                    lastInflectionIndex = i
                }
            }
        }
        if (points.last() != path.last()) points.add(path.last())
        return points
    }

    private fun getDistance(p1: PointF, x2: Float, y2: Float): Float {
        return sqrt((x2 - p1.x).toDouble().pow(2.0) + (y2 - p1.y).toDouble().pow(2.0)).toFloat()
    }
}