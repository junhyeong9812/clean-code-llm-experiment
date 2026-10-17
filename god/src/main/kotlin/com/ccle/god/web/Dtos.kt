package com.ccle.god.web

import java.time.Instant

// 응답 본문 모양(ADR-0003). Marketplace가 채워서 돌려주고 컨트롤러가 그대로 JSON으로 쓴다. Instant는 KST ISO-8601로 나간다(Json.kt).

data class BidPlaced(val bidId: Long, val status: String, val tradeId: Long?, val tradePrice: Long?)

data class BidCancelled(val bidId: Long, val status: String)

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
)

data class BookView(val bestBuy: Long?, val bestSell: Long?)

data class TradeView(
    val tradeId: Long,
    val productSizeId: Long,
    val price: Long,
    val status: String,
    val matchedAt: Instant,
    val paymentDueAt: Instant,
)

data class OrderItemView(
    val orderItemId: Long,
    val tradeId: Long,
    val status: String,
    val price: Long,
    val buyerFee: Long,
    val cardShare: Long,
    val pointShare: Long,
    val shippingShare: Long,
)

data class OrderView(
    val orderId: Long,
    val status: String,
    val itemTotal: Long,
    val buyerFeeTotal: Long,
    val shippingFee: Long,
    val pointUsed: Long,
    val cardAmount: Long,
    val items: List<OrderItemView>,
)

/** 결제 결과. created = 새 주문(201), 아니면 같은 멱등 키의 기존 주문(200). */
data class PaymentResult(val created: Boolean, val order: OrderView)

data class PointsView(val available: Long, val pending: Long, val usable: Long)
