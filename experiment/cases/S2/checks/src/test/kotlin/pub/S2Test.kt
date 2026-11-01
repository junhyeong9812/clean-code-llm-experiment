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
 * 증상: 판매자 때문에 취소된 거래가 두 번뿐인데 그 판매자의 새 판매 입찰이 거절된다(판매 정지는 세 번째부터).
 */
class S2Test {

    @Test
    fun `판매자 때문에 취소된 거래가 두 번이면 새 판매 입찰을 걸 수 있다`() {
        val seller = newUser()
        val sizes = listOf(newSize(), newSize())
        sizes.forEach { sellBid(seller, it) }

        // 서로 다른 구매자 두 명이 사서 결제한다
        val paidAt = Clock.now()
        val items = sizes.map { buyAndPay(it) }

        // 판매자는 보내지 않는다 → 결제 3일 뒤 shipment-deadline
        moveTo(paidAt.plus(Duration.ofDays(3)))
        val batch = Batches.run("shipment-deadline")
        assertEquals(200, batch.status, "shipment-deadline: $batch")
        items.forEach {
            val status = Db.query("SELECT status FROM order_items WHERE id = ?", it).single()["status"].toString()
            assertTrue(status.startsWith("CANCELLED"), "준비: 주문 항목 $it 이 취소되지 않음($status)")
        }

        // 다른 사이즈에 새 판매 입찰
        val r = Api.post("/bids", mapOf("productSizeId" to newSize(), "side" to "SELL", "price" to 90_000, "validDays" to 30), seller)
        assertEquals(201, r.status, "새 판매 입찰의 응답 상태")
    }

    // ---- 준비 도우미(HTTP·DB) ----

    private val price = 50_000L

    private fun moveTo(t: Instant) = Clock.moveTo(t).also { assertTrue(it.status in 200..299, "시계 이동: $it") }

    private fun uniq() = UUID.randomUUID().toString().replace("-", "").take(16)

    private fun newUser(): Long {
        val u = uniq()
        return Db.long(
            "INSERT INTO users(email, name, grade, created_at) VALUES (?, ?, 'BRONZE', TIMESTAMPTZ '2025-01-01 00:00:00+09') RETURNING id",
            "s2-$u@s2.test", "s2-$u",
        )
    }

    private fun newSize(): Long {
        val u = uniq()
        val product = Db.long(
            "INSERT INTO products(category_id, brand, name, model_no, release_price) " +
                "VALUES ((SELECT min(id) FROM categories), 's2-brand', ?, ?, 100000) RETURNING id",
            "s2-product-$u", "S2-$u",
        )
        return Db.long("INSERT INTO product_sizes(product_id, size) VALUES (?, '270') RETURNING id", product)
    }

    private fun sellBid(seller: Long, size: Long) {
        val r = Api.post("/bids", mapOf("productSizeId" to size, "side" to "SELL", "price" to price, "validDays" to 90), seller)
        assertEquals(201, r.status, "준비: 판매 입찰: $r")
    }

    /** 새 구매자가 그 사이즈에 구매 입찰을 걸어 체결하고 포인트 없이 결제한다. 반환: 주문 항목 id. */
    private fun buyAndPay(size: Long): Long {
        val buyer = newUser()
        val buy = Api.post("/bids", mapOf("productSizeId" to size, "side" to "BUY", "price" to price, "validDays" to 30), buyer)
        assertEquals(201, buy.status, "준비: 구매 입찰: $buy")
        assertEquals("MATCHED", buy.json["status"].asText(), "준비: 체결: $buy")
        val pay = Api.post("/orders", mapOf("tradeIds" to listOf(buy.json["tradeId"].asLong()), "pointToUse" to 0), buyer,
            "s2-" + UUID.randomUUID())
        assertEquals(201, pay.status, "준비: 결제: $pay")
        assertEquals("PAID", pay.json["status"].asText(), "준비: 결제: $pay")
        return pay.json["items"].single()["orderItemId"].asLong()
    }
}
