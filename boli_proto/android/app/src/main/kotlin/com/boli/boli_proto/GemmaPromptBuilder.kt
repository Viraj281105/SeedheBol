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
    // Shared preamble
    // -------------------------------------------------------------------------

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
    fun buildTranslationPrompt(text: String, ctx: GemmaContext): String = buildString {
        appendLine(systemHeader(ctx))
        appendLine()
        appendLine("TASK: Translate this ${ctx.l2} text into ${ctx.l1}.")
        appendLine("Text: $text")
        appendLine()
        appendLine("Give only the translation. No explanation.")
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
    fun buildMicroLessonPrompt(topic: String, ctx: GemmaContext): String = buildString {
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
    fun buildVocabularyPrompt(ocrText: String, ctx: GemmaContext): String = buildString {
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

    // -------------------------------------------------------------------------
    // Explanation of a specific phrase
    // -------------------------------------------------------------------------

    /**
     * Explains why a phrase is used and how to use it, in simple L1.
     *
     * Expected output: plain prose, 2-4 sentences in L1.
     */
    fun buildExplanationPrompt(phrase: String, ctx: GemmaContext): String = buildString {
        appendLine(systemHeader(ctx))
        appendLine()
        appendLine("TASK: Explain what \"$phrase\" means in ${ctx.l2} and when to use it.")
        appendLine("Write 2-4 short sentences in ${ctx.l1}.")
        appendLine("Include one example situation from ${ctx.occupation} work.")
        appendLine("No grammar jargon.")
    }

    // -------------------------------------------------------------------------
    // Roleplay — next conversational turn
    // -------------------------------------------------------------------------

    /**
     * Generates the bot's next turn in a conversation.
     *
     * Expected output format:
     *   L2: <next bot sentence in target language>
     *   L1: <same sentence translated to L1 for comprehension>
     *   HINT: <optional pronunciation tip, or omit>
     */
    fun buildRoleplayNextTurnPrompt(
        history: List<DialogueTurn>,
        ctx: GemmaContext,
    ): String = buildString {
        appendLine(systemHeader(ctx))
        appendLine()
        appendLine("TASK: Continue this conversation. You play a ${ctx.scenario ?: "colleague"} speaking ${ctx.l2}.")
        appendLine()
        appendLine("Conversation so far:")
        for (turn in history.takeLast(6)) { // Last 6 turns to stay within token budget
            val speaker = if (turn.speaker == "user") "Learner" else "You"
            appendLine("$speaker: ${turn.text}")
        }
        appendLine()
        appendLine("Output exactly:")
        appendLine("L2: <your next sentence in ${ctx.l2}, simple, 1-2 sentences>")
        appendLine("L1: <same sentence in ${ctx.l1}>")
        appendLine("HINT: <one pronunciation tip, or write HINT: none>")
    }

    // -------------------------------------------------------------------------
    // Combined OCR → Lesson (the primary demo flow)
    // -------------------------------------------------------------------------

    /**
     * Single prompt that handles the Camera→OCR→Gemma→MicroLesson flow.
     * Combines translation + vocabulary + practice sentence in one inference call
     * to minimise latency (one LLM round-trip vs three).
     */
    fun buildOcrLessonPrompt(ocrText: String, ctx: GemmaContext): String = buildString {
        appendLine(systemHeader(ctx))
        appendLine()
        appendLine("TASK: The learner just photographed this ${ctx.l2} text:")
        appendLine("\"$ocrText\"")
        appendLine()
        appendLine("Create a quick lesson. Output exactly:")
        appendLine("TOPIC: <lesson title in ${ctx.l2}>")
        appendLine("TRANSLATION: <full text translated to ${ctx.l1}>")
        appendLine("EXPLANATION: <2 sentences in ${ctx.l1} putting this in workplace context>")
        appendLine("WORD: <${ctx.l2} word> = <${ctx.l1} meaning> (<roman pronunciation>)")
        appendLine("WORD: ... (up to 3 words)")
        appendLine("PRACTICE: <one sentence in ${ctx.l2} the learner should say next>")
        appendLine()
        appendLine("Keep it practical. This person is a ${ctx.occupation}.")
    }
}
