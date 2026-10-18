package com.ccle.layered.service

import com.ccle.layered.domain.BatchTarget
import com.ccle.layered.domain.BatchTargets
import com.ccle.layered.domain.MarketClock
import com.ccle.layered.domain.OrderRepository
import com.ccle.layered.domain.PgTransactionRepository
import com.ccle.layered.domain.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * 기동 시 전환(ADR-0003 §9의 7): 남아 있는 PAYING 주문(직전 프로세스가 PG 호출 전후에 죽어 결과를 쓰지 못한 것)을 UNKNOWN으로 바꾸고
 * 주문마다 APPROVE UNKNOWN 1행(requested_at = 주문 created_at)을 쓴다. 이후 확정은 pg-inquiry가 한다. 앱 인스턴스는 하나라고 전제한다.
 * 요청을 받기 전에 부른다(앱 루트의 StartupRecovery 훅).
 */
@Service
class StalePaymentRecovery(
    private val clock: MarketClock,
    private val targets: BatchTargets,
    private val tx: TransactionTemplate,
    private val users: UserRepository,
    private val orders: OrderRepository,
    private val pgTransactions: PgTransactionRepository,
) {
    private val log = LoggerFactory.getLogger(StalePaymentRecovery::class.java)

    fun convertAll() {
        val converted = targets.forEach(BatchTarget.PAYING_ORDERS, clock.now()) { orderId ->
            tx.execute {
                val order = orders.find(orderId)!!
                users.lock(order.buyerId)
                orders.reload(order)
                // 결제 결과 반영 실패 뒤 재기록과 같은 판정: 잠근 뒤 다시 읽고 abandonPayment가 쓸지 정한다
                val row = order.abandonPayment({ pgTransactions.approveCount(orderId) }, requestedAt = order.createdAt)
                row?.let(pgTransactions::add)
                row != null
            }!!
        }
        if (converted > 0) log.warn("기동 시 PAYING 주문 {}건을 UNKNOWN으로 바꿨다(확정은 pg-inquiry)", converted)
    }
}
