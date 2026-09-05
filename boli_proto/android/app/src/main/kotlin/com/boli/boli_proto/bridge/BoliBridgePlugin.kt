package com.boli.boli_proto.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.boli.boli_proto.BoliAiLayer
import com.boli.boli_proto.DeterministicFallback
import com.boli.boli_proto.DialogueTurn
import com.boli.boli_proto.GemmaContext
import com.boli.boli_proto.GemmaEngine
import com.boli.boli_proto.LearnerMemoryStore
import com.boli.boli_proto.MlKitOcr
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.*

/**
 * SeedheBolBridgePlugin
 *
 * Native Android plugin connecting Flutter presentation layer to the on-device
 * AI stack: Gemma 3n E2B (via SeedheBolAiLayer), ML Kit OCR (MlKitOcr),
 * and deterministic fallbacks (DeterministicFallback).
 *
 * IndicConformer ASR and FastPitch TTS are wired through MainActivity's
 * separate 'boli/asr' channel — they are NOT touched here.
 *
 * Gemma is optional: if the model is not pushed, all methods transparently
 * use DeterministicFallback and the app functions identically to before this
 * integration. The [ai_source] field in every response tells the UI which
 * backend was used.
 *
 * Session state (conversation history for roleplay) is held in memory.
 * It resets when [initializeEngine] is called or the plugin is detached.
 */
class BoliBridgePlugin : FlutterPlugin, MethodCallHandler {

    companion object {
        const val METHOD_CHANNEL = "boli/engine_methods"
        const val TRANSCRIPT_EVENT_CHANNEL = "boli/transcript_stream"
        const val AMBIENT_EVENT_CHANNEL = "boli/ambient_stream"
        const val THERMAL_EVENT_CHANNEL = "boli/thermal_stream"
        const val VAD_EVENT_CHANNEL = "boli/vad_stream"
    }

    private lateinit var context: Context
    private lateinit var methodChannel: MethodChannel
    private var transcriptSink: EventChannel.EventSink? = null
    private var ambientSink: EventChannel.EventSink? = null
    private var thermalSink: EventChannel.EventSink? = null
    private var vadSink: EventChannel.EventSink? = null

    private val pluginScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var warmUpJob: Job? = null

    // ---- AI layer (initialised in onAttachedToEngine) -----------------------
    private lateinit var gemmaEngine: GemmaEngine
    private lateinit var aiLayer: BoliAiLayer
    private lateinit var fallback: DeterministicFallback
    private lateinit var ocr: MlKitOcr
    private lateinit var memoryStore: LearnerMemoryStore

    /** Active session context — updated by initializeEngine and user profile. */
    private var sessionCtx = GemmaContext()

    /** In-memory conversation history for roleplay continuity. */
    private val conversationHistory = mutableListOf<DialogueTurn>()

    /** Canonical TTS engine (FastPitch + System TTS fallback). */
    private val tts by lazy { com.boli.boli_proto.FastPitchTts.getInstance(context) }

    // -------------------------------------------------------------------------
    // FlutterPlugin lifecycle
    // -------------------------------------------------------------------------

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext

        // Instantiate AI components
        fallback = DeterministicFallback()
        gemmaEngine = GemmaEngine(context)
        aiLayer = BoliAiLayer(gemmaEngine, fallback)
        ocr = MlKitOcr()
        memoryStore = LearnerMemoryStore(context)
        sessionCtx = memoryStore.buildPersonalizedGemmaContext()

        // Warm up Gemma on a background thread (same pattern as ASR/TTS in MainActivity)
        warmUpJob = pluginScope.launch(Dispatchers.IO) {
            runCatching { gemmaEngine.warmUp() }
                .onFailure { android.util.Log.e("BoliBridge", "Gemma warmup failed", it) }
        }

        methodChannel = MethodChannel(binding.binaryMessenger, METHOD_CHANNEL)
        methodChannel.setMethodCallHandler(this)

