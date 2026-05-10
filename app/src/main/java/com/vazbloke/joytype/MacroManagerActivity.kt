package com.vazbloke.joytype

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager

class MacroManagerActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var macros: MutableList<MacroRepository.Macro>
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val btnAdd = Button(this).apply { text = "+ Add New Macro" }
        listView = ListView(this)

        root.addView(btnAdd)
        root.addView(listView)
        setContentView(root)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        macros = MacroRepository.loadMacros(prefs).toMutableList()

        refreshList()

        btnAdd.setOnClickListener { showMacroDialog(null, -1) }
        listView.setOnItemClickListener { _, _, position, _ -> showMacroDialog(macros[position], position) }
    }

    private fun showMacroDialog(existingMacro: MacroRepository.Macro?, index: Int) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 0)
        }

        val inputName = EditText(this).apply {
            hint = "Display Name"
            setText(existingMacro?.name ?: "")
        }
        
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MacroManagerActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Keystroke Sequence", "Pasteboard Text", "Lua Script"))
        }

        val inputContent = EditText(this).apply {
            val existingStr = when (existingMacro) {
                is MacroRepository.Macro.Keystroke -> {
                    existingMacro.sequence.mapNotNull { 
                        when (it) {
                            KeyEvent.KEYCODE_SPACE -> " "
                            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A' + (it - KeyEvent.KEYCODE_A)).toString()
                            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + (it - KeyEvent.KEYCODE_0)).toString()
                            else -> null
                        }
                    }.joinToString("")
                }
                is MacroRepository.Macro.Pasteboard -> existingMacro.text
                is MacroRepository.Macro.LuaScript -> existingMacro.scriptContent
                null -> ""
            }
            setText(existingStr)
        }

        // Set initial spinner state
        when (existingMacro) {
            is MacroRepository.Macro.Pasteboard -> typeSpinner.setSelection(1)
            is MacroRepository.Macro.LuaScript -> typeSpinner.setSelection(2)
            else -> typeSpinner.setSelection(0)
        }

        // Dynamically change hints based on selection
        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                inputContent.hint = when (position) {
                    0 -> "Sequence (e.g., MPKFA)"
                    1 -> "Text to paste (e.g., player.additem)"
                    else -> "Lua Code"
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        layout.addView(inputName)
        layout.addView(typeSpinner)
        layout.addView(inputContent)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existingMacro == null) "New Macro" else "Edit Macro")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newName = inputName.text.toString().trim()
                val newContent = inputContent.text.toString()
                
                if (newName.isNotEmpty() && newContent.isNotEmpty()) {
                    val newMacro = when (typeSpinner.selectedItemPosition) {
                        0 -> MacroRepository.Macro.Keystroke(newName, MacroRepository.textToKeySequence(newContent))
                        1 -> MacroRepository.Macro.Pasteboard(newName, newContent)
                        else -> MacroRepository.Macro.LuaScript(newName, newContent)
                    }
                    
                    if (index >= 0) macros[index] = newMacro else macros.add(newMacro)
                    saveAndRefresh()
                }
            }
            .setNegativeButton("Cancel", null)

        if (existingMacro != null) {
            dialog.setNeutralButton("Delete") { _, _ ->
                macros.removeAt(index)
                saveAndRefresh()
            }
        }
        dialog.show()
    }

    private fun saveAndRefresh() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        MacroRepository.saveMacros(prefs, macros)
        macros = MacroRepository.loadMacros(prefs).toMutableList() // Reload to sync
        refreshList()
    }

    private fun refreshList() {
        // Show type tag in the list for clarity
        val displayNames = macros.map { 
            val typeStr = when(it) {
                is MacroRepository.Macro.Keystroke -> "[Key]"
                is MacroRepository.Macro.Pasteboard -> "[Text]"
                is MacroRepository.Macro.LuaScript -> "[Lua]"
            }
            "$typeStr ${it.name}" 
        }
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayNames)
        listView.adapter = adapter
    }
}