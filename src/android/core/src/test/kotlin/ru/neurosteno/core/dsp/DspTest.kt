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
