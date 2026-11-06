package ru.neurosteno.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClusterPostTest {

    private fun we(spk: Int, s: Long, e: Long, vararg v: Float) = WindowEmbedding(EmbWindow(spk, s, e), v)

    @Test
    fun windowsExcludeOtherSpeakersAndSplitLongParts() {
        val turns = listOf(
            SpeakerTurn(0, 0, 14_000),
            SpeakerTurn(1, 5_000, 5_800), // перекрытие: вырезается из турна 0
        )
        val cfg = ClusterPost.Config(minWindowMs = 1000, maxWindowMs = 6000)
        val w = ClusterPost.windows(turns, cfg)
        // спикер 0: [0,5000) → одно окно, [5800,14000) = 8.2 с → два окна; спикер 1: 800 мс < 1 с → нет
        assertEquals(listOf(EmbWindow(0, 0, 5000), EmbWindow(0, 5800, 9900), EmbWindow(0, 9900, 14_000)), w)
        assertTrue(w.none { it.speakerIdx == 1 })
    }

    @Test
    fun mergesClustersWithCloseCentroids() {
        val turns = listOf(SpeakerTurn(0, 0, 5000), SpeakerTurn(1, 5000, 10_000), SpeakerTurn(2, 10_000, 15_000))
        val embs = listOf(
            we(0, 0, 5000, 1f, 0f, 0f),
            we(1, 5000, 10_000, 0.95f, 0.1f, 0f), // тот же голос, что 0
            we(2, 10_000, 15_000, 0f, 1f, 0f),
        )
        val m = ClusterPost.remap(turns, embs, ClusterPost.Config(mergeCos = 0.8f))
        assertEquals(0, m[1])
        assertEquals(2, m[2])
        assertEquals(0, m[0])
    }

    @Test
    fun mergeStopsAtMinSpeakersAndThreshold() {
        val turns = listOf(SpeakerTurn(0, 0, 5000), SpeakerTurn(1, 5000, 10_000))
        val embs = listOf(we(0, 0, 5000, 1f, 0f), we(1, 5000, 10_000, 0.9f, 0.43f))
        assertEquals(1, ClusterPost.remap(turns, embs, ClusterPost.Config(mergeCos = 0.95f))[1])
        assertEquals(1, ClusterPost.remap(turns, embs, ClusterPost.Config(mergeCos = 0.5f, minSpeakers = 2))[1])
        assertEquals(0, ClusterPost.remap(turns, embs, ClusterPost.Config(mergeCos = 0.5f))[1])
    }

    @Test
    fun tinyClusterGoesToNearestByEmbeddingNotByTime() {
        // мелкий кластер 2 стоит по времени рядом с 0, но по голосу ближе к 1
        val turns = listOf(
            SpeakerTurn(0, 0, 20_000), SpeakerTurn(2, 20_000, 21_500),
            SpeakerTurn(1, 30_000, 50_000),
        )
        val embs = listOf(we(0, 0, 6000, 1f, 0f), we(1, 30_000, 36_000, 0f, 1f), we(2, 20_000, 21_500, 0.3f, 0.9f))
        val m = ClusterPost.remap(turns, embs, ClusterPost.Config(mergeCos = 0.99f, tinyMs = 3000))
        assertEquals(1, m[2])
        assertEquals(0, m[0])
        assertEquals(1, m[1])
    }

    @Test
    fun clusterWithoutEmbeddingIsLeftForTimeBasedAbsorb() {
        val turns = listOf(SpeakerTurn(0, 0, 20_000), SpeakerTurn(1, 20_000, 20_500))
        val embs = listOf(we(0, 0, 6000, 1f, 0f))
        val m = ClusterPost.remap(turns, embs, ClusterPost.Config(mergeCos = 0.5f, tinyMs = 3000))
        assertEquals(mapOf(0 to 0, 1 to 1), m)
    }

    /** Окна вокруг направления (1, x) с разбросом по третьей оси. */
    private fun cluster(spk: Int, t0: Long, x: Float, n: Int) =
        (0 until n).map { k -> we(spk, t0 + k * 6000L, t0 + (k + 1) * 6000L, 1f, x, if (k % 2 == 0) 0.3f else -0.3f) }

    @Test
    fun ratioMergesIndistinguishableClustersOnly() {
        val turns = listOf(SpeakerTurn(0, 0, 18_000), SpeakerTurn(1, 20_000, 38_000), SpeakerTurn(2, 40_000, 58_000))
        // 0 и 1 — одно распределение окон (cross ≈ within), 2 — другой голос
        val embs = cluster(0, 0, 0f, 3) + cluster(1, 20_000, 0.05f, 3) + cluster(2, 40_000, 3f, 3)
        val m = ClusterPost.remap(turns, embs, ClusterPost.Config(mergeRatio = 0.97f, minWindowsForMerge = 3))
        assertEquals(0, m[1])
        assertEquals(2, m[2])
    }

    @Test
    fun ratioMergeNeedsEnoughWindows() {
        val turns = listOf(SpeakerTurn(0, 0, 18_000), SpeakerTurn(1, 20_000, 32_000))
        val embs = cluster(0, 0, 0f, 3) + cluster(1, 20_000, 0f, 2)
        assertEquals(1, ClusterPost.remap(turns, embs, ClusterPost.Config(mergeRatio = 0.9f, minWindowsForMerge = 3))[1])
        assertEquals(0, ClusterPost.remap(turns, embs, ClusterPost.Config(mergeRatio = 0.9f, minWindowsForMerge = 2))[1])
    }

    @Test
    fun windowsCappedEvenlyPerCluster() {
        val turns = listOf(SpeakerTurn(0, 0, 60_000))
        val w = ClusterPost.windows(turns, ClusterPost.Config(maxWindowMs = 6000, maxWindowsPerCluster = 4))
        assertEquals(4, w.size)
        assertEquals(listOf(0L, 12_000L, 30_000L, 42_000L), w.map { it.startMs })
    }

    @Test
    fun disabledByDefaultKeepsClusters() {
        val turns = listOf(SpeakerTurn(0, 0, 5000), SpeakerTurn(1, 5000, 10_000))
        val embs = listOf(we(0, 0, 5000, 1f, 0f), we(1, 5000, 10_000, 1f, 0f))
        assertEquals(mapOf(0 to 0, 1 to 1), ClusterPost.remap(turns, embs, ClusterPost.Config()))
    }

    @Test
    fun applyRelabelsTurnsAndKeepsTimes() {
        val turns = listOf(SpeakerTurn(3, 0, 5000), SpeakerTurn(7, 5000, 10_000))
        val embs = listOf(we(3, 0, 5000, 1f, 0f), we(7, 5000, 10_000, 1f, 0.01f))
        val out = ClusterPost.apply(turns, embs, ClusterPost.Config(mergeCos = 0.9f))
        assertEquals(listOf(SpeakerTurn(3, 0, 5000), SpeakerTurn(3, 5000, 10_000)), out)
    }
}
