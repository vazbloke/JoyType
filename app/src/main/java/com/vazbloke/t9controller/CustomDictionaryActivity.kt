package com.vazbloke.t9controller

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

        val title = if (existingWord == null) "Add Custom Word" else "Edit Word"

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newWord = input.text.toString().trim().lowercase()
                // Validate to ensure only a-z and apostrophes
                if (newWord.isNotEmpty() && newWord.all { it in 'a'..'z' || it == '\'' }) {
                    if (index == -1) {
                        if (!wordsList.contains(newWord)) wordsList.add(newWord)
                    } else {
                        wordsList[index] = newWord
                    }
                    saveAndRefresh()
                } else {
                    android.widget.Toast.makeText(this, "Invalid characters", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveAndRefresh() {
        wordsList.sort()
        engine.overwriteCustomDictionary(wordsList)
        adapter.notifyDataSetChanged()
        
        // Force the live keyboard service to reload its memory!
        val intent = android.content.Intent("com.vazbloke.t9controller.RELOAD_DICT")
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