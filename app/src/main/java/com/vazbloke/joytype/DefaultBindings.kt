package com.vazbloke.joytype

import android.view.KeyEvent

object DefaultBindings {
    val MAP = mapOf(
        "key_mod_1" to KeyEvent.KEYCODE_BUTTON_C,
        "key_mod_2" to KeyEvent.KEYCODE_BUTTON_Z,
        "key_mod_3" to -1,
        "key_accept" to KeyEvent.KEYCODE_BUTTON_R1,
        "key_recompose" to KeyEvent.KEYCODE_BUTTON_L1, 
        "key_backspace_word" to KeyEvent.KEYCODE_BUTTON_Y,
        "key_backspace_stroke" to KeyEvent.KEYCODE_BUTTON_B,
        "key_add_space" to KeyEvent.KEYCODE_BUTTON_A,
        "key_clear_text" to -1,
        "key_enter" to KeyEvent.KEYCODE_BUTTON_R2,
        "key_undo" to KeyEvent.KEYCODE_BUTTON_X,
        "key_close" to KeyEvent.KEYCODE_BUTTON_SELECT,
        "key_open_settings" to KeyEvent.KEYCODE_BUTTON_START,
        "key_word_left" to -1,
        "key_word_right" to -1,
        "key_cycle_fwd" to -1,
        "key_cycle_back" to -1,
        "key_toggle_mode" to KeyEvent.KEYCODE_BUTTON_L2,
        "key_add_to_dict" to KeyEvent.KEYCODE_BUTTON_THUMBR
    )
}