package com.boli.boli_proto

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.LongBuffer
import java.security.MessageDigest

/**
 * Marathi speech synthesis — AI4Bharat FastPitch + HiFi-GAN, 22.05 kHz.
 *
 *   text -> character ids -> fastpitch.onnx -> mel [1,80,T]
 *                          -> hifigan.onnx  -> waveform -> AudioTrack
 *
 * Replaces the Piper path (OnnxTts.kt, kept but unused). The model is
 * CHARACTER based (Coqui config: use_phonemes=false), so the text goes
 * straight in — no phonemiser, no espeak-ng, no fixed table of speakable
 * phrases. Any text whose characters are in tokens.json can be spoken.
 *
 * SAMPLE RATE (CLAUDE.md Trap A): this path is 22050 Hz end to end, same
 * contract as the Piper path it replaces, and still shares no code with the
 * 16 kHz recognition path.
 *
 * The tokeniser here is a Kotlin port of scripts/verify_tts.py's
 * make_encoder(), checked against Coqui's own tokenizer for Marathi
 * (scripts/verify_fastpitch.py: 6/6 phrases match). Multi-character vocab
 * entries such as "<PAD>" are structurally unreachable by a per-character
 * scan in Coqui's own encoder too, so they are dropped when the table is
 * loaded rather than causing a lookup that could never succeed.
 */
class FastPitchTts(private val context: Context) {

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    private val fastpitch: OrtSession by lazy {
        env.createSession(resolveAsset(FASTPITCH_MODEL).absolutePath, cpuOptions())
    }
    private val hifigan: OrtSession by lazy {
        env.createSession(resolveAsset(HIFIGAN_MODEL).absolutePath, cpuOptions())
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
        val json = JSONObject(context.assets.open(TOKENS).bufferedReader().use { it.readText() })
        val c2i = json.getJSONObject("char_to_id")
        val map = buildMap {
            for (key in c2i.keys()) {
                // Only single-character entries can ever match `for (c in text)`
                // — mirrors scripts/verify_tts.py's make_encoder() exactly.
                if (key.length == 1) put(key[0], c2i.getLong(key))
            }
        }
        Tokens(
            charToId = map,
            addBlank = json.getBoolean("add_blank"),
            useEosBos = json.getBoolean("use_eos_bos"),
            blankId = json.optLong("blank_id", 0L),
            bosId = json.optLong("bos_id", 0L),
            eosId = json.optLong("eos_id", 0L),
        )
    }

    private val cacheDir: File by lazy {
        File(context.filesDir, "tts_cache").apply { mkdirs() }
    }

    private var track: AudioTrack? = null

    private fun cpuOptions() = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(4)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
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
            context.assets.open(name).use { input -> dst.outputStream().use { input.copyTo(it) } }
        }
        return dst
    }

    fun warmUp() {
        fastpitch
        hifigan
        Log.i(TAG, "fastpitch ready, ${tokens.charToId.size} characters in table")
    }

    /** Speaks [text]. Returns the text actually spoken, or throws if unspeakable. */
    fun speak(text: String): String {
        val key = text.trim()
        val pcm = cached(key) ?: synthesise(key).also { store(key, it) }
        play(pcm)
        return key
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
    // Lesson phrases repeat constantly, so the second play of anything is a
    // file read rather than two forward passes.

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
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        t.write(pcm, 0, pcm.size)
        t.play()
        track = t
    }

    fun stop() {
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
    }

    companion object {
        private const val TAG = "BoliTts"
        private const val FASTPITCH_MODEL = "fastpitch.onnx"
        private const val HIFIGAN_MODEL = "hifigan.onnx"
        private const val TOKENS = "tts_tokens.json"
        private const val SAMPLE_RATE = 22050 // HiFi-GAN's native rate. NOT the ASR's 16000.

        // scripts/pick_voice.py: speaker 0 scored 0.988 mean intelligibility for
        // Marathi against the app's own recogniser, speaker 1 scored 0.976.
        // See reference/voices.json.
        private const val SPEAKER = 0L
    }
}
