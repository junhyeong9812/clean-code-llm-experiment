# ADR-0007: 규모 확장 단계 A 동작 계약 — 기능 A1~A12

- 상태: **채택** (2026-10-29, 사용자 합의 — §16 [제안] 값 그대로, 듀얼 리뷰·감사·재점검 반영 23건) · 개정 1(2026-10-29, §19 — 테스트 작성에서 나온 입력 규칙·응답 보완 14항목, 새 테이블 수 19로 정정) · 개정 2(2026-10-30, §20 — 크기 확인(ADR-0006 §6.1-7) 뒤 보충 묶음 A13~A17, 스키마 무변경. **초안 — 합의 전**)
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
- 새 행 하나를 바꾸는 요청: 그 행을 `FOR UPDATE`로 잠그고 → 상태·기한을 다시 확인 → 쓰기 → 커밋. **상태를 소모하는 전환**(요청이 그 행의 상태 값을 바꿔 같은 요청을 다시 받을 수 없게 되는 것 — 예: 삭제·숨김·판정·종결·취소·읽음)이면, 같은 행에 동시에 온 **같은 전환** 두 요청 중 하나만 성공하고 다른 하나는 409다(ADR-0004 §9.1과 같은 원리). 서로 다른 전환이 겹치면 나중 요청은 앞 전환이 남긴 상태로 자기 허용 규칙을 다시 본다(예: `VISIBLE` 리뷰에 숨김과 삭제가 겹쳐 숨김이 먼저면 삭제는 `HIDDEN → DELETED`로 역시 200). 상태 값을 바꾸지 않는 수정(§20.2.1 리뷰 수정·§20.3.1 목표가 변경 — 개정 2)은 잠금으로 줄을 설 뿐 두 요청이 모두 허용 상태라 둘 다 200이고, 최종 값은 나중에 잠금을 얻은 요청의 값이다.
- 중복 등록을 막는 UNIQUE가 있는 INSERT는 **먼저 부모 행을 잠근다**(각 절에 적음). 그래서 동시 중복은 UNIQUE 위반(400 `UNEXPECTED_ERROR`)이 아니라 409가 된다.
- 기존 수정 과제의 고칠 곳·읽을 곳 함수는 **호출만** 할 수 있고 그 안에 코드를 넣지 않는다 **[불변식 3]**. 이 문서의 기능은 그 함수를 부르지 않아도 되게 정했다(필요한 판정은 SQL로 저장 상태를 읽는다).

### 2.4 새 기능이 저장하는 시각

- 새 테이블에 저장하는 시각(작성·종결·발행·판정·감지·체크인 시각 등)은 **그 요청·배치의 현재 시각**이다. 기준 시각에 기간을 더한 값으로 저장 시각을 정하거나 `min(현재, 기준 + N일)` 꼴로 상한을 두지 않는다 **[불변식 7 — T1]**. 기간은 비교(기한 안인가·지났는가)에만 쓴다.
- 예외는 사용자가 보낸 시각(A11 `occurredAt`)과 사용자가 정한 날짜(A10 휴가, A12 슬롯 시각)다 — 계산한 값이 아니라 입력이다.

### 2.5 목록 페이지 (새 공통 규칙 — A9·A15 관리자 목록에만)

- 기존 조회 API는 페이지가 없다(전체 목록). 이 문서도 사용자용 목록은 같다(전체, 정렬 명시).
- **관리자 목록(A9 §11.3, 개정 2: A15 `GET /admin/inquiries` §20.4.2)만** 키셋 페이지를 쓴다 **[제안]**: 쿼리 `limit`(1~100, 기본 50) · `after`(id, 생략하면 처음부터). 그 목록의 id 오름차순으로 `id > after`인 것을 `limit`개. 응답 `{"items": [...], "nextAfter": 마지막 항목 id | null}` — 받은 항목 수가 `limit`과 같으면 마지막 id, 적으면 null(같을 때 다음 페이지가 비어 있을 수 있다).
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
- 새 테이블 **19개** = ADR-0006 §4.1 목록 18(`seller_ratings` 포함) + `shipment_delays`(A11 "표시만"을 행으로 남기는 자리) — §17의 2. (개정 1: 처음 적은 20은 계수 오류였다 — §19)

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
- **잠금 없음**(사이즈 행에 행 잠금을 걸지 않는다 — INSERT의 FK 검사가 거는 KEY SHARE 대기는 있다). 사이즈 1개: `trade_count`, `min_price`, `max_price`, `median_price` = 가격을 오름차순으로 놓은 `floor((n − 1) / 2)`번째(0부터 — 짝수면 아래 중앙값) **[제안]**. `created_at` = 현재.
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
  1. 항목 상태가 `PAID`가 아니면 409 `ITEM_STATE_CONFLICT`. 그 항목의 살아 있는 예약(`RESERVED`·`CHECKED_IN`)이 있으면 409. (항목 상태 판정은 슬롯 행 잠금 **전**에 한다 — 주문 행을 잠갔으므로 결과는 같고, PAID 아닌 항목의 요청이 슬롯을 기다리며 교착 고리에 끼지 않는다.)
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
2. **[ADR-0006 §4.1 갱신]** 새 테이블 18 → 19(A11 `shipment_delays` 추가 — 개정 1에서 20을 19로 바로잡음). API·배치 수는 그대로(배치 11).
3. **[오류 code]** 새 code를 만들지 않는다(§2.2) — 사용자 결정 2026-10-29. ADR-0006은 이를 정하지 않았으므로 개정이 아니라 보완이다.

## 18. 결과

- contract3 행동 테스트는 이 문서(+ ADR-0002~0004)만 보고 쓴다. **T1 회귀에 묶이지 않게**, `confirmed_at`에 기대는 테스트(A1 리뷰 기간·A4 분쟁 기간 알림·A2 기준 금액)는 확정을 `delivered_at + 7일` 안에 구매자 확정으로 만들거나 `confirmed_at`을 픽스처로 직접 둔다(T1 정답은 늦은 확정·자동 확정의 시각만 바꾼다). C2 회귀는 ADR-0006 §5의 픽스처 규칙(제한 + ACTIVE 입찰 조합 피하기)을 그대로 따른다. 기존처럼 직렬 실행, 자기 행만 단언, 픽스처는 테스트가 만든다(새 테이블 픽스처 도우미 `Db3` — 구현 때), 끝에 verify 1회(시드 빌드 전용 검사는 끈 채).
- 기능별 god 증가 추정(이 상세도 기준): A1 280 · A2 300 · A3 320 · A4 330 · A5 280 · A6 320 · A7 260 · A8 180 · A9 380 · A10 140 · A11 260 · A12 340 = **≈ 3,390줄**(ADR-0006 §4.1의 ≈ 3,230보다 조금 많다 — 실측은 구현 때).

## 19. 개정 1 — contract3 작성에서 나온 보완 (2026-10-29)

행동 테스트(`docs/plans/2026-10-29/contract3-tests/`)를 쓰며 이 문서가 정하지 않은 곳이 나왔다. 아래는 기존 공통 규칙에서 끌어내거나 Claude가 정한 값이다 **[제안]**(§16과 같은 지위 — 완료 보고에서 사용자 확인). 계수 오류 정정(§3·§17의 2 새 테이블 20 → 19)도 이 개정이다.

**공통 입력 규칙(§2.1 보완)**

