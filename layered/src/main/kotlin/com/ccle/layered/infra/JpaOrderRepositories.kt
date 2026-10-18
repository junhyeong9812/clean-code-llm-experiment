package com.ccle.layered.infra

import com.ccle.layered.domain.DuplicateIdempotencyKeyException
import com.ccle.layered.domain.OrderItem
import com.ccle.layered.domain.OrderItemRepository
import com.ccle.layered.domain.OrderRepository
import com.ccle.layered.domain.PgTransaction
import com.ccle.layered.domain.PgTransactionRepository
import com.ccle.layered.domain.PurchaseOrder
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import org.hibernate.exception.ConstraintViolationException
import org.springframework.stereotype.Repository
import java.time.Instant

// 조회는 em.find·em.createQuery로 한다(Spring Data 저장소 메서드는 트랜잭션 밖에서 부르면 호출마다 읽기 전용 트랜잭션을 연다.
// god처럼 트랜잭션 밖에서는 autocommit 1문, 안에서는 그 트랜잭션의 영속성 컨텍스트로 읽는다).
@Repository
class JpaOrderRepository(private val em: EntityManager) : OrderRepository {

    override fun find(id: Long): PurchaseOrder? = em.find(PurchaseOrder::class.java, id)

    override fun findByIdempotencyKey(key: String): PurchaseOrder? =
        em.createQuery("SELECT o FROM PurchaseOrder o WHERE o.idempotencyKey = :key", PurchaseOrder::class.java)
            .setParameter("key", key)
            .resultList.firstOrNull()

    /**
     * 주문 INSERT는 persist 시점(IDENTITY)에 실행되고 UNIQUE는 즉시 검사되므로 멱등 키 위반은 커밋이 아니라 여기서 올라온다.
     * orders_idempotency_key_key 위반만 도메인 예외로 바꾸고, 그 밖의 예외는 그대로 올린다.
     */
    override fun add(order: PurchaseOrder) {
        try {
            em.persist(order)
        } catch (e: PersistenceException) {
            val keyConflict = generateSequence<Throwable>(e) { it.cause }
                .any { it is ConstraintViolationException && it.constraintName == "orders_idempotency_key_key" }
            if (keyConflict) throw DuplicateIdempotencyKeyException(order.idempotencyKey, e)
            throw e
        }
    }

    override fun reload(order: PurchaseOrder) = em.refresh(order)

    override fun countUndecidedFor(tradeId: Long): Long =
        (
            em.createNativeQuery(
                "SELECT count(*) FROM order_items i JOIN orders o ON o.id = i.order_id" +
                    " WHERE i.trade_id = :trade AND i.status <> 'PAYMENT_FAILED' AND o.status IN ('UNKNOWN', 'PAYING')",
            ).setParameter("trade", tradeId).singleResult as Number
            ).toLong()
}

@Repository
class JpaOrderItemRepository(private val em: EntityManager) : OrderItemRepository {

    override fun add(item: OrderItem) = em.persist(item)

    override fun listOf(orderId: Long): List<OrderItem> =
        em.createQuery("SELECT i FROM OrderItem i WHERE i.orderId = :order ORDER BY i.id", OrderItem::class.java)
            .setParameter("order", orderId)
            .resultList

    override fun tradeIdsOf(orderId: Long): Set<Long> =
        em.createQuery("SELECT i.tradeId FROM OrderItem i WHERE i.orderId = :order", Long::class.javaObjectType)
            .setParameter("order", orderId)
            .resultList.toSet()

    override fun countLive(tradeIds: List<Long>): Long =
        em.createQuery(
            "SELECT count(i) FROM OrderItem i WHERE i.tradeId IN :ids AND i.status <> 'PAYMENT_FAILED'",
            Long::class.javaObjectType,
        ).setParameter("ids", tradeIds).singleResult
}

@Repository
class JpaPgTransactionRepository(private val em: EntityManager) : PgTransactionRepository {

    override fun add(row: PgTransaction) = em.persist(row)

    override fun approveCount(orderId: Long): Long =
        em.createQuery("SELECT count(p) FROM PgTransaction p WHERE p.orderId = :order AND p.kind = 'APPROVE'", Long::class.javaObjectType)
            .setParameter("order", orderId)
            .singleResult

    override fun approveRequestedAt(orderId: Long): Instant =
        em.createQuery(
            "SELECT p.requestedAt FROM PgTransaction p WHERE p.orderId = :order AND p.kind = 'APPROVE'",
            Instant::class.java,
        ).setParameter("order", orderId).singleResult
}
