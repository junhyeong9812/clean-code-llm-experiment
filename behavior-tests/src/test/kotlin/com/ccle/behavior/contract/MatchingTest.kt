package com.ccle.behavior.contract

import com.ccle.behavior.support.Api
import com.ccle.behavior.support.ContractTest
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Flow
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ADR-0003 §3.1 즉시 주문, §3.3 체결 규칙, §3.4 호가. */
@ContractTest
class MatchingTest {
    private val sec = Duration.ofSeconds(1)

    @Test
    fun `교차 체결 - 판매 먼저, 구매가 ≥ 판매가 → 판매가로 체결, trades·bids·bid_events`() {
        val size = Db.createProductSize()
        val seller = Db.createUser()
        val buyer = Db.createUser()
        val sell = Flow.activeBid(seller, size, "SELL", 100_000)
        val now = Flow.advance(sec)

        val r = Flow.bid(buyer, size, "BUY", 110_000).assertStatus(201)
        assertEquals("MATCHED", r.text("status"))
        assertEquals(100_000L, r.long("tradePrice"))
        val buy = r.long("bidId")
        val t = Db.trade(r.long("tradeId"))
        assertEquals(buy, t.buyBidId)
        assertEquals(sell, t.sellBidId)
        assertEquals(buyer, t.buyerId)
        assertEquals(seller, t.sellerId)
        assertEquals(size, t.productSizeId)
        assertEquals(100_000L, t.price)
        assertEquals("AWAITING_PAYMENT", t.status)
        assertEquals(now, t.matchedAt)
        assertEquals(now.plus(Duration.ofMinutes(30)), t.paymentDueAt)

        assertEquals("MATCHED", Db.bid(buy).status)
        assertEquals("MATCHED", Db.bid(sell).status)
        assertEquals(110_000L, Db.bid(buy).price)

        val buyEv = Db.bidEvents(buy)
        assertEquals(listOf(null to "ACTIVE", "ACTIVE" to "MATCHED"), buyEv.map { it.fromStatus to it.toStatus })
        assertEquals(listOf(null, t.id), buyEv.map { it.tradeId })
        assertTrue(buyEv.all { it.createdAt == now })
        val sellEv = Db.bidEvents(sell)
        assertEquals(listOf(null to "ACTIVE", "ACTIVE" to "MATCHED"), sellEv.map { it.fromStatus to it.toStatus })
        assertEquals(t.id, sellEv[1].tradeId)
        assertEquals(now, sellEv[1].createdAt)
        assertTrue(buyEv[1].id < sellEv[1].id, "MATCHED 이벤트는 구매 입찰 행 먼저")
    }

    @Test
    fun `체결가 - 구매 입찰이 먼저면 구매가`() {
        val size = Db.createProductSize()
        val buyer = Db.createUser()
        val buy = Flow.activeBid(buyer, size, "BUY", 110_000)
        Flow.advance(sec)
        val r = Flow.bid(Db.createUser(), size, "SELL", 100_000).assertStatus(201)
        assertEquals("MATCHED", r.text("status"))
        assertEquals(110_000L, r.long("tradePrice"))
        val t = Db.trade(r.long("tradeId"))
        assertEquals(110_000L, t.price)
        assertEquals(buy, t.buyBidId)
        val buyEv = Db.bidEvents(buy).last()
        val sellEv = Db.bidEvents(r.long("bidId")).last()
        assertTrue(buyEv.id < sellEv.id, "MATCHED 이벤트는 구매 입찰 행 먼저")
    }

    @Test
    fun `체결가 - 같은 시각이면 id가 앞선 입찰의 가격`() {
        val size = Db.createProductSize()
        val buy = Flow.activeBid(Db.createUser(), size, "BUY", 105_000)
        // 시계를 옮기지 않는다: created_at 같음 → id 순
        val r = Flow.bid(Db.createUser(), size, "SELL", 100_000).assertStatus(201)
        assertEquals(105_000L, r.long("tradePrice"))
        assertEquals(buy, Db.trade(r.long("tradeId")).buyBidId)
    }

    @Test
    fun `교차하지 않으면 둘 다 ACTIVE, 같은 가격은 교차`() {
        val size = Db.createProductSize()
        val buy = Flow.activeBid(Db.createUser(), size, "BUY", 90_000)
        val sell = Flow.activeBid(Db.createUser(), size, "SELL", 100_000)
        assertTrue(Db.tradesOfSize(size).isEmpty())
        assertEquals("ACTIVE", Db.bid(buy).status)
        assertEquals("ACTIVE", Db.bid(sell).status)

        val r = Flow.bid(Db.createUser(), size, "BUY", 100_000).assertStatus(201)
        assertEquals("MATCHED", r.text("status"))
        assertEquals(100_000L, r.long("tradePrice"))
        assertEquals(sell, Db.trade(r.long("tradeId")).sellBidId)
        assertEquals("ACTIVE", Db.bid(buy).status)
    }

