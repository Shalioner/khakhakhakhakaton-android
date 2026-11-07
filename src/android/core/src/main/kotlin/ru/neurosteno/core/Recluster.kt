package ru.neurosteno.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Повторная кластеризация эмбеддингов окон (ClusterPost.windows) поверх турнов sherpa — без новых моделей.
 * Методы (docs/research/2026-11-07-diarization-methods.md):
 *  - NME: спектральная кластеризация с автоподбором p-обрезки аффинити по нормированному максимальному
 *    eigengap (Park et al., NME-SC, arXiv 2003.02405); число спикеров = argmax eigengap в [minK, maxK];
 *  - SC: то же с фиксированной долей соседей pval (как 3D-Speaker, pval 0.02..);
 *  - AHC: агломеративная average linkage по косинусу; остановка по порогу (fixed), по порогу Оцу
 *    на распределении косинусов файла (otsu) или по максимуму силуэта K=2..maxK (sil, K=1 если силуэт < silMin).
 * После кластеризации: (а) мелкие кластеры (< tinyMs / tinyFrac длительности окон) — в ближайший центроид;
 * (б) необязательное сглаживание Витерби по времени (штраф за смену метки между соседними окнами);
 * (в) метки переносятся на турны: window — каждое окно своей меткой, turn — большинство окон турна,
 *     cluster — большинство окон старого кластера (только слияния).
 */
object Recluster {

    enum class Method { NME, SC, AHC, AHC_OTSU, AHC_SIL }
    enum class Assign { WINDOW, TURN, CLUSTER }

    data class Config(
        val method: Method = Method.NME,
        val assign: Assign = Assign.TURN,
        val minSpeakers: Int = 1,
        val maxSpeakers: Int = 8,
        /** Окон меньше — кластеризация не меняется (спектральная на коротких файлах ненадёжна). */
        val minWindows: Int = 10,
        /** Окон больше — кластеризуется равномерная по времени выборка, остальные — к ближайшему центроиду. */
        val maxWindows: Int = 300,
        /** NME: максимальная доля соседей p/n и число опробованных значений p. */
        val nmeMaxFrac: Float = 0.25f,
        val nmeSteps: Int = 20,
        /** SC: доля соседей, остающихся в строке аффинити. */
        val pval: Float = 0.1f,
        /** Бинаризовать аффинити после p-обрезки (как NME-SC) или оставить косинусы (как 3D-Speaker). */
        val binarize: Boolean = true,
        /** AHC: порог среднего косинуса для слияния. */
        val ahcThreshold: Float = 0.5f,
        /** AHC_OTSU: сдвиг к порогу Оцу. */
        val otsuShift: Float = 0f,
        /** AHC_SIL: если лучший силуэт K≥2 меньше — один спикер. */
        val silMin: Float = 0.1f,
        /** Мелкий кластер (длительность окон) — в ближайший по центроиду. */
        val tinyMs: Long = 0,
        val tinyFrac: Float = 0f,
        /** Витерби: штраф за смену метки между соседними окнами с паузой ≤ smoothGapMs; 0 — выкл. */
        val smoothPenalty: Float = 0f,
        val smoothGapMs: Long = 1000,
        /**
         * Защита крупных кластеров: окна старого кластера с ≥ guardWindows окон сохраняют его метку (его уже
         * проверил ClusterPost по ratio, а похожие голоса эмбеддинг всё равно не разделит); окна мелких кластеров
         * уходят в крупный с той же новой меткой (большинство его окон) и ближайшим центроидом, а если такого
         * нет — остаются с новой меткой. 0 — выкл.
         */
        val guardWindows: Int = 0,
        /** Старых кластеров с окнами меньше — не трогать (один кластер sherpa не дробится). */
        val minOldClusters: Int = 2,
    )

    // ---------- кластеризация векторов ----------

