package com.ccle.layered.service

import com.ccle.layered.domain.OrderItemRepository
import com.ccle.layered.domain.OrderRepository
import com.ccle.layered.domain.OrderSnapshot
import org.springframework.stereotype.Component
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * 주문과 항목을 한 스냅샷에서 읽는다(읽기 전용 REPEATABLE READ 트랜잭션). READ COMMITTED는 문장마다 스냅샷이 바뀌어
 * 그 사이 pg-inquiry 승인이 커밋되면 {주문 UNKNOWN, 항목 PAID} 같은 조합을 읽을 수 있다.
 * 트랜잭션 밖에서 부른다(결제 결과 확인·같은 키 대기·주문 조회).
 */
@Component
class OrderReader(tx: TransactionTemplate, private val orders: OrderRepository, private val items: OrderItemRepository) {

    private val snapshotTx = TransactionTemplate(tx.transactionManager!!).apply {
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        isReadOnly = true
    }

    fun read(orderId: Long): OrderSnapshot = snapshotTx.execute { OrderSnapshot(orders.find(orderId)!!, items.listOf(orderId)) }!!
}
