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
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import ru.neurosteno.audio.PcmFile
import ru.neurosteno.core.Chunker
import ru.neurosteno.core.SpeakerTurn
import ru.neurosteno.core.SpeechSegment
import ru.neurosteno.core.TokenWords
import ru.neurosteno.core.Word

/** Пути моделей внутри assets (кладёт tools/fetch_models.py). */
object ModelAssets {
    const val VAD = "models/vad/silero_vad.onnx"
    const val ASR_DIR = "models/asr"
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
class SherpaAsr(assets: AssetManager, private val cfg: EngineConfig) : AutoCloseable {
    private val recognizer: OfflineRecognizer

    init {
        val model = when (cfg.asrKind) {
            AsrKind.TRANSDUCER -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "${ModelAssets.ASR_DIR}/encoder.int8.onnx",
                    decoder = "${ModelAssets.ASR_DIR}/decoder.onnx",
                    joiner = "${ModelAssets.ASR_DIR}/joiner.onnx",
                ),
                tokens = "${ModelAssets.ASR_DIR}/tokens.txt",
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

    fun run(pcm: PcmFile, onProgress: (Float) -> Unit = {}): List<SpeakerTurn> {
        val samples = pcm.readAll()
        val segs = sd.processWithCallback(samples, callback = { done: Int, total: Int, _: Long ->
            if (total > 0) onProgress(done.toFloat() / total)
            0
        })
        return segs.map { SpeakerTurn(it.speaker, (it.start * 1000).toLong(), (it.end * 1000).toLong()) }
    }

    override fun close() = sd.release()
}
