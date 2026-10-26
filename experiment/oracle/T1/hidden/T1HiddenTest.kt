package hidden

import support.Api
import support.Batches
import support.Clock
import support.Db
import support.Resp
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T1 숨은 테스트 — 확정 시각 상한. 단언은 결과만 본다(HTTP 응답 상태·본문, DB 값).
 *  - A(구매자 늦은 확정): 배송 완료 D → D+10일에 구매자 확정 → 확정 시각 = D+7일(응답·DB).
 *    분쟁 기한 = D+7일+14일: 기한 1시간 전 접수는 201(항목 1), 기한 1시간 뒤 접수는 409 DEADLINE_PASSED(항목 2).
 *    시계는 뒤로 못 가므로 항목 두 개를 미리 만들어 기한 전 → 기한 뒤 순으로 접수한다.
 *  - B(자동 확정): 화요일 정오(KST)에 배송 완료 D′, 아무도 확정하지 않음 → D′+7일이 속한 주의 마감(다음 월요일 00:00 KST)
 *    1시간 뒤에 weekly-settlement → 그 항목이 D′+7일이 속한 주의 정산에 INCLUDED, 확정 시각 = D′+7일(DB).
 *  - A·B 모두 그 항목의 적립(EARN) expires_at = 기대 확정 시각 + 365일(확정 시각이 포인트 확정에도 같은 값으로 쓰이는가).
 * 두 테스트는 각자 현재 시계에서 출발한다(실행 순서 무관).
 */
class T1HiddenTest {

    private val kst = ZoneId.of("Asia/Seoul")

    @Test
    fun `A - 배송 완료 7일 뒤 구매자 확정은 배송 완료 + 7일로 기록되고 분쟁 기한은 그 시각 + 14일이다`() {
        val buyer = newUser()
        val early = purchase(buyer, 50_000)
        val late = purchase(buyer, 50_000)
        deliver(early)
        deliver(late)
        val d1 = deliveredAt(early.item)
        val d2 = deliveredAt(late.item)
        assertEquals(d1, d2, "준비: 두 항목이 같은 시각에 배송 완료")
        val due = d1.plus(Duration.ofDays(7))

        moveTo(d1.plus(Duration.ofDays(10)))
        for (b in listOf(early, late)) {
            val r = Api.post("/order-items/${b.item}/confirm", null, buyer)
            assertEquals(200, r.status, "구매 확정: $r")
            assertEquals("CONFIRMED", r.json["status"].asText(), "구매 확정 상태: $r")
            assertEquals(due, OffsetDateTime.parse(r.json["confirmedAt"].asText()).toInstant(),
                "배송 완료 7일 뒤의 구매 확정 — 응답 confirmedAt = 배송 완료 + 7일(항목 ${b.item})")
            assertEquals(due, confirmedAt(b.item), "배송 완료 7일 뒤의 구매 확정 — DB confirmed_at = 배송 완료 + 7일")
            assertEquals(due.plus(Duration.ofDays(365)), earnExpiresAt(b.item), "적립 유효기간 = 확정 시각(배송 완료 + 7일) + 365일")
        }

        val deadline = due.plus(Duration.ofDays(14))
        moveTo(deadline.minus(Duration.ofHours(1)))
        val ok = Api.post("/order-items/${early.item}/disputes", null, buyer)
        assertEquals(201, ok.status, "분쟁 기한 1시간 전 접수는 받는다: $ok")

        moveTo(deadline.plus(Duration.ofHours(1)))
        val past = Api.post("/order-items/${late.item}/disputes", null, buyer)
        assertEquals(409, past.status, "분쟁 기한(확정 + 14일) 1시간 뒤 접수는 409: $past")
        assertEquals("DEADLINE_PASSED", past.json["code"].asText(), "분쟁 기한 지남 코드: $past")
        assertEquals("CONFIRMED", Db.query("SELECT status FROM order_items WHERE id = ?", late.item).single()["status"],
            "기한 뒤 거절된 항목은 CONFIRMED 그대로")
    }

