package com.ccle.behavior.contract2

import com.ccle.behavior.support.Contract2Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db2
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.PgCancel
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-0004 §9 동시성·장애 중 직렬로 판정할 수 있는 것: 상태가 바뀐 뒤 도착한 요청은 409(§9.1·§9.2 — 요청은 멱등이 아니다),
 * 경합 쌍(취소 ↔ 발송, 취소 ↔ 기한 초과 배치, 확정 ↔ 확정)의 진 쪽은 아무것도 바꾸지 않는다.
 * 배치 재실행 멱등은 BatchTest·SettlementTest·GradeRecalcTest에 있다.
 */
@Contract2Test
class SerialRaceTest {

    @Test
    fun `같은 취소를 다시 보내면 409 ITEM_STATE_CONFLICT, 환불·RESTORE·PG 취소는 1건`() {
        val buyer = Db.createUser()
        Db.createPointBundle(buyer, 3_000, Flow.now())
        val p = S2.purchase(listOf(Line(100_000)), buyer, points = 1_000)
        val now = Flow.advance(HOUR)
        S2.ready("구매자 취소", S2.cancel(p.item, buyer))
        Flow.advance(HOUR)
        val requestsBefore = PgCancel.info(p.item).requests
        S2.cancel(p.item, buyer).assertError(409, "ITEM_STATE_CONFLICT")
        assertEquals(now, Db2.item(p.item).cancelledAt)
        assertEquals(1, Db2.refunds(p.item).size)
        assertEquals(1, Db2.ledgerOfItem(p.item, "RESTORE").size)
        assertEquals(1, Db2.pgCancels(p.orderId).size)
        assertEquals(requestsBefore, PgCancel.info(p.item).requests, "상태 위반은 PG를 부르지 않는다")
    }

    @Test
    fun `취소 ↔ 발송 - 취소된 항목의 발송은 409, 발송된 항목의 취소는 409`() {
        val cancelled = S2.purchase(70_000)
        S2.ready("구매자 취소", S2.cancel(cancelled.item, cancelled.buyer))
        S2.ship(cancelled.item, cancelled.seller).assertError(409, "ITEM_STATE_CONFLICT")
        assertEquals("CANCELLED_BY_BUYER", Db2.item(cancelled.item).status)
        assertEquals(null, Db2.item(cancelled.item).shippedAt)

        val shipped = S2.purchase(70_000)
        S2.toShipped(shipped.item, shipped.seller)
        S2.cancel(shipped.item, shipped.buyer).assertError(409, "ITEM_STATE_CONFLICT")
        assertEquals("SHIPPED_TO_CENTER", Db2.item(shipped.item).status)
        assertNoRefund(shipped, shipped.item)
    }

    @Test
    fun `취소 ↔ 기한 초과 배치 - 먼저 취소된 항목은 shipment-deadline이 건드리지 않고(패널티 없음), 배치가 먼저면 취소는 409`() {
        val first = S2.purchase(70_000)
        val second = S2.purchase(70_000)
        S2.ready("구매자 취소", S2.cancel(first.item, first.buyer))
        Flow.moveTo(first.paidAt.plus(days(3)))
        S2.runBatch("shipment-deadline")
        assertEquals("CANCELLED_BY_BUYER", Db2.item(first.item).status)
        assertEquals("BUYER", Db2.refunds(first.item).single().fault)
        assertTrue(Db2.penaltiesOfItem(first.item).isEmpty())

        assertEquals("CANCELLED_SELLER_FAULT", Db2.item(second.item).status)
        val requestsBefore = PgCancel.info(second.item).requests
        S2.cancel(second.item, second.buyer).assertError(409, "ITEM_STATE_CONFLICT")
        assertEquals("SELLER", Db2.refunds(second.item).single().fault)
        assertEquals(requestsBefore, PgCancel.info(second.item).requests, "상태 위반은 PG를 부르지 않는다")
    }

    @Test
    fun `같은 확정을 다시 보내면 409 ITEM_STATE_CONFLICT, confirmed_at·포인트 그대로`() {
        val p = S2.purchase(100_000)
        S2.toDelivered(p.item, p.seller)
        val confirmedAt = Flow.advance(HOUR)
        S2.ready("구매 확정", S2.confirm(p.item, p.buyer))
        Flow.advance(HOUR)
        S2.confirm(p.item, p.buyer).assertError(409, "ITEM_STATE_CONFLICT")
        assertEquals(confirmedAt, Db2.item(p.item).confirmedAt)
        assertEquals(1_000L, Db.balance(p.buyer)!!.available)
        assertEquals(0L, Db.balance(p.buyer)!!.pending)
    }
}
