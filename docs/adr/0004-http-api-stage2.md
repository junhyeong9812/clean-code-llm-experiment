# ADR-0004: HTTP API 계약 2단계 — 취소·검수·배송·확정·분쟁·포인트·등급·정산

- 상태: **채택** (2026-10-21). [기본값] 항목은 그대로 채택했다. §11의 1은 ADR-0003 §9의 8로, 2~9는 ADR-0002 §9.7로 옮겼다
- 앞 결정: [ADR-0002 도메인 절차·규칙과 DB 설계](0002-domain-and-database.md) · [ADR-0003 HTTP API 계약 1단계](0003-http-api-stage1.md)
- 표기: **[기본값]** = 인터뷰에서 정하지 않아 제안한 값. 합의 때 검토한다. **[verify]** = `tools/seedgen/verify.sql`이 이미 고정한 값이라 계약이 따른다.
- 정본 순서: 금액·상태 규칙은 ADR-0002, 실행 판정은 `verify.sql`, HTTP 표면과 두 구현이 같은 DB 결과를 내는 세부는 이 문서. ADR-0002·0003을 바꾸는 곳은 §11에 모았다.

## 1. 결정 요약 (인터뷰 2026-10-21)

| 항목 | 결정 |
|---|---|
| 범위 | ADR-0003 §10 전부: 구매자 취소(부분 취소)·발송·입고·검수·배송·구매 확정(자동 포함)·판매자 귀책(검수 불합격·발송 기한 초과)·분쟁·포인트 확정·소멸·회수·복구·등급 재산정·주간 정산·패널티·판매 제한 |
| 행위자 | 구매자(확정·취소·분쟁 제기)와 판매자(발송)는 `X-User-Id` API. 센터·운영자(입고·검수·배송 완료·분쟁 판정)는 `/admin` |
| 공통 | ADR-0003 §2(오류 본문·판정 순서·5xx 금지)·§7(멈춘 시계·배치 형식)을 그대로 따른다 |
| 동시성·장애 | §9에 처음부터 정한다 |
| 적립률 | 결제 요청 시각의 등급을 `user_grade_history`로 읽는다(§8.1, ADR-0003 §10 전제) |

- 시각 계산은 모두 KST(`Asia/Seoul`)다 — 정산 주·월 경계, 날짜(`date`) 열. (verify가 직접 검사하는 것은 정산 마감·지급 시각뿐이고, 등급 달력월 경계는 테스트가 판정한다.)

## 2. 공통

### 2.1 새 오류 코드

| HTTP | code | 뜻 |
|---|---|---|
| 409 | `ITEM_STATE_CONFLICT` | 그 동작을 할 수 없는 주문 항목 상태(예: 발송된 항목 취소, DELIVERED가 아닌 항목 확정) |
| 409 | `DEADLINE_PASSED` | 기한이 지남(발송 기한 뒤 발송 등록, 분쟁 기간 뒤 분쟁 제기) |
| 409 | `DISPUTE_NOT_OPEN` | OPEN이 아닌 분쟁 판정 |
| 409 | `PG_UNAVAILABLE` | 카드 취소를 PG가 처리하지 못함(§7.2). 아무것도 바뀌지 않았고 같은 요청을 다시 보낼 수 있다 |

- 403 `NOT_OWNER`: 구매자 API는 그 주문의 구매자, 발송은 그 체결의 판매자만. 404 `NOT_FOUND`: 없는 주문 항목·분쟁. 판정 순서는 ADR-0003 §2(401 → 400 → 404 → 403 → 409).
- `/admin` 엔드포인트는 헤더가 필요 없다(ADR-0003 §7과 같다).
- 상태 위반과 기한 위반이 함께면 상태 위반(409 `ITEM_STATE_CONFLICT`)이 먼저다(예: 이미 발송된 항목을 기한 뒤 다시 발송).
- 날짜(`date`) 열은 `"YYYY-MM-DD"` 문자열로 낸다(`periodStart`·`periodEnd`). 시각은 ADR-0003 §2대로 오프셋 포함 ISO-8601.
- 문자열 입력(검수 reason 등)은 받은 값 그대로 저장한다(빈 값 판정에만 앞뒤 공백을 뺀다).

### 2.2 주문 항목 응답

```json
{"orderItemId", "orderId", "tradeId", "status", "price", "buyerFee", "cardShare", "pointShare", "shippingShare",
 "paidAt", "shippedAt", "inspectedAt", "deliveredAt", "confirmedAt", "cancelledAt",
 "refund": null | {"cardAmount", "pointAmount", "buyerFeeAmount", "shippingAmount", "fault"}}
```

