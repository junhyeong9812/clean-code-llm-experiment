package com.ccle.layered.infra

import com.ccle.layered.domain.ShipmentDelay
import com.ccle.layered.domain.ShipmentDelayRepository
import com.ccle.layered.domain.ShipmentTrackingEvent
import com.ccle.layered.domain.ShipmentTrackingEventRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component

@Component
class JpaShipmentTrackingEventRepository(private val em: EntityManager) : ShipmentTrackingEventRepository {

    override fun add(event: ShipmentTrackingEvent) = em.persist(event)

    override fun listOf(orderItemId: Long): List<ShipmentTrackingEvent> =
        em.createQuery(
            "SELECT e FROM ShipmentTrackingEvent e WHERE e.orderItemId = :item ORDER BY e.occurredAt, e.id",
            ShipmentTrackingEvent::class.java,
        ).setParameter("item", orderItemId).resultList
}

@Component
class JpaShipmentDelayRepository(private val em: EntityManager) : ShipmentDelayRepository {

    override fun add(delay: ShipmentDelay) = em.persist(delay)

    override fun listOf(orderItemId: Long): List<ShipmentDelay> =
        em.createQuery("SELECT d FROM ShipmentDelay d WHERE d.orderItemId = :item ORDER BY d.id", ShipmentDelay::class.java)
            .setParameter("item", orderItemId).resultList
}
