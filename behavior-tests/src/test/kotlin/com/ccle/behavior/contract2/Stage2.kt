package com.ccle.behavior.contract2

import com.ccle.behavior.contract.ItemAmounts
import com.ccle.behavior.contract.OrderAmounts
import com.ccle.behavior.support.Api
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db2
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.ItemRow
import com.ccle.behavior.support.PgCancel
import com.ccle.behavior.support.RefundRow
import com.ccle.behavior.support.Resp
import com.ccle.behavior.support.longAt
import com.ccle.behavior.support.textAt
import com.fasterxml.jackson.databind.JsonNode
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

val SEC: Duration = Duration.ofSeconds(1)
val HOUR: Duration = Duration.ofHours(1)
val DAY: Duration = Duration.ofDays(1)
fun days(n: Long): Duration = Duration.ofDays(n)

/** KST 달력 계산(ADR-0004 §1: 정산 주·월 경계·date 열은 Asia/Seoul). 기대값은 현재 시계에서 계산한다. */
object Kst {
    val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    fun date(t: Instant): LocalDate = t.atZone(ZONE).toLocalDate()
    fun at(d: LocalDate): Instant = d.atStartOfDay(ZONE).toInstant()

    /** t가 속한 정산 주의 월요일(§10.1 period_start). */
    fun weekStart(t: Instant): LocalDate = date(t).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** t가 속한 주의 마감 = 다음 월요일 00:00 KST(항상 t보다 뒤). */
    fun cutoffOf(t: Instant): Instant = at(weekStart(t).plusDays(7))

    /** 지급 시각 = period_start + 9일 00:00 KST(수요일, §10.2). */
    fun payday(periodStart: LocalDate): Instant = at(periodStart.plusDays(9))

    fun monthStart(t: Instant): LocalDate = date(t).withDayOfMonth(1)

    /** t 뒤 첫 달 1일(KST). */
    fun nextMonthStart(t: Instant): LocalDate = monthStart(t).plusMonths(1)
}

/** 결제까지 끝낸 주문 1건(1단계 경로). items·trades·sellers는 항목 순서(= tradeId 오름차순). */
data class Purchase(
    val buyer: Long, val orderId: Long, val key: String,
    val items: List<Long>, val trades: List<Long>, val sellers: List<Long>, val paidAt: Instant,
) {
    val item: Long get() = items.single()
    val seller: Long get() = sellers.single()
}

/** 주문 항목 1개의 준비값: 체결가, 카테고리(null = 첫 카테고리 SHOES), 판매자(null = 새 사용자). */
data class Line(val price: Long, val category: String? = null, val seller: Long? = null)

/** 2단계 엔드포인트(ADR-0004)와 준비 단계 도우미. */
object S2 {
    // ---- 엔드포인트 ----
    fun item(id: Long, user: Any?): Resp = Api.get("/order-items/$id", user)
    fun ship(id: Long, seller: Any?): Resp = Api.post("/order-items/$id/shipment", null, seller)
    fun receive(id: Long): Resp = Api.post("/admin/order-items/$id/receipt", null)
    fun inspect(id: Long, result: String?, reason: String? = null): Resp =
        Api.post("/admin/order-items/$id/inspection", mapOf("result" to result, "reason" to reason))
    fun deliver(id: Long): Resp = Api.post("/admin/order-items/$id/delivery", null)
    fun confirm(id: Long, buyer: Any?): Resp = Api.post("/order-items/$id/confirm", null, buyer)
    fun cancel(id: Long, buyer: Any?): Resp = Api.post("/order-items/$id/cancel", null, buyer)
    fun dispute(id: Long, buyer: Any?): Resp = Api.post("/order-items/$id/disputes", null, buyer)
    fun resolve(disputeId: Long, accept: Boolean): Resp = Api.post("/admin/disputes/$disputeId/resolution", mapOf("accept" to accept))
    fun settlements(user: Any?): Resp = Api.get("/me/settlements", user)

