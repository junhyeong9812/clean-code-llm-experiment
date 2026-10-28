package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.HOUR
import com.ccle.behavior.contract2.days
import com.ccle.behavior.support.Contract3Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db3
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.SlowTrigger
import com.ccle.behavior.support.longAt
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-0007 §8.2 가격 알림: 등록(ACTIVE, expires_at = 현재 + 60일, 사이즈당 ACTIVE 1건 — 사용자 행 잠금으로 동시 중복도 409, 구매 제한 중에도 가능),
 * 취소(본인 것만, ACTIVE만 → CANCELLED·closed_at = 현재), 조회(alertId 오름차순). 판정 순서 401 → 400 → 404 → 403 → 409.
 */
@Contract3Test
class A5PriceAlertTest {

    @Test
    fun `§8_2 등록 - 201 가격 알림 모양, ACTIVE·expires_at = 현재 + 60일·triggeredBidId·closedAt null·createdAt = 현재`() {
        val u = Db.createUser()
        val size = Db.createProductSize()
        val now = Flow.now()
        val r = SB.alertAdd(u, size, 95_000).assertStatus(201)
        val row = Db3.row("price_alerts", r.long("alertId"))
        SB.assertAlertBody(r.json, row)
        assertEquals(u, row.long("user_id"))
        assertEquals(size, row.long("product_size_id"))
        assertEquals(95_000L, row.long("target_price"))
        assertEquals("ACTIVE", row.text("status"))
        assertEquals(now.plus(days(60)), row.instant("expires_at"))
        assertEquals(now, row.instant("created_at"))
        assertNull(row.longOrNull("triggered_bid_id"))
        assertNull(row.instantOrNull("closed_at"))
    }

    @Test
    fun `§8_2 등록 - 같은 사이즈 ACTIVE가 있으면 409, 다른 사이즈·다른 사용자는 201, 취소 뒤에는 같은 사이즈 다시 201`() {
        val u = Db.createUser()
        val size = Db.createProductSize()
        val first = SB.alert(u, size, 90_000)
        SB.alertAdd(u, size, 80_000).assertError(409, "ITEM_STATE_CONFLICT")
        assertEquals(1, Db3.count("price_alerts", "user_id = ? AND product_size_id = ?", u, size))
        SB.alertAdd(u, Db.createProductSize(), 80_000).assertStatus(201)
        SB.alertAdd(Db.createUser(), size, 80_000).assertStatus(201)

        SB.alertCancel(u, first).assertStatus(200)
        val again = SB.alertAdd(u, size, 85_000).assertStatus(201)
        assertEquals("ACTIVE", Db3.row("price_alerts", again.long("alertId")).text("status"))
    }

    @Test
    fun `§8_2 등록 - 구매 제한 중에도 201(§2_2 새 엔드포인트는 PURCHASE_RESTRICTED를 내지 않는다)`() {
        val now = Flow.now()
        val u = Db.createUser(purchaseRestrictedUntil = now.plus(days(10)))
        SB.alertAdd(u, Db.createProductSize(), 100_000).assertStatus(201)
    }

    @Test
    fun `§8_2 등록 오류 - targetPrice 0·음수·소수·문자열·누락 400, productSizeId 누락 400, 없는 사이즈 404, 400이 404보다 먼저, 헤더 없음 401, 행 없음`() {
        val u = Db.createUser()
        val size = Db.createProductSize()
        SB.alertAdd(u, size, 0).assertError(400, "INVALID_REQUEST")
        SB.alertAdd(u, size, -1).assertError(400, "INVALID_REQUEST")
        SB.alertAdd(u, size, 1.5).assertError(400, "INVALID_REQUEST")
        SB.alertAdd(u, size, "abc").assertError(400, "INVALID_REQUEST")
        SB.alertAdd(u, size, null).assertError(400, "INVALID_REQUEST")
        SB.alertAdd(u, null, 100_000).assertError(400, "INVALID_REQUEST")
        SB.alertAdd(u, Db.missingId("product_sizes"), 100_000).assertError(404, "NOT_FOUND")
        SB.alertAdd(u, Db.missingId("product_sizes"), 0).assertError(400, "INVALID_REQUEST")
        SB.alertAdd(null, size, 100_000).assertError(401, "UNKNOWN_USER")
        SB.alertAdd(Db.missingId("users"), size, 100_000).assertError(401, "UNKNOWN_USER")
        assertEquals(0, Db3.count("price_alerts", "user_id = ?", u))
        SB.alertAdd(u, size, 1).assertStatus(201)                       // 양의 정수 최솟값
    }

