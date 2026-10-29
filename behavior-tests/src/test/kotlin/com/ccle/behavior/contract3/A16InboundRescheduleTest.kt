package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.HOUR
import com.ccle.behavior.contract2.Line
import com.ccle.behavior.contract2.S2
import com.ccle.behavior.contract2.SEC
import com.ccle.behavior.support.Api
import com.ccle.behavior.support.Contract3Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db2
import com.ccle.behavior.support.Db3
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.Resp
import com.ccle.behavior.support.SlowTrigger
import com.ccle.behavior.support.longAt
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-0007 §20.5 A16 입고 예약 변경 `POST /me/inbound-reservations/{id}/reschedule` `{"slotId"}`:
 * 판정 순서 401 → 400(slotId 누락·정수 아님) → 404(예약) → 404(새 슬롯) → 403(예약의 seller_id 아님) →
 * 409 1(항목 PAID 아님) → 2(예약 RESERVED 아님) → 3(같은 슬롯) → 4(새 슬롯 정원) → 5(현재 > 옛 slot − 2시간 DEADLINE_PASSED) → 6(현재 > 새 slot − 24시간 DEADLINE_PASSED),
 * 결과(옛 예약 CANCELLED·closed_at = 현재 + 새 예약 RESERVED 1행, 응답 200 {"cancelled", "reserved"}), 감사 로그 없음(§20.1),
 * 상세 조회 `GET /inbound-slots/{id}`(헤더 불필요 — §19의 7)·`GET /me/inbound-reservations/{id}`(§20.5.2),
 * 동시성(§20.12: 변경과 같은 슬롯의 예약 — 정원 1)·잠금 순서(§20.8: 주문 FU 먼저).
 */
@Contract3Test
class A16InboundRescheduleTest {

    private val DAY_: Duration = Duration.ofDays(1)

    /** 예약·변경 기한(슬롯 − 24시간)에 여유를 둔 새 슬롯 시각: after + 2일 이후 첫 빈 정시. */
    private fun futureSlotStart(after: Instant = Flow.now()): Instant = SC.freeHourAtOrAfter(after.plus(DAY_.multipliedBy(2)))

    /** start 이상 첫 빈 정시에 슬롯을 만든다. */
    private fun slotAt(start: Instant, capacity: Int = 5): Long = SC.newSlot(SC.freeHourAtOrAfter(start), capacity)

    private fun itemRows(item: Long) = Db3.rows("inbound_reservations", "order_item_id = ?", item)

