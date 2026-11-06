package ru.neurosteno.core

/** Проверка инвариантов контракта перед экспортом. Пустой список = валидно. */
object ContractValidator {

    private val speakerRe = Regex("^speaker_[1-9][0-9]*$")

    fun validate(t: Transcript): List<String> {
        val errors = ArrayList<String>()
        var prevStart = -1L
        val firstSeen = LinkedHashMap<String, Int>()
        t.utterances.forEachIndexed { i, u ->
            if (!speakerRe.matches(u.speakerId)) errors += "#$i: bad speaker_id '${u.speakerId}'"
            if (u.startMs < 0) errors += "#$i: start_ms < 0"
            if (u.startMs >= u.endMs) errors += "#$i: start_ms >= end_ms"
            if (u.endMs > t.durationMs) errors += "#$i: end_ms > duration (${u.endMs} > ${t.durationMs})"
            if (u.text.isBlank()) errors += "#$i: empty text"
            if (u.startMs < prevStart) errors += "#$i: not sorted by start_ms"
            prevStart = u.startMs
            if (u.speakerId !in firstSeen) firstSeen[u.speakerId] = firstSeen.size + 1
        }
        firstSeen.forEach { (id, order) ->
            if (id != "speaker_$order") errors += "speaker numbering not by first appearance: $id is #$order"
        }
        return errors
    }
}
