# log — 1·2단계 open question 정리

## 타임라인

| 시각 | 사건 | 결과 |
|---|---|---|
| 2026-10-24 16:00 | 인터뷰 2회(8문항) | 결정만 기록 · 오류 문구 고정 · 배치 건별 격리 · 늦은 승인 유지 · 소항목 전부 현행 확정 · 500k 별도 측정 · stakes 높음 · auto · ff+push |
| 2026-10-24 16:07 | 사전 확인(메인 직접) | 경고 경계: god `Marketplace.kt:1366-1371` 엄격 > vs 생성기 `seedgen.py:522-525` 제한 시 목록 초기화 — 같은 배치 같은 시각 연속 경고에서 갈림. ADR-0003 §7 표시는 `[기본값 — 생성기와 같은 해석]`. 브랜치 `docs/open-questions`, 디스크 51G |
| 2026-10-24 16:12 | 명세 합의("합의, auto 진행") → set-state spec-approved·mode auto | SPEC=1·MODE=auto |
| 2026-10-24 16:25 | ADR-0003 §12(1~7)·ADR-0004 §15(1~4) + 포인터 문장 3곳 작성(python 치환, 각 문자열 1회 매칭 assert) | diff +28/−5, docs/adr 2파일 |
| 2026-10-24 16:30 | 리뷰 packet(base `e8937d8`) OUT=`scratchpad/oq.MofX`, 미러=`scratchpad/oqm.Zyqb`. 보안 스캔: 키 패턴 0, password는 로컬 기본값 `ccle` 3곳(기존과 같음) | Opus 워커 ∥ codex 시작 |
| 2026-10-24 16:42 | codex 리뷰 회수(try 1 정상) | finding 4, 확인 8항목, OQ 1 |
| 2026-10-24 16:44 | Opus 리뷰 회수(26 tool uses, 5.4분) | finding 4(F1~F4), 확인 11항목, OQ 3 |
| 2026-10-24 16:50 | 메인 종합: 채택 7(R1~R7)·기각 0 → ADR 수정 적용 | **절차 편차:** 종합 감사 전에 수정을 적용했다(감사는 수정 전 packet + 채택표로 수행, 수정은 post-fix 재점검이 본다) |
| 2026-10-24 16:52 | codex 종합 감사 시작(입력: packet + 양 리뷰 원문 + 채택표) | |
| 2026-10-24 17:00 | codex 감사 회수(try 1 정상) | 판정 오류 3(R3 사용자 결정 처리·R6 표기·R2 적용 범위)·누락 1(A1). 메인: A1 채택·수정 / R2 이미 하위 항목으로 기동 실패 구분 — 변경 없음 / R6 표기 지적 수용, 수정 유지 / R3 유지 — 사용자 결정은 '현행 확정'이고 문장은 현행 동작 서술(spec §2) |
| 2026-10-24 17:12 | codex post-fix 재점검 회수(try 1 정상, 미러 `scratchpad/oqr.WEFe`) | 신규 finding 0, R1~R7·A1 해소 확인 → 리뷰 종료(설계 문서 상한 = 듀얼 1패스) |
| 2026-10-24 17:15 | NEXT 갱신: N1 제거 → 새 N1(변경 2건 구현)·N2(500k 측정), equiv·layered 남긴 것은 보류·분석 메모로 | |

## 항목 대장