    @Test
    fun `§20_5_1 변경 - 200 {cancelled 옛 예약(CANCELLED·closed_at = 현재), reserved 새 예약(RESERVED·새 슬롯·같은 seller·항목·created_at = 현재)}, 슬롯 reserved 이동, 항목 PAID 그대로, 감사 로그 없음`() {
        val seller = Db.createUser()
        val p = S2.purchase(listOf(Line(30_000, seller = seller)))
        val oldSlot = slotAt(futureSlotStart())
        val newSlot = slotAt(SDB.slotStart(oldSlot).plus(HOUR))
        val old = SC.readyReserve(seller, oldSlot, p.item)
        val now = Flow.advance(HOUR)
        val audits = SC.maxAuditId()

        val r = SDB.reschedule(old, seller, newSlot).assertStatus(200)
        val c = SC.assertReservation(SDB.part(r, "cancelled"), old, r)
        assertEquals("CANCELLED", c.text("status"))
        assertEquals(now, c.instant("closed_at"), "옛 예약 closed_at = 현재")
        assertEquals(oldSlot, c.long("slot_id"), "옛 예약의 슬롯은 그대로")
        val newId = SDB.part(r, "reserved").longAt("reservationId")
        assertTrue(newId != old, "새 예약 행: $r")
        val n = SC.assertReservation(SDB.part(r, "reserved"), newId, r)
        assertEquals("RESERVED", n.text("status"))
        assertEquals(newSlot, n.long("slot_id"))
        assertEquals(seller, n.long("seller_id"))
        assertEquals(p.item, n.long("order_item_id"))
        assertEquals(now, n.instant("created_at"), "새 예약 created_at = 현재")
        assertNull(n.instantOrNull("closed_at"))

        assertEquals(0L, SDB.live(oldSlot))
        assertEquals(1L, SDB.live(newSlot))
        assertEquals(listOf(old, newId), itemRows(p.item).map { it.id }, "항목의 예약 = 옛(CANCELLED) + 새(RESERVED)")
        assertEquals("PAID", Db2.item(p.item).status, "변경은 항목 상태를 바꾸지 않는다")
        assertEquals(audits, SC.maxAuditId(), "변경은 감사 로그를 쓰지 않는다(§20.1)")
        val mine = SC.array(Api.get("/me/inbound-reservations", seller).assertStatus(200))
        assertEquals(listOf(old, newId), mine.map { it.longAt("reservationId") })

        // 새 예약은 다시 변경할 수 있고(옛 슬롯으로 돌아가도 된다), 취소된 옛 예약은 409
        Flow.advance(SEC)
        SDB.reschedule(old, seller, newSlot).assertError(409, "ITEM_STATE_CONFLICT")
        val back = SDB.reschedule(newId, seller, oldSlot).assertStatus(200)
        assertEquals("CANCELLED", SC.reservationRow(newId).text("status"))
        assertEquals(oldSlot, SC.reservationRow(SDB.part(back, "reserved").longAt("reservationId")).long("slot_id"))
        assertEquals(1L, Db3.count("inbound_reservations", "order_item_id = ? AND status IN ('RESERVED','CHECKED_IN')", p.item))
    }

    @Test
    fun `§20_5_1 판정 순서 - 401 → 400(slotId 누락·null·문자열·5．0) → 404 예약 → 404 새 슬롯 → 403(seller_id 아님 — 구매자·제3자, 취소된 예약이어도)`() {
        val p = S2.purchase(30_000)
        val stranger = Db.createUser()
        val oldSlot = slotAt(futureSlotStart())
        val newSlot = slotAt(SDB.slotStart(oldSlot).plus(HOUR))
        val res = SC.readyReserve(p.seller, oldSlot, p.item)
        val missingRes = Db.missingId("inbound_reservations")
        val missingSlot = Db.missingId("inbound_slots")

        SDB.reschedule(res, null, "x").assertError(401, "UNKNOWN_USER")
        SDB.reschedule(res, "abc", newSlot).assertError(401, "UNKNOWN_USER")
        SDB.reschedule(res, Db.missingId("users"), newSlot).assertError(401, "UNKNOWN_USER")

        SDB.reschedule(res, p.seller, null).assertError(400, "INVALID_REQUEST")
        SDB.rescheduleRaw(res, p.seller, "{}").assertError(400, "INVALID_REQUEST")       // §19의 1
        SDB.reschedule(res, p.seller, "a").assertError(400, "INVALID_REQUEST")
        SDB.reschedule(res, p.seller, newSlot.toString()).assertError(400, "INVALID_REQUEST")   // §19의 2: 정수 JSON 숫자만
        SDB.reschedule(res, p.seller, 5.0).assertError(400, "INVALID_REQUEST")
        SDB.reschedule(missingRes, stranger, null).assertError(400, "INVALID_REQUEST")   // 400이 404보다 먼저

        SDB.reschedule(missingRes, p.seller, newSlot).assertError(404, "NOT_FOUND")
        SDB.reschedule(missingRes, stranger, missingSlot).assertError(404, "NOT_FOUND")
        SDB.reschedule(res, p.seller, missingSlot).assertError(404, "NOT_FOUND")
        SDB.reschedule(res, stranger, missingSlot).assertError(404, "NOT_FOUND")        // 404(새 슬롯)가 403보다 먼저
        SDB.reschedule(res, stranger, newSlot).assertError(403, "NOT_OWNER")
        SDB.reschedule(res, p.buyer, newSlot).assertError(403, "NOT_OWNER")
        assertEquals("RESERVED", SC.reservationRow(res).text("status"))
        assertEquals(listOf(res), itemRows(p.item).map { it.id }, "실패한 요청은 행을 쓰지 않는다")

        SC.cancelReservation(res, p.seller).assertStatus(200)
        SDB.reschedule(res, stranger, newSlot).assertError(403, "NOT_OWNER")             // 403이 409보다 먼저
        SDB.reschedule(res, p.seller, newSlot).assertError(409, "ITEM_STATE_CONFLICT")
    }

