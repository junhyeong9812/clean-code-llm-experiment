package com.ccle.layered.api

import com.ccle.layered.domain.Notification
import com.ccle.layered.domain.PriceAlert
import com.ccle.layered.domain.ShipmentDelay
import com.ccle.layered.domain.ShipmentTrackingEvent
import com.ccle.layered.domain.Tracking
import com.ccle.layered.domain.Wishlist
import com.ccle.layered.domain.WishlistItem
import java.time.Instant

// 응답 본문 모양(ADR-0007 §7.2 A4 · §8 A5 · §12.2 A11). Instant는 KST ISO-8601(Json.kt).

/** 알림 모양(§7.2). */
data class NotificationView(val notificationId: Long, val kind: String, val refId: Long, val createdAt: Instant, val readAt: Instant?) {
    constructor(n: Notification) : this(n.id!!, n.kind, n.refId, n.createdAt, n.readAt)
}

/** 일괄 읽음 응답(§20.6.1). */
data class ReadAllView(val readCount: Long)

/** 안 읽은 수(§20.6.2). */
data class UnreadCountView(val unreadCount: Long)

/** 위시리스트 등록·삭제 응답(§8.1). */
data class WishlistEntryView(val productSizeId: Long, val createdAt: Instant) {
    constructor(w: Wishlist) : this(w.productSizeId, w.createdAt)
}

/** 위시리스트 조회 1행(§8.1). */
data class WishlistItemView(val productSizeId: Long, val createdAt: Instant, val bestSell: Long?, val activeAlert: PriceAlertView?) {
    constructor(i: WishlistItem) : this(i.wish.productSizeId, i.wish.createdAt, i.bestSell, i.activeAlert?.let { PriceAlertView(it) })
}

/** 가격 알림 모양(§8.2). */
data class PriceAlertView(
    val alertId: Long,
    val productSizeId: Long,
    val targetPrice: Long,
    val status: String,
    val expiresAt: Instant,
    val triggeredBidId: Long?,
    val closedAt: Instant?,
    val createdAt: Instant,
) {
    constructor(a: PriceAlert) : this(a.id!!, a.productSizeId, a.targetPrice, a.status, a.expiresAt, a.triggeredBidId, a.closedAt, a.createdAt)
}

/** 배송 추적 이벤트 모양(§12.2). */
data class TrackingEventView(
    val eventId: Long,
    val orderItemId: Long,
    val carrier: String,
    val trackingNo: String,
    val eventType: String,
    val occurredAt: Instant,
    val receivedAt: Instant,
) {
    constructor(e: ShipmentTrackingEvent) : this(e.id!!, e.orderItemId, e.carrier, e.trackingNo, e.eventType, e.occurredAt, e.receivedAt)
}

data class ShipmentDelayView(val kind: String, val detectedAt: Instant) {
    constructor(d: ShipmentDelay) : this(d.kind, d.detectedAt)
}

/** 배송 추적 조회(§12.2). */
data class TrackingView(val events: List<TrackingEventView>, val delays: List<ShipmentDelayView>) {
    constructor(t: Tracking) : this(t.events.map { TrackingEventView(it) }, t.delays.map { ShipmentDelayView(it) })
}
