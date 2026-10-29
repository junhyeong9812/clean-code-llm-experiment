package com.ccle.layered.api

import com.ccle.layered.service.TrackingService
import com.ccle.layered.service.UserService
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** 시각 필드(ADR-0007 §19의 5). 없거나 null이면 null, 오프셋 포함 ISO-8601 문자열이 아니면 400. */
private fun JsonNode.instantOrNull(field: String): Instant? {
    val text = textOrNull(field) ?: return null
    return try {
        OffsetDateTime.parse(text).toInstant()
    } catch (e: DateTimeParseException) {
        invalid("$field 는 오프셋 포함 ISO-8601 시각이어야 한다: $text")
    }
}

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
