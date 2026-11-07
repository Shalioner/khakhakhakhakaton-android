package ru.neurosteno.bench

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
import ru.neurosteno.core.Chunker
import ru.neurosteno.core.GuardedEditor
import ru.neurosteno.llm.LlamaBackend
import ru.neurosteno.core.ContractValidator
import ru.neurosteno.core.JsonExporter
import ru.neurosteno.core.PipelineConfig
import ru.neurosteno.core.PunctTransfer
import ru.neurosteno.core.SpeakerTurn
import ru.neurosteno.core.SpeechSegment
import ru.neurosteno.core.TokenWords
import ru.neurosteno.core.TranscriptBuilder
import ru.neurosteno.core.Word
import ru.neurosteno.core.dsp.DcBlocker
import ru.neurosteno.core.dsp.Loudness
import ru.neurosteno.core.dsp.StreamingResampler
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.system.exitProcess

/**
 * ПК-харнесс: тот же core + те же модели, что в APK, через sherpa-onnx Java API.
 * ./gradlew :bench:run --args="testdata/dev out/dev [--asr transducer|ctc] [--cluster-threshold 0.5] [--threads 8]"
 *   [--decoding greedy_search|modified_beam_search] [--max-active-paths 4] [--window-shift-ratio 0.1]
 *   [--vad-model <onnx>] [--chunk-pad-ms 200]
 * Вход — wav (любой частоты/каналов, PCM16/PCM24/PCM32/float). Выход — <name>.json + timings.json.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "cluster-sweep") { ClusterEmbeddings.sweep(args.drop(1)); return }
    if (args.size < 2) { System.err.println("usage: bench <in_dir> <out_dir> [options]"); exitProcess(2) }
    val inDir = File(args[0]); val outDir = File(args[1]).apply { mkdirs() }
    val opt = args.drop(2).chunked(2).associate { it[0].removePrefix("--") to it.getOrElse(1) { "" } }
    val models = File(opt["models"] ?: "src/android/app/src/main/assets/models")
    val ctcDir = File(opt["ctc-dir"] ?: ".cache/models/asr_ctc")
    val threads = opt["threads"]?.toInt() ?: Runtime.getRuntime().availableProcessors().coerceAtMost(8)
    val asrKind = opt["asr"] ?: "transducer"
    val cfg = PipelineConfig(
        pauseSplitMs = opt["pause-split-ms"]?.toLong() ?: 1000,
        minSpeakerTotalMs = opt["min-speaker-ms"]?.toLong() ?: 1500,
        chunkPadMs = opt["chunk-pad-ms"]?.toLong() ?: 200,
    )
    // A/B опций sherpa-onnx (2026-11-07): декодер ASR (оба прохода), сдвиг окна сегментации, модель VAD
    val decoding = opt["decoding"] ?: "greedy_search"
    val maxActivePaths = opt["max-active-paths"]?.toInt() ?: 4
    val windowShiftRatio = opt["window-shift-ratio"]?.toFloat() ?: 0.1f
    val vadModel = opt["vad-model"] ?: File(models, "vad/silero_vad.onnx").path
    val clusterThreshold = opt["cluster-threshold"]?.toFloat() ?: 0.5f
    val numClusters = opt["num-clusters"]?.toInt() ?: -1
    // --llm none | путь к gguf (по умолчанию модель из assets)
    val llmPath = opt["llm"] ?: File(models, "llm/model.gguf").path
    val llm = if (llmPath == "none" || !File(llmPath).exists()) null else LlamaBackend(llmPath, nThreads = minOf(threads, 4))

    val vadCfg = VadModelConfig.builder()
        .setSileroVadModelConfig(
            SileroVadModelConfig.builder().setModel(vadModel)
                .setThreshold(0.5f).setMinSilenceDuration(0.3f).setMinSpeechDuration(0.2f)
                .setWindowSize(512).setMaxSpeechDuration(20f).build(),
        ).setSampleRate(16000).setNumThreads(1).build()

    val modelCfg = if (asrKind == "ctc") {
        OfflineModelConfig.builder()
            .setNemo(OfflineNemoEncDecCtcModelConfig.builder().setModel(File(ctcDir, "model.int8.onnx").path).build())
            .setTokens(File(ctcDir, "tokens.txt").path).setNumThreads(threads).build()
    } else {
        val d = File(opt["asr-dir"] ?: File(models, "asr_words").path) // как в APK: модель слов
        OfflineModelConfig.builder()
            .setTransducer(
                OfflineTransducerModelConfig.builder().setEncoder(File(d, "encoder.int8.onnx").path)
                    .setDecoder(File(d, "decoder.onnx").path).setJoiner(File(d, "joiner.onnx").path).build(),
            )
            .setTokens(File(d, "tokens.txt").path).setModelType("nemo_transducer").setNumThreads(threads).build()
    }
    fun rnnt(d: File) = OfflineModelConfig.builder()
        .setTransducer(
            OfflineTransducerModelConfig.builder().setEncoder(File(d, "encoder.int8.onnx").path)
                .setDecoder(File(d, "decoder.onnx").path).setJoiner(File(d, "joiner.onnx").path).build(),
        )
        .setTokens(File(d, "tokens.txt").path).setModelType("nemo_transducer").setNumThreads(threads).build()
    fun recognizerOf(mc: OfflineModelConfig) = OfflineRecognizer(
        OfflineRecognizerConfig.builder()
            .setFeatureConfig(FeatureConfig.builder().setSampleRate(16000).setFeatureDim(64).build())
            .setOfflineModelConfig(mc).setDecodingMethod(decoding).setMaxActivePaths(maxActivePaths).build(),
    )
    val recognizer = recognizerOf(modelCfg)
    // --punct-dir: второй проход e2e-моделью только ради регистра/пунктуации (PunctTransfer)
    val punctRecognizer = (opt["punct-dir"] ?: File(models, "asr").path).takeIf { it != "none" }?.let { recognizerOf(rnnt(File(it))) } // как в APK; --punct-dir none — без второго прохода
    val diarizer = OfflineSpeakerDiarization(
        OfflineSpeakerDiarizationConfig.builder()
            .setSegmentation(
                OfflineSpeakerSegmentationModelConfig.builder()
                    .setPyannote(OfflineSpeakerSegmentationPyannoteModelConfig.builder().setModel(File(models, "seg/model.onnx").path)
                        .setWindowShiftRatio(windowShiftRatio).build())
                    .setNumThreads(threads).build(),
            )
            .setEmbedding(SpeakerEmbeddingExtractorConfig.builder().setModel(opt["emb"] ?: File(models, "spk/embedding.onnx").path).setNumThreads(threads).build())
            .setClustering(FastClusteringConfig.builder().setNumClusters(numClusters).setThreshold(clusterThreshold).build())
            .setMinDurationOn(0.2f).setMinDurationOff(0.5f).build(),
    )

    // --cluster-post on: эмбеддинги по кластерам → слияние/мелкие кластеры (ClusterEmbeddings.kt)
    val clusterPost = ClusterEmbeddings.fromOptions(opt, opt["emb"] ?: File(models, "spk/embedding.onnx").path, threads)
    val files = inDir.listFiles { f -> f.extension.lowercase() == "wav" }?.sorted().orEmpty()
    println("bench: ${files.size} files, asr=$asrKind threads=$threads cluster_threshold=$clusterThreshold " +
        "decoding=$decoding/$maxActivePaths shift=$windowShiftRatio vad=$vadModel chunk_pad=${cfg.chunkPadMs}")
    val timings = StringBuilder("{\n")
    var invalid = 0
    files.forEachIndexed { idx, f -> try {
        val t0 = System.currentTimeMillis()
        val samples = WavReader.read16kMono(f, agc = (opt["agc"] ?: "off") == "on")
        val durationMs = samples.size * 1000L / 16000
        val tDecode = System.currentTimeMillis()

        val vad = Vad(vadCfg)
        val segs = ArrayList<SpeechSegment>()
        var i = 0
        while (i < samples.size) {
            val end = minOf(i + 512, samples.size)
            val w = if (end - i == 512) samples.copyOfRange(i, end) else samples.copyOfRange(i, end) + FloatArray(512 - (end - i))
            vad.acceptWaveform(w)
            while (!vad.empty()) {
                val s = vad.front(); val st = s.start * 1000L / 16000
                segs += SpeechSegment(st, st + s.samples.size * 1000L / 16000); vad.pop()
            }
            i = end
        }
        vad.flush()
        while (!vad.empty()) { val s = vad.front(); val st = s.start * 1000L / 16000; segs += SpeechSegment(st, st + s.samples.size * 1000L / 16000); vad.pop() }
        vad.release()
        val tVad = System.currentTimeMillis()

        val words = ArrayList<Word>()
        for (c in Chunker.chunks(segs, durationMs, cfg)) {
            val a = (c.startMs * 16).toInt().coerceIn(0, samples.size); val b = (c.endMs * 16).toInt().coerceIn(a, samples.size)
            if (b - a < 160) continue
            val stream = recognizer.createStream()
            stream.acceptWaveform(samples.copyOfRange(a, b), 16000)
            recognizer.decode(stream)
            val r = recognizer.getResult(stream)
            var cw = TokenWords.toWords(r.tokens, r.timestamps, c.startMs, c.endMs)
            stream.release()
            if (punctRecognizer != null && cw.isNotEmpty()) {
                val ps = punctRecognizer.createStream()
                ps.acceptWaveform(samples.copyOfRange(a, b), 16000)
                punctRecognizer.decode(ps)
                cw = PunctTransfer.transfer(cw, punctRecognizer.getResult(ps).text)
                ps.release()
            }
            words += cw
        }
        val tAsr = System.currentTimeMillis()

        val turns = runCatching {
            diarizer.process(samples).map { SpeakerTurn(it.speaker, (it.start * 1000).toLong(), (it.end * 1000).toLong()) }
        }.getOrElse { System.err.println("diarization failed: $it"); emptyList() }
        val tDia = System.currentTimeMillis()

        val postTurns = clusterPost?.process(f.nameWithoutExtension, samples, turns, words, segs, durationMs) ?: turns
        var tr = TranscriptBuilder.build(words, postTurns, durationMs, segs, cfg = cfg)
        val tBuild = System.currentTimeMillis()
        var llmInfo = "llm off"
        if (llm != null && tr.utterances.isNotEmpty()) {
            val (edited, st) = GuardedEditor(llm).edit(tr, (durationMs * cfg.llmBudgetFraction).toLong().coerceAtLeast(5_000))
            tr = edited
            llmInfo = "llm ${System.currentTimeMillis() - tBuild} ms acc=${st.accepted} rej=${st.rejected} skip=${st.skipped} clean=${st.alreadyClean}"
        }
        val tEnd = System.currentTimeMillis()
        val errs = ContractValidator.validate(tr)
        if (errs.isNotEmpty()) { invalid++; System.err.println("${f.name}: CONTRACT $errs") }
        File(outDir, f.nameWithoutExtension + ".json").writeText(JsonExporter.toJson(tr))
        val proc = tEnd - t0
        val spk = tr.utterances.map { it.speakerId }.distinct().size
        println("%-30s audio=%7.1fs proc=%6.1fs rtf=%.3f utt=%d spk=%d (decode %d, vad %d, asr %d, diar %d ms)".format(
            f.name, durationMs / 1000.0, proc / 1000.0, proc.toDouble() / durationMs, tr.utterances.size, spk,
            tDecode - t0, tVad - tDecode, tAsr - tVad, tDia - tAsr) + " " + llmInfo)
        timings.append("  \"${f.nameWithoutExtension}\": {\"proc_ms\": $proc, \"audio_ms\": $durationMs, \"asr_ms\": ${tAsr - tVad}, \"diar_ms\": ${tDia - tAsr}, \"llm_ms\": ${tEnd - tBuild}}")
            .append(if (idx < files.size - 1) ",\n" else "\n")
    } catch (e: Exception) {
        // Битый/неподдерживаемый файл — понятная ошибка, прогон продолжается.
        invalid++
        System.err.println("${f.name}: ERROR ${e.message}")
        timings.append("  \"${f.nameWithoutExtension}\": {\"error\": ${JsonExporter.quote(e.message ?: e.toString())}}")
            .append(if (idx < files.size - 1) ",\n" else "\n")
    } }
    timings.append("}\n")
    File(outDir, "timings.json").writeText(timings.toString())
    recognizer.release(); punctRecognizer?.release(); diarizer.release(); llm?.close(); clusterPost?.close()
    if (invalid > 0) exitProcess(1)
}

/** Минимальный WAV-ридер (RIFF, PCM 16/24/32 и float32) + та же нормализация, что на телефоне. */
object WavReader {
    fun read16kMono(f: File, agc: Boolean = false): FloatArray {
        val bytes = f.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "${f.name}: not a WAV" }
        var pos = 12; var fmt = 1; var ch = 1; var rate = 16000; var bits = 16
        var dataOff = -1; var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4); val len = bb.getInt(pos + 4)
            if (id == "fmt ") {
                fmt = bb.getShort(pos + 8).toInt() and 0xffff; ch = bb.getShort(pos + 10).toInt()
                rate = bb.getInt(pos + 12); bits = bb.getShort(pos + 22).toInt()
                if (fmt == 0xFFFE) fmt = bb.getShort(pos + 32).toInt() // WAVE_FORMAT_EXTENSIBLE subformat
            } else if (id == "data") { dataOff = pos + 8; dataLen = minOf(len, bytes.size - dataOff); break }
            pos += 8 + len + (len and 1)
        }
        require(dataOff > 0) { "${f.name}: no data chunk" }
        val bps = bits / 8; val frames = dataLen / (bps * ch)
        val mono = FloatArray(frames) { i ->
            var s = 0f
            for (c in 0 until ch) {
                val o = dataOff + (i * ch + c) * bps
                s += when {
                    fmt == 3 && bits == 32 -> bb.getFloat(o)
                    bits == 16 -> bb.getShort(o) / 32768f
                    bits == 24 -> ((bytes[o].toInt() and 0xff) or ((bytes[o + 1].toInt() and 0xff) shl 8) or (bytes[o + 2].toInt() shl 16)) / 8388608f
                    bits == 32 -> bb.getInt(o) / 2147483648f
                    bits == 8 -> ((bytes[o].toInt() and 0xff) - 128) / 128f
                    else -> error("unsupported bits $bits")
                }
            }
            s / ch
        }
        val res = DcBlocker().process(StreamingResampler(rate, 16000).process(mono, flush = true))
        // нормализация громкости (как AudioDecoder на Android)
        val frame = 480
        val rms = FloatArray(res.size / frame) { k ->
            var a = 0.0; for (j in k * frame until (k + 1) * frame) a += res[j] * res[j]; sqrt(a / frame).toFloat()
        }
        val peak = res.maxOfOrNull { abs(it) } ?: 0f
        val g = Loudness.gain(rms, peak)
        val norm = FloatArray(res.size) { (res[it] * g).coerceIn(-1f, 1f) }
        if (!agc) return norm
        // динамическая нормализация (как AudioDecoder на телефоне при включённом AGC)
        val floor = ru.neurosteno.core.dsp.SpeechAgc.noiseFloor(FloatArray(rms.size) { rms[it] * g })
        return ru.neurosteno.core.dsp.SpeechAgc(floor).process(norm)
    }
}
