package com.ccle.layered.infra

import com.ccle.layered.domain.AdminAuditLog
import com.ccle.layered.domain.AdminQueryRepository
import com.ccle.layered.domain.BuyerWarning
import com.ccle.layered.domain.Dispute
import com.ccle.layered.domain.KeysetPage
import com.ccle.layered.domain.RestrictionType
import com.ccle.layered.domain.SellerGrade
import com.ccle.layered.domain.SellerPenalty
import com.ccle.layered.domain.Settlement
import com.ccle.layered.domain.SettlementFilter
import com.ccle.layered.domain.User
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class JpaAdminQueryRepository(private val em: EntityManager) : AdminQueryRepository {

    override fun restrictedUsers(type: RestrictionType, now: Instant, page: KeysetPage): List<User> {
        val column = when (type) {
            RestrictionType.PURCHASE -> "purchaseRestrictedUntil"
            RestrictionType.SALE -> "saleRestrictedUntil"
        }
        return em.createQuery("SELECT u FROM User u WHERE u.$column > :now AND u.id > :after ORDER BY u.id", User::class.java)
            .setParameter("now", now).setParameter("after", page.after).setMaxResults(page.limit).resultList
    }

    override fun countWarnings(userId: Long): Long =
        em.createQuery("SELECT count(w) FROM BuyerWarning w WHERE w.userId = :user", Long::class.javaObjectType)
            .setParameter("user", userId).singleResult

    override fun countPenalties(userId: Long): Long =
        em.createQuery("SELECT count(p) FROM SellerPenalty p WHERE p.sellerId = :user", Long::class.javaObjectType)
            .setParameter("user", userId).singleResult

    override fun latestSellerGrade(sellerId: Long): SellerGrade? =
        em.createQuery("SELECT g FROM SellerGrade g WHERE g.sellerId = :seller ORDER BY g.effectiveFrom DESC", SellerGrade::class.java)
            .setParameter("seller", sellerId).setMaxResults(1).resultList.firstOrNull()

    override fun penaltiesOf(sellerId: Long, page: KeysetPage): List<SellerPenalty> =
        em.createQuery(
            "SELECT p FROM SellerPenalty p WHERE p.sellerId = :user AND p.id > :after ORDER BY p.id",
            SellerPenalty::class.java,
        ).setParameter("user", sellerId).setParameter("after", page.after).setMaxResults(page.limit).resultList

    override fun warningsOf(userId: Long, page: KeysetPage): List<BuyerWarning> =
        em.createQuery(
            "SELECT w FROM BuyerWarning w WHERE w.userId = :user AND w.id > :after ORDER BY w.id",
            BuyerWarning::class.java,
        ).setParameter("user", userId).setParameter("after", page.after).setMaxResults(page.limit).resultList

    override fun settlements(filter: SettlementFilter, page: KeysetPage): List<Settlement> {
        val jpql = "SELECT s FROM Settlement s WHERE s.id > :after" +
            (if (filter.status != null) " AND s.status = :status" else "") +
            (if (filter.sellerId != null) " AND s.sellerId = :seller" else "") +
            (if (filter.from != null) " AND s.periodStart >= :from" else "") +
            (if (filter.to != null) " AND s.periodStart <= :to" else "") +
            " ORDER BY s.id"
        val query = em.createQuery(jpql, Settlement::class.java).setParameter("after", page.after)
        filter.status?.let { query.setParameter("status", it) }
        filter.sellerId?.let { query.setParameter("seller", it) }
        filter.from?.let { query.setParameter("from", it) }
        filter.to?.let { query.setParameter("to", it) }
        return query.setMaxResults(page.limit).resultList
    }

    override fun disputes(status: String?, page: KeysetPage): List<Dispute> {
        val query = em.createQuery(
            "SELECT d FROM Dispute d WHERE d.id > :after" + (if (status != null) " AND d.status = :status" else "") + " ORDER BY d.id",
            Dispute::class.java,
        ).setParameter("after", page.after)
        status?.let { query.setParameter("status", it) }
        return query.setMaxResults(page.limit).resultList
    }

    override fun auditLogs(action: String?, page: KeysetPage): List<AdminAuditLog> {
        val query = em.createQuery(
            "SELECT a FROM AdminAuditLog a WHERE a.id > :after" + (if (action != null) " AND a.action = :action" else "") + " ORDER BY a.id",
            AdminAuditLog::class.java,
        ).setParameter("after", page.after)
        action?.let { query.setParameter("action", it) }
        return query.setMaxResults(page.limit).resultList
    }
}
