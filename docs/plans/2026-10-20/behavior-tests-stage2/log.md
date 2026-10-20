# log — behavior-tests-stage2

## 타임라인

| 시각 | 사건 | 결과/결정 |
|------|------|----------|
| 2026-10-20 | 착수: NEXT N1(행위 테스트 2단계). 읽은 것: ADR-0003 §1·§2·§9~§11, ADR-0002 §1·§3·§8 목차, behavior-tests-stage1 명세 §0~§1, NEXT N1 | 인터뷰 시작(main `e9f55ee`) |
| 2026-10-21 | 인터뷰 2회(8문항): §10 전부·계약+테스트(red)까지·등급 시점은 계약/테스트만·판매자 API+센터 admin·기본값 초안→합의·동시성 절 처음부터·stakes 높음·verify 그대로 | 명세 합의 "합의, auto" → SPEC=1·MODE=auto. 브랜치 `feat/behavior-tests-stage2`(main `e9f55ee`) |
| 2026-10-21 | task 01 조사 워커(Opus, 읽기 전용): 절차별 DB 표면·스키마 공백·verify 검사·자유도 대조(아래 "DB 표면 대조") | 가정 1 성립(열·상태·CHECK 공백 없음). 계약 선택에 따라 공백: G1 지급 대기 상태, G2 PG 취소 실패 → ADR에서 피함. 메인 교차 확인: V267-270 item_deadlines·V595-597 paid_at·V611-633 inv8·G744 복구 순서·G646-650 판매 제한·G780-799 등급·G806-845 정산 실파일 대조 일치 |
| 2026-10-21 | task 01: ADR-0004 초안 작성(메인). 핵심 선택: 자동 확정 소급(`delivered+7일`) + 정산이 자동 확정 먼저, 미정산 주 전부 순서대로, paid_at 미리 기록, 카드 취소 결정적 키·실패 시 무변경 409, 적립률 이력 기준(없으면 users.grade), 주문 행 잠금 직렬화 | 초안 커밋. task 02(설계 선검증 + 듀얼 1패스)로 |

## 리뷰 ledger

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|

## DB 표면 대조 (가정 1 — 조사 워커 회수본 요약, 근거 T=tables.sql·V=verify.sql·G=seedgen.py)

| 절차 | 테이블·열 | 상태 값 | 제약(verify) |
|---|---|---|---|
| 구매자 취소 | order_items status·cancelled_at / refunds(fault BUYER) / pg CANCEL / RESTORE+배분 / EARN→CANCELLED / point_balances / orders.status | T122-124·T154·T137·T187-189·T101 | PAID에서만, V257 |
| 발송 | shipped_at | SHIPPED_TO_CENTER T122 | V268 ≤ paid+3일 |
| 입고 | status만(시각 열 없음) | INSPECTING | V249 inspected_at NULL |
| 검수 PASS/FAIL | inspections(UNIQUE C10)·inspected_at / FAIL = 귀책 환불·패널티·판매 제한 | PASS·FAIL T161 | V258-259·V274-281 |
| 배송 완료 | delivered_at | DELIVERED | V263 |
| 확정(수동·자동) | confirmed_at / EARN→AVAILABLE·expires_at | CONFIRMED·AVAILABLE(T192) | V270 ≤ delivered+7일, V450 +365일 |
| 발송 기한 초과 | 귀책 환불·패널티 | CANCELLED_SELLER_FAULT | V269 ≥ paid+3일 |
| 분쟁 제기/인정/기각 | disputes(OPEN·ACCEPTED·REJECTED, T172) / REFUNDED_AFTER_CONFIRM·cancelled_at=resolved_at / REVOKE / CLAWBACK | T169·T123·T238 | V283-295 [confirmed, +14일], V255-256 |
| 소멸·회수·복구 | point_ledger EXPIRE·REVOKE·RESTORE(DONE) + point_allocations | T187-189 | V401-418 |
| 등급 재산정 | user_grade_history(UNIQUE user·effective_from C12)·users.grade | T10·T19·T21 | V649-657 (1일, 임계값, 최신=현재) |
| 정산·패널티 | settlements(PAID·CARRIED T224·T228)·settlement_items(INCLUDED·HELD·CLAWBACK)·seller_penalties | T224-248 | V575-645 |

- 공백(계약 선택 의존): G1 지급 대기 상태 없음(T224·T228, V597) → paid_at 미리 기록. G2 환불 진행 상태 없음(T146-156, V341-347) → PG 실패 시 무반영. 정보 없음: 입고 시각, 수동/자동 확정 구분, 분쟁 사유.

## 생략한 검증

- (없음)

## 완료 요약