- `GET /order-items/{id}`: 구매자 또는 판매자. 상태를 바꾸는 엔드포인트도 성공 시 이 모양으로 `200`.
- `GET /orders/{orderId}`(ADR-0003 §5.3)의 `items[].status`·주문 `status`는 2단계 상태를 그대로 보인다.

### 2.3 한 항목을 바꾸는 처리의 공통 순서

1. 그 항목의 주문 행을 잠근다(`SELECT … FROM orders WHERE id = ? FOR UPDATE`) — 같은 주문의 항목 변경은 직렬이다(§9).
2. 상태·기한을 확인한다(위반이면 409, 아무것도 바꾸지 않음).
3. 환불이 있으면 카드 취소를 PG에 요청한다(§7.2). 실패면 409 `PG_UNAVAILABLE`, 아무것도 바꾸지 않음.
4. 항목·환불·포인트·패널티·주문 상태를 **한 트랜잭션**으로 반영하고 커밋한다.

## 3. 판매자·센터 절차

| 엔드포인트 | 누가 | 허용 상태 | 결과 |
|---|---|---|---|
| `POST /order-items/{id}/shipment` | 판매자 | `PAID` 이고 `현재 < paid_at + 3일` | `SHIPPED_TO_CENTER`, `shipped_at` = 현재. 기한 뒤면 409 `DEADLINE_PASSED` |
| `POST /admin/order-items/{id}/receipt` | 센터 | `SHIPPED_TO_CENTER` | `INSPECTING`(시각 열 없음 — `inspected_at`은 NULL **[verify]**) |
| `POST /admin/order-items/{id}/inspection` `{"result": "PASS"\|"FAIL", "reason": 문자열\|null}` | 센터 | `INSPECTING` | PASS: `inspections`(PASS, reason NULL — 요청의 reason은 무시, `inspected_at` = 현재) + 항목 `DELIVERING`·`inspected_at` 같은 값. FAIL: §6 판매자 귀책(`inspections` FAIL·reason, `inspected_at` = `cancelled_at` = 현재). FAIL인데 reason이 null이거나 앞뒤 공백을 뺀 값이 비면 400 |
| `POST /admin/order-items/{id}/delivery` | 센터 | `DELIVERING` | `DELIVERED`, `delivered_at` = 현재 |

- 배송 시작은 따로 없다 — 검수 합격이 곧 `DELIVERING`(ADR-0002 §4.4).
- `SHIPPED_TO_CENTER`·`INSPECTING` 항목은 구매자 취소도 기한 초과 취소도 되지 않는다. 발송된 항목의 귀책 취소는 검수 불합격으로만 생긴다 **[verify — 발송 항목의 귀책 취소는 검수 행이 있어야 한다]**.

## 4. 구매자 절차

### 4.1 구매 확정 `POST /order-items/{id}/confirm`

- 구매자, `DELIVERED`만. 항목 `CONFIRMED`, `confirmed_at = min(현재, delivered_at + 7일)` → §8.2 포인트 확정. 7일이 지난 뒤의 확정은 자동 확정과 같은 결과다(`confirmed_at ≤ delivered_at + 7일` **[verify]**).
- 소급된 `confirmed_at`이 분쟁 기간(§4.3)의 기준이므로, 늦게 확정된 항목은 분쟁 기간이 짧거나 이미 지났을 수 있다.

### 4.2 구매자 취소 `POST /order-items/{id}/cancel`

- 구매자, `PAID`만(발송 전, ADR-0002 §4.4). 항목 `CANCELLED_BY_BUYER`, `cancelled_at` = 현재 → §7 환불(`fault = BUYER`).
- 기한(`paid_at + 3일`)이 지나도 배치가 돌기 전이면 취소할 수 있다(PAID이므로). **[기본값]**

### 4.3 분쟁 제기 `POST /order-items/{id}/disputes`

- 구매자, `CONFIRMED`이고 `현재 ≤ confirmed_at + 14일` **[verify — 14일 정각 포함]**. 아니면 상태 위반 409 `ITEM_STATE_CONFLICT`, 기간 뒤 409 `DEADLINE_PASSED`.
- `disputes` 1행(`OPEN`, `opened_at` = 현재, `resolved_at` NULL) + 항목 `DISPUTED`. 응답 `201 {"disputeId", "orderItemId", "status": "OPEN", "openedAt"}`.
- 기각된 항목은 기간 안이면 다시 제기할 수 있다(OPEN·ACCEPTED는 항목당 최대 1건). **[기본값]**

### 4.4 분쟁 판정 `POST /admin/disputes/{id}/resolution` `{"accept": true|false}`

