package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.HOUR
import com.ccle.behavior.contract2.Kst
import com.ccle.behavior.contract2.S2
import com.ccle.behavior.contract2.SEC
import com.ccle.behavior.support.Api
import com.ccle.behavior.support.Contract3Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db2
import com.ccle.behavior.support.Db3
import com.ccle.behavior.support.FailTrigger
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.SlowTrigger
import com.ccle.behavior.support.failed
import com.ccle.behavior.support.instantAt
import com.ccle.behavior.support.longAt
import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-0007 §12.3 A12 입고 예약: 슬롯 생성(정시·미래·정원 1~100, 같은 시각 409, 감사 로그 INBOUND_SLOT_CREATE)·슬롯 조회(KST 날짜),
 * 예약(판정 1 상태 → 2 정원 → 3 기한 슬롯 − 24시간)·취소(슬롯 − 2시간)·체크인(창 [slot, slot + 1시간), 감사 로그 INBOUND_CHECK_IN)·내 예약 조회,
 * 배치 inbound-no-show(슬롯 + 1시간 ≤ 현재).
 */
@Contract3Test
class A12InboundTest {

    private val DAY_ = Duration.ofDays(1)

    /** 예약 가능한 새 슬롯 시각: 현재 + 25시간 이후 첫 빈 정시. */
    private fun futureSlotStart(after: Instant = Flow.now()): Instant = SC.freeHourAtOrAfter(after.plus(DAY_).plus(HOUR))

    private fun live(slotId: Long): Long = Db3.count("inbound_reservations", "slot_id = ? AND status IN ('RESERVED','CHECKED_IN')", slotId)

    private fun assertSlot(j: JsonNode, slotId: Long, ctx: Any) {
        val row = Db3.row("inbound_slots", slotId)
        for (f in listOf("slotId", "slotStart", "capacity", "reserved")) assertTrue(j.has(f), "슬롯 모양 필드 '$f' 없음: $ctx")
        assertEquals(slotId, j.longAt("slotId"), "slotId: $ctx")
        assertEquals(row.instant("slot_start"), j.instantAt("slotStart"), "slotStart: $ctx")
        assertEquals(row.long("capacity"), j.longAt("capacity"), "capacity: $ctx")
        assertEquals(live(slotId), j.longAt("reserved"), "reserved = RESERVED·CHECKED_IN 수: $ctx")
    }

    // ---- 슬롯 생성 ----

    @Test
    fun `§12_3 슬롯 생성 - 201 슬롯 모양(reserved 0), 정원 1·100 경계, 감사 로그 INBOUND_SLOT_CREATE(target = 슬롯 id, detail null)`() {
        val now = Flow.now()
        val s1 = SC.freeHourAtOrAfter(now.plus(Duration.ofDays(2)))
        val s2 = SC.freeHourAtOrAfter(s1.plus(HOUR))
        for ((start, cap) in listOf(s1 to 1, s2 to 100)) {
            val r = SC.createSlot(start, cap).assertStatus(201)
            val id = r.long("slotId")
            assertSlot(r.json, id, r)
            assertEquals(0L, r.long("reserved"))
            val row = Db3.row("inbound_slots", id)
            assertEquals(start, row.instant("slot_start"))
            assertEquals(cap.toLong(), row.long("capacity"))
            assertEquals(now, row.instant("created_at"))
            SC.assertAudit("INBOUND_SLOT_CREATE", id, 1, now, null)
        }
    }

