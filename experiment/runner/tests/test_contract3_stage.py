"""판정 회귀 contract3 단계(ADR-0005 §4.1 개정 3 — 단계 A 계약 contract3Test, 기준 168).

계약: 회귀 = contract ∧ contract2 ∧ contract3(각 단계 새 DB·앱·가짜 PG, 결과 XML 은 그 단계 태스크 디렉터리만,
verify.sql 도 단계마다), regression-exclude 가 있으면 기준 수 검사 없음, 부분 판정은 돌린 단계만의 AND.
docker·gradle 없이 판정기 경계(subprocess·runner 자원)를 가짜로 바꿔 확인한다.
"""
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import judge  # noqa: E402
import runner  # noqa: E402

STAGE_SH = HERE.parent / "templates" / "judge-stage.sh"
VERIFY_OK = "NOTICE:  verify: 25 개 검사 모두 통과\n"
VERIFY_BAD = "NOTICE:  verify: 불변식 위반 2 건 — ledger_balance=2\n"


def junit(n: int, failed: int = 0) -> str:
    cases = "".join(f'<testcase classname="com.ccle.behavior.contract3.T" name="t{i}">'
                    + ('<failure message="boom"/>' if i < failed else "") + "</testcase>" for i in range(n))
    return f'<?xml version="1.0"?><testsuite tests="{n}">{cases}</testsuite>'


class Constants(unittest.TestCase):
    def test_stage_and_baseline(self):
        self.assertEqual(judge.STAGES[-1], "contract3")
        self.assertEqual(judge.REGRESSION_BASELINE, {"contract": 122, "contract2": 89, "contract3": 168})
        self.assertEqual(judge.REGRESSION_STAGES, ("contract", "contract2", "contract3"))
        self.assertEqual(judge.REGRESSION_TASKS["contract3"], "contract3Test")
        self.assertEqual(judge.REGRESSION_TASKS["contract"], "contractTest")
        self.assertEqual(judge.REGRESSION_TASKS["contract2"], "contract2Test")


class Expected(unittest.TestCase):
    """prepare 의 expected_tests: regression-exclude 가 비면 contract3 168 포함, 있으면 회귀 기준 수 전부 없음."""

    def _expected(self, excl_lines):
        with tempfile.TemporaryDirectory() as d:
            oracle = Path(d, "oracle")
            (oracle / "_smoke").mkdir(parents=True)
            if excl_lines is not None:
                (oracle / "_smoke" / "regression-exclude.txt").write_text(excl_lines)
            agent = Path(d, "agent")
            runner.build_work("_smoke", "god", agent)
            jd = Path(d, "jd")
            jd.mkdir()
            with mock.patch.object(runner, "ORACLE", oracle):
                return judge.prepare("_smoke", "god", agent, jd)["expected_tests"]

    def test_no_exclude(self):
        e = self._expected(None)
        self.assertEqual({k: e[k] for k in judge.REGRESSION_STAGES}, {"contract": 122, "contract2": 89, "contract3": 168})

    def test_with_exclude(self):
        """제외 패턴이 닿는 단계만 기준 수 검사를 끈다(C2처럼 contract2 메서드 하나만 빼면 contract·contract3 기준은 남는다)."""
        e = self._expected("com.ccle.behavior.contract3.A1ReviewTest.x*  # 사유\n")
        self.assertEqual({k: e.get(k) for k in judge.REGRESSION_STAGES}, {"contract": 122, "contract2": 89, "contract3": None})
        e = self._expected("com.ccle.behavior.contract2.PenaltyTest.판매 제한*  # 사유\n")
        self.assertEqual({k: e.get(k) for k in judge.REGRESSION_STAGES}, {"contract": 122, "contract2": None, "contract3": 168})
        e = self._expected("com.ccle.behavior.contract.BidTest.x*  # 사유\n")
        self.assertEqual({k: e.get(k) for k in judge.REGRESSION_STAGES}, {"contract": None, "contract2": 89, "contract3": 168})

    def test_with_unscoped_exclude(self):
        """패키지로 시작하지 않는 패턴은 어느 단계에 닿을지 모르므로 회귀 기준 수 전부 끈다."""
        e = self._expected("*PenaltyTest*  # 사유\n")
        for k in judge.REGRESSION_STAGES:
            self.assertNotIn(k, e)


