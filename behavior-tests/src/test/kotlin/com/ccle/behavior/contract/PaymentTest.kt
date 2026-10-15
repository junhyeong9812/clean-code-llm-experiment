package com.ccle.behavior.contract

import com.ccle.behavior.pg.FakePg
import com.ccle.behavior.support.Api
import com.ccle.behavior.support.ContractTest
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.FakePgExtension
import com.ccle.behavior.support.Flow
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-0003 §5.1 결제 결과(승인·거절·타임아웃 두 종), §8 금액 표, §5.2 예약·확정·해제·적립 예정 행, §7 pg-inquiry. */
@ContractTest
class PaymentTest {
    private val day = Duration.ofDays(1)
    private val pg get() = FakePgExtension.pg

    @Test
    fun `승인 - 예 A(두 항목, 포인트 5,000) 금액·항목 순서·행 전체`() {
        val now = Flow.now()
        val buyer = Db.createUser()
        val bundle = Db.createPointBundle(buyer, 10_000, now.minus(day))
        val trades = tradesFor(buyer, Table8.A)
        val key = Flow.newKey()

        // tradeIds를 거꾸로 보내도 항목은 tradeId 오름차순
        val r = Flow.pay(buyer, trades.reversed(), 5_000, key).assertStatus(201)
        assertOrderBody(r, Table8.A, trades, status = "PAID", itemStatus = "PAID")
        val orderId = r.long("orderId")

        val order = Db.order(orderId)
        assertEquals(buyer, order.buyerId)
        assertEquals("PAID", order.status)
        assertEquals(key, order.idempotencyKey)
        assertEquals(now, order.createdAt)
        assertEquals(now, order.paidAt)
        val items = Db.orderItems(orderId)
        assertOrderRows(order, items, Table8.A, trades)
        assertItemIds(r, items)
        items.forEach { assertEquals("PAID", it.status); assertEquals(now, it.paidAt) }
        trades.forEach { assertEquals("PAID", Db.trade(it).status) }

        // pg_transactions: APPROVE 1행
        val tx = Db.pgTransactions(orderId).single()
        assertEquals("APPROVE", tx.kind)
        assertEquals(Table8.A.cardAmount, tx.amount)
        assertEquals("APPROVED", tx.status)
        assertEquals(pg.record(key)?.pgTxId, tx.pgTxId)
        assertEquals(key, tx.idempotencyKey)
        assertEquals(now, tx.requestedAt)
        assertEquals(now, tx.respondedAt)
        assertEquals(listOf(FakePg.ApprovalRequest(key, Table8.A.cardAmount)), pg.approvalRequests(key))

        // 포인트: USE CONFIRMED + 배분 + 묶음 차감
        val use = Db.useEntries(orderId).single()
        assertEquals(buyer, use.userId)
        assertEquals("CONFIRMED", use.status)
        assertEquals(5_000L, use.amount)
        assertNull(use.orderItemId)
        assertNull(use.expiresAt)
        assertNull(use.remaining)
        assertEquals(now, use.createdAt)
        val alloc = Db.allocations(use.id).single()
        assertEquals(bundle.earnId, alloc.earnEntryId)
        assertEquals(5_000L, alloc.amount)
        assertEquals(5_000L, Db.ledgerEntry(bundle.earnId).remaining)

        // 적립 예정: 항목마다 EARN PENDING
        items.zip(Table8.A.items).forEach { (item, exp) ->
            val earn = Db.earnOfItem(item.id).single()
            assertEquals(buyer, earn.userId)
            assertEquals("PENDING", earn.status)
            assertEquals(exp.earn, earn.amount)
            assertEquals(exp.earn, earn.remaining)
            assertNull(earn.expiresAt)
            assertNull(earn.orderId)
            assertEquals(now, earn.createdAt)
        }
        val bal = Db.balance(buyer)!!
        assertEquals(5_000L, bal.available)
        assertEquals(Table8.A.items.sumOf { it.earn }, bal.pending)
        assertEquals(now, bal.updatedAt)

        val pts = Api.get("/me/points", buyer).assertStatus(200)
        assertEquals(5_000L, pts.long("available"))
        assertEquals(Table8.A.items.sumOf { it.earn }, pts.long("pending"))
        assertEquals(5_000L, pts.long("usable"))
    }

