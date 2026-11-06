package ru.neurosteno.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Окно аудио одного кластера, по которому считается эмбеддинг (без перекрытий с другими спикерами). */
data class EmbWindow(val speakerIdx: Int, val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

/** Эмбеддинг окна (L2-нормировка не обязательна — делается внутри). */
class WindowEmbedding(val window: EmbWindow, val emb: FloatArray)

/**
 * Постобработка кластеров диаризации по эмбеддингам, посчитанным заново на чистых окнах турнов.
 * FastClustering sherpa-onnx (complete linkage, один порог по эмбеддингам 10-секундных чанков) то дробит
 * одного диктора на несколько кластеров, то оставляет мелкие кластеры из 1–2 с речи. Здесь:
 *  1) статистика кластера — сумма S и число n нормированных эмбеддингов окон; из неё
 *     cross(i,j) = S_i·S_j / (n_i n_j) — средний косинус между окнами двух кластеров,
 *     within(i) = (|S_i|² − n_i) / (n_i (n_i − 1)) — средний косинус окон внутри кластера;
 *  2) агломеративное слияние ближайшей пары, пока критерий выше порога:
 *     ratio = cross / within (окна одного кластера похожи на окна другого так же, как друг на друга —
 *     эмбеддинг их не различает; адаптивно к условиям записи) или косинус центроидов (mergeCos);
 *  3) мелкие кластеры (речь < tinyMs или < tinyFrac от всей речи) — в крупный с максимальным cross
 *     (как min_cluster_size в pyannote), а не к соседу по времени.
 * Чистая логика без Android; движок только считает эмбеддинги окон из [windows].
 */
object ClusterPost {

    data class Config(
        /** Окна короче не считаются (эмбеддинг на < 1 с шумный). */
        val minWindowMs: Long = 1000,
        /** Длинные куски режутся на окна не длиннее. */
        val maxWindowMs: Long = 6000,
        /** Не больше окон на кластер (равномерно по времени) — ограничивает время на длинных файлах. */
        val maxWindowsPerCluster: Int = 40,
        /** Слияние по косинусу центроидов больше порога; >= 1 — выключено. */
        val mergeCos: Float = 1.1f,
        /** Слияние по cross/within больше порога; > 1.5 — выключено. */
        val mergeRatio: Float = 2f,
        /** Для слияния по ratio у обоих кластеров должно быть столько окон (оценка within/cross надёжна). */
        val minWindowsForMerge: Int = 3,
        /** Кластер с суммарной речью меньше — «мелкий». */
        val tinyMs: Long = 0,
        /** ... или меньше этой доли всей речи. */
        val tinyFrac: Float = 0f,
        /** Нижняя граница числа спикеров при слиянии. */
        val minSpeakers: Int = 1,
    ) {
        companion object {
            fun from(p: PipelineConfig) = Config(
                mergeCos = p.clusterPostMergeCos,
                mergeRatio = p.clusterPostMergeRatio,
                minWindowsForMerge = p.clusterPostMinWindows,
                tinyMs = p.clusterPostTinyMs,
                tinyFrac = p.clusterPostTinyFrac,
                minSpeakers = p.minSpeakers,
            )
        }
    }

    /**
     * Окна для эмбеддингов: части турнов, не перекрытые турнами других кластеров, режутся на куски
     * ≤ maxWindowMs; куски < minWindowMs отбрасываются. Если у кластера окон больше maxWindowsPerCluster,
     * берутся равномерно по времени.
     */
    fun windows(turns: List<SpeakerTurn>, cfg: Config = Config()): List<EmbWindow> {
        val sorted = turns.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
        val out = ArrayList<EmbWindow>()
        for (t in sorted) {
            var parts = listOf(t.startMs to t.endMs)
            for (o in sorted) {
                if (o.speakerIdx == t.speakerIdx || o.endMs <= t.startMs || o.startMs >= t.endMs) continue
                parts = parts.flatMap { (s, e) ->
                    if (o.endMs <= s || o.startMs >= e) listOf(s to e)
                    else listOfNotNull(
                        if (o.startMs > s) s to o.startMs else null,
                        if (o.endMs < e) o.endMs to e else null,
                    )
                }
            }
            for ((s, e) in parts) out += split(t.speakerIdx, s, e, cfg)
        }
        val cap = cfg.maxWindowsPerCluster
        return out.groupBy { it.speakerIdx }.values.flatMap { list ->
            if (list.size <= cap) list else List(cap) { k -> list[(k.toLong() * list.size / cap).toInt()] }
        }.sortedBy { it.startMs }
    }

    private fun split(spk: Int, s: Long, e: Long, cfg: Config): List<EmbWindow> {
        val len = e - s
        if (len < cfg.minWindowMs) return emptyList()
        val n = max(1L, (len + cfg.maxWindowMs - 1) / cfg.maxWindowMs).toInt()
        val step = len / n
        return (0 until n).map { i -> EmbWindow(spk, s + i * step, if (i == n - 1) e else s + (i + 1) * step) }
    }

    /** Сумма нормированных эмбеддингов и их число. */
    private class Stat(val sum: DoubleArray, var n: Int) {
        fun add(o: Stat) { for (i in sum.indices) sum[i] += o.sum[i]; n += o.n }
        fun within(): Double? = if (n < 2) null else (dot(sum, sum) - n) / (n.toDouble() * (n - 1))
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double { var s = 0.0; for (i in a.indices) s += a[i] * b[i]; return s }
    private fun cross(a: Stat, b: Stat) = dot(a.sum, b.sum) / (a.n.toDouble() * b.n)
    private fun centroidCos(a: Stat, b: Stat): Double {
        val d = sqrt(dot(a.sum, a.sum) * dot(b.sum, b.sum))
        return if (d == 0.0) 0.0 else dot(a.sum, b.sum) / d
    }

    /** cross / within; within — среднее по кластерам, где оно определено (n ≥ 2). null — не определено. */
    private fun ratio(a: Stat, b: Stat): Double? {
        val ws = listOfNotNull(a.within(), b.within())
        if (ws.isEmpty()) return null
        val w = ws.average()
        return if (w <= 0.0) null else cross(a, b) / w
    }

    /** Центроиды кластеров (сумма нормированных эмбеддингов окон). */
    fun centroids(embs: List<WindowEmbedding>): Map<Int, FloatArray> =
        stats(embs).mapValues { (_, s) -> FloatArray(s.sum.size) { s.sum[it].toFloat() } }

    private fun stats(embs: List<WindowEmbedding>): Map<Int, Stat> {
        val out = HashMap<Int, Stat>()
        for (we in embs) {
            var nn = 0.0
            for (x in we.emb) nn += x * x
            if (nn == 0.0) continue
            val inv = 1.0 / sqrt(nn)
            val st = out.getOrPut(we.window.speakerIdx) { Stat(DoubleArray(we.emb.size), 0) }
            for (i in we.emb.indices) st.sum[i] += we.emb[i] * inv
            st.n++
        }
        return out
    }

    /**
     * Отображение старый индекс кластера → новый. Кластеры без эмбеддингов (все турны короче minWindowMs)
     * не трогаются: их обработает SpeakerRelabel.absorbTinySpeakers по времени.
     */
    fun remap(turns: List<SpeakerTurn>, embs: List<WindowEmbedding>, cfg: Config = Config()): Map<Int, Int> {
        val ids = turns.map { it.speakerIdx }.distinct().sorted()
        val identity = ids.associateWith { it }
        if (ids.size <= 1 || embs.isEmpty()) return identity
        val speech = HashMap<Int, Long>()
        turns.forEach { speech.merge(it.speakerIdx, it.endMs - it.startMs, Long::plus) }
        val totalSpeech = speech.values.sum().coerceAtLeast(1)

        val parent = ids.associateWith { it }.toMutableMap()
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]!!; return r }
        val st = stats(embs).filterKeys { it in parent }.toMutableMap()
        fun liveCount() = ids.count { find(it) == it }
        val byRatio = cfg.mergeRatio <= 1.5f
        val byCos = cfg.mergeCos < 1f

        // 1) агломеративное слияние ближайшей пары
        while ((byRatio || byCos) && st.size > 1 && liveCount() > cfg.minSpeakers) {
            var best: Triple<Int, Int, Double>? = null
            val keys = st.keys.sorted()
            for (i in keys.indices) for (j in i + 1 until keys.size) {
                val a = st.getValue(keys[i]); val b = st.getValue(keys[j])
                if (byRatio && min(a.n, b.n) < cfg.minWindowsForMerge) continue
                val score = if (byRatio) ratio(a, b) ?: continue else centroidCos(a, b)
                if (best == null || score > best.third) best = Triple(keys[i], keys[j], score)
            }
            val thr = if (byRatio) cfg.mergeRatio.toDouble() else cfg.mergeCos.toDouble()
            if (best == null || best.third <= thr) break
            val keep = min(best.first, best.second); val drop = max(best.first, best.second)
            parent[drop] = keep
            st.getValue(keep).add(st.remove(drop)!!)
            speech[keep] = (speech[keep] ?: 0) + (speech.remove(drop) ?: 0)
        }

        // 2) мелкие кластеры → крупный с максимальным средним косинусом окон
        val tinyLimit = max(cfg.tinyMs, (cfg.tinyFrac * totalSpeech).toLong())
        if (tinyLimit > 0) {
            val roots = st.keys.toList()
            val big = roots.filter { (speech[it] ?: 0) >= tinyLimit }
            if (big.isNotEmpty()) {
                for (r in roots.filter { (speech[it] ?: 0) < tinyLimit }) {
                    val s = st.getValue(r)
                    parent[r] = big.maxByOrNull { cross(s, st.getValue(it)) }!!
                }
            }
        }
        return ids.associateWith { find(it) }
    }

    /** Применяет [remap] к турнам. */
    fun apply(turns: List<SpeakerTurn>, embs: List<WindowEmbedding>, cfg: Config = Config()): List<SpeakerTurn> {
        val m = remap(turns, embs, cfg)
        return turns.map { it.copy(speakerIdx = m[it.speakerIdx] ?: it.speakerIdx) }
    }
}