    @Test
    fun `§8_2 취소 - 200 CANCELLED·closedAt = 현재, 다시 취소하면 409(closed_at 그대로)`() {
        val u = Db.createUser()
        val id = SB.alert(u, Db.createProductSize(), 90_000)
        val now = Flow.advance(HOUR)
        val r = SB.alertCancel(u, id).assertStatus(200)
        val row = Db3.row("price_alerts", id)
        assertEquals("CANCELLED", row.text("status"))
        assertEquals(now, row.instant("closed_at"))
        assertNull(row.longOrNull("triggered_bid_id"))
        SB.assertAlertBody(r.json, row)

        Flow.advance(HOUR)
        SB.alertCancel(u, id).assertError(409, "ITEM_STATE_CONFLICT")
        assertEquals(now, Db3.row("price_alerts", id).instant("closed_at"))
    }

    @Test
    fun `§8_2 취소 오류 - 없는 알림 404, 남의 알림 403(ACTIVE 유지), 남의 취소된 알림도 403(403이 409보다 먼저), 헤더 없음 401`() {
        val owner = Db.createUser()
        val other = Db.createUser()
        val id = SB.alert(owner, Db.createProductSize(), 90_000)
        SB.alertCancel(owner, Db.missingId("price_alerts")).assertError(404, "NOT_FOUND")
        SB.alertCancel(other, id).assertError(403, "NOT_OWNER")
        assertEquals("ACTIVE", Db3.row("price_alerts", id).text("status"))
        SB.alertCancel(null, id).assertError(401, "UNKNOWN_USER")

        SB.alertCancel(owner, id).assertStatus(200)
        SB.alertCancel(other, id).assertError(403, "NOT_OWNER")
    }

    @Test
    fun `§8_2 조회 - 본인 가격 알림 전부(상태 무관) alertId 오름차순, 없으면 빈 배열`() {
        val u = Db.createUser()
        val a1 = SB.alert(u, Db.createProductSize(), 90_000)
        val a2 = SB.alert(u, Db.createProductSize(), 80_000)
        SB.alert(Db.createUser(), Db.createProductSize(), 70_000)
        SB.alertCancel(u, a1).assertStatus(200)

        val r = SB.alerts(u).assertStatus(200)
        assertEquals(listOf(a1, a2), r.json.map { it.longAt("alertId") }, "본인 것만, alertId 오름차순: $r")
        SB.assertAlertBody(r.json[0], Db3.row("price_alerts", a1))
        SB.assertAlertBody(r.json[1], Db3.row("price_alerts", a2))

        val empty = SB.alerts(Db.createUser()).assertStatus(200)
        assertTrue(empty.json.isArray && empty.json.size() == 0, "빈 배열: $empty")
        SB.alerts(null).assertError(401, "UNKNOWN_USER")
    }

    @Test
    fun `§2_3 동시성 - 같은 사이즈 동시 등록은 하나만 201, 나머지 409(UNIQUE 위반 400 아님), ACTIVE 1건`() {
        val u = Db.createUser()
        val size = Db.createProductSize()
        val rs = SlowTrigger.on("price_alerts", "INSERT", condition = "NEW.user_id = $u") {
            SB.concurrently(4) { i -> SB.alertAdd(u, size, 90_000L + i) }
        }
        assertEquals(1, rs.count { it.status == 201 }, "201 하나: $rs")
        rs.filter { it.status != 201 }.forEach { it.assertError(409, "ITEM_STATE_CONFLICT") }
        assertEquals(1, Db3.count("price_alerts", "user_id = ? AND product_size_id = ? AND status = 'ACTIVE'", u, size))
    }
}