    private fun normalized(embs: List<FloatArray>): Array<DoubleArray> = Array(embs.size) { i ->
        val e = embs[i]; var nn = 0.0
        for (x in e) nn += x * x
        val inv = if (nn > 0) 1.0 / sqrt(nn) else 0.0
        DoubleArray(e.size) { e[it] * inv }
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double { var s = 0.0; for (i in a.indices) s += a[i] * b[i]; return s }

    private fun cosMatrix(x: Array<DoubleArray>): Array<DoubleArray> {
        val n = x.size
        val s = Array(n) { DoubleArray(n) }
        for (i in 0 until n) { s[i][i] = 1.0; for (j in i + 1 until n) { val c = dot(x[i], x[j]); s[i][j] = c; s[j][i] = c } }
        return s
    }

    /** Метки 0..K-1 для нормированных векторов. */
    fun cluster(x: Array<DoubleArray>, cfg: Config): IntArray {
        val n = x.size
        if (n == 0) return IntArray(0)
        if (n == 1) return IntArray(1)
        val s = cosMatrix(x)
        return when (cfg.method) {
            Method.NME, Method.SC -> spectral(s, cfg)
            Method.AHC -> ahc(s, cfg, threshold = cfg.ahcThreshold.toDouble())
            Method.AHC_OTSU -> ahc(s, cfg, threshold = otsu(s) + cfg.otsuShift)
            Method.AHC_SIL -> ahcSilhouette(s, cfg)
        }
    }

    /** Аффинити: в каждой строке остаются p наибольших (включая диагональ), симметризация (A+Aᵀ)/2. */
    private fun pruned(s: Array<DoubleArray>, p: Int, binarize: Boolean): Array<DoubleArray> {
        val n = s.size
        val a = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            val idx = (0 until n).sortedByDescending { s[i][it] }
            for (r in 0 until min(p, n)) { val j = idx[r]; a[i][j] = if (binarize) 1.0 else max(0.0, s[i][j]) }
        }
        for (i in 0 until n) for (j in i + 1 until n) { val m = 0.5 * (a[i][j] + a[j][i]); a[i][j] = m; a[j][i] = m }
        return a
    }

    private fun laplacian(a: Array<DoubleArray>): Array<DoubleArray> {
        val n = a.size
        return Array(n) { i ->
            var d = 0.0
            for (j in 0 until n) if (j != i) d += a[i][j]
            DoubleArray(n) { j -> if (i == j) d else -a[i][j] }
        }
    }

    /** K по максимальному eigengap среди λ[minK-1..maxK] (λ по возрастанию) и сам нормированный gap. */
    private fun eigengap(vals: DoubleArray, minK: Int, maxK: Int): Pair<Int, Double> {
        val kMax = min(maxK, vals.size - 1)
        var bestK = max(1, minK); var bestGap = -1.0
        for (k in max(1, minK)..max(max(1, minK), kMax)) {
            if (k >= vals.size) break
            val g = vals[k] - vals[k - 1]
            if (g > bestGap) { bestGap = g; bestK = k }
        }
        val lmax = vals.last().coerceAtLeast(1e-10)
        return bestK to bestGap / lmax
    }

    private fun spectral(s: Array<DoubleArray>, cfg: Config): IntArray {
        val n = s.size
        val minK = cfg.minSpeakers.coerceAtLeast(1)
        val maxK = cfg.maxSpeakers.coerceAtMost(n - 1).coerceAtLeast(minK)
        val p = if (cfg.method == Method.SC) {
            max(2, (cfg.pval * n).toInt())
        } else {
            // NME: p̂ = argmin (p/n) / g_p, g_p — нормированный максимальный eigengap
            val pMax = max(2, (cfg.nmeMaxFrac * n).toInt())
            val cands = (0 until cfg.nmeSteps).map { 1 + ((pMax - 1) * it.toLong() / max(1, cfg.nmeSteps - 1)).toInt() }
                .map { it.coerceIn(2, max(2, n - 1)) }.distinct()
            var best = cands.first(); var bestR = Double.MAX_VALUE
            for (pc in cands) {
                val vals = SymEigen.decompose(laplacian(pruned(s, pc, true)), vectors = false).values
                val (_, g) = eigengap(vals, minK, maxK)
                val r = (pc.toDouble() / n) / (g + 1e-10)
                if (r < bestR) { bestR = r; best = pc }
            }
            best
        }
        val eig = SymEigen.decompose(laplacian(pruned(s, p, cfg.binarize)), vectors = true)
        val (k, _) = eigengap(eig.values, minK, maxK)
        if (k <= 1) return IntArray(n)
        val y = Array(n) { i -> DoubleArray(k) { c -> eig.vectors!![c][i] } }
        return kmeans(y, k)
    }

