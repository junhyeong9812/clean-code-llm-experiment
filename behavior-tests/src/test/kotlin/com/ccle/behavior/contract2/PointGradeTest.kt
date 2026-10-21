package com.ccle.behavior.contract2

import com.ccle.behavior.pg.FakePg
import com.ccle.behavior.support.Contract2Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db2
import com.ccle.behavior.support.Flow
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-0004 §8.1 적립률(이력 기준 3단계 규칙, 늦은 승인 포함), §8.2 포인트 확정(§12 P1), §8.4 회수(§12 P2).
 * 적립 기대값: 체결가 100,000(포인트 0) → 카드 몫 103,000 중 체결가 부분 100,000 → BRONZE 1,000 · SILVER 1,500 · GOLD 2,000 · VIP 3,000.
 * 등급 이력은 ADR §8.1이 정한 대로 테스트가 넣는다(users.grade = 가장 최근 이력, verify user_grade_current).
 */
@Contract2Test
class PointGradeTest {
    private fun earnOf(p: Purchase): Long = Db.earnOfItem(p.item).single().amount

    @Test
    fun `적립률 1 - 요청 날짜 이전 effective_from의 최신 이력 등급을 쓴다(users grade가 아니다)`() {
        val now = Flow.now()
        val user = Db.createUser()
        Db2.addGradeHistory(user, "SILVER", Kst.monthStart(now), 500_000)
        Db2.addGradeHistory(user, "GOLD", Kst.nextMonthStart(now), 2_000_000)   // 요청 날짜 뒤
        assertEquals("GOLD", Db.user(user).grade)

        val p = S2.purchase(listOf(Line(100_000)), user)
        assertEquals(1_500L, earnOf(p), "SILVER 1.5%")
        assertEquals(1_500L, Db.balance(user)!!.pending)
    }

    @Test
    fun `적립률 2·3 - 이력이 모두 요청 날짜 뒤면 BRONZE, 이력이 전혀 없으면 users grade`() {
        val now = Flow.now()
        val future = Db.createUser()
        Db2.addGradeHistory(future, "GOLD", Kst.nextMonthStart(now), 2_000_000)
        assertEquals("GOLD", Db.user(future).grade)
        val noHistory = Db.createUser(grade = "VIP")

        assertEquals(1_000L, earnOf(S2.purchase(listOf(Line(100_000)), future)), "첫 이력 이전 = BRONZE 1%")
        assertEquals(3_000L, earnOf(S2.purchase(listOf(Line(100_000)), noHistory)), "이력 없음 = users.grade(VIP 3%)")
    }

    @Test
    fun `적립률 - 늦은 승인(pg-inquiry)도 주문 생성 날짜의 이력 등급을 쓴다`() {
        val now = Flow.now()
        val user = Db.createUser()
        val nextMonth = Kst.nextMonthStart(now)
        Db2.addGradeHistory(user, "SILVER", Kst.monthStart(now), 500_000)
        Db2.addGradeHistory(user, "GOLD", nextMonth, 2_000_000)
        val trade = Flow.trade(user, Db.createUser(), Db.createProductSize(), 100_000)
        val key = Flow.newKey()
        Flow.pgOutcome(key, FakePg.Outcome.TIMEOUT_APPROVED)
        val orderId = Flow.pay(user, listOf(trade), 0, key).assertStatus(201).long("orderId")
        assertEquals("UNKNOWN", Db.order(orderId).status, "준비(1단계) UNKNOWN")

        Flow.moveTo(Kst.at(nextMonth))           // 승인 반영 시점의 최신 이력은 GOLD
        Flow.runBatch("pg-inquiry")
        assertEquals("PAID", Db.order(orderId).status, "준비(1단계) 재조회 승인")
        val item = Db.orderItems(orderId).single()
        assertEquals(1_500L, Db.earnOfItem(item.id).single().amount, "주문 생성 날짜 기준 SILVER 1.5%")
    }

    @Test
    fun `P1 - E의 2번 항목 확정 → EARN 181 AVAILABLE, expires_at = confirmed_at + 365일, pending −181·available +181`() {
        val buyer = Db.createUser()
        Db.createPointBundle(buyer, 10_000, Flow.now())
        val p = S2.orderE(buyer)
        val before = Db.balance(buyer)!!
        assertEquals(272L + 181L, before.pending)

        S2.toDelivered(p.items[1], p.sellers[1])
        val now = Flow.advance(HOUR)
        assertItemOk(S2.confirm(p.items[1], p.buyer), p.items[1], "CONFIRMED")
        val e = Db.earnOfItem(p.items[1]).single()
        assertEquals("AVAILABLE", e.status)
        assertEquals(181L, e.amount)
        assertEquals(181L, e.remaining)
        assertEquals(now.plus(days(365)), e.expiresAt)
        assertEquals("PENDING", Db.earnOfItem(p.items[0]).single().status)
        val after = Db.balance(buyer)!!
        assertEquals(before.pending - 181L, after.pending)
        assertEquals(before.available + 181L, after.available)
        assertEquals(now, after.updatedAt)
    }

