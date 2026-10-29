package com.ccle.layered.service

import com.ccle.layered.domain.BatchTarget
import com.ccle.layered.domain.BatchTargets
import com.ccle.layered.domain.MarketClock
import com.ccle.layered.domain.TaxInvoice
import com.ccle.layered.domain.TaxInvoiceCorrection
import com.ccle.layered.domain.TaxInvoiceRepository
import com.ccle.layered.domain.TaxInvoiceSettlements
import com.ccle.layered.domain.notFound
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/** A7 세금계산서 정정·조회(ADR-0007 §10.3·§10.4). PG·카드 취소와 무관하다. */
@Service
class TaxInvoiceService(
    private val clock: MarketClock,
    private val tx: TransactionTemplate,
    private val invoices: TaxInvoiceRepository,
    private val audit: AdminAuditService,
) {

    /**
     * 정정(§10.3): 400 → 404 → 원본 행 FOR UPDATE → 409(ISSUE·ISSUED 아님). 한 트랜잭션, 이 순서로: 원본 → CANCELLED(INSERT보다 먼저 DB에 보낸다)
     * · CANCEL 1행 · 새 ISSUE/ISSUED 1행 · 감사 TAX_INVOICE_CORRECTION(target = 원본, detail = 새 ISSUE id).
     */
    fun correct(invoiceId: Long, supplyAmount: Long?): TaxInvoiceCorrection {
        val supply = TaxInvoice.requireSupply(supplyAmount)
        val now = clock.now()
        return tx.execute {
            val original = invoices.find(invoiceId) ?: notFound("없는 세금계산서: $invoiceId")
            invoices.lock(invoiceId)
            invoices.reload(original)
            original.cancelForCorrection()
            invoices.flush()
            val cancel = original.cancellation(now)
            invoices.add(cancel)
            val reissue = original.reissue(supply, now)
            invoices.add(reissue)
            audit.record("TAX_INVOICE_CORRECTION", invoiceId, reissue.id!!.toString(), now)
            TaxInvoiceCorrection(cancelled = cancel, reissued = reissue)
        }!!
    }

    /** 판매자 본인 계산서 전부(세 kind), invoiceId 오름차순(§10.4). 사용자 확인은 부르는 쪽이 한다. */
    fun list(sellerId: Long): List<TaxInvoice> = invoices.listOf(sellerId)
}

/**
 * tax-invoice-issue(ADR-0007 §10.2): 계산서가 하나도 없는 정산 중 (PAID이고 paid_at ≤ 현재) 또는 CARRIED(settlements id 순).
 * 정산 1건 = 그 정산 행을 잠근 트랜잭션 하나(읽기), 잠근 뒤 다시 확인한다. processed = 쓴 행 수.
 */
@Component
class TaxInvoiceIssueBatch(
    private val targets: BatchTargets,
    private val tx: TransactionTemplate,
    private val settlements: TaxInvoiceSettlements,
    private val invoices: TaxInvoiceRepository,
) {
    fun run(now: Instant): BatchResult = targets.forEachIsolated("tax-invoice-issue", BatchTarget.UNINVOICED_SETTLEMENTS, now) { settlementId ->
        tx.execute {
            settlements.lock(settlementId)
            val settlement = settlements.find(settlementId)
            val invoice = TaxInvoice.forSettlement(settlement, invoices.countOfSettlement(settlementId), now)
            if (invoice != null) invoices.add(invoice)
            invoice != null
        }!!
    }
}
