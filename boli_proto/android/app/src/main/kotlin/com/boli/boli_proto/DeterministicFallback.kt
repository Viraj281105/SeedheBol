package com.boli.boli_proto

import android.util.Log

/**
 * DeterministicFallback
 *
 * Rule-based, zero-latency responses used when Gemma 3n E2B is unavailable
 * (model not pushed, insufficient RAM, cold-start failure, etc.).
 *
 * All logic that was previously hardcoded inline in BoliBridgePlugin.kt has been
 * moved here so it can be tested in isolation. The bridge plugin now calls this
 * class rather than duplicating constants.
 *
 * IMPORTANT: Do NOT make this class smarter over time at the expense of Gemma.
 * Its only job is to keep the app functional when the LLM layer is absent.
 * It must never fail; every method returns a valid result instantly.
 */
class DeterministicFallback {

    // --------------------------------------------------------------------------
    // Translation
    // --------------------------------------------------------------------------

    /**
     * Returns a canned translation note when Gemma is unavailable.
     * In the full product this would consult a small compiled word-list.
     * For the hackathon prototype it surfaces the OCR text with a status label.
     */
    fun translateOcrText(ocrText: String, ctx: GemmaContext): String {
        return if (ocrText.isBlank()) {
            "No text detected in image."
        } else {
            // Surface the raw text so at least the user sees what was recognised
            "[Offline translation unavailable — showing raw OCR]\n$ocrText"
        }
    }

    // --------------------------------------------------------------------------
    // Micro-lessons
    // --------------------------------------------------------------------------

    /**
     * Returns a pre-built lesson from a small hardcoded set.
     * Keyed by the OCR text or topic, falls back to a generic construction-site lesson.
     */
    fun generateMicroLesson(topic: String, ctx: GemmaContext): MicroLesson {
        Log.d(TAG, "Fallback micro-lesson for topic='$topic'")

        // Small curated set for the demo domain (construction, Marathi L2)
        val knownLessons = mapOf(
            "cement" to MicroLesson(
                topic = "सिमेंट (Cement)",
                explanation = "सिमेंट म्हणजे इमारत बांधण्यासाठी वापरलेला पदार्थ.",
                vocabulary = listOf(
                    VocabItem("सिमेंट", "सीमेंट / cement", "simenta"),
                    VocabItem("बांधकाम", "निर्माण कार्य / construction", "bandh-kaam"),
                ),
                practicePrompt = "म्हणा: मला सिमेंट हवे आहे",
                source = "fallback",
            ),
            "पाणी" to MicroLesson(
                topic = "पाणी (Water)",
                explanation = "पाणी म्हणजे जीवन. कामाच्या ठिकाणी पाण्याची माहिती आवश्यक आहे.",
                vocabulary = listOf(
                    VocabItem("पाणी", "पानी / water", "paani"),
                    VocabItem("टाकी", "टंकी / tank", "taaki"),
                ),
                practicePrompt = "म्हणा: मला पाणी द्या",
                source = "fallback",
            ),
        )

        // Try to match any known keyword in the topic string
        val match = knownLessons.entries.firstOrNull { (key, _) ->
            topic.contains(key, ignoreCase = true)
        }?.value

        return match ?: MicroLesson(
            topic = "नवीन शब्द (New Word)",
            explanation = "हा शब्द ${ctx.l2}मध्ये शिका. तुमच्या कामाच्या ठिकाणी हे उपयुक्त आहे.",
            vocabulary = listOf(
                VocabItem(
                    l2Word = topic.take(20),
                    l1Meaning = "अर्थ उपलब्ध नाही",
                    romanization = "",
                ),
            ),
            practicePrompt = "हे शब्द लक्षात ठेवा.",
            source = "fallback",
        )
    }

    // --------------------------------------------------------------------------
    // Vocabulary extraction
    // --------------------------------------------------------------------------

    fun generateVocabulary(ocrText: String, ctx: GemmaContext): List<VocabItem> {
        // Very basic: split on whitespace, return first 5 tokens as vocab items
        val words = ocrText.trim().split(Regex("\\s+")).filter { it.length > 1 }.take(5)
        return words.map { word ->
            VocabItem(
                l2Word = word,
                l1Meaning = "(अर्थ उपलब्ध नाही)",
                romanization = "",
            )
        }.ifEmpty {
            listOf(VocabItem("—", "No text detected", ""))
        }
    }

    // --------------------------------------------------------------------------
    // Explanation
    // --------------------------------------------------------------------------

    fun getExplanation(phrase: String, ctx: GemmaContext): String {
        return "\"$phrase\" — ${ctx.l2} भाषेत हा शब्द आहे. " +
                "Gemma AI उपलब्ध नसल्यामुळे सविस्तर स्पष्टीकरण दाखवता येत नाही."
    }

    // --------------------------------------------------------------------------
    // Roleplay — extracted from BoliBridgePlugin.kt (previous hardcoded response)
    // --------------------------------------------------------------------------

    fun nextRoleplayTurn(
        history: List<DialogueTurn>,
        situationId: String,
        currentNodeId: String,
        ctx: GemmaContext,
    ): Map<String, Any?> {
        // Preserve the exact hardcoded response that was in BoliBridgePlugin
        // so existing demo behaviour is unchanged when Gemma is absent.
        val userText = history.lastOrNull { it.speaker == "user" }?.text ?: ""
        return mapOf(
            "recognized_transcript" to userText,
            "is_intent_matched" to true,
            "matched_intent" to "confirm_mix",
            "next_node_id" to "node_02",
            "prompt_l2" to "सरि, सिमेंट कलवई विगिधम् एन्ना?",
            "prompt_transliteration" to "Sari, siment kalavai vigidham enna?",
            "prompt_l1" to "ठीक है, सीमेंट मिश्रण का अनुपात क्या है?",
            "pre_rendered_audio_path" to "audio/ta_const_03_concrete_mix/mix_01_confirm.wav",
            "pronunciation_score" to -0.35,
            "weak_phonemes" to listOf("ट"),
            "articulatory_hint" to "जीभ को तालू के पिछले भाग से स्पर्श करें",
            "ai_source" to "fallback",
        )
    }

    // --------------------------------------------------------------------------
    // Pronunciation scoring — always deterministic, Gemma never touches this
    // --------------------------------------------------------------------------

    fun scorePronunciation(targetWord: String, canonicalG2P: String): Map<String, Any?> {
        return mapOf(
            "target_word" to targetWord,
            "target_transliteration" to canonicalG2P,
            "overall_score" to -0.42,
            "phonemes" to listOf(
                mapOf(
                    "phoneme" to "ट",
                    "ipa_symbol" to "ʈ",
                    "score" to -0.85,
                    "is_correct" to false,
                    "substituted_phoneme" to "त",
                    "articulation_guidance" to "Curl tongue back against the hard palate",
                )
            ),
            "l1_interference_diagnostic" to "L1 ${canonicalG2P.take(10)} interference detected on retroflex consonant",
        )
    }

    companion object {
        private const val TAG = "BoliDeterministic"
    }
}
