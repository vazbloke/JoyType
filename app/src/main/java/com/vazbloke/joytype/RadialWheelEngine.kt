package com.vazbloke.joytype

import kotlin.math.atan2

class RadialWheelEngine(val maxSectors: Int, private val listener: RadialWheelListener) {

    var radialPage = 0
        private set
    var radialSelectedIndex = 0
        private set
        
    // THE NEW SINGLE SOURCE OF TRUTH
    var absoluteIndex = 0
        private set

    private var radialLastSector = -1
    private var isPeggedAtStart = false
    private var isPeggedAtEnd = false

    private var isSelectorActive = false

    var candidates = listOf<String>()

    interface RadialWheelListener {
        fun onIndexChanged(newIndex: Int, page: Int)
        fun onTick()
        fun onThud()
    }

    fun setSelectorActivationState(activationState: Boolean) {
        isSelectorActive = activationState
    }

    /**
     * Feeds the joystick data into the engine. 
     * Calculates octants, crossovers, pagination, and triggers haptics.
     */
    fun updateInput(x: Float, y: Float, mag: Float, disabledIndices: Set<Int> = emptySet()) {
        if (mag <= 0.3f) {
            // Stick released -> Reset scroll states
            radialLastSector = -1
            isPeggedAtStart = false
            isPeggedAtEnd = false
            return
        }

        val totalItemsCount = candidates.size

        var angle = atan2(y.toDouble(), x.toDouble())
        angle += Math.PI / 2.0
        if (angle < 0) angle += 2 * Math.PI

        val sectorAngle = (2 * Math.PI) / maxSectors
        val sector = Math.round(angle / sectorAngle).toInt() % maxSectors
        val maxPages = kotlin.math.ceil(totalItemsCount.toDouble() / maxSectors).toInt().coerceAtLeast(1)

        if (radialLastSector != -1 && sector != radialLastSector) {
            var delta = sector - radialLastSector
            
            val half = maxSectors / 2.0
            if (delta > half) delta -= maxSectors
            if (delta < -half) delta += maxSectors

            val isMovingForward = delta > 0
            val isMovingBackward = delta < 0
            var justPegged = false

            if (radialLastSector == maxSectors - 1 && sector == 0) {
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
            // Counter-Clockwise crossover
            else if (radialLastSector == 0 && sector == maxSectors - 1) {
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

        radialLastSector = sector

        if (totalItemsCount > 0) {
            val itemsOnCurrentPage = if (radialPage == maxPages - 1 && totalItemsCount % maxSectors != 0) {
                totalItemsCount % maxSectors
            } else {
                kotlin.math.min(maxSectors, totalItemsCount)
            }

            val newIndex = if (isPeggedAtStart) {
                0
            } else if (isPeggedAtEnd) {
                itemsOnCurrentPage - 1
            } else {
                sector.coerceAtMost(itemsOnCurrentPage - 1)
            }

            var snappedIndex = newIndex
            if (disabledIndices.contains(snappedIndex) && disabledIndices.size < itemsOnCurrentPage) {
                var left = snappedIndex
                var right = snappedIndex
                while(true) {
                    right++
                    if (right < itemsOnCurrentPage && !disabledIndices.contains(right)) {
                        snappedIndex = right
                        break
                    }
                    left--
                    if (left >= 0 && !disabledIndices.contains(left)) {
                        snappedIndex = left
                        break
                    }
                    if (left < 0 && right >= itemsOnCurrentPage) break 
                }
            }

            if (snappedIndex != radialSelectedIndex) {
                radialSelectedIndex = snappedIndex
                absoluteIndex = (radialPage * maxSectors) + radialSelectedIndex
                
                if (!isPeggedAtStart && !isPeggedAtEnd) {
                    listener.onTick()
                }
                listener.onIndexChanged(radialSelectedIndex, radialPage)
            }
        }
    }

    // --- NEW LINEAR INDEX MANAGERS ---

    fun cycleForward() {
        if (candidates.isEmpty()) return
        setAbsoluteIndex((absoluteIndex + 1) % candidates.size)
        listener.onTick()
        listener.onIndexChanged(radialSelectedIndex, radialPage)
    }

    fun cycleBackward() {
        if (candidates.isEmpty()) return
        setAbsoluteIndex((absoluteIndex - 1 + candidates.size) % candidates.size)
        listener.onTick()
        listener.onIndexChanged(radialSelectedIndex, radialPage)
    }

    fun setAbsoluteIndex(index: Int) {
        if (candidates.isEmpty()) return
        absoluteIndex = index.coerceIn(0, candidates.size - 1)
        radialPage = absoluteIndex / maxSectors
        radialSelectedIndex = absoluteIndex % maxSectors
    }

    fun reset() {
        radialPage = 0
        radialSelectedIndex = 0
        absoluteIndex = 0
        radialLastSector = -1
        isPeggedAtStart = false
        isPeggedAtEnd = false
    }
}