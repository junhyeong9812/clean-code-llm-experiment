package com.ccle.behavior.contract2

import com.ccle.behavior.support.Api
import com.ccle.behavior.support.Contract2Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db2
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.PgCancel
import com.ccle.behavior.support.textAt
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-0004 §7 환불: §12 표 A1·A2·E1~E4(정본), refunds 열, 주문 상태, 카드 취소(PG 키·금액·pg_transactions CANCEL),
 * PG 불가 409 PG_UNAVAILABLE·무변경·재시도, 배치의 건너뛰기, 포인트 RESTORE(만료 늦은 묶음부터)·만료 묶음 복구 뒤 EXPIRE.
 */
@Contract2Test
class RefundTest {

    @Test
    fun `A1·A2 - 2번 항목 구매자 취소(부분) 뒤 1번 항목 취소(마지막), 카드·포인트 환불·주문 상태·적립 예정 취소·RESTORE·잔액`() {
        val buyer = Db.createUser()
        val bundle = Db.createPointBundle(buyer, 10_000, Flow.now())
        val p = S2.orderA(buyer)
        assertEquals(5_000L, Db.ledgerEntry(bundle.earnId).remaining)

        // A1
        val t1 = Flow.advance(HOUR)
        assertItemOk(S2.cancel(p.items[1], p.buyer), p.items[1], "CANCELLED_BY_BUYER")
        assertRefund(p.items[1], Table12.A1, t1)
        assertCardCancel(p, p.items[1], Table12.A1.card, t1)
        assertEquals("PARTIALLY_CANCELLED", Db.order(p.orderId).status)
        val earn2 = Db.earnOfItem(p.items[1]).single()
        assertEquals("CANCELLED", earn2.status)
        assertEquals(436L, earn2.remaining, "적립 예정 취소는 remaining을 그대로 둔다")
        val r1 = Db2.ledgerOfItem(p.items[1], "RESTORE").single()
        assertEquals("DONE", r1.status)
        assertEquals(Table12.A1.point, r1.amount)
        assertEquals(p.orderId, r1.orderId)
        assertEquals(t1, r1.createdAt)
        assertEquals(mapOf(bundle.earnId to Table12.A1.point), allocMap(r1.id))
        assertEquals(5_000L + 1_364L, Db.ledgerEntry(bundle.earnId).remaining)
        Db.balance(buyer)!!.let {
            assertEquals(6_364L, it.available)
            assertEquals(1_164L, it.pending)
            assertEquals(t1, it.updatedAt)
        }
        assertEquals("PARTIALLY_CANCELLED", Api.get("/orders/${p.orderId}", p.buyer).assertStatus(200).text("status"))

        // A2 (마지막)
        val t2 = Flow.advance(HOUR)
        assertItemOk(S2.cancel(p.items[0], p.buyer), p.items[0], "CANCELLED_BY_BUYER")
        assertRefund(p.items[0], Table12.A2, t2)
        assertCardCancel(p, p.items[0], Table12.A2.card, t2)
        assertEquals("CANCELLED", Db.order(p.orderId).status)
        val all = Db2.refundsOfOrder(p.orderId)
        assertEquals(164_950L, all.sumOf { it.cardAmount }, "Σ카드 환불 = card_amount")
        assertEquals(5_000L, all.sumOf { it.pointAmount }, "Σ포인트 환불 = point_used")
        assertEquals(164_950L, Db2.pgCancels(p.orderId).sumOf { it.amount })
        assertEquals(10_000L, Db.ledgerEntry(bundle.earnId).remaining)
        Db.balance(buyer)!!.let {
            assertEquals(10_000L, it.available)
            assertEquals(0L, it.pending)
        }
        val view = Api.get("/orders/${p.orderId}", p.buyer).assertStatus(200)
        assertEquals("CANCELLED", view.text("status"))
        assertEquals(listOf("CANCELLED_BY_BUYER", "CANCELLED_BY_BUYER"), view.json.get("items").map { it.textAt("status") })
    }