    @Test
    fun `§20_5_1 409 ITEM_STATE_CONFLICT - 1 항목 PAID 아님(발송), 2 예약 RESERVED 아님(취소), 3 같은 슬롯, 4 새 슬롯 정원 참(취소된 예약은 세지 않는다) - 실패는 행 무변경`() {
        val oldSlot = slotAt(futureSlotStart())
        val newSlot = slotAt(SDB.slotStart(oldSlot).plus(HOUR))
        val full = slotAt(SDB.slotStart(newSlot).plus(HOUR), capacity = 1)
        val shipped = S2.purchase(30_000)
        val cancelled = S2.purchase(30_000)
        val same = S2.purchase(30_000)
        val toFull = S2.purchase(30_000)
        val occupant = S2.purchase(30_000)
        val rShipped = SC.readyReserve(shipped.seller, oldSlot, shipped.item)
        val rCancelled = SC.readyReserve(cancelled.seller, oldSlot, cancelled.item)
        val rSame = SC.readyReserve(same.seller, oldSlot, same.item)
        val rToFull = SC.readyReserve(toFull.seller, oldSlot, toFull.item)
        val rOccupant = SC.readyReserve(occupant.seller, full, occupant.item)
        S2.toShipped(shipped.item, shipped.seller)
        assertEquals("RESERVED", SC.reservationRow(rShipped).text("status"), "준비: 발송해도 예약은 RESERVED")
        SC.cancelReservation(rCancelled, cancelled.seller).assertStatus(200)

        SDB.reschedule(rShipped, shipped.seller, newSlot).assertError(409, "ITEM_STATE_CONFLICT")
        SDB.reschedule(rCancelled, cancelled.seller, newSlot).assertError(409, "ITEM_STATE_CONFLICT")
        SDB.reschedule(rSame, same.seller, oldSlot).assertError(409, "ITEM_STATE_CONFLICT")
        SDB.reschedule(rToFull, toFull.seller, full).assertError(409, "ITEM_STATE_CONFLICT")
        assertEquals(listOf("RESERVED", "CANCELLED", "RESERVED", "RESERVED"),
            listOf(rShipped, rCancelled, rSame, rToFull).map { SC.reservationRow(it).text("status") })
        for (p in listOf(shipped, same, toFull)) assertEquals(1, itemRows(p.item).size, "실패한 변경은 새 예약을 쓰지 않는다")
        assertEquals(1L, SDB.live(full))

        // 정원은 살아 있는 예약만 센다
        SC.cancelReservation(rOccupant, occupant.seller).assertStatus(200)
        SDB.reschedule(rToFull, toFull.seller, full).assertStatus(200)
        assertEquals(1L, SDB.live(full))
    }

    @Test
    fun `§20_5_1 기한 5 - 현재 = 옛 slot − 2시간은 200, 1초 뒤는 409 DEADLINE_PASSED(새 슬롯은 기한 안)`() {
        val oldSlot = slotAt(futureSlotStart())
        val oldStart = SDB.slotStart(oldSlot)
        val newSlot = slotAt(oldStart.plus(Duration.ofDays(2)))
        val a = S2.purchase(30_000)
        val b = S2.purchase(30_000)
        val ra = SC.readyReserve(a.seller, oldSlot, a.item)
        val rb = SC.readyReserve(b.seller, oldSlot, b.item)

        val at = Flow.moveTo(oldStart.minus(Duration.ofHours(2)))
        val r = SDB.reschedule(ra, a.seller, newSlot).assertStatus(200)
        assertEquals(at, SC.reservationRow(ra).instant("closed_at"))
        assertEquals(newSlot, SC.reservationRow(SDB.part(r, "reserved").longAt("reservationId")).long("slot_id"))
        Flow.moveTo(at.plus(SEC))
        SDB.reschedule(rb, b.seller, newSlot).assertError(409, "DEADLINE_PASSED")
        assertEquals("RESERVED", SC.reservationRow(rb).text("status"))
        assertEquals(oldSlot, SC.reservationRow(rb).long("slot_id"))
        assertEquals(1, itemRows(b.item).size)
    }

