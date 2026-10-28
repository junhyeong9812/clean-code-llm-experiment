package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.DAY
import com.ccle.behavior.contract2.HOUR
import com.ccle.behavior.contract2.S2
import com.ccle.behavior.contract2.SEC
import com.ccle.behavior.contract2.days
import com.ccle.behavior.support.Api
import com.ccle.behavior.support.Contract3Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db3
import com.ccle.behavior.support.FailTrigger
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.SlowTrigger
import com.ccle.behavior.support.failed
import com.ccle.behavior.support.instantAt
import com.ccle.behavior.support.longAt
import com.ccle.behavior.support.textAt
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-0007 §9 A6 고객 문의: 작성·추가 메시지·답변(감사 로그 INQUIRY_ANSWER, §2.7)·종결, 조회, slaBreached 계산(§9.1),
 * 판정 순서 401 → 400(본문) → 404 → 403 → 409, 글자 수 규칙(§2.1 — 앞뒤 공백을 뺀 코드 포인트 수), 배치 inquiry-auto-close(§9.3).
 */
@Contract3Test
class A6InquiryTest {

    // ---- §9.1 작성 ----

    @Test
    fun `§9_1 작성 - 201 문의 모양(OPEN, slaDueAt = 현재 + 48시간), 문의 1행 + USER 메시지 1행, 제목은 받은 그대로 저장`() {
        val user = Db.createUser()
        val now = Flow.now()
        val title = "  배송 문의  "
        val r = SC.createInquiry(user, category = "ORDER", title = title, body = "  언제 오나요  ").assertStatus(201)
        val id = r.long("inquiryId")
        val row = SC.assertInquiry(r.json, id, slaBreached = false, ctx = r)
        assertEquals(user, row.long("user_id"))
        assertEquals("ORDER", row.text("category"))
        assertEquals(title, row.text("title"), "§2.1 저장은 받은 값 그대로")
        assertEquals("OPEN", row.text("status"))
        assertNull(row.textOrNull("close_reason"))
        assertNull(row.longOrNull("order_item_id"))
        assertEquals(now, row.instant("created_at"))
        assertEquals(now.plus(SC.SLA), row.instant("sla_due_at"))
        assertNull(row.instantOrNull("first_answered_at"))
        assertNull(row.instantOrNull("answered_at"))
        assertNull(row.instantOrNull("closed_at"))
        val m = SC.messages(id).single()
        assertEquals("USER", m.text("author"))
        assertEquals("  언제 오나요  ", m.text("body"))
        assertEquals(now, m.instant("created_at"))
    }

    @Test
    fun `§9_1 작성 - orderItemId는 그 항목의 구매자·판매자만(201), 제3자 403 NOT_OWNER, 없는 항목 404 NOT_FOUND`() {
        val p = S2.purchase(50_000)
        val byBuyer = SC.createInquiry(p.buyer, category = "ORDER", orderItemId = p.item).assertStatus(201)
        assertEquals(p.item, byBuyer.long("orderItemId"))
        assertEquals(p.item, SC.inquiryRow(byBuyer.long("inquiryId")).long("order_item_id"))
        val bySeller = SC.createInquiry(p.seller, category = "SETTLEMENT", orderItemId = p.item).assertStatus(201)
        assertEquals(p.item, bySeller.long("orderItemId"))

        val stranger = Db.createUser()
        SC.createInquiry(stranger, orderItemId = p.item).assertError(403, "NOT_OWNER")
        SC.createInquiry(stranger, orderItemId = Db.missingId("order_items")).assertError(404, "NOT_FOUND")
        assertEquals(0L, Db3.count("inquiries", "user_id = ?", stranger), "실패한 작성은 행을 남기지 않는다")
    }

