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
import difflib
import fcntl
import filecmp
import hashlib
import json
import os
import re
import shutil
import signal
import stat
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
PROXY_IMAGE = "ccle-proxy:1"          # experiment/runner/proxy — 허용 목록(api.anthropic.com) 밖 거부
EGRESS_NET = "ccle-egress"            # 프록시만 붙는 외부 연결 네트워크(실행 네트워크는 --internal)
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
    # 외부 접속은 프록시로만(실행 네트워크가 --internal 이라 직접 나갈 길이 없다). 로컬 서비스는 프록시를 거치지 않는다
    **{k: "http://proxy:3128" for k in ("HTTPS_PROXY", "HTTP_PROXY", "https_proxy", "http_proxy")},
    **{k: "localhost,127.0.0.1,db" for k in ("NO_PROXY", "no_proxy")},
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
SETTINGS = {"permissions": {"deny": sorted(events.FORBIDDEN_TOOLS)}}
COPY_IGNORE = shutil.ignore_patterns("build", ".gradle", ".kotlin", "*.log")
CASE_PRIVATE = {"TYPE"}  # 사본에 넣지 않는 과제 파일

_append_lock = threading.Lock()
_live: set = set()          # 진행 중 run_id — 중단 시 일괄 정리
_live_lock = threading.RLock()  # 신호 처리기가 메인 스레드에서 같은 잠금을 다시 잡아도 교착하지 않게
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


def make_home(home: Path, timeout_s: int, with_creds: bool = True):
    """격리 HOME: settings.json(금지 도구) + Gradle 사용자 홈(오프라인 init·데몬 끔) + 로그인 파일 사본(셸 모드는 없음)."""
    (home / ".claude").mkdir(parents=True)
    (home / ".claude" / "settings.json").write_text(json.dumps(SETTINGS))
    g = home / ".gradle"
    if GRADLE_WARM.is_dir():
        shutil.copytree(GRADLE_WARM, g, symlinks=True)
    (g / "init.d").mkdir(parents=True, exist_ok=True)
    shutil.copy2(HERE / "templates" / "offline.gradle", g / "init.d" / "offline.gradle")
    shutil.copy2(HERE / "templates" / "gradle.properties", g / "gradle.properties")
    if not with_creds:  # 무료 인프라 확인(셸 모드)은 로그인 상태와 무관하게 돈다
        return None
    # 실행이 끝날 때까지 사본이 만료되지 않게(시간 상한 + 10분 여유) — 실행 중 컨테이너가 스스로 갱신하지 않도록
    creds.wait_fresh(min_left_s=timeout_s + 600, stopped=_stop.is_set)
    return creds.copy_in(home)


# ---------------------------------------------------------------- 도커

def allowed_mounts(work: Path, home: Path) -> set:
    return {(str(work), AGENT_WORK, True), (str(home), AGENT_HOME, True), (str(GRADLE_RO), AGENT_GRADLE_RO, False)}


def check_mounts(inspected: list, allowed: set) -> list:
    """docker inspect .Mounts 가 허용 목록과 정확히 같은지. 차이 목록(빈 목록 = 통과)."""
    got = {(m.get("Source"), m.get("Destination"), bool(m.get("RW"))) for m in inspected}
    return sorted(f"extra:{x}" for x in got - allowed) + sorted(f"missing:{x}" for x in allowed - got)


def remove_run(name: str) -> list:
    """그 실행의 컨테이너 3개·네트워크를 지우고, 지운 뒤에도 남은 자원 목록을 돌려준다(빈 목록 = 정리 완료).
    삭제 명령의 실패는 '없어서 실패'와 구분이 안 되므로 반환 코드 대신 남은 자원을 다시 조회해 판정한다."""
    for c in (f"{name}-agent", f"{name}-pg", f"{name}-db", f"{name}-proxy"):
        subprocess.run(["docker", "rm", "-f", c], capture_output=True)
    subprocess.run(["docker", "network", "rm", name], capture_output=True)
    left = subprocess.run(["docker", "ps", "-aq", "--filter", f"label={LABEL}={name}"], capture_output=True, text=True)
    nets = subprocess.run(["docker", "network", "ls", "-q", "--filter", f"label={LABEL}={name}"], capture_output=True, text=True)
    if left.returncode or nets.returncode:
        return [f"조회 실패 rc={left.returncode}/{nets.returncode}"]
    return [f"container:{x}" for x in left.stdout.split()] + [f"network:{x}" for x in nets.stdout.split()]


