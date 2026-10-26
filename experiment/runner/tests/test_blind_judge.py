"""Blind contract tests for the judging stage (judge.py + runner.classify/limit_evidence + events sed rule).

Written from the contract only — implementation not read.
"""
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import judge  # noqa: E402
import runner  # noqa: E402
import events  # noqa: E402


def write(root: Path, rel: str, content: str = "x"):
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content)
    return p


class TreeCase(unittest.TestCase):
    def setUp(self):
        self.td = tempfile.TemporaryDirectory()
        d = Path(self.td.name)
        self.pristine, self.work = d / "pristine", d / "work"
        for root in (self.pristine, self.work):
            write(root, "TASK.md", "task")
            write(root, "test.sh", "#!/bin/sh\n")
            write(root, "checks/src/test/kotlin/PublicTest.kt", "public test")
            write(root, "checks/src/test/kotlin/sub/Other.kt", "other")
            write(root, "checks/build.gradle.kts", "plugins {}")
            write(root, "app/src/main/A.kt", "app")

    def tearDown(self):
        self.td.cleanup()


# ---------- tampered ----------
class TestTampered(TreeCase):
    def test_identical_is_empty(self):
        self.assertEqual(judge.tampered(self.pristine, self.work), [])

    def test_task_md_modified(self):
        write(self.work, "TASK.md", "changed")
        self.assertEqual(judge.tampered(self.pristine, self.work), ["TASK.md"])

    def test_test_sh_deleted(self):
        (self.work / "test.sh").unlink()
        self.assertEqual(judge.tampered(self.pristine, self.work), ["test.sh"])

    def test_checks_file_deleted(self):
        (self.work / "checks/src/test/kotlin/sub/Other.kt").unlink()
        self.assertEqual(judge.tampered(self.pristine, self.work), ["checks/src/test/kotlin/sub/Other.kt"])

    def test_checks_file_added(self):
        write(self.work, "checks/src/test/kotlin/Extra.kt", "new")
        self.assertEqual(judge.tampered(self.pristine, self.work), ["checks/src/test/kotlin/Extra.kt"])

    def test_checks_file_only_in_pristine_added_there(self):
        write(self.pristine, "checks/Only.kt", "p")
        self.assertEqual(judge.tampered(self.pristine, self.work), ["checks/Only.kt"])

    def test_checks_build_ignored(self):
        write(self.work, "checks/build/classes/X.class", "bin")
        write(self.work, "checks/build/test-results/test/TEST-x.xml", "<x/>")
        write(self.pristine, "checks/build/old.txt", "old")
        self.assertEqual(judge.tampered(self.pristine, self.work), [])

    def test_nested_build_under_checks_ignored(self):
        write(self.work, "checks/sub/build/out.txt", "o")
        self.assertEqual(judge.tampered(self.pristine, self.work), [])

    def test_build_gradle_file_not_ignored(self):
        # `build.gradle.kts` is a file, not a `build` directory — still protected
        write(self.work, "checks/build.gradle.kts", "changed")
        self.assertEqual(judge.tampered(self.pristine, self.work), ["checks/build.gradle.kts"])

    def test_app_changes_ignored(self):
        write(self.work, "app/src/main/A.kt", "fixed")
        write(self.work, "app/src/main/New.kt", "new")
        write(self.work, "notes.md", "n")
        self.assertEqual(judge.tampered(self.pristine, self.work), [])

    def test_non_root_task_md_ignored(self):
        write(self.work, "app/TASK.md", "nested")
        self.assertEqual(judge.tampered(self.pristine, self.work), [])

    def test_sorted_and_posix_multiple(self):
        write(self.work, "test.sh", "changed")
        write(self.work, "checks/src/test/kotlin/sub/Other.kt", "changed")
        write(self.work, "TASK.md", "changed")
        write(self.work, "checks/A.kt", "added")
        got = judge.tampered(self.pristine, self.work)
        want = sorted(["test.sh", "checks/src/test/kotlin/sub/Other.kt", "TASK.md", "checks/A.kt"])
        self.assertEqual(got, want)
        for p in got:
            self.assertNotIn("\\", p)
            self.assertFalse(p.startswith("/"))

    def test_same_size_content_change_detected(self):
        write(self.work, "checks/src/test/kotlin/PublicTest.kt", "public tesT")
        self.assertEqual(judge.tampered(self.pristine, self.work), ["checks/src/test/kotlin/PublicTest.kt"])

    def test_whole_checks_deleted(self):
        import shutil
        shutil.rmtree(self.work / "checks")
        got = judge.tampered(self.pristine, self.work)
        self.assertEqual(got, sorted(["checks/build.gradle.kts", "checks/src/test/kotlin/PublicTest.kt",
                                      "checks/src/test/kotlin/sub/Other.kt"]))


