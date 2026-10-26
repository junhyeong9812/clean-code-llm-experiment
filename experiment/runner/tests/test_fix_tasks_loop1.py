"""과제 A 리뷰 루프 1 수정 확인(fix verification)."""
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import judge  # noqa: E402
import runner  # noqa: E402


class StageStatus(unittest.TestCase):
    R = {"tests": 3, "failed": [], "skipped": 0}

    def test_app_died_vs_timeout(self):
        self.assertEqual(judge.stage_outcome("app_died", None, self.R), "app_start_fail")
        self.assertEqual(judge.stage_outcome("app_start_timeout", None, self.R), "infra")


class BuildKind(unittest.TestCase):
    def test_kinds(self):
        self.assertEqual(judge.build_failure_kind(125, "docker: Error response"), "infra")
        self.assertEqual(judge.build_failure_kind(1, "Could not resolve org.x:y. No cached version available for offline mode"), "infra")
        self.assertEqual(judge.build_failure_kind(1, "e: file:///work/app/src/A.kt:3:1 Unresolved reference"), "compile")
        self.assertEqual(judge.build_failure_kind(1, "e: A.kt Unresolved\\nCould not resolve x"), "compile")


class BuildFiles(unittest.TestCase):
    def test_root_build_files_restored_and_recorded(self):
        with tempfile.TemporaryDirectory() as d:
            a, b = Path(d, "a"), Path(d, "b")
            for r in (a, b):
                (r / "gradle" / "wrapper").mkdir(parents=True)
                for f in judge.BUILD_FILES:
                    (r / f).write_text("orig")
            (b / "build.gradle.kts").write_text("tasks.withType<Test> { exclude everything }")
            self.assertEqual(judge.restore_build_files(a, b), ["build.gradle.kts"])
            self.assertEqual((b / "build.gradle.kts").read_text(), "orig")


class ExtraBuildScripts(unittest.TestCase):
    def test_removed_when_not_in_pristine(self):
        with tempfile.TemporaryDirectory() as d:
            a, b = Path(d, "a"), Path(d, "b")
            for r in (a, b):
                (r / "checks").mkdir(parents=True)
                (r / "build.gradle.kts").write_text("x")
                (r / "checks" / "build.gradle.kts").write_text("x")
            (b / "app").mkdir()
            (b / "app" / "build.gradle.kts").write_text("tasks.named('test') { enabled = false }")
            (b / "gradle.properties").write_text("org.gradle.jvmargs=-Dx")
            (b / "buildSrc" / "src").mkdir(parents=True)
            removed = judge.remove_extra_build_scripts(a, b)
            self.assertEqual(sorted(removed), ["app/build.gradle.kts", "buildSrc/", "gradle.properties"])
            self.assertTrue((b / "build.gradle.kts").is_file())

    def test_count_tests(self):
        with tempfile.TemporaryDirectory() as d:
            Path(d, "A.kt").write_text("@Test fun a() {}\n@Test\nfun b() {}\n// @Tested 아님")
            self.assertEqual(judge.count_tests(Path(d), Path(d, "none")), 2)


class Loop2(unittest.TestCase):
    def test_escaping_links_removed(self):
        import os
        with tempfile.TemporaryDirectory() as d:
            w = Path(d, "w")
            (w / "app").mkdir(parents=True)
            (w / "app" / "A.kt").write_text("x")
            os.symlink("/etc/passwd", w / "TASK.md")
            os.symlink("../../outside", w / "app" / "out")
            os.symlink("A.kt", w / "app" / "inner")
            self.assertEqual(sorted(judge.remove_escaping_links(w)), ["TASK.md", "app/out"])
            self.assertTrue((w / "app" / "inner").is_symlink())

    def test_edit_spans_not_targeted_but_hit(self):
        import events
        sp = events.parse_symbols("app/M.kt:1-1772 edit\napp/M.kt:1700-1740 fix")
        grep = events.Call(1, "Bash", {"command": "grep -n x app/M.kt"}, "a", False, "5:x")
        self.assertIsNone(events.metrics([grep], sp)["first_targeted_turn"])  # 파일 단위 edit 범위는 겨냥이 아니다
        ed = events.Call(2, "Edit", {"file_path": "/work/app/M.kt"}, "b", False, "", False,
                         {"structuredPatch": [{"oldStart": 30, "oldLines": 1, "lines": ["-a", "+b"]}]})
        self.assertTrue(events.metrics([ed], sp)["first_edit_hit"])  # import 편집도 적중(edit)

    def test_needs_rejudge(self):
        import runner
        self.assertTrue(runner.needs_rejudge({"success": None}))
        self.assertTrue(runner.needs_rejudge({"success": False, "stages": {"hidden": {"outcome": "fail"},
                                                                           "contract": {"outcome": "infra"}}}))
        self.assertFalse(runner.needs_rejudge({"success": False, "stages": {"hidden": {"outcome": "fail"}}}))


class CleanupLive(unittest.TestCase):
    def test_prefix_live(self):
        live = {"ccle-t1-god-sonnet-r1-123", "check-t1-god-fix-1-99"}
        self.assertTrue(runner.is_live("ccle-t1-god-sonnet-r1-123-jhidden", live))
        self.assertTrue(runner.is_live("check-t1-god-fix-1-99-jcontract", live))
        self.assertFalse(runner.is_live("ccle-t1-god-sonnet-r1-1234", live))


class Conjunction(unittest.TestCase):
    def test_definite_fail_survives_later_infra(self):
        self.assertFalse(judge.conjunction({"public": "fail", "contract": "infra"}, judge.STAGES, []))


if __name__ == "__main__":
    unittest.main()
