package hidden

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
 * S1 숨은 테스트 — 배치는 실행 시점의 대상을 한 번에 모두 처리한다. 단언은 결과만 본다(HTTP 응답 상태·본문, DB 값).
 *  - A(payment-due): 체결 둘을 차례로 만든다(뒤의 것이 trades의 마지막 행). 둘 다 결제 기한을 넘긴 뒤 payment-due 1회 →
 *    두 체결 모두 PAYMENT_EXPIRED(DB), 결제 요청 409 TRADE_NOT_PAYABLE, 구매 입찰 CANCELLED(HTTP).
 *  - B(bid-expiry — 같은 대상 순회를 쓰는 다른 배치): 체결 상대가 없는 판매 입찰 둘을 차례로 건다(뒤의 것이 bids의 마지막 행).
 *    둘 다 유효기간을 넘긴 뒤 bid-expiry 1회 → 두 입찰 모두 EXPIRED(HTTP·DB).
 *    payment-due만 따로 고친 오답(naive-1)을 잡는다.
 * 두 테스트는 각자 현재 시계에서 출발한다(실행 순서 무관).
 */
class S1HiddenTest {

    @Test
    fun `A - payment-due 한 번에 결제 기한이 지난 체결이 마지막 행까지 모두 만료된다`() {
        val first = trade(70_000)
        val last = trade(80_000)
        assertEquals(last.id, Db.long("SELECT max(id) FROM trades"), "준비: 뒤의 체결이 trades의 마지막 행")
        moveTo(listOf(first.id, last.id).maxOf { paymentDueAt(it) }.plus(Duration.ofMinutes(1)))

        val batch = Batches.run("payment-due")
        assertEquals(200, batch.status, "payment-due: $batch")

        for ((name, t) in listOf("앞 체결" to first, "마지막 체결" to last)) {
            assertEquals("PAYMENT_EXPIRED", Db.query("SELECT status FROM trades WHERE id = ?", t.id).single()["status"], "$name 상태(DB, 체결 ${t.id})")
            val pay = Api.post("/orders", mapOf("tradeIds" to listOf(t.id), "pointToUse" to 0), t.buyer, "s1h-" + UUID.randomUUID())
            assertEquals(409, pay.status, "$name 결제 요청: $pay")
            assertEquals("TRADE_NOT_PAYABLE", pay.json["code"]?.asText(), "$name 결제 요청: $pay")
            val bid = Api.get("/bids/${t.buyBid}", t.buyer)
            assertEquals(200, bid.status, "$name 구매 입찰 조회: $bid")
            assertEquals("CANCELLED", bid.json["status"].asText(), "$name 구매 입찰 상태: $bid")
        }
    }

    @Test
    fun `B - bid-expiry 한 번에 유효기간이 지난 입찰이 마지막 행까지 모두 만료된다`() {
        val seller1 = newUser()
        val seller2 = newUser()
        val firstBid = sellBid(seller1, newSize(), 110_000)
        val lastBid = sellBid(seller2, newSize(), 120_000)
        assertEquals(lastBid, Db.long("SELECT max(id) FROM bids"), "준비: 뒤의 입찰이 bids의 마지막 행")
        val expiresAt = listOf(firstBid, lastBid).maxOf {
            (Db.query("SELECT expires_at FROM bids WHERE id = ?", it).single()["expires_at"] as java.sql.Timestamp).toInstant()
        }
        moveTo(expiresAt.plus(Duration.ofMinutes(1)))

        val batch = Batches.run("bid-expiry")
        assertEquals(200, batch.status, "bid-expiry: $batch")

        for ((name, pair) in listOf("앞 입찰" to (firstBid to seller1), "마지막 입찰" to (lastBid to seller2))) {
            val (bidId, seller) = pair
            val view = Api.get("/bids/$bidId", seller)
            assertEquals(200, view.status, "$name 조회: $view")
            assertEquals("EXPIRED", view.json["status"].asText(), "$name 상태(HTTP, 입찰 $bidId): $view")
            assertEquals("EXPIRED", Db.query("SELECT status FROM bids WHERE id = ?", bidId).single()["status"], "$name 상태(DB, 입찰 $bidId)")
        }
    }

    // ---- 준비 도우미(HTTP·DB) ----

    private data class Trade(val id: Long, val buyer: Long, val buyBid: Long)

    private fun moveTo(t: Instant) = Clock.moveTo(t).also { assertTrue(it.status in 200..299, "시계 이동: $it") }

    private fun uniq() = UUID.randomUUID().toString().replace("-", "").take(16)

    private fun newUser(): Long {
        val u = uniq()
        return Db.long(
            "INSERT INTO users(email, name, grade, created_at) VALUES (?, ?, 'BRONZE', TIMESTAMPTZ '2025-01-01 00:00:00+09') RETURNING id",
            "s1h-$u@s1h.test", "s1h-$u",
        )
    }

    private fun newSize(): Long {
        val u = uniq()
        val product = Db.long(
            "INSERT INTO products(category_id, brand, name, model_no, release_price) " +
                "VALUES ((SELECT min(id) FROM categories), 's1h-brand', ?, ?, 100000) RETURNING id",
            "s1h-product-$u", "S1H-$u",
        )
        return Db.long("INSERT INTO product_sizes(product_id, size) VALUES (?, '270') RETURNING id", product)
    }

    private fun sellBid(seller: Long, size: Long, price: Long): Long {
        val r = Api.post("/bids", mapOf("productSizeId" to size, "side" to "SELL", "price" to price, "validDays" to 30), seller)
        assertEquals(201, r.status, "판매 입찰: $r")
        assertEquals("ACTIVE", r.json["status"].asText(), "판매 입찰(상대 없음): $r")
        return r.json["bidId"].asLong()
    }

    /** 새 판매자의 판매 입찰 → 새 구매자의 구매 입찰로 체결 1건. */
    private fun trade(price: Long): Trade {
        val buyer = newUser()
        val size = newSize()
        sellBid(newUser(), size, price)
        val buy = Api.post("/bids", mapOf("productSizeId" to size, "side" to "BUY", "price" to price, "validDays" to 30), buyer)
        assertEquals(201, buy.status, "구매 입찰: $buy")
        assertEquals("MATCHED", buy.json["status"].asText(), "체결: $buy")
        return Trade(buy.json["tradeId"].asLong(), buyer, buy.json["bidId"].asLong())
    }

    private fun paymentDueAt(tradeId: Long): Instant =
        (Db.query("SELECT payment_due_at FROM trades WHERE id = ?", tradeId).single()["payment_due_at"] as java.sql.Timestamp).toInstant()
}
