package com.vazbloke.t9controller

import android.content.SharedPreferences
import android.graphics.PointF
import android.inputmethodservice.InputMethodService
import android.text.Html
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.ExtractedTextRequest
import android.widget.TextView
import androidx.preference.PreferenceManager
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

class OdinT9Service : InputMethodService() {

    private lateinit var tvPredictions: TextView
    private lateinit var swipeDebugView: SwipeDebugView
    private val t9Engine = T9Engine()
    private val swipeEngine = SwipeEngine()
    private lateinit var prefs: SharedPreferences

    // --- JoyJoy State ---
    private var isTrackingWord = false
    private val joySwipePath = mutableListOf<PointF>()
    private var joyAngleSum = 0f
    private var joyLastAngle = 0f
    private val joyProbabilities = mutableListOf<Map<Char, Float>>()

    private var currentPredictions = listOf<String>()
    private var predictionIndex = 0

    // --- Configurations ---
    private var completionMode = "press_after" // Options: "hold_to_swipe", "press_after"
    private val keyBindings = mutableMapOf<Int, Action>()

    // --- State History (For Undo) ---
    private val undoStack = java.util.Stack<CharSequence>()

    enum class Action {
        CYCLE_FWD, CYCLE_BACK, ACCEPT, CYCLE_PREV,
        BACKSPACE_WORD, BACKSPACE_CHAR, ADD_SPACE, CLEAR_TEXT, UNDO, NONE
    }

    private val t9Centers = mapOf(
        '1' to PointF(-1f, -1f), '2' to PointF(0f, -1f), '3' to PointF(1f, -1f),
        '4' to PointF(-1f, 0f),  '5' to PointF(0f, 0f),  '6' to PointF(1f, 0f),
        '7' to PointF(-1f, 1f),  '8' to PointF(0f, 1f),  '9' to PointF(1f, 1f)
    )

    override fun onCreate() {
        super.onCreate()
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        t9Engine.loadDictionary(this)
        swipeEngine.dictionary = t9Engine.getAllWords()
        loadSettings()
    }

    private fun loadSettings() {
        // In your SettingsActivity, save this as "hold_to_swipe" or "press_after"
        completionMode = prefs.getString("word_completion_mode", "press_after") ?: "press_after"

        // Map your desired default Gamepad KeyCodes to Actions here.
        // These can be updated via SharedPreferences in your settings.
        keyBindings.clear()
        keyBindings[prefs.getInt("key_cycle_fwd", KeyEvent.KEYCODE_BUTTON_R1)] = Action.CYCLE_FWD
        keyBindings[prefs.getInt("key_cycle_back", KeyEvent.KEYCODE_BUTTON_L1)] = Action.CYCLE_BACK // Assuming L1 is NOT the trigger
        keyBindings[prefs.getInt("key_accept", KeyEvent.KEYCODE_BUTTON_R2)] = Action.ACCEPT
        keyBindings[prefs.getInt("key_cycle_prev", KeyEvent.KEYCODE_BUTTON_X)] = Action.CYCLE_PREV
        keyBindings[prefs.getInt("key_backspace_word", KeyEvent.KEYCODE_BUTTON_Y)] = Action.BACKSPACE_WORD
        keyBindings[prefs.getInt("key_backspace_char", KeyEvent.KEYCODE_BUTTON_B)] = Action.BACKSPACE_CHAR
        keyBindings[prefs.getInt("key_add_space", KeyEvent.KEYCODE_BUTTON_A)] = Action.ADD_SPACE
        keyBindings[prefs.getInt("key_clear_text", KeyEvent.KEYCODE_BUTTON_SELECT)] = Action.CLEAR_TEXT
        keyBindings[prefs.getInt("key_undo", KeyEvent.KEYCODE_BUTTON_START)] = Action.UNDO
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        tvPredictions = view.findViewById(R.id.tv_predictions)
        swipeDebugView = view.findViewById(R.id.swipe_debug_view)
        swipeDebugView.visibility = View.VISIBLE
        tvPredictions.text = "JoyJoy Ready"
        return view
    }

