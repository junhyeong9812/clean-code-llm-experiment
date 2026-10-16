-- 합성 데이터 불변식 검증 (ADR-0002 §4·§5·§6.1·§9 — §9가 §6.1보다 우선).
-- 실행: psql -v ON_ERROR_STOP=1 -f tools/seedgen/verify.sql
--
-- 원칙
--  * tables.sql의 CHECK와 constraints.sql의 UNIQUE·FK가 이미 막는 규칙(불변식 1·6·7, 행 단위 범위)은 다시 검사하지 않는다.
--    대신 그 제약들이 실제로 걸려 있는지를 첫 검사(schema_constraints_present)로 확인한다(불변식 9의 FK 부분).
--  * 행 간·테이블 간 규칙만 검사한다. 대용량을 위해 집계 + 해시 조인 위주로 쓰고, 공용 중간 결과는 임시 테이블에 담는다.
--  * 임시 테이블은 이 세션에만 있고 ON COMMIT DROP + 마지막 ROLLBACK으로 사라진다. 실제 테이블에는 쓰지 않는다.
--  * 각 검사 = 한 행 (check_name, violations). 위반 합이 0이 아니면 마지막 DO 블록이 RAISE EXCEPTION → psql exit code ≠ 0.
--  * 시각 기준: 정산 주(월~일)·지급(수 00:00)은 Asia/Seoul.

\set ON_ERROR_STOP on
\pset pager off

BEGIN;
SET LOCAL work_mem = '512MB';
SET LOCAL enable_parallel_hash = off;   -- 병렬 해시는 /dev/shm(DSM)에 해시 테이블을 둔다: 본 규모에서 1GB를 넘어 실패(실측)
SET LOCAL TIME ZONE 'Asia/Seoul';

-- 결제 이후 항목 상태(§9.4에서 AWAITING_PAYMENT·PAYMENT_FAILED를 뺀 것)
-- 환불된 항목 상태 = CANCELLED_BY_BUYER·CANCELLED_SELLER_FAULT·REFUNDED_AFTER_CONFIRM

------------------------------------------------------------------------------------------
-- 공용 중간 결과
------------------------------------------------------------------------------------------

-- 주문 항목 + 주문 + 체결 + 카테고리 수수료율, 주문 안 순번(id 순 = '첫 항목'·'앞 항목')
CREATE TEMP TABLE v_oi ON COMMIT DROP AS
SELECT oi.id, oi.order_id, oi.trade_id, oi.price, oi.buyer_fee, oi.card_share, oi.point_share, oi.shipping_share,
       oi.status, oi.paid_at, oi.shipped_at, oi.inspected_at, oi.delivered_at, oi.confirmed_at, oi.cancelled_at,
       (oi.price + oi.buyer_fee + oi.shipping_share)                         AS b,
       oi.status NOT IN ('AWAITING_PAYMENT','PAYMENT_FAILED')                 AS is_paid,
       oi.status IN ('CANCELLED_BY_BUYER','CANCELLED_SELLER_FAULT')           AS is_cancel,
       oi.status IN ('CANCELLED_BY_BUYER','CANCELLED_SELLER_FAULT','REFUNDED_AFTER_CONFIRM') AS is_refunded,
       o.status AS o_status, o.buyer_id AS o_buyer, o.card_amount AS o_card, o.point_used AS o_point,
       o.shipping_fee AS o_ship, o.paid_at AS o_paid_at, o.created_at AS o_created_at,
       (o.item_total + o.buyer_fee_total + o.shipping_fee)                    AS o_total,
       t.price AS t_price, t.status AS t_status, t.buyer_id AS t_buyer, t.seller_id AS t_seller,
       t.matched_at AS t_matched_at, t.payment_due_at AS t_due,
       c.sale_fee_rate,
       row_number() OVER (PARTITION BY oi.order_id ORDER BY oi.id)            AS rn
FROM order_items oi
JOIN orders o         ON o.id = oi.order_id
JOIN trades t         ON t.id = oi.trade_id
JOIN product_sizes ps ON ps.id = t.product_size_id
JOIN products p       ON p.id = ps.product_id
JOIN categories c     ON c.id = p.category_id;
ANALYZE v_oi;

-- 주문별 항목 집계
CREATE TEMP TABLE v_ord ON COMMIT DROP AS
SELECT o.id, o.status, o.item_total, o.buyer_fee_total, o.shipping_fee, o.point_used, o.card_amount, o.paid_at, o.created_at,
       o.status IN ('PAID','PARTIALLY_CANCELLED','CANCELLED')                  AS is_paid,
       count(i.id)                                       AS n,
       coalesce(sum(i.price), 0)                         AS s_price,
       coalesce(sum(i.buyer_fee), 0)                     AS s_fee,
       coalesce(sum(i.card_share), 0)                    AS s_card,
       coalesce(sum(i.point_share), 0)                   AS s_point,
       count(*) FILTER (WHERE i.is_cancel)               AS n_cancel,
       count(*) FILTER (WHERE i.is_refunded)             AS n_refunded,
       count(*) FILTER (WHERE i.status = 'AWAITING_PAYMENT') AS n_awaiting,
       count(*) FILTER (WHERE i.status = 'PAYMENT_FAILED')   AS n_failed
FROM orders o
LEFT JOIN v_oi i ON i.order_id = o.id
GROUP BY o.id;
ANALYZE v_ord;

-- 환불 집계(항목별)
CREATE TEMP TABLE v_ref_item ON COMMIT DROP AS
SELECT order_item_id, count(*) AS n, sum(card_amount) AS card, sum(point_amount) AS point,
       sum(buyer_fee_amount) AS fee, sum(shipping_amount) AS ship,
       min(fault) AS fault_min, max(fault) AS fault_max
FROM refunds GROUP BY order_item_id;
ANALYZE v_ref_item;

-- 원장 + 귀속 주문(order_id가 없으면 order_item의 주문)
CREATE TEMP TABLE v_led ON COMMIT DROP AS
SELECT l.id, l.user_id, l.kind, l.status, l.amount, l.remaining, l.expires_at, l.order_item_id, l.created_at,
       coalesce(l.order_id, oi.order_id) AS order_id
FROM point_ledger l
LEFT JOIN order_items oi ON oi.id = l.order_item_id;
ANALYZE v_led;

-- 배분 + 양쪽 원장 항목
CREATE TEMP TABLE v_alloc ON COMMIT DROP AS
SELECT a.id, a.entry_id, a.earn_entry_id, a.amount,
       e.kind AS e_kind, e.status AS e_status, e.user_id AS e_user, e.created_at AS e_created_at,
       e.order_id AS e_order, e.order_item_id AS e_item,
       r.kind AS r_kind, r.status AS r_status, r.user_id AS r_user, r.expires_at AS r_expires_at,
       r.order_item_id AS r_item
FROM point_allocations a
JOIN v_led e ON e.id = a.entry_id
JOIN v_led r ON r.id = a.earn_entry_id;
ANALYZE v_alloc;

-- 정산 마감 시각(이 주의 정산이 돈 시각 = period_end 다음날 00:00 KST)과 전역 마지막 마감
CREATE TEMP TABLE v_set ON COMMIT DROP AS
SELECT s.*, ((s.period_end + 1)::timestamp AT TIME ZONE 'Asia/Seoul') AS cutoff,
       lag(s.carry_out)    OVER w AS prev_carry_out,
       lead(s.period_start) OVER w AS next_period_start
FROM settlements s
WINDOW w AS (PARTITION BY s.seller_id ORDER BY s.period_start);
ANALYZE v_set;

CREATE TEMP TABLE v_global ON COMMIT DROP AS
SELECT max(cutoff) AS last_cutoff, max(period_start) AS last_period_start FROM v_set;

-- 항목별 정산 행 집계
CREATE TEMP TABLE v_si_item ON COMMIT DROP AS
SELECT order_item_id,
       count(*) FILTER (WHERE status = 'INCLUDED') AS n_inc,
       count(*) FILTER (WHERE status = 'HELD')     AS n_held,
       count(*) FILTER (WHERE status = 'CLAWBACK') AS n_claw
FROM settlement_items GROUP BY order_item_id;
ANALYZE v_si_item;

-- 입찰 이벤트: 입찰별 첫/마지막/연쇄 끊김
CREATE TEMP TABLE v_be ON COMMIT DROP AS
SELECT bid_id,
       count(*)                                                     AS n,
       (array_agg(from_status ORDER BY id))[1]                      AS first_from,
       (array_agg(to_status   ORDER BY id))[1]                      AS first_to,
       (array_agg(to_status   ORDER BY id DESC))[1]                 AS last_to,
       count(*) FILTER (WHERE rn > 1 AND from_status IS DISTINCT FROM prev_to)       AS n_chain_break,
       count(*) FILTER (WHERE rn > 1 AND created_at < prev_created)                  AS n_time_back,
       min(created_at)                                              AS min_created
