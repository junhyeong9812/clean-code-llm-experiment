package hidden

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.TestMethodOrder
import support.Batches
import support.Clock
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * C2 숨은 테스트: 제한이 새로 걸리거나 갱신되는 순간 그쪽 ACTIVE 입찰 취소.
 * S1·S2 판매 제한(3번째 패널티 = 검수 불합격 / shipment-deadline), B 구매 제한(payment-due — 재매칭보다 먼저),
 * N 갱신 아님(제한 중 패널티·경고 1건), R 갱신(제한 중 3건), 재계수(제한을 건 패널티 뒤로 다시 센다 — contract2 PenaltyTest 계약).
 * 테스트끼리 같은 DB·시계를 쓴다: 사용자·사이즈는 테스트마다 새로 만들고 시계는 앞으로만 상대 이동한다. bid-expiry는 돌리지 않는다.
 */
@TestMethodOrder(MethodOrderer.MethodName::class)
class C2HiddenTest {
    private val day = Duration.ofDays(1)

    /** 판매자 귀책 3건 준비: 판매자가 걸어 둔 판매 입찰 3개가 팔리고 결제된 항목. 반환: 주문 항목 id. */
    private fun soldItems(seller: Long, n: Int = 3): List<Long> = (1..n).map { Fx.sellSize(seller, 50_000) }.map { Fx.buyAndPay(it, 50_000) }

    /** 판매자의 취소 대상 판매 입찰들: 다른 사이즈 여럿 + 한 사이즈에 둘 + 유효기간이 지났지만 ACTIVE인 것(stale). */
    private class SellSide(val bids: List<Long>, val sizes: List<Long>, val alreadyCancelled: Long, val ownBuy: Long, val ownBuySize: Long)

    /** stale 입찰을 먼저 걸고 31일을 넘긴 뒤 나머지를 건다(bid-expiry는 돌지 않으므로 stale은 ACTIVE로 남는다). */
    private fun sellSide(seller: Long): SellSide {
        val staleSize = Fx.createProductSize()
        val stale = Fx.activeBid(seller, staleSize, "SELL", 70_000, 30)
        Fx.advance(Duration.ofDays(31))
        val x1 = Fx.createProductSize()
        val a = Fx.activeBid(seller, x1, "SELL", 80_000, 90)
        val b = Fx.activeBid(seller, x1, "SELL", 85_000, 60)
        val x2 = Fx.createProductSize()
        val c = Fx.activeBid(seller, x2, "SELL", 90_000, 30)
        val goneSize = Fx.createProductSize()
        val gone = Fx.activeBid(seller, goneSize, "SELL", 60_000)
        Fx.ok("입찰 취소", support.Api.delete("/bids/$gone", seller))
        val buySize = Fx.createProductSize()
        val ownBuy = Fx.activeBid(seller, buySize, "BUY", 40_000)
        return SellSide(listOf(stale, a, b, c), listOf(x1, x2), gone, ownBuy, buySize)
    }

    private fun assertNothingCancelled(s: SellSide, mark: Long, what: String) {
        s.bids.forEach { Bids.assertUntouched(it, mark, "ACTIVE", what) }
        Bids.assertUntouched(s.ownBuy, mark, "ACTIVE", what)
    }

    private fun assertSellCancelled(seller: Long, s: SellSide, sold: List<Long>, mark: Long, now: java.time.Instant, what: String) {
        assertEquals(now.plus(Duration.ofDays(30)), Fx.saleRestrictedUntil(seller), "준비: 3번째 패널티로 판매 제한")
        s.bids.forEach { Bids.assertCancelled(it, mark, now, "$what — 판매자의 ACTIVE 판매 입찰") }
        Bids.assertUntouched(s.alreadyCancelled, mark, "CANCELLED", "$what — 이미 취소된 판매 입찰")
        Bids.assertUntouched(s.ownBuy, mark, "ACTIVE", "$what — 판매자의 구매 입찰(반대쪽)")
        sold.forEach { item ->
            val sell = support.Db.long("SELECT t.sell_bid_id FROM order_items i JOIN trades t ON t.id = i.trade_id WHERE i.id = ?", item)
            Bids.assertUntouched(sell, mark, "MATCHED", "$what — 체결된 판매 입찰")
        }
        s.sizes.forEach { assertEquals(null to null, Fx.book(it), "$what — 호가에서 빠짐(사이즈 $it)") }
        assertEquals(40_000L to null, Fx.book(s.ownBuySize), "$what — 판매자의 구매 입찰은 호가에 남음")
    }

    @Test
    fun `S1 판매 제한 - 3번째 패널티가 검수 불합격이면 그 순간 판매자의 ACTIVE 판매 입찰이 전부 취소된다`() {
        val seller = Fx.createUser()
        val s = sellSide(seller)
        val items = soldItems(seller)
        val mark = Bids.mark()

        Fx.faultByInspection(items[0], seller)
        Fx.faultByInspection(items[1], seller)
        assertEquals(2, Fx.penaltyCount(seller))
        assertNull(Fx.saleRestrictedUntil(seller), "준비: 2건이면 제한 없음")
        assertNothingCancelled(s, mark, "패널티 2건 뒤")

        Fx.faultByInspection(items[2], seller)
        assertSellCancelled(seller, s, items, mark, Clock.now(), "3번째 패널티(검수 불합격)")
    }

