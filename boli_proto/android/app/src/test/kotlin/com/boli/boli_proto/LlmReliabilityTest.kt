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
}
