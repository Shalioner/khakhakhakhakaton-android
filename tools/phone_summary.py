"""Сводка прогона BenchActivity на телефоне (вызывается из tools/phone_bench.sh).

    python tools/phone_summary.py out/phone/<run> --write-timings   # timings_report.json для report.py
    python tools/phone_summary.py out/phone/<run>                   # сводка → stdout + <run>/summary.md

Ожидаемое содержимое <run>/ (создаёт phone_bench.sh):
  out/*.json, out/timings.json   — выход BenchActivity (adb pull)
  logcat.txt                     — logcat за время прогона (строки «bench "<name>":{...}», крэши, LMK)
  meminfo.csv                    — epoch,pid,pss_kb,rss_kb,native_pss_kb,java_pss_kb,vmrss_kb,vmhwm_kb
  thermal.csv                    — epoch,battery_c,thermal_status,cpu_max_c,skin_c,cpu_mhz
  events.csv                     — epoch,event,name (START / FILE <name> / DONE / CRASH)
  manifest.json                  — от phone_prepare.py (kind: main/edge/fmt/long, expect)
  device.txt                     — модель, Android, RAM

Формат timings BenchActivity ({"audio_ms","total_ms","rtf",...}) report.py не понимает (ждёт proc_ms),
поэтому --write-timings пересобирает его в {"<name>": {"proc_ms": total_ms, "audio_ms": audio_ms}}.
Источник таймингов — строки «bench» в logcat (переживают крэш посреди прогона) + out/timings.json.
Код выхода 1 — есть блокеры (крэш/ANR/LMK, нет выхода для файла, нарушение контракта, реплики на неречи).
"""
import argparse
import csv
import json
import os
import re
import sys
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from validate_json import load_segments, utf8_stdout, validate  # noqa: E402
from wer import wer  # noqa: E402

PKG = "ru.neurosteno.app"
BENCH_RE = re.compile(r'NeuroSteno\s*:\s*bench "(?P<name>[^"]+)":(?P<js>\{.*\})\s*$')
ISSUES = [
    ("crash", re.compile(r"FATAL EXCEPTION|Fatal signal \d+|>>> " + re.escape(PKG) + r" <<<")),
    ("anr", re.compile(r"ANR in " + re.escape(PKG))),
    ("lmk/kill", re.compile(r"(lowmemorykiller|lmkd).*" + re.escape(PKG) + r"|Kill(ing)? .*" + re.escape(PKG)
                            + r"|Process " + re.escape(PKG) + r" .*has died")),
    ("oom", re.compile(r"OutOfMemoryError|Недостаточно памяти")),
    ("app_error", re.compile(r"\sE NeuroSteno\s*:")),
    ("llm", re.compile(r"NeuroSteno\s*:\s*llm: ")),
]
STAGES = ["decode", "vad", "asr", "diarization", "align", "llm"]


