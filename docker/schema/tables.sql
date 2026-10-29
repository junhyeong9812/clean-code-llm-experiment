-- ADR-0002 §6·§9 스키마: 테이블과 PK·NOT NULL·CHECK만.
-- FK·UNIQUE·인덱스는 constraints.sql (대량 적재 뒤에 걸어 적재 속도와 전체 검증을 함께 얻는다).
-- 금액은 원 단위 bigint, 비율 numeric(5,4), 시각 timestamptz.

-- 사용자·등급
CREATE TABLE users (
    id                         bigserial PRIMARY KEY,
    email                      text        NOT NULL,
    name                       text        NOT NULL,
    grade                      text        NOT NULL CHECK (grade IN ('BRONZE','SILVER','GOLD','VIP')),
    purchase_restricted_until  timestamptz,
    sale_restricted_until      timestamptz,
    created_at                 timestamptz NOT NULL
);

CREATE TABLE user_grade_history (
    id              bigserial PRIMARY KEY,
    user_id         bigint NOT NULL,
    grade           text   NOT NULL CHECK (grade IN ('BRONZE','SILVER','GOLD','VIP')),
    effective_from  date   NOT NULL,
    basis_amount    bigint NOT NULL CHECK (basis_amount >= 0)
);

CREATE TABLE seller_payout_accounts (
    user_id            bigint PRIMARY KEY,
    bank_code          text   NOT NULL,
    account_no_masked  text   NOT NULL
);

-- 상품
CREATE TABLE categories (
    id             bigserial PRIMARY KEY,
    name           text         NOT NULL,
    sale_fee_rate  numeric(5,4) NOT NULL CHECK (sale_fee_rate >= 0 AND sale_fee_rate < 1)
);

CREATE TABLE products (
    id             bigserial PRIMARY KEY,
    category_id    bigint NOT NULL,
    brand          text   NOT NULL,
    name           text   NOT NULL,
    model_no       text   NOT NULL,
    release_price  bigint NOT NULL CHECK (release_price > 0)
);

CREATE TABLE product_sizes (
    id          bigserial PRIMARY KEY,
    product_id  bigint NOT NULL,
    size        text   NOT NULL
);

-- 입찰·체결
CREATE TABLE bids (
    id               bigserial PRIMARY KEY,
    user_id          bigint      NOT NULL,
    product_size_id  bigint      NOT NULL,
    side             text        NOT NULL CHECK (side IN ('BUY','SELL')),
    price            bigint      NOT NULL CHECK (price > 0),
    status           text        NOT NULL CHECK (status IN ('ACTIVE','MATCHED','CANCELLED','EXPIRED')),
    expires_at       timestamptz NOT NULL,
    created_at       timestamptz NOT NULL,
    CHECK (expires_at > created_at)
);

-- append-only: 입찰의 모든 상태 변화(§9.1). 등록은 from_status NULL → ACTIVE.
CREATE TABLE bid_events (
    id           bigserial PRIMARY KEY,
    bid_id       bigint      NOT NULL,
    from_status  text        CHECK (from_status IN ('ACTIVE','MATCHED','CANCELLED','EXPIRED')),
    to_status    text        NOT NULL CHECK (to_status IN ('ACTIVE','MATCHED','CANCELLED','EXPIRED')),
    trade_id     bigint,
    created_at   timestamptz NOT NULL
);

CREATE TABLE trades (
    id               bigserial PRIMARY KEY,
    buy_bid_id       bigint      NOT NULL,
    sell_bid_id      bigint      NOT NULL,
    buyer_id         bigint      NOT NULL,
    seller_id        bigint      NOT NULL,
    product_size_id  bigint      NOT NULL,
    price            bigint      NOT NULL CHECK (price > 0),
    status           text        NOT NULL CHECK (status IN ('AWAITING_PAYMENT','PAID','PAYMENT_EXPIRED')),
    matched_at       timestamptz NOT NULL,
    payment_due_at   timestamptz NOT NULL,
    CHECK (buyer_id <> seller_id),
    CHECK (payment_due_at > matched_at)
);