class StageOnce(unittest.TestCase):
    """run_stage_once: contract3 결과는 contract3Test 디렉터리만 읽고 verify 를 붙인다."""

    def setUp(self):
        self.td = tempfile.TemporaryDirectory()
        self.tw = Path(self.td.name, "tests")
        (self.tw / ".judge").mkdir(parents=True)
        self.R = SimpleNamespace(start_services=lambda *a, **k: None, remove_run=lambda n: [],
                                 AGENT_ENV={k: "x" for k in judge.APP_ENV}, LABEL="l", IMAGE="img", AGENT_WORK="/work")

    def tearDown(self):
        self.td.cleanup()

    def _run(self, st, xml_by_task, verify=VERIFY_OK, verify_rc=0, rc=0):
        tw = self.tw

        def fake(cmd, **kw):
            if cmd[:2] == ["docker", "run"] and "/work/.judge/stage.sh" in cmd:  # 판정 컨테이너 = stage.sh 의 산출물 흉내
                o = tw / ".judge" / "out"
                for task, x in xml_by_task.items():
                    d = tw / "behavior-tests" / "build" / "test-results" / task
                    d.mkdir(parents=True, exist_ok=True)
                    (d / "TEST-x.xml").write_text(x)
                (o / f"{st}.status").write_text("ran\n")
                (o / f"{st}.rc").write_text(f"{rc}\n")
                (o / f"{st}.verify").write_text(verify)
                (o / f"{st}.verify.rc").write_text(f"{verify_rc}\n")
            out = "true\n" if cmd[:2] == ["docker", "inspect"] else ""
            return subprocess.CompletedProcess(cmd, 0, out, "")

        with mock.patch.object(judge.subprocess, "run", side_effect=fake):
            return judge.run_stage_once(self.R, "_smoke", st, tw, Path(self.td.name, "app"), "n", [], [], [], set(), True)

    def test_contract3_pass_reads_own_dir(self):
        sr = self._run("contract3", {"contract3Test": junit(168), "contract2Test": junit(89, failed=3)})
        self.assertEqual(sr["outcome"], "pass")
        self.assertEqual(sr["tests"], 168)
        self.assertEqual(sr["failed"], [])
        self.assertIs(sr["verify"]["ok"], True)

    def test_contract3_missing_results_is_no_results(self):
        sr = self._run("contract3", {"contract2Test": junit(89)})  # 다른 단계 결과를 contract3 로 세지 않는다
        self.assertEqual(sr["outcome"], "no_results")

    def test_contract3_test_failure(self):
        sr = self._run("contract3", {"contract3Test": junit(168, failed=1)}, rc=1)
        self.assertEqual(sr["outcome"], "fail")
        self.assertIn("com.ccle.behavior.contract3.T.t0", sr["failure_messages"])

    def test_contract3_verify_violation_fails(self):
        sr = self._run("contract3", {"contract3Test": junit(168)}, verify=VERIFY_BAD, verify_rc=3)
        self.assertEqual(sr["outcome"], "fail")
        self.assertEqual(sr["verify"]["violations"], {"ledger_balance": 2})

    def test_contract3_verify_unreadable_is_infra(self):
        sr = self._run("contract3", {"contract3Test": junit(168)}, verify="garbage", verify_rc=1)
        self.assertEqual(sr["outcome"], "infra")

    def test_existing_stages_still_read_their_dirs(self):  # 기존 판정 불변
        sr = self._run("contract", {"contractTest": junit(122), "contract3Test": junit(168, failed=2)})
        self.assertEqual((sr["outcome"], sr["tests"]), ("pass", 122))
        self.assertIn("verify", sr)
        sr = self._run("contract2", {"contract2Test": junit(89), "contract3Test": junit(168, failed=2)})
        self.assertEqual((sr["outcome"], sr["tests"]), ("pass", 89))
        self.assertIn("verify", sr)

    def test_unknown_stage_not_counted(self):
        sr = self._run("contract4", {"contract3Test": junit(168)})
        self.assertEqual(sr["outcome"], "no_results")
        self.assertNotIn("verify", sr)


