package ru.neurosteno.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt
import ru.neurosteno.core.dsp.DcBlocker
import ru.neurosteno.core.dsp.Loudness
import ru.neurosteno.core.dsp.StreamingResampler

class AudioDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Любой формат, который понимает MediaExtractor (wav/mp3/m4a/aac/ogg/opus/flac/amr/3gp),
 * → PCM16 моно 16 кГц в кэш-файле. Два прохода: декод+ресемплинг+DC в tmp, затем нормализация громкости.
 */
class AudioDecoder(private val context: Context) {

    fun interface Progress { fun onProgress(fraction: Float) }

    /**
     * @param asrAgcFile если задан — дополнительно пишется копия с динамической нормализацией (SpeechAgc)
     * только для распознавания: ASR тихие слова слышит лучше, а диаризации AGC мешает (A/B, TEST_RESULTS).
     */
    fun decode(
        uri: Uri, outFile: File, progress: Progress? = null, isCancelled: () -> Boolean = { false },
        asrAgcFile: File? = null, agcMaxGain: Float = 8f,
        onWarning: (String) -> Unit = {},
    ): PcmFile {
        val tmp = File(outFile.parentFile, outFile.name + ".raw")
        tmp.writeBytes(ByteArray(0))
        try {
            val stream = try {
                context.contentResolver.openInputStream(uri)
            } catch (e: Exception) {
                throw AudioDecodeException("Не удалось открыть файл: ${e.message}", e)
            }
            val stats = stream?.buffered()?.use { input ->
                input.mark(12)
                val header = ByteArray(12)
                var count = 0
                while (count < header.size) {
                    val n = input.read(header, count, header.size - count)
                    if (n <= 0) break
                    count += n
                }
                input.reset()
                if (count == 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(header, 8, 4, Charsets.US_ASCII) == "WAVE") {
                    // Формат, который не умеет WavPcmReader (A-law, mu-law, ADPCM, >2 каналов…),
                    // отдаём MediaExtractor, как раньше.
                    val reader = try { ru.neurosteno.core.WavPcmReader(input) } catch (e: Exception) { null }
                    reader?.let { decodeWav(it, tmp, progress, isCancelled, onWarning) }
                } else null
            } ?: decodeToRaw(uri, tmp, progress, isCancelled, onWarning)
            normalize(tmp, outFile, stats, asrAgcFile, agcMaxGain)
            progress?.onProgress(1f)
            return PcmFile(outFile)
        } finally {
            tmp.delete()
        }
    }

    private class Stats(val frameRms: FloatArray, val peak: Float)

