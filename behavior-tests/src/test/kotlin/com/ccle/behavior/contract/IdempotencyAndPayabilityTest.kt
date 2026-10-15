package com.ccle.behavior.contract

import com.ccle.behavior.pg.FakePg
import com.ccle.behavior.support.Api
import com.ccle.behavior.support.ContractTest
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.FakePgExtension
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.Resp
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ADR-0003 §5.1 멱등·결제 가능 체결·요청 검증, §2 판정 순서. */
@ContractTest
class IdempotencyAndPayabilityTest {
    private val pg get() = FakePgExtension.pg

    // ---- 멱등 ----

    @Test
    fun `같은 키·같은 요청 - 200 현재 상태, 아무것도 바꾸지 않음(PG 호출 없음)`() {
        val buyer = Db.createUser()
        val trades = tradesFor(buyer, Table8.C)
        val key = Flow.newKey()
        val first = Flow.pay(buyer, trades, 0, key).assertStatus(201)
        val orderId = first.long("orderId")

        val again = Flow.pay(buyer, trades, 0, key).assertStatus(200)
        assertEquals(orderId, again.long("orderId"))
        assertOrderBody(again, Table8.C, trades, status = "PAID", itemStatus = "PAID")
        assertEquals(1, Db.ordersByKey(key).size)
        assertEquals(1, Db.orderItemsOfTrade(trades[0]).size)
        assertEquals(1, Db.pgTransactions(orderId).size)
        assertEquals(1, pg.approvalRequests(key).size)
        assertEquals(1, Db.earnOfItem(Db.orderItems(orderId)[0].id).size)
    }

    @Test
    fun `같은 요청의 정의 - tradeIds는 집합, pointToUse 생략 = 0`() {
        val buyer = Db.createUser()
        val size = Db.createProductSize()
        val t1 = Flow.trade(buyer, Db.createUser(), size, 50_000)
        val t2 = Flow.trade(buyer, Db.createUser(), size, 60_000)
        val key = Flow.newKey()
        val orderId = Flow.pay(buyer, listOf(t1, t2), 0, key).assertStatus(201).long("orderId")

        assertEquals(orderId, Flow.pay(buyer, listOf(t2, t1), null, key).assertStatus(200).long("orderId"))
        assertEquals(1, Db.ordersByKey(key).size)
        assertEquals(1, pg.approvalRequests(key).size)
    }

    @Test
    fun `같은 키·다른 요청 - 409 IDEMPOTENCY_KEY_REUSED (tradeIds 다름·pointToUse 다름)`() {
        val now = Flow.now()
        val buyer = Db.createUser()
        Db.createPointBundle(buyer, 5_000, now.minus(Duration.ofDays(1)))
        val size = Db.createProductSize()
        val t1 = Flow.trade(buyer, Db.createUser(), size, 30_000)
        val t2 = Flow.trade(buyer, Db.createUser(), size, 30_000)
        val key = Flow.newKey()
        val orderId = Flow.pay(buyer, listOf(t1), 0, key).assertStatus(201).long("orderId")

        Flow.pay(buyer, listOf(t1, t2), 0, key).assertError(409, "IDEMPOTENCY_KEY_REUSED")
        Flow.pay(buyer, listOf(t2), 0, key).assertError(409, "IDEMPOTENCY_KEY_REUSED")
        Flow.pay(buyer, listOf(t1), 1_000, key).assertError(409, "IDEMPOTENCY_KEY_REUSED")
        assertEquals(listOf(orderId), Db.ordersByKey(key).map { it.id })
        assertEquals("AWAITING_PAYMENT", Db.trade(t2).status)
        assertEquals(1, pg.approvalRequests(key).size)
    }

    @Test
    fun `같은 키·다른 구매자 - 409 IDEMPOTENCY_KEY_REUSED (멱등 키 조회가 404·403보다 먼저)`() {
        val buyer = Db.createUser()
        val trades = tradesFor(buyer, Table8.C)
        val key = Flow.newKey()
        Flow.pay(buyer, trades, 0, key).assertStatus(201)

        val other = Db.createUser()
        val otherTrades = tradesFor(other, Table8.D)
        Flow.pay(other, otherTrades, 0, key).assertError(409, "IDEMPOTENCY_KEY_REUSED")
        Flow.pay(other, trades, 0, key).assertError(409, "IDEMPOTENCY_KEY_REUSED")
        Flow.pay(other, listOf(Db.missingId("trades")), 0, key).assertError(409, "IDEMPOTENCY_KEY_REUSED")
        assertEquals("AWAITING_PAYMENT", Db.trade(otherTrades[0]).status)
        assertEquals(1, Db.ordersByKey(key).size)
    }

