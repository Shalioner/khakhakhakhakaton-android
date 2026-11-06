package ru.neurosteno.core

/**
 * Слова одного спикера склеиваются в реплику; разрыв при смене спикера или паузе > pauseSplitMs.
 * Неречевые реплики (без слов или один неуверенный короткий токен) отбрасываются.
 */
object Segmenter {

    fun segment(words: List<LabeledWord>, cfg: PipelineConfig = PipelineConfig()): List<RawUtterance> {
        val sorted = words.filter { it.word.text.isNotBlank() }.sortedBy { it.word.startMs }
        val out = ArrayList<RawUtterance>()
        var cur = ArrayList<Word>()
        var curSpk = Alignment.UNKNOWN
        for (lw in sorted) {
            val split = cur.isNotEmpty() &&
                (lw.speakerIdx != curSpk || lw.word.startMs - cur.last().endMs > cfg.pauseSplitMs)
            if (split) {
                out += RawUtterance(curSpk, cur)
                cur = ArrayList()
            }
            cur += lw.word
            curSpk = lw.speakerIdx
        }
        if (cur.isNotEmpty()) out += RawUtterance(curSpk, cur)
        return out.filterNot { isNonSpeech(it, cfg) }
    }

    fun isNonSpeech(u: RawUtterance, cfg: PipelineConfig): Boolean {
        val real = u.words.filter { WordGuard.normalizeWord(it.text).isNotEmpty() }
        if (real.isEmpty()) return true
        if (real.size == 1) {
            val w = real[0]
            val lowConf = w.conf != null && w.conf < cfg.nonSpeechMinConf
            if (lowConf && w.durationMs < cfg.nonSpeechMaxSingleTokenMs) return true
        }
        return false
    }

    /**
     * Уточнение границ реплик по VAD: таймстемпы ASR дают начала токенов, а конец последнего слова
     * неизвестен. Конец реплики продлевается до конца VAD-сегмента, в котором начинается её последнее слово
     * (не дальше vadExtendEndMs), начало — до начала VAD-сегмента первого слова (не дальше vadExtendStartMs).
     * Соседние реплики не перекрываются: граница не заходит за соседа (при перебиваниях — не трогаем).
     */
    fun refineWithVad(utts: List<RawUtterance>, vad: List<SpeechSegment>, cfg: PipelineConfig): List<Pair<Long, Long>> {
        if (vad.isEmpty()) return utts.map { it.startMs to it.endMs }
        val segs = vad.sortedBy { it.startMs }
        fun segAt(t: Long): SpeechSegment? = segs.firstOrNull { it.startMs <= t && t < it.endMs }
        return utts.mapIndexed { i, u ->
            val prevEnd = if (i > 0) utts[i - 1].endMs else Long.MIN_VALUE
            val nextStart = if (i < utts.size - 1) utts[i + 1].startMs else Long.MAX_VALUE
            var s = u.startMs
            var e = u.endMs
            segAt(u.words.first().startMs + 1)?.let { seg ->
                if (seg.startMs < s && s - seg.startMs <= cfg.vadExtendStartMs) s = maxOf(seg.startMs, prevEnd)
            }
            segAt(u.words.last().startMs + 1)?.let { seg ->
                if (seg.endMs > e && seg.endMs - e <= cfg.vadExtendEndMs) e = minOf(seg.endMs, nextStart)
            }
            if (e <= s) u.startMs to u.endMs else s to e
        }
    }

    /**
     * Притягивает границы реплики к границам VAD-сегмента, если они ближе edgePadMs.
     * Таймстемпы токенов ASR часто ставят начало слова чуть позже реального начала звука.
     */
    fun snapToVad(start: Long, end: Long, vad: List<SpeechSegment>, cfg: PipelineConfig): Pair<Long, Long> {
        if (vad.isEmpty()) return start to end
        var s = start
        var e = end
        for (seg in vad) {
            if (seg.startMs <= start && start - seg.startMs <= cfg.edgePadMs) s = seg.startMs
            if (seg.endMs >= end && seg.endMs - end <= cfg.edgePadMs) e = seg.endMs
        }
        return s to e
    }
}
