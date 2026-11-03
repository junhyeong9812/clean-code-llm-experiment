package pub

import support.Api
import support.Batches
import support.Clock
import support.Db
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 증상: 적립분 두 개 중 일부를 결제에 쓰고, 먼저 적립된 쪽의 유효기간이 끝난 뒤 point-expiry를 돌리면
 * 이미 쓰였어야 할 포인트가 소멸되어 사용 가능액이 줄어든다.
 */
class D2Test {

    @Test
    fun `먼저 적립된 1,000P를 결제에 쓰고 그 유효기간이 끝나면 소멸될 것이 없다`() {
        val now = Clock.now()
        val buyer = newUser()
        val first = earned(buyer, 1_000, confirmedAt = now.minus(Duration.ofDays(2)))
        val second = earned(buyer, 2_000, confirmedAt = now.minus(Duration.ofDays(1)))
        assertTrue(first.validUntil.isBefore(second.validUntil), "준비: 먼저 적립된 쪽의 유효기간이 먼저 끝난다")

        // 1,000P를 써서 결제한다
        val pay = Api.post("/orders", mapOf("tradeIds" to listOf(matchedTrade(buyer)), "pointToUse" to 1_000), buyer,
            "d2-" + UUID.randomUUID())
        assertEquals(201, pay.status, "준비: 결제: $pay")
        assertEquals("PAID", pay.json["status"].asText(), "준비: 결제: $pay")

        // 먼저 적립된 쪽의 유효기간이 끝나는 시각 → point-expiry
        moveTo(first.validUntil)
        val batch = Batches.run("point-expiry")
        assertEquals(200, batch.status, "point-expiry: $batch")

        val expired = Db.long("SELECT coalesce(sum(amount), 0) FROM point_ledger WHERE user_id = ? AND kind = 'EXPIRE'", buyer)
        assertEquals(0L, expired, "point-expiry가 소멸한 포인트")
        val points = Api.get("/me/points", buyer)
        assertEquals(200, points.status, "GET /me/points: $points")
        assertEquals(2_000L, points.json["usable"].asLong(), "point-expiry 뒤 사용 가능액")
    }

    // ---- 준비 도우미(HTTP·DB) ----

    private val price = 50_000L

    private fun moveTo(t: Instant) = Clock.moveTo(t).also { assertTrue(it.status in 200..299, "시계 이동: $it") }

    private fun uniq() = UUID.randomUUID().toString().replace("-", "").take(16)

    private fun newUser(): Long {
        val u = uniq()
        return Db.long(
            "INSERT INTO users(email, name, grade, created_at) VALUES (?, ?, 'BRONZE', TIMESTAMPTZ '2025-01-01 00:00:00+09') RETURNING id",
            "d2-$u@d2.test", "d2-$u",
        )
    }

    private fun newSize(): Long {
        val u = uniq()
        val product = Db.long(
            "INSERT INTO products(category_id, brand, name, model_no, release_price) " +
                "VALUES ((SELECT min(id) FROM categories), 'd2-brand', ?, ?, 100000) RETURNING id",
            "d2-product-$u", "D2-$u",
        )
        return Db.long("INSERT INTO product_sizes(product_id, size) VALUES (?, '270') RETURNING id", product)
    }

    /** 새 판매자의 판매 입찰에 구매자가 구매 입찰을 걸어 체결한다. 반환: 체결 id. */
    private fun matchedTrade(buyer: Long): Long {
        val size = newSize()
        val sell = Api.post("/bids", mapOf("productSizeId" to size, "side" to "SELL", "price" to price, "validDays" to 30), newUser())
        assertEquals(201, sell.status, "준비: 판매 입찰: $sell")
        val buy = Api.post("/bids", mapOf("productSizeId" to size, "side" to "BUY", "price" to price, "validDays" to 30), buyer)
        assertEquals(201, buy.status, "준비: 구매 입찰: $buy")
        assertEquals("MATCHED", buy.json["status"].asText(), "준비: 체결: $buy")
        return buy.json["tradeId"].asLong()
    }

