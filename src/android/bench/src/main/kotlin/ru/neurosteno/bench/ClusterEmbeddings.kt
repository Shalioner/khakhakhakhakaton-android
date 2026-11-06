package ru.neurosteno.bench

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import ru.neurosteno.core.ClusterPost
import ru.neurosteno.core.ContractValidator
import ru.neurosteno.core.JsonExporter
import ru.neurosteno.core.PipelineConfig
import ru.neurosteno.core.SpeakerTurn
import ru.neurosteno.core.SpeechSegment
import ru.neurosteno.core.TranscriptBuilder
import ru.neurosteno.core.WindowEmbedding
import ru.neurosteno.core.Word
import java.io.File

/**
 * Постобработка кластеров для bench (то же, что SherpaClusterEmbedder на телефоне):
 * эмбеддинги CAM++ на чистых окнах турнов → ClusterPost (слияние по центроидам + мелкие кластеры).
 * Опции: --cluster-post on|off (по умолчанию off), --cp-ratio 0.97, --cp-merge 1.1 (косинус центроидов),
 * --cp-tiny-ms 0, --cp-tiny-frac 0, --cp-dump <dir> (дамп турнов/слов/VAD/эмбеддингов).
 * Перебор по дампам без пересчёта моделей: bench cluster-sweep <dump_dir> <out_root> <name>=<c|r>:<порог>:<tinyMs>:<tinyFrac>[:<minWindows>][@minSpeakerMs] ...
 */
class ClusterEmbeddings(modelPath: String, threads: Int, val cfg: ClusterPost.Config, private val dumpDir: File?) : AutoCloseable {
    private val extractor = SpeakerEmbeddingExtractor(
        SpeakerEmbeddingExtractorConfig.builder().setModel(modelPath).setNumThreads(threads).build(),
    )

    fun embed(samples: FloatArray, turns: List<SpeakerTurn>): List<WindowEmbedding> =
        ClusterPost.windows(turns, cfg).mapNotNull { w ->
            val a = (w.startMs * 16).toInt().coerceIn(0, samples.size)
            val b = (w.endMs * 16).toInt().coerceIn(a, samples.size)
            if (b - a < 1600) return@mapNotNull null
            val s = extractor.createStream()
            try {
                s.acceptWaveform(samples.copyOfRange(a, b), 16000)
                s.inputFinished()
                if (!extractor.isReady(s)) null else WindowEmbedding(w, extractor.compute(s))
            } finally { s.release() }
        }

    /** Турны после постобработки (и дамп промежуточных данных для перебора порогов без пересчёта ASR). */
    fun process(name: String, samples: FloatArray, turns: List<SpeakerTurn>, words: List<Word>, vad: List<SpeechSegment>, durationMs: Long): List<SpeakerTurn> {
        if (turns.isEmpty()) return turns
        val t0 = System.currentTimeMillis()
        val embs = embed(samples, turns)
        dumpDir?.let { writeDump(File(it.apply { mkdirs() }, "$name.cp.txt"), durationMs, turns, vad, words, embs) }
        val out = ClusterPost.apply(turns, embs, cfg)
        println("cluster-post $name: ${turns.map { it.speakerIdx }.distinct().size} -> ${out.map { it.speakerIdx }.distinct().size} clusters, " +
            "${embs.size} windows, ${System.currentTimeMillis() - t0} ms")
        return out
    }

    override fun close() = extractor.release()

