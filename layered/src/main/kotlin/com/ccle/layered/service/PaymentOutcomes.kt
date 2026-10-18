package com.ccle.layered.service

import com.ccle.layered.domain.OrderItemRepository
import com.ccle.layered.domain.OrderRepository
import com.ccle.layered.domain.PointLedgerEntry
import com.ccle.layered.domain.PointRepository
import com.ccle.layered.domain.TradeRepository
import com.ccle.layered.domain.UserRepository
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 결제 결과 반영(§5.1 표). 결제 직후와 pg-inquiry가 같이 쓴다. 호출자의 트랜잭션 안에서 부르고, 구매자 행을 잠근 뒤 바꾼다.
 */
@Component
class PaymentOutcomes(
    private val orders: OrderRepository,
    private val items: OrderItemRepository,
    private val trades: TradeRepository,
    private val users: UserRepository,
    private val points: PointRepository,
    private val pointBook: PointBook,
) {

    /** 승인: 주문·항목 PAID(paid_at), 체결 PAID, USE CONFIRMED, 항목별 EARN PENDING(적립 > 0만), 잔액 pending 증가. */
    fun approve(orderId: Long, paidAt: Instant, now: Instant) {
        val order = orders.find(orderId)!!
        users.lock(order.buyerId)
        order.approve(paidAt)

        val rate = users.find(order.buyerId)!!.earnRatePermille()
        val earns = mutableListOf<PointLedgerEntry>()
        for (item in items.listOf(orderId)) {
            val earn = item.markPaid(paidAt, order.buyerId, rate)
            trades.find(item.tradeId)!!.markPaid()
            if (earn != null) {
                points.add(earn)
                earns += earn
            }
        }
        points.useEntryOf(orderId)?.confirm()
        PointLedgerEntry.pendingDelta(earns)?.let { pointBook.changeBalance(order.buyerId, it, now) }
    }

    /** 거절: 주문 FAILED, 항목 PAYMENT_FAILED, 체결은 그대로, 포인트 예약 해제. */
    fun decline(orderId: Long, now: Instant) {
        val order = orders.find(orderId)!!
        users.lock(order.buyerId)
        order.decline()
        for (item in items.listOf(orderId)) item.markPaymentFailed()
        val use = points.useEntryOf(orderId) ?: return
        pointBook.release(use, now)
    }
}
