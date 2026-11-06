"""Подготовка набора файлов для прогона BenchActivity на телефоне (вызывается из tools/phone_bench.sh).

    python tools/phone_prepare.py --stage .cache/phone_stage [--src testdata/test] [--edge] [--fmt]
                                  [--fmt-src distinct_2spk_000] [--long 30] [--jury] [--exclude REGEX]

Собирает в <stage>/:
  push/         плоский список аудио для adb push в .../files/bench/ (имена уникальны: BenchActivity
                пишет out/<nameWithoutExtension>.json, поэтому варианты форматов получают суффикс)
  refs_main/    эталоны <name>.json + <name>.meta.json для файлов из --src (если эталон есть рядом)
  refs_fmt/     эталоны для вариантов форматов (копия эталона исходника, conditions.format = вариант)
  refs_long/    эталон склеенного длинного файла (тайминги сдвинуты, спикеры сведены по meta.speakers_src)
  manifest.json {name: {kind, file, src, expect, ...}}

«Злые» файлы (.cache/edge): тишина/шум/музыка 10 с, короткое «да», стерео 48 кГц 24 бит, 8 бит 22 кГц,
битый WAV. Существующие файлы не перегенерируются, отсутствующие создаются.
Варианты форматов: ffmpeg (если есть на PATH или в $FFMPEG) → mp3/m4a/ogg/opus/amr/flac;
иначе soundfile → mp3/ogg/opus/flac, а m4a/amr пропускаются с предупреждением.
Только локальные файлы, сети нет.
"""
import argparse
import json
import math
import os
import re
import shutil
import subprocess
import sys
from math import gcd
from pathlib import Path

import numpy as np
import soundfile as sf
from scipy.signal import resample_poly

AUDIO_EXT = {"wav", "mp3", "m4a", "aac", "ogg", "opus", "flac", "amr", "3gp", "mp4", "webm"}
# Три «диалога» для сценария «как жюри»: 2 спикера чисто, 2 спикера похожие голоса, 3 спикера с шумом.
JURY = ["distinct_2spk_000", "clean_2spk_001", "noisy_3spk_000"]
SR = 16000

# (суффикс, расширение, аргументы ffmpeg, (sr, channels, sf_format, sf_subtype) для soundfile или None)
FMT_VARIANTS = [
    ("mp3_44k_st", "mp3", ["-ar", "44100", "-ac", "2", "-c:a", "libmp3lame", "-b:a", "128k"],
     (44100, 2, "MP3", "MPEG_LAYER_III")),
    ("m4a_44k_st", "m4a", ["-ar", "44100", "-ac", "2", "-c:a", "aac", "-b:a", "128k"], None),
    ("ogg_44k_st", "ogg", ["-ar", "44100", "-ac", "2", "-c:a", "libvorbis", "-q:a", "4"],
     (44100, 2, "OGG", "VORBIS")),
    ("opus_48k_mono", "opus", ["-ar", "48000", "-ac", "1", "-c:a", "libopus", "-b:a", "32k"],
     (48000, 1, "OGG", "OPUS")),
    ("amr_8k_mono", "amr", ["-ar", "8000", "-ac", "1", "-c:a", "libopencore_amrnb", "-b:a", "12.2k"], None),
    ("flac_48k_st", "flac", ["-ar", "48000", "-ac", "2", "-c:a", "flac"], (48000, 2, "FLAC", "PCM_16")),
]
# Если нужного внешнего кодека нет в сборке ffmpeg — встроенный (часть требует -strict -2).
FFMPEG_FALLBACK_CODEC = {"libvorbis": ["vorbis", "-strict", "-2"], "libopus": ["opus", "-strict", "-2"]}


def log(msg):
    print(f"[prepare] {msg}", flush=True)


def warn(msg):
    print(f"[prepare] ВНИМАНИЕ: {msg}", flush=True)


def load_mono(path):
    x, sr = sf.read(str(path), dtype="float32", always_2d=True)
    return x.mean(axis=1), sr


