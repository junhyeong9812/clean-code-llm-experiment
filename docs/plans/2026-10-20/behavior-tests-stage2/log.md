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
| Q1~Q6(ADR) | 1 | opus OQ | 0004 §12·§4.1·§10.1·§7.1·§7.3·§9.1 | 채택(문서·예시) | fixed | 1 |
| L1-1 | 1 | codex | 테스트 | 채택 | fixed | 1 |
| L1-2 | 1 | codex+opus | 테스트 | 채택 | fixed | 1 |
| L1-3 | 1 | codex+opus | 테스트 | 채택 | fixed | 1 |
| L1-4 | 1 | codex | 테스트 | 채택 | fixed | 1 |
| L1-5 | 1 | codex+opus | 테스트 | 채택 | fixed | 1 |
| L1-6 | 1 | opus | 테스트 | 채택 | fixed | 1 |
| L1-7 | 1 | opus | 테스트 | 채택 | fixed | 1 |
| L1-8 | 1 | opus | 테스트 | 채택 | fixed | 1 |
| L1-9 | 1 | opus | 테스트 | 채택 | fixed | 1 |
| L1-10 | 1 | opus | 테스트 | 채택 | fixed | 1 |
| L1-11 | 1 | opus | 테스트 | 채택 | fixed | 1 |
| L1-12 | 1 | opus+감사 A1 | 테스트 | 채택 | fixed | 1 |
| L1-13 | 1 | 반증 스텁 | 테스트 | 채택 | fixed | 1 |
| L1-14 | 1 | 반증 스텁 | 테스트 | 채택 | fixed | 1 |
| L1-15 | 1 | opus | 테스트 | 채택 | fixed | 1 |
| Q-1 | 1 | opus OQ3 | 시계 큰 이동 뒤 정산 실행 시간 | open question | 구현 단계 측정 | — |
| Q-2 | 1 | opus | 등급 소급 없음 미판정 | 기각(낮음) | — | — |
| L2-1 | 2 | codex | FulfillmentTest.kt:73 reason 원문 | 채택 | fixed | 2 |
| L2-2 | 2 | codex+opus OQ | §8.1 기동 전환 경로 | 기각(하네스 한계 — 계약 테스트는 앱 재기동 불가; equiv recov도 적립률 미판정, 감사 A2) → 미검증 한계로 NEXT 기록 | — | — |
| L2-3 | 2 | codex | SettlementTest.kt:87 CLAWBACK 경계 | 채택 | fixed | 2 |
| L2-4 | 2 | codex(+감사 A4) | SettlementTest.kt:70 한 주 원자성 | 채택 | fixed | 2 |
| L2-5 | 2 | codex OQ | FakePg 취소 DECLINE | 채택 | fixed | 2 |
| L2-6 | 2 | opus | 분쟁 인정 다항목·배송비(E6·E7) | 채택 | fixed | 2 |
| L2-A3 | 2 | 감사 | 배치 금액 불일치 건너뛰기 | 채택 | fixed | 2 |
| L3-1 | 3 | codex(+감사 A2) | Stage2.kt:212-214, SerialRaceTest.kt:33·65 PG 요청 수 | 채택(거짓 실패) | fixed | 3 |
| L3-2 | 3 | opus | SettlementTest.kt INCLUDED 뒤 분쟁 HELD | 채택 | fixed | 3 |
| L3-3 | 3 | opus | GradeRecalcTest.kt 판매분 기준 금액 | 채택 | fixed | 3 |
| L3-4 | 3 | opus | GradeRecalcTest.kt D ≠ 1일 실행 | 채택 | fixed | 3 |
| L3-5 | 3 | opus | 소급 확정 balance.updated_at | 채택 | fixed | 3 |
| L3-x1 | 3 | codex | §8.1 기동 전환 | 기각(L2-2 반복, NEXT) | — | — |
| Q3-1~4 | 3 | codex·opus OQ | 정산 행 없음 분기·PG 취소 타임아웃·KDoc·영구 상태 | 미검증 기록(KDoc만 수정) | — | — |
| 2026-10-21 | 리뷰 회수: codex 7건(F1~F7, 금액 12행 전부 맞음) · Opus 8건+OQ 7(금액 전부 맞음, [verify] 주장 전부 맞음). 메인 종합 D1~D10·Q1~Q6 | 원문·종합표 `scratchpad/rv.LVul/results/` |
| 2026-10-21 | 사용자 결정(D4 카드 취소 중 앱 사망): "계약 밖 + 최소 안전장치" | |
| 2026-10-21 | codex 감사(1회 성공): A1 D3 같은 날 배치 전후 승인 차이·GOLD 무이력 · A2 D2 "스냅샷" 표현 부정확(BRONZE 유지 사용자는 D 행 없음) · A3 D4(b)의 "키에 사유" 안이 고아 뒤 이중 카드 취소를 만든다 | A1 채택: 한계 명시 + grade-recalc 기준일 00:00 실행 전제 + 비BRONZE 테스트 사용자는 이력 행 함께. A2 채택: 문구 정정. A3 채택: 키는 항목당 하나 유지, 안전장치는 금액 불일치 409만(사용자 결정 "계약 밖 + 최소 안전장치"의 범위 안 — 세부 교체를 보고) |
| 2026-10-21 | ADR-0004 수정(메인): D1 확정 min(현재, 배송+7일) · D2 D 행 있으면 건너뜀 · D3 등급 3단계 규칙+한계 · D4 계약 밖+금액 확인 · D5 한 주 = 한 트랜잭션 · D6 판매자 행 잠금·사용자 id 순 · D7 만료 묶음 복구분만 EXPIRE · D8 items 순서·reason 규칙 · D9/§11.8 늦은 정산 · D10 [verify] 범위 · Q1 P2 1,000P→164 · Q2~Q6 문서화 | post-fix 재점검(codex)으로 |
| 2026-10-21 | post-fix 재점검(codex, packet base `e792eea`, OUT `scratchpad/rv.bicV`): D1~D10·P2 전부 해소, **신규 0** | ADR-0004 리뷰 종료(설계 문서 = 설계 선검증+듀얼 1패스 상한). 사용자 합의 요청 |
| 2026-10-21 | 사용자: ADR-0004 "채택" | 상태 채택. §11.1 → ADR-0003 §9의 8, §11.2~9 → ADR-0002 §9.7 개정 항목으로 옮김(명세 §4 방식). task 02 완료 → task 03(테스트 기반) |
| 2026-10-21 11:17~11:28 | task 03 워커(Opus): `contract2Test` 태스크(태그 contract2 + 패키지 필터, contractTest에서 contract2 패키지 제외), FakePg `POST /cancellations`·`UNAVAILABLE`·`GET /_control/cancellations/{key}`, `@Contract2Test`, FakePgCancellationTest 8개. run.sh 수정 없음(두 번째 인자로 태스크) | run.sh god·layered exit 0(infra 26 = 18+8, contract 117/117, skip 0, verify 0). contract2Test 0개 → exit 1(No tests found — 0건 통과 위장 없음, 메인: 유지). 메인 확인: build.gradle diff 열람·금지영역 diff 0 → 코드 커밋. 보조 제어 엔드포인트는 ADR §7.2에 1줄 추가. 동시에 다른 세션의 무관한 부하 프로세스(`/tmp/tmp.k7g7Rydr7o/scripts/run.sh`)가 돌고 있었음 — 결과에 영향 없음 |
| 2026-10-21 11:29~11:54 | task 04 워커(Opus, blind — god/layered 미열람): contract2 테스트 10클래스 65개 + Stage2.kt·Db2.kt·PgCancel.kt(2,152줄). 기존 파일 변경 0 | 두 앱 65/65 red, skip 0, verify 0. 원인: 셋업 2단계 엔드포인트 404 41 · 셋업 배치 400 4 · 대상 엔드포인트 404 12 · 대상 배치 400 5 · §8.1 미반영 3(의도 — ADR-0003 §9.8 미구현) · 기타 0. 1단계 god 117/117·infra 26. 메인 확인: 65개 @Test·기존 테스트·금지영역 무변경 → 커밋 |
| 2026-10-21 | 워커 OQ 3건 메인 판정(ADR 문구 보강, 결정 아님 — 판정 순서·형식): 상태 위반이 기한 위반보다 먼저 · date 열 `YYYY-MM-DD` · 문자열은 받은 값 그대로 저장 | ADR §2.1 3줄. 테스트 쪽 단언 보강은 task 05 리뷰에서 |
| 2026-10-21 | task 05 착수: 리뷰 루프 1(테스트 코드, 높음) packet base `a8a3556`, OUT `scratchpad/rv.XA7s`, 미러 `mirror.Wh7P`, diff 14파일 +2,155, 스캔 0 · 반증 스텁 워커 병렬(응답만 그럴듯하게 주는 프록시 → 0개 통과여야 함) | Opus ∥ codex + 반증 워커 |
| 2026-10-21 11:57~12:03 | 반증 스텁 워커(Opus): 2단계 라우트에 DB 무변경·그럴듯한 성공 응답 프록시(god 앞) → 65 중 **63 실패·2 통과** | 통과 2: 분쟁 응답 ISO 형식(BuyerActionTest:234-242, DB 미확인)·배치 이름 스모크(BatchTest:133-138). 오류 기대 12건은 200에 실패. 뒷부분 단언 강도는 미판정(첫 실패에서 멈춤) |
| 2026-10-21 | 리뷰 루프 1 회수: codex 5건 · Opus 9건+OQ 5 · 스텁 2건 → 메인 종합 L1-1~15·Q-1~3 (전부 거짓 통과/거짓 실패로 분류 — 기대값 불일치 0, 금액 전부 맞음) | codex 감사 A1: L1-12 수정안이 "remaining 0이면 REVOKE 없음"을 검증 못 함 → 별도 사례 추가로 채택 |
| 2026-10-21 | ADR §12에 E5 행 추가(기존 §7.1 규칙으로 유도한 예시, 규칙 변경 아님) | 수정 워커(task 04 작성자, blind 유지)에 L1 수정 브리핑 |
| 2026-10-21 12:10~12:23 | L1 수정 워커(Opus, blind): 15건 반영, 65→73개(+9, 스모크 −1), +271 −17줄 | 두 앱 73/73 red, skip 0, 기타 0, verify 0 · 1단계 117/117·infra 26 · 반증 스텁 재실행 **통과 0**. 메인 확인: 73개 @Test·1단계·main 소스·금지영역 무변경 → 커밋. 루프 2 리뷰로 |
| 2026-10-21 | 리뷰 루프 2 착수: packet base `a8a3556`(누적) + loopfix diff(6a5a69c..86dc2fa), OUT `scratchpad/rv.WYYN`, 미러 `mirror.U26p`, 스캔 0 | Opus ∥ codex |
| 2026-10-21 | 리뷰 루프 2 회수: codex 4건+OQ 1 · Opus 1건+OQ 5(수정이 만든 결함 0) → 메인 종합 L2-1~6·Q2-1~4. codex 감사: A1 E7 기대값(감사는 E6 뒤로 읽음 — 원안은 "E1 뒤"라 값은 맞음, 문구를 명시로 정정) · A2 L2-2 기각 근거의 "recov가 다룸" 주장 오류 → 미검증 한계로 정정 · A3 Q2-2 기각 부당 → 채택(배치 금액 불일치) · A4 L2-4 주입 순서 → "실제 두 번째 INSERT"에서 실패 | ADR §12 E6·E7, 가짜 PG 취소 DECLINE 추가. 수정 워커에 L2 브리핑 |
| 2026-10-21 12:33~12:44 | L2 수정 워커(Opus, blind): 7건, 73→80개, infra 26→27(FakePg DECLINE), +178 −2 | 두 앱 80/80 red, skip 0, 기타 0, verify 0 · 1단계 117/117·infra 27 · 반증 스텁 통과 0, 원자성 트리거·함수 잔여 0. 메인 확인: 80개 @Test·1단계 무변경·FakePg diff(+3 −1) → 커밋. 루프 3(마지막)으로 |
| 2026-10-21 | 리뷰 루프 3(마지막) 착수: packet base `a8a3556` 누적 + loopfix(04b6558..2eed09e), OUT `scratchpad/rv.OYfN`, 미러 `mirror.zxS1`, 스캔 0 | Opus ∥ codex |
| 2026-10-21 | 리뷰 루프 3(상한) 회수: codex 2건+OQ 1(F2는 L2-2 반복) · Opus 4건+OQ 6(수정이 만든 결함 0) → 메인 종합 L3-1~5(L3-1은 **거짓 실패** — 같은 키 재시도 구현을 떨어뜨림)·L3-x1 기각·Q3-1~4 | 상한 도달·신규 채택 5 → 사용자 판단 요청 |
| 2026-10-21 | 사용자: "5건 수정 + 타깃 재점검 1회 후 종료" | codex 감사: A1 Q3-2의 "UNAVAILABLE와 같은 분기" 근거 부정확(타임아웃은 PG 기록 뒤 무응답) → 별도 미검증 경로로 기록 · A2 L3-1의 "모든 요청 키" 검증 불가 → 가짜 PG 전체 취소 조회 추가로 채택 · L3-x1 기각 타당(NEXT 유지) |
| 2026-10-21 13:03~13:14 | L3 수정 워커(Opus, blind): 6건, 80→82개, infra 27→28(가짜 PG 전체 취소 조회), +129 −14 | 두 앱 82/82 red, skip 0, 기타 0, verify 0 · 1단계 117/117·infra 28 · 반증 스텁 통과 0·트리거 잔여 0. 커밋 839f11f |
| 2026-10-21 | 타깃 재점검(codex, packet base `6202db8`, OUT `scratchpad/rv.lW1u`): L3-1~5 해소, **신규 0** | 리뷰 종료(사용자 결정대로). 마감 |
| 2026-10-21 | 사이클 마감: NEXT(N1 god 2단계·N2 layered 2단계·미검증 경로) · README 진행 행·2단계 실행 명령 · measurement-log 1행 · CS 이슈 아카이브는 study-note 보류 방침으로 0건 | 완료 요약: Opus 워커 초안(실파일·git show 복사) → 메인이 compare_layers류 대신 assertCardCancel·트리거 스니펫을 실파일과 대조 후 반영 |

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

