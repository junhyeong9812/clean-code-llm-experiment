package com.ccle.layered.service

import com.ccle.layered.domain.Bid
import com.ccle.layered.domain.BidRepository
import com.ccle.layered.domain.Book
import com.ccle.layered.domain.MarketClock
import com.ccle.layered.domain.ProductSizeRepository
import com.ccle.layered.domain.Trade
import com.ccle.layered.domain.TradeFilter
import com.ccle.layered.domain.TradeRepository
import com.ccle.layered.domain.notFound
import org.springframework.stereotype.Service

/** 입찰 조회 결과. liveTradeId = 그 입찰의 살아 있는 체결. */
class BidDetail(val bid: Bid, val liveTradeId: Long?)

/** 입찰 조회·호가(§3.4). */
@Service
class BidQueryService(
    private val clock: MarketClock,
    private val sizes: ProductSizeRepository,
    private val bids: BidRepository,
    private val trades: TradeRepository,
) {

    /** 등록자만. */
    fun get(userId: Long, bidId: Long): BidDetail {
        val bid = bids.find(bidId) ?: notFound("없는 입찰: $bidId")
        bid.requireOwner(userId)
        return BidDetail(bid, trades.liveTradeIdOf(bidId))
    }

    fun book(productSizeId: Long): Book {
        if (!sizes.exists(productSizeId)) notFound("없는 사이즈: $productSizeId")
        return bids.book(productSizeId, clock.now())
    }
}

/** 체결 조회(§4). */
@Service
class TradeQueryService(private val trades: TradeRepository) {

    fun list(userId: Long, role: String?, status: String?): List<Trade> = trades.listOf(userId, TradeFilter.of(role, status))
}
