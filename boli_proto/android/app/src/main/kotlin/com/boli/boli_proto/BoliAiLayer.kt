package com.boli.boli_proto

import android.util.Log

/**
 * BoliAiLayer — the clean abstraction between all Boli business logic and AI.
 *
 * This is the ONLY class that knows whether Gemma is available. Everything else
 * calls BoliAiLayer and receives a typed [AiResponse]; the [AiSource] field tells
 * callers (and the UI) which backend produced the answer.
 *
 * Contract:
 *   - Every public method returns a valid, non-null [AiResponse] — it never throws.
 *   - If [gemma] is unavailable, [deterministic] is used transparently.
 *   - Callers must NOT import GemmaEngine or DeterministicFallback directly.
 *
 * Threading:
 *   - All public methods are suspend functions; call from any coroutine context.
 *   - Gemma calls go to Dispatchers.IO via GemmaEngine internally.
 *   - Deterministic fallback calls are synchronous but lightweight.
 */
class BoliAiLayer(
    private val gemma: GemmaEngine,
    private val deterministic: DeterministicFallback,
) {

    // -------------------------------------------------------------------------
    // Response wrapper
    // -------------------------------------------------------------------------

    enum class AiSource { GEMMA, DETERMINISTIC_FALLBACK }

    data class AiResponse<T>(
        val value: T,
        val source: AiSource,
        val latencyMs: Long,
    )

    // -------------------------------------------------------------------------
    // Primary demo flow: Camera → OCR → Gemma → MicroLesson
    // -------------------------------------------------------------------------

    /**
     * Generates a contextual micro-lesson from [ocrText] in a single LLM call.
     * This is the hot path for the camera lesson feature.
     */
    suspend fun generateLessonFromOcr(
        ocrText: String,
        ctx: GemmaContext,
    ): AiResponse<MicroLesson> {
        val t0 = System.currentTimeMillis()
        if (gemma.isAvailable) {
            val prompt = GemmaPromptBuilder.buildOcrLessonPrompt(ocrText, ctx)
            val raw = gemma.generate(prompt)
            if (raw != null) {
                Log.i(TAG, "Gemma raw OCR lesson response:\n$raw")
                val lesson = parseOcrLessonResponse(raw, ocrText)
                if (lesson.explanation.isNotBlank() || lesson.vocabulary.isNotEmpty()) {
                    return AiResponse(lesson, AiSource.GEMMA, System.currentTimeMillis() - t0)
                } else {
                    Log.w(TAG, "Gemma response lacks explanation/vocab, falling back to deterministic")
                }
            }
        }
        // Gemma unavailable or failed
        val fallback = deterministic.generateMicroLesson(ocrText.take(30), ctx)
        return AiResponse(fallback, AiSource.DETERMINISTIC_FALLBACK, System.currentTimeMillis() - t0)
    }

    // -------------------------------------------------------------------------
    // Translation
    // -------------------------------------------------------------------------

    suspend fun translateOcrText(
        ocrText: String,
        ctx: GemmaContext,
    ): AiResponse<String> {
        val t0 = System.currentTimeMillis()
        if (gemma.isAvailable) {
            val prompt = GemmaPromptBuilder.buildTranslationPrompt(ocrText, ctx)
            val raw = gemma.generate(prompt)
            if (!raw.isNullOrBlank()) {
                return AiResponse(raw.trim(), AiSource.GEMMA, System.currentTimeMillis() - t0)
            }
        }
        val fallback = deterministic.translateOcrText(ocrText, ctx)
        return AiResponse(fallback, AiSource.DETERMINISTIC_FALLBACK, System.currentTimeMillis() - t0)
    }

    // -------------------------------------------------------------------------
    // Vocabulary
    // -------------------------------------------------------------------------

    suspend fun generateVocabulary(
        ocrText: String,
        ctx: GemmaContext,
    ): AiResponse<List<VocabItem>> {
        val t0 = System.currentTimeMillis()
        if (gemma.isAvailable) {
            val prompt = GemmaPromptBuilder.buildVocabularyPrompt(ocrText, ctx)
            val raw = gemma.generate(prompt)
            if (!raw.isNullOrBlank()) {
                val vocab = parseVocabResponse(raw)
                if (vocab.isNotEmpty()) {
                    return AiResponse(vocab, AiSource.GEMMA, System.currentTimeMillis() - t0)
                }
            }
        }
        val fallback = deterministic.generateVocabulary(ocrText, ctx)
        return AiResponse(fallback, AiSource.DETERMINISTIC_FALLBACK, System.currentTimeMillis() - t0)
    }

    // -------------------------------------------------------------------------
    // Explanation
    // -------------------------------------------------------------------------

    suspend fun getExplanation(
        phrase: String,
        ctx: GemmaContext,
    ): AiResponse<String> {
        val t0 = System.currentTimeMillis()
        if (gemma.isAvailable) {
            val prompt = GemmaPromptBuilder.buildExplanationPrompt(phrase, ctx)
            val raw = gemma.generate(prompt)
            if (!raw.isNullOrBlank()) {
                return AiResponse(raw.trim(), AiSource.GEMMA, System.currentTimeMillis() - t0)
            }
        }
        val fallback = deterministic.getExplanation(phrase, ctx)
        return AiResponse(fallback, AiSource.DETERMINISTIC_FALLBACK, System.currentTimeMillis() - t0)
    }

    // -------------------------------------------------------------------------
    // Roleplay
    // -------------------------------------------------------------------------

    /**
     * Generates Gemma's next roleplay turn. Returns a raw map so the existing
     * BoliBridgePlugin response format is preserved without breaking Flutter.
     */
    suspend fun nextRoleplayTurn(
        history: List<DialogueTurn>,
        situationId: String,
        currentNodeId: String,
        userSpokenText: String,
        ctx: GemmaContext,
    ): Map<String, Any?> {
        val t0 = System.currentTimeMillis()
        val historyWithUser = history + DialogueTurn("user", userSpokenText)

        if (gemma.isAvailable) {
            val prompt = GemmaPromptBuilder.buildRoleplayNextTurnPrompt(historyWithUser, ctx)
            val raw = gemma.generate(prompt)
            if (!raw.isNullOrBlank()) {
                val turn = parseRoleplayResponse(raw)
                val ms = System.currentTimeMillis() - t0
                Log.i(TAG, "Gemma roleplay turn in ${ms}ms")
                return mapOf(
                    "recognized_transcript" to userSpokenText,
                    "is_intent_matched" to true,
                    "matched_intent" to "gemma_response",
                    "next_node_id" to "gemma_node",
                    "prompt_l2" to turn.text,
                    "prompt_transliteration" to "",
                    "prompt_l1" to turn.l1Text,
                    "pre_rendered_audio_path" to null,
                    "pronunciation_score" to null,
                    "weak_phonemes" to emptyList<String>(),
                    "articulatory_hint" to turn.hint,
                    "ai_source" to "gemma",
                    "latency_ms" to ms,
                )
            }
        }
        // Fallback: preserve original stub response
        return deterministic.nextRoleplayTurn(historyWithUser, situationId, currentNodeId, ctx)
    }

    // -------------------------------------------------------------------------
    // Response parsers — extract structure from Gemma plain-text output
    // -------------------------------------------------------------------------

    /**
     * Parses the combined OCR-lesson prompt response.
     *
     * Expected lines:
     *   TOPIC: ...
     *   TRANSLATION: ...
     *   EXPLANATION: ...
     *   WORD: <l2> = <l1> (<roman>)
     *   PRACTICE: ...
     */
    private fun parseOcrLessonResponse(raw: String, fallbackTopic: String): MicroLesson {
        val lines = raw.lines().map { it.trim() }
        val topic = lines.firstOrNull { it.startsWith("TOPIC:") }
            ?.removePrefix("TOPIC:")?.trim() ?: fallbackTopic.take(30)
        val translation = lines.firstOrNull { it.startsWith("TRANSLATION:") }
            ?.removePrefix("TRANSLATION:")?.trim() ?: ""
        val explanation = lines.firstOrNull { it.startsWith("EXPLANATION:") }
            ?.removePrefix("EXPLANATION:")?.trim() ?: ""
        val practice = lines.firstOrNull { it.startsWith("PRACTICE:") }
            ?.removePrefix("PRACTICE:")?.trim() ?: ""
        val vocab = parseVocabLines(lines)

        return MicroLesson(
            topic = topic,
            explanation = explanation,
            vocabulary = vocab,
            practicePrompt = practice,
            translation = translation,
            source = "gemma",
        )
    }

    /**
     * Parses WORD: <l2> = <l1> (<roman>) lines.
     * Tolerates missing parentheses for romanization.
     */
    private fun parseVocabResponse(raw: String): List<VocabItem> =
        parseVocabLines(raw.lines().map { it.trim() })

    private fun parseVocabLines(lines: List<String>): List<VocabItem> {
        return lines
            .filter { it.startsWith("WORD:") }
            .mapNotNull { line ->
                val content = line.removePrefix("WORD:").trim()
                // Split on "=" → left=L2, right=L1 (+optional roman in parens)
                val eqIdx = content.indexOf('=')
                if (eqIdx < 0) return@mapNotNull null
                val l2 = content.substring(0, eqIdx).trim()
                val rest = content.substring(eqIdx + 1).trim()
                // Extract romanization from parentheses
                val parenStart = rest.lastIndexOf('(')
                val parenEnd = rest.lastIndexOf(')')
                val roman = if (parenStart >= 0 && parenEnd > parenStart)
                    rest.substring(parenStart + 1, parenEnd).trim() else ""
                val l1 = if (parenStart > 0) rest.substring(0, parenStart).trim() else rest
                VocabItem(l2Word = l2, l1Meaning = l1, romanization = roman)
            }
    }

    /**
     * Parses roleplay response:
     *   L2: <text>
     *   L1: <text>
     *   HINT: <text or "none">
     */
    private fun parseRoleplayResponse(raw: String): DialogueTurn {
        val lines = raw.lines().map { it.trim() }
        val l2Text = lines.firstOrNull { it.startsWith("L2:") }
            ?.removePrefix("L2:")?.trim() ?: raw.take(100)
        val l1Text = lines.firstOrNull { it.startsWith("L1:") }
            ?.removePrefix("L1:")?.trim() ?: ""
        val hint = lines.firstOrNull { it.startsWith("HINT:") }
            ?.removePrefix("HINT:")?.trim()?.let { if (it == "none") "" else it } ?: ""

        return DialogueTurn(speaker = "bot", text = l2Text, l1Text = l1Text, hint = hint)
    }

    companion object {
        private const val TAG = "BoliAiLayer"
    }
}
