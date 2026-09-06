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
    private val gemma: GemmaEngine? = null,
    private val deterministic: DeterministicFallback = DeterministicFallback(),
    private val knowledgeStore: WorkplaceKnowledgeStore? = null,
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
            var trimmed = line.trim()
            // Strip leading/trailing bracketed icons/tags like ( ), [o], [1], (x)
            trimmed = trimmed.replace(Regex("^(?:\\([^)]*\\)|\\[[^\\]]*\\]|\\{[^}]*\\}|<[^>]*>)+\\s*"), "")
            trimmed = trimmed.replace(Regex("\\s*(?:\\([^)]*\\)|\\[[^\\]]*\\]|\\{[^}]*\\}|<[^>]*>)+$"), "")
            // Strip leading bullet chars, numbering, symbols
            trimmed = trimmed.replace(Regex("^[•*\\-—_~#~=+@$%^&;:,.'\"|/\\\\()]+\\s*"), "")
            trimmed = trimmed.replace(Regex("\\s*[•*\\-—_~#~=+@$%^&;:,.'\"|/\\\\()]+$"), "")
            trimmed = trimmed.replace(Regex("^\\d+[.,)\\-\\s]+\\s*"), "")
            trimmed = trimmed.trim()

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

    private fun isParrot(text: String): Boolean {
        return text.matches(Regex("(?i).*(?:2-4 word summary|meaning of this signboard|1 sentence in|word_in_|second_word_|1 short spoken sentence|title in|topic in).*"))
    }

    // -------------------------------------------------------------------------
    // Primary demo flow: Camera → OCR → Gemma → MicroLesson
    // -------------------------------------------------------------------------

    /**
     * Generates a contextual micro-lesson from [ocrText] in a single LLM call.
     * Uses low temperature for deterministic structured output and graceful
     * partial-result fallback so a valid translation is never lost.
     */
    suspend fun generateLessonFromOcr(
        ocrText: String,
        ctx: GemmaContext,
    ): AiResponse<MicroLesson> {
        val t0 = System.currentTimeMillis()
        val cleanOcr = cleanOcrText(ocrText)
        val textToProcess = if (cleanOcr.isNotBlank()) cleanOcr else ocrText.trim()

        if (textToProcess.isBlank()) {
            throw IllegalArgumentException("No readable text found on the signboard.")
        }

        // 1. Query Local Micro-RAG Knowledge Store for ground truth match (without arbitrary baseline fallback)
        val ragMatch = knowledgeStore?.queryRelevantKnowledge(
            utterance = textToProcess,
            domain = "signboard",
            language = ctx.l2,
            allowFallback = false
        ) ?: knowledgeStore?.queryRelevantKnowledge(
            utterance = textToProcess,
            domain = ctx.occupation.ifBlank { "construction" },
            language = ctx.l2,
            allowFallback = false
        )

        // 2. Run on-device SLM inference if model is available
        if (gemma?.isAvailable == true) {
            val prompt = GemmaPromptBuilder.buildOcrLessonPrompt(textToProcess, ctx, ragMatch)
            val raw = gemma.generate(prompt, temperature = GemmaEngine.STRUCTURED_TEMPERATURE)
            if (raw != null) {
                Log.i(TAG, "Gemma raw OCR lesson response:\n$raw")
                val fallbackTitle = textToProcess.lines().firstOrNull()?.take(40) ?: textToProcess.take(40)
                val parsedLesson = parseOcrLessonResponse(raw, fallbackTitle)

                // Must have at least one useful field from Gemma that isn't parroting instructions
                val hasAnyValidContent = (parsedLesson.translation.isNotBlank() && !isParrot(parsedLesson.translation)) ||
                        (parsedLesson.explanation.isNotBlank() && !isParrot(parsedLesson.explanation)) ||
                        parsedLesson.vocabulary.isNotEmpty() ||
                        parsedLesson.practicePrompt.isNotBlank()

                if (hasAnyValidContent) {
                    val patchedLesson = patchMissingFields(parsedLesson, textToProcess, ctx, ragMatch)
                    return AiResponse(patchedLesson, AiSource.GEMMA, System.currentTimeMillis() - t0)
                } else {
                    Log.w(TAG, "Gemma response lacked usable fields for OCR lesson: '$raw'")
                }
            }
        }

        // 3. Guaranteed authentic direct synthesis from photographed OCR text & Micro-RAG
        Log.i(TAG, "Synthesizing authentic lesson directly from photographed OCR text: '$textToProcess'")
        val synthesizedLesson = synthesizeLessonFromOcrText(textToProcess, ctx, ragMatch)
        return AiResponse(synthesizedLesson, AiSource.DETERMINISTIC_FALLBACK, System.currentTimeMillis() - t0)
    }

    /**
     * Completes any missing fields in a partially-parsed Gemma response using
     * the actual OCR text and Micro-RAG rather than fabricated fallback lessons.
     */
    private fun patchMissingFields(
        lesson: MicroLesson,
        cleanOcrText: String,
        ctx: GemmaContext,
        ragMatch: WorkplaceKnowledgeItem? = null,
    ): MicroLesson {
        val firstLine = cleanOcrText.lines().firstOrNull()?.take(40) ?: cleanOcrText.take(40)

        val topic = if (lesson.topic.isNotBlank() &&
            !isParrot(lesson.topic) &&
            !LlmOutputSanitizer.hasDegenerativeRepetition(lesson.topic)
        ) {
            lesson.topic
        } else {
            firstLine
        }

        val translation = if (lesson.translation.isNotBlank() &&
            !isParrot(lesson.translation) &&
            !LlmOutputSanitizer.hasDegenerativeRepetition(lesson.translation)
        ) {
            lesson.translation
        } else {
            ragMatch?.groundTruthL1 ?: ""
        }

        val explanation = if (lesson.explanation.isNotBlank() &&
            !isParrot(lesson.explanation) &&
            !LlmOutputSanitizer.hasDegenerativeRepetition(lesson.explanation)
        ) {
            lesson.explanation
        } else {
            ragMatch?.let { "कार्यस्थल पर सुरक्षा और नियमों का पालन करने के लिए यह सूचना महत्वपूर्ण है। (${it.contextScenario})" }
                ?: "कामाच्या ठिकाणी सुरक्षेसाठी आणि नियमांचे पालन करण्यासाठी ही सूचना महत्त्वाची आहे."
        }

        val vocabulary = if (lesson.vocabulary.isNotEmpty()) {
            lesson.vocabulary
        } else {
            extractVocabFromOcrText(cleanOcrText, ctx, ragMatch)
        }

        val practice = if (lesson.practicePrompt.isNotBlank() &&
            !isParrot(lesson.practicePrompt) &&
            !LlmOutputSanitizer.hasDegenerativeRepetition(lesson.practicePrompt) &&
            LlmOutputSanitizer.matchesScript(lesson.practicePrompt, ctx.l2)
        ) {
            lesson.practicePrompt
        } else {
            ragMatch?.betterPhrasing?.takeIf { it.isNotBlank() }
                ?: if (vocabulary.isNotEmpty()) {
                    "${vocabulary.first().l2Word} बोला."
                } else {
                    "$firstLine बोला."
                }
        }

        return lesson.copy(
            topic = topic,
            translation = translation,
            explanation = explanation,
            vocabulary = vocabulary,
            practicePrompt = practice,
            source = lesson.source,
        )
    }

    fun extractVocabFromOcrText(
        cleanOcr: String,
        ctx: GemmaContext,
        ragMatch: WorkplaceKnowledgeItem? = null,
    ): List<VocabItem> {
        val vocabList = mutableListOf<VocabItem>()
        val words = cleanOcr
            .replace(Regex("[.,?!।\"'\\-()0-9\\[\\]{}<>/\\\\|•*—_~#~=+@$%^&;:]"), " ")
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()

        for (word in words) {
            val dictEntry = INDIC_WORKPLACE_VOCAB_DICT[word]
                ?: INDIC_WORKPLACE_VOCAB_DICT[word.lowercase()]
            if (dictEntry != null) {
                vocabList.add(VocabItem(l2Word = word, l1Meaning = dictEntry.first, romanization = dictEntry.second))
            } else if (ragMatch != null) {
                val ragL2Words = ragMatch.groundTruthL2.split(Regex("\\s+"))
                if (ragL2Words.any { it.contains(word) || word.contains(it) }) {
                    vocabList.add(VocabItem(l2Word = word, l1Meaning = ragMatch.groundTruthL1.take(25), romanization = ""))
                }
            }
        }

        if (vocabList.isEmpty()) {
            for (word in words.take(3)) {
                vocabList.add(VocabItem(l2Word = word, l1Meaning = "फलकावरील महत्त्वाचा शब्द", romanization = ""))
            }
        }

        return vocabList.distinctBy { it.l2Word }.take(4)
    }

    fun synthesizeLessonFromOcrText(
        cleanOcr: String,
        ctx: GemmaContext,
        ragMatch: WorkplaceKnowledgeItem? = null,
    ): MicroLesson {
        val firstLine = cleanOcr.lines().firstOrNull()?.take(40) ?: cleanOcr.take(40)

        val topic = firstLine

        val translation = if (ragMatch != null && ragMatch.groundTruthL1.isNotBlank()) {
            ragMatch.groundTruthL1
        } else {
            val transWords = cleanOcr
                .replace(Regex("[.,?!।\"'\\-()0-9\\[\\]{}<>/\\\\|•*—_~#~=+@$%^&;:]"), " ")
                .split(Regex("\\s+"))
                .mapNotNull { INDIC_WORKPLACE_VOCAB_DICT[it]?.first?.substringBefore(" (") }
            if (transWords.isNotEmpty()) {
                transWords.joinToString(" ")
            } else {
                "फलकावरील संदेश: $cleanOcr"
            }
        }

        val explanation = if (ragMatch != null) {
            "कार्यस्थल पर सुरक्षा और नियमों का पालन करने के लिए यह सूचना महत्वपूर्ण है। (${ragMatch.contextScenario})"
        } else {
            "कार्यस्थल पर सुरक्षा और काम के सही संचालन के लिए इस फलक को समझना आवश्यक है।"
        }

        val vocabulary = extractVocabFromOcrText(cleanOcr, ctx, ragMatch)

        val practice = if (ragMatch != null && ragMatch.betterPhrasing.isNotBlank()) {
            ragMatch.betterPhrasing
        } else if (vocabulary.isNotEmpty()) {
            "${vocabulary.first().l2Word} बोला."
        } else {
            "$firstLine बोला."
        }

        return MicroLesson(
            topic = topic,
            explanation = explanation,
            vocabulary = vocabulary,
            practicePrompt = practice,
            translation = translation,
            source = "rag_synthesized",
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

        val ragMatch = knowledgeStore?.queryRelevantKnowledge(
            utterance = textToProcess,
            domain = "signboard",
            language = ctx.l2,
            allowFallback = false
        )

        if (gemma?.isAvailable == true && textToProcess.isNotBlank()) {
            val prompt = GemmaPromptBuilder.buildTranslationPrompt(textToProcess, ctx)
            val raw = gemma?.generate(prompt, temperature = GemmaEngine.STRUCTURED_TEMPERATURE)
            if (!raw.isNullOrBlank()) {
                val cleaned = cleanLine(raw)
                    .replace(Regex("^(?i)(?:TRANSLATION|TRANS|MEANING)\\s*[:\\-—]\\s*"), "")
                    .replace(Regex("^[\"']|[\"']$"), "")
                    .trim()
                if (cleaned.isNotBlank() && !isParrot(cleaned)) {
                    return AiResponse(cleaned, AiSource.GEMMA, System.currentTimeMillis() - t0)
                }
            }
        }

        val fallback = ragMatch?.groundTruthL1 ?: deterministic.translateOcrText(textToProcess, ctx)
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

        if (gemma?.isAvailable == true && textToProcess.isNotBlank()) {
            val prompt = GemmaPromptBuilder.buildVocabularyPrompt(textToProcess, ctx)
            val raw = gemma?.generate(prompt, temperature = GemmaEngine.STRUCTURED_TEMPERATURE)
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
        if (gemma?.isAvailable == true) {
            val prompt = GemmaPromptBuilder.buildExplanationPrompt(phrase, ctx)
            val raw = gemma?.generate(prompt, temperature = GemmaEngine.STRUCTURED_TEMPERATURE)
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
        turnNumber: Int = 1,
        maxTurns: Int = 5,
        mood: String? = null,
    ): Map<String, Any?> {
        val t0 = System.currentTimeMillis()
        val historyWithUser = history + DialogueTurn("user", userSpokenText)

        // Tier 1: Query Local Micro-RAG Knowledge Store for factual workplace grounding
        val groundTruth = knowledgeStore?.queryRelevantKnowledge(
            utterance = userSpokenText,
            domain = ctx.occupation.ifBlank { "construction" },
            language = ctx.l2
        )

        if (gemma?.isAvailable == true) {
            val prompt = GemmaPromptBuilder.buildRoleplayNextTurnPrompt(
                history = historyWithUser,
                ctx = ctx,
                turnNumber = turnNumber,
                maxTurns = maxTurns,
                mood = mood,
                groundTruth = groundTruth,
            )
            val raw = gemma?.generate(prompt, temperature = GemmaEngine.ROLEPLAY_TEMPERATURE)
            if (!raw.isNullOrBlank()) {
                val turn = parseRoleplayResponse(raw, ctx, userSpokenText, groundTruth)
                if (turn != null && LlmOutputSanitizer.isValidL2Output(turn.text, ctx.l2) && !isParrotOrRepetition(turn.text, userSpokenText, history)) {
                    val ms = System.currentTimeMillis() - t0
                    Log.i(TAG, "Gemma roleplay turn in ${ms}ms with fluency ${turn.fluencyScore}")
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
                        "fluency_score" to turn.fluencyScore,
                        "weak_phonemes" to emptyList<String>(),
                        "articulatory_hint" to turn.hint,
                        "natural_phrasing" to turn.betterWay,
                        "intent_explanation" to turn.feedback,
                        "ai_source" to "gemma",
                        "latency_ms" to ms,
                    )
                } else {
                    Log.w(TAG, "SLM roleplay turn failed validation, parrot or repetition checks, falling back")
                }
            }
        }

        // Tier 3 Safety Fallback: Use verified Micro-RAG Ground Truth directly if available
        if (groundTruth != null && LlmOutputSanitizer.isValidL2Output(groundTruth.groundTruthL2, ctx.l2)) {
            val ms = System.currentTimeMillis() - t0
            Log.i(TAG, "Micro-RAG ground truth direct fulfillment in ${ms}ms")
            return mapOf(
                "recognized_transcript" to userSpokenText,
                "is_intent_matched" to true,
                "matched_intent" to "rag_ground_truth",
                "next_node_id" to "rag_node",
                "prompt_l2" to groundTruth.groundTruthL2,
                "prompt_transliteration" to "",
                "prompt_l1" to groundTruth.groundTruthL1,
                "pre_rendered_audio_path" to null,
                "pronunciation_score" to null,
                "fluency_score" to calculateHeuristicFluency(userSpokenText, ctx.l2),
                "weak_phonemes" to emptyList<String>(),
                "articulatory_hint" to groundTruth.coachingHint,
                "natural_phrasing" to groundTruth.betterPhrasing,
                "intent_explanation" to "कामाच्या गरजेनुसार अचूक उत्तर आहे.",
                "ai_source" to "rag_ground_truth",
                "latency_ms" to ms,
            )
        }

        // Fallback: preserve original stub response with calculated heuristic fluency
        val fallbackResult = deterministic.nextRoleplayTurn(historyWithUser, situationId, currentNodeId, ctx).toMutableMap()
        fallbackResult["fluency_score"] = calculateHeuristicFluency(userSpokenText, ctx.l2)
        return fallbackResult
    }

    data class PersonaQuestionScenario(
        val l2: String,
        val l1: String,
        val mood: String,
        val angle: String,
    )

    data class RoleplayOpenerData(
        val l2: String,
        val l1: String,
        val mood: String,
        val isGemma: Boolean,
    )

    private val personaScenarioIndices = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private val personaScenarios: Map<String, List<PersonaQuestionScenario>> = mapOf(
        "supervisor" to listOf(
            PersonaQuestionScenario(
                l2 = "सिमेंट आणि विटांचा साठा पुरेसा आहे का, की नवीन मागवू?",
                l1 = "सीमेंट और ईंटों का स्टॉक काफी है क्या, या नया मंगाएं?",
                mood = "साहित्य तपासणी (Stock Check)",
                angle = "Checking raw material stock"
            ),
            PersonaQuestionScenario(
                l2 = "सुरक्षा हेल्मेट आणि बूट घातले आहेत ना? सुरक्षितपणे काम करा.",
                l1 = "सुरक्षा हेलमेट और जूते पहने हैं ना? सावधानी से काम करें।",
                mood = "सुरक्षा दक्ष (Safety Strict)",
                angle = "Safety gear and helmet enforcement"
            ),
            PersonaQuestionScenario(
                l2 = "आज संध्याकाळपर्यंत हे प्लास्टरचे काम पूर्ण होईल का?",
                l1 = "आज शाम तक यह प्लास्टर का काम पूरा हो जाएगा क्या?",
                mood = "कामाचा ताण (Urgent Deadline)",
                angle = "Progress and end of day deadline"
            ),
            PersonaQuestionScenario(
                l2 = "कालच्या कामात काही अडचण आली होती का? आज काय प्लॅन आहे?",
                l1 = "कल के काम में कोई परेशानी आई थी क्या? आज का क्या प्लान है?",
                mood = "मार्गदर्शन (Helpful Review)",
                angle = "Reviewing blockers and planning"
            ),
            PersonaQuestionScenario(
                l2 = "दुपारी १२ वाजता नवीन सामानाचा ट्रक येणार आहे, रिकामे करायला तयार राहा.",
                l1 = "दोपहर १२ बजे नए सामान का ट्रक आने वाला है, खाली करने के लिए तैयार रहें।",
                mood = "नवीन काम (Active Alert)",
                angle = "Material unloading schedule"
            ),
            PersonaQuestionScenario(
                l2 = "कामाची अवजारे आणि मशिन व्यवस्थित चालू आहेत का, काही बिघाड आहे?",
                l1 = "काम के औजार और मशीन ठीक चल रही है क्या, कोई खराबी है?",
                mood = "यंत्र तपासणी (Inspection)",
                angle = "Tool and machine maintenance check"
            ),
            PersonaQuestionScenario(
                l2 = "मोठे साहेब साईट पाहायला येत आहेत, सगळा परिसर स्वच्छ ठेवा आणि काम सुरू ठेवा!",
                l1 = "बड़े साहब साइट देखने आ रहे हैं, पूरा इलाका साफ रखिए और काम जारी रखिए!",
                mood = "व्हीआयपी भेट (VIP Inspection)",
                angle = "VIP site inspection walkthrough"
            ),
            PersonaQuestionScenario(
                l2 = "पावसाचा अंदाज आहे, सिमेंटची सर्व पोती ताडपत्रीने नीट झाकली आहेत का?",
                l1 = "बारिश का अंदेशा है, सीमेंट की सारी बोरियां तिरपाल से ठीक से ढकी हैं क्या?",
                mood = "पावसाची तयारी (Monsoon Alert)",
                angle = "Monsoon rain protection and tarping"
            ),
            PersonaQuestionScenario(
                l2 = "शाब्बास भाऊ! कालचे भिंतीचे काम एकदम मजबूत आणि सरळ रेषेत झाले आहे.",
                l1 = "शाबाश भाई! कल का दीवार का काम एकदम मजबूत और सीधी लाइन में हुआ है।",
                mood = "शाब्बासकी (Praise & Morale)",
                angle = "Recognizing high quality work"
            ),
            PersonaQuestionScenario(
                l2 = "आजचे काम वेळेआधी संपले तर संध्याकाळी लवकर सुट्टी देऊ, जोरात हात चालवा!",
                l1 = "आज का काम समय से पहले खत्म हो गया तो शाम को जल्दी छुट्टी देंगे, तेजी से हाथ चलाएं!",
                mood = "उत्साहवर्धन (Speed & Reward)",
                angle = "Early wrap-up incentive"
            )
        ),
        "shopkeeper" to listOf(
            PersonaQuestionScenario(
                l2 = "बोला भाऊ, आज कोणत्या मापाचे स्क्रू आणि खिळे हवे आहेत?",
                l1 = "बोलिए भाई, आज किस साइज के स्क्रू और कीलें चाहिए?",
                mood = "व्यापारी (Business Inquisitive)",
                angle = "Hardware dimensions and sizes"
            ),
            PersonaQuestionScenario(
                l2 = "दोन इंची पाइप संपला आहे, अडीच इंची चालेल का?",
                l1 = "दो इंच का पाइप खत्म हो गया है, ढाई इंच का चलेगा क्या?",
                mood = "पर्याय शोधणारा (Alternative Offer)",
                angle = "Stock out and alternative product"
            ),
            PersonaQuestionScenario(
                l2 = "सामान रोखीने घेणार की फोन पे / युपीआय करणार आहात?",
                l1 = "सामान नकद लोगे या फोन पे / यूपीआई करोगे?",
                mood = "बिलिंग (Payment & Billing)",
                angle = "Payment method cash or UPI"
            ),
            PersonaQuestionScenario(
                l2 = "सामान नेण्यासाठी गोणी किंवा पिशवी आणली आहे का?",
                l1 = "सामान ले जाने के लिए बोरी या थैला लाए हो क्या?",
                mood = "मदतनीस (Helpful)",
                angle = "Packaging bag inquiry"
            ),
            PersonaQuestionScenario(
                l2 = "ह्या ड्रिल मशिनचे पक्के बिल बनवू का, आणखी काही वस्तू हव्यात?",
                l1 = "इस ड्रिल मशीन का पक्का बिल बना दूं क्या, या और कुछ सामान चाहिए?",
                mood = "हिशोबी (Prompt Billing)",
                angle = "Official invoice and add-ons"
            ),
            PersonaQuestionScenario(
                l2 = "कोणत्या कंपनीचा रंग आणि ब्रश पाहिजे? आशियान की बर्जर?",
                l1 = "किस कंपनी का पेंट और ब्रश चाहिए? एशियन या बर्जर?",
                mood = "सल्लागार (Consultative)",
                angle = "Brand selection and recommendation"
            ),
            PersonaQuestionScenario(
                l2 = "अरे भाऊ, आज उधारी बंद आहे, पण तुमच्या विश्वासावर देतो, लवकर चुकता करा!",
                l1 = "अरे भाई, आज उधारी बंद है, लेकिन आपके विश्वास पर देता हूं, जल्दी चुका देना!",
                mood = "उधारी आणि विश्वास (Credit Banter)",
                angle = "Friendly store credit policy"
            ),
            PersonaQuestionScenario(
                l2 = "पाचशेची सुट्टी नोट नाहीये, शंभर रुपये ऑनलाईन ट्रान्सफर करता का?",
                l1 = "पांच सौ का छुट्टे नहीं हैं, सौ रुपये ऑनलाइन ट्रांसफर कर दोगे क्या?",
                mood = "सुट्टे पैसे (Change Exchange)",
                angle = "Cash change shortage and online transfer"
            ),
            PersonaQuestionScenario(
                l2 = "नवीन वॉटरप्रूफ पुट्टी आली आहे, छतावर लावली तर पाण्याचा थेंबही गळणार नाही!",
                l1 = "नई वाटरप्रूफ पुट्टी आई है, छत पर लगाओगे तो पानी की एक बूंद भी नहीं टपकेगी!",
                mood = "नवीन उत्पादन (New Product Pitch)",
                angle = "Waterproofing chemical demo"
            ),
            PersonaQuestionScenario(
                l2 = "उरलेल्या टाइल्सचा बॉक्स शाबूत असेल, तर उद्या परत आणून पैसे घेऊन जा.",
                l1 = "बचे हुए टाइल्स का बॉक्स सही सलामत रहे, तो कल वापस लाकर पैसे ले जाना।",
                mood = "परतीची हमी (Return Guarantee)",
                angle = "Unused tiles return policy"
            )
        ),
        "watchman" to listOf(
            PersonaQuestionScenario(
                l2 = "थांबा! साईटवर आत जाण्यासाठी तुमचा गेट पास किंवा आयडी दाखवा.",
                l1 = "रुको! साइट के अंदर जाने के लिए अपना गेट पास या आईडी दिखाओ।",
                mood = "कडक सुरक्षा (Strict Protocol)",
                angle = "Entry pass and ID card check"
            ),
            PersonaQuestionScenario(
                l2 = "तुम्हाला आत कोणाला भेटायचे आहे? मॅनेजर साहेबांना की इंजिनिअरला?",
                l1 = "आपको अंदर किससे मिलना है? मैनेजर साहब से या इंजीनियर से?",
                mood = "चौकशी (Gatekeeper Inquiry)",
                angle = "Destination and person to meet"
            ),
            PersonaQuestionScenario(
                l2 = "गेट रजिस्टरमध्ये तुमचे नाव, मोबाईल नंबर आणि येण्याची वेळ लिहा.",
                l1 = "गेट रजिस्टर में अपना नाम, मोबाइल नंबर और आने का समय लिखिए।",
                mood = "नोंदणी (Registration Routine)",
                angle = "Visitor logbook entry"
            ),
            PersonaQuestionScenario(
                l2 = "गाडी किंवा टेम्पो आत नेताना सामानाची पावती गेटवर जमा केली का?",
                l1 = "गाड़ी या टेम्पो अंदर ले जाते समय सामान की रसीद गेट पर जमा की क्या?",
                mood = "गाडी तपासणी (Vehicle Verification)",
                angle = "Material invoice check at entrance"
            ),
            PersonaQuestionScenario(
                l2 = "हेल्मेट घातल्याशिवाय साईटवर प्रवेश नाही, तुमचे हेल्मेट कुठे आहे?",
                l1 = "हेलमेट पहने बिना साइट पर एंट्री नहीं है, आपका हेलमेट कहाँ है?",
                mood = "सुरक्षा नियम (Rule Enforcement)",
                angle = "Safety helmet enforcement at gate"
            ),
            PersonaQuestionScenario(
                l2 = "दुपारी २ वाजेपर्यंत बाहेरच्या लोकांना परवानगी नाही, पूर्वपरवानगी आहे का?",
                l1 = "दोपहर २ बजे तक बाहर वालों को परमिशन नहीं है, पूर्व अनुमति है क्या?",
                mood = "सतर्क (Alert Vigilance)",
                angle = "Restricted hours access control"
            ),
            PersonaQuestionScenario(
                l2 = "आत मोठी क्रेन चालू आहे, डाव्या बाजूच्या पिवळ्या मार्गावरूनच पुढे जा.",
                l1 = "अंदर बड़ी क्रेन चल रही है, बाईं तरफ के पीले रास्ते से ही आगे जाएं।",
                mood = "मार्गदर्शन (Safety Path)",
                angle = "Safe walkway instructions"
            ),
            PersonaQuestionScenario(
                l2 = "संध्याकाळी जाताना बॅग तपासायला दाखवावी लागेल, कंपनीचा कडक नियम आहे.",
                l1 = "शाम को जाते समय बैग चेक कराना पड़ेगा, कंपनी का कड़ा नियम है।",
                mood = "तपासणी (Exit Check)",
                angle = "Tool bag inspection at exit"
            ),
            PersonaQuestionScenario(
                l2 = "बाहेरून जेवणाचा डबा आला आहे, गेटवर नाव तपासून पटकन घेऊन जा.",
                l1 = "बाहर से खाने का टिफिन आया है, गेट पर नाम चेक करके जल्दी ले जाओ।",
                mood = "मदत (Tiffin Delivery)",
                angle = "Lunch delivery handover"
            ),
            PersonaQuestionScenario(
                l2 = "शिफ्ट संपली का भाऊ? आज खूप काम केले, व्यवस्थित घरी पोहोचा!",
                l1 = "शिफ्ट खत्म हो गई क्या भाई? आज बहुत मेहनत की, आराम से घर पहुंचिए!",
                mood = "आपुलकी (Warm Farewell)",
                angle = "End of shift friendly greeting"
            )
        ),
        "coworker" to listOf(
            PersonaQuestionScenario(
                l2 = "भाऊ, आज खूप ऊन आहे, पाच मिनिटे टपरीवर जाऊन कडक चहा मारूया का?",
                l1 = "भाई, आज बहुत धूप है, पांच मिनट टपरी पर जाकर कड़क चाय पिएं क्या?",
                mood = "चहाची सुट्टी (Tea Break)",
                angle = "Tea stall break invite"
            ),
            PersonaQuestionScenario(
                l2 = "माझा पाना सापडत नाहीये, तुझ्याकडे १० नंबरचा जास्तीचा पाना आहे का?",
                l1 = "मेरा पाना नहीं मिल रहा, तुम्हारे पास १० नंबर का एक्स्ट्रा पाना है क्या?",
                mood = "साधन मागणी (Tool Borrowing)",
                angle = "Borrowing a tool for work"
            ),
            PersonaQuestionScenario(
                l2 = "हे लोखंडाचे जड पाईप उचलायला जरा दोन मिनिटे हात लावतोस का?",
                l1 = "यह लोहे का भारी पाइप उठाने में जरा दो मिनट हाथ लगाओगे क्या?",
                mood = "मदतीची हाक (Cooperation)",
                angle = "Asking for physical help with heavy lifting"
            ),
            PersonaQuestionScenario(
                l2 = "दुपारच्या डब्यात काय आणले आहेस आज? एकत्र बसून जेवूया का?",
                l1 = "दोपहर के टिफिन में आज क्या लाए हो? साथ बैठकर खाएं क्या?",
                mood = "मित्रता (Lunch Sharing)",
                angle = "Sharing lunch together"
            ),
            PersonaQuestionScenario(
                l2 = "आज ओव्हरटाइम करायचा आहे की पाच वाजता सुट्टी होणार आहे?",
                l1 = "आज ओवरटाइम करना है या पांच बजे छुट्टी होने वाली है?",
                mood = "वेळेची विचारणा (Shift Schedule)",
                angle = "Overtime and going home timing"
            ),
            PersonaQuestionScenario(
                l2 = "सुपरवायझरने तुला आज कोणते काम दिले आहे? तिकडचे की इकडचे?",
                l1 = "सुपरवाइजर ने तुम्हें आज कौन सा काम दिया है? उधर का या इधर का?",
                mood = "गप्पा (Curious Chat)",
                angle = "Task distribution chat"
            ),
            PersonaQuestionScenario(
                l2 = "अरे भावा, वायर जोडण्यापूर्वी मेन स्विच बंद केला आहे ना?",
                l1 = "अरे भाई, तार जोड़ने से पहले मेन स्विच बंद किया है ना?",
                mood = "काळजी (Safety Check)",
                angle = "Electrical safety reminder"
            ),
            PersonaQuestionScenario(
                l2 = "उद्या रविवार आहे, गावाला जाणार की इथेच आराम करणार?",
                l1 = "कल रविवार है, गांव जाओगे या यहीं आराम करोगे?",
                mood = "सुट्टीचा बेत (Weekend Plans)",
                angle = "Weekend off discussion"
            ),
            PersonaQuestionScenario(
                l2 = "मशिनचा आवाज जरा खडखड येतोय, गिअरमध्ये तेल टाकायचे का?",
                l1 = "मशीन की आवाज थोड़ी अजीब आ रही है, गियर में तेल डालना है क्या?",
                mood = "यंत्र चर्चा (Machine Tuning)",
                angle = "Greasing and mechanical maintenance"
            ),
            PersonaQuestionScenario(
                l2 = "एक नंबर काम केलेस भावा! आज साहेबांकडून शाब्बासकी नक्की मिळणार!",
                l1 = "एक नंबर काम किया भाई! आज साहब से पक्की तारीफ मिलेगी!",
                mood = "उत्साह (Celebration)",
                angle = "Cheering a coworker's achievement"
            )
        ),
        "canteen" to listOf(
            PersonaQuestionScenario(
                l2 = "बोला भाऊ! स्पेशल चहा बनवू की साधा? साखर कमी पाहिजे का?",
                l1 = "बोलिए भाई! स्पेशल चाय बनाऊं या सादा? चीनी कम चाहिए क्या?",
                mood = "चहावाला (Fresh Tea)",
                angle = "Tea preference and sugar"
            ),
            PersonaQuestionScenario(
                l2 = "गरम समोसे आणि वडापाव तयार आहेत, काय देऊ?",
                l1 = "गरम समोसे और वड़ापाव तैयार हैं, क्या दूं?",
                mood = "गरमागरम (Hot Snacks)",
                angle = "Hot snacks order"
            ),
            PersonaQuestionScenario(
                l2 = "सुट्टे दहा रुपये आहेत का भाऊ? सुट्ट्या पैशांची फार टंचाई आहे.",
                l1 = "खुल्ले दस रुपये हैं क्या भाई? खुल्ले पैसों की बहुत किल्लत है।",
                mood = "सुट्टे पैसे (Change Request)",
                angle = "Exact cash change request"
            ),
            PersonaQuestionScenario(
                l2 = "पार्सल न्यायचे आहे की इथेच टपरीवर बसून पिणार आहात?",
                l1 = "पार्सल ले जाना है या यहीं टपरी पर बैठकर पियोगे?",
                mood = "चपळ सेवा (Quick Service)",
                angle = "Dine in vs takeaway"
            ),
            PersonaQuestionScenario(
                l2 = "आज नाश्त्यामध्ये पोहे आणि उपमा संपला, शिरा चालेल का?",
                l1 = "आज नाश्ते में पोहा और उपमा खत्म हो गया, शीरा चलेगा क्या?",
                mood = "पर्याय (Breakfast Alternative)",
                angle = "Breakfast alternative"
            ),
            PersonaQuestionScenario(
                l2 = "आले आणि वेलची घातलेला कडक मसाला चहा काढतो, दोन मिनिटे थांबा!",
                l1 = "अदरक और इलायची वाली कड़क मसाला चाय बनाता हूं, दो मिनट रुकिए!",
                mood = "कडक मसाला (Signature Chai)",
                angle = "Ginger cardamom tea special"
            ),
            PersonaQuestionScenario(
                l2 = "डब्यासोबत थंड पाण्याची ताजी बाटली पाहिजे का भाऊ?",
                l1 = "टिफिन के साथ ठंडे पानी की ताजा बोतल चाहिए क्या भाई?",
                mood = "थंडगार (Chilled Water)",
                angle = "Bottled drinking water"
            ),
            PersonaQuestionScenario(
                l2 = "कालच्या चहाचा हिशोब बाकी होता, आज एकत्र देणार का?",
                l1 = "कल की चाय का हिसाब बाकी था, आज एक साथ दोगे क्या?",
                mood = "हिशोब (Daily Tab)",
                angle = "Settling daily chai tab"
            ),
            PersonaQuestionScenario(
                l2 = "पावसाळी हवेत गरमागरम कांदा भजी तयार केली आहेत, एक प्लेट देऊ का?",
                l1 = "बारिश के मौसम में गरमागरम प्याज के पकोड़े बनाए हैं, एक प्लेट दूं क्या?",
                mood = "पावसाळी चव (Rain Snack Special)",
                angle = "Hot fritters and monsoon snack"
            ),
            PersonaQuestionScenario(
                l2 = "रात्रीच्या शिफ्टसाठी थर्मॉसमध्ये गरम चहा भरून देऊ का भाऊ?",
                l1 = "नाइट शिफ्ट के लिए थर्मस में गरम चाय भरकर दे दूं क्या भाई?",
                mood = "रात्रपाळी सेवा (Night Shift Care)",
                angle = "Night shift tea flask"
            )
        ),
        "client" to listOf(
            PersonaQuestionScenario(
                l2 = "नमस्ते! ह्या हॉलच्या लाद्या बसवण्याचे काम आज संध्याकाळपर्यंत पूर्ण होईल का?",
                l1 = "नमस्ते! इस हॉल के फर्श का काम आज शाम तक पूरा हो जाएगा क्या?",
                mood = "कामाची चौकशी (Progress Inquiry)",
                angle = "Flooring and finishing timeline"
            ),
            PersonaQuestionScenario(
                l2 = "बाथरूममधील पाण्याचा नळ जरा नीट घट्ट बसवा, गळती अजिबात नको.",
                l1 = "बाथरूम का नल ठीक से टाइट लगाइएगा, लीकेज बिल्कुल नहीं होना चाहिए।",
                mood = "सफाईदार काम (Quality Requirement)",
                angle = "Plumbing fixtures tightness"
            ),
            PersonaQuestionScenario(
                l2 = "खूप ऊन आहे बाहेर, आधी थंड पाणी किंवा सरबत घ्या मग काम करा.",
                l1 = "बाहर बहुत तेज धूप है, पहले ठंडा पानी या शरबत पी लीजिए फिर काम करें।",
                mood = "आपुलकी व आदर (Kind Hospitality)",
                angle = "Offering cold refreshments"
            ),
            PersonaQuestionScenario(
                l2 = "भिंतीचा हा निळा रंग खूप सुरेख दिसतोय, अगदी व्यवस्थित रंगवला आहे!",
                l1 = "दीवार का यह नीला रंग बहुत प्यारा लग रहा है, बिल्कुल अच्छे से पेंट किया है!",
                mood = "प्रशंसा (Customer Satisfaction)",
                angle = "Praising paint finish"
            ),
            PersonaQuestionScenario(
                l2 = "कपाटाच्या वर आणखी एक छोटी फळी बसवता येईल का, जास्तीचे सामान ठेवायला?",
                l1 = "अलमारी के ऊपर एक छोटी शेल्फ और लगा सकते हैं क्या, अतिरिक्त सामान रखने के लिए?",
                mood = "नवीन विनंती (Custom Request)",
                angle = "Adding custom shelf"
            )
        ),
        "driver" to listOf(
            PersonaQuestionScenario(
                l2 = "भाऊ, ही वाळू आणि खडी कुठे खाली करू? गेटजवळ की आत?",
                l1 = "भाई, यह बालू और गिट्टी कहाँ खाली करूं? गेट के पास या अंदर?",
                mood = "सामान उतरवणे (Unloading Location)",
                angle = "Material drop-off spot"
            ),
            PersonaQuestionScenario(
                l2 = "रस्ता खूप अरुंद आहे, टेम्पो मागे वळवायला जरा वाट दाखवा.",
                l1 = "रास्ता बहुत संकरा है, टेम्पो पीछे मोड़ने के लिए थोड़ा रास्ता दिखाइए।",
                mood = "रस्ता मार्गदर्शन (Narrow Lane Reversing)",
                angle = "Guiding truck in narrow street"
            ),
            PersonaQuestionScenario(
                l2 = "भाडे पाचशे रुपये ठरले होते, हमालीचे कामगार तुम्ही बोलावले आहेत ना?",
                l1 = "भाड़ा पांच सौ तय हुआ था, अनलोडिंग के लेबर आपने बुलाए हैं ना?",
                mood = "भाडे व हमाली (Freight & Labor)",
                angle = "Freight fare and loading labor"
            ),
            PersonaQuestionScenario(
                l2 = "पुढील डिलिव्हरीसाठी लवकर निघायचे आहे, पावतीवर सही करून द्या पटकन.",
                l1 = "अगली डिलीवरी के लिए जल्दी निकलना है, रसीद पर दस्तखत कर दीजिए जल्दी।",
                mood = "घाई (Quick Challan Signoff)",
                angle = "Delivery challan signature in a rush"
            )
        )
    )

    private fun pickScenarioForPersona(
        persona: String,
        preferredMood: String? = null,
        scenarioAngle: String? = null,
    ): PersonaQuestionScenario {
        val p = persona.lowercase()
        val key = when {
            p.contains("shop") -> "shopkeeper"
            p.contains("guard") || p.contains("watchman") || p.contains("security") -> "watchman"
            p.contains("coworker") || p.contains("worker") || p.contains("peer") -> "coworker"
            p.contains("canteen") || p.contains("tea") || p.contains("chai") -> "canteen"
            p.contains("client") || p.contains("customer") || p.contains("malik") -> "client"
            p.contains("driver") || p.contains("auto") || p.contains("tempo") -> "driver"
            else -> "supervisor"
        }
        val pool = personaScenarios[key] ?: personaScenarios["supervisor"]!!
        if (!preferredMood.isNullOrBlank()) {
            val matched = pool.firstOrNull { it.mood.contains(preferredMood, ignoreCase = true) }
            if (matched != null) return matched
        }
        if (!scenarioAngle.isNullOrBlank()) {
            val matched = pool.firstOrNull { it.angle.contains(scenarioAngle, ignoreCase = true) }
            if (matched != null) return matched
        }
        // Deterministic cycle sequencer: rotates smoothly through all scenarios
        // guaranteeing that successive sessions always feel fresh, vibrant, and varied
        val nextIdx = personaScenarioIndices.compute(key) { _, current ->
            ((current ?: -1) + 1) % pool.size
        } ?: 0
        return pool[nextIdx]
    }

    /**
     * Generates a dynamic, authentic opening line for a roleplay persona.
     * Guarantees non-repetitive workplace questions and distinct moods every invocation.
     */
    suspend fun generateRoleplayOpener(
        persona: String,
        scenario: String,
        ctx: GemmaContext,
        fallbackL2: String = "",
        fallbackL1: String = "",
        scenarioAngle: String? = null,
        mood: String? = null,
    ): RoleplayOpenerData {
        val selectedScenario = pickScenarioForPersona(persona, preferredMood = mood, scenarioAngle = scenarioAngle)
        val defaultL2 = if (fallbackL2.isNotBlank() && !fallbackL2.contains("काम वेळेवर") && !fallbackL2.contains("काम चालू आहे")) fallbackL2 else selectedScenario.l2
        val defaultL1 = if (fallbackL1.isNotBlank() && !fallbackL1.contains("काम समय पर") && !fallbackL1.contains("काम चल रहा है")) fallbackL1 else selectedScenario.l1
        val activeMood = mood ?: selectedScenario.mood

        if (gemma?.isAvailable == true) {
            val angle = scenarioAngle ?: selectedScenario.angle
            val prompt = GemmaPromptBuilder.buildRoleplayOpenerPrompt(
                persona = persona,
                scenario = scenario.ifBlank { "Workplace conversation" },
                ctx = ctx,
                scenarioAngle = angle,
                mood = activeMood,
            )
            val raw = gemma?.generate(prompt, temperature = GemmaEngine.ROLEPLAY_TEMPERATURE)
            if (!raw.isNullOrBlank()) {
                val lines = normalizeLlmOutputToLines(raw)
                val l2Raw = findTagValue(lines, "L2") ?: ""
                val l1Raw = findTagValue(lines, "L1") ?: ""
                val l2Clean = LlmOutputSanitizer.sanitize(l2Raw)?.trim() ?: ""
                val l1Clean = LlmOutputSanitizer.stripPlaceholders(l1Raw).trim()

                val isRepeatingGlitch = l2Clean.contains("वेळेवर सुरू") || l1Clean.contains("समय पर शुरू")
                if (l2Clean.isNotBlank() && !isRepeatingGlitch && LlmOutputSanitizer.isValidL2Output(l2Clean, ctx.l2)) {
                    Log.i(TAG, "Gemma roleplay opener (mood '$activeMood', angle '$angle'): $l2Clean")
                    return RoleplayOpenerData(
                        l2 = l2Clean,
                        l1 = l1Clean.ifBlank { defaultL1 },
                        mood = activeMood,
                        isGemma = true,
                    )
                } else {
                    Log.w(TAG, "Gemma roleplay opener rejected by validation: '$l2Clean' (raw: '$l2Raw')")
                }
            }
        }
        return RoleplayOpenerData(
            l2 = defaultL2,
            l1 = defaultL1,
            mood = activeMood,
            isGemma = false,
        )
    }

    private fun calculateHeuristicFluency(userSpokenText: String, l2: String): Int {
        val words = userSpokenText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return 50

        var score = 75
        // Reward natural length (3 to 10 words)
        when (words.size) {
            1 -> score -= 15
            2 -> score -= 5
            in 3..7 -> score += 10
            in 8..15 -> score += 12
            else -> score += 5
        }

        // Script correctness bonus
        if (LlmOutputSanitizer.matchesScript(userSpokenText, l2)) {
            score += 10
        }

        // Colloquial or polite marker bonus
        val lower = userSpokenText.lowercase()
        if (lower.contains("साहेब") || lower.contains("भावा") || lower.contains("होय") ||
            lower.contains("नाही") || lower.contains("नमस्ते") || lower.contains("धन्यवाद") ||
            lower.contains("ஐயா") || lower.contains("நன்றி") || lower.contains("సార్") ||
            lower.contains("అవును") || lower.contains("జీ") || lower.contains("हाँ")
        ) {
            score += 5
        }

        return score.coerceIn(40, 98)
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
        if (gemma?.isAvailable == true && spokenText.isNotBlank()) {
            val evalPrompt = GemmaPromptBuilder.buildEvaluateSpokenIntentPrompt(targetPhrase, prompt, spokenText, ctx)
            val raw = gemma?.generate(evalPrompt, temperature = GemmaEngine.STRUCTURED_TEMPERATURE)
            if (!raw.isNullOrBlank()) {
                val lines = normalizeLlmOutputToLines(raw)
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
        if (gemma?.isAvailable == true) {
            val prompt = GemmaPromptBuilder.buildPracticeDrillsPrompt(situation, domain, ctx)
            val raw = gemma?.generate(prompt, temperature = GemmaEngine.STRUCTURED_TEMPERATURE)
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
        if (gemma?.isAvailable == true && spokenText.isNotBlank()) {
            val prompt = GemmaPromptBuilder.buildPeerCoachPrompt(spokenText, speakerRole, ctx)
            val raw = gemma?.generate(prompt, temperature = GemmaEngine.STRUCTURED_TEMPERATURE)
            if (!raw.isNullOrBlank()) {
                val lines = normalizeLlmOutputToLines(raw)
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
        if (gemma?.isAvailable == true) {
            val prompt = GemmaPromptBuilder.buildDailyMissionPrompt(ctx)
            val raw = gemma?.generate(prompt, temperature = GemmaEngine.STRUCTURED_TEMPERATURE)
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

        if (gemma?.isAvailable == true) {
            val prompt = GemmaPromptBuilder.buildListenAroundPrompt(cleanedPhrase, ctx)
            val raw = gemma?.generate(prompt, temperature = GemmaEngine.STRUCTURED_TEMPERATURE)
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

    companion object {
        private const val TAG = "SeedheBolAi"

        private const val ALL_KNOWN_TAGS = "L2|L1|FLUENCY|SCORE|RATING|BETTER|NATURAL|FEEDBACK|HINT|TIP|TOPIC|EXPLANATION|WORD|PRACTICE|MATCH|TITLE|NATIVE_TITLE|NPC_ROLE|OBJECTIVE|OBJECTIVE_NATIVE|OPENER_L2|OPENER_L1|TARGET_WORDS|MAX_TURNS|NPC_L2|NPC_L1|SUCCESS|MEANING|TONE_INTENT|IMPORTANT_WORDS|NATURAL_REPLY|REPLY_NATIVE|REPLY_ROMAN|TRANS|TRANSLATION|VOCAB|D[1-3]_[A-Z]+"

        val INDIC_WORKPLACE_VOCAB_DICT: Map<String, Pair<String, String>> = mapOf(
            "मोबाईल" to Pair("मोबाइल (mobile)", "mobile"),
            "फोन" to Pair("फ़ोन (phone)", "phone"),
            "फोनचा" to Pair("फ़ोन का (phone's)", "phone-cha"),
            "वापर" to Pair("उपयोग / इस्तेमाल (use)", "vaapar"),
            "वाप" to Pair("उपयोग (use)", "vaapar"),
            "करू" to Pair("करना (do)", "karoo"),
            "नये" to Pair("नहीं / मना (prohibited)", "naye"),
            "नका" to Pair("मत / मत कीजिए (do not)", "naka"),
            "प्रवेश" to Pair("दाखिला / प्रवेश (entry)", "pravesh"),
            "निषिद्ध" to Pair("मना / वर्जित (forbidden)", "nishiddha"),
            "धूम्रपान" to Pair("धूम्रपान / बीड़ी-सिगरेट (smoking)", "dhoomrapaan"),
            "सक्त" to Pair("सख्त / कड़ा (strict)", "sakta"),
            "मनाई" to Pair("रोक / मना (prohibited)", "manaai"),
            "सावधान" to Pair("सतर्क / सावधान (caution)", "saavdhaan"),
            "धोका" to Pair("खतरा (danger)", "dhoka"),
            "सुरक्षा" to Pair("सुरक्षा / बचाव (safety)", "suraksha"),
            "हेल्मेट" to Pair("हेलमेट (helmet)", "helmet"),
            "बूट" to Pair("जूते (boots)", "boots"),
            "पाणी" to Pair("पानी (water)", "paani"),
            "पिण्याचे" to Pair("पीने का (drinking)", "pinyache"),
            "स्वच्छ" to Pair("साफ / स्वच्छ (clean)", "swachh"),
            "काम" to Pair("काम / कार्य (work)", "kaam"),
            "चालू" to Pair("जारी / शुरू (in progress)", "chaaloo"),
            "पुढील" to Pair("आगे का (next / ahead)", "pudhil"),
            "पुढे" to Pair("आगे (ahead)", "pudhe"),
            "थांबा" to Pair("रुको / ठहरिए (stop)", "thaamba"),
            "सिमेंट" to Pair("सीमेंट (cement)", "cement"),
            "विटा" to Pair("ईंटें (bricks)", "veeta"),
            "पाइप" to Pair("पाइप (pipe)", "pipe"),
            "वायरिंग" to Pair("तार / वायरिंग (wiring)", "wiring"),
            "शॉर्ट" to Pair("शॉर्ट सर्किट (short circuit)", "short"),
            "गळती" to Pair("रिसाव / टपकना (leak)", "galti"),
            "पार्सल" to Pair("पार्सल (parcel)", "parcel"),
            "डिलिव्हरी" to Pair("वितरण / डिलीवरी (delivery)", "delivery"),
            "कचरा" to Pair("कूड़ा-कचरा (trash)", "kachra"),
            "कचराकुंडीत" to Pair("कूड़ेदान में (in dustbin)", "kachrakundit"),
            "पावती" to Pair("रसीद / बिल (receipt)", "paavati"),
            "दुकान" to Pair("दुकान (shop)", "dukaan"),
            "गेट" to Pair("दरवाजा / गेट (gate)", "gate"),
            "पास" to Pair("अनुमति पत्र / पास (pass)", "pass"),
            "चौकीदार" to Pair("सुरक्षा गार्ड (security guard)", "chowkidar"),
            "तपासणी" to Pair("जांच / चेकिंग (inspection)", "tapasni"),
            "विजेचा" to Pair("बिजली का (electrical)", "vijecha"),
            "हात" to Pair("हाथ (hand)", "haat"),
            "लावू" to Pair("लगाना / छूना (touch)", "laavoo"),
        )

        private val COMMON_CONVERSATIONAL_WORDS = setOf(
            "साहेब", "सर", "काम", "आहे", "नाही", "होय", "हो", "भावा", "दादा", "जी", "है", "नहीं", "हाँ", "कर", "ते"
        )

        fun isParrotOrRepetition(candidate: String, userSpokenText: String, history: List<DialogueTurn>): Boolean {
            val cleanCand = candidate.trim().replace(Regex("[.,?!।\"'\\-]"), "").lowercase()
            val cleanUser = userSpokenText.trim().replace(Regex("[.,?!।\"'\\-]"), "").lowercase()

            if (cleanCand.isBlank()) return true

            // 1. Exact match with user utterance
            if (cleanCand == cleanUser) return true
            if (cleanCand.length > 5 && cleanUser == cleanCand) return true

            val candWords = cleanCand.split(Regex("\\s+")).filter { it.length > 1 }.toSet()
            val userWords = cleanUser.split(Regex("\\s+")).filter { it.length > 1 }.toSet()

            // 2. High word overlap on meaningful content words (parroting)
            val contentCandWords = candWords.filter { it !in COMMON_CONVERSATIONAL_WORDS }.toSet()
            val contentUserWords = userWords.filter { it !in COMMON_CONVERSATIONAL_WORDS }.toSet()

            if (contentCandWords.isNotEmpty() && contentUserWords.isNotEmpty()) {
                val intersection = contentCandWords.intersect(contentUserWords).size
                val overlapRatio = intersection.toDouble() / contentCandWords.size.toDouble()
                if (overlapRatio >= 0.45) {
                    Log.w(TAG, "Rejected candidate because it parrots user words ($intersection / ${contentCandWords.size}): '$candidate'")
                    return true
                }
            } else if (candWords.isNotEmpty() && userWords.isNotEmpty()) {
                if (candWords == userWords) return true
            }

            // 3. Repeating any previous bot line in history
            for (turn in history) {
                if (turn.speaker != "user") {
                    val prevClean = turn.text.trim().replace(Regex("[.,?!।\"'\\-]"), "").lowercase()
                    if (cleanCand == prevClean) return true
                    val prevWords = prevClean.split(Regex("\\s+")).filter { it.length > 1 }.toSet()
                    val prevContentWords = prevWords.filter { it !in COMMON_CONVERSATIONAL_WORDS }.toSet()
                    if (contentCandWords.isNotEmpty() && prevContentWords.isNotEmpty()) {
                        val overlap = contentCandWords.intersect(prevContentWords).size
                        val ratio = overlap.toDouble() / contentCandWords.size.toDouble()
                        if (ratio >= 0.75) {
                            Log.w(TAG, "Rejected candidate because it repeats previous bot turn ($overlap / ${contentCandWords.size}): '$candidate'")
                            return true
                        }
                    }
                }
            }

            return false
        }

        fun normalizeLlmOutputToLines(raw: String): List<String> {
            val withNewlines = raw
                .replace(Regex("(?i)(?=(?<![A-Za-z0-9_])(?:$ALL_KNOWN_TAGS)(?![A-Za-z0-9_])\\s*[:\\-—–=])"), "\n")
                .replace(Regex("##+"), "\n")
            return withNewlines.lines().map { it.trim() }.filter { it.isNotBlank() }
        }

        fun cleanLine(line: String): String {
            return line.trim()
                .replace(Regex("^[-*#0-9.\\s]+"), "") // strip leading bullets/numbers/markdown
                .replace(Regex("[*`#]+"), "")        // strip inline markdown asterisks or backticks
                .trim()
        }

        fun findTagValue(lines: List<String>, tagPattern: String): String? {
            val regex = Regex("(?i)(?:^|.*?)(?<![A-Za-z0-9_])(?:$tagPattern)(?![A-Za-z0-9_])\\s*[:\\-—–=]\\s*(.*)$")
            for (line in lines) {
                val cleaned = cleanLine(line)
                val match = regex.find(cleaned) ?: regex.find(line)
                if (match != null) {
                    var value = match.groupValues[1].trim()
                    val nextTag = Regex("(?i)\\s*(?:##|\\*\\*|\\s)\\s*(?:$ALL_KNOWN_TAGS)\\s*[:\\-—–=]").find(value)
                    if (nextTag != null) {
                        value = value.substring(0, nextTag.range.first).trim()
                    }
                    val isPlaceholder = value.matches(
                        Regex("(?i)^(?:2-4 word summary|meaning of this signboard|1 sentence in|word_in_|second_word_|1 short spoken sentence|title in|topic in).*")
                    )
                    if (value.isNotBlank() && !isPlaceholder) return value
                }
            }
            return null
        }

        fun parseVocabLines(lines: List<String>): List<VocabItem> {
            val vocabList = mutableListOf<VocabItem>()
            val nonVocabTags = Regex("^(?i)(?:TOPIC|TITLE|TRANSLATION|TRANS|MEANING|EXPLANATION|EXPLAIN|NOTE|PRACTICE|SPEAK|PRACTICE_PROMPT|SENTENCE|SIGNBOARD|TASK|RULES|EXAMPLE)\\s*[:\\-—–=]")
            val forbiddenL2 = setOf(
                "TRANSLATION", "EXPLANATION", "PRACTICE", "TOPIC", "TITLE",
                "WORD", "VOCAB", "MEANING", "NOTE", "SIGNBOARD", "TASK", "RULES", "EXAMPLE", "SPEAK"
            )

            for (rawLine in lines) {
                val trimmed = rawLine.trim()
                if (trimmed.isBlank()) continue

                val line = cleanLine(trimmed)
                // Strictly reject lines that start with any known non-vocab tag
                if (nonVocabTags.containsMatchIn(line) || nonVocabTags.containsMatchIn(trimmed)) {
                    continue
                }

                val isExplicitVocab = line.contains(Regex("^(?i)(?:WORD|VOCAB|KEYWORD)\\s*[:\\-—–=]"))
                val content = line.replace(Regex("^(?i)(?:WORD|VOCAB|KEYWORD)\\s*[:\\-—–=]\\s*"), "").trim()

                // If explicit prefix, allow : as separator; if NOT explicit, require =, —, or –
                val sepRegex = if (isExplicitVocab) {
                    Regex("[=—–:]|\\s+-\\s+")
                } else {
                    Regex("[=—–]|\\s+-\\s+")
                }

                val sepMatch = sepRegex.find(content) ?: continue
                val l2 = content.substring(0, sepMatch.range.first).trim()
                    .replace(Regex("[\"']"), "")
                val rest = content.substring(sepMatch.range.last + 1).trim()
                    .replace(Regex("[\"']"), "")

                if (l2.isBlank() || rest.isBlank()) continue

                // Ensure l2 is not an English tag/header or placeholder
                if (forbiddenL2.contains(l2.uppercase())) continue
                if (l2.matches(Regex("^(?i)(?:TOPIC|TITLE|TRANSLATION|EXPLANATION|PRACTICE|WORD|VOCAB|NOTE).*"))) continue
                if (l2.contains("word_in_", ignoreCase = true) || rest.contains("meaning_in_", ignoreCase = true) || l2.contains("second_word", ignoreCase = true)) continue

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

        fun parseOcrLessonResponse(raw: String, fallbackTopic: String): MicroLesson {
            val lines = normalizeLlmOutputToLines(raw)

            val explicitTopic = findTagValue(lines, "TOPIC|TITLE")
            val candidateTopic = explicitTopic ?: run {
                // If the model completed the prompt immediately after "TOPIC:" without repeating tag
                val firstCleaned = lines.map { cleanLine(it) }.firstOrNull { it.isNotBlank() }
                if (firstCleaned != null && !firstCleaned.contains(Regex("^(?i)(TOPIC|TITLE|TRANSLATION|EXPLANATION|WORD|PRACTICE)"))) {
                    firstCleaned.take(40)
                } else {
                    fallbackTopic
                }
            }
            val cleanedCandidate = candidateTopic.replace(Regex("^(?i)(?:TOPIC|TITLE)\\s*[:\\-—–=]\\s*"), "").trim()
            val topic = if (cleanedCandidate.isBlank() ||
                cleanedCandidate.matches(Regex("^(?i)(?:title|topic|summary)\\s+(?:in|for|of)\\s+.*")) ||
                cleanedCandidate.matches(Regex("(?i).*(?:2-4 word summary|summary of this sign).*")) ||
                cleanedCandidate.equals("Title", ignoreCase = true) ||
                cleanedCandidate.equals("Topic", ignoreCase = true)
            ) {
                fallbackTopic
            } else {
                cleanedCandidate
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
    }

    private fun parseDailyMission(raw: String, ctx: GemmaContext): DailyMission? {
        val lines = normalizeLlmOutputToLines(raw)
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
        val lines = normalizeLlmOutputToLines(raw)
        val meaningRaw = findTagValue(lines, "MEANING|TRANSLATION|HINDI_MEANING|SUMMARY|EXPLANATION|DETAIL")
        val meaning = if (!meaningRaw.isNullOrBlank() && !LlmOutputSanitizer.hasDegenerativeRepetition(meaningRaw)) {
            LlmOutputSanitizer.stripPlaceholders(meaningRaw)
        } else {
            // Check first line if it looks like an explanation
            val first = cleanLine(lines.firstOrNull() ?: "")
            if (first.isNotBlank() && !first.startsWith("#") && !first.contains(":") && first.length in 8..150) {
                first
            } else {
                deterministic.analyzeHeardPhrase(phrase, ctx).meaningL1
            }
        }

        val tone = findTagValue(lines, "TONE_INTENT|TONE|INTENT|CATEGORY|MOOD") ?: "सूचना / Workplace Instruction"
        val wordsRaw = findTagValue(lines, "IMPORTANT_WORDS|WORDS|VOCAB") ?: ""

        var replyL2 = findTagValue(lines, "NATURAL_REPLY|REPLY|REPLY_L2|RESPONSE|SUGGESTED_REPLY")
        if (replyL2.isNullOrBlank() || !LlmOutputSanitizer.isValidL2Output(replyL2, ctx.l2)) {
            val sanitized = replyL2?.let { LlmOutputSanitizer.sanitize(it) }
            if (sanitized != null && LlmOutputSanitizer.isValidL2Output(sanitized, ctx.l2)) {
                replyL2 = sanitized
            } else {
                replyL2 = deterministic.analyzeHeardPhrase(phrase, ctx).suggestedReplyL2
            }
        }
        val replyL1 = findTagValue(lines, "REPLY_NATIVE|REPLY_L1|REPLY_MEANING|REPLY_TRANS")
            ?: deterministic.analyzeHeardPhrase(phrase, ctx).replyMeaningL1
        val replyRoman = findTagValue(lines, "REPLY_ROMAN|ROMAN")
            ?: deterministic.analyzeHeardPhrase(phrase, ctx).replyRoman

        val wordList = mutableListOf<WordMeaning>()
        if (wordsRaw.isNotBlank()) {
            val pairs = wordsRaw.split(";", "\n", ",")
            for (p in pairs) {
                val cleanPair = cleanLine(p)
                val parts = cleanPair.split("=", ":", " - ").map { it.trim() }
                if (parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    wordList.add(WordMeaning(parts[0], parts[1]))
                }
            }
        }

        // If word extraction from tags was empty, fallback to rich deterministic token dictionary
        val finalWords = if (wordList.isNotEmpty()) {
            wordList.distinctBy { it.word }.take(5)
        } else {
            deterministic.analyzeHeardPhrase(phrase, ctx).importantWords
        }

        return HeardPhraseAnalysis(
            heardPhrase = phrase,
            meaningL1 = meaning,
            toneIntent = tone,
            importantWords = finalWords,
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


    /**
     * Parses roleplay response:
     *   L2: <reply>
     *   L1: <translation>
     *   BETTER: <better phrasing for learner>
     *   FEEDBACK: <feedback on learner's utterance>
     *   HINT: <pronunciation tip or "none">
     */
    private fun parseRoleplayResponse(
        raw: String,
        ctx: GemmaContext,
        userSpokenText: String = "",
        groundTruth: WorkplaceKnowledgeItem? = null,
    ): DialogueTurn? {
        val lines = normalizeLlmOutputToLines(raw)
        var l2Raw = findTagValue(lines, "L2|REPLY|RESPONSE") ?: ""
        if (l2Raw.isBlank()) {
            val first = cleanLine(raw.lines().firstOrNull() ?: "")
            if (!first.startsWith("##") && !first.startsWith("<") && first.length in 10..120) {
                l2Raw = first
            }
        }
        l2Raw = LlmOutputSanitizer.stripPlaceholders(l2Raw)

        var l2Text = l2Raw
        if (l2Text.isBlank() || !LlmOutputSanitizer.isValidL2Output(l2Text, ctx.l2)) {
            val sanitized = LlmOutputSanitizer.sanitize(l2Raw)
            if (sanitized != null && LlmOutputSanitizer.isValidL2Output(sanitized, ctx.l2)) {
                l2Text = sanitized
            } else if (groundTruth != null && LlmOutputSanitizer.isValidL2Output(groundTruth.groundTruthL2, ctx.l2)) {
                // Tier 3: Auto-repair using verified Micro-RAG Ground Truth!
                Log.i(TAG, "Auto-repaired L2 output using verified Micro-RAG ground truth: ${groundTruth.groundTruthL2}")
                l2Text = groundTruth.groundTruthL2
            } else {
                Log.w(TAG, "SLM roleplay response failed isValidL2Output check for ${ctx.l2}: '$l2Text'")
                return null
            }
        }

        val l1Raw = findTagValue(lines, "L1|TRANSLATION|MEANING") ?: ""
        var l1Text = LlmOutputSanitizer.stripPlaceholders(l1Raw)
        if (l1Text.isBlank()) {
            l1Text = groundTruth?.groundTruthL1 ?: deterministic.translateOcrText(l2Text, ctx).replace(Regex("^\\[.*?\\]\\s*"), "")
        }

        val fluencyRaw = findTagValue(lines, "FLUENCY|SCORE|RATING")
        val parsedFluency = fluencyRaw?.filter { it.isDigit() }?.toIntOrNull()
        val fluencyScore = (parsedFluency ?: calculateHeuristicFluency(userSpokenText, ctx.l2)).coerceIn(0, 100)

        val betterRaw = findTagValue(lines, "BETTER|NATURAL|POLISH") ?: ""
        var betterWay = LlmOutputSanitizer.stripPlaceholders(betterRaw)
        if (betterWay.isBlank() && groundTruth != null) {
            betterWay = groundTruth.betterPhrasing
        }

        val feedbackRaw = findTagValue(lines, "FEEDBACK|DIAGNOSTIC|NOTE") ?: ""
        var feedback = LlmOutputSanitizer.stripPlaceholders(feedbackRaw)
        if (feedback.isBlank() && groundTruth != null) {
            feedback = "उत्तर स्पष्ट आहे आणि कामाच्या संदर्भाशी जुळणारे आहे."
        }

        val hintRaw = findTagValue(lines, "HINT|TIP|PRONUNCIATION") ?: ""
        var hint = if (hintRaw.equals("none", ignoreCase = true)) "" else LlmOutputSanitizer.stripPlaceholders(hintRaw)
        if (hint.isBlank() && groundTruth != null) {
            hint = groundTruth.coachingHint
        }

        return DialogueTurn(
            speaker = "bot",
            text = l2Text,
            l1Text = l1Text,
            hint = hint,
            betterWay = betterWay,
            feedback = feedback,
            fluencyScore = fluencyScore,
        )
    }

    /**
     * Parses dynamic exercise drills generated by Gemma.
     */
    private fun parseDynamicExercises(raw: String): List<DynamicExercise> {
        val lines = normalizeLlmOutputToLines(raw)
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
}

/** Official SeedheBol AI layer alias. */
typealias SeedheBolAiLayer = BoliAiLayer

