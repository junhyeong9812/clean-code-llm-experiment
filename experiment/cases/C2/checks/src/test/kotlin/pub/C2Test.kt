package pub

import support.Api
import support.Clock
import support.Db
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class C2Test {

    @Test
    fun `X-User-Id 없이 부르면 401 UNKNOWN_USER`() {
        val r = Api.get("/me/penalties")
        assertEquals(401, r.status, "$r")
        assertEquals("UNKNOWN_USER", r.json["code"]?.asText(), "$r")
    }

    @Test
    fun `검수 불합격으로 패널티 1건이 생긴 판매자의 조회`() {
        val seller = newUser()
        val size = newProductSize()
        val price = 50_000L
        assertEquals(201, Api.post("/bids", mapOf("productSizeId" to size, "side" to "SELL", "price" to price, "validDays" to 30), seller).status)
        val buyer = newUser()
        val bid = Api.post("/bids", mapOf("productSizeId" to size, "side" to "BUY", "price" to price, "validDays" to 30), buyer)
        assertEquals("MATCHED", bid.json["status"].asText(), "$bid")
        val pay = Api.post("/orders", mapOf("tradeIds" to listOf(bid.json["tradeId"].asLong()), "pointToUse" to 0), buyer, "pub-" + UUID.randomUUID())
        assertEquals("PAID", pay.json["status"].asText(), "$pay")
        val item = pay.json["items"][0]["orderItemId"].asLong()
        assertEquals(200, Api.post("/order-items/$item/shipment", null, seller).status)
        assertEquals(200, Api.post("/admin/order-items/$item/receipt", null).status)
        val fail = Api.post("/admin/order-items/$item/inspection", mapOf("result" to "FAIL", "reason" to "오염"))
        assertEquals("CANCELLED_SELLER_FAULT", fail.json["status"].asText(), "$fail")
        val now = Clock.now()

        val r = Api.get("/me/penalties", seller)
        assertEquals(200, r.status, "$r")
        assertTrue(r.json.isArray && r.json.size() == 1, "패널티 1건: $r")
        val p = r.json[0]
        assertTrue(p["penaltyId"].isIntegralNumber, "$r")
        assertEquals(item, p["orderItemId"].asLong(), "$r")
        assertTrue(p["amount"].isIntegralNumber && p["amount"].asLong() > 0, "금액은 양의 정수(원): $r")
        assertEquals(now, OffsetDateTime.parse(p["createdAt"].asText()).toInstant(), "$r")
        assertEquals(false, p["settled"].asBoolean(true), "$r")

        val empty = Api.get("/me/penalties", buyer)
        assertEquals(200, empty.status, "$empty")
        assertTrue(empty.json.isArray && empty.json.size() == 0, "패널티 없는 사용자: $empty")
    }

    private var n = System.nanoTime() % 1_000_000_000

    private fun newUser(): Long {
        val u = "pub-c2-${++n}"
        return Db.long(
            "INSERT INTO users(email, name, grade, created_at) VALUES (?, ?, 'BRONZE', ?) RETURNING id",
            "$u@pub.test", u, OffsetDateTime.parse("2025-01-01T00:00:00+09:00"),
        )
    }

    private fun newProductSize(): Long {
        val u = "pub-c2-${++n}"
        val product = Db.long(
            "INSERT INTO products(category_id, brand, name, model_no, release_price) VALUES ((SELECT min(id) FROM categories), 'pub', ?, ?, 100000) RETURNING id",
            u, u,
        )
        return Db.long("INSERT INTO product_sizes(product_id, size) VALUES (?, '270') RETURNING id", product)
    }
}
