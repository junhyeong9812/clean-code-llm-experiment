package hidden

import support.Api
import support.Batches
import support.Clock
import support.Db
import support.Resp
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T1 숨은 테스트. 자연 흐름(결제 A → 결제 B → 결제 C → B 확정 → A 확정 → C 확정)으로 적립 묶음 세 개를 만들어
 * 생성 순서(A < B < C)와 만료 순서(B < A < C)를 엇갈리게 한다. 확정 사이에 시계를 옮겨야 만료 시각이 갈린다.
 * 단언은 결과만 본다: 묶음별 remaining, 사용 가능액, 소멸 배치 뒤 남은 포인트.
 *
 * 순서별 배분(사용 1,000P, 묶음 A·B·C = 각 적립액):
 *  - 정답(만료 이른 것부터): B 전부 → A 일부, C 그대로
 *  - 생성순·id순(주입·naive-2): A부터 / 만료 늦은 것부터(naive-1): C부터 / 생성 역순(naive-3): C부터
 */
class T1HiddenTest {

    @Test
    fun `포인트 사용은 만료가 이른 묶음부터 차감하고, 쓰고 남은 포인트는 이른 묶음의 만료로 줄지 않는다`() {
        val buyer = newUser()

        val a = purchase(buyer, 100_000)
        Clock.advance(Duration.ofMinutes(1))
        val b = purchase(buyer, 60_000)
        Clock.advance(Duration.ofMinutes(1))
        val c = purchase(buyer, 80_000)
        listOf(a, b, c).forEach(::deliver)

        confirm(b, buyer)
        Clock.advance(Duration.ofDays(1))
        confirm(a, buyer)
        Clock.advance(Duration.ofDays(1))
        confirm(c, buyer)

        val ea = earnOf(a.item)
        val eb = earnOf(b.item)
        val ec = earnOf(c.item)
        val use = 1_000L

        // 준비 확인(D12): 자연 흐름에서 생성 순서와 만료 순서가 실제로 엇갈렸는가, 배분이 순서를 드러내는 양인가
        assertTrue(ea.createdAt < eb.createdAt && eb.createdAt < ec.createdAt, "준비: 생성 순서 A < B < C — $ea $eb $ec")
        assertTrue(ea.id < eb.id && eb.id < ec.id, "준비: id 순서 A < B < C — $ea $eb $ec")
        assertTrue(eb.expiresAt < ea.expiresAt && ea.expiresAt < ec.expiresAt, "준비: 만료 순서 B < A < C — $ea $eb $ec")
        assertTrue(listOf(ea, eb, ec).all { it.status == "AVAILABLE" && it.remaining == it.amount }, "준비: 세 묶음 모두 사용 가능 — $ea $eb $ec")
        assertTrue(eb.amount < use && use < eb.amount + ea.amount && ec.amount < use, "준비: 사용액이 B보다 크고 A+B보다 작으며 C보다 크다 — $ea $eb $ec")
        val before = points(buyer)
        assertEquals(ea.amount + eb.amount + ec.amount, before.usable, "준비: 사용 가능액 = 세 묶음 합")

        // 포인트 사용
        val pay = pay(buyer, listOf(trade(buyer, 100_000)), use)
        assertEquals(201, pay.status, "포인트 사용 결제: $pay")
        assertEquals("PAID", pay.json["status"].asText(), "포인트 사용 결제: $pay")
        assertEquals(use, pay.json["pointUsed"].asLong(), "pointUsed: $pay")

        // 배분 결과: 만료가 가장 이른 B 전부 → 다음 A에서 나머지, 만료가 가장 늦은 C는 그대로
        assertEquals(0L, earnOf(b.item).remaining, "만료가 가장 이른 묶음(B)부터 다 써야 한다")
        assertEquals(ea.amount - (use - eb.amount), earnOf(a.item).remaining, "다음으로 이른 묶음(A)에서 나머지를 쓴다")
        assertEquals(ec.amount, earnOf(c.item).remaining, "만료가 가장 늦은 묶음(C)은 쓰지 않는다")
        val afterUse = points(buyer)
        assertEquals(before.usable - use, afterUse.usable, "사용 뒤 사용 가능액")
        assertEquals(before.available - use, afterUse.available, "사용 뒤 available")

        // B의 만료 시각으로 옮겨 소멸 배치 → B에 남은 것이 없으니 아무것도 소멸되지 않는다
        Clock.moveTo(eb.expiresAt).also { assertTrue(it.status in 200..299, "시계 이동: $it") }
        assertEquals(200, Batches.run("point-expiry").status, "point-expiry")
        assertEquals(0L, Db.long("SELECT count(*) FROM point_ledger WHERE user_id = ? AND kind = 'EXPIRE'", buyer), "B 만료 때 소멸 행 없음")
        val afterB = points(buyer)
        assertEquals(afterUse.available, afterB.available, "B 만료 뒤 남은 포인트(available)가 줄지 않는다")
        assertEquals(afterUse.usable, afterB.usable, "B 만료 뒤 사용 가능액이 줄지 않는다")

        // A의 만료 시각 → A에 남은 양만 소멸, C는 남는다
        Clock.moveTo(ea.expiresAt).also { assertTrue(it.status in 200..299, "시계 이동: $it") }
        assertEquals(200, Batches.run("point-expiry").status, "point-expiry")
        val afterA = points(buyer)
        assertEquals(ec.amount, afterA.available, "A 만료 뒤 남는 것은 C 전부")
        assertEquals(ec.amount, afterA.usable, "A 만료 뒤 사용 가능액 = C 전부")
    }