    @Test
    fun `E1·E2 - 1번 항목(배송비 귀속) 구매자 취소는 배송비 부분을 빼고, 마지막 2번 항목 취소에 배송비가 실린다`() {
        val buyer = Db.createUser()
        Db.createPointBundle(buyer, 10_000, Flow.now())
        val p = S2.orderE(buyer)

        val t1 = Flow.advance(HOUR)
        assertItemOk(S2.cancel(p.items[0], p.buyer), p.items[0], "CANCELLED_BY_BUYER")
        assertRefund(p.items[0], Table12.E1, t1)
        assertCardCancel(p, p.items[0], Table12.E1.card, t1)
        assertEquals("PARTIALLY_CANCELLED", Db.order(p.orderId).status)

        val t2 = Flow.advance(HOUR)
        assertItemOk(S2.cancel(p.items[1], p.buyer), p.items[1], "CANCELLED_BY_BUYER")
        assertRefund(p.items[1], Table12.E2, t2)
        assertCardCancel(p, p.items[1], Table12.E2.card, t2)
        assertEquals("CANCELLED", Db.order(p.orderId).status)
        val all = Db2.refundsOfOrder(p.orderId)
        assertEquals(50_000L, all.sumOf { it.cardAmount })
        assertEquals(5_000L, all.sumOf { it.pointAmount })
        assertEquals(3_000L, all.sumOf { it.shippingAmount })
        assertEquals(5_000L, Db2.ledgerOfUser(buyer, "RESTORE").sumOf { it.amount })
        assertTrue(Db.earnOfItem(p.items[0]).single().status == "CANCELLED" && Db.earnOfItem(p.items[1]).single().status == "CANCELLED")
    }

    @Test
    fun `E3·E4 - 1번 항목 검수 불합격(배송비 포함 전액·패널티 3,000), 이어 2번 항목 구매자 취소(배송비 이미 환불)`() {
        val buyer = Db.createUser()
        Db.createPointBundle(buyer, 10_000, Flow.now())
        val p = S2.orderE(buyer)

        S2.toInspecting(p.items[0], p.sellers[0])
        val t1 = Flow.advance(HOUR)
        assertItemOk(S2.inspect(p.items[0], "FAIL", "가품 의심"), p.items[0], "CANCELLED_SELLER_FAULT")
        assertRefund(p.items[0], Table12.e3("가품 의심"), t1)
        assertCardCancel(p, p.items[0], Table12.e3("").card, t1)
        assertEquals("PARTIALLY_CANCELLED", Db.order(p.orderId).status)
        val pen = Db2.penaltiesOfItem(p.items[0]).single()
        assertEquals(Table12.E3_PENALTY, pen.amount)
        assertEquals(p.sellers[0], pen.sellerId)

        val t2 = Flow.advance(HOUR)
        assertItemOk(S2.cancel(p.items[1], p.buyer), p.items[1], "CANCELLED_BY_BUYER")
        assertRefund(p.items[1], Table12.E4, t2)
        assertCardCancel(p, p.items[1], Table12.E4.card, t2)
        assertEquals("CANCELLED", Db.order(p.orderId).status)
        val all = Db2.refundsOfOrder(p.orderId)
        assertEquals(50_000L, all.sumOf { it.cardAmount })
        assertEquals(5_000L, all.sumOf { it.pointAmount })
        assertEquals(3_000L, all.sumOf { it.shippingAmount }, "배송비는 한 번만")
        assertTrue(Db2.penaltiesOfItem(p.items[1]).isEmpty())
    }

