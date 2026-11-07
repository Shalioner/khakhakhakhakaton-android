package ru.neurosteno.core.nemotron

import ru.neurosteno.core.SpeakerTurn
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Эталон — tools/spikes/nemotron_kotlin_ref.py (код спайка nemotron_diarize.py + те же фейковые сети).
 * Ресурсы: core/src/test/resources/nemotron/{mel_filters.bin, features.bin, probs_every4.bin, ref.json}.
 */
class NemotronTest {

    private fun res(name: String): ByteArray =
        javaClass.getResourceAsStream("/nemotron/$name")!!.use { it.readBytes() }

    private fun floats(name: String): FloatArray {
        val b = ByteBuffer.wrap(res(name)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()).also { b.get(it) }
    }

    private val ref: String by lazy { String(res("ref.json"), Charsets.UTF_8) }
    private fun refInt(key: String): Int = Regex("\"$key\": (-?\\d+)").find(ref)!!.groupValues[1].toInt()
    private fun refIntList(key: String): List<Int> =
        Regex("\"$key\": \\[([^\\]]*)]").find(ref)!!.groupValues[1].split(",").map { it.trim().toInt() }

    private val mel by lazy { floats("mel_filters.bin") }
    private val signal by lazy { TestSignal.lcg(refInt("n_samples"), refInt("seed").toLong()) }
    private val silence = FloatArray(512) { ((it % 7) - 3) * 0.01f }

    @Test
    fun featuresMatchPython() {
        val fe = NemotronFrontend(mel)
        val src = ArraySampleSource(signal)
        assertEquals(refInt("num_frames"), fe.numFrames(src.numSamples))
        val windows = refIntList("feature_windows")
        val n = refInt("frames_per_window")
        val expected = floats("features.bin")
        var maxDiff = 0.0
        var maxRel = 0.0
        for ((w, start) in windows.withIndex()) {
            val got = fe.frames(src, start, n)
            for (i in got.indices) {
                val e = expected[w * n * 128 + i]
                val d = abs(got[i] - e).toDouble()
                maxDiff = max(maxDiff, d)
                maxRel = max(maxRel, d / max(1.0, abs(e).toDouble()))
            }
        }
        // хвостовое окно частично за num_frames: кадры за концом — ровно нули, как в Python
        val tail = fe.frames(src, windows[2], n)
        for (i in 20 * 128 until n * 128) assertEquals(0f, tail[i])
        println("nemotron features: max |diff| = $maxDiff, max rel = $maxRel")
        assertTrue(maxDiff < 1e-3, "max |diff| = $maxDiff")
        assertTrue(maxRel < 1e-4, "max rel = $maxRel")
    }

    @Test
    fun frontendBlocksAreConsistent() {
        // кадры, посчитанные кусками (как в чанках), совпадают с кадрами одного большого блока; блок с отсчёта 0
        // считает преэмфазу во float64 (как numpy), прочие — во float32, отсюда ~1e-4 на тихих кадрах
        val fe = NemotronFrontend(mel)
        val src = ArraySampleSource(signal.copyOf(16000 * 3))
        val all = fe.frames(src, 0, 300)
        val part = fe.frames(src, 137, 50)
        for (i in part.indices) assertEquals(all[137 * 128 + i], part[i], 1e-3f)
    }

