package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.DAY
import com.ccle.behavior.contract2.HOUR
import com.ccle.behavior.contract2.Line
import com.ccle.behavior.contract2.days
import com.ccle.behavior.contract2.Kst
import com.ccle.behavior.contract2.S2
import com.ccle.behavior.contract2.SEC
import com.ccle.behavior.contract2.instantOrNull
import com.ccle.behavior.support.Api
import com.ccle.behavior.support.Contract3Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db2
import com.ccle.behavior.support.Db3
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.Resp
import com.ccle.behavior.support.instantAt
import com.ccle.behavior.support.longAt
import com.ccle.behavior.support.textAt
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-0007 §11.3 A9 관리자 조회 7개와 §2.5 키셋 페이지(limit 1~100 기본 50 · after · nextAfter = 받은 수가 limit과 같으면 마지막 id),
 * 날짜 범위 필터 양끝 포함·from > to 400, 감사 로그 목록 모양(§2.7·§11.3 detail).
 * 시드 행이 섞이므로 자기 행은 after(테스트 시작 전 최대 id)·필터로 좁히고, 시드 행 목록은 같은 조건의 DB 조회와 대조한다.
 */
@Contract3Test
class A9AdminQueryTest {

    private fun email(user: Long): String = Db.query("SELECT email FROM users WHERE id = ?", user) { it.getString(1) }.single()

    private fun ids(r: Resp, field: String): List<Long> = SC.items(r).map { it.longAt(field) }

    // ---- §2.5 페이지 · audit-logs ----

    @Test
    fun `§2_5·§11_3 audit-logs - after 이후 id 오름차순 limit개, nextAfter = 받은 수가 limit과 같으면 마지막 id(다음 페이지가 비어도)·적으면 null, action 필터`() {
        val before = SC.maxAuditId()
        val user = Db.createUser()
        val inquiries = (1..3).map { SC.newInquiry(user) }
        val now = Flow.now()
        inquiries.forEach { SC.readyAnswer(it) }
        val slot = SC.newSlot(SC.freeHourAtOrAfter(now.plus(Duration.ofDays(2))), 3)
        val answerAudits = inquiries.map { Db3.audits("INQUIRY_ANSWER", it).single().id }
        val slotAudit = Db3.audits("INBOUND_SLOT_CREATE", slot).single().id

        val base = "/admin/audit-logs?action=INQUIRY_ANSWER&after"
        val p1 = Api.get("$base=$before&limit=2").assertStatus(200)
        assertEquals(answerAudits.take(2), ids(p1, "auditId"))
        assertEquals(answerAudits[1], SC.nextAfter(p1))
        val p2 = Api.get("$base=${SC.nextAfter(p1)}&limit=2").assertStatus(200)
        assertEquals(answerAudits.drop(2), ids(p2, "auditId"))
        assertNull(SC.nextAfter(p2), "받은 수 < limit → null")
        val exact = Api.get("$base=$before&limit=3").assertStatus(200)
        assertEquals(answerAudits, ids(exact, "auditId"))
        assertEquals(answerAudits[2], SC.nextAfter(exact), "받은 수 = limit → 마지막 id")
        val empty = Api.get("$base=${answerAudits[2]}&limit=3").assertStatus(200)
        assertEquals(emptyList(), SC.items(empty))
        assertNull(SC.nextAfter(empty))

        // 항목 모양
        for ((j, inq) in SC.items(exact).zip(inquiries)) {
            assertEquals("INQUIRY_ANSWER", j.textAt("action"))
            assertEquals(inq, j.longAt("targetId"))
            assertTrue(j.has("detail") && j.get("detail").isNull, "INQUIRY_ANSWER detail null: $exact")
            assertEquals(now, j.instantAt("createdAt"))
        }
        // action 필터 · 필터 없음
        val slots = Api.get("/admin/audit-logs?action=INBOUND_SLOT_CREATE&after=$before").assertStatus(200)
        assertEquals(listOf(slotAudit), ids(slots, "auditId"))
        assertEquals(slot, SC.items(slots).single().longAt("targetId"))
        val all = Api.get("/admin/audit-logs?after=$before").assertStatus(200)
        assertEquals(answerAudits + slotAudit, ids(all, "auditId"))
        assertNull(SC.nextAfter(all))
    }

