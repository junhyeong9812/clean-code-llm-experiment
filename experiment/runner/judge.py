"""판정 단계 (ADR-0005 §4.1). 에이전트 실행이 끝난 사본을 복제해, 실행마다 새 판정 컨테이너에서 단계별로 돌린다:
변조 검사 → 빌드(네트워크 없음) → 공개 → 숨은 → 회귀(contractTest·contract2Test − regression-exclude, verify.sql −
verify-exclude). 단계마다 새 1k DB + 새 앱 + 가짜 PG(테스트가 시계를 앞으로만 옮기므로).
success = 빌드 ∧ 공개 ∧ 숨은 ∧ 변조 없음 ∧ 회귀. 판정 불가(인프라)는 judge_error 로 따로 — 무음 성공·실패 금지.
"""
import hashlib
import json
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

STAGES = ("public", "hidden", "contract", "contract2")
PROTECTED = ("TASK.md", "test.sh")          # + checks/ 전체(지원 코드·공개 테스트)
VERIFY_FAILED = re.compile(r"verify: 불변식 위반 \d+ 건 — (.+)")
VERIFY_OK = re.compile(r"verify: \d+ 개 검사 모두 통과")


def sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def _files(root: Path, sub: str) -> dict:
    base = root / sub
    if base.is_file():
        return {sub: base}
    return {f.relative_to(root).as_posix(): f for f in base.rglob("*")
            if f.is_file() and not {"build", ".gradle"} & set(f.relative_to(base).parts)} if base.is_dir() else {}


def tampered(pristine: Path, work: Path) -> list:
    """보호 파일(TASK.md·test.sh·checks/)이 원본과 다른 경로 목록 — 바뀜·없어짐·새로 생김(checks 안)."""
    out = []
    for sub in PROTECTED + ("checks",):
        a, b = _files(pristine, sub), _files(work, sub)
        for rel in sorted(set(a) | set(b)):
            if rel not in a or rel not in b or sha(a[rel]) != sha(b[rel]):
                out.append(rel)
    return sorted(out)


def restore(pristine: Path, work: Path) -> None:
    """판정은 항상 원본 보호 파일로 한다(변조 여부와 무관 — 변조는 따로 실패 사유)."""
    for sub in PROTECTED:
        shutil.copy2(pristine / sub, work / sub)
    if (work / "checks").exists():
        shutil.rmtree(work / "checks")
    shutil.copytree(pristine / "checks", work / "checks", ignore=shutil.ignore_patterns("build", ".gradle"))


def junit_results(dirs: list) -> dict:
    """JUnit XML → {tests, failed:[fqcn.method], skipped}. XML 이 하나도 없으면 tests=0."""
    tests = skipped = 0
    failed = []
    for d in dirs:
        for x in sorted(Path(d).glob("*.xml")) if Path(d).is_dir() else []:
            try:
                root = ET.parse(x).getroot()
            except ET.ParseError:
                failed.append(f"<깨진 결과 파일 {x.name}>")
                continue
            for tc in root.iter("testcase"):
                tests += 1
                name = f"{tc.get('classname')}.{tc.get('name')}"
                if tc.find("failure") is not None or tc.find("error") is not None:
                    failed.append(name)
                elif tc.find("skipped") is not None:
                    skipped += 1
    return {"tests": tests, "failed": failed, "skipped": skipped}


def failure_messages(dirs: list, limit: int = 10) -> dict:
    """실패 테스트 → 메시지 앞부분(판정 행에서 실패 이유를 바로 보게 — 최대 limit 건, 건당 300자)."""
    out = {}
    for d in dirs:
        for x in sorted(Path(d).glob("*.xml")) if Path(d).is_dir() else []:
            try:
                root = ET.parse(x).getroot()
            except ET.ParseError:
                continue
            for tc in root.iter("testcase"):
                f = tc.find("failure")
                if f is None:
                    f = tc.find("error")
                if f is not None and len(out) < limit:
                    out[f"{tc.get('classname')}.{tc.get('name')}"] = (f.get("message") or (f.text or ""))[:300]
    return out


