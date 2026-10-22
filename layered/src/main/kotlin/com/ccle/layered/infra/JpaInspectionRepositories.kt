package com.ccle.layered.infra

import com.ccle.layered.domain.Inspection
import com.ccle.layered.domain.InspectionRepository
import com.ccle.layered.domain.Refund
import com.ccle.layered.domain.RefundRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component

@Component
class JpaInspectionRepository(private val em: EntityManager) : InspectionRepository {

    override fun add(inspection: Inspection) = em.persist(inspection)
}

@Component
class JpaRefundRepository(private val em: EntityManager) : RefundRepository {

    override fun findFor(orderItemId: Long): Refund? =
        em.createQuery("SELECT r FROM Refund r WHERE r.orderItemId = :item", Refund::class.java)
            .setParameter("item", orderItemId)
            .resultList.firstOrNull()
}
