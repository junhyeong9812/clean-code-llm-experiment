package com.ccle.layered.infra

import com.ccle.layered.domain.BatchTarget
import com.ccle.layered.domain.ShipmentDelayKind
import java.time.Instant

/** delivery-delay-scan 한 종류의 대상 조건(ADR-0007 §12.2, 별칭 t = order_items): 같은 (항목, kind) 행이 없을 때만. */
internal class DelaySql private constructor(kind: ShipmentDelayKind, condition: String) {
    val targetCondition =
        "$condition AND NOT EXISTS (SELECT 1 FROM shipment_delays d WHERE d.order_item_id = t.id AND d.kind = '${kind.name}')"

    val params: (Instant) -> Map<String, Any> = { mapOf("due" to kind.cutoff(it)) }

    companion object {
        fun of(target: BatchTarget): DelaySql = when (val kind = ShipmentDelayKind.values().first { it.target == target }) {
            ShipmentDelayKind.SHIPMENT_OVERDUE -> DelaySql(kind, "t.status = 'PAID' AND t.paid_at <= :due")
            ShipmentDelayKind.DELIVERY_LATE -> DelaySql(kind, "t.status = 'DELIVERING' AND t.inspected_at <= :due")
        }
    }
}
