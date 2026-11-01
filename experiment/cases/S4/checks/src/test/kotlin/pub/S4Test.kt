package pub

import support.Api
import support.Db
import kotlin.test.Test
import kotlin.test.assertEquals

class S4Test {

    @Test
    fun `관리자 분쟁 목록은 limit을 주지 않으면 20건씩 돌려준다`() {
        val before = Db.long("SELECT coalesce(max(id), 0) FROM disputes")
        val item = Db.long("SELECT min(id) FROM order_items")
        val ids = (1..25).map {
            Db.long(
                "INSERT INTO disputes(order_item_id, status, opened_at, resolved_at) " +
                    "VALUES (?, 'REJECTED', TIMESTAMPTZ '2026-01-01 00:00:00+09', TIMESTAMPTZ '2026-01-02 00:00:00+09') RETURNING id",
                item,
            )
        }

        val r = Api.get("/admin/disputes?after=$before")
        assertEquals(200, r.status, "$r")
        val got = r.json["items"].map { it["disputeId"].asLong() }
        assertEquals(20, got.size, "limit 없이 받은 분쟁 수")
        assertEquals(ids.take(20), got)
        assertEquals(ids[19], r.json["nextAfter"].asLong(), "받은 수가 20이면 nextAfter = 마지막 id")
    }
}
