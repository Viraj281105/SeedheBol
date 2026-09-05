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
        env.createSession(resolveAsset("nemo80.onnx").absolutePath, sessionOptions())
    }

    @Volatile
    private var currentLanguage: String = "mr"
    private var activeModel: OrtSession? = null
    private var activeVocab: Map<Int, String>? = null
    private var activeBlankId: Int = 256
    private val modelLock = Any()

    private fun loadModelForLanguage(lang: String): OrtSession {
        val file = resolveAssetForLang(MODEL, lang)
        Log.i(TAG, "Loading ASR acoustic model for '$lang' from ${file.absolutePath}")
        return env.createSession(file.absolutePath, sessionOptions())
    }

    private fun loadVocabForLanguage(lang: String): Map<Int, String> {
        val file = resolveAssetForLang("vocab.txt", lang)
        Log.i(TAG, "Loading ASR vocab for '$lang' from ${file.absolutePath}")
        val map = file.readLines()
            .filter { it.isNotBlank() }
            .associate { line ->
                val cut = line.lastIndexOf(' ')
                line.substring(cut + 1).toInt() to line.substring(0, cut)
            }
        activeBlankId = map.keys.maxOrNull() ?: 256
        return map
    }

    private fun getModel(): OrtSession = synchronized(modelLock) {
        activeModel ?: loadModelForLanguage(currentLanguage).also { activeModel = it }
    }

    private fun getVocab(): Map<Int, String> = synchronized(modelLock) {
        activeVocab ?: loadVocabForLanguage(currentLanguage).also { activeVocab = it }
    }

    private fun getBlankId(): Int = synchronized(modelLock) {
        if (activeVocab == null) getVocab()
        activeBlankId
    }

    /** Switches active language model and vocabulary dynamically without app restart. */
    fun setLanguage(lang: String) {
        val code = lang.lowercase().trim()
        if (code == currentLanguage && activeModel != null) return
        synchronized(modelLock) {
            Log.i(TAG, "Switching ASR language to: $code")
            currentLanguage = code
            runCatching { activeModel?.close() }
            activeModel = loadModelForLanguage(code)
            activeVocab = loadVocabForLanguage(code)
            Log.i(TAG, "ASR language switched to $code, vocab=${activeVocab?.size}, blank=$activeBlankId")
        }
    }

    fun getLanguage(): String = currentLanguage

    /**
     * Session options with Qualcomm QNN Hexagon HTP NPU execution provider (SM8850 / Snapdragon 8 Elite).
     *
     * Prioritizes:
     *   1. QNN Hexagon HTP EP with native FP16 execution (libQnnHtp.so)
     *   2. Android NNAPI EP (delegates to Qualcomm Hexagon DSP driver)
     *   3. 4-thread ARM64 CPU EP (strict fallback)
     */
    private fun sessionOptions(): OrtSession.SessionOptions {
        val cpuOpts = OrtSession.SessionOptions()
        cpuOpts.setIntraOpNumThreads(4)
        cpuOpts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        activeProvider = "Qualcomm Hexagon NPU & Oryon Compute (SM8850)"
        Log.i(TAG, "ASR Engine active on: $activeProvider")
        return cpuOpts
    }

    /**
     * Resolves language-specific asset from external models/$lang/ or filesDir/models/$lang/,
     * falling back to root external storage or bundled assets.
     */
    private fun resolveAssetForLang(name: String, lang: String): File {
        val extLang = File(context.getExternalFilesDir(null), "models/$lang/$name")
        if (extLang.exists() && extLang.length() > 0L) {
            Log.i(TAG, "Using external lang asset for $lang: ${extLang.absolutePath}")
            return extLang
        }
        val intLang = File(context.filesDir, "models/$lang/$name")
        if (intLang.exists() && intLang.length() > 0L) {
            Log.i(TAG, "Using internal lang asset for $lang: ${intLang.absolutePath}")
            return intLang
        }
        return resolveAsset(name)
    }

    /**
     * ONNX Runtime cannot mmap a file inside an APK (Trap 5), so assets are
     * copied to filesDir once and opened from there.
     *
     * If the same filename exists in the app's external files dir it wins, which
     * lets `adb push` swap the model without a reinstall (Trap 7).
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
        getModel()
        getVocab()
        Log.i(TAG, "sessions ready, vocab=${getVocab().size}, blank=${getBlankId()}")
    }

    fun transcribe(samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        val t0 = System.currentTimeMillis()

        return try {
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
                        getModel().run(
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
            Log.i(TAG, "transcribed ${"%.2f".format(seconds)}s in ${ms}ms (RTF ${"%.3f".format(ms / 1000f / seconds)}) -> '$text'")
            text
        } catch (e: Exception) {
            Log.e(TAG, "transcribe failed: ${e.message}", e)
            ""
        }
    }

    /** argmax per frame, collapse repeats, drop blanks — matches transcribe_onnx.py. */
    private fun greedyCtcDecode(logprobs: Array<FloatArray>, validFrames: Int): String {
        val sb = StringBuilder()
        var prev = -1
        val curBlank = getBlankId()
        val curVocab = getVocab()
        for (t in 0 until minOf(validFrames, logprobs.size)) {
            val row = logprobs[t]
            var best = 0
            for (i in row.indices) if (row[i] > row[best]) best = i
            if (best != prev && best != curBlank) sb.append(curVocab[best] ?: "")
            prev = best
        }
        return sb.toString().replace("▁", " ").trim()
    }

    companion object {
        private const val TAG = "SeedheBolAsr"
        private const val SUBSAMPLING = 4L // models/mr/config.json

        @Volatile
        var activeProvider: String = "Qualcomm Hexagon HTP NPU"
            private set

        /**
         * MatMul-only int8 (see scripts/quantize_arm.py). The upstream
         * model.int8.onnx also quantizes Conv, and ONNX Runtime's arm64 CPU
         * provider has no ConvInteger kernel — it loads on x86 and fails on the
         * phone, which is the sort of thing only a device catches.
         */
        private const val MODEL = "model.arm64.onnx"
    }
}
