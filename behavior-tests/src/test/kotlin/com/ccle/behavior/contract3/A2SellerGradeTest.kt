package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.DAY
import com.ccle.behavior.contract2.HOUR
import com.ccle.behavior.contract2.Kst
import com.ccle.behavior.contract2.S2
import com.ccle.behavior.contract2.SEC
import com.ccle.behavior.contract2.days
import com.ccle.behavior.support.Contract3Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db2
import com.ccle.behavior.support.Db3
import com.ccle.behavior.support.FailTrigger
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.Row3
import com.ccle.behavior.support.failed
import com.ccle.behavior.support.longAt
import com.ccle.behavior.support.textAt
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-0007 §5.1 판매자 등급 규칙 · §5.2 seller-grade-recalc · §5.4 GET /me/seller-grade.
 * 기준 금액은 주간 정산(ADR-0004 §10)의 INCLUDED 합이므로 판매 → 구매 확정 → weekly-settlement를 API로 돌려 만든다.
 * 수수료율: SHOES 6%(기본 카테고리)·APPAREL 8%·BAGS 10%·TECH 4%.
 */
@Contract3Test
class A2SellerGradeTest {

    private fun grades(seller: Long): List<Row3> = Db3.rows("seller_grades", "seller_id = ?", seller, orderBy = "effective_from")

    private fun assertGrade(row: Row3, grade: String, d: LocalDate, basis: Long, restricted: Boolean, createdAt: Instant) {
        assertEquals(grade, row.text("grade"), "grade: $row")
        assertEquals(d, row.date("effective_from"), "effective_from = D: $row")
        assertEquals(basis, row.long("basis_amount"), "basis_amount: $row")
        assertEquals(restricted, row.bool("restricted"), "restricted: $row")
        assertEquals(createdAt, row.instant("created_at"), "created_at = 현재: $row")
    }

    /** 판매자들의 확정 판매를 지금 주 마감에 정산한다(INCLUDED). 반환: 정산 실행 시각. */
    private fun settleNow(): Instant {
        val c = Flow.moveTo(Kst.cutoffOf(Flow.now()))
        S2.runBatch("weekly-settlement")
        return c
    }

    @Test
    fun `§5_1 문턱 - PLUS 1,000,000 · PRO 3,000,000 · MASTER 8,000,000(경계 포함), 999,999는 STANDARD, created_at = 현재`() {
        val cases = listOf(999_999L to "STANDARD", 1_000_000L to "PLUS", 2_999_999L to "PLUS", 3_000_000L to "PRO", 7_999_999L to "PRO", 8_000_000L to "MASTER")
        val sellers = cases.map { (price, _) -> Db.createUser().also { SA.confirmedSale(it, price) } }
        settleNow()
        sellers.forEachIndexed { i, s -> assertEquals(cases[i].first, Db2.settlementItems(Db2.settlementsOfSeller(s).single().id).single().amount, "준비: INCLUDED") }

        val d = Kst.nextMonthStart(Flow.now())
        val at = Flow.moveTo(Kst.at(d).plus(HOUR))
        S2.runBatch("seller-grade-recalc")
        sellers.forEachIndexed { i, s -> assertGrade(grades(s).single(), cases[i].second, d, cases[i].first, false, at) }
    }

    @Test
    fun `§5_1 기준 금액 - INCLUDED만 합한다(HELD 0·CLAWBACK 음수는 빼지 않는다), 정산이 없는 사용자는 대상 아님`() {
        val seller = Db.createUser()
        val inc = SA.confirmedSale(seller, 1_000_000)
        val held = SA.confirmedSale(seller, 900_000)
        S2.openDispute(held.item, held.buyer)                       // 확정 뒤 분쟁 → HELD
        val buyerOnly = inc.buyer
        settleNow()
        assertEquals(listOf("INCLUDED", "HELD"), Db2.settlementItems(Db2.settlementsOfSeller(seller).single().id).map { it.status })
        // 정산에 포함된 항목을 분쟁 인정 → 다음 주 CLAWBACK(−1,000,000)
        S2.resolved(S2.openDispute(inc.item, inc.buyer), true)
        settleNow()
        assertTrue(Db2.settlementsOfSeller(seller).flatMap { Db2.settlementItems(it.id) }.any { it.status == "CLAWBACK" }, "준비: CLAWBACK")

        val d = Kst.nextMonthStart(Flow.now())
        val at = Flow.moveTo(Kst.at(d))
        S2.runBatch("seller-grade-recalc")
        assertGrade(grades(seller).single(), "PLUS", d, 1_000_000, false, at)            // CLAWBACK을 빼면 0 → STANDARD
        assertTrue(grades(buyerOnly).isEmpty(), "settlements 행이 없는 사용자는 대상이 아니다")
    }