    /** 검증 대상인 2단계 배치 실행(§5). */
    fun runBatch(name: String): Resp {
        val r = Flow.batch(name)
        assertEquals(200, r.status, "2단계 배치 '$name': $r")
        assertTrue(r.json.get("processed")?.isIntegralNumber == true, "processed 정수 아님: $r")
        return r
    }

    // ---- 준비 ----
    /** 준비 단계의 2단계 호출. 실패 메시지는 "준비(2단계)"로 시작한다(실패 원인 분류용). */
    fun ready(step: String, r: Resp, expected: Int = 200): Resp {
        assertEquals(expected, r.status, "준비(2단계) $step: $r")
        return r
    }

    fun readyBatch(name: String): Resp = ready("배치 $name", Flow.batch(name))

    fun toShipped(item: Long, seller: Long) { ready("발송", ship(item, seller)) }
    fun toInspecting(item: Long, seller: Long) { toShipped(item, seller); ready("입고", receive(item)) }
    fun toDelivering(item: Long, seller: Long) { toInspecting(item, seller); ready("검수 합격", inspect(item, "PASS")) }
    fun toDelivered(item: Long, seller: Long) { toDelivering(item, seller); ready("배송 완료", deliver(item)) }
    fun toConfirmed(item: Long, seller: Long, buyer: Long) { toDelivered(item, seller); ready("구매 확정", confirm(item, buyer)) }
    fun toSellerFault(item: Long, seller: Long, reason: String = "오염") { toInspecting(item, seller); ready("검수 불합격", inspect(item, "FAIL", reason)) }
    fun openDispute(item: Long, buyer: Long): Long = ready("분쟁 제기", dispute(item, buyer), 201).long("disputeId")
    fun resolved(disputeId: Long, accept: Boolean) { ready("분쟁 판정", resolve(disputeId, accept)) }

    /** 체결·결제(1단계 경로)로 PAID 주문 1건. 항목마다 사이즈를 새로 만든다. */
    fun purchase(lines: List<Line>, buyer: Long = Db.createUser(), points: Long = 0, key: String = Flow.newKey()): Purchase {
        val sellers = lines.map { it.seller ?: Db.createUser() }
        val trades = lines.mapIndexed { i, l -> Flow.trade(buyer, sellers[i], Db.createProductSize(l.category), l.price) }
        val r = Flow.pay(buyer, trades, points, key).assertStatus(201)
        assertEquals("PAID", r.text("status"), "준비(1단계) 결제 승인: $r")
        val orderId = r.long("orderId")
        val items = Db.orderItems(orderId)
        assertEquals(trades, items.map { it.tradeId }, "준비(1단계) 항목 순서")
        return Purchase(buyer, orderId, key, items.map { it.id }, trades, sellers, Db.order(orderId).paidAt!!)
    }

    fun purchase(vararg prices: Long): Purchase = purchase(prices.map { Line(it) })

    /** 예 A 주문(ADR-0003 §8 A, 포인트 5,000). 묶음은 호출자가 미리 만든다. */
    fun orderA(buyer: Long): Purchase {
        val p = S2.purchase(listOf(Line(120_000), Line(45_000)), buyer, points = 5_000)
        com.ccle.behavior.contract.assertOrderRows(Db.order(p.orderId), Db.orderItems(p.orderId), com.ccle.behavior.contract.Table8.A, p.trades)
        return p
    }

    /** 예 E 주문(§12: 30,000 · 20,000, 포인트 5,000). */
    fun orderE(buyer: Long): Purchase {
        val p = S2.purchase(listOf(Line(30_000), Line(20_000)), buyer, points = 5_000)
        com.ccle.behavior.contract.assertOrderRows(Db.order(p.orderId), Db.orderItems(p.orderId), Table12.E, p.trades)
        Table12.E.items.forEachIndexed { i, e -> assertEquals(e.earn, Db.earnOfItem(p.items[i]).single().amount, "준비(1단계) E 적립[$i]") }
        return p
    }
}

// ---- 응답 단언 ----
val ITEM_FIELDS = listOf(
    "orderItemId", "orderId", "tradeId", "status", "price", "buyerFee", "cardShare", "pointShare", "shippingShare",
    "paidAt", "shippedAt", "inspectedAt", "deliveredAt", "confirmedAt", "cancelledAt", "refund",
)

