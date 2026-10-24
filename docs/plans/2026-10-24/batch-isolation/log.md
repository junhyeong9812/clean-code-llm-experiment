# log — 계약 변경 2건 구현 (오류 문구 고정 · 배치 건별 격리)

## 타임라인

| 시각 | 사건 | 결과 |
|---|---|---|
| 2026-10-24 17:40 | 인터뷰 2회(7문항) | 임시 DB 트리거 · `failed` 필드 · 정산 중단은 오류 유지 · "예상하지 못한 서버 오류" · equiv `iso` 추가 · stakes 높음 · auto · ff+push |
| 2026-10-24 17:50 | 사전 읽기(메인) | 공유 지점: god `forEachTarget`(:1292, 기동 전환 :1710도 사용) / layered `KeysetBatchTargets.forEach`(:49). 응답: layered `AdminController.kt:42` `mapOf("processed")`. 기존 테스트는 `processed` 정수만 확인(`Flow.kt:101`, `Stage2.kt:84`). 브랜치 `feat/batch-isolation` |
| 2026-10-24 18:05 | 사용자 합의("합의, auto 진행") → set-state spec-approved·mode auto | SPEC=1·MODE=auto |
| 2026-10-24 18:10 | 설계 확정(메인): 키셋 순회(god `forEachTarget`, layered `KeysetBatchTargets.forEach`)는 무변경 — 격리 배치 7개만 handle을 예외 포착 래퍼로 감싼다(기동 전환·정산 안 auto-confirm은 래퍼 밖). PG_UNAVAILABLE은 WARN·그 밖은 ERROR, 둘 다 `failed`. load-bearing 가정 2(트리거가 실행 중 앱에서 예외로 올라옴)는 기존 equiv `errors`·`fail` 시나리오(INSERT/UPDATE 트리거 → 400)와 `SettlementTest.kt:343` 원자성 테스트가 이미 실증 — 가정 1(롤백 뒤 같은 순회 계속)만 스모크 대상 | — |
| 2026-10-24 18:15 | ADR-0003 §7·§12의 1·2, ADR-0004 §5·§15의 1 갱신(구현 전 표시 제거, `failed` 정의·고정 문구·정산 400 확정) — blind 워커 입력 | 미커밋 |
| 2026-10-24 18:20 | 워커 2개 발주(Opus): 설계 선검증(읽기 전용) ∥ blind 테스트(ADR·spec·behavior-tests만, god/·layered/ 열람 금지) | 진행 중 |
| 2026-10-24 18:30 | god 구현(`forEachTarget`에 `isolatedAs` 파라미터 — 기본 null = 전파, 기동 전환·정산 안 auto-confirm은 null) + layered 구현(포트·infra 무변경, service `BatchTargets.forEachIsolated` 확장 함수 + `AutoConfirmBatch.runStoppingOnFailure`) + 두 앱 ErrorHandling 고정 문구 + AdminController `mapOf("processed","failed")`. 계획(god도 순회 무변경 + 래퍼)과 달리 god는 순회 함수에 옵션을 넣음 — 동작 같음(F9) | 두 앱 compileKotlin rc 0 |
| 2026-10-24 18:35 | 설계 선검증 회수(Opus): blocker 0. F1 I1 둘째 문장 과잉 서술 → 명세 보정, blind 워커에 전달 / F2 layered 실패 수는 실행 지역 카운터(이미 그렇게 구현) / F4 트리거 예외는 flush 또는 커밋 → 가정 2 문구 보정·예외 클래스 미단언 전달 / F8 인프라 장애(교착·연결)도 대상 실패로 셈 — 키셋 조회는 try 밖이라 전파, ADR 문구와 일치(기록만) / F3·F5·F6·F7·F11 확인 | 설계 채택 |
| 2026-10-24 18:40 | 스모크(실행 중 앱 + 임시 트리거, scratchpad `smoke.sh`, 1k 시드, 시계 2026-12-15): bid-expiry 대상 1006건 중 최소 id 21544에 BEFORE UPDATE 트리거 → god·layered 모두 `{"processed":1005,"failed":1}` 200, 21544 ACTIVE·EXPIRED 이벤트 0, ERROR 로그 `bid-expiry: 대상 21544 실패`. 트리거 제거 뒤 `{"processed":1,"failed":0}`·EXPIRED. bids INSERT 트리거 → POST /bids `400 {"code":"UNEXPECTED_ERROR","message":"예상하지 못한 서버 오류"}`(원인은 ERROR 로그에만). weekly-settlement 200 `failed:0` | load-bearing 가정 1·2 실증 |
| 2026-10-24 18:55 | blind 테스트 회수(Opus): 새 `support/FailTrigger.kt`, `contract/BatchIsolationTest.kt`(고정 문구·bid-expiry·payment-due·pg-inquiry·응답 형식), `contract2/BatchIsolationTest.kt`(shipment-deadline 예외·PG 불가, auto-confirm 단독, point-expiry, grade-recalc, weekly-settlement 앞 auto-confirm 실패 400·마감 미전진·첫 실패에서 멈춤, 응답 형식) + `Flow.runBatch`·`S2.runBatch`가 정수 `failed`도 요구(강화). testClasses rc 0. 경계 이탈 1건: 워커가 `git diff --stat`을 돌려 god/ 파일 이름·줄 수를 봄(내용 미열람, 설계 미사용) — blind 계약 위반은 아니라고 판단. 미검: 대상 조회 실패 400(SELECT 트리거 불가), 한 주 정산 실패 뒤 뒤 주 미정산, 로그(행동 테스트 밖 — 스모크·equiv log-counts가 대신), 기동 전환 비격리(재기동 필요) | 회수 |
| 2026-10-24 19:00 | equiv `scenarios/iso.sh` 작성(bid-expiry·point-expiry·auto-confirm 단독 격리 + 재실행, weekly-settlement 앞 auto-confirm 실패 400·정산 0 → 재실행, log-counts) + README 12종. `need`는 격리 여부와 무관한 주입 경로만 확인(변이 jar가 exit 2가 아니라 1로 끝나게) | — |
| 2026-10-24 19:40 | 행동 테스트 전체(`tools/behavior/run.sh`): god test rc 0(infra 28·contract 122)·contract2Test rc 0(89), layered 같은 수치 rc 0, verify 4회 모두 위반 0(76 검사). 새 테스트 12개 포함(이전 117·82) | green |
| 2026-10-24 19:45 | 그린 위장 점검 발주: main(4276972) worktree(scratchpad `redwt`)에 새 테스트만 복사해 god contractTest·contract2Test 실행 — 백그라운드 | 진행 중 |
| 2026-10-24 19:50 | 리뷰 루프 1 packet: base 4276972, OUT=`scratchpad/tmp.HlluaH6WA5`, mirror=`scratchpad/tmp.tDmPikLQGY`(ledger 0, 시크릿 스캔 0). untracked 5(테스트 3·iso.sh·spec). Opus ∥ codex 발주 | 진행 중 |
| 2026-10-24 20:30 | 그린 위장 점검 회수: main god에서 새 테스트 contract 5/5·contract2 7/7 실패(나머지 실패 contract 17·contract2 30은 `runBatch`가 `failed` 정수를 요구하게 강화한 탓) | 새 테스트는 변경을 실제로 검증 |
| 2026-10-24 20:35 | 리뷰 루프 1 회수: Opus F1~F3 + OQ4, codex F1·F2. 종합 → 아래 ledger. 수정: R1 두 앱 `fromStatus`에 요청 메서드·경로 전달(`/error`는 ERROR_REQUEST_URI) / R3 shipment-deadline 두 테스트에 기저 failed(A·B 기한 전 실행) + 1 단언, 테스트 주석 "실패 없을 때와 같다" → "ADR대로" / OQ1 → spec I4를 ADR 문구(PG 건너뜀 WARN)에 맞춤 / R2 기각 → README iso 행에 판정 한계 명시 | compile rc 0 |
| 2026-10-24 20:50 | codex 종합 감사(루프 1, `results/audit-r1.md`): R1·R3·OQ1 동의, R2 전면 기각 근거 부족(A1) → iso에 응답-로그 일치 `need` 추가 | — |
| 2026-10-24 21:05 | equiv `iso` 단독 1회차: 같음이나 bid-expiry `failed 0` — 픽스처 입찰 `validDays 1`이 400(계약은 30·60·90)이라 트리거가 시드 행에 걸림(`need`가 가짜 통과). 수정: validDays 30 + 입찰 생성 가드, bid-expiry 단계를 11-01로 이동 | 시나리오 결함 수정 |
| 2026-10-24 21:10 | equiv `iso` 단독 2회차: 4층 같음(79초) — point-expiry `{7,1}`·auto-confirm `{83,1}`·weekly-settlement 400 고정 문구 → 재실행 `{68,0}`·bid-expiry `{526,1}` → `{1,0}`, log-counts 격리 ERROR 3·계약 밖 오류 1·failed 합 3·400 1 | 같음 |
| 2026-10-24 21:15 | 변이: layered `forEachIsolated` catch 첫 줄에 `throw e`(격리 끔) jar(scratchpad `layered-mut.jar`) → `LAYERED_JAR=… run.sh iso` rc 1, 응답·새행·SQL·DB문장 4층 모두 차이. 소스 원복(변이 표식 0)·bootJar 재빌드 | 변이 검출 |
| 2026-10-24 21:20 | 최종 검증 발주(백그라운드, 순차): 행동 테스트 god·layered × test·contract2Test → equiv all × 2 / 리뷰 루프 2 packet OUT=`scratchpad/tmp.fDwchtnJc9`, mirror=`scratchpad/tmp.dCKmOY5Lbw`(루프 1 미러 삭제), Opus ∥ codex | 진행 중 |
| 2026-10-24 21:40 | codex 루프 2(`results/codex-r2.md`, 2회 시도 중 2회차 채택): finding 0, OQ `iso.sh:94` `bc` 의존 → awk로 교체(equiv 최종 실행 시작 전, 합산 단위 확인 3) | — |
| 2026-10-24 21:50 | Opus 루프 2 회수: F1(low) + OQ 4. 채택 R4·R5 → iso 일치 검사 전용 die 문구 + grep 0건 방어, README 정정. 신규 채택 있음 → 루프 3 | — |
| 2026-10-24 22:00 | codex 종합 감사(루프 2, `results/audit-r2.md`): R4·R5·OQ8 동의, A2(OQ6 근거 오류 → ADR 문장 추가)·A3(iso 일치식 조건 명시) 채택·수정 | — |
| 2026-10-24 22:05 | 리뷰 루프 3 packet OUT=`scratchpad/tmp.DCbHZUf9lO`, mirror=`scratchpad/tmp.DXMyGDx6aG`(루프 2 미러 삭제, ledger 0·시크릿 0), Opus ∥ codex | 진행 중 |
| 2026-10-24 22:20 | 최종 행동 테스트(루프 1 수정 반영본): god test·contract2Test, layered test·contract2Test 모두 rc 0, verify 4회 위반 0 / codex 루프 3(`results/codex-r3.md`): finding 0·OQ 0, 렌즈 8개 applicable verified | — |
| 2026-10-24 22:35 | Opus 루프 3 회수: F1·F2(low) 신규 채택 → 상한 루프에서 종료 조건(신규 채택 0) 미충족. equiv 최종 실행(수정 전 코드) 중단(TaskStop), 남은 `ccle-equiv-db` 컨테이너(이미지 1k-s42, lib.sh EQ_DB) 확인 후 삭제 | review 종료 조건 미충족 |
| 2026-10-24 22:45 | R6·R7 수정 → bootJar·iso 단독 4층 같음(80초, log-counts 3 1 3 1, 새 ERROR 문구 확인) → codex post-fix 재점검(`results/recheck-r3.md`): R6·R7 해소, R8(설명문 "이미 롤백") → 수정. 변이 jar 최종 코드로 재빌드(소스 원복·변이 표식 0) | — |
| 2026-10-24 22:50 | 최종 검증 재발주(백그라운드, scratchpad `final2`): 행동 테스트 4회 → equiv all × 2 → 변이 iso | 진행 중 |
| 2026-10-25 00:35 | 최종 검증 회수(scratchpad `final2`, 최종 코드): 행동 테스트 god·layered 각 test rc 0(infra 28·contract 122)·contract2Test rc 0(89), verify 4회 위반 0 / equiv `all` 1회차 rc 0 12개 같음(946초)·2회차 rc 0 12개 같음(952초) / 변이 iso(최종 코드 기준 격리 끈 layered jar) rc 1, 응답·새행·SQL·DB문장 4층 차이. 정리: red-check worktree 제거, 변이 jar 삭제, `ccle-equiv-*`·`ccle-bt-*`·`ccle-smoke-*` 컨테이너 0, 앱 프로세스 0, 디스크 50G | 검증 완료 |
| 2026-10-25 00:40 | 사이클 마감: NEXT 갱신(N1 완료 이력으로, 배치 격리 한계 보류 항목 추가) · measurement-log 1행 · CS 이슈 아카이브는 study-note 쓰기 금지(spec §4)로 보류(NEXT 보류 목록 아홉 작업) | — |

