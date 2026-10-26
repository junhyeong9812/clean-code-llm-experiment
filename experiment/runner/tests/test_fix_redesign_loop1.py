"""tasks-redesign 리뷰 루프 1 수정 확인(fix verification): fd 번호 · 대상 못 푼 쓰기 '모름' · 서브셸 작업 위치 · 글롭 ·
대상 모르는 python 쓰기의 첫 편집 턴 · 파일럿 실데이터(명령 문자열 고정본)."""
import json
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import events  # noqa: E402


def writes(cmd, cwd="/work"):
    return events.bash_writes(events.segments(cmd, cwd)[0])


def bash(turn, cmd, err=False):
    return events.Call(turn, "Bash", {"command": cmd}, f"t{turn}", False, "", err)


class FdNumbers(unittest.TestCase):
    def test_fd_dup_after_writer_is_not_a_path(self):
        self.assertEqual(writes("sed -i 's/a/b/' app/A.kt 2>&1"), (["app/A.kt"], []))
        self.assertEqual(writes("cp /tmp/A.kt app/A.kt 2>/dev/null"), (["app/A.kt"], []))
        self.assertEqual(writes("mv app/A.kt.new app/A.kt 2>&1"), (["app/A.kt"], []))
        self.assertEqual(writes("echo x | tee app/a.txt 2>/dev/null"), (["app/a.txt"], []))


class UnresolvedWrites(unittest.TestCase):
    def test_marked_unknown(self):
        cases = {"grep -rl X app | xargs sed -i 's/x/y/'": "xargs",
                 "find app -name X.kt -exec sed -i 's/a/b/' {} +": "find",
                 "sed -i \"s/a/b/\" $(grep -rl foo app)": "subst",
                 "sed -i 's/a/b/' app/*.kt": "glob",
                 "sed -i 's/a/b/'": "sed"}
        for cmd, u in cases.items():
            with self.subTest(cmd=cmd):
                tg, unk = writes(cmd)
                self.assertEqual(tg, [])
                self.assertIn(u, unk)

    def test_line_continuation_and_multiline_quote(self):
        self.assertEqual(writes("sed -i 's/a/b/' \\\n  app/A.kt"), (["app/A.kt"], []))
        self.assertEqual(writes("python3 -c \"\nopen('app/A.kt','w').write('x')\n\""), (["app/A.kt"], []))

    def test_xargs_reader_is_not_edit(self):
        self.assertEqual(writes("grep -rl X app | xargs grep -n foo"), ([], []))


class Subshell(unittest.TestCase):
    def test_cd_in_parens_does_not_leak(self):
        segs, after = events.segments("(cd app && sed -i 's/a/b/' A.kt); sed -i 's/a/b/' B.kt")
        self.assertEqual(events.bash_writes(segs), (["app/A.kt", "B.kt"], []))
        self.assertEqual(after, "/work")
        self.assertEqual(events.segments("sh -c 'cd app && ls'; ls")[1], "/work")


class FirstEditTurn(unittest.TestCase):
    SP = events.parse_symbols("app/A.kt:1-5 fix\n")

    def test_inline_python_unknown_counts_turn_not_target(self):
        cs = [bash(1, "python3 - <<'E'\nimport sys\nopen(sys.argv[1],'w').write('x')\nE"), bash(2, "sed -i 's/a/b/' app/A.kt")]
        m = events.metrics(cs, self.SP)
        self.assertEqual((m["first_edit_turn"], m["first_edit_tool_turn"], m["edit_unknown_turns"]), (1, 2, [1]))

    def test_script_and_hint_do_not_count(self):
        cs = [bash(1, "python3 tool.py"), bash(2, "node -e \"require('fs').writeFileSync(p, s)\""),
              bash(3, "sed -i 's/a/b/' app/A.kt")]
        m = events.metrics(cs, self.SP)
        self.assertEqual((m["first_edit_turn"], m["edit_unknown_turns"]), (3, [1, 2]))


class PilotRealData(unittest.TestCase):
    """직전 파일럿(tasks-a-judge, 2026-10-26) 4개 실행의 Bash 명령 고정본 — 손으로 확인한 편집 턴·대상(실행 diff 와 일치)."""

    def test_pilot_commands(self):
        data = json.loads((HERE / "fixtures" / "pilot-bash-commands.json").read_text())
        for run in data:
            with self.subTest(run=run["run"]):
                cs = [bash(t, cmd) for t, cmd in run["calls"]]
                m = events.metrics(cs, [], existing=set(run["existing_fix_files"]))
                self.assertEqual(m["first_edit_tool_turn"], run["expect_edit_turn"])
                self.assertEqual(sorted(m["edited_paths"]), sorted(run["expect_edited"]))
                self.assertEqual(m["edit_unknown_turns"], [])


if __name__ == "__main__":
    unittest.main()