    @Test
    fun `§9_1 작성 - 400 INVALID_REQUEST - 알 수 없는·없는 category, 빈 제목·본문, 제목 101자·본문 2,001자, orderItemId 정수 아님`() {
        val user = Db.createUser()
        SC.createInquiry(user, category = "REFUND").assertError(400, "INVALID_REQUEST")
        SC.createInquiry(user, category = null).assertError(400, "INVALID_REQUEST")
        SC.createInquiry(user, title = null).assertError(400, "INVALID_REQUEST")
        SC.createInquiry(user, title = "   ").assertError(400, "INVALID_REQUEST")
        SC.createInquiry(user, title = "").assertError(400, "INVALID_REQUEST")
        SC.createInquiry(user, body = null).assertError(400, "INVALID_REQUEST")
        SC.createInquiry(user, body = "  \t ").assertError(400, "INVALID_REQUEST")
        SC.createInquiry(user, title = "a".repeat(101)).assertError(400, "INVALID_REQUEST")
        SC.createInquiry(user, body = "a".repeat(2_001)).assertError(400, "INVALID_REQUEST")
        SC.createInquiry(user, orderItemId = "abc").assertError(400, "INVALID_REQUEST")
        assertEquals(0L, Db3.count("inquiries", "user_id = ?", user))
    }

    @Test
    fun `§2_1 글자 수 = 앞뒤 공백을 뺀 코드 포인트 수 - 제목 100·본문 2,000 경계(보충 평면 문자·앞뒤 공백·NBSP·전각 공백·중간 공백)`() {
        val user = Db.createUser()
        // 코드 포인트 100개 = UTF-16 200단위: 코드 포인트로 세면 통과, UTF-16으로 세면 거부된다
        SC.createInquiry(user, title = SC.cps(100)).assertStatus(201)
        SC.createInquiry(user, title = SC.cps(101)).assertError(400, "INVALID_REQUEST")
        // 앞뒤 공백은 세지 않는다
        SC.createInquiry(user, title = "   " + "a".repeat(100) + "   ").assertStatus(201)
        // 중간 공백은 센다: a + 공백 98 + b = 100자, a + 공백 99 + b = 101자
        SC.createInquiry(user, title = "a" + " ".repeat(98) + "b").assertStatus(201)
        SC.createInquiry(user, title = "a" + " ".repeat(99) + "b").assertError(400, "INVALID_REQUEST")
        // 본문
        SC.createInquiry(user, body = SC.cps(2_000)).assertStatus(201)
        SC.createInquiry(user, body = SC.cps(2_001)).assertError(400, "INVALID_REQUEST")
        SC.createInquiry(user, body = "  " + "가".repeat(2_000) + "  ").assertStatus(201)
        // 1자 하한
        SC.createInquiry(user, title = " x ", body = SC.cps(1)).assertStatus(201)
        // §19의 6: 앞뒤 NBSP(U+00A0)·전각 공백(U+3000)도 깎고 센다(isWhitespace 집합)
        val nbsp = "\u00A0"
        val ideo = "\u3000"
        SC.createInquiry(user, title = nbsp + ideo + "a".repeat(100) + ideo + nbsp).assertStatus(201)
        SC.createInquiry(user, title = nbsp + ideo + "a".repeat(101) + ideo + nbsp).assertError(400, "INVALID_REQUEST")
        SC.createInquiry(user, title = nbsp + ideo + nbsp).assertError(400, "INVALID_REQUEST")
        SC.createInquiry(user, body = ideo.repeat(3)).assertError(400, "INVALID_REQUEST")
        assertEquals(7L, Db3.count("inquiries", "user_id = ?", user))
    }

    @Test
    fun `§9_1 작성 판정 순서 - 401(헤더 없음·숫자 아님·없는 사용자) → 400 → 404 → 403`() {
        val p = S2.purchase(50_000)
        val stranger = Db.createUser()
        SC.createInquiry(null, category = "BOGUS").assertError(401, "UNKNOWN_USER")
        SC.createInquiry("abc").assertError(401, "UNKNOWN_USER")
        SC.createInquiry(Db.missingId("users")).assertError(401, "UNKNOWN_USER")
        SC.createInquiry(stranger, title = "  ", orderItemId = Db.missingId("order_items")).assertError(400, "INVALID_REQUEST")
        SC.createInquiry(stranger, title = SC.cps(101), orderItemId = p.item).assertError(400, "INVALID_REQUEST")
        SC.createInquiry(stranger, orderItemId = Db.missingId("order_items")).assertError(404, "NOT_FOUND")
    }