    companion object {
        fun fromOptions(opt: Map<String, String>, embModel: String, threads: Int): ClusterEmbeddings? {
            if ((opt["cluster-post"] ?: "off") != "on") return null
            val d = ClusterPost.Config.from(PipelineConfig())
            val cfg = d.copy(
                mergeCos = opt["cp-merge"]?.toFloat() ?: d.mergeCos,
                mergeRatio = opt["cp-ratio"]?.toFloat() ?: d.mergeRatio,
                tinyMs = opt["cp-tiny-ms"]?.toLong() ?: d.tinyMs,
                tinyFrac = opt["cp-tiny-frac"]?.toFloat() ?: d.tinyFrac,
            )
            println("cluster-post: $cfg")
            return ClusterEmbeddings(embModel, threads, cfg, opt["cp-dump"]?.let(::File))
        }

        fun writeDump(f: File, durationMs: Long, turns: List<SpeakerTurn>, vad: List<SpeechSegment>, words: List<Word>, embs: List<WindowEmbedding>) {
            f.bufferedWriter().use { o ->
                o.write("D $durationMs\n")
                turns.forEach { o.write("T ${it.speakerIdx} ${it.startMs} ${it.endMs}\n") }
                vad.forEach { o.write("V ${it.startMs} ${it.endMs}\n") }
                words.forEach { o.write("W ${it.startMs} ${it.endMs} ${it.conf ?: "nan"} ${it.text}\n") }
                embs.forEach { e -> o.write("P ${e.window.speakerIdx} ${e.window.startMs} ${e.window.endMs} ${e.emb.joinToString(",")}\n") }
            }
        }

        private class Dump(val durationMs: Long, val turns: List<SpeakerTurn>, val vad: List<SpeechSegment>, val words: List<Word>, val embs: List<WindowEmbedding>)

        private fun readDump(f: File): Dump {
            var d = 0L
            val t = ArrayList<SpeakerTurn>(); val v = ArrayList<SpeechSegment>(); val w = ArrayList<Word>(); val p = ArrayList<WindowEmbedding>()
            f.forEachLine { line ->
                val x = line.split(' ', limit = 5)
                when (x[0]) {
                    "D" -> d = x[1].toLong()
                    "T" -> t += SpeakerTurn(x[1].toInt(), x[2].toLong(), x[3].toLong())
                    "V" -> v += SpeechSegment(x[1].toLong(), x[2].toLong())
                    "W" -> w += Word(x[4], x[1].toLong(), x[2].toLong(), x[3].toFloatOrNull())
                    "P" -> p += WindowEmbedding(
                        ru.neurosteno.core.EmbWindow(x[1].toInt(), x[2].toLong(), x[3].toLong()),
                        x[4].split(',').map { it.toFloat() }.toFloatArray(),
                    )
                }
            }
            return Dump(d, t, v, w, p)
        }

        /** bench cluster-sweep <dump_dir> <out_root> base=off r97=r:0.97:0:0:3 ... — пишет JSON в <out_root>/<name>. */
        fun sweep(args: List<String>) {
            val dumpDir = File(args[0]); val outRoot = File(args[1])
            val cfg = PipelineConfig()
            val dumps = dumpDir.listFiles { f -> f.name.endsWith(".cp.txt") }!!.sorted().map { it.name.removeSuffix(".cp.txt") to readDump(it) }
            for (spec in args.drop(2)) {
                val (name, p0) = spec.split('=', limit = 2)
                // необязательный хвост "@<minSpeakerTotalMs>" — порог растворения мелких спикеров по времени
                val p = p0.substringBefore('@')
                val bcfg = p0.substringAfter('@', "").toLongOrNull()?.let { cfg.copy(minSpeakerTotalMs = it) } ?: cfg
                val out = File(outRoot, name).apply { mkdirs() }
                // спецификация: off | <c|r>:<порог>:<tinyMs>:<tinyFrac>  (c — косинус центроидов, r — cross/within)
                val pc = if (p == "off") null else p.split(':').let {
                    val thr = it[1].toFloat()
                    ClusterPost.Config(
                        mergeCos = if (it[0] == "c") thr else 1.1f, mergeRatio = if (it[0] == "r") thr else 2f,
                        tinyMs = it[2].toLong(), tinyFrac = it[3].toFloat(),
                        minWindowsForMerge = it.getOrNull(4)?.toInt() ?: 3,
                    )
                }
                for ((n, d) in dumps) {
                    val turns = if (pc == null) d.turns else ClusterPost.apply(d.turns, d.embs, pc)
                    val tr = TranscriptBuilder.build(d.words, turns, d.durationMs, d.vad, cfg = bcfg)
                    check(ContractValidator.validate(tr).isEmpty()) { "$n: contract" }
                    File(out, "$n.json").writeText(JsonExporter.toJson(tr))
                }
                println("sweep $name: ${dumps.size} files")
            }
        }
    }
}