def ensure_egress() -> None:
    if subprocess.run(["docker", "network", "inspect", EGRESS_NET], capture_output=True).returncode:
        r = subprocess.run(["docker", "network", "create", "--label", f"{LABEL}-egress=1", EGRESS_NET],
                           capture_output=True, text=True)
        if r.returncode and "already exists" not in r.stderr:  # 병렬 실행이 동시에 만들 수 있다
            raise RuntimeError(f"egress 네트워크 생성 실패: {r.stderr[-300:]}")


def start_services(name: str) -> None:
    """--internal 실행 네트워크(외부로 나갈 경로 없음) + DB(db) + 가짜 PG + 허용 목록 프록시(proxy, egress 에도 연결)."""
    lab = ["--label", f"{LABEL}={name}"]
    ensure_egress()
    sh(["docker", "network", "create", "--internal", *lab, name])
    sh(["docker", "run", "-d", "--name", f"{name}-proxy", *lab, "--network", EGRESS_NET, PROXY_IMAGE])
    sh(["docker", "network", "connect", "--alias", "proxy", name, f"{name}-proxy"])
    sh(["docker", "run", "-d", "--name", f"{name}-db", *lab, "--network", name, "--network-alias", "db",
        "--shm-size=1g", DB_IMAGE])
    sh(["docker", "run", "-d", "--name", f"{name}-pg", *lab, "--network", name,
        "-v", f"{FAKEPG}:/fakepg:ro", "-e", f"PG_PORT={PG_PORT}", "-e", "PG_DELAY_MS=3000", "-e", f"TZ={TZ}",
        IMAGE, "java", "-cp", "/fakepg/*", "com.ccle.behavior.pg.FakePgKt"])
    deadline = time.time() + 120
    db_ok = pg_ok = px_ok = False
    while time.time() < deadline and not (db_ok and pg_ok and px_ok):
        if not px_ok:
            px_ok = subprocess.run(["docker", "exec", f"{name}-proxy", "nc", "-z", "127.0.0.1", "3128"],
                                   capture_output=True).returncode == 0
        if _stop.is_set():
            raise RuntimeError("서비스 기동 중 중단")
        if not db_ok:
            db_ok = subprocess.run(["docker", "exec", f"{name}-db", "pg_isready", "-U", "ccle", "-d", "ccle_seed", "-q"],
                                   capture_output=True).returncode == 0
        if not pg_ok:
            pg_ok = "fake-pg ready" in sh(["docker", "logs", f"{name}-pg"], check=False).stdout
        time.sleep(0.5)
    if not (db_ok and pg_ok and px_ok):
        raise RuntimeError(f"서비스 기동 실패 db={db_ok} pg={pg_ok} proxy={px_ok}: {sh(['docker', 'logs', f'{name}-pg'], check=False).stderr[-300:]}")
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
    """(rc, timed_out, wall). 시간 상한이면 컨테이너를 kill 한다(러너 종료 — censored). 중단 신호도 1초 안에 kill."""
    t0 = time.time()
    timed_out = False
    with open(out, "wb") as fo, open(err, "wb") as fe:
        p = subprocess.Popen(["docker", "start", "-a", f"{name}-agent"], stdin=subprocess.DEVNULL, stdout=fo, stderr=fe)
        while p.poll() is None:
            if time.time() - t0 >= timeout_s or _stop.is_set():
                timed_out = not _stop.is_set()
                subprocess.run(["docker", "kill", f"{name}-agent"], capture_output=True)
                try:
                    p.wait(timeout=60)
                except subprocess.TimeoutExpired:
                    p.kill()
                    p.wait()
                break
            time.sleep(1)
    return p.returncode, timed_out, time.time() - t0


# CLI 자신이 여는 허용 목록 밖 접속(차단되는 것이 맞다 — 계정 MCP 커넥터 로드 = 하네스 유입). 거부돼도 무효로 보지 않는다.
CLI_EXPECTED_REFUSED = {"mcp-proxy.anthropic.com"}  # 2.1.308 Haiku 스모크(프록시) 실측
PROXY_REQ = re.compile(r"Request \(file descriptor \d+\): (?:CONNECT (\S+?)(?::\d+)? |(?:GET|POST|PUT|HEAD|DELETE|PATCH|OPTIONS) \w+://([^/:\s]+))")
PROXY_REFUSED = re.compile(r'Proxying refused on filtered domain "([^"]+)"')


