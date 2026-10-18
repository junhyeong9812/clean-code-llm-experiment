package com.ccle.layered.api

import com.ccle.layered.service.TradeQueryService
import com.ccle.layered.service.UserService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 체결 조회(ADR-0003 §4). */
@RestController
class TradeController(private val users: UserService, private val tradeQuery: TradeQueryService) {

    @GetMapping("/trades")
    fun list(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @RequestParam(required = false) role: String?,
        @RequestParam(required = false) status: String?,
    ): List<TradeView> {
        val userId = users.requireUser(userIdOf(userHeader))
        return tradeQuery.list(userId, role, status).map(::TradeView)
    }
}