    @Test
    fun `카드 취소 PG 불가 - 409 PG_UNAVAILABLE, 아무것도 바뀌지 않음, 같은 요청을 다시 보내면 반영되고 PG 취소 기록은 1건`() {
        // 체결가 100,000, 포인트 1,000 → 카드 102,000, 한 항목 주문이라 환불 = 카드 102,000 · 포인트 1,000
        val buyer = Db.createUser()
        val bundle = Db.createPointBundle(buyer, 3_000, Flow.now())
        val p = S2.purchase(listOf(Line(100_000)), buyer, points = 1_000)
        val balBefore = Db.balance(buyer)!!
        PgCancel.unavailable(p.item)

        Flow.advance(HOUR)
        S2.cancel(p.item, p.buyer).assertError(409, "PG_UNAVAILABLE")
        val row = Db2.item(p.item)
        assertEquals("PAID", row.status)
        assertNoRefund(p, p.item)
        assertEquals("PAID", Db.order(p.orderId).status)
        assertEquals("PENDING", Db.earnOfItem(p.item).single().status)
        assertTrue(Db2.ledgerOfItem(p.item, "RESTORE").isEmpty())
        assertEquals(2_000L, Db.ledgerEntry(bundle.earnId).remaining)
        assertEquals(balBefore, Db.balance(buyer))
        val pg = PgCancel.info(p.item)
        assertEquals(listOf(102_000L), pg.amounts, "PG가 받은 취소 요청: $pg")
        assertNull(pg.status, "PG에 취소 기록 없음: $pg")

        PgCancel.approve(p.item)
        val now = Flow.advance(HOUR)
        assertItemOk(S2.cancel(p.item, p.buyer), p.item, "CANCELLED_BY_BUYER")
        assertRefund(p.item, RefundExp(card = 102_000, point = 1_000, buyerFee = 3_000, shipping = 0, reason = "구매자 취소", fault = "BUYER"), now)
        val after = PgCancel.info(p.item)
        assertEquals(2, after.requests)
        assertEquals("APPROVED", after.status)
        assertEquals(102_000L, after.amount)
        assertEquals(1, Db2.pgCancels(p.orderId).size)
        assertEquals(after.pgTxId, Db2.pgCancels(p.orderId).single().pgTxId)
        assertEquals(3_000L, Db.ledgerEntry(bundle.earnId).remaining)
    }

    @Test
    fun `카드 취소 PG 불가 - shipment-deadline은 그 대상을 건너뛰고(무변경) 다음 실행에서 처리한다`() {
        val p = S2.purchase(100_000)
        PgCancel.unavailable(p.item)
        Flow.moveTo(p.paidAt.plus(days(3)))
        S2.runBatch("shipment-deadline")
        assertEquals("PAID", Db2.item(p.item).status)
        assertNoRefund(p, p.item)
        assertTrue(Db2.penaltiesOfItem(p.item).isEmpty())
        assertTrue(PgCancel.info(p.item).requests >= 1, "배치가 PG에 취소를 요청했어야 한다")

        PgCancel.approve(p.item)
        val now = Flow.advance(HOUR)
        S2.runBatch("shipment-deadline")
        val row = Db2.item(p.item)
        assertEquals("CANCELLED_SELLER_FAULT", row.status)
        assertEquals(now, row.cancelledAt)
        assertEquals("발송 기한 초과", Db2.refunds(p.item).single().reason)
        assertEquals(1, Db2.penaltiesOfItem(p.item).size)
        assertEquals(1, Db2.pgCancels(p.orderId).size)
    }

    @Test
    fun `RESTORE - 그 주문 USE가 빠져나간 묶음으로, 만료가 늦은 묶음부터, 묶음마다 가져간 양 − 되돌린 양까지`() {
        val buyer = Db.createUser()
        val early = Db.createPointBundle(buyer, 3_000, Flow.now())
        val lateAt = Flow.advance(SEC)
        val late = Db.createPointBundle(buyer, 3_000, lateAt)
        assertTrue(late.expiresAt.isAfter(early.expiresAt))
        val p = S2.orderA(buyer)
        // FIFO(ADR-0003 §5.2): 이른 묶음 3,000 + 늦은 묶음 2,000
        val use = Db.useEntries(p.orderId).single()
        assertEquals(mapOf(early.earnId to 3_000L, late.earnId to 2_000L), allocMap(use.id), "준비(1단계) FIFO 배분")

        // A1: 1,364 → 늦은 묶음이 가져간 2,000 안에서
        S2.ready("구매자 취소", S2.cancel(p.items[1], p.buyer))
        val r1 = Db2.ledgerOfItem(p.items[1], "RESTORE").single()
        assertEquals(mapOf(late.earnId to 1_364L), allocMap(r1.id))
        assertEquals(0L, Db.ledgerEntry(early.earnId).remaining)
        assertEquals(1_000L + 1_364L, Db.ledgerEntry(late.earnId).remaining)

        // A2: 3,636 → 늦은 묶음의 남은 되돌림 636, 이른 묶음 3,000
        S2.ready("구매자 취소", S2.cancel(p.items[0], p.buyer))
        val r2 = Db2.ledgerOfItem(p.items[0], "RESTORE").single()
        assertEquals(mapOf(late.earnId to 636L, early.earnId to 3_000L), allocMap(r2.id))
        assertEquals(3_000L, Db.ledgerEntry(early.earnId).remaining)
        assertEquals(3_000L, Db.ledgerEntry(late.earnId).remaining)
    }

