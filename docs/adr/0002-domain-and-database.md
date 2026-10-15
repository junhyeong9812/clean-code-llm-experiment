# ADR-0002: 도메인 절차·규칙과 DB 설계 — 입찰 · 구매 · 결제 · 포인트 · 정산

- 상태: **채택** (2026-10-15). 초안 2026-10-14
- 앞 결정: [ADR-0001 기본 세팅](0001-base-setup.md)
- 표기: **[기본값]** = 인터뷰에서 정하지 않아 제안한 기본값. 사용자 검토에서 그대로 채택했다(2026-10-15).

## 1. 결정 요약 (인터뷰)

| 항목 | 결정 |
|---|---|
| 거래 모델 | 리셀 마켓형. 판매 입찰·구매 입찰이 가격으로 체결 → 결제 → 발송·검수·배송 → 구매 확정 → 판매자 정산 |
| 결제 | 카드 + 포인트 복합결제. 체결 여러 건을 한 번에 결제(묶음), 건별 취소 = 부분 취소 |
| 부분 취소 안분 | 건 금액 비례로 카드·포인트를 나눠 환불. 원 단위 잔차는 그 결제의 마지막 취소 건이 흡수(총합 정확) |
| 결제 실패 | 결제 기한 + 멱등 키 + PG 타임아웃 시 상태 재조회 |
| 수수료 | 판매 수수료(카테고리별) + 구매 수수료 + 배송비 |
| 포인트 적립 | 결제 시 '적립 예정(PENDING)' → 구매 확정 시 '사용 가능(AVAILABLE)'. 실결제(카드) 금액 기준, 결제 시점 등급의 적립률 |
| 등급 | 4단계, 최근 6개월 구매 확정 금액으로 매월 1일 재산정 |
| 포인트 사용 | 유효기간·FIFO(만료 임박분부터) 차감, 최소 1,000P·10P 단위, 결제액의 최대 50% |
| 포인트 회수 | 취소 시 PENDING 적립 취소, 사용분은 비례 복구 |
| 판매자 귀책 | 검수 불합격·미발송 → 구매자 전액 환불 + 판매자 패널티(거래가 %)를 다음 정산에서 차감 |
| 정산 | 구매 확정분을 주 단위로 정산, 카테고리별 수수료, 분쟁 중인 건은 보류 |
| 입찰 | 유효기간 30·60·90일, 사이즈별 가격 우선 → 같은 가격은 먼저 등록한 순. 즉시 구매·즉시 판매 = 최우선 상대 입찰과 체결 |

## 2. 행위자와 용어

| 용어 | 뜻 |
|---|---|
| 상품(product)·사이즈(product_size) | 거래 단위는 상품의 사이즈 하나(예: 신발 270) |
| 입찰(bid) | 구매 입찰(BUY): 이 가격 이하로 사겠다. 판매 입찰(SELL): 이 가격 이상으로 팔겠다 |
| 체결(trade) | 구매 입찰과 판매 입찰이 맞은 거래 1건 = 상품 1개 |
| 주문(order) | 구매자가 결제 대기 중인 체결 여러 건을 한 번에 결제하는 묶음 |
| 주문 항목(order_item) | 주문 안의 체결 1건. 발송·검수·배송·확정·취소의 단위 |
| 포인트 원장(point_ledger) | 적립·사용·복구·소멸·회수의 모든 변동 기록 |
| 정산(settlement) | 판매자별 주간 지급액 계산서 |

## 3. 전체 절차

