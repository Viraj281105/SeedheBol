package com.boli.boli_proto

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.LongBuffer
import java.security.MessageDigest
import java.util.Locale

/**
 * Marathi speech synthesis — AI4Bharat FastPitch + HiFi-GAN, 22.05 kHz,
 * with resilient system TextToSpeech fallback.
 *
 *   text -> character ids -> fastpitch.onnx -> mel [1,80,T]
 *                          -> hifigan.onnx  -> waveform -> AudioTrack
 *
 * If FastPitch fails (missing weights, unsupported tokens, or OS error),
 * synthesis transparently falls back to Android's built-in TextToSpeech engine
 * so audio output NEVER fails silently.
 */
class FastPitchTts private constructor(private val context: Context) {

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    private val fastpitch: OrtSession by lazy {
        resolveAsset("fastpitch.onnx.data")
        env.createSession(resolveAsset(FASTPITCH_MODEL).absolutePath, npuSessionOptions("FastPitch"))
    }
    private val hifigan: OrtSession by lazy {
        env.createSession(resolveAsset(HIFIGAN_MODEL).absolutePath, npuSessionOptions("HiFi-GAN"))
    }

    private data class Tokens(
        val charToId: Map<Char, Long>,
        val addBlank: Boolean,
        val useEosBos: Boolean,
        val blankId: Long,
        val bosId: Long,
        val eosId: Long,
    )

    private val tokens: Tokens by lazy {
        val raw = try {
            val assetNames = context.assets.list("") ?: emptyArray()
            val target = if (assetNames.contains(TOKENS)) TOKENS else "tokens.json"
            context.assets.open(target).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading primary tokens asset: ${e.message}")
            context.assets.open("tokens.json").bufferedReader().use { it.readText() }
        }

        var json = JSONObject(raw)
        if (!json.has("char_to_id")) {
            Log.w(TAG, "char_to_id not found in first file, falling back to tokens.json")
            val fallbackRaw = context.assets.open("tokens.json").bufferedReader().use { it.readText() }
            json = JSONObject(fallbackRaw)
        }

        val c2i = json.getJSONObject("char_to_id")
        val map = buildMap {
            for (key in c2i.keys()) {
                if (key.length == 1) put(key[0], c2i.getLong(key))
            }
        }
        Tokens(
            charToId = map,
            addBlank = json.optBoolean("add_blank", false),
            useEosBos = json.optBoolean("use_eos_bos", false),
            blankId = json.optLong("blank_id", 0L),
            bosId = json.optLong("bos_id", 0L),
            eosId = json.optLong("eos_id", 0L),
        )
    }

    private val cacheDir: File by lazy {
        File(context.filesDir, "tts_cache").apply { mkdirs() }
    }

    private var track: AudioTrack? = null

    // System TTS fallback engine
    private var systemTts: TextToSpeech? = null
    @Volatile
    private var isSystemTtsReady = false

