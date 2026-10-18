package com.ccle.layered.infra

import com.ccle.layered.domain.PointAllocation
import com.ccle.layered.domain.PointBalance
import com.ccle.layered.domain.PointBalanceRepository
import com.ccle.layered.domain.PointLedgerEntry
import com.ccle.layered.domain.PointRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import java.time.Instant

// 조회는 em.find·em.createQuery로 한다(Spring Data 저장소 메서드는 트랜잭션 밖에서 부르면 호출마다 읽기 전용 트랜잭션을 연다.
// god처럼 트랜잭션 밖에서는 autocommit 1문, 안에서는 그 트랜잭션의 영속성 컨텍스트로 읽는다).
@Repository
class JpaPointRepository(private val em: EntityManager) : PointRepository {

    override fun find(entryId: Long): PointLedgerEntry =
        em.find(PointLedgerEntry::class.java, entryId) ?: throw IllegalStateException("포인트 행이 없다: $entryId")

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

@Repository
class JpaPointBalanceRepository(private val em: EntityManager) : PointBalanceRepository {

    override fun find(userId: Long): PointBalance? = em.find(PointBalance::class.java, userId)

    // id를 직접 정하는 엔티티라 merge가 아니라 persist로 넣는다(새 행)
    override fun add(balance: PointBalance) = em.persist(balance)
}
