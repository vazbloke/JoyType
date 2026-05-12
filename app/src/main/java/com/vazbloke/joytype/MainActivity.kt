package com.vazbloke.joytype

import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var switchBluetooth: SwitchMaterial
    private lateinit var tvBtStatus: TextView
    private lateinit var cardBluetooth: MaterialCardView
    private lateinit var etSandbox: EditText

    // Core Engine
    private lateinit var controller: JoyTypeController
    private lateinit var localTransmitter: LocalActivityTransmitter
    private lateinit var bluetoothTransmitter: BluetoothHidTransmitter

    // UI Elements (Mirrored from Service)
    private lateinit var visualDebugView: VisualDebugView
    private lateinit var tvPredictions: TextView
    private lateinit var tvModeBadge: TextView
    private lateinit var tvPaginationBadge: TextView
    private lateinit var tvSelectionBadge: TextView
    private lateinit var hsvPredictions: android.widget.HorizontalScrollView
    private lateinit var llBreadcrumbBar: View
    private lateinit var tvBreadcrumb: TextView

    // State Trackers
    private var radialDidMove = false
    private var cursorDidMove = false
    private var isCursorGliding = false
    private var repeatingAction: Action? = null
    private val repeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var macroLibrary: List<MacroRepository.Macro> = emptyList()

    private val repeatRunnable = object : Runnable {
        override fun run() {
            repeatingAction?.let {
                controller.executeAction(it, isRepeat = true)
                repeatHandler.postDelayed(this, 50L) 
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        switchBluetooth = findViewById(R.id.switch_bluetooth)
        tvBtStatus = findViewById(R.id.tv_bt_status)
        cardBluetooth = findViewById(R.id.card_bluetooth)
        etSandbox = findViewById(R.id.et_sandbox)

        // Bind Keyboard UI
        tvPredictions = findViewById(R.id.tv_predictions)
        tvModeBadge = findViewById(R.id.tv_mode_badge)
        tvSelectionBadge = findViewById(R.id.tv_selection_badge)
        tvPaginationBadge = findViewById(R.id.tv_pagination_badge)
        visualDebugView = findViewById(R.id.swipe_debug_view)
        hsvPredictions = findViewById(R.id.hsv_predictions)
        llBreadcrumbBar = findViewById(R.id.ll_breadcrumb_bar)
        tvBreadcrumb = findViewById(R.id.tv_breadcrumb)

        // Initialize Transmitters
        localTransmitter = LocalActivityTransmitter(etSandbox)
        bluetoothTransmitter = BluetoothHidTransmitter(this)
        
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        visualDebugView.visibility = if (prefs.getBoolean("visual_debug_mode", false)) View.VISIBLE else View.GONE

        controller = JoyTypeController(
            context = this,
            prefs = prefs,
            transmitter = localTransmitter, // Default to Sandbox
            haptics = HapticManager(this),
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
            onHideKeyboard = { finish() }
        )

        controller.loadSettings()
        controller.resetState()

        CoroutineScope(Dispatchers.IO).launch {
            controller.t9Engine.loadDictionary(this@MainActivity)
        }

        setupBluetoothToggle()
    }

    override fun onResume() {
        super.onResume()
        controller.loadSettings()
        try {
            macroLibrary = MacroRepository.loadMacros(PreferenceManager.getDefaultSharedPreferences(this)) 
        } catch (e: Exception) {
            macroLibrary = emptyList()
        }
    }

    private fun setupBluetoothToggle() {
        switchBluetooth.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                controller.transmitter = bluetoothTransmitter
                etSandbox.hint = "Bluetooth Mode Active (Typing sent to external device)"
                etSandbox.isEnabled = false
                tvBtStatus.text = "Waiting for connection..."
                tvBtStatus.setTextColor(android.graphics.Color.parseColor("#E6C229"))
                cardBluetooth.strokeColor = android.graphics.Color.parseColor("#E6C229")
            } else {
                controller.transmitter = localTransmitter
                etSandbox.hint = "Type here to test local layout..."
                etSandbox.isEnabled = true
                tvBtStatus.text = "Disconnected"
                tvBtStatus.setTextColor(android.graphics.Color.parseColor("#888888"))
                cardBluetooth.strokeColor = android.graphics.Color.parseColor("#333333")
            }
        }
    }

    // --- HARDWARE EVENT INTERCEPTORS ---
    
    // We trap the KeyEvents here so Android doesn't use the D-Pad to move focus off the text box!
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val isGamepad = KeyEvent.isGamepadButton(keyCode) || keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
        
        if (isGamepad) {
            if (event.action == KeyEvent.ACTION_DOWN) handleKeyDown(keyCode, event)
            else if (event.action == KeyEvent.ACTION_UP) handleKeyUp(keyCode, event)
            return true 
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) {
            
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
                val disabledIndices = if (controller.transmitter.isHardwareSpoofingRequired && controller.activeRadialEngine == controller.utilityRadialEngine) setOf(1, 3, 4, 5) else emptySet()
                controller.activeRadialEngine.updateInput(x, y, mag, disabledIndices)
                return true
            }

            controller.syncModifiers()

            if (controller.isCursorModifierHeld) {
                if (mag > 0.2f) { 
                    cursorDidMove = true
                    val newIndex = if (x < 0) 0 else 1
                    if (controller.cursorRadialEngine.absoluteIndex != newIndex) {
                        controller.cursorRadialEngine.setAbsoluteIndex(newIndex)
                        updateUI()
                    }
                    if (!isCursorGliding) isCursorGliding = true
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
        return super.dispatchGenericMotionEvent(event)
    }

    private fun handleKeyDown(keyCode: Int, event: KeyEvent) {
        val isDPad = keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
        if (event.repeatCount > 0 && !isDPad) return

        if (keyCode == controller.m1KeyCode) controller.isM1Held = true
        if (keyCode == controller.m2KeyCode) controller.isM2Held = true
        controller.syncModifiers()

        val targetRadialKey = when (controller.radialModifier) { ModifierKey.M1 -> controller.m1KeyCode; ModifierKey.M2 -> controller.m2KeyCode; else -> -1 }
        if (targetRadialKey != -1 && keyCode == targetRadialKey) {
            controller.isRadialSelectorActive = true
            radialDidMove = false
            if (controller.currentMode == InputMode.PRE && controller.t9Engine.wordProbabilities.isEmpty()) controller.predictiveRadialEngine.candidates = controller.SPECIAL_CHARS
            controller.activeRadialEngine.reset()
            controller.activeRadialEngine.setAbsoluteIndex(0)
            updateUI()
            return
        }

        val targetCursorKey = when (controller.cursorModifier) { ModifierKey.M1 -> controller.m1KeyCode; ModifierKey.M2 -> controller.m2KeyCode; else -> -1 }
        if (targetCursorKey != -1 && keyCode == targetCursorKey) {
            controller.isCursorMenuOpen = true
            cursorDidMove = false
            controller.cursorRadialEngine.setAbsoluteIndex(1)
            updateUI()
            return
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
            return
        }
    }

    private fun handleKeyUp(keyCode: Int, event: KeyEvent) {
        repeatingAction = null
        repeatHandler.removeCallbacks(repeatRunnable)

        if (keyCode == controller.m1KeyCode) controller.isM1Held = false
        if (keyCode == controller.m2KeyCode) controller.isM2Held = false
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
                if (controller.commitOnRelease) controller.commitCurrentSelection() else controller.resetState()
                updateUI()
            }
        }
    }

    // --- UI RENDERING (Mirrored from Service) ---
    private fun updateUI() {
        if (!::controller.isInitialized) return
        if (controller.currentMode == InputMode.MACRO && ::tvBreadcrumb.isInitialized) tvBreadcrumb.text = "MACROS"

        val engine = controller.activeRadialEngine
        if (controller.currentMode == InputMode.MACRO && engine.candidates.isEmpty()) loadMacroCandidates()

        if (engine.candidates.isEmpty() && !controller.isRadialSelectorActive && controller.currentMode != InputMode.MACRO) {
            setRestingUI(isComposingEmpty = controller.t9Engine.wordProbabilities.isNotEmpty())
            return
        }

        tvModeBadge.visibility = View.VISIBLE
        val badgeText = when (controller.currentMode) { InputMode.PRE -> "[T9]"; InputMode.ABC -> "[ABC]"; InputMode.MACRO -> "[MAC]" }
        tvModeBadge.text = badgeText

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
            val isEmulator = controller.transmitter.isHardwareSpoofingRequired

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

    private fun loadMacroCandidates() {
        val list = mutableListOf<String>()
        list.addAll(macroLibrary.map { it.name }) 
        controller.macroRadialEngine.candidates = list
        controller.macroRadialEngine.setAbsoluteIndex(0)
    }
}