package com.ccle.layered.api

import com.ccle.layered.domain.PriceStatDaily
import com.ccle.layered.domain.Trade
import com.ccle.layered.service.PriceStatsService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate

/** 일별 시세 1행(ADR-0007 §11.2). date는 "YYYY-MM-DD". */
data class PriceStatView(val date: LocalDate, val tradeCount: Int, val minPrice: Long, val maxPrice: Long, val medianPrice: Long) {
    constructor(p: PriceStatDaily) : this(p.statDate, p.tradeCount, p.minPrice, p.maxPrice, p.medianPrice)
}

/** 최근 체결 1건(§11.2). */
data class RecentTradeView(val tradeId: Long, val price: Long, val matchedAt: Instant) {
    constructor(t: Trade) : this(t.id!!, t.price, t.matchedAt)
}

/** 시세 조회(ADR-0007 §11.2). 헤더 불필요 — X-User-Id를 읽지 않는다(§19의 7). */
@RestController
class MarketDataController(private val stats: PriceStatsService) {

    @GetMapping("/product-sizes/{productSizeId}/price-stats")
    fun priceStats(
        @PathVariable productSizeId: String,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
    ): List<PriceStatView> = stats.priceStats(pathId(productSizeId), from, to).map { PriceStatView(it) }

    @GetMapping("/product-sizes/{productSizeId}/recent-trades")
    fun recentTrades(@PathVariable productSizeId: String): List<RecentTradeView> =
        stats.recentTrades(pathId(productSizeId)).map { RecentTradeView(it) }
}
