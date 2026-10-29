package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.HOUR
import com.ccle.behavior.contract2.S2
import com.ccle.behavior.contract2.SEC
import com.ccle.behavior.contract2.days
import com.ccle.behavior.support.Contract3Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.Resp
import com.ccle.behavior.support.instantAt
import com.ccle.behavior.support.longAt
import com.ccle.behavior.support.textAt
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-0007 §20.4.2 A15 관리자 문의함: `GET /admin/inquiries`(§2.5 키셋 페이지 — limit 1~100 기본 50·after·nextAfter, inquiryId 오름차순,
 * 필터 status·slaBreached 글자 그대로 — §19의 3, 항목 = 문의 모양 + userId, slaBreached 필터 = 응답 값과 같은 식),
 * `GET /admin/inquiries/{id}`(없는 문의 404, 상세 모양 §9.2 + userId), 둘 다 조회라 감사 로그 없음(§2.7·§20.1).
 * 시드·다른 테스트 문의가 섞이므로 자기 행은 after(테스트 시작 전 최대 inquiry id)로 좁힌다.
 */
@Contract3Test
class A15AdminInquiryTest {

    private fun ids(r: Resp): List<Long> = SC.items(r).map { it.longAt("inquiryId") }

    @Test
    fun `§20_4_2·§2_5 목록 - after 이후 inquiryId 오름차순 limit개, nextAfter = 받은 수가 limit과 같으면 마지막 id(다음 페이지가 비어도)·적으면 null, 항목 = 문의 모양 + userId`() {
        val before = SDB.maxId("inquiries")
        val u1 = Db.createUser()
        val u2 = Db.createUser()
        val a = SC.newInquiry(u1)
        val b = SC.newInquiry(u2)
        val c = SC.newInquiry(u1)
        val audits = SC.maxAuditId()

        val p1 = SDB.adminInquiries("after=$before&limit=2").assertStatus(200)
        assertEquals(listOf(a, b), ids(p1))
        assertEquals(b, SC.nextAfter(p1))
        val p2 = SDB.adminInquiries("after=${SC.nextAfter(p1)}&limit=2").assertStatus(200)
        assertEquals(listOf(c), ids(p2))
        assertNull(SC.nextAfter(p2), "받은 수 < limit → null")
        val exact = SDB.adminInquiries("after=$before&limit=3").assertStatus(200)
        assertEquals(listOf(a, b, c), ids(exact))
        assertEquals(c, SC.nextAfter(exact), "받은 수 = limit → 마지막 id")
        val empty = SDB.adminInquiries("after=$c&limit=3").assertStatus(200)
        assertEquals(emptyList(), SC.items(empty))
        assertNull(SC.nextAfter(empty))

        SC.items(exact).forEach { SDB.assertAdminInquiry(it, it.longAt("inquiryId"), slaBreached = false, ctx = exact) }
        assertEquals(listOf(u1, u2, u1), SC.items(exact).map { it.longAt("userId") })
        assertEquals(audits, SC.maxAuditId(), "조회는 감사 로그를 쓰지 않는다")
    }

    @Test
    fun `§2_5 기본 limit 50·최대 100·after 생략은 처음부터 - 자기 문의 51건`() {
        val before = SDB.maxId("inquiries")
        val user = Db.createUser()
        val mine = (1..51).map { SC.newInquiry(user) }
        val r = SDB.adminInquiries("after=$before").assertStatus(200)                // limit 생략 = 50
        assertEquals(mine.take(50), ids(r))
        assertEquals(mine[49], SC.nextAfter(r))
        val rest = SDB.adminInquiries("after=${mine[49]}").assertStatus(200)
        assertEquals(listOf(mine[50]), ids(rest))
        assertNull(SC.nextAfter(rest))
        val r100 = SDB.adminInquiries("after=$before&limit=100").assertStatus(200)
        assertEquals(mine, ids(r100))
        assertNull(SC.nextAfter(r100), "51 < 100 → null")
        val r1 = SDB.adminInquiries("after=$before&limit=1").assertStatus(200)
        assertEquals(listOf(mine[0]), ids(r1))
        assertEquals(mine[0], SC.nextAfter(r1))
        // after 생략·after=0은 처음부터(전체 최소 id)
        val firstId = Db.long("SELECT min(id) FROM inquiries")
        assertEquals(listOf(firstId), ids(SDB.adminInquiries("limit=1").assertStatus(200)))
        assertEquals(listOf(firstId), ids(SDB.adminInquiries("limit=1&after=0").assertStatus(200)))
    }