- §8.1 적립률의 **기동 전환** 경로: 계약 테스트는 실행 중 앱 재기동 불가, equiv recov는 적립액을 비교하지 않음 → 어느 도구도 검증하지 않음(L2-2·L3-x1). god 2단계 구현 task에서 다룬다(NEXT).
- §7.2 카드 취소 **응답 없음(PG 기록 뒤 지연)**: 가짜 PG 취소에 지연 제어 없음(감사 A1).
- §10.1 **정산 행이 하나도 없을 때** 분기: 시드에 정산 행이 있어 만들 수 없음.
- 2단계 구현이 없어 테스트 기대값 자체는 실행으로 확인하지 못함(이번 끝의 정의 범위 밖 — 첫 green 시점에 테스트 결함이 드러날 수 있음).

## 완료 요약

**결과:** ADR-0004(HTTP 행위 계약 2단계)를 설계 선검증 + 듀얼 1패스(post-fix 재점검 신규 0)를 거쳐 사용자 "채택"으로 합의했다. 2단계 계약 테스트 10클래스 82개를 blind 워커가 작성했고, 두 앱에서 82/82 red, skip 0, 실패 원인 "기타" 0, verify 0이다. 테스트 리뷰는 듀얼 루프 3회(상한) + 타깃 재점검(신규 0)으로 끝냈다.

