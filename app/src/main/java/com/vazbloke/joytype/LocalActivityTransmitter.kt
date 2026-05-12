package com.vazbloke.joytype

import android.widget.EditText

class LocalActivityTransmitter(private val editText: EditText) : OutputTransmitter {
    
    // We don't need hardware spoofing for a local text box
    override val isHardwareSpoofingRequired = false 

    override fun sendGamepadState(buttons: Int, leftX: Float, leftY: Float, rightX: Float, rightY: Float) {} // Ignored locally

    override fun commitText(text: String) {
        val start = editText.selectionStart.coerceAtLeast(0)
        val end = editText.selectionEnd.coerceAtLeast(0)
        editText.text.replace(kotlin.math.min(start, end), kotlin.math.max(start, end), text)
    }

    override fun deleteSurroundingText(leftLength: Int, rightLength: Int) {
        val start = (editText.selectionStart - leftLength).coerceAtLeast(0)
        val end = (editText.selectionEnd + rightLength).coerceAtMost(editText.text.length)
        if (start < end) editText.text.delete(start, end)
    }

    override fun sendKeyPress(keyCode: Int, requiresShift: Boolean) {
        // Ignored for the local sandbox, but implemented for interface compliance
    }

    override fun getEditorState(): EditorStateSnapshot {
        return EditorStateSnapshot(
            text = editText.text.toString(),
            selectionStart = editText.selectionStart,
            selectionEnd = editText.selectionEnd
        )
    }

    override fun setSelection(start: Int, end: Int) {
        if (start >= 0 && end <= editText.text.length) {
            editText.setSelection(start, end)
        }
    }

    override fun beginBatchEdit() {}
    override fun endBatchEdit() {}
    override fun performEditorAction(actionId: Int) {}
    override fun performContextMenuAction(id: Int) {}
}