package com.vazbloke.joytype

import kotlin.math.atan2

class RadialWheelEngine(private val listener: RadialWheelListener) {

    // --- State ---
    var radialPage = 0
        private set
    var radialSelectedIndex = 0
        private set

    private var radialLastOctant = -1
    private var isPeggedAtStart = false
    private var isPeggedAtEnd = false

    // --- Callbacks ---
    interface RadialWheelListener {
        fun onIndexChanged(newIndex: Int, page: Int)
        fun onTick()
        fun onThud()
    }

    /**
     * Feeds the joystick data into the engine. 
     * Calculates octants, crossovers, pagination, and triggers haptics.
     */
    fun updateInput(x: Float, y: Float, mag: Float, totalItemsCount: Int) {
        if (mag <= 0.3f) {
            // Stick released -> Reset scroll states
            radialLastOctant = -1
            isPeggedAtStart = false
            isPeggedAtEnd = false
            return
        }

        var angle = atan2(y.toDouble(), x.toDouble())
        angle += Math.PI / 2.0
        if (angle < 0) angle += 2 * Math.PI

        val octant = Math.round(angle / (Math.PI / 4.0)).toInt() % 8
        val maxPages = kotlin.math.ceil(totalItemsCount / 8.0).toInt().coerceAtLeast(1)

        if (radialLastOctant != -1 && octant != radialLastOctant) {
            var delta = octant - radialLastOctant
            if (delta > 4) delta -= 8
            if (delta < -4) delta += 8

            val isMovingForward = delta > 0
            val isMovingBackward = delta < 0
            var justPegged = false

            // Clockwise crossover (7 to 0)
            if (radialLastOctant == 7 && octant == 0) {
                if (isPeggedAtStart) {
                    isPeggedAtStart = false
                    listener.onTick()
                } else if (radialPage < maxPages - 1) {
                    radialPage++
                } else if (!isPeggedAtEnd) {
                    isPeggedAtEnd = true
                    justPegged = true
                    listener.onThud()
                }
            }
            // Counter-Clockwise crossover (0 to 7)
            else if (radialLastOctant == 0 && octant == 7) {
                if (isPeggedAtEnd) {
                    isPeggedAtEnd = false
                    listener.onTick()
                } else if (radialPage > 0) {
                    radialPage--
                } else if (!isPeggedAtStart) {
                    isPeggedAtStart = true
                    justPegged = true
                    listener.onThud()
                }
            }

            // The Directional Grinding Gear
            if (!justPegged) {
                if (isPeggedAtStart) {
                    if (isMovingBackward) listener.onThud() else listener.onTick()
                } else if (isPeggedAtEnd) {
                    if (isMovingForward) listener.onThud() else listener.onTick()
                }
            }
        }

        radialLastOctant = octant

        if (totalItemsCount > 0) {
            // Calculate how many items are actually on the current page
            val itemsOnCurrentPage = if (radialPage == maxPages - 1 && totalItemsCount % 8 != 0) {
                totalItemsCount % 8
            } else {
                kotlin.math.min(8, totalItemsCount)
            }

            val newIndex = if (isPeggedAtStart) {
                0
            } else if (isPeggedAtEnd) {
                itemsOnCurrentPage - 1
            } else {
                octant.coerceAtMost(itemsOnCurrentPage - 1)
            }

            // Only update and tick if the index actually changed
            if (newIndex != radialSelectedIndex) {
                radialSelectedIndex = newIndex
                if (!isPeggedAtStart && !isPeggedAtEnd) {
                    listener.onTick()
                }
                listener.onIndexChanged(radialSelectedIndex, radialPage)
            }
        }
    }

    fun reset() {
        radialPage = 0
        radialSelectedIndex = 0
        radialLastOctant = -1
        isPeggedAtStart = false
        isPeggedAtEnd = false
    }
}