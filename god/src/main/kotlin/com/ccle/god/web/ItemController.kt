package com.ccle.god.web

import com.ccle.god.Marketplace
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/** 주문 항목 조회(ADR-0004 §2.2)와 판매자 발송(§3). 센터 절차는 AdminController. */
@RestController
class ItemController(private val marketplace: Marketplace) {

    @GetMapping("/order-items/{itemId}")
    fun get(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable itemId: String,
    ): ItemView {
        val userId = marketplace.requireUser(userHeader)
        return marketplace.getOrderItem(userId, pathId(itemId))
    }

    @PostMapping("/order-items/{itemId}/shipment")
    fun ship(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable itemId: String,
    ): ItemView {
        val userId = marketplace.requireUser(userHeader)
        return marketplace.shipItem(userId, pathId(itemId))
    }
}
