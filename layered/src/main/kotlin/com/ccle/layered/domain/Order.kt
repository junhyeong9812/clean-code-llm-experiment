package com.ccle.layered.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** 날짜 판정의 시간대(ADR-0003 §2). */
internal val KST: ZoneOffset = ZoneOffset.ofHours(9)

/** 결제 요청의 값(§5.1). Idempotency-Key 1~64자, tradeIds 1개 이상·중복 없음, pointToUse ≥ 0(생략 = 0). */
class OrderRequest private constructor(val idempotencyKey: String, val tradeIds: List<Long>, val point: Long) {
    companion object {
        fun of(idempotencyKey: String?, tradeIds: List<Long>?, pointToUse: Long?): OrderRequest {
            if (idempotencyKey == null) invalidRequest("Idempotency-Key 헤더가 필요하다")
            if (idempotencyKey.length !in 1..64) invalidRequest("Idempotency-Key는 1~64자: ${idempotencyKey.length}자")
            if (tradeIds == null || tradeIds.isEmpty()) invalidRequest("tradeIds가 1개 이상 필요하다")
            if (tradeIds.toSet().size != tradeIds.size) invalidRequest("tradeIds에 중복이 있다")
            val point = pointToUse ?: 0L
            if (point < 0) invalidRequest("pointToUse는 0 이상: $point")
            return OrderRequest(idempotencyKey, tradeIds, point)
        }
    }
}

/**
 * 결제할 체결(§5.1). 하나라도 없으면 404, 모두 있고 하나라도 남의 것이면 403, 하나라도 결제 불가(AWAITING_PAYMENT이고 현재 < 기한)면 409.
 * trades = tradeId 오름차순으로 읽은 것(= 항목 순서).
 */
class PayableTrades private constructor(val trades: List<Trade>) {

    /** 그 체결에 PAYMENT_FAILED가 아닌 주문 항목이 있으면(결제 중이거나 결제됨) 409. */
    fun requireNoLiveItems(liveItemCount: Long) {
        if (liveItemCount > 0) throw MarketException(Failure.TRADE_NOT_PAYABLE, "결제 중이거나 결제된 체결이 있다")
    }

    companion object {
        fun of(requestedIds: List<Long>, found: List<Trade>, buyerId: Long, now: Instant): PayableTrades {
            if (found.size != requestedIds.size) notFound("없는 체결이 있다: $requestedIds")
            if (found.any { it.buyerId != buyerId }) throw MarketException(Failure.NOT_OWNER, "남이 구매자인 체결이 있다")
            for (t in found) t.requirePayable(now)
            return PayableTrades(found)
        }
    }
}

/**
 * 주문 금액(ADR-0002 §5.1·§9.2, ADR-0003 §8). 항목은 tradeId 오름차순.
 * 수수료 = max(floor(체결가 × 3%), 1,000), 배송비 = 항목 합 ≥ 100,000이면 0 아니면 3,000, 총액 = 항목 합 + 수수료 합 + 배송비.
 */
class OrderAmounts(private val trades: List<Trade>, val point: Long) {
    val fees: List<Long> = trades.map { maxOf(mulDiv(it.price, 3, 100), 1_000L) }
    val itemTotal: Long = trades.fold(0L) { sum, t -> Math.addExact(sum, t.price) }
    val feeTotal: Long = fees.fold(0L) { sum, fee -> Math.addExact(sum, fee) }
    val shippingFee: Long = if (itemTotal >= 100_000) 0L else 3_000L
    val total: Long = Math.addExact(Math.addExact(itemTotal, feeTotal), shippingFee)
    val cardAmount: Long get() = total - point

    /** 포인트 규칙(§5.2, 위반 422): 0이거나, 1,000 이상·10의 배수·총액 절반 이하·사용 가능액 이하. 사용 가능액은 앞 규칙을 통과한 뒤에만 읽는다. */
    fun requirePointRule(usable: () -> Long) {
        if (point == 0L) return
        if (point < 1_000) pointRule("1,000P 이상만 쓸 수 있다: $point")
        if (point % 10 != 0L) pointRule("10P 단위로만 쓸 수 있다: $point")
        if (point > total / 2) pointRule("주문 총액의 50%(${total / 2})까지만 쓸 수 있다: $point")
        val available = usable()
        if (point > available) pointRule("사용 가능액($available)을 넘는다: $point")
    }

