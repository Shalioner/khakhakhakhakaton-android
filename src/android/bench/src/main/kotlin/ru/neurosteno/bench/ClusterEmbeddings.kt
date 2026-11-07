package ru.neurosteno.bench

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import ru.neurosteno.core.ClusterPost
import ru.neurosteno.core.ContractValidator
import ru.neurosteno.core.JsonExporter
import ru.neurosteno.core.PipelineConfig
import ru.neurosteno.core.Recluster
import ru.neurosteno.core.SpeakerTurn
import ru.neurosteno.core.SpeechSegment
import ru.neurosteno.core.TranscriptBuilder
import ru.neurosteno.core.WindowEmbedding
import ru.neurosteno.core.Word
import java.io.File

/**
 * Постобработка кластеров для bench (то же, что SherpaClusterEmbedder на телефоне):
 * эмбеддинги CAM++ на чистых окнах турнов → ClusterPost (слияние по центроидам + мелкие кластеры).
 * Опции: --cluster-post on|off (по умолчанию on), --cp-ratio 0.97, --cp-merge 1.1 (косинус центроидов),
 * --cp-tiny-ms 0, --cp-tiny-frac 0, --cp-max-windows 40, --cp-recluster <ключи Recluster>, --cp-dump <dir> (дамп турнов/слов/VAD/эмбеддингов).
 * Перебор по дампам без пересчёта моделей: bench cluster-sweep <dump_dir> <out_root> <name>=<c|r>:<порог>:<tinyMs>:<tinyFrac>[:<minWindows>][@minSpeakerMs] ...
 */
