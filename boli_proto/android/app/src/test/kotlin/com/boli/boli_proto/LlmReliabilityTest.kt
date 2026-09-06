package com.boli.boli_proto

import org.junit.Assert.*
import org.junit.Test

class LlmReliabilityTest {

    @Test
    fun testUserReportedDegenerativeRepetitionIsCaught() {
        val userScreenshotGibberish =
            "\"मुझे मान्य पार्य मान होई पार्य मान आयु रेस्टी के मान्य पार्य मान होई पार्य मान होई पार्य मान आयु रेस्टी के मान्य पार्य मान होई पार्य मान होई पार्य मान आयु रेस्टी के मान्य पार्य मान होई पार्य मान होई पार्य मान होई पार्य मान आयु रेस्टी के मान्य पार्य मान होई पार्य मान होई पार्य मान होई पार्य मान होई"

        assertTrue(
            "Sanitizer MUST detect catastrophic repetitive loop from user screenshot",
            LlmOutputSanitizer.hasDegenerativeRepetition(userScreenshotGibberish)
        )

        assertFalse(
            "User screenshot corrupted text MUST NOT pass isValidL2Output",
            LlmOutputSanitizer.isValidL2Output(userScreenshotGibberish, "mr")
        )
    }

    @Test
    fun testNormalWorkplacePhrasesPassSanitizer() {
        val legitimatePhrases = listOf(
            "आजचे काम कसे चालले आहे?" to "mr",
            "आज का काम कैसा चल रहा है?" to "hi",
            "இன்று வேலை எப்படி போகிறது?" to "ta",
            "ఈ రోజు పని ఎలా జరుగుతోంది?" to "te",
            "ಇಂದಿನ ಕೆಲಸ ಹೇಗೆ ನಡೆಯುತ್ತಿದೆ?" to "kn",
            "ഇന്നത്തെ ജോലി എങ്ങനെ പോകുന്നു?" to "ml",
            "আজকের কাজ কেমন চলছে?" to "bn",
            "આજનું કામ કેવું ચાલે છે?" to "gu",
            "ଆଜିର କାମ କିପରି ଚାଲିଛି?" to "or",
        )

        for ((phrase, lang) in legitimatePhrases) {
            assertFalse(
                "Legitimate phrase '$phrase' for $lang should not be flagged as repetitive",
                LlmOutputSanitizer.hasDegenerativeRepetition(phrase)
            )
            assertTrue(
                "Legitimate phrase '$phrase' for $lang must be valid L2 output",
                LlmOutputSanitizer.isValidL2Output(phrase, lang)
            )
        }
    }

    @Test
    fun testScriptMatchingIntegrity() {
        assertTrue("Devanagari must match Marathi", LlmOutputSanitizer.matchesScript("काम कसे आहे", "mr"))
        assertTrue("Devanagari must match Hindi", LlmOutputSanitizer.matchesScript("काम कैसा है", "hi"))
        assertTrue("Tamil script must match Tamil", LlmOutputSanitizer.matchesScript("வணக்கம்", "ta"))
        assertTrue("Telugu script must match Telugu", LlmOutputSanitizer.matchesScript("నమస్కారం", "te"))
        assertTrue("Kannada script must match Kannada", LlmOutputSanitizer.matchesScript("ನಮಸ್ಕಾರ", "kn"))
        assertTrue("Malayalam script must match Malayalam", LlmOutputSanitizer.matchesScript("നമസ്കാരം", "ml"))
        assertTrue("Bengali script must match Bengali", LlmOutputSanitizer.matchesScript("নমস্কার", "bn"))
        assertTrue("Gujarati script must match Gujarati", LlmOutputSanitizer.matchesScript("નમસ્તે", "gu"))
        assertTrue("Odia script must match Odia", LlmOutputSanitizer.matchesScript("ନମସ୍କାର", "or"))

        // Cross-script mismatches
        assertFalse("Tamil characters should not match Marathi", LlmOutputSanitizer.matchesScript("வணக்கம்", "mr"))
        assertFalse("Telugu characters should not match Bengali", LlmOutputSanitizer.matchesScript("నమస్కారం", "bn"))
    }

