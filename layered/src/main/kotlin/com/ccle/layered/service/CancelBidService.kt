package com.ccle.layered.service

import com.ccle.layered.domain.Bid
import com.ccle.layered.domain.BidEventRepository
import com.ccle.layered.domain.BidRepository
import com.ccle.layered.domain.MarketClock
import com.ccle.layered.domain.ProductSizeRepository
import com.ccle.layered.domain.notFound
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/** 입찰 취소(§3.2). 등록자만, ACTIVE만. 판정 순서: 404 → 403 → (사이즈 잠금 뒤) 409. */
@Service
class CancelBidService(
    private val clock: MarketClock,
    private val tx: TransactionTemplate,
    private val sizes: ProductSizeRepository,
    private val bids: BidRepository,
    private val events: BidEventRepository,
) {

    fun cancel(userId: Long, bidId: Long): Bid {
        val now = clock.now()
        return tx.execute {
            val bid = bids.find(bidId) ?: notFound("없는 입찰: $bidId")
            bid.requireOwner(userId)

            // 상태는 사이즈를 잠근 뒤 다시 읽는다(잠금을 기다리는 사이에 체결됐을 수 있다)
            sizes.lock(bid.productSizeId)
            bids.reload(bid)
            events.add(bid.cancel(now))
            bid
        }!!
    }
}
