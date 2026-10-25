"""Blind contract tests for creds.py — temp files only, never the real ~/.claude."""
import contextlib
import fcntl
import io
import json
import multiprocessing as mp
import os
import stat
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import creds  # noqa: E402

SECRET = "sk-ant-oat01-SECRETVALUE"


def write_cred(p: Path, exp_ms, token=SECRET):
    token = token.ljust(16, "x")  # 계약 갱신(루프 2): 되쓰기 전 토큰 형식 검증(길이 16 이상)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps({"claudeAiOauth": {"accessToken": token, "refreshToken": token + "R",
                                               "expiresAt": exp_ms, "scopes": ["x"]}}))


def mode(p):
    return stat.S_IMODE(os.stat(p).st_mode)


def _wb_proc(sandbox, origin, lock, q):
    q.put(creds.write_back(Path(sandbox), Path(origin), Path(lock)))


class CredsBase(unittest.TestCase):
    def setUp(self):
        self.td = tempfile.TemporaryDirectory()
        self.d = Path(self.td.name)
        self.origin = self.d / "host" / ".credentials.json"
        self.lock = self.d / "lock" / "creds.lock"
        self.lock.parent.mkdir(parents=True)
        self.sandbox = self.d / "sbx" / ".claude" / ".credentials.json"

    def tearDown(self):
        self.td.cleanup()


class TestExpiry(CredsBase):
    def test_valid(self):
        write_cred(self.origin, 1792933200123)
        self.assertAlmostEqual(creds.expiry(self.origin), 1792933200.123, places=3)

    def test_failures_return_zero(self):
        self.assertEqual(creds.expiry(self.d / "missing.json"), 0.0)
        bad = self.d / "bad.json"
        bad.write_text('{"claudeAiOauth": {"expiresAt": 17')
        self.assertEqual(creds.expiry(bad), 0.0)
        nokey = self.d / "nokey.json"
        nokey.write_text('{"claudeAiOauth": {}}')
        self.assertEqual(creds.expiry(nokey), 0.0)
        empty = self.d / "empty.json"
        empty.write_text("")
        self.assertEqual(creds.expiry(empty), 0.0)


class TestCopyIn(CredsBase):
    def test_copy_and_mode(self):
        write_cred(self.origin, 1000)
        home = self.d / "agent-home"
        home.mkdir()
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            p = creds.copy_in(home, self.origin, self.lock)
        self.assertEqual(Path(p), home / ".claude" / ".credentials.json")
        self.assertEqual(Path(p).read_bytes(), self.origin.read_bytes())
        self.assertEqual(mode(p), 0o600)
        self.assertNotIn(SECRET, out.getvalue())


