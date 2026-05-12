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

    // Master list of KeyCodes useful for emulator/cheat chaining
    private val availableKeyCodes = mapOf(
        "ENTER" to KeyEvent.KEYCODE_ENTER,
        "SPACE" to KeyEvent.KEYCODE_SPACE,
        "UP ARROW" to KeyEvent.KEYCODE_DPAD_UP,
        "DOWN ARROW" to KeyEvent.KEYCODE_DPAD_DOWN,
        "LEFT ARROW" to KeyEvent.KEYCODE_DPAD_LEFT,
        "RIGHT ARROW" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "ESCAPE" to KeyEvent.KEYCODE_ESCAPE,
        "TAB" to KeyEvent.KEYCODE_TAB,
        "F1" to KeyEvent.KEYCODE_F1,
        "F2" to KeyEvent.KEYCODE_F2,
        "F3" to KeyEvent.KEYCODE_F3,
        "F4" to KeyEvent.KEYCODE_F4,
        "F5" to KeyEvent.KEYCODE_F5,
        "F6" to KeyEvent.KEYCODE_F6,
        "F7" to KeyEvent.KEYCODE_F7,
        "F8" to KeyEvent.KEYCODE_F8,
        "F9" to KeyEvent.KEYCODE_F9,
        "F10" to KeyEvent.KEYCODE_F10,
        "F11" to KeyEvent.KEYCODE_F11,
        "F12" to KeyEvent.KEYCODE_F12,
        "INSERT" to KeyEvent.KEYCODE_INSERT,
        "DELETE" to KeyEvent.KEYCODE_FORWARD_DEL,
        "HOME" to KeyEvent.KEYCODE_MOVE_HOME,
        "END" to KeyEvent.KEYCODE_MOVE_END,
        "PAGE UP" to KeyEvent.KEYCODE_PAGE_UP,
        "PAGE DOWN" to KeyEvent.KEYCODE_PAGE_DOWN
    )

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
            hint = "Macro Name (e.g., God Mode)"
            setText(existingMacro?.name ?: "")
        }
        
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MacroManagerActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Chain Sequence", "Pasteboard Text"))
        }

        val dynamicContentContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // --- PASTEBOARD UI ---
        val pasteboardInput = EditText(this).apply {
            hint = "Text to instantly paste..."
            if (existingMacro is MacroRepository.Macro.Pasteboard) {
                setText(existingMacro.text)
            }
        }

        // --- CHAIN UI ---
        val chainItemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val btnAddNode = Button(this).apply { text = "+ Add Block" }
        val chainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(chainItemsContainer)
            addView(btnAddNode)
        }

        fun addTextNode(content: String) {
            val row = LinearLayout(this@MacroManagerActivity).apply { 
                orientation = LinearLayout.HORIZONTAL
                tag = "TEXT" 
                setPadding(0, 10, 0, 10)
            }
            val et = EditText(this@MacroManagerActivity).apply { 
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                hint = "Text string..."
                setText(content)
                setSingleLine()
            }
            val btnDel = Button(this@MacroManagerActivity).apply { 
                text = "X"
                setOnClickListener { chainItemsContainer.removeView(row) }
            }
            row.addView(et)
            row.addView(btnDel)
            chainItemsContainer.addView(row)
        }

        fun addKeyNode(code: Int, name: String) {
            val row = LinearLayout(this@MacroManagerActivity).apply { 
                orientation = LinearLayout.HORIZONTAL
                tag = code // We store the pure KeyCode integer in the tag for retrieval!
                setPadding(0, 10, 0, 10)
            } 
            val tv = TextView(this@MacroManagerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = "[KEY: $name]"
                setTextColor(android.graphics.Color.parseColor("#E6C229"))
                textSize = 16f
                setPadding(10, 20, 10, 20)
            }
            val btnDel = Button(this@MacroManagerActivity).apply { 
                text = "X"
                setOnClickListener { chainItemsContainer.removeView(row) }
            }
            row.addView(tv)
            row.addView(btnDel)
            chainItemsContainer.addView(row)
        }

        // Initialize existing Chain data
        if (existingMacro is MacroRepository.Macro.Chain) {
            existingMacro.nodes.forEach { node ->
                when (node) {
                    is MacroRepository.ChainNode.Text -> addTextNode(node.content)
                    is MacroRepository.ChainNode.KeyCode -> {
                        val name = availableKeyCodes.entries.firstOrNull { it.value == node.code }?.key ?: "UNKNOWN (${node.code})"
                        addKeyNode(node.code, name)
                    }
                }
            }
        } else if (existingMacro == null) {
            addTextNode("") // Default empty block
        }

        btnAddNode.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Add Chain Block")
                .setItems(arrayOf("Text String", "Special KeyCode")) { _, which ->
                    if (which == 0) {
                        addTextNode("")
                    } else {
                        val keyNames = availableKeyCodes.keys.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle("Select KeyCode")
                            .setItems(keyNames) { _, keyIndex ->
                                val selectedName = keyNames[keyIndex]
                                val selectedCode = availableKeyCodes[selectedName]!!
                                addKeyNode(selectedCode, selectedName)
                            }.show()
                    }
                }.show()
        }

        // --- SPINNER TOGGLE LOGIC ---
        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                dynamicContentContainer.removeAllViews()
                if (position == 0) {
                    dynamicContentContainer.addView(chainLayout)
                } else {
                    dynamicContentContainer.addView(pasteboardInput)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Set initial spinner state
        if (existingMacro is MacroRepository.Macro.Pasteboard) {
            typeSpinner.setSelection(1)
        } else {
            typeSpinner.setSelection(0)
        }

        layout.addView(inputName)
        layout.addView(typeSpinner)
        layout.addView(dynamicContentContainer)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existingMacro == null) "New Macro" else "Edit Macro")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newName = inputName.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val newMacro = if (typeSpinner.selectedItemPosition == 0) {
                    // Extract Chain Data
                    val parsedNodes = mutableListOf<MacroRepository.ChainNode>()
                    for (i in 0 until chainItemsContainer.childCount) {
                        val row = chainItemsContainer.getChildAt(i) as LinearLayout
                        val tag = row.tag
                        if (tag == "TEXT") {
                            val et = row.getChildAt(0) as EditText
                            val content = et.text.toString()
                            if (content.isNotEmpty()) parsedNodes.add(MacroRepository.ChainNode.Text(content))
                        } else if (tag is Int) {
                            parsedNodes.add(MacroRepository.ChainNode.KeyCode(tag))
                        }
                    }
                    MacroRepository.Macro.Chain(newName, parsedNodes)
                } else {
                    // Extract Pasteboard Data
                    val text = pasteboardInput.text.toString()
                    MacroRepository.Macro.Pasteboard(newName, text)
                }
                
                if (index >= 0) macros[index] = newMacro else macros.add(newMacro)
                saveAndRefresh()
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
        macros = MacroRepository.loadMacros(prefs).toMutableList() 
        refreshList()
    }

    private fun refreshList() {
        val displayNames = macros.map { 
            // The compiler is now perfectly happy with this exhaustive 'when'!
            val typeStr = when(it) {
                is MacroRepository.Macro.Chain -> "[Chain]"
                is MacroRepository.Macro.Pasteboard -> "[Paste]"
            }
            "$typeStr ${it.name}" 
        }
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayNames)
        listView.adapter = adapter
    }
}