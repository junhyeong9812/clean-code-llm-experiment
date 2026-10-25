"""Blind contract tests for runner.py — only the docker-free functions."""
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import runner  # noqa: E402


def mounts_from(allowed):
    return [{"Type": "bind", "Source": str(s), "Destination": d, "Mode": "", "RW": rw,
             "Propagation": "rprivate"} for s, d, rw in allowed]


def result_ev(subtype="success", is_error=False, result="ok", **kw):
    e = {"type": "result", "subtype": subtype, "is_error": is_error, "num_turns": 1,
         "total_cost_usd": 0.1, "result": result,
         "modelUsage": {"m": {"inputTokens": 1, "outputTokens": 1,
                              "cacheReadInputTokens": 0, "cacheCreationInputTokens": 0}}}
    e.update(kw)
    return e


ASST = {"type": "assistant", "message": {"id": "m1", "content": [{"type": "text", "text": "x"}],
                                         "usage": {"input_tokens": 1, "output_tokens": 1,
                                                   "cache_read_input_tokens": 0,
                                                   "cache_creation_input_tokens": 0}},
        "parent_tool_use_id": None}


class TestMounts(unittest.TestCase):
    def setUp(self):
        self.td = tempfile.TemporaryDirectory()
        d = Path(self.td.name)
        self.work, self.home = d / "work", d / "home"
        self.work.mkdir()
        self.home.mkdir()
        self.allowed = runner.allowed_mounts(self.work, self.home)

    def tearDown(self):
        self.td.cleanup()

    def test_allowed_mounts_shape(self):
        norm = {(str(s), d, rw) for s, d, rw in self.allowed}
        self.assertEqual(len(norm), 3)
        self.assertIn((str(self.work), "/work", True), norm)
        self.assertIn((str(self.home), "/home/agent", True), norm)
        self.assertIn((str(runner.GRADLE_RO), "/gradle-ro", False), norm)

    def test_exact_match_ok(self):
        self.assertEqual(runner.check_mounts(mounts_from(self.allowed), self.allowed), [])

    def test_order_independent(self):
        self.assertEqual(runner.check_mounts(list(reversed(mounts_from(self.allowed))), self.allowed), [])

    def test_one_extra_mount(self):
        ins = mounts_from(self.allowed) + [{"Type": "bind", "Source": "/home/jun", "Destination": "/host",
                                            "RW": False}]
        got = runner.check_mounts(ins, self.allowed)
        self.assertTrue(got)
        self.assertTrue(any(x.startswith("extra:") for x in got), got)
        self.assertFalse(any(x.startswith("missing:") for x in got), got)

    def test_missing_mount(self):
        ins = [m for m in mounts_from(self.allowed) if m["Destination"] != "/gradle-ro"]
        got = runner.check_mounts(ins, self.allowed)
        self.assertTrue(any(x.startswith("missing:") for x in got), got)
        self.assertFalse(any(x.startswith("extra:") for x in got), got)

    def test_rw_differs(self):
        ins = mounts_from(self.allowed)
        for m in ins:
            if m["Destination"] == "/gradle-ro":
                m["RW"] = True
        got = runner.check_mounts(ins, self.allowed)
        self.assertTrue(any(x.startswith("extra:") for x in got), got)
        self.assertTrue(any(x.startswith("missing:") for x in got), got)

    def test_same_dest_other_source(self):
        ins = mounts_from(self.allowed)
        for m in ins:
            if m["Destination"] == "/work":
                m["Source"] = "/home/jun/project/clean-code-llm-experiment"
        self.assertTrue(runner.check_mounts(ins, self.allowed))

    def test_volume_mount_counts_as_extra(self):
        ins = mounts_from(self.allowed) + [{"Type": "volume", "Name": "v1", "Source": "/var/lib/docker/volumes/v1/_data",
                                            "Destination": "/data", "RW": True}]
        self.assertTrue(any(x.startswith("extra:") for x in runner.check_mounts(ins, self.allowed)))

    def test_empty_inspect_is_all_missing(self):
        got = runner.check_mounts([], self.allowed)
        self.assertEqual(len([x for x in got if x.startswith("missing:")]), 3)