    @Test
    fun `§2_5 기본 limit 50·최대 100·after 생략은 처음부터 - 자기 감사 행 101개(슬롯 생성)로 페이지·nextAfter`() {
        val before = SC.maxAuditId()
        var t = SC.freeHourAtOrAfter(Flow.now().plus(days(300)))
        val slots = (1..101).map {
            val id = SC.newSlot(t, 1)
            t = SC.freeHourAtOrAfter(t.plus(HOUR))
            id
        }
        val audits = slots.map { Db3.audits("INBOUND_SLOT_CREATE", it).single().id }
        assertEquals(audits.sorted(), audits)

        val r = Api.get("/admin/audit-logs?after=$before").assertStatus(200)        // limit 생략 = 50
        assertEquals(audits.take(50), ids(r, "auditId"))
        assertEquals(audits[49], SC.nextAfter(r))
        val r2 = Api.get("/admin/audit-logs?after=${audits[49]}").assertStatus(200)
        assertEquals(audits.subList(50, 100), ids(r2, "auditId"))
        assertEquals(audits[99], SC.nextAfter(r2))
        val r100 = Api.get("/admin/audit-logs?after=$before&limit=100").assertStatus(200)
        assertEquals(audits.take(100), ids(r100, "auditId"))
        assertEquals(audits[99], SC.nextAfter(r100))
        val rest = Api.get("/admin/audit-logs?after=${audits[99]}&limit=100").assertStatus(200)
        assertEquals(listOf(audits[100]), ids(rest, "auditId"))
        assertNull(SC.nextAfter(rest), "받은 수 < limit → null")
        val r1 = Api.get("/admin/audit-logs?after=$before&limit=1").assertStatus(200)
        assertEquals(listOf(audits[0]), ids(r1, "auditId"))
        assertEquals(audits[0], SC.nextAfter(r1))
        // after 생략 = 처음부터, after=0도 처음부터
        val firstId = Db.long("SELECT min(id) FROM admin_audit_logs")
        assertEquals(listOf(firstId), ids(Api.get("/admin/audit-logs?limit=1").assertStatus(200), "auditId"))
        assertEquals(listOf(firstId), ids(Api.get("/admin/audit-logs?limit=1&after=0").assertStatus(200), "auditId"))
    }

    @Test
    fun `§2_7 기존 ／admin 쓰기(입고·검수·배송 완료·분쟁 판정)는 감사 로그를 쓰지 않는다`() {
        val before = SC.maxAuditId()
        val p = S2.purchase(30_000)
        S2.toConfirmed(p.item, p.seller, p.buyer)        // receipt·inspection·delivery(/admin) 포함
        S2.resolved(S2.openDispute(p.item, p.buyer), accept = false)
        val r = Api.get("/admin/audit-logs?after=$before").assertStatus(200)
        assertEquals(emptyList(), SC.items(r))
        assertNull(SC.nextAfter(r))
        assertEquals(before, SC.maxAuditId())
    }

    @Test
    fun `§2_5 limit·after가 정수 아님·범위 밖이면 400, 알 수 없는 enum 필터 400`() {
        for (q in listOf("limit=0", "limit=101", "limit=-1", "limit=abc", "limit=1.5", "after=-1", "after=x", "action=BOGUS")) {
            Api.get("/admin/audit-logs?$q").assertError(400, "INVALID_REQUEST")
        }
        Api.get("/admin/disputes?limit=0").assertError(400, "INVALID_REQUEST")
        Api.get("/admin/disputes?status=CLOSED").assertError(400, "INVALID_REQUEST")
        Api.get("/admin/settlements?after=-1").assertError(400, "INVALID_REQUEST")
        Api.get("/admin/settlements?status=HELD").assertError(400, "INVALID_REQUEST")
        Api.get("/admin/settlements?sellerId=abc").assertError(400, "INVALID_REQUEST")
        Api.get("/admin/users/restricted?type=purchase&limit=101").assertError(400, "INVALID_REQUEST")
        val u = Db.createUser()
        Api.get("/admin/users/$u/penalties?limit=0").assertError(400, "INVALID_REQUEST")
        Api.get("/admin/users/$u/warnings?after=x").assertError(400, "INVALID_REQUEST")
    }