    @Test
    fun testDeterministicFallbackAll9Languages() {
        val fallback = DeterministicFallback()
        val languages = listOf("mr", "hi", "ta", "te", "kn", "ml", "bn", "gu", "or")

        for (lang in languages) {
            val ctx = GemmaContext(
                l1 = "Hindi",
                l2 = lang,
                occupation = "delivery partner",
                userLevel = "beginner"
            )

            // 1. Daily Mission
            val mission = fallback.generateDailyMission(ctx)
            assertNotNull("Daily mission must not be null for $lang", mission)
            assertTrue("Opener L2 must not be blank for $lang", mission.openerL2.isNotBlank())
            assertFalse(
                "Opener L2 must not have repetition for $lang",
                LlmOutputSanitizer.hasDegenerativeRepetition(mission.openerL2)
            )
            assertTrue(
                "Opener L2 must match expected script for $lang",
                LlmOutputSanitizer.matchesScript(mission.openerL2, lang)
            )

            // 2. Roleplay Turn
            val roleplayMap = fallback.nextRoleplayTurn(
                history = emptyList(),
                situationId = "sit_01",
                currentNodeId = "node_01",
                ctx = ctx
            )
            val promptL2 = roleplayMap["prompt_l2"] as String
            assertTrue("Roleplay prompt_l2 must not be blank for $lang", promptL2.isNotBlank())
            assertFalse(
                "Roleplay prompt_l2 must not have repetition for $lang",
                LlmOutputSanitizer.hasDegenerativeRepetition(promptL2)
            )
            assertTrue(
                "Roleplay prompt_l2 must match expected script for $lang",
                LlmOutputSanitizer.matchesScript(promptL2, lang)
            )

            // 3. Listen Around Me (Heard Phrase)
            val heardAnalysis = fallback.analyzeHeardPhrase("general instruction", ctx)
            assertNotNull("Heard phrase analysis must not be null for $lang", heardAnalysis)
            assertTrue(
                "Suggested reply L2 must not be blank for $lang",
                heardAnalysis.suggestedReplyL2.isNotBlank()
            )
            assertFalse(
                "Suggested reply L2 must not have repetition for $lang",
                LlmOutputSanitizer.hasDegenerativeRepetition(heardAnalysis.suggestedReplyL2)
            )
            assertTrue(
                "Suggested reply L2 must match script for $lang",
                LlmOutputSanitizer.matchesScript(heardAnalysis.suggestedReplyL2, lang)
            )
        }
    }

    @Test
    fun testSanitizeRemovesTrailingRepetitionLoop() {
        val loopTail = "काम पूर्ण झाले आहे. पार्य मान होई पार्य मान होई पार्य मान होई"
        val sanitized = LlmOutputSanitizer.sanitize(loopTail)
        assertNotNull(sanitized)
        assertFalse(LlmOutputSanitizer.hasDegenerativeRepetition(sanitized!!))
        assertTrue(sanitized.startsWith("काम पूर्ण झाले आहे"))
    }

    @Test
    fun testAuthenticIndicReduplicationDoesNotTriggerDegenerativeRepetition() {
        val authenticReduplications = listOf(
            "हळू हळू चाला" to "mr",
            "लवकर लवकर काम करा" to "mr",
            "गरम गरम चहा घ्या" to "mr",
            "नवीन नवीन गोष्टी शिका" to "mr",
            "जाता जाता सामान आणा" to "mr",
            "रोज रोज हेच काम आहे" to "mr",
            "थोडे थोडे पाणी प्या" to "mr",
            "बारीक बारीक विटा लावा" to "mr",
            "साफ साफ सांगा" to "mr",
        )

        for ((phrase, lang) in authenticReduplications) {
            assertFalse(
                "Authentic reduplication '$phrase' should NOT be flagged as repetitive",
                LlmOutputSanitizer.hasDegenerativeRepetition(phrase)
            )
            assertTrue(
                "Authentic reduplication '$phrase' must be valid L2 output",
                LlmOutputSanitizer.isValidL2Output(phrase, lang)
            )
        }
    }

    @Test
    fun testCatastrophicThreePlusRepeatsAreCaught() {
        val degenerative3Plus = listOf(
            "लगेगा लगेगा लगेगा",
            "पार्य पार्य पार्य",
            "काम काम काम काम",
            "होय होय होय होय",
        )

        for (phrase in degenerative3Plus) {
            assertTrue(
                "3+ identical repeats '$phrase' MUST be caught as degenerative",
                LlmOutputSanitizer.hasDegenerativeRepetition(phrase)
            )
        }
    }

    @Test
    fun testSingleWordPoliteAffirmationsPassIsValidL2() {
        val politeSingles = listOf(
            "होय.",
            "नक्कीच!",
            "धन्यवाद!",
            "बरं.",
        )

        for (word in politeSingles) {
            assertTrue(
                "Polite single word '$word' must pass isValidL2Output",
                LlmOutputSanitizer.isValidL2Output(word, "mr")
            )
        }
    }

