package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.HOUR
import com.ccle.behavior.contract2.SEC
import com.ccle.behavior.contract2.days
import com.ccle.behavior.support.Contract3Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db3
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.Resp
import com.ccle.behavior.support.SlowTrigger
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-0007 §20.3.1 목표가 변경 `PATCH /me/price-alerts/{id}`(401 → 400 → 404 → 403 → 알림 FU → ACTIVE 아니면 409, target_price만,
 * 동시 변경 둘 다 200) · §20.3.2 재등록 `POST /me/price-alerts/{id}/renew`(401 → 404 → 403 → 사이즈 KS → 사용자 FU → 409 1·2·3,
 * 새 알림 ACTIVE·같은 target·expires_at = 현재 + 60일, 옛 알림 그대로).
 * EXPIRED·TRIGGERED는 실제 price-alert-scan으로 만든다. 알림 주인에게 입찰과 제한을 함께 두지 않는다(§20.12 C2).
 */
@Contract3Test
class A14PriceAlertRenewTest {

    // ---- §20.3.1 목표가 변경 ----

    @Test
    fun `§20_3_1 변경 - 200 가격 알림 모양, target_price만 바뀌고 expires_at·created_at·status 그대로, 같은 값도 200, 감사 없음`() {
        val u = Db.createUser()
        val size = Db.createProductSize()
        val id = SB.alert(u, size, 90_000)
        val before = SDA.alertRow(id)
        val audit = SDA.maxAuditId()
        Flow.advance(HOUR)

        val r = SDA.patchAlert(u, id, 80_000).assertStatus(200)
        val row = SDA.alertRow(id)
        SB.assertAlertBody(r.json, row)
        assertEquals(80_000L, row.long("target_price"))
        assertEquals(before.instant("expires_at"), row.instant("expires_at"), "유효 기간을 늘리지 않는다")
        assertEquals(before.instant("created_at"), row.instant("created_at"))
        assertEquals("ACTIVE", row.text("status"))
        assertNull(row.instantOrNull("closed_at"))
        assertNull(row.longOrNull("triggered_bid_id"))
        assertEquals(1, SDA.alertsOf(u, size).size, "행을 늘리지 않는다")

        SDA.patchAlert(u, id, 80_000).assertStatus(200)
        assertEquals(80_000L, SDA.alertRow(id).long("target_price"))
        SDA.patchAlert(u, id, 1).assertStatus(200)                                  // 양의 정수 최솟값
        assertEquals(1L, SDA.alertRow(id).long("target_price"))
        SDA.assertNoAuditSince(audit)
    }

    @Test
    fun `§20_3_1·§19의 1·2 입력 - targetPrice 누락·null·0·음수·소수·5_0·문자열 400, 400이 404보다 먼저, 행 무변경`() {
        val u = Db.createUser()
        val id = SB.alert(u, Db.createProductSize(), 90_000)
        val before = SDA.alertRow(id).toString()
        for (body in listOf(
            """{}""",
            """{"targetPrice":null}""",
            """{"targetPrice":0}""",
            """{"targetPrice":-1}""",
            """{"targetPrice":1.5}""",
            """{"targetPrice":5.0}""",
            """{"targetPrice":"100"}""",
            """{"targetPrice":"abc"}""",
            """not-json""",
        )) {
            SDA.patchAlertRaw(u, id, body).assertError(400, "INVALID_REQUEST")
        }
        SDA.patchAlert(u, Db.missingId("price_alerts"), 0).assertError(400, "INVALID_REQUEST")
        SDA.patchAlert(Db.createUser(), id, 0).assertError(400, "INVALID_REQUEST")    // 400이 403보다
        assertEquals(before, SDA.alertRow(id).toString())
    }

    @Test
    fun `§20_3_1 판정 순서 - 401 → 400 → 404 → 403 → 409, 남의 알림 403(취소된 것도 — 403이 409보다)`() {
        val owner = Db.createUser()
        val other = Db.createUser()
        val id = SB.alert(owner, Db.createProductSize(), 90_000)
        val missing = Db.missingId("price_alerts")

        SDA.patchAlert(null, missing, 0).assertError(401, "UNKNOWN_USER")
        SDA.patchAlert("abc", id, 80_000).assertError(401, "UNKNOWN_USER")
        SDA.patchAlert(Db.missingId("users"), id, 80_000).assertError(401, "UNKNOWN_USER")
        SDA.patchAlert(owner, missing, 80_000).assertError(404, "NOT_FOUND")
        SDA.patchAlert(other, id, 80_000).assertError(403, "NOT_OWNER")
        assertEquals(90_000L, SDA.alertRow(id).long("target_price"))

        SB.alertCancel(owner, id).assertStatus(200)
        SDA.patchAlert(other, id, 80_000).assertError(403, "NOT_OWNER")
        SDA.patchAlert(owner, id, 80_000).assertError(409, "ITEM_STATE_CONFLICT")
    }

