"""Blind contract tests for Bash edit recognition / cwd tracking in events.py.

Written from the contract only (implementation bodies not read).
"""
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import events  # noqa: E402

SPANS_TEXT = ("app/src/A.kt:10-20 fix\n"
              "app/src/B.kt:5-8 read\n"
              "app/src/C.kt:1-3 edit\n")


def spans(text=SPANS_TEXT):
    return events.parse_symbols(text)


def bw(cmd, cwd="/work"):
    segs, _ = events.segments(cmd, cwd)
    return events.bash_writes(segs)


OUT = "line\n" * 21  # non-empty stdout for read commands (see note in report)


def B(turn, cmd, result="", is_error=False):
    return events.Call(turn, "Bash", {"command": cmd}, f"t{turn}", False, result, is_error)


def M(calls, sp=None, existing=None):
    return events.metrics(calls, spans() if sp is None else sp, existing)


DIFF_P1 = ("--- a/app/src/A.kt\n+++ b/app/src/A.kt\n@@ -1,1 +1,1 @@\n-x\n+y\n")


# ---------------- rule 1: working directory ----------------
class TestCwd(unittest.TestCase):
    def test_cd_changes_cwd_after(self):
        _, cwd = events.segments("cd app/src && ls")
        self.assertEqual(cwd, "/work/app/src")

    def test_pushd_changes_cwd(self):
        _, cwd = events.segments("pushd app && ls")
        self.assertEqual(cwd, "/work/app")

    def test_cd_then_write_in_same_command(self):
        self.assertEqual(bw("cd app/src && echo x > A.kt"), (["app/src/A.kt"], []))

    def test_initial_cwd_argument(self):
        self.assertEqual(bw("echo x > A.kt", cwd="/work/app/src"), (["app/src/A.kt"], []))

    def test_dotdot_inside_work(self):
        self.assertEqual(bw("cd app/src && echo x > ../x.txt"), (["app/x.txt"], []))

    def test_absolute_and_dot_prefix_normalized(self):
        self.assertEqual(bw("echo 1 > /work/x.txt; echo 2 > ./y.txt"), (["x.txt", "y.txt"], []))

    def test_cwd_carries_across_calls(self):
        m = M([B(1, "cd app/src"), B(2, "sed -i 's/a/b/' A.kt")])
        self.assertEqual(m["edited_paths"], ["app/src/A.kt"])

    def test_cwd_outside_work_resets_next_call(self):
        m = M([B(1, "cd /tmp"), B(2, "echo x > a.txt")])
        self.assertEqual(m["edited_paths"], ["a.txt"])

    def test_cd_var_not_resolved(self):
        targets, _ = bw("cd $DIR && echo x > a.txt")
        for t in targets:
            self.assertNotIn("$", t)
            self.assertNotIn("DIR", t)

    def test_error_call_still_moves_cwd(self):
        m = M([B(1, "cd app/src && false", is_error=True), B(2, "sed -i 's/a/b/' A.kt")])
        self.assertEqual(m["edited_paths"], ["app/src/A.kt"])


# ---------------- rule 2: write targets ----------------
class TestRedirects(unittest.TestCase):
    def test_redirect_kinds(self):
        self.assertEqual(bw("echo a > a.txt; echo b >> b.txt; echo c >| c.txt; ls &> d.txt"),
                         (["a.txt", "b.txt", "c.txt", "d.txt"], []))

    def test_dedup_first_appearance_order(self):
        self.assertEqual(bw("echo > b.txt; echo > a.txt; echo >> b.txt"), (["b.txt", "a.txt"], []))

    def test_tee_and_tee_a(self):
        self.assertEqual(bw("echo x | tee a.txt b.txt; echo y | tee -a c.txt"),
                         (["a.txt", "b.txt", "c.txt"], []))


