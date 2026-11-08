package ru.neurosteno.core

import ru.neurosteno.core.dsp.WavGain
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.*

class WavGainTest {
    private fun write(f: File, samples: ShortArray) {
        RandomAccessFile(f, "rw").use { raf ->
            raf.setLength(0)
            raf.write(ByteArray(44) { 7 })
            val b = ByteArray(samples.size * 2)
            for (i in samples.indices) { val v = samples[i].toInt(); b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte() }
            raf.write(b)
        }
    }

    private fun read(f: File): ShortArray {
        val b = f.readBytes()
        return ShortArray((b.size - 44) / 2) { i -> ((b[44 + 2 * i].toInt() and 0xff) or (b[45 + 2 * i].toInt() shl 8)).toShort() }
    }

    private fun rms(x: ShortArray) = sqrt(x.map { (it / 32768.0).let { v -> v * v } }.average())

    @Test fun quietSpeechIsAmplifiedWithoutClippingAndHeaderKept() {
        val f = File.createTempFile("gain", ".wav")
        try {
            // тихий «голос» −36 dBFS с паузами, 3 с
            val x = ShortArray(48_000) { i -> if ((i / 8000) % 2 == 0) (500 * sin(2 * PI * 220 * i / 16000)).toInt().toShort() else 0 }
            write(f, x)
            val g = RandomAccessFile(f, "rw").use { WavGain.normalizePcm16(it, 44, x.size * 2L) }
            val y = read(f)
            assertTrue(g > 4f, "gain $g")
            assertTrue(g <= 8f)
            assertTrue(rms(y) > rms(x) * 4)
            assertTrue(y.all { abs(it.toInt()) < 32767 })
            for (i in x.indices step 997) assertEquals((x[i] * g).toInt().toDouble(), y[i].toDouble(), 1.5)
            assertTrue(f.readBytes().take(44).all { it == 7.toByte() })
        } finally { f.delete() }
    }

    @Test fun loudOrSilentFileIsUntouched() {
        val f = File.createTempFile("gain", ".wav")
        try {
            val loud = ShortArray(16_000) { i -> (12000 * sin(2 * PI * 300 * i / 16000)).toInt().toShort() }
            write(f, loud)
            assertEquals(1f, RandomAccessFile(f, "rw").use { WavGain.normalizePcm16(it, 44, loud.size * 2L) })
            assertContentEquals(loud, read(f))
            val silent = ShortArray(16_000)
            write(f, silent)
            assertEquals(1f, RandomAccessFile(f, "rw").use { WavGain.normalizePcm16(it, 44, silent.size * 2L) })
        } finally { f.delete() }
    }
}
