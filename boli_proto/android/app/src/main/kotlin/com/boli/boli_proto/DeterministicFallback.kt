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

        // Curated set for workplace signboards (Tamil, Marathi, Hindi)
        val knownLessons = mapOf(
            "காலை" to MicroLesson(
                topic = "காலை வணக்கம் (Morning Sign)",
                translation = "शुभ प्रभात / सुबह का काम",
                explanation = "यह सुबह के कार्यस्थल का संदेश है। काम की शुरुआत के लिए आवश्यक शब्द।",
                vocabulary = listOf(
                    VocabItem("காலை", "सुबह / morning", "kaalai"),
                    VocabItem("வேலை", "काम / work", "velai"),
                    VocabItem("வாருங்கள்", "आइए / welcome", "vaarungal"),
                    VocabItem("வணக்கம்", "नमस्ते / greetings", "vanakkam"),
                ),
                practicePrompt = "காலை வணக்கம், வேலை ஆரம்பம்",
                source = "fallback",
            ),
            "வேலை" to MicroLesson(
                topic = "வேலை நேரம் (Work Hours)",
                translation = "काम का समय / कार्यस्थल",
                explanation = "कार्यस्थल पर काम के समय और निर्देशों के बारे में बताया गया है।",
                vocabulary = listOf(
                    VocabItem("வேலை", "काम / work", "velai"),
                    VocabItem("நேரம்", "समय / time", "neram"),
                    VocabItem("நன்றி", "धन्यवाद / thanks", "nandri"),
                ),
                practicePrompt = "வேலை நேரம் ஆரம்பமானது",
                source = "fallback",
            ),
            "सावधान" to MicroLesson(
                topic = "सावधान (Caution / Safety Sign)",
                translation = "सावधान रहें / ध्यान से काम करें",
                explanation = "कार्यस्थल पर सुरक्षा चेतावनी बोर्ड। यहां हेलमेट और जूते पहनना आवश्यक है।",
                vocabulary = listOf(
                    VocabItem("सावधान", "सतर्क / caution", "saavdhaan"),
                    VocabItem("सुरक्षा", "बचाव / safety", "suraksha"),
                    VocabItem("काळजी", "ध्यान रखना / take care", "kaalji"),
                    VocabItem("काम", "कार्य / work", "kaam"),
                ),
                practicePrompt = "येथे काळजीपूर्वक काम करा",
                source = "fallback",
            ),
            "प्रवेश" to MicroLesson(
                topic = "प्रवेश निषिद्ध (No Entry)",
                translation = "अंदर जाना मना है / प्रवेश वर्जित",
                explanation = "यह चेतावनी बोर्ड है। बिना अनुमति इस क्षेत्र में प्रवेश न करें।",
                vocabulary = listOf(
                    VocabItem("प्रवेश", "दाखिला / entry", "pravesh"),
                    VocabItem("निषिद्ध", "वर्जित / forbidden", "nishiddha"),
                    VocabItem("थांबा", "रुको / stop", "thaamba"),
                    VocabItem("परवानगी", "इजाजत / permission", "parvaangi"),
                ),
                practicePrompt = "येथे प्रवेश निषिद्ध आहे",
                source = "fallback",
            ),
            "सिमेंट" to MicroLesson(
                topic = "सिमेंट (Cement Storage)",
                translation = "सीमेंट और निर्माण सामग्री",
                explanation = "सिमेंट इमारत बांधण्यासाठी मुख्य घटक आहे. पोती कोरडी ठेवा.",
                vocabulary = listOf(
                    VocabItem("सिमेंट", "सीमेंट / cement", "simenta"),
                    VocabItem("बांधकाम", "निर्माण कार्य / construction", "bandh-kaam"),
                    VocabItem("पोती", "बोरी / sack", "poti"),
                ),
                practicePrompt = "मला सिमेंटची पोती हवी आहेत",
                source = "fallback",
            ),
            "पाणी" to MicroLesson(
                topic = "पिण्याचे पाणी (Drinking Water)",
                translation = "पीने का पानी",
                explanation = "कामाच्या ठिकाणी पिण्याच्या पाण्याची सोय दर्शवणारा फलक आहे.",
                vocabulary = listOf(
                    VocabItem("पाणी", "पानी / water", "paani"),
                    VocabItem("पिण्याचे", "पीने का / drinking", "pinyache"),
                    VocabItem("टाकी", "टंकी / tank", "taaki"),
                ),
                practicePrompt = "पिण्याचे पाणी कुठे आहे?",
                source = "fallback",
            ),
        )

        // Try to match any known keyword in the topic string
        val match = knownLessons.entries.firstOrNull { (key, _) ->
            topic.contains(key, ignoreCase = true)
        }?.value

        return match ?: MicroLesson(
            topic = if (topic.isNotBlank()) topic.take(24) else "पाटी वाचन (Signboard)",
            translation = if (topic.isNotBlank()) "फलकावरील संदेश: $topic" else "फलक समजून घ्या",
            explanation = "हा फलक ${ctx.l2}मध्ये आहे. तुमच्या दैनंदिन कामासाठी हे समजून घेणे उपयुक्त ठरेल.",
            vocabulary = listOf(
                VocabItem(
                    l2Word = if (topic.isNotBlank()) topic.take(16) else "शब्द",
                    l1Meaning = "कार्यस्थळावरील शब्द",
                    romanization = "",
                ),
            ),
            practicePrompt = "हा शब्द स्पष्ट उच्चारासह बोला",
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
