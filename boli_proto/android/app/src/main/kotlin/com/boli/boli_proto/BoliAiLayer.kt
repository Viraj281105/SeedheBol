package com.boli.boli_proto

import android.util.Log

/**
 * SeedheBolAiLayer — the clean abstraction between all SeedheBol business logic and AI.
 *
 * This is the ONLY class that knows whether Gemma is available. Everything else
 * calls SeedheBolAiLayer and receives a typed [AiResponse]; the [AiSource] field tells
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
                .replace(Regex("^\\d+[.,)\\-\\s]+\\s*"), "") // strip leading list numbering like "18, " or "19. "
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

        val topic = if (lesson.topic.isNotBlank() && !LlmOutputSanitizer.hasDegenerativeRepetition(lesson.topic)) {
            lesson.topic
        } else {
            cleanOcrText.lines().firstOrNull()?.take(30) ?: fallback.topic
        }

        val translation = if (lesson.translation.isNotBlank() && !LlmOutputSanitizer.hasDegenerativeRepetition(lesson.translation)) {
            lesson.translation
        } else {
            deterministic.translateOcrText(cleanOcrText, ctx)
        }

        val explanation = if (lesson.explanation.isNotBlank() && !LlmOutputSanitizer.hasDegenerativeRepetition(lesson.explanation)) {
            lesson.explanation
        } else {
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

        val practice = if (lesson.practicePrompt.isNotBlank() &&
            !LlmOutputSanitizer.hasDegenerativeRepetition(lesson.practicePrompt) &&
            LlmOutputSanitizer.matchesScript(lesson.practicePrompt, ctx.l2)) {
            lesson.practicePrompt
        } else {
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
                val turn = parseRoleplayResponse(raw, ctx)
                if (turn != null && LlmOutputSanitizer.isValidL2Output(turn.text, ctx.l2)) {
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
                        "natural_phrasing" to turn.betterWay,
                        "intent_explanation" to turn.feedback,
                        "ai_source" to "gemma",
                        "latency_ms" to ms,
                    )
                } else {
                    Log.w(TAG, "Gemma roleplay turn failed validation or repetition checks, falling back to deterministic")
                }
            }
        }
        // Fallback: preserve original stub response
        return deterministic.nextRoleplayTurn(historyWithUser, situationId, currentNodeId, ctx)
    }

    /**
     * Generates a dynamic, authentic Gemma opening line for a roleplay persona.
     * Falls back to the hardcoded persona opener when Gemma is unavailable.
     *
     * @return Pair<l2Text, l1Text> — the persona's opening line in L2 and its L1 meaning.
     */
    suspend fun generateRoleplayOpener(
        persona: String,
        scenario: String,
        ctx: GemmaContext,
        fallbackL2: String,
        fallbackL1: String,
    ): Pair<String, String> {
        if (gemma.isAvailable) {
            val prompt = GemmaPromptBuilder.buildRoleplayOpenerPrompt(persona, scenario, ctx)
            val raw = gemma.generate(prompt, temperature = GemmaEngine.ROLEPLAY_TEMPERATURE)
            if (!raw.isNullOrBlank()) {
                val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
                val l2 = findTagValue(lines, "L2") ?: ""
                val l1 = findTagValue(lines, "L1") ?: ""
                if (l2.isNotBlank() && LlmOutputSanitizer.isValidL2Output(l2, ctx.l2)) {
                    Log.i(TAG, "Gemma roleplay opener: $l2")
                    return Pair(l2, l1.ifBlank { fallbackL1 })
                }
            }
        }
        return Pair(fallbackL2, fallbackL1)
    }

    data class SpokenIntentResult(
        val isMatched: Boolean,
        val confidence: Double,
        val feedback: String,
        val betterWay: String,
        val source: String,
    )

    /**
     * Evaluates user's spoken answer semantically using Gemma, tolerating variations,
     * accents, and synonyms. Falls back to DeterministicFallback when unavailable.
     */
    suspend fun evaluateSpokenIntent(
        targetPhrase: String,
        prompt: String,
        spokenText: String,
        ctx: GemmaContext,
    ): AiResponse<SpokenIntentResult> {
        val t0 = System.currentTimeMillis()
        if (gemma.isAvailable && spokenText.isNotBlank()) {
            val evalPrompt = GemmaPromptBuilder.buildEvaluateSpokenIntentPrompt(targetPhrase, prompt, spokenText, ctx)
            val raw = gemma.generate(evalPrompt, temperature = GemmaEngine.STRUCTURED_TEMPERATURE)
            if (!raw.isNullOrBlank()) {
                val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
                val matchVal = findTagValue(lines, "MATCH") ?: ""
                val feedback = findTagValue(lines, "FEEDBACK") ?: ""
                val better = findTagValue(lines, "BETTER") ?: targetPhrase
                val isMatched = matchVal.contains("YES", ignoreCase = true) || matchVal.contains("TRUE", ignoreCase = true)
                val result = SpokenIntentResult(
                    isMatched = isMatched,
                    confidence = if (isMatched) 0.90 else 0.40,
                    feedback = feedback.ifBlank { if (isMatched) "अर्थ योग्य आहे!" else "वाक्य पुन्हा बोलण्याचा प्रयत्न करा." },
                    betterWay = better,
                    source = "gemma",
                )
                return AiResponse(result, AiSource.GEMMA, System.currentTimeMillis() - t0)
            }
        }
        val fallbackMap = deterministic.evaluateSpokenIntent(targetPhrase, prompt, spokenText, ctx)
        val result = SpokenIntentResult(
            isMatched = fallbackMap["is_matched"] as? Boolean ?: false,
            confidence = (fallbackMap["confidence"] as? Number)?.toDouble() ?: 0.5,
            feedback = fallbackMap["feedback"] as? String ?: "",
            betterWay = fallbackMap["better_way"] as? String ?: targetPhrase,
            source = "fallback",
        )
        return AiResponse(result, AiSource.DETERMINISTIC_FALLBACK, System.currentTimeMillis() - t0)
    }

    // -------------------------------------------------------------------------
    // Dynamic Workplace Practice Drills
    // -------------------------------------------------------------------------

    suspend fun generateDynamicExercises(
        situation: String,
        domain: String,
        ctx: GemmaContext,
    ): AiResponse<List<DynamicExercise>> {
        val t0 = System.currentTimeMillis()
        if (gemma.isAvailable) {
            val prompt = GemmaPromptBuilder.buildPracticeDrillsPrompt(situation, domain, ctx)
            val raw = gemma.generate(prompt, temperature = GemmaEngine.STRUCTURED_TEMPERATURE)
            if (!raw.isNullOrBlank()) {
                val exercises = parseDynamicExercises(raw)
                if (exercises.isNotEmpty()) {
                    return AiResponse(exercises, AiSource.GEMMA, System.currentTimeMillis() - t0)
                }
            }
        }
        val fallback = deterministic.generateDynamicExercises(situation, domain, ctx)
        return AiResponse(fallback, AiSource.DETERMINISTIC_FALLBACK, System.currentTimeMillis() - t0)
    }

    // -------------------------------------------------------------------------
    // "With Someone" Peer Practice Facilitation
    // -------------------------------------------------------------------------

    suspend fun coachPeerTurn(
        spokenText: String,
        speakerRole: String,
        ctx: GemmaContext,
    ): AiResponse<PeerTurnCoachResult> {
        val t0 = System.currentTimeMillis()
        if (gemma.isAvailable && spokenText.isNotBlank()) {
            val prompt = GemmaPromptBuilder.buildPeerCoachPrompt(spokenText, speakerRole, ctx)
            val raw = gemma.generate(prompt, temperature = GemmaEngine.STRUCTURED_TEMPERATURE)
            if (!raw.isNullOrBlank()) {
                val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
                val trans = findTagValue(lines, "TRANS|TRANSLATION") ?: ""
                val better = findTagValue(lines, "BETTER|NATURAL") ?: ""
                val tip = findTagValue(lines, "TIP|COACH") ?: ""
                val next = findTagValue(lines, "NEXT|QUESTION") ?: ""
                val result = PeerTurnCoachResult(
                    speakerRole = speakerRole,
                    spokenText = spokenText,
                    translation = trans.ifBlank { spokenText },
                    betterWay = better,
                    coachTip = tip,
                    nextPromptSuggestion = next,
                    source = "gemma",
                )
                return AiResponse(result, AiSource.GEMMA, System.currentTimeMillis() - t0)
            }
        }
        val fallback = deterministic.coachPeerTurn(spokenText, speakerRole, ctx)
        return AiResponse(fallback, AiSource.DETERMINISTIC_FALLBACK, System.currentTimeMillis() - t0)
    }

    /**
     * Synthesizes a daily workplace challenge customized to the learner's profile.
     */
    suspend fun generateDailyMission(
        ctx: GemmaContext,
    ): AiResponse<DailyMission> {
        val t0 = System.currentTimeMillis()
        if (gemma.isAvailable) {
            val prompt = GemmaPromptBuilder.buildDailyMissionPrompt(ctx)
            val raw = gemma.generate(prompt, temperature = GemmaEngine.STRUCTURED_TEMPERATURE)
            if (!raw.isNullOrBlank()) {
                val parsed = parseDailyMission(raw, ctx)
                if (parsed != null) {
                    return AiResponse(parsed, AiSource.GEMMA, System.currentTimeMillis() - t0)
                }
            }
        }
        val fallback = deterministic.generateDailyMission(ctx)
        return AiResponse(fallback, AiSource.DETERMINISTIC_FALLBACK, System.currentTimeMillis() - t0)
    }

    /**
     * Analyzes an overheard or repeated workplace phrase in [ctx.l2],
     * returning meaning, tone/intent, key vocabulary, and an actionable natural reply.
     */
    suspend fun analyzeHeardPhrase(
        phrase: String,
        ctx: GemmaContext,
    ): AiResponse<HeardPhraseAnalysis> {
        val t0 = System.currentTimeMillis()
        val cleanedPhrase = phrase.trim()
        if (cleanedPhrase.isBlank()) {
            val emptyFallback = deterministic.analyzeHeardPhrase(cleanedPhrase, ctx)
            return AiResponse(emptyFallback, AiSource.DETERMINISTIC_FALLBACK, System.currentTimeMillis() - t0)
        }

        if (gemma.isAvailable) {
            val prompt = GemmaPromptBuilder.buildListenAroundPrompt(cleanedPhrase, ctx)
            val raw = gemma.generate(prompt, temperature = GemmaEngine.STRUCTURED_TEMPERATURE)
            if (!raw.isNullOrBlank()) {
                val parsed = parseHeardPhraseAnalysis(raw, cleanedPhrase, ctx)
                if (parsed != null) {
                    return AiResponse(parsed, AiSource.GEMMA, System.currentTimeMillis() - t0)
                }
            }
        }
        val fallback = deterministic.analyzeHeardPhrase(cleanedPhrase, ctx)
        return AiResponse(fallback, AiSource.DETERMINISTIC_FALLBACK, System.currentTimeMillis() - t0)
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

    private fun parseDailyMission(raw: String, ctx: GemmaContext): DailyMission? {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
        val title = findTagValue(lines, "TITLE") ?: return null
        if (LlmOutputSanitizer.hasDegenerativeRepetition(title)) {
            Log.w(TAG, "Gemma title rejected due to repetition: '$title'")
            return null
        }
        val nativeTitle = findTagValue(lines, "NATIVE_TITLE|MARATHI_TITLE") ?: title
        if (LlmOutputSanitizer.hasDegenerativeRepetition(nativeTitle)) {
            Log.w(TAG, "Gemma nativeTitle rejected due to repetition: '$nativeTitle'")
            return null
        }
        val npcRole = findTagValue(lines, "NPC_ROLE|ROLE|PERSONA") ?: "Supervisor"
        val objective = findTagValue(lines, "OBJECTIVE|GOAL") ?: "Complete the workplace conversation."
        val objectiveNative = findTagValue(lines, "OBJECTIVE_NATIVE|GOAL_NATIVE") ?: objective

        var openerL2 = findTagValue(lines, "OPENER_L2|OPENER|FIRST_TURN") ?: return null
        if (LlmOutputSanitizer.hasDegenerativeRepetition(openerL2)) {
            val sanitized = LlmOutputSanitizer.sanitize(openerL2)
            if (sanitized != null && sanitized.length >= 6) {
                openerL2 = sanitized
            } else {
                Log.w(TAG, "Gemma openerL2 rejected due to repetition loop: '$openerL2'")
                return null
            }
        }
        if (!LlmOutputSanitizer.matchesScript(openerL2, ctx.l2)) {
            Log.w(TAG, "Gemma openerL2 script mismatch for ${ctx.l2}: '$openerL2'")
            return null
        }

        var openerL1 = findTagValue(lines, "OPENER_L1|OPENER_MEANING") ?: ""
        if (openerL1.isNotBlank() && LlmOutputSanitizer.hasDegenerativeRepetition(openerL1)) {
            openerL1 = LlmOutputSanitizer.sanitize(openerL1) ?: ""
        }

        val targetWordsStr = findTagValue(lines, "TARGET_WORDS|WORDS") ?: ""
        val targetWords = targetWordsStr.split(",", ";").map { it.trim() }
            .filter { it.isNotBlank() && !LlmOutputSanitizer.hasDegenerativeRepetition(it) }
        val maxTurns = findTagValue(lines, "MAX_TURNS|TURNS")?.toIntOrNull() ?: 4

        return DailyMission(
            title = title,
            nativeTitle = nativeTitle,
            npcRole = npcRole,
            objective = objective,
            objectiveNative = objectiveNative,
            openerL2 = openerL2,
            openerL1 = openerL1,
            targetWords = if (targetWords.isNotEmpty()) targetWords else ctx.frequentlyMissedWords.take(3),
            maxTurns = maxTurns.coerceIn(3, 5),
            source = "gemma",
        )
    }

    private fun parseHeardPhraseAnalysis(raw: String, phrase: String, ctx: GemmaContext): HeardPhraseAnalysis? {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
        val meaning = findTagValue(lines, "MEANING|TRANSLATION|HINDI_MEANING") ?: return null
        if (LlmOutputSanitizer.hasDegenerativeRepetition(meaning)) return null

        val tone = findTagValue(lines, "TONE_INTENT|TONE|INTENT") ?: "सूचना / Instruction"
        val wordsRaw = findTagValue(lines, "IMPORTANT_WORDS|WORDS|VOCAB") ?: ""
        var replyL2 = findTagValue(lines, "NATURAL_REPLY|REPLY|REPLY_L2") ?: return null
        if (!LlmOutputSanitizer.isValidL2Output(replyL2, ctx.l2)) {
            val sanitized = LlmOutputSanitizer.sanitize(replyL2)
            if (sanitized != null && LlmOutputSanitizer.isValidL2Output(sanitized, ctx.l2)) {
                replyL2 = sanitized
            } else {
                Log.w(TAG, "Gemma replyL2 invalid for ${ctx.l2}: '$replyL2'")
                return null
            }
        }
        val replyL1 = findTagValue(lines, "REPLY_NATIVE|REPLY_L1|REPLY_MEANING") ?: ""
        val replyRoman = findTagValue(lines, "REPLY_ROMAN|ROMAN") ?: ""

        val wordList = mutableListOf<WordMeaning>()
        if (wordsRaw.isNotBlank()) {
            val pairs = wordsRaw.split(";", ",")
            for (p in pairs) {
                val parts = p.split("=", ":", "-").map { it.trim() }
                if (parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    wordList.add(WordMeaning(parts[0], parts[1]))
                }
            }
        }

        return HeardPhraseAnalysis(
            heardPhrase = phrase,
            meaningL1 = meaning,
            toneIntent = tone,
            importantWords = wordList,
            suggestedReplyL2 = replyL2,
            replyMeaningL1 = replyL1,
            replyRoman = replyRoman,
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
     *   L2: <reply>
     *   L1: <translation>
     *   BETTER: <better phrasing for learner>
     *   FEEDBACK: <feedback on learner's utterance>
     *   HINT: <pronunciation tip or "none">
     */
    private fun parseRoleplayResponse(raw: String, ctx: GemmaContext): DialogueTurn? {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
        var l2Text = findTagValue(lines, "L2|REPLY|RESPONSE") ?: cleanLine(raw.lines().firstOrNull() ?: "").take(100)
        if (l2Text.isBlank()) return null
        if (LlmOutputSanitizer.hasDegenerativeRepetition(l2Text)) {
            val sanitized = LlmOutputSanitizer.sanitize(l2Text)
            if (sanitized != null && sanitized.length >= 4) {
                l2Text = sanitized
            } else {
                Log.w(TAG, "Gemma roleplay response rejected due to repetition: '$l2Text'")
                return null
            }
        }
        if (!LlmOutputSanitizer.matchesScript(l2Text, ctx.l2)) {
            Log.w(TAG, "Gemma roleplay response script mismatch for ${ctx.l2}: '$l2Text'")
            return null
        }

        val l1Text = findTagValue(lines, "L1|TRANSLATION") ?: ""
        val betterWay = findTagValue(lines, "BETTER|NATURAL|POLISH") ?: ""
        val feedback = findTagValue(lines, "FEEDBACK|DIAGNOSTIC|NOTE") ?: ""
        val hintRaw = findTagValue(lines, "HINT|TIP|PRONUNCIATION") ?: ""
        val hint = if (hintRaw.equals("none", ignoreCase = true)) "" else hintRaw

        return DialogueTurn(
            speaker = "bot",
            text = l2Text,
            l1Text = l1Text,
            hint = hint,
            betterWay = betterWay,
            feedback = feedback,
        )
    }

    /**
     * Parses dynamic exercise drills generated by Gemma.
     */
    private fun parseDynamicExercises(raw: String): List<DynamicExercise> {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
        val list = mutableListOf<DynamicExercise>()

        // Drill 1 (Speaking)
        val d1Prompt = findTagValue(lines, "D1_PROMPT") ?: "वाक्य स्पष्टपणे बोला (Speak clearly)"
        val d1Target = findTagValue(lines, "D1_TARGET")
        val d1Roman = findTagValue(lines, "D1_ROMAN") ?: ""
        val d1Trans = findTagValue(lines, "D1_TRANS") ?: ""
        if (!d1Target.isNullOrBlank()) {
            list.add(DynamicExercise(
                kind = "speak",
                prompt = d1Prompt,
                targetText = d1Target,
                roman = d1Roman,
                translation = d1Trans,
            ))
        }

        // Drill 2 (Choice)
        val d2Prompt = findTagValue(lines, "D2_PROMPT") ?: "योग्य पर्याय निवडा (Choose correct option)"
        val d2Correct = findTagValue(lines, "D2_CORRECT")
        val d2Opt2 = findTagValue(lines, "D2_OPT2") ?: "मला माहित नाही"
        val d2Opt3 = findTagValue(lines, "D2_OPT3") ?: "उद्या पाहू"
        if (!d2Correct.isNullOrBlank()) {
            val options = listOf(d2Correct, d2Opt2, d2Opt3).shuffled()
            val correctIdx = options.indexOf(d2Correct)
            list.add(DynamicExercise(
                kind = "choice",
                prompt = d2Prompt,
                targetText = d2Correct,
                options = options,
                answerIndex = correctIdx,
            ))
        }

        // Drill 3 (Speaking)
        val d3Prompt = findTagValue(lines, "D3_PROMPT") ?: "कामाच्या ठिकाणी हे सांगा"
        val d3Target = findTagValue(lines, "D3_TARGET")
        val d3Roman = findTagValue(lines, "D3_ROMAN") ?: ""
        val d3Trans = findTagValue(lines, "D3_TRANS") ?: ""
        if (!d3Target.isNullOrBlank()) {
            list.add(DynamicExercise(
                kind = "speak",
                prompt = d3Prompt,
                targetText = d3Target,
                roman = d3Roman,
                translation = d3Trans,
            ))
        }

        return list
    }

    companion object {
        private const val TAG = "SeedheBolAi"
    }
}

/** Official SeedheBol AI layer alias. */
typealias SeedheBolAiLayer = BoliAiLayer

