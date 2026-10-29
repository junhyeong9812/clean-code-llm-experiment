package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.HOUR
import com.ccle.behavior.contract2.S2
import com.ccle.behavior.contract2.SEC
import com.ccle.behavior.contract2.days
import com.ccle.behavior.support.Contract3Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db3
import com.ccle.behavior.support.FailTrigger
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.Resp
import com.ccle.behavior.support.SlowTrigger
import com.ccle.behavior.support.failed
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-0007 §20.3.3 배치 `price-alert-rearm`: TRIGGERED · closed_at ≥ 현재 − 7일 · 발동 입찰의 저장 상태가 CANCELLED·EXPIRED ·
 * 위시리스트 있음 · 그 쌍에 더 큰 id·ACTIVE 알림 없음 · 구매 제한 아님인 알림마다 새 ACTIVE 알림 1행(같은 target, expires_at = 현재 + 60일).
 * 알림(notifications)·입찰·옛 알림은 쓰지 않는다, 재실행 0, 건별 격리(§2.6).
 * 배치는 다른 테스트 행도 처리하므로 첫 실행 processed는 실행 직전 같은 대상 조건으로 센 수와 맞추고(그 밖은 하한 ≥), 자기 행을 단언한다. 같은 시각 재실행은 processed = 0(§20.1).
 * 발동 입찰은 DELETE /bids 또는 bid-expiry로 끝낸다 — 판매 제한으로 만들지 않는다(§20.12 C2). 구매 제한은 픽스처로 두고 그 사용자는 입찰이 없다.
 */
@Contract3Test
class A14PriceAlertRearmTest {

    @Test
    fun `§20_3_3 발동 입찰 CANCELLED - 새 알림 1행(ACTIVE·같은 target·expires_at = 현재 + 60일·created_at = 현재), 옛 알림·입찰 무변경, 알림 행 없음, 재실행 0`() {
        val t = SDA.triggered(target = 90_000)
        SDA.cancelBid(t)
        val oldAlert = SDA.alertRow(t.alertId).toString()
        val bidsBefore = Db.bidsOfSize(t.size)
        val notifBefore = Db3.notificationsOf(t.owner).size
        val audit = SDA.maxAuditId()
        val now = Flow.advance(HOUR)
        val expected = SDA.rearmTargetCount(now)                              // 실행 직전 같은 대상 조건(§20.3.3)으로 센 수
        assertTrue(expected >= 1, "준비: 이 알림이 대상: $expected")

        val r = SDA.rearm()
        assertEquals(expected, r.long("processed"), "processed = 만든 새 알림 수 = 실행 직전 대상 수: $r")
        assertEquals(0L, r.failed(), "실패 없음: $r")
        val n = SDA.assertRearmed(t, now)
        assertEquals(oldAlert, SDA.alertRow(t.alertId).toString(), "옛 알림은 그대로")
        assertEquals(bidsBefore, Db.bidsOfSize(t.size), "입찰은 읽기만 한다")
        assertEquals(notifBefore, Db3.notificationsOf(t.owner).size, "재무장은 알림 행을 만들지 않는다")
        SDA.assertNoAuditSince(audit)
        SB.assertAlertBody(SB.alerts(t.owner).assertStatus(200).json.last(), n)

        val again = SDA.rearm()
        assertEquals(0L, again.long("processed"), "같은 시각 재실행은 0(§20.1): $again")
        SDA.assertRearmed(t, now)
        Flow.advance(HOUR)
        SDA.rearm()
        SDA.assertRearmed(t, now)                                              // n.id > t.id가 옛 알림을 대상에서 뺀다

        // 재등록은 그 쌍의 마지막 알림에서만(§20.3.2의 2) — 옛 알림 재등록은 409
        SDA.renew(t.owner, t.alertId).assertError(409, "ITEM_STATE_CONFLICT")
    }

