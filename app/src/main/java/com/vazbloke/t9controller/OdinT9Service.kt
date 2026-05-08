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
    private val lastAcceptedProbabilities = mutableListOf<Map<Char, Float>>() // NEW: Memory State
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
    private lateinit var hsvPredictions: android.widget.HorizontalScrollView

    enum class ModifierKey { NONE, M1, M2, M3 }
    
    // Replace your old Action enum mapping with a KeyCombo map
    data class KeyCombo(val keyCode: Int, val modifier: ModifierKey)
    private val keyBindings = mutableMapOf<KeyCombo, Action>()

    enum class Action {
        ACCEPT, CYCLE_PREV, BACKSPACE_WORD, BACKSPACE_STROKE, 
        ADD_SPACE, CLEAR_TEXT, UNDO, OPEN_SETTINGS, NONE, ENTER, 
        CLOSE_KEYBOARD, CURSOR_WORD_LEFT, CURSOR_WORD_RIGHT,
        CYCLE_FWD, CYCLE_BACK // NEW
    }

    // Modifier State
    private var isM1Held = false
    private var isM2Held = false
    private var isM3Held = false
    private var m1KeyCode = KeyEvent.KEYCODE_BUTTON_C
    private var m2KeyCode = KeyEvent.KEYCODE_BUTTON_Z
    private var m3KeyCode = -1
    
    private var radialModifier = ModifierKey.M1
    private var cursorModifier = ModifierKey.M2

    private val t9Centers = mapOf(
        '1' to PointF(-1f, -1f), '2' to PointF(0f, -1f), '3' to PointF(1f, -1f),
        '4' to PointF(-1f, 0f),  '5' to PointF(0f, 0f),  '6' to PointF(1f, 0f),
        '7' to PointF(-1f, 1f),  '8' to PointF(0f, 1f),  '9' to PointF(1f, 1f)
    )

    // Clickwheel
    private var isPeggedAtStart = false
    private var isPeggedAtEnd = false

    // --- Key Repeat State ---
    private var repeatDelay = 600L
    private val repeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var repeatingAction: Action? = null

    private val repeatRunnable = object : Runnable {
        override fun run() {
            repeatingAction?.let {
                executeAction(it)
                repeatHandler.postDelayed(this, 50L) // 50ms firing rate once the delay is passed
            }
        }
    }

    // --- Pair Input State ---
    private var pairInputMode = false
    private var peakPt: PointF? = null
    private var peakMag = 0f
    private var inValley = false
    private var lastMag = 0f
    private var isDescending = false
    private var lastDetectionType = ""
    private val registeredDebugPeaks = mutableListOf<PointF>()

    // --- Cursor Glide State ---
    private val cursorHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isCursorGliding = false
    private var cursorX = 0f
    private var cursorY = 0f
    private var cursorMag = 0f

    private val cursorGlideRunnable = object : Runnable {
        override fun run() {
            if (!isCursorGliding) return
            val ic = currentInputConnection ?: return
            
            // Analog Speed Math: Hard push = 30ms delay (fast), Soft push = 200ms delay (slow)
            val delay = 200L - (cursorMag * 170L).toLong()
            
            if (kotlin.math.abs(cursorX) > kotlin.math.abs(cursorY)) {
                if (cursorX > 0) ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
                else ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
            } else {
                if (cursorY > 0) ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
                else ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
            }
            
            cursorHandler.postDelayed(this, delay.coerceAtLeast(20L))
        }
    }

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

        repeatDelay = prefs.getInt("key_repeat_delay", 600).toLong()

        pairInputMode = prefs.getBoolean("pair_input_mode", false)

        if (::swipeDebugView.isInitialized) {
            swipeDebugView.visibility = if (visualDebug) View.VISIBLE else View.GONE
        }

        // Load Modifiers using SSOT
        m1KeyCode = prefs.getInt("key_mod_1", DefaultBindings.MAP["key_mod_1"]!!)
        m2KeyCode = prefs.getInt("key_mod_2", DefaultBindings.MAP["key_mod_2"]!!)
        m3KeyCode = prefs.getInt("key_mod_3", DefaultBindings.MAP["key_mod_3"]!!)

        val radialStr = prefs.getString("joy_radial_mod", "NONE")
        radialModifier = when(radialStr) {
            "M1" -> ModifierKey.M1
            "M2" -> ModifierKey.M2
            "M3" -> ModifierKey.M3
            else -> ModifierKey.NONE
        }
        
        val cursorStr = prefs.getString("joy_cursor_mod", "NONE")
        cursorModifier = when(cursorStr) {
            "M1" -> ModifierKey.M1
            "M2" -> ModifierKey.M2
            "M3" -> ModifierKey.M3
            else -> ModifierKey.NONE
        }

        keyBindings.clear()

        // Helper function to pair keys with their dropdown modifiers
        fun bind(action: Action, keyPref: String, modPref: String) {
            val defaultKey = DefaultBindings.MAP[keyPref] ?: -1
            val keyCode = prefs.getInt(keyPref, defaultKey)
            val modString = prefs.getString(modPref, "NONE")
            val mod = when (modString) {
                "M1" -> ModifierKey.M1
                "M2" -> ModifierKey.M2
                "M3" -> ModifierKey.M3
                else -> ModifierKey.NONE
            }
            if (keyCode != -1) {
                keyBindings[KeyCombo(keyCode, mod)] = action
            }
        }

        // Bind all actions with default values
        bind(Action.ACCEPT, "key_accept", "mod_accept")
        bind(Action.CYCLE_PREV, "key_cycle_prev", "mod_cycle_prev")
        bind(Action.BACKSPACE_WORD, "key_backspace_word", "mod_backspace_word")
        bind(Action.BACKSPACE_STROKE, "key_backspace_stroke", "mod_backspace_stroke")
        bind(Action.ADD_SPACE, "key_add_space", "mod_add_space")
        bind(Action.CLEAR_TEXT, "key_clear_text", "mod_clear_text")
        bind(Action.ENTER, "key_enter", "mod_enter")
        bind(Action.UNDO, "key_undo", "mod_undo")
        bind(Action.CLOSE_KEYBOARD, "key_close", "mod_close")
        bind(Action.OPEN_SETTINGS, "key_open_settings", "mod_open_settings")
        bind(Action.CURSOR_WORD_LEFT, "key_word_left", "mod_word_left")
        bind(Action.CURSOR_WORD_RIGHT, "key_word_right", "mod_word_right")
        bind(Action.CYCLE_FWD, "key_cycle_fwd", "mod_cycle_fwd")
        bind(Action.CYCLE_BACK, "key_cycle_back", "mod_cycle_back")
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        tvPredictions = view.findViewById(R.id.tv_predictions)
        swipeDebugView = view.findViewById(R.id.swipe_debug_view)
        
        // THE FIX: Hook up the scroll view so it doesn't crash!
        hsvPredictions = view.findViewById(R.id.hsv_predictions) 
        
        swipeDebugView.visibility = if (visualDebug) View.VISIBLE else View.GONE
        tvPredictions.text = "..."

        // Toast instruction
        tvPredictions.setOnClickListener {
            if (tvPredictions.text.toString() == "...") {
                android.widget.Toast.makeText(this, "Flick joystick to start typing", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        
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
                    val maxPages = if (isPunctuationMode) 2 else kotlin.math.ceil(currentPredictions.size / 8.0).toInt().coerceAtLeast(1)

                    if (radialLastOctant != -1) {
                        // Clockwise crossover (7 to 0)
                        if (radialLastOctant == 7 && octant == 0) {
                            if (isPeggedAtStart) {
                                isPeggedAtStart = false // THE FIX: Just unpeg, don't turn the page!
                            } else if (radialPage < maxPages - 1) {
                                radialPage++
                            } else if (!isPeggedAtEnd) {
                                isPeggedAtEnd = true
                                triggerHardHapticClick() // THUNK!
                            }
                        }
                        // Counter-Clockwise crossover (0 to 7)
                        else if (radialLastOctant == 0 && octant == 7) {
                            if (isPeggedAtEnd) {
                                isPeggedAtEnd = false // THE FIX: Just unpeg, don't turn the page!
                            } else if (radialPage > 0) {
                                radialPage--
                            } else if (!isPeggedAtStart) {
                                isPeggedAtStart = true
                                triggerHardHapticClick() // THUNK!
                            }
                        }
                    }

                    radialLastOctant = octant

                    // Un-peg if the user pulls the stick down away from the top boundary
                    if (octant in 2..6) {
                        isPeggedAtStart = false
                        isPeggedAtEnd = false
                    }

                    val currentItems = if (isPunctuationMode) {
                        if (radialPage == 0) punctuationsP1 else punctuationsP2
                    } else {
                        val start = radialPage * 8
                        val end = kotlin.math.min(start + 8, currentPredictions.size)
                        if (start < currentPredictions.size) currentPredictions.subList(start, end) else emptyList()
                    }

                    if (currentItems.isNotEmpty()) {
                        // Lock the visual selection if pegged against a wall
                        val newIndex = if (isPeggedAtStart) {
                            0
                        } else if (isPeggedAtEnd) {
                            currentItems.size - 1
                        } else {
                            octant.coerceAtMost(currentItems.size - 1)
                        }

                        if (newIndex != radialSelectedIndex) {
                            radialSelectedIndex = newIndex
                            // Only tick if they aren't pushing against a wall
                            if (!isPeggedAtStart && !isPeggedAtEnd) {
                                triggerHapticClick()
                            }
                        }
                        updateUI()
                    }
                } else {
                    // Stick released -> Reset scroll states completely
                    radialLastOctant = -1
                    isPeggedAtStart = false
                    isPeggedAtEnd = false
                }
                return true
            }

            // --- CURSOR MODIFIER INTERCEPT ---
            val isCursorActive = cursorModifier != ModifierKey.NONE && (
                (cursorModifier == ModifierKey.M1 && isM1Held) || 
                (cursorModifier == ModifierKey.M2 && isM2Held) || 
                (cursorModifier == ModifierKey.M3 && isM3Held)
            )

            if (isCursorActive) {
                if (mag > 0.2f) { // Deadzone
                    cursorX = x
                    cursorY = y
                    cursorMag = mag
                    if (!isCursorGliding) {
                        isCursorGliding = true
                        cursorHandler.post(cursorGlideRunnable) // Start gliding!
                    }
                } else {
                    isCursorGliding = false // Stop gliding
                }
                return true
            } else {
                isCursorGliding = false
            }

            // --- NORMAL T9 TYPING ---
            handleJoyJoyMovement(x, y, mag)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun handleJoyJoyMovement(rawX: Float, rawY: Float, mag: Float) {
        val mapped = mapCircleToSquare(rawX, rawY)

        // UX Polish: Clear the debug canvas ONLY when a brand new physical flick begins
        if (mag > 0.1f && currentStrokePath.isEmpty()) {
            registeredDebugPeaks.clear()
            lastDetectionType = ""
        }

        if (mag > 0.5f && !vibratedThisStroke) {
            vibratedThisStroke = true
            triggerHapticClick() 
        }

        // --- PAIR INPUT MODE (Dual-Heuristic Detection) ---
        if (pairInputMode && currentStrokePath.isNotEmpty()) {
            
            if (peakPt == null || mag > peakMag) {
                peakPt = PointF(mapped.x, mapped.y)
                peakMag = mag
            }
            
            var triggeredPair = false

            // Heuristic A: The Diagonal Slice
            if (mag < peakMag - 0.25f) { 
                inValley = true 
            } else if (inValley && mag > lastMag + 0.05f && mag > 0.3f) {
                triggeredPair = true 
                lastDetectionType = "Diagonal-slice" // LOG IT!
            }

            // Heuristic B: The Rim-Roll
            if (!triggeredPair && peakPt != null && peakMag > 0.5f) {
                val distFromPeak = getDistance(peakPt!!, mapped.x, mapped.y)
                if (distFromPeak > 0.7f && mag > 0.4f) {
                    triggeredPair = true 
                    lastDetectionType = "Rim-roll" // LOG IT!
                }
            }

            if (triggeredPair && peakPt != null) {
                wordProbabilities.add(generateProbabilityMap(peakPt!!))
                registeredDebugPeaks.add(PointF(peakPt!!.x, peakPt!!.y)) // SAVE THE PEAK!
                updateLivePredictions()
                
                vibratedThisStroke = false 
                triggerHapticClick()
                
                currentStrokePath.clear()
                currentStrokePath.add(PointF(mapped.x, mapped.y))
                peakPt = null
                peakMag = 0f
                inValley = false
                lastMag = mag
                
                // Update debug view immediately to show the glowing point mid-flick
                swipeDebugView.updateJoyT9Debug(currentStrokePath, registeredDebugPeaks, wordProbabilities, lastDetectionType)
                return
            }
        }
        
        lastMag = mag
        // ----------------------------------------------

        // Notice we changed this from 0.0f to 0.1f to enforce the hardware deadzone safety!
        if (mag < 0.1f) {
            vibratedThisStroke = false 
            peakPt = null
            peakMag = 0f
            inValley = false
            lastMag = 0f

            if (currentStrokePath.isNotEmpty()) {
                val maxPt = currentStrokePath.maxByOrNull { sqrt(it.x * it.x + it.y * it.y) }
                if (maxPt != null && sqrt(maxPt.x * maxPt.x + maxPt.y * maxPt.y) > 0.01f) {
                    wordProbabilities.add(generateProbabilityMap(maxPt))
                    registeredDebugPeaks.add(maxPt) // SAVE THE PEAK!
                    
                    // Only label it a normal flick if pair input didn't already trigger something else
                    if (lastDetectionType.isEmpty()) lastDetectionType = "Normal flick" 
                }
                currentStrokePath.clear()
                updateLivePredictions()
                
                // Update the debug view one last time so the final state persists on screen
                swipeDebugView.updateJoyT9Debug(currentStrokePath, registeredDebugPeaks, wordProbabilities, lastDetectionType)
            }
            return
        }

        currentStrokePath.add(PointF(mapped.x, mapped.y))
        swipeDebugView.updateJoyT9Debug(currentStrokePath, registeredDebugPeaks, wordProbabilities, lastDetectionType)
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

        // NEW: Check if it's a D-pad key
        val isDPad = keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
        
        // Eat held buttons (to prevent machine-gunning inputs), EXCEPT for the D-pad!
        if (event.repeatCount > 0 && !isDPad) return true

        // D-PAD TEXT CURSOR PASSTHROUGH
        if (isDPad) {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            return true
        }

        // Track Modifiers
        if (keyCode == m1KeyCode) isM1Held = true
        if (keyCode == m2KeyCode) isM2Held = true
        if (keyCode == m3KeyCode) isM3Held = true

        // Radial Menu Intercept
        val targetRadialKey = when (radialModifier) {
            ModifierKey.M1 -> m1KeyCode
            ModifierKey.M2 -> m2KeyCode
            ModifierKey.M3 -> m3KeyCode
            else -> -1
        }

        // Ensure it doesn't trigger if targetRadialKey is -1 (NONE selected)
        if (targetRadialKey != -1 && keyCode == targetRadialKey) {
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
        val currentMod = if (isM1Held) ModifierKey.M1 else if (isM2Held) ModifierKey.M2 else if (isM3Held) ModifierKey.M3 else ModifierKey.NONE
        val action = keyBindings[KeyCombo(keyCode, currentMod)]
        
        if (action != null) {
            executeAction(action)

            // Start the repeat timer (Ignore Action.NONE and Actions we don't want repeating)
            if (action != Action.NONE && action != Action.CLOSE_KEYBOARD && action != Action.OPEN_SETTINGS) {
                repeatingAction = action
                repeatHandler.postDelayed(repeatRunnable, repeatDelay)
            }

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

        // Cancel any repeating action when ANY key is lifted
        repeatingAction = null
        repeatHandler.removeCallbacks(repeatRunnable)
        
        // D-PAD TEXT CURSOR PASSTHROUGH
        val isDPad = keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
        if (isDPad) {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            return true
        }

        // Track Modifier Releases
        if (keyCode == m1KeyCode) isM1Held = false
        if (keyCode == m2KeyCode) isM2Held = false
        if (keyCode == m3KeyCode) isM3Held = false

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
        if (keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
            // Returning false refuses the input, forcing Android to natively pass it to the text box!
            return false 
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
                    val wordToCommit = getCapitalizedWord(currentPredictions[predictionIndex])
                    ic.commitText(wordToCommit, 1)
                    if (autoSpace) ic.commitText(" ", 1)
                    lastAcceptTime = now
                } else {
                    // FIX: Robust Double-Tap Period
                    if (doubleAcceptPeriod && (now - lastAcceptTime < 500)) {
                        val textBefore = ic.getTextBeforeCursor(10, 0)?.toString() ?: ""
                        // Hunt down any trailing spaces regardless of how many there are
                        val spacesMatch = Regex("\\s+$").find(textBefore) 
                        if (spacesMatch != null) {
                            ic.deleteSurroundingText(spacesMatch.value.length, 0)
                        }
                        ic.commitText(". ", 1)
                        lastAcceptTime = 0L 
                    } else {
                        ic.commitText(" ", 1)
                        lastAcceptTime = now
                    }
                }

                // Save the exact probability state before resetting!
                lastAcceptedProbabilities.clear()
                lastAcceptedProbabilities.addAll(wordProbabilities)

                resetState()
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
                    
                    if (textBefore.endsWith("\n")) {
                        ic.deleteSurroundingText(1, 0)
                    } else {
                        val spacesMatch = Regex("[ \\t]+$").find(textBefore) 
                        val spacesLen = spacesMatch?.value?.length ?: 0
                        val wordMatch = Regex("\\S+[ \\t]*$").find(textBefore)
                        val deleteLen = wordMatch?.value?.length ?: spacesLen
                        if (deleteLen > 0) ic.deleteSurroundingText(deleteLen, 0)
                    }
                }
            }
            Action.CYCLE_PREV -> {
                // If the user is currently typing a word, ignore this action so we don't overwrite their current thread
                if (wordProbabilities.isNotEmpty()) return 

                saveUndoSnapshot()
                triggerHapticClick()

                val extracted = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0) ?: return
                val text = extracted.text.toString()
                val cursor = extracted.selectionStart

                // 1. Skip any trailing spaces immediately before the cursor
                var searchCursor = cursor
                while (searchCursor > 0 && text[searchCursor - 1].isWhitespace()) {
                    searchCursor--
                }

                // 2. Expand outwards from the true end of the word to find the start
                var start = searchCursor
                while (start > 0 && text[start - 1].isLetter()) start--
                
                var end = searchCursor
                while (end < text.length && text[end].isLetter()) end++

                if (start < end) {
                    val targetWord = text.substring(start, end)

                    // 3. Delete from the start of the word ALL THE WAY to the original cursor 
                    // (This cleanly deletes the word AND the trailing spaces!)
                    ic.setSelection(start, cursor)
                    ic.commitText("", 1)

                    // 4. Reconstruct the active composing state
                    wordProbabilities.clear()
                    currentStrokePath.clear()
                    
                    // NEW: Authenticate the saved state!
                    var restoredState = false
                    if (lastAcceptedProbabilities.size == targetWord.length) {
                        // Dry-run the saved probabilities through the engine
                        val testPredictions = t9Engine.getProbabilisticPredictions(lastAcceptedProbabilities)
                        if (testPredictions.any { it.equals(targetWord, ignoreCase = true) }) {
                            // Validated! Restore the rich state.
                            wordProbabilities.addAll(lastAcceptedProbabilities)
                            restoredState = true
                        }
                    }
                    
                    // Fallback: If validation failed, 100% reverse-engineer it
                    if (!restoredState) {
                        val seq = t9Engine.wordToSequence(targetWord)
                        for (digit in seq) {
                            // Feed the engine 100% confidence for each digit
                            wordProbabilities.add(mapOf(digit to 1.0f))
                        }
                    }
                    
                    // Generate the predictions
                    currentPredictions = t9Engine.getProbabilisticPredictions(wordProbabilities)
                    // Try to pre-select the exact word they just pulled back
                    val foundIndex = currentPredictions.indexOfFirst { it.equals(targetWord, ignoreCase = true) }
                    predictionIndex = if (foundIndex != -1) foundIndex else 0
                    
                    // Ensure the radial menu is closed initially so they see the standard prediction bar                    
                    isRadialMenuOpen = false 
                    updateUI()
                }
            }
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
            Action.CURSOR_WORD_LEFT -> {
                triggerHapticClick()
                val textBefore = ic.getTextBeforeCursor(100, 0)?.toString() ?: return
                val match = Regex("\\s*\\S+\\s*$").find(textBefore)
                val jumpLength = match?.value?.length ?: textBefore.length
                for(i in 0 until jumpLength) ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
            }
            Action.CURSOR_WORD_RIGHT -> {
                triggerHapticClick()
                val textAfter = ic.getTextAfterCursor(100, 0)?.toString() ?: return
                val match = Regex("^\\s*\\S+").find(textAfter)
                val jumpLength = match?.value?.length ?: textAfter.length
                for(i in 0 until jumpLength) ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
            }
            Action.CYCLE_FWD -> {
                if (currentPredictions.isNotEmpty()) {
                    triggerHapticClick()
                    predictionIndex = (predictionIndex + 1) % currentPredictions.size
                    updateUI()
                }
            }
            Action.CYCLE_BACK -> {
                if (currentPredictions.isNotEmpty()) {
                    triggerHapticClick()
                    // Add currentPredictions.size to prevent negative modulo results!
                    predictionIndex = (predictionIndex - 1 + currentPredictions.size) % currentPredictions.size
                    updateUI()
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
        isPeggedAtStart = false
        isPeggedAtEnd = false
        lastMag = 0f
        isDescending = false

        // NEW: Reset Pair Input State!
        peakPt = null
        peakMag = 0f
        inValley = false
        lastMag = 0f
        lastDetectionType = ""
        registeredDebugPeaks.clear()
    }

    private fun updateUI() {
        if (currentPredictions.isEmpty() && !isRadialMenuOpen) {
            if (wordProbabilities.isNotEmpty()) {
                // UX Polish: The user is mid-stroke, but the engine currently has no exact matches to show.
                // Display a green indicator so they know the keyboard is still tracking their inputs!
                tvPredictions.text = android.text.Html.fromHtml("<b><font color='#A3FF00'>[...]</font></b>", android.text.Html.FROM_HTML_MODE_LEGACY)
            } else {
                // Resting state
                tvPredictions.text = "..."
            }
            return
        }
        
        // 1. Slice lists: Standard mode now ALSO caps at 8 words
        val itemsToDraw = if (isRadialMenuOpen) {
            if (isPunctuationMode) {
                if (radialPage == 0) punctuationsP1 else punctuationsP2
            } else {
                val start = radialPage * 8
                val end = kotlin.math.min(start + 8, currentPredictions.size)
                if (start < currentPredictions.size) currentPredictions.subList(start, end) else emptyList()
            }
        } else {
            currentPredictions.take(8)
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
        
        tvPredictions.text = android.text.Html.fromHtml(display, android.text.Html.FROM_HTML_MODE_LEGACY)

        // 4. THE CRASH FIX: Capture state and use Try/Catch Failsafes
        val capturedSelectedIndex = radialSelectedIndex
        val capturedItems = itemsToDraw.toList()

        if (isRadialMenuOpen && capturedItems.isNotEmpty()) {
            tvPredictions.post {
                val layout = tvPredictions.layout
                if (layout != null && capturedSelectedIndex < capturedItems.size) {
                    val plainText = tvPredictions.text.toString()
                    val targetWord = capturedItems[capturedSelectedIndex]
                    
                    val charIndex = plainText.indexOf("[$") 
                    val fallbackIndex = plainText.indexOf(targetWord)
                    val actualIndex = if (charIndex >= 0) charIndex else fallbackIndex
                    
                    // Explicitly check that the index is within the bounds of the CURRENT layout state
                    if (actualIndex >= 0 && actualIndex <= layout.text.length) {
                        try {
                            val xOffset = layout.getPrimaryHorizontal(actualIndex).toInt()
                            // Add tvPredictions.left so the scroll offset knows where the text actually starts inside the LinearLayout!
                            hsvPredictions.smoothScrollTo(tvPredictions.left + xOffset - (hsvPredictions.width / 2), 0)
                        } catch (e: Exception) {
                            // If Android's async layout engine desyncs during a hyper-fast spin,
                            // silently catch it. It will self-correct on the very next frame!
                            e.printStackTrace()
                        }
                    }
                }
            }
        } else {
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

    private fun triggerHardHapticClick() {
        if (!vibrateOnType || !vibrator.hasVibrator()) return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(vibrateDuration + 25L, 255))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(vibrateDuration + 25L)
        }
    }
}