package ru.neurosteno.core

/**
 * Защита от LLM: правка допустима, только если нормализованная последовательность слов не изменилась.
 * Нормализация: нижний регистр, ё→е, удаление всего, кроме букв и цифр (дефис внутри слова сохраняется).
 */
object WordGuard {

    fun normalizeWord(w: String): String {
        val lower = w.lowercase().replace('ё', 'е')
        val sb = StringBuilder()
        for ((i, ch) in lower.withIndex()) {
            if (ch.isLetterOrDigit()) sb.append(ch)
            else if (ch == '-' && i > 0 && i < lower.length - 1 && lower[i - 1].isLetterOrDigit() && lower[i + 1].isLetterOrDigit()) sb.append(ch)
        }
        return sb.toString()
    }

    fun words(text: String): List<String> =
        text.split(Regex("[\\s\\u00A0]+|(?<=\\p{L})[—–](?=\\p{L})")).map(::normalizeWord).filter { it.isNotEmpty() }

    fun sameWords(before: String, after: String): Boolean = words(before) == words(after)

    /** Возвращает правку LLM, если она прошла guard, иначе исходный текст. */
    fun accept(original: String, edited: String?): String {
        val e = edited?.trim()
        if (e.isNullOrEmpty()) return original
        return if (sameWords(original, e)) e else original
    }
}