### 게이트 (명세 §5 — log 실측)

| 항목 | 결과 | 근거(log) |
|---|---|---|
| 2단계 분리 실행 태스크 | `contract2Test`(태그 contract2 + 패키지 필터, contractTest에서 contract2 제외). 0개면 exit 1("No tests found" — 0건 통과 위장 없음) | task 03 |
| 1단계 `run.sh god`·`layered` | exit 0, contract 117/117, infra 26, skip 0, verify 0(task 03, 두 앱). 이후 루프마다 1단계 117/117, infra 27→28 | task 03·L1~L3 수정 행 |
| 2단계 전부 red | 65/65 → 73/73 → 80/80 → **82/82**(두 앱), skip 0, 기타 0, verify 0 | task 04·L1~L3 수정 행 |
| red 원인 분포(82개) | 셋업 2단계 엔드포인트 404 55 · 셋업 배치 400 4 · 대상 엔드포인트 404 15 · 대상 배치 400 5 · §8.1 미반영 3(의도 — ADR-0003 §9의 8 미구현) · 기타 0 | L3 수정 워커 보고 |
| 테스트 기반 자체 테스트 | FakePgCancellationTest 8개 → infra 18→26 green, DECLINE 27, 전체 취소 조회 28 | task 03·L2·L3 |
| 반증 스텁(DB 무변경·그럴듯한 성공 응답 프록시) | 1차: 65 중 **63 실패·2 통과**(BuyerActionTest:234-242 ISO 형식·BatchTest:133-138 배치 이름 스모크). L1·L2·L3 수정 뒤 재실행마다 **통과 0**, 원자성 트리거·함수 잔여 0 | 반증 워커·L1~L3 수정 행 |
| 금액 대조 | ADR 리뷰: codex 금액 12행 전부 맞음, Opus 금액·[verify] 주장 전부 맞음. 테스트 루프 1~3: 기대값 불일치 0 | 리뷰 회수 행 |
| 리뷰 | ADR: 설계 선검증 + 듀얼 1패스(D1~D10·Q1~Q6), post-fix 재점검 신규 0. 테스트: 듀얼 루프 3회(L1 15건, L2 6건 채택 + L2-2 기각, L3 5건), 타깃 재점검(codex) 신규 0. 루프 2·3 "수정이 만든 결함 0" | ledger·타임라인 |

