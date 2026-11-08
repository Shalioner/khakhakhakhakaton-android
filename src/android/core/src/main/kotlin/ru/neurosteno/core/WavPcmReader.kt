package ru.neurosteno.core

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Потоковый RIFF/WAVE PCM-декодер. Потоком владеет вызывающая сторона. */
class WavPcmReader(private val input: InputStream) {
    val sampleRate: Int
    val channels: Int
    private val bits: Int
    private val format: Int
    private val blockAlign: Int
    private var remaining: Long
    private var totalBytes: Long = 0
    private var truncated = false

    init {
        val riff = readExact(12)
        require(String(riff, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(riff, 8, 4, Charsets.US_ASCII) == "WAVE") { "Ожидался RIFF/WAVE" }
        var fmt: ByteArray? = null
        var dataSize: Long
        while (true) {
            val header = readExact(8)
            val id = String(header, 0, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(4).toLong() and 0xffffffffL
            if (id == "data") { dataSize = size; break }
            if (id == "fmt ") {
                require(size in 16..65536) { "Неверный размер fmt" }
                fmt = readExact(size.toInt())
            } else {
                skipExact(size)
            }
            if (size % 2 != 0L) skipExact(1)
        }
        val bytes = requireNotNull(fmt) { "В WAV отсутствует fmt перед data" }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var tag = bb.getShort(0).toInt() and 0xffff
        channels = bb.getShort(2).toInt() and 0xffff
        sampleRate = bb.getInt(4)
        blockAlign = bb.getShort(12).toInt() and 0xffff
        bits = bb.getShort(14).toInt() and 0xffff
        if (tag == 0xfffe) {
            require(bytes.size >= 40 && bb.getShort(16).toInt() >= 22) { "Повреждён extensible fmt" }
            val guidTail = byteArrayOf(0, 0, 16, 0, -128, 0, 0, -86, 0, 56, -101, 113)
            require(bytes.copyOfRange(28, 40).contentEquals(guidTail)) { "Неизвестный WAV subformat" }
            tag = bb.getInt(24)
        }
        format = tag
        require(channels in 1..2 && sampleRate > 0) { "Неверная частота или число каналов WAV" }
        require((format == 1 && bits in listOf(8, 16, 24, 32)) || (format == 3 && bits == 32)) {
            "WAV поддерживает PCM 8/16/24/32 и float32"
        }
        require(blockAlign == channels * (bits / 8)) { "Неверный размер кадра WAV" }
        remaining = dataSize
        totalBytes = dataSize
    }

    /** Доля прочитанных данных 0..1 (для индикатора прогресса). */
    fun fraction(): Float =
        if (totalBytes <= 0L) 0f else (1f - remaining.toFloat() / totalBytes).coerceIn(0f, 1f)

    fun readMono(maxFrames: Int = 4096): FloatArray {
        require(maxFrames in 1..65536)
        if (truncated) throw EOFException("WAV обрывается внутри data")
        if (remaining == 0L) return FloatArray(0)
        val bytes = ByteArray(minOf(remaining, maxFrames.toLong() * blockAlign).toInt())
        var count = 0
        while (count < bytes.size) {
            val n = input.read(bytes, count, bytes.size - count)
            if (n < 0) break
            if (n == 0) {
                val value = input.read()
                if (value < 0) break
                bytes[count++] = value.toByte()
            } else count += n
        }
        remaining -= count
        truncated = count < bytes.size || count % blockAlign != 0
        val frames = count / blockAlign
        if (frames == 0 && truncated) throw EOFException("WAV обрывается внутри кадра")
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(frames) {
            var sum = 0f
            repeat(channels) {
                sum += when {
                    format == 3 -> bb.float.also { require(it.isFinite()) { "WAV содержит NaN/Infinity" } }
                    bits == 8 -> ((bb.get().toInt() and 255) - 128) / 128f
                    bits == 16 -> bb.short / 32768f
                    bits == 24 -> ((bb.get().toInt() and 255) or
                        ((bb.get().toInt() and 255) shl 8) or (bb.get().toInt() shl 16)) / 8388608f
                    else -> bb.int / 2147483648f
                }
            }
            sum / channels
        }
    }

    private fun readExact(size: Int): ByteArray {
        val bytes = ByteArray(size)
        for (i in bytes.indices) {
            val value = input.read()
            if (value < 0) throw EOFException("Повреждён заголовок WAV")
            bytes[i] = value.toByte()
        }
        return bytes
    }

    private fun skipExact(size: Long) {
        var left = size
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) left -= skipped
            else {
                if (input.read() < 0) throw EOFException("Повреждён WAV chunk")
                left--
            }
        }
    }
}
