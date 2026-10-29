package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.S2
import com.ccle.behavior.contract2.SEC
import com.ccle.behavior.contract2.days
import com.ccle.behavior.support.Api
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db3
import com.ccle.behavior.support.FailTrigger
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.Resp
import com.ccle.behavior.support.Row3
import com.ccle.behavior.support.instantAt
import com.ccle.behavior.support.longAt
import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 보충 묶음 D 중 A15(ADR-0007 §20.4 문의 재개·관리자 문의함) · A16(§20.5 입고 예약 변경·상세) · A17(§20.6 알림 일괄 읽음·안 읽은 수·
 * notification-purge) 전용 도우미. 엔드포인트 호출·준비·응답 모양 단언만 둔다. 기대값은 테스트 쪽에서 ADR 절을 근거로 정한다.
 * 같은 패키지의 다른 묶음 D 도우미와 이름이 겹치지 않게 모든 선언을 object SDB 안에 둔다.
 */
object SDB {
    // ---- A15 문의 재개 (§20.4.1) ----
    fun reopen(id: Long, user: Any?, body: Any? = "bt 재개"): Resp = Api.post("/me/inquiries/$id/reopen", mapOf("body" to body), user)
    fun reopenRaw(id: Long, user: Any?, raw: String): Resp = Api.request("POST", "/me/inquiries/$id/reopen", rawBody = raw, userId = user)

    /** 문의를 USER 종결(§9.1)하고 closed_at을 돌려준다(준비). */
    fun readyClose(id: Long, user: Long): Instant {
        val r = SC.close(id, user)
        assertEquals(200, r.status, "준비(A6) 종결: $r")
        return SC.inquiryRow(id).instant("closed_at")
    }

    // ---- A15 관리자 문의함 (§20.4.2) ----
    fun adminInquiries(query: String = ""): Resp = Api.get("/admin/inquiries" + (if (query.isEmpty()) "" else "?$query"))
    fun adminInquiry(id: Long): Resp = Api.get("/admin/inquiries/$id")

    /** §20.4.2 항목 = 문의 모양(§9.1) + userId. 반환: DB 행. */
    fun assertAdminInquiry(j: JsonNode, id: Long, slaBreached: Boolean, ctx: Any): Row3 {
        val row = SC.assertInquiry(j, id, slaBreached, ctx)
        assertTrue(j.has("userId"), "관리자 문의 모양 필드 'userId' 없음: $ctx")
        assertEquals(row.long("user_id"), j.longAt("userId"), "userId = 작성자: $ctx")
        return row
    }

    fun maxId(table: String): Long {
        require(table.matches(Regex("[a-z_]+")))
        return Db.long("SELECT coalesce(max(id), 0) FROM $table")
    }

    // ---- A16 입고 예약 변경·상세 (§20.5) ----
    fun reschedule(id: Long, user: Any?, slotId: Any?): Resp =
        Api.post("/me/inbound-reservations/$id/reschedule", mapOf("slotId" to slotId), user)

    fun rescheduleRaw(id: Long, user: Any?, raw: String): Resp =
        Api.request("POST", "/me/inbound-reservations/$id/reschedule", rawBody = raw, userId = user)

    fun slotDetail(id: Long, user: Any? = null): Resp = Api.get("/inbound-slots/$id", user)
    fun myReservation(id: Long, user: Any?): Resp = Api.get("/me/inbound-reservations/$id", user)

    /** 살아 있는 예약(RESERVED·CHECKED_IN) 수(§12.3). */
    fun live(slotId: Long): Long = Db3.count("inbound_reservations", "slot_id = ? AND status IN ('RESERVED','CHECKED_IN')", slotId)

    fun slotStart(slotId: Long): Instant = Db3.row("inbound_slots", slotId).instant("slot_start")

    /** §12.3 슬롯 모양 = DB 행, reserved = 살아 있는 예약 수. */
    fun assertSlot(j: JsonNode, slotId: Long, ctx: Any) {
        val row = Db3.row("inbound_slots", slotId)
        for (f in listOf("slotId", "slotStart", "capacity", "reserved")) assertTrue(j.has(f), "슬롯 모양 필드 '$f' 없음: $ctx")
        assertEquals(slotId, j.longAt("slotId"), "slotId: $ctx")
        assertEquals(row.instant("slot_start"), j.instantAt("slotStart"), "slotStart: $ctx")
        assertEquals(row.long("capacity"), j.longAt("capacity"), "capacity: $ctx")
        assertEquals(live(slotId), j.longAt("reserved"), "reserved = RESERVED·CHECKED_IN 수: $ctx")
    }