    @Test
    fun `B - 아무도 확정하지 않은 항목은 배송 완료 + 7일로 자동 확정되어 그 시각이 속한 주에 정산된다`() {
        val buyer = newUser()
        val b = purchase(buyer, 70_000)
        toDelivering(b)

        // 배송 완료 = 다음 화요일 정오(KST) — 주 중간
        val tuesday = LocalDate.ofInstant(Clock.now(), kst).with(TemporalAdjusters.next(DayOfWeek.TUESDAY))
        moveTo(tuesday.atTime(12, 0).atZone(kst).toInstant())
        ok("배송 완료", Api.post("/admin/order-items/${b.item}/delivery", null))
        val delivered = deliveredAt(b.item)
        val due = delivered.plus(Duration.ofDays(7))
        val weekStart = LocalDate.ofInstant(due, kst).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val cutoff = weekStart.plusDays(7).atStartOfDay(kst).toInstant()
        assertTrue(due.isBefore(cutoff) && due.isAfter(weekStart.atStartOfDay(kst).toInstant()), "준비: 배송 완료 + 7일이 주 중간")

        // 그 주 마감 1시간 뒤에 주간 정산(먼저 자동 확정을 수행한다)
        moveTo(cutoff.plus(Duration.ofHours(1)))
        val batch = Batches.run("weekly-settlement")
        assertEquals(200, batch.status, "weekly-settlement: $batch")

        val r = Api.get("/me/settlements", b.seller)
        assertEquals(200, r.status, "정산 조회: $r")
        val weeks = r.json.filter { s -> s["items"].any { it["orderItemId"].asLong() == b.item } }
        assertEquals(listOf(weekStart.toString()), weeks.map { it["periodStart"].asText() },
            "자동 확정 항목은 배송 완료 + 7일이 속한 주(period_start $weekStart)에 정산된다: $r")
        val line = weeks.single()["items"].single { it["orderItemId"].asLong() == b.item }
        assertEquals("INCLUDED", line["status"].asText(), "정산 항목 상태: $r")

        val row = Db.query("SELECT status FROM order_items WHERE id = ?", b.item).single()
        assertEquals("CONFIRMED", row["status"], "자동 확정 상태")
        assertEquals(due, confirmedAt(b.item), "자동 확정 — DB confirmed_at = 배송 완료 + 7일")
        assertEquals(due.plus(Duration.ofDays(365)), earnExpiresAt(b.item), "자동 확정 적립 유효기간 = 확정 시각(배송 완료 + 7일) + 365일")
    }

    // ---- 도우미(HTTP·DB) ----

    private fun moveTo(t: Instant) = Clock.moveTo(t).also { assertTrue(it.status in 200..299, "시계 이동: $it") }

    private fun inst(v: Any?): Instant = when (v) {
        is java.sql.Timestamp -> v.toInstant()
        is OffsetDateTime -> v.toInstant()
        else -> error("시각 아님: $v")
    }

    private fun deliveredAt(item: Long): Instant =
        inst(Db.query("SELECT delivered_at FROM order_items WHERE id = ?", item).single()["delivered_at"])

    private fun confirmedAt(item: Long): Instant =
        inst(Db.query("SELECT confirmed_at FROM order_items WHERE id = ?", item).single()["confirmed_at"])

    /** 그 항목의 적립(EARN) 행 expires_at — 항목마다 정확히 1행. */
    private fun earnExpiresAt(item: Long): Instant =
        inst(Db.query("SELECT expires_at FROM point_ledger WHERE order_item_id = ? AND kind = 'EARN'", item).single()["expires_at"])

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

    private data class Bought(val item: Long, val seller: Long)

    private fun purchase(buyer: Long, price: Long): Bought {
        val tradeId = trade(buyer, price)
        val r: Resp = Api.post("/orders", mapOf("tradeIds" to listOf(tradeId), "pointToUse" to 0), buyer, "t1h-" + UUID.randomUUID())
        assertEquals(201, r.status, "결제: $r")
        assertEquals("PAID", r.json["status"].asText(), "결제: $r")
        val item = r.json["items"].single()["orderItemId"].asLong()
        return Bought(item, Db.long("SELECT seller_id FROM trades WHERE id = ?", tradeId))
    }

    private fun ok(step: String, r: Resp) = assertEquals(200, r.status, "$step: $r")

    private fun toDelivering(b: Bought) {
        ok("발송", Api.post("/order-items/${b.item}/shipment", null, b.seller))
        ok("입고", Api.post("/admin/order-items/${b.item}/receipt", null))
        ok("검수", Api.post("/admin/order-items/${b.item}/inspection", mapOf("result" to "PASS")))
    }

    private fun deliver(b: Bought) {
        toDelivering(b)
        ok("배송 완료", Api.post("/admin/order-items/${b.item}/delivery", null))
    }
}
