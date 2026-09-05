package com.boli.boli_proto

import org.junit.Assert.*
import org.junit.Test

class GemmaRoleplayPromptTest {

    @Test
    fun testRoleplayOpenerPromptStructureAndDirectives() {
        val ctx = GemmaContext(
            l1 = "Hindi",
            l2 = "Marathi",
            occupation = "construction worker",
            userLevel = "beginner"
        )
        val prompt = GemmaPromptBuilder.buildRoleplayOpenerPrompt(
            persona = "Site Supervisor",
            scenario = "Morning shift check-in",
            ctx = ctx,
            scenarioAngle = "Checking raw material stock",
            mood = "साहित्य तपासणी (Stock Check)",
        )

        // Must NOT contain repeating static exemplar that causes model to parrot
        assertFalse("Prompt must not include static repeating exemplar", prompt.contains("अरे भावा, आजचे काम वेळेवर सुरू झाले का?"))
        // Must contain dynamic mood and scenario angle
        assertTrue("Prompt must include mood", prompt.contains("साहित्य तपासणी"))
        assertTrue("Prompt must include topic angle", prompt.contains("Checking raw material stock"))
        // Must contain strict single sentence directive
        assertTrue("Prompt must restrict length to 1 sentence", prompt.contains("EXACTLY ONE short spoken sentence"))
        // Must enforce language separation
        assertTrue("Prompt must enforce authentic target language", prompt.contains("Output ONLY authentic Marathi in L2"))
    }

    @Test
    fun testRoleplayNextTurnPromptContainsStructuredTags() {
        val ctx = GemmaContext(
            l1 = "Hindi",
            l2 = "Marathi",
            occupation = "construction worker",
            userLevel = "beginner"
        )
        val history = listOf(
            DialogueTurn("bot", "सिमेंट आणि विटांचा साठा पुरेसा आहे का?"),
            DialogueTurn("user", "होय साहेब, सुरू झाले आहे.")
        )
        val prompt = GemmaPromptBuilder.buildRoleplayNextTurnPrompt(history, ctx, turnNumber = 2, maxTurns = 5)

        // Must include required tags
        assertTrue("Prompt must require L2 tag", prompt.contains("L2:"))
        assertTrue("Prompt must require L1 tag", prompt.contains("L1:"))
        assertTrue("Prompt must require FLUENCY tag", prompt.contains("FLUENCY:"))
        assertTrue("Prompt must require BETTER tag", prompt.contains("BETTER:"))
        assertTrue("Prompt must require FEEDBACK tag", prompt.contains("FEEDBACK:"))
        assertTrue("Prompt must require HINT tag", prompt.contains("HINT:"))
        // Must contain history
        assertTrue("Prompt must contain recent user utterance", prompt.contains("होय साहेब, सुरू झाले आहे."))
    }

    @Test
    fun testSanitizerDetectsAndDeduplicatesIntraSentenceRepetition() {
        val deviceGlitchText = "आजो, आपका नाम क्या है? और आपका नाम क्या है?"

        assertTrue(
            "Sanitizer must detect intra-sentence clause repetition",
            LlmOutputSanitizer.hasDuplicateClauses(deviceGlitchText)
        )
        assertTrue(
            "Sanitizer must treat intra-sentence loop as degenerative repetition",
            LlmOutputSanitizer.hasDegenerativeRepetition(deviceGlitchText)
        )

        val deduplicated = LlmOutputSanitizer.deduplicateClauses(deviceGlitchText)
        assertFalse(
            "Deduplicated text must not have duplicate clauses",
            LlmOutputSanitizer.hasDuplicateClauses(deduplicated)
        )
        assertTrue(
            "Deduplicated text must retain single clause",
            deduplicated.contains("आपका नाम क्या है?")
        )
    }

    @Test
    fun testSanitizerStripsBracketPlaceholders() {
        val deviceGlitchL1 = "मराठी में आपका नाम '[your name]' है और आपका काम '[job]' है"
        val stripped = LlmOutputSanitizer.stripPlaceholders(deviceGlitchL1)

        assertFalse("Placeholders '[your name]' must be stripped", stripped.contains("[your name]"))
        assertFalse("Placeholders '[job]' must be stripped", stripped.contains("[job]"))
        assertFalse("Quotes should be stripped", stripped.contains("'"))
        assertEquals("मराठी में आपका नाम है और आपका काम है", stripped)
    }

    @Test
    fun testSanitizerRejectsHindiIntrusionWhenTargetIsMarathi() {
        val pureHindiSentence = "आजो, आपका नाम क्या है?"
        val authenticMarathiSentence = "अरे भावा, आजचे काम कसे चालले आहे?"
        val marathiWithQuestion = "पाणी कुठे मिळेल साहेब?"

        // Hindi intrusion check
        assertTrue(
            "Hindi sentence must be flagged as Hindi intrusion for Marathi",
            LlmOutputSanitizer.isHindiIntrusionForMarathi(pureHindiSentence)
        )
        assertFalse(
            "Authentic Marathi sentence must NOT be flagged as Hindi intrusion",
            LlmOutputSanitizer.isHindiIntrusionForMarathi(authenticMarathiSentence)
        )
        assertFalse(
            "Marathi inquiry must NOT be flagged as Hindi intrusion",
            LlmOutputSanitizer.isHindiIntrusionForMarathi(marathiWithQuestion)
        )

        // isValidL2Output checks
        assertFalse(
            "Pure Hindi sentence must be REJECTED when target is Marathi (mr)",
            LlmOutputSanitizer.isValidL2Output(pureHindiSentence, "mr")
        )
        assertTrue(
            "Pure Hindi sentence is VALID when target is Hindi (hi)",
            LlmOutputSanitizer.isValidL2Output(pureHindiSentence, "hi")
        )
        assertTrue(
            "Authentic Marathi sentence must PASS for Marathi (mr)",
            LlmOutputSanitizer.isValidL2Output(authenticMarathiSentence, "mr")
        )
    }

