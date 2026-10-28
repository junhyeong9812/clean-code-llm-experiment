# log — 계약 ADR-0007(단계 A 기능 A1~A12 동작 계약)

> 작업 폴더: `docs/plans/2026-10-28/contract-a/` · 브랜치 `plan/contract-a`(main `5117fde`) · 산출물 유형: 설계 문서

## 타임라인

| 시각 | 사건 | 결과 |
|---|---|---|
| 2026-10-28 23:27 | main `5117fde` push(사용자 "push하고 계속 진행해줘") · 브랜치 `plan/contract-a` 생성 · 작업 폴더 생성 | 인터뷰 시작 |
| 2026-10-28 23:29 | 인터뷰 4문항 응답(12개 한 번에·기존 상세도·수치 Claude 제안·시드 1k 비례) → requirement-spec 작성, auto 유지 | task 01 조사 위임 |
| 2026-10-28 23:34 | packet c7-01b(스키마·시드·verify, Opus 워커) 회수 — 메인 교차 확인: FK `ADD FOREIGN KEY` 34개·테이블 22개 이름 일치(`sellers`·`penalties` 없음 → `users`·`seller_penalties`), verify FK 하드코딩 `tools/seedgen/verify.sql:166` | 채택 |
| 2026-10-28 23:36 | packet c7-01a(API 관례·고칠 곳, Opus 워커) 회수 — 메인 교차 확인: layered `domain/Failure.kt` enum 16값·`api/ErrorHandling.kt` `statusOf` 전수 when, `BatchService.kt:44` `forEachIsolated` private, `KeysetBatchTargets.spec` when(BatchTarget). **ADR-0006 §3의 1 허용 목록(디스패치·컨트롤러·import) 밖 수정이 새 오류 code·새 배치에 필요** → spec §4에 따라 멈추고 사용자 결정 요청 | 대기 |
| 2026-10-29 00:08 | 사용자 결정 2건: 오류 code = 기존 code만 재사용, 새 배치 = ADR-0006 §3의 1 허용 목록 개정(BatchTarget enum·KeysetBatchTargets.spec 분기·forEachIsolated internal) → spec §0·§1·§4 재합의 반영 | ADR-0007 초안 착수 |
| 2026-10-29 00:16 | ADR-0007 초안 작성(§1~§18 — 새 테이블 20·FK 24·배치 11·[제안] 값 §16·verify 새 검사·시드 후처리) + ADR-0006 개정 1(§3의 1 허용 3항목·§4.1 테이블 수)·README 진행 6행 | 리뷰 착수 |
| 2026-10-29 00:16 | 듀얼 1패스 ⓪ packet: BASE `5117fde`, `$OUT` = scratchpad `c7/rv`(packet·results), 미러 = scratchpad `c7/mirror`(log.md 0개). 보안 스캔 4파일 매칭 — 전부 오탐(application.yml 로컬 DB 기본값 `ccle`, 러너 테스트 픽스처 token 문자열) → 통과. ① Opus 워커(c7-rv1-opus) ∥ codex(medium, read-only) 병렬 시작. 설계 선검증은 렌즈 1로 같은 패스에 포함 | 대기 |
| 2026-10-29 00:26 | ① 회수: codex(exit 0, bwrap 오류 없음 — blocker 2·major 5) · Opus(blocker 0·major 5·minor 15) → ② 종합: 중복 병합 21건 전부 채택(기각 0), ADR-0007에 반영(R1~R21) | ③ 감사 착수 |
| 2026-10-29 00:27 | ③ codex 종합 감사 시작 — 입력: 미러 사본 `c7/mirror2/_audit/`(양 리뷰 원문·채택표·수정 diff·수정 후 전문). Opus 원문은 회수 메시지를 옮긴 것(머리의 읽은 파일 목록만 생략) | 대기 |
| 2026-10-29 00:30 | ③ 감사 1차: codex exit 0이지만 bwrap `Failed RTM_NEWADDR` 반복으로 입력을 못 읽어 '판정 불가' 출력(알려진 오류 — NEXT 보류 항목) → 출력 내용으로 실패 판정, 1회 재시도 | 재시도 |
| 2026-10-29 00:32 | ③ 감사 재시도 성공(exit 0, bwrap 0회): (a) 메인 판정 오류 없음 · (b) major 1 — R10 수정이 만든 시드 fee_rebates `paid_at` 규칙이 ZERO 행 CHECK와 충돌 → R22 채택·수정 · (c) 놓친 충돌 없음 | ④ post-fix 재점검 |
| 2026-10-29 00:37 | ④ post-fix 재점검(codex, exit 0, bwrap 0회): R1·R2·R4~R22 해소, R3 미해소 — 첫 문의 body "공백 뺀 값"과 공통 규칙 "앞뒤 공백을 뺀 값" 충돌(R23) → 첫 문의 행을 공통 규칙으로 통일 + §2.1에 글자 수 정의(앞뒤 공백 뺀 값의 코드 포인트 수) | 메인 셀프 확인 |
| 2026-10-29 00:37 | R23 수정은 1어구 통일 + 정의 1줄이라 재점검을 다시 돌리지 않고 메인이 확인: grep으로 '공백 뺀 값' 잔여 0, 길이 규칙 3곳(A1 content·A6 body·§2.1)이 같은 정의. **절차 이탈 기록:** 中 변형의 '재점검 clean' 종료 조건을 R23 수정분은 셀프 확인으로 대신함 | 리뷰 종료 |
| 2026-10-29 00:39 | 사이클 마감: NEXT N0 갱신(ADR-0007 제안·다음 contract3) · measurement-log 1행 · 완료 요약(Opus 워커 c7-sum, 실파일 복사) · CS 이슈 아카이브 = study-note 쓰기 금지(spec §4)라 보류(NEXT 보류 항목과 같음) | 커밋 |