    @Test
    fun `§20_3_1 ACTIVE가 아니면 409 ITEM_STATE_CONFLICT - CANCELLED·TRIGGERED·EXPIRED, 행 무변경`() {
        val u = Db.createUser()
        val cancelled = SB.alert(u, Db.createProductSize(), 90_000)
        SB.alertCancel(u, cancelled).assertStatus(200)
        val t = SDA.triggered(owner = u, wish = false)
        val expired = SDA.expiredAlerts(listOf(u to Db.createProductSize())).first.single()
        Flow.advance(HOUR)
        for (id in listOf(cancelled, t.alertId, expired)) {
            val before = SDA.alertRow(id).toString()
            SDA.patchAlert(u, id, 50_000).assertError(409, "ITEM_STATE_CONFLICT")
            assertEquals(before, SDA.alertRow(id).toString(), "409는 아무것도 바꾸지 않는다")
        }
    }

    @Test
    fun `§20_3_1 저장 상태만 본다 - expires_at ≤ 현재인데 scan 전이라 ACTIVE면 200(expires_at 그대로), 다음 scan은 EXPIRED`() {
        val u = Db.createUser()
        val now0 = Flow.now()
        val id = Db3.insert(
            "price_alerts",
            mapOf(
                "user_id" to u, "product_size_id" to Db.createProductSize(), "target_price" to 100_000L, "status" to "ACTIVE",
                "expires_at" to now0.plus(SEC), "triggered_bid_id" to null, "closed_at" to null, "created_at" to now0.minus(days(59)),
            ),
        )!!
        val now = Flow.advance(SEC)                                                 // expires_at = 현재
        SDA.patchAlert(u, id, 70_000).assertStatus(200)
        val row = SDA.alertRow(id)
        assertEquals(70_000L, row.long("target_price"))
        assertEquals(now0.plus(SEC), row.instant("expires_at"))
        assertEquals("ACTIVE", row.text("status"))

        SDA.scan()
        val after = SDA.alertRow(id)
        assertEquals("EXPIRED", after.text("status"))
        assertEquals(now, after.instant("closed_at"))
    }

    @Test
    fun `§20_3_1 다음 price-alert-scan은 새 목표가로 판정 - 올리면 그 SELL로 TRIGGERED, 내리면 ACTIVE 유지`() {
        val u = Db.createUser()
        val up = Db.createProductSize()
        val down = Db.createProductSize()
        val aUp = SB.alert(u, up, 90_000)
        val aDown = SB.alert(u, down, 100_000)
        val bidUp = Flow.activeBid(Db.createUser(), up, "SELL", 95_000)
        Flow.activeBid(Db.createUser(), down, "SELL", 95_000)
        SDA.patchAlert(u, aUp, 95_000).assertStatus(200)                          // price = target 정각 포함(§8.3의 3)
        SDA.patchAlert(u, aDown, 94_999).assertStatus(200)
        val now = Flow.advance(SEC)

        SDA.scan()
        val rUp = SDA.alertRow(aUp)
        assertEquals("TRIGGERED", rUp.text("status"))
        assertEquals(bidUp, rUp.longOrNull("triggered_bid_id"))
        assertEquals(now, rUp.instant("closed_at"))
        assertEquals("ACTIVE", SDA.alertRow(aDown).text("status"))
        // 발동 뒤에는 바꿀 수 없다(a5_alert_trigger_bid 유지)
        SDA.patchAlert(u, aUp, 1).assertError(409, "ITEM_STATE_CONFLICT")
        assertEquals(95_000L, SDA.alertRow(aUp).long("target_price"))
    }

