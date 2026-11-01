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
 * 증상: 결제 기한이 지난 체결 가운데 가장 나중에 만들어진 하나가 payment-due 배치를 한 번 돌려도 만료되지 않는다.
 */
class S1Test {

    @Test
    fun `결제 기한이 지난 마지막 체결은 payment-due 한 번에 만료된다`() {
        val buyer = newUser()
        val seller = newUser()
        val size = newSize()
        val sell = Api.post("/bids", mapOf("productSizeId" to size, "side" to "SELL", "price" to 90_000, "validDays" to 30), seller)
        assertEquals(201, sell.status, "판매 입찰: $sell")
        val buy = Api.post("/bids", mapOf("productSizeId" to size, "side" to "BUY", "price" to 90_000, "validDays" to 30), buyer)
        assertEquals(201, buy.status, "구매 입찰: $buy")
        assertEquals("MATCHED", buy.json["status"].asText(), "체결: $buy")
        val tradeId = buy.json["tradeId"].asLong()
        val buyBid = buy.json["bidId"].asLong()

        // 그 뒤로 다른 체결을 만들지 않고 결제 기한 1분 뒤에 payment-due를 한 번 돌린다
        val due = (Db.query("SELECT payment_due_at FROM trades WHERE id = ?", tradeId).single()["payment_due_at"] as java.sql.Timestamp).toInstant()
        moveTo(due.plus(Duration.ofMinutes(1)))
        val batch = Batches.run("payment-due")
        assertEquals(200, batch.status, "payment-due: $batch")

        val pay = Api.post("/orders", mapOf("tradeIds" to listOf(tradeId), "pointToUse" to 0), buyer, "s1-" + UUID.randomUUID())
        assertEquals(409, pay.status, "만료된 체결의 결제 요청: $pay")
        assertEquals("TRADE_NOT_PAYABLE", pay.json["code"]?.asText(), "만료된 체결의 결제 요청: $pay")
        val bid = Api.get("/bids/$buyBid", buyer)
        assertEquals(200, bid.status, "구매 입찰 조회: $bid")
        assertEquals("CANCELLED", bid.json["status"].asText(), "구매 입찰 상태: $bid")
        assertEquals("PAYMENT_EXPIRED", Db.query("SELECT status FROM trades WHERE id = ?", tradeId).single()["status"], "체결 상태(DB)")
    }

    // ---- 준비 도우미(HTTP·DB) ----

    private fun moveTo(t: Instant) = Clock.moveTo(t).also { assertTrue(it.status in 200..299, "시계 이동: $it") }

    private fun uniq() = UUID.randomUUID().toString().replace("-", "").take(16)

    private fun newUser(): Long {
        val u = uniq()
        return Db.long(
            "INSERT INTO users(email, name, grade, created_at) VALUES (?, ?, 'BRONZE', TIMESTAMPTZ '2025-01-01 00:00:00+09') RETURNING id",
            "s1-$u@s1.test", "s1-$u",
        )
    }

    private fun newSize(): Long {
        val u = uniq()
        val product = Db.long(
            "INSERT INTO products(category_id, brand, name, model_no, release_price) " +
                "VALUES ((SELECT min(id) FROM categories), 's1-brand', ?, ?, 100000) RETURNING id",
            "s1-product-$u", "S1-$u",
        )
        return Db.long("INSERT INTO product_sizes(product_id, size) VALUES (?, '270') RETURNING id", product)
    }
}