| 질문(출처) | 결정 | ADR 위치 | 코드 근거(god / layered — 리뷰 확인) |
|---|---|---|---|
| 계약 밖 서버 오류 400 + message 노출(god L1-x1, layered 1단계 관측) | 변경 — 400·code 유지, message 고정 | ADR-0003 §12의 1 | `web/ErrorHandling.kt:28-31` / `api/ErrorHandling.kt:41-44` |
| 배치 대상 1건 실패 시 전체 중단(god 1·2단계) | 변경 — 건별 격리(1단계 배치·2단계 배치), weekly-settlement는 격리 안 함, 기동 전환은 현행 | ADR-0003 §12의 2, ADR-0004 §15의 1 | `Marketplace.kt:1267,1292-1308,1576` / `BatchService.kt:36-37,300`, `KeysetBatchTargets.kt:49-67`, `StartupRecovery.kt:10`(양쪽) |
| PAYING인데 APPROVE 행 있으면 로그 없이 건너뜀 | 현행 확정 | ADR-0003 §12의 3 | `:388-390,1713-1716` / `PaymentService.kt:128-130`, `StalePaymentRecovery.kt:68-73` |
| 요청 시각을 잠금 전에 읽음 | 현행 확정 | ADR-0003 §12의 4 | `:335,1270` 외 / `PaymentService.kt:51`, `BatchService.kt:54` 외 |
| 교차 구매자 같은 키 경합 Hibernate ERROR 로그 | 현행 확정(INSERT까지 간 경합만) | ADR-0003 §12의 5 | `:336-346,501-513` / `PaymentService.kt:52-58,97-103` |
| 구매 경고 계수 경계(ADR 엄격 > vs 생성기) | 현행 확정(ADR 식) | ADR-0003 §12의 6 | `:1366-1372` / `User.kt:60-62`, 생성기 `seedgen.py:522-525` |
| 포인트 예약 묶음 전체 적재(god L3-x2) | 현행 확정, 규모는 N2 | ADR-0003 §12의 7 | `:1104-1111` / `JpaPointRepositories.kt:33-39` |
| 늦은 승인 주문의 발송 기한(god 2단계 R2-OQ2) | 현행 확정 | ADR-0004 §15의 2 | `:1423-1427,1442-1450` / `BatchService.kt:171-172,196-203` |
| PG 취소 성공 뒤 커밋 예외(R2-OQ3) | 현행 확정(계약 밖과 같은 종류, 커밋 불명 두 갈래) | ADR-0004 §15의 3 | `:982-1028,1055-1073` / `ItemRefunder.kt:45-62` |
| 검수 reason 공백 집합 | 현행 확정(Kotlin isBlank) | ADR-0004 §15의 4 | `:787` / `Inspection.kt:16` |
| 500k 규모(배치·정산 스캔) | 결정 안 함 → NEXT N2 | — | — |
| layered 구조 차이(재합의 1) | 결정 대상 아님 → 과제 러너 분석 메모 | — | — |
| equiv 2단계가 남긴 것(<10원 배치 경로·금액 불일치 자연 경로) | 계약 밖 유지, N1 구현 뒤 다시 봄 | ADR-0004 §15의 1 셋째 하위 | — |

## 리뷰 ledger

| id | loop | source | 근거 | disposition | status | fixed_in |
|---|---|---|---|---|---|---|
| R1 | 1 | codex 1 · opus F1 | ADR-0004 §15의 1 ↔ §5:104·§10.1:196, verify.sql:621-625, god Marketplace.kt:1576, layered BatchService.kt:300 — 정산 앞 auto-confirm을 격리하면 소급 확정이 마감된 주에 들어감 | 채택 — weekly-settlement는 격리하지 않음(auto-confirm 1건 실패 시 그 실행은 정산 안 함, 보수안) | fixed | 1 |
| R2 | 1 | codex 2 · opus F2 | ADR-0003 §12의 2 ↔ §9의 7:172, StartupRecovery.kt:10(양 앱) — 기동 전환 격리 시 PAYING을 남긴 채 요청 수신, 이전 동작은 기동 실패 | 채택 — 기동 전환은 격리 대상에서 빼고 현행 확정. '1의 응답'을 '400 UNEXPECTED_ERROR'로(opus OQ2 흡수) | fixed | 1 |
| R3 | 1 | codex 3 | ADR-0003 §12의 5 ↔ god Marketplace.kt:501-513, layered PaymentService.kt:97-103 — 조회 단계 409는 제약 위반 로그 없음 | 채택 — INSERT까지 간 경합에서만 ERROR 로그 | fixed | 1 |
| R4 | 1 | codex 4 | ADR-0004 §15의 3 ↔ §9.2:186-188 — 커밋 예외 ≠ 롤백 확정 | 채택 — 미커밋/커밋됨 두 갈래 서술 | fixed | 1 |
| R5 | 1 | opus F3 | ADR-0003 §12의 3 ↔ god :388·:1713, layered PaymentService.kt:128·StalePaymentRecovery.kt:68 — 잠금 대상은 구매자 users 행 | 채택 | fixed | 1 |
| R6 | 1 | opus F4 | ADR-0003 §12의 2 인용 ↔ ADR-0004 §9.2:187 — 인용 문장이 ADR-0003 §7에 없음 | 채택(근거 정확성). 감사: 표기 정합이라 잘못 채택 — 수정은 의미 무변경이라 유지 | fixed | 1 |
| A1 | 1 | 감사 | ADR-0004 §15의 1 셋째 하위 ↔ §7.2:130-131, god Marketplace.kt:1067-1071, layered PaymentGateway.kt:59-73 — 재시도 문장이 금액 불일치(마지막 환불 변동) 경로를 덮지 않음 | 채택 — PG_UNAVAILABLE 건너뜀 + §7.2 계약 밖 유지 명시 | fixed | 1 |
| R7 | 1 | opus OQ1 | ADR-0004 §15의 1 셋째 하위 ↔ §6:111 — 계약 밖(<10원) 처리를 사실상 정함 | 채택(spec §4) — 계약 밖 유지, 귀결 설명만 | fixed | 1 |