    @Test
    fun `§20_3_1·§2_3 동시 변경 - 같은 알림에 값이 다른 두 변경이면 둘 다 200, 최종 target ∈ 두 값, 행 1개`() {
        val u = Db.createUser()
        val size = Db.createProductSize()
        val id = SB.alert(u, size, 90_000)
        val values = listOf(81_000L, 82_000L)
        val rs = SlowTrigger.on("price_alerts", "UPDATE", condition = "OLD.id = $id") {
            SA.concurrently(2) { i -> SDA.patchAlert(u, id, values[i]) }
        }
        rs.forEachIndexed { i, r ->
            r.assertStatus(200)
            assertEquals(values[i], r.long("targetPrice"), "각 응답은 자기 값: $r")
        }
        val rows = SDA.alertsOf(u, size)
        assertEquals(1, rows.size, "행 1개: $rows")
        assertTrue(rows.single().long("target_price") in values, "최종 값 ∈ 두 값: ${rows.single()}")
        assertEquals("ACTIVE", rows.single().text("status"))
    }

    @Test
    fun `§20_3_1·§2_3 변경과 취소가 겹치면 - 취소는 200, 변경은 먼저면 200·나중이면 409, 최종 CANCELLED`() {
        val u = Db.createUser()
        val id = SB.alert(u, Db.createProductSize(), 90_000)
        Flow.advance(HOUR)
        val rs = SlowTrigger.on("price_alerts", "UPDATE", condition = "OLD.id = $id") {
            SA.concurrently(2) { i -> if (i == 0) SB.alertCancel(u, id) else SDA.patchAlert(u, id, 70_000) }
        }
        val (cancel, patch) = rs
        cancel.assertStatus(200)
        val row = SDA.alertRow(id)
        assertEquals("CANCELLED", row.text("status"))
        if (patch.status == 200) assertEquals(70_000L, row.long("target_price"), "변경이 먼저였다")
        else {
            patch.assertError(409, "ITEM_STATE_CONFLICT")
            assertEquals(90_000L, row.long("target_price"))
        }
    }

    // ---- §20.3.2 재등록 ----

    @Test
    fun `§20_3_2 재등록 - EXPIRED·TRIGGERED에서 201 새 알림(ACTIVE·같은 target·expires_at = 현재 + 60일·created_at = 현재), 옛 알림 그대로, 감사 없음`() {
        val u = Db.createUser()
        val sizeE = Db.createProductSize()
        val (ids, _) = SDA.expiredAlerts(listOf(u to sizeE), target = 88_000)
        val expired = ids.single()
        val t = SDA.triggered(owner = u, target = 77_000, wish = false)
        val oldE = SDA.alertRow(expired).toString()
        val oldT = SDA.alertRow(t.alertId).toString()
        val audit = SDA.maxAuditId()
        val now = Flow.advance(HOUR)

        val r1 = SDA.renew(u, expired).assertStatus(201)
        val n1 = SDA.alertRow(r1.long("alertId"))
        SB.assertAlertBody(r1.json, n1)
        SDA.assertNewAlert(n1, u, sizeE, 88_000, now)
        assertTrue(n1.id > expired)

        val r2 = SDA.renew(u, t.alertId).assertStatus(201)
        val n2 = SDA.alertRow(r2.long("alertId"))
        SB.assertAlertBody(r2.json, n2)
        SDA.assertNewAlert(n2, u, t.size, 77_000, now)

        assertEquals(oldE, SDA.alertRow(expired).toString(), "옛 알림은 그대로")
        assertEquals(oldT, SDA.alertRow(t.alertId).toString(), "옛 알림은 그대로")
        assertEquals(2, SDA.alertsOf(u, sizeE).size)
        SDA.assertNoAuditSince(audit)
    }

    @Test
    fun `§20_3_2 재등록 - 구매 제한 중에도 201, 위시리스트가 없어도 201`() {
        val u = Db.createUser()                                                  // 입찰 없는 사용자(§20.12 C2)
        val size = Db.createProductSize()
        val (ids, _) = SDA.expiredAlerts(listOf(u to size))
        val now = Flow.advance(HOUR)
        Db.setRestrictions(u, now.plus(days(7)), null)
        assertTrue(SB.wishRows(u, size).isEmpty(), "준비: 위시리스트 없음")
        val r = SDA.renew(u, ids.single()).assertStatus(201)
        SDA.assertNewAlert(SDA.alertRow(r.long("alertId")), u, size, 100_000, now)
    }

