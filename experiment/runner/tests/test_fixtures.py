"""실제 캡처(claude 2.1.308, Haiku 스모크) 고정 입력으로 파서를 확인한다."""
import json
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import events  # noqa: E402
import runner  # noqa: E402

FIX = HERE / "fixtures"


def load(name):
    evs, bad = events.load((FIX / name).read_text().splitlines())
    return evs, bad


def result(evs):
    return next(e for e in evs if e["type"] == "result")


class Smoke1(unittest.TestCase):
    """Read 1회 + Bash 2회, 정상 종료."""

    def setUp(self):
        self.evs, self.bad = load("smoke1-haiku.jsonl")

    def test_tokens_equal_modelusage_sum(self):
        t = events.tokens(self.evs)
        self.assertEqual(t["total_tokens"], 6 + 1180 + 48614 + 10397)
        self.assertFalse(t["partial_tokens"])
        self.assertEqual(self.bad, 0)

    def test_turns_are_message_ids_not_cli_num_turns(self):
        # 첫 메시지가 Read·Bash 를 함께 호출(병렬) — 턴(API 호출 = message.id)은 3, CLI num_turns 는 4.
        # 행에는 둘 다 남는다(turns · result_turns). 지표는 앞 실험과 같은 message.id 기준.
        cs, turns = events.calls(self.evs)
        self.assertEqual(turns, 3)
        self.assertEqual(result(self.evs)["num_turns"], 4)
        self.assertEqual([c.turn for c in cs], [1, 1, 2])

    def test_full_read_recorded_not_targeted(self):
        cs, _ = events.calls(self.evs)
        m = events.metrics(cs, events.parse_symbols("a.txt:1-1 fix"))
        self.assertEqual(m["full_read_turns"], {"a.txt": 1})
        self.assertIsNone(m["first_targeted_turn"])
        self.assertEqual(m["lines_read"]["read"], 2)

    def test_classify_normal(self):
        cs, _ = events.calls(self.evs)
        self.assertEqual(runner.classify(self.evs, 0, False, events.audit(cs)),
                         {"end_reason": "normal", "censored": False, "valid": True, "invalid_reason": None})


class Smoke2(unittest.TestCase):
    """Bash 편집으로 끝난 정상 실행."""

    def test_bash_edit_counts_as_first_edit_without_hit(self):
        evs, _ = load("smoke2-haiku-normal.jsonl")
        cs, turns = events.calls(evs)
        self.assertEqual(turns, result(evs)["num_turns"])
        m = events.metrics(cs, events.parse_symbols("notes/greeting.txt:1 fix"))
        self.assertEqual(m["first_edit_turn"], 3)
        self.assertIsNone(m["first_edit_hit"])
        self.assertEqual(events.audit(cs), [])


