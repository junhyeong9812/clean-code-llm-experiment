package com.ccle.layered.infra

import com.ccle.layered.domain.BuyerWarning
import com.ccle.layered.domain.BuyerWarningRepository
import com.ccle.layered.domain.Trade
import com.ccle.layered.domain.TradeFilter
import com.ccle.layered.domain.TradeRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import java.time.Instant

// 조회는 em.find·em.createQuery로 한다(Spring Data 저장소 메서드는 트랜잭션 밖에서 부르면 호출마다 읽기 전용 트랜잭션을 연다.
// god처럼 트랜잭션 밖에서는 autocommit 1문, 안에서는 그 트랜잭션의 영속성 컨텍스트로 읽는다).
@Repository
class JpaTradeRepository(private val em: EntityManager) : TradeRepository {

    override fun add(trade: Trade) = em.persist(trade)

    override fun find(id: Long): Trade? = em.find(Trade::class.java, id)

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
        val who = if (filter.asBuyer) "t.buyerId" else "t.sellerId"
        val jpql = "SELECT t FROM Trade t WHERE $who = :user" +
            (if (filter.status != null) " AND t.status = :status" else "") +
            " ORDER BY t.id"
        val query = em.createQuery(jpql, Trade::class.java).setParameter("user", userId)
        if (filter.status != null) query.setParameter("status", filter.status)
        return query.resultList
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
