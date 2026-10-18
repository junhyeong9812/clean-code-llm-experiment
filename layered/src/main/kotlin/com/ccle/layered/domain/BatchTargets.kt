package com.ccle.layered.domain

import java.time.Instant

/** 배치·기동 전환의 대상(ADR-0003 §7·§9의 7). 처리 순서는 id 순. */
enum class BatchTarget {
    /** bid-expiry: status = ACTIVE AND expires_at ≤ 현재인 입찰. */
    EXPIRED_BIDS,

    /** payment-due: AWAITING_PAYMENT AND payment_due_at ≤ 현재인 체결 중 UNKNOWN·PAYING 주문이 없는 것. */
    OVERDUE_TRADES,

    /** pg-inquiry: UNKNOWN 주문. */
    UNKNOWN_ORDERS,

    /** 기동 시 전환: 남아 있는 PAYING 주문. */
    PAYING_ORDERS,
}

interface BatchTargets {
    /**
     * 대상 id를 id 순으로 하나씩 handle에 넘긴다. 반환 = handle이 true(상태를 바꿨다)를 돌려준 수.
     * 시작할 때의 최대 id까지만 본다: 실행 중에 새로 생긴 행(재매칭 체결·새 주문 등)은 다음 실행의 대상이다.
     */
    fun forEach(target: BatchTarget, now: Instant, handle: (Long) -> Boolean): Int
}
