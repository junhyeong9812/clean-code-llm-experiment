"""과제 A 리뷰 루프 3 수정 확인(fix verification): 판정 테스트 작업 공간 분리·기동 지연 확정 시점·재판정 상한·보관 마스킹."""
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import judge  # noqa: E402
import runner  # noqa: E402


class TestWorkspace(unittest.TestCase):
    def test_agent_files_never_reach_test_workspace(self):
        with tempfile.TemporaryDirectory() as d:
            agent = Path(d, "agent")
            runner.build_work("T1", "god", agent)
            # 에이전트가 심을 수 있는 것들: 앱 빌드 스크립트 조작, 지원 코드 변조, 예약 경로 선점
            (agent / "app" / "build.gradle.kts").write_text("gradle.taskGraph.whenReady { /* 테스트 끄기 */ }")
            sup = next((agent / "checks" / "src" / "test" / "kotlin" / "support").glob("*.kt"))
            sup.write_text("// 변조")
            (agent / ".judge").mkdir()
            (agent / "behavior-tests").mkdir()
            (agent / "checks" / "src" / "test" / "kotlin" / "hidden").mkdir()
            jd = Path(d, "jd")
            jd.mkdir()
            res = judge.prepare("T1", "god", agent, jd)  # 예약 경로가 사본에 있어도 예외 없이
            tw = jd / "tests"
            self.assertFalse((tw / "app").exists())
            self.assertEqual((tw / "settings.gradle.kts").read_text(), judge.JUDGE_SETTINGS)
            self.assertEqual((tw / "build.gradle.kts").read_text(), judge.JUDGE_ROOT_BUILD)
            self.assertNotEqual((tw / "checks" / sup.relative_to(agent / "checks")).read_text(), "// 변조")
            self.assertTrue(any((tw / "checks" / "src" / "test" / "kotlin" / "hidden").rglob("*.kt")))
            self.assertTrue((tw / ".judge" / "stage.sh").is_file())
            self.assertTrue((tw / "gradlew").is_file())
            self.assertTrue(any("support" in x for x in res["tampered"]))


class StageRetry(unittest.TestCase):
    def _run(self, statuses, final):
        it = iter(statuses)
        with mock.patch.object(judge, "run_stage_once",
                               side_effect=lambda *a, **k: {"status": next(it), "outcome": "infra"}):
            return judge.run_stage(*([None] * 11), final_timeout=final)

    def test_two_timeouts_not_final_is_infra(self):
        r = self._run(["app_start_timeout", "app_start_timeout"], False)
        self.assertEqual(r["outcome"], "infra")
        self.assertIn("timeout_suspect", r["reason"])

    def test_two_timeouts_final_is_fail(self):
        r = self._run(["stage_timeout", "app_start_timeout"], True)
        self.assertEqual(r["outcome"], "fail")
        self.assertIn("timeout_fail", r["reason"])

    def test_retry_then_ran(self):
        r = self._run(["app_start_timeout", "ran"], False)
        self.assertEqual(r["status"], "ran")
        self.assertEqual(r["first_attempt"]["status"], "app_start_timeout")


class CountCheck(unittest.TestCase):
    def test_mismatch_pass_becomes_fail(self):
        self.assertEqual(judge.check_count({"outcome": "pass", "tests": 1}, 6)["outcome"], "fail")
        self.assertEqual(judge.check_count({"outcome": "pass", "tests": 6}, 6)["outcome"], "pass")
        self.assertEqual(judge.check_count({"outcome": "fail", "tests": 1}, 6)["outcome"], "fail")
        self.assertEqual(judge.check_count({"outcome": "pass", "tests": 1}, None)["outcome"], "pass")


class Rejudge(unittest.TestCase):
    ROW = {"case": "T1", "structure": "god", "model": "sonnet", "rep": 1, "run_id": "r1", "turns": 12,
           "build_ok": False, "build_failure": "infra", "build_tail": "x", "success": None, "held": True,
           "valid": False, "invalid_reason": "judge_infra"}

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.res = Path(self.tmp.name, "results")
        h = self.res / "held" / "r1"
        (h / "work").mkdir(parents=True)
        (h / "row.json").write_text(json.dumps(self.ROW))
        self.p = mock.patch.object(runner, "RUNS", Path(self.tmp.name, "runs"))
        self.p.start()

    def tearDown(self):
        self.p.stop()
        self.tmp.cleanup()

    def rows(self):
        f = self.res / "runs.jsonl"
        return [json.loads(x) for x in f.read_text().splitlines()] if f.is_file() else []

    def test_resolved_row_replaces_judge_fields(self):
        calls = []
        with mock.patch.object(runner.judging, "judge",
                               side_effect=lambda *a, **k: calls.append(k) or
                               {"judged": True, "build_ok": True, "stages": {"public": {"outcome": "pass"}},
                                "success": True}):
            self.assertEqual(runner.rejudge_held(self.res), 1)
        r = self.rows()[0]
        self.assertTrue(calls[0]["final_timeout"])
        self.assertEqual((r["valid"], r["success"], r["turns"]), (True, True, 12))
        self.assertNotIn("build_failure", r)
        self.assertNotIn("build_tail", r)
        self.assertNotIn("held", r)
        self.assertIn("finished_at", r)
        self.assertFalse((self.res / "held" / "r1").exists())

    def test_unresolved_after_cap(self):
        with mock.patch.object(runner.judging, "judge", return_value={"judged": True, "success": None}):
            self.assertEqual(runner.rejudge_held(self.res), 0)
            self.assertEqual(self.rows(), [])
            self.assertEqual(json.loads((self.res / "held" / "r1" / "row.json").read_text())["rejudge_tries"], 1)
            self.assertEqual(runner.rejudge_held(self.res), 1)
        r = self.rows()[0]
        self.assertEqual((r["valid"], r["invalid_reason"], r["rejudge_tries"]), (False, "judge_unresolved", 2))
        self.assertFalse((self.res / "held" / "r1").exists())
        # 종단 — 자동 재실행 대상에서 빠진다(보관본이 지워져도)
        self.assertIn(("T1", "god", "sonnet", 1), runner.done_keys(self.res))

    def test_broken_row_skipped(self):
        bad = self.res / "held" / "r0"
        bad.mkdir()
        (bad / "row.json").write_text("{깨짐")
        with mock.patch.object(runner.judging, "judge", return_value={"judged": True, "success": False}):
            self.assertEqual(runner.rejudge_held(self.res), 1)
        self.assertTrue(bad.exists())


class HeldMask(unittest.TestCase):
    def test_mask_tree(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d, "a")).mkdir()
            Path(d, "a", "c.json").write_text('{"refreshToken":"sk-ant-ort01-SECRET"}')
            Path(d, "b.txt").write_text("nothing")
            self.assertEqual(runner.mask_tree(Path(d), ["sk-ant-ort01-SECRET"]), 1)
            self.assertNotIn("SECRET", Path(d, "a", "c.json").read_text())


if __name__ == "__main__":
    unittest.main()
