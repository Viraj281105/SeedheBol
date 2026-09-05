package com.boli.boli_proto

/**
 * GemmaPromptBuilder
 *
 * Produces structured Gemma 3n E2B prompts from a [GemmaContext].
 * All prompts use a consistent system-header format so the model can quickly
 * orient itself to the user's language pair, level, and domain.
 *
 * Design principles:
 *   1. Output language is always [GemmaContext.l2] (the target language).
 *      Only explanations and L1 translations are in [GemmaContext.l1].
 *   2. Prompts are terse — Gemma 3n E2B has a 2B parameter budget; short,
 *      directive prompts outperform long prose descriptions.
 *   3. Every prompt ends with a clear, parseable output instruction so
 *      BoliAiLayer can extract structured data without complex parsing.
 *   4. No chain-of-thought ("think step by step") — we want 512 tokens of
 *      useful output, not reasoning traces.
 */
object GemmaPromptBuilder {

    // -------------------------------------------------------------------------
    // Shared preamble & chat wrapper
    // -------------------------------------------------------------------------

    private fun wrapTurn(userText: String): String = buildString {
        append("<start_of_turn>user\n")
        append(userText.trim())
        append("\n<end_of_turn>\n<start_of_turn>model\n")
    }

    // -------------------------------------------------------------------------
    // Script & Language helpers for reliable SLM inference
    // -------------------------------------------------------------------------

    fun getLanguageScriptName(lang: String): Pair<String, String> {
        val l = lang.lowercase().trim()
        return when {
            l.startsWith("mr") || l.contains("marathi") -> "Marathi" to "Devanagari script (मराठी)"
            l.startsWith("hi") || l.contains("hindi") -> "Hindi" to "Devanagari script (हिन्दी)"
            l.startsWith("ta") || l.contains("tamil") -> "Tamil" to "Tamil script (தமிழ்)"
            l.startsWith("te") || l.contains("telugu") -> "Telugu" to "Telugu script (తెలుగు)"
            l.startsWith("kn") || l.contains("kannada") -> "Kannada" to "Kannada script (ಕನ್ನಡ)"
            l.startsWith("ml") || l.contains("malayalam") -> "Malayalam" to "Malayalam script (മലയാളം)"
            l.startsWith("bn") || l.contains("bengali") || l.startsWith("as") -> "Bengali" to "Bengali script (বাংলা)"
            l.startsWith("gu") || l.contains("gujarati") -> "Gujarati" to "Gujarati script (ગુજરાતી)"
            l.startsWith("or") || l.contains("odia") -> "Odia" to "Odia script (ଓଡ଼ିଆ)"
            l.startsWith("pa") || l.contains("punjabi") -> "Punjabi" to "Gurmukhi script (ਪੰਜਾਬੀ)"
            else -> lang to "native script"
        }
    }

    private fun systemHeader(ctx: GemmaContext): String = buildString {
        val (l2Name, l2Script) = getLanguageScriptName(ctx.l2)
        val (l1Name, l1Script) = getLanguageScriptName(ctx.l1)
        appendLine("You are SeedheBol AI, an on-device workplace language assistant.")
        appendLine("Learner: ${ctx.occupation} | Target: $l2Name in $l2Script | Learner language: $l1Name in $l1Script | Level: ${ctx.userLevel}.")
        ctx.scenario?.let { appendLine("Workplace context: $it") }
        if (ctx.frequentlyMissedWords.isNotEmpty()) {
            appendLine("Struggling words: ${ctx.frequentlyMissedWords.take(3).joinToString(", ")}.")
        }
        appendLine("Strict rules: Short sentences. Authentic workplace vocabulary. No repetitive loops. No placeholder brackets. No markdown bolding.")
    }

    // -------------------------------------------------------------------------
    // Translation (OCR → contextual translation)
    // -------------------------------------------------------------------------

    /**
     * Translates [text] (typically raw OCR output) into [ctx.l1] with context.
     *
     * Expected output: plain text translation in L1, 1-3 sentences max.
     */
    fun buildTranslationPrompt(text: String, ctx: GemmaContext): String {
        val userPrompt = buildString {
            appendLine(systemHeader(ctx))
            appendLine()
            appendLine("TASK: Translate this signboard text into ${ctx.l1}.")
            appendLine("Give ONLY the direct translation. No explanation, no quotes, no markdown.")
            appendLine()
            appendLine("Example:")
            appendLine("Text: प्रवेश निषिद्ध")
            appendLine("Translation: अंदर जाना मना है")
            appendLine()
            appendLine("Text: $text")
            appendLine("Translation:")
        }
        return wrapTurn(userPrompt)
    }

