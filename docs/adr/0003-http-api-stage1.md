# ADR-0003: HTTP API 계약 1단계 — 입찰 · 체결 · 결제

- 상태: **채택** (2026-10-15). [기본값] 항목은 그대로 채택했다. §9의 1·4는 ADR-0002 §9.6으로 옮겼다
- 앞 결정: [ADR-0001 기본 세팅](0001-base-setup.md) · [ADR-0002 도메인 절차·규칙과 DB 설계](0002-domain-and-database.md)
- 표기: **[기본값]** = 인터뷰에서 정하지 않아 제안한 값. 합의 때 검토한다.
- 정본 순서: 금액·상태 규칙은 ADR-0002, 그 실행 판정은 `tools/seedgen/verify.sql`이다. 이 문서는 HTTP 표면과, 두 구현이 같은 DB 결과를 내는 데 필요한 세부를 정한다. ADR-0002를 보완하는 곳은 §9에 모았다.

## 1. 결정 요약 (인터뷰)

| 항목 | 결정 |
|---|---|
| 범위 | 단계적. 1단계 = 입찰·체결·결제(포인트 사용·적립 예정 포함) + 배치(입찰 만료·결제 기한·PG 재조회) |
| 두 구현 | `god`(:8081)·`layered`(:8082)가 같은 계약을 따른다. 같은 요청 순서 → 같은 응답 → 같은 DB 결과(자동 증가 id·`pg_tx_id` 값 제외) |
| 사용자 식별 | `X-User-Id` 헤더(인증은 실험 범위 밖) |
| 시각 | 앱은 주입된 시계만 쓴다. 시계는 멈춰 있고 관리 엔드포인트로만 앞으로 옮긴다 |
| 카드 결제 | 가짜 PG 서버(HTTP). 테스트가 멱등 키별로 결과를 정한다. 앱은 `PG_URL`로 붙는다 |
| 판정 | HTTP 응답(상태 + `code`) + DB 상태 직접 조회 |

## 2. 공통 규칙

- JSON, 금액은 원 단위 정수, 시각은 ISO-8601 오프셋 포함(`2026-10-01T00:00:00+09:00`).
- 오류 응답 본문 `{"code": "<코드>", "message": "<설명>"}`. 테스트는 HTTP 상태와 `code`를 함께 단언한다(라우트가 없어서 나는 404와 구분하기 위해).
- 어떤 요청도 5xx로 끝나지 않는다(가짜 PG 장애 포함 — §5.1의 UNKNOWN으로 흡수).

| HTTP | code | 뜻 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 필수 값 누락·형식 오류·범위 밖·알 수 없는 enum 값·`Idempotency-Key` 누락 또는 1~64자 밖·`pointToUse` 음수, 알 수 없는 배치 이름 |
| 401 | `UNKNOWN_USER` | `X-User-Id` 없음·숫자 아님·없는 사용자(헤더가 필요한 엔드포인트만) |
| 403 | `NOT_OWNER` | 남의 입찰 조회·취소, 남의 주문 조회, 남이 구매자인 체결 결제 |
| 403 | `PURCHASE_RESTRICTED` / `SALE_RESTRICTED` | 제한 중 구매 입찰·즉시 구매 / 판매 입찰·즉시 판매 |
| 404 | `NOT_FOUND` | 없는 입찰·체결·주문·사이즈 |
| 409 | `BID_NOT_ACTIVE` | ACTIVE가 아닌 입찰 취소 |
| 409 | `NO_COUNTER_BID` | 즉시 구매·판매인데 체결 가능한 상대 입찰이 없음 |
| 409 | `TRADE_NOT_PAYABLE` | 결제 불가 체결(§5.1) |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 같은 멱등 키에 다른 구매자 또는 다른 요청 |
| 409 | `CLOCK_BACKWARD` | 시계를 뒤로 옮김 |
| 422 | `POINT_RULE_VIOLATION` | 포인트 사용 규칙 위반(§5.2) |

- 판정 순서: 401 → 400 → (멱등 키 조회, §5.1) → 404 → 403 → 409 → 422. 여러 체결 중 하나라도 없으면 404, 모두 있고 하나라도 남의 것이면 403. **[기본값]**

## 3. 입찰

### 3.1 등록 `POST /bids`

```json
요청 {"productSizeId": 123, "side": "BUY", "price": 250000, "validDays": 30}
     {"productSizeId": 123, "side": "SELL", "instant": true}
응답 201 {"bidId": 9001, "status": "ACTIVE", "tradeId": null, "tradePrice": null}
     201 {"bidId": 9002, "status": "MATCHED", "tradeId": 777, "tradePrice": 248000}
```