    @Test
    fun `거절된 주문의 같은 키 재요청 - 200 FAILED, PG 다시 부르지 않음`() {
        val buyer = Db.createUser()
        val trades = tradesFor(buyer, Table8.C)
        val key = Flow.newKey()
        Flow.pgOutcome(key, FakePg.Outcome.DECLINE)
        val orderId = Flow.pay(buyer, trades, 0, key).assertStatus(201).long("orderId")

        val again = Flow.pay(buyer, trades, 0, key).assertStatus(200)
        assertEquals(orderId, again.long("orderId"))
        assertEquals("FAILED", again.text("status"))
        assertEquals(1, pg.approvalRequests(key).size)
        assertEquals(1, Db.pgTransactions(orderId).size)
        assertEquals(1, Db.orderItemsOfTrade(trades[0]).size)
    }

    @Test
    fun `같은 키 동시 요청 - 주문 1건, 하나는 201·나머지는 같은 주문 200`() {
        val buyer = Db.createUser()
        val trades = tradesFor(buyer, Table8.C)
        val key = Flow.newKey()
        val n = 6
        val pool = Executors.newFixedThreadPool(n)
        val go = CountDownLatch(1)
        val rs: List<Resp> = try {
            val fs = (1..n).map { pool.submit<Resp> { go.await(); Flow.pay(buyer, trades, 0, key) } }
            go.countDown()
            fs.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, rs.count { it.status == 201 }, rs.toString())
        assertEquals(n - 1, rs.count { it.status == 200 }, rs.toString())
        assertEquals(1, rs.map { it.long("orderId") }.toSet().size)
        assertEquals(1, Db.ordersByKey(key).size)
        assertEquals(1, Db.orderItemsOfTrade(trades[0]).size)
        assertEquals(1, pg.approvalRequests(key).size)
    }

    // ---- 결제 가능 체결 ----

    @Test
    fun `이미 PAID인 체결 - 새 키로 409 TRADE_NOT_PAYABLE`() {
        val buyer = Db.createUser()
        val trades = tradesFor(buyer, Table8.C)
        Flow.pay(buyer, trades, 0).assertStatus(201)
        Flow.pay(buyer, trades, 0).assertError(409, "TRADE_NOT_PAYABLE")
        assertEquals(1, Db.orderItemsOfTrade(trades[0]).size)
    }

    @Test
    fun `결제 기한 - 기한 1초 전 결제 가능, 기한 시각(현재 = payment_due_at)부터 409`() {
        val buyer = Db.createUser()
        val size = Db.createProductSize()
        val t1 = Flow.trade(buyer, Db.createUser(), size, 99_000)
        val t2 = Flow.trade(buyer, Db.createUser(), size, 99_000)
        val due = Db.trade(t1).paymentDueAt
        assertEquals(due, Db.trade(t2).paymentDueAt)

        Flow.moveTo(due.minusSeconds(1))
        Flow.pay(buyer, listOf(t1), 0).assertStatus(201)
        Flow.moveTo(due)
        Flow.pay(buyer, listOf(t2), 0).assertError(409, "TRADE_NOT_PAYABLE")
        assertTrue(Db.orderItemsOfTrade(t2).isEmpty())
    }

    @Test
    fun `결제 가능 + 불가 섞임 - 409, 결제 가능 체결은 그대로 결제할 수 있다`() {
        val buyer = Db.createUser()
        val size = Db.createProductSize()
        val paid = Flow.trade(buyer, Db.createUser(), size, 40_000)
        Flow.pay(buyer, listOf(paid), 0).assertStatus(201)
        val open = Flow.trade(buyer, Db.createUser(), size, 40_000)

        Flow.pay(buyer, listOf(paid, open), 0).assertError(409, "TRADE_NOT_PAYABLE")
        assertTrue(Db.orderItemsOfTrade(open).isEmpty())
        Flow.pay(buyer, listOf(open), 0).assertStatus(201)
    }

