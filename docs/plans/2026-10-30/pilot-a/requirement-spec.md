# 요구사항 명세서 — 파일럿 A (ADR-0006 §6.1-9·§6.2·§6.3)

> 작성일: 2026-10-30 · 작업 폴더: `docs/plans/2026-10-30/pilot-a/` · 브랜치 `impl/pilot-a`(main `0744368`)
> 산출물 유형: **실행물**(에이전트 실험 실행 4회 + 결과 디렉토리, 낱말 비대칭 시 앱 주석) + 게이트 판정 기록.

## 0. 요구사항 원문 (인터뷰 기록)

- 원문: "병합·push 후 다음으로" → NEXT 다음 착수 = 파일럿 A.
- Q/A:
  - 병렬 → **`--parallel 2`**(2회차와 같음). 시작 전 `free`를 log에.
  - 낱말 대칭(TP-1) → **점검 후 비대칭이면 주석 정리**(동작 무변경).
  - 예산 → **러너 기본 `--budget 5`** 그대로.
  - §6.3 적용 → **판정하고 보고만**. 본 실행·단계 B·2차 결론 등 다음 작업은 사용자 확인.

## 1. 목표·대상 (필수)

다음이 되면 끝:
1. **낱말 대칭 점검:** T1·C2 `TASK.md` 핵심어(예: 확정·confirm·7일·제한·restrict·입찰·bid·취소)로 두 구조 `src/main`을 grep해 적중 수를 표로 남긴다. 단계 A·보충 주석 때문에 한쪽에만 생긴 적중이 있으면 그 주석을 중립 문장으로 고친다(주석만, **줄 수 그대로**).
2. **파일럿 실행:** `runner.py run --model sonnet --cases T1,C2 --reps 1 --parallel 2 --results-dir experiment/results-pilot3`(본 표본 밖). 실행 직전 기준 SHA·`code_dirty`·두 구조 줄 수·`free`를 log에 고정.
3. **게이트 판정(§6.2):** 4실행 valid ∧ 지표 전부 파싱 ∧ 하나 이상 성공 ∧ 구조마다 턴 중앙값 ≥ 10. 무효(ADR-0005 §6.4)·지표 불완전 실행은 §6.3대로 같은 조건 1회 재실행(사용자 사전 승인 범위 — 그 1회까지만).
4. **§6.3 표 적용 판정 기록:** 어느 행인지, 그 근거 수치(턴·토큰·성공·편집 지표·도구 출력 절단 여부 — 위협 7)를 log와 ADR-0006에 기록하고 사용자에게 보고. 다음 작업은 착수하지 않는다.

## 2. 경계·불변식 (필수)

- 과제(TASK.md·공개·숨은 테스트·oracle 패치·symbols)·러너·판정기·지표 정의 무변경(ADR-0005 개정 2 그대로).
- 주석 정리는 줄 수 그대로 — oracle 패치 42개 dry-run(offset 0·fuzz 0)·symbols 블록 sha1·fixcheck 25/25·33/33 그대로. 앱 동작·SQL 무변경(컴파일 + 고칠 곳 대조).
- 실행은 승인 범위 4회 + §6.3 재실행 1회까지. 그 밖의 claude 실행 금지.
- 기존 결과 디렉토리(`results`·`results-pilot`·`results-pilot2`) 무변경.

## 3. 기준소스 (필수)

ADR-0006 §6.1-9·§6.2·§6.3·§8 · ADR-0005(개정 2·3 — §5 지표·§6.4 무효·§7.3) · `experiment/cases/{T1,C2}/TASK.md` · 현행 main 코드 · 2회차 파일럿 기록(`docs/plans/2026-10-26/tasks-redesign/log.md`, `experiment/results-pilot2/runs.jsonl`).

## 4. 금지영역 (필수)

`experiment/cases/**`·`experiment/oracle/**`·`experiment/runner/**`·기존 `experiment/results*/**` · behavior-tests·tools·docker·seedgen · 앱 로직(주석 외) · 승인 범위 밖 claude 실행 · 본 실행·단계 B 착수 · study-note 쓰기 · push는 사용자 확인 후.

## 5. 검증 방법 (필수)

- 주석 정리 시: 핵심어 적중 표 전·후, 줄 수 diff 0(`git diff --numstat` 같은 수), 두 앱 컴파일, oracle 패치 42 dry-run, fixcheck. 정리가 없으면 표만.
- 실행: `runs.jsonl` 4행의 valid·success·censored·턴·토큰·`bad_event_lines`·편집 지표·`code_sha`/dirty 확인, raw 이벤트에서 턴 수 손 대조 1건 이상, 도구 출력 절단(`Marketplace.kt` 3,667줄 Read·cat) 여부 확인.
- 리뷰: 주석 정리는 셀프체크(동작 무변경·줄 수), 게이트 판정은 결과 해석 셀프체크 + 사용자 보고(판정 규칙이 사전 등록이라 해석 여지 작음).

## 6. stakes (필수)

- 판정: **높음** — 실험의 다음 경로(본 실행·단계 B·2차 결론)를 정하는 사전 등록 게이트, 외부 비용 발생.

## 7. 자율성

- [x] auto (사용자 선택 2026-10-30)

## 8. load-bearing 가정

1. 러너·판정기는 단계 A 코드에서 그대로 돈다(재채택 60행 check로 판정 경로는 확인됨, 에이전트 실행 경로는 미확인) — 첫 실행의 raw·runs 행으로 확인.
2. 주석 정리는 줄 수를 지켜 oracle 이식 결과를 깨지 않는다 — dry-run으로 확인.

## 9. task 분해

| task | 목표 | 의존 | acceptance |
|------|------|------|-----------|
| 01 | 낱말 대칭 점검(+ 필요 시 주석 정리·검증·커밋) | — | §5 첫 항 |
| 02 | 실행 전 고정(SHA·dirty·줄 수·free) → 파일럿 4회 | 01 | runs 4행 |
| 03 | 게이트·§6.3 판정, 필요 시 재실행 1회 | 02 | 판정 행 확정 |
| 04 | ADR-0006·NEXT·측정 로그·완료 요약 → 보고·병합 확인 | 03 | §1 |