FROM (SELECT be.*, row_number() OVER w AS rn, lag(to_status) OVER w AS prev_to, lag(created_at) OVER w AS prev_created
      FROM bid_events be WINDOW w AS (PARTITION BY bid_id ORDER BY id)) x
GROUP BY bid_id;
ANALYZE v_be;

-- 입찰의 ACTIVE 구간(이벤트 id 기준 — 이벤트 id는 처리 순서라 같은 초 안의 선후도 구분한다)
CREATE TEMP TABLE v_act ON COMMIT DROP AS
SELECT x.bid_id, x.s_eid, x.e_eid, x.s_at, b.side, b.price, b.created_at, b.expires_at, b.user_id, b.product_size_id
FROM (SELECT bid_id, to_status, id AS s_eid, created_at AS s_at, lead(id) OVER (PARTITION BY bid_id ORDER BY id) AS e_eid
      FROM bid_events) x
JOIN bids b ON b.id = x.bid_id
WHERE x.to_status = 'ACTIVE';
CREATE INDEX ON v_act (bid_id);
CREATE INDEX ON v_act (product_size_id, side, s_eid);
ANALYZE v_act;

-- 체결별 매칭 순간(MATCHED 이벤트 id), 테이커(ACTIVE 구간이 더 늦게 시작한 쪽)와 메이커
CREATE TEMP TABLE v_match ON COMMIT DROP AS
SELECT t.id AS trade_id, t.product_size_id, me.eid AS m_eid, me.eid2 AS m_eid2, t.matched_at AS m_at,
       CASE WHEN ba.s_eid > sa.s_eid THEN ba.bid_id ELSE sa.bid_id END AS taker_id,
       CASE WHEN ba.s_eid > sa.s_eid THEN 'BUY' ELSE 'SELL' END AS taker_side,
       CASE WHEN ba.s_eid > sa.s_eid THEN ba.user_id ELSE sa.user_id END AS taker_user,
       CASE WHEN ba.s_eid > sa.s_eid THEN sa.bid_id ELSE ba.bid_id END AS maker_id,
       CASE WHEN ba.s_eid > sa.s_eid THEN sa.price ELSE ba.price END AS maker_price,
       CASE WHEN ba.s_eid > sa.s_eid THEN sa.created_at ELSE ba.created_at END AS maker_created
FROM trades t
JOIN (SELECT trade_id, min(id) AS eid, max(id) AS eid2 FROM bid_events WHERE to_status = 'MATCHED' GROUP BY trade_id) me
  ON me.trade_id = t.id
JOIN v_act ba ON ba.bid_id = t.buy_bid_id  AND ba.e_eid IN (me.eid, me.eid2)
JOIN v_act sa ON sa.bid_id = t.sell_bid_id AND sa.e_eid IN (me.eid, me.eid2);
CREATE INDEX ON v_match (taker_id);
ANALYZE v_match;

------------------------------------------------------------------------------------------
-- 검사
------------------------------------------------------------------------------------------
CREATE TEMP TABLE v_result ON COMMIT DROP AS

-- [§6.1-9 FK, §6.1-6 §9.1] constraints.sql의 FK 34개(검증됨)·부분 UNIQUE 3개가 실제로 걸려 있나
SELECT 'schema_constraints_present' AS check_name,
       (abs(34 - (SELECT count(*) FROM pg_constraint
                  WHERE contype = 'f' AND convalidated AND connamespace = 'public'::regnamespace))
      + abs(3 - (SELECT count(*) FROM pg_indexes WHERE schemaname = 'public'
                 AND indexname IN ('trades_live_buy_bid_key','trades_live_sell_bid_key','order_items_live_trade_key')))
      -- 불변식 1·7을 맡은 CHECK가 걸려 있나(정의 문자열로 확인)
      + abs(1 - (SELECT count(*) FROM pg_constraint WHERE contype = 'c' AND conrelid = 'orders'::regclass
                 AND pg_get_constraintdef(oid) LIKE '%item_total + buyer_fee_total%'))
      + abs(2 - (SELECT count(*) FROM pg_constraint WHERE contype = 'c' AND conrelid = 'settlements'::regclass
                 AND (pg_get_constraintdef(oid) LIKE '%net = (((gross - fee_total) - penalty_total) + carry_in)%'
                      OR pg_get_constraintdef(oid) LIKE '%payout = GREATEST(net%'))))
       ::bigint AS violations

---------------- 주문·항목 금액 (§5.1·§6.1-2·§9.2) ----------------
UNION ALL -- 주문에 항목이 하나도 없음
SELECT 'order_has_items', count(*) FROM v_ord WHERE n = 0
UNION ALL -- [§6.1-2] Σ체결가 = item_total, Σ구매 수수료 = buyer_fee_total (모든 주문)
SELECT 'order_item_total_sum', count(*) FROM v_ord WHERE n > 0 AND (s_price <> item_total OR s_fee <> buyer_fee_total)
UNION ALL -- [§6.1-2] PAID 이상 주문: Σcard_share = card_amount, Σpoint_share = point_used
SELECT 'order_share_sum', count(*) FROM v_ord WHERE is_paid AND (s_card <> card_amount OR s_point <> point_used)
UNION ALL -- [§9.2] shipping_share: 첫 항목 = 배송비, 나머지 0 (→ Σshipping_share = shipping_fee)
SELECT 'order_shipping_share', count(*) FROM v_oi
 WHERE shipping_share <> CASE WHEN rn = 1 THEN o_ship ELSE 0 END
UNION ALL -- [§5.1] 배송비 = 항목 금액 합 ≥ 100,000이면 0, 아니면 3,000
SELECT 'order_shipping_fee_rule', count(*) FROM v_ord
 WHERE shipping_fee <> CASE WHEN item_total >= 100000 THEN 0 ELSE 3000 END
UNION ALL -- [§5.1] 항목 금액 = 체결가
SELECT 'item_price_eq_trade', count(*) FROM v_oi WHERE price <> t_price
UNION ALL -- [§5.1] 구매 수수료 = max(floor(체결가 × 3%), 1,000)
SELECT 'item_buyer_fee_rule', count(*) FROM v_oi WHERE buyer_fee <> greatest(price * 3 / 100, 1000)
UNION ALL -- [§9.2] 카드 몫 = floor(card_amount × b_i / 총액) + (0 또는 1원 잔차)  (PAID 이상)
SELECT 'item_card_share_floor', count(*) FROM v_oi
 WHERE o_status IN ('PAID','PARTIALLY_CANCELLED','CANCELLED')
   AND card_share - (o_card * b) / o_total NOT IN (0, 1)
UNION ALL -- [§9.2] 잔차 1원은 앞 항목부터: +1 받은 항목 앞에, +1을 못 받았는데 기준액 미만인 항목이 있으면 위반 (PAID 이상)
SELECT 'item_card_share_residual_order', count(*) FROM (
  SELECT order_id,
         min(rn) FILTER (WHERE card_share - (o_card * b) / o_total = 0 AND card_share < b) AS first_gap,
         max(rn) FILTER (WHERE card_share - (o_card * b) / o_total = 1)                    AS last_plus
  FROM v_oi WHERE o_status IN ('PAID','PARTIALLY_CANCELLED','CANCELLED')
  GROUP BY order_id) x
 WHERE first_gap < last_plus
UNION ALL -- [§5.1] 포인트 사용: 0 또는 (≥1,000P, 10P 단위, ≤ 총액 × 50%)
SELECT 'order_point_use_rule', count(*) FROM v_ord
 WHERE point_used > 0
   AND (point_used < 1000 OR point_used % 10 <> 0 OR point_used * 2 > item_total + buyer_fee_total + shipping_fee)
UNION ALL -- [§2] 주문 구매자 = 항목 체결의 구매자
SELECT 'order_item_trade_buyer', count(*) FROM v_oi WHERE o_buyer <> t_buyer