- `OPEN`만(아니면 409 `DISPUTE_NOT_OPEN`).
- 기각: 분쟁 `REJECTED`, `resolved_at` = 현재, 항목 `CONFIRMED`(`confirmed_at`은 그대로).
- 인정: 분쟁 `ACCEPTED`, `resolved_at` = 현재, 항목 `REFUNDED_AFTER_CONFIRM`, `cancelled_at` = `resolved_at` **[verify]** → §7 환불(`fault = DISPUTE`, 전액) + §8.4 회수. 패널티 없음 **[verify]**. 이미 정산에 포함된 항목은 다음 정산에서 CLAWBACK(§10).
- 응답 `200 {"disputeId", "orderItemId", "status", "openedAt", "resolvedAt"}`.

## 5. 배치 (ADR-0003 §7 형식에 추가)

| name | 대상(순서) | 하는 일 |
|---|---|---|
| `shipment-deadline` | `PAID` 항목 중 `paid_at + 3일 ≤ 현재`, id 순 | §6 판매자 귀책(`cancelled_at` = 현재, 검수 행 없음) |
| `auto-confirm` | `DELIVERED` 항목 중 `delivered_at + 7일 ≤ 현재`, id 순 | `CONFIRMED`, **`confirmed_at` = `delivered_at + 7일`**(소급) → §8.2 |
| `point-expiry` | `EARN AVAILABLE`, `remaining > 0`, `expires_at ≤ 현재`, id 순 | §8.3 소멸 |
| `grade-recalc` | 모든 사용자 | §8.5 |
| `weekly-settlement` | 마감 안 된 주(§10.1) | **먼저 `auto-confirm`을 수행**한 뒤 §10 정산 |

- `processed` = 상태가 바뀐 주 대상 수(`weekly-settlement`는 만든 정산 행 수, `grade-recalc`는 쓴 이력 행 수). 테스트는 정확한 값을 단언하지 않는다(ADR-0003 §7).
- 대상 1건의 처리는 트랜잭션 하나다. 중간에 실패하면 그 대상은 바뀌지 않고, 다시 돌리면 남은 대상을 처리한다(§9.2).
- `auto-confirm`의 소급 이유: `confirmed_at ≤ delivered_at + 7일`이 **[verify]**다. 정산이 그 전에 `auto-confirm`을 먼저 돌리므로, 소급된 확정 시각이 이미 마감된 주에 들어가는 일은 없다(§10.1).

## 6. 판매자 귀책 (검수 불합격·발송 기한 초과)

- 항목 `CANCELLED_SELLER_FAULT`, `cancelled_at` = 현재 → §7 환불(`fault = SELLER`, 전액).
- 패널티: `seller_penalties` 1행(`seller_id` = 체결 판매자, `order_item_id`, `amount = floor(체결가 × 10%)` **[verify]**, `settlement_id` NULL, `created_at` = 현재).
- 판매자 귀책 처리는 판매자 사용자 행을 잠근다(§9.1 — 동시 귀책의 횟수 누락 방지).
- **계약 밖(사용자 결정 2026-10-22):** 체결가 < 10원인 항목의 판매자 귀책. 패널티가 0원이 되어 `seller_penalties.amount > 0`·verify `penalty_per_seller_fault`와 맞지 않는다(시드·테스트·verify는 체결가 ≥ 10을 전제한다). 이 경우 처리는 정하지 않는다 — 구현에 따라 카드 취소 뒤 반영이 실패할 수 있다.
- 판매 제한: 패널티를 쓴 뒤, 그 판매자의 패널티 중 `created_at > 현재 − 30일`이고 `created_at > coalesce(sale_restricted_until − 30일, -∞)`인 것이 3건 이상이면 `users.sale_restricted_until = 현재 + 30일`. 구매 경고(ADR-0003 §7)와 같은 해석이다. **[기본값 — 생성기와 같은 해석]**

## 7. 환불

### 7.1 금액 (ADR-0002 §5.3·§9.2·§9.5)

- 기호: 항목 i의 카드 몫 `c_i`, 포인트 몫 `p_i`, 첫 항목의 배송비 `s`(나머지 항목 0), 배송비의 카드 부분 `s_c = floor(s × c_1 / b_1)`, 포인트 부분 `s_p = s − s_c`.
- **구매자 취소:** 환불 = 그 항목의 몫에서 배송비 부분을 뺀 것. 카드 `c_i − (첫 항목이면 s_c)`, 포인트 `p_i − (첫 항목이면 s_p)`.
- **판매자 귀책·분쟁 인정:** 그 항목의 몫 전부(첫 항목이면 배송비 부분 포함). 카드 `c_i`, 포인트 `p_i`.
- **마지막 환불:** 이 환불로 주문의 모든 항목이 환불되면, 아직 환불되지 않은 배송비(구매자 취소로 첫 항목이 먼저 빠진 경우의 `s_c`·`s_p`)를 이 환불에 더한다. 결과: 모든 항목이 환불된 주문은 Σ카드 환불 = `card_amount`, Σ포인트 환불 = `point_used`. **[verify]**
- `refunds` 1행: `card_amount`, `point_amount`, `buyer_fee_amount` = 그 항목의 구매 수수료(모든 사유), `shipping_amount` = 이 환불에 실린 배송비 전체(`s` 또는 0), `reason`(구매자 취소 `구매자 취소`, 기한 초과 `발송 기한 초과`, 불합격 = 검수 reason, 분쟁 `분쟁 인정`), `fault`, `created_at` = 현재.
- 주문 상태: 환불된 항목(취소 2종 + `REFUNDED_AFTER_CONFIRM`)이 일부면 `PARTIALLY_CANCELLED`, 전부면 `CANCELLED` **[verify]**.
- 취소된 항목의 체결(`PAID`)과 입찰(`MATCHED`)은 그대로 둔다. 판매 입찰 복구는 없다(결제 기한 만료와 다름).

