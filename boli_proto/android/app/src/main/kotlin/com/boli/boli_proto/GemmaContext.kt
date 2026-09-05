package com.boli.boli_proto

/**
 * Structured context fed to Gemma 3n E2B for every inference call.
 *
 * All fields are nullable — callers only supply what is known at the call site.
 * The GemmaPromptBuilder uses these to produce a grounded, user-specific prompt.
 *
 * NEVER include audio PCM or image bytes here — those stay in specialised
 * systems (OnnxAsr / MlKitOcr). Only their *outputs* (text strings) land here.
 */
data class GemmaContext(
    /** Mother tongue / L1 of the learner — e.g. "Hindi", "Bhojpuri", "Tamil" */
    val l1: String = "Hindi",

    /** Target language / L2 being learned — e.g. "Marathi", "Tamil", "Telugu" */
    val l2: String = "Marathi",

    /** Learner's occupation for domain-relevant vocabulary — e.g. "construction worker" */
    val occupation: String = "construction worker",

    /** Self-assessed or inferred level — "beginner" | "intermediate" | "advanced" */
    val userLevel: String = "beginner",

    /** Current scenario for roleplay / situational learning — e.g. "at the hardware store" */
    val scenario: String? = null,

    /** Raw OCR output from MlKitOcr — may contain noise; Gemma cleans and interprets. */
    val ocrText: String? = null,

    /** ASR transcript from OnnxAsr — what the user said, already decoded text. */
    val asrTranscript: String? = null,

    /** Current lesson topic for micro-lesson generation — e.g. "numbers 1-10" */
    val learningContext: String? = null,
)

// --------------------------------------------------------------------------
// Structured output types returned by BoliAiLayer (after Gemma parsing)
// --------------------------------------------------------------------------

/**
 * A single vocabulary item extracted from OCR text or lesson content.
 *
 * [l2Word]       — word in the target language
 * [l1Meaning]    — meaning in the learner's mother tongue
 * [romanization] — Roman-script pronunciation guide
 * [exampleSentence] — a short contextual sentence (optional)
 */
data class VocabItem(
    val l2Word: String,
    val l1Meaning: String,
    val romanization: String = "",
    val exampleSentence: String = "",
)

/**
 * A dynamic micro-lesson produced by Gemma from OCR/ASR context.
 *
 * [topic]        — lesson heading (1 phrase max)
 * [explanation]  — plain explanation in L1 (2-3 sentences max)
 * [vocabulary]   — key words to remember
 * [practicePrompt] — what to say or do next (drives TTS)
 * [source]       — "gemma" | "fallback" (for UI badge)
 */
data class MicroLesson(
    val topic: String,
    val explanation: String,
    val vocabulary: List<VocabItem> = emptyList(),
    val practicePrompt: String = "",
    val source: String = "gemma",
)

/**
 * A single turn in a roleplay conversation.
 *
 * [speaker]  — "user" | "bot"
 * [text]     — text in L2 (bot) or as spoken by user (user)
 * [l1Text]   — translation in L1 (for comprehension aid; bot only)
 * [hint]     — articulation / pronunciation hint (optional)
 */
data class DialogueTurn(
    val speaker: String,
    val text: String,
    val l1Text: String = "",
    val hint: String = "",
)
