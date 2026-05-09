package com.vazbloke.joytype

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import android.view.KeyEvent

class MacroManagerActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var macros: MutableList<MacroRepository.Macro>
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // MVP Layout constructed dynamically to save you writing an XML file
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val btnAdd = Button(this).apply { text = "+ Add New Macro" }
        listView = ListView(this)

        root.addView(btnAdd)
        root.addView(listView)
        setContentView(root)

        // Load existing macros
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        macros = MacroRepository.loadMacros(prefs).toMutableList()

        refreshList()

        // Handle Add Button
        btnAdd.setOnClickListener {
            showMacroDialog(null, -1)
        }

        // Handle Edit/Delete on click
        listView.setOnItemClickListener { _, _, position, _ ->
            showMacroDialog(macros[position], position)
        }
    }

    private fun showMacroDialog(existingMacro: MacroRepository.Macro?, index: Int) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 0)
        }

        val inputName = EditText(this).apply {
            hint = "Display Name (e.g., Infinite Ammo)"
            setText(existingMacro?.name ?: "")
        }
        
        val inputSequence = EditText(this).apply {
            hint = "Sequence (e.g., MPKFA)"
            // Convert existing KeyCodes back to a readable string for editing
            val existingStr = existingMacro?.sequence?.mapNotNull { 
                when (it) {
                    KeyEvent.KEYCODE_SPACE -> " "
                    in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A' + (it - KeyEvent.KEYCODE_A)).toString()
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + (it - KeyEvent.KEYCODE_0)).toString()
                    else -> null
                }
            }?.joinToString("") ?: ""
            
            setText(existingStr)
        }

        layout.addView(inputName)
        layout.addView(inputSequence)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existingMacro == null) "New Macro" else "Edit Macro")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newName = inputName.text.toString().trim()
                val newSeq = inputSequence.text.toString().trim()
                
                if (newName.isNotEmpty() && newSeq.isNotEmpty()) {
                    val newMacro = MacroRepository.Macro(newName, MacroRepository.textToKeySequence(newSeq))
                    
                    if (index >= 0) macros[index] = newMacro
                    else macros.add(newMacro)
                    
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
        refreshList()
    }

    private fun refreshList() {
        val displayNames = macros.map { it.name }
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayNames)
        listView.adapter = adapter
    }
}