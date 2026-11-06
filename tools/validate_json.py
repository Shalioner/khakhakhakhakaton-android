"""Проверка JSON-выхода по контракту (AGENTS.md, раздел 2.4).

    python tools/validate_json.py out/file.json [--duration-ms 61234]
    python tools/validate_json.py out/file.json --audio testdata/dev/file.wav

Код выхода 0 — ок, 1 — нарушения контракта (печатаются построчно).
Модуль также даёт load_segments() для остальных tools/*.py.
"""
import argparse
import json
import re
import sys

ITEM_KEYS = {"speaker_id", "start_ms", "end_ms", "text"}
SPK_RE = re.compile(r"^speaker_([1-9][0-9]*)$")


def _is_int(v):
    return isinstance(v, int) and not isinstance(v, bool)


def validate(obj, duration_ms=None):
    """Возвращает список строк-ошибок (пустой = валидно)."""
    errs = []
    if not isinstance(obj, dict):
        return ["root: ожидается объект"]
    if set(obj.keys()) != {"transcript"}:
        errs.append(f"root: ключи должны быть ровно ['transcript'], есть {sorted(obj.keys())}")
    tr = obj.get("transcript")
    if not isinstance(tr, list):
        return errs + ["transcript: ожидается массив"]
    prev_start = None
    seen = {}  # speaker_id -> порядковый номер появления
    for i, it in enumerate(tr):
        p = f"transcript[{i}]"
        if not isinstance(it, dict):
            errs.append(f"{p}: ожидается объект")
            continue
        keys = set(it.keys())
        if keys != ITEM_KEYS:
            extra, miss = sorted(keys - ITEM_KEYS), sorted(ITEM_KEYS - keys)
            errs.append(f"{p}: лишние поля {extra}, нет полей {miss}")
        s, e, spk, txt = it.get("start_ms"), it.get("end_ms"), it.get("speaker_id"), it.get("text")
        if not (_is_int(s) and _is_int(e)):
            errs.append(f"{p}: start_ms/end_ms должны быть целыми")
        else:
            if not (0 <= s < e):
                errs.append(f"{p}: нарушено 0 <= start_ms < end_ms ({s}, {e})")
            if duration_ms is not None and e > duration_ms:
                errs.append(f"{p}: end_ms {e} > duration_ms {duration_ms}")
            if prev_start is not None and s < prev_start:
                errs.append(f"{p}: не отсортировано по start_ms ({s} < {prev_start})")
            prev_start = s
        if not isinstance(txt, str) or not txt.strip():
            errs.append(f"{p}: text пустой или не строка")
        if not isinstance(spk, str) or not SPK_RE.match(spk):
            errs.append(f"{p}: speaker_id {spk!r} не вида speaker_N")
        elif spk not in seen:
            expected = f"speaker_{len(seen) + 1}"
            if spk != expected:
                errs.append(f"{p}: новый спикер {spk}, ожидался {expected} (нумерация по первому появлению)")
            seen[spk] = len(seen) + 1
    return errs


def utf8_stdout():
    """Windows-консоль: печатать кириллицу в UTF-8."""
    for st in (sys.stdout, sys.stderr):
        try:
            st.reconfigure(encoding="utf-8")
        except (AttributeError, ValueError):
            pass


def load_segments(path):
    """[(speaker_id, start_ms, end_ms, text)] без проверки контракта (терпимо к мусору)."""
    with open(path, encoding="utf-8") as f:
        obj = json.load(f)
    out = []
    for it in obj.get("transcript", []) if isinstance(obj, dict) else []:
        try:
            out.append((str(it["speaker_id"]), int(it["start_ms"]), int(it["end_ms"]), str(it.get("text", ""))))
        except (KeyError, TypeError, ValueError):
            continue
    return out


def audio_duration_ms(path):
    import soundfile as sf
    info = sf.info(path)
    return int(info.frames * 1000 // info.samplerate)


def main(argv=None):
    utf8_stdout()
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("json_path")
    ap.add_argument("--duration-ms", type=int)
    ap.add_argument("--audio", help="взять duration из аудиофайла")
    a = ap.parse_args(argv)
    dur = a.duration_ms
    if dur is None and a.audio:
        dur = audio_duration_ms(a.audio)
    try:
        with open(a.json_path, encoding="utf-8") as f:
            obj = json.load(f)
    except (OSError, ValueError) as ex:
        print(f"ERROR: не читается JSON: {ex}")
        return 1
    errs = validate(obj, dur)
    for e in errs:
        print("ERROR:", e)
    if not errs:
        print(f"OK: {len(obj['transcript'])} реплик")
    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main())
