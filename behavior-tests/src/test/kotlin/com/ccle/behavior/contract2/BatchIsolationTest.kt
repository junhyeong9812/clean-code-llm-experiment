package com.ccle.behavior.contract2

import com.ccle.behavior.support.Contract2Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db2
import com.ccle.behavior.support.FailTrigger
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.PgCancel
import com.ccle.behavior.support.assertUnexpectedError
import com.ccle.behavior.support.failed
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-0004 §15의 1(2단계 배치 대상 1건 실패 격리, ADR-0003 §12의 2 적용)·§5 응답 failed·§7.2 PG_UNAVAILABLE 건너뛰기의 failed 계수,
 * weekly-settlement 비격리(앞 auto-confirm 실패 → 400 UNEXPECTED_ERROR, 마감 미전진 — 명세 I3).
 *
 * 실패는 임시 DB 트리거로 자기 대상 A 한 행에서만 낸다(FailTrigger). 대상 A < B(id 순).
 * I1: A의 행은 실행 전과 같고 B는 ADR대로 처리된다(A·B는 서로 독립인 행). I2: 트리거를 지운 뒤 다시 돌리면 A가 처리된다. I4: failed ≥ 1
 * (shipment-deadline은 다른 테스트가 남긴 계속 실패하는 대상(카드 취소 금액 불일치)이 있을 수 있어, A·B가 기한 전일 때 잰 기저 failed + 1).
 * 시드·다른 테스트 행도 처리되므로 processed·failed의 정확한 값은 단언하지 않는다(weekly-settlement 200의 failed = 0만 예외 — §5).
 */
@Contract2Test
class BatchIsolationTest {

    private fun assertFailedRun(name: String, baseline: Long = 0): com.ccle.behavior.support.Resp {
        val r = S2.runBatch(name)
        assertTrue(r.failed() >= baseline + 1, "A 실패를 failed에 센다(기저 $baseline): $r")
        assertTrue(r.long("processed") >= 1, "B는 커밋되어 processed에 들어간다: $r")
        return r
    }

    // ---- shipment-deadline ----

    /** §6 판매자 귀책(기한 초과) 결과: CANCELLED_SELLER_FAULT·cancelled_at = 현재·검수 행 없음·환불·카드 취소·패널티·적립 예정 취소·주문 CANCELLED. 체결가 100,000. */
    private fun assertSellerFaultByDeadline(p: Purchase, at: Instant) {
        val row = Db2.item(p.item)
        assertEquals("CANCELLED_SELLER_FAULT", row.status)
        assertEquals(at, row.cancelledAt)
        assertTrue(Db2.inspections(p.item).isEmpty())
        assertRefund(p.item, RefundExp(card = 103_000, point = 0, buyerFee = 3_000, shipping = 0, reason = "발송 기한 초과", fault = "SELLER"), at)
        assertCardCancel(p, p.item, 103_000, at)
        val pen = Db2.penaltiesOfItem(p.item).single()
        assertEquals(p.seller, pen.sellerId)
        assertEquals(10_000L, pen.amount)
        assertEquals(at, pen.createdAt)
        assertEquals("CANCELLED", Db.order(p.orderId).status)
        assertEquals("CANCELLED", Db2.ledgerOfItem(p.item, "EARN").single().status)
    }

    /** 기한 초과 처리 전 상태(PAID 그대로, 환불·CANCEL 행·패널티 없음, 적립 예정 PENDING, 주문 PAID). */
    private fun assertStillPaid(p: Purchase) {
        assertEquals("PAID", Db2.item(p.item).status)
        assertNoRefund(p, p.item)
        assertTrue(Db2.penaltiesOfItem(p.item).isEmpty())
        assertEquals("PENDING", Db2.ledgerOfItem(p.item, "EARN").single().status)
        assertEquals("PAID", Db.order(p.orderId).status)
    }

