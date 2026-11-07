package ru.neurosteno.core

/**
 * Перенос регистра и пунктуации с текста e2e-модели (GigaAM v3 punct: знаки, заглавные, но числа цифрами)
 * на слова модели без пунктуации (числа словами, тайминги слов).
 *
 * Слова и их тайминги не меняются: меняется только написание (регистр + знаки вокруг слова),
 * и только так, что нормализованная форма слова остаётся прежней (проверяется WordGuard.normalizeWord).
 * Выравнивание — LCS по нормализованным словам. Невыровненные куски (числа «27» ↔ «двадцать семь»)
 * остаются как есть; знак после такого куска в e2e-тексте переносится на последнее слово куска,
 * а заглавная буква — на первое, если кусок начинает предложение.
 */
object PunctTransfer {

    fun transfer(words: List<Word>, punctText: String): List<Word> {
        if (words.isEmpty() || punctText.isBlank()) return words
        val src = punctText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val srcNorm = src.map(WordGuard::normalizeWord)
        val dstNorm = words.map { WordGuard.normalizeWord(it.text) }
        val pairs = lcs(srcNorm, dstNorm)

        val out = words.map { it.text }.toMutableList()
        // 1) совпавшие слова: написание из e2e
        for ((si, di) in pairs) out[di] = src[si]
        // 2) невыровненные куски между совпадениями
        val bounds = listOf(-1 to -1) + pairs + listOf(src.size to words.size)
        for (k in 0 until bounds.size - 1) {
            val (s0, d0) = bounds[k]
            val (s1, d1) = bounds[k + 1]
            if (d1 - d0 <= 1) continue // в hyp нет невыровненных слов
            val gapSrc = src.subList(s0 + 1, s1)
            val firstDst = d0 + 1
            val lastDst = d1 - 1
            val sentenceStart = d0 < 0 || out[d0].trimEnd().lastOrNull()?.let { it in ".?!…" } == true
            if (sentenceStart) out[firstDst] = capitalize(out[firstDst])
            val trailing = gapSrc.lastOrNull()?.takeLastWhile { !it.isLetterOrDigit() }.orEmpty()
            if (trailing.isNotEmpty() && out[lastDst].last().isLetterOrDigit()) out[lastDst] = out[lastDst] + trailing
        }
        // При перекрытии второй проход иногда слышит только конец фразы как
        // отдельное предложение. Его границы относятся к другому контексту.
        val start = pairs.firstOrNull()?.second
        if (start != null && start > 0 && pairs.size == src.size &&
            pairs.last().second - start + 1 == src.size &&
            !endsSentence(out[start - 1]) && endsSentence(src.last()) &&
            src.dropLast(1).none(::endsSentence)) {
            val end = pairs.last().second
            out[start] = words[start].text + src.first().takeLastWhile { !it.isLetterOrDigit() }
            out[end] = out[end].trimEnd('.', '?', '!', '…')
        }
        // Страховка: слово не должно поменяться после нормализации.
        return words.mapIndexed { i, w ->
            if (WordGuard.normalizeWord(out[i]) == dstNorm[i] && out[i].isNotBlank()) w.copy(text = out[i]) else w
        }
    }

    private fun endsSentence(text: String) = text.lastOrNull()?.let { it in ".?!…" } == true

    private fun capitalize(s: String): String {
        val i = s.indexOfFirst { it.isLetter() }
        return if (i < 0) s else s.substring(0, i) + s[i].uppercaseChar() + s.substring(i + 1)
    }

    /** Пары индексов (src, dst) наибольшей общей подпоследовательности, по возрастанию. */
    internal fun lcs(a: List<String>, b: List<String>): List<Pair<Int, Int>> {
        val n = a.size; val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i][j] = if (a[i] == b[j] && a[i].isNotEmpty()) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val res = ArrayList<Pair<Int, Int>>()
        var i = 0; var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] && a[i].isNotEmpty() -> { res += i to j; i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return res
    }
}
