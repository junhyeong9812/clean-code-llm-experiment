package com.ccle.behavior.contract2

import com.ccle.behavior.support.Contract2Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db2
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.longAt
import com.ccle.behavior.support.textAt
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-0004 §4 구매자 절차: 구매 확정(7일 뒤 = 소급), 구매자 취소(PAID만), 분쟁 제기(14일 정각 포함·재제기), 분쟁 판정. */
@Contract2Test
class BuyerActionTest {

    // 체결가 100,000 → 수수료 3,000, 배송비 0, 카드 103,000, 적립 1,000(BRONZE, ADR-0003 §8 D)
    private val price = 100_000L
    private val card = 103_000L
    private val earn = 1_000L

    @Test
    fun `확정 - DELIVERED만, confirmed_at = 현재(7일 안), CONFIRMED·적립 AVAILABLE(expires_at = confirmed_at + 365일)·잔액 이동`() {
        val p = S2.purchase(price)
        S2.toDelivered(p.item, p.seller)
        val deliveredAt = Db2.item(p.item).deliveredAt!!
        val now = Flow.advance(days(2))

        val row = assertItemOk(S2.confirm(p.item, p.buyer), p.item, "CONFIRMED")
        assertEquals(now, row.confirmedAt)
        assertEquals(deliveredAt, row.deliveredAt)
        val e = Db.earnOfItem(p.item).single()
        assertEquals("AVAILABLE", e.status)
        assertEquals(earn, e.amount)
        assertEquals(earn, e.remaining)
        assertEquals(now.plus(days(365)), e.expiresAt)
        assertEquals(p.paidAt, e.createdAt, "EARN created_at은 결제 시각 그대로")
        val bal = Db.balance(p.buyer)!!
        assertEquals(earn, bal.available)
        assertEquals(0L, bal.pending)
        assertEquals(now, bal.updatedAt)
    }

    @Test
    fun `확정 - 배송 완료 7일이 지난 뒤의 확정은 confirmed_at = delivered_at + 7일(소급)`() {
        val p = S2.purchase(price)
        S2.toDelivered(p.item, p.seller)
        val deliveredAt = Db2.item(p.item).deliveredAt!!
        Flow.moveTo(deliveredAt.plus(days(8)))

        val row = assertItemOk(S2.confirm(p.item, p.buyer), p.item, "CONFIRMED")
        assertEquals(deliveredAt.plus(days(7)), row.confirmedAt)
        assertEquals(deliveredAt.plus(days(7)).plus(days(365)), Db.earnOfItem(p.item).single().expiresAt)
    }

    @Test
    fun `확정 - 적립 행이 없는 항목(적립 0)은 포인트 변화 없음`() {
        // 체결가 50: 수수료 1,000, 배송비 3,000 → 카드 몫 4,050 중 체결가 부분 50 → 적립 floor(0.5) = 0
        val p = S2.purchase(50)
        assertTrue(Db.earnOfItem(p.item).isEmpty(), "준비(1단계): 적립 0이면 EARN 행 없음")
        S2.toDelivered(p.item, p.seller)
        val before = points(p.buyer)
        assertItemOk(S2.confirm(p.item, p.buyer), p.item, "CONFIRMED")
        assertTrue(Db.ledger(p.buyer).isEmpty())
        assertEquals(before, points(p.buyer), "available·pending 그대로(잔액 행 없음 = 0)")
    }

    /** (available, pending). point_balances 행이 없으면 0, 0. */
    private fun points(user: Long): Pair<Long, Long> = Db.balance(user).let { (it?.available ?: 0L) to (it?.pending ?: 0L) }

