"""Синтез фраз testdata/synth_lines_ru.txt голосами Piper (piper-tts, onnxruntime; сборка не нужна).

    .cache/venv_piper/Scripts/python -I tools/piper_synth.py testdata/synth_lines_ru.txt \
        .cache/data/piper .cache/data/piper_lines [--voices irina,ruslan,dmitri,denis]

Чётные строки (0, 2, …) → OUT/dev/<voice>/<NNN>.wav+.txt, нечётные → OUT/test/... (тот же раздел фраз,
что у testdata/dev|test). Формат — --layout dirs для tools/make_synthetic.py. Голос: VOICES_DIR/<voice>/
ru_RU-<voice>-medium.onnx (+ .onnx.json). Уже существующие wav пропускаются.
Установка: python -m venv .cache/venv_piper && .cache/venv_piper/Scripts/python -m pip install piper-tts==1.8.0
"""
import argparse
import sys
import wave
from pathlib import Path


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("lines")
    ap.add_argument("voices_dir")
    ap.add_argument("out_dir")
    ap.add_argument("--voices", default="irina,ruslan,dmitri,denis")
    a = ap.parse_args(argv)
    from piper import PiperVoice
    lines = [s.strip() for s in Path(a.lines).read_text(encoding="utf-8").splitlines() if s.strip()]
    n = 0
    for v in a.voices.split(","):
        voice = PiperVoice.load(str(Path(a.voices_dir) / v / f"ru_RU-{v}-medium.onnx"))
        for i, text in enumerate(lines):
            d = Path(a.out_dir) / ("dev" if i % 2 == 0 else "test") / v
            d.mkdir(parents=True, exist_ok=True)
            wav = d / f"{i:03d}.wav"
            if not wav.exists():
                with wave.open(str(wav), "wb") as w:
                    voice.synthesize_wav(text, w)
                n += 1
            (d / f"{i:03d}.txt").write_text(text, encoding="utf-8")
    print(f"piper: синтезировано {n} реплик")
    return 0


if __name__ == "__main__":
    sys.exit(main())