### 7.2 카드 취소 (가짜 PG)

- 카드 환불 > 0일 때만. 앱 → PG `POST {PG_URL}/cancellations` `{"idempotencyKey": "cancel-<orderItemId>", "approvalKey": <주문 멱등 키>, "amount": <카드 환불>}` → `200 {"pgTxId", "status": "APPROVED"}`. 같은 키의 두 번째 요청은 첫 기록을 그대로 돌려준다.
- 성공이면 `pg_transactions` 1행: `kind = CANCEL`, `amount` = 카드 환불, `status = APPROVED`, `pg_tx_id` = PG 값, `idempotency_key = cancel-<orderItemId>`, `requested_at` = `responded_at` = 현재 **[verify — Σ카드 환불 = Σ승인 CANCEL]**.
- PG가 응답하지 않거나(`PG_TIMEOUT_MS`·연결 실패·5xx), APPROVED가 아니거나, **응답의 `amount`가 요청한 금액과 다르면**: 요청이면 409 `PG_UNAVAILABLE`, 배치면 그 대상을 건너뛴다. 어느 쪽이든 DB는 바뀌지 않는다. 키는 항목당 하나(사유와 무관)라, 다시 보내거나 다른 사유로 보내도 PG에 취소가 두 번 생기지 않는다. 가짜 PG 응답: `200 {"pgTxId", "status": "APPROVED", "amount"}`(같은 키면 첫 기록의 금액). **[기본값]**
- **계약 밖(사용자 결정 2026-10-21):** PG가 취소를 기록한 뒤 앱이 DB에 반영하기 전에 프로세스가 죽는 경우. 같은 요청을 다시 보내면 반영되지만, 그 사이 항목이 다른 상태로 바뀌면 카드 취소만 남는다(스키마에 환불 진행 상태가 없다). 같은 종류로, PG가 취소를 기록한 뒤 응답이 없는(409) 사이 같은 주문의 다른 항목이 환불되어 "마지막 환불"(§7.1) 여부가 바뀌면 재요청 금액이 첫 기록과 달라져 409가 반복되고 카드 취소만 남는다(사용자 결정 2026-10-22 — 계약 밖). 위 금액 확인은 이 상태에서 다른 금액이 기록되는 것만 막는다.
- 테스트 → PG: `POST /_control/outcomes` `{"idempotencyKey": "cancel-<id>", "outcome": "UNAVAILABLE"|"DECLINE"}` = 그 키의 취소 요청에 503 / `200 {"status": "DECLINED"}`(둘 다 기록하지 않음). 지정이 없으면 승인.
- 테스트 → PG 조회(보조): `GET /_control/cancellations/{key}` → `{"requests", "amounts", "approvalKeys", "record": {"pgTxId", "status", "amount", "approvalKey"} | null}` — 앱이 보낸 취소 요청 수·금액과 기록.

### 7.3 포인트

- **적립 예정 취소:** 그 항목의 EARN이 `PENDING`이면 `CANCELLED`, `point_balances.pending` 감소. `remaining`은 그대로 둔다(= `amount`, 배분 없음 — verify의 "PENDING·CANCELLED EARN은 손대지 않음"). **[기본값]**
- **복구(RESTORE):** 포인트 환불 > 0이면 RESTORE 1행(`status = DONE`, `amount` = 포인트 환불, `order_id`, `order_item_id`, `created_at` = 현재)과 배분을 쓴다. 되돌릴 묶음 = 그 주문의 CONFIRMED USE가 빠져나간 묶음, **만료가 늦은 묶음부터(같으면 id 큰 것부터)**, 묶음마다 "그 USE가 가져간 양 − 이미 되돌린 양"까지. 묶음 `remaining`·`point_balances.available` 증가. **[기본값 — 생성기와 같은 순서]**
- 되돌린 묶음이 이미 만료됐으면(`expires_at ≤ 현재`) 같은 시각에 **되돌린 양만큼만** EXPIRE 1행 + 배분을 뒤따라 쓴다(`remaining`은 복구 전 값으로 돌아감 — 그 묶음에 원래 남아 있던 양은 `point-expiry`가 처리). **[기본값 — 생성기와 같음]**
- 회수(REVOKE)로 0이 된 묶음에도 다른 주문의 복구는 되돌아온다(생성기·verify와 같음).

