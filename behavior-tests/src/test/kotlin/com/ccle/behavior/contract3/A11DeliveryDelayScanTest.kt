package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.S2
import com.ccle.behavior.contract2.SEC
import com.ccle.behavior.contract2.days
import com.ccle.behavior.support.Contract3Test
import com.ccle.behavior.support.Db2
import com.ccle.behavior.support.FailTrigger
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.failed
import com.ccle.behavior.support.instantAt
import com.ccle.behavior.support.textAt
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-0007 §12.2 배치 `delivery-delay-scan`: SHIPMENT_OVERDUE(PAID, paid_at + 3일 ≤ 현재) → DELIVERY_LATE(DELIVERING, inspected_at + 5일 ≤ 현재)
 * 차례로, (항목, kind)당 1행(detected_at = 현재), 항목 상태 무변경, 재실행 무중복, 조회 delays에 반영, 대상 1건 실패 격리(§2.6).
 * shipment-deadline은 돌리지 않는다(그 배치가 아직 돌지 않은 항목이 대상이다).
 */
@Contract3Test
class A11DeliveryDelayScanTest {

    private fun run() = S2.runBatch("delivery-delay-scan")

    private fun assertDelay(item: Long, kind: String, at: Instant) {
        val rows = SB.delays(item)
        assertEquals(1, rows.size, "항목 $item 지연 1행: $rows")
        assertEquals(kind, rows.single().text("kind"))
        assertEquals(at, rows.single().instant("detected_at"), "detected_at = 현재")
    }

    @Test
    fun `SHIPMENT_OVERDUE - 결제 + 3일 정각 포함·1초 전 미포함, 발송된 항목 제외, 항목 PAID 그대로, 재실행 무중복, 조회 delays`() {
        val a = S2.purchase(100_000)
        val shipped = S2.purchase(100_000).also { S2.toShipped(it.item, it.seller) }
        Flow.advance(SEC)
        val b = S2.purchase(100_000)
        assertEquals(a.paidAt.plus(SEC), b.paidAt)

        val now = Flow.moveTo(a.paidAt.plus(days(3)))
        run()
        assertDelay(a.item, "SHIPMENT_OVERDUE", now)
        assertTrue(SB.delays(b.item).isEmpty(), "결제 + 3일 1초 전은 대상 아님")
        assertTrue(SB.delays(shipped.item).isEmpty(), "발송된 항목은 대상 아님")
        assertEquals("PAID", Db2.item(a.item).status, "항목 상태 무변경")
        assertEquals(null, Db2.item(a.item).cancelledAt)

        val r = SB.tracking(a.item, a.buyer).assertStatus(200)
        val d = r.json.get("delays")
        assertEquals(1, d.size(), "delays: $r")
        assertEquals("SHIPMENT_OVERDUE", d[0].textAt("kind"))
        assertEquals(now, d[0].instantAt("detectedAt"))

        run()
        assertDelay(a.item, "SHIPMENT_OVERDUE", now)

        val later = Flow.advance(SEC)
        run()
        assertDelay(b.item, "SHIPMENT_OVERDUE", later)
    }

    @Test
    fun `DELIVERY_LATE - 검수 합격 + 5일 정각 포함·1초 전 미포함, DELIVERED 제외, DELIVERING 그대로(SHIPMENT_OVERDUE 아님), 재실행 무중복`() {
        val x = S2.purchase(100_000).also { S2.toDelivering(it.item, it.seller) }
        val done = S2.purchase(100_000).also { S2.toDelivered(it.item, it.seller) }
        Flow.advance(SEC)
        val y = S2.purchase(100_000).also { S2.toDelivering(it.item, it.seller) }
        val inspX = Db2.item(x.item).inspectedAt!!
        assertEquals(inspX.plus(SEC), Db2.item(y.item).inspectedAt)

        val now = Flow.moveTo(inspX.plus(days(5)))
        run()
        assertDelay(x.item, "DELIVERY_LATE", now)
        assertTrue(SB.delays(y.item).isEmpty(), "검수 + 5일 1초 전은 대상 아님")
        assertTrue(SB.delays(done.item).isEmpty(), "DELIVERED 항목은 대상 아님")
        assertEquals("DELIVERING", Db2.item(x.item).status)

        run()
        assertEquals(1, SB.delays(x.item).size)

        val later = Flow.advance(SEC)
        run()
        assertDelay(y.item, "DELIVERY_LATE", later)
    }

    @Test
    fun `두 종류를 차례로 - id가 작은 DELIVERY_LATE 항목보다 SHIPMENT_OVERDUE 행이 먼저 쓰인다`() {
        val late = S2.purchase(100_000).also { S2.toDelivering(it.item, it.seller) }
        val overdue = S2.purchase(100_000)
        assertTrue(late.item < overdue.item)
        Flow.moveTo(maxOf(Db2.item(late.item).inspectedAt!!.plus(days(5)), overdue.paidAt.plus(days(3))))
        run()
        val o = SB.delays(overdue.item).single()
        val l = SB.delays(late.item).single()
        assertEquals("SHIPMENT_OVERDUE", o.text("kind"))
        assertEquals("DELIVERY_LATE", l.text("kind"))
        assertTrue(o.id < l.id, "SHIPMENT_OVERDUE를 먼저 훑는다: $o, $l")
    }

    @Test
    fun `§2_6 격리 - A 지연 행 INSERT가 실패해도 B는 쓰고 failed ≥ 1, 다시 돌리면 A 1행`() {
        val a = S2.purchase(100_000)
        val b = S2.purchase(100_000)
        assertTrue(a.item < b.item)
        val now = Flow.moveTo(b.paidAt.plus(days(3)))
        FailTrigger.on("shipment_delays", "INSERT", "NEW.order_item_id = ${a.item}") {
            val r = run()
            assertTrue(r.failed() >= 1, "A 실패를 failed에 센다: $r")
            assertTrue(r.long("processed") >= 1, "B는 커밋된다: $r")
        }
        assertTrue(SB.delays(a.item).isEmpty())
        assertDelay(b.item, "SHIPMENT_OVERDUE", now)

        val later = Flow.advance(SEC)
        run()
        assertDelay(a.item, "SHIPMENT_OVERDUE", later)
        assertDelay(b.item, "SHIPMENT_OVERDUE", now)
    }
}
