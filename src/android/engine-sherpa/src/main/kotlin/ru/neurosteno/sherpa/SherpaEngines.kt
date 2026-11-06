package ru.neurosteno.sherpa

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import ru.neurosteno.audio.PcmFile
import ru.neurosteno.core.Chunker
import ru.neurosteno.core.EmbWindow
import ru.neurosteno.core.WindowEmbedding
import ru.neurosteno.core.SpeakerTurn
import ru.neurosteno.core.SpeechSegment
import ru.neurosteno.core.TokenWords
import ru.neurosteno.core.Word

/** Пути моделей внутри assets (кладёт tools/fetch_models.py). */
object ModelAssets {
    const val VAD = "models/vad/silero_vad.onnx"
    /** e2e (регистр/пунктуация, числа цифрами) — только для PunctTransfer. */
    const val ASR_DIR = "models/asr"
    /** Основная модель слов: без пунктуации, числа словами. */
    const val ASR_WORDS_DIR = "models/asr_words"
    const val ASR_CTC_DIR = "models/asr_ctc"
    const val SEG = "models/seg/model.onnx"
    const val EMB = "models/spk/embedding.onnx"
}

enum class AsrKind { TRANSDUCER, CTC }

data class EngineConfig(
    val numThreads: Int = 4,
    val asrKind: AsrKind = AsrKind.TRANSDUCER,
    val vadThreshold: Float = 0.5f,
    val vadMinSilenceSec: Float = 0.3f,
    val vadMinSpeechSec: Float = 0.2f,
    val vadMaxSpeechSec: Float = 20f,
    /** Порог кластеризации (cos-расстояние): выше → меньше спикеров. numClusters = -1 → автоопределение. */
    val clusterThreshold: Float = 0.5f,
    val numClusters: Int = -1,
    val minDurationOnSec: Float = 0.2f,
    val minDurationOffSec: Float = 0.5f,
)

/** Silero VAD по файлу окнами по 512 сэмплов. Возвращает речевые интервалы в мс. */
class SherpaVad(assets: AssetManager, private val cfg: EngineConfig) : AutoCloseable {
    private val vad = Vad(
        assets,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = ModelAssets.VAD,
                threshold = cfg.vadThreshold,
                minSilenceDuration = cfg.vadMinSilenceSec,
                minSpeechDuration = cfg.vadMinSpeechSec,
                windowSize = 512,
                maxSpeechDuration = cfg.vadMaxSpeechSec,
            ),
            sampleRate = PcmFile.SAMPLE_RATE,
            numThreads = 1,
        ),
    )

    fun run(pcm: PcmFile, onProgress: (Float) -> Unit = {}, isCancelled: () -> Boolean = { false }): List<SpeechSegment> {
        val out = ArrayList<SpeechSegment>()
        val block = 512 * 64
        var pos = 0L
        fun drain() {
            while (!vad.empty()) {
                val s = vad.front()
                val startMs = s.start * 1000 / PcmFile.SAMPLE_RATE
                out += SpeechSegment(startMs, startMs + s.samples.size * 1000L / PcmFile.SAMPLE_RATE)
                vad.pop()
            }
        }
        while (pos < pcm.numSamples) {
            if (isCancelled()) throw InterruptedException("cancelled")
            val buf = pcm.read(pos, block)
            var i = 0
            while (i + 512 <= buf.size) { vad.acceptWaveform(buf.copyOfRange(i, i + 512)); i += 512 }
            if (i < buf.size) vad.acceptWaveform(buf.copyOfRange(i, buf.size) + FloatArray(512 - (buf.size - i)))
            drain()
            pos += block
            onProgress(pos.toFloat() / pcm.numSamples)
        }
        vad.flush(); drain()
        vad.reset()
        return out
    }

    override fun close() = vad.release()
}

