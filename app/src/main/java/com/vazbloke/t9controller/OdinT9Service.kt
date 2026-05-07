package com.vazbloke.t9controller

import android.content.Context
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

import android.os.Vibrator
import android.os.VibrationEffect
import android.os.Build
import android.view.animation.OvershootInterpolator // For the spring animation!

class OdinT9Service : InputMethodService() {

    private lateinit var swipeDebugView: SwipeDebugView
    private val t9Engine = T9Engine()
    private lateinit var prefs: SharedPreferences

    // --- Core State ---
    private var isTriggerHeld = false
    private val currentStrokePath = mutableListOf<PointF>()
    private val wordProbabilities = mutableListOf<Map<Char, Float>>()
    private var vibratedThisStroke = false // NEW: Tracks the flick attack!
    
    // --- Radial UI State ---
    private var isRadialMenuOpen = false
    private var isPunctuationMode = false
    private var radialSelectedIndex = 0
    // private val PUNCTUATIONS = listOf(".", ",", "?", "!", "-", "'", "@", ":")
    private val radialKey = KeyEvent.KEYCODE_BUTTON_C // Your M1 Button

    // NEW: Pagination State
    private var radialPage = 0 
    private var radialLastOctant = -1
    private val punctuationsP1 = listOf(".", ",", "?", "!", "-", "'", "@", ":")
    private val punctuationsP2 = listOf("\"", "(", ")", "/", "\\", "_", ";", "&") // Add whatever you want here!

    private var circleDetectedThisStroke = false

    private var currentPredictions = listOf<String>()
    private var predictionIndex = 0
    private val undoStack = java.util.Stack<CharSequence>()

    // --- New Features State ---
    private var autoSpace = true
    private var doubleAcceptPeriod = true
    private var autoCap = true         // NEW
    private var visualDebug = true     // NEW
    private var lastAcceptTime = 0L

    // Vibrate options
    private var vibrateOnType = true
    private var vibrateDuration = 15L
    private lateinit var vibrator: Vibrator

    // --- Cursor UI State ---
    private var lastCursorMoveTime = 0L

    private lateinit var tvPredictions: TextView
    private lateinit var hsvPredictions: android.widget.HorizontalScrollView // NEW

    enum class ModifierKey { NONE, M1, M2 }
    
    // Replace your old Action enum mapping with a KeyCombo map
    data class KeyCombo(val keyCode: Int, val modifier: ModifierKey)
    private val keyBindings = mutableMapOf<KeyCombo, Action>()

    enum class Action {
        ACCEPT, CYCLE_PREV, BACKSPACE_WORD, BACKSPACE_CHAR, BACKSPACE_STROKE, 
        ADD_SPACE, CLEAR_TEXT, UNDO, OPEN_SETTINGS, NONE, ENTER, 
        CLOSE_KEYBOARD // NEW
    }

    // Modifier State
    private var isM1Held = false
    private var isM2Held = false
    private var m1KeyCode = KeyEvent.KEYCODE_BUTTON_C
    private var m2KeyCode = KeyEvent.KEYCODE_BUTTON_Z
    
    private var radialModifier = ModifierKey.M1
    private var cursorModifier = ModifierKey.M2

    private val t9Centers = mapOf(
        '1' to PointF(-1f, -1f), '2' to PointF(0f, -1f), '3' to PointF(1f, -1f),
        '4' to PointF(-1f, 0f),  '5' to PointF(0f, 0f),  '6' to PointF(1f, 0f),
        '7' to PointF(-1f, 1f),  '8' to PointF(0f, 1f),  '9' to PointF(1f, 1f)
    )

