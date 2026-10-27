"""Bash 편집 인식·작업 위치 추적(tasks-redesign task 01): 쓰기 대상 판별 · 오탐 방지 · cd 뒤 상대 경로 · 첫 편집 적중."""
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import events  # noqa: E402

G = "app/src/main/kotlin/com/ccle/god"


def writes(cmd, cwd="/work"):
    return events.bash_writes(events.segments(cmd, cwd)[0])


def bash(turn, cmd, result="", err=False):
    return events.Call(turn, "Bash", {"command": cmd}, f"t{turn}", False, result, err)


class Targets(unittest.TestCase):
    def test_kinds(self):
        cases = {
            "sed -i 's/a/b/' app/A.kt": ["app/A.kt"],
            "sed -i.bak -e 's/a/b/' -e 's/c/d/' app/A.kt app/B.kt": ["app/A.kt", "app/B.kt"],
            "sed -Ei 's/a/b/' /work/app/A.kt": ["app/A.kt"],
            "perl -pi -e 's/a/b/' app/A.kt": ["app/A.kt"],
            "echo x > app/a.txt; echo y >> app/b.txt": ["app/a.txt", "app/b.txt"],
            "echo x | tee -a app/c.txt": ["app/c.txt"],
            "cat > app/x.sql <<'EOF'\nselect 1 > 2;\nEOF": ["app/x.sql"],
            "cp app/A.kt app/B.kt": ["app/B.kt"],
            "mv /tmp/A.kt app/src/": ["app/src/A.kt"],
            "patch -p1 <<'EOF'\n--- a/app/A.kt\n+++ b/app/A.kt\n@@ -1 +1 @@\n-a\n+b\nEOF": ["app/A.kt"],
            "git apply <<'EOF'\n--- a/app/A.kt\n+++ b/app/A.kt\nEOF": ["app/A.kt"],
        }
        for cmd, want in cases.items():
            with self.subTest(cmd=cmd):
                self.assertEqual(writes(cmd), (want, []))

    def test_python_heredoc_variable_and_param(self):  # 파일럿 C2 god·layered 모양
        god = ("cd /work/app/src/main/kotlin/com/ccle/god; python3 - <<'E'\np='Marketplace.kt'\ns=open(p).read()\n"
               "open(p,'w').write(s)\nopen('web/Dtos.kt','a').write('x > y')\np='web/OrderController.kt'\n"
               "s=open(p).read()\nopen(p,'w').write(s)\nE\ncd /work && bash test.sh 2>&1 | tail -20")
        self.assertEqual(sorted(writes(god)[0]), [f"{G}/Marketplace.kt", f"{G}/web/Dtos.kt", f"{G}/web/OrderController.kt"])
        lay = ("cd /work/app; python3 -I - <<'E'\ndef sub(p,a,b):\n    s=open(p).read(); open(p,'w').write(s.replace(a,b,1))\n"
               "sub('A.kt','x','y')\nsub(p='B.kt', a='1', b='2')\nfrom pathlib import Path\nPath('C.kt').write_text('z')\n"
               "for f in ['D.kt']:\n    open(f, mode='w')\nE")
        self.assertEqual(sorted(writes(lay)[0]), ["app/A.kt", "app/B.kt", "app/C.kt", "app/D.kt"])

    def test_python_reads_only(self):
        for cmd in ("python3 - <<'E'\nprint(open('app/A.kt').read().count('x'))\nE",
                    "python3 -c \"import json; print(json.load(open('a.json')))\"",
                    "python3 -m json.tool app/x.json"):
            with self.subTest(cmd=cmd):
                self.assertEqual(writes(cmd), ([], []))

    def test_unknown(self):
        for cmd in ("patch -p1 < fix.diff", "python3 tool.py", "python3 - <<'E'\nimport sys\nopen(sys.argv[1],'w')\nE",
                    "python3 - <<'E'\nm='w'\nopen('a.kt', m)\nE", "node -e \"require('fs').writeFileSync(f, s)\""):
            with self.subTest(cmd=cmd):
                self.assertEqual(writes(cmd)[0], [])
                self.assertTrue(writes(cmd)[1])

    def test_not_edits(self):
        for cmd in ("sed -n 1,5p app/A.kt", "grep -q x app/A.kt > /dev/null", "./gradlew test 2>&1 | tail -50",
                    "./gradlew test > /tmp/out.txt 2>&1", "./gradlew build | tee build.log", "echo x > out.log",
                    "git diff | patch --dry-run -p1", "git apply --check x.diff", "cat <<'EOF'\nhello > world\nEOF",
                    "cd /tmp && echo x > a.kt", "echo x > ~/note", "echo x > ../outside.kt"):
            with self.subTest(cmd=cmd):
                self.assertEqual(writes(cmd), ([], []))

    def test_variable_target_is_unknown(self):  # 계약 갱신(리뷰 루프 2): 변수 경로는 사본 파일일 수 있다
        self.assertEqual(writes("echo x > $OUT"), ([], ["var"]))

    def test_herestring_is_not_heredoc(self):
        self.assertEqual(writes("grep -c x <<< \"$s\"; echo y > app/a.txt"), (["app/a.txt"], []))