    // -------------------------------------------------------------------------
    // Micro-lesson (OCR / topic → short lesson)
    // -------------------------------------------------------------------------

    /**
     * Generates a micro-lesson from either a topic keyword or OCR text.
     *
     * Expected output format (parse in BoliAiLayer):
     *   TOPIC: <lesson title in L2>
     *   EXPLANATION: <2-3 sentences in L1>
     *   WORD: <L2 word> = <L1 meaning> (<romanization>)
     *   WORD: ...
     *   PRACTICE: <one sentence in L2 to say aloud>
     */
    fun buildMicroLessonPrompt(topic: String, ctx: GemmaContext): String {
        val userPrompt = buildString {
            appendLine(systemHeader(ctx))
            appendLine()
            appendLine("TASK: Create a 1-minute micro-lesson about \"$topic\" for a ${ctx.l2} learner.")
            appendLine("Output exactly this format:")
            appendLine("TOPIC: <lesson title in ${ctx.l2}>")
            appendLine("EXPLANATION: <2-3 sentences in ${ctx.l1} explaining the topic>")
            appendLine("WORD: <${ctx.l2} word> = <${ctx.l1} meaning> (<roman pronunciation>)")
            appendLine("WORD: ... (up to 4 words total)")
            appendLine("PRACTICE: <one simple sentence in ${ctx.l2} the learner should say>")
            appendLine()
            appendLine("Be concrete. Use workplace vocabulary relevant to ${ctx.occupation}.")
        }
        return wrapTurn(userPrompt)
    }

    // -------------------------------------------------------------------------
    // Vocabulary extraction from OCR text
    // -------------------------------------------------------------------------

    /**
     * Extracts meaningful words from [ocrText] and provides L1 meanings.
     *
     * Expected output format:
     *   WORD: <L2 word> = <L1 meaning> (<romanization>)
     *   WORD: ...
     */
    fun buildVocabularyPrompt(ocrText: String, ctx: GemmaContext): String {
        val userPrompt = buildString {
            appendLine(systemHeader(ctx))
            appendLine()
            appendLine("TASK: From this ${ctx.l2} text, extract up to 5 useful words for a ${ctx.occupation}.")
            appendLine("Text: $ocrText")
            appendLine()
            appendLine("Output exactly this format for each word:")
            appendLine("WORD: <${ctx.l2} word> = <${ctx.l1} meaning> (<roman pronunciation>)")
            appendLine()
            appendLine("Skip common words (the, is, a). Focus on nouns and action words.")
        }
        return wrapTurn(userPrompt)
    }

    // -------------------------------------------------------------------------
    // Explanation of a specific phrase
    // -------------------------------------------------------------------------

    /**
     * Explains why a phrase is used and how to use it, in simple L1.
     *
     * Expected output: plain prose, 2-4 sentences in L1.
     */
    fun buildExplanationPrompt(phrase: String, ctx: GemmaContext): String {
        val userPrompt = buildString {
            appendLine(systemHeader(ctx))
            appendLine()
            appendLine("TASK: Explain what \"$phrase\" means in ${ctx.l2} and when to use it.")
            appendLine("Write 2-4 short sentences in ${ctx.l1}.")
            appendLine("Include one example situation from ${ctx.occupation} work.")
            appendLine("No grammar jargon.")
        }
        return wrapTurn(userPrompt)
    }

    // -------------------------------------------------------------------------
    // Roleplay — next conversational turn
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Roleplay — semantic understanding, natural response, and coaching feedback
    // -------------------------------------------------------------------------