- 일반 입찰: `price` 정수 > 0, `validDays ∈ {30, 60, 90}`. `created_at` = 현재 시각, `expires_at` = 현재 시각 + validDays일.
- 즉시(`instant: true`): `price`·`validDays`가 있으면 400. 체결 가능한 반대편 최우선 입찰(§3.3)의 가격으로 입찰을 만들고 바로 체결한다. `expires_at` = 현재 + 30일. 상대가 없으면 409이고 아무 행도 만들지 않는다.
- 행: `bids` 1행 + `bid_events`(`NULL → ACTIVE`, `trade_id` NULL) 1행. 교차하면 같은 트랜잭션에서 체결(§3.3).
- 제한: 구매는 `purchase_restricted_until > 현재`이면 403, 판매는 `sale_restricted_until`. 제한은 새 입찰에만 적용한다. 이미 걸린 입찰의 체결과 결제는 막지 않는다. **[기본값]**

### 3.2 취소 `DELETE /bids/{bidId}`

- 등록자만, ACTIVE만. `200 {"bidId", "status": "CANCELLED"}`. `bid_events`(`ACTIVE → CANCELLED`, `trade_id` NULL).

### 3.3 체결 규칙

- **체결 가능한 입찰** = `status = ACTIVE` 이고 `expires_at > 현재`. 만료 시각이 지났지만 아직 배치가 돌지 않은 입찰은 매칭·즉시 가격·호가 조회에서 모두 빠진다.
- 교차: 새 구매가 ≥ 판매 최우선 가격, 새 판매가 ≤ 구매 최우선 가격.
- 최우선 순서: 가격(구매는 높은 순, 판매는 낮은 순) → `created_at` → `id`. 상대 사용자가 자신이면 건너뛰고 다음 순위를 본다.
- **체결가 = `(created_at, id)`가 앞선 입찰의 가격.** 복구된 판매 입찰(§7)은 원래 `created_at`을 유지하므로, 복구 뒤 다시 체결될 때는 그 입찰이 상대보다 앞서면 자기 가격으로 체결된다.
- 체결 1건: `trades`(`AWAITING_PAYMENT`, `matched_at` = 현재, `payment_due_at` = 현재 + 30분) + 두 입찰 `MATCHED` + `bid_events` 2행(`ACTIVE → MATCHED`, `trade_id` = 새 체결; 구매 입찰 행 먼저).
- 입찰 1개에 살아 있는(`PAYMENT_EXPIRED`가 아닌) 체결은 최대 1건이다. 같은 상대를 노리는 요청이 동시에 와도 그 상대와의 체결은 1건이다. 진 요청도 201을 받고(다음 순위와 체결했거나 ACTIVE로 남음), 즉시 주문이면서 남은 상대가 없으면 409 `NO_COUNTER_BID`다. 테스트는 동시성에서 "살아 있는 체결 수와 불변식"만 단언한다.

### 3.4 조회

- `GET /bids/{bidId}`(등록자만) → `{"bidId", "userId", "productSizeId", "side", "price", "status", "createdAt", "expiresAt", "tradeId"}`. `tradeId` = 그 입찰의 살아 있는 체결 id, 없으면 null.
- `GET /product-sizes/{id}/book`(헤더 불필요) → `{"bestBuy": 정수|null, "bestSell": 정수|null}`. 체결 가능한 입찰 기준.

## 4. 체결 조회

- `GET /trades?role=buyer|seller[&status=...]` → `[{"tradeId", "productSizeId", "price", "status", "matchedAt", "paymentDueAt"}]`, `tradeId` 오름차순. `role` 필수.

## 5. 결제

### 5.1 주문 결제 `POST /orders`

```json
헤더 Idempotency-Key: 6f1c…   (필수, 1~64자)
요청 {"tradeIds": [778, 777], "pointToUse": 5000}
응답 201 {"orderId", "status", "itemTotal", "buyerFeeTotal", "shippingFee", "pointUsed", "cardAmount",
          "items": [{"orderItemId", "tradeId", "status", "price", "buyerFee", "cardShare", "pointShare", "shippingShare"}]}
```