    @Test
    fun `승인 - 예 B(최소 수수료·배송비·포인트 1,000) 금액, 묶음 전부 사용`() {
        val now = Flow.now()
        val buyer = Db.createUser()
        val bundle = Db.createPointBundle(buyer, 1_000, now.minus(day))
        val trades = tradesFor(buyer, Table8.B)

        val r = Flow.pay(buyer, trades, 1_000).assertStatus(201)
        assertOrderBody(r, Table8.B, trades, status = "PAID", itemStatus = "PAID")
        val orderId = r.long("orderId")
        val items = Db.orderItems(orderId)
        assertOrderRows(Db.order(orderId), items, Table8.B, trades)
        assertEquals(Table8.B.items[0].earn, Db.earnOfItem(items[0].id).single().amount)
        assertEquals(0L, Db.ledgerEntry(bundle.earnId).remaining)
        assertEquals(1_000L, Db.allocations(Db.useEntries(orderId).single().id).single().amount)
        val bal = Db.balance(buyer)!!
        assertEquals(0L, bal.available)
        assertEquals(Table8.B.items[0].earn, bal.pending)
    }

    @Test
    fun `승인 - 예 C(포인트 0) 금액, USE 행 없음, 잔액 행은 처음 바뀔 때 생성`() {
        val now = Flow.now()
        val buyer = Db.createUser()
        val trades = tradesFor(buyer, Table8.C)
        assertNull(Db.balance(buyer))

        val r = Flow.pay(buyer, trades, 0).assertStatus(201)
        assertOrderBody(r, Table8.C, trades, status = "PAID", itemStatus = "PAID")
        val orderId = r.long("orderId")
        val items = Db.orderItems(orderId)
        assertOrderRows(Db.order(orderId), items, Table8.C, trades)
        assertTrue(Db.useEntries(orderId).isEmpty(), "pointToUse 0이면 USE 행 없음")
        assertEquals(Table8.C.items[0].earn, Db.earnOfItem(items[0].id).single().amount)
        val bal = Db.balance(buyer)!!
        assertEquals(0L, bal.available)
        assertEquals(Table8.C.items[0].earn, bal.pending)
        assertEquals(now, bal.updatedAt)
    }

    @Test
    fun `승인 - 예 D(항목 합 100,000 → 배송비 0, pointToUse 생략 = 0)`() {
        val buyer = Db.createUser()
        val trades = tradesFor(buyer, Table8.D)
        val r = Flow.pay(buyer, trades, pointToUse = null).assertStatus(201)
        assertOrderBody(r, Table8.D, trades, status = "PAID", itemStatus = "PAID")
        val orderId = r.long("orderId")
        val items = Db.orderItems(orderId)
        assertOrderRows(Db.order(orderId), items, Table8.D, trades)
        assertEquals(Table8.D.items[0].earn, Db.earnOfItem(items[0].id).single().amount)
        assertTrue(Db.useEntries(orderId).isEmpty())
    }