    @Test
    fun `§20_3_3 재무장한 알림은 다음 price-alert-scan이 다른 SELL로 다시 발동한다(PRICE_ALERT 새 키)`() {
        val t = SDA.triggered(target = 90_000)
        SDA.cancelBid(t)
        val at = Flow.advance(HOUR)
        SDA.rearm()
        val n = SDA.assertRearmed(t, at)
        val bid2 = Flow.activeBid(Db.createUser(), t.size, "SELL", 89_000)
        val now = Flow.advance(SEC)
        SDA.scan()
        val row = SDA.alertRow(n.id)
        assertEquals("TRIGGERED", row.text("status"))
        assertEquals(bid2, row.longOrNull("triggered_bid_id"))
        assertEquals(now, row.instant("closed_at"))
        SB.assertNotif("PRICE_ALERT:${n.id}", t.owner, "PRICE_ALERT", n.id, now)
        assertEquals(1, SB.notifCount("PRICE_ALERT:${t.alertId}"), "옛 알림의 알림은 1건 그대로")
    }

    @Test
    fun `§20_3_3 발동 입찰 EXPIRED - expires_at이 지나도 bid-expiry 전(ACTIVE)이면 대상 아님, bid-expiry 뒤 실행에서 재무장`() {
        val owner = Db.createUser()
        val size = Db.createProductSize()
        val seller = Db.createUser()
        val bid = Flow.activeBid(seller, size, "SELL", 90_000, validDays = 30)
        val bidExpires = Db.bid(bid).expiresAt
        Flow.moveTo(bidExpires.minus(days(1)))
        SB.wishAdd(owner, size).assertStatus(201)
        val alertId = SB.alert(owner, size, 90_000)
        val closedAt = Flow.advance(SEC)
        SDA.scan()
        assertEquals(bid, SDA.alertRow(alertId).longOrNull("triggered_bid_id"), "준비: 그 입찰로 발동")
        val t = SDA.Triggered(alertId, owner, size, bid, seller, 90_000, closedAt)

        Flow.moveTo(bidExpires)
        assertEquals("ACTIVE", Db.bid(bid).status, "준비: bid-expiry 전")
        SDA.rearm()
        SDA.assertNotRearmed(t, "저장된 bids.status가 ACTIVE")

        S2.runBatch("bid-expiry")
        assertEquals("EXPIRED", Db.bid(bid).status, "준비: bid-expiry")
        val now = Flow.advance(SEC)
        SDA.rearm()
        SDA.assertRearmed(t, now)
    }

    @Test
    fun `§20_3_3 발동 입찰이 ACTIVE·MATCHED면 대상 아님(MATCHED 제외 — 결제 기한 만료로 되살아날 수 있다)`() {
        val active = SDA.triggered()
        val matched = SDA.triggered()
        val buyer = Db.createUser()
        val r = Flow.bid(buyer, matched.size, "BUY", matched.target).assertStatus(201)
        assertEquals("MATCHED", r.text("status"), "준비: 발동 입찰이 체결됐다 $r")
        assertEquals("MATCHED", Db.bid(matched.bidId).status)
        Flow.advance(HOUR)

        SDA.rearm()
        SDA.assertNotRearmed(active, "발동 입찰 ACTIVE")
        SDA.assertNotRearmed(matched, "발동 입찰 MATCHED")
        assertEquals("ACTIVE", Db.bid(active.bidId).status)
    }

    @Test
    fun `§20_3_3 위시리스트가 없으면 대상 아님 - 처음부터 없음·삭제됨, 다시 등록하면 그 뒤 실행에서 재무장`() {
        val never = SDA.triggered(wish = false)
        val removed = SDA.triggered()
        SDA.cancelBid(never)
        SDA.cancelBid(removed)
        SB.wishDel(removed.owner, removed.size).assertStatus(200)
        Flow.advance(HOUR)
        SDA.rearm()
        SDA.assertNotRearmed(never, "위시리스트 없음")
        SDA.assertNotRearmed(removed, "위시리스트 삭제됨")

        SB.wishAdd(removed.owner, removed.size).assertStatus(201)
        val now = Flow.advance(SEC)
        SDA.rearm()
        SDA.assertRearmed(removed, now)
        SDA.assertNotRearmed(never, "위시리스트 없음")
    }

