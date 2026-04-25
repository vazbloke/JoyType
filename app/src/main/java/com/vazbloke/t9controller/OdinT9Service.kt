package com.vazbloke.t9controller

import android.graphics.PointF
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.pow

class OdinT9Service : InputMethodService() {

    private lateinit var tvPredictions: TextView
    private lateinit var tvMode: TextView
    private val t9Engine = T9Engine()
    private val swipeEngine = SwipeEngine()

    enum class InputMode {
        LJOY_RBUTTONS, JOY_JOY, SWIPE
    }

    private var currentMode = InputMode.LJOY_RBUTTONS
    private var currentSequence = ""
    private var currentPredictions = listOf<String>()
    private var predictionIndex = 0
    private var currentJoystickRow = 1

    private var lastLJoyDirection = -1
    private var lastRJoyDirection = -1

    private var analogL2Down = false
    private var digitalL2Down = false
    private var analogR2Down = false
    private var digitalR2Down = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var backspaceRepeatRunnable: Runnable? = null

    // Swipe State
    private var isSwiping = false
    private var cursorX = 4.5f
    private var cursorY = 1.0f
    private val cursorSpeed = 0.5f
    private val currentSwipePath = mutableListOf<PointF>()

    private lateinit var swipeDebugView: SwipeDebugView

    // New State Variables for Swipe Anchor
    private var anchorJoyX = 0f
    private var anchorJoyY = 0f
    private var currentJoyX = 0f
    private var currentJoyY = 0f

    /**
     * Maps a circular joystick input [-1, 1] to a square bounding box [-1, 1].
     * This ensures that "riding the gate" produces straight lines.
     */
    private fun mapCircleToSquare(u: Float, v: Float): PointF {
        if (u == 0f && v == 0f) return PointF(0f, 0f)

        // Get the current magnitude of the joystick push
        val radius = Math.sqrt((u * u + v * v).toDouble()).toFloat()
        val normalizedRadius = radius.coerceAtMost(1f)

        // Find the angle of the joystick
        val theta = Math.atan2(v.toDouble(), u.toDouble()).toFloat()

        // Calculate the multiplier needed to stretch this specific angle to the edge of a square
        val cosTheta = Math.abs(Math.cos(theta.toDouble())).toFloat()
        val sinTheta = Math.abs(Math.sin(theta.toDouble())).toFloat()
        val scale = 1f / Math.max(cosTheta, sinTheta)

        // Apply the stretch
        val mappedRadius = normalizedRadius * scale

        // Convert back to Cartesian coordinates
        val x = mappedRadius * Math.cos(theta.toDouble()).toFloat()
        val y = mappedRadius * Math.sin(theta.toDouble()).toFloat()

        return PointF(x.coerceIn(-1f, 1f), y.coerceIn(-1f, 1f))
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        tvPredictions = view.findViewById(R.id.tv_predictions)
        tvMode = view.findViewById(R.id.tv_mode)
        swipeDebugView = view.findViewById(R.id.swipe_debug_view)

        view.findViewById<View>(R.id.btn_toggle_mode).setOnClickListener {
            toggleMode()
        }

        updateModeUI()
        return view
    }

    private fun toggleMode() {
        currentMode = when (currentMode) {
            InputMode.LJOY_RBUTTONS -> InputMode.JOY_JOY
            InputMode.JOY_JOY -> InputMode.SWIPE
            InputMode.SWIPE -> InputMode.LJOY_RBUTTONS
        }
        currentSequence = ""
        updatePredictions()
        updateModeUI()
        swipeDebugView.clear()
    }

