package com.ccle.layered.infra

import com.ccle.layered.domain.InboundReservation
import com.ccle.layered.domain.InboundReservationRepository
import com.ccle.layered.domain.InboundSlot
import com.ccle.layered.domain.InboundSlotDay
import com.ccle.layered.domain.InboundSlotInput
import com.ccle.layered.domain.InboundSlotRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class JpaInboundSlotRepository(private val em: EntityManager) : InboundSlotRepository {

    override fun find(id: Long): InboundSlot? = em.find(InboundSlot::class.java, id)

    override fun insertIfAbsent(input: InboundSlotInput, now: Instant): Long? {
        val inserted = em.createNativeQuery(
            "INSERT INTO inbound_slots (slot_start, capacity, created_at) VALUES (:start, :capacity, :now)" +
                " ON CONFLICT (slot_start) DO NOTHING",
        ).setParameter("start", input.slotStart).setParameter("capacity", input.capacity).setParameter("now", now).executeUpdate()
        if (inserted == 0) return null
        return (
            em.createNativeQuery("SELECT id FROM inbound_slots WHERE slot_start = :start")
                .setParameter("start", input.slotStart).singleResult as Number
            ).toLong()
    }

    override fun lock(id: Long) {
        em.createNativeQuery("SELECT id FROM inbound_slots WHERE id = :id FOR UPDATE").setParameter("id", id).resultList
    }

    override fun listOn(day: InboundSlotDay): List<InboundSlot> =
        em.createQuery(
            "SELECT s FROM InboundSlot s WHERE s.slotStart >= :from AND s.slotStart < :to ORDER BY s.slotStart",
            InboundSlot::class.java,
        ).setParameter("from", day.from).setParameter("to", day.to).resultList

    override fun countLive(slotId: Long): Long =
        em.createQuery(
            "SELECT count(r) FROM InboundReservation r WHERE r.slotId = :slot AND r.status IN ('RESERVED', 'CHECKED_IN')",
            Long::class.javaObjectType,
        ).setParameter("slot", slotId).singleResult
}

@Component
class JpaInboundReservationRepository(private val em: EntityManager) : InboundReservationRepository {

    override fun find(id: Long): InboundReservation? = em.find(InboundReservation::class.java, id)

    override fun add(reservation: InboundReservation) = em.persist(reservation)

    override fun lock(id: Long) {
        em.createNativeQuery("SELECT id FROM inbound_reservations WHERE id = :id FOR UPDATE").setParameter("id", id).resultList
    }

    override fun reload(reservation: InboundReservation) = em.refresh(reservation)

    override fun countLiveOfItem(orderItemId: Long): Long =
        em.createQuery(
            "SELECT count(r) FROM InboundReservation r WHERE r.orderItemId = :item AND r.status IN ('RESERVED', 'CHECKED_IN')",
            Long::class.javaObjectType,
        ).setParameter("item", orderItemId).singleResult

    override fun listOf(sellerId: Long): List<InboundReservation> =
        em.createQuery("SELECT r FROM InboundReservation r WHERE r.sellerId = :seller ORDER BY r.id", InboundReservation::class.java)
            .setParameter("seller", sellerId).resultList
}
