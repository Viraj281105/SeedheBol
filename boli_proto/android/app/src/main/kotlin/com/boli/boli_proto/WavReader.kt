package com.boli.boli_proto

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal RIFF/WAVE reader for the bundled sample: 16kHz mono PCM16 only.
 *
 * It asserts the format rather than resampling. Silently accepting 44.1kHz is
 * CLAUDE.md Trap 3, and it fails as bad transcripts rather than as an error.
 */
object WavReader {

    fun read(stream: InputStream): FloatArray {
        val bytes = stream.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        require(bytes.size > 44) { "file too short to be a WAV" }
        require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a RIFF/WAVE file" }

        var pos = 12
        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataSize = 0

        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = buf.getInt(pos + 4)
            when (id) {
                "fmt " -> {
                    channels = buf.getShort(pos + 10).toInt()
                    sampleRate = buf.getInt(pos + 12)
                    bitsPerSample = buf.getShort(pos + 22).toInt()
                }
                "data" -> {
                    dataOffset = pos + 8
                    dataSize = size
                }
            }
            if (dataOffset >= 0) break
            pos += 8 + size + (size and 1) // chunks are word-aligned
        }

        require(dataOffset >= 0) { "no data chunk" }
        require(sampleRate == 16000) { "expected 16000 Hz, got $sampleRate (Trap 3)" }
        require(channels == 1) { "expected mono, got $channels channels" }
        require(bitsPerSample == 16) { "expected PCM16, got $bitsPerSample bits" }

        val n = minOf(dataSize, bytes.size - dataOffset) / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            out[i] = buf.getShort(dataOffset + i * 2) / 32768f
        }
        return out
    }
}