def proxy_hosts(log: str) -> dict:
    """프록시 로그 → 요청 호스트·거부 호스트(정렬). 거부가 있으면 누군가 허용 목록 밖으로 나가려 했다."""
    req = {m[1] or m[2] for m in PROXY_REQ.finditer(log)}
    return {"requested": sorted(h.lower() for h in req if h), "refused": sorted({h.lower() for h in PROXY_REFUSED.findall(log)})}


def image_id(img: str) -> str:
    return sh(["docker", "image", "inspect", "-f", "{{.Id}}", img]).stdout.strip()


# ---------------------------------------------------------------- 결과

def append_row(results: Path, row: dict) -> None:
    results.mkdir(parents=True, exist_ok=True)
    with _append_lock, open(results / "runs.jsonl", "a", encoding="utf-8") as f:
        fcntl.flock(f, fcntl.LOCK_EX)
        f.write(json.dumps(row, ensure_ascii=False) + "\n")


def done_keys(results: Path) -> set:
    """valid 행의 (case, structure, model, rep). 깨진 줄(강제 종료로 반쯤 쓰인 append)은 건너뛰고 알린다."""
    keys, bad = set(), 0
    p = results / "runs.jsonl"
    if p.exists():
        for line in p.read_text(errors="replace").splitlines():
            if not line.strip():
                continue
            try:
                r = json.loads(line)
            except json.JSONDecodeError:
                bad += 1
                continue
            if r.get("valid"):
                keys.add((r["case"], r["structure"], r["model"], r["rep"]))
    if bad:
        print(f"[runner] runs.jsonl 깨진 줄 {bad}개 건너뜀", flush=True)
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


DIFF_SKIP_DIRS = {"build", ".gradle", ".kotlin"}
DIFF_MAX_FILE = 2 * 2**20  # 이보다 큰 파일은 내용 대신 크기·해시만


def _tree(root: Path) -> dict:
    """상대 경로 → 항목(링크는 따라가지 않는다). 값: ("file", Path) · ("link", 대상 문자열) · ("special", 종류)."""
    out = {}
    for d, dirs, files in os.walk(root, followlinks=False):
        dirs[:] = [x for x in dirs if x not in DIFF_SKIP_DIRS and not os.path.islink(os.path.join(d, x))]
        for x in list(files) + [x for x in os.listdir(d) if os.path.islink(os.path.join(d, x)) and x not in files]:
            p = Path(d, x)
            rel = p.relative_to(root).as_posix()
            st = os.lstat(p)
            if stat.S_ISLNK(st.st_mode):
                out[rel] = ("link", os.readlink(p))
            elif stat.S_ISREG(st.st_mode):
                out[rel] = ("file", p)
            else:
                out[rel] = ("special", stat.filemode(st.st_mode)[0])
    return out


def _lines(e) -> list:
    kind, v = e
    if kind != "file":
        return [f"<{kind}: {v}>\n"]
    size = v.stat().st_size
    if size > DIFF_MAX_FILE:
        return [f"<large file {size}B sha256={hashlib.sha256(v.read_bytes()).hexdigest()}>\n"]
    data = v.read_bytes()
    if b"\0" in data[:8192]:
        return [f"<binary {size}B sha256={hashlib.sha256(data).hexdigest()}>\n"]
    return data.decode(errors="replace").splitlines(keepends=True)


def write_diff(base: Path, work: Path, dst: Path) -> int:
    """base(에이전트 전 사본) 대비 work 의 unified diff. 호스트에서 돌므로 링크·FIFO·장치 파일을 열지 않는다
    (에이전트가 만든 링크를 따라가면 호스트 파일이 결과에 섞이거나 FIFO 에서 멈춘다)."""
    a, b = _tree(base), _tree(work)
    chunks = []
    for rel in sorted(set(a) | set(b)):
        ea, eb = a.get(rel), b.get(rel)
        if ea and eb and ea[0] == eb[0] == "file" and filecmp.cmp(ea[1], eb[1], shallow=False):
            continue
        if ea == eb and ea and ea[0] != "file":
            continue
        la = _lines(ea) if ea else []
        lb = _lines(eb) if eb else []
        chunks += difflib.unified_diff(la, lb, f"a/{rel}" if ea else "/dev/null", f"b/{rel}" if eb else "/dev/null")
        if chunks and not chunks[-1].endswith("\n"):
            chunks[-1] += "\n\\ No newline at end of file\n"
    txt = "".join(chunks).encode()
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_bytes(txt)
    return len(txt)