    init {
        try {
            systemTts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isSystemTtsReady = true
                    val langRes = systemTts?.setLanguage(Locale.forLanguageTag("mr-IN"))
                    if (langRes == TextToSpeech.LANG_MISSING_DATA || langRes == TextToSpeech.LANG_NOT_SUPPORTED) {
                        val hiRes = systemTts?.setLanguage(Locale.forLanguageTag("hi-IN"))
                        if (hiRes == TextToSpeech.LANG_MISSING_DATA || hiRes == TextToSpeech.LANG_NOT_SUPPORTED) {
                            systemTts?.setLanguage(Locale.getDefault())
                        }
                    }
                    Log.i(TAG, "System TTS fallback ready (language: ${systemTts?.voice?.locale ?: "default"})")
                } else {
                    Log.w(TAG, "System TTS initialization failed with status: $status")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "System TTS initialization threw exception", t)
        }
    }

    private fun npuSessionOptions(modelName: String): OrtSession.SessionOptions {
        // Priority 1: Qualcomm QNN Hexagon HTP NPU
        try {
            val qnnOpts = OrtSession.SessionOptions()
            qnnOpts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            val qnnConfig = mutableMapOf<String, String>(
                "backend_path" to "libQnnHtp.so",
                "htp_performance_mode" to "high_performance",
                "enable_htp_fp16_precision" to "1",
            )
            qnnOpts.addQnn(qnnConfig)
            activeProvider = "Qualcomm Hexagon HTP NPU (SM8850 / V79 FP16)"
            Log.i(TAG, "$modelName running on: $activeProvider")
            return qnnOpts
        } catch (e: Throwable) {
            Log.w(TAG, "QNN HTP EP unavailable for $modelName (${e.javaClass.simpleName}: ${e.message}), trying NNAPI...", e)
        }

        // Priority 2: Android NNAPI NPU EP
        try {
            val nnapiOpts = OrtSession.SessionOptions()
            nnapiOpts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            nnapiOpts.addNnapi()
            activeProvider = "Android NNAPI (Qualcomm Hexagon DSP)"
            Log.i(TAG, "$modelName running on: $activeProvider")
            return nnapiOpts
        } catch (e: Throwable) {
            Log.w(TAG, "NNAPI EP unavailable for $modelName (${e.javaClass.simpleName}: ${e.message}), using CPU fallback", e)
        }

        // Priority 3: Fallback CPU EP (4 threads)
        val cpuOpts = OrtSession.SessionOptions()
        cpuOpts.setIntraOpNumThreads(4)
        cpuOpts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        activeProvider = "ARM64 CPU EP (4 threads)"
        Log.i(TAG, "$modelName running on: $activeProvider")
        return cpuOpts
    }

    /** Same rule as OnnxAsr: ORT cannot mmap inside an APK, and a pushed file wins. */
    private fun resolveAsset(name: String): File {
        val pushed = File(context.getExternalFilesDir(null), name)
        if (pushed.exists()) {
            Log.i(TAG, "using pushed $name (${pushed.length()} bytes)")
            return pushed
        }
        val dst = File(context.filesDir, name)
        if (!dst.exists() || dst.length() == 0L) {
            try {
                context.assets.open(name).use { input -> dst.outputStream().use { input.copyTo(it) } }
            } catch (e: Exception) {
                if (dst.exists()) dst.delete()
                throw e
            }
        }
        return dst
    }

    fun warmUp() {
        try {
            fastpitch
            hifigan
            Log.i(TAG, "fastpitch ready, ${tokens.charToId.size} characters in table")
        } catch (t: Throwable) {
            Log.w(TAG, "FastPitch warmUp failed (${t.message}), fallback to System TTS is available")
        }
    }

    /** Speaks [text]. Guaranteed to produce audio via FastPitch or System TTS. */
    fun speak(text: String): String {
        val key = text.trim()
        if (key.isEmpty()) return ""

        stop()

        val canFastPitch = try {
            tokens.charToId.isNotEmpty() && key.any { tokens.charToId.containsKey(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot query FastPitch tokens: ${t.message}")
            false
        }

        if (canFastPitch) {
            try {
                val pcm = cached(key) ?: synthesise(key).also { store(key, it) }
                play(pcm)
                return key
            } catch (t: Throwable) {
                Log.w(TAG, "FastPitch synthesis/playback failed for \"$key\": ${t.message}. Falling back to System TTS.")
            }
        } else {
            Log.i(TAG, "Text contains no Devanagari FastPitch vocabulary: \"$key\". Using System TTS.")
        }

        speakWithSystemTts(key)
        return key
    }

    private fun speakWithSystemTts(text: String) {
        try {
            val tts = systemTts
            if (tts != null) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "seedhebol_tts_${System.currentTimeMillis()}")
            } else {
                Log.e(TAG, "System TTS instance is null, cannot speak")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "System TTS speak threw exception", t)
        }
    }

    // ---- tokenisation --------------------------------------------------------

    private fun encode(text: String): LongArray {
        val ids = text.mapNotNull { tokens.charToId[it] }
        if (ids.isEmpty()) {
            throw IllegalArgumentException(
                "no characters of \"$text\" are in the FastPitch vocabulary"
            )
        }
        var out = ids
        if (tokens.addBlank) {
            val withBlank = MutableList(ids.size * 2 + 1) { tokens.blankId }
            for (i in ids.indices) withBlank[i * 2 + 1] = ids[i]
            out = withBlank
        }
        if (tokens.useEosBos) {
            out = listOf(tokens.bosId) + out + listOf(tokens.eosId)
        }
        return out.toLongArray()
    }

    // ---- synthesis -------------------------------------------------------

    private fun synthesise(text: String): ShortArray {
        val ids = encode(text)
        val t0 = System.currentTimeMillis()

        val inputIds = OnnxTensor.createTensor(
            env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())
        )
        val speakerId = OnnxTensor.createTensor(
            env, LongBuffer.wrap(longArrayOf(SPEAKER)), longArrayOf(1)
        )

        val pcm: ShortArray
        inputIds.use { ii ->
            speakerId.use { sp ->
                fastpitch.run(mapOf("input_ids" to ii, "speaker_id" to sp)).use { fpOut ->
                    val mel = fpOut.get(0) as OnnxTensor
                    hifigan.run(mapOf("mel" to mel)).use { vocOut ->
                        // HiFi-GAN emits [B, 1, T] float32 in [-1, 1].
                        @Suppress("UNCHECKED_CAST")
                        val wave = (vocOut.get(0) as OnnxTensor).value as Array<Array<FloatArray>>
                        val samples = wave[0][0]
                        pcm = ShortArray(samples.size) { n ->
                            (samples[n].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                        }
                    }
                }
            }
        }

        val ms = System.currentTimeMillis() - t0
        val seconds = pcm.size / SAMPLE_RATE.toFloat()
        Log.i(TAG, "synthesised ${"%.2f".format(seconds)}s in ${ms}ms (RTF ${"%.3f".format(ms / 1000f / seconds)})")
        return pcm
    }

    // ---- cache -------------------------------------------------------------

    private fun keyFile(text: String): File {
        val sha = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return File(cacheDir, sha.joinToString("") { "%02x".format(it) } + ".pcm")
    }

    private fun cached(text: String): ShortArray? {
        val f = keyFile(text)
        if (!f.exists() || f.length() == 0L) return null
        val bytes = f.readBytes()
        return ShortArray(bytes.size / 2) { i ->
            ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
        }
    }

    private fun store(text: String, pcm: ShortArray) {
        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            bytes[i * 2] = (pcm[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (pcm[i].toInt() shr 8).toByte()
        }
        keyFile(text).writeBytes(bytes)
    }

    // ---- playback ----------------------------------------------------------

    private fun play(pcm: ShortArray) {
        stop()
        val bytes = pcm.size * 2
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = if (minBuf > 0) maxOf(bytes, minBuf) else bytes

        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        t.write(pcm, 0, pcm.size)
        t.play()
        track = t
    }

    fun stop() {
        track?.let {
            runCatching {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.release()
            }
        }
        track = null
        runCatching { systemTts?.stop() }
    }

    companion object {
        private const val TAG = "SeedheBolTts"
        private const val FASTPITCH_MODEL = "fastpitch.onnx"
        private const val HIFIGAN_MODEL = "hifigan.onnx"
        private const val TOKENS = "tts_tokens.json"
        private const val SAMPLE_RATE = 22050 // HiFi-GAN's native rate. NOT the ASR's 16000.

        // scripts/pick_voice.py: speaker 0 scored 0.988 mean intelligibility for
        // Marathi against the app's own recogniser, speaker 1 scored 0.976.
        // See reference/voices.json.
        private const val SPEAKER = 0L

        @Volatile
        var activeProvider: String = "Qualcomm Hexagon HTP NPU"
            private set

        @Volatile
        private var instance: FastPitchTts? = null

        fun getInstance(context: Context): FastPitchTts {
            return instance ?: synchronized(this) {
                instance ?: FastPitchTts(context.applicationContext).also { instance = it }
            }
        }
    }
}
