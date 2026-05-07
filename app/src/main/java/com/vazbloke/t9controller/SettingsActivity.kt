package com.vazbloke.t9controller

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceViewHolder

// --- Preference for Action Keys (Has Bindable Key + Spinner) ---
class KeyBindingPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    init { widgetLayoutResource = R.layout.widget_modifier_spinner }
    var currentModifier: String = "NONE"
    var onModifierChanged: ((String) -> Unit)? = null

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val spinner = holder.findViewById(R.id.mod_spinner) as? Spinner
        spinner?.let {
            val adapter = ArrayAdapter(context, R.layout.spinner_item_small, arrayOf("NONE", "M1", "M2"))
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            it.adapter = adapter
            it.setSelection(adapter.getPosition(currentModifier))
            it.isFocusable = false

            it.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selected = adapter.getItem(position) ?: "NONE"
                    if (selected != currentModifier) {
                        currentModifier = selected
                        onModifierChanged?.invoke(selected)
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }
}

// --- NEW: Preference for Joystick (Strict Spinner ONLY, No binding) ---
class InlineSpinnerPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    init { widgetLayoutResource = R.layout.widget_modifier_spinner }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val spinner = holder.findViewById(R.id.mod_spinner) as? Spinner
        spinner?.let {
            val adapter = ArrayAdapter(context, R.layout.spinner_item_small, arrayOf("M1", "M2")) // Strict options
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            it.adapter = adapter
            
            val currentVal = sharedPreferences?.getString(key, "M1") ?: "M1"
            it.setSelection(adapter.getPosition(currentVal))
            it.isFocusable = false

            it.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selected = adapter.getItem(position) ?: "M1"
                    if (selected != currentVal) {
                        sharedPreferences?.edit()?.putString(key, selected)?.apply()
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }
}

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager.beginTransaction().replace(android.R.id.content, SettingsFragment()).commit()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val fragment = supportFragmentManager.findFragmentById(android.R.id.content) as? SettingsFragment
        if (fragment?.listeningKey != null) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    fragment.cancelListening()
                    return true
                }
                fragment.bindKey(event.keyCode)
                return true 
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

            // Added key_mod_1 and key_mod_2 to the listenable list!
            val bindableKeys = listOf(
                "key_mod_1", "key_mod_2", 
                "key_accept", "key_cycle_prev", "key_backspace_word", "key_backspace_char",
                "key_backspace_stroke", "key_add_space", "key_clear_text", "key_enter", 
                "key_undo", "key_close", "key_open_settings"
            )

            val defaultMappings = mapOf(
                "key_mod_1" to KeyEvent.KEYCODE_BUTTON_C,
                "key_mod_2" to KeyEvent.KEYCODE_BUTTON_Z,
                "key_accept" to KeyEvent.KEYCODE_BUTTON_R1,
                "key_cycle_prev" to KeyEvent.KEYCODE_BUTTON_X,
                "key_backspace_word" to KeyEvent.KEYCODE_BUTTON_Y,
                "key_backspace_char" to -1,
                "key_backspace_stroke" to KeyEvent.KEYCODE_BUTTON_B,
                "key_add_space" to KeyEvent.KEYCODE_BUTTON_A,
                "key_clear_text" to -1,
                "key_enter" to KeyEvent.KEYCODE_BUTTON_R2,
                "key_undo" to KeyEvent.KEYCODE_BUTTON_THUMBL,
                "key_close" to KeyEvent.KEYCODE_BUTTON_SELECT,
                "key_open_settings" to KeyEvent.KEYCODE_BUTTON_START
            )

            for (key in bindableKeys) {
                val pref = findPreference<Preference>(key)
                val currentCode = prefs.getInt(key, defaultMappings[key] ?: -1)
                pref?.summary = if (currentCode != -1) "Bound to: ${getKeyName(currentCode)}" else "Unbound"

                // Only attach spinner logic if it's an action key with a spinner
                if (pref is KeyBindingPreference) {
                    val modKey = key.replace("key_", "mod_")
                    pref.currentModifier = prefs.getString(modKey, "NONE") ?: "NONE"
                    pref.onModifierChanged = { newMod ->
                        prefs.edit().putString(modKey, newMod).apply()
                    }
                }

                // All bindable keys (Modifiers AND Actions) get the listening click event
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