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

    /** shipment-deadline(ADR-0004 §5): PAID이고 paid_at + 3일 ≤ 현재인 항목. */
    UNSHIPPED_ITEMS,

    /** auto-confirm(ADR-0004 §5): DELIVERED이고 delivered_at + 7일 ≤ 현재인 항목. */
    UNCONFIRMED_ITEMS,

    /** point-expiry(ADR-0004 §8.3): EARN AVAILABLE, remaining > 0, expires_at ≤ 현재인 묶음. */
    EXPIRED_POINTS,

    /** grade-recalc(ADR-0004 §8.5): 기준일 이력이 없고 계산한 등급이 users.grade와 다른 사용자. */
    GRADE_CHANGES,

    /** rating-recalc(ADR-0007 §4.5): 리뷰가 1행 이상 있는 판매자(users). */
    REVIEWED_SELLERS,

    /** seller-grade-recalc(ADR-0007 §5.2): 정산 행이 있고 이번 달(D) 판매자 등급 행이 없는 사용자. */
    SELLER_GRADE_PENDING,

    /** rebate-settlement(ADR-0007 §5.3): 지난달(M) 등급 행이 있고 M 리베이트가 없는 판매자. 달이 닫혔을 때만 순회한다. */
    REBATE_PENDING,

    /** monthly-statement(ADR-0007 §6.4): 지난달(M) 정산이 있고 M 정산서가 없는 판매자. 달이 닫혔을 때만 순회한다. */
    MONTHLY_STATEMENT_PENDING,

    /** tax-invoice-issue(ADR-0007 §10.2): 계산서가 없는 정산 중 (PAID이고 paid_at ≤ 현재) 또는 CARRIED. */
    UNINVOICED_SETTLEMENTS,
}

interface BatchTargets {
    /**
     * 대상 id를 id 순으로 하나씩 handle에 넘긴다. 반환 = handle이 true(상태를 바꿨다)를 돌려준 수.
     * 시작할 때의 최대 id까지만 본다: 실행 중에 새로 생긴 행(재매칭 체결·새 주문 등)은 다음 실행의 대상이다.
     */
    fun forEach(target: BatchTarget, now: Instant, handle: (Long) -> Boolean): Int
}
