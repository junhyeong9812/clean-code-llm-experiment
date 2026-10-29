package com.ccle.layered.api

import com.ccle.layered.domain.AdminAuditLog
import com.ccle.layered.domain.BuyerWarning
import com.ccle.layered.domain.Dispute
import com.ccle.layered.domain.PageOf
import com.ccle.layered.domain.SellerPenalty
import com.ccle.layered.domain.Settlement
import com.ccle.layered.domain.UserSummary
import com.ccle.layered.service.AdminQueryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate

// 응답 본문 모양(ADR-0007 §11.3 A9 · §2.5 페이지). Instant는 KST ISO-8601(Json.kt).

/** 관리자 목록 페이지(§2.5): nextAfter = 받은 항목 수가 limit과 같으면 마지막 id, 적으면 null. */
data class PageView<T>(val items: List<T>, val nextAfter: Long?) {
    constructor(p: PageOf<T>) : this(p.items, p.nextAfter)
}

data class RestrictedUserView(val userId: Long, val email: String, val restrictedUntil: Instant)

/** 관리자 사용자 요약(§11.3·§19의 13). */
data class UserSummaryView(
    val userId: Long,
    val email: String,
    val grade: String,
    val purchaseRestrictedUntil: Instant?,
    val saleRestrictedUntil: Instant?,
    val warningCount: Long,
    val penaltyCount: Long,
    val sellerGrade: SellerGradeView?,
) {
    constructor(s: UserSummary) : this(
        s.user.id!!, s.user.email, s.user.grade, s.user.purchaseRestrictedUntil, s.user.saleRestrictedUntil,
        s.warningCount, s.penaltyCount, s.sellerGrade?.let { SellerGradeView(it) },
    )
}

data class PenaltyView(val penaltyId: Long, val orderItemId: Long, val amount: Long, val settlementId: Long?, val createdAt: Instant) {
    constructor(p: SellerPenalty) : this(p.id!!, p.orderItemId, p.amount, p.settlementId, p.createdAt)
}

data class WarningView(val warningId: Long, val tradeId: Long, val createdAt: Instant) {
    constructor(w: BuyerWarning) : this(w.id!!, w.tradeId, w.createdAt)
}

data class AdminSettlementView(
    val settlementId: Long,
    val sellerId: Long,
    val periodStart: LocalDate,
    val status: String,
    val net: Long,
    val payout: Long,
    val paidAt: Instant?,
) {
    constructor(s: Settlement) : this(s.id!!, s.sellerId, s.periodStart, s.status, s.net, s.payout, s.paidAt)
}

data class AdminDisputeView(val disputeId: Long, val orderItemId: Long, val status: String, val openedAt: Instant, val resolvedAt: Instant?) {
    constructor(d: Dispute) : this(d.id!!, d.orderItemId, d.status, d.openedAt, d.resolvedAt)
}

data class AuditLogView(val auditId: Long, val action: String, val targetId: Long, val detail: String?, val createdAt: Instant) {
    constructor(a: AdminAuditLog) : this(a.id!!, a.action, a.targetId, a.detail, a.createdAt)
}

/** 관리자 조회(ADR-0007 §11.3). 목록은 키셋 페이지(§2.5 — limit·after). 쿼리 값의 형식·범위는 서비스·도메인이 본다. */
@RestController
@RequestMapping("/admin")
class AdminQueryController(private val queries: AdminQueryService) {

    @GetMapping("/users/restricted")
    fun restrictedUsers(
        @RequestParam(required = false) type: String?,
        @RequestParam(required = false) limit: String?,
        @RequestParam(required = false) after: String?,
    ): PageView<RestrictedUserView> {
        val (restriction, page) = queries.restrictedUsers(type, limit, after)
        return PageView(page.map { RestrictedUserView(it.id!!, it.email, restriction.until(it)) })
    }

    @GetMapping("/users/{userId}/summary")
    fun summary(@PathVariable userId: String): UserSummaryView = UserSummaryView(queries.summary(pathId(userId)))

    @GetMapping("/users/{userId}/penalties")
    fun penalties(
        @PathVariable userId: String,
        @RequestParam(required = false) limit: String?,
        @RequestParam(required = false) after: String?,
    ): PageView<PenaltyView> = PageView(queries.penalties(pathId(userId), limit, after).map { PenaltyView(it) })

    @GetMapping("/users/{userId}/warnings")
    fun warnings(
        @PathVariable userId: String,
        @RequestParam(required = false) limit: String?,
        @RequestParam(required = false) after: String?,
    ): PageView<WarningView> = PageView(queries.warnings(pathId(userId), limit, after).map { WarningView(it) })

    @GetMapping("/settlements")
    fun settlements(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) sellerId: String?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) limit: String?,
        @RequestParam(required = false) after: String?,
    ): PageView<AdminSettlementView> =
        PageView(queries.settlements(status, sellerId, from, to, limit, after).map { AdminSettlementView(it) })

    @GetMapping("/disputes")
    fun disputes(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) limit: String?,
        @RequestParam(required = false) after: String?,
    ): PageView<AdminDisputeView> = PageView(queries.disputes(status, limit, after).map { AdminDisputeView(it) })

    @GetMapping("/audit-logs")
    fun auditLogs(
        @RequestParam(required = false) action: String?,
        @RequestParam(required = false) limit: String?,
        @RequestParam(required = false) after: String?,
    ): PageView<AuditLogView> = PageView(queries.auditLogs(action, limit, after).map { AuditLogView(it) })
}
