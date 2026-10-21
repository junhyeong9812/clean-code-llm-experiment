package com.ccle.behavior.contract2

import com.ccle.behavior.support.Contract2Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db2
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.SettlementItemRow
import com.ccle.behavior.support.longAt
import com.ccle.behavior.support.textAt
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-0004 §10 주간 정산(weekly-settlement): §12 S1~S4, HELD(주마다)·CLAWBACK·패널티 부착(created_at < cutoff),
 * carry_in < 0이면 대상이 없어도 행, paid_at = 수요일 00:00 KST, 정산 전 auto-confirm, 늦게 돌린 정산, GET /me/settlements.
 *
 * 주·마감은 현재 시계에서 계산한다. 테스트 시작 시각 t0의 주([월요일, 다음 월요일))는 아직 정산되지 않았다
 * (정산된 마지막 마감 ≤ 현재 < t0 주의 마감). 정산은 시드·다른 테스트 판매자도 처리하므로 새 판매자의 행만 단언한다.
 */
@Contract2Test
class SettlementTest {

    private fun item(status: String, orderItemId: Long, amount: Long, fee: Long) = Triple(status, orderItemId, amount to fee)
    private fun rows(items: List<SettlementItemRow>) = items.map { Triple(it.status, it.orderItemId, it.amount to it.fee) }

    /** GET /me/settlements = 판매자의 정산 행(period_start 오름차순) + 항목(orderItemId 오름차순). */
    private fun assertSettlementsBody(seller: Long) {
        val r = S2.settlements(seller).assertStatus(200)
        val db = Db2.settlementsOfSeller(seller)
        val arr = r.json
        assertTrue(arr.isArray, "배열: $r")
        assertEquals(db.size, arr.size(), "정산 수: $r")
        db.forEachIndexed { i, s ->
            val j = arr[i]
            assertEquals(s.id, j.longAt("settlementId"), "[$i] settlementId(period_start 오름차순): $r")
            assertEquals(s.periodStart.toString(), j.textAt("periodStart"), "[$i] periodStart \"YYYY-MM-DD\": $r")
            assertEquals(s.periodEnd.toString(), j.textAt("periodEnd"), "[$i] periodEnd \"YYYY-MM-DD\": $r")
            assertEquals(s.gross, j.longAt("gross"))
            assertEquals(s.feeTotal, j.longAt("feeTotal"))
            assertEquals(s.penaltyTotal, j.longAt("penaltyTotal"))
            assertEquals(s.carryIn, j.longAt("carryIn"))
            assertEquals(s.net, j.longAt("net"))
            assertEquals(s.payout, j.longAt("payout"))
            assertEquals(s.carryOut, j.longAt("carryOut"))
            assertEquals(s.status, j.textAt("status"))
            assertEquals(s.paidAt, j.instantOrNull("paidAt"), "[$i] paidAt: $r")
            val items = Db2.settlementItems(s.id).sortedBy { it.orderItemId }
            val ji = j.get("items")
            assertEquals(items.size, ji?.size() ?: -1, "[$i] items 수: $r")
            items.forEachIndexed { k, it ->
                assertEquals(it.orderItemId, ji[k].longAt("orderItemId"), "[$i].items[$k] orderItemId 오름차순: $r")
                assertEquals(it.amount, ji[k].longAt("amount"))
                assertEquals(it.fee, ji[k].longAt("fee"))
                assertEquals(it.status, ji[k].textAt("status"))
            }
        }
    }

