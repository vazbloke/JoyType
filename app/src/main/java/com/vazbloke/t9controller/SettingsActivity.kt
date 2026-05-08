package com.vazbloke.t9controller

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.AttributeSet
import android.view.KeyEvent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceViewHolder

class LongClickPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    var onPreferenceLongClick: (() -> Unit)? = null
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.setOnLongClickListener {
            onPreferenceLongClick?.invoke()
            true
        }
    }
}

// --- Action Key Preference (Includes Dialog Logic for Exclusivity) ---
class KeyBindingPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    init { widgetLayoutResource = R.layout.widget_modifier_spinner }
    var currentModifier: String = "NONE"
    var onModifierChanged: ((String) -> Unit)? = null
    var onPreferenceLongClick: (() -> Unit)? = null

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.setOnLongClickListener {
            onPreferenceLongClick?.invoke()
            true
        }

        val modText = holder.findViewById(R.id.mod_text) as? TextView
        modText?.text = "▼ $currentModifier" // Arrow perfectly on the left!
        
        modText?.setOnClickListener {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val joyRadial = prefs.getString("joy_radial_mod", "NONE")
            val joyCursor = prefs.getString("joy_cursor_mod", "NONE")
            
            val options = arrayOf("NONE", "M1", "M2", "M3")
            val displayOptions = options.map { 
                if (it != "NONE" && (it == joyRadial || it == joyCursor)) "$it (Joystick)" else it 
            }.toTypedArray()

            android.app.AlertDialog.Builder(context)
                .setItems(displayOptions) { _, which ->
                    val selected = options[which]
                    if (selected != "NONE" && (selected == joyRadial || selected == joyCursor)) {
                        android.widget.Toast.makeText(context, "Modifier used for joystick. Unset to use here.", android.widget.Toast.LENGTH_LONG).show()
                    } else if (selected != currentModifier) {
                        currentModifier = selected
                        modText.text = "▼ $currentModifier"
                        onModifierChanged?.invoke(selected)
                    }
                }.show()
        }
    }
}

