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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S2 숨은 테스트 — 판매자 귀책 패널티의 판매 제한 기준(30일 안 3건). 단언은 결과만 본다(HTTP 응답 상태·code, DB 값).
 * 패널티는 shipment-deadline(결제 + 3일, 발송 기한 초과)으로 만든다. 판매자는 판매 입찰을 미리 걸어 두고
 * (제한은 새 입찰에만) 새 구매자가 체결·결제한다.
 *  - A: 2건이면 제한 없음(새 판매 입찰 201), 하루 뒤 3건째에 sale_restricted_until = 현재 + 30일, 새 판매 입찰 403 SALE_RESTRICTED.
 *  - B: 한 번의 배치에서 같은 시각 3건 → 제한(방금 쓴 패널티도 센다).
 *  - C: 30일 창 — 판매자 둘이 같은 시각 t에 2건씩. t+29일의 3건째는 제한, t+30일(정확히 30일 뒤)의 3건째는 제한 없음.
 *  - D: 형제 규칙(구매 경고, payment-due)은 그대로 — 경고 2건이면 새 구매 입찰 201, 3건째에 403 PURCHASE_RESTRICTED.
 * 테스트는 각자 현재 시계에서 출발한다(실행 순서 무관).
 */
class S2HiddenTest {

    private val price = 50_000L

    @Test
    fun `A - 판매자 귀책 2건이면 판매 제한이 없고 3건째에 현재 + 30일 판매 제한`() {
        val seller = newUser()
        val sizes = (1..3).map { newSize().also { s -> sellBid(seller, s) } }

        fault(sizes.take(2).map { buyAndPay(it) })
        assertNull(saleRestrictedUntil(seller), "2건 뒤 sale_restricted_until")
        assertEquals(201, newSellBid(seller).status, "2건 뒤 새 판매 입찰의 응답 상태")

        moveTo(Clock.now().plus(Duration.ofDays(1)))
        val now = fault(listOf(buyAndPay(sizes[2])))
        assertEquals(now.plus(Duration.ofDays(30)), saleRestrictedUntil(seller), "3건째 sale_restricted_until = 현재 + 30일")
        val r = newSellBid(seller)
        assertEquals(403, r.status, "3건째 뒤 새 판매 입찰: $r")
        assertEquals("SALE_RESTRICTED", r.json["code"].asText(), "3건째 뒤 새 판매 입찰 code: $r")
    }

    @Test
    fun `B - 한 번의 배치에서 같은 시각 3건이면 판매 제한`() {
        val seller = newUser()
        val sizes = (1..3).map { newSize().also { s -> sellBid(seller, s) } }
        val now = fault(sizes.map { buyAndPay(it) })
        assertEquals(now.plus(Duration.ofDays(30)), saleRestrictedUntil(seller), "같은 시각 3건 sale_restricted_until = 현재 + 30일")
        val r = newSellBid(seller)
        assertEquals(403, r.status, "같은 시각 3건 뒤 새 판매 입찰: $r")
        assertEquals("SALE_RESTRICTED", r.json["code"].asText(), "같은 시각 3건 뒤 새 판매 입찰 code: $r")
    }

    @Test
    fun `C - 30일 창 - 29일 뒤의 3건째는 제한, 정확히 30일 뒤의 3건째는 제한 없음`() {
        val inWindow = newUser()
        val atEdge = newUser()
        val a = (1..3).map { newSize().also { s -> sellBid(inWindow, s) } }
        val b = (1..3).map { newSize().also { s -> sellBid(atEdge, s) } }

        val t = fault(a.take(2).map { buyAndPay(it) } + b.take(2).map { buyAndPay(it) })
        assertNull(saleRestrictedUntil(inWindow), "t의 2건 뒤(창 안 판매자)")
        assertNull(saleRestrictedUntil(atEdge), "t의 2건 뒤(경계 판매자)")

        moveTo(t.plus(Duration.ofDays(26)))
        val a3 = buyAndPay(a[2])
        moveTo(t.plus(Duration.ofDays(27)))
        val b3 = buyAndPay(b[2])

        val t29 = fault(listOf(a3))
        assertEquals(t.plus(Duration.ofDays(29)), t29, "준비: 3건째 시각")
        assertEquals(t29.plus(Duration.ofDays(30)), saleRestrictedUntil(inWindow), "29일 전 2건 + 3건째 → 현재 + 30일")

        val t30 = fault(listOf(b3))
        assertEquals(t.plus(Duration.ofDays(30)), t30, "준비: 3건째 시각")
        assertNull(saleRestrictedUntil(atEdge), "정확히 30일 전 2건은 창 밖 — 제한 없음")
        assertEquals(201, newSellBid(atEdge).status, "창 밖 2건 + 1건 뒤 새 판매 입찰의 응답 상태")
    }

    @Test
    fun `D - 구매 경고는 그대로 - 2건이면 새 구매 입찰 가능, 3건째에 구매 제한`() {
        val buyer = newUser()
        repeat(2) { matchUnpaid(buyer) }
        expire()
        assertNull(purchaseRestrictedUntil(buyer), "경고 2건 뒤 purchase_restricted_until")
        assertEquals(201, newBuyBid(buyer).status, "경고 2건 뒤 새 구매 입찰의 응답 상태")

        matchUnpaid(buyer)
        val now = expire()
        assertEquals(now.plus(Duration.ofDays(7)), purchaseRestrictedUntil(buyer), "경고 3건째 purchase_restricted_until = 현재 + 7일")
        val r = newBuyBid(buyer)
        assertEquals(403, r.status, "경고 3건째 뒤 새 구매 입찰: $r")
        assertEquals("PURCHASE_RESTRICTED", r.json["code"].asText(), "경고 3건째 뒤 새 구매 입찰 code: $r")
    }