    override fun onCreate() {
        super.onCreate()
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
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
        autoCap = prefs.getBoolean("auto_capitalization", true)
        visualDebug = prefs.getBoolean("visual_debug_mode", true)

        vibrateOnType = prefs.getBoolean("vibrate_on_type", true)
        vibrateDuration = prefs.getInt("vibrate_duration", 15).toLong()

        if (::swipeDebugView.isInitialized) {
            swipeDebugView.visibility = if (visualDebug) View.VISIBLE else View.GONE
        }

        // Load Modifiers
        m1KeyCode = prefs.getInt("key_mod_1", KeyEvent.KEYCODE_BUTTON_C)
        m2KeyCode = prefs.getInt("key_mod_2", KeyEvent.KEYCODE_BUTTON_Z)
        
        radialModifier = if (prefs.getString("joy_radial_mod", "M1") == "M2") ModifierKey.M2 else ModifierKey.M1
        cursorModifier = if (prefs.getString("joy_cursor_mod", "M2") == "M1") ModifierKey.M1 else ModifierKey.M2

        keyBindings.clear()

        // Helper function to pair keys with their dropdown modifiers
        fun bind(action: Action, keyPref: String, modPref: String, defaultKey: Int) {
            val keyCode = prefs.getInt(keyPref, defaultKey)
            val modString = prefs.getString(modPref, "NONE")
            val mod = when (modString) {
                "M1" -> ModifierKey.M1
                "M2" -> ModifierKey.M2
                else -> ModifierKey.NONE
            }
            // Only bind if the key is actually set to something valid
            if (keyCode != -1) {
                keyBindings[KeyCombo(keyCode, mod)] = action
            }
        }

        // Bind all actions with default values
        bind(Action.ACCEPT, "key_accept", "mod_accept", KeyEvent.KEYCODE_BUTTON_R1)
        bind(Action.CYCLE_PREV, "key_cycle_prev", "mod_cycle_prev", KeyEvent.KEYCODE_BUTTON_X)
        bind(Action.BACKSPACE_WORD, "key_backspace_word", "mod_backspace_word", KeyEvent.KEYCODE_BUTTON_Y)
        bind(Action.BACKSPACE_CHAR, "key_backspace_char", "mod_backspace_char", -1) 
        bind(Action.BACKSPACE_STROKE, "key_backspace_stroke", "mod_backspace_stroke", KeyEvent.KEYCODE_BUTTON_B)
        bind(Action.ADD_SPACE, "key_add_space", "mod_add_space", KeyEvent.KEYCODE_BUTTON_A)
        bind(Action.CLEAR_TEXT, "key_clear_text", "mod_clear_text", -1)
        bind(Action.ENTER, "key_enter", "mod_enter", KeyEvent.KEYCODE_BUTTON_R2)
        bind(Action.UNDO, "key_undo", "mod_undo", KeyEvent.KEYCODE_BUTTON_THUMBL)
        bind(Action.CLOSE_KEYBOARD, "key_close", "mod_close", KeyEvent.KEYCODE_BUTTON_SELECT)
        bind(Action.OPEN_SETTINGS, "key_open_settings", "mod_open_settings", KeyEvent.KEYCODE_BUTTON_START)
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        tvPredictions = view.findViewById(R.id.tv_predictions)
        hsvPredictions = view.findViewById(R.id.hsv_predictions)

        swipeDebugView = view.findViewById(R.id.swipe_debug_view)
        
        // NEW: Respect the visual debug setting on boot
        swipeDebugView.visibility = if (visualDebug) View.VISIBLE else View.GONE
        tvPredictions.text = "..."

        return view
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!isInputViewShown) return super.onGenericMotionEvent(event)

        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) {

            val rawX = event.getAxisValue(MotionEvent.AXIS_X)
            val rawY = event.getAxisValue(MotionEvent.AXIS_Y)
            val magL = sqrt(rawX * rawX + rawY * rawY)
            
            val rawZ = event.getAxisValue(MotionEvent.AXIS_Z)
            val rawRZ = event.getAxisValue(MotionEvent.AXIS_RZ)
            val magR = sqrt(rawZ * rawZ + rawRZ * rawRZ)

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
                    
                    // 1. Calculate how many pages we actually have
                    val maxPages = if (isPunctuationMode) 2 else kotlin.math.ceil(currentPredictions.size / 8.0).toInt().coerceAtLeast(1)
                    
                    // 2. Pagination Logic (iPod click-wheel style)
                    if (radialLastOctant != -1) {
                        if (radialLastOctant == 7 && octant == 0) {
                            // Roll forward, cap at the last page
                            radialPage = kotlin.math.min(radialPage + 1, maxPages - 1)
                        } else if (radialLastOctant == 0 && octant == 7) {
                            // Roll backward, cap at the first page
                            radialPage = kotlin.math.max(radialPage - 1, 0)
                        }
                    }
                    radialLastOctant = octant
                    
                    // 3. Slice the correct list based on the current page
                    val currentItems = if (isPunctuationMode) {
                        if (radialPage == 0) punctuationsP1 else punctuationsP2
                    } else {
                        val start = radialPage * 8
                        val end = kotlin.math.min(start + 8, currentPredictions.size)
                        if (start < currentPredictions.size) currentPredictions.subList(start, end) else emptyList()
                    }
                    
                    if (currentItems.isNotEmpty()) {
                        val newIndex = octant.coerceAtMost(currentItems.size - 1)
                        
                        // NEW: TRAVERSAL VIBRATION
                        if (newIndex != radialSelectedIndex) {
                            radialSelectedIndex = newIndex
                            triggerHapticClick() // Premium tick as you roll the stick!
                        }
                        updateUI()
                    }
                }
                return true
            }

            // --- CURSOR MODIFIER INTERCEPT ---
            val isCursorActive = (cursorModifier == ModifierKey.M1 && isM1Held) || (cursorModifier == ModifierKey.M2 && isM2Held)
    
            if (isCursorActive) {
                if (mag > 0.2f) { // Slight deadzone
                    val now = System.currentTimeMillis()
                    
                    // Analog Speed: Hard push = 50ms delay, Soft push = 150ms delay
                    val delay = 150L - (mag * 100L).toLong() 
                    
                    if (now - lastCursorMoveTime > delay) {
                        val ic = currentInputConnection
                        if (ic != null) {
                            // Determine if the user is pushing mostly horizontal or vertical
                            if (kotlin.math.abs(x) > kotlin.math.abs(y)) {
                                if (x > 0) ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
                                else ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
                            } else {
                                if (y > 0) ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
                                else ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
                            }
                            
                            // A tiny micro-vibration so you can "feel" the cursor jumping lines
                            if (vibrateOnType && vibrator.hasVibrator()) {
                                @Suppress("DEPRECATION")
                                vibrator.vibrate(5L) 
                            }
                            lastCursorMoveTime = now
                        }
                    }
                }
                return true // Stop standard typing math!
            }

            // --- NORMAL T9 TYPING ---
            handleJoyJoyMovement(x, y, mag)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

