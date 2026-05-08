package com.vazbloke.joytype

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.view.KeyEvent

class CustomDictionaryActivity : AppCompatActivity() {

    private lateinit var engine: T9Engine
    private var wordsList = mutableListOf<String>()
    private lateinit var adapter: WordAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_dictionary)

        engine = T9Engine()
        wordsList = engine.getAllCustomWords().toMutableList()

        val listView = findViewById<ListView>(R.id.list_words)
        adapter = WordAdapter()
        listView.adapter = adapter

        findViewById<ImageButton>(R.id.btn_add_word).setOnClickListener {
            showWordDialog(null, -1)
        }
    }

    private fun showWordDialog(existingWord: String?, index: Int) {
        val input = EditText(this)
        input.setText(existingWord ?: "")
        input.setSingleLine()
        
        // 1. Tell the keyboard to display an "Enter/Done" action button
        input.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI

        val title = if (existingWord == null) "Add Custom Word" else "Edit Word"

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newWord = input.text.toString().trim().lowercase()
                if (newWord.isNotEmpty() && newWord.all { it in 'a'..'z' || it == '\'' }) {
                    if (index == -1) {
                        if (!wordsList.contains(newWord)) wordsList.add(newWord)
                    } else {
                        wordsList[index] = newWord
                    }
                    saveAndRefresh()
                } else {
                    android.widget.Toast.makeText(this@CustomDictionaryActivity, "Invalid characters", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        // 2. THE FIX: Wire up the Enter/R2 key to programmatically click "Save"
        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE || 
               (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                true
            } else {
                false
            }
        }

        // 3. THE FIX: Force the keyboard open immediately when the dialog appears
        dialog.setOnShowListener {
            input.requestFocus()
            
            // Explicitly clear flags that prevent the keyboard from interacting with dialogs
            dialog.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            
            // Wait exactly 100ms for the UI to settle, then aggressively summon the keyboard
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }, 100)
        }

        dialog.show()
    }

    private fun saveAndRefresh() {
        wordsList.sort()
        engine.overwriteCustomDictionary(wordsList)
        adapter.notifyDataSetChanged()
        
        // Force the live keyboard service to reload its memory!
        val intent = android.content.Intent("com.vazbloke.joytype.RELOAD_DICT")
        sendBroadcast(intent)
    }

    inner class WordAdapter : BaseAdapter() {
        override fun getCount(): Int = wordsList.size
        override fun getItem(p0: Int): Any = wordsList[p0]
        override fun getItemId(p0: Int): Long = p0.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@CustomDictionaryActivity).inflate(R.layout.item_custom_word, parent, false)
            
            val tvWord = view.findViewById<TextView>(R.id.tv_word)
            val btnEdit = view.findViewById<ImageButton>(R.id.btn_edit)
            val btnDelete = view.findViewById<ImageButton>(R.id.btn_delete)

            val word = wordsList[position]
            tvWord.text = word

            btnEdit.setOnClickListener { showWordDialog(word, position) }
            
            btnDelete.setOnClickListener {
                wordsList.removeAt(position)
                saveAndRefresh()
            }

            return view
        }
    }
}