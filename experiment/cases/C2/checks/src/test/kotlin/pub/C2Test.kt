package pub

import support.Api
import support.Batches
import support.Clock
import support.Db
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class C2Test {

    @Test
    fun `판매 제한이 걸리면 그 판매자의 ACTIVE 판매 입찰이 취소된다(발송 기한 초과 3건)`() {
        val seller = newUser()
        // 나중에 팔릴 판매 입찰 3개(사이즈 각각)와, 팔리지 않고 남아 있을 판매 입찰 2개
        val sold = (1..3).map { newProductSize().also { s -> placeActive(seller, s, "SELL", 50_000) } }
        val keepSize = newProductSize()
        val open = listOf(placeActive(seller, keepSize, "SELL", 80_000), placeActive(seller, keepSize, "SELL", 90_000))

        val paidAt = Clock.now()
        sold.forEach { buyAndPay(it, 50_000) }
        val mark = Db.long("SELECT coalesce(max(id), 0) FROM bid_events")

        // 아무도 발송하지 않은 채 결제 + 3일 → 발송 기한 초과 귀책 3건 → 판매 제한
        val now = paidAt.plus(Duration.ofDays(3))
        assertEquals(200, Clock.moveTo(now).status)
        assertEquals(200, Batches.run("shipment-deadline").status)
        val until = Db.query("SELECT sale_restricted_until FROM users WHERE id = ?", seller).single()["sale_restricted_until"] as java.sql.Timestamp?
        assertEquals(now.plus(Duration.ofDays(30)), until?.toInstant(), "판매 제한이 걸림(준비)")

        for (bid in open) {
            assertEquals("CANCELLED", Db.query("SELECT status FROM bids WHERE id = ?", bid).single()["status"], "입찰 $bid")
            val ev = Db.query("SELECT from_status, to_status, trade_id, created_at FROM bid_events WHERE bid_id = ? AND id > ? ORDER BY id", bid, mark)
            assertEquals(1, ev.size, "입찰 $bid 의 취소 이벤트 1행: $ev")
            assertEquals("ACTIVE", ev[0]["from_status"], "$ev")
            assertEquals("CANCELLED", ev[0]["to_status"], "$ev")
            assertEquals(null, ev[0]["trade_id"], "$ev")
            assertEquals(now, (ev[0]["created_at"] as java.sql.Timestamp).toInstant(), "$ev")
        }
        val book = Api.get("/product-sizes/$keepSize/book")
        assertEquals(200, book.status, "$book")
        assertTrue(book.json["bestSell"].isNull, "호가에서 빠짐: $book")
    }

    @Test
    fun `구매 제한이 걸리면 그 구매자의 ACTIVE 구매 입찰이 취소된다(결제 기한 초과 3건)`() {
        val buyer = newUser()
        val waitingSize = newProductSize()
        val waiting = placeActive(buyer, waitingSize, "BUY", 30_000)
        val trades = (1..3).map {
            val size = newProductSize()
            placeActive(newUser(), size, "SELL", 50_000)
            val r = Api.post("/bids", mapOf("productSizeId" to size, "side" to "BUY", "price" to 50_000, "validDays" to 30), buyer)
            assertEquals("MATCHED", r.json["status"].asText(), "$r")
            r.json["tradeId"].asLong()
        }
        val mark = Db.long("SELECT coalesce(max(id), 0) FROM bid_events")

        // 결제하지 않은 채 결제 기한이 지나 payment-due → 경고 3건 → 구매 제한
        val now = Clock.now().plus(Duration.ofMinutes(30))
        assertEquals(200, Clock.moveTo(now).status)
        assertEquals(200, Batches.run("payment-due").status)
        val until = Db.query("SELECT purchase_restricted_until FROM users WHERE id = ?", buyer).single()["purchase_restricted_until"] as java.sql.Timestamp?
        assertEquals(now.plus(Duration.ofDays(7)), until?.toInstant(), "구매 제한이 걸림(준비): 체결 $trades")

        assertEquals("CANCELLED", Db.query("SELECT status FROM bids WHERE id = ?", waiting).single()["status"])
        val ev = Db.query("SELECT from_status, to_status, trade_id, created_at FROM bid_events WHERE bid_id = ? AND id > ? ORDER BY id", waiting, mark)
        assertEquals(1, ev.size, "취소 이벤트 1행: $ev")
        assertEquals("ACTIVE", ev[0]["from_status"], "$ev")
        assertEquals("CANCELLED", ev[0]["to_status"], "$ev")
        assertEquals(now, (ev[0]["created_at"] as java.sql.Timestamp).toInstant(), "$ev")
        val book = Api.get("/product-sizes/$waitingSize/book")
        assertTrue(book.json["bestBuy"].isNull, "호가에서 빠짐: $book")
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

    private fun placeActive(user: Long, size: Long, side: String, price: Long): Long {
        val r = Api.post("/bids", mapOf("productSizeId" to size, "side" to side, "price" to price, "validDays" to 90), user)
        assertEquals(201, r.status, "$r")
        assertEquals("ACTIVE", r.json["status"].asText(), "$r")
        return r.json["bidId"].asLong()
    }

    private fun buyAndPay(size: Long, price: Long) {
        val buyer = newUser()
        val bid = Api.post("/bids", mapOf("productSizeId" to size, "side" to "BUY", "price" to price, "validDays" to 30), buyer)
        assertEquals("MATCHED", bid.json["status"].asText(), "$bid")
        val pay = Api.post("/orders", mapOf("tradeIds" to listOf(bid.json["tradeId"].asLong()), "pointToUse" to 0), buyer, "pub-" + UUID.randomUUID())
        assertEquals("PAID", pay.json["status"].asText(), "$pay")
    }
}
