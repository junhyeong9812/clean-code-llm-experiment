"""tasks-redesign 리뷰 루프 3 수정 확인(fix verification): 주석이 다음 줄을 삼키지 않음 · 모르는 작업 위치 유지 ·
if·case 갈래 쓰기 · 산술·[[ ]] 오탐 · perl 값 옵션 · cp -r · 사본 밖 출력의 명령 치환 · 줄 단위 적중."""
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


class Comments(unittest.TestCase):
    def test_comment_quote_does_not_swallow(self):
        self.assertEqual(writes("# Let's fix\nsed -i s/a/b/ app/x.kt\n# that's it"), (["app/x.kt"], []))
        segs, after = events.segments("# Check what's there\ncd app/src && grep -n foo X.kt\n# and it's line\nsed -i '5s/a/b/' X.kt")
        self.assertEqual((events.bash_writes(segs), after), ((["app/src/X.kt"], []), "/work/app/src"))
        self.assertEqual(writes("# shift 1 << n\nsed -i s/a/b/ app/x.kt"), (["app/x.kt"], []))
        self.assertEqual(writes("ls # trailing \\\nsed -i s/a/b/ app/x.kt"), (["app/x.kt"], []))


class UnknownCwdPersists(unittest.TestCase):
    def test_next_call(self):
        m = events.metrics([bash(1, "cd app/src"), bash(2, "pushd /tmp; popd"), bash(3, "sed -i s/a/b/ X.kt")], [],
                           existing={"app/src/X.kt"})
        self.assertEqual((m["first_edit_tool_turn"], m["created_paths"], m["edit_unknown_turns"]), (None, [], [3]))
        m = events.metrics([bash(1, 'cd "$D"'), bash(2, "cd /work/app && sed -i s/a/b/ X.kt")], [], existing={"app/X.kt"})
        self.assertEqual(m["first_edit_tool_turn"], 2)


class Conditionals(unittest.TestCase):
    def test_branch_writes_are_unknown_turns(self):
        m = events.metrics([bash(1, "if false; then echo x > app/A.kt; fi")], events.parse_symbols("app/A.kt:1-5 fix\n"))
        self.assertEqual((m["first_edit_tool_turn"], m["first_edit_turn"], m["edit_unknown_turns"], m["edited_paths"]),
                         (None, None, [1], ["app/A.kt"]))
        m = events.metrics([bash(1, "if grep -q x app/A.kt; then sed -i s/a/b/ app/A.kt; fi; sed -i s/c/d/ app/B.kt")], [])
        self.assertEqual((m["first_edit_tool_turn"], m["first_edit_path"], m["edit_unknown_turns"]), (1, "app/B.kt", [1]))

    def test_case_parens_are_not_subshells(self):
        segs, after = events.segments("cd app; case $x in a) echo 1 ;; esac; sed -i s/a/b/ A.kt")
        self.assertEqual((events.bash_writes([s for s in segs if not s.cond]), after), ((["app/A.kt"], []), "/work/app"))


class FalsePositives(unittest.TestCase):
    def test_cases(self):
        cases = {"echo $((n>3))": ([], []), "[[ $a > b ]] && echo ok": ([], []), "(( i > 5 )) && echo y": ([], []),
                 "echo \"$(date)\" > /tmp/log": ([], []),
                 "perl -I lib -i -pe s/a/b/ app/x.kt": (["app/x.kt"], []),
                 "perl -pi -e 's/a/b/' app/x.kt": (["app/x.kt"], [])}
        for cmd, want in cases.items():
            with self.subTest(cmd=cmd):
                self.assertEqual(writes(cmd), want)

    def test_cp_recursive_unknown(self):
        self.assertEqual(writes("cp -r app/dir app/dir2"), ([], ["cp"]))


class LineLevelHit(unittest.TestCase):
    def test_edit_tool_fix_only(self):
        sp = events.parse_symbols("app/M.kt:100-110 fix\napp/M.kt:1-2000 edit\n")
        meta = {"structuredPatch": [{"oldStart": 1600, "oldLines": 1, "lines": ["-a", "+b"]}]}
        cs = [events.Call(1, "Edit", {"file_path": "/work/app/M.kt"}, "e", False, "ok", False, meta)]
        m = events.metrics(cs, sp)
        self.assertEqual((m["first_edit_hit"], m["first_edit_hit_fix"]), (True, False))
        m = events.metrics([bash(1, "sed -i s/a/b/ app/M.kt")], sp)
        self.assertEqual((m["first_edit_hit"], m["first_edit_hit_fix"]), (True, None))


if __name__ == "__main__":
    unittest.main()
