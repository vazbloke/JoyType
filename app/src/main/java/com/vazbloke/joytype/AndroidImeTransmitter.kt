package com.vazbloke.joytype

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AndroidImeTransmitter(private val ims: InputMethodService) : OutputTransmitter {

    override val isHardwareSpoofingRequired: Boolean
        get() {
            val editorInfo = ims.currentInputEditorInfo
            return editorInfo == null || editorInfo.inputType == android.text.InputType.TYPE_NULL
        }
    
    override fun sendGamepadState(buttons: Int, leftX: Float, leftY: Float, rightX: Float, rightY: Float) {} // Ignored locally
    override fun sendMouseState(leftClick: Boolean, rightClick: Boolean, dx: Int, dy: Int) {}

    private val hardwareTypingMutex = Mutex()

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

    override fun commitText(text: String) {
        val ic = ims.currentInputConnection ?: return
        val editorInfo = ims.currentInputEditorInfo
        val requiresHardwareKeys = editorInfo == null || editorInfo.inputType == android.text.InputType.TYPE_NULL
        
        if (requiresHardwareKeys) {
            CoroutineScope(Dispatchers.Main).launch {
                hardwareTypingMutex.withLock {
                    for (char in text) {
                        val keyCode = charToKeyCode(char)
                        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) continue
                        val useShift = requiresShift(char)
                        
                        if (useShift) ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT))
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                        delay(10)
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                        if (useShift) ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT))
                        delay(10)
                    }
                }
            }
        } else {
            ic.commitText(text, 1)
        }
    }

    override fun deleteSurroundingText(leftLength: Int, rightLength: Int) {
        val ic = ims.currentInputConnection ?: return
        val editorInfo = ims.currentInputEditorInfo
        val requiresHardwareKeys = editorInfo == null || editorInfo.inputType == android.text.InputType.TYPE_NULL
        
        if (requiresHardwareKeys) {
            CoroutineScope(Dispatchers.Main).launch {
                for (i in 0 until leftLength) {
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                    delay(10)
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
                    delay(10)
                }
            }
        } else {
            val selectedText = ic.getSelectedText(0)
            if (!selectedText.isNullOrEmpty()) {
                ic.commitText("", 1) 
            } else {
                // # This needs to be replaced
                ic.deleteSurroundingText(leftLength, rightLength)
            }
        }
    }

    override fun sendKeyPress(keyCode: Int, requiresShift: Boolean) {
        val ic = ims.currentInputConnection ?: return
        CoroutineScope(Dispatchers.Main).launch {
            if (requiresShift) ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            delay(10)
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            if (requiresShift) ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT))
        }
    }

    override fun getEditorState(): EditorStateSnapshot? {
        val extracted = ims.currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0) ?: return null
        return EditorStateSnapshot(
            text = extracted.text?.toString() ?: "",
            selectionStart = extracted.selectionStart,
            selectionEnd = extracted.selectionEnd
        )
    }

    override fun setSelection(start: Int, end: Int) { ims.currentInputConnection?.setSelection(start, end) }
    override fun beginBatchEdit() { ims.currentInputConnection?.beginBatchEdit() }
    override fun endBatchEdit() { ims.currentInputConnection?.endBatchEdit() }
    override fun performEditorAction(actionId: Int) { ims.currentInputConnection?.performEditorAction(actionId) }
    override fun performContextMenuAction(id: Int) { ims.currentInputConnection?.performContextMenuAction(id) }
}