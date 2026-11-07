"""Эталон для юнит-тестов core/nemotron (NemotronFrontend, NemotronStreamer) из кода спайка nemotron_diarize.py.

    python -I tools/spikes/nemotron_kotlin_ref.py --mel .cache/models/nemotron/nealcaren/mel_filters.bin \
        --out src/android/core/src/test/resources/nemotron

Нейросети заменены детерминированными фейками (FakeEmbed/FakeStep), которые повторены в Kotlin-тесте 1:1:
проверяется ровно наша логика — признаки, нарезка на чанки, FIFO/кэш спикеров (AOSC), постобработка в турны.
Сигнал — целочисленный LCG (одинаковые биты в Python и Kotlin, без sin/random).
Выход:
  mel_filters.bin    копия фильтров модели (OpenMDW-1.1, NVIDIA; см. NOTICE.txt рядом)
  features.bin       float32 log-mel трёх окон frames(start, 32) (см. ref.json "feature_windows")
  probs_every4.bin   float32 [nf/4, 8] вероятности фейкового стримера, каждый 4-й кадр 10 мс
  ref.json           параметры сигнала, num_frames, кадры, выбранные компрессией кэша, турны
"""
import argparse
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import nemotron_diarize as nd  # noqa: E402

SEED = 12345
N_SAMPLES = 100 * 16000 + 1234


def lcg_signal(n, seed):
    """int16-сигнал: тишина (шум ±64) и три «голоса» (меандр 400 Гц, меандр ~1230 Гц, пила ~2290 Гц) + шум.
    Сегменты 0.5..6 с, тип и длина — из того же LCG. Повторён в Kotlin (NemotronTestSignal)."""
    st = seed & 0xFFFFFFFF
    out = np.zeros(n, np.int32)

    def nxt():
        nonlocal st
        st = (st * 1664525 + 1013904223) & 0xFFFFFFFF
        return st

    t = 0
    while t < n:
        kind = (nxt() >> 8) % 4
        ln = 8000 + (nxt() >> 8) % 88000
        for _ in range(ln):
            if t >= n:
                break
            noise = ((nxt() >> 16) & 0xFFFF) - 32768
            if kind == 0:
                v = noise >> 9
            elif kind == 1:
                v = (8000 if (t // 20) % 2 else -8000) + (noise >> 6)
            elif kind == 2:
                v = (6000 if (t % 13) < 6 else -6000) + (noise >> 6)
            else:
                v = (t % 7) * 2000 - 6000 + (noise >> 6)
            out[t] = v
            t += 1
    return out.astype(np.int16)


class FakeEmbed:
    """[1, N, 128] -> [1, N/8, 512]: среднее 8 кадров и 4 линейные копии * 0.1."""

    def run(self, _names, feeds):
        f = feeds["features"][0]
        m = f.reshape(-1, 8, 128).mean(axis=1)
        e = np.concatenate([m, m * 0.5, -m, m * 0.25], axis=1) * np.float32(0.1)
        return [e.astype(np.float32)[None]]


class FakeStep:
    """[1, T, 512] -> [1, 8T, 8]: относительная энергия полос + вклад среднего по всему входу (кэш влияет)."""

    def run(self, _names, feeds):
        x = feeds["embeds"][0].astype(np.float32)
        t = x.shape[0]
        g = x.mean(axis=0)
        avg = x[:, :128].mean(axis=1)
        g_avg = g[:128].mean()
        out = np.zeros((t, 8, 8), np.float32)
        for k in range(8):
            band = x[:, 16 * k: 16 * k + 16].mean(axis=1)
            c = g[16 * k: 16 * k + 16].mean() - g_avg
            for u in range(8):
                out[:, u, k] = np.float32(8.0) * (band - avg - c) + np.float32(0.1 * (u - 3.5)) - np.float32(0.5)
        return [out.reshape(1, t * 8, 8)]


class RecordingCache(nd.SpeakerCache):
    picks = []

    def compress(self, embeds, probs):
        nf = probs.shape[0]
        e, p = super().compress(embeds, probs)
        # индексы выбранных кадров: восстановим через совпадение строк (кадры уникальны, тихий = nf)
        ext = np.concatenate([embeds, self.silence[None]], axis=0)
        idx = []
        for row in e:
            hits = np.nonzero((ext == row).all(axis=1))[0]
            idx.append(int(hits[0]) if len(hits) else -1)
        RecordingCache.picks.append({"n_in": int(nf), "frames": idx})
        return e, p


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mel", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)

    x = lcg_signal(N_SAMPLES, SEED).astype(np.float32) / np.float32(32768.0)
    fe = nd.Frontend(a.mel)
    fe.prepare(x)
    nf = fe.num_frames
    windows = [0, nd.CHUNK * nd.SUB, nf - 20]
    feats = np.concatenate([fe.frames(s, 32) for s in windows], axis=0)
    feats.astype("<f4").tofile(out / "features.bin")

    nem = nd.Nemotron.__new__(nd.Nemotron)
    nem.embed, nem.step, nem.fe = FakeEmbed(), FakeStep(), fe
    nem.silence = (np.arange(512, dtype=np.float32) % 7 - 3) * np.float32(0.01)
    orig = nd.SpeakerCache
    nd.SpeakerCache = RecordingCache
    try:
        p = nem.probs(x)
    finally:
        nd.SpeakerCache = orig
    p[::4].astype("<f4").tofile(out / "probs_every4.bin")
    turns = nd.to_turns(p, 0.5, 0.2, 0.5)
    dur_ms = len(x) * 1000 // 16000
    tr = [[k, int(round(b * 1000)), min(int(round(e * 1000)), dur_ms)] for k, b, e in turns]
    ref = {
        "seed": SEED, "n_samples": N_SAMPLES, "num_frames": nf, "feature_windows": windows,
        "frames_per_window": 32, "silence": "(i % 7 - 3) * 0.01", "compress": RecordingCache.picks,
        "turns": tr, "active_frames_per_spk": [int(v) for v in (p > 0.5).sum(axis=0)],
    }
    (out / "ref.json").write_text(json.dumps(ref), encoding="utf-8")
    Path(a.mel).read_bytes()
    (out / "mel_filters.bin").write_bytes(Path(a.mel).read_bytes())
    print("nf", nf, "turns", len(tr), "spk", len({t[0] for t in tr}), "compress", len(RecordingCache.picks),
          "active", ref["active_frames_per_spk"], "-1 in picks", sum(f.count(-1) for f in [c["frames"] for c in RecordingCache.picks]))


if __name__ == "__main__":
    main()