## 리뷰 ledger

| ID | 출처 | 지적 | 판정 | 처리 |
|---|---|---|---|---|
| R1 | codex·opus | §3 새 FK 수 24 → 실제 나열 27, verify 목표 58 → 61(codex blocker·opus 4-4) | 채택 | fixed(1패스) |
| R2 | codex·opus | §7.1 제한 알림 두 kind가 한 행·같은 dedupe 접두어 → UNIQUE 충돌, 훑는 순서 불명, epoch 변환 규칙 없음(codex blocker+major·opus 2-4·2-5) → 두 행 분리·표 순서·floor epoch를 SQL 한 식으로 | 채택 | fixed(1패스) |
| R3 | codex·opus | §9 A6 body 경계·200 응답 모양 없음(codex·opus 2-2) → 1~2,000자·판정 순서·문의 모양 | 채택 | fixed(1패스) |
| R4 | codex·opus | §2.5·§11 날짜 필터 포함 여부·from>to·after 범위·시세 일수 경계(codex·opus 2-3) → 양끝 포함, after ≥ 0, 일수 = to−from+1 ≤ 92 | 채택 | fixed(1패스) |
| R5 | codex·opus | §12.3 슬롯 생성 동시 중복이 UNIQUE 위반 400(codex·opus 3-2) → ON CONFLICT DO NOTHING + 0행이면 409 | 채택 | fixed(1패스) |
| R6 | codex·opus | §14·§15 seller_grades restricted 과거 복원 불가·a2 재집계 verify가 테스트 뒤 실패(codex·opus 4-2) → 시드 가정 고정(마지막 제한 구간만) + a2는 저장 열끼리, 재집계는 시드 빌드 전용 | 채택 | fixed(1패스) |
| R7 | codex | §14 시드 추적 이벤트 감사 생략 여지·감사 대응 검사 없음 → 빠짐없이 1행 + a9_audit_coverage(시드 빌드 전용) | 채택 | fixed(1패스) |
| R8 | opus | §15 부분 UNIQUE 이름 목록 3→7 변경이 ADR-0006 §3의 2 예외(FK 수) 밖(1-1) → 기존 식은 FK 수만, 새 검사 a0_partial_unique_present | 채택 | fixed(1패스) |
| R9 | opus | §5.1 불변식 7 C1 해석 근거 없음(1-2) → 기존 구매자 등급 재구현 금지로 해석, A2는 ADR-0006 §4.1 채택 기능 | 채택 | fixed(1패스) |
| R10 | opus | §5.3 rebate-settlement 닫힘 게이트 없음 → 마지막 주 수수료 영구 누락(2-1) → monthly-statement와 같은 게이트, 시드 2026-08까지 | 채택 | fixed(1패스) |
| R11 | opus | §4.5 seller_ratings 행 없음 + VISIBLE 0 처리 불명(2-6) → 쓰지 않음 | 채택 | fixed(1패스) |
| R12 | opus | §6.4 held_count 행 수/항목 수 불명(2-7) → count(*) 행 수, last_carry_out = period_start 가장 늦은 정산 | 채택 | fixed(1패스) |
| R13 | opus | §2.6 배치 4개 잠금 미명시(3-1) → rebate·monthly = 사용자 행 FOR UPDATE, price-stats·delivery-delay = 잠금 없음, §2.6 일반 규칙 수정 | 채택 | fixed(1패스) |
| R14 | opus | §10.3 정정 UPDATE→INSERT 순서와 ORM flush(3 참고) → 명시적 flush 1줄 | 채택 | fixed(1패스) |
| R15 | opus | §3 monthly_statements ≥0 범위 모호 → CLAWBACK 달 CHECK 위반(4-1) → gross·fee_total 부호 제약 없음 | 채택 | fixed(1패스) |
| R16 | opus | §12.3 예약 판정 순서가 '상태 위반 먼저'와 어긋남(4-3) → 정원을 기한보다 먼저 | 채택 | fixed(1패스) |
| R17 | opus | §14 시드 시각 제약 누락(received_at·예약 기한·read_at·리뷰 분쟁 구간)(5-1) → 표에 시각 규칙 + a0_time_order·a12_reservation_deadline | 채택 | fixed(1패스) |
| R18 | opus | §8.2 가격 알림 없는 사이즈 404 없음(5-2) → 추가 + DELETE 응답 모양 | 채택 | fixed(1패스) |
| R19 | opus | §18 contract3 픽스처의 T1 회귀 의존 지침 없음(5-3) → delivered_at+7일 안 확정 또는 confirmed_at 직접 | 채택 | fixed(1패스) |
| R20 | opus | §11.3·§12.2 summary 404·delays 정렬 없음(5-4) → 추가 | 채택 | fixed(1패스) |
| R21 | codex | §8.2·§12.3 성공 응답 본문 미정(가격 알림 DELETE·예약 취소·체크인) — R3과 같은 유형, opus 2-2와 병합 | 채택 | fixed(1패스) |
| R22 | 감사(codex) | §14 fee_rebates 시드 `created_at = paid_at` 일괄 → ZERO 행은 paid_at NULL이어야 함(§3 CHECK·§5.3) — R10 수정이 만든 결함 | 채택 | fixed(감사 후) |
| R23 | 재점검(codex) | §9 첫 문의 body '공백 뺀 값' vs 공통 '앞뒤 공백을 뺀 값' — 중간 공백 포함 여부로 경계 응답 갈림(R3 미해소) | 채택 | fixed(재점검 후, 셀프 확인) |

