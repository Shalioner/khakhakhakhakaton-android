package ru.neurosteno.core

/**
 * Чистая постобработка: слова ASR + турны диаризации (+ VAD) → реплики контракта.
 * Без Android-зависимостей, тестируется на JVM.
 */
object TranscriptBuilder {

    fun build(
        words: List<Word>,
        turns: List<SpeakerTurn>,
        durationMs: Long,
        vad: List<SpeechSegment> = emptyList(),
        centroids: Map<Int, FloatArray> = emptyMap(),
        cfg: PipelineConfig = PipelineConfig(),
    ): Transcript {
        val clean = words
            .filter { WordGuard.normalizeWord(it.text).isNotEmpty() }
            .map { clampWord(it, durationMs) }
            .filter { it.endMs > it.startMs }
            .sortedBy { it.startMs }

        var effTurns = turns
        if (centroids.size > 1) {
            val m = SpeakerRelabel.mergeByCentroids(centroids, cfg.mergeClusterCosine)
            effTurns = turns.map { it.copy(speakerIdx = m[it.speakerIdx] ?: it.speakerIdx) }
        }

        val labeled = Alignment.assign(clean, effTurns, cfg)
        var utts = Segmenter.segment(labeled, cfg)
        utts = SpeakerRelabel.absorbTinySpeakers(utts, cfg.minSpeakerTotalMs)
        utts = SpeakerRelabel.mergeAdjacentSameSpeaker(utts, cfg.pauseSplitMs)
        val names = SpeakerRelabel.relabel(utts)

        val bounds = Segmenter.refineWithVad(utts, vad, cfg)
        val out = utts.mapIndexed { i, u ->
            val (s, e) = bounds[i]
            Utterance(names.getValue(u.speakerIdx), s.coerceIn(0, durationMs), e.coerceIn(0, durationMs), u.text.trim())
        }
        return Transcript(fixOverlapsAndOrder(out), durationMs)
    }

    private fun clampWord(w: Word, durationMs: Long): Word =
        w.copy(startMs = w.startMs.coerceIn(0, durationMs), endMs = w.endMs.coerceIn(0, durationMs))

    /** Гарантирует инварианты контракта: сортировка, start<end, непустой текст. */
    internal fun fixOverlapsAndOrder(utts: List<Utterance>): List<Utterance> =
        utts.filter { it.text.isNotBlank() }
            .map { if (it.endMs <= it.startMs) it.copy(endMs = it.startMs + 1) else it }
            .sortedWith(compareBy({ it.startMs }, { it.endMs }))
}
