package com.ccle.layered.service

import com.ccle.layered.domain.BatchTarget
import com.ccle.layered.domain.BatchTargets
import com.ccle.layered.domain.ClosedMonth
import com.ccle.layered.domain.Failure
import com.ccle.layered.domain.MarketClock
import com.ccle.layered.domain.MarketException
import com.ccle.layered.domain.MonthlyStatement
import com.ccle.layered.domain.MonthlyStatementRepository
import com.ccle.layered.domain.PayoutAccount
import com.ccle.layered.domain.PayoutAccountInput
import com.ccle.layered.domain.PayoutAccountRequest
import com.ccle.layered.domain.PayoutAccountRequestRepository
import com.ccle.layered.domain.PayoutDecision
import com.ccle.layered.domain.SettlementRepository
import com.ccle.layered.domain.UserRepository
import com.ccle.layered.domain.notFound
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * A3 지급 계좌 변경 요청·판정·조회(ADR-0007 §6.1~§6.3). 승인해도 seller_payout_accounts는 바꾸지 않는다.
 * 사용자 확인(401)은 부르는 쪽이 본문 파싱 전에 한다.
 */
@Service
class PayoutAccountService(
    private val clock: MarketClock,
    private val tx: TransactionTemplate,
    private val users: UserRepository,
    private val requests: PayoutAccountRequestRepository,
    private val audit: AdminAuditService,
) {
    private val snapshotTx = TransactionTemplate(tx.transactionManager!!).apply {
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        isReadOnly = true
    }

    /** 변경 요청(§6.1): 사용자 행 FOR UPDATE → 그 사용자의 PENDING 요청이 있으면 409, 없으면 PENDING 1행. */
    fun request(userId: Long, bankCode: String?, accountNo: String?): PayoutAccountRequest {
        val input = PayoutAccountInput.of(bankCode, accountNo)
        val now = clock.now()
        return tx.execute {
            users.lock(userId)
            if (requests.countPending(userId) > 0) {
                throw MarketException(Failure.ITEM_STATE_CONFLICT, "판정 대기 중인 요청이 있다: 사용자 $userId")
            }
            val request = PayoutAccountRequest.request(userId, input, now)
            requests.add(request)
            request
        }!!
    }

    /** 판정(§6.2·§19의 9): 400 → 404 → 요청 행 FOR UPDATE → 409(PENDING 아님). 감사 PAYOUT_ACCOUNT_DECISION(detail = 결과 상태). */
    fun decide(requestId: Long, approve: Boolean?, reason: String?): PayoutAccountRequest {
        val decision = PayoutDecision.of(approve, reason)
        val now = clock.now()
        return tx.execute {
            val request = requests.find(requestId) ?: notFound("없는 요청: $requestId")
            requests.lock(requestId)
            requests.reload(request)
            request.decide(decision, now)
            audit.record("PAYOUT_ACCOUNT_DECISION", requestId, request.status, now)
            request
        }!!
    }

    /** 유효 계좌 조회(§6.3). 요청과 처음 등록 계좌를 한 스냅샷에서 읽는다. */
    fun account(userId: Long): PayoutAccount = snapshotTx.execute {
        PayoutAccount.of(requests.approvedOrPending(userId)) { requests.initial(userId) }
    }!!
}

/** 월간 정산서 조회(ADR-0007 §6.4): month 오름차순. 사용자 확인은 부르는 쪽이 한다. */
@Service
class MonthlyStatementQueryService(private val statements: MonthlyStatementRepository) {

    fun list(sellerId: Long): List<MonthlyStatement> = statements.listOf(sellerId)
}

/**
 * monthly-statement(ADR-0007 §6.4): 대상 달 M = 지난달, 닫혔을 때만(아니면 대상 0건). 대상 = period_start가 M에 속하는 정산이 있고
 * month = M 정산서가 없는 판매자(users id 순). 사용자 행을 잠근 뒤 다시 확인하고 그 달 정산들로 1행. processed = 쓴 행 수.
 */
@Component
class MonthlyStatementBatch(
    private val targets: BatchTargets,
    private val tx: TransactionTemplate,
    private val users: UserRepository,
    private val settlements: SettlementRepository,
    private val statements: MonthlyStatementRepository,
) {
    fun run(now: Instant): BatchResult {
        val closed = ClosedMonth.of(now, settlements.lastPeriodStart()) ?: return BatchResult(0, 0)
        val m = closed.month
        return targets.forEachIsolated("monthly-statement", BatchTarget.MONTHLY_STATEMENT_PENDING, now) { userId ->
            tx.execute {
                users.lock(userId)
                if (statements.countAt(userId, m) > 0) return@execute false
                val ofMonth = statements.settlementsIn(userId, m, closed.next)
                if (ofMonth.isEmpty()) return@execute false
                val held = statements.heldRows(ofMonth.map { it.id!! })
                statements.add(MonthlyStatement.of(userId, m, ofMonth, held, now))
                true
            }!!
        }
    }
}
