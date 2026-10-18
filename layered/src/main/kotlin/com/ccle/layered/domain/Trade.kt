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

    /** 결제 가능(§5.1): AWAITING_PAYMENT이고 현재 < payment_due_at. 아니면 409. */
    fun requirePayable(now: Instant) {
        if (status != "AWAITING_PAYMENT" || !now.isBefore(paymentDueAt)) {
            throw MarketException(Failure.TRADE_NOT_PAYABLE, "결제할 수 없는 체결: $id ($status, 기한 $paymentDueAt)")
        }
    }

    /** 승인 반영(§5.1 표): AWAITING_PAYMENT → PAID. */
    fun markPaid() {
        status = "PAID"
    }

    /**
     * payment-due 대상(§7, §9의 6): AWAITING_PAYMENT이고 payment_due_at ≤ 현재이며 결과가 정해지지 않은 주문(UNKNOWN·PAYING)이 없다.
     * undecidedOrders = 그 체결에 걸린 UNKNOWN·PAYING 주문 수.
     */
    fun isExpirableUnpaid(now: Instant, undecidedOrders: Long): Boolean =
        status == "AWAITING_PAYMENT" && !paymentDueAt.isAfter(now) && undecidedOrders == 0L

    /** AWAITING_PAYMENT → PAYMENT_EXPIRED. */
    fun expireUnpaid() {
        status = "PAYMENT_EXPIRED"
    }

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

    fun find(id: Long): Trade?

    /** 그 id들의 체결, id 오름차순(없는 id는 빠진다). */
    fun findAll(ids: List<Long>): List<Trade>

    /** 그 입찰의 살아 있는(PAYMENT_EXPIRED가 아닌) 체결 id. 입찰 1개에 최대 1건이다. */
    fun liveTradeIdOf(bidId: Long): Long?

    /** 내 체결 목록, id 오름차순. */
    fun listOf(userId: Long, filter: TradeFilter): List<Trade>

    /** 잠금을 기다리는 사이에 바뀌었을 수 있으므로 잠근 뒤 DB의 현재 값으로 다시 읽는다. */
    fun reload(trade: Trade)

    /**
     * 지금까지의 변경을 바로 DB에 쓴다. 만료한 체결의 판매 입찰로 새 체결을 넣기 전에 부른다:
     * 부분 UNIQUE(trades_live_sell_bid_key)가 아직 살아 있는 옛 체결과 충돌하지 않게.
     */
    fun flush()
}

@Entity
@Table(name = "buyer_warnings")
class BuyerWarning(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var userId: Long,
    var tradeId: Long,
    var createdAt: Instant,
) {
    companion object {
        /** 결제 기한이 지난 체결의 구매자 경고 1행(§7), created_at = 현재. */
        fun issue(trade: Trade, now: Instant) = BuyerWarning(userId = trade.buyerId, tradeId = trade.id!!, createdAt = now)
    }
}

interface BuyerWarningRepository {
    fun add(warning: BuyerWarning)

    /** 그 사용자의 경고 중 created_at > from 인 것의 created_at. */
    fun createdAtAfter(userId: Long, from: Instant): List<Instant>
}