```
판매 입찰 등록 ─┐
                ├─ 체결(trade: AWAITING_PAYMENT, 결제 기한 30분)
구매 입찰 등록 ─┘          │
  (즉시 구매/판매)          ├─ 기한 내 결제 ── 주문 결제(카드 승인 + 포인트 사용) ── 항목 PAID
                            │                     └─ 포인트 적립 예정(PENDING)
                            └─ 기한 초과 ── 체결 취소, 판매 입찰 복구, 구매자 경고
항목 PAID ─ 판매자 발송(SHIPPED_TO_CENTER) ─ 검수(INSPECTING)
   ├─ 합격 ─ 배송(DELIVERING) ─ 배송 완료(DELIVERED) ─ 구매 확정(CONFIRMED)
   │                                                    ├─ 적립 예정 → 사용 가능
   │                                                    └─ 다음 주간 정산에 포함
   └─ 불합격 / 발송 기한 초과 ─ 판매자 귀책 취소: 구매자 전액 환불, 판매자 패널티
구매자 취소(발송 전까지만) ─ 부분 취소: 비례 안분 환불, 적립 예정 취소, 사용 포인트 복구
구매 확정 후 분쟁 ─ 정산 보류 ─ 인정 시 환불 + 적립 회수 / 기각 시 보류 해제
```

## 4. 상태 전이

### 4.1 입찰 `bids.status`

| 현재 | 사건 | 다음 |
|---|---|---|
| ACTIVE | 상대 입찰과 체결 | MATCHED |
| ACTIVE | 등록자 취소 | CANCELLED |
| ACTIVE | 유효기간 경과(배치) | EXPIRED |
| MATCHED | 체결이 결제 기한 초과로 취소되고 판매 입찰이 유효기간 안 | ACTIVE(판매 입찰만 복구, 구매 입찰은 CANCELLED) |

- 매칭: 같은 `product_size`에서 구매 최고가 ≥ 판매 최저가일 때 체결한다. 체결 가격은 **먼저 등록된 입찰의 가격**이다. 즉시 구매는 판매 최저가, 즉시 판매는 구매 최고가로 체결한다.
- 같은 가격이면 `created_at`이 빠른 입찰이 우선이다.
- 동시성: 같은 입찰이 두 번 체결되면 안 된다(행 잠금 또는 조건부 갱신 `WHERE status='ACTIVE'`).
- 자기 체결 금지: 같은 사용자의 구매·판매 입찰은 체결하지 않는다. **[기본값]**

### 4.2 체결 `trades.status`

| 현재 | 사건 | 다음 |
|---|---|---|
| AWAITING_PAYMENT | 주문 결제 승인 | PAID |
| AWAITING_PAYMENT | 결제 기한(체결 + 30분) 초과(배치) | PAYMENT_EXPIRED |
| PAID | 주문 항목 진행 | (항목 상태를 따른다) |

- 결제 기한 초과 시 구매자 경고 +1. 30일 안에 3회면 7일간 구매 입찰·즉시 구매 제한. **[기본값]**

### 4.3 주문 `orders.status`

| 현재 | 사건 | 다음 |
|---|---|---|
| PENDING | 결제 요청(멱등 키) | PAYING |
| PAYING | PG 승인 | PAID |
| PAYING | PG 거절 | FAILED(체결은 AWAITING_PAYMENT 유지, 기한 안이면 재시도 가능) |
| PAYING | PG 타임아웃 | UNKNOWN → 재조회로 PAID 또는 FAILED 확정 |
| PAID | 일부 항목 취소 | PARTIALLY_CANCELLED |
| PAID·PARTIALLY_CANCELLED | 모든 항목 취소 | CANCELLED |

- 같은 멱등 키의 재요청은 새 승인을 만들지 않고 첫 결과를 돌려준다.
- 포인트 사용은 PG 승인 전에 '예약'하고, 승인되면 확정하고, 거절되면 풀어 준다.

### 4.4 주문 항목 `order_items.status`

