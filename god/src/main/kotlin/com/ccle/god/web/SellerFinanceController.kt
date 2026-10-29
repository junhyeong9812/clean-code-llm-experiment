package com.ccle.god.web

import com.ccle.god.Marketplace
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/** 판매자 등급·리베이트 조회(ADR-0007 §5.4), 지급 계좌 변경·판정·조회와 월간 정산서 조회(§6), 세금계산서 정정·조회(§10). */
@RestController
class SellerFinanceController(private val marketplace: Marketplace) {

    @GetMapping("/me/seller-grade")
    fun sellerGrade(@RequestHeader("X-User-Id", required = false) userHeader: String?): SellerGradesView =
        marketplace.sellerGrades(marketplace.requireUser(userHeader))

    @GetMapping("/me/fee-rebates")
    fun feeRebates(@RequestHeader("X-User-Id", required = false) userHeader: String?): List<FeeRebateView> =
        marketplace.feeRebates(marketplace.requireUser(userHeader))

    @PostMapping("/me/payout-account-requests")
    fun requestPayoutAccount(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @RequestBody(required = false) body: String?,
    ): ResponseEntity<PayoutRequestView> {
        val userId = marketplace.requireUser(userHeader)
        val json = parseObject(body)
        val request = marketplace.requestPayoutAccount(userId, json.textOrNull("bankCode"), json.textOrNull("accountNo"))
        return ResponseEntity.status(HttpStatus.CREATED).body(request)
    }

    @PostMapping("/admin/payout-account-requests/{requestId}/decision")
    fun decidePayoutAccount(@PathVariable requestId: String, @RequestBody(required = false) body: String?): PayoutRequestView {
        val json = parseObject(body)
        // reason 형식(문자열|null)은 approve 값과 무관하게 먼저 본다(ADR-0007 §19의 9)
        val approve = json.booleanOrNull("approve")
        val reason = json.textOrNull("reason")
        return marketplace.decidePayoutAccount(pathId(requestId), approve, reason)
    }

    @GetMapping("/me/payout-account")
    fun payoutAccount(@RequestHeader("X-User-Id", required = false) userHeader: String?): PayoutAccountView =
        marketplace.payoutAccount(marketplace.requireUser(userHeader))

    @GetMapping("/me/monthly-statements")
    fun monthlyStatements(@RequestHeader("X-User-Id", required = false) userHeader: String?): List<MonthlyStatementView> =
        marketplace.monthlyStatements(marketplace.requireUser(userHeader))

    @PostMapping("/admin/tax-invoices/{invoiceId}/correction")
    fun correctTaxInvoice(@PathVariable invoiceId: String, @RequestBody(required = false) body: String?): TaxInvoiceCorrected {
        val json = parseObject(body)
        return marketplace.correctTaxInvoice(pathId(invoiceId), json.longOrNull("supplyAmount"))
    }

    @GetMapping("/me/tax-invoices")
    fun taxInvoices(@RequestHeader("X-User-Id", required = false) userHeader: String?): List<TaxInvoiceView> =
        marketplace.taxInvoices(marketplace.requireUser(userHeader))
}
