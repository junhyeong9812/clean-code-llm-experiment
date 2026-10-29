package com.ccle.layered.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

// A7 세금계산서(ADR-0007 §10). 행 모양(§10.1): ISSUE(ISSUED → 정정되면 CANCELLED) · CANCEL(DONE, 원본 금액 × −1) · HOLD(DONE, 0·0·0).

@Entity
@Table(name = "tax_invoices")
class TaxInvoice(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var settlementId: Long,
    var sellerId: Long,
    var kind: String,
    status: String,
    var supplyAmount: Long,
    var vatAmount: Long,
    var totalAmount: Long,
    var refInvoiceId: Long? = null,
    var issuedAt: Instant,
) {
    var status: String = status
        private set

    /**
     * 정정의 원본 처리(§10.3): ISSUE·ISSUED만(아니면 409) → CANCELLED. 원본은 status만 바뀐다(§19의 12). 원본 행을 잠근 뒤 다시 읽고 부른다.
     * 이 변경은 아래 두 INSERT보다 먼저 DB에 보내야 한다(부분 UNIQUE tax_invoices_live_settlement_key).
     */
    fun cancelForCorrection() {
        if (kind != "ISSUE" || status != "ISSUED") {
            throw MarketException(Failure.ITEM_STATE_CONFLICT, "정정할 수 없는 세금계산서: $id ($kind·$status)")
        }
        status = "CANCELLED"
    }

    /** 정정의 CANCEL 행: 원본의 세 금액 × −1, ref = 원본, issued_at = 현재. */
    fun cancellation(now: Instant) = TaxInvoice(
        settlementId = settlementId, sellerId = sellerId, kind = "CANCEL", status = "DONE",
        supplyAmount = -supplyAmount, vatAmount = -vatAmount, totalAmount = -totalAmount,
        refInvoiceId = id!!, issuedAt = now,
    )

    /** 정정 재발행: 같은 정산의 새 ISSUE/ISSUED(공급가액 = 요청 값), ref = 원본, issued_at = 현재. */
    fun reissue(supplyAmount: Long, now: Instant) = issue(settlementId, sellerId, supplyAmount, ref = id!!, now = now)

    companion object {
        /** 부가세(§10.1) = floor(공급가액 × 10 / 100). 공급가액은 0 이상이다. */
        private fun vatOf(supply: Long): Long = mulDiv(supply, 10, 100)

        private fun issue(settlementId: Long, sellerId: Long, supply: Long, ref: Long?, now: Instant): TaxInvoice {
            val vat = vatOf(supply)
            return TaxInvoice(
                settlementId = settlementId, sellerId = sellerId, kind = "ISSUE", status = "ISSUED",
                supplyAmount = supply, vatAmount = vat, totalAmount = Math.addExact(supply, vat),
                refInvoiceId = ref, issuedAt = now,
            )
        }

        /**
         * tax-invoice-issue의 정산 1건(§10.2): 계산서가 없을 때 PAID이고 paid_at ≤ 현재 → ISSUE(공급가액 = max(fee_total, 0)),
         * CARRIED → HOLD. 그 밖(계산서가 이미 있음·지급일 전)은 null. issued_at = 현재.
         */
        fun forSettlement(settlement: Settlement, existingInvoices: Long, now: Instant): TaxInvoice? = when {
            existingInvoices > 0 -> null
            settlement.status == "PAID" && !settlement.paidAt!!.isAfter(now) ->
                issue(settlement.id!!, settlement.sellerId, maxOf(settlement.feeTotal, 0L), ref = null, now = now)
            settlement.status == "CARRIED" -> TaxInvoice(
                settlementId = settlement.id!!, sellerId = settlement.sellerId, kind = "HOLD", status = "DONE",
                supplyAmount = 0, vatAmount = 0, totalAmount = 0, issuedAt = now,
            )
            else -> null
        }

        /** 정정 요청의 공급가액(§10.3): 0 이상 정수. */
        fun requireSupply(supplyAmount: Long?): Long {
            if (supplyAmount == null) invalidRequest("supplyAmount가 필요하다")
            if (supplyAmount < 0) invalidRequest("supplyAmount는 0 이상: $supplyAmount")
            return supplyAmount
        }
    }
}

/** 정정 결과(§10.3): cancelled = 새 CANCEL 행, reissued = 새 ISSUE 행. */
class TaxInvoiceCorrection(val cancelled: TaxInvoice, val reissued: TaxInvoice)

interface TaxInvoiceRepository {
    fun add(invoice: TaxInvoice)

    fun find(id: Long): TaxInvoice?

    /** 계산서 행 FOR UPDATE(§2.3). */
    fun lock(id: Long)

    fun reload(invoice: TaxInvoice)

    /** 지금까지의 변경을 DB에 보낸다(정정 원본 UPDATE를 INSERT보다 먼저 — §10.3). */
    fun flush()

    fun countOfSettlement(settlementId: Long): Long

    /** 그 판매자의 계산서 전부(세 kind), id 오름차순. */
    fun listOf(sellerId: Long): List<TaxInvoice>
}

/** 세금계산서 발행 대상의 정산 행 읽기(§10.2 — 잠금은 읽기). */
interface TaxInvoiceSettlements {
    /** 정산 행 FOR UPDATE. */
    fun lock(settlementId: Long)

    fun find(settlementId: Long): Settlement
}