class TestSed(unittest.TestCase):
    def test_sed_i(self):
        self.assertEqual(bw("sed -i 's/a/b/' app/A.kt"), (["app/A.kt"], []))

    def test_sed_i_suffix(self):
        self.assertEqual(bw("sed -i.bak 's/a/b/' app/A.kt"), (["app/A.kt"], []))

    def test_sed_bundled(self):
        self.assertEqual(bw("sed -Ei 's/a+/b/' app/A.kt"), (["app/A.kt"], []))

    def test_sed_in_place_long(self):
        self.assertEqual(bw("sed --in-place 's/a/b/' app/A.kt"), (["app/A.kt"], []))

    def test_sed_e_all_positional_are_files(self):
        self.assertEqual(bw("sed -i -e 's/a/b/' -e 's/c/d/' x.kt y.kt"), (["x.kt", "y.kt"], []))

    def test_sed_f_all_positional_are_files(self):
        self.assertEqual(bw("sed -i -f fix.sed x.kt y.kt"), (["x.kt", "y.kt"], []))

    def test_sed_without_e_first_positional_is_script(self):
        targets, _ = bw("sed -i 's/a/b/' x.kt y.kt")
        self.assertEqual(targets, ["x.kt", "y.kt"])

    def test_sed_n_is_read(self):
        self.assertEqual(bw("sed -n '1,20p' app/A.kt"), ([], []))

    def test_perl_pi(self):
        self.assertEqual(bw("perl -pi -e 's/a/b/' app/A.kt"), (["app/A.kt"], []))


class TestCopyMove(unittest.TestCase):
    def test_cp_file_dest(self):
        self.assertEqual(bw("cp a.kt b.kt"), (["b.kt"], []))

    def test_mv_file_dest(self):
        self.assertEqual(bw("mv app/a.kt app/b.kt"), (["app/b.kt"], []))

    def test_cp_trailing_slash_dir(self):
        self.assertEqual(bw("cp src/A.kt dst/"), (["dst/A.kt"], []))

    def test_cp_dot_dir_with_cwd(self):
        self.assertEqual(bw("cd app && cp ../lib/A.kt ."), (["app/A.kt"], []))

    def test_mv_dotless_last_component_is_dir(self):
        self.assertEqual(bw("mv A.kt backup"), (["backup/A.kt"], []))

    def test_install_dest(self):
        self.assertEqual(bw("install src/run.sh bin/run.sh"), (["bin/run.sh"], []))


class TestPatch(unittest.TestCase):
    def test_patch_p1_heredoc(self):
        self.assertEqual(bw("patch -p1 <<'EOF'\n" + DIFF_P1 + "EOF"), (["app/src/A.kt"], []))

    def test_git_apply_default_p1(self):
        self.assertEqual(bw("git apply <<'EOF'\n" + DIFF_P1 + "EOF"), (["app/src/A.kt"], []))

    def test_patch_p0(self):
        d = "--- app/src/A.kt\n+++ app/src/A.kt\n@@ -1 +1 @@\n-x\n+y\n"
        self.assertEqual(bw("patch -p0 <<'EOF'\n" + d + "EOF"), (["app/src/A.kt"], []))

    def test_patch_no_p_uses_basename_against_cwd(self):
        d = "--- a/app/src/A.kt\n+++ b/app/src/A.kt\n@@ -1 +1 @@\n-x\n+y\n"
        self.assertEqual(bw("cd app/src && patch <<'EOF'\n" + d + "EOF"), (["app/src/A.kt"], []))

    def test_dev_null_ignored(self):
        d = ("--- a/app/old.kt\n+++ /dev/null\n@@ -1 +0,0 @@\n-x\n"
             "--- /dev/null\n+++ b/app/new.kt\n@@ -0,0 +1 @@\n+y\n")
        self.assertEqual(bw("git apply <<'EOF'\n" + d + "EOF"), (["app/new.kt"], []))

    def test_patch_positional_file(self):
        self.assertEqual(bw("patch app/A.kt fix.diff"), (["app/A.kt"], []))

    def test_patch_stdin_file_unknown(self):
        self.assertEqual(bw("patch -p1 < fix.diff"), ([], ["patch"]))

    def test_patch_i_unknown(self):
        self.assertEqual(bw("patch -p1 -i fix.diff"), ([], ["patch"]))

    def test_patch_dry_run_not_edit(self):
        self.assertEqual(bw("patch --dry-run -p1 <<'EOF'\n" + DIFF_P1 + "EOF"), ([], []))

    def test_git_apply_check_stat_numstat_not_edit(self):
        for flag in ("--check", "--stat", "--numstat"):
            with self.subTest(flag=flag):
                self.assertEqual(bw(f"git apply {flag} <<'EOF'\n" + DIFF_P1 + "EOF"), ([], []))


