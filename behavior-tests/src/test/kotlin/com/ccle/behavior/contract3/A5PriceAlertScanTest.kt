package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.DAY
import com.ccle.behavior.contract2.S2
import com.ccle.behavior.contract2.SEC
import com.ccle.behavior.contract2.days
import com.ccle.behavior.support.Contract3Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db3
import com.ccle.behavior.support.FailTrigger
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.failed
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-0007 §8.3 배치 `price-alert-scan`: ACTIVE 가격 알림마다 1(만료 → EXPIRED) → 2(구매 제한 중이면 그대로 ACTIVE) →
 * 3(ACTIVE·만료 전·price ≤ target인 SELL 입찰 중 price, created_at, id 순 첫 번째 → TRIGGERED + PRICE_ALERT 알림).
 * 입찰은 읽기만(상태 무변경), 재실행 무중복, 대상 1건 실패 격리(§2.6 — 알림 갱신과 알림 INSERT는 한 트랜잭션).
 *
 * 체결이 생기지 않게 가격 알림 사이즈에는 BUY 입찰을 SELL보다 싸게만 둔다.
 */
@Contract3Test
class A5PriceAlertScanTest {

    private fun run() = S2.runBatch("price-alert-scan")

    private fun sell(size: Long, price: Long): Long = Flow.activeBid(Db.createUser(), size, "SELL", price)

    /** §8.3의 3 결과: TRIGGERED·triggered_bid_id·closed_at = 현재 + PRICE_ALERT 알림 1행(알림 주인, ref_id = 알림 id). */
    private fun assertTriggered(alertId: Long, owner: Long, bidId: Long, at: Instant) {
        val a = Db3.row("price_alerts", alertId)
        assertEquals("TRIGGERED", a.text("status"), "가격 알림: $a")
        assertEquals(bidId, a.longOrNull("triggered_bid_id"), "triggered_bid_id: $a")
        assertEquals(at, a.instant("closed_at"), "closed_at = 현재: $a")
        SB.assertNotif("PRICE_ALERT:$alertId", owner, "PRICE_ALERT", alertId, at)
    }

    private fun assertStillActive(alertId: Long) {
        val a = Db3.row("price_alerts", alertId)
        assertEquals("ACTIVE", a.text("status"), "ACTIVE 유지: $a")
        assertNull(a.longOrNull("triggered_bid_id"))
        assertNull(a.instantOrNull("closed_at"))
        assertEquals(0, SB.notifCount("PRICE_ALERT:$alertId"), "알림 없음")
    }

    @Test
    fun `§8_3의 3 - 가장 싼 SELL(나중 입찰이어도) 선택, TRIGGERED·알림 PRICE_ALERT, 입찰 무변경, 조회에 반영, 재실행 무중복`() {
        val size = Db.createProductSize()
        val owner = Db.createUser()
        val alertId = SB.alert(owner, size, 100_000)
        val b1 = sell(size, 100_000)
        val b2 = sell(size, 100_000)
        Flow.advance(SEC)
        val cheap = sell(size, 99_000)
        val cancelSeller = Db.createUser()
        val cancelled = Flow.activeBid(cancelSeller, size, "SELL", 50_000)
        Flow.cancel(cancelSeller, cancelled).assertStatus(200)
        Flow.activeBid(Db.createUser(), size, "BUY", 40_000)
        val bidsBefore = Db.bidsOfSize(size)

        val now = Flow.advance(SEC)
        run()
        assertTriggered(alertId, owner, cheap, now)
        assertEquals(bidsBefore, Db.bidsOfSize(size), "입찰은 읽기만 한다")
        assertTrue(listOf(b1, b2).all { Db.bid(it).status == "ACTIVE" })
        SB.assertAlertBody(SB.alerts(owner).assertStatus(200).json[0], Db3.row("price_alerts", alertId))

        Flow.advance(SEC)
        run()
        assertEquals(1, SB.notifCount("PRICE_ALERT:$alertId"), "재실행은 새 알림을 만들지 않는다")
        assertEquals(now, Db3.row("price_alerts", alertId).instant("closed_at"), "TRIGGERED는 다시 처리하지 않는다")
    }

    @Test
    fun `§8_3의 3 - 같은 가격·같은 created_at이면 id가 작은 입찰, price = target 정각 포함`() {
        val size = Db.createProductSize()
        val owner = Db.createUser()
        val alertId = SB.alert(owner, size, 100_000)
        val first = sell(size, 100_000)                                   // 멈춘 시계: 두 입찰 created_at 같음
        val second = sell(size, 100_000)
        assertEquals(Db.bid(first).createdAt, Db.bid(second).createdAt, "준비: created_at 같음")
        assertTrue(first < second)
        val now = Flow.advance(SEC)
        run()
        assertTriggered(alertId, owner, first, now)
        assertEquals("ACTIVE", Db.bid(second).status)
    }