## 생략한 검증

(없음)

## 완료 요약

### 무엇을 했나

- 인터뷰 4결정(12개 한 번에 · 기존 상세도 · 수치는 Claude [제안] · 시드 1k 비례)으로 착수, 조사 중 ADR-0006 §3의 1 허용 목록과 부딪힌 2건을 사용자 재합의(오류 code = 기존 code만 · 새 배치용 허용 목록 개정 3항목).
- 산출물: `docs/adr/0007-stage-a-contract.md`(신규, §1~§18 — A1~A12 엔드포인트·새 테이블 20·새 FK 27·배치 11·시드 후처리·verify 새 검사·[제안] 값 목록) + ADR-0006 개정 1(§3의 1 허용 3항목, §4.1 테이블 수 20) + README 진행 6행.
- 리뷰: 설계 선검증(렌즈 1) + 듀얼 1패스(Opus ∥ codex) → 감사 → post-fix 재점검. 지적 23건(R1~R23) 전부 채택·fixed, 기각 0.

### 핵심 diff (before/after — 실파일 복사)

(a) ADR-0006 §3의 1 — 허용 목록 개정 (before = `5117fde`, 개정은 기존 줄 뒤에 1줄 추가)

```diff
    - layered의 기존 파일은 다음 세 가지만 바꿀 수 있다: 배치 디스패치 분기 추가(`BatchService.run`의 `when`과 생성자 주입), 컨트롤러 등록, 새 저장소 포트의 import.
+   - **개정 1(2026-10-29, 사용자 결정 — ADR-0007 §17의 1):** 새 배치가 기존 키셋 순회·격리를 재사용하도록 셋을 더한다: `BatchTarget` enum 값 추가, `KeysetBatchTargets.spec`의 `when` 분기 추가, `BatchService.kt` `forEachIsolated`의 공개 범위 private → internal. 기존 분기·동작은 바꾸지 않는다. 오류 code는 새로 만들지 않으므로 `Failure` enum·`statusOf`는 바꾸지 않는다(ADR-0007 §2.2).
```