    // ---- 도우미(HTTP·DB) ----

    private data class Points(val available: Long, val pending: Long, val usable: Long)

    private data class Earn(val id: Long, val status: String, val amount: Long, val remaining: Long, val createdAt: Instant, val expiresAt: Instant)

    private fun inst(v: Any?): Instant = when (v) {
        is java.sql.Timestamp -> v.toInstant()
        is OffsetDateTime -> v.toInstant()
        else -> error("시각 아님: $v")
    }

    private fun earnOf(item: Long): Earn {
        val r = Db.query(
            "SELECT id, status, amount, remaining, created_at, expires_at FROM point_ledger WHERE order_item_id = ? AND kind = 'EARN'",
            item,
        ).single()
        return Earn(
            (r["id"] as Number).toLong(), r["status"] as String, (r["amount"] as Number).toLong(),
            (r["remaining"] as Number).toLong(), inst(r["created_at"]), inst(r["expires_at"]),
        )
    }

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
            "t1h-$u@t1.test", "t1h-$u",
        )
    }

    private fun newSize(): Long {
        val u = uniq()
        val product = Db.long(
            "INSERT INTO products(category_id, brand, name, model_no, release_price) " +
                "VALUES ((SELECT min(id) FROM categories), 't1h-brand', ?, ?, 100000) RETURNING id",
            "t1h-product-$u", "T1H-$u",
        )
        return Db.long("INSERT INTO product_sizes(product_id, size) VALUES (?, '270') RETURNING id", product)
    }

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
        Api.post("/orders", mapOf("tradeIds" to trades, "pointToUse" to point), buyer, "t1h-" + UUID.randomUUID())

    private data class Bought(val item: Long, val seller: Long)

    private fun purchase(buyer: Long, price: Long): Bought {
        val tradeId = trade(buyer, price)
        val r = pay(buyer, listOf(tradeId), 0)
        assertEquals(201, r.status, "결제: $r")
        assertEquals("PAID", r.json["status"].asText(), "결제: $r")
        val item = r.json["items"].single()["orderItemId"].asLong()
        return Bought(item, Db.long("SELECT seller_id FROM trades WHERE id = ?", tradeId))
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
