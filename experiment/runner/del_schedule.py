"""ADR-0009 실행 순서: 칸(과제×구조)마다 방식 순서를 섞어 러너를 한 실행씩 부른다.

러너 한 번 = 방식 하나의 한 실행(--parallel 1). 반복마다 칸 순서를 고정 시드로 섞고, 칸 안의 방식 순서는
(반복 + 칸 번호)만큼 회전한다 — 시간대·캐시 상태가 한 방식에 몰리지 않게(ADR-0009 §8). 러너가 valid 행이 있는
키를 건너뛰므로 다시 부르면 무효·미실행만 돈다(재실행 상한 = --passes).

  python3 experiment/runner/del_schedule.py --phase pilot --tasks D1,D2,D3 --s-tasks S1,S2,S3,S4 --results-dir … [--dry-run]
  python3 experiment/runner/del_schedule.py --phase main --tasks S1,S2,S3,S4,D1,D2,D3 --results-dir … [--dry-run]
"""
import argparse
import random
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ARMS = ("solo", "explore-haiku", "explore-sonnet", "explore-opus")
STRUCTURES = ("god", "layered")
SEED = 20261103
MAIN_STAGES = "public,hidden"


def jobs(phase: str, tasks: list, s_tasks: list = (), seed: int = SEED) -> list:
    """(rep, case, structure, arm) 순서 목록. pilot = 4방식 × tasks × 1회 + solo × s_tasks × 1회(탐색량 기준), main = 4방식 × tasks × 3회."""
    rng = random.Random(seed)
    out = []
    reps = (1,) if phase == "pilot" else (1, 2, 3)
    for rep in reps:
        cells = [(c, s) for c in tasks for s in STRUCTURES]
        rng.shuffle(cells)
        for i, (c, s) in enumerate(cells):
            k = (rep + i) % len(ARMS)
            for arm in ARMS[k:] + ARMS[:k]:
                out.append((rep, c, s, arm))
    if phase == "pilot":
        out += [(1, c, s, "solo") for c in s_tasks for s in STRUCTURES]
    return out


def argv(job: tuple, results: str, phase: str) -> list:
    rep, c, s, arm = job
    a = [sys.executable, str(HERE / "runner.py"), "run", "--model", "opus", "--arm", arm, "--cases", c,
         "--structures", s, "--reps", str(rep), "--parallel", "1", "--timeout", "1800", "--min-mem-gb", "12",
         "--results-dir", results]
    if phase == "main":
        a += ["--stages", MAIN_STAGES]
    return a


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--phase", choices=("pilot", "main"), required=True)
    ap.add_argument("--tasks", required=True)
    ap.add_argument("--s-tasks", default="")
    ap.add_argument("--results-dir", required=True)
    ap.add_argument("--passes", type=int, default=3, help="전체 목록을 몇 번 훑나(1회 + 재실행 상한 2)")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    js = jobs(a.phase, a.tasks.split(","), [x for x in a.s_tasks.split(",") if x])
    print(f"jobs={len(js)} phase={a.phase} results={a.results_dir}", flush=True)
    if a.dry_run:
        for j in js:
            print(*j)
        return 0
    for p in range(1, a.passes + 1):
        for j in js:
            r = subprocess.run(argv(j, a.results_dir, a.phase))
            if r.returncode == 130:  # 러너 중단(SIGINT·SIGTERM) — 스케줄도 멈춘다
                print("중단", flush=True)
                return 130
        print(f"pass {p} 끝", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
