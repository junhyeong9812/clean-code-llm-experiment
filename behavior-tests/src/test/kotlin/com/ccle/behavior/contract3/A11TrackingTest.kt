package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.HOUR
import com.ccle.behavior.contract2.S2
import com.ccle.behavior.contract2.SEC
import com.ccle.behavior.support.Contract3Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db2
import com.ccle.behavior.support.Db3
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.Resp
import com.ccle.behavior.support.longAt
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-0007 §12.2 A11 배송 추적 이벤트 기록 `POST /admin/order-items/{id}/tracking-events`
 * (enum·trackingNo 1~30자(§2.1 앞뒤 공백 뺀 코드 포인트)·occurredAt ≤ 현재 → 아니면 400, 없는 항목 404, 항목 상태 4종 밖이면 409,
 * 1행 received_at = 현재, 항목 상태 무변경) + §2.7 감사 로그 TRACKING_EVENT(target_id = 새 이벤트 id, detail = event_type — §11.3),
 * 조회 `GET /order-items/{id}/tracking`(구매자 또는 판매자, events는 occurredAt·id 오름차순).
 */
@Contract3Test
class A11TrackingTest {

    /** 발송된(SHIPPED_TO_CENTER) 항목 1개. */
    private fun shipped(): com.ccle.behavior.contract2.Purchase {
        val p = S2.purchase(100_000)
        S2.toShipped(p.item, p.seller)
        return p
    }

    /** 201 응답 = 새 이벤트 행(received_at = 현재) + 감사 로그 1행. 반환: 이벤트 id. */
    private fun assertRecorded(r: Resp, item: Long, body: Map<String, Any?>, occurredAt: Instant, at: Instant): Long {
        r.assertStatus(201)
        val id = r.long("eventId")
        val row = Db3.row("shipment_tracking_events", id)
        assertEquals(item, row.long("order_item_id"))
        assertEquals(body["carrier"], row.text("carrier"))
        assertEquals(body["trackingNo"], row.text("tracking_no"), "받은 값 그대로 저장")
        assertEquals(body["eventType"], row.text("event_type"))
        assertEquals(occurredAt, row.instant("occurred_at"), "occurred_at = 보낸 시각")
        assertEquals(at, row.instant("received_at"), "received_at = 현재")
        SB.assertEventBody(r.json, row)
        val audit = Db3.audits("TRACKING_EVENT", id).single()
        assertEquals(body["eventType"], audit.textOrNull("detail"), "detail = event_type")
        assertEquals(at, audit.instant("created_at"))
        return id
    }

    @Test
    fun `§12_2 기록 - SHIPPED_TO_CENTER 항목 201 이벤트 모양, received_at = 현재, 감사 로그 TRACKING_EVENT, 항목 상태·시각 무변경`() {
        val p = shipped()
        val before = Db2.item(p.item)
        val now = Flow.advance(HOUR)
        val occurred = now.minus(HOUR).plusSeconds(17)
        val body = SB.event("CJ", "123456789012", "PICKED_UP", SB.iso(occurred))
        assertRecorded(SB.postEvent(p.item, body), p.item, body, occurred, now)
        assertEquals(before, Db2.item(p.item), "항목은 바꾸지 않는다")
    }

    @Test
    fun `§12_2 기록 - INSPECTING·DELIVERING·DELIVERED 항목도 201, carrier·eventType 네 값 모두 받는다`() {
        val inspecting = S2.purchase(100_000).also { S2.toInspecting(it.item, it.seller) }
        val delivering = S2.purchase(100_000).also { S2.toDelivering(it.item, it.seller) }
        val delivered = S2.purchase(100_000).also { S2.toDelivered(it.item, it.seller) }
        val now = Flow.advance(HOUR)
        val cases = listOf(
            Triple(inspecting.item, "HANJIN", "IN_TRANSIT"),
            Triple(delivering.item, "LOTTE", "OUT_FOR_DELIVERY"),
            Triple(delivered.item, "POST", "DELIVERED"),
        )
        for ((item, carrier, type) in cases) {
            val status = Db2.item(item).status
            val body = SB.event(carrier, "TN-$item", type, SB.iso(now.minus(HOUR)))
            assertRecorded(SB.postEvent(item, body), item, body, now.minus(HOUR), now)
            assertEquals(status, Db2.item(item).status, "항목 상태 무변경")
        }
        assertEquals(listOf("INSPECTING", "DELIVERING", "DELIVERED"), cases.map { Db2.item(it.first).status })
    }

    @Test
    fun `§12_2 기록 - occurredAt = 현재(정각) 201, trackingNo 30자·앞뒤 공백 포함 30자(받은 그대로 저장)·코드 포인트 30개(보충 문자) 201`() {
        val p = shipped()
        val now = Flow.advance(HOUR)
        val b1 = SB.event(trackingNo = "1".repeat(30), occurredAt = SB.iso(now))
        assertRecorded(SB.postEvent(p.item, b1), p.item, b1, now, now)
        val b2 = SB.event(trackingNo = "  " + "2".repeat(30) + "  ", occurredAt = SB.iso(now))
        assertRecorded(SB.postEvent(p.item, b2), p.item, b2, now, now)
        val b3 = SB.event(trackingNo = "📦".repeat(30), occurredAt = SB.iso(now))   // U+1F4E6 × 30 = 코드 포인트 30
        assertRecorded(SB.postEvent(p.item, b3), p.item, b3, now, now)
        val b4 = SB.event(trackingNo = "1", occurredAt = SB.iso(now))
        assertRecorded(SB.postEvent(p.item, b4), p.item, b4, now, now)
    }