    /** k-means++ (фиксированный seed), 10 перезапусков, минимальная инерция. */
    fun kmeans(y: Array<DoubleArray>, k: Int, restarts: Int = 10, seed: Int = 0): IntArray {
        val n = y.size; val dim = y[0].size
        fun d2(a: DoubleArray, b: DoubleArray): Double { var s = 0.0; for (i in 0 until dim) { val t = a[i] - b[i]; s += t * t }; return s }
        val rnd = Random(seed)
        var bestLab = IntArray(n); var bestInertia = Double.MAX_VALUE
        repeat(restarts) {
            val cent = ArrayList<DoubleArray>()
            cent += y[rnd.nextInt(n)].copyOf()
            while (cent.size < k) {
                val dist = DoubleArray(n) { i -> cent.minOf { c -> d2(y[i], c) } }
                val tot = dist.sum()
                if (tot <= 0) { cent += y[rnd.nextInt(n)].copyOf(); continue }
                var r = rnd.nextDouble() * tot; var pick = n - 1
                for (i in 0 until n) { r -= dist[i]; if (r <= 0) { pick = i; break } }
                cent += y[pick].copyOf()
            }
            val lab = IntArray(n)
            for (iter in 0 until 100) {
                var changed = false
                for (i in 0 until n) {
                    var bj = 0; var bd = Double.MAX_VALUE
                    for (j in 0 until k) { val dd = d2(y[i], cent[j]); if (dd < bd) { bd = dd; bj = j } }
                    if (lab[i] != bj) { changed = true; lab[i] = bj }
                }
                for (j in 0 until k) {
                    val members = (0 until n).filter { lab[it] == j }
                    if (members.isEmpty()) continue
                    cent[j] = DoubleArray(dim) { c -> members.sumOf { y[it][c] } / members.size }
                }
                if (!changed && iter > 0) break // iter 0: метки 0 могли совпасть случайно — центроиды всё равно пересчитаны
            }
            val inertia = (0 until n).sumOf { d2(y[it], cent[lab[it]]) }
            if (inertia < bestInertia) { bestInertia = inertia; bestLab = lab.copyOf() }
        }
        return compact(bestLab)
    }

    /** Перенумерация меток по первому появлению 0..K-1. */
    fun compact(lab: IntArray): IntArray {
        val m = HashMap<Int, Int>()
        return IntArray(lab.size) { m.getOrPut(lab[it]) { m.size } }
    }

    /** История слияний average linkage: список (a, b, sim), кластеры с индексами строк исходной матрицы. */
    private fun ahcHistory(s: Array<DoubleArray>): List<Triple<Int, Int, Double>> {
        val n = s.size
        val sim = Array(n) { s[it].copyOf() }
        val size = IntArray(n) { 1 }
        val alive = BooleanArray(n) { true }
        val hist = ArrayList<Triple<Int, Int, Double>>()
        repeat(n - 1) {
            var bi = -1; var bj = -1; var bs = -Double.MAX_VALUE
            for (i in 0 until n) if (alive[i]) for (j in i + 1 until n) if (alive[j] && sim[i][j] > bs) { bs = sim[i][j]; bi = i; bj = j }
            hist += Triple(bi, bj, bs)
            for (c in 0 until n) if (alive[c] && c != bi && c != bj) {
                val v = (size[bi] * sim[bi][c] + size[bj] * sim[bj][c]) / (size[bi] + size[bj])
                sim[bi][c] = v; sim[c][bi] = v
            }
            size[bi] += size[bj]; alive[bj] = false
        }
        return hist
    }

    /** Разбиение после первых `merges` слияний. */
    private fun cut(n: Int, hist: List<Triple<Int, Int, Double>>, merges: Int): IntArray {
        val parent = IntArray(n) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
        for (m in 0 until merges) { val (a, b, _) = hist[m]; parent[find(b)] = find(a) }
        return compact(IntArray(n) { find(it) })
    }

