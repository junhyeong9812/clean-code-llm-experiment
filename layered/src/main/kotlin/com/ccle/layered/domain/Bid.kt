package com.ccle.layered.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Duration
import java.time.Instant

@Entity
@Table(name = "product_sizes")
class ProductSize(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var productId: Long,
    var size: String,
)

interface ProductSizeRepository {
    fun exists(id: Long): Boolean

    /**
     * 사이즈 행을 잠근다(§3.3 사이즈 단위 직렬화, `SELECT … FOR UPDATE`). 그 사이즈의 입찰 상태를 바꾸는 처리는 모두 이 잠금 뒤에 하고,
     * 잠금은 커밋까지 유지된다. 그래서 같은 사이즈 안에서는 bid_events.id 순서가 실제 처리 순서다. 반환 = 사이즈가 있었나.
     */
    fun lock(id: Long): Boolean
}

/**
 * 입찰 등록 요청의 값(§3.1). 일반 입찰: price > 0, validDays ∈ {30, 60, 90}.
 * 즉시 입찰(instant = true): price·validDays를 주면 400, 가격은 상대 입찰의 가격, 유효기간 30일.
 */
class BidRequest private constructor(
    val productSizeId: Long,
    val side: String,
    /** 즉시 입찰이면 null. */
    val price: Long?,
    val validDays: Long,
) {
    val instant: Boolean get() = price == null

    companion object {
        fun of(productSizeId: Long?, side: String?, price: Long?, validDays: Long?, instant: Boolean?): BidRequest {
            if (productSizeId == null) invalidRequest("productSizeId가 필요하다")
            if (side == null) invalidRequest("side가 필요하다")
            if (side != "BUY" && side != "SELL") invalidRequest("side는 BUY 또는 SELL: $side")
            if (instant == true) {
                if (price != null) invalidRequest("즉시 입찰에는 price를 줄 수 없다")
                if (validDays != null) invalidRequest("즉시 입찰에는 validDays를 줄 수 없다")
                return BidRequest(productSizeId, side, null, 30L)
            }
            if (price == null) invalidRequest("price가 필요하다")
            if (price <= 0) invalidRequest("price는 0보다 커야 한다: $price")
            if (validDays == null) invalidRequest("validDays가 필요하다")
            if (validDays != 30L && validDays != 60L && validDays != 90L) invalidRequest("validDays는 30·60·90: $validDays")
            return BidRequest(productSizeId, side, price, validDays)
        }
    }
}

@Entity
@Table(name = "bids")
class Bid(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var userId: Long,
    var productSizeId: Long,
    var side: String,
    var price: Long,
    status: String,
    var expiresAt: Instant,
    var createdAt: Instant,
) {
    var status: String = status
        private set

    /** 등록자만(§3.2·§3.4). */
    fun requireOwner(userId: Long) {
        if (this.userId != userId) throw MarketException(Failure.NOT_OWNER, "남의 입찰: $id")
    }

    /** 체결 순서(§3.3): (created_at, id)가 앞서나. 체결가 = 앞선 입찰의 가격. */
    fun isAheadOf(other: Bid): Boolean =
        createdAt.isBefore(other.createdAt) || (createdAt == other.createdAt && id!! < other.id!!)

    /** 저장 뒤 등록 이벤트(NULL → ACTIVE). */
    fun placedEvent(now: Instant) = BidEvent(bidId = id!!, fromStatus = null, toStatus = "ACTIVE", tradeId = null, createdAt = now)

    /** ACTIVE → CANCELLED(§3.2). ACTIVE가 아니면 409. 사이즈를 잠그고 다시 읽은 뒤 부른다. */
    fun cancel(now: Instant): BidEvent {
        if (status != "ACTIVE") throw MarketException(Failure.BID_NOT_ACTIVE, "ACTIVE가 아닌 입찰: $status")
        status = "CANCELLED"
        return BidEvent(bidId = id!!, fromStatus = "ACTIVE", toStatus = "CANCELLED", tradeId = null, createdAt = now)
    }

    /** ACTIVE → MATCHED(§3.3). 저장된 체결의 이벤트를 돌려준다. */
    fun matched(trade: Trade, now: Instant): BidEvent {
        check(status == "ACTIVE") { "ACTIVE가 아닌 입찰 $id 를 체결하려 했다: $status" }
        status = "MATCHED"
        return BidEvent(bidId = id!!, fromStatus = "ACTIVE", toStatus = "MATCHED", tradeId = trade.id, createdAt = now)
    }

    companion object {
        /**
         * 새 입찰(§3.1). created_at = 현재, expires_at = 현재 + 유효기간. 즉시 입찰은 상대(반대편 최우선 입찰)의 가격이고,
         * 상대가 없으면 409 NO_COUNTER_BID.
         */
        fun place(userId: Long, request: BidRequest, counter: Bid?, now: Instant): Bid {
            val price = request.price
                ?: (counter ?: throw MarketException(Failure.NO_COUNTER_BID, "체결 가능한 상대 입찰이 없다")).price
            return Bid(
                userId = userId,
                productSizeId = request.productSizeId,
                side = request.side,
                price = price,
                status = "ACTIVE",
                expiresAt = now.plus(Duration.ofDays(request.validDays)),
                createdAt = now,
            )
        }
    }
}

interface BidRepository {
    fun find(id: Long): Bid?

    fun add(bid: Bid)

    /** 잠금을 기다리는 사이에 바뀌었을 수 있으므로 잠근 뒤 DB의 현재 값으로 다시 읽는다. */
    fun reload(bid: Bid)

    /**
     * 반대편 최우선 체결 가능 입찰(§3.3). 체결 가능 = ACTIVE이고 expires_at > 현재.
     * 순서: 가격(구매는 높은 순, 판매는 낮은 순) → created_at → id. 자기(userId) 입찰은 건너뛴다.
     * limitPrice가 있으면 교차하는 것만(새 구매가 ≥ 판매가, 새 판매가 ≤ 구매가), 없으면(즉시 입찰) 가격 조건 없이.
     */
    fun bestCounter(productSizeId: Long, side: String, userId: Long, limitPrice: Long?, now: Instant): Bid?

    /** 호가(§3.4): 체결 가능한 입찰의 최고 구매가·최저 판매가. */
    fun book(productSizeId: Long, now: Instant): Book
}

data class Book(val bestBuy: Long?, val bestSell: Long?)

@Entity
@Table(name = "bid_events")
class BidEvent(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var bidId: Long,
    var fromStatus: String? = null,
    var toStatus: String,
    var tradeId: Long? = null,
    var createdAt: Instant,
)

interface BidEventRepository {
    fun add(event: BidEvent)
}
