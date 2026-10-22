package com.ccle.god.web

import com.ccle.god.Marketplace
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/** 주문 결제·조회(ADR-0003 §5.1·§5.3), 포인트 조회(§5.2), 판매자 정산 조회(ADR-0004 §10.3). */
@RestController
class OrderController(private val marketplace: Marketplace) {

    @PostMapping("/orders")
    fun pay(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody(required = false) body: String?,
    ): ResponseEntity<OrderView> {
        val userId = marketplace.requireUser(userHeader)
        val json = parseObject(body)
        val result = marketplace.payOrder(
            userId = userId,
            idempotencyKey = idempotencyKey,
            tradeIds = json.longListOrNull("tradeIds"),
            pointToUse = json.longOrNull("pointToUse"),
        )
        return ResponseEntity.status(if (result.created) HttpStatus.CREATED else HttpStatus.OK).body(result.order)
    }

    @GetMapping("/orders/{orderId}")
    fun get(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable orderId: String,
    ): OrderView {
        val userId = marketplace.requireUser(userHeader)
        return marketplace.getOrder(userId, pathId(orderId))
    }

    @GetMapping("/me/points")
    fun points(@RequestHeader("X-User-Id", required = false) userHeader: String?): PointsView {
        val userId = marketplace.requireUser(userHeader)
        return marketplace.getPoints(userId)
    }

    @GetMapping("/me/settlements")
    fun settlements(@RequestHeader("X-User-Id", required = false) userHeader: String?): List<SettlementView> {
        val userId = marketplace.requireUser(userHeader)
        return marketplace.listSettlements(userId)
    }
}
