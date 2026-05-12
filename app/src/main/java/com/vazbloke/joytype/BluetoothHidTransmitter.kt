package com.vazbloke.joytype

import android.content.Context

class BluetoothHidTransmitter(private val context: Context) : OutputTransmitter {

    override val isHardwareSpoofingRequired: Boolean
        get() {
            // val editorInfo = ims.currentInputEditorInfo
            // return editorInfo == null || editorInfo.inputType == android.text.InputType.TYPE_NULL
            return false
        }

    override fun commitText(text: String) {
        // TODO: Map string to HID Byte Arrays and transmit via BluetoothHidDevice
    }

    override fun deleteSurroundingText(leftLength: Int, rightLength: Int) {
        // Bluetooth can't reach forward to delete rightLength, only backspace
        for (i in 0 until leftLength) {
            sendKeyPress(android.view.KeyEvent.KEYCODE_DEL)
        }
    }

    override fun sendKeyPress(keyCode: Int, requiresShift: Boolean) {
        // TODO: Map Android Keycode to HID byte array
    }

    // --- BLIND STATE ---
    override fun getEditorState(): EditorStateSnapshot? = null 
    override fun setSelection(start: Int, end: Int) {}
    override fun beginBatchEdit() {}
    override fun endBatchEdit() {}
    override fun performEditorAction(actionId: Int) {}
    override fun performContextMenuAction(id: Int) {}
}