    @Test
    fun `우선순위 - 새 구매는 판매 최저가, 같은 가격은 created_at, 같은 시각은 id`() {
        val size = Db.createProductSize()
        val s105 = Flow.activeBid(Db.createUser(), size, "SELL", 105_000)
        Flow.advance(sec)
        val s100a = Flow.activeBid(Db.createUser(), size, "SELL", 100_000)
        val s100b = Flow.activeBid(Db.createUser(), size, "SELL", 100_000) // 같은 시각, id 뒤
        Flow.advance(sec)
        val s100c = Flow.activeBid(Db.createUser(), size, "SELL", 100_000) // 늦은 시각
        Flow.advance(sec)

        val matched = (1..4).map {
            val r = Flow.bid(Db.createUser(), size, "BUY", 110_000).assertStatus(201)
            assertEquals("MATCHED", r.text("status"))
            Db.trade(r.long("tradeId")).let { t -> t.sellBidId to t.price }
        }
        assertEquals(listOf(s100a to 100_000L, s100b to 100_000L, s100c to 100_000L, s105 to 105_000L), matched)
    }

    @Test
    fun `우선순위 - 새 판매는 구매 최고가, 같은 가격은 먼저 등록한 입찰`() {
        val size = Db.createProductSize()
        val b95 = Flow.activeBid(Db.createUser(), size, "BUY", 95_000)
        Flow.advance(sec)
        val b100a = Flow.activeBid(Db.createUser(), size, "BUY", 100_000)
        Flow.advance(sec)
        val b100b = Flow.activeBid(Db.createUser(), size, "BUY", 100_000)
        Flow.advance(sec)

        val matched = (1..3).map {
            val r = Flow.bid(Db.createUser(), size, "SELL", 90_000).assertStatus(201)
            Db.trade(r.long("tradeId")).let { t -> t.buyBidId to t.price }
        }
        assertEquals(listOf(b100a to 100_000L, b100b to 100_000L, b95 to 95_000L), matched)
    }

    @Test
    fun `자기 체결 건너뛰기 - 상대가 자신이면 다음 순위와 체결`() {
        val size = Db.createProductSize()
        val me = Db.createUser()
        val other = Db.createUser()
        val mySell = Flow.activeBid(me, size, "SELL", 100_000)
        Flow.advance(sec)
        val otherSell = Flow.activeBid(other, size, "SELL", 101_000)
        Flow.advance(sec)

        val r = Flow.bid(me, size, "BUY", 110_000).assertStatus(201)
        assertEquals("MATCHED", r.text("status"))
        assertEquals(101_000L, r.long("tradePrice"))
        val t = Db.trade(r.long("tradeId"))
        assertEquals(otherSell, t.sellBidId)
        assertEquals(other, t.sellerId)
        assertEquals("ACTIVE", Db.bid(mySell).status)
    }

    @Test
    fun `자기 체결 건너뛰기 - 상대가 자신뿐이면 ACTIVE로 남음`() {
        val size = Db.createProductSize()
        val me = Db.createUser()
        val mySell = Flow.activeBid(me, size, "SELL", 100_000)
        val r = Flow.bid(me, size, "BUY", 110_000).assertStatus(201)
        assertEquals("ACTIVE", r.text("status"))
        assertTrue(r.isNull("tradeId"))
        assertEquals("ACTIVE", Db.bid(mySell).status)
        assertTrue(Db.tradesOfSize(size).isEmpty())
    }

    @Test
    fun `즉시 구매 - 판매 최우선 가격으로 입찰을 만들고 바로 체결`() {
        val size = Db.createProductSize()
        Flow.activeBid(Db.createUser(), size, "SELL", 100_000)
        Flow.advance(sec)
        val best = Flow.activeBid(Db.createUser(), size, "SELL", 95_000)
        val now = Flow.advance(sec)
        val buyer = Db.createUser()

        val r = Flow.instant(buyer, size, "BUY").assertStatus(201)
        assertEquals("MATCHED", r.text("status"))
        assertEquals(95_000L, r.long("tradePrice"))
        val bid = Db.bid(r.long("bidId"))
        assertEquals("BUY", bid.side)
        assertEquals(95_000L, bid.price)
        assertEquals("MATCHED", bid.status)
        assertEquals(now, bid.createdAt)
        assertEquals(now.plus(Duration.ofDays(30)), bid.expiresAt)
        val t = Db.trade(r.long("tradeId"))
        assertEquals(best, t.sellBidId)
        assertEquals(95_000L, t.price)
        assertEquals(listOf(null to "ACTIVE", "ACTIVE" to "MATCHED"), Db.bidEvents(bid.id).map { it.fromStatus to it.toStatus })
    }

    @Test
    fun `즉시 판매 - 구매 최우선 가격으로 체결`() {
        val size = Db.createProductSize()
        Flow.activeBid(Db.createUser(), size, "BUY", 100_000)
        val best = Flow.activeBid(Db.createUser(), size, "BUY", 105_000)
        val now = Flow.advance(sec)

        val r = Flow.instant(Db.createUser(), size, "SELL").assertStatus(201)
        assertEquals("MATCHED", r.text("status"))
        assertEquals(105_000L, r.long("tradePrice"))
        val bid = Db.bid(r.long("bidId"))
        assertEquals("SELL", bid.side)
        assertEquals(105_000L, bid.price)
        assertEquals(now.plus(Duration.ofDays(30)), bid.expiresAt)
        assertEquals(best, Db.trade(r.long("tradeId")).buyBidId)
    }