### 규모

| 파일 | 줄 수(`wc -l`) | 변경 |
|---|---|---|
| `docs/adr/0004-http-api-stage2.md` | 268 | 신규 |
| `docs/adr/0002-domain-and-database.md` | 345 | +12 (§9.7) |
| `docs/adr/0003-http-api-stage1.md` | 186 | +2 (§9의 8) |
| `behavior-tests/build.gradle.kts` | 173 | +26 −8 |
| `behavior-tests/src/main/.../pg/FakePg.kt` | 263 | +74 −2 |
| `contract2/` 11파일 (RefundTest 453·SettlementTest 394·Stage2 305·BuyerActionTest 264·FulfillmentTest 194·GradeRecalcTest 170·ItemApiTest 159·PointGradeTest 159·BatchTest 145·PenaltyTest 106·SerialRaceTest 82) | 2,431 | 신규, 82 tests |
| `infra/FakePgCancellationTest.kt` | 187 | 신규, 11 tests |
| `support/Db2.kt` 132 · `PgCancel.kt` 68 · `FakePgExtension.kt` +7 | | |
| **합계** `e9f55ee..c6eb3ad` | | 22파일 +3,407 −10 |

- 테스트 수: task 04 65 → L1 73(+9, 스모크 −1) → L2 80 → L3 **82**. infra 18 → 26 → 27 → **28**.
- 커밋: 025e3b2 명세 · e792eea ADR 초안 · ef74d67 ADR 리뷰 반영 · b7c58c6 재점검 · 1a9e174 채택·개정 항목 · 2a4887e 기반(task 03) · a8a3556 · 6a5a69c 테스트 65 · e873538 · 7ea0d84 E5 · 04b6558 L1 · 86dc2fa · 8e4c610 E6·E7 · 5e56539 L2 · 2eed09e · 6202db8 · 839f11f L3 · c6eb3ad (+ 마감 docs).

