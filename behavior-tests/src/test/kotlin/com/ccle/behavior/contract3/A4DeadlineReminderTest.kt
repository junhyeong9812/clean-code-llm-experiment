package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.DAY
import com.ccle.behavior.contract2.HOUR
import com.ccle.behavior.contract2.Kst
import com.ccle.behavior.contract2.Line
import com.ccle.behavior.contract2.S2
import com.ccle.behavior.contract2.SEC
import com.ccle.behavior.contract2.days
import com.ccle.behavior.support.Contract3Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db2
import com.ccle.behavior.support.Db3
import com.ccle.behavior.support.FailTrigger
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.failed
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-0007 §7.1 배치 `deadline-reminder`: 여섯 종류의 대상 조건(경계 정각 포함/미포함 양쪽)·받는 사람·ref_id·dedupe_key·created_at,
 * 재실행은 새 대상만(dedupe), 표의 행 순서대로 훑기(알림 id 순), 입찰·체결·항목·사용자 무변경, 대상 1건 실패 격리(§2.6).
 *
 * 대상마다 경계 양쪽을 한 실행에서 보려고 대상 둘을 1초 차이로 만든다(멈춘 시계). 배치는 시드·다른 테스트 행도 처리하므로
 * 자기 대상의 dedupe_key로만 단언하고 processed 값은 보지 않는다.
 */
@Contract3Test
class A4DeadlineReminderTest {

    private val minutes10: Duration = Duration.ofMinutes(10)

    private fun run() = S2.runBatch("deadline-reminder")

    // ---- PAYMENT_DUE_SOON: AWAITING_PAYMENT, now < payment_due_at ≤ now + 10분 ----

    @Test
    fun `§7_1 PAYMENT_DUE_SOON - 기한 10분 전 정각 포함·10분 1초 전 미포함, 구매자에게, 결제된 체결 제외, 체결 무변경, 재실행 무중복`() {
        val buyerX = Db.createUser()
        val sellerX = Db.createUser()
        val tX = Flow.trade(buyerX, sellerX, Db.createProductSize(), 100_000)
        val paid = S2.purchase(100_000)                             // 같은 시각 체결이지만 PAID
        Flow.advance(SEC)
        val buyerY = Db.createUser()
        val tY = Flow.trade(buyerY, Db.createUser(), Db.createProductSize(), 100_000)
        val dueX = Db.trade(tX).paymentDueAt
        assertEquals(dueX.plus(SEC), Db.trade(tY).paymentDueAt, "준비: Y 기한 = X 기한 + 1초")
        val beforeX = Db.trade(tX)

        val now = Flow.moveTo(dueX.minus(minutes10))                 // X: due = now + 10분(포함), Y: now + 10분 1초(미포함)
        run()
        SB.assertNotif("PAYMENT_DUE_SOON:$tX", buyerX, "PAYMENT_DUE_SOON", tX, now)
        assertEquals(0, SB.notifCount("PAYMENT_DUE_SOON:$tY"), "10분 1초 전은 대상 아님")
        assertEquals(0, SB.notifCount("PAYMENT_DUE_SOON:${paid.trades.single()}"), "PAID 체결은 대상 아님")
        assertTrue(Db3.notificationsOf(sellerX).none { it.text("kind") == "PAYMENT_DUE_SOON" }, "판매자에게는 보내지 않는다")
        assertEquals(beforeX, Db.trade(tX), "체결 무변경(AWAITING_PAYMENT 그대로)")

        // 재실행: 같은 대상은 다시 만들지 않는다
        run()
        assertEquals(1, SB.notifCount("PAYMENT_DUE_SOON:$tX"))

        val later = Flow.advance(SEC)                                // 이제 Y도 10분 전 정각
        run()
        SB.assertNotif("PAYMENT_DUE_SOON:$tY", buyerY, "PAYMENT_DUE_SOON", tY, later)
        assertEquals(now, SB.notifByKey("PAYMENT_DUE_SOON:$tX")!!.instant("created_at"), "X 행은 그대로")
    }