# ---------- restore ----------
class TestRestore(TreeCase):
    def test_restore_fixes_all_tampering(self):
        write(self.work, "TASK.md", "changed")
        (self.work / "test.sh").unlink()
        write(self.work, "checks/Added.kt", "a")
        (self.work / "checks/src/test/kotlin/sub/Other.kt").unlink()
        write(self.work, "checks/src/test/kotlin/PublicTest.kt", "weakened")
        self.assertNotEqual(judge.tampered(self.pristine, self.work), [])
        judge.restore(self.pristine, self.work)
        self.assertEqual(judge.tampered(self.pristine, self.work), [])
        self.assertFalse((self.work / "checks/Added.kt").exists())
        self.assertEqual((self.work / "TASK.md").read_text(), "task")

    def test_restore_keeps_app(self):
        write(self.work, "app/src/main/A.kt", "fixed")
        judge.restore(self.pristine, self.work)
        self.assertEqual((self.work / "app/src/main/A.kt").read_text(), "fixed")

    def test_restore_does_not_copy_pristine_build_or_gradle(self):
        write(self.pristine, "checks/build/junk.txt", "j")
        write(self.pristine, "checks/.gradle/cache.bin", "c")
        judge.restore(self.pristine, self.work)
        self.assertFalse((self.work / "checks/build/junk.txt").exists())
        self.assertFalse((self.work / "checks/.gradle/cache.bin").exists())
        self.assertEqual(judge.tampered(self.pristine, self.work), [])

    def test_restore_when_checks_missing_in_work(self):
        import shutil
        shutil.rmtree(self.work / "checks")
        judge.restore(self.pristine, self.work)
        self.assertEqual(judge.tampered(self.pristine, self.work), [])


# ---------- junit_results ----------
def junit(cases_xml: str, name="S"):
    return f'<?xml version="1.0"?>\n<testsuite name="{name}">\n{cases_xml}\n</testsuite>\n'


