package ru.neurosteno.core

/**
 * Склейка токенов ASR (BPE с «▁» или посимвольных с пробелом) в слова с таймингами.
 * Знаки препинания, пришедшие отдельными токенами, приклеиваются к предыдущему слову.
 * timestamps — начала токенов в секундах относительно чанка; offsetMs переводит в абсолютное время.
 */
object TokenWords {

    private const val WORD_START = '▁'
    /** Средняя длительность последнего токена слова, если конец больше неоткуда взять. */
    private const val LAST_TOKEN_MS = 120L
    /** Слово не тянется до следующего дальше этого запаса (пауза между словами). */
    private const val MAX_TAIL_MS = 400L

    fun toWords(tokens: Array<String>, timestamps: FloatArray, offsetMs: Long, chunkEndMs: Long): List<Word> {
        if (tokens.isEmpty() || timestamps.size != tokens.size) return emptyList()
        data class Acc(val sb: StringBuilder, val startMs: Long, var lastTokMs: Long)
        val accs = ArrayList<Acc>()
        var newWord = true
        for (i in tokens.indices) {
            val raw = tokens[i]
            val t = offsetMs + (timestamps[i] * 1000).toLong()
            if (raw.isBlank()) { newWord = true; continue }
            val startsWord = raw[0] == WORD_START || raw[0] == ' '
            val body = raw.trimStart(WORD_START, ' ')
            if (body.isEmpty()) { newWord = true; continue }
            val isPunct = body.all { !it.isLetterOrDigit() }
            if (isPunct && accs.isNotEmpty()) {
                accs.last().sb.append(body)
                newWord = startsWord
                continue
            }
            if (startsWord || newWord || accs.isEmpty()) {
                accs += Acc(StringBuilder(body), t, t)
            } else {
                accs.last().sb.append(body); accs.last().lastTokMs = t
            }
            newWord = false
        }
        return accs.mapIndexed { i, a ->
            val next = accs.getOrNull(i + 1)?.startMs ?: chunkEndMs
            val natural = a.lastTokMs + LAST_TOKEN_MS
            val end = minOf(maxOf(natural, a.startMs + 40), next, a.lastTokMs + MAX_TAIL_MS, chunkEndMs)
            Word(a.sb.toString(), a.startMs, maxOf(end, a.startMs + 1))
        }.filter { WordGuard.normalizeWord(it.text).isNotEmpty() }
    }
}

/** Нарезка речевых интервалов VAD на ASR-чанки ≤ maxChunkMs; резать только в паузах. */
object Chunker {

    data class Chunk(val startMs: Long, val endMs: Long)

    fun chunks(segments: List<SpeechSegment>, durationMs: Long, cfg: PipelineConfig = PipelineConfig()): List<Chunk> {
        val segs = segments.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
        val out = ArrayList<Chunk>()
        var curStart = -1L
        var curEnd = -1L
        for (s in segs) {
            // Сегмент длиннее лимита (VAD не нашёл паузу) — режем жёстко, другого выхода нет.
            val pieces = if (s.endMs - s.startMs > cfg.maxChunkMs) {
                val n = ((s.endMs - s.startMs + cfg.maxChunkMs - 1) / cfg.maxChunkMs).toInt()
                val step = (s.endMs - s.startMs) / n
                (0 until n).map { k -> SpeechSegment(s.startMs + k * step, if (k == n - 1) s.endMs else s.startMs + (k + 1) * step) }
            } else listOf(s)
            for (p in pieces) {
                if (curStart < 0) { curStart = p.startMs; curEnd = p.endMs; continue }
                val gap = p.startMs - curEnd
                if (p.endMs - curStart <= cfg.maxChunkMs && gap < cfg.pauseSplitMs * 2) {
                    curEnd = p.endMs
                } else {
                    out += Chunk(curStart, curEnd); curStart = p.startMs; curEnd = p.endMs
                }
            }
        }
        if (curStart >= 0) out += Chunk(curStart, curEnd)
        // Паддинг, не залезая на соседние чанки и за границы файла.
        return out.mapIndexed { i, c ->
            val lo = if (i > 0) (out[i - 1].endMs + c.startMs) / 2 else 0L
            val hi = if (i < out.size - 1) (c.endMs + out[i + 1].startMs) / 2 else durationMs
            Chunk(maxOf(lo, c.startMs - cfg.chunkPadMs), minOf(hi, c.endMs + cfg.chunkPadMs))
        }
    }
}
