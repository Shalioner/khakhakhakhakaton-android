"""Ошибка границ реплик: |Δstart| и |Δend| (мс) после сопоставления реплик.

    python tools/boundary_err.py ref.json hyp.json

Сопоставление один-к-одному по максимальному временному перекрытию
(венгерский алгоритм по матрице перекрытий; пары с нулевым перекрытием отбрасываются).
Спикер не учитывается (это забота DER). Несопоставленные реплики считаются отдельно.
"""
import sys

import numpy as np
from scipy.optimize import linear_sum_assignment


def match(ref_segs, hyp_segs):
    if not ref_segs or not hyp_segs:
        return []
    rs = np.array([[s[1], s[2]] for s in ref_segs], dtype=np.int64)
    hs = np.array([[s[1], s[2]] for s in hyp_segs], dtype=np.int64)
    ov = np.minimum(rs[:, None, 1], hs[None, :, 1]) - np.maximum(rs[:, None, 0], hs[None, :, 0])
    ov = np.maximum(ov, 0)
    ri, hi = linear_sum_assignment(-ov)
    return [(int(i), int(j)) for i, j in zip(ri, hi) if ov[i, j] > 0]


def boundary_err(ref_segs, hyp_segs):
    pairs = match(ref_segs, hyp_segs)
    ds = np.array([abs(ref_segs[i][1] - hyp_segs[j][1]) for i, j in pairs], dtype=float)
    de = np.array([abs(ref_segs[i][2] - hyp_segs[j][2]) for i, j in pairs], dtype=float)
    both = np.concatenate([ds, de])
    nan = float("nan")
    return {
        "matched": len(pairs),
        "unmatched_ref": len(ref_segs) - len(pairs),
        "unmatched_hyp": len(hyp_segs) - len(pairs),
        "start_mean": float(ds.mean()) if len(ds) else nan,
        "start_median": float(np.median(ds)) if len(ds) else nan,
        "end_mean": float(de.mean()) if len(de) else nan,
        "end_median": float(np.median(de)) if len(de) else nan,
        "mean": float(both.mean()) if len(both) else nan,
        "median": float(np.median(both)) if len(both) else nan,
    }


def main(argv=None):
    from validate_json import load_segments, utf8_stdout
    utf8_stdout()
    argv = argv if argv is not None else sys.argv[1:]
    if len(argv) != 2:
        print(__doc__)
        return 2
    r = boundary_err(load_segments(argv[0]), load_segments(argv[1]))
    print(f"matched {r['matched']}  unmatched ref {r['unmatched_ref']} hyp {r['unmatched_hyp']}")
    print(f"|start| mean {r['start_mean']:.0f} ms, median {r['start_median']:.0f} ms")
    print(f"|end|   mean {r['end_mean']:.0f} ms, median {r['end_median']:.0f} ms")
    print(f"both    mean {r['mean']:.0f} ms, median {r['median']:.0f} ms")
    return 0


if __name__ == "__main__":
    sys.exit(main())
