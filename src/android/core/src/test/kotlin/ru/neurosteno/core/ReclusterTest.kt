package ru.neurosteno.core

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SymEigenTest {

    private fun randomSym(n: Int, seed: Int): Array<DoubleArray> {
        val r = Random(seed)
        val a = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in i until n) { val v = r.nextDouble(-1.0, 1.0); a[i][j] = v; a[j][i] = v }
        return a
    }

    @Test
    fun diagonalAndKnown2x2() {
        val r = SymEigen.decompose(arrayOf(doubleArrayOf(2.0, 1.0), doubleArrayOf(1.0, 2.0)))
        assertEquals(1.0, r.values[0], 1e-12); assertEquals(3.0, r.values[1], 1e-12)
        val v = r.vectors!![0]
        assertEquals(abs(v[0]), abs(v[1]), 1e-12); assertTrue(v[0] * v[1] < 0)
        val d = SymEigen.decompose(arrayOf(doubleArrayOf(5.0, 0.0, 0.0), doubleArrayOf(0.0, -1.0, 0.0), doubleArrayOf(0.0, 0.0, 2.0)))
        assertEquals(listOf(-1.0, 2.0, 5.0), d.values.toList())
        assertEquals(1, SymEigen.decompose(arrayOf(doubleArrayOf(7.0))).values.size)
    }

    @Test
    fun randomMatricesSatisfyAvEqualsLambdaVAndOrthonormal() {
        for ((n, seed) in listOf(3 to 1, 10 to 2, 57 to 3, 200 to 4)) {
            val a = randomSym(n, seed)
            val r = SymEigen.decompose(a)
            val vs = r.vectors!!
            for (k in 0 until n) {
                for (i in 0 until n) {
                    var av = 0.0; for (j in 0 until n) av += a[i][j] * vs[k][j]
                    assertEquals(r.values[k] * vs[k][i], av, 1e-8, "n=$n k=$k")
                }
            }
            for (k in 0 until n) for (l in k until n) {
                var d = 0.0; for (i in 0 until n) d += vs[k][i] * vs[l][i]
                assertEquals(if (k == l) 1.0 else 0.0, d, 1e-9)
            }
            for (k in 1 until n) assertTrue(r.values[k] >= r.values[k - 1])
            // только значения — те же числа
            val only = SymEigen.decompose(a, vectors = false)
            for (k in 0 until n) assertEquals(r.values[k], only.values[k], 1e-9)
            // след сохраняется
            assertEquals((0 until n).sumOf { a[it][it] }, r.values.sum(), 1e-9)
        }
    }

    @Test
    fun laplacianOfTwoComponentsHasTwoZeroEigenvalues() {
        // два полных подграфа по 4 вершины без связей между ними
        val n = 8
        val l = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 3.0 else if (i / 4 == j / 4) -1.0 else 0.0 } }
        val v = SymEigen.decompose(l, vectors = false).values
        assertEquals(0.0, v[0], 1e-10); assertEquals(0.0, v[1], 1e-10); assertEquals(4.0, v[2], 1e-10)
    }
}

class ReclusterTest {

    /** Окна k спикеров: эмбеддинг = центр спикера + шум, спикеры чередуются по времени. */
    private fun synth(k: Int, perSpk: Int, dim: Int = 16, noise: Double = 0.25, seed: Int = 0, oldIdx: (Int, Int) -> Int = { s, _ -> s }): List<WindowEmbedding> {
        val r = Random(seed)
        val centers = List(k) { DoubleArray(dim) { r.nextDouble(-1.0, 1.0) } }
        val out = ArrayList<WindowEmbedding>()
        var t = 0L
        for (i in 0 until perSpk) for (s in 0 until k) {
            val e = FloatArray(dim) { (centers[s][it] + noise * r.nextGaussian()).toFloat() }
            out += WindowEmbedding(EmbWindow(oldIdx(s, i), t, t + 2000), e)
            t += 2500
        }
        return out
    }

