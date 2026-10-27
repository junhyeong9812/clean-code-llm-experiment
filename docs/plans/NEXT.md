# NEXT — 다음 작업 전망

> 프로젝트 롤링 단일 문서. 착수는 새 작업 폴더의 requirement-spec부터. 다음 spec에 옮길 때 실코드로 재확인한다.

## 기준

- 마지막 갱신: 2026-10-28 · 직전 완료 작업: `docs/plans/2026-10-26/tasks-redesign/`(T1·C2 재설계 + Bash 편집 인식 + 2회차 파일럿 — **게이트 재미달**) · 기준 커밋: `b1f7c86` + docs · 병합 상태: 대기

## 다음 작업 후보 (우선순위순)

### N0. 1차 결론 작성(README) + 규모 확장 여부 결정 — 우선순위: **최우선** · 관측 신호: 재설계 파일럿(2026-10-28)도 4/4 성공인데 턴 5~6(구조별 중앙값 god 5.5·layered 5.5 < 10, ADR-0005 §7 게이트 재미달) (`tasks-redesign` log)

- **사전 결정(2026-10-26, ADR §7.3 개정 2):** 재미달이면 "이 코드 크기에서는 Sonnet이 두 구조 모두 쉽게 풀어, 구조 효과를 볼 만큼 과제를 어렵게 만들기 어렵다"를 1차 결론으로 쓰고, 코드 규모를 키운 재측정을 다음 결정으로 남긴다. 본 실행(Sonnet 36회)은 하지 않는다.
- 결론에 넣을 근거(본 표본이 아니라 파일럿 8회 — 기술적 관찰로만, 효과 추정 아님): 두 회차 모두 두 구조 성공·회귀 통과 · 턴 5~6·도구 호출 4~5회 · 키워드 grep 1회로 지점 도달(분리·오진 유도·여러 지점이 grep 한 번에 무력화) · 토큰 95k~166k(캐시 고정분이 큼) · 구조 간 순위가 과제마다 뒤집힘(1회차 C2 god>layered, 2회차 C2 layered>god) — n=1이라 비교 불가.
- 범위 문구: Sonnet · 수정 과제 · 구현당 2.7k~4.8k줄 · 사전 등록 요약과 다른 추정 대상(ADR §2·§9 위협 11). 파일럿은 본 표본이 아님.
- 다음 결정 후보(사용자): ① 코드 규모 확장(목표 1만 줄+ — 구현 비용 큼) ② 지표를 턴 대신 토큰·읽은 줄로 바꾸는 사전 기준 개정(사후 변경이라 선택 편향 표시 필요) ③ 여기서 실험 종료.
- 남은 측정 장치(재사용 가능): Bash 편집 인식(missed_edit_paths 안전망 — 파일럿 8회 누락 0), 판정 단계·check, T1·C2 개정 2(채택 확정 `ae6deda`).
- 이월: `--max-budget-usd` 중단 미확인(두 파일럿 모두 예산 미도달) · Bash 해석 휴리스틱 긴 꼬리(사용자 잔여 위험 수용) · Q3-2·Q3-3 · 앱·테스트 컨테이너 loopback 공유 · app_died 감지 지연.

### N1. 500k 배치 비용 — 대처 후보 비교(필요해질 때) — 우선순위: 낮음 · 관측 신호: 500k 측정(2026-10-25)

- 근거: `docs/plans/2026-10-25/measure-500k/log.md` "측정 결과"·"해석". 실행 1회(평상시 + 약 15주 몰아 처리) ≈ 2.6시간, 두 앱 시간은 1회 비교로 판단 보류(관찰 비 0.928~1.007). 몰아 처리는 배치마다 9~43분짜리 동기 요청 하나. 현재 이 실험의 요구(1k 행동 테스트·equiv)에는 영향 없음 — 과제 러너가 500k에서 배치를 돌리거나 운영형 시나리오를 다룰 때 착수.
- 후보(병목 노드별 — 효과는 미측정 추론, 착수 전 문장별 시간을 `pg_stat_statements`로 재는 확인 측정이 먼저):
  | 후보 | 대상 | 바뀌는 것 | 기대 | 비용·위험 |
  |---|---|---|---|---|
  | a. 대상 조회 부분 인덱스(예: bids `(id) WHERE status='ACTIVE'` + 만료) 또는 플래너 통계 보정 | bid-expiry·auto-confirm·shipment-deadline·point-expiry | 스키마(ADR-0002·docker — 금지영역이었음) | PK 훑기 + 오추정 제거 — 평상시 수십 초 단축 가능, 몰아 처리에선 비중 작아 보임 | 스키마 변경·verify·시드 재빌드, 두 앱 SQL 동치 불변 |
  | b. grade-recalc 대상 선정을 사용자별 SubPlan 대신 집계 1회(GROUP BY)로 | grade-recalc(평상시 895초) | 두 앱 쿼리(ADR §8.5 조건 의미는 유지) | 50만 명 × 서브쿼리 반복 제거 — 가장 클 것으로 보임 | 두 앱 같은 SQL로 바꿔 equiv 재확인, 잠근 뒤 재판정은 그대로 |
  | c. 정산 잠금 조회·대상 조회 통합 + 미정산 판정(NOT EXISTS settlement_items) 구조 | weekly-settlement(첫 주 두 조회 55초 × 주 수 — 가정값) | 두 앱 쿼리, 필요하면 인덱스(스키마) | 같은 SubPlan 두 번 → 한 번 | 잠금 순서(§9.1)·verify 영향 검토 필요 |
  | d. 대상 1건 = 트랜잭션 1개 비용(요청 평균 8~12ms/건) | 전 배치 | ADR 계약(격리 단위) | 묶음 커밋 시 크게 줄 수 있음 | 격리 계약(ADR-0003 §12의 2)과 충돌 — 사실상 비채택 |
  | e. 배치 비동기화(요청은 접수만) | 몰아 처리 | API 계약 | 43분 동기 요청 해소 | 계약 밖 — 이 실험 범위 아님 |
