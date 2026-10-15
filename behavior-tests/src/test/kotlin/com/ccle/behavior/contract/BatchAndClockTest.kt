package com.ccle.behavior.contract

import com.ccle.behavior.pg.FakePg
import com.ccle.behavior.support.Api
import com.ccle.behavior.support.ContractTest
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.instantAt
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-0003 §7 시계·배치(bid-expiry, payment-due, pg-inquiry 기한 관련), 구매 경고·제한. */
@ContractTest
class BatchAndClockTest {
    private val sec = Duration.ofSeconds(1)

    // ---- 시계 ----

    @Test
    fun `시계 - GET now, 같은 시각·뒤 시각 PUT 허용, 앞 시각 409 CLOCK_BACKWARD`() {
        val now = Flow.now()
        assertTrue(!now.isBefore(Db.SEED_NOW.toInstant()), "시계는 시드 시각보다 앞일 수 없다: $now")
        val same = Flow.setClock(now)
        assertTrue(same.status in 200..299, "같은 시각 허용: $same")
        assertEquals(now, Flow.now())

        val next = now.plus(Duration.ofMinutes(3))
        assertTrue(Flow.setClock(next).status in 200..299)
        assertEquals(next, Flow.now())

        Flow.setClock(next.minus(sec)).assertError(409, "CLOCK_BACKWARD")
        assertEquals(next, Flow.now())
    }

    @Test
    fun `시계 - 형식 오류·누락 400 INVALID_REQUEST`() {
        val now = Flow.now()
        Api.put("/admin/clock", mapOf("now" to "yesterday")).assertError(400, "INVALID_REQUEST")
        Api.put("/admin/clock", emptyMap<String, String>()).assertError(400, "INVALID_REQUEST")
        assertEquals(now, Flow.now())
    }

    @Test
    fun `시계 - 응답 시각은 오프셋 포함 ISO-8601`() {
        val text = Api.get("/admin/clock").assertStatus(200).text("now")
        assertTrue(Regex(""".*T.*([+-]\d{2}:\d{2}|Z)$""").matches(text), "오프셋 포함: $text")
    }

    // ---- bid-expiry ----

    @Test
    fun `bid-expiry - expires_at ≤ 현재인 ACTIVE만 EXPIRED, bid_events ACTIVE→EXPIRED`() {
        val size = Db.createProductSize()
        val user = Db.createUser()
        val short = Flow.activeBid(user, size, "BUY", 90_000, 30)
        val long = Flow.activeBid(user, size, "BUY", 80_000, 60)
        val cancelled = Flow.activeBid(user, size, "BUY", 70_000, 30)
        Flow.cancel(user, cancelled).assertStatus(200)
        val sell = Flow.activeBid(Db.createUser(), size, "SELL", 100_000, 30)
        val matched = Flow.bid(Db.createUser(), size, "BUY", 100_000).assertStatus(201).long("bidId")

        val now = Flow.moveTo(Db.bid(short).expiresAt)
        val r = Flow.runBatch("bid-expiry")
        assertTrue(r.long("processed") >= 1, "자기 입찰 1건 이상 처리: $r")

        assertEquals("EXPIRED", Db.bid(short).status)
        val ev = Db.bidEvents(short).last()
        assertEquals("ACTIVE", ev.fromStatus)
        assertEquals("EXPIRED", ev.toStatus)
        assertNull(ev.tradeId)
        assertEquals(now, ev.createdAt)
        assertEquals("ACTIVE", Db.bid(long).status)
        assertEquals("CANCELLED", Db.bid(cancelled).status)
        assertEquals("MATCHED", Db.bid(sell).status)
        assertEquals("MATCHED", Db.bid(matched).status)
        assertEquals(2, Db.bidEvents(cancelled).size)

        // 만료된 입찰은 취소할 수 없다
        Flow.cancel(user, short).assertError(409, "BID_NOT_ACTIVE")
        // 두 번째 실행은 같은 입찰을 다시 바꾸지 않는다
        Flow.runBatch("bid-expiry")
        assertEquals(2, Db.bidEvents(short).size)
    }

    // ---- payment-due ----

