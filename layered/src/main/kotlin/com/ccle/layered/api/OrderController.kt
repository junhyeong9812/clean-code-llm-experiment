package com.ccle.layered.api

import com.ccle.layered.service.OrderQueryService
import com.ccle.layered.service.PaymentService
import com.ccle.layered.service.PointQueryService
import com.ccle.layered.service.UserService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/** 주문 결제·조회(ADR-0003 §5.1·§5.3)와 포인트 조회(§5.2). */
@RestController
class OrderController(
    private val users: UserService,
    private val payment: PaymentService,
    private val orderQuery: OrderQueryService,
    private val pointQuery: PointQueryService,
) {

    @PostMapping("/orders")
    fun pay(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody(required = false) body: String?,
    ): ResponseEntity<OrderView> {
        val userId = users.requireUser(userIdOf(userHeader))
        val json = parseObject(body)
        val result = payment.pay(
            userId = userId,
            idempotencyKey = idempotencyKey,
            tradeIds = json.longListOrNull("tradeIds"),
            pointToUse = json.longOrNull("pointToUse"),
        )
        return ResponseEntity.status(if (result.created) HttpStatus.CREATED else HttpStatus.OK).body(OrderView(result.order))
    }

    @GetMapping("/orders/{orderId}")
    fun get(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable orderId: String,
    ): OrderView {
        val userId = users.requireUser(userIdOf(userHeader))
        return OrderView(orderQuery.get(userId, pathId(orderId)))
    }

    @GetMapping("/me/points")
    fun points(@RequestHeader("X-User-Id", required = false) userHeader: String?): PointsView {
        val userId = users.requireUser(userIdOf(userHeader))
        return PointsView(pointQuery.summary(userId))
    }
}
