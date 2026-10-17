package com.ccle.god.web

import com.ccle.god.Marketplace
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 체결 조회(ADR-0003 §4). */
@RestController
class TradeController(private val marketplace: Marketplace) {

    @GetMapping("/trades")
    fun list(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @RequestParam(required = false) role: String?,
        @RequestParam(required = false) status: String?,
    ): List<TradeView> {
        val userId = marketplace.requireUser(userHeader)
        return marketplace.listTrades(userId, role, status)
    }
}
