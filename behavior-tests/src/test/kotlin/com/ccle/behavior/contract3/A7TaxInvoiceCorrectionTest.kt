package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.HOUR
import com.ccle.behavior.contract2.Kst
import com.ccle.behavior.contract2.S2
import com.ccle.behavior.support.Contract3Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db2
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.Row3
import com.ccle.behavior.support.SlowTrigger
import com.ccle.behavior.support.longAt
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-0007 §10.3 세금계산서 정정(원본 CANCELLED + CANCEL 1행 + 새 ISSUE 1행 + §2.7 감사 TAX_INVOICE_CORRECTION, 한 트랜잭션) ·
 * 오류(400 → 404 → 409) · 동시 정정 · §10.4 조회. 원본 ISSUE는 정산(TECH 판매) → 지급일 → tax-invoice-issue로 만든다.
 */
@Contract3Test
class A7TaxInvoiceCorrectionTest {

    private class Issued(val seller: Long, val settlementId: Long, val invoice: Row3)

    /** 판매자마다 TECH 판매 1건(수수료 = 체결가 × 4%)과 패널티만 있는 판매자 1명 → 정산 → 지급일 → 발행. 반환: (ISSUE들, HOLD). */
    private fun issue(prices: List<Long>): Pair<List<Issued>, Row3> {
        val sellers = prices.map { p -> Db.createUser().also { SA.confirmedSale(it, p, "TECH") } }
        val carried = Db.createUser()
        SA.sellerFault(carried, 30_000)
        Flow.moveTo(Kst.cutoffOf(Flow.now()))
        S2.runBatch("weekly-settlement")
        val settlements = sellers.map { Db2.settlementsOfSeller(it).single() }
        Flow.moveTo(settlements.first().paidAt!!)
        S2.runBatch("tax-invoice-issue")
        val issued = sellers.mapIndexed { i, s ->
            val inv = SA.invoicesOfSettlement(settlements[i].id).single()
            assertEquals("ISSUED", inv.text("status"), "준비: $inv")
            Issued(s, settlements[i].id, inv)
        }
        val hold = SA.invoicesOfSettlement(Db2.settlementsOfSeller(carried).single().id).single()
        assertEquals("HOLD", hold.text("kind"), "준비: $hold")
        return issued to hold
    }

    /** 정정 결과 행: 원본 CANCELLED(나머지 열 그대로), CANCEL(DONE, −원본 금액, ref 원본), 새 ISSUE(supply, ref 원본). 반환: 새 ISSUE 행. */
    private fun assertCorrected(orig: Row3, supply: Long, at: Instant): Row3 {
        val all = SA.invoicesOfSettlement(orig.long("settlement_id"))
        val after = all.single { it.id == orig.id }
        assertEquals("CANCELLED", after.text("status"), "원본 → CANCELLED: $after")
        // §19의 12: 원본은 status만 바뀌고 issued_at을 포함한 다른 열은 그대로
        for (col in listOf("kind", "supply_amount", "vat_amount", "total_amount", "ref_invoice_id", "settlement_id", "seller_id", "issued_at")) {
            assertEquals(orig[col], after[col], "원본 $col 그대로: $after")
        }
        val newer = all.filter { it.id > orig.id && it.longOrNull("ref_invoice_id") == orig.id }
        assertEquals(2, newer.size, "CANCEL 1행 + 새 ISSUE 1행: $all")
        val cancel = newer.single { it.text("kind") == "CANCEL" }
        SA.assertInvoiceRow(
            cancel, "CANCEL", "DONE", -orig.long("supply_amount"), -orig.long("vat_amount"), -orig.long("total_amount"), orig.id, at,
            orig.long("settlement_id"), orig.long("seller_id"),
        )
        val re = newer.single { it.text("kind") == "ISSUE" }
        val vat = SA.vatOf(supply)
        SA.assertInvoiceRow(re, "ISSUE", "ISSUED", supply, vat, supply + vat, orig.id, at, orig.long("settlement_id"), orig.long("seller_id"))
        SA.assertOneAudit("TAX_INVOICE_CORRECTION", orig.id, re.id.toString(), at)
        return re
    }