def judge(case: str, structure: str, work: Path) -> dict:
    """판정 자리(ADR §4) — 과제 작업에서 구현한다. 지금은 판정하지 않았다는 사실만 남긴다."""
    return {"judged": False}


def symbols(case: str, structure: str) -> list:
    p = ORACLE / case / f"symbols.{structure}.txt"
    return events.parse_symbols(p.read_text()) if p.is_file() else []


def classify(evs: list, rc: int, timed_out: bool, violations: list, stderr: str = "", tok: dict = None,
             n_assistant: int = None) -> dict:
    """valid = (result ∨ 러너 시간 상한) ∧ 감사 통과 ∧ 사용량 한도 아님 ∧ 토큰 측정됨 (ADR §6.4).
    - 시간 상한(result 없음): valid·censored, 부분 토큰이 0이어도 그대로(첫 응답 전 상한 — 측정값 0).
    - result 가 있는데 modelUsage 가 없으면 무효(no_modelusage) — 부분 합은 정식 토큰과 정의가 달라 섞지 않는다.
    - 오류 result(한도 문구 아님): 무효(agent_error) — API·연결·인증 오류로 끊긴 실행이 주 분석에 섞이지 않게.
    - result 없이 끝났는데 stderr 에 한도 문구: 사용량 한도(대기 뒤 재시도). 인프라 오류는 run_one 이 따로."""
    reason = events.end_reason(evs, timed_out)
    if reason in ("no_result", "timeout") and events.LIMIT_TEXT.search(stderr or ""):
        reason = "usage_limit"  # 한도·과부하 재시도 끝에 상한에 걸린 실행도 한도로(시간 상한 censored 가 아니다)
    elif reason == "timeout" and n_assistant == 0:
        reason = "no_activity"  # 첫 응답도 없이 상한 — 에이전트가 일하지 않은 인프라 문제
    invalid = None
    if violations:
        invalid = "audit"
    elif reason in ("usage_limit", "no_result", "no_activity"):
        invalid = reason
    elif reason == "error":
        invalid = "agent_error"
    elif reason in ("normal", "budget") and tok is not None and tok.get("tokens_source") != "modelUsage":
        invalid = "no_modelusage"
    return {"end_reason": reason, "censored": reason in ("timeout", "budget"), "valid": invalid is None,
            "invalid_reason": invalid}


def tail(p: Path, n: int = 1500) -> str:
    try:
        return p.read_bytes()[-n:].decode(errors="replace")
    except OSError:
        return ""


_case_locks: dict = {}
_case_locks_guard = threading.Lock()


def case_lock(case: str) -> threading.Lock:
    """같은 과제의 실행은 동시에 돌리지 않는다(ADR §8 '과제별 직렬' — 두 구조가 같은 시점·같은 부하를 나누지 않게)."""
    with _case_locks_guard:
        return _case_locks.setdefault(case, threading.Lock())


def disk_free_gb(*paths: Path) -> float:
    """러너가 쓰는 파일시스템(HOME 캐시·결과·docker data-root) 중 가장 작은 여유."""
    roots = [p for p in paths if p.exists()]
    d = subprocess.run(["docker", "info", "-f", "{{.DockerRootDir}}"], capture_output=True, text=True)
    if d.returncode == 0 and d.stdout.strip() and Path(d.stdout.strip()).exists():
        roots.append(Path(d.stdout.strip()))
    return min(shutil.disk_usage(r).free for r in roots) / 2**30


def mask_file(p: Path, secrets: list) -> int:
    """토큰 값이 파일에 있으면 치환한다(에이전트가 로그인 파일을 출력한 경우 — 감사 위반이지만 원문을 남기지 않는다)."""
    if not secrets or not p.is_file():
        return 0
    data = p.read_bytes()
    n = 0
    for v in secrets:
        b = v.encode()
        n += data.count(b)
        data = data.replace(b, b"<masked-credential>")
    if n:
        p.write_bytes(data)
    return n


