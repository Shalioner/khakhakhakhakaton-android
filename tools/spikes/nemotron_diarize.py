"""Спайк: диаризация NVIDIA Nemotron-3-Diarization (streaming Sortformer, до 8 спикеров) через onnxruntime.

    python -I tools/spikes/nemotron_diarize.py --models .cache/models/nemotron/nealcaren \
        --out out/nemotron/dev testdata/dev/*.wav [--threads 8] [--threshold 0.5] \
        [--min-on 0.2] [--min-off 0.5] [--cp-in out/cp_dump_dev --cp-out .cache/nemotron/cp_dev]
    python -I tools/spikes/nemotron_diarize.py --models ... --bench-sec 1800 testdata/test/*.wav

Модель — ONNX из HF NealCaren/Nemotron-3-Diarization-ONNX (неофициальный экспорт из transformers):
  embed.onnx      log-mel [1, N, 128] -> эмбеддинги [1, ceil(N/8), 512] (stacking x8 + проекция)
  step_int8.onnx  [спикер-кэш, FIFO, чанк, правый контекст] [1, T, 512] -> логиты [1, 8T, 8] (10 мс)
  mel_filters.bin float32 [128, 257] Slaney mel (librosa, n_fft 512, 16 кГц)
  silence_embeds.bin float32 [512]
Фронтенд, геометрия офлайн-чанков и Arrival-Order Speaker Cache — ровно как в эталоне sherpa-onnx PR #4032
(scripts/nemo/nemotron-3-diarization/test_onnx.py, sherpa-onnx/csrc/offline-speaker-diarization-sortformer-impl.h;
Apache-2.0), который сверен с transformers Nemotron3DiarizationForAudioFrameClassification (max diff 1.3e-5):
  * преэмфаза 0.97 по всему сигналу; STFT center=True (нулевой паддинг n_fft/2), n_fft 512, симметричное окно
    Ханна 400 по центру 512, hop 160; |X|^2 @ mel^T; log(x + 2^-24); без нормализации; num_frames = len // 160;
  * офлайн: чанк 340 энкодер-кадров (27.2 с), правый контекст 40, FIFO 40, период обновления кэша 300,
    кэш 264 кадра, 1 «тихий» кадр на спикера, prediction_score_threshold 0.25, boost 0.05, min_pos 0.5,
    strong 0.75, weak 1.5 (config.json модели);
  * num_embeds = num_frames // 8 + 1 (лишний кадр центрированного STFT, обнулённый, участвует в выходной свёртке).
Отличие от PR: в step.onnx NealCaren нет attention_mask/num_frames, поэтому в последнем чанке при
num_frames % 8 == 0 нулевой хвостовой эмбеддинг не маскируется во внимании (влияет на последние ~80 мс файла).
Постобработка как в sherpa-onnx PR: порог активности 0.5, склейка пауз < min_off, отброс турнов <= min_on,
спикеры нумеруются по первому появлению. Перекрытия сохраняются (несколько спикеров в одном кадре).

Вход: wav любой частоты/каналов -> та же нормализация, что в bench (WavReader.read16kMono): DC-фильтр,
ресемплинг до 16 кГц, усиление к RMS 0.08 по 40 % самых громких кадров (не больше 20x, пик <= 0.97).
Выход в --out: <name>.json (формат контракта, text = "·", пригоден для tools/der.py/report.py),
<name>.rttm, timings.json {"name": {"proc_ms", "audio_ms", "diar_ms"}} и mem.json (пиковая память процесса).
--cp-in/--cp-out: взять дамп bench (--cp-dump, строки D/V/W) и заменить турны (T) на турны Nemotron,
чтобы прогнать тот же core (TranscriptBuilder) через `bench cluster-sweep <cp-out> <dir> base=off`.
Скачанные модели — недоверенные данные: запускать с python -I, скрипт лежит вне каталога моделей.
"""
import argparse
import json
import math
import os
import sys
import time
import wave
from pathlib import Path

import numpy as np
import onnxruntime as ort
from scipy.signal import lfilter, resample_poly

