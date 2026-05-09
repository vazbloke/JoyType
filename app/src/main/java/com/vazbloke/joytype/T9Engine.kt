package com.vazbloke.joytype

import android.content.Context
import android.os.Environment
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import kotlinx.coroutines.launch

class TrieNode(
    var isWord: Boolean = false,
    var frequency: Int = 0,
    val children: MutableMap<Char, TrieNode> = mutableMapOf()
)

class T9Engine {
    private val charToDigit = mapOf(
        'j' to '1', 'k' to '1', 'l' to '1',
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
        '1' to listOf('j', 'k', 'l'),
        '2' to listOf('a', 'b', 'c'),
        '3' to listOf('d', 'e', 'f'),
        '4' to listOf('g', 'h', 'i'),
        '6' to listOf('m', 'n', 'o'),
        '7' to listOf('p', 'q', 'r', 's'),
        '8' to listOf('t', 'u', 'v'),
        '9' to listOf('w', 'x', 'y', 'z')
    )

    private var root = TrieNode()
    private var allWordsList = mutableListOf<String>()
    private var customWordsList = mutableListOf<String>()

    // Generates a unique fingerprint based on the APK's update time and the custom dictionary's modified time
    private fun getCacheFingerprint(context: Context): String {
        val appUpdateTime = try {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        } catch (e: Exception) { 0L }
        val customDictTime = getCustomDictFile().lastModified()
        return "${appUpdateTime}_${customDictTime}"
    }

    fun loadDictionary(context: Context) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val currentFingerprint = getCacheFingerprint(context)
        val savedFingerprint = prefs.getString("dict_cache_fingerprint", "")
        val binFile = File(context.cacheDir, "dictionary_cache.bin")

        // --- THE FAST PATH (Binary Load) ---
        if (binFile.exists() && currentFingerprint == savedFingerprint) {
            try {
                DataInputStream(BufferedInputStream(binFile.inputStream())).use { input ->
                    root = readNode(input)
                    readStrings(input, allWordsList)
                    readStrings(input, customWordsList)
                }
                return // Successfully loaded from cache in milliseconds!
            } catch (e: Exception) {
                e.printStackTrace()
                // If the binary file is corrupted, fall through to the slow path
            }
        }

        // --- THE SLOW PATH (CSV Parsing) ---
        root.children.clear()
        allWordsList.clear()
        customWordsList.clear()