/** 필드가 있어야 하고 null이거나 오프셋 포함 ISO-8601 시각. */
fun JsonNode.instantOrNull(field: String): Instant? {
    val n = get(field)
    assertTrue(n != null, "필드 '$field' 없음: $this")
    return if (n.isNull) null else OffsetDateTime.parse(n.asText()).toInstant()
}

/** §2.2 주문 항목 응답 = 그 항목의 DB 행(+ 환불 행). 모든 필드가 있어야 한다. 반환: DB 행. */
fun assertItemBody(r: Resp, id: Long): ItemRow {
    val row = Db2.item(id)
    val j = r.json
    for (f in ITEM_FIELDS) assertTrue(j.has(f), "응답 필드 '$f' 없음: $r")
    assertEquals(row.id, j.longAt("orderItemId"), "orderItemId: $r")
    assertEquals(row.orderId, j.longAt("orderId"), "orderId: $r")
    assertEquals(row.tradeId, j.longAt("tradeId"), "tradeId: $r")
    assertEquals(row.status, j.textAt("status"), "status = DB: $r")
    assertEquals(row.price, j.longAt("price"), "price: $r")
    assertEquals(row.buyerFee, j.longAt("buyerFee"), "buyerFee: $r")
    assertEquals(row.cardShare, j.longAt("cardShare"), "cardShare: $r")
    assertEquals(row.pointShare, j.longAt("pointShare"), "pointShare: $r")
    assertEquals(row.shippingShare, j.longAt("shippingShare"), "shippingShare: $r")
    assertEquals(row.paidAt, j.instantOrNull("paidAt"), "paidAt: $r")
    assertEquals(row.shippedAt, j.instantOrNull("shippedAt"), "shippedAt: $r")
    assertEquals(row.inspectedAt, j.instantOrNull("inspectedAt"), "inspectedAt: $r")
    assertEquals(row.deliveredAt, j.instantOrNull("deliveredAt"), "deliveredAt: $r")
    assertEquals(row.confirmedAt, j.instantOrNull("confirmedAt"), "confirmedAt: $r")
    assertEquals(row.cancelledAt, j.instantOrNull("cancelledAt"), "cancelledAt: $r")
    val refunds = Db2.refunds(id)
    assertTrue(refunds.size <= 1, "항목당 환불 행은 최대 1: $refunds")
    val rn = j.get("refund")
    val refund = refunds.firstOrNull()
    if (refund == null) {
        assertTrue(rn.isNull, "환불 없으면 refund null: $r")
    } else {
        assertTrue(rn.isObject, "refund 객체: $r")
        assertEquals(refund.cardAmount, rn.longAt("cardAmount"), "refund.cardAmount: $r")
        assertEquals(refund.pointAmount, rn.longAt("pointAmount"), "refund.pointAmount: $r")
        assertEquals(refund.buyerFeeAmount, rn.longAt("buyerFeeAmount"), "refund.buyerFeeAmount: $r")
        assertEquals(refund.shippingAmount, rn.longAt("shippingAmount"), "refund.shippingAmount: $r")
        assertEquals(refund.fault, rn.textAt("fault"), "refund.fault: $r")
    }
    return row
}

/** 성공 응답(200, §2.2 모양)이고 항목 상태가 expected. */
fun assertItemOk(r: Resp, id: Long, expected: String): ItemRow {
    r.assertStatus(200)
    val row = assertItemBody(r, id)
    assertEquals(expected, row.status, "항목 상태: $r")
    return row
}

// ---- 환불·카드 취소 단언 (§7) ----
data class RefundExp(val card: Long, val point: Long, val buyerFee: Long, val shipping: Long, val reason: String, val fault: String)

