package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.HOUR
import com.ccle.behavior.contract2.instantOrNull
import com.ccle.behavior.support.Api
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db3
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.Resp
import com.ccle.behavior.support.Row3
import com.ccle.behavior.support.instantAt
import com.ccle.behavior.support.longAt
import com.ccle.behavior.support.textAt
import com.fasterxml.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 묶음 C(ADR-0007 §9 A6 문의 · §11 A8 시세·A9 관리자 조회 · §12.1 A10 프로필 · §12.3 A12 입고 예약) 전용 도우미.
 * 엔드포인트 호출과 준비 단계, 응답 모양 단언만 둔다. 기대값은 테스트 쪽에서 ADR 절을 근거로 정한다.
 */
object SC {
    /** 코드 포인트 1개 = UTF-16 2단위(보충 평면). 글자 수 규칙(§2.1 — 코드 포인트 수)을 UTF-16 길이와 구분한다. */
    const val EMOJI = "😀"

    /** 코드 포인트 n개짜리 문자열. */
    fun cps(n: Int, unit: String = EMOJI): String = unit.repeat(n)

    val SLA: Duration = Duration.ofHours(48)

    // ---- A6 문의 (§9) ----
    fun createInquiry(user: Any?, category: Any? = "OTHER", title: Any? = "bt 문의", body: Any? = "bt 본문", orderItemId: Any? = null): Resp =
        Api.post("/me/inquiries", mapOf("category" to category, "title" to title, "body" to body, "orderItemId" to orderItemId), user)

    fun newInquiry(user: Long, orderItemId: Long? = null): Long {
        val r = createInquiry(user, orderItemId = orderItemId)
        assertEquals(201, r.status, "준비(A6) 문의 작성: $r")
        return r.long("inquiryId")
    }

    fun message(id: Long, user: Any?, body: Any? = "bt 추가"): Resp = Api.post("/me/inquiries/$id/messages", mapOf("body" to body), user)
    fun answer(id: Long, body: Any? = "bt 답변"): Resp = Api.post("/admin/inquiries/$id/answer", mapOf("body" to body))
    fun close(id: Long, user: Any?): Resp = Api.post("/me/inquiries/$id/close", null, user)
    fun detail(id: Long, user: Any?): Resp = Api.get("/me/inquiries/$id", user)

    fun readyAnswer(id: Long): Resp = answer(id).also { assertEquals(200, it.status, "준비(A6) 답변: $it") }

    /**
     * 과거 시각의 문의 픽스처(§3 inquiries CHECK·verify a6_inquiry_messages 모양: 첫 메시지 USER, 답변이 있으면 ADMIN 메시지).
     * status OPEN/ANSWERED만(CLOSED 픽스처는 쓰지 않는다).
     */
    fun insertInquiry(user: Long, createdAt: Instant, answeredAt: Instant? = null): Long {
        val status = if (answeredAt == null) "OPEN" else "ANSWERED"
        val cols = linkedMapOf<String, Any?>(
            "user_id" to user, "category" to "OTHER", "title" to "bt-fixture", "status" to status,
            "created_at" to createdAt, "sla_due_at" to createdAt.plus(SLA),
        )
        if (answeredAt != null) { cols["first_answered_at"] = answeredAt; cols["answered_at"] = answeredAt }
        val id = Db3.insert("inquiries", cols)!!
        Db3.insert("inquiry_messages", mapOf("inquiry_id" to id, "author" to "USER", "body" to "bt-fixture", "created_at" to createdAt))
        if (answeredAt != null) Db3.insert("inquiry_messages", mapOf("inquiry_id" to id, "author" to "ADMIN", "body" to "bt-fixture", "created_at" to answeredAt))
        return id
    }

    fun inquiryRow(id: Long): Row3 = Db3.row("inquiries", id)
    fun messages(id: Long): List<Row3> = Db3.rows("inquiry_messages", "inquiry_id = ?", id)