## 8. 포인트·등급

### 8.1 적립률 (ADR-0003 §5.1 개정 — §11)

- 적립률의 등급은 승인을 반영할 때(동기 승인·pg-inquiry·기동 전환 모두) 다음 순서로 정한다. **[기본값]**
  1. `user_grade_history`에서 `effective_from ≤ date(주문 created_at, KST)`인 최신 행의 등급.
  2. 그런 행이 없지만 그 사용자의 이력이 있으면(모두 요청 날짜 뒤) `BRONZE` — 첫 이력 이전 등급(시드는 모든 사용자가 BRONZE에서 시작, 생성기와 같음).
  3. 이력이 전혀 없으면 `users.grade`(1단계 테스트가 직접 넣은 사용자와 호환).
- 결과: 요청 뒤에 등급이 재산정돼도 늦은 승인은 요청 날짜의 등급을 쓴다. **한계:** `effective_from`이 날짜라, `grade-recalc`를 기준일 00:00이 아닌 시각에 돌리면 그 날 배치 전에 생성된 주문은 승인이 배치 전이냐 뒤냐에 따라 등급이 다르다. 테스트·과제는 `grade-recalc`를 기준일 00:00(KST)에 돌린다.
- 테스트가 BRONZE가 아닌 사용자를 만들고 `grade-recalc`를 거치게 할 때는 그 등급의 이력 행도 함께 넣는다(2번 규칙에 걸리지 않게).

### 8.2 포인트 확정 (구매 확정 시)

- 그 항목의 EARN이 `PENDING`이면 `AVAILABLE`, `expires_at = confirmed_at + 365일` **[verify]**(`created_at`은 결제 시각 그대로). `point_balances`: pending −, available +, `updated_at` = 현재.
- 적립 행이 없는 항목(적립 0)은 포인트 변화 없음.

### 8.3 소멸 (EXPIRE)

- (`point-expiry` 배치) 묶음 1개당 EXPIRE 1행(`status = DONE`, `amount` = 그 묶음의 `remaining`, `order_id`·`order_item_id` NULL, `created_at` = 현재) + 배분 1행. 묶음 `remaining = 0`(EARN은 `AVAILABLE` 그대로), `point_balances.available` 감소. **[기본값 — `created_at`을 배치 시각으로; verify는 `≥ expires_at`만 본다]**

### 8.4 회수 (REVOKE, 분쟁 인정 시)

- 그 항목의 EARN 묶음 `remaining > 0`이면 REVOKE 1행(`amount = remaining`, `order_id`, `order_item_id`, `created_at` = 현재) + 배분, `remaining = 0`, `available` 감소. 이미 쓴 부분은 회수하지 않는다(ADR-0002 §9.2). EARN은 `AVAILABLE`로 남는다 **[verify]**.
- 순서: 같은 처리 안에서 회수 → 환불(RESTORE)의 순서로 쓴다. **[기본값]**

### 8.5 등급 재산정 `grade-recalc`

- 기준일 `D` = 현재 시각이 속한 달의 1일(KST). 지난 달들은 소급하지 않는다. **[기본값]**
- 기준 금액 = 그 사용자가 구매자인 항목 중 상태가 `CONFIRMED`·`DISPUTED`이고 `confirmed_at`이 `[D − 6개월, D)`(KST 달력월)인 것의 체결가 합. 분쟁 인정된 항목(`REFUNDED_AFTER_CONFIRM`)은 빠진다 = 확정 월에서 뺀다(ADR-0002 §9.2).
- 등급 = VIP ≥ 5,000,000 · GOLD ≥ 2,000,000 · SILVER ≥ 500,000 · 그 밖 BRONZE **[verify]**.
- 그 사용자에게 `effective_from = D`인 이력이 이미 있으면 건너뛴다(`UNIQUE(user_id, effective_from)` 보호 — 그 달의 첫 산정 결과를 유지). **[기본값]**
- 없으면: 등급이 `users.grade`와 다를 때만 `user_grade_history` 1행(`grade`, `effective_from = D`, `basis_amount`)과 `users.grade` 갱신. 같으면 아무것도 쓰지 않는다 — 그래서 첫 실행에서 등급이 그대로였던 사용자는 같은 달에 다시 돌릴 때 기준 금액이 바뀌었으면 그때 이력이 생길 수 있다(결과는 실행 시점의 DB 상태로 결정된다).

