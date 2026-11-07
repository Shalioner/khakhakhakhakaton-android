package ru.neurosteno.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Собственные значения и векторы вещественной симметричной матрицы без внешних библиотек:
 * приведение Хаусхолдером к трёхдиагональному виду (tred2) + неявный QL со сдвигами (tql2),
 * порт алгоритмов EISPACK в редакции JAMA. O(n³) один раз; n до ~500 — доли секунды на ПК.
 */
object SymEigen {

    /** values — по возрастанию; vectors[k] — k-й собственный вектор (длина n), null если не запрошены. */
    class Result(val values: DoubleArray, val vectors: Array<DoubleArray>?)

    /** [a] — симметричная n×n (не изменяется). vectors=false — только значения (быстрее). */
    fun decompose(a: Array<DoubleArray>, vectors: Boolean = true): Result {
        val n = a.size
        if (n == 0) return Result(DoubleArray(0), if (vectors) emptyArray() else null)
        val v = Array(n) { i -> a[i].copyOf().also { require(it.size == n) { "matrix must be square" } } }
        val d = DoubleArray(n)
        val e = DoubleArray(n)
        tred2(v, d, e, vectors)
        tql2(v, d, e, vectors)
        val order = (0 until n).sortedBy { d[it] }
        val vals = DoubleArray(n) { d[order[it]] }
        val vecs = if (vectors) Array(n) { k -> DoubleArray(n) { i -> v[i][order[k]] } } else null
        return Result(vals, vecs)
    }

    private fun tred2(v: Array<DoubleArray>, d: DoubleArray, e: DoubleArray, vectors: Boolean) {
        val n = d.size
        for (j in 0 until n) d[j] = v[n - 1][j]
        for (i in n - 1 downTo 1) {
            var scale = 0.0
            var h = 0.0
            for (k in 0 until i) scale += abs(d[k])
            if (scale == 0.0) {
                e[i] = d[i - 1]
                for (j in 0 until i) { d[j] = v[i - 1][j]; v[i][j] = 0.0; v[j][i] = 0.0 }
            } else {
                for (k in 0 until i) { d[k] /= scale; h += d[k] * d[k] }
                var f = d[i - 1]
                var g = sqrt(h)
                if (f > 0) g = -g
                e[i] = scale * g
                h -= f * g
                d[i - 1] = f - g
                for (j in 0 until i) e[j] = 0.0
                for (j in 0 until i) {
                    f = d[j]
                    v[j][i] = f
                    g = e[j] + v[j][j] * f
                    for (k in j + 1 until i) { g += v[k][j] * d[k]; e[k] += v[k][j] * f }
                    e[j] = g
                }
                f = 0.0
                for (j in 0 until i) { e[j] /= h; f += e[j] * d[j] }
                val hh = f / (h + h)
                for (j in 0 until i) e[j] -= hh * d[j]
                for (j in 0 until i) {
                    f = d[j]; g = e[j]
                    for (k in j until i) v[k][j] -= (f * e[k] + g * d[k])
                    d[j] = v[i - 1][j]
                    v[i][j] = 0.0
                }
            }
            d[i] = h
        }
        if (vectors) {
            for (i in 0 until n - 1) {
                v[n - 1][i] = v[i][i]
                v[i][i] = 1.0
                val h = d[i + 1]
                if (h != 0.0) {
                    for (k in 0..i) d[k] = v[k][i + 1] / h
                    for (j in 0..i) {
                        var g = 0.0
                        for (k in 0..i) g += v[k][i + 1] * v[k][j]
                        for (k in 0..i) v[k][j] -= g * d[k]
                    }
                }
                for (k in 0..i) v[k][i + 1] = 0.0
            }
            for (j in 0 until n) { d[j] = v[n - 1][j]; v[n - 1][j] = 0.0 }
            v[n - 1][n - 1] = 1.0
        } else {
            // d[j] после редукции — диагональ трёхдиагональной матрицы хранится в v[j][j]
            for (j in 0 until n) d[j] = v[j][j]
            // последняя строка: v[n-1][n-1] не трогалась редукцией после копирования в d
            d[n - 1] = v[n - 1][n - 1]
        }
        e[0] = 0.0
    }

    private fun tql2(v: Array<DoubleArray>, d: DoubleArray, e: DoubleArray, vectors: Boolean) {
        val n = d.size
        for (i in 1 until n) e[i - 1] = e[i]
        e[n - 1] = 0.0
        var f = 0.0
        var tst1 = 0.0
        val eps = Math.ulp(1.0)
        for (l in 0 until n) {
            tst1 = max(tst1, abs(d[l]) + abs(e[l]))
            var m = l
            while (m < n) { if (abs(e[m]) <= eps * tst1) break; m++ }
            if (m > l) {
                var iter = 0
                do {
                    if (++iter > 100) break
                    var g = d[l]
                    var p = (d[l + 1] - g) / (2.0 * e[l])
                    var r = hypot(p, 1.0)
                    if (p < 0) r = -r
                    d[l] = e[l] / (p + r)
                    d[l + 1] = e[l] * (p + r)
                    val dl1 = d[l + 1]
                    var h = g - d[l]
                    for (i in l + 2 until n) d[i] -= h
                    f += h
                    p = d[m]
                    var c = 1.0; var c2 = c; var c3 = c
                    val el1 = e[l + 1]
                    var s = 0.0; var s2 = 0.0
                    for (i in m - 1 downTo l) {
                        c3 = c2; c2 = c; s2 = s
                        g = c * e[i]
                        h = c * p
                        r = hypot(p, e[i])
                        e[i + 1] = s * r
                        s = e[i] / r
                        c = p / r
                        p = c * d[i] - s * g
                        d[i + 1] = h + s * (c * g + s * d[i])
                        if (vectors) for (k in 0 until n) {
                            h = v[k][i + 1]
                            v[k][i + 1] = s * v[k][i] + c * h
                            v[k][i] = c * v[k][i] - s * h
                        }
                    }
                    p = -s * s2 * c3 * el1 * e[l] / dl1
                    e[l] = s * p
                    d[l] = c * p
                } while (abs(e[l]) > eps * tst1)
            }
            d[l] += f
            e[l] = 0.0
        }
    }
}
