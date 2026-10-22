package com.ccle.layered.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Duration
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

    /**
     * 적립 확정(ADR-0004 §8.2, EARN에 부른다): PENDING이면 AVAILABLE, expires_at = confirmed_at + 365일, 잔액 영향 pending → available.
     * PENDING이 아니면 그대로(null).
     */
    fun confirmEarn(confirmedAt: Instant): BalanceDelta? {
        check(kind == "EARN") { "EARN이 아닌 행 $id 을 적립 확정하려 했다: $kind" }
        if (status != "PENDING") return null
        status = "AVAILABLE"
        expiresAt = confirmedAt.plus(EARN_VALID_FOR)
        return BalanceDelta(available = amount, pending = -amount)
    }

    /** 적립 예정 취소(§7.3, EARN에 부른다): PENDING이면 CANCELLED, pending 감소. remaining은 그대로(= amount). 아니면 null. */
    fun cancelPending(): BalanceDelta? {
        check(kind == "EARN") { "EARN이 아닌 행 $id 의 적립 예정을 취소하려 했다: $kind" }
        if (status != "PENDING") return null
        status = "CANCELLED"
        return BalanceDelta(available = 0, pending = -amount)
    }

    /**
     * 회수(§8.4, EARN에 부른다): AVAILABLE이고 남은 양 > 0이면 남은 양 전부의 REVOKE 행(아직 아무것도 바꾸지 않음), 아니면 null.
     * 이미 쓴 부분은 회수하지 않는다. EARN은 AVAILABLE로 남는다.
     */
    fun revocation(orderId: Long, orderItemId: Long, now: Instant): PointLedgerEntry? {
        check(kind == "EARN") { "EARN이 아닌 행 $id 을 회수하려 했다: $kind" }
        val left = remaining!!
        if (status != "AVAILABLE" || left == 0L) return null
        return PointLedgerEntry(
            userId = userId,
            kind = "REVOKE",
            status = "DONE",
            amount = left,
            orderItemId = orderItemId,
            orderId = orderId,
            createdAt = now,
        )
    }

    /** 회수 반영(저장한 REVOKE 행으로): remaining 0, 배분 1행을 돌려준다. */
    fun revokedBy(revoke: PointLedgerEntry): PointAllocation {
        check(revoke.kind == "REVOKE" && revoke.amount == remaining) { "묶음 $id 의 남은 양과 다른 회수: ${revoke.amount}" }
        remaining = 0
        return PointAllocation(entryId = revoke.id!!, earnEntryId = id!!, amount = revoke.amount)
    }

    /**
     * 소멸(ADR-0004 §8.3, EARN에 부른다): AVAILABLE이고 남은 양 > 0이고 expires_at ≤ 현재면 남은 양 전부의 EXPIRE 행
     * (아직 아무것도 바꾸지 않음 — 저장한 뒤 expiredBy), 아니면 null.
     */
    fun expiration(now: Instant): PointLedgerEntry? {
        check(kind == "EARN") { "EARN이 아닌 행 $id 을 소멸하려 했다: $kind" }
        val left = remaining!!
        if (status != "AVAILABLE" || left == 0L || expiresAt!!.isAfter(now)) return null
        return expiry(userId, left, now)
    }

    /** 소멸 반영(저장한 EXPIRE 행으로): 그 양만큼 remaining을 줄이고 배분 1행을 돌려준다(EARN은 AVAILABLE 그대로). */
    fun expiredBy(expire: PointLedgerEntry): PointAllocation {
        check(expire.kind == "EXPIRE") { "EXPIRE가 아닌 행 ${expire.id} 로 소멸하려 했다: ${expire.kind}" }
        remaining = remaining!! - expire.amount
        return PointAllocation(entryId = expire.id!!, earnEntryId = id!!, amount = expire.amount)
    }

    companion object {
        private val EARN_VALID_FOR: Duration = Duration.ofDays(365)

        /** 복구(§7.3): RESTORE 1행(DONE, amount = 포인트 환불, 주문·항목, created_at = 현재). */
        fun restoration(userId: Long, orderId: Long, orderItemId: Long, amount: Long, now: Instant) = PointLedgerEntry(
            userId = userId,
            kind = "RESTORE",
            status = "DONE",
            amount = amount,
            orderItemId = orderItemId,
            orderId = orderId,
            createdAt = now,
        )

        /** 소멸(§7.3·§8.3): EXPIRE 1행(DONE, 주문·항목 NULL, created_at = 현재). */
        fun expiry(userId: Long, amount: Long, now: Instant) =
            PointLedgerEntry(userId = userId, kind = "EXPIRE", status = "DONE", amount = amount, createdAt = now)

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

/** 복구 대상 묶음 1개(§7.3): 그 주문의 CONFIRMED USE가 이 묶음에서 가져간 양 − 이 주문의 앞선 RESTORE가 이미 되돌린 양. */
class RestorableBundle(val earnEntryId: Long, val restorable: Long)

/**
 * 포인트 복구(§7.3)의 배분 계산. restore = 저장한 RESTORE 행. 복구 대상 묶음을 만료가 늦은 것부터(같으면 id 큰 것부터) 받아,
 * 묶음마다 되돌릴 수 있는 양까지 되돌린다. 되돌린 묶음이 이미 만료됐으면(expires_at ≤ 현재) 되돌린 양만큼 같은 시각에 소멸한다.
 */
class PointRestoration(private val restore: PointLedgerEntry, private val now: Instant) {
    private var need = restore.amount
    private val expiring = mutableListOf<Pair<PointLedgerEntry, Long>>()

    init {
        check(restore.kind == "RESTORE") { "RESTORE가 아닌 행 ${restore.id} 으로 복구하려 했다: ${restore.kind}" }
    }

    fun isComplete(): Boolean = need == 0L

    /** 이 묶음에서 되돌릴 양(0 이하면 건너뛴다). */
    fun takeFrom(row: RestorableBundle): Long = minOf(row.restorable, need)

    /** 묶음에 take만큼 되돌리고 배분 1행을 돌려준다. */
    fun returnTo(bundle: PointLedgerEntry, take: Long): PointAllocation {
        bundle.restore(take)
        need -= take
        if (!bundle.expiresAt!!.isAfter(now)) expiring += bundle to take
        return PointAllocation(entryId = restore.id!!, earnEntryId = bundle.id!!, amount = take)
    }

    /** 환불 포인트는 그 주문이 쓴 포인트를 넘지 않으므로(§7.1) 모자랄 수 없다. */
    fun requireComplete() = check(need == 0L) { "복구 대상 부족: order ${restore.orderId}, 부족 $need" }

    /** 되돌린 뒤 바로 소멸할 (묶음, 양), 되돌린 순서. */
    fun expiring(): List<Pair<PointLedgerEntry, Long>> = expiring

    /** 잔액 영향: available + (복구 − 소멸). */
    fun balanceDelta(): BalanceDelta = BalanceDelta(available = restore.amount - expiring.sumOf { it.second }, pending = 0)
}

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

    /** 잠금을 기다리는 사이에 바뀌었을 수 있으므로 DB의 현재 값으로 다시 읽는다. */
    fun reload(entry: PointLedgerEntry)

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

    /** 그 항목의 EARN 행(없으면 null). */
    fun earnOf(orderItemId: Long): PointLedgerEntry?

    /** 그 주문의 복구 대상 묶음(§7.3), 만료가 늦은 것부터(같으면 id 큰 것부터). */
    fun restorableBundles(orderId: Long): List<RestorableBundle>
}

interface PointBalanceRepository {
    fun find(userId: Long): PointBalance?

    fun add(balance: PointBalance)
}