1. 본문의 키가 **없는 것은 null과 같다**. 필수 값이면 400, null을 허용하는 값(예: `vacationFrom`)이면 null로 본다.
2. 문자열 자리는 JSON 문자열만, 정수 자리는 정수 JSON 숫자만 받는다(`5.0`·`"5"` → 400 — 기존 두 구현의 본문 파싱 관례와 같다). `accountNo`는 JSON 문자열이고 앞뒤 공백을 포함해 숫자 10~14자리여야 한다(공백이 있으면 400).
3. 쿼리와 본문의 enum·boolean 값은 문서에 적은 글자 그대로만 받는다(대소문자 구분 — 본문 `"cj"` → 400, 쿼리 빈 값 `?unread=` → 400).
4. 문서에 쿼리로 적은 날짜·필터 중 **필수**는 `GET /inbound-slots`의 `date`, 시세 조회의 `from`·`to`, `GET /admin/users/restricted`의 `type`이다(누락 400). 그 밖의 필터는 선택이다.
5. 시각 입력(`occurredAt`·`slotStart`)은 오프셋 포함 ISO-8601만 받는다(오프셋 없으면 400). PostgreSQL `timestamptz` 범위 밖이 되는 시각(확장 연도 등)은 계약 밖이다(§8-1과 같은 처리 — 400 `UNEXPECTED_ERROR`도 계약 위반 아님). 날짜 입력(쿼리 `date`·`from`·`to`, 본문 `vacationFrom`·`vacationTo`)은 정확히 `[0-9]{4}-[0-9]{2}-[0-9]{2}`이고 실제 날짜여야 한다 — ISO 확장 연도(`+10000-01-01`·`-0001-01-01`)·없는 날짜는 400(god 구현 리뷰 2026-10-29).
6. "앞뒤 공백"은 ADR-0004 §15의 4와 같은 공백 집합(Kotlin `isWhitespace` — NBSP·전각 공백 포함)이다. 길이(§2.1)도 그 공백을 앞뒤에서 뺀 값으로 센다.
7. 이 문서의 헤더가 필요 없는 새 엔드포인트(`GET /sellers/{id}/reviews`·`/profile`, `/product-sizes/{id}/price-stats`·`/recent-trades`, `/inbound-slots`, 개정 2: `GET /inbound-slots/{id}` §20.5.2)는 `X-User-Id`를 **읽지 않는다**(잘못된 값이 와도 401이 아니다).
8. 경로 변수가 정수가 아닌 경우는 계약 밖이다(프레임워크 라우팅 — 기존 엔드포인트와 같다).
8-1. 정수 입력이 상한 없이 정의된 곳(예: §10.3 `supplyAmount`)에서 계산이 bigint를 넘는 값(부가세·합계 오버플로)은 계약 밖이다 — ADR-0003 §12의 1대로 400 `UNEXPECTED_ERROR`가 나도 계약 위반이 아니다(god 구현 리뷰 2026-10-29).

**기능별 보완**

9. **§6.2** 반려 요청의 `reason` 키 누락은 null과 같아 400(1). 형식 검사는 `approve` 값과 무관하게 먼저 한다 — 승인 요청이어도 `reason`이 문자열도 null도 아니면(예: `5`) 400(2). 승인 시 "무시"는 형식이 맞는 문자열 값을 저장하지 않는다는 뜻이다.
10. **§8.1** `activeAlert` = 그 사용자의 그 사이즈 `ACTIVE` 가격 알림(최대 1건 — 부분 UNIQUE).
10-2. **§8.1·§8.2** 위시리스트·가격 알림 등록의 "없는 사이즈 404"는 `SELECT id FROM product_sizes WHERE id = ? FOR KEY SHARE`로 판정하고 **사용자 행 잠금보다 먼저** 둔다 — 잠금 순서를 기존 입찰·결제 만료 경로와 같은 "사이즈 → 사용자"로 맞춘다(INSERT의 FK 검사가 사이즈에 KEY SHARE를 걸므로 사용자 → 사이즈면 입찰 등록과 교착 — god 누적 리뷰 2026-10-29).
10-1. **§8.1** 위시리스트 삭제도 등록과 같이 **사용자 행 `FOR UPDATE`** 뒤 그 행을 다시 찾는다(없으면 404). 같은 행의 동시 삭제는 하나만 200, 다른 하나는 404(§2.3 — god 구현 리뷰 2026-10-29).
9-1. **§9.1** `orderItemId`가 있는 문의 작성은 그 항목의 **주문 행 → 항목 행을 `FOR KEY SHARE`**(없는 항목이면 404)로 INSERT보다 먼저 잡는다(주문은 FOR UPDATE가 아니라 KEY SHARE — pg-inquiry가 사용자 FU를 쥔 채 주문에 KEY SHARE를 걸기 때문) — INSERT의 FK 검사가 사용자 → 항목 순으로 KEY SHARE를 걸면 주문 → 항목(FOR UPDATE) → 사용자(FOR UPDATE)인 기존 항목 절차(취소·확정·검수·배치)와 교착한다(god 누적 리뷰 2026-10-29).
9-2. **§4.1** 리뷰 작성은 INSERT 전에 구매자·판매자 행을 `SELECT id FROM users WHERE id IN (?, ?) ORDER BY id FOR KEY SHARE`로 잡는다 — INSERT의 FK 검사는 구매자 → 판매자 순(id 순 아님)이라, id 오름차순 FOR UPDATE를 쓰는 판매자 귀책(다른 주문의 같은 두 사용자)과 교착할 수 있다(god 누적 리뷰 2026-10-29). 같은 패턴이 기존 체결 INSERT(`trades` 구매자·판매자 FK)에도 있으나 기존 경로라 바꾸지 않는다(ADR-0006 §3의 2 — 잔여 위험으로 기록).
11. **§9.1** `ANSWERED` 문의에 추가 메시지가 와서 `OPEN`이 될 때 `answered_at`·`first_answered_at`은 그대로 둔다(자동 종결 대상에서는 상태가 `ANSWERED`가 아니므로 빠진다).
12. **§10.3** 정정 응답의 `cancelled`는 **새 `CANCEL` 행**이다. 원본은 `status`만 `CANCELLED`로 바뀌고 `issued_at`을 포함한 다른 열은 그대로다("시각은 모두 현재"는 새로 쓰는 두 행의 `issued_at`).
13. **§11.3** `summary.sellerGrade` = `effective_from`이 가장 늦은 판매자 등급 행(§5.4 `current`와 같다). `GET /admin/settlements?sellerId=`의 사용자가 없으면 빈 목록이다(필터이므로 404가 아니다).
14. **§12.1** `intro`는 필수 문자열(빈 문자열 허용, null·누락 400). `vacationFrom`·`vacationTo` 키 누락은 null(1).

## 20. 개정 2 — 단계 A 보충 A13~A17 (2026-10-30, 초안 — 합의 전)

ADR-0006 §6.1-7 크기 확인(2026-10-30)에서 god가 5,228줄로 5,500줄에 못 미쳤다. 사전 등록 규칙대로 묶음을 더한다(ADR-0006 개정 2). 기능 다섯 개는 사용자가 골랐다(2026-10-30, 후보 조사 `docs/plans/2026-10-30/contract-b/`). 번호는 A13 = F1 … A17 = F6이다(F5는 고르지 않았다). 아래 **[제안]**은 §16과 지위가 같다 — 합의 때 §20.13 목록으로 검토한다.

### 20.1 공통

- **스키마 무변경.** 테이블·열·CHECK·UNIQUE·부분 UNIQUE·FK(61)·인덱스를 바꾸지 않는다. 시드(§14)와 `verify.sql`도 바꾸지 않는다(§20.9·§20.11). 그래서 새 알림 `kind`·새 감사 `action`·새 상태 값은 없다(셋 다 `CHECK IN`이 닫혀 있다).
- **새 `/admin` 쓰기 없음.** A15의 두 `/admin` 요청은 조회다. 감사 로그(§2.7) 대상은 7개 그대로다.
- **기존 22개 테이블에 쓰지 않는다**(§2.3 그대로) **[불변식 2]**. 새 쓰기는 모두 단계 A 테이블(`reviews`·`price_alerts`·`inquiries`·`inquiry_messages`·`inbound_reservations`·`notifications`)에 한다.
- 오류 code·판정 순서·입력 규칙은 §2·§19 그대로다(401 → 400 → 404 → 403 → 409, 상태 위반이 기한 위반보다 먼저). 각 절의 409는 **적은 번호 순서**로 판정한다.
- 기존 응답 모양(리뷰·가격 알림·문의·예약·슬롯·알림 모양)은 **바꾸지 않는다**. 단계 A 테스트(168)가 그 모양을 단언하기 때문이다. 새 필드는 새 엔드포인트의 새 모양에만 둔다.
- 새 배치 2개(`price-alert-rearm`·`notification-purge`). 배치 공통(§2.6)을 그대로 따른다: 격리, id 순, 대상 1건 = 트랜잭션 하나, 기간 경계는 앱이 계산한 `:due`를 바인드(두 구조의 대상 SQL이 같게 — §13 끝), 잠금이 있으면 잠근 뒤 같은 조건을 다시 확인하고 바뀌었으면 건너뛴다(실패 아님). 모든 배치에서 같은 시각에 다시 돌리면 `processed` = 0이다.

### 20.2 A13 리뷰 수정 · 쓸 수 있는 항목 · 내 리뷰

#### 20.2.1 수정 `PATCH /reviews/{id}` `{"rating": 1~5 정수, "content": 문자열}`

