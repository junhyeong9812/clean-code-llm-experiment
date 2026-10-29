package com.ccle.layered.api

import com.ccle.layered.service.TrackingService
import com.ccle.layered.service.UserService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/** 배송 추적 이벤트 기록(관리자)·조회(ADR-0007 §12.2). */
@RestController
class TrackingController(private val users: UserService, private val tracking: TrackingService) {

    @PostMapping("/admin/order-items/{itemId}/tracking-events")
    fun record(@PathVariable itemId: String, @RequestBody(required = false) body: String?): ResponseEntity<TrackingEventView> {
        val json = parseObject(body)
        val event = tracking.record(
            pathId(itemId),
            json.textOrNull("carrier"),
            json.textOrNull("trackingNo"),
            json.textOrNull("eventType"),
            json.instantOrNull("occurredAt"),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(TrackingEventView(event))
    }

    @GetMapping("/order-items/{itemId}/tracking")
    fun tracking(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable itemId: String,
    ): TrackingView {
        val userId = users.requireUser(userIdOf(userHeader))
        return TrackingView(tracking.tracking(userId, pathId(itemId)))
    }
}
