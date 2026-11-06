package ru.neurosteno.core

import kotlin.math.max
import kotlin.math.min

/**
 * Слово → спикер по максимальному перекрытию с турнами диаризации;
 * при нулевом перекрытии — ближайший турн не дальше nearestTurnMaxMs, иначе спикер соседнего слова.
 * Затем сглаживание одиночных «островков».
 */
object Alignment {

    const val UNKNOWN = -1

    fun assign(words: List<Word>, turns: List<SpeakerTurn>, cfg: PipelineConfig = PipelineConfig()): List<LabeledWord> {
        if (words.isEmpty()) return emptyList()
        if (turns.isEmpty()) return words.map { LabeledWord(it, 0) }
        val sortedTurns = turns.sortedBy { it.startMs }
        val raw = words.map { w -> LabeledWord(w, bestSpeaker(w, sortedTurns, cfg)) }
        val filled = fillUnknown(raw)
        return smooth(filled, sortedTurns, cfg)
    }

    internal fun overlap(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Long =
        max(0L, min(aEnd, bEnd) - max(aStart, bStart))

    private fun bestSpeaker(w: Word, turns: List<SpeakerTurn>, cfg: PipelineConfig): Int {
        val bySpeaker = HashMap<Int, Long>()
        for (t in turns) {
            if (t.startMs >= w.endMs) break
            val ov = overlap(w.startMs, w.endMs, t.startMs, t.endMs)
            if (ov > 0) bySpeaker.merge(t.speakerIdx, ov, Long::plus)
        }
        if (bySpeaker.isNotEmpty()) return bySpeaker.maxByOrNull { it.value }!!.key
        var best = UNKNOWN
        var bestDist = Long.MAX_VALUE
        for (t in turns) {
            val d = when {
                t.endMs <= w.startMs -> w.startMs - t.endMs
                t.startMs >= w.endMs -> t.startMs - w.endMs
                else -> 0L
            }
            if (d < bestDist) { bestDist = d; best = t.speakerIdx }
        }
        return if (bestDist <= cfg.nearestTurnMaxMs) best else UNKNOWN
    }

    /** Неизвестный спикер берётся у ближайшего по времени соседа с известным спикером. */
    private fun fillUnknown(words: List<LabeledWord>): List<LabeledWord> {
        if (words.none { it.speakerIdx == UNKNOWN }) return words
        if (words.all { it.speakerIdx == UNKNOWN }) return words.map { it.copy(speakerIdx = 0) }
        return words.mapIndexed { i, lw ->
            if (lw.speakerIdx != UNKNOWN) return@mapIndexed lw
            var l = i - 1
            while (l >= 0 && words[l].speakerIdx == UNKNOWN) l--
            var r = i + 1
            while (r < words.size && words[r].speakerIdx == UNKNOWN) r++
            val dl = if (l >= 0) lw.word.startMs - words[l].word.endMs else Long.MAX_VALUE
            val dr = if (r < words.size) words[r].word.startMs - lw.word.endMs else Long.MAX_VALUE
            lw.copy(speakerIdx = if (dl <= dr) words[l].speakerIdx else words[r].speakerIdx)
        }
    }

    /**
     * Одиночное слово чужого спикера между двумя словами одного спикера переходит к соседям,
     * кроме коротких ответов («да», «угу»…), у которых есть собственный турн ≥ backchannelMinTurnMs.
     */
    private fun smooth(words: List<LabeledWord>, turns: List<SpeakerTurn>, cfg: PipelineConfig): List<LabeledWord> {
        if (words.size < 3) return words
        val out = words.toMutableList()
        for (i in 1 until words.size - 1) {
            val prev = out[i - 1].speakerIdx
            val next = words[i + 1].speakerIdx
            val cur = words[i]
            if (prev != next || cur.speakerIdx == prev) continue
            if (isProtectedBackchannel(cur, turns, cfg)) continue
            out[i] = cur.copy(speakerIdx = prev)
        }
        return out
    }

    /**
     * Слово защищено от сглаживания, если у его спикера есть собственный турн, перекрывающий слово:
     * для коротких ответов достаточно backchannelMinTurnMs, для прочих — ownTurnProtectMs.
     */
    private fun isProtectedBackchannel(lw: LabeledWord, turns: List<SpeakerTurn>, cfg: PipelineConfig): Boolean {
        val norm = WordGuard.normalizeWord(lw.word.text)
        val minTurn = if (norm in cfg.backchannelWords) cfg.backchannelMinTurnMs else cfg.ownTurnProtectMs
        return turns.any {
            it.speakerIdx == lw.speakerIdx &&
                (it.endMs - it.startMs) >= minTurn &&
                overlap(it.startMs, it.endMs, lw.word.startMs, lw.word.endMs) > 0
        }
    }
}
