package com.ccle.layered.service

import com.ccle.layered.domain.BalanceDelta
import com.ccle.layered.domain.OrderItem
import com.ccle.layered.domain.PointBalance
import com.ccle.layered.domain.PointBalanceRepository
import com.ccle.layered.domain.PointLedgerEntry
import com.ccle.layered.domain.PointRepository
import com.ccle.layered.domain.PointRestoration
import com.ccle.layered.domain.PurchaseOrder
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 포인트 예약·해제·잔액 캐시 갱신(§5.2)과 적립 확정·취소·회수·복구(ADR-0004 §7.3·§8.2·§8.4)의 저장 순서.
 * 구매자 행을 잠근 호출자의 트랜잭션 안에서 부른다.
 */
@Component
class PointBook(private val points: PointRepository, private val balances: PointBalanceRepository) {

    /** 예약: USE RESERVED 1행 → 사용 가능 묶음 (expires_at, id) 순 차감·배분 → available 감소. */
    fun reserve(use: PointLedgerEntry, now: Instant) {
        points.add(use)
        use.allocate(points.usableBundles(use.userId, now)).forEach(points::addAllocation)
        changeBalance(use.userId, use.reservationDelta(), now)
    }

    /** 해제: USE RELEASED, 그 USE의 배분 행을 지우고 같은 묶음의 remaining·available을 되돌린다. */
    fun release(use: PointLedgerEntry, now: Instant) {
        val delta = use.release()
        for (a in points.allocationsOf(use.id!!)) {
            points.find(a.earnEntryId).restore(a.amount)
            points.removeAllocation(a)
        }
        changeBalance(use.userId, delta, now)
    }

    /** 적립 확정(ADR-0004 §8.2): 그 항목의 EARN이 PENDING이면 AVAILABLE + 잔액 pending → available. 적립 행이 없으면 그대로. */
    fun confirmEarn(item: OrderItem, buyerId: Long, confirmedAt: Instant, now: Instant) {
        val earn = points.earnOf(item.id!!) ?: return
        val delta = earn.confirmEarn(confirmedAt) ?: return
        changeBalance(buyerId, delta, now)
    }

    /** 적립 예정 취소(§7.3): 그 항목의 EARN이 PENDING이면 CANCELLED + pending 감소. */
    fun cancelPendingEarn(item: OrderItem, now: Instant) {
        val earn = points.earnOf(item.id!!) ?: return
        val delta = earn.cancelPending() ?: return
        changeBalance(earn.userId, delta, now)
    }

    /** 회수(§8.4): 그 항목의 EARN 묶음에 남은 양 전부를 REVOKE 1행 + 배분, remaining 0, available 감소. */
    fun revokeEarn(item: OrderItem, now: Instant) {
        val earn = points.earnOf(item.id!!) ?: return
        val revoke = earn.revocation(item.orderId, item.id!!, now) ?: return
        points.add(revoke)
        points.addAllocation(earn.revokedBy(revoke))
        changeBalance(earn.userId, BalanceDelta(available = -revoke.amount, pending = 0), now)
    }

    /**
     * 소멸(§8.3): 만료된 EARN 묶음에 남은 양 전부를 EXPIRE 1행(주문 NULL) + 배분 1행, remaining 0(EARN은 AVAILABLE), available 감소.
     * 사용자 행을 잠그고 묶음을 다시 읽은 뒤 부른다. 소멸할 것이 없으면(그 사이 쓰였거나 만료 전) 그대로 false.
     */
    fun expire(earn: PointLedgerEntry, now: Instant): Boolean {
        val expire = earn.expiration(now) ?: return false
        points.add(expire)
        points.addAllocation(earn.expiredBy(expire))
        changeBalance(earn.userId, BalanceDelta(available = -expire.amount, pending = 0), now)
        return true
    }

    /**
     * 복구(§7.3): RESTORE 1행 → 복구 대상 묶음을 차례로 되돌리며 배분 → 이미 만료된 묶음은 되돌린 양만큼 EXPIRE 1행 + 배분 → available 증가.
     * 구매자 행을 잠근 트랜잭션 안에서 부른다.
     */
    fun restore(order: PurchaseOrder, item: OrderItem, amount: Long, now: Instant) {
        val restore = PointLedgerEntry.restoration(order.buyerId, order.id!!, item.id!!, amount, now)
        points.add(restore)
        val restoration = PointRestoration(restore, now)
        for (row in points.restorableBundles(order.id!!)) {
            if (restoration.isComplete()) break
            val take = restoration.takeFrom(row)
            if (take <= 0) continue
            points.addAllocation(restoration.returnTo(points.find(row.earnEntryId), take))
        }
        restoration.requireComplete()
        for ((bundle, take) in restoration.expiring()) {
            val expire = PointLedgerEntry.expiry(order.buyerId, take, now)
            points.add(expire)
            points.addAllocation(bundle.expiredBy(expire))
        }
        changeBalance(order.buyerId, restoration.balanceDelta(), now)
    }

    /** point_balances 캐시 갱신. 행이 없는 사용자는 처음 바뀔 때 만든다. */
    fun changeBalance(userId: Long, delta: BalanceDelta, now: Instant) {
        val balance = balances.find(userId)
        if (balance == null) {
            balances.add(PointBalance.opened(userId, delta, now))
        } else {
            balance.apply(delta, now)
        }
    }
}