    @Test
    fun `§12_2 기록 오류 400 - 알 수 없는 carrier·eventType, trackingNo 빈 값·공백만·31자, 필드 누락, occurredAt 현재 + 1초·형식 오류·오프셋 없음, 행·감사 없음`() {
        val p = shipped()
        val now = Flow.advance(HOUR)
        val ok = SB.iso(now.minus(HOUR))
        val audits = SB.trackingAudits()
        val bad = listOf(
            SB.event(carrier = "DHL", occurredAt = ok),
            SB.event(carrier = "cj", occurredAt = ok),
            SB.event(eventType = "RETURNED", occurredAt = ok),
            SB.event(trackingNo = "", occurredAt = ok),
            SB.event(trackingNo = "   ", occurredAt = ok),
            SB.event(trackingNo = "1".repeat(31), occurredAt = ok),
            SB.event(carrier = null, occurredAt = ok),
            SB.event(trackingNo = null, occurredAt = ok),
            SB.event(eventType = null, occurredAt = ok),
            SB.event(occurredAt = null),
            SB.event(occurredAt = SB.iso(now.plus(SEC))),
            SB.event(occurredAt = "not-a-time"),
            SB.event(occurredAt = now.minus(HOUR).atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDateTime().toString()),  // 오프셋 없음(§19의 5)
        )
        for (b in bad) SB.postEvent(p.item, b).assertError(400, "INVALID_REQUEST")
        assertTrue(SB.events(p.item).isEmpty(), "이벤트 행 없음")
        assertEquals(audits, SB.trackingAudits(), "실패한 요청은 감사 로그를 쓰지 않는다")
    }

    @Test
    fun `§12_2 기록 오류 - 없는 항목 404, 400이 404보다 먼저, PAID·CONFIRMED·CANCELLED_BY_BUYER 항목 409(404가 409보다 먼저), 행·감사 없음`() {
        val now = Flow.now()
        val ok = SB.event(occurredAt = SB.iso(now))
        val audits = SB.trackingAudits()
        SB.postEvent(Db.missingId("order_items"), ok).assertError(404, "NOT_FOUND")
        SB.postEvent(Db.missingId("order_items"), SB.event(carrier = "DHL", occurredAt = SB.iso(now))).assertError(400, "INVALID_REQUEST")

        val paid = S2.purchase(100_000)
        val confirmed = S2.purchase(100_000).also { S2.toConfirmed(it.item, it.seller, it.buyer) }
        val cancelled = S2.purchase(100_000).also { S2.ready("구매자 취소", S2.cancel(it.item, it.buyer)) }
        val body = SB.event(occurredAt = SB.iso(Flow.now()))
        for (item in listOf(paid.item, confirmed.item, cancelled.item)) {
            val status = Db2.item(item).status
            SB.postEvent(item, body).assertError(409, "ITEM_STATE_CONFLICT")
            assertTrue(SB.events(item).isEmpty())
            assertEquals(status, Db2.item(item).status)
        }
        assertEquals(audits, SB.trackingAudits(), "실패한 요청은 감사 로그를 쓰지 않는다")
    }

    @Test
    fun `§12_2 조회 - 구매자·판매자 200 같은 본문, events는 occurredAt 오름차순(같으면 id), delays 빈 배열`() {
        val p = shipped()
        val now = Flow.advance(HOUR)
        val t1 = now.minus(HOUR)
        val t3 = now.minus(HOUR).minus(HOUR).minus(HOUR)
        val e1 = SB.postEvent(p.item, SB.event(eventType = "IN_TRANSIT", occurredAt = SB.iso(t1))).assertStatus(201).long("eventId")
        val e2 = SB.postEvent(p.item, SB.event(eventType = "PICKED_UP", occurredAt = SB.iso(t3))).assertStatus(201).long("eventId")
        val e3 = SB.postEvent(p.item, SB.event(eventType = "IN_TRANSIT", occurredAt = SB.iso(t1))).assertStatus(201).long("eventId")
        assertTrue(e1 < e3)

        val rb = SB.tracking(p.item, p.buyer).assertStatus(200)
        val rs = SB.tracking(p.item, p.seller).assertStatus(200)
        assertEquals(rb.json, rs.json, "구매자·판매자 같은 본문")
        val events = rb.json.get("events")
        assertEquals(listOf(e2, e1, e3), events.map { it.longAt("eventId") }, "occurredAt·id 오름차순: $rb")
        events.forEach { SB.assertEventBody(it, Db3.row("shipment_tracking_events", it.longAt("eventId"))) }
        val delays = rb.json.get("delays")
        assertTrue(delays != null && delays.isArray && delays.size() == 0, "지연 없음 → 빈 배열: $rb")
    }

    @Test
    fun `§12_2 조회 - 이벤트 없는 항목은 빈 events, 남(구매자·판매자 아님) 403, 없는 항목 404, 헤더 없음 401`() {
        val p = S2.purchase(100_000)
        val r = SB.tracking(p.item, p.buyer).assertStatus(200)
        assertTrue(r.json.get("events").isArray && r.json.get("events").size() == 0, "빈 events: $r")
        SB.tracking(p.item, Db.createUser()).assertError(403, "NOT_OWNER")
        SB.tracking(Db.missingId("order_items"), p.buyer).assertError(404, "NOT_FOUND")
        SB.tracking(p.item, null).assertError(401, "UNKNOWN_USER")
        assertNull(Db2.item(p.item).shippedAt)
    }
}
