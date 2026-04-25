package com.vazbloke.t9controller

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

class OdinT9Service : InputMethodService() {

    private lateinit var tvPredictions: TextView
    private lateinit var tvMode: TextView
    private val t9Engine = T9Engine()

    enum class InputMode {
        LJOY_RBUTTONS, JOY_JOY
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
        currentMode = if (currentMode == InputMode.LJOY_RBUTTONS) {
            InputMode.JOY_JOY
        } else {
            InputMode.LJOY_RBUTTONS
        }
        currentSequence = ""
        updatePredictions()
        updateModeUI()
    }

    private fun updateModeUI() {
        tvMode.text = when (currentMode) {
            InputMode.LJOY_RBUTTONS -> "Mode: LJoy RButtons"
            InputMode.JOY_JOY -> "Mode: Joy Joy"
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

            if (currentMode == InputMode.LJOY_RBUTTONS) {
                val yAxis = event.getAxisValue(MotionEvent.AXIS_Y)
                currentJoystickRow = when {
                    yAxis < -0.5f -> 0 // Up
                    yAxis > 0.5f -> 2  // Down
                    else -> 1          // Center
                }
                return true
            } else if (currentMode == InputMode.JOY_JOY) {
                // Check both joysticks
                val x = event.getAxisValue(MotionEvent.AXIS_X)
                val y = event.getAxisValue(MotionEvent.AXIS_Y)
                val z = event.getAxisValue(MotionEvent.AXIS_Z)
                val rz = event.getAxisValue(MotionEvent.AXIS_RZ)

                handleJoystickDirection(x, y, true)
                handleJoystickDirection(z, rz, false)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    private fun handleJoystickDirection(x: Float, y: Float, isLeft: Boolean): Boolean {
        val mag = Math.sqrt((x * x + y * y).toDouble())
        if (mag < 0.25f) {
            if (isLeft) lastLJoyDirection = -1 else lastRJoyDirection = -1
            return false
        }

        if (mag < 0.5f) return true // Deadzone

        val angle = Math.toDegrees(Math.atan2((-y).toDouble(), x.toDouble())) // 0 is Right, 90 is Up
        // Normalize angle to 0-360
        val normAngle = (angle + 360) % 360
        
        // 8 directions: 0: R, 1: TR, 2: T, 3: TL, 4: L, 5: BL, 6: B, 7: BR
        val direction = (((normAngle + 22.5) % 360) / 45).toInt()
        
        val lastDir = if (isLeft) lastLJoyDirection else lastRJoyDirection
        if (lastDir == -1) {
            val digit = when (direction) {
                0 -> "6" // Right
                1 -> "3" // Top-Right
                2 -> "2" // Top
                3 -> "1" // Top-Left
                4 -> "4" // Left
                5 -> "7" // Bottom-Left
                6 -> "8" // Bottom
                7 -> "9" // Bottom-Right
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
        
        // Handle M1 to close keyboard (typically KEYCODE_BUTTON_C or 188 on Odin)
        if (keyCode == KeyEvent.KEYCODE_BUTTON_C || keyCode == 188) {
            requestHideSelf(0)
            return true
        }

        // Handle L2/R2 digital
        if (keyCode == KeyEvent.KEYCODE_BUTTON_L2) {
            updateL2State(true, false)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            updateR2State(true, false)
            return true
        }

        // Handle L1 Backspace with auto-repeat logic for JOY_JOY
        if (currentMode == InputMode.JOY_JOY && keyCode == KeyEvent.KEYCODE_BUTTON_L1) {
            if (!isRepeat) {
                handleBackspace()
                startBackspaceRepeat()
            }
            return true
        }

        var handled = true
        if (currentMode == InputMode.LJOY_RBUTTONS) {
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
        } else { // JOY_JOY mode
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

        if (handled) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_L2) {
            updateL2State(false, false)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            updateR2State(false, false)
            return true
        }
        if (currentMode == InputMode.JOY_JOY && keyCode == KeyEvent.KEYCODE_BUTTON_L1) {
            stopBackspaceRepeat()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun updateL2State(down: Boolean, isAnalog: Boolean) {
        val wasDown = analogL2Down || digitalL2Down
        if (isAnalog) analogL2Down = down else digitalL2Down = down
        val isDown = analogL2Down || digitalL2Down
        
        if (isDown != wasDown) {
            if (currentMode == InputMode.LJOY_RBUTTONS) {
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
                mainHandler.postDelayed(this, 100) // Repeat every 100ms
            }
        }
        mainHandler.postDelayed(backspaceRepeatRunnable!!, 1000) // Initial 1s delay
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

    // Updated to accept an optional suffix (space or empty string)
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
        // Commit any pending sequence first without adding a space
        if (currentSequence.isNotEmpty()) {
            commitCurrentWord("")
        }

        // Send a physical Enter/Return key event to the input field
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    private fun handleBackspace() {
        if (currentSequence.isNotEmpty()) {
            currentSequence = currentSequence.dropLast(1)
            updatePredictions()
        } else {
            // Delete one character before the cursor
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
    }

    private fun handleCursorMove(dpadKeyCode: Int) {
        // If the user tries to move the cursor while halfway through a T9 word,
        // commit the word first so the cursor movement behaves predictably.
        if (currentSequence.isNotEmpty()) {
            commitCurrentWord("")
        }

        // Pass the D-Pad event directly into the text field to move the cursor naturally
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, dpadKeyCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, dpadKeyCode))
    }

    private fun updatePredictions() {
        if (currentSequence.isEmpty()) {
            currentPredictions = emptyList()
            predictionIndex = 0
            tvPredictions.text = "Odin T9 Ready"
            return
        }

        currentPredictions = t9Engine.getPredictions(currentSequence)
        predictionIndex = 0
        updateUI()
    }

    private fun updateUI() {
        if (currentPredictions.isEmpty()) {
            tvPredictions.text = "No match: $currentSequence"
            return
        }

        val display = currentPredictions.mapIndexed { index, word ->
            if (index == predictionIndex) "[$word]" else word
        }.joinToString("   ")

        tvPredictions.text = display
    }
}