class TestWriteBack(CredsBase):
    def setUp(self):
        super().setUp()
        write_cred(self.origin, 2_000_000, token="ORIGIN")
        os.chmod(self.origin, 0o600)
        self.orig_bytes = self.origin.read_bytes()

    def test_later_replaces_atomically(self):
        write_cred(self.sandbox, 2_000_001, token="NEWER")
        os.chmod(self.sandbox, 0o644)
        ino = os.stat(self.origin).st_ino
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            self.assertIs(creds.write_back(self.sandbox, self.origin, self.lock), True)
        self.assertEqual(self.origin.read_bytes(), self.sandbox.read_bytes())
        self.assertEqual(mode(self.origin), 0o600)
        self.assertNotEqual(os.stat(self.origin).st_ino, ino, "expected temp file + os.replace")
        self.assertEqual(sorted(x.name for x in self.origin.parent.iterdir()), [".credentials.json"])
        self.assertNotIn("NEWER", out.getvalue())

    def test_equal_expiry_no_change(self):
        write_cred(self.sandbox, 2_000_000, token="SAME_EXP_DIFFERENT_TOKEN")
        self.assertIs(creds.write_back(self.sandbox, self.origin, self.lock), False)
        self.assertEqual(self.origin.read_bytes(), self.orig_bytes)

    def test_earlier_no_change(self):
        write_cred(self.sandbox, 1_999_999, token="OLDER")
        self.assertIs(creds.write_back(self.sandbox, self.origin, self.lock), False)
        self.assertEqual(self.origin.read_bytes(), self.orig_bytes)

    def test_broken_or_missing_sandbox_no_change(self):
        self.sandbox.parent.mkdir(parents=True)
        self.sandbox.write_text('{"claudeAiOauth": {"expiresAt": 9999999999999')  # truncated
        self.assertIs(creds.write_back(self.sandbox, self.origin, self.lock), False)
        self.assertEqual(self.origin.read_bytes(), self.orig_bytes)
        self.sandbox.unlink()
        self.assertIs(creds.write_back(self.sandbox, self.origin, self.lock), False)
        self.assertEqual(self.origin.read_bytes(), self.orig_bytes)

    def test_sandbox_without_expiry_key_no_change(self):
        self.sandbox.parent.mkdir(parents=True)
        self.sandbox.write_text('{"claudeAiOauth": {"accessToken": "x"}}')
        self.assertIs(creds.write_back(self.sandbox, self.origin, self.lock), False)
        self.assertEqual(self.origin.read_bytes(), self.orig_bytes)

    def test_blocks_while_lock_held(self):
        write_cred(self.sandbox, 3_000_000, token="NEWER")
        fd = os.open(self.lock, os.O_RDWR | os.O_CREAT, 0o600)
        fcntl.flock(fd, fcntl.LOCK_EX)
        result = {}
        t = threading.Thread(target=lambda: result.setdefault(
            "r", creds.write_back(self.sandbox, self.origin, self.lock)))
        try:
            t.start()
            time.sleep(0.5)
            self.assertTrue(t.is_alive(), "write_back did not wait for the flock")
            self.assertEqual(self.origin.read_bytes(), self.orig_bytes)
        finally:
            fcntl.flock(fd, fcntl.LOCK_UN)
            os.close(fd)
        t.join(5)
        self.assertFalse(t.is_alive())
        self.assertIs(result.get("r"), True)
        self.assertEqual(self.origin.read_bytes(), self.sandbox.read_bytes())

    def test_concurrent_write_backs_keep_latest(self):
        ctx = mp.get_context("fork")
        q = ctx.Queue()
        exps = [2_000_000 + k * 1000 for k in range(1, 9)]
        sandboxes = []
        for k, e in enumerate(exps):
            sb = self.d / f"sbx{k}" / ".claude" / ".credentials.json"
            write_cred(sb, e, token=f"T{k}")
            sandboxes.append(sb)
        procs = [ctx.Process(target=_wb_proc, args=(str(sb), str(self.origin), str(self.lock), q))
                 for sb in reversed(sandboxes)]
        for p in procs:
            p.start()
        for p in procs:
            p.join(20)
        rs = [q.get(timeout=5) for _ in procs]
        self.assertTrue(any(rs))
        self.assertEqual(creds.expiry(self.origin), max(exps) / 1000)
        json.loads(self.origin.read_text())  # never left half-written
        self.assertEqual(mode(self.origin), 0o600)
        self.assertEqual(sorted(x.name for x in self.origin.parent.iterdir()), [".credentials.json"])


class TestWaitFresh(CredsBase):
    def test_fresh_enough_returns_without_sleep(self):
        write_cred(self.origin, (1000 + 3600) * 1000)
        sleeps, logs = [], []
        creds.wait_fresh(self.origin, min_left_s=2700, sleep=sleeps.append, now=lambda: 1000.0,
                         log=lambda *a, **k: logs.append(a), max_wait_s=600)
        self.assertEqual(sleeps, [])

    def test_waits_in_steps_until_refreshed(self):  # 계약 갱신: 10초 단위(중단 신호에 빨리 반응)
        clock = [1000.0]
        write_cred(self.origin, (1000 + 100) * 1000)  # 100 s left < 2700
        sleeps, logs = [], []

        def fake_sleep(s):
            sleeps.append(s)
            clock[0] += s
            if len(sleeps) == 3:
                write_cred(self.origin, (clock[0] + 7200) * 1000)

        creds.wait_fresh(self.origin, min_left_s=2700, sleep=fake_sleep, now=lambda: clock[0],
                         log=lambda *a, **k: logs.append(" ".join(map(str, a))), max_wait_s=3600)
        self.assertEqual(sleeps, [10, 10, 10])
        self.assertFalse(any(SECRET in m for m in logs))

    def test_gives_up_after_max_wait(self):
        clock = [1000.0]
        write_cred(self.origin, (1000 + 10) * 1000)

        def fake_sleep(s):
            clock[0] += s

        with self.assertRaises(RuntimeError):
            creds.wait_fresh(self.origin, min_left_s=2700, sleep=fake_sleep, now=lambda: clock[0],
                             log=lambda *a, **k: None, max_wait_s=300)
        self.assertLessEqual(clock[0] - 1000.0, 300 + 60)

    def test_missing_origin_waits_then_raises(self):
        clock = [0.0]
        with self.assertRaises(RuntimeError):
            creds.wait_fresh(self.d / "nope.json", min_left_s=10,
                             sleep=lambda s: clock.__setitem__(0, clock[0] + s),
                             now=lambda: clock[0], log=lambda *a, **k: None, max_wait_s=120)


if __name__ == "__main__":
    unittest.main()
