package pub

import support.Api
import support.Clock
import support.Db
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class D1Test {

    @Test
    fun `구매 제한 끝 시각이 현재 시각과 같으면 아직 구매 제한 중이다 - 구매 입찰 403`() {
        val size = newProductSize()
        val user = newUser()
        val until = Clock.now()
        assertEquals(1, Db.update("UPDATE users SET purchase_restricted_until = ? WHERE id = ?", OffsetDateTime.ofInstant(until, ZoneOffset.UTC), user))

        val r = Api.post("/bids", mapOf("productSizeId" to size, "side" to "BUY", "price" to 90_000, "validDays" to 30), user)
        assertEquals(403, r.status, "$r")
        assertEquals("PURCHASE_RESTRICTED", r.json["code"].asText(), "$r")
    }

    private var n = System.nanoTime() % 1_000_000_000

    private fun newUser(): Long {
        val u = "pub-d1-${++n}"
        return Db.long(
            "INSERT INTO users(email, name, grade, created_at) VALUES (?, ?, 'BRONZE', ?) RETURNING id",
            "$u@pub.test", u, OffsetDateTime.parse("2025-01-01T00:00:00+09:00"),
        )
    }

    private fun newProductSize(): Long {
        val u = "pub-d1-${++n}"
        val product = Db.long(
            "INSERT INTO products(category_id, brand, name, model_no, release_price) VALUES ((SELECT min(id) FROM categories), 'pub', ?, ?, 100000) RETURNING id",
            u, u,
        )
        return Db.long("INSERT INTO product_sizes(product_id, size) VALUES (?, '270') RETURNING id", product)
    }
}
