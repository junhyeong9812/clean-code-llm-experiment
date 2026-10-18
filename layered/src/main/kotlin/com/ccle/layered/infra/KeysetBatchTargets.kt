package com.ccle.layered.infra

import com.ccle.layered.domain.BatchTarget
import com.ccle.layered.domain.BatchTargets
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 배치 대상 id를 id 순 키셋 페이지(500개씩)로 읽는다. 시작할 때의 max(id)까지만 본다.
 * 조건은 별칭 t로 쓴 WHERE 절이다. UNKNOWN·PAYING 주문의 항목은 모두 AWAITING_PAYMENT이므로(verify order_status_vs_items)
 * payment-due의 항목 조건 status <> 'PAYMENT_FAILED'를 붙여도 뜻은 같고, 부분 인덱스 order_items_live_trade_key를 탄다.
 * @Repository가 아니다: 영속 예외 변환 프록시가 handle(배치 처리)에서 올라온 예외까지 바꾸지 않게.
 */
@Component
class KeysetBatchTargets(private val em: EntityManager) : BatchTargets {

    private class Spec(val table: String, val condition: String, val usesNow: Boolean)

    private fun spec(target: BatchTarget): Spec = when (target) {
        BatchTarget.EXPIRED_BIDS -> Spec("bids", "t.status = 'ACTIVE' AND t.expires_at <= :now", usesNow = true)
        BatchTarget.OVERDUE_TRADES -> Spec(
            "trades",
            "t.status = 'AWAITING_PAYMENT' AND t.payment_due_at <= :now" +
                " AND NOT EXISTS (SELECT 1 FROM order_items i JOIN orders o ON o.id = i.order_id" +
                "                 WHERE i.trade_id = t.id AND i.status <> 'PAYMENT_FAILED' AND o.status IN ('UNKNOWN', 'PAYING'))",
            usesNow = true,
        )
        BatchTarget.UNKNOWN_ORDERS -> Spec("orders", "t.status = 'UNKNOWN'", usesNow = false)
        BatchTarget.PAYING_ORDERS -> Spec("orders", "t.status = 'PAYING'", usesNow = false)
    }

    override fun forEach(target: BatchTarget, now: Instant, handle: (Long) -> Boolean): Int {
        val spec = spec(target)
        val upTo = (em.createNativeQuery("SELECT coalesce(max(id), 0) FROM ${spec.table}").singleResult as Number).toLong()
        var after = 0L
        var processed = 0
        while (true) {
            val query = em.createNativeQuery(
                "SELECT t.id FROM ${spec.table} t WHERE (${spec.condition}) AND t.id > :after AND t.id <= :upTo ORDER BY t.id LIMIT 500",
            ).setParameter("after", after).setParameter("upTo", upTo)
            if (spec.usesNow) query.setParameter("now", now)
            val ids = query.resultList.map { (it as Number).toLong() }
            if (ids.isEmpty()) return processed
            for (id in ids) {
                if (handle(id)) processed++
            }
            after = ids.last()
        }
    }
}
