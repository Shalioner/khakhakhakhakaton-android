package ru.neurosteno.core

import kotlin.math.sqrt

object SpeakerRelabel {

    /** speaker_N по порядку первого появления (N с 1). */
    fun relabel(utts: List<RawUtterance>): Map<Int, String> {
        val map = LinkedHashMap<Int, String>()
        for (u in utts.sortedBy { it.startMs }) {
            if (u.speakerIdx !in map) map[u.speakerIdx] = "speaker_${map.size + 1}"
        }
        return map
    }

    /**
     * Слияние кластеров, чьи центроиды эмбеддингов близки (cos > порога).
     * Возвращает отображение старый индекс → новый индекс. Жадно, начиная с самой близкой пары.
     */
    fun mergeByCentroids(centroids: Map<Int, FloatArray>, threshold: Float): Map<Int, Int> {
        val parent = centroids.keys.associateWith { it }.toMutableMap()
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]!!; return r }
        val keys = centroids.keys.sorted()
        val pairs = ArrayList<Triple<Int, Int, Float>>()
        for (i in keys.indices) for (j in i + 1 until keys.size) {
            pairs += Triple(keys[i], keys[j], cosine(centroids[keys[i]]!!, centroids[keys[j]]!!))
        }
        for ((a, b, c) in pairs.sortedByDescending { it.third }) {
            if (c <= threshold) break
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
        }
        return keys.associateWith { find(it) }
    }

    /**
     * Спикеры с суммарной речью < minTotalMs растворяются: их реплики переходят к спикеру
     * ближайшей по времени соседней реплики. Если спикер всего один — не трогаем.
     */
    fun absorbTinySpeakers(utts: List<RawUtterance>, minTotalMs: Long): List<RawUtterance> {
        val total = HashMap<Int, Long>()
        utts.forEach { total.merge(it.speakerIdx, it.endMs - it.startMs, Long::plus) }
        if (total.size <= 1) return utts
        val tiny = total.filter { it.value < minTotalMs }.keys
        if (tiny.isEmpty() || tiny.size == total.size) return utts
        val sorted = utts.sortedBy { it.startMs }
        return sorted.mapIndexed { i, u ->
            if (u.speakerIdx !in tiny) return@mapIndexed u
            val prev = (i - 1 downTo 0).firstOrNull { sorted[it].speakerIdx !in tiny }
            val next = (i + 1 until sorted.size).firstOrNull { sorted[it].speakerIdx !in tiny }
            val dp = prev?.let { u.startMs - sorted[it].endMs } ?: Long.MAX_VALUE
            val dn = next?.let { sorted[it].startMs - u.endMs } ?: Long.MAX_VALUE
            val target = if (dp <= dn) sorted[prev!!].speakerIdx else sorted[next!!].speakerIdx
            u.copy(speakerIdx = target)
        }.let(::mergeAdjacentSameSpeaker)
    }

    /** Соседние реплики одного спикера без пауз-разрыва склеиваются (после перенумерации/слияний). */
    fun mergeAdjacentSameSpeaker(utts: List<RawUtterance>, maxGapMs: Long = Long.MAX_VALUE): List<RawUtterance> {
        val out = ArrayList<RawUtterance>()
        for (u in utts.sortedBy { it.startMs }) {
            val last = out.lastOrNull()
            if (last != null && last.speakerIdx == u.speakerIdx && u.startMs - last.endMs <= maxGapMs) {
                out[out.size - 1] = RawUtterance(last.speakerIdx, last.words + u.words)
            } else out += u
        }
        return out
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        if (na == 0.0 || nb == 0.0) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }
}
