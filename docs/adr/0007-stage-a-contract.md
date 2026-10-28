# ADR-0007: 규모 확장 단계 A 동작 계약 — 기능 A1~A12

- 상태: **채택** (2026-10-29, 사용자 합의 — §16 [제안] 값 그대로, 듀얼 리뷰·감사·재점검 반영 23건)
- 앞 결정: [ADR-0002 도메인·DB](0002-domain-and-database.md) · [ADR-0003 계약 1단계](0003-http-api-stage1.md) · [ADR-0004 계약 2단계](0004-http-api-stage2.md) · [ADR-0006 규모 확장](0006-scale-up.md)(§3 불변식·§4.1 목록)
- 표기: **[제안]** = 인터뷰에서 Claude가 정하도록 맡긴 수치·세부(2026-10-28 사용자 결정). 합의 때 목록(§16)으로 검토한다. **[불변식 N]** = ADR-0006 §3의 N 때문에 이렇게 정했다는 뜻.
- 정본 순서: 새 테이블의 행 규칙은 이 문서와 그 DDL(§3), 실행 판정은 `verify.sql`의 새 검사(§15), HTTP 표면은 이 문서. 기존 테이블·엔드포인트·배치의 계약은 ADR-0002~0004 그대로다.

## 1. 결정 요약 (인터뷰 2026-10-28·29)

| 항목 | 결정 |
|---|---|
| 범위 | ADR-0006 §4.1의 A1~A12를 이 문서 하나에 정한다(스키마·시드 후처리·1k 새 태그 이미지를 한 번만 만든다) |
| 상세도 | ADR-0003·0004와 같다: 요청·응답 필드, 오류 code, 경계값, 배치 대상·잠금, 테스트 정본 예시 |
| 수치 | Claude가 제안하고(**[제안]**) 리뷰·합의에서 확인한다 |
| 시드 | 새 테이블을 1k 규모에 비례해 채운다(§14, 후처리 패스) |
| 오류 code | **기존 code만 쓴다**(새 code 없음). layered `Failure` enum·`statusOf`를 고치지 않기 위해서다 |
| 새 배치 | layered의 기존 틀(`BatchTarget`·`KeysetBatchTargets.spec`·`forEachIsolated`)을 재사용한다 — ADR-0006 §3의 1 허용 목록을 개정한다(§17의 1) |

## 2. 공통

### 2.1 기존 규칙 그대로

- ADR-0003 §2(JSON·원 단위 정수·오프셋 포함 ISO-8601·오류 본문·5xx 금지·판정 순서 401 → 400 → 404 → 403 → 409 → 422), §7(멈춘 시계·요청 시각 1회 읽기·`/admin` 헤더 불필요), §12의 1(계약 밖 오류 = 400 `UNEXPECTED_ERROR` 고정 문구)·§12의 2(배치 건별 격리).
- ADR-0004 §2.1(상태 위반과 기한 위반이 함께면 상태 위반이 먼저, 날짜 `"YYYY-MM-DD"`, 문자열은 받은 그대로 저장·빈 값 판정에만 `isBlank`), §9.1(사용자 행끼리 id 오름차순 잠금).
- 시각 계산은 KST(`Asia/Seoul`). "오늘" = 현재 시각의 KST 날짜. "달" = KST 달력월.
- 문자열 길이 "N자"는 **앞뒤 공백을 뺀 값의 유니코드 코드 포인트 수**다(중간 공백은 센다). 저장은 받은 값 그대로 **[제안]**.

### 2.2 오류 code 쓰임 (기존 code만)

| 상황 | 응답 |
|---|---|
| 필수 누락·형식 오류·범위 밖·알 수 없는 enum·글자 수 밖·미래 시각 입력 | 400 `INVALID_REQUEST` |
| `X-User-Id` 없음·숫자 아님·없는 사용자 | 401 `UNKNOWN_USER` |
| 없는 대상(리뷰·주문 항목·사용자·사이즈·문의·알림·가격 알림·세금계산서·슬롯·예약·요청) | 404 `NOT_FOUND` |
| 남의 대상 | 403 `NOT_OWNER` |
| 그 동작을 할 수 없는 상태(새 테이블의 상태 포함), 중복 등록, 정원 초과 | 409 `ITEM_STATE_CONFLICT` |
| 기한이 지남(리뷰 작성 기간·입고 예약·취소·체크인 기한) | 409 `DEADLINE_PASSED` |

- `ITEM_STATE_CONFLICT`는 이름이 "주문 항목"이지만 이 문서에서는 **새 테이블 행의 상태 충돌에도 쓴다** **[제안]**. 메시지는 계약이 고정하지 않는다(ADR-0003 §12의 1과 같음).
- 새 엔드포인트에서 `PURCHASE_RESTRICTED`·`SALE_RESTRICTED`는 내지 않는다. 제한은 조회·배치 판정에만 쓴다(§5·§8, **[불변식 7]**).

### 2.3 쓰기와 잠금

- 새 기능은 **기존 22개 테이블에 쓰지 않는다**(INSERT·UPDATE·DELETE 없음) **[불변식 2]**. 기존 행을 `FOR UPDATE`로 잠그는 것은 읽기로 본다 — 쓰는 곳은 이 문서가 잠금을 명시한 곳뿐이다.
- 새 행 하나를 바꾸는 요청: 그 행을 `FOR UPDATE`로 잠그고 → 상태·기한을 다시 확인 → 쓰기 → 커밋. 같은 행에 동시에 온 두 요청 중 하나만 성공하고 다른 하나는 409다(ADR-0004 §9.1과 같은 원리).
- 중복 등록을 막는 UNIQUE가 있는 INSERT는 **먼저 부모 행을 잠근다**(각 절에 적음). 그래서 동시 중복은 UNIQUE 위반(400 `UNEXPECTED_ERROR`)이 아니라 409가 된다.
- 기존 수정 과제의 고칠 곳·읽을 곳 함수는 **호출만** 할 수 있고 그 안에 코드를 넣지 않는다 **[불변식 3]**. 이 문서의 기능은 그 함수를 부르지 않아도 되게 정했다(필요한 판정은 SQL로 저장 상태를 읽는다).

### 2.4 새 기능이 저장하는 시각

- 새 테이블에 저장하는 시각(작성·종결·발행·판정·감지·체크인 시각 등)은 **그 요청·배치의 현재 시각**이다. 기준 시각에 기간을 더한 값으로 저장 시각을 정하거나 `min(현재, 기준 + N일)` 꼴로 상한을 두지 않는다 **[불변식 7 — T1]**. 기간은 비교(기한 안인가·지났는가)에만 쓴다.
- 예외는 사용자가 보낸 시각(A11 `occurredAt`)과 사용자가 정한 날짜(A10 휴가, A12 슬롯 시각)다 — 계산한 값이 아니라 입력이다.

### 2.5 목록 페이지 (새 공통 규칙 — A9 관리자 목록에만)

- 기존 조회 API는 페이지가 없다(전체 목록). 이 문서도 사용자용 목록은 같다(전체, 정렬 명시).
- **관리자 목록(A9)만** 키셋 페이지를 쓴다 **[제안]**: 쿼리 `limit`(1~100, 기본 50) · `after`(id, 생략하면 처음부터). 그 목록의 id 오름차순으로 `id > after`인 것을 `limit`개. 응답 `{"items": [...], "nextAfter": 마지막 항목 id | null}` — 받은 항목 수가 `limit`과 같으면 마지막 id, 적으면 null(같을 때 다음 페이지가 비어 있을 수 있다).
- `limit`·`after`가 정수가 아니거나 범위 밖(`limit` 1~100, `after` 0 이상)이면 400.
- 날짜 범위 필터(`from`·`to`, `"YYYY-MM-DD"`)는 **양끝 포함**이고, 둘 다 있을 때 `from > to`면 400이다(§11.2·§11.3 공통).

### 2.6 배치 공통

- `POST /admin/batches/{name}`에 11개 이름을 더한다(§13 표). 응답·건별 격리·`processed`/`failed` 정의는 ADR-0003 §7·§12의 2 그대로 — **11개 모두 격리한다**(격리 예외는 기존 `weekly-settlement`뿐).
- 대상은 표의 조건을 만족하는 행을 **id 순**으로 처리한다. 대상 1건 = 트랜잭션 하나. **잠글 행은 각 배치 절에 적은 것뿐이다** — 잠금이 있는 배치는 잠근 뒤 조건을 다시 확인하고 바뀌었으면 건너뛴다(실패 아님). "잠금 없음"으로 적은 배치는 재확인 없이 쓴다(같은 배치 두 개의 동시 실행은 계약 밖 — ADR-0004 §9.1).
- 스케줄러는 두지 않는다(ADR-0006 §3의 2).