    @Test
    fun `shipment-deadline 격리 - A 반영이 실패해도 A는 PAID·환불·패널티 없음, B는 판매자 귀책, 다시 돌리면 A 처리되고 카드 취소는 1건`() {
        val a = S2.purchase(100_000)
        val b = S2.purchase(100_000)
        assertTrue(a.item < b.item)
        val baseline = S2.runBatch("shipment-deadline").failed()      // A·B는 아직 기한 전
        val now = Flow.moveTo(maxOf(a.paidAt, b.paidAt).plus(days(3)))

        FailTrigger.on("order_items", "UPDATE", "OLD.id = ${a.item}") { assertFailedRun("shipment-deadline", baseline) }
        assertStillPaid(a)
        assertSellerFaultByDeadline(b, now)

        // I2: 같은 키(cancel-<id>)로 다시 요청 → PG가 첫 기록을 돌려주고 반영된다(§15의 1) — 카드는 한 번만 취소
        val later = Flow.advance(HOUR)
        S2.runBatch("shipment-deadline")
        assertSellerFaultByDeadline(a, later)
        assertEquals(1, Db2.refunds(b.item).size, "B는 다시 처리되지 않는다")
    }

    @Test
    fun `shipment-deadline 카드 취소 PG 불가 - A를 건너뛰고 failed ≥ 1, B는 처리, PG 회복 뒤 A 처리`() {
        val a = S2.purchase(100_000)
        val b = S2.purchase(100_000)
        assertTrue(a.item < b.item)
        PgCancel.unavailable(a.item)
        val baseline = S2.runBatch("shipment-deadline").failed()      // A·B는 아직 기한 전
        val now = Flow.moveTo(maxOf(a.paidAt, b.paidAt).plus(days(3)))

        assertFailedRun("shipment-deadline", baseline)
        assertStillPaid(a)
        assertTrue(PgCancel.info(a.item).requests >= 1, "배치가 PG에 취소를 요청했어야 한다")
        assertSellerFaultByDeadline(b, now)

        PgCancel.approve(a.item)
        val later = Flow.advance(HOUR)
        S2.runBatch("shipment-deadline")
        assertSellerFaultByDeadline(a, later)
    }

    // ---- auto-confirm (단독 — 격리) ----

    @Test
    fun `auto-confirm 단독 격리 - A 확정이 실패해도 A는 DELIVERED·포인트 무변경, B는 소급 확정, 다시 돌리면 A도 소급 확정`() {
        val a = S2.purchase(100_000)
        val b = S2.purchase(100_000)
        assertTrue(a.item < b.item)
        S2.toDelivered(a.item, a.seller)
        S2.toDelivered(b.item, b.seller)
        val retroA = Db2.item(a.item).deliveredAt!!.plus(days(7))
        val retroB = Db2.item(b.item).deliveredAt!!.plus(days(7))
        Flow.moveTo(maxOf(retroA, retroB).plus(DAY))

        FailTrigger.on("order_items", "UPDATE", "OLD.id = ${a.item}") { assertFailedRun("auto-confirm") }
        val rowA = Db2.item(a.item)
        assertEquals("DELIVERED", rowA.status)
        assertNull(rowA.confirmedAt)
        val earnA = Db.earnOfItem(a.item).single()
        assertEquals("PENDING", earnA.status)
        assertNull(earnA.expiresAt)
        assertEquals(0L, Db.balance(a.buyer)!!.available)
        assertEquals(1_000L, Db.balance(a.buyer)!!.pending)
        assertAutoConfirmed(b, retroB)

        // I2
        Flow.advance(HOUR)
        S2.runBatch("auto-confirm")
        assertAutoConfirmed(a, retroA)
        assertEquals(retroB, Db2.item(b.item).confirmedAt)
    }

    /** §5 auto-confirm 결과: CONFIRMED, confirmed_at = delivered_at + 7일, §8.2 포인트 확정(체결가 100,000 → 적립 1,000). */
    private fun assertAutoConfirmed(p: Purchase, retro: Instant) {
        val row = Db2.item(p.item)
        assertEquals("CONFIRMED", row.status)
        assertEquals(retro, row.confirmedAt)
        val e = Db.earnOfItem(p.item).single()
        assertEquals("AVAILABLE", e.status)
        assertEquals(retro.plus(days(365)), e.expiresAt)
        assertEquals(1_000L, Db.balance(p.buyer)!!.available)
        assertEquals(0L, Db.balance(p.buyer)!!.pending)
    }

    // ---- point-expiry ----

