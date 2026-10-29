package com.ccle.layered.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

// A12 입고 예약(ADR-0007 §12.3). 항목 상태는 바꾸지 않는다(입고 처리는 기존 /admin/order-items/{id}/receipt).
// 판매자 패널티·판매 제한과 무관하다. 오류 code는 기존 Failure만 쓴다(§2.2).

/** 슬롯 생성 요청의 값(§12.3): slotStart는 정시(분·초 0)이고 > 현재, capacity 1~100. 아니면 400. */
class InboundSlotInput private constructor(val slotStart: Instant, val capacity: Int) {
    companion object {
        fun of(slotStart: Instant?, capacity: Long?, now: Instant): InboundSlotInput {
            if (slotStart == null) invalidRequest("slotStart가 필요하다")
            if (capacity == null) invalidRequest("capacity가 필요하다")
            if (capacity !in 1L..100L) invalidRequest("capacity는 1~100: $capacity")
            if (slotStart.epochSecond % 3600 != 0L || slotStart.nano != 0) invalidRequest("slotStart는 정시여야 한다: $slotStart")
            if (!slotStart.isAfter(now)) invalidRequest("slotStart는 현재 뒤여야 한다: $slotStart")
            return InboundSlotInput(slotStart, capacity.toInt())
        }
    }
}

/** 슬롯 조회의 날짜(§12.3, 필수): 그 KST 날짜의 [from, to). */
class InboundSlotDay private constructor(date: LocalDate) {
    val from: Instant = date.atStartOfDay(KST).toInstant()
    val to: Instant = date.plusDays(1).atStartOfDay(KST).toInstant()

    companion object {
        fun of(date: String?): InboundSlotDay = InboundSlotDay(dateInput("date", date) ?: invalidRequest("date가 필요하다"))
    }
}

/** 입고 슬롯 행(§3 inbound_slots). UNIQUE(slot_start). */
@Entity
@Table(name = "inbound_slots")
class InboundSlot(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var slotStart: Instant,
    var capacity: Int,
    var createdAt: Instant,
) {
    /**
     * 예약(§12.3)의 슬롯 판정 — 주문 행·슬롯 행을 잠근 뒤: 그 항목의 살아 있는 예약이 있으면 409, 살아 있는 예약 수 ≥ 정원이면 409
     * (상태 위반으로 보아 기한보다 먼저), 현재 > slot_start − 24시간이면 409 DEADLINE_PASSED. → RESERVED 1행.
     */
    fun reserve(item: OrderItem, sellerId: Long, liveOfItem: () -> Long, liveOfSlot: () -> Long, now: Instant): InboundReservation {
        if (liveOfItem() > 0) throw MarketException(Failure.ITEM_STATE_CONFLICT, "살아 있는 입고 예약이 있는 주문 항목: ${item.id}")
        if (liveOfSlot() >= capacity) throw MarketException(Failure.ITEM_STATE_CONFLICT, "정원이 찬 슬롯: $id")
        val deadline = slotStart.minus(RESERVE_BEFORE)
        if (now.isAfter(deadline)) throw MarketException(Failure.DEADLINE_PASSED, "입고 예약 기한($deadline)이 지났다: 슬롯 $id")
        return InboundReservation(slotId = id!!, sellerId = sellerId, orderItemId = item.id!!, status = "RESERVED", createdAt = now)
    }

    companion object {
        private val RESERVE_BEFORE = Duration.ofHours(24)

        /** 예약할 수 있는 항목 상태(§12.3의 1): PAID. 슬롯 행 잠금 전에 판정한다. */
        fun requireReservable(item: OrderItem) {
            if (item.status != "PAID") {
                throw MarketException(Failure.ITEM_STATE_CONFLICT, "PAID가 아닌 주문 항목: ${item.id} (${item.status})")
            }
        }
    }
}