### 핵심 스니펫

**1) ADR-0004 §12 E 행 — 테스트 기대값의 정본** (`docs/adr/0004-http-api-stage2.md`)

```markdown
| E1 | E: 30,000 · 20,000, 수수료 1,000 · 1,000(최소), 배송비 3,000, 총액 55,000, 포인트 5,000, 카드 50,000, 카드 몫 30,910 · 19,090, 포인트 몫 3,090 · 1,910, `s_c` 2,727 · `s_p` 273 | 1번 항목 구매자 취소 | 28,183 | 2,817 | 0 | PARTIALLY_CANCELLED |
| E2 | 〃 | 이어서 2번 항목 구매자 취소(마지막 — 배송비 실림) | 21,817 | 2,183 | 3,000 | CANCELLED (Σ카드 50,000 · Σ포인트 5,000) |
| E3 | E 새 주문 | 1번 항목 검수 불합격(판매자 귀책) | 30,910 | 3,090 | 3,000 | PARTIALLY_CANCELLED, 패널티 3,000 |
| E4 | 〃(E3 뒤) | 2번 항목 구매자 취소(마지막, 배송비는 이미 환불) | 19,090 | 1,910 | 0 | CANCELLED |
| E5 | E 새 주문, E1 뒤 | 2번 항목 검수 불합격(마지막 — 판매자 귀책도 남은 배송비를 싣는다) | 21,817 | 2,183 | 3,000 | CANCELLED, 패널티 2,000 |
| E6 | E 새 주문 | 1번 항목 확정 → 분쟁 인정(첫 항목 — 배송비 부분 포함 전액) | 30,910 | 3,090 | 3,000 | PARTIALLY_CANCELLED |
| E7 | E 새 주문, 1번 항목 구매자 취소(E1) 뒤 | 2번 항목 확정 → 분쟁 인정(마지막 — 남은 배송비를 싣는다) | 21,817 | 2,183 | 3,000 | CANCELLED |
```

