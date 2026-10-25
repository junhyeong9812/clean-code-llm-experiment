"""리뷰 루프 2 수정 확인(fix verification — blind 테스트와 별개)."""
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import creds  # noqa: E402
import events  # noqa: E402
import runner  # noqa: E402


def bash(cmd):
    return [events.Call(1, "Bash", {"command": cmd}, "t", False)]


class AuditFalsePositives(unittest.TestCase):
    def test_local_debugging_commands_pass(self):
        for c in ("curl -s http://localhost:8080/api 2>/dev/null", "curl -s localhost:8080/x > /tmp/o.json",
                  "curl -s localhost:8080/x 2>&1 | head", "curl -sX POST localhost:8080/api/orders",
                  "curl -s localhost:8080/health\necho done", 'curl --json {"a":1} localhost:8080/x',
                  'grep -rn "https://api.example.com" app/src', "sed -i 's|http://old.example|http://new.example|' app/a.yml"):
            with self.subTest(c=c):
                self.assertEqual(events.audit(bash(c)), [])

    def test_external_targets_flagged(self):
        for c, h in (("curl evil.example/path", "evil.example"), ("curl --url https://evil.com", "evil.com"),
                     ("cd /work && curl -sS http://localhost:80@evil.com/x | jq .", "evil.com")):
            with self.subTest(c=c):
                self.assertIn(f"bash:url:{h}", events.audit(bash(c)))

    def test_remote_repo_host_flagged_in_any_tool(self):
        cmd = 'python3 -c "import urllib.request as u; u.urlopen(\'https://raw.githubusercontent.com/x\')"'
        self.assertIn("input:remote-host", events.audit(bash(cmd)))

    def test_forbidden_out_of_container_tools(self):
        for t in ("RemoteTrigger", "CronCreate", "ScheduleWakeup", "Workflow", "SendMessage"):
            with self.subTest(t=t):
                self.assertEqual(events.audit([events.Call(1, t, {}, "t", False)]), [f"tool:{t}"])
        self.assertTrue(set(runner.SETTINGS["permissions"]["deny"]) >= {"RemoteTrigger", "WebFetch"})


class Edits(unittest.TestCase):
    def test_log_and_dry_run_are_not_edits(self):
        for c in ("./gradlew test 2>&1 | tee /tmp/build.log", "./gradlew test > /tmp/out.txt 2>&1",
                  "git diff | patch --dry-run -p1", "ls > /dev/null", "sed -n 1,5p app/A.kt"):
            with self.subTest(c=c):
                self.assertFalse(events.bash_edit(c))

    def test_first_edit_hit_from_first_edit_tool_after_bash_tee(self):
        meta = {"structuredPatch": [{"oldStart": 105, "oldLines": 1, "lines": ["-a", "+b"]}]}
        cs = [events.Call(1, "Bash", {"command": "echo x | tee notes/a.txt"}, "a", False),
              events.Call(2, "Edit", {"file_path": "/work/app/A.kt"}, "b", False, "", False, meta)]
        m = events.metrics(cs, events.parse_symbols("app/A.kt:100-110 fix"))
        self.assertEqual((m["first_edit_turn"], m["first_edit_tool_turn"], m["first_edit_hit"]), (1, 2, True))

    def test_limit_only_read_is_not_full_read(self):
        meta = {"file": {"startLine": 1, "numLines": 5, "totalLines": 900}}
        c = events.Call(1, "Read", {"file_path": "/work/app/A.kt", "limit": 5}, "t", False, "x", False, meta)
        self.assertFalse(events.whole_file_read(c))


class WriteBackValidation(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.TemporaryDirectory()
        self.o, self.sb, self.lk = (Path(self.d.name, n) for n in ("o.json", "s.json", "l"))
        self.o.write_text(json.dumps({"claudeAiOauth": {"accessToken": "a" * 20, "refreshToken": "r" * 20,
                                                         "expiresAt": 1000}, "mcpOAuth": {"x": 1}}))

    def tearDown(self):
        self.d.cleanup()

    def test_refuses_missing_refresh_token(self):
        self.sb.write_text(json.dumps({"claudeAiOauth": {"accessToken": "a" * 20, "expiresAt": 2000}, "mcpOAuth": {}}))
        before = self.o.read_text()
        with self.assertRaises(ValueError):
            creds.write_back(self.sb, self.o, self.lk)
        self.assertEqual(self.o.read_text(), before)

    def test_refuses_lost_top_level_key(self):
        self.sb.write_text(json.dumps({"claudeAiOauth": {"accessToken": "b" * 20, "refreshToken": "s" * 20,
                                                          "expiresAt": 2000}}))
        with self.assertRaises(ValueError):
            creds.write_back(self.sb, self.o, self.lk)


class Harness(unittest.TestCase):
    def test_problems(self):
        ok = {"cwd": "/work", "mcp_servers": [], "plugins": ["cc-plugin-agents-md"],
              "memory_paths": {"auto": "/home/agent/.claude/projects/-work/memory/"}}
        self.assertEqual(events.harness_problems(ok), [])
        bad = {**ok, "mcp_servers": [{"name": "x"}], "plugins": ["user-plugin"], "memory_paths": {"user": "/root/x"}}
        self.assertEqual(len(events.harness_problems(bad)), 3)

    def test_smoke_fixture_init_passes(self):
        evs, _ = events.load((HERE / "fixtures" / "smoke4-haiku-tools.jsonl").read_text().splitlines())
        self.assertEqual(events.harness_problems(events.init_info(evs)), [])


class Diff(unittest.TestCase):
    def test_symlink_to_host_file_not_followed(self):
        with tempfile.TemporaryDirectory() as d:
            base, work, secret = Path(d, "base"), Path(d, "work"), Path(d, "host-secret.txt")
            base.mkdir()
            work.mkdir()
            secret.write_text("HOST-ONLY-CONTENT\n")
            os.symlink(secret, work / "link")
            os.mkfifo(work / "fifo")
            n = runner.write_diff(base, work, Path(d, "out.diff"))
            txt = Path(d, "out.diff").read_text()
            self.assertGreater(n, 0)
            self.assertNotIn("HOST-ONLY-CONTENT", txt)


class DiffFormat(unittest.TestCase):
    def test_modified_added_deleted(self):
        with tempfile.TemporaryDirectory() as d:
            base, work = Path(d, "base"), Path(d, "work")
            for r in (base, work):
                (r / "app").mkdir(parents=True)
            (base / "app" / "A.kt").write_text("a\nb\n")
            (work / "app" / "A.kt").write_text("a\nB\n")
            (base / "gone.txt").write_text("x\n")
            (work / "new.txt").write_text("n")
            (work / "build").mkdir()
            (work / "build" / "out.class").write_text("ignored")
            runner.write_diff(base, work, Path(d, "o.diff"))
            t = Path(d, "o.diff").read_text()
            self.assertIn("--- a/app/A.kt\n+++ b/app/A.kt\n", t)
            self.assertIn("-b\n+B\n", t)
            self.assertIn("--- a/gone.txt\n+++ /dev/null\n", t)
            self.assertIn("+n\n\\ No newline at end of file\n", t)
            self.assertNotIn("out.class", t)


class Classify(unittest.TestCase):
    def test_loose_limit_text(self):
        evs = [{"type": "result", "is_error": True, "subtype": "error", "result": "You've hit your limit · resets 3am"}]
        self.assertEqual(runner.classify(evs, 1, False, [], "", events.tokens(evs))["invalid_reason"], "usage_limit")


if __name__ == "__main__":
    unittest.main()