        // 1. Load Base Dictionary
        try {
            val inputStream = context.assets.open("en.csv")
            val reader = BufferedReader(InputStreamReader(inputStream))
            reader.forEachLine { line ->
                val parts = line.split("\t", ",")
                if (parts.isNotEmpty()) {
                    val word = parts[0].lowercase()
                    // Allow apostrophes through the filter!
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
                        // Allow apostrophes here too
                        if (word.isNotEmpty() && word.all { it in 'a'..'z' || it == '\'' }) {
                            insertWord(word, 999999)
                            customWordsList.add(word)
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

        // 3. Serialize to Binary Cache for the next boot
        try {
            DataOutputStream(BufferedOutputStream(binFile.outputStream())).use { out ->
                writeNode(out, root)
                writeStrings(out, allWordsList)
                writeStrings(out, customWordsList)
            }
            prefs.edit().putString("dict_cache_fingerprint", currentFingerprint).apply()
        } catch (e: Exception) { e.printStackTrace() }
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

        // --- REFINED: Deep-Path Lookahead (Prefix Completion) ---
        val exactMatches = beam.filter { it.first.isWord }.toMutableList()

        // THE FIX: ONLY trigger lookahead if there are absolutely ZERO exact matches!
        if (exactMatches.isEmpty()) {
            val completions = mutableListOf<Triple<TrieNode, String, Float>>()
            
            // Only branch off the top 5 most probable paths to save performance
            val topPaths = beam.sortedByDescending { 
                it.third + (freqWeight * kotlin.math.log(it.first.frequency.toDouble() + 1, 10.0).toFloat()) 
            }.take(5)

            for ((node, wordSoFar, logProb) in topPaths) {
                collectDescendants(node, wordSoFar, logProb - 0.2f, completions, 10)
            }
            
            // THE THROTTLE: Sort the completions, remove duplicates, and take ONLY 4!
            val topCompletions = completions
                .sortedByDescending { it.third + (freqWeight * kotlin.math.log(it.first.frequency.toDouble() + 1, 10.0).toFloat()) }
                .distinctBy { it.second }
                .take(4) 
                
            exactMatches.addAll(topCompletions)
        }

        return exactMatches
            .sortedByDescending { it.third + (freqWeight * kotlin.math.log(it.first.frequency.toDouble() + 1, 10.0).toFloat()) }
            .distinctBy { it.second } // Ensure no duplicate strings appear in the UI
            .map { it.second }
            .take(24) // Still allows up to 24 for normal exact-match typing
    }

    /**
     * Helper function to greedily fetch auto-completions.
     */
    private fun collectDescendants(
        node: TrieNode,
        prefix: String,
        baseLogProb: Float,
        results: MutableList<Triple<TrieNode, String, Float>>,
        depthLeft: Int
    ) {
        // OPTIMIZATION: We only need 4 final results, so we can stop collecting at 10 
        // to keep the engine lightning fast!
        if (depthLeft <= 0 || results.size >= 10) return 

        val sortedChildren = node.children.entries.sortedByDescending { it.value.frequency }
        
        for ((char, child) in sortedChildren) {
            val newWord = prefix + char
            if (child.isWord) {
                results.add(Triple(child, newWord, baseLogProb))
            }
            collectDescendants(child, newWord, baseLogProb, results, depthLeft - 1)
        }
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

    fun addCustomWord(word: String) {
        insertWord(word.lowercase(), 999999)
        try { getCustomDictFile().appendText("${word.lowercase()},999999\n") } catch (e: Exception) {}
    }

    // Change this from private to public in T9Engine.kt
    fun wordToSequence(word: String): String {
        // THE FIX: Force lowercase so capitalized words don't crash into a '0' mapping!
        return word.lowercase().map { charToDigit[it] ?: '0' }.joinToString("")
    }

    // Find getCustomDictFile() and make it public so our Activity can use it:
    fun getCustomDictFile(): File {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val t9Dir = File(downloadsDir, "JoyType")
        if (!t9Dir.exists()) t9Dir.mkdirs()
        return File(t9Dir, "customdictionary.csv")
    }

    // Add these new management functions:
    fun getAllCustomWords(): List<String> {
        val words = mutableListOf<String>()
        try {
            val file = getCustomDictFile()
            if (file.exists()) {
                file.forEachLine { line ->
                    val parts = line.split("\t", ",")
                    if (parts.isNotEmpty() && parts[0].isNotBlank()) words.add(parts[0].trim())
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return words.sorted()
    }

    fun overwriteCustomDictionary(newWords: List<String>) {
        try {
            val file = getCustomDictFile()
            file.writeText("") // Clear file
            for (word in newWords) {
                file.appendText("${word.lowercase()},999999\n")
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun fullReload(context: Context) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().remove("dict_cache_fingerprint").apply()
        
        val binFile = File(context.cacheDir, "dictionary_cache.bin")
        if (binFile.exists()) binFile.delete()
        
        // Load on a background thread so the UI doesn't hang!
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            loadDictionary(context)
        }
    }

    // --- BINARY SERIALIZATION HELPERS ---
    
    private fun writeNode(out: DataOutputStream, node: TrieNode) {
        out.writeBoolean(node.isWord)
        out.writeInt(node.frequency)
        out.writeInt(node.children.size)
        
        for ((char, child) in node.children) {
            out.writeChar(char.code)
            writeNode(out, child)
        }
    }

    private fun readNode(input: DataInputStream): TrieNode {
        val node = TrieNode()
        node.isWord = input.readBoolean()
        node.frequency = input.readInt()
        val childCount = input.readInt()
        
        for (i in 0 until childCount) {
            val char = input.readChar()
            val child = readNode(input)
            node.children[char] = child
        }
        return node
    }

    private fun writeStrings(out: DataOutputStream, list: List<String>) {
        out.writeInt(list.size)
        for (word in list) {
            out.writeUTF(word)
        }
    }

    private fun readStrings(input: DataInputStream, list: MutableList<String>) {
        val size = input.readInt()
        for (i in 0 until size) {
            list.add(input.readUTF())
        }
    }
}