    @Test
    fun `§8_3의 3 - price = target + 1·BUY 입찰만·SELL 없음이면 ACTIVE 유지, 알림 없음`() {
        val owner = Db.createUser()
        val over = Db.createProductSize()
        val aOver = SB.alert(owner, over, 100_000)
        sell(over, 100_001)
        val buyOnly = Db.createProductSize()
        val aBuy = SB.alert(owner, buyOnly, 100_000)
        Flow.activeBid(Db.createUser(), buyOnly, "BUY", 90_000)
        val aNone = SB.alert(owner, Db.createProductSize(), 100_000)

        Flow.advance(SEC)
        run()
        assertStillActive(aOver)
        assertStillActive(aBuy)
        assertStillActive(aNone)
        assertTrue(Db3.notificationsOf(owner).none { it.text("kind") == "PRICE_ALERT" })
    }

    @Test
    fun `§8_3의 1 - expires_at = 현재(정각)면 EXPIRED·closed_at = 현재(맞는 입찰·구매 제한이 있어도 만료가 먼저, 알림 없음), expires_at = 현재 + 1초는 ACTIVE`() {
        val now0 = Flow.now()
        val created = now0.minus(days(59))
        val matched = Db.createProductSize()
        sell(matched, 50_000)
        val u1 = Db.createUser()
        val u2 = Db.createUser()
        val u3 = Db.createUser()
        fun insert(user: Long, size: Long, expires: Instant): Long = Db3.insert(
            "price_alerts",
            mapOf(
                "user_id" to user, "product_size_id" to size, "target_price" to 100_000L, "status" to "ACTIVE",
                "expires_at" to expires, "triggered_bid_id" to null, "closed_at" to null, "created_at" to created,
            ),
        )!!
        val expiring = insert(u1, matched, now0.plus(SEC))                 // 맞는 SELL 50,000이 있다
        val restrictedExpiring = insert(u2, Db.createProductSize(), now0.plus(SEC))
        val stays = insert(u3, Db.createProductSize(), now0.plus(SEC).plus(SEC))
        val now = Flow.advance(SEC)
        Db.setRestrictions(u2, now.plus(DAY), null)

        run()
        for (id in listOf(expiring, restrictedExpiring)) {
            val a = Db3.row("price_alerts", id)
            assertEquals("EXPIRED", a.text("status"), "만료: $a")
            assertEquals(now, a.instant("closed_at"))
            assertNull(a.longOrNull("triggered_bid_id"))
            assertEquals(0, SB.notifCount("PRICE_ALERT:$id"), "만료는 알림을 만들지 않는다")
        }
        assertStillActive(stays)
    }

    @Test
    fun `§8_3의 2 - 구매 제한 중(끝이 현재보다 뒤)이면 맞는 입찰이 있어도 ACTIVE 유지, 제한 끝 = 현재가 되면 TRIGGERED`() {
        val size = Db.createProductSize()
        val owner = Db.createUser()
        val alertId = SB.alert(owner, size, 100_000)
        val bid = sell(size, 100_000)
        val now = Flow.advance(SEC)
        Db.setRestrictions(owner, now.plus(SEC), null)                   // 입찰 없는 사용자(§18 C2)

        run()
        assertStillActive(alertId)
        assertEquals(now.plus(SEC), Db.user(owner).purchaseRestrictedUntil, "제한은 읽기만 한다")

        val later = Flow.advance(SEC)                                     // 제한 끝 = 현재 → 제한 아님
        run()
        assertTriggered(alertId, owner, bid, later)
    }

    @Test
    fun `§2_6 격리 - A 알림 갱신이 실패해도 A는 ACTIVE·알림 없음, B는 TRIGGERED, failed ≥ 1, 다시 돌리면 A TRIGGERED`() {
        val sizeA = Db.createProductSize()
        val sizeB = Db.createProductSize()
        val ownerA = Db.createUser()
        val ownerB = Db.createUser()
        val a = SB.alert(ownerA, sizeA, 100_000)
        val b = SB.alert(ownerB, sizeB, 100_000)
        assertTrue(a < b)
        val bidA = sell(sizeA, 100_000)
        val bidB = sell(sizeB, 100_000)
        val now = Flow.advance(SEC)

        FailTrigger.on("price_alerts", "UPDATE", "OLD.id = $a") {
            val r = run()
            assertTrue(r.failed() >= 1, "A 실패를 failed에 센다: $r")
            assertTrue(r.long("processed") >= 1, "B는 커밋된다: $r")
        }
        assertStillActive(a)
        assertTriggered(b, ownerB, bidB, now)

        val later = Flow.advance(SEC)
        run()
        assertTriggered(a, ownerA, bidA, later)
        assertEquals(1, SB.notifCount("PRICE_ALERT:$b"))
    }

