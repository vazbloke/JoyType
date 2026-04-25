package com.vazbloke.t9controller

import android.graphics.PointF
import kotlin.math.*

class SwipeEngine {

    // 1. Define the spatial map of a QWERTY keyboard
    // X, Y coordinates representing keys. Row 0 is top, Row 2 is bottom.
    private val keyboardLayout = mapOf(
        'q' to PointF(0f, 0f), 'w' to PointF(1f, 0f), 'e' to PointF(2f, 0f), 'r' to PointF(3f, 0f), 't' to PointF(4f, 0f), 'y' to PointF(5f, 0f), 'u' to PointF(6f, 0f), 'i' to PointF(7f, 0f), 'o' to PointF(8f, 0f), 'p' to PointF(9f, 0f),
        'a' to PointF(0.5f, 1f), 's' to PointF(1.5f, 1f), 'd' to PointF(2.5f, 1f), 'f' to PointF(3.5f, 1f), 'g' to PointF(4.5f, 1f), 'h' to PointF(5.5f, 1f), 'j' to PointF(6.5f, 1f), 'k' to PointF(7.5f, 1f), 'l' to PointF(8.5f, 1f),
        'z' to PointF(1.5f, 2f), 'x' to PointF(2.5f, 2f), 'c' to PointF(3.5f, 2f), 'v' to PointF(4.5f, 2f), 'b' to PointF(5.5f, 2f), 'n' to PointF(6.5f, 2f), 'm' to PointF(7.5f, 2f)
    )

    // A tiny sample dictionary for testing.
    private val dictionary = listOf("hello", "world", "good", "game", "odin", "test", "help")

    fun decodeSwipe(path: List<PointF>): List<String> {
//        Does not detect the path properly. Always returns less than 2
        if (path.size < 2) return emptyList()

        val inflectionPoints = extractInflectionPoints(path)
        val extractedLetters = inflectionPoints.map { getNearestChar(it) }.distinct()

        if (extractedLetters.isEmpty()) return emptyList()

        val startChar = extractedLetters.first()
        val endChar = extractedLetters.last()

        // Filter the dictionary:
        // 1. Must start and end with the roughly correct letters
        // 2. Must contain the intermediate inflection letters in order
        return dictionary.filter { word ->
            if (word.first() != startChar || word.last() != endChar) return@filter false

            var wordIndex = 0
            for (letter in extractedLetters) {
                val foundIndex = word.indexOf(letter, wordIndex)
                if (foundIndex == -1) return@filter false
                wordIndex = foundIndex
            }
            true
        }
    }

    private fun extractInflectionPoints(path: List<PointF>): List<PointF> {
        val points = mutableListOf<PointF>()
        points.add(path.first()) // Always include the start point

        for (i in 1 until path.size - 1) {
            val prev = path[i - 1]
            val curr = path[i]
            val next = path[i + 1]

            // Calculate vectors
            val v1x = curr.x - prev.x
            val v1y = curr.y - prev.y
            val v2x = next.x - curr.x
            val v2y = next.y - curr.y

            // Calculate angle between vectors using dot product
            val dotProduct = (v1x * v2x) + (v1y * v2y)
            val mag1 = sqrt(v1x*v1x + v1y*v1y)
            val mag2 = sqrt(v2x*v2x + v2y*v2y)

            if (mag1 > 0 && mag2 > 0) {
                val angle = acos(dotProduct / (mag1 * mag2))
                // If direction changes by more than ~45 degrees (0.78 radians), it's a corner
                if (angle > 0.78f) {
                    points.add(curr)
                }
            }
        }

        points.add(path.last()) // Always include the end point
        return points
    }

    private fun getNearestChar(point: PointF): Char {
        return keyboardLayout.minByOrNull {
            // Distance formula
            sqrt((it.value.x - point.x).pow(2) + (it.value.y - point.y).pow(2))
        }?.key ?: 'a'
    }
}