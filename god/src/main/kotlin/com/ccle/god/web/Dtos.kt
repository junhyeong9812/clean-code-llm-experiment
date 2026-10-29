package com.ccle.god.web

import com.fasterxml.jackson.annotation.JsonUnwrapped
import java.time.Instant
import java.time.LocalDate

// 응답 본문 모양(ADR-0003·ADR-0004). Marketplace가 채워서 돌려주고 컨트롤러가 그대로 JSON으로 쓴다. Instant는 KST ISO-8601로 나간다(Json.kt).

data class BidPlaced(val bidId: Long, val status: String, val tradeId: Long?, val tradePrice: Long?)

data class BidCancelled(val bidId: Long, val status: String)

data class BidView(
    val bidId: Long,
    val userId: Long,
    val productSizeId: Long,
    val side: String,
    val price: Long,
    val status: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val tradeId: Long?,
)

data class BookView(val bestBuy: Long?, val bestSell: Long?)

data class TradeView(
    val tradeId: Long,
    val productSizeId: Long,
    val price: Long,
    val status: String,
    val matchedAt: Instant,
    val paymentDueAt: Instant,
)

data class OrderItemView(
    val orderItemId: Long,
    val tradeId: Long,
    val status: String,
    val price: Long,
    val buyerFee: Long,
    val cardShare: Long,
    val pointShare: Long,
    val shippingShare: Long,
)

data class OrderView(
    val orderId: Long,
    val status: String,
    val itemTotal: Long,
    val buyerFeeTotal: Long,
    val shippingFee: Long,
    val pointUsed: Long,
    val cardAmount: Long,
    val items: List<OrderItemView>,
)

/** 결제 결과. created = 새 주문(201), 아니면 같은 멱등 키의 기존 주문(200). */
data class PaymentResult(val created: Boolean, val order: OrderView)

data class PointsView(val available: Long, val pending: Long, val usable: Long)

/** 주문 항목 응답(ADR-0004 §2.2). 조회와 항목 상태를 바꾸는 엔드포인트가 같이 쓴다. refund = 환불 행, 없으면 null. */
data class ItemView(
    val orderItemId: Long,
    val orderId: Long,
    val tradeId: Long,
    val status: String,
    val price: Long,
    val buyerFee: Long,
    val cardShare: Long,
    val pointShare: Long,
    val shippingShare: Long,
    val paidAt: Instant?,
    val shippedAt: Instant?,
    val inspectedAt: Instant?,
    val deliveredAt: Instant?,
    val confirmedAt: Instant?,
    val cancelledAt: Instant?,
    val refund: RefundView?,
)

data class RefundView(val cardAmount: Long, val pointAmount: Long, val buyerFeeAmount: Long, val shippingAmount: Long, val fault: String)

/** 분쟁 제기 응답(ADR-0004 §4.3). */
data class DisputeOpened(val disputeId: Long, val orderItemId: Long, val status: String, val openedAt: Instant)

/** 분쟁 판정 응답(ADR-0004 §4.4). */
data class DisputeView(val disputeId: Long, val orderItemId: Long, val status: String, val openedAt: Instant, val resolvedAt: Instant?)

/** 배치 1회의 결과(ADR-0003 §7·§12의 2): processed = 상태가 바뀐 주 대상 수, failed = 커밋되지 못한 대상 수. */
data class BatchResult(val processed: Int, val failed: Int)

/** 판매자 정산 조회 응답 1행(ADR-0004 §10.3). periodStart·periodEnd는 "YYYY-MM-DD". items는 orderItemId 오름차순. */
data class SettlementView(
    val settlementId: Long,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val gross: Long,
    val feeTotal: Long,
    val penaltyTotal: Long,
    val carryIn: Long,
    val net: Long,
    val payout: Long,
    val carryOut: Long,
    val status: String,
    val paidAt: Instant?,
    val items: List<SettlementItemView>,
)

data class SettlementItemView(val orderItemId: Long, val amount: Long, val fee: Long, val status: String)

// ---- 단계 A (ADR-0007) ----

/** 리뷰 모양(ADR-0007 §4.1). 작성·삭제·숨김 응답과 판매자 리뷰 조회가 같이 쓴다. */
data class ReviewView(
    val reviewId: Long,
    val orderItemId: Long,
    val sellerId: Long,
    val rating: Int,
    val content: String,
    val status: String,
    val createdAt: Instant,
)

/** 판매자 리뷰 조회(§4.4). reviewCount·ratingSum = 마지막 rating-recalc 결과, averageX100은 reviewCount 0이면 null. */
data class SellerReviewsView(
    val sellerId: Long,
    val reviewCount: Long,
    val ratingSum: Long,
    val averageX100: Long?,
    val reviews: List<ReviewView>,
)