    // ---- users/restricted ----

    @Test
    fun `§11_3 users-restricted - type별 제한 끝 ＞ 현재(정각 제외)인 사용자, 항목 모양, type 없음·알 수 없음 400`() {
        val now = Flow.now()
        val before = Db.long("SELECT coalesce(max(id), 0) FROM users")
        val purchase = Db.createUser(purchaseRestrictedUntil = now.plus(DAY))
        Db.createUser(purchaseRestrictedUntil = now)                       // 끝 = 현재 → 제외
        Db.createUser(purchaseRestrictedUntil = now.minus(SEC))
        val sale = Db.createUser(saleRestrictedUntil = now.plus(SEC))
        val both = Db.createUser(purchaseRestrictedUntil = now.plus(DAY), saleRestrictedUntil = now.plus(DAY.multipliedBy(2)))
        Db.createUser(saleRestrictedUntil = now)

        val rp = Api.get("/admin/users/restricted?type=purchase&after=$before").assertStatus(200)
        assertEquals(listOf(purchase, both), ids(rp, "userId"))
        for (j in SC.items(rp)) {
            val u = j.longAt("userId")
            assertEquals(email(u), j.textAt("email"))
            assertEquals(Db.user(u).purchaseRestrictedUntil, j.instantAt("restrictedUntil"))
        }
        val rs = Api.get("/admin/users/restricted?type=sale&after=$before").assertStatus(200)
        assertEquals(listOf(sale, both), ids(rs, "userId"))
        assertEquals(Db.user(both).saleRestrictedUntil, SC.items(rs)[1].instantAt("restrictedUntil"))
        val page = Api.get("/admin/users/restricted?type=sale&after=$before&limit=1").assertStatus(200)
        assertEquals(listOf(sale), ids(page, "userId"))
        assertEquals(sale, SC.nextAfter(page))

        Api.get("/admin/users/restricted").assertError(400, "INVALID_REQUEST")
        Api.get("/admin/users/restricted?type=both").assertError(400, "INVALID_REQUEST")
    }

    // ---- users/{id}/summary · penalties · warnings ----

