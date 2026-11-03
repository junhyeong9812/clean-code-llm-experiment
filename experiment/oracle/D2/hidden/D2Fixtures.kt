package hidden

import support.Api
import support.Batches
import support.Clock
import support.Db
import support.Resp
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D2 숨은 테스트 준비 도우미. 사용자·빈 상품 사이즈·포인트 적립분(구매 확정된 과거 거래 1건 + EARN AVAILABLE + 잔액 캐시)만
 * SQL로 넣고, 입찰·체결·결제·구매자 취소·배치는 API로 한다. 적립분 만료 = 확정 + 365일(behavior-tests Db.createPointBundle과 같은 모양).
 */
object D2Fx {
    private const val PRICE = 50_000L

    class Bundle(val id: Long, val amount: Long, val expiresAt: Instant)

    fun now(): Instant = Clock.now()

    fun moveTo(t: Instant) = Clock.moveTo(t).also { assertTrue(it.status in 200..299, "시계 이동: $it") }

    private fun uniq() = UUID.randomUUID().toString().replace("-", "").take(16)

    private fun ts(t: Instant) = Timestamp.from(t)

    fun newUser(): Long {
        val u = uniq()
        return Db.long(
            "INSERT INTO users(email, name, grade, created_at) VALUES (?, ?, 'BRONZE', TIMESTAMPTZ '2025-01-01 00:00:00+09') RETURNING id",
            "d2h-$u@d2.test", "d2h-$u",
        )
    }

    fun newSize(): Long {
        val u = uniq()
        val product = Db.long(
            "INSERT INTO products(category_id, brand, name, model_no, release_price) " +
                "VALUES ((SELECT min(id) FROM categories), 'd2h', ?, ?, 100000) RETURNING id",
            "d2h-$u", "D2H-$u",
        )
        return Db.long("INSERT INTO product_sizes(product_id, size) VALUES (?, '270') RETURNING id", product)
    }

    /** 사용 가능한 적립분 1개(remaining = amount, expires_at = confirmedAt + 365일) + point_balances.available 증가. */
    fun bundle(buyer: Long, amount: Long, confirmedAt: Instant): Bundle {
        val seller = newUser()
        val size = newSize()
        val p = amount * 100
        val fee = maxOf(p * 3 / 100, 1000L)
        val ship = if (p >= 100_000) 0L else 3000L
        val total = p + fee + ship
        val tSell = confirmedAt.minus(Duration.ofDays(10))
        val tBuy = confirmedAt.minus(Duration.ofDays(9))
        val paid = tBuy.plus(Duration.ofMinutes(1))
        val expiresAt = confirmedAt.plus(Duration.ofDays(365))
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
            buyer, p, fee, ship, total, "d2h-fixture-" + UUID.randomUUID(), ts(paid), ts(paid),
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
            buyer, amount, amount, ts(expiresAt), item, ts(paid),
        )
        Db.update(
            "INSERT INTO point_balances(user_id, available, pending, updated_at) VALUES (?, ?, 0, ?) " +
                "ON CONFLICT (user_id) DO UPDATE SET available = point_balances.available + EXCLUDED.available, updated_at = EXCLUDED.updated_at",
            buyer, amount, ts(confirmedAt),
        )
        return Bundle(id, amount, expiresAt)
    }

    /** 새 판매자의 판매 입찰(새 사이즈)에 구매자가 구매 입찰을 걸어 체결한다. 반환: 체결 id. */
    fun matchedTrade(buyer: Long): Long {
        val size = newSize()
        val sell = Api.post("/bids", mapOf("productSizeId" to size, "side" to "SELL", "price" to PRICE, "validDays" to 30), newUser())
        assertEquals(201, sell.status, "준비: 판매 입찰: $sell")
        val buy = Api.post("/bids", mapOf("productSizeId" to size, "side" to "BUY", "price" to PRICE, "validDays" to 30), buyer)
        assertEquals(201, buy.status, "준비: 구매 입찰: $buy")
        assertEquals("MATCHED", buy.json["status"].asText(), "준비: 체결: $buy")
        return buy.json["tradeId"].asLong()
    }

    /** 체결 n건을 한 주문으로 포인트를 써서 결제한다(PAID). 반환: 주문 응답. */
    fun pay(buyer: Long, trades: Int, point: Long): Resp {
        val ids = (1..trades).map { matchedTrade(buyer) }
        val r = Api.post("/orders", mapOf("tradeIds" to ids, "pointToUse" to point), buyer, "d2h-" + UUID.randomUUID())
        assertEquals(201, r.status, "준비: 결제: $r")
        assertEquals("PAID", r.json["status"].asText(), "준비: 결제: $r")
        return r
    }

    /** 그 주문 USE 행의 배분: 적립분 id → 양. */
    fun useAllocations(orderId: Long): Map<Long, Long> {
        val use = Db.long("SELECT id FROM point_ledger WHERE order_id = ? AND kind = 'USE'", orderId)
        return allocations(use)
    }

    fun allocations(entryId: Long): Map<Long, Long> =
        Db.query("SELECT earn_entry_id, amount FROM point_allocations WHERE entry_id = ?", entryId)
            .associate { (it["earn_entry_id"] as Number).toLong() to (it["amount"] as Number).toLong() }

    /** 그 항목의 RESTORE 행(하나여야 한다): id to amount. */
    fun restoreOf(item: Long): Pair<Long, Long> {
        val row = Db.query("SELECT id, amount FROM point_ledger WHERE order_item_id = ? AND kind = 'RESTORE'", item).single()
        return (row["id"] as Number).toLong() to (row["amount"] as Number).toLong()
    }

    fun remaining(bundleId: Long): Long = Db.long("SELECT remaining FROM point_ledger WHERE id = ?", bundleId)

    fun expiredSum(user: Long): Long =
        Db.long("SELECT coalesce(sum(amount), 0) FROM point_ledger WHERE user_id = ? AND kind = 'EXPIRE'", user)

    fun points(user: Long): Pair<Long, Long> {
        val r = Api.get("/me/points", user)
        assertEquals(200, r.status, "GET /me/points: $r")
        return r.json["usable"].asLong() to r.json["available"].asLong()
    }

    fun runBatch(name: String) = Batches.run(name).also { assertEquals(200, it.status, "$name: $it") }

    fun cancel(item: Long, buyer: Long) =
        Api.post("/order-items/$item/cancel", null, buyer).also { assertEquals(200, it.status, "준비: 구매자 취소: $it") }
}
