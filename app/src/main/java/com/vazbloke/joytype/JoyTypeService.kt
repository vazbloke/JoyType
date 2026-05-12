package com.vazbloke.joytype

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

class JoyTypeService : InputMethodService() {

    private lateinit var transmitter: AndroidImeTransmitter
    private lateinit var frontend: JoyTypeFrontend

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        
        transmitter = AndroidImeTransmitter(this)
        frontend = JoyTypeFrontend(
            context = this,
            view = view,
            transmitter = transmitter,
            onHideKeyboard = { requestHideSelf(0) }
        )
        return view
    }

    override fun onWindowShown() {
        super.onWindowShown()
        if (::frontend.isInitialized) frontend.onResume()
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Ensure UI forces a redraw when a text box is focused
        if (::frontend.isInitialized) frontend.updateUI()
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!isInputViewShown) return super.onGenericMotionEvent(event)
        if (frontend.onGenericMotionEvent(event)) return true
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isInputViewShown) return super.onKeyDown(keyCode, event)
        if (frontend.onKeyDown(keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!isInputViewShown) return super.onKeyUp(keyCode, event)
        if (frontend.onKeyUp(keyCode, event)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (::frontend.isInitialized) frontend.onUpdateSelection(newSelStart, newSelEnd)
    }
}