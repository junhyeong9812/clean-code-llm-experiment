"""ADR-0009 위임 방식: explorer 정의 주입·프롬프트 접미·방식별 재개 키."""
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import runner  # noqa: E402


class Arms(unittest.TestCase):
    def test_arms_table(self):
        self.assertEqual(runner.ARMS, {"direct": None, "solo": None, "explore-haiku": "haiku",
                                       "explore-sonnet": "sonnet", "explore-opus": "opus"})
        self.assertEqual(runner.NO_AGENT_ARMS, {"solo"})

    def test_make_home_direct_has_no_agents(self):
        with tempfile.TemporaryDirectory() as d:
            with mock.patch.object(runner, "GRADLE_WARM", Path(d) / "none"):
                runner.make_home(Path(d) / "h", 60, with_creds=False)
            self.assertFalse((Path(d) / "h" / ".claude" / "agents").exists())

    def test_make_home_explorer_model(self):
        with tempfile.TemporaryDirectory() as d:
            with mock.patch.object(runner, "GRADLE_WARM", Path(d) / "none"):
                runner.make_home(Path(d) / "h", 60, with_creds=False, explorer_model="haiku")
            md = (Path(d) / "h" / ".claude" / "agents" / "explorer.md").read_text()
            self.assertTrue(md.startswith("---\nname: explorer\n"))
            self.assertIn("\nmodel: haiku\n", md)
            self.assertIn("\ntools: Bash, Read, Grep, Glob\n", md)

    def test_done_keys_filters_arm(self):
        with tempfile.TemporaryDirectory() as d:
            rows = [{"case": "A", "structure": "god", "model": "opus", "rep": 1, "valid": True},
                    {"case": "A", "structure": "god", "model": "opus", "rep": 1, "valid": True, "arm": "explore-haiku"}]
            (Path(d) / "runs.jsonl").write_text("".join(json.dumps(r) + "\n" for r in rows))
            self.assertEqual(runner.done_keys(Path(d)), {("A", "god", "opus", 1)})
            self.assertEqual(runner.done_keys(Path(d), "explore-haiku"), {("A", "god", "opus", 1)})
            self.assertEqual(runner.done_keys(Path(d), "explore-sonnet"), set())

    def test_held_keys_filters_arm(self):
        with tempfile.TemporaryDirectory() as d:
            h = Path(d) / "held" / "x"
            h.mkdir(parents=True)
            (h / "row.json").write_text(json.dumps({"case": "A", "structure": "god", "model": "opus", "rep": 1,
                                                     "arm": "explore-opus"}))
            self.assertEqual(runner.held_keys(Path(d)), set())
            self.assertEqual(runner.held_keys(Path(d), "explore-opus"), {("A", "god", "opus", 1)})

    def test_delegate_rule_names_agent(self):
        self.assertIn("explorer", runner.DELEGATE_RULE)
        self.assertIn("Agent 도구", runner.DELEGATE_RULE)

    def test_make_home_deny_agent(self):
        with tempfile.TemporaryDirectory() as d:
            with mock.patch.object(runner, "GRADLE_WARM", Path(d) / "none"):
                runner.make_home(Path(d) / "a", 60, with_creds=False)
                runner.make_home(Path(d) / "b", 60, with_creds=False, deny_agent=True)
            a = json.loads((Path(d) / "a" / ".claude" / "settings.json").read_text())["permissions"]["deny"]
            b = json.loads((Path(d) / "b" / ".claude" / "settings.json").read_text())["permissions"]["deny"]
            self.assertNotIn("Agent", a)
            self.assertEqual(set(b), set(a) | {"Agent", "Task"})

    def test_wait_mem(self):
        seq = iter([5.0, 13.0, 13.0])
        slept = []
        with mock.patch.object(runner, "mem_available_gb", lambda: next(seq)):
            m = runner.wait_mem(12, sleep=slept.append, log=lambda *a, **k: None)
        self.assertEqual((slept, m), ([60], 13.0))
        with mock.patch.object(runner, "mem_available_gb", lambda: 1.0), mock.patch.object(runner, "_stop") as st:
            st.is_set.return_value = True
            self.assertIsNone(runner.wait_mem(12, sleep=slept.append, log=lambda *a, **k: None))
        with mock.patch.object(runner, "mem_available_gb", lambda: 1.0):
            self.assertEqual(runner.wait_mem(0, sleep=slept.append), 1.0)  # 0 = 끔


