"""Сводный отчёт метрик: WER, cpWER, DER, ошибка границ, RTF, число спикеров.

    python tools/report.py testdata/dev out/dev [--timings out/dev/timings.json]
                           [--csv report.csv] [--md report.md] [--collar-ms 250]

Эталоны: все <ref_dir>/**/<name>.json (рекурсивно, кроме *.meta.json и timings.json);
гипотеза: <hyp_dir>/<name>.json (или с тем же относительным путём).
Длительность для проверки контракта: <name>.meta.json:"duration_ms" или аудиофайл
<name>.{wav,flac,mp3,ogg,opus,m4a} рядом с эталоном.
Ячейки матрицы: <name>.meta.json:"conditions" {ось: значение} — по каждой паре
ось=значение печатается среднее (см. testdata/README.md).
Эталоны без текста (<name>.meta.json: "has_text": false, верхний уровень или conditions) —
например, DataHive с эталоном по речевой активности каналов: WER/cpWER не считаются ("—" и не входят
в среднее), DER/границы/число спикеров — как обычно. Без поля has_text эталон считается текстовым.
timings.json: {"<name>": {"proc_ms": 1234, "audio_ms": 60000}} или {"<name>": 0.12} (готовый RTF).
Отсутствующая/битая гипотеза = худший случай (WER=DER=100%), контракт = FAIL.
Код выхода 1, если хоть одна гипотеза отсутствует или нарушает контракт.
"""
import argparse
import csv
import json
import math
import os
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from boundary_err import boundary_err  # noqa: E402
from der import der  # noqa: E402
from validate_json import audio_duration_ms, load_segments, utf8_stdout, validate  # noqa: E402
from wer import cpwer, wer  # noqa: E402

AUDIO_EXT = (".wav", ".flac", ".mp3", ".ogg", ".opus", ".m4a")
COLS = ["file", "wer", "cpwer", "der", "miss", "fa", "conf", "bnd_mean_ms", "bnd_median_ms",
        "rtf", "spk_ref", "spk_hyp", "contract"]
METRICS = ["wer", "cpwer", "der", "bnd_mean_ms", "bnd_median_ms", "rtf"]
HIGHER_IS_WORSE = set(METRICS)


def _refs(ref_dir):
    for p in sorted(Path(ref_dir).rglob("*.json")):
        if p.name.endswith(".meta.json") or p.name == "timings.json":
            continue
        yield p


def _meta(ref):
    m = ref.with_name(ref.stem + ".meta.json")
    if m.exists():
        with open(m, encoding="utf-8") as f:
            return json.load(f)
    return {}


def _duration(ref, meta):
    if "duration_ms" in meta:
        return int(meta["duration_ms"])
    for ext in AUDIO_EXT:
        a = ref.with_suffix(ext)
        if a.exists():
            try:
                return audio_duration_ms(str(a))
            except Exception:
                pass
    return None


def has_text(meta):
    """False, если эталон без текста (meta "has_text": false на верхнем уровне или в conditions)."""
    v = meta.get("has_text", meta.get("conditions", {}).get("has_text", True))
    return v not in (False, "false", "False", 0)


def _rtf(t, dur):
    if t is None:
        return None
    if isinstance(t, (int, float)):
        return float(t)
    if "proc_ms" not in t:  # {"error": ...} от bench
        return None
    audio = t.get("audio_ms", dur)
    return t["proc_ms"] / audio if audio else None


def evaluate(ref_dir, hyp_dir, timings=None, collar_ms=250):
    timings = timings or {}
    rows = []
    for ref in _refs(ref_dir):
        name, rel = ref.stem, ref.relative_to(ref_dir)
        meta = _meta(ref)
        dur = _duration(ref, meta)
        r = load_segments(ref)
        text = has_text(meta)
        row = {"file": str(rel.with_suffix("")).replace("\\", "/"),
               "spk_ref": len({s[0] for s in r}), "conditions": meta.get("conditions", {}), "has_text": text}
        hp = next((p for p in (Path(hyp_dir) / rel, Path(hyp_dir) / f"{name}.json") if p.exists()), None)
        obj = None
        if hp is not None:
            try:
                with open(hp, encoding="utf-8") as f:
                    obj = json.load(f)
            except ValueError:
                obj = None
        if obj is None:
            row.update(wer=1.0 if text else None, cpwer=1.0 if text else None, der=1.0, miss=1.0, fa=0.0,
                       conf=0.0, bnd_mean_ms=None, bnd_median_ms=None, rtf=None, spk_hyp=0,
                       contract="MISSING" if hp is None else "BAD_JSON")
            rows.append(row)
            continue
        errs = validate(obj, dur)
        h = load_segments(hp)
        d = der(r, h, collar_ms)
        b = boundary_err(r, h)
        row.update(wer=wer(r, h)[0] if text else None, cpwer=cpwer(r, h)[0] if text else None,
                   der=d["der"], miss=d["miss"], fa=d["fa"], conf=d["conf"], bnd_mean_ms=b["mean"], bnd_median_ms=b["median"],
                   rtf=_rtf(timings.get(name), dur), spk_hyp=len({s[0] for s in h}),
                   contract="OK" if not errs else f"FAIL({len(errs)}): {errs[0]}")
        rows.append(row)
    return rows