    @Test
    fun `S1 → S4 - SHOES 120,000 + APPAREL 45,000 확정 주 PAID 154,200(수요일 지급), 재실행 무변경, 정산 뒤 분쟁 인정은 다음 주 CLAWBACK·CARRIED −112,800`() {
        val seller = Db.createUser()
        val buyer = Db.createUser()
        val p = S2.purchase(listOf(Line(120_000, "SHOES", seller), Line(45_000, "APPAREL", seller)), buyer)
        p.items.forEach { S2.toConfirmed(it, seller, buyer) }
        val t0 = Flow.now()
        val c1 = Kst.cutoffOf(t0)

        Flow.moveTo(c1)
        S2.runBatch("weekly-settlement")
        val s1 = Db2.settlementsOfSeller(seller).single()
        assertSettlement(s1, Table12.S1, Kst.weekStart(t0))
        assertEquals(
            listOf(item("INCLUDED", p.items[0], 120_000, 7_200), item("INCLUDED", p.items[1], 45_000, 3_600)),
            rows(Db2.settlementItems(s1.id)),
        )
        assertEquals(Kst.at(Kst.weekStart(t0).plusDays(9)), s1.paidAt)
        assertEquals(java.time.DayOfWeek.WEDNESDAY, Kst.date(s1.paidAt!!).dayOfWeek)

        // 같은 주는 다시 정산하지 않는다
        S2.runBatch("weekly-settlement")
        assertEquals(listOf(s1), Db2.settlementsOfSeller(seller))
        assertEquals(2, Db2.settlementItems(s1.id).size)
        assertSettlementsBody(seller)

        // S4: 정산에 포함된 SHOES 120,000이 분쟁 인정 → 다음 정산에서 CLAWBACK
        S2.resolved(S2.openDispute(p.items[0], buyer), true)
        val c2 = c1.plus(days(7))
        Flow.moveTo(c2)
        S2.runBatch("weekly-settlement")
        val all = Db2.settlementsOfSeller(seller)
        assertEquals(2, all.size)
        assertSettlement(all[1], Table12.S4, Kst.date(c1))
        assertEquals(listOf(item("CLAWBACK", p.items[0], -120_000, -7_200)), rows(Db2.settlementItems(all[1].id)))
        assertSettlementsBody(seller)

        // 한 주 더: CLAWBACK은 다시 쓰지 않는다(INCLUDED 수 = CLAWBACK 수), 이월만 있는 행
        val c3 = c2.plus(days(7))
        Flow.moveTo(c3)
        S2.runBatch("weekly-settlement")
        val three = Db2.settlementsOfSeller(seller)
        assertEquals(3, three.size)
        assertSettlement(three[2], Table12.Settle(0, 0, 0, -112_800, -112_800, 0, -112_800, "CARRIED"), Kst.date(c2))
        assertTrue(Db2.settlementItems(three[2].id).isEmpty())
        assertEquals(1, Db2.settlementItemsOfItem(p.items[0]).count { it.status == "CLAWBACK" }, "CLAWBACK은 1회")
    }

    @Test
    fun `S2 → S3 - 패널티만 있는 주 CARRIED −3,000·패널티에 settlement_id, 다음 주 TECH 30,000 확정은 carry_in −3,000 → PAID 25,800`() {
        val seller = Db.createUser()
        val fault = S2.purchase(listOf(Line(30_000, null, seller)))
        val tech = S2.purchase(listOf(Line(30_000, "TECH", seller)))
        S2.toSellerFault(fault.item, seller)
        S2.toInspecting(tech.item, seller)
        val t0 = Flow.now()
        val c1 = Kst.cutoffOf(t0)

        Flow.moveTo(c1)
        S2.runBatch("weekly-settlement")
        val s2 = Db2.settlementsOfSeller(seller).single()
        assertSettlement(s2, Table12.S2, Kst.weekStart(t0))
        assertTrue(Db2.settlementItems(s2.id).isEmpty())
        val pen = Db2.penaltiesOfItem(fault.item).single()
        assertEquals(3_000L, pen.amount, "floor(30,000 × 10%)")
        assertEquals(s2.id, pen.settlementId, "패널티는 그 정산에 붙는다")

        // 다음 주: 마감 정각(c1)에 확정 → 그 주에 INCLUDED
        S2.ready("검수 합격", S2.inspect(tech.item, "PASS"))
        S2.ready("배송 완료", S2.deliver(tech.item))
        S2.ready("구매 확정", S2.confirm(tech.item, tech.buyer))
        val c2 = c1.plus(days(7))
        Flow.moveTo(c2)
        S2.runBatch("weekly-settlement")
        val all = Db2.settlementsOfSeller(seller)
        assertEquals(2, all.size)
        assertSettlement(all[1], Table12.S3, Kst.date(c1))
        assertEquals(listOf(item("INCLUDED", tech.item, 30_000, 1_200)), rows(Db2.settlementItems(all[1].id)))
        assertSettlementsBody(seller)
    }