# Параметры фронтенда/кэша из config.json и processor_config.json модели (ревизия f667ed7)
SR, N_FFT, WIN, HOP, N_MELS, PREEMPH = 16000, 512, 400, 160, 128, 0.97
SUB, N_SPK, HID = 8, 8, 512
CHUNK, RC, FIFO, UPD = 340, 40, 40, 300
SPKCACHE, SIL_FRAMES = 264, 1
PRED_THR, LATEST_BOOST, MIN_POS_RATE, STRONG_RATE, WEAK_RATE = 0.25, 0.05, 0.5, 0.75, 1.5


def read_wav_norm(path):
    """Аналог bench WavReader.read16kMono: моно, 16 кГц, DC-фильтр, нормализация громкости."""
    with wave.open(str(path), "rb") as w:
        ch, sw, rate, n = w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
        raw = w.readframes(n)
    if sw == 2:
        x = np.frombuffer(raw, "<i2").astype(np.float32) / 32768.0
    elif sw == 4:
        x = np.frombuffer(raw, "<i4").astype(np.float32) / 2147483648.0
    elif sw == 3:
        b = np.frombuffer(raw, np.uint8).reshape(-1, 3).astype(np.int32)
        v = b[:, 0] | (b[:, 1] << 8) | (b[:, 2] << 16)
        x = (np.where(v >= 1 << 23, v - (1 << 24), v) / 8388608.0).astype(np.float32)
    elif sw == 1:
        x = (np.frombuffer(raw, np.uint8).astype(np.float32) - 128) / 128.0
    else:
        raise ValueError(f"{path}: unsupported sample width {sw}")
    x = x.reshape(-1, ch).mean(axis=1)
    if rate != SR:
        g = math.gcd(rate, SR)
        x = resample_poly(x, SR // g, rate // g).astype(np.float32)
    x = lfilter([1.0, -1.0], [1.0, -0.996], x).astype(np.float32)  # DcBlocker(r = 0.996)
    fr = 480
    nfr = len(x) // fr
    if nfr and np.abs(x).max() > 0:
        rms = np.sqrt((x[: nfr * fr].reshape(nfr, fr).astype(np.float64) ** 2).mean(1)).astype(np.float32)
        p60 = np.sort(rms)[min(int(nfr * 0.6), nfr - 1)]
        sp = rms[(rms >= p60) & (rms > 1e-4)]
        if len(sp):
            gain = 0.08 / math.sqrt(float((sp.astype(np.float64) ** 2).mean()))
            gain = max(min(gain, 0.97 / float(np.abs(x).max()), 20.0), 0.05)
            x = np.clip(x * gain, -1.0, 1.0).astype(np.float32)
    return x


class Frontend:
    """NeMo log-mel без нормализации (как compute_features() в PR #4032), считается блоками по кадрам."""

    def __init__(self, mel_path):
        self.mel = np.fromfile(mel_path, "<f4").reshape(N_MELS, N_FFT // 2 + 1)
        w = np.hanning(WIN).astype(np.float32)  # симметричное, как torch.hann_window(periodic=False)
        left = (N_FFT - WIN) // 2
        self.window = np.pad(w, (left, N_FFT - WIN - left))

    def prepare(self, samples):
        self.s = samples  # без копий всего сигнала: преэмфаза и паддинг считаются по кускам в frames()
        self.num_frames = len(samples) // HOP

    def _preemph_padded(self, a, b):
        """Отсчёты [a, b) сигнала после преэмфазы и нулевого паддинга n_fft/2 с обеих сторон."""
        s, n, pad = self.s, len(self.s), N_FFT // 2
        out = np.zeros(b - a, np.float32)
        lo, hi = max(a - pad, 0), min(b - pad, n)
        if hi > lo:
            prev = s[lo - 1: hi - 1] if lo > 0 else np.concatenate([[0.0], s[: hi - 1]])
            y = (s[lo:hi] - PREEMPH * prev).astype(np.float32)
            if lo == 0:
                y[0] = s[0]
            out[lo + pad - a: hi + pad - a] = y
        return out

    def frames(self, start, count):
        """log-mel кадров [start, start+count); кадры >= num_frames нулевые (как паддинг эталона)."""
        out = np.zeros((count, N_MELS), np.float32)
        nv = min(count, self.num_frames - start)
        if nv <= 0:
            return out
        seg = self._preemph_padded(start * HOP, start * HOP + (nv - 1) * HOP + N_FFT)
        fr = np.lib.stride_tricks.sliding_window_view(seg, N_FFT)[::HOP][:nv]
        power = np.abs(np.fft.rfft(fr * self.window, axis=-1)) ** 2
        out[:nv] = np.log(power.astype(np.float32) @ self.mel.T + 2.0 ** -24)
        return out


class SpeakerCache:
    """Arrival-Order Speaker Cache + FIFO; перенос numpy-реализации из PR #4032 (test_onnx.py)."""

    def __init__(self, silence):
        budget = SPKCACHE // N_SPK - SIL_FRAMES
        self.min_pos = math.floor(budget * MIN_POS_RATE)
        self.n_strong = math.floor(budget * STRONG_RATE)
        self.n_weak = math.floor(budget * WEAK_RATE)
        self.silence = silence
        self.cache_embeds = np.zeros((0, HID), np.float32)
        self.cache_probs = np.zeros((0, N_SPK), np.float32)
        self.fifo = np.zeros((0, HID), np.float32)
        self.is_compressed = False

    def get_embeds(self):
        return np.concatenate([self.cache_embeds, self.fifo], axis=0)

    def update(self, chunk_embeds, probs, n_chunk):
        n_cache = self.cache_embeds.shape[0]
        probs = probs.reshape(-1, SUB, N_SPK).mean(axis=1)
        fifo = np.concatenate([self.fifo, chunk_embeds[:n_chunk]], axis=0)
        n = fifo.shape[0]
        if n <= FIFO:
            self.fifo = fifo
            return
        popped = min(max(UPD, n - FIFO), n)
        fifo_probs = probs[n_cache: n_cache + n]
        stored = self.cache_probs if self.is_compressed else probs[:n_cache]
        ce = np.concatenate([self.cache_embeds, fifo[:popped]], axis=0)
        cp = np.concatenate([stored, fifo_probs[:popped]], axis=0)
        self.fifo = fifo[popped:]
        if ce.shape[0] > SPKCACHE:
            ce, cp = self.compress(ce, cp)
            self.is_compressed = True
        self.cache_embeds, self.cache_probs = ce, cp

    def scores(self, probs):
        with np.errstate(divide="ignore"):
            lp = np.log(np.maximum(probs, PRED_THR))
            lc = np.log(np.maximum(1.0 - probs, PRED_THR))
        s = lp - lc + lc.sum(axis=-1, keepdims=True) - math.log(0.5)
        speech = probs > 0.5
        s[~speech] = -np.inf
        pos = s > 0
        enough = pos.sum(axis=0, keepdims=True) >= self.min_pos
        s[~pos & speech & enough] = -np.inf
        return s

    @staticmethod
    def topk(col, k):
        return np.argsort(-col, kind="stable")[:k]

    def compress(self, embeds, probs):
        nf, ns = probs.shape
        s = self.scores(probs)
        s[SPKCACHE:] += LATEST_BOOST
        for nb, boost in ((self.n_strong, -2.0 * math.log(0.5)), (self.n_weak, -math.log(0.5))):
            for k in range(ns):
                s[self.topk(s[:, k], nb), k] += boost
        n_scored = nf + SIL_FRAMES
        s = np.concatenate([s, np.full((SIL_FRAMES, ns), np.inf)], axis=0)
        flat = s.T.reshape(-1)
        picked = self.topk(flat, SPKCACHE)
        sentinel = n_scored * ns
        picked = np.sort(np.where(flat[picked] == -np.inf, sentinel, picked))
        frames = np.where(picked == sentinel, nf, np.minimum(picked % n_scored, nf))
        embeds = np.concatenate([embeds, self.silence[None]], axis=0)
        probs = np.concatenate([probs, np.zeros((1, ns), probs.dtype)])
        return embeds[frames], probs[frames]


class Nemotron:
    def __init__(self, model_dir, threads, step_name="step_int8.onnx"):
        d = Path(model_dir)
        so = ort.SessionOptions()
        so.intra_op_num_threads = threads
        so.inter_op_num_threads = 1
        prov = ["CPUExecutionProvider"]
        self.embed = ort.InferenceSession(str(d / "embed.onnx"), so, providers=prov)
        self.step = ort.InferenceSession(str(d / step_name), so, providers=prov)
        self.fe = Frontend(d / "mel_filters.bin")
        self.silence = np.fromfile(d / "silence_embeds.bin", "<f4")
        assert self.silence.shape == (HID,)

    def probs(self, samples):
        """Вероятности активности (num_frames, 8), кадр 10 мс."""
        fe = self.fe
        fe.prepare(samples)
        nf = fe.num_frames
        if nf == 0:
            return np.zeros((0, N_SPK), np.float32)
        n_emb = nf // SUB + 1
        cache = SpeakerCache(self.silence)
        out = []
        for start in range(0, n_emb, CHUNK):
            end = min(start + CHUNK, n_emb)
            stop = min(end + RC, n_emb)
            n_chunk = end - start
            feats = fe.frames(start * SUB, (stop - start) * SUB)
            chunk_embeds = self.embed.run(None, {"features": feats[None]})[0][0]
            cached = cache.get_embeds()
            x = np.concatenate([cached, chunk_embeds], axis=0)[None].astype(np.float32)
            logits = self.step.run(None, {"embeds": x})[0][0]
            p = 1.0 / (1.0 + np.exp(-logits))
            cache.update(chunk_embeds, p, n_chunk)
            c = cached.shape[0]
            out.append(p[c * SUB: (c + n_chunk) * SUB])
        return np.concatenate(out, axis=0)[:nf]


def to_turns(probs, threshold=0.5, min_on=0.2, min_off=0.5):
    """[(spk_idx, start_s, end_s)] как ComputeResult() в PR: порог, склейка пауз < min_off, отброс <= min_on."""
    fs = HOP / SR
    act = probs > threshold
    turns, found = [], 0
    for s in range(probs.shape[1]):
        a = np.concatenate([[False], act[:, s], [False]]).astype(np.int8)
        d = np.diff(a)
        segs = [[b * fs, e * fs] for b, e in zip(np.nonzero(d == 1)[0], np.nonzero(d == -1)[0])]
        merged = []
        for seg in segs:
            if merged and seg[0] - merged[-1][1] <= min_off:
                merged[-1][1] = seg[1]
            else:
                merged.append(seg)
        kept = [(found, b, e) for b, e in merged if e - b > min_on]
        if kept:
            turns += kept
            found += 1
    turns.sort(key=lambda t: (t[1], t[0]))
    # нумерация по первому появлению (PR нумерует по каналу, каналы и так в порядке прихода)
    order = {}
    for t in turns:
        order.setdefault(t[0], len(order))
    return [(order[k], b, e) for k, b, e in turns]


def peak_mem_mb():
    try:
        import psutil
        mi = psutil.Process().memory_info()
        return round(getattr(mi, "peak_wset", getattr(mi, "rss", 0)) / 2 ** 20, 1), round(mi.rss / 2 ** 20, 1)
    except Exception:
        return None, None


class RssSampler:
    """Максимальный RSS процесса во время блока (опрос каждые 10 мс) — память самой диаризации."""

    def __enter__(self):
        import threading
        import psutil
        self.p, self.stop, self.max = psutil.Process(), threading.Event(), 0
        self.before = self.p.memory_info().rss

        def run():
            while not self.stop.is_set():
                self.max = max(self.max, self.p.memory_info().rss)
                self.stop.wait(0.01)
        self.t = threading.Thread(target=run, daemon=True)
        self.t.start()
        return self

    def __exit__(self, *exc):
        self.stop.set()
        self.t.join()
        self.max = max(self.max, self.p.memory_info().rss)
        self.before_mb, self.max_mb = round(self.before / 2 ** 20, 1), round(self.max / 2 ** 20, 1)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("wavs", nargs="+")
    ap.add_argument("--models", required=True)
    ap.add_argument("--step", default="step_int8.onnx")
    ap.add_argument("--out")
    ap.add_argument("--threads", type=int, default=8)
    ap.add_argument("--threshold", type=float, default=0.5)
    ap.add_argument("--min-on", type=float, default=0.2)
    ap.add_argument("--min-off", type=float, default=0.5)
    ap.add_argument("--cp-in")
    ap.add_argument("--cp-out")
    ap.add_argument("--save-probs", action="store_true")
    ap.add_argument("--bench-sec", type=float, default=0,
                    help="замер скорости/памяти: склеить входы до N секунд и прогнать один раз")
    a = ap.parse_args(argv)

    m0 = peak_mem_mb()
    t0 = time.perf_counter()
    nem = Nemotron(a.models, a.threads, a.step)
    load_ms = (time.perf_counter() - t0) * 1000
    m1 = peak_mem_mb()
    print(f"models loaded in {load_ms:.0f} ms, mem peak/rss {m1} MB (before {m0})", flush=True)

    if a.bench_sec > 0:
        parts, tot = [], 0
        while tot < a.bench_sec * SR:
            for w in a.wavs:
                x = read_wav_norm(w)
                parts.append(x)
                tot += len(x)
                if tot >= a.bench_sec * SR:
                    break
        x = np.concatenate(parts)[: int(a.bench_sec * SR)]
        with RssSampler() as rs:
            t = time.perf_counter()
            p = nem.probs(x)
            dt = time.perf_counter() - t
        turns = to_turns(p, a.threshold, a.min_on, a.min_off)
        print(json.dumps({"audio_s": len(x) / SR, "diar_s": round(dt, 2), "rtf": round(dt / (len(x) / SR), 4),
                          "threads": a.threads, "spk": len({t[0] for t in turns}),
                          "rss_before_diar_mb": rs.before_mb, "rss_max_during_diar_mb": rs.max_mb,
                          "process_peak_mb": peak_mem_mb()[0], "after_load_mb": m1[0], "before_load_mb": m0[0]}))
        return 0

    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    timings, mem = {}, {"after_load_mb": m1, "load_ms": round(load_ms), "threads": a.threads}
    for w in a.wavs:
        w = Path(w)
        name = w.stem
        x = read_wav_norm(w)
        dur_ms = len(x) * 1000 // SR
        with RssSampler() as rs:
            t = time.perf_counter()
            p = nem.probs(x)
            dt = time.perf_counter() - t
        mem.setdefault("diar_rss_delta_max_mb", 0)
        mem["diar_rss_delta_max_mb"] = max(mem["diar_rss_delta_max_mb"], round(rs.max_mb - rs.before_mb, 1))
        mem["diar_rss_max_mb"] = max(mem.get("diar_rss_max_mb", 0), rs.max_mb)
        turns = to_turns(p, a.threshold, a.min_on, a.min_off)
        tr = []
        for k, b, e in turns:
            s, en = int(round(b * 1000)), min(int(round(e * 1000)), dur_ms)
            if en > s:
                tr.append({"speaker_id": f"speaker_{k + 1}", "start_ms": s, "end_ms": en, "text": "·"})
        (out / f"{name}.json").write_text(json.dumps({"transcript": tr}, ensure_ascii=False, indent=1), encoding="utf-8")
        with open(out / f"{name}.rttm", "w", encoding="utf-8") as f:
            for k, b, e in turns:
                f.write(f"SPEAKER {name} 1 {b:.3f} {e - b:.3f} <NA> <NA> speaker_{k + 1} <NA> <NA>\n")
        if a.save_probs:
            np.save(out / f"{name}.probs.npy", p.astype(np.float16))
        timings[name] = {"proc_ms": round(dt * 1000), "audio_ms": dur_ms, "diar_ms": round(dt * 1000)}
        nspk = len({t[0] for t in turns})
        print(f"{name:32s} audio={dur_ms / 1000:7.1f}s diar={dt:6.2f}s rtf={dt * 1000 / dur_ms:.4f} "
              f"turns={len(turns)} spk={nspk} maxp_unused_ch={int((p.max(0) > a.threshold).sum())}", flush=True)
        if a.cp_in and a.cp_out:
            src = Path(a.cp_in) / f"{name}.cp.txt"
            if src.exists():
                Path(a.cp_out).mkdir(parents=True, exist_ok=True)
                lines = [ln for ln in src.read_text(encoding="utf-8").splitlines() if ln[:2] in ("D ", "V ", "W ")]
                tl = [f"T {k} {int(round(b * 1000))} {int(round(e * 1000))}" for k, b, e in turns]
                d = [ln for ln in lines if ln.startswith("D ")]
                rest = [ln for ln in lines if not ln.startswith("D ")]
                (Path(a.cp_out) / f"{name}.cp.txt").write_text("\n".join(d + tl + rest) + "\n", encoding="utf-8")
    mem["peak_mb"], mem["rss_end_mb"] = peak_mem_mb()
    (out / "timings.json").write_text(json.dumps(timings, indent=1), encoding="utf-8")
    (out / "mem.json").write_text(json.dumps(mem, indent=1), encoding="utf-8")
    print("mem", mem)
    return 0


if __name__ == "__main__":
    sys.exit(main())
