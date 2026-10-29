package com.ccle.layered.domain

import java.time.Instant
import java.time.LocalDate

// A9 관리자 조회(ADR-0007 §11.3)와 그 목록의 키셋 페이지(§2.5).

/** 키셋 페이지(§2.5): limit 1~100(기본 50), after 0 이상(기본 0 = 처음부터). 정수가 아니거나 범위 밖이면 400. */
class KeysetPage private constructor(val limit: Int, val after: Long) {
    /** 페이지 응답의 nextAfter: 받은 수가 limit과 같으면 마지막 id, 적으면 null. */
    fun <T> of(items: List<T>, idOf: (T) -> Long): PageOf<T> = PageOf(items, if (items.size == limit) idOf(items.last()) else null)

    companion object {
        private const val DEFAULT_LIMIT = 50

        fun of(limit: String?, after: String?): KeysetPage {
            val size = if (limit == null) DEFAULT_LIMIT else limit.toIntOrNull() ?: invalidRequest("limit은 정수: $limit")
            if (size !in 1..100) invalidRequest("limit은 1~100: $size")
            val from = if (after == null) 0L else after.toLongOrNull() ?: invalidRequest("after는 정수: $after")
            if (from < 0) invalidRequest("after는 0 이상: $from")
            return KeysetPage(size, from)
        }
    }
}

/** 페이지 한 장(§2.5). */
class PageOf<T>(val items: List<T>, val nextAfter: Long?) {
    fun <R> map(transform: (T) -> R): PageOf<R> = PageOf(items.map(transform), nextAfter)
}

/** 제한 사용자 목록의 종류(§11.3 type = purchase|sale, 필수). */
enum class RestrictionType(val wire: String) {
    PURCHASE("purchase"),
    SALE("sale"),
    ;

    fun until(user: User): Instant = (if (this == PURCHASE) user.purchaseRestrictedUntil else user.saleRestrictedUntil)!!

    companion object {
        fun of(type: String?): RestrictionType {
            if (type == null) invalidRequest("type이 필요하다")
            return values().firstOrNull { it.wire == type } ?: invalidRequest("type은 purchase 또는 sale: $type")
        }
    }
}

/** 관리자 정산 목록 필터(§11.3·§2.5·§19의 13): status(PAID·CARRIED)·sellerId·from~to(period_start, 양끝 포함) 선택. */
class SettlementFilter private constructor(val status: String?, val sellerId: Long?, val from: LocalDate?, val to: LocalDate?) {
    companion object {
        fun of(status: String?, sellerId: String?, from: String?, to: String?): SettlementFilter {
            if (status != null && status != "PAID" && status != "CARRIED") invalidRequest("알 수 없는 정산 상태: $status")
            val seller = if (sellerId == null) null else sellerId.toLongOrNull() ?: invalidRequest("sellerId는 정수: $sellerId")
            val fromDate = dateInput("from", from)
            val toDate = dateInput("to", to)
            if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) invalidRequest("from이 to보다 늦다: $fromDate > $toDate")
            return SettlementFilter(status, seller, fromDate, toDate)
        }
    }
}

/** 관리자 분쟁 목록 필터(§11.3): status(OPEN·ACCEPTED·REJECTED) 선택. */
fun disputeStatusFilter(status: String?): String? {
    if (status != null && status != "OPEN" && status != "ACCEPTED" && status != "REJECTED") invalidRequest("알 수 없는 분쟁 상태: $status")
    return status
}

private val AUDIT_ACTIONS = setOf(
    "REVIEW_HIDE", "PAYOUT_ACCOUNT_DECISION", "INQUIRY_ANSWER", "TAX_INVOICE_CORRECTION", "TRACKING_EVENT",
    "INBOUND_SLOT_CREATE", "INBOUND_CHECK_IN",
)

/** 감사 로그 목록 필터(§11.3): action(일곱 값) 선택. */
fun auditActionFilter(action: String?): String? {
    if (action != null && action !in AUDIT_ACTIONS) invalidRequest("알 수 없는 action: $action")
    return action
}

/** 관리자 사용자 요약(§11.3·§19의 13): 경고·패널티 수는 전체 기간, sellerGrade = effective_from이 가장 늦은 행. */
class UserSummary(val user: User, val warningCount: Long, val penaltyCount: Long, val sellerGrade: SellerGrade?)

/** 관리자 조회(§11.3)의 읽기. 목록은 id 오름차순으로 id > page.after인 것을 page.limit개. */
interface AdminQueryRepository {
    fun restrictedUsers(type: RestrictionType, now: Instant, page: KeysetPage): List<User>

    fun countWarnings(userId: Long): Long

    fun countPenalties(userId: Long): Long

    /** effective_from이 가장 늦은 판매자 등급 행. */
    fun latestSellerGrade(sellerId: Long): SellerGrade?

    fun penaltiesOf(sellerId: Long, page: KeysetPage): List<SellerPenalty>

    fun warningsOf(userId: Long, page: KeysetPage): List<BuyerWarning>

    fun settlements(filter: SettlementFilter, page: KeysetPage): List<Settlement>

    fun disputes(status: String?, page: KeysetPage): List<Dispute>

    fun auditLogs(action: String?, page: KeysetPage): List<AdminAuditLog>
}
