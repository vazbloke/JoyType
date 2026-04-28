package com.vazbloke.t9controller

import android.content.Context
import android.os.Environment
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.math.log

class TrieNode(
    var isWord: Boolean = false,
    var frequency: Int = 0,
    val children: MutableMap<Char, TrieNode> = mutableMapOf()
)

class T9Engine {
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

    private val root = TrieNode()
    private var allWordsList = mutableListOf<String>()

    fun loadDictionary(context: Context) {
        // 1. Load Base Dictionary
        try {
            val inputStream = context.assets.open("en.csv")
            val reader = BufferedReader(InputStreamReader(inputStream))
            reader.forEachLine { line ->
                val parts = line.split("\t", ",")
                if (parts.isNotEmpty()) {
                    val word = parts[0].lowercase()
                    if (word.all { it in 'a'..'z' }) {
                        val freq = if (parts.size > 1) parts[1].toIntOrNull() ?: 0 else 0
                        insertWord(word, freq)
                        allWordsList.add(word)
                    }
                }
            }
            inputStream.close()
        } catch (e: Exception) { e.printStackTrace() }

        // 2. Load Custom Dictionary
        try {
            val customFile = getCustomDictFile()
            if (customFile.exists()) {
                customFile.forEachLine { line ->
                    val parts = line.split("\t", ",")
                    if (parts.isNotEmpty()) {
                        val word = parts[0].trim().lowercase()
                        if (word.isNotEmpty() && word.all { it in 'a'..'z' }) {
                            insertWord(word, 999999)
                            allWordsList.add(word)
                        }
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun insertWord(word: String, frequency: Int) {
        var current = root
        for (char in word) {
            if (!current.children.containsKey(char)) {
                current.children[char] = TrieNode()
            }
            current = current.children[char]!!
        }
        current.isWord = true
        current.frequency = maxOf(current.frequency, frequency)
    }

    /**
     * BEAM SEARCH HMM IMPLEMENTATION
     * Takes a list of probability maps (one map per joystick inflection).
     * Explores the Trie, dropping highly improbable paths.
     */
    fun getProbabilisticPredictions(inputProbabilities: List<Map<Char, Float>>, beamWidth: Int = 15): List<String> {
        if (inputProbabilities.isEmpty()) return emptyList()

        // State: (CurrentNode, WordSoFar, CumulativeLogProbability)
        var beam = listOf(Triple(root, "", 0.0f))

        for (probabilityMap in inputProbabilities) {
            val nextBeam = mutableListOf<Triple<TrieNode, String, Float>>()

            for ((node, wordSoFar, logProb) in beam) {
                // For every possible digit the user might have meant
                for ((digit, prob) in probabilityMap) {
                    if (prob < 0.02f) continue // Prune absolute noise

                    val chars = digitToChars[digit] ?: continue
                    val transitionLogProb = log(prob.toDouble(), 10.0).toFloat()

                    // Try every character assigned to that digit
                    for (char in chars) {
                        if (node.children.containsKey(char)) {
                            val childNode = node.children[char]!!
                            nextBeam.add(Triple(childNode, wordSoFar + char, logProb + transitionLogProb))
                        }
                    }
                }
            }

            // Sort by probability and keep only the top paths (The Beam)
            beam = nextBeam.sortedByDescending { it.third }.take(beamWidth)
            if (beam.isEmpty()) break
        }

        // Return the words from valid terminal nodes, sorted by their frequency and probability
        return beam.filter { it.first.isWord }
            .sortedByDescending { it.third + log(it.first.frequency.toDouble() + 1, 10.0).toFloat() }
            .map { it.second }
    }

    // Fallback for strict deterministic typing (LJOY_RBUTTONS mode)
    fun getPredictions(sequence: String): List<String> {
        val deterministicProbs = sequence.map { digit -> mapOf(digit to 1.0f) }
        return getProbabilisticPredictions(deterministicProbs)
    }

    fun getCharsForDigit(digit: Char): List<Char> = digitToChars[digit] ?: emptyList()
    fun getAllWords(): List<String> = allWordsList

    private fun getCustomDictFile(): File {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val t9Dir = File(downloadsDir, "T9Controller")
        if (!t9Dir.exists()) t9Dir.mkdirs()
        return File(t9Dir, "customdictionary.csv")
    }

    fun addCustomWord(word: String) {
        insertWord(word.lowercase(), 999999)
        try { getCustomDictFile().appendText("${word.lowercase()},999999\n") } catch (e: Exception) {}
    }

    // Change this from private to public in T9Engine.kt
    fun wordToSequence(word: String): String {
        return word.map { charToDigit[it] ?: '0' }.joinToString("")
    }
}