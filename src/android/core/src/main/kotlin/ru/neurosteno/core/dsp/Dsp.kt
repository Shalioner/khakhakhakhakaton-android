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
        val out = ArrayList<Float>((input.size / ratio).toInt() + 4)
        val limit = buf.size - halfTaps - 1
        while (pos < limit) {
            out += interp(buf, pos)
            pos += ratio
        }
        val keepFrom = maxOf(0, floor(pos).toInt() - halfTaps)
        history = buf.copyOfRange(keepFrom, buf.size)
        pos -= keepFrom
        return out.toFloatArray()
    }

    private fun interp(buf: FloatArray, p: Double): Float {
        val center = floor(p).toInt()
        val frac = p - center
        var acc = 0.0
        var norm = 0.0
        for (k in -halfTaps + 1..halfTaps) {
            val idx = center + k
            if (idx < 0 || idx >= buf.size) continue
            val x = k - frac
            val sinc = if (abs(x) < 1e-9) 1.0 else sin(PI * cutoff * x) / (PI * cutoff * x)
            val win = 0.5 + 0.5 * cos(PI * x / (halfTaps + 1))
            val w = sinc * win
            acc += buf[idx] * w
            norm += w
        }
        return (acc / norm).toFloat()
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
