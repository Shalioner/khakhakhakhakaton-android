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
import ru.neurosteno.core.ClusterPost
import ru.neurosteno.core.ContractValidator
import ru.neurosteno.core.GuardedEditor
import ru.neurosteno.core.LlmBackend
import ru.neurosteno.core.PipelineConfig
import ru.neurosteno.core.PunctTransfer
import ru.neurosteno.core.SpeakerTurn
import ru.neurosteno.core.Transcript
import ru.neurosteno.core.TranscriptBuilder
import ru.neurosteno.sherpa.EngineConfig
import ru.neurosteno.sherpa.ModelAssets
import ru.neurosteno.nemotron.NemotronDiarizer
import ru.neurosteno.sherpa.SherpaAsr
import ru.neurosteno.sherpa.SherpaClusterEmbedder
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
        // Копия с AGC — только для ASR (диаризация получает исходный нормализованный сигнал).
        val asrPcmFile = if (cfg.agcEnabled) File(context.cacheDir, "input_asr.pcm") else null
        val pcm = withContext(Dispatchers.IO) {
            timed(Stage.DECODE) {
                AudioDecoder(context).decode(uri, pcmFile, { listener.onProgress(Stage.DECODE, it) }, isCancelled, asrAgcFile = asrPcmFile, agcMaxGain = cfg.agcMaxGain, onWarning = { warnings += it })
            }
        }
        val durationMs = pcm.durationMs
        val asrPcm = asrPcmFile?.takeIf { it.exists() }?.let { PcmFile(it) } ?: pcm

        val vadSegs = withContext(Dispatchers.Default) {
            timed(Stage.VAD) {
                SherpaVad(assets, engineCfg).use { it.run(asrPcm, { f -> listener.onProgress(Stage.VAD, f) }, isCancelled) }
            }
        }
        val chunks = Chunker.chunks(vadSegs, durationMs, cfg)
        Log.i(TAG, "duration=${durationMs}ms vad=${vadSegs.size} chunks=${chunks.size}")

        // ASR и диаризация параллельно, на раздельных пулах потоков. Для длинных файлов (B3 ревью) —
        // последовательно: иначе ASR-энкодер + весь сигнал во float + матрица кластеризации одновременно
        // могут превысить память 4 ГБ телефона, а LMK убивает процесс без шанса на fallback.
        val sequential = durationMs > cfg.parallelMaxMs
        val half = if (sequential) engineCfg.numThreads else (engineCfg.numThreads / 2).coerceAtLeast(1)
        if (sequential) Log.i(TAG, "long file: ASR and diarization run sequentially")
        val asrPool = Executors.newSingleThreadExecutor()
        val diaPool = Executors.newSingleThreadExecutor()
        val (words, turns) = try {
            coroutineScope {
                val asrJob = async(asrPool.asCoroutineDispatcher()) {
                    timed(Stage.ASR) {
                        listener.onProgress(Stage.ASR, 0f)
                        val ec = engineCfg.copy(numThreads = half)
                        // Проход 1: слова и тайминги (модель без пунктуации, числа словами).
                        val byChunk = SherpaAsr(assets, ec, ModelAssets.ASR_WORDS_DIR).use {
                            it.recognizeByChunk(asrPcm, chunks, { f -> listener.onProgress(Stage.ASR, f * 0.5f) }, isCancelled)
                        }
                        // Проход 2: e2e-модель только ради регистра/пунктуации; модели грузятся по очереди (RAM).
                        val texts = try {
                            SherpaAsr(assets, ec, ModelAssets.ASR_DIR).use {
                                it.texts(asrPcm, chunks, { f -> listener.onProgress(Stage.ASR, 0.5f + f * 0.5f) }, isCancelled)
                            }
                        } catch (e: InterruptedException) {
                            throw e
                        } catch (e: Throwable) {
                            Log.e(TAG, "punct pass failed", e)
                            warnings += "Пунктуация ASR недоступна — текст без знаков"
                            null
                        }
                        byChunk.flatMapIndexed { i, w -> texts?.getOrNull(i)?.let { PunctTransfer.transfer(w, it) } ?: w }
                    }
                }
                if (sequential) asrJob.join()
                val diaJob = async(diaPool.asCoroutineDispatcher()) {
                    timed(Stage.DIARIZATION) {
                        listener.onProgress(Stage.DIARIZATION, 0f) // стадия видна сразу, а не после первого колбэка sherpa
                        try {
                            // при ClusterPost диаризация занимает 0..0.85, остальное — эмбеддинги кластеров (иначе экран «замирает»)
                            val scale = if (cfg.clusterPostEnabled) 0.85f else 1f
                            val prog: (Float) -> Unit = { f -> listener.onProgress(Stage.DIARIZATION, (f * scale).coerceAtMost(scale)) }
                            // Nemotron-3-Diarization (если включён) с откатом на pyannote+CAM++ при любой ошибке загрузки/инференса.
                            var usedNemotron = false
                            val raw = (if (cfg.nemotronDiarizer) try {
                                NemotronDiarizer(context, half).use { it.run(pcm, prog, isCancelled) }.also { usedNemotron = true }
                            } catch (e: InterruptedException) {
                                throw e
                            } catch (e: Throwable) {
                                Log.e(TAG, "nemotron failed, fallback to sherpa", e)
                                warnings += "Nemotron недоступен — диаризация pyannote"
                                null
                            } else null)
                                ?: SherpaDiarizer(assets, engineCfg.copy(numThreads = half)).use { it.run(pcm, prog, isCancelled) }
                            // ClusterPost — только для pyannote+CAM++ (Nemotron сам определяет число говорящих; замер на телефоне — без него)
                            (if (cfg.clusterPostEnabled && !usedNemotron) postCluster(raw, pcm, half, isCancelled, warnings) else raw)
                                .also { listener.onProgress(Stage.DIARIZATION, 1f) }
                        } catch (e: InterruptedException) {
                            throw e
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

        if (cfg.llmEnabled && GuardedEditor.anyNeedsEdit(transcript)) {
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

    /**
     * Постобработка кластеров: эмбеддинги по чистым окнам турнов → ClusterPost. Извлекатель грузится
     * после того, как диаризатор освобождён, и закрывается сразу. Любая ошибка → исходные турны.
     */
    private fun postCluster(
        turns: List<SpeakerTurn>,
        pcm: PcmFile,
        threads: Int,
        isCancelled: () -> Boolean,
        warnings: MutableList<String>,
    ): List<SpeakerTurn> {
        if (turns.map { it.speakerIdx }.distinct().size <= 1) return turns
        return try {
            val pc = ClusterPost.Config.from(cfg)
            val embs = SherpaClusterEmbedder(context.assets, engineCfg.copy(numThreads = threads)).use {
                it.embed(pcm, ClusterPost.windows(turns, pc), isCancelled)
            }
            ClusterPost.apply(turns, embs, pc).also {
                Log.i(TAG, "cluster-post: ${turns.map { t -> t.speakerIdx }.distinct().size} -> ${it.map { t -> t.speakerIdx }.distinct().size} clusters, ${embs.size} windows")
            }
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "cluster post-processing failed", e)
            warnings += "Уточнение спикеров по голосу не удалось — оставлена исходная диаризация"
            turns
        }
    }

    companion object {
        const val TAG = "NeuroSteno"
        /**
         * Потоков всего (делятся пополам между параллельными ASR и диаризацией). Замер на Pixel 6a (Tensor, 2+2+4 ядра):
         * 8 → RTF 0.79, 6 → 0.63, 4 → 0.54, 2 → 0.57 — больше потоков упираются в малые ядра. Берём половину ядер, не больше 4.
         */
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
    }
}
