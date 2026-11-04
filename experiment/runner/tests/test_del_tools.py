"""ADR-0009 실행 순서·로그인 유지 도구."""
import sys
import tempfile
import unittest
from collections import Counter
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import del_schedule as ds  # noqa: E402
import keepalive  # noqa: E402


class Schedule(unittest.TestCase):
    def test_main_counts_and_balance(self):
        js = ds.jobs("main", ["S1", "S2", "S3", "S4", "D1", "D2", "D3"])
        self.assertEqual(len(js), 168)
        self.assertEqual(len(set(js)), 168)
        self.assertEqual(Counter(j[3] for j in js), {a: 42 for a in ds.ARMS})
        firsts = Counter(js[i][3] for i in range(0, 168, 4))  # 칸마다 첫 방식이 고르게
        self.assertLessEqual(max(firsts.values()) - min(firsts.values()), 3)

    def test_deterministic(self):
        self.assertEqual(ds.jobs("main", ["S1", "D1"]), ds.jobs("main", ["S1", "D1"]))

    def test_pilot(self):
        js = ds.jobs("pilot", ["D1", "D2", "D3"], ["S1", "S2", "S3", "S4"])
        self.assertEqual(len(js), 24 + 8)
        self.assertTrue(all(j[0] == 1 for j in js))
        self.assertEqual(Counter(j[3] for j in js[24:]), {"solo": 8})

    def test_argv(self):
        a = ds.argv((2, "D1", "god", "explore-haiku"), "R", "main")
        self.assertEqual(a[a.index("--arm") + 1], "explore-haiku")
        self.assertEqual(a[a.index("--stages") + 1], "public,hidden")
        self.assertEqual(a[a.index("--parallel") + 1], "1")
        self.assertEqual(a[a.index("--min-mem-gb") + 1], "12")
        self.assertNotIn("--stages", ds.argv((1, "D1", "god", "solo"), "R", "pilot"))


class ScheduleState(unittest.TestCase):
    def test_spent_and_missing(self):
        import json
        with tempfile.TemporaryDirectory() as d:
            rows = [{"rep": 1, "case": "D1", "structure": "god", "arm": "solo", "valid": True, "cost_usd": 0.5},
                    {"rep": 1, "case": "D1", "structure": "god", "arm": "explore-haiku", "valid": False, "cost_usd": 0.25}]
            (Path(d) / "runs.jsonl").write_text("".join(json.dumps(r) + "\n" for r in rows) + "broken\n")
            self.assertEqual(ds.spent(d), 0.75)
            js = [(1, "D1", "god", "solo"), (1, "D1", "god", "explore-haiku")]
            self.assertEqual(ds.missing(js, d), [(1, "D1", "god", "explore-haiku")])


class Keepalive(unittest.TestCase):
    def test_tick_only_below(self):
        with tempfile.TemporaryDirectory() as d:
            calls = []
            with mock.patch.object(keepalive, "left_min", side_effect=[30.0]):
                self.assertIsNone(keepalive.tick(6, run=lambda *a, **k: calls.append(a), lock=Path(d) / "l"))
            with mock.patch.object(keepalive, "left_min", side_effect=[4.0, 4.0]):
                self.assertFalse(keepalive.tick(6, run=lambda *a, **k: mock.Mock(returncode=0),
                                                log=lambda *a, **k: None, lock=Path(d) / "l"))
            with mock.patch.object(keepalive, "left_min", side_effect=[4.0, 60.0]):
                self.assertTrue(keepalive.tick(6, run=lambda *a, **k: calls.append(a) or mock.Mock(returncode=0),
                                               log=lambda *a, **k: None, lock=Path(d) / "l"))
            self.assertEqual(calls, [(keepalive.PING,)])


if __name__ == "__main__":
    unittest.main()
