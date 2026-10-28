package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.HOUR
import com.ccle.behavior.contract2.Kst
import com.ccle.behavior.contract2.S2
import com.ccle.behavior.contract2.days
import com.ccle.behavior.contract3.SA.instantOrNullAt
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-0007 §5.3 rebate-settlement(닫힌 달 판정 = §6.4, 그 달 등급의 율, fee_base·amount 식, PAID/ZERO, 재실행·격리) · §5.4 GET /me/fee-rebates.
 *
 * 시나리오(달 M = 테스트 시각 뒤 첫 달):
 * M 전 주에 판매·정산 → M 1일에 seller-grade-recalc(그 달 등급 행) → M 안의 첫 월요일 W1·마지막 월요일 L 주에 판매 →
 * L + 7일(다음 달)에 정산 전 실행은 대상 0건(닫히지 않음) → weekly-settlement(마지막 마감 = L + 7일 정각) → 실행.
 * 수수료율: SHOES 6%·BAGS 10%·TECH 4%. TECH 30,750 → 수수료 1,230.
 */
@Contract3Test
class A2RebateTest {

    private fun rebates(seller: Long): List<Row3> = Db3.rows("fee_rebates", "seller_id = ?", seller, orderBy = "month")

    private fun assertRebate(
        seller: Long, month: LocalDate, grade: String, feeBase: Long, rate: Long, amount: Long, at: Instant,
    ): Row3 {
        val r = rebates(seller).single()
        assertEquals(month, r.date("month"), "month = M(1일): $r")
        assertEquals(grade, r.text("grade"), "grade = effective_from M 행: $r")
        assertEquals(feeBase, r.long("fee_base"), "fee_base: $r")
        assertEquals(rate, r.long("rate_permille"), "rate_permille: $r")
        assertEquals(amount, r.long("amount"), "amount = floor(max(fee_base, 0) × rate / 1000): $r")
        if (amount > 0) {
            assertEquals("PAID", r.text("status"), "status: $r")
            assertEquals(at, r.instant("paid_at"), "paid_at = 현재: $r")
        } else {
            assertEquals("ZERO", r.text("status"), "status: $r")
            assertNull(r.instantOrNull("paid_at"), "ZERO paid_at NULL: $r")
        }
        assertEquals(at, r.instant("created_at"), "created_at = 현재: $r")
        return r
    }