    @Test
    fun `§20_3_2의 1 EXPIRED·TRIGGERED가 아니면 409 - ACTIVE·CANCELLED(취소는 되살리지 않는다), 새 행 없음`() {
        val u = Db.createUser()
        val sizeA = Db.createProductSize()
        val sizeC = Db.createProductSize()
        val active = SB.alert(u, sizeA, 90_000)
        val cancelled = SB.alert(u, sizeC, 90_000)
        SB.alertCancel(u, cancelled).assertStatus(200)
        Flow.advance(HOUR)
        SDA.renew(u, active).assertError(409, "ITEM_STATE_CONFLICT")
        SDA.renew(u, cancelled).assertError(409, "ITEM_STATE_CONFLICT")
        assertEquals(1, SDA.alertsOf(u, sizeA).size)
        assertEquals(1, SDA.alertsOf(u, sizeC).size)
    }

    @Test
    fun `§20_3_2의 2 그 쌍에 더 큰 id 알림(상태 무관)이 있으면 409 - 재등록한 옛 알림·새 등록 뒤·새 알림을 취소한 뒤, 마지막 알림만 된다`() {
        val u = Db.createUser()
        val size = Db.createProductSize()
        val (ids, _) = SDA.expiredAlerts(listOf(u to size))
        val old = ids.single()
        Flow.advance(HOUR)
        val newer = SDA.renew(u, old).assertStatus(201).long("alertId")
        SDA.renew(u, old).assertError(409, "ITEM_STATE_CONFLICT")              // 더 큰 id(ACTIVE)가 있다
        SB.alertCancel(u, newer).assertStatus(200)
        SDA.renew(u, old).assertError(409, "ITEM_STATE_CONFLICT")              // 더 큰 id는 CANCELLED여도 막는다
        SDA.renew(u, newer).assertError(409, "ITEM_STATE_CONFLICT")            // 마지막 알림이지만 CANCELLED(1)
        assertEquals(2, SDA.alertsOf(u, size).size, "새 행 없음")

        // 다른 사용자·다른 사이즈의 알림은 막지 않는다
        val other = Db.createUser()
        val (otherIds, _) = SDA.expiredAlerts(listOf(other to size, u to Db.createProductSize()))
        SB.alert(Db.createUser(), size, 50_000)
        SDA.renew(other, otherIds[0]).assertStatus(201)
        SDA.renew(u, otherIds[1]).assertStatus(201)
    }

    @Test
    fun `§20_3_2의 2 둘째 항 - 그 쌍에 ACTIVE 알림이 있으면 id가 작아도 409(부분 UNIQUE 위반 400이 아니다)`() {
        // 계약 안에서는 ACTIVE가 늘 더 큰 id다. 둘째 항만 따로 보려고 ACTIVE를 먼저 넣고 그보다 큰 id의 EXPIRED를 만든다(픽스처).
        val u = Db.createUser()
        val size = Db.createProductSize()
        val now0 = Flow.now()
        val active = Db3.insert(
            "price_alerts",
            mapOf(
                "user_id" to u, "product_size_id" to size, "target_price" to 90_000L, "status" to "ACTIVE",
                "expires_at" to now0.plus(days(60)), "triggered_bid_id" to null, "closed_at" to null, "created_at" to now0,
            ),
        )!!
        val expired = Db3.insert(
            "price_alerts",
            mapOf(
                "user_id" to u, "product_size_id" to size, "target_price" to 80_000L, "status" to "EXPIRED",
                "expires_at" to now0, "triggered_bid_id" to null, "closed_at" to now0, "created_at" to now0.minus(days(60)),
            ),
        )!!
        assertTrue(active < expired)
        Flow.advance(HOUR)
        SDA.renew(u, expired).assertError(409, "ITEM_STATE_CONFLICT")
        assertEquals(2, SDA.alertsOf(u, size).size)
    }

    @Test
    fun `§20_3_2의 3 기한 - closed_at + 7일 정각은 201, 1초 뒤는 409 DEADLINE_PASSED, 1·2 위반이 기한보다 먼저`() {
        val u = Db.createUser()
        val sEdge = Db.createProductSize()
        val sLate = Db.createProductSize()
        val sNewer = Db.createProductSize()
        val sCancel = Db.createProductSize()
        val cancelled = SB.alert(u, sCancel, 90_000)
        val (ids, closedAt) = SDA.expiredAlerts(listOf(u to sEdge, u to sLate, u to sNewer))
        val (edge, late, withNewer) = ids
        SB.alertCancel(u, cancelled).assertStatus(200)
        SB.alert(u, sNewer, 50_000)                                               // 더 큰 id의 ACTIVE

        val atEdge = Flow.moveTo(closedAt.plus(days(7)))
        val r = SDA.renew(u, edge).assertStatus(201)
        SDA.assertNewAlert(SDA.alertRow(r.long("alertId")), u, sEdge, 100_000, atEdge)

        Flow.moveTo(atEdge.plus(SEC))
        SDA.renew(u, late).assertError(409, "DEADLINE_PASSED")
        assertEquals(1, SDA.alertsOf(u, sLate).size)
        SDA.renew(u, withNewer).assertError(409, "ITEM_STATE_CONFLICT")         // 2가 3보다 먼저
        SDA.renew(u, cancelled).assertError(409, "ITEM_STATE_CONFLICT")         // 1이 3보다 먼저
    }

