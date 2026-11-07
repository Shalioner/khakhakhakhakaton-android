package ru.neurosteno.core.nemotron

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Признаки Nemotron-3-Diarization: NeMo log-mel без нормализации, ровно как `Frontend` в
 * tools/spikes/nemotron_diarize.py (тот повторяет compute_features() из sherpa-onnx PR #4032):
 * преэмфаза 0.97 по всему сигналу; STFT center=True с нулевым паддингом n_fft/2; n_fft 512; симметричное
 * окно Ханна 400 по центру 512; hop 160; |X|² @ Slaney-mel(128)ᵀ; log(x + 2⁻²⁴); num_frames = len / 160.
 * Кадры считаются блоками [start, start+count) — весь сигнал в RAM не нужен.
 *
 * Арифметика повторяет numpy-типы спайка: преэмфаза во float32 (а в блоке, начинающемся с отсчёта 0, — во
 * float64, потому что там numpy склеивает с python-списком [0.0]); окно — float32; FFT и |X|² — float64;
 * mel и log — float32. Расхождение с Python — только порядок суммирования в mel (≈1e-6 отн.).
 *
 * @param melFilters float32 [128, 257] (mel_filters.bin модели, row-major)
 */
class NemotronFrontend(melFilters: FloatArray) {
    private val mel = melFilters.copyOf()
    private val melLo = IntArray(N_MELS)
    private val melHi = IntArray(N_MELS)
    private val window = FloatArray(N_FFT)
    private val cosT = DoubleArray(N_FFT / 2)
    private val sinT = DoubleArray(N_FFT / 2)
    private val bitrev = IntArray(N_FFT)

    init {
        require(melFilters.size == N_MELS * N_BINS) { "mel filters: ${melFilters.size} != ${N_MELS * N_BINS}" }
        for (m in 0 until N_MELS) {
            var lo = N_BINS; var hi = 0
            for (b in 0 until N_BINS) if (mel[m * N_BINS + b] != 0f) { lo = min(lo, b); hi = max(hi, b + 1) }
            melLo[m] = lo; melHi[m] = max(hi, lo)
        }
        // np.hanning(400) (симметричное) → float32, по центру 512
        val left = (N_FFT - WIN) / 2
        for (n in 0 until WIN) window[left + n] = (0.5 - 0.5 * cos(2.0 * PI * n / (WIN - 1))).toFloat()
        for (i in 0 until N_FFT / 2) {
            cosT[i] = cos(-2.0 * PI * i / N_FFT); sinT[i] = sin(-2.0 * PI * i / N_FFT)
        }
        val bits = Integer.numberOfTrailingZeros(N_FFT)
        for (i in 0 until N_FFT) bitrev[i] = Integer.reverse(i) ushr (32 - bits)
    }

    fun numFrames(numSamples: Long): Int = (numSamples / HOP).toInt()

    /** log-mel кадров [start, start+count) как [count, 128] row-major; кадры ≥ num_frames — нули. */
    fun frames(src: SampleSource, start: Int, count: Int): FloatArray {
        val out = FloatArray(count * N_MELS)
        val nf = numFrames(src.numSamples)
        val nv = min(count, nf - start)
        if (nv <= 0) return out
        val a = start.toLong() * HOP
        val seg = preemphPadded(src, a, a + (nv - 1).toLong() * HOP + N_FFT)
        val re = DoubleArray(N_FFT)
        val im = DoubleArray(N_FFT)
        val power = FloatArray(N_BINS)
        for (f in 0 until nv) {
            val off = f * HOP
            for (i in 0 until N_FFT) { re[bitrev[i]] = (seg[off + i] * window[i]).toDouble(); im[bitrev[i]] = 0.0 }
            fft(re, im)
            for (b in 0 until N_BINS) power[b] = (re[b] * re[b] + im[b] * im[b]).toFloat()
            val o = f * N_MELS
            for (m in 0 until N_MELS) {
                var acc = 0.0
                val row = m * N_BINS
                for (b in melLo[m] until melHi[m]) acc += power[b].toDouble() * mel[row + b]
                out[o + m] = ln((acc.toFloat() + LOG_EPS).toDouble()).toFloat()
            }
        }
        return out
    }

    /** Отсчёты [a, b) сигнала после преэмфазы и нулевого паддинга n_fft/2 с обеих сторон (как _preemph_padded). */
    internal fun preemphPadded(src: SampleSource, a: Long, b: Long): FloatArray {
        val n = src.numSamples
        val pad = (N_FFT / 2).toLong()
        val out = FloatArray((b - a).toInt())
        val lo = max(a - pad, 0L)
        val hi = min(b - pad, n)
        if (hi <= lo) return out
        val dst = (lo + pad - a).toInt()
        val cnt = (hi - lo).toInt()
        if (lo > 0) {
            val raw = src.read(lo - 1, cnt + 1)
            for (i in 0 until cnt) out[dst + i] = raw[i + 1] - PREEMPH_F * raw[i]
        } else {
            val raw = src.read(0, cnt)
            out[dst] = raw[0]
            for (i in 1 until cnt) out[dst + i] = (raw[i].toDouble() - PREEMPH_D * raw[i - 1].toDouble()).toFloat()
        }
        return out
    }

    /** Итеративный radix-2 FFT на месте (вход уже в bit-reversed порядке). */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        var len = 2
        while (len <= N_FFT) {
            val half = len / 2
            val step = N_FFT / len
            var i = 0
            while (i < N_FFT) {
                for (j in 0 until half) {
                    val wr = cosT[j * step]; val wi = sinT[j * step]
                    val p = i + j; val q = p + half
                    val tr = re[q] * wr - im[q] * wi
                    val ti = re[q] * wi + im[q] * wr
                    re[q] = re[p] - tr; im[q] = im[p] - ti
                    re[p] += tr; im[p] += ti
                }
                i += len
            }
            len *= 2
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val N_FFT = 512
        const val WIN = 400
        const val HOP = 160
        const val N_MELS = 128
        const val N_BINS = N_FFT / 2 + 1
        private const val PREEMPH_F = 0.97f
        private const val PREEMPH_D = 0.97
        /** 2^-24 */
        private const val LOG_EPS = 5.9604645E-8f
    }
}
