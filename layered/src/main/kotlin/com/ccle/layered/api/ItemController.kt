package com.ccle.layered.api

import com.ccle.layered.service.BuyerItemService
import com.ccle.layered.service.FulfillmentService
import com.ccle.layered.service.OrderItemQueryService
import com.ccle.layered.service.UserService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/** 주문 항목 조회(ADR-0004 §2.2), 판매자 발송(§3), 구매자 확정·취소·분쟁 제기(§4). 센터·운영자 절차는 AdminController. */
@RestController
class ItemController(
    private val users: UserService,
    private val itemQuery: OrderItemQueryService,
    private val fulfillment: FulfillmentService,
    private val buyer: BuyerItemService,
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

    @PostMapping("/order-items/{itemId}/confirm")
    fun confirm(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable itemId: String,
    ): ItemView {
        val userId = users.requireUser(userIdOf(userHeader))
        return ItemView(buyer.confirm(userId, pathId(itemId)))
    }

    @PostMapping("/order-items/{itemId}/cancel")
    fun cancel(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable itemId: String,
    ): ItemView {
        val userId = users.requireUser(userIdOf(userHeader))
        return ItemView(buyer.cancel(userId, pathId(itemId)))
    }

    @PostMapping("/order-items/{itemId}/disputes")
    fun openDispute(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable itemId: String,
    ): ResponseEntity<DisputeOpened> {
        val userId = users.requireUser(userIdOf(userHeader))
        return ResponseEntity.status(HttpStatus.CREATED).body(DisputeOpened(buyer.openDispute(userId, pathId(itemId))))
    }
}