    @Test
    fun `RESTORE - 만료가 같으면 id 큰 묶음부터`() {
        val buyer = Db.createUser()
        val now = Flow.now()
        val first = Db.createPointBundle(buyer, 3_000, now)
        val second = Db.createPointBundle(buyer, 3_000, now)
        assertEquals(first.expiresAt, second.expiresAt)
        val p = S2.orderA(buyer)
        assertEquals(mapOf(first.earnId to 3_000L, second.earnId to 2_000L), allocMap(Db.useEntries(p.orderId).single().id), "준비(1단계) FIFO 배분")

        S2.ready("구매자 취소", S2.cancel(p.items[1], p.buyer))
        val r1 = Db2.ledgerOfItem(p.items[1], "RESTORE").single()
        assertEquals(mapOf(second.earnId to 1_364L), allocMap(r1.id))
        assertEquals(0L, Db.ledgerEntry(first.earnId).remaining)
    }

    @Test
    fun `RESTORE - 되돌린 묶음이 이미 만료됐으면 같은 시각 되돌린 양만큼 EXPIRE, remaining은 복구 전 값, 남은 양은 point-expiry가 소멸`() {
        val buyer = Db.createUser()
        val bundle = Db.createPointBundle(buyer, 2_000, Flow.now())
        Flow.moveTo(bundle.expiresAt.minus(DAY))
        // 체결가 100,000(배송비 0), 포인트 1,000 → 한 항목 주문, 취소 시 포인트 환불 1,000
        val p = S2.purchase(listOf(Line(100_000)), buyer, points = 1_000)
        assertEquals(1_000L, Db.ledgerEntry(bundle.earnId).remaining)
        val available = Db.balance(buyer)!!.available

        val now = Flow.moveTo(bundle.expiresAt)
        assertItemOk(S2.cancel(p.item, p.buyer), p.item, "CANCELLED_BY_BUYER")
        val restore = Db2.ledgerOfItem(p.item, "RESTORE").single()
        assertEquals(1_000L, restore.amount)
        assertEquals(mapOf(bundle.earnId to 1_000L), allocMap(restore.id))
        val expires = Db2.ledgerOfUser(buyer, "EXPIRE")
        val ex = expires.single()
        assertTrue(ex.id > restore.id, "EXPIRE가 RESTORE 뒤에 온다")
        assertEquals(1_000L, ex.amount, "되돌린 양만큼만")
        assertEquals("DONE", ex.status)
        assertEquals(now, ex.createdAt)
        assertEquals(mapOf(bundle.earnId to 1_000L), allocMap(ex.id))
        assertEquals(1_000L, Db.ledgerEntry(bundle.earnId).remaining, "remaining = 복구 전 값")
        assertEquals(available, Db.balance(buyer)!!.available)

        S2.runBatch("point-expiry")
        assertEquals(listOf(1_000L, 1_000L), Db2.ledgerOfUser(buyer, "EXPIRE").map { it.amount })
        assertEquals(0L, Db.ledgerEntry(bundle.earnId).remaining)
        assertEquals(available - 1_000L, Db.balance(buyer)!!.available)
    }
}
