"""ADR-0009 분석: 칸 비·요약 R·재표본 구간(§5.2), 성공 비열등(§6), 결정 표(§7.2·§7.3, 11-05 개정 — R_D 행 해당 없음).

  python3 experiment/runner/del_analyze.py --results-dir experiment/results-del-main [--json out.json]

백분위 구간 = 정렬한 재표본 2,000개의 0기반 50번째·1949번째(아래·위 50개씩 제외, 보간 없음 — ADR에 산식이 없어 여기서 고정).
"""
import argparse
import json
import math
import random
import statistics as st
from pathlib import Path

ARMS = ("solo", "explore-haiku", "explore-sonnet", "explore-opus")
SEED = 20261103
BOOT = 2000
Z = 1.959963984540054
MARGIN = -0.10
# (이름, A, B, 종류) — 모델 효과 = 주, 위임 효과 = 보조(§7.3)
COMPARISONS = (
    ("모델 효과 haiku", "explore-haiku", "explore-opus", "model"),
    ("모델 효과 sonnet", "explore-sonnet", "explore-opus", "model"),
    ("위임 효과", "explore-opus", "solo", "delegation"),
)
# 결정 표 행 → 종류별 문구(§7.2 · §7.3). 둘째 행(R_D)은 2026-11-05 개정으로 적용하지 않는다.
ROW_TEXT = {
    "model": {1: "첫 행 — 탐색 워커 기본 모델을 그 모델로", 3: "셋째 행 — 비용 손해(바꾸지 않음)",
              4: "넷째 행 — 성공 손해(바꾸지 않음)", 0: "그 밖 — 불확실(현행 유지, 사용자 결정)"},
    "delegation": {1: "첫 행 — 위임은 비용에도 이득", 3: "셋째 행 — 위임은 비용 손해(컨텍스트 보호 등 다른 이유로만)",
                   4: "넷째 행 — 위임은 성공 손해", 0: "그 밖 — 불확실(현행 유지, 사용자 결정)"},
}
MIN_CELLS, MIN_D_CELLS = 12, 5


def load(results_dir: Path) -> dict:
    """(case, structure, arm) → 유효 실행 목록. 같은 (rep, 칸, 방식)에 유효 행이 여럿이면 마지막 것."""
    by_key = {}
    for line in (results_dir / "runs.jsonl").read_text().splitlines():
        r = json.loads(line)
        if r.get("valid"):
            by_key[(r["rep"], r["case"], r["structure"], r["arm"])] = r
    cells = {}
    for (_, c, s, a), r in by_key.items():
        cells.setdefault((c, s, a), []).append(r)
    return cells


def costs(runs: list) -> list:
    """비용 계산에 쓰는 값: cost_check ok인 실행의 cost_usd(§7.4)."""
    return [r["cost_usd"] for r in runs if r.get("cost_check") == "ok" and r.get("cost_usd") is not None]


def wilson(k: int, n: int, z: float = Z):
    if n == 0:
        return 0.0, 1.0
    p = k / n
    den = 1 + z * z / n
    mid = (p + z * z / (2 * n)) / den
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / den
    return mid - half, mid + half


def newcombe(k1: int, n1: int, k2: int, n2: int, z: float = Z):
    """두 독립 비율 차 p1 − p2의 Newcombe 하이브리드 점수 구간(Wilson 기반)."""
    p1, p2 = k1 / n1, k2 / n2
    l1, u1 = wilson(k1, n1, z)
    l2, u2 = wilson(k2, n2, z)
    d = p1 - p2
    return d - math.sqrt((p1 - l1) ** 2 + (u2 - p2) ** 2), d + math.sqrt((u1 - p1) ** 2 + (p2 - l2) ** 2)


def gmean(xs: list) -> float:
    return math.exp(sum(math.log(x) for x in xs) / len(xs))


