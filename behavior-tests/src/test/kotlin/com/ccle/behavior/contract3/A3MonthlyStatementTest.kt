package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.HOUR
import com.ccle.behavior.contract2.Kst
import com.ccle.behavior.contract2.S2
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
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-0007 §6.4 monthly-statement(닫힌 달 판정, 대상 = period_start ∈ M인 정산이 있는 판매자, 각 열의 식, 재실행·격리) · GET /me/monthly-statements.
 *
 * 달 M의 첫 월요일 W1 · 마지막 월요일 L. 판매자 X: W1 주에 SHOES 120,000 확정(INCLUDED, 수수료 7,200)과 APPAREL 45,000 확정 뒤 분쟁(매주 HELD),
 * W1 + 7일 주에 패널티 3,000 → 그 뒤 매주 CARRIED(−3,000 이월). Y: W1 − 7일 주(period_start가 M 전 달)만. Z: L 주 TECH 30,000 1건.
 */
@Contract3Test
class A3MonthlyStatementTest {

    private fun statements(seller: Long): List<Row3> = Db3.rows("monthly_statements", "seller_id = ?", seller, orderBy = "month")

    private data class Exp(
        val count: Long, val gross: Long, val fee: Long, val penalty: Long, val payout: Long,
        val carried: Long, val held: Long, val lastCarryOut: Long,
    )

    private fun assertStatement(row: Row3, month: LocalDate, e: Exp, at: Instant) {
        assertEquals(month, row.date("month"), "month: $row")
        assertEquals(e.count, row.long("settlement_count"), "settlement_count: $row")
        assertEquals(e.gross, row.long("gross"), "gross: $row")
        assertEquals(e.fee, row.long("fee_total"), "fee_total: $row")
        assertEquals(e.penalty, row.long("penalty_total"), "penalty_total: $row")
        assertEquals(e.payout, row.long("payout_total"), "payout_total: $row")
        assertEquals(e.carried, row.long("carried_count"), "carried_count: $row")
        assertEquals(e.held, row.long("held_count"), "held_count(HELD 행 수 — 주마다): $row")
        assertEquals(e.lastCarryOut, row.long("last_carry_out"), "last_carry_out: $row")
        assertEquals(at, row.instant("created_at"), "created_at = 현재: $row")
    }