    // ---- §9.1 추가 메시지·답변·종결 ----

    @Test
    fun `§9_1 답변 - OPEN → ANSWERED, ADMIN 메시지, answered_at·first_answered_at = 현재, 감사 로그 INQUIRY_ANSWER(detail null) - 재답변은 answered_at만 갱신`() {
        val user = Db.createUser()
        val id = SC.newInquiry(user)
        val t1 = Flow.advance(HOUR)
        val r1 = SC.answer(id, "  확인 중입니다  ").assertStatus(200)
        val row1 = SC.assertInquiry(r1.json, id, slaBreached = false, ctx = r1)
        assertEquals("ANSWERED", row1.text("status"))
        assertEquals(t1, row1.instant("answered_at"))
        assertEquals(t1, row1.instant("first_answered_at"))
        val m = SC.messages(id)
        assertEquals(listOf("USER", "ADMIN"), m.map { it.text("author") })
        assertEquals("  확인 중입니다  ", m[1].text("body"))
        assertEquals(t1, m[1].instant("created_at"))
        SC.assertAudit("INQUIRY_ANSWER", id, 1, t1, null)

        val t2 = Flow.advance(HOUR)
        val r2 = SC.answer(id).assertStatus(200)
        val row2 = SC.assertInquiry(r2.json, id, slaBreached = false, ctx = r2)
        assertEquals("ANSWERED", row2.text("status"))
        assertEquals(t2, row2.instant("answered_at"))
        assertEquals(t1, row2.instant("first_answered_at"), "first_answered_at은 첫 답변 시각 그대로")
        assertEquals(3, SC.messages(id).size)
        SC.assertAudit("INQUIRY_ANSWER", id, 2, t2, null)
    }

    @Test
    fun `§9_1 답변 오류 - 빈·2,001자 본문 400, 없는 문의 404(본문 오류가 먼저), CLOSED 409 - 실패는 감사 로그·메시지를 쓰지 않는다`() {
        val user = Db.createUser()
        val id = SC.newInquiry(user)
        SC.answer(id, " ").assertError(400, "INVALID_REQUEST")
        SC.answer(id, null).assertError(400, "INVALID_REQUEST")
        SC.answer(id, "a".repeat(2_001)).assertError(400, "INVALID_REQUEST")
        SC.answer(Db.missingId("inquiries"), "").assertError(400, "INVALID_REQUEST")
        SC.answer(Db.missingId("inquiries")).assertError(404, "NOT_FOUND")
        SC.close(id, user).assertStatus(200)
        SC.answer(id).assertError(409, "ITEM_STATE_CONFLICT")
        assertEquals("CLOSED", SC.inquiryRow(id).text("status"))
        assertEquals(1, SC.messages(id).size)
        SC.assertAudit("INQUIRY_ANSWER", id, 0)
    }

    @Test
    fun `§9_1 추가 메시지 - OPEN 그대로·ANSWERED는 OPEN으로, USER 메시지 1행, 200 문의 모양`() {
        val user = Db.createUser()
        val id = SC.newInquiry(user)
        val t1 = Flow.advance(HOUR)
        val r1 = SC.message(id, user, "추가 1").assertStatus(200)
        assertEquals("OPEN", SC.assertInquiry(r1.json, id, false, r1).text("status"))
        val answeredAt = Flow.advance(HOUR)
        SC.readyAnswer(id)
        val t2 = Flow.advance(HOUR)
        val r2 = SC.message(id, user, "추가 2").assertStatus(200)
        val row2 = SC.assertInquiry(r2.json, id, false, r2)
        assertEquals("OPEN", row2.text("status"))
        // §19의 11: ANSWERED → OPEN이어도 answered_at·first_answered_at은 그대로
        assertEquals(answeredAt, row2.instant("answered_at"))
        assertEquals(answeredAt, row2.instant("first_answered_at"))
        val m = SC.messages(id)
        assertEquals(listOf("USER", "USER", "ADMIN", "USER"), m.map { it.text("author") })
        assertEquals(listOf(t1, t2), listOf(m[1].instant("created_at"), m[3].instant("created_at")))
        assertEquals("추가 2", m[3].text("body"))
    }

