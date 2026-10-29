package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.HOUR
import com.ccle.behavior.support.Contract3Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.SlowTrigger
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-0007 §20.6.1 일괄 읽음 `POST /me/notifications/read-all`(내 안 읽은 알림을 id 순 FOR UPDATE → read_at = 현재, 200 {"readCount"},
 * 0건도 200 — 단건 읽음 §7.2의 409와 다르다) · §20.6.2 안 읽은 수 `GET /me/notifications/unread-count`(= ?unread=true 길이),
 * 동시성(일괄 두 요청은 교착 없이 각 행을 한 번만 읽음 처리, 단건과 겹치면 먼저 잠근 쪽만 그 행을 바꾼다).
 * 알림은 실제 배치(deadline-reminder)로 만든다(SDB.restrictionNotifs — 입찰 없는 사용자, C2 픽스처 규칙).
 */
@Contract3Test
class A17NotificationBulkTest {

    private fun unread(user: Long): Long {
        val r = SDB.unreadCount(user).assertStatus(200)
        assertTrue(r.json.isObject && r.json.has("unreadCount"), "{unreadCount} 모양: $r")
        val n = r.long("unreadCount")
        val list = SB.notifications(user, "true").assertStatus(200)
        assertEquals(list.json.size().toLong(), n, "unreadCount = ?unread=true 길이: $r · $list")
        return n
    }

    @Test
    fun `§20_6_1 일괄 읽음 - 내 안 읽은 알림만 read_at = 현재, readCount = 바꾼 수, 이미 읽은 알림·남의 알림 그대로, 뒤의 단건 읽음은 409`() {
        val u = Db.createUser()
        val v = Db.createUser()
        val rows = SDB.restrictionNotifs(u, 3)
        val others = SDB.restrictionNotifs(v, 1)
        val t1 = Flow.advance(HOUR)
        SDB.readyRead(rows[0].id, u)
        val t2 = Flow.advance(HOUR)

        val r = SDB.readAll(u).assertStatus(200)
        assertEquals(2L, r.long("readCount"), "안 읽은 2건만: $r")
        assertEquals(t1, SDB.notif(rows[0].id).instant("read_at"), "이미 읽은 알림의 read_at 그대로")
        for (row in rows.drop(1)) assertEquals(t2, SDB.notif(row.id).instant("read_at"), "read_at = 현재: ${SDB.notif(row.id)}")
        assertNull(SDB.notif(others[0].id).instantOrNull("read_at"), "남의 알림은 그대로")
        assertEquals(0L, unread(u))
        assertEquals(1L, unread(v))
        // 전체 목록은 그대로 남고 readAt이 보인다(§7.2 모양)
        val all = SB.notifications(u).assertStatus(200)
        assertEquals(rows.map { it.id }, all.json.map { it.get("notificationId").asLong() })
        all.json.forEachIndexed { i, j -> SB.assertNotifBody(j, SDB.notif(rows[i].id)) }

        SB.read(rows[1].id, u).assertError(409, "ITEM_STATE_CONFLICT")

        // 다시 부르면 0건도 200, read_at 그대로
        Flow.advance(HOUR)
        assertEquals(0L, SDB.readAll(u).assertStatus(200).long("readCount"))
        for (row in rows.drop(1)) assertEquals(t2, SDB.notif(row.id).instant("read_at"))
    }

    @Test
    fun `§20_6_1·§20_6_2 알림 없는 사용자 - 일괄 읽음 200 readCount 0, 안 읽은 수 0`() {
        val u = Db.createUser()
        assertEquals(0L, SDB.readAll(u).assertStatus(200).long("readCount"))
        assertEquals(0L, unread(u))
    }

    @Test
    fun `§20_6_2 안 읽은 수 - 단건·일괄 읽음에 따라 3 → 2 → 0, 항상 ?unread=true 길이와 같다`() {
        val u = Db.createUser()
        val rows = SDB.restrictionNotifs(u, 3)
        assertEquals(3L, unread(u))
        SDB.readyRead(rows[1].id, u)
        assertEquals(2L, unread(u))
        SDB.readAll(u).assertStatus(200)
        assertEquals(0L, unread(u))
        SDB.restrictionNotifs(u, 1)                                          // 새 알림이 생기면 다시 센다
        assertEquals(1L, unread(u))
    }

    @Test
    fun `§20_6 401 UNKNOWN_USER - 일괄 읽음·안 읽은 수 모두 헤더 없음·숫자 아님·없는 사용자`() {
        for (user in listOf(null, "abc", Db.missingId("users"))) {
            SDB.readAll(user).assertError(401, "UNKNOWN_USER")
            SDB.unreadCount(user).assertError(401, "UNKNOWN_USER")
        }
    }

    @Test
    fun `§20_6_1 동시 일괄 읽음 2건 - 교착 없이 둘 다 200, readCount 합 = 안 읽은 수, 행마다 read_at 1회(= 현재)`() {
        val u = Db.createUser()
        val rows = SDB.restrictionNotifs(u, 3)
        val now = Flow.advance(HOUR)
        // 먼저 잠근 요청이 UPDATE에서 멈춘다 → 다른 요청은 같은 행 잠금을 기다린다(경합 관측)
        val rs = SlowTrigger.on("notifications", "UPDATE", condition = "OLD.user_id = $u") {
            SC.concurrently(2) { SDB.readAll(u) }
        }
        rs.forEach { it.assertStatus(200) }
        assertEquals(3L, rs.sumOf { it.long("readCount") }, "각 행은 한 번만 센다: $rs")
        for (row in rows) assertEquals(now, SDB.notif(row.id).instant("read_at"))
        assertEquals(0L, unread(u))
    }

    @Test
    fun `§20_6_1 단건 읽음과 일괄 읽음이 같은 알림에서 겹치면 먼저 잠근 쪽만 그 행을 읽음 처리 - 단건 200이면 일괄은 그 행을 빼고 센다, 단건 409면 일괄이 센다`() {
        val u = Db.createUser()
        val rows = SDB.restrictionNotifs(u, 3)
        val now = Flow.advance(HOUR)
        val target = rows[0].id
        val rs = SlowTrigger.on("notifications", "UPDATE", condition = "OLD.id = $target") {
            SC.concurrently(2) { i -> if (i == 0) SB.read(target, u) else SDB.readAll(u) }
        }
        val (single, bulk) = rs
        bulk.assertStatus(200)
        when (single.status) {
            200 -> assertEquals(2L, bulk.long("readCount"), "단건이 먼저: 일괄은 나머지 2건: $rs")
            409 -> {
                single.assertError(409, "ITEM_STATE_CONFLICT")
                assertEquals(3L, bulk.long("readCount"), "일괄이 먼저: 3건, 단건은 409: $rs")
            }
            else -> kotlin.test.fail("단건은 200 또는 409: $rs")
        }
        for (row in rows) assertEquals(now, SDB.notif(row.id).instant("read_at"))
    }
}