    @Test
    fun `거절 - 주문 FAILED, 항목 PAYMENT_FAILED, 체결 그대로, PG DECLINED 행`() {
        val now = Flow.now()
        val buyer = Db.createUser()
        val trades = tradesFor(buyer, Table8.C)
        val key = Flow.newKey()
        Flow.pgOutcome(key, FakePg.Outcome.DECLINE)

        val r = Flow.pay(buyer, trades, 0, key).assertStatus(201)
        assertOrderBody(r, Table8.C, trades, status = "FAILED", itemStatus = "PAYMENT_FAILED")
        val orderId = r.long("orderId")
        val order = Db.order(orderId)
        assertEquals("FAILED", order.status)
        assertNull(order.paidAt)
        assertEquals(now, order.createdAt)
        Db.orderItems(orderId).forEach { assertEquals("PAYMENT_FAILED", it.status); assertNull(it.paidAt) }
        assertEquals("AWAITING_PAYMENT", Db.trade(trades[0]).status)
        val tx = Db.pgTransactions(orderId).single()
        assertEquals("APPROVE", tx.kind)
        assertEquals("DECLINED", tx.status)
        assertNull(tx.pgTxId)
        assertEquals(Table8.C.cardAmount, tx.amount)
        assertEquals(key, tx.idempotencyKey)
        assertEquals(now, tx.requestedAt)
        assertEquals(now, tx.respondedAt)
        assertTrue(Db.earnOfItem(Db.orderItems(orderId)[0].id).isEmpty(), "거절이면 적립 예정 없음")

        // 기한 안이면 새 키로 다시 결제할 수 있다
        val retry = Flow.pay(buyer, trades, 0).assertStatus(201)
        assertEquals("PAID", retry.text("status"))
        assertEquals("PAID", Db.trade(trades[0]).status)
        assertEquals(listOf("PAYMENT_FAILED", "PAID"), Db.orderItemsOfTrade(trades[0]).map { it.status })
    }

    @Test
    fun `거절 - 포인트 해제(USE RELEASED, 배분 삭제, 묶음·잔액 되돌림)`() {
        val now = Flow.now()
        val buyer = Db.createUser()
        val bundle = Db.createPointBundle(buyer, 1_000, now.minus(day))
        val trades = tradesFor(buyer, Table8.B)
        val key = Flow.newKey()
        Flow.pgOutcome(key, FakePg.Outcome.DECLINE)

        val r = Flow.pay(buyer, trades, 1_000, key).assertStatus(201)
        assertOrderBody(r, Table8.B, trades, status = "FAILED", itemStatus = "PAYMENT_FAILED")
        val orderId = r.long("orderId")
        val use = Db.useEntries(orderId).single()
        assertEquals("RELEASED", use.status)
        assertEquals(1_000L, use.amount)
        assertTrue(Db.allocations(use.id).isEmpty(), "해제된 USE의 배분 행은 지운다")
        assertEquals(1_000L, Db.ledgerEntry(bundle.earnId).remaining)
        val bal = Db.balance(buyer)!!
        assertEquals(1_000L, bal.available)
        assertEquals(0L, bal.pending)
    }