class TestJunit(unittest.TestCase):
    def setUp(self):
        self.td = tempfile.TemporaryDirectory()
        self.d = Path(self.td.name)

    def tearDown(self):
        self.td.cleanup()

    def test_pass_failure_error_skipped(self):
        write(self.d / "r", "TEST-a.xml", junit(
            '<testcase classname="pkg.A" name="ok"/>'
            '<testcase classname="pkg.A" name="bad"><failure message="m">trace</failure></testcase>'
            '<testcase classname="pkg.A" name="boom"><error message="e"/></testcase>'
            '<testcase classname="pkg.A" name="skip"><skipped/></testcase>'))
        r = judge.junit_results([self.d / "r"])
        self.assertEqual(r["tests"], 4)
        self.assertEqual(sorted(r["failed"]), ["pkg.A.bad", "pkg.A.boom"])
        self.assertEqual(r["skipped"], 1)

    def test_skipped_not_failed(self):
        write(self.d / "r", "TEST-a.xml", junit('<testcase classname="C" name="s"><skipped message="x"/></testcase>'))
        r = judge.junit_results([self.d / "r"])
        self.assertEqual((r["tests"], r["failed"], r["skipped"]), (1, [], 1))

    def test_missing_dir_ignored(self):
        write(self.d / "r", "TEST-a.xml", junit('<testcase classname="C" name="a"/>'))
        r = judge.junit_results([self.d / "nope", self.d / "r"])
        self.assertEqual((r["tests"], r["failed"], r["skipped"]), (1, [], 0))

    def test_all_missing(self):
        r = judge.junit_results([self.d / "nope"])
        self.assertEqual((r["tests"], r["failed"], r["skipped"]), (0, [], 0))

    def test_empty_list(self):
        r = judge.junit_results([])
        self.assertEqual((r["tests"], r["failed"], r["skipped"]), (0, [], 0))

    def test_multiple_dirs_and_files_summed(self):
        write(self.d / "r1", "TEST-a.xml", junit('<testcase classname="A" name="a1"/><testcase classname="A" name="a2"/>'))
        write(self.d / "r1", "TEST-b.xml", junit('<testcase classname="B" name="b1"><failure/></testcase>'))
        write(self.d / "r2", "TEST-c.xml", junit('<testcase classname="C" name="c1"><error/></testcase>'))
        r = judge.junit_results([self.d / "r1", self.d / "r2"])
        self.assertEqual(r["tests"], 4)
        self.assertEqual(sorted(r["failed"]), ["B.b1", "C.c1"])

    def test_non_xml_files_ignored(self):
        write(self.d / "r", "TEST-a.xml", junit('<testcase classname="A" name="a"/>'))
        write(self.d / "r", "output.txt", "<testcase classname='X' name='y'><failure/></testcase>")
        write(self.d / "r", "binary/ignored.bin", "zz")
        r = judge.junit_results([self.d / "r"])
        self.assertEqual((r["tests"], r["failed"]), (1, []))

    def test_broken_xml_one_failed_entry(self):
        write(self.d / "r", "TEST-good.xml", junit('<testcase classname="A" name="a"/>'))
        write(self.d / "r", "TEST-bad.xml", '<testsuite><testcase classname="A" name="x">')
        r = judge.junit_results([self.d / "r"])
        broken = [f for f in r["failed"] if f.startswith("<깨진 결과 파일")]
        self.assertEqual(len(broken), 1, r["failed"])
        self.assertTrue(broken[0].endswith(">"), broken[0])
        self.assertGreaterEqual(r["tests"], 1)  # good file still parsed
        self.assertNotIn("A.a", r["failed"])

    def test_empty_xml_file_is_broken(self):
        write(self.d / "r", "TEST-empty.xml", "")
        r = judge.junit_results([self.d / "r"])
        self.assertEqual(len([f for f in r["failed"] if f.startswith("<깨진 결과 파일")]), 1)

    def test_testsuites_wrapper(self):
        write(self.d / "r", "all.xml",
              '<testsuites><testsuite name="s1"><testcase classname="A" name="a"/></testsuite>'
              '<testsuite name="s2"><testcase classname="B" name="b"><failure/></testcase></testsuite></testsuites>')
        r = judge.junit_results([self.d / "r"])
        self.assertEqual((r["tests"], r["failed"]), (2, ["B.b"]))

    def test_suite_without_testcases(self):
        write(self.d / "r", "TEST-a.xml", junit(""))
        r = judge.junit_results([self.d / "r"])
        self.assertEqual((r["tests"], r["failed"], r["skipped"]), (0, [], 0))

    def test_system_out_child_not_failure(self):
        write(self.d / "r", "TEST-a.xml", junit(
            '<testcase classname="A" name="a"><system-out>failure error</system-out></testcase>'))
        r = judge.junit_results([self.d / "r"])
        self.assertEqual((r["tests"], r["failed"]), (1, []))

    def test_failure_and_error_together_listed_once(self):
        write(self.d / "r", "TEST-a.xml", junit(
            '<testcase classname="A" name="a"><failure/><error/></testcase>'))
        r = judge.junit_results([self.d / "r"])
        self.assertEqual((r["tests"], r["failed"]), (1, ["A.a"]))


