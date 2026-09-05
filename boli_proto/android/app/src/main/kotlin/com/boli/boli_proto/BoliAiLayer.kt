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
    // OCR Pre-processing & Normalization
    // -------------------------------------------------------------------------

    /**
     * Sanitizes raw OCR text before passing it to Gemma.
     *
     * Removes:
     *   - Stray single characters, pure punctuation or symbols
     *   - Pure phone numbers / barcode noise
     *   - Duplicate consecutive lines
     *   - Leading/trailing bullet points or dashes
     * Keeps up to the top 6 most salient lines to fit within token budgets.
     */
    fun cleanOcrText(raw: String): String {
        if (raw.isBlank()) return ""
        val cleanedLines = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        for (line in raw.lines()) {
            val trimmed = line.trim()
                .replace(Regex("^[•*\\-—_~|#]+\\s*"), "") // strip leading bullet chars
                .replace(Regex("\\s*[•*\\-—_~|#]+$"), "") // strip trailing bullet chars
                .trim()

            // Ignore empty or 1-character stray symbols (e.g. ".", "|")
            if (trimmed.length < 2) continue

            // Ignore pure punctuation, numbers or phone numbers
            if (trimmed.all { !it.isLetter() }) continue

            val normalizedKey = trimmed.lowercase()
            if (!seen.contains(normalizedKey)) {
                seen.add(normalizedKey)
                cleanedLines.add(trimmed)
            }
        }

        val selected = cleanedLines.take(6)
        return if (selected.isNotEmpty()) {
            selected.joinToString("\n")
        } else {
            // If strict filtering stripped everything, fall back to trimmed raw preview
            raw.trim().take(120)
        }
    }

    // -------------------------------------------------------------------------
    // Primary demo flow: Camera → OCR → Gemma → MicroLesson
    // -------------------------------------------------------------------------

    /**
     * Generates a contextual micro-lesson from [ocrText] in a single LLM call.
     * Uses low temperature (0.2) for deterministic structured output and graceful
     * partial-result fallback so a valid translation is never lost.
     */
    suspend fun generateLessonFromOcr(
        ocrText: String,
        ctx: GemmaContext,
    ): AiResponse<MicroLesson> {
        val t0 = System.currentTimeMillis()
        val cleanOcr = cleanOcrText(ocrText)
        val textToProcess = if (cleanOcr.isNotBlank()) cleanOcr else ocrText.trim()

        if (gemma.isAvailable && textToProcess.isNotBlank()) {
            val prompt = GemmaPromptBuilder.buildOcrLessonPrompt(textToProcess, ctx)
            val raw = gemma.generate(prompt, temperature = GemmaEngine.STRUCTURED_TEMPERATURE)
            if (raw != null) {
                Log.i(TAG, "Gemma raw OCR lesson response:\n$raw")
                val fallbackTitle = textToProcess.lines().firstOrNull()?.take(30) ?: textToProcess.take(30)
                val parsedLesson = parseOcrLessonResponse(raw, fallbackTitle)

                // Graceful partial-result check:
                // If Gemma gave ANY useful output (translation, explanation, vocab, or practice),
                // do NOT discard it. Patch missing fields gracefully.
                val hasAnyValidContent = parsedLesson.translation.isNotBlank() ||
                        parsedLesson.explanation.isNotBlank() ||
                        parsedLesson.vocabulary.isNotEmpty() ||
                        parsedLesson.practicePrompt.isNotBlank()

                if (hasAnyValidContent) {
                    val patchedLesson = patchMissingFields(parsedLesson, textToProcess, ctx)
                    return AiResponse(patchedLesson, AiSource.GEMMA, System.currentTimeMillis() - t0)
                } else {
                    Log.w(TAG, "Gemma response lacked all target fields, falling back to deterministic")
                }
            }
        }

        // Gemma unavailable or completely empty response
        val fallback = deterministic.generateMicroLesson(textToProcess.take(30), ctx)
        return AiResponse(fallback, AiSource.DETERMINISTIC_FALLBACK, System.currentTimeMillis() - t0)
    }

    /**
     * Completes any missing fields in a partially-parsed Gemma response using
     * deterministic domain context so the UI receives a complete, non-broken lesson.
     */
    private fun patchMissingFields(
        lesson: MicroLesson,
        cleanOcrText: String,
        ctx: GemmaContext,
    ): MicroLesson {
        val fallback = deterministic.generateMicroLesson(cleanOcrText.take(30), ctx)

        val topic = if (lesson.topic.isNotBlank()) lesson.topic else {
            cleanOcrText.lines().firstOrNull()?.take(30) ?: fallback.topic
        }

        val translation = if (lesson.translation.isNotBlank()) lesson.translation else {
            deterministic.translateOcrText(cleanOcrText, ctx)
        }

        val explanation = if (lesson.explanation.isNotBlank()) lesson.explanation else {
            if (translation.isNotBlank() && !translation.startsWith("[")) {
                "कार्यस्थल पर सुरक्षा और निर्देश का बोर्ड।"
            } else {
                fallback.explanation
            }
        }

        val vocabulary = if (lesson.vocabulary.isNotEmpty()) lesson.vocabulary else {
            val extracted = deterministic.generateVocabulary(cleanOcrText, ctx)
            if (extracted.isNotEmpty()) extracted else fallback.vocabulary
        }

        val practice = if (lesson.practicePrompt.isNotBlank()) lesson.practicePrompt else {
            if (vocabulary.isNotEmpty()) {
                "${vocabulary.first().l2Word} येथे वापरा."
            } else {
                fallback.practicePrompt
            }
        }

        return lesson.copy(
            topic = topic,
            translation = translation,
            explanation = explanation,
            vocabulary = vocabulary,
            practicePrompt = practice,
            source = "gemma",
        )
    }

    // -------------------------------------------------------------------------
    // Translation
    // -------------------------------------------------------------------------

    suspend fun translateOcrText(
        ocrText: String,
        ctx: GemmaContext,
    ): AiResponse<String> {
        val t0 = System.currentTimeMillis()
        val cleanOcr = cleanOcrText(ocrText)
        val textToProcess = if (cleanOcr.isNotBlank()) cleanOcr else ocrText.trim()

        if (gemma.isAvailable && textToProcess.isNotBlank()) {
            val prompt = GemmaPromptBuilder.buildTranslationPrompt(textToProcess, ctx)
            val raw = gemma.generate(prompt, temperature = GemmaEngine.STRUCTURED_TEMPERATURE)
            if (!raw.isNullOrBlank()) {
                val cleaned = cleanLine(raw)
                    .replace(Regex("^(?i)(?:TRANSLATION|TRANS|MEANING)\\s*[:\\-—]\\s*"), "")
                    .replace(Regex("^[\"']|[\"']$"), "")
                    .trim()
                if (cleaned.isNotBlank()) {
                    return AiResponse(cleaned, AiSource.GEMMA, System.currentTimeMillis() - t0)
                }
            }
        }
        val fallback = deterministic.translateOcrText(textToProcess, ctx)
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
        val cleanOcr = cleanOcrText(ocrText)
        val textToProcess = if (cleanOcr.isNotBlank()) cleanOcr else ocrText.trim()

        if (gemma.isAvailable && textToProcess.isNotBlank()) {
            val prompt = GemmaPromptBuilder.buildVocabularyPrompt(textToProcess, ctx)
            val raw = gemma.generate(prompt, temperature = GemmaEngine.STRUCTURED_TEMPERATURE)
            if (!raw.isNullOrBlank()) {
                val vocab = parseVocabResponse(raw)
                if (vocab.isNotEmpty()) {
                    return AiResponse(vocab, AiSource.GEMMA, System.currentTimeMillis() - t0)
                }
            }
        }
        val fallback = deterministic.generateVocabulary(textToProcess, ctx)
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
            val raw = gemma.generate(prompt, temperature = GemmaEngine.STRUCTURED_TEMPERATURE)
            if (!raw.isNullOrBlank()) {
                val clean = cleanLine(raw).replace(Regex("^(?i)(?:EXPLANATION|EXPLAIN)\\s*[:\\-—]\\s*"), "")
                return AiResponse(clean.trim(), AiSource.GEMMA, System.currentTimeMillis() - t0)
            }
        }
        val fallback = deterministic.getExplanation(phrase, ctx)
        return AiResponse(fallback, AiSource.DETERMINISTIC_FALLBACK, System.currentTimeMillis() - t0)
    }

    // -------------------------------------------------------------------------
    // Roleplay
    // -------------------------------------------------------------------------

    /**
     * Generates Gemma's next roleplay turn using conversational temperature (0.7).
     * Returns a raw map so existing Flutter bridge response format is preserved.
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
            val raw = gemma.generate(prompt, temperature = GemmaEngine.ROLEPLAY_TEMPERATURE)
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
    // Resilient response parsers — tolerant of markdown, spacing, and delimiter drift
    // -------------------------------------------------------------------------

    private fun cleanLine(line: String): String {
        return line.trim()
            .replace(Regex("^[-*#0-9.\\s]+"), "") // strip leading bullets/numbers/markdown
            .replace(Regex("[*`#]+"), "")        // strip inline markdown asterisks or backticks
            .trim()
    }

    private fun findTagValue(lines: List<String>, tagPattern: String): String? {
        val regex = Regex("^(?i)(?:$tagPattern)\\s*[:\\-—]\\s*(.*)$")
        for (line in lines) {
            val cleaned = cleanLine(line)
            val match = regex.find(cleaned)
            if (match != null) {
                val value = match.groupValues[1].trim()
                if (value.isNotBlank()) return value
            }
        }
        return null
    }

    /**
     * Parses the combined OCR-lesson prompt response with high tolerance:
     *   - Handles markdown headers or bullets (`**TOPIC:**`, `- TOPIC:`, `### TOPIC:`)
     *   - Handles prompt completion when topic is placed on the first output line
     *   - Extracts translation, explanation, vocab, and practice sentence
     */
    private fun parseOcrLessonResponse(raw: String, fallbackTopic: String): MicroLesson {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }

        val explicitTopic = findTagValue(lines, "TOPIC|TITLE")
        val topic = explicitTopic ?: run {
            // If the model completed the prompt immediately after "TOPIC:"
            val firstCleaned = lines.map { cleanLine(it) }.firstOrNull { it.isNotBlank() }
            if (firstCleaned != null && !firstCleaned.contains(Regex("^(?i)(TRANSLATION|EXPLANATION|WORD|PRACTICE)"))) {
                firstCleaned.take(40)
            } else {
                fallbackTopic
            }
        }

        val translation = findTagValue(lines, "TRANSLATION|TRANS|MEANING") ?: ""
        val explanation = findTagValue(lines, "EXPLANATION|EXPLAIN|MEANING_DETAIL|NOTE") ?: ""
        val practice = findTagValue(lines, "PRACTICE|SPEAK|PRACTICE_PROMPT|SENTENCE") ?: ""
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
     * Parses vocabulary lines:
     *   `WORD: <l2> = <l1> (<roman>)`
     * Tolerates:
     *   - Missing `WORD:` tag (e.g. `- शब्द = अर्थ (roman)`)
     *   - Multiple delimiters: `=`, `—`, `–`, `:`, or `-`
     *   - Missing romanization or brackets `[...]`
     *   - Quotation marks around words
     */
    private fun parseVocabResponse(raw: String): List<VocabItem> =
        parseVocabLines(raw.lines().map { it.trim() }.filter { it.isNotBlank() })

    private fun parseVocabLines(lines: List<String>): List<VocabItem> {
        val vocabList = mutableListOf<VocabItem>()

        for (rawLine in lines) {
            val line = cleanLine(rawLine)
            // Strip leading "WORD:" or "VOCAB:" if present
            val content = line.replace(Regex("^(?i)(?:WORD|VOCAB)\\s*[:\\-—]\\s*"), "").trim()

            // Delimiters: =, —, –, :, or space-hyphen-space
            val sepMatch = Regex("[=—–:]|\\s+-\\s+").find(content) ?: continue
            val l2 = content.substring(0, sepMatch.range.first).trim()
                .replace(Regex("[\"']"), "")
            val rest = content.substring(sepMatch.range.last + 1).trim()
                .replace(Regex("[\"']"), "")

            if (l2.isBlank() || rest.isBlank()) continue

            // Extract romanization from parens () or brackets []
            val parenMatch = Regex("[\\[(]([^\\])]+)[\\])]").find(rest)
            val roman = parenMatch?.groupValues?.get(1)?.trim() ?: ""
            val l1 = if (parenMatch != null) {
                rest.removeRange(parenMatch.range).trim()
            } else {
                rest
            }

            if (l2.isNotBlank() && l1.isNotBlank()) {
                vocabList.add(VocabItem(l2Word = l2, l1Meaning = l1, romanization = roman))
            }
        }
        return vocabList.distinctBy { it.l2Word }.take(5)
    }

    /**
     * Parses roleplay response:
     *   L2: <text>
     *   L1: <text>
     *   HINT: <text or "none">
     */
    private fun parseRoleplayResponse(raw: String): DialogueTurn {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
        val l2Text = findTagValue(lines, "L2|REPLY|RESPONSE") ?: cleanLine(raw.lines().firstOrNull() ?: "").take(100)
        val l1Text = findTagValue(lines, "L1|TRANSLATION") ?: ""
        val hintRaw = findTagValue(lines, "HINT|TIP|PRONUNCIATION") ?: ""
        val hint = if (hintRaw.equals("none", ignoreCase = true)) "" else hintRaw

        return DialogueTurn(speaker = "bot", text = l2Text, l1Text = l1Text, hint = hint)
    }

    companion object {
        private const val TAG = "BoliAiLayer"
    }
}
