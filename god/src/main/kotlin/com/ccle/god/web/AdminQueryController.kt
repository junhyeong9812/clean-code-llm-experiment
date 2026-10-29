package com.ccle.god.web

import com.ccle.god.Marketplace
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 관리자 조회(ADR-0007 §11.3). 목록은 키셋 페이지(§2.5 — limit·after). 쿼리 값의 형식·범위는 Marketplace가 본다. */
@RestController
@RequestMapping("/admin")
class AdminQueryController(private val marketplace: Marketplace) {

    @GetMapping("/users/restricted")
    fun restrictedUsers(
        @RequestParam(required = false) type: String?,
        @RequestParam(required = false) limit: String?,
        @RequestParam(required = false) after: String?,
    ): PageView<RestrictedUserView> = marketplace.restrictedUsers(type, limit, after)

    @GetMapping("/users/{userId}/summary")
    fun summary(@PathVariable userId: String): UserSummaryView = marketplace.userSummary(pathId(userId))

    @GetMapping("/users/{userId}/penalties")
    fun penalties(
        @PathVariable userId: String,
        @RequestParam(required = false) limit: String?,
        @RequestParam(required = false) after: String?,
    ): PageView<PenaltyView> = marketplace.userPenalties(pathId(userId), limit, after)

    @GetMapping("/users/{userId}/warnings")
    fun warnings(
        @PathVariable userId: String,
        @RequestParam(required = false) limit: String?,
        @RequestParam(required = false) after: String?,
    ): PageView<WarningView> = marketplace.userWarnings(pathId(userId), limit, after)

    @GetMapping("/settlements")
    fun settlements(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) sellerId: String?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) limit: String?,
        @RequestParam(required = false) after: String?,
    ): PageView<AdminSettlementView> = marketplace.adminSettlements(status, sellerId, from, to, limit, after)

    @GetMapping("/disputes")
    fun disputes(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) limit: String?,
        @RequestParam(required = false) after: String?,
    ): PageView<AdminDisputeView> = marketplace.adminDisputes(status, limit, after)

    @GetMapping("/audit-logs")
    fun auditLogs(
        @RequestParam(required = false) action: String?,
        @RequestParam(required = false) limit: String?,
        @RequestParam(required = false) after: String?,
    ): PageView<AuditLogView> = marketplace.auditLogs(action, limit, after)
}
