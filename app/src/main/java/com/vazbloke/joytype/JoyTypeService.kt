package com.vazbloke.joytype

import android.graphics.PointF
import android.view.inputmethod.ExtractedTextRequest


import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt


import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class JoyTypeService : InputMethodService() {

    private lateinit var prefs: SharedPreferences
    private lateinit var transmitter: AndroidImeTransmitter
    private lateinit var controller: JoyTypeController

    // --- MACRO STATE ---
    private var macroLibrary: List<MacroRepository.Macro> = emptyList()
    private var lastPastedClipboardText: String? = null

    // UI Elements
    private lateinit var visualDebugView: VisualDebugView
    private lateinit var tvPredictions: TextView
    private lateinit var tvModeBadge: TextView
    private lateinit var tvPaginationBadge: TextView
    private lateinit var tvSelectionBadge: TextView
    private lateinit var hsvPredictions: android.widget.HorizontalScrollView
    private lateinit var llBreadcrumbBar: View
    private lateinit var tvBreadcrumb: TextView

    // Cursor Gliding (Requires MainThread Looper)
    private var isCursorGliding = false
    private var cursorX = 0f
    private var cursorY = 0f
    private var cursorMag = 0f
    private val cursorHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val cursorGlideRunnable = object : Runnable {
        override fun run() {
            if (!isCursorGliding) return
            val delay = 200L - (cursorMag * 160L).toLong()
            val isHorizontal = kotlin.math.abs(cursorX) > kotlin.math.abs(cursorY)
            
            if (transmitter.isHardwareSpoofingRequired) {
                val code = if (isHorizontal) {
                    if (cursorX > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                } else {
                    if (cursorY > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                }
                transmitter.sendKeyPress(code, requiresShift = controller.isHighlighting)
            } else {
                val state = transmitter.getEditorState()
                if (state != null) {
                    val textLen = state.text.length
                    if (isHorizontal) {
                        if (cursorX > 0) { if (controller.glideCursorIndex < textLen) controller.glideCursorIndex++ } 
                        else { if (controller.glideCursorIndex > 0) controller.glideCursorIndex-- }
                        
                        transmitter.beginBatchEdit()
                        if (controller.isHighlighting && controller.highlightAnchorIndex != -1) {
                            transmitter.setSelection(controller.highlightAnchorIndex, controller.glideCursorIndex)
                        } else {
                            transmitter.setSelection(controller.glideCursorIndex, controller.glideCursorIndex)
                        }
                        transmitter.endBatchEdit()
                    } else {
                        val code = if (cursorY > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                        transmitter.sendKeyPress(code, requiresShift = controller.isHighlighting)
                        
                        // Re-sync after vertical jump
                        val newState = transmitter.getEditorState()
                        controller.glideCursorIndex = newState?.selectionEnd ?: controller.glideCursorIndex
                    }
                }
            }
            // Trigger tick directly from the HapticManager
            // haptics.tick() 
            cursorHandler.postDelayed(this, delay.coerceAtLeast(40L))
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val haptics = HapticManager(this)
        
        transmitter = AndroidImeTransmitter(this)
        
        controller = JoyTypeController(
            context = this,
            prefs = prefs,
            transmitter = transmitter,
            haptics = haptics,
            onUpdateUI = { updateUI() },
            onUpdateDebugUI = { 
                if (::visualDebugView.isInitialized) {
                    visualDebugView.updateJoyT9Debug(
                        controller.t9Engine.currentStrokePath, 
                        controller.registeredDebugPeaks, 
                        controller.t9Engine.wordProbabilities, 
                        controller.lastDetectionType
                    )
                }
            },
            onHideKeyboard = { requestHideSelf(0) }
        )

        controller.loadSettings()

        CoroutineScope(Dispatchers.IO).launch {
            controller.t9Engine.loadDictionary(this@JoyTypeService)
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        controller.loadSettings()
        
        // THE FIX: Reload the macro library every time the keyboard opens 
        // so it reflects any changes made in MacroManagerActivity!
        try {
            macroLibrary = MacroRepository.loadMacros(prefs)
        } catch (e: Exception) {
            macroLibrary = emptyList()
            e.printStackTrace()
        }
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        tvPredictions = view.findViewById(R.id.tv_predictions)
        tvModeBadge = view.findViewById(R.id.tv_mode_badge)
        tvSelectionBadge = view.findViewById(R.id.tv_selection_badge)
        tvPaginationBadge = view.findViewById(R.id.tv_pagination_badge)
        visualDebugView = view.findViewById(R.id.swipe_debug_view)
        hsvPredictions = view.findViewById(R.id.hsv_predictions)
        llBreadcrumbBar = view.findViewById(R.id.ll_breadcrumb_bar)
        tvBreadcrumb = view.findViewById(R.id.tv_breadcrumb)

        visualDebugView.visibility = if (prefs.getBoolean("visual_debug_mode", false)) View.VISIBLE else View.GONE
        controller.resetState()

        tvPredictions.setOnClickListener {
            if (controller.t9Engine.currentPredictions.isEmpty() && !controller.isRadialSelectorActive) {
                android.widget.Toast.makeText(this, "Flick joystick to start typing", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        return view
    }

    // --- STATE & REPEAT TRACKERS ---
    private var radialDidMove = false
    private var cursorDidMove = false
    private var glideTextLength = 0

    private var repeatingAction: Action? = null
    private val repeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val repeatDelay = 400L
    private val repeatRunnable = object : Runnable {
        override fun run() {
            repeatingAction?.let {
                controller.executeAction(it, isRepeat = true)
                repeatHandler.postDelayed(this, 50L) 
            }
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!isInputViewShown) return super.onGenericMotionEvent(event)

        if (!(event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD))
            return super.onGenericMotionEvent(event)

        val rawX = event.getAxisValue(MotionEvent.AXIS_X)
        val rawY = event.getAxisValue(MotionEvent.AXIS_Y)
        val magL = kotlin.math.sqrt(rawX * rawX + rawY * rawY)
        
        val rawZ = event.getAxisValue(MotionEvent.AXIS_Z)
        val rawRZ = event.getAxisValue(MotionEvent.AXIS_RZ)
        val magR = kotlin.math.sqrt(rawZ * rawZ + rawRZ * rawRZ)

        val useRightStick = magR > magL
        val x = if (useRightStick) rawZ else rawX
        val y = if (useRightStick) rawRZ else rawY
        val mag = if (useRightStick) magR else magL

        // --- RADIAL UI JOYSTICK INTERCEPT ---
        if (controller.isRadialSelectorActive) {
            if (mag > 0.3f) {
                val justWokeUp = !radialDidMove
                radialDidMove = true
                if (justWokeUp) updateUI()
            }

            val disabledIndices = if (transmitter.isHardwareSpoofingRequired && controller.activeRadialEngine == controller.utilityRadialEngine) {
                setOf(1, 3, 4, 5) 
            } else emptySet()
            
            controller.activeRadialEngine.updateInput(x, y, mag, disabledIndices)
            return true
        }

        // --- CURSOR MODIFIER INTERCEPT ---
        controller.syncModifiers()

        if (controller.isCursorModifierHeld) {
            if (mag > 0.2f) { 
                cursorX = x
                cursorY = y
                cursorMag = mag

                // --- CURSOR MENU HIGHLIGHT ---
                cursorDidMove = true
                val newIndex = if (x < 0) 0 else 1
                if (controller.cursorRadialEngine.absoluteIndex != newIndex) {
                    controller.cursorRadialEngine.setAbsoluteIndex(newIndex)
                    updateUI()
                }

                if (!isCursorGliding) {
                    isCursorGliding = true
                    val state = transmitter.getEditorState()
                    val currentSelectionStart = state?.selectionStart ?: 0
                    val currentSelectionEnd = state?.selectionEnd ?: 0
                    glideTextLength = state?.text?.length ?: 0

                    if (controller.isHighlighting && controller.highlightAnchorIndex != -1) {
                        controller.glideCursorIndex = if (controller.highlightAnchorIndex == currentSelectionStart) currentSelectionEnd else currentSelectionStart
                    } else {
                        controller.glideCursorIndex = currentSelectionStart
                    }
                    cursorHandler.post(cursorGlideRunnable) 
                }
            } else {
                isCursorGliding = false 
            }
            return true
        } else {
            // THE NEW RELEASE LOGIC
            if (isCursorGliding) {
                isCursorGliding = false
                if (controller.isHighlighting && controller.highlightAnchorIndex == controller.glideCursorIndex) {
                    if (controller.activeRadialEngine == controller.utilityRadialEngine) {
                        controller.t9Engine.currentPredictions = emptyList()
                    }
                    controller.isHighlighting = false
                    controller.highlightAnchorIndex = -1
                    updateUI()
                }
            }
        }

        // --- NORMAL T9 TYPING ---
        controller.handleStrokeInput(x, y, mag)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isInputViewShown) return super.onKeyDown(keyCode, event)

        val isDPad = keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)

        if (event.repeatCount > 0 && !isDPad) return true

        if (keyCode == controller.m1KeyCode) controller.isM1Held = true
        if (keyCode == controller.m2KeyCode) controller.isM2Held = true

        controller.syncModifiers()

        val targetRadialKey = when (controller.radialModifier) {
            ModifierKey.M1 -> controller.m1KeyCode
            ModifierKey.M2 -> controller.m2KeyCode
            else -> -1
        }

        if (targetRadialKey != -1 && keyCode == targetRadialKey) {
            controller.isRadialSelectorActive = true
            radialDidMove = false

            if (controller.currentMode == InputMode.PRE && controller.t9Engine.wordProbabilities.isEmpty()) {
                controller.predictiveRadialEngine.candidates = controller.SPECIAL_CHARS
            }

            controller.activeRadialEngine.reset()
            controller.activeRadialEngine.setAbsoluteIndex(0)
            
            tvPredictions.animate().cancel() 
            tvPredictions.alpha = 0f
            tvPredictions.translationY = 30f 
            tvPredictions.animate().alpha(1f).translationY(0f).setDuration(200).start()
            
            updateUI()
            return true
        }

        val targetCursorKey = when (controller.cursorModifier) {
            ModifierKey.M1 -> controller.m1KeyCode
            ModifierKey.M2 -> controller.m2KeyCode
            else -> -1
        }

        if (targetCursorKey != -1 && keyCode == targetCursorKey) {
            controller.isCursorMenuOpen = true
            cursorDidMove = false
            controller.cursorRadialEngine.setAbsoluteIndex(1)
            updateUI()
        }

        val currentMod = if (controller.isM1Held) ModifierKey.M1 else if (controller.isM2Held) ModifierKey.M2 else ModifierKey.NONE
        val action = controller.keyBindings[KeyCombo(keyCode, currentMod)]

        if (action != null && action != Action.NONE) {
            if (controller.isRadialSelectorActive) {
                controller.isRadialSelectorActive = false
                if (controller.activeRadialEngine == controller.utilityRadialEngine) {
                    controller.isHighlighting = false
                    controller.highlightAnchorIndex = -1
                }
                updateUI()
            }

            controller.executeAction(action)

            if (action != Action.CLOSE_KEYBOARD && action != Action.OPEN_SETTINGS) {
                repeatingAction = action
                repeatHandler.postDelayed(repeatRunnable, repeatDelay)
            }
            return true
        }

        if (isDPad) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                val state = transmitter.getEditorState()
                var cursorIndex = state?.selectionStart ?: 0
                val textLen = state?.text?.length ?: 0

                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && cursorIndex > 0) cursorIndex--
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && cursorIndex < textLen) cursorIndex++

                transmitter.beginBatchEdit()
                transmitter.setSelection(cursorIndex, cursorIndex)
                transmitter.endBatchEdit()
            } else {
                transmitter.sendKeyPress(keyCode)
            }
            return true 
        }

        if (KeyEvent.isGamepadButton(keyCode)) return true

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        repeatingAction = null
        repeatHandler.removeCallbacks(repeatRunnable)

        if (keyCode == controller.m1KeyCode) controller.isM1Held = false
        if (keyCode == controller.m2KeyCode) controller.isM2Held = false

        controller.syncModifiers()

        val targetCursorKey = when (controller.cursorModifier) {
            ModifierKey.M1 -> controller.m1KeyCode
            ModifierKey.M2 -> controller.m2KeyCode
            else -> -1
        }
        
        if (targetCursorKey != -1 && keyCode == targetCursorKey) {
            if (controller.isCursorMenuOpen) {
                controller.isCursorMenuOpen = false
                updateUI() 
            }
        }

        val targetRadialKey = when (controller.radialModifier) {
            ModifierKey.M1 -> controller.m1KeyCode
            ModifierKey.M2 -> controller.m2KeyCode
            else -> -1
        }
        
        if (keyCode == targetRadialKey) {
            if (controller.isRadialSelectorActive) {
                controller.isRadialSelectorActive = false
                tvPredictions.animate().cancel()
                tvPredictions.translationY = 0f

                if (controller.commitOnRelease) {
                    // Route Macros manually since the Controller doesn't have Android Clipboard Context
                    if (controller.activeRadialEngine == controller.macroRadialEngine) {
                        handleMacroSelection(controller.macroRadialEngine.absoluteIndex)
                    } else {
                        controller.commitCurrentSelection()
                    }
                } else {
                    controller.resetState() 
                }
                updateUI()
            }
            return true
        }

        val isDPad = keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
        if (isDPad) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            }
            return true
        }

        if (KeyEvent.isGamepadButton(keyCode)) return true

        return super.onKeyUp(keyCode, event)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)

        if (!isCursorGliding) {
            if (controller.isHighlighting && controller.highlightAnchorIndex != -1) {
                controller.glideCursorIndex = if (controller.highlightAnchorIndex == newSelStart) newSelEnd else newSelStart
            } else {
                controller.glideCursorIndex = newSelEnd
            }
        }

        val isTextSelected = newSelStart != newSelEnd

        if (isTextSelected || controller.isHighlighting) {
            if (controller.activeRadialEngine == controller.utilityRadialEngine) {
                controller.utilityRadialEngine.setAbsoluteIndex(0)
            }
            updateUI()
        } else {
            if (!isCursorGliding && controller.activeRadialEngine == controller.utilityRadialEngine) {
                controller.isHighlighting = false
                controller.highlightAnchorIndex = -1
                updateUI()
            }
        }
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val ic = currentInputConnection ?: return@postDelayed
            ic.beginBatchEdit()
            ic.commitText("", 1)
            ic.endBatchEdit()
        }, 200L) 

        updateUI()
    }

    private fun updateUI() {
        if (!::controller.isInitialized) return

        if (controller.currentMode == InputMode.MACRO && ::tvBreadcrumb.isInitialized) {
            tvBreadcrumb.text = "MACROS"
        }

        val engine = controller.activeRadialEngine

        // Lazy-load Macro candidates so JoyTypeController doesn't need context
        if (controller.currentMode == InputMode.MACRO && engine.candidates.isEmpty()) {
            loadMacroCandidates()
        }

        if (engine.candidates.isEmpty() && !controller.isRadialSelectorActive && controller.currentMode != InputMode.MACRO) {
            setRestingUI(isComposingEmpty = controller.t9Engine.wordProbabilities.isNotEmpty())
            return
        }

        tvModeBadge.visibility = View.VISIBLE
        when (controller.currentMode) {
            InputMode.PRE -> animateSlotMachineBadge("[T9]", R.color.joy_green)
            InputMode.ABC -> animateSlotMachineBadge("[ABC]", R.color.joy_blue)
            InputMode.MACRO -> animateSlotMachineBadge("[MAC]", R.color.joy_yellow)
        }

        val activeItems = engine.candidates

        val itemsToDraw = if (controller.isRadialSelectorActive) {
            val start = engine.radialPage * engine.maxSectors
            val end = kotlin.math.min(start + engine.maxSectors, activeItems.size)
            if (start < activeItems.size) activeItems.subList(start, end) else emptyList()
        } else {
            val linearPage = if (activeItems.isNotEmpty()) engine.absoluteIndex / engine.maxSectors else 0
            val start = linearPage * engine.maxSectors
            val end = kotlin.math.min(start + engine.maxSectors, activeItems.size)
            if (start < activeItems.size) activeItems.subList(start, end) else emptyList()
        }

        val activeColor = if (controller.isRadialSelectorActive || controller.isCursorMenuOpen) {
            controller.hexColors.legacy_midway_orange
        } else if (engine == controller.utilityRadialEngine) {
            controller.hexColors.legacy_utility_red 
        } else {
            controller.hexColors.legacy_prediction_purple 
        }

        val valDisplay = if (controller.isRadialSelectorActive) {
            val arrows = arrayOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")
            val isEmulator = transmitter.isHardwareSpoofingRequired

            itemsToDraw.mapIndexed { index, word ->
                val isDisabled = isEmulator && engine == controller.utilityRadialEngine && word in listOf("Copy", "Cut", "Select Word", "Select All")
                
                val textToDraw = if (isDisabled) "<font color='${controller.hexColors.joy_gray_disabled}'>$word</font>" 
                                 else if (engine == controller.predictiveRadialEngine && word in controller.SPECIAL_CHARS) "  $word  " 
                                 else word
                                 
                val dir = if (engine.maxSectors == 8 && index < arrows.size) "${arrows[index]} " else ""
                
                if (index == engine.radialSelectedIndex && !isDisabled) {
                    "<b>[<font color='${controller.hexColors.joy_gray_text}'>$dir</font><font color='$activeColor'>$textToDraw</font>]</b>"
                } else {
                    "<font color='${controller.hexColors.joy_gray_text}'>$dir$textToDraw</font>"
                }
            }.joinToString("   ")
        } else {
            itemsToDraw.mapIndexed { index, word ->
                val adjustedIndex = index + (if (activeItems.isNotEmpty()) (engine.absoluteIndex / engine.maxSectors) * engine.maxSectors else 0)
                if (adjustedIndex == engine.absoluteIndex) {
                    "<b><font color='$activeColor'>[$word]</font></b>" 
                } else {
                    "<font color='${controller.hexColors.joy_gray_text}'>$word</font>" 
                }
            }.joinToString("   ")
        }

        tvPredictions.text = android.text.Html.fromHtml(valDisplay, android.text.Html.FROM_HTML_MODE_LEGACY)

        val maxPages = kotlin.math.ceil(activeItems.size.toDouble() / engine.maxSectors).toInt().coerceAtLeast(1)
        if (controller.isRadialSelectorActive && maxPages > 1) {
            tvPaginationBadge.text = "[${engine.radialPage + 1}/$maxPages]"
            tvPaginationBadge.visibility = View.VISIBLE
            tvModeBadge.visibility = View.GONE 
        } else {
            tvPaginationBadge.visibility = View.GONE
            tvModeBadge.visibility = View.VISIBLE 
        }

        val capturedSelectedIndex = engine.radialSelectedIndex
        val capturedItems = itemsToDraw.toList()

        if (controller.isRadialSelectorActive && capturedItems.isNotEmpty()) {
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

    private var isSettingResting = false
    private fun setRestingUI(isComposingEmpty: Boolean = false) {
        if (isSettingResting) return 
        isSettingResting = true

        if (controller.currentMode == InputMode.PRE && !isComposingEmpty) {
            controller.predictiveRadialEngine.candidates = listOf(".")
            controller.predictiveRadialEngine.setAbsoluteIndex(0)
        } else {
            controller.predictiveRadialEngine.candidates = emptyList()
            controller.predictiveRadialEngine.setAbsoluteIndex(0)
        }
        
        updateUI()
        isSettingResting = false
    }

    // --- ANIMATIONS ---
    private var currentBadgeText = ""
    private fun animateSlotMachineBadge(newText: String, colorResId: Int) {
        if (currentBadgeText == newText) return 
        currentBadgeText = newText

        tvModeBadge.animate().translationY(-30f).alpha(0f).setDuration(100)
            .withEndAction {
                tvModeBadge.text = newText
                tvModeBadge.setTextColor(getColor(colorResId))
                tvModeBadge.translationY = 30f
                tvModeBadge.animate().translationY(0f).alpha(1f).setDuration(150)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            }.start()
    }

    private fun animateBarSlideIn() {
        hsvPredictions.translationX = 100f
        hsvPredictions.alpha = 0f
        hsvPredictions.animate().translationX(0f).alpha(1f).setDuration(150)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    // --- MACRO HANDLING (Kept here because it requires Context) ---
    private fun getClipboardPreview(): String? {
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        if (clipboard.hasPrimaryClip()) {
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }
    
    private fun loadMacroCandidates() {
        val list = mutableListOf<String>()
        val clip = getClipboardPreview() // Assuming this helper is defined elsewhere in your file
        if (clip != null) {
            val preview = clip.replace("\n", " ").take(10) + if(clip.length > 10) "..." else ""
            list.add("📋 Paste: $preview")
        }
        list.addAll(macroLibrary.map { it.name }) // Assuming macroLibrary is globally accessible
        controller.macroRadialEngine.candidates = list
        controller.macroRadialEngine.setAbsoluteIndex(0)
    }

    private fun handleMacroSelection(targetIndex: Int) {
        val clip = getClipboardPreview()
        val hasClip = clip != null
        val macroIndex = if (hasClip) targetIndex - 1 else targetIndex
        
        if (hasClip && targetIndex == 0) {
            lastPastedClipboardText = clip 
            transmitter.commitText(clip!!)
            return
        } else if (macroLibrary.isNotEmpty() && macroIndex >= 0 && macroIndex < macroLibrary.size) {
            val selectedMacro = macroLibrary[macroIndex]
            
            // Look how incredibly clean this is now! Transmitter handles all the shift/delay complexity natively.
            when (selectedMacro) {
                is MacroRepository.Macro.Pasteboard -> transmitter.commitText(selectedMacro.text)
                is MacroRepository.Macro.Chain -> {
                    for (node in selectedMacro.nodes) {
                        when (node) {
                            is MacroRepository.ChainNode.Text -> transmitter.commitText(node.content)
                            is MacroRepository.ChainNode.KeyCode -> transmitter.sendKeyPress(node.code)
                        }
                    }
                }
            }
        }

        controller.isRadialSelectorActive = false
        updateUI()
    }
}