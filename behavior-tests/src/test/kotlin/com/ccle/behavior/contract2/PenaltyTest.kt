package com.ccle.behavior.contract2

import com.ccle.behavior.support.Contract2Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db2
import com.ccle.behavior.support.Flow
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * ADR-0004 §6 판매자 귀책: 패널티 금액·행, 판매 제한(30일 안 3건 → 현재 + 30일, 30일 창 경계, 제한을 건 패널티 뒤로 다시 센다).
 * 패널티는 shipment-deadline(§5)으로 만든다. 판매자는 판매 입찰을 미리 걸어 두고(제한은 새 입찰에만 — ADR-0003 §3.1)
 * 구매자가 나중에 체결·결제한다.
 */
@Contract2Test
class PenaltyTest {
    private val price = 50_000L

    /** 판매자 1명이 사이즈 n개에 판매 입찰(90일)을 건다. 반환: 사이즈 id. */
    private fun sellBids(seller: Long, n: Int): List<Long> = (1..n).map {
        val size = Db.createProductSize()
        Flow.activeBid(seller, size, "SELL", price, 90)
        size
    }

    /** 새 구매자가 그 사이즈를 사서 결제한다(1단계 경로). 반환: 주문 항목 id. */
    private fun buyAndPay(size: Long): Long {
        val buyer = Db.createUser()
        val r = Flow.bid(buyer, size, "BUY", price).assertStatus(201)
        assertEquals("MATCHED", r.text("status"), "준비(1단계) 체결: $r")
        val trade = r.long("tradeId")
        val pay = Flow.pay(buyer, listOf(trade), 0).assertStatus(201)
        assertEquals("PAID", pay.text("status"), "준비(1단계) 결제: $pay")
        return Db.orderItemsOfTrade(trade).single().id
    }

    /** 결제 시각 + 3일로 옮겨 shipment-deadline을 돌리고 그 항목들이 판매자 귀책이 됐는지 확인(준비). 반환: 현재. */
    private fun faultAt(items: List<Long>, paidAt: Instant): Instant {
        val now = Flow.moveTo(paidAt.plus(days(3)))
        S2.readyBatch("shipment-deadline")
        items.forEach { assertEquals("CANCELLED_SELLER_FAULT", Db2.item(it).status, "준비(2단계) 기한 초과 귀책: 항목 $it") }
        return now
    }

    @Test
    fun `패널티 - amount = floor(체결가 × 10%), seller_id = 체결 판매자, settlement_id NULL, created_at = 현재`() {
        val p = S2.purchase(33_333)
        val now = Flow.moveTo(p.paidAt.plus(days(3)))
        S2.runBatch("shipment-deadline")
        val pen = Db2.penaltiesOfItem(p.item).single()
        assertEquals(3_333L, pen.amount)
        assertEquals(p.seller, pen.sellerId)
        assertEquals(Db.trade(p.trades.single()).sellerId, pen.sellerId)
        assertNull(pen.settlementId)
        assertEquals(now, pen.createdAt)
    }

    @Test
    fun `판매 제한 - 2건이면 제한 없음, 30일 안 3건째에 sale_restricted_until = 현재 + 30일, 판매 입찰 403 SALE_RESTRICTED`() {
        val seller = Db.createUser()
        val sizes = sellBids(seller, 3)
        val t0 = Flow.now()
        faultAt(listOf(buyAndPay(sizes[0]), buyAndPay(sizes[1])), t0)
        assertEquals(2, Db2.penaltiesOfSeller(seller).size)
        assertNull(Db.user(seller).saleRestrictedUntil)

        val t1 = Flow.advance(DAY)
        val now = faultAt(listOf(buyAndPay(sizes[2])), t1)
        assertEquals(3, Db2.penaltiesOfSeller(seller).size)
        assertEquals(now.plus(days(30)), Db.user(seller).saleRestrictedUntil)
        Flow.bid(seller, Db.createProductSize(), "SELL", 90_000).assertError(403, "SALE_RESTRICTED")
    }

    @Test
    fun `판매 제한 - 30일 창, created_at이 정확히 30일 전인 패널티는 세지 않는다`() {
        val seller = Db.createUser()
        val sizes = sellBids(seller, 3)
        val t = faultAt(listOf(buyAndPay(sizes[0]), buyAndPay(sizes[1])), Flow.now())

        val paid = Flow.moveTo(t.plus(days(27)))
        val now = faultAt(listOf(buyAndPay(sizes[2])), paid)
        assertEquals(t.plus(days(30)), now)
        assertEquals(3, Db2.penaltiesOfSeller(seller).size)
        assertNull(Db.user(seller).saleRestrictedUntil, "30일 전 패널티 2건은 창 밖")
    }

    @Test
    fun `판매 제한 - 제한을 건 패널티 뒤로 다시 센다(뒤 2건은 그대로, 3건째에 다시 제한)`() {
        val seller = Db.createUser()
        val sizes = sellBids(seller, 6)
        val t1 = faultAt(sizes.take(3).map { buyAndPay(it) }, Flow.now())
        val firstUntil = t1.plus(days(30))
        assertEquals(firstUntil, Db.user(seller).saleRestrictedUntil)

        // 제한 중이어도 이미 걸린 판매 입찰의 체결·결제는 된다(ADR-0003 §3.1)
        val t2 = faultAt(listOf(buyAndPay(sizes[3]), buyAndPay(sizes[4])), t1)
        assertEquals(5, Db2.penaltiesOfSeller(seller).size)
        assertEquals(firstUntil, Db.user(seller).saleRestrictedUntil, "마지막 제한 뒤 패널티 2건 → 그대로")

        val t3 = faultAt(listOf(buyAndPay(sizes[5])), t2)
        assertEquals(6, Db2.penaltiesOfSeller(seller).size)
        assertEquals(t3.plus(days(30)), Db.user(seller).saleRestrictedUntil)
    }
}
