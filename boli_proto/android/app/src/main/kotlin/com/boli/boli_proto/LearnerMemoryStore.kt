package com.boli.boli_proto

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * LearnerMemoryStore
 *
 * Manages 100% offline, on-device persistent memory for the learner.
 * Stored as a private JSON file in the application's internal files directory
 * (`context.filesDir/seedhebol_learner_memory.json`).
 *
 * Tracks:
 *   - Mother tongue (L1) and target language (L2)
 *   - Occupation and difficulty level
 *   - Learned vocabulary (words successfully practiced or saved from OCR)
 *   - Frequently missed words (tracked by failure count to naturally resurface)
 *   - Pronunciation weaknesses (phonemes or words with low acoustic GOP scores)
 *   - Recent conversation / practice context snippets
 *   - Completed situations & scenarios
 */
class LearnerMemoryStore(private val context: Context) {

    companion object {
        private const val TAG = "LearnerMemoryStore"
        private const val FILE_NAME = "seedhebol_learner_memory.json"
        private const val MAX_RECENT_CONTEXT = 8
        private const val MAX_TRACKED_WORDS = 100
    }

    private val storageFile: File by lazy {
        File(context.filesDir, FILE_NAME)
    }

    // In-memory cache guarded by synchronization
    private val lock = Any()

    var l1: String = "Hindi"
        private set
    var l2: String = "Marathi"
        private set
    var occupation: String = "construction worker"
        private set
    var userLevel: String = "beginner"
        private set

    private val learnedVocabulary = mutableSetOf<String>()
    private val missedWordCounts = mutableMapOf<String, Int>()
    private val pronunciationWeaknesses = mutableMapOf<String, Double>() // word/sound -> lowest score
    private val recentContextList = mutableListOf<String>()
    private val completedScenarios = mutableSetOf<String>()

    init {
        loadFromDisk()
    }

    // -------------------------------------------------------------------------
    // Persistence Operations (Offline JSON)
    // -------------------------------------------------------------------------

    private fun loadFromDisk() {
        synchronized(lock) {
            if (!storageFile.exists()) {
                // Seed with sensible defaults for blue-collar workplace starter
                learnedVocabulary.addAll(listOf("नमस्कार", "मदत", "पाणी", "काम"))
                missedWordCounts["मदत हवी आहे"] = 1
                saveToDisk()
                return
            }

            try {
                val jsonStr = storageFile.readText()
                val root = JSONObject(jsonStr)

                l1 = root.optString("l1", "Hindi")
                l2 = root.optString("l2", "Marathi")
                occupation = root.optString("occupation", "construction worker")
                userLevel = root.optString("userLevel", "beginner")

                learnedVocabulary.clear()
                val vocabArray = root.optJSONArray("learnedVocabulary") ?: JSONArray()
                for (i in 0 until vocabArray.length()) {
                    val w = vocabArray.optString(i)
                    if (w.isNotBlank()) learnedVocabulary.add(w)
                }

                missedWordCounts.clear()
                val missedObj = root.optJSONObject("missedWordCounts") ?: JSONObject()
                val missedKeys = missedObj.keys()
                while (missedKeys.hasNext()) {
                    val key = missedKeys.next()
                    missedWordCounts[key] = missedObj.optInt(key, 1)
                }

                pronunciationWeaknesses.clear()
                val pronObj = root.optJSONObject("pronunciationWeaknesses") ?: JSONObject()
                val pronKeys = pronObj.keys()
                while (pronKeys.hasNext()) {
                    val key = pronKeys.next()
                    pronunciationWeaknesses[key] = pronObj.optDouble(key, 0.5)
                }

                recentContextList.clear()
                val contextArray = root.optJSONArray("recentContext") ?: JSONArray()
                for (i in 0 until contextArray.length()) {
                    val c = contextArray.optString(i)
                    if (c.isNotBlank()) recentContextList.add(c)
                }

                completedScenarios.clear()
                val scenariosArray = root.optJSONArray("completedScenarios") ?: JSONArray()
                for (i in 0 until scenariosArray.length()) {
                    val s = scenariosArray.optString(i)
                    if (s.isNotBlank()) completedScenarios.add(s)
                }

                Log.i(TAG, "Loaded learner memory: ${learnedVocabulary.size} learned words, ${missedWordCounts.size} missed words")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading learner memory, resetting to defaults", e)
            }
        }
    }

