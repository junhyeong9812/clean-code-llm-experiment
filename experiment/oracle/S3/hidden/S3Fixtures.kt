package hidden

import support.Api
import support.Batches
import support.Clock
import support.Db
import support.Resp
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S3 숨은 테스트 준비 도우미. 사용자·빈 상품 사이즈·판매 제한 시각만 SQL로 넣고, 입찰·체결·결제·위시리스트·가격 알림·배치는 API로 만든다.
 * 판매 제한은 판매 입찰을 건 뒤에 SQL로 건다(제한 중에는 판매 입찰을 새로 걸 수 없다). 시계는 앞으로만 상대 이동한다.
 */
object S3Fx {
    private val n = AtomicLong(System.nanoTime() % 1_000_000_000)

    private fun uniq() = "s3h-${n.incrementAndGet()}"

    fun createUser(): Long {
        val u = uniq()
        return Db.long(
            "INSERT INTO users(email, name, grade, created_at) VALUES (?, ?, 'BRONZE', ?) RETURNING id",
            "$u@s3.test", u, OffsetDateTime.parse("2025-01-01T00:00:00+09:00"),
        )
    }

    /** 입찰이 하나도 없는 새 상품 사이즈. */
    fun createProductSize(): Long {
        val u = uniq()
        val category = Db.long("SELECT min(id) FROM categories")
        val product = Db.long(
            "INSERT INTO products(category_id, brand, name, model_no, release_price) VALUES (?, 's3', ?, ?, 100000) RETURNING id",
            category, "s3-$u", "S3-$u",
        )
        return Db.long("INSERT INTO product_sizes(product_id, size) VALUES (?, '270') RETURNING id", product)
    }

    fun ok(step: String, r: Resp, expected: Int = 200): Resp {
        assertEquals(expected, r.status, "준비 $step: $r")
        return r
    }

    fun bid(user: Long, size: Long, side: String, price: Long, validDays: Int = 90): Resp =
        Api.post("/bids", mapOf("productSizeId" to size, "side" to side, "price" to price, "validDays" to validDays), user)

    fun instant(user: Long, size: Long, side: String): Resp =
        Api.post("/bids", mapOf("productSizeId" to size, "side" to side, "instant" to true), user)

    /** 상대가 없는 자리에 입찰을 건다(ACTIVE). 반환: 입찰 id. */
    fun activeBid(user: Long, size: Long, side: String, price: Long, validDays: Int = 90): Long {
        val r = ok("$side 입찰", bid(user, size, side, price, validDays), 201)
        assertEquals("ACTIVE", r.json["status"].asText(), "준비 ACTIVE 입찰: $r")
        return r.json["bidId"].asLong()
    }

    fun setSaleRestrictedUntil(user: Long, t: Instant) =
        assertEquals(1, Db.update("UPDATE users SET sale_restricted_until = ? WHERE id = ?", OffsetDateTime.ofInstant(t, ZoneOffset.UTC), user))

    fun setPurchaseRestrictedUntil(user: Long, t: Instant) =
        assertEquals(1, Db.update("UPDATE users SET purchase_restricted_until = ? WHERE id = ?", OffsetDateTime.ofInstant(t, ZoneOffset.UTC), user))

    fun saleRestrictedUntil(user: Long): Instant? =
        (Db.query("SELECT sale_restricted_until FROM users WHERE id = ?", user).single()["sale_restricted_until"] as java.sql.Timestamp?)?.toInstant()

    fun moveTo(t: Instant): Instant {
        ok("시계 이동", Clock.moveTo(t))
        return Clock.now()
    }

    fun advance(d: java.time.Duration): Instant = moveTo(Clock.now().plus(d))

    fun book(size: Long): Pair<Long?, Long?> {
        val r = ok("시세", Api.get("/product-sizes/$size/book"))
        fun v(f: String) = r.json[f]?.takeUnless { it.isNull }?.asLong()
        return v("bestBuy") to v("bestSell")
    }