CREATE TABLE buyer_warnings (
    id          bigserial PRIMARY KEY,
    user_id     bigint      NOT NULL,
    trade_id    bigint      NOT NULL,
    created_at  timestamptz NOT NULL
);

-- 주문·결제
CREATE TABLE orders (
    id                bigserial PRIMARY KEY,
    buyer_id          bigint      NOT NULL,
    status            text        NOT NULL CHECK (status IN ('PENDING','PAYING','PAID','FAILED','UNKNOWN','PARTIALLY_CANCELLED','CANCELLED')),
    item_total        bigint      NOT NULL CHECK (item_total > 0),
    buyer_fee_total   bigint      NOT NULL CHECK (buyer_fee_total >= 0),
    shipping_fee      bigint      NOT NULL CHECK (shipping_fee >= 0),
    point_used        bigint      NOT NULL CHECK (point_used >= 0),
    card_amount       bigint      NOT NULL CHECK (card_amount >= 0),
    idempotency_key   text        NOT NULL,
    created_at        timestamptz NOT NULL,
    paid_at           timestamptz,
    CHECK (item_total + buyer_fee_total + shipping_fee = point_used + card_amount)   -- 불변식 1
);

CREATE TABLE order_items (
    id              bigserial PRIMARY KEY,
    order_id        bigint NOT NULL,
    trade_id        bigint NOT NULL,
    price           bigint NOT NULL CHECK (price > 0),
    buyer_fee       bigint NOT NULL CHECK (buyer_fee >= 0),
    card_share      bigint NOT NULL CHECK (card_share >= 0),
    point_share     bigint NOT NULL CHECK (point_share >= 0),
    shipping_share  bigint NOT NULL CHECK (shipping_share >= 0),
    status          text   NOT NULL CHECK (status IN ('AWAITING_PAYMENT','PAYMENT_FAILED','PAID','SHIPPED_TO_CENTER','INSPECTING',
                                                     'DELIVERING','DELIVERED','CONFIRMED','DISPUTED','REFUNDED_AFTER_CONFIRM',
                                                     'CANCELLED_BY_BUYER','CANCELLED_SELLER_FAULT')),
    paid_at         timestamptz,
    shipped_at      timestamptz,
    inspected_at    timestamptz,
    delivered_at    timestamptz,
    confirmed_at    timestamptz,
    cancelled_at    timestamptz,
    CHECK (card_share + point_share = price + buyer_fee + shipping_share)
);

CREATE TABLE pg_transactions (
    id               bigserial PRIMARY KEY,
    order_id         bigint      NOT NULL,
    kind             text        NOT NULL CHECK (kind IN ('APPROVE','CANCEL','INQUIRY')),
    amount           bigint      NOT NULL CHECK (amount >= 0),
    status           text        NOT NULL CHECK (status IN ('REQUESTED','APPROVED','DECLINED','UNKNOWN')),
    pg_tx_id         text,
    idempotency_key  text        NOT NULL,
    requested_at     timestamptz NOT NULL,
    responded_at     timestamptz
);

CREATE TABLE refunds (
    id                bigserial PRIMARY KEY,
    order_item_id     bigint      NOT NULL,
    card_amount       bigint      NOT NULL CHECK (card_amount >= 0),
    point_amount      bigint      NOT NULL CHECK (point_amount >= 0),
    buyer_fee_amount  bigint      NOT NULL CHECK (buyer_fee_amount >= 0),
    shipping_amount   bigint      NOT NULL CHECK (shipping_amount >= 0),
    reason            text        NOT NULL,
    fault             text        NOT NULL CHECK (fault IN ('BUYER','SELLER','DISPUTE')),
    created_at        timestamptz NOT NULL
);

CREATE TABLE inspections (
    id             bigserial PRIMARY KEY,
    order_item_id  bigint      NOT NULL,
    result         text        NOT NULL CHECK (result IN ('PASS','FAIL')),
    reason         text,
    inspected_at   timestamptz NOT NULL
);