# ---------- verify_result ----------
class TestVerify(unittest.TestCase):
    def test_all_pass(self):
        r = judge.verify_result("...\nverify: 12 개 검사 모두 통과\n", 0, set())
        self.assertEqual(r, {"ok": True, "violations": {}, "excluded": []})

    def test_all_pass_with_nonzero_rc_not_ok_true(self):
        r = judge.verify_result("verify: 12 개 검사 모두 통과\n", 1, set())
        self.assertIsNot(r["ok"], True)

    def test_violations_parsed(self):
        r = judge.verify_result("verify: 불변식 위반 2 건 — orphan_items=3, neg_stock=1\n", 1, set())
        self.assertIs(r["ok"], False)
        self.assertEqual(r["violations"], {"orphan_items": 3, "neg_stock": 1})
        self.assertEqual(r["excluded"], [])

    def test_violations_values_are_int(self):
        r = judge.verify_result("verify: 불변식 위반 1 건 — a=7\n", 1, set())
        self.assertIsInstance(r["violations"]["a"], int)

    def test_all_excluded_ok(self):
        r = judge.verify_result("verify: 불변식 위반 2 건 — b=1, a=2\n", 1, {"a", "b"})
        self.assertIs(r["ok"], True)
        self.assertEqual(r["violations"], {})
        self.assertEqual(r["excluded"], ["a", "b"])

    def test_partial_exclude(self):
        r = judge.verify_result("verify: 불변식 위반 3 건 — z=1, a=2, m=5\n", 1, {"z", "a", "unused"})
        self.assertIs(r["ok"], False)
        self.assertEqual(r["violations"], {"m": 5})
        self.assertEqual(r["excluded"], ["a", "z"])  # sorted, only actual violations

    def test_exclude_not_in_violations_not_reported(self):
        r = judge.verify_result("verify: 불변식 위반 1 건 — a=1\n", 1, {"x"})
        self.assertEqual(r["excluded"], [])
        self.assertEqual(r["violations"], {"a": 1})

    def test_violation_line_embedded_in_noise(self):
        text = "psql: notice\nfoo\nverify: 불변식 위반 1 건 — dup_order=4\ntrailer\n"
        r = judge.verify_result(text, 1, set())
        self.assertEqual(r["violations"], {"dup_order": 4})

    def test_violation_rc_zero_still_parsed(self):
        r = judge.verify_result("verify: 불변식 위반 1 건 — a=1\n", 0, set())
        self.assertIs(r["ok"], False)
        self.assertEqual(r["violations"], {"a": 1})

    def test_no_space_after_comma(self):
        r = judge.verify_result("verify: 불변식 위반 2 건 — a=1,b=2\n", 1, set())
        self.assertEqual(r["violations"], {"a": 1, "b": 2})

    def test_unparseable_is_none(self):
        for text, rc in [("", 0), ("", 1), ("ERROR: relation does not exist", 1),
                         ("verify: something else", 0)]:
            with self.subTest(text=text, rc=rc):
                self.assertIsNone(judge.verify_result(text, rc, set())["ok"])

    def test_pass_line_without_number_is_none(self):
        self.assertIsNone(judge.verify_result("verify: 개 검사 모두 통과", 0, set())["ok"])


# ---------- read_list ----------
class TestReadList(unittest.TestCase):
    def setUp(self):
        self.td = tempfile.TemporaryDirectory()
        self.d = Path(self.td.name)

    def tearDown(self):
        self.td.cleanup()

    def test_missing_file(self):
        self.assertEqual(judge.read_list(self.d / "nope.txt"), [])

    def test_comments_blanks_whitespace(self):
        p = write(self.d, "l.txt", "# header\n  a  \n\nb # trailing\n   # only comment\n\t c\t\n")
        self.assertEqual(judge.read_list(p), ["a", "b", "c"])

    def test_empty_file(self):
        p = write(self.d, "l.txt", "")
        self.assertEqual(judge.read_list(p), [])


