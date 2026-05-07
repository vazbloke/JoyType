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

    // Most controllers map the extra C/Z buttons to these:
    private val MODIFIER_KEY_1 = KeyEvent.KEYCODE_BUTTON_C
    private val MODIFIER_KEY_2 = KeyEvent.KEYCODE_BUTTON_Z

    // --- Hybrid State ---
    private var isTriggerHeld = false
    private val currentStrokePath = mutableListOf<PointF>()
    private val wordProbabilities = mutableListOf<Map<Char, Float>>()

    // --- RADIAL UI STATE ---
    private var isRadialMenuOpen = false
    private var radialSelectedIndex = 0
    private val RADIAL_KEY = KeyEvent.KEYCODE_BUTTON_C // Change this to your M1 keycode

    private var joyAngleSum = 0f
    private var joyLastAngle = -999f
    private var circleDetectedThisStroke = false

    private var currentPredictions = listOf<String>()
    private var predictionIndex = 0

    private var autoSpace = true
    private var triggerKey = KeyEvent.KEYCODE_BUTTON_L1
    private val keyBindings = mutableMapOf<Int, Action>()
    private val undoStack = java.util.Stack<CharSequence>()


    enum class Action {
        CYCLE_FWD, CYCLE_BACK, ACCEPT, CYCLE_PREV,
        BACKSPACE_WORD, BACKSPACE_CHAR, ADD_SPACE, CLEAR_TEXT, UNDO, OPEN_SETTINGS, NONE
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

    override fun onWindowShown() {
        super.onWindowShown()
        loadSettings()
    }

    private fun loadSettings() {
        autoSpace = prefs.getBoolean("autospace_after_accept", true)
        triggerKey = prefs.getInt("key_trigger", KeyEvent.KEYCODE_BUTTON_L1)

        keyBindings.clear()
        keyBindings[prefs.getInt("key_cycle_fwd", KeyEvent.KEYCODE_BUTTON_R1)] = Action.CYCLE_FWD
        keyBindings[prefs.getInt("key_cycle_back", KeyEvent.KEYCODE_BUTTON_L2)] = Action.CYCLE_BACK
        keyBindings[prefs.getInt("key_accept", KeyEvent.KEYCODE_BUTTON_R2)] = Action.ACCEPT
        keyBindings[prefs.getInt("key_cycle_prev", KeyEvent.KEYCODE_BUTTON_X)] = Action.CYCLE_PREV
        keyBindings[prefs.getInt("key_backspace_word", KeyEvent.KEYCODE_BUTTON_Y)] = Action.BACKSPACE_WORD
        keyBindings[prefs.getInt("key_backspace_char", KeyEvent.KEYCODE_BUTTON_B)] = Action.BACKSPACE_CHAR
        keyBindings[prefs.getInt("key_add_space", KeyEvent.KEYCODE_BUTTON_A)] = Action.ADD_SPACE
        keyBindings[prefs.getInt("key_clear_text", KeyEvent.KEYCODE_BUTTON_SELECT)] = Action.CLEAR_TEXT
        keyBindings[prefs.getInt("key_undo", KeyEvent.KEYCODE_BUTTON_THUMBL)] = Action.UNDO
        keyBindings[prefs.getInt("key_open_settings", KeyEvent.KEYCODE_BUTTON_START)] = Action.OPEN_SETTINGS
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        tvPredictions = view.findViewById(R.id.tv_predictions)
        swipeDebugView = view.findViewById(R.id.swipe_debug_view)
        swipeDebugView.visibility = View.VISIBLE
        tvPredictions.text = "JoyJoy Ready"
        return view
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!isInputViewShown) return super.onGenericMotionEvent(event)

        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) {

            val rawX = event.getAxisValue(MotionEvent.AXIS_X)
            val rawY = event.getAxisValue(MotionEvent.AXIS_Y)
            val magL = kotlin.math.sqrt((rawX * rawX + rawY * rawY).toDouble()).toFloat()

            val rawZ = event.getAxisValue(MotionEvent.AXIS_Z)
            val rawRZ = event.getAxisValue(MotionEvent.AXIS_RZ)
            val magR = kotlin.math.sqrt((rawZ * rawZ + rawRZ * rawRZ).toDouble()).toFloat()

            val useRightStick = magR > magL
            val x = if (useRightStick) rawZ else rawX
            val y = if (useRightStick) rawRZ else rawY
            val mag = if (useRightStick) magR else magL

            // --- RADIAL UI JOYSTICK INTERCEPT ---
            if (isRadialMenuOpen) {
                if (mag > 0.3f) { // Deadzone so a resting thumb doesn't jump the selection
                    // 1. Calculate angle, shift Top to 0, range [0, 2PI)
                    var angle = kotlin.math.atan2(y.toDouble(), x.toDouble())
                    angle += Math.PI / 2.0
                    if (angle < 0) angle += 2 * Math.PI

                    // 2. Divide into 8 octants (0 = Top, 1 = TopRight, 2 = Right...)
                    val octant = Math.round(angle / (Math.PI / 4.0)).toInt() % 8

                    // 3. Prevent crashing if there are less than 8 words available
                    if (currentPredictions.isNotEmpty()) {
                        radialSelectedIndex = octant.coerceAtMost(currentPredictions.size - 1)
                        updateUI()
                    }
                }
                return true // Stop standard T9 math from running!
            }

            // --- NORMAL T9 TYPING ---
            handleJoyJoyMovement(x, y, mag)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun handleJoyJoyMovement(rawX: Float, rawY: Float, mag: Float) {
        val mapped = mapCircleToSquare(rawX, rawY)

        // 1. HARDWARE SNAPBACK TO CENTER (MAGNITUDE 0.0)
        if (mag == 0.0f) {
            if (currentStrokePath.isNotEmpty()) {
                if (isTriggerHeld) {
                    // Continuous Swipe: Trace the center crossing visually
                    currentStrokePath.add(PointF(0f, 0f))
                } else {
                    // Discrete Mode: Finalize the single character flick
                    if (!circleDetectedThisStroke) {
                        val maxPt = currentStrokePath.maxByOrNull { sqrt(it.x * it.x + it.y * it.y) }
                        if (maxPt != null && sqrt(maxPt.x * maxPt.x + maxPt.y * maxPt.y) > 0.01f) {
                            wordProbabilities.add(generateProbabilityMap(maxPt)) // Reverted to Digits
                        }
                    }
                    currentStrokePath.clear()
                    circleDetectedThisStroke = false
                    updateLivePredictions()
                }
            }
            joyAngleSum = 0f
            joyLastAngle = -999f
            return
        }

        // 2. ACTIVE MOVEMENT
        currentStrokePath.add(PointF(mapped.x, mapped.y))

        // 3. '5' CIRCLE DETECTION
        val currentAngle = atan2(rawY.toDouble(), rawX.toDouble()).toFloat()
        if (joyLastAngle == -999f) joyLastAngle = currentAngle

        var delta = currentAngle - joyLastAngle
        while (delta > Math.PI) delta -= (2 * Math.PI).toFloat()
        while (delta < -Math.PI) delta += (2 * Math.PI).toFloat()

        // Accumulate smooth rotation. A straight line cross resets it.
        if (abs(delta) < (Math.PI / 2)) joyAngleSum += delta else joyAngleSum = 0f
        joyLastAngle = currentAngle

        if (abs(joyAngleSum) > Math.PI && !circleDetectedThisStroke) {
            circleDetectedThisStroke = true
            val fiveMap = t9Centers.keys.associateWith { if (it == '5') 0.95f else 0.005f }
            wordProbabilities.add(fiveMap)
            joyAngleSum = 0f
            updateLivePredictions()
        }

        // Only live-update while swiping to show the inflections, discrete updates on snapback
        if (isTriggerHeld) {
            updateLivePredictions()
        } else {
            // But still draw the raw line for discrete mode
            swipeDebugView.updateJoyT9Debug(currentStrokePath, emptyList(), wordProbabilities)
        }
    }

    private fun updateLivePredictions() {
        val tempProbabilities = wordProbabilities.toMutableList()

        var activeInflections = listOf<PointF>()
        // If swiping, dynamically extract and preview the corners being built
        if (isTriggerHeld && currentStrokePath.isNotEmpty()) {
            activeInflections = swipeEngine.extractInflectionPoints(currentStrokePath)
            for (point in activeInflections) {
                if (kotlin.math.abs(point.x) < 0.1f && kotlin.math.abs(point.y) < 0.1f) continue
                tempProbabilities.add(generateProbabilityMap(point))
            }
        }

        swipeDebugView.updateJoyT9Debug(currentStrokePath, activeInflections, tempProbabilities)

        if (tempProbabilities.isNotEmpty()) {
            currentPredictions = t9Engine.getProbabilisticPredictions(tempProbabilities)
            predictionIndex = 0
            updateUI()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isInputViewShown) return super.onKeyDown(keyCode, event)
        if (event.repeatCount > 0) return true

        // RADIAL UI: OPEN
        if (keyCode == RADIAL_KEY) {
            if (currentPredictions.isNotEmpty()) {
                isRadialMenuOpen = true
                radialSelectedIndex = 0 // Default to the first word
                updateUI()
            }
            return true
        }

        if (keyCode == triggerKey) {
            // ... rest of your code
            isTriggerHeld = true
            return true
        }

        val action = keyBindings[keyCode]
        if (action != null) {
            executeAction(action)
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {

        // RADIAL UI: COMMIT WORD AND CLOSE
        if (keyCode == RADIAL_KEY) {
            if (isRadialMenuOpen) {
                isRadialMenuOpen = false

                // User let go of M1, commit the highlighted word!
                if (currentPredictions.isNotEmpty() && radialSelectedIndex < currentPredictions.size) {
                    saveUndoSnapshot()
                    val space = if (autoSpace) " " else ""
                    currentInputConnection?.commitText("${currentPredictions[radialSelectedIndex]}$space", 1)
                    resetState()
                } else {
                    updateUI()
                }
            }
            return true
        }

        if (keyCode == triggerKey) {
            // ... rest of your code
            isTriggerHeld = false

            // Finalize the active swipe stroke upon releasing the trigger
            if (currentStrokePath.isNotEmpty()) {
                val inflections = swipeEngine.extractInflectionPoints(currentStrokePath)
                for (point in inflections) {
                    if (abs(point.x) < 0.1f && abs(point.y) < 0.1f) continue
                    wordProbabilities.add(generateProbabilityMap(point)) // Reverted to Digits
                }
                currentStrokePath.clear()
                updateLivePredictions()
            }
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
                    val space = if (autoSpace) " " else ""
                    ic.commitText("${currentPredictions[predictionIndex]}$space", 1)
                }
                resetState()
            }
            Action.CYCLE_PREV -> {
                saveUndoSnapshot()
                val textBefore = ic.getTextBeforeCursor(50, 0)?.toString() ?: return
                val lastWordMatch = Regex("([a-zA-Z]+)\\s*$").find(textBefore)
                if (lastWordMatch != null) {
                    val lastWord = lastWordMatch.groupValues[1]
                    val seq = t9Engine.wordToSequence(lastWord)
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
            Action.BACKSPACE_CHAR -> ic.deleteSurroundingText(1, 0)
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
            Action.OPEN_SETTINGS -> {
                val intent = android.content.Intent(this, SettingsActivity::class.java)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
            Action.NONE -> {}
        }
    }

    private fun saveUndoSnapshot() {
        val ic = currentInputConnection ?: return
        val currentText = ic.getExtractedText(ExtractedTextRequest(), 0)?.text
        if (currentText != null) {
            undoStack.push(currentText)
            if (undoStack.size > 20) undoStack.removeAt(0)
        }
    }

    private fun resetState() {
        isTriggerHeld = false
        currentStrokePath.clear()
        wordProbabilities.clear()
        // ... rest of resetState
        circleDetectedThisStroke = false
        currentPredictions = emptyList()
        predictionIndex = 0
        swipeDebugView.clear()
        tvPredictions.text = "JoyJoy Ready"
        isRadialMenuOpen = false
    }

    private fun updateUI() {
        if (currentPredictions.isEmpty()) {
            tvPredictions.text = "Tracking..."
            return
        }

        val display = if (isRadialMenuOpen) {
            // RADIAL UI MODE: Add arrows and dim unselected words
            val arrows = arrayOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")
            currentPredictions.mapIndexed { index, word ->
                val dir = if (index < arrows.size) arrows[index] else ""

                if (index == radialSelectedIndex) "<b><font color='#FFA500'>[$dir $word]</font></b>"
                else "<font color='#555555'>$dir $word</font>"
            }.joinToString("   ")

        } else {
            // STANDARD MODE
            currentPredictions.mapIndexed { index, word ->
                if (index == predictionIndex) "<b><font color='#A3FF00'>[$word]</font></b>" else word
            }.joinToString("   ")
        }

        tvPredictions.text = Html.fromHtml(display, Html.FROM_HTML_MODE_LEGACY)
    }

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