    @Test
    fun `carry_in lt 0이면 대상이 없어도 그 판매자의 다음 주 행을 만든다(CARRIED, carry_out 그대로 이월)`() {
        val seller = Db.createUser()
        val fault = S2.purchase(listOf(Line(30_000, null, seller)))
        S2.toSellerFault(fault.item, seller)
        val c1 = Kst.cutoffOf(Flow.now())
        Flow.moveTo(c1)
        S2.runBatch("weekly-settlement")
        assertEquals(-3_000L, Db2.settlementsOfSeller(seller).single().carryOut)

        val c2 = c1.plus(days(7))
        Flow.moveTo(c2)
        S2.runBatch("weekly-settlement")
        val all = Db2.settlementsOfSeller(seller)
        assertEquals(2, all.size)
        assertSettlement(all[1], Table12.Settle(0, 0, 0, -3_000, -3_000, 0, -3_000, "CARRIED"), Kst.date(c1))
        assertTrue(Db2.settlementItems(all[1].id).isEmpty())
    }

    @Test
    fun `HELD - 분쟁 중인 확정 항목은 주마다 HELD(0, 0) 행, 기각 뒤 다음 정산에 INCLUDED`() {
        val seller = Db.createUser()
        val p = S2.purchase(listOf(Line(50_000, "BAGS", seller)))  // 수수료 10% = 5,000
        S2.toConfirmed(p.item, seller, p.buyer)
        val disputeId = S2.openDispute(p.item, p.buyer)
        val t0 = Flow.now()
        val c1 = Kst.cutoffOf(t0)
        val zero = Table12.Settle(0, 0, 0, 0, 0, 0, 0, "CARRIED")

        Flow.moveTo(c1)
        S2.runBatch("weekly-settlement")
        val s1 = Db2.settlementsOfSeller(seller).single()
        assertSettlement(s1, zero, Kst.weekStart(t0))
        assertEquals(listOf(item("HELD", p.item, 0, 0)), rows(Db2.settlementItems(s1.id)))

        val c2 = c1.plus(days(7))
        Flow.moveTo(c2)
        S2.runBatch("weekly-settlement")
        val two = Db2.settlementsOfSeller(seller)
        assertEquals(2, two.size)
        assertSettlement(two[1], zero, Kst.date(c1))
        assertEquals(listOf(item("HELD", p.item, 0, 0)), rows(Db2.settlementItems(two[1].id)))

        S2.resolved(disputeId, false)
        val c3 = c2.plus(days(7))
        Flow.moveTo(c3)
        S2.runBatch("weekly-settlement")
        val three = Db2.settlementsOfSeller(seller)
        assertEquals(3, three.size)
        assertSettlement(three[2], Table12.Settle(50_000, 5_000, 0, 0, 45_000, 45_000, 0, "PAID"), Kst.date(c2))
        assertEquals(listOf("HELD", "HELD", "INCLUDED"), Db2.settlementItemsOfItem(p.item).map { it.status })
        assertSettlementsBody(seller)
    }

    @Test
    fun `패널티 부착 - created_at = 마감 시각인 패널티는 그 주가 아니라 다음 주 정산에 붙는다`() {
        val c = Kst.cutoffOf(Flow.now().plus(days(3)))
        Flow.moveTo(c.minus(days(3)))
        val p = S2.purchase(30_000)
        Flow.moveTo(c)
        S2.readyBatch("shipment-deadline")
        val pen = Db2.penaltiesOfItem(p.item).single()
        assertEquals(c, pen.createdAt, "준비(2단계) 패널티 created_at = 마감")

        S2.runBatch("weekly-settlement")
        assertTrue(Db2.settlementsOfSeller(p.seller).isEmpty(), "created_at < cutoff가 아니므로 마감 c의 주에는 없다")
        assertNull(Db2.penaltiesOfItem(p.item).single().settlementId)

        Flow.moveTo(c.plus(days(7)))
        S2.runBatch("weekly-settlement")
        val s = Db2.settlementsOfSeller(p.seller).single()
        assertSettlement(s, Table12.S2, Kst.date(c))
        assertEquals(s.id, Db2.penaltiesOfItem(p.item).single().settlementId)
    }

    @Test
    fun `정산 전 auto-confirm - 배송 완료 + 7일이 지난 항목은 같은 실행에서 소급 확정되어 그 주에 INCLUDED`() {
        val seller = Db.createUser()
        val p = S2.purchase(listOf(Line(120_000, "SHOES", seller)))
        S2.toDelivered(p.item, seller)
        val retro = Db2.item(p.item).deliveredAt!!.plus(days(7))
        val c = Kst.cutoffOf(retro)

        Flow.moveTo(c)
        S2.runBatch("weekly-settlement")
        val row = Db2.item(p.item)
        assertEquals("CONFIRMED", row.status)
        assertEquals(retro, row.confirmedAt)
        val s = Db2.settlementsOfSeller(seller).single()
        assertSettlement(s, Table12.Settle(120_000, 7_200, 0, 0, 112_800, 112_800, 0, "PAID"), Kst.weekStart(retro))
        assertEquals(listOf(item("INCLUDED", p.item, 120_000, 7_200)), rows(Db2.settlementItems(s.id)))
    }