    @Test
    fun `S2 판매 제한 - 3번째 패널티가 발송 기한 초과(shipment-deadline)여도 그 순간 판매자의 ACTIVE 판매 입찰이 전부 취소된다`() {
        val seller = Fx.createUser()
        val s = sellSide(seller)
        val paidAt = Clock.now()
        val items = soldItems(seller)
        val mark = Bids.mark()

        Fx.faultByInspection(items[0], seller)
        Fx.faultByInspection(items[1], seller)
        assertNull(Fx.saleRestrictedUntil(seller), "준비: 2건이면 제한 없음")
        assertNothingCancelled(s, mark, "패널티 2건 뒤")

        val now = Fx.moveTo(paidAt.plus(Duration.ofDays(3)).plusSeconds(60))
        Fx.ok("shipment-deadline", Batches.run("shipment-deadline"))
        assertEquals("CANCELLED_SELLER_FAULT", Fx.itemStatus(items[2]), "준비: 발송 기한 초과 귀책")
        assertEquals(3, Fx.penaltyCount(seller))
        assertSellCancelled(seller, s, items, mark, now, "3번째 패널티(shipment-deadline)")
    }

    @Test
    fun `B 구매 제한 - 3번째 경고가 제한을 걸면 구매자의 ACTIVE 구매 입찰이 판매 입찰 재매칭보다 먼저 취소된다`() {
        val buyer = Fx.createUser()
        val sellSize = Fx.createProductSize()
        val ownSell = Fx.activeBid(buyer, sellSize, "SELL", 120_000)
        val otherSize = Fx.createProductSize()
        val otherBuy = Fx.activeBid(buyer, otherSize, "BUY", 30_000)

        val first = listOf(Fx.unpaidTrade(buyer), Fx.unpaidTrade(buyer))
        val mark0 = Bids.mark()
        Fx.expireUnpaid(first)
        assertEquals(2, Fx.warningCount(buyer))
        assertNull(Fx.purchaseRestrictedUntil(buyer), "준비: 경고 2건이면 제한 없음")
        Bids.assertUntouched(otherBuy, mark0, "ACTIVE", "경고 2건 뒤")
        Bids.assertUntouched(ownSell, mark0, "ACTIVE", "경고 2건 뒤")

        // 빈 사이즈: 판매 입찰 1개와 체결(미결제) → 같은 사이즈에 구매자의 다른 구매 입찰(되살아날 판매가 이상, 그 사이즈 유일한 구매 입찰)
        val size = Fx.createProductSize()
        val seller = Fx.createUser()
        val trade = Fx.unpaidTrade(buyer, size, 100_000, seller)
        val sell = support.Db.long("SELECT sell_bid_id FROM trades WHERE id = ?", trade)
        val sameSizeBuy = Fx.activeBid(buyer, size, "BUY", 110_000)
        val mark = Bids.mark()

        val now = Fx.expireUnpaid(listOf(trade))
        assertEquals(now.plus(Duration.ofDays(7)), Fx.purchaseRestrictedUntil(buyer), "준비: 3번째 경고로 구매 제한")
        Bids.assertCancelled(sameSizeBuy, mark, now, "같은 사이즈의 구매 입찰")
        Bids.assertCancelled(otherBuy, mark, now, "다른 사이즈의 구매 입찰")
        Bids.assertUntouched(ownSell, mark, "ACTIVE", "구매자의 판매 입찰(반대쪽)")

        assertEquals("ACTIVE", Bids.status(sell), "되살아난 판매 입찰은 ACTIVE로 남는다(취소된 구매 입찰과 체결되지 않음)")
        assertEquals(listOf("MATCHED" to "ACTIVE"), Bids.eventsAfter(sell, mark).map { it.from to it.to }, "되살아난 판매 입찰 이벤트")
        assertEquals(1L, support.Db.long("SELECT count(*) FROM trades WHERE product_size_id = ?", size), "새 체결 없음")
        assertEquals(null to 100_000L, Fx.book(size), "호가: 구매 입찰 없음, 판매 입찰 100,000")
    }

