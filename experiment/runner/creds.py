"""구독 로그인 파일 사본·되쓰기 (ADR-0005 §6.1, 앞 실험 run_experiment.py 방식).

컨테이너 안 CLI 가 토큰을 갱신(회전)하면 원본의 refresh 토큰이 무효가 될 수 있다. 그래서
① 원본 만료가 가까우면 사본을 만들지 않고 원본 갱신을 기다리고 ② 실행 뒤 사본의 만료가 원본보다 늦으면
그 사본을 원본에 원자적으로 되쓴다. 토큰 값은 읽기만 하고 어디에도 출력하지 않는다(만료 시각만 다룬다).
"""
import fcntl
import json
import os
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


def wait_fresh(origin: Path = ORIGIN, min_left_s: int = MIN_LEFT_S, sleep=time.sleep, now=time.time,
               log=print, max_wait_s: int = 6 * 3600, stopped=lambda: False) -> None:
    """원본 만료까지 min_left_s 미만이면 원본이 갱신될 때까지 기다리기만 한다 — 갱신은 사용자의 호스트 claude 세션이
    한다(러너가 호스트 CLI 를 부르면 호스트 ~/.claude 에 쓰고 병렬 실행끼리 refresh 토큰 회전이 겹친다).
    min_left_s 는 실행 시간 상한보다 길어야 한다(실행 중 컨테이너 사본이 스스로 갱신하지 않게).
    max_wait_s 를 넘기거나 stopped() 가 참이면 예외 — 무한 대기 대신 행(인프라·중단 무효)으로 드러낸다."""
    if expiry(origin) == 0.0:  # 없음·깨짐 — 기다려도 풀리지 않는다(6시간 대기 대신 즉시 인프라 무효)
        raise RuntimeError(f"로그인 파일을 읽을 수 없음(없음 또는 형식 오류): {origin}")
    waited = 0
    while expiry(origin) - now() < min_left_s:
        if expiry(origin) == 0.0:  # 대기 중 원본이 사라지거나 깨짐
            raise RuntimeError(f"로그인 파일을 읽을 수 없음(대기 중 없어짐 또는 형식 오류): {origin}")
        if stopped():
            raise RuntimeError("로그인 파일 갱신 대기 중 중단")
        if waited >= max_wait_s:
            raise RuntimeError(f"로그인 파일 만료 임박이 {max_wait_s // 60}분째 해소되지 않음")
        left = expiry(origin) - now()
        if waited % 60 == 0:
            log(f"[creds] 만료까지 {left / 60:.0f}분 — 원본 갱신 대기(호스트 claude 세션을 한 번 쓰면 갱신된다)", flush=True)
        sleep(10)
        waited += 10


def copy_in(home: Path, origin: Path = ORIGIN, lock: Path = LOCK) -> Path:
    """격리 HOME 에 사본(0600). 되쓰기와 겹치지 않게 같은 잠금 안에서 복사한다."""
    dst = home / ".claude" / ".credentials.json"
    dst.parent.mkdir(parents=True, exist_ok=True)
    with _locked(lock):
        _write_private(dst, origin.read_bytes())
    return dst


def _write_private(dst: Path, data: bytes) -> None:
    """처음부터 0600 으로 만들고(잠깐이라도 넓은 권한 없음) fsync."""
    fd = os.open(dst, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    try:
        os.fchmod(fd, 0o600)
        view = memoryview(data)
        while view:  # os.write 는 일부만 쓸 수 있다
            view = view[os.write(fd, view):]
        os.fsync(fd)
    finally:
        os.close(fd)
    if dst.read_bytes() != data:
        raise OSError(f"쓰기 검증 실패: {dst}")


def write_back(sandbox: Path, origin: Path = ORIGIN, lock: Path = LOCK) -> bool:
    """사본 만료가 원본보다 늦을 때만 임시 파일 + rename 으로 원본을 교체한다. 교체했으면 True."""
    with _locked(lock):
        if expiry(sandbox) <= expiry(origin):
            return False
        problem = invalid_sandbox(sandbox, origin)
        if problem:  # 에이전트·CLI 가 사본을 이상하게 바꿨으면 원본을 지킨다(복구는 재로그인뿐)
            raise ValueError(f"사본 되쓰기 거부: {problem}")
        tmp = origin.with_name(origin.name + ".tmp-ccle")
        _write_private(tmp, sandbox.read_bytes())
        os.replace(tmp, origin)
        dfd = os.open(origin.parent, os.O_RDONLY)
        try:
            os.fsync(dfd)
        finally:
            os.close(dfd)
        return True


def invalid_sandbox(sandbox: Path, origin: Path) -> str:
    """되쓰기 전 검증. 문제 없으면 빈 문자열. 토큰 값은 길이만 본다."""
    try:
        sb, org = json.loads(sandbox.read_text()), json.loads(origin.read_text())
    except Exception as e:
        return f"JSON 아님({type(e).__name__})"
    o = sb.get("claudeAiOauth") if isinstance(sb, dict) else None
    if not isinstance(o, dict):
        return "claudeAiOauth 없음"
    for k in ("accessToken", "refreshToken"):
        if not (isinstance(o.get(k), str) and len(o[k]) >= 16):
            return f"{k} 형식 이상"
    lost = sorted(set(org) - set(sb)) if isinstance(org, dict) else []
    if lost:
        return f"원본 최상위 키 누락 {lost}"
    if not isinstance(o.get("expiresAt"), (int, float)):
        return "expiresAt 형식 이상"
    return ""


def secrets(p: Path) -> list:
    """마스킹용 토큰 문자열(access·refresh). 값은 반환만 하고 출력하지 않는다."""
    try:
        o = json.loads(p.read_text())["claudeAiOauth"]
        return [v for k, v in o.items() if k in ("accessToken", "refreshToken") and isinstance(v, str) and len(v) >= 16]
    except Exception:
        return []