## 리뷰 ledger

| id | loop | source | 근거 | 내용 | disposition | status | fixed_in |
|---|---|---|---|---|---|---|---|
| R1 | 1 | opus F1·codex F1 | god `web/ErrorHandling.kt:40,78`, layered `api/ErrorHandling.kt:53,95` | `/error`·Spring 5xx 변환 경로의 ERROR 로그에 요청 메서드·경로 없음 — spec §1·ADR-0003 §12의 1 "원인(요청 메서드·경로·예외)은 로그에만"과 불일치. 고정 문구로 응답에서 원인이 사라져 로그가 유일한 단서 | 채택 | fixed | 1 |
| R2 | 1 | opus F3·codex F2 | `tools/equiv/scenarios/iso.sh` `need`들 | 두 앱이 함께 이전 동작으로 회귀하면 iso가 "같음"으로 통과 | 기각(계약 단언) → 감사 A1로 로그 부분만 채택 | fixed | 1 |
| A1 | 1 | 감사(codex) | `iso.sh:88-89` | R2 전면 기각 근거 부족: 로그 건수는 기록·비교만 해서 두 앱이 함께 ERROR 로그를 잃으면 통과, 행동 테스트도 로그를 보지 않음 — I4 공백 | 채택 — 격리 여부와 무관한 일치 조건(응답 failed 합 = 격리 ERROR 수, 400 UNEXPECTED_ERROR 수 ≤ 계약 밖 오류 로그 수)을 `need`로 → 변이 jar는 여전히 차이(1) | fixed | 1 |
| R3 | 1 | opus F2 | `behavior-tests/.../contract2/BatchIsolationTest.kt:28-33,69`, `RefundTest.kt:260,386` | RefundTest가 남긴 금액 불일치 PAID 항목이 매 shipment-deadline에서 PG_UNAVAILABLE로 failed에 잡혀 `failed ≥ 1`이 늘 참 — 일반 예외 경로의 failed 계수 회귀를 못 잡음(그린 위장) | 채택 | fixed | 1 |
| OQ1 | 1 | opus | spec I4 vs ADR-0003 §12의 2 | PG 건너뜀은 failed에 세지만 WARN — I4 "ERROR 로그"와 어긋남 | open question → spec 문구 정정(코드 무변경) | fixed | 1 |
| OQ2 | 1 | opus | god `Marketplace.kt` 격리 catch, layered `BatchService.kt:44-60` | 인프라 장애에서 페이지(≤500)만큼 대상마다 ERROR 스택 — 운영 상한 필요 여부 | 범위 밖(ADR 계약대로) → NEXT 관찰 항목 | — | — |
| OQ3 | 1 | opus | `iso.sh` | payment-due·pg-inquiry·grade-recalc·shipment-deadline 트리거 격리의 SQL 동치는 equiv 근거 없음 | open question → 한계로 기록: 격리 래퍼는 SQL을 내지 않고 실패 전 문장은 성공 경로 앞부분(equiv 기존 시나리오가 비교) — 설계 선검증 F7 | — | — |
| OQ4 | 1 | opus | `fromStatus` 400..499 | INVALID_REQUEST 본문에 Spring·Jackson 문구 | 범위 밖(계약 안 오류, §12의 1 대상 아님) | — | — |
| R4 | 2 | opus F1 | `tools/equiv/scenarios/iso.sh:96`, `:26`, `README.md:44` | 응답-로그 일치 검사를 `need`로 묶어 I4 위반이 "경로를 지나지 않음" 진단으로 exit 2 — 조작자가 주입·하네스 문제로 오인, README 설명과도 불일치 | 채택 | fixed | 2 |
| R5 | 2 | main-synthesis(opus OQ3) | `iso.sh:94` | `set -euo pipefail`(lib.sh:31)에서 200 배치 응답이 0건이면 grep 일치 0으로 메시지 없이 종료 | 채택(같은 줄, grep 0건 방어 `or true`) | fixed | 2 |
| OQ5 | 2 | codex | `iso.sh:94` | `bc` 의존 | open question → awk로 교체(코드 정리) | fixed | 2 |
| OQ6 | 2 | opus | god `Marketplace.kt:1323-1333`, layered `BatchService.kt:47-57` | 커밋 결과 불명 예외(연결 끊김)를 failed로 셈 — 실제 커밋됐을 수 있음 | 범위 밖: ADR-0004 §15의 3과 같은 계약 밖 종류(커밋됐으면 다음 실행에서 대상 조건이 빼므로 재처리 없음 — I2 무해) | — | — |
| A2 | 2 | 감사(codex) | 같은 곳, ADR-0003 §12의 2 | OQ6 기각 근거 부족: "I2 무해"는 틀린 근거(커밋된 대상은 실패가 아닌데 failed에 듦), ADR이 이 경우의 failed 의미를 정하지 않음 | 채택 — ADR-0003 §12의 2에 "커밋 여부 불명도 failed에 센다(§15의 3과 같은 계약 밖, 커밋됐으면 다음 실행 대상에서 빠짐)" 명시. 코드 무변경 | fixed | 2 |
| A3 | 2 | 감사(codex) | `iso.sh:93-96` | A1 근거 과도: failed에는 WARN(PG 건너뜀)도 들어가므로 "failed 합 = ERROR 수"는 격리 여부와 무관한 일반 불변식이 아님 — 이 시나리오 입력 한정 | 채택 — iso 주석·README에 "카드 취소 대상이 없어 성립" 조건 명시 | fixed | 2 |
| OQ7 | 2 | opus | god `:1327`, layered `:50` | WARN(PG 건너뜀) 로그를 검증하는 경로 없음 | 한계 기록: 문구는 이전 WARN 자리 이동(코드 리뷰 2회 + 설계 선검증 F7 확인), 행동 테스트는 failed·DB를 단언 | — | — |
| OQ8 | 2 | opus | `contract2/BatchIsolationTest.kt:30,68,88` | 기저 측정 뒤 3일 사이 새 제3자 실패가 생기면 `failed ≥ 기저+1`이 A 없이 성립 가능 | 한계 기록: 응답은 대상별 내역이 없어 더 좁힐 수 없음(ADR 응답 형식), A 상태 단언이 I1 본체 | — | — |