    @Test
    fun `§12_3 슬롯 생성 400 - 정시 아님(분·초), 현재 이하(현재 정시 포함), 정원 0·101·없음·정수 아님(문자열·5．0), slotStart 없음·형식 오류·오프셋 없음 - 행·감사 로그 없음`() {
        val h = Flow.now().truncatedTo(ChronoUnit.HOURS).plus(HOUR)
        val now = Flow.moveTo(h)                                        // 현재 = 정시
        val auditBefore = SC.maxAuditId()
        val slotsBefore = Db3.count("inbound_slots")
        val future = SC.freeHourAtOrAfter(now.plus(Duration.ofDays(3)))
        SC.createSlot(future.plus(Duration.ofMinutes(30))).assertError(400, "INVALID_REQUEST")
        SC.createSlot(future.plus(SEC)).assertError(400, "INVALID_REQUEST")
        SC.createSlot(now).assertError(400, "INVALID_REQUEST")             // slotStart = 현재 (> 현재 아님)
        SC.createSlot(now.minus(HOUR)).assertError(400, "INVALID_REQUEST")
        SC.createSlot(future, 0).assertError(400, "INVALID_REQUEST")
        SC.createSlot(future, 101).assertError(400, "INVALID_REQUEST")
        SC.createSlot(future, null).assertError(400, "INVALID_REQUEST")
        SC.createSlot(future, "ten").assertError(400, "INVALID_REQUEST")
        SC.createSlot(future, "5").assertError(400, "INVALID_REQUEST")           // §19의 2: 정수 JSON 숫자만
        SC.createSlot(future, 5.0).assertError(400, "INVALID_REQUEST")
        SC.createSlot(Flow.iso(future).substring(0, 19)).assertError(400, "INVALID_REQUEST")   // §19의 5: 오프셋 없음
        SC.createSlot(null).assertError(400, "INVALID_REQUEST")
        SC.createSlot("tomorrow").assertError(400, "INVALID_REQUEST")
        assertEquals(slotsBefore, Db3.count("inbound_slots"))
        assertEquals(auditBefore, SC.maxAuditId(), "실패한 요청은 감사 로그를 쓰지 않는다")
        SC.createSlot(SC.freeHourAtOrAfter(now.plus(HOUR))).assertStatus(201)   // 현재 + 1시간 정시는 된다
    }

    @Test
    fun `§12_3 같은 시각 슬롯 - 두 번째는 409 ITEM_STATE_CONFLICT(감사 1행), 동시 생성 2건도 하나만 201`() {
        val s1 = SC.freeHourAtOrAfter(Flow.now().plus(Duration.ofDays(2)))
        val id = SC.newSlot(s1, 5)
        SC.createSlot(s1, 7).assertError(409, "ITEM_STATE_CONFLICT")
        assertEquals(5L, Db3.row("inbound_slots", id).long("capacity"))
        SC.assertAudit("INBOUND_SLOT_CREATE", id, 1)

        val s2 = SC.freeHourAtOrAfter(s1.plus(HOUR))
        // 부모 잠금이 없는 INSERT … ON CONFLICT 경로: 두 INSERT가 BEFORE ROW 트리거에서 함께 0.5초 멈춘 뒤 같은 slot_start로 경쟁한다
        // — 한쪽은 상대 트랜잭션이 끝나기를 기다린 뒤 DO NOTHING(들어간 행 0) → 409(경합 보장)
        val rs = SlowTrigger.on("inbound_slots", "INSERT") { SC.concurrently(2) { SC.createSlot(s2, 3) } }
        assertEquals(1, rs.count { it.status == 201 }, "하나만 생성: $rs")
        rs.single { it.status != 201 }.assertError(409, "ITEM_STATE_CONFLICT")
        assertEquals(1L, Db3.count("inbound_slots", "slot_start = ?", s2))
    }

    // ---- 슬롯 조회 ----