/** GigaAM v3 через OfflineRecognizer; распознаёт чанки и возвращает слова в абсолютном времени. */
class SherpaAsr(
    assets: AssetManager,
    private val cfg: EngineConfig,
    private val dir: String = ModelAssets.ASR_WORDS_DIR,
) : AutoCloseable {
    private val recognizer: OfflineRecognizer

    init {
        val model = when (cfg.asrKind) {
            AsrKind.TRANSDUCER -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$dir/encoder.int8.onnx",
                    decoder = "$dir/decoder.onnx",
                    joiner = "$dir/joiner.onnx",
                ),
                tokens = "$dir/tokens.txt",
                modelType = "nemo_transducer",
                numThreads = cfg.numThreads,
            )
            AsrKind.CTC -> OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = "${ModelAssets.ASR_CTC_DIR}/model.int8.onnx"),
                tokens = "${ModelAssets.ASR_CTC_DIR}/tokens.txt",
                numThreads = cfg.numThreads,
            )
        }
        recognizer = OfflineRecognizer(
            assets,
            OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = PcmFile.SAMPLE_RATE, featureDim = 64),
                modelConfig = model,
                decodingMethod = "greedy_search",
            ),
        )
    }

    fun recognize(
        pcm: PcmFile,
        chunks: List<Chunker.Chunk>,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): List<Word> {
        val words = ArrayList<Word>()
        val total = chunks.sumOf { it.endMs - it.startMs }.coerceAtLeast(1)
        var done = 0L
        for (c in chunks) {
            if (isCancelled()) throw InterruptedException("cancelled")
            val samples = pcm.readMs(c.startMs, c.endMs)
            if (samples.isEmpty()) continue
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, PcmFile.SAMPLE_RATE)
                recognizer.decode(stream)
                val r = recognizer.getResult(stream)
                words += TokenWords.toWords(r.tokens, r.timestamps, c.startMs, c.endMs)
            } finally {
                stream.release()
            }
            done += c.endMs - c.startMs
            onProgress(done.toFloat() / total)
        }
        return words
    }

    /** Только текст по тем же чанкам (для переноса пунктуации e2e-модели). */
    fun texts(pcm: PcmFile, chunks: List<Chunker.Chunk>, onProgress: (Float) -> Unit = {}, isCancelled: () -> Boolean = { false }): List<String> =
        chunks.mapIndexed { i, c ->
            if (isCancelled()) throw InterruptedException("cancelled")
            val samples = pcm.readMs(c.startMs, c.endMs)
            onProgress((i + 1f) / chunks.size)
            if (samples.isEmpty()) return@mapIndexed ""
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, PcmFile.SAMPLE_RATE)
                recognizer.decode(stream)
                recognizer.getResult(stream).text
            } finally { stream.release() }
        }

    /** Слова по чанкам (список на чанк), чтобы потом перенести пунктуацию почанково. */
    fun recognizeByChunk(pcm: PcmFile, chunks: List<Chunker.Chunk>, onProgress: (Float) -> Unit = {}, isCancelled: () -> Boolean = { false }): List<List<Word>> =
        chunks.mapIndexed { i, c ->
            onProgress((i + 1f) / chunks.size)
            recognize(pcm, listOf(c), isCancelled = isCancelled)
        }

    override fun close() = recognizer.release()
}

/** pyannote-segmentation-3.0 + эмбеддинги + FastClustering с автоопределением числа спикеров. */
class SherpaDiarizer(assets: AssetManager, private val cfg: EngineConfig) : AutoCloseable {
    private val sd = OfflineSpeakerDiarization(
        assets,
        OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = ModelAssets.SEG),
                numThreads = cfg.numThreads,
            ),
            embedding = SpeakerEmbeddingExtractorConfig(model = ModelAssets.EMB, numThreads = cfg.numThreads),
            clustering = FastClusteringConfig(numClusters = cfg.numClusters, threshold = cfg.clusterThreshold),
            minDurationOn = cfg.minDurationOnSec,
            minDurationOff = cfg.minDurationOffSec,
        ),
    )

    fun run(pcm: PcmFile, onProgress: (Float) -> Unit = {}, isCancelled: () -> Boolean = { false }): List<SpeakerTurn> {
        val samples = pcm.readAll()
        // I1 ревью: ненулевой ответ колбэка — просьба прервать обработку [Проверить: поддерживает ли
        // sherpa-onnx 2.0.1 прерывание]; в любом случае после возврата отмена пробрасывается.
        val segs = sd.processWithCallback(samples, callback = { done: Int, total: Int, _: Long ->
            if (total > 0) onProgress(done.toFloat() / total)
            if (isCancelled()) 1 else 0
        })
        if (isCancelled()) throw InterruptedException("cancelled")
        return segs.map { SpeakerTurn(it.speaker, (it.start * 1000).toLong(), (it.end * 1000).toLong()) }
    }

    override fun close() = sd.release()
}

/**
 * Эмбеддинги CAM++ по чистым окнам турнов (ClusterPost.windows) для постобработки кластеров.
 * Создаётся после диаризации (модели грузятся по очереди) и закрывается сразу после расчёта.
 * Аудио читается окнами из PcmFile — файл целиком в RAM не нужен.
 */
class SherpaClusterEmbedder(assets: AssetManager, cfg: EngineConfig) : AutoCloseable {
    private val extractor = SpeakerEmbeddingExtractor(
        assets,
        SpeakerEmbeddingExtractorConfig(model = ModelAssets.EMB, numThreads = cfg.numThreads),
    )

    fun embed(pcm: PcmFile, windows: List<EmbWindow>, isCancelled: () -> Boolean = { false }): List<WindowEmbedding> =
        windows.mapNotNull { w ->
            if (isCancelled()) throw InterruptedException("cancelled")
            val samples = pcm.readMs(w.startMs, w.endMs)
            if (samples.size < PcmFile.SAMPLE_RATE / 10) return@mapNotNull null
            val s = extractor.createStream()
            try {
                s.acceptWaveform(samples, PcmFile.SAMPLE_RATE)
                s.inputFinished()
                if (!extractor.isReady(s)) null else WindowEmbedding(w, extractor.compute(s))
            } finally {
                s.release()
            }
        }

    override fun close() = extractor.release()
}
