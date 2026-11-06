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
    fun readAll(): FloatArray {
        // Блоками по 1 МБ прямо в итоговый массив: без промежуточного ByteArray на весь файл (B3 ревью:
        // было 6 байт/сэмпл пика, 345 МБ на час; теперь 4 байта/сэмпл + 1 МБ).
        val n = numSamples.toInt()
        val out = FloatArray(n)
        val bytes = ByteArray(1 shl 20)
        RandomAccessFile(file, "r").use { raf ->
            var pos = 0
            while (pos < n) {
                val cnt = minOf(bytes.size / 2, n - pos)
                raf.readFully(bytes, 0, cnt * 2)
                val sb = ByteBuffer.wrap(bytes, 0, cnt * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                for (i in 0 until cnt) out[pos + i] = sb.get(i) / 32768f
                pos += cnt
            }
        }
        return out
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