private fun handleJoyJoyMovement(rawX: Float, rawY: Float, mag: Float) {
        val mapped = mapCircleToSquare(rawX, rawY)

        // NEW: Vibrate on the ATTACK of the flick (surpassing 50% distance)
        if (mag > 0.5f && !vibratedThisStroke) {
            vibratedThisStroke = true
            triggerHapticClick() 
        }

        if (mag == 0.0f) {
            vibratedThisStroke = false // NEW: Reset the tracker when stick returns to center

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

        // Track Modifiers
        if (keyCode == m1KeyCode) isM1Held = true
        if (keyCode == m2KeyCode) isM2Held = true

        // Radial Menu Intercept
        val targetRadialKey = if (radialModifier == ModifierKey.M1) m1KeyCode else m2KeyCode
        if (keyCode == targetRadialKey) {
            isRadialMenuOpen = true
            isPunctuationMode = currentPredictions.isEmpty()
            radialSelectedIndex = 0
            radialPage = 0
            radialLastOctant = -1
            
            tvPredictions.animate().cancel() 
            tvPredictions.alpha = 0f
            tvPredictions.translationY = 30f 
            tvPredictions.animate().alpha(1f).translationY(0f).setDuration(200).start()
            
            updateUI()
            return true
        }

        // Action Check
        val currentMod = if (isM1Held) ModifierKey.M1 else if (isM2Held) ModifierKey.M2 else ModifierKey.NONE
        val action = keyBindings[KeyCombo(keyCode, currentMod)]
        
        if (action != null) {
            executeAction(action)
            return true
        }

        // DPAD PASSTHROUGH
        if (keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        
        // Track Modifier Releases
        if (keyCode == m1KeyCode) isM1Held = false
        if (keyCode == m2KeyCode) isM2Held = false

        // RADIAL UI: COMMIT
        val targetRadialKey = if (radialModifier == ModifierKey.M1) m1KeyCode else m2KeyCode
        if (keyCode == targetRadialKey) {
            if (isRadialMenuOpen) {
                isRadialMenuOpen = false

                // Cleanup the animation state
                tvPredictions.animate().cancel()
                tvPredictions.translationY = 0f

                val ic = currentInputConnection

                if (isPunctuationMode) {
                    val items = if (radialPage == 0) punctuationsP1 else punctuationsP2
                    saveUndoSnapshot()
                    ic?.commitText(items[radialSelectedIndex], 1)
                } else if (currentPredictions.isNotEmpty()) {
                    saveUndoSnapshot()
                    
                    // CALCULATE EXACT INDEX based on the current page!
                    val actualIndex = (radialPage * 8) + radialSelectedIndex
                    
                    if (actualIndex < currentPredictions.size) {
                        val wordToCommit = getCapitalizedWord(currentPredictions[actualIndex])
                        ic?.commitText(wordToCommit, 1)
                        if (autoSpace) ic?.commitText(" ", 1)
                        lastAcceptTime = System.currentTimeMillis()
                    }
                    resetState() 
                }
                updateUI()
            }
            return true
        }

        // D-PAD TEXT CURSOR PASSTHROUGH
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            return true
        }

        return super.onKeyUp(keyCode, event)
    }

    private fun executeAction(action: Action) {
        val ic = currentInputConnection ?: return

        when (action) {
            Action.ACCEPT -> {
                saveUndoSnapshot()

                triggerHapticClick()

                val now = System.currentTimeMillis()
                val ic = currentInputConnection ?: return

                if (currentPredictions.isNotEmpty()) {
                    // THE FIX: Check capitalization before committing!
                    val wordToCommit = getCapitalizedWord(currentPredictions[predictionIndex])
                    ic.commitText(wordToCommit, 1)
                    
                    if (autoSpace) ic.commitText(" ", 1)
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
                triggerHapticClick()

                if (wordProbabilities.isNotEmpty()) {
                    // COMPOSING MODE: Nuke the entire active input thread
                    wordProbabilities.clear()
                    currentStrokePath.clear()
                    updateLivePredictions()
                } else {
                    // NORMAL MODE: Delete the whole word in the text box
                    saveUndoSnapshot()
                    val textBefore = ic.getTextBeforeCursor(50, 0)?.toString() ?: return
                    val spacesMatch = Regex("\\s+$").find(textBefore)
                    val spacesLen = spacesMatch?.value?.length ?: 0
                    val wordMatch = Regex("\\S+\\s*$").find(textBefore)
                    val deleteLen = wordMatch?.value?.length ?: spacesLen
                    if (deleteLen > 0) ic.deleteSurroundingText(deleteLen, 0)
                }
            }
            Action.BACKSPACE_CHAR -> ic.deleteSurroundingText(1, 0)
            Action.ADD_SPACE -> {
                saveUndoSnapshot()

                triggerHapticClick()

                if (currentPredictions.isNotEmpty()) {
                    // If a word is queued up, accept it AND add a space
                    val wordToCommit = getCapitalizedWord(currentPredictions[predictionIndex])
                    ic.commitText(wordToCommit, 1)
                    if (autoSpace) ic.commitText(" ", 1)
                    lastAcceptTime = System.currentTimeMillis()
                    resetState()
                } else {
                    // Otherwise, just add a space
                    ic.commitText(" ", 1)
                }
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
            Action.ENTER -> {
                saveUndoSnapshot()
                val editorInfo = currentInputEditorInfo
                
                if (editorInfo != null) {
                    val actionId = editorInfo.imeOptions and android.view.inputmethod.EditorInfo.IME_MASK_ACTION
                    
                    // If the text box has a specific action (Search, Send, Done, Go)
                    if (actionId != android.view.inputmethod.EditorInfo.IME_ACTION_NONE && 
                        actionId != android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED) {
                        ic.performEditorAction(actionId)
                    } else {
                        // Otherwise, just inject a standard physical Enter/Return key press
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                    }
                }
                
                // Optional: If you want the keyboard to forcefully hide itself after pressing Enter
                // requestHideSelf(0) 
            }
            Action.BACKSPACE_STROKE -> {
                triggerHapticClick()

                if (wordProbabilities.isNotEmpty()) {
                    // COMPOSING MODE: Delete the last joystick flick
                    wordProbabilities.removeAt(wordProbabilities.size - 1)
                    updateLivePredictions()
                } else {
                    // NORMAL MODE: Act like a standard backspace
                    saveUndoSnapshot()
                    ic.deleteSurroundingText(1, 0)
                }
            }
            Action.CLOSE_KEYBOARD -> {
                triggerHapticClick()
                requestHideSelf(0)
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
        tvPredictions.text = "..."
        isRadialMenuOpen = false
        isPunctuationMode = false
        radialPage = 0
        radialLastOctant = -1
        vibratedThisStroke = false
    }

    private fun updateUI() {
        if (currentPredictions.isEmpty() && !isRadialMenuOpen) {
            tvPredictions.text = "..."
            return
        }
        
        // 1. Slice lists: Standard mode now ALSO caps at 8 words (Bug 1 Fix)
        val itemsToDraw = if (isRadialMenuOpen) {
            if (isPunctuationMode) {
                if (radialPage == 0) punctuationsP1 else punctuationsP2
            } else {
                val start = radialPage * 8
                val end = kotlin.math.min(start + 8, currentPredictions.size)
                if (start < currentPredictions.size) currentPredictions.subList(start, end) else emptyList()
            }
        } else {
            currentPredictions.take(8) // Standard typing is capped at 8!
        }

        // 2. Format the text
        var display = if (isRadialMenuOpen) {
            val arrows = arrayOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")
            itemsToDraw.mapIndexed { index, word ->
                val dir = if (index < arrows.size) arrows[index] else ""
                if (index == radialSelectedIndex) "<b><font color='#FFA500'>[$dir $word]</font></b>" 
                else "<font color='#555555'>$dir $word</font>"
            }.joinToString("   ")
        } else {
            itemsToDraw.mapIndexed { index, word ->
                if (index == predictionIndex) "<b><font color='#A3FF00'>[$word]</font></b>" 
                else "<font color='#777777'>$word</font>" 
            }.joinToString("   ")
        }

        // 3. Shortened Pagination: [1/3]
        if (isRadialMenuOpen) {
            val maxPages = if (isPunctuationMode) 2 else kotlin.math.ceil(currentPredictions.size / 8.0).toInt().coerceAtLeast(1)
            if (maxPages > 1) {
                display += "   <font color='#888888'><i>[${radialPage + 1}/$maxPages]</i></font>"
            }
        }
        
        tvPredictions.text = Html.fromHtml(display, Html.FROM_HTML_MODE_LEGACY)

        // 4. THE AUTO-SCROLL MAGIC
        if (isRadialMenuOpen && itemsToDraw.isNotEmpty()) {
            tvPredictions.post {
                val layout = tvPredictions.layout
                if (layout != null) {
                    val plainText = tvPredictions.text.toString()
                    val targetWord = itemsToDraw[radialSelectedIndex]
                    // Find where this word starts in the plain text string
                    val charIndex = plainText.indexOf("[$") // Matches the arrow and word
                    val fallbackIndex = plainText.indexOf(targetWord)
                    
                    val actualIndex = if (charIndex >= 0) charIndex else fallbackIndex
                    
                    if (actualIndex >= 0) {
                        // Ask Android for the exact pixel X coordinate of that letter!
                        val xOffset = layout.getPrimaryHorizontal(actualIndex).toInt()
                        // Scroll to it, minus half the screen width to center it perfectly
                        hsvPredictions.smoothScrollTo(xOffset - (hsvPredictions.width / 2), 0)
                    }
                }
            }
        } else {
            // Reset scroll when not in radial mode
            hsvPredictions.scrollTo(0, 0)
        }
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
        val radius = sqrt(u * u + v * v)
        val normalizedRadius = radius.coerceAtMost(1f)
        val theta = atan2(v, u)
        val cosTheta = abs(kotlin.math.cos(theta))
        val sinTheta = abs(kotlin.math.sin(theta))
        val scale = 1f / max(cosTheta, sinTheta)
        val mappedRadius = normalizedRadius * scale
        val x = mappedRadius * kotlin.math.cos(theta)
        val y = mappedRadius * kotlin.math.sin(theta)
        return PointF(x.coerceIn(-1f, 1f), y.coerceIn(-1f, 1f))
    }

    private fun getDistance(p1: PointF, x2: Float, y2: Float): Float {
        return sqrt((x2 - p1.x) * (x2 - p1.x) + (y2 - p1.y) * (y2 - p1.y))
    }

    private fun getCapitalizedWord(word: String): String {
        if (!autoCap) return word
        
        // 1. Hardcode for the English standalone "I"
        if (word.lowercase() == "i") return "I"
        
        val ic = currentInputConnection ?: return word
        
        // 2. Manual Brute-Force Check
        // Grab the 3 characters right before the cursor to check for punctuation and spaces
        val textBefore = ic.getTextBeforeCursor(3, 0)?.toString() ?: ""
        
        val isStartOfSentence = textBefore.isEmpty() || 
                                textBefore.endsWith(". ") || 
                                textBefore.endsWith("! ") || 
                                textBefore.endsWith("? ") || 
                                textBefore.endsWith("\n")

        if (isStartOfSentence) {
            return word.replaceFirstChar { it.uppercase() }
        }

        // 3. Fallback to OS checking just in case (for weird text fields)
        val editorInfo = currentInputEditorInfo
        if (editorInfo != null) {
            val capsMode = ic.getCursorCapsMode(editorInfo.inputType)
            if (capsMode > 0) {
                return word.replaceFirstChar { it.uppercase() }
            }
        }
        
        return word
    }

    private fun triggerHapticClick() {
        if (!vibrateOnType || !vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Force exact milliseconds, and force MAXIMUM amplitude (255)
            vibrator.vibrate(VibrationEffect.createOneShot(vibrateDuration, 255))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(vibrateDuration)
        }
    }
}