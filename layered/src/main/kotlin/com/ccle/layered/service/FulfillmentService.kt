package com.ccle.layered.service

import com.ccle.layered.domain.InspectionRepository
import com.ccle.layered.domain.InspectionRequest
import com.ccle.layered.domain.MarketClock
import com.ccle.layered.domain.OrderItem
import com.ccle.layered.domain.OrderItemRepository
import com.ccle.layered.domain.Refund
import com.ccle.layered.domain.RefundRepository
import com.ccle.layered.domain.TradeRepository
import com.ccle.layered.domain.notFound
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/** 주문 항목 응답의 재료(ADR-0004 §2.2): 항목과 그 환불 행(없으면 null). */
class ItemDetail(val item: OrderItem, val refund: Refund?)

/** 항목을 바꾼 트랜잭션·조회 스냅샷 안에서 환불 행을 읽어 응답 재료를 만든다. */
private fun RefundRepository.detailOf(item: OrderItem) = ItemDetail(item, findFor(item.id!!))

/** `GET /order-items/{id}`(§2.2). 그 체결의 구매자 또는 판매자만. 항목과 환불 행을 한 스냅샷에서 읽는다(OrderReader와 같은 이유). */
@Service
class OrderItemQueryService(
    tx: TransactionTemplate,
    private val items: OrderItemRepository,
    private val trades: TradeRepository,
    private val refunds: RefundRepository,
) {
    private val snapshotTx = TransactionTemplate(tx.transactionManager!!).apply {
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        isReadOnly = true
    }

    fun get(userId: Long, itemId: Long): ItemDetail = snapshotTx.execute {
        val item = items.find(itemId) ?: notFound("없는 주문 항목: $itemId")
        item.requireParty(trades.find(item.tradeId)!!, userId)
        refunds.detailOf(item)
    }!!
}

/**
 * 판매자 발송·센터 절차(§3): 발송 → 입고 → 검수 → 배송 완료. 각각 한 트랜잭션에서 주문 → 항목을 잠그고 다시 읽은 뒤 상태를 바꾼다.
 * 판정 순서: 404 → 403 → 409(상태) → 409(기한).
 */
@Service
class FulfillmentService(
    private val clock: MarketClock,
    private val tx: TransactionTemplate,
    private val locker: ItemLocker,
    private val trades: TradeRepository,
    private val inspections: InspectionRepository,
    private val refunds: RefundRepository,
) {

    /** 발송 등록. 그 체결의 판매자만. */
    fun ship(userId: Long, itemId: Long): ItemDetail {
        val now = clock.now()
        return tx.execute {
            val item = locker.lock(itemId)
            item.requireSeller(trades.find(item.tradeId)!!, userId)
            item.ship(now)
            refunds.detailOf(item)
        }!!
    }

    /** 입고. */
    fun receive(itemId: Long): ItemDetail =
        tx.execute {
            val item = locker.lock(itemId)
            item.receive()
            refunds.detailOf(item)
        }!!

    /** 검수. 요청 값 확인(400)은 잠그기 전에 한다. */
    fun inspect(itemId: Long, result: String?, reason: String?): ItemDetail {
        val request = InspectionRequest.of(result, reason)
        val now = clock.now()
        return tx.execute {
            val item = locker.lock(itemId)
            inspections.add(item.inspect(request, now))
            refunds.detailOf(item)
        }!!
    }

    /** 배송 완료. */
    fun deliver(itemId: Long): ItemDetail {
        val now = clock.now()
        return tx.execute {
            val item = locker.lock(itemId)
            item.deliver(now)
            refunds.detailOf(item)
        }!!
    }
}
