package ru.neurosteno.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokenChunkTest {

    @Test
    fun bpeTokensToWordsWithPunctuation() {
        val tokens = arrayOf("▁При", "вет", ",", "▁как", "▁дела", "?")
        val ts = floatArrayOf(0.10f, 0.30f, 0.50f, 0.80f, 1.20f, 1.50f)
        val w = TokenWords.toWords(tokens, ts, offsetMs = 1000, chunkEndMs = 3000)
        assertEquals(listOf("Привет,", "как", "дела?"), w.map { it.text })
        assertEquals(1100, w[0].startMs)
        assertEquals(1800, w[1].startMs)
        assertTrue(w[0].endMs <= w[1].startMs)
        assertTrue(w.all { it.endMs > it.startMs && it.endMs <= 3000 })
    }

    @Test
    fun charTokensWithSpaces() {
        val tokens = arrayOf("д", "а", " ", "н", "е", "т")
        val ts = floatArrayOf(0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f)
        val w = TokenWords.toWords(tokens, ts, 0, 1000)
        assertEquals(listOf("да", "нет"), w.map { it.text })
    }

    @Test
    fun chunksRespectLimitAndPauses() {
        val cfg = PipelineConfig(maxChunkMs = 10_000, chunkPadMs = 200)
        val segs = listOf(SpeechSegment(0, 4000), SpeechSegment(4500, 9000), SpeechSegment(9500, 14_000), SpeechSegment(30_000, 31_000))
        val ch = Chunker.chunks(segs, 40_000, cfg)
        assertEquals(3, ch.size)
        assertTrue(ch.all { it.endMs - it.startMs <= 10_000 + 400 })
        assertEquals(29_800, ch[2].startMs)
    }

    @Test
    fun longSegmentSplitHard() {
        val ch = Chunker.chunks(listOf(SpeechSegment(0, 60_000)), 60_000, PipelineConfig(maxChunkMs = 25_000))
        assertEquals(3, ch.size)
        assertEquals(60_000, ch.last().endMs)
    }
}
