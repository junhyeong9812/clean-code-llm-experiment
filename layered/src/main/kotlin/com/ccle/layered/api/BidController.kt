package com.ccle.layered.api

import com.ccle.layered.service.BidQueryService
import com.ccle.layered.service.CancelBidService
import com.ccle.layered.service.PlaceBidService
import com.ccle.layered.service.UserService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** 입찰(ADR-0003 §3)과 호가(§3.4). 헤더 확인 → 파싱 → 서비스 호출 → 응답 변환만 한다. */
@RestController
class BidController(
    private val users: UserService,
    private val placeBid: PlaceBidService,
    private val cancelBid: CancelBidService,
    private val bidQuery: BidQueryService,
) {

    @PostMapping("/bids")
    @ResponseStatus(HttpStatus.CREATED)
    fun place(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @RequestBody(required = false) body: String?,
    ): BidPlaced {
        val userId = users.requireUser(userIdOf(userHeader))
        val json = parseObject(body)
        val placed = placeBid.place(
            userId = userId,
            productSizeId = json.longOrNull("productSizeId"),
            side = json.textOrNull("side"),
            price = json.longOrNull("price"),
            validDays = json.longOrNull("validDays"),
            instant = json.booleanOrNull("instant"),
        )
        return BidPlaced(placed)
    }

    @DeleteMapping("/bids/{bidId}")
    fun cancel(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable bidId: String,
    ): BidCancelled {
        val userId = users.requireUser(userIdOf(userHeader))
        return BidCancelled(cancelBid.cancel(userId, pathId(bidId)))
    }

    @GetMapping("/bids/{bidId}")
    fun get(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable bidId: String,
    ): BidView {
        val userId = users.requireUser(userIdOf(userHeader))
        return BidView(bidQuery.get(userId, pathId(bidId)))
    }

    @GetMapping("/product-sizes/{productSizeId}/book")
    fun book(@PathVariable productSizeId: String): BookView = BookView(bidQuery.book(pathId(productSizeId)))
}
