package ru.neurosteno.core

/** Метрики для bench (дублируют python-скрипты из tools, чтобы прогон на телефоне тоже считал WER). */
object Metrics {

    /** WER по нормализованным словам (та же нормализация, что у WordGuard). */
    fun wer(reference: String, hypothesis: String): Double {
        val r = WordGuard.words(reference)
        val h = WordGuard.words(hypothesis)
        if (r.isEmpty()) return if (h.isEmpty()) 0.0 else 1.0
        return editDistance(r, h).toDouble() / r.size
    }

    fun <T> editDistance(a: List<T>, b: List<T>): Int {
        var prev = IntArray(b.size + 1) { it }
        var cur = IntArray(b.size + 1)
        for (i in 1..a.size) {
            cur[0] = i
            for (j in 1..b.size) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.size]
    }

    fun rtf(processingMs: Long, audioMs: Long): Double = if (audioMs <= 0) 0.0 else processingMs.toDouble() / audioMs
}