    @Test
    fun `§11_3 summary·penalties·warnings - 경고·패널티 수는 전체 기간(31일 뒤에도 그대로), sellerGrade = 가장 늦은 effective_from 행(§19의 13)|null, 목록 모양 = DB 행, 없는 사용자 404`() {
        // 판매자: 검수 불합격 패널티 1건
        val p = S2.purchase(30_000)
        S2.toSellerFault(p.item, p.seller)
        // 구매자: 결제 기한 초과 경고 1건(ADR-0003 payment-due)
        val buyer = Db.createUser()
        val trade = Flow.trade(buyer, Db.createUser(), Db.createProductSize(), 20_000)
        Flow.moveTo(Db.trade(trade).paymentDueAt)
        Flow.runBatch("payment-due")
        assertEquals(1, Db.warnings(buyer).size, "준비: 경고 1건")
        // 최근 N일 계수가 아님을 가르기 위해 31일 뒤로(경고·패널티는 30일 창 밖)
        val now = Flow.advance(days(31))
        // 판매자 등급 행 2개: 늦은 달(PLUS)을 먼저 넣어 id 순과 effective_from 순을 어긋나게 한다
        val month = Kst.monthStart(now)
        Db3.insert("seller_grades", mapOf(
            "seller_id" to p.seller, "grade" to "PLUS", "effective_from" to month, "basis_amount" to 1_500_000L,
            "restricted" to false, "created_at" to now,
        ))
        Db3.insert("seller_grades", mapOf(
            "seller_id" to p.seller, "grade" to "PRO", "effective_from" to month.minusMonths(1), "basis_amount" to 3_500_000L,
            "restricted" to false, "created_at" to now,
        ))

        val s = Api.get("/admin/users/${p.seller}/summary").assertStatus(200).json
        for (f in listOf("userId", "email", "grade", "purchaseRestrictedUntil", "saleRestrictedUntil", "warningCount", "penaltyCount", "sellerGrade")) {
            assertTrue(s.has(f), "summary 필드 '$f' 없음: $s")
        }
        val sellerRow = Db.user(p.seller)
        assertEquals(p.seller, s.longAt("userId"))
        assertEquals(email(p.seller), s.textAt("email"))
        assertEquals(sellerRow.grade, s.textAt("grade"))
        assertEquals(sellerRow.purchaseRestrictedUntil, s.instantOrNull("purchaseRestrictedUntil"))
        assertEquals(sellerRow.saleRestrictedUntil, s.instantOrNull("saleRestrictedUntil"))
        assertEquals(0L, s.longAt("warningCount"))
        assertEquals(1L, s.longAt("penaltyCount"))
        val g = s.get("sellerGrade")
        assertEquals("PLUS", g.textAt("grade"))
        assertEquals(month.toString(), g.textAt("effectiveFrom"))
        assertEquals(1_500_000L, g.longAt("basisAmount"))
        assertEquals(false, g.get("restricted").asBoolean())

        val b = Api.get("/admin/users/$buyer/summary").assertStatus(200).json
        assertEquals(1L, b.longAt("warningCount"))
        assertEquals(0L, b.longAt("penaltyCount"))
        assertTrue(b.get("sellerGrade").isNull, "판매자 등급 행 없음 → null: $b")
        assertEquals(Db.user(buyer).grade, b.textAt("grade"))

        // penalties
        val pen = Db2.penaltiesOfSeller(p.seller).single()
        val rp = Api.get("/admin/users/${p.seller}/penalties").assertStatus(200)
        val pj = SC.items(rp).single()
        assertEquals(pen.id, pj.longAt("penaltyId"))
        assertEquals(pen.orderItemId, pj.longAt("orderItemId"))
        assertEquals(pen.amount, pj.longAt("amount"))
        assertEquals(pen.settlementId, pj.get("settlementId").takeUnless { it.isNull }?.asLong())
        assertEquals(pen.createdAt, pj.instantAt("createdAt"))
        assertNull(SC.nextAfter(rp))
        assertEquals(emptyList(), SC.items(Api.get("/admin/users/$buyer/penalties").assertStatus(200)))

        // warnings
        val w = Db.warnings(buyer).single()
        val rw = Api.get("/admin/users/$buyer/warnings").assertStatus(200)
        val wj = SC.items(rw).single()
        assertEquals(w.id, wj.longAt("warningId"))
        assertEquals(trade, wj.longAt("tradeId"))
        assertEquals(w.createdAt, wj.instantAt("createdAt"))
        assertEquals(emptyList(), SC.items(Api.get("/admin/users/${p.seller}/warnings").assertStatus(200)))

        val missing = Db.missingId("users")
        for (path in listOf("summary", "penalties", "warnings")) Api.get("/admin/users/$missing/$path").assertError(404, "NOT_FOUND")
    }

    // ---- settlements ----