# ---------- stage_outcome ----------
class TestStageOutcome(unittest.TestCase):
    R_OK = {"tests": 3, "failed": [], "skipped": 0}
    R_FAIL = {"tests": 3, "failed": ["A.a"], "skipped": 0}
    R_NONE = {"tests": 0, "failed": [], "skipped": 0}

    def test_app_start_fail(self):
        self.assertEqual(judge.stage_outcome("app_start_fail", 1, self.R_OK), "app_start_fail")

    def test_non_ran_is_infra(self):
        for st in ("infra", "skipped", "timeout", ""):
            with self.subTest(st=st):
                self.assertEqual(judge.stage_outcome(st, 0, self.R_OK), "infra")

    def test_no_results(self):
        self.assertEqual(judge.stage_outcome("ran", 0, self.R_NONE), "no_results")
        self.assertEqual(judge.stage_outcome("ran", 1, self.R_NONE), "no_results")

    def test_pass(self):
        self.assertEqual(judge.stage_outcome("ran", 0, self.R_OK), "pass")

    def test_rc_nonzero_without_failed_is_infra(self):  # 계약 갱신(리뷰 루프 1): Gradle 자체 오류는 판정 불가
        self.assertEqual(judge.stage_outcome("ran", 1, self.R_OK), "infra")

    def test_fail_by_failed(self):
        self.assertEqual(judge.stage_outcome("ran", 0, self.R_FAIL), "fail")

    def test_rc_none_with_results_is_infra(self):  # 계약 갱신(리뷰 루프 1)
        self.assertEqual(judge.stage_outcome("ran", None, self.R_OK), "infra")

    def test_skipped_only_counts_as_results(self):
        self.assertEqual(judge.stage_outcome("ran", 0, {"tests": 2, "failed": [], "skipped": 2}), "pass")


# ---------- conjunction ----------
class TestConjunction(unittest.TestCase):
    K = ("public", "hidden")

    def test_stages_constant(self):
        self.assertEqual(judge.STAGES, ("public", "hidden", "contract", "contract2"))

    def test_all_pass(self):
        self.assertIs(judge.conjunction({"public": "pass", "hidden": "pass"}, self.K, []), True)

    def test_all_stages_pass(self):
        oc = {s: "pass" for s in judge.STAGES}
        self.assertIs(judge.conjunction(oc, judge.STAGES, []), True)

    def test_tampered_false_even_if_pass(self):
        self.assertIs(judge.conjunction({"public": "pass", "hidden": "pass"}, self.K, ["TASK.md"]), False)

    def test_tampered_beats_infra(self):
        self.assertIs(judge.conjunction({"public": "infra", "hidden": "infra"}, self.K, ["test.sh"]), False)

    def test_fail(self):
        self.assertIs(judge.conjunction({"public": "pass", "hidden": "fail"}, self.K, []), False)

    def test_app_start_fail(self):
        self.assertIs(judge.conjunction({"public": "app_start_fail", "hidden": "pass"}, self.K, []), False)

    def test_fail_beats_infra(self):
        self.assertIs(judge.conjunction({"public": "infra", "hidden": "fail"}, self.K, []), False)
        self.assertIs(judge.conjunction({"public": "fail", "hidden": "infra"}, self.K, []), False)

    def test_fail_beats_no_results_and_missing(self):
        self.assertIs(judge.conjunction({"hidden": "fail"}, self.K, []), False)
        self.assertIs(judge.conjunction({"public": "no_results", "hidden": "app_start_fail"}, self.K, []), False)

    def test_infra_none(self):
        self.assertIsNone(judge.conjunction({"public": "pass", "hidden": "infra"}, self.K, []))

    def test_no_results_none(self):
        self.assertIsNone(judge.conjunction({"public": "pass", "hidden": "no_results"}, self.K, []))

    def test_missing_key_is_infra_none(self):
        self.assertIsNone(judge.conjunction({"public": "pass"}, self.K, []))
        self.assertIsNone(judge.conjunction({}, self.K, []))

    def test_extra_keys_ignored(self):
        oc = {"public": "pass", "hidden": "pass", "contract": "fail"}
        self.assertIs(judge.conjunction(oc, self.K, []), True)

    def test_unknown_outcome_none(self):
        self.assertIsNone(judge.conjunction({"public": "pass", "hidden": "weird"}, self.K, []))

    def test_empty_keys(self):
        self.assertIs(judge.conjunction({}, (), []), True)
        self.assertIs(judge.conjunction({}, (), ["x"]), False)

    def test_list_keys(self):
        self.assertIs(judge.conjunction({"public": "pass", "hidden": "pass"}, ["public", "hidden"], []), True)