    /** 포인트 예약(§5.2): pointToUse > 0일 때만 USE RESERVED 행, 아니면 null(예약 없음). */
    fun pointReservation(buyerId: Long, orderId: Long, now: Instant): PointLedgerEntry? =
        if (point > 0) PointLedgerEntry.reservation(buyerId, orderId, point, now) else null

    fun order(buyerId: Long, idempotencyKey: String, now: Instant) = PurchaseOrder(
        buyerId = buyerId,
        status = "PAYING",
        itemTotal = itemTotal,
        buyerFeeTotal = feeTotal,
        shippingFee = shippingFee,
        pointUsed = point,
        cardAmount = cardAmount,
        idempotencyKey = idempotencyKey,
        createdAt = now,
    )

    /**
     * 항목(AWAITING_PAYMENT), tradeId 오름차순. 기준액 b_i = 체결가 + 수수료 (+ 배송비, 첫 항목만).
     * 카드 몫 = floor(카드 × b_i / 총액), 남은 원은 앞 항목부터 1원씩(기준액을 넘지 않게). 포인트 몫 = b_i − 카드 몫.
     */
    fun items(orderId: Long): List<OrderItem> {
        val bases = trades.indices.map { i -> trades[i].price + fees[i] + (if (i == 0) shippingFee else 0L) } // 각각 ≤ total
        val cardShares = bases.map { mulDiv(cardAmount, it, total) }.toMutableList()
        var left = cardAmount - cardShares.sum()
        while (left > 0) {
            for (i in cardShares.indices) {
                if (left > 0 && cardShares[i] < bases[i]) {
                    cardShares[i] += 1
                    left -= 1
                }
            }
        }
        return trades.indices.map { i ->
            OrderItem(
                orderId = orderId,
                tradeId = trades[i].id!!,
                price = trades[i].price,
                buyerFee = fees[i],
                cardShare = cardShares[i],
                pointShare = bases[i] - cardShares[i],
                shippingShare = if (i == 0) shippingFee else 0L,
                status = "AWAITING_PAYMENT",
            )
        }
    }

    private fun pointRule(message: String): Nothing = throw MarketException(Failure.POINT_RULE_VIOLATION, message)
}

// 클래스 이름을 Order로 하면 JPQL의 ORDER 키워드와 겹치므로 PurchaseOrder로 둔다(테이블은 orders)
@Entity
@Table(name = "orders")
class PurchaseOrder(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var buyerId: Long,
    status: String,
    var itemTotal: Long,
    var buyerFeeTotal: Long,
    var shippingFee: Long,
    var pointUsed: Long,
    var cardAmount: Long,
    var idempotencyKey: String,
    var createdAt: Instant,
    paidAt: Instant? = null,
) {
    var status: String = status
        private set

    var paidAt: Instant? = paidAt
        private set

    /** 멱등(§5.1): 같은 키의 주문은 같은 구매자·같은 tradeIds 집합·같은 pointToUse일 때만 그 주문, 아니면 409. */
    fun requireSameRequest(buyerId: Long, tradeIds: Set<Long>, request: OrderRequest) {
        if (this.buyerId != buyerId || tradeIds != request.tradeIds.toSet() || pointUsed != request.point) {
            throw MarketException(Failure.IDEMPOTENCY_KEY_REUSED, "같은 멱등 키에 다른 구매자 또는 다른 요청: $idempotencyKey")
        }
    }

    /** 등록자(구매자)만(§5.3). */
    fun requireBuyer(userId: Long) {
        if (buyerId != userId) throw MarketException(Failure.NOT_OWNER, "남의 주문: $id")
    }

    /** 적립률 등급을 정하는 날짜(ADR-0004 §8.1): 주문 생성 시각의 KST 날짜. 승인이 늦어도(pg-inquiry·기동 전환) 이 날짜다. */
    fun gradeDate(): LocalDate = LocalDate.ofInstant(createdAt, KST)

    /** 승인(§5.1 표): PAYING·UNKNOWN → PAID. paidAt = PG가 승인한 시각(재조회 승인이면 요청 시각). */
    fun approve(paidAt: Instant) {
        check(status == "PAYING" || status == "UNKNOWN") { "결과가 정해진 주문 $id 를 승인하려 했다: $status" }
        status = "PAID"
        this.paidAt = paidAt
    }

    /** 거절(§5.1 표): PAYING·UNKNOWN → FAILED. */
    fun decline() {
        check(status == "PAYING" || status == "UNKNOWN") { "결과가 정해진 주문 $id 를 거절하려 했다: $status" }
        status = "FAILED"
    }

    /** PG 결과를 아직 쓰지 못한 상태(요청 처리 중이거나, 쓰지 못하고 죽은 프로세스가 남긴 것). */
    fun isPaying(): Boolean = status == "PAYING"

    /**
     * 항목 환불 뒤의 주문 상태(ADR-0004 §7.1): 이 환불로 모든 항목이 환불됐으면(last) CANCELLED, 아니면 PARTIALLY_CANCELLED.
     * 환불 처리의 마지막 변경이다(포인트 복구 뒤).
     */
    fun recordItemRefund(last: Boolean) {
        check(status == "PAID" || status == "PARTIALLY_CANCELLED") { "결제되지 않은 주문 $id 의 항목을 환불하려 했다: $status" }
        status = if (last) "CANCELLED" else "PARTIALLY_CANCELLED"
    }

    /** 결과 미정: PG 재조회로 확정할 대상(§7 pg-inquiry). */
    fun isUnknown(): Boolean = status == "UNKNOWN"

    /**
     * 결과를 쓰지 못한 결제의 정리(§9의 7 — 결과 반영 실패 뒤 재기록, 기동 시 전환): 아직 PAYING이고 APPROVE 행이 없을 때만
     * UNKNOWN으로 바꾸고 써야 할 APPROVE UNKNOWN 행을 돌려준다(결과가 정해진 뒤 1행, §5.1). 아니면 null(이미 기록됨).
     * approveRowCount는 PAYING일 때만 센다.
     */
    fun abandonPayment(approveRowCount: () -> Long, requestedAt: Instant): PgTransaction? {
        if (!isPaying() || approveRowCount() > 0) return null
        markUnknown()
        return PgTransaction.unknownApproval(this, requestedAt)
    }

    /** 응답 없음(§5.1 표): PAYING → UNKNOWN. 확정은 pg-inquiry가 한다. */
    fun markUnknown() {
        check(status == "PAYING") { "PAYING이 아닌 주문 $id 를 UNKNOWN으로 바꾸려 했다: $status" }
        status = "UNKNOWN"
    }
}