---------------- 주문·항목·체결 상태 일관 (§4.2·§4.3·§9.1·§9.4) ----------------
UNION ALL -- 주문 상태 ↔ 항목 상태
SELECT 'order_status_vs_items', count(*) FROM v_ord
 WHERE n > 0 AND (
       (status IN ('PENDING','PAYING','UNKNOWN') AND n_awaiting <> n)
    OR (status = 'FAILED' AND n_failed <> n)
    OR (is_paid AND n_awaiting + n_failed > 0)
    OR (status = 'PAID' AND n_refunded > 0)
    OR (status = 'PARTIALLY_CANCELLED' AND NOT (n_refunded BETWEEN 1 AND n - 1))
    OR (status = 'CANCELLED' AND n_refunded <> n)
    OR (n_refunded > 0 AND status NOT IN ('PARTIALLY_CANCELLED','CANCELLED')))
UNION ALL -- 주문 paid_at ↔ 상태, paid_at ≥ created_at
SELECT 'order_paid_at', count(*) FROM v_ord
 WHERE is_paid <> (paid_at IS NOT NULL) OR paid_at < created_at
UNION ALL -- [§3·§4.2] 결제는 체결 뒤, 결제 기한 안 (PAID 이상)
SELECT 'order_paid_within_due', count(*) FROM v_oi
 WHERE o_created_at < t_matched_at
    OR (o_status IN ('PAID','PARTIALLY_CANCELLED','CANCELLED') AND o_paid_at > t_due)
UNION ALL -- 항목 paid_at = 주문 paid_at (결제 이후 항목), 결제 전 항목은 NULL
SELECT 'item_paid_at_eq_order', count(*) FROM v_oi
 WHERE (is_paid AND paid_at IS DISTINCT FROM o_paid_at) OR (NOT is_paid AND paid_at IS NOT NULL)
UNION ALL -- [§4.2] 체결 PAID ⇔ 결제 이후 항목이 있음
SELECT 'trade_status_vs_items', count(*) FROM trades t
 LEFT JOIN (SELECT trade_id, bool_or(is_paid) AS has_paid FROM v_oi GROUP BY trade_id) x ON x.trade_id = t.id
 WHERE (t.status = 'PAID') <> coalesce(x.has_paid, false)

---------------- 항목 상태-시각 (§4.4) ----------------
UNION ALL -- 상태별 필수/금지 시각 (§9.5: REFUNDED_AFTER_CONFIRM의 cancelled_at = 분쟁 인정 시각)
SELECT 'item_status_timestamps', count(*) FROM v_oi
 WHERE CASE status
   WHEN 'AWAITING_PAYMENT'  THEN num_nonnulls(paid_at, shipped_at, inspected_at, delivered_at, confirmed_at, cancelled_at) <> 0
   WHEN 'PAYMENT_FAILED'    THEN num_nonnulls(paid_at, shipped_at, inspected_at, delivered_at, confirmed_at, cancelled_at) <> 0
   WHEN 'PAID'              THEN paid_at IS NULL OR num_nonnulls(shipped_at, inspected_at, delivered_at, confirmed_at, cancelled_at) <> 0
   WHEN 'SHIPPED_TO_CENTER' THEN num_nulls(paid_at, shipped_at) <> 0 OR num_nonnulls(inspected_at, delivered_at, confirmed_at, cancelled_at) <> 0
   WHEN 'INSPECTING'        THEN num_nulls(paid_at, shipped_at) <> 0 OR num_nonnulls(inspected_at, delivered_at, confirmed_at, cancelled_at) <> 0
   WHEN 'DELIVERING'        THEN num_nulls(paid_at, shipped_at, inspected_at) <> 0 OR num_nonnulls(delivered_at, confirmed_at, cancelled_at) <> 0
   WHEN 'DELIVERED'         THEN num_nulls(paid_at, shipped_at, inspected_at, delivered_at) <> 0 OR num_nonnulls(confirmed_at, cancelled_at) <> 0
   WHEN 'CONFIRMED'         THEN num_nulls(paid_at, shipped_at, inspected_at, delivered_at, confirmed_at) <> 0 OR cancelled_at IS NOT NULL
   WHEN 'DISPUTED'          THEN num_nulls(paid_at, shipped_at, inspected_at, delivered_at, confirmed_at) <> 0 OR cancelled_at IS NOT NULL
   WHEN 'REFUNDED_AFTER_CONFIRM' THEN num_nulls(paid_at, shipped_at, inspected_at, delivered_at, confirmed_at, cancelled_at) <> 0
        OR NOT EXISTS (SELECT 1 FROM disputes d WHERE d.order_item_id = v_oi.id AND d.status = 'ACCEPTED'
                       AND d.resolved_at = v_oi.cancelled_at)
   WHEN 'CANCELLED_BY_BUYER' THEN num_nulls(paid_at, cancelled_at) <> 0 OR num_nonnulls(shipped_at, inspected_at, delivered_at, confirmed_at) <> 0
   WHEN 'CANCELLED_SELLER_FAULT' THEN num_nulls(paid_at, cancelled_at) <> 0 OR num_nonnulls(delivered_at, confirmed_at) <> 0
                                      OR (shipped_at IS NULL) <> (inspected_at IS NULL)
   END
UNION ALL -- 시각 순서: paid ≤ shipped ≤ inspected ≤ delivered ≤ confirmed, cancelled ≥ 앞선 시각들
SELECT 'item_timestamp_order', count(*) FROM v_oi
 WHERE (shipped_at < paid_at) IS TRUE OR (inspected_at < shipped_at) IS TRUE OR (delivered_at < inspected_at) IS TRUE
    OR (confirmed_at < delivered_at) IS TRUE
    OR (cancelled_at < greatest(paid_at, shipped_at, inspected_at, delivered_at, confirmed_at)) IS TRUE
UNION ALL -- [§4.4] 발송 기한 결제+3일, 미발송 귀책 취소는 기한 뒤, 자동 확정 배송 완료+7일 이내
SELECT 'item_deadlines', count(*) FROM v_oi
 WHERE (shipped_at > paid_at + interval '3 days') IS TRUE
    OR (status = 'CANCELLED_SELLER_FAULT' AND shipped_at IS NULL AND cancelled_at < paid_at + interval '3 days')
    OR (confirmed_at > delivered_at + interval '7 days') IS TRUE

---------------- 검수·분쟁 (§4.4) ----------------
UNION ALL -- 검수: DELIVERING 이후는 PASS 1건, 검수된 귀책 취소는 FAIL, 그 외 상태는 검수 없음, 시각 일치
SELECT 'inspection_consistency', count(*) FROM v_oi i LEFT JOIN inspections n ON n.order_item_id = i.id
 WHERE CASE
   WHEN i.status IN ('DELIVERING','DELIVERED','CONFIRMED','DISPUTED','REFUNDED_AFTER_CONFIRM')
        THEN n.result IS DISTINCT FROM 'PASS'
   WHEN i.status = 'CANCELLED_SELLER_FAULT' AND i.inspected_at IS NOT NULL
        THEN n.result IS DISTINCT FROM 'FAIL'
   ELSE n.id IS NOT NULL END
    OR (n.id IS NOT NULL AND n.inspected_at IS DISTINCT FROM i.inspected_at)
UNION ALL -- 분쟁: DISPUTED ⇔ OPEN 1건, REFUNDED_AFTER_CONFIRM ⇔ ACCEPTED 1건, 확정된 항목에만, 확정 후 14일 안 접수
SELECT 'dispute_consistency', count(*) FROM v_oi i
 LEFT JOIN (SELECT order_item_id,
                   count(*) FILTER (WHERE status = 'OPEN')     AS n_open,
                   count(*) FILTER (WHERE status = 'ACCEPTED') AS n_acc,
                   count(*) AS n, min(opened_at) AS min_open, max(opened_at) AS max_open
            FROM disputes GROUP BY order_item_id) d ON d.order_item_id = i.id
 WHERE (i.status = 'DISPUTED') <> (coalesce(d.n_open, 0) = 1)
    OR coalesce(d.n_open, 0) > 1
    OR (i.status = 'REFUNDED_AFTER_CONFIRM') <> (coalesce(d.n_acc, 0) = 1)
    OR coalesce(d.n_acc, 0) > 1
    OR (coalesce(d.n, 0) > 0 AND i.status NOT IN ('CONFIRMED','DISPUTED','REFUNDED_AFTER_CONFIRM'))
    OR (d.min_open < i.confirmed_at) IS TRUE
    OR (d.max_open > i.confirmed_at + interval '14 days') IS TRUE