    @Test
    fun `§20_5_1 기한 6 - 현재 = 새 slot − 24시간은 200, 1초 뒤는 409 DEADLINE_PASSED(옛 슬롯은 기한 안)`() {
        val newSlot = slotAt(futureSlotStart())
        val newStart = SDB.slotStart(newSlot)
        val oldSlot = slotAt(newStart.plus(Duration.ofDays(3)))
        val a = S2.purchase(30_000)
        val b = S2.purchase(30_000)
        val ra = SC.readyReserve(a.seller, oldSlot, a.item)
        val rb = SC.readyReserve(b.seller, oldSlot, b.item)

        Flow.moveTo(newStart.minus(DAY_))
        SDB.reschedule(ra, a.seller, newSlot).assertStatus(200)
        Flow.moveTo(newStart.minus(DAY_).plus(SEC))
        SDB.reschedule(rb, b.seller, newSlot).assertError(409, "DEADLINE_PASSED")
        assertEquals("RESERVED", SC.reservationRow(rb).text("status"))
        assertEquals(1, itemRows(b.item).size)
        assertEquals(1L, SDB.live(newSlot))
    }

    @Test
    fun `§20_5_1·§20_1 상태 위반이 기한 위반보다 먼저 - 두 기한이 다 지났어도 PAID 아님·RESERVED 아님·같은 슬롯·정원은 ITEM_STATE_CONFLICT, 상태가 맞으면 DEADLINE_PASSED`() {
        val oldSlot = slotAt(futureSlotStart())
        val oldStart = SDB.slotStart(oldSlot)
        val newSlot = slotAt(oldStart.plus(HOUR))
        val full = slotAt(SDB.slotStart(newSlot).plus(HOUR), capacity = 1)
        val shipped = S2.purchase(30_000)
        val cancelled = S2.purchase(30_000)
        val same = S2.purchase(30_000)
        val toFull = S2.purchase(30_000)
        val ok = S2.purchase(30_000)
        val occupant = S2.purchase(30_000)
        val rShipped = SC.readyReserve(shipped.seller, oldSlot, shipped.item)
        val rCancelled = SC.readyReserve(cancelled.seller, oldSlot, cancelled.item)
        val rSame = SC.readyReserve(same.seller, oldSlot, same.item)
        val rToFull = SC.readyReserve(toFull.seller, oldSlot, toFull.item)
        val rOk = SC.readyReserve(ok.seller, oldSlot, ok.item)
        SC.readyReserve(occupant.seller, full, occupant.item)
        S2.toShipped(shipped.item, shipped.seller)
        SC.cancelReservation(rCancelled, cancelled.seller).assertStatus(200)

        Flow.moveTo(oldStart.minus(Duration.ofHours(2)).plus(SEC))      // 5(옛 − 2시간)와 6(새 − 24시간)이 모두 지남
        SDB.reschedule(rShipped, shipped.seller, newSlot).assertError(409, "ITEM_STATE_CONFLICT")
        SDB.reschedule(rCancelled, cancelled.seller, newSlot).assertError(409, "ITEM_STATE_CONFLICT")
        SDB.reschedule(rSame, same.seller, oldSlot).assertError(409, "ITEM_STATE_CONFLICT")
        SDB.reschedule(rToFull, toFull.seller, full).assertError(409, "ITEM_STATE_CONFLICT")
        SDB.reschedule(rOk, ok.seller, newSlot).assertError(409, "DEADLINE_PASSED")
        assertEquals(0L, SDB.live(newSlot))
    }

