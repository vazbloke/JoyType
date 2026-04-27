package com.vazbloke.t9controller

import android.content.Context
import android.os.Environment
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class T9Engine {
    private val charToDigit = mapOf(
        'a' to '2', 'b' to '2', 'c' to '2',
        'd' to '3', 'e' to '3', 'f' to '3',
        'g' to '4', 'h' to '4', 'i' to '4',
        'j' to '5', 'k' to '5', 'l' to '5',
        'm' to '6', 'n' to '6', 'o' to '6',
        'p' to '7', 'q' to '7', 'r' to '7', 's' to '7',
        't' to '8', 'u' to '8', 'v' to '8',
        'w' to '9', 'x' to '9', 'y' to '9', 'z' to '9'
    )

    private val digitToChars = mapOf(
        '2' to listOf('a', 'b', 'c'),
        '3' to listOf('d', 'e', 'f'),
        '4' to listOf('g', 'h', 'i'),
        '5' to listOf('j', 'k', 'l'),
        '6' to listOf('m', 'n', 'o'),
        '7' to listOf('p', 'q', 'r', 's'),
        '8' to listOf('t', 'u', 'v'),
        '9' to listOf('w', 'x', 'y', 'z')
    )

    private val dictionary = mutableMapOf<String, MutableList<Pair<String, Int>>>()
    private val customDictionary = mutableMapOf<String, MutableList<Pair<String, Int>>>()
    private var allWordsList = listOf<String>()

    // Locates or creates the T9Controller folder in the user's Downloads directory
    private fun getCustomDictFile(): File {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val t9Dir = File(downloadsDir, "T9Controller")
        if (!t9Dir.exists()) {
            t9Dir.mkdirs()
        }
        return File(t9Dir, "customdictionary.csv")
    }

    fun loadDictionary(context: Context) {
        val tempAllWords = mutableListOf<String>()

        // 1. Load the Base Dictionary (en.csv)
        try {
            val inputStream = context.assets.open("en.csv")
            val reader = BufferedReader(InputStreamReader(inputStream))
            reader.forEachLine { line ->
                val parts = line.split("\t", ",")
                if (parts.isNotEmpty()) {
                    val word = parts[0].lowercase()
                    if (word.all { it in 'a'..'z' }) {
                        val freq = if (parts.size > 1) parts[1].toIntOrNull() ?: 0 else 0
                        val sequence = wordToSequence(word)

                        if (!dictionary.containsKey(sequence)) {
                            dictionary[sequence] = mutableListOf()
                        }
                        dictionary[sequence]?.add(word to freq)
                        tempAllWords.add(word)
                    }
                }
            }
            dictionary.forEach { (_, words) -> words.sortByDescending { it.second } }
            inputStream.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Load the Custom User Dictionary from Downloads
        try {
            val customFile = getCustomDictFile()
            if (customFile.exists()) {
                customFile.forEachLine { line ->
                    val parts = line.split("\t", ",") // Supports both tab and comma formats
                    if (parts.isNotEmpty()) {
                        val word = parts[0].trim().lowercase()
                        if (word.isNotEmpty() && word.all { it in 'a'..'z' }) {
                            addCustomWordToMemory(word)
                            tempAllWords.add(word)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        allWordsList = tempAllWords
    }

    private fun wordToSequence(word: String): String {
        return word.map { charToDigit[it] ?: '0' }.joinToString("")
    }

    fun getPredictions(sequence: String): List<String> {
        if (sequence.isEmpty()) return emptyList()
        val custom = customDictionary[sequence]?.map { it.first } ?: emptyList()
        val default = dictionary[sequence]?.map { it.first } ?: emptyList()
        return (custom + default).distinct()
    }

    fun getCharsForDigit(digit: Char): List<Char> {
        return digitToChars[digit] ?: emptyList()
    }

    // Stores the word in RAM for immediate use
    private fun addCustomWordToMemory(word: String) {
        val sequence = wordToSequence(word)
        if (!customDictionary.containsKey(sequence)) {
            customDictionary[sequence] = mutableListOf()
        }
        // Check if it already exists to avoid duplicates
        if (customDictionary[sequence]?.none { it.first == word } == true) {
            customDictionary[sequence]?.add(0, word to 999999)
        }
    }

    // Saves the word to RAM and appends it to the external CSV file
    fun addCustomWord(word: String) {
        val cleanWord = word.lowercase()
        addCustomWordToMemory(cleanWord)

        try {
            val customFile = getCustomDictFile()
            // Append word with a high fake frequency so you can edit the CSV easily later
            customFile.appendText("$cleanWord,999999\n")
        } catch (e: Exception) {
            e.printStackTrace() // If permission isn't granted, it will fail silently here
        }
    }

    fun getAllWords(): List<String> = allWordsList
}