def compare(cells: dict, a: str, b: str, kind: str = "model", seed: int = SEED, boot: int = BOOT) -> dict:
    keys = sorted({(c, s) for (c, s, _) in cells})
    excluded = {x: sum(1 for k in keys for r in cells.get((*k, x), []) if r.get("cost_check") != "ok") for x in (a, b)}
    common = [k for k in keys if len(costs(cells.get((*k, a), []))) >= 2 and len(costs(cells.get((*k, b), []))) >= 2]
    ratio = {k: st.median(costs(cells[(*k, a)])) / st.median(costs(cells[(*k, b)])) for k in common}
    d_common = [k for k in common if k[0].startswith("D")]  # D1~D3 (S1~S4 = 나머지)
    s_common = [k for k in common if not k[0].startswith("D")]

    rng = random.Random(seed)
    samples = {"R": [], "R_D": [], "R_S": []}
    for _ in range(boot):
        rr = {}
        for k in common:
            ca, cb = costs(cells[(*k, a)]), costs(cells[(*k, b)])
            rr[k] = st.median(rng.choices(ca, k=len(ca))) / st.median(rng.choices(cb, k=len(cb)))
        if common:
            samples["R"].append(gmean([rr[k] for k in common]))
        if d_common:
            samples["R_D"].append(gmean([rr[k] for k in d_common]))
        if s_common:
            samples["R_S"].append(gmean([rr[k] for k in s_common]))

    def pct(xs):
        xs = sorted(xs)
        return xs[int(0.025 * len(xs))], xs[min(len(xs) - 1, int(math.ceil(0.975 * len(xs))) - 1)]

    def summary(ks, name, need):
        if not ks or len(ks) < need:
            return {"value": None, "ci": None, "cells": len(ks), "note": f"판정 불가(공통 칸 {len(ks)} < {need})"}
        return {"value": gmean([ratio[k] for k in ks]), "ci": pct(samples[name]), "cells": len(ks)}

    rows = ROW_TEXT[kind]
    if not common:
        return {"A": a, "B": b, "kind": kind, "common_cells": 0, "cost_excluded": excluded, "cell_ratio": {},
                "R": summary([], "R", MIN_CELLS), "R_D": None, "R_S": None, "success": None, "blocked": [],
                "row_no": None, "row": "판정 불가(공통 칸 0)", "success_loss": False}

    ka = sum(r["success"] for k in common for r in cells[(*k, a)])
    na = sum(len(cells[(*k, a)]) for k in common)
    kb = sum(r["success"] for k in common for r in cells[(*k, b)])
    nb = sum(len(cells[(*k, b)]) for k in common)
    lo, hi = newcombe(ka, na, kb, nb)
    noninf = "비열등" if lo > MARGIN else ("열등" if hi < MARGIN else "성공 불확실")
    blocked = [k for k in common
               if sum(r["success"] for r in cells[(*k, b)]) >= 2 and sum(r["success"] for r in cells[(*k, a)]) == 0]

    R = summary(common, "R", MIN_CELLS)
    if R["value"] is None:
        no, row = None, R["note"]
    elif R["value"] < 0.8 and R["ci"][1] < 1.0 and noninf == "비열등" and not blocked:
        no = 1
    elif R["value"] > 1.25 and R["ci"][0] > 1.0:
        no = 3
    elif noninf == "열등":
        no = 4
    else:
        no = 0
    if no is not None:
        row = rows[no]
    return {
        "A": a, "B": b, "kind": kind, "common_cells": len(common), "cost_excluded": excluded,
        "cell_ratio": {f"{c}/{s}": ratio[(c, s)] for c, s in common},
        "R": R, "R_D": summary(d_common, "R_D", MIN_D_CELLS), "R_S": summary(s_common, "R_S", 1),
        "success": {"A": [ka, na], "B": [kb, nb], "diff": ka / na - kb / nb, "ci": (lo, hi), "verdict": noninf},
        "blocked": [f"{c}/{s}" for c, s in blocked],
        "row_no": no, "row": row,
        # 셋째 행이 먼저 맞아도 열등이면 '성공 손해'를 따로 기록한다(§7.2 넷째 행 "비용과 따로")
        "success_loss": noninf == "열등",
    }


