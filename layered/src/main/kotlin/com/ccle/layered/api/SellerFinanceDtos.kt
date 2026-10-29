package com.ccle.layered.api

import com.ccle.layered.domain.FeeRebate
import com.ccle.layered.domain.MonthlyStatement
import com.ccle.layered.domain.PayoutAccount
import com.ccle.layered.domain.PayoutAccountRequest
import com.ccle.layered.domain.Review
import com.ccle.layered.domain.SellerGrade
import com.ccle.layered.domain.SellerReviews
import com.ccle.layered.domain.TaxInvoice
import com.ccle.layered.domain.TaxInvoiceCorrection
import com.ccle.layered.service.SellerGrades
import java.time.Instant
import java.time.LocalDate

// 응답 본문 모양(ADR-0007 §4 A1 · §5 A2 · §6 A3 · §10 A7). 날짜는 "YYYY-MM-DD", Instant는 KST ISO-8601(Json.kt).

/** 리뷰 모양(§4.1). */
data class ReviewView(
    val reviewId: Long,
    val orderItemId: Long,
    val sellerId: Long,
    val rating: Int,
    val content: String,
    val status: String,
    val createdAt: Instant,
) {
    constructor(r: Review) : this(r.id!!, r.orderItemId, r.sellerId, r.rating.toInt(), r.content, r.status, r.createdAt)
}

/** 판매자 리뷰 조회(§4.4). */
data class SellerReviewsView(
    val sellerId: Long,
    val reviewCount: Long,
    val ratingSum: Long,
    val averageX100: Long?,
    val reviews: List<ReviewView>,
) {
    constructor(s: SellerReviews) : this(s.sellerId, s.reviewCount, s.ratingSum, s.averageX100, s.reviews.map { ReviewView(it) })
}

/** 판매자 등급 모양(§5.4). */
data class SellerGradeView(val grade: String, val effectiveFrom: LocalDate, val basisAmount: Long, val restricted: Boolean) {
    constructor(g: SellerGrade) : this(g.grade, g.effectiveFrom, g.basisAmount, g.restricted)
}

data class SellerGradesView(val current: SellerGradeView?, val history: List<SellerGradeView>) {
    constructor(g: SellerGrades) : this(g.current?.let { SellerGradeView(it) }, g.history.map { SellerGradeView(it) })
}

/** 리베이트 1행(§5.4). */
data class FeeRebateView(
    val month: LocalDate,
    val grade: String,
    val feeBase: Long,
    val ratePermille: Int,
    val amount: Long,
    val status: String,
    val paidAt: Instant?,
) {
    constructor(f: FeeRebate) : this(f.month, f.grade, f.feeBase, f.ratePermille, f.amount, f.status, f.paidAt)
}

/** 지급 계좌 변경 요청 모양(§6.1). */
data class PayoutRequestView(
    val requestId: Long,
    val bankCode: String,
    val accountNoMasked: String,
    val status: String,
    val rejectReason: String?,
    val requestedAt: Instant,
    val decidedAt: Instant?,
) {
    constructor(p: PayoutAccountRequest) : this(p.id!!, p.bankCode, p.accountNoMasked, p.status, p.rejectReason, p.requestedAt, p.decidedAt)
}

/** 유효 지급 계좌(§6.3). */
data class PayoutAccountView(val bankCode: String?, val accountNoMasked: String?, val source: String?, val pending: PayoutRequestView?) {
    constructor(a: PayoutAccount) : this(a.bankCode, a.accountNoMasked, a.source, a.pending?.let { PayoutRequestView(it) })
}

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
) {
    constructor(m: MonthlyStatement) : this(
        m.month, m.settlementCount, m.gross, m.feeTotal, m.penaltyTotal, m.payoutTotal, m.carriedCount, m.heldCount, m.lastCarryOut,
    )
}

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
) {
    constructor(v: TaxInvoice) : this(
        v.id!!, v.settlementId, v.kind, v.status, v.supplyAmount, v.vatAmount, v.totalAmount, v.refInvoiceId, v.issuedAt,
    )
}

/** 정정 응답(§10.3). */
data class TaxInvoiceCorrected(val cancelled: TaxInvoiceView, val reissued: TaxInvoiceView) {
    constructor(c: TaxInvoiceCorrection) : this(TaxInvoiceView(c.cancelled), TaxInvoiceView(c.reissued))
}
