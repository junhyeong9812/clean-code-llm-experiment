package com.ccle.layered.service

import com.ccle.layered.domain.BatchTarget
import com.ccle.layered.domain.BatchTargets
import com.ccle.layered.domain.BidEventRepository
import com.ccle.layered.domain.BidRepository
import com.ccle.layered.domain.BuyerWarning
import com.ccle.layered.domain.BuyerWarningRepository
import com.ccle.layered.domain.MarketClock
import com.ccle.layered.domain.OrderRepository
import com.ccle.layered.domain.PgOutcome
import com.ccle.layered.domain.PgTransaction
import com.ccle.layered.domain.PgTransactionRepository
import com.ccle.layered.domain.ProductSizeRepository
import com.ccle.layered.domain.TradeRepository
import com.ccle.layered.domain.User
import com.ccle.layered.domain.UserRepository
import com.ccle.layered.domain.invalidRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/** `POST /admin/batches/{name}`(§7): 현재 시각 기준 1회. 반환 = 상태가 바뀐 주 대상 수. 알 수 없는 이름은 400. */
@Service
class BatchService(
    private val clock: MarketClock,
    private val bidExpiry: BidExpiryBatch,
    private val paymentDue: PaymentDueBatch,
    private val pgInquiry: PgInquiryBatch,
) {
    private val log = LoggerFactory.getLogger(BatchService::class.java)

    fun run(name: String): Int {
        val now = clock.now()
        val started = System.nanoTime() // 소요 시간 로그용(업무 시각 아님)
        val processed = when (name) {
            "bid-expiry" -> bidExpiry.run(now)
            "payment-due" -> paymentDue.run(now)
            "pg-inquiry" -> pgInquiry.run(now)
            else -> invalidRequest("알 수 없는 배치: $name")
        }
        log.info("배치 {} (현재 {}): processed={}, {}ms", name, now, processed, (System.nanoTime() - started) / 1_000_000)
        return processed
    }
}

/** bid-expiry: ACTIVE이고 expires_at ≤ 현재인 입찰을 id 순으로 EXPIRED. 입찰마다 그 사이즈를 잠근 트랜잭션 하나. */
@Component
class BidExpiryBatch(
    private val targets: BatchTargets,
    private val tx: TransactionTemplate,
    private val sizes: ProductSizeRepository,
    private val bids: BidRepository,
    private val events: BidEventRepository,
) {
    fun run(now: Instant): Int = targets.forEach(BatchTarget.EXPIRED_BIDS, now) { bidId ->
        tx.execute {
            val bid = bids.find(bidId)!!
            sizes.lock(bid.productSizeId)
            bids.reload(bid)
            // 잠금을 기다리는 사이에 취소·체결됐으면 건너뛴다
            if (!bid.isExpirable(now)) {
                false
            } else {
                events.add(bid.expire(now))
                true
            }
        }!!
    }
}

/**
 * payment-due: 기한이 지난 미결제 체결을 id 순으로 만료한다. 체결 1건 = 그 사이즈를 잠근 트랜잭션 하나(§3.3).
 * 잠금 순서: 사이즈 → 구매자. 구매자 행을 잠그면 그 구매자의 결제(구매자 잠금 안에서 PAYING 주문을 만든다)와
 * 줄을 서므로, 잠근 뒤 다시 확인한 결과가 결제와 엇갈리지 않는다.
 */
@Component
class PaymentDueBatch(
    private val targets: BatchTargets,
    private val tx: TransactionTemplate,
    private val sizes: ProductSizeRepository,
    private val users: UserRepository,
    private val trades: TradeRepository,
    private val orders: OrderRepository,
    private val warnings: BuyerWarningRepository,
    private val bids: BidRepository,
    private val events: BidEventRepository,
    private val matches: MatchRecorder,
) {
    fun run(now: Instant): Int = targets.forEach(BatchTarget.OVERDUE_TRADES, now) { tradeId -> tx.execute { expire(tradeId, now) }!! }

    private fun expire(tradeId: Long, now: Instant): Boolean {
        val trade = trades.find(tradeId)!!
        sizes.lock(trade.productSizeId)
        users.lock(trade.buyerId)
        trades.reload(trade)
        if (!trade.isExpirableUnpaid(now, orders.countUndecidedFor(tradeId))) return false

        // 만료를 바로 DB에 반영한다: 아래 재매칭이 같은 판매 입찰로 새 체결을 넣을 때 부분 UNIQUE(trades_live_sell_bid_key)에 걸리지 않게
        trade.expireUnpaid()
        trades.flush()

        warnings.add(BuyerWarning.issue(trade, now))
        val buyer = users.find(trade.buyerId)!!
        users.reload(buyer)
        buyer.restrictIfWarned(warnings.createdAtAfter(trade.buyerId, User.warningWindowStart(now)), now)

        // 구매 입찰 이벤트가 판매 입찰 이벤트보다 먼저
        events.add(bids.find(trade.buyBidId)!!.cancelForUnpaid(trade, now))
        val sell = bids.find(trade.sellBidId)!!
        events.add(sell.reopenForUnpaid(trade, now))
        if (sell.isMatchable(now)) {
            val counter = bids.bestCounter(sell.productSizeId, "SELL", sell.userId, sell.price, now)
            if (counter != null) matches.record(sell, counter, now)
        }
        return true
    }
}

/**
 * pg-inquiry: UNKNOWN 주문을 id 순으로 PG에 다시 묻는다. PG 호출 중에는 트랜잭션·잠금을 잡지 않는다.
 * 승인 → 승인 처리(paid_at = APPROVE 행의 requested_at), 404·거절 → 거절 처리, 응답 없음 → 행 없이 UNKNOWN 유지.
 */
@Component
class PgInquiryBatch(
    private val targets: BatchTargets,
    private val tx: TransactionTemplate,
    private val users: UserRepository,
    private val orders: OrderRepository,
    private val pgTransactions: PgTransactionRepository,
    private val pgCaller: PgCaller,
    private val outcomes: PaymentOutcomes,
) {
    fun run(now: Instant): Int = targets.forEach(BatchTarget.UNKNOWN_ORDERS, now) { orderId ->
        val result = pgCaller.inquire(orders.find(orderId)!!.idempotencyKey)
        if (result.outcome == PgOutcome.UNKNOWN) {
            false
        } else {
            tx.execute {
                val order = orders.find(orderId)!!
                users.lock(order.buyerId)
                orders.reload(order)
                // 다른 재조회가 먼저 확정했으면 건너뛴다
                if (!order.isUnknown()) return@execute false
                pgTransactions.add(PgTransaction.inquiry(order, result, now))
                if (result.outcome == PgOutcome.APPROVED) {
                    outcomes.approve(orderId, paidAt = pgTransactions.approveRequestedAt(orderId), now = now)
                } else {
                    outcomes.decline(orderId, now)
                }
                true
            }!!
        }
    }
}