    // ---- §20.5.2 상세 조회 ----

    @Test
    fun `§20_5_2 슬롯 상세 - 슬롯 모양(reserved = 살아 있는 예약 수), 헤더 불필요(잘못된 X-User-Id도 200 — §19의 7), 없는 슬롯 404`() {
        val slot = slotAt(futureSlotStart(), capacity = 7)
        val r0 = SDB.slotDetail(slot).assertStatus(200)
        SDB.assertSlot(r0.json, slot, r0)
        assertEquals(0L, r0.long("reserved"))
        val a = S2.purchase(30_000)
        val b = S2.purchase(30_000)
        val ra = SC.readyReserve(a.seller, slot, a.item)
        SC.readyReserve(b.seller, slot, b.item)
        SC.cancelReservation(ra, a.seller).assertStatus(200)
        val r1 = SDB.slotDetail(slot, "abc").assertStatus(200)
        SDB.assertSlot(r1.json, slot, r1)
        assertEquals(1L, r1.long("reserved"), "취소된 예약은 세지 않는다")
        assertEquals(7L, r1.long("capacity"))
        SDB.slotDetail(slot, Db.missingId("users")).assertStatus(200)
        SDB.slotDetail(Db.missingId("inbound_slots")).assertError(404, "NOT_FOUND")
    }

    @Test
    fun `§20_5_2 예약 상세 - 본인 예약 200 예약 모양, 남의 예약 403, 없는 예약 404(404가 403보다 먼저), 헤더 없음·없는 사용자 401`() {
        val p = S2.purchase(30_000)
        val stranger = Db.createUser()
        val slot = slotAt(futureSlotStart())
        val id = SC.readyReserve(p.seller, slot, p.item)
        val r = SDB.myReservation(id, p.seller).assertStatus(200)
        assertEquals("RESERVED", SC.assertReservation(r.json, id, r).text("status"))
        SC.cancelReservation(id, p.seller).assertStatus(200)
        val r2 = SDB.myReservation(id, p.seller).assertStatus(200)
        assertEquals("CANCELLED", SC.assertReservation(r2.json, id, r2).text("status"))

        SDB.myReservation(id, stranger).assertError(403, "NOT_OWNER")
        SDB.myReservation(id, p.buyer).assertError(403, "NOT_OWNER")
        SDB.myReservation(Db.missingId("inbound_reservations"), stranger).assertError(404, "NOT_FOUND")
        SDB.myReservation(id, null).assertError(401, "UNKNOWN_USER")
        SDB.myReservation(id, "abc").assertError(401, "UNKNOWN_USER")
        SDB.myReservation(id, Db.missingId("users")).assertError(401, "UNKNOWN_USER")
    }

    // ---- 동시성 · 잠금 순서 ----

    @Test
    fun `§20_12·§20_8 변경과 같은 슬롯(정원 1)의 새 예약이 동시 - 교착 없이 하나만 성공, 다른 하나는 409 ITEM_STATE_CONFLICT, 살아 있는 예약 1`() {
        val oldSlot = slotAt(futureSlotStart())
        val target = slotAt(SDB.slotStart(oldSlot).plus(HOUR), capacity = 1)
        val a = S2.purchase(30_000)
        val b = S2.purchase(30_000)
        val ra = SC.readyReserve(a.seller, oldSlot, a.item)
        // 이긴 요청이 새 슬롯 행을 잠근 채 INSERT에서 멈춘다 → 진 요청은 슬롯 잠금 뒤 정원을 본다(경합 관측)
        val rs = SlowTrigger.on("inbound_reservations", "INSERT", condition = "NEW.slot_id = $target") {
            SC.concurrently(2) { i -> if (i == 0) SDB.reschedule(ra, a.seller, target) else SC.reserve(b.seller, target, b.item) }
        }
        val (change, reserve) = rs
        assertTrue(change.status in setOf(200, 409) && reserve.status in setOf(201, 409), "교착(400 UNEXPECTED_ERROR) 없이 계약 응답만: $rs")
        assertEquals(1, listOf(change.status == 200, reserve.status == 201).count { it }, "하나만 성공: $rs")
        rs.filter { it.status == 409 }.single().assertError(409, "ITEM_STATE_CONFLICT")
        assertEquals(1L, SDB.live(target))
        if (change.status == 200) {
            assertEquals(0, itemRows(b.item).size, "진 예약은 행을 쓰지 않는다")
        } else {
            assertEquals("RESERVED", SC.reservationRow(ra).text("status"), "진 변경은 옛 예약을 그대로 둔다")
            assertEquals(listOf(ra), itemRows(a.item).map { it.id })
        }
    }

