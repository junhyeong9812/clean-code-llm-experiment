package hidden

import support.Api
import support.Batches
import support.Clock
import support.Db
import support.Resp
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D3 숨은 테스트 준비 도우미. 사용자·빈 상품 사이즈만 SQL로 넣고, 입찰·체결·결제·발송·배치는 API로 한다.
 * 결제는 포인트 없이 체결 1건(100,000원 — 배송비 없음).
 */
object D3Fx {
    private const val PRICE = 100_000L

    class Paid(val item: Long, val seller: Long, val buyer: Long, val paidAt: Instant)

    fun now(): Instant = Clock.now()

    fun moveTo(t: Instant): Instant {
        val r = Clock.moveTo(t)
        assertTrue(r.status in 200..299, "시계 이동: $r")
        return t
    }

    fun advance(seconds: Long): Instant = moveTo(now().plusSeconds(seconds))

    private fun uniq() = UUID.randomUUID().toString().replace("-", "").take(16)

    fun newUser(): Long {
        val u = uniq()
        return Db.long(
            "INSERT INTO users(email, name, grade, created_at) VALUES (?, ?, 'BRONZE', TIMESTAMPTZ '2025-01-01 00:00:00+09') RETURNING id",
            "d3h-$u@d3.test", "d3h-$u",
        )
    }

    fun newSize(): Long {
        val u = uniq()
        val product = Db.long(
            "INSERT INTO products(category_id, brand, name, model_no, release_price) " +
                "VALUES ((SELECT min(id) FROM categories), 'd3h', ?, ?, 100000) RETURNING id",
            "d3h-$u", "D3H-$u",
        )
        return Db.long("INSERT INTO product_sizes(product_id, size) VALUES (?, '270') RETURNING id", product)
    }

    /** 새 판매자·구매자·사이즈로 체결 1건을 만들고 현재 시계로 결제한다(PAID, 발송 전). */
    fun paid(): Paid {
        val size = newSize()
        val seller = newUser()
        val buyer = newUser()
        val sell = Api.post("/bids", mapOf("productSizeId" to size, "side" to "SELL", "price" to PRICE, "validDays" to 30), seller)
        assertEquals(201, sell.status, "준비: 판매 입찰: $sell")
        val buy = Api.post("/bids", mapOf("productSizeId" to size, "side" to "BUY", "price" to PRICE, "validDays" to 30), buyer)
        assertEquals(201, buy.status, "준비: 구매 입찰: $buy")
        assertEquals("MATCHED", buy.json["status"].asText(), "준비: 체결: $buy")
        val pay = Api.post("/orders", mapOf("tradeIds" to listOf(buy.json["tradeId"].asLong()), "pointToUse" to 0), buyer,
            "d3h-" + UUID.randomUUID())
        assertEquals(201, pay.status, "준비: 결제: $pay")
        assertEquals("PAID", pay.json["status"].asText(), "준비: 결제: $pay")
        val item = pay.json["items"].single()["orderItemId"].asLong()
        return Paid(item, seller, buyer, paidAt(item))
    }

    fun paidAt(item: Long): Instant =
        (Db.query("SELECT paid_at FROM order_items WHERE id = ?", item).single()["paid_at"] as Timestamp).toInstant()

    fun status(item: Long): String = Db.query("SELECT status FROM order_items WHERE id = ?", item).single()["status"] as String

    fun ship(p: Paid): Resp = Api.post("/order-items/${p.item}/shipment", null, p.seller)

    fun runBatch(name: String) = Batches.run(name).also { assertEquals(200, it.status, "$name: $it") }

    /** 그 항목의 마감 임박 알림(SHIPMENT_DUE_SOON) 수 — 판매자에게 간 것. */
    fun dueSoon(p: Paid): Long =
        Db.long("SELECT count(*) FROM notifications WHERE dedupe_key = ? AND user_id = ? AND kind = 'SHIPMENT_DUE_SOON'",
            "SHIPMENT_DUE_SOON:${p.item}", p.seller)

    /** 그 항목의 지연 기록(SHIPMENT_OVERDUE) 수. */
    fun overdue(p: Paid): Long =
        Db.long("SELECT count(*) FROM shipment_delays WHERE order_item_id = ? AND kind = 'SHIPMENT_OVERDUE'", p.item)
}
