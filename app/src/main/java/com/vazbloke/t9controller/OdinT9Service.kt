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

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        tvPredictions = view.findViewById(R.id.tv_predictions)
        tvMode = view.findViewById(R.id.tv_mode)

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
    }

    private fun updateModeUI() {
        tvMode.text = when (currentMode) {
            InputMode.LJOY_RBUTTONS -> "Mode: LJoy RButtons"
            InputMode.JOY_JOY -> "Mode: Joy Joy"
            InputMode.SWIPE -> "Mode: Swipe"
        }
        if (currentMode == InputMode.SWIPE) {
            tvPredictions.text = "Hold R1 to Swipe"
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!isInputViewShown) {
            return super.onGenericMotionEvent(event)
        }

        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) {

            // Handle L2/R2 analog triggers
            val lTrigger = Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
            val rTrigger = Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))

            updateL2State(lTrigger > 0.5f, true)
            updateR2State(rTrigger > 0.5f, true)

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

                    handleJoystickDirection(x, y, true)
                    handleJoystickDirection(z, rz, false)
                    return true
                }
                InputMode.SWIPE -> {
                    // 1. ROBUST AXIS READING: Check standard X/Y, fallback to Z/RZ if they are dead
                    var xAxis = event.getAxisValue(MotionEvent.AXIS_X)
                    var yAxis = event.getAxisValue(MotionEvent.AXIS_Y)

                    if (Math.abs(xAxis) < 0.01f && Math.abs(yAxis) < 0.01f) {
                        xAxis = event.getAxisValue(MotionEvent.AXIS_Z)
                        yAxis = event.getAxisValue(MotionEvent.AXIS_RZ)
                    }

                    // Apply deadzone to prevent cursor drift
                    if (Math.abs(xAxis) > 0.1f || Math.abs(yAxis) > 0.1f) {

                        // 2. SLOW DOWN CURSOR: At 120Hz, a speed of 0.5 shoots the cursor out of bounds instantly.
                        cursorX += xAxis * 0.15f
                        cursorY += yAxis * 0.15f

                        // Clamp to virtual keyboard bounds (0 to 9 on X, 0 to 2 on Y)
                        cursorX = cursorX.coerceIn(0f, 9f)
                        cursorY = cursorY.coerceIn(0f, 2f)

                        if (isSwiping) {
                            // 3. DISTANCE THRESHOLD: Only record points if we've moved a reasonable amount.
                            // This eliminates micro-jitter and allows the angle math to work properly.
                            val lastPoint = currentSwipePath.lastOrNull()
                            if (lastPoint == null || getDistance(lastPoint, cursorX, cursorY) > 0.4f) {
                                currentSwipePath.add(PointF(cursorX, cursorY))

                                // Debug UI: Shows you in real-time that points are being recorded
                                mainHandler.post {
                                    tvPredictions.text = "Swiping... [${currentSwipePath.size} pts]"
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

    // Add this helper function anywhere in your OdinT9Service class
    private fun getDistance(p1: PointF, x2: Float, y2: Float): Float {
        return Math.sqrt(Math.pow((x2 - p1.x).toDouble(), 2.0) + Math.pow((y2 - p1.y).toDouble(), 2.0)).toFloat()
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
                    KeyEvent.KEYCODE_BUTTON_R1 -> {
                        if (!isRepeat && !isSwiping) {
                            isSwiping = true
                            currentSwipePath.clear()
                            currentSwipePath.add(PointF(cursorX, cursorY))
                            tvPredictions.text = "Swiping..."
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
        if (currentMode == InputMode.SWIPE && keyCode == KeyEvent.KEYCODE_BUTTON_R1) {
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
        val predictions = swipeEngine.decodeSwipe(currentSwipePath)

        if (predictions.isNotEmpty()) {
            currentPredictions = predictions
            predictionIndex = 0
            updateUI()
        } else {
            tvPredictions.text = "No swipe match"
        }
    }

    private fun updateUI() {
        if (currentPredictions.isEmpty()) {
            if (currentMode == InputMode.SWIPE && currentSwipePath.isEmpty()) {
                 tvPredictions.text = "Hold R1 to Swipe"
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