| R6 | 3 | opus F1 | `tools/equiv/scenarios/iso.sh:50,73` | point-expiry·bid-expiry `need`(A 불변)가 A가 대상이 아니어도 성립 — 픽스처가 틀어지면 격리 경로를 안 타고 "같음"(21:05 validDays 사건과 같은 종류) | 채택 — 재실행 뒤 A 처리(`remaining 0`·`EXPIRED`) `need` 추가 | fixed | 3 |
| R7 | 3 | opus F2 | god `Marketplace.kt:1329`, layered `BatchService.kt:54` | 커밋 여부 불명 대상에도 ERROR 문구가 "롤백" 단정 — ADR-0003 §12의 2(A2)와 불일치, 운영 관측 오도 | 채택 — 두 앱 같은 문구 "실패(반영 안 됨 — 커밋 단계 예외면 커밋 여부 불명, 다음 실행이 다시 판정)", iso grep 맞춤 | fixed | 3 |
| R8 | 3 | post-fix 재점검(codex) | ADR-0003 §12의 2 앞부분, layered `BatchService.kt:40`·god `Marketplace.kt:1297` KDoc | R7 수정 뒤에도 설명문이 "이미 롤백" 단정 | 채택 — ADR `failed` 정의에 "(또는 커밋 여부를 알 수 없는)", 두 KDoc "롤백됐고(커밋 단계 예외면 커밋 여부 불명)" | fixed | 3 |
| OQ9 | 3 | opus | `contract/BatchIsolationTest` 첫 테스트 | `/error`·5xx 변환 경로의 고정 문구는 테스트 없음(코드 확인만) | 한계 기록: 세 경로 모두 `unexpected()` 한 곳 반환(리뷰 3회 확인), 5xx·/error 주입 수단이 행동 테스트에 없음 | — | — |
| OQ10 | 3 | opus | ADR-0004 §15의 1 | 한 주 정산 실패 → 뒤 주 미정산·400은 테스트 없음 | 한계 기록: 코드 무변경 경로(예외 전파), 기존 `SettlementTest` 원자성 테스트가 그 주 미커밋을 단언 — 응답 400·뒤 주는 미검 | — | — |
| OQ11 | 3 | opus | `iso.sh` 범위 | shipment-deadline PG 건너뜀의 equiv 비교 없음 | 범위 밖(README 명시, 기존 `refund` 시나리오가 PG 건너뜀 경로 비교) | — | — |