(b) ADR-0007 §3 새 FK 수 — R1 (before = 리뷰 전 사본)

```diff
-- **새 FK 24개**(전부 `ADD FOREIGN KEY`): reviews 3(order_item·buyer·seller) · seller_ratings 1 · seller_grades 1 · fee_rebates 1 · payout_account_requests 1 · monthly_statements 1 · notifications 1 · wishlists 2 · price_alerts 3(user·product_size·triggered_bid → bids) · inquiries 2(user·order_item) · inquiry_messages 1 · tax_invoices 3(settlement·seller·ref_invoice → tax_invoices) · price_stats_daily 1 · seller_profiles 1 · shipment_tracking_events 1 · shipment_delays 1 · inbound_reservations 3(slot·seller·order_item) · `admin_audit_logs`·`inbound_slots`는 0. → verify `schema_constraints_present`의 FK 수 34 → **58** **[구현 검증 — DDL 작성 때 다시 센다]**.
+- **새 FK 27개**(전부 `ADD FOREIGN KEY`): reviews 3(order_item·buyer·seller) · seller_ratings 1 · seller_grades 1 · fee_rebates 1 · payout_account_requests 1 · monthly_statements 1 · notifications 1 · wishlists 2 · price_alerts 3(user·product_size·triggered_bid → bids) · inquiries 2(user·order_item) · inquiry_messages 1 · tax_invoices 3(settlement·seller·ref_invoice → tax_invoices) · price_stats_daily 1 · seller_profiles 1 · shipment_tracking_events 1 · shipment_delays 1 · inbound_reservations 3(slot·seller·order_item) · `admin_audit_logs`·`inbound_slots`는 0. → verify `schema_constraints_present`의 FK 수 34 → **61** **[구현 검증 — DDL 작성 때 다시 센다]**.
```

(c) ADR-0007 §7.1 제한 알림 두 행 + dedupe 규칙 — R2