    val INQUIRY_FIELDS = listOf(
        "inquiryId", "category", "title", "status", "closeReason", "orderItemId", "createdAt", "slaDueAt", "slaBreached",
        "firstAnsweredAt", "answeredAt", "closedAt",
    )

    /** §9.1 문의 모양 = 그 문의의 DB 행. slaBreached는 계산 값이라 기대값을 따로 받는다. 반환: DB 행. */
    fun assertInquiry(j: JsonNode, id: Long, slaBreached: Boolean, ctx: Any): Row3 {
        val row = inquiryRow(id)
        for (f in INQUIRY_FIELDS) assertTrue(j.has(f), "문의 모양 필드 '$f' 없음: $ctx")
        assertEquals(id, j.longAt("inquiryId"), "inquiryId: $ctx")
        assertEquals(row.text("category"), j.textAt("category"), "category: $ctx")
        assertEquals(row.text("title"), j.textAt("title"), "title: $ctx")
        assertEquals(row.text("status"), j.textAt("status"), "status = DB: $ctx")
        assertEquals(row.textOrNull("close_reason"), j.get("closeReason").takeUnless { it.isNull }?.asText(), "closeReason: $ctx")
        assertEquals(row.longOrNull("order_item_id"), j.get("orderItemId").takeUnless { it.isNull }?.asLong(), "orderItemId: $ctx")
        assertEquals(row.instant("created_at"), j.instantAt("createdAt"), "createdAt: $ctx")
        assertEquals(row.instant("sla_due_at"), j.instantAt("slaDueAt"), "slaDueAt: $ctx")
        assertEquals(row.instantOrNull("first_answered_at"), j.instantOrNull("firstAnsweredAt"), "firstAnsweredAt: $ctx")
        assertEquals(row.instantOrNull("answered_at"), j.instantOrNull("answeredAt"), "answeredAt: $ctx")
        assertEquals(row.instantOrNull("closed_at"), j.instantOrNull("closedAt"), "closedAt: $ctx")
        assertTrue(j.get("slaBreached").isBoolean, "slaBreached boolean: $ctx")
        assertEquals(slaBreached, j.get("slaBreached").asBoolean(), "slaBreached: $ctx")
        return row
    }

    // ---- A10 프로필 (§12.1) ----
    fun putProfile(user: Any?, shopName: Any? = "bt 상점", intro: Any? = "소개", from: Any? = null, to: Any? = null): Resp =
        Api.request("PUT", "/me/seller-profile", mapOf("shopName" to shopName, "intro" to intro, "vacationFrom" to from, "vacationTo" to to), user)

    fun profile(userId: Long): Resp = Api.get("/sellers/$userId/profile")

    // ---- A12 입고 예약 (§12.3) ----
    fun createSlot(slotStart: Any?, capacity: Any? = 10): Resp =
        Api.post("/admin/inbound-slots", mapOf("slotStart" to (if (slotStart is Instant) Flow.iso(slotStart) else slotStart), "capacity" to capacity))

    fun newSlot(slotStart: Instant, capacity: Int = 10): Long {
        val r = createSlot(slotStart, capacity)
        assertEquals(201, r.status, "준비(A12) 슬롯 생성: $r")
        return r.long("slotId")
    }

    /** t 이상인 첫 정시 중 inbound_slots에 아직 없는 시각(시드 슬롯·앞 테스트 슬롯과 겹치지 않게). */
    fun freeHourAtOrAfter(t: Instant): Instant {
        var h = t.truncatedTo(ChronoUnit.HOURS)
        if (h.isBefore(t)) h = h.plus(HOUR)
        while (Db3.count("inbound_slots", "slot_start = ?", h) > 0) h = h.plus(HOUR)
        return h
    }

    fun reserve(user: Any?, slotId: Any?, orderItemId: Any?): Resp =
        Api.post("/me/inbound-reservations", mapOf("slotId" to slotId, "orderItemId" to orderItemId), user)