    @Test
    fun `§12_3 슬롯 조회 - 그 KST 날짜의 슬롯(00시 포함·다음 날 00시 제외), slotStart 오름차순, reserved = 살아 있는 예약 수 - 형식 오류·date 누락 400, 잘못된 X-User-Id도 200`() {
        var d = Kst.date(Flow.now()).plusDays(40)
        while (Db3.count("inbound_slots", "slot_start >= ? AND slot_start < ?", Kst.at(d), Kst.at(d.plusDays(2))) > 0) d = d.plusDays(1)
        val late = SC.newSlot(Kst.at(d).plus(Duration.ofHours(23)), 3)
        val midnight = SC.newSlot(Kst.at(d), 2)
        val next = SC.newSlot(Kst.at(d.plusDays(1)), 4)
        val p = S2.purchase(30_000)
        SC.readyReserve(p.seller, midnight, p.item)

        val r = Api.get("/inbound-slots?date=$d").assertStatus(200)
        val list = SC.array(r)
        assertEquals(listOf(midnight, late), list.map { it.longAt("slotId") })
        list.forEach { assertSlot(it, it.longAt("slotId"), r) }
        assertEquals(1L, list[0].longAt("reserved"))
        assertEquals(0L, list[1].longAt("reserved"))
        assertEquals(listOf(next), SC.array(Api.get("/inbound-slots?date=${d.plusDays(1)}").assertStatus(200)).map { it.longAt("slotId") })
        Api.get("/inbound-slots?date=2026-13-01").assertError(400, "INVALID_REQUEST")
        Api.get("/inbound-slots?date=20261101").assertError(400, "INVALID_REQUEST")
        Api.get("/inbound-slots").assertError(400, "INVALID_REQUEST")            // §19의 4: date 필수
        // §19의 7: 헤더 불필요 — 잘못된 X-User-Id도 읽지 않는다
        assertEquals(listOf(midnight, late), SC.array(Api.get("/inbound-slots?date=$d", "abc").assertStatus(200)).map { it.longAt("slotId") })
    }

    // ---- 예약 ----

    @Test
    fun `§12_3 예약 - 201 예약 모양(RESERVED, seller_id = 판매자, created_at = 현재), 슬롯 reserved 증가, 내 예약 목록 reservationId 오름차순`() {
        val now = Flow.now()
        val slot = SC.newSlot(futureSlotStart(), 5)
        val seller = Db.createUser()
        val p1 = S2.purchase(listOf(com.ccle.behavior.contract2.Line(30_000, seller = seller)))
        val p2 = S2.purchase(listOf(com.ccle.behavior.contract2.Line(40_000, seller = seller)))
        val r = SC.reserve(seller, slot, p1.item).assertStatus(201)
        val id1 = r.long("reservationId")
        val row = SC.assertReservation(r.json, id1, r)
        assertEquals("RESERVED", row.text("status"))
        assertEquals(seller, row.long("seller_id"))
        assertEquals(slot, row.long("slot_id"))
        assertEquals(p1.item, row.long("order_item_id"))
        assertEquals(now, row.instant("created_at"))
        assertNull(row.instantOrNull("closed_at"))
        val id2 = SC.readyReserve(seller, slot, p2.item)
        assertEquals(2L, live(slot))
        assertEquals("PAID", Db2.item(p1.item).status, "예약은 항목 상태를 바꾸지 않는다")

        val mine = SC.array(Api.get("/me/inbound-reservations", seller).assertStatus(200))
        assertEquals(listOf(id1, id2), mine.map { it.longAt("reservationId") })
        mine.forEach { SC.assertReservation(it, it.longAt("reservationId"), mine) }
        assertEquals(emptyList(), SC.array(Api.get("/me/inbound-reservations", p1.buyer).assertStatus(200)))
        Api.get("/me/inbound-reservations", null).assertError(401, "UNKNOWN_USER")
    }

    @Test
    fun `§2_2 판매 제한 중인 판매자(ACTIVE 입찰 없음)도 입고 예약 201 - 새 엔드포인트는 SALE_RESTRICTED를 내지 않는다`() {
        val slot = SC.newSlot(futureSlotStart(), 5)
        val p = S2.purchase(30_000)                                  // 판매 입찰은 체결로 MATCHED
        assertTrue(Db.bidsOfUser(p.seller).none { it.status == "ACTIVE" }, "준비: 판매자에게 ACTIVE 입찰 없음")
        Db.setRestrictions(p.seller, null, Flow.now().plus(Duration.ofDays(30)))
        val r = SC.reserve(p.seller, slot, p.item).assertStatus(201)
        assertEquals("RESERVED", SC.assertReservation(r.json, r.long("reservationId"), r).text("status"))
    }

