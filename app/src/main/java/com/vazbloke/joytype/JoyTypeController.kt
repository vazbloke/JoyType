package com.vazbloke.joytype

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent

// --- EXTRACTED ENUMS ---
enum class ModifierKey { NONE, M1, M2 }
data class KeyCombo(val keyCode: Int, val modifier: ModifierKey)
enum class InputMode { PRE, ABC, MACRO }

enum class HardwareMode { KEYBOARD, GAMEPAD, MOUSE }

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

// --- THE UNIVERSAL ENGINE ---
class JoyTypeController(
    val context: Context,
    val prefs: SharedPreferences,
    var transmitter: OutputTransmitter,
    val haptics: HapticManager,
    val onUpdateUI: () -> Unit,
    val onUpdateDebugUI: () -> Unit,
    val onHideKeyboard: () -> Unit
) {
    val t9Engine = T9Engine()
    
    // --- CORE STATE ---
    var hardwareMode = HardwareMode.KEYBOARD // THE FIX: 3-way hardware state
    var currentMode = InputMode.PRE
        set(value) {
            field = value
            onUpdateUI()
        }

    var isRadialSelectorActive = false
    var isCursorMenuOpen = false
    var isHighlighting = false
    
    // Feature Flags
    var autoSpace = true
    var doubleAcceptPeriod = true
    var autoCap = true
    var commitOnRelease = true
    var pairInputMode = false

    // Key Binding State
    val keyBindings = mutableMapOf<KeyCombo, Action>()
    var m1KeyCode = KeyEvent.KEYCODE_BUTTON_C
    var m2KeyCode = KeyEvent.KEYCODE_BUTTON_Z
    var radialModifier = ModifierKey.M1
    var cursorModifier = ModifierKey.M2

    var isM1Held = false
    var isM2Held = false
    var isCursorModifierHeld = false

    // Master Sync
    fun syncModifiers() {
        isCursorModifierHeld = cursorModifier != ModifierKey.NONE && (
            (cursorModifier == ModifierKey.M1 && isM1Held) || 
            (cursorModifier == ModifierKey.M2 && isM2Held)
        )
    }

    // --- ENGINES ---
    private val radialListener = object : RadialWheelEngine.RadialWheelListener {
        override fun onIndexChanged(newIndex: Int, page: Int) { onUpdateUI() }
        override fun onTick() { haptics.tick() }
        override fun onThud() { haptics.thud() }
    }

    val predictiveRadialEngine = RadialWheelEngine(8, radialListener)
    val abcRadialEngine = RadialWheelEngine(9, radialListener)
    val utilityRadialEngine = RadialWheelEngine(8, radialListener).apply {
        candidates = listOf("Cancel", "Copy", "Paste", "Cut", "Select Word", "Select All")
    }
    val cursorRadialEngine = RadialWheelEngine(8, radialListener).apply {
        candidates = listOf("◀", "▶")
    }
    val macroRadialEngine = RadialWheelEngine(8, radialListener)

    val activeRadialEngine: RadialWheelEngine
        get() = when {
            isCursorModifierHeld -> cursorRadialEngine  
            isHighlighting -> utilityRadialEngine       
            currentMode == InputMode.ABC -> abcRadialEngine
            currentMode == InputMode.MACRO -> macroRadialEngine
            else -> predictiveRadialEngine
        }

    fun loadSettings() {
        autoSpace = prefs.getBoolean("autospace_after_accept", true)
        doubleAcceptPeriod = prefs.getBoolean("double_accept_period", true)
        autoCap = prefs.getBoolean("auto_capitalization", true)
        commitOnRelease = prefs.getBoolean("commit_on_release", true)
        pairInputMode = prefs.getBoolean("pair_input_mode", false)

        m1KeyCode = prefs.getInt("key_mod_1", KeyEvent.KEYCODE_BUTTON_C)
        m2KeyCode = prefs.getInt("key_mod_2", KeyEvent.KEYCODE_BUTTON_Z)

        val radialStr = prefs.getString("joy_radial_mod", "M1")
        radialModifier = when(radialStr) {
            "M1" -> ModifierKey.M1
            "M2" -> ModifierKey.M2
            else -> ModifierKey.NONE
        }
        
        val cursorStr = prefs.getString("joy_cursor_mod", "M2")
        cursorModifier = when(cursorStr) {
            "M1" -> ModifierKey.M1
            "M2" -> ModifierKey.M2
            else -> ModifierKey.NONE
        }

        keyBindings.clear()
        for (action in Action.values()) {
            if (action == Action.NONE) continue
            val keyCode = prefs.getInt(action.prefKey, action.defaultKey)
            val modString = prefs.getString(action.prefMod, action.defaultMod.name)
            val mod = try { ModifierKey.valueOf(modString ?: "NONE") } catch (e: Exception) { ModifierKey.NONE }
            if (keyCode != -1) keyBindings[KeyCombo(keyCode, mod)] = action
        }
    }

    // --- COLORS & CONSTANTS ---
    inner class HexPalette {
        val joy_orange by lazy { getHexColor(R.color.joy_orange) }
        val joy_purple by lazy { getHexColor(R.color.joy_purple) }
        val joy_red by lazy { getHexColor(R.color.joy_red) }
        val joy_green by lazy { getHexColor(R.color.joy_green) }
        val joy_blue by lazy { getHexColor(R.color.joy_blue) }
        val joy_yellow by lazy { getHexColor(R.color.joy_yellow) }
        val joy_gray_text by lazy { getHexColor(R.color.joy_gray_text) }
        val joy_less_gray by lazy { getHexColor(R.color.joy_less_gray) }
        val joy_gray_disabled by lazy { getHexColor(R.color.joy_gray_disabled) }
        val joy_gray_dim by lazy { getHexColor(R.color.joy_gray_dim) }
        val legacy_midway_orange by lazy { getHexColor(R.color.legacy_midway_orange) }
        val legacy_utility_red by lazy { getHexColor(R.color.legacy_utility_red) }
        val legacy_prediction_purple by lazy { getHexColor(R.color.legacy_prediction_purple) }
    }
    val hexColors = HexPalette()

    private fun getHexColor(resId: Int): String {
        return String.format("#%06X", 0xFFFFFF and context.getColor(resId))
    }

    val SPECIAL_CHARS = listOf(
        ".", ",", "?", "!", "@", "-", "_", ":", ";", "'", "\"", "(", ")", "/", "\\", "&", 
        "#", "%", "*", "+", "=", "<", ">", "$", "~", "`", "{", "}", "[", "]", "|", "^"
    )
    
    val ABC_DIGITS by lazy {
        listOf(
            "1<small><font color='${hexColors.joy_gray_dim}'>0.,?</font></small>", 
            "2<small><font color='${hexColors.joy_gray_dim}'>abc</font></small>", 
            "3<small><font color='${hexColors.joy_gray_dim}'>def</font></small>", 
            "4<small><font color='${hexColors.joy_gray_dim}'>ghi</font></small>", 
            "5<small><font color='${hexColors.joy_gray_dim}'>jkl</font></small>", 
            "6<small><font color='${hexColors.joy_gray_dim}'>mno</font></small>", 
            "7<small><font color='${hexColors.joy_gray_dim}'>pqrs</font></small>", 
            "8<small><font color='${hexColors.joy_gray_dim}'>tuv</font></small>", 
            "9<small><font color='${hexColors.joy_gray_dim}'>wxyz</font></small>"
        )
    }

    // --- STATE TRACKERS ---
    var vibratedThisStroke = false
    var peakPt: android.graphics.PointF? = null
    var peakMag = 0f
    var inValley = false
    var lastMag = 0f
    var lastDetectionType = ""
    val registeredDebugPeaks = mutableListOf<android.graphics.PointF>()

    var glideCursorIndex = 0
    var highlightAnchorIndex = -1
    var lastManualSpaceTime = 0L

    private val undoStack = java.util.Stack<EditorStateSnapshot>()
    private val redoStack = java.util.Stack<EditorStateSnapshot>()

    // --- ACTION PIPELINE ---
    fun resetState() {
        if (t9Engine.wordProbabilities.isNotEmpty()) {
            t9Engine.lastAcceptedProbabilities.clear()
            t9Engine.lastAcceptedProbabilities.addAll(t9Engine.wordProbabilities)
        }

        t9Engine.currentStrokePath.clear()
        t9Engine.wordProbabilities.clear()

        if (currentMode == InputMode.ABC) {
            abcRadialEngine.candidates = ABC_DIGITS
            abcRadialEngine.setAbsoluteIndex(0)
        } 
        
        predictiveRadialEngine.candidates = emptyList()
        predictiveRadialEngine.setAbsoluteIndex(0)

        isRadialSelectorActive = false
        predictiveRadialEngine.reset()
        abcRadialEngine.reset()
        utilityRadialEngine.reset()
        macroRadialEngine.reset()

        vibratedThisStroke = false
        peakPt = null
        peakMag = 0f
        inValley = false
        lastMag = 0f
        lastDetectionType = ""
        registeredDebugPeaks.clear()

        isHighlighting = false
        highlightAnchorIndex = -1
        
        onUpdateUI()
    }

    fun saveUndoSnapshot() {
        val state = transmitter.getEditorState() ?: return
        if (state.text.isNotEmpty()) {
            undoStack.push(state)
            if (undoStack.size > 20) undoStack.removeAt(0)
            redoStack.clear()
        }
    }

    fun getAutoCapitalizedWord(word: String): String {
        if (!autoCap) return word
        val state = transmitter.getEditorState() ?: return word 

        val textBefore = state.text.substring(0, state.selectionStart.coerceAtMost(state.text.length))
        val trimmedBefore = textBefore.trimEnd()
        val isStartOfSentence = textBefore.isEmpty() || 
                                trimmedBefore.endsWith(".") || 
                                trimmedBefore.endsWith("!") || 
                                trimmedBefore.endsWith("?") || 
                                textBefore.endsWith("\n")

        if (isStartOfSentence || word.lowercase() == "i") {
            return word.replaceFirstChar { it.uppercase() }
        }
        return word
    }

    fun diveIntoAbcStage2(targetStr: String) {
        val targetDigit = targetStr.first()
        val chars = mutableListOf<String>()
        chars.add(targetDigit.toString())

        if (targetDigit == '1') {
            chars.add("0")
            chars.add(".")
            chars.addAll(SPECIAL_CHARS.filter { it != "." }) 
        } else {
            val letters = t9Engine.getCharsForDigit(targetDigit)
            for (c in letters) {
                chars.add(c.uppercaseChar().toString())
                chars.add(c.toString())
            }
        }
        abcRadialEngine.candidates = chars
        abcRadialEngine.setAbsoluteIndex(0)
        isRadialSelectorActive = false 
        onUpdateUI()
    }

    fun executeUtilityCommand(command: String) {
        val state = transmitter.getEditorState()
        var shouldCollapse = true
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager

        when (command) {
            "Cancel" -> {}
            "Copy" -> {
                if (state != null && state.selectionStart != state.selectionEnd) {
                    val start = kotlin.math.min(state.selectionStart, state.selectionEnd)
                    val end = kotlin.math.max(state.selectionStart, state.selectionEnd)
                    val selectedText = state.text.substring(start, end)
                    val clip = android.content.ClipData.newPlainText("JoyType", selectedText)
                    clipboard.setPrimaryClip(clip)
                }
            }
            "Cut" -> {
                if (state != null && state.selectionStart != state.selectionEnd) {
                    val start = kotlin.math.min(state.selectionStart, state.selectionEnd)
                    val end = kotlin.math.max(state.selectionStart, state.selectionEnd)
                    val selectedText = state.text.substring(start, end)
                    val clip = android.content.ClipData.newPlainText("JoyType", selectedText)
                    clipboard.setPrimaryClip(clip)
                    transmitter.commitText("") // Delete natively
                }
                shouldCollapse = false
            }
            "Paste" -> {
                if (clipboard.hasPrimaryClip()) {
                    val textToPaste = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                    if (textToPaste.isNotEmpty()) transmitter.commitText(textToPaste)
                }
                shouldCollapse = false
            }
            "Select Word" -> {
                if (state != null) {
                    val text = state.text
                    val cursor = state.selectionStart
                    var start = cursor
                    while (start > 0 && text[start - 1].isLetterOrDigit()) start--
                    var end = cursor
                    while (end < text.length && text[end].isLetterOrDigit()) end++
                    if (start < end) {
                        transmitter.setSelection(start, end)
                        isHighlighting = true
                        highlightAnchorIndex = start
                        glideCursorIndex = end
                        return
                    }
                }
            }
            "Select All" -> {
                transmitter.performContextMenuAction(android.R.id.selectAll)
                isHighlighting = true
                return
            }
        }
        isHighlighting = false
        if (shouldCollapse) transmitter.setSelection(glideCursorIndex, glideCursorIndex)
        resetState()
    }

    // --- CORE MATH & ENGINE FEEDING ---
    private val t9Centers = mapOf(
        '1' to android.graphics.PointF(-1f, -1f), '2' to android.graphics.PointF(0f, -1f), '3' to android.graphics.PointF(1f, -1f),
        '4' to android.graphics.PointF(-1f, 0f),  '5' to android.graphics.PointF(0f, 0f),  '6' to android.graphics.PointF(1f, 0f),
        '7' to android.graphics.PointF(-1f, 1f),  '8' to android.graphics.PointF(0f, 1f),  '9' to android.graphics.PointF(1f, 1f)
    )

    private fun mapCircleToSquare(u: Float, v: Float): android.graphics.PointF {
        if (u == 0f && v == 0f) return android.graphics.PointF(0f, 0f)
        val radius = kotlin.math.sqrt(u * u + v * v)
        val normalizedRadius = radius.coerceAtMost(1f)
        val theta = kotlin.math.atan2(v, u)
        val scale = 1f / kotlin.math.max(kotlin.math.abs(kotlin.math.cos(theta)), kotlin.math.abs(kotlin.math.sin(theta)))
        val mappedRadius = normalizedRadius * scale
        return android.graphics.PointF(
            (mappedRadius * kotlin.math.cos(theta)).coerceIn(-1f, 1f),
            (mappedRadius * kotlin.math.sin(theta)).coerceIn(-1f, 1f)
        )
    }

    private fun getDistance(p1: android.graphics.PointF, x2: Float, y2: Float): Float {
        return kotlin.math.sqrt((x2 - p1.x) * (x2 - p1.x) + (y2 - p1.y) * (y2 - p1.y))
    }

    private fun generateProbabilityMap(pt: android.graphics.PointF): Map<Char, Float> {
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

    fun handleStrokeInput(rawX: Float, rawY: Float, mag: Float) {
        if (currentMode == InputMode.ABC) return

        val mapped = mapCircleToSquare(rawX, rawY)

        if (mag > 0.1f && t9Engine.currentStrokePath.isEmpty()) {
            registeredDebugPeaks.clear()
            lastDetectionType = ""
        }

        if (mag > 0.5f && !vibratedThisStroke) {
            vibratedThisStroke = true
            haptics.click() 
        }

        // --- PAIR INPUT MODE (Dual-Heuristic Detection) ---
        if (pairInputMode && t9Engine.currentStrokePath.isNotEmpty()) {
            if (peakPt == null || mag > peakMag) {
                peakPt = android.graphics.PointF(mapped.x, mapped.y)
                peakMag = mag
            }

            var triggeredPair = false

            if (mag < peakMag - 0.4f) { 
                inValley = true 
            } else if (inValley && mag > lastMag + 0.05f && mag > 0.45f) {
                triggeredPair = true 
                lastDetectionType = "Diagonal-slice"
            }

            if (!triggeredPair && peakPt != null && peakMag > 0.6f) { 
                val distFromPeak = getDistance(peakPt!!, mapped.x, mapped.y)
                if (distFromPeak > 0.85f && mag > 0.6f) {
                    triggeredPair = true 
                    lastDetectionType = "Rim-roll" 
                }
            }

            if (triggeredPair && peakPt != null) {
                t9Engine.wordProbabilities.add(generateProbabilityMap(peakPt!!))
                registeredDebugPeaks.add(android.graphics.PointF(peakPt!!.x, peakPt!!.y)) 
                
                predictiveRadialEngine.candidates = t9Engine.getProbabilisticPredictions(t9Engine.wordProbabilities)
                predictiveRadialEngine.setAbsoluteIndex(0)
                onUpdateUI()
                
                vibratedThisStroke = false 
                haptics.tick()
                
                t9Engine.currentStrokePath.clear()
                t9Engine.currentStrokePath.add(android.graphics.PointF(mapped.x, mapped.y))
                peakPt = null
                peakMag = 0f
                inValley = false
                lastMag = mag
                
                onUpdateDebugUI()
                return
            }
        }
        
        lastMag = mag

        // --- STROKE COMPLETION ---
        if (mag < 0.1f) {
            vibratedThisStroke = false 
            peakPt = null
            peakMag = 0f
            inValley = false
            lastMag = 0f

            if (t9Engine.currentStrokePath.isNotEmpty()) {
                val maxPt = t9Engine.currentStrokePath.maxByOrNull { kotlin.math.sqrt(it.x * it.x + it.y * it.y) }
                if (maxPt != null && kotlin.math.sqrt(maxPt.x * maxPt.x + maxPt.y * maxPt.y) > 0.01f) {
                    
                    if (currentMode == InputMode.PRE) {
                        t9Engine.wordProbabilities.add(generateProbabilityMap(maxPt))
                        registeredDebugPeaks.add(maxPt) 
                        if (lastDetectionType.isEmpty()) lastDetectionType = "Normal flick" 
                        predictiveRadialEngine.candidates = t9Engine.getProbabilisticPredictions(t9Engine.wordProbabilities)
                        predictiveRadialEngine.setAbsoluteIndex(0)
                        onUpdateUI()
                    } else {
                        val digitMap = generateProbabilityMap(maxPt)
                        val sortedDigits = digitMap.entries.sortedByDescending { it.value }
                        val winningDigit = sortedDigits.getOrNull(0)?.key ?: '5'
                        val runnerUpDigit = sortedDigits.getOrNull(1)?.key
                        
                        val chars = mutableListOf<String>()
                        val injectChars = { targetDigit: Char ->
                            for (c in t9Engine.getCharsForDigit(targetDigit)) {
                                chars.add(c.uppercaseChar().toString()) 
                                chars.add(c.toString())                 
                            }
                            chars.add(targetDigit.toString())           
                        }
                        
                        injectChars(winningDigit)
                        if (runnerUpDigit != null) injectChars(runnerUpDigit)

                        abcRadialEngine.candidates = chars
                        abcRadialEngine.setAbsoluteIndex(0)
                        isRadialSelectorActive = false 
                        lastDetectionType = "Manual Entry"
                    }
                }
                t9Engine.currentStrokePath.clear()
                onUpdateUI()
                onUpdateDebugUI()
            }
            return
        }

        t9Engine.currentStrokePath.add(android.graphics.PointF(mapped.x, mapped.y))
        onUpdateDebugUI()
    }

    // --- THE ACTION PIPELINE ---
    fun executeAction(action: Action, isRepeat: Boolean = false) {
        val fireActionHaptic = { if (isRepeat) haptics.repeatTick() else haptics.click() }

        when (action) {
            Action.ACCEPT -> {
                commitCurrentSelection() // Funneled into the universal commit logic
                fireActionHaptic()
            }
            Action.BACKSPACE_WORD -> {
                fireActionHaptic()
                if (activeRadialEngine == utilityRadialEngine) {
                    saveUndoSnapshot()
                    transmitter.deleteSurroundingText(1, 0)
                    resetState()
                    return
                }

                if (t9Engine.wordProbabilities.isNotEmpty()) {
                    t9Engine.wordProbabilities.clear()
                    t9Engine.currentStrokePath.clear()
                    resetState() 
                } else {
                    saveUndoSnapshot()
                    val state = transmitter.getEditorState() ?: return
                    val textBefore = state.text.substring(0, state.selectionStart.coerceAtMost(state.text.length))
                    
                    if (textBefore.endsWith("\n")) {
                        transmitter.deleteSurroundingText(1, 0)
                    } else {
                        val spacesMatch = Regex("[ \\t]+$").find(textBefore) 
                        val spacesLen = spacesMatch?.value?.length ?: 0
                        val wordMatch = Regex("\\S+[ \\t]*$").find(textBefore)
                        val deleteLen = wordMatch?.value?.length ?: spacesLen
                        if (deleteLen > 0) transmitter.deleteSurroundingText(deleteLen, 0)
                    }
                }
            }
            Action.RECOMPOSE -> {
                if (t9Engine.wordProbabilities.isNotEmpty()) return 
                saveUndoSnapshot()
                fireActionHaptic()

                val state = transmitter.getEditorState() ?: return
                val text = state.text
                val cursor = state.selectionStart

                val isWordChar = { c: Char -> c.isLetter() || c == '\'' }
                var searchCursor = cursor
                while (searchCursor > 0 && !isWordChar(text[searchCursor - 1])) searchCursor--

                var start = searchCursor
                while (start > 0 && isWordChar(text[start - 1])) start--
                var end = searchCursor
                while (end < text.length && isWordChar(text[end])) end++

                if (start < end) {
                    val targetWord = text.substring(start, end)
                    val deleteEnd = kotlin.math.max(end, cursor)

                    val leftLen = cursor - start
                    val rightLen = deleteEnd - cursor
                    transmitter.deleteSurroundingText(leftLen, rightLen)

                    t9Engine.wordProbabilities.clear()
                    t9Engine.currentStrokePath.clear()
                    
                    var restoredState = false
                    if (t9Engine.lastAcceptedProbabilities.size == targetWord.length) {
                        val testPredictions = t9Engine.getProbabilisticPredictions(t9Engine.lastAcceptedProbabilities)
                        if (testPredictions.any { it.equals(targetWord, ignoreCase = true) }) {
                            t9Engine.wordProbabilities.addAll(t9Engine.lastAcceptedProbabilities)
                            restoredState = true
                        }
                    }
                    
                    if (!restoredState) {
                        for (digit in t9Engine.wordToSequence(targetWord)) {
                            t9Engine.wordProbabilities.add(mapOf(digit to 1.0f))
                        }
                    }

                    val newPredictions = t9Engine.getProbabilisticPredictions(t9Engine.wordProbabilities).toMutableList()
                    if (targetWord.isNotEmpty() && targetWord[0].isUpperCase()) {
                        for (i in newPredictions.indices) newPredictions[i] = newPredictions[i].replaceFirstChar { it.uppercase() }
                    }

                    if (newPredictions.isEmpty() || newPredictions.none { it.equals(targetWord, ignoreCase = true) }) {
                        newPredictions.add(0, targetWord)
                    }
                    
                    predictiveRadialEngine.candidates = newPredictions
                    val foundIndex = newPredictions.indexOfFirst { it.equals(targetWord, ignoreCase = true) }
                    predictiveRadialEngine.setAbsoluteIndex(if (foundIndex != -1) foundIndex else 0)
                    
                    isRadialSelectorActive = false 
                    currentMode = InputMode.PRE 
                    onUpdateUI()
                }
            }
            Action.ADD_SPACE -> {
                saveUndoSnapshot()
                fireActionHaptic()

                if (activeRadialEngine == utilityRadialEngine) {
                    transmitter.commitText(" ")
                    resetState()
                    return
                }

                val now = System.currentTimeMillis()
                val engine = activeRadialEngine
                val targetString = if (engine.candidates.isNotEmpty()) engine.candidates[engine.absoluteIndex] else ""
                
                if (targetString.isNotEmpty()) {
                    if (currentMode == InputMode.PRE) {
                        var wordToCommit = getAutoCapitalizedWord(targetString)
                        if (autoSpace) wordToCommit += " " 
                        transmitter.commitText(wordToCommit)
                    } else {
                        transmitter.commitText(targetString)
                    }
                    resetState()
                } else {
                    // THE FIX: Ignore held keys (!isRepeat) so holding Space doesn't spawn periods!
                    if (currentMode == InputMode.PRE && doubleAcceptPeriod && (now - lastManualSpaceTime < 500) && !isRepeat) {
                        val state = transmitter.getEditorState()
                        val textBefore = state?.text?.substring(0, state.selectionStart) ?: ""
                        val spacesMatch = Regex("\\s+$").find(textBefore)
                        
                        transmitter.beginBatchEdit()
                        if (spacesMatch != null) transmitter.deleteSurroundingText(spacesMatch.value.length, 0)
                        transmitter.commitText(". ")
                        transmitter.endBatchEdit()
                        lastManualSpaceTime = 0L
                    } else if (currentMode == InputMode.ABC && engine.candidates == ABC_DIGITS) {
                        val str = if (engine.candidates.isNotEmpty()) engine.candidates[engine.absoluteIndex] else ""
                        if (str.isNotEmpty()) diveIntoAbcStage2(str)
                        return 
                    } else {
                        transmitter.commitText(" ")
                        // THE FIX: Only update the timestamp for manual presses, not repeats!
                        if (currentMode == InputMode.PRE && !isRepeat) lastManualSpaceTime = now
                    }
                }
            }
            Action.CLEAR_TEXT -> {
                saveUndoSnapshot()
                fireActionHaptic()
                transmitter.performContextMenuAction(android.R.id.selectAll)
                transmitter.commitText("")
            }
            Action.UNDO -> {
                if (undoStack.isNotEmpty()) {
                    fireActionHaptic()
                    val state = transmitter.getEditorState()
                    if (state != null) redoStack.push(state)
                    
                    val previousState = undoStack.pop()
                    transmitter.beginBatchEdit()
                    transmitter.performContextMenuAction(android.R.id.selectAll)
                    transmitter.commitText(previousState.text)
                    transmitter.setSelection(previousState.selectionStart, previousState.selectionEnd)
                    glideCursorIndex = previousState.selectionEnd
                    transmitter.endBatchEdit()

                    resetState()

                    if (previousState.selectionStart != previousState.selectionEnd) {
                        isHighlighting = true
                        highlightAnchorIndex = previousState.selectionStart
                        glideCursorIndex = previousState.selectionEnd
                        onUpdateUI()
                    }
                }
            }
            Action.REDO -> {
                if (redoStack.isNotEmpty()) {
                    fireActionHaptic()
                    val state = transmitter.getEditorState()
                    if (state != null) undoStack.push(state)
                    
                    val nextState = redoStack.pop()
                    transmitter.beginBatchEdit()
                    transmitter.performContextMenuAction(android.R.id.selectAll)
                    transmitter.commitText(nextState.text)
                    transmitter.setSelection(nextState.selectionStart, nextState.selectionEnd)
                    glideCursorIndex = nextState.selectionEnd
                    transmitter.endBatchEdit()

                    resetState()

                    if (nextState.selectionStart != nextState.selectionEnd) {
                        isHighlighting = true
                        highlightAnchorIndex = nextState.selectionStart
                        glideCursorIndex = nextState.selectionEnd
                        onUpdateUI()
                    }
                }
            }
            Action.ENTER -> {
                saveUndoSnapshot()
                fireActionHaptic()
                // Natively triggers "Search/Go" if applicable, otherwise a newline
                transmitter.performEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED)
                transmitter.commitText("\n") 
            }
            Action.BACKSPACE_STROKE -> {
                fireActionHaptic()
                if (currentMode == InputMode.ABC && abcRadialEngine.candidates != ABC_DIGITS) {
                    resetState()
                    return
                }

                if (activeRadialEngine == utilityRadialEngine) {
                    saveUndoSnapshot()
                    transmitter.deleteSurroundingText(1, 0) // Emulator-safe delete wrapper
                    resetState()
                    return
                }

                if (t9Engine.wordProbabilities.isNotEmpty()) {
                    t9Engine.wordProbabilities.removeAt(t9Engine.wordProbabilities.size - 1)
                    if (t9Engine.wordProbabilities.isEmpty()) {
                        resetState()
                    } else {
                        predictiveRadialEngine.candidates = t9Engine.getProbabilisticPredictions(t9Engine.wordProbabilities)
                        predictiveRadialEngine.setAbsoluteIndex(0)
                        onUpdateUI()
                    }
                } else {
                    saveUndoSnapshot()
                    transmitter.deleteSurroundingText(1, 0)
                }
            }
            Action.CLOSE_KEYBOARD -> {
                fireActionHaptic()
                onHideKeyboard()
            }
            Action.CURSOR_WORD_LEFT -> {
                fireActionHaptic()
                val state = transmitter.getEditorState() ?: return
                val textBefore = state.text.substring(0, state.selectionStart.coerceAtMost(state.text.length))
                val match = Regex("\\s*\\S+\\s*$").find(textBefore)
                val jumpLength = match?.value?.length ?: textBefore.length
                for(i in 0 until jumpLength) transmitter.sendKeyPress(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            }
            Action.CURSOR_WORD_RIGHT -> {
                fireActionHaptic()
                val state = transmitter.getEditorState() ?: return
                val textAfter = state.text.substring(state.selectionEnd.coerceAtLeast(0))
                val match = Regex("^\\s*\\S+").find(textAfter)
                val jumpLength = match?.value?.length ?: textAfter.length
                for(i in 0 until jumpLength) transmitter.sendKeyPress(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            }
            Action.CYCLE_FWD -> {
                fireActionHaptic()
                activeRadialEngine.cycleForward()
            }
            Action.CYCLE_BACK -> {
                fireActionHaptic()
                activeRadialEngine.cycleBackward()
            }
            Action.TOGGLE_MODE -> {
                if (t9Engine.wordProbabilities.isNotEmpty()) {
                    haptics.thud() 
                    return
                }
                fireActionHaptic()
                currentMode = when (currentMode) {
                    InputMode.PRE -> InputMode.ABC
                    InputMode.ABC -> InputMode.MACRO
                    InputMode.MACRO -> InputMode.PRE
                }
                resetState() 
            }
            Action.ADD_TO_DICT -> {
                fireActionHaptic()
                val state = transmitter.getEditorState() ?: return
                val text = state.text
                val cursor = state.selectionStart

                var start = cursor
                while (start > 0 && text[start - 1].isLetterOrDigit()) start--
                var end = cursor
                while (end < text.length && text[end].isLetterOrDigit()) end++

                if (start < end) {
                    val targetWord = text.substring(start, end)
                    t9Engine.addCustomWord(targetWord, context)
                }
            }
            Action.TOGGLE_HIGHLIGHT -> {
                fireActionHaptic()
                isHighlighting = !isHighlighting
                val state = transmitter.getEditorState() ?: return
                
                if (isHighlighting) {
                    highlightAnchorIndex = state.selectionStart
                    glideCursorIndex = highlightAnchorIndex
                    utilityRadialEngine.setAbsoluteIndex(0) 
                } else {
                    highlightAnchorIndex = -1
                    transmitter.setSelection(glideCursorIndex, glideCursorIndex) 
                }
                onUpdateUI()
            }
            Action.OPEN_SETTINGS -> {
                val intent = android.content.Intent(context, SettingsActivity::class.java)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            Action.NONE -> {}
        }
    }

    // Unifies R1 (Action.ACCEPT) and M1-Release into a single, bulletproof pipeline
    fun commitCurrentSelection() {
        val engine = activeRadialEngine
        val targetString = if (engine.candidates.isNotEmpty()) engine.candidates[engine.absoluteIndex] else ""

        if (targetString.isNotEmpty()) {
            when (engine) {
                macroRadialEngine -> {
                    // handleMacroSelection(engine.absoluteIndex) 
                    return 
                }
                utilityRadialEngine -> {
                    val isDisabled = transmitter.isHardwareSpoofingRequired && targetString in listOf("Copy", "Cut", "Select Word", "Select All")
                    if (!isDisabled) {
                        executeUtilityCommand(targetString)
                        return 
                    }
                }
                predictiveRadialEngine -> {
                    saveUndoSnapshot()
                    if (targetString in SPECIAL_CHARS) {
                        val clingyPunctuation = listOf(".", ",", "?", "!", ":", ";", ")", "]", "}")
                        if (clingyPunctuation.contains(targetString)) {
                            transmitter.beginBatchEdit()
                            val state = transmitter.getEditorState()
                            val textBefore = state?.text?.substring(0, state.selectionStart) ?: ""
                            if (textBefore.endsWith(" ")) transmitter.deleteSurroundingText(1, 0)
                            
                            transmitter.commitText(targetString)
                            if (autoSpace) transmitter.commitText(" ")
                            transmitter.endBatchEdit()
                        } else {
                            transmitter.commitText(targetString)
                        }
                    } else {
                        val wordToCommit = getAutoCapitalizedWord(targetString)
                        transmitter.commitText(wordToCommit)
                        if (autoSpace) transmitter.commitText(" ")
                    }
                }
                abcRadialEngine -> {
                    if (targetString in ABC_DIGITS) {
                        diveIntoAbcStage2(targetString)
                        return 
                    } else {
                        transmitter.commitText(targetString)
                    }
                }
            }
        } else {
            // Empty string commit? (Usually handled by double-space logic in ADD_SPACE, but fallback to single space here)
            transmitter.commitText(" ")
        }
        resetState()
    }
}