    @Test
    fun `구매자 취소 - PAID 항목 CANCELLED_BY_BUYER·cancelled_at = 현재, 환불 fault BUYER·reason 구매자 취소, 체결 PAID·입찰 MATCHED 유지`() {
        val p = S2.purchase(price)
        val now = Flow.advance(HOUR)
        val row = assertItemOk(S2.cancel(p.item, p.buyer), p.item, "CANCELLED_BY_BUYER")
        assertEquals(now, row.cancelledAt)
        assertNull(row.shippedAt)
        // 한 항목 주문: 마지막 환불 = 몫 전부(배송비 0)
        assertRefund(p.item, RefundExp(card = card, point = 0, buyerFee = 3_000, shipping = 0, reason = "구매자 취소", fault = "BUYER"), now)
        assertCardCancel(p, p.item, card, now)
        assertEquals("CANCELLED", Db.order(p.orderId).status)
        // 적립 예정 취소: PENDING → CANCELLED, remaining 그대로, pending 감소
        val e = Db.earnOfItem(p.item).single()
        assertEquals("CANCELLED", e.status)
        assertEquals(earn, e.remaining)
        assertEquals(0L, Db.balance(p.buyer)!!.pending)
        // 체결·입찰은 그대로(판매 입찰 복구 없음)
        val t = Db.trade(p.trades.single())
        assertEquals("PAID", t.status)
        assertEquals("MATCHED", Db.bid(t.sellBidId).status)
        assertEquals("MATCHED", Db.bid(t.buyBidId).status)
    }

    @Test
    fun `구매자 취소 - 발송 기한이 지나도 배치가 돌기 전이면 PAID라 취소된다`() {
        val p = S2.purchase(price)
        val now = Flow.moveTo(p.paidAt.plus(days(3)).plus(HOUR))
        assertEquals("PAID", Db2.item(p.item).status)
        val row = assertItemOk(S2.cancel(p.item, p.buyer), p.item, "CANCELLED_BY_BUYER")
        assertEquals(now, row.cancelledAt)
        assertEquals("BUYER", Db2.refunds(p.item).single().fault)
        assertTrue(Db2.penaltiesOfItem(p.item).isEmpty())
    }

    @Test
    fun `분쟁 제기 - CONFIRMED이고 confirmed_at + 14일 정각까지 201, disputes OPEN·항목 DISPUTED, 응답 모양`() {
        val p = S2.purchase(price)
        S2.toConfirmed(p.item, p.seller, p.buyer)
        val confirmedAt = Db2.item(p.item).confirmedAt!!
        val now = Flow.moveTo(confirmedAt.plus(days(14)))

        val r = S2.dispute(p.item, p.buyer).assertStatus(201)
        val d = Db2.disputes(p.item).single()
        assertEquals(d.id, r.long("disputeId"))
        assertEquals(p.item, r.long("orderItemId"))
        assertEquals("OPEN", r.text("status"))
        assertEquals(now, r.json.instantOrNull("openedAt"))
        assertEquals("OPEN", d.status)
        assertEquals(now, d.openedAt)
        assertNull(d.resolvedAt)
        val item = assertItemOk(S2.item(p.item, p.buyer), p.item, "DISPUTED")
        assertEquals(confirmedAt, item.confirmedAt)
    }

    @Test
    fun `분쟁 제기 - 14일 + 1초 뒤 409 DEADLINE_PASSED, CONFIRMED가 아니면 409 ITEM_STATE_CONFLICT, 무변경`() {
        val late = S2.purchase(price)
        val delivered = S2.purchase(price)
        S2.toConfirmed(late.item, late.seller, late.buyer)
        S2.toDelivered(delivered.item, delivered.seller)
        val confirmedAt = Db2.item(late.item).confirmedAt!!

        S2.dispute(delivered.item, delivered.buyer).assertError(409, "ITEM_STATE_CONFLICT")
        assertEquals("DELIVERED", Db2.item(delivered.item).status)

        Flow.moveTo(confirmedAt.plus(days(14)).plus(SEC))
        S2.dispute(late.item, late.buyer).assertError(409, "DEADLINE_PASSED")
        assertEquals("CONFIRMED", Db2.item(late.item).status)
        assertTrue(Db2.disputes(late.item).isEmpty())
    }