class Cwd(unittest.TestCase):
    def test_cd_persists_across_calls_and_resets_outside(self):
        segs, after = events.segments("cd app/src; ls")
        self.assertEqual((segs[0].cwd, after), ("/work/app/src", "/work/app/src"))
        self.assertEqual(events.segments("cd /tmp")[1], "/work")  # 사본 밖이면 다음 호출은 사본 루트
        self.assertEqual(events.segments("cd ..", "/work/app/src")[1], "/work/app")

    def test_read_metrics_after_cd(self):
        sp = events.parse_symbols(f"{G}/M.kt:100-110 fix\n{G}/web/D.kt:5-6 read\n")
        cs = [bash(1, f"cd {G}"),
              bash(2, "cat web/D.kt", "x"),
              bash(3, "sed -n 95,115p M.kt", "x"),
              bash(4, "grep -n foo -r .", "./M.kt:105:foo"),
              bash(5, "sed -i 's/a/b/' M.kt")]
        m = events.metrics(cs, sp)
        self.assertEqual(m["full_read_turns"], {f"{G}/web/D.kt": 2})
        self.assertEqual(m["first_targeted_turn_by_span"], {f"{G}/M.kt:100-110": 3})
        self.assertEqual((m["first_edit_tool_turn"], m["first_edit_path"], m["first_edit_hit"]), (5, f"{G}/M.kt", True))

    def test_grep_stdin_owner_is_pipe_source(self):
        sp = events.parse_symbols("app/A.kt:10-12 fix\napp/B.kt:1-2 read\n")
        c = bash(1, "cat app/B.kt; cat app/A.kt | grep -n foo", "11:foo")
        self.assertEqual([s.path for s in events.targeted(c, sp)], ["app/A.kt"])


class FirstEdit(unittest.TestCase):
    SP = events.parse_symbols("app/A.kt:10-12 fix\napp/A.kt:1-50 edit\n")

    def test_creation_is_not_first_edit_when_existing_known(self):
        cs = [bash(1, "echo x > app/New.kt"), bash(2, "sed -i 's/a/b/' app/B.kt"), bash(3, "sed -i 's/a/b/' app/A.kt")]
        m = events.metrics(cs, self.SP, existing={"app/A.kt", "app/B.kt"})
        self.assertEqual(m["first_edit_turn"], 1)
        self.assertEqual((m["first_edit_tool_turn"], m["first_edit_path"], m["first_edit_hit"]), (2, "app/B.kt", False))
        self.assertEqual(m["created_paths"], ["app/New.kt"])
        self.assertEqual(m["edited_paths"], ["app/New.kt", "app/B.kt", "app/A.kt"])

    def test_failed_bash_and_unknown_do_not_set_first_edit(self):
        cs = [bash(1, "sed -i 's/a/b/' app/A.kt", "no such file", err=True), bash(2, "python3 fix.py")]
        m = events.metrics(cs, self.SP)
        # 계약 갱신(리뷰 루프 2): 오류 끝난 sed 는 첫 편집이 아니지만 '모름' 턴으로 남는다
        self.assertEqual((m["first_edit_turn"], m["first_edit_tool_turn"], m["edit_unknown_turns"]), (None, None, [1, 2]))

    def test_edit_tool_values_unchanged(self):
        meta = {"structuredPatch": [{"oldStart": 11, "oldLines": 1, "lines": ["-a", "+b"]}]}
        cs = [events.Call(1, "Edit", {"file_path": "/work/app/A.kt"}, "e", False, "ok", False, meta)]
        m = events.metrics(cs, self.SP)
        self.assertEqual((m["first_edit_tool_turn"], m["first_edit_via"], m["first_edit_hit"]), (1, "Edit", True))


if __name__ == "__main__":
    unittest.main()
