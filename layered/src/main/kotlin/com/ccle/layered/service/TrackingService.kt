package com.ccle.layered.service

import com.ccle.layered.domain.BatchTargets
import com.ccle.layered.domain.MarketClock
import com.ccle.layered.domain.OrderItemRepository
import com.ccle.layered.domain.ShipmentDelay
import com.ccle.layered.domain.ShipmentDelayKind
import com.ccle.layered.domain.ShipmentDelayRepository
import com.ccle.layered.domain.ShipmentTrackingEvent
import com.ccle.layered.domain.ShipmentTrackingEventRepository
import com.ccle.layered.domain.Tracking
import com.ccle.layered.domain.TrackingEventInput
import com.ccle.layered.domain.TradeRepository
import com.ccle.layered.domain.notFound
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/** A11 배송 추적 이벤트 기록(관리자)·조회(ADR-0007 §12.2). 항목 상태는 바꾸지 않는다. */
@Service
class TrackingService(
    private val clock: MarketClock,
    private val tx: TransactionTemplate,
    private val items: OrderItemRepository,
    private val trades: TradeRepository,
    private val events: ShipmentTrackingEventRepository,
    private val delays: ShipmentDelayRepository,
    private val audit: AdminAuditService,
) {
    private val snapshotTx = TransactionTemplate(tx.transactionManager!!).apply {
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        isReadOnly = true
    }

    /** 기록: 400 → 404(항목) → 409(추적할 수 없는 상태). 1행(received_at = 현재) + 감사 TRACKING_EVENT(target = 새 이벤트 id, detail = event_type). */
    fun record(itemId: Long, carrier: String?, trackingNo: String?, eventType: String?, occurredAt: Instant?): ShipmentTrackingEvent {
        val now = clock.now()
        val input = TrackingEventInput.of(carrier, trackingNo, eventType, occurredAt, now)
        return tx.execute {
            val item = items.find(itemId) ?: notFound("없는 주문 항목: $itemId")
            val event = ShipmentTrackingEvent.record(item, input, now)
            events.add(event)
            audit.record("TRACKING_EVENT", event.id!!, event.eventType, now)
            event
        }!!
    }

    /** 조회: 404 → 403(구매자도 판매자도 아님). 한 스냅샷에서 이벤트·지연 표시를 읽는다. */
    fun tracking(userId: Long, itemId: Long): Tracking = snapshotTx.execute {
        val item = items.find(itemId) ?: notFound("없는 주문 항목: $itemId")
        item.requireParty(trades.find(item.tradeId)!!, userId)
        Tracking(events.listOf(itemId), delays.listOf(itemId))
    }!!
}

/**
 * delivery-delay-scan(ADR-0007 §12.2): 두 종류를 차례로(order_items id 순). 같은 (항목, kind) 행이 없을 때만 1행(detected_at = 현재).
 * 잠금 없음, 항목 상태는 바꾸지 않는다. processed = 쓴 행 수.
 */
@Component
class DeliveryDelayScanBatch(
    private val targets: BatchTargets,
    private val tx: TransactionTemplate,
    private val delays: ShipmentDelayRepository,
) {
    fun run(now: Instant): BatchResult {
        var processed = 0
        var failed = 0
        for (kind in ShipmentDelayKind.values()) {
            val result = targets.forEachIsolated("delivery-delay-scan", kind.target, now) { itemId ->
                tx.execute {
                    delays.add(ShipmentDelay(orderItemId = itemId, kind = kind.name, detectedAt = now))
                    true
                }!!
            }
            processed += result.processed
            failed += result.failed
        }
        return BatchResult(processed, failed)
    }
}
