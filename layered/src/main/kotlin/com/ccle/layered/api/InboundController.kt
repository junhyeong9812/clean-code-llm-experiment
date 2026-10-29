package com.ccle.layered.api

import com.ccle.layered.domain.InboundReservationRead
import com.ccle.layered.domain.InboundSlotRead
import com.ccle.layered.service.InboundService
import com.ccle.layered.service.UserService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/** 입고 슬롯 모양(ADR-0007 §12.3). reserved = RESERVED·CHECKED_IN 예약 수. */
data class InboundSlotView(val slotId: Long, val slotStart: Instant, val capacity: Int, val reserved: Long) {
    constructor(r: InboundSlotRead) : this(r.slot.id!!, r.slot.slotStart, r.slot.capacity, r.reserved)
}

/** 입고 예약 모양(§12.3). */
data class InboundReservationView(
    val reservationId: Long,
    val slotId: Long,
    val slotStart: Instant,
    val orderItemId: Long,
    val status: String,
    val createdAt: Instant,
    val closedAt: Instant?,
) {
    constructor(r: InboundReservationRead) : this(
        r.reservation.id!!, r.reservation.slotId, r.slotStart, r.reservation.orderItemId, r.reservation.status,
        r.reservation.createdAt, r.reservation.closedAt,
    )
}

/** 예약 변경 응답(ADR-0007 §20.5.1): cancelled = 옛 예약, reserved = 새 예약. */
data class InboundRescheduledView(val cancelled: InboundReservationView, val reserved: InboundReservationView) {
    constructor(r: Pair<InboundReservationRead, InboundReservationRead>) :
        this(InboundReservationView(r.first), InboundReservationView(r.second))
}

/** 입고 슬롯 생성(관리자)·조회, 입고 예약·취소·체크인(관리자)·조회(ADR-0007 §12.3), 예약 변경·슬롯·예약 상세(§20.5). */
@RestController
class InboundController(private val users: UserService, private val inbound: InboundService) {

    @PostMapping("/admin/inbound-slots")
    fun createSlot(@RequestBody(required = false) body: String?): ResponseEntity<InboundSlotView> {
        val json = parseObject(body)
        val slot = inbound.createSlot(json.instantOrNull("slotStart"), json.longOrNull("capacity"))
        return ResponseEntity.status(HttpStatus.CREATED).body(InboundSlotView(slot))
    }

    /** 헤더 불필요 — X-User-Id를 읽지 않는다(ADR-0007 §19의 7). */
    @GetMapping("/inbound-slots")
    fun slots(@RequestParam(required = false) date: String?): List<InboundSlotView> = inbound.slotsOn(date).map { InboundSlotView(it) }

    /** 헤더 불필요 — X-User-Id를 읽지 않는다(ADR-0007 §19의 7). */
    @GetMapping("/inbound-slots/{slotId}")
    fun slot(@PathVariable slotId: String): InboundSlotView = InboundSlotView(inbound.slot(pathId(slotId)))

    @PostMapping("/me/inbound-reservations")
    fun reserve(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @RequestBody(required = false) body: String?,
    ): ResponseEntity<InboundReservationView> {
        val userId = users.requireUser(userIdOf(userHeader))
        val json = parseObject(body)
        val reservation = inbound.reserve(userId, json.longOrNull("slotId"), json.longOrNull("orderItemId"))
        return ResponseEntity.status(HttpStatus.CREATED).body(InboundReservationView(reservation))
    }

    @DeleteMapping("/me/inbound-reservations/{reservationId}")
    fun cancel(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable reservationId: String,
    ): InboundReservationView {
        val userId = users.requireUser(userIdOf(userHeader))
        return InboundReservationView(inbound.cancel(userId, pathId(reservationId)))
    }

    @PostMapping("/me/inbound-reservations/{reservationId}/reschedule")
    fun reschedule(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable reservationId: String,
        @RequestBody(required = false) body: String?,
    ): InboundRescheduledView {
        val userId = users.requireUser(userIdOf(userHeader))
        val json = parseObject(body)
        return InboundRescheduledView(inbound.reschedule(userId, pathId(reservationId), json.longOrNull("slotId")))
    }

    @GetMapping("/me/inbound-reservations/{reservationId}")
    fun reservation(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable reservationId: String,
    ): InboundReservationView {
        val userId = users.requireUser(userIdOf(userHeader))
        return InboundReservationView(inbound.reservation(userId, pathId(reservationId)))
    }

    @PostMapping("/admin/inbound-reservations/{reservationId}/check-in")
    fun checkIn(@PathVariable reservationId: String): InboundReservationView = InboundReservationView(inbound.checkIn(pathId(reservationId)))

    @GetMapping("/me/inbound-reservations")
    fun mine(@RequestHeader("X-User-Id", required = false) userHeader: String?): List<InboundReservationView> =
        inbound.list(users.requireUser(userIdOf(userHeader))).map { InboundReservationView(it) }
}