| 현재 | 사건 | 다음 |
|---|---|---|
| PAID | 판매자 발송 등록 | SHIPPED_TO_CENTER |
| PAID | 발송 기한(결제 + 3일) 초과(배치) | CANCELLED_SELLER_FAULT |
| PAID | 구매자 취소 | CANCELLED_BY_BUYER |
| SHIPPED_TO_CENTER | 센터 입고 | INSPECTING |
| INSPECTING | 검수 합격 | DELIVERING |
| INSPECTING | 검수 불합격 | CANCELLED_SELLER_FAULT |
| DELIVERING | 배송 완료 | DELIVERED |
| DELIVERED | 구매자 확정 또는 배송 완료 + 7일(배치) | CONFIRMED |
| CONFIRMED | 분쟁 접수(확정 후 14일 안) | DISPUTED |
| DISPUTED | 분쟁 인정 | REFUNDED_AFTER_CONFIRM |
| DISPUTED | 분쟁 기각 | CONFIRMED |

- 구매자 취소는 발송 전(PAID)까지만 된다. **[기본값]**

### 4.5 포인트 원장 항목

| 종류 | 상태 | 뜻 |
|---|---|---|
| EARN | PENDING → AVAILABLE / CANCELLED | 결제 시 적립 예정, 확정 시 사용 가능, 취소 시 무효 |
| USE | RESERVED → CONFIRMED / RELEASED | 결제 중 예약, 승인 시 확정, 거절 시 해제 |
| RESTORE | — | 취소로 사용분 복구(원래 적립 묶음으로 되돌림, 만료일 유지) |
| EXPIRE | — | 유효기간 경과분 소멸(배치) |
| REVOKE | — | 확정 후 환불 시 적립분 회수 |

## 5. 금액 규칙

### 5.1 결제 금액

```
항목 금액     = 체결가
구매 수수료   = max(체결가 × 3%, 1,000원)          (항목별, 원 단위 버림)
배송비        = 주문당 3,000원, 항목 금액 합 ≥ 100,000원이면 0원   [기본값]
주문 총액     = Σ(체결가 + 구매 수수료) + 배송비
포인트 사용   ≤ 주문 총액 × 50%, ≤ 사용 가능 잔액, ≥ 1,000P, 10P 단위
카드 결제액   = 주문 총액 − 포인트 사용
```

### 5.2 포인트 적립

```
적립 기준     = 카드 결제액 중 항목(체결가) 몫     (수수료·배송비 몫 제외)   [기본값]
항목별 적립   = floor(항목의 카드 몫 × 등급 적립률)
등급 적립률   = BRONZE 1% · SILVER 1.5% · GOLD 2% · VIP 3%   (결제 시점 등급)
등급 기준     = 최근 6개월 구매 확정 금액: SILVER ≥ 50만 · GOLD ≥ 200만 · VIP ≥ 500만   [기본값]
유효기간      = 사용 가능해진 날부터 1년   [기본값]
```

- 항목별 카드 몫·포인트 몫 = 주문 총액 대비 (체결가 + 구매 수수료)의 비율로 안분한다. 배송비는 첫 항목에 귀속한다. **[기본값]**

### 5.3 부분 취소 환불

```
취소 항목의 환불 = 그 항목의 (체결가 + 구매 수수료) 몫을 카드·포인트 비례 안분
잔차          = 원 단위 반올림 차이는 그 주문의 마지막 취소 항목이 흡수 → 모든 항목이 취소되면 Σ환불 = 원 결제와 정확히 같다
배송비        = 주문의 모든 항목이 취소될 때 마지막 취소에서 환불   [기본값]
판매자 귀책   = 구매 수수료·배송비 몫까지 전액 환불
포인트        = 취소 항목의 적립 예정(PENDING)은 CANCELLED, 사용 포인트 몫은 RESTORE(원래 묶음·만료일로)
```

### 5.4 판매자 정산

```
항목 정산액   = 체결가 − 판매 수수료(카테고리별: 신발 6% · 의류 8% · 가방 10% · 테크 4%)   [기본값: 카테고리·비율]
패널티        = 판매자 귀책 취소 1건당 체결가 × 10%   [기본값]
주간 정산     = 지난주(월 00:00 ~ 일 24:00) CONFIRMED 항목 정산액 합 − 미정산 패널티 + 이월액
지급          = 정산 > 0 이면 수요일 지급, ≤ 0 이면 0원 지급하고 음수분 이월
보류          = DISPUTED 항목은 해당 주 정산에서 제외(HELD), 해소된 주에 포함
확정 후 환불  = 이미 정산된 항목이 분쟁 인정되면 그 정산액을 다음 정산에서 차감
```