def read_json(p):
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def read_csv(p):
    if not p.exists():
        return []
    with open(p, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def bench_timings(run):
    """{name: dict} из logcat «bench» + out/timings.json (последнее значение побеждает)."""
    res = {}
    lc = run / "logcat.txt"
    if lc.exists():
        for line in lc.read_text(encoding="utf-8", errors="replace").splitlines():
            m = BENCH_RE.search(line)
            if m:
                try:
                    res[m["name"]] = json.loads(m["js"])
                except ValueError:
                    res[m["name"]] = {"error": f"unparsed: {m['js'][:120]}"}
    tj = run / "out" / "timings.json"
    if tj.exists():
        try:
            for k, v in read_json(tj).items():
                res.setdefault(k, v)
        except ValueError as e:
            print(f"ВНИМАНИЕ: out/timings.json не читается ({e}) — беру тайминги из logcat")
    return res


def write_timings(run):
    t = bench_timings(run)
    rep = {k: {"proc_ms": v["total_ms"], "audio_ms": v.get("audio_ms")} for k, v in t.items()
           if isinstance(v, dict) and "total_ms" in v}
    with open(run / "bench_timings.json", "w", encoding="utf-8") as f:
        json.dump(t, f, ensure_ascii=False, indent=1)
    with open(run / "timings_report.json", "w", encoding="utf-8") as f:
        json.dump(rep, f, ensure_ascii=False, indent=1)
    print(f"timings: {len(t)} записей ({len(rep)} с total_ms) → {run / 'timings_report.json'}")
    return 0


def file_windows(events):
    """[(name, t0, t1)] по events.csv: файл закончился в момент появления его строки bench."""
    out, prev = [], None
    for e in events:
        t = num(e.get("epoch"))
        if e.get("event") == "START":
            prev = t
        elif e.get("event") == "FILE" and t is not None:
            out.append((e.get("name", ""), prev if prev is not None else t, t))
            prev = t
    return out


def mb(kb):
    return None if kb is None else kb / 1024


def fmt(v, spec="{:.0f}", dash="—"):
    return dash if v is None else spec.format(v)


def main(argv=None):
    utf8_stdout()
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("run_dir")
    ap.add_argument("--write-timings", action="store_true")
    ap.add_argument("--rtf-pass", type=float, default=0.5)
    ap.add_argument("--rtf-fail", type=float, default=1.0)
    ap.add_argument("--pss-pass-mb", type=float, default=1500)
    ap.add_argument("--pss-fail-mb", type=float, default=2000)
    a = ap.parse_args(argv)
    run = Path(a.run_dir)
    if a.write_timings:
        return write_timings(run)

    manifest = read_json(run / "manifest.json") if (run / "manifest.json").exists() else {}
    timings = bench_timings(run)
    mem = read_csv(run / "meminfo.csv")
    therm = read_csv(run / "thermal.csv")
    events = read_csv(run / "events.csv")
    windows = {n: (t0, t1) for n, t0, t1 in file_windows(events)}
    outd = run / "out"
    lines, blockers, warns = [], [], []

    def p(s=""):
        lines.append(s)

    dev = (run / "device.txt").read_text(encoding="utf-8", errors="replace") if (run / "device.txt").exists() else ""
    p(f"# Прогон на телефоне: {run.name}")
    p()
    for key in ("model", "android", "sdk", "soc", "ram_total", "heapgrowthlimit", "heapsize", "airplane", "cpus"):
        m = re.search(rf"^{key}=(.*)$", dev, re.M)
        if m:
            p(f"- {key}: {m.group(1).strip()}")
    p()

    names = list(manifest) or sorted(timings)
    for n in timings:
        if n not in names:
            names.append(n)
    hdr = ["file", "kind", "audio_s", "proc_s", "RTF"] + [s + "_s" for s in STAGES] + \
          ["natheap_MB", "PSS_MB", "RSS_MB", "utts", "spk", "contract", "note"]
    p("| " + " | ".join(hdr) + " |")
    p("|" + "---|" * len(hdr))
    main_rtf, all_pss = [], []
    for n in names:
        info = manifest.get(n, {})
        kind = info.get("kind", "?")
        t = timings.get(n)
        note, contract, utts, spk = "", "—", None, None
        jp = outd / f"{n}.json"
        if t is None:
            note = "НЕТ строки bench (не обработан / крэш)"
            blockers.append(f"{n}: нет результата")
        elif "error" in t:
            note = f"error: {str(t['error'])[:90]}"
            if info.get("expect") == "error_or_empty":
                note = "ок (ожидаемая ошибка): " + note
            else:
                blockers.append(f"{n}: {t['error']}")
        if jp.exists():
            try:
                obj = read_json(jp)
                errs = validate(obj, (t or {}).get("audio_ms") if isinstance(t, dict) else None)
                tr = obj.get("transcript", []) if isinstance(obj, dict) else []
                utts, spk = len(tr), len({u.get("speaker_id") for u in tr})
                contract = "OK" if not errs else f"FAIL({len(errs)})"
                if errs:
                    blockers.append(f"{n}: контракт: {errs[0]}")
            except ValueError as e:
                contract = "BAD_JSON"
                blockers.append(f"{n}: битый JSON ({e})")
        elif t is not None and "error" not in t:
            contract = "MISSING"
            blockers.append(f"{n}: есть тайминг, но нет out/{n}.json")
        exp = info.get("expect")
        if exp == "empty" and utts:
            note = (note + "; " if note else "") + f"НЕРЕЧЬ дала {utts} реплик"
            blockers.append(f"{n}: неречевой файл дал {utts} реплик (правило 2.8)")
        if exp == "nonempty" and utts == 0:
            note = (note + "; " if note else "") + "0 реплик на речи"
            warns.append(f"{n}: 0 реплик, ожидалась речь")
        if exp == "error_or_empty" and t is not None and "error" not in t and utts:
            warns.append(f"{n}: битый файл дал {utts} реплик")
        # память в окне файла
        pss = rss = None
        if n in windows:
            t0, t1 = windows[n]
            ws = [r for r in mem if num(r.get("epoch")) is not None and t0 <= num(r["epoch"]) <= t1 + 1]
            pv = [num(r.get("pss_kb")) for r in ws if num(r.get("pss_kb"))]
            rv = [v for r in ws for v in (num(r.get("rss_kb")), num(r.get("vmrss_kb"))) if v]
            pss = mb(max(pv)) if pv else None
            rss = mb(max(rv)) if rv else None
        if pss:
            all_pss.append((pss, n))
        audio = num((t or {}).get("audio_ms")) if isinstance(t, dict) else None
        proc = num((t or {}).get("total_ms")) if isinstance(t, dict) else None
        rtf = num((t or {}).get("rtf")) if isinstance(t, dict) else None
        if rtf is not None and kind in ("main", "long", "fmt"):
            main_rtf.append((rtf, n, kind))
        st = [fmt(num((t or {}).get(f"{s}_ms")) / 1000 if isinstance(t, dict) and num(t.get(f"{s}_ms")) is not None else None,
                  "{:.1f}") for s in STAGES]
        row = [n, kind, fmt(audio / 1000 if audio else None, "{:.1f}"), fmt(proc / 1000 if proc else None, "{:.1f}"),
               fmt(rtf, "{:.3f}")] + st + [fmt(num((t or {}).get("native_heap_mb")) if isinstance(t, dict) else None),
                                           fmt(pss), fmt(rss), fmt(utts), fmt(spk), contract, note]
        p("| " + " | ".join(row) + " |")
    p()

    # форматы: WER варианта vs эталон исходника и vs гипотеза по исходному wav
    fmts = [(n, i) for n, i in manifest.items() if i.get("kind") == "fmt"]
    if fmts:
        p("## Форматы (один исходник)")
        p()
        p("| вариант | WER vs эталон, % | WER vs выход по wav, % |")
        p("|---|---|---|")
        for n, i in fmts:
            src = i.get("fmt_of")
            hyp, base = outd / f"{n}.json", outd / f"{src}.json"
            w1 = w2 = None
            refp = next((q for q in (run / "refs_fmt" / f"{n}.json", Path("testdata/test") / f"{src}.json") if q.exists()), None)
            if hyp.exists() and refp:
                w1 = wer(load_segments(refp), load_segments(hyp))[0] * 100
            if hyp.exists() and base.exists():
                w2 = wer(load_segments(base), load_segments(hyp))[0] * 100
            p(f"| {i.get('variant', n)} | {fmt(w1, '{:.1f}')} | {fmt(w2, '{:.1f}')} |")
            if w2 is not None and w2 > 5:
                warns.append(f"{n}: расхождение с wav-версией {w2:.1f}% WER")
        p()

    # агрегаты
    p("## Итог")
    p()
    mr = [r for r, _, k in main_rtf if k == "main"]
    if mr:
        worst = max((r, n) for r, n, k in main_rtf if k == "main")
        mean = sum(mr) / len(mr)
        verdict = "PASS" if worst[0] <= a.rtf_pass else ("WARN" if worst[0] <= a.rtf_fail else "FAIL")
        p(f"- RTF (main, {len(mr)} файлов): среднее {mean:.3f}, худший {worst[0]:.3f} ({worst[1]}) → {verdict} "
          f"(PASS ≤ {a.rtf_pass}, FAIL > {a.rtf_fail})")
        if verdict == "FAIL":
            blockers.append(f"RTF худший {worst[0]:.3f} > {a.rtf_fail}")
    for r, n, k in main_rtf:
        if k == "long":
            p(f"- RTF длинного файла {n}: {r:.3f}")
    pss_all = [num(r.get("pss_kb")) for r in mem if num(r.get("pss_kb"))]
    rss_all = [v for r in mem for v in (num(r.get("rss_kb")), num(r.get("vmrss_kb"))) if v]
    hwm = [num(r.get("vmhwm_kb")) for r in mem if num(r.get("vmhwm_kb"))]
    if pss_all:
        peak = mb(max(pss_all))
        who = max(all_pss)[1] if all_pss else "?"
        verdict = "PASS" if peak <= a.pss_pass_mb else ("WARN" if peak <= a.pss_fail_mb else "FAIL")
        p(f"- Пик PSS: {peak:.0f} МБ (файл {who}) → {verdict} (PASS ≤ {a.pss_pass_mb:.0f}, FAIL > {a.pss_fail_mb:.0f}); "
          f"сэмплов {len(mem)}")
        if verdict == "FAIL":
            blockers.append(f"пик PSS {peak:.0f} МБ")
    else:
        p("- Пик PSS: нет данных meminfo")
    if rss_all:
        p(f"- Пик RSS (сэмплы): {mb(max(rss_all)):.0f} МБ" + (f"; VmHWM (точный пик RSS процесса): {mb(max(hwm)):.0f} МБ" if hwm else ""))
    pids = {r.get("pid") for r in mem if r.get("pid")}
    if len(pids) > 1:
        blockers.append(f"процесс перезапускался во время прогона: pid {sorted(pids)}")
        p(f"- ВНИМАНИЕ: несколько pid за прогон {sorted(pids)} — процесс умирал")
    if therm:
        bt = [num(r.get("battery_c")) for r in therm if num(r.get("battery_c")) is not None]
        ct = [num(r.get("cpu_max_c")) for r in therm if num(r.get("cpu_max_c")) is not None]
        ts = [num(r.get("thermal_status")) for r in therm if num(r.get("thermal_status")) is not None]
        if bt:
            p(f"- Батарея: {bt[0]:.1f} → {bt[-1]:.1f} °C (макс {max(bt):.1f})")
        if ct:
            p(f"- CPU (thermal HAL): макс {max(ct):.1f} °C")
        if ts:
            p(f"- Thermal status: макс {max(ts):.0f} (0 норм, ≥3 сильный троттлинг)")
            if max(ts) >= 3:
                warns.append("троттлинг (thermal status ≥ 3) — RTF занижен/завышен, повторить на остывшем телефоне")
    # logcat
    lc = run / "logcat.txt"
    if lc.exists():
        text = lc.read_text(encoding="utf-8", errors="replace").splitlines()
        p()
        p("## logcat")
        p()
        for tag, rx in ISSUES:
            hits = [ln for ln in text if rx.search(ln)]
            if tag == "lmk/kill":
                # наши же am force-stop перед запуском/перезапуском — не LMK
                hits = [ln for ln in hits if not re.search(r"force.?stop|Force stopping|: stop " + re.escape(PKG), ln, re.I)]
            p(f"- {tag}: {len(hits)}" + (f" — {hits[0].strip()[:200]}" if hits else ""))
            if hits and tag in ("crash", "anr", "lmk/kill"):
                blockers.append(f"logcat {tag}: {hits[0].strip()[:160]}")
            if hits and tag == "app_error":
                for h in hits[:5]:
                    p(f"    {h.strip()[:200]}")
        done = any("bench done" in ln for ln in text)
        p(f"- bench done: {'да' if done else 'НЕТ'}")
        if not done:
            blockers.append("нет «bench done» в logcat")
    p()
    p("## Блокеры" if blockers else "## Блокеров нет")
    for b in dict.fromkeys(blockers):
        p(f"- {b}")
    if warns:
        p()
        p("## Предупреждения")
        for w in dict.fromkeys(warns):
            p(f"- {w}")
    text = "\n".join(lines)
    print(text)
    (run / "summary.md").write_text(text + "\n", encoding="utf-8")
    print(f"\nСводка: {run / 'summary.md'}")
    return 1 if blockers else 0


if __name__ == "__main__":
    sys.exit(main())