---------------- 환불 (§5.3·§6.1-3·§9.2) ----------------
UNION ALL -- 환불 행 ⇔ 환불 상태(1건), fault: BUYER/SELLER/DISPUTE가 상태와 일치
SELECT 'refund_status_match', count(*) FROM v_oi i LEFT JOIN v_ref_item r ON r.order_item_id = i.id
 WHERE (i.is_refunded <> (r.n IS NOT NULL)) OR r.n > 1
    OR (r.n IS NOT NULL AND (r.fault_min <> r.fault_max OR r.fault_min <> CASE i.status
          WHEN 'CANCELLED_BY_BUYER' THEN 'BUYER' WHEN 'CANCELLED_SELLER_FAULT' THEN 'SELLER'
          WHEN 'REFUNDED_AFTER_CONFIRM' THEN 'DISPUTE' END))
UNION ALL -- [§9.2] 항목 환불액 = 체결가 + 구매 수수료 + 이 환불에 실린 배송비, buyer_fee_amount = 항목 구매 수수료
SELECT 'refund_item_amount', count(*) FROM v_oi i JOIN v_ref_item r ON r.order_item_id = i.id
 WHERE r.card + r.point <> i.price + i.buyer_fee + r.ship OR r.fee <> i.buyer_fee
UNION ALL -- [§9.2] 항목별 카드 환불 = 카드 몫 − (첫 항목이면 배송비 카드 부분) + (이 환불이 배송비를 포함하면 배송비 카드 부분). 항목당 환불 1건
SELECT 'refund_item_card_point', count(*) FROM v_oi i
 JOIN v_ref_item r ON r.order_item_id = i.id
 JOIN (SELECT order_id, o_ship - least(o_ship - (o_ship * card_share) / b, point_share) AS ship_card
       FROM v_oi WHERE rn = 1) s ON s.order_id = i.order_id
 WHERE r.n <> 1
    OR r.card <> i.card_share - CASE WHEN i.rn = 1 THEN s.ship_card ELSE 0 END
                              + CASE WHEN r.ship > 0 THEN s.ship_card ELSE 0 END
UNION ALL -- [§6.1-3] 주문별 Σ환불(card+point) ≤ 결제액, 모든 항목 환불이면 정확히 같음
SELECT 'order_refund_total', count(*) FROM v_ord o
 JOIN (SELECT i.order_id, sum(r.card) AS card, sum(r.point) AS point, sum(r.ship) AS ship
       FROM v_ref_item r JOIN v_oi i ON i.id = r.order_item_id GROUP BY i.order_id) x ON x.order_id = o.id
 WHERE x.card + x.point > o.card_amount + o.point_used
    OR (o.n_refunded = o.n AND x.card + x.point <> o.card_amount + o.point_used)
UNION ALL -- [§5.3·§9.2] 카드·포인트 각각: Σ환불 ≤ 결제분, 모든 항목 환불이면 각각 같음
SELECT 'order_refund_card_point_split', count(*) FROM v_ord o
 JOIN (SELECT i.order_id, sum(r.card) AS card, sum(r.point) AS point
       FROM v_ref_item r JOIN v_oi i ON i.id = r.order_item_id GROUP BY i.order_id) x ON x.order_id = o.id
 WHERE x.card > o.card_amount OR x.point > o.point_used
    OR (o.n_refunded = o.n AND (x.card <> o.card_amount OR x.point <> o.point_used))
UNION ALL -- [§9.2] 배송비 환불: Σ ≤ 배송비, 모든 항목 환불이면 = 배송비
SELECT 'order_refund_shipping', count(*) FROM v_ord o
 JOIN (SELECT i.order_id, sum(r.ship) AS ship
       FROM v_ref_item r JOIN v_oi i ON i.id = r.order_item_id GROUP BY i.order_id) x ON x.order_id = o.id
 WHERE x.ship > o.shipping_fee OR (o.n_refunded = o.n AND x.ship <> o.shipping_fee)
UNION ALL -- [§5.3·§9.2] 주문별 Σ환불 포인트 = Σ RESTORE 금액
SELECT 'refund_points_restored', count(*) FROM (
  SELECT coalesce(a.order_id, b.order_id) AS order_id
  FROM (SELECT i.order_id, sum(r.point) AS p FROM v_ref_item r JOIN v_oi i ON i.id = r.order_item_id
        GROUP BY i.order_id HAVING sum(r.point) > 0) a
  FULL JOIN (SELECT order_id, sum(amount) AS p FROM v_led WHERE kind = 'RESTORE' GROUP BY order_id) b
    ON b.order_id = a.order_id
  WHERE a.p IS DISTINCT FROM b.p) x
UNION ALL -- 주문별 Σ승인된 PG CANCEL 금액 = Σ환불 카드 금액
SELECT 'refund_card_vs_pg_cancel', count(*) FROM (
  SELECT 1
  FROM (SELECT i.order_id, sum(r.card) AS c FROM v_ref_item r JOIN v_oi i ON i.id = r.order_item_id
        GROUP BY i.order_id HAVING sum(r.card) > 0) a
  FULL JOIN (SELECT order_id, sum(amount) AS c FROM pg_transactions
             WHERE kind = 'CANCEL' AND status = 'APPROVED' GROUP BY order_id) b ON b.order_id = a.order_id
  WHERE a.c IS DISTINCT FROM b.c) x

---------------- PG (§4.3·§6.1-9) ----------------
UNION ALL -- [§9.5] PAID 이상 주문: 승인 정확히 1건(APPROVE 승인, 또는 앞선 APPROVE UNKNOWN 뒤 INQUIRY 승인), 금액 = card_amount
SELECT 'pg_approve_per_order', count(*) FROM v_ord o
 LEFT JOIN (SELECT p.order_id, count(*) AS n, sum(p.amount) AS amt FROM pg_transactions p
            WHERE p.status = 'APPROVED'
              AND (p.kind = 'APPROVE'
                   OR (p.kind = 'INQUIRY' AND EXISTS (SELECT 1 FROM pg_transactions u WHERE u.order_id = p.order_id
                       AND u.kind = 'APPROVE' AND u.status = 'UNKNOWN' AND u.requested_at <= p.requested_at)))
            GROUP BY p.order_id) p ON p.order_id = o.id
 WHERE (o.is_paid AND (p.n IS DISTINCT FROM 1 OR p.amt IS DISTINCT FROM o.card_amount))
    OR (NOT o.is_paid AND p.n IS NOT NULL)
UNION ALL -- [§6.1-9·§9.5] 결제 이후 항목에는 그 주문의 승인(APPROVE 승인 또는 UNKNOWN 뒤 INQUIRY 승인)이 있음
SELECT 'paid_item_has_approved_pg', count(*) FROM v_oi i
 WHERE i.is_paid AND NOT EXISTS (SELECT 1 FROM pg_transactions p
                                  WHERE p.order_id = i.order_id AND p.status = 'APPROVED'
                                    AND (p.kind = 'APPROVE' OR (p.kind = 'INQUIRY' AND EXISTS (
                                         SELECT 1 FROM pg_transactions u WHERE u.order_id = p.order_id
                                           AND u.kind = 'APPROVE' AND u.status = 'UNKNOWN'))))

---------------- 포인트 (§4.5·§5.2·§6.1-4·5·§9.2) ----------------
UNION ALL -- [§6.1-4] point_balances = 원장 합 (행 없는 사용자는 원장 합이 둘 다 0)
SELECT 'point_balance_cache', count(*) FROM (
  SELECT 1
  FROM point_balances pb
  FULL JOIN (SELECT user_id,
                    sum(remaining) FILTER (WHERE status = 'AVAILABLE') AS avail,
                    sum(amount)    FILTER (WHERE status = 'PENDING')   AS pend
             FROM v_led WHERE kind = 'EARN' GROUP BY user_id) l ON l.user_id = pb.user_id
  WHERE coalesce(pb.available, 0) <> coalesce(l.avail, 0) OR coalesce(pb.pending, 0) <> coalesce(l.pend, 0)) x
UNION ALL -- [§9.2·§9.5] 비-EARN 항목의 배분 합 = 그 금액, RELEASED USE는 배분 없음
SELECT 'ledger_allocation_sum', count(*) FROM v_led l
 LEFT JOIN (SELECT entry_id, sum(amount) AS s FROM point_allocations GROUP BY entry_id) a ON a.entry_id = l.id
 WHERE l.kind <> 'EARN'
   AND CASE WHEN l.kind = 'USE' AND l.status = 'RELEASED' THEN coalesce(a.s, 0) <> 0
            ELSE coalesce(a.s, 0) <> l.amount END