# ---------- runner.limit_evidence / classify ----------
def result_ev(subtype="success", is_error=False, result="ok", **kw):
    e = {"type": "result", "subtype": subtype, "is_error": is_error, "num_turns": 1,
         "total_cost_usd": 0.1, "result": result,
         "modelUsage": {"m": {"inputTokens": 1, "outputTokens": 1,
                              "cacheReadInputTokens": 0, "cacheCreationInputTokens": 0}}}
    e.update(kw)
    return e


def asst(mid="m1", txt="x"):
    return {"type": "assistant",
            "message": {"id": mid, "content": [{"type": "text", "text": txt}],
                        "usage": {"input_tokens": 1, "output_tokens": 1,
                                  "cache_read_input_tokens": 0, "cache_creation_input_tokens": 0}},
            "parent_tool_use_id": None}


def asst_tool(mid="m1", tid="t1", cmd="grep -rn 429 app"):
    return {"type": "assistant",
            "message": {"id": mid, "content": [{"type": "tool_use", "id": tid, "name": "Bash",
                                                "input": {"command": cmd}}],
                        "usage": {"input_tokens": 1, "output_tokens": 1,
                                  "cache_read_input_tokens": 0, "cache_creation_input_tokens": 0}},
            "parent_tool_use_id": None}


def tool_result(tid="t1", content="HTTP 429 Too Many Requests; rate limit; quota exceeded; overloaded"):
    return {"type": "user",
            "message": {"content": [{"type": "tool_result", "tool_use_id": tid, "content": content,
                                     "is_error": False}]},
            "parent_tool_use_id": None}


INIT = {"type": "system", "subtype": "init", "model": "claude-429", "tools": ["Bash"],
        "claude_code_version": "2.1.308", "note": "rate limit quota overloaded 529"}
THINK = {"type": "system", "subtype": "thinking_tokens", "text": "usage limit 429"}


class TestLimitEvidence(unittest.TestCase):
    def test_result_text_included(self):
        s = runner.limit_evidence([asst(), result_ev("error_during_execution", True, "Claude AI usage limit reached")])
        self.assertIn("usage limit reached", s)

    def test_subtype_and_api_error_status_included(self):
        s = runner.limit_evidence([result_ev("error_xyz_subtype", True, "boom", api_error_status=529)])
        self.assertIn("error_xyz_subtype", s)
        self.assertIn("529", s)

    def test_other_system_event_included(self):
        ev = {"type": "system", "subtype": "api_retry", "error": "overloaded_error", "error_status": 529}
        s = runner.limit_evidence([ev])
        self.assertIn("overloaded", s.lower())

    def test_tool_result_and_assistant_excluded(self):
        evs = [INIT, THINK, asst(txt="the server returned 429, quota, rate limit, overloaded"),
               asst_tool(), tool_result(), result_ev()]
        s = runner.limit_evidence(evs).lower()
        for w in ("429", "529", "quota", "rate limit", "overloaded", "usage limit", "too many"):
            self.assertNotIn(w, s, s)

    def test_empty(self):
        self.assertIsInstance(runner.limit_evidence([]), str)
        self.assertNotIn("429", runner.limit_evidence([]))


