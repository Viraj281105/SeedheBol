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

    private fun systemHeader(ctx: GemmaContext): String = buildString {
        appendLine("You are SeedheBol AI, an on-device language tutor.")
        appendLine("Learner: ${ctx.occupation} | L1: ${ctx.l1} | Learning: ${ctx.l2} | Level: ${ctx.userLevel}")
        ctx.scenario?.let { appendLine("Scenario: $it") }
        appendLine("Rules: Short sentences. Simple vocabulary. Offline. No markdown.")
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
        val persona = ctx.scenario ?: "workplace supervisor"
        val userPrompt = buildString {
            appendLine(systemHeader(ctx))
            appendLine()
            appendLine("ROLE: You play a $persona speaking in ${ctx.l2}.")
            appendLine("TASK: Continue the conversation with the learner.")
            appendLine("Understand the learner's intent semantically even if their ${ctx.l2} grammar is rough or mixed with ${ctx.l1}.")
            appendLine("Keep sentences practical, polite, and spoken as in a real Indian workplace.")
            appendLine()
            appendLine("Conversation history:")
            for (turn in history.takeLast(6)) {
                val speaker = if (turn.speaker == "user") "Learner" else "You ($persona)"
                appendLine("$speaker: ${turn.text}")
            }
            appendLine()
            appendLine("Output strictly in this format:")
            appendLine("L2: <your natural response in ${ctx.l2}, 1-2 sentences>")
            appendLine("L1: <meaning of your response in ${ctx.l1}>")
            appendLine("BETTER: <a natural, polite way the learner could have said their last turn in ${ctx.l2}>")
            appendLine("FEEDBACK: <1 short sentence in ${ctx.l1} acknowledging what the learner communicated>")
            appendLine("HINT: <one pronunciation or articulation tip, or write HINT: none>")
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
        val userPrompt = buildString {
            appendLine(systemHeader(ctx))
            appendLine()
            appendLine("TASK: Generate 3 short practice drills for a ${ctx.occupation} worker in $domain.")
            appendLine("Situation: \"$situation\"")
            appendLine("Format strictly as follows (no markdown bolding):")
            appendLine("D1_PROMPT: <Instruction in ${ctx.l1}, e.g. Say this to your supervisor>")
            appendLine("D1_TARGET: <Sentence in ${ctx.l2}>")
            appendLine("D1_ROMAN: <Romanized pronunciation>")
            appendLine("D1_TRANS: <Meaning in ${ctx.l1}>")
            appendLine("D2_PROMPT: <Comprehension question in ${ctx.l1}>")
            appendLine("D2_CORRECT: <Correct answer in ${ctx.l2}>")
            appendLine("D2_OPT2: <Incorrect option in ${ctx.l2}>")
            appendLine("D2_OPT3: <Incorrect option in ${ctx.l2}>")
            appendLine("D3_PROMPT: <Instruction in ${ctx.l1}>")
            appendLine("D3_TARGET: <Essential workplace response in ${ctx.l2}>")
            appendLine("D3_ROMAN: <Romanized pronunciation>")
            appendLine("D3_TRANS: <Meaning in ${ctx.l1}>")
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
        val userPrompt = buildString {
            appendLine(systemHeader(ctx))
            appendLine()
            appendLine("TASK: Act as an offline language facilitator for 2 people practicing face-to-face.")
            appendLine("Speaker ($speakerRole) said: \"$spokenText\"")
            appendLine("Output strictly in this format:")
            appendLine("TRANS: <direct translation in the other speaker's language>")
            appendLine("BETTER: <more natural/colloquial phrasing in target language>")
            appendLine("TIP: <1 short conversation coaching tip in ${ctx.l1}>")
            appendLine("NEXT: <suggested reply or follow-up question to keep conversation going>")
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
}
