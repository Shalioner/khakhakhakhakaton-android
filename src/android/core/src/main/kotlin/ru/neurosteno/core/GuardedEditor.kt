package ru.neurosteno.core

/** Локальная LLM: один промпт → одно продолжение. Реализация в engine-llm (llama.cpp). */
interface LlmBackend : AutoCloseable {
    /** Генерация до maxTokens; должна прерываться, когда shouldStop() == true. */
    fun complete(system: String, user: String, maxTokens: Int, shouldStop: () -> Boolean): String

    /** Многострочный ответ (протокол). По умолчанию — то же, что complete(). */
    fun completeMultiline(system: String, user: String, maxTokens: Int, shouldStop: () -> Boolean): String =
        complete(system, user, maxTokens, shouldStop)
}

/**
 * Правка пунктуации и регистра реплик через LLM.
 * Инварианты: число, порядок, спикеры и тайминги реплик не меняются; каждый текст проходит WordGuard;
 * при превышении бюджета оставшиеся реплики остаются с текстом ASR.
 */
class GuardedEditor(
    private val llm: LlmBackend,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Stats(var accepted: Int = 0, var rejected: Int = 0, var skipped: Int = 0, var alreadyClean: Int = 0, var elapsedMs: Long = 0)

    val system = "Ты корректор русского текста. Расставь знаки препинания и заглавные буквы. " +
        "Не добавляй, не удаляй, не заменяй и не переставляй слова. Ответь только исправленным текстом."

    fun edit(t: Transcript, budgetMs: Long, onProgress: (Float) -> Unit = {}, isCancelled: () -> Boolean = { false }): Pair<Transcript, Stats> {
        val st = Stats()
        val start = clock()
        val deadline = start + budgetMs
        val out = t.utterances.mapIndexed { i, u ->
            onProgress(i.toFloat() / t.utterances.size.coerceAtLeast(1))
            if (!needsEdit(u.text)) { st.alreadyClean++; return@mapIndexed u }
            if (isCancelled() || clock() >= deadline) { st.skipped++; return@mapIndexed u }
            val maxTok = (WordGuard.words(u.text).size * 3 + 16).coerceAtMost(512)
            val edited = runCatching {
                llm.complete(system, u.text, maxTok) { isCancelled() || clock() >= deadline }
            }.getOrNull()
            val cleaned = edited?.let(::cleanAnswer)
            val accepted = WordGuard.accept(u.text, cleaned)
            if (accepted != u.text || cleaned == u.text) st.accepted++ else st.rejected++
            u.copy(text = accepted)
        }
        st.elapsedMs = clock() - start
        return t.copy(utterances = out) to st
    }

    /**
     * LLM вызывается только там, где ASR не оформил текст: строчная первая буква
     * или нет завершающего знака. GigaAM v3 e2e обычно уже ставит пунктуацию — экономим RTF.
     */
    fun needsEdit(text: String): Boolean = Companion.needsEdit(text)

    companion object {
        private const val TERMINAL = ".?!…»\""

        fun needsEdit(text: String): Boolean {
            val t = text.trim()
            if (t.isEmpty()) return false
            val first = t.firstOrNull { it.isLetter() } ?: return false
            return first.isLowerCase() || t.last() !in TERMINAL
        }

        /** Нужна ли LLM вообще: если все реплики уже оформлены, модель не загружаем (экономия 2–8 с на телефоне). */
        fun anyNeedsEdit(t: Transcript): Boolean = t.utterances.any { needsEdit(it.text) }
    }

    /** Убираем типичный мусор ответа: кавычки вокруг, префиксы «Ответ:», лишние строки. */
    internal fun cleanAnswer(s: String): String {
        var x = s.trim()
        x = x.removePrefix("Ответ:").removePrefix("Исправленный текст:").trim()
        x = x.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
        if (x.length >= 2 && (x.first() == '"' && x.last() == '"' || x.first() == '«' && x.last() == '»')) x = x.substring(1, x.length - 1)
        return x
    }
}
