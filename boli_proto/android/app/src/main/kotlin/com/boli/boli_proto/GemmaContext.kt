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

    /** Words successfully practiced or learned by the user */
    val learnedVocabulary: List<String> = emptyList(),

    /** Frequently missed words to naturally bring back into future practice and dialogue */
    val frequentlyMissedWords: List<String> = emptyList(),

    /** Sounds or words with low acoustic pronunciation scores */
    val pronunciationWeaknesses: List<String> = emptyList(),

    /** Recent practice or conversation context snippets */
    val recentContext: List<String> = emptyList(),

    /** Scenarios and situations completed by the learner */
    val completedScenarios: List<String> = emptyList(),
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
    val translation: String = "",
    val source: String = "gemma",
)

/**
 * A single turn in a roleplay conversation.
 *
 * [speaker]    — "user" | "bot"
 * [text]       — text in L2 (bot) or as spoken by user (user)
 * [l1Text]     — translation in L1 (for comprehension aid; bot only)
 * [hint]       — articulation / pronunciation hint (optional)
 * [betterWay]  — suggested natural/polite phrasing in L2 for the learner
 * [feedback]   — brief semantic/grammar diagnostic in L1
 */
data class DialogueTurn(
    val speaker: String,
    val text: String,
    val l1Text: String = "",
    val hint: String = "",
    val betterWay: String = "",
    val feedback: String = "",
)

/**
 * A dynamically generated workplace practice exercise produced by Gemma.
 */
data class DynamicExercise(
    val kind: String, // "speak", "choice"
    val prompt: String,
    val targetText: String,
    val roman: String = "",
    val translation: String = "",
    val options: List<String> = emptyList(),
    val answerIndex: Int = 0,
)

/**
 * Facilitation output for the "With Someone" two-person peer practice tool.
 */
data class PeerTurnCoachResult(
    val speakerRole: String,
    val spokenText: String,
    val translation: String,
    val betterWay: String = "",
    val coachTip: String = "",
    val nextPromptSuggestion: String = "",
    val source: String = "gemma",
)