    @Test
    fun `§20_4_2 status 필터 - OPEN·ANSWERED·CLOSED만, 다른 필터와 함께 페이지`() {
        val before = SDB.maxId("inquiries")
        val user = Db.createUser()
        val open = SC.newInquiry(user)
        val answered = SC.newInquiry(user)
        SC.readyAnswer(answered)
        val closed = SC.newInquiry(user)
        SDB.readyClose(closed, user)
        val open2 = SC.newInquiry(user)

        assertEquals(listOf(open, open2), ids(SDB.adminInquiries("status=OPEN&after=$before").assertStatus(200)))
        assertEquals(listOf(answered), ids(SDB.adminInquiries("status=ANSWERED&after=$before").assertStatus(200)))
        val c = SDB.adminInquiries("status=CLOSED&after=$before").assertStatus(200)
        assertEquals(listOf(closed), ids(c))
        assertEquals("CLOSED", SC.items(c).single().textAt("status"))
        assertEquals("USER", SC.items(c).single().textAt("closeReason"))
        val page = SDB.adminInquiries("status=OPEN&after=$before&limit=1").assertStatus(200)
        assertEquals(listOf(open), ids(page))
        assertEquals(open, SC.nextAfter(page))
        assertEquals(listOf(open2), ids(SDB.adminInquiries("status=OPEN&after=$open&limit=1").assertStatus(200)))
        assertEquals(listOf(open, answered, closed, open2), ids(SDB.adminInquiries("after=$before").assertStatus(200)))
    }

    @Test
    fun `§20_4_2 slaBreached 필터 - 응답 값과 같은 식(coalesce(first_answered_at ＞ sla_due_at, sla_due_at ＜ 현재)), 정각은 false, 미답변 행도 false 필터에 든다`() {
        val before = SDB.maxId("inquiries")
        val user = Db.createUser()
        val now = Flow.now()
        val c = now.minus(days(3))
        val dueBefore = SC.insertInquiry(user, now.minus(SC.SLA).minus(SEC))            // 미답변, sla_due_at = 현재 − 1초 → true
        val dueNow = SC.insertInquiry(user, now.minus(SC.SLA))                          // 미답변, sla_due_at = 현재 → false
        val dueLater = SC.insertInquiry(user, now.minus(SC.SLA).plus(HOUR))             // 미답변, 아직 → false
        val late = SC.insertInquiry(user, c, answeredAt = c.plus(SC.SLA).plus(SEC))     // 늦은 답변 → true
        val atDue = SC.insertInquiry(user, c, answeredAt = c.plus(SC.SLA))              // 정각 답변 → false
        val early = SC.insertInquiry(user, c, answeredAt = c.plus(HOUR))                // 일찍 답변(지금은 기한 지남) → false
        val lateClosed = SC.insertInquiry(user, c, answeredAt = c.plus(SC.SLA).plus(HOUR))
        SDB.readyClose(lateClosed, user)                                                  // CLOSED도 같은 식 → true
        val expTrue = listOf(dueBefore, late, lateClosed)
        val expFalse = listOf(dueNow, dueLater, atDue, early)

        val t = SDB.adminInquiries("slaBreached=true&after=$before").assertStatus(200)
        assertEquals(expTrue, ids(t))
        SC.items(t).forEach { SDB.assertAdminInquiry(it, it.longAt("inquiryId"), slaBreached = true, ctx = t) }
        val f = SDB.adminInquiries("slaBreached=false&after=$before").assertStatus(200)
        assertEquals(expFalse, ids(f), "미답변·기한 전 행이 false 필터에서 빠지지 않는다")
        SC.items(f).forEach { SDB.assertAdminInquiry(it, it.longAt("inquiryId"), slaBreached = false, ctx = f) }
        assertEquals((expTrue + expFalse).sorted(), ids(SDB.adminInquiries("after=$before").assertStatus(200)))
        // 두 필터 함께
        assertEquals(listOf(late), ids(SDB.adminInquiries("status=ANSWERED&slaBreached=true&after=$before").assertStatus(200)))
        assertEquals(listOf(atDue, early), ids(SDB.adminInquiries("status=ANSWERED&slaBreached=false&after=$before").assertStatus(200)))
        assertEquals(listOf(lateClosed), ids(SDB.adminInquiries("status=CLOSED&slaBreached=true&after=$before").assertStatus(200)))
        assertEquals(listOf(dueBefore), ids(SDB.adminInquiries("status=OPEN&slaBreached=true&after=$before").assertStatus(200)))

        // 시계가 가면 계산 값도 바뀐다: dueNow는 1초 뒤 true
        Flow.moveTo(now.plus(SEC))
        assertEquals(listOf(dueBefore, dueNow, late, lateClosed), ids(SDB.adminInquiries("slaBreached=true&after=$before").assertStatus(200)))
    }

