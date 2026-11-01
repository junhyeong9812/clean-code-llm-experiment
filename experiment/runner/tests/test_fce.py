"""첫 정답 편집 턴 추출기(fce.py) — ADR-0008 §5.4 단위 테스트. 합성 이벤트(fixtures/fce-*.jsonl)를 작은 기준 사본에서 재생한다.
기준 사본: app/A.kt = l1…l10 (줄마다 'l<번호>'), TASK.md. 기록 diff 는 기대 최종 트리를 손으로 만들어 runner.write_diff 로 낸다
(재생기와 독립)."""
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import events  # noqa: E402
import fce  # noqa: E402
import runner  # noqa: E402

FIX = HERE / "fixtures"
A = "app/A.kt"
BASE_A = "".join(f"l{i}\n" for i in range(1, 11))


def lines_with(**repl) -> str:
    """BASE_A 에서 줄 바꾸기: lines_with(l5='L5') · 삽입은 'l4': 'l4\\nNEW4'."""
    return "".join((repl.get(f"l{i}", f"l{i}")) + "\n" for i in range(1, 11))


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="fce-test-"))
        self.base = self.tmp / "base"
        (self.base / "app").mkdir(parents=True)
        (self.base / A).write_text(BASE_A)
        (self.base / "TASK.md").write_text("task\n")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def recorded(self, files: dict) -> bytes:
        """기대 최종 트리(기준 + files 덮어쓰기)의 기록 diff."""
        exp = self.tmp / "expected"
        if exp.exists():
            shutil.rmtree(exp)
        shutil.copytree(self.base, exp)
        for rel, txt in files.items():
            (exp / rel).parent.mkdir(parents=True, exist_ok=True)
            (exp / rel).write_text(txt)
        runner.write_diff(self.base, exp, self.tmp / "rec.diff")
        return (self.tmp / "rec.diff").read_bytes()

    def run_fixture(self, name, ranges, final_files, success=True, groups=None):
        evs, bad = events.load((FIX / f"fce-{name}.jsonl").read_text().splitlines())
        self.assertEqual(bad, 0)
        cs, turns = events.calls(evs)
        scratch = self.tmp / "scratch"
        scratch.mkdir(exist_ok=True)
        g = fce.group_lines(groups) if groups is not None else None
        return fce.evaluate(cs, turns, self.base, ranges, g, self.recorded(final_files), success, scratch)


FIX5 = {A: {5}}


class NoEdit(Base):
    def test_success_without_edit_is_fallback_none(self):
        r = self.run_fixture("no-edit", FIX5, {})
        self.assertFalse(r["unknown"], r["unknown_reasons"])
        self.assertIsNone(r["first_edit_turn"])
        self.assertIsNone(r["first_correct_edit_turn_raw"])
        self.assertTrue(r["fce_fallback"])
        self.assertIsNone(r["first_correct_edit_turn"])
        self.assertEqual(r["edits"], [])

    def test_failed_run_is_plus_inf(self):
        r = self.run_fixture("no-edit", FIX5, {}, success=False)
        self.assertEqual(r["first_correct_edit_turn"], "+inf")
        self.assertEqual(r["primary"], "+inf")
        self.assertFalse(r["fce_fallback"])


class ErrorEndedEdit(Base):
    def test_is_error_bash_that_changed_file_is_edit(self):
        # 턴 1 = 없는 도구 이름(bash) — 실행되지 않음(편집 아님, 오기 턴). 턴 2 = sed -i 뒤 테스트 실패로 is_error — 편집.
        r = self.run_fixture("error-edit", FIX5, {A: lines_with(l5="L5")})
        self.assertFalse(r["unknown"], r["unknown_reasons"])
        self.assertEqual(r["first_correct_edit_turn"], 2)
        self.assertEqual(r["typo_turns"], 1)
        self.assertEqual(len(r["edits"]), 1)
        self.assertTrue(r["edits"][0]["is_error"])
        self.assertEqual(r["edits"][0]["lines"], {A: ["5"]})


class ShiftedLines(Base):
    def test_correct_edit_after_shifting_edit_uses_base_coordinates(self):
        # 턴 1: 맨 앞에 3줄 삽입(앵커 0 — 범위 밖). 턴 3: 현재 8번째 줄(=기준 l5)을 바꿈 → 기준 좌표 5 → 적중.
        final = "h1\nh2\nh3\n" + lines_with(l5="L5")
        r = self.run_fixture("shift", FIX5, {A: final})
        self.assertFalse(r["unknown"], r["unknown_reasons"])
        self.assertEqual(r["first_edit_turn"], 1)
        self.assertEqual(r["edits"][0]["lines"], {A: ["0"]})
        self.assertFalse(r["edits"][0]["hit"])
        self.assertEqual(r["edits"][1]["lines"], {A: ["5"]})
        self.assertEqual(r["first_correct_edit_turn"], 3)
        self.assertEqual(r["reads_before_correct"], 1)  # 턴 2 의 sed -n (턴 1 은 편집이라 읽기 아님)

    def test_without_map_current_line_8_would_miss(self):
        # 같은 실행에서 범위를 현재 좌표 8 로 두면 비적중이어야 한다(좌표 맵이 실제로 쓰였는지 반증).
        final = "h1\nh2\nh3\n" + lines_with(l5="L5")
        r = self.run_fixture("shift", {A: {8}}, {A: final})
        self.assertIsNone(r["first_correct_edit_turn_raw"])