    @Test
    fun streamerMatchesPythonOnFakeSessions() {
        val picks = ArrayList<Pair<Int, IntArray>>()
        val stepT = ArrayList<Int>()
        val streamer = NemotronStreamer(FakeEmbed(), FakeStep(stepT), NemotronFrontend(mel), silence)
        streamer.cacheHook = { c ->
            c.onCompress = { nIn, frames, input -> picks += nIn to canonical(frames, input, nIn) }
        }
        val src = ArraySampleSource(signal)
        var lastProgress = 0f
        val p = streamer.probs(src, onProgress = { lastProgress = it })
        assertEquals(1f, lastProgress)
        val nf = refInt("num_frames")
        assertEquals(nf * 8, p.size)
        // геометрия чанков: [0+380], [264+40+380] x2, [264+40+231]
        assertEquals(listOf(380, 684, 684, 535), stepT)

        // компрессия кэша выбрала те же кадры, что и Python
        val refPicks = Regex("\\{\"n_in\": (\\d+), \"frames\": \\[([^\\]]*)]}").findAll(ref)
            .map { it.groupValues[1].toInt() to it.groupValues[2].split(",").map { s -> s.trim().toInt() } }.toList()
        assertEquals(refPicks.size, picks.size)
        for ((i, r) in refPicks.withIndex()) {
            assertEquals(r.first, picks[i].first, "compress #$i n_in")
            assertContentEquals(r.second.toIntArray(), picks[i].second, "compress #$i frames")
        }

        val expected = floats("probs_every4.bin")
        var maxDiff = 0f
        for (r in 0 until expected.size / 8) for (k in 0 until 8) {
            maxDiff = max(maxDiff, abs(p[(r * 4) * 8 + k] - expected[r * 8 + k]))
        }
        println("nemotron streamer: max |dprob| = $maxDiff")
        assertTrue(maxDiff < 1e-3, "max |dprob| = $maxDiff")

        val turns = NemotronStreamer.toTurns(p, durationMs = src.numSamples * 1000 / 16000)
        val refTurns = Regex("\\[(\\d+), (\\d+), (\\d+)]").findAll(ref.substringAfter("\"turns\": ").substringBefore("]],") + "]")
            .map { SpeakerTurn(it.groupValues[1].toInt(), it.groupValues[2].toLong(), it.groupValues[3].toLong()) }.toList()
        assertEquals(refTurns, turns)
    }

    /** Кадр → первый индекс идентичной строки во входе компрессии (как сопоставляет эталон). */
    private fun canonical(frames: IntArray, input: FloatArray, nIn: Int): IntArray {
        val ext = input + silence
        fun same(a: Int, b: Int): Boolean {
            for (j in 0 until 512) if (ext[a * 512 + j] != ext[b * 512 + j]) return false
            return true
        }
        return IntArray(frames.size) { i -> (0..nIn).first { same(it, frames[i]) } }
    }

    @Test
    fun toTurnsThresholdMergeAndOrder() {
        val nf = 300
        val p = FloatArray(nf * 8)
        fun on(spk: Int, from: Int, to: Int) { for (i in from until to) p[i * 8 + spk] = 0.9f }
        on(5, 10, 50)    // спикер канала 5 — появляется первым
        on(5, 80, 120)   // пауза 0.3 с ≤ 0.5 → склейка с предыдущим
        on(2, 100, 150)  // перекрытие с каналом 5 сохраняется
        on(2, 250, 268)  // 0.18 с ≤ min_on → отброс
        on(7, 200, 215)  // 0.15 с → канал без турнов не получает номера
        on(0, 160, 161); p[160 * 8 + 0] = 0.5f // ровно порог — не активен
        val t = NemotronStreamer.toTurns(p, durationMs = 1400)
        assertEquals(listOf(SpeakerTurn(0, 100, 1200), SpeakerTurn(1, 1000, 1400)), t)
    }

    @Test
    fun topkIsStableDescending() {
        val v = floatArrayOf(1f, 3f, 3f, Float.NEGATIVE_INFINITY, 0f, -0f, Float.POSITIVE_INFINITY)
        assertContentEquals(intArrayOf(6, 1, 2, 0, 4, 5), SpeakerCache.topk(v, 6))
    }

    @Test
    fun emptyAndShortInput() {
        val s = NemotronStreamer(FakeEmbed(), FakeStep(ArrayList()), NemotronFrontend(mel), silence)
        assertEquals(0, s.probs(ArraySampleSource(FloatArray(100))).size)
        assertEquals(emptyList(), s.turns(ArraySampleSource(FloatArray(0))))
        // 1 с: один чанк, FIFO не переполняется, компрессии нет
        assertEquals(100 * 8, s.probs(ArraySampleSource(signal.copyOf(16000))).size)
    }