    @Test
    fun `point-expiry 격리 - A 묶음 소멸이 실패해도 A는 remaining·잔액 그대로·EXPIRE 없음, B는 소멸, 다시 돌리면 A 소멸`() {
        val userA = Db.createUser()
        val userB = Db.createUser()
        val t = Flow.now()
        val bundleA = Db.createPointBundle(userA, 2_000, t)
        val bundleB = Db.createPointBundle(userB, 3_000, t)
        assertTrue(bundleA.earnId < bundleB.earnId)
        val now = Flow.moveTo(maxOf(bundleA.expiresAt, bundleB.expiresAt))

        FailTrigger.on("point_ledger", "UPDATE", "OLD.id = ${bundleA.earnId}") { assertFailedRun("point-expiry") }
        assertTrue(Db2.ledgerOfUser(userA, "EXPIRE").isEmpty(), "A EXPIRE 행 없음")
        assertTrue(Db.allocationsOfEarn(bundleA.earnId).isEmpty(), "A 배분 없음")
        assertEquals(2_000L, Db.ledgerEntry(bundleA.earnId).remaining)
        assertEquals(2_000L, Db.balance(userA)!!.available)
        assertExpired(userB, bundleB.earnId, 3_000, now)

        // I2
        val later = Flow.advance(HOUR)
        S2.runBatch("point-expiry")
        assertExpired(userA, bundleA.earnId, 2_000, later)
        assertEquals(1, Db2.ledgerOfUser(userB, "EXPIRE").size, "B는 다시 처리되지 않는다")
    }

    /** §8.3 소멸: EXPIRE 1행(DONE, amount = remaining, 주문 NULL, created_at = 현재) + 배분, 묶음 0·AVAILABLE 유지, available 감소. */
    private fun assertExpired(user: Long, earnId: Long, amount: Long, at: Instant) {
        val ex = Db2.ledgerOfUser(user, "EXPIRE").single()
        assertEquals("DONE", ex.status)
        assertEquals(amount, ex.amount)
        assertNull(ex.orderId)
        assertNull(ex.orderItemId)
        assertEquals(at, ex.createdAt)
        assertEquals(mapOf(earnId to amount), allocMap(ex.id))
        val e = Db.ledgerEntry(earnId)
        assertEquals(0L, e.remaining)
        assertEquals("AVAILABLE", e.status)
        assertEquals(0L, Db.balance(user)!!.available)
    }

    // ---- grade-recalc ----

    @Test
    fun `grade-recalc 격리 - A 이력 쓰기가 실패해도 A는 이력 없음·BRONZE, B는 SILVER, 같은 기준일에 다시 돌리면 A SILVER`() {
        val userA = Db.createUser()
        val userB = Db.createUser()
        assertTrue(userA < userB)
        for (u in listOf(userA, userB)) {
            val p = S2.purchase(listOf(Line(500_000)), u)
            S2.toConfirmed(p.item, p.seller, u)
        }
        val d = Kst.nextMonthStart(Flow.now())
        Flow.moveTo(Kst.at(d))                                     // §8.1: 기준일 00:00(KST)에 돌린다

        FailTrigger.on("user_grade_history", "INSERT", "NEW.user_id = $userA") { assertFailedRun("grade-recalc") }
        assertTrue(Db2.gradeHistory(userA).isEmpty(), "A 이력 없음")
        assertEquals("BRONZE", Db.user(userA).grade, "A users.grade 그대로(부분 반영 없음)")
        assertSilver(userB, d)

        // I2: 같은 기준일(시계 그대로)에 다시 돌린다 — A는 그 달 이력이 없으므로 대상으로 남아 있다
        S2.runBatch("grade-recalc")
        assertSilver(userA, d)
        assertEquals(1, Db2.gradeHistory(userB).size, "B는 그 달 이력이 있어 건너뛴다")
    }

    private fun assertSilver(user: Long, d: java.time.LocalDate) {
        val h = Db2.gradeHistory(user).single()
        assertEquals("SILVER", h.grade)
        assertEquals(d, h.effectiveFrom)
        assertEquals(500_000L, h.basisAmount)
        assertEquals("SILVER", Db.user(user).grade)
    }

    // ---- weekly-settlement (격리하지 않음 — I3) ----