/** 입고 예약 행(§3 inbound_reservations). 상태 RESERVED·CHECKED_IN·CANCELLED·NO_SHOW, RESERVED가 아니면 closed_at이 있다. */
@Entity
@Table(name = "inbound_reservations")
class InboundReservation(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var slotId: Long,
    var sellerId: Long,
    var orderItemId: Long,
    status: String,
    var createdAt: Instant,
    closedAt: Instant? = null,
) {
    var status: String = status
        private set

    var closedAt: Instant? = closedAt
        private set

    /** 본인 것만(§12.3 취소 — 403). */
    fun requireOwner(userId: Long) {
        if (sellerId != userId) throw MarketException(Failure.NOT_OWNER, "남의 예약: $id")
    }

    /** 취소·체크인의 상태(§12.3): RESERVED만. 아니면 409. */
    fun requireReserved() {
        if (status != "RESERVED") throw MarketException(Failure.ITEM_STATE_CONFLICT, "RESERVED가 아닌 예약: $id ($status)")
    }

    /** 취소(§12.3): 현재 > slot_start − 2시간이면 409 DEADLINE_PASSED. → CANCELLED, closed_at = 현재. */
    fun cancel(slotStart: Instant, now: Instant) {
        val deadline = slotStart.minus(CANCEL_BEFORE)
        if (now.isAfter(deadline)) throw MarketException(Failure.DEADLINE_PASSED, "입고 예약 취소 기한($deadline)이 지났다: $id")
        status = "CANCELLED"
        closedAt = now
    }

    /** 체크인(§12.3): 현재 < slot_start면 409, 현재 ≥ slot_start + 1시간이면 409 DEADLINE_PASSED. → CHECKED_IN, closed_at = 현재. */
    fun checkIn(slotStart: Instant, now: Instant) {
        if (now.isBefore(slotStart)) throw MarketException(Failure.ITEM_STATE_CONFLICT, "슬롯 시작($slotStart) 전이다: $id")
        val deadline = slotStart.plus(CHECK_IN_WINDOW)
        if (!now.isBefore(deadline)) throw MarketException(Failure.DEADLINE_PASSED, "체크인 기한($deadline)이 지났다: $id")
        status = "CHECKED_IN"
        closedAt = now
    }

    /** 노쇼(§12.3 inbound-no-show): RESERVED만 → NO_SHOW, closed_at = 현재. 바꿨으면 true. */
    fun markNoShow(now: Instant): Boolean {
        if (status != "RESERVED") return false
        status = "NO_SHOW"
        closedAt = now
        return true
    }

    companion object {
        private val CANCEL_BEFORE = Duration.ofHours(2)
        private val CHECK_IN_WINDOW = Duration.ofHours(1)

        /** 노쇼 기준 시각 상한(§12.3): slot_start ≤ 현재 − 1시간. */
        fun noShowCutoff(now: Instant): Instant = now.minus(CHECK_IN_WINDOW)
    }
}

/** 슬롯과 그 살아 있는(RESERVED·CHECKED_IN) 예약 수(§12.3 슬롯 모양). */
class InboundSlotRead(val slot: InboundSlot, val reserved: Long)

/** 예약과 그 슬롯 시각(§12.3 예약 모양). */
class InboundReservationRead(val reservation: InboundReservation, val slotStart: Instant)

interface InboundSlotRepository {
    fun find(id: Long): InboundSlot?

    /** INSERT … ON CONFLICT (slot_start) DO NOTHING. 들어간 행이 있으면 그 id, 같은 시각 슬롯이 있으면 null. */
    fun insertIfAbsent(input: InboundSlotInput, now: Instant): Long?

    /** 슬롯 행 FOR UPDATE. */
    fun lock(id: Long)

    /** slot_start ∈ [day.from, day.to), slot_start 순. */
    fun listOn(day: InboundSlotDay): List<InboundSlot>

    /** 그 슬롯의 RESERVED·CHECKED_IN 예약 수. */
    fun countLive(slotId: Long): Long
}

interface InboundReservationRepository {
    fun find(id: Long): InboundReservation?

    fun add(reservation: InboundReservation)

    /** 예약 행 FOR UPDATE. */
    fun lock(id: Long)

    fun reload(reservation: InboundReservation)

    /** 그 항목의 RESERVED·CHECKED_IN 예약 수. */
    fun countLiveOfItem(orderItemId: Long): Long

    /** 그 판매자의 예약, id 순. */
    fun listOf(sellerId: Long): List<InboundReservation>
}
