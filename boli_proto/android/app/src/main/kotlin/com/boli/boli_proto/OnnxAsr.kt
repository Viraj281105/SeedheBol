package com.boli.boli_proto

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * IndicConformer CTC inference — the Kotlin twin of scripts/transcribe_onnx.py.
 *
 *   PCM float32 [-1,1] @16kHz
 *     -> nemo80.onnx       (waveforms, waveforms_lens) -> (features, features_lens)
 *     -> model.int8.onnx   (audio_signal, length)      -> logprobs [B,T,257]
 *     -> greedy CTC decode
 *
 * The log-mel front-end is an ONNX graph, not hand-written DSP. That is why
 * CLAUDE.md Trap 1 does not apply here: this runs the identical graph that
 * produced reference/melspec.npy on the laptop.
 */
class OnnxAsr(private val context: Context) {

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    private val preprocessor: OrtSession by lazy {
        env.createSession(resolveAsset("nemo80.onnx").absolutePath, cpuOptions())
    }
    private val model: OrtSession by lazy {
        env.createSession(resolveAsset(MODEL).absolutePath, cpuOptions())
    }

    /** id -> token. Blank is the highest id, written as "<blk>" in vocab.txt. */
    private val vocab: Map<Int, String> by lazy {
        resolveAsset("vocab.txt").readLines()
            .filter { it.isNotBlank() }
            .associate { line ->
                val cut = line.lastIndexOf(' ')
                line.substring(cut + 1).toInt() to line.substring(0, cut)
            }
    }
    private val blankId: Int by lazy { vocab.keys.max() }

    private fun cpuOptions() = OrtSession.SessionOptions().apply {
        // Pixel 8 is Tensor G3, not Snapdragon — no QNN here (CLAUDE.md Trap 8).
        // The claim being proven is offline, not accelerated.
        setIntraOpNumThreads(4)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }

    /**
     * ONNX Runtime cannot mmap a file inside an APK (Trap 5), so assets are
     * copied to filesDir once and opened from there.
     *
     * If the same filename exists in the app's external files dir it wins, which
     * lets `adb push` swap the model without a reinstall (Trap 7). That directory
     * is readable without any storage permission under scoped storage:
     *   adb push model.arm64.onnx /sdcard/Android/data/com.boli.boli_proto/files/
     */
    private fun resolveAsset(name: String): File {
        val pushed = File(context.getExternalFilesDir(null), name)
        if (pushed.exists()) {
            Log.i(TAG, "using pushed $name (${pushed.length()} bytes)")
            return pushed
        }
        val dst = File(context.filesDir, name)
        if (!dst.exists() || dst.length() == 0L) {
            Log.i(TAG, "unpacking $name from assets")
            context.assets.open(name).use { input ->
                dst.outputStream().use { input.copyTo(it) }
            }
        }
        return dst
    }

    /** Warms both sessions so the first real transcription is not also a cold start. */
    fun warmUp() {
        preprocessor
        model
        vocab
        Log.i(TAG, "sessions ready, vocab=${vocab.size}, blank=$blankId")
    }

    fun transcribe(samples: FloatArray): String {
        require(samples.isNotEmpty()) { "no audio" }
        val t0 = System.currentTimeMillis()

        // --- log-mel front-end ---
        val waveforms = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())
        )
        val waveformsLens = OnnxTensor.createTensor(
            env, LongBuffer.wrap(longArrayOf(samples.size.toLong())), longArrayOf(1)
        )

        var text: String
        waveforms.use { w ->
            waveformsLens.use { wl ->
                preprocessor.run(mapOf("waveforms" to w, "waveforms_lens" to wl)).use { pre ->
                    val features = pre.get(0) as OnnxTensor
                    val featuresLens = pre.get(1) as OnnxTensor
                    val nFrames = (featuresLens.value as LongArray)[0]

                    // --- acoustic model ---
                    model.run(
                        mapOf("audio_signal" to features, "length" to featuresLens)
                    ).use { out ->
                        @Suppress("UNCHECKED_CAST")
                        val logprobs = (out.get(0) as OnnxTensor).value as Array<Array<FloatArray>>
                        val valid = ((nFrames - 1) / SUBSAMPLING + 1).toInt()
                        text = greedyCtcDecode(logprobs[0], valid)
                    }
                }
            }
        }

        val ms = System.currentTimeMillis() - t0
        val seconds = samples.size / 16000f
        Log.i(TAG, "transcribed ${"%.2f".format(seconds)}s in ${ms}ms (RTF ${"%.3f".format(ms / 1000f / seconds)})")
        return text
    }

    /** argmax per frame, collapse repeats, drop blanks — matches transcribe_onnx.py. */
    private fun greedyCtcDecode(logprobs: Array<FloatArray>, validFrames: Int): String {
        val sb = StringBuilder()
        var prev = -1
        for (t in 0 until minOf(validFrames, logprobs.size)) {
            val row = logprobs[t]
            var best = 0
            for (i in row.indices) if (row[i] > row[best]) best = i
            if (best != prev && best != blankId) sb.append(vocab[best] ?: "")
            prev = best
        }
        return sb.toString().replace("▁", " ").trim()
    }

    companion object {
        private const val TAG = "BoliAsr"
        private const val SUBSAMPLING = 4L // models/mr/config.json

        /**
         * MatMul-only int8 (see scripts/quantize_arm.py). The upstream
         * model.int8.onnx also quantizes Conv, and ONNX Runtime's arm64 CPU
         * provider has no ConvInteger kernel — it loads on x86 and fails on the
         * phone, which is the sort of thing only a device catches.
         */
        private const val MODEL = "model.arm64.onnx"
    }
}
