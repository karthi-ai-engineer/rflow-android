package io.github.karthiaiengineer.rflow.core

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** 16-bit little-endian mono PCM: what the microphone gives and Gemini takes (16 kHz) or speaks (24 kHz). */
object Pcm {
    const val RATE = 16_000
    const val FRAME_BYTES = RATE / 10 * 2  // 100 ms, the frame Google recommends: 3,200 bytes
    const val VOICE_RATE = 24_000

    /** RMS loudness, 1.0 = full scale. */
    fun level(frame: ByteArray, size: Int = frame.size): Double {
        val n = size / 2
        if (n == 0) return 0.0
        var sum = 0.0
        for (i in 0 until n) {
            val s = sample(frame, i).toDouble()
            sum += s * s
        }
        return sqrt(sum / n) / 32768.0
    }

    fun dbfs(level: Double): Double = if (level <= 0) -120.0 else 20 * log10(level)

    /** Digital silence: every sample within [peak] (a muted microphone, nothing playing). */
    fun silent(frame: ByteArray, peak: Int = 32): Boolean {
        for (i in 0 until frame.size / 2) if (abs(sample(frame, i)) > peak) return false
        return true
    }

    fun sample(b: ByteArray, i: Int): Int = (b[2 * i].toInt() and 0xFF) or (b[2 * i + 1].toInt() shl 8)
}

/**
 * A WAV file written as the audio comes (16 kHz mono 16-bit), its header fixed on [close]: a meeting's recording. A
 * recording cut short (the phone off) still has its audio; [repair] fixes its header when it's opened next.
 */
class WavWriter(val file: File, private val rate: Int = Pcm.RATE) : AutoCloseable {
    private val out = RandomAccessFile(file, "rw").apply { setLength(0); write(header(rate, 0)) }
    var bytes = 0L
        private set

    val seconds get() = bytes / (rate * 2.0)

    fun write(pcm: ByteArray, size: Int = pcm.size) {
        out.write(pcm, 0, size)
        bytes += size
    }

    override fun close() {
        out.seek(0)
        out.write(header(rate, bytes))
        out.close()
    }

    companion object {
        fun header(rate: Int, dataBytes: Long): ByteArray {
            val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2)
                .putShort(2).putShort(16)
            b.put("data".toByteArray()).putInt(dataBytes.toInt())
            return b.array()
        }

        /** The header of a WAV whose writing was cut short, made to match its size. */
        fun repair(file: File, rate: Int = Pcm.RATE) {
            if (!file.exists() || file.length() < 44) return
            RandomAccessFile(file, "rw").use { it.seek(0); it.write(header(rate, file.length() - 44)) }
        }

        /** Seconds of audio in a 16 kHz WAV. */
        fun seconds(file: File, rate: Int = Pcm.RATE): Double = ((file.length() - 44).coerceAtLeast(0)) / (rate * 2.0)
    }
}