class JudgeCombination(unittest.TestCase):
    """judge(): success·regression_pass 가 contract3 까지 결합되고, 부분 판정은 돌린 단계만 본다."""

    def _judge(self, outcomes, stages=judge.STAGES, tampered=(), expected=None):
        with tempfile.TemporaryDirectory() as d:
            jd = Path(d)
            libs = jd / "work" / "app" / "build" / "libs"
            libs.mkdir(parents=True)
            (libs / "app-0.0.1.jar").write_text("jar")
            (jd / "tests").mkdir()
            prep = {"tampered": list(tampered), "hidden_present": True, "build_files_changed": [],
                    "escaping_links_removed": [], "expected_tests": expected or dict(judge.REGRESSION_BASELINE)}
            ran = []

            def stage(R, case, st, *a, **k):
                ran.append(st)
                return {"outcome": outcomes[st], "tests": {"contract": 122, "contract2": 89, "contract3": 168}.get(st, 1)}

            with mock.patch.object(judge, "prepare", return_value=prep), \
                    mock.patch.object(judge, "run_stage", side_effect=stage), \
                    mock.patch.object(runner, "make_home", lambda *a, **k: None), \
                    mock.patch.object(judge.subprocess, "run",
                                      return_value=subprocess.CompletedProcess([], 0, "", "")):
                res = judge.judge("_smoke", "god", jd / "agent", jd, "n", log=lambda *a, **k: None,
                                  stages_to_run=stages)
            return res, ran

    ALL_PASS = {s: "pass" for s in judge.STAGES}

    def test_all_pass(self):
        res, ran = self._judge(self.ALL_PASS)
        self.assertEqual(ran, list(judge.STAGES))
        self.assertIs(res["success"], True)
        self.assertIs(res["regression_pass"], True)
        self.assertNotIn("partial_stages", res)

    def test_contract3_fail_fails_regression_and_success(self):
        res, _ = self._judge({**self.ALL_PASS, "contract3": "fail"})
        self.assertIs(res["regression_pass"], False)
        self.assertIs(res["success"], False)
        self.assertIs(res["success_without_regression"], True)

    def test_contract3_infra_is_undecided(self):
        res, _ = self._judge({**self.ALL_PASS, "contract3": "infra"})
        self.assertIsNone(res["regression_pass"])
        self.assertIsNone(res["success"])
        self.assertIn("contract3", res["judge_error"])

    def test_count_mismatch_on_contract3_fails(self):
        res, _ = self._judge(self.ALL_PASS, expected={**judge.REGRESSION_BASELINE, "contract3": 999})
        self.assertEqual(res["stages"]["contract3"]["outcome"], "fail")
        self.assertEqual(res["stages"]["contract3"]["count_mismatch"], {"expected": 999, "ran": 168})
        self.assertIs(res["success"], False)

    def test_partial_regression_only(self):
        st = ("contract", "contract2", "contract3")
        res, ran = self._judge(self.ALL_PASS, stages=st)
        self.assertEqual(ran, list(st))
        self.assertEqual(res["partial_stages"], list(st))
        self.assertIs(res["success"], True)
        self.assertIs(res["regression_pass"], True)

    def test_partial_without_contract3_leaves_regression_undecided(self):
        res, _ = self._judge(self.ALL_PASS, stages=("contract", "contract2"))
        self.assertIs(res["success"], True)          # 돌린 단계만의 AND
        self.assertIsNone(res["regression_pass"])    # contract3 를 안 돌렸으니 회귀는 미정


class StageScript(unittest.TestCase):
    """judge-stage.sh: contract3 → :behavior-tests:contract3Test + verify.sql. /work·curl·psql·gradlew 는 가짜."""

    def _run(self, stage):
        td = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, td)
        work, bin_ = Path(td, "work"), Path(td, "bin")
        (work / ".judge").mkdir(parents=True)
        bin_.mkdir()
        script = STAGE_SH.read_text().replace("/work", str(work))
        (work / ".judge" / "stage.sh").write_text(script)
        (work / ".judge" / "verify.sql").write_text("select 1;\n")

        def exe(p: Path, body: str):
            p.write_text("#!/usr/bin/env bash\n" + body)
            p.chmod(p.stat().st_mode | stat.S_IEXEC)

        exe(bin_ / "curl", "exit 0\n")
        exe(bin_ / "psql", f'echo "psql $*" >> {work}/calls; echo "NOTICE:  verify: 1 개 검사 모두 통과"\n')
        exe(work / "gradlew", f'echo "gradlew $*" >> {work}/calls\n')
        env = {**os.environ, "PATH": f"{bin_}:{os.environ['PATH']}", "DB_PASSWORD": "p", "DB_USER": "u"}
        subprocess.run(["bash", str(work / ".judge" / "stage.sh"), stage], env=env, check=True, timeout=60)
        out = work / ".judge" / "out"
        calls = (work / "calls").read_text()
        self.work = work
        return calls, out

    def test_contract3_task_and_verify(self):
        calls, out = self._run("contract3")
        self.assertIn(":behavior-tests:contract3Test --rerun --continue", calls)
        self.assertIn(f"-I {self.work}/.judge/exclude.gradle", calls)  # regression-exclude 초기화 스크립트
        self.assertIn("psql", calls)
        self.assertEqual((out / "contract3.status").read_text().strip(), "ran")
        self.assertEqual((out / "contract3.rc").read_text().strip(), "0")
        self.assertEqual((out / "contract3.verify.rc").read_text().strip(), "0")

    def test_existing_mapping_unchanged(self):
        for st, task in (("contract", "contractTest"), ("contract2", "contract2Test")):
            calls, out = self._run(st)
            self.assertIn(f":behavior-tests:{task} --rerun --continue", calls)
            self.assertIn("psql", calls)
            self.assertTrue((out / f"{st}.verify.rc").is_file())

    def test_public_has_no_verify(self):
        calls, out = self._run("public")
        self.assertIn(":checks:test --tests pub.*", calls)
        self.assertNotIn("psql", calls)
        self.assertFalse((out / "public.verify.rc").exists())


if __name__ == "__main__":
    unittest.main()