- 포인트 비용은 플랫폼이 부담한다. 판매자 정산은 포인트 사용과 무관하다.
- 판매 제한: 30일 안에 판매자 귀책 3회면 30일간 판매 입찰 제한. **[기본값]**

## 6. DB 설계 (PostgreSQL 17)

금액은 원 단위 `bigint`, 비율은 `numeric(5,4)`, 시각은 `timestamptz`로 둔다. 두 구현(`god`·`layered`)은 같은 스키마를 쓴다. 갓 클래스와 레이어드의 차이는 코드에만 있다.

```sql
-- 사용자·등급
users(id bigserial PK, email text UNIQUE, name text, grade text NOT NULL,           -- BRONZE·SILVER·GOLD·VIP
      purchase_restricted_until timestamptz, sale_restricted_until timestamptz, created_at timestamptz)
user_grade_history(id PK, user_id FK, grade text, effective_from date, basis_amount bigint)
seller_payout_accounts(user_id PK FK, bank_code text, account_no_masked text)

-- 상품
categories(id PK, name text UNIQUE, sale_fee_rate numeric(5,4))
products(id PK, category_id FK, brand text, name text, model_no text UNIQUE, release_price bigint)
product_sizes(id PK, product_id FK, size text, UNIQUE(product_id, size))

-- 입찰·체결
bids(id PK, user_id FK, product_size_id FK, side text,                              -- BUY·SELL
     price bigint, status text, expires_at timestamptz, created_at timestamptz)
  INDEX (product_size_id, side, status, price, created_at)                          -- 매칭 탐색
trades(id PK, buy_bid_id FK UNIQUE, sell_bid_id FK UNIQUE, buyer_id FK, seller_id FK,
       product_size_id FK, price bigint, status text, matched_at timestamptz, payment_due_at timestamptz)
buyer_warnings(id PK, user_id FK, trade_id FK, created_at)

-- 주문·결제
orders(id PK, buyer_id FK, status text, item_total bigint, buyer_fee_total bigint, shipping_fee bigint,
       point_used bigint, card_amount bigint, idempotency_key text UNIQUE, created_at, paid_at)
order_items(id PK, order_id FK, trade_id FK UNIQUE, price bigint, buyer_fee bigint,
            card_share bigint, point_share bigint, shipping_share bigint, status text,
            paid_at, shipped_at, inspected_at, delivered_at, confirmed_at, cancelled_at)
pg_transactions(id PK, order_id FK, kind text,                                       -- APPROVE·CANCEL·INQUIRY
                amount bigint, status text,                                          -- REQUESTED·APPROVED·DECLINED·UNKNOWN
                pg_tx_id text, idempotency_key text, requested_at, responded_at)
refunds(id PK, order_item_id FK, card_amount bigint, point_amount bigint, buyer_fee_amount bigint,
        shipping_amount bigint, reason text, fault text, created_at)                -- fault: BUYER·SELLER·DISPUTE
inspections(id PK, order_item_id FK UNIQUE, result text, reason text, inspected_at)
disputes(id PK, order_item_id FK, status text, opened_at, resolved_at)

-- 포인트
point_ledger(id PK, user_id FK, kind text,                                           -- EARN·USE·RESTORE·EXPIRE·REVOKE
             status text, amount bigint, remaining bigint,                           -- remaining: EARN 묶음의 남은 양
             expires_at timestamptz, order_item_id FK NULL, order_id FK NULL, created_at)
  INDEX (user_id, kind, status, expires_at)                                         -- FIFO 차감
point_allocations(id PK, use_entry_id FK, earn_entry_id FK, amount bigint)          -- 사용이 어느 적립 묶음에서 나갔나
point_balances(user_id PK FK, available bigint, pending bigint, updated_at)         -- 원장의 캐시(검증 대상)

-- 정산
settlements(id PK, seller_id FK, period_start date, period_end date, gross bigint, fee_total bigint,
            penalty_total bigint, carry_in bigint, net bigint, payout bigint, carry_out bigint,
            status text, paid_at, UNIQUE(seller_id, period_start))
settlement_items(id PK, settlement_id FK, order_item_id FK, amount bigint, fee bigint, status text)  -- INCLUDED·HELD·CLAWBACK
seller_penalties(id PK, seller_id FK, order_item_id FK, amount bigint, settlement_id FK NULL, created_at)
```