- `rating`·`content` **둘 다 필수** **[제안]**(부분 갱신 없음 — §19의 1 "키 없음 = null"과 겹쳐 null 뜻이 둘이 되지 않게). 값 규칙은 작성(§4.1)과 같다: `rating` 1~5, `content` 1~1,000자(아니면 400).
- 순서: 401 → 400 → 404(리뷰) → 403(작성자 `buyer_id`가 아님) → **주문 행 `FOR UPDATE`**(리뷰 항목의 주문) → **리뷰 행 `FOR UPDATE`** → 409:
  1. 리뷰가 `VISIBLE`이 아니면 409 `ITEM_STATE_CONFLICT`(`HIDDEN`·`DELETED`).
  2. 항목 상태가 `CONFIRMED`가 아니면 409 `ITEM_STATE_CONFLICT`. 분쟁이 제기됐지만 `rating-recalc` 전이라 아직 `VISIBLE`인 리뷰(항목 `DISPUTED`·`REFUNDED_AFTER_CONFIRM`)가 여기서 막힌다.
  3. `현재 > confirmed_at + 30일`이면 409 `DEADLINE_PASSED`. 작성과 같은 창이다(30일 정각은 고칠 수 있다).
- 결과: `rating`·`content` = 받은 값, `updated_at` = 현재. `created_at`·`status`·`hidden_reason`·당사자 열은 그대로다. 값이 지금과 같아도 쓴다(멱등 처리 없음 **[제안]**). 응답 `200` 리뷰 모양(§4.1 — 모양 그대로).
- 같은 리뷰에 동시에 온 두 수정은 리뷰 행 잠금으로 줄을 서고 **둘 다 200**이다. 상태를 소모하지 않는 수정이라 뒤 요청도 잠근 뒤 1~3을 통과한다. 최종 `rating`·`content`는 나중에 잠금을 얻은 요청의 값이다(§2.3).
- 평점(`seller_ratings`)은 바로 고치지 않는다. 다음 `rating-recalc`가 VISIBLE 합을 다시 세어 반영한다(§4.5의 3 — 값이 다르면 UPDATE). 판매자 리뷰 조회(§4.4)는 그사이 평점과 리뷰 목록의 시점이 다를 수 있다(§4.4에 이미 적은 성질).
- 잠금 이유:
  - 주문 행은 분쟁 제기(ADR-0004 §4.3 — 주문 → 항목 `FOR UPDATE`)와 줄을 세운다. 그래서 2의 판정과 분쟁 제기가 엇갈리지 않는다(작성 §4.1과 같은 이유).
  - 리뷰 행은 삭제·숨김·`rating-recalc`·다른 수정과 줄을 세운다.
  - 순서는 주문 → 리뷰다. 리뷰 행을 쥔 채 주문 행을 기다리는 경로는 없다(§20.8).
  - UPDATE는 FK 열을 바꾸지 않으므로 FK 검사(KEY SHARE)를 걸지 않는다.

#### 20.2.2 쓸 수 있는 항목 `GET /me/reviewable-items`

- 대상: 내가 구매자(`orders.buyer_id`)인 주문 항목 중 상태 `CONFIRMED`이고, 그 항목의 리뷰 행이 없고(상태 무관 — 지운 리뷰도 있음으로 친다, §4.1), `현재 ≤ confirmed_at + 30일`인 것. 작성(§4.1)이 409 없이 받는 항목과 같은 집합이다.
- 응답 `[{"orderItemId", "sellerId", "confirmedAt", "reviewDeadline"}]`, `orderItemId` 오름차순. `sellerId` = 체결 판매자. `reviewDeadline = confirmed_at + 30일`은 **계산해 보여 주기만** 하고 저장하지 않는다.

#### 20.2.3 내 리뷰 `GET /me/reviews`

- 내가 쓴(`buyer_id` = 나) 리뷰 전부(세 상태), `reviewId` 오름차순.
- 항목 모양(내 리뷰 모양) = 리뷰 모양 + `"hiddenReason"`·`"updatedAt"`·`"reviewDeadline"`(그 항목의 `confirmed_at + 30일` — 수정 기한, 계산 값). 리뷰가 있는 항목은 `confirmed_at`이 NULL이 아니다(verify `a0_time_order`가 상시 확인).

### 20.3 A14 가격 알림 목표가 변경 · 재등록 · 재무장

#### 20.3.1 목표가 변경 `PATCH /me/price-alerts/{id}` `{"targetPrice": 양의 정수}`

- 순서: 401 → 400(`targetPrice` 누락·정수 아님·0 이하) → 404 → 403 → 알림 행 `FOR UPDATE` → `ACTIVE`가 아니면 409 `ITEM_STATE_CONFLICT`.
- 결과: `target_price`만 바꾼다. `expires_at`·`created_at`·`status`는 그대로다(유효 기간을 늘리지 않는다). 값이 같아도 200. 응답 `200` 가격 알림 모양.
- 같은 알림에 동시에 온 두 변경은 알림 행 잠금으로 줄을 서고 **둘 다 200**이다. 최종 `target_price`는 나중에 잠금을 얻은 요청의 값이다(§2.3). 변경과 취소·scan이 겹치면 상태를 바꾼 쪽이 먼저 잠갔을 때만 변경이 409다.
- 저장 상태만 본다. `expires_at ≤ 현재`인데 `price-alert-scan` 전이라 아직 `ACTIVE`인 알림도 바꿀 수 있다(취소 §8.2와 같다) **[제안]**.
- 다음 `price-alert-scan`은 새 목표가로 판정한다. 발동 뒤에는 바꿀 수 없으므로(`ACTIVE`만) verify `a5_alert_trigger_bid`(발동 입찰 `price ≤ target_price`)가 계속 성립한다.

#### 20.3.2 재등록 `POST /me/price-alerts/{id}/renew` (본문 없음)

- 순서: 401 → 404 → 403 → **그 알림 사이즈 KEY SHARE**(§19의 10-2 `keyShareSize`) → **사용자 행 `FOR UPDATE`** → 409:
  1. 알림이 `EXPIRED`·`TRIGGERED`가 아니면 409 `ITEM_STATE_CONFLICT`(`ACTIVE`·`CANCELLED`. 취소는 사용자가 끈 것이라 되살리지 않는다).
  2. 같은 (사용자, 사이즈)에 **이 알림보다 id가 큰 알림**(상태 무관)이나 **`ACTIVE` 알림**이 있으면 409 `ITEM_STATE_CONFLICT`. 재등록은 그 쌍의 마지막 알림에서만 한다. 계약 안에서는 같은 쌍의 `ACTIVE` 알림이 늘 더 큰 id다. 그래도 둘째 항을 두어 부분 UNIQUE `price_alerts_live_user_size_key` 위반(400 `UNEXPECTED_ERROR`)이 생길 길을 막는다.
  3. `현재 > closed_at + 7일`이면 409 `DEADLINE_PASSED`(7일 정각은 된다) **[제안]**.
- 결과: 새 알림 1행 — `ACTIVE`, 같은 `target_price`, `expires_at = 현재 + 60일`(등록 §8.2와 같은 모양 — `closed_at`에 기간을 더하거나 `min`으로 자르지 않는다 **[불변식 7 — T1]**), `created_at` = 현재. 옛 알림은 그대로다. 응답 `201` 새 알림의 가격 알림 모양.
- 등록(§8.2)처럼 구매 제한 중에도 할 수 있고 위시리스트가 없어도 된다.
- 잠금 이유:
  - 사용자 행으로 같은 사용자의 등록·재등록·재무장(모두 사용자 행을 잠근 뒤 2를 판정)이 줄을 서서 2와 INSERT 사이에 새 알림이 끼지 않는다.
  - 사이즈 KEY SHARE를 먼저 잡는 이유는 등록과 같다(§19의 10-2). INSERT의 FK 검사가 사이즈에 KEY SHARE를 걸기 때문에 "사이즈 → 사용자" 순서를 지켜야 한다.
  - 옛 알림 행은 잠그지 않는다. `EXPIRED`·`TRIGGERED`·`CANCELLED`는 다시 바뀌지 않는다(바뀌는 것은 `ACTIVE` → 종결뿐). 1에서 `ACTIVE`를 읽었다면 그 순간의 상태로 409다.

#### 20.3.3 배치 `price-alert-rearm`