    @Test
    fun `§2_6 한 트랜잭션 - PRICE_ALERT 알림 INSERT가 실패하면 알림 갱신도 롤백(ACTIVE 유지), failed ≥ 1, 다시 돌리면 TRIGGERED`() {
        val size = Db.createProductSize()
        val owner = Db.createUser()
        val a = SB.alert(owner, size, 100_000)
        val bid = sell(size, 90_000)
        Flow.advance(SEC)

        FailTrigger.on("notifications", "INSERT", "NEW.dedupe_key = 'PRICE_ALERT:$a'") {
            assertTrue(run().failed() >= 1)
        }
        assertStillActive(a)

        val later = Flow.advance(SEC)
        run()
        assertTriggered(a, owner, bid, later)
    }

    @Test
    fun `§8_3의 3 - 같은 가격이면 created_at이 이른 입찰(1초 먼저 만든 쪽)`() {
        val size = Db.createProductSize()
        val owner = Db.createUser()
        val alertId = SB.alert(owner, size, 100_000)
        val early = sell(size, 100_000)
        Flow.advance(SEC)
        val late = sell(size, 100_000)
        assertTrue(Db.bid(early).createdAt < Db.bid(late).createdAt, "준비: created_at 다름")
        val now = Flow.advance(SEC)
        run()
        assertTriggered(alertId, owner, early, now)
    }

    @Test
    fun `§8_2 취소 - 배치가 TRIGGERED·EXPIRED로 바꾼 알림은 ACTIVE가 아니므로 409, 행 그대로`() {
        val now0 = Flow.now()
        val size = Db.createProductSize()
        val owner = Db.createUser()
        val triggered = SB.alert(owner, size, 100_000)
        sell(size, 100_000)
        val expired = Db3.insert(
            "price_alerts",
            mapOf(
                "user_id" to owner, "product_size_id" to Db.createProductSize(), "target_price" to 100_000L, "status" to "ACTIVE",
                "expires_at" to now0.plus(SEC), "triggered_bid_id" to null, "closed_at" to null, "created_at" to now0.minus(days(59)),
            ),
        )!!
        Flow.advance(SEC)
        run()
        assertEquals("TRIGGERED", Db3.row("price_alerts", triggered).text("status"), "준비: TRIGGERED")
        assertEquals("EXPIRED", Db3.row("price_alerts", expired).text("status"), "준비: EXPIRED")
        Flow.advance(SEC)
        for (id in listOf(triggered, expired)) {
            val before = Db3.row("price_alerts", id).toString()
            SB.alertCancel(owner, id).assertError(409, "ITEM_STATE_CONFLICT")
            assertEquals(before, Db3.row("price_alerts", id).toString(), "행 무변경")
        }
    }

    @Test
    fun `§8_3의 3·§8_1 SELL 만료 경계 - expires_at = 현재인 ACTIVE SELL은 제외(알림 ACTIVE 유지·bestSell에서 빠짐), expires_at = 현재 + 1초는 포함`() {
        // bid-expiry를 돌리지 않아 입찰은 ACTIVE인 채 expires_at = 현재가 된다. 가격 알림(60일)·위시는 입찰보다 먼저 등록한다.
        val owner = Db.createUser()
        val sizeX = Db.createProductSize()                                 // 만료 정각 입찰 + 1초 뒤 만든 비싼 입찰
        val sizeY = Db.createProductSize()                                 // 1초 뒤 만든 입찰(대조)
        val sizeZ = Db.createProductSize()                                 // 만료 정각 입찰뿐
        val aX = SB.alert(owner, sizeX, 100_000)
        val aY = SB.alert(owner, sizeY, 100_000)
        for (s in listOf(sizeX, sizeY, sizeZ)) SB.wishAdd(owner, s).assertStatus(201)
        val expiring = Flow.activeBid(Db.createUser(), sizeX, "SELL", 100_000, validDays = 30)
        Flow.activeBid(Db.createUser(), sizeZ, "SELL", 90_000, validDays = 30)
        Flow.advance(SEC)
        Flow.activeBid(Db.createUser(), sizeX, "SELL", 120_000, validDays = 30)
        val alive = Flow.activeBid(Db.createUser(), sizeY, "SELL", 100_000, validDays = 30)

        val now = Flow.moveTo(Db.bid(expiring).expiresAt)
        assertEquals("ACTIVE", Db.bid(expiring).status, "준비: bid-expiry 전이라 ACTIVE")
        assertEquals(now.plus(SEC), Db.bid(alive).expiresAt)

        val w = SB.wishlist(owner).assertStatus(200).json
        assertEquals(listOf(sizeX, sizeY, sizeZ), w.map { it.get("productSizeId").asLong() })
        assertEquals(120_000L, w[0].get("bestSell").asLong(), "만료 정각 입찰을 빼고 다음 최저가: $w")
        assertEquals(100_000L, w[1].get("bestSell").asLong(), "expires_at = 현재 + 1초는 포함: $w")
        assertTrue(w[2].get("bestSell").isNull, "만료 정각 입찰뿐이면 null: $w")

        run()
        assertStillActive(aX)
        assertTriggered(aY, owner, alive, now)
    }
}