    @Test
    fun `§9_1 추가 메시지 판정 순서 - 401 → 400(빈 본문·2,001자, 없는 문의여도) → 404 → 403(작성자 아님, CLOSED여도) → 409(CLOSED)`() {
        val user = Db.createUser()
        val other = Db.createUser()
        val id = SC.newInquiry(user)
        SC.message(id, null, "").assertError(401, "UNKNOWN_USER")
        SC.message(id, user, "   ").assertError(400, "INVALID_REQUEST")
        SC.message(id, user, SC.cps(2_001)).assertError(400, "INVALID_REQUEST")
        SC.message(id, user, SC.cps(2_000)).assertStatus(200)
        SC.message(Db.missingId("inquiries"), other, "").assertError(400, "INVALID_REQUEST")
        SC.message(Db.missingId("inquiries"), other).assertError(404, "NOT_FOUND")
        SC.message(id, other).assertError(403, "NOT_OWNER")
        SC.close(id, user).assertStatus(200)
        SC.message(id, other).assertError(403, "NOT_OWNER")
        SC.message(id, user).assertError(409, "ITEM_STATE_CONFLICT")
        assertEquals(2, SC.messages(id).size)
    }

    @Test
    fun `§9_1 종결 - OPEN·ANSWERED → CLOSED(close_reason USER, closed_at = 현재), CLOSED면 409, 남의 문의 403, 없는 문의 404, 헤더 없음 401`() {
        val user = Db.createUser()
        val other = Db.createUser()
        val open = SC.newInquiry(user)
        val answered = SC.newInquiry(user)
        SC.readyAnswer(answered)
        val now = Flow.advance(HOUR)

        SC.close(open, null).assertError(401, "UNKNOWN_USER")
        SC.close(Db.missingId("inquiries"), user).assertError(404, "NOT_FOUND")
        SC.close(open, other).assertError(403, "NOT_OWNER")
        for (id in listOf(open, answered)) {
            val r = SC.close(id, user).assertStatus(200)
            val row = SC.assertInquiry(r.json, id, false, r)
            assertEquals("CLOSED", row.text("status"))
            assertEquals("USER", row.text("close_reason"))
            assertEquals(now, row.instant("closed_at"))
        }
        Flow.advance(HOUR)
        SC.close(open, other).assertError(403, "NOT_OWNER")
        SC.close(open, user).assertError(409, "ITEM_STATE_CONFLICT")
        assertEquals(now, SC.inquiryRow(open).instant("closed_at"), "실패한 종결은 closed_at을 바꾸지 않는다")
    }

    @Test
    fun `§9_1·§2_3 동시 종결 2건 - 하나만 200, 다른 하나는 409 ITEM_STATE_CONFLICT`() {
        val user = Db.createUser()
        val id = SC.newInquiry(user)
        // 이긴 요청이 문의 행을 잠근 채 UPDATE 직전 0.5초 멈춘다 → 진 요청은 그 잠금을 기다린 뒤 CLOSED를 본다(경합 보장)
        val rs = SlowTrigger.on("inquiries", "UPDATE", condition = "OLD.id = $id") { SC.concurrently(2) { SC.close(id, user) } }
        assertEquals(1, rs.count { it.status == 200 }, "하나만 성공: $rs")
        rs.single { it.status != 200 }.assertError(409, "ITEM_STATE_CONFLICT")
        assertEquals("CLOSED", SC.inquiryRow(id).text("status"))
    }

    // ---- §9.1 slaBreached ----