    /**
     * Generates Gemma's next conversational turn in a scenario.
     *
     * Instructs the model to:
     *   1. Understand user intent semantically (tolerate broken grammar or slang).
     *   2. Respond naturally in persona (supervisor, shopkeeper, guard, coworker).
     *   3. Suggest a more natural/polite phrasing for the learner ("Better Way").
     *   4. Provide native L1 comprehension and articulation tips.
     */
    fun buildRoleplayNextTurnPrompt(
        history: List<DialogueTurn>,
        ctx: GemmaContext,
    ): String {
        val (l2Name, l2Script) = getLanguageScriptName(ctx.l2)
        val (l1Name, l1Script) = getLanguageScriptName(ctx.l1)
        val persona = ctx.scenario ?: "workplace supervisor"
        val userPrompt = buildString {
            appendLine(systemHeader(ctx))
            appendLine()
            appendLine("ROLE: You play a $persona speaking in $l2Name ($l2Script).")
            appendLine("TASK: Continue the conversation with the learner.")
            appendLine("Understand the learner's intent semantically even if their $l2Name grammar is rough.")
            appendLine("Keep sentences practical, polite, and spoken as in a real Indian workplace.")
            if (ctx.frequentlyMissedWords.isNotEmpty()) {
                appendLine("Reinforce: ${ctx.frequentlyMissedWords.take(2).joinToString(", ")}.")
            }
            appendLine()
            appendLine("Conversation history:")
            for (turn in history.takeLast(4)) {
                val speaker = if (turn.speaker == "user") "Learner" else "You ($persona)"
                appendLine("$speaker: ${turn.text}")
            }
            appendLine()
            appendLine("Format strictly as follows (no angle brackets, no markdown):")
            appendLine("L2: Your natural response in $l2Script (1-2 sentences)")
            appendLine("L1: Meaning of your response in $l1Script")
            appendLine("BETTER: A natural, polite way the learner could have phrased their last turn in $l2Script")
            appendLine("FEEDBACK: 1 short sentence in $l1Script acknowledging what the learner communicated")
            appendLine("HINT: One pronunciation or articulation tip, or write none")
            appendLine()
            appendLine("CRITICAL: Do NOT loop or repeat phrases. Output crisp native text.")
        }
        return wrapTurn(userPrompt)
    }

    // -------------------------------------------------------------------------
    // Dynamic Workplace Practice Drills
    // -------------------------------------------------------------------------

    /**
     * Generates dynamic workplace exercises for any situation on the fly.
     */
    fun buildPracticeDrillsPrompt(
        situation: String,
        domain: String,
        ctx: GemmaContext,
    ): String {
        val (l2Name, l2Script) = getLanguageScriptName(ctx.l2)
        val (l1Name, l1Script) = getLanguageScriptName(ctx.l1)
        val userPrompt = buildString {
            appendLine(systemHeader(ctx))
            appendLine()
            appendLine("TASK: Generate 3 short practice drills for a ${ctx.occupation} in $domain.")
            appendLine("Situation: \"$situation\"")
            if (ctx.frequentlyMissedWords.isNotEmpty()) {
                appendLine("Target words: ${ctx.frequentlyMissedWords.take(3).joinToString(", ")}.")
            }
            appendLine("Format strictly as follows (no angle brackets, no markdown):")
            appendLine("D1_PROMPT: Instruction in $l1Script (e.g. Say this to your supervisor)")
            appendLine("D1_TARGET: Sentence in $l2Script")
            appendLine("D1_ROMAN: Romanized pronunciation")
            appendLine("D1_TRANS: Meaning in $l1Script")
            appendLine("D2_PROMPT: Comprehension question in $l1Script")
            appendLine("D2_CORRECT: Correct answer in $l2Script")
            appendLine("D2_OPT2: Incorrect option in $l2Script")
            appendLine("D2_OPT3: Incorrect option in $l2Script")
            appendLine("D3_PROMPT: Instruction in $l1Script")
            appendLine("D3_TARGET: Essential workplace response in $l2Script")
            appendLine("D3_ROMAN: Romanized pronunciation")
            appendLine("D3_TRANS: Meaning in $l1Script")
        }
        return wrapTurn(userPrompt)
    }

    // -------------------------------------------------------------------------
    // "With Someone" Peer Facilitator / Coach
    // -------------------------------------------------------------------------

