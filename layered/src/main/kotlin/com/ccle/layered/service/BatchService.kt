package com.ccle.layered.service

import com.ccle.layered.domain.BatchTarget
import com.ccle.layered.domain.BatchTargets
import com.ccle.layered.domain.BidEventRepository
import com.ccle.layered.domain.BidRepository
import com.ccle.layered.domain.BuyerWarning
import com.ccle.layered.domain.BuyerWarningRepository
import com.ccle.layered.domain.Failure
import com.ccle.layered.domain.GradePeriod
import com.ccle.layered.domain.MarketException
import com.ccle.layered.domain.MarketClock
import com.ccle.layered.domain.OrderRepository
import com.ccle.layered.domain.PgOutcome
import com.ccle.layered.domain.PgTransaction
import com.ccle.layered.domain.PgTransactionRepository
import com.ccle.layered.domain.PointRepository
import com.ccle.layered.domain.ProductSizeRepository
import com.ccle.layered.domain.RefundFault
import com.ccle.layered.domain.SellerPenaltyRepository
import com.ccle.layered.domain.SellerStatement
import com.ccle.layered.domain.SettlementRepository
import com.ccle.layered.domain.SettlementWeek
import com.ccle.layered.domain.TradeRepository
import com.ccle.layered.domain.User
import com.ccle.layered.domain.UserRepository
import com.ccle.layered.domain.invalidRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * `POST /admin/batches/{name}`(§7, ADR-0004 §5): 현재 시각 기준 1회. 반환 = 상태가 바뀐 주 대상 수(grade-recalc는 쓴 이력 행 수, weekly-settlement는 만든 정산 행 수).
 * 알 수 없는 이름은 400. 대상 1건 = 트랜잭션 하나(weekly-settlement는 한 주 = 트랜잭션 하나). 카드 취소 실패(409 PG_UNAVAILABLE)는 그 대상만 건너뛰고(ADR-0004 §7.2),
 * 그 밖의 예외는 배치를 멈춘다(앞 대상은 커밋된 채).
 */
