package ru.neurosteno.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Сырой PCM16 LE моно 16 кГц на диске. Читается окнами, чтобы не держать час аудио во float в RAM. */
class PcmFile(val file: File, val sampleRate: Int = SAMPLE_RATE) {

    val numSamples: Long get() = file.length() / 2
    val durationMs: Long get() = numSamples * 1000 / sampleRate

    /** Читает [startSample, startSample+count) как float в [-1, 1]. */
    fun read(startSample: Long, count: Int): FloatArray {
        val s = startSample.coerceIn(0, numSamples)
        val n = minOf(count.toLong(), numSamples - s).toInt()
        if (n <= 0) return FloatArray(0)
        val bytes = ByteArray(n * 2)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(s * 2)
            raf.readFully(bytes)
        }
        val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(n) { sb.get(it) / 32768f }
    }

    fun readMs(startMs: Long, endMs: Long): FloatArray =
        read(startMs * sampleRate / 1000, ((endMs - startMs) * sampleRate / 1000).toInt())

    /** Весь файл во float (для диаризации). 1 час = 57.6M сэмплов = 230 МБ — вызывающий решает, можно ли. */
    fun readAll(): FloatArray = read(0, numSamples.toInt())

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