    @Test
    fun `남이 구매자인 체결 - 403 NOT_OWNER (판매자 본인 포함)`() {
        val buyer = Db.createUser()
        val seller = Db.createUser()
        val t = Flow.trade(buyer, seller, Db.createProductSize(), 50_000)
        Flow.pay(seller, listOf(t), 0).assertError(403, "NOT_OWNER")
        Flow.pay(Db.createUser(), listOf(t), 0).assertError(403, "NOT_OWNER")
        assertTrue(Db.orderItemsOfTrade(t).isEmpty())
    }

    @Test
    fun `여러 체결 - 하나라도 없으면 404, 모두 있고 하나라도 남의 것이면 403`() {
        val buyer = Db.createUser()
        val size = Db.createProductSize()
        val mine = Flow.trade(buyer, Db.createUser(), size, 50_000)
        val others = Flow.trade(Db.createUser(), Db.createUser(), size, 50_000)
        val missing = Db.missingId("trades")
        Flow.pay(buyer, listOf(mine, missing), 0).assertError(404, "NOT_FOUND")
        Flow.pay(buyer, listOf(others, missing), 0).assertError(404, "NOT_FOUND")
        Flow.pay(buyer, listOf(mine, others), 0).assertError(403, "NOT_OWNER")
        assertTrue(Db.orderItemsOfTrade(mine).isEmpty())
    }

    @Test
    fun `판정 순서 - 403이 409보다, 409가 422보다 먼저`() {
        val buyer = Db.createUser()
        val size = Db.createProductSize()
        val paid = Flow.trade(buyer, Db.createUser(), size, 30_000)
        Flow.pay(buyer, listOf(paid), 0).assertStatus(201)
        val others = Flow.trade(Db.createUser(), Db.createUser(), size, 30_000)
        Flow.pay(buyer, listOf(paid, others), 0).assertError(403, "NOT_OWNER")
        Flow.pay(buyer, listOf(paid), 995).assertError(409, "TRADE_NOT_PAYABLE")
    }

    // ---- 요청 검증 ----

    @Test
    fun `요청 형식 오류 - 400 INVALID_REQUEST`() {
        val buyer = Db.createUser()
        val t = Flow.trade(buyer, Db.createUser(), Db.createProductSize(), 50_000)
        Flow.pay(buyer, emptyList(), 0).assertError(400, "INVALID_REQUEST")
        Flow.pay(buyer, listOf(t, t), 0).assertError(400, "INVALID_REQUEST")
        Api.post("/orders", mapOf("pointToUse" to 0), buyer, Flow.newKey()).assertError(400, "INVALID_REQUEST")
        Api.request("POST", "/orders", rawBody = """{"tradeIds":"x"}""", userId = buyer, idempotencyKey = Flow.newKey())
            .assertError(400, "INVALID_REQUEST")
        assertTrue(Db.orderItemsOfTrade(t).isEmpty())
    }

    @Test
    fun `Idempotency-Key - 누락·빈 값·65자 400, 64자는 허용`() {
        val buyer = Db.createUser()
        val t = Flow.trade(buyer, Db.createUser(), Db.createProductSize(), 50_000)
        Flow.pay(buyer, listOf(t), 0, key = null).assertError(400, "INVALID_REQUEST")
        Flow.pay(buyer, listOf(t), 0, key = "").assertError(400, "INVALID_REQUEST")
        Flow.pay(buyer, listOf(t), 0, key = "k".repeat(65)).assertError(400, "INVALID_REQUEST")
        assertTrue(Db.orderItemsOfTrade(t).isEmpty())
        val key64 = (Flow.newKey() + "x".repeat(64)).take(64)
        Flow.pay(buyer, listOf(t), 0, key = key64).assertStatus(201)
        assertEquals(1, Db.ordersByKey(key64).size)
    }

    @Test
    fun `판정 순서 - 401이 400보다, 400이 404보다 먼저`() {
        Flow.pay(null, emptyList(), 0, key = null).assertError(401, "UNKNOWN_USER")
        Flow.pay("abc", listOf(1L), 0).assertError(401, "UNKNOWN_USER")
        Flow.pay(Db.missingId("users"), listOf(1L), 0).assertError(401, "UNKNOWN_USER")
        Flow.pay(Db.createUser(), listOf(Db.missingId("trades")), 0, key = null).assertError(400, "INVALID_REQUEST")
    }
}
