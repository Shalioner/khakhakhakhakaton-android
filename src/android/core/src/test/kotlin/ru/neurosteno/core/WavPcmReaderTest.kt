package ru.neurosteno.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

class WavPcmReaderTest {
    private fun wav(bits: Int, channels: Int, rate: Int, floating: Boolean = false, extensible: Boolean = false): ByteArray {
        val data = ByteBuffer.allocate(3 * channels * bits / 8).order(ByteOrder.LITTLE_ENDIAN)
        for (value in listOf(-0.5f, 0f, 0.5f)) repeat(channels) {
            when {
                floating -> data.putFloat(value)
                bits == 8 -> data.put((value * 128 + 128).toInt().toByte())
                bits == 16 -> data.putShort((value * 32768).toInt().toShort())
                bits == 24 -> {
                    val sample = (value * 8388608).toInt()
                    repeat(3) { data.put((sample shr (it * 8)).toByte()) }
                }
                else -> data.putInt((value * 2147483648f).toInt())
            }
        }
        val fmt = ByteBuffer.allocate(if (extensible) 40 else 16).order(ByteOrder.LITTLE_ENDIAN)
        fmt.putShort((if (extensible) 0xfffe else if (floating) 3 else 1).toShort())
        fmt.putShort(channels.toShort()).putInt(rate).putInt(rate * channels * bits / 8)
        fmt.putShort((channels * bits / 8).toShort()).putShort(bits.toShort())
        if (extensible) {
            fmt.putShort(22).putShort(bits.toShort()).putInt(0).putInt(if (floating) 3 else 1)
            fmt.put(byteArrayOf(0, 0, 16, 0, -128, 0, 0, -86, 0, 56, -101, 113))
        }
        val out = ByteArrayOutputStream()
        fun chunk(id: String, bytes: ByteArray) {
            out.write(id.toByteArray(Charsets.US_ASCII))
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).array())
            out.write(bytes)
            if (bytes.size % 2 == 1) out.write(0)
        }
        out.write("WAVE".toByteArray())
        chunk("JUNK", byteArrayOf(7))
        chunk("fmt ", fmt.array())
        chunk("data", data.array())
        return "RIFF".toByteArray() + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(out.size()).array() + out.toByteArray()
    }

    @Test fun decodesEverySupportedEncodingInSmallReads() {
        for (channels in 1..2) for (bits in listOf(8, 16, 24, 32)) for (rate in listOf(8000, 22050, 48000)) {
            val reader = WavPcmReader(ByteArrayInputStream(wav(bits, channels, rate)))
            assertEquals(rate, reader.sampleRate)
            assertEquals(channels, reader.channels)
            assertContentEquals(floatArrayOf(-0.5f), reader.readMono(1))
            assertContentEquals(floatArrayOf(0f, 0.5f), reader.readMono())
            assertTrue(reader.readMono().isEmpty())
        }
        for (floating in listOf(false, true)) {
            val reader = WavPcmReader(ByteArrayInputStream(wav(32, 2, 44100, floating, extensible = true)))
            assertContentEquals(floatArrayOf(-0.5f, 0f, 0.5f), reader.readMono())
        }
        val reader = WavPcmReader(ByteArrayInputStream(wav(32, 1, 16000, floating = true)))
        assertContentEquals(floatArrayOf(-0.5f, 0f, 0.5f), reader.readMono())
    }

    @Test fun truncationReturnsCompleteFramesBeforeReportingFailure() {
        val bytes = wav(16, 1, 16000).dropLast(3).toByteArray()
        val reader = WavPcmReader(ByteArrayInputStream(bytes))
        assertContentEquals(floatArrayOf(-0.5f), reader.readMono())
        assertFailsWith<EOFException> { reader.readMono() }
    }

    @Test fun rejectsMalformedHeaders() {
        assertFailsWith<EOFException> { WavPcmReader(ByteArrayInputStream(byteArrayOf(1))) }
        val bytes = wav(16, 1, 16000)
        bytes[0] = 0
        assertFailsWith<IllegalArgumentException> { WavPcmReader(ByteArrayInputStream(bytes)) }
    }

    @Test fun averagesChannelsAndRejectsUnsupportedFormats() {
        val bytes = wav(16, 2, 48000)
        // У первого кадра каналы -0.5 и +0.5 должны дать ноль.
        val data = bytes.size - 12
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(data + 2, 16384)
        assertEquals(0f, WavPcmReader(ByteArrayInputStream(bytes)).readMono()[0])
        val fmt = bytes.indexOf('f'.code.toByte())
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(fmt + 8, 6)
        assertFailsWith<IllegalArgumentException> { WavPcmReader(ByteArrayInputStream(bytes)) }
    }
}