    @Test
    fun `타임아웃(승인 기록됨) - UNKNOWN·예약 유지, 결제 불가, pg-inquiry로 PAID(paid_at = 요청 시각), 같은 키 재요청 200 PAID`() {
        val now = Flow.now()
        val buyer = Db.createUser()
        val bundle = Db.createPointBundle(buyer, 1_000, now.minus(day))
        val trades = tradesFor(buyer, Table8.B)
        val key = Flow.newKey()
        Flow.pgOutcome(key, FakePg.Outcome.TIMEOUT_APPROVED)

        val r = Flow.pay(buyer, trades, 1_000, key).assertStatus(201)
        assertOrderBody(r, Table8.B, trades, status = "UNKNOWN", itemStatus = "AWAITING_PAYMENT")
        val orderId = r.long("orderId")
        assertEquals("UNKNOWN", Db.order(orderId).status)
        assertNull(Db.order(orderId).paidAt)
        Db.orderItems(orderId).forEach { assertEquals("AWAITING_PAYMENT", it.status); assertNull(it.paidAt) }
        assertEquals("AWAITING_PAYMENT", Db.trade(trades[0]).status)
        val approve = Db.pgTransactions(orderId).single()
        assertEquals("APPROVE", approve.kind)
        assertEquals("UNKNOWN", approve.status)
        assertNull(approve.pgTxId)
        assertNull(approve.respondedAt)
        assertEquals(now, approve.requestedAt)
        assertEquals(Table8.B.cardAmount, approve.amount)
        // 예약 유지: 배분이 이미 있고 묶음·잔액에서 빠져 있다(ADR-0002 §9.6)
        val use = Db.useEntries(orderId).single()
        assertEquals("RESERVED", use.status)
        assertEquals(1_000L, Db.allocations(use.id).sumOf { it.amount })
        assertEquals(0L, Db.ledgerEntry(bundle.earnId).remaining)
        assertEquals(0L, Db.balance(buyer)!!.available)
        assertTrue(Db.earnOfItem(Db.orderItems(orderId)[0].id).isEmpty())
        assertEquals(0L, Api.get("/me/points", buyer).assertStatus(200).long("usable"))

        // UNKNOWN 주문의 체결은 확정 전까지 결제 불가
        Flow.pay(buyer, trades, 0).assertError(409, "TRADE_NOT_PAYABLE")
        // 같은 키 재요청: 현재 상태 UNKNOWN, PG 호출 없음
        val again = Flow.pay(buyer, trades, 1_000, key).assertStatus(200)
        assertEquals(orderId, again.long("orderId"))
        assertEquals("UNKNOWN", again.text("status"))
        assertEquals(1, pg.approvalRequests(key).size)

        // 재조회 배치
        val later = Flow.advance(Duration.ofMinutes(5))
        Flow.runBatch("pg-inquiry")
        val order = Db.order(orderId)
        assertEquals("PAID", order.status)
        assertEquals(now, order.paidAt, "paid_at = APPROVE 행의 requested_at")
        Db.orderItems(orderId).forEach { assertEquals("PAID", it.status); assertEquals(now, it.paidAt) }
        assertEquals("PAID", Db.trade(trades[0]).status)
        val txs = Db.pgTransactions(orderId)
        assertEquals(listOf("APPROVE" to "UNKNOWN", "INQUIRY" to "APPROVED"), txs.map { it.kind to it.status })
        val inquiry = txs[1]
        assertEquals(Table8.B.cardAmount, inquiry.amount)
        assertEquals(key, inquiry.idempotencyKey)
        assertEquals(later, inquiry.requestedAt)
        assertEquals(later, inquiry.respondedAt)
        assertEquals(pg.record(key)?.pgTxId, inquiry.pgTxId)
        assertEquals("CONFIRMED", Db.ledgerEntry(use.id).status)
        assertEquals(1_000L, Db.allocations(use.id).sumOf { it.amount })
        val earn = Db.earnOfItem(Db.orderItems(orderId)[0].id).single()
        assertEquals("PENDING", earn.status)
        assertEquals(Table8.B.items[0].earn, earn.amount)
        assertEquals(now, earn.createdAt, "EARN created_at = 재조회 승인 시각(APPROVE requested_at)")
        assertEquals(Table8.B.items[0].earn, Db.balance(buyer)!!.pending)

        // 같은 키 재요청: 그 주문의 현재 상태(PAID)를 200으로(ADR-0003 §9-4)
        val retry = Flow.pay(buyer, trades, 1_000, key).assertStatus(200)
        assertEquals(orderId, retry.long("orderId"))
        assertOrderBody(retry, Table8.B, trades, status = "PAID", itemStatus = "PAID")
        assertEquals(1, pg.approvalRequests(key).size)
        assertEquals(2, Db.pgTransactions(orderId).size)
    }