    @Test
    fun testTamilTeluguFewShotPromptsAreWellFormed() {
        val tamilCtx = GemmaContext(l1 = "Hindi", l2 = "Tamil", occupation = "delivery partner", userLevel = "beginner")
        val tamilPrompt = GemmaPromptBuilder.buildRoleplayOpenerPrompt("Customer", "Delivery address", tamilCtx)
        assertTrue("Tamil opener prompt must specify Tamil target", tamilPrompt.contains("Tamil"))
        assertTrue("Tamil opener prompt must enforce authentic Tamil", tamilPrompt.contains("Output ONLY authentic Tamil in L2"))

        val teluguCtx = GemmaContext(l1 = "Hindi", l2 = "Telugu", occupation = "delivery partner", userLevel = "beginner")
        val teluguPrompt = GemmaPromptBuilder.buildRoleplayOpenerPrompt("Customer", "Delivery address", teluguCtx)
        assertTrue("Telugu opener prompt must specify Telugu target", teluguPrompt.contains("Telugu"))
        assertTrue("Telugu opener prompt must enforce authentic Telugu", teluguPrompt.contains("Output ONLY authentic Telugu in L2"))
    }

    @Test
    fun testTurnProgressionAndFluencyPrompt() {
        val ctx = GemmaContext(l1 = "Hindi", l2 = "Marathi", occupation = "construction worker", userLevel = "beginner")
        val history = listOf(
            DialogueTurn("bot", "सिमेंट आणि विटांचा साठा पुरेसा आहे का?"),
            DialogueTurn("user", "होय साहेब, सुरू झाले आहे.")
        )

        // Turn 3 of 5 prompt
        val turn3Prompt = GemmaPromptBuilder.buildRoleplayNextTurnPrompt(history, ctx, turnNumber = 3, maxTurns = 5)
        assertTrue("Must include turn progression marker", turn3Prompt.contains("SESSION PROGRESS: Turn 3 of 5"))
        assertTrue("Must request FLUENCY score tag", turn3Prompt.contains("FLUENCY:"))
        assertTrue("Must mandate direct reply to learner", turn3Prompt.contains("Respond directly to what the Learner said"))

        // Final turn prompt (Turn 5 of 5)
        val finalTurnPrompt = GemmaPromptBuilder.buildRoleplayNextTurnPrompt(history, ctx, turnNumber = 5, maxTurns = 5)
        assertTrue("Final turn must instruct model to conclude naturally", finalTurnPrompt.contains("FINAL TURN: Acknowledge what the learner said and conclude the conversation naturally"))
    }

    @Test
    fun testCorruptedGlitchStringRejected() {
        val glitch = "आप लगेगा लगेगा मान्य"
        val isRepetitive = LlmOutputSanitizer.hasDegenerativeRepetition(glitch)
        assertTrue("Glitch with consecutive word repetition 'लगेगा लगेगा' must be flagged as repetitive", isRepetitive)

        val isValid = LlmOutputSanitizer.isValidL2Output(glitch, "Marathi")
        assertFalse("Glitch string must be rejected as invalid L2 for Marathi", isValid)

        val isHindi = LlmOutputSanitizer.isHindiIntrusionForMarathi(glitch)
        assertTrue("Glitch string with Hindi markers must be detected as Hindi intrusion", isHindi)
    }

    @Test
    fun testNextTurnPromptStructure() {
        val ctx = GemmaContext(l1 = "Hindi", l2 = "Marathi", occupation = "shop assistant", userLevel = "beginner")
        val history = listOf(
            DialogueTurn("bot", "काय हवे आहे तुम्हाला?"),
            DialogueTurn("user", "मला चहा पाहिजे.")
        )
        val prompt = GemmaPromptBuilder.buildRoleplayNextTurnPrompt(history, ctx, turnNumber = 2, maxTurns = 5)
        assertTrue("Prompt must require strict format without markdown", prompt.contains("Strict format"))
        assertTrue("Prompt must specify L2 tag", prompt.contains("L2:"))
        assertTrue("Prompt must specify FLUENCY tag", prompt.contains("FLUENCY:"))
    }

    @Test
    fun testParrotOrRepetitionDetection() {
        val history = listOf(
            DialogueTurn("bot", "कामाची अवजारे आणि मशिन व्यवस्थित चालू आहेत का, काही बिघाड आहे?"),
            DialogueTurn("user", "नाही काही बिघाड नाही आहे")
        )

        // 1. Repeating previous bot question must be rejected
        val selfRepeat = "कामाची अवजारे आणि मशिन व्यवस्थित चालू आहेत का, काही बिघाड आहे"
        assertTrue(
            "Echo of previous question must be flagged as repetition",
            BoliAiLayer.isParrotOrRepetition(selfRepeat, "नाही काही बिघाड नाही आहे", history)
        )

        // 2. Parroting user's words must be rejected
        val userParrot = "आणली आहे मडली आहे"
        assertTrue(
            "Parroting user words must be flagged as repetition",
            BoliAiLayer.isParrotOrRepetition(userParrot, "हो मडली आहे", history)
        )

        // 3. Genuine conversational reply must be accepted
        val goodResponse = "छान, मग काम सुरू करा आणि काळजी घ्या."
        assertFalse(
            "Natural progression must NOT be flagged as repetition",
            BoliAiLayer.isParrotOrRepetition(goodResponse, "नाही काही बिघाड नाही आहे", history)
        )
    }
}
