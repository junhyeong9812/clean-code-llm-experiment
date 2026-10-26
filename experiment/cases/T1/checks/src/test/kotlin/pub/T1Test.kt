package pub

import support.Api
import support.Batches
import support.Clock
import support.Db
import support.Resp
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 증상: 배송 완료 뒤 아무도 구매 확정하지 않은 항목이, 배송 완료 7일째가 속한 주가 아니라 그 뒤 주의 판매자 정산에 들어간다.
 */
class T1Test {

    private val kst = ZoneId.of("Asia/Seoul")

    @Test
    fun `아무도 확정하지 않은 항목은 배송 완료 7일째가 속한 주의 정산에 들어간다`() {
        val buyer = newUser()
        val bought = purchase(buyer, 80_000)
        ok("발송", Api.post("/order-items/${bought.item}/shipment", null, bought.seller))
        ok("입고", Api.post("/admin/order-items/${bought.item}/receipt", null))
        ok("검수", Api.post("/admin/order-items/${bought.item}/inspection", mapOf("result" to "PASS")))

        // 다음 목요일 15:00(KST)에 배송 완료
        val thursday = LocalDate.ofInstant(Clock.now(), kst).with(TemporalAdjusters.next(DayOfWeek.THURSDAY))
        moveTo(thursday.atTime(15, 0).atZone(kst).toInstant())
        ok("배송 완료", Api.post("/admin/order-items/${bought.item}/delivery", null))

        // 배송 완료 7일째(다음 주 목요일)가 속한 주 = 그 주 월요일부터, 마감 = 다음 월요일 00:00(KST)
        val seventhDay = thursday.plusDays(7)
        val weekStart = seventhDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val cutoff = weekStart.plusDays(7).atStartOfDay(kst).toInstant()

        // 아무도 확정하지 않은 채 그 주가 마감된 뒤(화요일 10:00)에 주간 정산을 돌린다
        moveTo(cutoff.plus(Duration.ofHours(34)))
        val batch = Batches.run("weekly-settlement")
        assertEquals(200, batch.status, "weekly-settlement: $batch")

        val r = Api.get("/me/settlements", bought.seller)
        assertEquals(200, r.status, "정산 조회: $r")
        val weeks = r.json.filter { s -> s["items"].any { it["orderItemId"].asLong() == bought.item } }.map { it["periodStart"].asText() }
        assertEquals(listOf(weekStart.toString()), weeks, "그 항목이 들어간 정산 주(period_start): $r")
    }

    // ---- 준비 도우미(HTTP·DB) ----

    private fun moveTo(t: Instant) = Clock.moveTo(t).also { assertTrue(it.status in 200..299, "시계 이동: $it") }

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

    private data class Bought(val item: Long, val seller: Long)

    /** 체결 1건을 포인트 없이 결제하고 그 주문 항목을 돌려준다. */
    private fun purchase(buyer: Long, price: Long): Bought {
        val tradeId = trade(buyer, price)
        val r: Resp = Api.post("/orders", mapOf("tradeIds" to listOf(tradeId), "pointToUse" to 0), buyer, "t1-" + UUID.randomUUID())
        assertEquals(201, r.status, "결제: $r")
        assertEquals("PAID", r.json["status"].asText(), "결제: $r")
        val item = r.json["items"].single()["orderItemId"].asLong()
        return Bought(item, Db.long("SELECT seller_id FROM trades WHERE id = ?", tradeId))
    }

    private fun ok(step: String, r: Resp) = assertEquals(200, r.status, "$step: $r")
}