    @Test
    fun `타임아웃(기록 안 됨) - UNKNOWN, pg-inquiry 404 → INQUIRY DECLINED·거절 처리·포인트 해제, 다시 결제 가능`() {
        val now = Flow.now()
        val buyer = Db.createUser()
        val bundle = Db.createPointBundle(buyer, 1_000, now.minus(day))
        val trades = tradesFor(buyer, Table8.B)
        val key = Flow.newKey()
        Flow.pgOutcome(key, FakePg.Outcome.TIMEOUT_NOT_RECEIVED)

        val r = Flow.pay(buyer, trades, 1_000, key).assertStatus(201)
        assertEquals("UNKNOWN", r.text("status"))
        val orderId = r.long("orderId")
        val approve = Db.pgTransactions(orderId).single()
        assertEquals("UNKNOWN", approve.status)
        assertNull(approve.respondedAt)

        val later = Flow.advance(Duration.ofMinutes(5))
        Flow.runBatch("pg-inquiry")
        val order = Db.order(orderId)
        assertEquals("FAILED", order.status)
        assertNull(order.paidAt)
        Db.orderItems(orderId).forEach { assertEquals("PAYMENT_FAILED", it.status) }
        assertEquals("AWAITING_PAYMENT", Db.trade(trades[0]).status)
        val inquiry = Db.pgTransactions(orderId).last()
        assertEquals("INQUIRY", inquiry.kind)
        assertEquals("DECLINED", inquiry.status)
        assertNull(inquiry.pgTxId)
        assertEquals(Table8.B.cardAmount, inquiry.amount)
        assertEquals(key, inquiry.idempotencyKey)
        assertEquals(later, inquiry.requestedAt)
        assertEquals(later, inquiry.respondedAt)
        assertEquals(2, Db.pgTransactions(orderId).size)
        val use = Db.useEntries(orderId).single()
        assertEquals("RELEASED", use.status)
        assertTrue(Db.allocations(use.id).isEmpty())
        assertEquals(1_000L, Db.ledgerEntry(bundle.earnId).remaining)
        assertEquals(1_000L, Db.balance(buyer)!!.available)

        val retry = Flow.pay(buyer, trades, 1_000).assertStatus(201)
        assertEquals("PAID", retry.text("status"))
    }

    @Test
    fun `pg-inquiry - PG 재조회도 응답 없음이면 INQUIRY 행 없이 UNKNOWN 유지`() {
        val buyer = Db.createUser()
        val trades = tradesFor(buyer, Table8.C)
        val key = Flow.newKey()
        Flow.pgOutcome(key, FakePg.Outcome.TIMEOUT_APPROVED)
        val orderId = Flow.pay(buyer, trades, 0, key).assertStatus(201).long("orderId")
        assertEquals("UNKNOWN", Db.order(orderId).status)

        Flow.pgInquiryTimeout(key)
        Flow.advance(Duration.ofMinutes(1))
        Flow.runBatch("pg-inquiry")
        assertTrue(pg.inquiryCount(key) >= 1, "재조회를 시도해야 한다")
        assertEquals("UNKNOWN", Db.order(orderId).status)
        assertEquals(listOf("APPROVE"), Db.pgTransactions(orderId).map { it.kind })
        assertEquals("AWAITING_PAYMENT", Db.trade(trades[0]).status)
    }

    @Test
    fun `주문 조회 - 구매자 200 같은 모양, 남의 주문 403, 없는 주문 404, 헤더 없음 401`() {
        val buyer = Db.createUser()
        val trades = tradesFor(buyer, Table8.C)
        val created = Flow.pay(buyer, trades, 0).assertStatus(201)
        val orderId = created.long("orderId")

        val r = Api.get("/orders/$orderId", buyer).assertStatus(200)
        assertEquals(orderId, r.long("orderId"))
        assertOrderBody(r, Table8.C, trades, status = "PAID", itemStatus = "PAID")
        assertItemIds(r, Db.orderItems(orderId))
        assertEquals(created.json, r.json, "조회 = §5.1 응답과 같은 모양·값")

        Api.get("/orders/$orderId", Db.createUser()).assertError(403, "NOT_OWNER")
        Api.get("/orders/${Db.missingId("orders")}", buyer).assertError(404, "NOT_FOUND")
        Api.get("/orders/$orderId").assertError(401, "UNKNOWN_USER")
    }

    @Test
    fun `포인트 조회 - 행 없으면 0, 헤더 없음 401`() {
        val user = Db.createUser()
        val r = Api.get("/me/points", user).assertStatus(200)
        assertEquals(0L, r.long("available"))
        assertEquals(0L, r.long("pending"))
        assertEquals(0L, r.long("usable"))
        Api.get("/me/points").assertError(401, "UNKNOWN_USER")
        assertNotNull(Db.user(user))
    }
}