### 6.1 데이터 불변식 (적재 검증과 과제 판정에 쓴다)

1. 주문: `item_total + buyer_fee_total + shipping_fee = point_used + card_amount`
2. 주문 항목 몫의 합 = 주문 금액(카드 몫 합 = card_amount, 포인트 몫 합 = point_used)
3. 한 주문의 환불 합 ≤ 결제액. 모든 항목이 취소되면 정확히 같다.
4. `point_balances.available` = 그 사용자 AVAILABLE EARN 묶음의 `remaining` 합. `pending` = PENDING EARN 합.
5. `point_allocations`: 한 USE의 배분 합 = USE 금액, 한 EARN의 배분 합 ≤ EARN 금액.
6. 입찰 1개는 체결 최대 1건(`trades.buy_bid_id`·`sell_bid_id` UNIQUE).
7. 정산: `net = gross − fee_total − penalty_total + carry_in`, `payout = max(net, 0)`, `carry_out = min(net, 0)`.
8. CONFIRMED 항목은 정확히 한 정산에 INCLUDED되거나 HELD 상태다.
9. 고아 행 없음: 모든 FK 대상 존재. PAID 이후 주문 항목에는 승인된 `pg_transactions`가 있다.

## 7. 합성 데이터 규모 (다음 작업의 입력)

| 테이블 | 행 수(예상) |
|---|---|
| users | 500,000 |
| products / product_sizes | 약 20,000 / 약 200,000 |
| bids | 약 12,000,000(체결·만료·취소·활성 포함) |
| trades / order_items | 약 5,000,000 |
| orders | 약 3,000,000(묶음 평균 1.6건) |
| point_ledger | 약 12,000,000 |
| settlements | 약 1,500,000(판매자 약 10만 × 주) |

- 디스크(여유 약 50GB)와 시드 이미지 크기 때문에 실제 행 수는 데이터 생성 작업에서 측정하고 조정한다.

## 8. 미결정 · 다음 단계

- [기본값] 항목 13곳은 검토 결과 그대로 채택했다(2026-10-15).
- 갓 클래스의 범위: 위 절차 전체(입찰·체결·결제·취소·포인트·검수·확정·정산 배치)를 클래스 1개, 파일 1개에 담는다. 컨트롤러·엔티티·DTO는 바깥에 둔다(ADR-0001).
- 배치(만료·결제 기한·자동 확정·등급 재산정·포인트 소멸·주간 정산)는 HTTP 관리 엔드포인트로도 실행할 수 있게 한다. 테스트와 과제에서 시각을 고정해 부르기 위해서다.
- 다음 작업: DDL 확정(`docker/init`) → 합성 데이터 생성기·CSV·적재·불변식 검증 → 행위 테스트 → 갓 클래스 구현

## 9. 개정 (2026-10-15, DDL·생성기 작업 중)

### 9.1 입찰 복구와 체결 유일성

§4.1의 "판매 입찰 복구"와 불변식 6(`trades.sell_bid_id` UNIQUE)이 서로 부딪혔다. 복구된 입찰이 다시 체결될 수 없기 때문이다. 다음과 같이 고친다(사용자 결정).

