package ru.neurosteno.core

/**
 * Краткий протокол разговора локальной LLM. Показывается в UI, в JSON-экспорт не попадает.
 * Длинная расшифровка обрезается до maxChars, чтобы уложиться в контекст 2048 токенов маленькой модели.
 */
class Summarizer(private val llm: LlmBackend) {

    val system = "Ты секретарь. По расшифровке разговора составь краткий протокол на русском: " +
        "участники, обсуждённые вопросы, принятые решения. 3–7 пунктов, без выдумок."

    fun prompt(t: Transcript, maxChars: Int = 3000): String {
        val sb = StringBuilder()
        for (u in t.utterances) {
            val line = "${u.speakerId.replace("speaker_", "Участник ")}: ${u.text}\n"
            if (sb.length + line.length > maxChars) { sb.append("…\n"); break }
            sb.append(line)
        }
        return sb.toString()
    }

    fun summarize(t: Transcript, maxTokens: Int = 320, shouldStop: () -> Boolean = { false }): String =
        if (t.utterances.isEmpty()) "" else llm.complete(system, prompt(t), maxTokens, shouldStop).trim()
}
