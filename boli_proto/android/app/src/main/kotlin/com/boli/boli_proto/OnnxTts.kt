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
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest

/**
 * Marathi speech synthesis — Piper (VITS), 22.05 kHz.
 *
 *   text -> phoneme ids (precomputed) -> piper_mr.onnx -> waveform -> AudioTrack
 *
 * Structure deliberately mirrors OnnxAsr: lazy session, the same asset
 * resolution, the same warm-up contract.
 *
 * SAMPLE RATE (CLAUDE.md Trap A): this path is 22050 Hz end to end and shares
 * no code with the 16 kHz recognition path. There is no resampling here at all
 * — the synthesiser's native rate goes straight to AudioTrack. Do not factor
 * anything in this file together with MicRecorder or MelFrontend; the two rates
 * existing side by side is exactly how silent pitch bugs get introduced.
 *
 * Phonemisation happens at build time (scripts/tts_prepare.py) because Piper
 * needs espeak-ng IPA and shipping espeak-ng would mean an NDK build. The
 * consequence is that only phrases in the bundled table can be spoken.
 */
class OnnxTts(private val context: Context) {

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    private val session: OrtSession by lazy {
        env.createSession(resolveAsset(MODEL).absolutePath, cpuOptions())
    }

    /** text -> phoneme id sequence, built by scripts/tts_prepare.py */
    private val phonemes: Map<String, LongArray> by lazy {
        val json = JSONObject(context.assets.open(TABLE).bufferedReader().use { it.readText() })
        buildMap {
            for (key in json.keys()) {
                val arr = json.getJSONArray(key)
                put(key, LongArray(arr.length()) { arr.getLong(it) })
            }
        }
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
        if (pushed.exists()) return pushed
        val dst = File(context.filesDir, name)
        if (!dst.exists() || dst.length() == 0L) {
            context.assets.open(name).use { input -> dst.outputStream().use { input.copyTo(it) } }
        }
        return dst
    }

    fun warmUp() {
        session
        phonemes
        Log.i(TAG, "tts ready, ${phonemes.size} phrases in table")
    }

    /** Speaks [text]. Returns the phrase actually spoken, or throws if unknown. */
    fun speak(text: String): String {
        val key = text.trim()
        val pcm = cached(key) ?: synthesise(key).also { store(key, it) }
        play(pcm)
        return key
    }

    // ---- synthesis ---------------------------------------------------------

    private fun synthesise(text: String): ShortArray {
        val ids = phonemes[text]
            ?: throw IllegalArgumentException(
                "no phonemes for \"$text\" — add it to lib/data.dart and re-run scripts/tts_prepare.py"
            )
        val t0 = System.currentTimeMillis()

        val input = OnnxTensor.createTensor(
            env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())
        )
        val lengths = OnnxTensor.createTensor(
            env, LongBuffer.wrap(longArrayOf(ids.size.toLong())), longArrayOf(1)
        )
        // [noise_scale, length_scale, noise_w] — Piper's published defaults.
        val scales = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(floatArrayOf(0.667f, 1.0f, 0.8f)), longArrayOf(3)
        )
        val sid = OnnxTensor.createTensor(
            env, LongBuffer.wrap(longArrayOf(SPEAKER)), longArrayOf(1)
        )

        val pcm: ShortArray
        input.use { i ->
            lengths.use { l ->
                scales.use { s ->
                    sid.use { sp ->
                        session.run(
                            mapOf("input" to i, "input_lengths" to l, "scales" to s, "sid" to sp)
                        ).use { out ->
                            // Piper emits [B, 1, 1, T] float32 in [-1, 1].
                            @Suppress("UNCHECKED_CAST")
                            val wave = (out.get(0) as OnnxTensor).value
                                as Array<Array<Array<FloatArray>>>
                            val samples = wave[0][0][0]
                            pcm = ShortArray(samples.size) { n ->
                                (samples[n].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                            }
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
    // file read rather than a forward pass.

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
        private const val MODEL = "piper_mr.onnx"
        private const val TABLE = "tts_phonemes.json"
        private const val SAMPLE_RATE = 22050 // Piper's native rate. NOT the ASR's 16000.
        private const val SPEAKER = 3L        // one of nine Marathi speakers
    }
}