    @Test
    fun `§20_3_3 그 쌍에 더 큰 id 알림이 있으면 대상 아님 - 새 등록(ACTIVE)·새 등록 뒤 취소(CANCELLED)·재등록`() {
        val registered = SDA.triggered()
        val cancelledNewer = SDA.triggered()
        val renewed = SDA.triggered()
        for (t in listOf(registered, cancelledNewer, renewed)) SDA.cancelBid(t)
        SB.alert(registered.owner, registered.size, 50_000)
        SB.alertCancel(cancelledNewer.owner, SB.alert(cancelledNewer.owner, cancelledNewer.size, 50_000)).assertStatus(200)
        SDA.renew(renewed.owner, renewed.alertId).assertStatus(201)
        val counts = listOf(registered, cancelledNewer, renewed).map { SDA.alertsOf(it.owner, it.size).size }
        Flow.advance(HOUR)

        SDA.rearm()
        assertEquals(counts, listOf(registered, cancelledNewer, renewed).map { SDA.alertsOf(it.owner, it.size).size }, "새 행 없음")
    }

    @Test
    fun `§20_3_3 구매 제한 중(끝 ＞ 현재)이면 건너뛰고 제한 끝 = 현재가 되면 재무장, 제한은 읽기만`() {
        val t = SDA.triggered()                                                // 주인은 입찰이 없다(§20.12)
        SDA.cancelBid(t)
        val now = Flow.advance(HOUR)
        Db.setRestrictions(t.owner, now.plus(SEC), null)
        SDA.rearm()
        SDA.assertNotRearmed(t, "구매 제한 중")
        assertEquals(now.plus(SEC), Db.user(t.owner).purchaseRestrictedUntil, "제한은 읽기만 한다")

        val later = Flow.advance(SEC)                                          // 끝 = 현재 → 제한 아님
        SDA.rearm()
        SDA.assertRearmed(t, later)
    }

    @Test
    fun `§20_3_3 종결 7일 창 - closed_at = 현재 − 7일(정각)은 대상, 1초 더 지난 것은 대상 아님`() {
        val out = SDA.triggered()                                              // closed_at = T
        val inEdge = SDA.triggered()                                           // closed_at = T + 1초 이상
        assertTrue(inEdge.closedAt > out.closedAt)
        val gap = java.time.Duration.between(out.closedAt, inEdge.closedAt)
        SDA.cancelBid(out)
        SDA.cancelBid(inEdge)

        val at = Flow.moveTo(inEdge.closedAt.plus(days(7)))
        assertTrue(java.time.Duration.between(out.closedAt, at) >= days(7).plus(SEC), "준비: out은 7일 + $gap 전")
        SDA.rearm()
        SDA.assertRearmed(inEdge, at)
        SDA.assertNotRearmed(out, "종결 7일 창 밖")
    }

    @Test
    fun `§2_6 격리 - A의 새 알림 INSERT가 실패해도 B는 재무장, failed ≥ 1, 다시 돌리면 A 재무장`() {
        val a = SDA.triggered()
        val b = SDA.triggered()
        assertTrue(a.alertId < b.alertId)
        SDA.cancelBid(a)
        SDA.cancelBid(b)
        val now = Flow.advance(HOUR)

        FailTrigger.on("price_alerts", "INSERT", "NEW.user_id = ${a.owner}") {
            val r = SDA.rearm()
            assertTrue(r.failed() >= 1, "A 실패를 failed에 센다: $r")
            assertTrue(r.long("processed") >= 1, "B는 커밋된다: $r")
        }
        SDA.assertNotRearmed(a, "실패한 대상")
        SDA.assertRearmed(b, now)

        val later = Flow.advance(SEC)
        SDA.rearm()
        SDA.assertRearmed(a, later)
        SDA.assertRearmed(b, now)
    }

    /**
     * §20.12·§20.8: 재무장이 사이즈 KS → 사용자 FU를 쥔 채 새 알림 INSERT 직전에 멈춘 사이 같은 사용자·사이즈의 입찰 등록이 오면,
     * 입찰은 사이즈 행에서 기다린다 — 교착 없이 배치 200(그 알림 재무장)·입찰 201.
     */
    @Test
    fun `§20_12·§20_8 재무장과 같은 사용자·사이즈의 입찰 등록이 겹쳐도 교착 없이 재무장·입찰 201`() {
        val t = SDA.triggered()
        SDA.cancelBid(t)
        val now = Flow.advance(HOUR)
        val rs = SlowTrigger.on("price_alerts", "INSERT", condition = "NEW.user_id = ${t.owner}") {
            val pool = Executors.newSingleThreadExecutor()
            try {
                val batch = pool.submit<Resp> { Flow.batch("price-alert-rearm") }
                SDA.awaitTriggerWait()
                val bid = Flow.bid(t.owner, t.size, "BUY", 10_000)             // 반대편 ACTIVE SELL 없음 → ACTIVE
                listOf(batch.get(60, TimeUnit.SECONDS), bid)
            } finally {
                pool.shutdownNow()
            }
        }
        rs[0].assertStatus(200)
        rs[1].assertStatus(201)
        assertEquals("ACTIVE", rs[1].text("status"))
        SDA.assertRearmed(t, now)
    }