    /**
     * Acts as an AI Language Facilitator when two people practice together.
     */
    fun buildPeerCoachPrompt(
        spokenText: String,
        speakerRole: String,
        ctx: GemmaContext,
    ): String {
        val (l2Name, l2Script) = getLanguageScriptName(ctx.l2)
        val (l1Name, l1Script) = getLanguageScriptName(ctx.l1)
        val userPrompt = buildString {
            appendLine(systemHeader(ctx))
            appendLine()
            appendLine("TASK: Act as an offline language facilitator for 2 people practicing face-to-face.")
            appendLine("Speaker ($speakerRole) said: \"$spokenText\"")
            appendLine("Output strictly in this format (no angle brackets, no markdown):")
            appendLine("TRANS: Direct translation in the other speaker's language ($l1Script)")
            appendLine("BETTER: More natural/colloquial phrasing in $l2Script")
            appendLine("TIP: 1 short conversation coaching tip in $l1Script")
            appendLine("NEXT: Suggested reply or follow-up question in $l2Script to keep conversation going")
        }
        return wrapTurn(userPrompt)
    }

    // -------------------------------------------------------------------------
    // Combined OCR → Lesson (the primary demo flow)
    // -------------------------------------------------------------------------

    /**
     * Single prompt that handles the Camera→OCR→Gemma→MicroLesson flow.
     * Combines translation + vocabulary + practice sentence in one inference call
     * to minimise latency (one LLM round-trip vs three).
     */
    fun buildOcrLessonPrompt(ocrText: String, ctx: GemmaContext): String {
        val userPrompt = buildString {
            appendLine(systemHeader(ctx))
            appendLine()
            appendLine("TASK: Create a short workplace micro-lesson from photographed signboard text.")
            appendLine("Rules: Output strictly the lines below. No markdown bolding (**), no bullet dashes (-), no intro text.")
            appendLine()
            appendLine("--- Example ---")
            appendLine("Signboard: \"धोका! पुढे काम चालू आहे\"")
            appendLine("TOPIC: काम चालू आहे")
            appendLine("TRANSLATION: खतरा! आगे काम चल रहा है")
            appendLine("EXPLANATION: यह निर्माण स्थल पर सुरक्षा चेतावनी बोर्ड है। हमेशा ध्यान से काम करें।")
            appendLine("WORD: धोका = खतरा (dhoka)")
            appendLine("WORD: पुढे = आगे (pudhe)")
            appendLine("PRACTICE: येथे काळजीपूर्वक काम करा.")
            appendLine()
            appendLine("--- New Task ---")
            appendLine("Signboard: \"$ocrText\"")
            appendLine("TOPIC:")
        }
        return wrapTurn(userPrompt)
    }

    // -------------------------------------------------------------------------
    // Daily Mission Synthesis & Turn Evaluation
    // -------------------------------------------------------------------------

    /**
     * Synthesizes a daily workplace mission tailored to the user's occupation,
     * weak words, and language pair.
     */
    fun buildDailyMissionPrompt(ctx: GemmaContext): String {
        val (l2Name, l2Script) = getLanguageScriptName(ctx.l2)
        val (l1Name, l1Script) = getLanguageScriptName(ctx.l1)
        val userPrompt = buildString {
            appendLine(systemHeader(ctx))
            appendLine()
            appendLine("TASK: Create a 2-minute daily workplace language challenge for a ${ctx.occupation} learning $l2Name.")
            appendLine("The scenario MUST be practical for an Indian workplace (delivery, site work, security, customer query).")
            appendLine("All $l2Name text MUST be written in authentic $l2Script.")
            appendLine("All $l1Name text MUST be written in authentic $l1Script.")
            if (ctx.frequentlyMissedWords.isNotEmpty()) {
                appendLine("Target words: ${ctx.frequentlyMissedWords.take(3).joinToString(", ")}.")
            }
            appendLine()
            appendLine("Format strictly as follows line by line (do NOT include angle brackets <>, no markdown):")
            appendLine("TITLE: Short English Title")
            appendLine("NATIVE_TITLE: Short title in $l2Script")
            appendLine("NPC_ROLE: Supervisor or Customer role in $l2Script")
            appendLine("OBJECTIVE: Goal in English (1 sentence)")
            appendLine("OBJECTIVE_NATIVE: Goal in $l1Script (1 sentence)")
            appendLine("OPENER_L2: Realistic first sentence spoken by the NPC in $l2Script (one sentence, under 15 words)")
            appendLine("OPENER_L1: Hindi/native translation of the opener in $l1Script")
            appendLine("TARGET_WORDS: 2 or 3 essential vocabulary words in $l2Script separated by commas")
            appendLine("MAX_TURNS: 4")
            appendLine()
            appendLine("CRITICAL: Under NO circumstances repeat words or phrases in loops. Keep sentences crisp, authentic, and polite.")
            appendLine("Begin directly with TITLE:")
        }
        return wrapTurn(userPrompt)
    }