    @Test
    fun testComprehensiveTagExtractionForDailyMissionAndListenAround() {
        val rawDailyMissionOutput = """
            TITLE: Safety Helmet Check
            NATIVE_TITLE: हेल्मेट तपासणी
            NPC_ROLE: Site Supervisor
            OBJECTIVE: Confirm you have worn safety gear
            OBJECTIVE_NATIVE: तुम्ही हेल्मेट घातले आहे याची खात्री करा
            OPENER_L2: अरे भावा, आज हेल्मेट घातले आहेस का?
            OPENER_L1: अरे भाई, आज हेलमेट पहना है क्या?
            TARGET_WORDS: हेल्मेट, सुरक्षा, होय
            MAX_TURNS: 3
        """.trimIndent()

        val lines = BoliAiLayer.normalizeLlmOutputToLines(rawDailyMissionOutput)
        assertEquals("Safety Helmet Check", BoliAiLayer.findTagValue(lines, "TITLE"))
        assertEquals("हेल्मेट तपासणी", BoliAiLayer.findTagValue(lines, "NATIVE_TITLE"))
        assertEquals("Site Supervisor", BoliAiLayer.findTagValue(lines, "NPC_ROLE"))
        assertEquals("Confirm you have worn safety gear", BoliAiLayer.findTagValue(lines, "OBJECTIVE"))
        assertEquals("तुम्ही हेल्मेट घातले आहे याची खात्री करा", BoliAiLayer.findTagValue(lines, "OBJECTIVE_NATIVE"))
        assertEquals("अरे भावा, आज हेल्मेट घातले आहेस का?", BoliAiLayer.findTagValue(lines, "OPENER_L2"))
        assertEquals("अरे भाई, आज हेलमेट पहना है क्या?", BoliAiLayer.findTagValue(lines, "OPENER_L1"))
        assertEquals("हेल्मेट, सुरक्षा, होय", BoliAiLayer.findTagValue(lines, "TARGET_WORDS"))
        assertEquals("3", BoliAiLayer.findTagValue(lines, "MAX_TURNS"))

        val rawListenAroundOutput = """
            HEARD: काम संपल्यावर अवजारे जागेवर ठेवा
            MEANING: काम खत्म होने पर औजार अपनी जगह पर रखें
            TONE_INTENT: कार्यस्थळ सूचना (Workplace instruction)
            IMPORTANT_WORDS: अवजारे:औजार, जागेवर:जगह पर
            NATURAL_REPLY: होय साहेब, मी लगेच ठेवतो
            REPLY_NATIVE: जी साहब, मैं तुरंत रख देता हूँ
            REPLY_ROMAN: Hoy saheb, mee lagech thevto
        """.trimIndent()

        val listenLines = BoliAiLayer.normalizeLlmOutputToLines(rawListenAroundOutput)
        assertEquals("काम संपल्यावर अवजारे जागेवर ठेवा", BoliAiLayer.findTagValue(listenLines, "HEARD"))
        assertEquals("काम खत्म होने पर औजार अपनी जगह पर रखें", BoliAiLayer.findTagValue(listenLines, "MEANING"))
        assertEquals("कार्यस्थळ सूचना (Workplace instruction)", BoliAiLayer.findTagValue(listenLines, "TONE_INTENT"))
        assertEquals("अवजारे:औजार, जागेवर:जगह पर", BoliAiLayer.findTagValue(listenLines, "IMPORTANT_WORDS"))
        assertEquals("होय साहेब, मी लगेच ठेवतो", BoliAiLayer.findTagValue(listenLines, "NATURAL_REPLY"))
        assertEquals("जी साहब, मैं तुरंत रख देता हूँ", BoliAiLayer.findTagValue(listenLines, "REPLY_NATIVE"))
        assertEquals("Hoy saheb, mee lagech thevto", BoliAiLayer.findTagValue(listenLines, "REPLY_ROMAN"))
    }