fun assertRefund(itemId: Long, exp: RefundExp, at: Instant): RefundRow {
    val rf = Db2.refunds(itemId).single()
    assertEquals(exp.card, rf.cardAmount, "refunds.card_amount")
    assertEquals(exp.point, rf.pointAmount, "refunds.point_amount")
    assertEquals(exp.buyerFee, rf.buyerFeeAmount, "refunds.buyer_fee_amount")
    assertEquals(exp.shipping, rf.shippingAmount, "refunds.shipping_amount")
    assertEquals(exp.reason, rf.reason, "refunds.reason")
    assertEquals(exp.fault, rf.fault, "refunds.fault")
    assertEquals(at, rf.createdAt, "refunds.created_at = 현재")
    return rf
}

/** 카드 취소: PG가 받은 요청 1건(키 cancel-<id>, 승인 키 = 주문 멱등 키, 금액) + pg_transactions CANCEL 1행. */
fun assertCardCancel(p: Purchase, itemId: Long, amount: Long, at: Instant) {
    val pg = PgCancel.info(itemId)
    assertEquals(1, pg.requests, "PG 취소 요청 수: $pg")
    assertEquals(listOf(amount), pg.amounts, "PG 취소 amount = 카드 환불: $pg")
    assertEquals(listOf(p.key), pg.approvalKeys, "PG 취소 approvalKey = 주문 멱등 키: $pg")
    assertEquals("APPROVED", pg.status, "PG 취소 기록: $pg")
    val rows = Db2.pgCancels(p.orderId).filter { it.idempotencyKey == PgCancel.key(itemId) }
    val tx = rows.single()
    assertEquals(amount, tx.amount, "CANCEL amount = 카드 환불")
    assertEquals("APPROVED", tx.status)
    assertEquals(pg.pgTxId, tx.pgTxId, "pg_tx_id = PG 값")
    assertEquals(at, tx.requestedAt, "CANCEL requested_at = 현재")
    assertEquals(at, tx.respondedAt, "CANCEL responded_at = 현재")
}

/** 카드 취소도 환불도 없다(PG 실패·상태 위반 뒤 무변경). */
fun assertNoRefund(p: Purchase, itemId: Long) {
    assertTrue(Db2.refunds(itemId).isEmpty(), "환불 행 없음")
    assertTrue(Db2.pgCancels(p.orderId).none { it.idempotencyKey == PgCancel.key(itemId) }, "CANCEL 행 없음")
    assertNull(Db2.item(itemId).cancelledAt, "cancelled_at NULL")
}

/** 포인트 묶음의 배분을 (묶음 → 양)으로. */
fun allocMap(entryId: Long): Map<Long, Long> = Db.allocations(entryId).associate { it.earnEntryId to it.amount }

// ---- ADR-0004 §12 금액 예시(정본, BRONZE 1%) — 표에서 그대로 옮겼다 ----
object Table12 {
    /** 주문 E(§12 환불 표 E행·E 계산): 30,000 · 20,000, 수수료 1,000 · 1,000, 배송비 3,000, 총액 55,000, 포인트 5,000, 카드 50,000. */
    val E = OrderAmounts(
        itemTotal = 50_000, buyerFeeTotal = 2_000, shippingFee = 3_000, total = 55_000, pointUsed = 5_000, cardAmount = 50_000,
        items = listOf(
            ItemAmounts(price = 30_000, buyerFee = 1_000, cardShare = 30_910, pointShare = 3_090, shippingShare = 3_000, earn = 272),
            ItemAmounts(price = 20_000, buyerFee = 1_000, cardShare = 19_090, pointShare = 1_910, shippingShare = 0, earn = 181),
        ),
    )