class TestPython(unittest.TestCase):
    def py(self, code, cwd="/work", interp="python3"):
        return bw(f"{interp} - <<'E'\n{code}\nE", cwd)

    def test_c_open_w(self):
        self.assertEqual(bw("python3 -c \"open('x.txt','w').write('1')\""), (["x.txt"], []))

    def test_python_variants(self):
        for interp in ("python", "python3.11"):
            with self.subTest(interp=interp):
                self.assertEqual(bw(f"{interp} -c \"open('x.txt','a').write('1')\""), (["x.txt"], []))

    def test_heredoc_open_resolved_against_cwd(self):
        code = "p='A.kt'\ns=open(p).read()\nopen(p,'w').write(s.replace('a','b'))"
        self.assertEqual(self.py(code, cwd="/work/app/src"), (["app/src/A.kt"], []))

    def test_heredoc_after_cd(self):
        code = "open('A.kt','w').write('x')"
        self.assertEqual(bw(f"cd app/src && python3 - <<'E'\n{code}\nE"), (["app/src/A.kt"], []))

    def test_modes(self):
        for mode in ("w", "a", "x", "r+", "wb"):
            with self.subTest(mode=mode):
                self.assertEqual(self.py(f"f=open('m.txt','{mode}')"), (["m.txt"], []))

    def test_keyword_mode(self):
        self.assertEqual(self.py("f=open('m.txt', mode='w')"), (["m.txt"], []))

    def test_read_only_python(self):
        self.assertEqual(self.py("print(open('A.kt').read())\nprint(open('B.kt','r').read())"), ([], []))

    def test_path_write_text(self):
        code = "from pathlib import Path\nPath('app/x.kt').write_text('y')\nPath('app/y.bin').write_bytes(b'')"
        self.assertEqual(self.py(code), (["app/x.kt", "app/y.bin"], []))

    def test_path_slash_join(self):
        code = "from pathlib import Path\nroot=Path('app')\n(root/'src'/'A.kt').write_text('y')"
        self.assertEqual(self.py(code), (["app/src/A.kt"], []))

    def test_os_path_join(self):
        code = "import os\nopen(os.path.join('app','src','A.kt'),'w').write('y')"
        self.assertEqual(self.py(code), (["app/src/A.kt"], []))

    def test_variable_all_assignments(self):
        code = "p='a.kt'\nif 0:\n    p='b.kt'\nopen(p,'w').write('')"
        targets, unknown = self.py(code)
        self.assertEqual(sorted(targets), ["a.kt", "b.kt"])
        self.assertEqual(unknown, [])

    def test_for_literal_list(self):
        code = "for f in ['a.kt', 'b.kt']:\n    open(f,'w').write('')"
        targets, unknown = self.py(code)
        self.assertEqual(sorted(targets), ["a.kt", "b.kt"])
        self.assertEqual(unknown, [])

    def test_function_param_positional_and_keyword(self):
        code = ("def fix(path, s):\n    open(path,'w').write(s)\n"
                "fix('a.kt', 'x')\nfix(path='b.kt', s='y')")
        targets, unknown = self.py(code)
        self.assertEqual(sorted(targets), ["a.kt", "b.kt"])
        self.assertEqual(unknown, [])

    def test_shutil_and_os_destinations(self):
        code = ("import shutil, os\nshutil.copy('a.kt','b.kt')\nshutil.copy2('a.kt','c.kt')\n"
                "shutil.copyfile('a.kt','d.kt')\nshutil.move('a.kt','e.kt')\n"
                "os.replace('t1','f.kt')\nos.rename('t2','g.kt')")
        targets, unknown = self.py(code)
        self.assertEqual(sorted(targets), ["b.kt", "c.kt", "d.kt", "e.kt", "f.kt", "g.kt"])
        self.assertEqual(unknown, [])

    def test_python_m_module_not_edit(self):
        self.assertEqual(bw("python3 -m pytest -q"), ([], []))

    def test_script_file_unknown(self):
        # 계약 갱신(리뷰 루프 1): '모름' 이름은 쓰기 확실성으로 나눈다 — 스크립트 파일은 쓰기였는지도 모름
        self.assertEqual(bw("python3 fix.py"), ([], ["python-script"]))

    def test_unresolvable_path_unknown(self):
        targets, unknown = self.py("import sys\nopen(sys.argv[1],'w').write('x')")
        self.assertEqual(targets, [])
        self.assertTrue(unknown)

    def test_non_constant_mode_unknown(self):
        targets, unknown = self.py("m='w' if 1 else 'r'\nmode=input()\nopen('a.kt', mode)")
        self.assertTrue(unknown)

    def test_chdir_unknown(self):
        _, unknown = self.py("import os\nos.chdir('app')\nopen('a.kt','w').write('x')")
        self.assertTrue(unknown)

    def test_parse_failure_with_write_hint_unknown(self):
        targets, unknown = self.py("open('a.kt','w').write(\n  def ::")
        self.assertEqual(targets, [])
        self.assertTrue(unknown)

    def test_heredoc_body_not_parsed_as_shell(self):
        code = "print('a > b.txt')\nx = 1 > 0"
        self.assertEqual(self.py(code), ([], []))


