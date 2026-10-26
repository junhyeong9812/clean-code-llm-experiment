package hidden

import support.Api
import support.Batches
import support.Clock
import support.Db
import support.Resp
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C2 숨은 테스트 준비 도우미. 사용자·빈 상품 사이즈·제한 시각(N·R)만 SQL로 넣고, 입찰·체결·결제·발송·입고·검수·배치는 API로 만든다.
 * 시계는 앞으로만 상대 이동한다.
 */
object Fx {
    private val n = AtomicLong(System.nanoTime() % 1_000_000_000)

    private fun uniq() = "c2h-${n.incrementAndGet()}"

    fun createUser(): Long {
        val u = uniq()
        return Db.long(
            "INSERT INTO users(email, name, grade, created_at) VALUES (?, ?, 'BRONZE', ?) RETURNING id",
            "$u@c2.test", u, OffsetDateTime.parse("2025-01-01T00:00:00+09:00"),
        )
    }

    /** 입찰이 하나도 없는 새 상품 사이즈. */
    fun createProductSize(): Long {
        val u = uniq()
        val category = Db.long("SELECT min(id) FROM categories")
        val product = Db.long(
            "INSERT INTO products(category_id, brand, name, model_no, release_price) VALUES (?, 'c2', ?, ?, 100000) RETURNING id",
            category, "c2-$u", "C2-$u",
        )
        return Db.long("INSERT INTO product_sizes(product_id, size) VALUES (?, '270') RETURNING id", product)
    }

    fun ok(step: String, r: Resp, expected: Int = 200): Resp {
        assertEquals(expected, r.status, "준비 $step: $r")
        return r
    }

    fun bid(user: Long, size: Long, side: String, price: Long, validDays: Int = 90): Resp =
        ok("$side 입찰", Api.post("/bids", mapOf("productSizeId" to size, "side" to side, "price" to price, "validDays" to validDays), user), 201)

    /** 상대가 없는 자리에 입찰을 건다(ACTIVE). 반환: 입찰 id. */
    fun activeBid(user: Long, size: Long, side: String, price: Long, validDays: Int = 90): Long {
        val r = bid(user, size, side, price, validDays)
        assertEquals("ACTIVE", r.json["status"].asText(), "준비 ACTIVE 입찰: $r")
        return r.json["bidId"].asLong()
    }

    /** 판매자가 새 사이즈에 판매 입찰을 건다(나중에 buyAndPay로 팔린다). 반환: 사이즈 id. */
    fun sellSize(seller: Long, price: Long): Long = createProductSize().also { activeBid(seller, it, "SELL", price) }

    /** 새 구매자가 그 사이즈의 판매 입찰과 체결하고 결제한다. 반환: 주문 항목 id. */
    fun buyAndPay(size: Long, price: Long): Long {
        val buyer = createUser()
        val b = bid(buyer, size, "BUY", price, 30)
        assertEquals("MATCHED", b.json["status"].asText(), "준비 체결: $b")
        val pay = ok("결제", Api.post("/orders", mapOf("tradeIds" to listOf(b.json["tradeId"].asLong()), "pointToUse" to 0), buyer,
            "c2h-" + UUID.randomUUID()), 201)
        assertEquals("PAID", pay.json["status"].asText(), "준비 결제 승인: $pay")
        return pay.json["items"].single()["orderItemId"].asLong()
    }

    fun shipAndReceive(item: Long, seller: Long) {
        ok("발송", Api.post("/order-items/$item/shipment", null, seller))
        ok("입고", Api.post("/admin/order-items/$item/receipt", null))
    }

    fun inspectFail(item: Long) {
        val r = ok("검수 불합격", Api.post("/admin/order-items/$item/inspection", mapOf("result" to "FAIL", "reason" to "오염")))
        assertEquals("CANCELLED_SELLER_FAULT", r.json["status"].asText(), "준비 판매자 귀책: $r")
    }

    /** 발송 → 입고 → 검수 불합격(판매자 귀책 1건). */
    fun faultByInspection(item: Long, seller: Long) {
        shipAndReceive(item, seller)
        inspectFail(item)
    }

    /** 미결제로 남을 체결: 판매자(새 사용자)의 판매 입찰에 구매자가 같은 가격 구매 입찰로 체결. 반환: 체결 id. */
    fun unpaidTrade(buyer: Long, size: Long = createProductSize(), price: Long = 50_000, seller: Long = createUser()): Long {
        activeBid(seller, size, "SELL", price)
        val b = bid(buyer, size, "BUY", price, 30)
        assertEquals("MATCHED", b.json["status"].asText(), "준비 체결: $b")
        return b.json["tradeId"].asLong()
    }