- R2 기각 근거: equiv는 비교 하네스다(README 첫 문단 "결과를 비교한다", 기존 시나리오의 `need`도 경로 확인만 — `s2batch.sh` `need`). 계약 단언은 같은 경로를 행동 테스트 `contract/BatchIsolationTest.kt`·`contract2/BatchIsolationTest.kt`가 두 앱 모두에 하고(19:40 green, 20:30 main에서 red). 시나리오 안에서 계약 위반에 `die`하면 한쪽만 회귀한 경우가 차이(1)가 아니라 실행 실패(2)로 분류돼 spec §5 변이 판정(exit 1)과 종료 코드 의미(README)가 섞인다. 한계는 README iso 행에 명시.

## 생략한 검증

- 없음(긴급 아님, DEBT 0). 단 리뷰 종료 조건 미충족 1건: 상한 루프 3에서 신규 채택 R6·R7(low) — 수정 + codex 타깃 재점검(R8 발견·수정)으로 open 0, 신규 채택 0인 루프는 없음 → 병합 전 사용자 판단 필요(review.md §1 "review unresolved" 경로).
- 절차 편차: blind 테스트 워커가 `git diff --stat`으로 god/ 파일 이름·줄 수를 봄(내용 미열람).

## 완료 요약

### 1. 결과