    @Test
    fun `payment-due - 기한 1초 전에는 그대로, 기한 시각에 PAYMENT_EXPIRED·경고·구매 입찰 CANCELLED·판매 입찰 ACTIVE 복구`() {
        val size = Db.createProductSize()
        val buyer = Db.createUser()
        val seller = Db.createUser()
        val sell = Flow.activeBid(seller, size, "SELL", 100_000, 60)
        val r = Flow.bid(buyer, size, "BUY", 100_000).assertStatus(201)
        val buy = r.long("bidId")
        val tradeId = r.long("tradeId")
        val due = Db.trade(tradeId).paymentDueAt

        Flow.moveTo(due.minus(sec))
        Flow.runBatch("payment-due")
        assertEquals("AWAITING_PAYMENT", Db.trade(tradeId).status)
        assertTrue(Db.warnings(buyer).isEmpty())

        val now = Flow.moveTo(due)
        Flow.runBatch("payment-due")
        assertEquals("PAYMENT_EXPIRED", Db.trade(tradeId).status)
        val w = Db.warnings(buyer).single()
        assertEquals(tradeId, w.tradeId)
        assertEquals(now, w.createdAt)

        assertEquals("CANCELLED", Db.bid(buy).status)
        val buyEv = Db.bidEvents(buy).last()
        assertEquals("MATCHED" to "CANCELLED", buyEv.fromStatus to buyEv.toStatus)
        assertEquals(tradeId, buyEv.tradeId)
        assertEquals(now, buyEv.createdAt)

        assertEquals("ACTIVE", Db.bid(sell).status)
        val sellEv = Db.bidEvents(sell).last()
        assertEquals("MATCHED" to "ACTIVE", sellEv.fromStatus to sellEv.toStatus)
        assertEquals(tradeId, sellEv.tradeId)
        assertEquals(now, sellEv.createdAt)
        // 복구된 입찰: 원래 created_at 유지, 살아 있는 체결 없음 → tradeId null, 호가에 다시 보인다
        val view = Api.get("/bids/$sell", seller).assertStatus(200)
        assertEquals("ACTIVE", view.text("status"))
        assertTrue(view.isNull("tradeId"), view.toString())
        assertEquals(Db.bid(sell).createdAt, view.json.instantAt("createdAt"))
        assertEquals(100_000L, Api.get("/product-sizes/$size/book").long("bestSell"))
        // 만료된 체결은 결제할 수 없다
        Flow.pay(buyer, listOf(tradeId), 0).assertError(409, "TRADE_NOT_PAYABLE")
        // 두 번째 실행은 같은 체결을 다시 처리하지 않는다
        Flow.runBatch("payment-due")
        assertEquals(1, Db.warnings(buyer).size)
    }

    @Test
    fun `payment-due - 복구된 판매 입찰은 즉시 재매칭, 판매 입찰이 앞서면 자기 가격`() {
        val size = Db.createProductSize()
        val seller = Db.createUser()
        val sell = Flow.activeBid(seller, size, "SELL", 100_000, 60)
        Flow.advance(sec)
        val first = Flow.bid(Db.createUser(), size, "BUY", 100_000).assertStatus(201)
        val expiredTrade = first.long("tradeId")
        Flow.advance(sec)
        val buyer2 = Db.createUser()
        val waiting = Flow.activeBid(buyer2, size, "BUY", 120_000) // 판매 입찰이 MATCHED라 대기
        val now = Flow.moveTo(Db.trade(expiredTrade).paymentDueAt)

        Flow.runBatch("payment-due")
        val live = Db.liveTradesOfBid(sell).single()
        assertEquals(waiting, live.buyBidId)
        assertEquals(buyer2, live.buyerId)
        assertEquals(100_000L, live.price, "체결가 = (created_at, id)가 앞선 입찰(복구된 판매 입찰)의 가격")
        assertEquals("AWAITING_PAYMENT", live.status)
        assertEquals(now, live.matchedAt)
        assertEquals(now.plus(Duration.ofMinutes(30)), live.paymentDueAt)
        assertEquals("MATCHED", Db.bid(sell).status)
        assertEquals("MATCHED", Db.bid(waiting).status)

        val sellEv = Db.bidEvents(sell)
        assertEquals(
            listOf(null to "ACTIVE", "ACTIVE" to "MATCHED", "MATCHED" to "ACTIVE", "ACTIVE" to "MATCHED"),
            sellEv.map { it.fromStatus to it.toStatus },
        )
        assertEquals(listOf(null, expiredTrade, expiredTrade, live.id), sellEv.map { it.tradeId })
        val waitEv = Db.bidEvents(waiting).last()
        assertEquals("ACTIVE" to "MATCHED", waitEv.fromStatus to waitEv.toStatus)
        assertEquals(live.id, waitEv.tradeId)
        assertTrue(waitEv.id < sellEv.last().id, "MATCHED 이벤트는 구매 입찰 행 먼저")
        assertEquals(live.id, Api.get("/bids/$sell", seller).assertStatus(200).long("tradeId"))
    }