def run_one(case: str, structure: str, rep: int, model: str, results: Path, budget: str, timeout_s: int,
            argv_override: list = None) -> dict:
    run_id = f"{case}-{structure}-{model}-r{rep}-{int(time.time() * 1000) % 10**10}"
    name = f"ccle-{run_id}".lower()
    rd = RUNS / run_id
    raw = results / "raw"
    out, err = raw / f"{run_id}.jsonl", raw / f"{run_id}.stderr"
    row = {"run_id": run_id, "case": case, "structure": structure, "model": model, "rep": rep,
           "started_at": now_iso(), "image": IMAGE, "cli_version": CLI_VERSION, "budget_usd": budget,
           "timeout_s": timeout_s, "shell": bool(argv_override)}
    alive = sb_creds = None
    secrets = []
    with _live_lock:
        _live.add(name)
    try:  # 시도 식별 직후부터 예외 경계 — 표식 생성 실패도 행으로 남는다
        raw.mkdir(parents=True, exist_ok=True)
        rd.mkdir(parents=True)
        # 시도 표식: 러너가 강제 종료(SIGKILL·부모 셸 종료)돼 finally 가 못 돌면 sweep_orphans 가 이것으로 행을 남긴다
        alive = open(rd / "attempt.lock", "w")
        fcntl.flock(alive, fcntl.LOCK_EX)
        (rd / "attempt.json").write_text(json.dumps({"row": row, "results": str(results)}, ensure_ascii=False))
        free = disk_free_gb(CACHE, results)
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
        sb_creds = make_home(rd / "home", timeout_s, with_creds=not argv_override)
        secrets = creds.secrets(sb_creds) if sb_creds else []
        prompt = PROMPTS[case_type(case)]
        argv = argv_override or ["claude", "-p", prompt, "--model", model, "--output-format", "stream-json",
                                 "--verbose", "--max-budget-usd", budget, "--permission-mode", "bypassPermissions"]
        start_services(name)
        if _stop.is_set():
            raise RuntimeError("에이전트 기동 전 중단")
        create_agent(name, rd / "work", rd / "home", argv)
        if _stop.is_set():
            raise RuntimeError("에이전트 기동 전 중단")
        rc, timed_out, wall = attach_agent(name, out, err, timeout_s)
        row.update(rc=rc, timed_out=timed_out, wall_s=round(wall, 1))
        px = proxy_hosts(sh(["docker", "logs", f"{name}-proxy"], check=False).stderr +
                         sh(["docker", "logs", f"{name}-proxy"], check=False).stdout)
        row["proxy"] = px
        down = [c for c in (f"{name}-db", f"{name}-pg")
                if sh(["docker", "inspect", "-f", "{{.State.Running}}", c], check=False).stdout.strip() != "true"]
        if _stop.is_set():
            row.update(valid=False, invalid_reason="interrupted")
            return row
        row["diff_bytes"] = write_diff(rd / "base", rd / "work", results / "diffs" / f"{run_id}.diff")
        row["diff"] = str((results / "diffs" / f"{run_id}.diff").relative_to(results))
        row["events"] = str(out.relative_to(results))
        if argv_override:  # 셸 모드: 이벤트 파싱 없이 출력만
            row.update(valid=False, invalid_reason="shell")
            return row
        lines = out.read_text(errors="replace").splitlines()
        evs, bad = events.load(lines)
        cs, turns = events.calls(evs)
        res = next((e for e in reversed(evs) if e.get("type") == "result"), {}) or {}
        violations = events.audit(cs)
        tok = events.tokens(evs)
        n_asst = sum(1 for e in evs if e.get("type") == "assistant")
        row.update(bad_event_lines=bad, init=events.init_info(evs), turns=turns, result_turns=res.get("num_turns"),
                   result_subtype=res.get("subtype"), is_error=res.get("is_error"),
                   duration_ms=res.get("duration_ms"), **tok,
                   **events.metrics(cs, symbols(case, structure)), audit_violations=violations,
                   **classify(evs, rc, timed_out, violations, tail(err, 4000) + tail(out, 4000), tok, n_asst))
        row["result_count"] = events.result_count(evs)  # 2 이상이면 큐에 쌓인 턴 — 토큰은 마지막 result 기준(표시)
        harness = events.harness_problems(row["init"]) if any(e.get("subtype") == "init" for e in evs) \
            else ["init 이벤트 없음"]
        if harness and row["valid"]:
            row.update(valid=False, invalid_reason="harness", harness_problems=harness)
        if row["init"].get("cli_version") != CLI_VERSION and "init 이벤트 없음" not in harness and row["valid"]:
            row.update(valid=False, invalid_reason="version_mismatch")
        agent_refused = sorted(set(px["refused"]) - CLI_EXPECTED_REFUSED)
        if agent_refused and row["valid"]:  # 허용 목록 밖 접속 시도(차단됨) — 결과는 깨끗해도 시도 자체를 무효로
            row.update(valid=False, invalid_reason="network", network_refused=agent_refused)
        if down and row["valid"]:  # 실행 중 DB·가짜 PG 가 죽었으면 에이전트가 본 환경이 계약과 다르다
            row.update(valid=False, invalid_reason="infra", infra_error=f"실행 중 서비스 중지: {down}")
        row.update(judge(case, structure, rd / "work"))
        return row
    except Exception as e:  # 러너·인프라 오류도 행으로 남긴다(무음 제외 금지)
        row.update(valid=False, invalid_reason="interrupted" if _stop.is_set() else "infra", infra_error=repr(e)[:800])
        return row
    finally:
        left = remove_run(name)  # 컨테이너가 멈춘 뒤에 되쓴다(사본이 더 바뀌지 않게)
        if left:
            row.update(valid=False, invalid_reason=row.get("invalid_reason") or "infra", cleanup_left=left)
        if sb_creds is not None:  # 컨테이너가 돌았을 수 있는 모든 경로에서 — 회전된 refresh 토큰을 잃지 않게
            secrets = sorted(set(secrets) | set(creds.secrets(sb_creds)))  # 실행 중 회전된 새 값까지
            try:
                row["creds_written_back"] = creds.write_back(sb_creds)
            except Exception as e:
                row["creds_writeback_error"] = repr(e)[:300]
            secrets = sorted(set(secrets) | set(creds.secrets(creds.ORIGIN)))
        masked = mask_file(out, secrets) + mask_file(err, secrets) + \
            mask_file(results / "diffs" / f"{run_id}.diff", secrets)  # 사본에 복사된 로그인 파일도 diff 에 나온다
        if masked:
            row["masked_credentials"] = masked
        if not row.get("valid", True) or row.get("end_reason") not in (None, "normal") or argv_override:
            row["tail"] = tail(err) or tail(out)
            if argv_override:
                row.update(tail=tail(out, 4000), stderr_tail=tail(err))
        with _live_lock:
            _live.discard(name)
        row["finished_at"] = now_iso()
        append_row(results, row)
        if alive is not None:  # 행을 쓴 뒤 잠금을 쥔 채 표식을 지운다 → sweep 이 이 시도를 고아로 보지 않는다
            (rd / "attempt.json").unlink(missing_ok=True)
            alive.close()
        rm_tree(rd)  # 작업 사본·격리 HOME(로그인 사본 포함)은 남기지 않는다 — 보관은 diff·이벤트만(spec §2)


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
            if not meta.is_file():  # 잠금을 얻는 사이 정상 종료(표식 삭제)
                continue
            m = json.loads(meta.read_text())
            row = {**m["row"], "valid": False, "invalid_reason": "orphan",
                   "infra_error": "러너가 행을 남기지 못하고 종료됨(sweep 으로 기록)", "finished_at": now_iso()}
            left = remove_run(f"ccle-{row['run_id']}".lower())
            if left:
                row["cleanup_left"] = left
            sb = d / "home" / ".claude" / ".credentials.json"
            if sb.is_file():  # 죽기 전에 컨테이너가 토큰을 회전시켰을 수 있다 — 오류가 나도 행·정리는 계속
                try:
                    row["creds_written_back"] = creds.write_back(sb)
                except Exception as e:
                    row["creds_writeback_error"] = repr(e)[:300]
                try:
                    secrets = sorted(set(creds.secrets(sb)) | set(creds.secrets(creds.ORIGIN)))
                    res = Path(m["results"])
                    row["masked_credentials"] = sum(mask_file(f, secrets) for f in (
                        res / "raw" / f"{row['run_id']}.jsonl", res / "raw" / f"{row['run_id']}.stderr",
                        res / "diffs" / f"{row['run_id']}.diff"))
                except Exception as e:
                    row["mask_error"] = repr(e)[:300]
            append_row(Path(m["results"]), row)
            meta.unlink()
        rm_tree(d)
        n += 1
    return n