    @Test
    fun `§5_1 판매 제한 - sale_restricted_until gt 현재면 restricted·STANDARD(basis는 그대로), = 현재(정각)면 제한 아님`() {
        val restricted = Db.createUser()
        val edge = Db.createUser()
        SA.confirmedSale(restricted, 8_000_000)
        SA.confirmedSale(edge, 8_000_000)
        settleNow()
        val d = Kst.nextMonthStart(Flow.now())
        val at = Kst.at(d)
        Db.setRestrictions(restricted, null, at.plus(SEC))
        Db.setRestrictions(edge, null, at)
        Flow.moveTo(at)
        S2.runBatch("seller-grade-recalc")
        assertGrade(grades(restricted).single(), "STANDARD", d, 8_000_000, true, at)
        assertGrade(grades(edge).single(), "MASTER", d, 8_000_000, false, at)
    }

    @Test
    fun `§5_2 기준일 - D + 10일에 돌려도 effective_from = D, D 이후 주의 정산(period_start ≥ D)은 빠지고 그 판매자도 대상이다(basis 0)`() {
        val early = Db.createUser()
        SA.confirmedSale(early, 1_000_000)
        settleNow()
        val d = Kst.nextMonthStart(Flow.now())
        val w = SA.firstMonday(d)                                   // period_start ≥ D인 첫 주
        Flow.moveTo(Kst.at(w))
        SA.confirmedSale(early, 3_000_000)                          // D 이후 주 → 빠진다(포함되면 PRO)
        val late = Db.createUser()
        SA.confirmedSale(late, 1_000_000)
        Flow.moveTo(Kst.at(w.plusDays(7)))
        S2.runBatch("weekly-settlement")
        assertEquals(w, Db2.settlementsOfSeller(late).single().periodStart, "준비: D 이후 주 정산")

        val at = Flow.moveTo(maxOf(Kst.at(d).plus(days(10)), Flow.now()).plus(HOUR))
        assertEquals(d, Kst.monthStart(at), "준비: 아직 D의 달")
        S2.runBatch("seller-grade-recalc")
        assertGrade(grades(early).single(), "PLUS", d, 1_000_000, false, at)
        assertGrade(grades(late).single(), "STANDARD", d, 0, false, at)
    }

    @Test
    fun `§5_2 같은 달 재실행 - 그 달 행이 있으면 기준 금액이 바뀌어도 건너뛴다(첫 결과 유지), 다음 달은 같은 등급이어도 새 행`() {
        val seller = Db.createUser()
        SA.confirmedSale(seller, 1_000_000)
        settleNow()
        val d = Kst.nextMonthStart(Flow.now())
        val at = Flow.moveTo(Kst.at(d))
        S2.runBatch("seller-grade-recalc")
        assertGrade(grades(seller).single(), "PLUS", d, 1_000_000, false, at)

        // 같은 달: 판매·정산이 늘어도 재실행은 그 달 행을 다시 쓰지 않는다
        SA.confirmedSale(seller, 5_000_000)
        settleNow()
        Flow.advance(HOUR)
        S2.runBatch("seller-grade-recalc")
        assertGrade(grades(seller).single(), "PLUS", d, 1_000_000, false, at)

        val d2 = d.plusMonths(1)
        val at2 = Flow.moveTo(Kst.at(d2))
        S2.runBatch("seller-grade-recalc")
        val g = grades(seller)
        assertEquals(2, g.size)
        assertGrade(g[1], "PRO", d2, 6_000_000, false, at2)
    }