- `bids.status`는 현재 상태만 갖는다. 모든 상태 변화는 append-only 테이블 `bid_events(id, bid_id, from_status, to_status, trade_id NULL, created_at)`에 쌓는다. 등록 시에도 `NULL → ACTIVE`를 1행 남긴다.
- 결제 기한이 지난 체결은 지우지 않고 `PAYMENT_EXPIRED`로 남긴다. `buyer_warnings`가 이 행을 참조한다.
- 유일성은 부분 UNIQUE로 강제한다: `UNIQUE(buy_bid_id) WHERE status <> 'PAYMENT_EXPIRED'`, `sell_bid_id`도 같다.
- 불변식 6을 고친다: 입찰 1개에 **살아 있는**(PAYMENT_EXPIRED가 아닌) 체결은 최대 1건이다.
- 같은 이유로 주문 항목에도 부분 UNIQUE를 쓴다. 결제가 거절되면 그 주문의 항목은 `PAYMENT_FAILED`가 되고, 같은 체결로 새 주문을 만들 수 있다: `UNIQUE(trade_id) WHERE status <> 'PAYMENT_FAILED'`. 결제 중인 항목의 상태는 `AWAITING_PAYMENT`다.

### 9.2 금액 세부 (기본값의 구체화)

- **항목 몫:** 항목 i의 기준액 `b_i = 체결가 + 구매 수수료 (+ 배송비, 첫 항목만)`. 카드 몫은 `floor(card_amount × b_i / 주문 총액)`로 나누고, 남은 원은 앞 항목부터 1원씩(그 항목의 기준액을 넘지 않게) 더한다. 포인트 몫 = `b_i − 카드 몫`. `shipping_share`는 첫 항목에만 배송비, 나머지는 0이다.
- **배송비의 카드·포인트 분할:** 첫 항목의 배송비 중 카드 부분은 `floor(배송비 × 카드 몫 / b_1)`, 나머지는 포인트 부분이다.
- **환불:** 구매자 취소는 그 항목의 몫에서 배송비 부분을 뺀 만큼 환불한다. 주문의 모든 항목이 취소·환불되는 마지막 환불에서 아직 환불되지 않은 배송비를 함께 돌려준다. 판매자 귀책과 분쟁 인정은 항목의 몫 전부(배송비 부분 포함)를 즉시 환불한다. 결과: 모든 항목이 환불된 주문은 `Σ환불 = 결제액`이 정확히 성립한다.
- **적립 기준:** 항목의 카드 몫 중 체결가 부분 = `floor(카드 몫 × 체결가 / b_i)`. 적립 = `floor(그 값 × 적립률)`. 0이면 적립 행을 만들지 않는다.
- **포인트 배분:** `point_allocations(entry_id, earn_entry_id, amount)`. `entry_id`는 USE·EXPIRE·REVOKE(묶음에서 빠짐) 또는 RESTORE(묶음으로 되돌아옴)다. 어느 경우든 한 항목의 배분 합 = 그 항목의 금액이다. AVAILABLE EARN의 `remaining = amount − Σ(USE·EXPIRE·REVOKE 배분) + Σ(RESTORE 배분)`, `0 ≤ remaining ≤ amount`.
- **복구 순서:** 취소된 항목의 포인트 몫은 그 주문 USE가 빠져나간 묶음으로 되돌린다. 만료일이 늦은 묶음부터 채운다. 되돌린 묶음이 이미 만료됐으면 같은 시각에 EXPIRE가 뒤따른다.
- **회수(REVOKE):** 분쟁 인정 시 그 항목 적립 묶음의 남은 양만큼만 회수한다. 이미 쓴 부분은 회수하지 않는다(플랫폼 부담).
- **등급 산정 금액:** 구매 확정 시 체결가를 더하고, 확정 후 환불(분쟁 인정) 시 그 확정 월에서 뺀다.

### 9.3 정산 세부

