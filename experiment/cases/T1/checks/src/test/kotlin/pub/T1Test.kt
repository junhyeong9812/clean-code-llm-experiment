package pub

import support.Api
import support.Batches
import support.Clock
import support.Db
import support.Resp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 증상: 먼저 결제했지만 나중에 구매 확정한 포인트(A)와, 나중에 결제했지만 먼저 확정한 포인트(B)를 가진 사용자가
 * 포인트를 쓴 뒤 B의 유효기간이 끝나면, 쓰지 않은 B가 소멸돼 남은 포인트가 줄어든다.
 */
class T1Test {

    @Test
    fun `유효기간이 먼저 끝나는 포인트를 먼저 써서 쓰고 남은 포인트가 소멸되지 않는다`() {
        val buyer = newUser()

        // A를 먼저 결제하고, 시계를 조금 옮겨 B를 결제한다
        val a = purchase(buyer, 100_000)
        Clock.advance(Duration.ofMinutes(1))
        val b = purchase(buyer, 60_000)
        deliver(a)
        deliver(b)

        // B를 먼저 확정하고, 이틀 뒤 A를 확정한다 → B의 유효기간이 A보다 이틀 먼저 끝난다
        confirm(b, buyer)
        Clock.advance(Duration.ofDays(2))
        confirm(a, buyer)

        val before = points(buyer)
        assertTrue(before.usable > 1_000, "준비: 두 묶음 모두 사용 가능해야 한다 $before")
        // 두 적립의 만료 시각(사용 전에 읽는다 — 이 시점 그 사용자의 원장 행은 적립 둘뿐)
        val ends = Db.query(
            "SELECT (extract(epoch FROM expires_at) * 1000)::bigint AS ms FROM point_ledger" +
                " WHERE user_id = ? AND expires_at IS NOT NULL ORDER BY 1",
            buyer,
        ).map { Instant.ofEpochMilli((it["ms"] as Number).toLong()) }
        assertEquals(2, ends.size, "준비: 유효기간이 정해진 적립이 두 건이어야 한다 $ends")

        // 1,000P 사용(B의 적립보다 크고 A+B보다 작다)
        val pay = pay(buyer, listOf(trade(buyer, 100_000)), 1_000)
        assertEquals(201, pay.status, "포인트 사용 결제: $pay")
        assertEquals("PAID", pay.json["status"].asText(), "포인트 사용 결제: $pay")

        // B의 유효기간만 끝나고 A는 아직 남은 시각(두 적립의 만료 시각 사이)으로 옮기고 소멸 배치를 돌린다
        Clock.moveTo(ends[0].plus(Duration.between(ends[0], ends[1]).dividedBy(2)))
        val batch = Batches.run("point-expiry")
        assertEquals(200, batch.status, "point-expiry: $batch")

        // B가 먼저 쓰였다면 B에는 남은 것이 없어 소멸할 것이 없다 — 쓰고 남은 포인트가 그대로 있어야 한다
        val after = points(buyer)
        assertEquals(before.usable - 1_000, after.usable, "쓰고 남은 사용 가능 포인트: 전 $before, 후 $after")
        assertEquals(before.available - 1_000, after.available, "쓰고 남은 포인트: 전 $before, 후 $after")
    }

    // ---- 준비 도우미(HTTP·DB) ----

    private data class Points(val available: Long, val pending: Long, val usable: Long)

    private fun points(user: Long): Points {
        val r = Api.get("/me/points", user)
        assertEquals(200, r.status, "포인트 조회: $r")
        return Points(r.json["available"].asLong(), r.json["pending"].asLong(), r.json["usable"].asLong())
    }

    private fun uniq() = UUID.randomUUID().toString().replace("-", "").take(16)

    private fun newUser(): Long {
        val u = uniq()
        return Db.long(
            "INSERT INTO users(email, name, grade, created_at) VALUES (?, ?, 'BRONZE', TIMESTAMPTZ '2025-01-01 00:00:00+09') RETURNING id",
            "t1-$u@t1.test", "t1-$u",
        )
    }

    private fun newSize(): Long {
        val u = uniq()
        val product = Db.long(
            "INSERT INTO products(category_id, brand, name, model_no, release_price) " +
                "VALUES ((SELECT min(id) FROM categories), 't1-brand', ?, ?, 100000) RETURNING id",
            "t1-product-$u", "T1-$u",
        )
        return Db.long("INSERT INTO product_sizes(product_id, size) VALUES (?, '270') RETURNING id", product)
    }

    /** 새 판매자의 판매 입찰 → 구매자의 구매 입찰로 체결 1건. */
    private fun trade(buyer: Long, price: Long): Long {
        val seller = newUser()
        val size = newSize()
        val sell = Api.post("/bids", mapOf("productSizeId" to size, "side" to "SELL", "price" to price, "validDays" to 30), seller)
        assertEquals(201, sell.status, "판매 입찰: $sell")
        val buy = Api.post("/bids", mapOf("productSizeId" to size, "side" to "BUY", "price" to price, "validDays" to 30), buyer)
        assertEquals(201, buy.status, "구매 입찰: $buy")
        assertEquals("MATCHED", buy.json["status"].asText(), "체결: $buy")
        return buy.json["tradeId"].asLong()
    }

    private fun pay(buyer: Long, trades: List<Long>, point: Long): Resp =
        Api.post("/orders", mapOf("tradeIds" to trades, "pointToUse" to point), buyer, "t1-" + UUID.randomUUID())

    private data class Bought(val item: Long, val seller: Long)

    /** 체결 1건을 포인트 없이 결제하고 그 주문 항목을 돌려준다. */
    private fun purchase(buyer: Long, price: Long): Bought {
        val tradeId = trade(buyer, price)
        val r = pay(buyer, listOf(tradeId), 0)
        assertEquals(201, r.status, "결제: $r")
        assertEquals("PAID", r.json["status"].asText(), "결제: $r")
        val item = r.json["items"].single()["orderItemId"].asLong()
        val seller = Db.long("SELECT seller_id FROM trades WHERE id = ?", tradeId)
        return Bought(item, seller)
    }

    private fun ok(step: String, r: Resp) = assertEquals(200, r.status, "$step: $r")

    private fun deliver(b: Bought) {
        ok("발송", Api.post("/order-items/${b.item}/shipment", null, b.seller))
        ok("입고", Api.post("/admin/order-items/${b.item}/receipt", null))
        ok("검수", Api.post("/admin/order-items/${b.item}/inspection", mapOf("result" to "PASS")))
        ok("배송 완료", Api.post("/admin/order-items/${b.item}/delivery", null))
    }

    private fun confirm(b: Bought, buyer: Long) = ok("구매 확정", Api.post("/order-items/${b.item}/confirm", null, buyer))
}
