"""tasks-redesign 리뷰 루프 2 수정 확인(fix verification): 오류 끝난 Bash 의 쓰기 · 변수 경로·풀 수 없는 cd · 주석 ·
따옴표 안 `<<` · 복합 명령 낱말 · perl -M · git -C · python <<< · diff 대조(missed_edit_paths)."""
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


class ErrorCall(unittest.TestCase):
    def test_failed_call_keeps_paths_as_unknown_turn(self):
        cs = [bash(1, "sed -i 's/a/b/' app/A.kt && ./gradlew -q compileKotlin", err=True)]
        m = events.metrics(cs, events.parse_symbols("app/A.kt:1-5 fix\n"))
        self.assertEqual((m["edited_paths"], m["edit_unknown_turns"]), (["app/A.kt"], [1]))
        self.assertIsNone(m["first_edit_tool_turn"])  # 실행됐는지 모른다 — 첫 편집으로 확정하지 않는다


class Variables(unittest.TestCase):
    def test_variable_targets_and_cwd(self):
        self.assertEqual(writes('F=app/A.kt; sed -i "s/a/b/" "$F"'), ([], ["var"]))
        self.assertEqual(writes('for f in app/A.kt app/B.kt; do sed -i "s/a/b/" "$f"; done'), ([], ["var"]))
        self.assertEqual(writes('cd "$D" && sed -i "s/a/b/" A.kt'), ([], ["cwd"]))
        self.assertEqual(writes("cd app && cd - && sed -i 's/a/b/' A.kt"), ([], ["cwd"]))
        self.assertEqual(writes('cd "$D" && sed -i "s/a/b/" /work/app/A.kt'), (["app/A.kt"], []))
        # 계약 갱신(리뷰 루프 3): 모르는 위치는 다음 호출까지 유지(절대 경로 cd 로만 복구)
        self.assertEqual(events.segments('cd "$D"')[1], events.UNKNOWN_CWD)

    def test_var_is_unsure_for_first_edit_turn(self):
        m = events.metrics([bash(1, "./gradlew test > $LOG")], [])
        self.assertEqual((m["first_edit_turn"], m["edit_unknown_turns"]), (None, [1]))


class ParserMisc(unittest.TestCase):
    def test_cases(self):
        cases = {"echo a # save > notes": ([], []),
                 "perl -Mstrict -ne 'print' app/A.kt": ([], []),
                 "{ cd app; sed -i 's/a/b/' X.kt; }": (["app/X.kt"], []),
                 "python3 -c \"print(1 << 2)\"\nsed -i 's/a/b/' app/A.kt": (["app/A.kt"], []),
                 "git -C app apply <<'EOF'\n--- a/A.kt\n+++ b/A.kt\nEOF": (["app/A.kt"], []),
                 "python3 <<< \"open('app/A.kt','w').write('x')\"": (["app/A.kt"], []),
                 "ruby -pi -e 'gsub(/a/,\"b\")' app/A.kt": (["app/A.kt"], []),
                 "grep -n '#include' app/A.kt > /dev/null": ([], []),
                 "python3 -c \"if 0<len(L): open('app/A.kt','w')\"": (["app/A.kt"], [])}
        for cmd, want in cases.items():
            with self.subTest(cmd=cmd):
                self.assertEqual(writes(cmd), want)


class DiffCrossCheck(unittest.TestCase):
    def test_missed_edit_paths(self):
        cs = [bash(1, "sed -i 's/a/b/' app/A.kt"), bash(2, "xargs -I{} sh -c 'sed -i s/a/b/ {}' < list")]
        m = events.metrics(cs, [], existing={"app/A.kt", "app/B.kt"}, changed={"app/A.kt", "app/B.kt"})
        self.assertEqual(m["missed_edit_paths"], ["app/B.kt"])
        self.assertIsNone(events.metrics(cs, [])["missed_edit_paths"])


class ChangedFiles(unittest.TestCase):
    def test_tree_compare(self):
        import tempfile
        sys.path.insert(0, str(HERE.parent))
        import runner
        with tempfile.TemporaryDirectory() as d:
            a, b = Path(d, "a"), Path(d, "b")
            for r in (a, b):
                (r / "app").mkdir(parents=True)
                (r / "app" / "same.kt").write_text("x")
            (a / "app" / "mod.kt").write_text("1")
            (b / "app" / "mod.kt").write_text("2")
            (a / "app" / "gone.kt").write_text("g")
            (b / "app" / "new.kt").write_text("n")
            self.assertEqual(runner.changed_files(a, b), {"app/mod.kt", "app/gone.kt", "app/new.kt"})


if __name__ == "__main__":
    unittest.main()
