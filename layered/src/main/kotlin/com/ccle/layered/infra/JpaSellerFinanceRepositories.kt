package com.ccle.layered.infra

import com.ccle.layered.domain.FeeRebate
import com.ccle.layered.domain.FeeRebateRepository
import com.ccle.layered.domain.InitialPayoutAccount
import com.ccle.layered.domain.MonthlyStatement
import com.ccle.layered.domain.MonthlyStatementRepository
import com.ccle.layered.domain.PayoutAccountRequest
import com.ccle.layered.domain.PayoutAccountRequestRepository
import com.ccle.layered.domain.SellerGrade
import com.ccle.layered.domain.SellerGradeRepository
import com.ccle.layered.domain.Settlement
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.time.LocalDate

// A2 판매자 등급·리베이트, A3 지급 계좌·월간 정산서(ADR-0007 §5·§6). 기존 정산 표는 읽기만 한다.

@Component
class JpaSellerGradeRepository(private val em: EntityManager) : SellerGradeRepository {

    override fun add(grade: SellerGrade) = em.persist(grade)

    override fun countAt(sellerId: Long, effectiveFrom: LocalDate): Long =
        em.createQuery(
            "SELECT count(g) FROM SellerGrade g WHERE g.sellerId = :seller AND g.effectiveFrom = :d",
            Long::class.javaObjectType,
        ).setParameter("seller", sellerId).setParameter("d", effectiveFrom).singleResult

    override fun findAt(sellerId: Long, effectiveFrom: LocalDate): SellerGrade? =
        em.createQuery(
            "SELECT g FROM SellerGrade g WHERE g.sellerId = :seller AND g.effectiveFrom = :m",
            SellerGrade::class.java,
        ).setParameter("seller", sellerId).setParameter("m", effectiveFrom).resultList.firstOrNull()

    override fun basisAmount(sellerId: Long, from: LocalDate, to: LocalDate): Long =
        (
            em.createNativeQuery(
                "SELECT coalesce(sum(si.amount), 0) FROM settlement_items si JOIN settlements s ON s.id = si.settlement_id" +
                    " WHERE s.seller_id = :seller AND si.status = 'INCLUDED' AND s.period_start >= :from AND s.period_start < :d",
            ).setParameter("seller", sellerId).setParameter("from", from).setParameter("d", to).singleResult as Number
            ).toLong()

    override fun listOf(sellerId: Long): List<SellerGrade> =
        em.createQuery("SELECT g FROM SellerGrade g WHERE g.sellerId = :seller ORDER BY g.effectiveFrom", SellerGrade::class.java)
            .setParameter("seller", sellerId).resultList
}

@Component
class JpaFeeRebateRepository(private val em: EntityManager) : FeeRebateRepository {

    override fun add(rebate: FeeRebate) = em.persist(rebate)

    override fun countAt(sellerId: Long, month: LocalDate): Long =
        em.createQuery("SELECT count(f) FROM FeeRebate f WHERE f.sellerId = :seller AND f.month = :m", Long::class.javaObjectType)
            .setParameter("seller", sellerId).setParameter("m", month).singleResult

    override fun feeBase(sellerId: Long, month: LocalDate, next: LocalDate): Long =
        (
            em.createNativeQuery(
                "SELECT coalesce(sum(s.fee_total), 0) FROM settlements s" +
                    " WHERE s.seller_id = :seller AND s.period_start >= :m AND s.period_start < :next",
            ).setParameter("seller", sellerId).setParameter("m", month).setParameter("next", next).singleResult as Number
            ).toLong()

    override fun listOf(sellerId: Long): List<FeeRebate> =
        em.createQuery("SELECT f FROM FeeRebate f WHERE f.sellerId = :seller ORDER BY f.month", FeeRebate::class.java)
            .setParameter("seller", sellerId).resultList
}

@Component
class JpaPayoutAccountRequestRepository(private val em: EntityManager) : PayoutAccountRequestRepository {

    override fun add(request: PayoutAccountRequest) = em.persist(request)

    override fun find(id: Long): PayoutAccountRequest? = em.find(PayoutAccountRequest::class.java, id)

    override fun lock(id: Long) {
        em.createNativeQuery("SELECT id FROM payout_account_requests WHERE id = :id FOR UPDATE").setParameter("id", id).resultList
    }

    override fun reload(request: PayoutAccountRequest) = em.refresh(request)

    override fun countPending(userId: Long): Long =
        em.createQuery(
            "SELECT count(p) FROM PayoutAccountRequest p WHERE p.userId = :user AND p.status = 'PENDING'",
            Long::class.javaObjectType,
        ).setParameter("user", userId).singleResult

    override fun approvedOrPending(userId: Long): List<PayoutAccountRequest> =
        em.createQuery(
            "SELECT p FROM PayoutAccountRequest p WHERE p.userId = :user AND p.status IN ('APPROVED', 'PENDING') ORDER BY p.decidedAt DESC, p.id DESC",
            PayoutAccountRequest::class.java,
        ).setParameter("user", userId).resultList

    override fun initial(userId: Long): InitialPayoutAccount? {
        val row = em.createNativeQuery("SELECT bank_code, account_no_masked FROM seller_payout_accounts WHERE user_id = :user")
            .setParameter("user", userId).resultList.firstOrNull() as Array<*>? ?: return null
        return InitialPayoutAccount(row[0] as String, row[1] as String)
    }
}

@Component
class JpaMonthlyStatementRepository(private val em: EntityManager) : MonthlyStatementRepository {

    override fun add(statement: MonthlyStatement) = em.persist(statement)

    override fun countAt(sellerId: Long, month: LocalDate): Long =
        em.createQuery(
            "SELECT count(ms) FROM MonthlyStatement ms WHERE ms.sellerId = :seller AND ms.month = :m",
            Long::class.javaObjectType,
        ).setParameter("seller", sellerId).setParameter("m", month).singleResult

    override fun settlementsIn(sellerId: Long, month: LocalDate, next: LocalDate): List<Settlement> =
        em.createQuery(
            "SELECT s FROM Settlement s WHERE s.sellerId = :seller AND s.periodStart >= :m AND s.periodStart < :next ORDER BY s.periodStart",
            Settlement::class.java,
        ).setParameter("seller", sellerId).setParameter("m", month).setParameter("next", next).resultList

    override fun heldRows(settlementIds: List<Long>): Long =
        em.createQuery(
            "SELECT count(i) FROM SettlementItem i WHERE i.settlementId IN :ids AND i.status = 'HELD'",
            Long::class.javaObjectType,
        ).setParameter("ids", settlementIds).singleResult

    override fun listOf(sellerId: Long): List<MonthlyStatement> =
        em.createQuery("SELECT ms FROM MonthlyStatement ms WHERE ms.sellerId = :seller ORDER BY ms.month", MonthlyStatement::class.java)
            .setParameter("seller", sellerId).resultList
}
