package com.ccle.layered.service

import com.ccle.layered.domain.BatchTarget
import com.ccle.layered.domain.BatchTargets
import com.ccle.layered.domain.MarketClock
import com.ccle.layered.domain.OrderRepository
import com.ccle.layered.domain.PgTransaction
import com.ccle.layered.domain.PgTransactionRepository
import com.ccle.layered.domain.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * 기동 시 전환(ADR-0003 §9의 7): 남아 있는 PAYING 주문(직전 프로세스가 PG 호출 전후에 죽어 결과를 쓰지 못한 것)을 UNKNOWN으로 바꾸고
 * 주문마다 APPROVE UNKNOWN 1행(requested_at = 주문 created_at)을 쓴다. 이후 확정은 pg-inquiry가 한다. 앱 인스턴스는 하나라고 전제한다.
 *
 * SmartInitializingSingleton은 모든 싱글턴 빈을 만든 직후(refresh의 finishBeanFactoryInitialization) 불린다.
 * 내장 Tomcat은 그 뒤 finishRefresh의 WebServerStartStopLifecycle.start()에서야 커넥터를 붙여 포트를 열므로,
 * 이 훅이 끝나기 전에는 요청이 들어올 수 없다. 여기서 예외가 나면 기동이 실패한다(PAYING을 남긴 채 요청을 받지 않는다).
 */
@Component
class StartupRecovery(
    private val clock: MarketClock,
    private val targets: BatchTargets,
    private val tx: TransactionTemplate,
    private val users: UserRepository,
    private val orders: OrderRepository,
    private val pgTransactions: PgTransactionRepository,
) : SmartInitializingSingleton {
    private val log = LoggerFactory.getLogger(StartupRecovery::class.java)

    override fun afterSingletonsInstantiated() {
        val converted = targets.forEach(BatchTarget.PAYING_ORDERS, clock.now()) { orderId ->
            tx.execute {
                val order = orders.find(orderId)!!
                users.lock(order.buyerId)
                orders.reload(order)
                // 결제 결과 반영과 같은 원리: 잠근 뒤 다시 읽어 아직 PAYING이고 APPROVE 행이 없을 때만 쓴다
                if (order.status != "PAYING" || pgTransactions.approveCount(orderId) > 0) return@execute false
                pgTransactions.add(PgTransaction.unknownApproval(order, requestedAt = order.createdAt))
                order.markUnknown()
                true
            }!!
        }
        if (converted > 0) log.warn("기동 시 PAYING 주문 {}건을 UNKNOWN으로 바꿨다(확정은 pg-inquiry)", converted)
    }
}
