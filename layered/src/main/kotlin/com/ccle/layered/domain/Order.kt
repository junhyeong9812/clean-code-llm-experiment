package com.ccle.layered.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

// 클래스 이름을 Order로 하면 JPQL의 ORDER 키워드와 겹치므로 PurchaseOrder로 둔다(테이블은 orders)
@Entity
@Table(name = "orders")
class PurchaseOrder(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var buyerId: Long,
    var status: String,
    var itemTotal: Long,
    var buyerFeeTotal: Long,
    var shippingFee: Long,
    var pointUsed: Long,
    var cardAmount: Long,
    var idempotencyKey: String,
    var createdAt: Instant,
    var paidAt: Instant? = null,
)

@Entity
@Table(name = "order_items")
class OrderItem(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var orderId: Long,
    var tradeId: Long,
    var price: Long,
    var buyerFee: Long,
    var cardShare: Long,
    var pointShare: Long,
    var shippingShare: Long,
    var status: String,
    var paidAt: Instant? = null,
    var shippedAt: Instant? = null,
    var inspectedAt: Instant? = null,
    var deliveredAt: Instant? = null,
    var confirmedAt: Instant? = null,
    var cancelledAt: Instant? = null,
)

@Entity
@Table(name = "pg_transactions")
class PgTransaction(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var orderId: Long,
    var kind: String,
    var amount: Long,
    var status: String,
    var pgTxId: String? = null,
    var idempotencyKey: String,
    var requestedAt: Instant,
    var respondedAt: Instant? = null,
)
