package com.boli.boli_proto

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * MicRecorder — 16kHz mono PCM16 audio capture with real-time Adaptive VAD.
 *
 * Performance features:
 *   - Energy-based Voice Activity Detection (RMS thresholding).
 *   - Ambient noise calibration during first 150ms.
 *   - Early silence cutoff: terminates recording once the user finishes speaking
 *     and pauses for ~650ms, eliminating up to 3.5s of dead-air waiting.
 *   - Fallback to caller's requested max [seconds] if no speech is detected.
 */
object MicRecorder {

    private const val TAG = "SeedheBolAsr"
    private const val SAMPLE_RATE = 16000
    private const val CHUNK_SAMPLES = 800 // 50ms at 16,000 Hz

    fun record(seconds: Float = 6f, enableVad: Boolean = true): FloatArray {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBuf > 0) { "AudioRecord unavailable (getMinBufferSize=$minBuf)" }

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, SAMPLE_RATE * 2)
        )

        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialise" }
            // Trap 3: phone mics often default to 44.1kHz. Assert what we actually got.
            check(recorder.sampleRate == SAMPLE_RATE) {
                "mic opened at ${recorder.sampleRate} Hz, need $SAMPLE_RATE"
            }

            val total = (SAMPLE_RATE * seconds).toInt()
            val pcm = ShortArray(total)
            recorder.startRecording()

            var read = 0
            var isSpeaking = false
            var speechChunks = 0
            var silenceChunksAfterSpeech = 0
            var ambientRmsAccumulator = 0.0
            var ambientChunkCount = 0
            var speechThreshold = 500.0

            while (read < total) {
                val toRead = minOf(CHUNK_SAMPLES, total - read)
                val n = recorder.read(pcm, read, toRead)
                if (n <= 0) break

                if (enableVad) {
                    // Compute chunk RMS energy
                    var sum = 0.0
                    for (i in read until (read + n)) {
                        val s = pcm[i].toDouble()
                        sum += s * s
                    }
                    val chunkRms = Math.sqrt(sum / n)

                    // Calibrate ambient baseline during first 150ms (3 chunks)
                    if (ambientChunkCount < 3) {
                        ambientRmsAccumulator += chunkRms
                        ambientChunkCount++
                        if (ambientChunkCount == 3) {
                            val ambientBase = ambientRmsAccumulator / 3.0
                            speechThreshold = maxOf(450.0, ambientBase * 2.2)
                            Log.d(TAG, "VAD calibrated ambientBase=%.1f, speechThreshold=%.1f".format(ambientBase, speechThreshold))
                        }
                    } else {
                        if (chunkRms > speechThreshold) {
                            speechChunks++
                            if (speechChunks >= 2) {
                                isSpeaking = true
                                silenceChunksAfterSpeech = 0
                            }
                        } else {
                            if (isSpeaking) {
                                silenceChunksAfterSpeech++
                                // 13 chunks of 50ms = 650ms of silence after at least 400ms of speech
                                if (silenceChunksAfterSpeech >= 13 && read >= (SAMPLE_RATE * 0.4).toInt()) {
                                    Log.i(TAG, "VAD early-cutoff: speech ended, 650ms trailing silence detected ($read samples in ${read * 1000 / SAMPLE_RATE}ms)")
                                    read += n
                                    break
                                }
                            }
                        }
                    }
                }

                read += n
            }
            recorder.stop()
            Log.i(TAG, "captured $read samples @ ${recorder.sampleRate}Hz (duration: ${"%.2f".format(read / SAMPLE_RATE.toFloat())}s)")

            // PCM16 -> float [-1,1], the same normalisation the WAV path uses.
            return FloatArray(read) { pcm[it] / 32768f }
        } finally {
            recorder.release()
        }
    }
}