/** 판매자 등급 모양(§5.4). effectiveFrom은 "YYYY-MM-DD". */
data class SellerGradeView(val grade: String, val effectiveFrom: LocalDate, val basisAmount: Long, val restricted: Boolean)

data class SellerGradesView(val current: SellerGradeView?, val history: List<SellerGradeView>)

/** 리베이트 1행(§5.4). */
data class FeeRebateView(
    val month: LocalDate,
    val grade: String,
    val feeBase: Long,
    val ratePermille: Int,
    val amount: Long,
    val status: String,
    val paidAt: Instant?,
)

/** 지급 계좌 변경 요청 모양(§6.1). */
data class PayoutRequestView(
    val requestId: Long,
    val bankCode: String,
    val accountNoMasked: String,
    val status: String,
    val rejectReason: String?,
    val requestedAt: Instant,
    val decidedAt: Instant?,
)

/** 유효 지급 계좌(§6.3). source = REQUEST|INITIAL, 둘 다 없으면 세 필드 null. pending = PENDING 요청. */
data class PayoutAccountView(val bankCode: String?, val accountNoMasked: String?, val source: String?, val pending: PayoutRequestView?)

/** 월간 정산서 1행(§6.4). */
data class MonthlyStatementView(
    val month: LocalDate,
    val settlementCount: Int,
    val gross: Long,
    val feeTotal: Long,
    val penaltyTotal: Long,
    val payoutTotal: Long,
    val carriedCount: Int,
    val heldCount: Int,
    val lastCarryOut: Long,
)

/** 세금계산서 모양(§10.3). */
data class TaxInvoiceView(
    val invoiceId: Long,
    val settlementId: Long,
    val kind: String,
    val status: String,
    val supplyAmount: Long,
    val vatAmount: Long,
    val totalAmount: Long,
    val refInvoiceId: Long?,
    val issuedAt: Instant,
)

/** 정정 응답(§10.3): cancelled = 새 CANCEL 행, reissued = 새 ISSUE 행. */
data class TaxInvoiceCorrected(val cancelled: TaxInvoiceView, val reissued: TaxInvoiceView)

/** 알림 모양(§7.2). */
data class NotificationView(val notificationId: Long, val kind: String, val refId: Long, val createdAt: Instant, val readAt: Instant?)

/** 위시리스트 등록·삭제 응답(§8.1). */
data class WishlistEntryView(val productSizeId: Long, val createdAt: Instant)

/** 위시리스트 조회 1행(§8.1). bestSell = 체결 가능한 SELL 최저가(없으면 null), activeAlert = 그 사이즈의 ACTIVE 가격 알림(§19의 10). */
data class WishlistItemView(val productSizeId: Long, val createdAt: Instant, val bestSell: Long?, val activeAlert: PriceAlertView?)

/** 가격 알림 모양(§8.2). */
data class PriceAlertView(
    val alertId: Long,
    val productSizeId: Long,
    val targetPrice: Long,
    val status: String,
    val expiresAt: Instant,
    val triggeredBidId: Long?,
    val closedAt: Instant?,
    val createdAt: Instant,
)

/** 배송 추적 이벤트 모양(§12.2). */
data class TrackingEventView(
    val eventId: Long,
    val orderItemId: Long,
    val carrier: String,
    val trackingNo: String,
    val eventType: String,
    val occurredAt: Instant,
    val receivedAt: Instant,
)

data class ShipmentDelayView(val kind: String, val detectedAt: Instant)

/** 배송 추적 조회(§12.2): events는 occurredAt·id 오름차순, delays는 id 오름차순. */
data class TrackingView(val events: List<TrackingEventView>, val delays: List<ShipmentDelayView>)

/** 문의 모양(ADR-0007 §9.1). slaBreached는 계산 값(저장하지 않는다). */
data class InquiryView(
    val inquiryId: Long,
    val category: String,
    val title: String,
    val status: String,
    val closeReason: String?,
    val orderItemId: Long?,
    val createdAt: Instant,
    val slaDueAt: Instant,
    val slaBreached: Boolean,
    val firstAnsweredAt: Instant?,
    val answeredAt: Instant?,
    val closedAt: Instant?,
)

data class InquiryMessageView(val author: String, val body: String, val createdAt: Instant)

/** 문의 상세의 주문 항목(§9.2): 지금의 항목 상태와 OPEN 분쟁 유무. */
data class InquiryOrderItemView(val orderItemId: Long, val status: String, val openDispute: Boolean)

