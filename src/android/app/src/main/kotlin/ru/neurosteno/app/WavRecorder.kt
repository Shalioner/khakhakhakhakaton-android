package ru.neurosteno.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Запись с микрофона сразу в нужный конвейеру формат: WAV PCM16 моно 16 кГц.
 * Заголовок WAV пишется заглушкой и дописывается при остановке.
 * Ошибки потока записи не роняют приложение: запись останавливается, error заполняется.
 */
class WavRecorder(private val file: File) {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    @Volatile var level: Float = 0f
        private set
    @Volatile var recordedMs: Long = 0
        private set
    @Volatile var error: String? = null
        private set
    val isRunning: Boolean get() = running.get()

    @SuppressLint("MissingPermission") // разрешение проверяет UI перед start()
    fun start() {
        val rate = 16_000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            error("Микрофон недоступен")
        }
        running.set(true)
        worker = thread(name = "wav-recorder") {
            var total = 0L
            try {
                RandomAccessFile(file, "rw").use { out ->
                    out.setLength(0)
                    out.write(ByteArray(44))
                    val buf = ShortArray(rate / 10)
                    val bytes = ByteArray(buf.size * 2)
                    rec.startRecording()
                    try {
                        while (running.get()) {
                            val n = rec.read(buf, 0, buf.size)
                            if (n < 0) { error = "Ошибка чтения микрофона ($n)"; break }
                            if (n == 0) continue
                            var peak = 0
                            for (i in 0 until n) {
                                val v = buf[i].toInt()
                                bytes[2 * i] = (v and 0xff).toByte(); bytes[2 * i + 1] = (v shr 8 and 0xff).toByte()
                                peak = maxOf(peak, abs(v))
                            }
                            out.write(bytes, 0, n * 2)
                            total += n * 2
                            level = peak / 32768f
                            recordedMs = total / 2 * 1000 / rate
                        }
                    } finally {
                        runCatching { rec.stop() }
                        out.seek(0); out.write(header(total, rate))
                    }
                }
            } catch (e: Exception) {
                Log.e(Pipeline.TAG, "recorder failed", e)
                error = "Ошибка записи: ${e.message}"
            } finally {
                rec.release()
                running.set(false)
            }
        }
    }

    fun stop(): File {
        running.set(false)
        worker?.join()
        return file
    }

    private fun header(dataLen: Long, rate: Int): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + dataLen).toInt()); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(rate); b.putInt(rate * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataLen.toInt())
        return b.array()
    }
}