def choose_model(results: dict):
    """§7.2 "둘 다 맞으면 R이 작은 쪽": 모델 효과 비교 중 첫 행인 것에서 R이 가장 작은 A. 없으면 None."""
    hits = [c for c in results.values() if c["kind"] == "model" and c["row_no"] == 1]
    return min(hits, key=lambda c: c["R"]["value"])["A"] if hits else None


def aux(cells: dict) -> dict:
    """보조 지표(§5.3, 판정에 쓰지 않음): 방식별 평균."""
    out = {}
    for a in ARMS:
        runs = [r for (c, s, x), rs in cells.items() if x == a for r in rs]
        ok = [r for r in runs if r.get("cost_check") == "ok"]  # 비용 평균도 §7.4와 같게 비용 검사 ok만
        if not runs or not ok:
            out[a] = {"runs": len(runs), "note": "실행 또는 비용 값 없음"}
            continue
        out[a] = {
            "runs": len(runs),
            "success": sum(r["success"] for r in runs),
            "cost_excluded": len(runs) - len(ok),
            "cost_mean": st.mean(r["cost_usd"] for r in ok),
            # Opus 모델 키 비용 — explore-opus는 explorer도 Opus라 메인 비용과 분리할 수 없다(= 총비용)
            "opus_cost_mean": st.mean(r["cost_by_model"].get("claude-opus-5-5", 0) for r in ok),
            "wall_mean_s": st.mean(r["wall_s"] for r in runs),
            "turns_mean": st.mean(r["turns"] for r in runs),
            "main_lines_mean": st.mean(sum(r["lines_read"].get(k, 0) for k in ("read", "grep", "bash")) for r in runs),
            "sub_lines_mean": st.mean(r["lines_read"].get("sub", 0) for r in runs),
            "explorer_rate": sum("explorer" in (r.get("subagent_types") or []) for r in runs) / len(runs),
            "sub_edit_runs": sum(bool(r.get("sub_edit_paths")) for r in runs),
        }
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--results-dir", required=True, type=Path)
    ap.add_argument("--json", type=Path)
    a = ap.parse_args()
    cells = load(a.results_dir)
    comps = {n: compare(cells, x, y, kind) for n, x, y, kind in COMPARISONS}
    res = {"comparisons": comps, "model_choice": choose_model(comps), "aux": aux(cells)}

    def fmt(x):
        if x is None or x["value"] is None:
            return (x or {}).get("note", "없음")
        return f"{x['value']:.3f}  [{x['ci'][0]:.3f}, {x['ci'][1]:.3f}]  ({x['cells']}칸)"

    for n, c in comps.items():
        s = c["success"]
        print(f"## {n}: {c['A']} ÷ {c['B']}  (공통 칸 {c['common_cells']}, 비용 검사 제외 {c['cost_excluded']})")
        print(f"  R = {fmt(c['R'])}")
        print(f"  R_D = {fmt(c['R_D'])} · R_S = {fmt(c['R_S'])}  (보조 — 둘째 행(R_D)은 해당 없음, 2026-11-05 개정)")
        if s:
            print(f"  성공 A {s['A'][0]}/{s['A'][1]} · B {s['B'][0]}/{s['B'][1]} · 차 {s['diff']:+.3f} "
                  f"[{s['ci'][0]:+.3f}, {s['ci'][1]:+.3f}] → {s['verdict']}  막힘 {c['blocked'] or '없음'}")
        print(f"  결정 표: {c['row']}" + ("  + 성공 손해(따로 기록)" if c["success_loss"] and c["row_no"] != 4 else ""))
        print("  칸 비: " + " ".join(f"{k}={v:.2f}" for k, v in c["cell_ratio"].items()))
    print(f"## 모델 효과 최종 선택(§7.2 R이 작은 쪽): {res['model_choice'] or '없음 — 현행 유지'}")
    print("## 보조")
    for arm, x in res["aux"].items():
        print(f"  {arm}: " + ", ".join(f"{k}={v:.3f}" if isinstance(v, float) else f"{k}={v}" for k, v in x.items()))
    if a.json:
        a.json.write_text(json.dumps(res, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
