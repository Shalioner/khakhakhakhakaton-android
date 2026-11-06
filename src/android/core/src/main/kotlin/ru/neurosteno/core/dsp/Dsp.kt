package ru.neurosteno.core.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Потоковый ресемплер: windowed-sinc (окно Ханна) с антиалиасингом при понижении частоты.
 * Чистый Kotlin, тестируется на JVM.
 */
class StreamingResampler(private val inRate: Int, private val outRate: Int, private val halfTaps: Int = 16) {
    private val ratio = inRate.toDouble() / outRate
    private val cutoff = if (outRate < inRate) outRate.toDouble() / inRate * 0.95 else 0.95
    private var history = FloatArray(0)
    /** Позиция следующего выходного сэмпла во входной шкале относительно начала history. */
    private var pos = 0.0
    private var started = false

    fun process(input: FloatArray, flush: Boolean = false): FloatArray {
        if (inRate == outRate) return input
        if (!started) {
            history = FloatArray(halfTaps) // нулевой паддинг слева
            pos = halfTaps.toDouble()
            started = true
        }
        val buf = history + input + (if (flush) FloatArray(halfTaps) else FloatArray(0))
        val limit = buf.size - halfTaps - 1
        val n = if (pos < limit) ((limit - pos) / ratio).toInt() + 2 else 0
        val out = FloatArray(n)
        var cnt = 0
        while (pos < limit && cnt < n) {
            out[cnt++] = interp(buf, pos)
            pos += ratio
        }
        val keepFrom = maxOf(0, floor(pos).toInt() - halfTaps)
        history = buf.copyOfRange(keepFrom, buf.size)
        pos -= keepFrom
        return if (cnt == n) out else out.copyOf(cnt)
    }

    /**
     * Полифазная таблица: веса ядра для PHASES дробных сдвигов, нормированные к сумме 1.
     * Раньше sin/cos считались на каждый тап каждого сэмпла — минуты на часовом mp3.
     */
    private val taps = 2 * halfTaps
    private val table: Array<FloatArray> = Array(PHASES + 1) { ph ->
        val frac = ph.toDouble() / PHASES
        val w = DoubleArray(taps) { t ->
            val x = (t - halfTaps + 1) - frac
            val sinc = if (abs(x) < 1e-9) 1.0 else sin(PI * cutoff * x) / (PI * cutoff * x)
            sinc * (0.5 + 0.5 * cos(PI * x / (halfTaps + 1)))
        }
        val s = w.sum()
        FloatArray(taps) { (w[it] / s).toFloat() }
    }

    private fun interp(buf: FloatArray, p: Double): Float {
        val center = floor(p).toInt()
        val w = table[((p - center) * PHASES + 0.5).toInt()]
        val base = center - halfTaps + 1
        var acc = 0f
        if (base >= 0 && base + taps <= buf.size) {
            for (t in 0 until taps) acc += buf[base + t] * w[t]
        } else {
            for (t in 0 until taps) { val idx = base + t; if (idx in buf.indices) acc += buf[idx] * w[t] }
        }
        return acc
    }

    private companion object {
        const val PHASES = 512
    }
}

/** Однополюсный DC-фильтр (high-pass ~10 Гц на 16 кГц). */
class DcBlocker(private val r: Float = 0.996f) {
    private var x1 = 0f
    private var y1 = 0f
    fun process(x: FloatArray): FloatArray {
        val y = FloatArray(x.size)
        for (i in x.indices) {
            val v = x[i] - x1 + r * y1
            x1 = x[i]; y1 = v; y[i] = v
        }
        return y
    }
}

object Loudness {
    /**
     * Коэффициент усиления к целевому RMS по «громким» (речевым) кадрам.
     * Речевые кадры = кадры с RMS выше 60-го перцентиля и выше порога тишины;
     * усиление ограничено так, чтобы пик не превысил peakLimit и не больше maxGain.
     */
    fun gain(frameRms: FloatArray, peak: Float, targetRms: Float = 0.08f, peakLimit: Float = 0.97f, maxGain: Float = 20f): Float {
        if (frameRms.isEmpty() || peak <= 0f) return 1f
        val sorted = frameRms.sorted()
        val p60 = sorted[(sorted.size * 0.6).toInt().coerceAtMost(sorted.size - 1)]
        val speech = frameRms.filter { it >= p60 && it > 1e-4f }
        if (speech.isEmpty()) return 1f
        val speechRms = sqrt(speech.map { it * it }.average()).toFloat()
        val g = targetRms / speechRms
        return minOf(g, peakLimit / peak, maxGain).coerceAtLeast(0.05f)
    }
}