UNION ALL -- [§9.2] AVAILABLE EARN remaining = amount − Σ(USE(RELEASED 제외)·EXPIRE·REVOKE) + Σ(RESTORE)
SELECT 'earn_remaining_formula', count(*) FROM v_led l
 LEFT JOIN (SELECT earn_entry_id,
                   sum(amount) FILTER (WHERE e_kind IN ('EXPIRE','REVOKE') OR (e_kind = 'USE' AND e_status <> 'RELEASED')) AS out_amt,
                   sum(amount) FILTER (WHERE e_kind = 'RESTORE') AS in_amt
            FROM v_alloc GROUP BY earn_entry_id) a ON a.earn_entry_id = l.id
 WHERE l.kind = 'EARN' AND l.status = 'AVAILABLE'
   AND l.remaining <> l.amount - coalesce(a.out_amt, 0) + coalesce(a.in_amt, 0)
UNION ALL -- [§4.5] PENDING·CANCELLED EARN은 손대지 않음: 배분 없음, PENDING remaining = amount
SELECT 'earn_non_available_untouched', count(*) FROM v_led l
 WHERE l.kind = 'EARN' AND l.status <> 'AVAILABLE'
   AND ((l.status = 'PENDING' AND l.remaining <> l.amount)
        OR EXISTS (SELECT 1 FROM point_allocations a WHERE a.earn_entry_id = l.id))
UNION ALL -- 배분 모양: entry는 비-EARN, earn_entry는 EARN, 같은 사용자
SELECT 'allocation_shape', count(*) FROM v_alloc
 WHERE e_kind = 'EARN' OR r_kind <> 'EARN' OR e_user <> r_user
UNION ALL -- [§4.5] USE는 만료 전 묶음에서, EXPIRE는 만료된 묶음에서
SELECT 'allocation_expiry_side', count(*) FROM v_alloc
 WHERE (e_kind = 'USE'    AND r_expires_at <= e_created_at)
    OR (e_kind = 'EXPIRE' AND r_expires_at >  e_created_at)
UNION ALL -- [§9.2] RESTORE는 그 주문 USE가 빠져나간 묶음으로만, 묶음별 Σ되돌림 ≤ Σ사용
SELECT 'restore_within_use_bundles', count(*) FROM (
  SELECT 1 FROM v_alloc r
  LEFT JOIN (SELECT e_order, earn_entry_id, sum(amount) AS used FROM v_alloc
             WHERE e_kind = 'USE' AND e_status = 'CONFIRMED' GROUP BY e_order, earn_entry_id) u
    ON u.e_order = r.e_order AND u.earn_entry_id = r.earn_entry_id
  WHERE r.e_kind = 'RESTORE'
  GROUP BY r.e_order, r.earn_entry_id, u.used
  HAVING u.used IS NULL OR sum(r.amount) > u.used) x
UNION ALL -- [§9.2] REVOKE: 분쟁 인정 항목에만, 그 항목의 적립 묶음에서만
SELECT 'revoke_link', count(*) FROM (
  SELECT l.id FROM v_led l LEFT JOIN order_items oi ON oi.id = l.order_item_id
  WHERE l.kind = 'REVOKE' AND oi.status IS DISTINCT FROM 'REFUNDED_AFTER_CONFIRM'
  UNION ALL
  SELECT entry_id FROM v_alloc WHERE e_kind = 'REVOKE' AND r_item IS DISTINCT FROM e_item) x
UNION ALL -- 비-EARN 원장 항목은 주문에 귀속(order_id 또는 order_item_id) — EXPIRE 제외
SELECT 'ledger_entry_order_link', count(*) FROM v_led
 WHERE kind IN ('USE','RESTORE','REVOKE') AND order_id IS NULL
UNION ALL -- [§4.3·§4.5] USE 상태 ↔ 주문 상태, PAID 이상 주문의 point_used = Σ USE(CONFIRMED)
SELECT 'order_point_used_eq_use', count(*) FROM v_ord o
 LEFT JOIN (SELECT order_id,
                   sum(amount) FILTER (WHERE status = 'CONFIRMED') AS conf,
                   count(*)    FILTER (WHERE status = 'RESERVED')  AS n_res,
                   count(*)    FILTER (WHERE status = 'RELEASED')  AS n_rel
            FROM v_led WHERE kind = 'USE' GROUP BY order_id) u ON u.order_id = o.id
 WHERE (o.is_paid AND coalesce(u.conf, 0) <> o.point_used)
    OR (NOT o.is_paid AND u.conf IS NOT NULL)
    OR (coalesce(u.n_res, 0) > 0 AND o.status NOT IN ('PAYING','UNKNOWN'))
    OR (coalesce(u.n_rel, 0) > 0 AND o.status <> 'FAILED')
UNION ALL -- USE 사용자 = 주문 구매자
SELECT 'use_user_eq_buyer', count(*) FROM v_led l JOIN orders o ON o.id = l.order_id
 WHERE l.kind IN ('USE','RESTORE') AND l.user_id <> o.buyer_id
UNION ALL -- [§4.5·§5.2] EARN: 항목당 ≤1, 결제 이후 항목에만, 상태가 항목 상태와 일치, 사용자 = 구매자, 주문 항목에 귀속
SELECT 'earn_vs_item_status', count(*) FROM (
  SELECT l.id FROM v_led l LEFT JOIN v_oi i ON i.id = l.order_item_id
  WHERE l.kind = 'EARN' AND (
        i.id IS NULL OR NOT i.is_paid OR l.user_id <> i.o_buyer
     OR l.status <> CASE
          WHEN i.status IN ('PAID','SHIPPED_TO_CENTER','INSPECTING','DELIVERING','DELIVERED') THEN 'PENDING'
          WHEN i.status IN ('CONFIRMED','DISPUTED','REFUNDED_AFTER_CONFIRM') THEN 'AVAILABLE'
          ELSE 'CANCELLED' END)
  UNION ALL
  SELECT order_item_id FROM v_led WHERE kind = 'EARN' AND order_item_id IS NOT NULL
  GROUP BY order_item_id HAVING count(*) > 1) x
UNION ALL -- [§5.2] AVAILABLE 적립의 만료 = 구매 확정 + 1년(시각 기준)
SELECT 'earn_expiry_one_year', count(*) FROM v_led e JOIN v_oi i ON i.id = e.order_item_id
 WHERE e.kind = 'EARN' AND e.status = 'AVAILABLE' AND e.expires_at IS DISTINCT FROM i.confirmed_at + interval '365 days'
UNION ALL -- [§5.2·§9.2] 적립액 ∈ {floor(기준 × 등급률)}; 기준 = floor(카드 몫 × 체결가 / b_i). 최저율로도 ≥1이면 EARN 필수
SELECT 'earn_amount_rate', count(*) FROM (
  SELECT i.id, (i.card_share * i.price) / i.b AS base, e.amount
  FROM v_oi i
  LEFT JOIN v_led e ON e.order_item_id = i.id AND e.kind = 'EARN'
  WHERE i.is_paid) x
 WHERE (amount IS NULL AND base / 100 >= 1)
    OR (amount IS NOT NULL AND amount NOT IN (base / 100, base * 15 / 1000, base * 2 / 100, base * 3 / 100))

---------------- 입찰·체결 (§4.1·§4.2·§9.1) ----------------
UNION ALL -- 체결 ↔ 두 입찰: 방향, 사용자, 사이즈, 구매가 ≥ 판매가
SELECT 'trade_bid_shape', count(*) FROM trades t
 JOIN bids bb ON bb.id = t.buy_bid_id JOIN bids sb ON sb.id = t.sell_bid_id
 WHERE bb.side <> 'BUY' OR sb.side <> 'SELL' OR bb.user_id <> t.buyer_id OR sb.user_id <> t.seller_id
    OR bb.product_size_id <> t.product_size_id OR sb.product_size_id <> t.product_size_id
    OR bb.price < sb.price
UNION ALL -- [§4.1] 체결가 = 먼저 등록된 입찰의 가격 ((created_at, id) 순)
SELECT 'trade_price_earlier_bid', count(*) FROM trades t
 JOIN bids bb ON bb.id = t.buy_bid_id JOIN bids sb ON sb.id = t.sell_bid_id
 WHERE t.price <> CASE WHEN (bb.created_at, bb.id) < (sb.created_at, sb.id) THEN bb.price ELSE sb.price END
UNION ALL -- 체결 시각 ≥ 두 입찰 등록 시각, 체결 시각 < 두 입찰 만료
SELECT 'trade_matched_after_bids', count(*) FROM trades t
 JOIN bids bb ON bb.id = t.buy_bid_id JOIN bids sb ON sb.id = t.sell_bid_id
 WHERE t.matched_at < bb.created_at OR t.matched_at < sb.created_at
    OR t.matched_at >= bb.expires_at OR t.matched_at >= sb.expires_at