    private fun Random.nextGaussian(): Double {
        val u = nextDouble().coerceAtLeast(1e-12); val v = nextDouble()
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * v)
    }

    private fun purityOk(embs: List<WindowEmbedding>, lab: IntArray, truth: (Int) -> Int) {
        // каждый новый кластер содержит окна одного истинного спикера
        val groups = embs.indices.groupBy { lab[it] }
        for ((_, idx) in groups) assertEquals(1, idx.map(truth).distinct().size)
    }

    @Test
    fun spectralNmeFindsNumberOfSpeakers() {
        for (k in listOf(2, 3, 5)) {
            val embs = synth(k, 12, seed = k)
            val lab = Recluster.windowLabels(embs, Recluster.Config(method = Recluster.Method.NME))!!
            assertEquals(k, lab.max() + 1, "k=$k")
            purityOk(embs, lab) { embs[it].window.speakerIdx }
        }
    }

    @Test
    fun spectralFixedPvalAndAhcVariants() {
        val embs = synth(3, 12, seed = 7)
        for (cfg in listOf(
            Recluster.Config(method = Recluster.Method.SC, pval = 0.2f),
            Recluster.Config(method = Recluster.Method.AHC, ahcThreshold = 0.3f),
            Recluster.Config(method = Recluster.Method.AHC_OTSU),
            Recluster.Config(method = Recluster.Method.AHC_SIL),
        )) {
            val lab = Recluster.windowLabels(embs, cfg)!!
            assertEquals(3, lab.max() + 1, cfg.method.name)
            purityOk(embs, lab) { embs[it].window.speakerIdx }
        }
    }

    @Test
    fun singleSpeakerWithSilhouetteAndRespectsMaxSpeakers() {
        val one = synth(1, 30, noise = 0.3, seed = 3)
        assertEquals(1, Recluster.windowLabels(one, Recluster.Config(method = Recluster.Method.AHC_SIL, silMin = 0.3f, minOldClusters = 1))!!.max() + 1)
        val many = synth(10, 4, noise = 0.05, seed = 4)
        val lab = Recluster.windowLabels(many, Recluster.Config(method = Recluster.Method.AHC, ahcThreshold = 0.99f, maxSpeakers = 8))!!
        assertTrue(lab.max() + 1 <= 8)
    }

    @Test
    fun tooFewWindowsKeepsOriginal() {
        val turns = listOf(SpeakerTurn(0, 0, 3000), SpeakerTurn(1, 3000, 6000))
        val embs = synth(2, 2)
        assertEquals(turns, Recluster.apply(turns, embs, Recluster.Config(minWindows = 10)))
    }

    @Test
    fun overSegmentedClustersAreMergedTurnAndClusterAssign() {
        // 2 истинных спикера, sherpa раздробила каждого на 3 кластера (старый индекс = спикер*3 + i%3)
        val embs = synth(2, 15, seed = 11) { s, i -> s * 3 + i % 3 }
        val turns = embs.map { SpeakerTurn(it.window.speakerIdx, it.window.startMs, it.window.endMs) }
        for (assign in Recluster.Assign.values()) {
            val out = Recluster.apply(turns, embs, Recluster.Config(method = Recluster.Method.NME, assign = assign))
            assertEquals(2, Recluster.count(out), assign.name)
            // турны старых кластеров 0..2 → одна метка, 3..5 → другая
            val m = out.zip(turns).groupBy({ it.second.speakerIdx / 3 }, { it.first.speakerIdx }).mapValues { it.value.distinct() }
            assertEquals(1, m.getValue(0).size); assertEquals(1, m.getValue(1).size)
            assertTrue(m.getValue(0) != m.getValue(1))
        }
    }

    @Test
    fun windowAssignSplitsMergedCluster() {
        // sherpa слила двух спикеров в один длинный турн; окна внутри него разделяются
        val embs = synth(2, 10, seed = 5) { _, _ -> 0 }
        val turns = listOf(SpeakerTurn(0, 0, embs.last().window.endMs))
        val out = Recluster.apply(turns, embs, Recluster.Config(method = Recluster.Method.NME, assign = Recluster.Assign.WINDOW, minOldClusters = 1))
        assertEquals(2, Recluster.count(out))
        assertEquals(0L, out.first().startMs); assertEquals(turns[0].endMs, out.last().endMs)
        for (k in 1 until out.size) assertEquals(out[k - 1].endMs, out[k].startMs) // без дыр и перекрытий
        // TURN-назначение не делит турн
        assertEquals(1, Recluster.count(Recluster.apply(turns, embs, Recluster.Config(assign = Recluster.Assign.TURN, minOldClusters = 1))))
    }

    @Test
    fun tinyClusterAbsorbedAndClustersWithoutWindowsKept() {
        // 2 спикера по 12 окон + 1 выброс из 1 окна далеко от обоих
        val base = synth(2, 12, seed = 9)
        val outlier = WindowEmbedding(EmbWindow(0, 100_000, 101_000), FloatArray(16) { if (it == 0) 5f else 0f })
        val embs = base + outlier
        val noTiny = Recluster.windowLabels(embs, Recluster.Config(method = Recluster.Method.AHC, ahcThreshold = 0.5f))!!
        assertEquals(3, noTiny.max() + 1)
        val tiny = Recluster.windowLabels(embs, Recluster.Config(method = Recluster.Method.AHC, ahcThreshold = 0.5f, tinyMs = 3000))!!
        assertEquals(2, tiny.max() + 1)
        // кластер 7 без окон сохраняет отдельную метку
        val turns = embs.map { SpeakerTurn(it.window.speakerIdx, it.window.startMs, it.window.endMs) } + SpeakerTurn(7, 200_000, 200_500)
        val out = Recluster.apply(turns, embs, Recluster.Config(method = Recluster.Method.AHC, ahcThreshold = 0.5f, tinyMs = 3000))
        assertEquals(3, Recluster.count(out))
        assertEquals(10_007, out.last().speakerIdx)
    }

    @Test
    fun viterbiSmoothingFixesIsolatedFlip() {
        val embs = synth(2, 15, noise = 0.2, seed = 2)
        // все окна спикера 0 подряд, затем спикера 1; в середине «нуля» одно окно с голосом спикера 1
        val sorted = embs.sortedBy { it.window.speakerIdx }.mapIndexed { i, e -> WindowEmbedding(EmbWindow(e.window.speakerIdx, i * 2100L, i * 2100L + 2000), e.emb) }
        val c1 = sorted.last().emb
        val flipped = sorted.toMutableList().also { it[7] = WindowEmbedding(it[7].window, c1.copyOf()) }
        val raw = Recluster.windowLabels(flipped, Recluster.Config(method = Recluster.Method.AHC, ahcThreshold = 0.3f))!!
        val sm = Recluster.windowLabels(flipped, Recluster.Config(method = Recluster.Method.AHC, ahcThreshold = 0.3f, smoothPenalty = 2f))!!
        assertTrue(raw[7] != raw[6], "без сглаживания окно 7 должно перейти к другому спикеру")
        assertEquals(sm[6], sm[7])
        assertEquals(2, sm.max() + 1)
    }

    @Test
    fun otsuSplitsBimodalCosines() {
        val s = Array(6) { i -> DoubleArray(6) { j -> if (i == j) 1.0 else if (i / 3 == j / 3) 0.8 else 0.1 } }
        val t = Recluster.otsu(s)
        assertTrue(t > 0.1 && t < 0.8, "t=$t")
        assertTrue(Recluster.silhouette(s, intArrayOf(0, 0, 0, 1, 1, 1)) > 0.5)
    }

    @Test
    fun singleOldClusterIsKept() {
        val embs = synth(2, 10, seed = 5) { _, _ -> 0 }
        assertEquals(null, Recluster.windowLabels(embs, Recluster.Config(method = Recluster.Method.AHC_SIL)))
    }

    @Test
    fun guardKeepsBigClustersAndMovesFragments() {
        // два больших старых кластера (0, 1) одного «голоса» + фрагменты 2..5 этого же голоса, и спикер 6 отдельно
        val r = Random(1)
        val v = FloatArray(16) { r.nextFloat() - 0.5f }
        val u = FloatArray(16) { r.nextFloat() - 0.5f }
        fun near(c: FloatArray) = FloatArray(16) { c[it] + 0.1f * (r.nextFloat() - 0.5f) }
        val embs = ArrayList<WindowEmbedding>()
        var t = 0L
        fun add(old: Int, c: FloatArray, k: Int) = repeat(k) { embs += WindowEmbedding(EmbWindow(old, t, t + 2000), near(c)); t += 2500 }
        add(0, v, 8); add(1, v, 8); for (f in 2..5) add(f, v, 1); add(6, u, 8)
        val plain = Recluster.windowLabels(embs, Recluster.Config(method = Recluster.Method.AHC_SIL))!!
        assertEquals(2, plain.max() + 1) // без защиты 0 и 1 слиты
        val g = Recluster.windowLabels(embs, Recluster.Config(method = Recluster.Method.AHC_SIL, guardWindows = 5))!!
        assertEquals(3, g.max() + 1) // 0, 1, 6 сохранены, фрагменты ушли в 0 или 1
        val lab = { old: Int -> embs.indices.filter { embs[it].window.speakerIdx == old }.map { g[it] }.distinct() }
        val big = setOf(lab(0).single(), lab(1).single())
        for (f in 2..5) assertTrue(lab(f).single() in big)
        assertTrue(lab(6).single() !in big)
    }

    @Test
    fun afterClusterPostWithoutReclusterEqualsClusterPost() {
        val embs = synth(2, 15, seed = 11) { s, i -> s * 3 + i % 3 }
        val turns = embs.map { SpeakerTurn(it.window.speakerIdx, it.window.startMs, it.window.endMs) }
        val cp = ClusterPost.Config(mergeRatio = 0.97f)
        val disabled = PipelineConfig(reclusterEnabled = false).reclusterConfig()
        assertEquals(null, disabled)
        assertEquals(ClusterPost.apply(turns, embs, cp), Recluster.afterClusterPost(turns, embs, cp, disabled))
        val rc = PipelineConfig().reclusterConfig()
        assertEquals(Recluster.Config(method = Recluster.Method.AHC_SIL, guardWindows = 15), rc)
        val out = Recluster.afterClusterPost(turns, embs, cp, rc)
        assertEquals(2, Recluster.count(out))
    }
}
