package com.ccle.layered.service

import com.ccle.layered.domain.BatchTarget
import com.ccle.layered.domain.BatchTargets
import com.ccle.layered.domain.ClosedMonth
import com.ccle.layered.domain.FeeRebate
import com.ccle.layered.domain.FeeRebateRepository
import com.ccle.layered.domain.SellerGrade
import com.ccle.layered.domain.SellerGradeMonth
import com.ccle.layered.domain.SellerGradeRepository
import com.ccle.layered.domain.SettlementRepository
import com.ccle.layered.domain.UserRepository
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/** 판매자 등급 조회 결과(ADR-0007 §5.4): current = effective_from이 가장 늦은 행, history = effective_from 오름차순. */
class SellerGrades(val current: SellerGrade?, val history: List<SellerGrade>)

/** A2 조회(ADR-0007 §5.4). 사용자 확인은 부르는 쪽이 한다. */
@Service
class SellerGradeQueryService(private val grades: SellerGradeRepository, private val rebates: FeeRebateRepository) {

    fun grades(sellerId: Long): SellerGrades {
        val history = grades.listOf(sellerId)
        return SellerGrades(history.lastOrNull(), history)
    }

    fun rebates(sellerId: Long): List<FeeRebate> = rebates.listOf(sellerId)
}

/**
 * seller-grade-recalc(ADR-0007 §5.2): 기준월 D = 현재가 속한 달 1일(KST). 대상 = 정산 행이 있고 effective_from = D 행이 없는 사용자(users id 순).
 * 사용자 1명 = 그 사용자 행을 잠근 트랜잭션 하나, 잠근 뒤 D 행이 없는지 다시 본다(있으면 건너뜀 — 첫 결과 유지). processed = 쓴 행 수.
 */
@Component
class SellerGradeRecalcBatch(
    private val targets: BatchTargets,
    private val tx: TransactionTemplate,
    private val users: UserRepository,
    private val grades: SellerGradeRepository,
) {
    fun run(now: Instant): BatchResult {
        val month = SellerGradeMonth.of(now)
        return targets.forEachIsolated("seller-grade-recalc", BatchTarget.SELLER_GRADE_PENDING, now) { userId ->
            tx.execute {
                users.lock(userId)
                if (grades.countAt(userId, month.effectiveFrom) > 0) return@execute false
                val basis = grades.basisAmount(userId, month.basisFrom, month.effectiveFrom)
                grades.add(SellerGrade.assess(users.find(userId)!!, month, basis, now))
                true
            }!!
        }
    }
}

/**
 * rebate-settlement(ADR-0007 §5.3): 대상 달 M = 지난달, 닫혔을 때만(§6.4와 같은 판정 — 아니면 대상 0건).
 * 대상 = effective_from = M 등급 행이 있고 month = M 리베이트가 없는 판매자(users id 순). 사용자 행을 잠근 뒤 다시 확인한다.
 * 주간 정산은 건드리지 않는다. processed = 쓴 행 수(ZERO 포함).
 */
@Component
class RebateSettlementBatch(
    private val targets: BatchTargets,
    private val tx: TransactionTemplate,
    private val users: UserRepository,
    private val settlements: SettlementRepository,
    private val grades: SellerGradeRepository,
    private val rebates: FeeRebateRepository,
) {
    fun run(now: Instant): BatchResult {
        val closed = ClosedMonth.of(now, settlements.lastPeriodStart()) ?: return BatchResult(0, 0)
        val m = closed.month
        return targets.forEachIsolated("rebate-settlement", BatchTarget.REBATE_PENDING, now) { userId ->
            tx.execute {
                users.lock(userId)
                val grade = grades.findAt(userId, m) ?: return@execute false
                if (rebates.countAt(userId, m) > 0) return@execute false
                val feeBase = rebates.feeBase(userId, m, closed.next)
                rebates.add(FeeRebate.settle(grade, m, feeBase, now))
                true
            }!!
        }
    }
}
