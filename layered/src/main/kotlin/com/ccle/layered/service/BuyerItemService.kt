package com.ccle.layered.service

import com.ccle.layered.domain.Dispute
import com.ccle.layered.domain.DisputeDecision
import com.ccle.layered.domain.DisputeRepository
import com.ccle.layered.domain.MarketClock
import com.ccle.layered.domain.OrderItem
import com.ccle.layered.domain.OrderRepository
import com.ccle.layered.domain.RefundFault
import com.ccle.layered.domain.RefundRepository
import com.ccle.layered.domain.UserRepository
import com.ccle.layered.domain.notFound
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * 확정 반영(ADR-0004 §4.1·auto-confirm 공용): 구매자 행 잠금 → 항목 CONFIRMED → 포인트 확정(§8.2).
 * 주문·항목을 잠그고 확인한 트랜잭션 안에서, 아직 아무것도 바꾸지 않은 상태로 부른다(변경은 구매자 잠금 뒤).
 */
@Component
class ItemConfirmer(
    private val orders: OrderRepository,
    private val users: UserRepository,
    private val pointBook: PointBook,
) {
    fun confirm(item: OrderItem, confirmedAt: Instant, now: Instant) {
        val buyerId = orders.find(item.orderId)!!.buyerId
        users.lock(buyerId)
        item.markConfirmed(confirmedAt)
        pointBook.confirmEarn(item, buyerId, confirmedAt, now)
    }
}

/**
 * 구매자 절차(ADR-0004 §4.1~§4.3): 확정·취소·분쟁 제기. 각각 한 트랜잭션에서 주문 → 항목을 잠그고 다시 읽은 뒤 확인·반영한다.
 * 판정 순서: 404 → 403(구매자 아님) → 409(상태) → 409(기한).
 */
@Service
class BuyerItemService(
    private val clock: MarketClock,
    private val tx: TransactionTemplate,
    private val locker: ItemLocker,
    private val orders: OrderRepository,
    private val confirmer: ItemConfirmer,
    private val refunder: ItemRefunder,
    private val disputes: DisputeRepository,
    private val refunds: RefundRepository,
) {

    /** 구매 확정. DELIVERED만, confirmed_at = min(현재, delivered_at + 7일). */
    fun confirm(userId: Long, itemId: Long): ItemDetail {
        val now = clock.now()
        return tx.execute {
            val item = lockOwnItem(userId, itemId)
            confirmer.confirm(item, item.confirmationTime(now), now)
            refunds.detailOf(item)
        }!!
    }

    /** 구매자 취소. PAID만 → §7 환불(fault BUYER). */
    fun cancel(userId: Long, itemId: Long): ItemDetail {
        val now = clock.now()
        return tx.execute {
            val item = lockOwnItem(userId, itemId)
            item.requireCancellableByBuyer()
            refunder.refund(item, RefundFault.BUYER, "구매자 취소", now)
            refunds.detailOf(item)
        }!!
    }

    /** 분쟁 제기. CONFIRMED이고 현재 ≤ confirmed_at + 14일 → disputes OPEN 1행 + 항목 DISPUTED. */
    fun openDispute(userId: Long, itemId: Long): Dispute {
        val now = clock.now()
        return tx.execute {
            val item = lockOwnItem(userId, itemId)
            val dispute = item.openDispute(now)
            disputes.add(dispute)
            dispute
        }!!
    }

    private fun lockOwnItem(userId: Long, itemId: Long): OrderItem {
        val item = locker.lock(itemId)
        item.requireBuyer(orders.find(item.orderId)!!, userId)
        return item
    }
}

/**
 * 분쟁 판정(ADR-0004 §4.4). OPEN만. 기각: REJECTED, 항목 CONFIRMED(confirmed_at 그대로).
 * 인정: §7 환불(fault DISPUTE, 전액, cancelled_at = resolved_at) + §8.4 회수, 패널티 없음. 카드 취소가 실패하면 409 PG_UNAVAILABLE, 분쟁은 OPEN 그대로.
 * 잠금 순서: 분쟁 조회 → 주문 → 항목 → 분쟁을 다시 읽어 상태 확인. 분쟁 상태는 맨 끝에 바꾼다(god와 같은 UPDATE 순서).
 */
@Service
class DisputeService(
    private val clock: MarketClock,
    private val tx: TransactionTemplate,
    private val locker: ItemLocker,
    private val disputes: DisputeRepository,
    private val refunder: ItemRefunder,
) {
    fun resolve(disputeId: Long, accept: Boolean?): Dispute {
        val decision = DisputeDecision.of(accept)
        val now = clock.now()
        return tx.execute {
            val dispute = disputes.find(disputeId) ?: notFound("없는 분쟁: $disputeId")
            val item = locker.lock(dispute.orderItemId)
            disputes.reload(dispute)
            dispute.requireOpen()
            if (decision == DisputeDecision.ACCEPTED) {
                refunder.refund(item, RefundFault.DISPUTE, "분쟁 인정", now)
            } else {
                item.rejectDispute()
            }
            dispute.resolve(decision, now)
            dispute
        }!!
    }
}
