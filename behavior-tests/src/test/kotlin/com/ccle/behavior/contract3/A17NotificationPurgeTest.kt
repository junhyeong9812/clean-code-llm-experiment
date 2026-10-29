package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.HOUR
import com.ccle.behavior.contract2.Kst
import com.ccle.behavior.contract2.Line
import com.ccle.behavior.contract2.S2
import com.ccle.behavior.contract2.SEC
import com.ccle.behavior.contract2.days
import com.ccle.behavior.support.Contract3Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db2
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.failed
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-0007 §20.6.3 배치 `notification-purge`: 대상 = read_at IS NOT NULL AND read_at ≤ 현재 − 30일(정각 포함), 안 읽은 알림은 지우지 않는다,
 * 알림 1건 = DELETE 1문장(잠금 없음), 같은 시각 재실행 processed 0(§20.1), 건별 격리(§2.6),
 * 지운 알림은 dedupe로 다시 생기지 않는다 — §20.12가 정한 종류(PAYMENT_DUE_SOON·SETTLEMENT_PAID)를 실제 deadline-reminder로 만든다.
 * 배치는 시드·다른 테스트 행도 처리하므로 자기 행의 존재로 단언하고, processed 정확값은 격리 실행(실행 직전 같은 대상 조건으로 센 수 − 실패 1)과 재실행 0에서 본다.
 */
@Contract3Test
class A17NotificationPurgeTest {

    private val retention: Duration = days(30)

    private fun purge() = S2.runBatch("notification-purge")

    @Test
    fun `§20_6_3 대상 경계 - read_at = 현재 − 30일은 지운다, 1초 뒤 읽은 것은 남는다(다음 초에 지운다), 안 읽은 알림은 오래돼도 남는다, 같은 시각 재실행 processed 0`() {
        val u = Db.createUser()
        val rows = SDB.restrictionNotifs(u, 3)
        val (a, b, unreadRow) = rows
        val t = Flow.advance(HOUR)
        SDB.readyRead(a.id, u)
        Flow.advance(SEC)
        SDB.readyRead(b.id, u)

        Flow.moveTo(t.plus(retention).minus(SEC))
        purge()
        assertTrue(SDB.notifExists(a.id) && SDB.notifExists(b.id), "30일 − 1초: 아직")

        Flow.moveTo(t.plus(retention))
        purge()
        assertFalse(SDB.notifExists(a.id), "read_at = 현재 − 30일(정각)은 지운다")
        assertTrue(SDB.notifExists(b.id), "read_at = 현재 − 30일 + 1초는 남는다")
        assertTrue(SDB.notifExists(unreadRow.id), "안 읽은 알림은 지우지 않는다")
        assertEquals(0L, purge().long("processed"), "같은 시각 재실행 processed 0")

        Flow.advance(SEC)
        purge()
        assertFalse(SDB.notifExists(b.id))
        assertTrue(SDB.notifExists(unreadRow.id))
        assertNull(SDB.notif(unreadRow.id).instantOrNull("read_at"), "안 읽은 알림은 그대로")
        // 조회도 지운 행을 보이지 않는다
        val list = SB.notifications(u).assertStatus(200)
        assertEquals(listOf(unreadRow.id), list.json.map { it.get("notificationId").asLong() })
        assertEquals(1L, SDB.unreadCount(u).assertStatus(200).long("unreadCount"))
    }

