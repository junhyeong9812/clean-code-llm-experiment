package com.ccle.layered.infra

import com.ccle.layered.domain.PriceStatDaily
import com.ccle.layered.domain.PriceStatRepository
import com.ccle.layered.domain.PriceStatsRange
import com.ccle.layered.domain.Trade
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class JpaPriceStatRepository(private val em: EntityManager) : PriceStatRepository {

    override fun add(stat: PriceStatDaily) = em.persist(stat)

    override fun paidPricesBetween(productSizeId: Long, from: Instant, to: Instant): List<Long> =
        em.createQuery(
            "SELECT tr.price FROM Trade tr WHERE tr.productSizeId = :size AND tr.status = 'PAID'" +
                " AND tr.matchedAt >= :from AND tr.matchedAt < :to ORDER BY tr.price",
            Long::class.javaObjectType,
        ).setParameter("size", productSizeId).setParameter("from", from).setParameter("to", to).resultList

    override fun listOf(productSizeId: Long, range: PriceStatsRange): List<PriceStatDaily> =
        em.createQuery(
            "SELECT p FROM PriceStatDaily p WHERE p.productSizeId = :size AND p.statDate >= :from AND p.statDate <= :to ORDER BY p.statDate",
            PriceStatDaily::class.java,
        ).setParameter("size", productSizeId).setParameter("from", range.from).setParameter("to", range.to).resultList

    override fun recentPaidTrades(productSizeId: Long, limit: Int): List<Trade> =
        em.createQuery(
            "SELECT t FROM Trade t WHERE t.productSizeId = :size AND t.status = 'PAID' ORDER BY t.matchedAt DESC, t.id DESC",
            Trade::class.java,
        ).setParameter("size", productSizeId).setMaxResults(limit).resultList
}
