package ru.neurosteno.core

/**
 * Краткий протокол разговора. Показывается в UI, в JSON-экспорт не попадает.
 *
 * Маленькая локальная модель (0.5B) не умеет надёжно делать выжимку: пересказывает реплики дословно
 * и зацикливается (проверено на Pixel 6a). Поэтому факты протокола считаются из расшифровки
 * детерминированно (участники и доля их речи, длительность, ключевые слова), а LLM пишет одну строку —
 * тему разговора; если эта строка повторяет реплику, она отбрасывается.
 */
class Summarizer(private val llm: LlmBackend) {

    val system = "Ты секретарь. Назови тему разговора одним коротким предложением своими словами, " +
        "не цитируя реплики. Без markdown."

    fun prompt(t: Transcript, maxChars: Int = 3000): String {
        val sb = StringBuilder("Расшифровка:\n")
        for (u in t.utterances) {
            val line = "${u.speakerId.replace("speaker_", "Участник ")}: ${u.text}\n"
            if (sb.length + line.length > maxChars) { sb.append("…\n"); break }
            sb.append(line)
        }
        sb.append("\nТема разговора:")
        return sb.toString()
    }

    fun summarize(t: Transcript, maxTokens: Int = 40, shouldStop: () -> Boolean = { false }): String {
        if (t.utterances.isEmpty()) return ""
        val topic = runCatching { cleanTopic(llm.complete(system, prompt(t), maxTokens, shouldStop), t) }.getOrNull()
        return buildString {
            if (!topic.isNullOrBlank()) append("Тема: ").append(topic).append('\n')
            facts(t).forEach { append("— ").append(it).append('\n') }
        }.trimEnd()
    }

    /** Факты из расшифровки: без LLM, поэтому без выдумок. */
    fun facts(t: Transcript): List<String> {
        val bySpeaker = LinkedHashMap<String, Long>()
        t.utterances.forEach { bySpeaker.merge(it.speakerId, it.endMs - it.startMs, Long::plus) }
        val total = bySpeaker.values.sum().coerceAtLeast(1)
        val share = bySpeaker.entries.joinToString(", ") { (id, ms) ->
            "${id.replace("speaker_", "Говорящий ")} — ${(ms * 100 / total)} %"
        }
        val out = ArrayList<String>()
        out += "Участников: ${bySpeaker.size} ($share)"
        out += "Длительность ${fmt(t.durationMs)}, реплик: ${t.utterances.size}"
        val kw = keywords(t, 6)
        if (kw.isNotEmpty()) out += "Ключевые слова: " + kw.joinToString(", ")
        return out
    }

    /** Самые частые содержательные слова (длина ≥ 5, без стоп-слов). */
    fun keywords(t: Transcript, n: Int): List<String> {
        val freq = HashMap<String, Int>()
        t.utterances.forEach { u -> WordGuard.words(u.text).filter { it.length >= 5 && it !in STOP }.forEach { freq.merge(it, 1, Int::plus) } }
        return freq.entries.filter { it.value >= 2 }.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(n).map { it.key }
    }

    /** Первая непустая строка без разметки; отбрасывается, если совпадает с какой-либо репликой на ≥70 % слов. */
    fun cleanTopic(raw: String, t: Transcript): String? {
        val line = raw.lines().map { it.replace("**", "").trim().trimStart('#', '—', '-', ' ').removePrefix("Тема:").trim() }
            .firstOrNull { it.isNotEmpty() } ?: return null
        val w = WordGuard.words(line).toSet()
        if (w.size < 2) return null
        val echoes = t.utterances.any { u ->
            val uw = WordGuard.words(u.text).toSet()
            uw.isNotEmpty() && w.intersect(uw).size >= 0.7 * minOf(w.size, uw.size)
        }
        return if (echoes) null else line.take(160)
    }

    private fun fmt(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

    private companion object {
        val STOP = setOf(
            "который", "которые", "которая", "потому", "только", "сейчас", "можно", "нужно", "очень", "когда",
            "если", "этого", "этому", "этой", "этот", "чтобы", "тогда", "давайте", "просто", "вообще", "здесь",
            "будет", "будем", "может", "могу", "можем", "кажется", "ладно", "хорошо", "спасибо", "всего", "всегда",
            "после", "перед", "между", "через", "также", "тоже", "больше", "меньше", "почему", "какой", "какая",
        )
    }
}
