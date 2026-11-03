package pub

import support.Api
import support.Batches
import support.Clock
import support.Db
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 제보: 발송 기한(결제 후 3일)이 지난 미발송 건의 지연 기록이 하루 늦게 생긴다.
 * 결제 + 3일 정각에 delivery-delay-scan을 돌리면 그 항목의 SHIPMENT_OVERDUE 지연 기록이 1행 생겨야 한다.
 */
class D3Test {

    @Test
    fun `결제 후 3일이 된 미발송 항목은 delivery-delay-scan에서 지연 기록이 생긴다`() {
        val item = paidItem()
        val paidAt = Db.query("SELECT paid_at FROM order_items WHERE id = ?", item).single()["paid_at"] as Timestamp
        val deadline = paidAt.toInstant().plus(Duration.ofDays(3))

        moveTo(deadline.minusSeconds(1))
        assertEquals(200, Batches.run("delivery-delay-scan").status, "delivery-delay-scan")
        assertEquals(0L, overdueRows(item), "발송 기한 1초 전의 지연 기록 수")

        moveTo(deadline)
        assertEquals(200, Batches.run("delivery-delay-scan").status, "delivery-delay-scan")
        assertEquals(1L, overdueRows(item), "발송 기한(결제 + 3일) 정각의 지연 기록 수")
        val status = Db.query("SELECT status FROM order_items WHERE id = ?", item).single()["status"]
        assertEquals("PAID", status, "지연 기록은 항목 상태를 바꾸지 않는다")
    }

    // ---- 준비 도우미(HTTP·DB) ----

    private fun overdueRows(item: Long) =
        Db.long("SELECT count(*) FROM shipment_delays WHERE order_item_id = ? AND kind = 'SHIPMENT_OVERDUE'", item)

    private fun moveTo(t: Instant) = Clock.moveTo(t).also { assertTrue(it.status in 200..299, "시계 이동: $it") }

    private fun uniq() = UUID.randomUUID().toString().replace("-", "").take(16)

    private fun newUser(): Long {
        val u = uniq()
        return Db.long(
            "INSERT INTO users(email, name, grade, created_at) VALUES (?, ?, 'BRONZE', TIMESTAMPTZ '2025-01-01 00:00:00+09') RETURNING id",
            "d3-$u@d3.test", "d3-$u",
        )
    }

    private fun newSize(): Long {
        val u = uniq()
        val product = Db.long(
            "INSERT INTO products(category_id, brand, name, model_no, release_price) " +
                "VALUES ((SELECT min(id) FROM categories), 'd3-brand', ?, ?, 100000) RETURNING id",
            "d3-product-$u", "D3-$u",
        )
        return Db.long("INSERT INTO product_sizes(product_id, size) VALUES (?, '270') RETURNING id", product)
    }

    /** 새 판매자·구매자로 체결 1건을 만들고 결제한다(PAID, 발송 전). 반환: 주문 항목 id. */
    private fun paidItem(): Long {
        val size = newSize()
        val buyer = newUser()
        val sell = Api.post("/bids", mapOf("productSizeId" to size, "side" to "SELL", "price" to 100_000, "validDays" to 30), newUser())
        assertEquals(201, sell.status, "준비: 판매 입찰: $sell")
        val buy = Api.post("/bids", mapOf("productSizeId" to size, "side" to "BUY", "price" to 100_000, "validDays" to 30), buyer)
        assertEquals(201, buy.status, "준비: 구매 입찰: $buy")
        assertEquals("MATCHED", buy.json["status"].asText(), "준비: 체결: $buy")
        val pay = Api.post("/orders", mapOf("tradeIds" to listOf(buy.json["tradeId"].asLong()), "pointToUse" to 0), buyer,
            "d3-" + UUID.randomUUID())
        assertEquals(201, pay.status, "준비: 결제: $pay")
        assertEquals("PAID", pay.json["status"].asText(), "준비: 결제: $pay")
        return pay.json["items"].single()["orderItemId"].asLong()
    }
}
