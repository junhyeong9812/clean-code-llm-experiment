package com.ccle.behavior.contract2

import com.ccle.behavior.support.Contract2Test
import com.ccle.behavior.support.Db2
import com.ccle.behavior.support.Flow
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-0004 §3 판매자·센터 절차: 발송(기한 경계), 입고, 검수 PASS·FAIL(reason 규칙), 배송 완료, 허용 상태 밖 409. */
@Contract2Test
class FulfillmentTest {

    @Test
    fun `발송 - 현재 lt paid_at + 3일이면 200 SHIPPED_TO_CENTER·shipped_at = 현재, 기한 시각에는 409 DEADLINE_PASSED·무변경`() {
        val early = S2.purchase(70_000)
        val late = S2.purchase(70_000)
        assertEquals(early.paidAt, late.paidAt)
        val deadline = early.paidAt.plus(days(3))

        val t1 = Flow.moveTo(deadline.minus(SEC))
        val row = assertItemOk(S2.ship(early.item, early.seller), early.item, "SHIPPED_TO_CENTER")
        assertEquals(t1, row.shippedAt)
        assertEquals(early.paidAt, row.paidAt)
        assertNull(row.inspectedAt)

        Flow.moveTo(deadline)
        S2.ship(late.item, late.seller).assertError(409, "DEADLINE_PASSED")
        val after = Db2.item(late.item)
        assertEquals("PAID", after.status)
        assertNull(after.shippedAt)
    }

    @Test
    fun `입고 - SHIPPED_TO_CENTER → INSPECTING, 시각 열 없음(inspected_at NULL)`() {
        val p = S2.purchase(70_000)
        val shippedAt = Flow.now()
        S2.toShipped(p.item, p.seller)
        Flow.advance(HOUR)
        val row = assertItemOk(S2.receive(p.item), p.item, "INSPECTING")
        assertEquals(shippedAt, row.shippedAt)
        assertNull(row.inspectedAt)
        assertTrue(Db2.inspections(p.item).isEmpty())
    }

    @Test
    fun `검수 합격 - inspections PASS·reason NULL(요청 reason 무시)·inspected_at = 현재, 항목 DELIVERING·같은 시각`() {
        val p = S2.purchase(70_000)
        val shippedAt = Flow.now()
        S2.toInspecting(p.item, p.seller)
        val now = Flow.advance(HOUR)

        val row = assertItemOk(S2.inspect(p.item, "PASS", "무시될 사유"), p.item, "DELIVERING")
        assertEquals(now, row.inspectedAt)
        assertEquals(shippedAt, row.shippedAt)
        assertNull(row.deliveredAt)
        val ins = Db2.inspections(p.item).single()
        assertEquals("PASS", ins.result)
        assertNull(ins.reason, "PASS의 reason은 NULL")
        assertEquals(now, ins.inspectedAt)
        assertTrue(Db2.penaltiesOfItem(p.item).isEmpty())
        assertNoRefund(p, p.item)
    }

    @Test
    fun `검수 불합격 - inspections FAIL·reason, CANCELLED_SELLER_FAULT·inspected_at = cancelled_at = 현재, 전액 환불(fault SELLER, reason = 검수 reason)·패널티`() {
        // 45,678: 수수료 1,370, 배송비 3,000, 총액 50,048(포인트 0 → 카드 전액). 패널티 floor(4,567.8) = 4,567
        val p = S2.purchase(45_678)
        S2.toInspecting(p.item, p.seller)
        val now = Flow.advance(HOUR)

        val row = assertItemOk(S2.inspect(p.item, "FAIL", "사이즈 불일치"), p.item, "CANCELLED_SELLER_FAULT")
        assertEquals(now, row.inspectedAt)
        assertEquals(now, row.cancelledAt)
        val ins = Db2.inspections(p.item).single()
        assertEquals("FAIL", ins.result)
        assertEquals("사이즈 불일치", ins.reason)
        assertEquals(now, ins.inspectedAt)

        assertRefund(p.item, RefundExp(card = 50_048, point = 0, buyerFee = 1_370, shipping = 3_000, reason = "사이즈 불일치", fault = "SELLER"), now)
        assertCardCancel(p, p.item, 50_048, now)
        val pen = Db2.penaltiesOfItem(p.item).single()
        assertEquals(p.seller, pen.sellerId)
        assertEquals(4_567L, pen.amount)
        assertNull(pen.settlementId)
        assertEquals(now, pen.createdAt)
        assertEquals("CANCELLED", com.ccle.behavior.support.Db.order(p.orderId).status)
    }

    @Test
    fun `검수 요청 오류 400 INVALID_REQUEST - FAIL에 reason null·빈 문자열·공백만, 알 수 없는 result, result 누락 - 무변경`() {
        val p = S2.purchase(70_000)
        S2.toInspecting(p.item, p.seller)
        S2.inspect(p.item, "FAIL", null).assertError(400, "INVALID_REQUEST")
        S2.inspect(p.item, "FAIL", "").assertError(400, "INVALID_REQUEST")
        S2.inspect(p.item, "FAIL", "   ").assertError(400, "INVALID_REQUEST")
        S2.inspect(p.item, "MAYBE", "사유").assertError(400, "INVALID_REQUEST")
        S2.inspect(p.item, null, "사유").assertError(400, "INVALID_REQUEST")
        assertEquals("INSPECTING", Db2.item(p.item).status)
        assertTrue(Db2.inspections(p.item).isEmpty())
        assertNoRefund(p, p.item)
        // 대조군: 올바른 요청은 처리된다
        assertItemOk(S2.inspect(p.item, "FAIL", "오염"), p.item, "CANCELLED_SELLER_FAULT")
    }