- 대상 SQL(별칭 `t` = `price_alerts`, 앱이 `:now`·`:due = 현재 − 7일`을 바인드):
  ```sql
  t.status = 'TRIGGERED' AND t.closed_at >= :due
  AND EXISTS (SELECT 1 FROM bids b WHERE b.id = t.triggered_bid_id AND b.status IN ('CANCELLED', 'EXPIRED'))
  AND EXISTS (SELECT 1 FROM wishlists w WHERE w.user_id = t.user_id AND w.product_size_id = t.product_size_id)
  AND NOT EXISTS (SELECT 1 FROM price_alerts n WHERE n.user_id = t.user_id AND n.product_size_id = t.product_size_id
                  AND (n.id > t.id OR n.status = 'ACTIVE'))
  AND NOT EXISTS (SELECT 1 FROM users u WHERE u.id = t.user_id AND u.purchase_restricted_until > :now)
  ```
  - 종결 7일 창은 재등록 기한과 같다(정각 포함).
  - 발동 입찰 조건은 **되돌아올 수 없는 상태**(`CANCELLED`·`EXPIRED`)인 것으로 좁힌다 **[제안]**. 판정은 **저장된 `bids.status`**만 본다. `expires_at`이 지났지만 `bid-expiry` 전인 입찰은 아직 `ACTIVE`이므로 대상이 아니다 — `bid-expiry`로 `EXPIRED`가 된 뒤의 실행에서 대상이 된다(종결 7일 창 안일 때).
    - 근거(god 코드 확인): 입찰을 `ACTIVE`로 쓰는 곳은 새 입찰(`Marketplace.kt:214`)과 결제 기한 만료의 판매 입찰 복구(`:1482` — `MATCHED` → `ACTIVE`, 유효 기간이 남았으면)뿐이다. 그래서 `CANCELLED`·`EXPIRED`는 끝 상태다.
    - `MATCHED`는 제외한다: 체결이 결제 기한을 넘기면 같은 판매 입찰이 `ACTIVE`로 돌아와(`:1482`), 재무장한 알림이 **같은 입찰로 다시 발동**한다(같은 입찰에 `PRICE_ALERT` 2회). 그 대가로 결제까지 끝나 계속 `MATCHED`인 입찰의 알림도 재무장하지 않는다(사용자는 재등록 §20.3.2로 되살릴 수 있다).
  - 구매 제한 중인 사용자의 알림은 대상이 아니다(건너뜀). 저장된 `purchase_restricted_until`만 읽는다 **[불변식 7 — C2]**. 제한이 끝난 뒤에도 7일 창 안이면 그때 재무장한다.
- 알림 1건(`price_alerts` id 순): 알림의 (사용자, 사이즈)를 읽고 → **사이즈 KEY SHARE → 사용자 행 `FOR UPDATE`**(재등록·§19의 10-2와 같다) → 같은 조건을 `t.id = :id`로 다시 확인한다. 성립하면 새 알림 1행(재등록과 같은 값: `ACTIVE`, 같은 `target_price`, `expires_at = 현재 + 60일`, `created_at` = 현재)을 쓰고, 아니면 건너뛴다. 알림 행(`notifications`)은 만들지 않는다.
- 재확인이 믿을 만한 이유: 재확인의 각 조건은 잡은 잠금으로 고정된다.
  - 입찰 상태: `CANCELLED`·`EXPIRED`는 끝 상태라 읽은 뒤 바뀌지 않는다. 대상 조회 뒤 `ACTIVE` → `CANCELLED`·`EXPIRED`로 바뀐 것은 재확인에서 잡힌다 — 입찰 상태를 바꾸는 처리는 모두 사이즈 행 `FOR UPDATE` 뒤에 하므로(ADR-0003 §3.3) 사이즈 KEY SHARE를 쥔 뒤의 재확인은 커밋된 최신 상태를 본다.
  - 위시리스트·더 새 알림·구매 제한: 사용자 행 잠금 아래에서 바뀐다(위시 등록·삭제, 등록·재등록, 결제 기한 경고). 예외는 다른 알림의 `ACTIVE` → 종결(scan·취소)인데, 이것은 대상을 줄이는 쪽이라 놓쳐도 다음 실행이 다시 본다.
- `processed` = 만든 새 알림 수. 재실행 0: 새 알림이 생기면 `n.id > t.id`가 그 옛 알림을 대상에서 뺀다.
- 입찰에는 아무것도 쓰지 않는다(읽기). 재무장한 알림이 다른 SELL 입찰로 다시 발동하는 것은 다음 `price-alert-scan`이 한다.

### 20.4 A15 문의 재개 · 관리자 문의함

#### 20.4.1 재개 `POST /me/inquiries/{id}/reopen` `{"body"}`

- 순서: 401 → 400(`body` 1~2,000자, §9.1) → 404 → 403(작성자 아님) → 문의 행 `FOR UPDATE` → 409:
  1. `CLOSED`가 아니면 409 `ITEM_STATE_CONFLICT`.
  2. `현재 > closed_at + 7일`이면 409 `DEADLINE_PASSED`(종결 사유 `USER`·`AUTO` 모두 같다. 7일 정각은 된다) **[제안]**.
- 결과: `status = OPEN`, `close_reason` NULL, `closed_at` NULL, USER 메시지 1행(`created_at` = 현재). `answered_at`·`first_answered_at`·`sla_due_at`은 그대로다. 그래서 `slaBreached`(§9.1)는 첫 답변 기준 그대로 계산된다. 응답 `200` 문의 모양.
- 재개 횟수에는 제한이 없다 **[제안]**. 다시 닫히면 그 새 `closed_at`부터 7일이다.
- 재개된 문의는 `OPEN`이라 `inquiry-auto-close` 대상이 아니다. 다시 답변을 받아 `ANSWERED`가 되면 그 새 `answered_at`부터 7일 뒤 대상이 된다(§9.3 그대로).
- 잠금: 문의 행 하나. 추가 메시지·답변·종결·`inquiry-auto-close`와 같은 행 잠금이라 줄을 선다. 메시지 INSERT의 FK 검사는 이미 `FOR UPDATE`로 쥔 문의 행에 KEY SHARE를 건다.

#### 20.4.2 관리자 문의함 `GET /admin/inquiries` · `GET /admin/inquiries/{id}`

- `GET /admin/inquiries` — §2.5 키셋 페이지를 쓴다(§2.5에 반영). 정렬은 `inquiryId` 오름차순이다.
  - 필터(둘 다 선택): `status`(`OPEN`·`ANSWERED`·`CLOSED`)와 `slaBreached`(`true`·`false`). 글자 그대로만 받고, 아니면 400이다(§19의 3).
  - 항목 = 문의 모양 + `"userId"`.
  - `slaBreached` 필터는 응답 값(§9.1)과 같은 식이다: `coalesce(t.first_answered_at > t.sla_due_at, t.sla_due_at < :now)`가 참이면 `true`, 거짓이면 `false`. NULL이 생기지 않게 `coalesce`로 쓴다 — `(a IS NULL AND …) OR a > b` 꼴은 미답변 행에서 NULL이 되어 `false` 필터에서 빠진다.
- `GET /admin/inquiries/{id}` — 없는 문의 404. 응답은 상세 모양(§9.2: 문의 모양 + `messages` + `orderItem`)에 `"userId"`를 더한 것이다. 한 스냅샷에서 읽는다.
- 둘 다 조회라 감사 로그를 쓰지 않는다(§2.7은 쓰기만).

### 20.5 A16 입고 예약 변경 · 슬롯·예약 상세

#### 20.5.1 예약 변경 `POST /me/inbound-reservations/{id}/reschedule` `{"slotId"}`

- 순서: 401 → 400(`slotId` 누락·정수 아님) → 404(예약) → 404(새 슬롯) → 403(예약의 `seller_id`가 아님) → 잠금·409. 잠금과 판정은 이 순서로 섞인다:
  1. **주문 행 `FOR UPDATE`**(예약 항목의 주문) → 항목이 `PAID`가 아니면 409 `ITEM_STATE_CONFLICT`. 다른 잠금보다 **먼저** 판정한다(§12.3의 1 괄호와 같은 이유 — god 구현 리뷰 GF-7: PAID 아닌 항목의 요청이 슬롯을 기다리며 교착 고리에 끼지 않게).
  2. **예약 행 `FOR UPDATE`** → 예약이 `RESERVED`가 아니면 409 `ITEM_STATE_CONFLICT`.
  3. 새 슬롯이 지금 슬롯과 같으면 409 `ITEM_STATE_CONFLICT`.
  4. **새 슬롯 행 `FOR UPDATE`** → 새 슬롯의 살아 있는 예약(`RESERVED`·`CHECKED_IN`) 수 ≥ `capacity`면 409 `ITEM_STATE_CONFLICT`(정원 — 상태 위반으로 보아 기한보다 먼저, §12.3의 2).
  5. `현재 > 옛 slot_start − 2시간`이면 409 `DEADLINE_PASSED`(취소 기한, §12.3).
  6. `현재 > 새 slot_start − 24시간`이면 409 `DEADLINE_PASSED`(예약 기한, §12.3).