UNION ALL -- [§4.2] 결제 기한 = 체결 + 30분
SELECT 'trade_payment_due', count(*) FROM trades WHERE payment_due_at <> matched_at + interval '30 minutes'
UNION ALL -- [§4.1·ADR-0003 §7] 판매 입찰 복구(MATCHED → ACTIVE)는 유효기간 안에서만(지났으면 MATCHED → EXPIRED)
SELECT 'bid_restore_within_validity', count(*) FROM bid_events e JOIN bids b ON b.id = e.bid_id
 WHERE e.from_status = 'MATCHED' AND e.to_status = 'ACTIVE' AND e.created_at >= b.expires_at
UNION ALL -- [§9.1] MATCHED 입찰 ⇔ 살아 있는 체결 1건
SELECT 'bid_matched_live_trade', count(*) FROM bids b
 LEFT JOIN (SELECT bid_id, count(*) AS n FROM (
              SELECT buy_bid_id AS bid_id FROM trades WHERE status <> 'PAYMENT_EXPIRED'
              UNION ALL
              SELECT sell_bid_id FROM trades WHERE status <> 'PAYMENT_EXPIRED') y GROUP BY bid_id) x ON x.bid_id = b.id
 WHERE (b.status = 'MATCHED') <> (coalesce(x.n, 0) = 1) OR coalesce(x.n, 0) > 1
UNION ALL -- [§4.1] 결제 기한 초과 체결의 구매 입찰은 CANCELLED
SELECT 'expired_trade_buy_bid_cancelled', count(*) FROM trades t JOIN bids bb ON bb.id = t.buy_bid_id
 WHERE t.status = 'PAYMENT_EXPIRED' AND bb.status <> 'CANCELLED'
UNION ALL -- [§4.2·§9.1] 결제 기한 초과 체결 ⇔ 구매자 경고 1건(사용자 = 구매자)
SELECT 'buyer_warning_per_expired_trade', count(*) FROM trades t
 LEFT JOIN (SELECT trade_id, count(*) AS n, min(user_id) AS u1, max(user_id) AS u2
            FROM buyer_warnings GROUP BY trade_id) w ON w.trade_id = t.id
 WHERE (t.status = 'PAYMENT_EXPIRED') <> (w.n IS NOT NULL) OR w.n > 1 OR w.u1 <> t.buyer_id OR w.u2 <> t.buyer_id
UNION ALL -- [§1] 입찰 유효기간 30·60·90일
SELECT 'bid_validity_period', count(*) FROM bids
 WHERE expires_at - created_at NOT IN (interval '30 days', interval '60 days', interval '90 days')
UNION ALL -- [§9.1] bid_events: 이벤트 있음, 첫 이벤트 NULL→ACTIVE, from = 직전 to, 마지막 to = bids.status, 시각 단조·등록 이후
SELECT 'bid_events_chain', count(*) FROM bids b LEFT JOIN v_be e ON e.bid_id = b.id
 WHERE e.bid_id IS NULL OR e.first_from IS NOT NULL OR e.first_to <> 'ACTIVE' OR e.n_chain_break > 0
    OR e.last_to <> b.status OR e.n_time_back > 0 OR e.min_created < b.created_at
UNION ALL -- [§9.1] →MATCHED 이벤트는 trade_id가 있고 그 체결의 입찰이며, 체결마다 MATCHED 이벤트 정확히 2건
SELECT 'bid_events_matched_trade', count(*) FROM (
  SELECT be.id FROM bid_events be LEFT JOIN trades t ON t.id = be.trade_id
  WHERE be.to_status = 'MATCHED' AND (t.id IS NULL OR be.bid_id NOT IN (t.buy_bid_id, t.sell_bid_id))
  UNION ALL
  SELECT t.id FROM trades t
  LEFT JOIN (SELECT trade_id, count(*) AS n FROM bid_events WHERE to_status = 'MATCHED' GROUP BY trade_id) m
    ON m.trade_id = t.id
  WHERE m.n IS DISTINCT FROM 2) x
UNION ALL -- [§4.1] 가격·시간 우선: 매칭 순간 메이커보다 우선순위가 높은 반대편 ACTIVE 입찰(테이커 본인 것 제외)이 없어야 한다
SELECT 'trade_price_time_priority', count(*) FROM v_match m
 WHERE EXISTS (SELECT 1 FROM v_act z
               WHERE z.product_size_id = m.product_size_id
                 AND z.side = CASE m.taker_side WHEN 'BUY' THEN 'SELL' ELSE 'BUY' END
                 AND z.s_eid < m.m_eid AND (z.e_eid IS NULL OR z.e_eid > m.m_eid)
                 AND z.bid_id <> m.maker_id AND z.user_id <> m.taker_user
                 AND z.expires_at > m.m_at   -- 만료 시각이 지난 ACTIVE 입찰은 매칭 후보가 아니다(ADR-0003 §3.3)
                 AND CASE m.taker_side
                       WHEN 'BUY'  THEN (z.price, z.created_at, z.bid_id) < (m.maker_price, m.maker_created, m.maker_id)
                       ELSE (-z.price, z.created_at, z.bid_id) < (-m.maker_price, m.maker_created, m.maker_id) END)
UNION ALL -- [§4.1] 호가창은 어느 순간에도 교차하지 않는다: 입찰이 ACTIVE가 되는 순간(등록·복구) 반대편에 교차하는
          -- 다른 사용자의 체결 가능한(만료 시각 전) ACTIVE 입찰이 있으면 그 자리에서 체결됐어야 한다.
          -- 예외: 그 구간이 같은 시각의 체결로 끝났고 이 입찰이 그 체결의 테이커이며, 등록(복구)과 체결 이벤트 2행 사이에
          --       같은 사이즈의 다른 이벤트가 없다(등록·복구 즉시 체결). 같은 사이즈 안의 이벤트 id 순서는 실제 처리
          --       순서다(ADR-0003 §3.3 사이즈 단위 직렬화). 다른 사이즈의 이벤트는 사이에 끼어도 된다
SELECT 'book_never_crossed', count(*) FROM (
  -- 즉시 체결된 테이커 순간을 먼저 걸러 낸 뒤(MATERIALIZED로 순서 고정) 남은 구간만 교차를 확인한다.
  -- 순서를 플래너에 맡기면 1,300만 구간 전부에 교차 확인을 먼저 돌려 본 규모에서 끝나지 않는다(실측: 50만 시드 60분 초과)
  WITH cand AS MATERIALIZED (
    SELECT x.* FROM v_act x
     WHERE NOT EXISTS (SELECT 1 FROM v_match m
                       WHERE m.taker_id = x.bid_id AND x.e_eid IN (m.m_eid, m.m_eid2) AND m.m_at = x.s_at
                         -- 등록 ~ 그 체결의 마지막 MATCHED 사이 이벤트를 id 범위(PK)로 먼저 훑고 사이즈는 행마다 확인한다.
                         -- 같은 시각 안의 범위라 짧다(사이즈 → 입찰 → 이벤트 순은 테이커마다 입찰 수백 개를 훑는다)
                         AND NOT EXISTS (SELECT 1 FROM bid_events be
                                         WHERE be.id > x.s_eid AND be.id < m.m_eid2
                                           AND be.trade_id IS DISTINCT FROM m.trade_id
                                           AND (SELECT bb.product_size_id FROM bids bb WHERE bb.id = be.bid_id)
                                               = x.product_size_id)))
  SELECT 1 FROM cand x
   WHERE EXISTS (SELECT 1 FROM v_act z
                 WHERE z.product_size_id = x.product_size_id
                   AND z.side = CASE x.side WHEN 'BUY' THEN 'SELL' ELSE 'BUY' END   -- 반대편(등식이라 해시 키에 들어간다)
                   AND z.s_eid < x.s_eid AND (z.e_eid IS NULL OR z.e_eid > x.s_eid)
                   AND z.user_id <> x.user_id
                   AND z.expires_at > x.s_at
                   AND CASE x.side WHEN 'BUY' THEN x.price >= z.price ELSE x.price <= z.price END)) bnc
UNION ALL -- 데이터가 비어 있어 검사들이 빈 집합으로 통과하는 것을 막는다
SELECT 'data_not_empty', (SELECT count(*) FROM (VALUES
         ((SELECT count(*) FROM trades)), ((SELECT count(*) FROM orders)), ((SELECT count(*) FROM order_items)),
         ((SELECT count(*) FROM bids)), ((SELECT count(*) FROM point_ledger)), ((SELECT count(*) FROM settlements))) v(n)
       WHERE n = 0)