    private fun updateModeUI() {
        tvMode.text = when (currentMode) {
            InputMode.LJOY_RBUTTONS -> "Mode: LJoy RButtons"
            InputMode.JOY_JOY -> "Mode: Joy Joy"
            InputMode.SWIPE -> "Mode: Swipe"
        }
        if (currentMode == InputMode.SWIPE) {
            tvPredictions.text = "Hold L1 to Swipe"
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!isInputViewShown) {
            return super.onGenericMotionEvent(event)
        }

        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) {

            val lTrigger = max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
            val rTrigger = max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))

            updateL2State(lTrigger > 0.5f, isAnalog = true)
            updateR2State(rTrigger > 0.5f, isAnalog = true)

            when (currentMode) {
                InputMode.LJOY_RBUTTONS -> {
                    val yAxis = event.getAxisValue(MotionEvent.AXIS_Y)
                    currentJoystickRow = when {
                        yAxis < -0.5f -> 0 // Up
                        yAxis > 0.5f -> 2  // Down
                        else -> 1          // Center
                    }
                    return true
                }
                InputMode.JOY_JOY -> {
                    val x = event.getAxisValue(MotionEvent.AXIS_X)
                    val y = event.getAxisValue(MotionEvent.AXIS_Y)
                    val z = event.getAxisValue(MotionEvent.AXIS_Z)
                    val rz = event.getAxisValue(MotionEvent.AXIS_RZ)

                    handleJoystickDirection(x, y, isLeft = true)
                    handleJoystickDirection(z, rz, isLeft = false)
                    return true
                }
                InputMode.SWIPE -> {
                    var rawX = event.getAxisValue(MotionEvent.AXIS_X)
                    var rawY = event.getAxisValue(MotionEvent.AXIS_Y)

                    if (Math.abs(rawX) < 0.01f && Math.abs(rawY) < 0.01f) {
                        rawX = event.getAxisValue(MotionEvent.AXIS_Z)
                        rawY = event.getAxisValue(MotionEvent.AXIS_RZ)
                    }

                    // --- NEW: Map the physical circular input to a virtual square space ---
                    val squareMappedPoint = mapCircleToSquare(rawX, rawY)

                    // Always track the mapped state so it's ready when L1 is pressed
                    currentJoyX = squareMappedPoint.x
                    currentJoyY = squareMappedPoint.y

                    if (isSwiping) {
                        // We only care about the shape relative to the start point
                        val relativeX = currentJoyX - anchorJoyX
                        val relativeY = currentJoyY - anchorJoyY

                        val lastPoint = currentSwipePath.lastOrNull()

                        // 0.1f distance threshold to filter out hardware micro-jitter
                        if (lastPoint == null || getDistance(lastPoint, relativeX, relativeY) > 0.1f) {
                            currentSwipePath.add(PointF(relativeX, relativeY))
// 0.1f distance threshold to filter out hardware micro-jitter
                            if (lastPoint == null || getDistance(lastPoint, relativeX, relativeY) > 0.1f) {
                                currentSwipePath.add(PointF(relativeX, relativeY))

                                // Removed smoothed path. Pass raw path directly to RDP.
                                val corners = swipeEngine.extractInflectionPoints(currentSwipePath)

                                mainHandler.post {
                                    // Draw the raw path and the mathematically extracted corners
                                    swipeDebugView.updatePath(currentSwipePath, corners)
                                    val displayCorners = Math.max(0, corners.size - 2)
                                    tvPredictions.text = "Shape Corners: $displayCorners"
                                }
                            }
                        }
                    }
                    return true
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    private fun getDistance(p1: PointF, x2: Float, y2: Float): Float {
        return sqrt((x2 - p1.x).toDouble().pow(2.0) + (y2 - p1.y).toDouble().pow(2.0)).toFloat()
    }

    private fun handleJoystickDirection(x: Float, y: Float, isLeft: Boolean): Boolean {
        val mag = sqrt((x * x + y * y).toDouble())
        if (mag < 0.25f) {
            if (isLeft) lastLJoyDirection = -1 else lastRJoyDirection = -1
            return false
        }

        if (mag < 0.5f) return true

        val angle = Math.toDegrees(atan2((-y).toDouble(), x.toDouble()))
        val normAngle = (angle + 360) % 360
        val direction = (((normAngle + 22.5) % 360) / 45).toInt()

        val lastDir = if (isLeft) lastLJoyDirection else lastRJoyDirection
        if (lastDir == -1) {
            val digit = when (direction) {
                0 -> "6"
                1 -> "3"
                2 -> "2"
                3 -> "1"
                4 -> "4"
                5 -> "7"
                6 -> "8"
                7 -> "9"
                else -> null
            }
            if (digit != null) {
                currentSequence += digit
                updatePredictions()
            }
            if (isLeft) lastLJoyDirection = direction else lastRJoyDirection = direction
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isInputViewShown) {
            return super.onKeyDown(keyCode, event)
        }

        val isRepeat = event.repeatCount > 0

        if (keyCode == KeyEvent.KEYCODE_BUTTON_C || keyCode == 188) {
            requestHideSelf(0)
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_BUTTON_L2) {
            updateL2State(true, isAnalog = false)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            updateR2State(true, isAnalog = false)
            return true
        }

        if (currentMode == InputMode.JOY_JOY && keyCode == KeyEvent.KEYCODE_BUTTON_L1) {
            if (!isRepeat) {
                handleBackspace()
                startBackspaceRepeat()
            }
            return true
        }

        var handled = true
        when (currentMode) {
            InputMode.LJOY_RBUTTONS -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_BUTTON_Y -> if (!isRepeat) handleGridInput(0)
                    KeyEvent.KEYCODE_BUTTON_X -> if (!isRepeat) handleGridInput(1)
                    KeyEvent.KEYCODE_BUTTON_A -> if (!isRepeat) handleGridInput(2)
                    KeyEvent.KEYCODE_BUTTON_L1 -> if (!isRepeat) cyclePrediction(-1)
                    KeyEvent.KEYCODE_BUTTON_R1 -> if (!isRepeat) cyclePrediction(1)
                    KeyEvent.KEYCODE_BUTTON_B -> if (!isRepeat) commitCurrentWord(" ")
                    KeyEvent.KEYCODE_DPAD_LEFT -> handleCursorMove(KeyEvent.KEYCODE_DPAD_LEFT)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> handleCursorMove(KeyEvent.KEYCODE_DPAD_RIGHT)
                    KeyEvent.KEYCODE_DPAD_UP -> handleCursorMove(KeyEvent.KEYCODE_DPAD_UP)
                    KeyEvent.KEYCODE_DPAD_DOWN -> handleCursorMove(KeyEvent.KEYCODE_DPAD_DOWN)
                    else -> handled = false
                }
            }
            InputMode.JOY_JOY -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_BUTTON_B -> if (!isRepeat) {
                        currentSequence += "5"
                        updatePredictions()
                    }
                    KeyEvent.KEYCODE_BUTTON_R1 -> if (!isRepeat) commitCurrentWord(" ")
                    KeyEvent.KEYCODE_DPAD_LEFT -> handleCursorMove(KeyEvent.KEYCODE_DPAD_LEFT)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> handleCursorMove(KeyEvent.KEYCODE_DPAD_RIGHT)
                    else -> handled = false
                }
            }
            InputMode.SWIPE -> {
                when (keyCode) {
                    // CHANGED TO L1: Press down to lock the anchor and start recording
                    KeyEvent.KEYCODE_BUTTON_L1 -> {
                        if (!isRepeat && !isSwiping) {
                            isSwiping = true
                            currentSwipePath.clear()

                            // Capture the joystick's physical tilt at the exact moment L1 is pressed
                            anchorJoyX = currentJoyX
                            anchorJoyY = currentJoyY

                            // Start path at center (0,0) for the relative vector drawing
                            currentSwipePath.add(PointF(0f, 0f))
                            tvPredictions.text = "Recording Shape..."
                        }
                    }

                    KeyEvent.KEYCODE_BUTTON_X -> if (!isRepeat) cyclePrediction(-1)
                    KeyEvent.KEYCODE_BUTTON_A -> if (!isRepeat) cyclePrediction(1)
                    KeyEvent.KEYCODE_BUTTON_B -> if (!isRepeat) commitCurrentWord(" ")
                    KeyEvent.KEYCODE_DPAD_LEFT -> handleCursorMove(KeyEvent.KEYCODE_DPAD_LEFT)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> handleCursorMove(KeyEvent.KEYCODE_DPAD_RIGHT)
                    else -> handled = false
                }
            }
        }

        if (handled) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_L2) {
            updateL2State(false, isAnalog = false)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            updateR2State(false, isAnalog = false)
            return true
        }
        if (currentMode == InputMode.JOY_JOY && keyCode == KeyEvent.KEYCODE_BUTTON_L1) {
            stopBackspaceRepeat()
            return true
        }

        // CHANGED TO L1
        if (currentMode == InputMode.SWIPE && keyCode == KeyEvent.KEYCODE_BUTTON_L1) {
            if (isSwiping) {
                isSwiping = false
                processSwipe()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun updateL2State(down: Boolean, isAnalog: Boolean) {
        val wasDown = analogL2Down || digitalL2Down
        if (isAnalog) analogL2Down = down else digitalL2Down = down
        val isDown = analogL2Down || digitalL2Down

        if (isDown != wasDown) {
            if (currentMode != InputMode.JOY_JOY) {
                if (isDown) {
                    handleBackspace()
                    startBackspaceRepeat()
                } else {
                    stopBackspaceRepeat()
                }
            }
        }
    }

    private fun updateR2State(down: Boolean, isAnalog: Boolean) {
        val wasDown = analogR2Down || digitalR2Down
        if (isAnalog) analogR2Down = down else digitalR2Down = down
        val isDown = analogR2Down || digitalR2Down

        if (isDown != wasDown && isDown) {
            handleEnter()
        }
    }

    private fun startBackspaceRepeat() {
        stopBackspaceRepeat()
        backspaceRepeatRunnable = object : Runnable {
            override fun run() {
                handleBackspace()
                mainHandler.postDelayed(this, 100)
            }
        }
        mainHandler.postDelayed(backspaceRepeatRunnable!!, 1000)
    }

    private fun stopBackspaceRepeat() {
        backspaceRepeatRunnable?.let { mainHandler.removeCallbacks(it) }
        backspaceRepeatRunnable = null
    }

    private fun handleGridInput(column: Int) {
        val digit = (currentJoystickRow * 3) + column + 1
        currentSequence += digit.toString()
        updatePredictions()
    }

    private fun cyclePrediction(direction: Int) {
        if (currentPredictions.isEmpty()) return
        predictionIndex = (predictionIndex + direction + currentPredictions.size) % currentPredictions.size
        updateUI()
    }

    private fun commitCurrentWord(suffix: String = "") {
        val ic = currentInputConnection ?: return

        if (currentPredictions.isNotEmpty()) {
            val wordToCommit = currentPredictions[predictionIndex]
            ic.commitText("$wordToCommit$suffix", 1)
        } else if (currentSequence.isNotEmpty()) {
            ic.commitText("$currentSequence$suffix", 1)
        } else if (suffix.isNotEmpty()) {
            ic.commitText(suffix, 1)
        }

        currentSequence = ""
        updatePredictions()
    }

    private fun handleEnter() {
        if (currentSequence.isNotEmpty()) {
            commitCurrentWord("")
        }

        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    private fun handleBackspace() {
        if (currentSequence.isNotEmpty()) {
            currentSequence = currentSequence.dropLast(1)
            updatePredictions()
        } else {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
    }

    private fun handleCursorMove(dpadKeyCode: Int) {
        if (currentSequence.isNotEmpty()) {
            commitCurrentWord("")
        }

        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, dpadKeyCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, dpadKeyCode))
    }

    private fun updatePredictions() {
        if (currentSequence.isEmpty()) {
            currentPredictions = emptyList()
            predictionIndex = 0
            if (currentMode != InputMode.SWIPE) {
                tvPredictions.text = "Odin T9 Ready"
            }
            return
        }

        currentPredictions = t9Engine.getPredictions(currentSequence)
        predictionIndex = 0
        updateUI()
    }

    private fun processSwipe() {
        val predictions = swipeEngine.decodeSwipe(currentSwipePath, anchorJoyX, anchorJoyY)

        if (predictions.isNotEmpty()) {
            val bestMatch = predictions.first()
            currentInputConnection?.commitText("$bestMatch ", 1)

            val alternatives = predictions.take(3).joinToString("   |   ")
            tvPredictions.text = alternatives
        } else {
            tvPredictions.text = "No swipe match"
        }
        swipeDebugView.clear()
    }

    private fun updateUI() {
        if (currentPredictions.isEmpty()) {
            if (currentMode == InputMode.SWIPE && currentSwipePath.isEmpty()) {
                 tvPredictions.text = "Hold L1 to Swipe"
            } else if (currentSequence.isNotEmpty()) {
                tvPredictions.text = "No match: $currentSequence"
            }
            return
        }

        val display = currentPredictions.mapIndexed { index, word ->
            if (index == predictionIndex) "[$word]" else word
        }.joinToString("   ")

        tvPredictions.text = display
    }
}