### 2.7 관리자 감사 로그

- 이 문서의 **새 `/admin` 쓰기 요청**(배치 제외)은 성공하면 같은 트랜잭션에서 `admin_audit_logs` 1행을 쓴다(§11). 대상: 리뷰 숨김(A1)·지급 계좌 판정(A3)·문의 답변(A6)·세금계산서 정정(A7)·배송 추적 이벤트(A11)·입고 슬롯 생성·체크인(A12). 실패한 요청은 쓰지 않는다. 기존 `/admin` 요청은 쓰지 않는다 **[불변식 2]**.

## 3. 새 테이블 (DDL 요약)

관례는 기존과 같다(ADR-0002 §6, `docker/schema/tables.sql`·`constraints.sql`): `bigserial` id, 금액 `bigint`, 시각 `timestamptz`, 날짜 `date`, 상태 `text` + `CHECK IN`, UNIQUE·FK·인덱스는 `constraints.sql`, FK는 이름 없이. 새 DDL은 **두 기존 파일의 끝에 덧붙인다**(적재 스크립트가 이 두 파일만 읽는다).

| 테이블 | 기능 | 주요 열 · 제약 |
|---|---|---|
| `reviews` | A1 | id · order_item_id · buyer_id · seller_id · rating smallint (1~5) · content text · status (VISIBLE·HIDDEN·DELETED) · hidden_reason text? (DISPUTE·ADMIN) · created_at · updated_at · **UNIQUE(order_item_id)** · CHECK (status = 'HIDDEN') = (hidden_reason IS NOT NULL) |
| `seller_ratings` | A1 | seller_id **PK** · review_count int ≥ 0 · rating_sum int ≥ 0 · recalculated_at · CHECK rating_sum BETWEEN review_count AND 5 × review_count |
| `seller_grades` | A2 | id · seller_id · grade (STANDARD·PLUS·PRO·MASTER) · effective_from date · basis_amount bigint ≥ 0 · restricted boolean · created_at · **UNIQUE(seller_id, effective_from)** |
| `fee_rebates` | A2 | id · seller_id · month date(1일) · grade · fee_base bigint · rate_permille int (0·50·100·150) · amount bigint ≥ 0 · status (PAID·ZERO) · paid_at? · created_at · **UNIQUE(seller_id, month)** · CHECK (status = 'PAID') = (amount > 0) · CHECK (status = 'PAID') = (paid_at IS NOT NULL) |
| `payout_account_requests` | A3 | id · user_id · bank_code text · account_no_masked text · status (PENDING·APPROVED·REJECTED) · reject_reason text? · requested_at · decided_at? · CHECK (status = 'PENDING') = (decided_at IS NULL) · CHECK (status = 'REJECTED') = (reject_reason IS NOT NULL) · 부분 UNIQUE `payout_account_requests_live_user_key`(user_id) WHERE status = 'PENDING' |
| `monthly_statements` | A3 | id · seller_id · month date · settlement_count int > 0 · gross · fee_total (부호 제약 없음 — CLAWBACK 달은 음수) · penalty_total ≥ 0 · payout_total ≥ 0 · carried_count int ≥ 0 · held_count int ≥ 0 · last_carry_out bigint ≤ 0 · created_at · **UNIQUE(seller_id, month)** |
| `notifications` | A4·A5 | id · user_id · kind (PAYMENT_DUE_SOON·SHIPMENT_DUE_SOON·DISPUTE_WINDOW_CLOSING·SETTLEMENT_PAID·PURCHASE_RESTRICTED·SALE_RESTRICTED·PRICE_ALERT) · ref_id bigint · dedupe_key text · created_at · read_at? · **UNIQUE(dedupe_key)** |
| `wishlists` | A5 | id · user_id · product_size_id · created_at · **UNIQUE(user_id, product_size_id)** |
| `price_alerts` | A5 | id · user_id · product_size_id · target_price bigint > 0 · status (ACTIVE·TRIGGERED·EXPIRED·CANCELLED) · expires_at · triggered_bid_id bigint? · closed_at? · created_at · CHECK expires_at > created_at · CHECK (status = 'ACTIVE') = (closed_at IS NULL) · CHECK (status = 'TRIGGERED') = (triggered_bid_id IS NOT NULL) · 부분 UNIQUE `price_alerts_live_user_size_key`(user_id, product_size_id) WHERE status = 'ACTIVE' |
| `inquiries` | A6 | id · user_id · order_item_id bigint? · category (ORDER·PAYMENT·SETTLEMENT·ACCOUNT·OTHER) · title text · status (OPEN·ANSWERED·CLOSED) · close_reason text? (USER·AUTO) · created_at · sla_due_at · first_answered_at? · answered_at? · closed_at? · CHECK (status = 'CLOSED') = (closed_at IS NOT NULL) · CHECK (status = 'CLOSED') = (close_reason IS NOT NULL) · CHECK sla_due_at = created_at + interval '48 hours' |
| `inquiry_messages` | A6 | id · inquiry_id · author (USER·ADMIN) · body text · created_at |
| `tax_invoices` | A7 | id · settlement_id · seller_id · kind (ISSUE·CANCEL·HOLD) · status (ISSUED·CANCELLED·DONE) · supply_amount · vat_amount · total_amount (bigint) · ref_invoice_id bigint? · issued_at · CHECK kind별 모양(§8.1) · 부분 UNIQUE `tax_invoices_live_settlement_key`(settlement_id) WHERE (kind = 'ISSUE' AND status = 'ISSUED') OR kind = 'HOLD' |
| `price_stats_daily` | A8 | id · product_size_id · stat_date date · trade_count int > 0 · min_price · max_price · median_price (bigint > 0) · created_at · **UNIQUE(product_size_id, stat_date)** · CHECK min_price ≤ median_price ≤ max_price |
| `admin_audit_logs` | A9 | id · action (REVIEW_HIDE·PAYOUT_ACCOUNT_DECISION·INQUIRY_ANSWER·TAX_INVOICE_CORRECTION·TRACKING_EVENT·INBOUND_SLOT_CREATE·INBOUND_CHECK_IN) · target_id bigint · detail text? · created_at |
| `seller_profiles` | A10 | user_id **PK** · shop_name text · intro text · vacation_from date? · vacation_to date? · created_at · updated_at · CHECK (vacation_from IS NULL) = (vacation_to IS NULL) · CHECK vacation_from ≤ vacation_to |
| `shipment_tracking_events` | A11 | id · order_item_id · carrier (CJ·HANJIN·LOTTE·POST) · tracking_no text · event_type (PICKED_UP·IN_TRANSIT·OUT_FOR_DELIVERY·DELIVERED) · occurred_at · received_at · CHECK occurred_at ≤ received_at |
| `shipment_delays` | A11 | id · order_item_id · kind (SHIPMENT_OVERDUE·DELIVERY_LATE) · detected_at · **UNIQUE(order_item_id, kind)** |
| `inbound_slots` | A12 | id · slot_start · capacity int > 0 · created_at · **UNIQUE(slot_start)** · CHECK slot_start의 분·초 = 0 |
| `inbound_reservations` | A12 | id · slot_id · seller_id · order_item_id · status (RESERVED·CHECKED_IN·CANCELLED·NO_SHOW) · created_at · closed_at? · CHECK (status = 'RESERVED') = (closed_at IS NULL) · 부분 UNIQUE `inbound_reservations_live_item_key`(order_item_id) WHERE status IN ('RESERVED','CHECKED_IN') |

- **새 FK 27개**(전부 `ADD FOREIGN KEY`): reviews 3(order_item·buyer·seller) · seller_ratings 1 · seller_grades 1 · fee_rebates 1 · payout_account_requests 1 · monthly_statements 1 · notifications 1 · wishlists 2 · price_alerts 3(user·product_size·triggered_bid → bids) · inquiries 2(user·order_item) · inquiry_messages 1 · tax_invoices 3(settlement·seller·ref_invoice → tax_invoices) · price_stats_daily 1 · seller_profiles 1 · shipment_tracking_events 1 · shipment_delays 1 · inbound_reservations 3(slot·seller·order_item) · `admin_audit_logs`·`inbound_slots`는 0. → verify `schema_constraints_present`의 FK 수 34 → **61** **[구현 검증 — DDL 작성 때 다시 센다]**.
- 인덱스: 각 FK 열(조회·배치 대상) + 배치 대상 조건 열(예: `price_alerts(status)`, `inquiries(status, answered_at)`, `inbound_reservations(status)`). 이름 `<table>_<목적>_idx`.
- 새 테이블 20개 = ADR-0006 §4.1 추정 18 + `seller_ratings`(이미 목록에 있음)·`shipment_delays`(A11 "표시만"을 행으로 남기는 자리) — §17의 2.

