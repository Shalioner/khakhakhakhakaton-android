package ru.neurosteno.app

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import ru.neurosteno.audio.AudioDecoder
import ru.neurosteno.audio.PcmFile
import ru.neurosteno.core.Chunker
import ru.neurosteno.core.ContractValidator
import ru.neurosteno.core.GuardedEditor
import ru.neurosteno.core.LlmBackend
import ru.neurosteno.core.PipelineConfig
import ru.neurosteno.core.SpeakerTurn
import ru.neurosteno.core.Transcript
import ru.neurosteno.core.TranscriptBuilder
import ru.neurosteno.sherpa.EngineConfig
import ru.neurosteno.sherpa.SherpaAsr
import ru.neurosteno.sherpa.SherpaDiarizer
import ru.neurosteno.sherpa.SherpaVad
import java.io.File
import java.util.concurrent.Executors

enum class Stage(val title: String) {
    DECODE("Декодирование аудио"),
    VAD("Поиск речи"),
    ASR("Распознавание речи"),
    DIARIZATION("Определение говорящих"),
    ALIGN("Сборка реплик"),
    LLM("Пунктуация (локальная LLM)"),
    DONE("Готово"),
}

data class StageTiming(val stage: Stage, val ms: Long)

data class PipelineResult(
    val transcript: Transcript,
    val pcm: PcmFile,
    val timings: List<StageTiming>,
    val totalMs: Long,
    val warnings: List<String>,
) {
    val rtf: Double get() = if (transcript.durationMs > 0) totalMs.toDouble() / transcript.durationMs else 0.0
}

/** Фабрика LLM: null, если модель/движок недоступны (тогда шаг пропускается — fallback на текст ASR). */
fun interface LlmFactory { fun create(context: Context): LlmBackend? }

class Pipeline(
    private val context: Context,
    private val cfg: PipelineConfig = PipelineConfig(),
    private val engineCfg: EngineConfig = EngineConfig(numThreads = defaultThreads()),
    private val llmFactory: LlmFactory = LlmFactory { null },
) {
    fun interface Listener { fun onProgress(stage: Stage, fraction: Float) }

    suspend fun run(uri: Uri, listener: Listener, isCancelled: () -> Boolean): PipelineResult {
        val t0 = System.currentTimeMillis()
        val timings: MutableList<StageTiming> = java.util.Collections.synchronizedList(ArrayList())
        val warnings: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        val assets = context.assets

        fun <T> timed(stage: Stage, block: () -> T): T {
            val s = System.currentTimeMillis()
            return block().also { timings += StageTiming(stage, System.currentTimeMillis() - s) }
        }

        val pcmFile = File(context.cacheDir, "input.pcm")
        val pcm = withContext(Dispatchers.IO) {
            timed(Stage.DECODE) {
                AudioDecoder(context).decode(uri, pcmFile, { listener.onProgress(Stage.DECODE, it) }, isCancelled)
            }
        }
        val durationMs = pcm.durationMs

        val vadSegs = withContext(Dispatchers.Default) {
            timed(Stage.VAD) {
                SherpaVad(assets, engineCfg).use { it.run(pcm, { f -> listener.onProgress(Stage.VAD, f) }, isCancelled) }
            }
        }
        val chunks = Chunker.chunks(vadSegs, durationMs, cfg)
        Log.i(TAG, "duration=${durationMs}ms vad=${vadSegs.size} chunks=${chunks.size}")

        // ASR и диаризация параллельно, на раздельных пулах потоков.
        val half = (engineCfg.numThreads / 2).coerceAtLeast(1)
        val asrPool = Executors.newSingleThreadExecutor()
        val diaPool = Executors.newSingleThreadExecutor()
        val (words, turns) = try {
            coroutineScope {
                val asrJob = async(asrPool.asCoroutineDispatcher()) {
                    timed(Stage.ASR) {
                        SherpaAsr(assets, engineCfg.copy(numThreads = half)).use {
                            it.recognize(pcm, chunks, { f -> listener.onProgress(Stage.ASR, f) }, isCancelled)
                        }
                    }
                }
                val diaJob = async(diaPool.asCoroutineDispatcher()) {
                    timed(Stage.DIARIZATION) {
                        try {
                            SherpaDiarizer(assets, engineCfg.copy(numThreads = half)).use {
                                it.run(pcm) { f -> listener.onProgress(Stage.DIARIZATION, f) }
                            }
                        } catch (e: Throwable) {
                            // Fallback: диаризация упала (OOM, битая модель) → один спикер.
                            Log.e(TAG, "diarization failed", e)
                            warnings += "Диаризация не удалась (${e.javaClass.simpleName}); все реплики отнесены к одному спикеру"
                            emptyList<SpeakerTurn>()
                        }
                    }
                }
                asrJob.await() to diaJob.await()
            }
        } finally {
            asrPool.shutdown(); diaPool.shutdown()
        }
        if (isCancelled()) throw InterruptedException("cancelled")

        listener.onProgress(Stage.ALIGN, 0f)
        var transcript = timed(Stage.ALIGN) { TranscriptBuilder.build(words, turns, durationMs, vadSegs, cfg = cfg) }

        if (cfg.llmEnabled && transcript.utterances.isNotEmpty()) {
            transcript = withContext(Dispatchers.Default) {
                timed(Stage.LLM) {
                    val llm = runCatching { llmFactory.create(context) }.getOrNull()
                    if (llm == null) {
                        warnings += "Локальная LLM недоступна — оставлен текст ASR"
                        transcript
                    } else llm.use {
                        val budget = (durationMs * cfg.llmBudgetFraction).toLong().coerceAtLeast(5_000)
                        val (edited, st) = GuardedEditor(it).edit(transcript, budget, { f -> listener.onProgress(Stage.LLM, f) }, isCancelled)
                        Log.i(TAG, "llm: $st")
                        if (st.skipped > 0) warnings += "LLM: бюджет времени исчерпан, ${st.skipped} реплик без правки"
                        edited
                    }
                }
            }
        }

        val errors = ContractValidator.validate(transcript)
        if (errors.isNotEmpty()) {
            // Не должно случаться: TranscriptBuilder гарантирует инварианты. Логируем и всё равно отдаём.
            Log.e(TAG, "contract violations: $errors")
            warnings += "Нарушения контракта: ${errors.take(3)}"
        }
        listener.onProgress(Stage.DONE, 1f)
        return PipelineResult(transcript, pcm, timings.toList(), System.currentTimeMillis() - t0, warnings.toList())
    }

    companion object {
        const val TAG = "NeuroSteno"
        fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
    }
}
