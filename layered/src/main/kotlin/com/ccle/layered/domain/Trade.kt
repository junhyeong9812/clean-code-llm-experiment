package com.ccle.layered.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Duration
import java.time.Instant

@Entity
@Table(name = "trades")
class Trade(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var buyBidId: Long,
    var sellBidId: Long,
    var buyerId: Long,
    var sellerId: Long,
    var productSizeId: Long,
    var price: Long,
    status: String,
    var matchedAt: Instant,
    var paymentDueAt: Instant,
) {
    var status: String = status
        private set

    companion object {
        val STATUSES = setOf("AWAITING_PAYMENT", "PAID", "PAYMENT_EXPIRED")
    }
}

/**
 * 두 입찰의 체결(§3.3). 체결가 = (created_at, id)가 앞선 입찰의 가격. 결제 기한 = 현재 + 30분.
 * 체결 저장 → 두 입찰 MATCHED, bid_events는 구매 입찰 행 먼저. 사이즈를 잠근 트랜잭션 안에서 쓴다.
 */
class Match(a: Bid, b: Bid) {
    val buy: Bid = if (a.side == "BUY") a else b
    val sell: Bid = if (a.side == "BUY") b else a
    val price: Long = if (a.isAheadOf(b)) a.price else b.price

    init {
        check(buy.side == "BUY" && sell.side == "SELL") { "같은 쪽 입찰끼리 체결하려 했다: ${a.id}, ${b.id}" }
    }

    fun trade(now: Instant) = Trade(
        buyBidId = buy.id!!,
        sellBidId = sell.id!!,
        buyerId = buy.userId,
        sellerId = sell.userId,
        productSizeId = buy.productSizeId,
        price = price,
        status = "AWAITING_PAYMENT",
        matchedAt = now,
        paymentDueAt = now.plus(Duration.ofMinutes(30)),
    )

    /** 저장된 체결로 두 입찰을 MATCHED로 바꾸고 이벤트를 돌려준다(구매 입찰 먼저). */
    fun markMatched(trade: Trade, now: Instant): List<BidEvent> = listOf(buy.matched(trade, now), sell.matched(trade, now))
}

/** 체결 조회의 역할(§4): role = buyer|seller(필수), status는 체결 상태 중 하나(선택). */
class TradeFilter private constructor(val asBuyer: Boolean, val status: String?) {
    companion object {
        fun of(role: String?, status: String?): TradeFilter {
            if (role == null) invalidRequest("role이 필요하다")
            if (role != "buyer" && role != "seller") invalidRequest("role은 buyer 또는 seller: $role")
            if (status != null && status !in Trade.STATUSES) invalidRequest("알 수 없는 체결 상태: $status")
            return TradeFilter(role == "buyer", status)
        }
    }
}

interface TradeRepository {
    fun add(trade: Trade)

    /** 그 입찰의 살아 있는(PAYMENT_EXPIRED가 아닌) 체결 id. 입찰 1개에 최대 1건이다. */
    fun liveTradeIdOf(bidId: Long): Long?

    /** 내 체결 목록, id 오름차순. */
    fun listOf(userId: Long, filter: TradeFilter): List<Trade>
}

@Entity
@Table(name = "buyer_warnings")
class BuyerWarning(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var userId: Long,
    var tradeId: Long,
    var createdAt: Instant,
)