def _num(v):
    return v is not None and not (isinstance(v, float) and math.isnan(v))


def _agg(rows):
    mean, worst = {}, {}
    for m in METRICS:
        vals = [(r[m], r["file"]) for r in rows if _num(r.get(m))]
        if vals:
            mean[m] = sum(v for v, _ in vals) / len(vals)
            worst[m] = max(vals)
    return mean, worst


def _fmt(m, v):
    if not _num(v):
        return "—"
    if m in ("wer", "cpwer", "der", "miss", "fa", "conf"):
        return f"{v * 100:.1f}"
    if m == "rtf":
        return f"{v:.3f}"
    if m.startswith("bnd"):
        return f"{v:.0f}"
    return str(v)


def to_markdown(rows):
    out = ["| " + " | ".join(COLS) + " |", "|" + "---|" * len(COLS)]
    for r in rows:
        out.append("| " + " | ".join(_fmt(c, r.get(c)) if c in METRICS or c in ("miss", "fa", "conf")
                                    else str(r.get(c, "")) for c in COLS) + " |")
    mean, worst = _agg(rows)
    ok = sum(r["contract"] == "OK" for r in rows)
    out.append("| **mean** | " + " | ".join(_fmt(c, mean.get(c)) if c in METRICS else
                                            (f"{ok}/{len(rows)} OK" if c == "contract" else "")
                                            for c in COLS[1:]) + " |")
    out.append("| **worst** | " + " | ".join(
        (f"{_fmt(c, worst[c][0])} ({worst[c][1]})" if c in worst else "—") if c in METRICS else ""
        for c in COLS[1:]) + " |")
    # ячейки матрицы условий
    cells = defaultdict(list)
    for r in rows:
        for k, v in r["conditions"].items():
            cells[(k, str(v))].append(r)
    if cells:
        out += ["", "### По ячейкам матрицы (среднее / худшее)", "",
                "| ось | значение | n | WER | cpWER | DER | bnd_mean_ms | RTF |", "|---|---|---|---|---|---|---|---|"]
        for (k, v), rs in sorted(cells.items()):
            mn, ws = _agg(rs)
            cell = [f"{_fmt(m, mn.get(m))} / {_fmt(m, ws[m][0]) if m in ws else '—'}"
                    for m in ("wer", "cpwer", "der", "bnd_mean_ms", "rtf")]
            out.append(f"| {k} | {v} | {len(rs)} | " + " | ".join(cell) + " |")
    return "\n".join(out)


def write_csv(rows, path):
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(COLS + ["conditions"])
        for r in rows:
            w.writerow([r.get(c) if _num(r.get(c)) else "" for c in COLS] +
                       [json.dumps(r["conditions"], ensure_ascii=False)])


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("ref_dir")
    ap.add_argument("hyp_dir")
    ap.add_argument("--timings")
    ap.add_argument("--csv", help="путь CSV (по умолчанию <hyp_dir>/report.csv)")
    ap.add_argument("--md", help="также сохранить markdown в файл")
    ap.add_argument("--collar-ms", type=int, default=250)
    a = ap.parse_args(argv)
    utf8_stdout()
    timings = {}
    tp = a.timings or (os.path.join(a.hyp_dir, "timings.json")
                       if os.path.exists(os.path.join(a.hyp_dir, "timings.json")) else None)
    if tp:
        with open(tp, encoding="utf-8") as f:
            timings = json.load(f)
    rows = evaluate(a.ref_dir, a.hyp_dir, timings, a.collar_ms)
    if not rows:
        print(f"Нет эталонов *.json в {a.ref_dir}")
        return 1
    md = to_markdown(rows)
    print(md)
    csv_path = a.csv or os.path.join(a.hyp_dir, "report.csv")
    write_csv(rows, csv_path)
    print(f"\nCSV: {csv_path}")
    if a.md:
        Path(a.md).write_text(md + "\n", encoding="utf-8")
    return 0 if all(r["contract"] == "OK" for r in rows) else 1


if __name__ == "__main__":
    sys.exit(main())
