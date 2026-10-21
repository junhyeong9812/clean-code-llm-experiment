# log — behavior-tests-stage2

## 타임라인

| 시각 | 사건 | 결과/결정 |
|------|------|----------|
| 2026-10-20 | 착수: NEXT N1(행위 테스트 2단계). 읽은 것: ADR-0003 §1·§2·§9~§11, ADR-0002 §1·§3·§8 목차, behavior-tests-stage1 명세 §0~§1, NEXT N1 | 인터뷰 시작(main `e9f55ee`) |
| 2026-10-21 | 인터뷰 2회(8문항): §10 전부·계약+테스트(red)까지·등급 시점은 계약/테스트만·판매자 API+센터 admin·기본값 초안→합의·동시성 절 처음부터·stakes 높음·verify 그대로 | 명세 합의 "합의, auto" → SPEC=1·MODE=auto. 브랜치 `feat/behavior-tests-stage2`(main `e9f55ee`) |
| 2026-10-21 | task 01 조사 워커(Opus, 읽기 전용): 절차별 DB 표면·스키마 공백·verify 검사·자유도 대조(아래 "DB 표면 대조") | 가정 1 성립(열·상태·CHECK 공백 없음). 계약 선택에 따라 공백: G1 지급 대기 상태, G2 PG 취소 실패 → ADR에서 피함. 메인 교차 확인: V267-270 item_deadlines·V595-597 paid_at·V611-633 inv8·G744 복구 순서·G646-650 판매 제한·G780-799 등급·G806-845 정산 실파일 대조 일치 |
| 2026-10-21 | task 01: ADR-0004 초안 작성(메인). 핵심 선택: 자동 확정 소급(`delivered+7일`) + 정산이 자동 확정 먼저, 미정산 주 전부 순서대로, paid_at 미리 기록, 카드 취소 결정적 키·실패 시 무변경 409, 적립률 이력 기준(없으면 users.grade), 주문 행 잠금 직렬화 | 초안 커밋. task 02(설계 선검증 + 듀얼 1패스)로 |
| 2026-10-21 | task 02 리뷰 착수: ADR-0004 = 설계 문서 → 설계 선검증(금액 표 재계산·[verify] 주장 대조를 리뷰 지시 3·4로 포함) + 듀얼 1패스. packet base `025e3b2`, OUT `scratchpad/rv.LVul`, 미러 `mirror.Y6Bd`(verify.sql·seedgen.py 포함), diff 255줄, 보안 스캔 0건 | Opus ∥ codex 시작 |

## 리뷰 ledger

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| D1 | 1 | codex+opus | 0004 §4.1, verify:270 | 채택 | fixed | 1 |
| D2 | 1 | codex+opus(+감사 A2) | 0004 §8.5, constraints:12 | 채택 | fixed | 1 |
| D3 | 1 | codex+opus(+감사 A1) | 0004 §8.1 | 채택 | fixed(한계 명시) | 1 |
| D4 | 1 | codex+opus(+감사 A3) | 0004 §7.2·§9.2 | 채택(사용자 결정: 계약 밖+금액 확인) | fixed | 1 |
| D5 | 1 | opus | 0004 §10.1·§9.2 | 채택 | fixed | 1 |
| D6 | 1 | opus | 0004 §6·§9.1 | 채택 | fixed | 1 |
| D7 | 1 | opus | 0004 §7.3·§8.3 | 채택 | fixed | 1 |
| D8 | 1 | codex+opus | 0004 §10.3·§3 | 채택 | fixed | 1 |
| D9 | 1 | codex+opus OQ | 0004 §10.1 | 채택(문서) | fixed | 1 |
| D10 | 1 | codex | 0004 §1·§10.2 | 채택(문서) | fixed | 1 |
| Q1~Q6 | 1 | opus OQ | 0004 §12·§4.1·§10.1·§7.1·§7.3·§9.1 | 채택(문서·예시) | fixed | 1 |
| 2026-10-21 | 리뷰 회수: codex 7건(F1~F7, 금액 12행 전부 맞음) · Opus 8건+OQ 7(금액 전부 맞음, [verify] 주장 전부 맞음). 메인 종합 D1~D10·Q1~Q6 | 원문·종합표 `scratchpad/rv.LVul/results/` |
| 2026-10-21 | 사용자 결정(D4 카드 취소 중 앱 사망): "계약 밖 + 최소 안전장치" | |
| 2026-10-21 | codex 감사(1회 성공): A1 D3 같은 날 배치 전후 승인 차이·GOLD 무이력 · A2 D2 "스냅샷" 표현 부정확(BRONZE 유지 사용자는 D 행 없음) · A3 D4(b)의 "키에 사유" 안이 고아 뒤 이중 카드 취소를 만든다 | A1 채택: 한계 명시 + grade-recalc 기준일 00:00 실행 전제 + 비BRONZE 테스트 사용자는 이력 행 함께. A2 채택: 문구 정정. A3 채택: 키는 항목당 하나 유지, 안전장치는 금액 불일치 409만(사용자 결정 "계약 밖 + 최소 안전장치"의 범위 안 — 세부 교체를 보고) |
| 2026-10-21 | ADR-0004 수정(메인): D1 확정 min(현재, 배송+7일) · D2 D 행 있으면 건너뜀 · D3 등급 3단계 규칙+한계 · D4 계약 밖+금액 확인 · D5 한 주 = 한 트랜잭션 · D6 판매자 행 잠금·사용자 id 순 · D7 만료 묶음 복구분만 EXPIRE · D8 items 순서·reason 규칙 · D9/§11.8 늦은 정산 · D10 [verify] 범위 · Q1 P2 1,000P→164 · Q2~Q6 문서화 | post-fix 재점검(codex)으로 |
| 2026-10-21 | post-fix 재점검(codex, packet base `e792eea`, OUT `scratchpad/rv.bicV`): D1~D10·P2 전부 해소, **신규 0** | ADR-0004 리뷰 종료(설계 문서 = 설계 선검증+듀얼 1패스 상한). 사용자 합의 요청 |
| 2026-10-21 | 사용자: ADR-0004 "채택" | 상태 채택. §11.1 → ADR-0003 §9의 8, §11.2~9 → ADR-0002 §9.7 개정 항목으로 옮김(명세 §4 방식). task 02 완료 → task 03(테스트 기반) |
| 2026-10-21 11:17~11:28 | task 03 워커(Opus): `contract2Test` 태스크(태그 contract2 + 패키지 필터, contractTest에서 contract2 패키지 제외), FakePg `POST /cancellations`·`UNAVAILABLE`·`GET /_control/cancellations/{key}`, `@Contract2Test`, FakePgCancellationTest 8개. run.sh 수정 없음(두 번째 인자로 태스크) | run.sh god·layered exit 0(infra 26 = 18+8, contract 117/117, skip 0, verify 0). contract2Test 0개 → exit 1(No tests found — 0건 통과 위장 없음, 메인: 유지). 메인 확인: build.gradle diff 열람·금지영역 diff 0 → 코드 커밋. 보조 제어 엔드포인트는 ADR §7.2에 1줄 추가. 동시에 다른 세션의 무관한 부하 프로세스(`/tmp/tmp.k7g7Rydr7o/scripts/run.sh`)가 돌고 있었음 — 결과에 영향 없음 |

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
