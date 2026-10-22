package com.ccle.layered.infra

import com.ccle.layered.domain.Settlement
import com.ccle.layered.domain.SettlementItem
import com.ccle.layered.domain.SettlementRepository
import com.ccle.layered.domain.SettlementTarget
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate

@Component
class JpaSettlementRepository(private val em: EntityManager) : SettlementRepository {

    /**
     * 정산 주(마감 :cutoff)의 대상 항목 조건(ADR-0004 §10.2, 별칭 i). 경계는 모두 cutoff 배타.
     * INCLUDED·HELD: CONFIRMED·DISPUTED이고 confirmed_at < cutoff, 아직 INCLUDED가 없는 것(HELD는 주마다 다시 쓴다).
     * CLAWBACK: REFUNDED_AFTER_CONFIRM이고 cancelled_at < cutoff, INCLUDED 수 > CLAWBACK 수.
     */
    private val targetCondition =
        "(i.status IN ('CONFIRMED', 'DISPUTED') AND i.confirmed_at < :cutoff" +
            " AND NOT EXISTS (SELECT 1 FROM settlement_items s WHERE s.order_item_id = i.id AND s.status = 'INCLUDED'))" +
            " OR (i.status = 'REFUNDED_AFTER_CONFIRM' AND i.cancelled_at < :cutoff" +
            " AND (SELECT count(*) FILTER (WHERE s.status = 'INCLUDED') - count(*) FILTER (WHERE s.status = 'CLAWBACK')" +
            "      FROM settlement_items s WHERE s.order_item_id = i.id) > 0)"

    override fun lastPeriodStart(): LocalDate? =
        em.createQuery("SELECT max(s.periodStart) FROM Settlement s", LocalDate::class.java).singleResult

    override fun lockTargets(cutoff: Instant) {
        em.createNativeQuery("SELECT i.id FROM order_items i WHERE $targetCondition ORDER BY i.id FOR UPDATE")
            .setParameter("cutoff", cutoff).resultList
    }

    // 수수료는 DB에서 계산한다: sale_fee_rate가 numeric이라 체결가 × 율이 정확하고 floor가 verify와 같은 식이다
    override fun targets(cutoff: Instant): List<SettlementTarget> =
        em.createNativeQuery(
            "SELECT i.id, t.seller_id, i.status, i.price, CAST(floor(i.price * c.sale_fee_rate) AS bigint)" +
                " FROM order_items i JOIN trades t ON t.id = i.trade_id JOIN product_sizes ps ON ps.id = t.product_size_id" +
                " JOIN products p ON p.id = ps.product_id JOIN categories c ON c.id = p.category_id" +
                " WHERE $targetCondition ORDER BY i.id",
        ).setParameter("cutoff", cutoff).resultList.map { row ->
            row as Array<*>
            SettlementTarget(
                orderItemId = (row[0] as Number).toLong(),
                sellerId = (row[1] as Number).toLong(),
                itemStatus = row[2] as String,
                price = (row[3] as Number).toLong(),
                saleFee = (row[4] as Number).toLong(),
            )
        }

    // 판매자의 직전 정산 = period_start가 가장 늦은 정산. carry_out이 0이면 carry_in 0이므로 음수만 읽는다
    override fun negativeCarries(): Map<Long, Long> =
        em.createNativeQuery(
            "SELECT s.seller_id, s.carry_out FROM settlements s WHERE s.carry_out < 0" +
                " AND s.period_start = (SELECT max(s2.period_start) FROM settlements s2 WHERE s2.seller_id = s.seller_id)",
        ).resultList.associate { row ->
            row as Array<*>
            (row[0] as Number).toLong() to (row[1] as Number).toLong()
        }

    override fun add(settlement: Settlement) = em.persist(settlement)

    override fun addItem(item: SettlementItem) = em.persist(item)

    override fun listOf(sellerId: Long): List<Settlement> =
        em.createQuery(
            "SELECT s FROM Settlement s WHERE s.sellerId = :seller ORDER BY s.periodStart",
            Settlement::class.java,
        ).setParameter("seller", sellerId).resultList

    override fun itemsOf(settlementIds: List<Long>): List<SettlementItem> =
        em.createQuery(
            "SELECT i FROM SettlementItem i WHERE i.settlementId IN :ids ORDER BY i.orderItemId, i.id",
            SettlementItem::class.java,
        ).setParameter("ids", settlementIds).resultList
}
