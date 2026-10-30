# 요구사항 명세서 — 단계 A 보충 구현 (ADR-0007 §20 A13~A17)

> 작성일: 2026-10-30 · 작업 폴더: `docs/plans/2026-10-30/stage-a-supplement/` · 브랜치 `impl/stage-a-supplement`(main `1cbe468`)
> 산출물 유형: **실행물**(behavior-tests contract3 · god · layered · equiv · 판정기 기준 수) + ADR-0005 숫자 갱신.

## 0. 요구사항 원문 (인터뷰 기록)

- 원문: "채택하고 push, 구현으로 진행" → ADR-0007 §20 구현.
- Q/A:
  - 나누기 → **한 작업에서 차례로**: contract3 보충 테스트(red) → god → layered → equiv 시나리오 → 판정 기준 수·ADR-0005. 단계마다 커밋·듀얼 1패스, 끝에 누적 듀얼 루프(≤3).
  - 테스트 → **blind Opus 워커**(구현 미열람, ADR-0007 §20만). 구현 중 테스트 결함은 Claude가 ADR 근거로 판정·수정하고 목록 보고(단계 A와 같은 방식).
  - 자율성 → **auto**.

## 1. 목표·대상 (필수)

다음이 되면 끝:
1. contract3에 A13~A17 테스트 추가(§20.12 지침 — T1·C2 회귀 픽스처 규칙, A13·A14 동시 수정 '둘 다 200', 재무장 MATCHED 비대상, A17 재생성 없음 종류 지정). 두 앱 모두 contract3 전체 green(168 + 보충 N).
2. god·layered가 §20대로 구현: 기존 test 150·contract2 89·verify 120 그대로, 고칠 곳 대조(god 25/25·layered 33/33), layered 허용 목록 검사(95cc1da 시점 55파일은 허용 목록만 — 단계 A 파일은 메서드 추가 허용), god 로직은 Marketplace.kt.
3. equiv: 보충 시나리오(새 배치 price-alert-rearm·notification-purge 실행·재실행 0, A16 UPDATE→flush→INSERT 순서, A13~A17 주요 경로) 추가, `all` 전부 같음. 차이는 layered를 god에 맞춤.
4. 판정기 `REGRESSION_BASELINE` contract3 168 → 새 수, ADR-0005 §4.1 숫자. 러너 테스트 통과.
5. god 실측(`cat $(find god/src/main -name '*.kt') | wc -l`) 기록 — 5,500 미만이면 ADR-0006 §6.1-7 규칙 반복(보고 후 사용자 결정).
6. 시드 NOW에서 두 새 배치 processed 0 확인(§20.11).

## 2. 경계·불변식 (필수)

- ADR-0006 §3 불변식 1~7(기존 22 테이블 쓰기 금지, 기존 경로 SQL 추가 금지, 고칠 곳 무변경, 본보기 배제, 오류 code 기존만) + 개정 2 §3의 1 해석.
- 스키마·시드·verify·이미지 무변경. 기존 contract·contract2·infra 테스트 무변경, 기존 contract3 168개 무변경(결함 판정 시만 ADR 근거로 수정 — log).
- 두 앱 같은 SQL·잠금(god = 정본), §20.8 잠금 순서.

## 3. 기준소스 (필수)

ADR-0007 §20(+ §1~§19) · ADR-0006 §3·§6.1 · ADR-0005 §4.1 · 현행 god·layered·behavior-tests·tools/equiv·experiment/runner · `experiment/oracle/{T1,C2}/symbols.*` · 고칠 곳 스크립트(scratchpad `g7/fixcheck.py`·`l7/fixcheck-layered.py`·`l7/allowcheck.sh`).

## 4. 금지영역 (필수)

`docker/**`·`tools/seedgen/**`·`experiment/cases/**`·`experiment/oracle/**`·`experiment/results*/**` 변경 금지 · 기존 contract·contract2·infra 테스트 변경 금지 · 에이전트(claude) 실험 실행 금지 · study-note 쓰기 금지 · push는 사용자 확인 후.

## 5. 검증 방법 (필수)

- 테스트: blind 작성 → 두 앱에서 red 사유가 '라우트·배치 없음'뿐인지 확인 → god green → layered green.
- 단계마다: 기존 test·contract2·contract3 + verify + fixcheck + allowcheck → 커밋 → 듀얼 1패스(렌즈: ADR 일치·god와 SQL·잠금 동치·불변식·동시성·테스트 결함).
- 끝: equiv `all` · 러너 테스트 · 누적 듀얼 루프(≤3) · god 실측.

## 6. stakes (필수)

- 판정: **높음** — public API·DB 쓰기·동시성·실험 처치(크기)·판정 기준 수.

## 7. 자율성

- [x] auto (사용자 선택 2026-10-30)

## 8. load-bearing 가정

1. 스키마 무변경으로 §20 쓰기가 CHECK·UNIQUE·verify 상시 검사를 지킨다(설계 리뷰 정적 판정) — god 구현 첫 실행의 verify로 확인.
2. blind 테스트가 대부분 ADR대로 맞다 — 실패는 대부분 구현 쪽.

## 9. task 분해

| task | 목표 | 의존 | acceptance |
|------|------|------|-----------|
| 01 | contract3 보충 테스트(blind Opus) | — | 두 앱 red 사유 = 라우트·배치 없음뿐, 기존 168 그대로 |
| 02 | god 구현 | 01 | god contract3 전체 green + §5 |
| 03 | layered 구현 | 02 | layered green + allowcheck + §5 |
| 04 | equiv 보충 시나리오 + 판정 기준 수·ADR-0005 | 03 | all 같음, 러너 테스트 |
| 05 | 누적 듀얼 루프 → god 실측 → 병합(push 확인) | 04 | §1 |