class OutsideFix(Base):
    def test_edit_outside_fix_is_not_correct(self):
        r = self.run_fixture("outside", FIX5, {A: lines_with(l9="L9")})
        self.assertFalse(r["unknown"], r["unknown_reasons"])
        self.assertIsNone(r["first_correct_edit_turn_raw"])
        self.assertEqual(r["first_correct_edit_turn"], 1)  # 성공 + 정답 편집 없음 → 첫 편집 사건(§5.1)
        self.assertTrue(r["fce_fallback"])
        self.assertEqual(r["sleep_turns"], 1)

    def test_failed_outside_is_inf_not_fallback(self):
        r = self.run_fixture("outside", FIX5, {A: lines_with(l9="L9")}, success=False)
        self.assertEqual(r["first_correct_edit_turn"], "+inf")
        self.assertFalse(r["fce_fallback"])


INSERT_PATCH = """--- a/app/A.kt
+++ b/app/A.kt
@@ -2,6 +2,7 @@
 l2
 l3
 l4
+NEW4
 l5
 l6
 l7
"""


class Insertion(Base):
    def test_pure_insertion_range_is_anchor_and_next(self):
        self.assertEqual(fce.patch_ranges(INSERT_PATCH), {A: {4, 5}})

    def test_insert_hits_by_anchor(self):
        # 턴 1: l7 뒤 삽입(앵커 7 — 비적중). 턴 3: l4 뒤 삽입(앵커 4 — 적중).
        r = self.run_fixture("insert", fce.patch_ranges(INSERT_PATCH), {A: lines_with(l4="l4\nNEW4", l7="l7\nNEW7")})
        self.assertFalse(r["unknown"], r["unknown_reasons"])
        self.assertEqual([e["lines"] for e in r["edits"]], [{A: ["7"]}, {A: ["4"]}])
        self.assertEqual(r["first_correct_edit_turn"], 3)
        self.assertEqual(r["reads_before_correct"], 1)  # Read 도구


GROUPS = {"g1": [f"{A}:2"], "g2": [f"{A}:8"]}


class MultiGroup(Base):
    def test_all_groups_turn_is_last_group_reached(self):
        r = self.run_fixture("groups", {A: {2, 8}}, {A: lines_with(l2="L2", l8="L8")}, groups=GROUPS)
        self.assertFalse(r["unknown"], r["unknown_reasons"])
        self.assertEqual(r["group_turns"], {"g1": 1, "g2": 3})
        self.assertEqual(r["all_fix_touched_turn"], 3)
        self.assertEqual(r["primary"], 3)
        self.assertEqual(r["first_correct_edit_turn"], 1)

    def test_missing_group(self):
        r = self.run_fixture("groups", {A: {2}}, {A: lines_with(l2="L2", l8="L8")}, groups=GROUPS)
        self.assertEqual(r["groups_missing"], ["g2"])
        self.assertIsNone(r["all_fix_touched_turn"])
        r = self.run_fixture("groups", {A: {2}}, {A: lines_with(l2="L2", l8="L8")}, success=False, groups=GROUPS)
        self.assertEqual(r["all_fix_touched_turn"], "+inf")


class SameContent(Base):
    def test_no_op_writes_and_new_file_are_not_edits(self):
        # 일치 없는 sed -i · 같은 내용 Write · 실행되지 않은 Edit(tool_use_error) · 새 파일 생성 — 모두 편집 사건 아님.
        r = self.run_fixture("same-content", FIX5, {"notes.txt": "hi\n"})
        self.assertFalse(r["unknown"], r["unknown_reasons"])
        self.assertEqual(r["edits"], [])
        self.assertIsNone(r["first_edit_turn"])


class Unknown(Base):
    def test_replay_mismatch_is_unknown(self):
        final = "h1\nh2\nh3\n" + lines_with(l5="L5")
        r = self.run_fixture("shift", FIX5, {A: final.replace("L5", "X5")})  # 기록 diff 와 다른 최종 트리
        self.assertTrue(r["unknown"])
        self.assertIn("final-tree-mismatch", r["unknown_reasons"])
        self.assertIsNone(r["first_correct_edit_turn"])
        self.assertIsNone(r["primary"])

    def test_script_file_is_unreplayable(self):
        r = self.run_fixture("unreplayable", FIX5, {"fix.py": "import re\n", A: lines_with(l5="L5")})
        self.assertTrue(r["unknown"])
        self.assertTrue(any("python-script" in x for x in r["unknown_reasons"]), r["unknown_reasons"])


class PatchRanges(unittest.TestCase):
    def test_context_lines_excluded_and_replace_block(self):
        p = """--- a/app/B.kt
+++ b/app/B.kt
@@ -10,7 +10,8 @@
 c10
 c11
 c12
-old13
+new13a
+new13b
 c14
 c15
 c16
@@ -40,3 +41,2 @@
 c40
-old41
 c42
"""
        self.assertEqual(fce.patch_ranges(p), {"app/B.kt": {13, 41}})

    def test_u0_pure_insertion_and_new_file(self):
        p = """--- a/app/B.kt
+++ b/app/B.kt
@@ -7,0 +8,2 @@
+x
+y
--- /dev/null
+++ b/app/New.kt
@@ -0,0 +1,1 @@
+n
"""
        self.assertEqual(fce.patch_ranges(p), {"app/B.kt": {7, 8}})

    def test_deleted_line_starting_with_dashes_is_body(self):
        p = """--- a/app/B.kt
+++ b/app/B.kt
@@ -1,2 +1,1 @@
--- comment
 keep
"""
        self.assertEqual(fce.patch_ranges(p), {"app/B.kt": {1}})


if __name__ == "__main__":
    unittest.main()
