package com.ccle.layered.domain

import java.time.Instant

/**
 * 환불 사유(ADR-0004 §7). 이름이 refunds.fault 값이다. itemStatus = 환불 뒤 항목 상태, fromStatuses = 그 사유로 환불할 수 있는 상태.
 * 판매자 귀책은 발송 기한 초과(PAID)와 검수 불합격(INSPECTING) 두 경로다.
 */
enum class RefundFault(val itemStatus: String, val fromStatuses: Set<String>) {
    BUYER("CANCELLED_BY_BUYER", setOf("PAID")),
    SELLER("CANCELLED_SELLER_FAULT", setOf("PAID", "INSPECTING")),
    DISPUTE("REFUNDED_AFTER_CONFIRM", setOf("DISPUTED")),
    ;

    companion object {
        /** 환불된 항목의 상태들. */
        val itemStatuses: Set<String> = entries.map { it.itemStatus }.toSet()
    }
}

/**
 * 항목 환불 금액(ADR-0004 §7.1). s = 배송비(첫 항목 = id가 가장 작은 항목에 귀속), s_c = floor(s × c_1 / b_1), s_p = s − s_c.
 * 구매자 취소는 그 항목의 몫에서 (첫 항목이면) 배송비 부분을 빼고, 판매자 귀책·분쟁 인정은 몫 전부(첫 항목이면 배송비 s 포함).
 * 이 환불로 주문의 모든 항목이 환불되고(last) 배송비가 아직 환불되지 않았으면 s_c·s_p를 더하고 배송비 s를 싣는다.
 */
class RefundAmounts private constructor(
    val fault: RefundFault,
    val card: Long,
    val point: Long,
    val shipping: Long,
    val last: Boolean,
) {
    /** refunds 1행. buyer_fee_amount = 그 항목의 구매 수수료(모든 사유). */
    fun refund(item: OrderItem, reason: String, now: Instant) = Refund(
        orderItemId = item.id!!,
        cardAmount = card,
        pointAmount = point,
        buyerFeeAmount = item.buyerFee,
        shippingAmount = shipping,
        reason = reason,
        fault = fault.name,
        createdAt = now,
    )

    companion object {
        /**
         * items = 그 주문의 항목 id 순. shippingRefunded = 그 주문의 환불 행에 이미 실린 배송비 합 — 마지막 환불이면서
         * 이 환불에 배송비가 없고 배송비가 있는 주문일 때만 읽는다.
         */
        fun of(order: PurchaseOrder, items: List<OrderItem>, item: OrderItem, fault: RefundFault, shippingRefunded: () -> Long): RefundAmounts {
            val first = items.first()
            val isFirst = first.id == item.id
            val shippingFee = order.shippingFee
            val shipCard = mulDiv(shippingFee, first.cardShare, first.price + first.buyerFee + first.shippingShare)
            val shipPoint = shippingFee - shipCard
            val keepsShipping = isFirst && fault == RefundFault.BUYER
            var card = item.cardShare - (if (keepsShipping) shipCard else 0L)
            var point = item.pointShare - (if (keepsShipping) shipPoint else 0L)
            var shipping = if (isFirst && !keepsShipping) shippingFee else 0L
            val last = items.count { it.id != item.id && it.isRefunded() } == items.size - 1
            if (last && shipping == 0L && shippingFee > 0 && shippingRefunded() == 0L) {
                card += shipCard
                point += shipPoint
                shipping = shippingFee
            }
            return RefundAmounts(fault, card, point, shipping, last)
        }
    }
}
