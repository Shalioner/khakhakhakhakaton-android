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

/**
 * Динамическая нормализация речи (AGC) для 16 кГц: усиление подстраивается под огибающую громкости,
 * чтобы тихие слова посреди фразы не терялись на фоне громких (наблюдалось на записи с микрофона).
 * - огибающая по кадрам 10 мс: быстрая атака, спад ~150 мс;
 * - адаптация только на речи: кадры ниже noiseFloor*gateRatio держат текущее усиление (шум не раздувается);
 * - усиление ограничено [minGain, maxGain] и сглажено; на выходе мягкий лимитер (без клиппинга).
 * Потоковый: process() можно вызывать кусками.
 */
class SpeechAgc(
    private val noiseFloor: Float,
    private val target: Float = 0.08f,
    private val maxGain: Float = 8f,
    private val minGain: Float = 0.5f,
    private val gateRatio: Float = 3f,
) {
    private val frame = 160
    private var env = 0f
    private var gain = 1f
    private val buf = FloatArray(frame)
    private var bufN = 0

    fun process(x: FloatArray): FloatArray {
        val out = FloatArray(x.size)
        var o = 0
        var i = 0
        while (i < x.size) {
            val take = minOf(frame - bufN, x.size - i)
            System.arraycopy(x, i, buf, bufN, take)
            bufN += take; i += take
            if (bufN == frame) { o = emit(out, o, frame); bufN = 0 }
        }
        // хвост, не набравший кадр, обрабатываем с текущим усилением (без обновления огибающей)
        for (k in 0 until bufN) out[o++] = limit(buf[k] * gain)
        // хвост уже выведен — сбрасываем, чтобы не вывести повторно
        bufN = 0
        return out
    }

    private fun emit(out: FloatArray, start: Int, n: Int): Int {
        var s = 0.0
        for (k in 0 until n) s += buf[k] * buf[k]
        val rms = sqrt(s / n).toFloat()
        env = if (rms > env) env + 0.5f * (rms - env) else env * 0.94f + rms * 0.06f
        val target0 = if (env > noiseFloor * gateRatio && env > 1e-4f) (target / env).coerceIn(minGain, maxGain) else gain
        val prev = gain
        gain = prev + 0.15f * (target0 - prev)
        var o = start
        for (k in 0 until n) {
            val g = prev + (gain - prev) * k / n // линейная интерполяция усиления внутри кадра
            out[o++] = limit(buf[k] * g)
        }
        return o
    }

    /** Мягкий лимитер: линейно до 0.8, выше — плавное насыщение к 1.0. */
    private fun limit(v: Float): Float {
        val a = abs(v)
        if (a <= 0.8f) return v
        val y = 0.8f + 0.2f * kotlin.math.tanh(((a - 0.8f) / 0.2f).toDouble()).toFloat()
        return if (v < 0) -y else y
    }

    companion object {
        /** Порог шума: 10-й перцентиль RMS кадров (тишина/фон между словами). */
        fun noiseFloor(frameRms: FloatArray): Float {
            if (frameRms.isEmpty()) return 1e-3f
            val s = frameRms.sorted()
            return s[(s.size * 0.1).toInt().coerceAtMost(s.size - 1)].coerceAtLeast(1e-4f)
        }
    }
}