@Entity
@Table(name = "order_items")
class OrderItem(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var orderId: Long,
    var tradeId: Long,
    var price: Long,
    var buyerFee: Long,
    var cardShare: Long,
    var pointShare: Long,
    var shippingShare: Long,
    status: String,
    paidAt: Instant? = null,
    shippedAt: Instant? = null,
    inspectedAt: Instant? = null,
    deliveredAt: Instant? = null,
    confirmedAt: Instant? = null,
    cancelledAt: Instant? = null,
) {
    var status: String = status
        private set

    var paidAt: Instant? = paidAt
        private set

    var shippedAt: Instant? = shippedAt
        private set

    var inspectedAt: Instant? = inspectedAt
        private set

    var deliveredAt: Instant? = deliveredAt
        private set

    var confirmedAt: Instant? = confirmedAt
        private set

    var cancelledAt: Instant? = cancelledAt
        private set

    /** 승인 반영(§5.1 표): 항목 PAID(paid_at). 적립 예정(§5.2): 적립액 > 0이면 EARN PENDING 행을 돌려준다(아니면 null). */
    fun markPaid(paidAt: Instant, buyerId: Long, ratePermille: Long): PointLedgerEntry? {
        status = "PAID"
        this.paidAt = paidAt
        val earn = earnAmount(ratePermille)
        return if (earn > 0) PointLedgerEntry.pendingEarn(buyerId, earn, id!!, paidAt) else null
    }

    fun markPaymentFailed() {
        status = "PAYMENT_FAILED"
    }

    /** 그 체결의 구매자 또는 판매자만(ADR-0004 §2.2 조회). */
    fun requireParty(trade: Trade, userId: Long) {
        if (userId != trade.buyerId && userId != trade.sellerId) {
            throw MarketException(Failure.NOT_OWNER, "구매자도 판매자도 아닌 주문 항목: $id")
        }
    }

    /** 그 주문의 구매자만(ADR-0004 §4 구매자 절차). */
    fun requireBuyer(order: PurchaseOrder, userId: Long) {
        if (order.buyerId != userId) throw MarketException(Failure.NOT_OWNER, "구매자가 아닌 주문 항목: $id")
    }

    /** 그 체결의 판매자만(§3 발송). */
    fun requireSeller(trade: Trade, userId: Long) {
        if (trade.sellerId != userId) throw MarketException(Failure.NOT_OWNER, "판매자가 아닌 주문 항목: $id")
    }

    /** 발송 등록(§3): PAID → SHIPPED_TO_CENTER, 현재 < paid_at + 3일. 상태 위반(409)이 기한 위반(409 DEADLINE_PASSED)보다 먼저다(§2.1). */
    fun ship(now: Instant) {
        requireStatus("PAID")
        val deadline = paidAt!!.plus(SHIP_WITHIN)
        if (!now.isBefore(deadline)) throw MarketException(Failure.DEADLINE_PASSED, "발송 기한($deadline)이 지났다: $id")
        status = "SHIPPED_TO_CENTER"
        shippedAt = now
    }

    /** 입고(§3): SHIPPED_TO_CENTER → INSPECTING. 입고 시각 열은 없다(inspected_at은 검수 때 쓴다). */
    fun receive() {
        requireStatus("SHIPPED_TO_CENTER")
        status = "INSPECTING"
    }

    /** 검수(§3)의 상태 확인: INSPECTING만. 바꾸지 않는다(불합격은 이 뒤에 판매자 귀책 환불을 한다). */
    fun requireInspecting() = requireStatus("INSPECTING")

    /** 검수 합격(§3): DELIVERING, inspected_at = 현재, inspections PASS 행(reason NULL — 요청의 reason은 무시)을 돌려준다. */
    fun passInspection(now: Instant): Inspection {
        requireStatus("INSPECTING")
        status = "DELIVERING"
        inspectedAt = now
        return Inspection(orderItemId = id!!, result = "PASS", reason = null, inspectedAt = now)
    }

    /**
     * 검수 불합격(§3·§6)의 기록: 판매자 귀책 환불(CANCELLED_SELLER_FAULT, cancelled_at = 현재)을 반영한 뒤 부른다.
     * inspected_at = 현재, inspections FAIL 행(reason = 받은 그대로)을 돌려준다.
     */
    fun failedInspection(reason: String, now: Instant): Inspection {
        check(status == "CANCELLED_SELLER_FAULT") { "판매자 귀책 환불 전의 항목 $id 에 불합격을 기록하려 했다: $status" }
        inspectedAt = now
        return Inspection(orderItemId = id!!, result = "FAIL", reason = reason, inspectedAt = now)
    }

    /** 배송 완료(§3): DELIVERING → DELIVERED, delivered_at = 현재. */
    fun deliver(now: Instant) {
        requireStatus("DELIVERING")
        status = "DELIVERED"
        deliveredAt = now
    }

    /**
     * 구매 확정(ADR-0004 §4.1)의 확인과 확정 시각: DELIVERED만, confirmed_at = min(현재, delivered_at + 7일)
     * (7일 뒤의 확정은 자동 확정과 같다). 바꾸지 않는다 — 구매자 행을 잠근 뒤 markConfirmed.
     */
    fun confirmationTime(now: Instant): Instant {
        requireStatus("DELIVERED")
        val lastDay = deliveredAt!!.plus(CONFIRM_WITHIN)
        return if (now.isBefore(lastDay)) now else lastDay
    }

    /** 확정 반영(§4.1·auto-confirm): CONFIRMED, confirmed_at. */
    fun markConfirmed(confirmedAt: Instant) {
        check(status == "DELIVERED") { "DELIVERED가 아닌 항목 $id 를 확정하려 했다: $status" }
        status = "CONFIRMED"
        this.confirmedAt = confirmedAt
    }

    /** 발송 기한 초과(ADR-0004 §5 shipment-deadline): PAID이고 paid_at + 3일 ≤ 현재. 바꾸지 않는다 — 판매자 귀책은 잠금 뒤. */
    fun isShipmentOverdue(now: Instant): Boolean = status == "PAID" && !paidAt!!.plus(SHIP_WITHIN).isAfter(now)

    /**
     * 자동 확정(ADR-0004 §5 auto-confirm)의 확정 시각: DELIVERED이고 delivered_at + 7일 ≤ 현재면 delivered_at + 7일(소급), 아니면 null.
     * 바꾸지 않는다 — 구매자 행을 잠근 뒤 markConfirmed.
     */
    fun autoConfirmationTime(now: Instant): Instant? {
        if (status != "DELIVERED") return null
        val due = deliveredAt!!.plus(CONFIRM_WITHIN)
        return if (due.isAfter(now)) null else due
    }

    /** 구매자 취소(§4.2)의 확인: PAID만(발송 기한이 지나도 배치 전이면 PAID라 취소된다). 바꾸지 않는다. */
    fun requireCancellableByBuyer() = requireStatus("PAID")

    /**
     * 분쟁 제기(§4.3): CONFIRMED이고 현재 ≤ confirmed_at + 14일. 상태 위반이 기한 위반보다 먼저다.
     * 항목 DISPUTED, OPEN 분쟁 행을 돌려준다.
     */
    fun openDispute(now: Instant): Dispute {
        requireStatus("CONFIRMED")
        val deadline = confirmedAt!!.plus(DISPUTE_WITHIN)
        if (now.isAfter(deadline)) throw MarketException(Failure.DEADLINE_PASSED, "분쟁 기간($deadline)이 지났다: $id")
        status = "DISPUTED"
        return Dispute.open(id!!, now)
    }

    /** 분쟁 기각(§4.4): DISPUTED → CONFIRMED(confirmed_at 그대로). */
    fun rejectDispute() {
        check(status == "DISPUTED") { "분쟁 중이 아닌 항목 $id 의 분쟁을 기각하려 했다: $status" }
        status = "CONFIRMED"
    }

    /** 환불된 항목(취소 2종 + 확정 후 환불, §7.1 주문 상태·마지막 환불 판정). */
    fun isRefunded(): Boolean = status in RefundFault.itemStatuses

    /**
     * 환불 반영(§7): 사유별 상태(구매자 취소 CANCELLED_BY_BUYER·판매자 귀책 CANCELLED_SELLER_FAULT·분쟁 인정 REFUNDED_AFTER_CONFIRM),
     * cancelled_at = 현재. 카드 취소와 사용자 잠금 뒤에 부른다.
     */
    fun markRefunded(fault: RefundFault, now: Instant) {
        check(status in fault.fromStatuses) { "${fault}로 환불할 수 없는 항목 $id: $status" }
        status = fault.itemStatus
        cancelledAt = now
    }

    /** 항목 절차의 상태 확인(§2.1): 기대 상태가 아니면 409 ITEM_STATE_CONFLICT. 주문·항목을 잠그고 다시 읽은 뒤 부른다. */
    private fun requireStatus(expected: String) {
        if (status != expected) throw MarketException(Failure.ITEM_STATE_CONFLICT, "${expected}가 아닌 주문 항목: $id ($status)")
    }

    /** 적립액 = floor(floor(카드 몫 × 체결가 ÷ 기준액) × 적립률), 기준액 = 체결가 + 수수료 + 배송비 몫. */
    private fun earnAmount(ratePermille: Long): Long {
        val base = Math.addExact(Math.addExact(price, buyerFee), shippingShare)
        return mulDiv(mulDiv(cardShare, price, base), ratePermille, 1000)
    }

    companion object {
        private val SHIP_WITHIN: Duration = Duration.ofDays(3)
        private val CONFIRM_WITHIN: Duration = Duration.ofDays(7)
        private val DISPUTE_WITHIN: Duration = Duration.ofDays(14)

        /** shipment-deadline 대상의 paid_at 상한(이 시각 이하면 발송 기한 초과). */
        fun shipmentOverdueCutoff(now: Instant): Instant = now.minus(SHIP_WITHIN)

        /** auto-confirm 대상의 delivered_at 상한(이 시각 이하면 자동 확정). */
        fun autoConfirmCutoff(now: Instant): Instant = now.minus(CONFIRM_WITHIN)
    }
}