def resample(x, sr_from, sr_to):
    if sr_from == sr_to:
        return x
    g = gcd(sr_from, sr_to)
    return resample_poly(x, sr_to // g, sr_from // g).astype("float32")


def read_json(p):
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def write_json(p, obj):
    with open(p, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)


# ---------------------------------------------------------------- edge
def gen_edge(edge_dir, src_dir):
    """Создаёт отсутствующие «злые» файлы. Возвращает {name: (path, expect)}."""
    edge_dir.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(7)
    wavs = sorted(src_dir.glob("*.wav")) if src_dir else []
    out = {}

    def need(name):
        p = edge_dir / name
        return p, not p.exists()

    p, miss = need("silence_10s.wav")
    if miss:
        sf.write(p, np.zeros(10 * SR, dtype="int16"), SR, subtype="PCM_16")
    out["silence_10s"] = (p, "empty")

    p, miss = need("noise_10s.wav")
    if miss:  # розовый шум ~ -26 dBFS
        w = rng.standard_normal(10 * SR)
        pink = np.cumsum(w)
        pink -= np.convolve(pink, np.ones(400) / 400, mode="same")
        pink = pink / (np.abs(pink).max() + 1e-9) * 0.1
        sf.write(p, pink.astype("float32"), SR, subtype="PCM_16")
    out["noise_10s"] = (p, "empty")

    p, miss = need("music_10s.wav")
    if miss:  # аккорды с огибающей, 2 доли/с — без речи
        t = np.arange(10 * SR) / SR
        notes = [261.6, 329.6, 392.0, 523.3, 440.0, 349.2]
        y = np.zeros_like(t)
        for k, f0 in enumerate(notes):
            seg = (t >= k * 10 / len(notes)) & (t < (k + 1) * 10 / len(notes))
            env = np.exp(-((t * 2) % 1.0) * 3)
            for h, a in ((1, 1.0), (2, 0.4), (3, 0.2), (1.5, 0.3)):
                y += seg * env * a * np.sin(2 * np.pi * f0 * h * t)
        y = y / (np.abs(y).max() + 1e-9) * 0.5
        sf.write(p, y.astype("float32"), SR, subtype="PCM_16")
    out["music_10s"] = (p, "empty")

    p, miss = need("short_da.wav")
    if miss:  # самая короткая однословная реплика из эталонов + 400 мс тишины по краям
        best = None
        for j in sorted(src_dir.glob("*.json")) if src_dir else []:
            if j.name.endswith(".meta.json"):
                continue
            a = j.with_suffix(".wav")
            if not a.exists():
                continue
            for u in read_json(j).get("transcript", []):
                if len(u["text"].split()) == 1:
                    d = u["end_ms"] - u["start_ms"]
                    if best is None or d < best[0]:
                        best = (d, a, u["start_ms"], u["end_ms"])
        if best:
            x, sr = load_mono(best[1])
            x = resample(x, sr, SR)
            seg = x[int(best[2] * SR / 1000):int(best[3] * SR / 1000)]
            pad = np.zeros(int(0.4 * SR), dtype="float32")
            sf.write(p, np.concatenate([pad, seg, pad]), SR, subtype="PCM_16")
        else:
            warn("нет эталонов с однословной репликой — short_da.wav пропущен")
    if p.exists():
        out["short_da"] = (p, "nonempty")

    for name, sr_to, ch, subtype in (("stereo48k.wav", 48000, 2, "PCM_24"), ("pcm8bit_22k.wav", 22050, 1, "PCM_U8")):
        p, miss = need(name)
        if miss:
            if not wavs:
                warn(f"нет исходных wav — {name} пропущен")
                continue
            x, sr = load_mono(wavs[0])
            x = resample(x[: 90 * sr], sr, sr_to)
            if ch == 2:  # правый канал тише и с задержкой 2 мс — реальное стерео, не копия
                d = int(0.002 * sr_to)
                x = np.stack([x, np.concatenate([np.zeros(d, "float32"), x[:-d]]) * 0.7], axis=1)
            sf.write(p, np.clip(x, -1, 1), sr_to, subtype=subtype)
        out[Path(name).stem] = (p, "nonempty")

    p, miss = need("broken.wav")
    if miss:  # заголовок RIFF без data-чанка
        p.write_bytes(b"RIFF\x00\x00\x00\x00WAVEjunkjunk")
    out["broken"] = (p, "error_or_empty")
    return out


# ---------------------------------------------------------------- formats
def ffmpeg_path():
    f = os.environ.get("FFMPEG") or shutil.which("ffmpeg")
    return f if f and (Path(f).exists() or shutil.which(f)) else None


def ffmpeg_encoders(ff):
    try:
        r = subprocess.run([ff, "-hide_banner", "-encoders"], capture_output=True, text=True, timeout=30)
        return r.stdout
    except Exception:
        return ""


def gen_formats(src_audio, src_name, fmt_dir):
    """Варианты одного файла в разных контейнерах/частотах. Возвращает [(variant_name, path, suffix)]."""
    fmt_dir.mkdir(parents=True, exist_ok=True)
    ff = ffmpeg_path()
    enc = ffmpeg_encoders(ff) if ff else ""
    if ff:
        log(f"ffmpeg: {ff}")
    else:
        warn("ffmpeg не найден (PATH/$FFMPEG) — варианты через soundfile, m4a/amr будут пропущены")
    x = sr = None
    res = []
    for suffix, ext, ff_args, sf_spec in FMT_VARIANTS:
        name = f"{src_name}__{suffix}"
        dst = fmt_dir / f"{name}.{ext}"
        if dst.exists() and dst.stat().st_size > 0:
            res.append((name, dst, suffix))
            continue
        done = False
        if ff:
            args = list(ff_args)
            ci = args.index("-c:a") + 1
            codec = args[ci]
            if not re.search(rf"^\s*A\S*\s+{re.escape(codec)}\s", enc, re.M):
                alt = FFMPEG_FALLBACK_CODEC.get(codec)
                if alt and re.search(rf"^\s*A\S*\s+{alt[0]}\s", enc, re.M):
                    args[ci:ci + 1] = alt[:1]
                    args += alt[1:]
                else:
                    codec = None
            if codec:
                r = subprocess.run([ff, "-hide_banner", "-loglevel", "error", "-y", "-i", str(src_audio)] + args + [str(dst)],
                                   capture_output=True, text=True)
                done = r.returncode == 0 and dst.exists() and dst.stat().st_size > 0
                if not done:
                    warn(f"ffmpeg {suffix}: {r.stderr.strip()[:200]}")
            else:
                warn(f"в ffmpeg нет кодировщика {ff_args[ff_args.index('-c:a') + 1]} — пробую soundfile")
        if not done and sf_spec:
            try:
                if x is None:
                    x, sr = load_mono(src_audio)
                sr_to, ch, fmt, sub = sf_spec
                y = resample(x, sr, sr_to)
                if ch == 2:
                    y = np.stack([y, y * 0.9], axis=1)
                y = np.clip(y, -1, 1)
                # libsndfile (Vorbis/MP3) на Windows падает на одной большой записи — пишем блоками.
                with sf.SoundFile(str(dst), "w", sr_to, ch, subtype=sub, format=fmt) as fo:
                    for b in range(0, len(y), 4096):
                        fo.write(y[b:b + 4096])
                got = sf.info(str(dst)).duration
                done = abs(got - len(x) / sr) < 0.5
                if not done:
                    warn(f"soundfile {suffix}: длительность {got:.1f} с вместо {len(x) / sr:.1f} с")
            except Exception as e:  # noqa: BLE001
                warn(f"soundfile {suffix}: {e}")
        if done:
            res.append((name, dst, suffix))
        else:
            if dst.exists():
                dst.unlink()
            warn(f"вариант {suffix} ({ext}) пропущен")
    return res


# ---------------------------------------------------------------- long
def gen_long(src_dir, minutes, out_dir):
    """Склейка wav из src_dir по кругу (пауза 1 с) до >= minutes. Эталон: сдвиг таймингов,
    спикер = (conditions.voices, speakers_src[k-1]) — те же голоса TTS сводятся в одного speaker_N."""
    pairs = [(w, w.with_suffix(".json"), w.with_name(w.stem + ".meta.json")) for w in sorted(src_dir.glob("*.wav"))]
    pairs = [p for p in pairs if p[1].exists()]
    if not pairs:
        warn("нет wav с эталонами — длинный файл не создан")
        return None
    name = f"long_{minutes}m"
    wav = out_dir / f"{name}.wav"
    target = minutes * 60 * SR
    gap = np.zeros(SR, dtype="int16")
    items, n, i = [], 0, 0
    tmp = wav.with_suffix(".tmp.wav")
    with sf.SoundFile(str(tmp), "w", SR, 1, subtype="PCM_16") as fo:
        while n < target:
            w, j, m = pairs[i % len(pairs)]
            x, sr = load_mono(w)
            x = resample(x, sr, SR)
            pcm = (np.clip(x, -1, 1) * 32767).astype("int16")
            off_ms = n * 1000 // SR
            meta = read_json(m) if m.exists() else {}
            src = meta.get("speakers_src") or []
            voices = meta.get("conditions", {}).get("voices", "")
            for u in read_json(j)["transcript"]:
                k = int(u["speaker_id"].split("_")[1])
                key = f"{voices}:{src[k - 1]}" if k - 1 < len(src) else f"{w.stem}:{u['speaker_id']}"
                items.append((u["start_ms"] + off_ms, u["end_ms"] + off_ms, key, u["text"]))
            fo.write(pcm)
            fo.write(gap)
            n += len(pcm) + len(gap)
            i += 1
    os.replace(tmp, wav)
    items.sort(key=lambda t: (t[0], t[1]))
    ids, tr = {}, []
    for s, e, key, text in items:
        ids.setdefault(key, f"speaker_{len(ids) + 1}")
        tr.append({"speaker_id": ids[key], "start_ms": s, "end_ms": e, "text": text})
    dur = n * 1000 // SR
    log(f"длинный файл {wav.name}: {dur / 60000:.1f} мин, {len(tr)} реплик, {len(ids)} голосов")
    return name, wav, {"transcript": tr}, {"duration_ms": dur, "source": "synthetic",
                                           "conditions": {"duration": f"{minutes}m", "origin": "concat"}}


# ---------------------------------------------------------------- main
def main(argv=None):
    for st in (sys.stdout, sys.stderr):
        try:
            st.reconfigure(encoding="utf-8")
        except (AttributeError, ValueError):
            pass
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--stage", default=".cache/phone_stage")
    ap.add_argument("--src", default="testdata/test", help="папка с аудио (+ эталоны <name>.json рядом)")
    ap.add_argument("--edge", action="store_true", help="добавить «злые» файлы из --edge-dir")
    ap.add_argument("--edge-dir", default=".cache/edge")
    ap.add_argument("--fmt", action="store_true", help="добавить варианты форматов одного файла")
    ap.add_argument("--fmt-src", default="distinct_2spk_000", help="имя файла из --src для вариантов форматов")
    ap.add_argument("--long", type=int, default=0, help="минут: склеить длинный файл (0 = нет)")
    ap.add_argument("--jury", action="store_true", help=f"из --src взять только {', '.join(JURY)}")
    ap.add_argument("--exclude", help="regex: исключить имена (без расширения)")
    a = ap.parse_args(argv)

    stage = Path(a.stage)
    src = Path(a.src)
    if not src.is_dir():
        print(f"нет папки {src}", file=sys.stderr)
        return 2
    for sub in ("push", "refs_main", "refs_fmt", "refs_long"):
        d = stage / sub
        if d.exists():
            shutil.rmtree(d)
        d.mkdir(parents=True)
    manifest = {}
    excl = re.compile(a.exclude) if a.exclude else None

    def add(name, path, kind, **kw):
        if excl and kind != "long" and excl.search(name):  # --exclude . --long 30 = только длинный файл
            return False
        if name in manifest:
            warn(f"дубликат имени {name} — пропущен ({path})")
            return False
        ext = Path(path).suffix.lower()
        dst = stage / "push" / f"{name}{ext}"
        shutil.copyfile(path, dst)
        if kw.get("duration_ms") is None:
            try:
                kw["duration_ms"] = int(sf.info(str(dst)).duration * 1000)
            except Exception:  # m4a/amr/битый — soundfile не читает
                kw["duration_ms"] = None
        manifest[name] = {"kind": kind, "file": dst.name, "src_path": str(path).replace("\\", "/"),
                          "bytes": dst.stat().st_size, **kw}
        return True

    # 1. основной набор
    audio = sorted(p for p in src.iterdir() if p.is_file() and p.suffix.lower().lstrip(".") in AUDIO_EXT)
    if a.jury:
        audio = [p for p in audio if p.stem in JURY]
        miss = set(JURY) - {p.stem for p in audio}
        if miss:
            warn(f"в {src} нет файлов жюри-набора: {sorted(miss)}")
    for p in audio:
        ref = p.with_suffix(".json")
        dur = None
        try:
            dur = int(sf.info(str(p)).frames * 1000 // sf.info(str(p)).samplerate)
        except Exception:
            pass
        if add(p.stem, p, "main", has_ref=ref.exists(), duration_ms=dur) and ref.exists():
            shutil.copyfile(ref, stage / "refs_main" / ref.name)
            meta = p.with_name(p.stem + ".meta.json")
            if meta.exists():
                shutil.copyfile(meta, stage / "refs_main" / meta.name)
            elif dur:
                write_json(stage / "refs_main" / meta.name, {"duration_ms": dur})

    # 2. злые файлы
    if a.edge:
        for name, (path, expect) in gen_edge(Path(a.edge_dir), src).items():
            add(f"edge_{name}", path, "edge", expect=expect)

    # 3. форматы
    if a.fmt:
        cand = [p for p in src.glob(f"{a.fmt_src}.*") if p.suffix.lower().lstrip(".") in AUDIO_EXT]
        if not cand:
            warn(f"нет исходника {a.fmt_src} в {src} — варианты форматов пропущены")
        else:
            s = cand[0]
            ref, meta = s.with_suffix(".json"), s.with_name(s.stem + ".meta.json")
            for name, path, suffix in gen_formats(s, s.stem, Path(".cache/phone_fmt")):
                if add(name, path, "fmt", fmt_of=s.stem, variant=suffix) and ref.exists():
                    shutil.copyfile(ref, stage / "refs_fmt" / f"{name}.json")
                    m = read_json(meta) if meta.exists() else {"duration_ms": int(sf.info(str(s)).duration * 1000)}
                    m.setdefault("conditions", {})["format"] = suffix
                    write_json(stage / "refs_fmt" / f"{name}.meta.json", m)
            # исходный wav в refs_fmt тоже — для сравнения в одной таблице
            if s.stem in manifest and ref.exists():
                shutil.copyfile(ref, stage / "refs_fmt" / ref.name)
                m = read_json(meta) if meta.exists() else {}
                m.setdefault("conditions", {})["format"] = f"{s.suffix.lstrip('.')}_orig"
                write_json(stage / "refs_fmt" / f"{s.stem}.meta.json", m)

    # 4. длинный файл
    if a.long > 0:
        r = gen_long(src, a.long, Path(".cache"))
        if r:
            name, wav, ref, meta = r
            if add(name, wav, "long", duration_ms=meta["duration_ms"]):
                write_json(stage / "refs_long" / f"{name}.json", ref)
                write_json(stage / "refs_long" / f"{name}.meta.json", meta)

    write_json(stage / "manifest.json", manifest)
    total = sum(v["bytes"] for v in manifest.values())
    kinds = {}
    for v in manifest.values():
        kinds[v["kind"]] = kinds.get(v["kind"], 0) + 1
    log(f"{len(manifest)} файлов ({', '.join(f'{k}={n}' for k, n in kinds.items())}), {total / 2**20:.1f} МБ → {stage / 'push'}")
    est = sum((v.get("duration_ms") or 0) for v in manifest.values())
    if est:
        log(f"аудио с известной длительностью: {est / 60000:.1f} мин (при RTF 0.5 ≈ {est / 120000:.0f} мин прогона)")
    return 0 if manifest else 1


if __name__ == "__main__":
    sys.exit(main())