- 발생 가능 문제: a·c는 스키마/잠금 순서에 닿아 변경이 확산된다 — 착수 시 높음 stakes. b가 가장 국소적.
- 측정 한계(그대로 승계): 반복 1회·순서 편향, 페이지별 시간 미측정, EXPLAIN은 첫 페이지 리터럴 custom plan, 가짜 PG 지연 0(실 PG면 shipment-deadline이 카드 취소 호출 수 × 지연만큼 더), 같은 달 grade-recalc 재실행 비용 미측정, 포인트 묶음 누적 시나리오 미측정(ADR-0003 §12의 7은 "현 시드 최대 100개에서 0.5초"까지만 근거).

## 보류·이월

- **equiv 2단계가 남긴 것(근거: `docs/plans/2026-10-23/equiv-stage2/log.md` 리뷰 ledger R6·OQ1, 2026-10-24 batch-isolation 반영):** 체결가 < 10원 판매자 귀책을 shipment-deadline 배치로 밟는 경로는 시나리오에 없다(검수 FAIL로만 재현 — 응답은 이제 400 고정 문구, PG 취소는 기록된 채 DB는 롤백). 배치에서는 격리되어 그 항목만 매 실행 `failed`·ERROR로 남는다(ADR-0004 §15의 1 계약 밖 귀결) — 이 배치 경로의 두 앱 동치는 미측정(격리 래퍼는 SQL을 내지 않아 위험은 낮음). 금액 불일치(§7.2 계약 밖)는 결과 상태를 주입해 재현했고 자연 경로(취소 응답 없음 + 다른 항목 환불)는 가짜 PG에 취소 응답 없음 결과가 없어 재현 불가.

- **과제 러너 분석 메모 — layered 2단계가 남긴 것(근거: `docs/plans/2026-10-22/layered-stage2/log.md`):** 재합의 1 — 규칙 위치와 SQL 동치가 부딪히는 곳(§8.1 등급 선택·등급 경계·정산 대상 조건·수수료 floor)은 god SQL과 같게 infra에 둠. 실험 분석(과제 러너) 때 '구조 차이'로 다룰 항목.

- 시드 이미지(2026-10-25 갱신): 500k 이미지 재빌드 완료(`ccle-postgres-seed:500k-s42` 16.6GB, verify 위반 0, 빌드 1:28:48) — 유지 중. 이전 기록: equiv 2단계 착수 시 docker에서 `ccle-postgres-seed:1k-s42`·500k 이미지가 모두 없었다(세션 밖 정리로 추정 — 디스크 6.7G → 54G). 1k는 `tools/seedgen/build-image.sh ~/ccle-csv/1k 1k-s42`로 재빌드(verify 위반 0, 기존 7개 equiv 판정 불변). 500k는 2026-10-25 재빌드됨

- 동치 하네스 한계(근거: `docs/plans/2026-10-19/equiv-harness/log.md`): 폴링 접기는 반복 단위가 통째로 늘어나는 변경을 못 잡음(재합의 2) · docker 데몬 오류 시 정리 잔여 확인이 "없음"으로 처리(리뷰 N2) · 직렬 시나리오는 준비 문장 이름까지 비교(부하 큰 호스트에서 흔들리면 재합의) · docker 로그 회전 없음 가정 · 재개 조건: 해당 증상이 실제로 관측될 때