    @Test
    fun `§6_4 전 과정 - 닫히기 전 processed 0, 닫힌 뒤(마지막 마감 = L + 7일) 판매자별 1행, M 전 주만 있는 판매자는 대상 아님, 격리·재실행`() {
        val m = Kst.nextMonthStart(Flow.now().plus(days(7)))
        val w1 = SA.firstMonday(m)
        val l = SA.lastMonday(m)
        val mondays = SA.mondaysIn(m)
        val x = Db.createUser()
        val y = Db.createUser()
        val z = Db.createUser()
        assertTrue(y < z)

        Flow.moveTo(Kst.at(w1.minusDays(7)))
        SA.confirmedSale(y, 30_000, "TECH")
        SA.confirmedSale(x, 50_000, "BAGS")                         // X도 M 전 달(W1 − 7일 주)에 정산 1건 — 순서 픽스처용
        Flow.moveTo(Kst.at(w1))
        SA.confirmedSale(x, 120_000, "SHOES")
        val held = SA.confirmedSale(x, 45_000, "APPAREL")
        S2.openDispute(held.item, held.buyer)
        Flow.moveTo(Kst.at(w1.plusDays(7)))
        SA.sellerFault(x, 30_000)
        Flow.moveTo(Kst.at(l))
        SA.confirmedSale(z, 30_000, "TECH")

        val closeAt = Flow.moveTo(Kst.at(l.plusDays(7)))
        val notClosed = S2.runBatch("monthly-statement")
        assertEquals(0L, notClosed.long("processed"), "닫히지 않은 달 → 대상 0건: $notClosed")
        assertEquals(0L, notClosed.failed())
        for (s in listOf(x, y, z)) assertTrue(statements(s).isEmpty())

        S2.runBatch("weekly-settlement")
        assertEquals(closeAt, Db.lastSettlementCutoff(), "준비: 마지막 마감 = L + 7일")
        assertEquals(listOf(w1.minusDays(7)) + mondays, Db2.settlementsOfSeller(x).map { it.periodStart }, "준비: X는 M 전 주 1건 + M의 매주 정산")
        assertEquals(listOf(w1.minusDays(7)), Db2.settlementsOfSeller(y).map { it.periodStart })

        FailTrigger.on("monthly_statements", "INSERT", "NEW.seller_id = $z") {
            val r = S2.runBatch("monthly-statement")
            assertTrue(r.failed() >= 1, "Z 실패를 failed에 센다: $r")
            assertTrue(r.long("processed") >= 1, "X는 커밋된다: $r")
        }
        val n = mondays.size.toLong()
        val xExp = Exp(n, 120_000, 7_200, 3_000, 112_800, n - 1, n, -3_000)
        assertStatement(statements(x).single(), m, xExp, closeAt)
        assertTrue(statements(z).isEmpty())
        assertTrue(statements(y).isEmpty(), "period_start가 M에 속하는 정산이 없으면 대상이 아니다")

        val again = Flow.advance(HOUR)
        S2.runBatch("monthly-statement")
        assertStatement(statements(z).single(), m, Exp(1, 30_000, 1_200, 0, 28_800, 0, 0, 0), again)
        assertStatement(statements(x).single(), m, xExp, closeAt)              // 재실행은 다시 쓰지 않는다
        assertTrue(statements(y).isEmpty())

        // 순서 픽스처: X의 M 전 달 정산서(그 달 실제 정산 1건의 합 그대로 — verify a3_statement_sums를 지킨다)
        val prev = Db2.settlementsOfSeller(x).first()
        val prevMonth = prev.periodStart.withDayOfMonth(1)
        assertEquals(m.minusMonths(1), prevMonth, "준비: W1 − 7일 주는 M 전 달")
        Db3.insert("monthly_statements", mapOf(
            "seller_id" to x, "month" to prevMonth, "settlement_count" to 1, "gross" to prev.gross, "fee_total" to prev.feeTotal,
            "penalty_total" to prev.penaltyTotal, "payout_total" to prev.payout, "carried_count" to (if (prev.status == "CARRIED") 1 else 0),
            "held_count" to Db2.settlementItems(prev.id).count { it.status == "HELD" }, "last_carry_out" to prev.carryOut, "created_at" to closeAt,
        ))

        // GET /me/monthly-statements — month 오름차순(이른 달이 먼저)
        val r = SA.monthlyStatements(x).assertStatus(200)
        assertEquals(2, r.json.size(), "목록: $r")
        SA.assertDate(prevMonth, r.json[0], "month")
        assertEquals(prev.gross, r.json[0].longAt("gross"))
        val j = r.json[1]
        SA.assertDate(m, j, "month")
        assertEquals(n, j.longAt("settlementCount"))
        assertEquals(120_000L, j.longAt("gross"))
        assertEquals(7_200L, j.longAt("feeTotal"))
        assertEquals(3_000L, j.longAt("penaltyTotal"))
        assertEquals(112_800L, j.longAt("payoutTotal"))
        assertEquals(n - 1, j.longAt("carriedCount"))
        assertEquals(n, j.longAt("heldCount"))
        assertEquals(-3_000L, j.longAt("lastCarryOut"))
    }

    @Test
    fun `§6_4 GET me monthly-statements - 행 없으면 빈 배열, 401`() {
        val r = SA.monthlyStatements(Db.createUser()).assertStatus(200)
        assertTrue(r.json.isArray && r.json.size() == 0, "빈 배열: $r")
        SA.monthlyStatements(null).assertError(401, "UNKNOWN_USER")
        SA.monthlyStatements("abc").assertError(401, "UNKNOWN_USER")
        SA.monthlyStatements(Db.missingId("users")).assertError(401, "UNKNOWN_USER")
    }
}