    @Test
    fun `배송 완료 - DELIVERING → DELIVERED, delivered_at = 현재`() {
        val p = S2.purchase(70_000)
        S2.toDelivering(p.item, p.seller)
        val inspectedAt = Db2.item(p.item).inspectedAt
        val now = Flow.advance(DAY)
        val row = assertItemOk(S2.deliver(p.item), p.item, "DELIVERED")
        assertEquals(now, row.deliveredAt)
        assertEquals(inspectedAt, row.inspectedAt)
        assertNull(row.confirmedAt)
    }

    @Test
    fun `허용 상태 밖 409 ITEM_STATE_CONFLICT - 단계마다 다음 단계가 아닌 요청, 상태 무변경`() {
        val p = S2.purchase(70_000)
        val id = p.item
        fun expectState(s: String) = assertEquals(s, Db2.item(id).status)

        // PAID
        S2.receive(id).assertError(409, "ITEM_STATE_CONFLICT")
        S2.inspect(id, "PASS").assertError(409, "ITEM_STATE_CONFLICT")
        S2.deliver(id).assertError(409, "ITEM_STATE_CONFLICT")
        S2.confirm(id, p.buyer).assertError(409, "ITEM_STATE_CONFLICT")
        S2.dispute(id, p.buyer).assertError(409, "ITEM_STATE_CONFLICT")
        expectState("PAID")

        // SHIPPED_TO_CENTER
        S2.toShipped(id, p.seller)
        S2.ship(id, p.seller).assertError(409, "ITEM_STATE_CONFLICT")
        S2.inspect(id, "PASS").assertError(409, "ITEM_STATE_CONFLICT")
        S2.deliver(id).assertError(409, "ITEM_STATE_CONFLICT")
        S2.cancel(id, p.buyer).assertError(409, "ITEM_STATE_CONFLICT")
        expectState("SHIPPED_TO_CENTER")

        // INSPECTING
        S2.ready("입고", S2.receive(id))
        S2.receive(id).assertError(409, "ITEM_STATE_CONFLICT")
        S2.deliver(id).assertError(409, "ITEM_STATE_CONFLICT")
        S2.cancel(id, p.buyer).assertError(409, "ITEM_STATE_CONFLICT")
        S2.confirm(id, p.buyer).assertError(409, "ITEM_STATE_CONFLICT")
        expectState("INSPECTING")

        // DELIVERING
        S2.ready("검수 합격", S2.inspect(id, "PASS"))
        S2.inspect(id, "PASS").assertError(409, "ITEM_STATE_CONFLICT")
        S2.inspect(id, "FAIL", "오염").assertError(409, "ITEM_STATE_CONFLICT")
        S2.receive(id).assertError(409, "ITEM_STATE_CONFLICT")
        S2.confirm(id, p.buyer).assertError(409, "ITEM_STATE_CONFLICT")
        S2.cancel(id, p.buyer).assertError(409, "ITEM_STATE_CONFLICT")
        expectState("DELIVERING")
        assertEquals(1, Db2.inspections(id).size)

        // DELIVERED
        S2.ready("배송 완료", S2.deliver(id))
        S2.deliver(id).assertError(409, "ITEM_STATE_CONFLICT")
        S2.ship(id, p.seller).assertError(409, "ITEM_STATE_CONFLICT")
        S2.cancel(id, p.buyer).assertError(409, "ITEM_STATE_CONFLICT")
        S2.dispute(id, p.buyer).assertError(409, "ITEM_STATE_CONFLICT")
        expectState("DELIVERED")
        assertNoRefund(p, id)
    }

    @Test
    fun `발송된 항목(SHIPPED_TO_CENTER·INSPECTING)은 발송 기한이 지나도 shipment-deadline 대상이 아니다`() {
        val shipped = S2.purchase(70_000)
        val inspecting = S2.purchase(70_000)
        S2.toShipped(shipped.item, shipped.seller)
        S2.toInspecting(inspecting.item, inspecting.seller)

        Flow.moveTo(shipped.paidAt.plus(days(3)).plus(HOUR))
        S2.runBatch("shipment-deadline")
        assertEquals("SHIPPED_TO_CENTER", Db2.item(shipped.item).status)
        assertEquals("INSPECTING", Db2.item(inspecting.item).status)
        for (p in listOf(shipped, inspecting)) {
            assertNoRefund(p, p.item)
            assertTrue(Db2.penaltiesOfItem(p.item).isEmpty())
        }
    }

    @Test
    fun `검수 불합격 reason은 받은 그대로 저장 - 앞뒤 공백 포함 inspections·refunds`() {
        val p = S2.purchase(70_000)
        S2.toInspecting(p.item, p.seller)
        assertItemOk(S2.inspect(p.item, "FAIL", "  오염  "), p.item, "CANCELLED_SELLER_FAULT")
        assertEquals("  오염  ", Db2.inspections(p.item).single().reason)
        assertEquals("  오염  ", Db2.refunds(p.item).single().reason)
    }
}
