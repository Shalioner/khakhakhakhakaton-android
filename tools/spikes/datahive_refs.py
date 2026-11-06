"""Спайк: живые русские диалоги DataHive (стерео, каждый участник на своём канале) -> моно wav + приблизительный эталон.

    python -I tools/spikes/datahive_refs.py .cache/data/datahive/preview .cache/nemotron/datahive

Эталон строится энергетическим VAD по каждому каналу отдельно (канал = спикер): RMS окон 20 мс с шагом 10 мс,
порог max(шум(p10) + 15 дБ, p99 - 35 дБ), склейка пауз < 300 мс, отброс сегментов < 200 мс.
Это НЕ ручная разметка: границы приблизительные, паузы внутри фраз и эхо/просачивание между каналами
дают систематические ошибки. Годится только для ОТНОСИТЕЛЬНОГО сравнения диаризаторов на одном эталоне.
Выход: <name>.wav (моно 16 кГц PCM16, сумма каналов), <name>.json (контракт, text = "·"), <name>.meta.json.
"""
import json
import math
import sys
import wave
from pathlib import Path

import numpy as np
import soundfile as sf
from scipy.signal import resample_poly

SR = 16000


def channel_segments(x):
    win, hop = 320, 160
    n = 1 + max(0, (len(x) - win) // hop)
    fr = np.lib.stride_tricks.sliding_window_view(x, win)[::hop][:n]
    db = 10 * np.log10((fr.astype(np.float64) ** 2).mean(1) + 1e-12)
    thr = max(np.percentile(db, 10) + 15, np.percentile(db, 99) - 35)
    act = np.concatenate([[False], db > thr, [False]]).astype(np.int8)
    d = np.diff(act)
    segs = [[b * 10, e * 10 + 10] for b, e in zip(np.nonzero(d == 1)[0], np.nonzero(d == -1)[0])]
    merged = []
    for s in segs:
        if merged and s[0] - merged[-1][1] < 300:
            merged[-1][1] = s[1]
        else:
            merged.append(s)
    return [s for s in merged if s[1] - s[0] >= 200], thr


def main(src, dst):
    dst = Path(dst)
    dst.mkdir(parents=True, exist_ok=True)
    for f in sorted(Path(src).glob("*.mp3")):
        y, sr = sf.read(str(f), dtype="float32", always_2d=True)
        g = math.gcd(sr, SR)
        y = np.stack([resample_poly(y[:, c], SR // g, sr // g) for c in range(y.shape[1])], 1).astype(np.float32)
        dur_ms = len(y) * 1000 // SR
        tr, stats = [], {}
        for c in range(y.shape[1]):
            segs, thr = channel_segments(y[:, c])
            stats[f"ch{c}"] = {"speech_s": round(sum(e - s for s, e in segs) / 1000, 1), "thr_db": round(float(thr), 1)}
            tr += [{"speaker_id": f"ch{c}", "start_ms": int(s), "end_ms": int(min(e, dur_ms)), "text": "·"} for s, e in segs]
        tr.sort(key=lambda t: t["start_ms"])
        order = {}
        for t in tr:
            t["speaker_id"] = "speaker_%d" % (order.setdefault(t["speaker_id"], len(order)) + 1)
        mono = y.mean(1)
        pcm = (np.clip(mono, -1, 1) * 32767).astype("<i2")
        with wave.open(str(dst / f"{f.stem}.wav"), "wb") as w:
            w.setnchannels(1), w.setsampwidth(2), w.setframerate(SR), w.writeframes(pcm.tobytes())
        (dst / f"{f.stem}.json").write_text(json.dumps({"transcript": tr}, ensure_ascii=False, indent=1), encoding="utf-8")
        meta = {"duration_ms": dur_ms, "source": "datahive preview (CC-BY-4.0), energy-VAD per channel",
                "conditions": {"speakers": 2, "quality": "phone_opus", "voices": "real_ru", "origin": "real"},
                "ref_stats": stats}
        (dst / f"{f.stem}.meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f.stem, dur_ms / 1000, stats, len(tr))


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:3]))
