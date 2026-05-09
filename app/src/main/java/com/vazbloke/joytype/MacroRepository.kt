package com.vazbloke.joytype

import android.content.SharedPreferences
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

object MacroRepository {
    private const val PREF_KEY = "saved_macro_library"

    // Data class lives here
    data class Macro(val name: String, val sequence: List<Int>)

    fun loadMacros(prefs: SharedPreferences): List<Macro> {
        val jsonString = prefs.getString(PREF_KEY, null)
        
        // If no JSON exists, save and return the default library
        if (jsonString == null) {
            val defaults = getDefaultLibrary()
            saveMacros(prefs, defaults)
            return defaults
        }

        val macros = mutableListOf<Macro>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val name = obj.getString("name")
                
                val seqArray = obj.getJSONArray("sequence")
                val sequence = mutableListOf<Int>()
                for (j in 0 until seqArray.length()) {
                    sequence.add(seqArray.getInt(j))
                }
                macros.add(Macro(name, sequence))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return getDefaultLibrary() // Failsafe
        }
        return macros
    }

    fun saveMacros(prefs: SharedPreferences, macros: List<Macro>) {
        val jsonArray = JSONArray()
        for (macro in macros) {
            val obj = JSONObject()
            obj.put("name", macro.name)
            
            val seqArray = JSONArray()
            for (code in macro.sequence) {
                seqArray.put(code)
            }
            obj.put("sequence", seqArray)
            
            jsonArray.put(obj)
        }
        prefs.edit().putString(PREF_KEY, jsonArray.toString()).apply()
    }

    // A helper function to turn user-typed strings ("MPKFA") into hardware keycodes
    fun textToKeySequence(text: String): List<Int> {
        val codes = mutableListOf<Int>()
        for (char in text.uppercase()) {
            if (char in 'A'..'Z') codes.add(KeyEvent.KEYCODE_A + (char - 'A'))
            else if (char in '0'..'9') codes.add(KeyEvent.KEYCODE_0 + (char - '0'))
            else if (char == ' ') codes.add(KeyEvent.KEYCODE_SPACE)
        }
        return codes
    }

    private fun getDefaultLibrary() = listOf(
        Macro("Claw: God Mode", listOf(KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_A)),
        Macro("Doom: IDDQD", listOf(KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_D))
    )
}