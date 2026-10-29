package com.ccle.god.entity

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

// 1단계(ADR-0003)·2단계(ADR-0004)가 쓰는 테이블의 행 매핑. 스키마는 docker/schema/tables.sql 이 정본이고 앱은 validate만 한다.
// 연관(@ManyToOne 등) 없이 외래 키는 id 값 그대로 둔다. 상태·종류 같은 enum 값은 DB와 같은 문자열로 둔다.
// 규칙은 여기 두지 않는다(Marketplace에 있다).

@Entity
@Table(name = "users")
class User(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var email: String,
    var name: String,
    var grade: String,
    var purchaseRestrictedUntil: Instant? = null,
    var saleRestrictedUntil: Instant? = null,
    var createdAt: Instant,
)

@Entity
@Table(name = "product_sizes")
class ProductSize(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var productId: Long,
    var size: String,
)

@Entity
@Table(name = "bids")
class Bid(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var userId: Long,
    var productSizeId: Long,
    var side: String,
    var price: Long,
    var status: String,
    var expiresAt: Instant,
    var createdAt: Instant,
)

@Entity
@Table(name = "bid_events")
class BidEvent(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var bidId: Long,
    var fromStatus: String? = null,
    var toStatus: String,
    var tradeId: Long? = null,
    var createdAt: Instant,
)

@Entity
@Table(name = "trades")
class Trade(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var buyBidId: Long,
    var sellBidId: Long,
    var buyerId: Long,
    var sellerId: Long,
    var productSizeId: Long,
    var price: Long,
    var status: String,
    var matchedAt: Instant,
    var paymentDueAt: Instant,
)

@Entity
@Table(name = "buyer_warnings")
class BuyerWarning(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var userId: Long,
    var tradeId: Long,
    var createdAt: Instant,
)

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

@Entity
@Table(name = "point_ledger")
class PointLedgerEntry(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var userId: Long,
    var kind: String,
    var status: String,
    var amount: Long,
    var remaining: Long? = null,
    var expiresAt: Instant? = null,
    var orderItemId: Long? = null,
    var orderId: Long? = null,
    var createdAt: Instant,
)

@Entity
@Table(name = "point_allocations")
class PointAllocation(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var entryId: Long,
    var earnEntryId: Long,
    var amount: Long,
)

@Entity
@Table(name = "point_balances")
class PointBalance(
    @Id
    var userId: Long,
    var available: Long,
    var pending: Long,
    var updatedAt: Instant,
)

@Entity
@Table(name = "refunds")
class Refund(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var orderItemId: Long,
    var cardAmount: Long,
    var pointAmount: Long,
    var buyerFeeAmount: Long,
    var shippingAmount: Long,
    var reason: String,
    var fault: String,
    var createdAt: Instant,
)

@Entity
@Table(name = "inspections")
class Inspection(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var orderItemId: Long,
    var result: String,
    var reason: String? = null,
    var inspectedAt: Instant,
)

@Entity
@Table(name = "disputes")
class Dispute(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var orderItemId: Long,
    var status: String,
    var openedAt: Instant,
    var resolvedAt: Instant? = null,
)

@Entity
@Table(name = "seller_penalties")
class SellerPenalty(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var sellerId: Long,
    var orderItemId: Long,
    var amount: Long,
    var settlementId: Long? = null,
    var createdAt: Instant,
)

@Entity
@Table(name = "settlements")
class Settlement(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var sellerId: Long,
    var periodStart: LocalDate,
    var periodEnd: LocalDate,
    var gross: Long,
    var feeTotal: Long,
    var penaltyTotal: Long,
    var carryIn: Long,
    var net: Long,
    var payout: Long,
    var carryOut: Long,
    var status: String,
    var paidAt: Instant? = null,
)

@Entity
@Table(name = "settlement_items")
class SettlementItem(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var settlementId: Long,
    var orderItemId: Long,
    var amount: Long,
    var fee: Long,
    var status: String,
)

// ---- 단계 A 새 테이블(ADR-0007 §3) ----

@Entity
@Table(name = "reviews")
class Review(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var orderItemId: Long,
    var buyerId: Long,
    var sellerId: Long,
    var rating: Short,
    var content: String,
    var status: String,
    var hiddenReason: String? = null,
    var createdAt: Instant,
    var updatedAt: Instant,
)

@Entity
@Table(name = "seller_ratings")
class SellerRating(
    @Id
    var sellerId: Long,
    var reviewCount: Int,
    var ratingSum: Int,
    var recalculatedAt: Instant,
)

@Entity
@Table(name = "seller_grades")
class SellerGrade(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var sellerId: Long,
    var grade: String,
    var effectiveFrom: LocalDate,
    var basisAmount: Long,
    var restricted: Boolean,
    var createdAt: Instant,
)

@Entity
@Table(name = "fee_rebates")
class FeeRebate(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var sellerId: Long,
    var month: LocalDate,
    var grade: String,
    var feeBase: Long,
    var ratePermille: Int,
    var amount: Long,
    var status: String,
    var paidAt: Instant? = null,
    var createdAt: Instant,
)

@Entity
@Table(name = "payout_account_requests")
class PayoutAccountRequest(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var userId: Long,
    var bankCode: String,
    var accountNoMasked: String,
    var status: String,
    var rejectReason: String? = null,
    var requestedAt: Instant,
    var decidedAt: Instant? = null,
)

@Entity
@Table(name = "monthly_statements")
class MonthlyStatement(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var sellerId: Long,
    var month: LocalDate,
    var settlementCount: Int,
    var gross: Long,
    var feeTotal: Long,
    var penaltyTotal: Long,
    var payoutTotal: Long,
    var carriedCount: Int,
    var heldCount: Int,
    var lastCarryOut: Long,
    var createdAt: Instant,
)

@Entity
@Table(name = "tax_invoices")
class TaxInvoice(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var settlementId: Long,
    var sellerId: Long,
    var kind: String,
    var status: String,
    var supplyAmount: Long,
    var vatAmount: Long,
    var totalAmount: Long,
    var refInvoiceId: Long? = null,
    var issuedAt: Instant,
)

@Entity
@Table(name = "admin_audit_logs")
class AdminAuditLog(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var action: String,
    var targetId: Long,
    var detail: String? = null,
    var createdAt: Instant,
)
