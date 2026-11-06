"""Сборка наборов testdata/live_dev и testdata/live_test из открытых источников (.cache/data/).

    python -I tools/build_live_sets.py fetch                 # скачать подмножества (~400 МБ) в .cache/data/
    python -I tools/build_live_sets.py datahive|langswap|piper|meow|all [--root .]

Запускать из корня репозитория (или указать --root). Полный конвейер — tools/build_live_sets.sh
(шаг piper требует .cache/venv_piper с piper-tts, см. tools/piper_synth.py).
Каждый файл набора: <name>.wav (16 кГц моно PCM16) + <name>.json (эталон по контракту) + <name>.meta.json.

Источники и что из них делается
-------------------------------
datahive  DataHive Russian Conversations (sample), CC BY 4.0. Живые телефонные разговоры (VoIP/Opus),
          стерео: звонящий — левый канал, принимающий — правый, общая шкала времени, утечки между
          каналами нет (корреляция каналов ~0). Аудио набора = сумма каналов → 16 кГц моно, пик -1 dBFS.
          Эталон (без текста, meta "has_text": false) строится по речевой активности КАЖДОГО канала:
            1) полоса 100–1000 Гц (Баттерворт 4-го порядка, без сдвига фазы), кадры 20 мс с шагом 10 мс;
            2) уровень кадра в дБ; опорный уровень канала L95 = 95-й перцентиль кадров;
               порог включения L95-30 дБ, выключения L95-36 дБ (гистерезис), но не ниже -65/-71 дБ;
            3) отрезок остаётся, только если в нём >= 5 и >= 15 % «голосовых» кадров (максимум
               нормированной автокорреляции > 0.6 на лагах F0 60–400 Гц) — отсекает шорохи/щелчки;
            4) каждый отрезок расширяется на 50 мс с обеих сторон (слабые согласные на краях);
            5) отрезки одного канала с паузой < 1000 мс сливаются в реплику; реплики < 250 мс отбрасываются.
          Погрешность оценивается сравнением с независимым детектором Silero VAD (тот же канал;
          порог 0.5/0.35, мин. речь 250 мс, пауза 100 мс, те же шаги 3–4): кадровое совпадение,
          DER «энергия против Silero» и ошибка границ пишутся в meta "ref_check".
          Известные систематические ошибки: смех/кашель/дыхание у микрофона размечаются как речь
          (детектор не отличает неречь), тихие хвосты фраз ниже порога теряются.
langswap  Langswap «Dialogs» (OpenRAIL, коммерция разрешена, ограничения использования в LICENSE.md).
          Сценарные диалоги актёров (Masha Ж, Sveta Ж, Dima М), реплики записаны по одной, имя файла
          = <пара>_part<K>_<номер реплики>. Берём непрерывные окна по 36 реплик с максимумом
          стилизованных (whisper/laughing ×3, arrogance/angry/fear ×1) и ставим реплики подряд с паузой
          U(0.15, 0.9) с (у того же диктора U(0.2, 0.6) с), с вероятностью 0.15 — перекрытие до 10 %.
          Текст и спикер — из metadata.csv; соседние реплики одного диктора с паузой < 1 с сливаются.
          Masha↔Sveta — похожие женские голоса (voices=ff_stylized), Masha↔Dima — mf_stylized.
          Дикторы в dev и test одни и те же (их всего 3), не пересекаются части сценария (part).
piper     Голоса Piper ru (rhasspy/piper-voices medium: irina, ruslan, dmitri, denis) произносят фразы
          testdata/synth_lines_ru.txt (чётные строки — dev, нечётные — test, как в testdata/dev|test);
          диалоги склеивает tools/make_synthetic.build_dialog (паузы 0–1.2 с, перекрытия до 15 %).
meow      2–3 диалога из testdata/dev + клипы ESC-50 (cat/dog/laughing, CC BY-NC 3.0 — только внутренние
          тесты): клип вставляется в паузу между репликами (сдвигая последующие), эталон не меняется
          (неречь не должна давать реплик). Позиции вставок — в meta "inserted".
"""
import argparse
import csv
import json
import re
import sys
import urllib.request
from pathlib import Path