    @Test
    fun `§12_3 예약 판정 순서 - 401 → 400(slotId·orderItemId 없음·정수 아님) → 404(없는 슬롯·항목) → 403(판매자 아님)`() {
        val slot = SC.newSlot(futureSlotStart(), 5)
        val p = S2.purchase(30_000)
        val stranger = Db.createUser()
        SC.reserve(null, slot, p.item).assertError(401, "UNKNOWN_USER")
        SC.reserve("x", slot, p.item).assertError(401, "UNKNOWN_USER")
        SC.reserve(Db.missingId("users"), slot, p.item).assertError(401, "UNKNOWN_USER")
        SC.reserve(p.seller, null, p.item).assertError(400, "INVALID_REQUEST")
        SC.reserve(p.seller, slot, null).assertError(400, "INVALID_REQUEST")
        SC.reserve(p.seller, "a", p.item).assertError(400, "INVALID_REQUEST")
        SC.reserve(stranger, null, Db.missingId("order_items")).assertError(400, "INVALID_REQUEST")
        SC.reserve(p.seller, Db.missingId("inbound_slots"), p.item).assertError(404, "NOT_FOUND")
        SC.reserve(p.seller, slot, Db.missingId("order_items")).assertError(404, "NOT_FOUND")
        SC.reserve(stranger, Db.missingId("inbound_slots"), p.item).assertError(404, "NOT_FOUND")
        SC.reserve(p.buyer, slot, p.item).assertError(403, "NOT_OWNER")
        SC.reserve(stranger, slot, p.item).assertError(403, "NOT_OWNER")
        assertEquals(0L, Db3.count("inbound_reservations", "order_item_id = ?", p.item))
    }

    @Test
    fun `§12_3 예약 409 ITEM_STATE_CONFLICT - 항목이 PAID 아님, 그 항목의 살아 있는 예약 있음(다른 슬롯도), 취소 뒤에는 다시 예약 가능`() {
        val s1 = SC.newSlot(futureSlotStart(), 5)
        val s2 = SC.newSlot(SC.freeHourAtOrAfter(Db3.row("inbound_slots", s1).instant("slot_start").plus(HOUR)), 5)
        val shipped = S2.purchase(30_000)
        S2.toShipped(shipped.item, shipped.seller)
        SC.reserve(shipped.seller, s1, shipped.item).assertError(409, "ITEM_STATE_CONFLICT")

        val p = S2.purchase(30_000)
        val first = SC.readyReserve(p.seller, s1, p.item)
        SC.reserve(p.seller, s1, p.item).assertError(409, "ITEM_STATE_CONFLICT")
        SC.reserve(p.seller, s2, p.item).assertError(409, "ITEM_STATE_CONFLICT")
        assertEquals(1L, Db3.count("inbound_reservations", "order_item_id = ?", p.item))
        SC.cancelReservation(first, p.seller).assertStatus(200)
        val again = SC.reserve(p.seller, s2, p.item).assertStatus(201)
        assertEquals("RESERVED", again.text("status"))
        assertEquals(2L, Db3.count("inbound_reservations", "order_item_id = ?", p.item))
    }

    @Test
    fun `§12_3 정원 - 살아 있는 예약 수 ≥ capacity면 409 ITEM_STATE_CONFLICT, 취소된 예약은 세지 않는다`() {
        val slot = SC.newSlot(futureSlotStart(), 1)
        val a = S2.purchase(30_000)
        val b = S2.purchase(30_000)
        val ra = SC.readyReserve(a.seller, slot, a.item)
        SC.reserve(b.seller, slot, b.item).assertError(409, "ITEM_STATE_CONFLICT")
        SC.cancelReservation(ra, a.seller).assertStatus(200)
        SC.reserve(b.seller, slot, b.item).assertStatus(201)
        assertEquals(1L, live(slot))
    }

