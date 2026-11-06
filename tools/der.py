"""DER (Diarization Error Rate) по JSON эталона и гипотезы.

    python tools/der.py ref.json hyp.json [--collar-ms 250] [--step-ms 10]

Покадровый расчёт (шаг 10 мс), перекрытия речи учитываются (как md-eval):
  miss = max(0, Nref - Nhyp), fa = max(0, Nhyp - Nref),
  conf = min(Nref, Nhyp) - Ncorrect;  DER = (miss + fa + conf) / sum(Nref).
Collar: кадры в пределах ±collar от каждой границы реплики эталона не оцениваются.
Сопоставление спикеров ref↔hyp — венгерский алгоритм по матрице совместной длительности.
Текст реплик не используется: реплика = интервал речи спикера.
"""
import argparse
import sys

import numpy as np
from scipy.optimize import linear_sum_assignment


def _activity(segs, n_frames, step):
    spk = list(dict.fromkeys(s[0] for s in segs))
    idx = {k: i for i, k in enumerate(spk)}
    A = np.zeros((max(len(spk), 1), n_frames), dtype=bool)
    for s in segs:
        a, b = int(round(s[1] / step)), int(round(s[2] / step))
        A[idx[s[0]], max(a, 0):max(min(b, n_frames), 0)] = True
    return A[: len(spk)] if spk else A[:0]


def der(ref_segs, hyp_segs, collar_ms=250, step_ms=10):
    """Возвращает dict: der, miss, fa, conf (доли), total_ms, mapping."""
    end = max([s[2] for s in ref_segs + hyp_segs] + [0])
    n = int(np.ceil(end / step_ms)) + 1
    R, H = _activity(ref_segs, n, step_ms), _activity(hyp_segs, n, step_ms)
    mask = np.ones(n, dtype=bool)
    c = int(round(collar_ms / step_ms))
    if c > 0:
        for s in ref_segs:
            for t in (s[1], s[2]):
                f = int(round(t / step_ms))
                mask[max(f - c, 0):min(f + c, n)] = False
    R, H = R[:, mask], H[:, mask]
    nref, nhyp = R.sum(0), H.sum(0)
    mapping, ncorr = {}, np.zeros(R.shape[1], dtype=np.int64)
    if len(R) and len(H):
        ov = R.astype(np.int64) @ H.T.astype(np.int64)
        ri, hi = linear_sum_assignment(-ov)
        ref_ids = list(dict.fromkeys(s[0] for s in ref_segs))
        hyp_ids = list(dict.fromkeys(s[0] for s in hyp_segs))
        for i, j in zip(ri, hi):
            if ov[i, j] > 0:
                mapping[ref_ids[i]] = hyp_ids[j]
                ncorr += (R[i] & H[j])
    total = int(nref.sum())
    miss = int(np.maximum(nref - nhyp, 0).sum())
    fa = int(np.maximum(nhyp - nref, 0).sum())
    conf = int((np.minimum(nref, nhyp) - ncorr).sum())
    d = max(total, 1)
    if total == 0:
        err = 0.0 if fa == 0 else 1.0
    else:
        err = (miss + fa + conf) / d
    return {"der": err, "miss": miss / d, "fa": fa / d, "conf": conf / d,
            "total_ms": total * step_ms, "mapping": mapping}


def main(argv=None):
    from validate_json import load_segments, utf8_stdout
    utf8_stdout()
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("ref")
    ap.add_argument("hyp")
    ap.add_argument("--collar-ms", type=int, default=250)
    ap.add_argument("--step-ms", type=int, default=10)
    a = ap.parse_args(argv)
    r = der(load_segments(a.ref), load_segments(a.hyp), a.collar_ms, a.step_ms)
    print(f"DER {r['der'] * 100:.2f}%  (miss {r['miss'] * 100:.2f}%, fa {r['fa'] * 100:.2f}%, "
          f"conf {r['conf'] * 100:.2f}%; scored {r['total_ms'] / 1000:.1f} s)  map {r['mapping']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
