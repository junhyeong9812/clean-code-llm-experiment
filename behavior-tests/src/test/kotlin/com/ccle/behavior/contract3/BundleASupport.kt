package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.Line
import com.ccle.behavior.contract2.Purchase
import com.ccle.behavior.contract2.S2
import com.ccle.behavior.support.Api
import com.ccle.behavior.support.Db3
import com.ccle.behavior.support.Resp
import com.ccle.behavior.support.Row3
import com.ccle.behavior.support.instantAt
import com.ccle.behavior.support.longAt
import com.ccle.behavior.support.textAt
import com.fasterxml.jackson.databind.JsonNode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 묶음 A(ADR-0007 §4 A1 리뷰·평점 · §5 A2 판매자 등급·리베이트 · §6 A3 지급 계좌·월간 정산서 · §10 A7 세금계산서 · §2.7 감사 로그)
 * 엔드포인트·달력·준비·단언 도우미. 기대값은 ADR-0007(+ ADR-0004 §10 정산)에서만 온다.
 */
object SA {
    // ---- A1 엔드포인트 (§4) ----
    fun writeReview(item: Long, user: Any?, rating: Any?, content: Any?): Resp =
        Api.post("/order-items/$item/review", mapOf("rating" to rating, "content" to content), user)
    fun writeReviewRaw(item: Long, user: Any?, raw: String): Resp = Api.request("POST", "/order-items/$item/review", rawBody = raw, userId = user)
    fun deleteReview(id: Long, user: Any?): Resp = Api.delete("/reviews/$id", user)
    fun hideReview(id: Long): Resp = Api.post("/admin/reviews/$id/hide", null)
    fun sellerReviews(userId: Long): Resp = Api.get("/sellers/$userId/reviews")

    // ---- A2 (§5.4) ----
    fun sellerGrade(user: Any?): Resp = Api.get("/me/seller-grade", user)
    fun feeRebates(user: Any?): Resp = Api.get("/me/fee-rebates", user)

    // ---- A3 (§6) ----
    fun requestPayout(user: Any?, bankCode: Any?, accountNo: Any?): Resp =
        Api.post("/me/payout-account-requests", mapOf("bankCode" to bankCode, "accountNo" to accountNo), user)
    fun requestPayoutRaw(user: Any?, raw: String): Resp = Api.request("POST", "/me/payout-account-requests", rawBody = raw, userId = user)
    fun decide(id: Long, approve: Any?, reason: Any?): Resp =
        Api.post("/admin/payout-account-requests/$id/decision", mapOf("approve" to approve, "reason" to reason))
    fun decideRaw(id: Long, raw: String): Resp = Api.request("POST", "/admin/payout-account-requests/$id/decision", rawBody = raw)
    fun payoutAccount(user: Any?): Resp = Api.get("/me/payout-account", user)
    fun monthlyStatements(user: Any?): Resp = Api.get("/me/monthly-statements", user)

    // ---- A7 (§10) ----
    fun correct(id: Long, supplyAmount: Any?): Resp = Api.post("/admin/tax-invoices/$id/correction", mapOf("supplyAmount" to supplyAmount))
    fun correctRaw(id: Long, raw: String): Resp = Api.request("POST", "/admin/tax-invoices/$id/correction", rawBody = raw)
    fun taxInvoices(user: Any?): Resp = Api.get("/me/tax-invoices", user)