class TestClassifyLimit(unittest.TestCase):
    def test_no_result_with_limit_in_stderr(self):
        for phrase in ("Usage Limit", "RATE LIMIT exceeded", "limit reached", "You've hit your limit",
                       "your limit will reset at 5pm", "quota", "Overloaded", "status 429", "error 529"):
            with self.subTest(phrase=phrase):
                r = runner.classify([asst()], 1, False, [], stderr=f"tail...\n{phrase}\n")
                self.assertIs(r["valid"], False)
                self.assertEqual(r["invalid_reason"], "usage_limit")

    def test_no_result_without_limit_is_no_result(self):
        r = runner.classify([asst()], 1, False, [], stderr="segfault")
        self.assertEqual(r["invalid_reason"], "no_result")

    def test_timeout_with_limit_is_usage_limit(self):
        r = runner.classify([asst()], 137, True, [], stderr="429 Too Many Requests", n_assistant=1)
        self.assertIs(r["valid"], False)
        self.assertEqual(r["invalid_reason"], "usage_limit")

    def test_timeout_no_assistant_no_activity(self):
        r = runner.classify([], 137, True, [], stderr="", n_assistant=0)
        self.assertIs(r["valid"], False)
        self.assertEqual(r["invalid_reason"], "no_activity")

    def test_timeout_with_assistant_valid_censored(self):
        r = runner.classify([asst()], 137, True, [], stderr="", n_assistant=1)
        self.assertEqual(r["end_reason"], "timeout")
        self.assertIs(r["valid"], True)
        self.assertIs(r["censored"], True)
        self.assertFalse(r["invalid_reason"])

    def test_tool_result_429_does_not_invalidate_timeout(self):
        evs = [INIT, asst_tool(), tool_result(), asst("m2", "the app returns 429 on overload")]
        r = runner.classify(evs, 137, True, [], stderr="" + runner.limit_evidence(evs), n_assistant=2)
        self.assertIs(r["valid"], True, r)
        self.assertIs(r["censored"], True)

    def test_tool_result_429_does_not_make_no_result_usage_limit(self):
        evs = [asst_tool(), tool_result()]
        r = runner.classify(evs, 1, False, [], stderr=runner.limit_evidence(evs))
        self.assertEqual(r["invalid_reason"], "no_result")

    def test_result_event_limit_via_limit_evidence_on_timeout(self):
        # a system retry event saying overloaded, then timeout
        evs = [asst(), {"type": "system", "subtype": "api_retry", "error": "overloaded_error"}]
        r = runner.classify(evs, 137, True, [], stderr=runner.limit_evidence(evs), n_assistant=1)
        self.assertEqual(r["invalid_reason"], "usage_limit")

    def test_normal_result_ignores_limit_words_in_stderr(self):
        # end_reason normal → the stderr rule (no_result/timeout only) does not apply
        r = runner.classify([asst(), result_ev()], 0, False, [], stderr="rate limit")
        self.assertEqual(r["end_reason"], "normal")
        self.assertIs(r["valid"], True)

    def test_audit_still_wins_on_timeout(self):
        r = runner.classify([asst()], 137, True, ["bash:ssh"], stderr="", n_assistant=1)
        self.assertIs(r["valid"], False)
        self.assertEqual(r["invalid_reason"], "audit")


# ---------- events: sed -n 'A,Bp' ----------
SPANS_TXT = "app/A.kt:100-120 fix\napp/B.kt:50-60 read\n"


def sed_call(cmd, turn=1, is_error=False, result="x"):
    return events.Call(turn, "Bash", {"command": cmd}, f"t{turn}", False, result, is_error)