- **멱등:** 같은 키의 주문이 있으면, 같은 구매자이고 `tradeIds` 집합과 `pointToUse`(생략 = 0)가 같을 때 `200`으로 **그 주문의 현재 상태**를 돌려주고 아무것도 바꾸지 않는다(PG 호출 없음). 아니면 409 `IDEMPOTENCY_KEY_REUSED`. 같은 키로 동시에 와도 주문은 1건이고 진 요청은 이긴 쪽의 결과를 200으로 받는다.
- **결제 가능 체결** = 구매자가 나, `AWAITING_PAYMENT`, `현재 < payment_due_at`, 그 체결에 `PAYMENT_FAILED`가 아닌 주문 항목이 없음. `tradeIds`는 1개 이상, 중복이면 400.
- **항목 순서:** 주문 항목은 `tradeId` 오름차순으로 만든다(= `order_items.id` 순서 = "첫 항목"). 응답 `items`도 같은 순서. 금액은 ADR-0002 §5.1·§9.2(§8 예시).
- 처리: 주문·항목 행 생성(`PAYING`, 항목 `AWAITING_PAYMENT`, `idempotency_key`, `created_at` = 현재) → 포인트 예약(§5.2, `pointToUse > 0`일 때만) → PG 승인 요청 → 결과 반영. 주문 행은 PG 호출 전에 커밋한다(타임아웃 뒤에도 남아야 하므로). `PENDING` 상태는 쓰지 않는다. **[기본값]**

| PG 결과 | 주문 | 항목 | 체결 | 포인트 | HTTP |
|---|---|---|---|---|---|
| 승인 | `PAID`, `paid_at` = 현재 | `PAID`, `paid_at` 같음 | `PAID` | USE `CONFIRMED`, 항목별 EARN `PENDING`(적립 > 0만), 잔액 갱신 | 201 |
| 거절 | `FAILED` | `PAYMENT_FAILED` | 그대로 | USE `RELEASED`, 배분 삭제, 묶음·잔액 되돌림 | 201 |
| 응답 없음(`PG_TIMEOUT_MS` 초과·연결 실패·5xx) | `UNKNOWN` | 그대로 | 그대로 | USE `RESERVED` 유지 | 201 |

- **`pg_transactions` 행(덧붙이기 전용, 결과가 정해진 뒤 1행 INSERT):** `kind = APPROVE`, `amount = card_amount`, `idempotency_key` = 주문 키, `requested_at` = 요청 시각, 승인이면 `status = APPROVED`·`pg_tx_id` = PG 값·`responded_at` = 현재, 거절이면 `DECLINED`·`pg_tx_id` NULL·`responded_at` = 현재, 응답 없음이면 `UNKNOWN`·`pg_tx_id` NULL·`responded_at` NULL. `REQUESTED`는 쓰지 않는다.
- 적립률은 결제 요청 시각의 `users.grade`.
- 거절된 체결은 기한 안이면 새 키로 다시 결제할 수 있다. UNKNOWN 주문의 체결은 재조회로 확정될 때까지 결제할 수 없다(409).

### 5.2 포인트

- **사용 가능액** = 그 사용자의 `kind = EARN, status = AVAILABLE, expires_at > 현재` 묶음의 `remaining` 합. 예약이 이미 `remaining`에서 빠져 있으므로 별도 차감은 없다.
- 규칙(위반 422): `pointToUse = 0`이거나, `1,000 ≤ pointToUse`, 10의 배수, `≤ floor(주문 총액 × 50%)`, `≤ 사용 가능액`.
- **예약:** USE 1행(`RESERVED`, `amount = pointToUse`, `order_id` = 주문, `order_item_id`·`expires_at`·`remaining` NULL, `created_at` = 현재)을 만들고, 사용 가능 묶음을 `(expires_at, id)` 순으로 차감해 `point_allocations`를 쓰고 `remaining`과 `point_balances.available`을 줄인다.
- **확정:** USE → `CONFIRMED`(배분 그대로). **해제:** USE → `RELEASED`, 그 USE의 배분 행을 지우고 같은 묶음의 `remaining`·`available`을 되돌린다.
- **적립 예정:** 항목마다 EARN 1행(`PENDING`, `amount = remaining` = 적립액, `expires_at` NULL, `order_item_id` = 항목, `order_id` NULL, `created_at` = 결제 시각), `point_balances.pending` 증가.
- `point_balances`: 행이 없는 사용자는 처음 바뀔 때 만든다. `updated_at` = 바꾼 시각.
- `GET /me/points` → `{"available", "pending", "usable"}`. `available`·`pending` = `point_balances` 캐시(행 없으면 0) — 만료 시각이 지났지만 소멸 배치(2단계)가 아직 돌지 않은 묶음도 포함한다(verify.sql `point_balance_cache`와 같은 정의). `usable` = 위 사용 가능액(만료 묶음 제외) — 포인트 규칙 판정에 쓰는 값.