    @Test
    fun `분쟁 기간은 소급된 confirmed_at 기준 - 늦게 확정한 항목은 이미 기간이 지났을 수 있다`() {
        val p = S2.purchase(price)
        S2.toDelivered(p.item, p.seller)
        val deliveredAt = Db2.item(p.item).deliveredAt!!
        Flow.moveTo(deliveredAt.plus(days(21)).plus(SEC))
        val row = assertItemOk(S2.confirm(p.item, p.buyer), p.item, "CONFIRMED")
        assertEquals(deliveredAt.plus(days(7)), row.confirmedAt)
        S2.dispute(p.item, p.buyer).assertError(409, "DEADLINE_PASSED")
        assertTrue(Db2.disputes(p.item).isEmpty())
    }

    @Test
    fun `분쟁 기각 - REJECTED·resolved_at = 현재, 항목 CONFIRMED·confirmed_at 그대로, 다시 판정 409 DISPUTE_NOT_OPEN, 기간 안 재제기 201`() {
        val p = S2.purchase(price)
        S2.toConfirmed(p.item, p.seller, p.buyer)
        val confirmedAt = Db2.item(p.item).confirmedAt!!
        Flow.advance(DAY)
        val disputeId = S2.openDispute(p.item, p.buyer)
        S2.dispute(p.item, p.buyer).assertError(409, "ITEM_STATE_CONFLICT")   // OPEN 중 재제기
        val now = Flow.advance(DAY)

        val r = S2.resolve(disputeId, false).assertStatus(200)
        assertEquals(disputeId, r.long("disputeId"))
        assertEquals(p.item, r.long("orderItemId"))
        assertEquals("REJECTED", r.text("status"))
        assertEquals(now, r.json.instantOrNull("resolvedAt"))
        assertEquals(Db2.dispute(disputeId).openedAt, r.json.instantOrNull("openedAt"))
        val d = Db2.dispute(disputeId)
        assertEquals("REJECTED", d.status)
        assertEquals(now, d.resolvedAt)
        val row = Db2.item(p.item)
        assertEquals("CONFIRMED", row.status)
        assertEquals(confirmedAt, row.confirmedAt)
        assertNoRefund(p, p.item)

        S2.resolve(disputeId, true).assertError(409, "DISPUTE_NOT_OPEN")
        assertEquals("REJECTED", Db2.dispute(disputeId).status)

        // 기각된 항목은 기간 안이면 다시 제기할 수 있다
        val again = S2.dispute(p.item, p.buyer).assertStatus(201)
        assertEquals("OPEN", again.text("status"))
        assertEquals(listOf("REJECTED", "OPEN"), Db2.disputes(p.item).map { it.status })
        assertEquals("DISPUTED", Db2.item(p.item).status)
    }

    @Test
    fun `분쟁 판정 요청 오류 - 없는 분쟁 404 NOT_FOUND, accept 누락 400 INVALID_REQUEST`() {
        val p = S2.purchase(price)
        S2.toConfirmed(p.item, p.seller, p.buyer)
        val disputeId = S2.openDispute(p.item, p.buyer)
        com.ccle.behavior.support.Api.post("/admin/disputes/$disputeId/resolution", emptyMap<String, Any>()).assertError(400, "INVALID_REQUEST")
        assertEquals("OPEN", Db2.dispute(disputeId).status)
        S2.resolve(Db.missingId("disputes"), true).assertError(404, "NOT_FOUND")
        // 대조군
        S2.resolve(disputeId, false).assertStatus(200)
    }

