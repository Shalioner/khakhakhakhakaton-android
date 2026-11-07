package ru.neurosteno.nemotron

import android.content.Context
import android.util.Log
import ru.neurosteno.audio.PcmFile
import ru.neurosteno.core.SpeakerTurn
import ru.neurosteno.core.nemotron.NemotronConfig
import ru.neurosteno.core.nemotron.NemotronFrontend
import ru.neurosteno.core.nemotron.NemotronStreamer
import ru.neurosteno.core.nemotron.SampleSource
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Пути внутри assets (кладёт tools/fetch_models.py; LICENSE OpenMDW-1.1 и NOTICE NVIDIA — там же). */
object NemotronAssets {
    const val DIR = "models/nemotron"
    const val EMBED = "$DIR/embed.onnx"
    const val STEP = "$DIR/step_int8.onnx"
    const val MEL = "$DIR/mel_filters.bin"
    const val SILENCE = "$DIR/silence_embeds.bin"

    fun available(context: Context): Boolean =
        listOf(EMBED, STEP, MEL, SILENCE).all { runCatching { context.assets.open(it).close() }.isSuccess }
}

/**
 * Диаризация NVIDIA Nemotron-3-Diarization (streaming Sortformer, до 8 спикеров) — ADR 0003.
 * Сети через onnxruntime C API (libneurort.so), признаки/чанки/кэш спикеров — `core` (NemotronStreamer).
 * Та же сигнатура run(), что у SherpaDiarizer: можно подменять с fallback. Аудио читается окнами по
 * чанку (30.4 с) из PcmFile, весь файл во float не нужен. Конструктор бросает исключение, если моделей нет
 * или ORT не загрузилась, — вызывающий делает fallback.
 */
class NemotronDiarizer(
    context: Context,
    numThreads: Int = 4,
    private val cfg: NemotronConfig = NemotronConfig(),
) : AutoCloseable {
    private val embed: OrtSession
    private val step: OrtSession
    private val streamer: NemotronStreamer

    init {
        val t0 = System.nanoTime()
        val embedFile = copyAsset(context, NemotronAssets.EMBED)
        val stepFile = copyAsset(context, NemotronAssets.STEP)
        val mel = readFloats(context, NemotronAssets.MEL)
        val silence = readFloats(context, NemotronAssets.SILENCE)
        embed = OrtSession(embedFile.path, 1) // 2 МБ, линейная проекция — один поток
        step = try {
            OrtSession(stepFile.path, numThreads)
        } catch (e: Throwable) {
            embed.close(); throw e
        }
        streamer = NemotronStreamer(embed, step, NemotronFrontend(mel), silence, cfg)
        Log.i(TAG, "loaded in ${(System.nanoTime() - t0) / 1_000_000} ms, ort ${OrtSession.runtimeVersion()}, threads $numThreads")
    }

    fun run(pcm: PcmFile, onProgress: (Float) -> Unit = {}, isCancelled: () -> Boolean = { false }): List<SpeakerTurn> {
        require(pcm.sampleRate == NemotronFrontend.SAMPLE_RATE) { "need 16 kHz PCM, got ${pcm.sampleRate}" }
        val src = object : SampleSource {
            override val numSamples: Long get() = pcm.numSamples
            override fun read(start: Long, count: Int): FloatArray = pcm.read(start, count)
        }
        val t0 = System.nanoTime()
        val turns = streamer.turns(src, onProgress, isCancelled)
        val ms = (System.nanoTime() - t0) / 1_000_000
        Log.i(TAG, "diarized ${pcm.durationMs} ms in $ms ms: ${turns.size} turns, ${turns.map { it.speakerIdx }.toSet().size} speakers")
        return turns
    }

    override fun close() {
        step.close()
        embed.close()
    }

    companion object {
        const val TAG = "NemotronDiarizer"

        /** onnxruntime читает модель по пути — копируем asset (noCompress) во filesDir один раз; повторно — если размер другой. */
        internal fun copyAsset(context: Context, asset: String): File {
            val dir = File(context.filesDir, "nemotron").apply { mkdirs() }
            val dst = File(dir, asset.substringAfterLast('/'))
            val size = context.assets.openFd(asset).use { it.length }
            if (dst.exists() && dst.length() == size) return dst
            val tmp = File(dst.path + ".part")
            context.assets.open(asset).use { inp -> tmp.outputStream().use { inp.copyTo(it, 1 shl 20) } }
            if (!tmp.renameTo(dst)) { tmp.delete(); error("cannot write ${dst.path}") }
            return dst
        }

        internal fun readFloats(context: Context, asset: String): FloatArray {
            val bytes = context.assets.open(asset).use { it.readBytes() }
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(fb.remaining()).also { fb.get(it) }
        }
    }
}