    @Test
    fun `§20_6_3·§2_6 격리 - A 삭제가 실패해도 failed = 1·processed = 직전 대상 수 − 1·B는 지운다, 다시 돌리면 A도 지운다`() {
        val u = Db.createUser()
        val (a, b) = SDB.restrictionNotifs(u, 2)
        assertTrue(a.id < b.id)
        Flow.advance(HOUR)
        SDB.readAll(u).assertStatus(200)
        val readAt = SDB.notif(a.id).instant("read_at")
        val at = Flow.moveTo(readAt.plus(retention))
        val expected = SDB.purgeTargetCount(at)                               // 실행 직전 같은 대상 조건(§20.6.3)으로 센 수 — A·B 포함
        assertTrue(expected >= 2, "준비: A·B가 대상: $expected")

        SDB.failOnDelete("notifications", "OLD.id = ${a.id}") {
            val r = purge()
            assertEquals(1L, r.failed(), "A 실패만 failed에 센다: $r")
            assertEquals(expected - 1, r.long("processed"), "processed = 지운 행 수 = 대상 수 − 실패한 A: $r")
        }
        assertTrue(SDB.notifExists(a.id), "실패한 A는 남는다")
        assertFalse(SDB.notifExists(b.id), "B는 지운다")

        Flow.advance(SEC)
        purge()
        assertFalse(SDB.notifExists(a.id), "트리거를 지운 뒤 다시 돌리면 A도 지운다")
    }

    /**
     * §20.6.3 "지운 알림이 dedupe로 다시 생기지 않는다"·§20.12: 읽음 → 시계 이동(≥ 30일) → notification-purge → deadline-reminder.
     * 종류는 PAYMENT_DUE_SOON(체결의 payment_due_at)·SETTLEMENT_PAID(정산 paid_at) — 둘 다 T1·C2 고칠 곳과 무관한 저장 열.
     * 정산은 구매자 확정(배송 완료 직후 — §18 T1 회피)으로 만들고, 제한된 사용자를 두지 않는다(C2).
     */
    @Test
    fun `§20_6_3·§20_12 재생성 없음 - PAYMENT_DUE_SOON·SETTLEMENT_PAID를 deadline-reminder로 만들고 읽은 뒤 30일 지나 정리하면 다시 돌려도 같은 dedupe_key가 생기지 않는다`() {
        // SETTLEMENT_PAID: 구매 확정 → 주간 정산(지급 시각 = 다음 주 수요일 00:00 KST, ADR-0004 §10.2)
        val seller = Db.createUser()
        val sale = S2.purchase(listOf(Line(100_000, null, seller)))
        S2.toConfirmed(sale.item, seller, sale.buyer)
        Flow.moveTo(Kst.cutoffOf(Flow.now()))
        S2.runBatch("weekly-settlement")
        val settlement = Db2.settlementsOfSeller(seller).single()
        assertEquals("PAID", settlement.status, "준비: 정산 PAID")
        Flow.moveTo(settlement.paidAt!!)

        // PAYMENT_DUE_SOON: 결제 기한 10분 안의 AWAITING_PAYMENT 체결
        val buyer = Db.createUser()
        val trade = Flow.trade(buyer, Db.createUser(), Db.createProductSize(), 100_000)
        Flow.moveTo(Db.trade(trade).paymentDueAt.minus(Duration.ofMinutes(5)))

        val made = Flow.now()
        S2.runBatch("deadline-reminder")
        val payKey = "PAYMENT_DUE_SOON:$trade"
        val paidKey = "SETTLEMENT_PAID:${settlement.id}"
        val pay = SB.assertNotif(payKey, buyer, "PAYMENT_DUE_SOON", trade, made)
        val paid = SB.assertNotif(paidKey, seller, "SETTLEMENT_PAID", settlement.id, made)

        val readAt = Flow.advance(SEC)
        SB.read(pay.id, buyer).assertStatus(200)
        SB.read(paid.id, seller).assertStatus(200)

        Flow.moveTo(readAt.plus(retention))
        purge()
        assertEquals(0L, SB.notifCount(payKey), "정리됨: $payKey")
        assertEquals(0L, SB.notifCount(paidKey), "정리됨: $paidKey")

        S2.runBatch("deadline-reminder")
        assertEquals(0L, SB.notifCount(payKey), "지운 PAYMENT_DUE_SOON이 다시 생기지 않는다")
        assertEquals(0L, SB.notifCount(paidKey), "지운 SETTLEMENT_PAID가 다시 생기지 않는다")
        Flow.advance(HOUR)
        S2.runBatch("deadline-reminder")
        assertEquals(0L, SB.notifCount(payKey) + SB.notifCount(paidKey), "시계가 더 가도 다시 생기지 않는다")
    }
}