class TestOtherInterpreters(unittest.TestCase):
    def test_node_write_unknown(self):
        # 계약 갱신(리뷰 루프 1): 쓰기 흔적만 있는 인터프리터 코드는 '<이름>-hint'
        self.assertEqual(bw("node -e \"require('fs').writeFileSync('a.js','x')\""), ([], ["node-hint"]))

    def test_node_read_only_not_unknown(self):
        self.assertEqual(bw("node -e \"console.log(1)\""), ([], []))


# ---------------- rule 4: not edits ----------------
class TestNotEdits(unittest.TestCase):
    def test_outside_work(self):
        # 계약 갱신(리뷰 루프 2): "echo x > $OUT" 은 변수 경로 — '모름'(var)으로 옮김(test_bash_edits)
        for cmd in ("echo x > /tmp/a.txt", "echo x > /dev/null", "echo x > ~/a.txt",
                    "echo x > ../../etc/a.txt", "echo x | tee -",
                    "make > build.log", "make > run.out", "ls 2>&1", "ls >&2"):
            with self.subTest(cmd=cmd):
                self.assertEqual(bw(cmd), ([], []))

    def test_cd_dotdot_escape(self):
        self.assertEqual(bw("cd .. && echo x > a.txt"), ([], []))

    def test_heredoc_body_gt_not_redirect(self):
        cmd = "cat <<'EOF' > a.txt\nfoo > bar.txt\nEOF"
        self.assertEqual(bw(cmd), (["a.txt"], []))

    def test_here_string_not_heredoc(self):
        cmd = "cat <<<EOF\necho x > a.txt"
        self.assertEqual(bw(cmd), (["a.txt"], []))

    def test_here_string_with_redirect(self):
        self.assertEqual(bw("cat <<< 'hi' > out.txt"), (["out.txt"], []))

    def test_pure_reads(self):
        self.assertEqual(bw("cat A.kt; grep -n foo A.kt; sed -n '3,9p' A.kt"), ([], []))


