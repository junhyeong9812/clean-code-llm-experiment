package hidden

import support.Api
import support.Batches
import support.Clock
import support.Db
import support.Resp
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals

/**
 * D1 숨은 테스트 준비 도우미. 사용자·빈 상품 사이즈·제한 끝 시각·(등급 대상이 되기 위한) 빈 정산 행만 SQL로 넣고,
 * 입찰·체결·위시리스트·가격 알림·배치는 API로 만든다. 시계는 멈춘 시계라 Clock.now()를 끝 시각으로 넣으면 '끝 = 현재'가 정확히 성립한다.
 * 시계는 앞으로만 옮긴다.
 */
object D1Fx {
    private val n = AtomicLong(System.nanoTime() % 1_000_000_000)

    private fun uniq() = "d1h-${n.incrementAndGet()}"

    private fun ts(t: Instant?) = t?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) }

    fun createUser(purchaseUntil: Instant? = null, saleUntil: Instant? = null): Long {
        val u = uniq()
        return Db.long(
            "INSERT INTO users(email, name, grade, purchase_restricted_until, sale_restricted_until, created_at) VALUES (?, ?, 'BRONZE', ?, ?, ?) RETURNING id",
            "$u@d1.test", u, ts(purchaseUntil), ts(saleUntil), OffsetDateTime.parse("2025-01-01T00:00:00+09:00"),
        )
    }

    /** 입찰이 하나도 없는 새 상품 사이즈. */
    fun createProductSize(): Long {
        val u = uniq()
        val product = Db.long(
            "INSERT INTO products(category_id, brand, name, model_no, release_price) VALUES ((SELECT min(id) FROM categories), 'd1', ?, ?, 100000) RETURNING id",
            "d1-$u", "D1-$u",
        )
        return Db.long("INSERT INTO product_sizes(product_id, size) VALUES (?, '270') RETURNING id", product)
    }

    fun setPurchaseUntil(user: Long, t: Instant?) =
        assertEquals(1, Db.update("UPDATE users SET purchase_restricted_until = ? WHERE id = ?", ts(t), user))

    fun purchaseUntil(user: Long): Instant? =
        (Db.query("SELECT purchase_restricted_until FROM users WHERE id = ?", user).single()["purchase_restricted_until"] as java.sql.Timestamp?)?.toInstant()

    /** 판매자 등급 산정 대상이 되게 빈 정산 행 1개(금액 0, CARRIED)를 넣는다 — 기준 금액 0. */
    fun addEmptySettlement(seller: Long) = assertEquals(
        1,
        Db.update(
            "INSERT INTO settlements(seller_id, period_start, period_end, gross, fee_total, penalty_total, carry_in, net, payout, carry_out, status, paid_at)" +
                " VALUES (?, DATE '2025-01-06', DATE '2025-01-12', 0, 0, 0, 0, 0, 0, 0, 'CARRIED', NULL)",
            seller,
        ),
    )

    fun ok(step: String, r: Resp, expected: Int = 200): Resp {
        assertEquals(expected, r.status, "준비 $step: $r")
        return r
    }

    fun bid(user: Long, size: Long, side: String, price: Long, validDays: Int = 30): Resp =
        Api.post("/bids", mapOf("productSizeId" to size, "side" to side, "price" to price, "validDays" to validDays), user)

    fun instant(user: Long, size: Long, side: String): Resp =
        Api.post("/bids", mapOf("productSizeId" to size, "side" to side, "instant" to true), user)

    /** 상대가 없는 자리에 입찰을 건다(ACTIVE). 반환: 입찰 id. */
    fun activeBid(user: Long, size: Long, side: String, price: Long, validDays: Int = 30): Long {
        val r = ok("$side 입찰", bid(user, size, side, price, validDays), 201)
        assertEquals("ACTIVE", r.json["status"].asText(), "준비 ACTIVE 입찰: $r")
        return r.json["bidId"].asLong()
    }

    fun moveTo(t: Instant): Instant {
        ok("시계 이동", Clock.moveTo(t))
        return Clock.now()
    }

    fun advance(d: java.time.Duration): Instant = moveTo(Clock.now().plus(d))

    fun batch(name: String): Resp = ok(name, Batches.run(name))

    fun addWish(user: Long, size: Long) = ok("위시리스트 추가", Api.post("/me/wishlist", mapOf("productSizeId" to size), user), 201)

    fun addAlert(user: Long, size: Long, target: Long): Long =
        ok("가격 알림 등록", Api.post("/me/price-alerts", mapOf("productSizeId" to size, "targetPrice" to target), user), 201).json["alertId"].asLong()

    fun alertStatus(alertId: Long): String = Db.query("SELECT status FROM price_alerts WHERE id = ?", alertId).single()["status"] as String

    fun alertsOf(user: Long, size: Long): List<Pair<Long, String>> =
        Db.query("SELECT id, status FROM price_alerts WHERE user_id = ? AND product_size_id = ? ORDER BY id", user, size)
            .map { (it["id"] as Number).toLong() to it["status"] as String }

    fun restrictionNotifs(user: Long, kind: String): List<String> =
        Db.query("SELECT dedupe_key FROM notifications WHERE user_id = ? AND kind = ? ORDER BY id", user, kind).map { it["dedupe_key"] as String }

    /** (제한된 사용자, 등급 행의 restricted, grade) — effective_from이 가장 늦은 행. */
    fun latestGrade(seller: Long): Pair<Boolean, String>? =
        Db.query("SELECT restricted, grade FROM seller_grades WHERE seller_id = ? ORDER BY effective_from DESC, id DESC LIMIT 1", seller)
            .singleOrNull()?.let { it["restricted"] as Boolean to it["grade"] as String }

    fun restrictedIds(type: String, after: Long): List<Long> {
        val r = ok("제한 사용자 목록", Api.get("/admin/users/restricted?type=$type&after=$after"))
        return r.json["items"].map { it["userId"].asLong() }
    }

    fun maxUserId(): Long = Db.long("SELECT coalesce(max(id), 0) FROM users")

    // ---- 구매 경고 → 구매 제한 (이전한 계약 테스트용) ----

    /** 같은 구매자의 미결제 체결 n건(사이즈·판매자 각각 새로). 반환: 체결 id. */
    fun unpaidTrades(buyer: Long, n: Int): List<Long> = (1..n).map {
        val size = createProductSize()
        activeBid(createUser(), size, "SELL", 50_000)
        val r = ok("BUY 입찰", bid(buyer, size, "BUY", 50_000), 201)
        assertEquals("MATCHED", r.json["status"].asText(), "준비 체결: $r")
        r.json["tradeId"].asLong()
    }

    fun paymentDueAt(trade: Long): Instant =
        (Db.query("SELECT payment_due_at FROM trades WHERE id = ?", trade).single()["payment_due_at"] as java.sql.Timestamp).toInstant()

    fun tradeStatus(trade: Long): String = Db.query("SELECT status FROM trades WHERE id = ?", trade).single()["status"] as String

    fun warningCount(buyer: Long): Long = Db.long("SELECT count(*) FROM buyer_warnings WHERE user_id = ?", buyer)

    /** 결제 기한 시각으로 옮겨 payment-due를 돌린다. 반환: 그 시각. */
    fun expireAll(trades: List<Long>): Instant {
        val now = moveTo(trades.maxOf { paymentDueAt(it) })
        batch("payment-due")
        trades.forEach { assertEquals("PAYMENT_EXPIRED", tradeStatus(it), "준비 만료: 체결 $it") }
        return now
    }
}
