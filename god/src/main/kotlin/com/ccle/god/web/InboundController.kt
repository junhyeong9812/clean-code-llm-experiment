package com.ccle.god.web

import com.ccle.god.Marketplace
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

/** 입고 슬롯 생성(관리자)·조회, 입고 예약·취소·체크인(관리자)·조회(ADR-0007 §12.3). */
@RestController
class InboundController(private val marketplace: Marketplace) {

    @PostMapping("/admin/inbound-slots")
    fun createSlot(@RequestBody(required = false) body: String?): ResponseEntity<InboundSlotView> {
        val json = parseObject(body)
        val slot = marketplace.createInboundSlot(json.instantOrNull("slotStart"), json.longOrNull("capacity"))
        return ResponseEntity.status(HttpStatus.CREATED).body(slot)
    }

    /** 헤더 불필요 — X-User-Id를 읽지 않는다(ADR-0007 §19의 7). */
    @GetMapping("/inbound-slots")
    fun slots(@RequestParam(required = false) date: String?): List<InboundSlotView> = marketplace.inboundSlots(date)

    @PostMapping("/me/inbound-reservations")
    fun reserve(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @RequestBody(required = false) body: String?,
    ): ResponseEntity<InboundReservationView> {
        val userId = marketplace.requireUser(userHeader)
        val json = parseObject(body)
        val reservation = marketplace.reserveInbound(userId, json.longOrNull("slotId"), json.longOrNull("orderItemId"))
        return ResponseEntity.status(HttpStatus.CREATED).body(reservation)
    }

    @DeleteMapping("/me/inbound-reservations/{reservationId}")
    fun cancel(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable reservationId: String,
    ): InboundReservationView {
        val userId = marketplace.requireUser(userHeader)
        return marketplace.cancelInboundReservation(userId, pathId(reservationId))
    }

    @PostMapping("/admin/inbound-reservations/{reservationId}/check-in")
    fun checkIn(@PathVariable reservationId: String): InboundReservationView = marketplace.checkInInbound(pathId(reservationId))

    @GetMapping("/me/inbound-reservations")
    fun mine(@RequestHeader("X-User-Id", required = false) userHeader: String?): List<InboundReservationView> =
        marketplace.inboundReservations(marketplace.requireUser(userHeader))
}