/** 주문 하나와 그 항목(order_items.id 순서 = tradeId 오름차순). 한 스냅샷에서 읽은 것. */
class OrderSnapshot(val order: PurchaseOrder, val items: List<OrderItem>)

interface OrderRepository {
    fun find(id: Long): PurchaseOrder?

    fun findByIdempotencyKey(key: String): PurchaseOrder?

    /** 같은 멱등 키의 주문이 이미 있으면(orders_idempotency_key_key 위반) DuplicateIdempotencyKeyException. */
    fun add(order: PurchaseOrder)

    /** 잠금을 기다리는 사이에 바뀌었을 수 있으므로 잠근 뒤 DB의 현재 값으로 다시 읽는다. */
    fun reload(order: PurchaseOrder)

    /** 그 체결에 걸린(항목이 PAYMENT_FAILED가 아닌) 결과 미정(UNKNOWN·PAYING) 주문 수. */
    fun countUndecidedFor(tradeId: Long): Long

    /** 주문 행 잠금(`SELECT … FOR UPDATE`). 같은 주문의 항목 변경은 이 잠금으로 줄을 선다(ADR-0004 §2.3). */
    fun lock(id: Long)
}

/** 다른 요청이 같은 멱등 키로 주문을 먼저 만들었다(유일 제약 위반). 다시 하면 멱등 키 조회에서 판정된다. */
class DuplicateIdempotencyKeyException(key: String, cause: Throwable) : RuntimeException("멱등 키 충돌: $key", cause)

