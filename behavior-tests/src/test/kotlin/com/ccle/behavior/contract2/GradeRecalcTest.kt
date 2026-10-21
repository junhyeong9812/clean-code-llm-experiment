package com.ccle.behavior.contract2

import com.ccle.behavior.support.Contract2Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db2
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.GradeHistoryRow
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-0004 §8.5 grade-recalc: 기준일 D = 현재가 속한 달 1일(KST), 기준 금액 = CONFIRMED·DISPUTED 항목 중
 * confirmed_at ∈ [D − 6개월, D)의 체결가 합, 임계값, 같은 달 재실행, 같으면 쓰지 않음. 모두 D 00:00(KST)에 돌린다(§8.1).
 * 사용자는 이력 없는 BRONZE로 새로 만들고, 포인트 묶음(확정 거래를 만든다)은 쓰지 않는다.
 */
@Contract2Test
class GradeRecalcTest {

    /** 새 구매자가 그 가격 1건을 사서 CONFIRMED까지(현재 시각에 확정). 반환: 구매. */
    private fun confirmedPurchase(buyer: Long, price: Long): Purchase {
        val p = S2.purchase(listOf(Line(price)), buyer)
        S2.toConfirmed(p.item, p.seller, p.buyer)
        return p
    }

    private fun history(user: Long): List<GradeHistoryRow> = Db2.gradeHistory(user)

    private fun assertOneRow(user: Long, grade: String, d: LocalDate, basis: Long) {
        val h = history(user).single()
        assertEquals(grade, h.grade, "등급: $h")
        assertEquals(d, h.effectiveFrom, "effective_from = D: $h")
        assertEquals(basis, h.basisAmount, "basis_amount: $h")
        assertEquals(grade, Db.user(user).grade, "users.grade 갱신")
    }

    @Test
    fun `임계값 - SILVER 500,000(경계)·GOLD 2,000,000·VIP 5,000,000, 499,999는 BRONZE라 행 없음, 재산정 뒤 결제는 새 등급 적립률`() {
        val silver = Db.createUser(); val ps = confirmedPurchase(silver, 500_000)
        val bronze = Db.createUser(); val pb = confirmedPurchase(bronze, 499_999)
        val gold = Db.createUser(); val pg = confirmedPurchase(gold, 2_000_000)
        val vip = Db.createUser(); val pv = confirmedPurchase(vip, 5_000_000)

        val d = Kst.nextMonthStart(Flow.now())
        Flow.moveTo(Kst.at(d))
        S2.runBatch("grade-recalc")
        assertOneRow(silver, "SILVER", d, 500_000)
        assertOneRow(gold, "GOLD", d, 2_000_000)
        assertOneRow(vip, "VIP", d, 5_000_000)
        assertTrue(history(bronze).isEmpty(), "등급이 users.grade와 같으면 아무것도 쓰지 않는다")
        assertEquals("BRONZE", Db.user(bronze).grade)
        // 판매 금액은 구매자 기준 금액이 아니다: 판매자(5,000,000 판매자 포함)는 이력 없음·BRONZE
        for (seller in listOf(ps, pb, pg, pv).map { it.seller }) {
            assertTrue(history(seller).isEmpty(), "판매자 $seller 이력 없음")
            assertEquals("BRONZE", Db.user(seller).grade, "판매자 $seller")
        }

        // §8.1: effective_from = D ≤ 주문 날짜 → SILVER 1.5%
        val p = S2.purchase(listOf(Line(100_000)), silver)
        assertEquals(1_500L, Db.earnOfItem(p.item).single().amount)
    }

    @Test
    fun `기준 금액 - DISPUTED는 포함, 분쟁 인정(REFUNDED_AFTER_CONFIRM)·미확정(DELIVERED)은 빠진다`() {
        val disputed = Db.createUser()
        val pd = confirmedPurchase(disputed, 500_000)
        S2.openDispute(pd.item, disputed)

        val refunded = Db.createUser()
        val pr = confirmedPurchase(refunded, 500_000)
        S2.resolved(S2.openDispute(pr.item, refunded), true)

        val delivered = Db.createUser()
        val pv = S2.purchase(listOf(Line(500_000)), delivered)
        S2.toDelivered(pv.item, pv.seller)

        val d = Kst.nextMonthStart(Flow.now())
        Flow.moveTo(Kst.at(d))
        S2.runBatch("grade-recalc")
        assertOneRow(disputed, "SILVER", d, 500_000)
        assertTrue(history(refunded).isEmpty(), "분쟁 인정된 항목은 확정 월에서 빠진다")
        assertTrue(history(delivered).isEmpty(), "확정되지 않은 항목은 세지 않는다")
    }