CREATE TABLE disputes (
    id             bigserial PRIMARY KEY,
    order_item_id  bigint      NOT NULL,
    status         text        NOT NULL CHECK (status IN ('OPEN','ACCEPTED','REJECTED')),
    opened_at      timestamptz NOT NULL,
    resolved_at    timestamptz,
    CHECK ((status = 'OPEN') = (resolved_at IS NULL))
);

-- 포인트
CREATE TABLE point_ledger (
    id             bigserial PRIMARY KEY,
    user_id        bigint      NOT NULL,
    kind           text        NOT NULL CHECK (kind IN ('EARN','USE','RESTORE','EXPIRE','REVOKE')),
    status         text        NOT NULL,
    amount         bigint      NOT NULL CHECK (amount > 0),
    remaining      bigint,
    expires_at     timestamptz,
    order_item_id  bigint,
    order_id       bigint,
    created_at     timestamptz NOT NULL,
    CHECK ((kind = 'EARN'    AND status IN ('PENDING','AVAILABLE','CANCELLED'))
        OR (kind = 'USE'     AND status IN ('RESERVED','CONFIRMED','RELEASED'))
        OR (kind IN ('RESTORE','EXPIRE','REVOKE') AND status = 'DONE')),
    CHECK ((kind = 'EARN') = (remaining IS NOT NULL)),
    CHECK (remaining IS NULL OR (remaining >= 0 AND remaining <= amount)),
    CHECK (kind <> 'EARN' OR status <> 'AVAILABLE' OR expires_at IS NOT NULL)
);

-- entry_id: USE·EXPIRE·REVOKE(묶음에서 빠짐) 또는 RESTORE(묶음으로 되돌아옴) (§9.2)
CREATE TABLE point_allocations (
    id             bigserial PRIMARY KEY,
    entry_id       bigint NOT NULL,
    earn_entry_id  bigint NOT NULL,
    amount         bigint NOT NULL CHECK (amount > 0)
);

-- 원장의 캐시(불변식 4로 검증)
CREATE TABLE point_balances (
    user_id     bigint PRIMARY KEY,
    available   bigint      NOT NULL CHECK (available >= 0),
    pending     bigint      NOT NULL CHECK (pending >= 0),
    updated_at  timestamptz NOT NULL
);

-- 정산
CREATE TABLE settlements (
    id             bigserial PRIMARY KEY,
    seller_id      bigint NOT NULL,
    period_start   date   NOT NULL,
    period_end     date   NOT NULL,
    gross          bigint NOT NULL,
    fee_total      bigint NOT NULL,
    penalty_total  bigint NOT NULL CHECK (penalty_total >= 0),
    carry_in       bigint NOT NULL CHECK (carry_in <= 0),
    net            bigint NOT NULL,
    payout         bigint NOT NULL CHECK (payout >= 0),
    carry_out      bigint NOT NULL CHECK (carry_out <= 0),
    status         text   NOT NULL CHECK (status IN ('PAID','CARRIED')),
    paid_at        timestamptz,
    CHECK (net = gross - fee_total - penalty_total + carry_in),        -- 불변식 7
    CHECK (payout = greatest(net, 0) AND carry_out = least(net, 0)),
    CHECK ((status = 'PAID') = (payout > 0)),
    CHECK (period_end = period_start + 6)
);

CREATE TABLE settlement_items (
    id             bigserial PRIMARY KEY,
    settlement_id  bigint NOT NULL,
    order_item_id  bigint NOT NULL,
    amount         bigint NOT NULL,
    fee            bigint NOT NULL,
    status         text   NOT NULL CHECK (status IN ('INCLUDED','HELD','CLAWBACK')),
    CHECK ((status = 'INCLUDED' AND amount > 0 AND fee >= 0)
        OR (status = 'HELD'     AND amount = 0 AND fee = 0)
        OR (status = 'CLAWBACK' AND amount < 0 AND fee <= 0))
);

