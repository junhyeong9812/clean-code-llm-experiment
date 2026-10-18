package com.ccle.layered.service

import com.ccle.layered.domain.BalanceDelta
import com.ccle.layered.domain.PointBalance
import com.ccle.layered.domain.PointBalanceRepository
import com.ccle.layered.domain.PointLedgerEntry
import com.ccle.layered.domain.PointRepository
import org.springframework.stereotype.Component
import java.time.Instant

/** 포인트 예약·해제·잔액 캐시 갱신(§5.2)의 저장 순서. 구매자 행을 잠근 호출자의 트랜잭션 안에서 부른다. */
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
