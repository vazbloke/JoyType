package com.vazbloke.t9controller

import android.inputmethodservice.InputMethodService
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

class OdinT9Service : InputMethodService() {

    private lateinit var tvPredictions: TextView
    private val t9Engine = T9Engine()

    // State management
    private var currentSequence = ""
    private var currentPredictions = listOf<String>()
    private var predictionIndex = 0

    // 0 = Up (Row 1), 1 = Center (Row 2), 2 = Down (Row 3)
    private var currentJoystickRow = 1

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        tvPredictions = view.findViewById(R.id.tv_predictions)
        return view
    }

    // Intercept Left Joystick movements
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) {
            val yAxis = event.getAxisValue(MotionEvent.AXIS_Y)
            currentJoystickRow = when {
                yAxis < -0.5f -> 0 // Joystick Up
                yAxis > 0.5f -> 2  // Joystick Down
                else -> 1          // Joystick Center
            }
            return true // Consume event
        }
        return super.onGenericMotionEvent(event)
    }

    // Intercept Controller Buttons
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        var handled = true
        when (keyCode) {
            // Columns: Y = 0 (Left), X = 1 (Center), A = 2 (Right)
            KeyEvent.KEYCODE_BUTTON_Y -> handleGridInput(0)
            KeyEvent.KEYCODE_BUTTON_X -> handleGridInput(1)
            KeyEvent.KEYCODE_BUTTON_A -> handleGridInput(2)

            // Cycle Predictions
            KeyEvent.KEYCODE_BUTTON_L1 -> cyclePrediction(-1)
            KeyEvent.KEYCODE_BUTTON_R1 -> cyclePrediction(1)

            // B Button = Space / Commit Word
            KeyEvent.KEYCODE_BUTTON_B -> commitCurrentWord()

            // Added Select for Backspace just in case
            KeyEvent.KEYCODE_BUTTON_SELECT -> handleBackspace()

            else -> handled = false
        }

        if (handled) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun handleGridInput(column: Int) {
        // Calculate T9 Number (1-9) based on 3x3 grid
        // Row 0: 1, 2, 3 | Row 1: 4, 5, 6 | Row 2: 7, 8, 9
        val digit = (currentJoystickRow * 3) + column + 1
        currentSequence += digit.toString()
        updatePredictions()
    }

    private fun cyclePrediction(direction: Int) {
        if (currentPredictions.isEmpty()) return
        predictionIndex = (predictionIndex + direction + currentPredictions.size) % currentPredictions.size
        updateUI()
    }

    private fun commitCurrentWord() {
        val ic = currentInputConnection ?: return

        if (currentPredictions.isNotEmpty()) {
            val wordToCommit = currentPredictions[predictionIndex]
            ic.commitText("$wordToCommit ", 1)
        } else if (currentSequence.isNotEmpty()) {
            // Fallback to raw numbers if no word matches
            ic.commitText("$currentSequence ", 1)
        } else {
            // Standard space if nothing is typed
            ic.commitText(" ", 1)
        }

        // Reset state
        currentSequence = ""
        updatePredictions()
    }

    private fun handleBackspace() {
        if (currentSequence.isNotEmpty()) {
            currentSequence = currentSequence.dropLast(1)
            updatePredictions()
        } else {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
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

        // Highlight the currently selected word among predictions
        val display = currentPredictions.mapIndexed { index, word ->
            if (index == predictionIndex) "[$word]" else word
        }.joinToString("   ")

        tvPredictions.text = display
    }
}

// --- T9 Prediction Engine ---
class T9Engine {
    // In a production app, you would load a massive dictionary (e.g., from a text file)
    // and map standard English words to their T9 numerical representations using a Trie data structure.
    // For demonstration, here is a hardcoded map mapping sequences to lists of words.

    // Mapping:
    // 2=abc, 3=def, 4=ghi, 5=jkl, 6=mno, 7=pqrs, 8=tuv, 9=wxyz
    private val dictionary = mapOf(
        "843" to listOf("the", "tie", "vid"),
        "263" to listOf("and", "cod", "ane"),
        "43556" to listOf("hello"),
        "6346" to listOf("odin", "neon", "meno"),
        "4263" to listOf("game", "hand")
    )

    fun getPredictions(sequence: String): List<String> {
        return dictionary[sequence] ?: listOf()
    }
}