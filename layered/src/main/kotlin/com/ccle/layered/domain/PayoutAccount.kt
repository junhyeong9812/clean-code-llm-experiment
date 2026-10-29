package com.ccle.layered.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

// A3 지급 계좌 변경·월간 정산서(ADR-0007 §6). 승인해도 seller_payout_accounts는 바꾸지 않는다(§6.2) — 유효 계좌는 조회에서 정한다.

@Entity
@Table(name = "payout_account_requests")
class PayoutAccountRequest(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var userId: Long,
    var bankCode: String,
    var accountNoMasked: String,
    status: String,
    rejectReason: String? = null,
    var requestedAt: Instant,
    decidedAt: Instant? = null,
) {
    var status: String = status
        private set

    var rejectReason: String? = rejectReason
        private set

    var decidedAt: Instant? = decidedAt
        private set

    /** 판정(§6.2): PENDING만(아니면 409) → APPROVED·REJECTED(반려 사유는 받은 그대로), decided_at = 현재. 요청 행을 잠근 뒤 다시 읽고 부른다. */
    fun decide(decision: PayoutDecision, now: Instant) {
        if (status != "PENDING") throw MarketException(Failure.ITEM_STATE_CONFLICT, "PENDING이 아닌 요청: $id ($status)")
        status = if (decision.approve) "APPROVED" else "REJECTED"
        rejectReason = if (decision.approve) null else decision.reason
        decidedAt = now
    }

    companion object {
        /** 변경 요청(§6.1): PENDING, requested_at = 현재. */
        fun request(userId: Long, input: PayoutAccountInput, now: Instant) = PayoutAccountRequest(
            userId = userId,
            bankCode = input.bankCode,
            accountNoMasked = input.accountNoMasked,
            status = "PENDING",
            requestedAt = now,
        )
    }
}

/**
 * 변경 요청의 값(§6.1): bankCode는 시드 은행 코드 6개 중 하나, accountNo는 숫자 10~14자리(앞뒤 공백 불가 — §19의 2).
 * 저장은 "****" + 끝 4자리(전체 번호는 저장하지 않는다).
 */
class PayoutAccountInput private constructor(val bankCode: String, val accountNoMasked: String) {
    companion object {
        /** 시드 은행 코드(§6.1). */
        private val BANK_CODES = setOf("004", "011", "020", "081", "088", "090")

        private val ACCOUNT_NO = Regex("[0-9]{10,14}")

        fun of(bankCode: String?, accountNo: String?): PayoutAccountInput {
            if (bankCode == null) invalidRequest("bankCode가 필요하다")
            if (bankCode !in BANK_CODES) invalidRequest("알 수 없는 은행 코드: $bankCode")
            if (accountNo == null) invalidRequest("accountNo가 필요하다")
            if (!ACCOUNT_NO.matches(accountNo)) invalidRequest("accountNo는 숫자 10~14자리")
            return PayoutAccountInput(bankCode, "****" + accountNo.takeLast(4))
        }
    }
}

/** 판정 요청의 값(§6.2·§19의 9): approve 필수, 반려면 reason이 비지 않아야 한다. 승인이면 reason은 저장하지 않는다. */
class PayoutDecision private constructor(val approve: Boolean, val reason: String?) {
    companion object {
        fun of(approve: Boolean?, reason: String?): PayoutDecision {
            if (approve == null) invalidRequest("approve가 필요하다")
            if (!approve && reason.isNullOrBlank()) invalidRequest("반려에는 reason이 필요하다")
            return PayoutDecision(approve, reason)
        }
    }
}

/** 처음 등록된 지급 계좌(seller_payout_accounts 행 — 읽기만). */
class InitialPayoutAccount(val bankCode: String, val accountNoMasked: String)

/**
 * 유효 지급 계좌(§6.3): APPROVED 요청 중 decided_at이 가장 늦은 것(같으면 id 큰 것) → REQUEST,
 * 없으면 처음 등록 계좌 → INITIAL, 둘 다 없으면 세 필드 null. pending = PENDING 요청.
 */