    // ---- 달력 (KST 날짜) ----
    /** 달 m(1일)의 첫 월요일. */
    fun firstMonday(m: LocalDate): LocalDate = m.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))

    /** 달 m(1일)에 속하는 마지막 월요일 L(§6.4). */
    fun lastMonday(m: LocalDate): LocalDate = m.plusMonths(1).minusDays(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** 달 m에 속하는 월요일 전부(오름차순). */
    fun mondaysIn(m: LocalDate): List<LocalDate> = generateSequence(firstMonday(m)) { it.plusDays(7) }.takeWhile { it.month == m.month }.toList()

    // ---- 준비 ----
    /** 판매자 seller의 체결가 price 항목 1개를 새 구매자가 사서 구매 확정까지(현재 시각 확정 — §18 T1 회피). */
    fun confirmedSale(seller: Long, price: Long, category: String? = null): Purchase {
        val p = S2.purchase(listOf(Line(price, category, seller)))
        S2.toConfirmed(p.item, seller, p.buyer)
        return p
    }

    /** 판매자 귀책(검수 불합격) 1건 — 패널티 floor(체결가 × 10%)(ADR-0004 §6). */
    fun sellerFault(seller: Long, price: Long): Purchase {
        val p = S2.purchase(listOf(Line(price, null, seller)))
        S2.toSellerFault(p.item, seller)
        return p
    }

    /** n개 요청을 동시에 출발시킨다(기존 동시성 테스트 방식). */
    fun concurrently(n: Int, call: (Int) -> Resp): List<Resp> {
        val pool = Executors.newFixedThreadPool(n)
        val ready = CountDownLatch(n)
        val go = CountDownLatch(1)
        try {
            val futures = (0 until n).map { i ->
                pool.submit<Resp> {
                    ready.countDown()
                    go.await()
                    call(i)
                }
            }
            ready.await(10, TimeUnit.SECONDS)
            go.countDown()
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    /** 동시 요청 결과: 성공(okStatus) 정확히 1건, 나머지는 409 code. */
    fun assertOneWins(rs: List<Resp>, okStatus: Int, code: String = "ITEM_STATE_CONFLICT"): Resp {
        assertTrue(rs.all { it.status == okStatus || it.status == 409 }, "$okStatus 또는 409만: $rs")
        assertEquals(1, rs.count { it.status == okStatus }, "성공 1건: $rs")
        rs.filter { it.status == 409 }.forEach { it.assertError(409, code) }
        return rs.single { it.status == okStatus }
    }

    // ---- JSON ----
    fun JsonNode.textOrNullAt(field: String): String? {
        val n = get(field)
        assertTrue(n != null, "필드 '$field' 없음: $this")
        return if (n.isNull) null else { assertTrue(n.isTextual, "문자열 필드 '$field': $this"); n.asText() }
    }

    fun JsonNode.longOrNullAt(field: String): Long? {
        val n = get(field)
        assertTrue(n != null, "필드 '$field' 없음: $this")
        return if (n.isNull) null else { assertTrue(n.isIntegralNumber, "정수 필드 '$field': $this"); n.asLong() }
    }

    fun JsonNode.instantOrNullAt(field: String): Instant? = textOrNullAt(field)?.let { OffsetDateTime.parse(it).toInstant() }

    fun JsonNode.arr(): List<JsonNode> {
        assertTrue(isArray, "배열이어야 한다: $this")
        return toList()
    }

    // ---- 행·응답 모양 ----
    /** §4.1 리뷰 모양 = 그 리뷰 행. */
    fun assertReviewBody(j: JsonNode, row: Row3) {
        assertEquals(row.id, j.longAt("reviewId"), "reviewId: $j")
        assertEquals(row.long("order_item_id"), j.longAt("orderItemId"), "orderItemId: $j")
        assertEquals(row.long("seller_id"), j.longAt("sellerId"), "sellerId: $j")
        assertEquals(row.long("rating"), j.longAt("rating"), "rating: $j")
        assertEquals(row.text("content"), j.textAt("content"), "content: $j")
        assertEquals(row.text("status"), j.textAt("status"), "status: $j")
        assertEquals(row.instant("created_at"), j.instantAt("createdAt"), "createdAt: $j")
    }

    fun review(id: Long): Row3 = Db3.row("reviews", id)
    fun reviewsOfItem(item: Long): List<Row3> = Db3.rows("reviews", "order_item_id = ?", item)

    /** §6.1 요청 모양 = 그 요청 행. */
    fun assertPayoutRequestBody(j: JsonNode, row: Row3) {
        assertEquals(row.id, j.longAt("requestId"), "requestId: $j")
        assertEquals(row.text("bank_code"), j.textAt("bankCode"), "bankCode: $j")
        assertEquals(row.text("account_no_masked"), j.textAt("accountNoMasked"), "accountNoMasked: $j")
        assertEquals(row.text("status"), j.textAt("status"), "status: $j")
        assertEquals(row.textOrNull("reject_reason"), j.textOrNullAt("rejectReason"), "rejectReason: $j")
        assertEquals(row.instant("requested_at"), j.instantAt("requestedAt"), "requestedAt: $j")
        assertEquals(row.instantOrNull("decided_at"), j.instantOrNullAt("decidedAt"), "decidedAt: $j")
    }

    fun payoutRequests(user: Long): List<Row3> = Db3.rows("payout_account_requests", "user_id = ?", user)

    /** §10.3 계산서 모양 = 그 계산서 행. */
    fun assertInvoiceBody(j: JsonNode, row: Row3) {
        assertEquals(row.id, j.longAt("invoiceId"), "invoiceId: $j")
        assertEquals(row.long("settlement_id"), j.longAt("settlementId"), "settlementId: $j")
        assertEquals(row.text("kind"), j.textAt("kind"), "kind: $j")
        assertEquals(row.text("status"), j.textAt("status"), "status: $j")
        assertEquals(row.long("supply_amount"), j.longAt("supplyAmount"), "supplyAmount: $j")
        assertEquals(row.long("vat_amount"), j.longAt("vatAmount"), "vatAmount: $j")
        assertEquals(row.long("total_amount"), j.longAt("totalAmount"), "totalAmount: $j")
        assertEquals(row.longOrNull("ref_invoice_id"), j.longOrNullAt("refInvoiceId"), "refInvoiceId: $j")
        assertEquals(row.instant("issued_at"), j.instantAt("issuedAt"), "issuedAt: $j")
    }

    fun invoicesOfSettlement(settlementId: Long): List<Row3> = Db3.rows("tax_invoices", "settlement_id = ?", settlementId)

    /** §10.1 행 모양: kind·status·세 금액·ref·issued_at. */
    fun assertInvoiceRow(
        row: Row3, kind: String, status: String, supply: Long, vat: Long, total: Long, ref: Long?, issuedAt: Instant,
        settlementId: Long? = null, sellerId: Long? = null,
    ) {
        assertEquals(kind, row.text("kind"), "kind: $row")
        assertEquals(status, row.text("status"), "status: $row")
        assertEquals(supply, row.long("supply_amount"), "supply_amount: $row")
        assertEquals(vat, row.long("vat_amount"), "vat_amount: $row")
        assertEquals(total, row.long("total_amount"), "total_amount: $row")
        assertEquals(ref, row.longOrNull("ref_invoice_id"), "ref_invoice_id: $row")
        assertEquals(issuedAt, row.instant("issued_at"), "issued_at = 현재: $row")
        if (settlementId != null) assertEquals(settlementId, row.long("settlement_id"), "settlement_id: $row")
        if (sellerId != null) assertEquals(sellerId, row.long("seller_id"), "seller_id: $row")
    }

    /** §10.1 ISSUE 금액: vat = floor(supply × 10 / 100), total = supply + vat. */
    fun vatOf(supply: Long): Long = Math.floorDiv(supply * 10, 100L)

    // ---- 감사 로그 (§2.7·§11.3) ----
    /** 그 action·target의 감사 행이 정확히 1개이고 detail·created_at이 맞다. */
    fun assertOneAudit(action: String, targetId: Long, detail: String?, at: Instant) {
        val a = Db3.audits(action, targetId).singleOrNull() ?: kotlin.test.fail("감사 로그 $action target=$targetId 1행이어야 한다: ${Db3.audits(action, targetId)}")
        assertEquals(detail, a.textOrNull("detail"), "감사 detail: $a")
        assertEquals(at, a.instant("created_at"), "감사 created_at = 현재: $a")
    }

    fun assertNoAudit(action: String, targetId: Long) =
        assertTrue(Db3.audits(action, targetId).isEmpty(), "실패한 요청은 감사 로그를 쓰지 않는다: $action target=$targetId")

    fun auditCount(action: String, targetId: Long): Int = Db3.audits(action, targetId).size

    fun assertNullField(j: JsonNode, field: String) {
        assertTrue(j.has(field), "필드 '$field' 없음: $j")
        assertTrue(j.get(field).isNull, "'$field' = null: $j")
    }

    fun assertDate(expected: LocalDate, j: JsonNode, field: String) = assertEquals(expected.toString(), j.textAt(field), "$field \"YYYY-MM-DD\": $j")

    /** Row3는 값 비교(equals)가 없어 문자열 스냅샷으로 비교한다. */
    fun snap(rows: List<Row3>): List<String> = rows.map { it.toString() }

    fun noRow(table: String, where: String, vararg params: Any?) = assertNull(Db3.rows(table, where, *params).firstOrNull(), "$table 행 없음($where)")
}