E5는 루프 1, E6·E7은 루프 2에서 기존 §7.1 규칙으로 유도해 추가했다(규칙 변경 아님).

**2) `assertCardCancel` — 루프 3 L3-1 거짓 실패 수정** (`behavior-tests/src/test/kotlin/com/ccle/behavior/contract2/Stage2.kt`)

before (`git show 6a5a69c:…/Stage2.kt`):

```kotlin
/** 카드 취소: PG가 받은 요청 1건(키 cancel-<id>, 승인 키 = 주문 멱등 키, 금액) + pg_transactions CANCEL 1행. */
fun assertCardCancel(p: Purchase, itemId: Long, amount: Long, at: Instant) {
    val pg = PgCancel.info(itemId)
    assertEquals(1, pg.requests, "PG 취소 요청 수: $pg")
    assertEquals(listOf(amount), pg.amounts, "PG 취소 amount = 카드 환불: $pg")
    assertEquals(listOf(p.key), pg.approvalKeys, "PG 취소 approvalKey = 주문 멱등 키: $pg")
    assertEquals("APPROVED", pg.status, "PG 취소 기록: $pg")
```

after (HEAD):

```kotlin
fun assertCardCancel(p: Purchase, itemId: Long, amount: Long, at: Instant) {
    val pg = PgCancel.info(itemId)
    assertTrue(pg.amounts.isNotEmpty() && pg.amounts.all { it == amount }, "PG 취소 요청 금액 = 카드 환불: $pg")
    assertTrue(pg.approvalKeys.all { it == p.key }, "PG 취소 approvalKey = 주문 멱등 키: $pg")
    assertEquals("APPROVED", pg.status, "PG 취소 기록: $pg")
    assertEquals(amount, pg.amount, "PG 기록 금액: $pg")
    assertEquals(p.key, pg.approvalKey, "PG 기록 승인 키: $pg")
    val refundedKeys = Db2.itemsOfOrder(p.orderId).filter { i -> Db2.refunds(i.id).any { it.cardAmount > 0 } }.map { PgCancel.key(it.id) }.toSet()
    val recorded = PgCancel.keysOfApproval(p.key).filter { it.recordStatus != null }.map { it.key }.toSet()
    assertEquals(refundedKeys, recorded, "그 주문 승인 키로 기록된 취소 키 = 카드 환불된 항목 키")
```

요청 수 단언을 없애고(같은 키 재시도 허용 — ADR §7.2·§9.2), 감사 A2대로 승인 키 기준 전체 취소 키를 대조해 다른 키의 추가 취소는 계속 잡는다.

**3) 정산 원자성 — 실제 두 번째 INSERT에서 터지는 트리거(L2-4, 감사 A4)** (`behavior-tests/src/test/kotlin/com/ccle/behavior/contract2/SettlementTest.kt`)