# ---------------- rule 5: metrics ----------------
class TestMetricsEdits(unittest.TestCase):
    def test_edited_paths_mixed_order(self):
        calls = [B(1, "echo > n.txt"),
                 events.Call(2, "Write", {"file_path": "/work/w.kt", "content": "x"}, "t2", False, "ok"),
                 B(3, "sed -i 's/a/b/' n.txt app/src/A.kt")]
        self.assertEqual(M(calls)["edited_paths"], ["n.txt", "w.kt", "app/src/A.kt"])

    def test_created_paths_requires_existing(self):
        calls = [B(1, "echo > new.txt; sed -i s/a/b/ app/src/A.kt")]
        self.assertNotIn("new.txt", M(calls)["created_paths"])
        m = M(calls, existing={"app/src/A.kt"})
        self.assertIn("new.txt", m["created_paths"])
        self.assertNotIn("app/src/A.kt", m["created_paths"])

    def test_first_edit_bash_hit_file_level(self):
        m = M([B(1, "cat app/src/A.kt"), B(2, "cd app/src && sed -i '99s/a/b/' A.kt")])
        self.assertEqual(m["first_edit_tool_turn"], 2)
        self.assertEqual(m["first_edit_turn"], 2)
        self.assertEqual(m["first_edit_path"], "app/src/A.kt")
        self.assertEqual(m["first_edit_via"], "Bash")
        self.assertIs(m["first_edit_hit"], True)

    def test_first_edit_bash_hit_via_edit_span(self):
        m = M([B(1, "sed -i 's/a/b/' app/src/C.kt")])
        self.assertIs(m["first_edit_hit"], True)

    def test_first_edit_bash_miss(self):
        m = M([B(1, "sed -i 's/a/b/' app/src/B.kt")])
        self.assertEqual(m["first_edit_path"], "app/src/B.kt")
        self.assertIs(m["first_edit_hit"], False)

    def test_first_edit_hit_none_without_fix_edit_spans(self):
        sp = events.parse_symbols("app/src/B.kt:5-8 read\n")
        m = M([B(1, "sed -i 's/a/b/' app/src/B.kt")], sp=sp)
        self.assertIsNone(m["first_edit_hit"])

    def test_creation_not_first_edit_but_counts_first_edit_turn(self):
        existing = {"app/src/A.kt"}
        m = M([B(1, "echo x > app/src/New.kt"), B(2, "sed -i s/a/b/ app/src/A.kt")], existing=existing)
        self.assertEqual(m["first_edit_turn"], 1)
        self.assertEqual(m["first_edit_tool_turn"], 2)
        self.assertEqual(m["first_edit_path"], "app/src/A.kt")

    def test_existing_none_every_target_counts_existing(self):
        m = M([B(1, "echo x > app/src/New.kt")])
        self.assertEqual(m["first_edit_tool_turn"], 1)
        self.assertEqual(m["first_edit_path"], "app/src/New.kt")

    def test_first_edit_path_is_first_existing_target(self):
        m = M([B(1, "echo > new.txt; sed -i s/a/b/ app/src/B.kt app/src/A.kt")],
              existing={"app/src/A.kt", "app/src/B.kt"})
        self.assertEqual(m["first_edit_path"], "app/src/B.kt")
        self.assertIs(m["first_edit_hit"], True)  # any existing target (A.kt) matches a fix span

    def test_edit_tool_before_bash(self):
        calls = [events.Call(1, "Edit", {"file_path": "/work/app/src/B.kt", "old_string": "a",
                                         "new_string": "b"}, "t1", False, "ok"),
                 B(2, "sed -i s/a/b/ app/src/A.kt")]
        m = M(calls)
        self.assertEqual(m["first_edit_tool_turn"], 1)
        self.assertEqual(m["first_edit_via"], "Edit")
        self.assertEqual(m["first_edit_path"], "app/src/B.kt")

    def test_error_call_not_edit(self):
        m = M([B(1, "sed -i s/a/b/ app/src/A.kt", is_error=True)])
        self.assertIsNone(m["first_edit_turn"])
        self.assertIsNone(m["first_edit_tool_turn"])
        # 계약 갱신(리뷰 루프 2): 오류 끝난 호출의 대상은 남기고 턴은 '모름'(실행 여부를 모른다)
        self.assertEqual((m["edited_paths"], m["edit_unknown_turns"]), (["app/src/A.kt"], [1]))

    def test_python_unknown_not_first_edit_turn(self):
        m = M([B(1, "python3 fix.py"), B(2, "sed -i s/a/b/ app/src/A.kt")])
        self.assertEqual(m["first_edit_turn"], 2)
        self.assertEqual(m["edit_unknown_turns"], [1])

    def test_patch_unknown_counts_first_edit_turn(self):
        m = M([B(1, "patch -p1 < fix.diff"), B(2, "sed -i s/a/b/ app/src/A.kt")])
        self.assertEqual(m["first_edit_turn"], 1)
        self.assertEqual(m["first_edit_tool_turn"], 2)
        self.assertEqual(m["edit_unknown_turns"], [1])

    def test_unknown_turns_skip_errors(self):
        m = M([B(1, "python3 fix.py", is_error=True), B(2, "python3 fix.py")])
        self.assertEqual(m["edit_unknown_turns"], [1, 2])  # 계약 갱신(리뷰 루프 2): 오류 호출도 '모름' 턴

    def test_outside_work_bash_not_edit(self):
        m = M([B(1, "echo x > /tmp/a.txt; make > b.log")])
        self.assertIsNone(m["first_edit_turn"])
        self.assertEqual(m["edited_paths"], [])


