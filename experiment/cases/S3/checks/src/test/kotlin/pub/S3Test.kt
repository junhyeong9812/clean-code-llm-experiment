package pub

import support.Api
import support.Clock
import support.Db
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class S3Test {

    @Test
    fun `판매 제한 중인 판매자의 판매 입찰은 새 구매 입찰의 상대가 되지 않는다`() {
        val size = newProductSize()
        val restricted = newUser()
        val other = newUser()
        // 제한이 걸리기 전에 건 더 싼 판매 입찰, 다른 판매자의 판매 입찰
        val hidden = placeActive(restricted, size, "SELL", 90_000)
        val open = placeActive(other, size, "SELL", 100_000)
        val until = Clock.now().plus(Duration.ofDays(10))
        assertEquals(1, Db.update("UPDATE users SET sale_restricted_until = ? WHERE id = ?", OffsetDateTime.ofInstant(until, ZoneOffset.UTC), restricted))

        val r = Api.post("/bids", mapOf("productSizeId" to size, "side" to "BUY", "price" to 120_000, "validDays" to 30), newUser())
        assertEquals(201, r.status, "$r")
        assertEquals("MATCHED", r.json["status"].asText(), "$r")
        val trade = Db.query("SELECT sell_bid_id, price FROM trades WHERE id = ?", r.json["tradeId"].asLong()).single()
        assertEquals(open, (trade["sell_bid_id"] as Number).toLong(), "다른 판매자의 판매 입찰과 거래: $trade")
        assertEquals(100_000L, (trade["price"] as Number).toLong(), "$trade")
        assertEquals("ACTIVE", Db.query("SELECT status FROM bids WHERE id = ?", hidden).single()["status"], "입찰 자체는 그대로")
    }

    private var n = System.nanoTime() % 1_000_000_000

    private fun newUser(): Long {
        val u = "pub-s3-${++n}"
        return Db.long(
            "INSERT INTO users(email, name, grade, created_at) VALUES (?, ?, 'BRONZE', ?) RETURNING id",
            "$u@pub.test", u, OffsetDateTime.parse("2025-01-01T00:00:00+09:00"),
        )
    }

    private fun newProductSize(): Long {
        val u = "pub-s3-${++n}"
        val product = Db.long(
            "INSERT INTO products(category_id, brand, name, model_no, release_price) VALUES ((SELECT min(id) FROM categories), 'pub', ?, ?, 100000) RETURNING id",
            u, u,
        )
        return Db.long("INSERT INTO product_sizes(product_id, size) VALUES (?, '270') RETURNING id", product)
    }

    private fun placeActive(user: Long, size: Long, side: String, price: Long): Long {
        val r = Api.post("/bids", mapOf("productSizeId" to size, "side" to side, "price" to price, "validDays" to 90), user)
        assertEquals(201, r.status, "$r")
        assertEquals("ACTIVE", r.json["status"].asText(), "$r")
        return r.json["bidId"].asLong()
    }
}