class RunOneArms(unittest.TestCase):
    """run_one 의 방식별 프롬프트·HOME·run_id·행 기록·판정 단계 전달(외부 자원은 모두 가짜)."""

    def _run(self, arm, stages=None):
        got = {}
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)

            def fake_home(home, t, with_creds=True, explorer_model=None, deny_agent=False):
                got["home"] = (explorer_model, deny_agent)
                return None

            def fake_attach(name, out, err, t):
                out.write_text(json.dumps({"type": "system", "subtype": "init"}) + "\n"
                               + json.dumps({"type": "result", "modelUsage": {}}) + "\n")
                err.write_text("")
                return 0, False, 1.0

            def fake_judge(case, structure, work, jd, name, **kw):
                got["judge_kw"] = kw
                return {"judged": True, "success": True}

            with mock.patch.object(runner, "RUNS", d / "runs"), \
                    mock.patch.object(runner, "CACHE", d), \
                    mock.patch.object(runner, "disk_free_gb", lambda *a: 999), \
                    mock.patch.object(runner, "image_id", lambda i: "img"), \
                    mock.patch.object(runner, "build_work", lambda c, s, w: w.mkdir(parents=True)), \
                    mock.patch.object(runner, "make_home", fake_home), \
                    mock.patch.object(runner, "start_services", lambda n: None), \
                    mock.patch.object(runner, "create_agent", lambda n, w, h, argv: got.__setitem__("argv", argv)), \
                    mock.patch.object(runner, "attach_agent", fake_attach), \
                    mock.patch.object(runner, "proxy_hosts", lambda log: {"refused": []}), \
                    mock.patch.object(runner, "sh", lambda *a, **k: mock.Mock(stdout="true", stderr="")), \
                    mock.patch.object(runner, "write_diff", lambda *a: 0), \
                    mock.patch.object(runner, "remove_run", lambda n: []), \
                    mock.patch.object(runner.events, "init_info", lambda evs: {"cli_version": runner.CLI_VERSION}), \
                    mock.patch.object(runner.events, "harness_problems", lambda i: []), \
                    mock.patch.object(runner, "classify", lambda *a, **k: {"valid": True, "end_reason": "normal"}), \
                    mock.patch.object(runner.judging, "judge", fake_judge):
                row = runner.run_one("S1", "god", 1, "opus", d / "res", "5", 60, arm=arm, stages=stages)
        return row, got

    def test_direct_unchanged(self):
        row, got = self._run("direct")
        self.assertEqual(got["argv"][2], runner.PROMPTS["bug"])
        self.assertEqual(got["home"], (None, False))
        self.assertTrue(row["run_id"].startswith("S1-god-opus-r1-"))
        self.assertEqual(row["arm"], "direct")
        self.assertEqual(got["judge_kw"], {})

    def test_solo_denies_agent_no_rule(self):
        row, got = self._run("solo")
        self.assertEqual(got["argv"][2], runner.PROMPTS["bug"])
        self.assertEqual(got["home"], (None, True))
        self.assertTrue(row["run_id"].startswith("S1-god-opus-solo-r1-"))

    def test_explore_arm(self):
        row, got = self._run("explore-haiku", stages=("public", "hidden"))
        self.assertEqual(got["argv"][2], runner.PROMPTS["bug"] + runner.DELEGATE_RULE)
        self.assertEqual(got["home"], ("haiku", False))
        self.assertEqual(row["arm"], "explore-haiku")
        self.assertEqual(got["judge_kw"], {"stages_to_run": ("public", "hidden")})


class SubInfo(unittest.TestCase):
    def test_types_and_sub_writes(self):
        from events import Call
        def call(name, inp, sub):
            return mock.Mock(spec=Call, name=name, input=inp, sub=sub)
        cs = []
        for n, inp, sub in [("Agent", {"subagent_type": "explorer"}, False),
                            ("Bash", {"command": "sed -i 's/a/b/' app/x.kt"}, True),
                            ("Bash", {"command": "sed -i 's/a/b/' app/y.kt"}, False),
                            ("Task", {}, False)]:
            c = mock.Mock(); c.name, c.input, c.sub = n, inp, sub
            cs.append(c)
        import events
        r = events.sub_info(cs)
        self.assertEqual(r["subagent_types"], ["explorer", "?"])
        self.assertEqual(r["sub_edit_paths"], ["app/x.kt"])

    def test_cost_by_model(self):
        import events
        t = events.tokens([{"type": "result", "total_cost_usd": 0.3,
                            "modelUsage": {"o": {"inputTokens": 1, "costUSD": 0.2}, "h": {"outputTokens": 2, "costUSD": 0.1}}}])
        self.assertEqual(t["cost_by_model"], {"o": 0.2, "h": 0.1})
        self.assertEqual(t["total_tokens"], 3)


if __name__ == "__main__":
    unittest.main()
