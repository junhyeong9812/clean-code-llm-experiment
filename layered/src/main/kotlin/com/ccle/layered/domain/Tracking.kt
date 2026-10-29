package com.ccle.layered.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Duration
import java.time.Instant

// A11 배송 추적(ADR-0007 §12.2). 항목 상태는 바꾸지 않는다(입고·배송 완료는 기존 /admin 엔드포인트). 오류 code는 기존 Failure만 쓴다(§2.2).

/** 글자 수(§2.1·§19의 6): 앞뒤 공백(isWhitespace)을 뺀 값의 유니코드 코드 포인트 수. 중간 공백은 센다. */
private fun textLength(value: String): Int {
    val trimmed = value.trim()
    return trimmed.codePointCount(0, trimmed.length)
}

/** 추적 이벤트 기록 요청의 값(§12.2): enum은 문서 글자 그대로, trackingNo 1~30자(받은 그대로 저장), occurredAt ≤ 현재. */
class TrackingEventInput private constructor(
    val carrier: String,
    val trackingNo: String,
    val eventType: String,
    val occurredAt: Instant,
) {
    companion object {
        private val CARRIERS = setOf("CJ", "HANJIN", "LOTTE", "POST")
        private val EVENT_TYPES = setOf("PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED")

        fun of(carrier: String?, trackingNo: String?, eventType: String?, occurredAt: Instant?, now: Instant): TrackingEventInput {
            if (carrier == null) invalidRequest("carrier가 필요하다")
            if (carrier !in CARRIERS) invalidRequest("알 수 없는 carrier: $carrier")
            if (trackingNo == null) invalidRequest("trackingNo가 필요하다")
            if (textLength(trackingNo) !in 1..30) invalidRequest("trackingNo는 1~30자")
            if (eventType == null) invalidRequest("eventType이 필요하다")
            if (eventType !in EVENT_TYPES) invalidRequest("알 수 없는 eventType: $eventType")
            if (occurredAt == null) invalidRequest("occurredAt이 필요하다")
            if (occurredAt.isAfter(now)) invalidRequest("occurredAt은 현재 이후일 수 없다: $occurredAt")
            return TrackingEventInput(carrier, trackingNo, eventType, occurredAt)
        }
    }
}

/** 배송 추적 이벤트 행(§3 shipment_tracking_events). received_at = 기록 요청 시각. */
@Entity
@Table(name = "shipment_tracking_events")
class ShipmentTrackingEvent(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var orderItemId: Long,
    var carrier: String,
    var trackingNo: String,
    var eventType: String,
    var occurredAt: Instant,
    var receivedAt: Instant,
) {
    companion object {
        private val TRACKABLE_ITEM_STATUSES = setOf("SHIPPED_TO_CENTER", "INSPECTING", "DELIVERING", "DELIVERED")

        /** 기록(§12.2): 항목 상태가 SHIPPED_TO_CENTER·INSPECTING·DELIVERING·DELIVERED가 아니면 409. */
        fun record(item: OrderItem, input: TrackingEventInput, now: Instant): ShipmentTrackingEvent {
            if (item.status !in TRACKABLE_ITEM_STATUSES) {
                throw MarketException(Failure.ITEM_STATE_CONFLICT, "배송 추적을 기록할 수 없는 주문 항목: ${item.id} (${item.status})")
            }
            return ShipmentTrackingEvent(
                orderItemId = item.id!!,
                carrier = input.carrier,
                trackingNo = input.trackingNo,
                eventType = input.eventType,
                occurredAt = input.occurredAt,
                receivedAt = now,
            )
        }
    }
}

/** 배송 지연 표시 행(§3 shipment_delays). UNIQUE(order_item_id, kind). */
@Entity
@Table(name = "shipment_delays")
class ShipmentDelay(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var orderItemId: Long,
    var kind: String,
    var detectedAt: Instant,
)

/**
 * delivery-delay-scan의 두 종류(§12.2) — 선언 순서대로 차례로 훑는다(order_items id 순). kind = 이름, cutoff = 기준 시각 상한(기준 + 기간 ≤ 현재).
 * 같은 (항목, kind) 행이 없을 때만 대상이다(대상 조건의 NOT EXISTS).
 */
enum class ShipmentDelayKind(val target: BatchTarget, private val after: Duration) {
    /** PAID이고 paid_at + 3일 ≤ 현재(발송 기한 지남 — shipment-deadline이 아직 돌지 않은 항목). */
    SHIPMENT_OVERDUE(BatchTarget.SHIPMENT_OVERDUE_ITEMS, Duration.ofDays(3)),

    /** DELIVERING이고 inspected_at + 5일 ≤ 현재. */
    DELIVERY_LATE(BatchTarget.DELIVERY_LATE_ITEMS, Duration.ofDays(5)),
    ;

    fun cutoff(now: Instant): Instant = now.minus(after)
}

/** 배송 추적 조회(§12.2): events는 occurredAt·id 순, delays는 id 순. */
class Tracking(val events: List<ShipmentTrackingEvent>, val delays: List<ShipmentDelay>)

interface ShipmentTrackingEventRepository {
    fun add(event: ShipmentTrackingEvent)

    /** 그 항목의 이벤트, occurredAt·id 순. */
    fun listOf(orderItemId: Long): List<ShipmentTrackingEvent>
}

interface ShipmentDelayRepository {
    fun add(delay: ShipmentDelay)

    /** 그 항목의 지연 표시, id 순. */
    fun listOf(orderItemId: Long): List<ShipmentDelay>
}