    @Test
    fun `§12_3 예약 기한 - 현재 = slot − 24시간은 예약 가능, 1초 뒤는 409 DEADLINE_PASSED, 상태·정원 위반이 기한보다 먼저(ITEM_STATE_CONFLICT)`() {
        val early = SC.newSlot(futureSlotStart(), 1)                          // 정원 1, 먼저 시작하는 슬롯
        val earlyStart = Db3.row("inbound_slots", early).instant("slot_start")
        val slotStart = SC.freeHourAtOrAfter(earlyStart.plus(HOUR))
        val slot = SC.newSlot(slotStart, 5)
        val p0 = S2.purchase(30_000)
        val p1 = S2.purchase(30_000)
        val p2 = S2.purchase(30_000)
        val p3 = S2.purchase(30_000)
        val shipped = S2.purchase(30_000)
        S2.toShipped(shipped.item, shipped.seller)
        SC.readyReserve(p0.seller, early, p0.item)                             // early 정원 다 참

        Flow.moveTo(slotStart.minus(DAY_))
        SC.reserve(p1.seller, slot, p1.item).assertStatus(201)                 // 정각 포함
        SC.reserve(p3.seller, early, p3.item).assertError(409, "ITEM_STATE_CONFLICT")   // 정원 초과 + 기한 지남 → 정원
        Flow.moveTo(slotStart.minus(DAY_).plus(SEC))
        SC.reserve(p2.seller, slot, p2.item).assertError(409, "DEADLINE_PASSED")
        SC.reserve(shipped.seller, slot, shipped.item).assertError(409, "ITEM_STATE_CONFLICT")   // 상태 위반 + 기한 지남 → 상태
        assertEquals(0L, Db3.count("inbound_reservations", "order_item_id IN (?, ?, ?)", p2.item, p3.item, shipped.item))
    }

    @Test
    fun `§12_3·§2_3 정원 1 슬롯에 다른 항목 예약 2건 동시 - 하나만 201, 다른 하나는 409 ITEM_STATE_CONFLICT`() {
        val slot = SC.newSlot(futureSlotStart(), 1)
        val a = S2.purchase(30_000)
        val b = S2.purchase(30_000)
        val ps = listOf(a, b)
        // 이긴 요청이 슬롯 행을 잠근 채 INSERT 직전 0.5초 멈춘다 → 진 요청은 슬롯 잠금 뒤 정원 초과를 본다(경합 보장)
        val rs = SlowTrigger.on("inbound_reservations", "INSERT", condition = "NEW.slot_id = $slot") {
            SC.concurrently(2) { i -> SC.reserve(ps[i].seller, slot, ps[i].item) }
        }
        assertEquals(1, rs.count { it.status == 201 }, "하나만 예약: $rs")
        rs.single { it.status != 201 }.assertError(409, "ITEM_STATE_CONFLICT")
        assertEquals(1L, live(slot))
    }

    @Test
    fun `§12_3·§2_3 같은 항목을 다른 슬롯 2개에 동시 예약 - 하나만 201, 다른 하나는 409 ITEM_STATE_CONFLICT, 살아 있는 예약 1행`() {
        val s1 = SC.newSlot(futureSlotStart(), 5)
        val s2 = SC.newSlot(SC.freeHourAtOrAfter(Db3.row("inbound_slots", s1).instant("slot_start").plus(HOUR)), 5)
        val p = S2.purchase(30_000)
        val slots = listOf(s1, s2)
        // 이긴 요청이 주문 행을 잠근 채 INSERT 직전 0.5초 멈춘다 → 진 요청은 주문 행 잠금 뒤 살아 있는 예약을 본다(경합 보장)
        val rs = SlowTrigger.on("inbound_reservations", "INSERT", condition = "NEW.order_item_id = ${p.item}") {
            SC.concurrently(2) { i -> SC.reserve(p.seller, slots[i], p.item) }
        }
        assertEquals(1, rs.count { it.status == 201 }, "하나만 예약: $rs")
        rs.single { it.status != 201 }.assertError(409, "ITEM_STATE_CONFLICT")
        assertEquals(1L, Db3.count("inbound_reservations", "order_item_id = ? AND status IN ('RESERVED','CHECKED_IN')", p.item))
        assertEquals(1L, Db3.count("inbound_reservations", "order_item_id = ?", p.item))
    }