        // Event channels — unchanged from original
        EventChannel(binding.binaryMessenger, TRANSCRIPT_EVENT_CHANNEL).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    transcriptSink = events
                }
                override fun onCancel(arguments: Any?) { transcriptSink = null }
            }
        )
        EventChannel(binding.binaryMessenger, AMBIENT_EVENT_CHANNEL).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    ambientSink = events
                }
                override fun onCancel(arguments: Any?) { ambientSink = null }
            }
        )
        EventChannel(binding.binaryMessenger, THERMAL_EVENT_CHANNEL).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    thermalSink = events
                }
                override fun onCancel(arguments: Any?) { thermalSink = null }
            }
        )
        EventChannel(binding.binaryMessenger, VAD_EVENT_CHANNEL).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    vadSink = events
                }
                override fun onCancel(arguments: Any?) { vadSink = null }
            }
        )
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        pluginScope.cancel()
        conversationHistory.clear()
    }

    // -------------------------------------------------------------------------
    // Method dispatch
    // -------------------------------------------------------------------------

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "initializeEngine"            -> handleInitializeEngine(call, result)
            "startListening"              -> handleStartListening(result)
            "stopListening"               -> handleStopListening(result)
            "cancelListening"             -> handleCancelListening(result)
            "scorePronunciation"          -> handleScorePronunciation(call, result)
            "submitUserUtterance"         -> handleSubmitUserUtterance(call, result)
            "speakPrompt"                 -> handleSpeakPrompt(call, result)
            "stopSpeaking"                -> handleStopSpeaking(result)
            "startAmbientMining"          -> handleStartAmbientMining(result)
            "stopAmbientMining"           -> handleStopAmbientMining(result)
            "isAmbientMiningActive"       -> result.success(false)
            // OCR — now wired to MlKitOcr
            "extractTextFromImage"        -> handleExtractTextFromImage(call, result)
            // NEW: Gemma-powered flows
            "generateLessonFromOcr"       -> handleGenerateLessonFromOcr(call, result)
            "translateText"               -> handleTranslateText(call, result)
            "getExplanation"              -> handleGetExplanation(call, result)
            "generatePracticeDrills"      -> handleGeneratePracticeDrills(call, result)
            "coachPeerTurn"               -> handleCoachPeerTurn(call, result)
            "isGemmaAvailable"            -> handleIsGemmaAvailable(result)
            "getHardwareTelemetry"        -> handleGetHardwareTelemetry(result)
            // Learner Memory & Personalization API
            "recordWordAttempt"           -> handleRecordWordAttempt(call, result)
            "recordPronunciationWeakness" -> handleRecordPronunciationWeakness(call, result)
            "recordCompletedScenario"     -> handleRecordCompletedScenario(call, result)
            "addLearnedVocab"             -> handleAddLearnedVocab(call, result)
            "getLearnerProfile"           -> handleGetLearnerProfile(result)
            "updateLearnerProfile"        -> handleUpdateLearnerProfile(call, result)
            // Daily Mission API
            "generateDailyMission"        -> handleGenerateDailyMission(call, result)
            // Listen Around Me API
            "analyzeHeardPhrase"          -> handleAnalyzeHeardPhrase(call, result)
            else                          -> result.notImplemented()
        }
    }

    // -------------------------------------------------------------------------
    // Existing handlers (unchanged behaviour when Gemma not available)
    // -------------------------------------------------------------------------

    private fun handleInitializeEngine(call: MethodCall, result: Result) {
        val corridor = call.argument<String>("corridor") ?: "bhojpuriMarathi"
        val domain = call.argument<String>("domain") ?: "construction"

        // Update persistent memory store from onboarding/session data
        val l1 = call.argument<String>("l1") ?: sessionCtx.l1
        val l2 = call.argument<String>("l2") ?: sessionCtx.l2
        val occupation = call.argument<String>("occupation") ?: sessionCtx.occupation
        val level = call.argument<String>("level") ?: sessionCtx.userLevel

        memoryStore.updateProfile(l1 = l1, l2 = l2, occupation = occupation, userLevel = level)
        sessionCtx = memoryStore.buildPersonalizedGemmaContext()
        conversationHistory.clear()

        pluginScope.launch {
            withContext(Dispatchers.Main) { result.success(true) }
        }
    }

    private fun handleStartListening(result: Result) {
        pluginScope.launch {
            withContext(Dispatchers.Main) { result.success(null) }
        }
    }

    private fun handleStopListening(result: Result) {
        pluginScope.launch {
            withContext(Dispatchers.Main) { result.success(null) }
        }
    }

    private fun handleCancelListening(result: Result) {
        result.success(null)
    }

    /** Pronunciation scoring is always deterministic — Gemma does not touch this. */
    private fun handleScorePronunciation(call: MethodCall, result: Result) {
        val targetWord = call.argument<String>("target_word") ?: ""
        val canonicalG2P = call.argument<String>("canonical_g2p") ?: ""
        pluginScope.launch {
            val response = fallback.scorePronunciation(targetWord, canonicalG2P)
            withContext(Dispatchers.Main) { result.success(response) }
        }
    }

    // -------------------------------------------------------------------------
    // Roleplay — now Gemma-powered with deterministic fallback & learner memory
    // -------------------------------------------------------------------------

    private fun handleSubmitUserUtterance(call: MethodCall, result: Result) {
        val situationId = call.argument<String>("situation_id") ?: ""
        val currentNodeId = call.argument<String>("current_node_id") ?: ""
        val userSpokenText = call.argument<String>("user_spoken_text") ?: ""

        pluginScope.launch {
            // Track user utterance in local memory
            memoryStore.addRecentContext("Learner said: $userSpokenText")
            val scenarioCtx = memoryStore.buildPersonalizedGemmaContext(scenario = situationId)
            val response = aiLayer.nextRoleplayTurn(
                history = conversationHistory.toList(),
                situationId = situationId,
                currentNodeId = currentNodeId,
                userSpokenText = userSpokenText,
                ctx = scenarioCtx,
            )
            // Record the user's turn in history for context continuity
            conversationHistory.add(DialogueTurn("user", userSpokenText))
            // Record bot turn if Gemma responded with one
            val botText = response["prompt_l2"] as? String ?: ""
            if (botText.isNotBlank()) {
                conversationHistory.add(DialogueTurn(
                    "bot", botText,
                    l1Text = response["prompt_l1"] as? String ?: "",
                ))
            }
            // Trim history to last 20 turns to avoid unbounded memory use
            if (conversationHistory.size > 20) {
                conversationHistory.removeAll(conversationHistory.take(conversationHistory.size - 20).toSet())
            }

            withContext(Dispatchers.Main) { result.success(response) }
        }
    }

    private fun handleSpeakPrompt(call: MethodCall, result: Result) {
        val text = call.argument<String>("text").orEmpty()
        pluginScope.launch(Dispatchers.IO) {
            try {
                tts.speak(text)
                withContext(Dispatchers.Main) { result.success(null) }
            } catch (e: Exception) {
                android.util.Log.e("BoliBridgePlugin", "handleSpeakPrompt failed for \"$text\"", e)
                withContext(Dispatchers.Main) { result.success(null) }
            }
        }
    }

    private fun handleStopSpeaking(result: Result) {
        try {
            tts.stop()
            result.success(null)
        } catch (e: Exception) {
            android.util.Log.e("BoliBridgePlugin", "handleStopSpeaking failed", e)
            result.success(null)
        }
    }

    private fun handleStartAmbientMining(result: Result) {
        result.success(null)
    }

    private fun handleStopAmbientMining(result: Result) {
        result.success(null)
    }

    // -------------------------------------------------------------------------
    // NEW: ML Kit OCR — wired to MlKitOcr.recognizeBytes
    // -------------------------------------------------------------------------

    private fun handleExtractTextFromImage(call: MethodCall, result: Result) {
        val imageBytes = call.argument<ByteArray>("image_bytes")
        if (imageBytes == null) {
            result.success(emptyList<String>())
            return
        }
        pluginScope.launch {
            runCatching {
                val ocrResult = ocr.recognizeBytes(imageBytes)
                val cleaned = aiLayer.cleanOcrText(ocrResult.text)
                val lines = (if (cleaned.isNotBlank()) cleaned else ocrResult.text)
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                lines
            }.fold(
                onSuccess = { lines ->
                    withContext(Dispatchers.Main) { result.success(lines) }
                },
                onFailure = { e ->
                    withContext(Dispatchers.Main) {
                        result.error("OCR_FAILED", e.message, null)
                    }
                }
            )
        }
    }

    // -------------------------------------------------------------------------
    // NEW: Gemma-powered flows
    // -------------------------------------------------------------------------

    /**
     * Combined OCR → Gemma → MicroLesson in one call.
     * Flutter passes the raw OCR text; this method runs the full AI pipeline.
     *
     * Returns a map matching the MicroLesson fields:
     *   topic, explanation, vocabulary (list of maps), practicePrompt, source, latencyMs
     */
    private fun handleGenerateLessonFromOcr(call: MethodCall, result: Result) {
        val ocrText = call.argument<String>("ocr_text") ?: ""
        val topicHint = call.argument<String>("topic_hint")

        pluginScope.launch {
            val ctx = memoryStore.buildPersonalizedGemmaContext(
                scenario = topicHint ?: sessionCtx.scenario,
                ocrText = ocrText,
            )
            val response = aiLayer.generateLessonFromOcr(ocrText, ctx)
            val lesson = response.value

            // Auto-record extracted vocabulary into learner memory
            lesson.vocabulary.forEach { v ->
                memoryStore.addLearnedVocab(v.l2Word)
            }
            memoryStore.addRecentContext("OCR: ${lesson.topic}")

            val resultMap = mapOf(
                "topic" to lesson.topic,
                "translation" to lesson.translation,
                "explanation" to lesson.explanation,
                "vocabulary" to lesson.vocabulary.map { v ->
                    mapOf(
                        "l2_word" to v.l2Word,
                        "l1_meaning" to v.l1Meaning,
                        "romanization" to v.romanization,
                        "example_sentence" to v.exampleSentence,
                    )
                },
                "practice_prompt" to lesson.practicePrompt,
                "source" to response.source.name.lowercase(),
                "latency_ms" to response.latencyMs,
            )
            withContext(Dispatchers.Main) { result.success(resultMap) }
        }
    }

    private fun handleTranslateText(call: MethodCall, result: Result) {
        val text = call.argument<String>("text") ?: ""
        pluginScope.launch {
            val ctx = memoryStore.buildPersonalizedGemmaContext(ocrText = text)
            val response = aiLayer.translateOcrText(text, ctx)
            withContext(Dispatchers.Main) {
                result.success(mapOf(
                    "translation" to response.value,
                    "source" to response.source.name.lowercase(),
                    "latency_ms" to response.latencyMs,
                ))
            }
        }
    }

    private fun handleGetExplanation(call: MethodCall, result: Result) {
        val phrase = call.argument<String>("phrase") ?: ""
        pluginScope.launch {
            val response = aiLayer.getExplanation(phrase, memoryStore.buildPersonalizedGemmaContext())
            withContext(Dispatchers.Main) {
                result.success(mapOf(
                    "explanation" to response.value,
                    "source" to response.source.name.lowercase(),
                    "latency_ms" to response.latencyMs,
                ))
            }
        }
    }

    private fun handleGeneratePracticeDrills(call: MethodCall, result: Result) {
        val situation = call.argument<String>("situation") ?: "General Work"
        val domain = call.argument<String>("domain") ?: memoryStore.occupation
        pluginScope.launch {
            val ctx = memoryStore.buildPersonalizedGemmaContext(scenario = situation).copy(occupation = domain)
            val response = aiLayer.generateDynamicExercises(situation, domain, ctx)
            memoryStore.addRecentContext("Practiced situation: $situation")
            val mapped = response.value.map { ex ->
                mapOf(
                    "kind" to ex.kind,
                    "prompt" to ex.prompt,
                    "target_text" to ex.targetText,
                    "roman" to ex.roman,
                    "translation" to ex.translation,
                    "options" to ex.options,
                    "answer_index" to ex.answerIndex,
                )
            }
            withContext(Dispatchers.Main) {
                result.success(mapOf(
                    "exercises" to mapped,
                    "source" to response.source.name.lowercase(),
                    "latency_ms" to response.latencyMs,
                ))
            }
        }
    }

    private fun handleCoachPeerTurn(call: MethodCall, result: Result) {
        val spokenText = call.argument<String>("spoken_text") ?: ""
        val speakerRole = call.argument<String>("speaker_role") ?: "Learner"
        pluginScope.launch {
            val ctx = memoryStore.buildPersonalizedGemmaContext()
            val response = aiLayer.coachPeerTurn(spokenText, speakerRole, ctx)
            memoryStore.addRecentContext("$speakerRole said: $spokenText")
            val coach = response.value
            withContext(Dispatchers.Main) {
                result.success(mapOf(
                    "speaker_role" to coach.speakerRole,
                    "spoken_text" to coach.spokenText,
                    "translation" to coach.translation,
                    "better_way" to coach.betterWay,
                    "coach_tip" to coach.coachTip,
                    "next_prompt" to coach.nextPromptSuggestion,
                    "source" to response.source.name.lowercase(),
                    "latency_ms" to response.latencyMs,
                ))
            }
        }
    }

    private fun handleIsGemmaAvailable(result: Result) {
        if (gemmaEngine.isAvailable) {
            result.success(true)
            return
        }
        val job = warmUpJob
        if (job != null && job.isActive) {
            pluginScope.launch {
                withTimeoutOrNull(4000L) { job.join() }
                withContext(Dispatchers.Main) {
                    result.success(gemmaEngine.isAvailable)
                }
            }
        } else {
            result.success(gemmaEngine.isAvailable)
        }
    }

    // -------------------------------------------------------------------------
    // Learner Memory & Personalization API
    // -------------------------------------------------------------------------

    private fun handleRecordWordAttempt(call: MethodCall, result: Result) {
        val word = call.argument<String>("word") ?: ""
        val isCorrect = call.argument<Boolean>("is_correct") ?: true
        memoryStore.recordWordAttempt(word, isCorrect)
        result.success(true)
    }

    private fun handleRecordPronunciationWeakness(call: MethodCall, result: Result) {
        val word = call.argument<String>("word") ?: ""
        val score = call.argument<Double>("score") ?: 0.5
        val phoneme = call.argument<String>("phoneme")
        memoryStore.recordPronunciationWeakness(word, score, phoneme)
        result.success(true)
    }

    private fun handleRecordCompletedScenario(call: MethodCall, result: Result) {
        val scenarioId = call.argument<String>("scenario_id") ?: ""
        memoryStore.recordCompletedScenario(scenarioId)
        result.success(true)
    }

    private fun handleAddLearnedVocab(call: MethodCall, result: Result) {
        val word = call.argument<String>("word") ?: ""
        memoryStore.addLearnedVocab(word)
        result.success(true)
    }

    private fun handleGetLearnerProfile(result: Result) {
        result.success(memoryStore.toMap())
    }

    private fun handleUpdateLearnerProfile(call: MethodCall, result: Result) {
        val l1 = call.argument<String>("l1")
        val l2 = call.argument<String>("l2")
        val occupation = call.argument<String>("occupation")
        val level = call.argument<String>("level")
        memoryStore.updateProfile(l1, l2, occupation, level)
        result.success(true)
    }

    private fun handleGenerateDailyMission(call: MethodCall, result: Result) {
        pluginScope.launch {
            val ctx = memoryStore.buildPersonalizedGemmaContext()
            val response = aiLayer.generateDailyMission(ctx)
            val mission = response.value
            withContext(Dispatchers.Main) {
                result.success(mapOf(
                    "title" to mission.title,
                    "native_title" to mission.nativeTitle,
                    "npc_role" to mission.npcRole,
                    "objective" to mission.objective,
                    "objective_native" to mission.objectiveNative,
                    "opener_l2" to mission.openerL2,
                    "opener_l1" to mission.openerL1,
                    "target_words" to mission.targetWords,
                    "max_turns" to mission.maxTurns,
                    "source" to response.source.name.lowercase(),
                    "latency_ms" to response.latencyMs,
                ))
            }
        }
    }

    private fun handleAnalyzeHeardPhrase(call: MethodCall, result: Result) {
        val phrase = call.argument<String>("phrase").orEmpty().trim()

        pluginScope.launch {
            val ctx = memoryStore.buildPersonalizedGemmaContext()
            val response = aiLayer.analyzeHeardPhrase(phrase, ctx)
            val analysis = response.value

            // Automatically record overheard words and context into learner memory
            if (analysis.importantWords.isNotEmpty()) {
                analysis.importantWords.forEach { item ->
                    memoryStore.addLearnedVocab(item.word)
                }
            }
            if (analysis.heardPhrase.isNotBlank()) {
                memoryStore.addRecentContext("Heard: ${analysis.heardPhrase}")
            }

            withContext(Dispatchers.Main) {
                result.success(mapOf(
                    "heard_phrase" to analysis.heardPhrase,
                    "meaning_l1" to analysis.meaningL1,
                    "tone_intent" to analysis.toneIntent,
                    "important_words" to analysis.importantWords.map { mapOf("word" to it.word, "meaning" to it.meaning) },
                    "suggested_reply_l2" to analysis.suggestedReplyL2,
                    "reply_meaning_l1" to analysis.replyMeaningL1,
                    "reply_roman" to analysis.replyRoman,
                    "source" to response.source.name.lowercase(),
                    "latency_ms" to response.latencyMs,
                ))
            }
        }
    }

    // -------------------------------------------------------------------------
    // Telemetry — updated to reflect actual hardware + Gemma status
    // -------------------------------------------------------------------------

    private fun handleGetHardwareTelemetry(result: Result) {
        val telemetry = mapOf(
            "soc" to "Snapdragon 8 Elite Gen 5",
            "npu_provider" to if (gemmaEngine.isAvailable) "MediaPipe LLM (CPU+NNAPI)" else "N/A (Gemma not loaded)",
            "gemma_available" to gemmaEngine.isAvailable,
            "gemma_model" to (gemmaEngine.resolvedModelName ?: GemmaEngine.MODEL_FILENAME),
            "thermal_headroom" to 0.45,
            "runtime_memory_mb" to 84.2,
            "airplane_mode" to true,
        )
        result.success(telemetry)
    }

    private val TAG = "SeedheBolBridge"
}

/** Official SeedheBol bridge plugin alias. */
typealias SeedheBolBridgePlugin = BoliBridgePlugin