/** 문의 상세(§9.2) = 문의 모양 + messages(id 순) + orderItem. */
data class InquiryDetailView(
    @get:JsonUnwrapped val inquiry: InquiryView,
    val messages: List<InquiryMessageView>,
    val orderItem: InquiryOrderItemView?,
)

/** 일별 시세 1행(§11.2). date는 "YYYY-MM-DD". */
data class PriceStatView(val date: LocalDate, val tradeCount: Int, val minPrice: Long, val maxPrice: Long, val medianPrice: Long)

/** 최근 체결 1건(§11.2). */
data class RecentTradeView(val tradeId: Long, val price: Long, val matchedAt: Instant)

/** 관리자 목록 페이지(§2.5): nextAfter = 받은 항목 수가 limit과 같으면 마지막 id, 적으면 null. */
data class PageView<T>(val items: List<T>, val nextAfter: Long?)

data class RestrictedUserView(val userId: Long, val email: String, val restrictedUntil: Instant)

/** 관리자 사용자 요약(§11.3). warningCount·penaltyCount는 전체 기간 수, sellerGrade = effective_from이 가장 늦은 행(§19의 13). */
data class UserSummaryView(
    val userId: Long,
    val email: String,
    val grade: String,
    val purchaseRestrictedUntil: Instant?,
    val saleRestrictedUntil: Instant?,
    val warningCount: Long,
    val penaltyCount: Long,
    val sellerGrade: SellerGradeView?,
)

data class PenaltyView(val penaltyId: Long, val orderItemId: Long, val amount: Long, val settlementId: Long?, val createdAt: Instant)

data class WarningView(val warningId: Long, val tradeId: Long, val createdAt: Instant)

data class AdminSettlementView(
    val settlementId: Long,
    val sellerId: Long,
    val periodStart: LocalDate,
    val status: String,
    val net: Long,
    val payout: Long,
    val paidAt: Instant?,
)

data class AdminDisputeView(val disputeId: Long, val orderItemId: Long, val status: String, val openedAt: Instant, val resolvedAt: Instant?)

data class AuditLogView(val auditId: Long, val action: String, val targetId: Long, val detail: String?, val createdAt: Instant)

/** 판매자 프로필(§12.1). onVacation = 오늘(KST)이 [vacationFrom, vacationTo] 안(계산 값). */
data class SellerProfileView(
    val userId: Long,
    val shopName: String,
    val intro: String,
    val vacationFrom: LocalDate?,
    val vacationTo: LocalDate?,
    val onVacation: Boolean,
)

/** 입고 슬롯 모양(§12.3). reserved = RESERVED·CHECKED_IN 예약 수. */
data class InboundSlotView(val slotId: Long, val slotStart: Instant, val capacity: Int, val reserved: Long)

/** 입고 예약 모양(§12.3). */
data class InboundReservationView(
    val reservationId: Long,
    val slotId: Long,
    val slotStart: Instant,
    val orderItemId: Long,
    val status: String,
    val createdAt: Instant,
    val closedAt: Instant?,
)

// ---- 단계 A 보충 (ADR-0007 §20) ----

/** 쓸 수 있는 항목(§20.2.2). reviewDeadline = confirmed_at + 30일(계산 값). */
data class ReviewableItemView(val orderItemId: Long, val sellerId: Long, val confirmedAt: Instant, val reviewDeadline: Instant)

/** 내 리뷰 모양(§20.2.3) = 리뷰 모양 + hiddenReason·updatedAt·reviewDeadline(수정 기한, 계산 값). */
data class MyReviewView(
    @get:JsonUnwrapped val review: ReviewView,
    val hiddenReason: String?,
    val updatedAt: Instant,
    val reviewDeadline: Instant,
)

/** 관리자 문의함 항목(§20.4.2) = 문의 모양 + userId. */
data class AdminInquiryView(@get:JsonUnwrapped val inquiry: InquiryView, val userId: Long)

/** 관리자 문의 상세(§20.4.2) = 상세 모양(§9.2) + userId. */
data class AdminInquiryDetailView(@get:JsonUnwrapped val detail: InquiryDetailView, val userId: Long)

/** 예약 변경 응답(§20.5.1): cancelled = 옛 예약, reserved = 새 예약. */
data class InboundRescheduled(val cancelled: InboundReservationView, val reserved: InboundReservationView)

/** 일괄 읽음 응답(§20.6.1). */
data class ReadAllView(val readCount: Long)

/** 안 읽은 수(§20.6.2). */
data class UnreadCountView(val unreadCount: Long)