    @Test
    fun `weekly-settlement - 앞 auto-confirm 1건 실패면 400 UNEXPECTED_ERROR·정산 없음·마감 미전진(앞 확정은 커밋, 뒤는 멈춤), 원인 제거 뒤 그 주를 그 항목 포함해 정산`() {
        val seller = Db.createUser()
        // 같은 판매자, 주문 3건(항목 id 순 Z < X < W). X에서 실패시킨다
        val z = S2.purchase(listOf(Line(30_000, "TECH", seller)))
        val x = S2.purchase(listOf(Line(30_000, "TECH", seller)))
        val w = S2.purchase(listOf(Line(30_000, "TECH", seller)))
        assertTrue(z.item < x.item && x.item < w.item)
        for (p in listOf(z, x, w)) S2.toDelivered(p.item, seller)
        val retro = listOf(z, x, w).maxOf { Db2.item(it.item).deliveredAt!! }.plus(days(7))
        val retros = listOf(z, x, w).associate { it.item to Db2.item(it.item).deliveredAt!!.plus(days(7)) }
        val c = Kst.cutoffOf(retro)
        assertTrue(retros.values.all { Kst.cutoffOf(it) == c }, "준비: 세 항목의 소급 확정 시각이 같은 주")
        Flow.moveTo(c)
        val lastCutoffBefore = Db.lastSettlementCutoff()
        val settlementsBefore = Db.long("SELECT count(*) FROM settlements")

        FailTrigger.on("order_items", "UPDATE", "OLD.id = ${x.item}") { name ->
            Flow.batch("weekly-settlement").assertUnexpectedError(name)
        }
        // I3: 정산하지 않았다 — 마지막 마감·정산 행 수 그대로, 이 판매자 행 없음
        assertEquals(lastCutoffBefore, Db.lastSettlementCutoff(), "마지막 마감이 전진하지 않는다")
        assertEquals(settlementsBefore, Db.long("SELECT count(*) FROM settlements"), "정산 행을 만들지 않는다")
        assertTrue(Db2.settlementsOfSeller(seller).isEmpty())
        // 실패한 X는 DELIVERED 그대로, 앞(Z)의 확정은 커밋된 채, 첫 실패에서 멈추므로 뒤(W)는 처리되지 않았다(§15의 1)
        assertEquals("DELIVERED", Db2.item(x.item).status)
        assertNull(Db2.item(x.item).confirmedAt)
        assertEquals("CONFIRMED", Db2.item(z.item).status)
        assertEquals(retros[z.item], Db2.item(z.item).confirmedAt)
        assertEquals("DELIVERED", Db2.item(w.item).status)

        // 원인 제거 뒤: 200, failed = 0(§5), 그 주를 X 포함해 정산
        val r = S2.runBatch("weekly-settlement")
        assertEquals(0L, r.failed(), "weekly-settlement는 200이면 failed = 0: $r")
        for (p in listOf(z, x, w)) {
            val row = Db2.item(p.item)
            assertEquals("CONFIRMED", row.status)
            assertEquals(retros[p.item], row.confirmedAt, "소급 확정 = delivered_at + 7일")
        }
        assertEquals(c, Db.lastSettlementCutoff(), "마감이 c까지 전진")
        val s = Db2.settlementsOfSeller(seller).single()
        assertSettlement(s, Table12.Settle(90_000, 3_600, 0, 0, 86_400, 86_400, 0, "PAID"), Kst.weekStart(retro))
        assertEquals(
            listOf(z.item, x.item, w.item).map { Triple("INCLUDED", it, 30_000L to 1_200L) },
            Db2.settlementItems(s.id).map { Triple(it.status, it.orderItemId, it.amount to it.fee) },
        )
    }

    // ---- 응답 형식 ----

    @Test
    fun `배치 응답 - 2단계 배치는 200 processed·failed 정수, weekly-settlement는 failed = 0`() {
        // grade-recalc는 기준일 00:00(KST)에만 돌린다(§8.1) — 격리 테스트에서 응답 형식을 함께 본다
        for (name in listOf("shipment-deadline", "auto-confirm", "point-expiry")) {
            S2.runBatch(name).failed()
        }
        assertEquals(0L, S2.runBatch("weekly-settlement").failed())
    }
}