    fun addWish(user: Long, size: Long) = ok("위시리스트 추가", Api.post("/me/wishlist", mapOf("productSizeId" to size), user), 201)

    /** 위시리스트 조회 → {사이즈 id: 최저 판매가}. */
    fun wishlistBestSell(user: Long): Map<Long, Long?> {
        val r = ok("위시리스트 조회", Api.get("/me/wishlist", user))
        return r.json.associate { it["productSizeId"].asLong() to it["bestSell"]?.takeUnless { v -> v.isNull }?.asLong() }
    }

    fun addAlert(user: Long, size: Long, target: Long): Long =
        ok("가격 알림 등록", Api.post("/me/price-alerts", mapOf("productSizeId" to size, "targetPrice" to target), user), 201).json["alertId"].asLong()

    fun scan() = ok("price-alert-scan", Batches.run("price-alert-scan"))

    /** (상태, triggered_bid_id). */
    fun alert(alertId: Long): Pair<String, Long?> {
        val row = Db.query("SELECT status, triggered_bid_id FROM price_alerts WHERE id = ?", alertId).single()
        return row["status"] as String to (row["triggered_bid_id"] as Number?)?.toLong()
    }

    fun pay(buyer: Long, trade: Long): Resp =
        Api.post("/orders", mapOf("tradeIds" to listOf(trade), "pointToUse" to 0), buyer, "s3h-" + UUID.randomUUID())

    /** 체결의 (구매 입찰 id, 판매 입찰 id, 가격). */
    fun trade(tradeId: Long): Triple<Long, Long, Long> {
        val t = Db.query("SELECT buy_bid_id, sell_bid_id, price FROM trades WHERE id = ?", tradeId).single()
        return Triple((t["buy_bid_id"] as Number).toLong(), (t["sell_bid_id"] as Number).toLong(), (t["price"] as Number).toLong())
    }

    /** 판매자가 새 사이즈에 판매 입찰을 걸고, 새 구매자가 그것과 체결해 결제한다. 반환: 주문 항목 id. */
    fun soldItem(seller: Long, price: Long): Long {
        val size = createProductSize()
        activeBid(seller, size, "SELL", price)
        val buyer = createUser()
        val b = ok("BUY 입찰", bid(buyer, size, "BUY", price, 30), 201)
        assertEquals("MATCHED", b.json["status"].asText(), "준비 체결: $b")
        val p = ok("결제", pay(buyer, b.json["tradeId"].asLong()), 201)
        assertEquals("PAID", p.json["status"].asText(), "준비 결제 승인: $p")
        return p.json["items"].single()["orderItemId"].asLong()
    }

    fun shipAndReceive(item: Long, seller: Long) {
        ok("발송", Api.post("/order-items/$item/shipment", null, seller))
        ok("입고", Api.post("/admin/order-items/$item/receipt", null))
    }

    fun inspectFail(item: Long) {
        val r = ok("검수 불합격", Api.post("/admin/order-items/$item/inspection", mapOf("result" to "FAIL", "reason" to "오염")))
        assertEquals("CANCELLED_SELLER_FAULT", r.json["status"].asText(), "준비 판매자 귀책: $r")
    }

    fun penaltyCount(seller: Long): Long = Db.long("SELECT count(*) FROM seller_penalties WHERE seller_id = ?", seller)

    fun mark(): Long = Db.long("SELECT coalesce(max(id), 0) FROM bid_events")

    /** 입찰 자체는 그대로: 상태 ACTIVE, mark 뒤 이벤트 없음. */
    fun assertUntouched(bid: Long, mark: Long, what: String) {
        assertEquals("ACTIVE", Db.query("SELECT status FROM bids WHERE id = ?", bid).single()["status"], "$what: 입찰 $bid 상태")
        val ev = Db.query("SELECT id, from_status, to_status FROM bid_events WHERE bid_id = ? AND id > ?", bid, mark)
        assertTrue(ev.isEmpty(), "$what: 입찰 $bid 에 새 이벤트가 없어야 한다: $ev")
    }
}
