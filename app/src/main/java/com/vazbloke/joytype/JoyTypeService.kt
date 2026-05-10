package com.vazbloke.joytype

import android.content.SharedPreferences
import android.graphics.PointF
import android.inputmethodservice.InputMethodService
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.ExtractedTextRequest
import android.widget.TextView
import androidx.preference.PreferenceManager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

class JoyTypeService : InputMethodService() {

    private lateinit var visualDebugView: VisualDebugView
    private val t9Engine = T9Engine()
    private val hardwareTypingMutex = Mutex()

    private lateinit var prefs: SharedPreferences

    // --- Core State ---
    private var isTriggerHeld = false
    private val currentStrokePath = mutableListOf<PointF>()
    private val wordProbabilities = mutableListOf<Map<Char, Float>>()
    private val lastAcceptedProbabilities = mutableListOf<Map<Char, Float>>() // NEW: Memory State
    private var vibratedThisStroke = false // NEW: Tracks the flick attack!
    
    // --- Radial UI State ---
    private var isRadialMenuOpen = false
    enum class RadialState { MACRO, UTILITY, PREDICTIONS, SPECIAL_CHARS }

    private val currentRadialState: RadialState
        get() {
            if (currentMode == InputMode.MACRO) return RadialState.MACRO
            if (currentPredictions === UTILITY_ACTIONS) return RadialState.UTILITY
            // Catch both T9 and ABC predictions
            if (currentPredictions.isNotEmpty()) return RadialState.PREDICTIONS 
            return RadialState.SPECIAL_CHARS
        }
    private var radialDidMove = false
    
    // Master Special character List (32 Symbols = 4 Pages)
    private val SPECIAL_CHARS = listOf(
        ".", ",", "?", "!", "@", "-", "_", ":", 
        ";", "'", "\"", "(", ")", "/", "\\", "&", 
        "#", "%", "*", "+", "=", "<", ">", "$", 
        "~", "`", "{", "}", "[", "]", "|", "^"
    )
    
    private var circleDetectedThisStroke = false

    private var currentPredictions = listOf<String>()
    private var predictionIndex = 0
    private val ABC_DIGITS = listOf(
        "1<small><font color='#999999'>.,?</font></small>", 
        "2<small><font color='#999999'>abc</font></small>", 
        "3<small><font color='#999999'>def</font></small>", 
        "4<small><font color='#999999'>ghi</font></small>", 
        "5<small><font color='#999999'>jkl</font></small>", 
        "6<small><font color='#999999'>mno</font></small>", 
        "7<small><font color='#999999'>pqrs</font></small>", 
        "8<small><font color='#999999'>tuv</font></small>", 
        "9<small><font color='#999999'>wxyz</font></small>"
    )

    // --- Undo/Redo State ---
    private data class TextSnapshot(val text: CharSequence, val selectionStart: Int, val selectionEnd: Int)
    
    private val undoStack = java.util.Stack<TextSnapshot>()
    private val redoStack = java.util.Stack<TextSnapshot>()

    // --- New Features State ---
    private var autoSpace = true
    private var doubleAcceptPeriod = true
    private var autoCap = true         // NEW
    private var visualDebug = true     // NEW
    private var lastAcceptTime = 0L

    private lateinit var haptics: HapticManager

    // --- Cursor Glide State ---
    private val cursorHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isCursorGliding = false
    private var cursorX = 0f
    private var cursorY = 0f
    private var cursorMag = 0f
    // Local math trackers to bypass IPC
    private var glideCursorIndex = 0
    private var glideTextLength = 0

    private lateinit var tvPredictions: TextView
    private lateinit var tvModeBadge: TextView
    private lateinit var tvPaginationBadge: TextView
    private lateinit var hsvPredictions: android.widget.HorizontalScrollView

