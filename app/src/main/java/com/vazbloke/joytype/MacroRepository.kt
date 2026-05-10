package com.vazbloke.joytype

import android.content.SharedPreferences
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

object MacroRepository {
    private const val PREF_KEY = "saved_macro_library"

    sealed class Macro {
        abstract val name: String

        // Type 1: Hardware Keystrokes (Needs precise timing/delays)
        data class Keystroke(
            override val name: String, 
            val sequence: List<Int>
        ) : Macro()

        // Type 2: Pasteboard (Fire-and-forget text)
        data class Pasteboard(
            override val name: String, 
            val text: String
        ) : Macro()

        // Type 3: Lua Script (Advanced logic)
        data class LuaScript(
            override val name: String, 
            val scriptContent: String
        ) : Macro()
    }

    fun loadMacros(prefs: SharedPreferences): List<Macro> {
        val jsonString = prefs.getString(PREF_KEY, null)
        
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
                
                // Smart fallback for old V1 macros
                val type = if (obj.has("type")) obj.getString("type") else "keystroke"

                when (type) {
                    "keystroke" -> {
                        val seqArray = obj.getJSONArray("sequence")
                        val sequence = mutableListOf<Int>()
                        for (j in 0 until seqArray.length()) {
                            sequence.add(seqArray.getInt(j))
                        }
                        macros.add(Macro.Keystroke(name, sequence))
                    }
                    "pasteboard" -> macros.add(Macro.Pasteboard(name, obj.optString("text", "")))
                    "luascript" -> macros.add(Macro.LuaScript(name, obj.optString("scriptContent", "")))
                }
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
            
            when (macro) {
                is Macro.Keystroke -> {
                    obj.put("type", "keystroke")
                    val seqArray = JSONArray()
                    macro.sequence.forEach { seqArray.put(it) }
                    obj.put("sequence", seqArray)
                }
                is Macro.Pasteboard -> {
                    obj.put("type", "pasteboard")
                    obj.put("text", macro.text)
                }
                is Macro.LuaScript -> {
                    obj.put("type", "luascript")
                    obj.put("scriptContent", macro.scriptContent)
                }
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(PREF_KEY, jsonArray.toString()).apply()
    }

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
        Macro.Keystroke("Claw: God Mode", listOf(KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_A)),
        Macro.Pasteboard("Paste Test", "player.additem 0000000f 100")
    )
}