    @Test
    fun `§20_4_2 목록 400 INVALID_REQUEST - status·slaBreached 글자 그대로만(소문자·빈 값·알 수 없는 값·1), limit·after 정수 아님·범위 밖`() {
        for (q in listOf(
            "status=open", "status=", "status=BOGUS", "slaBreached=TRUE", "slaBreached=1", "slaBreached=", "slaBreached=yes",
            "limit=0", "limit=101", "limit=-1", "limit=abc", "limit=1.5", "after=-1", "after=x",
        )) {
            SDB.adminInquiries(q).assertError(400, "INVALID_REQUEST")
        }
    }

    @Test
    fun `§20_4_2 상세 - 문의 모양 + userId + messages(id 순) + orderItem(지금 상태·OPEN 분쟁 유무 | null), 없는 문의 404, 감사 로그 없음`() {
        val p = S2.purchase(50_000)
        S2.toConfirmed(p.item, p.seller, p.buyer)
        val withItem = SC.newInquiry(p.seller, p.item)
        val plain = SC.newInquiry(p.buyer)
        SC.readyAnswer(plain)
        Flow.advance(SEC)
        SC.message(plain, p.buyer, "다시").assertStatus(200)
        val audits = SC.maxAuditId()

        val d = SDB.adminInquiry(plain).assertStatus(200)
        SDB.assertAdminInquiry(d.json, plain, slaBreached = false, ctx = d)
        assertEquals(p.buyer, d.json.longAt("userId"))
        assertTrue(d.json.has("orderItem") && d.json.get("orderItem").isNull, "orderItemId 없는 문의의 orderItem = null: $d")
        val msgs = d.json.get("messages").toList()
        val rows = SC.messages(plain)
        assertEquals(listOf("USER", "ADMIN", "USER"), msgs.map { it.textAt("author") })
        assertEquals(rows.map { it.text("body") }, msgs.map { it.textAt("body") })
        assertEquals(rows.map { it.instant("created_at") }, msgs.map { it.instantAt("createdAt") })

        val d2 = SDB.adminInquiry(withItem).assertStatus(200)
        SDB.assertAdminInquiry(d2.json, withItem, slaBreached = false, ctx = d2)
        assertEquals(p.seller, d2.json.longAt("userId"), "판매자가 쓴 문의의 userId = 판매자")
        val oi = d2.json.get("orderItem")
        assertTrue(oi != null && oi.isObject, "orderItem 객체: $d2")
        assertEquals(p.item, oi.longAt("orderItemId"))
        assertEquals("CONFIRMED", oi.textAt("status"))
        assertEquals(false, oi.get("openDispute").asBoolean())
        S2.openDispute(p.item, p.buyer)
        val oi2 = SDB.adminInquiry(withItem).assertStatus(200).json.get("orderItem")
        assertEquals("DISPUTED", oi2.textAt("status"))
        assertTrue(oi2.get("openDispute").isBoolean && oi2.get("openDispute").asBoolean(), "OPEN 분쟁 있음: $oi2")

        SDB.adminInquiry(Db.missingId("inquiries")).assertError(404, "NOT_FOUND")
        assertEquals(audits, SC.maxAuditId(), "조회는 감사 로그를 쓰지 않는다(§2.7)")
    }
}
