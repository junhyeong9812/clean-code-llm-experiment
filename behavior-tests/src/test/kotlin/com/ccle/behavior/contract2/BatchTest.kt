package com.ccle.behavior.contract2

import com.ccle.behavior.support.Contract2Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db2
import com.ccle.behavior.support.Flow
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-0004 §5 배치: shipment-deadline·auto-confirm·point-expiry의 대상 조건(경계 1초 전/정각)과 결과, 재실행 멱등(§9.2).
 * grade-recalc는 GradeRecalcTest, weekly-settlement는 SettlementTest. processed의 정확한 값은 단언하지 않는다.
 */
@Contract2Test
class BatchTest {

    @Test
    fun `shipment-deadline - paid_at + 3일 1초 전 그대로, 정각에 판매자 귀책(cancelled_at = 현재, 검수 행 없음, 환불 발송 기한 초과, 패널티), 재실행 무변경`() {
        // 체결가 100,000 → 수수료 3,000, 배송비 0, 카드 103,000. 패널티 10,000
        val p = S2.purchase(100_000)
        val deadline = p.paidAt.plus(days(3))

        Flow.moveTo(deadline.minus(SEC))
        S2.runBatch("shipment-deadline")
        assertEquals("PAID", Db2.item(p.item).status)
        assertNoRefund(p, p.item)

        val now = Flow.moveTo(deadline)
        S2.runBatch("shipment-deadline")
        val row = Db2.item(p.item)
        assertEquals("CANCELLED_SELLER_FAULT", row.status)
        assertEquals(now, row.cancelledAt)
        assertNull(row.shippedAt)
        assertTrue(Db2.inspections(p.item).isEmpty(), "기한 초과 귀책은 검수 행 없음")
        assertRefund(p.item, RefundExp(card = 103_000, point = 0, buyerFee = 3_000, shipping = 0, reason = "발송 기한 초과", fault = "SELLER"), now)
        assertCardCancel(p, p.item, 103_000, now)
        val pen = Db2.penaltiesOfItem(p.item).single()
        assertEquals(p.seller, pen.sellerId)
        assertEquals(10_000L, pen.amount)
        assertEquals(now, pen.createdAt)
        assertEquals("CANCELLED", Db.order(p.orderId).status)
        assertEquals("CANCELLED", Db2.ledgerOfItem(p.item, "EARN").single().status, "적립 예정 취소")

        Flow.advance(HOUR)
        S2.runBatch("shipment-deadline")
        assertEquals(1, Db2.refunds(p.item).size)
        assertEquals(1, Db2.penaltiesOfItem(p.item).size)
        assertEquals(1, Db2.pgCancels(p.orderId).size)
        assertEquals(now, Db2.item(p.item).cancelledAt)
    }

    @Test
    fun `auto-confirm - delivered_at + 7일 1초 전 그대로, 늦게 돌려도 confirmed_at = delivered_at + 7일(소급)·포인트 확정, 재실행 무변경, 그 뒤 구매자 확정 409`() {
        val p = S2.purchase(100_000)
        S2.toDelivered(p.item, p.seller)
        val deliveredAt = Db2.item(p.item).deliveredAt!!

        Flow.moveTo(deliveredAt.plus(days(7)).minus(SEC))
        S2.runBatch("auto-confirm")
        assertEquals("DELIVERED", Db2.item(p.item).status)

        Flow.moveTo(deliveredAt.plus(days(9)))
        S2.runBatch("auto-confirm")
        val retro = deliveredAt.plus(days(7))
        val row = Db2.item(p.item)
        assertEquals("CONFIRMED", row.status)
        assertEquals(retro, row.confirmedAt)
        val e = Db.earnOfItem(p.item).single()
        assertEquals("AVAILABLE", e.status)
        assertEquals(retro.plus(days(365)), e.expiresAt)
        assertEquals(1_000L, Db.balance(p.buyer)!!.available)
        assertEquals(0L, Db.balance(p.buyer)!!.pending)

        Flow.advance(HOUR)
        S2.runBatch("auto-confirm")
        assertEquals(retro, Db2.item(p.item).confirmedAt)
        assertEquals(1_000L, Db.balance(p.buyer)!!.available)
        S2.confirm(p.item, p.buyer).assertError(409, "ITEM_STATE_CONFLICT")
    }

    @Test
    fun `auto-confirm - 구매자가 먼저 확정한 항목은 바꾸지 않는다(confirmed_at 그대로)`() {
        val p = S2.purchase(100_000)
        S2.toDelivered(p.item, p.seller)
        val deliveredAt = Db2.item(p.item).deliveredAt!!
        val confirmedAt = Flow.advance(DAY)
        S2.ready("구매 확정", S2.confirm(p.item, p.buyer))
        Flow.moveTo(deliveredAt.plus(days(8)))
        S2.runBatch("auto-confirm")
        assertEquals(confirmedAt, Db2.item(p.item).confirmedAt)
        assertEquals(1_000L, Db.balance(p.buyer)!!.available)
    }

    @Test
    fun `point-expiry - expires_at 1초 전 그대로, 정각에 EXPIRE(amount = remaining, 주문 NULL)·배분·묶음 0·EARN AVAILABLE 유지·available 감소, 재실행 무변경`() {
        val buyer = Db.createUser()
        val bundle = Db.createPointBundle(buyer, 3_000, Flow.now())
        // 1,000P를 써서 remaining 2,000 (체결가 100,000 → 총액 103,000)
        S2.purchase(listOf(Line(100_000)), buyer, points = 1_000)
        assertEquals(2_000L, Db.ledgerEntry(bundle.earnId).remaining)
        val availableBefore = Db.balance(buyer)!!.available

        Flow.moveTo(bundle.expiresAt.minus(SEC))
        S2.runBatch("point-expiry")
        assertTrue(Db2.ledgerOfUser(buyer, "EXPIRE").isEmpty())
        assertEquals(2_000L, Db.ledgerEntry(bundle.earnId).remaining)

        val now = Flow.moveTo(bundle.expiresAt)
        S2.runBatch("point-expiry")
        val ex = Db2.ledgerOfUser(buyer, "EXPIRE").single()
        assertEquals("DONE", ex.status)
        assertEquals(2_000L, ex.amount)
        assertNull(ex.orderId)
        assertNull(ex.orderItemId)
        assertNull(ex.remaining)
        assertEquals(now, ex.createdAt)
        assertEquals(mapOf(bundle.earnId to 2_000L), allocMap(ex.id))
        val b = Db.ledgerEntry(bundle.earnId)
        assertEquals(0L, b.remaining)
        assertEquals("AVAILABLE", b.status)
        val bal = Db.balance(buyer)!!
        assertEquals(availableBefore - 2_000L, bal.available)
        assertEquals(now, bal.updatedAt)

        Flow.advance(HOUR)
        S2.runBatch("point-expiry")
        assertEquals(1, Db2.ledgerOfUser(buyer, "EXPIRE").size)
    }

    @Test
    fun `배치 이름 - 2단계 배치 5종이 모두 200 processed 정수(grade-recalc는 기준일 00시에)`() {
        Flow.moveTo(Kst.at(Kst.nextMonthStart(Flow.now())))
        for (name in listOf("shipment-deadline", "auto-confirm", "point-expiry", "grade-recalc", "weekly-settlement")) {
            S2.runBatch(name)
        }
    }
}