    @Test
    fun `즉시 주문 - 상대 없음 409 NO_COUNTER_BID, 행 없음`() {
        val size = Db.createProductSize()
        val buyer = Db.createUser()
        Flow.instant(buyer, size, "BUY").assertError(409, "NO_COUNTER_BID")
        Flow.instant(buyer, size, "SELL").assertError(409, "NO_COUNTER_BID")
        // 같은 방향 입찰만 있음
        Flow.activeBid(Db.createUser(), size, "BUY", 100_000)
        Flow.instant(buyer, size, "BUY").assertError(409, "NO_COUNTER_BID")
        // 상대가 자신뿐
        Flow.activeBid(buyer, size, "SELL", 120_000)
        Flow.instant(buyer, size, "BUY").assertError(409, "NO_COUNTER_BID")
        assertEquals(1, Db.bidsOfUser(buyer).size)
        assertTrue(Db.tradesOfSize(size).isEmpty())
    }

    @Test
    fun `만료 시각이 지난 ACTIVE 입찰은 매칭·즉시 가격·호가에서 빠진다`() {
        val size = Db.createProductSize()
        val seller = Db.createUser()
        val sell = Flow.activeBid(seller, size, "SELL", 100_000, 30)
        val expiresAt = Db.bid(sell).expiresAt

        // 만료 1초 전: 아직 체결 가능
        Flow.moveTo(expiresAt.minus(sec))
        Api.get("/product-sizes/$size/book").assertStatus(200).also { assertEquals(100_000L, it.long("bestSell")) }

        // 만료 시각 = 현재: expires_at > 현재가 아니므로 빠진다(배치 전이라 상태는 ACTIVE)
        Flow.moveTo(expiresAt)
        val book = Api.get("/product-sizes/$size/book").assertStatus(200)
        assertTrue(book.isNull("bestSell"), "만료 입찰은 호가에서 빠진다: $book")
        Flow.instant(Db.createUser(), size, "BUY").assertError(409, "NO_COUNTER_BID")
        val r = Flow.bid(Db.createUser(), size, "BUY", 110_000).assertStatus(201)
        assertEquals("ACTIVE", r.text("status"))
        assertEquals("ACTIVE", Db.bid(sell).status)
        assertTrue(Db.tradesOfSize(size).isEmpty())
    }

    @Test
    fun `만료 1초 전 입찰은 체결된다`() {
        val size = Db.createProductSize()
        val sell = Flow.activeBid(Db.createUser(), size, "SELL", 100_000, 30)
        Flow.moveTo(Db.bid(sell).expiresAt.minus(sec))
        val r = Flow.bid(Db.createUser(), size, "BUY", 100_000).assertStatus(201)
        assertEquals("MATCHED", r.text("status"))
        assertEquals(sell, Db.trade(r.long("tradeId")).sellBidId)
    }

    // ---- §3.4 호가 ----

    @Test
    fun `호가 - 헤더 불필요, 빈 사이즈는 null·null`() {
        val r = Api.get("/product-sizes/${Db.createProductSize()}/book").assertStatus(200)
        assertTrue(r.isNull("bestBuy") && r.isNull("bestSell"), r.toString())
    }

    @Test
    fun `호가 - 체결 가능한 입찰 기준 최고 구매가·최저 판매가 (취소·체결 제외)`() {
        val size = Db.createProductSize()
        val u = Db.createUser()
        Flow.activeBid(Db.createUser(), size, "BUY", 90_000)
        Flow.activeBid(Db.createUser(), size, "BUY", 95_000)
        val cancelled = Flow.activeBid(u, size, "BUY", 99_000)
        Flow.cancel(u, cancelled).assertStatus(200)
        Flow.activeBid(Db.createUser(), size, "SELL", 120_000)
        Flow.activeBid(Db.createUser(), size, "SELL", 100_000)
        val r = Api.get("/product-sizes/$size/book").assertStatus(200)
        assertEquals(95_000L, r.long("bestBuy"))
        assertEquals(100_000L, r.long("bestSell"))

        // 100,000 판매가 체결되면 다음 판매가
        Flow.bid(Db.createUser(), size, "BUY", 100_000).assertStatus(201)
        assertEquals(120_000L, Api.get("/product-sizes/$size/book").long("bestSell"))
    }

    @Test
    fun `호가 - 없는 사이즈 404 NOT_FOUND`() {
        Api.get("/product-sizes/${Db.createProductSize()}/book").assertStatus(200) // 라우트가 있는 상태에서의 404인지
        Api.get("/product-sizes/${Db.missingId("product_sizes")}/book").assertError(404, "NOT_FOUND")
    }
}
