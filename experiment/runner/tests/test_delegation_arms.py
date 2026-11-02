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
        self.assertEqual(runner.ARMS, {"direct": None, "explore-haiku": "haiku",
                                       "explore-sonnet": "sonnet", "explore-opus": "opus"})

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


if __name__ == "__main__":
    unittest.main()