def verify_result(text: str, rc: int, exclude: set) -> dict:
    """verify.sql 출력 → {ok, violations:{검사:건수}, excluded:[…]}. 형식을 못 읽으면 ok=None(판정 불가)."""
    if rc == 0 and VERIFY_OK.search(text):
        return {"ok": True, "violations": {}, "excluded": []}
    m = VERIFY_FAILED.search(text)
    if not m:
        return {"ok": None, "violations": {}, "excluded": [], "tail": text[-600:]}
    viol = {}
    for part in m[1].split(","):
        name, _, n = part.strip().rpartition("=")
        if name:
            viol[name.strip()] = int(n) if n.strip().lstrip("-").isdigit() else n
    kept = {k: v for k, v in viol.items() if k not in exclude}
    return {"ok": not kept, "violations": kept, "excluded": sorted(set(viol) & exclude)}


def read_list(p: Path) -> list:
    """`항목  # 사유` 줄 목록(빈 줄·주석 줄 무시)."""
    if not p.is_file():
        return []
    out = []
    for line in p.read_text().splitlines():
        item = line.split("#", 1)[0].strip()
        if item:
            out.append(item)
    return out


def stage_outcome(status: str, rc, res: dict, expect_tests: bool = True) -> str:
    """pass | fail | app_start_fail | no_results | infra.
    - app_died(빌드된 앱 프로세스가 죽음) → app_start_fail(에이전트 코드 탓일 수 있어 확정 실패)
    - app_start_timeout(살아 있는데 제때 안 뜸) → infra(부하·인프라 — 판정 불가)
    - 테스트가 돌았는데 rc ≠ 0 이고 실패 테스트가 없음(Gradle 자체 오류) → infra"""
    if status in ("app_died", "app_start_fail"):
        return "app_start_fail"
    if status != "ran":
        return "infra"
    if res["tests"] == 0:
        return "no_results" if expect_tests else "pass"
    if res["failed"]:
        return "fail"
    return "pass" if rc == 0 else "infra"


BUILD_INFRA = re.compile(r"Could not resolve|No cached version|offline mode|Could not download|OutOfMemoryError|"
                         r"Gradle could not start|daemon disappeared|No space left", re.I)
KOTLIN_ERROR = re.compile(r"^e: ", re.M)


def build_failure_kind(rc: int, output: str) -> str:
    """빌드 실패 분류: compile(에이전트 코드 — 확정 실패) | infra(docker·Gradle·캐시 — 판정 불가).
    docker 자체 오류(rc ≥ 125)·의존성·메모리·디스크 오류는 infra, Kotlin 컴파일 오류(`e:` 줄)가 있으면 compile,
    둘 다 아니면 compile(빌드 스크립트 오류도 사본 탓으로 본다 — 루트 빌드 파일은 판정 전에 원본으로 되돌린다)."""
    if rc >= 125 or (BUILD_INFRA.search(output) and not KOTLIN_ERROR.search(output)):
        return "infra"
    return "compile"


def conjunction(oc: dict, keys, tampered: list):
    """여러 단계의 AND. 확정 실패(fail·app_start_fail·변조)가 하나라도 있으면 False, 그 밖에 판정 불가가 있으면 None."""
    vals = [oc.get(k, "infra") for k in keys]
    if tampered or any(v in ("fail", "app_start_fail") for v in vals):
        return False
    if any(v != "pass" for v in vals):
        return None
    return True


EXCLUDE_GRADLE = """// 판정 회귀: regression-exclude(테스트 메서드 단위) 제외
def excl = new File('/work/.judge/regression-exclude.txt').readLines().collect { it.trim() }.findAll { it }
allprojects {
    tasks.withType(Test).configureEach {
        filter {
            excl.each { excludeTestsMatching(it) }
            failOnNoMatchingTests = false
        }
    }
}
"""


BUILD_FILES = ("build.gradle.kts", "settings.gradle.kts", "gradlew", "gradlew.bat",
               "gradle/wrapper/gradle-wrapper.properties", "gradle/wrapper/gradle-wrapper.jar")


def restore_build_files(pristine: Path, work: Path) -> list:
    """루트 빌드 파일·wrapper 를 원본으로 되돌리고 바뀌었던 것을 돌려준다(빌드 스크립트로 테스트를 끄는 우회 차단).
    변조로 세지는 않는다(ADR §4.1 보호 목록 밖) — 기록만 한다."""
    changed = []
    for rel in BUILD_FILES:
        a, b = pristine / rel, work / rel
        if a.is_file() and (not b.is_file() or sha(a) != sha(b)):
            changed.append(rel)
            b.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(a, b)
    return changed