    @Test
    fun `§2_3 같은 예약의 동시 변경 2건(다른 슬롯) - 하나만 200, 다른 하나는 409 ITEM_STATE_CONFLICT, 항목의 살아 있는 예약 1·행 2`() {
        val oldSlot = slotAt(futureSlotStart())
        val s1 = slotAt(SDB.slotStart(oldSlot).plus(HOUR))
        val s2 = slotAt(SDB.slotStart(s1).plus(HOUR))
        val p = S2.purchase(30_000)
        val res = SC.readyReserve(p.seller, oldSlot, p.item)
        val slots = listOf(s1, s2)
        // 이긴 요청이 옛 예약 UPDATE에서 멈춘다 → 진 요청은 주문 행 잠금을 기다린 뒤 CANCELLED를 본다(경합 관측)
        val rs = SlowTrigger.on("inbound_reservations", "UPDATE", condition = "OLD.id = $res") {
            SC.concurrently(2) { i -> SDB.reschedule(res, p.seller, slots[i]) }
        }
        assertEquals(1, rs.count { it.status == 200 }, "하나만 성공: $rs")
        rs.single { it.status != 200 }.assertError(409, "ITEM_STATE_CONFLICT")
        assertEquals(2, itemRows(p.item).size, "옛 1 + 새 1")
        assertEquals(1L, Db3.count("inbound_reservations", "order_item_id = ? AND status IN ('RESERVED','CHECKED_IN')", p.item))
        assertEquals("CANCELLED", SC.reservationRow(res).text("status"))
    }

    /**
     * §20.8: 변경은 주문 FU를 먼저 쥔다(주문 → 예약 → 새 슬롯 → INSERT의 FK KS). 변경이 INSERT에서 멈춘 사이 같은 항목의 구매자 취소
     * (주문 → 항목 FOR UPDATE → 사용자)가 오면 취소는 주문 행에서 기다린다 — 교착 없이 변경 200·취소 200.
     */
    @Test
    fun `§20_8 변경과 같은 항목의 구매자 취소가 겹쳐도 교착 없이 둘 다 성공`() {
        val oldSlot = slotAt(futureSlotStart())
        val newSlot = slotAt(SDB.slotStart(oldSlot).plus(HOUR))
        val p = S2.purchase(30_000)
        val res = SC.readyReserve(p.seller, oldSlot, p.item)
        val rs = SlowTrigger.on("inbound_reservations", "INSERT", condition = "NEW.order_item_id = ${p.item}") {
            val pool = Executors.newSingleThreadExecutor()
            try {
                val change = pool.submit<Resp> { SDB.reschedule(res, p.seller, newSlot) }
                SDA.awaitTriggerWait()   // 변경이 트리거(advisory 잠금)에서 멈춘 것을 관측한 뒤 취소를 보낸다
                val cancel = S2.cancel(p.item, p.buyer)
                listOf(change.get(30, TimeUnit.SECONDS), cancel)
            } finally {
                pool.shutdownNow()
            }
        }
        rs[0].assertStatus(200)
        rs[1].assertStatus(200)
        assertEquals("CANCELLED_BY_BUYER", Db2.item(p.item).status)
        assertEquals(1L, SDB.live(newSlot))
    }
}
