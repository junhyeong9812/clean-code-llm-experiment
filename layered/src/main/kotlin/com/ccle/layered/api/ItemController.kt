package com.ccle.layered.api

import com.ccle.layered.service.FulfillmentService
import com.ccle.layered.service.OrderItemQueryService
import com.ccle.layered.service.UserService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/** 주문 항목 조회(ADR-0004 §2.2)와 판매자 발송(§3). 센터 절차는 AdminController. */
@RestController
class ItemController(
    private val users: UserService,
    private val itemQuery: OrderItemQueryService,
    private val fulfillment: FulfillmentService,
) {

    @GetMapping("/order-items/{itemId}")
    fun get(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable itemId: String,
    ): ItemView {
        val userId = users.requireUser(userIdOf(userHeader))
        return ItemView(itemQuery.get(userId, pathId(itemId)))
    }

    @PostMapping("/order-items/{itemId}/shipment")
    fun ship(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable itemId: String,
    ): ItemView {
        val userId = users.requireUser(userIdOf(userHeader))
        return ItemView(fulfillment.ship(userId, pathId(itemId)))
    }
}