    private fun ahc(s: Array<DoubleArray>, cfg: Config, threshold: Double): IntArray {
        val n = s.size
        val hist = ahcHistory(s)
        var merges = 0
        while (merges < hist.size && (hist[merges].third > threshold || n - merges > cfg.maxSpeakers)) merges++
        while (merges > 0 && n - merges < cfg.minSpeakers) merges--
        return cut(n, hist, merges)
    }

    /** Порог Оцу по внедиагональным косинусам файла (максимум межклассовой дисперсии, 100 корзин). */
    fun otsu(s: Array<DoubleArray>): Double {
        val v = ArrayList<Double>()
        for (i in s.indices) for (j in i + 1 until s.size) v += s[i][j]
        if (v.size < 2) return 0.5
        val lo = v.min(); val hi = v.max()
        if (hi - lo < 1e-9) return lo
        val bins = 100
        val h = DoubleArray(bins)
        for (x in v) h[((x - lo) / (hi - lo) * (bins - 1)).toInt()] += 1.0
        val tot = v.size.toDouble()
        val mean = (0 until bins).sumOf { it * h[it] } / tot
        var w0 = 0.0; var m0 = 0.0; var best = 0; var bestVar = -1.0
        for (t in 0 until bins - 1) {
            w0 += h[t] / tot; m0 += t * h[t] / tot
            val w1 = 1 - w0
            if (w0 <= 0 || w1 <= 0) continue
            val mu0 = m0 / w0; val mu1 = (mean - m0) / w1
            val bv = w0 * w1 * (mu0 - mu1) * (mu0 - mu1)
            if (bv > bestVar) { bestVar = bv; best = t }
        }
        return lo + (best + 0.5) / (bins - 1) * (hi - lo)
    }

    /** Средний силуэт по косинусной дистанции 1−cos; одиночные кластеры дают 0. */
    fun silhouette(s: Array<DoubleArray>, lab: IntArray): Double {
        val n = s.size
        val k = (lab.maxOrNull() ?: 0) + 1
        if (k < 2) return 0.0
        val cnt = IntArray(k).also { c -> lab.forEach { c[it]++ } }
        var tot = 0.0
        for (i in 0 until n) {
            if (cnt[lab[i]] <= 1) continue
            val sum = DoubleArray(k)
            for (j in 0 until n) if (j != i) sum[lab[j]] += 1 - s[i][j]
            val a = sum[lab[i]] / (cnt[lab[i]] - 1)
            var b = Double.MAX_VALUE
            for (c in 0 until k) if (c != lab[i] && cnt[c] > 0) b = min(b, sum[c] / cnt[c])
            tot += (b - a) / max(a, b).coerceAtLeast(1e-12)
        }
        return tot / n
    }

    private fun ahcSilhouette(s: Array<DoubleArray>, cfg: Config): IntArray {
        val n = s.size
        val hist = ahcHistory(s)
        var best: IntArray = IntArray(n); var bestS = -Double.MAX_VALUE
        for (k in max(2, cfg.minSpeakers)..min(cfg.maxSpeakers, n - 1)) {
            val lab = cut(n, hist, n - k)
            val sc = silhouette(s, lab)
            if (sc > bestS) { bestS = sc; best = lab }
        }
        return if (bestS < cfg.silMin && cfg.minSpeakers <= 1) IntArray(n) else best
    }

    // ---------- постобработка меток окон ----------

    private fun centroids(x: Array<DoubleArray>, lab: IntArray, k: Int): Array<DoubleArray> {
        val dim = x[0].size
        val c = Array(k) { DoubleArray(dim) }
        for (i in x.indices) for (d in 0 until dim) c[lab[i]][d] += x[i][d]
        for (cc in c) { val nn = sqrt(dot(cc, cc)); if (nn > 0) for (d in cc.indices) cc[d] /= nn }
        return c
    }

    private fun absorbTiny(x: Array<DoubleArray>, w: List<EmbWindow>, lab: IntArray, cfg: Config): IntArray {
        val k = (lab.maxOrNull() ?: 0) + 1
        if (k < 2) return lab
        val dur = LongArray(k); for (i in lab.indices) dur[lab[i]] += w[i].durationMs
        val limit = max(cfg.tinyMs, (cfg.tinyFrac * dur.sum()).toLong())
        if (limit <= 0) return lab
        val big = (0 until k).filter { dur[it] >= limit }
        if (big.isEmpty() || big.size == k) return lab
        val c = centroids(x, lab, k)
        return compact(IntArray(lab.size) { i ->
            if (dur[lab[i]] >= limit) lab[i] else big.maxByOrNull { dot(x[i], c[it]) }!!
        })
    }