    @Test
    fun `§11_3·§2_5 settlements - 자기 정산(PAID 주·패널티만 있는 CARRIED 주)으로 sellerId·status·from~to(period_start 양끝 포함) 필터, 항목 모양, 없는 sellerId 빈 목록, from ＞ to·형식 오류 400`() {
        val seller = Db.createUser()
        val buyer = Db.createUser()
        val p1 = S2.purchase(listOf(Line(100_000, seller = seller)), buyer)
        S2.toConfirmed(p1.item, seller, buyer)
        val t0 = Flow.now()
        val w1 = Kst.weekStart(t0)
        val w2 = w1.plusDays(7)
        val c1 = Kst.cutoffOf(t0)
        Flow.moveTo(c1)
        S2.runBatch("weekly-settlement")
        val p2 = S2.purchase(listOf(Line(50_000, seller = seller)))
        S2.toSellerFault(p2.item, seller)                     // 둘째 주: 확정 없음 + 패널티 → CARRIED
        Flow.moveTo(c1.plus(days(7)))
        S2.runBatch("weekly-settlement")
        val rows = Db2.settlementsOfSeller(seller)
        assertEquals(listOf(w1, w2), rows.map { it.periodStart }, "준비: 두 주 정산")
        assertEquals(listOf("PAID", "CARRIED"), rows.map { it.status }, "준비: PAID·CARRIED")
        val (s1, s2) = rows.map { it.id }

        fun q(query: String): List<Long> = ids(Api.get("/admin/settlements?sellerId=$seller$query").assertStatus(200), "settlementId")
        val r = Api.get("/admin/settlements?sellerId=$seller").assertStatus(200)
        assertEquals(listOf(s1, s2), ids(r, "settlementId"))
        assertNull(SC.nextAfter(r))
        val byId = rows.associateBy { it.id }
        for (j in SC.items(r)) {
            val row = byId.getValue(j.longAt("settlementId"))
            assertEquals(seller, j.longAt("sellerId"))
            assertEquals(row.periodStart.toString(), j.textAt("periodStart"))
            assertEquals(row.status, j.textAt("status"))
            assertEquals(row.net, j.longAt("net"))
            assertEquals(row.payout, j.longAt("payout"))
            assertEquals(row.paidAt, j.instantOrNull("paidAt"))
        }
        assertEquals(listOf(s1), q("&status=PAID"))
        assertEquals(listOf(s2), q("&status=CARRIED"))
        assertEquals(listOf(s1), q("&from=$w1&to=$w1"))
        assertEquals(listOf(s1, s2), q("&from=$w1&to=$w2"))
        assertEquals(listOf(s2), q("&from=${w1.plusDays(1)}&to=$w2"))
        assertEquals(listOf(s1), q("&from=$w1&to=${w2.minusDays(1)}"))
        assertEquals(listOf(s2), q("&from=$w2"))
        assertEquals(listOf(s1), q("&to=$w1"))
        assertEquals(listOf(s2), q("&status=CARRIED&from=$w1&to=$w2"))
        assertEquals(listOf(s2), q("&after=$s1"))

        val none = Api.get("/admin/settlements?sellerId=${Db.missingId("users")}").assertStatus(200)   // §19의 13
        assertEquals(emptyList(), SC.items(none))
        assertNull(SC.nextAfter(none))

        Api.get("/admin/settlements?from=$w2&to=$w1").assertError(400, "INVALID_REQUEST")
        Api.get("/admin/settlements?from=2026-9-1").assertError(400, "INVALID_REQUEST")
        Api.get("/admin/settlements?to=2026-02-30").assertError(400, "INVALID_REQUEST")
    }

    // ---- disputes ----

    @Test
    fun `§11_3 disputes - status 필터(OPEN·REJECTED·ACCEPTED), 항목 모양(openedAt·resolvedAt), id 오름차순`() {
        val before = Db.long("SELECT coalesce(max(id), 0) FROM disputes")
        val a = S2.purchase(30_000)
        val b = S2.purchase(30_000)
        S2.toConfirmed(a.item, a.seller, a.buyer)
        S2.toConfirmed(b.item, b.seller, b.buyer)
        val c = S2.purchase(30_000)
        S2.toConfirmed(c.item, c.seller, c.buyer)
        val open = S2.openDispute(a.item, a.buyer)
        val rejected = S2.openDispute(b.item, b.buyer)
        val accepted = S2.openDispute(c.item, c.buyer)
        Flow.advance(SEC)
        S2.resolved(rejected, accept = false)
        S2.resolved(accepted, accept = true)

        val all = Api.get("/admin/disputes?after=$before").assertStatus(200)
        assertEquals(listOf(open, rejected, accepted), ids(all, "disputeId"))
        for (j in SC.items(all)) {
            val row = Db2.dispute(j.longAt("disputeId"))
            assertEquals(row.orderItemId, j.longAt("orderItemId"))
            assertEquals(row.status, j.textAt("status"))
            assertEquals(row.openedAt, j.instantAt("openedAt"))
            assertEquals(row.resolvedAt, j.instantOrNull("resolvedAt"))
        }
        assertEquals(listOf(open), ids(Api.get("/admin/disputes?status=OPEN&after=$before").assertStatus(200), "disputeId"))
        assertEquals(listOf(rejected), ids(Api.get("/admin/disputes?status=REJECTED&after=$before").assertStatus(200), "disputeId"))
        assertEquals(listOf(accepted), ids(Api.get("/admin/disputes?status=ACCEPTED&after=$before").assertStatus(200), "disputeId"))
    }
}
