#!/usr/bin/env python3
"""과제 러너 (ADR-0005 §6). (과제, 구조, 모델, 반복) 하나 = 실행 전용 도커 네트워크 하나:
1k 시드 DB 컨테이너(별칭 db) + 가짜 PG 컨테이너 + 에이전트 컨테이너(가짜 PG 컨테이너의 네트워크 네임스페이스 공유 —
가짜 PG 가 127.0.0.1 에만 바인드하므로). 에이전트 컨테이너에는 작업 사본·격리 HOME·Gradle 읽기 전용 캐시만 마운트한다.
결과는 <results>/runs.jsonl 에 시도마다 1행(무효 포함).

사용:
  python3 experiment/runner/runner.py prep            # 가짜 PG jar 디렉터리 + Gradle 읽기 전용 캐시(온라인 1회)
  python3 experiment/runner/runner.py run --model haiku --cases _smoke --structures god --reps 1 [--parallel 3]
  python3 experiment/runner/runner.py shell --case _smoke --structure god -- 'bash 명령'   # 에이전트 대신 셸(무료 인프라 확인)
  python3 experiment/runner/runner.py cleanup         # 남은 러너 컨테이너·네트워크 삭제
판정(빌드·공개·숨은·회귀)은 아직 없다 — judge() 자리만 있다(과제 작업에서 구현).
"""
import argparse
import concurrent.futures as cf
import fcntl
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import creds  # noqa: E402
import events  # noqa: E402

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
CASES = ROOT / "experiment" / "cases"
ORACLE = ROOT / "experiment" / "oracle"
STRUCTURES = ("god", "layered")

CACHE = Path.home() / ".cache" / "ccle-runner"
RUNS = CACHE / "runs"
GRADLE_RO = CACHE / "gradle-ro"          # modules-2 만 — GRADLE_RO_DEP_CACHE 로 읽기 전용 마운트
GRADLE_SEED = CACHE / "gradle-seed"      # prep 이 온라인으로 채우는 쓰기 가능 GRADLE_USER_HOME(에이전트에는 마운트 안 함)
FAKEPG = CACHE / "fakepg"
# 예열 템플릿: 두 구조를 차례로 빌드한 격리 HOME 의 .gradle(wrapper 배포판 + caches/<버전> — Gradle API·스크립트·변환 캐시).
# 앱 컴파일 결과는 사본 build/ 에 남으므로 여기에 없다. 없으면 실행마다 첫 빌드가 ~2분 더 걸린다(스모크 실측 126초 → 20초).
GRADLE_WARM = CACHE / "gradle-warm"

CLI_VERSION = "2.1.308"
IMAGE = f"ccle-agent:cli-{CLI_VERSION}"
DB_IMAGE = "ccle-postgres-seed:1k-s42"
GRADLE_VERSION = "8.14.4"
GRADLE_DIST = f"/opt/gradle/gradle-{GRADLE_VERSION}-bin.zip"
PG_PORT = 15480
LABEL = "ccle.runner"
MIN_FREE_GB = 10
TZ = "Asia/Seoul"

AGENT_HOME, AGENT_WORK, AGENT_GRADLE_RO = "/home/agent", "/work", "/gradle-ro"
AGENT_ENV = {
    "HOME": AGENT_HOME, "TZ": TZ,
    "GRADLE_USER_HOME": f"{AGENT_HOME}/.gradle", "GRADLE_RO_DEP_CACHE": AGENT_GRADLE_RO,
    "DB_URL": "jdbc:postgresql://db:5432/ccle_seed", "DB_USER": "ccle", "DB_PASSWORD": "ccle",
    "PG_URL": f"http://127.0.0.1:{PG_PORT}", "PG_TIMEOUT_MS": "2000",
}

ENV_NOTE = ("빌드·테스트는 ./gradlew 로 한다(JDK 21과 의존성은 준비돼 있고 외부 네트워크는 쓸 수 없다). "
            "DB(PostgreSQL)·결제 대행(PG) 접속 정보는 환경 변수 DB_URL·DB_USER·DB_PASSWORD·PG_URL 에 있다.\n")