    @Test
    fun `기준 기간 - D − 6개월 정각 확정은 포함, D 정각 확정은 제외, 등급이 같으면 쓰지 않고, 기간을 벗어나면 BRONZE 행`() {
        val user = Db.createUser()
        val big = S2.purchase(listOf(Line(500_000)), user)
        val small = S2.purchase(listOf(Line(100_000)), user)
        S2.toDelivering(big.item, big.seller)
        S2.toDelivering(small.item, small.seller)

        val m0 = Kst.nextMonthStart(Flow.now())
        Flow.moveTo(Kst.at(m0))
        S2.ready("배송 완료", S2.deliver(big.item))
        S2.ready("구매 확정", S2.confirm(big.item, user))          // confirmed_at = m0 00:00

        val d1 = m0.plusMonths(1)
        Flow.moveTo(Kst.at(d1))
        S2.ready("배송 완료", S2.deliver(small.item))
        S2.ready("구매 확정", S2.confirm(small.item, user))        // confirmed_at = d1 00:00 → d1 기준에서는 제외
        S2.runBatch("grade-recalc")
        assertOneRow(user, "SILVER", d1, 500_000)

        val d6 = m0.plusMonths(6)                                  // [m0, d6): 600,000 → SILVER(같음) → 쓰지 않음
        Flow.moveTo(Kst.at(d6))
        S2.runBatch("grade-recalc")
        assertEquals(1, history(user).size, "등급이 같으면 이력을 쓰지 않는다(하한 포함)")
        assertEquals("SILVER", Db.user(user).grade)

        val d7 = m0.plusMonths(7)                                  // [d1, d7): 100,000 → BRONZE
        Flow.moveTo(Kst.at(d7))
        S2.runBatch("grade-recalc")
        val h = history(user)
        assertEquals(2, h.size)
        assertEquals("BRONZE", h.last().grade)
        assertEquals(d7, h.last().effectiveFrom)
        assertEquals(100_000L, h.last().basisAmount)
        assertEquals("BRONZE", Db.user(user).grade)
    }

    @Test
    fun `같은 달 재실행 - 그 달 이력이 이미 있으면 기준 금액이 바뀌어도 건너뛴다`() {
        val d = Kst.nextMonthStart(Flow.now().plus(days(2)))
        Flow.moveTo(Kst.at(d).minus(DAY))
        val user = Db.createUser()
        val p = confirmedPurchase(user, 500_000)

        Flow.moveTo(Kst.at(d))
        S2.runBatch("grade-recalc")
        assertOneRow(user, "SILVER", d, 500_000)

        Flow.advance(HOUR)
        S2.resolved(S2.openDispute(p.item, user), true)           // 기준 금액 0이 됨
        S2.runBatch("grade-recalc")
        assertOneRow(user, "SILVER", d, 500_000)
    }

    @Test
    fun `같은 달 재실행 - 첫 실행에서 그대로였던 사용자는 소급 확정으로 기준 금액이 바뀌면 그때 이력이 생긴다`() {
        val d = Kst.nextMonthStart(Flow.now().plus(days(9)))
        Flow.moveTo(Kst.at(d).minus(days(8)))
        val user = Db.createUser()
        val p = S2.purchase(listOf(Line(500_000)), user)
        S2.toDelivered(p.item, p.seller)                           // delivered_at = D − 8일

        Flow.moveTo(Kst.at(d))
        S2.runBatch("grade-recalc")
        assertTrue(history(user).isEmpty())

        Flow.advance(HOUR)
        val row = assertItemOk(S2.confirm(p.item, user), p.item, "CONFIRMED")
        assertEquals(Kst.at(d).minus(DAY), row.confirmedAt, "준비: 소급 확정 = delivered_at + 7일 = D − 1일")
        S2.runBatch("grade-recalc")
        assertOneRow(user, "SILVER", d, 500_000)
    }

    @Test
    fun `기준일 - D + 10일에 돌려도 effective_from = D, 기간은 D − 6개월부터 D 전까지(D + 5일 확정분은 빠진다)`() {
        val user = Db.createUser()
        confirmedPurchase(user, 500_000)                            // D 전 확정 → 포함
        val d = Kst.nextMonthStart(Flow.now())
        Flow.moveTo(Kst.at(d).plus(days(5)))
        confirmedPurchase(user, 2_000_000)                          // D + 5일 확정 → 제외(포함되면 GOLD 2,500,000)
        Flow.moveTo(Kst.at(d).plus(days(10)))
        S2.runBatch("grade-recalc")
        assertOneRow(user, "SILVER", d, 500_000)
    }
}
