package com.boli.boli_proto

import org.junit.Assert.*
import org.junit.Test

class WorkplaceKnowledgeStoreTest {

    private val store = WorkplaceKnowledgeStore() // Uses in-memory static corpus

    @Test
    fun testCementStockQueryRetrievesConstructionGroundTruth() {
        val result = store.queryRelevantKnowledge(
            utterance = "सिमेंट संपले",
            domain = "construction",
            language = "mr"
        )

        assertNotNull("Should retrieve verified knowledge for 'सिमेंट संपले'", result)
        assertEquals("construction", result?.domain)
        assertEquals("mr", result?.language)
        assertTrue(
            "Ground truth should contain cement order information",
            result?.groundTruthL2?.contains("सिमेंट") == true
        )
        assertTrue(
            "Should have valid better phrasing",
            result?.betterPhrasing?.isNotBlank() == true
        )
        assertTrue(
            "Should have coaching hint",
            result?.coachingHint?.isNotBlank() == true
        )
    }

    @Test
    fun testHardwareScrewQueryRetrievesHardwareGroundTruth() {
        val result = store.queryRelevantKnowledge(
            utterance = "दोन इंची स्क्रू आणि खिळे",
            domain = "hardware",
            language = "mr"
        )

        assertNotNull("Should retrieve verified hardware knowledge", result)
        assertEquals("hardware", result?.domain)
        assertTrue(
            "Ground truth should contain screw/hardware specifications",
            result?.groundTruthL2?.contains("स्क्रू") == true || result?.groundTruthL2?.contains("खिळे") == true
        )
    }

    @Test
    fun testPlumbingLeakageQueryRetrievesPlumbingGroundTruth() {
        val result = store.queryRelevantKnowledge(
            utterance = "पाणी गळती होत आहे",
            domain = "plumbing",
            language = "mr"
        )

        assertNotNull("Should retrieve verified plumbing knowledge", result)
        assertEquals("plumbing", result?.domain)
        assertTrue(
            "Ground truth should address water leakage or tap valve",
            result?.groundTruthL2?.contains("गळत") == true || result?.groundTruthL2?.contains("व्हॉल्व्ह") == true
        )
    }

    @Test
    fun testHindiCrossCorridorSupport() {
        val result = store.queryRelevantKnowledge(
            utterance = "सीमेंट का स्टॉक खत्म हो गया",
            domain = "construction",
            language = "hi"
        )

        assertNotNull("Should retrieve Hindi construction ground truth", result)
        assertEquals("hi", result?.language)
        assertTrue(
            "Should contain Hindi cement phrase",
            result?.groundTruthL2?.contains("सीमेंट") == true
        )
    }

    @Test
    fun testDomainBaselineFallbackWhenUnknownUtterance() {
        val result = store.queryRelevantKnowledge(
            utterance = "अनाकलनीय शब्द १०२९३८४७५६",
            domain = "electrical",
            language = "mr"
        )

        assertNotNull("Should fall back to domain baseline item for electrical", result)
        assertEquals("electrical", result?.domain)
        assertEquals("mr", result?.language)
    }
}