def prepare(case: str, structure: str, work: Path, jd: Path) -> dict:
    """판정 작업 디렉터리: 사본 복제 + 원본 보호 파일 복원 + 숨은 테스트 + behavior-tests + verify.sql + 제외 목록."""
    import runner as R
    pristine = jd / "pristine"
    R.build_work(case, structure, pristine)
    jw = jd / "work"
    shutil.copytree(work, jw, symlinks=True, ignore=shutil.ignore_patterns("build", ".gradle", ".kotlin"))
    tam = tampered(pristine, jw)
    restore(pristine, jw)
    changed_build = restore_build_files(pristine, jw)
    hidden = R.ORACLE / case / "hidden"
    if hidden.is_dir():
        shutil.copytree(hidden, jw / "checks" / "src" / "test" / "kotlin" / "hidden", dirs_exist_ok=True)
    shutil.copytree(R.ROOT / "behavior-tests", jw / "behavior-tests", ignore=shutil.ignore_patterns("build", ".gradle"))
    s = (jw / "settings.gradle.kts").read_text()
    (jw / "settings.gradle.kts").write_text(s.rstrip("\n") + '\ninclude("behavior-tests")\n')
    j = jw / ".judge"
    j.mkdir()
    shutil.copy2(R.HERE / "templates" / "judge-stage.sh", j / "stage.sh")
    shutil.copy2(R.ROOT / "tools" / "seedgen" / "verify.sql", j / "verify.sql")
    (j / "exclude.gradle").write_text(EXCLUDE_GRADLE)
    (j / "regression-exclude.txt").write_text("\n".join(read_list(R.ORACLE / case / "regression-exclude.txt")) + "\n")
    return {"tampered": tam, "hidden_present": hidden.is_dir(), "build_files_changed": changed_build}


def judge(case: str, structure: str, work: Path, jd: Path, name: str, log=print, stages_to_run=STAGES) -> dict:
    """한 실행의 판정. work = 에이전트가 끝낸 사본(읽기만), jd = 판정 전용 디렉터리(호출자가 지운다).
    결과 success: True | False(확정 실패) | None(판정 불가 — 호출자는 무효로 다룬다)."""
    import runner as R
    out = {"judged": True, "stages": {}}
    try:
        out.update(prepare(case, structure, work, jd))
        home = jd / "home"
        R.make_home(home, 0, with_creds=False)
        jw = jd / "work"
        mounts = ["-v", f"{jw}:{R.AGENT_WORK}", "-v", f"{home}:{R.AGENT_HOME}",
                  "-v", f"{R.GRADLE_RO}:{R.AGENT_GRADLE_RO}:ro"]
        env = [x for k in ("HOME", "TZ", "GRADLE_USER_HOME", "GRADLE_RO_DEP_CACHE", "DB_URL", "DB_USER", "DB_PASSWORD",
                           "PG_URL", "PG_TIMEOUT_MS") for x in ("-e", f"{k}={R.AGENT_ENV[k]}")]
        user = ["--user", f"{R.os.getuid()}:{R.os.getgid()}"]
        bname = f"{name}-jbuild"
        try:
            b = subprocess.run(["docker", "run", "--rm", "--name", bname, "--label", f"{R.LABEL}={name}",
                                "--network", "none", *user, *mounts, *env, "-w", R.AGENT_WORK, R.IMAGE,
                                "./gradlew", "--console=plain", "-q", ":app:bootJar", ":checks:testClasses"],
                               capture_output=True, text=True, timeout=1800)
            brc, bout = b.returncode, b.stdout + b.stderr
        except subprocess.TimeoutExpired:
            brc, bout = 124, "빌드 시간 초과(1800초)"
        finally:
            subprocess.run(["docker", "rm", "-f", bname], capture_output=True)
        out["build_ok"] = brc == 0
        if brc != 0:
            out["build_failure"] = "infra" if brc == 124 else build_failure_kind(brc, bout)
            out["build_tail"] = bout[-1500:]
            if out["build_failure"] == "infra":
                out.update(judge_error=f"빌드 인프라 오류 rc={brc}", success=None, success_without_regression=None)
            else:
                out.update(success=False, success_without_regression=False, regression_pass=None)
            return out
        excl_verify = set(read_list(R.ORACLE / case / "verify-exclude.txt"))
        for st in stages_to_run:
            out["stages"][st] = run_stage(R, case, st, jw, name, mounts, env, user, excl_verify, out["hidden_present"])
            sr = out["stages"][st]
            log(f"[judge] {name} {st}: {sr['outcome']} tests={sr.get('tests')} failed={len(sr.get('failed', []))}", flush=True)
        oc = {k: v["outcome"] for k, v in out["stages"].items()}
        infra = [k for k, v in oc.items() if v in ("infra", "no_results")]
        if infra:
            out["judge_error"] = f"판정 불가 단계: {infra}"
        out["success_without_regression"] = conjunction(oc, ("public", "hidden"), out["tampered"])
        out["regression_pass"] = conjunction(oc, ("contract", "contract2"), [])
        out["success"] = conjunction(oc, STAGES, out["tampered"])
        if tuple(stages_to_run) != STAGES:  # 과제 검증(check)용 부분 판정 — success 는 돌린 단계만의 AND
            out["partial_stages"] = list(stages_to_run)
            out["success"] = conjunction(oc, stages_to_run, out["tampered"])
        return out
    except Exception as e:  # 판정 준비 자체 오류 — 이미 확정된 단계는 out["stages"] 에 남아 있다
        out["judge_error"] = repr(e)[:800]
        oc = {k: v["outcome"] for k, v in out["stages"].items()}
        out["success"] = False if conjunction(oc, tuple(oc) or STAGES, out.get("tampered") or []) is False else None
        return out