DONE_RULE = "완료 기준: 현재 디렉터리에서 `bash test.sh` 가 통과한다. 테스트 파일과 test.sh 는 수정하지 마라.\n"
SCOPE = "작업은 현재 디렉터리 안에서만 하라."
PROMPTS = {
    "bug": ("현재 디렉터리의 프로젝트(Kotlin·Spring Boot 백엔드)에 버그 보고가 들어왔다. TASK.md 를 읽고 문제의 근본 원인을 찾아 고쳐라.\n"
            + DONE_RULE + ENV_NOTE + SCOPE + " 끝나면 원인과 수정 내용을 3줄 이내로 요약하라."),
    "change": ("현재 디렉터리의 프로젝트(Kotlin·Spring Boot 백엔드)에 변경 요청이 들어왔다. TASK.md 를 읽고 요청을 구현하라.\n"
               + DONE_RULE + ENV_NOTE + SCOPE + " 끝나면 구현 내용을 3줄 이내로 요약하라."),
    "question": ("현재 디렉터리의 프로젝트(Kotlin·Spring Boot 백엔드)에 대한 질문이 들어왔다. TASK.md 를 읽고 코드를 조사해 답하라.\n"
                 "코드는 수정하지 마라. 답은 TASK.md 가 정한 형식으로 현재 디렉터리의 ANSWER.md 에 써라.\n"
                 + ENV_NOTE + SCOPE),
}
PROMPTS["partial"] = PROMPTS["bug"]
SETTINGS = {"permissions": {"deny": ["WebFetch", "WebSearch"]}}
COPY_IGNORE = shutil.ignore_patterns("build", ".gradle", ".kotlin", "*.log")
CASE_PRIVATE = {"TYPE"}  # 사본에 넣지 않는 과제 파일

_append_lock = threading.Lock()
_live: set = set()          # 진행 중 run_id — 중단 시 일괄 정리
_live_lock = threading.Lock()
_stop = threading.Event()


# ---------------------------------------------------------------- 공통

def sh(args, check=True, timeout=600, **kw) -> subprocess.CompletedProcess:
    p = subprocess.run(args, capture_output=True, text=True, timeout=timeout, **kw)
    if check and p.returncode != 0:
        raise RuntimeError(f"{' '.join(map(str, args))[:200]} → rc={p.returncode}: {(p.stderr or p.stdout)[-500:]}")
    return p


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def case_type(case: str) -> str:
    t = (CASES / case / "TYPE").read_text().strip()
    if t not in PROMPTS:
        raise ValueError(f"{case}: 알 수 없는 TYPE {t!r}")
    return t


# ---------------------------------------------------------------- 작업 사본