UNION ALL -- 체결마다 매칭 순간·테이커·메이커가 정해져야 한다(위 검사가 빈 집합으로 통과하지 않게)
SELECT 'trade_match_resolved', (SELECT count(*) FROM trades) - (SELECT count(*) FROM v_match)
UNION ALL -- [§4.1] 호가 교차 없음: 같은 사이즈의 ACTIVE 구매 최고가 < ACTIVE 판매 최저가 (자기 체결 금지 쌍은 예외)
SELECT 'active_book_no_cross', count(*) FROM (
  SELECT DISTINCT bb.product_size_id
  FROM (SELECT product_size_id
        FROM bids WHERE status = 'ACTIVE'
        GROUP BY product_size_id
        HAVING max(price) FILTER (WHERE side = 'BUY') >= min(price) FILTER (WHERE side = 'SELL')) c
  JOIN bids bb ON bb.product_size_id = c.product_size_id AND bb.status = 'ACTIVE' AND bb.side = 'BUY'
  JOIN bids sb ON sb.product_size_id = c.product_size_id AND sb.status = 'ACTIVE' AND sb.side = 'SELL'
              AND bb.price >= sb.price AND bb.user_id <> sb.user_id
  -- 지금의 ACTIVE 구간 시작: 나중에 ACTIVE가 된 쪽이 들어온 순간 먼저 있던 쪽이 이미 만료 시각을 지났으면
  -- 교차가 아니다(만료 배치 전의 ACTIVE 입찰은 매칭 후보가 아니다 — ADR-0003 §3.3)
  JOIN v_act ab ON ab.bid_id = bb.id AND ab.e_eid IS NULL
  JOIN v_act asl ON asl.bid_id = sb.id AND asl.e_eid IS NULL
  WHERE CASE WHEN ab.s_eid > asl.s_eid THEN sb.expires_at > ab.s_at ELSE bb.expires_at > asl.s_at END) x

---------------- 정산 (§5.4·§6.1-8·§9.3) ----------------
UNION ALL -- [§9.3] carry_in = 같은 판매자 직전 정산 carry_out (첫 정산 0)
SELECT 'settlement_carry_chain', count(*) FROM v_set WHERE carry_in <> coalesce(prev_carry_out, 0)
UNION ALL -- [§5.4] 음수 이월이 있으면 다음 주 정산 행이 있어야 함(마지막 주 제외)
SELECT 'settlement_carry_next_week', count(*) FROM v_set s, v_global g
 WHERE s.carry_out < 0 AND s.period_start < g.last_period_start
   AND s.next_period_start IS DISTINCT FROM s.period_start + 7
UNION ALL -- [§9.3] gross = Σamount, fee_total = Σfee
SELECT 'settlement_gross_fee_sum', count(*) FROM v_set s
 LEFT JOIN (SELECT settlement_id, sum(amount) AS a, sum(fee) AS f FROM settlement_items GROUP BY settlement_id) x
   ON x.settlement_id = s.id
 WHERE s.gross <> coalesce(x.a, 0) OR s.fee_total <> coalesce(x.f, 0)
UNION ALL -- [§9.3] penalty_total = 이 정산에 붙은 패널티 합
SELECT 'settlement_penalty_sum', count(*) FROM v_set s
 LEFT JOIN (SELECT settlement_id, sum(amount) AS p FROM seller_penalties
            WHERE settlement_id IS NOT NULL GROUP BY settlement_id) x ON x.settlement_id = s.id
 WHERE s.penalty_total <> coalesce(x.p, 0)
UNION ALL -- [§9.3] 포함·보류·회수·패널티·이월이 하나도 없는 정산 행은 없음
SELECT 'settlement_not_empty', count(*) FROM v_set s
 WHERE s.carry_in = 0 AND s.penalty_total = 0
   AND NOT EXISTS (SELECT 1 FROM settlement_items si WHERE si.settlement_id = s.id)
UNION ALL -- [§5.4·§9.3] 정산 주는 월요일 시작, PAID 정산의 paid_at = 그 다음 주 수요일 00:00 KST
SELECT 'settlement_period_and_paid_at', count(*) FROM v_set
 WHERE extract(isodow FROM period_start) <> 1
    OR (status = 'PAID' AND paid_at IS DISTINCT FROM ((period_start + 9)::timestamp AT TIME ZONE 'Asia/Seoul'))
UNION ALL -- [§9.5] CARRIED 정산은 paid_at NULL
SELECT 'settlement_carried_unpaid', count(*) FROM v_set WHERE status = 'CARRIED' AND paid_at IS NOT NULL
UNION ALL -- [§5.4·§9.3] 정산 행 금액: INCLUDED = (체결가, floor(체결가 × 카테고리율)), CLAWBACK = 그 음수
SELECT 'settlement_item_amounts', count(*) FROM settlement_items si JOIN v_oi i ON i.id = si.order_item_id
 WHERE (si.status = 'INCLUDED' AND (si.amount <> i.price OR si.fee <> floor(i.price * i.sale_fee_rate)))
    OR (si.status = 'CLAWBACK' AND (si.amount <> -i.price OR si.fee <> -floor(i.price * i.sale_fee_rate)))
UNION ALL -- 정산 행의 판매자 = 체결 판매자, INCLUDED 항목은 그 주 마감 전에 확정, CLAWBACK은 분쟁 인정 항목
SELECT 'settlement_item_seller_and_time', count(*) FROM settlement_items si
 JOIN v_set s ON s.id = si.settlement_id JOIN v_oi i ON i.id = si.order_item_id
 WHERE s.seller_id <> i.t_seller
    OR (si.status = 'INCLUDED' AND (i.confirmed_at IS NULL OR i.confirmed_at >= s.cutoff))
    OR (si.status = 'CLAWBACK' AND i.status <> 'REFUNDED_AFTER_CONFIRM')
UNION ALL -- [§9.3 불변식 8 개정] 항목 상태별 정산 행 수
SELECT 'settlement_inv8', count(*) FROM v_oi i
 CROSS JOIN v_global g
 LEFT JOIN v_si_item x ON x.order_item_id = i.id
 WHERE
   -- 확정 이력 없는 항목은 정산 행 없음
   (i.status NOT IN ('CONFIRMED','DISPUTED','REFUNDED_AFTER_CONFIRM') AND x.order_item_id IS NOT NULL)
   -- 어떤 항목도 INCLUDED ≤ 1, CLAWBACK ≤ INCLUDED
   OR coalesce(x.n_inc, 0) > 1 OR coalesce(x.n_claw, 0) > coalesce(x.n_inc, 0)
   -- 마감 전 확정된 CONFIRMED: INCLUDED 1, CLAWBACK 0
   --   (예외: 보류(HELD)됐다가 마지막 마감 뒤에 기각된 항목은 아직 INCLUDED 0 — ADR §9.5)
   OR (i.status = 'CONFIRMED' AND i.confirmed_at < g.last_cutoff
       AND NOT (coalesce(x.n_inc, 0) = 1 AND coalesce(x.n_claw, 0) = 0)
       AND NOT (coalesce(x.n_inc, 0) = 0 AND coalesce(x.n_held, 0) > 0
                AND EXISTS (SELECT 1 FROM disputes d WHERE d.order_item_id = i.id
                            AND d.status = 'REJECTED' AND d.resolved_at >= g.last_cutoff)))
   -- 마지막 마감 뒤 확정된 CONFIRMED: 아직 INCLUDED 없음
   OR (i.status = 'CONFIRMED' AND (i.confirmed_at >= g.last_cutoff OR g.last_cutoff IS NULL) AND coalesce(x.n_inc, 0) <> 0)
   -- DISPUTED: CLAWBACK 없음
   OR (i.status = 'DISPUTED' AND coalesce(x.n_claw, 0) <> 0)
   -- REFUNDED_AFTER_CONFIRM: INCLUDED 수 = CLAWBACK 수 (0 또는 1)
   --   (예외: 마지막 마감 뒤 인정된 건은 회수가 아직 없다 — INCLUDED 1, CLAWBACK 0 — ADR §9.5)
   OR (i.status = 'REFUNDED_AFTER_CONFIRM' AND coalesce(x.n_inc, 0) <> coalesce(x.n_claw, 0)
       AND NOT (coalesce(x.n_inc, 0) = 1 AND coalesce(x.n_claw, 0) = 0 AND i.cancelled_at >= g.last_cutoff))