CREATE TABLE seller_penalties (
    id             bigserial PRIMARY KEY,
    seller_id      bigint      NOT NULL,
    order_item_id  bigint      NOT NULL,
    amount         bigint      NOT NULL CHECK (amount > 0),
    settlement_id  bigint,
    created_at     timestamptz NOT NULL
);

-- ===== 단계 A 새 테이블 (ADR-0007 §3) — 기존 테이블은 읽기만 하고 여기에만 쓴다 =====

-- A1 리뷰·평점
CREATE TABLE reviews (
    id             bigserial PRIMARY KEY,
    order_item_id  bigint      NOT NULL,
    buyer_id       bigint      NOT NULL,
    seller_id      bigint      NOT NULL,
    rating         smallint    NOT NULL CHECK (rating BETWEEN 1 AND 5),
    content        text        NOT NULL,
    status         text        NOT NULL CHECK (status IN ('VISIBLE','HIDDEN','DELETED')),
    hidden_reason  text        CHECK (hidden_reason IN ('DISPUTE','ADMIN')),
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    CHECK ((status = 'HIDDEN') = (hidden_reason IS NOT NULL))
);

CREATE TABLE seller_ratings (
    seller_id        bigint PRIMARY KEY,
    review_count     int         NOT NULL CHECK (review_count >= 0),
    rating_sum       int         NOT NULL CHECK (rating_sum >= 0),
    recalculated_at  timestamptz NOT NULL,
    CHECK (rating_sum BETWEEN review_count AND 5 * review_count)
);

-- A2 판매자 등급·수수료 리베이트
CREATE TABLE seller_grades (
    id              bigserial PRIMARY KEY,
    seller_id       bigint      NOT NULL,
    grade           text        NOT NULL CHECK (grade IN ('STANDARD','PLUS','PRO','MASTER')),
    effective_from  date        NOT NULL,
    basis_amount    bigint      NOT NULL CHECK (basis_amount >= 0),
    restricted      boolean     NOT NULL,
    created_at      timestamptz NOT NULL
);

CREATE TABLE fee_rebates (
    id             bigserial PRIMARY KEY,
    seller_id      bigint      NOT NULL,
    month          date        NOT NULL CHECK (extract(day FROM month) = 1),
    grade          text        NOT NULL CHECK (grade IN ('STANDARD','PLUS','PRO','MASTER')),
    fee_base       bigint      NOT NULL,
    rate_permille  int         NOT NULL CHECK (rate_permille IN (0, 50, 100, 150)),
    amount         bigint      NOT NULL CHECK (amount >= 0),
    status         text        NOT NULL CHECK (status IN ('PAID','ZERO')),
    paid_at        timestamptz,
    created_at     timestamptz NOT NULL,
    CHECK ((status = 'PAID') = (amount > 0)),
    CHECK ((status = 'PAID') = (paid_at IS NOT NULL))
);

-- A3 지급 계좌 변경 요청·월간 정산서
CREATE TABLE payout_account_requests (
    id                 bigserial PRIMARY KEY,
    user_id            bigint      NOT NULL,
    bank_code          text        NOT NULL,
    account_no_masked  text        NOT NULL,
    status             text        NOT NULL CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    reject_reason      text,
    requested_at       timestamptz NOT NULL,
    decided_at         timestamptz,
    CHECK ((status = 'PENDING') = (decided_at IS NULL)),
    CHECK ((status = 'REJECTED') = (reject_reason IS NOT NULL))
);

CREATE TABLE monthly_statements (
    id                bigserial PRIMARY KEY,
    seller_id         bigint      NOT NULL,
    month             date        NOT NULL CHECK (extract(day FROM month) = 1),
    settlement_count  int         NOT NULL CHECK (settlement_count > 0),
    gross             bigint      NOT NULL,                                -- CLAWBACK 달은 음수일 수 있다
    fee_total         bigint      NOT NULL,
    penalty_total     bigint      NOT NULL CHECK (penalty_total >= 0),
    payout_total      bigint      NOT NULL CHECK (payout_total >= 0),
    carried_count     int         NOT NULL CHECK (carried_count >= 0),
    held_count        int         NOT NULL CHECK (held_count >= 0),
    last_carry_out    bigint      NOT NULL CHECK (last_carry_out <= 0),
    created_at        timestamptz NOT NULL
);

