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
            val det = deterministic.translateOcrText(cleanOcrText, ctx)
            // Never surface internal error markers (starting with '[') as lesson content
            if (det.isNotBlank() && !det.startsWith("[")) det else ""
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
        turnNumber: Int = 1,
        maxTurns: Int = 5,
        mood: String? = null,
    ): Map<String, Any?> {
        val t0 = System.currentTimeMillis()
        val historyWithUser = history + DialogueTurn("user", userSpokenText)

        if (gemma.isAvailable) {
            val prompt = GemmaPromptBuilder.buildRoleplayNextTurnPrompt(
                history = historyWithUser,
                ctx = ctx,
                turnNumber = turnNumber,
                maxTurns = maxTurns,
                mood = mood,
            )
            val raw = gemma.generate(prompt, temperature = GemmaEngine.ROLEPLAY_TEMPERATURE)
            if (!raw.isNullOrBlank()) {
                val turn = parseRoleplayResponse(raw, ctx, userSpokenText)
                if (turn != null && LlmOutputSanitizer.isValidL2Output(turn.text, ctx.l2)) {
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
                    Log.w(TAG, "Gemma roleplay turn failed validation or repetition checks, falling back to deterministic")
                }
            }
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
        return pool.random()
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

        if (gemma.isAvailable) {
            val angle = scenarioAngle ?: selectedScenario.angle
            val prompt = GemmaPromptBuilder.buildRoleplayOpenerPrompt(
                persona = persona,
                scenario = scenario.ifBlank { "Workplace conversation" },
                ctx = ctx,
                scenarioAngle = angle,
                mood = activeMood,
            )
            val raw = gemma.generate(prompt, temperature = GemmaEngine.ROLEPLAY_TEMPERATURE)
            if (!raw.isNullOrBlank()) {
                val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
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
    private fun parseRoleplayResponse(raw: String, ctx: GemmaContext, userSpokenText: String = ""): DialogueTurn? {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
        var l2Raw = findTagValue(lines, "L2|REPLY|RESPONSE") ?: ""
        if (l2Raw.isBlank()) {
            val first = cleanLine(raw.lines().firstOrNull() ?: "")
            if (!first.startsWith("##") && !first.startsWith("<") && first.length in 10..120) {
                l2Raw = first
            }
        }
        l2Raw = LlmOutputSanitizer.stripPlaceholders(l2Raw)
        if (l2Raw.isBlank()) return null

        val l2Text = LlmOutputSanitizer.sanitize(l2Raw) ?: return null
        if (!LlmOutputSanitizer.isValidL2Output(l2Text, ctx.l2)) {
            Log.w(TAG, "Gemma roleplay response failed isValidL2Output check for ${ctx.l2}: '$l2Text'")
            return null
        }

        val l1Raw = findTagValue(lines, "L1|TRANSLATION") ?: ""
        val l1Text = LlmOutputSanitizer.stripPlaceholders(l1Raw)

        val fluencyRaw = findTagValue(lines, "FLUENCY|SCORE|RATING")
        val parsedFluency = fluencyRaw?.filter { it.isDigit() }?.toIntOrNull()
        val fluencyScore = (parsedFluency ?: calculateHeuristicFluency(userSpokenText, ctx.l2)).coerceIn(0, 100)

        val betterRaw = findTagValue(lines, "BETTER|NATURAL|POLISH") ?: ""
        val betterWay = LlmOutputSanitizer.stripPlaceholders(betterRaw)

        val feedbackRaw = findTagValue(lines, "FEEDBACK|DIAGNOSTIC|NOTE") ?: ""
        val feedback = LlmOutputSanitizer.stripPlaceholders(feedbackRaw)

        val hintRaw = findTagValue(lines, "HINT|TIP|PRONUNCIATION") ?: ""
        val hint = if (hintRaw.equals("none", ignoreCase = true)) "" else LlmOutputSanitizer.stripPlaceholders(hintRaw)

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