## 9. 동시성·장애

### 9.1 경합

- 주문 항목을 바꾸는 모든 처리(§3·§4·§5·§6)는 §2.3의 주문 행 잠금으로 직렬화한다. 그래서 같은 항목에 동시에 온 두 요청 중 하나만 성공하고 다른 하나는 바뀐 상태를 보고 409를 받는다(예: 취소 ↔ 발송, 확정 ↔ 자동 확정, 분쟁 제기 ↔ 판정). 같은 주문의 다른 항목 두 개를 동시에 취소해도 "마지막 환불"(§7.1)은 정확히 한 번이다.
- 포인트 행을 바꾸는 처리(구매자)와 판매자 귀책(판매자)은 주문 잠금 뒤에 관련 사용자 행을 잠근다(`users … FOR UPDATE`, 1단계와 같은 잠금). 잠금 순서: 주문 → 항목 → 사용자, **사용자 행끼리는 id 오름차순**(구매자·판매자가 서로 바뀐 두 주문이 교착하지 않게).
- 정산(§10)은 **한 주를 트랜잭션 하나로**(모든 판매자) 처리하고, 대상 항목 행을 id 순으로 잠근 뒤(`order_items … FOR UPDATE`) 대상 조건을 다시 평가한다. 항목 처리 쪽은 주문 → 항목 순으로 잠그므로 정산과 교착하지 않는다. 정산 중인 항목의 분쟁 제기는 정산 커밋 뒤에 처리된다. **[기본값]**
- 배치 두 개를 동시에 돌리는 것은 계약 밖이다(테스트·과제는 직렬로 부른다).

### 9.2 장애

- 카드 취소는 DB 반영 전에 항목당 하나인 키로 요청한다(§7.2). PG가 취소를 기록한 뒤 앱이 죽으면 DB에는 아무것도 없고, 같은 요청을 다시 보내면 PG가 같은 기록을 돌려주고 앱이 반영한다 — 카드가 두 번 취소되지 않는다. 그 사이 항목이 다른 상태로 바뀌는 경우는 계약 밖이다(§7.2).
- 배치는 대상 1건씩 커밋한다(`weekly-settlement`는 한 주씩). 재실행은 남은 대상만 처리한다(대상 조건이 바뀐 상태를 빼므로). 한 주의 정산이 중간에 실패하면 그 주 전체가 커밋되지 않으므로, 재실행이 그 주를 처음부터 다시 정산한다.
- 응답을 못 받은 클라이언트가 같은 요청을 다시 보내면: 이미 반영됐으면 409 `ITEM_STATE_CONFLICT`(상태가 바뀌었으므로). 요청을 멱등으로 만들지 않는다. **[기본값]**

## 10. 주간 정산 `weekly-settlement`

### 10.1 어느 주를 정산하나

- 마감 시각 = 월요일 00:00 KST. 주 = `[마감 − 7일, 마감)`, `period_start` = 그 주 월요일, `period_end = period_start + 6` **[verify]**.
- 정산할 주 = 마감 ≤ 현재이고 아직 정산되지 않은 모든 주, 오래된 주부터. "정산되지 않음" = 그 주의 마감이 지금까지 만든 모든 정산의 마지막 마감보다 뒤. 시드의 마지막 마감은 2026-09-28이다. 정산 행이 하나도 없으면 현재 직전 마감 1주만 정산한다.
- 한 주는 모든 판매자를 한 트랜잭션으로 처리한다(§9). 그래서 "마지막 마감"은 한 주가 통째로 커밋될 때만 전진한다. **[verify — 마지막 마감 전 확정분은 모두 포함되어야 한다]**
- **늦게 돌린 정산:** 여러 주를 몰아서 정산하면 지난 주도 **지금의 항목 상태**로 계산한다(예: 그 주 마감 때 CONFIRMED였고 지금 DISPUTED면 그 주에 HELD, 이미 분쟁 인정됐으면 INCLUDED도 CLAWBACK도 없음). 매주 제때 돌린 결과와 다를 수 있다. 같은 요청·배치 순서면 결과는 결정적이다.

### 10.2 판매자 1명의 한 주 (`cutoff` = 그 주 마감)

| 행 | 대상 | 금액 |
|---|---|---|
| INCLUDED | 판매자의 항목 중 `CONFIRMED`, `confirmed_at < cutoff`, 아직 INCLUDED가 없는 것 | `amount` = 체결가, `fee = floor(체결가 × categories.sale_fee_rate)` |
| HELD | `DISPUTED`, `confirmed_at < cutoff`, 아직 INCLUDED가 없는 것 | 0, 0 (주마다 다시 쓴다) |
| CLAWBACK | `REFUNDED_AFTER_CONFIRM`, `cancelled_at < cutoff`, INCLUDED 수 > CLAWBACK 수 | −체결가, −수수료 |
| 패널티 | `settlement_id` NULL, `created_at < cutoff` | 그 정산에 붙인다(`settlement_id` 갱신) |

