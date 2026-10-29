package com.ccle.layered.service

import com.ccle.layered.domain.AdminAuditLog
import com.ccle.layered.domain.AdminQueryRepository
import com.ccle.layered.domain.BuyerWarning
import com.ccle.layered.domain.Dispute
import com.ccle.layered.domain.KeysetPage
import com.ccle.layered.domain.MarketClock
import com.ccle.layered.domain.PageOf
import com.ccle.layered.domain.RestrictionType
import com.ccle.layered.domain.SellerPenalty
import com.ccle.layered.domain.Settlement
import com.ccle.layered.domain.SettlementFilter
import com.ccle.layered.domain.User
import com.ccle.layered.domain.UserRepository
import com.ccle.layered.domain.UserSummary
import com.ccle.layered.domain.auditActionFilter
import com.ccle.layered.domain.disputeStatusFilter
import com.ccle.layered.domain.notFound
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/** A9 관리자 조회(ADR-0007 §11.3). 목록은 키셋 페이지(§2.5). 쿼리 값의 형식·범위 오류는 400. */
@Service
class AdminQueryService(
    private val clock: MarketClock,
    tx: TransactionTemplate,
    private val users: UserRepository,
    private val queries: AdminQueryRepository,
) {
    private val snapshotTx = TransactionTemplate(tx.transactionManager!!).apply {
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        isReadOnly = true
    }

    /** 제한 사용자: type 필수(purchase|sale), 그 제한 끝 > 현재. */
    fun restrictedUsers(type: String?, limit: String?, after: String?): Pair<RestrictionType, PageOf<User>> {
        val restriction = RestrictionType.of(type)
        val page = KeysetPage.of(limit, after)
        return restriction to page.of(queries.restrictedUsers(restriction, clock.now(), page)) { it.id!! }
    }

    /** 사용자 요약: 없는 사용자 404. 한 스냅샷에서 읽는다. */
    fun summary(userId: Long): UserSummary = snapshotTx.execute {
        val user = requireUserRow(userId)
        val warnings = queries.countWarnings(userId)
        val penalties = queries.countPenalties(userId)
        UserSummary(user, warnings, penalties, queries.latestSellerGrade(userId))
    }!!

    /** 판매자 패널티: 400(페이지) → 없는 사용자 404. */
    fun penalties(userId: Long, limit: String?, after: String?): PageOf<SellerPenalty> {
        val page = KeysetPage.of(limit, after)
        requireUserRow(userId)
        return page.of(queries.penaltiesOf(userId, page)) { it.id!! }
    }

    /** 구매자 경고: 400(페이지) → 없는 사용자 404. */
    fun warnings(userId: Long, limit: String?, after: String?): PageOf<BuyerWarning> {
        val page = KeysetPage.of(limit, after)
        requireUserRow(userId)
        return page.of(queries.warningsOf(userId, page)) { it.id!! }
    }

    /** 정산 목록: 없는 sellerId는 빈 목록(필터 — §19의 13). */
    fun settlements(status: String?, sellerId: String?, from: String?, to: String?, limit: String?, after: String?): PageOf<Settlement> {
        val filter = SettlementFilter.of(status, sellerId, from, to)
        val page = KeysetPage.of(limit, after)
        return page.of(queries.settlements(filter, page)) { it.id!! }
    }

    fun disputes(status: String?, limit: String?, after: String?): PageOf<Dispute> {
        val filter = disputeStatusFilter(status)
        val page = KeysetPage.of(limit, after)
        return page.of(queries.disputes(filter, page)) { it.id!! }
    }

    fun auditLogs(action: String?, limit: String?, after: String?): PageOf<AdminAuditLog> {
        val filter = auditActionFilter(action)
        val page = KeysetPage.of(limit, after)
        return page.of(queries.auditLogs(filter, page)) { it.id!! }
    }

    private fun requireUserRow(userId: Long): User = users.find(userId) ?: notFound("없는 사용자: $userId")
}
