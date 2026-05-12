package com.vazbloke.joytype

import android.content.SharedPreferences
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

object MacroRepository {
    private const val PREF_KEY = "saved_macro_library"

    // Strict constraint: Only two types of macros exist in the ecosystem
    sealed class Macro {
        abstract val name: String
        data class Chain(override val name: String, val nodes: List<ChainNode>) : Macro()
        data class Pasteboard(override val name: String, val text: String) : Macro()
    }

    sealed class ChainNode {
        data class Text(val content: String) : ChainNode()
        data class KeyCode(val code: Int) : ChainNode()
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
                val type = obj.getString("type")

                when (type) {
                    "Pasteboard" -> {
                        macros.add(Macro.Pasteboard(name, obj.optString("text", "")))
                    }
                    "Chain" -> {
                        val nodesArray = obj.getJSONArray("nodes")
                        val nodesList = mutableListOf<ChainNode>()
                        for (j in 0 until nodesArray.length()) {
                            val nodeObj = nodesArray.getJSONObject(j)
                            val nodeType = nodeObj.getString("nodeType")
                            if (nodeType == "Text") {
                                nodesList.add(ChainNode.Text(nodeObj.getString("content")))
                            } else if (nodeType == "KeyCode") {
                                nodesList.add(ChainNode.KeyCode(nodeObj.getInt("code")))
                            }
                        }
                        macros.add(Macro.Chain(name, nodesList))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return getDefaultLibrary() // Failsafe against corrupted JSON
        }
        return macros
    }

    fun saveMacros(prefs: SharedPreferences, macros: List<Macro>) {
        val jsonArray = JSONArray()
        for (macro in macros) {
            val obj = JSONObject()
            obj.put("name", macro.name)
            
            when (macro) {
                is Macro.Pasteboard -> {
                    obj.put("type", "Pasteboard")
                    obj.put("text", macro.text)
                }
                is Macro.Chain -> {
                    obj.put("type", "Chain")
                    val nodesArray = JSONArray()
                    for (node in macro.nodes) {
                        val nodeObj = JSONObject()
                        when (node) {
                            is ChainNode.Text -> {
                                nodeObj.put("nodeType", "Text")
                                nodeObj.put("content", node.content)
                            }
                            is ChainNode.KeyCode -> {
                                nodeObj.put("nodeType", "KeyCode")
                                nodeObj.put("code", node.code)
                            }
                        }
                        nodesArray.put(nodeObj)
                    }
                    obj.put("nodes", nodesArray)
                }
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(PREF_KEY, jsonArray.toString()).apply()
    }

    private fun getDefaultLibrary() = listOf(
        Macro.Chain("Claw: God Mode", listOf(
            ChainNode.KeyCode(KeyEvent.KEYCODE_M),
            ChainNode.KeyCode(KeyEvent.KEYCODE_P),
            ChainNode.KeyCode(KeyEvent.KEYCODE_K),
            ChainNode.KeyCode(KeyEvent.KEYCODE_F),
            ChainNode.KeyCode(KeyEvent.KEYCODE_A)
        )),
        Macro.Pasteboard("Paste Test", "player.additem 0000000f 100")
    )
}