## 생략한 검증

- 없음. 문서만 바뀌어 테스트·스모크 대상 없음(§4 ①② — 실행물 무변경, `git diff --stat`으로 docs 외 0 확인). blind 테스트 워커는 설계 문서라 비대상. 리뷰 절차 편차 1건(감사 전 수정 적용 — 타임라인 16:50).

## 완료 요약

**정한 것** — NEXT N1의 질문을 모두 닫았다(항목 대장 13행). 앱·테스트·도구 무변경, `docs/adr` 2파일 + NEXT만 바뀜.
- 현행 확정 9: ADR-0003 §12의 3~7(PAYING+APPROVE 무로그 건너뜀·요청 시각·교차 키 경합 로그·경고 경계 엄격 `>`·포인트 묶음 전체 적재), §12의 2 하위(기동 전환 비격리), ADR-0004 §15의 2~4(늦은 승인 발송 기한·취소 뒤 커밋 예외·reason 공백 = `isBlank`).
- 변경 2(**[변경 — 구현 전]**, NEXT N1): 계약 밖 오류 `message` 고정(ADR-0003 §12의 1), 배치 건별 격리(ADR-0003 §12의 2, ADR-0004 §15의 1 — `weekly-settlement`는 격리 안 함).
- 별도 측정: 500k 규모 → NEXT N2. layered 구조 차이는 과제 러너 분석 메모로, equiv 2단계 잔여는 보류로 옮김.

**핵심 diff** (`git diff e8937d8`에서 복사)

ADR-0004 §5 포인터:
```diff
-- 대상 1건의 처리는 트랜잭션 하나다. 중간에 실패하면 그 대상은 바뀌지 않고, 다시 돌리면 남은 대상을 처리한다(§9.2).
+- 대상 1건의 처리는 트랜잭션 하나다. 중간에 실패하면 그 대상은 바뀌지 않고, 다시 돌리면 남은 대상을 처리한다(§9.2). 실패 뒤 배치를 계속하는지는 §15의 1.
```

ADR-0003 §2 포인터:
```diff
-- 어떤 요청도 5xx로 끝나지 않는다(가짜 PG 장애 포함 — §5.1의 UNKNOWN으로 흡수).
+- 어떤 요청도 5xx로 끝나지 않는다(가짜 PG 장애 포함 — §5.1의 UNKNOWN으로 흡수). 계약 밖 서버 오류의 응답은 §12의 1.
```

ADR-0004 §15의 1 weekly-settlement 하위(신규, 리뷰 R1 반영):
```diff
+   - **`weekly-settlement`는 격리하지 않는다.** 앞서 수행하는 `auto-confirm`에서 1건이라도 실패하면 그 실행은 정산하지 않는다 — 실패한 항목이 DELIVERED로 남은 채 마감이 앞으로 가면, 나중에 소급 확정된 `confirmed_at`(`delivered_at + 7일`)이 이미 마감된 주에 들어가 §5의 "소급된 확정 시각이 이미 마감된 주에 들어가는 일은 없다"가 깨진다. 한 주의 정산이 실패하면 그 주를 롤백하고 뒤 주를 정산하지 않는다. 뒤 주가 커밋되면 "마지막 마감"이 실패한 주를 앞질러 그 주가 정산되지 않은 채 남기 때문이다(§10.1). 이때 요청의 응답(오류로 끝낼지, 앞 주까지의 `processed`로 200을 낼지)은 구현 작업에서 정한다.
```

**리뷰** — 설계 문서 상한(듀얼 1패스). codex finding 4 ∥ Opus finding 4 + OQ 3 → 채택 7(R1~R7)·기각 0. codex 종합 감사: 판정 오류 지적 3(R2·R3 유지, R6 표기 지적 수용·수정 유지)·누락 1(A1 채택·수정). post-fix 재점검: 신규 0, R1~R7·A1 해소 → 종료. 절차 편차 1건: 종합 감사 전에 수정을 적용함(감사는 수정 전 packet + 채택표로, 수정 내용은 재점검이 확인 — 16:50).

**남은 것** — NEXT N1(변경 2건 구현: god·layered·행동 테스트·equiv, 고정 문구·실패 수 응답·weekly-settlement 중단 응답은 착수 인터뷰에서), N2(500k 이미지 재빌드 후 배치 규모 측정, 포인트 묶음 적재 포함).
