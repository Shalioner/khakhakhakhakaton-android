package ru.neurosteno.core.nemotron

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Arrival-Order Speaker Cache + FIFO Nemotron (offline-конфиг модели): перенос `SpeakerCache` из
 * tools/spikes/nemotron_diarize.py (тот — перенос numpy-эталона sherpa-onnx PR #4032, test_onnx.py).
 * Матрицы — FloatArray row-major; эмбеддинги [rows, 512], вероятности [rows, 8].
 * Порядок float32-операций и устойчивые сортировки повторяют numpy, чтобы выбор кадров совпадал с Python.
 */
internal class SpeakerCache(private val silence: FloatArray) {
    private val minPos: Int
    private val nStrong: Int
    private val nWeak: Int

    var cacheEmbeds = FloatArray(0); private set
    var cacheProbs = FloatArray(0); private set
    var cacheRows = 0; private set
    var fifo = FloatArray(0); private set
    var fifoRows = 0; private set
    private var isCompressed = false

    /** Для тестов: индексы кадров, выбранных каждой компрессией (кадр n_in = «тихий» эмбеддинг). */
    var onCompress: ((nIn: Int, frames: IntArray, input: FloatArray) -> Unit)? = null

    init {
        require(silence.size == HID) { "silence embedding: ${silence.size} != $HID" }
        val budget = SPKCACHE / N_SPK - SIL_FRAMES
        minPos = floor(budget * MIN_POS_RATE).toInt()
        nStrong = floor(budget * STRONG_RATE).toInt()
        nWeak = floor(budget * WEAK_RATE).toInt()
    }

    val rows: Int get() = cacheRows + fifoRows

    /** [кэш, FIFO] — префикс входа шага. */
    fun embeds(): FloatArray = cacheEmbeds + fifo

    /**
     * @param chunkEmbeds эмбеддинги чанка с правым контекстом [*, 512]; в FIFO идут первые nChunk строк
     * @param probs сигмоиды шага [8 * rows_входа, 8] (кадры 10 мс)
     */
    fun update(chunkEmbeds: FloatArray, probs: FloatArray, nChunk: Int) {
        val nCache = cacheRows
        val stepRows = probs.size / (SUB * N_SPK)
        // probs.reshape(-1, 8, 8).mean(axis=1): последовательная сумма по 8 подкадрам, затем / 8
        val pm = FloatArray(stepRows * N_SPK)
        for (t in 0 until stepRows) for (k in 0 until N_SPK) {
            var s = probs[(t * SUB) * N_SPK + k]
            for (u in 1 until SUB) s += probs[(t * SUB + u) * N_SPK + k]
            pm[t * N_SPK + k] = s / SUB
        }
        val newFifo = fifo + chunkEmbeds.copyOfRange(0, nChunk * HID)
        val n = fifoRows + nChunk
        if (n <= FIFO) {
            fifo = newFifo; fifoRows = n
            return
        }
        val popped = min(max(UPD, n - FIFO), n)
        val stored = if (isCompressed) cacheProbs else pm.copyOfRange(0, nCache * N_SPK)
        var ce = cacheEmbeds + newFifo.copyOfRange(0, popped * HID)
        var cp = stored + pm.copyOfRange(nCache * N_SPK, (nCache + popped) * N_SPK)
        fifo = newFifo.copyOfRange(popped * HID, n * HID); fifoRows = n - popped
        var ceRows = nCache + popped
        if (ceRows > SPKCACHE) {
            val (e, p) = compress(ce, cp, ceRows)
            ce = e; cp = p; ceRows = SPKCACHE
            isCompressed = true
        }
        cacheEmbeds = ce; cacheProbs = cp; cacheRows = ceRows
    }

    /** Оценки кадров для компрессии ([nf, 8]); -inf — кадр не годится для спикера. */
    internal fun scores(probs: FloatArray, nf: Int): FloatArray {
        val s = FloatArray(nf * N_SPK)
        val lp = FloatArray(N_SPK)
        val lc = FloatArray(N_SPK)
        val posCount = IntArray(N_SPK)
        for (i in 0 until nf) {
            for (k in 0 until N_SPK) {
                val p = probs[i * N_SPK + k]
                lp[k] = ln(max(p, PRED_THR).toDouble()).toFloat()
                lc[k] = ln(max(1f - p, PRED_THR).toDouble()).toFloat()
            }
            // numpy pairwise для 8 элементов
            val sum = ((lc[0] + lc[1]) + (lc[2] + lc[3])) + ((lc[4] + lc[5]) + (lc[6] + lc[7]))
            for (k in 0 until N_SPK) {
                val v = lp[k] - lc[k] + sum - LOG_HALF
                val speech = probs[i * N_SPK + k] > 0.5f
                s[i * N_SPK + k] = if (speech) v else Float.NEGATIVE_INFINITY
                if (speech && v > 0f) posCount[k]++
            }
        }
        for (i in 0 until nf) for (k in 0 until N_SPK) {
            val v = s[i * N_SPK + k]
            // ~pos & speech & enough → -inf (речь, но оценка ≤ 0, а положительных кадров у спикера хватает)
            if (v != Float.NEGATIVE_INFINITY && !(v > 0f) && posCount[k] >= minPos) s[i * N_SPK + k] = Float.NEGATIVE_INFINITY
        }
        return s
    }

    private fun compress(embeds: FloatArray, probs: FloatArray, nf: Int): Pair<FloatArray, FloatArray> {
        val s = scores(probs, nf)
        for (i in SPKCACHE until nf) for (k in 0 until N_SPK) s[i * N_SPK + k] += LATEST_BOOST
        for ((nb, boost) in arrayOf(nStrong to STRONG_BOOST, nWeak to WEAK_BOOST)) {
            for (k in 0 until N_SPK) {
                val col = FloatArray(nf) { s[it * N_SPK + k] }
                for (i in topk(col, nb)) s[i * N_SPK + k] += boost
            }
        }
        val nScored = nf + SIL_FRAMES
        // s.T.reshape(-1) с добавленной строкой +inf («тихий» кадр): индекс = k * nScored + кадр
        val flat = FloatArray(nScored * N_SPK)
        for (k in 0 until N_SPK) {
            for (i in 0 until nf) flat[k * nScored + i] = s[i * N_SPK + k]
            for (i in nf until nScored) flat[k * nScored + i] = Float.POSITIVE_INFINITY
        }
        val sentinel = nScored * N_SPK
        val picked = topk(flat, SPKCACHE).map { if (flat[it] == Float.NEGATIVE_INFINITY) sentinel else it }.sorted()
        val frames = IntArray(picked.size) { if (picked[it] == sentinel) nf else min(picked[it] % nScored, nf) }
        onCompress?.invoke(nf, frames, embeds)
        val outE = FloatArray(frames.size * HID)
        val outP = FloatArray(frames.size * N_SPK)
        for ((j, f) in frames.withIndex()) {
            if (f == nf) {
                silence.copyInto(outE, j * HID)
                // вероятности тихого кадра — нули
            } else {
                embeds.copyInto(outE, j * HID, f * HID, (f + 1) * HID)
                probs.copyInto(outP, j * N_SPK, f * N_SPK, (f + 1) * N_SPK)
            }
        }
        return outE to outP
    }

    companion object {
        const val HID = 512
        const val N_SPK = 8
        const val SUB = 8
        const val FIFO = 40
        const val UPD = 300
        const val SPKCACHE = 264
        const val SIL_FRAMES = 1
        private const val PRED_THR = 0.25f
        private const val LATEST_BOOST = 0.05f
        private const val MIN_POS_RATE = 0.5
        private const val STRONG_RATE = 0.75
        private const val WEAK_RATE = 1.5
        private val LOG_HALF = ln(0.5).toFloat()
        private val STRONG_BOOST = (-2.0 * ln(0.5)).toFloat()
        private val WEAK_BOOST = (-ln(0.5)).toFloat()

        /** np.argsort(-v, kind="stable")[:k]: по убыванию, при равенстве — меньший индекс; -0.0 == 0.0 как в numpy. */
        internal fun topk(v: FloatArray, k: Int): IntArray {
            val idx = (0 until v.size).toMutableList()
            idx.sortWith { i, j ->
                val a = v[i]; val b = v[j]
                if (a > b) -1 else if (a < b) 1 else i.compareTo(j)
            }
            return IntArray(min(k, v.size)) { idx[it] }
        }
    }
}
