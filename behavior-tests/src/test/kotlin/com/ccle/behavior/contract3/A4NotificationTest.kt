package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.DAY
import com.ccle.behavior.contract2.HOUR
import com.ccle.behavior.contract2.S2
import com.ccle.behavior.contract2.days
import com.ccle.behavior.support.Contract3Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db3
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.Row3
import com.ccle.behavior.support.SlowTrigger
import com.ccle.behavior.support.instantAt
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-0007 §7.2 알림 조회·읽음: `GET /me/notifications[?unread=true]`(notificationId 오름차순, unread는 true·false만),
 * `POST /me/notifications/{id}/read`(본인 것만, 이미 읽었으면 409 — 멱등 아님, read_at = 현재, 200 알림 모양),
 * 판정 순서 401 → 400 → 404 → 403 → 409(ADR-0003 §2), 같은 행 동시 요청은 하나만 성공(§2.3).
 *
 * 알림은 API(배치)로 만든다: 구매·판매 제한 사용자(입찰 없음 — §18 C2) + deadline-reminder → 알림 2행(구매 제한 먼저).
 */
@Contract3Test
class A4NotificationTest {

    /** 구매·판매 제한 사용자와 그 알림 2행(id 순: PURCHASE_RESTRICTED, SALE_RESTRICTED). */
    private fun userWithTwo(): Pair<Long, List<Row3>> {
        val now = Flow.now()
        val u = Db.createUser()
        Db.setRestrictions(u, now.plus(days(3)), now.plus(days(4)))
        S2.runBatch("deadline-reminder")
        val rows = Db3.notificationsOf(u)
        assertEquals(listOf("PURCHASE_RESTRICTED", "SALE_RESTRICTED"), rows.map { it.text("kind") }, "준비(A4) 알림 2행: $rows")
        return u to rows
    }

    private fun assertList(user: Long, unread: String?, expected: List<Row3>) {
        val r = SB.notifications(user, unread).assertStatus(200)
        assertTrue(r.json.isArray, "배열: $r")
        assertEquals(expected.size, r.json.size(), "알림 수: $r")
        expected.forEachIndexed { i, row -> SB.assertNotifBody(r.json[i], row) }
    }

    @Test
    fun `§7_2 조회 - 본인 알림 전부 notificationId 오름차순, unread=true·false, 알림 없는 사용자는 빈 배열`() {
        val (u, rows) = userWithTwo()
        assertTrue(rows[0].id < rows[1].id)
        assertList(u, null, rows)
        assertList(u, "true", rows)
        assertList(u, "false", rows)
        assertList(Db.createUser(), null, emptyList())
    }

    @Test
    fun `§7_2 읽음 - 200 알림 모양·read_at = 현재, 이후 unread=true에서 빠지고 전체 목록에는 readAt과 함께 남는다`() {
        val (u, rows) = userWithTwo()
        val now = Flow.advance(HOUR)
        val r = SB.read(rows[0].id, u).assertStatus(200)
        val after = Db3.row("notifications", rows[0].id)
        assertEquals(now, after.instant("read_at"), "read_at = 현재")
        SB.assertNotifBody(r.json, after)
        assertEquals(now, r.json.instantAt("readAt"))
        assertNull(Db3.row("notifications", rows[1].id).instantOrNull("read_at"), "다른 알림은 그대로")

        assertList(u, "true", listOf(Db3.row("notifications", rows[1].id)))
        assertList(u, null, listOf(after, Db3.row("notifications", rows[1].id)))
    }

    @Test
    fun `§7_2 읽음 - 이미 읽은 알림은 409 ITEM_STATE_CONFLICT(멱등 아님), read_at 그대로`() {
        val (u, rows) = userWithTwo()
        val first = Flow.advance(HOUR)
        SB.read(rows[0].id, u).assertStatus(200)
        Flow.advance(HOUR)
        SB.read(rows[0].id, u).assertError(409, "ITEM_STATE_CONFLICT")
        assertEquals(first, Db3.row("notifications", rows[0].id).instant("read_at"))
    }

    @Test
    fun `§7_2 읽음 오류 - 없는 알림 404, 남의 알림 403(읽음 표시 안 됨), 남의 이미 읽은 알림도 403(403이 409보다 먼저), 헤더 없음 401`() {
        val (owner, rows) = userWithTwo()
        val other = Db.createUser()
        SB.read(Db.missingId("notifications"), owner).assertError(404, "NOT_FOUND")
        SB.read(rows[0].id, other).assertError(403, "NOT_OWNER")
        assertNull(Db3.row("notifications", rows[0].id).instantOrNull("read_at"), "남의 요청은 바꾸지 않는다")

        SB.read(rows[1].id, owner).assertStatus(200)
        SB.read(rows[1].id, other).assertError(403, "NOT_OWNER")
        SB.read(rows[0].id, null).assertError(401, "UNKNOWN_USER")
        SB.read(rows[0].id, Db.missingId("users")).assertError(401, "UNKNOWN_USER")
    }

    @Test
    fun `§7_2 조회 오류 - unread가 true·false가 아니면 400(대소문자 다름·빈 값 포함 §19의 3), 헤더 없음·없는 사용자 401(401이 400보다 먼저)`() {
        val u = Db.createUser()
        SB.notifications(u, "yes").assertError(400, "INVALID_REQUEST")
        SB.notifications(u, "1").assertError(400, "INVALID_REQUEST")
        SB.notifications(u, "TRUE").assertError(400, "INVALID_REQUEST")
        SB.notifications(u, "").assertError(400, "INVALID_REQUEST")
        SB.notifications(null).assertError(401, "UNKNOWN_USER")
        SB.notifications(Db.missingId("users")).assertError(401, "UNKNOWN_USER")
        SB.notifications(null, "yes").assertError(401, "UNKNOWN_USER")
    }

    @Test
    fun `§2_3 동시성 - 같은 알림 읽음 두 요청은 하나만 200, 다른 하나는 409, read_at 1회`() {
        val (u, rows) = userWithTwo()
        val now = Flow.advance(DAY)
        // 먼저 잠근 요청이 쓰기 직전 0.5초 멈춰 다른 요청이 그 행 잠금을 기다리게 한다(경합 보장)
        val rs = SlowTrigger.on("notifications", "UPDATE", condition = "OLD.id = ${rows[0].id}") {
            SB.concurrently(2) { SB.read(rows[0].id, u) }
        }
        assertEquals(1, rs.count { it.status == 200 }, "200 하나: $rs")
        rs.filter { it.status != 200 }.forEach { it.assertError(409, "ITEM_STATE_CONFLICT") }
        assertEquals(now, Db3.row("notifications", rows[0].id).instant("read_at"))
    }
}