    @Test
    fun `§7_1 PAYMENT_DUE_SOON - 기한 정각(now = payment_due_at)은 미포함, 기한 1초 전은 포함`() {
        val buyerP = Db.createUser()
        val tP = Flow.trade(buyerP, Db.createUser(), Db.createProductSize(), 100_000)
        Flow.advance(SEC)
        val buyerQ = Db.createUser()
        val tQ = Flow.trade(buyerQ, Db.createUser(), Db.createProductSize(), 100_000)

        val now = Flow.moveTo(Db.trade(tP).paymentDueAt)             // P: due = now(미포함), Q: due = now + 1초(포함)
        run()
        assertEquals(0, SB.notifCount("PAYMENT_DUE_SOON:$tP"), "기한 정각은 대상 아님(now < payment_due_at)")
        SB.assertNotif("PAYMENT_DUE_SOON:$tQ", buyerQ, "PAYMENT_DUE_SOON", tQ, now)
        assertEquals("AWAITING_PAYMENT", Db.trade(tP).status, "배치는 체결을 바꾸지 않는다")
    }

    // ---- SHIPMENT_DUE_SOON: PAID 항목, paid_at + 2일 ≤ now < paid_at + 3일 ----

    @Test
    fun `§7_1 SHIPMENT_DUE_SOON - 결제 + 2일 정각 포함·1초 전 미포함, 판매자에게, 발송된 항목 제외, 항목 무변경, 결제 + 3일 1초 전은 포함`() {
        val a = S2.purchase(100_000)
        val shipped = S2.purchase(100_000)
        S2.toShipped(shipped.item, shipped.seller)
        Flow.advance(SEC)
        val b = S2.purchase(100_000)
        assertEquals(a.paidAt.plus(SEC), b.paidAt, "준비: B 결제 = A 결제 + 1초")

        val now = Flow.moveTo(a.paidAt.plus(days(2)))
        run()
        SB.assertNotif("SHIPMENT_DUE_SOON:${a.item}", a.seller, "SHIPMENT_DUE_SOON", a.item, now)
        assertEquals(0, SB.notifCount("SHIPMENT_DUE_SOON:${b.item}"), "결제 + 2일 1초 전은 대상 아님")
        assertEquals(0, SB.notifCount("SHIPMENT_DUE_SOON:${shipped.item}"), "발송된 항목은 대상 아님")
        assertTrue(Db3.notificationsOf(a.buyer).none { it.text("kind") == "SHIPMENT_DUE_SOON" }, "구매자에게는 보내지 않는다")
        assertEquals("PAID", Db2.item(a.item).status, "항목 무변경")
        assertNull(Db2.item(a.item).cancelledAt)

        val later = Flow.moveTo(b.paidAt.plus(days(3)).minus(SEC))   // B: 결제 + 3일 1초 전(포함)
        run()
        SB.assertNotif("SHIPMENT_DUE_SOON:${b.item}", b.seller, "SHIPMENT_DUE_SOON", b.item, later)
        assertEquals(1, SB.notifCount("SHIPMENT_DUE_SOON:${a.item}"), "A는 다시 만들지 않는다")
    }

    @Test
    fun `§7_1 SHIPMENT_DUE_SOON - 결제 + 3일 정각은 미포함`() {
        val d = S2.purchase(100_000)
        Flow.advance(SEC)
        val e = S2.purchase(100_000)
        val now = Flow.moveTo(d.paidAt.plus(days(3)))                 // D: 결제 + 3일(미포함), E: 결제 + 3일 − 1초(포함)
        run()
        assertEquals(0, SB.notifCount("SHIPMENT_DUE_SOON:${d.item}"), "now < paid_at + 3일이어야 한다")
        SB.assertNotif("SHIPMENT_DUE_SOON:${e.item}", e.seller, "SHIPMENT_DUE_SOON", e.item, now)
        assertEquals("PAID", Db2.item(d.item).status, "기한 초과 처리는 이 배치가 하지 않는다")
    }

    // ---- DISPUTE_WINDOW_CLOSING: CONFIRMED, confirmed_at + 12일 ≤ now ≤ confirmed_at + 14일 ----

    @Test
    fun `§7_1 DISPUTE_WINDOW_CLOSING - 확정 + 12일 정각 포함·1초 전 미포함, 구매자에게, 항목 무변경`() {
        val x = S2.purchase(100_000)
        S2.toConfirmed(x.item, x.seller, x.buyer)                      // §18: 배송 완료 직후 구매자 확정
        Flow.advance(SEC)
        val y = S2.purchase(100_000)
        S2.toConfirmed(y.item, y.seller, y.buyer)
        val cX = Db2.item(x.item).confirmedAt!!
        assertEquals(cX.plus(SEC), Db2.item(y.item).confirmedAt, "준비: Y 확정 = X 확정 + 1초")

        val now = Flow.moveTo(cX.plus(days(12)))
        run()
        SB.assertNotif("DISPUTE_WINDOW_CLOSING:${x.item}", x.buyer, "DISPUTE_WINDOW_CLOSING", x.item, now)
        assertEquals(0, SB.notifCount("DISPUTE_WINDOW_CLOSING:${y.item}"), "확정 + 12일 1초 전은 대상 아님")
        assertTrue(Db3.notificationsOf(x.seller).none { it.text("kind") == "DISPUTE_WINDOW_CLOSING" }, "판매자에게는 보내지 않는다")
        assertEquals("CONFIRMED", Db2.item(x.item).status)
        assertEquals(cX, Db2.item(x.item).confirmedAt, "confirmed_at 무변경")
    }

