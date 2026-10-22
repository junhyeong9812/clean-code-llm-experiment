package com.ccle.layered.infra

import com.ccle.layered.domain.Dispute
import com.ccle.layered.domain.DisputeRepository
import com.ccle.layered.domain.SellerPenalty
import com.ccle.layered.domain.SellerPenaltyRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class JpaDisputeRepository(private val em: EntityManager) : DisputeRepository {

    override fun find(id: Long): Dispute? = em.find(Dispute::class.java, id)

    override fun add(dispute: Dispute) = em.persist(dispute)

    override fun reload(dispute: Dispute) = em.refresh(dispute)
}

@Component
class JpaSellerPenaltyRepository(private val em: EntityManager) : SellerPenaltyRepository {

    override fun add(penalty: SellerPenalty) = em.persist(penalty)

    override fun createdAtAfter(sellerId: Long, from: Instant): List<Instant> =
        em.createQuery(
            "SELECT p.createdAt FROM SellerPenalty p WHERE p.sellerId = :seller AND p.createdAt > :from",
            Instant::class.java,
        ).setParameter("seller", sellerId).setParameter("from", from).resultList
}