## 4. A1 리뷰·평점

### 4.1 작성 `POST /order-items/{id}/review` `{"rating": 1~5 정수, "content": 문자열}`

- 구매자(그 주문의 구매자)만. `content`는 앞뒤 공백을 뺀 값이 1~1,000자(아니면 400) **[제안]**.
- 순서: 401 → 400 → 404(항목) → 403 → 주문 행 `FOR UPDATE` → 409.
  - 항목 상태가 `CONFIRMED`가 아니면 409 `ITEM_STATE_CONFLICT`(DISPUTED·REFUNDED_AFTER_CONFIRM 포함).
  - 그 항목의 리뷰가 이미 있으면(어떤 상태든 — 지운 뒤 다시 쓸 수 없다) 409 `ITEM_STATE_CONFLICT`.
  - `현재 > confirmed_at + 30일`이면 409 `DEADLINE_PASSED`(30일 정각은 쓸 수 있다).
- 결과: `reviews` 1행(`VISIBLE`, `seller_id` = 체결 판매자, `created_at` = `updated_at` = 현재). 응답 `201` 리뷰 모양: `{"reviewId", "orderItemId", "sellerId", "rating", "content", "status", "createdAt"}`.
- 주문 행을 잠그는 이유: 같은 항목의 리뷰 동시 작성이 409가 되게(§2.3). 분쟁 제기도 그 주문 행을 잠그므로 둘은 직렬이다.
- 기각된 분쟁의 항목은 다시 `CONFIRMED`라 기간 안이면 쓸 수 있다.

### 4.2 삭제 `DELETE /reviews/{id}`

- 작성자만(아니면 403). 리뷰 행 `FOR UPDATE`. `DELETED`면 409 `ITEM_STATE_CONFLICT`. `VISIBLE`·`HIDDEN` → `DELETED`(`hidden_reason` NULL), `updated_at` = 현재. 응답 `200` 리뷰 모양.

### 4.3 관리자 숨김 `POST /admin/reviews/{id}/hide`

- `VISIBLE`만(아니면 409). → `HIDDEN`, `hidden_reason = ADMIN`, `updated_at` = 현재 + 감사 로그 `REVIEW_HIDE`. 응답 `200` 리뷰 모양.

### 4.4 판매자 리뷰 조회 `GET /sellers/{userId}/reviews` (헤더 불필요)

- 없는 사용자 404. `{"sellerId", "reviewCount", "ratingSum", "averageX100", "reviews": [리뷰 모양…]}`.
  - `reviewCount`·`ratingSum` = `seller_ratings` 행(마지막 `rating-recalc` 결과), 행이 없으면 0·0. `averageX100 = floor(ratingSum × 100 / reviewCount)`, `reviewCount = 0`이면 null.
  - `reviews` = 그 판매자의 `VISIBLE` 리뷰, `reviewId` 오름차순(지금의 행 상태 — 평점과 시점이 다를 수 있다).

### 4.5 배치 `rating-recalc`

- 대상: `reviews`가 1행 이상 있는 판매자(`users` id 순).
- 판매자 1명: 그 판매자의 리뷰 행을 id 순으로 `FOR UPDATE` 한 뒤
  1. 분쟁 숨김: `VISIBLE`이고 항목 상태가 `DISPUTED`·`REFUNDED_AFTER_CONFIRM`이면 → `HIDDEN`, `hidden_reason = DISPUTE`, `updated_at` = 현재.
  2. 분쟁 해제: `HIDDEN`·`DISPUTE`이고 항목 상태가 `CONFIRMED`(기각)이면 → `VISIBLE`, `hidden_reason` NULL, `updated_at` = 현재. `ADMIN` 숨김과 `REFUNDED_AFTER_CONFIRM`은 풀지 않는다.
  3. 평점: `VISIBLE` 리뷰의 수·rating 합으로 `seller_ratings`를 쓴다 — 행이 없고 `VISIBLE`이 1건 이상이면 INSERT, 행이 있고 값이 다르면 UPDATE(`recalculated_at` = 현재). 값이 같거나, 행이 없고 `VISIBLE`이 0건이면 쓰지 않는다(조회는 행 없음 = 0·0).
- `processed` = 1~3 중 하나라도 쓴 판매자 수. 분쟁 상태는 기존 경로에서 바꾸고(ADR-0004 §4.3·§4.4), 리뷰에는 이 배치가 돌 때 반영된다 **[불변식 2 — 기존 분쟁 경로에 SQL을 넣지 않는다]**.

## 5. A2 판매자 등급·수수료 리베이트

### 5.1 등급 규칙 **[제안]**

- 기준월 `D` = 현재 시각이 속한 달의 1일(KST). 기준 금액 = 그 판매자의 `settlement_items` 중 `INCLUDED`의 `amount` 합, 소속 정산의 `period_start`가 `[D − 3개월, D)`인 것.
- 등급(기준 금액): **MASTER ≥ 8,000,000 · PRO ≥ 3,000,000 · PLUS ≥ 1,000,000 · 그 밖 STANDARD**. 구매자 등급(`users.grade`, ADR-0004 §8.5)과 이름·문턱·기간을 다르게 둔다 **[불변식 7 — C1]**. 불변식 7의 C1 항목은 **기존 구매자 등급 경계·적립률의 재구현 금지**로 해석한다 — 판매자 등급이라는 새 규칙 자체는 ADR-0006 §4.1 A2로 채택된 기능이다.
- **판매 제한:** 산정 시 `users.sale_restricted_until > 현재`이면 `restricted = true`, 등급은 기준 금액과 무관하게 `STANDARD`. 저장된 값만 읽는다 — 제한 조건(패널티 수)을 다시 세지 않는다 **[불변식 7 — T2]**.

### 5.2 배치 `seller-grade-recalc`

- 대상: `settlements` 행이 하나 이상 있는 사용자 중 `effective_from = D`인 `seller_grades` 행이 없는 것(`users` id 순).
- 사용자 1명: `seller_grades` 1행(`grade`, `effective_from = D`, `basis_amount`, `restricted`, `created_at` = 현재). 등급이 지난달과 같아도 매달 쓴다(구매자 등급과 다름). 이미 그 달 행이 있으면 건너뛴다 — 같은 달 재실행은 첫 결과를 유지한다.
- 중복 방지: 사용자 행 `FOR UPDATE` 뒤 재확인(§2.3 — 기존 `users` 행 잠금은 읽기).
- `processed` = 쓴 행 수.

### 5.3 배치 `rebate-settlement`

- 대상 달 `M` = 지난달(현재 달의 1일 − 1개월). **그 달이 닫혔을 때만** 돈다 — 닫힘 판정은 `monthly-statement`와 같다(§6.4). 아니면 대상 0건. 그 달 마지막 주 정산이 생기기 전에 행이 확정되어 그 주 수수료가 빠지는 일을 막는다.
- 대상: `effective_from = M`인 `seller_grades` 행이 있고 `month = M`인 `fee_rebates` 행이 없는 판매자(`users` id 순). 사용자 행 `FOR UPDATE` 뒤 재확인.
- 리베이트율(그 달의 등급 = `effective_from = M` 행): STANDARD 0‰ · PLUS 50‰ · PRO 100‰ · MASTER 150‰ **[제안]**.
- `fee_base` = 그 판매자 정산 중 `period_start`가 달 `M`에 속하는 것의 `fee_total` 합(CLAWBACK으로 음수일 수 있다). `amount = floor(max(fee_base, 0) × rate / 1000)`.
- `fee_rebates` 1행: `amount > 0`이면 `PAID`, `paid_at` = 현재. 0이면 `ZERO`, `paid_at` NULL. 주간 정산(`settlements`)은 건드리지 않는다 **[불변식 2]**.
- `processed` = 쓴 행 수(ZERO 포함).

### 5.4 조회

- `GET /me/seller-grade` → `{"current": 등급 모양 | null, "history": [등급 모양…]}`. 등급 모양 `{"grade", "effectiveFrom", "basisAmount", "restricted"}`, `current` = `effective_from`이 가장 늦은 행, `history`는 `effectiveFrom` 오름차순.
- `GET /me/fee-rebates` → `[{"month", "grade", "feeBase", "ratePermille", "amount", "status", "paidAt"}]`, `month` 오름차순.

## 6. A3 지급 계좌 변경·월간 정산서

### 6.1 지급 계좌 변경 요청 `POST /me/payout-account-requests` `{"bankCode", "accountNo"}`