    /** Витерби: max Σ cos(x_t, c_l) − penalty·[смена метки между окнами с паузой ≤ gap]. */
    private fun smooth(x: Array<DoubleArray>, w: List<EmbWindow>, lab: IntArray, cfg: Config): IntArray {
        val k = (lab.maxOrNull() ?: 0) + 1
        if (k < 2 || cfg.smoothPenalty <= 0f) return lab
        val order = w.indices.sortedBy { w[it].startMs }
        val c = centroids(x, lab, k)
        val n = order.size
        val score = Array(n) { DoubleArray(k) }
        val back = Array(n) { IntArray(k) }
        for (l in 0 until k) score[0][l] = dot(x[order[0]], c[l])
        for (t in 1 until n) {
            val gap = w[order[t]].startMs - w[order[t - 1]].endMs
            val pen = if (gap <= cfg.smoothGapMs) cfg.smoothPenalty.toDouble() else 0.0
            for (l in 0 until k) {
                var bp = 0; var bv = -Double.MAX_VALUE
                for (q in 0 until k) { val v = score[t - 1][q] - if (q == l) 0.0 else pen; if (v > bv) { bv = v; bp = q } }
                score[t][l] = bv + dot(x[order[t]], c[l]); back[t][l] = bp
            }
        }
        val out = IntArray(lab.size)
        var l = (0 until k).maxByOrNull { score[n - 1][it] }!!
        for (t in n - 1 downTo 0) { out[order[t]] = l; if (t > 0) l = back[t][l] }
        return compact(out)
    }

    /** Метки окон (индексы как в [embs]); null — оставить исходную кластеризацию. */
    fun windowLabels(embs: List<WindowEmbedding>, cfg: Config): IntArray? {
        val n = embs.size
        if (n < max(2, cfg.minWindows)) return null
        val w = embs.map { it.window }
        if (w.map { it.speakerIdx }.distinct().size < cfg.minOldClusters) return null
        val x = normalized(embs.map { it.emb })
        // выборка для кластеризации — равномерно по времени
        val order = w.indices.sortedBy { w[it].startMs }
        val sample = if (n <= cfg.maxWindows) order else List(cfg.maxWindows) { order[(it.toLong() * n / cfg.maxWindows).toInt()] }
        val sl = cluster(Array(sample.size) { x[sample[it]] }, cfg)
        var lab = IntArray(n) { -1 }
        sample.forEachIndexed { i, idx -> lab[idx] = sl[i] }
        if (sample.size < n) {
            val k = (sl.maxOrNull() ?: 0) + 1
            val c = centroids(Array(sample.size) { x[sample[it]] }, sl, k)
            for (i in 0 until n) if (lab[i] < 0) lab[i] = (0 until k).maxByOrNull { dot(x[i], c[it]) }!!
        }
        lab = absorbTiny(x, w, lab, cfg)
        lab = smooth(x, w, lab, cfg)
        if (cfg.guardWindows > 0) lab = guard(x, w, lab, cfg.guardWindows)
        return lab
    }

    private fun guard(x: Array<DoubleArray>, w: List<EmbWindow>, lab: IntArray, guardWindows: Int): IntArray {
        val byOld = w.indices.groupBy { w[it].speakerIdx }
        val big = byOld.filterValues { it.size >= guardWindows }
        if (big.isEmpty()) return lab
        val bigMaj = big.mapValues { (_, idx) -> idx.groupingBy { lab[it] }.eachCount().maxByOrNull { it.value }!!.key }
        val dim = x[0].size
        val cent = big.mapValues { (_, idx) ->
            val c = DoubleArray(dim); for (i in idx) for (d in 0 until dim) c[d] += x[i][d]
            val nn = sqrt(dot(c, c)); if (nn > 0) for (d in 0 until dim) c[d] /= nn; c
        }
        val anchor = big.keys.withIndex().associate { (k, old) -> old to 1_000_000 + k }
        return compact(IntArray(lab.size) { i ->
            val old = w[i].speakerIdx
            anchor[old] ?: big.keys.filter { bigMaj[it] == lab[i] }.maxByOrNull { dot(x[i], cent.getValue(it)) }?.let { anchor.getValue(it) } ?: lab[i]
        })
    }