UNION ALL -- [§5.4] 패널티: 판매자 귀책 취소 항목마다 정확히 1건, 금액 floor(체결가 × 10%), 판매자 = 체결 판매자
SELECT 'penalty_per_seller_fault', count(*) FROM v_oi i
 LEFT JOIN (SELECT order_item_id, count(*) AS n, min(amount) AS amt, min(seller_id) AS s1, max(seller_id) AS s2
            FROM seller_penalties GROUP BY order_item_id) p ON p.order_item_id = i.id
 WHERE (i.status = 'CANCELLED_SELLER_FAULT') <> (p.n IS NOT NULL) OR p.n > 1
    OR p.amt <> i.price * 10 / 100 OR p.s1 <> i.t_seller OR p.s2 <> i.t_seller
UNION ALL -- [§5.4·§9.3] 패널티 정산: 같은 판매자의 정산에, 그 정산 마감 전 발생, 마지막 마감 전 발생분은 모두 정산됨
SELECT 'penalty_settlement_link', count(*) FROM seller_penalties p
 CROSS JOIN v_global g
 LEFT JOIN v_set s ON s.id = p.settlement_id
 WHERE (p.settlement_id IS NOT NULL AND (s.seller_id <> p.seller_id OR p.created_at >= s.cutoff))
    OR (p.settlement_id IS NULL AND p.created_at < g.last_cutoff)

---------------- 등급 (§5.2) ----------------
UNION ALL -- 등급 이력: 매월 1일, 기준 금액 → 등급 (SILVER ≥ 50만 · GOLD ≥ 200만 · VIP ≥ 500만)
SELECT 'grade_history_threshold', count(*) FROM user_grade_history
 WHERE extract(day FROM effective_from) <> 1
    OR grade <> CASE WHEN basis_amount >= 5000000 THEN 'VIP' WHEN basis_amount >= 2000000 THEN 'GOLD'
                     WHEN basis_amount >= 500000 THEN 'SILVER' ELSE 'BRONZE' END
UNION ALL -- users.grade = 가장 최근 등급 이력 (이력이 있는 사용자만)
SELECT 'user_grade_current', count(*) FROM users u
 JOIN (SELECT DISTINCT ON (user_id) user_id, grade FROM user_grade_history
       ORDER BY user_id, effective_from DESC) h ON h.user_id = u.id
 WHERE u.grade <> h.grade;

------------------------------------------------------------------------------------------
-- 결과표
------------------------------------------------------------------------------------------
\echo '== 불변식 검사 (violations = 0 이어야 함) =='
SELECT check_name, violations FROM v_result ORDER BY (violations = 0), check_name;

------------------------------------------------------------------------------------------
-- 리포트: 행 수 · 상태 분포 · 금액 합
------------------------------------------------------------------------------------------
\echo '== 행 수 =='
SELECT 'users' AS tbl, count(*) FROM users UNION ALL
SELECT 'user_grade_history', count(*) FROM user_grade_history UNION ALL
SELECT 'seller_payout_accounts', count(*) FROM seller_payout_accounts UNION ALL
SELECT 'categories', count(*) FROM categories UNION ALL
SELECT 'products', count(*) FROM products UNION ALL
SELECT 'product_sizes', count(*) FROM product_sizes UNION ALL
SELECT 'bids', count(*) FROM bids UNION ALL
SELECT 'bid_events', count(*) FROM bid_events UNION ALL
SELECT 'trades', count(*) FROM trades UNION ALL
SELECT 'buyer_warnings', count(*) FROM buyer_warnings UNION ALL
SELECT 'orders', count(*) FROM orders UNION ALL
SELECT 'order_items', count(*) FROM order_items UNION ALL
SELECT 'pg_transactions', count(*) FROM pg_transactions UNION ALL
SELECT 'refunds', count(*) FROM refunds UNION ALL
SELECT 'inspections', count(*) FROM inspections UNION ALL
SELECT 'disputes', count(*) FROM disputes UNION ALL
SELECT 'point_ledger', count(*) FROM point_ledger UNION ALL
SELECT 'point_allocations', count(*) FROM point_allocations UNION ALL
SELECT 'point_balances', count(*) FROM point_balances UNION ALL
SELECT 'settlements', count(*) FROM settlements UNION ALL
SELECT 'settlement_items', count(*) FROM settlement_items UNION ALL
SELECT 'seller_penalties', count(*) FROM seller_penalties;

\echo '== 상태 분포 =='
SELECT 'bids' AS tbl, side || ':' || status AS status, count(*) FROM bids GROUP BY 1, 2 UNION ALL
SELECT 'trades', status, count(*) FROM trades GROUP BY 1, 2 UNION ALL
SELECT 'orders', status, count(*) FROM orders GROUP BY 1, 2 UNION ALL
SELECT 'order_items', status, count(*) FROM order_items GROUP BY 1, 2 UNION ALL
SELECT 'pg_transactions', kind || ':' || status, count(*) FROM pg_transactions GROUP BY 1, 2 UNION ALL
SELECT 'refunds', fault, count(*) FROM refunds GROUP BY 1, 2 UNION ALL
SELECT 'inspections', result, count(*) FROM inspections GROUP BY 1, 2 UNION ALL
SELECT 'disputes', status, count(*) FROM disputes GROUP BY 1, 2 UNION ALL
SELECT 'point_ledger', kind || ':' || status, count(*) FROM point_ledger GROUP BY 1, 2 UNION ALL
SELECT 'settlements', status, count(*) FROM settlements GROUP BY 1, 2 UNION ALL
SELECT 'settlement_items', status, count(*) FROM settlement_items GROUP BY 1, 2 UNION ALL
SELECT 'users.grade', grade, count(*) FROM users GROUP BY 1, 2
ORDER BY 1, 2;

\echo '== 금액 합 =='
SELECT 'orders(PAID 이상).card_amount' AS metric, coalesce(sum(card_amount), 0) AS total FROM orders
 WHERE status IN ('PAID','PARTIALLY_CANCELLED','CANCELLED') UNION ALL
SELECT 'orders(PAID 이상).point_used', coalesce(sum(point_used), 0) FROM orders
 WHERE status IN ('PAID','PARTIALLY_CANCELLED','CANCELLED') UNION ALL
SELECT 'orders(PAID 이상).item_total', coalesce(sum(item_total), 0) FROM orders
 WHERE status IN ('PAID','PARTIALLY_CANCELLED','CANCELLED') UNION ALL
SELECT 'refunds.card_amount', coalesce(sum(card_amount), 0) FROM refunds UNION ALL
SELECT 'refunds.point_amount', coalesce(sum(point_amount), 0) FROM refunds UNION ALL
SELECT 'pg APPROVE APPROVED', coalesce(sum(amount), 0) FROM pg_transactions WHERE kind = 'APPROVE' AND status = 'APPROVED' UNION ALL
SELECT 'pg CANCEL APPROVED', coalesce(sum(amount), 0) FROM pg_transactions WHERE kind = 'CANCEL' AND status = 'APPROVED' UNION ALL
SELECT 'point_ledger.' || kind || ':' || status, sum(amount) FROM point_ledger GROUP BY kind, status UNION ALL
SELECT 'point_balances.available', coalesce(sum(available), 0) FROM point_balances UNION ALL
SELECT 'point_balances.pending', coalesce(sum(pending), 0) FROM point_balances UNION ALL
SELECT 'settlements.gross', coalesce(sum(gross), 0) FROM settlements UNION ALL
SELECT 'settlements.fee_total', coalesce(sum(fee_total), 0) FROM settlements UNION ALL
SELECT 'settlements.penalty_total', coalesce(sum(penalty_total), 0) FROM settlements UNION ALL
SELECT 'settlements.payout', coalesce(sum(payout), 0) FROM settlements UNION ALL
SELECT 'seller_penalties.amount', coalesce(sum(amount), 0) FROM seller_penalties;

------------------------------------------------------------------------------------------
-- 판정: 위반이 하나라도 있으면 실패 (exit code ≠ 0)
------------------------------------------------------------------------------------------
DO $$
DECLARE
  total bigint;
  n_checks int;
  failed text;
BEGIN
  SELECT sum(violations), count(*), string_agg(check_name || '=' || violations, ', ' ORDER BY check_name)
                                    FILTER (WHERE violations <> 0)
    INTO total, n_checks, failed
  FROM v_result;
  IF n_checks = 0 OR total IS NULL THEN
    RAISE EXCEPTION 'verify: 검사 결과가 비어 있음 (n_checks=%)', n_checks;
  END IF;
  IF total <> 0 THEN
    RAISE EXCEPTION 'verify: 불변식 위반 % 건 — %', total, failed;
  END IF;
  RAISE NOTICE 'verify: % 개 검사 모두 통과 (위반 0)', n_checks;
END $$;

ROLLBACK;
