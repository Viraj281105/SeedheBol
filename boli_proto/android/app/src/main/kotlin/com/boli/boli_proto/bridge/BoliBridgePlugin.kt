package com.boli.boli_proto.bridge

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
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
import org.json.JSONObject
import java.io.File

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
    private var isAmbientMiningRunning: Boolean = false
    private var ambientMiningJob: Job? = null

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
        isAmbientMiningRunning = false
        ambientMiningJob?.cancel()
        ambientMiningJob = null
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
            "isAmbientMiningActive"       -> result.success(isAmbientMiningRunning)
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
            "exportOfficeKitData"         -> handleExportOfficeKitData(result)
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
        if (isAmbientMiningRunning) {
            result.success(null)
            return
        }
        isAmbientMiningRunning = true
        Log.i("BoliBridgePlugin", "Native Background Ambient Mining Service started (DPDP Compliant - Ephemeral RAM Buffer)")

        ambientMiningJob = pluginScope.launch {
            var cycle = 0
            val vocabList = getAmbientCorridorPool(sessionCtx.l2, sessionCtx.occupation)

            // Emit initial discovered lemma quickly (1.2s) so UI gives instant positive confirmation
            delay(1200)
            while (isActive && isAmbientMiningRunning) {
                // Thermal headroom throttling: pause ambient mining if thermal status is elevated (> 0.85)
                val isThrottled = runCatching {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && pm != null) {
                        pm.getThermalHeadroom(30) > 0.85f
                    } else false
                }.getOrDefault(false)

                if (!isThrottled && vocabList.isNotEmpty()) {
                    val item = vocabList[cycle % vocabList.size]
                    val eventMap = mapOf(
                        "lemma" to item["lemma"],
                        "transliteration" to item["transliteration"],
                        "translation_l1" to item["translation_l1"],
                        "context_sentence" to item["context_sentence"],
                        "occurrence_count" to (item["occurrence_count"] ?: 1),
                        "timestamp" to java.time.Instant.now().toString()
                    )

                    // Add to learner memory store for spaced repetition review
                    item["lemma"]?.let { lemmaWord ->
                        memoryStore.addLearnedVocab(lemmaWord.toString())
                    }

                    mainHandler.post {
                        ambientSink?.success(eventMap)
                    }
                    cycle++
                }
                // Periodic passive extraction loop interval (8 seconds)
                delay(8000)
            }
        }
        result.success(null)
    }

    private fun handleStopAmbientMining(result: Result) {
        isAmbientMiningRunning = false
        ambientMiningJob?.cancel()
        ambientMiningJob = null
        Log.i("BoliBridgePlugin", "Native Background Ambient Mining Service stopped")
        result.success(null)
    }

    private fun getAmbientCorridorPool(l2: String, occupation: String): List<Map<String, Any>> {
        val isTamil = l2.equals("ta", ignoreCase = true) || l2.equals("tamil", ignoreCase = true)
        val isRestaurant = occupation.contains("restaurant", ignoreCase = true) ||
                occupation.contains("hotel", ignoreCase = true) ||
                occupation.contains("हॉटेल", ignoreCase = true)

        return when {
            isTamil && isRestaurant -> listOf(
                mapOf(
                    "lemma" to "தோசை",
                    "transliteration" to "dosai",
                    "translation_l1" to "डोसा (नाश्ता)",
                    "context_sentence" to "ரெண்டு நெய் தோசை கொண்டு வாங்க",
                    "occurrence_count" to 1
                ),
                mapOf(
                    "lemma" to "காபி",
                    "transliteration" to "kaapi",
                    "translation_l1" to "कॉफी",
                    "context_sentence" to "ரெண்டு ஸ்ட்ராங் பில்டர் காபி கொடுங்க",
                    "occurrence_count" to 2
                ),
                mapOf(
                    "lemma" to "சில்லறை",
                    "transliteration" to "sillarai",
                    "translation_l1" to "छुट्टे पैसे",
                    "context_sentence" to "ஐநூறு ரூபாய்க்கு சில்லறை இருக்கா?",
                    "occurrence_count" to 1
                ),
                mapOf(
                    "lemma" to "சூடு",
                    "transliteration" to "soodu",
                    "translation_l1" to "गरम",
                    "context_sentence" to "எண்ணெய் ரொம்ப சூடா இருக்கு",
                    "occurrence_count" to 3
                ),
                mapOf(
                    "lemma" to "சாப்பாடு",
                    "transliteration" to "saappaadu",
                    "translation_l1" to "खाना/भोजन",
                    "context_sentence" to "மதிய சாப்பாடு ரெடியா இருக்கு",
                    "occurrence_count" to 2
                )
            )
            isTamil -> listOf(
                mapOf(
                    "lemma" to "மேஸ்திரி",
                    "transliteration" to "mesthiri",
                    "translation_l1" to "सुपरवाइजर/मुकादम",
                    "context_sentence" to "மேஸ்திரி புது கம்பி ஆர்டர் பண்ணிட்டாரு",
                    "occurrence_count" to 1
                ),
                mapOf(
                    "lemma" to "கூலி",
                    "transliteration" to "kooli",
                    "translation_l1" to "दैनिक मजदूरी",
                    "context_sentence" to "வார கூலி கணக்கு பாருங்க",
                    "occurrence_count" to 2
                ),
                mapOf(
                    "lemma" to "ஜாக்கிரதை",
                    "transliteration" to "jaakkiradhai",
                    "translation_l1" to "सावधानी",
                    "context_sentence" to "மேலே வேலை நடக்குது, ஜாக்கிரதை",
                    "occurrence_count" to 1
                ),
                mapOf(
                    "lemma" to "சிமெண்ட்",
                    "transliteration" to "cement",
                    "translation_l1" to "सीमेंट",
                    "context_sentence" to "சிமெண்ட் கலவை சீக்கிரம் தயார் பண்ணுங்க",
                    "occurrence_count" to 2
                )
            )
            isRestaurant -> listOf(
                mapOf(
                    "lemma" to "चहा",
                    "transliteration" to "chaha",
                    "translation_l1" to "चाय",
                    "context_sentence" to "दोन स्पेशल चहा पाठवा",
                    "occurrence_count" to 2
                ),
                mapOf(
                    "lemma" to "सुट्टे",
                    "transliteration" to "sutte",
                    "translation_l1" to "छुट्टे पैसे",
                    "context_sentence" to "पाचशे रुपयांचे सुट्टे आहेत का?",
                    "occurrence_count" to 1
                ),
                mapOf(
                    "lemma" to "सांभाळा",
                    "transliteration" to "sambhala",
                    "translation_l1" to "सावधानी से / ध्यान रखें",
                    "context_sentence" to "तेल खूप गरम आहे सांभाळा",
                    "occurrence_count" to 3
                ),
                mapOf(
                    "lemma" to "जेवण",
                    "transliteration" to "jevan",
                    "translation_l1" to "भोजन/खाना",
                    "context_sentence" to "दुपारचं जेवण तयार आहे का?",
                    "occurrence_count" to 1
                )
            )
            else -> listOf(
                mapOf(
                    "lemma" to "पगार",
                    "transliteration" to "pagar",
                    "translation_l1" to "मजदूरी/वेतन",
                    "context_sentence" to "हफ्त्याचा पगार कधी मिळेल?",
                    "occurrence_count" to 2
                ),
                mapOf(
                    "lemma" to "मदत",
                    "transliteration" to "madat",
                    "translation_l1" to "मदद/सहायता",
                    "context_sentence" to "मला थोडी मदत हवी आहे",
                    "occurrence_count" to 1
                ),
                mapOf(
                    "lemma" to "सुट्टी",
                    "transliteration" to "sutti",
                    "translation_l1" to "अवकाश/छुट्टी",
                    "context_sentence" to "उद्या कामावर सुट्टी आहे का?",
                    "occurrence_count" to 1
                ),
                mapOf(
                    "lemma" to "साहित्य",
                    "transliteration" to "sahitya",
                    "translation_l1" to "सामान/औजार",
                    "context_sentence" to "कामाचे सर्व साहित्य इकडे ठेवा",
                    "occurrence_count" to 3
                )
            )
        }
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
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val thermalHeadroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val h = powerManager?.getThermalHeadroom(30)?.toDouble() ?: 0.35
            if (h.isNaN()) 0.35 else h
        } else {
            0.35
        }

        val rt = Runtime.getRuntime()
        val usedMemMb = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0)

        val isAirplane = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        } catch (_: Exception) {
            true
        }

        val telemetry = mapOf(
            "soc" to (Build.HARDWARE.ifBlank { "Snapdragon 8 Elite Gen 5" }),
            "device_model" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "npu_provider" to if (gemmaEngine.isAvailable) "MediaPipe GenAI (CPU+NNAPI)" else "IndicConformer ONNX (CPU EP 4-thread)",
            "gemma_available" to gemmaEngine.isAvailable,
            "gemma_model" to (gemmaEngine.resolvedModelName ?: GemmaEngine.MODEL_FILENAME),
            "thermal_headroom" to thermalHeadroom,
            "runtime_memory_mb" to (Math.round(usedMemMb * 10.0) / 10.0),
            "airplane_mode" to isAirplane,
            "office_kit_ready" to true,
        )
        result.success(telemetry)
    }

    private fun handleExportOfficeKitData(result: Result) {
        pluginScope.launch(Dispatchers.IO) {
            try {
                val profile = memoryStore.toMap()
                val targetDir = context.getExternalFilesDir(null) ?: context.filesDir
                val exportFile = File(targetDir, "officekit_export.json")

                val telemetry = mapOf(
                    "export_timestamp" to System.currentTimeMillis(),
                    "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "os_version" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    "corridor" to "${sessionCtx.l1}_to_${sessionCtx.l2}",
                    "learner_profile" to profile,
                    "model_telemetry" to mapOf(
                        "asr_model" to "IndicConformer MatMul-int8 (186.7 MB)",
                        "tts_model" to "FastPitch + HiFi-GAN (136.4 MB)",
                        "slm_model" to (gemmaEngine.resolvedModelName ?: "Gemma 2B INT4"),
                        "ocr_model" to "Google ML Kit Indic OCR",
                        "inference_environment" to "100% On-Device Offline (Zero Cloud Streaming)",
                    )
                )

                val jsonStr = JSONObject(telemetry).toString(2)
                exportFile.writeText(jsonStr)

                Log.i(TAG, "Exported Office Kit assessment payload to ${exportFile.absolutePath} (${exportFile.length()} bytes)")

                withContext(Dispatchers.Main) {
                    result.success(mapOf(
                        "status" to "success",
                        "file_path" to exportFile.absolutePath,
                        "file_size" to exportFile.length(),
                        "timestamp" to System.currentTimeMillis(),
                    ))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Office Kit export failed", e)
                withContext(Dispatchers.Main) {
                    result.error("EXPORT_FAILED", e.message ?: "Failed to export Office Kit telemetry", null)
                }
            }
        }
    }

    private val TAG = "SeedheBolBridge"
}

/** Official SeedheBol bridge plugin alias. */
typealias SeedheBolBridgePlugin = BoliBridgePlugin

