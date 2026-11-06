#!/usr/bin/env python3
"""Скачивает модели в src/android/app/src/main/assets/models и библиотеки sherpa-onnx в src/android/libs.

Запуск: python tools/fetch_models.py [--llm qwen2.5-0.5b|qwen3-0.6b|none]
Модели в git не коммитятся (см. .gitignore). Приложение их не скачивает — только упаковывает в APK.
"""
import argparse
import shutil
import sys
import tarfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/android/app/src/main/assets/models"
LIBS = ROOT / "src/android/libs"
CACHE = ROOT / ".cache/downloads"

GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
SHERPA_VER = "2.0.1"

ARCHIVES = {
    "asr": f"{GH}/asr-models/sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16.tar.bz2",
    "asr_ctc": f"{GH}/asr-models/sherpa-onnx-nemo-ctc-giga-am-v3-russian-2025-12-16.tar.bz2",
    "seg": f"{GH}/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2",
}
FILES = {
    "vad/silero_vad.onnx": f"{GH}/asr-models/silero_vad.onnx",
    "spk/embedding.onnx": f"{GH}/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx",
}
LIBFILES = {
    f"sherpa-onnx-{SHERPA_VER}.aar": f"{GH}/v{SHERPA_VER}/sherpa-onnx-{SHERPA_VER}.aar",
    f"sherpa-onnx-jvm-{SHERPA_VER}.jar": f"{GH}/v{SHERPA_VER}/sherpa-onnx-jvm-{SHERPA_VER}.jar",
    f"sherpa-onnx-native-lib-win-x64-{SHERPA_VER}.jar": f"{GH}/v{SHERPA_VER}/sherpa-onnx-native-lib-win-x64-{SHERPA_VER}.jar",
}
LLMS = {
    "qwen2.5-0.5b": "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
    "qwen3-0.6b": "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf",
}


def download(url: str, dst: Path) -> Path:
    if dst.exists() and dst.stat().st_size > 0:
        print(f"[skip] {dst.name}")
        return dst
    dst.parent.mkdir(parents=True, exist_ok=True)
    tmp = dst.with_suffix(dst.suffix + ".part")
    print(f"[get ] {url}")
    req = urllib.request.Request(url, headers={"User-Agent": "neuro-steno-fetch/1.0"})
    with urllib.request.urlopen(req) as r, open(tmp, "wb") as f:
        shutil.copyfileobj(r, f, 1 << 20)
    tmp.rename(dst)
    return dst


def extract(archive: Path, target: Path) -> None:
    """Распаковывает архив sherpa, кладёт содержимое верхней папки в target (без test_wavs)."""
    if target.exists() and any(target.iterdir()):
        print(f"[skip] {target}")
        return
    target.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, "r:bz2") as tf:
        for m in tf.getmembers():
            if not m.isfile():
                continue
            parts = Path(m.name).parts
            rel = Path(*parts[1:]) if len(parts) > 1 else Path(parts[0])
            if "test_wavs" in rel.parts or rel.suffix in {".md", ".py", ".sh"}:
                continue
            out = target / rel
            if not out.resolve().is_relative_to(target.resolve()):
                raise RuntimeError(f"unsafe path in archive: {m.name}")
            out.parent.mkdir(parents=True, exist_ok=True)
            with tf.extractfile(m) as src, open(out, "wb") as dst:
                shutil.copyfileobj(src, dst)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--llm", default="qwen2.5-0.5b", choices=[*LLMS, "none"])
    ap.add_argument("--with-ctc", action="store_true", help="ещё и CTC-модель для A/B на bench")
    a = ap.parse_args()

    for name, url in LIBFILES.items():
        download(url, LIBS / name)
    for rel, url in FILES.items():
        download(url, ASSETS / rel)
    keys = ["asr", "seg"] + (["asr_ctc"] if a.with_ctc else [])
    for k in keys:
        arc = download(ARCHIVES[k], CACHE / Path(ARCHIVES[k]).name)
        extract(arc, ASSETS / k)
    if a.llm != "none":
        download(LLMS[a.llm], ASSETS / "llm" / "model.gguf")
    print("done:", ASSETS)
    return 0


if __name__ == "__main__":
    sys.exit(main())