    /** 체결들의 결제 기한(가장 늦은 것)으로 시계를 옮겨 payment-due를 돌린다. 반환: 현재. */
    fun expireUnpaid(trades: List<Long>): Instant {
        val due = trades.maxOf { (Db.query("SELECT payment_due_at FROM trades WHERE id = ?", it).single()["payment_due_at"] as java.sql.Timestamp).toInstant() }
        val now = moveTo(maxOf(due, Clock.now()))
        ok("payment-due", Batches.run("payment-due"))
        trades.forEach { assertEquals("PAYMENT_EXPIRED", tradeStatus(it), "준비 결제 기한 만료: 체결 $it") }
        return now
    }

    fun moveTo(t: Instant): Instant {
        ok("시계 이동", Clock.moveTo(t))
        return Clock.now()
    }

    fun advance(d: Duration): Instant = moveTo(Clock.now().plus(d))

    fun tradeStatus(trade: Long): String = Db.query("SELECT status FROM trades WHERE id = ?", trade).single()["status"] as String

    fun itemStatus(item: Long): String = Db.query("SELECT status FROM order_items WHERE id = ?", item).single()["status"] as String

    private fun ts(v: Any?): Instant? = (v as java.sql.Timestamp?)?.toInstant()

    fun saleRestrictedUntil(user: Long): Instant? =
        ts(Db.query("SELECT sale_restricted_until FROM users WHERE id = ?", user).single()["sale_restricted_until"])

    fun purchaseRestrictedUntil(user: Long): Instant? =
        ts(Db.query("SELECT purchase_restricted_until FROM users WHERE id = ?", user).single()["purchase_restricted_until"])

    fun setSaleRestrictedUntil(user: Long, t: Instant) =
        assertEquals(1, Db.update("UPDATE users SET sale_restricted_until = ? WHERE id = ?", OffsetDateTime.ofInstant(t, java.time.ZoneOffset.UTC), user))

    fun setPurchaseRestrictedUntil(user: Long, t: Instant) =
        assertEquals(1, Db.update("UPDATE users SET purchase_restricted_until = ? WHERE id = ?", OffsetDateTime.ofInstant(t, java.time.ZoneOffset.UTC), user))

    fun penaltyCount(seller: Long): Long = Db.long("SELECT count(*) FROM seller_penalties WHERE seller_id = ?", seller)

    fun warningCount(buyer: Long): Long = Db.long("SELECT count(*) FROM buyer_warnings WHERE user_id = ?", buyer)

    fun book(size: Long): Pair<Long?, Long?> {
        val r = ok("호가", Api.get("/product-sizes/$size/book"))
        fun v(f: String) = r.json[f]?.takeUnless { it.isNull }?.asLong()
        return v("bestBuy") to v("bestSell")
    }
}

/** bid_events 1행. */
data class Ev(val id: Long, val from: String?, val to: String, val tradeId: Long?, val createdAt: Instant)

/** 입찰 상태·이벤트 단언. mark = 그 시점의 bid_events 최대 id(그 뒤에 생긴 이벤트만 본다). */
object Bids {
    fun mark(): Long = Db.long("SELECT coalesce(max(id), 0) FROM bid_events")

    fun status(bid: Long): String = Db.query("SELECT status FROM bids WHERE id = ?", bid).single()["status"] as String

    fun eventsAfter(bid: Long, mark: Long): List<Ev> =
        Db.query("SELECT id, from_status, to_status, trade_id, created_at FROM bid_events WHERE bid_id = ? AND id > ? ORDER BY id", bid, mark).map {
            Ev((it["id"] as Number).toLong(), it["from_status"] as String?, it["to_status"] as String, (it["trade_id"] as Number?)?.toLong(),
                (it["created_at"] as java.sql.Timestamp).toInstant())
        }

    /** 이번에 취소됨: 상태 CANCELLED, mark 뒤 이벤트가 정확히 1행(ACTIVE → CANCELLED, trade_id NULL, created_at = now). */
    fun assertCancelled(bid: Long, mark: Long, now: Instant, what: String) {
        assertEquals("CANCELLED", status(bid), "$what: 입찰 $bid 상태")
        val ev = eventsAfter(bid, mark)
        assertEquals(1, ev.size, "$what: 입찰 $bid 의 새 이벤트는 정확히 1행: $ev")
        val e = ev.single()
        assertEquals("ACTIVE", e.from, "$what: 입찰 $bid 이벤트 from: $e")
        assertEquals("CANCELLED", e.to, "$what: 입찰 $bid 이벤트 to: $e")
        assertNull(e.tradeId, "$what: 입찰 $bid 이벤트 trade_id: $e")
        assertEquals(now, e.createdAt, "$what: 입찰 $bid 이벤트 created_at = 현재: $e")
    }

    /** 그대로: 상태가 expected이고 mark 뒤 이벤트가 없다. */
    fun assertUntouched(bid: Long, mark: Long, expected: String, what: String) {
        assertEquals(expected, status(bid), "$what: 입찰 $bid 상태")
        val ev = eventsAfter(bid, mark)
        assertTrue(ev.isEmpty(), "$what: 입찰 $bid 에 새 이벤트가 없어야 한다: $ev")
    }
}
