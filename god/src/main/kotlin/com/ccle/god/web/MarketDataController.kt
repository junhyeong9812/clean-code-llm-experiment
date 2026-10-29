package com.ccle.god.web

import com.ccle.god.Marketplace
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 시세 조회(ADR-0007 §11.2). 헤더 불필요 — X-User-Id를 읽지 않는다(§19의 7). */
@RestController
class MarketDataController(private val marketplace: Marketplace) {

    @GetMapping("/product-sizes/{productSizeId}/price-stats")
    fun priceStats(
        @PathVariable productSizeId: String,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
    ): List<PriceStatView> = marketplace.priceStats(pathId(productSizeId), from, to)

    @GetMapping("/product-sizes/{productSizeId}/recent-trades")
    fun recentTrades(@PathVariable productSizeId: String): List<RecentTradeView> = marketplace.recentTrades(pathId(productSizeId))
}