    @Test
    fun `§10_3·§2_7 정정 - 원본 CANCELLED·CANCEL(−금액)·새 ISSUE(vat floor), 감사 detail = 새 ISSUE id, 응답 cancelled = 새 CANCEL·reissued(§19의 12), 원본 issued_at 그대로, 재발행본 재정정(0원)·재실행 무발행·조회`() {
        val (issued, _) = issue(listOf(30_875))                  // 수수료 1,235
        val orig = issued.single().invoice
        assertEquals(1_235L, orig.long("supply_amount"), "준비")

        val t1 = Flow.advance(HOUR)
        val r = SA.correct(orig.id, 12_345).assertStatus(200)
        val re = assertCorrected(orig, 12_345, t1)                // vat floor(1,234.5) = 1,234
        assertEquals(1_234L, re.long("vat_amount"))
        SA.assertInvoiceBody(r.json.get("reissued"), re)
        // §19의 12: cancelled = 새 CANCEL 행
        val cancelRow = SA.invoicesOfSettlement(orig.long("settlement_id")).single { it.text("kind") == "CANCEL" && it.longOrNull("ref_invoice_id") == orig.id }
        val cancelledNode = r.json.get("cancelled")
        assertTrue(cancelledNode != null && cancelledNode.isObject, "cancelled 객체: $r")
        SA.assertInvoiceBody(cancelledNode, cancelRow)

        // 재발행본(ISSUE·ISSUED)은 다시 정정할 수 있다 — 공급가액 0 경계
        val t2 = Flow.advance(HOUR)
        val r2 = SA.correct(re.id, 0).assertStatus(200)
        val re2 = assertCorrected(re, 0, t2)
        SA.assertInvoiceBody(r2.json.get("reissued"), re2)
        assertEquals(5, SA.invoicesOfSettlement(issued.single().settlementId).size)
        assertEquals(1, SA.invoicesOfSettlement(issued.single().settlementId).count { it.text("kind") == "ISSUE" && it.text("status") == "ISSUED" })

        Flow.advance(HOUR)
        S2.runBatch("tax-invoice-issue")
        assertEquals(5, SA.invoicesOfSettlement(issued.single().settlementId).size, "계산서가 있는 정산은 다시 발행하지 않는다")

        // §10.4 조회: 세 kind 전부, invoiceId 오름차순
        val list = SA.taxInvoices(issued.single().seller).assertStatus(200)
        val rows = SA.invoicesOfSettlement(issued.single().settlementId)
        assertEquals(rows.size, list.json.size(), "목록: $list")
        rows.forEachIndexed { i, row -> SA.assertInvoiceBody(list.json[i], row) }
        assertEquals(listOf("ISSUE", "CANCEL", "ISSUE", "CANCEL", "ISSUE"), rows.map { it.text("kind") })
    }

    @Test
    fun `§10_3 오류 - supplyAmount 음수·소수·문자열·null·누락 400, 없는 계산서 404, CANCELLED 원본·CANCEL·HOLD 409, 판정 순서 400 → 404 → 409, 실패는 무변경·감사 없음`() {
        val (issued, hold) = issue(listOf(30_000))
        val orig = issued.single().invoice
        val sid = issued.single().settlementId

        for (raw in listOf(
            """{"supplyAmount":-1}""", """{"supplyAmount":1.5}""", """{"supplyAmount":"abc"}""",
            """{"supplyAmount":null}""", """{}""", """not-json""",
        )) SA.correctRaw(orig.id, raw).assertError(400, "INVALID_REQUEST")
        assertEquals(SA.snap(listOf(orig)), SA.snap(SA.invoicesOfSettlement(sid)), "400은 아무것도 바꾸지 않는다")
        SA.assertNoAudit("TAX_INVOICE_CORRECTION", orig.id)

        val missing = Db.missingId("tax_invoices")
        SA.correct(missing, 100).assertError(404, "NOT_FOUND")
        SA.correct(missing, -1).assertError(400, "INVALID_REQUEST")             // 400이 404보다
        SA.assertNoAudit("TAX_INVOICE_CORRECTION", missing)

        SA.correct(hold.id, 100).assertError(409, "ITEM_STATE_CONFLICT")
        SA.correct(hold.id, -1).assertError(400, "INVALID_REQUEST")             // 400이 409보다
        assertEquals(SA.snap(listOf(hold)), SA.snap(SA.invoicesOfSettlement(hold.long("settlement_id"))))
        SA.assertNoAudit("TAX_INVOICE_CORRECTION", hold.id)

        val at = Flow.advance(HOUR)
        SA.correct(orig.id, 500).assertStatus(200)
        val after = SA.invoicesOfSettlement(sid)
        val cancel = after.single { it.text("kind") == "CANCEL" }
        Flow.advance(HOUR)
        SA.correct(orig.id, 600).assertError(409, "ITEM_STATE_CONFLICT")       // 이미 정정된 원본
        SA.correct(cancel.id, 600).assertError(409, "ITEM_STATE_CONFLICT")
        assertEquals(SA.snap(after), SA.snap(SA.invoicesOfSettlement(sid)), "409는 아무것도 바꾸지 않는다")
        SA.assertOneAudit("TAX_INVOICE_CORRECTION", orig.id, after.single { it.text("kind") == "ISSUE" && it.text("status") == "ISSUED" }.id.toString(), at)
        SA.assertNoAudit("TAX_INVOICE_CORRECTION", cancel.id)
    }

    @Test
    fun `§10_3·§2_3 동시 정정 - 같은 원본에 5건이면 200 1건·나머지 409, CANCEL 1행·새 ISSUE 1행·감사 1행`() {
        val (issued, _) = issue(listOf(50_000))
        val orig = issued.single().invoice
        val at = Flow.advance(HOUR)
        // 원본 UPDATE(정정의 첫 쓰기) 직전에 0.5초 멈춰 나머지가 원본 행 잠금을 기다리게 한다
        val win = SA.assertOneWins(SlowTrigger.on("tax_invoices", "UPDATE") { SA.concurrently(5) { i -> SA.correct(orig.id, 1_000L * (i + 1)) } }, 200)
        val supply = win.json.get("reissued").longAt("supplyAmount")
        assertCorrected(orig, supply, at)
        assertEquals(3, SA.invoicesOfSettlement(issued.single().settlementId).size)
    }
}