    /**
     * Advances the daily mission dialogue turn by turn (3-5 turns total).
     */
    fun buildMissionTurnPrompt(
        mission: DailyMission,
        history: List<DialogueTurn>,
        turnIndex: Int,
        totalTurns: Int,
        userSpokenText: String,
        ctx: GemmaContext,
    ): String {
        val (l2Name, l2Script) = getLanguageScriptName(ctx.l2)
        val (l1Name, l1Script) = getLanguageScriptName(ctx.l1)
        val isFinalTurn = turnIndex >= totalTurns - 1
        val userPrompt = buildString {
            appendLine(systemHeader(ctx))
            appendLine()
            appendLine("MISSION: ${mission.title} (${mission.objective})")
            appendLine("ROLE: You play ${mission.npcRole} speaking in $l2Name ($l2Script).")
            appendLine("Turn $turnIndex of $totalTurns. Final turn: $isFinalTurn.")
            appendLine("The learner just said: \"$userSpokenText\"")
            appendLine("Evaluate their intent semantically. Keep responses practical and realistic for Indian workplace.")
            if (isFinalTurn) {
                appendLine("Since this is the final turn, bring the scenario to a satisfying conclusion (e.g. approve the request, confirm resolution).")
            }
            appendLine()
            appendLine("Conversation history:")
            for (turn in history.takeLast(4)) {
                val speaker = if (turn.speaker == "user") "Learner" else mission.npcRole
                appendLine("$speaker: ${turn.text}")
            }
            appendLine("Learner: $userSpokenText")
            appendLine()
            appendLine("Format strictly as follows (no angle brackets, no markdown):")
            appendLine("NPC_L2: Your response in $l2Script (1-2 sentences)")
            appendLine("NPC_L1: Meaning in $l1Script")
            appendLine("BETTER: More natural/polite way the learner could have phrased their response in $l2Script")
            appendLine("FEEDBACK: 1 short sentence in $l1Script giving coaching feedback")
            appendLine("SUCCESS: yes or partial or no")
            appendLine()
            appendLine("CRITICAL: Do NOT loop or repeat phrases.")
        }
        return wrapTurn(userPrompt)
    }

    // -------------------------------------------------------------------------
    // Listen Around Me (Overheard / Captured Workplace Speech)
    // -------------------------------------------------------------------------

    /**
     * Analyzes an overheard or repeated phrase in [ctx.l2], interpreting meaning,
     * tone, key vocabulary, and an actionable natural reply.
     */
    fun buildListenAroundPrompt(heardPhrase: String, ctx: GemmaContext): String {
        val (l2Name, l2Script) = getLanguageScriptName(ctx.l2)
        val (l1Name, l1Script) = getLanguageScriptName(ctx.l1)
        val userPrompt = buildString {
            appendLine(systemHeader(ctx))
            appendLine()
            appendLine("TASK: The worker (${ctx.occupation}) overheard or repeated this workplace phrase in $l2Name ($l2Script):")
            appendLine("\"$heardPhrase\"")
            appendLine()
            appendLine("This might be colloquial, workplace slang, or slightly noisy speech.")
            appendLine("Analyze it to help the worker instantly understand and respond.")
            appendLine()
            appendLine("Format strictly as follows (no angle brackets, no markdown bolding):")
            appendLine("MEANING: Clear meaning in $l1Script (1-2 short sentences)")
            appendLine("TONE_INTENT: Tone & intent (e.g. Instruction, Warning, Request, or Inquiry)")
            appendLine("IMPORTANT_WORDS: word1 = meaning in $l1Script; word2 = meaning in $l1Script")
            appendLine("NATURAL_REPLY: Short spoken reply the worker can say in $l2Script (under 12 words)")
            appendLine("REPLY_NATIVE: Meaning of the reply in $l1Script")
            appendLine("REPLY_ROMAN: Pronunciation of reply in English letters")
            appendLine()
            appendLine("CRITICAL: Do NOT loop or repeat phrases.")
        }
        return wrapTurn(userPrompt)
    }
}

