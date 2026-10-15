package com.ccle.behavior.contract

import com.ccle.behavior.support.Api
import com.ccle.behavior.support.ContractTest
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.Resp
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ADR-0003 §3.1 제한, §3.3 동시성. */
@ContractTest
class RestrictionAndConcurrencyTest {

    @Test
    fun `구매 제한 중 - 구매 입찰·즉시 구매 403 PURCHASE_RESTRICTED, 판매는 허용`() {
        val now = Flow.now()
        val size = Db.createProductSize()
        Flow.activeBid(Db.createUser(), size, "SELL", 100_000)
        val user = Db.createUser(purchaseRestrictedUntil = now.plus(Duration.ofDays(7)))

        Flow.bid(user, size, "BUY", 90_000).assertError(403, "PURCHASE_RESTRICTED")
        Flow.instant(user, size, "BUY").assertError(403, "PURCHASE_RESTRICTED")
        assertTrue(Db.bidsOfUser(user).isEmpty())
        Flow.bid(user, size, "SELL", 130_000).assertStatus(201)
    }

    @Test
    fun `판매 제한 중 - 판매 입찰·즉시 판매 403 SALE_RESTRICTED, 구매는 허용`() {
        val now = Flow.now()
        val size = Db.createProductSize()
        Flow.activeBid(Db.createUser(), size, "BUY", 100_000)
        val user = Db.createUser(saleRestrictedUntil = now.plus(Duration.ofDays(30)))

        Flow.bid(user, size, "SELL", 120_000).assertError(403, "SALE_RESTRICTED")
        Flow.instant(user, size, "SELL").assertError(403, "SALE_RESTRICTED")
        assertTrue(Db.bidsOfUser(user).isEmpty())
        Flow.bid(user, size, "BUY", 80_000).assertStatus(201)
    }

    @Test
    fun `제한 경계 - 제한 시각 = 현재이면 허용(제한은 restricted_until이 현재보다 뒤일 때)`() {
        val now = Flow.now()
        val size = Db.createProductSize()
        val user = Db.createUser(purchaseRestrictedUntil = now, saleRestrictedUntil = now.minus(Duration.ofDays(1)))
        Flow.bid(user, size, "BUY", 90_000).assertStatus(201)
        Flow.bid(user, size, "SELL", 120_000).assertStatus(201)
    }

    @Test
    fun `제한은 새 입찰에만 - 이미 걸린 입찰의 체결과 결제는 막지 않는다`() {
        val now = Flow.now()
        val size = Db.createProductSize()
        val buyer = Db.createUser()
        val seller = Db.createUser()
        val buy = Flow.activeBid(buyer, size, "BUY", 100_000)
        val sell = Flow.activeBid(seller, size, "SELL", 130_000)
        Db.setRestrictions(buyer, now.plus(Duration.ofDays(7)), null)
        Db.setRestrictions(seller, null, now.plus(Duration.ofDays(7)))

        // 제한된 구매자의 기존 구매 입찰이 체결된다
        val r = Flow.bid(Db.createUser(), size, "SELL", 100_000).assertStatus(201)
        assertEquals("MATCHED", r.text("status"))
        val tradeId = r.long("tradeId")
        assertEquals(buy, Db.trade(tradeId).buyBidId)
        // 제한된 판매자의 기존 판매 입찰이 체결된다
        val r2 = Flow.bid(Db.createUser(), size, "BUY", 130_000).assertStatus(201)
        assertEquals(sell, Db.trade(r2.long("tradeId")).sellBidId)
        // 제한된 구매자가 결제한다
        val pay = Flow.pay(buyer, listOf(tradeId)).assertStatus(201)
        assertEquals("PAID", pay.text("status"))
    }

    @Test
    fun `판정 순서 - 400·404가 403보다, 403이 409(NO_COUNTER_BID)보다 먼저`() {
        val now = Flow.now()
        val user = Db.createUser(purchaseRestrictedUntil = now.plus(Duration.ofDays(7)))
        val size = Db.createProductSize()
        Flow.bid(user, size, "BUY", 0).assertError(400, "INVALID_REQUEST")
        Flow.bid(user, Db.missingId("product_sizes"), "BUY", 100_000).assertError(404, "NOT_FOUND")
        Flow.instant(user, size, "BUY").assertError(403, "PURCHASE_RESTRICTED") // 상대 없음보다 먼저
    }

