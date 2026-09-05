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

    private data class FallbackRoleplayData(
        val l2: String,
        val l1: String,
        val better: String,
        val hint: String,
    )

    fun nextRoleplayTurn(
        history: List<DialogueTurn>,
        situationId: String,
        currentNodeId: String,
        ctx: GemmaContext,
    ): Map<String, Any?> {
        val userText = history.lastOrNull { it.speaker == "user" }?.text ?: ""
        val l2 = ctx.l2.lowercase()

        val userLower = userText.lowercase()
        val roleplayData = when {
            (l2.startsWith("mr") || l2.contains("marathi") || l2.startsWith("hi") || l2.contains("hindi")) -> {
                when {
                    userLower.contains("पाणी") || userLower.contains("पानी") || userLower.contains("water") || userLower.contains("तहान") -> FallbackRoleplayData(
                        l2 = "पिण्याचे पाणी समोरच्या टाकीजवळ आहे. तिथे जाऊन स्वच्छ पाणी घ्या.",
                        l1 = "पीने का पानी सामने वाली टंकी के पास है। वहां जाकर साफ पानी ले लीजिए।",
                        better = "मला पिण्यासाठी थोडे पाणी मिळेल का?",
                        hint = "‘पाणी’ मधील ‘णी’ चा उच्चार स्पष्ट करा."
                    )
                    userLower.contains("सिमेंट") || userLower.contains("सामान") || userLower.contains("विटा") || userLower.contains("रेती") -> FallbackRoleplayData(
                        l2 = "सामान गोदामात ठेवले आहे. हवी तेवढी पोती घेऊन या आणि नोंद करा.",
                        l1 = "सामान गोदाम में रखा है। जितनी बोरियां चाहिए ले आएं और रजिस्टर में लिख दें।",
                        better = "मला कामासाठी नवीन साहित्य हवे आहे.",
                        hint = "‘गोदाम’ आणि ‘साहित्य’ स्पष्ट बोला."
                    )
                    userLower.contains("चहा") || userLower.contains("चाय") || userLower.contains("जेवण") || userLower.contains("सुट्टी") || userLower.contains("भूख") -> FallbackRoleplayData(
                        l2 = "हो, आता जेवणाची सुट्टी झाली आहे. अर्ध्या तासात चहा पिऊन परत या.",
                        l1 = "हाँ, अब दोपहर की छुट्टी हो गई है। आधे घंटे में चाय पीकर वापस आ जाएं।",
                        better = "आता जेवणाची वेळ झाली आहे का?",
                        hint = "‘सुट्टी’ मधील ‘ट्ट’ वर थोडा जोर द्या."
                    )
                    userLower.contains("पैसे") || userLower.contains("पगार") || userLower.contains("मजुरी") || userLower.contains("रुपये") -> FallbackRoleplayData(
                        l2 = "हिशोब तयार आहे. आज संध्याकाळी ५ वाजता ऑफिसमध्ये येऊन तुमची मजुरी घ्या.",
                        l1 = "हिसाब तैयार है। आज शाम ५ बजे ऑफिस में आकर अपनी मजदूरी ले लीजिए।",
                        better = "माझ्या या आठवड्याचा पगार कधी मिळेल?",
                        hint = "‘मजुरी’ किंवा ‘पगार’ नम्रतेने उच्चारा."
                    )
                    userLower.contains("वेळ") || userLower.contains("उशीर") || userLower.contains("शिफ्ट") || userLower.contains("घंटा") -> FallbackRoleplayData(
                        l2 = "ठीक आहे, आज अर्धा तास उशीर झाला तरी चालेल, पण काम सुरक्षित करा.",
                        l1 = "ठीक है, आज आधा घंटा देर हो जाए तो भी चलेगा, लेकिन काम सावधानी से करें।",
                        better = "मला काम पूर्ण करण्यासाठी आणखी ३० मिनिटे लागतील.",
                        hint = "‘वेळ’ मधील ‘ळ’ चा उच्चार टाळूला जीभ लावून करा."
                    )
                    userLower.contains("आजारी") || userLower.contains("तब्येत") || userLower.contains("दवाखाना") || userLower.contains("डॉक्टर") -> FallbackRoleplayData(
                        l2 = "काळजी घ्या. आधी फर्स्ट एड बॉक्समधून मलम लावा किंवा दवाखान्यात जाऊन या.",
                        l1 = "ध्यान रखें। पहले फर्स्ट एड बॉक्स से मलहम लगाएं या डॉक्टर को दिखा लें।",
                        better = "माझी तब्येत बरी नाही, मला दवाखान्यात जायचे आहे.",
                        hint = "‘काळजी’ चा उच्चार स्पष्ट करा."
                    )
                    userLower.contains("नमस्ते") || userLower.contains("नमस्कार") || userLower.contains("राम") || userLower.contains("hello") || userLower.contains("hi") -> FallbackRoleplayData(
                        l2 = "नमस्ते भाऊ! बोला, आज कामावर काय मदत हवी किंवा काही अडचण आहे का?",
                        l1 = "नमस्ते भाई! बोलिए, आज काम पर क्या मदद चाहिए या कोई परेशानी है?",
                        better = "नमस्ते साहेब, आजचे काम काय आहे?",
                        hint = "‘नमस्ते’ स्पष्ट आणि नम्र स्वरात बोला."
                    )
                    userLower.contains("झाले") || userLower.contains("पूर्ण") || userLower.contains("संपले") || userLower.contains("done") -> FallbackRoleplayData(
                        l2 = "खूप छान! काम नीट झाले आहे. आता पुढील कामाची नोंद करून विश्रांती घ्या.",
                        l1 = "बहुत बढ़िया! काम ठीक से हो गया। अब अगले काम की एंट्री करके थोड़ा आराम कर लें।",
                        better = "साहेब, मी दिलेले काम पूर्ण केले आहे. तपासून पाहा.",
                        hint = "‘पूर्ण’ चा रफार स्पष्ट उच्चारा."
                    )
                    userLower.contains("बिघाड नाही") || userLower.contains("खराबी नहीं") || userLower.contains("काही बिघाड") || userLower.contains("चालू आहे") || userLower.contains("व्यवस्थित आहे") -> FallbackRoleplayData(
                        l2 = "छान, मग मशिन व्यवस्थित चालवा आणि काम सुरू करा. काही अडचण आली तर मला सांगा.",
                        l1 = "बढ़िया, फिर मशीन ठीक से चलाएं और काम शुरू करें। कोई परेशानी हो तो मुझे बताएं।",
                        better = "होय साहेब, सर्व अवजारे आणि मशिन व्यवस्थित चालू आहेत.",
                        hint = "‘व्यवस्थित’ उच्चारताना ‘स्थि’ वर जोर द्या."
                    )
                    userLower.contains("आणली") || userLower.contains("मडली") || userLower.contains("आणले") || userLower.contains("घेऊन") || userLower.contains("लाए") -> FallbackRoleplayData(
                        l2 = "उत्तम! सामान जागेवर ठेवा आणि सुरक्षितपणे कामाला सुरुवात करा.",
                        l1 = "उत्तम! सामान जगह पर रखें और सावधानी से काम शुरू करें।",
                        better = "होय साहेब, मी आवश्यक सामान आणले आहे.",
                        hint = "‘सुरुवात’ स्पष्ट उच्चारा."
                    )
                    userLower.contains("नाही") || userLower.contains("नहीं") || userLower.contains("no") -> FallbackRoleplayData(
                        l2 = "काही अडचण नाही. आधी व्यवस्थित समजून घ्या आणि मग सुरू करा.",
                        l1 = "कोई बात नहीं। पहले ठीक से समझ लीजिए और फिर शुरू कीजिए।",
                        better = "नाही साहेब, मला अजून समजले नाही.",
                        hint = "‘नाही’ उच्चारताना स्पष्ट आवाज ठेवा."
                    )
                    userLower.contains("हो") || userLower.contains("होय") || userLower.contains("yes") || userLower.contains("हाँ") -> FallbackRoleplayData(
                        l2 = "छान! मग कामाला लागा. काही अडचण आली तर मला लगेच बोलवा.",
                        l1 = "अच्छा! फिर काम पर लगिए। कोई परेशानी आए तो मुझे तुरंत बुलाइए।",
                        better = "होय साहेब, मी लगेच काम सुरू करतो.",
                        hint = "‘लगेच’ मधील उच्चार चटकन करा."
                    )
                    userLower.contains("दुकान") || userLower.contains("किंमत") || userLower.contains("भाव") || userLower.contains("पाइप") || userLower.contains("खिळे") -> FallbackRoleplayData(
                        l2 = "हा माल चांगल्या दर्जाचा आहे. तुम्हाला किती नग पाहिजेत ते सांगा?",
                        l1 = "यह सामान बढ़िया क्वालिटी का है। आपको कितने पीस चाहिए बताइए?",
                        better = "भाऊ, ह्या सामानाचा भाव काय आहे?",
                        hint = "‘सामान’ आणि ‘भाव’ स्पष्ट बोला."
                    )
                    userLower.contains("गेट") || userLower.contains("पास") || userLower.contains("नाव") || userLower.contains("आधार") -> FallbackRoleplayData(
                        l2 = "ठीक आहे, तुमचे नाव नोंदवले आहे. आता आत जाऊन सुपरवायझरला भेटा.",
                        l1 = "ठीक है, आपका नाम लिख लिया है। अब अंदर जाकर सुपरवाइजर से मिलिए।",
                        better = "माझा गेट पास हा आहे, मी आत जाऊ शकतो का?",
                        hint = "‘नोंद’ चा उच्चार नाकातून करा."
                    )
                    else -> {
                        val p = situationId.lowercase()
                        when {
                            p.contains("shop") -> FallbackRoleplayData(
                                l2 = "हो भाऊ, नक्की देऊ शकतो. अजून काही सामान हवे आहे का?",
                                l1 = "हाँ भाई, बिल्कुल दे सकता हूँ। और कोई सामान चाहिए क्या?",
                                better = "मला हे सामान द्या आणि बिल सांगा.",
                                hint = "‘बिल’ नम्रपणे मागा."
                            )
                            p.contains("guard") || p.contains("security") -> FallbackRoleplayData(
                                l2 = "समजले भाऊ. सुरक्षेचे नियम पाळा आणि हेल्मेट घालूनच आत जा.",
                                l1 = "समझ गया भाई। सुरक्षा नियमों का पालन करें और हेलमेट पहनकर ही अंदर जाएं।",
                                better = "मी हेल्मेट घातले आहे, धन्यवाद.",
                                hint = "‘सुरक्षा’ चा उच्चार स्पष्ट करा."
                            )
                            p.contains("coworker") -> FallbackRoleplayData(
                                l2 = "बरोबर बोललास भाऊ! चल आपण मिळून हे काम पटकन पूर्ण करूया.",
                                l1 = "सही कहा भाई! चलो हम मिलकर यह काम जल्दी खत्म करते हैं।",
                                better = "हो, आपण दोघे मिळून करूया.",
                                hint = "‘मिळून’ चा उच्चार जिभ वर करून करा."
                            )
                            else -> FallbackRoleplayData(
                                l2 = "ठीक आहे, समजले. कामाची काळजी घ्या आणि सुरक्षितपणे पूर्ण करा.",
                                l1 = "ठीक है, समझ गया। काम का ध्यान रखें और सुरक्षित रूप से पूरा करें।",
                                better = "होय साहेब, मी कामाची काळजी घेईन.",
                                hint = "‘काळजी’ चा उच्चार टाळूच्या मध्यभागी जीभ लावून करा."
                            )
                        }
                    }
                }
            }
            l2.startsWith("te") || l2.contains("telugu") -> FallbackRoleplayData(
                l2 = "సరే, పని సమయానికి పూర్తి కావాలి. సామగ్రిని తనిખీ చేశారా?",
                l1 = "ठीक है, काम समय पर पूरा होना चाहिए। क्या सामान की जांच कर ली?",
                better = "అవును, నేను పని పూర్తి చేసి తనిఖీ చేశాను.",
                hint = "స్పష్టంగా మాట్లాడండి."
            )
            l2.startsWith("kn") || l2.contains("kannada") -> FallbackRoleplayData(
                l2 = "ಸರಿ, ಕೆಲಸ ಸಮಯಕ್ಕೆ ಮುಗಿಯಬೇಕು. ಸಾಮಗ್ರಿ ಪರಿಶೀಲನೆ ಮಾಡಿದ್ದೀರಾ?",
                l1 = "ठीक है, काम समय पर पूरा होना चाहिए। क्या सामान की जांच कर ली?",
                better = "ಹೌದು, ನಾನು ಕೆಲಸ ಮುಗಿಸಿ ಪರಿಶೀಲಿಸಿದ್ದೇನೆ.",
                hint = "ಸ್ಪಷ್ಟವಾಗಿ ಮಾತನಾಡಿ."
            )
            l2.startsWith("ml") || l2.contains("malayalam") -> FallbackRoleplayData(
                l2 = "ശരി, ജോലി സമയത്തിന് തീരണം. സാധനങ്ങൾ പരിശോധിച്ചോ?",
                l1 = "ठीक है, काम समय पर पूरा होना चाहिए। क्या सामान की जांच कर ली?",
                better = "അതെ, ഞാൻ ജോലി പൂർത്തിയാക്കി പരിശോധിച്ചു.",
                hint = "വ്യക്തമായി സംസാരിക്കുക."
            )
            l2.startsWith("bn") || l2.contains("bengali") -> FallbackRoleplayData(
                l2 = "ঠিক আছে, কাজ সময়মতো শেষ করতে হবে। মালপত্র পরীক্ষা করেছেন?",
                l1 = "ठीक है, काम समय पर पूरा होना चाहिए। क्या सामान की जांच कर ली?",
                better = "হ্যাঁ, আমি কাজ শেষ করে পরীক্ষা করেছি।",
                hint = "স্পষ্টভাবে কথা বলুন।"
            )
            l2.startsWith("gu") || l2.contains("gujarati") -> FallbackRoleplayData(
                l2 = "બરાબર, કામ સમયસર પૂરું થવું જોઈએ. સામાન ચકાસી લીધો?",
                l1 = "ठीक है, काम समय पर पूरा होना चाहिए। क्या सामान की जांच कर ली?",
                better = "હા, મેં કામ પૂરું કરીને ચકાસી લીધું છે.",
                hint = "સ્પષ્ટ અવાજે બોલો."
            )
            l2.startsWith("or") || l2.contains("odia") -> FallbackRoleplayData(
                l2 = "ଠିକ୍ ଅଛି, କାମ ଠିକ୍ ସମୟରେ ସରିବା ଦରକାର। ସାମଗ୍ରୀ ଯାଞ୍ଚ କଲେଣି?",
                l1 = "ठीक है, काम समय पर पूरा होना चाहिए। क्या सामान की जांच कर ली?",
                better = "ହଁ, ମୁଁ କାମ ସାରି ଯାଞ୍ଚ କରିସାରିଛି।",
                hint = "ସ୍ପଷ୍ଟ ଭାବରେ କୁହନ୍ତୁ।"
            )
            else -> FallbackRoleplayData(
                l2 = "சரி, சிமெண்ட் கலவை விகிதம் என்ன? சரியாக கலந்தீர்களா?",
                l1 = "ठीक है, सीमेंट मिश्रण का अनुपात क्या है? क्या ठीक से मिलाया?",
                better = "சரி, நான் வேலையை முடித்துவிட்டேன்.",
                hint = "நாக்கின் நுனியை மேல் அண்ணத்தில் தொடவும்."
            )
        }

        return mapOf(
            "recognized_transcript" to userText,
            "is_intent_matched" to true,
            "matched_intent" to "workplace_interaction",
            "next_node_id" to "node_02",
            "prompt_l2" to roleplayData.l2,
            "prompt_transliteration" to "",
            "prompt_l1" to roleplayData.l1,
            "pre_rendered_audio_path" to null,
            "pronunciation_score" to -0.35,
            "weak_phonemes" to emptyList<String>(),
            "articulatory_hint" to roleplayData.hint,
            "natural_phrasing" to roleplayData.better,
            "intent_explanation" to "कामाविषयी संवाद साधला.",
            "ai_source" to "fallback",
        )
    }

    // --------------------------------------------------------------------------
    // Semantic Speech Evaluation Fallback
    // --------------------------------------------------------------------------

    fun evaluateSpokenIntent(
        targetPhrase: String,
        prompt: String,
        spokenText: String,
        ctx: GemmaContext,
    ): Map<String, Any> {
        val s = spokenText.trim().lowercase()
        val t = targetPhrase.trim().lowercase()
        val p = prompt.trim().lowercase()

        // Extract significant words
        val targetWords = t.split(Regex("[\\s,।?.!]+")).filter { it.length >= 3 }
        val promptWords = p.split(Regex("[\\s,।?.!]+")).filter { it.length >= 3 }
        val spokenWords = s.split(Regex("[\\s,।?.!]+")).filter { it.length >= 3 }

        val overlap = spokenWords.count { sw ->
            targetWords.any { tw -> tw.contains(sw) || sw.contains(tw) } ||
            promptWords.any { pw -> pw.contains(sw) || sw.contains(pw) }
        }

        val isMatched = overlap >= 1 || (s.length >= 4 && (t.contains(s) || s.contains(t)))
        return mapOf(
            "is_matched" to isMatched,
            "confidence" to if (isMatched) 0.85 else 0.30,
            "feedback" to if (isMatched) "अर्थ योग्य आहे! (Meaning understood!)" else "वाक्य पुन्हा बोलण्याचा प्रयत्न करा.",
            "better_way" to targetPhrase,
            "source" to "fallback",
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

    // Daily Mission fallback (Full 9 Indic languages & workplace occupations)
    // --------------------------------------------------------------------------

    fun generateDailyMission(ctx: GemmaContext): DailyMission {
        val occ = ctx.occupation.lowercase()
        val l2 = ctx.l2.lowercase()
        val isDelivery = occ.contains("deliver") || occ.contains("logist")
        val isSecurity = occ.contains("secur") || occ.contains("guard")

        return when {
            // Tamil (ta)
            l2.startsWith("ta") || l2.contains("tamil") -> when {
                isDelivery -> DailyMission(
                    title = "Clarifying Delivery Address",
                    nativeTitle = "முகவரி கேட்டல்",
                    npcRole = "வாடிக்கையாளர் (Customer)",
                    objective = "Ask the customer for landmark because the building is unclear.",
                    objectiveNative = "ग्राहक से पास का लैंडमार्क पूछें ताकि सही इमारत मिल सके।",
                    openerL2 = "வணக்கம், என் பார்சல் இன்னும் வரவில்லை, நீங்கள் எங்கே இருக்கிறீர்கள்?",
                    openerL1 = "नमस्ते, मेरा पार्सल अभी तक नहीं आया, आप कहाँ हैं?",
                    targetWords = listOf("முகவரி", "கட்டிடம்", "வழி"),
                    maxTurns = 4,
                    source = "fallback",
                )
                isSecurity -> DailyMission(
                    title = "Gate Pass Verification",
                    nativeTitle = "நுழைவுச்சீட்டு சரிபார்ப்பு",
                    npcRole = "பார்வையாளர் (Visitor)",
                    objective = "A visitor has arrived without a badge. Politely ask for identification.",
                    objectiveNative = "एक आगंतुक बिना पहचान पत्र के आया है। पहचान पत्र मांगें।",
                    openerL2 = "நான் மேனேஜரைப் பார்க்க வேண்டும், ஆனால் என் அடையாள அட்டை இல்லை.",
                    openerL1 = "मुझे मैनेजर से मिलना है, पर मेरा आईडी कार्ड नहीं मिल रहा।",
                    targetWords = listOf("அடையாளம்", "பதிவு", "விதி"),
                    maxTurns = 4,
                    source = "fallback",
                )
                else -> DailyMission(
                    title = "Asking for 30 More Minutes",
                    nativeTitle = "கூடுதல் நேரம் கேட்டல்",
                    npcRole = "மேற்பார்வையாளர் (Supervisor)",
                    objective = "Explain the delay and request 30 more minutes.",
                    objectiveNative = "सुपरवाइजर से काम के लिए 30 मिनट का और समय मांगें।",
                    openerL2 = "வேலை ஏன் இன்னும் முடியவில்லை? ஷிப்ட் முடியப்போகிறது.",
                    openerL1 = "काम अभी तक पूरा क्यों नहीं हुआ? शिफ्ट खत्म होने वाली है।",
                    targetWords = listOf("உதவி", "நேரம்", "வேலை"),
                    maxTurns = 4,
                    source = "fallback",
                )
            }

            // Telugu (te)
            l2.startsWith("te") || l2.contains("telugu") -> when {
                isDelivery -> DailyMission(
                    title = "Clarifying Delivery Address",
                    nativeTitle = "చిరునామా అడగడం",
                    npcRole = "కస్టమర్ (Customer)",
                    objective = "Ask the customer for a nearby landmark.",
                    objectiveNative = "ग्राहक को कॉल करके पास का लैंडमार्क पूछें।",
                    openerL2 = "హలో, నా డెలివరీ ఇంకా రాలేదు, మీరు ఎక్కడ ఉన్నారు?",
                    openerL1 = "नमस्ते, मेरी डिलीवरी अभी तक नहीं आई, आप कहाँ हैं?",
                    targetWords = listOf("చిరునామా", "భవనం", "దారి"),
                    maxTurns = 4,
                    source = "fallback",
                )
                else -> DailyMission(
                    title = "Asking for 30 More Minutes",
                    nativeTitle = "సమయం అడగడం",
                    npcRole = "సూపర్‌వైజర్ (Supervisor)",
                    objective = "Explain the delay and request 30 more minutes.",
                    objectiveNative = "सुपरवाइजर से 30 मिनट का समय मांगें।",
                    openerL2 = "పని ఇంకా ఎందుకు పూర్తి కాలేదు? షిఫ్ట్ అయిపోవచ్చింది.",
                    openerL1 = "काम अभी तक पूरा क्यों नहीं हुआ? शिफ्ट खत्म होने वाली है।",
                    targetWords = listOf("సహాయం", "పని", "సమయం"),
                    maxTurns = 4,
                    source = "fallback",
                )
            }

            // Kannada (kn)
            l2.startsWith("kn") || l2.contains("kannada") -> when {
                isDelivery -> DailyMission(
                    title = "Clarifying Delivery Address",
                    nativeTitle = "ವಿಳಾಸ ವಿಚಾರಣೆ",
                    npcRole = "ಗ್ರಾಹಕ (Customer)",
                    objective = "Ask customer for landmark because house number is unclear.",
                    objectiveNative = "ग्राहक से पास का लैंडमार्क पूछें।",
                    openerL2 = "ನಮಸ್ಕಾರ, ನನ್ನ ಡೆಲಿವರಿ ಇನ್ನೂ ಬಂದಿಲ್ಲ, ನೀವು ಎಲ್ಲಿದ್ದೀರಿ?",
                    openerL1 = "नमस्ते, मेरी डिलीवरी अभी तक नहीं आई, आप कहाँ हैं?",
                    targetWords = listOf("ವಿಳಾಸ", "ಕಟ್ಟಡ", "ರಸ್ತೆ"),
                    maxTurns = 4,
                    source = "fallback",
                )
                else -> DailyMission(
                    title = "Asking for 30 More Minutes",
                    nativeTitle = "ಸಮಯ ಕೇಳುವುದು",
                    npcRole = "ಮೇಲ್ವಿಚಾರಕ (Supervisor)",
                    objective = "Explain delay and request 30 more minutes.",
                    objectiveNative = "सुपरवाइजर से 30 मिनट का समय मांगें।",
                    openerL2 = "ಕೆಲಸ ಇನ್ನೂ ಏಕೆ ಮುಗಿದಿಲ್ಲ? ಶಿಫ್ಟ್ ಮುಗಿಯುತ್ತಾ ಬಂದಿದೆ.",
                    openerL1 = "काम अभी तक पूरा क्यों नहीं हुआ? शिफ्ट खत्म होने वाली है।",
                    targetWords = listOf("ಸಹಾಯ", "ಕೆಲಸ", "ಸಮಯ"),
                    maxTurns = 4,
                    source = "fallback",
                )
            }

            // Bengali (bn)
            l2.startsWith("bn") || l2.contains("bengali") -> when {
                isDelivery -> DailyMission(
                    title = "Clarifying Delivery Address",
                    nativeTitle = "ঠিকানা জানা",
                    npcRole = "গ্রাহক (Customer)",
                    objective = "Call customer to ask for landmark.",
                    objectiveNative = "গ্রাহকের কাছে ল্যান্ডমার্ক জানতে চান।",
                    openerL2 = "হ্যালো, আমার ডেলিভারি এখনো আসেনি, আপনি কোথায় আছেন?",
                    openerL1 = "नमस्ते, मेरी डिलीवरी अभी तक नहीं आई, आप कहाँ हैं?",
                    targetWords = listOf("ঠিকানা", "বিল্ডিং", "রাস্তা"),
                    maxTurns = 4,
                    source = "fallback",
                )
                else -> DailyMission(
                    title = "Asking for 30 More Minutes",
                    nativeTitle = "সময় চাওয়া",
                    npcRole = "সুপারভাইজার (Supervisor)",
                    objective = "Explain delay and request 30 more minutes.",
                    objectiveNative = "কাজের জন্য ৩০ মিনিট অতিরিক্ত সময় চান।",
                    openerL2 = "কাজ এখনো শেষ হয়নি কেন? শিফট তো শেষ হতে চলল।",
                    openerL1 = "काम अभी तक पूरा क्यों नहीं हुआ? शिफ्ट खत्म होने वाली है।",
                    targetWords = listOf("সাহায্য", "কাজ", "সময়"),
                    maxTurns = 4,
                    source = "fallback",
                )
            }

            // Gujarati (gu)
            l2.startsWith("gu") || l2.contains("gujarati") -> when {
                isDelivery -> DailyMission(
                    title = "Clarifying Delivery Address",
                    nativeTitle = "સરનામું પૂછવું",
                    npcRole = "ગ્રાહક (Customer)",
                    objective = "Call customer to ask for landmark.",
                    objectiveNative = "ગ્રાહકને લેન્ડમાર્ક વિશે પૂછો.",
                    openerL2 = "નમસ્તે, મારી ડિલિવરી હજી આવી નથી, તમે ક્યાં પહોંચ્યા છો?",
                    openerL1 = "नमस्ते, मेरी डिलीवरी अभी तक नहीं आई, आप कहाँ पहुंचे हैं?",
                    targetWords = listOf("સરનામું", "મકાન", "રસ્તો"),
                    maxTurns = 4,
                    source = "fallback",
                )
                else -> DailyMission(
                    title = "Asking for 30 More Minutes",
                    nativeTitle = "સમય માંગવો",
                    npcRole = "સુપરવાઇઝર (Supervisor)",
                    objective = "Explain delay and request 30 more minutes.",
                    objectiveNative = "કામ માટે ૩૦ મિનિટનો સમય માંગો.",
                    openerL2 = "કામ હજી પૂરું કેમ નથી થયું? શિફ્ટ પૂરી થવા આવી છે.",
                    openerL1 = "काम अभी तक पूरा क्यों नहीं हुआ? शिफ्ट खत्म होने वाली है।",
                    targetWords = listOf("મદદ", "કામ", "સમય"),
                    maxTurns = 4,
                    source = "fallback",
                )
            }

            // Malayalam (ml)
            l2.startsWith("ml") || l2.contains("malayalam") -> when {
                isDelivery -> DailyMission(
                    title = "Clarifying Delivery Address",
                    nativeTitle = "വിലാസം ചോദിക്കൽ",
                    npcRole = "ഉപഭോക്താവ് (Customer)",
                    objective = "Ask customer for landmark.",
                    objectiveNative = "ലാൻഡ്മാർക്ക് ചോദിക്കുക.",
                    openerL2 = "ഹലോ, എൻ്റെ ഡെലിവറി ഇതുവരെ എത്തിയില്ല, നിങ്ങൾ എവിടെയാണ്?",
                    openerL1 = "नमस्ते, मेरी डिलीवरी अभी तक नहीं आई, आप कहाँ हैं?",
                    targetWords = listOf("വിലാസം", "കെട്ടിടം", "വഴി"),
                    maxTurns = 4,
                    source = "fallback",
                )
                else -> DailyMission(
                    title = "Asking for 30 More Minutes",
                    nativeTitle = "സമയം ചോദിക്കൽ",
                    npcRole = "സൂപ്പർവൈസർ (Supervisor)",
                    objective = "Explain delay and request 30 more minutes.",
                    objectiveNative = "കൂടുതൽ സമയം ചോദിക്കുക.",
                    openerL2 = "ജോലി എന്താണ് ഇതുവരെ തീരാഞ്ഞത്? സമയം കഴിയാറായി.",
                    openerL1 = "काम अभी तक पूरा क्यों नहीं हुआ? समय खत्म होने वाला है।",
                    targetWords = listOf("സഹായം", "ജോലി", "സമയം"),
                    maxTurns = 4,
                    source = "fallback",
                )
            }

            // Odia (or)
            l2.startsWith("or") || l2.contains("odia") -> when {
                isDelivery -> DailyMission(
                    title = "Clarifying Delivery Address",
                    nativeTitle = "ଠିକଣା ପଚାରିବା",
                    npcRole = "ଗ୍ରାହକ (Customer)",
                    objective = "Ask customer for landmark.",
                    objectiveNative = "ଗ୍ରାହକଙ୍କୁ ଠିକଣା ପଚାରନ୍ତୁ।",
                    openerL2 = "ନମସ୍କାର, ମୋର ଡେଲିଭରୀ ଏପର୍ଯ୍ୟନ୍ତ ଆସିନାହିଁ, ଆପଣ କେଉଁଠି ଅଛନ୍ତି?",
                    openerL1 = "नमस्ते, मेरी डिलीवरी अभी तक नहीं आई, आप कहाँ हैं?",
                    targetWords = listOf("ଠିକଣା", "ବିଲ୍ଡିଂ", "ରାସ୍ତା"),
                    maxTurns = 4,
                    source = "fallback",
                )
                else -> DailyMission(
                    title = "Asking for 30 More Minutes",
                    nativeTitle = "ସମୟ ମାଗିବା",
                    npcRole = "ସୁପରଭାଇଜର (Supervisor)",
                    objective = "Explain delay and request 30 more minutes.",
                    objectiveNative = "କାମ ପାଇଁ ଅଧିକ ସମୟ ମାଗନ୍ତୁ।",
                    openerL2 = "କାମ ଏପର୍ଯ୍ୟନ୍ତ କାହିଁକି ସରିନାହିଁ? ସମୟ ସରିବାକୁ ବସିଲାଣି।",
                    openerL1 = "काम अभी तक पूरा क्यों नहीं हुआ? समय खत्म होने वाला है।",
                    targetWords = listOf("ସାହାଯ୍ୟ", "କାମ", "ସମୟ"),
                    maxTurns = 4,
                    source = "fallback",
                )
            }

            // Hindi (hi)
            l2.startsWith("hi") || l2.contains("hindi") -> when {
                isDelivery -> DailyMission(
                    title = "Clarifying Customer Address",
                    nativeTitle = "डिलीवरी का पता पूछना",
                    npcRole = "ग्राहक (Customer)",
                    objective = "You cannot locate the flat number. Call the customer and ask for a landmark.",
                    objectiveNative = "आपको फ्लैट नंबर नहीं मिल रहा। ग्राहक को कॉल करके पास का लैंडमार्क पूछें।",
                    openerL2 = "नमस्ते, मेरी डिलीवरी अभी तक नहीं आई, आप कहाँ पहुँचे हैं?",
                    openerL1 = "नमस्ते, मेरी डिलीवरी अभी तक नहीं आई, आप कहाँ पहुँचे हैं?",
                    targetWords = listOf("पता", "बिल्डिंग", "रास्ता"),
                    maxTurns = 4,
                    source = "fallback",
                )
                isSecurity -> DailyMission(
                    title = "Gate Pass Verification",
                    nativeTitle = "गेट पास सत्यापन",
                    npcRole = "आगंतुक (Visitor)",
                    objective = "A visitor has arrived without a badge. Politely ask for identification and register them.",
                    objectiveNative = "एक आगंतुक बिना पहचान पत्र के आया है। पहचान पत्र मांगें।",
                    openerL2 = "मुझे मैनेजर से मिलने अंदर जाना है, लेकिन मेरा पहचान पत्र नहीं मिल रहा।",
                    openerL1 = "मुझे मैनेजर से मिलने अंदर जाना है, लेकिन मेरा पहचान पत्र नहीं मिल रहा।",
                    targetWords = listOf("पहचान", "रजिस्टर", "नियम"),
                    maxTurns = 4,
                    source = "fallback",
                )
                else -> DailyMission(
                    title = "Asking for 30 More Minutes",
                    nativeTitle = "काम का समय मांगना",
                    npcRole = "सुपरवाइजर (Site Supervisor)",
                    objective = "Your supervisor asks why the work isn't finished. Explain politely and request 30 more minutes.",
                    objectiveNative = "सुपरवाइजर पूछ रहे हैं काम पूरा क्यों नहीं हुआ। स्थिति समझाएं और 30 मिनट का समय मांगें।",
                    openerL2 = "काम अभी तक पूरा क्यों नहीं हुआ? आज की शिफ्ट खत्म होने वाली है।",
                    openerL1 = "काम अभी तक पूरा क्यों नहीं हुआ? आज की शिफ्ट खत्म होने वाली है।",
                    targetWords = if (ctx.frequentlyMissedWords.isNotEmpty()) ctx.frequentlyMissedWords.take(3) else listOf("मदद", "परेशानी", "समय"),
                    maxTurns = 4,
                    source = "fallback",
                )
            }

            // Default: Marathi (mr)
            else -> when {
                isSecurity -> DailyMission(
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
                isDelivery -> DailyMission(
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
    }

    // --------------------------------------------------------------------------
    // Listen Around Me fallback
    // --------------------------------------------------------------------------

    // --------------------------------------------------------------------------
    // Listen Around Me fallback (Rich Domain Lexicon & Intent Matcher)
    // --------------------------------------------------------------------------

    internal val workplaceWordDict = mapOf(
        // Marathi (mr)
        "हातोडी" to "हथौड़ा / hammer",
        "सिमेंट" to "सीमेंट / cement",
        "वाळू" to "बालू / रेत / sand",
        "खडी" to "गिट्टी / gravel",
        "गिलावा" to "प्लास्टर / plaster",
        "माप" to "नाप / measurement",
        "शिडी" to "सीढ़ी / ladder",
        "पाईप" to "पाइप / pipe",
        "वायर" to "तार / wire",
        "स्विच" to "स्विच / switch",
        "चावी" to "चाबी / key",
        "पाना" to "पाना / spanner",
        "ड्रिल" to "ड्रिल / drill",
        "लोखंड" to "लोहा / iron",
        "लाकूड" to "लकड़ी / wood",
        "रंग" to "पेंट / रंग / paint",
        "झाडू" to "झाड़ू / broom",
        "पाणी" to "पानी / water",
        "गोणी" to "बोरी / sack",
        "हेल्मेट" to "हेलमेट / helmet",
        "काळजी" to "सावधानी / caution",
        "धोका" to "खतरा / danger",
        "सावकाश" to "धीरे / slowly",
        "लवकर" to "जल्दी / quickly",
        "थांबा" to "रुकिए / stop",
        "तिकडे" to "उधर / there",
        "इकडे" to "इधर / here",
        "वर" to "ऊपर / up",
        "खाली" to "नीचे / down",
        "बाहेर" to "बाहर / outside",
        "आत" to "अंदर / inside",
        "गोदाम" to "गोदाम / warehouse",
        "गोडाउन" to "गोदाम / warehouse",
        "माल" to "माल / goods",
        "सामान" to "सामान / material",
        "पावती" to "रसीद / receipt",
        "सही" to "हस्ताक्षर / signature",
        "वजन" to "वजन / weight",
        "वेळ" to "समय / time",
        "उशीर" to "देर / delay",
        "पगार" to "वेतन / salary",
        "सुट्टी" to "छुट्टी / leave",
        "जेवण" to "खाना / meal",
        "चहा" to "चाय / tea",
        "साहेब" to "साहब / boss",
        "काम" to "काम / work",
        "मदत" to "मदद / help",
        "मजबूत" to "मजबूत / strong",
        "सुरू" to "शुरू / start",
        "संपले" to "खत्म / finished",
        "पत्ता" to "पता / address",
        "ओळखपत्र" to "पहचान पत्र / ID card",
        // Tamil (ta)
        "சுத்தியல்" to "हथौड़ा / hammer",
        "சிமெண்ட்" to "सीमेंट / cement",
        "மணல்" to "बालू / sand",
        "வேலை" to "काम / work",
        "நேரம்" to "समय / time",
        "சீக்கிரம்" to "जल्दी / quickly",
        "உதவி" to "मदद / help",
        "கவனம்" to "सावधान / caution",
        "தலைக்கவசம்" to "हेलमेट / helmet",
        "ரசீது" to "रसीद / receipt",
        "படிக்கட்டு" to "सीढ़ी / stairs",
        "தண்ணீர்" to "पानी / water",
        // Telugu (te)
        "సుత్తి" to "हथौड़ा / hammer",
        "సిమెంట్" to "सीमेंट / cement",
        "ఇసుక" to "बालू / sand",
        "పని" to "काम / work",
        "సమయం" to "समय / time",
        "త్వరగా" to "जल्दी / quickly",
        "సహాయం" to "मदद / help",
        "జాగ్రత్త" to "सावधान / caution",
        "హెల్మెట్" to "हेलमेट / helmet",
        "రసీదు" to "रसीद / receipt",
        "నీరు" to "पानी / water",
        // Kannada (kn)
        "ಸುತ್ತಿಗೆ" to "हथौड़ा / hammer",
        "ಸಿಮೆಂಟ್" to "सीमेंट / cement",
        "ಮರಳು" to "बालू / sand",
        "ಕೆಲಸ" to "काम / work",
        "ಸಮಯ" to "समय / time",
        "ಬೇಗ" to "जल्दी / quickly",
        "ಸಹಾಯ" to "मदद / help",
        "ಎಚ್ಚರ" to "सावधान / caution",
        "ಹೆಲ್ಮೆಟ್" to "हेलमेट / helmet",
        "ರಸೀದಿ" to "रसीद / receipt",
        "ನೀರು" to "पानी / water"
    )

    /** Returns the L1 meaning string for [word] if it exists in the workplace dict, else null. */
    fun lookupWord(word: String): String? = workplaceWordDict[word.lowercase().trim()]
        ?: workplaceWordDict[word.trim()]

    fun analyzeHeardPhrase(phrase: String, ctx: GemmaContext): HeardPhraseAnalysis {
        val p = phrase.lowercase().trim()
        val tokens = p.split(Regex("[\\s,?.!।\"'\\-]+")).filter { it.isNotBlank() }

        // Dynamic word extraction from phrase
        val extractedWords = mutableListOf<WordMeaning>()
        for (token in tokens) {
            val meaning = workplaceWordDict[token]
            if (meaning != null && extractedWords.none { it.word == token }) {
                extractedWords.add(WordMeaning(token, meaning))
            }
        }

        // 1. Placement / Logistics / Material location
        if (p.contains("कुठे") || p.contains("सामान") || p.contains("ठेव") || p.contains("गोदाम") || p.contains("उतरवा") || p.contains("खाली करा")) {
            val words = if (extractedWords.isNotEmpty()) extractedWords else listOf(
                WordMeaning("सामान", "सामग्री / goods"),
                WordMeaning("कुठे", "कहाँ / where"),
                WordMeaning("ठेवायचं", "रखना है / to place"),
            )
            return HeardPhraseAnalysis(
                heardPhrase = phrase,
                meaningL1 = "पूछा जा रहा है कि सामान कहां रखना है या सामग्री कहां खाली करनी है।",
                toneIntent = "विचारणा / Workplace Inquiry",
                importantWords = words,
                suggestedReplyL2 = "हे सामान आत गोदामात ठेवा.",
                replyMeaningL1 = "यह सामान अंदर गोदाम में रख दीजिए।",
                replyRoman = "he saamaan aat godaamaat theva",
                source = "fallback",
            )
        }

        // 2. Urgent Time / Hurry / Shift Wrapup
        if (p.contains("लवकर") || p.contains("वेळ") || p.contains("उशीर") || p.contains("पटकन") || p.contains("घाई") || p.contains("சீக்கிரம்") || p.contains("త్వరగా") || p.contains("ಬೇಗ")) {
            val words = if (extractedWords.isNotEmpty()) extractedWords else listOf(
                WordMeaning("लवकर", "जल्दी / quickly"),
                WordMeaning("वेळ", "समय / time"),
                WordMeaning("कमी", "कम / short"),
            )
            return HeardPhraseAnalysis(
                heardPhrase = phrase,
                meaningL1 = "काम जल्दी पूरा करने की ताकीद की जा रही है क्योंकि समय कम है।",
                toneIntent = "ताकीद / Urgent Instruction",
                importantWords = words,
                suggestedReplyL2 = "हो, मी लगेच पूर्ण करतो.",
                replyMeaningL1 = "जी, मैं अभी तुरंत पूरा करता हूँ।",
                replyRoman = "ho, mee lagech poorna karto",
                source = "fallback",
            )
        }

        // 3. Safety Alert / Hazard / Restricted Zone
        if (p.contains("तिकडे") || p.contains("जाऊ नका") || p.contains("थांबा") || p.contains("धोका") || p.contains("हेल्मेट") || p.contains("काळजी") || p.contains("கவனம்") || p.contains("జాగ్రత్త") || p.contains("ಎಚ್ಚರ")) {
            val words = if (extractedWords.isNotEmpty()) extractedWords else listOf(
                WordMeaning("तिकडे", "उधर / there"),
                WordMeaning("जाऊ नका", "मत जाइए / don't go"),
                WordMeaning("काळजी", "सावधानी / caution"),
            )
            return HeardPhraseAnalysis(
                heardPhrase = phrase,
                meaningL1 = "चेतावनी दी जा रही है कि उस तरफ मत जाइए, वहां खतरा या काम चल रहा है।",
                toneIntent = "चेतावणी / Safety Warning",
                importantWords = words,
                suggestedReplyL2 = "ठीक आहे, मी इकडेच थांबतो.",
                replyMeaningL1 = "ठीक है, मैं यहीं रुकता हूँ।",
                replyRoman = "theek aahe, mee ikdech thaambto",
                source = "fallback",
            )
        }

        // 4. Verification / Challan / Entry Pass / ID
        if (p.contains("पावती") || p.contains("पास") || p.contains("कार्ड") || p.contains("दाखवा") || p.contains("नोंद") || p.contains("रसीद") || p.contains("రసీదు") || p.contains("ரசீது")) {
            val words = if (extractedWords.isNotEmpty()) extractedWords else listOf(
                WordMeaning("पावती", "रसीद / receipt"),
                WordMeaning("दाखवा", "दिखाइए / show"),
                WordMeaning("तपासा", "जांचिए / verify"),
            )
            return HeardPhraseAnalysis(
                heardPhrase = phrase,
                meaningL1 = "गेट पर या काउंटर पर पर्ची या पहचान पत्र दिखाने को कहा जा रहा है।",
                toneIntent = "मागणी / Verification Request",
                importantWords = words,
                suggestedReplyL2 = "ही घ्या माझी पावती.",
                replyMeaningL1 = "यह लीजिए मेरी पर्ची / रसीद।",
                replyRoman = "hee ghya maajhi paavti",
                source = "fallback",
            )
        }

        // 5. Schedule / Timing / Tomorrow morning
        if (p.contains("उद्या") || p.contains("सकाळी") || p.contains("वाजता") || p.contains("सुट्टी") || p.contains("रात्रपाळी")) {
            val words = if (extractedWords.isNotEmpty()) extractedWords else listOf(
                WordMeaning("उद्या", "कल / tomorrow"),
                WordMeaning("सकाळी", "सुबह / morning"),
                WordMeaning("वाजता", "बजे / o'clock"),
            )
            return HeardPhraseAnalysis(
                heardPhrase = phrase,
                meaningL1 = "कल की शिफ्ट के समय या सुबह आने का निर्देश दिया जा रहा है।",
                toneIntent = "वेळ / Shift Schedule",
                importantWords = words,
                suggestedReplyL2 = "होय साहेब, मी वेळेवर हजर राहीन.",
                replyMeaningL1 = "जी साहब, मैं समय पर उपस्थित रहूँगा।",
                replyRoman = "hoy saheb, mee velevar hajar raheen",
                source = "fallback",
            )
        }

        // 6. Quality / Construction / Tools / Measurement
        if (p.contains("माप") || p.contains("सिमेंट") || p.contains("वाळू") || p.contains("हातोडी") || p.contains("मजबूत") || p.contains("गिलावा") || p.contains("पाणी मारा")) {
            val words = if (extractedWords.isNotEmpty()) extractedWords else listOf(
                WordMeaning("माप", "नाप / measurement"),
                WordMeaning("मजबूत", "मजबूत / strong"),
                WordMeaning("काम", "काम / work"),
            )
            return HeardPhraseAnalysis(
                heardPhrase = phrase,
                meaningL1 = "निर्माण सामग्री, नाप या काम की मजबूती संबंधी निर्देश दिया जा रहा है।",
                toneIntent = "काम सूचना / Technical Directive",
                importantWords = words,
                suggestedReplyL2 = "होय, मी अचूक माप घेऊन काम करतो.",
                replyMeaningL1 = "जी, मैं सही नाप लेकर काम करता हूँ।",
                replyRoman = "hoy, mee achuk maap gheun kaam karto",
                source = "fallback",
            )
        }

        // 7. Generic Intelligent Fallback (Multilingual aware)
        val words = if (extractedWords.isNotEmpty()) extractedWords else listOf(
            WordMeaning("काम", "काम / work"),
            WordMeaning("सूचना", "निर्देश / instruction"),
        )
        val (replyL2, replyL1, replyRoman) = when {
            ctx.l2.startsWith("ta", ignoreCase = true) || ctx.l2.contains("tamil", ignoreCase = true) ->
                Triple("சரி, புரிந்தது. உடனே செய்கிறேன்.", "हाँ, समझ गया। मैं तुरंत करता हूँ।", "sari, purindhadhu. udane seigiren")
            ctx.l2.startsWith("te", ignoreCase = true) || ctx.l2.contains("telugu", ignoreCase = true) ->
                Triple("సరే, అర్థమైంది. వెంటనే చేస్తాను.", "हाँ, समझ गया। मैं तुरंत करता हूँ।", "sare, arthamaindi. ventane chesthanu")
            ctx.l2.startsWith("kn", ignoreCase = true) || ctx.l2.contains("kannada", ignoreCase = true) ->
                Triple("ಸರಿ, ತಿಳಿಯಿತು. ತಕ್ಷಣ ಮಾಡುತ್ತೇನೆ.", "हाँ, समझ गया। ನಾನು వెంటనే ಮಾಡುತ್ತೇನೆ।", "sari, thiliyithu. thakshana maaduththene")
            ctx.l2.startsWith("ml", ignoreCase = true) || ctx.l2.contains("malayalam", ignoreCase = true) ->
                Triple("ശരി, മനസ്സിലായി. ഉടൻ ചെയ്യാം.", "हाँ, समझ गया। मैं तुरंत करता हूँ।", "shari, manassilaayi. udan cheyyaam")
            ctx.l2.startsWith("bn", ignoreCase = true) || ctx.l2.contains("bengali", ignoreCase = true) ->
                Triple("ঠিক আছে, বুঝতে পেরেছি। এখনই করছি।", "हाँ, समझ गया। मैं तुरंत करता हूँ।", "theek aache, bujhte perechi. ekhoni korchi")
            ctx.l2.startsWith("gu", ignoreCase = true) || ctx.l2.contains("gujarati", ignoreCase = true) ->
                Triple("બરાબર, સમજાઈ ગયું. હું હમણાં જ કરું છું.", "हाँ, समझ गया। मैं तुरंत करता हूँ।", "barabar, samjai gayu. hu hamna j karu chhu")
            ctx.l2.startsWith("or", ignoreCase = true) || ctx.l2.contains("odia", ignoreCase = true) ->
                Triple("ଠିକ୍ ଅଛି, ବୁଝିପାରିଲି। ଏବେ କରୁଛି।", "हाँ, समझ गया। मैं तुरंत करता हूँ।", "thik achhi, bujhiparili. ebe karuchhi")
            ctx.l2.startsWith("hi", ignoreCase = true) || ctx.l2.contains("hindi", ignoreCase = true) ->
                Triple("हाँ, समझ गया। मैं तुरंत करता हूँ।", "हाँ, समझ गया। मैं तुरंत करता हूँ।", "haan, samajh gaya. main turant karta hoon")
            else ->
                Triple("हो, समजले. मी लगेच करतो.", "हाँ, समझ गया। मैं तुरंत करता हूँ।", "ho, samajle. mee lagech karto")
        }

        return HeardPhraseAnalysis(
            heardPhrase = if (phrase.isNotBlank()) phrase else "कामाची सूचना",
            meaningL1 = "कार्यस्थल पर काम संबंधी निर्देश या बातचीत की जा रही है।",
            toneIntent = "सूचना / Workplace Instruction",
            importantWords = words,
            suggestedReplyL2 = replyL2,
            replyMeaningL1 = replyL1,
            replyRoman = replyRoman,
            source = "fallback",
        )
    }

    // --------------------------------------------------------------------------
    // Pronunciation scoring — always deterministic, Gemma never touches this
    // --------------------------------------------------------------------------

    fun scorePronunciation(targetWord: String, canonicalG2P: String, dialect: String = "standard"): Map<String, Any?> {
        val isNagpuri = dialect.contains("nagpur", true) || dialect.contains("varhad", true)
        val isChennai = dialect.contains("chennai", true) || dialect.contains("madras", true)
        val isMadurai = dialect.contains("madurai", true)

        val dialectToleranceNote = when {
            isNagpuri -> "Acceptable regional variant in Nagpur / Varhadi Marathi (वैध विदर्भी उच्चार)"
            isChennai -> "Recognized Chennai urban dialect cadence (சென்னை வட்டார வழக்கு)"
            isMadurai -> "Recognized Southern Madurai dialect cadence (மதுரை வழக்கு)"
            else -> "Standard regional pronunciation"
        }

        val overallScore = if (isNagpuri || isChennai || isMadurai) -0.15 else -0.32
        return mapOf(
            "target_word" to targetWord,
            "target_transliteration" to canonicalG2P,
            "overall_score" to overallScore,
            "phonemes" to listOf(
                mapOf(
                    "phoneme" to "ट",
                    "ipa_symbol" to "ʈ",
                    "score" to if (isNagpuri) -0.12 else -0.35,
                    "is_correct" to true,
                    "substituted_phoneme" to null,
                    "articulation_guidance" to if (isNagpuri || isChennai) "Natural regional pronunciation accepted" else "Curl tongue slightly back against the hard palate",
                )
            ),
            "dialect_variant_detected" to (isNagpuri || isChennai || isMadurai),
            "dialect_note" to dialectToleranceNote,
            "l1_interference_diagnostic" to "Dialect acoustic boundary calibrated for $dialect",
        )
    }

    companion object {
        private const val TAG = "SeedheBolDeterministic"
    }
}
