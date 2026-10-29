package com.ccle.god.web

import com.ccle.god.Marketplace
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
class TrackingController(private val marketplace: Marketplace) {

    @PostMapping("/admin/order-items/{itemId}/tracking-events")
    fun record(@PathVariable itemId: String, @RequestBody(required = false) body: String?): ResponseEntity<TrackingEventView> {
        val json = parseObject(body)
        val event = marketplace.recordTrackingEvent(
            pathId(itemId),
            json.textOrNull("carrier"),
            json.textOrNull("trackingNo"),
            json.textOrNull("eventType"),
            json.instantOrNull("occurredAt"),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(event)
    }

    @GetMapping("/order-items/{itemId}/tracking")
    fun tracking(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable itemId: String,
    ): TrackingView {
        val userId = marketplace.requireUser(userHeader)
        return marketplace.tracking(userId, pathId(itemId))
    }
}