def run_stage(R, case, st, jw, name, mounts, env, user, excl_verify, hidden_present) -> dict:
    """판정 단계 하나 — 예외·시간 초과·정리 실패는 그 단계만 infra 로 남기고 다음 단계로 간다."""
    if st == "hidden" and not hidden_present:
        return {"outcome": "infra", "reason": "숨은 테스트 없음"}
    sn = f"{name}-j{st}"
    for p in (jw / "checks" / "build" / "test-results", jw / "behavior-tests" / "build" / "test-results",
              jw / ".judge" / "out"):
        shutil.rmtree(p, ignore_errors=True)
    o = jw / ".judge" / "out"
    rcode, rtext, err = 0, "", None
    try:
        R.start_services(sn, proxy=False)
        r = subprocess.run(["docker", "run", "--rm", "--name", f"{sn}-judge", "--label", f"{R.LABEL}={sn}",
                            "--network", f"container:{sn}-pg", *user, *mounts, *env, "-w", R.AGENT_WORK,
                            R.IMAGE, "bash", "/work/.judge/stage.sh", st],
                           capture_output=True, text=True, timeout=3600)
        rcode, rtext = r.returncode, r.stdout + r.stderr
    except Exception as e:
        err = repr(e)[:500]
    finally:
        left = R.remove_run(sn)
    rf = lambda n: (o / n).read_text(errors="replace") if (o / n).is_file() else None  # noqa: E731
    status = (rf(f"{st}.status") or "missing").strip()
    rc_txt = rf(f"{st}.rc")
    rc = int(rc_txt.strip()) if rc_txt and rc_txt.strip().lstrip("-").isdigit() else None
    rdirs = [jw / "checks" / "build" / "test-results" / "test"] if st in ("public", "hidden") else \
        [jw / "behavior-tests" / "build" / "test-results" / ("contractTest" if st == "contract" else "contract2Test")]
    res = junit_results(rdirs)
    sr = {"status": status, "rc": rc, **res, "outcome": stage_outcome(status, rc, res)}
    if res["failed"]:
        sr["failure_messages"] = failure_messages(rdirs)
    if st in ("contract", "contract2") and status == "ran":
        vtxt = rf(f"{st}.verify") or ""
        vrc_txt = rf(f"{st}.verify.rc")
        vrc = int(vrc_txt.strip()) if vrc_txt and vrc_txt.strip().lstrip("-").isdigit() else -1
        sr["verify"] = verify_result(vtxt, vrc, excl_verify)
        if sr["verify"]["ok"] is None and sr["outcome"] != "fail":
            sr["outcome"] = "infra"
        elif sr["outcome"] == "pass" and not sr["verify"]["ok"]:
            sr["outcome"] = "fail"
    problems = []
    if err:
        problems.append(f"예외 {err}")
    if rcode:
        problems.append(f"판정 컨테이너 rc={rcode}")
    if left:
        problems.append(f"정리 잔여 {left}")  # 자원이 남은 단계는 다음 단계·다른 실행에 영향을 줄 수 있다
        sr["cleanup_left"] = left
    if problems and sr["outcome"] != "fail":
        sr["outcome"] = "infra"
    if problems:
        sr["infra_problems"] = problems
    if sr["outcome"] in ("infra", "no_results", "app_start_fail"):
        sr["tail"] = (rf(f"{st}.log") or rtext)[-1200:]
        if status.startswith("app_"):
            sr["app_log_tail"] = (rf(f"{st}.app.log") or "")[-1200:]
    return sr
