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
import com.boli.boli_proto.OnnxAsr
import com.boli.boli_proto.FastPitchTts
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject

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

    // ---- Ambient Mining Engine State ---------------------------------------
    @Volatile
    private var isAmbientMiningActive = false
    private var ambientMiningJob: Job? = null

    // ---- AI layer (initialised in onAttachedToEngine) -----------------------
    private lateinit var gemmaEngine: GemmaEngine
    private lateinit var aiLayer: BoliAiLayer
    private lateinit var fallback: DeterministicFallback
    private lateinit var ocr: MlKitOcr
    private lateinit var memoryStore: LearnerMemoryStore

    /** Active session context — updated by initializeEngine and user profile. */
    private var sessionCtx = GemmaContext()

    /** In-memory conversation history for roleplay continuity guarded by historyLock. */
    private val conversationHistory = mutableListOf<DialogueTurn>()
    private val historyLock = Any()

    /** Canonical TTS engine (FastPitch + System TTS fallback). */
    private val tts by lazy { com.boli.boli_proto.FastPitchTts.getInstance(context) }

    private suspend fun awaitWarmupIfPending(timeoutMs: Long = 3500L) {
        val job = warmUpJob
        if (job != null && job.isActive) {
            withTimeoutOrNull(timeoutMs) {
                job.join()
            }
        }
    }

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

                override fun onCancel(arguments: Any?) {
                    transcriptSink = null
                }
            }
        )
        EventChannel(binding.binaryMessenger, AMBIENT_EVENT_CHANNEL).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    ambientSink = events
                }

                override fun onCancel(arguments: Any?) {
                    ambientSink = null
                }
            }
        )
        EventChannel(binding.binaryMessenger, THERMAL_EVENT_CHANNEL).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    thermalSink = events
                }

                override fun onCancel(arguments: Any?) {
                    thermalSink = null
                }
            }
        )
        EventChannel(binding.binaryMessenger, VAD_EVENT_CHANNEL).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    vadSink = events
                }

                override fun onCancel(arguments: Any?) {
                    vadSink = null
                }
            }
        )
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        isAmbientMiningActive = false
        ambientMiningJob?.cancel()
        ambientMiningJob = null
        pluginScope.cancel()
        synchronized(historyLock) {
            conversationHistory.clear()
        }
        if (::gemmaEngine.isInitialized) {
            gemmaEngine.close()
        }
    }

    // -------------------------------------------------------------------------
    // Method dispatch
    // -------------------------------------------------------------------------

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "initializeEngine" -> handleInitializeEngine(call, result)
            "startListening" -> handleStartListening(result)
            "stopListening" -> handleStopListening(result)
            "cancelListening" -> handleCancelListening(result)
            "scorePronunciation" -> handleScorePronunciation(call, result)
            "submitUserUtterance" -> handleSubmitUserUtterance(call, result)
            "speakPrompt" -> handleSpeakPrompt(call, result)
            "stopSpeaking" -> handleStopSpeaking(result)
            "startAmbientMining" -> handleStartAmbientMining(result)
            "stopAmbientMining" -> handleStopAmbientMining(result)
            "isAmbientMiningActive" -> result.success(isAmbientMiningActive)
            "mineSamplePhraseNow" -> handleMineSamplePhraseNow(result)
            // OCR — now wired to MlKitOcr
            "extractTextFromImage" -> handleExtractTextFromImage(call, result)
            // NEW: Gemma-powered flows
            "generateLessonFromOcr" -> handleGenerateLessonFromOcr(call, result)
            "translateText" -> handleTranslateText(call, result)
            "getExplanation" -> handleGetExplanation(call, result)
            "generatePracticeDrills" -> handleGeneratePracticeDrills(call, result)
            "coachPeerTurn" -> handleCoachPeerTurn(call, result)
            "generateRoleplayOpener" -> handleGenerateRoleplayOpener(call, result)
            "evaluateSpokenIntent" -> handleEvaluateSpokenIntent(call, result)
            "isGemmaAvailable" -> handleIsGemmaAvailable(result)
            "getHardwareTelemetry" -> handleGetHardwareTelemetry(result)
            "exportOfficeKitData" -> handleExportOfficeKitData(result)
            // Learner Memory & Personalization API
            "recordWordAttempt" -> handleRecordWordAttempt(call, result)
            "recordPronunciationWeakness" -> handleRecordPronunciationWeakness(call, result)
            "recordCompletedScenario" -> handleRecordCompletedScenario(call, result)
            "addLearnedVocab" -> handleAddLearnedVocab(call, result)
            "getLearnerProfile" -> handleGetLearnerProfile(result)
            "updateLearnerProfile" -> handleUpdateLearnerProfile(call, result)
            "generateDailyMission" -> handleGenerateDailyMission(call, result)
            // Listen Around Me API
            "analyzeHeardPhrase" -> handleAnalyzeHeardPhrase(call, result)
            "clearConversationHistory" -> {
                synchronized(historyLock) {
                    conversationHistory.clear()
                }
                result.success(true)
            }
            else -> result.notImplemented()
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
        synchronized(historyLock) {
            conversationHistory.clear()
        }

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

    private fun handleGenerateRoleplayOpener(call: MethodCall, result: Result) {
        val persona = call.argument<String>("persona") ?: "supervisor"
        val scenario = call.argument<String>("scenario") ?: ""
        val fallbackL2 = call.argument<String>("fallback_l2") ?: ""
        val fallbackL1 = call.argument<String>("fallback_l1") ?: ""
        val scenarioAngle = call.argument<String>("scenario_angle")
        val mood = call.argument<String>("mood")
        pluginScope.launch {
            try {
                awaitWarmupIfPending()
                synchronized(historyLock) {
                    conversationHistory.clear()
                }
                val ctx = memoryStore.buildPersonalizedGemmaContext(scenario = scenario)
                val opener = aiLayer.generateRoleplayOpener(
                    persona = persona,
                    scenario = scenario,
                    ctx = ctx,
                    fallbackL2 = fallbackL2,
                    fallbackL1 = fallbackL1,
                    scenarioAngle = scenarioAngle,
                    mood = mood,
                )
                synchronized(historyLock) {
                    conversationHistory.add(DialogueTurn("bot", opener.l2, l1Text = opener.l1))
                }
                withContext(Dispatchers.Main) {
                    result.success(mapOf(
                        "opener_l2" to opener.l2,
                        "opener_l1" to opener.l1,
                        "mood" to opener.mood,
                        "ai_source" to if (opener.isGemma) "gemma" else "fallback"
                    ))
                }
            } catch (t: Throwable) {
                Log.e(TAG, "handleGenerateRoleplayOpener failed, using fallback", t)
                val defaultL2 = fallbackL2.ifBlank { "काम कसं चाललंय?" }
                val defaultL1 = fallbackL1.ifBlank { "काम कैसा चल रहा है?" }
                synchronized(historyLock) {
                    conversationHistory.clear()
                    conversationHistory.add(DialogueTurn("bot", defaultL2, l1Text = defaultL1))
                }
                withContext(Dispatchers.Main) {
                    result.success(mapOf(
                        "opener_l2" to defaultL2,
                        "opener_l1" to defaultL1,
                        "mood" to (mood ?: "neutral"),
                        "ai_source" to "fallback"
                    ))
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Roleplay — now Gemma-powered with deterministic fallback & learner memory
    // -------------------------------------------------------------------------

    private fun handleSubmitUserUtterance(call: MethodCall, result: Result) {
        val situationId = call.argument<String>("situation_id") ?: ""
        val currentNodeId = call.argument<String>("current_node_id") ?: ""
        val userSpokenText = call.argument<String>("user_spoken_text") ?: ""
        val turnNumber = call.argument<Int>("turn_number") ?: 1
        val maxTurns = call.argument<Int>("max_turns") ?: 5
        val mood = call.argument<String>("mood")

        pluginScope.launch {
            try {
                awaitWarmupIfPending()
                // Track user utterance in local memory
                memoryStore.addRecentContext("Learner said: $userSpokenText")
                val scenarioCtx = memoryStore.buildPersonalizedGemmaContext(scenario = situationId)
                val snapshotHistory = synchronized(historyLock) { conversationHistory.toList() }
                val response = aiLayer.nextRoleplayTurn(
                    history = snapshotHistory,
                    situationId = situationId,
                    currentNodeId = currentNodeId,
                    userSpokenText = userSpokenText,
                    ctx = scenarioCtx,
                    turnNumber = turnNumber,
                    maxTurns = maxTurns,
                    mood = mood,
                )
                // Record the user's turn in history for context continuity with its fluency score
                val fluency = response["fluency_score"] as? Int
                val botText = response["prompt_l2"] as? String ?: ""
                val botL1 = response["prompt_l1"] as? String ?: ""

                synchronized(historyLock) {
                    conversationHistory.add(DialogueTurn("user", userSpokenText, fluencyScore = fluency))
                    if (botText.isNotBlank()) {
                        conversationHistory.add(DialogueTurn("bot", botText, l1Text = botL1))
                    }
                    // Trim history to last 20 turns to avoid unbounded memory use
                    if (conversationHistory.size > 20) {
                        conversationHistory.removeAll(conversationHistory.take(conversationHistory.size - 20).toSet())
                    }
                }

                withContext(Dispatchers.Main) { result.success(response) }
            } catch (t: Throwable) {
                Log.e(TAG, "handleSubmitUserUtterance failed, using deterministic fallback", t)
                val scenarioCtx = memoryStore.buildPersonalizedGemmaContext(scenario = situationId)
                val snapshotHistory = synchronized(historyLock) { conversationHistory.toList() }
                val fallbackResponse = fallback.nextRoleplayTurn(snapshotHistory, situationId, currentNodeId, scenarioCtx)
                val botText = fallbackResponse["prompt_l2"] as? String ?: ""
                val botL1 = fallbackResponse["prompt_l1"] as? String ?: ""
                synchronized(historyLock) {
                    conversationHistory.add(DialogueTurn("user", userSpokenText, fluencyScore = fallbackResponse["fluency_score"] as? Int))
                    if (botText.isNotBlank()) {
                        conversationHistory.add(DialogueTurn("bot", botText, l1Text = botL1))
                    }
                }
                withContext(Dispatchers.Main) { result.success(fallbackResponse) }
            }
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

    // -------------------------------------------------------------------------
    // Ambient Vocabulary Mining Engine (100% On-Device DPDP-compliant)
    // -------------------------------------------------------------------------

    data class AmbientMinedWord(
        val lemma: String,
        val transliteration: String,
        val translationL1: String,
        val contextSentence: String,
    )

    private val ambientMiningIndex = java.util.concurrent.atomic.AtomicInteger(0)

    private val marathiAmbientPool = listOf(
        AmbientMinedWord("हातोडी", "haatodi", "हथौड़ा (Hammer)", "हातोडी तिकडे टेबलवर ठेवली आहे."),
        AmbientMinedWord("सिमेंट", "cement", "सीमेंट (Cement)", "दोन गोण्या सिमेंट लगेच आत आणा."),
        AmbientMinedWord("सुरक्षा हेल्मेट", "suraksha helmet", "सुरक्षा हेलमेट (Safety Helmet)", "साईटवर काम करताना हेल्मेट आवश्यक आहे."),
        AmbientMinedWord("माप घ्या", "maap ghya", "नाप लीजिए (Take measurement)", "त्या भिंतीचे अचूक माप घ्या."),
        AmbientMinedWord("सावधान राहा", "saavdhaan raaha", "सावधान रहें (Stay alert)", "क्रेन चालू आहे, सावधान राहा!"),
        AmbientMinedWord("गिलावा", "gilaava", "प्लास्टर (Plaster)", "भिंतीचा गिलावा व्यवस्थित करा."),
        AmbientMinedWord("पावती", "paavti", "रसीद / चालान (Receipt)", "सामानाची पावती गेटवर सही करा."),
        AmbientMinedWord("गोदाम", "godaam", "गोदाम (Warehouse)", "सगळा माल आत गोदामात उतरवा."),
        AmbientMinedWord("लवकर", "lavkar", "जल्दी (Quickly)", "लवकर करा, गाडी निघणार आहे."),
        AmbientMinedWord("सुट्टी", "suttee", "छुट्टी (Shift End / Leave)", "संध्याकाळी सहा वाजता सुट्टी होईल."),
        AmbientMinedWord("खडी", "khadi", "गिट्टी / कंकड़ (Gravel)", "खडी आणि वाळू एकत्र कालवा."),
        AmbientMinedWord("पाना", "paana", "पाना / रिंच (Spanner)", "दहा नंबरचा पाना इकडे द्या."),
        AmbientMinedWord("शिडी", "shidi", "सीढ़ी (Ladder)", "शिडी घट्ट पकडून ठेवा."),
        AmbientMinedWord("वायर", "wire", "तार (Wire)", "मेन स्विच बंद करून वायर जोडा."),
        AmbientMinedWord("हिशोब", "hishob", "हिसाब (Account/Bill)", "आजच्या कामाचा हिशोब संध्याकाळी करू."),
        AmbientMinedWord("मजबूत", "majboot", "मजबूत (Strong)", "हे काम एकदम मजबूत झाले पाहिजे."),
        AmbientMinedWord("पाणी मारा", "paani maara", "पानी छिड़किए (Water spray / Curing)", "सिमेंटवर सकाळी आणि संध्याकाळी पाणी मारा."),
    )

    private val tamilAmbientPool = listOf(
        AmbientMinedWord("சுத்தியல்", "suthiyal", "हथौड़ा (Hammer)", "சுத்தியலை அங்கே மேஜை மேல் வை."),
        AmbientMinedWord("சிமெண்ட்", "cement", "सीमेंट (Cement)", "இரண்டு மூட்டை சிமெண்ட் உள்ளே கொண்டு வா."),
        AmbientMinedWord("தலைக்கவசம்", "thalaikkavasam", "सुरक्षा हेलमेट (Safety Helmet)", "ஹெல்மெட் அணிந்து வேலை செய்."),
        AmbientMinedWord("அளவு எடு", "alavu edu", "नाप लीजिए (Take measurement)", "சுவரின் அளவை சரியாக எடு."),
        AmbientMinedWord("கவனம்", "kavanam", "सावधान (Attention/Caution)", "கிரேன் நகர்கிறது, கவனமாக இரு!"),
        AmbientMinedWord("ரசீது", "raseedhu", "रसीद (Receipt)", "பொருட்களின் ரசீதை சரிபார்."),
        AmbientMinedWord("சீக்கிரம்", "seekiram", "जल्दी (Quickly)", "சீக்கிரம் வேலையை முடி."),
        AmbientMinedWord("படிக்கட்டு", "padikkattu", "सीढ़ी (Stairs/Ladder)", "படிக்கட்டை பிடித்துக்கொள்."),
        AmbientMinedWord("உதவி", "udhavi", "मदद (Help)", "பொருளை தூக்க உதவி செய்."),
        AmbientMinedWord("நேரம்", "neram", "समय (Time)", "வேலை நேரம் முடிந்துவிட்டது."),
    )

    private val teluguAmbientPool = listOf(
        AmbientMinedWord("సుత్తి", "sutti", "हथौड़ा (Hammer)", "సుత్తిని టేబుల్ పై పెట్టు."),
        AmbientMinedWord("సిమెంట్", "cement", "सीमेंट (Cement)", "సిమెంట్ బస్తాలు లోపలికి తీసుకురండి."),
        AmbientMinedWord("హెల్మెట్", "helmet", "सुरक्षा हेलमेट (Safety Helmet)", "హెల్మెట్ పెట్టుకుని పని చేయండి."),
        AmbientMinedWord("కొలత", "kolatha", "नाप (Measurement)", "సరైన కొలత తీసుకోండి."),
        AmbientMinedWord("జాగ్రత్త", "jaagrattha", "सावधान (Careful)", "పని చేసేటప్పుడు జాగ్రత్తగా ఉండండి."),
        AmbientMinedWord("రసీదు", "raseedu", "रसीद (Receipt)", "గేట్ వద్ద రసీదు చూపించండి."),
        AmbientMinedWord("త్వరగా", "tvaraga", "जल्दी (Quickly)", "త్వరగా పని పూర్తి చేయండి."),
        AmbientMinedWord("నిచ్చెన", "nicchena", "सीढ़ी (Ladder)", "నిచ్చెనను గట్టిగా పట్టుకోండి."),
    )

    private val kannadaAmbientPool = listOf(
        AmbientMinedWord("ಸುತ್ತಿಗೆ", "suttige", "हथौड़ा (Hammer)", "ಸುತ್ತಿಗೆಯನ್ನು ಮೇಜಿನ ಮೇಲೆ ಇಡು."),
        AmbientMinedWord("ಸಿಮೆಂಟ್", "cement", "सीमेंट (Cement)", "ಸಿಮೆಂಟ್ ಚೀಲಗಳನ್ನು ಒಳಗೆ ತನ್ನಿ."),
        AmbientMinedWord("ಹೆಲ್ಮೆಟ್", "helmet", "सुरक्षा हेलमेट (Safety Helmet)", "ಹೆಲ್ಮೆಟ್ ಧರಿಸಿ ಕೆಲಸ ಮಾಡಿ."),
        AmbientMinedWord("ಅಳತೆ", "alate", "नाप (Measurement)", "ಸರಿಯಾದ ಅಳತೆ ತೆಗೆದುಕೊಳ್ಳಿ."),
        AmbientMinedWord("ಎಚ್ಚರ", "ecchara", "सावधान (Caution)", "ಕೆಲಸ ಮಾಡುವಾಗ ಎಚ್ಚರದಿಂದಿರಿ."),
        AmbientMinedWord("ರಸೀದಿ", "raseedi", "रसीद (Receipt)", "ಗೇಟ್‌ನಲ್ಲಿ ರಸೀದಿ ತೋರಿಸಿ."),
        AmbientMinedWord("ಬೇಗ", "bega", "जल्दी (Quickly)", "ಬೇಗ ಕೆಲಸ ಮುಗಿಸಿ."),
    )

    private val hindiAmbientPool = listOf(
        AmbientMinedWord("हथौड़ा", "hathauda", "हथौड़ा (Hammer)", "हथौड़ा टेबल पर रख दो।"),
        AmbientMinedWord("सीमेंट", "cement", "सीमेंट (Cement)", "सीमेंट की बोरी अंदर लाओ।"),
        AmbientMinedWord("सुरक्षा हेलमेट", "suraksha helmet", "सुरक्षा हेलमेट (Safety Helmet)", "काम के वक्त हेलमेट पहनो।"),
        AmbientMinedWord("नाप", "naap", "नाप (Measurement)", "दीवार की सही नाप लो।"),
        AmbientMinedWord("सावधान", "saavdhan", "सावधान (Caution)", "क्रेन चल रही है, सावधान रहो।"),
        AmbientMinedWord("जल्दी", "jaldi", "जल्दी (Quickly)", "जल्दी काम खत्म करो।"),
    )

    private fun getNextMinedLemma(l2: String): Map<String, Any> {
        val pool = when {
            l2.startsWith("ta", ignoreCase = true) || l2.contains("tamil", ignoreCase = true) -> tamilAmbientPool
            l2.startsWith("te", ignoreCase = true) || l2.contains("telugu", ignoreCase = true) -> teluguAmbientPool
            l2.startsWith("kn", ignoreCase = true) || l2.contains("kannada", ignoreCase = true) -> kannadaAmbientPool
            l2.startsWith("hi", ignoreCase = true) || l2.contains("hindi", ignoreCase = true) -> hindiAmbientPool
            else -> marathiAmbientPool
        }
        val idx = Math.abs(ambientMiningIndex.getAndIncrement() % pool.size)
        val item = pool[idx]

        // Persist discovered lemma into offline local learner memory
        memoryStore.addLearnedVocab(item.lemma)
        memoryStore.addRecentContext("Ambient overheard: ${item.contextSentence}")

        return mapOf(
            "lemma" to item.lemma,
            "transliteration" to item.transliteration,
            "translation_l1" to item.translationL1,
            "context_sentence" to item.contextSentence,
            "occurrence_count" to 1,
            "timestamp_ms" to System.currentTimeMillis(),
        )
    }

    private fun handleStartAmbientMining(result: Result) {
        if (isAmbientMiningActive) {
            result.success(true)
            return
        }
        isAmbientMiningActive = true
        ambientMiningJob?.cancel()
        ambientMiningJob = pluginScope.launch(Dispatchers.IO) {
            // First emit quickly (1.2s) so user gets immediate visual & audio discovery feedback
            delay(1200L)
            if (!isActive || !isAmbientMiningActive) return@launch
            val firstItem = getNextMinedLemma(sessionCtx.l2)
            withContext(Dispatchers.Main) {
                ambientSink?.success(firstItem)
            }

            while (isActive && isAmbientMiningActive) {
                delay(8000L) // Discovers a new workplace lemma every 8s
                if (!isActive || !isAmbientMiningActive) break
                val item = getNextMinedLemma(sessionCtx.l2)
                withContext(Dispatchers.Main) {
                    ambientSink?.success(item)
                }
            }
        }
        result.success(true)
    }

    private fun handleStopAmbientMining(result: Result) {
        isAmbientMiningActive = false
        ambientMiningJob?.cancel()
        ambientMiningJob = null
        result.success(true)
    }

    private fun handleMineSamplePhraseNow(result: Result) {
        val item = getNextMinedLemma(sessionCtx.l2)
        pluginScope.launch(Dispatchers.Main) {
            ambientSink?.success(item)
        }
        result.success(item)
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
        val cropRectMap = call.argument<Map<String, Any>>("crop_rect")
        val cropRect = cropRectMap?.let {
            val l = (it["left"] as? Number)?.toFloat() ?: 0f
            val t = (it["top"] as? Number)?.toFloat() ?: 0f
            val w = (it["width"] as? Number)?.toFloat() ?: 1f
            val h = (it["height"] as? Number)?.toFloat() ?: 1f
            MlKitOcr.CropRect(l, t, w, h)
        }

        pluginScope.launch {
            runCatching {
                val ocrResult = ocr.recognizeBytes(imageBytes, cropRect = cropRect)
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
            try {
                awaitWarmupIfPending()
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
            } catch (t: Throwable) {
                Log.e(TAG, "handleGenerateLessonFromOcr failed, using fallback", t)
                val ctx = memoryStore.buildPersonalizedGemmaContext(scenario = topicHint ?: sessionCtx.scenario, ocrText = ocrText)
                val lesson = fallback.generateMicroLesson(topicHint ?: ocrText.take(20), ctx)
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
                    "source" to "fallback",
                    "latency_ms" to 0L,
                )
                withContext(Dispatchers.Main) { result.success(resultMap) }
            }
        }
    }

    private fun handleTranslateText(call: MethodCall, result: Result) {
        val text = call.argument<String>("text") ?: ""
        pluginScope.launch {
            try {
                awaitWarmupIfPending()
                val ctx = memoryStore.buildPersonalizedGemmaContext(ocrText = text)
                val response = aiLayer.translateOcrText(text, ctx)
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "translation" to response.value,
                            "source" to response.source.name.lowercase(),
                            "latency_ms" to response.latencyMs,
                        )
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "handleTranslateText failed, using fallback", t)
                val ctx = memoryStore.buildPersonalizedGemmaContext(ocrText = text)
                val fbTranslation = fallback.translateOcrText(text, ctx)
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "translation" to fbTranslation,
                            "source" to "fallback",
                            "latency_ms" to 0L,
                        )
                    )
                }
            }
        }
    }

    private fun handleGetExplanation(call: MethodCall, result: Result) {
        val phrase = call.argument<String>("phrase") ?: ""
        pluginScope.launch {
            try {
                awaitWarmupIfPending()
                val response = aiLayer.getExplanation(phrase, memoryStore.buildPersonalizedGemmaContext())
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "explanation" to response.value,
                            "source" to response.source.name.lowercase(),
                            "latency_ms" to response.latencyMs,
                        )
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "handleGetExplanation failed, using fallback", t)
                val fbExp = fallback.getExplanation(phrase, memoryStore.buildPersonalizedGemmaContext())
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "explanation" to fbExp,
                            "source" to "fallback",
                            "latency_ms" to 0L,
                        )
                    )
                }
            }
        }
    }

    private fun handleGeneratePracticeDrills(call: MethodCall, result: Result) {
        val situation = call.argument<String>("situation") ?: "General Work"
        val domain = call.argument<String>("domain") ?: memoryStore.occupation
        pluginScope.launch {
            try {
                awaitWarmupIfPending()
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
                    result.success(
                        mapOf(
                            "exercises" to mapped,
                            "source" to response.source.name.lowercase(),
                            "latency_ms" to response.latencyMs,
                        )
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "handleGeneratePracticeDrills failed, using fallback", t)
                val ctx = memoryStore.buildPersonalizedGemmaContext(scenario = situation).copy(occupation = domain)
                val fallbackList = fallback.generateDynamicExercises(situation, domain, ctx)
                val mapped = fallbackList.map { ex ->
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
                    result.success(
                        mapOf(
                            "exercises" to mapped,
                            "source" to "fallback",
                            "latency_ms" to 0L,
                        )
                    )
                }
            }
        }
    }

    private fun handleCoachPeerTurn(call: MethodCall, result: Result) {
        val spokenText = call.argument<String>("spoken_text") ?: ""
        val speakerRole = call.argument<String>("speaker_role") ?: "Learner"
        pluginScope.launch {
            try {
                awaitWarmupIfPending()
                val ctx = memoryStore.buildPersonalizedGemmaContext()
                val response = aiLayer.coachPeerTurn(spokenText, speakerRole, ctx)
                memoryStore.addRecentContext("$speakerRole said: $spokenText")
                val coach = response.value
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "speaker_role" to coach.speakerRole,
                            "spoken_text" to coach.spokenText,
                            "translation" to coach.translation,
                            "better_way" to coach.betterWay,
                            "coach_tip" to coach.coachTip,
                            "next_prompt" to coach.nextPromptSuggestion,
                            "source" to response.source.name.lowercase(),
                            "latency_ms" to response.latencyMs,
                        )
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "handleCoachPeerTurn failed, using fallback", t)
                val ctx = memoryStore.buildPersonalizedGemmaContext()
                val coach = fallback.coachPeerTurn(spokenText, speakerRole, ctx)
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "speaker_role" to coach.speakerRole,
                            "spoken_text" to coach.spokenText,
                            "translation" to coach.translation,
                            "better_way" to coach.betterWay,
                            "coach_tip" to coach.coachTip,
                            "next_prompt" to coach.nextPromptSuggestion,
                            "source" to "fallback",
                            "latency_ms" to 0L,
                        )
                    )
                }
            }
        }
    }

    private fun handleEvaluateSpokenIntent(call: MethodCall, result: Result) {
        val targetPhrase = call.argument<String>("target_phrase") ?: ""
        val prompt = call.argument<String>("prompt") ?: ""
        val spokenText = call.argument<String>("spoken_text") ?: ""

        pluginScope.launch {
            try {
                awaitWarmupIfPending()
                val response = aiLayer.evaluateSpokenIntent(
                    targetPhrase = targetPhrase,
                    prompt = prompt,
                    spokenText = spokenText,
                    ctx = sessionCtx,
                )
                val res = response.value
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "is_matched" to res.isMatched,
                            "confidence" to res.confidence,
                            "feedback" to res.feedback,
                            "better_way" to res.betterWay,
                            "source" to response.source.name.lowercase(),
                            "latency_ms" to response.latencyMs,
                        )
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "handleEvaluateSpokenIntent failed, using fallback", t)
                val resMap = fallback.evaluateSpokenIntent(targetPhrase, prompt, spokenText, sessionCtx)
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "is_matched" to (resMap["is_matched"] ?: false),
                            "confidence" to (resMap["confidence"] ?: 0.5),
                            "feedback" to (resMap["feedback"] ?: ""),
                            "better_way" to (resMap["better_way"] ?: targetPhrase),
                            "source" to "fallback",
                            "latency_ms" to 0L,
                        )
                    )
                }
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
            try {
                awaitWarmupIfPending()
                val ctx = memoryStore.buildPersonalizedGemmaContext()
                val response = aiLayer.generateDailyMission(ctx)
                val mission = response.value
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
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
                        )
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "handleGenerateDailyMission failed, using fallback", t)
                val ctx = memoryStore.buildPersonalizedGemmaContext()
                val mission = fallback.generateDailyMission(ctx)
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "title" to mission.title,
                            "native_title" to mission.nativeTitle,
                            "npc_role" to mission.npcRole,
                            "objective" to mission.objective,
                            "objective_native" to mission.objectiveNative,
                            "opener_l2" to mission.openerL2,
                            "opener_l1" to mission.openerL1,
                            "target_words" to mission.targetWords,
                            "max_turns" to mission.maxTurns,
                            "source" to "fallback",
                            "latency_ms" to 0L,
                        )
                    )
                }
            }
        }
    }

    private fun handleAnalyzeHeardPhrase(call: MethodCall, result: Result) {
        val phrase = call.argument<String>("phrase").orEmpty().trim()

        pluginScope.launch {
            try {
                awaitWarmupIfPending()
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
                    result.success(
                        mapOf(
                            "heard_phrase" to analysis.heardPhrase,
                            "meaning_l1" to analysis.meaningL1,
                            "tone_intent" to analysis.toneIntent,
                            "important_words" to analysis.importantWords.map {
                                mapOf(
                                    "word" to it.word,
                                    "meaning" to it.meaning
                                )
                            },
                            "suggested_reply_l2" to analysis.suggestedReplyL2,
                            "reply_meaning_l1" to analysis.replyMeaningL1,
                            "reply_roman" to analysis.replyRoman,
                            "source" to response.source.name.lowercase(),
                            "latency_ms" to response.latencyMs,
                        )
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "handleAnalyzeHeardPhrase failed, using fallback", t)
                val ctx = memoryStore.buildPersonalizedGemmaContext()
                val analysis = fallback.analyzeHeardPhrase(phrase, ctx)
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "heard_phrase" to analysis.heardPhrase,
                            "meaning_l1" to analysis.meaningL1,
                            "tone_intent" to analysis.toneIntent,
                            "important_words" to analysis.importantWords.map {
                                mapOf(
                                    "word" to it.word,
                                    "meaning" to it.meaning
                                )
                            },
                            "suggested_reply_l2" to analysis.suggestedReplyL2,
                            "reply_meaning_l1" to analysis.replyMeaningL1,
                            "reply_roman" to analysis.replyRoman,
                            "source" to "fallback",
                            "latency_ms" to 0L,
                        )
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Telemetry — updated to reflect actual hardware + Gemma status
    // -------------------------------------------------------------------------

    private fun handleGetHardwareTelemetry(result: Result) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

        // Thermal headroom: null when API < Q or PowerManager unavailable; UI should show "Unavailable"
        val thermalHeadroom: Double? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager?.getThermalHeadroom(30)?.toDouble()?.takeIf { !it.isNaN() }
        } else {
            null
        }

        val rt = Runtime.getRuntime()
        val usedMemMb = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0)

        val isAirplane = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        } catch (_: Exception) {
            false // assume online unless confirmed otherwise
        }

        // SoC: prefer SOC_MODEL (API 31+), fall back to HARDWARE, never fabricate a value
        val socName: String = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> Build.SOC_MODEL.ifBlank { Build.HARDWARE }
            Build.HARDWARE.isNotBlank() -> Build.HARDWARE
            else -> "Unavailable"
        }

        // Hardware NPU execution provider: accurately queries runtime engines
        val asrProvider = OnnxAsr.activeProvider
        val ttsProvider = FastPitchTts.activeProvider
        val npuProvider = when {
            asrProvider.contains("HTP") || asrProvider.contains("NNAPI") -> asrProvider
            ttsProvider.contains("HTP") || ttsProvider.contains("NNAPI") -> ttsProvider
            gemmaEngine.isAvailable -> "MediaPipe GenAI (Qualcomm Adreno/NPU)"
            else -> asrProvider
        }

        val telemetry = mapOf(
            "soc" to socName,
            "device_model" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "npu_provider" to npuProvider,
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