    /**
     * §20.12·§19의 10-2 역방향 인터리빙(SG-2): 입찰 등록이 사이즈 FU를 쥔 채 bids INSERT 직전(FK 검사 전)에 먼저 멈춘 사이 재무장이 오면,
     * 정답 순서(사이즈 KS → 사용자 FU)의 재무장은 사이즈 행에서 기다렸다가 입찰 커밋 뒤 진행한다 — 배치 200(failed 0, 그 알림 재무장)·입찰 201.
     * 역순(사용자 FU → 사이즈 KS)이면 입찰의 FK 검사(사용자 KS)와 교착한다.
     */
    @Test
    fun `§20_12·§19의 10-2 입찰 등록이 먼저 사이즈를 잡고 멈춘 사이 재무장이 와도 교착 없이 재무장·입찰 201(역방향 인터리빙)`() {
        val t = SDA.triggered()
        SDA.cancelBid(t)
        val now = Flow.advance(HOUR)
        val rs = SlowTrigger.on("bids", "INSERT", condition = "NEW.user_id = ${t.owner}") {
            val pool = Executors.newSingleThreadExecutor()
            try {
                val bid = pool.submit<Resp> { Flow.bid(t.owner, t.size, "BUY", 10_000) }   // 반대편 ACTIVE SELL 없음 → ACTIVE
                SDA.awaitTriggerWait()
                val batch = Flow.batch("price-alert-rearm")
                listOf(bid.get(60, TimeUnit.SECONDS), batch)
            } finally {
                pool.shutdownNow()
            }
        }
        rs[0].assertStatus(201)
        assertEquals("ACTIVE", rs[0].text("status"))
        rs[1].assertStatus(200)
        assertEquals(0L, rs[1].failed(), "교착 없음(실패 0): ${rs[1]}")
        SDA.assertRearmed(t, now)
    }

    /**
     * §20.3.3 잠근 뒤 재확인(SG-3): 위시리스트 삭제가 사용자 FU를 쥔 채 DELETE 직전에 멈춘 사이 재무장이 오면, 대상 조회는 아직 위시를 보지만
     * 재무장은 사용자 FU에서 기다린다. 삭제가 커밋된 뒤의 재확인은 위시가 없음을 보고 건너뛴다 — 삭제 200, 새 ACTIVE 알림 없음.
     */
    @Test
    fun `§20_3_3 잠근 뒤 재확인 - 위시리스트 삭제가 사용자 행을 쥐고 멈춘 사이 재무장이 오면 삭제 200·그 알림은 재무장하지 않는다`() {
        val t = SDA.triggered()
        SDA.cancelBid(t)
        Flow.advance(HOUR)
        val rs = SlowTrigger.on("wishlists", "DELETE", condition = "OLD.user_id = ${t.owner}") {
            val pool = Executors.newSingleThreadExecutor()
            try {
                val del = pool.submit<Resp> { SB.wishDel(t.owner, t.size) }
                SDA.awaitTriggerWait()
                val batch = Flow.batch("price-alert-rearm")
                listOf(del.get(60, TimeUnit.SECONDS), batch)
            } finally {
                pool.shutdownNow()
            }
        }
        rs[0].assertStatus(200)
        rs[1].assertStatus(200)
        assertEquals(0L, Db3.count("wishlists", "user_id = ? AND product_size_id = ?", t.owner, t.size), "위시리스트 삭제됨")
        SDA.assertNotRearmed(t, "잠근 뒤 재확인에서 위시리스트 없음")
        assertEquals(0L, Db3.count("price_alerts", "user_id = ? AND product_size_id = ? AND status = 'ACTIVE'", t.owner, t.size), "새 ACTIVE 없음")
    }
}
