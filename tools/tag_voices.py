#!/usr/bin/env python3
"""Помечает в <name>.meta.json условие voices: mono / distinct / same_base / mixed по базовому TTS-голосу дикторов."""
import json
import sys
from pathlib import Path

BASE = {"S1": "Irina", "S3": "Irina", "S5": "Irina", "S2": "Pavel", "S4": "Pavel"}

for f in Path(sys.argv[1]).glob("*.meta.json"):
    m = json.loads(f.read_text(encoding="utf-8"))
    spk = m.get("speakers_src", [])
    bases = {BASE.get(Path(s).name, s) for s in spk}
    if len(spk) <= 1:
        v = "mono"
    elif len(bases) == len(spk):
        v = "distinct"
    elif len(bases) == 1:
        v = "same_base"
    else:
        v = "mixed"
    m.setdefault("conditions", {})["voices"] = v
    f.write_text(json.dumps(m, ensure_ascii=False, indent=1), encoding="utf-8")
