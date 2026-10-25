"""구독 로그인 파일 사본·되쓰기 (ADR-0005 §6.1, 앞 실험 run_experiment.py 방식).

컨테이너 안 CLI 가 토큰을 갱신(회전)하면 원본의 refresh 토큰이 무효가 될 수 있다. 그래서
① 원본 만료가 가까우면 사본을 만들지 않고 원본 갱신을 기다리고 ② 실행 뒤 사본의 만료가 원본보다 늦으면
그 사본을 원본에 원자적으로 되쓴다. 토큰 값은 읽기만 하고 어디에도 출력하지 않는다(만료 시각만 다룬다).
"""
import fcntl
import json
import os
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

ORIGIN = Path.home() / ".claude" / ".credentials.json"
LOCK = Path.home() / ".cache" / "ccle-runner" / "creds.lock"
MIN_LEFT_S = 45 * 60


def expiry(p: Path) -> float:
    """만료 시각(초). 읽을 수 없으면 0 — 되쓰기 판단에서 '더 늦지 않음'이 되어 원본을 건드리지 않는다."""
    try:
        return json.loads(p.read_text())["claudeAiOauth"]["expiresAt"] / 1000
    except Exception:
        return 0.0


def _locked(lock: Path):
    lock.parent.mkdir(parents=True, exist_ok=True)
    f = open(lock, "w")
    fcntl.flock(f, fcntl.LOCK_EX)
    return f


def host_refresh() -> None:
    """원본이 이미 만료됐을 때 호스트 CLI 로 갱신을 일으킨다(앞 실험 방식 — 하네스 없이 짧은 Haiku 호출 1회).
    갱신은 호스트에서만 일어나게 해 컨테이너 사본과 refresh 토큰 회전이 겹치지 않게 한다."""
    subprocess.run([str(Path.home() / ".local" / "bin" / "claude"), "-p", "ok", "--model", "haiku",
                    "--setting-sources", "project"], cwd=tempfile.gettempdir(), capture_output=True, timeout=180)


def wait_fresh(origin: Path = ORIGIN, min_left_s: int = MIN_LEFT_S, sleep=time.sleep, now=time.time,
               log=print, max_wait_s: int = 6 * 3600, refresh=None, stopped=lambda: False) -> None:
    """원본 만료까지 min_left_s 미만이면 원본이 갱신될 때까지 기다린다(호스트의 claude 세션이 갱신한다).
    이미 만료됐으면 refresh()(주어졌을 때만 — 러너는 host_refresh 를 넘긴다)로 호스트 갱신을 일으킨다. min_left_s 는 실행 시간 상한보다 길어야 한다(실행 중
    컨테이너 사본이 스스로 갱신하지 않게). max_wait_s 를 넘기거나 stopped() 가 참이면 예외 — 무한 대기 대신
    행(인프라·중단 무효)으로 드러낸다."""
    waited = 0
    while expiry(origin) - now() < min_left_s:
        if stopped():
            raise RuntimeError("로그인 파일 갱신 대기 중 중단")
        if waited >= max_wait_s:
            raise RuntimeError(f"로그인 파일 만료 임박이 {max_wait_s // 60}분째 해소되지 않음")
        left = expiry(origin) - now()
        if waited % 60 == 0:
            log(f"[creds] 만료까지 {left / 60:.0f}분 — 원본 갱신 대기", flush=True)
        if left < 0 and refresh is not None:
            refresh()
        sleep(10)
        waited += 10


def copy_in(home: Path, origin: Path = ORIGIN, lock: Path = LOCK) -> Path:
    """격리 HOME 에 사본(0600). 되쓰기와 겹치지 않게 같은 잠금 안에서 복사한다."""
    dst = home / ".claude" / ".credentials.json"
    dst.parent.mkdir(parents=True, exist_ok=True)
    with _locked(lock):
        shutil.copy2(origin, dst)
    os.chmod(dst, 0o600)
    return dst


def write_back(sandbox: Path, origin: Path = ORIGIN, lock: Path = LOCK) -> bool:
    """사본 만료가 원본보다 늦을 때만 임시 파일 + rename 으로 원본을 교체한다. 교체했으면 True."""
    with _locked(lock):
        if expiry(sandbox) <= expiry(origin):
            return False
        tmp = origin.with_name(origin.name + ".tmp-ccle")
        shutil.copy2(sandbox, tmp)
        os.chmod(tmp, 0o600)
        os.replace(tmp, origin)
        return True
