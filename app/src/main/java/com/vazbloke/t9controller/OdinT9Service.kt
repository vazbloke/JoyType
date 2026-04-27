package com.vazbloke.t9controller

import android.graphics.PointF
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.pow

class OdinT9Service : InputMethodService() {

    private lateinit var tvPredictions: TextView
    private lateinit var tvMode: TextView
    private lateinit var swipeDebugView: SwipeDebugView
    private val t9Engine = T9Engine()
    private val swipeEngine = SwipeEngine()

    enum class InputMode {
        LJOY_RBUTTONS, JOY_JOY, SWIPE, TEACH
    }

    private var currentMode = InputMode.LJOY_RBUTTONS
    private var preTeachMode = InputMode.LJOY_RBUTTONS
    private var currentSequence = ""
    private var currentPredictions = listOf<String>()
    private var predictionIndex = 0
    private var currentJoystickRow = 1

    private var lastLJoyDirection = -1
    private var lastRJoyDirection = -1

    private var analogL2Down = false
    private var digitalL2Down = false
    private var analogR2Down = false
    private var digitalR2Down = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var backspaceRepeatRunnable: Runnable? = null

    private var isSwiping = false
    private var cursorX = 4.5f
    private var cursorY = 1.0f
    private val currentSwipePath = mutableListOf<PointF>()
    private var anchorJoyX = 0f
    private var anchorJoyY = 0f
    private var currentJoyX = 0f
    private var currentJoyY = 0f
    private var smoothedJoyX = 0f
    private var smoothedJoyY = 0f