- `bankCode`는 시드 은행 코드 6개(`004`·`011`·`020`·`081`·`088`·`090`) 중 하나, `accountNo`는 숫자 10~14자리(아니면 400) **[제안]**. 저장은 `account_no_masked = "****" + 끝 4자리`(기존 `seller_payout_accounts`와 같은 모양) — 전체 번호는 저장하지 않는다.
- 사용자 행 `FOR UPDATE` → 그 사용자의 `PENDING` 요청이 있으면 409 `ITEM_STATE_CONFLICT`. 없으면 1행(`PENDING`, `requested_at` = 현재). 응답 `201` 요청 모양 `{"requestId", "bankCode", "accountNoMasked", "status", "rejectReason", "requestedAt", "decidedAt"}`.

### 6.2 판정 `POST /admin/payout-account-requests/{id}/decision` `{"approve": true|false, "reason": 문자열|null}`

- `PENDING`만(아니면 409). 반려인데 `reason`이 null이거나 빈 값이면 400. 승인 시 `reason`은 무시.
- → `APPROVED` 또는 `REJECTED`(`reject_reason`), `decided_at` = 현재 + 감사 로그 `PAYOUT_ACCOUNT_DECISION`. 응답 `200` 요청 모양.
- 승인해도 `seller_payout_accounts`를 바꾸지 않는다 **[불변식 2]**. 유효 계좌는 조회에서 정한다(§6.3).

### 6.3 조회 `GET /me/payout-account`

- `{"bankCode", "accountNoMasked", "source", "pending": 요청 모양 | null}`. 유효 계좌 = 그 사용자의 `APPROVED` 요청 중 `decided_at`이 가장 늦은 것(같으면 id 큰 것) → `source = "REQUEST"`. 없으면 `seller_payout_accounts` 행 → `"INITIAL"`. 둘 다 없으면 세 필드 모두 null.

### 6.4 월간 정산서 배치 `monthly-statement`

- 대상 달 `M` = 지난달. **그 달이 닫혔을 때만** 돈다: `M`에 속하는 마지막 월요일을 `L`이라 할 때, 지금까지 만든 정산의 마지막 마감(ADR-0004 §10.1)이 `L + 7일` 이상이어야 한다. 아니면 대상 0건(`processed` 0).
- 대상: `period_start`가 `M`에 속하는 정산이 있고 `month = M`인 정산서가 없는 판매자(`users` id 순).
- 판매자 1명: 사용자 행 `FOR UPDATE` 뒤 재확인, 그 달 정산들을 읽어 1행 — `settlement_count`, `gross`·`fee_total`·`penalty_total`·`payout_total`(각 합), `carried_count`(`CARRIED` 수 — 이월), `held_count`(그 정산들의 `HELD` **행 수** `count(*)` — 같은 항목이 여러 주에 HELD면 주마다 센다, 보류), `last_carry_out`(그 달 `period_start`가 가장 늦은 정산의 `carry_out`), `created_at` = 현재.
- 조회 `GET /me/monthly-statements` → `[{"month", "settlementCount", "gross", "feeTotal", "penaltyTotal", "payoutTotal", "carriedCount", "heldCount", "lastCarryOut"}]`, `month` 오름차순.

## 7. A4 알림 (상태 스캔형)

### 7.1 배치 `deadline-reminder`

여섯 종류를 아래 표의 **행 순서대로** 하나씩 끝까지 훑는다(종류마다 그 표의 id 순 — 예: 구매 제한 사용자를 모두 훑은 뒤 판매 제한). 대상 1건 = 알림 1행 INSERT. 조건에 `NOT EXISTS (같은 dedupe_key)`가 들어 있어 재실행은 새 대상만 만든다.

| kind | 훑는 표 · 조건 (현재 = `now`) | 받는 사람 | `ref_id` · `dedupe_key` |
|---|---|---|---|
| `PAYMENT_DUE_SOON` | `trades`: `AWAITING_PAYMENT`, `now < payment_due_at ≤ now + 10분` **[제안]** | 구매자 | 체결 id · `PAYMENT_DUE_SOON:<id>` |
| `SHIPMENT_DUE_SOON` | `order_items`: `PAID`, `paid_at + 2일 ≤ now < paid_at + 3일` **[제안]** | 판매자 | 항목 id · `SHIPMENT_DUE_SOON:<id>` |
| `DISPUTE_WINDOW_CLOSING` | `order_items`: `CONFIRMED`, `confirmed_at + 12일 ≤ now ≤ confirmed_at + 14일` **[제안]** | 구매자 | 항목 id · `DISPUTE_WINDOW_CLOSING:<id>` |
| `SETTLEMENT_PAID` | `settlements`: `PAID`, `now − 7일 < paid_at ≤ now` **[제안]** | 판매자 | 정산 id · `SETTLEMENT_PAID:<id>` |
| `PURCHASE_RESTRICTED` | `users`: `purchase_restricted_until > now` | 그 사용자 | 사용자 id · `PURCHASE_RESTRICTED:<id>:<E>` |
| `SALE_RESTRICTED` | `users`: `sale_restricted_until > now` | 그 사용자 | 사용자 id · `SALE_RESTRICTED:<id>:<E>` |

- `<E>` = 제한 끝 시각의 epoch 초 **내림**(`floor(extract(epoch from …))::bigint`). `dedupe_key`는 대상 조건의 `NOT EXISTS`와 INSERT 모두 **SQL 한 식**으로 만든다(앱에서 따로 계산하지 않는다 — 두 값이 어긋나 재실행마다 UNIQUE 위반이 나는 것을 막는다).

- 기한·제한은 **저장된 열을 비교만** 한다. 입찰·체결·항목에는 아무것도 하지 않는다 **[불변식 2·7 — C2]**. "자동 확정 예정" 알림은 두지 않는다 **[불변식 7 — T1]**.
- `processed` = 만든 알림 수. 알림 1행이 대상 1건이라 잠금은 없다(`UNIQUE(dedupe_key)` 위반은 배치 두 개를 동시에 돌린 경우뿐 — 계약 밖, ADR-0004 §9.1).
- `PRICE_ALERT`는 A5 배치가 만든다(§8.3).

### 7.2 조회·읽음

- `GET /me/notifications[?unread=true]` → `[{"notificationId", "kind", "refId", "createdAt", "readAt"}]`, `notificationId` 오름차순. `unread`는 `true`·`false`만(아니면 400), `true`면 `read_at` NULL만.
- `POST /me/notifications/{id}/read` → 본인 것만(403). 알림 행 `FOR UPDATE`, 이미 읽었으면 409 `ITEM_STATE_CONFLICT`(멱등으로 만들지 않는다 — ADR-0004 §9.2와 같음). `read_at` = 현재, 응답 `200` 알림 모양.

## 8. A5 위시리스트·가격 알림

### 8.1 위시리스트

- `POST /me/wishlist` `{"productSizeId"}` → 없는 사이즈 404. 사용자 행 `FOR UPDATE` → 이미 있으면 409. 1행, 응답 `201 {"productSizeId", "createdAt"}`.
- `DELETE /me/wishlist/{productSizeId}` → 없으면 404. 행을 지운다(새 테이블이라 DELETE 허용). 응답 `200 {"productSizeId", "createdAt"}`.
- `GET /me/wishlist` → `[{"productSizeId", "createdAt", "bestSell", "activeAlert": 가격 알림 모양 | null}]`, `productSizeId` 오름차순. `bestSell` = 그 사이즈의 `ACTIVE`이고 `expires_at > 현재`인 SELL 입찰의 최저가(없으면 null) — `GET /product-sizes/{id}/book`과 같은 기준.

### 8.2 가격 알림

- `POST /me/price-alerts` `{"productSizeId", "targetPrice": 양의 정수}` → 없는 사이즈 404. 사용자 행 `FOR UPDATE` → 같은 사이즈의 `ACTIVE` 알림이 있으면 409. 1행(`ACTIVE`, `expires_at` = 현재 + 60일 **[제안]**). 응답 `201` 가격 알림 모양 `{"alertId", "productSizeId", "targetPrice", "status", "expiresAt", "triggeredBidId", "closedAt", "createdAt"}`. 구매 제한 중에도 등록할 수 있다.
- `DELETE /me/price-alerts/{id}` → 본인 것만. 알림 행 `FOR UPDATE`, `ACTIVE`가 아니면 409. → `CANCELLED`, `closed_at` = 현재. 응답 `200` 가격 알림 모양.
- `GET /me/price-alerts` → 가격 알림 모양 목록, `alertId` 오름차순.

### 8.3 배치 `price-alert-scan`