// --- Joystick Preference (Includes Dialog Logic for Exclusivity) ---
class InlineSpinnerPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    init { widgetLayoutResource = R.layout.widget_modifier_spinner }
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val modText = holder.findViewById(R.id.mod_text) as? TextView
        
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val currentVal = prefs.getString(key, "NONE") ?: "NONE"
        modText?.text = "▼ $currentVal"

        modText?.setOnClickListener {
            val actionModKeys = listOf("mod_accept", "mod_cycle_prev", "mod_backspace_word", "mod_backspace_stroke", "mod_add_space", "mod_clear_text", "mod_enter", "mod_undo", "mod_close", "mod_open_settings", "mod_word_left", "mod_word_right")
            val usedByActions = actionModKeys.mapNotNull { prefs.getString(it, "NONE") }.filter { it != "NONE" }.toSet()
            
            val otherJoyKey = if (key == "joy_radial_mod") "joy_cursor_mod" else "joy_radial_mod"
            val usedByOtherJoy = prefs.getString(otherJoyKey, "NONE") ?: "NONE"

            val options = arrayOf("NONE", "M1", "M2", "M3")
            val displayOptions = options.map {
                if (it != "NONE") {
                    if (it == usedByOtherJoy) "$it (Other Joystick feature)"
                    else if (usedByActions.contains(it)) "$it (Action Keys)"
                    else it
                } else it
            }.toTypedArray()

            android.app.AlertDialog.Builder(context)
                .setItems(displayOptions) { _, which ->
                    val selected = options[which]
                    if (selected != "NONE" && (selected == usedByOtherJoy || usedByActions.contains(selected))) {
                        android.widget.Toast.makeText(context, "Modifier already in use. Unset elsewhere first.", android.widget.Toast.LENGTH_LONG).show()
                    } else if (selected != currentVal) {
                        prefs.edit().putString(key, selected).apply()
                        modText.text = "▼ $selected"
                    }
                }.show()
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

        val actionKeys = listOf(
            "key_accept", "key_cycle_fwd", "key_cycle_back", "key_cycle_prev", 
            "key_backspace_word", "key_backspace_stroke", 
            "key_add_space", "key_clear_text", "key_enter", 
            "key_undo", "key_close", "key_open_settings", "key_word_left", "key_word_right", "key_toggle_mode", "key_add_to_dict"
        )

        val modKeys = listOf("key_mod_1", "key_mod_2", "key_mod_3")
        val allKeys = modKeys + actionKeys

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

            val dictPref = findPreference<Preference>("manage_custom_dict")
            dictPref?.setOnPreferenceClickListener {
                startActivity(android.content.Intent(requireContext(), CustomDictionaryActivity::class.java))
                true
            }

            // Added key_mod_1 and key_mod_2 to the listenable list!
            val bindableKeys = listOf(
                "key_mod_1", "key_mod_2", 
                "key_accept", "key_cycle_prev", "key_backspace_word",
                "key_backspace_stroke", "key_add_space", "key_clear_text", "key_enter", 
                "key_undo", "key_close", "key_open_settings"
            )

            for (key in allKeys) {
                val pref = findPreference<Preference>(key)

                val defaultKey = DefaultBindings.MAP[key] ?: -1
                val currentCode = prefs.getInt(key, defaultKey)

                pref?.summary = if (currentCode != -1) "Bound to: ${getKeyName(currentCode)}" else "Unbound"

                val unbindAction = {
                    prefs.edit().putInt(key, -1).apply()
                    pref?.summary = "Unbound"
                    if (listeningKey == key) listeningKey = null 
                }

                if (pref is KeyBindingPreference) {
                    val modKey = key.replace("key_", "mod_")
                    pref.currentModifier = prefs.getString(modKey, "NONE") ?: "NONE"
                    
                    pref.onModifierChanged = { newMod -> 
                        val keyCode = prefs.getInt(key, -1)
                        if (keyCode != -1) {
                            // If modifier changes, check for new clash with existing combos
                            for(otherKey in actionKeys) {
                                if (otherKey != key && prefs.getInt(otherKey, -2) == keyCode) {
                                    if (prefs.getString(otherKey.replace("key_", "mod_"), "NONE") == newMod) {
                                        prefs.edit().putInt(otherKey, -1).apply()
                                        findPreference<Preference>(otherKey)?.summary = "Unbound"
                                    }
                                }
                            }
                        }
                        prefs.edit().putString(modKey, newMod).apply() 
                    }
                    pref.onPreferenceLongClick = unbindAction
                } else if (pref is LongClickPreference) {
                    pref.onPreferenceLongClick = unbindAction
                }

                pref?.setOnPreferenceClickListener { clickedPref ->
                    if (listeningKey == null) {
                        listeningKey = clickedPref.key
                        originalSummary = clickedPref.summary
                        clickedPref.summary = "Press key to bind... (Back to cancel, long press to unbind)"
                    }
                    true
                }
            }
        }

        fun bindKey(keyCode: Int) {
            val key = listeningKey ?: return
            
            // GLOBAL CLASH PREVENTION LOGIC
            if (key.startsWith("key_mod_")) {
                // Core Modifiers cannot share keys with anything. Period.
                for (otherKey in allKeys) {
                    if (otherKey != key && prefs.getInt(otherKey, -2) == keyCode) {
                        prefs.edit().putInt(otherKey, -1).apply()
                        findPreference<Preference>(otherKey)?.summary = "Unbound"
                    }
                }
            } else {
                // Action keys can share buttons AS LONG AS the modifier is different
                val thisMod = prefs.getString(key.replace("key_", "mod_"), "NONE")
                for (otherKey in allKeys) {
                    if (otherKey == key) continue
                    if (prefs.getInt(otherKey, -2) == keyCode) {
                        if (otherKey.startsWith("key_mod_")) {
                            // Cannot clash with a core modifier button
                            prefs.edit().putInt(otherKey, -1).apply()
                            findPreference<Preference>(otherKey)?.summary = "Unbound"
                        } else {
                            // Clash occurs if they share a button AND the same modifier
                            val otherMod = prefs.getString(otherKey.replace("key_", "mod_"), "NONE")
                            if (thisMod == otherMod) {
                                prefs.edit().putInt(otherKey, -1).apply()
                                findPreference<Preference>(otherKey)?.summary = "Unbound"
                            }
                        }
                    }
                }
            }

            prefs.edit().putInt(key, keyCode).apply()
            findPreference<Preference>(key)?.summary = "Bound to: ${getKeyName(keyCode)}"
            listeningKey = null
        }

        fun cancelListening() {
            val key = listeningKey ?: return
            findPreference<Preference>(key)?.summary = originalSummary
            listeningKey = null
        }

        private fun getKeyName(keyCode: Int): String {
            return KeyEvent.keyCodeToString(keyCode).replace("KEYCODE_", "").replace("BUTTON_", "").replace("_", " ")
        }
    }
}