import numpy as np
import soundfile as sf
from scipy.signal import butter, resample_poly, sosfiltfilt

sys.path.insert(0, str(Path(__file__).resolve().parent))
import make_synthetic  # noqa: E402
from boundary_err import boundary_err  # noqa: E402
from der import der  # noqa: E402
from validate_json import load_segments, utf8_stdout, validate  # noqa: E402

SR = 16000
HF = "https://huggingface.co"
NO_TEXT = "[речь]"  # заглушка: контракт требует непустой text; для has_text=false WER не считается

# ---------- состав наборов (фиксирован ради воспроизводимости) ----------
DATAHIVE_REPO = "datahiveai/russian-conversations-sample"
# dev и test разведены по паре стран звонящего/принимающего (в данных нет id диктора; повторы
# одного диктора в sample ограничены, но есть — пара стран служит грубым прокси диктора).
DATAHIVE = {
    "dev": ["ru_000007", "ru_000017", "ru_000013", "ru_000014"],   # KZ-KZ ×2, ME-ME ×2
    "test": ["ru_000002", "ru_000009", "ru_000004", "ru_000003"],  # RU-RU ×2, UA-UA, DK-SK
    # ru_000005 исключён: на канале 1 энергия и Silero расходятся (DER 0.6) — эталон ненадёжен
}
DATAHIVE_MAX_S = 420  # длиннее — обрезать по общей паузе не позже 7 мин

LANGSWAP_REPO = "langswap/dialogs-ru-emotional-conversations"
LANGSWAP_WIN = 36
LANGSWAP = {  # (part, номер первой реплики окна) — выбраны select_langswap_windows()
    "dev": [("masha_sveta_part9", 1258), ("masha_sveta_part5", 141), ("masha_dima_part9", 192)],
    "test": [("masha_sveta_part3", 700), ("masha_sveta_part6", 306), ("masha_dima_part11", 267)],
}
LANGSWAP_STYLE_W = {"whisper": 3, "laughing": 3, "arrogance": 1, "angry": 1, "fear": 1}
LANGSWAP_SPK = {"M": "Masha", "S": "Sveta", "D": "Dima"}

PIPER_VOICES = {"irina": "f", "ruslan": "m", "dmitri": "m", "denis": "m"}
PIPER = {
    "dev": [("irina", "ruslan"), ("dmitri", "denis", "irina")],
    "test": [("irina", "dmitri"), ("ruslan", "denis", "irina")],
}

ESC50_RAW = "https://raw.githubusercontent.com/karolpiczak/ESC-50/master"
ESC50_N = {"cat": 8, "dog": 5, "laughing": 5}  # каждый 5-й файл категории по алфавиту
MEOW_BASES = ["distinct_2spk_000", "clean_3spk_000", "clean_2spk_001"]  # из testdata/dev


# ---------- утилиты ----------
def _download(url, dst):
    dst = Path(dst)
    if dst.exists() and dst.stat().st_size > 0:
        return 0
    dst.parent.mkdir(parents=True, exist_ok=True)
    tmp = dst.with_name(dst.name + ".part")
    with urllib.request.urlopen(url, timeout=120) as r, open(tmp, "wb") as f:
        while True:
            b = r.read(1 << 20)
            if not b:
                break
            f.write(b)
    tmp.replace(dst)
    return dst.stat().st_size


