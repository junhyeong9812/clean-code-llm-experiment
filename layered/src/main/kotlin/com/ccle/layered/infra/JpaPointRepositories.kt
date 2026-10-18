package com.ccle.layered.infra

import com.ccle.layered.domain.PointAllocation
import com.ccle.layered.domain.PointBalance
import com.ccle.layered.domain.PointBalanceRepository
import com.ccle.layered.domain.PointLedgerEntry
import com.ccle.layered.domain.PointRepository
import jakarta.persistence.EntityManager
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant

interface PointLedgerJpaRepository : JpaRepository<PointLedgerEntry, Long>

@Repository
class JpaPointRepository(private val jpa: PointLedgerJpaRepository, private val em: EntityManager) : PointRepository {

    override fun find(entryId: Long): PointLedgerEntry = jpa.findById(entryId).orElseThrow()

    override fun add(entry: PointLedgerEntry) = em.persist(entry)

    override fun usable(userId: Long, now: Instant): Long {
        val sum = em.createNativeQuery(
            "SELECT coalesce(sum(remaining), 0) FROM point_ledger" +
                " WHERE user_id = :user AND kind = 'EARN' AND status = 'AVAILABLE' AND expires_at > :now",
        ).setParameter("user", userId).setParameter("now", now).singleResult as Number
        return sum.toLong()
    }

    override fun usableBundles(userId: Long, now: Instant): List<PointLedgerEntry> =
        em.createNativeQuery(
            "SELECT * FROM point_ledger" +
                " WHERE user_id = :user AND kind = 'EARN' AND status = 'AVAILABLE' AND expires_at > :now AND remaining > 0" +
                " ORDER BY expires_at, id",
            PointLedgerEntry::class.java,
        ).setParameter("user", userId).setParameter("now", now).resultList.map { it as PointLedgerEntry }

    override fun useEntryOf(orderId: Long): PointLedgerEntry? =
        em.createQuery("SELECT l FROM PointLedgerEntry l WHERE l.orderId = :order AND l.kind = 'USE'", PointLedgerEntry::class.java)
            .setParameter("order", orderId)
            .resultList.firstOrNull()

    override fun addAllocation(allocation: PointAllocation) = em.persist(allocation)

    override fun allocationsOf(useEntryId: Long): List<PointAllocation> =
        em.createQuery("SELECT a FROM PointAllocation a WHERE a.entryId = :use", PointAllocation::class.java)
            .setParameter("use", useEntryId)
            .resultList

    override fun removeAllocation(allocation: PointAllocation) = em.remove(allocation)
}

interface PointBalanceJpaRepository : JpaRepository<PointBalance, Long>

@Repository
class JpaPointBalanceRepository(private val jpa: PointBalanceJpaRepository, private val em: EntityManager) : PointBalanceRepository {

    override fun find(userId: Long): PointBalance? = jpa.findById(userId).orElse(null)

    // id를 직접 정하는 엔티티라 Spring Data save는 merge(먼저 SELECT)가 된다. 새 행이므로 persist로 넣는다
    override fun add(balance: PointBalance) = em.persist(balance)
}
