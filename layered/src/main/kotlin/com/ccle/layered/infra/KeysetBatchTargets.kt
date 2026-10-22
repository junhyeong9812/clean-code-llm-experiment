package com.ccle.layered.infra

import com.ccle.layered.domain.BatchTarget
import com.ccle.layered.domain.BatchTargets
import com.ccle.layered.domain.GradePeriod
import com.ccle.layered.domain.OrderItem
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 배치 대상 id를 id 순 키셋 페이지(500개씩)로 읽는다. 시작할 때의 max(id)까지만 본다.
 * 조건은 별칭 t로 쓴 WHERE 절이다. UNKNOWN·PAYING 주문의 항목은 모두 AWAITING_PAYMENT이므로(verify order_status_vs_items)
 * payment-due의 항목 조건 status <> 'PAYMENT_FAILED'를 붙여도 뜻은 같고, 부분 인덱스 order_items_live_trade_key를 탄다.
 */
@Component
class KeysetBatchTargets(private val em: EntityManager) : BatchTargets {

    /** params = 현재 시각에서 정한 조건 파라미터(이름 → 값). */
    private class Spec(val table: String, val condition: String, val params: (Instant) -> Map<String, Any>)

    private fun spec(target: BatchTarget): Spec = when (target) {
        BatchTarget.EXPIRED_BIDS -> Spec("bids", "t.status = 'ACTIVE' AND t.expires_at <= :now", ::nowParam)
        BatchTarget.OVERDUE_TRADES -> Spec(
            "trades",
            "t.status = 'AWAITING_PAYMENT' AND t.payment_due_at <= :now" +
                " AND NOT EXISTS (SELECT 1 FROM order_items i JOIN orders o ON o.id = i.order_id" +
                "                 WHERE i.trade_id = t.id AND i.status <> 'PAYMENT_FAILED' AND o.status IN ('UNKNOWN', 'PAYING'))",
            ::nowParam,
        )
        BatchTarget.UNKNOWN_ORDERS -> Spec("orders", "t.status = 'UNKNOWN'") { emptyMap() }
        BatchTarget.PAYING_ORDERS -> Spec("orders", "t.status = 'PAYING'") { emptyMap() }
        BatchTarget.UNSHIPPED_ITEMS -> Spec("order_items", "t.status = 'PAID' AND t.paid_at <= :due") {
            mapOf("due" to OrderItem.shipmentOverdueCutoff(it))
        }
        BatchTarget.UNCONFIRMED_ITEMS -> Spec("order_items", "t.status = 'DELIVERED' AND t.delivered_at <= :due") {
            mapOf("due" to OrderItem.autoConfirmCutoff(it))
        }
        BatchTarget.EXPIRED_POINTS -> Spec(
            "point_ledger",
            "t.kind = 'EARN' AND t.status = 'AVAILABLE' AND t.remaining > 0 AND t.expires_at <= :now",
            ::nowParam,
        )
        BatchTarget.GRADE_CHANGES -> Spec("users", "$noGradeHistoryAtD AND t.grade <> $gradeSql") { gradeParams(GradePeriod.of(it)) }
    }

    private fun nowParam(now: Instant): Map<String, Any> = mapOf("now" to now)

    override fun forEach(target: BatchTarget, now: Instant, handle: (Long) -> Boolean): Int {
        val spec = spec(target)
        val params = spec.params(now)
        val upTo = (em.createNativeQuery("SELECT coalesce(max(id), 0) FROM ${spec.table}").singleResult as Number).toLong()
        var after = 0L
        var processed = 0
        while (true) {
            val query = em.createNativeQuery(
                "SELECT t.id FROM ${spec.table} t WHERE (${spec.condition}) AND t.id > :after AND t.id <= :upTo ORDER BY t.id LIMIT 500",
            ).setParameter("after", after).setParameter("upTo", upTo)
            params.forEach { (name, value) -> query.setParameter(name, value) }
            val ids = query.resultList.map { (it as Number).toLong() }
            if (ids.isEmpty()) return processed
            for (id in ids) {
                if (handle(id)) processed++
            }
            after = ids.last()
        }
    }
}