@Service
class BatchService(
    private val clock: MarketClock,
    private val bidExpiry: BidExpiryBatch,
    private val paymentDue: PaymentDueBatch,
    private val pgInquiry: PgInquiryBatch,
    private val shipmentDeadline: ShipmentDeadlineBatch,
    private val autoConfirm: AutoConfirmBatch,
    private val pointExpiry: PointExpiryBatch,
    private val gradeRecalc: GradeRecalcBatch,
    private val weeklySettlement: WeeklySettlementBatch,
) {
    private val log = LoggerFactory.getLogger(BatchService::class.java)

    fun run(name: String): Int {
        val now = clock.now()
        val started = System.nanoTime() // 소요 시간 로그용(업무 시각 아님)
        val processed = when (name) {
            "bid-expiry" -> bidExpiry.run(now)
            "payment-due" -> paymentDue.run(now)
            "pg-inquiry" -> pgInquiry.run(now)
            "shipment-deadline" -> shipmentDeadline.run(now)
            "auto-confirm" -> autoConfirm.run(now)
            "point-expiry" -> pointExpiry.run(now)
            "grade-recalc" -> gradeRecalc.run(now)
            "weekly-settlement" -> weeklySettlement.run(now)
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

/**
 * shipment-deadline(ADR-0004 §5): 발송 기한이 지난 PAID 항목을 id 순으로 §6 판매자 귀책(사유 `발송 기한 초과`).
 * 항목 1건 = 주문 → 항목을 잠근 트랜잭션 하나, 잠근 뒤 다시 확인한다(그 사이 구매자 취소·발송됐으면 건너뜀).
 * 카드 취소 실패(409 PG_UNAVAILABLE)면 그 트랜잭션을 롤백하고 건너뛴다(§7.2) — 다음 실행의 대상으로 남는다.
 */
@Component
class ShipmentDeadlineBatch(
    private val targets: BatchTargets,
    private val tx: TransactionTemplate,
    private val locker: ItemLocker,
    private val sellerFaults: SellerFaults,
) {
    private val log = LoggerFactory.getLogger(ShipmentDeadlineBatch::class.java)

    fun run(now: Instant): Int = targets.forEach(BatchTarget.UNSHIPPED_ITEMS, now) { itemId ->
        try {
            tx.execute {
                val item = locker.lock(itemId)
                if (!item.isShipmentOverdue(now)) {
                    false
                } else {
                    sellerFaults.apply(item, reason = RefundFault.SHIPMENT_OVERDUE_REASON, now = now)
                    true
                }
            }!!
        } catch (e: MarketException) {
            if (e.failure != Failure.PG_UNAVAILABLE) throw e
            log.warn("shipment-deadline: 항목 {} 건너뜀(카드 취소 실패)", itemId)
            false
        }
    }
}

/**
 * auto-confirm(ADR-0004 §5): 배송 완료 7일이 지난 DELIVERED 항목을 id 순으로 CONFIRMED, confirmed_at = delivered_at + 7일(소급) → §8.2.
 * 항목 1건 = 주문 → 항목을 잠근 트랜잭션 하나, 잠근 뒤 다시 확인한다(그 사이 구매자가 확정했으면 건너뜀).
 */
@Component
class AutoConfirmBatch(
    private val targets: BatchTargets,
    private val tx: TransactionTemplate,
    private val locker: ItemLocker,
    private val confirmer: ItemConfirmer,
) {
    fun run(now: Instant): Int = targets.forEach(BatchTarget.UNCONFIRMED_ITEMS, now) { itemId ->
        tx.execute {
            val item = locker.lock(itemId)
            val confirmedAt = item.autoConfirmationTime(now)
            if (confirmedAt == null) {
                false
            } else {
                confirmer.confirm(item, confirmedAt, now)
                true
            }
        }!!
    }
}

/**
 * point-expiry(ADR-0004 §8.3): 만료된 EARN 묶음을 id 순으로 소멸. 묶음 1개 = 그 사용자 행을 잠근 트랜잭션 하나
 * (묶음 remaining을 바꾸는 처리는 모두 이 잠금 안에서 한다). 다른 잠금은 잡지 않는다.
 */
@Component
class PointExpiryBatch(
    private val targets: BatchTargets,
    private val tx: TransactionTemplate,
    private val users: UserRepository,
    private val points: PointRepository,
    private val pointBook: PointBook,
) {
    fun run(now: Instant): Int = targets.forEach(BatchTarget.EXPIRED_POINTS, now) { earnId ->
        tx.execute {
            val earn = points.find(earnId)
            users.lock(earn.userId)
            points.reload(earn)
            pointBook.expire(earn, now)
        }!!
    }
}

/**
 * grade-recalc(ADR-0004 §8.5): 기준일 이력이 없고 등급이 바뀌는 사용자를 id 순으로 재산정한다. 반환 = 쓴 이력 행 수.
 * 사용자 1명 = 그 사용자 행을 잠근 트랜잭션 하나. 잠근 뒤 이력 유무·기준 금액·users.grade를 다시 읽어 판정한다
 * (기준 금액을 바꾸는 확정·분쟁 인정은 구매자 행을 잠그므로 잠근 뒤의 값이 최신이다).
 */
@Component
class GradeRecalcBatch(
    private val targets: BatchTargets,
    private val tx: TransactionTemplate,
    private val users: UserRepository,
) {
    fun run(now: Instant): Int {
        val period = GradePeriod.of(now)
        return targets.forEach(BatchTarget.GRADE_CHANGES, now) { userId ->
            tx.execute {
                users.lock(userId)
                val review = users.gradeReview(userId, period)
                if (!review.changesGrade()) return@execute false
                users.addGradeHistory(userId, review.grade, period, review.basis)
                users.find(userId)!!.regrade(review)
                true
            }!!
        }
    }
}

/**
 * weekly-settlement(ADR-0004 §10): 먼저 auto-confirm을 수행하고(소급 확정 시각이 이미 마감된 주에 들어가지 않게), 정산할 주를 오래된 주부터 정산한다.
 * 한 주 = 모든 판매자를 트랜잭션 하나로(§9.2 — 실패하면 그 주 전체가 롤백되고 예외가 전파된다. 앞 주는 커밋된 채).
 */
@Component
class WeeklySettlementBatch(
    private val tx: TransactionTemplate,
    private val autoConfirm: AutoConfirmBatch,
    private val settlements: SettlementRepository,
    private val penalties: SellerPenaltyRepository,
) {
    fun run(now: Instant): Int {
        autoConfirm.run(now)
        var processed = 0
        for (week in SettlementWeek.pending(settlements.lastPeriodStart(), now)) {
            processed += tx.execute { settle(week) }!!
        }
        return processed
    }

    /**
     * 한 주의 정산(§10.2). 반환 = 만든 정산 행 수.
     * 잠금(§9.1): 대상 항목 행을 id 순으로 잠근 뒤(order_items만 — 다른 테이블 행은 잠그지 않는다) 새 문장으로 대상 조건을 다시 평가한다.
     * 정산 행 INSERT는 판매자 users 행에 FK 잠금을 거는데, 그 전에 항목을 모두 잠갔으므로 주문 → 항목 → 사용자 순서의 항목 처리(ItemLocker)와 교착하지 않는다.
     * 엔티티 변경(패널티 settlement_id)은 마지막 조회(네이티브 — 실행 전 flush) 뒤에만 하고, 커밋 때 패널티 id 순으로 UPDATE된다.
     */
    private fun settle(week: SettlementWeek): Int {
        settlements.lockTargets(week.cutoff)
        val targets = settlements.targets(week.cutoff)
        val unsettled = penalties.unsettledBefore(week.cutoff)
        val carries = settlements.negativeCarries()
        val statements = SellerStatement.of(targets, unsettled, carries)
        for (statement in statements) {
            val settlement = statement.settlement(week)
            settlements.add(settlement)
            statement.items(settlement).forEach { settlements.addItem(it) }
            statement.attachPenalties(settlement)
        }
        return statements.size
    }
}