    // ---- 동시성 ----

    private fun concurrently(n: Int, call: (Int) -> Resp): List<Resp> {
        val pool = Executors.newFixedThreadPool(n)
        val ready = CountDownLatch(n)
        val go = CountDownLatch(1)
        try {
            val futures = (0 until n).map { i ->
                pool.submit<Resp> {
                    ready.countDown()
                    go.await()
                    call(i)
                }
            }
            ready.await(10, TimeUnit.SECONDS)
            go.countDown()
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `동시 즉시 구매 - 상대 1개면 체결 1건, 나머지 409 NO_COUNTER_BID, 5xx 없음`() {
        val size = Db.createProductSize()
        val sell = Flow.activeBid(Db.createUser(), size, "SELL", 100_000)
        val buyers = (1..8).map { Db.createUser() }

        val rs = concurrently(buyers.size) { i -> Flow.instant(buyers[i], size, "BUY") }
        assertTrue(rs.all { it.status == 201 || it.status == 409 }, "201 또는 409만: $rs")
        assertEquals(1, rs.count { it.status == 201 }, "체결 1건: $rs")
        rs.filter { it.status == 409 }.forEach { it.assertError(409, "NO_COUNTER_BID") }
        assertEquals(1, Db.liveTradesOfBid(sell).size)
        assertEquals("MATCHED", Db.bid(sell).status)
        assertEquals(1, Db.tradesOfSize(size).size)
    }

    @Test
    fun `동시 구매 입찰 - 같은 상대를 노려도 그 상대와의 체결은 1건, 모두 201`() {
        val size = Db.createProductSize()
        val sell = Flow.activeBid(Db.createUser(), size, "SELL", 100_000)
        val buyers = (1..8).map { Db.createUser() }

        val rs = concurrently(buyers.size) { i -> Flow.bid(buyers[i], size, "BUY", 110_000) }
        rs.forEach { it.assertStatus(201) }
        assertEquals(1, rs.count { it.text("status") == "MATCHED" }, rs.toString())
        assertEquals(1, Db.liveTradesOfBid(sell).size)
        // 불변식: 모든 입찰은 살아 있는 체결 최대 1건, MATCHED ⇔ 1건
        Db.bidsOfSize(size).forEach { b ->
            val live = Db.liveTradesOfBid(b.id).size
            assertTrue(live <= 1)
            assertEquals(b.status == "MATCHED", live == 1, "입찰 ${b.id}")
        }
    }

    @Test
    fun `동시 판매 입찰 - 구매 입찰 1개에 체결 1건`() {
        val size = Db.createProductSize()
        val buy = Flow.activeBid(Db.createUser(), size, "BUY", 100_000)
        val sellers = (1..8).map { Db.createUser() }

        val rs = concurrently(sellers.size) { i -> Flow.bid(sellers[i], size, "SELL", 95_000) }
        rs.forEach { it.assertStatus(201) }
        assertEquals(1, Db.liveTradesOfBid(buy).size)
        assertEquals(1, Db.tradesOfSize(size).size)
        Db.bidsOfSize(size).forEach { b -> assertTrue(Db.liveTradesOfBid(b.id).size <= 1) }
    }

    @Test
    fun `동시 즉시 판매 - 상대 2개면 체결 2건, 상대마다 1건`() {
        val size = Db.createProductSize()
        val b1 = Flow.activeBid(Db.createUser(), size, "BUY", 100_000)
        val b2 = Flow.activeBid(Db.createUser(), size, "BUY", 99_000)
        val sellers = (1..6).map { Db.createUser() }

        val rs = concurrently(sellers.size) { i -> Flow.instant(sellers[i], size, "SELL") }
        assertTrue(rs.all { it.status == 201 || it.status == 409 }, rs.toString())
        assertEquals(2, rs.count { it.status == 201 })
        rs.filter { it.status == 409 }.forEach { it.assertError(409, "NO_COUNTER_BID") }
        assertEquals(1, Db.liveTradesOfBid(b1).size)
        assertEquals(1, Db.liveTradesOfBid(b2).size)
        // 체결 응답은 404(라우트 없음)가 아니다
        assertTrue(rs.none { it.status == 404 })
        Api.get("/product-sizes/$size/book").assertStatus(200)
    }
}
