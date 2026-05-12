package com.vazbloke.joytype

// A universal state snapshot so the engine doesn't rely strictly on Android's ExtractedText
data class EditorStateSnapshot(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int
)

interface OutputTransmitter {
    val isHardwareSpoofingRequired: Boolean
    fun commitText(text: String)
    fun deleteSurroundingText(leftLength: Int, rightLength: Int)
    fun sendKeyPress(keyCode: Int, requiresShift: Boolean = false)
    
    // UI & Action Commands
    fun setSelection(start: Int, end: Int)
    fun beginBatchEdit()
    fun endBatchEdit()
    fun performEditorAction(actionId: Int)
    fun performContextMenuAction(id: Int) 

    // Blind State checking
    fun getEditorState(): EditorStateSnapshot?
}