- 대상: `ACTIVE` 가격 알림(id 순). 알림 행 `FOR UPDATE` 뒤 재확인.
  1. `expires_at ≤ 현재` → `EXPIRED`, `closed_at` = 현재.
  2. 아니고 그 사용자가 `purchase_restricted_until > 현재`이면 아무것도 하지 않는다(알림은 멈출 뿐 `ACTIVE` 유지, `processed`에 세지 않음). 제한은 읽기만 한다 **[불변식 7 — C2]**.
  3. 아니면 그 사이즈의 `ACTIVE`·`expires_at > 현재`·`price ≤ target_price`인 SELL 입찰 중 `price, created_at, id` 순 첫 번째가 있으면 → `TRIGGERED`, `triggered_bid_id`, `closed_at` = 현재 + `notifications` 1행(`PRICE_ALERT`, `ref_id` = 알림 id, `dedupe_key = PRICE_ALERT:<알림 id>`, 받는 사람 = 알림 주인). 입찰은 잠그지 않는다(읽기).
- `processed` = 1 또는 3으로 바뀐 알림 수.

## 9. A6 고객 문의

### 9.1 엔드포인트

| 엔드포인트 | 누가 | 허용 상태 | 결과 |
|---|---|---|---|
| `POST /me/inquiries` `{"category", "title", "body", "orderItemId": 정수\|null}` | 사용자 | — | `title` 1~100자·`body` 1~2,000자(앞뒤 공백을 뺀 값 기준) **[제안]**, 알 수 없는 category 400. `orderItemId`가 있으면 없는 항목 404, 그 항목의 구매자·판매자가 아니면 403. 문의 1행(`OPEN`, `sla_due_at = created_at + 48시간` **[제안]**) + 메시지 1행(USER). `201` 문의 모양 |
| `POST /me/inquiries/{id}/messages` `{"body"}` | 작성자 | `OPEN`·`ANSWERED` | 메시지 1행(USER), 상태 `OPEN`. `CLOSED`면 409. `200` 문의 모양 |
| `POST /admin/inquiries/{id}/answer` `{"body"}` | 운영자 | `OPEN`·`ANSWERED` | 메시지 1행(ADMIN), 상태 `ANSWERED`, `answered_at` = 현재, `first_answered_at`이 NULL이면 현재 + 감사 로그 `INQUIRY_ANSWER`. `CLOSED`면 409. `200` 문의 모양 |
| `POST /me/inquiries/{id}/close` | 작성자 | `OPEN`·`ANSWERED` | `CLOSED`, `close_reason = USER`, `closed_at` = 현재. `CLOSED`면 409. `200` 문의 모양 |

- 모든 `body`(첫 문의·추가 메시지·답변)는 앞뒤 공백을 뺀 값이 1~2,000자(아니면 400). 판정 순서: 401 → 400(본문) → 404 → 403(작성자 아님) → 409.
- 상태를 바꾸는 요청은 문의 행 `FOR UPDATE` 뒤 확인한다.
- 문의 모양: `{"inquiryId", "category", "title", "status", "closeReason", "orderItemId", "createdAt", "slaDueAt", "slaBreached", "firstAnsweredAt", "answeredAt", "closedAt"}`.
  - `slaBreached` = (`first_answered_at` IS NULL 이고 `현재 > sla_due_at`) 또는 `first_answered_at > sla_due_at`. 계산 값이고 저장하지 않는다.

### 9.2 조회

- `GET /me/inquiries` → 문의 모양 목록, `inquiryId` 오름차순.
- `GET /me/inquiries/{id}` → 문의 모양 + `"messages": [{"author", "body", "createdAt"}]`(id 순) + `"orderItem": {"orderItemId", "status", "openDispute": boolean} | null`. `orderItem`은 지금의 항목 상태와 `OPEN` 분쟁 유무를 읽기만 한다.

### 9.3 배치 `inquiry-auto-close`

- 대상: `ANSWERED`이고 `answered_at + 7일 ≤ 현재` **[제안]**(id 순). 문의 행 `FOR UPDATE` 뒤 재확인 → `CLOSED`, `close_reason = AUTO`, **`closed_at` = 현재**(배치 시각 — `answered_at + 7일`로 소급하지 않는다 **[불변식 7 — T1]**).
- `processed` = 종결한 문의 수.

## 10. A7 세금계산서

### 10.1 행 모양 **[제안]**

| kind | status | 금액 | `ref_invoice_id` |
|---|---|---|---|
| `ISSUE` | `ISSUED` → (정정되면) `CANCELLED` | `supply_amount ≥ 0`, `vat_amount = floor(supply × 10 / 100)`, `total = supply + vat` | 정정 재발행이면 원본 id, 첫 발행이면 NULL |
| `CANCEL` | `DONE` | 원본의 세 금액에 −1을 곱한 값 | 원본 ISSUE id |
| `HOLD` | `DONE` | 0·0·0 | NULL |

- 공급가액 = 그 정산의 `fee_total`(판매 수수료 — 마켓이 판매자에게 발행) 중 0 이상인 부분 `max(fee_total, 0)`.

### 10.2 배치 `tax-invoice-issue`

- 대상: 세금계산서 행이 하나도 없는 정산 중 (`PAID`이고 `paid_at ≤ 현재`) 또는 `CARRIED`(`settlements` id 순).
- 정산 1건: `PAID` → `ISSUE`/`ISSUED` 1행(`issued_at` = 현재). `CARRIED` → `HOLD` 1행(이월 정산은 발행 보류, `issued_at` = 현재). 정산 행은 `FOR UPDATE`로 잠근 뒤 재확인(읽기).
- 아직 지급일 전인 `PAID` 정산(`paid_at > 현재`)은 지급일이 지난 뒤 실행에서 발행된다.
- `processed` = 쓴 행 수.

### 10.3 정정 `POST /admin/tax-invoices/{id}/correction` `{"supplyAmount": 0 이상 정수}`

- 대상이 `ISSUE`·`ISSUED`가 아니면 409(`CANCEL`·`HOLD`·이미 정정된 원본). 원본 행 `FOR UPDATE`.
- 한 트랜잭션, 이 순서로(부분 UNIQUE 때문에 원본 UPDATE를 INSERT보다 먼저 DB에 보낸다 — ORM이면 원본 변경 뒤 명시적 flush): 원본 → `CANCELLED` · `CANCEL` 1행 · 새 `ISSUE`/`ISSUED` 1행(`supply = supplyAmount`, 같은 정산, `ref_invoice_id` = 원본) · 감사 로그 `TAX_INVOICE_CORRECTION`. 시각은 모두 현재.
- 응답 `200 {"cancelled": 계산서 모양, "reissued": 계산서 모양}`. 계산서 모양 `{"invoiceId", "settlementId", "kind", "status", "supplyAmount", "vatAmount", "totalAmount", "refInvoiceId", "issuedAt"}`.
- 정정은 PG·카드 취소와 무관하다(카드 취소 키 규칙을 쓰지 않는다 — P1 고칠 곳과 겹치지 않게).

### 10.4 조회 `GET /me/tax-invoices`

- 판매자 본인 계산서 전부(세 kind), `invoiceId` 오름차순.

## 11. A8 시세·통계 · A9 관리자 조회

### 11.1 배치 `price-stats`

- 대상 날짜 `d` = 오늘 − 1일(KST). 대상: 체결 상태 `PAID`이고 `matched_at`의 KST 날짜가 `d`인 체결이 있는 사이즈 중 `(사이즈, d)` 행이 없는 것(`product_sizes` id 순). 지난 날짜는 소급하지 않는다(시드가 채운다 — §14).
- **잠금 없음**(사이즈 행을 잠그지 않는다 — 매칭의 사이즈 잠금과 경합하지 않게). 사이즈 1개: `trade_count`, `min_price`, `max_price`, `median_price` = 가격을 오름차순으로 놓은 `floor((n − 1) / 2)`번째(0부터 — 짝수면 아래 중앙값) **[제안]**. `created_at` = 현재.
- 그 날의 체결이 배치 뒤에 `PAID`가 되어도(결제 기한 30분) 이미 쓴 행은 고치지 않는다 — 배치 시점의 상태다.
- `processed` = 쓴 행 수.

### 11.2 시세 조회 (헤더 불필요)

- `GET /product-sizes/{id}/price-stats?from=YYYY-MM-DD&to=YYYY-MM-DD` → `from`·`to` 필수. 없는 사이즈 404, 날짜 형식 오류·`from > to`·일수(`to − from + 1`) > 92면 400 **[제안]**. `[{"date", "tradeCount", "minPrice", "maxPrice", "medianPrice"}]`, `date` 오름차순.
- `GET /product-sizes/{id}/recent-trades` → 그 사이즈의 `PAID` 체결 최근 20건 **[제안]**, `matched_at` 내림차순·같으면 id 내림차순: `[{"tradeId", "price", "matchedAt"}]`.