    @Test
    fun `N 갱신 아님 - 이미 제한 중인 판매자의 패널티 1건, 구매자의 경고 1건은 아무 입찰도 취소하지 않는다`() {
        // 판매: 입찰을 건 뒤 DB로 제한(현재 + 10일)을 건다(제한은 새 입찰에만 — 이미 걸린 입찰의 체결·결제는 된다)
        val seller = Fx.createUser()
        val penSize = Fx.sellSize(seller, 50_000)
        val sellSize = Fx.createProductSize()
        val sellBid = Fx.activeBid(seller, sellSize, "SELL", 80_000)
        val t0 = Clock.now()
        Fx.setSaleRestrictedUntil(seller, t0.plus(Duration.ofDays(10)))
        val item = Fx.buyAndPay(penSize, 50_000)
        val mark = Bids.mark()
        Fx.faultByInspection(item, seller)
        assertEquals(1, Fx.penaltyCount(seller))
        assertEquals(t0.plus(Duration.ofDays(10)), Fx.saleRestrictedUntil(seller), "준비: 제한 그대로")
        Bids.assertUntouched(sellBid, mark, "ACTIVE", "제한을 바꾸지 않는 패널티")

        // 구매: 체결을 만든 뒤 DB로 제한(현재 + 3일)을 건다
        val buyer = Fx.createUser()
        val buySize = Fx.createProductSize()
        val buyBid = Fx.activeBid(buyer, buySize, "BUY", 30_000)
        val trade = Fx.unpaidTrade(buyer)
        val t1 = Clock.now()
        Fx.setPurchaseRestrictedUntil(buyer, t1.plus(Duration.ofDays(3)))
        val mark2 = Bids.mark()
        Fx.expireUnpaid(listOf(trade))
        assertEquals(1, Fx.warningCount(buyer))
        assertEquals(t1.plus(Duration.ofDays(3)), Fx.purchaseRestrictedUntil(buyer), "준비: 제한 그대로")
        Bids.assertUntouched(buyBid, mark2, "ACTIVE", "제한을 바꾸지 않는 경고")
    }

    @Test
    fun `R 갱신 - 제한 중에 패널티·경고가 3건이 되어 제한이 갱신되는 순간에도 취소된다`() {
        // 판매: 마지막 제한이 1일 전에 걸린 상태(sale_restricted_until = 현재 + 29일) → 그 뒤 패널티 3건
        val seller = Fx.createUser()
        val penSizes = (1..3).map { Fx.sellSize(seller, 50_000) }
        val sellSize = Fx.createProductSize()
        val sellBid = Fx.activeBid(seller, sellSize, "SELL", 80_000)
        val t0 = Clock.now()
        Fx.setSaleRestrictedUntil(seller, t0.plus(Duration.ofDays(29)))
        val items = penSizes.map { Fx.buyAndPay(it, 50_000) }
        val mark = Bids.mark()
        Fx.faultByInspection(items[0], seller)
        Fx.faultByInspection(items[1], seller)
        assertEquals(t0.plus(Duration.ofDays(29)), Fx.saleRestrictedUntil(seller), "준비: 2건이면 그대로")
        Bids.assertUntouched(sellBid, mark, "ACTIVE", "패널티 2건 뒤")
        Fx.faultByInspection(items[2], seller)
        val now = Clock.now()
        assertEquals(now.plus(Duration.ofDays(30)), Fx.saleRestrictedUntil(seller), "준비: 3건째에 제한 갱신")
        Bids.assertCancelled(sellBid, mark, now, "판매 제한 갱신")

        // 구매: 마지막 제한이 1일 전에 걸린 상태(purchase_restricted_until = 현재 + 6일) → 그 뒤 경고 3건(한 번의 payment-due)
        val buyer = Fx.createUser()
        val buySize = Fx.createProductSize()
        val buyBid = Fx.activeBid(buyer, buySize, "BUY", 30_000)
        val trades = (1..3).map { Fx.unpaidTrade(buyer) }
        val t1 = Clock.now()
        Fx.setPurchaseRestrictedUntil(buyer, t1.plus(Duration.ofDays(6)))
        val mark2 = Bids.mark()
        val now2 = Fx.expireUnpaid(trades)
        assertEquals(3, Fx.warningCount(buyer))
        assertEquals(now2.plus(Duration.ofDays(7)), Fx.purchaseRestrictedUntil(buyer), "준비: 3건째에 제한 갱신")
        Bids.assertCancelled(buyBid, mark2, now2, "구매 제한 갱신")
    }

    @Test
    fun `재계수 - 판매 제한은 제한을 건 패널티 뒤로 다시 센다(뒤 2건은 그대로, 3건째에 다시 제한)`() {
        // contract2 PenaltyTest 의 같은 계약. 추가 귀책 항목은 첫 제한 전에 결제·발송·입고까지 해 둔다
        val seller = Fx.createUser()
        val items = soldItems(seller, 6)
        items.forEach { Fx.shipAndReceive(it, seller) }

        items.take(3).forEach { Fx.inspectFail(it) }
        val t1 = Clock.now()
        val firstUntil = t1.plus(Duration.ofDays(30))
        assertEquals(firstUntil, Fx.saleRestrictedUntil(seller))

        Fx.advance(day)
        items.slice(3..4).forEach { Fx.inspectFail(it) }
        assertEquals(5, Fx.penaltyCount(seller))
        assertEquals(firstUntil, Fx.saleRestrictedUntil(seller), "마지막 제한 뒤 패널티 2건 → 그대로")

        val t3 = Fx.advance(day)
        Fx.inspectFail(items[5])
        assertEquals(6, Fx.penaltyCount(seller))
        assertEquals(t3.plus(Duration.ofDays(30)), Fx.saleRestrictedUntil(seller))
    }
}
