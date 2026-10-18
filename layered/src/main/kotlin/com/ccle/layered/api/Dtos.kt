package com.ccle.layered.api

import com.ccle.layered.domain.Bid
import com.ccle.layered.domain.Book
import com.ccle.layered.domain.Trade
import com.ccle.layered.service.BidDetail
import com.ccle.layered.service.PlacedBid
import java.time.Instant

// 응답 본문 모양(ADR-0003). 서비스 결과(도메인 객체)를 읽어 채운다. Instant는 KST ISO-8601로 나간다(Json.kt).

data class BidPlaced(val bidId: Long, val status: String, val tradeId: Long?, val tradePrice: Long?) {
    constructor(placed: PlacedBid) : this(placed.bid.id!!, placed.bid.status, placed.trade?.id, placed.trade?.price)
}

data class BidCancelled(val bidId: Long, val status: String) {
    constructor(bid: Bid) : this(bid.id!!, bid.status)
}

data class BidView(
    val bidId: Long,
    val userId: Long,
    val productSizeId: Long,
    val side: String,
    val price: Long,
    val status: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val tradeId: Long?,
) {
    constructor(detail: BidDetail) : this(
        bidId = detail.bid.id!!,
        userId = detail.bid.userId,
        productSizeId = detail.bid.productSizeId,
        side = detail.bid.side,
        price = detail.bid.price,
        status = detail.bid.status,
        createdAt = detail.bid.createdAt,
        expiresAt = detail.bid.expiresAt,
        tradeId = detail.liveTradeId,
    )
}

data class BookView(val bestBuy: Long?, val bestSell: Long?) {
    constructor(book: Book) : this(book.bestBuy, book.bestSell)
}

data class TradeView(
    val tradeId: Long,
    val productSizeId: Long,
    val price: Long,
    val status: String,
    val matchedAt: Instant,
    val paymentDueAt: Instant,
) {
    constructor(trade: Trade) : this(
        tradeId = trade.id!!,
        productSizeId = trade.productSizeId,
        price = trade.price,
        status = trade.status,
        matchedAt = trade.matchedAt,
        paymentDueAt = trade.paymentDueAt,
    )
}