    private fun saveToDisk() {
        synchronized(lock) {
            try {
                val root = JSONObject().apply {
                    put("l1", l1)
                    put("l2", l2)
                    put("occupation", occupation)
                    put("userLevel", userLevel)

                    put("learnedVocabulary", JSONArray(learnedVocabulary.toList()))

                    val missedObj = JSONObject()
                    missedWordCounts.forEach { (k, v) -> missedObj.put(k, v) }
                    put("missedWordCounts", missedObj)

                    val pronObj = JSONObject()
                    pronunciationWeaknesses.forEach { (k, v) -> pronObj.put(k, v) }
                    put("pronunciationWeaknesses", pronObj)

                    put("recentContext", JSONArray(recentContextList))
                    put("completedScenarios", JSONArray(completedScenarios.toList()))
                }

                FileOutputStream(storageFile).use { out ->
                    out.write(root.toString(2).toByteArray())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save learner memory to disk", e)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Learning Signal Updates
    // -------------------------------------------------------------------------

    fun updateProfile(
        l1: String? = null,
        l2: String? = null,
        occupation: String? = null,
        userLevel: String? = null,
    ) {
        synchronized(lock) {
            l1?.let { this.l1 = it }
            l2?.let { this.l2 = it }
            occupation?.let { this.occupation = it }
            userLevel?.let { this.userLevel = it }
            saveToDisk()
        }
    }

    fun recordWordAttempt(word: String, isCorrect: Boolean) {
        synchronized(lock) {
            val trimmed = word.trim()
            if (trimmed.isBlank()) return

            if (isCorrect) {
                learnedVocabulary.add(trimmed)
                // If it was previously missed, decrement the failure count or clear it
                val count = missedWordCounts[trimmed] ?: 0
                if (count <= 1) {
                    missedWordCounts.remove(trimmed)
                } else {
                    missedWordCounts[trimmed] = count - 1
                }
            } else {
                val current = missedWordCounts[trimmed] ?: 0
                missedWordCounts[trimmed] = current + 1
            }

            // Limit size of tracked missed words
            if (missedWordCounts.size > MAX_TRACKED_WORDS) {
                val leastMissed = missedWordCounts.entries.minByOrNull { it.value }?.key
                leastMissed?.let { missedWordCounts.remove(it) }
            }

            saveToDisk()
        }
    }

    fun recordPronunciationWeakness(wordOrSound: String, score: Double, phonemeHint: String? = null) {
        synchronized(lock) {
            val key = phonemeHint ?: wordOrSound.trim()
            if (key.isBlank()) return

            // Keep lowest score recorded
            val prev = pronunciationWeaknesses[key] ?: 1.0
            if (score < prev) {
                pronunciationWeaknesses[key] = score
            }
            saveToDisk()
        }
    }

    fun recordCompletedScenario(scenarioId: String) {
        synchronized(lock) {
            val trimmed = scenarioId.trim()
            if (trimmed.isNotBlank()) {
                completedScenarios.add(trimmed)
                addRecentContext("Completed scenario: $trimmed")
                saveToDisk()
            }
        }
    }

    fun addRecentContext(snippet: String) {
        synchronized(lock) {
            val trimmed = snippet.trim()
            if (trimmed.isBlank()) return
            recentContextList.removeAll { it == trimmed }
            recentContextList.add(trimmed)
            while (recentContextList.size > MAX_RECENT_CONTEXT) {
                recentContextList.removeAt(0)
            }
            saveToDisk()
        }
    }

    fun addLearnedVocab(word: String) {
        synchronized(lock) {
            val trimmed = word.trim()
            if (trimmed.isNotBlank()) {
                learnedVocabulary.add(trimmed)
                saveToDisk()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Gemma Context Synthesis
    // -------------------------------------------------------------------------

    /**
     * Builds an enriched [GemmaContext] incorporating all local memory signals.
     */
    fun buildPersonalizedGemmaContext(
        scenario: String? = null,
        ocrText: String? = null,
        asrTranscript: String? = null,
        learningContext: String? = null,
    ): GemmaContext = synchronized(lock) {
        // Top 5 frequently missed words
        val topMissed = missedWordCounts.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key }

        // Pronunciation weak spots (e.g. score < 0.65)
        val weakPron = pronunciationWeaknesses.entries
            .filter { it.value < 0.65 }
            .sortedBy { it.value }
            .take(4)
            .map { it.key }

        return GemmaContext(
            l1 = l1,
            l2 = l2,
            occupation = occupation,
            userLevel = userLevel,
            scenario = scenario,
            ocrText = ocrText,
            asrTranscript = asrTranscript,
            learningContext = learningContext,
            learnedVocabulary = learnedVocabulary.toList().takeLast(10),
            frequentlyMissedWords = topMissed,
            pronunciationWeaknesses = weakPron,
            recentContext = recentContextList.takeLast(4),
            completedScenarios = completedScenarios.toList().takeLast(8),
        )
    }

    /**
     * Snapshot map representation for Flutter Bridge queries.
     */
    fun toMap(): Map<String, Any> = synchronized(lock) {
        mapOf(
            "l1" to l1,
            "l2" to l2,
            "occupation" to occupation,
            "user_level" to userLevel,
            "learned_vocabulary" to learnedVocabulary.toList(),
            "frequently_missed_words" to missedWordCounts.entries
                .sortedByDescending { it.value }
                .map { mapOf("word" to it.key, "count" to it.value) },
            "pronunciation_weaknesses" to pronunciationWeaknesses.entries
                .map { mapOf("sound" to it.key, "score" to it.value) },
            "recent_context" to recentContextList,
            "completed_scenarios" to completedScenarios.toList(),
            "total_learned_count" to learnedVocabulary.size,
        )
    }
}
