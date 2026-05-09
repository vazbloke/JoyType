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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

class JoyTypeService : InputMethodService() {

    private lateinit var visualDebugView: VisualDebugView
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
    private var isSpecialCharMode = false
    private var radialSelectedIndex = 0

    // NEW: Pagination State
    private var radialPage = 0 
    private var radialLastOctant = -1
    
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

    enum class Action {
        ACCEPT, RECOMPOSE, BACKSPACE_WORD, BACKSPACE_STROKE, 
        ADD_SPACE, CLEAR_TEXT, UNDO, REDO, OPEN_SETTINGS, NONE, ENTER, 
        CLOSE_KEYBOARD, CURSOR_WORD_LEFT, CURSOR_WORD_RIGHT,
        CYCLE_FWD, CYCLE_BACK, TOGGLE_MODE, ADD_TO_DICT, TOGGLE_HIGHLIGHT
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
                InputMode.T9 -> {
                    tvModeBadge.text = "[T9]"
                    tvModeBadge.visibility = View.VISIBLE
                    if (::llBreadcrumbBar.isInitialized) llBreadcrumbBar.visibility = View.GONE
                }
                InputMode.ABC -> {
                    tvModeBadge.text = "[ABC]"
                    tvModeBadge.visibility = View.VISIBLE
                    if (::llBreadcrumbBar.isInitialized) llBreadcrumbBar.visibility = View.GONE
                }
                InputMode.MACRO -> {
                    tvModeBadge.text = "[MAC]"
                    tvModeBadge.visibility = View.VISIBLE
                    if (::llBreadcrumbBar.isInitialized) llBreadcrumbBar.visibility = View.VISIBLE
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
    private val UTILITY_ACTIONS = listOf("Cancel", "Copy", "Paste", "Cut", "Select All", "Toggle Case")
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
            
            if (abs(cursorX) > abs(cursorY)) {
                // HORIZONTAL MOVEMENT: Use mathematically pure IMS selection
                if (cursorX > 0) {
                    if (glideCursorIndex < glideTextLength) glideCursorIndex++
                } else {
                    if (glideCursorIndex > 0) glideCursorIndex--
                }
                // Force the cursor to our exact calculated index, bypassing hardware key listeners!
                // Fixed: Lock the text box state so Chrome can't hijack the cursor mid-move!
                ic.beginBatchEdit()

                if (isHighlighting && highlightAnchorIndex != -1) {
                    // Some apps silently reject backwards ranges. Always pass the smaller index first!
                    // Drag the selection box
                    ic.setSelection(highlightAnchorIndex, glideCursorIndex)
                } else {
                    ic.setSelection(glideCursorIndex, glideCursorIndex)
                }

                ic.endBatchEdit()
            } else {
                // VERTICAL MOVEMENT: We still must use DPAD here. 
                // Software keyboards cannot know where visual line breaks occur on the screen, 
                // so we have to rely on Android's native vertical text navigation.
                if (cursorY > 0) {
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
                } else {
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP))
                }
                
                // Re-sync our local tracker just in case the DPAD vertical move changed our index
                val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
                glideCursorIndex = extracted?.selectionStart ?: glideCursorIndex
            }
            
            haptics.tick() 
            cursorHandler.postDelayed(this, delay.coerceAtLeast(40L))
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
        m1KeyCode = prefs.getInt("key_mod_1", (DefaultBindings.MAP["key_mod_1"] as? Int) ?: -1)
        m2KeyCode = prefs.getInt("key_mod_2", (DefaultBindings.MAP["key_mod_2"] as? Int) ?: -1)
        m3KeyCode = prefs.getInt("key_mod_3", (DefaultBindings.MAP["key_mod_3"] as? Int) ?: -1)

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