class ClusterEmbeddings(modelPath: String, threads: Int, val cfg: ClusterPost.Config, private val dumpDir: File?, private val rc: Recluster.Config? = null) : AutoCloseable {
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
        val out = Recluster.afterClusterPost(turns, embs, cfg, rc)
        println("cluster-post $name: ${turns.map { it.speakerIdx }.distinct().size} -> ${out.map { it.speakerIdx }.distinct().size} clusters, " +
            "${embs.size} windows, ${System.currentTimeMillis() - t0} ms")
        return out
    }

    override fun close() = extractor.release()

    companion object {
        fun fromOptions(opt: Map<String, String>, embModel: String, threads: Int): ClusterEmbeddings? {
            if ((opt["cluster-post"] ?: "on") != "on") return null
            val pipeline = PipelineConfig()
            val d = ClusterPost.Config.from(pipeline)
            val cfg = d.copy(
                mergeCos = opt["cp-merge"]?.toFloat() ?: d.mergeCos,
                mergeRatio = opt["cp-ratio"]?.toFloat() ?: d.mergeRatio,
                tinyMs = opt["cp-tiny-ms"]?.toLong() ?: d.tinyMs,
                tinyFrac = opt["cp-tiny-frac"]?.toFloat() ?: d.tinyFrac,
                maxWindowsPerCluster = opt["cp-max-windows"]?.toInt() ?: d.maxWindowsPerCluster,
            )
            // По умолчанию как в приложении. Для сравнения только с ClusterPost: --cp-recluster off.
            val rc = when (val spec = opt["cp-recluster"]) {
                null -> pipeline.reclusterConfig()
                "off" -> null
                else -> reclusterConfig(spec.split(',').filter { it.isNotBlank() }.associate {
                    it.substringBefore('=') to it.substringAfter('=')
                })
            }
            println("cluster-post: $cfg recluster: $rc")
            return ClusterEmbeddings(embModel, threads, cfg, opt["cp-dump"]?.let(::File), rc)
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
                // спецификация: off | <c|r>:<порог>:<tinyMs>:<tinyFrac>[:<minWindows>[:<cap>[:<tinyWindows>]]]  (c — косинус центроидов, r — cross/within)
                //               | x:<ключ>=<знач>,...  — Recluster (см. reclusterConfig); cap — окон на кластер, как в APK (40)
                var cap = 40
                var rc: Recluster.Config? = null
                val pc = if (p == "off" || p.startsWith("x:")) null else p.split(':').let {
                    val thr = it[1].toFloat()
                    it.getOrNull(5)?.let { c -> cap = c.toInt() }
                    ClusterPost.Config(
                        mergeCos = if (it[0] == "c") thr else 1.1f, mergeRatio = if (it[0] == "r") thr else 2f,
                        tinyMs = it[2].toLong(), tinyFrac = it[3].toFloat(),
                        minWindowsForMerge = it.getOrNull(4)?.toInt() ?: 3,
                        tinyWindows = it.getOrNull(6)?.toInt() ?: 0,
                    )
                }
                var pre: ClusterPost.Config? = null
                if (p.startsWith("x:")) {
                    val kv = p.removePrefix("x:").split(',').filter { it.isNotBlank() }.associate { it.substringBefore('=') to it.substringAfter('=') }
                    kv["cap"]?.let { cap = it.toInt() }
                    // pre=r:0.97 — сначала слияние ClusterPost по ratio (как в APK), затем Recluster
                    kv["pre"]?.let { pre = ClusterPost.Config(mergeRatio = it.toFloat()) }
                    rc = reclusterConfig(kv)
                }
                var ksum = 0
                var rcNs = 0L; var rcMax = 0L
                for ((n, d) in dumps) {
                    val embs = capPerCluster(d.embs, cap)
                    var turns = when {
                        pc != null -> ClusterPost.apply(d.turns, embs, pc)
                        rc != null -> {
                            val t0 = pre?.let { ClusterPost.apply(d.turns, embs, it) } ?: d.turns
                            val e0 = pre?.let { pcfg -> val m = ClusterPost.remap(d.turns, embs, pcfg); embs.map { WindowEmbedding(ru.neurosteno.core.EmbWindow(m[it.window.speakerIdx] ?: it.window.speakerIdx, it.window.startMs, it.window.endMs), it.emb) } } ?: embs
                            val tn = System.nanoTime()
                            Recluster.apply(t0, e0, rc!!).also { rcNs += System.nanoTime() - tn; rcMax = maxOf(rcMax, System.nanoTime() - tn) }
                        }
                        else -> d.turns
                    }
                    ksum += Recluster.count(turns)
                    val tr = TranscriptBuilder.build(d.words, turns, d.durationMs, d.vad, cfg = bcfg)
                    check(ContractValidator.validate(tr).isEmpty()) { "$n: contract" }
                    File(out, "$n.json").writeText(JsonExporter.toJson(tr))
                }
                println("sweep $name: ${dumps.size} files, clusters ${"%.1f".format(ksum.toDouble() / dumps.size)} per file, recluster ${rcNs / 1_000_000} ms total, max ${rcMax / 1_000_000} ms")
            }
        }

        /** Окна на кластер как в ClusterPost.windows (равномерно по времени), чтобы дамп без ограничения воспроизводил APK. */
        fun capPerCluster(embs: List<WindowEmbedding>, cap: Int): List<WindowEmbedding> =
            embs.groupBy { it.window.speakerIdx }.values.flatMap { list ->
                if (list.size <= cap) list else List(cap) { k -> list[(k.toLong() * list.size / cap).toInt()] }
            }.sortedBy { it.window.startMs }

        /** m=nme|sc|ahc|otsu|sil, a=window|turn|cluster, minw, maxw, frac, steps, pval, bin=0|1, thr, shift, sil, tiny, tinyf, smooth, gap, guard, minold. */
        fun reclusterConfig(kv: Map<String, String>): Recluster.Config {
            val d = Recluster.Config()
            return d.copy(
                method = when (kv["m"] ?: "nme") {
                    "nme" -> Recluster.Method.NME; "sc" -> Recluster.Method.SC; "ahc" -> Recluster.Method.AHC
                    "otsu" -> Recluster.Method.AHC_OTSU; "sil" -> Recluster.Method.AHC_SIL; else -> error("m=${kv["m"]}")
                },
                assign = when (kv["a"] ?: "turn") {
                    "window" -> Recluster.Assign.WINDOW; "turn" -> Recluster.Assign.TURN; "cluster" -> Recluster.Assign.CLUSTER; else -> error("a=${kv["a"]}")
                },
                minWindows = kv["minw"]?.toInt() ?: d.minWindows,
                maxWindows = kv["maxw"]?.toInt() ?: d.maxWindows,
                nmeMaxFrac = kv["frac"]?.toFloat() ?: d.nmeMaxFrac,
                nmeSteps = kv["steps"]?.toInt() ?: d.nmeSteps,
                pval = kv["pval"]?.toFloat() ?: d.pval,
                binarize = kv["bin"]?.let { it == "1" } ?: d.binarize,
                ahcThreshold = kv["thr"]?.toFloat() ?: d.ahcThreshold,
                otsuShift = kv["shift"]?.toFloat() ?: d.otsuShift,
                silMin = kv["sil"]?.toFloat() ?: d.silMin,
                tinyMs = kv["tiny"]?.toLong() ?: d.tinyMs,
                tinyFrac = kv["tinyf"]?.toFloat() ?: d.tinyFrac,
                smoothPenalty = kv["smooth"]?.toFloat() ?: d.smoothPenalty,
                smoothGapMs = kv["gap"]?.toLong() ?: d.smoothGapMs,
                guardWindows = kv["guard"]?.toInt() ?: d.guardWindows,
                minOldClusters = kv["minold"]?.toInt() ?: d.minOldClusters,
            )
        }
    }
}