god·layered 두 앱이 ADR-0003 §12의 1·2와 ADR-0004 §15의 1을 똑같이 구현했다. 계약 밖 오류는 `400 UNEXPECTED_ERROR` 고정 문구로 응답하고 원인은 ERROR 로그에만 남긴다. 격리 배치 7개는 실패한 대상만 롤백하고 계속 진행하며, 응답은 `{"processed": n, "failed": m}`이다. `weekly-settlement`와 기동 시 전환은 격리하지 않는다. ADR의 "구현 전" 표시는 확정 문장으로 바꿨다. 변경 13파일(+205/−127)에 새 파일 4개(테스트 3, `iso.sh`)가 더해졌고, 금지영역 diff는 0이다.

### 2. 핵심 diff

**① god `forEachTarget`: 격리 catch** (`git show 4276972:god/src/main/kotlin/com/ccle/god/Marketplace.kt:1292-1308` → `god/src/main/kotlin/com/ccle/god/Marketplace.kt:1300-1338`)

before:
```kotlin
    private fun forEachTarget(table: String, condition: String, params: Map<String, Any>, handle: (Long) -> Boolean): Int {
        ...
            if (ids.isEmpty()) return processed
            for (id in ids) {
                if (handle(id)) processed++
            }
            after = ids.last()
```
after:
```kotlin
    private fun forEachTarget(
        table: String,
        condition: String,
        params: Map<String, Any>,
        isolatedAs: String? = null,
        handle: (Long) -> Boolean,
    ): BatchResult {
        ...
            if (ids.isEmpty()) return BatchResult(processed, failed)
            for (id in ids) {
                val changed = if (isolatedAs == null) {
                    handle(id)
                } else {
                    try {
                        handle(id)
                    } catch (e: Exception) {
                        failed++
                        if (e is ApiException && e.code == "PG_UNAVAILABLE") {
                            log.warn("{}: 대상 {} 건너뜀(카드 취소 실패)", isolatedAs, id)
                        } else {
                            log.error("{}: 대상 {} 실패(반영 안 됨 — 커밋 단계 예외면 커밋 여부 불명, 다음 실행이 다시 판정)", isolatedAs, id, e)
                        }
                        false
                    }
                }
                if (changed) processed++
            }
            after = ids.last()
```
`isolatedAs` 기본값 null은 예외를 그대로 전파한다. 기동 시 전환(`.processed`)과 `settleWeeks`의 `autoConfirmItems(now, isolatedAs = null)`가 이 경로를 쓴다.