interface OrderItemRepository {
    fun add(item: OrderItem)

    fun find(id: Long): OrderItem?

    /** 항목 행 잠금(`SELECT … FOR UPDATE`). 주문 잠금 뒤, 사용자 잠금 전에 한다(잠금 순서 주문 → 항목 → 사용자). */
    fun lock(id: Long)

    /** 잠금을 기다리는 사이에 바뀌었을 수 있으므로 잠근 뒤 DB의 현재 값으로 다시 읽는다. */
    fun reload(item: OrderItem)

    /** 주문의 항목, id 순. */
    fun listOf(orderId: Long): List<OrderItem>

    fun tradeIdsOf(orderId: Long): Set<Long>

    /** 그 체결들의 PAYMENT_FAILED가 아닌 항목 수. */
    fun countLive(tradeIds: List<Long>): Long
}

@Entity
@Table(name = "pg_transactions")
class PgTransaction(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var orderId: Long,
    var kind: String,
    var amount: Long,
    var status: String,
    var pgTxId: String? = null,
    var idempotencyKey: String,
    var requestedAt: Instant,
    var respondedAt: Instant? = null,
) {
    companion object {
        /**
         * 승인 요청 결과 1행(§5.1, 결과가 정해진 뒤 INSERT). 승인·거절이면 responded_at = 현재, 응답 없음이면 UNKNOWN·responded_at NULL.
         */
        fun approval(orderId: Long, cardAmount: Long, idempotencyKey: String, result: PgResult, requestedAt: Instant, now: Instant) = PgTransaction(
            orderId = orderId,
            kind = "APPROVE",
            amount = cardAmount,
            status = result.outcome.name,
            pgTxId = result.pgTxId,
            idempotencyKey = idempotencyKey,
            requestedAt = requestedAt,
            respondedAt = if (result.outcome == PgOutcome.UNKNOWN) null else now,
        )

        /**
         * 재조회 결과 1행(§7 pg-inquiry): INQUIRY, amount = card_amount, requested_at·responded_at = 현재,
         * pg_tx_id는 APPROVED면 PG 값·DECLINED면 NULL. 응답 없음이면 행을 만들지 않는다(호출자).
         */
        fun inquiry(order: PurchaseOrder, result: PgResult, now: Instant): PgTransaction {
            check(result.outcome != PgOutcome.UNKNOWN) { "응답 없는 재조회는 기록하지 않는다: 주문 ${order.id}" }
            return PgTransaction(
                orderId = order.id!!,
                kind = "INQUIRY",
                amount = order.cardAmount,
                status = result.outcome.name,
                pgTxId = result.pgTxId,
                idempotencyKey = order.idempotencyKey,
                requestedAt = now,
                respondedAt = now,
            )
        }

        /**
         * 카드 취소 1행(ADR-0004 §7.2): CANCEL·APPROVED, amount = 카드 환불, pg_tx_id = PG 값, 키 `cancel-<항목 id>`,
         * requested_at = responded_at = 현재.
         */
        fun cancellation(orderId: Long, orderItemId: Long, amount: Long, pgTxId: String, now: Instant) = PgTransaction(
            orderId = orderId,
            kind = "CANCEL",
            amount = amount,
            status = "APPROVED",
            pgTxId = pgTxId,
            idempotencyKey = CardCancellation.keyFor(orderItemId),
            requestedAt = now,
            respondedAt = now,
        )

        /** 결과를 쓰지 못한 승인 요청의 UNKNOWN 1행(§9의 7). */
        fun unknownApproval(order: PurchaseOrder, requestedAt: Instant) = PgTransaction(
            orderId = order.id!!,
            kind = "APPROVE",
            amount = order.cardAmount,
            status = "UNKNOWN",
            pgTxId = null,
            idempotencyKey = order.idempotencyKey,
            requestedAt = requestedAt,
            respondedAt = null,
        )
    }
}

interface PgTransactionRepository {
    fun add(row: PgTransaction)

    fun approveCount(orderId: Long): Long

    /** 그 주문 APPROVE 행의 requested_at(PG가 실제로 승인한 때, §7 재조회 승인 시각). */
    fun approveRequestedAt(orderId: Long): Instant
}
