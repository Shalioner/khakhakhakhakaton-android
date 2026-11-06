"""Юнит-проверки метрик: python -m pytest tools/test_metrics.py -q"""
import json
import math
import os
import shutil
import sys

import numpy as np
import pytest
import soundfile as sf

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_synthetic  # noqa: E402
import report  # noqa: E402
from boundary_err import boundary_err  # noqa: E402
from der import der  # noqa: E402
from validate_json import validate  # noqa: E402
from wer import cpwer, normalize, wer  # noqa: E402


def S(spk, s, e, t="x"):
    return (spk, s, e, t)


# ---------- WER ----------
def test_normalize():
    assert normalize("Ёлка, ЁЖ!  Привет — мир...") == "елка еж привет мир"


def test_wer_basic():
    ref = [S("a", 0, 1000, "Привет, мир!"), S("b", 1000, 2000, "как дела")]
    assert wer(ref, ref)[0] == 0.0
    hyp = [S("speaker_1", 0, 2000, "привет мир как дело")]
    w, e, n = wer(ref, hyp)
    assert (e, n) == (1, 4) and w == 0.25
    assert wer(ref, [])[0] == 1.0
    assert wer([], [])[0] == 0.0
    assert wer(ref, [S("a", 0, 1, "привет мир как дела лишнее")])[1] == 1


def test_wer_order_by_start():
    ref = [S("a", 0, 1, "один"), S("b", 5, 6, "два")]
    hyp = [S("x", 5, 6, "два"), S("y", 0, 1, "один")]  # порядок в файле не важен
    assert wer(ref, hyp)[0] == 0.0


def test_cpwer():
    ref = [S("A", 0, 1, "раз два"), S("B", 1, 2, "три четыре")]
    swapped = [S("s1", 0, 1, "три четыре"), S("s2", 1, 2, "раз два")]
    assert cpwer(ref, swapped)[0] == 0.0  # перестановка спикеров не штрафуется
    one_spk = [S("s1", 0, 2, "раз два три четыре")]
    w, e, n = cpwer(ref, one_spk)
    assert (e, n) == (4, 4)  # A↔s1: 2 вставки; B↔пусто: 2 удаления
    assert wer(ref, one_spk)[0] == 0.0  # а обычный WER диаризацию не видит
    three = ref + [S("C", 2, 3, "пять")]
    assert cpwer(three, ref)[1] == 1


# ---------- DER ----------
def test_der_perfect_and_permuted():
    ref = [S("A", 0, 5000), S("B", 5000, 10000)]
    hyp = [S("speaker_2", 0, 5000), S("speaker_1", 5000, 10000)]
    r = der(ref, hyp, collar_ms=0)
    assert r["der"] == 0.0 and r["mapping"] == {"A": "speaker_2", "B": "speaker_1"}


def test_der_one_speaker():
    ref = [S("A", 0, 5000), S("B", 5000, 10000)]
    r = der(ref, [S("s", 0, 10000)], collar_ms=0)
    assert r["der"] == pytest.approx(0.5) and r["conf"] == pytest.approx(0.5)


def test_der_miss_fa_collar():
    ref = [S("A", 0, 10000)]
    r = der(ref, [S("s", 0, 5000)], collar_ms=0)
    assert r["miss"] == pytest.approx(0.5) and r["fa"] == 0
    r = der(ref, [S("s", 0, 10000), S("t", 20000, 21000)], collar_ms=0)
    assert r["fa"] == pytest.approx(0.1)
    # сдвиг границы на 200 мс попадает в collar 250 мс
    assert der(ref, [S("s", 200, 9800)], collar_ms=250)["der"] == 0.0
    assert der(ref, [S("s", 200, 9800)], collar_ms=0)["der"] == pytest.approx(0.04)


def test_der_overlap():
    ref = [S("A", 0, 4000), S("B", 2000, 6000)]  # 2 с перекрытия, всего 8 с речи
    r = der(ref, [S("x", 0, 4000), S("y", 4000, 6000)], collar_ms=0)
    assert r["miss"] == pytest.approx(2 / 8) and r["der"] == pytest.approx(2 / 8)


def test_der_empty():
    assert der([], [], 250)["der"] == 0.0
    assert der([S("A", 0, 1000)], [], 0)["der"] == 1.0


