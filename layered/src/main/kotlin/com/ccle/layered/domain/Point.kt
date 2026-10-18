package com.ccle.layered.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

// 포인트(ADR-0003 §5.2·ADR-0002 §9.6). 사용 가능액 = EARN·AVAILABLE·만료 전 묶음의 remaining 합(예약분은 이미 remaining에서 빠져 있다).

@Entity
@Table(name = "point_ledger")
class PointLedgerEntry(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var userId: Long,
    var kind: String,
    status: String,
    var amount: Long,
    remaining: Long? = null,
    var expiresAt: Instant? = null,
    var orderItemId: Long? = null,
    var orderId: Long? = null,
    var createdAt: Instant,
) {
    var status: String = status
        private set

    var remaining: Long? = remaining
        private set

    /**
     * 예약 배분(USE 행에 부른다): 사용 가능 묶음을 (expires_at, id) 순으로 받아 앞에서부터 차감하고 배분 행을 돌려준다.
     * 사용 가능액 확인을 같은 구매자 잠금 안에서 했으므로 모자랄 수 없다.
     */
    fun allocate(bundles: List<PointLedgerEntry>): List<PointAllocation> {
        check(kind == "USE" && status == "RESERVED") { "예약이 아닌 행 $id 에서 배분하려 했다: $kind $status" }
        val allocations = mutableListOf<PointAllocation>()
        var need = amount
        for (bundle in bundles) {
            if (need == 0L) break
            val take = minOf(bundle.remaining!!, need)
            bundle.remaining = bundle.remaining!! - take
            need -= take
            allocations += PointAllocation(entryId = id!!, earnEntryId = bundle.id!!, amount = take)
        }
        check(need == 0L) { "포인트 부족: user $userId, 부족 $need" }
        return allocations
    }

    /** 예약 확정: USE RESERVED → CONFIRMED(배분 그대로). */
    fun confirm() {
        check(kind == "USE" && status == "RESERVED") { "예약이 아닌 행 $id 를 확정하려 했다: $kind $status" }
        status = "CONFIRMED"
    }

    /** 예약의 잔액 영향: available −amount(사용 가능액에서 빠진다). */
    fun reservationDelta(): BalanceDelta {
        check(kind == "USE") { "USE가 아닌 행 $id 의 예약 영향을 물었다: $kind" }
        return BalanceDelta(available = -amount, pending = 0)
    }

    /**
     * 예약 해제: USE RESERVED → RELEASED. 잔액 영향(available +amount)을 돌려준다.
     * 배분 행 삭제와 묶음 되돌림(restore)은 호출자가 한다.
     */
    fun release(): BalanceDelta {
        check(kind == "USE" && status == "RESERVED") { "예약이 아닌 행 $id 를 해제하려 했다: $kind $status" }
        status = "RELEASED"
        return BalanceDelta(available = amount, pending = 0)
    }

    /** 해제된 배분만큼 묶음의 remaining을 되돌린다. */
    fun restore(amount: Long) {
        remaining = remaining!! + amount
    }

    companion object {
        /** 예약(§5.2): USE RESERVED 1행, order_item_id·expires_at·remaining 없음. */
        fun reservation(userId: Long, orderId: Long, amount: Long, now: Instant) = PointLedgerEntry(
            userId = userId,
            kind = "USE",
            status = "RESERVED",
            amount = amount,
            remaining = null,
            expiresAt = null,
            orderItemId = null,
            orderId = orderId,
            createdAt = now,
        )

        /** 적립 예정 행들의 잔액 영향: pending +금액 합. 적립 예정이 없으면 잔액을 바꾸지 않는다(null). */
        fun pendingDelta(earns: List<PointLedgerEntry>): BalanceDelta? =
            if (earns.isEmpty()) null else BalanceDelta(available = 0, pending = earns.fold(0L) { sum, e -> Math.addExact(sum, e.amount) })

        /** 적립 예정(§5.2): 항목마다 EARN PENDING, remaining = 적립액, expires_at·order_id 없음, created_at = 결제 시각. */
        fun pendingEarn(userId: Long, amount: Long, orderItemId: Long, paidAt: Instant) = PointLedgerEntry(
            userId = userId,
            kind = "EARN",
            status = "PENDING",
            amount = amount,
            remaining = amount,
            expiresAt = null,
            orderItemId = orderItemId,
            orderId = null,
            createdAt = paidAt,
        )
    }
}

@Entity
@Table(name = "point_allocations")
class PointAllocation(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var entryId: Long,
    var earnEntryId: Long,
    var amount: Long,
)

/** point_balances 캐시의 변화량. */
data class BalanceDelta(val available: Long, val pending: Long)

/** point_balances 캐시. 행이 없는 사용자는 처음 바뀔 때 만든다. updated_at = 바꾼 시각. */
@Entity
@Table(name = "point_balances")
class PointBalance(
    @Id
    var userId: Long,
    available: Long,
    pending: Long,
    updatedAt: Instant,
) {
    var available: Long = available
        private set

    var pending: Long = pending
        private set

    var updatedAt: Instant = updatedAt
        private set

    fun apply(delta: BalanceDelta, now: Instant) {
        available = Math.addExact(available, delta.available)
        pending = Math.addExact(pending, delta.pending)
        updatedAt = now
    }

    companion object {
        /** 처음 바뀌는 사용자의 행: 0에서 delta만큼 바뀐 값. */
        fun opened(userId: Long, delta: BalanceDelta, now: Instant) =
            PointBalance(userId = userId, available = delta.available, pending = delta.pending, updatedAt = now)
    }
}

interface PointRepository {
    fun find(entryId: Long): PointLedgerEntry

    fun add(entry: PointLedgerEntry)

    /** 사용 가능액(§5.2): EARN·AVAILABLE·expires_at > 현재 묶음의 remaining 합. */
    fun usable(userId: Long, now: Instant): Long

    /** 차감할 사용 가능 묶음(remaining > 0), (expires_at, id) 순. */
    fun usableBundles(userId: Long, now: Instant): List<PointLedgerEntry>

    /** 그 주문의 USE 행(없으면 null). */
    fun useEntryOf(orderId: Long): PointLedgerEntry?

    fun addAllocation(allocation: PointAllocation)

    fun allocationsOf(useEntryId: Long): List<PointAllocation>

    fun removeAllocation(allocation: PointAllocation)
}

interface PointBalanceRepository {
    fun find(userId: Long): PointBalance?

    fun add(balance: PointBalance)
}