    @Test
    fun `§9_1 slaBreached - 미답변은 현재 ＞ sla_due_at(정각은 false), 답변은 first_answered_at ＞ sla_due_at(정각은 false)`() {
        val user = Db.createUser()
        val now = Flow.now()
        val dueNow = SC.insertInquiry(user, now.minus(SC.SLA))                       // sla_due_at = 현재
        val dueBefore = SC.insertInquiry(user, now.minus(SC.SLA).minus(SEC))         // sla_due_at = 현재 − 1초
        val answeredEarly = SC.insertInquiry(user, now.minus(days(3)), answeredAt = now.minus(SC.SLA)) // sla 전에 답변, 지금은 sla 지남
        val answerAtDue = SC.insertInquiry(user, now.minus(SC.SLA))
        val answerLate = SC.insertInquiry(user, now.minus(SC.SLA).minus(SEC))

        fun breached(id: Long): Boolean {
            val r = SC.detail(id, user).assertStatus(200)
            return r.json.get("slaBreached").asBoolean().also { SC.assertInquiry(r.json, id, it, r) }
        }
        assertEquals(false, breached(dueNow), "현재 = sla_due_at → 아직 아님")
        assertEquals(true, breached(dueBefore), "현재 > sla_due_at")
        assertEquals(false, breached(answeredEarly), "first_answered_at ≤ sla_due_at이면 지금 기한이 지났어도 false")

        val r1 = SC.answer(answerAtDue).assertStatus(200)
        SC.assertInquiry(r1.json, answerAtDue, slaBreached = false, ctx = r1)
        val r2 = SC.answer(answerLate).assertStatus(200)
        SC.assertInquiry(r2.json, answerLate, slaBreached = true, ctx = r2)
        assertEquals(now, SC.inquiryRow(answerLate).instant("first_answered_at"))
    }

    // ---- §9.2 조회 ----

    @Test
    fun `§9_2 목록 - 내 문의만 inquiryId 오름차순, 상세 - 메시지 id 순(author·body·createdAt)·orderItem null`() {
        val user = Db.createUser()
        val other = Db.createUser()
        val a = SC.newInquiry(user)
        SC.newInquiry(other)
        val b = SC.newInquiry(user)
        val list = SC.array(Api.get("/me/inquiries", user).assertStatus(200))
        assertEquals(listOf(a, b), list.map { it.longAt("inquiryId") })
        list.forEach { SC.assertInquiry(it, it.longAt("inquiryId"), false, list) }
        assertEquals(emptyList(), SC.array(Api.get("/me/inquiries", Db.createUser()).assertStatus(200)))
        Api.get("/me/inquiries", null).assertError(401, "UNKNOWN_USER")

        SC.readyAnswer(a)
        Flow.advance(SEC)
        SC.message(a, user, "다시 질문").assertStatus(200)
        val d = SC.detail(a, user).assertStatus(200)
        SC.assertInquiry(d.json, a, false, d)
        assertTrue(d.json.get("orderItem").isNull, "orderItemId 없는 문의의 orderItem = null: $d")
        val msgs = d.json.get("messages").toList()
        val rows = SC.messages(a)
        assertEquals(rows.map { it.text("author") }, msgs.map { it.textAt("author") })
        assertEquals(rows.map { it.text("body") }, msgs.map { it.textAt("body") })
        assertEquals(rows.map { it.instant("created_at") }, msgs.map { it.instantAt("createdAt") })
        assertEquals(listOf("USER", "ADMIN", "USER"), msgs.map { it.textAt("author") })
    }

    @Test
    fun `§9_2 상세 - orderItem은 지금의 항목 상태와 OPEN 분쟁 유무, 없는 문의 404, 남의 문의 403`() {
        val p = S2.purchase(60_000)
        S2.toConfirmed(p.item, p.seller, p.buyer)
        val id = SC.newInquiry(p.buyer, p.item)

        fun orderItem(): com.fasterxml.jackson.databind.JsonNode {
            val r = SC.detail(id, p.buyer).assertStatus(200)
            val oi = r.json.get("orderItem")
            assertTrue(oi != null && oi.isObject, "orderItem 객체: $r")
            assertEquals(p.item, oi.longAt("orderItemId"))
            assertTrue(oi.get("openDispute").isBoolean, "openDispute boolean: $r")
            return oi
        }
        orderItem().let { assertEquals("CONFIRMED", it.textAt("status")); assertEquals(false, it.get("openDispute").asBoolean()) }
        val dispute = S2.openDispute(p.item, p.buyer)
        orderItem().let { assertEquals("DISPUTED", it.textAt("status")); assertEquals(true, it.get("openDispute").asBoolean()) }
        S2.resolved(dispute, accept = false)
        orderItem().let { assertEquals("CONFIRMED", it.textAt("status")); assertEquals(false, it.get("openDispute").asBoolean()) }

        SC.detail(Db.missingId("inquiries"), p.buyer).assertError(404, "NOT_FOUND")
        SC.detail(id, p.seller).assertError(403, "NOT_OWNER")
        SC.detail(id, null).assertError(401, "UNKNOWN_USER")
    }

