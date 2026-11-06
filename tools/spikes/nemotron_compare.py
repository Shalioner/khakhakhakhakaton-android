"""Сравнение DER: текущая диаризация (pyannote-seg-3.0 + CAM++ + FastClustering) vs Nemotron-3-Diarization.

    python -I tools/spikes/nemotron_compare.py --split dev --ref testdata/dev \
        --sys "cur_e2e=csv:artifacts/test-results/dev_report.csv" \
        --sys "cur_turns=cp:out/cp_dump_dev" --sys "nem_turns=json:out/nemotron/dev" \
        --sys "nem_e2e=json:out/nemotron/sweep_dev/base" [--md out.md] [--collar-ms 250]

Источник системы:
  csv:<report.csv>  — готовые колонки der/miss/fa/conf/spk_hyp из tools/report.py;
  cp:<dir>          — сырые турны диаризатора (строки «T idx start end») из дампа bench --cp-dump;
  json:<dir>        — <name>.json в формате контракта (реплики или турны с перекрытиями).
DER — tools/der.py (покадрово 10 мс, collar 250 мс, перекрытия учитываются, венгерское сопоставление).
Печатает построчную таблицу и средние/худшие по осям voices, quality, speakers.
"""
import argparse
import csv
import json
import os
import sys
from collections import defaultdict
from pathlib import Path

TOOLS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(TOOLS))
from der import der  # noqa: E402
from validate_json import load_segments  # noqa: E402


def load_sys(spec, name):
    kind, path = spec.split(":", 1)
    p = Path(path)
    if kind == "csv":
        return ("csv", {r["file"]: r for r in csv.DictReader(open(p, encoding="utf-8"))})
    if kind == "cp":
        f = p / f"{name}.cp.txt"
        if not f.exists():
            return None
        segs = []
        for ln in f.read_text(encoding="utf-8").splitlines():
            if ln.startswith("T "):
                _, k, a, b = ln.split()
                segs.append((f"s{k}", int(a), int(b), ""))
        return segs
    if kind == "json":
        f = p / f"{name}.json"
        return load_segments(f) if f.exists() else None
    raise ValueError(spec)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--split", required=True)
    ap.add_argument("--ref", required=True)
    ap.add_argument("--sys", action="append", required=True, help="label=kind:path")
    ap.add_argument("--collar-ms", type=int, default=250)
    ap.add_argument("--md")
    ap.add_argument("--json")
    a = ap.parse_args(argv)
    systems = [s.split("=", 1) for s in a.sys]
    csvs = {lab: load_sys(spec, None)[1] for lab, spec in systems if spec.startswith("csv:")}
    rows = []
    for ref in sorted(Path(a.ref).glob("*.json")):
        if ref.name.endswith(".meta.json"):
            continue
        name = ref.stem
        meta = json.loads(ref.with_name(name + ".meta.json").read_text(encoding="utf-8"))
        cond = meta.get("conditions", {})
        r = load_segments(ref)
        row = {"file": name, "voices": cond.get("voices"), "quality": cond.get("quality"),
               "speakers": cond.get("speakers"), "spk_ref": len({s[0] for s in r})}
        for lab, spec in systems:
            if spec.startswith("csv:"):
                c = csvs[lab].get(name)
                if c is None:
                    continue
                row[lab] = {k: float(c[k]) for k in ("der", "miss", "fa", "conf")}
                row[lab]["spk"] = int(c["spk_hyp"])
                continue
            h = load_sys(spec, name)
            if h is None:
                continue
            d = der(r, h, a.collar_ms)
            row[lab] = {k: d[k] for k in ("der", "miss", "fa", "conf")}
            row[lab]["spk"] = len({s[0] for s in h})
        rows.append(row)

    labs = [lab for lab, _ in systems]
    out = []
    hdr = "| файл | voices | quality | spk ref | " + " | ".join(f"{lab} DER (miss/fa/conf) · spk" for lab in labs) + " |"
    out.append(hdr)
    out.append("|" + "---|" * (4 + len(labs)))
    for row in rows:
        cells = []
        for lab in labs:
            v = row.get(lab)
            cells.append("—" if v is None else
                         f"{v['der'] * 100:.1f} ({v['miss'] * 100:.1f}/{v['fa'] * 100:.1f}/{v['conf'] * 100:.1f}) · {v['spk']}")
        out.append(f"| {row['file']} | {row['voices']} | {row['quality']} | {row['spk_ref']} | " + " | ".join(cells) + " |")

    def agg(sel):
        res = []
        for lab in labs:
            vals = [(r[lab]["der"], r) for r in sel if lab in r]
            if not vals:
                res.append("—")
                continue
            mean = sum(v for v, _ in vals) / len(vals)
            worst = max(v for v, _ in vals)
            spk_ok = sum(1 for _, r in vals if r[lab]["spk"] == r["spk_ref"])
            res.append(f"{mean * 100:.1f} / {worst * 100:.1f} · spk верно {spk_ok}/{len(vals)}")
        return res

    out.append("")
    out.append("| группа | n | " + " | ".join(f"{lab} DER ср / худш" for lab in labs) + " |")
    out.append("|" + "---|" * (2 + len(labs)))
    out.append(f"| все | {len(rows)} | " + " | ".join(agg(rows)) + " |")
    for axis in ("voices", "quality", "speakers"):
        groups = defaultdict(list)
        for r in rows:
            groups[r[axis]].append(r)
        for k in sorted(groups, key=str):
            out.append(f"| {axis}={k} | {len(groups[k])} | " + " | ".join(agg(groups[k])) + " |")
    text = "\n".join(out)
    sys.stdout.reconfigure(encoding="utf-8")
    print(f"## {a.split}\n\n{text}\n")
    if a.md:
        Path(a.md).write_text(f"## {a.split}\n\n{text}\n", encoding="utf-8")
    if a.json:
        Path(a.json).write_text(json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