    @Test
    fun `§7_1 DISPUTE_WINDOW_CLOSING - 확정 + 14일 정각 포함·1초 지나면 미포함`() {
        val p = S2.purchase(100_000)
        S2.toConfirmed(p.item, p.seller, p.buyer)
        Flow.advance(SEC)
        val q = S2.purchase(100_000)
        S2.toConfirmed(q.item, q.seller, q.buyer)
        val cQ = Db2.item(q.item).confirmedAt!!

        val now = Flow.moveTo(cQ.plus(days(14)))                      // Q: 확정 + 14일(포함), P: 확정 + 14일 1초(미포함)
        run()
        SB.assertNotif("DISPUTE_WINDOW_CLOSING:${q.item}", q.buyer, "DISPUTE_WINDOW_CLOSING", q.item, now)
        assertEquals(0, SB.notifCount("DISPUTE_WINDOW_CLOSING:${p.item}"), "확정 + 14일이 지난 항목은 대상 아님")
    }

    @Test
    fun `§7_1 DISPUTE_WINDOW_CLOSING - CONFIRMED만, 창 안의 DISPUTED·REFUNDED_AFTER_CONFIRM은 미포함, 기각되어 CONFIRMED로 돌아온 항목은 포함`() {
        val disputed = S2.purchase(100_000)
        val refunded = S2.purchase(100_000)
        val rejected = S2.purchase(100_000)
        for (p in listOf(disputed, refunded, rejected)) S2.toConfirmed(p.item, p.seller, p.buyer)
        val c = Db2.item(disputed.item).confirmedAt!!
        assertTrue(listOf(refunded, rejected).all { Db2.item(it.item).confirmedAt == c }, "준비: 같은 확정 시각")
        S2.openDispute(disputed.item, disputed.buyer)
        S2.resolved(S2.openDispute(refunded.item, refunded.buyer), true)
        S2.resolved(S2.openDispute(rejected.item, rejected.buyer), false)
        assertEquals("DISPUTED", Db2.item(disputed.item).status, "준비")
        assertEquals("REFUNDED_AFTER_CONFIRM", Db2.item(refunded.item).status, "준비")
        assertEquals("CONFIRMED", Db2.item(rejected.item).status, "준비: 기각 → CONFIRMED")
        assertEquals(c, Db2.item(rejected.item).confirmedAt, "준비: 기각해도 confirmed_at 그대로")

        val now = Flow.moveTo(c.plus(days(12)))
        run()
        assertEquals(0, SB.notifCount("DISPUTE_WINDOW_CLOSING:${disputed.item}"), "DISPUTED는 대상 아님")
        assertEquals(0, SB.notifCount("DISPUTE_WINDOW_CLOSING:${refunded.item}"), "REFUNDED_AFTER_CONFIRM은 대상 아님")
        SB.assertNotif("DISPUTE_WINDOW_CLOSING:${rejected.item}", rejected.buyer, "DISPUTE_WINDOW_CLOSING", rejected.item, now)
        assertEquals("DISPUTED", Db2.item(disputed.item).status, "항목 무변경")
    }

    // ---- SETTLEMENT_PAID: PAID 정산, now − 7일 < paid_at ≤ now ----