    @Test
    fun `P2 - A의 1번 항목(EARN 1,164) 확정 뒤 다른 주문이 1,000P를 쓰고 분쟁 인정 → REVOKE 164·묶음 0·EARN AVAILABLE, 회수 다음 RESTORE`() {
        val buyer = Db.createUser()
        val fixture = Db.createPointBundle(buyer, 5_000, Flow.now())
        val a = S2.orderA(buyer)
        assertEquals(0L, Db.ledgerEntry(fixture.earnId).remaining, "준비(1단계) 묶음 전부 사용")

        S2.toConfirmed(a.items[0], a.sellers[0], a.buyer)
        val earn = Db.earnOfItem(a.items[0]).single()
        assertEquals(1_164L, earn.amount)
        assertEquals("AVAILABLE", earn.status, "준비(2단계) 확정 → AVAILABLE")

        // 사용 가능 묶음은 EARN 1,164 하나 → 1,000P가 여기서 빠진다(체결가 30,000 → 총액 34,000)
        val other = S2.purchase(listOf(Line(30_000)), buyer, points = 1_000)
        assertEquals(mapOf(earn.id to 1_000L), allocMap(Db.useEntries(other.orderId).single().id), "준비(1단계) FIFO")
        assertEquals(164L, Db.ledgerEntry(earn.id).remaining)

        val disputeId = S2.openDispute(a.items[0], a.buyer)
        val now = Flow.advance(HOUR)
        S2.ready("분쟁 인정", S2.resolve(disputeId, true))

        val revoke = Db2.ledgerOfItem(a.items[0], "REVOKE").single()
        assertEquals("DONE", revoke.status)
        assertEquals(164L, revoke.amount)
        assertEquals(a.orderId, revoke.orderId)
        assertEquals(now, revoke.createdAt)
        assertEquals(mapOf(earn.id to 164L), allocMap(revoke.id))
        val e = Db.ledgerEntry(earn.id)
        assertEquals(0L, e.remaining)
        assertEquals("AVAILABLE", e.status)

        // 분쟁 인정 환불(전액): 포인트 몫 3,636은 그 주문 USE가 빠져나간 묶음(fixture)으로 RESTORE
        val restore = Db2.ledgerOfItem(a.items[0], "RESTORE").single()
        assertEquals(3_636L, restore.amount)
        assertTrue(revoke.id < restore.id, "회수 → 환불(RESTORE) 순서")
        assertEquals(mapOf(fixture.earnId to 3_636L), allocMap(restore.id))
        assertRefund(a.items[0], RefundExp(card = 119_964, point = 3_636, buyerFee = 3_600, shipping = 0, reason = "분쟁 인정", fault = "DISPUTE"), now)
        assertEquals("PARTIALLY_CANCELLED", Db.order(a.orderId).status)
        assertEquals(3_636L, Db.balance(buyer)!!.available)

        // 회수로 0이 된 묶음에도 다른 주문의 복구는 돌아온다(§7.3): 1,000P를 쓴 주문을 취소 → RESTORE 1,000 → remaining 1,000
        Flow.advance(HOUR)
        S2.ready("구매자 취소", S2.cancel(other.item, buyer))
        val back = Db2.ledgerOfItem(other.item, "RESTORE").single()
        assertEquals(1_000L, back.amount)
        assertEquals(mapOf(earn.id to 1_000L), allocMap(back.id))
        assertEquals(1_000L, Db.ledgerEntry(earn.id).remaining)
        assertEquals("AVAILABLE", Db.ledgerEntry(earn.id).status)
    }

    @Test
    fun `회수 - 적립을 전부 써서 remaining = 0이면 분쟁 인정에 REVOKE 행이 없다`() {
        // 체결가 100,000(포인트 0) → EARN 1,000. 확정 뒤 다른 주문이 1,000P를 모두 쓴다
        val buyer = Db.createUser()
        val d = S2.purchase(listOf(Line(100_000)), buyer)
        S2.toConfirmed(d.item, d.seller, buyer)
        val earn = Db.earnOfItem(d.item).single()
        assertEquals(1_000L, earn.amount)
        S2.purchase(listOf(Line(30_000)), buyer, points = 1_000)
        assertEquals(0L, Db.ledgerEntry(earn.id).remaining, "준비(1단계) 적립 전부 사용")

        val disputeId = S2.openDispute(d.item, buyer)
        Flow.advance(HOUR)
        S2.ready("분쟁 인정", S2.resolve(disputeId, true))
        assertEquals("REFUNDED_AFTER_CONFIRM", Db2.item(d.item).status)
        assertTrue(Db2.ledgerOfItem(d.item, "REVOKE").isEmpty(), "남은 양이 없으면 회수 행 없음")
        assertEquals(0L, Db.ledgerEntry(earn.id).remaining)
        assertEquals("AVAILABLE", Db.ledgerEntry(earn.id).status)
        assertEquals(0L, Db.balance(buyer)!!.available)
    }
}