    private fun decodeToRaw(uri: Uri, out: File, progress: Progress?, isCancelled: () -> Boolean, onWarning: (String) -> Unit): Stats {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            extractor.release()
            throw AudioDecodeException("Не удалось открыть файл: формат не поддерживается или файл повреждён", e)
        }
        val frameRms = FrameRmsAccumulator()
        var peak = 0f
        var codec: MediaCodec? = null
        try {
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AudioDecodeException("В файле нет аудиодорожки")
            extractor.selectTrack(track)
            val fmt = extractor.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            codec = try {
                MediaCodec.createDecoderByType(mime).apply { configure(fmt, null, null, 0) }
            } catch (e: Exception) {
                throw AudioDecodeException("Кодек $mime не поддерживается устройством", e)
            }
            codec.start()

            var inRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT
            var resampler: StreamingResampler? = null
            val dc = DcBlocker()

            BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { os ->
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var lastOutput = android.os.SystemClock.elapsedRealtime()
                while (!outputDone) {
                    if (isCancelled()) throw InterruptedException("cancelled")
                    if (android.os.SystemClock.elapsedRealtime() - lastOutput >= 10_000) {
                        throw AudioDecodeException("Декодер не выдаёт аудио более 10 секунд")
                    }
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = codec.outputFormat
                            inRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            resampler = null
                            lastOutput = android.os.SystemClock.elapsedRealtime()
                        }
                        outIdx >= 0 -> {
                            if (info.size > 0) lastOutput = android.os.SystemClock.elapsedRealtime()
                            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            val ob = codec.getOutputBuffer(outIdx)!!
                            ob.position(info.offset); ob.limit(info.offset + info.size)
                            val mono = toMono(ob, channels, pcmEncoding)
                            codec.releaseOutputBuffer(outIdx, false)
                            val rs = resampler ?: StreamingResampler(inRate, PcmFile.SAMPLE_RATE).also { resampler = it }
                            val res = dc.process(rs.process(mono, flush = eos))
                            for (v in res) {
                                val a = abs(v); if (a > peak) peak = a
                            }
                            frameRms.add(res)
                            writePcm16(os, res)
                            if (durationUs > 0 && info.presentationTimeUs > 0) {
                                progress?.onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                            }
                            if (eos) outputDone = true
                        }
                    }
                }
            }
            if (out.length() < PcmFile.SAMPLE_RATE / 10 * 2) throw AudioDecodeException("Аудио слишком короткое или пустое")
            return Stats(frameRms.result(), peak)
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            if (out.length() >= PcmFile.SAMPLE_RATE / 10 * 2) {
                onWarning("Аудио декодировано частично: ${e.message}")
                return Stats(frameRms.result(), peak)
            }
            throw AudioDecodeException("Ошибка декодирования: ${e.message}", e)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun decodeWav(
        reader: ru.neurosteno.core.WavPcmReader, out: File, progress: Progress?, isCancelled: () -> Boolean, onWarning: (String) -> Unit,
    ): Stats {
        val frameRms = FrameRmsAccumulator()
        var peak = 0f
        BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { os ->
            try {
                val resampler = StreamingResampler(reader.sampleRate, PcmFile.SAMPLE_RATE)
                val dc = DcBlocker()
                while (true) {
                    if (isCancelled()) throw InterruptedException("cancelled")
                    val mono = reader.readMono()
                    val res = dc.process(resampler.process(mono, flush = mono.isEmpty()))
                    for (v in res) peak = maxOf(peak, abs(v))
                    frameRms.add(res)
                    writePcm16(os, res)
                    progress?.onProgress(reader.fraction())
                    if (mono.isEmpty()) break
                }
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                os.flush()
                if (out.length() < PcmFile.SAMPLE_RATE / 10 * 2) throw AudioDecodeException("Ошибка WAV: ${e.message}", e)
                onWarning("Аудио декодировано частично: ${e.message}")
            }
        }
        if (out.length() < PcmFile.SAMPLE_RATE / 10 * 2) throw AudioDecodeException("Аудио слишком короткое или пустое")
        return Stats(frameRms.result(), peak)
    }

    private fun toMono(buf: ByteBuffer, channels: Int, encoding: Int): FloatArray {
        val bb = buf.order(ByteOrder.LITTLE_ENDIAN)
        val ch = channels.coerceAtLeast(1)
        return if (encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
            val fb = bb.asFloatBuffer()
            val frames = fb.remaining() / ch
            FloatArray(frames) { i -> var s = 0f; for (c in 0 until ch) s += fb.get(i * ch + c); s / ch }
        } else {
            val sb = bb.asShortBuffer()
            val frames = sb.remaining() / ch
            FloatArray(frames) { i -> var s = 0f; for (c in 0 until ch) s += sb.get(i * ch + c); s / ch / 32768f }
        }
    }

    private fun writePcm16(os: BufferedOutputStream, x: FloatArray) {
        val bytes = ByteArray(x.size * 2)
        for (i in x.indices) {
            val v = (x[i] * 32767f).toInt().coerceIn(-32768, 32767)
            bytes[2 * i] = (v and 0xff).toByte()
            bytes[2 * i + 1] = (v shr 8 and 0xff).toByte()
        }
        os.write(bytes)
    }

    /** Второй проход: общий коэффициент громкости (без клиппинга) и, по запросу, копия с AGC для ASR. */
    private fun normalize(src: File, dst: File, stats: Stats, agcFile: File?, agcMaxGain: Float) {
        val g = Loudness.gain(stats.frameRms, stats.peak)
        if (agcFile == null && abs(g - 1f) < 0.05f) { src.copyTo(dst, overwrite = true); return }
        val speechAgc = agcFile?.let {
            ru.neurosteno.core.dsp.SpeechAgc(
                ru.neurosteno.core.dsp.SpeechAgc.noiseFloor(FloatArray(stats.frameRms.size) { i -> stats.frameRms[i] * g }),
                maxGain = agcMaxGain,
            )
        }
        val agcOs = agcFile?.let { BufferedOutputStream(FileOutputStream(it), 1 shl 16) }
        try {
            RandomAccessFile(src, "r").use { inp ->
                BufferedOutputStream(FileOutputStream(dst), 1 shl 16).use { os ->
                    val chunk = ByteArray(1 shl 16)
                    while (true) {
                        val n = inp.read(chunk)
                        if (n <= 0) break
                        val sb = ByteBuffer.wrap(chunk, 0, n).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val f = FloatArray(sb.remaining()) { sb.get(it) * g / 32768f }
                        writePcm16(os, f)
                        if (agcOs != null) writePcm16(agcOs, speechAgc!!.process(f))
                    }
                }
            }
        } finally {
            agcOs?.close()
        }
    }

    private class FrameRmsAccumulator(private val frame: Int = 480) {
        private val list = ArrayList<Float>()
        private var acc = 0.0
        private var n = 0
        fun add(x: FloatArray) {
            for (v in x) {
                acc += v * v; n++
                if (n == frame) { list += sqrt(acc / n).toFloat(); acc = 0.0; n = 0 }
            }
        }
        fun result(): FloatArray = list.toFloatArray()
    }
}
