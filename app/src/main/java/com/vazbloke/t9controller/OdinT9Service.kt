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
    private lateinit var prefs: SharedPreferences

    // Most controllers map the extra C/Z buttons to these:
    private val MODIFIER_KEY_1 = KeyEvent.KEYCODE_BUTTON_C
    private val MODIFIER_KEY_2 = KeyEvent.KEYCODE_BUTTON_Z

    // --- Core State ---
    private var isTriggerHeld = false
    private val currentStrokePath = mutableListOf<PointF>()
    private val wordProbabilities = mutableListOf<Map<Char, Float>>()
    // --- New Features State ---
    private var doubleAcceptPeriod = true
    private var lastAcceptTime = 0L
    
    // --- Radial UI State ---
    private var isRadialMenuOpen = false
    private var isPunctuationMode = false
    private var radialSelectedIndex = 0
    // private val PUNCTUATIONS = listOf(".", ",", "?", "!", "-", "'", "@", ":")
    private val RADIAL_KEY = KeyEvent.KEYCODE_BUTTON_C // Your M1 Button

    // NEW: Pagination State
    private var radialPage = 0 
    private var radialLastOctant = -1
    private val PUNCTUATIONS_P1 = listOf(".", ",", "?", "!", "-", "'", "@", ":")
    private val PUNCTUATIONS_P2 = listOf("\"", "(", ")", "/", "\\", "_", ";", "&") // Add whatever you want here!


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
//        swipeEngine.dictionary = t9Engine.getAllWords()
        loadSettings()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        loadSettings()
    }

    private fun loadSettings() {
        autoSpace = prefs.getBoolean("autospace_after_accept", true)
        doubleAcceptPeriod = prefs.getBoolean("double_accept_period", true)

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
                if (mag > 0.3f) { 
                    var angle = kotlin.math.atan2(y.toDouble(), x.toDouble()) 
                    angle += Math.PI / 2.0 
                    if (angle < 0) angle += 2 * Math.PI

                    val octant = Math.round(angle / (Math.PI / 4.0)).toInt() % 8
                    
                    // NEW: iPod Click-Wheel Pagination
                    if (isPunctuationMode && radialLastOctant != -1) {
                        // Clockwise crossover (Top-Left to Top) -> Next Page
                        if (radialLastOctant == 7 && octant == 0) {
                            radialPage = 1 
                        }
                        // Counter-Clockwise crossover (Top to Top-Left) -> Prev Page
                        else if (radialLastOctant == 0 && octant == 7) {
                            radialPage = 0
                        }
                    }
                    radialLastOctant = octant // Save for the next frame
                    
                    // Pick the correct list to read from
                    val currentItems = if (isPunctuationMode) {
                        if (radialPage == 0) PUNCTUATIONS_P1 else PUNCTUATIONS_P2
                    } else currentPredictions
                    
                    if (currentItems.isNotEmpty()) {
                        radialSelectedIndex = octant.coerceAtMost(currentItems.size - 1)
                        updateUI()
                    }
                }
                return true
            }

            // --- NORMAL T9 TYPING ---
            handleJoyJoyMovement(x, y, mag)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun handleJoyJoyMovement(rawX: Float, rawY: Float, mag: Float) {
        val mapped = mapCircleToSquare(rawX, rawY)

        if (mag == 0.0f) {
            // SNAPBACK: Find the sharpest point of the flick and save the letter
            if (currentStrokePath.isNotEmpty()) {
                val maxPt = currentStrokePath.maxByOrNull { kotlin.math.sqrt(it.x * it.x + it.y * it.y) }
                if (maxPt != null && kotlin.math.sqrt(maxPt.x * maxPt.x + maxPt.y * maxPt.y) > 0.01f) {
                    wordProbabilities.add(generateProbabilityMap(maxPt))
                }
                currentStrokePath.clear()
                updateLivePredictions()
            }
            return
        }

        // ACTIVE MOVEMENT: Just record the path to find the max point later
        currentStrokePath.add(PointF(mapped.x, mapped.y))
        swipeDebugView.updateJoyT9Debug(currentStrokePath, emptyList(), wordProbabilities)
    }

    private fun updateLivePredictions() {
        if (wordProbabilities.isNotEmpty()) {
            currentPredictions = t9Engine.getProbabilisticPredictions(wordProbabilities)
            predictionIndex = 0
            updateUI()
        } else {
            currentPredictions = emptyList()
            updateUI()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isInputViewShown) return super.onKeyDown(keyCode, event)
        if (event.repeatCount > 0) return true

        // RADIAL UI: OPEN (Words OR Punctuation)
        if (keyCode == RADIAL_KEY) {
            isRadialMenuOpen = true
            isPunctuationMode = currentPredictions.isEmpty() 
            radialSelectedIndex = 0 
            radialPage = 0 // NEW: Always start on Page 1
            radialLastOctant = -1 // NEW: Reset the boundary tracker
            updateUI()
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

        // RADIAL UI: COMMIT
        if (keyCode == RADIAL_KEY) {
            if (isRadialMenuOpen) {
                isRadialMenuOpen = false
                
                if (isPunctuationMode) {
                    // NEW: Pull from the correct page
                    val items = if (radialPage == 0) PUNCTUATIONS_P1 else PUNCTUATIONS_P2
                    saveUndoSnapshot()
                    currentInputConnection?.commitText(items[radialSelectedIndex], 1)
                } else if (currentPredictions.isNotEmpty() && radialSelectedIndex < currentPredictions.size) {
                    saveUndoSnapshot()
                    val space = if (autoSpace) " " else ""
                    currentInputConnection?.commitText("${currentPredictions[radialSelectedIndex]}$space", 1)
                    lastAcceptTime = System.currentTimeMillis()
                    resetState() 
                }
                updateUI()
            }
            return true
        }

        if (keyCode == triggerKey) {
            // ... rest of your code
            isTriggerHeld = false

            // Finalize the active swipe stroke upon releasing the trigger
            if (currentStrokePath.isNotEmpty()) {
//                val inflections = swipeEngine.extractInflectionPoints(currentStrokePath)
//                for (point in inflections) {
//                    if (abs(point.x) < 0.1f && abs(point.y) < 0.1f) continue
//                    wordProbabilities.add(generateProbabilityMap(point)) // Reverted to Digits
//                }
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
                val now = System.currentTimeMillis()
                
                if (currentPredictions.isNotEmpty()) {
                    // Normal Word Accept
                    val space = if (autoSpace) " " else ""
                    ic.commitText("${currentPredictions[predictionIndex]}$space", 1)
                    lastAcceptTime = now
                } else {
                    // Empty Accept - Check for Double Tap!
                    if (doubleAcceptPeriod && (now - lastAcceptTime < 500)) {
                        val textBefore = ic.getTextBeforeCursor(2, 0)?.toString()
                        if (textBefore?.endsWith(" ") == true) {
                            ic.deleteSurroundingText(1, 0) // Delete the space
                        }
                        ic.commitText(". ", 1)
                        lastAcceptTime = 0L // Reset
                    } else {
                        // Standard space addition
                        ic.commitText(" ", 1)
                        lastAcceptTime = now
                    }
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
        isPunctuationMode = false
        radialPage = 0
        radialLastOctant = -1
    }

    private fun updateUI() {
        if (currentPredictions.isEmpty() && !isRadialMenuOpen) {
            tvPredictions.text = "JoyJoy Ready"
            return
        }
        
        val display = if (isRadialMenuOpen) {
            val arrows = arrayOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")
            
            // NEW: Fetch the correct list for drawing
            val items = if (isPunctuationMode) {
                if (radialPage == 0) PUNCTUATIONS_P1 else PUNCTUATIONS_P2
            } else currentPredictions
            
            items.mapIndexed { index, word ->
                val dir = if (index < arrows.size) arrows[index] else ""
                if (index == radialSelectedIndex) "<b><font color='#FFA500'>[$dir $word]</font></b>" 
                else "<font color='#555555'>$dir $word</font>"
            }.joinToString("   ")
        } else {
            currentPredictions.mapIndexed { index, word ->
                if (index == predictionIndex) "<b><font color='#A3FF00'>[$word]</font></b>" else word
            }.joinToString("   ")
        }
        
        tvPredictions.text = android.text.Html.fromHtml(display, android.text.Html.FROM_HTML_MODE_LEGACY)
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