    @Test
    fun cancellationThrows() {
        val s = NemotronStreamer(FakeEmbed(), FakeStep(ArrayList()), NemotronFrontend(mel), silence)
        assertFailsWith<InterruptedException> { s.probs(ArraySampleSource(signal), isCancelled = { true }) }
    }
}

/** Повтор lcg_signal() из nemotron_kotlin_ref.py: те же биты int16. */
internal object TestSignal {
    fun lcg(n: Int, seed: Long): FloatArray {
        var st = seed and 0xFFFFFFFFL
        fun nxt(): Long { st = (st * 1664525L + 1013904223L) and 0xFFFFFFFFL; return st }
        val out = FloatArray(n)
        var t = 0
        while (t < n) {
            val kind = ((nxt() shr 8) % 4).toInt()
            val ln = 8000 + ((nxt() shr 8) % 88000).toInt()
            for (r in 0 until ln) {
                if (t >= n) break
                val noise = ((nxt() shr 16) and 0xFFFF).toInt() - 32768
                val v = when (kind) {
                    0 -> noise shr 9
                    1 -> (if ((t / 20) % 2 == 1) 8000 else -8000) + (noise shr 6)
                    2 -> (if (t % 13 < 6) 6000 else -6000) + (noise shr 6)
                    else -> (t % 7) * 2000 - 6000 + (noise shr 6)
                }
                out[t] = v.toShort() / 32768f
                t++
            }
        }
        return out
    }
}

/** FakeEmbed из nemotron_kotlin_ref.py. */
internal class FakeEmbed : OnnxSession {
    override fun run(inputs: Map<String, FloatTensor>): Map<String, FloatTensor> {
        val f = inputs.getValue("features")
        val n = f.dim(1)
        val t = n / 8
        val e = FloatArray(t * 512)
        for (r in 0 until t) for (m in 0 until 128) {
            var s = 0f
            for (u in 0 until 8) s += f.data[(r * 8 + u) * 128 + m]
            val v = s / 8
            e[r * 512 + m] = v * 0.1f
            e[r * 512 + 128 + m] = v * 0.5f * 0.1f
            e[r * 512 + 256 + m] = -v * 0.1f
            e[r * 512 + 384 + m] = v * 0.25f * 0.1f
        }
        return linkedMapOf("embeds" to FloatTensor(longArrayOf(1, t.toLong(), 512), e))
    }

    override fun close() {}
}

/** FakeStep из nemotron_kotlin_ref.py; запоминает длину входа T. */
internal class FakeStep(private val calls: MutableList<Int>) : OnnxSession {
    override fun run(inputs: Map<String, FloatTensor>): Map<String, FloatTensor> {
        val x = inputs.getValue("embeds")
        val t = x.dim(1)
        calls += t
        val d = x.data
        val g = DoubleArray(512)
        for (r in 0 until t) for (j in 0 until 512) g[j] += d[r * 512 + j].toDouble()
        for (j in 0 until 512) g[j] /= t
        fun mean(a: (Int) -> Double, from: Int, to: Int): Double { var s = 0.0; for (j in from until to) s += a(j); return s / (to - from) }
        val gAvg = mean({ g[it] }, 0, 128)
        val out = FloatArray(t * 64)
        for (r in 0 until t) {
            val avg = mean({ d[r * 512 + it].toDouble() }, 0, 128)
            for (k in 0 until 8) {
                val band = mean({ d[r * 512 + it].toDouble() }, 16 * k, 16 * k + 16)
                val c = mean({ g[it] }, 16 * k, 16 * k + 16) - gAvg
                for (u in 0 until 8) {
                    out[(r * 8 + u) * 8 + k] = (8.0 * (band - avg - c)).toFloat() + (0.1 * (u - 3.5)).toFloat() - 0.5f
                }
            }
        }
        return linkedMapOf("logits" to FloatTensor(longArrayOf(1, t * 8L, 8), out))
    }

    override fun close() {}
}
