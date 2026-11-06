"""Генератор синтетических диалогов с точной разметкой спикеров и времени.

    python tools/make_synthetic.py SRC OUT_DIR [--layout dirs|cv|fleurs] [--n 10]
        [--speakers 2] [--duration-s 60] [--snr-db 10] [--noise-dir DIR]
        [--overlap-prob 0.3] [--max-overlap 0.2] [--max-pause 1.5] [--seed 0] [--prefix syn]

Пишет в OUT_DIR: <name>.wav (16 кГц моно PCM16), <name>.json (эталон по контракту)
и <name>.meta.json (duration_ms, conditions, источники) — формат для tools/report.py.

Алгоритм: реплики выбранных дикторов чередуются (соседние — разные дикторы),
тишина по краям каждой реплики срезается (порог -40 дБ от пика), между репликами
пауза U(0, max_pause) с, либо с вероятностью overlap_prob перекрытие на долю
U(0, max_overlap) от длины более короткой из двух соседних реплик. Опционально
шум с заданным SNR (по мощности речи): файлы из --noise-dir (wav/flac, зацикливаются)
или розовый шум, если --noise-dir не указан. В конце пиковая нормализация до -1 dBFS.

Ожидаемая структура SRC
-----------------------
--layout dirs (по умолчанию; свои записи, SOVA, OpenSTT и т.п.):
    SRC/<speaker>/<utt>.wav|.flac|.mp3|.ogg  +  SRC/<speaker>/<utt>.txt (текст, UTF-8)
--layout cv (Common Voice ru, распакованный архив cv-corpus-*/ru):
    SRC/validated.tsv (колонки client_id, path, sentence, ...)  +  SRC/clips/<path>.mp3
    Диктор = client_id. Нужен libsndfile >= 1.1 (soundfile>=0.12 на Windows уже с ним) для mp3.
--layout fleurs (google/fleurs, ru_ru, как в репозитории HF: data/ru_ru/):
    SRC/<split>.tsv (без заголовка: id, file_name, raw_transcription, transcription, ...,
    num_samples, gender)  +  SRC/audio/<split>/<file_name>.wav (или SRC/<split>/<file_name>)
    В FLEURS НЕТ id диктора: каждая запись считается отдельным псевдо-диктором,
    поэтому у каждого «диктора» диалога ровно одна реплика (годится для 1 реплики на
    спикера/проверки ASR, не для оценки кластеризации). Для диаризации — cv или dirs.
Лицензии: Common Voice — CC0; FLEURS — CC BY 4.0 (указывать в testdata/README.md).
"""
import argparse
import csv
import json
import random
import sys
from pathlib import Path

import numpy as np
import soundfile as sf
from scipy.signal import resample_poly

SR = 16000
AUDIO_EXT = (".wav", ".flac", ".mp3", ".ogg", ".opus")


