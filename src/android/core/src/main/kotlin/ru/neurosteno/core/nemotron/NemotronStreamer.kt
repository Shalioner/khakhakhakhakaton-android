package ru.neurosteno.core.nemotron

import ru.neurosteno.core.SpeakerTurn
import kotlin.math.exp
import kotlin.math.min

/** Постобработка вероятностей в турны (значения — как в спайке и bench: порог 0.5, min_on 0.2 с, min_off 0.5 с). */
data class NemotronConfig(
    val threshold: Float = 0.5f,
    val minDurationOnSec: Double = 0.2,
    val minDurationOffSec: Double = 0.5,
)

/**
 * Офлайн-инференс Nemotron-3-Diarization (streaming Sortformer) по чанкам — перенос `Nemotron.probs()` и
 * `to_turns()` из tools/spikes/nemotron_diarize.py:
 *  * признаки → `embed` ([1, N, 128] → [1, N/8, 512]);
 *  * чанк 340 эмбеддинг-кадров (27.2 с) + правый контекст 40 (3.2 с); вход `step` = [кэш спикеров, FIFO, чанк+контекст];
 *  * sigmoid(logits) [8T, 8] → кадры чанка в выход, кэш/FIFO обновляются (SpeakerCache, AOSC);
 *  * порог активности → турны, спикеры по первому появлению. Перекрытия сохраняются.
 * Память: окно признаков ≤ 3040 кадров + вход шага ≤ 684×512 + массив вероятностей (8 float на 10 мс).
 *
 * @param embed сессия embed.onnx (вход "features"), @param step сессия step_int8.onnx (вход "embeds");
 * берётся первый выход каждой сессии.
 */
class NemotronStreamer(
    private val embed: OnnxSession,
    private val step: OnnxSession,
    private val frontend: NemotronFrontend,
    private val silence: FloatArray,
    private val cfg: NemotronConfig = NemotronConfig(),
) {
    /** Для тестов: доступ к кэшу текущего прогона. */
    internal var cacheHook: ((SpeakerCache) -> Unit)? = null

    /** Вероятности активности [numFrames, 8] row-major, кадр 10 мс. */
    fun probs(src: SampleSource, onProgress: (Float) -> Unit = {}, isCancelled: () -> Boolean = { false }): FloatArray {
        val nf = frontend.numFrames(src.numSamples)
        if (nf == 0) return FloatArray(0)
        val nEmb = nf / SUB + 1
        val cache = SpeakerCache(silence).also { cacheHook?.invoke(it) }
        val out = FloatArray(nf * N_SPK)
        var written = 0
        var start = 0
        while (start < nEmb) {
            if (isCancelled()) throw InterruptedException("cancelled")
            val end = min(start + CHUNK, nEmb)
            val stop = min(end + RC, nEmb)
            val nChunk = end - start
            val nFeat = (stop - start) * SUB
            val feats = frontend.frames(src, start * SUB, nFeat)
            val e = embed.run(mapOf(EMBED_IN to FloatTensor(longArrayOf(1, nFeat.toLong(), N_MELS.toLong()), feats))).values.first()
            val chunkRows = e.data.size / HID
            check(e.data.size == chunkRows * HID && chunkRows >= nChunk) { "embed output ${e.shape.contentToString()}" }
            val c = cache.rows
            val x = cache.embeds() + e.data
            val t = c + chunkRows
            val logits = step.run(mapOf(STEP_IN to FloatTensor(longArrayOf(1, t.toLong(), HID.toLong()), x))).values.first()
            check(logits.data.size == t * SUB * N_SPK) { "step output ${logits.shape.contentToString()} for T=$t" }
            val p = FloatArray(logits.data.size)
            for (i in p.indices) {
                // 1 / (1 + exp(-x)) во float32, как numpy
                val ex = exp(-logits.data[i].toDouble()).toFloat()
                p[i] = 1f / (1f + ex)
            }
            cache.update(e.data, p, nChunk)
            val from = c * SUB * N_SPK
            val len = min(nChunk * SUB * N_SPK, out.size - written)
            if (len > 0) { p.copyInto(out, written, from, from + len); written += len }
            onProgress(end.toFloat() / nEmb)
            start += CHUNK
        }
        return out
    }

    /** Турны по всему файлу; durationMs обрезает конец последнего турна. */
    fun turns(src: SampleSource, onProgress: (Float) -> Unit = {}, isCancelled: () -> Boolean = { false }): List<SpeakerTurn> {
        val p = probs(src, onProgress, isCancelled)
        return toTurns(p, cfg, src.numSamples * 1000 / NemotronFrontend.SAMPLE_RATE)
    }

    companion object {
        const val CHUNK = 340
        const val RC = 40
        const val SUB = SpeakerCache.SUB
        const val N_SPK = SpeakerCache.N_SPK
        const val HID = SpeakerCache.HID
        const val N_MELS = NemotronFrontend.N_MELS
        const val EMBED_IN = "features"
        const val STEP_IN = "embeds"
        private const val FRAME_SEC = NemotronFrontend.HOP.toDouble() / NemotronFrontend.SAMPLE_RATE

        /**
         * Как to_turns() спайка (ComputeResult() PR): порог, склейка пауз ≤ min_off, отброс турнов ≤ min_on;
         * номер спикера — порядок первого появления среди каналов, у которых остался хоть один турн.
         */
        fun toTurns(probs: FloatArray, cfg: NemotronConfig = NemotronConfig(), durationMs: Long = Long.MAX_VALUE): List<SpeakerTurn> {
            val nf = probs.size / N_SPK
            data class T(val spk: Int, val b: Double, val e: Double)
            val turns = ArrayList<T>()
            var found = 0
            for (s in 0 until N_SPK) {
                val merged = ArrayList<DoubleArray>()
                var i = 0
                while (i < nf) {
                    if (probs[i * N_SPK + s] > cfg.threshold) {
                        var j = i
                        while (j < nf && probs[j * N_SPK + s] > cfg.threshold) j++
                        val b = i * FRAME_SEC; val e = j * FRAME_SEC
                        val last = merged.lastOrNull()
                        if (last != null && b - last[1] <= cfg.minDurationOffSec) last[1] = e else merged += doubleArrayOf(b, e)
                        i = j
                    } else i++
                }
                val kept = merged.filter { it[1] - it[0] > cfg.minDurationOnSec }
                if (kept.isNotEmpty()) {
                    kept.forEach { turns += T(found, it[0], it[1]) }
                    found++
                }
            }
            turns.sortWith(compareBy<T>({ it.b }, { it.spk }))
            val order = HashMap<Int, Int>()
            return turns.mapNotNull { t ->
                val k = order.getOrPut(t.spk) { order.size }
                val s = Math.round(t.b * 1000)
                val e = min(Math.round(t.e * 1000), durationMs)
                if (e > s) SpeakerTurn(k, s, e) else null
            }
        }
    }
}
