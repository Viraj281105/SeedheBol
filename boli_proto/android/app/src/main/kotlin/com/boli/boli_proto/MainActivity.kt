package com.boli.boli_proto

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlin.concurrent.thread

/**
 * The whole Dart<->Kotlin surface: one channel, methods returning String.
 * Flutter owns the UI; Kotlin owns audio and inference.
 *
 * Channel: 'boli/asr'
 *   Methods: transcribeAsset | transcribeMic | speak
 *
 * The broader engine surface (OCR, Gemma, roleplay) lives on 'boli/engine_methods'
 * handled by BoliBridgePlugin, which is registered via flutterEngine.plugins.add().
 *
 * PERMISSIONS requested here:
 *   RECORD_AUDIO — microphone for IndicConformer ASR
 *   CAMERA       — CameraX for ML Kit OCR lens screen
 */
class MainActivity : FlutterActivity() {

    private val asr by lazy { OnnxAsr(applicationContext) }

    // Canonical on-device speech synthesis via AI4Bharat FastPitch + HiFi-GAN (22.05 kHz).
    private val tts by lazy { FastPitchTts(applicationContext) }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // BoliBridgePlugin registers 'boli/engine_methods' + event channels.
        // It also owns GemmaEngine warm-up — no duplication needed here.
        flutterEngine.plugins.add(com.boli.boli_proto.bridge.BoliBridgePlugin())

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    // T3 — bundled WAV, no permissions needed.
                    "transcribeAsset" -> off(result) {
                        val pcm = assets.open("sample.wav").use { WavReader.read(it) }
                        Log.i(TAG, "sample.wav: ${pcm.size} samples")
                        asr.transcribe(pcm)
                    }

                    // T4 — live microphone.
                    "transcribeMic" -> {
                        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                            requestPermission(Manifest.permission.RECORD_AUDIO, REQ_MIC)
                            result.error("NO_PERMISSION", "Microphone permission requested — tap again", null)
                        } else {
                            val seconds = (call.argument<Double>("seconds") ?: 4.0).toFloat()
                            off(result) { asr.transcribe(MicRecorder.record(seconds)) }
                        }
                    }

                    // Plays the target phrase. Speaking exercises call this
                    // before asking the user to repeat — nobody can pronounce
                    // a word they have never heard.
                    "speak" -> {
                        val text = call.argument<String>("text").orEmpty()
                        off(result) { tts.speak(text) }
                    }

                    else -> result.notImplemented()
                }
            }

        // Unpacking 137MB and building both sessions takes a moment; do it now
        // so the first button press measures inference, not cold start.
        thread { runCatching { asr.warmUp() }.onFailure { Log.e(TAG, "warmUp failed", it) } }
        thread { runCatching { tts.warmUp() }.onFailure { Log.e(TAG, "tts warmUp failed", it) } }
        // GemmaEngine warm-up is triggered in BoliBridgePlugin.onAttachedToEngine.
    }

    // ---- permission helpers (generalised to handle CAMERA too) ---------------

    private fun hasPermission(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun requestPermission(perm: String, requestCode: Int) =
        ActivityCompat.requestPermissions(this, arrayOf(perm), requestCode)

    /** Inference is far too slow for the platform thread; hop off and post back. */
    private fun off(result: MethodChannel.Result, work: () -> String) {
        thread {
            val outcome = runCatching(work)
            runOnUiThread {
                outcome
                    .onSuccess { result.success(it) }
                    .onFailure {
                        Log.e(TAG, "inference failed", it)
                        result.error("INFER_FAILED", it.message ?: it.toString(), null)
                    }
            }
        }
    }

    companion object {
        private const val CHANNEL = "boli/asr"
        private const val TAG = "SeedheBolMain"
        private const val REQ_MIC = 1
        private const val REQ_CAMERA = 2
    }
}
