package com.ccle.behavior.contract

import com.ccle.behavior.pg.FakePg
import com.ccle.behavior.support.ContractTest
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.FailTrigger
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.PgClient
import com.ccle.behavior.support.assertUnexpectedError
import com.ccle.behavior.support.failed
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-0003 §12의 1(계약 밖 서버 오류 = 400 UNEXPECTED_ERROR + 고정 문구)·§12의 2(배치 대상 1건 실패 격리)와
 * §7 배치 응답 `200 {"processed": n, "failed": m}`.
 *
 * 실패는 임시 DB 트리거로 자기 대상 A 한 행에서만 낸다(FailTrigger). 대상 A < B(id 순): A만 롤백되고(I1 — A의 행은 실행 전과 같다)
 * 배치는 B까지 계속 처리한다(I1), 응답 failed ≥ 1(I4), 트리거를 지운 뒤 다시 돌리면 A가 처리된다(I2).
 * 시드·다른 테스트 행도 처리되므로 processed·failed의 정확한 값은 단언하지 않는다.
 */
@ContractTest
class BatchIsolationTest {
    private val pg = PgClient.suite

    @Test
    fun `계약 밖 서버 오류 - POST bids 중 DB 예외는 400 UNEXPECTED_ERROR, message는 고정 문구(예외 문구 미포함), 원인 제거 뒤 201`() {
        val user = Db.createUser()
        val size = Db.createProductSize()
        FailTrigger.on("bids", "INSERT", "NEW.user_id = $user") { name ->
            Flow.bid(user, size, "BUY", 50_000).assertUnexpectedError(name)
        }
        assertTrue(Db.bidsOfUser(user).isEmpty(), "실패한 요청은 입찰을 남기지 않는다")
        // 대조: 같은 요청이 원인이 없으면 성공한다
        Flow.activeBid(user, size, "BUY", 50_000)
    }

    @Test
    fun `bid-expiry 격리 - A 만료가 실패해도 A는 ACTIVE·이벤트 없음, B는 EXPIRED, failed ≥ 1, 다시 돌리면 A EXPIRED`() {
        // A·B는 서로 독립(다른 사용자·다른 사이즈)
        val a = Flow.activeBid(Db.createUser(), Db.createProductSize(), "BUY", 60_000, 30)
        val b = Flow.activeBid(Db.createUser(), Db.createProductSize(), "BUY", 50_000, 30)
        assertTrue(a < b)
        val now = Flow.moveTo(maxOf(Db.bid(a).expiresAt, Db.bid(b).expiresAt))

        FailTrigger.on("bids", "UPDATE", "OLD.id = $a") {
            val r = Flow.runBatch("bid-expiry")
            assertTrue(r.failed() >= 1, "A 실패를 failed에 센다: $r")
            assertTrue(r.long("processed") >= 1, "B는 커밋되어 processed에 들어간다: $r")
        }
        // A: 실행 전과 같다
        assertEquals("ACTIVE", Db.bid(a).status)
        assertEquals(listOf(null to "ACTIVE"), Db.bidEvents(a).map { it.fromStatus to it.toStatus })
        // B: ADR대로 처리된다
        assertEquals("EXPIRED", Db.bid(b).status)
        val evB = Db.bidEvents(b).last()
        assertEquals("ACTIVE" to "EXPIRED", evB.fromStatus to evB.toStatus)
        assertNull(evB.tradeId)
        assertEquals(now, evB.createdAt)

        // I2: 원인이 없어지면 다음 실행에서 처리된다
        val later = Flow.advance(Duration.ofMinutes(1))
        Flow.runBatch("bid-expiry")
        assertEquals("EXPIRED", Db.bid(a).status)
        val evA = Db.bidEvents(a)
        assertEquals(listOf(null to "ACTIVE", "ACTIVE" to "EXPIRED"), evA.map { it.fromStatus to it.toStatus })
        assertEquals(later, evA.last().createdAt)
        assertEquals(2, Db.bidEvents(b).size, "B는 다시 처리되지 않는다")
    }