# ---------- границы ----------
def test_boundary():
    ref = [S("A", 1000, 3000), S("B", 3500, 6000), S("A", 9000, 9500)]
    hyp = [S("x", 1100, 2900), S("y", 3400, 6300)]
    b = boundary_err(ref, hyp)
    assert b["matched"] == 2 and b["unmatched_ref"] == 1 and b["unmatched_hyp"] == 0
    assert b["start_mean"] == pytest.approx(100) and b["end_mean"] == pytest.approx(200)
    assert b["median"] == pytest.approx(100)
    assert math.isnan(boundary_err(ref, [])["mean"])


# ---------- контракт ----------
def T(*items):
    return {"transcript": [dict(zip(("speaker_id", "start_ms", "end_ms", "text"), it)) for it in items]}


def test_validate_ok():
    assert validate(T(("speaker_1", 0, 100, "а"), ("speaker_2", 50, 200, "б"),
                      ("speaker_1", 300, 400, "в")), duration_ms=400) == []
    assert validate({"transcript": []}) == []


@pytest.mark.parametrize("obj,needle", [
    (T(("speaker_2", 0, 100, "а")), "ожидался speaker_1"),
    (T(("speaker_1", 100, 100, "а")), "start_ms < end_ms"),
    (T(("speaker_1", -1, 100, "а")), "start_ms < end_ms"),
    (T(("speaker_1", 200, 300, "а"), ("speaker_1", 100, 150, "б")), "не отсортировано"),
    (T(("speaker_1", 0, 100, "   ")), "text пустой"),
    (T(("spk1", 0, 100, "а")), "speaker_N"),
    (T(("speaker_1", 0, 100.5, "а")), "целыми"),
    (T(("speaker_1", 0, True, "а")), "целыми"),
    (T(("speaker_1", 0, 500, "а")), "duration_ms"),
    ({"transcript": [], "summary": "x"}, "ключи"),
    ({"transcript": [{"speaker_id": "speaker_1", "start_ms": 0, "end_ms": 1, "text": "a", "conf": 1}]}, "лишние"),
])
def test_validate_errors(obj, needle):
    errs = validate(obj, duration_ms=400)
    assert errs and any(needle in e for e in errs), errs


# ---------- синтетика + report end-to-end ----------
def test_synthetic_and_report(tmp_path):
    src = tmp_path / "src"
    rng = np.random.default_rng(1)
    for spk, f0 in (("m1", 120), ("f1", 220), ("m2", 150)):
        (src / spk).mkdir(parents=True)
        for k in range(3):
            n = int(16000 * rng.uniform(1.0, 2.0))
            t = np.arange(n) / 16000
            x = 0.3 * np.sin(2 * np.pi * f0 * t)
            x = np.concatenate([np.zeros(4000), x, np.zeros(4000)])  # тишина по краям срежется
            sf.write(src / spk / f"u{k}.wav", x, 22050 if k == 0 else 16000)  # и ресемплинг
            (src / spk / f"u{k}.txt").write_text(f"{spk} фраза номер {k}", encoding="utf-8")
    ref_dir = tmp_path / "ref"
    assert make_synthetic.main([str(src), str(ref_dir), "--n", "2", "--speakers", "3",
                                "--duration-s", "5", "--snr-db", "15"]) == 0
    jsons = sorted(p for p in ref_dir.glob("*.json") if not p.name.endswith(".meta.json"))
    assert len(jsons) == 2
    for p in jsons:
        meta = json.loads(p.with_name(p.stem + ".meta.json").read_text(encoding="utf-8"))
        info = sf.info(str(p.with_suffix(".wav")))
        assert info.samplerate == 16000 and info.channels == 1
        assert validate(json.loads(p.read_text(encoding="utf-8")), meta["duration_ms"]) == []
    hyp_dir = tmp_path / "hyp"
    hyp_dir.mkdir()
    shutil.copy(jsons[0], hyp_dir / jsons[0].name)  # второй файл отсутствует -> худший случай
    (hyp_dir / "timings.json").write_text(json.dumps({jsons[0].stem: {"proc_ms": 500}}))
    rows = report.evaluate(str(ref_dir), str(hyp_dir),
                           json.loads((hyp_dir / "timings.json").read_text()))
    r0, r1 = rows
    assert r0["wer"] == 0 and r0["cpwer"] == 0 and r0["der"] == 0 and r0["contract"] == "OK"
    assert r0["rtf"] > 0 and r0["spk_ref"] == r0["spk_hyp"] == 3
    assert r1["contract"] == "MISSING" and r1["wer"] == 1.0
    md = report.to_markdown(rows)
    assert "**mean**" in md and "**worst**" in md and "По ячейкам" in md
    assert report.main([str(ref_dir), str(hyp_dir)]) == 1  # из-за MISSING
    assert (hyp_dir / "report.csv").exists()