# ---------------------------------------------------------------- 명령

def cmd_prep(a) -> int:
    """가짜 PG: behavior-tests 기존 gradle 태스크(fakePgClasspath)를 컨테이너에서 돌린 산출물을 jar 디렉터리로. Gradle 캐시: 두 구조 사본을 온라인으로
    한 번 빌드해 seed 홈을 채우고 modules-2 만 읽기 전용 캐시로 복사(잠금·gc 파일 제외 — Gradle 문서의 RO 캐시 조건).
    예열 템플릿: 네트워크 없는 컨테이너에서 실제 실행과 같은 설정으로 두 구조를 빌드(오프라인 빌드 확인을 겸한다)."""
    # 가짜 PG: 저장소 tracked 파일 사본을 이미지 컨테이너에서 빌드(seed 홈 — 호스트 ~/.gradle·호스트 JDK 를 쓰지 않는다)
    GRADLE_SEED.mkdir(parents=True, exist_ok=True)
    repo = RUNS / "prep-repo"
    rm_tree(repo)
    repo.mkdir(parents=True)
    files = sh(["git", "ls-files", "-z"], cwd=ROOT).stdout.split("\0")
    for f in filter(None, files):
        src = ROOT / f
        if src.is_file() and not src.is_symlink():
            (repo / f).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, repo / f)
    wp = repo / "gradle" / "wrapper" / "gradle-wrapper.properties"
    wp.write_text(re.sub(r"(?m)^distributionUrl=.*$", f"distributionUrl=file\\:{GRADLE_DIST}", wp.read_text()))
    p = subprocess.run(["docker", "run", "--rm", "--user", f"{os.getuid()}:{os.getgid()}",
                        "-v", f"{repo}:/repo", "-v", f"{GRADLE_SEED}:/seed", "-e", "GRADLE_USER_HOME=/seed",
                        "-w", "/repo", IMAGE, "bash", "-c",
                        "./gradlew --no-daemon -q :behavior-tests:fakePgClasspath && cat behavior-tests/build/fake-pg.classpath"],
                       capture_output=True, text=True, timeout=3600)
    if p.returncode:
        raise SystemExit(f"prep 가짜 PG 빌드 실패: {p.stdout[-800:]}{p.stderr[-800:]}")
    cp = p.stdout.strip().splitlines()[-1].split(":")
    tmp = FAKEPG.with_name("fakepg.tmp")
    rm_tree(tmp)
    tmp.mkdir(parents=True)
    for entry in cp:
        hp = Path(entry.replace("/repo/", f"{repo}/", 1).replace("/seed/", f"{GRADLE_SEED}/", 1))
        if hp.is_dir():
            if any(hp.iterdir()):
                shutil.make_archive(str(tmp / f"bt-{hp.parent.name}-{hp.name}"), "zip", hp)
                (tmp / f"bt-{hp.parent.name}-{hp.name}.zip").rename(tmp / f"bt-{hp.parent.name}-{hp.name}.jar")
        elif hp.suffix == ".jar" and hp.is_file():
            shutil.copy2(hp, tmp / hp.name)
        elif hp.suffix != ".jar" and not hp.exists():
            continue  # 소스가 없는 소스셋의 출력 디렉터리(예: classes/java/main)는 만들어지지 않는다
        else:
            raise SystemExit(f"prep 가짜 PG 클래스패스 항목 없음: {entry} → {hp}")
    rm_tree(FAKEPG)
    tmp.rename(FAKEPG)
    rm_tree(repo)
    print(f"fakepg: {sorted(x.name for x in FAKEPG.iterdir())}")

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


