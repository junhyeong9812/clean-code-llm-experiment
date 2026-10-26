"""과제 A 작업: 러너 소수정(ADR 개정 1 '변경 예정') + 판정 단계 순수 함수."""
import json
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import events  # noqa: E402
import judge  # noqa: E402
import runner  # noqa: E402

SP = events.parse_symbols("app/M.kt:100-110 fix")


def bash(cmd, result="x", turn=1):
    return events.Call(turn, "Bash", {"command": cmd}, "t", False, result)


class SedRule(unittest.TestCase):
    def test_sed_cover_and_width(self):
        self.assertTrue(events.targeted(bash("sed -n '95,115p' app/M.kt"), SP))
        self.assertFalse(events.targeted(bash("sed -n '105,120p' app/M.kt"), SP))   # 부분 겹침
        self.assertFalse(events.targeted(bash("sed -n '1,1772p' app/M.kt"), SP))    # 폭 초과
        m = events.metrics([bash("sed -n '1,1772p' app/M.kt")], SP)
        self.assertEqual((m["first_targeted_turn"], m["chunk_read_turns"]), (None, {"app/M.kt": 1}))


class LimitEvidence(unittest.TestCase):
    def test_tool_results_ignored(self):
        evs = [{"type": "user", "message": {"content": [{"type": "tool_result", "tool_use_id": "t",
                                                          "content": "HTTP 429 quota exceeded"}]}}]
        self.assertNotIn("429", runner.limit_evidence(evs))
        c = runner.classify(evs, 137, True, [], "" + runner.limit_evidence(evs), events.tokens(evs), 3)
        self.assertEqual((c["valid"], c["censored"]), (True, True))

    def test_result_and_system_count(self):
        evs = [{"type": "system", "subtype": "api_retry", "error": "overloaded_error 529"}]
        c = runner.classify(evs, 137, True, [], runner.limit_evidence(evs), events.tokens([]), 2)
        self.assertEqual(c["invalid_reason"], "usage_limit")


class JudgePure(unittest.TestCase):
    def test_tampered_and_restore(self):
        with tempfile.TemporaryDirectory() as d:
            a, b = Path(d, "a"), Path(d, "b")
            for r in (a, b):
                (r / "checks" / "src").mkdir(parents=True)
                (r / "TASK.md").write_text("t")
                (r / "test.sh").write_text("s")
                (r / "checks" / "src" / "T.kt").write_text("k")
            (b / "test.sh").write_text("changed")
            (b / "checks" / "src" / "Extra.kt").write_text("e")
            (b / "checks" / "build").mkdir()
            (b / "checks" / "build" / "x").write_text("ignored")
            self.assertEqual(judge.tampered(a, b), ["checks/src/Extra.kt", "test.sh"])
            judge.restore(a, b)
            self.assertEqual(judge.tampered(a, b), [])

    def test_junit_parse(self):
        with tempfile.TemporaryDirectory() as d:
            Path(d, "TEST-x.xml").write_text(
                '<testsuite><testcase classname="pub.A" name="ok"/>'
                '<testcase classname="pub.A" name="bad"><failure message="m"/></testcase>'
                '<testcase classname="pub.A" name="skip"><skipped/></testcase></testsuite>')
            self.assertEqual(judge.junit_results([Path(d)]), {"tests": 3, "failed": ["pub.A.bad"], "skipped": 1})
            self.assertEqual(judge.junit_results([Path(d, "none")])["tests"], 0)

    def test_verify_parse_and_exclude(self):
        ok = judge.verify_result("NOTICE:  verify: 61 개 검사 모두 통과 (위반 0)", 0, set())
        self.assertTrue(ok["ok"])
        txt = "ERROR:  verify: 불변식 위반 7 건 — earn_amount_rate=3, grade_history_threshold=4"
        self.assertFalse(judge.verify_result(txt, 3, set())["ok"])
        r = judge.verify_result(txt, 3, {"earn_amount_rate", "grade_history_threshold"})
        self.assertEqual((r["ok"], r["excluded"]), (True, ["earn_amount_rate", "grade_history_threshold"]))
        self.assertIsNone(judge.verify_result("psql: connection refused", 2, set())["ok"])

    def test_conjunction(self):
        self.assertTrue(judge.conjunction({"a": "pass", "b": "pass"}, ("a", "b"), []))
        self.assertFalse(judge.conjunction({"a": "fail", "b": "infra"}, ("a", "b"), []))  # 확정 실패가 우선
        self.assertIsNone(judge.conjunction({"a": "pass", "b": "infra"}, ("a", "b"), []))
        self.assertFalse(judge.conjunction({"a": "pass"}, ("a",), ["test.sh"]))
        self.assertFalse(judge.conjunction({"a": "app_start_fail"}, ("a",), []))

    def test_stage_outcome(self):
        r = {"tests": 2, "failed": [], "skipped": 0}
        self.assertEqual(judge.stage_outcome("ran", 0, r), "pass")
        self.assertEqual(judge.stage_outcome("ran", 1, {"tests": 2, "failed": ["x"], "skipped": 0}), "fail")
        self.assertEqual(judge.stage_outcome("ran", 1, {"tests": 0, "failed": [], "skipped": 0}), "no_results")
        self.assertEqual(judge.stage_outcome("app_start_fail", None, r), "app_start_fail")
        self.assertEqual(judge.stage_outcome("missing", None, r), "infra")

    def test_read_list(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d, "x.txt")
            p.write_text("# 주석\ncom.a.B.m  # 사유\n\n  com.a.C.n\n")
            self.assertEqual(judge.read_list(p), ["com.a.B.m", "com.a.C.n"])


if __name__ == "__main__":
    unittest.main()