    @Test
    fun `§7_1 SETTLEMENT_PAID - 지급 전(paid_at = now + 1초) 미포함, paid_at = now 포함, paid_at = now − 7일 + 1초 포함, paid_at = now − 7일 미포함, 판매자에게`() {
        // 판매자 A(주 W1)·C(주 W2)·E(주 W3)의 정산. 각 주의 지급 시각 = 다음 주 수요일 00:00 KST(ADR-0004 §10.2)
        val sellerA = Db.createUser()
        val pA = S2.purchase(listOf(Line(100_000, null, sellerA)))
        S2.toConfirmed(pA.item, sellerA, pA.buyer)
        val c1 = Kst.cutoffOf(Flow.now())
        Flow.moveTo(c1)
        S2.runBatch("weekly-settlement")
        val sA = Db2.settlementsOfSeller(sellerA).single()
        assertEquals("PAID", sA.status, "준비: A 정산 PAID")
        val paid1 = sA.paidAt!!

        Flow.advance(HOUR)
        val sellerC = Db.createUser()
        val pC = S2.purchase(listOf(Line(100_000, null, sellerC)))
        S2.toConfirmed(pC.item, sellerC, pC.buyer)

        Flow.moveTo(paid1.minus(SEC))                                 // A: paid_at = now + 1초(미포함)
        run()
        assertEquals(0, SB.notifCount("SETTLEMENT_PAID:${sA.id}"), "지급 시각 전은 대상 아님")

        val c2 = c1.plus(days(7))
        Flow.moveTo(c2)
        S2.runBatch("weekly-settlement")
        val sC = Db2.settlementsOfSeller(sellerC).single()
        assertEquals("PAID", sC.status, "준비: C 정산 PAID")
        val paid2 = sC.paidAt!!
        assertEquals(paid1.plus(days(7)), paid2)

        Flow.advance(HOUR)
        val sellerE = Db.createUser()
        val pE = S2.purchase(listOf(Line(100_000, null, sellerE)))
        S2.toConfirmed(pE.item, sellerE, pE.buyer)

        val now1 = Flow.moveTo(paid2.minus(SEC))                      // A: paid_at = now − 7일 + 1초(포함), C: now + 1초(미포함)
        run()
        SB.assertNotif("SETTLEMENT_PAID:${sA.id}", sellerA, "SETTLEMENT_PAID", sA.id, now1)
        assertTrue(Db3.notificationsOf(pA.buyer).none { it.text("kind") == "SETTLEMENT_PAID" }, "구매자에게는 보내지 않는다")
        assertEquals(0, SB.notifCount("SETTLEMENT_PAID:${sC.id}"))

        Flow.moveTo(c2.plus(days(7)))
        S2.runBatch("weekly-settlement")
        val sE = Db2.settlementsOfSeller(sellerE).single()
        assertEquals("PAID", sE.status, "준비: E 정산 PAID")
        assertEquals(paid2.plus(days(7)), sE.paidAt)

        val now2 = Flow.moveTo(sE.paidAt!!)                           // C: paid_at = now − 7일(미포함), E: paid_at = now(포함)
        run()
        assertEquals(0, SB.notifCount("SETTLEMENT_PAID:${sC.id}"), "now − 7일 < paid_at 이어야 한다")
        SB.assertNotif("SETTLEMENT_PAID:${sE.id}", sellerE, "SETTLEMENT_PAID", sE.id, now2)
        assertEquals(sA, Db2.settlementsOfSeller(sellerA).single(), "정산 무변경")
    }

    // ---- PURCHASE_RESTRICTED · SALE_RESTRICTED: 제한 끝 > now ----