    @Test
    fun `§5_1 기간 - 기준 금액은 period_start ∈ (D − 3개월 이상, D 미만) - 3개월 뒤까지는 포함, 4개월 뒤 달에는 빠져 STANDARD(매달 행)`() {
        val seller = Db.createUser()
        SA.confirmedSale(seller, 1_000_000)
        val periodStart = Kst.weekStart(Flow.now())
        settleNow()
        val m0 = periodStart.withDayOfMonth(1)
        var last: Instant? = null
        for (k in 1L..4L) {
            val d = m0.plusMonths(k)
            if (Kst.at(d) <= Flow.now()) continue                  // 정산 실행이 이미 그 달에 들어갔으면 건너뛴다
            last = Flow.moveTo(Kst.at(d))
            S2.runBatch("seller-grade-recalc")
            val row = grades(seller).last()
            val expected = if (k <= 3) "PLUS" to 1_000_000L else "STANDARD" to 0L
            assertGrade(row, expected.first, d, expected.second, false, last)
        }
        assertTrue(last != null)
        assertEquals(m0.plusMonths(4), grades(seller).last().date("effective_from"))
    }

    @Test
    fun `§5_2·§2_6 격리 - A의 INSERT가 실패해도 B는 행, failed ≥ 1, 다시 돌리면 A 행(같은 D)`() {
        val a = Db.createUser()
        val b = Db.createUser()
        assertTrue(a < b)
        SA.confirmedSale(a, 1_000_000)
        SA.confirmedSale(b, 3_000_000)
        settleNow()
        val d = Kst.nextMonthStart(Flow.now())
        val at = Flow.moveTo(Kst.at(d))
        FailTrigger.on("seller_grades", "INSERT", "NEW.seller_id = $a") {
            val r = S2.runBatch("seller-grade-recalc")
            assertTrue(r.failed() >= 1, "A 실패를 failed에 센다: $r")
            assertTrue(r.long("processed") >= 1, "B는 커밋된다: $r")
        }
        assertTrue(grades(a).isEmpty())
        assertGrade(grades(b).single(), "PRO", d, 3_000_000, false, at)

        val later = Flow.advance(HOUR)
        S2.runBatch("seller-grade-recalc")
        assertGrade(grades(a).single(), "PLUS", d, 1_000_000, false, later)
        assertEquals(1, grades(b).size)
    }

    @Test
    fun `§5_4 GET me seller-grade - current = effective_from 최신, history 오름차순(등급 모양), 행 없으면 null·빈 목록, 401`() {
        val seller = Db.createUser()
        SA.confirmedSale(seller, 1_000_000)
        settleNow()
        val d1 = Kst.nextMonthStart(Flow.now())
        Flow.moveTo(Kst.at(d1))
        S2.runBatch("seller-grade-recalc")
        Db.setRestrictions(seller, null, Kst.at(d1.plusMonths(1)).plus(DAY))
        Flow.moveTo(Kst.at(d1.plusMonths(1)))
        S2.runBatch("seller-grade-recalc")
        Db.setRestrictions(seller, null, null)

        val r = SA.sellerGrade(seller).assertStatus(200)
        val db = grades(seller)
        assertEquals(2, db.size)
        val hist = r.json.get("history")
        assertEquals(db.size, hist.size(), "history: $r")
        db.forEachIndexed { i, g ->
            val j = hist[i]
            SA.assertDate(g.date("effective_from"), j, "effectiveFrom")
            assertEquals(g.text("grade"), j.textAt("grade"))
            assertEquals(g.long("basis_amount"), j.longAt("basisAmount"))
            assertEquals(g.bool("restricted"), j.get("restricted").booleanValue())
            assertTrue(j.get("restricted").isBoolean)
        }
        assertEquals(listOf("PLUS", "STANDARD"), hist.map { it.textAt("grade") })
        val cur = r.json.get("current")
        SA.assertDate(d1.plusMonths(1), cur, "effectiveFrom")
        assertEquals("STANDARD", cur.textAt("grade"))
        assertEquals(1_000_000L, cur.longAt("basisAmount"))
        assertTrue(cur.get("restricted").booleanValue())

        val nobody = SA.sellerGrade(Db.createUser()).assertStatus(200)
        SA.assertNullField(nobody.json, "current")
        assertEquals(0, nobody.json.get("history").size())

        SA.sellerGrade(null).assertError(401, "UNKNOWN_USER")
        SA.sellerGrade("abc").assertError(401, "UNKNOWN_USER")
        SA.sellerGrade(Db.missingId("users")).assertError(401, "UNKNOWN_USER")
    }
}