def live_names() -> set:
    """잠금이 쥐어진(진행 중) 시도의 docker 이름. 다른 러너 프로세스의 실행도 포함."""
    live = set()
    for d in RUNS.iterdir() if RUNS.is_dir() else []:
        lk = d / "attempt.lock"
        if not lk.is_file():
            continue
        with open(lk, "a") as f:
            try:
                fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                live.add(f"ccle-{d.name}".lower())
    return live


def cmd_cleanup(_a) -> int:
    """고아 시도를 행으로 남기고, 진행 중이 아닌 러너 자원만 지운다(다른 러너의 진행 중 실행은 건드리지 않는다)."""
    print(f"orphans={sweep_orphans()}")
    # 자원 목록을 먼저 뜨고 잠금을 본다: 시도는 잠금을 쥔 뒤에 자원을 만들므로, 목록에 있는 자원의 주인은
    # 이 시점에 이미 잠금을 쥐고 있다(반대 순서면 그 사이 시작한 실행의 자원을 지울 수 있다)
    rows = sh(["docker", "ps", "-a", "--filter", f"label={LABEL}", "--format", f'{{{{.ID}}}} {{{{.Label "{LABEL}"}}}}'],
              check=False).stdout.split("\n")
    live = live_names()
    ids = [x.split()[0] for x in rows if x.strip() and (len(x.split()) < 2 or x.split()[1] not in live)]
    if ids:
        sh(["docker", "rm", "-f", *ids], check=False)
    nrows = sh(["docker", "network", "ls", "--filter", f"label={LABEL}", "--format", f'{{{{.ID}}}} {{{{.Label "{LABEL}"}}}}'],
               check=False).stdout.split("\n")
    nets = [x.split()[0] for x in nrows if x.strip() and (len(x.split()) < 2 or x.split()[1] not in live)]
    if nets:
        sh(["docker", "network", "rm", *nets], check=False)
    print(f"containers={len(ids)} networks={len(nets)} live_skipped={len(live)}")
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
    results.mkdir(parents=True, exist_ok=True)
    guard = open(results / ".runner.lock", "w")  # 같은 결과 디렉터리에 러너 둘 → 같은 키 valid 행 중복 방지
    try:
        fcntl.flock(guard, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        raise SystemExit(f"다른 러너가 같은 결과 디렉터리를 쓰는 중: {results}")
    orphans = sweep_orphans()
    if orphans:
        print(f"[runner] 이전 고아 시도 {orphans}개를 orphan 행으로 기록·정리", flush=True)
    jobs = jobs_for(cases, structures, a.model, [int(r) for r in a.reps.split(",")], done_keys(results))
    print(f"jobs={len(jobs)} parallel={a.parallel} results={results}", flush=True)
    bad = 0

    def one(job):
        c, s, rep = job
        r = None
        for attempt in range(1, a.limit_retries + 2):
            if _stop.is_set():  # 중단 뒤 아직 시작하지 않은 job 은 시도가 아니다 — 행을 만들지 않는다
                return r
            with case_lock(c):
                if _stop.is_set():  # 잠금을 기다리는 동안 중단됨 — 시작하지 않은 job
                    return r
                r = run_one(c, s, rep, a.model, results, a.budget, a.timeout)
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
            if r is None:
                print(f"{c} {s} r{rep}: 시작 안 함(중단)", flush=True)
                continue
            bad += not r.get("valid")
            print(f"{c} {s} r{rep}: valid={r.get('valid')} reason={r.get('invalid_reason') or r.get('end_reason')} "
                  f"tokens={r.get('total_tokens')} turns={r.get('turns')} wall={r.get('wall_s')} "
                  f"viol={r.get('audit_violations')}", flush=True)
    return 130 if _stop.is_set() else (1 if bad else 0)


def cmd_shell(a) -> int:
    signal.signal(signal.SIGINT, _on_signal)
    signal.signal(signal.SIGTERM, _on_signal)
    r = run_one(a.case, a.structure, 0, "shell", Path(a.results_dir).resolve(), "0", a.timeout,
                argv_override=["bash", "-c", a.command])
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
    p = sub.add_parser("shell")
    p.add_argument("--case", default="_smoke")
    p.add_argument("--structure", default="god")
    p.add_argument("--timeout", type=int, default=900)
    p.add_argument("--results-dir", default=str(CACHE / "shell-results"))
    p.add_argument("command")
    a = ap.parse_args()
    return {"prep": cmd_prep, "cleanup": cmd_cleanup, "run": cmd_run, "shell": cmd_shell}[a.cmd](a)


if __name__ == "__main__":
    sys.exit(main())
