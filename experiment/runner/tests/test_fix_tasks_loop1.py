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