**② layered `forEachIsolated`** (service 확장 함수. 포트와 infra `KeysetBatchTargets`는 바꾸지 않았다. 새 코드는 `layered/src/main/kotlin/com/ccle/layered/service/BatchService.kt:44-60`, 대체된 shipment-deadline 국소 catch는 `git show 4276972:layered/src/main/kotlin/com/ccle/layered/service/BatchService.kt:196-212`)

before (PG_UNAVAILABLE만 shipment-deadline 안에서 건너뛰고, 그 밖의 예외는 배치를 멈춘다):
```kotlin
    fun run(now: Instant): Int = targets.forEach(BatchTarget.UNSHIPPED_ITEMS, now) { itemId ->
        try {
            tx.execute {
                ...
            }!!
        } catch (e: MarketException) {
            if (e.failure != Failure.PG_UNAVAILABLE) throw e
            log.warn("shipment-deadline: 항목 {} 건너뜀(카드 취소 실패)", itemId)
            false
        }
    }
```
after:
```kotlin
private fun BatchTargets.forEachIsolated(batch: String, target: BatchTarget, now: Instant, handle: (Long) -> Boolean): BatchResult {
    var failed = 0
    val processed = forEach(target, now) { id ->
        try {
            handle(id)
        } catch (e: Exception) {
            failed++
            if (e is MarketException && e.failure == Failure.PG_UNAVAILABLE) {
                isolationLog.warn("{}: 대상 {} 건너뜀(카드 취소 실패)", batch, id)
            } else {
                isolationLog.error("{}: 대상 {} 실패(반영 안 됨 — 커밋 단계 예외면 커밋 여부 불명, 다음 실행이 다시 판정)", batch, id, e)
            }
            false
        }
    }
    return BatchResult(processed, failed)
}
```
weekly-settlement는 격리하지 않는 별도 진입점을 쓴다. before는 `autoConfirm.run(now)`(`git show 4276972:…/BatchService.kt:300`), after는 `autoConfirm.runStoppingOnFailure(now)`(`BatchService.kt:329`, 정의 `:254`)이다.

