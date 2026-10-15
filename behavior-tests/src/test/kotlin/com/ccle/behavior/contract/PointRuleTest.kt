package com.ccle.behavior.contract

import com.ccle.behavior.pg.FakePg
import com.ccle.behavior.support.Api
import com.ccle.behavior.support.ContractTest
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Flow
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import kotlin.test.assertEquals

/** ADR-0003 §5.2 포인트 규칙(422 경계), 사용 가능액, FIFO 배분. 예 B(총액 34,000, 상한 17,000P) 기준. */
@ContractTest
class PointRuleTest {
    private val day = Duration.ofDays(1)

    @ParameterizedTest
    @ValueSource(longs = [10, 990, 1_005, 1_001, 17_010, 20_000])
    fun `포인트 규칙 위반 - 422 POINT_RULE_VIOLATION (1,000 미만·10 배수 아님·총액 절반 초과)`(point: Long) {
        val now = Flow.now()
        val buyer = Db.createUser()
        Db.createPointBundle(buyer, 30_000, now.minus(day))
        val trades = tradesFor(buyer, Table8.B)
        Flow.pay(buyer, trades, point).assertError(422, "POINT_RULE_VIOLATION")
        // 위반 요청은 체결을 막지 않는다: 바로 이어 규칙 안의 값으로 결제된다
        Flow.pay(buyer, trades, 1_000).assertStatus(201).also { assertEquals("PAID", it.text("status")) }
    }

    @Test
    fun `포인트 상한 경계 - 총액 34,000이면 17,000P까지 허용`() {
        val now = Flow.now()
        val buyer = Db.createUser()
        Db.createPointBundle(buyer, 30_000, now.minus(day))
        val trades = tradesFor(buyer, Table8.B)
        val r = Flow.pay(buyer, trades, Table8.B_POINT_MAX).assertStatus(201)
        assertEquals("PAID", r.text("status"))
        assertEquals(Table8.B_POINT_MAX, r.long("pointUsed"))
        assertEquals(Table8.B_POINT_MAX, Db.order(r.long("orderId")).pointUsed)
    }

    @Test
    fun `pointToUse 음수 - 400 INVALID_REQUEST`() {
        val buyer = Db.createUser()
        val trades = tradesFor(buyer, Table8.B)
        Flow.pay(buyer, trades, -1_000).assertError(400, "INVALID_REQUEST")
    }

    @Test
    fun `사용 가능액 초과 - 422`() {
        val now = Flow.now()
        val buyer = Db.createUser()
        Db.createPointBundle(buyer, 1_000, now.minus(day))
        val trades = tradesFor(buyer, Table8.B)
        Flow.pay(buyer, trades, 1_010).assertError(422, "POINT_RULE_VIOLATION")
        Flow.pay(buyer, trades, 1_000).assertStatus(201)
    }

    @Test
    fun `포인트가 전혀 없으면 1,000P도 422`() {
        val buyer = Db.createUser()
        val trades = tradesFor(buyer, Table8.B)
        Flow.pay(buyer, trades, 1_000).assertError(422, "POINT_RULE_VIOLATION")
    }

    @Test
    fun `예약 중(UNKNOWN) 포인트는 다른 주문이 쓰지 못한다`() {
        val now = Flow.now()
        val buyer = Db.createUser()
        Db.createPointBundle(buyer, 1_000, now.minus(day))
        val first = tradesFor(buyer, Table8.B)
        val second = tradesFor(buyer, Table8.B)
        val key = Flow.newKey()
        Flow.pgOutcome(key, FakePg.Outcome.TIMEOUT_APPROVED)
        Flow.pay(buyer, first, 1_000, key).assertStatus(201).also { assertEquals("UNKNOWN", it.text("status")) }

        Flow.pay(buyer, second, 1_000).assertError(422, "POINT_RULE_VIOLATION")
        val pts = Api.get("/me/points", buyer).assertStatus(200)
        assertEquals(0L, pts.long("usable"))
        assertEquals(0L, pts.long("available"))
    }

    @Test
    fun `FIFO - 만료가 이른 묶음부터 (expires_at, id) 순으로 배분`() {
        val now = Flow.now()
        val buyer = Db.createUser()
        val late = Db.createPointBundle(buyer, 1_000, now.minus(day))            // id 앞, 만료 늦음
        val early = Db.createPointBundle(buyer, 600, now.minus(day.multipliedBy(2))) // id 뒤, 만료 이름
        val trades = tradesFor(buyer, Table8.B)

        val orderId = Flow.pay(buyer, trades, 1_000).assertStatus(201).long("orderId")
        val use = Db.useEntries(orderId).single()
        val byEarn = Db.allocations(use.id).associate { it.earnEntryId to it.amount }
        assertEquals(mapOf(early.earnId to 600L, late.earnId to 400L), byEarn)
        assertEquals(0L, Db.ledgerEntry(early.earnId).remaining)
        assertEquals(600L, Db.ledgerEntry(late.earnId).remaining)
        assertEquals(600L, Db.balance(buyer)!!.available)
    }

    @Test
    fun `FIFO - 만료 시각이 같으면 id 순`() {
        val now = Flow.now()
        val buyer = Db.createUser()
        val first = Db.createPointBundle(buyer, 600, now.minus(day))
        val second = Db.createPointBundle(buyer, 1_000, now.minus(day))
        val trades = tradesFor(buyer, Table8.B)

        val orderId = Flow.pay(buyer, trades, 1_000).assertStatus(201).long("orderId")
        val byEarn = Db.allocations(Db.useEntries(orderId).single().id).associate { it.earnEntryId to it.amount }
        assertEquals(mapOf(first.earnId to 600L, second.earnId to 400L), byEarn)
    }

    /**
     * 만료 시각이 지난 묶음은 사용 가능액에서 빠진다(소멸 배치 전이라 available 캐시에는 남는다).
     * 묶음 만료 = 구매 확정 + 365일(verify)이므로 시계를 1년 앞으로 옮긴다. 시계는 앞으로만 가므로 이후 테스트에 영향 없음.
     */
    @Test
    fun `사용 가능액 - 만료 시각이 지난 묶음 제외, available 캐시는 포함`() {
        val now = Flow.now()
        val buyer = Db.createUser()
        val old = Db.createPointBundle(buyer, 1_000, now.minus(day))
        val later = Flow.moveTo(old.expiresAt) // expires_at > 현재가 아님 → 사용 불가
        val fresh = Db.createPointBundle(buyer, 1_000, later.minus(day))

        val pts = Api.get("/me/points", buyer).assertStatus(200)
        assertEquals(2_000L, pts.long("available"))
        assertEquals(0L, pts.long("pending"))
        assertEquals(1_000L, pts.long("usable"))

        val trades = tradesFor(buyer, Table8.B)
        Flow.pay(buyer, trades, 1_010).assertError(422, "POINT_RULE_VIOLATION")
        val orderId = Flow.pay(buyer, trades, 1_000).assertStatus(201).long("orderId")
        val alloc = Db.allocations(Db.useEntries(orderId).single().id).single()
        assertEquals(fresh.earnId, alloc.earnEntryId)
        assertEquals(1_000L, Db.ledgerEntry(old.earnId).remaining)
    }
}