### 5.3 조회 `GET /orders/{orderId}`

- 구매자만. §5.1 응답과 같은 모양, `200`.

## 6. 가짜 PG

- 별도 프로세스(테스트 모듈이 띄움). 앱 설정: `PG_URL`, `PG_TIMEOUT_MS`(기본 2000).
- 앱 → PG:
  - `POST {PG_URL}/approvals` `{"idempotencyKey", "amount"}` → `200 {"pgTxId", "status": "APPROVED"|"DECLINED"}`
  - `GET {PG_URL}/approvals/{idempotencyKey}` → `200 {"pgTxId", "status"}` 또는 `404`(승인 요청이 기록되지 않음)
- 테스트 → PG: `POST {PG_URL}/_control/outcomes` `{"idempotencyKey", "outcome": "APPROVE"|"DECLINE"|"TIMEOUT_APPROVED"|"TIMEOUT_NOT_RECEIVED"}`. 지정이 없는 키는 APPROVE.
  - `TIMEOUT_APPROVED`: 승인을 기록하고 `PG_TIMEOUT_MS`보다 늦게 응답(재조회 = APPROVED).
  - `TIMEOUT_NOT_RECEIVED`: 기록하지 않고 늦게 응답(재조회 = 404).
  - 같은 키로 두 번째 승인 요청이 오면 첫 기록을 그대로 돌려준다.

## 7. 관리 엔드포인트 (시각·배치, 헤더 불필요)

- 시계: 멈춘 시계. 앱 시작 시 `CLOCK_START` 환경변수, 없으면 `2026-10-01T00:00:00+09:00`(시드의 현재 시각). `PUT /admin/clock {"now"}`는 같거나 뒤 시각만 허용(앞이면 409 `CLOCK_BACKWARD`). `GET /admin/clock → {"now"}`. 요청 처리 중 시각은 바뀌지 않는다(모든 `created_at` 등이 같은 값).
- 배치 `POST /admin/batches/{name}` → `200 {"processed": n}`. 현재 시각 기준 1회. `n` = 상태가 바뀐 주 대상 수(아래 열). 다른 행(시드·다른 테스트)도 처리하므로 테스트는 `n`의 정확한 값이 아니라 자기 행의 결과를 단언한다.

| name | 대상(순서) | 하는 일 |
|---|---|---|
| `bid-expiry` | `status = ACTIVE AND expires_at ≤ 현재` 입찰, id 순 | → `EXPIRED`, `bid_events`(`ACTIVE → EXPIRED`, `trade_id` NULL), 이벤트 시각 = 현재 |
| `payment-due` | `AWAITING_PAYMENT AND payment_due_at ≤ 현재` 체결 중 **UNKNOWN 주문이 없는 것**, id 순 | 체결 → `PAYMENT_EXPIRED`. `buyer_warnings` 1행. 구매 입찰 `MATCHED → CANCELLED`. 판매 입찰은 `expires_at ≤ 현재`면 `MATCHED → EXPIRED`, 아니면 `MATCHED → ACTIVE` 후 §3.3으로 즉시 재매칭(새 입찰처럼 반대편을 찾는다). 이 이벤트들의 `trade_id` = 만료된 체결 |
| `pg-inquiry` | `UNKNOWN` 주문, id 순 | PG 재조회 → `pg_transactions` INQUIRY 1행(`amount = card_amount`, 같은 키, `requested_at`·`responded_at` = 현재, 404는 `DECLINED`, 응답 없음이면 행을 만들지 않고 주문은 UNKNOWN 유지). APPROVED면 §5.1 승인 처리, DECLINED면 거절 처리 |

- **구매 경고:** 경고 1행(`created_at` = 현재)을 쓴 뒤, 그 구매자의 경고 중 `created_at > 현재 − 30일` 이고 `created_at > coalesce(purchase_restricted_until − 7일, -∞)`(마지막 제한이 걸린 시각 뒤)인 것이 3건 이상이면 `purchase_restricted_until = 현재 + 7일`. 제한을 건 경고는 다음 계산에서 빠지므로 횟수는 다시 센다. **[기본값 — 생성기와 같은 해석]**
- **재조회 승인 시각:** 재조회로 승인되면 `paid_at` = 그 주문 APPROVE 행의 `requested_at`(PG가 실제로 승인한 때). 결제 기한 안에 요청된 결제이므로 기한 판정(`paid_at ≤ payment_due_at`)이 유지된다. EARN의 `created_at`도 같은 값. **[기본값]**
- 기한이 지난 체결의 UNKNOWN 주문이 재조회로 거절되면, 체결은 다음 `payment-due`에서 만료된다.

