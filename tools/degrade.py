#!/usr/bin/env python3
"""Искажения синтетических записей для матрицы качества (эталонная разметка не меняется).

python tools/degrade.py IN_DIR OUT_DIR --mode tel8k|quiet|clip [--suffix _tel]
  tel8k — полоса 300–3400 Гц и частота 8 кГц (телефон); файл остаётся 8 кГц;
  quiet — ослабление на 26 дБ (тихая запись, далеко от микрофона);
  clip  — усиление на 18 дБ и жёсткое ограничение (перегруз).
Копирует <name>.json и <name>.meta.json, дописывая условие quality в meta.
"""
import argparse
import json
import shutil
from pathlib import Path

import numpy as np
import soundfile as sf
from scipy.signal import butter, resample_poly, sosfilt


def tel8k(x: np.ndarray, sr: int):
    sos = butter(6, [300, 3400], btype="bandpass", fs=sr, output="sos")
    y = sosfilt(sos, x)
    g = np.gcd(sr, 8000)
    return resample_poly(y, 8000 // g, sr // g), 8000


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("in_dir")
    ap.add_argument("out_dir")
    ap.add_argument("--mode", required=True, choices=["tel8k", "quiet", "clip"])
    ap.add_argument("--suffix", default=None)
    a = ap.parse_args()
    src, dst = Path(a.in_dir), Path(a.out_dir)
    dst.mkdir(parents=True, exist_ok=True)
    suffix = a.suffix or f"_{a.mode}"
    for wav in sorted(src.glob("*.wav")):
        x, sr = sf.read(wav, dtype="float64", always_2d=False)
        if x.ndim > 1:
            x = x.mean(axis=1)
        if a.mode == "tel8k":
            y, sr = tel8k(x, sr)
        elif a.mode == "quiet":
            y = x * 10 ** (-26 / 20)
        else:
            y = np.clip(x * 10 ** (18 / 20), -0.6, 0.6)
        name = wav.stem + suffix
        sf.write(dst / f"{name}.wav", np.clip(y, -1, 1).astype(np.float32), sr, subtype="PCM_16")
        ref = wav.with_suffix(".json")
        if ref.exists():
            shutil.copy(ref, dst / f"{name}.json")
        meta_p = src / f"{wav.stem}.meta.json"
        if meta_p.exists():
            meta = json.loads(meta_p.read_text(encoding="utf-8"))
            meta.setdefault("conditions", {})["quality"] = a.mode
            (dst / f"{name}.meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1), encoding="utf-8")
        print(name, sr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
