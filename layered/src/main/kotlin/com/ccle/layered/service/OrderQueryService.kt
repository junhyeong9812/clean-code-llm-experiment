package com.ccle.layered.service

import com.ccle.layered.domain.MarketClock
import com.ccle.layered.domain.OrderRepository
import com.ccle.layered.domain.OrderSnapshot
import com.ccle.layered.domain.PointBalanceRepository
import com.ccle.layered.domain.PointRepository
import com.ccle.layered.domain.notFound
import org.springframework.stereotype.Service

/** 주문 조회(§5.3). 구매자만. */
@Service
class OrderQueryService(private val orders: OrderRepository, private val reader: OrderReader) {

    fun get(userId: Long, orderId: Long): OrderSnapshot {
        val order = orders.find(orderId) ?: notFound("없는 주문: $orderId")
        order.requireBuyer(userId)
        return reader.read(orderId)
    }
}

/** `GET /me/points` 값: available·pending = 캐시(행 없으면 0), usable = 사용 가능액(만료 묶음 제외). */
class PointsSummary(val available: Long, val pending: Long, val usable: Long)

@Service
class PointQueryService(
    private val clock: MarketClock,
    private val balances: PointBalanceRepository,
    private val points: PointRepository,
) {

    fun summary(userId: Long): PointsSummary {
        val balance = balances.find(userId)
        return PointsSummary(
            available = balance?.available ?: 0L,
            pending = balance?.pending ?: 0L,
            usable = points.usable(userId, clock.now()),
        )
    }
}
