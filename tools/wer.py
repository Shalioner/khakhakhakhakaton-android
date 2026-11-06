"""WER и cpWER между эталоном и гипотезой (JSON по контракту).

    python tools/wer.py ref.json hyp.json

WER   — по конкатенации текстов всех реплик в порядке start_ms.
cpWER — concatenated minimum-permutation WER: тексты конкатенируются по спикерам,
        спикеры эталона и гипотезы сопоставляются перестановкой, минимизирующей
        суммарное число ошибок (точно, венгерским алгоритмом по матрице ошибок;
        лишние/недостающие спикеры сопоставляются с пустой строкой).
        cpWER - WER ~ вклад ошибок диаризации в текст.
Нормализация: lower, ё→е, пунктуация → пробел, схлопывание пробелов.
"""
import re
import sys
from collections import OrderedDict

import jiwer
import numpy as np
from scipy.optimize import linear_sum_assignment

_PUNCT = re.compile(r"[^\w\s]|_", re.UNICODE)


def normalize(text):
    t = text.lower().replace("ё", "е")
    t = _PUNCT.sub(" ", t)
    return " ".join(t.split())


def word_errors(ref, hyp):
    """(errors, n_ref_words) для нормализованных строк; терпимо к пустым."""
    r, h = ref.split(), hyp.split()
    if not r:
        return len(h), 0
    if not h:
        return len(r), len(r)
    o = jiwer.process_words(ref, hyp)
    return o.substitutions + o.deletions + o.insertions, len(r)


def _ratio(err, n):
    if n == 0:
        return 0.0 if err == 0 else 1.0
    return err / n


def _concat(segs):
    return normalize(" ".join(s[3] for s in sorted(segs, key=lambda s: (s[1], s[2]))))


def wer(ref_segs, hyp_segs):
    e, n = word_errors(_concat(ref_segs), _concat(hyp_segs))
    return _ratio(e, n), e, n


def _by_speaker(segs):
    d = OrderedDict()
    for s in sorted(segs, key=lambda s: (s[1], s[2])):
        d.setdefault(s[0], []).append(s)
    return [_concat(v) for v in d.values()]


def cpwer(ref_segs, hyp_segs):
    R, H = _by_speaker(ref_segs), _by_speaker(hyp_segs)
    k = max(len(R), len(H), 1)
    R += [""] * (k - len(R))
    H += [""] * (k - len(H))
    cost = np.zeros((k, k), dtype=np.int64)
    n_total = 0
    for i, r in enumerate(R):
        n_total += len(r.split())
        for j, h in enumerate(H):
            cost[i, j] = word_errors(r, h)[0]
    rows, cols = linear_sum_assignment(cost)
    e = int(cost[rows, cols].sum())
    return _ratio(e, n_total), e, n_total


def main(argv=None):
    from validate_json import load_segments, utf8_stdout
    utf8_stdout()
    argv = argv if argv is not None else sys.argv[1:]
    if len(argv) != 2:
        print(__doc__)
        return 2
    ref, hyp = load_segments(argv[0]), load_segments(argv[1])
    w, we, n = wer(ref, hyp)
    c, ce, _ = cpwer(ref, hyp)
    print(f"WER   {w * 100:.2f}%  ({we}/{n})")
    print(f"cpWER {c * 100:.2f}%  ({ce}/{n})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
