package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.HOUR
import com.ccle.behavior.contract2.Kst
import com.ccle.behavior.contract2.S2
import com.ccle.behavior.contract2.SEC
import com.ccle.behavior.contract2.days
import com.ccle.behavior.support.Contract3Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db2
import com.ccle.behavior.support.FailTrigger
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.failed
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-0007 §10.1 행 모양 · §10.2 tax-invoice-issue(PAID·paid_at ≤ 현재 → ISSUE, CARRIED → HOLD, 재실행·격리) · §10.4 GET /me/tax-invoices.
 * 정산은 weekly-settlement(ADR-0004 §10)를 API로 돌려 만든다: PAID의 paid_at = 마감 + 2일 00:00 KST(수요일).
 * 수수료율: SHOES 6%·APPAREL 8%·BAGS 10%·TECH 4%.
 */
@Contract3Test
class A7TaxInvoiceIssueTest {

    @Test
    fun `§10_2 발행 - CARRIED는 마감 직후 HOLD(0·0·0), PAID는 paid_at 1초 전 미발행·정각 발행(ISSUE·ISSUED, vat floor 10%), 재실행 무변경, 조회`() {
        val paid = Db.createUser()
        SA.confirmedSale(paid, 120_000, "SHOES")
        SA.confirmedSale(paid, 45_000, "APPAREL")                  // 수수료 7,200 + 3,600 = 10,800
        val odd = Db.createUser()
        SA.confirmedSale(odd, 30_875, "TECH")                      // 수수료 1,235 → vat floor(123.5) = 123
        val carried = Db.createUser()
        SA.sellerFault(carried, 30_000)                            // 패널티만 → CARRIED

        val c = Flow.moveTo(Kst.cutoffOf(Flow.now()))
        S2.runBatch("weekly-settlement")
        val sPaid = Db2.settlementsOfSeller(paid).single()
        val sOdd = Db2.settlementsOfSeller(odd).single()
        val sCarried = Db2.settlementsOfSeller(carried).single()
        assertEquals(10_800L, sPaid.feeTotal, "준비")
        assertEquals(1_235L, sOdd.feeTotal, "준비")
        assertEquals("CARRIED", sCarried.status, "준비")
        val payday = sPaid.paidAt!!
        assertEquals(c.plus(days(2)), payday, "준비: 지급일 = 마감 + 2일")

        S2.runBatch("tax-invoice-issue")
        SA.assertInvoiceRow(SA.invoicesOfSettlement(sCarried.id).single(), "HOLD", "DONE", 0, 0, 0, null, c, sCarried.id, carried)
        assertTrue(SA.invoicesOfSettlement(sPaid.id).isEmpty(), "지급일 전 PAID는 발행하지 않는다")
        assertTrue(SA.invoicesOfSettlement(sOdd.id).isEmpty())

        Flow.moveTo(payday.minus(SEC))
        S2.runBatch("tax-invoice-issue")
        assertTrue(SA.invoicesOfSettlement(sPaid.id).isEmpty(), "paid_at > 현재")

        Flow.moveTo(payday)
        S2.runBatch("tax-invoice-issue")
        SA.assertInvoiceRow(SA.invoicesOfSettlement(sPaid.id).single(), "ISSUE", "ISSUED", 10_800, 1_080, 11_880, null, payday, sPaid.id, paid)
        SA.assertInvoiceRow(SA.invoicesOfSettlement(sOdd.id).single(), "ISSUE", "ISSUED", 1_235, 123, 1_358, null, payday, sOdd.id, odd)
        SA.assertInvoiceRow(SA.invoicesOfSettlement(sCarried.id).single(), "HOLD", "DONE", 0, 0, 0, null, c)

        Flow.advance(HOUR)
        S2.runBatch("tax-invoice-issue")
        for (s in listOf(sPaid, sOdd, sCarried)) assertEquals(1, SA.invoicesOfSettlement(s.id).size, "계산서가 있는 정산은 대상이 아니다")

        // §10.4 조회
        for (seller in listOf(paid, carried)) {
            val r = SA.taxInvoices(seller).assertStatus(200)
            val rows = Db2.settlementsOfSeller(seller).flatMap { SA.invoicesOfSettlement(it.id) }.sortedBy { it.id }
            assertEquals(rows.size, r.json.size(), "목록: $r")
            rows.forEachIndexed { i, row -> SA.assertInvoiceBody(r.json[i], row) }
        }
    }