### 11.3 관리자 조회 (A9 — §2.5 페이지)

| 엔드포인트 | 항목 모양 | 필터 |
|---|---|---|
| `GET /admin/users/restricted?type=purchase\|sale` | `{"userId", "email", "restrictedUntil"}` | `type` 필수. 제한 끝 `> 현재`인 사용자 |
| `GET /admin/users/{id}/summary` (페이지 아님, 없는 사용자 404) | `{"userId", "email", "grade", "purchaseRestrictedUntil", "saleRestrictedUntil", "warningCount", "penaltyCount", "sellerGrade": 등급 모양\|null}` | `warningCount`·`penaltyCount`는 **전체 기간** 수(최근 N일 계수는 하지 않는다 **[불변식 7 — T2]**) |
| `GET /admin/users/{id}/penalties` | `{"penaltyId", "orderItemId", "amount", "settlementId", "createdAt"}` | 없는 사용자 404 |
| `GET /admin/users/{id}/warnings` | `{"warningId", "tradeId", "createdAt"}` | 없는 사용자 404 |
| `GET /admin/settlements` | `{"settlementId", "sellerId", "periodStart", "status", "net", "payout", "paidAt"}` | `status`(PAID·CARRIED)·`sellerId`·`from`·`to`(`period_start` 범위) 선택 |
| `GET /admin/disputes` | `{"disputeId", "orderItemId", "status", "openedAt", "resolvedAt"}` | `status`(OPEN·ACCEPTED·REJECTED) 선택 |
| `GET /admin/audit-logs` | `{"auditId", "action", "targetId", "detail", "createdAt"}` | `action` 선택 |

- 감사 로그 `detail` **[제안]**: REVIEW_HIDE `null` · PAYOUT_ACCOUNT_DECISION `"APPROVED"`/`"REJECTED"` · INQUIRY_ANSWER `null` · TAX_INVOICE_CORRECTION 새 ISSUE id 문자열 · TRACKING_EVENT event_type · INBOUND_SLOT_CREATE `null` · INBOUND_CHECK_IN `null`. `target_id` = 바뀐 행 id(정정은 원본 id, 추적 이벤트는 새 이벤트 id).

## 12. A10 판매자 프로필 · A11 배송 추적 · A12 입고 예약

### 12.1 A10 판매자 프로필

- `PUT /me/seller-profile` `{"shopName", "intro", "vacationFrom": "YYYY-MM-DD"|null, "vacationTo": …|null}` → `shopName` 1~40자, `intro` 0~500자, 휴가는 둘 다 null이거나 둘 다 있고 `from ≤ to`(아니면 400) **[제안]**. 사용자 행 `FOR UPDATE` 뒤 없으면 INSERT(`created_at`), 있으면 UPDATE. `updated_at` = 현재. 응답 `200` 프로필 모양.
- `GET /sellers/{userId}/profile` (헤더 불필요) → 사용자나 프로필이 없으면 404. `{"userId", "shopName", "intro", "vacationFrom", "vacationTo", "onVacation"}`, `onVacation` = 오늘이 `[from, to]` 안(계산 값).
- 휴가는 **표시만** 한다. 입찰·체결·발송 기한에 영향이 없다.

### 12.2 A11 배송 추적

- `POST /admin/order-items/{id}/tracking-events` `{"carrier", "trackingNo", "eventType", "occurredAt"}` → 알 수 없는 enum·빈 `trackingNo`(1~30자 **[제안]**)·`occurredAt > 현재`면 400. 없는 항목 404. 항목 상태가 `SHIPPED_TO_CENTER`·`INSPECTING`·`DELIVERING`·`DELIVERED`가 아니면 409. 1행(`received_at` = 현재) + 감사 로그 `TRACKING_EVENT`. 응답 `201` 이벤트 모양 `{"eventId", "orderItemId", "carrier", "trackingNo", "eventType", "occurredAt", "receivedAt"}`. 항목 상태는 바꾸지 않는다(입고·배송 완료는 기존 `/admin` 엔드포인트).
- `GET /order-items/{id}/tracking` (구매자 또는 판매자) → `{"events": [이벤트 모양…], "delays": [{"kind", "detectedAt"}]}`. events는 `occurredAt`·id 오름차순, delays는 id 오름차순.
- 배치 `delivery-delay-scan` **[제안]**: 두 종류를 차례로(`order_items` id 순).
  - `SHIPMENT_OVERDUE`: `PAID`이고 `paid_at + 3일 ≤ 현재`(발송 기한 지남 — `shipment-deadline`이 아직 돌지 않은 항목).
  - `DELIVERY_LATE`: `DELIVERING`이고 `inspected_at + 5일 ≤ 현재`.
  - **잠금 없음.** 같은 `(항목, kind)` 행이 없을 때만(대상 조건의 `NOT EXISTS`) `shipment_delays` 1행(`detected_at` = 현재). 항목 상태는 바꾸지 않는다 **[불변식 2]**. `processed` = 쓴 행 수.

### 12.3 A12 입고 예약

- 슬롯 생성 `POST /admin/inbound-slots` `{"slotStart", "capacity": 1~100}` → `slotStart`는 정시(분·초 0)이고 `> 현재`(아니면 400). 잠글 부모 행이 없으므로 `INSERT … ON CONFLICT (slot_start) DO NOTHING`으로 쓰고, 들어간 행이 0이면 409(같은 시각 슬롯 — 동시 생성도 409). 1행 + 감사 로그 `INBOUND_SLOT_CREATE`. 응답 `201` 슬롯 모양 `{"slotId", "slotStart", "capacity", "reserved"}`(`reserved` = `RESERVED`·`CHECKED_IN` 예약 수).
- 슬롯 조회 `GET /inbound-slots?date=YYYY-MM-DD` (헤더 불필요) → 그 KST 날짜의 슬롯, `slotStart` 오름차순.
- 예약 `POST /me/inbound-reservations` `{"slotId", "orderItemId"}` → 없는 슬롯·항목 404, 그 항목의 판매자가 아니면 403. 그 항목의 주문 행 → 슬롯 행 순서로 `FOR UPDATE` 한 뒤(같은 항목의 동시 예약은 주문 행에서, 같은 슬롯의 정원 경합은 슬롯 행에서 직렬이 된다):
  1. 항목 상태가 `PAID`가 아니면 409 `ITEM_STATE_CONFLICT`. 그 항목의 살아 있는 예약(`RESERVED`·`CHECKED_IN`)이 있으면 409.
  2. 살아 있는 예약 수 ≥ `capacity`면 409 `ITEM_STATE_CONFLICT`(정원 — 상태 위반으로 보아 기한보다 먼저, ADR-0004 §2.1).
  3. `현재 > slot_start − 24시간`이면 409 `DEADLINE_PASSED`(예약 기한 **[제안]**).
  → 1행(`RESERVED`, `seller_id`, `created_at` = 현재). 응답 `201` 예약 모양 `{"reservationId", "slotId", "slotStart", "orderItemId", "status", "createdAt", "closedAt"}`.
  - 부분 UNIQUE `inbound_reservations_live_item_key`는 마지막 방어선이다(주문 행 잠금으로 계약 안에서는 위반이 생기지 않는다).
- 취소 `DELETE /me/inbound-reservations/{id}` → 본인 것만. 예약 행 `FOR UPDATE`, `RESERVED`가 아니면 409, `현재 > slot_start − 2시간`이면 409 `DEADLINE_PASSED` **[제안]**. → `CANCELLED`, `closed_at` = 현재. 응답 `200` 예약 모양.
- 체크인 `POST /admin/inbound-reservations/{id}/check-in` → `RESERVED`가 아니면 409. `현재 < slot_start`면 409 `ITEM_STATE_CONFLICT`, `현재 ≥ slot_start + 1시간`이면 409 `DEADLINE_PASSED`. → `CHECKED_IN`, `closed_at` = 현재 + 감사 로그 `INBOUND_CHECK_IN`. 응답 `200` 예약 모양. 항목 상태는 바꾸지 않는다(입고 처리는 기존 `/admin/order-items/{id}/receipt`).
- 조회 `GET /me/inbound-reservations` → 예약 모양 목록, `reservationId` 오름차순.
- 배치 `inbound-no-show`: `RESERVED`이고 `slot_start + 1시간 ≤ 현재`(id 순). 예약 행 `FOR UPDATE` 뒤 → `NO_SHOW`, `closed_at` = 현재. 판매자 패널티(`seller_penalties`)·판매 제한과 무관하다 **[불변식 2·7]**. `processed` = 바꾼 예약 수.