def build_work(case: str, structure: str, work: Path) -> None:
    """두 구조가 같은 모양의 사본: app/(그 구조의 src) + 공통 루트 gradle + wrapper(배포판은 이미지 안 zip) + 과제 공개 파일.
    docs·behavior-tests·tools·README·.git·다른 구조는 넣지 않는다(ADR §6.2)."""
    if structure not in STRUCTURES:
        raise ValueError(structure)
    work.mkdir(parents=True)
    shutil.copytree(ROOT / structure / "src", work / "app" / "src", ignore=COPY_IGNORE)
    for name in ("settings.gradle.kts", "build.gradle.kts"):
        shutil.copy2(HERE / "templates" / name, work / name)
    for name in ("gradlew", "gradlew.bat"):
        shutil.copy2(ROOT / name, work / name)
    (work / "gradle" / "wrapper").mkdir(parents=True)
    shutil.copy2(ROOT / "gradle" / "wrapper" / "gradle-wrapper.jar", work / "gradle" / "wrapper" / "gradle-wrapper.jar")
    props = (ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties").read_text()
    props, n = re.subn(r"(?m)^distributionUrl=.*$", f"distributionUrl=file\\:{GRADLE_DIST}", props)
    if n != 1:
        raise RuntimeError("gradle-wrapper.properties 에 distributionUrl 이 1개가 아님")
    (work / "gradle" / "wrapper" / "gradle-wrapper.properties").write_text(props)
    src = CASES / case
    for f in sorted(src.rglob("*")):
        rel = f.relative_to(src)
        if f.is_file() and rel.parts[0] not in CASE_PRIVATE:
            (work / rel).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(f, work / rel)
    inject = ORACLE / case / f"inject.{structure}.patch"
    if inject.is_file():
        sh(["patch", "-p1", "--forward", "--no-backup-if-mismatch", "-i", str(inject)], cwd=work)


def make_home(home: Path, timeout_s: int) -> Path:
    """격리 HOME: settings.json(웹 도구 금지) + Gradle 사용자 홈(오프라인 init·데몬 끔) + 로그인 파일 사본."""
    (home / ".claude").mkdir(parents=True)
    (home / ".claude" / "settings.json").write_text(json.dumps(SETTINGS))
    g = home / ".gradle"
    if GRADLE_WARM.is_dir():
        shutil.copytree(GRADLE_WARM, g, symlinks=True)
    (g / "init.d").mkdir(parents=True, exist_ok=True)
    shutil.copy2(HERE / "templates" / "offline.gradle", g / "init.d" / "offline.gradle")
    shutil.copy2(HERE / "templates" / "gradle.properties", g / "gradle.properties")
    # 실행이 끝날 때까지 사본이 만료되지 않게(시간 상한 + 10분 여유) — 실행 중 컨테이너가 스스로 갱신하지 않도록
    creds.wait_fresh(min_left_s=timeout_s + 600, refresh=creds.host_refresh, stopped=_stop.is_set)
    return creds.copy_in(home)


# ---------------------------------------------------------------- 도커

def allowed_mounts(work: Path, home: Path) -> set:
    return {(str(work), AGENT_WORK, True), (str(home), AGENT_HOME, True), (str(GRADLE_RO), AGENT_GRADLE_RO, False)}


def check_mounts(inspected: list, allowed: set) -> list:
    """docker inspect .Mounts 가 허용 목록과 정확히 같은지. 차이 목록(빈 목록 = 통과)."""
    got = {(m.get("Source"), m.get("Destination"), bool(m.get("RW"))) for m in inspected}
    return sorted(f"extra:{x}" for x in got - allowed) + sorted(f"missing:{x}" for x in allowed - got)


def remove_run(name: str) -> None:
    for c in (f"{name}-agent", f"{name}-pg", f"{name}-db"):
        subprocess.run(["docker", "rm", "-f", c], capture_output=True)
    subprocess.run(["docker", "network", "rm", name], capture_output=True)


def start_services(name: str) -> None:
    lab = ["--label", f"{LABEL}={name}"]
    sh(["docker", "network", "create", *lab, name])
    sh(["docker", "run", "-d", "--name", f"{name}-db", *lab, "--network", name, "--network-alias", "db",
        "--shm-size=1g", DB_IMAGE])
    sh(["docker", "run", "-d", "--name", f"{name}-pg", *lab, "--network", name,
        "-v", f"{FAKEPG}:/fakepg:ro", "-e", f"PG_PORT={PG_PORT}", "-e", "PG_DELAY_MS=3000", "-e", f"TZ={TZ}",
        IMAGE, "java", "-cp", "/fakepg/*", "com.ccle.behavior.pg.FakePgKt"])
    deadline = time.time() + 120
    db_ok = pg_ok = False
    while time.time() < deadline and not (db_ok and pg_ok):
        if not db_ok:
            db_ok = subprocess.run(["docker", "exec", f"{name}-db", "pg_isready", "-U", "ccle", "-d", "ccle_seed", "-q"],
                                   capture_output=True).returncode == 0
        if not pg_ok:
            pg_ok = "fake-pg ready" in sh(["docker", "logs", f"{name}-pg"], check=False).stdout
        time.sleep(0.5)
    if not (db_ok and pg_ok):
        raise RuntimeError(f"서비스 기동 실패 db={db_ok} pg={pg_ok}: {sh(['docker', 'logs', f'{name}-pg'], check=False).stderr[-300:]}")
    sh(["docker", "exec", f"{name}-db", "psql", "-U", "ccle", "-d", "ccle_seed", "-Atqc", "SELECT 1"])


def create_agent(name: str, work: Path, home: Path, argv: list) -> None:
    env = [x for k, v in AGENT_ENV.items() for x in ("-e", f"{k}={v}")]
    sh(["docker", "create", "--name", f"{name}-agent", "--label", f"{LABEL}={name}",
        "--network", f"container:{name}-pg", "--user", f"{os.getuid()}:{os.getgid()}",
        "--memory", "8g", "--pids-limit", "4096",
        "-v", f"{work}:{AGENT_WORK}", "-v", f"{home}:{AGENT_HOME}", "-v", f"{GRADLE_RO}:{AGENT_GRADLE_RO}:ro",
        *env, "-w", AGENT_WORK, IMAGE, *argv])
    mounts = json.loads(sh(["docker", "inspect", "-f", "{{json .Mounts}}", f"{name}-agent"]).stdout)
    bad = check_mounts(mounts, allowed_mounts(work, home))
    hc = json.loads(sh(["docker", "inspect", "-f", "{{json .HostConfig}}", f"{name}-agent"]).stdout)
    if hc.get("Privileged") or hc.get("CapAdd"):
        bad.append(f"hostconfig:privileged={hc.get('Privileged')} capadd={hc.get('CapAdd')}")
    if bad:
        raise RuntimeError(f"마운트 허용 목록 불일치 — 시작 안 함: {bad}")


def attach_agent(name: str, out: Path, err: Path, timeout_s: int) -> tuple:
    """(rc, timed_out, wall). 시간 상한이면 컨테이너를 kill 한다(러너 종료 — censored)."""
    t0 = time.time()
    with open(out, "wb") as fo, open(err, "wb") as fe:
        p = subprocess.Popen(["docker", "start", "-a", f"{name}-agent"], stdin=subprocess.DEVNULL, stdout=fo, stderr=fe)
        try:
            rc = p.wait(timeout=timeout_s)
            timed_out = False
        except subprocess.TimeoutExpired:
            subprocess.run(["docker", "kill", f"{name}-agent"], capture_output=True)
            rc = p.wait(timeout=60)
            timed_out = True
    return rc, timed_out, time.time() - t0


def image_id(img: str) -> str:
    return sh(["docker", "image", "inspect", "-f", "{{.Id}}", img]).stdout.strip()


# ---------------------------------------------------------------- 결과

def append_row(results: Path, row: dict) -> None:
    results.mkdir(parents=True, exist_ok=True)
    with _append_lock, open(results / "runs.jsonl", "a", encoding="utf-8") as f:
        fcntl.flock(f, fcntl.LOCK_EX)
        f.write(json.dumps(row, ensure_ascii=False) + "\n")


def done_keys(results: Path) -> set:
    keys = set()
    p = results / "runs.jsonl"
    if p.exists():
        for line in p.read_text().splitlines():
            if line.strip():
                r = json.loads(line)
                if r.get("valid"):
                    keys.add((r["case"], r["structure"], r["model"], r["rep"]))
    return keys


def jobs_for(cases: list, structures: list, model: str, reps: list, done: set) -> list:
    """구조 순서를 과제·반복마다 돌린다(같은 구조가 늘 먼저 돌지 않게). valid 행이 있으면 건너뛴다(재개)."""
    out = []
    for rep in reps:
        for i, c in enumerate(cases):
            k = (i + rep) % len(structures)
            for s in structures[k:] + structures[:k]:
                if (c, s, model, rep) not in done:
                    out.append((c, s, rep))
    return out


def write_diff(base: Path, work: Path, dst: Path) -> int:
    p = subprocess.run(["diff", "-ruN", "--exclude=build", "--exclude=.gradle", "--exclude=.kotlin", str(base), str(work)],
                       capture_output=True)
    if p.returncode > 1:
        raise RuntimeError(f"diff 실패: {p.stderr[-300:]!r}")
    txt = p.stdout.replace(str(base).encode(), b"a").replace(str(work).encode(), b"b")
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_bytes(txt)
    return len(txt)


def judge(case: str, structure: str, work: Path) -> dict:
    """판정 자리(ADR §4) — 과제 작업에서 구현한다. 지금은 판정하지 않았다는 사실만 남긴다."""
    return {"judged": False}


def symbols(case: str, structure: str) -> list:
    p = ORACLE / case / f"symbols.{structure}.txt"
    return events.parse_symbols(p.read_text()) if p.is_file() else []


def classify(evs: list, rc: int, timed_out: bool, violations: list) -> dict:
    """valid = (result ∨ 러너 시간 상한) ∧ 감사 통과 ∧ 사용량 한도 아님(ADR §6.4). 인프라 오류는 run_one 이 따로."""
    reason = events.end_reason(evs, timed_out)
    invalid = None
    if violations:
        invalid = "audit"
    elif reason == "usage_limit":
        invalid = "usage_limit"
    elif reason == "no_result":
        invalid = "no_result"
    return {"end_reason": reason, "censored": reason in ("timeout", "budget"), "valid": invalid is None,
            "invalid_reason": invalid}


def tail(p: Path, n: int = 1500) -> str:
    try:
        return p.read_bytes()[-n:].decode(errors="replace")
    except OSError:
        return ""


def run_one(case: str, structure: str, rep: int, model: str, results: Path, budget: str, timeout_s: int,
            argv_override: list = None, keep: bool = False) -> dict:
    run_id = f"{case}-{structure}-{model}-r{rep}-{int(time.time() * 1000) % 10**10}"
    name = f"ccle-{run_id}".lower()
    rd = RUNS / run_id
    raw = results / "raw"
    raw.mkdir(parents=True, exist_ok=True)
    out, err = raw / f"{run_id}.jsonl", raw / f"{run_id}.stderr"
    row = {"run_id": run_id, "case": case, "structure": structure, "model": model, "rep": rep,
           "started_at": now_iso(), "image": IMAGE, "cli_version": CLI_VERSION, "budget_usd": budget,
           "timeout_s": timeout_s, "shell": bool(argv_override)}
    with _live_lock:
        _live.add(name)
    rd.mkdir(parents=True)
    # 시도 표식: 러너가 강제 종료(SIGKILL·부모 셸 종료)돼 finally 가 못 돌면 sweep_orphans 가 이것으로 행을 남긴다
    (rd / "attempt.json").write_text(json.dumps({"row": row, "results": str(results)}, ensure_ascii=False))
    alive = open(rd / "attempt.lock", "w")
    fcntl.flock(alive, fcntl.LOCK_EX)
    try:
        free = shutil.disk_usage(Path.home()).free / 2**30
        row["disk_free_gb"] = round(free, 1)
        if free < MIN_FREE_GB:
            row.update(valid=False, invalid_reason="infra", infra_error=f"여유 디스크 {free:.1f}GB < {MIN_FREE_GB}GB")
            return row
        if _stop.is_set():
            row.update(valid=False, invalid_reason="interrupted")
            return row
        row["image_id"] = image_id(IMAGE)
        build_work(case, structure, rd / "work")
        shutil.copytree(rd / "work", rd / "base", symlinks=True)
        sb_creds = make_home(rd / "home", timeout_s)
        prompt = PROMPTS[case_type(case)]
        argv = argv_override or ["claude", "-p", prompt, "--model", model, "--output-format", "stream-json",
                                 "--verbose", "--max-budget-usd", budget, "--permission-mode", "bypassPermissions"]
        start_services(name)
        create_agent(name, rd / "work", rd / "home", argv)
        rc, timed_out, wall = attach_agent(name, out, err, timeout_s)
        row.update(rc=rc, timed_out=timed_out, wall_s=round(wall, 1))
        row["creds_written_back"] = creds.write_back(sb_creds)
        if _stop.is_set():
            row.update(valid=False, invalid_reason="interrupted", tail=tail(err) or tail(out))
            return row
        row["diff_bytes"] = write_diff(rd / "base", rd / "work", results / "diffs" / f"{run_id}.diff")
        row["diff"] = str((results / "diffs" / f"{run_id}.diff").relative_to(results))
        row["events"] = str(out.relative_to(results))
        if argv_override:  # 셸 모드: 이벤트 파싱 없이 출력만
            row.update(valid=False, invalid_reason="shell", tail=tail(out, 4000), stderr_tail=tail(err))
            return row
        lines = out.read_text(errors="replace").splitlines()
        evs, bad = events.load(lines)
        cs, turns = events.calls(evs)
        res = next((e for e in reversed(evs) if e.get("type") == "result"), {}) or {}
        violations = events.audit(cs)
        row.update(bad_event_lines=bad, init=events.init_info(evs), turns=turns, result_turns=res.get("num_turns"),
                   result_subtype=res.get("subtype"), is_error=res.get("is_error"),
                   duration_ms=res.get("duration_ms"), **events.tokens(evs),
                   **events.metrics(cs, symbols(case, structure)), audit_violations=violations,
                   **classify(evs, rc, timed_out, violations))
        if not row["valid"] or row["end_reason"] != "normal":
            row["tail"] = tail(err) or tail(out)
        row.update(judge(case, structure, rd / "work"))
        return row
    except Exception as e:  # 러너·인프라 오류도 행으로 남긴다(무음 제외 금지)
        row.update(valid=False, invalid_reason="interrupted" if _stop.is_set() else "infra", infra_error=repr(e)[:800],
                   tail=tail(err) or tail(out))
        return row
    finally:
        remove_run(name)
        with _live_lock:
            _live.discard(name)
        row["finished_at"] = now_iso()
        append_row(results, row)
        alive.close()
        if not keep:
            rm_tree(rd)


def rm_tree(d: Path) -> None:
    subprocess.run(["chmod", "-R", "u+w", str(d)], capture_output=True)
    shutil.rmtree(d, ignore_errors=True)


def sweep_orphans() -> int:
    """잠금이 풀린 시도 디렉터리 = 러너가 행을 남기지 못하고 죽은 시도. 행(invalid_reason=orphan)을 남기고
    그 실행의 컨테이너·네트워크·디렉터리(로그인 파일 사본 포함)를 지운다. 무음 누락 방지."""
    n = 0
    if not RUNS.is_dir():
        return 0
    for d in sorted(RUNS.iterdir()):
        meta = d / "attempt.json"
        if not meta.is_file():
            continue  # prep 등 시도가 아닌 디렉터리
        with open(d / "attempt.lock", "a") as lk:
            try:
                fcntl.flock(lk, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                continue  # 진행 중
            m = json.loads(meta.read_text())
            row = {**m["row"], "valid": False, "invalid_reason": "orphan",
                   "infra_error": "러너가 행을 남기지 못하고 종료됨(sweep 으로 기록)", "finished_at": now_iso()}
            append_row(Path(m["results"]), row)
        remove_run(f"ccle-{row['run_id']}".lower())
        rm_tree(d)
        n += 1
    return n


# ---------------------------------------------------------------- 명령

def cmd_prep(a) -> int:
    """가짜 PG: behavior-tests 기존 gradle 태스크 산출물을 jar 디렉터리로. Gradle 캐시: 두 구조 사본을 온라인으로
    한 번 빌드해 seed 홈을 채우고 modules-2 만 읽기 전용 캐시로 복사(잠금·gc 파일 제외 — Gradle 문서의 RO 캐시 조건).
    예열 템플릿: 네트워크 없는 컨테이너에서 실제 실행과 같은 설정으로 두 구조를 빌드(오프라인 빌드 확인을 겸한다)."""
    java_home = os.environ.get("JAVA_HOME") or str(Path.home() / ".sdkman/candidates/java/21.0.5-tem")
    env = {**os.environ, "JAVA_HOME": java_home}
    sh(["./gradlew", "-q", ":behavior-tests:fakePgClasspath"], cwd=ROOT, env=env, timeout=1800)
    cp = (ROOT / "behavior-tests" / "build" / "fake-pg.classpath").read_text().strip().split(os.pathsep)
    tmp = FAKEPG.with_name("fakepg.tmp")
    shutil.rmtree(tmp, ignore_errors=True)
    tmp.mkdir(parents=True)
    for entry in cp:
        p = Path(entry)
        if p.is_dir():
            if any(p.iterdir()):
                sh([f"{java_home}/bin/jar", "cf", str(tmp / f"bt-{p.parent.name}-{p.name}.jar"), "-C", str(p), "."])
        elif p.suffix == ".jar":
            shutil.copy2(p, tmp / p.name)
    shutil.rmtree(FAKEPG, ignore_errors=True)
    tmp.rename(FAKEPG)
    print(f"fakepg: {sorted(x.name for x in FAKEPG.iterdir())}")

    GRADLE_SEED.mkdir(parents=True, exist_ok=True)
    for s in STRUCTURES:
        d = RUNS / f"prep-{s}"
        shutil.rmtree(d, ignore_errors=True)
        build_work(a.case, s, d)
        p = subprocess.run(["docker", "run", "--rm", "--user", f"{os.getuid()}:{os.getgid()}",
                            "-v", f"{d}:/work", "-v", f"{GRADLE_SEED}:/seed", "-e", "GRADLE_USER_HOME=/seed",
                            "-e", f"TZ={TZ}", "-w", "/work", IMAGE, "./gradlew", "--no-daemon", "-q",
                            ":app:bootJar", ":app:testClasses"], capture_output=True, text=True, timeout=3600)
        shutil.rmtree(d, ignore_errors=True)
        if p.returncode:
            raise SystemExit(f"prep {s} 빌드 실패: {p.stdout[-800:]}{p.stderr[-800:]}")
        print(f"gradle seed: {s} ok")
    _replace_dir(GRADLE_RO, lambda tmp: shutil.copytree(GRADLE_SEED / "caches" / "modules-2", tmp / "modules-2",
                                                         ignore=shutil.ignore_patterns("*.lock", "gc.properties")))
    print(f"gradle-ro: {sh(['du', '-sh', str(GRADLE_RO)]).stdout.split()[0]}")

    # 예열: 실제 실행과 같은 마운트(RO 캐시)·오프라인 설정의 새 HOME 에서 god → layered 를 빌드하고 그 .gradle 을 템플릿으로
    home = RUNS / "prep-home"
    shutil.rmtree(home, ignore_errors=True)
    g = home / ".gradle"
    (g / "init.d").mkdir(parents=True)
    shutil.copy2(HERE / "templates" / "offline.gradle", g / "init.d" / "offline.gradle")
    shutil.copy2(HERE / "templates" / "gradle.properties", g / "gradle.properties")
    for s in STRUCTURES:
        d = RUNS / f"prep-{s}"
        shutil.rmtree(d, ignore_errors=True)
        build_work(a.case, s, d)
        p = subprocess.run(["docker", "run", "--rm", "--network", "none", "--user", f"{os.getuid()}:{os.getgid()}",
                            "-v", f"{d}:/work", "-v", f"{home}:{AGENT_HOME}", "-v", f"{GRADLE_RO}:{AGENT_GRADLE_RO}:ro",
                            *[x for k in ("HOME", "TZ", "GRADLE_USER_HOME", "GRADLE_RO_DEP_CACHE") for x in ("-e", f"{k}={AGENT_ENV[k]}")],
                            "-w", "/work", IMAGE, "./gradlew", "-q", ":app:bootJar", ":app:testClasses"],
                           capture_output=True, text=True, timeout=3600)
        shutil.rmtree(d, ignore_errors=True)
        if p.returncode:
            raise SystemExit(f"prep 예열 {s} 빌드 실패(오프라인): {p.stdout[-800:]}{p.stderr[-800:]}")
        print(f"gradle warm: {s} ok(network none)")
    def keep_warm(tmp):
        tmp.mkdir()
        for sub in ("wrapper", f"caches/{GRADLE_VERSION}"):
            shutil.copytree(g / sub, tmp / sub, symlinks=True,
                            ignore=shutil.ignore_patterns("*.lock", "gc.properties", "*.out.log"))
    _replace_dir(GRADLE_WARM, keep_warm)
    shutil.rmtree(home, ignore_errors=True)
    print(f"gradle-warm: {sh(['du', '-sh', str(GRADLE_WARM)]).stdout.split()[0]}")
    return 0


def _replace_dir(dst: Path, fill) -> None:
    """임시 디렉터리를 채운 뒤 교체한다(중간에 실패해도 반쯤 찬 dst 가 남지 않게)."""
    tmp = dst.with_name(dst.name + ".tmp")
    if tmp.exists():
        sh(["chmod", "-R", "u+w", str(tmp)])
        shutil.rmtree(tmp)
    tmp.parent.mkdir(parents=True, exist_ok=True)
    fill(tmp)
    if dst.exists():
        sh(["chmod", "-R", "u+w", str(dst)])
        shutil.rmtree(dst)
    tmp.rename(dst)


def cmd_cleanup(_a) -> int:
    print(f"orphans={sweep_orphans()}")
    ids = sh(["docker", "ps", "-aq", "--filter", f"label={LABEL}"], check=False).stdout.split()
    if ids:
        sh(["docker", "rm", "-f", *ids], check=False)
    nets = sh(["docker", "network", "ls", "-q", "--filter", f"label={LABEL}"], check=False).stdout.split()
    if nets:
        sh(["docker", "network", "rm", *nets], check=False)
    print(f"containers={len(ids)} networks={len(nets)}")
    return 0


def _on_signal(signum, _frame):
    _stop.set()
    with _live_lock:
        names = list(_live)
    for n in names:  # 진행 중 실행의 컨테이너를 먼저 지워 대기 중인 docker start -a 가 풀리게 한다
        subprocess.run(["docker", "rm", "-f", f"{n}-agent"], capture_output=True)
    print(f"[runner] 신호 {signum} — 진행 중 {len(names)}개 중단·정리", flush=True)


def cmd_run(a) -> int:
    signal.signal(signal.SIGINT, _on_signal)
    signal.signal(signal.SIGTERM, _on_signal)
    results = Path(a.results_dir).resolve()
    cases = a.cases.split(",")
    structures = a.structures.split(",")
    for c in cases:
        case_type(c)
    for s in structures:
        if s not in STRUCTURES:
            raise SystemExit(f"구조는 {STRUCTURES}")
    orphans = sweep_orphans()
    if orphans:
        print(f"[runner] 이전 고아 시도 {orphans}개를 orphan 행으로 기록·정리", flush=True)
    jobs = jobs_for(cases, structures, a.model, [int(r) for r in a.reps.split(",")], done_keys(results))
    print(f"jobs={len(jobs)} parallel={a.parallel} results={results}", flush=True)
    bad = 0

    def one(job):
        c, s, rep = job
        for attempt in range(1, a.limit_retries + 2):
            r = run_one(c, s, rep, a.model, results, a.budget, a.timeout, keep=a.keep)
            if r.get("invalid_reason") != "usage_limit" or attempt > a.limit_retries or _stop.is_set():
                return r
            print(f"{c} {s} r{rep}: 사용량 한도 — {a.limit_wait}s 대기 후 재시도({attempt})", flush=True)
            _stop.wait(a.limit_wait)
        return r

    with cf.ThreadPoolExecutor(max_workers=a.parallel) as ex:
        futs = {ex.submit(one, j): j for j in jobs}
        for f in cf.as_completed(futs):
            c, s, rep = futs[f]
            r = f.result()
            bad += not r.get("valid")
            print(f"{c} {s} r{rep}: valid={r.get('valid')} reason={r.get('invalid_reason') or r.get('end_reason')} "
                  f"tokens={r.get('total_tokens')} turns={r.get('turns')} wall={r.get('wall_s')} "
                  f"viol={r.get('audit_violations')}", flush=True)
    return 130 if _stop.is_set() else (1 if bad else 0)


def cmd_shell(a) -> int:
    signal.signal(signal.SIGINT, _on_signal)
    signal.signal(signal.SIGTERM, _on_signal)
    r = run_one(a.case, a.structure, 0, "shell", Path(a.results_dir).resolve(), "0", a.timeout,
                argv_override=["bash", "-c", a.command], keep=a.keep)
    print(r.get("tail", ""))
    print(json.dumps({k: v for k, v in r.items() if k not in ("tail",)}, ensure_ascii=False))
    return 0 if r.get("infra_error") is None and r.get("rc") == 0 else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("prep")
    p.add_argument("--case", default="_smoke")
    sub.add_parser("cleanup")
    p = sub.add_parser("run")
    p.add_argument("--model", required=True)
    p.add_argument("--cases", required=True)
    p.add_argument("--structures", default=",".join(STRUCTURES))
    p.add_argument("--reps", default="1,2,3")
    p.add_argument("--parallel", type=int, default=3)
    p.add_argument("--budget", default="5")
    p.add_argument("--timeout", type=int, default=2700)
    p.add_argument("--limit-wait", type=int, default=900)
    p.add_argument("--limit-retries", type=int, default=3)
    p.add_argument("--results-dir", default=str(ROOT / "experiment" / "results"))
    p.add_argument("--keep", action="store_true", help="실행 디렉터리를 지우지 않는다(디버깅)")
    p = sub.add_parser("shell")
    p.add_argument("--case", default="_smoke")
    p.add_argument("--structure", default="god")
    p.add_argument("--timeout", type=int, default=900)
    p.add_argument("--results-dir", default=str(CACHE / "shell-results"))
    p.add_argument("--keep", action="store_true")
    p.add_argument("command")
    a = ap.parse_args()
    return {"prep": cmd_prep, "cleanup": cmd_cleanup, "run": cmd_run, "shell": cmd_shell}[a.cmd](a)


if __name__ == "__main__":
    sys.exit(main())
