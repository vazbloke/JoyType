package com.vazbloke.t9controller

import android.content.Context
import java.io.BufferedReader
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

    private val dictionary = mutableMapOf<String, MutableList<Pair<String, Int>>>()
    private var allWordsList = listOf<String>()

    fun loadDictionary(context: Context) {
        try {
            val inputStream = context.assets.open("en.csv")
            val reader = BufferedReader(InputStreamReader(inputStream))
            val tempAllWords = mutableListOf<String>()

            reader.forEachLine { line ->
                val parts = line.split("\t")
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
            
            // Sort each sequence group by frequency (descending)
            dictionary.forEach { (_, words) ->
                words.sortByDescending { it.second }
            }
            
            allWordsList = tempAllWords
            inputStream.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun wordToSequence(word: String): String {
        return word.map { charToDigit[it] ?: '0' }.joinToString("")
    }

    fun getPredictions(sequence: String): List<String> {
        if (sequence.isEmpty()) return emptyList()
        return dictionary[sequence]?.map { it.first } ?: listOf(sequence)
    }

    fun getAllWords(): List<String> = allWordsList
}