-- A4·A5 알림
CREATE TABLE notifications (
    id          bigserial PRIMARY KEY,
    user_id     bigint      NOT NULL,
    kind        text        NOT NULL CHECK (kind IN ('PAYMENT_DUE_SOON','SHIPMENT_DUE_SOON','DISPUTE_WINDOW_CLOSING',
                                                     'SETTLEMENT_PAID','PURCHASE_RESTRICTED','SALE_RESTRICTED','PRICE_ALERT')),
    ref_id      bigint      NOT NULL,
    dedupe_key  text        NOT NULL,
    created_at  timestamptz NOT NULL,
    read_at     timestamptz
);

-- A5 위시리스트·가격 알림
CREATE TABLE wishlists (
    id               bigserial PRIMARY KEY,
    user_id          bigint      NOT NULL,
    product_size_id  bigint      NOT NULL,
    created_at       timestamptz NOT NULL
);

CREATE TABLE price_alerts (
    id                bigserial PRIMARY KEY,
    user_id           bigint      NOT NULL,
    product_size_id   bigint      NOT NULL,
    target_price      bigint      NOT NULL CHECK (target_price > 0),
    status            text        NOT NULL CHECK (status IN ('ACTIVE','TRIGGERED','EXPIRED','CANCELLED')),
    expires_at        timestamptz NOT NULL,
    triggered_bid_id  bigint,
    closed_at         timestamptz,
    created_at        timestamptz NOT NULL,
    CHECK (expires_at > created_at),
    CHECK ((status = 'ACTIVE') = (closed_at IS NULL)),
    CHECK ((status = 'TRIGGERED') = (triggered_bid_id IS NOT NULL))
);

-- A6 고객 문의
CREATE TABLE inquiries (
    id                 bigserial PRIMARY KEY,
    user_id            bigint      NOT NULL,
    order_item_id      bigint,
    category           text        NOT NULL CHECK (category IN ('ORDER','PAYMENT','SETTLEMENT','ACCOUNT','OTHER')),
    title              text        NOT NULL,
    status             text        NOT NULL CHECK (status IN ('OPEN','ANSWERED','CLOSED')),
    close_reason       text        CHECK (close_reason IN ('USER','AUTO')),
    created_at         timestamptz NOT NULL,
    sla_due_at         timestamptz NOT NULL,
    first_answered_at  timestamptz,
    answered_at        timestamptz,
    closed_at          timestamptz,
    CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)),
    CHECK ((status = 'CLOSED') = (close_reason IS NOT NULL)),
    CHECK (sla_due_at = created_at + interval '48 hours')
);

CREATE TABLE inquiry_messages (
    id          bigserial PRIMARY KEY,
    inquiry_id  bigint      NOT NULL,
    author      text        NOT NULL CHECK (author IN ('USER','ADMIN')),
    body        text        NOT NULL,
    created_at  timestamptz NOT NULL
);

-- A7 세금계산서 (ADR-0007 §10.1 kind별 모양)
CREATE TABLE tax_invoices (
    id              bigserial PRIMARY KEY,
    settlement_id   bigint      NOT NULL,
    seller_id       bigint      NOT NULL,
    kind            text        NOT NULL CHECK (kind IN ('ISSUE','CANCEL','HOLD')),
    status          text        NOT NULL CHECK (status IN ('ISSUED','CANCELLED','DONE')),
    supply_amount   bigint      NOT NULL,
    vat_amount      bigint      NOT NULL,
    total_amount    bigint      NOT NULL,
    ref_invoice_id  bigint,
    issued_at       timestamptz NOT NULL,
    CHECK (total_amount = supply_amount + vat_amount),
    CHECK ((kind = 'ISSUE'  AND status IN ('ISSUED','CANCELLED') AND supply_amount >= 0 AND vat_amount = supply_amount * 10 / 100)
        OR (kind = 'CANCEL' AND status = 'DONE' AND supply_amount <= 0 AND vat_amount <= 0 AND ref_invoice_id IS NOT NULL)
        OR (kind = 'HOLD'   AND status = 'DONE' AND supply_amount = 0 AND vat_amount = 0 AND ref_invoice_id IS NULL))
);