- 쓰기는 한 트랜잭션에서 **이 순서로** 한다. 부분 UNIQUE `inbound_reservations_live_item_key` 때문에 옛 예약 UPDATE를 INSERT보다 먼저 DB에 보내야 한다 — ORM이면 옛 예약을 바꾼 뒤 명시적 flush(§10.3 정정과 같은 함정).
  1. 옛 예약 → `CANCELLED`, `closed_at` = 현재.
  2. (flush)
  3. 새 예약 1행 INSERT: `RESERVED`, `slot_id` = 새 슬롯, 같은 `seller_id`·`order_item_id`, `created_at` = 현재.
- 응답 `200 {"cancelled": 옛 예약의 예약 모양, "reserved": 새 예약의 예약 모양}` **[제안]**(정정 §10.3과 같은 꼴).
- 그 항목의 살아 있는 예약은 옛 예약 하나뿐이다(부분 UNIQUE). 그래서 §12.3의 "살아 있는 예약이 있으면 409"는 여기서 판정하지 않는다.
- 잠금 순서(주문 → 예약 → 새 슬롯)와 기존 경로의 대조는 §20.8에 있다. 새 INSERT의 FK 검사(슬롯 KEY SHARE — 이미 쥔 행, 판매자 `users` KEY SHARE, 항목 KEY SHARE)는 예약(§12.3)과 같은 집합·같은 순서다. 그 앞에 쥐는 잠금도 예약과 같은 "주문 FU를 먼저" 꼴이다.

#### 20.5.2 상세 조회

- `GET /inbound-slots/{id}` (헤더 불필요 — §19의 7에 반영) → 없는 슬롯 404. 응답 슬롯 모양(`reserved` = 살아 있는 예약 수, §12.3).
- `GET /me/inbound-reservations/{id}` → 없는 예약 404, 남의 예약 403. 응답 예약 모양.

### 20.6 A17 알림 일괄 읽음 · 보존 기한 정리 · 안 읽은 수

#### 20.6.1 일괄 읽음 `POST /me/notifications/read-all` (본문 없음)

- 내 알림 중 `read_at` NULL인 행을 **id 오름차순으로 `FOR UPDATE`**(`SELECT id FROM notifications WHERE user_id = ? AND read_at IS NULL ORDER BY id FOR UPDATE`)한다. 잠근 뒤에도 `read_at`이 NULL인 행만 `read_at` = 현재로 바꾼다.
- 응답 `200 {"readCount": 바꾼 수}`. 0건도 200이다 **[제안]**. 단건 읽음(§7.2)은 이미 읽은 알림에 409를 주지만, 묶음 요청에서는 "읽을 것이 없음"을 상태 충돌로 보지 않는다.
- 같은 알림의 단건 읽음과 겹치면 먼저 잠근 쪽만 그 행을 읽음 처리한다. 다른 쪽은 409(단건)이거나 그 행을 빼고 센다(일괄).
- id 순 잠금은 같은 사용자의 일괄 읽음 두 요청이 서로 다른 순서로 행을 잡아 교착하지 않게 하려는 것이다. 사용자 행은 잠그지 않는다.
- 요청 중에 배치가 새로 만든 알림은 포함되지 않을 수 있다(잠금 문장 시점의 행).

#### 20.6.2 안 읽은 수 `GET /me/notifications/unread-count`

- `{"unreadCount": 내 알림 중 read_at NULL 수}`. `GET /me/notifications?unread=true`의 길이와 같다.

#### 20.6.3 배치 `notification-purge`

- 대상: `t.read_at IS NOT NULL AND t.read_at <= :due`(`:due = 현재 − 30일`, 30일 정각은 지운다) **[제안]**. 안 읽은 알림은 오래돼도 지우지 않는다.
- 알림 1건: `DELETE FROM notifications WHERE id = :id AND read_at <= :due`. **잠금 없음** — 문장 하나가 행 잠금과 조건 재평가를 함께 한다. `read_at`은 NULL로 돌아가지 않으므로 조건이 바뀌는 경우는 이미 지워진 것뿐이다. `processed` = 지운 행 수(재실행 0).
- 새 테이블 행이라 DELETE를 허용한다(위시리스트 §8.1 선례). `notifications`를 참조하는 FK는 없다.
- **지운 알림이 dedupe로 다시 생기지 않는다.** 전제는 셋이다:
  - (가) `read_at ≥ created_at`(§7.2, verify `a0_time_order`).
  - (나) 리마인더 알림의 `created_at`은 그것을 만든 배치 시각이고, 그 시각에 대상 조건이 성립했다(§7.1).
  - (다) 제한 기간은 고정이고 30일 이하다 — 구매 7일·판매 30일, 끝 = 건 시각 + 기간(ADR-0004).

  지울 수 있으면 `created_at ≤ read_at ≤ 현재 − 30일`이다. 이때 각 종류는 지금 대상 조건을 만족할 수 없다.

| kind | 지금 대상 조건 | 만든 시각 t(≤ 현재 − 30일)에서 아는 것 | 그래서 |
|---|---|---|---|
| `PAYMENT_DUE_SOON` | `현재 < payment_due_at` | `payment_due_at ≤ t + 10분` | `payment_due_at ≤ 현재 − 30일 + 10분 < 현재` |
| `SHIPMENT_DUE_SOON` | `현재 < paid_at + 3일` | `paid_at ≤ t − 2일` | `paid_at + 3일 ≤ 현재 − 29일` |
| `DISPUTE_WINDOW_CLOSING` | `현재 ≤ confirmed_at + 14일` | `confirmed_at ≤ t − 12일`(확정 뒤 `confirmed_at`은 바뀌지 않는다 — 기각도 그대로, ADR-0004 §4.4) | `confirmed_at + 14일 ≤ 현재 − 28일` |
| `SETTLEMENT_PAID` | `현재 − 7일 < paid_at` | `paid_at ≤ t`(시드는 `created_at = paid_at`, §14) | `paid_at ≤ 현재 − 30일` |
| `PURCHASE_/SALE_RESTRICTED` | 끝 시각 `E > 현재`(키에 `E`) | 건 시각 `s ≤ t`, `E = s + 7일 또는 30일 ≤ t + 30일` | `E ≤ 현재`. 제한이 다시 걸리면 `E`가 달라 새 키 = 새 알림(§7.1 연장과 같다 — 재생성이 아니다) |
| `PRICE_ALERT` | 키 = 알림 id. 알림이 `ACTIVE` → `TRIGGERED`로 바뀔 때 한 번 | 알림은 `ACTIVE`로 돌아가지 않는다 | 다시 생기지 않는다. 재등록·재무장은 새 알림 id다 |

  리마인더 창(10분·1일·2일·7일)과 제한 기간(≤ 30일)이 모두 보존 기한 30일보다 짧거나 같아서 성립한다. 이 값을 바꾸는 개정은 이 표를 다시 대조해야 한다. 전제를 깨는 픽스처(제한 끝을 건 시각 + 30일보다 뒤로 직접 넣기 등)는 계약 밖이다(§20.12).

### 20.7 배치 일람 추가 · 구현 위치 (§13 형식)

| name | 기능 | 대상(id 순) | `processed` |
|---|---|---|---|
| `price-alert-rearm` | A14 | `TRIGGERED` · 종결 7일 안 · 발동 입찰 `CANCELLED`·`EXPIRED` · 위시리스트 있음 · 그 쌍에 더 새 알림·`ACTIVE` 없음 · 구매 제한 아님 | 만든 새 알림 수 |
| `notification-purge` | A17 | 읽은 지 30일 지난 알림 | 지운 수 |