- `gross = Σamount`, `fee_total = Σfee`, `penalty_total` = 붙인 패널티 합, `carry_in` = 그 판매자 직전 정산의 `carry_out`(없으면 0), `net = gross − fee_total − penalty_total + carry_in`, `payout = max(net, 0)`, `carry_out = min(net, 0)` **[verify]**.
- 행을 만들지 않는 경우: 위 대상이 하나도 없고 `carry_in = 0`. (`carry_in < 0`이면 대상이 없어도 행을 만든다. verify `settlement_carry_next_week`는 전역 마지막 주를 검사하지 않으므로 이 경계는 테스트가 판정한다.)
- 상태·지급: `payout > 0`이면 `PAID`, `paid_at = period_start + 9일 00:00 KST`(수요일 — 정산 행을 만들 때 미리 쓴다), 아니면 `CARRIED`, `paid_at` NULL **[verify]**. 별도의 지급 배치는 없다.

### 10.3 조회 `GET /me/settlements`

- 판매자 본인 정산, `period_start` 오름차순, `items`는 `orderItemId` 오름차순(같은 항목의 HELD가 여러 정산에 있을 수 있다): `[{"settlementId", "periodStart", "periodEnd", "gross", "feeTotal", "penaltyTotal", "carryIn", "net", "payout", "carryOut", "status", "paidAt", "items": [{"orderItemId", "amount", "fee", "status"}]}]`.

## 11. ADR-0002·0003 보완 (합의 필요)

1. **[ADR-0003 §5.1 개정]** "적립률은 결제 요청 시각의 `users.grade`" → §8.1(이력 기준, 이력 없으면 `users.grade`). 1단계 테스트는 이력 없는 사용자만 써서 결과가 바뀌지 않는다. 구현은 god 2단계 첫 task(명세 결정).
2. **[ADR-0002 §5.2 해석]** 적립 유효기간 "사용 가능해진 날부터 1년" → `confirmed_at + 365일`(시각 단위) **[verify]**.
3. **[ADR-0002 §9.3 해석]** "수요일 00:00에 지급" = 정산 행을 만들 때 `paid_at`을 그 수요일 시각으로 미리 쓴다. 지급 대기 상태는 없다(스키마 `settlements.status`는 PAID·CARRIED뿐).
4. **[ADR-0002 §4.4 해석]** 자동 확정의 `confirmed_at` = `delivered_at + 7일`(배치가 늦게 돌아도 소급). 정산 배치는 자동 확정을 먼저 수행한다.
5. **[ADR-0002 §5.4 해석]** 판매 제한 "30일 안에 3회면 30일간" = 제한을 건 패널티 뒤로 횟수를 다시 센다(§6, 구매 경고와 같은 해석).
6. **[ADR-0002 §5.2 해석]** 등급 "최근 6개월" = 기준일 직전 6개 달력월(KST, 이번 달 제외). 재산정은 현재 달 1일 기준 1회, 지난 달 소급 없음.
7. 카드 취소는 PG 실패 시 반영하지 않는다(§7.2). 스키마에 환불 진행 상태가 없어서다. 실패 경로는 "아무것도 바뀌지 않음 + 재시도"뿐이고, PG 기록 뒤 앱 사망은 계약 밖이다(사용자 결정).
8. **[ADR-0002 §5.4 해석]** 늦게 돌린 주간 정산은 지난 주를 지금의 항목 상태로 계산한다(§10.1).
9. **[ADR-0002 §4.4 해석]** 7일이 지난 구매자 확정은 `confirmed_at = delivered_at + 7일`(§4.1).

## 12. 금액 예시 (테스트 기대값의 정본)

등급 BRONZE(1%). 예 A는 ADR-0003 §8의 A.

**환불**

