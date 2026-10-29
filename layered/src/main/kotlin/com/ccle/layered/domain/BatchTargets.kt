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

    /** deadline-reminder PAYMENT_DUE_SOON(ADR-0007 §7.1): AWAITING_PAYMENT, now < payment_due_at ≤ now + 10분, 같은 dedupe_key 알림 없음. */
    PAYMENT_DUE_SOON_TRADES,

    /** deadline-reminder SHIPMENT_DUE_SOON(§7.1): PAID, paid_at + 2일 ≤ now < paid_at + 3일, 같은 dedupe_key 알림 없음. */
    SHIPMENT_DUE_SOON_ITEMS,

    /** deadline-reminder DISPUTE_WINDOW_CLOSING(§7.1): CONFIRMED, confirmed_at + 12일 ≤ now ≤ confirmed_at + 14일, 같은 dedupe_key 알림 없음. */
    DISPUTE_WINDOW_CLOSING_ITEMS,

    /** deadline-reminder SETTLEMENT_PAID(§7.1): PAID, now − 7일 < paid_at ≤ now, 같은 dedupe_key 알림 없음. */
    RECENTLY_PAID_SETTLEMENTS,

    /** deadline-reminder PURCHASE_RESTRICTED(§7.1): purchase_restricted_until > now, 같은 dedupe_key 알림 없음. */
    PURCHASE_RESTRICTED_USERS,

    /** deadline-reminder SALE_RESTRICTED(§7.1): sale_restricted_until > now, 같은 dedupe_key 알림 없음. */
    SALE_RESTRICTED_USERS,

    /** price-alert-scan(ADR-0007 §8.3): ACTIVE 가격 알림. */
    ACTIVE_PRICE_ALERTS,

    /** delivery-delay-scan SHIPMENT_OVERDUE(ADR-0007 §12.2): PAID이고 paid_at + 3일 ≤ 현재, 같은 (항목, kind) 행 없음. */
    SHIPMENT_OVERDUE_ITEMS,

    /** delivery-delay-scan DELIVERY_LATE(§12.2): DELIVERING이고 inspected_at + 5일 ≤ 현재, 같은 (항목, kind) 행 없음. */
    DELIVERY_LATE_ITEMS,

    /** inquiry-auto-close(ADR-0007 §9.3): ANSWERED이고 answered_at + 7일 ≤ 현재인 문의. */
    AUTO_CLOSABLE_INQUIRIES,

    /** price-stats(ADR-0007 §11.1): 어제(KST) PAID 체결이 있고 (사이즈, 어제) 시세 행이 없는 사이즈. */
    PRICE_STATS_SIZES,

    /** inbound-no-show(ADR-0007 §12.3): RESERVED이고 slot_start + 1시간 ≤ 현재인 입고 예약. */
    NO_SHOW_RESERVATIONS,

    /** price-alert-rearm(ADR-0007 §20.3.3): TRIGGERED·종결 7일 안·발동 입찰 CANCELLED·EXPIRED·위시리스트 있음·더 새 알림·ACTIVE 없음·구매 제한 아님. */
    REARMABLE_PRICE_ALERTS,

    /** notification-purge(ADR-0007 §20.6.3): read_at IS NOT NULL이고 read_at ≤ 현재 − 30일인 알림. */
    PURGEABLE_NOTIFICATIONS,
}

interface BatchTargets {
    /**
     * 대상 id를 id 순으로 하나씩 handle에 넘긴다. 반환 = handle이 true(상태를 바꿨다)를 돌려준 수.
     * 시작할 때의 최대 id까지만 본다: 실행 중에 새로 생긴 행(재매칭 체결·새 주문 등)은 다음 실행의 대상이다.
     */
    fun forEach(target: BatchTarget, now: Instant, handle: (Long) -> Boolean): Int
}
