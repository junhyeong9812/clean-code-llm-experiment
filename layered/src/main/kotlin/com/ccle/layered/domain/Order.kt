package com.ccle.layered.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** 날짜 판정의 시간대(ADR-0003 §2). */
private val KST: ZoneOffset = ZoneOffset.ofHours(9)

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
    var shippedAt: Instant? = null,
    var inspectedAt: Instant? = null,
    var deliveredAt: Instant? = null,
    var confirmedAt: Instant? = null,
    var cancelledAt: Instant? = null,
) {
    var status: String = status
        private set

    var paidAt: Instant? = paidAt
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

    /** 적립액 = floor(floor(카드 몫 × 체결가 ÷ 기준액) × 적립률), 기준액 = 체결가 + 수수료 + 배송비 몫. */
    private fun earnAmount(ratePermille: Long): Long {
        val base = Math.addExact(Math.addExact(price, buyerFee), shippingShare)
        return mulDiv(mulDiv(cardShare, price, base), ratePermille, 1000)
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
}

/** 다른 요청이 같은 멱등 키로 주문을 먼저 만들었다(유일 제약 위반). 다시 하면 멱등 키 조회에서 판정된다. */
class DuplicateIdempotencyKeyException(key: String, cause: Throwable) : RuntimeException("멱등 키 충돌: $key", cause)

interface OrderItemRepository {
    fun add(item: OrderItem)

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