- 단계 A 배치는 13개가 된다(§2.6의 11 + 2). `runBatch`의 이름 분기는 기존 8 + 단계 A 11 = 19개(`Marketplace.kt:1330-1348`, 2026-10-30 확인)에서 **21개**가 된다. 단계 A 13개는 모두 격리한다(격리 예외는 기존 `weekly-settlement`뿐).
- 구현 위치 **[불변식 1]**:
  - **god:** `Marketplace.kt`의 해당 단계 A 블록(A1·A5·A6·A12·A4 절)에 함수를 더하고, `runBatch`에 두 분기를 더한다(키셋 순회 `forEachTarget` 재사용). 컨트롤러는 `web/`의 단계 A 컨트롤러(`ReviewController`·`NotificationController`·`InquiryController`·`InboundController`)이고, 새 응답 DTO는 `web/Dtos.kt`에 둔다. 새 엔티티는 없다.
  - **layered:** 단계 A 파일(ADR-0006 §3의 1 개정 2 해석 — 규모 확장 착수 전에 없던 파일)에 메서드·클래스를 더한다.
    - `service/ReviewService.kt`·`service/WishlistService.kt`(`PriceAlertService` + 새 `PriceAlertRearmBatch`)·`service/InquiryService.kt`·`service/InboundService.kt`·`service/NotificationService.kt`(+ 새 `NotificationPurgeBatch`)
    - 같은 기능의 `api/*Controller.kt`·`*Dtos.kt`·`infra/Jpa*Repositories.kt`·`domain/*.kt` — **단, 95cc1da 시점 파일**(예: `api/Dtos.kt`·`domain/Order.kt`·`domain/User.kt`·`infra/JpaOrderRepositories.kt`·`infra/JpaUserRepository.kt`)은 이 와일드카드에 들지 않는다. 그 파일은 **호출만** 하고, 아래 허용 목록 밖의 변경은 금지다.
    - 새 파일을 만들어도 된다.
  - **layered 기존 파일 변경**은 개정 1 허용 목록 안이다: `BatchService.run` 분기 2·생성자 주입, `BatchTarget` 값 2, `KeysetBatchTargets.spec` 분기 2.
- 두 구조의 대상 조회 SQL은 같아야 한다(§13 끝). 대상 식은 §20.3.3·§20.6.3의 식을 그대로 옮긴다.

### 20.8 잠금 순서 대조 (스스로 점검)

FK KEY SHARE 교착(§19의 9-1·9-2·10-1·10-2, god 구현 리뷰 GF-1·4~7)의 교훈은 하나다. **새 경로가 쥐는 잠금 + INSERT가 FK로 거는 KEY SHARE**를 기존 규약 셋과 같은 방향으로 맞춘다: "사이즈 → 사용자", "주문 → 항목 → 사용자", "사용자끼리 id 오름차순".

| 새 경로 | 잠금 순서(→ = 쥔 채 다음을 기다림) | 대조한 기존 경로 | 판정 |
|---|---|---|---|
| A13 수정 | 주문 FU → 리뷰 FU (UPDATE — FK 검사 없음) | 분쟁 제기·판정(주문 → 항목 FU), 리뷰 작성(주문 FU → 사용자 2명 KS), 삭제·숨김(리뷰 FU만), `rating-recalc`(판매자 리뷰들 FU id 순 → `seller_ratings` 쓰기의 FK로 판매자 `users` KS), `pg-inquiry`·승인 반영(사용자 FU → 주문 쓰기 — `Marketplace.kt:1503` → `approveOrder` `:698`), `price-alert-scan`(알림 FU → `triggered_bid_id` FK로 입찰 KS `:2596` → `users` KS) | 리뷰를 쥔 채 주문을 기다리는 경로가 없다. `rating-recalc`가 판매자 KS를 기다리는 동안 그 판매자 FU를 쥔 쪽(판매자 귀책: 주문 → 항목 → 사용자)은 주문을 먼저 잡았으므로 수정의 주문을 기다리지 않는다. 사용자 FU를 쥔 채 주문을 쓰는 `pg-inquiry`·승인 반영은 대상 주문 상태가 겹치지 않는다(`UNKNOWN`·`PAYING` 주문 vs 수정이 잠그는 주문 = 항목이 `CONFIRMED`인 결제 이후 주문). `price-alert-scan`은 주문·리뷰 행을 잡지 않는다 → 고리 없음 |
| A14 변경 | 알림 FU (UPDATE — FK 검사 없음) | 취소(알림 FU), `price-alert-scan`(알림 FU → `notifications` INSERT의 FK로 `users` KS) | 한 행 — 고리 없음 |
| A14 재등록 | 사이즈 KS → 사용자 FU → INSERT(FK: 사용자·사이즈는 이미 쥠, `triggered_bid_id` NULL) | 입찰 등록(사이즈 FU → INSERT FK `users` KS), `payment-due`(사이즈 FU → 사용자 FU), 등록(§19의 10-2), `price-alert-scan`(알림 FU → 사용자 KS) | 모두 "사이즈 → 사용자" 순서다. 재등록은 알림 행을 잠그지 않으므로 scan과 고리가 없다 → 기준 코드에서 고리 없음. **단, C2 정답 적용 코드**(`experiment/oracle/C2/fix.god.patch`)에서는 판매자 귀책·결제 기한 경고가 제한을 걸 때 사용자 FU를 쥔 채 입찰마다 사이즈 FU를 건다(사용자 → 사이즈). 그 사용자가 알림 주인이면 고리가 생길 수 있다. 위시 등록·가격 알림 등록(§19의 10-2)과 같은 잔여 위험이다(ADR-0005 §9의 13 — C2 잠금) |
| A14 재무장 | 사이즈 KS → 사용자 FU → INSERT(위와 같음) | 위와 같음 + 위시 등록·삭제(사이즈 KS → 사용자 FU / 사용자 FU) | 재등록과 같다 → 기준 코드에서 고리 없음. C2 정답 적용 코드에서는 재등록과 같은 잔여 위험(판매자 귀책: 사용자 FU → 사이즈 FU, `fix.god.patch` — ADR-0005 §9의 13) |
| A15 재개 | 문의 FU → 메시지 INSERT(FK: 쥔 문의 행 KS) | 추가 메시지·답변·종결·`inquiry-auto-close`(문의 FU) | 한 행 — 고리 없음 |
| A16 변경 | 주문 FU → (PAID 판정) → 예약 FU → 새 슬롯 FU → UPDATE·flush → INSERT(FK: 슬롯 KS(쥠) → 판매자 `users` KS → 항목 KS) | 예약 §12.3(주문 FU → 슬롯 FU → 같은 FK), 예약 취소·체크인·`inbound-no-show`(예약 FU만, 슬롯은 읽기), 항목 절차(주문 → 항목 → 사용자 FU), `pg-inquiry`·승인 반영(사용자 FU → 주문 KS·주문 쓰기 — `Marketplace.kt:1503` → `approveOrder` `:698`), `price-alert-scan`(알림 FU → 입찰 KS `:2596` → `users` KS) | 예약 행을 쥔 채 주문·슬롯을 기다리는 경로가 없다. 슬롯 FU를 쥐는 경로(예약·변경)는 모두 주문 FU를 먼저 쥐고, PAID 판정을 슬롯·예약 잠금 전에 한다(GF-7). 사용자 FU를 쥔 채 주문을 쓰는 `pg-inquiry`·승인 반영은 대상 주문 상태가 겹치지 않는다(`UNKNOWN`·`PAYING` 주문 vs 변경이 잠그는 주문 = 항목이 `PAID`인 결제 이후 주문). `price-alert-scan`은 주문·예약·슬롯 행을 잡지 않는다. FK KS는 §12.3 예약과 같은 집합·순서다 → 기존과 같은 위험만 있고 새 고리는 없다 |
| A17 일괄 읽음 | 내 안 읽은 알림 FU id 순 → UPDATE(FK 검사 없음) | 단건 읽음(알림 FU 1행), `notification-purge`(읽은 행 DELETE), `deadline-reminder`·`price-alert-scan`(새 행 INSERT) | 안 읽은 행 집합과 정리 대상(읽은 행)은 겹치지 않는다. 단건은 한 행이라 고리가 없다 |
| A17 정리 | DELETE 1행(그 행 잠금만) | 단건·일괄 읽음(안 읽은 행만 쓴다) | 고리 없음 |

### 20.9 스키마·제약·verify 대조 (스스로 점검)

verify는 상시 검사(행동 테스트 뒤에도 성립해야 함)와 시드 빌드 전용 검사(`ccle.seed_build = 1`)로 나뉜다. 시드 빌드 전용 검사는 행동 테스트 DB에서는 꺼져 있다.