class TestResume(unittest.TestCase):
    def setUp(self):
        self.td = tempfile.TemporaryDirectory()
        self.d = Path(self.td.name)

    def tearDown(self):
        self.td.cleanup()

    def write_runs(self, rows, extra_lines=()):
        p = self.d / "runs.jsonl"
        with open(p, "w") as f:
            for r in rows:
                f.write(json.dumps(r) + "\n")
            for x in extra_lines:
                f.write(x + "\n")

    def test_done_keys_valid_only(self):
        self.write_runs([
            dict(case="T1", structure="god", model="sonnet", rep=0, valid=True),
            dict(case="T1", structure="layered", model="sonnet", rep=0, valid=False, invalid_reason="audit"),
            dict(case="T1", structure="layered", model="sonnet", rep=1, valid=False),
            dict(case="T1", structure="layered", model="sonnet", rep=1, valid=True),  # retried, now valid
            dict(case="C2", structure="god", model="haiku", rep=2, valid=True),
        ])
        self.assertEqual(runner.done_keys(self.d), {("T1", "god", "sonnet", 0),
                                                    ("T1", "layered", "sonnet", 1),
                                                    ("C2", "god", "haiku", 2)})

    def test_done_keys_missing_file_empty(self):
        self.assertEqual(runner.done_keys(self.d), set())

    def test_done_keys_ignores_blank_and_missing_valid(self):
        self.write_runs([dict(case="T1", structure="god", model="m", rep=0)], extra_lines=[""])
        self.assertEqual(runner.done_keys(self.d), set())

    def test_jobs_for_rotation_and_skip(self):
        cases, structs, reps = ["T1", "C2", "P1"], ["god", "layered"], [0, 1, 2]
        jobs = runner.jobs_for(cases, structs, "m", reps, set())
        self.assertEqual(len(jobs), 18)
        self.assertEqual(len(set(jobs)), 18)
        for i, c in enumerate(cases):
            for r in reps:
                k = (i + r) % len(structs)
                want = structs[k:] + structs[:k]
                got = [s for (cc, s, rr) in jobs if cc == c and rr == r]
                self.assertEqual(got, want, (c, r))

    def test_jobs_for_skips_done_same_model_only(self):
        done = {("T1", "god", "m", 0), ("T1", "layered", "OTHER", 0), ("C2", "layered", "m", 1)}
        jobs = runner.jobs_for(["T1", "C2"], ["god", "layered"], "m", [0, 1], done)
        self.assertNotIn(("T1", "god", 0), jobs)
        self.assertNotIn(("C2", "layered", 1), jobs)
        self.assertIn(("T1", "layered", 0), jobs)
        self.assertEqual(len(jobs), 6)

    def test_resume_roundtrip(self):
        self.write_runs([dict(case="T1", structure="god", model="m", rep=0, valid=True),
                         dict(case="T1", structure="layered", model="m", rep=0, valid=False)])
        jobs = runner.jobs_for(["T1"], ["god", "layered"], "m", [0], runner.done_keys(self.d))
        self.assertEqual(jobs, [("T1", "layered", 0)])


class TestClassify(unittest.TestCase):
    def c(self, evs, timed_out=False, violations=(), rc=0):
        return runner.classify(evs, rc, timed_out, list(violations))

    def test_normal(self):
        r = self.c([ASST, result_ev()])
        self.assertEqual(r["end_reason"], "normal")
        self.assertIs(r["valid"], True)
        self.assertIs(r["censored"], False)
        self.assertFalse(r["invalid_reason"])

    def test_timeout_valid_censored(self):
        r = self.c([ASST], timed_out=True, rc=137)
        self.assertEqual(r["end_reason"], "timeout")
        self.assertIs(r["valid"], True)
        self.assertIs(r["censored"], True)

    def test_budget_valid_censored(self):
        r = self.c([ASST, result_ev("error_max_budget_usd", True)], rc=1)
        self.assertEqual(r["end_reason"], "budget")
        self.assertIs(r["valid"], True)
        self.assertIs(r["censored"], True)

    def test_error_valid_not_censored(self):
        r = self.c([ASST, result_ev("error_during_execution", True, "boom")], rc=1)
        self.assertEqual(r["end_reason"], "error")
        self.assertIs(r["valid"], True)
        self.assertIs(r["censored"], False)

    def test_usage_limit_invalid(self):
        r = self.c([ASST, result_ev("error_during_execution", True, "Claude AI usage limit reached")], rc=1)
        self.assertIs(r["valid"], False)
        self.assertEqual(r["invalid_reason"], "usage_limit")

    def test_no_result_invalid(self):
        r = self.c([ASST], rc=1)
        self.assertIs(r["valid"], False)
        self.assertEqual(r["invalid_reason"], "no_result")

    def test_audit_invalid_even_if_otherwise_ok(self):
        for evs, to in [([ASST, result_ev()], False), ([ASST], True)]:
            r = self.c(evs, timed_out=to, violations=["bash:ssh"])
            self.assertIs(r["valid"], False)
            self.assertEqual(r["invalid_reason"], "audit")


class TestBuildWork(unittest.TestCase):
    def setUp(self):
        self.td = tempfile.TemporaryDirectory()
        self.d = Path(self.td.name)

    def tearDown(self):
        self.td.cleanup()

    def check(self, structure):
        work = self.d / f"work-{structure}"
        runner.build_work("_smoke", structure, work)
        for rel in ["app/src", "settings.gradle.kts", "build.gradle.kts", "gradlew",
                    "gradle/wrapper/gradle-wrapper.properties", "TASK.md", "test.sh", "notes/greeting.txt"]:
            self.assertTrue((work / rel).exists(), f"{structure}: missing {rel}")
        self.assertTrue(os.access(work / "gradlew", os.X_OK))
        props = (work / "gradle/wrapper/gradle-wrapper.properties").read_text()
        url = [l for l in props.splitlines() if l.startswith("distributionUrl")]
        self.assertEqual(len(url), 1)
        self.assertIn("file\\:/opt/gradle/", url[0])
        self.assertTrue(url[0].endswith(".zip"))
        for rel in ["TYPE", "docs", "behavior-tests", "tools", "README.md", ".git", "god", "layered",
                    "oracle", "experiment", "docker"]:
            self.assertFalse((work / rel).exists(), f"{structure}: unexpected {rel}")
        builds = [p for p in work.rglob("build") if p.is_dir()]
        self.assertEqual(builds, [], f"{structure}: build dirs present")
        other = "layered" if structure == "god" else "god"
        pkg_hits = [p for p in (work / "app/src").rglob("*") if f"/ccle/{other}" in str(p)]
        self.assertEqual(pkg_hits, [], f"{structure}: other structure's sources leaked")
        return work

    def test_god(self):
        self.check("god")

    def test_layered(self):
        self.check("layered")


if __name__ == "__main__":
    unittest.main()