## 8. 금액 예시 (테스트 기대값의 정본)

등급 BRONZE(적립 1%) 기준.

| 예 | 체결가(항목 순) | 수수료 | 배송비 | 총액 | 포인트 | 카드 | 카드 몫 | 포인트 몫 | 적립 |
|---|---|---|---|---|---|---|---|---|---|
| A | 120,000 · 45,000 | 3,600 · 1,350 | 0 | 169,950 | 5,000 | 164,950 | 119,964 · 44,986 | 3,636 · 1,364 | 1,164 · 436 |
| B | 30,000 | 1,000(최소) | 3,000 | 34,000 | 1,000 | 33,000 | 33,000 | 1,000 | 291 |
| C | 99,000 | 2,970 | 3,000 | 104,970 | 0 | 104,970 | 104,970 | 0 | 990 |
| D | 100,000 | 3,000 | 0 | 103,000 | 0 | 103,000 | 103,000 | 0 | 1,000 |

- A: 카드 몫 floor 값은 119,963 · 44,986(합 164,949), 남은 1원을 앞 항목부터 기준액 한도 안에서 더한다 → 119,964 · 44,986. 적립 = floor(floor(카드 몫 × 체결가 ÷ 기준액) × 1%).
- B: 배송비의 카드 부분 = floor(3,000 × 33,000 ÷ 34,000) = 2,911, 포인트 부분 89.
- 포인트 상한 예: 총액 34,000이면 최대 17,000P.

## 9. ADR-0002 보완 (이 계약이 정한 해석)

1·4는 ADR-0002의 문장을 바꾸므로, 이 ADR을 채택하면 ADR-0002 §9에 개정 항목으로 옮긴다. 2·3·5는 ADR-0002가 정하지 않은 것을 정한다.

1. **[ADR-0002 개정 — 합의 필요]** 포인트 예약 시점에 배분을 쓰고 `remaining`·잔액을 줄인다. 해제 시 배분 행을 지운다. §9.5의 "예약은 승인될 때 배분으로 기록"을 대체한다. 이유: 예약 중(UNKNOWN 포함) 같은 포인트를 다른 주문이 쓰지 못하게 하려면 잔액에서 빠져 있어야 한다. 생성기는 예약을 메모리에서만 차감하고 배분 행은 승인 때 쓰므로 중간 상태가 다르다(최종 스냅샷은 같다). `verify.sql`은 RESERVED USE에 배분이 있는 상태를 허용한다(`ledger_allocation_sum`·`earn_remaining_formula`). 테스트는 UNKNOWN 주문의 배분·`remaining`·잔액을 직접 조회해 판정한다.
2. 결제 기한 만료 때 판매 입찰도 만료됐으면 `MATCHED → EXPIRED`(§4.1 표에 없는 전이 — 생성기·verify와 같다).
3. `pg_transactions`는 결과가 정해진 뒤 1행 INSERT(`REQUESTED` 미사용). 재조회 404는 `DECLINED`.
4. **[ADR-0002 개정 — 합의 필요]** 멱등 재요청은 "첫 결과"가 아니라 그 주문의 현재 상태를 돌려준다. 이유: 첫 응답을 그대로 재현하려면 응답을 저장할 열이 필요한데 스키마에 없다. 새 승인을 만들지 않는다는 §4.3의 뜻은 유지한다. 예: 첫 응답 UNKNOWN → 재조회로 PAID → 같은 키 재요청은 `200`, `status: PAID`.
5. 주문 상태 `PENDING`은 쓰지 않는다.

## 10. 1단계 밖 (다음 단계)

- 구매자 취소·판매자 귀책·검수·배송·확정·분쟁·포인트 확정(AVAILABLE)·소멸·회수·등급 재산정·정산.

## 11. 결과

- 행위 테스트(별도 Gradle 모듈)는 이 문서만 보고 쓰고, `BASE_URL`만 바꿔 두 앱에 같은 테스트를 돌린다. 테스트는 직렬로 실행하고, 자기가 만든 사용자·상품·사이즈만 단언한다.
- 1단계가 만든 DB는 `verify.sql`을 통과해야 한다(테스트 끝에 1회 실행).
