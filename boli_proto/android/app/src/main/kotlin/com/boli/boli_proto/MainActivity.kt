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
 * Flutter owns the UI; Kotlin owns audio and inference (CLAUDE.md 3.5).
 */
class MainActivity : FlutterActivity() {

    private val asr by lazy { OnnxAsr(applicationContext) }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

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
                        if (!hasMicPermission()) {
                            requestMicPermission()
                            result.error("NO_PERMISSION", "Microphone permission requested — tap again", null)
                        } else {
                            off(result) { asr.transcribe(MicRecorder.record()) }
                        }
                    }

                    else -> result.notImplemented()
                }
            }

        // Unpacking 137MB and building both sessions takes a moment; do it now
        // so the first button press measures inference, not cold start.
        thread { runCatching { asr.warmUp() }.onFailure { Log.e(TAG, "warmUp failed", it) } }
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestMicPermission() =
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)

    /** Inference is far too slow for the platform thread; hop off and post back. */
    private fun off(result: MethodChannel.Result, work: () -> String) {
        thread {
            val outcome = runCatching(work)
            runOnUiThread {
                outcome
                    .onSuccess { result.success(it) }
                    .onFailure {
                        Log.e(TAG, "transcribe failed", it)
                        result.error("ASR_FAILED", it.message ?: it.toString(), null)
                    }
            }
        }
    }

    companion object {
        private const val CHANNEL = "boli/asr"
        private const val TAG = "BoliAsr"
    }
}
