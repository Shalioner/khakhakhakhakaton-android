#!/usr/bin/env bash
# Наборы testdata/live_dev и testdata/live_test (DataHive, Langswap Dialogs, Piper ru, ESC-50-вставки).
# Запуск из корня репозитория. Скачанное лежит в .cache/data/<источник>/ (не в git), ~400 МБ.
# testdata/dev и testdata/test не меняются (meow_* только читает testdata/dev/*.wav — сначала
# tools/build_synth_matrix.sh). Шаг Piper требует venv с piper-tts (создаётся здесь, если нет).
# FETCH=0 — не ходить в сеть (только сборка из .cache/data).
set -euo pipefail
export PYTHONIOENCODING=utf-8
PY="python -I"
VENV=.cache/venv_piper
if [ -x "$VENV/Scripts/python" ]; then VPY="$VENV/Scripts/python"; else VPY="$VENV/bin/python"; fi

if [ "${FETCH:-1}" = 1 ]; then
  $PY tools/build_live_sets.py fetch
  if [ ! -x "$VPY" ]; then
    python -m venv "$VENV"
    if [ -x "$VENV/Scripts/python" ]; then VPY="$VENV/Scripts/python"; else VPY="$VENV/bin/python"; fi
    "$VPY" -m pip install -q piper-tts==1.8.0 numpy soundfile scipy
  fi
fi

for split in dev test; do
  D="testdata/live_$split"; mkdir -p "$D"
  rm -f "${D:?}"/*.wav "${D:?}"/*.json
done

"$VPY" -I tools/piper_synth.py testdata/synth_lines_ru.txt .cache/data/piper .cache/data/piper_lines
$PY tools/build_live_sets.py all

for split in dev test; do
  D="testdata/live_$split"; bad=0
  for j in "$D"/*.json; do
    case "$j" in *.meta.json) continue;; esac
    $PY tools/validate_json.py "$j" --audio "${j%.json}.wav" >/dev/null || { echo "FAIL $j"; bad=1; }
  done
  echo "$split: $(ls "$D"/*.wav | wc -l) files, contract $([ $bad = 0 ] && echo OK || echo FAIL)"
done
