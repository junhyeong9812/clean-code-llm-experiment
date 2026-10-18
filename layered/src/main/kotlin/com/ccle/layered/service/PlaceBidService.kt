package com.ccle.layered.service

import com.ccle.layered.domain.Bid
import com.ccle.layered.domain.BidEventRepository
import com.ccle.layered.domain.BidRepository
import com.ccle.layered.domain.BidRequest
import com.ccle.layered.domain.MarketClock
import com.ccle.layered.domain.ProductSizeRepository
import com.ccle.layered.domain.Trade
import com.ccle.layered.domain.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/** 등록 결과. trade = 등록과 함께 체결됐으면 그 체결. */
class PlacedBid(val bid: Bid, val trade: Trade?)

/**
 * 입찰 등록(§3.1). 일반 입찰은 교차하면 같은 트랜잭션에서 체결하고, 즉시 입찰은 반대편 최우선 입찰의 가격으로 만들어 바로 체결한다.
 * 판정 순서: 값 검증(400) → 사이즈 잠금(404) → 제한(403) → 상대(409).
 */
@Service
class PlaceBidService(
    private val clock: MarketClock,
    private val tx: TransactionTemplate,
    private val sizes: ProductSizeRepository,
    private val users: UserRepository,
    private val bids: BidRepository,
    private val events: BidEventRepository,
    private val matches: MatchRecorder,
) {

    /** 값 검증과 현재 시각은 트랜잭션 밖에서 끝내고, 사이즈 잠금부터 한 트랜잭션에서 한다. */
    fun place(userId: Long, productSizeId: Long?, side: String?, price: Long?, validDays: Long?, instant: Boolean?): PlacedBid {
        val request = BidRequest.of(productSizeId, side, price, validDays, instant)
        val now = clock.now()
        return tx.execute {
            sizes.lock(request.productSizeId)

            val user = users.find(userId) ?: throw IllegalStateException("확인된 사용자가 없다: $userId")
            user.requireCanBid(request.side, now)

            // 즉시 입찰은 가격 조건 없이 최우선 상대를 찾고, 일반 입찰은 교차하는 상대만 찾는다
            val counter = bids.bestCounter(request.productSizeId, request.side, userId, request.price, now)
            val bid = Bid.place(userId, request, counter, now)
            bids.add(bid)
            events.add(bid.placedEvent(now))

            val trade = counter?.let { matches.record(bid, it, now) }
            PlacedBid(bid, trade)
        }!!
    }
}
