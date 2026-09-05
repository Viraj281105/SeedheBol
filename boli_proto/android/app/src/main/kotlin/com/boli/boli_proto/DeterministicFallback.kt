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
            "natural_phrasing" to "हे काम व्यवस्थित झाले आहे.",
            "intent_explanation" to "कामाविषयी संवाद साधला.",
            "ai_source" to "fallback",
        )
    }

    // --------------------------------------------------------------------------
    // Dynamic workplace exercises fallback
    // --------------------------------------------------------------------------

    fun generateDynamicExercises(
        situation: String,
        domain: String,
        ctx: GemmaContext,
    ): List<DynamicExercise> {
        return listOf(
            DynamicExercise(
                kind = "speak",
                prompt = "कामाची माहिती द्या (Report work status)",
                targetText = "काम पूर्ण झाले आहे, तपासा.",
                roman = "Kaam poorna jhaale aahe, tapaasaa.",
                translation = "काम पूरा हो गया है, जांच लीजिए।",
            ),
            DynamicExercise(
                kind = "choice",
                prompt = "कामाच्या ठिकाणी मदत हवी असल्यास काय म्हणाल?",
                targetText = "मला येथे मदत हवी आहे",
                roman = "",
                translation = "मुझे यहां मदद चाहिए",
                options = listOf("मला येथे मदत हवी आहे", "दुकान कुठे आहे", "घरी जायचे आहे"),
                answerIndex = 0,
            ),
            DynamicExercise(
                kind = "speak",
                prompt = "पुढचे काम विचारा (Ask for next instruction)",
                targetText = "पुढचे काम काय करायचे आहे?",
                roman = "Pudhche kaam kaay karaayche aahe?",
                translation = "आगे का काम क्या करना है?",
            ),
        )
    }

    // --------------------------------------------------------------------------
    // Peer coaching fallback for "With Someone"
    // --------------------------------------------------------------------------

    fun coachPeerTurn(
        spokenText: String,
        speakerRole: String,
        ctx: GemmaContext,
    ): PeerTurnCoachResult {
        return PeerTurnCoachResult(
            speakerRole = speakerRole,
            spokenText = spokenText,
            translation = if (spokenText.isNotBlank()) "संदेश: $spokenText" else "काहीही ऐकू आले नाही",
            betterWay = if (spokenText.isNotBlank()) "कृपया $spokenText" else "",
            coachTip = "संवादात स्पष्ट आणि शांत आवाजात बोला.",
            nextPromptSuggestion = "पुढे काय करायचे ते विचारा.",
            source = "fallback",
        )
    }

    // --------------------------------------------------------------------------
    // Daily Mission fallback
    // --------------------------------------------------------------------------

    fun generateDailyMission(ctx: GemmaContext): DailyMission {
        val occ = ctx.occupation.lowercase()
        return when {
            occ.contains("secur") -> DailyMission(
                title = "Gate Pass Verification",
                nativeTitle = "गेट पास पडताळणी",
                npcRole = "व्हिजिटर (Visitor)",
                objective = "A visitor has arrived without a badge. Politely ask for identification and register them.",
                objectiveNative = "एक आगंतुक बिना पहचान पत्र के आया है। विनम्रतापूर्वक उनका पहचान पत्र मांगें और एंट्री करें।",
                openerL2 = "मला मॅनेजरला भेटायला आत जायचे आहे, पण माझे ओळखपत्र सापडत नाही.",
                openerL1 = "मुझे मैनेजर से मिलने अंदर जाना है, लेकिन मेरा पहचान पत्र नहीं मिल रहा।",
                targetWords = listOf("ओळखपत्र", "नोंद", "नियम"),
                maxTurns = 4,
                source = "fallback",
            )
            occ.contains("deliver") -> DailyMission(
                title = "Clarifying Customer Address",
                nativeTitle = "ग्राहकाचा पत्ता विचारणे",
                npcRole = "ग्राहक (Customer)",
                objective = "You cannot locate the flat number. Call the customer and ask for a landmark.",
                objectiveNative = "आपको फ्लैट नंबर नहीं मिल रहा। ग्राहक को कॉल करके पास का लैंडमार्क पूछें।",
                openerL2 = "हॅलो, माझी डिलिव्हरी अजून आली नाही, तुम्ही कुठे थांबला आहात?",
                openerL1 = "हेलो, मेरी डिलीवरी अभी तक नहीं आई, आप कहां रुके हैं?",
                targetWords = listOf("पत्ता", "इमारत", "दोन मिनिटे"),
                maxTurns = 4,
                source = "fallback",
            )
            else -> DailyMission(
                title = "Asking for 30 More Minutes",
                nativeTitle = "कामाची वेळ वाढवून मागणे",
                npcRole = "सुपरवायझर (Site Supervisor)",
                objective = "Your supervisor asks why the work isn't finished. Explain the issue politely and request 30 more minutes.",
                objectiveNative = "सुपरवाइजर पूछ रहे हैं काम पूरा क्यों नहीं हुआ। स्थिति समझाएं और 30 मिनट का समय मांगें।",
                openerL2 = "काम अजून पूर्ण का झाले नाही? आजची शिफ्ट संपत आली आहे.",
                openerL1 = "काम अभी तक पूरा क्यों नहीं हुआ? आज की शिफ्ट खत्म होने वाली है।",
                targetWords = if (ctx.frequentlyMissedWords.isNotEmpty()) ctx.frequentlyMissedWords.take(3) else listOf("मदत", "अडचण", "वेळ"),
                maxTurns = 4,
                source = "fallback",
            )
        }
    }

    // --------------------------------------------------------------------------
    // Listen Around Me fallback
    // --------------------------------------------------------------------------

    fun analyzeHeardPhrase(phrase: String, ctx: GemmaContext): HeardPhraseAnalysis {
        val p = phrase.lowercase()
        return when {
            p.contains("कुठे") || p.contains("सामान") || p.contains("ठेव") -> HeardPhraseAnalysis(
                heardPhrase = phrase,
                meaningL1 = "पूछा जा रहा है कि सामान कहां रखना है या सामग्री कहां उतारनी है।",
                toneIntent = "विचारणा / Workplace Inquiry",
                importantWords = listOf(
                    WordMeaning("सामान", "सामग्री / goods"),
                    WordMeaning("कुठे", "कहाँ / where"),
                    WordMeaning("ठेवायचं", "रखना है / to place"),
                ),
                suggestedReplyL2 = "हे सामान आत गोदामात ठेवा.",
                replyMeaningL1 = "यह सामान अंदर गोदाम में रख दीजिए।",
                replyRoman = "he saamaan aat godaamaat theva",
                source = "fallback",
            )
            p.contains("लवकर") || p.contains("वेळ") || p.contains("उशीर") -> HeardPhraseAnalysis(
                heardPhrase = phrase,
                meaningL1 = "काम जल्दी पूरा करने की ताकीद की जा रही है क्योंकि समय कम है।",
                toneIntent = "ताकीद / Urgent Instruction",
                importantWords = listOf(
                    WordMeaning("लवकर", "जल्दी / quickly"),
                    WordMeaning("वेळ", "समय / time"),
                    WordMeaning("कमी", "कम / short"),
                ),
                suggestedReplyL2 = "हो, मी लगेच पूर्ण करतो.",
                replyMeaningL1 = "जी, मैं अभी तुरंत पूरा करता हूँ।",
                replyRoman = "ho, mee lagech poorna karto",
                source = "fallback",
            )
            p.contains("तिकडे") || p.contains("जाऊ नका") || p.contains("थांबा") || p.contains("धोका") -> HeardPhraseAnalysis(
                heardPhrase = phrase,
                meaningL1 = "चेतावनी दी जा रही है कि उस तरफ मत जाइए, वहां खतरा या काम चल रहा है।",
                toneIntent = "चेतावणी / Safety Warning",
                importantWords = listOf(
                    WordMeaning("तिकडे", "उधर / there"),
                    WordMeaning("जाऊ नका", "मत जाइए / don't go"),
                    WordMeaning("काळजी", "सावधानी / caution"),
                ),
                suggestedReplyL2 = "ठीक आहे, मी इकडेच थांबतो.",
                replyMeaningL1 = "ठीक है, मैं यहीं रुकता हूँ।",
                replyRoman = "theek aahe, mee ikdech thaambto",
                source = "fallback",
            )
            p.contains("पावती") || p.contains("पास") || p.contains("कार्ड") || p.contains("दाखवा") -> HeardPhraseAnalysis(
                heardPhrase = phrase,
                meaningL1 = "गेट पर या काउंटर पर पर्ची या पहचान पत्र दिखाने को कहा जा रहा है।",
                toneIntent = "मागणी / Verification Request",
                importantWords = listOf(
                    WordMeaning("पावती", "रसीद / receipt"),
                    WordMeaning("दाखवा", "दिखाइए / show"),
                    WordMeaning("तपासा", "जांचिए / verify"),
                ),
                suggestedReplyL2 = "ही घ्या माझी पावती.",
                replyMeaningL1 = "यह लीजिए मेरी पर्ची / रसीद।",
                replyRoman = "hee ghya maajhi paavti",
                source = "fallback",
            )
            else -> HeardPhraseAnalysis(
                heardPhrase = if (phrase.isNotBlank()) phrase else "काम चालू आहे",
                meaningL1 = "कार्यस्थल पर काम संबंधी निर्देश या बातचीत की जा रही है।",
                toneIntent = "सूचना / Workplace Instruction",
                importantWords = listOf(
                    WordMeaning("काम", "काम / work"),
                    WordMeaning("समजले", "समझ में आया / understood"),
                ),
                suggestedReplyL2 = "हो, समजले. मी लगेच करतो.",
                replyMeaningL1 = "हाँ, समझ गया। मैं तुरंत करता हूँ।",
                replyRoman = "ho, samajle. mee lagech karto",
                source = "fallback",
            )
        }
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
        private const val TAG = "SeedheBolDeterministic"
    }
}