    class Earned(val id: Long, val validUntil: Instant)

    /**
     * 구매 확정된 과거 거래 1건과 그 적립(사용 가능, 유효기간 = 확정 + 365일)을 넣고 잔액 캐시를 늘린다.
     * 체결가 = amount × 100 → 적립 = 체결가의 1% = amount.
     */
    private fun earned(buyer: Long, amount: Long, confirmedAt: Instant): Earned {
        val seller = newUser()
        val size = newSize()
        val p = amount * 100
        val fee = maxOf(p * 3 / 100, 1000L)
        val ship = if (p >= 100_000) 0L else 3000L
        val total = p + fee + ship
        val tSell = confirmedAt.minus(Duration.ofDays(10))
        val tBuy = confirmedAt.minus(Duration.ofDays(9))
        val paid = tBuy.plus(Duration.ofMinutes(1))
        val validUntil = confirmedAt.plus(Duration.ofDays(365))
        val ts = { t: Instant -> java.sql.Timestamp.from(t) }
        val bidSql = "INSERT INTO bids(user_id, product_size_id, side, price, status, expires_at, created_at) VALUES (?, ?, ?, ?, 'MATCHED', ?, ?) RETURNING id"
        val sellBid = Db.long(bidSql, seller, size, "SELL", p, ts(tSell.plus(Duration.ofDays(30))), ts(tSell))
        val buyBid = Db.long(bidSql, buyer, size, "BUY", p, ts(tBuy.plus(Duration.ofDays(30))), ts(tBuy))
        val trade = Db.long(
            "INSERT INTO trades(buy_bid_id, sell_bid_id, buyer_id, seller_id, product_size_id, price, status, matched_at, payment_due_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'PAID', ?, ?) RETURNING id",
            buyBid, sellBid, buyer, seller, size, p, ts(tBuy), ts(tBuy.plus(Duration.ofMinutes(30))),
        )
        val order = Db.long(
            "INSERT INTO orders(buyer_id, status, item_total, buyer_fee_total, shipping_fee, point_used, card_amount, idempotency_key, created_at, paid_at) " +
                "VALUES (?, 'PAID', ?, ?, ?, 0, ?, ?, ?, ?) RETURNING id",
            buyer, p, fee, ship, total, "d2-fixture-" + UUID.randomUUID(), ts(paid), ts(paid),
        )
        val item = Db.long(
            "INSERT INTO order_items(order_id, trade_id, price, buyer_fee, card_share, point_share, shipping_share, status, " +
                "paid_at, shipped_at, inspected_at, delivered_at, confirmed_at) VALUES (?, ?, ?, ?, ?, 0, ?, 'CONFIRMED', ?, ?, ?, ?, ?) RETURNING id",
            order, trade, p, fee, total, ship, ts(paid), ts(paid.plus(Duration.ofDays(1))), ts(paid.plus(Duration.ofDays(2))),
            ts(paid.plus(Duration.ofDays(3))), ts(confirmedAt),
        )
        val id = Db.long(
            "INSERT INTO point_ledger(user_id, kind, status, amount, remaining, expires_at, order_item_id, order_id, created_at) " +
                "VALUES (?, 'EARN', 'AVAILABLE', ?, ?, ?, ?, NULL, ?) RETURNING id",
            buyer, amount, amount, ts(validUntil), item, ts(paid),
        )
        Db.update(
            "INSERT INTO point_balances(user_id, available, pending, updated_at) VALUES (?, ?, 0, ?) " +
                "ON CONFLICT (user_id) DO UPDATE SET available = point_balances.available + EXCLUDED.available, updated_at = EXCLUDED.updated_at",
            buyer, amount, ts(confirmedAt),
        )
        return Earned(id, validUntil)
    }
}