    // ---- 도우미(HTTP·DB) ----

    private fun moveTo(t: Instant) = Clock.moveTo(t).also { assertTrue(it.status in 200..299, "시계 이동: $it") }

    private fun uniq() = UUID.randomUUID().toString().replace("-", "").take(16)

    private fun inst(v: Any?): Instant? = when (v) {
        null -> null
        is java.sql.Timestamp -> v.toInstant()
        is OffsetDateTime -> v.toInstant()
        else -> error("시각 아님: $v")
    }

    private fun saleRestrictedUntil(user: Long): Instant? =
        inst(Db.query("SELECT sale_restricted_until FROM users WHERE id = ?", user).single()["sale_restricted_until"])

    private fun purchaseRestrictedUntil(user: Long): Instant? =
        inst(Db.query("SELECT purchase_restricted_until FROM users WHERE id = ?", user).single()["purchase_restricted_until"])

    private fun newUser(): Long {
        val u = uniq()
        return Db.long(
            "INSERT INTO users(email, name, grade, created_at) VALUES (?, ?, 'BRONZE', TIMESTAMPTZ '2025-01-01 00:00:00+09') RETURNING id",
            "s2h-$u@s2h.test", "s2h-$u",
        )
    }

    private fun newSize(): Long {
        val u = uniq()
        val product = Db.long(
            "INSERT INTO products(category_id, brand, name, model_no, release_price) " +
                "VALUES ((SELECT min(id) FROM categories), 's2h-brand', ?, ?, 100000) RETURNING id",
            "s2h-product-$u", "S2H-$u",
        )
        return Db.long("INSERT INTO product_sizes(product_id, size) VALUES (?, '270') RETURNING id", product)
    }

    private fun bid(user: Long, size: Long, side: String, price: Long, validDays: Int): Resp =
        Api.post("/bids", mapOf("productSizeId" to size, "side" to side, "price" to price, "validDays" to validDays), user)

    private fun sellBid(seller: Long, size: Long) {
        val r = bid(seller, size, "SELL", price, 90)
        assertEquals(201, r.status, "준비: 판매 입찰: $r")
    }

    private fun newSellBid(seller: Long): Resp = bid(seller, newSize(), "SELL", 90_000, 30)

    private fun newBuyBid(buyer: Long): Resp = bid(buyer, newSize(), "BUY", 10_000, 30)

    /** 새 구매자가 그 사이즈에 구매 입찰을 걸어 체결하고 포인트 없이 결제한다. 반환: 주문 항목 id. */
    private fun buyAndPay(size: Long): Long {
        val buyer = newUser()
        val buy = bid(buyer, size, "BUY", price, 30)
        assertEquals(201, buy.status, "준비: 구매 입찰: $buy")
        assertEquals("MATCHED", buy.json["status"].asText(), "준비: 체결: $buy")
        val pay = Api.post("/orders", mapOf("tradeIds" to listOf(buy.json["tradeId"].asLong()), "pointToUse" to 0), buyer,
            "s2h-" + UUID.randomUUID())
        assertEquals(201, pay.status, "준비: 결제: $pay")
        assertEquals("PAID", pay.json["status"].asText(), "준비: 결제: $pay")
        return pay.json["items"].single()["orderItemId"].asLong()
    }

    /** 그 항목들의 결제 시각 + 3일로 옮겨 shipment-deadline을 돌리고 판매자 귀책 취소를 확인한다(준비). 반환: 현재. */
    private fun fault(items: List<Long>): Instant {
        val paidAt = items.map { inst(Db.query("SELECT o.paid_at FROM orders o JOIN order_items i ON i.order_id = o.id WHERE i.id = ?", it).single()["paid_at"])!! }.max()
        val now = paidAt.plus(Duration.ofDays(3))
        if (now.isAfter(Clock.now())) moveTo(now)
        val r = Batches.run("shipment-deadline")
        assertEquals(200, r.status, "준비: shipment-deadline: $r")
        items.forEach {
            val status = Db.query("SELECT status FROM order_items WHERE id = ?", it).single()["status"]
            assertEquals("CANCELLED_SELLER_FAULT", status, "준비: 판매자 귀책 취소: 항목 $it")
        }
        return Clock.now()
    }

    /** 새 판매자의 판매 입찰에 그 구매자가 구매 입찰로 체결만 하고 결제하지 않는다. */
    private fun matchUnpaid(buyer: Long) {
        val seller = newUser()
        val size = newSize()
        sellBid(seller, size)
        val buy = bid(buyer, size, "BUY", price, 30)
        assertEquals(201, buy.status, "준비: 구매 입찰: $buy")
        assertEquals("MATCHED", buy.json["status"].asText(), "준비: 체결: $buy")
    }

    /** 결제 기한(체결 + 30분)이 지나게 31분 옮기고 payment-due를 돌린다(준비). 반환: 현재. */
    private fun expire(): Instant {
        val now = Clock.now().plus(Duration.ofMinutes(31))
        moveTo(now)
        val r = Batches.run("payment-due")
        assertEquals(200, r.status, "준비: payment-due: $r")
        return now
    }
}