| 기능 | 쓰는 열 | CHECK·UNIQUE | 상시 verify 검사 | 판정 |
|---|---|---|---|---|
| A13 수정 | `reviews.rating`·`content`·`updated_at` | `rating BETWEEN 1 AND 5`(400으로 먼저 막음) · `(status = 'HIDDEN') = (hidden_reason IS NOT NULL)`(상태 안 바꿈) · `UNIQUE(order_item_id)`(행 안 늘림) | `a0_time_order`·`a1_review_window`·`a1_review_not_in_dispute`는 `created_at` 기준이다(안 바꿈). `a1_review_parties`(당사자 안 바꿈). `a9_audit_target` REVIEW_HIDE: 숨겨진 리뷰는 VISIBLE로 돌아오지 않아 수정 대상이 아니다 | 위반 없음. 시드 전용 `a1_rating_cache`는 수정 뒤 배치 전에 다를 수 있다(이미 시드 전용으로 둔 이유와 같다) |
| A14 변경 | `price_alerts.target_price` | `target_price > 0`(400) | `a5_alert_trigger_bid`: ACTIVE만 바꾸므로 발동 뒤 target이 바뀌지 않는다 | 위반 없음 |
| A14 재등록·재무장 | 새 `price_alerts` 행 | `expires_at > created_at`(60일) · `(status = 'ACTIVE') = (closed_at IS NULL)` · `(status = 'TRIGGERED') = (triggered_bid_id IS NOT NULL)`(NULL) · 부분 UNIQUE `price_alerts_live_user_size_key`(2의 `ACTIVE` 항으로 막음) · FK 3 | `a0_time_order`(closed NULL), `a5_alert_expired_after`(새 행 ACTIVE) | 위반 없음 |
| A15 재개 | `inquiries.status`·`close_reason`·`closed_at`, 새 `inquiry_messages` 행 | `(status = 'CLOSED') = (closed_at IS NOT NULL)`·`(status = 'CLOSED') = (close_reason IS NOT NULL)`(둘 다 NULL로) · `sla_due_at` 식(안 바꿈) | `a6_inquiry_messages`: 첫 메시지 USER 그대로, ADMIN 메시지·답변 시각 그대로. `a6_auto_close_after_7d`: 다시 AUTO로 닫히려면 새 답변 + 7일. `a0_time_order` | 위반 없음 |
| A15 관리자 조회 | — | — | `a9_audit_target`·시드 전용 `a9_audit_coverage`: 감사 행 없음(조회) | 해당 없음 |
| A16 변경 | 옛 예약 `status`·`closed_at`, 새 예약 행 | `(status = 'RESERVED') = (closed_at IS NULL)` · 부분 UNIQUE `inbound_reservations_live_item_key`(UPDATE → flush → INSERT 순서) · FK 3 | `a12_capacity`(새 슬롯 FU 뒤 4), `a12_reservation_item_seller`(같은 seller), `a12_reservation_deadline`(6), `a12_reservation_item_paid`(1 — `created_at ≥ paid_at`, 발송은 뒤), `a12_reservation_close_window` CANCELLED(5), `a0_time_order` | 위반 없음 |
| A17 일괄 읽음 | `notifications.read_at` | — | `a0_time_order`(`read_at` = 현재 ≥ `created_at`) | 위반 없음 |
| A17 정리 | `notifications` 행 DELETE | — | `a4_notification_owner`·`a4_notification_dedupe_key`(남은 행만 본다) | 위반 없음. 시드 전용 `a4_reminder_complete`·`a5_trigger_notified`는 정리 뒤 깨질 수 있다. 그러나 시드에는 정리 대상이 없고(§20.11) 행동 테스트 DB에서는 꺼져 있다 |

- FK 수 61·부분 UNIQUE 4개·`schema_constraints_present`는 그대로다.
- 새 verify 검사는 두지 않는다 **[제안]**. 새 테이블·열이 없어 새로 묶을 행 간 규칙이 없다. 수정·재개·변경 이력은 행에 남지 않아(예: VISIBLE 리뷰의 `updated_at`은 `rating-recalc`의 숨김 해제로도 바뀐다) 상시 검사로 판정할 수 없다. 시드 전용 검사를 더해도 이미지를 다시 빌드할 때만 돈다(§20.11).

### 20.10 본보기 배제 (ADR-0006 §3의 7 — 스스로 점검)

| 본보기 | 이 묶음에서 닮을 수 있는 곳 | 판정 |
|---|---|---|
| C2 — 제한·제재 때 입찰 일괄 취소 | A14 재무장의 구매 제한 판정 | 저장된 `purchase_restricted_until`을 읽고 **건너뛰기만** 한다. 입찰·알림을 취소하지 않는다. 제한 때 알림·예약을 일괄 취소하는 기능은 후보에서 뺐다(log 05:45) |
| T1 — 기준 시각 + 기간으로 저장 시각 소급·상한(`min(현재, 기준 + N일)`) | A13 `reviewDeadline`(`confirmed_at + 30일`), A14 재등록·재무장 `expires_at`, A15·A16·A17 저장 시각 | 저장 시각은 모두 **현재**다(`updated_at`·`created_at`·`closed_at`·`read_at`). `expires_at = 현재 + 60일`은 등록과 같은 모양이고 `closed_at`·`expires_at`에 기간을 더하거나 `min`으로 자르지 않는다. `reviewDeadline`은 저장하지 않는 표시 값이고 판정은 비교(`현재 > 기준 + 30일`)만 한다(§2.4가 허용한 기한 판정). 기간 경계는 앱이 `:due = 현재 − N`으로 계산해 비교에만 쓴다 |
| T2·C1 — 패널티 계수 경계, 등급 경계·적립률 재구현 | 없음 | 패널티·경고 수를 세지 않는다. 등급·적립률을 읽지도 쓰지도 않는다. 노쇼 계수 제한은 후보에서 뺐다(log 05:45) |
| 주석 | 새 코드 전체 | 이식 때 저장소 전체 grep 규칙(ADR-0006 §3의 7 끝)이 새 코드에도 걸린다 |

- 수정 과제 고칠 곳·읽을 곳 **[불변식 3]**: 새 기능은 그 범위의 함수를 부르지 않는다. A13·A16은 `lockOrderOf`를 쓰지 않고 주문 행만 잠근다(단계 A §4.1·§12.3과 같은 꼴). A13 판정은 저장된 `confirmed_at`만 읽는다.

### 20.11 시드 영향 (§14)

- **시드 무변경**이다. 1k 이미지 `1k-s42-v2`를 다시 빌드하지 않는다. §14의 원칙("시드 = NOW에 배치·요청이 계약대로 돌았다면 남았을 행")과 맞는지 새 배치마다 확인했다.
  - `price-alert-rearm`을 NOW에 돌리면 대상은 0이다. 시드의 가격 알림은 `ACTIVE`·`EXPIRED`·`CANCELLED`뿐이고 `TRIGGERED`가 없다(§14, `stage_a.py` — ACTIVE는 NOW의 bestSell보다 낮은 목표가로 만든다).
  - `notification-purge`를 NOW에 돌려도 대상은 0이다. 시드에서 읽은 알림은 `SETTLEMENT_PAID`뿐이다. 그 `created_at`은 2026-09-01 00:00 KST 이후이고 `read_at`은 그보다 1시간 이상 뒤다. 따라서 모두 `read_at > NOW − 30일`(= 2026-09-01 00:00 KST)이다.
- 요청 이력(수정·재등록·재개·변경·일괄 읽음)은 시드에 넣지 않는다 **[제안]**. 일어나지 않았을 수 있는 요청이므로 원칙과 모순이 아니다.
- 기존 시드 행이 바뀌지 않으므로 시드 빌드 전용 검사도 그대로 성립한다.
- 시드 실측(2026-10-30, `1k-s42-v2`): 가격 알림 ACTIVE 106·EXPIRED 53·CANCELLED 30·TRIGGERED 0, 읽은 알림 271건 모두 `read_at` ≥ 2026-09-02 01:02 KST — `read_at ≤ NOW − 30일`은 0건. 두 배치의 NOW 대상은 0이다. 배치 실행으로 `processed` = 0을 보는 것은 구현 작업의 스모크에서 한다.

### 20.12 contract3 테스트 지침 (§18 보완)