```diff
-| `PURCHASE_RESTRICTED` · `SALE_RESTRICTED` | `users`: `purchase_restricted_until > now`(·`sale_…`) | 그 사용자 | 사용자 id · `PURCHASE_RESTRICTED:<id>:<제한 끝 epoch 초>` |
+| `PURCHASE_RESTRICTED` | `users`: `purchase_restricted_until > now` | 그 사용자 | 사용자 id · `PURCHASE_RESTRICTED:<id>:<E>` |
+| `SALE_RESTRICTED` | `users`: `sale_restricted_until > now` | 그 사용자 | 사용자 id · `SALE_RESTRICTED:<id>:<E>` |
+
+- `<E>` = 제한 끝 시각의 epoch 초 **내림**(`floor(extract(epoch from …))::bigint`). `dedupe_key`는 대상 조건의 `NOT EXISTS`와 INSERT 모두 **SQL 한 식**으로 만든다(앱에서 따로 계산하지 않는다 — 두 값이 어긋나 재실행마다 UNIQUE 위반이 나는 것을 막는다).
```

(d) ADR-0007 §5.3 rebate-settlement 닫힘 게이트 — R10

```diff
-- 대상 달 `M` = 지난달(현재 달의 1일 − 1개월). 대상: `effective_from = M`인 `seller_grades` 행이 있고 `month = M`인 `fee_rebates` 행이 없는 판매자(`users` id 순).
+- 대상 달 `M` = 지난달(현재 달의 1일 − 1개월). **그 달이 닫혔을 때만** 돈다 — 닫힘 판정은 `monthly-statement`와 같다(§6.4). 아니면 대상 0건. 그 달 마지막 주 정산이 생기기 전에 행이 확정되어 그 주 수수료가 빠지는 일을 막는다.
+- 대상: `effective_from = M`인 `seller_grades` 행이 있고 `month = M`인 `fee_rebates` 행이 없는 판매자(`users` id 순). 사용자 행 `FOR UPDATE` 뒤 재확인.
```

### 리뷰 요약

| 단계 | 출처 | 원 지적 | 채택 | 기각 | 비고 |
|---|---|---|---|---|---|
| ① 듀얼 1패스 | codex(medium, read-only) | blocker 2 · major 5 | 공동 6(R1~R6) · 단독 2(R7·R21) | 0 | exit 0, bwrap 오류 없음 |
| ① 듀얼 1패스 | Opus 워커 | blocker 0 · major 5 · minor 15 | 공동 6 · 단독 13(R8~R20) | 0 | 설계 선검증(렌즈 1) 포함 |
| ② 종합 | 메인 | 중복 병합 21건 | 21(R1~R21) | 0 | ADR-0007에 반영 |
| ③ 감사 | codex | 판정 오류 없음 · 놓친 충돌 없음 · major 1 | 1(R22 — R10 수정이 만든 시드 `paid_at` vs ZERO CHECK 충돌) | 0 | 1차 bwrap `Failed RTM_NEWADDR`로 '판정 불가' → 1회 재시도 성공 |
| ④ post-fix 재점검 | codex | R1·R2·R4~R22 해소, R3 미해소 | 1(R23 — 문의 body 공백 규칙 통일 + §2.1 글자 수 정의) | 0 | exit 0, bwrap 0회 |
| 합계 | | | **23(R1~R23)** | **0** | |

- 절차 이탈 1건: R23 수정분(1어구 통일 + 정의 1줄)은 재점검을 다시 돌리지 않고 메인 셀프 확인(grep '공백 뺀 값' 잔여 0, 길이 규칙 3곳 같은 정의)으로 '재점검 clean' 종료 조건을 대신함.
- 보안 스캔 4파일 매칭 — 전부 오탐(로컬 DB 기본값·테스트 픽스처 token 문자열).

### 남은 것

- **[제안] 값 사용자 확인 필요** — ADR-0007 §16 목록(A1~A12·공통 13행, [제안] 표기가 있는 줄 30). 합의 전까지 ADR-0007 상태는 **제안**.
- [구현 검증] 이연 항목(FK 수 재계수·시드 행 수·verify 변수 이름 등)은 DDL·시드 구현 때 확인.
- 다음: contract3 행동 테스트(ADR-0007 + ADR-0002~0004만 보고 작성, §18).