    /**
     * Турны с новыми метками. Кластеры без окон получают свои метки (>= 10000 + старый индекс) —
     * их по-прежнему растворяет SpeakerRelabel.absorbTinySpeakers.
     */
    fun apply(turns: List<SpeakerTurn>, embs: List<WindowEmbedding>, cfg: Config = Config()): List<SpeakerTurn> {
        if (turns.isEmpty()) return turns
        val lab = windowLabels(embs, cfg) ?: return turns
        val w = embs.map { it.window }
        // окна → турн (окно лежит внутри турна своего кластера)
        val byTurn = turns.indices.associateWith { ArrayList<Int>() }
        for (i in w.indices) {
            val t = turns.indices.firstOrNull { val tt = turns[it]; tt.speakerIdx == w[i].speakerIdx && w[i].startMs >= tt.startMs && w[i].endMs <= tt.endMs }
                ?: turns.indices.filter { turns[it].speakerIdx == w[i].speakerIdx }
                    .maxByOrNull { min(turns[it].endMs, w[i].endMs) - max(turns[it].startMs, w[i].startMs) }
                ?: continue
            byTurn.getValue(t) += i
        }
        fun majority(idx: List<Int>): Int? = idx.groupBy { lab[it] }.maxByOrNull { (_, l) -> l.sumOf { w[it].durationMs } }?.key
        val clusterMaj: Map<Int, Int?> = turns.map { it.speakerIdx }.distinct().associateWith { spk -> majority(w.indices.filter { w[it].speakerIdx == spk }) }
        val out = ArrayList<SpeakerTurn>()
        turns.forEachIndexed { ti, t ->
            val idx = byTurn.getValue(ti)
            val fallback = clusterMaj[t.speakerIdx] ?: (10_000 + t.speakerIdx)
            when {
                cfg.assign == Assign.CLUSTER || idx.isEmpty() -> out += t.copy(speakerIdx = fallback)
                cfg.assign == Assign.TURN -> out += t.copy(speakerIdx = majority(idx)!!)
                else -> {
                    // каждое окно — своей меткой; промежутки — метка ближайшего окна турна
                    val ws = idx.sortedBy { w[it].startMs }
                    val bounds = ArrayList<Long>()
                    bounds += t.startMs
                    for (k in 0 until ws.size - 1) bounds += (w[ws[k]].endMs + w[ws[k + 1]].startMs) / 2
                    bounds += t.endMs
                    for (k in ws.indices) {
                        val s = bounds[k]; val e = bounds[k + 1]
                        if (e <= s) continue
                        val l = lab[ws[k]]
                        val last = out.lastOrNull()
                        if (k > 0 && last != null && last.speakerIdx == l && last.endMs == s) out[out.size - 1] = last.copy(endMs = e)
                        else out += SpeakerTurn(l, s, e)
                    }
                }
            }
        }
        return out.sortedBy { it.startMs }
    }

    /**
     * Шаг конвейера: ClusterPost (слияние по ratio) → Recluster по тем же эмбеддингам окон
     * (индексы кластеров у окон переводятся в объединённые). [rc] = null — только ClusterPost.
     */
    fun afterClusterPost(turns: List<SpeakerTurn>, embs: List<WindowEmbedding>, cp: ClusterPost.Config, rc: Config?): List<SpeakerTurn> {
        val m = ClusterPost.remap(turns, embs, cp)
        val t1 = turns.map { it.copy(speakerIdx = m[it.speakerIdx] ?: it.speakerIdx) }
        if (rc == null) return t1
        val e1 = embs.map { WindowEmbedding(EmbWindow(m[it.window.speakerIdx] ?: it.window.speakerIdx, it.window.startMs, it.window.endMs), it.emb) }
        return apply(t1, e1, rc)
    }

    /** Число различных меток (для логов). */
    fun count(turns: List<SpeakerTurn>) = turns.map { it.speakerIdx }.distinct().size
}