```kotlin
        val name = "bt_atomic_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        val sellers = "${a.seller}, ${b.seller}"
        // 새 행이 A·B 중 하나이고 같은 period_start로 다른 쪽 행이 이미 보이면 실패: 실제 두 번째 INSERT에서 터진다(순서 무관)
        Db.update(
            "CREATE FUNCTION $name() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN " +
                "IF NEW.seller_id IN ($sellers) AND EXISTS (SELECT 1 FROM settlements s WHERE s.seller_id IN ($sellers) " +
                "AND s.seller_id <> NEW.seller_id AND s.period_start = NEW.period_start) THEN " +
                "RAISE EXCEPTION '$name: second seller insert'; END IF; RETURN NEW; END $$",
        )
        try {
            Db.update("CREATE TRIGGER $name BEFORE INSERT ON settlements FOR EACH ROW EXECUTE FUNCTION $name()")
            Flow.batch("weekly-settlement")                           // 응답 상태는 ADR이 정하지 않았다
            for (seller in listOf(a.seller, b.seller)) {
                assertTrue(Db2.settlementsOfSeller(seller).none { it.periodStart == week }, "그 주는 통째로 커밋되지 않아야 한다: 판매자 $seller")
            }
        } finally {
            Db.update("DROP TRIGGER IF EXISTS $name ON settlements")
            Db.update("DROP FUNCTION IF EXISTS $name()")
        }
        assertEquals(0L, Db.long("SELECT count(*) FROM pg_trigger WHERE tgname = ?", name), "트리거 제거")
```

### 교훈

- **반증 스텁이 정적 리뷰가 못 본 거짓 통과 2건을 잡았다.** DB를 바꾸지 않는 성공 응답 프록시에 65개 중 2개가 통과했다(L1-13·L1-14). 이후 루프마다 다시 돌려 통과 0을 유지했다.
- **리뷰마다 새로 채택할 지적이 나왔다** — ADR 1패스 16, 테스트 루프 1 15, 루프 2 6(+기각 1), 루프 3 5. 수정이 만든 결함은 루프 2·3 모두 0이라 churn이 아니라 새 빈틈이었고, 상한에서 끊고 남은 것은 미검증으로 기록했다.
- **codex 감사가 메인 판단 오류를 바로잡았다** — L2-2 기각 근거("equiv recov가 다룸")가 틀림 → 미검증 한계로 정정, 기각했던 Q2-2(배치 금액 불일치)를 채택으로, ADR 단계 A3("키에 사유"가 이중 카드 취소를 만듦), 루프 3 A1(타임아웃 ≠ 503).
- **고친 거짓 실패가 공용 도우미에 남아 있었다.** 루프 1(`04b6558`)은 RefundTest 한 곳의 요청 수 단언만 지웠고, 공용 `assertCardCancel`·SerialRaceTest의 같은 단언이 루프 3 L3-1로 다시 나왔다 — 결함을 고칠 때 같은 단언 패턴을 전수 grep한다(근거: git diff 04b6558·839f11f).
- **0건 통과 금지를 지켰다** — contract2Test가 0개면 exit 1(task 03).

### 남은 한계 (log "생략한 검증" 정리)

- §8.1 적립률 **기동 전환** 경로 — 어느 도구도 검증하지 않음(L2-2·L3-x1). god 2단계 구현 task에서 다룬다(NEXT N1).
- §7.2 카드 취소 **응답 없음(PG 기록 뒤 지연)** — 가짜 PG 취소에 지연 제어 없음(감사 A1). UNAVAILABLE와 다른 경로.
- §10.1 **정산 행이 하나도 없을 때** 분기 — 시드에 정산 행이 있어 만들 수 없음.
- **테스트 기대값의 실행 확인** — 2단계 구현이 없어 못 함(이번 끝의 정의 범위 밖). 첫 green 시점에 테스트 결함이 드러날 수 있다.
- open question: 시계를 크게 옮긴 뒤 정산 실행 시간(Q-1)은 구현 단계에서 측정. Q3-1~4는 미검증 기록(KDoc만 수정).