- 이 절(+ §2~§19)만 보고 쓴다. 기존 규칙도 그대로다: 직렬 실행, 자기 행만 단언, 픽스처는 테스트가 만든다(`Db3`), 끝에 verify 1회(시드 빌드 전용 검사는 끈 채).
- **T1 회귀에 묶이지 않게:** A13(수정 기한·쓸 수 있는 항목·`reviewDeadline`)은 §18처럼 확정을 `delivered_at + 7일` 안의 구매자 확정으로 만들거나 `confirmed_at`을 픽스처로 직접 둔다. A14·A15·A16·A17의 기한은 `closed_at`·슬롯·`read_at` 기준이라 T1과 무관하다.
- **C2 회귀에 묶이지 않게(ADR-0006 §5):**
  - A14 재무장에서 발동 입찰을 `CANCELLED`·`EXPIRED`로 만들 때 판매 제한을 쓰지 않는다(C2 정답이 그 입찰을 취소한다 — 기대값이 두 코드에서 같아도 경로가 C2 고칠 곳에 묶인다). 입찰 취소(`DELETE /bids/{id}`)나 `bid-expiry`로 만든다. `MATCHED` 입찰은 대상이 아님을 따로 단언한다.
  - 구매 제한 건너뜀 테스트는 `purchase_restricted_until`을 픽스처로 직접 두고, 그 사용자에게 `ACTIVE` 입찰을 두지 않는다.
- **A17 재생성 없음 테스트:** 알림은 실제 배치(`deadline-reminder`)로 만든다. 읽음 → 시계 이동(≥ 30일) → `notification-purge` → `deadline-reminder` 순서로 돌려 같은 `dedupe_key`가 다시 생기지 않는지 본다.
  - 쓸 종류는 **`PAYMENT_DUE_SOON`·`SETTLEMENT_PAID`**로 정한다(둘 다 T1·C2 고칠 곳과 무관한 저장 열 — `payment_due_at`·정산 `paid_at`).
  - `DISPUTE_WINDOW_CLOSING`은 쓰지 않는다. `confirmed_at`에 기대므로 T1 회귀에 묶인다(§18 — 써야 하면 `confirmed_at`을 픽스처로 직접 둔다).
  - `PURCHASE_/SALE_RESTRICTED`도 쓰지 않는다. 제한을 배치로 걸면 C2 정답이 그 사용자의 ACTIVE 입찰을 취소하므로 C2 픽스처 규칙(ADR-0006 §5 — 제한 + ACTIVE 입찰 조합 피하기)에 걸린다. 써야 하면 제한 끝을 픽스처로 직접 두고(계약 안의 값: 건 시각 + 7일·30일) 그 사용자에게 ACTIVE 입찰을 두지 않는다.
  - §18 규칙을 다시 적는다: T1에 묶이지 않게 `confirmed_at`은 구매자 확정(`delivered_at + 7일` 안)이나 픽스처로 만들고, C2에 묶이지 않게 제한된 사용자 + ACTIVE 입찰 조합을 만들지 않는다.
- **동시성(§19의 10-2 테스트 꼴):** 아래 세 쌍이 교착 없이 각각 계약대로 응답하는지 본다.
  - A16 변경과 같은 슬롯의 예약(정원 1)
  - A14 재무장·재등록과 같은 사이즈의 입찰 등록
  - A13 수정과 같은 항목의 분쟁 제기
- **동시 수정(§2.3 개정 2):** 같은 리뷰(A13)·같은 가격 알림(A14 변경)에 값이 다른 두 수정을 동시에 보낸다. 단언은 셋이다: 둘 다 200, 최종 값 ∈ {두 요청의 값}, 행은 1개 그대로. 어느 값이 남는지는 잠금 획득 순서라 단언하지 않는다.
- **판정 기준 수:** 168 → 168 + (보충 테스트 수)로 바뀐다. 갱신은 구현 작업에서 ADR-0005 §4.1의 4와 함께 한다(이번에는 예고만 — ADR-0006 개정 2).
- **equiv**(ADR-0006 §6.1-6): 새 배치 2개와 잠금 경로(A14 재무장·A16 변경)의 시나리오를 구현 작업에서 더한다.

### 20.13 [제안] 값 목록 (합의 때 검토)

| 기능 | 값 | 근거 |
|---|---|---|
| A13 | 수정 본문 `rating`·`content` 둘 다 필수 | §19의 1(키 없음 = null)과 부분 갱신이 겹치지 않게 |
| A13 | 409 순서: 리뷰 상태 → 항목 상태 → 기한 | ADR-0004 §2.1(상태가 기한보다 먼저), 리뷰 행이 요청 대상이라 먼저 |
| A13 | 같은 값 수정도 200·`updated_at` 갱신 | 멱등 판정 코드를 두지 않는다(§2.3 단순 쓰기) |
| A13 | 내 리뷰 = 세 상태 전부 | 지운 리뷰가 있으면 다시 쓸 수 없다는 사실(§4.1)을 사용자가 볼 수 있게 |
| A14 | 만료 시각이 지났지만 아직 `ACTIVE`인 알림도 변경 가능 | 취소(§8.2)와 같이 저장 상태만 본다 |
| A14 | 재등록·재무장 창 = 종결 + 7일(정각 포함) | 사용자 결정(7일). 정각 포함은 리뷰 기간(§4.1)과 같은 관례 |
| A14 | 재등록 409에 "그 쌍의 `ACTIVE` 알림" 항 추가 | 부분 UNIQUE 위반(400 `UNEXPECTED_ERROR`)을 계약 안에서 막는 방어선 |
| A14 | 재등록은 위시리스트 불필요·구매 제한 무관 | 등록(§8.2)과 같다. 재무장만 위시리스트·제한을 본다(사용자 결정) |
| A14 | 재무장 발동 입찰 조건 = 저장된 `bids.status`가 `CANCELLED`·`EXPIRED`(`MATCHED` 제외) | 끝 상태만 — `MATCHED`는 결제 기한 만료로 `ACTIVE`가 되어(`Marketplace.kt:1482`) 같은 입찰로 다시 발동한다. 저장 상태만 읽는다(§2.3·§7.1과 같은 원칙) |
| A15 | 재개 창 = 종결 + 7일(정각 포함), 횟수 제한 없음 | 사용자 결정(7일), 자동 종결 기간과 같은 길이 |
| A15 | 관리자 문의함 항목 = 문의 모양 + `userId` | 운영자가 작성자를 알아야 한다. 기존 문의 모양은 바꾸지 않는다(§20.1) |
| A16 | 400 → 404(예약) → 404(슬롯) → 403 | 공통 판정 순서(404가 403보다 먼저) |
| A16 | 응답 `{"cancelled", "reserved"}` | 정정(§10.3)과 같은 꼴 — 두 행을 모두 돌려준다 |
| A16 | `GET /inbound-slots/{id}` 헤더 불필요 | 슬롯 목록(§12.3)과 같다 |
| A17 | 일괄 읽음 0건도 200, 응답 `{"readCount"}` | 묶음 요청에서 빈 집합은 충돌이 아니다 |
| A17 | 정리 = `read_at ≤ 현재 − 30일`(정각 포함), 안 읽은 알림은 지우지 않음 | 사용자 결정(읽은 지 30일). 경계는 다른 배치의 `≤ :due` 관례 |
| 공통 | 새 verify 검사 없음 · 시드 요청 이력 없음 | §20.9·§20.11 |

### 20.14 god 증가 추정

- 출처: 단계 A god 구현의 **실측** 단가(묶음별 실제 증가 줄 수, 후보 조사 cb-01 — 새 엔티티가 없는 만큼 뺌)에서 기능 단위로 외삽했다. 실측에서 나온 값이라 단계 A의 "추정 ≈ 3,390 대비 실측 +2,536" 비율로 다시 깎지 않는다(이중 할인).
- 기능별: A13 ≈ 130 · A14 ≈ 150 · A15 ≈ 100 · A16 ≈ 110 · A17 ≈ 70 = **≈ 560줄**(범위 +510~590) → god ≈ 5,740~5,820줄.
- **판정 규칙(ADR-0006 개정 2와 같다):** 사전 등록 기준은 **5,500줄**뿐이다. 보충 뒤 실측이 5,500 미만이면 같은 규칙으로 묶음을 더한다. 약 5,800은 여유를 둔 목표일 뿐 기준이 아니다 — 실측이 5,500~5,800이면 그대로 다음 단계(ADR-0006 §6.1-8)로 간다.
