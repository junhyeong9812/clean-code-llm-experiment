package com.ccle.layered.service

import com.ccle.layered.domain.BatchTarget
import com.ccle.layered.domain.BatchTargets
import com.ccle.layered.domain.Failure
import com.ccle.layered.domain.InboundReservation
import com.ccle.layered.domain.InboundReservationRead
import com.ccle.layered.domain.InboundReservationRepository
import com.ccle.layered.domain.InboundSlot
import com.ccle.layered.domain.InboundSlotDay
import com.ccle.layered.domain.InboundSlotInput
import com.ccle.layered.domain.InboundSlotRead
import com.ccle.layered.domain.InboundSlotRepository
import com.ccle.layered.domain.MarketClock
import com.ccle.layered.domain.MarketException
import com.ccle.layered.domain.OrderItemRepository
import com.ccle.layered.domain.OrderRepository
import com.ccle.layered.domain.TradeRepository
import com.ccle.layered.domain.invalidRequest
import com.ccle.layered.domain.notFound
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/** A12 입고 슬롯 생성(관리자)·조회, 입고 예약·취소·체크인(관리자)·조회(ADR-0007 §12.3). 사용자 확인(401)은 부르는 쪽이 한다. */
@Service
class InboundService(
    private val clock: MarketClock,
    private val tx: TransactionTemplate,
    private val slots: InboundSlotRepository,
    private val reservations: InboundReservationRepository,
    private val orders: OrderRepository,
    private val items: OrderItemRepository,
    private val trades: TradeRepository,
    private val audit: AdminAuditService,
) {
    private val snapshotTx = TransactionTemplate(tx.transactionManager!!).apply {
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        isReadOnly = true
    }

    /**
     * 슬롯 생성: 400 → 잠글 부모 행이 없으므로 INSERT … ON CONFLICT (slot_start) DO NOTHING, 들어간 행이 0이면 409(같은 시각 — 동시 생성도)
     * + 감사 INBOUND_SLOT_CREATE(detail null).
     */
    fun createSlot(slotStart: Instant?, capacity: Long?): InboundSlotRead {
        val now = clock.now()
        val input = InboundSlotInput.of(slotStart, capacity, now)
        return tx.execute {
            val slotId = slots.insertIfAbsent(input, now)
                ?: throw MarketException(Failure.ITEM_STATE_CONFLICT, "같은 시각의 슬롯이 있다: ${input.slotStart}")
            audit.record("INBOUND_SLOT_CREATE", slotId, null, now)
            InboundSlotRead(InboundSlot(id = slotId, slotStart = input.slotStart, capacity = input.capacity, createdAt = now), 0)
        }!!
    }

    /** 슬롯 조회(헤더 불필요): date 필수, 그 KST 날짜의 슬롯 slotStart 오름차순. 한 스냅샷에서 읽는다. */
    fun slotsOn(date: String?): List<InboundSlotRead> {
        val day = InboundSlotDay.of(date)
        return snapshotTx.execute {
            slots.listOn(day).map { InboundSlotRead(it, slots.countLive(it.id!!)) }
        }!!
    }

    /**
     * 예약: 400 → 없는 슬롯·항목 404 → 판매자가 아니면 403 → 주문 행 FOR UPDATE → 항목이 PAID가 아니면 409(슬롯 잠금 전 — PAID 아닌 항목 요청이
     * 슬롯을 기다리지 않게) → 슬롯 행 FOR UPDATE → 살아 있는 예약·정원·기한 → RESERVED 1행.
     */
    fun reserve(userId: Long, slotId: Long?, orderItemId: Long?): InboundReservationRead {
        if (slotId == null) invalidRequest("slotId가 필요하다")
        if (orderItemId == null) invalidRequest("orderItemId가 필요하다")
        val now = clock.now()
        return tx.execute {
            val slot = slots.find(slotId) ?: notFound("없는 슬롯: $slotId")
            val item = items.find(orderItemId) ?: notFound("없는 주문 항목: $orderItemId")
            item.requireSeller(trades.find(item.tradeId)!!, userId)
            orders.lock(item.orderId)
            items.reload(item)
            InboundSlot.requireReservable(item)
            slots.lock(slotId)
            val reservation = slot.reserve(
                item,
                userId,
                liveOfItem = { reservations.countLiveOfItem(orderItemId) },
                liveOfSlot = { slots.countLive(slotId) },
                now = now,
            )
            reservations.add(reservation)
            InboundReservationRead(reservation, slot.slotStart)
        }!!
    }

    /** 취소: 404 → 본인 것만(403) → 예약 행 FOR UPDATE → RESERVED가 아니면 409 → 취소 기한. */
    fun cancel(userId: Long, reservationId: Long): InboundReservationRead {
        val now = clock.now()
        return tx.execute {
            val reservation = reservations.find(reservationId) ?: notFound("없는 예약: $reservationId")
            reservation.requireOwner(userId)
            val slotStart = lockReserved(reservation)
            reservation.cancel(slotStart, now)
            InboundReservationRead(reservation, slotStart)
        }!!
    }

    /** 체크인: 404 → 예약 행 FOR UPDATE → RESERVED가 아니면 409 → 체크인 창 → CHECKED_IN + 감사 INBOUND_CHECK_IN(detail null). */
    fun checkIn(reservationId: Long): InboundReservationRead {
        val now = clock.now()
        return tx.execute {
            val reservation = reservations.find(reservationId) ?: notFound("없는 예약: $reservationId")
            val slotStart = lockReserved(reservation)
            reservation.checkIn(slotStart, now)
            audit.record("INBOUND_CHECK_IN", reservationId, null, now)
            InboundReservationRead(reservation, slotStart)
        }!!
    }

    /** 내 예약, reservationId 오름차순. */
    fun list(userId: Long): List<InboundReservationRead> =
        reservations.listOf(userId).map { InboundReservationRead(it, slots.find(it.slotId)!!.slotStart) }

    /** 예약 행을 잠그고 다시 읽은 뒤 RESERVED만 통과시킨다. 반환 = 그 슬롯 시각. */
    private fun lockReserved(reservation: InboundReservation): Instant {
        reservations.lock(reservation.id!!)
        reservations.reload(reservation)
        reservation.requireReserved()
        return slots.find(reservation.slotId)!!.slotStart
    }
}

/**
 * inbound-no-show(ADR-0007 §12.3): RESERVED이고 slot_start + 1시간 ≤ 현재(id 순). 예약 1건 = 그 행을 잠근 트랜잭션 하나,
 * 잠근 뒤 다시 확인한다. → NO_SHOW, closed_at = 현재. processed = 바꾼 예약 수.
 */
@Component
class InboundNoShowBatch(
    private val targets: BatchTargets,
    private val tx: TransactionTemplate,
    private val reservations: InboundReservationRepository,
) {
    fun run(now: Instant): BatchResult =
        targets.forEachIsolated("inbound-no-show", BatchTarget.NO_SHOW_RESERVATIONS, now) { reservationId ->
            tx.execute {
                reservations.lock(reservationId)
                reservations.find(reservationId)!!.markNoShow(now)
            }!!
        }
}