    // ---- §9.3 배치 inquiry-auto-close ----

    @Test
    fun `§9_3 inquiry-auto-close - ANSWERED이고 answered_at + 7일 ≤ 현재(정각 포함)만 CLOSED·AUTO·closed_at = 배치 시각, OPEN·재질문은 그대로, 재실행 무변경`() {
        val user = Db.createUser()
        val target = SC.newInquiry(user)
        val open = SC.newInquiry(user)
        val reopened = SC.newInquiry(user)
        val answeredAt = Flow.advance(HOUR)
        SC.readyAnswer(target)
        SC.readyAnswer(reopened)
        Flow.advance(SEC)
        SC.message(reopened, user).assertStatus(200)      // ANSWERED → OPEN

        Flow.moveTo(answeredAt.plus(days(7)).minus(SEC))
        S2.runBatch("inquiry-auto-close")
        assertEquals("ANSWERED", SC.inquiryRow(target).text("status"), "7일 − 1초: 아직")

        val at = Flow.moveTo(answeredAt.plus(days(7)))
        S2.runBatch("inquiry-auto-close")
        val row = SC.inquiryRow(target)
        assertEquals("CLOSED", row.text("status"))
        assertEquals("AUTO", row.text("close_reason"))
        assertEquals(at, row.instant("closed_at"), "closed_at = 배치 현재 시각")
        assertEquals("OPEN", SC.inquiryRow(open).text("status"))
        assertEquals("OPEN", SC.inquiryRow(reopened).text("status"))
        SC.assertAudit("INQUIRY_ANSWER", target, 1)     // 배치는 감사 로그를 쓰지 않는다(§2.7)
        val d = SC.detail(target, user).assertStatus(200)
        SC.assertInquiry(d.json, target, false, d)

        Flow.advance(HOUR)
        S2.runBatch("inquiry-auto-close")
        assertEquals(at, SC.inquiryRow(target).instant("closed_at"), "재실행은 종결한 문의를 다시 건드리지 않는다")
    }

    @Test
    fun `§9_3 inquiry-auto-close 격리 - A 종결이 실패해도 A는 ANSWERED·failed ≥ 1, B는 종결, 다시 돌리면 A도 종결(closed_at = 그 실행 시각)`() {
        val user = Db.createUser()
        val now = Flow.now()
        val a = SC.insertInquiry(user, now.minus(days(8)), answeredAt = now.minus(days(7)))
        val b = SC.insertInquiry(user, now.minus(days(8)), answeredAt = now.minus(days(7)).minus(HOUR))
        assertTrue(a < b)
        val at = Flow.advance(SEC)
        FailTrigger.on("inquiries", "UPDATE", "OLD.id = $a") {
            val r = S2.runBatch("inquiry-auto-close")
            assertTrue(r.failed() >= 1, "A 실패를 failed에 센다: $r")
            assertTrue(r.long("processed") >= 1, "B는 커밋된다: $r")
        }
        assertEquals("ANSWERED", SC.inquiryRow(a).text("status"))
        assertNull(SC.inquiryRow(a).instantOrNull("closed_at"))
        assertEquals("CLOSED", SC.inquiryRow(b).text("status"))
        assertEquals(at, SC.inquiryRow(b).instant("closed_at"))

        val later = Flow.advance(DAY)
        S2.runBatch("inquiry-auto-close")
        assertEquals("CLOSED", SC.inquiryRow(a).text("status"))
        assertEquals("AUTO", SC.inquiryRow(a).text("close_reason"))
        assertEquals(later, SC.inquiryRow(a).instant("closed_at"))
        assertEquals(at, SC.inquiryRow(b).instant("closed_at"))
    }
}