| 예 | 주문 | 사건(순서) | 카드 환불 | 포인트 환불 | 배송비 | 주문 상태 |
|---|---|---|---|---|---|---|
| A1 | A: 120,000 · 45,000, 포인트 5,000, 배송비 0, 카드 몫 119,964 · 44,986, 포인트 몫 3,636 · 1,364 | 2번 항목 구매자 취소 | 44,986 | 1,364 | 0 | PARTIALLY_CANCELLED |
| A2 | 〃 | 이어서 1번 항목 구매자 취소(마지막) | 119,964 | 3,636 | 0 | CANCELLED (Σ카드 164,950 · Σ포인트 5,000) |
| E1 | E: 30,000 · 20,000, 수수료 1,000 · 1,000(최소), 배송비 3,000, 총액 55,000, 포인트 5,000, 카드 50,000, 카드 몫 30,910 · 19,090, 포인트 몫 3,090 · 1,910, `s_c` 2,727 · `s_p` 273 | 1번 항목 구매자 취소 | 28,183 | 2,817 | 0 | PARTIALLY_CANCELLED |
| E2 | 〃 | 이어서 2번 항목 구매자 취소(마지막 — 배송비 실림) | 21,817 | 2,183 | 3,000 | CANCELLED (Σ카드 50,000 · Σ포인트 5,000) |
| E3 | E 새 주문 | 1번 항목 검수 불합격(판매자 귀책) | 30,910 | 3,090 | 3,000 | PARTIALLY_CANCELLED, 패널티 3,000 |
| E4 | 〃(E3 뒤) | 2번 항목 구매자 취소(마지막, 배송비는 이미 환불) | 19,090 | 1,910 | 0 | CANCELLED |
| E5 | E 새 주문, E1 뒤 | 2번 항목 검수 불합격(마지막 — 판매자 귀책도 남은 배송비를 싣는다) | 21,817 | 2,183 | 3,000 | CANCELLED, 패널티 2,000 |
| E6 | E 새 주문 | 1번 항목 확정 → 분쟁 인정(첫 항목 — 배송비 부분 포함 전액) | 30,910 | 3,090 | 3,000 | PARTIALLY_CANCELLED |
| E7 | E 새 주문, 1번 항목 구매자 취소(E1) 뒤 | 2번 항목 확정 → 분쟁 인정(마지막 — 남은 배송비를 싣는다) | 21,817 | 2,183 | 3,000 | CANCELLED |

- E 계산: 기준액 b = 34,000(31,000 + 배송비) · 21,000. 카드 몫 floor 30,909 · 19,090(합 49,999) → 남은 1원은 첫 항목 → 30,910. `s_c = floor(3,000 × 30,910 / 34,000) = 2,727`. 적립 = floor(floor(30,910 × 30,000 / 34,000) × 1%) = floor(27,273 × 1%) = 272 · floor(floor(19,090 × 20,000 / 21,000) × 1%) = floor(18,180 × 1%) = 181.
- 구매자 취소의 `buyer_fee_amount`는 그 항목 수수료(E1 = 1,000). 카드 + 포인트 = 체결가 + 수수료 + 실린 배송비(E1: 28,183 + 2,817 = 31,000).

**정산** (카테고리 수수료율: SHOES 6% · APPAREL 8% · BAGS 10% · TECH 4% — `categories.sale_fee_rate`)

| 예 | 판매자의 그 주 | gross | fee_total | penalty | carry_in | net | payout | carry_out | 상태 |
|---|---|---|---|---|---|---|---|---|---|
| S1 | SHOES 120,000 + APPAREL 45,000 확정 | 165,000 | 10,800 | 0 | 0 | 154,200 | 154,200 | 0 | PAID |
| S2 | 확정 없음, 패널티 3,000 | 0 | 0 | 3,000 | 0 | −3,000 | 0 | −3,000 | CARRIED |
| S3 | S2 다음 주, TECH 30,000 확정 | 30,000 | 1,200 | 0 | −3,000 | 25,800 | 25,800 | 0 | PAID |
| S4 | S1의 SHOES 120,000이 정산 뒤 분쟁 인정, 그 주 다른 확정 없음 | −120,000 | −7,200 | 0 | 0 | −112,800 | 0 | −112,800 | CARRIED |

**포인트**

- P1 확정: E의 2번 항목 확정 → EARN 181 `AVAILABLE`, `expires_at = confirmed_at + 365일`, pending −181, available +181.
- P2 회수: A의 1번 항목(EARN 1,164)이 확정된 뒤(사용 가능 묶음은 이것 하나) 다른 주문에서 1,000P를 쓰고 분쟁 인정 → REVOKE 164, 묶음 `remaining` 0, EARN `AVAILABLE` 유지.

## 13. DB 표면 대조 (명세 가정 1)

- 2단계 절차가 쓰는 테이블·열·상태 값은 모두 `docker/schema/tables.sql`에 있다(대조표: `docs/plans/2026-10-20/behavior-tests-stage2/log.md`). 스키마로 표현할 수 없는 계약을 피한 곳: 지급 대기(§10.2 — 미리 기록), PG 취소 실패(§7.2 — 반영하지 않음). 입고 시각·수동/자동 확정 구분·분쟁 사유는 열이 없어 계약에서 다루지 않는다.

## 14. 결과

- 행위 테스트 2단계는 이 문서(+ ADR-0003·0002)만 보고 쓰고, 1단계와 따로 돌릴 수 있게 둔다. 직렬 실행, 자기 행만 단언, 테스트 끝에 `verify.sql` 1회.