    // --- Live Reload Receiver ---
    private val dictReloadReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == "com.vazbloke.joytype.RELOAD_DICT") {
                t9Engine.fullReload(this@JoyTypeService)
                android.widget.Toast.makeText(this@JoyTypeService, "Custom Dictionary Reloaded", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    enum class ModifierKey { NONE, M1, M2, M3 }
    
    // Replace your old Action enum mapping with a KeyCombo map
    data class KeyCombo(val keyCode: Int, val modifier: ModifierKey)
    private val keyBindings = mutableMapOf<KeyCombo, Action>()

    enum class Action(val xmlName: String, val defaultKey: Int, val defaultMod: ModifierKey) {
        ACCEPT("accept", KeyEvent.KEYCODE_BUTTON_R1, ModifierKey.NONE),
        RECOMPOSE("recompose", KeyEvent.KEYCODE_BUTTON_L1, ModifierKey.NONE),
        BACKSPACE_WORD("backspace_word", KeyEvent.KEYCODE_BUTTON_B, ModifierKey.M1),
        BACKSPACE_STROKE("backspace_stroke", KeyEvent.KEYCODE_BUTTON_B, ModifierKey.NONE),
        ADD_SPACE("add_space", KeyEvent.KEYCODE_BUTTON_A, ModifierKey.NONE),
        CLEAR_TEXT("clear_text", -1, ModifierKey.NONE),
        UNDO("undo", KeyEvent.KEYCODE_BUTTON_X, ModifierKey.NONE),
        REDO("redo", KeyEvent.KEYCODE_BUTTON_X, ModifierKey.M1),
        CLOSE_KEYBOARD("close", KeyEvent.KEYCODE_BUTTON_SELECT, ModifierKey.NONE),
        OPEN_SETTINGS("open_settings", KeyEvent.KEYCODE_BUTTON_START, ModifierKey.NONE),
        ENTER("enter", KeyEvent.KEYCODE_BUTTON_R2, ModifierKey.NONE),
        CURSOR_WORD_LEFT("word_left", KeyEvent.KEYCODE_DPAD_LEFT, ModifierKey.M2),
        CURSOR_WORD_RIGHT("word_right", KeyEvent.KEYCODE_DPAD_RIGHT, ModifierKey.M2),
        CYCLE_FWD("cycle_fwd", KeyEvent.KEYCODE_BUTTON_R1, ModifierKey.M1),
        CYCLE_BACK("cycle_back", KeyEvent.KEYCODE_BUTTON_L1, ModifierKey.M1),
        TOGGLE_MODE("toggle_mode", KeyEvent.KEYCODE_BUTTON_L2, ModifierKey.NONE),
        ADD_TO_DICT("add_to_dict", KeyEvent.KEYCODE_BUTTON_THUMBR, ModifierKey.NONE),
        TOGGLE_HIGHLIGHT("toggle_highlight", KeyEvent.KEYCODE_BUTTON_L2, ModifierKey.M1),
        NONE("none", -1, ModifierKey.NONE);

        val prefKey get() = "key_$xmlName"
        val prefMod get() = "mod_$xmlName"
    }

    enum class InputMode { T9, ABC, MACRO }
    private var currentMode = InputMode.T9
        set(value) {
            if (field != value) {
                field = value
                updateModeBadgeUI()
            }
        }
    
    private lateinit var llBreadcrumbBar: View
    private lateinit var tvBreadcrumb: TextView

    private var macroLibrary: List<MacroRepository.Macro> = emptyList()
    
    // REACTIVE SUBSCRIBER: Automatically called whenever 'currentMode' changes.
    // Manages structural visibility (Badges, Macro Bar).
    private fun updateModeBadgeUI() {
        if (!::tvModeBadge.isInitialized) return
        
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            when (currentMode) {
                InputMode.T9, InputMode.ABC -> {
                    tvModeBadge.text = if (currentMode == InputMode.T9) "[T9]" else "[ABC]"
                    tvModeBadge.setTextColor(android.graphics.Color.parseColor("#555555"))
                    tvModeBadge.visibility = View.VISIBLE
                    
                    if (::llBreadcrumbBar.isInitialized && llBreadcrumbBar.visibility == View.VISIBLE) {
                        llBreadcrumbBar.animate().alpha(0f).translationY(-20f).setDuration(150).withEndAction {
                            llBreadcrumbBar.visibility = View.GONE
                        }.start()
                    }
                }
                InputMode.MACRO -> {
                    tvModeBadge.text = "[MAC]"
                    tvModeBadge.setTextColor(android.graphics.Color.parseColor("#E6C229")) // Amber
                    tvModeBadge.visibility = View.VISIBLE
                    
                    // Only show the bar if we are actually deep inside a folder structure
                    // (Currently flat, so it stays hidden. When you add folders, it will animate
                    val hasSubfolders = false // Change this to macroPathStack.size > 1 when folders are added
                    
                    if (::llBreadcrumbBar.isInitialized) {
                        if (hasSubfolders) {
                            llBreadcrumbBar.translationY = -20f
                            llBreadcrumbBar.alpha = 0f
                            llBreadcrumbBar.visibility = View.VISIBLE
                            llBreadcrumbBar.animate().alpha(1f).translationY(0f).setDuration(200).start()
                        } else {
                            llBreadcrumbBar.visibility = View.GONE
                        }
                    }
                }
            }
        }
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

    // --- Key Repeat State ---
    private var repeatDelay = 600L
    private val repeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var repeatingAction: Action? = null

    private val repeatRunnable = object : Runnable {
        override fun run() {
            repeatingAction?.let {
                executeAction(it, isRepeat = true) // Flag for reduced vibrate
                // Increased the loop delay. 
                // This gives the physical weight inside the motor enough time to 
                // completely stop spinning between deletions, making it feel much lighter!
                repeatHandler.postDelayed(this, 140L) 
            }
        }
    }

    // --- Selection & Utility State ---
    private var isHighlighting = false
        // PUB SUB
        set(value) {
            // Only fire the UI update if the state actually changed
            if (field != value) {
                field = value
                updateSelectionBadgeUI()
            }
        }
    private var highlightAnchorIndex = -1
    private val UTILITY_ACTIONS = listOf("Cancel", "Copy", "Paste", "Cut", "Select Word", "Select All")
     // Add to your UI variables
    private lateinit var tvSelectionBadge: TextView
    // REACTIVE SUBSCRIBER: Automatically called whenever 'isHighlighting' changes.
    private fun updateSelectionBadgeUI() {
        // Failsafe in case state changes before the keyboard view is fully inflated
        if (!::tvSelectionBadge.isInitialized) return
        
        // Ensure UI updates always happen on the main thread
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            tvSelectionBadge.text = if (isHighlighting) "[SEL]" else "[CUR]"
            tvSelectionBadge.setTextColor(
                if (isHighlighting) android.graphics.Color.parseColor("#FF6B6B") 
                else android.graphics.Color.parseColor("#555555")
            )
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

    private val cursorGlideRunnable = object : Runnable {
        override fun run() {
            if (!isCursorGliding) return
            val ic = currentInputConnection ?: return
            
            val delay = 200L - (cursorMag * 160L).toLong()
            val isHorizontal = abs(cursorX) > abs(cursorY)
            
            val editorInfo = currentInputEditorInfo
            val requiresHardwareKeys = editorInfo == null || editorInfo.inputType == android.text.InputType.TYPE_NULL

            if (requiresHardwareKeys) {
                // --- EMULATOR MODE: Spoof Hardware Keys ---
                val code = if (isHorizontal) {
                    if (cursorX > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                } else {
                    if (cursorY > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                }

                // If selecting text, we must physically hold SHIFT while moving the D-Pad!
                if (isHighlighting) ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT))
                
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                
                if (isHighlighting) ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT))
                
            } else {
                // --- STANDARD ANDROID MODE: Software Math ---
                if (isHorizontal) {
                    if (cursorX > 0) {
                        if (glideCursorIndex < glideTextLength) glideCursorIndex++
                    } else {
                        if (glideCursorIndex > 0) glideCursorIndex--
                    }
                    ic.beginBatchEdit()
                    if (isHighlighting && highlightAnchorIndex != -1) {
                        ic.setSelection(highlightAnchorIndex, glideCursorIndex)
                    } else {
                        ic.setSelection(glideCursorIndex, glideCursorIndex)
                    }
                    ic.endBatchEdit()
                } else {
                    // Vertical software requires standard D-PAD
                    val code = if (cursorY > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                    if (isHighlighting) ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT))
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                    if (isHighlighting) ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT))
                    
                    val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
                    glideCursorIndex = extracted?.selectionEnd ?: glideCursorIndex
                }
            }
            
            haptics.tick() 
            cursorHandler.postDelayed(this, delay.coerceAtLeast(40L))
        }
    }

    // ---  MULTI-ENGINE ARCHITECTURE ---
    private val radialListener = object : RadialWheelEngine.RadialWheelListener {
        override fun onIndexChanged(newIndex: Int, page: Int) { updateUI() }
        override fun onTick() { haptics.tick() }
        override fun onThud() { haptics.thud() }
    }

    // Engine 1: The Standard 8-Way Wheel
    private val defaultRadialEngine = RadialWheelEngine(8, radialListener)
    
    // Engine 2: The ABC 9-Way Wheel
    private val abcRadialEngine = RadialWheelEngine(9, radialListener)

    // Dynamic Router: Automatically serves the correct engine based on current state
    private val activeRadialEngine: RadialWheelEngine
        get() = if (currentMode == InputMode.ABC && currentRadialState == RadialState.PREDICTIONS) {
            abcRadialEngine
        } else {
            defaultRadialEngine
        }

    private fun loadSettings() {
        autoSpace = prefs.getBoolean("autospace_after_accept", true)
        doubleAcceptPeriod = prefs.getBoolean("double_accept_period", true)
        autoCap = prefs.getBoolean("auto_capitalization", true)

        visualDebug = prefs.getBoolean("visual_debug_mode", false)

        // Inside loadSettings():
        val profileString = prefs.getString("haptic_profile", "MEDIUM") ?: "MEDIUM"
        haptics.currentProfile = try {
            HapticProfile.valueOf(profileString)
        } catch (_e: IllegalArgumentException) {
            // Failsafe in case of weird data
            HapticProfile.MEDIUM 
        }

        repeatDelay = prefs.getInt("key_repeat_delay", 600).toLong()

        macroLibrary = MacroRepository.loadMacros(prefs)

        pairInputMode = prefs.getBoolean("pair_input_mode", false)

        if (::visualDebugView.isInitialized) {
            visualDebugView.visibility = if (visualDebug) View.VISIBLE else View.GONE
        }

        // Load Modifiers using SSOT
        m1KeyCode = prefs.getInt("key_mod_1", KeyEvent.KEYCODE_BUTTON_C)
        m2KeyCode = prefs.getInt("key_mod_2", KeyEvent.KEYCODE_BUTTON_Z)
        m3KeyCode = prefs.getInt("key_mod_3", -1)

        val radialStr = prefs.getString("joy_radial_mod", "M1")
        radialModifier = when(radialStr) {
            "M1" -> ModifierKey.M1
            "M2" -> ModifierKey.M2
            "M3" -> ModifierKey.M3
            else -> ModifierKey.NONE
        }
        
        val cursorStr = prefs.getString("joy_cursor_mod", "M2")
        cursorModifier = when(cursorStr) {
            "M1" -> ModifierKey.M1
            "M2" -> ModifierKey.M2
            "M3" -> ModifierKey.M3
            else -> ModifierKey.NONE
        }

        keyBindings.clear()

        // The Enum is now the Single Source of Truth
        for (action in Action.values()) {
            if (action == Action.NONE) continue
            
            val keyCode = prefs.getInt(action.prefKey, action.defaultKey)
            val modString = prefs.getString(action.prefMod, action.defaultMod.name)
            
            val mod = try {
                ModifierKey.valueOf(modString ?: "NONE")
            } catch (e: Exception) { ModifierKey.NONE }
            
            if (keyCode != -1) {
                keyBindings[KeyCombo(keyCode, mod)] = action
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        haptics = HapticManager(this)
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        t9Engine.loadDictionary(this)
        loadSettings()

        // Register the receiver
        val filter = android.content.IntentFilter("com.vazbloke.joytype.RELOAD_DICT")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dictReloadReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(dictReloadReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(dictReloadReceiver)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        loadSettings()
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        tvPredictions = view.findViewById(R.id.tv_predictions)
        tvModeBadge = view.findViewById(R.id.tv_mode_badge)
        tvSelectionBadge = view.findViewById(R.id.tv_selection_badge)
        tvPaginationBadge = view.findViewById(R.id.tv_pagination_badge)

        visualDebugView = view.findViewById(R.id.swipe_debug_view)
        
        // THE FIX: Hook up the scroll view so it doesn't crash!
        hsvPredictions = view.findViewById(R.id.hsv_predictions) 

        llBreadcrumbBar = view.findViewById(R.id.ll_breadcrumb_bar)
        tvBreadcrumb = view.findViewById(R.id.tv_breadcrumb)

        visualDebugView.visibility = if (visualDebug) View.VISIBLE else View.GONE
        setRestingUI()

        // Toast instruction
        tvPredictions.setOnClickListener {
            if (currentPredictions.isEmpty() && !isRadialMenuOpen) {
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
                    val justWokeUp = !radialDidMove
                    radialDidMove = true
                    
                    // FIX: Force an instant redraw to paint the Orange highlight on Index 0
                    if (justWokeUp) updateUI()
                }
                
                val activeItemsCount = when(currentRadialState) {
                    RadialState.MACRO -> macroLibrary.size
                    RadialState.SPECIAL_CHARS -> SPECIAL_CHARS.size
                    RadialState.UTILITY -> UTILITY_ACTIONS.size
                    RadialState.PREDICTIONS -> currentPredictions.size
                }
                val editorInfo = currentInputEditorInfo
                val isEmulator = editorInfo == null || editorInfo.inputType == android.text.InputType.TYPE_NULL
                
                val disabledIndices = if (isEmulator && currentRadialState == RadialState.UTILITY) {
                    setOf(1, 3, 4, 5) 
                } else emptySet()
                
                activeRadialEngine.updateInput(x, y, mag, activeItemsCount, disabledIndices)
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

                        // 1. Capture the exact state of the text box ONCE
                        val extracted = currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0)
                        
                        // 2. Explicitly define our boundary variables
                        val currentSelectionStart = extracted?.selectionStart ?: 0
                        val currentSelectionEnd = extracted?.selectionEnd ?: 0
                        glideTextLength = extracted?.text?.length ?: 0

                        // 3. The Scroll Reset Fix: Find the moving head!
                        if (isHighlighting && highlightAnchorIndex != -1) {
                            // If the anchor is at the start, our moving head must be at the end (and vice versa)
                            glideCursorIndex = if (highlightAnchorIndex == currentSelectionStart) currentSelectionEnd else currentSelectionStart
                        } else {
                            // Normal cursor: start exactly where the OS cursor currently is
                            glideCursorIndex = currentSelectionStart
                        }

                        cursorHandler.post(cursorGlideRunnable) // Start gliding!
                    }
                } else {
                    isCursorGliding = false // Stop gliding
                }
                return true
            } else {
                // THE NEW RELEASE LOGIC
                if (isCursorGliding) {
                    isCursorGliding = false
                    
                    // If they released the stick right on the anchor (0 selection), cancel highlight mode safely
                    if (isHighlighting && highlightAnchorIndex == glideCursorIndex) {
                        isHighlighting = false
                        highlightAnchorIndex = -1
                        if (currentPredictions == UTILITY_ACTIONS) {
                            currentPredictions = emptyList()
                            updateUI()
                        }
                    }
                }
            }

            // --- NORMAL T9 TYPING ---
            handleStrokeInput(x, y, mag)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isInputViewShown) return super.onKeyDown(keyCode, event)

        val isDPad = keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)

        // Eat held buttons (to prevent machine-gunning inputs), EXCEPT for the D-pad!
        if (event.repeatCount > 0 && !isDPad) return true

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
            radialDidMove = false
            activeRadialEngine.reset()
            
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
            if (action != Action.NONE) {
                
                // THE NEW RADIAL DROP INTERCEPTOR
                if (isRadialMenuOpen) {
                    isRadialMenuOpen = false
                    
                    if (currentPredictions == UTILITY_ACTIONS) {
                        currentPredictions = emptyList()
                        isHighlighting = false
                        highlightAnchorIndex = -1
                    }
                    updateUI()
                }

                executeAction(action)

                // Start the repeat timer
                if (action != Action.NONE && action != Action.CLOSE_KEYBOARD && action != Action.OPEN_SETTINGS) {
                    repeatingAction = action
                    repeatHandler.postDelayed(repeatRunnable, repeatDelay)
                }

                return true
            }
        }

        // D-PAD TEXT CURSOR PASSTHROUGH
        if (isDPad) {
            val ic = currentInputConnection ?: return true
            
            // Bypass hardware event dispatch entirely for Left/Right. Use pure math!
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
                var cursorIndex = extracted?.selectionStart ?: 0
                val textLen = extracted?.text?.length ?: 0

                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && cursorIndex > 0) cursorIndex--
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && cursorIndex < textLen) cursorIndex++

                ic.beginBatchEdit()
                ic.setSelection(cursorIndex, cursorIndex)
                ic.endBatchEdit()
            } else {
                // For Up/Down, we still have to rely on Android's native text navigation, 
                // but we wrap it in a fallback flag to force it through.
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            }
            return true // We ate the event and handled it mathematically.
        }

        // FIX: Blackhole to consume all unmapped gamepad buttons (when keyboard open) so they don't leak to the OS
        if (KeyEvent.isGamepadButton(keyCode)) {
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {

        // Cancel any repeating action when ANY key is lifted
        repeatingAction = null
        repeatHandler.removeCallbacks(repeatRunnable)

        // Track Modifier Releases
        if (keyCode == m1KeyCode) isM1Held = false
        if (keyCode == m2KeyCode) isM2Held = false
        if (keyCode == m3KeyCode) isM3Held = false

        // RADIAL UI: COMMIT
        val targetRadialKey = if (radialModifier == ModifierKey.M1) m1KeyCode else m2KeyCode
        if (keyCode == targetRadialKey) {
            if (isRadialMenuOpen) {
                isRadialMenuOpen = false
                tvPredictions.animate().cancel()
                tvPredictions.translationY = 0f

                // SAFETY CATCH: Only execute if they actively flicked the stick
                if (radialDidMove) {
                    val activeItemsCount = when(currentRadialState) {
                        RadialState.MACRO -> macroLibrary.size
                        RadialState.SPECIAL_CHARS -> SPECIAL_CHARS.size
                        RadialState.UTILITY -> UTILITY_ACTIONS.size
                        RadialState.PREDICTIONS -> currentPredictions.size
                    }
                    
                    val actualIndex = (activeRadialEngine.radialPage * activeRadialEngine.maxSectors) + activeRadialEngine.radialSelectedIndex
                    val ic = currentInputConnection
                    
                    when (currentRadialState) {
                        RadialState.MACRO -> {
                            if (actualIndex < macroLibrary.size) {
                                val selectedMacro = macroLibrary[actualIndex]
                                val ic = currentInputConnection
                                
                                when (selectedMacro) {
                                    is MacroRepository.Macro.Keystroke -> {
                                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                            for (code in selectedMacro.sequence) {
                                                ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                                                kotlinx.coroutines.delay(15)
                                                ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                                                kotlinx.coroutines.delay(30)
                                            }
                                        }
                                    }
                                    is MacroRepository.Macro.Pasteboard -> {
                                        if (selectedMacro.text.isNotEmpty()) smartCommitText(selectedMacro.text)
                                    }
                                    is MacroRepository.Macro.LuaScript -> { /* Lua handling later */ }
                                }
                            }
                        }
                        RadialState.SPECIAL_CHARS -> {
                            if (actualIndex < SPECIAL_CHARS.size) {
                                saveUndoSnapshot()
                                val charToCommit = SPECIAL_CHARS[actualIndex]
                                val clingyPunctuation = listOf(".", ",", "?", "!", ":", ";", ")", "]", "}")
                                
                                if (clingyPunctuation.contains(charToCommit)) {
                                    ic?.beginBatchEdit()
                                    val textBefore = ic?.getTextBeforeCursor(1, 0)?.toString() ?: ""
                                    if (textBefore == " ") ic?.deleteSurroundingText(1, 0)
                                    ic?.commitText(charToCommit, 1)
                                    if (autoSpace && currentMode == InputMode.T9) ic?.commitText(" ", 1)
                                    ic?.endBatchEdit()
                                } else {
                                    ic?.commitText(charToCommit, 1)
                                }
                            }
                        }
                        RadialState.UTILITY -> {
                            if (actualIndex < UTILITY_ACTIONS.size) {
                                val command = UTILITY_ACTIONS[actualIndex]
                                val editorInfo = currentInputEditorInfo
                                val isEmulator = editorInfo == null || editorInfo.inputType == android.text.InputType.TYPE_NULL
                                val isDisabled = isEmulator && command in listOf("Copy", "Cut", "Select Word", "Select All")
                                
                                if (!isDisabled) {
                                    executeUtilityCommand(command)
                                } else {
                                    resetState()
                                    updateUI()
                                }
                            }
                        }
                        RadialState.PREDICTIONS -> {
                            if (actualIndex < currentPredictions.size) {
                                saveUndoSnapshot()
                                if (currentMode == InputMode.T9) {
                                    val wordToCommit = getCapitalizedWord(currentPredictions[actualIndex])
                                    smartCommitText(wordToCommit)
                                    if (autoSpace) smartCommitText(" ")
                                    lastAcceptTime = System.currentTimeMillis()
                                } else if (currentMode == InputMode.ABC && currentPredictions == ABC_DIGITS) {
                                    // --- STAGE 1 (Dive into the Letters / Symbols) ---
                                    diveIntoAbcStage2(currentPredictions[actualIndex])
                                    return true // Bypass standard completion so they stay in Stage 2
                                    
                                } else {
                                    smartCommitText(currentPredictions[actualIndex])
                                }
                            }
                        }
                    }
                }
                
                resetState() 
                updateUI()
            }
            return true
        }

        // D-PAD TEXT CURSOR PASSTHROUGH
        val isDPad = keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
        if (isDPad) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            }
            return true
        }

        // FIX: Blackhole to consume all unmapped gamepad buttons (when keyboard open) so they don't leak to the OS
        if (KeyEvent.isGamepadButton(keyCode)) {
            return true
        }

        return super.onKeyUp(keyCode, event)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)

        // FIX THE STUCK CURSOR
        // Only let the OS dictate our internal cursor position if the user IS NOT actively holding the joystick.
        if (!isCursorGliding) {
            if (isHighlighting && highlightAnchorIndex != -1) {
                // If we are highlighting, figure out which end is the moving head
                glideCursorIndex = if (highlightAnchorIndex == newSelStart) newSelEnd else newSelStart
            } else {
                glideCursorIndex = newSelEnd
            }
        }

        // FIX THE CROSSOVER CANCELLATION
        val isTextSelected = newSelStart != newSelEnd

        // Allow the menu to stay open if they pressed the Highlight button!
        if (isTextSelected || isHighlighting) {
            if (currentPredictions != UTILITY_ACTIONS) {
                currentPredictions = UTILITY_ACTIONS
                predictionIndex = 0
                updateUI()
            }
        } else {
            // Only auto-cancel selection mode if the user tapped away manually (joystick is resting).
            // If they are actively gliding, they are probably just crossing over the anchor point!
            if (!isCursorGliding && currentPredictions == UTILITY_ACTIONS) {
                currentPredictions = emptyList()
                isHighlighting = false
                highlightAnchorIndex = -1
                updateUI()
            }
        }
    }

    /// Purely to scroll the cursor into the visible area
    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        
        // Wait for the keyboard's layout animation to push the app's window up
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val ic = currentInputConnection ?: return@postDelayed
            
            // The OS ignores setSelection() if the cursor hasn't moved.
            // Committing a 0-length string forces the target app's input connection 
            // to update its layout constraints and bring the cursor into the visible viewport!
            ic.beginBatchEdit()
            ic.commitText("", 1)
            ic.endBatchEdit()
        }, 200L) // 200ms allows standard Android window animations to finish

        // Force the initial reactive draws on boot!
        updateModeBadgeUI()
        updateSelectionBadgeUI()
    }

    private fun handleStrokeInput(rawX: Float, rawY: Float, mag: Float) {
        // Disable canvas flicking in ABC mode
        if (currentMode == InputMode.ABC) return

        val mapped = mapCircleToSquare(rawX, rawY)

        // UX Polish: Clear the debug canvas ONLY when a brand new physical flick begins
        if (mag > 0.1f && currentStrokePath.isEmpty()) {
            registeredDebugPeaks.clear()
            lastDetectionType = ""
        }

        if (mag > 0.5f && !vibratedThisStroke) {
            vibratedThisStroke = true
            haptics.click() 
        }

        // --- PAIR INPUT MODE (Dual-Heuristic Detection) ---
        if (pairInputMode && currentStrokePath.isNotEmpty()) {
            
            if (peakPt == null || mag > peakMag) {
                peakPt = PointF(mapped.x, mapped.y)
                peakMag = mag
            }

            var triggeredPair = false

            // Heuristic A: The Diagonal Slice (Valley)
            // TIGHTENED: Require a deeper drop (-0.4f instead of -0.25f) to prove they truly crossed the center
            if (mag < peakMag - 0.4f) { 
                inValley = true 
            // TIGHTENED: Require a stronger push out of the valley (> 0.45f instead of > 0.3f)
            } else if (inValley && mag > lastMag + 0.05f && mag > 0.45f) {
                triggeredPair = true 
                lastDetectionType = "Diagonal-slice"
            }

            // Heuristic B: The Rim-Roll
            if (!triggeredPair && peakPt != null && peakMag > 0.6f) { // TIGHTENED: Peak must be stronger
                val distFromPeak = getDistance(peakPt!!, mapped.x, mapped.y)
                // TIGHTENED: Require a massive distance change (> 0.85f instead of > 0.7f) 
                // AND demand they stay pinned hard against the outer edge (> 0.6f instead of > 0.4f)
                if (distFromPeak > 0.85f && mag > 0.6f) {
                    triggeredPair = true 
                    lastDetectionType = "Rim-roll" 
                }
            }

            if (triggeredPair && peakPt != null) {
                wordProbabilities.add(generateProbabilityMap(peakPt!!))
                registeredDebugPeaks.add(PointF(peakPt!!.x, peakPt!!.y)) // SAVE THE PEAK!
                updateLivePredictions()
                
                vibratedThisStroke = false 
                haptics.tick()
                
                currentStrokePath.clear()
                currentStrokePath.add(PointF(mapped.x, mapped.y))
                peakPt = null
                peakMag = 0f
                inValley = false
                lastMag = mag
                
                // Update debug view immediately to show the glowing point mid-flick
                visualDebugView.updateJoyT9Debug(currentStrokePath, registeredDebugPeaks, wordProbabilities, lastDetectionType)
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
                    
                    if (currentMode == InputMode.T9) {
                        // --- NORMAL PREDICTIVE MODE ---
                        wordProbabilities.add(generateProbabilityMap(maxPt))
                        registeredDebugPeaks.add(maxPt) 
                        if (lastDetectionType.isEmpty()) lastDetectionType = "Normal flick" 
                        updateLivePredictions()
                    } else {
                        // --- MANUAL ABC MODE ---
                        val digitMap = generateProbabilityMap(maxPt)
                        
                        // THE FIX: Sort by probability to grab the winner AND the runner-up!
                        val sortedDigits = digitMap.entries.sortedByDescending { it.value }
                        val winningDigit = sortedDigits.getOrNull(0)?.key ?: '5'
                        val runnerUpDigit = sortedDigits.getOrNull(1)?.key
                        
                        val chars = mutableListOf<String>()
                        
                        // Helper lambda to format and inject a digit's characters
                        val injectChars = { targetDigit: Char ->
                            val baseChars = t9Engine.getCharsForDigit(targetDigit)
                            for (c in baseChars) {
                                chars.add(c.uppercaseChar().toString()) // 'T'
                                chars.add(c.toString())                 // 't'
                            }
                            chars.add(targetDigit.toString())           // '8'
                        }
                        
                        // 1. Inject the primary intended digit
                        injectChars(winningDigit)
                        
                        // 2. Inject the runner-up digit (in case their angle was slightly off)
                        if (runnerUpDigit != null) {
                            injectChars(runnerUpDigit)
                        }
                        
                        currentPredictions = chars
                        predictionIndex = 0
                        isRadialMenuOpen = false 
                        
                        lastDetectionType = "Manual Entry"
                    }
                }
                currentStrokePath.clear()
                updateUI()
                visualDebugView.updateJoyT9Debug(currentStrokePath, registeredDebugPeaks, wordProbabilities, lastDetectionType)
            }
            return
        }

        currentStrokePath.add(PointF(mapped.x, mapped.y))
        visualDebugView.updateJoyT9Debug(currentStrokePath, registeredDebugPeaks, wordProbabilities, lastDetectionType)
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

    private fun executeAction(action: Action, isRepeat: Boolean = false) {
        val ic = currentInputConnection ?: return

        // 2. Add this helper function
        val fireActionHaptic = {
            if (isRepeat) haptics.repeatTick() else haptics.click()
        }

        when (action) {
            Action.ACCEPT -> {
                saveUndoSnapshot()
                fireActionHaptic()

                if (currentPredictions == UTILITY_ACTIONS) {
                    executeUtilityCommand(currentPredictions[predictionIndex])
                    return
                }

                // --- MACRO INTERCEPT ---
                if (currentMode == InputMode.MACRO) {
                    val targetIndex = if (isRadialMenuOpen) {
                        (activeRadialEngine.radialPage * activeRadialEngine.maxSectors) + activeRadialEngine.radialSelectedIndex
                    } else {
                        predictionIndex
                    }

                    if (macroLibrary.isNotEmpty() && targetIndex < macroLibrary.size) {
                        val selectedMacro = macroLibrary[targetIndex]
                        val ic = currentInputConnection ?: return
                        
                        when (selectedMacro) {
                            is MacroRepository.Macro.Keystroke -> {
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                    for (keyCode in selectedMacro.sequence) {
                                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                                        kotlinx.coroutines.delay(15) 
                                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                                        kotlinx.coroutines.delay(30)
                                    }
                                }
                            }
                            is MacroRepository.Macro.Pasteboard -> smartCommitText(selectedMacro.text)
                            is MacroRepository.Macro.LuaScript -> { /* Future Lua execution */ }
                        }
                        
                        isRadialMenuOpen = false
                        updateUI() 
                    }
                    return 
                }

                val now = System.currentTimeMillis()
                val ic = currentInputConnection ?: return

                if (currentPredictions.isNotEmpty()) {
                    if (currentMode == InputMode.T9) {
                        var wordToCommit = getCapitalizedWord(currentPredictions[predictionIndex])
                        
                        if (autoSpace) {
                            val textBefore = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
                            val requiresPreSpace = listOf(".", ",", "?", "!", ":", ";", ")", "]", "}").contains(textBefore)
                            if (requiresPreSpace) wordToCommit = " $wordToCommit"
                            
                            val textAfter = ic.getTextAfterCursor(1, 0)?.toString() ?: ""
                            if (!textAfter.startsWith(" ") && !textAfter.startsWith(".") && !textAfter.startsWith(",")) {
                                // THE FIX: Append the space directly to the string!
                                wordToCommit += " " 
                            }
                        }
                        
                        // Send the entire block to a single coroutine (for spaces)
                        smartCommitText(wordToCommit)
                    } else if (currentMode == InputMode.ABC && currentPredictions == ABC_DIGITS) {
                        diveIntoAbcStage2(currentPredictions[predictionIndex])
                        return // Exit early so we don't trigger the resetState() below
                    } else {
                        smartCommitText(currentPredictions[predictionIndex])
                    }
                    
                    val wasT9 = currentMode == InputMode.T9
                    resetState()
                    if (wasT9) lastAcceptTime = now 
                } else {
                    if (currentMode == InputMode.T9 && doubleAcceptPeriod && (now - lastAcceptTime < 500)) {
                        val textBefore = ic.getTextBeforeCursor(10, 0)?.toString() ?: ""
                        val spacesMatch = Regex("\\s+$").find(textBefore)
                        
                        ic.beginBatchEdit()
                        if (spacesMatch != null) {
                            smartDelete(spacesMatch.value.length)
                        }
                        smartCommitText(". ")
                        ic.endBatchEdit()
                        lastAcceptTime = 0L 
                    } else {
                        if (currentMode == InputMode.T9) lastAcceptTime = now
                    }
                }
            }
            Action.BACKSPACE_WORD -> {
                fireActionHaptic()

                // If text is highlighted, just delete the selection
                if (currentPredictions == UTILITY_ACTIONS) {
                    saveUndoSnapshot()
                    smartDelete(1)
                    resetState()
                    return
                }

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
                        if (deleteLen > 0) smartDelete(deleteLen)
                    }
                }
            }
            Action.RECOMPOSE -> {
                // If the user is currently typing a word, ignore this action so we don't overwrite their current thread
                if (wordProbabilities.isNotEmpty()) return 

                saveUndoSnapshot()
                fireActionHaptic()

                val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return
                val text = extracted.text.toString()
                val cursor = extracted.selectionStart

                // Helper lambda: Words consist of letters OR apostrophes
                val isWordChar = { c: Char -> c.isLetter() || c == '\'' }

                // 1. THE FIX: Skip trailing spaces AND special characters immediately before the cursor
                var searchCursor = cursor
                while (searchCursor > 0 && !isWordChar(text[searchCursor - 1])) {
                    searchCursor--
                }

                // 2. Expand outwards from the true end of the word to find the start
                var start = searchCursor
                while (start > 0 && isWordChar(text[start - 1])) start--
                
                var end = searchCursor
                while (end < text.length && isWordChar(text[end])) end++

                if (start < end) {
                    val targetWord = text.substring(start, end)
                    val deleteEnd = kotlin.math.max(end, cursor)

                    // Delete mathematically without triggering the selection hijack
                    val leftLen = cursor - start
                    val rightLen = deleteEnd - cursor
                    ic.deleteSurroundingText(leftLen, rightLen)

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

                    // Restore original capitalization state! ---
                    val isCapitalized = targetWord.isNotEmpty() && targetWord[0].isUpperCase()
                    if (isCapitalized) {
                        currentPredictions = currentPredictions.map { it.replaceFirstChar { c -> c.uppercase() } }
                    }

                    // If the engine couldn't find the word
                    if (currentPredictions.isEmpty() || currentPredictions.none { it.equals(targetWord, ignoreCase = true) }) {
                        val newPredictions = currentPredictions.toMutableList()
                        newPredictions.add(0, targetWord)
                        currentPredictions = newPredictions
                    }

                    // Try to pre-select the exact word they just pulled back
                    val foundIndex = currentPredictions.indexOfFirst { it.equals(targetWord, ignoreCase = true) }
                    predictionIndex = if (foundIndex != -1) foundIndex else 0
                    
                    isRadialMenuOpen = false 
                    updateUI()
                }
            }
            Action.ADD_SPACE -> {
                saveUndoSnapshot()
                fireActionHaptic()

                if (currentPredictions == UTILITY_ACTIONS) {
                    smartCommitText(" ")
                    resetState()
                    return
                }

                val now = System.currentTimeMillis()
                val ic = currentInputConnection ?: return

                if (currentPredictions.isNotEmpty()) {
                    if (currentMode == InputMode.T9) {
                        var wordToCommit = getCapitalizedWord(currentPredictions[predictionIndex])
                        // THE FIX: Append the space directly to the string!
                        if (autoSpace) wordToCommit += " " 
                        smartCommitText(wordToCommit)
                        lastAcceptTime = now
                    } else {
                        smartCommitText(currentPredictions[predictionIndex])
                    }
                    resetState()
                } else {
                    if (currentMode == InputMode.T9 && doubleAcceptPeriod && (now - lastAcceptTime < 500)) {
                        val textBefore = ic.getTextBeforeCursor(10, 0)?.toString() ?: ""
                        val spacesMatch = Regex("\\s+$").find(textBefore)
                        
                        ic.beginBatchEdit()
                        if (spacesMatch != null) {
                            smartDelete(spacesMatch.value.length)
                        }
                        smartCommitText(". ")
                        ic.endBatchEdit()
                        lastAcceptTime = 0L
                    } else if (currentMode == InputMode.ABC && currentPredictions == ABC_DIGITS) {
                        diveIntoAbcStage2(currentPredictions[predictionIndex])
                        return // Exit early so we don't trigger the resetState() below
                    } else {
                        smartCommitText(" ")
                        if (currentMode == InputMode.T9) lastAcceptTime = now
                    }
                }
            }
            Action.CLEAR_TEXT -> {
                saveUndoSnapshot()
                fireActionHaptic()
                ic.performContextMenuAction(android.R.id.selectAll)
                ic.commitText("", 1)
            }
            Action.UNDO -> {
                if (undoStack.isNotEmpty()) {
                    fireActionHaptic()
                    val ic = currentInputConnection ?: return
                    
                    // Save current state to Redo before we go back
                    val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
                    if (extracted != null && extracted.text != null) {
                        redoStack.push(TextSnapshot(extracted.text, extracted.selectionStart, extracted.selectionEnd))
                    }
                    
                    val previousState = undoStack.pop()
                    
                    ic.beginBatchEdit()
                    ic.performContextMenuAction(android.R.id.selectAll)
                    ic.commitText(previousState.text, 1)
                    
                    // Restore the exact cursor position
                    ic.setSelection(previousState.selectionStart, previousState.selectionEnd)
                    
                    // Re-sync local cursor math to prevent jump glitches on next joystick flick
                    glideCursorIndex = previousState.selectionEnd
                    ic.endBatchEdit()

                    // Nuke the active composing memory so the UI resets!
                    resetState()

                    // THE FIX: Re-evaluate if the restored state was a selection
                    if (previousState.selectionStart != previousState.selectionEnd) {
                        isHighlighting = true
                        highlightAnchorIndex = previousState.selectionStart
                        glideCursorIndex = previousState.selectionEnd
                        currentPredictions = UTILITY_ACTIONS
                        
                        updateUI()
                    } else {
                        // Re-sync local cursor math for normal cursor
                        glideCursorIndex = previousState.selectionEnd
                    }
                }
            }
            Action.REDO -> {
                if (redoStack.isNotEmpty()) {
                    fireActionHaptic()
                    val ic = currentInputConnection ?: return
                    
                    // Save current state to Undo before we go forward
                    val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
                    if (extracted != null && extracted.text != null) {
                        undoStack.push(TextSnapshot(extracted.text, extracted.selectionStart, extracted.selectionEnd))
                    }
                    
                    val nextState = redoStack.pop()
                    
                    ic.beginBatchEdit()
                    ic.performContextMenuAction(android.R.id.selectAll)
                    ic.commitText(nextState.text, 1)
                    
                    // Restore the exact cursor position
                    ic.setSelection(nextState.selectionStart, nextState.selectionEnd)
                    
                    // Re-sync local cursor math
                    glideCursorIndex = nextState.selectionEnd
                    ic.endBatchEdit()

                    // Nuke the active composing memory so the UI resets!
                    resetState()

                    // THE FIX: Re-evaluate if the restored state was a selection
                    if (nextState.selectionStart != nextState.selectionEnd) {
                        isHighlighting = true
                        highlightAnchorIndex = nextState.selectionStart
                        glideCursorIndex = nextState.selectionEnd
                        currentPredictions = UTILITY_ACTIONS
                        
                        updateUI()
                    } else {
                        // Re-sync local cursor math for normal cursor
                        glideCursorIndex = nextState.selectionEnd
                    }
                }
            }
            Action.OPEN_SETTINGS -> {
                val intent = android.content.Intent(this, SettingsActivity::class.java)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
            Action.ENTER -> {
                saveUndoSnapshot()
                fireActionHaptic()
                val editorInfo = currentInputEditorInfo
                
                if (editorInfo != null) {
                    val actionId = editorInfo.imeOptions and android.view.inputmethod.EditorInfo.IME_MASK_ACTION
                    if (actionId != android.view.inputmethod.EditorInfo.IME_ACTION_NONE && 
                        actionId != android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED) {
                        // If the text box has a specific action (Search, Send, Done, Go)
                        ic.performEditorAction(actionId)
                    } else {
                        // Software string insertion inherently triggers 
                        // the OS's "scroll to cursor" layout pass. Hardware keys do not.
                        ic.commitText("\n", 1)
                    }
                }
            }
            Action.BACKSPACE_STROKE -> {
                fireActionHaptic()

                // STAGE 2 CANCEL: Pressing backspace returns you to the Digits
                if (currentMode == InputMode.ABC && currentPredictions != ABC_DIGITS) {
                    resetState()
                    updateUI()
                    return
                }

                // If text is highlighted, delete the entire selection
                if (currentPredictions == UTILITY_ACTIONS) {
                    saveUndoSnapshot()
                    smartDelete(1)
                    resetState()
                    return
                }

                if (wordProbabilities.isNotEmpty()) {
                    // COMPOSING MODE: Delete the last joystick flick
                    wordProbabilities.removeAt(wordProbabilities.size - 1)
                    updateLivePredictions()
                } else {
                    // NORMAL MODE: Act like a standard backspace
                    saveUndoSnapshot()
                    smartDelete(1)
                }
            }
            Action.CLOSE_KEYBOARD -> {
                fireActionHaptic()
                requestHideSelf(0)
            }
            Action.CURSOR_WORD_LEFT -> {
                fireActionHaptic()
                val textBefore = ic.getTextBeforeCursor(100, 0)?.toString() ?: return
                val match = Regex("\\s*\\S+\\s*$").find(textBefore)
                val jumpLength = match?.value?.length ?: textBefore.length
                for(_i in 0 until jumpLength) ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
            }
            Action.CURSOR_WORD_RIGHT -> {
                fireActionHaptic()
                val textAfter = ic.getTextAfterCursor(100, 0)?.toString() ?: return
                val match = Regex("^\\s*\\S+").find(textAfter)
                val jumpLength = match?.value?.length ?: textAfter.length
                for(_i in 0 until jumpLength) ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
            }
            Action.CYCLE_FWD -> {
                val maxCount = if (currentMode == InputMode.MACRO) macroLibrary.size else currentPredictions.size
                if (maxCount > 0) {
                    fireActionHaptic()
                    predictionIndex = (predictionIndex + 1) % maxCount
                    updateUI()
                }
            }
            Action.CYCLE_BACK -> {
                val maxCount = if (currentMode == InputMode.MACRO) macroLibrary.size else currentPredictions.size
                if (maxCount > 0) {
                    fireActionHaptic()
                    predictionIndex = (predictionIndex - 1 + maxCount) % maxCount
                    updateUI()
                }
            }
            Action.TOGGLE_MODE -> {
                if (wordProbabilities.isNotEmpty()) {
                    android.widget.Toast.makeText(this, "Cannot switch mode mid-type", android.widget.Toast.LENGTH_SHORT).show()
                    haptics.thud() 
                    return
                }

                fireActionHaptic()
                
                // Cycle through the modes
                currentMode = when (currentMode) {
                    InputMode.T9 -> InputMode.ABC
                    InputMode.ABC -> InputMode.MACRO
                    InputMode.MACRO -> InputMode.T9
                }
                
                // Reset scroll position when entering the mode
                if (currentMode == InputMode.MACRO) {
                    predictionIndex = 0
                }
                
                resetState() 
                updateUI()
            }
            Action.ADD_TO_DICT -> {
                fireActionHaptic()
                val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return
                val text = extracted.text.toString()
                val cursor = extracted.selectionStart

                // Walk left and right to find the word under the cursor
                var start = cursor
                while (start > 0 && text[start - 1].isLetterOrDigit()) start--
                var end = cursor
                while (end < text.length && text[end].isLetterOrDigit()) end++

                if (start < end) {
                    val targetWord = text.substring(start, end)
                    t9Engine.addCustomWord(targetWord)
                    android.widget.Toast.makeText(this, "Added: '$targetWord'", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            Action.TOGGLE_HIGHLIGHT -> {
                fireActionHaptic()
                isHighlighting = !isHighlighting
                val ic = currentInputConnection ?: return
                
                if (isHighlighting) {
                    val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
                    highlightAnchorIndex = extracted?.selectionStart ?: 0
                    glideCursorIndex = highlightAnchorIndex
                    
                    // Force the utility menu open immediately, even with no selection
                    currentPredictions = UTILITY_ACTIONS
                    predictionIndex = 0
                } else {
                    highlightAnchorIndex = -1
                    ic.setSelection(glideCursorIndex, glideCursorIndex) 
                    if (currentPredictions == UTILITY_ACTIONS) {
                        currentPredictions = emptyList()
                    }
                }
                updateUI()
            }
            Action.NONE -> {}
        }
    }

    private fun saveUndoSnapshot() {
        val ic = currentInputConnection ?: return
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return
        
        val currentText = extracted.text
        val selStart = extracted.selectionStart
        val selEnd = extracted.selectionEnd
        
        if (currentText != null) {
            undoStack.push(TextSnapshot(currentText, selStart, selEnd))
            if (undoStack.size > 20) undoStack.removeAt(0)
            
            redoStack.clear() // Any new typing breaks the Redo timeline
        }
    }

    private fun resetState() {
        // Cache the active stroke probabilities BEFORE clearing them
        if (wordProbabilities.isNotEmpty()) {
            lastAcceptedProbabilities.clear()
            lastAcceptedProbabilities.addAll(wordProbabilities)
        }

        isTriggerHeld = false
        currentStrokePath.clear()
        wordProbabilities.clear()
        circleDetectedThisStroke = false

        // Two-Stage ABC Initialization
        if (currentMode == InputMode.ABC) {
            currentPredictions = ABC_DIGITS
            predictionIndex = 0
        } else {
            currentPredictions = emptyList()
            predictionIndex = 0
        }

        visualDebugView.clear()
        setRestingUI()  

        isRadialMenuOpen = false
        radialDidMove = false
        defaultRadialEngine.reset()
        abcRadialEngine.reset()
        vibratedThisStroke = false

        lastMag = 0f
        isDescending = false

        // Pair Input State
        peakPt = null
        peakMag = 0f
        inValley = false
        lastMag = 0f
        lastDetectionType = ""
        registeredDebugPeaks.clear()

        isHighlighting = false
        highlightAnchorIndex = -1
    }

    private fun diveIntoAbcStage2(targetStr: String) {
        val targetDigit = targetStr.first()
        val chars = mutableListOf<String>()
        
        // Note: Change '1' to '5' here if you want 5 to trigger special characters
        if (targetDigit == '1') {
            chars.addAll(SPECIAL_CHARS)
            chars.add("1") // Put the digit at the very end as a fallback
        } else {
            val baseChars = t9Engine.getCharsForDigit(targetDigit)
            for (c in baseChars) {
                chars.add(c.uppercaseChar().toString())
                chars.add(c.toString())
            }
            chars.add(targetDigit.toString())
        }
        
        currentPredictions = chars
        predictionIndex = 0
        isRadialMenuOpen = false 
        updateUI()
    }

    private fun updateUI() {
        // 1. Structural Updates
        if (currentMode == InputMode.MACRO && ::tvBreadcrumb.isInitialized) {
            tvBreadcrumb.text = "MACROS"
        }

        // 2. Resting State Check
        if (currentPredictions.isEmpty() && !isRadialMenuOpen && currentMode != InputMode.MACRO) {
            setRestingUI(isComposingEmpty = wordProbabilities.isNotEmpty())
            return
        }

        // BADGE FIX: Explicitly set it here, and NEVER set it to GONE unless resting.
        tvModeBadge.visibility = View.VISIBLE
        tvModeBadge.text = when (currentMode) {
            InputMode.T9 -> "[T9]"
            InputMode.ABC -> "[ABC]"
            InputMode.MACRO -> "[MAC]"
        }

        // 3. Gather Active Items using Source of Truth
        val state = currentRadialState
        val activeItems = when (state) {
            RadialState.MACRO -> macroLibrary.map { it.name }
            RadialState.SPECIAL_CHARS -> SPECIAL_CHARS
            RadialState.UTILITY -> UTILITY_ACTIONS
            RadialState.PREDICTIONS -> currentPredictions
        }

        // 4. Determine Items to Draw using the cleanly abstracted Engine
        val engine = activeRadialEngine
        val itemsToDraw = if (isRadialMenuOpen) {
            val start = engine.radialPage * engine.maxSectors
            val end = kotlin.math.min(start + engine.maxSectors, activeItems.size)
            if (start < activeItems.size) activeItems.subList(start, end) else emptyList()
        } else {
            // FIX: Paginate linearly based on predictionIndex when resting!
            val linearPage = if (activeItems.isNotEmpty()) predictionIndex / engine.maxSectors else 0
            val start = linearPage * engine.maxSectors
            val end = kotlin.math.min(start + engine.maxSectors, activeItems.size)
            if (start < activeItems.size) activeItems.subList(start, end) else emptyList()
        }

        val activeColor = if (isRadialMenuOpen && radialDidMove) {
            "#FFA500" // Warning Orange
        } else if (state == RadialState.UTILITY) {
            "#FF6B6B" // Utility Red
        } else {
            "#D084FF" // Prediction Purple
        }

        // 5. Draw the Bar
        val valDisplay = if (isRadialMenuOpen) {
            val arrows = arrayOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")
            val editorInfo = currentInputEditorInfo
            val isEmulator = editorInfo == null || editorInfo.inputType == android.text.InputType.TYPE_NULL

            itemsToDraw.mapIndexed { index, word ->
                val isDisabled = isEmulator && state == RadialState.UTILITY && word in listOf("Copy", "Cut", "Select Word", "Select All")
                val textToDraw = if (isDisabled) "<font color='#444444'>$word</font>" else if (state == RadialState.SPECIAL_CHARS) "  $word  " else word
                val dir = if (engine.maxSectors == 8 && index < arrows.size) "${arrows[index]} " else ""
                
                if (index == engine.radialSelectedIndex && !isDisabled) {
                    "<b>[<font color='#555555'>$dir</font><font color='$activeColor'>$textToDraw</font>]</b>"
                } else {
                    "<font color='#555555'>$dir$textToDraw</font>"
                }
            }.joinToString("   ")
        } else {
            itemsToDraw.mapIndexed { index, word ->
                // Account for the linear page offset when highlighting!
                val adjustedIndex = index + (if (activeItems.isNotEmpty()) (predictionIndex / engine.maxSectors) * engine.maxSectors else 0)
                if (adjustedIndex == predictionIndex) "<b><font color='$activeColor'>[$word]</font></b>" 
                else "<font color='#777777'>$word</font>" 
            }.joinToString("   ")
        }

        tvPredictions.text = android.text.Html.fromHtml(valDisplay, android.text.Html.FROM_HTML_MODE_LEGACY)

        // 6. Pagination Badge (WITH OVERLAP FIX)
        val maxPages = kotlin.math.ceil(activeItems.size.toDouble() / engine.maxSectors).toInt().coerceAtLeast(1)
        if (isRadialMenuOpen && maxPages > 1) {
            tvPaginationBadge.text = "[${engine.radialPage + 1}/$maxPages]"
            tvPaginationBadge.visibility = View.VISIBLE
            tvModeBadge.visibility = View.GONE // THE FIX: Hide Mode Badge when paginating
        } else {
            tvPaginationBadge.visibility = View.GONE
            tvModeBadge.visibility = View.VISIBLE // Restore Mode Badge
        }

        // 7. Scroll Offset Fix
        val capturedSelectedIndex = engine.radialSelectedIndex
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
                    
                    if (actualIndex >= 0 && actualIndex <= layout.text.length) {
                        try {
                            val xOffset = layout.getPrimaryHorizontal(actualIndex).toInt()
                            hsvPredictions.smoothScrollTo(tvPredictions.left + xOffset - (hsvPredictions.width / 2), 0)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        } else {
            hsvPredictions.scrollTo(0, 0)
        }
    }

        /**
     * SINGLE SOURCE OF TRUTH FOR RESTING UI
     * Displays the resting dots and the inconspicuous Mode Badge.
     */
    private fun setRestingUI(isComposingEmpty: Boolean = false) {
        val baseText = if (isComposingEmpty) {
            "<b><font color='#A3FF00'>[...]</font></b>"
        } else {
            "..."
        }
        tvPredictions.text = android.text.Html.fromHtml(baseText, android.text.Html.FROM_HTML_MODE_LEGACY)
        
        // Hide pagination when resting
        tvPaginationBadge.visibility = View.GONE 

        // Restore the Mode Badge when we finish typing and return to rest
        if (::tvModeBadge.isInitialized) {
            tvModeBadge.visibility = View.VISIBLE
        }
    }

    private fun executeUtilityCommand(command: String) {
        val ic = currentInputConnection ?: return
        var shouldCollapse = true

        // THE FIX: Hook directly into Android's system clipboard
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager

        when (command) {
            "Cancel" -> { 
                // Do nothing. Will collapse and close below.
            }
            "Copy" -> {
                val selectedText = ic.getSelectedText(0)?.toString()
                if (!selectedText.isNullOrEmpty()) {
                    val clip = android.content.ClipData.newPlainText("JoyType", selectedText)
                    clipboard.setPrimaryClip(clip)
                }
            }
            "Cut" -> {
                val selectedText = ic.getSelectedText(0)?.toString()
                if (!selectedText.isNullOrEmpty()) {
                    val clip = android.content.ClipData.newPlainText("JoyType", selectedText)
                    clipboard.setPrimaryClip(clip)
                    ic.commitText("", 1) // Delete the selection natively
                }
                shouldCollapse = false // Let OS handle the new cursor position
            }
            "Paste" -> {
                if (clipboard.hasPrimaryClip()) {
                    val textToPaste = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                    if (textToPaste.isNotEmpty()) {
                        // THE FIX: Route through smartCommitText!
                        // In normal apps, it replaces the highlighted text instantly.
                        // In emulators, it literally types out the clipboard contents!
                        smartCommitText(textToPaste)
                    }
                }
                shouldCollapse = false
            }
            "Select Word" -> {
                val extracted = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
                if (extracted != null) {
                    val text = extracted.text.toString()
                    val cursor = extracted.selectionStart
                    
                    // Walk left and right to find word boundaries
                    var start = cursor
                    while (start > 0 && text[start - 1].isLetterOrDigit()) start--
                    var end = cursor
                    while (end < text.length && text[end].isLetterOrDigit()) end++
                    
                    if (start < end) {
                        ic.setSelection(start, end)
                        isHighlighting = true
                        highlightAnchorIndex = start
                        glideCursorIndex = end
                        return // Exit early so we don't collapse the selection!
                    }
                }
            }
            "Select All" -> {
                ic.performContextMenuAction(android.R.id.selectAll)
                isHighlighting = true 
                return // Exit early so we don't collapse the selection!
            }
        }
        
        // Turn off highlight mode after a command finishes
        isHighlighting = false
        if (shouldCollapse) {
            ic.setSelection(glideCursorIndex, glideCursorIndex) 
        }
        resetState()
    }

    private fun smartCommitText(textToCommit: String) {
        val ic = currentInputConnection ?: return
        val editorInfo = currentInputEditorInfo
        
        val requiresHardwareKeys = editorInfo == null || editorInfo.inputType == android.text.InputType.TYPE_NULL
        
        if (requiresHardwareKeys) {
            // FIX: Pure mathematical hardware spoofing
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                hardwareTypingMutex.withLock {
                    for (char in textToCommit) {
                        val keyCode = charToKeyCode(char)
                        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) continue
                        
                        val useShift = requiresShift(char)
                        
                        if (useShift) ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT))
                        
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                        kotlinx.coroutines.delay(10)
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                        
                        if (useShift) ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT))
                        
                        kotlinx.coroutines.delay(10) // Micro-buffer between distinct letters
                    }
                }
            }
        } else {
            // Normal Android App
            ic.commitText(textToCommit, 1)
        }
    }

    // For support in emulators
    private fun smartDelete(length: Int) {
        val ic = currentInputConnection ?: return
        val editorInfo = currentInputEditorInfo
        val requiresHardwareKeys = editorInfo == null || editorInfo.inputType == android.text.InputType.TYPE_NULL
        
        if (requiresHardwareKeys) {
            // Emulators: Spoof the physical backspace key!
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                for (i in 0 until length) {
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                    kotlinx.coroutines.delay(10)
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
                    kotlinx.coroutines.delay(10)
                }
            }
        } else {
            // If text is highlighted, deleteSurroundingText fails. Replace it natively
            val selectedText = ic.getSelectedText(0)
            if (!selectedText.isNullOrEmpty()) {
                ic.commitText("", 1)
            } else {
                // Normal Apps
                ic.deleteSurroundingText(length, 0)
            }
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

        val editorInfo = currentInputEditorInfo
        val requiresHardwareKeys = editorInfo == null || editorInfo.inputType == android.text.InputType.TYPE_NULL
        if (requiresHardwareKeys) {
            return word // Emulators have no context. Default to raw engine output
        }

        if (word.lowercase() == "i") return "I"
        
        val ic = currentInputConnection ?: return word
        
        val textBefore = ic.getTextBeforeCursor(3, 0)?.toString() ?: ""
        
        // Trim trailing spaces so it recognizes "Hello." and "Hello. " equally!
        val trimmedBefore = textBefore.trimEnd()
        val isStartOfSentence = textBefore.isEmpty() || 
                                trimmedBefore.endsWith(".") || 
                                trimmedBefore.endsWith("!") || 
                                trimmedBefore.endsWith("?") || 
                                textBefore.endsWith("\n")

        if (isStartOfSentence) {
            return word.replaceFirstChar { it.uppercase() }
        }

        // 3. Fallback to OS checking just in case (for weird text fields)
        if (editorInfo != null) {
            val capsMode = ic.getCursorCapsMode(editorInfo.inputType)
            if (capsMode > 0) {
                return word.replaceFirstChar { it.uppercase() }
            }
        }
        
        return word
    }

    private fun charToKeyCode(char: Char): Int {
        return when (char.lowercaseChar()) {
            in 'a'..'z' -> KeyEvent.KEYCODE_A + (char.lowercaseChar() - 'a')
            in '0'..'9' -> KeyEvent.KEYCODE_0 + (char - '0')
            ' ' -> KeyEvent.KEYCODE_SPACE
            '.' -> KeyEvent.KEYCODE_PERIOD
            ',' -> KeyEvent.KEYCODE_COMMA
            '?' -> KeyEvent.KEYCODE_SLASH 
            '!' -> KeyEvent.KEYCODE_1 
            '@' -> KeyEvent.KEYCODE_2 
            '-' -> KeyEvent.KEYCODE_MINUS
            '_' -> KeyEvent.KEYCODE_MINUS 
            ':' -> KeyEvent.KEYCODE_SEMICOLON 
            ';' -> KeyEvent.KEYCODE_SEMICOLON
            '\'' -> KeyEvent.KEYCODE_APOSTROPHE
            '"' -> KeyEvent.KEYCODE_APOSTROPHE 
            '(' -> KeyEvent.KEYCODE_9 
            ')' -> KeyEvent.KEYCODE_0 
            '/' -> KeyEvent.KEYCODE_SLASH
            '\\' -> KeyEvent.KEYCODE_BACKSLASH
            '&' -> KeyEvent.KEYCODE_7 
            '#' -> KeyEvent.KEYCODE_3 
            '%' -> KeyEvent.KEYCODE_5 
            '*' -> KeyEvent.KEYCODE_8 
            '+' -> KeyEvent.KEYCODE_EQUALS 
            '=' -> KeyEvent.KEYCODE_EQUALS
            '<' -> KeyEvent.KEYCODE_COMMA 
            '>' -> KeyEvent.KEYCODE_PERIOD 
            '$' -> KeyEvent.KEYCODE_4 
            '~' -> KeyEvent.KEYCODE_GRAVE 
            '`' -> KeyEvent.KEYCODE_GRAVE
            '{' -> KeyEvent.KEYCODE_LEFT_BRACKET 
            '}' -> KeyEvent.KEYCODE_RIGHT_BRACKET 
            '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
            ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
            '|' -> KeyEvent.KEYCODE_BACKSLASH 
            '^' -> KeyEvent.KEYCODE_6 
            '\n' -> KeyEvent.KEYCODE_ENTER
            else -> KeyEvent.KEYCODE_UNKNOWN
        }
    }

    private fun requiresShift(char: Char): Boolean {
        if (char.isUpperCase()) return true
        val shiftChars = listOf('?', '!', '@', '_', ':', '"', '(', ')', '&', '#', '%', '*', '+', '<', '>', '$', '~', '{', '}', '|', '^')
        return char in shiftChars
    }
}