    @Test
    fun `payment-due 격리 - A 체결 만료가 실패해도 A는 AWAITING_PAYMENT·경고·입찰 이벤트 없음, B는 만료 처리, 다시 돌리면 A 만료`() {
        val buyerA = Db.createUser()
        val buyerB = Db.createUser()
        val sizeA = Db.createProductSize()
        val sizeB = Db.createProductSize()
        val tradeA = Flow.trade(buyerA, Db.createUser(), sizeA, 70_000, 60)
        val tradeB = Flow.trade(buyerB, Db.createUser(), sizeB, 70_000, 60)
        assertTrue(tradeA < tradeB)
        val ta = Db.trade(tradeA)
        val tb = Db.trade(tradeB)
        val now = Flow.moveTo(maxOf(ta.paymentDueAt, tb.paymentDueAt))

        FailTrigger.on("trades", "UPDATE", "OLD.id = $tradeA") {
            val r = Flow.runBatch("payment-due")
            assertTrue(r.failed() >= 1, "A 실패를 failed에 센다: $r")
            assertTrue(r.long("processed") >= 1, "B는 커밋되어 processed에 들어간다: $r")
        }
        // A: 체결·입찰·이벤트·경고·제한 모두 실행 전과 같다
        assertEquals("AWAITING_PAYMENT", Db.trade(tradeA).status)
        assertTrue(Db.warningsOfTrade(tradeA).isEmpty())
        assertNull(Db.user(buyerA).purchaseRestrictedUntil)
        for (bid in listOf(ta.buyBidId, ta.sellBidId)) {
            assertEquals("MATCHED", Db.bid(bid).status, "A 입찰 $bid")
            assertEquals(listOf(null to "ACTIVE", "ACTIVE" to "MATCHED"), Db.bidEvents(bid).map { it.fromStatus to it.toStatus }, "A 입찰 $bid 이벤트")
        }
        // B: ADR대로 처리된다(§7 payment-due)
        assertPaymentExpired(tb, buyerB, now)

        // I2
        val later = Flow.advance(Duration.ofMinutes(1))
        Flow.runBatch("payment-due")
        assertPaymentExpired(ta, buyerA, later)
        assertEquals(1, Db.warningsOfTrade(tradeB).size, "B는 다시 처리되지 않는다")
    }

    /** §7 payment-due 결과: 체결 PAYMENT_EXPIRED, 경고 1행, 구매 입찰 CANCELLED, 판매 입찰 ACTIVE 복구(만료 전·상대 없음). 이벤트 trade_id = 만료 체결. */
    private fun assertPaymentExpired(t: com.ccle.behavior.support.TradeRow, buyer: Long, at: java.time.Instant) {
        assertEquals("PAYMENT_EXPIRED", Db.trade(t.id).status)
        val w = Db.warningsOfTrade(t.id).single()
        assertEquals(buyer, w.userId)
        assertEquals(at, w.createdAt)
        assertEquals("CANCELLED", Db.bid(t.buyBidId).status)
        val buyEv = Db.bidEvents(t.buyBidId).last()
        assertEquals("MATCHED" to "CANCELLED", buyEv.fromStatus to buyEv.toStatus)
        assertEquals(t.id, buyEv.tradeId)
        assertEquals(at, buyEv.createdAt)
        assertEquals("ACTIVE", Db.bid(t.sellBidId).status)
        val sellEv = Db.bidEvents(t.sellBidId).last()
        assertEquals("MATCHED" to "ACTIVE", sellEv.fromStatus to sellEv.toStatus)
        assertEquals(t.id, sellEv.tradeId)
        assertTrue(buyEv.id < sellEv.id, "구매 입찰 이벤트가 판매 입찰 이벤트보다 먼저")
    }