    /** §20.5.1 응답의 한 쪽(cancelled·reserved) 객체. */
    fun part(r: Resp, field: String): JsonNode {
        val n = r.json.get(field)
        assertTrue(n != null && n.isObject, "응답 '$field' 객체 없음: $r")
        return n
    }

    // ---- A17 알림 (§20.6) ----
    fun readAll(user: Any?): Resp = Api.post("/me/notifications/read-all", null, user)
    fun unreadCount(user: Any?): Resp = Api.get("/me/notifications/unread-count", user)

    /**
     * 사용자 u의 알림 n행을 실제 배치(deadline-reminder, §7.1)로 만든다: 1초씩 시계를 옮기며 구매 제한 끝을 "건 시각 + 7일"로 직접 두고
     * 배치를 돌리면 끝 시각 E가 매번 달라 새 PURCHASE_RESTRICTED 1행이 생긴다(§7.1 연장). 제한 기간은 계약 안의 값(§20.6.3 (다)),
     * 그 사용자에게 입찰을 두지 않는다(§18·ADR-0006 §5 C2 픽스처 규칙). 반환: 그 알림 행들(id 순).
     */
    fun restrictionNotifs(u: Long, n: Int): List<Row3> {
        val before = Db3.notificationsOf(u).map { it.id }.toSet()
        repeat(n) {
            val now = Flow.advance(SEC)
            Db.setRestrictions(u, now.plus(days(7)), null)
            S2.runBatch("deadline-reminder")
        }
        val rows = Db3.notificationsOf(u).filter { it.id !in before }
        assertEquals(n, rows.size, "준비(A4) 제한 알림 $n 행: $rows")
        assertTrue(rows.all { it.text("kind") == "PURCHASE_RESTRICTED" && it.instantOrNull("read_at") == null }, "준비: 안 읽은 PURCHASE_RESTRICTED: $rows")
        assertTrue(Db.bidsOfUser(u).isEmpty(), "준비: 제한 사용자에게 입찰 없음(C2 픽스처 규칙)")
        return rows
    }

    /** §20.6.3 대상 조건 문장 그대로(`:due = 현재 − 30일`, 배치와 같은 시각 바인드)로 센 지금 대상 수(SG-1 — 공유 DB라 실행 직전 수와 맞춘다). */
    fun purgeTargetCount(now: Instant): Long =
        Db.long("SELECT count(*) FROM notifications t WHERE t.read_at IS NOT NULL AND t.read_at <= ?", now.minus(days(30)))

    fun notif(id: Long): Row3 = Db3.row("notifications", id)
    fun notifExists(id: Long): Boolean = Db3.count("notifications", "id = ?", id) > 0

    /** 단건 읽음(§7.2)으로 준비. 반환: read_at. */
    fun readyRead(id: Long, user: Long): Instant {
        val r = SB.read(id, user)
        assertEquals(200, r.status, "준비(A4) 읽음: $r")
        return notif(id).instant("read_at")
    }

    // ---- 실패 주입 (DELETE — FailTrigger는 UPDATE·INSERT만 받는다) ----
    /** FailTrigger와 같은 방식으로 `BEFORE DELETE ON <table> FOR EACH ROW WHEN (<condition>)`에서 예외를 낸다. 끝나면 지운다. */
    fun <T> failOnDelete(table: String, condition: String, block: () -> T): T {
        require(table.matches(Regex("[a-z_]+")))
        val name = "bt_fail_" + UUID.randomUUID().toString().replace("-", "").take(12)
        Db.update(
            "CREATE FUNCTION $name() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN " +
                "RAISE EXCEPTION '${FailTrigger.RAISE_MARK} $name on $table'; END $$",
        )
        val result = try {
            Db.update("CREATE TRIGGER $name BEFORE DELETE ON $table FOR EACH ROW WHEN ($condition) EXECUTE FUNCTION $name()")
            block()
        } finally {
            Db.update("DROP TRIGGER IF EXISTS $name ON $table")
            Db.update("DROP FUNCTION IF EXISTS $name()")
        }
        assertEquals(0L, Db.long("SELECT count(*) FROM pg_trigger WHERE tgname = ?", name), "트리거 제거")
        assertEquals(0L, Db.long("SELECT count(*) FROM pg_proc WHERE proname = ?", name), "함수 제거")
        return result
    }
}