    fun readyReserve(user: Long, slotId: Long, orderItemId: Long): Long {
        val r = reserve(user, slotId, orderItemId)
        assertEquals(201, r.status, "준비(A12) 예약: $r")
        return r.long("reservationId")
    }

    fun cancelReservation(id: Long, user: Any?): Resp = Api.delete("/me/inbound-reservations/$id", user)
    fun checkIn(id: Long): Resp = Api.post("/admin/inbound-reservations/$id/check-in", null)
    fun reservationRow(id: Long): Row3 = Db3.row("inbound_reservations", id)

    /** §12.3 예약 모양 = DB 행(+ 슬롯 시각). */
    fun assertReservation(j: JsonNode, id: Long, ctx: Any): Row3 {
        val row = reservationRow(id)
        for (f in listOf("reservationId", "slotId", "slotStart", "orderItemId", "status", "createdAt", "closedAt")) {
            assertTrue(j.has(f), "예약 모양 필드 '$f' 없음: $ctx")
        }
        assertEquals(id, j.longAt("reservationId"), "reservationId: $ctx")
        assertEquals(row.long("slot_id"), j.longAt("slotId"), "slotId: $ctx")
        assertEquals(Db3.row("inbound_slots", row.long("slot_id")).instant("slot_start"), j.instantAt("slotStart"), "slotStart: $ctx")
        assertEquals(row.long("order_item_id"), j.longAt("orderItemId"), "orderItemId: $ctx")
        assertEquals(row.text("status"), j.textAt("status"), "status = DB: $ctx")
        assertEquals(row.instant("created_at"), j.instantAt("createdAt"), "createdAt: $ctx")
        assertEquals(row.instantOrNull("closed_at"), j.instantOrNull("closedAt"), "closedAt: $ctx")
        return row
    }

    // ---- 감사 로그 (§2.7·§11.3) ----
    fun maxAuditId(): Long = Db.long("SELECT coalesce(max(id), 0) FROM admin_audit_logs")

    /** 그 action·target의 감사 행이 정확히 n개이고 마지막 행이 (detail, created_at)이다. */
    fun assertAudit(action: String, targetId: Long, n: Int, at: Instant? = null, detail: String? = null) {
        val rows = Db3.audits(action, targetId)
        assertEquals(n, rows.size, "감사 로그 $action target=$targetId 행 수: $rows")
        if (n > 0 && at != null) {
            assertEquals(at, rows.last().instant("created_at"), "감사 created_at = 현재: $rows")
            assertEquals(detail, rows.last().textOrNull("detail"), "감사 detail: $rows")
        }
    }

    // ---- 동시 요청 ----
    fun concurrently(n: Int, call: (Int) -> Resp): List<Resp> {
        val pool = Executors.newFixedThreadPool(n)
        val ready = CountDownLatch(n)
        val go = CountDownLatch(1)
        try {
            val futures = (0 until n).map { i ->
                pool.submit<Resp> {
                    ready.countDown()
                    go.await()
                    call(i)
                }
            }
            ready.await(10, TimeUnit.SECONDS)
            go.countDown()
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    // ---- 목록 ----
    /** §2.5 페이지 응답: items 배열과 nextAfter(정수 또는 null). */
    fun items(r: Resp): List<JsonNode> {
        val a = r.json.get("items")
        assertTrue(a != null && a.isArray, "items 배열 없음: $r")
        return a.toList()
    }

    fun nextAfter(r: Resp): Long? {
        assertTrue(r.json.has("nextAfter"), "nextAfter 필드 없음: $r")
        val n = r.json.get("nextAfter")
        if (n.isNull) return null
        assertTrue(n.isIntegralNumber, "nextAfter 정수: $r")
        return n.asLong()
    }

    /** 사용자 목록 응답(배열). */
    fun array(r: Resp): List<JsonNode> {
        assertTrue(r.json.isArray, "배열 응답: $r")
        return r.json.toList()
    }
}