    @Test
    fun `§7_1 제한 알림 - 끝이 now보다 뒤인 사용자만(끝 = now 미포함·now + 1초 포함), dedupe_key의 E = epoch 초 내림, 재실행 무중복, 제한 연장 시 새 키로 1행, 사용자 무변경`() {
        val now = Flow.now()
        val u1 = Db.createUser()
        val u2 = Db.createUser()
        val u3 = Db.createUser()
        val u4 = Db.createUser()
        val p1 = now.plus(DAY).plusMillis(700)                          // 소수 초 → 내림
        val s1 = now.plus(days(2)).plusMillis(300)
        Db.setRestrictions(u1, p1, s1)
        Db.setRestrictions(u2, now, null)                                // 끝 = now → 대상 아님
        Db.setRestrictions(u3, null, now.plus(SEC))                      // 끝 = now + 1초 → 대상
        Db.setRestrictions(u4, null, now)                                // 끝 = now → 대상 아님
        val before1 = Db.user(u1)

        run()
        val kP1 = "PURCHASE_RESTRICTED:$u1:${SB.epochFloor(p1)}"
        val kS1 = "SALE_RESTRICTED:$u1:${SB.epochFloor(s1)}"
        SB.assertNotif(kP1, u1, "PURCHASE_RESTRICTED", u1, now)
        SB.assertNotif(kS1, u1, "SALE_RESTRICTED", u1, now)
        SB.assertNotif("SALE_RESTRICTED:$u3:${SB.epochFloor(now.plus(SEC))}", u3, "SALE_RESTRICTED", u3, now)
        assertTrue(Db3.notificationsOf(u2).none { it.text("kind") == "PURCHASE_RESTRICTED" }, "구매 제한 끝 = now는 대상 아님")
        assertTrue(Db3.notificationsOf(u4).none { it.text("kind") == "SALE_RESTRICTED" }, "판매 제한 끝 = now는 대상 아님")
        assertEquals(2, Db3.notificationsOf(u1).count { it.text("kind").endsWith("_RESTRICTED") })
        assertEquals(before1, Db.user(u1), "제한은 읽기만 한다")

        run()
        assertEquals(2, Db3.notificationsOf(u1).count { it.text("kind").endsWith("_RESTRICTED") }, "같은 제한 구간은 다시 만들지 않는다")

        // 제한이 연장되면(끝 시각이 바뀌면) dedupe_key가 달라 새 알림 1행
        val p1b = now.plus(days(5))
        Db.setRestrictions(u1, p1b, s1)
        val later = Flow.advance(SEC)
        run()
        SB.assertNotif("PURCHASE_RESTRICTED:$u1:${SB.epochFloor(p1b)}", u1, "PURCHASE_RESTRICTED", u1, later)
        assertEquals(3, Db3.notificationsOf(u1).count { it.text("kind").endsWith("_RESTRICTED") })
        assertEquals(1, SB.notifCount(kP1), "앞 구간 알림은 그대로")
    }

    // ---- 순서 ----

    @Test
    fun `§7_1 훑는 순서 - 표의 행 순서대로 종류를 끝까지(결제 기한 → 구매 제한 전부 → 판매 제한), 종류 안은 id 순(알림 id 순)`() {
        val buyer = Db.createUser()
        val t = Flow.trade(buyer, Db.createUser(), Db.createProductSize(), 100_000)
        val now = Flow.moveTo(Db.trade(t).paymentDueAt.minus(Duration.ofMinutes(5)))
        val r1 = Db.createUser()
        val r2 = Db.createUser()
        assertTrue(r1 < r2)
        Db.setRestrictions(r1, now.plus(DAY), now.plus(DAY))
        Db.setRestrictions(r2, now.plus(DAY), null)

        run()
        val pay = SB.notifByKey("PAYMENT_DUE_SOON:$t")!!.id
        val e = SB.epochFloor(now.plus(DAY))
        val pr1 = SB.notifByKey("PURCHASE_RESTRICTED:$r1:$e")!!.id
        val pr2 = SB.notifByKey("PURCHASE_RESTRICTED:$r2:$e")!!.id
        val sr1 = SB.notifByKey("SALE_RESTRICTED:$r1:$e")!!.id
        assertTrue(pay < pr1 && pr1 < pr2 && pr2 < sr1, "알림 id 순서: 결제 $pay < 구매 r1 $pr1 < 구매 r2 $pr2 < 판매 r1 $sr1")
    }

    // ---- 격리 (§2.6) ----

    @Test
    fun `§2_6 격리 - A 알림 INSERT가 실패해도 B는 만들고 failed ≥ 1, 트리거를 지우고 다시 돌리면 A 1행`() {
        val now = Flow.now()
        val a = Db.createUser()
        val b = Db.createUser()
        assertTrue(a < b)
        Db.setRestrictions(a, now.plus(DAY), null)
        Db.setRestrictions(b, now.plus(DAY), null)
        val e = SB.epochFloor(now.plus(DAY))

        FailTrigger.on("notifications", "INSERT", "NEW.user_id = $a") {
            val r = run()
            assertTrue(r.failed() >= 1, "A 실패를 failed에 센다: $r")
            assertTrue(r.long("processed") >= 1, "B는 커밋된다: $r")
        }
        assertTrue(Db3.notificationsOf(a).none { it.text("kind") == "PURCHASE_RESTRICTED" }, "A는 남지 않는다")
        SB.assertNotif("PURCHASE_RESTRICTED:$b:$e", b, "PURCHASE_RESTRICTED", b, now)

        val later = Flow.advance(SEC)
        run()
        SB.assertNotif("PURCHASE_RESTRICTED:$a:$e", a, "PURCHASE_RESTRICTED", a, later)
        assertEquals(1, Db3.notificationsOf(b).count { it.text("kind") == "PURCHASE_RESTRICTED" }, "B는 다시 만들지 않는다")
    }
}