- 정산은 매주 월요일 00:00(KST)에 지난주 분을 계산하고, 수요일 00:00에 지급한다(`paid_at`). 그 주에 포함 항목·보류·회수·패널티·이월이 하나도 없는 판매자는 행을 만들지 않는다.
- `settlement_items`: INCLUDED(amount = 체결가, fee = 판매 수수료), HELD(그 시점 DISPUTED, amount·fee 0 — 기록용), CLAWBACK(이미 포함된 뒤 분쟁 인정, amount·fee는 음수).
- `gross = Σ amount`, `fee_total = Σ fee`(INCLUDED·CLAWBACK), `penalty_total =` 그 정산에 붙은 패널티 합, `carry_in =` 직전 정산의 `carry_out`.
- 불변식 8을 고친다: 정산 마감 전에 확정된 CONFIRMED 항목은 INCLUDED가 정확히 1행이고 CLAWBACK이 없다. REFUNDED_AFTER_CONFIRM 항목은 INCLUDED 수 = CLAWBACK 수(0 또는 1)다.
- 정산 상태: `PAID`(payout > 0, 수요일 지급), `CARRIED`(payout 0).

### 9.4 상태 값

- bids: ACTIVE·MATCHED·CANCELLED·EXPIRED / trades: AWAITING_PAYMENT·PAID·PAYMENT_EXPIRED
- orders: PENDING·PAYING·PAID·FAILED·UNKNOWN·PARTIALLY_CANCELLED·CANCELLED
- order_items: AWAITING_PAYMENT·PAYMENT_FAILED·PAID·SHIPPED_TO_CENTER·INSPECTING·DELIVERING·DELIVERED·CONFIRMED·DISPUTED·REFUNDED_AFTER_CONFIRM·CANCELLED_BY_BUYER·CANCELLED_SELLER_FAULT
- point_ledger: EARN(PENDING·AVAILABLE·CANCELLED), USE(RESERVED·CONFIRMED·RELEASED), RESTORE·EXPIRE·REVOKE(DONE)
- inspections.result: PASS·FAIL / disputes.status: OPEN·ACCEPTED·REJECTED / settlements.status: PAID·CARRIED

### 9.5 세부 해석 (검증 SQL 작성 중 드러난 모호점의 확정)

- **PG 승인:** 승인 = `APPROVE`가 APPROVED이거나, `APPROVE`가 UNKNOWN이고 뒤따른 `INQUIRY`가 APPROVED. 행은 고치지 않고 덧붙인다(append-only).
- **포인트 예약 해제:** RELEASED된 USE에는 배분 행이 없다(예약은 승인될 때 배분으로 기록된다). §9.2의 배분 합 규칙은 CONFIRMED USE에 적용한다.
- **확정 후 환불(REFUNDED_AFTER_CONFIRM):** 주문 상태 계산에서 취소와 같이 센다(일부면 PARTIALLY_CANCELLED, 전부면 CANCELLED). `cancelled_at` = 분쟁 인정 시각.
- **환불 행의 열:** `card_amount + point_amount` = 실제 환불액. `buyer_fee_amount` = 그 항목의 구매 수수료(구매자 취소도 수수료를 환불한다, §5.3 첫 줄), `shipping_amount` = 이 환불에 포함된 배송비.
- **분쟁 인정의 포인트 몫:** 다른 취소와 같이 RESTORE로 되돌린다.
- **항목 순서:** "첫 항목·앞 항목"은 `order_items.id` 순서.
- **패널티의 정산:** 패널티는 발생 후 처음 마감되는 그 판매자의 주간 정산에 붙는다.
- **CARRIED 정산:** `paid_at`은 NULL.
- **정산 시점의 예외(현재 시각 스냅샷):** 마지막 마감 뒤에 분쟁이 기각된 항목은 아직 INCLUDED가 없다(보류만 있다). 마지막 마감 뒤에 분쟁이 인정된 항목은 아직 CLAWBACK이 없다. 둘 다 다음 정산에서 처리된다.