    // ---- 취소 ----

    @Test
    fun `§12_3 취소 - RESERVED → CANCELLED·closed_at = 현재, 기한 slot − 2시간(정각 포함, 1초 뒤 DEADLINE_PASSED), 404·403, 이미 취소면 409(기한보다 먼저)`() {
        val slotStart = futureSlotStart()
        val slot = SC.newSlot(slotStart, 5)
        val a = S2.purchase(30_000)
        val b = S2.purchase(30_000)
        val ra = SC.readyReserve(a.seller, slot, a.item)
        val rb = SC.readyReserve(b.seller, slot, b.item)

        SC.cancelReservation(ra, null).assertError(401, "UNKNOWN_USER")
        SC.cancelReservation(Db.missingId("inbound_reservations"), a.seller).assertError(404, "NOT_FOUND")
        SC.cancelReservation(ra, b.seller).assertError(403, "NOT_OWNER")

        val at = Flow.moveTo(slotStart.minus(Duration.ofHours(2)))
        val r = SC.cancelReservation(ra, a.seller).assertStatus(200)
        val row = SC.assertReservation(r.json, ra, r)
        assertEquals("CANCELLED", row.text("status"))
        assertEquals(at, row.instant("closed_at"))

        Flow.moveTo(at.plus(SEC))
        SC.cancelReservation(rb, b.seller).assertError(409, "DEADLINE_PASSED")
        assertEquals("RESERVED", SC.reservationRow(rb).text("status"))
        SC.cancelReservation(ra, a.seller).assertError(409, "ITEM_STATE_CONFLICT")
        assertEquals(at, SC.reservationRow(ra).instant("closed_at"))
    }

    // ---- 체크인 ----

    @Test
    fun `§12_3 체크인 - 현재 ＜ slot은 409 ITEM_STATE_CONFLICT, ［slot, slot + 1시간)은 CHECKED_IN·감사 로그 INBOUND_CHECK_IN, slot + 1시간은 409 DEADLINE_PASSED, 항목 상태 그대로`() {
        val slotStart = futureSlotStart()
        val slot = SC.newSlot(slotStart, 5)
        val ps = (1..4).map { S2.purchase(30_000) }
        val ids = ps.map { SC.readyReserve(it.seller, slot, it.item) }
        val cancelled = ids[3]
        SC.cancelReservation(cancelled, ps[3].seller).assertStatus(200)

        SC.checkIn(Db.missingId("inbound_reservations")).assertError(404, "NOT_FOUND")
        Flow.moveTo(slotStart.minus(SEC))
        SC.checkIn(ids[0]).assertError(409, "ITEM_STATE_CONFLICT")
        SC.assertAudit("INBOUND_CHECK_IN", ids[0], 0)

        val t0 = Flow.moveTo(slotStart)
        val r0 = SC.checkIn(ids[0]).assertStatus(200)
        val row0 = SC.assertReservation(r0.json, ids[0], r0)
        assertEquals("CHECKED_IN", row0.text("status"))
        assertEquals(t0, row0.instant("closed_at"))
        SC.assertAudit("INBOUND_CHECK_IN", ids[0], 1, t0, null)
        assertEquals("PAID", Db2.item(ps[0].item).status, "체크인은 항목 상태를 바꾸지 않는다")
        SC.checkIn(ids[0]).assertError(409, "ITEM_STATE_CONFLICT")
        SC.assertAudit("INBOUND_CHECK_IN", ids[0], 1)

        val t1 = Flow.moveTo(slotStart.plus(HOUR).minus(SEC))
        SC.checkIn(ids[1]).assertStatus(200)
        assertEquals(t1, SC.reservationRow(ids[1]).instant("closed_at"))

        Flow.moveTo(slotStart.plus(HOUR))
        SC.checkIn(ids[2]).assertError(409, "DEADLINE_PASSED")
        assertEquals("RESERVED", SC.reservationRow(ids[2]).text("status"))
        SC.assertAudit("INBOUND_CHECK_IN", ids[2], 0)
        SC.checkIn(cancelled).assertError(409, "ITEM_STATE_CONFLICT")    // 상태 위반이 기한보다 먼저
        // 살아 있는 예약 = CHECKED_IN 2(ids[0]·ids[1]) + RESERVED 1(ids[2] — 체크인 창이 지나 남음), CANCELLED 1(ids[3])은 빠진다
        assertEquals(listOf("CHECKED_IN", "CHECKED_IN", "RESERVED", "CANCELLED"), ids.map { SC.reservationRow(it).text("status") })
        assertEquals(3L, live(slot))
    }