    @Test
    fun `§20_3_2 판정 순서 - 401 → 404 → 403 → 409, 남의 알림은 ACTIVE여도 403, 새 행 없음`() {
        val owner = Db.createUser()
        val other = Db.createUser()
        val size = Db.createProductSize()
        val (ids, _) = SDA.expiredAlerts(listOf(owner to size))
        val expired = ids.single()
        val active = SB.alert(owner, Db.createProductSize(), 90_000)
        val missing = Db.missingId("price_alerts")

        SDA.renew(null, missing).assertError(401, "UNKNOWN_USER")
        SDA.renew("abc", expired).assertError(401, "UNKNOWN_USER")
        SDA.renew(Db.missingId("users"), expired).assertError(401, "UNKNOWN_USER")
        SDA.renew(owner, missing).assertError(404, "NOT_FOUND")
        SDA.renew(other, expired).assertError(403, "NOT_OWNER")
        SDA.renew(other, active).assertError(403, "NOT_OWNER")                   // 403이 409보다
        assertEquals(1, SDA.alertsOf(owner, size).size)
        assertTrue(Db3.rows("price_alerts", "user_id = ?", other).isEmpty())
    }

    @Test
    fun `§20_3_2·§2_3 동시 재등록 - 같은 알림을 동시에 3번 재등록하면 201 1건·나머지 409(UNIQUE 위반 400 아님), ACTIVE 1건`() {
        val u = Db.createUser()
        val size = Db.createProductSize()
        val (ids, _) = SDA.expiredAlerts(listOf(u to size))
        Flow.advance(HOUR)
        val rs = SlowTrigger.on("price_alerts", "INSERT", condition = "NEW.user_id = $u") {
            SA.concurrently(3) { SDA.renew(u, ids.single()) }
        }
        SA.assertOneWins(rs, 201)
        assertEquals(2, SDA.alertsOf(u, size).size, "옛 알림 + 새 알림 1")
        assertEquals(1L, Db3.count("price_alerts", "user_id = ? AND product_size_id = ? AND status = 'ACTIVE'", u, size))
    }

    /**
     * §20.12·§20.8: 재등록이 사이즈 KS → 사용자 FU를 쥔 채 INSERT 직전에 멈춘 사이 같은 사용자·사이즈의 입찰 등록(사이즈 FU → bids INSERT의
     * 사용자 FK)이 오면, 입찰은 사이즈 행에서 기다린다 — "사이즈 → 사용자" 순서가 같아 교착 없이 둘 다 201.
     */
    @Test
    fun `§20_12·§20_8 재등록과 같은 사용자·사이즈의 입찰 등록이 겹쳐도 교착 없이 둘 다 201`() {
        val u = Db.createUser()
        val size = Db.createProductSize()
        val (ids, _) = SDA.expiredAlerts(listOf(u to size))
        Flow.advance(HOUR)
        val rs = SlowTrigger.on("price_alerts", "INSERT", condition = "NEW.user_id = $u") {
            val pool = Executors.newSingleThreadExecutor()
            try {
                val renew = pool.submit<Resp> { SDA.renew(u, ids.single()) }
                SDA.awaitTriggerWait()
                val bid = Flow.bid(u, size, "BUY", 10_000)                      // 반대편 SELL 없음 → ACTIVE
                listOf(renew.get(30, TimeUnit.SECONDS), bid)
            } finally {
                pool.shutdownNow()
            }
        }
        rs[0].assertStatus(201)
        rs[1].assertStatus(201)
        assertEquals("ACTIVE", rs[1].text("status"))
        assertEquals(1L, Db3.count("price_alerts", "user_id = ? AND product_size_id = ? AND status = 'ACTIVE'", u, size))
    }
}
