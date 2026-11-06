#!/usr/bin/env bash
# Синтетическая матрица testdata/{dev,test} из реплик TTS (.cache/tts/src_{dev,test}, см. tools/tts_lines.ps1).
# dev и test используют разные фразы и разные seed. Запуск из корня репозитория.
set -euo pipefail
export PYTHONIOENCODING=utf-8
for split in dev test; do
  if [ "$split" = dev ]; then seed=10; else seed=20; fi
  D="testdata/$split"; S=".cache/tts/src_$split"; DEG=".cache/deg_$split"
  mkdir -p "$D" "$DEG"
  rm -f "${D:?}"/*.wav "${D:?}"/*.json "${DEG:?}"/*
  gen() { python -I tools/make_synthetic.py "$S" "$D" "$@" >/dev/null; }
  gen --n 1 --speakers 1 --duration-s 60  --seed $((seed+0)) --prefix mono  --overlap-prob 0
  gen --n 2 --speakers 2 --duration-s 90  --seed $((seed+1)) --prefix clean --overlap-prob 0.1
  gen --n 2 --speakers 3 --duration-s 180 --seed $((seed+2)) --prefix clean --overlap-prob 0.15
  gen --n 1 --speakers 5 --duration-s 300 --seed $((seed+3)) --prefix clean --overlap-prob 0.15
  gen --n 1 --speakers 3 --duration-s 180 --seed $((seed+4)) --prefix noisy --snr-db 8 --overlap-prob 0.3
  # Заведомо разные базовые голоса (Irina S1 + Pavel S2) — реалистичный случай для диаризации.
  DS=".cache/tts/src_${split}_distinct"; mkdir -p "$DS"; cp -r "$S"/S1 "$S"/S2 "$DS"/
  python -I tools/make_synthetic.py "$DS" "$D" --n 2 --speakers 2 --duration-s 120 --seed $((seed+5)) --prefix distinct --overlap-prob 0.2 >/dev/null
  cp "$D"/clean_3spk_000.* "$DEG"/
  for m in tel8k quiet clip; do python -I tools/degrade.py "$DEG" "$D" --mode "$m" >/dev/null; done
  python -I tools/tag_voices.py "$D"
  echo "$split: $(ls "$D"/*.wav | wc -l) files"
done
