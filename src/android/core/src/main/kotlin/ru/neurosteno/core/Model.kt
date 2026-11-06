package ru.neurosteno.core

/** Слово ASR в абсолютном времени исходного файла. conf — уверенность 0..1, если модель её даёт. */
data class Word(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val conf: Float? = null,
) {
    val durationMs: Long get() = endMs - startMs
}

/** Интервал речи одного спикера из диаризации (speakerIdx — сырой номер кластера). */
data class SpeakerTurn(
    val speakerIdx: Int,
    val startMs: Long,
    val endMs: Long,
)

/** Речевой интервал VAD. */
data class SpeechSegment(val startMs: Long, val endMs: Long)

/** Реплика в итоговом контракте. */
data class Utterance(
    val speakerId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

data class Transcript(val utterances: List<Utterance>, val durationMs: Long)

/** Слово с назначенным сырым индексом спикера. */
data class LabeledWord(val word: Word, val speakerIdx: Int)

/** Реплика до перенумерации: сырой индекс спикера + слова. */
data class RawUtterance(val speakerIdx: Int, val words: List<Word>) {
    val startMs: Long get() = words.first().startMs
    val endMs: Long get() = words.last().endMs
    val text: String get() = words.joinToString(" ") { it.text }
}
