package ru.neurosteno.core.dsp

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class DspTest {

    private fun sine(freq: Double, rate: Int, n: Int) = FloatArray(n) { (0.5 * sin(2 * PI * freq * it / rate)).toFloat() }

    private fun rms(x: FloatArray, from: Int, to: Int): Double = sqrt((from until to).sumOf { x[it].toDouble() * x[it] } / (to - from))

    @Test
    fun resample48kTo16kKeepsLengthAndTone() {
        val inp = sine(440.0, 48_000, 48_000)
        val rs = StreamingResampler(48_000, 16_000)
        // подаём кусками разного размера, как приходит из MediaCodec
        val parts = listOf(0 until 1000, 1000 until 20_000, 20_000 until 48_000)
        val out = parts.mapIndexed { i, r -> rs.process(inp.copyOfRange(r.first, r.last + 1), flush = i == parts.lastIndex) }
            .reduce { a, b -> a + b }
        assertTrue(abs(out.size - 16_000) <= 20, "len=${out.size}")
        val ref = sine(440.0, 16_000, out.size)
        assertEquals(rms(ref, 200, 15_000), rms(out, 200, 15_000), 0.02)
    }

    @Test
    fun downsamplingAttenuatesAliases() {
        // 7 кГц тон при 44.1 → 16 кГц должен почти исчезнуть (Найквист 8 кГц, срез ~7.6) — проверим 12 кГц
        val inp = sine(12_000.0, 44_100, 44_100)
        val out = StreamingResampler(44_100, 16_000).process(inp, flush = true)
        assertTrue(rms(out, 200, out.size - 200) < 0.03)
    }

    @Test
    fun dcRemoved() {
        val x = FloatArray(16_000) { 0.3f }
        val y = DcBlocker().process(x)
        assertTrue(abs(y.last()) < 0.01f)
    }

    @Test
    fun loudnessGainRespectsPeak() {
        val g = Loudness.gain(FloatArray(100) { 0.01f }, peak = 0.5f)
        assertTrue(g <= 0.97f / 0.5f + 1e-4)
        assertEquals(1f, Loudness.gain(FloatArray(0), 0.5f))
    }
}

class ResamplerSpeedTest {
    @Test
    fun tenMinutesOf48kUnderTwoSeconds() {
        val x = FloatArray(48_000 * 600) { kotlin.math.sin(it * 0.01).toFloat() * 0.3f }
        val t0 = System.nanoTime()
        val rs = StreamingResampler(48_000, 16_000)
        var total = 0
        var i = 0
        while (i < x.size) { val e = minOf(i + 4096, x.size); total += rs.process(x.copyOfRange(i, e), flush = e == x.size).size; i = e }
        val ms = (System.nanoTime() - t0) / 1_000_000
        kotlin.test.assertTrue(kotlin.math.abs(total - 16_000 * 600) < 50, "len=$total")
        kotlin.test.assertTrue(ms < 2000, "resample 10 min took $ms ms")
    }
}

class SpeechAgcTest {
    private fun tone(amp: Float, n: Int) = FloatArray(n) { (amp * kotlin.math.sin(it * 0.2)).toFloat() }
    private fun rms(x: FloatArray) = kotlin.math.sqrt(x.sumOf { it.toDouble() * it } / x.size)

    @Test
    fun quietWordAfterLoudIsBoosted() {
        val loud = tone(0.5f, 16_000); val quiet = tone(0.02f, 16_000)
        val agc = SpeechAgc(noiseFloor = 0.001f)
        val out = agc.process(loud + quiet)
        val q = out.copyOfRange(16_000 + 8000, 32_000)
        kotlin.test.assertTrue(rms(q) > rms(quiet) * 3, "quiet part should be boosted >3x, rms=${rms(q)} in=${rms(quiet)}")
        kotlin.test.assertTrue(out.all { kotlin.math.abs(it) <= 1f })
    }

    @Test
    fun silenceIsNotAmplified() {
        val agc = SpeechAgc(noiseFloor = 0.01f)
        val noise = FloatArray(16_000) { ((it * 7919 % 100) - 50) / 50f * 0.01f }
        val out = agc.process(noise)
        kotlin.test.assertTrue(rms(out) < rms(noise) * 1.5)
    }

    @Test
    fun chunkedEqualsLength() {
        val x = tone(0.1f, 10_007)
        val agc = SpeechAgc(0.001f)
        val a = agc.process(x.copyOfRange(0, 5000)); val b = agc.process(x.copyOfRange(5000, 10_007))
        kotlin.test.assertEquals(10_007, a.size + b.size)
    }
}
