"""ADR-0009 분석 도구: Newcombe 구간(ADR §6 예시값), 칸 비·결정 표 행·막힘·비용 검사 제외."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import del_analyze as da  # noqa: E402


def run(cost, success=True, check="ok"):
    return {"cost_usd": cost, "success": success, "cost_check": check}


def cells(a_cost, b_cost, a_succ=True, b_succ=True, n_cells=14):
    out = {}
    for i in range(n_cells):
        k = (f"D{i}" if i < 6 else f"S{i}", "god")
        out[(*k, "A")] = [run(a_cost, a_succ) for _ in range(3)]
        out[(*k, "B")] = [run(b_cost, b_succ) for _ in range(3)]
    return out


class Newcombe(unittest.TestCase):
    def test_adr_examples(self):
        for (k1, n1, k2, n2), (lo, hi) in {
            (42, 42, 42, 42): (-0.084, 0.084),
            (40, 42, 42, 42): (-0.158, 0.043),
            (18, 18, 18, 18): (-0.176, 0.176),
        }.items():
            got = da.newcombe(k1, n1, k2, n2)
            self.assertAlmostEqual(got[0], lo, places=3)
            self.assertAlmostEqual(got[1], hi, places=3)


class Compare(unittest.TestCase):
    def test_cheaper_and_noninferior_is_first_row(self):
        c = da.compare(cells(0.5, 1.0), "A", "B", boot=200)
        self.assertAlmostEqual(c["R"]["value"], 0.5)
        self.assertEqual(c["R"]["ci"], (0.5, 0.5))
        self.assertEqual(c["common_cells"], 14)
        self.assertEqual(c["success"]["verdict"], "비열등")
        self.assertEqual(c["row_no"], 1)

    def test_costlier_is_third_row(self):
        self.assertEqual(da.compare(cells(1.5, 1.0), "A", "B", boot=200)["row_no"], 3)

    def test_all_fail_is_inferior_and_blocked(self):
        c = da.compare(cells(0.5, 1.0, a_succ=False), "A", "B", boot=200)
        self.assertEqual(c["success"]["verdict"], "열등")
        self.assertEqual(len(c["blocked"]), 14)
        self.assertEqual(c["row_no"], 4)

    def test_middle_is_uncertain(self):
        self.assertTrue(da.compare(cells(0.9, 1.0), "A", "B", boot=200)["row_no"] == 0)

    def test_cost_check_mismatch_excluded_and_too_few_cells(self):
        c = cells(0.5, 1.0)
        for i in range(3):  # 3칸에서 A 비용 값을 1개만 남긴다 → 공통 칸 11
            k = (f"D{i}", "god", "A")
            c[k] = [run(0.5), run(9.9, check="mismatch"), run(9.9, check="missing")]
        r = da.compare(c, "A", "B", boot=50)
        self.assertEqual(r["common_cells"], 11)
        self.assertTrue(r["row"].startswith("판정 불가"))
        self.assertIsNone(r["R"]["value"])  # 12칸 미만이면 R도 계산하지 않는다(§7.4)


class ReviewGaps(unittest.TestCase):
    def test_bootstrap_resamples_each_arm_independently_per_cell(self):
        # 칸 하나만 비용이 다르다: A=[1,2,3], B=[1,1,1]. 그 칸의 재표본 median은 {1,2,3} 중 하나 → R = x^(1/14).
        c = cells(1.0, 1.0)
        c[("D0", "god", "A")] = [run(1.0), run(2.0), run(3.0)]
        r = da.compare(c, "A", "B", boot=400)
        self.assertAlmostEqual(r["R"]["value"], 2.0 ** (1 / 14))
        lo, hi = r["R"]["ci"]
        self.assertEqual((round(lo, 9), round(hi, 9)), (1.0, round(3.0 ** (1 / 14), 9)))
        # 두 방식을 짝지어(같은 인덱스로) 뽑으면 A=B=[1,2,3]인 칸의 비는 늘 1 → 구간 (1, 1). 독립 추출이면 넓어진다.
        c2 = cells(1.0, 1.0)
        c2[("D0", "god", "A")] = [run(1.0), run(2.0), run(3.0)]
        c2[("D0", "god", "B")] = [run(1.0), run(2.0), run(3.0)]
        lo2, hi2 = da.compare(c2, "A", "B", boot=400)["R"]["ci"]
        self.assertLess(lo2, 1.0)
        self.assertGreater(hi2, 1.0)
        # 남은 회차 수 그대로 뽑는다: 2회짜리 칸 [1, 3]의 median은 {1, 2, 3} → 상한 3^(1/14)
        c3 = cells(1.0, 1.0)
        c3[("D0", "god", "A")] = [run(1.0), run(3.0)]
        self.assertAlmostEqual(da.compare(c3, "A", "B", boot=400)["R"]["ci"][1], 3.0 ** (1 / 14))
        # 같은 시드면 같은 구간(재현성)
        self.assertEqual(da.compare(c, "A", "B", boot=400)["R"]["ci"], r["R"]["ci"])

    def test_cost_mismatch_counts_in_success_denominator(self):
        c = cells(0.5, 1.0)
        c[("D0", "god", "A")] = [run(0.5), run(0.5), run(9.9, success=False, check="mismatch")]
        r = da.compare(c, "A", "B", boot=50)
        self.assertEqual(r["success"]["A"], [41, 42])
        self.assertEqual(r["cost_excluded"], {"A": 1, "B": 0})
        self.assertAlmostEqual(r["R"]["value"], 0.5)

    def test_cost_loss_row_precedes_inferior_but_success_loss_kept(self):
        r = da.compare(cells(1.5, 1.0, a_succ=False), "A", "B", boot=50)
        self.assertEqual(r["row_no"], 3)
        self.assertTrue(r["success_loss"])

    def test_r_d_only_is_not_a_decision_row(self):
        c = cells(1.0, 1.0)
        for i in range(6):  # D 6칸 0.5배·S 8칸 1.2배 → R_D 0.5지만 R ≈ 0.88 > 0.8
            c[(f"D{i}", "god", "A")] = [run(0.5) for _ in range(3)]
        for i in range(6, 14):
            c[(f"S{i}", "god", "A")] = [run(1.2) for _ in range(3)]
        r = da.compare(c, "A", "B", boot=50)
        self.assertAlmostEqual(r["R_D"]["value"], 0.5)
        self.assertGreater(r["R"]["value"], 0.8)
        self.assertEqual(r["row_no"], 0)

    def test_choose_smaller_r_when_both_first_row(self):
        c = {}
        for k, v in cells(0.4, 1.0).items():
            c[(k[0], k[1], "H" if k[2] == "A" else "O")] = v
        for k, v in cells(0.7, 1.0).items():
            if k[2] == "A":
                c[(k[0], k[1], "S")] = v
        comps = {"h": da.compare(c, "H", "O", boot=50), "s": da.compare(c, "S", "O", boot=50)}
        self.assertEqual((comps["h"]["row_no"], comps["s"]["row_no"]), (1, 1))
        self.assertEqual(da.choose_model(comps), "H")

    def test_empty_and_few_d_cells_are_undecidable_not_crash(self):
        self.assertTrue(da.compare({}, "A", "B")["row"].startswith("판정 불가"))
        self.assertEqual(da.aux({})["solo"]["runs"], 0)
        c = cells(0.5, 1.0)
        for i in range(2):  # D 칸 2개 비용 값을 1개로 → D 공통 칸 4 < 5
            c[(f"D{i}", "god", "A")] = [run(0.5)]
        r = da.compare(c, "A", "B", boot=50)
        self.assertIsNone(r["R_D"]["value"])
        self.assertEqual(r["row_no"], 1)  # R은 12칸으로 계산 가능


if __name__ == "__main__":
    unittest.main()