class PayoutAccount(val bankCode: String?, val accountNoMasked: String?, val source: String?, val pending: PayoutAccountRequest?) {
    companion object {
        /** requests = APPROVED·PENDING 요청, decided_at 내림차순·id 내림차순. initial은 승인 요청이 없을 때만 읽는다. */
        fun of(requests: List<PayoutAccountRequest>, initial: () -> InitialPayoutAccount?): PayoutAccount {
            val approved = requests.firstOrNull { it.status == "APPROVED" }
            val pending = requests.firstOrNull { it.status == "PENDING" }
            if (approved != null) return PayoutAccount(approved.bankCode, approved.accountNoMasked, "REQUEST", pending)
            val first = initial() ?: return PayoutAccount(null, null, null, pending)
            return PayoutAccount(first.bankCode, first.accountNoMasked, "INITIAL", pending)
        }
    }
}

interface PayoutAccountRequestRepository {
    fun add(request: PayoutAccountRequest)

    fun find(id: Long): PayoutAccountRequest?

    /** 요청 행 FOR UPDATE(§2.3). */
    fun lock(id: Long)

    fun reload(request: PayoutAccountRequest)

    fun countPending(userId: Long): Long

    /** APPROVED·PENDING 요청, decided_at 내림차순·id 내림차순. */
    fun approvedOrPending(userId: Long): List<PayoutAccountRequest>

    /** seller_payout_accounts 행(없으면 null). */
    fun initial(userId: Long): InitialPayoutAccount?
}

@Entity
@Table(name = "monthly_statements")
class MonthlyStatement(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var sellerId: Long,
    var month: LocalDate,
    var settlementCount: Int,
    var gross: Long,
    var feeTotal: Long,
    var penaltyTotal: Long,
    var payoutTotal: Long,
    var carriedCount: Int,
    var heldCount: Int,
    var lastCarryOut: Long,
    var createdAt: Instant,
) {
    companion object {
        /**
         * 달 M의 정산서(§6.4). settlements = 그 판매자의 period_start ∈ M 정산(period_start 오름차순, 1건 이상), heldRows = 그 정산들의 HELD 행 수.
         * 건수·각 합·CARRIED 수(이월)·HELD 행 수(주마다 센다 — 보류)·period_start가 가장 늦은 정산의 carry_out, created_at = 현재.
         */
        fun of(sellerId: Long, month: LocalDate, settlements: List<Settlement>, heldRows: Long, now: Instant) = MonthlyStatement(
            sellerId = sellerId,
            month = month,
            settlementCount = settlements.size,
            gross = settlements.fold(0L) { sum, s -> Math.addExact(sum, s.gross) },
            feeTotal = settlements.fold(0L) { sum, s -> Math.addExact(sum, s.feeTotal) },
            penaltyTotal = settlements.fold(0L) { sum, s -> Math.addExact(sum, s.penaltyTotal) },
            payoutTotal = settlements.fold(0L) { sum, s -> Math.addExact(sum, s.payout) },
            carriedCount = settlements.count { it.status == "CARRIED" },
            heldCount = heldRows.toInt(),
            lastCarryOut = settlements.last().carryOut,
            createdAt = now,
        )
    }
}

interface MonthlyStatementRepository {
    fun add(statement: MonthlyStatement)

    fun countAt(sellerId: Long, month: LocalDate): Long

    /** 그 판매자의 정산 중 period_start ∈ [month, next), period_start 오름차순. */
    fun settlementsIn(sellerId: Long, month: LocalDate, next: LocalDate): List<Settlement>

    /** 그 정산들의 HELD settlement_items 행 수. */
    fun heldRows(settlementIds: List<Long>): Long

    /** 그 판매자의 정산서, month 오름차순. */
    fun listOf(sellerId: Long): List<MonthlyStatement>
}