**③ 두 앱 `unexpected()` 고정 문구** (god `git show 4276972:god/src/main/kotlin/com/ccle/god/web/ErrorHandling.kt:30` → `god/src/main/kotlin/com/ccle/god/web/ErrorHandling.kt:30`, layered `git show 4276972:layered/src/main/kotlin/com/ccle/layered/api/ErrorHandling.kt:43` → `layered/src/main/kotlin/com/ccle/layered/api/ErrorHandling.kt:43`. 두 앱의 변경이 같다)
```kotlin
-    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorBody("UNEXPECTED_ERROR", what))
+    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorBody("UNEXPECTED_ERROR", "예상하지 못한 서버 오류"))
```
R1 수정에 따라 `fromStatus`에 `where`(요청 메서드·경로) 인자를 넣었다. 그래서 `/error`와 5xx 변환 경로의 ERROR 로그에도 요청 메서드·경로가 남는다(`/error`는 `ERROR_REQUEST_URI`).

**④ AdminController 응답** (god `git show 4276972:god/src/main/kotlin/com/ccle/god/web/AdminController.kt:36` → `god/src/main/kotlin/com/ccle/god/web/AdminController.kt:36-39`, layered `git show 4276972:layered/src/main/kotlin/com/ccle/layered/api/AdminController.kt:42` → `layered/src/main/kotlin/com/ccle/layered/api/AdminController.kt:42-45`)
```kotlin
-    fun runBatch(@PathVariable name: String): Map<String, Int> = mapOf("processed" to batches.run(name))
+    fun runBatch(@PathVariable name: String): Map<String, Int> {
+        val result = batches.run(name)
+        return mapOf("processed" to result.processed, "failed" to result.failed)
+    }
```
(위는 layered다. god는 `marketplace.runBatch(name)`이고 나머지는 같다.)

### 3. 검증

- **행동 테스트(최종, R6~R8 수정 뒤 `final2`):** god·layered 모두 `test` rc 0(infra 28, contract 122)과 `contract2Test` rc 0(89)이다. verify는 4회 모두 위반 0(76 검사). 새 테스트 12개가 들어갔다(이전 contract 117, contract2 82). 기존 테스트는 `Flow.runBatch`·`S2.runBatch`가 정수 `failed`도 요구하게 강화했고, 단언을 약화한 곳은 없다.
- **그린 위장 점검(20:30):** main(4276972) worktree에 새 테스트만 복사해 god에서 돌렸다. 새 테스트는 contract 5/5, contract2 7/7로 모두 실패했다. 나머지 실패(contract 17, contract2 30)는 `runBatch`가 `failed` 정수를 요구하게 강화한 탓이다.
- **스모크(18:40, 실행 중인 앱 + 임시 트리거, 1k 시드):** bid-expiry 대상 1006건 중 id 21544에 트리거를 걸었다.
  - 두 앱 모두 `{"processed":1005,"failed":1}`을 반환했다. 21544는 ACTIVE로 남았고 EXPIRED 이벤트는 0, ERROR 로그에 `bid-expiry: 대상 21544 실패`가 찍혔다.
  - 트리거를 지운 뒤에는 `{"processed":1,"failed":0}`이 되고 EXPIRED로 바뀌었다.
  - bids INSERT 트리거를 걸면 `POST /bids`가 `400 {"code":"UNEXPECTED_ERROR","message":"예상하지 못한 서버 오류"}`를 냈다.
  - load-bearing 가정 1·2를 실증했다.
