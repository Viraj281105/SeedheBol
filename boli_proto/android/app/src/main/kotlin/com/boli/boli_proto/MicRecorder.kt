package com.boli.boli_proto

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * T4 — fixed-window capture at 16kHz mono PCM16.
 *
 * No VAD, no streaming, no waveform (CLAUDE.md 3). Record, stop, transcribe.
 */
object MicRecorder {

    private const val TAG = "SeedheBolAsr"
    private const val SAMPLE_RATE = 16000

    fun record(seconds: Float = 6f): FloatArray {
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
            while (read < total) {
                val n = recorder.read(pcm, read, total - read)
                if (n <= 0) break
                read += n
            }
            recorder.stop()
            Log.i(TAG, "captured $read samples @ ${recorder.sampleRate}Hz")

            // PCM16 -> float [-1,1], the same normalisation the WAV path uses.
            return FloatArray(read) { pcm[it] / 32768f }
        } finally {
            recorder.release()
        }
    }
}