    @Test
    fun `payment-due - 재매칭 상대가 복구된 판매 입찰보다 먼저 등록됐으면 상대 가격`() {
        val size = Db.createProductSize()
        val early = Db.createUser()
        val other = Db.createUser()
        Flow.activeBid(early, size, "BUY", 130_000)
        Flow.advance(sec)
        val waiting = Flow.activeBid(other, size, "BUY", 120_000)
        Flow.advance(sec)
        val r = Flow.bid(Db.createUser(), size, "SELL", 100_000).assertStatus(201) // 130,000 구매와 체결
        val sell = r.long("bidId")
        val expiredTrade = r.long("tradeId")
        assertEquals(130_000L, r.long("tradePrice"))
        Flow.moveTo(Db.trade(expiredTrade).paymentDueAt)

        Flow.runBatch("payment-due")
        val live = Db.liveTradesOfBid(sell).single()
        assertEquals(waiting, live.buyBidId)
        assertEquals(120_000L, live.price)
    }

    @Test
    fun `payment-due - 판매 입찰의 만료 시각도 지났으면 MATCHED→EXPIRED`() {
        val size = Db.createProductSize()
        val seller = Db.createUser()
        val sell = Flow.activeBid(seller, size, "SELL", 100_000, 30)
        Flow.moveTo(Db.bid(sell).expiresAt.minus(Duration.ofMinutes(10)))
        val r = Flow.bid(Db.createUser(), size, "BUY", 100_000).assertStatus(201)
        val tradeId = r.long("tradeId")
        Flow.activeBid(Db.createUser(), size, "BUY", 150_000) // 복구됐다면 재매칭됐을 상대
        val now = Flow.moveTo(Db.trade(tradeId).paymentDueAt)
        assertTrue(!Db.bid(sell).expiresAt.isAfter(now))

        Flow.runBatch("payment-due")
        assertEquals("PAYMENT_EXPIRED", Db.trade(tradeId).status)
        assertEquals("EXPIRED", Db.bid(sell).status)
        val ev = Db.bidEvents(sell).last()
        assertEquals("MATCHED" to "EXPIRED", ev.fromStatus to ev.toStatus)
        assertEquals(tradeId, ev.tradeId)
        assertEquals(now, ev.createdAt)
        assertEquals("CANCELLED", Db.bid(r.long("bidId")).status)
        assertTrue(Db.liveTradesOfBid(sell).isEmpty())
    }

    @Test
    fun `payment-due - UNKNOWN 주문이 있는 체결은 만료하지 않는다, 재조회 승인 시 paid_at = 요청 시각(기한 안)`() {
        val buyer = Db.createUser()
        val trades = tradesFor(buyer, Table8.C)
        val tradeId = trades.single()
        val requestedAt = Flow.now()
        val key = Flow.newKey()
        Flow.pgOutcome(key, FakePg.Outcome.TIMEOUT_APPROVED)
        val orderId = Flow.pay(buyer, trades, 0, key).assertStatus(201).long("orderId")
        assertEquals("UNKNOWN", Db.order(orderId).status)

        val due = Db.trade(tradeId).paymentDueAt
        Flow.moveTo(due.plus(Duration.ofMinutes(10)))
        Flow.runBatch("payment-due")
        assertEquals("AWAITING_PAYMENT", Db.trade(tradeId).status)
        assertTrue(Db.warnings(buyer).isEmpty())

        Flow.runBatch("pg-inquiry")
        val order = Db.order(orderId)
        assertEquals("PAID", order.status)
        assertEquals(requestedAt, order.paidAt)
        assertTrue(!order.paidAt!!.isAfter(due))
        assertEquals("PAID", Db.trade(tradeId).status)
        Flow.runBatch("payment-due")
        assertEquals("PAID", Db.trade(tradeId).status)
    }

    @Test
    fun `payment-due - 기한 지난 체결의 UNKNOWN 주문이 재조회로 거절되면 다음 payment-due에서 만료`() {
        val buyer = Db.createUser()
        val trades = tradesFor(buyer, Table8.C)
        val tradeId = trades.single()
        val key = Flow.newKey()
        Flow.pgOutcome(key, FakePg.Outcome.TIMEOUT_NOT_RECEIVED)
        val orderId = Flow.pay(buyer, trades, 0, key).assertStatus(201).long("orderId")

        Flow.moveTo(Db.trade(tradeId).paymentDueAt.plus(Duration.ofMinutes(1)))
        Flow.runBatch("pg-inquiry")
        assertEquals("FAILED", Db.order(orderId).status)
        assertEquals("AWAITING_PAYMENT", Db.trade(tradeId).status)
        // 기한이 지났으므로 새 키로도 결제할 수 없다
        Flow.pay(buyer, trades, 0).assertError(409, "TRADE_NOT_PAYABLE")

        Flow.runBatch("payment-due")
        assertEquals("PAYMENT_EXPIRED", Db.trade(tradeId).status)
        assertEquals(tradeId, Db.warnings(buyer).single().tradeId)
    }