- **equiv:**
  - `iso` 단독: 21:10에 4층 같음(79초). R6·R7 수정 뒤 22:45에 다시 4층 같음(80초, log-counts 3 1 3 1).
  - `run.sh all` 1회차(최종 코드, `final2`): rc 0, 12개 같음, 946초.
  - 변이(21:15, 수정 전 코드): layered `forEachIsolated` catch에 `throw e` → `iso` rc 1, 4층 모두 차이.
  - `run.sh all` 2회차(최종 코드): rc 0, 12개 같음, 952초.
  - 최종 코드 변이 `iso`(격리 끈 layered jar): rc 1, 응답·새행·SQL·DB문장 4층 모두 차이.

### 4. 리뷰 (듀얼 리뷰 루프 3회 = 상한, Opus ∥ codex → 종합 → codex 감사)

- **채택:**
  - 루프 1
    - R1: 로그에 요청 메서드·경로 추가
    - R3: shipment-deadline 테스트의 기저 failed 단언(그린 위장 차단)
    - OQ1: spec I4를 ADR 문구에 맞춤
  - 루프 2
    - R4: iso 일치 검사 전용 die 문구
    - R5: grep 0건 방어
    - OQ5: `bc` 대신 awk
  - 루프 3
    - R6: iso에 재실행 뒤 A 처리 `need` 추가
    - R7: ERROR 문구에서 "롤백" 단정 제거
    - R8: ADR·KDoc의 "이미 롤백" 단정 제거
- **기각:** R2(iso 계약 단언). 감사 A1에서 로그 부분만 채택했다.
- **감사가 잡은 종합 판정 오류 3건:**
  - A1: R2 전면 기각은 근거가 부족했다. I4 로그 공백 → iso에 응답-로그 일치 `need` 추가.
  - A2: OQ6 기각 근거("I2 무해")가 틀렸다 → ADR-0003 §12의 2에 커밋 여부 불명도 failed에 센다고 명시했다.
  - A3: A1의 일치식은 일반 불변식이 아니다 → "카드 취소 대상이 없어 성립" 조건을 명시했다.
- **종료 조건 미충족:** 상한인 루프 3에서도 Opus가 낸 R6·R7이 신규 채택됐다. 수정 뒤 codex 타깃 재점검(`results/recheck-r3.md`)에서 R6·R7 해소를 확인했고 R8을 새로 찾아 고쳤다. codex 루프 3 자체는 finding 0이었다. 신규 채택 0으로 끝난 루프는 없다.

### 5. 남은 한계(보류)

- **OQ2:** 인프라 장애가 나면 페이지(≤500)만큼 대상마다 ERROR 스택이 찍힌다. 운영 상한이 필요한지는 NEXT 관찰 항목으로 넘겼다.
- **OQ3:** payment-due·pg-inquiry·grade-recalc·shipment-deadline 트리거 격리의 SQL 동치에는 equiv 근거가 없다. 래퍼가 SQL을 내지 않는다는 설계 선검증 F7에 기댄다.
- **OQ7:** WARN(PG 건너뜀) 로그를 검증하는 경로가 없다. 행동 테스트는 failed와 DB만 단언한다.
- **OQ8:** 기저 측정 뒤 제3자 실패가 새로 생기면 `failed ≥ 기저+1`은 A 없이도 성립한다. 응답에 대상별 내역이 없어 더 좁힐 수 없고, A 상태 단언이 I1 본체다.
- **OQ9:** `/error`·5xx 변환 경로의 고정 문구는 테스트가 없고 코드로만 확인했다(세 경로 모두 `unexpected()` 한 곳).
- **OQ10:** 한 주 정산이 실패했을 때 400을 내고 뒤 주를 정산하지 않는 동작은 미검이다. 그 주 미커밋은 기존 `SettlementTest`가 단언한다.
- **OQ11:** shipment-deadline PG 건너뜀은 iso 범위 밖이다. 기존 `refund` 시나리오가 비교한다.
- **OQ4·OQ6:** 범위 밖(계약 안 오류 문구, 커밋 결과 불명은 ADR 명시로 처리).
- **blind 워커 미검:** 대상 조회 실패 400(SELECT 트리거 불가), 기동 전환 비격리(재기동 필요).