    // | A1 | 2번 항목 구매자 취소 | 44,986 | 1,364 | 0 | PARTIALLY_CANCELLED |
    val A1 = RefundExp(card = 44_986, point = 1_364, buyerFee = 1_350, shipping = 0, reason = "구매자 취소", fault = "BUYER")
    // | A2 | 이어서 1번 항목 구매자 취소(마지막) | 119,964 | 3,636 | 0 | CANCELLED (Σ카드 164,950 · Σ포인트 5,000) |
    val A2 = RefundExp(card = 119_964, point = 3_636, buyerFee = 3_600, shipping = 0, reason = "구매자 취소", fault = "BUYER")
    // | E1 | 1번 항목 구매자 취소 | 28,183 | 2,817 | 0 | PARTIALLY_CANCELLED |  (buyer_fee_amount E1 = 1,000)
    val E1 = RefundExp(card = 28_183, point = 2_817, buyerFee = 1_000, shipping = 0, reason = "구매자 취소", fault = "BUYER")
    // | E2 | 이어서 2번 항목 구매자 취소(마지막 — 배송비 실림) | 21,817 | 2,183 | 3,000 | CANCELLED (Σ카드 50,000 · Σ포인트 5,000) |
    val E2 = RefundExp(card = 21_817, point = 2_183, buyerFee = 1_000, shipping = 3_000, reason = "구매자 취소", fault = "BUYER")
    // | E3 | 1번 항목 검수 불합격(판매자 귀책) | 30,910 | 3,090 | 3,000 | PARTIALLY_CANCELLED, 패널티 3,000 |
    fun e3(reason: String) = RefundExp(card = 30_910, point = 3_090, buyerFee = 1_000, shipping = 3_000, reason = reason, fault = "SELLER")
    const val E3_PENALTY = 3_000L
    // | E4 | 2번 항목 구매자 취소(마지막, 배송비는 이미 환불) | 19,090 | 1,910 | 0 | CANCELLED |
    val E4 = RefundExp(card = 19_090, point = 1_910, buyerFee = 1_000, shipping = 0, reason = "구매자 취소", fault = "BUYER")

    /** 정산 표 1행: gross·fee_total·penalty·carry_in·net·payout·carry_out·상태. */
    data class Settle(
        val gross: Long, val feeTotal: Long, val penalty: Long, val carryIn: Long,
        val net: Long, val payout: Long, val carryOut: Long, val status: String,
    )

    // | S1 | SHOES 120,000 + APPAREL 45,000 확정 | 165,000 | 10,800 | 0 | 0 | 154,200 | 154,200 | 0 | PAID |
    val S1 = Settle(165_000, 10_800, 0, 0, 154_200, 154_200, 0, "PAID")
    // | S2 | 확정 없음, 패널티 3,000 | 0 | 0 | 3,000 | 0 | −3,000 | 0 | −3,000 | CARRIED |
    val S2 = Settle(0, 0, 3_000, 0, -3_000, 0, -3_000, "CARRIED")
    // | S3 | S2 다음 주, TECH 30,000 확정 | 30,000 | 1,200 | 0 | −3,000 | 25,800 | 25,800 | 0 | PAID |
    val S3 = Settle(30_000, 1_200, 0, -3_000, 25_800, 25_800, 0, "PAID")
    // | S4 | S1의 SHOES 120,000이 정산 뒤 분쟁 인정, 그 주 다른 확정 없음 | −120,000 | −7,200 | 0 | 0 | −112,800 | 0 | −112,800 | CARRIED |
    val S4 = Settle(-120_000, -7_200, 0, 0, -112_800, 0, -112_800, "CARRIED")
}

/** 정산 행이 표의 값과 같다. 상태·지급: PAID면 paid_at = 그 주 수요일 00:00 KST, CARRIED면 NULL. */
fun assertSettlement(row: com.ccle.behavior.support.SettlementRow, exp: Table12.Settle, periodStart: LocalDate) {
    assertEquals(periodStart, row.periodStart, "period_start = 그 주 월요일")
    assertEquals(periodStart.plusDays(6), row.periodEnd, "period_end = period_start + 6")
    assertEquals(exp.gross, row.gross, "gross")
    assertEquals(exp.feeTotal, row.feeTotal, "fee_total")
    assertEquals(exp.penalty, row.penaltyTotal, "penalty_total")
    assertEquals(exp.carryIn, row.carryIn, "carry_in")
    assertEquals(exp.net, row.net, "net")
    assertEquals(exp.payout, row.payout, "payout")
    assertEquals(exp.carryOut, row.carryOut, "carry_out")
    assertEquals(exp.status, row.status, "status")
    if (exp.status == "PAID") assertEquals(Kst.payday(periodStart), row.paidAt, "paid_at = period_start + 9일 00:00 KST")
    else assertNull(row.paidAt, "CARRIED paid_at NULL")
}
