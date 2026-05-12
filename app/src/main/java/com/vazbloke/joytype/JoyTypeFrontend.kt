package com.vazbloke.joytype

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class JoyTypeFrontend(
    private val context: Context,
    private val view: View,
    var transmitter: OutputTransmitter,
    private val onHideKeyboard: () -> Unit
) {
    val controller: JoyTypeController

    // UI Elements
    private val visualDebugView: VisualDebugView = view.findViewById(R.id.swipe_debug_view)
    private val tvPredictions: TextView = view.findViewById(R.id.tv_predictions)
    private val tvModeBadge: TextView = view.findViewById(R.id.tv_mode_badge)
    private val tvPaginationBadge: TextView = view.findViewById(R.id.tv_pagination_badge)
    private val hsvPredictions: android.widget.HorizontalScrollView = view.findViewById(R.id.hsv_predictions)
    private val tvBreadcrumb: TextView? = view.findViewById(R.id.tv_breadcrumb)

    // State Trackers
    private var radialDidMove = false
    private var cursorDidMove = false
    private var isCursorGliding = false
    private var repeatingAction: Action? = null
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var macroLibrary: List<MacroRepository.Macro> = emptyList()

    var onGamepadModeChanged: ((Boolean) -> Unit)? = null
    private val modeToggleHandler = Handler(Looper.getMainLooper())
    private val modeToggleRunnable = Runnable {
        controller.isGamepadMode = !controller.isGamepadMode
        controller.haptics.thud()
        Handler(Looper.getMainLooper()).postDelayed({ controller.haptics.thud() }, 150) // Double-thud feedback
        onGamepadModeChanged?.invoke(controller.isGamepadMode)
    }

    private var cursorX = 0f
    private var cursorY = 0f
    private var cursorMag = 0f
    private val cursorHandler = Handler(Looper.getMainLooper())

    private val tvSelectionBadge: TextView? = view.findViewById(R.id.tv_selection_badge) // Fixing UI Bug 1

    // Gamepad state trackers
    private var gpButtons = 0
    private var gpLX = 0f
    private var gpLY = 0f
    private var gpRX = 0f
    private var gpRY = 0f

    private fun updateGamepadState() {
        if (controller.isGamepadMode) transmitter.sendGamepadState(gpButtons, gpLX, gpLY, gpRX, gpRY)
    }

    private fun getGamepadMask(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> 1 shl 0; KeyEvent.KEYCODE_BUTTON_B -> 1 shl 1
        KeyEvent.KEYCODE_BUTTON_X -> 1 shl 2; KeyEvent.KEYCODE_BUTTON_Y -> 1 shl 3
        KeyEvent.KEYCODE_BUTTON_L1 -> 1 shl 4; KeyEvent.KEYCODE_BUTTON_R1 -> 1 shl 5
        KeyEvent.KEYCODE_BUTTON_L2 -> 1 shl 6; KeyEvent.KEYCODE_BUTTON_R2 -> 1 shl 7
        KeyEvent.KEYCODE_BUTTON_SELECT -> 1 shl 8; KeyEvent.KEYCODE_BUTTON_START -> 1 shl 9
        KeyEvent.KEYCODE_BUTTON_THUMBL -> 1 shl 10; KeyEvent.KEYCODE_BUTTON_THUMBR -> 1 shl 11
        KeyEvent.KEYCODE_DPAD_UP -> 1 shl 12; KeyEvent.KEYCODE_DPAD_DOWN -> 1 shl 13
        KeyEvent.KEYCODE_DPAD_LEFT -> 1 shl 14; KeyEvent.KEYCODE_DPAD_RIGHT -> 1 shl 15
        else -> 0
    }

    init {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        visualDebugView.visibility = if (prefs.getBoolean("visual_debug_mode", false)) View.VISIBLE else View.GONE

        controller = JoyTypeController(
            context = context,
            prefs = prefs,
            transmitter = transmitter,
            haptics = HapticManager(context),
            onUpdateUI = { updateUI() },
            onUpdateDebugUI = {
                visualDebugView.updateJoyT9Debug(
                    controller.t9Engine.currentStrokePath,
                    controller.registeredDebugPeaks,
                    controller.t9Engine.wordProbabilities,
                    controller.lastDetectionType
                )
            },
            onHideKeyboard = onHideKeyboard
        )

        controller.loadSettings()
        controller.resetState()

        tvPredictions.setOnClickListener {
            if (controller.t9Engine.currentPredictions.isEmpty() && !controller.isRadialSelectorActive) {
                android.widget.Toast.makeText(context, "Flick joystick to start typing", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            controller.t9Engine.loadDictionary(context)
        }
    }

    fun onResume() {
        controller.loadSettings()
        try {
            macroLibrary = MacroRepository.loadMacros(PreferenceManager.getDefaultSharedPreferences(context))
        } catch (e: Exception) {
            macroLibrary = emptyList()
        }
        updateUI()
    }
    
    fun setTransmitterInstance(newTransmitter: OutputTransmitter) {
        this.transmitter = newTransmitter
        controller.transmitter = newTransmitter
    }

    // --- CURSOR GLIDE RUNNABLE ---
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
                        val newState = transmitter.getEditorState()
                        controller.glideCursorIndex = newState?.selectionEnd ?: controller.glideCursorIndex
                    }
                }
            }
            cursorHandler.postDelayed(this, delay.coerceAtLeast(40L))
        }
    }

    private val repeatRunnable = object : Runnable {
        override fun run() {
            repeatingAction?.let {
                controller.executeAction(it, isRepeat = true)
                repeatHandler.postDelayed(this, 50L)
            }
        }
    }

    // --- HARDWARE ROUTING ---
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (controller.isGamepadMode) {
            gpLX = event.getAxisValue(MotionEvent.AXIS_X)
            gpLY = event.getAxisValue(MotionEvent.AXIS_Y)
            gpRX = event.getAxisValue(MotionEvent.AXIS_Z)
            gpRY = event.getAxisValue(MotionEvent.AXIS_RZ)
            
            // Map the D-Pad Hat axes to buttons
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            gpButtons = if (hatX < -0.5f) gpButtons or (1 shl 14) else gpButtons and (1 shl 14).inv()
            gpButtons = if (hatX > 0.5f) gpButtons or (1 shl 15) else gpButtons and (1 shl 15).inv()
            gpButtons = if (hatY < -0.5f) gpButtons or (1 shl 12) else gpButtons and (1 shl 12).inv()
            gpButtons = if (hatY > 0.5f) gpButtons or (1 shl 13) else gpButtons and (1 shl 13).inv()
            
            updateGamepadState()
            
            // THE FIX: Consume the event in MainActivity, but fall-through in the Service!
            return context is android.app.Activity
        }

        if (!(event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD))
            return false

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

        if (controller.isRadialSelectorActive) {
            if (mag > 0.3f) {
                val justWokeUp = !radialDidMove
                radialDidMove = true
                if (justWokeUp) updateUI()
            }
            val disabledIndices = if (transmitter.isHardwareSpoofingRequired && controller.activeRadialEngine == controller.utilityRadialEngine) setOf(1, 3, 4, 5) else emptySet()
            controller.activeRadialEngine.updateInput(x, y, mag, disabledIndices)
            return true
        }

        controller.syncModifiers()

        if (controller.isCursorModifierHeld) {
            if (mag > 0.2f) { 
                cursorX = x
                cursorY = y
                cursorMag = mag
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
            if (isCursorGliding) {
                isCursorGliding = false
                if (controller.isHighlighting && controller.highlightAnchorIndex == controller.glideCursorIndex) {
                    if (controller.activeRadialEngine == controller.utilityRadialEngine) controller.t9Engine.currentPredictions = emptyList()
                    controller.isHighlighting = false
                    controller.highlightAnchorIndex = -1
                    updateUI()
                }
            }
        }

        controller.handleStrokeInput(x, y, mag)
        return true
    }

    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (controller.isGamepadMode) {
            val mask = getGamepadMask(keyCode)
            if (mask != 0) {
                gpButtons = gpButtons or mask
                updateGamepadState()
            }
        }

        val isDPad = keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
        if (event.repeatCount > 0 && !isDPad) return true

        if (keyCode == controller.m1KeyCode) controller.isM1Held = true
        if (keyCode == controller.m2KeyCode) controller.isM2Held = true

        // THE FIX: Check for the 1-second M1+M2 Hold!
        if (controller.isM1Held && controller.isM2Held && event.repeatCount == 0) {
            modeToggleHandler.postDelayed(modeToggleRunnable, 1000L)
        }

        if (controller.isGamepadMode) {
            // THE FIX: Consume the event in MainActivity, but fall-through in the Service!
            return context is android.app.Activity
        }

        controller.syncModifiers()

        val targetRadialKey = when (controller.radialModifier) { ModifierKey.M1 -> controller.m1KeyCode; ModifierKey.M2 -> controller.m2KeyCode; else -> -1 }
        if (targetRadialKey != -1 && keyCode == targetRadialKey) {
            controller.isRadialSelectorActive = true
            radialDidMove = false
            if (controller.currentMode == InputMode.PRE && controller.t9Engine.wordProbabilities.isEmpty()) controller.predictiveRadialEngine.candidates = controller.SPECIAL_CHARS
            controller.activeRadialEngine.reset()
            controller.activeRadialEngine.setAbsoluteIndex(0)
            
            tvPredictions.animate().cancel() 
            tvPredictions.alpha = 0f
            tvPredictions.translationY = 30f 
            tvPredictions.animate().alpha(1f).translationY(0f).setDuration(200).start()
            
            updateUI()
            return true
        }

        val targetCursorKey = when (controller.cursorModifier) { ModifierKey.M1 -> controller.m1KeyCode; ModifierKey.M2 -> controller.m2KeyCode; else -> -1 }
        if (targetCursorKey != -1 && keyCode == targetCursorKey) {
            controller.isCursorMenuOpen = true
            cursorDidMove = false
            controller.cursorRadialEngine.setAbsoluteIndex(1)
            updateUI()
            return true
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
                repeatHandler.postDelayed(repeatRunnable, 400L)
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
        return false
    }

    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (controller.isGamepadMode) {
            val mask = getGamepadMask(keyCode)
            if (mask != 0) {
                gpButtons = gpButtons and mask.inv()
                updateGamepadState()
            }
        }

        repeatingAction = null
        repeatHandler.removeCallbacks(repeatRunnable)

        // THE FIX: Cancel the 1-second timer if they let go early!
        if (keyCode == controller.m1KeyCode || keyCode == controller.m2KeyCode) {
            modeToggleHandler.removeCallbacks(modeToggleRunnable)
        }

        if (keyCode == controller.m1KeyCode) controller.isM1Held = false
        if (keyCode == controller.m2KeyCode) controller.isM2Held = false

        if (controller.isGamepadMode) {
            // THE FIX: Consume the event in MainActivity, but fall-through in the Service!
            return context is android.app.Activity
        }

        controller.syncModifiers()

        val targetCursorKey = when (controller.cursorModifier) { ModifierKey.M1 -> controller.m1KeyCode; ModifierKey.M2 -> controller.m2KeyCode; else -> -1 }
        if (targetCursorKey != -1 && keyCode == targetCursorKey) {
            if (controller.isCursorMenuOpen) {
                controller.isCursorMenuOpen = false
                updateUI() 
            }
        }

        val targetRadialKey = when (controller.radialModifier) { ModifierKey.M1 -> controller.m1KeyCode; ModifierKey.M2 -> controller.m2KeyCode; else -> -1 }
        if (keyCode == targetRadialKey) {
            if (controller.isRadialSelectorActive) {
                controller.isRadialSelectorActive = false
                if (controller.commitOnRelease) {
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
                // Let it fall through or handle natively depending on environment
            }
            return true
        }

        if (KeyEvent.isGamepadButton(keyCode)) return true
        return false
    }
    
    fun onUpdateSelection(newSelStart: Int, newSelEnd: Int) {
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

    // --- UI RENDERING ---
    fun updateUI() {
        if (controller.currentMode == InputMode.MACRO && tvBreadcrumb != null) tvBreadcrumb.text = "MACROS"

        // THE FIX: Parse the Hex Strings into actual Android Color Ints!
        if (controller.isHighlighting) {
            tvSelectionBadge?.text = "[SEL]"
            tvSelectionBadge?.setTextColor(android.graphics.Color.parseColor(controller.hexColors.legacy_utility_red))
        } else {
            tvSelectionBadge?.text = "[CUR]"
            tvSelectionBadge?.setTextColor(android.graphics.Color.parseColor(controller.hexColors.joy_gray_text))
        }

        val engine = controller.activeRadialEngine
        if (controller.currentMode == InputMode.MACRO && engine.candidates.isEmpty()) loadMacroCandidates()

        if (engine.candidates.isEmpty() && !controller.isRadialSelectorActive && controller.currentMode != InputMode.MACRO) {
            setRestingUI(isComposingEmpty = controller.t9Engine.wordProbabilities.isNotEmpty())
            return
        }

        tvModeBadge.visibility = View.VISIBLE
        tvModeBadge.text = when (controller.currentMode) { InputMode.PRE -> "[T9]"; InputMode.ABC -> "[ABC]"; InputMode.MACRO -> "[MAC]" }

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

        val activeColor = if (controller.isRadialSelectorActive || controller.isCursorMenuOpen) controller.hexColors.legacy_midway_orange else if (engine == controller.utilityRadialEngine) controller.hexColors.legacy_utility_red else controller.hexColors.legacy_prediction_purple 

        val valDisplay = if (controller.isRadialSelectorActive) {
            val arrows = arrayOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")
            val isEmulator = transmitter.isHardwareSpoofingRequired

            itemsToDraw.mapIndexed { index, word ->
                val isDisabled = isEmulator && engine == controller.utilityRadialEngine && word in listOf("Copy", "Cut", "Select Word", "Select All")
                val textToDraw = if (isDisabled) "<font color='${controller.hexColors.joy_gray_disabled}'>$word</font>" else if (engine == controller.predictiveRadialEngine && word in controller.SPECIAL_CHARS) "  $word  " else word
                val dir = if (engine.maxSectors == 8 && index < arrows.size) "${arrows[index]} " else ""
                
                if (index == engine.radialSelectedIndex && !isDisabled) "<b>[<font color='${controller.hexColors.joy_gray_text}'>$dir</font><font color='$activeColor'>$textToDraw</font>]</b>"
                else "<font color='${controller.hexColors.joy_gray_text}'>$dir$textToDraw</font>"
            }.joinToString("   ")
        } else {
            itemsToDraw.mapIndexed { index, word ->
                val adjustedIndex = index + (if (activeItems.isNotEmpty()) (engine.absoluteIndex / engine.maxSectors) * engine.maxSectors else 0)
                if (adjustedIndex == engine.absoluteIndex) "<b><font color='$activeColor'>[$word]</font></b>" else "<font color='${controller.hexColors.joy_gray_text}'>$word</font>" 
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
                        } catch (e: Exception) {}
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
        if (controller.currentMode == InputMode.PRE && !isComposingEmpty) controller.predictiveRadialEngine.candidates = listOf(".")
        else controller.predictiveRadialEngine.candidates = emptyList()
        updateUI()
        isSettingResting = false
    }

    private fun getClipboardPreview(): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        if (clipboard.hasPrimaryClip()) {
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun loadMacroCandidates() {
        val list = mutableListOf<String>()
        val clip = getClipboardPreview()
        if (clip != null) {
            val preview = clip.replace("\n", " ").take(10) + if(clip.length > 10) "..." else ""
            list.add("📋 Paste: $preview")
        }
        list.addAll(macroLibrary.map { it.name }) 
        controller.macroRadialEngine.candidates = list
        controller.macroRadialEngine.setAbsoluteIndex(0)
    }

    private fun handleMacroSelection(targetIndex: Int) {
        val clip = getClipboardPreview()
        val hasClip = clip != null
        val macroIndex = if (hasClip) targetIndex - 1 else targetIndex
        
        if (hasClip && targetIndex == 0) {
            transmitter.commitText(clip!!)
            return
        } else if (macroLibrary.isNotEmpty() && macroIndex >= 0 && macroIndex < macroLibrary.size) {
            val selectedMacro = macroLibrary[macroIndex]
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