    @Test
    fun `pg-inquiry 격리 - A 반영이 실패해도 A는 UNKNOWN·INQUIRY 행 없음·예약 유지, B는 PAID, 다시 돌리면 A PAID(INQUIRY 1행)`() {
        val requestedAt = Flow.now()
        val buyerA = Db.createUser()
        val buyerB = Db.createUser()
        val tradesA = tradesFor(buyerA, Table8.C)
        val tradesB = tradesFor(buyerB, Table8.C)
        val keyA = Flow.newKey()
        val keyB = Flow.newKey()
        Flow.pgOutcome(keyA, FakePg.Outcome.TIMEOUT_APPROVED)
        Flow.pgOutcome(keyB, FakePg.Outcome.TIMEOUT_APPROVED)
        val orderA = Flow.pay(buyerA, tradesA, 0, keyA).assertStatus(201).long("orderId")
        val orderB = Flow.pay(buyerB, tradesB, 0, keyB).assertStatus(201).long("orderId")
        assertTrue(orderA < orderB)
        assertEquals("UNKNOWN", Db.order(orderA).status, "준비: UNKNOWN")
        assertEquals("UNKNOWN", Db.order(orderB).status, "준비: UNKNOWN")
        // 결제 요청 시각이 같도록 시계는 결제 뒤에 옮긴다
        val firstRun = Flow.advance(Duration.ofMinutes(5))

        FailTrigger.on("orders", "UPDATE", "OLD.id = $orderA") {
            val r = Flow.runBatch("pg-inquiry")
            assertTrue(r.failed() >= 1, "A 실패를 failed에 센다: $r")
            assertTrue(r.long("processed") >= 1, "B는 커밋되어 processed에 들어간다: $r")
        }
        // A: 실행 전과 같다
        val a = Db.order(orderA)
        assertEquals("UNKNOWN", a.status)
        assertNull(a.paidAt)
        assertEquals(listOf("APPROVE" to "UNKNOWN"), Db.pgTransactions(orderA).map { it.kind to it.status }, "A에 INQUIRY 행이 없어야 한다")
        Db.orderItems(orderA).forEach { assertEquals("AWAITING_PAYMENT", it.status); assertNull(it.paidAt) }
        assertEquals("AWAITING_PAYMENT", Db.trade(tradesA.single()).status)
        assertTrue(Db.earnOfItem(Db.orderItems(orderA).single().id).isEmpty(), "A 적립 예정 없음")
        assertTrue(Db.balance(buyerA)?.pending?.let { it == 0L } ?: true, "A pending 변화 없음")
        // B: ADR대로 처리된다
        assertInquiryApproved(orderB, keyB, tradesB.single(), buyerB, requestedAt, firstRun)

        // I2
        val later = Flow.advance(Duration.ofMinutes(1))
        Flow.runBatch("pg-inquiry")
        assertInquiryApproved(orderA, keyA, tradesA.single(), buyerA, requestedAt, later)
        assertEquals(2, Db.pgTransactions(orderB).size, "B는 다시 처리되지 않는다")
    }

    /** §7 pg-inquiry 승인: 주문·항목·체결 PAID(paid_at = APPROVE requested_at), INQUIRY 1행(APPROVED, PG 값, 현재), 적립 예정. */
    private fun assertInquiryApproved(orderId: Long, key: String, tradeId: Long, buyer: Long, requestedAt: java.time.Instant, at: java.time.Instant) {
        val o = Db.order(orderId)
        assertEquals("PAID", o.status)
        assertEquals(requestedAt, o.paidAt, "paid_at = APPROVE requested_at")
        Db.orderItems(orderId).forEach { assertEquals("PAID", it.status); assertEquals(requestedAt, it.paidAt) }
        assertEquals("PAID", Db.trade(tradeId).status)
        val txs = Db.pgTransactions(orderId)
        assertEquals(listOf("APPROVE" to "UNKNOWN", "INQUIRY" to "APPROVED"), txs.map { it.kind to it.status })
        val inq = txs[1]
        assertEquals(Table8.C.cardAmount, inq.amount)
        assertEquals(key, inq.idempotencyKey)
        assertEquals(at, inq.requestedAt)
        assertEquals(at, inq.respondedAt)
        assertEquals(pg.pgTxId(key), inq.pgTxId)
        val earn = Db.earnOfItem(Db.orderItems(orderId).single().id).single()
        assertEquals("PENDING", earn.status)
        assertEquals(Table8.C.items[0].earn, earn.amount)
        assertEquals(Table8.C.items[0].earn, Db.balance(buyer)!!.pending)
    }

    @Test
    fun `배치 응답 - 1단계 배치는 200 processed·failed 정수`() {
        for (name in listOf("bid-expiry", "payment-due", "pg-inquiry")) {
            val r = Flow.runBatch(name)
            r.failed()
        }
    }
}
