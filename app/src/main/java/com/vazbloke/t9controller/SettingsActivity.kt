package com.vazbloke.t9controller

import android.content.SharedPreferences
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager
            .beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
    }

    // Intercept hardware key events before they reach the UI
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val fragment = supportFragmentManager.findFragmentById(android.R.id.content) as? SettingsFragment
        if (fragment?.listeningKey != null) {
            // Ignore joystick movements that register as d-pad events
            if (event.action == KeyEvent.ACTION_DOWN) {
                // If they press back/B button to cancel, just cancel listening
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    fragment.cancelListening()
                    return true
                }
                fragment.bindKey(event.keyCode)
                return true // Consume the input
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        var listeningKey: String? = null
        private lateinit var prefs: SharedPreferences
        private var originalSummary: CharSequence? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

            val bindableKeys = listOf(
                // "key_cycle_fwd", "key_cycle_back", 
                "key_accept",
                "key_cycle_prev", "key_backspace_word", "key_backspace_char", "key_backspace_stroke",
                "key_add_space", "key_clear_text", "key_undo", "key_open_settings",
                "key_enter" // NEW
            )

            // Default fallback mappings if never set
            val defaultMappings = mapOf(
                // "key_cycle_fwd" to KeyEvent.KEYCODE_BUTTON_R1,
                // "key_cycle_back" to KeyEvent.KEYCODE_BUTTON_L2,
                "key_accept" to KeyEvent.KEYCODE_BUTTON_R1,
                "key_cycle_prev" to KeyEvent.KEYCODE_BUTTON_X,
                "key_backspace_word" to KeyEvent.KEYCODE_BUTTON_Y,
                // "key_backspace_char" to KeyEvent.KEYCODE_BUTTON_B,
                "key_backspace_stroke" to KeyEvent.KEYCODE_BUTTON_B, // NEW
                "key_add_space" to KeyEvent.KEYCODE_BUTTON_A,
                "key_clear_text" to KeyEvent.KEYCODE_BUTTON_SELECT,
                "key_undo" to KeyEvent.KEYCODE_BUTTON_THUMBL,
                "key_open_settings" to KeyEvent.KEYCODE_BUTTON_START, // Moved to Select
                "key_enter" to KeyEvent.KEYCODE_BUTTON_R2 // NEW
            )

            for (key in bindableKeys) {
                val pref = findPreference<Preference>(key)
                val currentCode = prefs.getInt(key, defaultMappings[key] ?: -1)
                pref?.summary = "Bound to: ${getKeyName(currentCode)}"

                pref?.setOnPreferenceClickListener { clickedPref ->
                    if (listeningKey == null) {
                        listeningKey = clickedPref.key
                        originalSummary = clickedPref.summary
                        clickedPref.summary = "Press any key to bind... (Press Back to cancel)"
                    }
                    true
                }
            }
        }

        fun bindKey(keyCode: Int) {
            val key = listeningKey ?: return
            prefs.edit().putInt(key, keyCode).apply()

            val pref = findPreference<Preference>(key)
            pref?.summary = "Bound to: ${getKeyName(keyCode)}"

            listeningKey = null
        }

        fun cancelListening() {
            val key = listeningKey ?: return
            val pref = findPreference<Preference>(key)
            pref?.summary = originalSummary
            listeningKey = null
        }

        private fun getKeyName(keyCode: Int): String {
            return KeyEvent.keyCodeToString(keyCode)
                .replace("KEYCODE_", "")
                .replace("BUTTON_", "")
                .replace("_", " ")
        }
    }
}