    @Test
    fun testOcrLessonParsingDoesNotIncludeHeadersAsVocabulary() {
        val rawLlmOutput = """
            TOPIC: Title in hi
            TRANSLATION: खतरा! आगे काम चल रहा है
            EXPLANATION: यह निर्माण स्थल पर सुरक्षा चेतावनी बोर्ड है। हमेशा ध्यान से काम करें।
            WORD: धोका = खतरा (dhoka)
            WORD: पुढे = आगे (pudhe)
            PRACTICE: येथे काळजीपूर्वक काम करा.
        """.trimIndent()

        val lesson = BoliAiLayer.parseOcrLessonResponse(rawLlmOutput, fallbackTopic = "पाटी वाचन")

        // 1. Topic must NOT be placeholder "Title in hi"
        assertEquals("पाटी वाचन", lesson.topic)

        // 2. Translation, explanation, and practice must match clean text
        assertEquals("खतरा! आगे काम चल रहा है", lesson.translation)
        assertEquals("यह निर्माण स्थल पर सुरक्षा चेतावनी बोर्ड है। हमेशा ध्यान से काम करें।", lesson.explanation)
        assertEquals("येथे काळजीपूर्वक काम करा.", lesson.practicePrompt)

        // 3. Vocabulary must ONLY contain actual words, NEVER TRANSLATION, EXPLANATION, or PRACTICE
        val vocabWords = lesson.vocabulary.map { it.l2Word }
        assertEquals(listOf("धोका", "पुढे"), vocabWords)
        assertFalse("Vocabulary must not include TRANSLATION heading", vocabWords.contains("TRANSLATION"))
        assertFalse("Vocabulary must not include EXPLANATION heading", vocabWords.contains("EXPLANATION"))
        assertFalse("Vocabulary must not include PRACTICE heading", vocabWords.contains("PRACTICE"))
    }

    @Test
    fun testCleanOcrTextStripsBracketAndIconNoise() {
        val aiLayer = BoliAiLayer()
        val noisyOcr = "( ) मोबाईल फोनचा वाप करू नये"
        val cleaned = aiLayer.cleanOcrText(noisyOcr)
        assertEquals("मोबाईल फोनचा वाप करू नये", cleaned)

        val noisyOcr2 = "[o] प्रवेश निषिद्ध"
        assertEquals("प्रवेश निषिद्ध", aiLayer.cleanOcrText(noisyOcr2))

        val numberedOcr = "1. विनापरवाना आत जाऊ नका"
        assertEquals("विनापरवाना आत जाऊ नका", aiLayer.cleanOcrText(numberedOcr))
    }

    @Test
    fun testOcrLessonParsingHandlesGemmaConversationalIntroAndMarkdown() {
        val gemmaChatterOutput = """
            Sure, here is a short workplace micro-lesson from the photographed signboard:
            **TOPIC:** मोबाईल फोन वापर बंदी
            **TRANSLATION:** यहाँ मोबाइल फोन का उपयोग मना है
            **EXPLANATION:** काम के दौरान सुरक्षा के लिए फोन का उपयोग न करें।
            **WORD:** मोबाईल = मोबाइल (mobile)
            **WORD:** वापर = उपयोग (vaapar)
            **PRACTICE:** कामाच्या वेळी मोबाईल वापरू नका.
        """.trimIndent()

        val lesson = BoliAiLayer.parseOcrLessonResponse(gemmaChatterOutput, fallbackTopic = "पाटी वाचन")
        assertEquals("मोबाईल फोन वापर बंदी", lesson.topic)
        assertEquals("यहाँ मोबाइल फोन का उपयोग मना है", lesson.translation)
        assertEquals("काम के दौरान सुरक्षा के लिए फोन का उपयोग न करें।", lesson.explanation)
        assertEquals("कामाच्या वेळी मोबाईल वापरू नका.", lesson.practicePrompt)
        assertEquals(listOf("मोबाईल", "वापर"), lesson.vocabulary.map { it.l2Word })
    }

    @Test
    fun testDirectSynthesisFromOcrTextNeverProducesFakeCementLesson() {
        val knowledgeStore = WorkplaceKnowledgeStore()
        val aiLayer = BoliAiLayer(knowledgeStore = knowledgeStore)
        val ctx = GemmaContext(l1 = "Hindi", l2 = "mr", occupation = "construction", userLevel = "beginner")

        val ragMatch = knowledgeStore.queryRelevantKnowledge(
            utterance = "मोबाईल फोनचा वाप करू नये",
            domain = "signboard",
            language = "mr",
            allowFallback = false
        )
        assertNotNull("Should match mobile phone safety item in Micro-RAG", ragMatch)

        val lesson = aiLayer.synthesizeLessonFromOcrText("मोबाईल फोनचा वाप करू नये", ctx, ragMatch)
        assertEquals("मोबाईल फोनचा वाप करू नये", lesson.topic)
        assertTrue("Translation must address mobile phone use prohibition", lesson.translation.contains("मोबाइल फोन"))
        assertFalse("Must never output cement on a mobile phone sign", lesson.translation.contains("सीमेंट"))
        assertFalse("Must never output cement in topic", lesson.topic.contains("सिमेंट"))
        assertTrue("Vocabulary must contain mobile", lesson.vocabulary.any { it.l2Word.contains("मोबाईल") })
        assertTrue("Practice prompt must be valid", lesson.practicePrompt.isNotBlank())
    }
}