    private var teachResolvedChars = charArrayOf()
    private var teachCurrentIndex = 0

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
        return sqrt((x2 - p1.x).toDouble().pow(2.0) + (y2 - p1.y).toDouble().pow(2.0)).toFloat()
    }

    override fun onCreate() {
        super.onCreate()
        t9Engine.loadDictionary(this)
        swipeEngine.dictionary = t9Engine.getAllWords()
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        tvPredictions = view.findViewById(R.id.tv_predictions)
        tvMode = view.findViewById(R.id.tv_mode)
        swipeDebugView = view.findViewById(R.id.swipe_debug_view)

        view.findViewById<View>(R.id.btn_toggle_mode).setOnClickListener {
            toggleMode()
        }

        updateModeUI()
        return view
    }

    private fun toggleMode() {
        currentMode = when (currentMode) {
            InputMode.LJOY_RBUTTONS -> InputMode.JOY_JOY
            InputMode.JOY_JOY -> InputMode.SWIPE
            InputMode.SWIPE -> InputMode.LJOY_RBUTTONS
            InputMode.TEACH -> InputMode.LJOY_RBUTTONS
        }
        currentSequence = ""
        updatePredictions()
        updateModeUI()
        swipeDebugView.clear()
    }

    private fun updateModeUI() {
        tvMode.text = when (currentMode) {
            InputMode.LJOY_RBUTTONS -> "Mode: LJoy RButtons"
            InputMode.JOY_JOY -> "Mode: Joy Joy"
            InputMode.SWIPE -> "Mode: Swipe"
            InputMode.TEACH -> "Mode: Teach (M2 to Save)"
        }

        // HIDE THE CANVAS unless we are explicitly in SWIPE mode
        if (currentMode == InputMode.SWIPE) {
            swipeDebugView.visibility = View.VISIBLE
            if (currentSwipePath.isEmpty()) {
                tvPredictions.text = "Hold L1 to Swipe"
            }
        } else {
            swipeDebugView.visibility = View.GONE
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!isInputViewShown) {
            return super.onGenericMotionEvent(event)
        }

        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) {

            val lTrigger = max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
            val rTrigger = max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))

            updateL2State(lTrigger > 0.5f, isAnalog = true)
            updateR2State(rTrigger > 0.5f, isAnalog = true)

            when (currentMode) {
                InputMode.LJOY_RBUTTONS -> {
                    val yAxis = event.getAxisValue(MotionEvent.AXIS_Y)
                    currentJoystickRow = when {
                        yAxis < -0.5f -> 0
                        yAxis > 0.5f -> 2
                        else -> 1
                    }
                    return true
                }
                InputMode.JOY_JOY -> {
                    val x = event.getAxisValue(MotionEvent.AXIS_X)
                    val y = event.getAxisValue(MotionEvent.AXIS_Y)
                    val z = event.getAxisValue(MotionEvent.AXIS_Z)
                    val rz = event.getAxisValue(MotionEvent.AXIS_RZ)

                    handleJoystickDirection(x, y, isLeft = true)
                    handleJoystickDirection(z, rz, isLeft = false)
                    return true
                }
                InputMode.SWIPE -> {
                    var rawX = event.getAxisValue(MotionEvent.AXIS_X)
                    var rawY = event.getAxisValue(MotionEvent.AXIS_Y)

                    if (abs(rawX) < 0.01f && abs(rawY) < 0.01f) {
                        rawX = event.getAxisValue(MotionEvent.AXIS_Z)
                        rawY = event.getAxisValue(MotionEvent.AXIS_RZ)
                    }

                    val mapped = mapCircleToSquare(rawX, rawY)

                    val deltaX = abs(mapped.x - currentJoyX)
                    val deltaY = abs(mapped.y - currentJoyY)
                    if (deltaX > 0.8f || deltaY > 0.8f) {
                        currentJoyX = mapped.x
                        currentJoyY = mapped.y
                        return true
                    }

                    currentJoyX = mapped.x
                    currentJoyY = mapped.y

                    val smoothingFactor = 0.3f
                    smoothedJoyX = (smoothedJoyX * (1f - smoothingFactor)) + (mapped.x * smoothingFactor)
                    smoothedJoyY = (smoothedJoyY * (1f - smoothingFactor)) + (mapped.y * smoothingFactor)

                    if (isSwiping) {
                        cursorX = smoothedJoyX
                        cursorY = smoothedJoyY

                        val lastPoint = currentSwipePath.lastOrNull()
                        if (lastPoint == null || getDistance(lastPoint, cursorX, cursorY) > 0.02f) {
                            currentSwipePath.add(PointF(cursorX, cursorY))

                            val corners = swipeEngine.extractInflectionPoints(currentSwipePath)

                            mainHandler.post {
                                swipeDebugView.updatePath(currentSwipePath, corners)
                                val displayCorners = max(0, corners.size - 2)
                                tvPredictions.text = "Shape Corners: $displayCorners"
                            }
                        }
                    } else {
                        smoothedJoyX = mapped.x
                        smoothedJoyY = mapped.y
                    }
                    return true
                }
                InputMode.TEACH -> {
                    return true
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    private fun handleJoystickDirection(x: Float, y: Float, isLeft: Boolean): Boolean {
        val mag = sqrt((x * x + y * y).toDouble())
        if (mag < 0.25f) {
            if (isLeft) lastLJoyDirection = -1 else lastRJoyDirection = -1
            return false
        }

        if (mag < 0.5f) return true

        val angle = Math.toDegrees(atan2((-y).toDouble(), x.toDouble()))
        val normAngle = (angle + 360) % 360
        val direction = (((normAngle + 22.5) % 360) / 45).toInt()

        val lastDir = if (isLeft) lastLJoyDirection else lastRJoyDirection
        if (lastDir == -1) {
            val digit = when (direction) {
                0 -> "6"
                1 -> "3"
                2 -> "2"
                3 -> "1"
                4 -> "4"
                5 -> "7"
                6 -> "8"
                7 -> "9"
                else -> null
            }
            if (digit != null) {
                currentSequence += digit
                updatePredictions()
            }
            if (isLeft) lastLJoyDirection = direction else lastRJoyDirection = direction
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isInputViewShown) {
            return super.onKeyDown(keyCode, event)
        }

        val isRepeat = event.repeatCount > 0

        if (keyCode == KeyEvent.KEYCODE_BUTTON_C || keyCode == 188) {
            if (!isRepeat && currentMode == InputMode.JOY_JOY) {
                currentSequence += "5"
                updatePredictions()
            }
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_BUTTON_Z || keyCode == 189) {
            if (!isRepeat) {
                handleTeachModeToggle()
            }
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_BUTTON_L2) {
            updateL2State(true, isAnalog = false)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            updateR2State(true, isAnalog = false)
            return true
        }

        if (currentMode == InputMode.JOY_JOY && keyCode == KeyEvent.KEYCODE_BUTTON_L1) {
            if (!isRepeat) {
                handleBackspace()
                startBackspaceRepeat()
            }
            return true
        }

        var handled = true
        when (currentMode) {
            InputMode.LJOY_RBUTTONS -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_BUTTON_Y -> if (!isRepeat) handleGridInput(0)
                    KeyEvent.KEYCODE_BUTTON_X -> if (!isRepeat) handleGridInput(1)
                    KeyEvent.KEYCODE_BUTTON_A -> if (!isRepeat) handleGridInput(2)
                    KeyEvent.KEYCODE_BUTTON_L1 -> if (!isRepeat) cyclePrediction(-1)
                    KeyEvent.KEYCODE_BUTTON_R1 -> if (!isRepeat) cyclePrediction(1)
                    KeyEvent.KEYCODE_BUTTON_B -> if (!isRepeat) commitCurrentWord(" ")
                    KeyEvent.KEYCODE_DPAD_LEFT -> handleCursorMove(KeyEvent.KEYCODE_DPAD_LEFT)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> handleCursorMove(KeyEvent.KEYCODE_DPAD_RIGHT)
                    KeyEvent.KEYCODE_DPAD_UP -> handleCursorMove(KeyEvent.KEYCODE_DPAD_UP)
                    KeyEvent.KEYCODE_DPAD_DOWN -> handleCursorMove(KeyEvent.KEYCODE_DPAD_DOWN)
                    else -> handled = false
                }
            }
            InputMode.JOY_JOY -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_BUTTON_B -> if (!isRepeat) {
                        currentSequence += "5"
                        updatePredictions()
                    }
                    KeyEvent.KEYCODE_BUTTON_R1 -> if (!isRepeat) commitCurrentWord(" ")
                    KeyEvent.KEYCODE_DPAD_LEFT -> handleCursorMove(KeyEvent.KEYCODE_DPAD_LEFT)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> handleCursorMove(KeyEvent.KEYCODE_DPAD_RIGHT)
                    else -> handled = false
                }
            }
            InputMode.SWIPE -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_BUTTON_L1 -> {
                        if (!isRepeat && !isSwiping) {
                            isSwiping = true
                            currentSwipePath.clear()
                            cursorX = smoothedJoyX
                            cursorY = smoothedJoyY
                            currentSwipePath.add(PointF(cursorX, cursorY))
                            anchorJoyX = cursorX
                            anchorJoyY = cursorY
                            tvPredictions.text = "Recording Shape..."
                        }
                    }
                    KeyEvent.KEYCODE_BUTTON_X -> if (!isRepeat) cyclePrediction(-1)
                    KeyEvent.KEYCODE_BUTTON_A -> if (!isRepeat) cyclePrediction(1)
                    KeyEvent.KEYCODE_BUTTON_B -> if (!isRepeat) commitCurrentWord(" ")
                    KeyEvent.KEYCODE_DPAD_LEFT -> handleCursorMove(KeyEvent.KEYCODE_DPAD_LEFT)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> handleCursorMove(KeyEvent.KEYCODE_DPAD_RIGHT)
                    else -> handled = false
                }
            }
            InputMode.TEACH -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_BUTTON_X -> if (!isRepeat) resolveTeachChar(0)
                    KeyEvent.KEYCODE_BUTTON_A -> if (!isRepeat) resolveTeachChar(1)
                    KeyEvent.KEYCODE_BUTTON_B -> if (!isRepeat) resolveTeachChar(2)
                    KeyEvent.KEYCODE_BUTTON_Y -> if (!isRepeat) resolveTeachChar(3)
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (!isRepeat) shiftTeachCursor(-1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (!isRepeat) shiftTeachCursor(1)
                    else -> handled = false
                }
            }
        }

        if (handled) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_L2) {
            updateL2State(false, isAnalog = false)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            updateR2State(false, isAnalog = false)
            return true
        }
        if (currentMode == InputMode.JOY_JOY && keyCode == KeyEvent.KEYCODE_BUTTON_L1) {
            stopBackspaceRepeat()
            return true
        }

        if (currentMode == InputMode.SWIPE && keyCode == KeyEvent.KEYCODE_BUTTON_L1) {
            if (isSwiping) {
                isSwiping = false
                processSwipe()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun updateL2State(down: Boolean, isAnalog: Boolean) {
        val wasDown = analogL2Down || digitalL2Down
        if (isAnalog) analogL2Down = down else digitalL2Down = down
        val isDown = analogL2Down || digitalL2Down

        if (isDown != wasDown) {
            if (currentMode != InputMode.JOY_JOY) {
                if (isDown) {
                    handleBackspace()
                    startBackspaceRepeat()
                } else {
                    stopBackspaceRepeat()
                }
            }
        }
    }

    private fun updateR2State(down: Boolean, isAnalog: Boolean) {
        val wasDown = analogR2Down || digitalR2Down
        if (isAnalog) analogR2Down = down else digitalR2Down = down
        val isDown = analogR2Down || digitalR2Down

        if (isDown != wasDown && isDown) {
            handleEnter()
        }
    }

    private fun startBackspaceRepeat() {
        stopBackspaceRepeat()
        backspaceRepeatRunnable = object : Runnable {
            override fun run() {
                handleBackspace()
                mainHandler.postDelayed(this, 100)
            }
        }
        mainHandler.postDelayed(backspaceRepeatRunnable!!, 1000)
    }

    private fun stopBackspaceRepeat() {
        backspaceRepeatRunnable?.let { mainHandler.removeCallbacks(it) }
        backspaceRepeatRunnable = null
    }

    private fun handleTeachModeToggle() {
        if (currentMode != InputMode.TEACH) {
            if (currentSequence.isEmpty()) return

            preTeachMode = currentMode
            currentMode = InputMode.TEACH

            // Generate a blank slate of ? corresponding to sequence length
            teachResolvedChars = CharArray(currentSequence.length) { '?' }

            // Explicitly start at index 0 every time
            teachCurrentIndex = 0

            updateTeachUI()
            updateModeUI() // To toggle canvas off if entering from Swipe Mode
        } else {
            val finalWord = String(teachResolvedChars).replace('?', ' ').trim()
            if (finalWord.isNotEmpty() && !finalWord.contains('?')) {
                t9Engine.addCustomWord(finalWord)
                val ic = currentInputConnection
                ic?.commitText("$finalWord ", 1)
            }
            currentSequence = ""
            currentMode = preTeachMode
            updatePredictions()
            updateModeUI()
        }
    }

    private fun resolveTeachChar(buttonIndex: Int) {
        if (teachCurrentIndex >= currentSequence.length) return
        val digit = currentSequence[teachCurrentIndex]
        val availableChars = t9Engine.getCharsForDigit(digit)

        if (buttonIndex < availableChars.size) {
            teachResolvedChars[teachCurrentIndex] = availableChars[buttonIndex]
            if (teachCurrentIndex < currentSequence.length - 1) {
                teachCurrentIndex++
            }
            updateTeachUI()
        }
    }

    private fun shiftTeachCursor(direction: Int) {
        teachCurrentIndex = (teachCurrentIndex + direction).coerceIn(0, currentSequence.length - 1)
        updateTeachUI()
    }

    private fun updateTeachUI() {
        val displayWord = StringBuilder()
        for (i in teachResolvedChars.indices) {
            val c = teachResolvedChars[i].uppercaseChar()
            if (i == teachCurrentIndex) {
                displayWord.append("<b><font color='#4488FF'>[ $c ]</font></b>")
            } else {
                displayWord.append("$c ")
            }
        }

        val currentDigit = currentSequence[teachCurrentIndex]
        val chars = t9Engine.getCharsForDigit(currentDigit)

        val hintString = buildString {
            if (chars.isNotEmpty()) append("X=${chars[0].uppercaseChar()}   ")
            if (chars.size > 1) append("A=${chars[1].uppercaseChar()}   ")
            if (chars.size > 2) append("B=${chars[2].uppercaseChar()}   ")
            if (chars.size > 3) append("Y=${chars[3].uppercaseChar()}")
        }

        val finalDisplay = "${displayWord.toString().trim()}<br><small><font color='#AAAAAA'>$hintString</font></small>"
        tvPredictions.text = Html.fromHtml(finalDisplay, Html.FROM_HTML_MODE_LEGACY)
    }

    private fun handleGridInput(column: Int) {
        val digit = (currentJoystickRow * 3) + column + 1
        currentSequence += digit.toString()
        updatePredictions()
    }

    private fun cyclePrediction(direction: Int) {
        if (currentPredictions.isEmpty()) return
        predictionIndex = (predictionIndex + direction + currentPredictions.size) % currentPredictions.size
        updateUI()
    }

    private fun commitCurrentWord(suffix: String = "") {
        val ic = currentInputConnection ?: return

        if (currentPredictions.isNotEmpty()) {
            val wordToCommit = currentPredictions[predictionIndex]
            ic.commitText("$wordToCommit$suffix", 1)
        } else if (currentSequence.isNotEmpty()) {
            ic.commitText("$currentSequence$suffix", 1)
        } else if (suffix.isNotEmpty()) {
            ic.commitText(suffix, 1)
        }

        currentSequence = ""
        updatePredictions()
    }

    private fun handleEnter() {
        if (currentSequence.isNotEmpty()) {
            commitCurrentWord("")
        }
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    private fun handleBackspace() {
        if (currentSequence.isNotEmpty()) {
            currentSequence = currentSequence.dropLast(1)
            updatePredictions()
        } else {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
    }

    private fun handleCursorMove(dpadKeyCode: Int) {
        if (currentSequence.isNotEmpty()) {
            commitCurrentWord("")
        }
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, dpadKeyCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, dpadKeyCode))
    }

    private fun updatePredictions() {
        if (currentSequence.isEmpty()) {
            currentPredictions = emptyList()
            predictionIndex = 0
            if (currentMode != InputMode.SWIPE && currentMode != InputMode.TEACH) {
                tvPredictions.text = "Odin T9 Ready"
            }
            return
        }

        currentPredictions = t9Engine.getPredictions(currentSequence)
        predictionIndex = 0
        updateUI()
    }

    private fun updateUI() {
        if (currentPredictions.isEmpty()) {
            if (currentMode == InputMode.SWIPE && currentSwipePath.isEmpty()) {
                tvPredictions.text = "Hold L1 to Swipe"
            } else if (currentSequence.isNotEmpty()) {
                // If there's no match, display purely grey question marks corresponding to the length
                val questionMarks = "?".repeat(currentSequence.length)
                val htmlDisplay = "<font color='#666666'>$questionMarks</font>"
                tvPredictions.text = Html.fromHtml(htmlDisplay, Html.FROM_HTML_MODE_LEGACY)
            }
            return
        }

        val display = currentPredictions.mapIndexed { index, word ->
            if (index == predictionIndex) "[$word]" else word
        }.joinToString("   ")

        tvPredictions.text = display
    }

    private fun processSwipe() {
        val predictions = swipeEngine.decodeSwipe(currentSwipePath, anchorJoyX, anchorJoyY)

        if (predictions.isNotEmpty()) {
            val bestMatch = predictions.first()
            currentInputConnection?.commitText("$bestMatch ", 1)

            val alternatives = predictions.take(3).joinToString("   |   ")
            tvPredictions.text = alternatives
        } else {
            tvPredictions.text = "No swipe match"
        }
        swipeDebugView.clear()
    }
}