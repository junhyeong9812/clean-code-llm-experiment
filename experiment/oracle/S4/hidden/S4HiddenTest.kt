package hidden

import support.Api
import support.Db
import support.Resp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * S4 숨은 테스트 — 관리자 분쟁 목록(`GET /admin/disputes`)만 limit 생략 시 20건. 단언은 HTTP 응답(상태·code·항목 id·nextAfter)만 본다.
 * 분쟁·감사 로그 행은 DB에 직접 넣고(픽스처) 자기 행은 after(넣기 전 최대 id)로 좁힌다.
 *  - A: limit 생략 → 20건·nextAfter = 20번째 id, 다음 페이지도 20건 기준(나머지 5건·null), status 필터를 줘도 20건.
 *  - B: limit을 주면 그 값 그대로(50·100·21·1).
 *  - C: 다른 관리자 목록(감사 로그)의 limit 생략은 그대로 50건.
 *  - D: 분쟁 목록의 limit 범위(1~100)·정수 검사는 그대로 — 0·101·abc는 400 INVALID_REQUEST, 100은 200.
 */
class S4HiddenTest {

    @Test
    fun `A - 분쟁 목록은 limit을 주지 않으면 20건씩, 다음 페이지도 같다`() {
        val before = maxId("disputes")
        val ids = disputes(25)

        val p1 = ok("/admin/disputes?after=$before")
        assertEquals(ids.take(20), ids(p1, "disputeId"), "limit 없는 첫 페이지")
        assertEquals(ids[19], nextAfter(p1), "받은 수 = 20 → 마지막 id")
        val p2 = ok("/admin/disputes?after=${ids[19]}")
        assertEquals(ids.drop(20), ids(p2, "disputeId"), "limit 없는 둘째 페이지")
        assertNull(nextAfter(p2), "받은 수 < 20 → null")
        val filtered = ok("/admin/disputes?status=REJECTED&after=$before")
        assertEquals(ids.take(20), ids(filtered, "disputeId"), "status 필터 + limit 없음")
    }

    @Test
    fun `B - 분쟁 목록에 limit을 주면 그 값 그대로`() {
        val before = maxId("disputes")
        val ids = disputes(105)

        val r50 = ok("/admin/disputes?after=$before&limit=50")
        assertEquals(ids.take(50), ids(r50, "disputeId"), "limit=50")
        assertEquals(ids[49], nextAfter(r50))
        val r100 = ok("/admin/disputes?after=$before&limit=100")
        assertEquals(ids.take(100), ids(r100, "disputeId"), "limit=100")
        assertEquals(ids[99], nextAfter(r100))
        val r21 = ok("/admin/disputes?after=$before&limit=21")
        assertEquals(ids.take(21), ids(r21, "disputeId"), "limit=21")
        val r1 = ok("/admin/disputes?after=$before&limit=1")
        assertEquals(ids.take(1), ids(r1, "disputeId"), "limit=1")
        assertEquals(ids[0], nextAfter(r1))
    }

    @Test
    fun `C - 감사 로그 목록은 limit을 주지 않으면 그대로 50건`() {
        val before = maxId("admin_audit_logs")
        val ids = (1..55).map {
            Db.long(
                "INSERT INTO admin_audit_logs(action, target_id, detail, created_at) " +
                    "VALUES ('INBOUND_SLOT_CREATE', ?, NULL, TIMESTAMPTZ '2026-01-01 00:00:00+09') RETURNING id",
                it.toLong(),
            )
        }
        val r = ok("/admin/audit-logs?after=$before")
        assertEquals(ids.take(50), ids(r, "auditId"), "감사 로그 limit 없음")
        assertEquals(ids[49], nextAfter(r), "받은 수 = 50 → 마지막 id")
    }

    @Test
    fun `D - 분쟁 목록의 limit 검사는 그대로 - 0·101·정수 아님 400, 100은 200`() {
        for (q in listOf("limit=0", "limit=101", "limit=abc", "limit=-1")) {
            val r = Api.get("/admin/disputes?$q")
            assertEquals(400, r.status, "$q: $r")
            assertEquals("INVALID_REQUEST", r.json["code"].asText(), "$q: $r")
        }
        assertEquals(200, Api.get("/admin/disputes?limit=100").status, "limit=100")
    }

    // ---- 도우미 ----

    private fun maxId(table: String): Long = Db.long("SELECT coalesce(max(id), 0) FROM $table")

    /** 판정이 끝난 분쟁 n행을 넣는다(픽스처 — 목록 조회만 본다). 반환: 넣은 id(오름차순). */
    private fun disputes(n: Int): List<Long> {
        val item = Db.long("SELECT min(id) FROM order_items")
        return (1..n).map {
            Db.long(
                "INSERT INTO disputes(order_item_id, status, opened_at, resolved_at) " +
                    "VALUES (?, 'REJECTED', TIMESTAMPTZ '2026-01-01 00:00:00+09', TIMESTAMPTZ '2026-01-02 00:00:00+09') RETURNING id",
                item,
            )
        }
    }

    private fun ok(path: String): Resp = Api.get(path).also { assertEquals(200, it.status, "$it") }

    private fun ids(r: Resp, field: String): List<Long> = r.json["items"].map { it[field].asLong() }

    private fun nextAfter(r: Resp): Long? = r.json["nextAfter"].takeUnless { it == null || it.isNull }?.asLong()
}