## 13. 배치 일람 (ADR-0003 §7·ADR-0004 §5 형식에 추가)

| name | 기능 | 대상(id 순) | `processed` |
|---|---|---|---|
| `rating-recalc` | A1 | 리뷰가 있는 판매자 | 쓴 판매자 수 |
| `seller-grade-recalc` | A2 | 정산 행이 있고 이번 달 등급 행이 없는 사용자 | 쓴 행 수 |
| `rebate-settlement` | A2 | 지난달 등급 행이 있고 지난달 리베이트가 없는 판매자 | 쓴 행 수 |
| `monthly-statement` | A3 | (지난달이 닫혔으면) 지난달 정산이 있고 정산서가 없는 판매자 | 쓴 행 수 |
| `deadline-reminder` | A4 | 여섯 종류(§7.1 순서) | 만든 알림 수 |
| `price-alert-scan` | A5 | `ACTIVE` 가격 알림 | 바뀐 알림 수 |
| `inquiry-auto-close` | A6 | `ANSWERED` · 답변 + 7일 지남 | 종결 수 |
| `tax-invoice-issue` | A7 | 계산서 없는 지급 정산·이월 정산 | 쓴 행 수 |
| `price-stats` | A8 | 어제 `PAID` 체결이 있는 사이즈 | 쓴 행 수 |
| `delivery-delay-scan` | A11 | 두 종류(§12.2) | 쓴 행 수 |
| `inbound-no-show` | A12 | `RESERVED` · 슬롯 + 1시간 지남 | 바꾼 수 |

- 구현 위치 **[불변식 1]**: god는 `Marketplace.kt`의 `runBatch` 분기 + 기존 키셋 순회(`forEachTarget`) 재사용. layered는 새 배치 클래스(새 파일) + `BatchService.run` 분기·생성자 주입 + `BatchTarget` 값·`KeysetBatchTargets.spec` 분기 + `forEachIsolated` internal(§17의 1).
- 두 구조의 대상 조회 SQL은 같아야 한다(equiv — ADR-0006 §6.1의 6). 대상 조건은 이 표·각 절의 식을 `WHERE` 절로 그대로 옮긴 것이다.

## 14. 시드 후처리 (1k — ADR-0006 §3의 4)

- 자리: `seedgen.py`의 `g.run()` 뒤, sha 계산 전. 입력 = 닫힌 22개 CSV 다시 읽기. 난수 = `random.Random(seed ^ 0x5CA1E)` **[제안]**(기존 `self.rng`와 별개). 새 테이블은 `TABLES`가 아닌 별도 목록에 두고(Gen이 CSV를 미리 열지 않게) manifest `rows`·`sha256`에 더한다.
- 시드의 "현재" = `NOW`(2026-10-01 00:00 KST). 각 테이블은 **NOW 시점에 그 배치·요청들이 계약대로 돌았다면 남았을 행**이 되게 만든다 — 그래야 verify(§15)와 이후 배치 실행이 시드와 모순되지 않는다.

| 테이블 | 생성 규칙 **[제안]** | 1k 예상 행 수 |
|---|---|---|
| `reviews` | NOW에 `CONFIRMED`인 항목의 30%, `created_at` = `confirmed_at` + U(0, min(30일, NOW − confirmed_at)), rating 분포 5:45%·4:30%·3:15%·2:6%·1:4%, 2%는 `HIDDEN`·ADMIN, 3%는 `DELETED` | ≈ 1,770 |
| `seller_ratings` | `rating-recalc`를 NOW에 돌린 결과(리뷰가 있는 판매자마다 1행) | ≈ 190 |
| `seller_grades` | 2025-01 ~ 2026-10의 매월 1일 `D`, 그 날까지 정산 행이 있는 판매자 1행, §5.1 식. `restricted` = `sale_restricted_until`이 있고 `sale_restricted_until − 30일 ≤ D 00:00 < sale_restricted_until`(시드에 남은 마지막 제한 구간만 쓴다 — 그보다 앞선 제한은 `users`에 남지 않아 false로 둔다. 시드 가정으로 고정) | ≈ 3,900 |
| `fee_rebates` | 2025-01 ~ 2026-08(닫힌 달 — §5.3), 등급 행이 있는 달마다 §5.3 식, `created_at` = 그 달이 닫힌 뒤 첫 월요일 00:00(마지막 주 정산과 같은 시각), `paid_at`은 `PAID`(amount > 0)일 때만 같은 시각이고 `ZERO`(STANDARD 등)면 NULL(§3 CHECK) | ≈ 3,500 |
| `payout_account_requests` | 판매자의 10%가 1건: APPROVED 70%·REJECTED 20%·PENDING 10%, `requested_at` 2026-01 ~ NOW, `decided_at` = `requested_at` + U(1, 72)시간(NOW 이전) | ≈ 20 |
| `monthly_statements` | 2024-10 ~ 2026-08(닫힌 달), 그 달 정산이 있는 판매자 | ≈ 3,900 |
| `notifications` | NOW에 `deadline-reminder`를 돌린 결과 + `SETTLEMENT_PAID`는 2026-09-01 이후 지급분 전부(`created_at` = 그 정산의 `paid_at`), 그중 NOW − 7일 이전 것은 `read_at` = `created_at` + U(1, 48)시간 | ≈ 800 |
| `wishlists` · `price_alerts` | 사용자 20%가 1~3 사이즈 / 그중 절반이 알림: ACTIVE(목표가 < NOW의 bestSell 또는 bestSell 없음, 만료 NOW 뒤) 60%·EXPIRED 25%·CANCELLED 15% | ≈ 400 · 200 |
| `inquiries` · `inquiry_messages` | 사용자 8%가 1~2건(`created_at` ≥ 사용자 `created_at`): CLOSED 80%(AUTO는 `closed_at = answered_at + 7일 + U(0, 1일)`이고 ≤ NOW인 것만, 나머지 USER)·ANSWERED 10%·OPEN 10%, 메시지 2~4건(시각 오름차순, 첫 메시지 = `created_at`) | ≈ 120 · 360 |
| `tax_invoices` | `tax-invoice-issue`를 NOW에 돌린 결과(PAID·`paid_at ≤ NOW` → ISSUE, CARRIED → HOLD) + 0.5%는 정정(CANCEL + 재발행) | ≈ 4,900 |
| `price_stats_daily` | NOW 전 모든 날짜·사이즈에 §11.1 식(`PAID` 체결 기준) | ≈ 6,000 |
| `admin_audit_logs` | 위 시드 행 중 관리자 쓰기에 해당하는 것(숨김·판정·답변·정정·추적 이벤트·슬롯·체크인)마다 **빠짐없이** 1행(`created_at` = 그 쓰기의 시각) | ≈ 18,500 |
| `seller_profiles` | 판매자의 50% | 100 |
| `shipment_tracking_events` | `shipped_at`이 있는 항목: PICKED_UP(`occurred_at` = `shipped_at` − U(1, 12)시간, 단 `paid_at` 이후) · `delivered_at`이 있으면 OUT_FOR_DELIVERY(`delivered_at` − U(1, 6)시간, 단 `inspected_at` 이후)·DELIVERED(`delivered_at`). `received_at` = max(`occurred_at`, `shipped_at`) + U(0, 10)분(NOW 이전 — 계약상 수신 시점 항목은 SHIPPED_TO_CENTER 이후) | ≈ 18,000 |
| `shipment_delays` | NOW에 `delivery-delay-scan`을 돌린 결과 | 수 건 |
| `inbound_slots` · `inbound_reservations` | 2026-09-01 ~ 2026-10-14 매일 10~17시 정시 슬롯(정원 20, `created_at` = 2026-08-25 00:00) / `shipped_at`이 2026-09-02 이후이거나 NOW에 `PAID`인 항목의 40%가 예약: 슬롯은 `paid_at + 24시간 ≤ slot_start`를 만족하는 것 중에서 고르고 `created_at` = U(`paid_at`, `slot_start − 24시간`). 발송된 항목은 `slot_start ≤ shipped_at`인 슬롯 → CHECKED_IN(`closed_at` = `slot_start` + U(0, 59)분) · NOW에 PAID인 항목은 지난 슬롯이면 NO_SHOW(`closed_at` = `slot_start` + 1시간), 앞 슬롯이면 RESERVED | 352 · ≈ 250 **[구현 검증]** |

- 행 수는 **[구현 검증]** — 실제 수는 시드 빌드 log에 적는다.
- `reviews`의 `created_at`은 그 항목의 분쟁 구간(`opened_at` ~ `resolved_at`)에 떨어지지 않게 다시 뽑는다(기각된 분쟁의 항목).
- 기존 22개 CSV는 바이트 그대로다(manifest 키별 sha 대조 — ADR-0006 §3의 4).
- 디스크 여유 검사(`seedgen.py`의 1천 명당 11MB)는 새 CSV 크기를 재서 갱신한다.