    // ---- 구매 경고 → 제한 ----

    /** 같은 구매자의 체결 n건을 만든다(사이즈 각각). 반환: 체결 id. */
    private fun unpaidTrades(buyer: Long, n: Int): List<Long> =
        (1..n).map { Flow.trade(buyer, Db.createUser(), Db.createProductSize(), 50_000) }

    private fun expireAll(trades: List<Long>): java.time.Instant {
        val now = Flow.moveTo(trades.maxOf { Db.trade(it).paymentDueAt })
        Flow.runBatch("payment-due")
        trades.forEach { assertEquals("PAYMENT_EXPIRED", Db.trade(it).status) }
        return now
    }

    @Test
    fun `구매 경고 - 2건이면 제한 없음, 30일 안 3건째에 purchase_restricted_until = 현재 + 7일`() {
        val buyer = Db.createUser()
        expireAll(unpaidTrades(buyer, 2))
        assertEquals(2, Db.warnings(buyer).size)
        assertNull(Db.user(buyer).purchaseRestrictedUntil)

        Flow.advance(Duration.ofDays(1))
        val now = expireAll(unpaidTrades(buyer, 1))
        assertEquals(3, Db.warnings(buyer).size)
        assertEquals(now.plus(Duration.ofDays(7)), Db.user(buyer).purchaseRestrictedUntil)
        Flow.bid(buyer, Db.createProductSize(), "BUY", 10_000).assertError(403, "PURCHASE_RESTRICTED")
    }

    @Test
    fun `구매 경고 - 한 번의 실행에서 3건이 쌓여도 제한`() {
        val buyer = Db.createUser()
        val now = expireAll(unpaidTrades(buyer, 3))
        assertEquals(3, Db.warnings(buyer).size)
        assertTrue(Db.warnings(buyer).all { it.createdAt == now })
        assertEquals(now.plus(Duration.ofDays(7)), Db.user(buyer).purchaseRestrictedUntil)
    }

    @Test
    fun `구매 경고 - 30일 창 - created_at이 정확히 30일 전인 경고는 세지 않는다`() {
        val buyer = Db.createUser()
        val t0 = expireAll(unpaidTrades(buyer, 2))
        val trades = unpaidTrades(buyer, 1) // 시계를 옮기기 전에 체결해 둔다(결제 기한 30분)
        assertTrue(Db.trade(trades[0]).paymentDueAt.isBefore(t0.plus(Duration.ofDays(30))))
        Flow.moveTo(t0.plus(Duration.ofDays(30)))
        Flow.runBatch("payment-due")
        assertEquals(3, Db.warnings(buyer).size)
        assertNull(Db.user(buyer).purchaseRestrictedUntil, "30일 전 경고 2건은 창 밖")
    }

    @Test
    fun `구매 경고 - 제한을 건 경고는 다음 계산에서 빠지고 다시 센다`() {
        val buyer = Db.createUser()
        val t1 = expireAll(unpaidTrades(buyer, 3))
        val firstUntil = t1.plus(Duration.ofDays(7))
        assertEquals(firstUntil, Db.user(buyer).purchaseRestrictedUntil)

        // 제한이 풀린 시각(restricted_until = 현재는 제한 아님)에 체결 3건을 더 만든다
        Flow.moveTo(firstUntil)
        val more = unpaidTrades(buyer, 3)

        // 한 실행에서 경고 4·5·6번째: 마지막 제한(t1) 이후 경고만 세므로 6번째에서 3건이 되어 다시 제한
        val t2 = Flow.moveTo(Db.trade(more[0]).paymentDueAt)
        Flow.runBatch("payment-due")
        assertTrue(more.all { Db.trade(it).status == "PAYMENT_EXPIRED" })
        assertEquals(6, Db.warnings(buyer).size)
        assertEquals(t2.plus(Duration.ofDays(7)), Db.user(buyer).purchaseRestrictedUntil)
    }

    @Test
    fun `구매 경고 - 제한 뒤 경고 2건은 제한을 다시 걸지 않는다(제한 전 경고는 세지 않음)`() {
        val buyer = Db.createUser()
        val t1 = expireAll(unpaidTrades(buyer, 3))
        val until = t1.plus(Duration.ofDays(7))
        Flow.moveTo(until)
        val extra = unpaidTrades(buyer, 2)
        expireAll(extra)
        assertEquals(5, Db.warnings(buyer).size)
        assertEquals(until, Db.user(buyer).purchaseRestrictedUntil, "마지막 제한 뒤 경고 2건 → 그대로")
    }
}