    @Test
    fun `§5_3 전 과정 - 닫히기 전 대상 0건, 닫힌 뒤 등급별 율(0·50·100·150‰)·floor·음수 fee_base는 ZERO, 그 달 등급 행이 없으면 대상 아님, 격리·재실행`() {
        val plus = Db.createUser()
        val pro = Db.createUser()
        val master = Db.createUser()
        val standard = Db.createUser()
        val claw = Db.createUser()
        val idle = Db.createUser()
        SA.confirmedSale(plus, 1_000_000)
        SA.confirmedSale(pro, 3_000_000)
        SA.confirmedSale(master, 8_000_000)
        SA.confirmedSale(standard, 999_999)
        val clawSale = SA.confirmedSale(claw, 1_000_000)
        SA.confirmedSale(idle, 1_000_000)
        Flow.moveTo(Kst.cutoffOf(Flow.now()))
        S2.runBatch("weekly-settlement")                                       // M 전 주: INCLUDED
        val clawDispute = S2.openDispute(clawSale.item, clawSale.buyer)        // 정산 뒤 분쟁(확정 + 14일 안)

        val m = Kst.nextMonthStart(Flow.now())
        Flow.moveTo(Kst.at(m))
        S2.runBatch("seller-grade-recalc")
        for ((s, g) in listOf(plus to "PLUS", pro to "PRO", master to "MASTER", standard to "STANDARD", claw to "PLUS", idle to "PLUS")) {
            val row = Db3.rows("seller_grades", "seller_id = ? AND effective_from = ?", s, m).single()
            assertEquals(g, row.text("grade"), "준비: M 등급 $row")
        }

        val w1 = SA.firstMonday(m)
        val l = SA.lastMonday(m)
        Flow.moveTo(Kst.at(w1))
        SA.confirmedSale(master, 30_750, "TECH")                               // W1 주 수수료 1,230
        val late = Db.createUser()
        SA.confirmedSale(late, 30_000, "TECH")                                 // M 등급 행 없는 판매자
        S2.resolved(clawDispute, true)                                         // W1 주 CLAWBACK −60,000
        Flow.moveTo(Kst.at(l))
        SA.confirmedSale(plus, 30_750, "TECH")                                 // 마지막 주(L) 수수료 1,230
        SA.confirmedSale(pro, 30_750, "TECH")
        SA.confirmedSale(standard, 30_750, "TECH")
        SA.confirmedSale(master, 50_000, "BAGS")                               // L 주 수수료 5,000

        // L + 7일: 다음 달이지만 마지막 주 정산 전 → 닫히지 않음 → 대상 0건
        val closeAt = Flow.moveTo(Kst.at(l.plusDays(7)))
        assertEquals(m.plusMonths(1), Kst.monthStart(closeAt), "준비: L + 7일은 다음 달")
        val notClosed = S2.runBatch("rebate-settlement")
        assertEquals(0L, notClosed.long("processed"), "닫히지 않은 달 → 대상 0건: $notClosed")
        assertEquals(0L, notClosed.failed())
        for (s in listOf(plus, pro, master, standard, claw, idle)) assertTrue(rebates(s).isEmpty())

        S2.runBatch("weekly-settlement")
        assertEquals(closeAt, Db.lastSettlementCutoff(), "준비: 마지막 마감 = L + 7일")
        assertEquals(l, Db2.settlementsOfSeller(plus).last().periodStart, "준비: 마지막 주 정산")
        // 다음 달 등급 행을 먼저 만들어도(제한 → STANDARD) 리베이트는 M 등급을 쓴다
        Db.setRestrictions(plus, null, closeAt.plus(days(1)))
        S2.runBatch("seller-grade-recalc")
        Db.setRestrictions(plus, null, null)
        assertEquals("STANDARD", Db3.rows("seller_grades", "seller_id = ? AND effective_from = ?", plus, m.plusMonths(1)).single().text("grade"))

        // 격리: standard의 INSERT만 실패
        FailTrigger.on("fee_rebates", "INSERT", "NEW.seller_id = $standard") {
            val r = S2.runBatch("rebate-settlement")
            assertTrue(r.failed() >= 1, "실패를 failed에 센다: $r")
            assertTrue(r.long("processed") >= 1, "나머지는 커밋된다: $r")
        }
        assertTrue(rebates(standard).isEmpty())
        assertRebate(plus, m, "PLUS", 1_230, 50, 61, closeAt)                  // floor(61.5)
        assertRebate(pro, m, "PRO", 1_230, 100, 123, closeAt)
        assertRebate(master, m, "MASTER", 6_230, 150, 934, closeAt)            // 두 주 합, floor(934.5)
        assertRebate(claw, m, "PLUS", -60_000, 50, 0, closeAt)                 // CLAWBACK 달 → 음수, amount 0
        assertRebate(idle, m, "PLUS", 0, 50, 0, closeAt)                       // 그 달 정산 없음
        assertTrue(rebates(late).isEmpty(), "effective_from = M 등급 행이 없으면 대상이 아니다")

        val again = Flow.advance(HOUR)
        S2.runBatch("rebate-settlement")
        assertRebate(standard, m, "STANDARD", 1_230, 0, 0, again)
        assertRebate(plus, m, "PLUS", 1_230, 50, 61, closeAt)                  // 재실행은 다시 쓰지 않는다
        assertRebate(claw, m, "PLUS", -60_000, 50, 0, closeAt)
        assertTrue(rebates(late).isEmpty())

        // 순서 픽스처: plus의 M 전 달 ZERO 리베이트(STANDARD·0‰ — §3 CHECK·verify a2_rebate_rule의 저장 열 규칙을 지킨다)
        Db3.insert("fee_rebates", mapOf(
            "seller_id" to plus, "month" to m.minusMonths(1), "grade" to "STANDARD", "fee_base" to 0L, "rate_permille" to 0,
            "amount" to 0L, "status" to "ZERO", "paid_at" to null, "created_at" to Kst.at(m),
        ))
        val ordered = SA.feeRebates(plus).assertStatus(200)
        assertEquals(listOf(m.minusMonths(1).toString(), m.toString()), ordered.json.map { it.textAt("month") }, "month 오름차순: $ordered")

        // §5.4 조회
        for (s in listOf(plus, standard)) {
            val r = SA.feeRebates(s).assertStatus(200)
            val rows = rebates(s)
            assertEquals(rows.size, r.json.size(), "목록: $r")
            rows.forEachIndexed { i, row ->
                val j = r.json[i]
                SA.assertDate(row.date("month"), j, "month")
                assertEquals(row.text("grade"), j.textAt("grade"))
                assertEquals(row.long("fee_base"), j.longAt("feeBase"))
                assertEquals(row.long("rate_permille"), j.longAt("ratePermille"))
                assertEquals(row.long("amount"), j.longAt("amount"))
                assertEquals(row.text("status"), j.textAt("status"))
                assertEquals(row.instantOrNull("paid_at"), j.instantOrNullAt("paidAt"))
            }
        }
        assertTrue(SA.feeRebates(standard).json[0].get("paidAt").isNull)
    }

    @Test
    fun `§5_4 GET me fee-rebates - 행 없으면 빈 배열, 401`() {
        val r = SA.feeRebates(Db.createUser()).assertStatus(200)
        assertTrue(r.json.isArray && r.json.size() == 0, "빈 배열: $r")
        SA.feeRebates(null).assertError(401, "UNKNOWN_USER")
        SA.feeRebates("abc").assertError(401, "UNKNOWN_USER")
        SA.feeRebates(Db.missingId("users")).assertError(401, "UNKNOWN_USER")
    }
}
