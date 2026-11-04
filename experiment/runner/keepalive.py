"""ADR-0009 무인 실행용 로그인 유지: 원본 로그인 파일 만료가 가까우면 호스트 claude 를 한 번 불러 갱신을 유도한다.

러너는 원본 갱신을 기다리기만 한다(creds.wait_fresh — 갱신은 호스트 세션 몫). 사람이 자리를 비운 동안에도 실행이
이어지도록, 실험 기간에만 이 루프를 띄운다. 러너의 사본 복사·되쓰기와 겹치지 않게 같은 잠금(creds.LOCK) 안에서 부른다.
토큰 값은 읽지 않는다(만료 시각만).

  python3 experiment/runner/keepalive.py [--below-min 6] [--every 60]
"""
import argparse
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import creds  # noqa: E402

PING = ["claude", "-p", "ok", "--model", "haiku"]


def left_min(origin: Path = creds.ORIGIN) -> float:
    return (creds.expiry(origin) - time.time()) / 60


def tick(below_min: float, run=subprocess.run, log=print, origin: Path = creds.ORIGIN, lock: Path = creds.LOCK):
    """만료까지 below_min 분 미만이면 잠금 안에서 1회 호출. 부르지 않았으면 None, 불렀으면 갱신됐는지(만료가 5분 넘게 늘었나)."""
    before = left_min(origin)
    if before >= below_min:
        return None
    with creds._locked(lock):
        p = run(PING, cwd="/tmp", capture_output=True, text=True, timeout=180)
    after = left_min(origin)
    refreshed = after > before + 5
    log(f"{datetime.now():%H:%M:%S} keepalive rc={p.returncode} 만료까지 {before:.1f}분 → {after:.1f}분 "
        f"{'갱신' if refreshed else '갱신 안 됨'}", flush=True)
    return refreshed


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--below-min", type=float, required=True, help="호스트 CLI 갱신 시점 실측으로 정한다(ADR-0009 §8)")
    ap.add_argument("--every", type=int, default=60)
    a = ap.parse_args()
    while True:
        try:
            tick(a.below_min)
        except Exception as e:  # 한 번 실패로 루프가 죽지 않게 — 드러내고 다음 주기에 다시
            print(f"{datetime.now():%H:%M:%S} keepalive 오류: {e!r:.300}", flush=True)
        time.sleep(a.every)


if __name__ == "__main__":
    raise SystemExit(main())