def _resample(x, sr):
    if sr == SR:
        return x.astype(np.float32)
    g = np.gcd(sr, SR)
    return resample_poly(x, SR // g, sr // g).astype(np.float32)


def _bucket(dur_ms):
    return "<1m" if dur_ms < 60e3 else "5m" if dur_ms < 15 * 60e3 else "30m" if dur_ms < 45 * 60e3 else "60m"


def label_segments(segs):
    """[(key, s, e, text)] -> transcript по контракту: сортировка, speaker_N по первому появлению."""
    segs = sorted(segs, key=lambda s: (s[1], s[2]))
    lab, out = {}, []
    for k, s, e, t in segs:
        lab.setdefault(k, len(lab) + 1)
        out.append({"speaker_id": f"speaker_{lab[k]}", "start_ms": int(s), "end_ms": int(e), "text": t})
    return out, lab


def merge_same_speaker(segs, gap_ms=1000):
    """Сливает соседние (по времени) реплики одного диктора с паузой < gap_ms, если между ними
    не начинается реплика другого диктора."""
    segs = sorted(segs, key=lambda s: (s[1], s[2]))
    out = []
    for s in segs:
        if out and out[-1][0] == s[0] and s[1] - out[-1][2] < gap_ms:
            k, a, b, t = out[-1]
            out[-1] = (k, a, max(b, s[2]), (t + " " + s[3]).strip())
        else:
            out.append(s)
    return out


def write_item(out_dir, name, y, transcript, meta):
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    y = np.asarray(y, dtype=np.float32)
    y = y * (10 ** (-1 / 20) / (np.abs(y).max() + 1e-9))
    sf.write(str(out_dir / f"{name}.wav"), y, SR, subtype="PCM_16")
    dur = int(len(y) * 1000 // SR)
    obj = {"transcript": transcript}
    errs = validate(obj, dur)
    if errs:
        raise RuntimeError(f"{name}: эталон нарушает контракт: {errs[:3]}")
    (out_dir / f"{name}.json").write_text(json.dumps(obj, ensure_ascii=False, indent=1), encoding="utf-8")
    meta = {"duration_ms": dur, **meta}
    meta.setdefault("conditions", {})["duration"] = _bucket(dur)
    (out_dir / f"{name}.meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"{out_dir.name}/{name}: {dur / 1000:.1f} s, {len(transcript)} реплик, "
          f"{len({t['speaker_id'] for t in transcript})} спк")
    return dur


# ---------- детекторы речевой активности канала ----------
_SOS_VOICE = butter(4, [100, 1000], btype="bandpass", fs=SR, output="sos")
_SOS_LP = butter(4, 900, fs=SR, output="sos")


def _runs(mask, hop_ms):
    """Булева маска кадров -> [(start_ms, end_ms)]."""
    m = np.concatenate([[False], mask, [False]]).astype(np.int8)
    d = np.diff(m)
    on, off = np.where(d == 1)[0], np.where(d == -1)[0]
    return [(int(a * hop_ms), int(b * hop_ms)) for a, b in zip(on, off)]


def _voicing(y, hop, n, win=512):
    """Максимум нормированной автокорреляции (лаги 2.5–16.7 мс, F0 60–400 Гц) по кадрам 32 мс."""
    w = sosfiltfilt(_SOS_LP, y.astype(np.float64))
    v = np.zeros(n)
    m = min(n, max((len(w) - win) // hop + 1, 0))
    for a in range(0, m, 4096):  # блоками, чтобы не держать всю матрицу кадров
        b = min(a + 4096, m)
        idx = np.arange(win)[None, :] + hop * np.arange(a, b)[:, None]
        f = w[idx]
        f = f - f.mean(1, keepdims=True)
        ac = np.fft.irfft(np.abs(np.fft.rfft(f, 2 * win)) ** 2)[:, :win]
        v[a:b] = (ac[:, 40:268] / (ac[:, :1] + 1e-12)).max(1)
    return v


def energy_activity(y, hop_ms=10, win_ms=20, rel_on=30.0, hyst=6.0, abs_min=-65.0,
                    voiced_thr=0.6, min_voiced_frames=5, min_voiced_share=0.15):
    """Сырые отрезки речи канала (до слияния пауз): энергия в полосе 100–1000 Гц с гистерезисом
    относительно L95 канала + проверка, что в отрезке есть периодичность (голос), а не широкополосный шум."""
    z = sosfiltfilt(_SOS_VOICE, y.astype(np.float64))
    hop, win = SR * hop_ms // 1000, SR * win_ms // 1000
    n = max((len(z) - win) // hop + 1, 0)
    if n == 0:
        return []
    idx = np.arange(win)[None, :] + hop * np.arange(n)[:, None]
    e = 10 * np.log10((z[idx] ** 2).mean(1) + 1e-12)
    l95 = float(np.percentile(e, 95))
    on = max(l95 - rel_on, abs_min)
    off = on - hyst
    act = np.zeros(n, dtype=bool)
    state = False
    for i, v in enumerate(e):
        state = v >= on if not state else v >= off
        act[i] = state
    voiced = (_voicing(y, hop, n) > voiced_thr) & act
    out = []
    for s, e_ in _runs(act, hop_ms):
        vv = voiced[s // hop_ms:e_ // hop_ms]
        if vv.sum() >= min_voiced_frames and vv.mean() >= min_voiced_share:
            # кадр i покрывает [i*hop, i*hop+win): сдвиг на половину окна — к центрам кадров
            out.append((s + win_ms // 2, e_ + win_ms // 2))
    return out


class Silero:
    """Silero VAD (onnx, вход x[1,512], h/c[2,1,64]) — только для оценки погрешности эталона."""

    def __init__(self, path):
        import onnxruntime as ort
        so = ort.SessionOptions()
        so.intra_op_num_threads = 1
        self.s = ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])

    def activity(self, y, thr=0.5, neg=0.35, min_speech_ms=250, min_sil_ms=100):
        h = np.zeros((2, 1, 64), np.float32)
        c = np.zeros((2, 1, 64), np.float32)
        probs = []
        y = y.astype(np.float32)
        for i in range(0, len(y) - 511, 512):
            p, h, c = self.s.run(None, {"x": y[None, i:i + 512], "h": h, "c": c})
            probs.append(float(p[0, 0]))
        step = 32  # мс на чанк
        segs, start, last_speech = [], None, None
        for i, p in enumerate(probs):
            t = i * step
            if p >= thr:
                if start is None:
                    start = t
                last_speech = t + step
            elif start is not None and p < neg and t - last_speech >= min_sil_ms:
                segs.append((start, last_speech))
                start = None
        if start is not None:
            segs.append((start, last_speech))
        return [s for s in segs if s[1] - s[0] >= min_speech_ms]


def finalize_turns(raw, dur_ms, pad_ms=50, gap_ms=1000, min_ms=250):
    segs = []
    for a, b in raw:
        a, b = max(0, a - pad_ms), min(dur_ms, b + pad_ms)
        if segs and a - segs[-1][1] < gap_ms:
            segs[-1] = (segs[-1][0], max(segs[-1][1], b))
        else:
            segs.append((a, b))
    return [s for s in segs if s[1] - s[0] >= min_ms]


def _mask(segs, n, step=10):
    m = np.zeros(n, dtype=bool)
    for a, b in segs:
        m[a // step:b // step] = True
    return m


# ---------- DataHive ----------
def build_datahive(root, silero_path):
    src = root / ".cache/data/datahive"
    meta_src = {}
    with open(src / "metadata.jsonl", encoding="utf-8") as f:
        for line in f:
            if line.strip():
                r = json.loads(line)
                meta_src[Path(r["file_name"]).stem] = r
    sil = Silero(silero_path) if silero_path and Path(silero_path).exists() else None
    for split, ids in DATAHIVE.items():
        out = root / f"testdata/live_{split}"
        for cid in ids:
            x, sr = sf.read(str(src / "preview" / f"{cid}.mp3"), dtype="float32", always_2d=True)
            if x.shape[1] != 2:
                raise RuntimeError(f"{cid}: ожидалось стерео")
            ch = [_resample(x[:, c], sr) for c in range(2)]
            n = min(len(c) for c in ch)
            ch = [c[:n] for c in ch]
            dur = n * 1000 // SR
            raw = [energy_activity(c) for c in ch]
            turns = [finalize_turns(r, dur) for r in raw]
            # обрезка по общей паузе (обе дорожки молчат) не позже DATAHIVE_MAX_S
            cut = dur
            if dur > DATAHIVE_MAX_S * 1000:
                allsegs = sorted(turns[0] + turns[1])
                end_so_far, best = 0, None
                for a, b in allsegs:
                    if a - end_so_far >= 300 and end_so_far <= DATAHIVE_MAX_S * 1000:
                        best = (end_so_far + a) // 2
                    end_so_far = max(end_so_far, b)
                cut = best or DATAHIVE_MAX_S * 1000
            ncut = cut * SR // 1000
            ch = [c[:ncut] for c in ch]
            raw = [[(a, min(b, cut)) for a, b in r if a < cut] for r in raw]
            turns = [[(a, min(b, cut)) for a, b in t if a < cut] for t in turns]
            check = {}
            if sil is not None:
                nfr = cut // 10 + 1
                inter = union = e_only = s_only = 0
                ders, bnds = [], []
                for c in range(2):
                    s_raw = sil.activity(ch[c])
                    me, ms = _mask(raw[c], nfr), _mask(s_raw, nfr)
                    inter += int((me & ms).sum()); union += int((me | ms).sum())
                    e_only += int((me & ~ms).sum()); s_only += int((~me & ms).sum())
                    s_t = finalize_turns(s_raw, cut)
                    rs = [("A", a, b, "x") for a, b in turns[c]]
                    hs = [("A", a, b, "x") for a, b in s_t]
                    bnds.append(boundary_err(rs, hs)["median"])
                    ders.append(der(rs, hs, collar_ms=250)["der"])
                check = {"method": "energy_vs_silero",
                         "frame_iou": round(inter / max(union, 1), 3),
                         "energy_only_share": round(e_only / max(union, 1), 3),
                         "silero_only_share": round(s_only / max(union, 1), 3),
                         "der_per_channel_collar250": [round(float(d), 3) for d in ders],
                         "boundary_median_ms_per_channel": [round(float(b), 0) for b in bnds]}
            segs = [(f"ch{c}", a, b, NO_TEXT) for c in range(2) for a, b in turns[c]]
            transcript, lab = label_segments(segs)
            y = ch[0] + ch[1]
            sp = [sum(b - a for a, b in t) for t in turns]
            m = meta_src.get(cid, {})
            meta = {
                "source": "datahive", "source_id": f"{DATAHIVE_REPO}/preview/{cid}.mp3",
                "license": "CC BY 4.0 (DataHive — Russian Conversations, attribution required)",
                "has_text": False,
                "conditions": {"speakers": len(lab), "quality": "voip_opus", "voices": "live",
                               "dynamics": "overlap", "format": "mp3_48k_stereo_to_wav16k_mono",
                               "origin": "live", "has_text": False},
                "ref_method": "energy VAD per channel (100-1000 Hz, L95-30 dB on / -36 dB off, voicing gate "
                              ">=15% periodic frames, pad 50 ms, merge gaps < 1000 ms, drop < 250 ms); "
                              "speaker = channel; no transcript",
                "ref_check": check,
                "speech_share_by_channel": [round(s / max(cut, 1), 3) for s in sp],
                "source_meta": {k: m.get(k) for k in ("country_from", "country_to", "topic", "duration_seconds",
                                                       "overlap_percent", "speech_balance_percent",
                                                       "turn_taking_gap_ms")},
                "cut_ms": cut,
            }
            if check and max(check["der_per_channel_collar250"]) > 0.25:
                print(f"WARN: {cid}: эталон расходится с Silero (DER {check['der_per_channel_collar250']})")
            write_item(out, f"dh_{cid}", y, transcript, meta)


# ---------- Langswap Dialogs ----------
def _langswap_rows(root):
    src = root / ".cache/data/langswap"
    parts = {}
    with open(src / "metadata.csv", encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f, delimiter="|", quoting=csv.QUOTE_NONE):
            m = re.match(r"wavs/(.+)_(\d+)\.wav$", r["audio_path"])
            if m:
                parts.setdefault(m.group(1), {})[int(m.group(2))] = r
    return parts


def select_langswap_windows(parts, part, w=LANGSWAP_WIN):
    """Окно из w подряд идущих реплик с максимальной «стилизованностью» (для документации выбора)."""
    d = parts[part]
    ks = sorted(d)
    best = None
    for i in range(len(ks) - w + 1):
        if ks[i + w - 1] - ks[i] != w - 1:
            continue
        sc = sum(LANGSWAP_STYLE_W.get(d[k]["emotion"], 0) for k in ks[i:i + w])
        if best is None or sc > best[0]:
            best = (sc, ks[i])
    return best[1]


def _langswap_window(parts, part, k0):
    d = parts[part]
    return [d[k] for k in range(k0, k0 + LANGSWAP_WIN)]


def build_langswap(root):
    src = root / ".cache/data/langswap"
    parts = _langswap_rows(root)
    for split, wins in LANGSWAP.items():
        out = root / f"testdata/live_{split}"
        for wi, (part, k0) in enumerate(wins):
            rows = _langswap_window(parts, part, k0)
            rng = np.random.default_rng(1000 + wi + (0 if split == "dev" else 500))
            placed, t, prev, prev_len = [], 0, None, 0
            for r in rows:
                x, sr = sf.read(str(src / r["audio_path"]), dtype="float32", always_2d=True)
                x = make_synthetic.trim(_resample(x.mean(1), sr))
                if len(x) < SR * 0.2:
                    continue
                spk = r["speaker_id"]
                if not placed:
                    st = int(0.3 * SR)
                elif spk == prev:
                    st = t + int(rng.uniform(0.2, 0.6) * SR)
                elif rng.random() < 0.15:
                    st = max(t - int(rng.uniform(0, 0.10) * min(len(x), prev_len)), placed[-1][1] + 1)
                else:
                    st = t + int(rng.uniform(0.15, 0.9) * SR)
                placed.append((spk, st, x, r))
                t, prev, prev_len = max(t, st + len(x)), spk, len(x)
            y = np.zeros(t + int(0.3 * SR), np.float32)
            for _, st, x, _ in placed:
                y[st:st + len(x)] += x
            segs = [(spk, st * 1000 // SR, (st + len(x)) * 1000 // SR, r["text"].strip()) for spk, st, x, r in placed]
            segs = merge_same_speaker(segs)
            transcript, lab = label_segments(segs)
            spk_names = sorted({LANGSWAP_SPK[p[0]] for p in placed})
            styles = {}
            for *_, r in placed:
                styles[r["emotion"]] = styles.get(r["emotion"], 0) + 1
            voices = "ff_stylized" if "Dima" not in spk_names else "mf_stylized"
            meta = {
                "source": "langswap_dialogs", "source_id": f"{LANGSWAP_REPO} {part} #{k0}..{k0 + LANGSWAP_WIN - 1}",
                "license": "OpenRAIL (Langswap Dialogs, LICENSE.md: use restrictions flow down; cite arXiv:2607.14310)",
                "has_text": True,
                "conditions": {"speakers": len(lab), "quality": "studio", "voices": voices,
                               "dynamics": "turns_overlap", "format": "wav44k_to_wav16k_mono",
                               "origin": "studio_actors", "has_text": True},
                "speakers_src": spk_names, "styles": styles,
                "params": {"pause_s": [0.15, 0.9], "same_spk_pause_s": [0.2, 0.6], "overlap_prob": 0.15,
                           "max_overlap": 0.10, "merge_same_speaker_gap_ms": 1000},
                "utterances_src": [r["audio_path"] for *_, r in placed],
            }
            write_item(out, f"ls_{part}_{k0}", y, transcript, meta)


# ---------- Piper ----------
def build_piper(root):
    src = root / ".cache/data/piper_lines"
    for split, dialogs in PIPER.items():
        out = root / f"testdata/live_{split}"
        pool = make_synthetic.scan_dirs(src / split)
        for di, voices in enumerate(dialogs):
            missing = [v for v in voices if v not in pool]
            if missing:
                raise RuntimeError(f"нет реплик Piper для {missing} в {src / split} (запустите tools/piper_synth.py)")
            a = argparse.Namespace(overlap_prob=0.2, max_overlap=0.15, max_pause=1.2, snr_db=None, noise_files=[])
            rng = np.random.default_rng(300 + di + (0 if split == "dev" else 50))
            y, segs, srcs = make_synthetic.build_dialog(pool, list(voices), 150.0, a, rng)
            # make_synthetic уже нумерует speaker_N по первому появлению
            transcript = segs
            genders = "".join(sorted(PIPER_VOICES[v] for v in voices))
            meta = {
                "source": "piper", "source_id": "rhasspy/piper-voices ru_RU medium: " + ", ".join(voices),
                "license": "phrases: own (testdata/synth_lines_ru.txt); voices: denis/dmitri CC0 data, irina data "
                           "license unknown (RHVoice), ruslan CC BY-NC-SA 4.0 data — internal tests only",
                "has_text": True,
                "conditions": {"speakers": len(voices), "quality": "clean", "voices": "distinct_piper",
                               "dynamics": "overlap", "format": "wav16k_mono", "origin": "synthetic_piper",
                               "has_text": True, "genders": genders},
                "speakers_src": list(voices),
                "params": {"overlap_prob": a.overlap_prob, "max_overlap": a.max_overlap, "max_pause": a.max_pause},
                "utterances_src": [(Path(s).relative_to(root) if Path(s).is_absolute() else Path(s)).as_posix() for s in srcs],
            }
            write_item(out, f"piper_{len(voices)}spk_{'_'.join(voices)}", y, transcript, meta)


# ---------- ESC-50 вставки ----------
def esc50_selection(root):
    rows = list(csv.DictReader(open(root / ".cache/data/esc50/esc50.csv", encoding="utf-8")))
    sel = []
    for cat, n in ESC50_N.items():
        names = sorted(r["filename"] for r in rows if r["category"] == cat)
        sel += [(cat, f) for f in names[::5][:n]]
    return sel


def build_meow(root):
    clips = esc50_selection(root)
    rng = np.random.default_rng(77)
    order = list(rng.permutation(len(clips)))
    per = len(clips) // len(MEOW_BASES)
    out = root / "testdata/live_dev"
    for bi, base in enumerate(MEOW_BASES):
        bdir = root / "testdata/dev"
        y, sr = sf.read(str(bdir / f"{base}.wav"), dtype="float32", always_2d=True)
        y = _resample(y.mean(1), sr)
        ref = load_segments(bdir / f"{base}.json")
        bmeta = json.loads((bdir / f"{base}.meta.json").read_text(encoding="utf-8"))
        speech = np.concatenate([y[s * SR // 1000:e * SR // 1000] for _, s, e, _ in ref])
        speech_rms = float(np.sqrt((speech ** 2).mean() + 1e-12))
        # паузы, где никто не говорит: (позиция вставки в мс исходника)
        gaps, end_so_far = [], 0
        for _, s, e, _ in sorted(ref, key=lambda r: r[1]):
            if end_so_far and s - end_so_far >= 100:
                gaps.append((end_so_far + s) // 2)
            end_so_far = max(end_so_far, e)
        my = [clips[i] for i in order[bi * per:(bi + 1) * per]]
        step = max(len(gaps) // (len(my) + 1), 1)
        at = [gaps[min((k + 1) * step, len(gaps) - 1)] for k in range(len(my))]
        at = sorted(set(at))
        pieces, inserted, shift, last = [], [], 0, 0
        for pos_ms, (cat, fn) in zip(at, my):
            c, csr = sf.read(str(root / ".cache/data/esc50/audio" / fn), dtype="float32", always_2d=True)
            c = make_synthetic.trim(_resample(c.mean(1), csr), thr_db=-30.0)[: 3 * SR]
            c = c[: len(c) - len(c) % (SR // 1000)]  # длина кратна 1 мс — сдвиг эталона без округления
            if len(c) < SR // 10:
                continue
            gain_db = float(rng.uniform(-6, 0))
            c_rms = float(np.sqrt((c ** 2).mean() + 1e-12))
            c = c * (speech_rms / c_rms) * 10 ** (gain_db / 20)
            pad = np.zeros(int(0.3 * SR), np.float32)
            ins = np.concatenate([pad, c, pad])
            p = pos_ms * SR // 1000
            pieces += [y[last:p], ins]
            last = p
            shift += len(ins) * 1000 // SR
            inserted.append({"at_ms": pos_ms + shift - len(ins) * 1000 // SR + 300, "len_ms": len(c) * 1000 // SR,
                             "category": cat, "clip": f"ESC-50/audio/{fn}", "gain_db_vs_speech": round(gain_db, 1),
                             "_src_pos_ms": pos_ms, "_shift_after_ms": shift})
        pieces.append(y[last:])
        z = np.concatenate(pieces)
        # сдвиг реплик эталона на суммарную длину вставок перед ними
        offs = [(i.pop("_src_pos_ms"), i.pop("_shift_after_ms")) for i in inserted]

        def sh(t):
            d = 0
            for pos, acc_ in offs:
                if t >= pos:
                    d = acc_
            return t + d
        segs = [(spk, sh(s), sh(e), txt) for spk, s, e, txt in ref]
        transcript, _ = label_segments(segs)
        cats = sorted({i["category"] for i in inserted})
        cond = dict(bmeta.get("conditions", {}))
        cond.update(origin="synthetic_esc50", nonspeech="+".join(cats), has_text=True)
        meta = {
            "source": "testdata/dev + ESC-50", "source_id": f"testdata/dev/{base}",
            "license": "base: as testdata/dev; ESC-50 clips CC BY-NC 3.0 (K. Piczak) — internal tests only",
            "has_text": True, "conditions": cond, "inserted": inserted,
            "note": "inserted non-speech must not produce replicas or new speakers (AGENTS.md 2.8)",
        }
        write_item(out, f"meow_{base}", z, transcript, meta)


# ---------- fetch ----------
def fetch(root):
    d = root / ".cache/data"
    total = 0
    # DataHive
    for f in ("README.md", "preview/metadata.jsonl"):
        total += _download(f"{HF}/datasets/{DATAHIVE_REPO}/resolve/main/{f}", d / "datahive" / f.split("/")[-1])
    for ids in DATAHIVE.values():
        for cid in ids:
            total += _download(f"{HF}/datasets/{DATAHIVE_REPO}/resolve/main/preview/{cid}.mp3",
                               d / "datahive/preview" / f"{cid}.mp3")
    # Langswap
    for f in ("README.md", "LICENSE.md", "metadata.csv"):
        total += _download(f"{HF}/datasets/{LANGSWAP_REPO}/resolve/main/{f}", d / "langswap" / f)
    parts = _langswap_rows(root)
    for wins in LANGSWAP.values():
        for part, k0 in wins:
            for r in _langswap_window(parts, part, k0):
                total += _download(f"{HF}/datasets/{LANGSWAP_REPO}/resolve/main/{r['audio_path']}",
                                   d / "langswap" / r["audio_path"])
    # Piper
    for v in PIPER_VOICES:
        for f in ("MODEL_CARD", f"ru_RU-{v}-medium.onnx", f"ru_RU-{v}-medium.onnx.json"):
            total += _download(f"{HF}/rhasspy/piper-voices/resolve/main/ru/ru_RU/{v}/medium/{f}", d / "piper" / v / f)
    # ESC-50
    for f in ("meta/esc50.csv", "LICENSE"):
        total += _download(f"{ESC50_RAW}/{f}", d / "esc50" / f.split("/")[-1])
    for _, fn in esc50_selection(root):
        total += _download(f"{ESC50_RAW}/audio/{fn}", d / "esc50/audio" / fn)
    print(f"fetch: скачано {total / 1e6:.1f} МБ (уже существующие файлы пропущены)")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("step", choices=["fetch", "datahive", "langswap", "piper", "meow", "all", "windows"])
    ap.add_argument("--root", default=".")
    ap.add_argument("--silero", default="src/android/app/src/main/assets/models/vad/silero_vad.onnx",
                    help="Silero VAD для оценки погрешности эталона DataHive (необязательно)")
    a = ap.parse_args(argv)
    utf8_stdout()
    root = Path(a.root).resolve()
    if a.step == "fetch":
        fetch(root)
    if a.step == "windows":  # показать, как выбраны окна Langswap
        parts = _langswap_rows(root)
        for wins in LANGSWAP.values():
            for part, k0 in wins:
                print(part, k0, "auto:", select_langswap_windows(parts, part))
    if a.step in ("datahive", "all"):
        build_datahive(root, root / a.silero)
    if a.step in ("langswap", "all"):
        build_langswap(root)
    if a.step in ("piper", "all"):
        build_piper(root)
    if a.step in ("meow", "all"):
        build_meow(root)
    return 0


if __name__ == "__main__":
    sys.exit(main())