-- A8 시세
CREATE TABLE price_stats_daily (
    id               bigserial PRIMARY KEY,
    product_size_id  bigint      NOT NULL,
    stat_date        date        NOT NULL,
    trade_count      int         NOT NULL CHECK (trade_count > 0),
    min_price        bigint      NOT NULL CHECK (min_price > 0),
    max_price        bigint      NOT NULL CHECK (max_price > 0),
    median_price     bigint      NOT NULL CHECK (median_price > 0),
    created_at       timestamptz NOT NULL,
    CHECK (min_price <= median_price AND median_price <= max_price)
);

-- A9 관리자 감사 로그
CREATE TABLE admin_audit_logs (
    id          bigserial PRIMARY KEY,
    action      text        NOT NULL CHECK (action IN ('REVIEW_HIDE','PAYOUT_ACCOUNT_DECISION','INQUIRY_ANSWER','TAX_INVOICE_CORRECTION',
                                                       'TRACKING_EVENT','INBOUND_SLOT_CREATE','INBOUND_CHECK_IN')),
    target_id   bigint      NOT NULL,
    detail      text,
    created_at  timestamptz NOT NULL
);

-- A10 판매자 프로필
CREATE TABLE seller_profiles (
    user_id        bigint PRIMARY KEY,
    shop_name      text        NOT NULL,
    intro          text        NOT NULL,
    vacation_from  date,
    vacation_to    date,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    CHECK ((vacation_from IS NULL) = (vacation_to IS NULL)),
    CHECK (vacation_from <= vacation_to)
);

-- A11 배송 추적·지연 표시
CREATE TABLE shipment_tracking_events (
    id             bigserial PRIMARY KEY,
    order_item_id  bigint      NOT NULL,
    carrier        text        NOT NULL CHECK (carrier IN ('CJ','HANJIN','LOTTE','POST')),
    tracking_no    text        NOT NULL,
    event_type     text        NOT NULL CHECK (event_type IN ('PICKED_UP','IN_TRANSIT','OUT_FOR_DELIVERY','DELIVERED')),
    occurred_at    timestamptz NOT NULL,
    received_at    timestamptz NOT NULL,
    CHECK (occurred_at <= received_at)
);

CREATE TABLE shipment_delays (
    id             bigserial PRIMARY KEY,
    order_item_id  bigint      NOT NULL,
    kind           text        NOT NULL CHECK (kind IN ('SHIPMENT_OVERDUE','DELIVERY_LATE')),
    detected_at    timestamptz NOT NULL
);

-- A12 입고 예약
CREATE TABLE inbound_slots (
    id          bigserial PRIMARY KEY,
    slot_start  timestamptz NOT NULL CHECK (mod(extract(epoch FROM slot_start), 3600) = 0),   -- 정시(세션 TimeZone과 무관 — KST는 정시 오프셋, 소수 초도 거부)
    capacity    int         NOT NULL CHECK (capacity > 0),
    created_at  timestamptz NOT NULL
);

CREATE TABLE inbound_reservations (
    id             bigserial PRIMARY KEY,
    slot_id        bigint      NOT NULL,
    seller_id      bigint      NOT NULL,
    order_item_id  bigint      NOT NULL,
    status         text        NOT NULL CHECK (status IN ('RESERVED','CHECKED_IN','CANCELLED','NO_SHOW')),
    created_at     timestamptz NOT NULL,
    closed_at      timestamptz,
    CHECK ((status = 'RESERVED') = (closed_at IS NULL))
);
