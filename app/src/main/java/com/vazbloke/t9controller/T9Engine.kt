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
    private val charToDigit = mapOf(
        '\'' to '1', 'j' to '1', 'k' to '1', 'l' to '1', // NEW: Option A mapping
        'a' to '2', 'b' to '2', 'c' to '2',
        'd' to '3', 'e' to '3', 'f' to '3',
        'g' to '4', 'h' to '4', 'i' to '4',
        // '5' is now empty
        'm' to '6', 'n' to '6', 'o' to '6',
        'p' to '7', 'q' to '7', 'r' to '7', 's' to '7',
        't' to '8', 'u' to '8', 'v' to '8',
        'w' to '9', 'x' to '9', 'y' to '9', 'z' to '9'
    )

    private val digitToChars = mapOf(
        '1' to listOf('\'', 'j', 'k', 'l'), // NEW
        '2' to listOf('a', 'b', 'c'),
        '3' to listOf('d', 'e', 'f'),
        '4' to listOf('g', 'h', 'i'),
        // No '5' mapping required
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
                    // FIX: Allow apostrophes through the filter!
                    if (word.all { it in 'a'..'z' || it == '\'' }) {
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
                        // FIX: Allow apostrophes here too
                        if (word.isNotEmpty() && word.all { it in 'a'..'z' || it == '\'' }) {
                            insertWord(word, 999999)
                            allWordsList.add(word)
                        }
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        // 3. FIX: Hardcode foundational 1-letter words so they never fail
        insertWord("i", 999999)
        insertWord("a", 999999)
        if (!allWordsList.contains("i")) allWordsList.add("i")
        if (!allWordsList.contains("a")) allWordsList.add("a")
    }

    private fun insertWord(word: String, frequency: Int) {
        var current = root
        for (char in word) {
            if (!current.children.containsKey(char)) {
                current.children[char] = TrieNode()
            }
            current = current.children[char]!!
            // FIX: Propagate the frequency to prefix nodes so the beam search
            // knows this path leads to a common word.
            current.frequency = maxOf(current.frequency, frequency)
        }
        current.isWord = true
        // current.frequency = maxOf(current.frequency, frequency) -> Can be removed
    }

    /**
     * BEAM SEARCH HMM IMPLEMENTATION
     * Takes a list of probability maps (one map per joystick inflection).
     * Explores the Trie, dropping highly improbable paths.
     */

    // UPDATE THIS FUNCTION SIGNATURE AND LOGIC
    // Notice it now takes inputProbabilities of specific letters, not digits

    fun getProbabilisticPredictions(inputProbabilities: List<Map<Char, Float>>, beamWidth: Int = 50): List<String> {
        if (inputProbabilities.isEmpty()) return emptyList()

        var beam = listOf(Triple(root, "", 0.0f))

        // The Forgiveness Dial
        val freqWeight = 1.5f

        for (probabilityMap in inputProbabilities) {
            val nextBeam = mutableListOf<Triple<TrieNode, String, Float>>()

            for ((node, wordSoFar, logProb) in beam) {
                // REVERTED: We are iterating over DIGITS again!
                for ((digit, prob) in probabilityMap) {
                    if (prob < 0.01f) continue

                    // Map the digit back to its letters (e.g. '5' -> 'j', 'k', 'l')
                    val chars = digitToChars[digit] ?: continue
                    val transitionLogProb = kotlin.math.log(prob.toDouble(), 10.0).toFloat()

                    // Try every character assigned to that digit
                    // Try every character assigned to that digit
                    for (char in chars) {
                        // 1. STANDARD PATH: The node has the character directly
                        if (node.children.containsKey(char)) {
                            val childNode = node.children[char]!!
                            nextBeam.add(Triple(childNode, wordSoFar + char, logProb + transitionLogProb))
                        }

                        // 2. AUTO-APOSTROPHE PATH:
                        // If the node has an apostrophe, peek inside it to see if our target character is there!
                        if (node.children.containsKey('\'')) {
                            val apoNode = node.children['\'']!!
                            if (apoNode.children.containsKey(char)) {
                                val childNode = apoNode.children[char]!!
                                // Append BOTH the hidden apostrophe and the user's character.
                                // We subtract a tiny 0.1f penalty just so the engine slightly prefers
                                // literal matches (like "cant" vs "can't" if both exist), but frequency usually wins.
                                nextBeam.add(Triple(childNode, wordSoFar + "'" + char, logProb + transitionLogProb - 0.1f))
                            }
                        }
                    }
                }
            }

            beam = nextBeam.sortedByDescending {
                it.third + (freqWeight * kotlin.math.log(it.first.frequency.toDouble() + 1, 10.0).toFloat())
            }.take(beamWidth)

            if (beam.isEmpty()) break
        }

        return beam.filter { it.first.isWord }
            .sortedByDescending { it.third + (freqWeight * kotlin.math.log(it.first.frequency.toDouble() + 1, 10.0).toFloat()) }
            // ... inside getProbabilisticPredictions:
            // Make sure you update the end of the return statement from .take(6) to .take(8)
            .map { it.second }
            .take(8) // We need 8 to fill the radial UI!
    }

    // Fallback for strict deterministic typing (LJOY_RBUTTONS mode)
    fun getPredictions(sequence: String): List<String> {
        // REVERTED: Back to mapping raw digits
        val deterministicProbs = sequence.map { digit -> mapOf(digit to 1.0f) }
        return getProbabilisticPredictions(deterministicProbs)
    }

 // Fallback for strict deterministic typing (LJOY_RBUTTONS mode)

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