class Smoke3(unittest.TestCase):
    """시간 상한 강제 종료: result 없음 → 부분 토큰, censored·valid."""

    def test_partial_tokens_and_censored(self):
        evs, _ = load("smoke3-haiku-timeout.jsonl")
        self.assertFalse(any(e["type"] == "result" for e in evs))
        t = events.tokens(evs)
        self.assertTrue(t["partial_tokens"])
        self.assertEqual(t["total_tokens"], 63055)
        c = runner.classify(evs, 137, True, [])
        self.assertEqual((c["end_reason"], c["censored"], c["valid"]), ("timeout", True, True))

    def test_truncated_last_line_is_counted_not_fatal(self):
        lines = (FIX / "smoke3-haiku-timeout.jsonl").read_text().splitlines()
        evs, bad = events.load(lines[:-1] + [lines[-1][: len(lines[-1]) // 2]])
        self.assertEqual(bad, 1)
        self.assertEqual(len(evs), len(lines) - 1)


class Rows(unittest.TestCase):
    def test_jobs_rotate_structure_order_and_skip_done(self):
        jobs = runner.jobs_for(["A", "B"], ["god", "layered"], "m", [1], {("A", "layered", "m", 1)})
        self.assertEqual(jobs, [("A", "god", 1), ("B", "god", 1), ("B", "layered", 1)])

    def test_done_keys_only_valid(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            rows = [{"case": "A", "structure": "god", "model": "m", "rep": 1, "valid": True},
                    {"case": "A", "structure": "layered", "model": "m", "rep": 1, "valid": False}]
            Path(d, "runs.jsonl").write_text("".join(json.dumps(r) + "\n" for r in rows))
            self.assertEqual(runner.done_keys(Path(d)), {("A", "god", "m", 1)})


class Smoke4Tools(unittest.TestCase):
    """Bash grep -n → Read(offset) → Edit → Write. 2.1.308 에는 Grep 도구가 없고 Edit 결과 텍스트에 줄 번호가 없다."""

    def setUp(self):
        evs, _ = load("smoke4-haiku-tools.jsonl")
        self.cs, self.turns = events.calls(evs)
        self.spans = events.parse_symbols("notes/data.txt:212-212 fix\nnotes/data.txt:1-20 read")
        self.m = events.metrics(self.cs, self.spans)

    def test_bash_grep_line_is_targeted_access(self):
        self.assertEqual(self.m["first_targeted_turn_by_span"], {"notes/data.txt:212-212": 3})

    def test_read_range_from_tool_use_result(self):
        read = next(c for c in self.cs if c.name == "Read")
        self.assertEqual(read.meta["file"]["startLine"], 207)
        self.assertEqual([sp.start for sp in events.targeted(read, self.spans)], [212])

    def test_edit_range_from_original_file_and_hit(self):
        edit = next(c for c in self.cs if c.name == "Edit")
        self.assertNotIn("212", edit.result)  # 결과 텍스트에는 줄 번호가 없다
        self.assertEqual(events.edit_range(edit), (212, 212))
        self.assertTrue(self.m["first_edit_hit"])
        self.assertEqual(self.m["edited_paths"], ["notes/data.txt", "notes/done.txt"])

    def test_bash_cat_of_task_files_not_counted_without_symbols(self):
        self.assertEqual(self.m["full_read_turns"], {})


class EditRange(unittest.TestCase):
    def test_pure_insertion_maps_to_preceding_line(self):
        meta = {"structuredPatch": [{"oldStart": 8, "oldLines": 6, "lines":
                                     [" a", " b", " c", "+new", " d", " e", " f"]}]}
        c = events.Call(1, "Edit", {"file_path": "/work/x.kt"}, "t", False, "", False, meta)
        self.assertEqual(events.edit_range(c), (10, 10))

    def test_multi_hunk_and_replace_lines(self):
        meta = {"structuredPatch": [{"oldStart": 1, "oldLines": 2, "lines": [" a", "-b", "+B"]},
                                    {"oldStart": 50, "oldLines": 3, "lines": [" x", "-y", "-z", "+YZ"]}]}
        c = events.Call(1, "Edit", {"file_path": "/work/x.kt"}, "t", False, "", False, meta)
        self.assertEqual(events.edit_range(c), (2, 52))


class BashReads(unittest.TestCase):
    def call(self, cmd, result="", turn=1):
        return events.Call(turn, "Bash", {"command": cmd}, "t", False, result)

    def test_sed_range_targets(self):
        spans = events.parse_symbols("app/A.kt:100-110 fix")
        self.assertTrue(events.targeted(self.call("sed -n '95,105p' app/A.kt", "x"), spans))
        self.assertFalse(events.targeted(self.call("sed -n '1,50p' app/A.kt", "x"), spans))

    def test_grep_single_file_output_uses_named_path(self):
        spans = events.parse_symbols("app/A.kt:100-110 fix")
        c = self.call("grep -n foo /work/app/A.kt", "105:val foo = 1")
        self.assertTrue(events.targeted(c, spans))

    def test_grep_multi_file_output(self):
        spans = events.parse_symbols("app/A.kt:100-110 fix")
        c = self.call("grep -rn foo app", "app/B.kt:3:foo\napp/A.kt:104:foo")
        self.assertTrue(events.targeted(c, spans))
        self.assertFalse(events.targeted(self.call("grep -rn foo app", "app/A.kt:3:foo"), spans))

    def test_cat_whole_file_is_full_read(self):
        spans = events.parse_symbols("app/A.kt:100-110 fix")
        m = events.metrics([self.call("cat -n app/A.kt", "1\tx")], spans)
        self.assertEqual(m["full_read_turns"], {"app/A.kt": 1})
        self.assertIsNone(m["first_targeted_turn"])


class WaitFresh(unittest.TestCase):
    def test_stop_aborts_wait_without_calling_host_cli(self):
        import tempfile
        import creds
        self.assertFalse(hasattr(creds, "host_refresh"))  # 러너는 호스트 CLI 를 부르지 않는다(호스트 ~/.claude 쓰기 금지)
        with tempfile.TemporaryDirectory() as d:
            o = Path(d, "c.json")
            o.write_text(json.dumps({"claudeAiOauth": {"expiresAt": 500 * 1000}}))
            n = []
            with self.assertRaises(RuntimeError):
                creds.wait_fresh(o, min_left_s=60, sleep=lambda s: n.append(s), now=lambda: 1000.0,
                                 log=lambda *a, **k: None, stopped=lambda: len(n) >= 2)
            self.assertEqual(n, [10, 10])

if __name__ == "__main__":
    unittest.main()
