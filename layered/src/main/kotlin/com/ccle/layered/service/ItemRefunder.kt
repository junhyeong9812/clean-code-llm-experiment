package com.ccle.layered.service

import com.ccle.layered.domain.OrderItem
import com.ccle.layered.domain.OrderItemRepository
import com.ccle.layered.domain.OrderRepository
import com.ccle.layered.domain.PgTransaction
import com.ccle.layered.domain.PgTransactionRepository
import com.ccle.layered.domain.RefundAmounts
import com.ccle.layered.domain.RefundFault
import com.ccle.layered.domain.RefundRepository
import com.ccle.layered.domain.SellerPenalty
import com.ccle.layered.domain.SellerPenaltyRepository
import com.ccle.layered.domain.TradeRepository
import com.ccle.layered.domain.User
import com.ccle.layered.domain.UserRepository
import org.springframework.stereotype.Component
import java.time.Instant

/** 사용자 행 여러 개를 id 오름차순으로 잠근다(ADR-0004 §9.1 — 구매자·판매자가 서로 바뀐 두 처리가 교착하지 않게). */
fun UserRepository.lockInIdOrder(vararg userIds: Long) {
    userIds.distinct().sorted().forEach { lock(it) }
}

/**
 * 항목 환불(ADR-0004 §7). 주문·항목을 잠그고 상태를 확인한 트랜잭션 안에서, 아직 아무것도 바꾸지 않은 상태로 부른다(§2.3의 3·4단계).
 * 순서: 금액 → 카드 취소(PG) → 사용자 잠금(구매자, 판매자 귀책이면 판매자도, id 오름차순) → 항목 상태·cancelled_at,
 * refunds 1행, pg_transactions CANCEL 1행, 적립 예정 취소(분쟁 인정이면 회수), RESTORE, 주문 상태.
 * 카드 취소가 실패하면 409 PG_UNAVAILABLE을 던지고, 그때까지 바꾼 것이 없으므로 롤백 뒤 DB는 그대로다.
 * PG는 주문 잠금을 쥔 채 부른다: 같은 주문의 다른 항목 환불이 끼어들어 금액(마지막 환불)을 바꾸지 못하게.
 * 사용자 잠금은 PG 뒤에 잡아, PG를 기다리는 동안 같은 사용자의 결제·입찰을 막지 않는다.
 * 엔티티 변경은 잠금(네이티브 쿼리 — 실행 전 flush) 뒤에만 하고, 주문 상태는 맨 끝에 바꾼다(god와 같은 UPDATE 순서).
 */
@Component
class ItemRefunder(
    private val orders: OrderRepository,
    private val items: OrderItemRepository,
    private val trades: TradeRepository,
    private val users: UserRepository,
    private val refunds: RefundRepository,
    private val pgTransactions: PgTransactionRepository,
    private val pgCaller: PgCaller,
    private val pointBook: PointBook,
) {

    fun refund(item: OrderItem, fault: RefundFault, reason: String, now: Instant) {
        val order = orders.find(item.orderId)!!
        val amounts = RefundAmounts.of(order, items.listOf(item.orderId), item, fault) { refunds.shippingRefunded(item.orderId) }

        val pgTxId = if (amounts.card > 0) pgCaller.cancel(item.id!!, order.idempotencyKey, amounts.card) else null

        if (fault == RefundFault.SELLER) {
            users.lockInIdOrder(order.buyerId, trades.find(item.tradeId)!!.sellerId)
        } else {
            users.lock(order.buyerId)
        }
        item.markRefunded(fault, now)
        refunds.add(amounts.refund(item, reason, now))
        if (pgTxId != null) pgTransactions.add(PgTransaction.cancellation(order.id!!, item.id!!, amounts.card, pgTxId, now))
        // 회수(§8.4)가 복구(RESTORE)보다 먼저다
        if (fault == RefundFault.DISPUTE) pointBook.revokeEarn(item, now) else pointBook.cancelPendingEarn(item, now)
        if (amounts.point > 0) pointBook.restore(order, item, amounts.point, now)
        order.recordItemRefund(amounts.last)
    }
}

/**
 * 판매자 귀책(ADR-0004 §6 — 검수 불합격·발송 기한 초과 공용). 주문·항목을 잠그고 상태를 확인한 트랜잭션 안에서, 아직 아무것도 바꾸지 않은 상태로 부른다.
 * §7 환불(fault SELLER, 전액 — 판매자 행도 잠근다) → 패널티 1행 → 판매 제한 판정(판매자 행을 다시 읽은 뒤).
 */
@Component
class SellerFaults(
    private val refunder: ItemRefunder,
    private val trades: TradeRepository,
    private val users: UserRepository,
    private val penalties: SellerPenaltyRepository,
) {

    fun apply(item: OrderItem, reason: String, now: Instant) {
        refunder.refund(item, RefundFault.SELLER, reason, now)
        val sellerId = trades.find(item.tradeId)!!.sellerId
        penalties.add(SellerPenalty.issue(sellerId, item, now))
        val seller = users.find(sellerId)!!
        users.reload(seller)
        seller.restrictIfPenalized(penalties.createdAtAfter(sellerId, User.penaltyWindowStart(now)), now)
    }
}
