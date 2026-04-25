package com.vazbloke.t9controller

import android.graphics.PointF
import kotlin.math.*

// --- Swipe Prediction Engine ---
class SwipeEngine {

    // 1. Group keys strictly by Row to allow for easy Y-axis snapping
    private val row0 = listOf('q' to 0f, 'w' to 1f, 'e' to 2f, 'r' to 3f, 't' to 4f, 'y' to 5f, 'u' to 6f, 'i' to 7f, 'o' to 8f, 'p' to 9f)
    private val row1 = listOf('a' to 0.5f, 's' to 1.5f, 'd' to 2.5f, 'f' to 3.5f, 'g' to 4.5f, 'h' to 5.5f, 'j' to 6.5f, 'k' to 7.5f, 'l' to 8.5f)
    private val row2 = listOf('z' to 1.5f, 'x' to 2.5f, 'c' to 3.5f, 'v' to 4.5f, 'b' to 5.5f, 'n' to 6.5f, 'm' to 7.5f)

    // Expand your dictionary here
    private val dictionary = listOf("hello", "world", "good", "game", "odin", "test", "help", "the", "there", "their")

    fun decodeSwipe(path: List<PointF>): List<String> {
        if (path.size < 2) return emptyList()

        // Apply a moving average to smooth out the curve and eliminate joystick noise
        val smoothedPath = smoothPath(path)

        // Find the sharp corners
        val inflectionPoints = extractInflectionPoints(smoothedPath)

        // Map each point to a cluster of the 3 most likely characters on that row
        val charClusters = inflectionPoints.map { getPossibleChars(it) }

        if (charClusters.isEmpty()) return emptyList()

        val startChars = charClusters.first()
        val endChars = charClusters.last()
        val middleClusters = charClusters.drop(1).dropLast(1)

        // Filter the dictionary using the fuzzy clusters
        return dictionary.filter { word ->
            if (word.length < 2) return@filter false

            // 1. Word MUST start and end with one of the 3 possible keys
            if (!startChars.contains(word.first()) || !endChars.contains(word.last())) return@filter false

            // 2. Word MUST contain at least one letter from each intermediate cluster, in order
            var currentWordIndex = 0
            for (cluster in middleClusters) {
                // indexOfAny checks if ANY character in the cluster exists in the string after currentWordIndex
                val foundIndex = word.indexOfAny(cluster.toCharArray(), currentWordIndex)
                if (foundIndex == -1) return@filter false
                currentWordIndex = foundIndex // Move the search window forward
            }
            true
        }
    }

    private fun getPossibleChars(point: PointF): Set<Char> {
        // 1. Snap to Row based purely on Joystick Y (Up, Center, Down)
        val targetRow = when {
            point.y < 0.5f -> row0 // Joystick Up
            point.y > 1.5f -> row2 // Joystick Down
            else -> row1           // Joystick Center
        }

        // 2. Find the 3 closest keys on that specific row based on X distance
        return targetRow
            .sortedBy { abs(it.second - point.x) }
            .take(3)
            .map { it.first }
            .toSet()
    }

    // #FIXME This should be private. Currently hacked just to get it working
    public fun smoothPath(path: List<PointF>): List<PointF> {
        // A simple 3-point moving average to iron out micro-wobbles
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

    // #FIXME This should be private. Currently hacked just to get it working
    public fun extractInflectionPoints(path: List<PointF>): List<PointF> {
        val points = mutableListOf<PointF>()
        points.add(path.first())

        var lastInflectionIndex = 0

        // "step = 2" means we look slightly behind and ahead of the current point.
        // This calculates the "macro" vector, completely ignoring gentle U-turns.
        val step = 2

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

            // Ensure vectors have length to avoid division by zero
            if (mag1 > 0.1f && mag2 > 0.1f) {
                // Coerce ensures floating point imprecision doesn't crash acos()
                val cosTheta = (dotProduct / (mag1 * mag2)).coerceIn(-1.0f, 1.0f)
                val angle = acos(cosTheta)

                // Threshold: ~60 degrees (1.0 radians).
                // We also require the new point to be at least 'step' distance away from the last one to prevent clustering.
                if (angle > 1.0f && (i - lastInflectionIndex) > step) {
                    points.add(curr)
                    lastInflectionIndex = i
                }
            }
        }

        // Always include the release point
        if (points.last() != path.last()) {
            points.add(path.last())
        }

        return points
    }
}