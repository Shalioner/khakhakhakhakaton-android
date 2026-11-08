package ru.neurosteno.core.dsp

import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Нормализация громкости готового WAV PCM16 моно «на месте» (после записи с микрофона).
 *
 * Источник VOICE_RECOGNITION по CDD идёт без AGC, поэтому речь в файле тихая (−30…−40 dBFS),
 * и при воспроизведении её плохо слышно. Здесь один общий коэффициент (как Loudness.gain в конвейере,
 * без клиппинга, не больше maxGain): форма сигнала не меняется, а конвейер перед ASR всё равно
 * нормализует громкость, поэтому на распознавание это не влияет.
 */
object WavGain {
    private const val FRAME = 480 // 30 мс на 16 кГц

    /** @return применённый коэффициент (1 — файл не менялся). */
    fun normalizePcm16(raf: RandomAccessFile, dataOffset: Long, dataLen: Long, maxGain: Float = 8f): Float {
        if (dataLen < FRAME * 2) return 1f
        val chunk = ByteArray(FRAME * 2 * 256)
        val rms = ArrayList<Float>((dataLen / (FRAME * 2)).toInt() + 1)
        var peak = 0
        var acc = 0.0
        var n = 0
        forEachChunk(raf, dataOffset, dataLen, chunk) { len ->
            var i = 0
            while (i + 1 < len) {
                val v = (chunk[i].toInt() and 0xff) or (chunk[i + 1].toInt() shl 8)
                peak = maxOf(peak, abs(v))
                val f = v / 32768.0
                acc += f * f
                if (++n == FRAME) { rms.add(sqrt(acc / n).toFloat()); acc = 0.0; n = 0 }
                i += 2
            }
        }
        val g = Loudness.gain(rms.toFloatArray(), peak / 32768f, maxGain = maxGain)
        if (g < 1.05f) return 1f
        forEachChunk(raf, dataOffset, dataLen, chunk) { len ->
            var i = 0
            while (i + 1 < len) {
                val v = (chunk[i].toInt() and 0xff) or (chunk[i + 1].toInt() shl 8)
                val s = (v * g).roundToInt().coerceIn(-32768, 32767)
                chunk[i] = (s and 0xff).toByte(); chunk[i + 1] = (s shr 8 and 0xff).toByte()
                i += 2
            }
            raf.seek(raf.filePointer - len)
            raf.write(chunk, 0, len)
        }
        return g
    }

    private inline fun forEachChunk(raf: RandomAccessFile, offset: Long, len: Long, buf: ByteArray, block: (Int) -> Unit) {
        raf.seek(offset)
        var left = len
        while (left > 0) {
            val want = minOf(buf.size.toLong(), left).toInt() and 1.inv()
            if (want == 0) break
            var got = 0
            while (got < want) {
                val r = raf.read(buf, got, want - got)
                if (r < 0) break
                got += r
            }
            if (got <= 0) break
            block(got and 1.inv())
            left -= got
            if (got < want) break
        }
    }
}