    @Test
    fun `분쟁 인정 - ACCEPTED, 항목 REFUNDED_AFTER_CONFIRM·cancelled_at = resolved_at, 전액 환불 fault DISPUTE·reason 분쟁 인정, 패널티 없음, 적립 회수`() {
        val p = S2.purchase(price)
        S2.toConfirmed(p.item, p.seller, p.buyer)
        val disputeId = S2.openDispute(p.item, p.buyer)
        val now = Flow.advance(DAY)

        val r = S2.resolve(disputeId, true).assertStatus(200)
        assertEquals("ACCEPTED", r.text("status"))
        assertEquals(now, r.json.instantOrNull("resolvedAt"))
        val d = Db2.dispute(disputeId)
        assertEquals("ACCEPTED", d.status)
        assertEquals(now, d.resolvedAt)
        val row = assertItemOk(S2.item(p.item, p.buyer), p.item, "REFUNDED_AFTER_CONFIRM")
        assertEquals(now, row.cancelledAt)
        assertRefund(p.item, RefundExp(card = card, point = 0, buyerFee = 3_000, shipping = 0, reason = "분쟁 인정", fault = "DISPUTE"), now)
        assertCardCancel(p, p.item, card, now)
        assertTrue(Db2.penaltiesOfItem(p.item).isEmpty(), "분쟁 인정은 패널티 없음")
        assertEquals("CANCELLED", Db.order(p.orderId).status)
        // §8.4 회수: 쓰지 않은 적립 전부, EARN은 AVAILABLE 유지
        val e = Db.earnOfItem(p.item).single()
        assertEquals("AVAILABLE", e.status)
        assertEquals(0L, e.remaining)
        val revoke = Db2.ledgerOfItem(p.item, "REVOKE").single()
        assertEquals(earn, revoke.amount)
        assertEquals(p.orderId, revoke.orderId)
        assertEquals(now, revoke.createdAt)
        assertEquals(mapOf(e.id to earn), allocMap(revoke.id))
        assertEquals(0L, Db.balance(p.buyer)!!.available)

        // 판정된 분쟁은 다시 판정할 수 없고, 환불된 항목에는 분쟁을 제기할 수 없다
        S2.resolve(disputeId, false).assertError(409, "DISPUTE_NOT_OPEN")
        S2.dispute(p.item, p.buyer).assertError(409, "ITEM_STATE_CONFLICT")
        assertEquals(1, Db2.refunds(p.item).size)
    }

    @Test
    fun `분쟁 응답 - openedAt·resolvedAt은 오프셋 포함 ISO-8601이고 disputes 행·시계와 같다`() {
        val p = S2.purchase(price)
        S2.toConfirmed(p.item, p.seller, p.buyer)
        val openedAt = Flow.advance(HOUR)
        val r = S2.dispute(p.item, p.buyer).assertStatus(201)
        val iso = Regex(""".*T.*([+-]\d{2}:\d{2}|Z)$""")
        assertTrue(iso.matches(r.json.textAt("openedAt")), r.toString())
        val disputeId = r.json.longAt("disputeId")
        val d = Db2.disputes(p.item).single()
        assertEquals(d.id, disputeId, "disputes.id = 응답 disputeId")
        assertEquals("OPEN", d.status)
        assertEquals(openedAt, d.openedAt, "opened_at = 그때 시계")
        assertEquals(d.openedAt, r.json.instantOrNull("openedAt"), "응답 openedAt = opened_at")
        assertEquals("DISPUTED", Db2.item(p.item).status)

        val resolvedAt = Flow.advance(HOUR)
        val res = S2.resolve(disputeId, false).assertStatus(200)
        assertTrue(iso.matches(res.json.textAt("resolvedAt")), res.toString())
        val after = Db2.dispute(disputeId)
        assertEquals("REJECTED", after.status)
        assertEquals(resolvedAt, after.resolvedAt, "resolved_at = 그때 시계")
        assertEquals(after.resolvedAt, res.json.instantOrNull("resolvedAt"), "응답 resolvedAt = resolved_at")
        assertEquals(after.openedAt, res.json.instantOrNull("openedAt"))
        assertEquals("CONFIRMED", Db2.item(p.item).status)
    }
}