    // ---- 배치 inbound-no-show ----

    @Test
    fun `§12_3 inbound-no-show - RESERVED이고 slot + 1시간 ≤ 현재(정각 포함)만 NO_SHOW·closed_at = 현재, CHECKED_IN·CANCELLED 그대로, 패널티·판매 제한 없음 - 격리·재실행`() {
        val slotStart = futureSlotStart()
        val slot = SC.newSlot(slotStart, 5)
        val ps = (1..4).map { S2.purchase(30_000) }
        val ids = ps.map { SC.readyReserve(it.seller, slot, it.item) }
        val (a, b, checkedIn, cancelled) = ids
        assertTrue(a < b)
        SC.cancelReservation(cancelled, ps[3].seller).assertStatus(200)
        Flow.moveTo(slotStart)
        SC.checkIn(checkedIn).assertStatus(200)

        Flow.moveTo(slotStart.plus(HOUR).minus(SEC))
        S2.runBatch("inbound-no-show")
        assertEquals(listOf("RESERVED", "RESERVED"), listOf(a, b).map { SC.reservationRow(it).text("status") }, "slot + 1시간 − 1초: 아직")

        // 격리: A의 반영이 실패해도 B는 NO_SHOW
        val at = Flow.moveTo(slotStart.plus(HOUR))
        FailTrigger.on("inbound_reservations", "UPDATE", "OLD.id = $a") {
            val r = S2.runBatch("inbound-no-show")
            assertTrue(r.failed() >= 1, "A 실패를 failed에 센다: $r")
            assertTrue(r.long("processed") >= 1, "B는 커밋된다: $r")
        }
        assertEquals("RESERVED", SC.reservationRow(a).text("status"))
        assertNull(SC.reservationRow(a).instantOrNull("closed_at"))
        assertEquals("NO_SHOW", SC.reservationRow(b).text("status"))
        assertEquals(at, SC.reservationRow(b).instant("closed_at"))

        val later = Flow.advance(HOUR)
        S2.runBatch("inbound-no-show")
        assertEquals("NO_SHOW", SC.reservationRow(a).text("status"))
        assertEquals(later, SC.reservationRow(a).instant("closed_at"), "closed_at = 그 실행 시각")
        assertEquals(at, SC.reservationRow(b).instant("closed_at"), "재실행은 이미 바꾼 예약을 건드리지 않는다")
        assertEquals("CHECKED_IN", SC.reservationRow(checkedIn).text("status"))
        assertEquals("CANCELLED", SC.reservationRow(cancelled).text("status"))
        for (p in ps) {
            assertTrue(Db2.penaltiesOfSeller(p.seller).isEmpty(), "노쇼는 패널티를 만들지 않는다")
            assertNull(Db.user(p.seller).saleRestrictedUntil, "노쇼는 판매 제한과 무관하다")
            assertEquals("PAID", Db2.item(p.item).status)
        }
        SC.checkIn(a).assertError(409, "ITEM_STATE_CONFLICT")
    }
}