        // Helper function to pair keys with their dropdown modifiers
        fun bind(action: Action, keyPref: String, modPref: String) {
            val defaultKey = (DefaultBindings.MAP[keyPref] as? Int) ?: -1
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
        bind(Action.RECOMPOSE, "key_recompose", "mod_cycle_prev")
        bind(Action.BACKSPACE_WORD, "key_backspace_word", "mod_backspace_word")
        bind(Action.BACKSPACE_STROKE, "key_backspace_stroke", "mod_backspace_stroke")
        bind(Action.ADD_SPACE, "key_add_space", "mod_add_space")
        bind(Action.CLEAR_TEXT, "key_clear_text", "mod_clear_text")
        bind(Action.ENTER, "key_enter", "mod_enter")
        bind(Action.UNDO, "key_undo", "mod_undo")
        bind(Action.REDO, "key_redo", "mod_redo")
        bind(Action.CLOSE_KEYBOARD, "key_close", "mod_close")
        bind(Action.OPEN_SETTINGS, "key_open_settings", "mod_open_settings")
        bind(Action.CURSOR_WORD_LEFT, "key_word_left", "mod_word_left")
        bind(Action.CURSOR_WORD_RIGHT, "key_word_right", "mod_word_right")
        bind(Action.CYCLE_FWD, "key_cycle_fwd", "mod_cycle_fwd")
        bind(Action.CYCLE_BACK, "key_cycle_back", "mod_cycle_back")
        bind(Action.TOGGLE_MODE, "key_toggle_mode", "mod_toggle_mode")
        bind(Action.ADD_TO_DICT, "key_add_to_dict", "mod_add_to_dict")
        // # TODO: Why bind to none below?
        bind(Action.TOGGLE_HIGHLIGHT, "key_toggle_highlight", "NONE") // Hardcode "NONE" since KeyBindingPreference handles the combo
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

        // Force the initial reactive draws on boot!
        updateModeBadgeUI()
        updateSelectionBadgeUI()
        
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
                    var angle = atan2(y.toDouble(), x.toDouble()) 
                    angle += Math.PI / 2.0 
                    if (angle < 0) angle += 2 * Math.PI

                    val octant = Math.round(angle / (Math.PI / 4.0)).toInt() % 8
                    val maxPages = if (isSpecialCharMode) {
                        kotlin.math.ceil(SPECIAL_CHARS.size / 8.0).toInt()
                    } else {
                        kotlin.math.ceil(currentPredictions.size / 8.0).toInt().coerceAtLeast(1)
                    }

                    if (radialLastOctant != -1 && octant != radialLastOctant) {
                        
                        // THE FIX: Calculate physical rotation direction
                        var delta = octant - radialLastOctant
                        if (delta > 4) delta -= 8
                        if (delta < -4) delta += 8
                        
                        val isMovingForward = delta > 0 // Clockwise
                        val isMovingBackward = delta < 0 // Counter-Clockwise
                        var justPegged = false

                        // Clockwise crossover (7 to 0)
                        if (radialLastOctant == 7 && octant == 0) {
                            if (isPeggedAtStart) {
                                isPeggedAtStart = false 
                                haptics.tick() // Tick on unpeg (7->0 retreat!)
                            } else if (radialPage < maxPages - 1) {
                                radialPage++
                            } else if (!isPeggedAtEnd) {
                                isPeggedAtEnd = true
                                justPegged = true
                                haptics.thud() // Initial THUNK!
                            }
                        }
                        // Counter-Clockwise crossover (0 to 7)
                        else if (radialLastOctant == 0 && octant == 7) {
                            if (isPeggedAtEnd) {
                                isPeggedAtEnd = false 
                                haptics.tick() // Tick on unpeg (0->7 retreat!)
                            } else if (radialPage > 0) {
                                radialPage--
                            } else if (!isPeggedAtStart) {
                                isPeggedAtStart = true
                                justPegged = true
                                haptics.thud() // Initial THUNK!
                            }
                        }

                        // THE DIRECTIONAL GRINDING GEAR:
                        if (!justPegged) {
                            if (isPeggedAtStart) {
                                // If we are pegged at 0, moving backward (7, 6, 5) thuds. Moving forward (5, 6, 7) ticks!
                                if (isMovingBackward) haptics.thud() else haptics.tick()
                            } else if (isPeggedAtEnd) {
                                // If we are pegged at Max, moving forward thuds. Moving backward ticks!
                                if (isMovingForward) haptics.thud() else haptics.tick()
                            }
                        }
                    }

                    radialLastOctant = octant

                    val currentItems = if (isSpecialCharMode) {
                        val start = radialPage * 8
                        val end = kotlin.math.min(start + 8, SPECIAL_CHARS.size)
                        if (start < SPECIAL_CHARS.size) SPECIAL_CHARS.subList(start, end) else emptyList()
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
                            // Only tick for normal valid scrolling (unpegged)
                            if (!isPeggedAtStart && !isPeggedAtEnd) {
                                haptics.tick()
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

    private fun handleStrokeInput(rawX: Float, rawY: Float, mag: Float) {
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
                        val winningDigit = digitMap.maxByOrNull { it.value }?.key ?: '5'
                        
                        val baseChars = t9Engine.getCharsForDigit(winningDigit)
                        val chars = mutableListOf<String>()
                        
                        // 1. Add lowercase characters
                        chars.addAll(baseChars.map { it.toString() })
                        // 2. Add uppercase characters
                        chars.addAll(baseChars.map { it.uppercaseChar().toString() })
                        // 3. Add the number
                        chars.add(winningDigit.toString())
                        
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
            isSpecialCharMode = currentPredictions.isEmpty()
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

                // Cleanup the animation state
                tvPredictions.animate().cancel()
                tvPredictions.translationY = 0f

                val ic = currentInputConnection
                
                if (isSpecialCharMode) {
                    val actualIndex = (radialPage * 8) + radialSelectedIndex
                    if (actualIndex < SPECIAL_CHARS.size) {
                        saveUndoSnapshot()
                        
                        val charToCommit = SPECIAL_CHARS[actualIndex]
                        
                        // THE FIX: Smart Punctuation (Cling to left word)
                        val clingyPunctuation = listOf(".", ",", "?", "!", ":", ";", ")", "]", "}")
                        
                        if (clingyPunctuation.contains(charToCommit)) {
                            ic?.beginBatchEdit()
                            val textBefore = ic?.getTextBeforeCursor(1, 0)?.toString() ?: ""
                            
                            // If there is an auto-space in the way, eat it!
                            if (textBefore == " ") {
                                ic?.deleteSurroundingText(1, 0) 
                            }
                            
                            ic?.commitText(charToCommit, 1)

                            // Re-apply the space on the right side ONLY if autoSpace is on AND we are in T9 Mode!
                            if (autoSpace && currentMode == InputMode.T9) { 
                                ic?.commitText(" ", 1)
                            }

                            ic?.endBatchEdit()
                        } else {
                            // Normal characters (like @ or /) just commit exactly where they are
                            ic?.commitText(charToCommit, 1)
                        }
                    }
                } else if (currentPredictions.isNotEmpty()) {
                    saveUndoSnapshot()
                    val actualIndex = (radialPage * 8) + radialSelectedIndex
                    
                    if (actualIndex < currentPredictions.size) {
                        if (currentPredictions == UTILITY_ACTIONS) {
                            executeUtilityCommand(currentPredictions[actualIndex])
                        } else if (currentMode == InputMode.T9) {
                            // --- T9 MODE ---
                            val wordToCommit = getCapitalizedWord(currentPredictions[actualIndex])
                            ic?.commitText(wordToCommit, 1)
                            if (autoSpace) ic?.commitText(" ", 1)
                            lastAcceptTime = System.currentTimeMillis()
                        } else {
                            // --- MANUAL MODE ---
                            // Absolute raw control. No capitalization, no spaces.
                            ic?.commitText(currentPredictions[actualIndex], 1)
                        }
                    }
                    resetState() 
                }
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

        return super.onKeyUp(keyCode, event)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)

        // 1. FIX THE STUCK CURSOR (Bug 1):
        // Only let the OS dictate our internal cursor position if the user IS NOT actively holding the joystick.
        if (!isCursorGliding) {
            if (isHighlighting && highlightAnchorIndex != -1) {
                // If we are highlighting, figure out which end is the moving head
                glideCursorIndex = if (highlightAnchorIndex == newSelStart) newSelEnd else newSelStart
            } else {
                glideCursorIndex = newSelEnd
            }
        }

        // 2. FIX THE CROSSOVER CANCELLATION (Bug 2):
        val isTextSelected = newSelStart != newSelEnd

        if (isTextSelected) {
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

                // --- UTILITY INTERCEPT ---
                if (currentPredictions == UTILITY_ACTIONS) {
                    executeUtilityCommand(currentPredictions[predictionIndex])
                    return
                }

                // --- MACRO INTERCEPT (V1) ---
                if (currentMode == InputMode.MACRO) {
                    val targetIndex = if (isRadialMenuOpen) radialSelectedIndex else predictionIndex

                    if (macroLibrary.isNotEmpty() && targetIndex < macroLibrary.size) {
                        val selectedMacro = macroLibrary[targetIndex]
                        val ic = currentInputConnection ?: return
                        
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            for (keyCode in selectedMacro.sequence) {
                                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                                kotlinx.coroutines.delay(15) 
                                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                                kotlinx.coroutines.delay(30)
                            }
                        }
                        
                        // Collapse the radial menu visually if it was open
                        isRadialMenuOpen = false
                        updateUI() 
                    }
                    return // Exit ACCEPT early so we don't accidentally commit text!
                }

                val now = System.currentTimeMillis()
                val ic = currentInputConnection ?: return

                if (currentPredictions.isNotEmpty()) {
                    if (currentMode == InputMode.T9) {
                        // --- T9 MODE ---
                        var wordToCommit = getCapitalizedWord(currentPredictions[predictionIndex])

                        // If typing right up against punctuation, inject a space first.
                        if (autoSpace) {
                            val textBefore = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
                            val requiresPreSpace = listOf(".", ",", "?", "!", ":", ";", ")", "]", "}").contains(textBefore)
                            if (requiresPreSpace) {
                                wordToCommit = " $wordToCommit"
                            }
                        }

                        ic.commitText(wordToCommit, 1)

                        // Smart Auto-Space checks if a space or special character is already there!
                        if (autoSpace) {
                            val textAfter = ic.getTextAfterCursor(1, 0)?.toString() ?: ""
                            if (!textAfter.startsWith(" ") && !textAfter.startsWith(".") && !textAfter.startsWith(",")) {
                                ic.commitText(" ", 1)
                            }
                        }
                    } else {
                        // --- MANUAL MODE ---
                        // Absolute raw control. No capitalization, no spaces.
                        ic.commitText(currentPredictions[predictionIndex], 1)
                    }
                    
                    val wasT9 = currentMode == InputMode.T9
                    resetState()
                    
                    // Set the timestamp AFTER resetState() to guarantee it isn't accidentally cleared!
                    if (wasT9) {
                        lastAcceptTime = now 
                    }
                } else {
                    // --- RESTING STATE (No active strokes) ---
                    // Double Accept Period ONLY triggers in T9 Mode
                    if (currentMode == InputMode.T9 && doubleAcceptPeriod && (now - lastAcceptTime < 500)) {
                        
                        // THE FIX: Wrap in a Batch Edit so the deletion and period happen atomically!
                        ic.beginBatchEdit()
                        val textBefore = ic.getTextBeforeCursor(10, 0)?.toString() ?: ""
                        val spacesMatch = Regex("\\s+$").find(textBefore) 
                        if (spacesMatch != null) {
                            ic.deleteSurroundingText(spacesMatch.value.length, 0)
                        }
                        ic.commitText(". ", 1)
                        ic.endBatchEdit()
                        
                        lastAcceptTime = 0L // Reset so a 3rd tap doesn't add another period
                    } else {
                        // Do NOT insert a phantom space!
                        
                        // THE CORE FIX: Even if we do nothing visually, we MUST record 
                        // this tap's timestamp so the next tap knows it was a double-tap!
                        if (currentMode == InputMode.T9) {
                            lastAcceptTime = now
                        }
                    }
                }
            }
            Action.BACKSPACE_WORD -> {
                fireActionHaptic()

                // If text is highlighted, just delete the selection
                if (currentPredictions == UTILITY_ACTIONS) {
                    saveUndoSnapshot()
                    ic.commitText("", 1)
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
                        if (deleteLen > 0) ic.deleteSurroundingText(deleteLen, 0)
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

                // If text is highlighted, space deletes it and adds a space
                if (currentPredictions == UTILITY_ACTIONS) {
                    ic.commitText(" ", 1)
                    resetState()
                    return
                }

                if (currentPredictions.isNotEmpty()) {
                    if (currentMode == InputMode.T9) {
                        val wordToCommit = getCapitalizedWord(currentPredictions[predictionIndex])
                        ic.commitText(wordToCommit, 1)
                        if (autoSpace) ic.commitText(" ", 1)
                        lastAcceptTime = System.currentTimeMillis()
                    } else {
                        // In Manual mode, just commit the raw char (and NO auto-space!)
                        ic.commitText(currentPredictions[predictionIndex], 1)
                    }
                    resetState()
                } else {
                    ic.commitText(" ", 1)
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

                // If text is highlighted, delete the entire selection
                if (currentPredictions == UTILITY_ACTIONS) {
                    saveUndoSnapshot()
                    ic.commitText("", 1)
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
                    ic.deleteSurroundingText(1, 0)
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
                if (currentPredictions.isNotEmpty()) {
                    fireActionHaptic()
                    predictionIndex = (predictionIndex + 1) % currentPredictions.size
                    updateUI()
                }
            }
            Action.CYCLE_BACK -> {
                if (currentPredictions.isNotEmpty()) {
                    fireActionHaptic()
                    // Add currentPredictions.size to prevent negative modulo results!
                    predictionIndex = (predictionIndex - 1 + currentPredictions.size) % currentPredictions.size
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
                } else {
                    highlightAnchorIndex = -1
                    // Collapse selection to current cursor to cancel
                    ic.setSelection(glideCursorIndex, glideCursorIndex) 
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
        currentPredictions = emptyList()
        predictionIndex = 0
        visualDebugView.clear()
        setRestingUI()  
        isRadialMenuOpen = false
        isSpecialCharMode = false
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

        isHighlighting = false
        highlightAnchorIndex = -1
    }

    private fun updateUI() {
        // --- MACRO OVERRIDE (V1 Flat List) ---
        if (currentMode == InputMode.MACRO) {

            // Safety check before touching the UI element!
            if (::tvBreadcrumb.isInitialized) {
                tvBreadcrumb.text = "MACROS" 
            }
            
            // Draw the flat list exactly like normal predictions!
            val display = if (isRadialMenuOpen) {
                val arrows = arrayOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")
                macroLibrary.mapIndexed { index, macro ->
                    val dir = if (index < arrows.size) "${arrows[index]} " else ""
                    if (index == radialSelectedIndex) "<b>[<font color='#555555'>$dir</font><font color='#42A5F5'>${macro.name}</font><font color='#555555'>]</font></b>" 
                    else "<font color='#555555'>$dir${macro.name}</font>"
                }.joinToString("   ")
            } else {
                macroLibrary.mapIndexed { index, macro ->
                    if (index == predictionIndex) "<b><font color='#42A5F5'>[${macro.name}]</font></b>" 
                    else "<font color='#777777'>${macro.name}</font>" 
                }.joinToString("   ")
            }
            
            tvPredictions.text = android.text.Html.fromHtml(display, android.text.Html.FROM_HTML_MODE_LEGACY)
            return // IMPORTANT: Exit early so standard T9 UI logic doesn't run!
        }

        if (currentPredictions.isEmpty() && !isRadialMenuOpen) {
            setRestingUI(isComposingEmpty = wordProbabilities.isNotEmpty())
            return
        }

        // If the code makes it past the 'return' above, it means the user 
        // IS actively typing or has the radial menu open. Hide the badge
        tvModeBadge.visibility = View.GONE
        
        // 1. Slice lists: Standard mode now ALSO caps at 8 words
        val itemsToDraw = if (isRadialMenuOpen) {
            if (isSpecialCharMode) {
                val start = radialPage * 8
                val end = kotlin.math.min(start + 8, SPECIAL_CHARS.size)
                if (start < SPECIAL_CHARS.size) SPECIAL_CHARS.subList(start, end) else emptyList()
            } else {
                val start = radialPage * 8
                val end = kotlin.math.min(start + 8, currentPredictions.size)
                if (start < currentPredictions.size) currentPredictions.subList(start, end) else emptyList()
            }
        } else {
            currentPredictions.take(8)
        }

        // 2. Format the text
        val isUtilityMode = currentPredictions == UTILITY_ACTIONS
        val activeColor = if (isUtilityMode) "#FF6B6B" else "#D084FF" // Soft Red vs Soft Purple

        val valDisplay = if (isRadialMenuOpen) {
            val arrows = arrayOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")
            itemsToDraw.mapIndexed { index, word ->
                val textToDraw = if (isSpecialCharMode) "  $word  " else word
                val dir = if (index < arrows.size) "${arrows[index]} " else ""
                
                if (index == radialSelectedIndex) {
                    "<b>[<font color='#555555'>$dir</font><font color='$activeColor'>$textToDraw</font><font color='#555555'>]</font></b>" 
                } else {
                    "<font color='#555555'>$dir$textToDraw</font>"
                }
            }.joinToString("   ")
        } else {
            itemsToDraw.mapIndexed { index, word ->
                if (index == predictionIndex) "<b><font color='$activeColor'>[$word]</font></b>" 
                else "<font color='#777777'>$word</font>" 
            }.joinToString("   ")
        }

        tvPredictions.text = android.text.Html.fromHtml(valDisplay, android.text.Html.FROM_HTML_MODE_LEGACY)

        // We are typing, so hide the Mode Badge
        tvModeBadge.visibility = View.GONE

        // 3. Calculate and show the Pagination Badge if needed
        if (isRadialMenuOpen) {
            val maxPages = if (isSpecialCharMode) {
                kotlin.math.ceil(SPECIAL_CHARS.size / 8.0).toInt()
            } else {
                kotlin.math.ceil(currentPredictions.size / 8.0).toInt().coerceAtLeast(1)
            }
            
            if (maxPages > 1) {
                tvPaginationBadge.text = "[${radialPage + 1}/$maxPages]"
                tvPaginationBadge.visibility = View.VISIBLE
            } else {
                tvPaginationBadge.visibility = View.GONE
            }
        } else {
            tvPaginationBadge.visibility = View.GONE
        }

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
        val editorInfo = currentInputEditorInfo
        if (editorInfo != null) {
            val capsMode = ic.getCursorCapsMode(editorInfo.inputType)
            if (capsMode > 0) {
                return word.replaceFirstChar { it.uppercase() }
            }
        }
        
        return word
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
        
        // Hide pagination when resting!
        tvPaginationBadge.visibility = View.GONE 
    }

    private fun executeUtilityCommand(command: String) {
        val ic = currentInputConnection ?: return
        when (command) {
            "Cancel" -> { 
                // Do nothing to the text/clipboard. Just fall through to cleanup.
            }
            "Copy" -> ic.performContextMenuAction(android.R.id.copy)
            "Paste" -> ic.performContextMenuAction(android.R.id.paste)
            "Cut" -> ic.performContextMenuAction(android.R.id.cut)
            "Select All" -> ic.performContextMenuAction(android.R.id.selectAll)
            "Toggle Case" -> {
                val selectedText = ic.getSelectedText(0)?.toString() ?: ""
                if (selectedText.isNotEmpty()) {
                    val newText = if (selectedText == selectedText.uppercase()) {
                        selectedText.lowercase()
                    } else {
                        selectedText.uppercase()
                    }
                    ic.commitText(newText, 1) // This replaces the selection automatically
                }
            }
        }
        
        // Turn off highlight mode after a command
        isHighlighting = false
        val newCursorPos = ic.getExtractedText(ExtractedTextRequest(), 0)?.selectionEnd ?: glideCursorIndex
        ic.setSelection(newCursorPos, newCursorPos) 
        resetState()
    }   
}