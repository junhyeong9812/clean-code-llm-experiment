package com.ccle.layered.infra

import com.ccle.layered.domain.BuyerWarning
import com.ccle.layered.domain.BuyerWarningRepository
import com.ccle.layered.domain.Trade
import com.ccle.layered.domain.TradeFilter
import com.ccle.layered.domain.TradeRepository
import jakarta.persistence.EntityManager
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant

interface TradeJpaRepository : JpaRepository<Trade, Long> {
    fun findByBuyerIdOrderById(buyerId: Long): List<Trade>
    fun findByBuyerIdAndStatusOrderById(buyerId: Long, status: String): List<Trade>
    fun findBySellerIdOrderById(sellerId: Long): List<Trade>
    fun findBySellerIdAndStatusOrderById(sellerId: Long, status: String): List<Trade>
}

@Repository
class JpaTradeRepository(private val jpa: TradeJpaRepository, private val em: EntityManager) : TradeRepository {

    override fun add(trade: Trade) = em.persist(trade)

    override fun find(id: Long): Trade? = jpa.findById(id).orElse(null)

    override fun findAll(ids: List<Long>): List<Trade> =
        em.createQuery("SELECT t FROM Trade t WHERE t.id IN :ids ORDER BY t.id", Trade::class.java)
            .setParameter("ids", ids)
            .resultList

    override fun liveTradeIdOf(bidId: Long): Long? =
        (
            em.createNativeQuery(
                "SELECT id FROM trades WHERE (buy_bid_id = :bid OR sell_bid_id = :bid) AND status <> 'PAYMENT_EXPIRED'",
            ).setParameter("bid", bidId).resultList.firstOrNull() as Number?
            )?.toLong()

    override fun reload(trade: Trade) = em.refresh(trade)

    override fun flush() = em.flush()

    override fun listOf(userId: Long, filter: TradeFilter): List<Trade> {
        val status = filter.status
        return if (filter.asBuyer) {
            if (status == null) jpa.findByBuyerIdOrderById(userId) else jpa.findByBuyerIdAndStatusOrderById(userId, status)
        } else {
            if (status == null) jpa.findBySellerIdOrderById(userId) else jpa.findBySellerIdAndStatusOrderById(userId, status)
        }
    }
}

@Repository
class JpaBuyerWarningRepository(private val em: EntityManager) : BuyerWarningRepository {

    override fun add(warning: BuyerWarning) = em.persist(warning)

    override fun createdAtAfter(userId: Long, from: Instant): List<Instant> =
        em.createQuery(
            "SELECT w.createdAt FROM BuyerWarning w WHERE w.userId = :user AND w.createdAt > :from",
            Instant::class.java,
        ).setParameter("user", userId).setParameter("from", from).resultList
}