    @Test
    fun `늦게 돌린 정산 - 지난 주도 지금의 상태로 - 마감 때 CONFIRMED였어도 지금 DISPUTED면 두 주 모두 HELD`() {
        val seller = Db.createUser()
        val p = S2.purchase(listOf(Line(45_000, "APPAREL", seller)))
        S2.toConfirmed(p.item, seller, p.buyer)
        val t0 = Flow.now()
        val c1 = Kst.cutoffOf(t0)
        Flow.moveTo(c1)                                            // 이 마감에 정산을 돌리지 않는다
        S2.openDispute(p.item, p.buyer)

        Flow.moveTo(c1.plus(days(7)))
        S2.runBatch("weekly-settlement")
        val all = Db2.settlementsOfSeller(seller)
        assertEquals(listOf(Kst.weekStart(t0), Kst.date(c1)), all.map { it.periodStart })
        val zero = Table12.Settle(0, 0, 0, 0, 0, 0, 0, "CARRIED")
        all.forEach { s ->
            assertSettlement(s, zero, s.periodStart)
            assertEquals(listOf(item("HELD", p.item, 0, 0)), rows(Db2.settlementItems(s.id)))
        }
    }

    @Test
    fun `늦게 돌린 정산 - 이미 분쟁 인정된 항목은 INCLUDED도 CLAWBACK도 없다`() {
        val seller = Db.createUser()
        val p = S2.purchase(listOf(Line(45_000, "APPAREL", seller)))
        S2.toConfirmed(p.item, seller, p.buyer)
        val c1 = Kst.cutoffOf(Flow.now())
        Flow.moveTo(c1)
        S2.resolved(S2.openDispute(p.item, p.buyer), true)

        Flow.moveTo(c1.plus(days(7)))
        S2.runBatch("weekly-settlement")
        assertEquals("REFUNDED_AFTER_CONFIRM", Db2.item(p.item).status)
        assertTrue(Db2.settlementItemsOfItem(p.item).isEmpty())
        assertTrue(Db2.settlementsOfSeller(seller).isEmpty(), "대상·패널티·이월이 없으면 행을 만들지 않는다")
    }

    @Test
    fun `마감 경계 - confirmed_at = 마감 정각이면 그 주에 INCLUDED·HELD가 없고 다음 주에 생긴다`() {
        val included = S2.purchase(listOf(Line(120_000, "SHOES")))
        val held = S2.purchase(listOf(Line(45_000, "APPAREL")))
        S2.toDelivering(included.item, included.seller)
        S2.toDelivering(held.item, held.seller)
        val c1 = Kst.cutoffOf(Flow.now())

        Flow.moveTo(c1)
        for (p in listOf(included, held)) {
            S2.ready("배송 완료", S2.deliver(p.item))
            S2.ready("구매 확정", S2.confirm(p.item, p.buyer))
            assertEquals(c1, Db2.item(p.item).confirmedAt, "준비(2단계) confirmed_at = 마감 정각")
        }
        S2.openDispute(held.item, held.buyer)
        S2.runBatch("weekly-settlement")
        for (p in listOf(included, held)) {
            assertTrue(Db2.settlementItemsOfItem(p.item).isEmpty(), "confirmed_at < cutoff가 아니므로 마감 c1의 주에는 없다")
            assertTrue(Db2.settlementsOfSeller(p.seller).isEmpty())
        }

        Flow.moveTo(c1.plus(days(7)))
        S2.runBatch("weekly-settlement")
        val si = Db2.settlementsOfSeller(included.seller).single()
        assertEquals(Kst.date(c1), si.periodStart)
        assertEquals(listOf(item("INCLUDED", included.item, 120_000, 7_200)), rows(Db2.settlementItems(si.id)))
        val sh = Db2.settlementsOfSeller(held.seller).single()
        assertEquals(Kst.date(c1), sh.periodStart)
        assertEquals(listOf(item("HELD", held.item, 0, 0)), rows(Db2.settlementItems(sh.id)))
    }
}