- **배치 격리가 남긴 것(근거: `docs/plans/2026-10-24/batch-isolation/log.md` 리뷰 ledger OQ2·OQ3·OQ8~OQ10):** ① DB 연결 장애·교착처럼 남은 대상이 모두 실패하는 상황에서 키셋 페이지(≤500)만큼 대상마다 ERROR 스택이 쌓인다(계약대로, 연속 실패 상한 없음) ② equiv `iso`는 bid-expiry·point-expiry·auto-confirm·weekly-settlement만 — payment-due·pg-inquiry·grade-recalc·shipment-deadline 트리거 격리의 SQL 동치는 행동 테스트(응답·DB)만 ③ `/error`·5xx 변환 경로 고정 문구, 한 주 정산 실패 뒤 400·뒤 주 미정산은 테스트 없음(코드 확인만) ④ shipment-deadline 테스트의 `failed ≥ 기저+1`은 기저 측정 뒤 생긴 제3자 실패로도 성립 가능 · 재개 조건: 운영형 관측(로그 폭주)이 필요해지거나 해당 경로를 바꿀 때

- study-note 이슈 아카이브 — study-note 쓰기를 하지 않기로 함(god·layered·equiv-harness·behavior-tests-stage2·god-class-stage2·layered-stage2·equiv-stage2·open-questions·batch-isolation·measure-500k·task-runner-design·runner-smoke 열두 작업 모두 보류) · 재개 조건: 사용자가 아카이브를 요청할 때
- 리뷰 도구: codex bwrap 오류(`Failed RTM_NEWADDR`)가 god 작업 4+1회, layered 작업 1회, god 2단계 2회, layered 2단계 1회(타깃 재점검) — 재시도로 회복. god 2단계에서는 exit 0으로 '판정 불가'를 출력해 종료 코드로는 실패를 못 잡았다(출력 내용으로 판정하게 바꿈) · 재개 조건: 재시도로도 실패하면 원인 조사
- bid-expiry layered +0.9%(god 대비) — 노이즈 범위로 보고 프로파일링 안 함 · 재개 조건: 과제 러너에서 성능을 지표로 쓸 때

## 완료 이력

- 완료 → `docs/plans/2026-10-26/tasks-redesign/` (T1 확정 시각 상한·C2 제한 시 입찰 취소 재설계 — 채택 60행 `ae6deda` · Bash 편집·cwd 인식 + diff 누락 대조 · ADR-0005 개정 2 · 2회차 파일럿 4/4 성공, **턴 게이트 재미달 → 1차 결론(N0)**)
- 완료 → `docs/plans/2026-10-26/tasks-a-judge/` (판정 단계 + 과제 T1·C2 채택(정답 5/5·none·naive 전부 실측) + 러너 소수정 + 파일럿 4회 — 전부 성공, **턴 게이트 미달 → 과제 재설계 N0**)
- 완료 → `docs/plans/2026-10-26/adr-0005-update/` (ADR-0005 개정 1)
- 완료 → `docs/plans/2026-10-25/runner-smoke/` (과제 러너 + 에이전트·프록시 이미지 + 스모크 — Haiku 5회, 테스트 162, 리뷰 3루프 + 재점검 후 user override)
- 완료 → `docs/plans/2026-10-25/task-runner-design/` (ADR-0005 과제·판정·러너 설계 채택 — 과제 8개, 컨테이너 격리, censored 처리, 파일럿 게이트)
- 완료 → `docs/plans/2026-10-25/measure-500k/` (500k 배치 8종·포인트 예약 측정 — 0회차 god + god·layered 각 1회, 측정 도구 `tools/equiv/measure-batches.sh`)
- 완료 → `docs/plans/2026-10-24/batch-isolation/` (계약 변경 2건 구현 — 오류 문구 고정·배치 7종 건별 격리 `{"processed","failed"}`, 행동 테스트 +12, equiv `iso` 추가 12개)
- 완료 → `docs/plans/2026-10-24/open-questions/` (open question 정리 — 현행 확정 9 · 변경 2(→ batch-isolation에서 구현) · 규모는 N1로)
- 완료 → `docs/plans/2026-10-23/equiv-stage2/` (2단계 SQL 순서 동치 실측 — 11개 시나리오 같음, 반대 순서 3곳 변이 검출)
- 완료 → `docs/plans/2026-10-22/layered-stage2/`
- 완료 → `docs/plans/2026-10-22/god-class-stage2/`
- 완료 → `docs/plans/2026-10-20/behavior-tests-stage2/`
- 완료 → `docs/plans/2026-10-19/equiv-harness/`
- 완료 → `docs/plans/2026-10-18/layered-stage1/`
- 완료 → `docs/plans/2026-10-17/god-class-stage1/`
- 완료 → `docs/plans/2026-10-15/behavior-tests-stage1/`
- 완료 → `docs/plans/2026-10-15/schema-and-seed-data/`
- 완료 → `docs/plans/2026-10-13/project-setup/`
