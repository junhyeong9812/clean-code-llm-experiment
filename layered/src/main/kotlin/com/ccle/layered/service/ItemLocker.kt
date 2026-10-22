package com.ccle.layered.service

import com.ccle.layered.domain.OrderItem
import com.ccle.layered.domain.OrderItemRepository
import com.ccle.layered.domain.OrderRepository
import com.ccle.layered.domain.notFound
import org.springframework.stereotype.Component

/**
 * 항목을 바꾸는 처리의 1단계(ADR-0004 §2.3·§9.1): 그 항목의 주문 행, 이어서 항목 행을 잠그고, 잠근 뒤의 항목을 돌려준다.
 * 같은 주문의 항목 변경은 이 잠금으로 줄을 서므로, 잠금을 기다리는 사이에 바뀐 상태를 다시 읽어 확인한다. 없는 항목이면 404.
 * 항목 행을 사용자 행보다 먼저 잠가 잠금 순서를 주문 → 항목 → 사용자로 고정한다(항목을 잠근 채 판매자 행에 FK를 거는 정산과 교착하지 않게).
 * 트랜잭션 안에서 부른다.
 */
@Component
class ItemLocker(private val orders: OrderRepository, private val items: OrderItemRepository) {

    fun lock(itemId: Long): OrderItem {
        val item = items.find(itemId) ?: notFound("없는 주문 항목: $itemId")
        orders.lock(item.orderId)
        items.lock(itemId)
        items.reload(item)
        return item
    }
}