class TestSedTargeted(unittest.TestCase):
    def setUp(self):
        self.spans = events.parse_symbols(SPANS_TXT)

    def hit(self, cmd):
        return [(sp.start) for sp in events.targeted(sed_call(cmd), self.spans)]

    def test_constant(self):
        self.assertEqual(events.READ_TARGET_MAX, 400)

    def test_exact_cover(self):
        self.assertEqual(self.hit("sed -n '100,120p' app/A.kt"), [100])

    def test_wider_cover(self):
        self.assertEqual(self.hit("sed -n '90,130p' /work/app/A.kt"), [100])

    def test_width_exactly_400_targeted(self):
        self.assertEqual(self.hit("sed -n '1,400p' app/A.kt"), [100])  # 400-1+1 = 400

    def test_width_401_not_targeted(self):
        self.assertEqual(self.hit("sed -n '1,401p' app/A.kt"), [])

    def test_width_400_ending_at_span_end(self):
        self.assertEqual(self.hit("sed -n '120,519p' app/A.kt"), [])  # start 120 > 100
        self.assertEqual(self.hit("sed -n '100,499p' app/A.kt"), [100])

    def test_partial_overlap_start(self):
        self.assertEqual(self.hit("sed -n '110,200p' app/A.kt"), [])

    def test_partial_overlap_end(self):
        self.assertEqual(self.hit("sed -n '50,119p' app/A.kt"), [])

    def test_inside_span_only(self):
        self.assertEqual(self.hit("sed -n '105,110p' app/A.kt"), [])

    def test_disjoint(self):
        self.assertEqual(self.hit("sed -n '1,50p' app/A.kt"), [])

    def test_other_file(self):
        self.assertEqual(self.hit("sed -n '100,120p' app/Z.kt"), [])

    def test_dot_slash_path(self):
        self.assertEqual(self.hit("sed -n '100,120p' ./app/A.kt"), [100])

    def test_double_quotes(self):
        self.assertEqual(self.hit('sed -n "100,120p" app/A.kt'), [100])

    def test_boundary_single_line_off(self):
        self.assertEqual(self.hit("sed -n '101,120p' app/A.kt"), [])
        self.assertEqual(self.hit("sed -n '100,119p' app/A.kt"), [])


class TestSedChunk(unittest.TestCase):
    def setUp(self):
        self.spans = events.parse_symbols(SPANS_TXT)

    def m(self, calls):
        return events.metrics(calls, self.spans)

    def test_wide_sed_recorded_as_chunk_first_turn(self):
        cs = [sed_call("sed -n '1,500p' /work/app/A.kt", turn=2),
              sed_call("sed -n '501,1000p' ./app/A.kt", turn=3)]
        m = self.m(cs)
        self.assertEqual(m["chunk_read_turns"], {"app/A.kt": 2})
        self.assertIsNone(m["first_targeted_turn"])

    def test_width_400_not_chunk(self):
        m = self.m([sed_call("sed -n '1,400p' app/A.kt", turn=1)])
        self.assertEqual(m["chunk_read_turns"], {})
        self.assertEqual(m["first_targeted_turn"], 1)

    def test_width_401_is_chunk(self):
        m = self.m([sed_call("sed -n '1,401p' app/A.kt", turn=1)])
        self.assertEqual(m["chunk_read_turns"], {"app/A.kt": 1})
        self.assertIsNone(m["first_targeted_turn"])

    def test_error_call_not_chunk(self):
        m = self.m([sed_call("sed -n '1,900p' app/A.kt", turn=1, is_error=True, result="No such file")])
        self.assertEqual(m["chunk_read_turns"], {})

    def test_chunk_then_targeted(self):
        cs = [sed_call("sed -n '1,900p' /work/app/A.kt", turn=1),
              sed_call("sed -n '45,65p' app/B.kt", turn=2)]
        m = self.m(cs)
        self.assertEqual(m["chunk_read_turns"], {"app/A.kt": 1})
        self.assertEqual(m["first_targeted_turn"], 2)
        self.assertEqual(m["first_targeted_turn_by_span"].get("app/B.kt:50-60"), 2)

    def test_overlap_only_not_targeted_in_metrics(self):
        m = self.m([sed_call("sed -n '110,130p' app/A.kt", turn=1)])
        self.assertIsNone(m["first_targeted_turn"])
        self.assertEqual(m["chunk_read_turns"], {})

    def test_two_files_chunked(self):
        cs = [sed_call("sed -n '1,1000p' app/A.kt", turn=1), sed_call("sed -n '1,1000p' /work/app/B.kt", turn=4)]
        self.assertEqual(self.m(cs)["chunk_read_turns"], {"app/A.kt": 1, "app/B.kt": 4})


if __name__ == "__main__":
    unittest.main()
