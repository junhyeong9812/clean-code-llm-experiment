"""판정 단계 (ADR-0005 §4.1). 에이전트 실행이 끝난 사본을 복제해, 실행마다 새 판정 컨테이너에서 단계별로 돌린다:
변조 검사 → 빌드(네트워크 없음) → 공개 → 숨은 → 회귀(contractTest·contract2Test·contract3Test − regression-exclude, verify.sql −
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

STAGES = ("public", "hidden", "contract", "contract2", "contract3")
# 회귀 단계 → behavior-tests Gradle 태스크(결과 XML 디렉터리 이름과 같다). judge-stage.sh 의 매핑과 맞춘다
REGRESSION_TASKS = {"contract": "contractTest", "contract2": "contract2Test", "contract3": "contract3Test"}
REGRESSION_STAGES = tuple(REGRESSION_TASKS)
PROTECTED = ("TASK.md", "test.sh")          # + checks/ 전체(지원 코드·공개 테스트)
VERIFY_FAILED = re.compile(r"verify: 불변식 위반 \d+ 건 — (.+)")
VERIFY_OK = re.compile(r"verify: \d+ 개 검사 모두 통과")
# 판정이 정하는 행 필드 — 재판정 때 첫 판정 값을 남기지 않고 전부 새로 쓴다
JUDGE_FIELDS = ("judged", "judge_skip", "stages", "judge_error", "success", "success_without_regression", "success_stage12",
                "regression_pass", "build_ok", "build_failure", "build_tail", "test_build_tail", "tampered",
                "hidden_present", "build_files_changed", "escaping_links_removed", "expected_tests", "partial_stages",
                "held", "valid", "invalid_reason")


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
    failed, broken = [], []
    for d in dirs:
        for x in sorted(Path(d).glob("*.xml")) if Path(d).is_dir() else []:
            try:
                root = ET.parse(x).getroot()
            except ET.ParseError:
                broken.append(x.name)  # 잘린 결과(디스크·강제 종료) — 테스트 실패가 아니라 판정 불가
                continue
            for tc in root.iter("testcase"):
                tests += 1
                name = f"{tc.get('classname')}.{tc.get('name')}"
                if tc.find("failure") is not None or tc.find("error") is not None:
                    failed.append(name)
                elif tc.find("skipped") is not None:
                    skipped += 1
    out = {"tests": tests, "failed": failed, "skipped": skipped}
    if broken:
        out["broken"] = broken
    return out


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
    if res.get("broken"):
        return "infra"
    if res["tests"] - res.get("skipped", 0) == 0:  # 전부 건너뜀도 '돌지 않음'(fail-open 차단)
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


GRADLE_SCRIPT = re.compile(r"(\.gradle(\.kts)?|gradle\.properties|\.versions\.toml)$")
# 회귀 기준 실행 수(기준 사본 실측 2026-10-26 — behavior-tests 는 이 작업에서 바뀌지 않는다, 매개변수화 테스트 포함).
# contract3 = 단계 A 계약(ADR-0007) 251(단계 A 168 + §20 보충 83, SlowTriggerSelfTest 포함 — ADR-0005 §4.1 개정 3)
REGRESSION_BASELINE = {"contract": 122, "contract2": 89, "contract3": 251}
TEST_ANNOTATION = re.compile(r"@Test\b")


def excl_touches(excl: list[str], stage: str) -> bool:
    """regression-exclude 패턴이 그 회귀 단계의 테스트에 닿을 수 있나. 다른 회귀 단계 패키지(com.ccle.behavior.<패키지>.)로
    시작하는 패턴만 닿지 않는다고 본다 — 와일드카드 등 다른 모양은 보수적으로 닿는다(그 단계 기준 수 검사를 끈다)."""
    pkg = {s: f"com.ccle.behavior.{t[:-len('Test')]}." for s, t in REGRESSION_TASKS.items()}
    others = [v for s, v in pkg.items() if s != stage]
    return any(not any(p.startswith(o) for o in others) for p in excl)


def remove_extra_build_scripts(pristine: Path, work: Path) -> list:
    """원본에 없는 Gradle 스크립트(*.gradle·*.gradle.kts·gradle.properties)와 buildSrc/ 를 지운다 — 에이전트가 하위
    프로젝트 빌드 스크립트로 테스트를 끄거나 결과를 꾸미는 경로 차단. 지운 경로를 돌려준다."""
    removed = []
    if (work / "buildSrc").exists():
        shutil.rmtree(work / "buildSrc")
        removed.append("buildSrc/")
    for f in sorted(work.rglob("*")):
        if f.is_file() and GRADLE_SCRIPT.search(f.name):
            rel = f.relative_to(work)
            if rel.parts and rel.parts[0] in ("build", ".gradle"):
                continue
            if not (pristine / rel).is_file():
                f.unlink()
                removed.append(rel.as_posix())
    return removed


def remove_escaping_links(work: Path) -> list:
    """사본 밖(또는 절대 경로)을 가리키는 심볼릭 링크를 지운다 — 호스트의 판정 준비(복원·쓰기·sha)가 링크를 따라
    사본 밖 파일을 읽거나 덮어쓰지 않게. 판정 컨테이너 안에서는 원래 그 경로가 없으니 동작 차이도 없다."""
    removed = []
    root = work.resolve()
    for d, dirs, files in __import__("os").walk(work, followlinks=False):
        for x in dirs + files:
            p = Path(d, x)
            if p.is_symlink():
                tgt = (p.parent / __import__("os").readlink(p))
                try:
                    inside = tgt.resolve().is_relative_to(root) and not __import__("os").readlink(p).startswith("/")
                except (OSError, RuntimeError):
                    inside = False
                if not inside:
                    p.unlink()
                    removed.append(p.relative_to(work).as_posix())
    return removed


def count_tests(*dirs: Path) -> int:
    """소스의 @Test 수(공개·숨은 테스트는 매개변수화 없이 쓴다 — 실행 수와 같아야 한다)."""
    return sum(len(TEST_ANNOTATION.findall(f.read_text(errors="replace")))
               for d in dirs if d.is_dir() for f in d.rglob("*.kt"))


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


# 테스트 작업 공간의 루트 빌드(앱 모듈 없음) — 플러그인 버전은 사본 템플릿과 같다
JUDGE_ROOT_BUILD = """plugins {
    kotlin("jvm") version "2.2.20" apply false
}
"""
JUDGE_SETTINGS = 'rootProject.name = "judge"\ninclude("checks", "behavior-tests")\n'


def prepare(case: str, structure: str, work: Path, jd: Path) -> dict:
    """판정 디렉터리 두 개.
    - jd/work: 에이전트 사본 복제(보호 파일·루트 빌드 파일 복원) — 앱 jar 빌드에만 쓴다.
    - jd/tests: 판정 전용 테스트 작업 공간 — 원본 checks/ + 숨은 테스트 + behavior-tests + 판정 자산. 에이전트가 만든
      파일이 하나도 없다(에이전트 코드는 jar 빌드와 별도 앱 컨테이너에서만 돈다 → 테스트·빌드 설정·결과를 못 바꾼다)."""
    import runner as R
    pristine = jd / "pristine"
    R.build_work(case, structure, pristine)
    jw = jd / "work"
    shutil.copytree(work, jw, symlinks=True, ignore=shutil.ignore_patterns("build", ".gradle", ".kotlin"))
    links = remove_escaping_links(jw)
    tam = tampered(pristine, jw)
    restore(pristine, jw)
    changed_build = restore_build_files(pristine, jw) + remove_extra_build_scripts(pristine, jw)
    tw = jd / "tests"
    tw.mkdir()
    for rel in BUILD_FILES + ("gradle.properties",):
        if (pristine / rel).is_file() and rel not in ("build.gradle.kts", "settings.gradle.kts"):
            (tw / rel).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(pristine / rel, tw / rel)
    (tw / "build.gradle.kts").write_text(JUDGE_ROOT_BUILD)
    (tw / "settings.gradle.kts").write_text(JUDGE_SETTINGS)
    shutil.copytree(pristine / "checks", tw / "checks", ignore=shutil.ignore_patterns("build", ".gradle"))
    hidden = R.ORACLE / case / "hidden"
    if hidden.is_dir():
        shutil.copytree(hidden, tw / "checks" / "src" / "test" / "kotlin" / "hidden")
    shutil.copytree(R.ROOT / "behavior-tests", tw / "behavior-tests", ignore=shutil.ignore_patterns("build", ".gradle"))
    j = tw / ".judge"
    j.mkdir()
    shutil.copy2(R.HERE / "templates" / "judge-stage.sh", j / "stage.sh")
    shutil.copy2(R.ROOT / "tools" / "seedgen" / "verify.sql", j / "verify.sql")
    (j / "exclude.gradle").write_text(EXCLUDE_GRADLE)
    (j / "regression-exclude.txt").write_text("\n".join(read_list(R.ORACLE / case / "regression-exclude.txt")) + "\n")
    excl = read_list(R.ORACLE / case / "regression-exclude.txt")
    expected = {"public": count_tests(pristine / "checks" / "src" / "test" / "kotlin" / "pub"),
                "hidden": count_tests(hidden),
                **{k: v for k, v in REGRESSION_BASELINE.items() if not excl_touches(excl, k)}}
    return {"tampered": tam, "hidden_present": hidden.is_dir(), "build_files_changed": changed_build,
            "escaping_links_removed": links,
            "expected_tests": expected}


def judge(case: str, structure: str, work: Path, jd: Path, name: str, log=print, stages_to_run=STAGES,
          final_timeout: bool = False) -> dict:
    """한 실행의 판정. work = 에이전트가 끝낸 사본(읽기만), jd = 판정 전용 디렉터리(호출자가 지운다).
    결과 success: True | False(확정 실패) | None(판정 불가 — 호출자는 무효로 다룬다).
    final_timeout: 기동 지연·시간 초과가 2회 연속이면 확정 실패로 본다(재판정 — 에이전트 부하가 없는 다른 시점).
    아니면 판정 불가로 남겨 보관·재판정한다(병렬 실행 부하를 에이전트 탓으로 세지 않게)."""
    import runner as R
    out = {"judged": True, "stages": {}}
    try:
        out.update(prepare(case, structure, work, jd))
        home = jd / "home"
        R.make_home(home, 0, with_creds=False)
        jw, tw = jd / "work", jd / "tests"
        thome = jd / "thome"  # 테스트 작업 공간 전용 HOME — 에이전트 코드가 돈 빌드와 Gradle 사용자 홈을 나누지 않는다
        R.make_home(thome, 0, with_creds=False)
        gro = ["-v", f"{R.GRADLE_RO}:{R.AGENT_GRADLE_RO}:ro"]
        mounts = ["-v", f"{jw}:{R.AGENT_WORK}", "-v", f"{home}:{R.AGENT_HOME}", *gro]
        tmounts = ["-v", f"{tw}:{R.AGENT_WORK}", "-v", f"{thome}:{R.AGENT_HOME}", *gro]
        env = [x for k in ("HOME", "TZ", "GRADLE_USER_HOME", "GRADLE_RO_DEP_CACHE", "DB_URL", "DB_USER", "DB_PASSWORD",
                           "PG_URL", "PG_TIMEOUT_MS") for x in ("-e", f"{k}={R.AGENT_ENV[k]}")]
        user = ["--user", f"{R.os.getuid()}:{R.os.getgid()}"]
        bname = f"{name}-jbuild"
        try:
            b = subprocess.run(["docker", "run", "--rm", "--name", bname, "--label", f"{R.LABEL}={name}",
                                "--network", "none", *user, *mounts, *env, "-w", R.AGENT_WORK, R.IMAGE,
                                "./gradlew", "--console=plain", "-q", ":app:bootJar"],
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
                out.update(judge_error=f"빌드 인프라 오류 rc={brc}", success=None, success_without_regression=None, success_stage12=None)
            else:
                out.update(success=False, success_without_regression=False, success_stage12=False, regression_pass=None)
            return out
        jars = sorted(x for x in (jw / "app" / "build" / "libs").glob("*.jar") if not x.name.endswith("-plain.jar")) \
            if (jw / "app" / "build" / "libs").is_dir() else []
        if not jars:
            out.update(judge_error="bootJar 성공인데 jar 없음", success=None, success_without_regression=None, success_stage12=None)
            return out
        appd = jd / "app"
        appd.mkdir()
        shutil.copyfile(jars[0], appd / "app.jar")  # 앱 컨테이너엔 이 jar 하나만(읽기 전용) — 사본·HOME 은 마운트하지 않는다
        tname = f"{name}-tbuild"
        try:  # 테스트 코드 컴파일(판정 재료만 — 실패는 판정 인프라 탓)
            t = subprocess.run(["docker", "run", "--rm", "--name", tname, "--label", f"{R.LABEL}={name}",
                                "--network", "none", *user, *tmounts, *env, "-w", R.AGENT_WORK, R.IMAGE,
                                "./gradlew", "--console=plain", "-q", ":checks:testClasses",
                                ":behavior-tests:testClasses"], capture_output=True, text=True, timeout=1800)
            trc, tout = t.returncode, t.stdout + t.stderr
        except subprocess.TimeoutExpired:
            trc, tout = 124, "테스트 컴파일 시간 초과(1800초)"
        finally:
            subprocess.run(["docker", "rm", "-f", tname], capture_output=True)
        if trc != 0:
            out.update(judge_error=f"테스트 컴파일 실패 rc={trc}(판정 재료)", test_build_tail=tout[-1500:],
                       success=None, success_without_regression=None, success_stage12=None)
            return out
        excl_verify = set(read_list(R.ORACLE / case / "verify-exclude.txt"))
        for st in stages_to_run:
            out["stages"][st] = run_stage(R, case, st, tw, appd, name, tmounts, env, user, excl_verify,
                                          out["hidden_present"], final_timeout)
            sr = check_count(out["stages"][st], out["expected_tests"].get(st))
            log(f"[judge] {name} {st}: {sr['outcome']} tests={sr.get('tests')} failed={len(sr.get('failed', []))}", flush=True)
        oc = {k: v["outcome"] for k, v in out["stages"].items()}
        infra = [k for k, v in oc.items() if v in ("infra", "no_results")]
        if infra:
            out["judge_error"] = f"판정 불가 단계: {infra}"
        out["success_without_regression"] = conjunction(oc, ("public", "hidden"), out["tampered"])
        out["regression_pass"] = conjunction(oc, REGRESSION_STAGES, [])
        # 보조(ADR-0006 §6.1-5): 1·2회차와 비교용 — 회귀를 contract·contract2 로만 본 성공
        out["success_stage12"] = conjunction(oc, ("public", "hidden", "contract", "contract2"), out["tampered"])
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


def check_count(sr: dict, exp) -> dict:
    """예정된 테스트가 그대로 돌지 않은 통과(빌드 조작·결과 위조 의심)는 통과로 세지 않는다."""
    if exp is not None and sr.get("tests") is not None and sr.get("outcome") == "pass" and sr["tests"] != exp:
        sr.update(outcome="fail", count_mismatch={"expected": exp, "ran": sr["tests"]})
    return sr


RETRY_STATUSES = ("app_start_timeout", "stage_timeout")


def run_stage(R, case, st, tw, appd, name, mounts, env, user, excl_verify, hidden_present,
              final_timeout=False) -> dict:
    """판정 단계 하나. 앱 기동 지연·단계 시간 초과는 같은 사본으로 1회 다시 시도한다. 다시 같으면
    final_timeout(재판정 — 다른 시점)일 때만 에이전트 코드 탓 확정 실패(timeout_fail), 아니면 판정 불가(보관·재판정).
    확정을 다른 시점으로 미루는 이유: 직후 재시도는 같은 부하 창이라 독립 표본이 아니다."""
    args = (R, case, st, tw, appd, name, mounts, env, user, excl_verify, hidden_present)
    first = run_stage_once(*args, attempt=1)
    if first.get("status") not in RETRY_STATUSES:
        return first
    second = run_stage_once(*args, attempt=2)
    second["first_attempt"] = {k: first.get(k) for k in ("status", "outcome", "infra_problems")}
    if second.get("status") in RETRY_STATUSES:
        if final_timeout:
            second.update(outcome="fail", reason="timeout_fail(다른 시점 재판정에서도 2회 연속 기동 지연·시간 초과)")
        else:
            second.update(outcome="infra", reason="timeout_suspect(2회 연속 — 다른 시점에 재판정)")
    return second


APP_ENV = ("TZ", "DB_URL", "DB_USER", "DB_PASSWORD", "PG_URL", "PG_TIMEOUT_MS")


def run_stage_once(R, case, st, tw, appd, name, mounts, env, user, excl_verify, hidden_present, attempt=1) -> dict:
    """판정 단계 한 번 — 예외·정리 실패는 그 단계만 infra 로 남기고 다음 단계로 간다.
    앱(에이전트 코드)은 별도 컨테이너: jar 하나만 읽기 전용, 사본·HOME·테스트 작업 공간 없음. 같은 네트워크 이름
    공간(가짜 PG 컨테이너)을 함께 써서 테스트 컨테이너가 127.0.0.1:8099 로 붙는다."""
    if st == "hidden" and not hidden_present:
        return {"outcome": "infra", "reason": "숨은 테스트 없음"}
    sn = f"{name}-j{st}{'' if attempt == 1 else attempt}"
    for p in (tw / "checks" / "build" / "test-results", tw / "behavior-tests" / "build" / "test-results",
              tw / ".judge" / "out"):
        shutil.rmtree(p, ignore_errors=True)
    o = tw / ".judge" / "out"
    (o).mkdir(parents=True, exist_ok=True)
    rcode, rtext, err, timed_out, app_running = 0, "", None, False, None
    try:
        R.start_services(sn, proxy=False)
        aenv = [x for k in APP_ENV for x in ("-e", f"{k}={R.AGENT_ENV[k]}")] + ["-e", "SERVER_PORT=8099", "-e", "HOME=/tmp"]
        a = subprocess.run(["docker", "run", "-d", "--name", f"{sn}-app", "--label", f"{R.LABEL}={sn}",
                            "--network", f"container:{sn}-pg", *user, "-v", f"{appd}:/app:ro", *aenv, "-w", "/tmp",
                            R.IMAGE, "java", "-jar", "/app/app.jar"], capture_output=True, text=True, timeout=120)
        if a.returncode:
            raise RuntimeError(f"앱 컨테이너 시작 실패: {(a.stdout + a.stderr)[-300:]}")
        r = subprocess.run(["docker", "run", "--rm", "--name", f"{sn}-judge", "--label", f"{R.LABEL}={sn}",
                            "--network", f"container:{sn}-pg", *user, *mounts, *env, "-w", R.AGENT_WORK,
                            R.IMAGE, "bash", "/work/.judge/stage.sh", st],
                           capture_output=True, text=True, timeout=3600)
        rcode, rtext = r.returncode, r.stdout + r.stderr
    except subprocess.TimeoutExpired:
        err = "단계 시간 초과(3600초)"
        timed_out = True
    except Exception as e:
        err = repr(e)[:500]
    finally:
        try:
            ins = subprocess.run(["docker", "inspect", "-f", "{{.State.Running}}", f"{sn}-app"],
                                 capture_output=True, text=True, timeout=60)
            app_running = ins.stdout.strip() == "true" if ins.returncode == 0 else None
            lg = subprocess.run(["docker", "logs", f"{sn}-app"], capture_output=True, text=True, timeout=60)
            if lg.returncode == 0:
                (o / f"{st}.app.log").write_text((lg.stdout + lg.stderr)[-200000:])
        except Exception:
            pass
        left = R.remove_run(sn)
    rf = lambda n: (o / n).read_text(errors="replace") if (o / n).is_file() else None  # noqa: E731
    status = "stage_timeout" if timed_out else (rf(f"{st}.status") or "missing").strip()
    if status == "app_start_timeout" and app_running is False:  # 기동 대기 중 앱 프로세스가 끝났다 = 앱 탓
        status = "app_died"
    rc_txt = rf(f"{st}.rc")
    rc = int(rc_txt.strip()) if rc_txt and rc_txt.strip().lstrip("-").isdigit() else None
    rdirs = [tw / "checks" / "build" / "test-results" / "test"] if st in ("public", "hidden") else \
        [tw / "behavior-tests" / "build" / "test-results" / REGRESSION_TASKS.get(st, f"unknown-{st}")]
    res = junit_results(rdirs)
    sr = {"status": status, "rc": rc, **res, "outcome": stage_outcome(status, rc, res)}
    if res["failed"]:
        sr["failure_messages"] = failure_messages(rdirs)
    if st in REGRESSION_STAGES and status == "ran":
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