    // --- JOYSTICK INPUT HANDLING ---

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!isInputViewShown) return super.onGenericMotionEvent(event)

        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) {

            val x = event.getAxisValue(MotionEvent.AXIS_X)
            val y = event.getAxisValue(MotionEvent.AXIS_Y)
            val mag = sqrt((x * x + y * y).toDouble()).toFloat()

            // Mode 2: "Press After". We auto-start tracking if the stick leaves center.
            if (completionMode == "press_after" && mag > 0.2f && !isTrackingWord) {
                startTracking()
            }

            if (isTrackingWord) {
                handleJoyJoyMovement(x, y, mag)
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun handleJoyJoyMovement(rawX: Float, rawY: Float, mag: Float) {
        val mapped = mapCircleToSquare(rawX, rawY)

        // Ignore movements near the physical deadzone so thumb can rest/cross center safely
        if (mag < 0.2f) {
            joyAngleSum = 0f
            return
        }

        // '5' Key Circle Detection (Thumb swiping in a tight circle near center)
        if (mag < 0.6f) {
            val currentAngle = atan2(rawY.toDouble(), rawX.toDouble()).toFloat()
            var delta = currentAngle - joyLastAngle

            while (delta > Math.PI) delta -= (2 * Math.PI).toFloat()
            while (delta < -Math.PI) delta += (2 * Math.PI).toFloat()

            joyAngleSum += delta
            joyLastAngle = currentAngle

            if (abs(joyAngleSum) > Math.PI) {
                joyAngleSum = 0f
                val fiveMap = t9Centers.keys.associateWith { if (it == '5') 0.95f else 0.005f }
                joyProbabilities.add(fiveMap)
                joySwipePath.add(PointF(0f, 0f))
                updateLivePredictions()
            }
        } else {
            val last = joySwipePath.lastOrNull()
            if (last == null || getDistance(last, mapped.x, mapped.y) > 0.05f) {
                joySwipePath.add(PointF(mapped.x, mapped.y))
                updateLivePredictions()
            }
        }
    }

    private fun updateLivePredictions() {
        if (joySwipePath.isEmpty() && joyProbabilities.isEmpty()) return

        val inflections = swipeEngine.extractInflectionPoints(joySwipePath)
        val tempProbabilities = joyProbabilities.toMutableList()

        for (point in inflections) {
            if (abs(point.x) < 0.1f && abs(point.y) < 0.1f) continue
            tempProbabilities.add(generateProbabilityMap(point))
        }

        swipeDebugView.updateJoyT9Debug(joySwipePath, inflections, tempProbabilities)

        currentPredictions = t9Engine.getProbabilisticPredictions(tempProbabilities)
        predictionIndex = 0
        updateUI()
    }

    private fun startTracking() {
        isTrackingWord = true
        joySwipePath.clear()
        joyProbabilities.clear()
        joyAngleSum = 0f
        currentPredictions = emptyList()
        predictionIndex = 0
        swipeDebugView.clear()
        tvPredictions.text = "Tracking..."
    }

    // --- BUTTON CONTROLS & ROUTING ---

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isInputViewShown) return super.onKeyDown(keyCode, event)
        if (event.repeatCount > 0) return true

        // 1. Check for Word Boundary Trigger (Hardcoded to L1 for this example)
        if (keyCode == KeyEvent.KEYCODE_BUTTON_L1) {
            if (completionMode == "hold_to_swipe") {
                startTracking()
            } else if (completionMode == "press_after") {
                executeAction(Action.ACCEPT)
                isTrackingWord = false
            }
            return true
        }

        // 2. Check Custom Key Bindings
        val action = keyBindings[keyCode]
        if (action != null) {
            executeAction(action)
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // If mode is "Hold to Swipe", releasing L1 finalizes the word
        if (keyCode == KeyEvent.KEYCODE_BUTTON_L1 && completionMode == "hold_to_swipe") {
            isTrackingWord = false
            executeAction(Action.ACCEPT)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun executeAction(action: Action) {
        val ic = currentInputConnection ?: return

        when (action) {
            Action.CYCLE_FWD -> {
                if (currentPredictions.isNotEmpty()) {
                    predictionIndex = (predictionIndex + 1) % currentPredictions.size
                    updateUI()
                }
            }
            Action.CYCLE_BACK -> {
                if (currentPredictions.isNotEmpty()) {
                    predictionIndex = (predictionIndex - 1 + currentPredictions.size) % currentPredictions.size
                    updateUI()
                }
            }
            Action.ACCEPT -> {
                saveUndoSnapshot()
                if (currentPredictions.isNotEmpty()) {
                    ic.commitText("${currentPredictions[predictionIndex]}", 1)
                }
                resetState()
            }
            Action.CYCLE_PREV -> {
                // Reads text before cursor, finds last word, cycles its prediction, and replaces it
                saveUndoSnapshot()
                val textBefore = ic.getTextBeforeCursor(50, 0)?.toString() ?: return
                val lastWordMatch = Regex("([a-zA-Z]+)\\s*$").find(textBefore)
                if (lastWordMatch != null) {
                    val lastWord = lastWordMatch.groupValues[1]
                    val seq = t9Engine.wordToSequence(lastWord) // Note: Requires making wordToSequence public in T9Engine
                    val preds = t9Engine.getPredictions(seq)
                    if (preds.isNotEmpty()) {
                        val currentIdx = preds.indexOf(lastWord)
                        val nextWord = if (currentIdx == -1) preds[0] else preds[(currentIdx + 1) % preds.size]
                        ic.deleteSurroundingText(lastWord.length, 0)
                        ic.commitText(nextWord, 1)
                    }
                }
            }
            Action.BACKSPACE_WORD -> {
                saveUndoSnapshot()
                val textBefore = ic.getTextBeforeCursor(50, 0)?.toString() ?: return
                val spacesMatch = Regex("\\s+$").find(textBefore)
                val spacesLen = spacesMatch?.value?.length ?: 0
                val wordMatch = Regex("[^\\s]+\\s*$").find(textBefore)
                val deleteLen = wordMatch?.value?.length ?: spacesLen
                if (deleteLen > 0) ic.deleteSurroundingText(deleteLen, 0)
            }
            Action.BACKSPACE_CHAR -> {
                ic.deleteSurroundingText(1, 0)
            }
            Action.ADD_SPACE -> {
                saveUndoSnapshot()
                ic.commitText(" ", 1)
            }
            Action.CLEAR_TEXT -> {
                saveUndoSnapshot()
                ic.performContextMenuAction(android.R.id.selectAll)
                ic.commitText("", 1)
            }
            Action.UNDO -> {
                if (undoStack.isNotEmpty()) {
                    val previousState = undoStack.pop()
                    ic.performContextMenuAction(android.R.id.selectAll)
                    ic.commitText(previousState, 1)
                }
            }
            Action.NONE -> {}
        }
    }

    private fun saveUndoSnapshot() {
        val ic = currentInputConnection ?: return
        val currentText = ic.getExtractedText(ExtractedTextRequest(), 0)?.text
        if (currentText != null) {
            undoStack.push(currentText)
            // Prevent memory leak on long sessions
            if (undoStack.size > 20) undoStack.removeAt(0)
        }
    }

    private fun resetState() {
        joySwipePath.clear()
        joyProbabilities.clear()
        currentPredictions = emptyList()
        predictionIndex = 0
        swipeDebugView.clear()
        tvPredictions.text = "JoyJoy Ready"
    }

    private fun updateUI() {
        if (currentPredictions.isEmpty()) {
            tvPredictions.text = "Tracking..."
            return
        }

        val display = currentPredictions.mapIndexed { index, word ->
            if (index == predictionIndex) "<b><font color='#A3FF00'>[$word]</font></b>" else word
        }.joinToString("   ")

        tvPredictions.text = Html.fromHtml(display, Html.FROM_HTML_MODE_LEGACY)
    }

    // --- UTILITIES ---

    private fun generateProbabilityMap(pt: PointF): Map<Char, Float> {
        val sigma = 0.55f
        val probs = mutableMapOf<Char, Float>()
        var sum = 0f

        for ((digit, center) in t9Centers) {
            val dist = getDistance(center, pt.x, pt.y)
            val p = Math.exp(-(dist * dist) / (2 * sigma * sigma).toDouble()).toFloat()
            probs[digit] = p
            sum += p
        }
        return probs.mapValues { it.value / sum }
    }

    private fun mapCircleToSquare(u: Float, v: Float): PointF {
        if (u == 0f && v == 0f) return PointF(0f, 0f)
        val radius = sqrt((u * u + v * v).toDouble()).toFloat()
        val normalizedRadius = radius.coerceAtMost(1f)
        val theta = atan2(v.toDouble(), u.toDouble()).toFloat()
        val cosTheta = abs(Math.cos(theta.toDouble())).toFloat()
        val sinTheta = abs(Math.sin(theta.toDouble())).toFloat()
        val scale = 1f / max(cosTheta, sinTheta)
        val mappedRadius = normalizedRadius * scale
        val x = mappedRadius * Math.cos(theta.toDouble()).toFloat()
        val y = mappedRadius * Math.sin(theta.toDouble()).toFloat()
        return PointF(x.coerceIn(-1f, 1f), y.coerceIn(-1f, 1f))
    }

    private fun getDistance(p1: PointF, x2: Float, y2: Float): Float {
        return sqrt(Math.pow((x2 - p1.x).toDouble(), 2.0) + Math.pow((y2 - p1.y).toDouble(), 2.0)).toFloat()
    }
}