## 15. verify 새 검사 (요약)

- `schema_constraints_present`: **FK 수 34 → 61만** 바꾼다(§3, ADR-0006 §3의 2가 허용한 유일한 기존 검사 변경). 부분 UNIQUE 이름 목록(3개)은 그대로 둔다 — 그 식은 이름으로 세므로 새 인덱스가 생겨도 값이 같다.
- 새 검사(`v_result`의 `;` 앞에 `UNION ALL`, 이름 `a<N>_…`) **[제안]**:
  - 공통 `a0_partial_unique_present`(새 부분 UNIQUE 4개 `payout_account_requests_live_user_key`·`price_alerts_live_user_size_key`·`tax_invoices_live_settlement_key`·`inbound_reservations_live_item_key`가 있는가) · `a0_time_order`(새 테이블의 시각 순서: `decided_at ≥ requested_at`, `read_at ≥ created_at`, `closed_at ≥ created_at`(문의·가격 알림·예약), `received_at ≥ occurred_at`, 리뷰 `created_at ≥` 항목 `confirmed_at`).
  - A1 `a1_review_item_confirmed`(VISIBLE 리뷰 항목이 CONFIRMED — 분쟁 뒤 배치 전에는 깨지므로 시드 빌드에서만) · `a1_review_window`(`created_at ≤ confirmed_at + 30일`) · `a1_review_parties`(buyer·seller = 체결 당사자) · `a1_rating_cache`(`seller_ratings` = VISIBLE 집계 — 시드 시점 한정, 테스트 뒤에는 배치 전이라 다를 수 있어 **시드 빌드에서만** 판정 — §15 끝).
  - A2 `a2_seller_grade_rule`(**저장 열끼리**: `restricted`면 STANDARD, 아니면 `basis_amount`의 문턱 등급) · `a2_rebate_rule`(저장 열끼리: `rate_permille` = 등급의 율, `amount = floor(max(fee_base, 0) × rate / 1000)`, status·paid_at). 기준 금액·`fee_base`를 정산에서 다시 집계하는 대조는 테스트가 정산을 더 만들면 깨지므로 **시드 빌드에서만**(`a2_seller_grade_basis`·`a2_rebate_base`).
  - A3 `a3_statement_sums`(정산서 = 그 달 정산 합).
  - A4 `a4_notification_owner`(받는 사람 = 대상의 당사자).
  - A5 `a5_alert_trigger_bid`(TRIGGERED 입찰이 같은 사이즈 SELL·`price ≤ target`).
  - A6 `a6_inquiry_messages`(문의마다 첫 메시지 USER, ANSWERED·CLOSED(답변 있음)는 ADMIN 메시지 있음) · `a6_auto_close_after_7d`.
  - A7 `a7_invoice_shape`(kind별 금액·ref) · `a7_one_live_issue`(PAID·지급일 지남 정산당 ISSUED 또는 HOLD 정확히 하나 — 시드 빌드에서만).
  - A8 `a8_price_stats_rule`(행 = 그 날 PAID 체결 집계 — 시드 빌드에서만).
  - A9 `a9_audit_coverage`(시드의 관리자 쓰기 행마다 감사 행 — 행동 테스트 픽스처는 감사 없이 직접 넣을 수 있으므로 **시드 빌드에서만**).
  - A11 `a11_tracking_item_shipped`(이벤트 항목은 `shipped_at` 있음, `received_at ≥ shipped_at`).
  - A12 `a12_capacity`(살아 있는 예약 ≤ 정원) · `a12_reservation_item_seller` · `a12_reservation_deadline`(`created_at ≤ slot_start − 24시간`).
- **"시드 빌드에서만" 검사**: 행동 테스트 끝의 verify(ADR-0003 §11)는 테스트가 배치를 부르지 않은 상태도 보므로, 배치가 맞춰 주는 캐시·발행 완전성 검사는 실패할 수 있다. 이런 검사는 `verify.sql`의 psql 변수(예: `-v seed_build=1`)로 켜고, `build-image.sh`만 켠다 **[구현 검증 — 변수 이름·켜는 방식]**. 나머지는 테스트 뒤에도 성립해야 한다.

## 16. [제안] 값 목록 (합의 때 검토)

| 기능 | 값 |
|---|---|
| A1 | 리뷰 기간 확정 + 30일(정각 포함) · 본문 1~1,000자 · 리뷰 1항목 1건(삭제 뒤 재작성 불가) |
| A2 | 등급 3개월 INCLUDED 합: PLUS 100만 · PRO 300만 · MASTER 800만 · 제한 중 STANDARD · 리베이트 0/5/10/15% · 매달 등급 행 |
| A3 | 은행 코드 6개 · 계좌 10~14자리 · 마스킹 `****` + 끝 4자리 · 요청 1건씩 · 닫힌 달 판정 |
| A4 | 결제 기한 10분 전 · 발송 기한 1일 전(결제 + 2일) · 분쟁 기간 2일 전(확정 + 12일) · 지급 알림 7일 창 |
| A5 | 가격 알림 유효 60일 · 사이즈당 ACTIVE 1건 |
| A6 | SLA 48시간 · 자동 종결 답변 + 7일 · 제목 1~100자 · 본문·메시지·답변 1~2,000자 |
| A7 | 공급가액 = max(fee_total, 0) · 부가세 floor 10% · 이월 정산 HOLD |
| A8 | 아래 중앙값 · 시세 기간 ≤ 92일 · 최근 체결 20건 |
| A9 | 페이지 기본 50 · 최대 100 · id 키셋 · 날짜 필터 양끝 포함 |
| A10 | 상점명 1~40자 · 소개 0~500자 |
| A11 | 배송 지연 = 검수 합격 + 5일 · 송장 1~30자 · 지연 표시 두 종류 |
| A12 | 예약 기한 슬롯 − 24시간 · 취소 기한 슬롯 − 2시간 · 체크인 창 1시간 · 노쇼 = 슬롯 + 1시간 · 정원 1~100 |
| 공통 | 시드 후처리 난수 `seed ^ 0x5CA1E` |

## 17. ADR-0006 보완

1. **[ADR-0006 §3의 1 개정 — 사용자 결정 2026-10-29]** layered 기존 파일 허용 변경에 셋을 더한다: `domain/BatchTarget`(enum) 값 추가 · `infra/KeysetBatchTargets.spec`의 `when` 분기 추가 · `service/BatchService.kt` `forEachIsolated`의 공개 범위 private → internal. 모두 수정 과제 고칠 곳·읽을 곳 범위 밖이고 기존 분기·동작을 바꾸지 않는다. layered C2 고칠 곳이 `BatchService.kt:143-148`·`:161-167`(PaymentDueBatch)에 있으므로 이 범위의 줄은 의미 변경 없이 밀리는 것만 허용한다(§3의 3 그대로).
2. **[ADR-0006 §4.1 갱신]** 새 테이블 18 → 20(`seller_ratings` 포함 재계수 + A11 `shipment_delays`). API·배치 수는 그대로(배치 11).
3. **[오류 code]** 새 code를 만들지 않는다(§2.2) — 사용자 결정 2026-10-29. ADR-0006은 이를 정하지 않았으므로 개정이 아니라 보완이다.

## 18. 결과

- contract3 행동 테스트는 이 문서(+ ADR-0002~0004)만 보고 쓴다. **T1 회귀에 묶이지 않게**, `confirmed_at`에 기대는 테스트(A1 리뷰 기간·A4 분쟁 기간 알림·A2 기준 금액)는 확정을 `delivered_at + 7일` 안에 구매자 확정으로 만들거나 `confirmed_at`을 픽스처로 직접 둔다(T1 정답은 늦은 확정·자동 확정의 시각만 바꾼다). C2 회귀는 ADR-0006 §5의 픽스처 규칙(제한 + ACTIVE 입찰 조합 피하기)을 그대로 따른다. 기존처럼 직렬 실행, 자기 행만 단언, 픽스처는 테스트가 만든다(새 테이블 픽스처 도우미 `Db3` — 구현 때), 끝에 verify 1회(시드 빌드 전용 검사는 끈 채).
- 기능별 god 증가 추정(이 상세도 기준): A1 280 · A2 300 · A3 320 · A4 330 · A5 280 · A6 320 · A7 260 · A8 180 · A9 380 · A10 140 · A11 260 · A12 340 = **≈ 3,390줄**(ADR-0006 §4.1의 ≈ 3,230보다 조금 많다 — 실측은 구현 때).
