package com.ccle.god.web

import com.ccle.god.Marketplace
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** 입찰(ADR-0003 §3)과 호가(§3.4). 헤더 확인 → 파싱 → Marketplace 호출만 한다. */
@RestController
class BidController(private val marketplace: Marketplace) {

    @PostMapping("/bids")
    @ResponseStatus(HttpStatus.CREATED)
    fun place(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @RequestBody(required = false) body: String?,
    ): BidPlaced {
        val userId = marketplace.requireUser(userHeader)
        val json = parseObject(body)
        return marketplace.placeBid(
            userId = userId,
            productSizeId = json.longOrNull("productSizeId"),
            side = json.textOrNull("side"),
            price = json.longOrNull("price"),
            validDays = json.longOrNull("validDays"),
            instant = json.booleanOrNull("instant"),
        )
    }

    @DeleteMapping("/bids/{bidId}")
    fun cancel(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable bidId: String,
    ): BidCancelled {
        val userId = marketplace.requireUser(userHeader)
        return marketplace.cancelBid(userId, pathId(bidId))
    }

    @GetMapping("/bids/{bidId}")
    fun get(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable bidId: String,
    ): BidView {
        val userId = marketplace.requireUser(userHeader)
        return marketplace.getBid(userId, pathId(bidId))
    }

    @GetMapping("/product-sizes/{productSizeId}/book")
    fun book(@PathVariable productSizeId: String): BookView = marketplace.getBook(pathId(productSizeId))
}