    @Test
    fun `§10_1 공급가액 = max(fee_total, 0) - CLAWBACK으로 fee_total이 음수인 PAID 정산은 ISSUE 0·0·0`() {
        val seller = Db.createUser()
        val bags = SA.confirmedSale(seller, 200_000, "BAGS")       // 1주차 INCLUDED 수수료 20,000
        val c1 = Flow.moveTo(Kst.cutoffOf(Flow.now()))
        S2.runBatch("weekly-settlement")
        S2.resolved(S2.openDispute(bags.item, bags.buyer), true)    // 2주차 CLAWBACK −200,000 / −20,000
        SA.confirmedSale(seller, 300_000, "TECH")                  // 2주차 INCLUDED 300,000 / 12,000
        Flow.moveTo(c1.plus(days(7)))
        S2.runBatch("weekly-settlement")
        val (s1, s2) = Db2.settlementsOfSeller(seller).also { assertEquals(2, it.size, "준비") }
        assertEquals(20_000L, s1.feeTotal, "준비")
        assertEquals(-8_000L, s2.feeTotal, "준비: fee_total 음수")
        assertEquals("PAID", s2.status, "준비: net 108,000 → PAID")

        val now = Flow.moveTo(s2.paidAt!!)
        S2.runBatch("tax-invoice-issue")
        SA.assertInvoiceRow(SA.invoicesOfSettlement(s1.id).single(), "ISSUE", "ISSUED", 20_000, 2_000, 22_000, null, now, s1.id, seller)
        SA.assertInvoiceRow(SA.invoicesOfSettlement(s2.id).single(), "ISSUE", "ISSUED", 0, 0, 0, null, now, s2.id, seller)
    }

    @Test
    fun `§10_2·§2_6 격리 - 정산 A의 INSERT가 실패해도 B는 HOLD, failed ≥ 1, 다시 돌리면 A HOLD`() {
        val a = Db.createUser()
        val b = Db.createUser()
        SA.sellerFault(a, 30_000)
        SA.sellerFault(b, 30_000)
        val c = Flow.moveTo(Kst.cutoffOf(Flow.now()))
        S2.runBatch("weekly-settlement")
        val sa = Db2.settlementsOfSeller(a).single()
        val sb = Db2.settlementsOfSeller(b).single()

        FailTrigger.on("tax_invoices", "INSERT", "NEW.settlement_id = ${sa.id}") {
            val r = S2.runBatch("tax-invoice-issue")
            assertTrue(r.failed() >= 1, "A 실패를 failed에 센다: $r")
            assertTrue(r.long("processed") >= 1, "B는 커밋된다: $r")
        }
        assertTrue(SA.invoicesOfSettlement(sa.id).isEmpty())
        SA.assertInvoiceRow(SA.invoicesOfSettlement(sb.id).single(), "HOLD", "DONE", 0, 0, 0, null, c)

        val later = Flow.advance(HOUR)
        S2.runBatch("tax-invoice-issue")
        SA.assertInvoiceRow(SA.invoicesOfSettlement(sa.id).single(), "HOLD", "DONE", 0, 0, 0, null, later, sa.id, a)
        assertEquals(1, SA.invoicesOfSettlement(sb.id).size)
    }

    @Test
    fun `§10_4 GET me tax-invoices - 없으면 빈 배열, 401`() {
        val r = SA.taxInvoices(Db.createUser()).assertStatus(200)
        assertTrue(r.json.isArray && r.json.size() == 0, "빈 배열: $r")
        SA.taxInvoices(null).assertError(401, "UNKNOWN_USER")
        SA.taxInvoices("abc").assertError(401, "UNKNOWN_USER")
        SA.taxInvoices(Db.missingId("users")).assertError(401, "UNKNOWN_USER")
    }
}
