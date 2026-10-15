package com.ccle.behavior.contract

import com.ccle.behavior.support.Db
import com.ccle.behavior.support.OrderItemRow
import com.ccle.behavior.support.OrderRow
import com.ccle.behavior.support.Resp
import com.ccle.behavior.support.longAt
import com.ccle.behavior.support.textAt
import kotlin.test.assertEquals

/**
 * ADR-0003 §8 금액 예시 표(BRONZE 1%) — 테스트 기대값의 정본. 값은 표에서 그대로 옮겼다(공식으로 다시 계산하지 않는다).
 */
data class ItemAmounts(
    val price: Long, val buyerFee: Long, val cardShare: Long, val pointShare: Long, val shippingShare: Long, val earn: Long,
)

data class OrderAmounts(
    val itemTotal: Long, val buyerFeeTotal: Long, val shippingFee: Long, val total: Long,
    val pointUsed: Long, val cardAmount: Long, val items: List<ItemAmounts>,
)

object Table8 {
    // | A | 120,000 · 45,000 | 3,600 · 1,350 | 0 | 169,950 | 5,000 | 164,950 | 119,964 · 44,986 | 3,636 · 1,364 | 1,164 · 436 |
    val A = OrderAmounts(
        itemTotal = 165_000, buyerFeeTotal = 4_950, shippingFee = 0, total = 169_950, pointUsed = 5_000, cardAmount = 164_950,
        items = listOf(
            ItemAmounts(price = 120_000, buyerFee = 3_600, cardShare = 119_964, pointShare = 3_636, shippingShare = 0, earn = 1_164),
            ItemAmounts(price = 45_000, buyerFee = 1_350, cardShare = 44_986, pointShare = 1_364, shippingShare = 0, earn = 436),
        ),
    )

    // | B | 30,000 | 1,000(최소) | 3,000 | 34,000 | 1,000 | 33,000 | 33,000 | 1,000 | 291 |
    val B = OrderAmounts(
        itemTotal = 30_000, buyerFeeTotal = 1_000, shippingFee = 3_000, total = 34_000, pointUsed = 1_000, cardAmount = 33_000,
        items = listOf(ItemAmounts(price = 30_000, buyerFee = 1_000, cardShare = 33_000, pointShare = 1_000, shippingShare = 3_000, earn = 291)),
    )

    // | C | 99,000 | 2,970 | 3,000 | 104,970 | 0 | 104,970 | 104,970 | 0 | 990 |
    val C = OrderAmounts(
        itemTotal = 99_000, buyerFeeTotal = 2_970, shippingFee = 3_000, total = 104_970, pointUsed = 0, cardAmount = 104_970,
        items = listOf(ItemAmounts(price = 99_000, buyerFee = 2_970, cardShare = 104_970, pointShare = 0, shippingShare = 3_000, earn = 990)),
    )

    // | D | 100,000 | 3,000 | 0 | 103,000 | 0 | 103,000 | 103,000 | 0 | 1,000 |
    val D = OrderAmounts(
        itemTotal = 100_000, buyerFeeTotal = 3_000, shippingFee = 0, total = 103_000, pointUsed = 0, cardAmount = 103_000,
        items = listOf(ItemAmounts(price = 100_000, buyerFee = 3_000, cardShare = 103_000, pointShare = 0, shippingShare = 0, earn = 1_000)),
    )

    /** "총액 34,000이면 최대 17,000P" (§8 포인트 상한 예). */
    const val B_POINT_MAX = 17_000L
}

/** §5.1 응답 본문의 금액·항목을 표와 대조한다. tradeIds는 항목 순서(오름차순)대로. */
fun assertOrderBody(r: Resp, exp: OrderAmounts, tradeIds: List<Long>, status: String, itemStatus: String) {
    assertEquals(status, r.text("status"), "주문 상태: $r")
    assertEquals(exp.itemTotal, r.long("itemTotal"), "itemTotal: $r")
    assertEquals(exp.buyerFeeTotal, r.long("buyerFeeTotal"), "buyerFeeTotal: $r")
    assertEquals(exp.shippingFee, r.long("shippingFee"), "shippingFee: $r")
    assertEquals(exp.pointUsed, r.long("pointUsed"), "pointUsed: $r")
    assertEquals(exp.cardAmount, r.long("cardAmount"), "cardAmount: $r")
    val items = r.json.get("items")
    assertEquals(exp.items.size, items?.size() ?: -1, "items 수: $r")
    exp.items.forEachIndexed { i, e ->
        val it = items[i]
        assertEquals(tradeIds[i], it.longAt("tradeId"), "items[$i].tradeId (tradeId 오름차순): $r")
        assertEquals(itemStatus, it.textAt("status"), "items[$i].status")
        assertEquals(e.price, it.longAt("price"), "items[$i].price")
        assertEquals(e.buyerFee, it.longAt("buyerFee"), "items[$i].buyerFee")
        assertEquals(e.cardShare, it.longAt("cardShare"), "items[$i].cardShare")
        assertEquals(e.pointShare, it.longAt("pointShare"), "items[$i].pointShare")
        assertEquals(e.shippingShare, it.longAt("shippingShare"), "items[$i].shippingShare")
        it.longAt("orderItemId")
    }
}

/** DB 주문·항목 행 금액을 표와 대조한다. 항목은 order_items.id 순서 = tradeId 오름차순. */
fun assertOrderRows(order: OrderRow, items: List<OrderItemRow>, exp: OrderAmounts, tradeIds: List<Long>) {
    assertEquals(exp.itemTotal, order.itemTotal)
    assertEquals(exp.buyerFeeTotal, order.buyerFeeTotal)
    assertEquals(exp.shippingFee, order.shippingFee)
    assertEquals(exp.pointUsed, order.pointUsed)
    assertEquals(exp.cardAmount, order.cardAmount)
    assertEquals(tradeIds, items.map { it.tradeId }, "order_items.id 순서 = tradeId 오름차순")
    exp.items.forEachIndexed { i, e ->
        val it = items[i]
        assertEquals(e.price, it.price, "item[$i].price")
        assertEquals(e.buyerFee, it.buyerFee, "item[$i].buyer_fee")
        assertEquals(e.cardShare, it.cardShare, "item[$i].card_share")
        assertEquals(e.pointShare, it.pointShare, "item[$i].point_share")
        assertEquals(e.shippingShare, it.shippingShare, "item[$i].shipping_share")
    }
}

/** 응답의 orderItemId가 DB 항목 id와 같은 순서인지. */
fun assertItemIds(r: Resp, items: List<OrderItemRow>) {
    assertEquals(items.map { it.id }, r.json.get("items").map { it.longAt("orderItemId") })
}

/** 표의 금액으로 체결을 만든다(같은 사이즈, 앞 가격의 체결 id가 작다). */
fun tradesFor(buyer: Long, exp: OrderAmounts): List<Long> {
    val size = Db.createProductSize()
    return exp.items.map { com.ccle.behavior.support.Flow.trade(buyer, Db.createUser(), size, it.price) }
}
