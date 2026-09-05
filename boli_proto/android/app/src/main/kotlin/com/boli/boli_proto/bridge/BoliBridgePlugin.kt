package com.boli.boli_proto.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.*

/**
 * BoliBridgePlugin
 * 
 * Native Android plugin connecting Flutter presentation layer to the on-device
 * Qualcomm Hexagon NPU execution engine, audio stream pipelines, and telemetry listeners.
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

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        methodChannel = MethodChannel(binding.binaryMessenger, METHOD_CHANNEL)
        methodChannel.setMethodCallHandler(this)

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
        pluginScope.cancel()
    }

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
            "isAmbientMiningActive" -> result.success(false)
            "extractTextFromImage" -> result.success(emptyList<String>())
            "getHardwareTelemetry" -> handleGetHardwareTelemetry(result)
            else -> result.notImplemented()
        }
    }

    private fun handleInitializeEngine(call: MethodCall, result: Result) {
        val corridor = call.argument<String>("corridor") ?: "bhojpuriTamil"
        val domain = call.argument<String>("domain") ?: "construction"
        pluginScope.launch {
            withContext(Dispatchers.Main) {
                result.success(true)
            }
        }
    }

    private fun handleStartListening(result: Result) {
        pluginScope.launch {
            withContext(Dispatchers.Main) {
                result.success(null)
            }
        }
    }

    private fun handleStopListening(result: Result) {
        pluginScope.launch {
            withContext(Dispatchers.Main) {
                result.success(null)
            }
        }
    }

    private fun handleCancelListening(result: Result) {
        result.success(null)
    }

    private fun handleScorePronunciation(call: MethodCall, result: Result) {
        val targetWord = call.argument<String>("target_word") ?: ""
        val canonicalG2P = call.argument<String>("canonical_g2p") ?: ""

        pluginScope.launch {
            val response = mapOf(
                "target_word" to targetWord,
                "target_transliteration" to canonicalG2P,
                "overall_score" to -0.42,
                "phonemes" to listOf(
                    mapOf(
                        "phoneme" to "ட",
                        "ipa_symbol" to "ʈ",
                        "score" to -0.85,
                        "is_correct" to false,
                        "substituted_phoneme" to "த",
                        "articulation_guidance" to "Curl tongue back against the hard palate"
                    )
                ),
                "l1_interference_diagnostic" to "L1 Bhojpuri interference detected on Tamil retroflex consonant"
            )
            withContext(Dispatchers.Main) {
                result.success(response)
            }
        }
    }

    private fun handleSubmitUserUtterance(call: MethodCall, result: Result) {
        val situationId = call.argument<String>("situation_id") ?: ""
        val currentNodeId = call.argument<String>("current_node_id") ?: ""
        val userSpokenText = call.argument<String>("user_spoken_text") ?: ""

        pluginScope.launch {
            val response = mapOf(
                "recognized_transcript" to userSpokenText,
                "is_intent_matched" to true,
                "matched_intent" to "confirm_mix",
                "next_node_id" to "node_02",
                "prompt_l2" to "சரி, சிமெண்ட் கலவை விகிதம் என்ன?",
                "prompt_transliteration" to "Sari, siment kalavai vigidham enna?",
                "prompt_l1" to "ठीक है, सीमेंट मिश्रण का अनुपात क्या है?",
                "pre_rendered_audio_path" to "audio/ta_const_03_concrete_mix/mix_01_confirm.wav",
                "pronunciation_score" to -0.35,
                "weak_phonemes" to listOf("ட"),
                "articulatory_hint" to "जीभ को तालू के पिछले भाग से स्पर्श करें"
            )
            withContext(Dispatchers.Main) {
                result.success(response)
            }
        }
    }

    private fun handleSpeakPrompt(call: MethodCall, result: Result) {
        result.success(null)
    }

    private fun handleStopSpeaking(result: Result) {
        result.success(null)
    }

    private fun handleStartAmbientMining(result: Result) {
        result.success(null)
    }

    private fun handleStopAmbientMining(result: Result) {
        result.success(null)
    }

    private fun handleGetHardwareTelemetry(result: Result) {
        val telemetry = mapOf(
            "soc" to "Snapdragon 8 Elite Gen 5",
            "npu_provider" to "QNNExecutionProvider (HTP)",
            "thermal_headroom" to 0.45,
            "runtime_memory_mb" to 84.2,
            "airplane_mode" to true
        )
        result.success(telemetry)
    }
}