class TestMetricsReads(unittest.TestCase):
    def test_cat_after_cd_full_read(self):
        m = M([B(1, "cd app/src"), B(2, "cat A.kt")])
        self.assertEqual(m["full_read_turns"], {"app/src/A.kt": 2})

    def test_cat_non_span_file_ignored(self):
        m = M([B(1, "cat app/src/Z.kt")])
        self.assertNotIn("app/src/Z.kt", m["full_read_turns"])

    def test_sed_n_covering_span_targets(self):
        m = M([B(1, "cd app/src"), B(2, "sed -n '5,25p' A.kt", result=OUT)])
        self.assertEqual(m["first_targeted_turn_by_span"].get("app/src/A.kt:10-20"), 2)
        self.assertEqual(m["first_targeted_turn"], 2)

    def test_sed_n_partial_cover_not_target(self):
        m = M([B(1, "sed -n '12,25p' app/src/A.kt", result=OUT)])
        self.assertNotIn("app/src/A.kt:10-20", m["first_targeted_turn_by_span"])

    def test_sed_n_width_400_boundary(self):
        m = M([B(1, "sed -n '1,400p' app/src/A.kt", result=OUT)])
        self.assertEqual(m["first_targeted_turn_by_span"].get("app/src/A.kt:10-20"), 1)
        m = M([B(1, "sed -n '1,401p' app/src/A.kt", result=OUT)])
        self.assertNotIn("app/src/A.kt:10-20", m["first_targeted_turn_by_span"])
        self.assertIn(1, list(m["chunk_read_turns"]) if not isinstance(m["chunk_read_turns"], dict)
                      else list(m["chunk_read_turns"].values()))

    def test_grep_path_lines_resolved_against_cwd(self):
        m = M([B(1, "cd app && grep -rn foo src", result="src/A.kt:15:foo\nsrc/Z.kt:3:foo")])
        self.assertEqual(m["first_targeted_turn_by_span"].get("app/src/A.kt:10-20"), 1)

    def test_grep_path_line_outside_span(self):
        m = M([B(1, "cd app && grep -rn foo src", result="src/A.kt:30:foo")])
        self.assertNotIn("app/src/A.kt:10-20", m["first_targeted_turn_by_span"])

    def test_grep_pathless_single_file_arg(self):
        m = M([B(1, "cd app/src"), B(2, "grep -n foo A.kt", result="15:foo")])
        self.assertEqual(m["first_targeted_turn_by_span"].get("app/src/A.kt:10-20"), 2)

    def test_grep_stdin_uses_preceding_command_file(self):
        m = M([B(1, "cd app/src && head -n 1000 A.kt | grep -n foo", result="15:foo")])
        self.assertEqual(m["first_targeted_turn_by_span"].get("app/src/A.kt:10-20"), 1)

    def test_edit_spans_ignored_for_targeting(self):
        m = M([B(1, "sed -n '1,10p' app/src/C.kt", result=OUT)])
        self.assertNotIn("app/src/C.kt:1-3", m["first_targeted_turn_by_span"])

    def test_targeted_direct_with_cwd(self):
        got = events.targeted(B(1, "sed -n '10,20p' A.kt", result=OUT), spans(), cwd="/work/app/src")
        self.assertTrue(any(getattr(s, "path", None) == "app/src/A.kt" for s in got), got)
        got = events.targeted(B(1, "sed -n '10,20p' A.kt", result=OUT), spans())
        self.assertFalse(any(getattr(s, "path", None) == "app/src/A.kt" for s in got), got)


if __name__ == "__main__":
    unittest.main()