def load_audio(path):
    x, sr = sf.read(str(path), dtype="float32", always_2d=True)
    x = x.mean(1)
    if sr != SR:
        g = np.gcd(sr, SR)
        x = resample_poly(x, SR // g, sr // g).astype(np.float32)
    return x


def trim(x, thr_db=-40.0, frame=320):
    if len(x) < frame:
        return x
    n = len(x) // frame
    e = np.sqrt((x[: n * frame].reshape(n, frame) ** 2).mean(1) + 1e-12)
    on = np.where(20 * np.log10(e / (e.max() + 1e-12)) > thr_db)[0]
    if len(on) == 0:
        return x[:0]
    return x[on[0] * frame:(on[-1] + 1) * frame]


# ---------- источники: {speaker: [(audio_path, text)]} ----------
def scan_dirs(src):
    out = {}
    for d in sorted(p for p in Path(src).iterdir() if p.is_dir()):
        for a in sorted(d.iterdir()):
            t = a.with_suffix(".txt")
            if a.suffix.lower() in AUDIO_EXT and t.exists():
                txt = t.read_text(encoding="utf-8").strip()
                if txt:
                    out.setdefault(d.name, []).append((a, txt))
    return out


def scan_cv(src):
    src, out = Path(src), {}
    with open(src / "validated.tsv", encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f, delimiter="\t", quoting=csv.QUOTE_NONE):
            if r.get("sentence", "").strip():
                out.setdefault(r["client_id"], []).append((src / "clips" / r["path"], r["sentence"].strip()))
    return out


def scan_fleurs(src):
    src, out = Path(src), {}
    for tsv in sorted(src.glob("*.tsv")):
        split = tsv.stem
        with open(tsv, encoding="utf-8", newline="") as f:
            for r in csv.reader(f, delimiter="\t", quoting=csv.QUOTE_NONE):
                if len(r) < 3:
                    continue
                for a in (src / "audio" / split / r[1], src / split / r[1]):
                    if a.exists():
                        out[f"fleurs_{split}_{r[1]}"] = [(a, r[2].strip())]
                        break
    return out


def pink_noise(n, rng):
    w = np.fft.rfft(rng.standard_normal(n))
    f = np.arange(len(w)); f[0] = 1
    x = np.fft.irfft(w / np.sqrt(f), n)
    return (x / (np.abs(x).max() + 1e-9)).astype(np.float32)


def make_noise(n, noise_files, rng):
    if not noise_files:
        return pink_noise(n, rng)
    x = load_audio(rng.choice(noise_files))
    if len(x) == 0:
        return pink_noise(n, rng)
    x = np.tile(x, n // len(x) + 1)
    o = rng.integers(0, len(x) - n + 1)
    return x[o:o + n]


def build_dialog(pool, speakers, duration_s, a, rng):
    """Возвращает (audio, segments[(spk_idx, start_ms, end_ms, text)], sources)."""
    queues = {s: list(pool[s]) for s in speakers}
    for q in queues.values():
        rng.shuffle(q)
    placed, t, prev, prev_len = [], 0, None, 0  # t — конец предыдущей реплики (сэмплы)
    order = list(speakers)
    while True:
        cand = [s for s in order if queues[s] and s != prev] or ([prev] if prev is not None and queues[prev] and len(order) == 1 else [])
        if not cand:
            break
        # первые реплики — по порядку дикторов (чтобы все прозвучали), дальше случайно
        unseen = [s for s in order if s not in {p[0] for p in placed} and s in cand]
        s = unseen[0] if unseen else cand[rng.integers(len(cand))]
        path, txt = queues[s].pop()
        x = trim(load_audio(path))
        if len(x) < SR * 0.3:
            continue
        if not placed:
            start = int(0.3 * SR)
        elif len(speakers) > 1 and rng.random() < a.overlap_prob:
            ov = int(rng.uniform(0, a.max_overlap) * min(len(x), prev_len))
            start = max(t - ov, placed[-1][1] + 1)
        else:
            start = t + int(rng.uniform(0, a.max_pause) * SR)
        placed.append((s, start, x, txt, str(path)))
        t, prev, prev_len = max(t, start + len(x)), s, len(x)
        if t >= duration_s * SR:
            break
    total = t + int(0.3 * SR)
    y = np.zeros(total, dtype=np.float32)
    for _, st, x, _, _ in placed:
        y[st:st + len(x)] += x
    if a.snr_db is not None and placed:
        speech = np.concatenate([x for _, _, x, _, _ in placed])
        ps = float((speech ** 2).mean())
        nz = make_noise(total, a.noise_files, rng)
        pn = float((nz ** 2).mean()) + 1e-12
        y += nz * np.sqrt(ps / (pn * 10 ** (a.snr_db / 10)))
    y *= 10 ** (-1 / 20) / (np.abs(y).max() + 1e-9)
    label = {}
    segs = []
    for s, st, x, txt, _ in sorted(placed, key=lambda p: p[1]):
        label.setdefault(s, len(label) + 1)
        segs.append({"speaker_id": f"speaker_{label[s]}", "start_ms": int(st * 1000 // SR),
                     "end_ms": int((st + len(x)) * 1000 // SR), "text": txt})
    return y, segs, [p[4] for p in placed]


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("src")
    ap.add_argument("out_dir")
    ap.add_argument("--layout", choices=["dirs", "cv", "fleurs"], default="dirs")
    ap.add_argument("--n", type=int, default=5, help="число диалогов")
    ap.add_argument("--speakers", type=int, default=2)
    ap.add_argument("--duration-s", type=float, default=60.0, help="целевая длительность (не меньше)")
    ap.add_argument("--snr-db", type=float, default=None)
    ap.add_argument("--noise-dir")
    ap.add_argument("--overlap-prob", type=float, default=0.3)
    ap.add_argument("--max-overlap", type=float, default=0.2)
    ap.add_argument("--max-pause", type=float, default=1.5)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--prefix", default="syn")
    a = ap.parse_args(argv)
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from validate_json import utf8_stdout
    utf8_stdout()
    pool = {"dirs": scan_dirs, "cv": scan_cv, "fleurs": scan_fleurs}[a.layout](a.src)
    pool = {k: v for k, v in pool.items() if v}
    if a.layout == "fleurs":
        print("WARN: в FLEURS нет id диктора — одна реплика на псевдо-диктора", file=sys.stderr)
    if len(pool) < a.speakers:
        print(f"ERROR: найдено {len(pool)} дикторов в {a.src}, нужно {a.speakers}", file=sys.stderr)
        return 1
    a.noise_files = sorted(str(p) for p in Path(a.noise_dir).rglob("*")
                           if p.suffix.lower() in AUDIO_EXT) if a.noise_dir else []
    rng = np.random.default_rng(a.seed)
    out = Path(a.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    names = sorted(pool)
    for i in range(a.n):
        spk = list(rng.choice(names, size=a.speakers, replace=False))
        y, segs, srcs = build_dialog(pool, spk, a.duration_s, a, rng)
        if not segs:
            print(f"WARN: диалог {i} пуст, пропущен", file=sys.stderr)
            continue
        name = f"{a.prefix}_{a.speakers}spk_{i:03d}"
        sf.write(str(out / f"{name}.wav"), y, SR, subtype="PCM_16")
        (out / f"{name}.json").write_text(json.dumps({"transcript": segs}, ensure_ascii=False, indent=1),
                                          encoding="utf-8")
        dur = int(len(y) * 1000 // SR)
        meta = {"duration_ms": dur, "source": a.layout,
                "conditions": {"speakers": a.speakers,
                               "duration": "<1m" if dur < 60e3 else "5m" if dur < 15 * 60e3 else "30m" if dur < 45 * 60e3 else "60m",
                               "quality": "clean" if a.snr_db is None else f"snr{int(a.snr_db)}",
                               "dynamics": "overlap" if a.overlap_prob > 0 else "turns",
                               "origin": "synthetic"},
                "params": {k: getattr(a, k) for k in ("overlap_prob", "max_overlap", "max_pause", "snr_db", "seed")},
                "speakers_src": [str(s) for s in spk], "utterances_src": srcs}
        (out / f"{name}.meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"{name}: {dur / 1000:.1f} s, {len(segs)} реплик")
    return 0


if __name__ == "__main__":
    sys.exit(main())
