# 요구사항 명세서 — layered 단계 A 구현 (ADR-0007 A1~A12)

> 작성일: 2026-10-29 · 작업 폴더: `docs/plans/2026-10-29/layered-stage-a/` · 브랜치 `impl/layered-stage-a`(main `95cc1da`)
> 산출물 유형: **실행물**(layered 앱). ADR-0006 §6.1의 4(layered).

## 0. 요구사항 원문 (인터뷰 기록)

- 원문: "병합·push 후 layered로 진행" → layered 구현.
- Q/A: 진행 방식 → **god와 같은 방식**(묶음 A→B→C 순차·Opus 워커, 묶음마다 contract3 green → 커밋 → 듀얼 1패스, 끝에 누적 듀얼 루프, 테스트 결함은 ADR 근거로 Claude 판정·목록 보고, auto). layered는 god와 **같은 SQL**(equiv)을 내야 하므로 god의 단계 A SQL·잠금 순서를 읽고 맞춘다. equiv 새 시나리오는 다음 작업(판정기 연결과 함께).

## 1. 목표·대상 (필수)

layered 앱이 ADR-0007(+ §19)의 A1~A12를 구현해 다음이 되면 끝:
1. `tools/behavior/run.sh layered contract3Test` 168/168.
2. 기존 layered `test`(infra 28 + contract 122)·`contract2Test` 89 그대로, verify 120 통과. god 쪽 결과 불변(god 미변경).
3. equiv `all` 12/12 같음(기존 경로의 layered SQL 무변경).
4. 구조(ADR-0006 §3의 1·개정 1): 새 기능 코드는 **새 파일**(api·service·domain·infra). 기존 파일 변경은 허용 목록만 — `BatchService.run` 분기·생성자 주입, 컨트롤러 등록, 새 포트 import, `BatchTarget` 값 추가, `KeysetBatchTargets.spec` 분기 추가, `forEachIsolated` private → internal. 오류 code는 기존 `Failure`만(enum·`statusOf` 무변경).
5. SQL·잠금 동치: 각 기능의 대상 조회·잠금·쓰기 문장이 god(`Marketplace.kt` 단계 A 블록)와 같은 모양(배치 대상 조건의 바인드 형식 `:due`/`:beforeNd`·KST 반열린 구간·EXISTS, §19의 9-1·9-2·10-1·10-2 사전 KEY SHARE 문장, 잠금 순서). 다음 작업의 equiv 새 시나리오가 판정한다.

대상: `layered/src/main/**`(새 파일 + 허용 목록 변경), contract3 테스트 결함 시 `behavior-tests/.../contract3/**`, ADR 보완 시 ADR-0007 §19.

## 2. 경계·불변식 (필수)

- ADR-0006 §3 불변식 1~7(기존 22 테이블 쓰기 금지, 기존 경로 SQL 추가 금지, 고칠 곳·읽을 곳 무변경 — `l7/fixcheck-layered.py` 33/33, 본보기 배제, 오류 code 기존만).
- layered 구조: 새 기능을 기존 서비스·도메인 파일에 넣지 않는다(허용 목록 밖 기존 파일 변경 = 실패). 규칙은 도메인·서비스에, SQL 동치가 필요한 조건은 기존 관례대로 infra에(layered 2단계 재합의 관례).
- god·스키마·시드·verify·`experiment/` 무변경.

## 3. 기준소스 (필수)

ADR-0007(+ §19) · ADR-0006 §3(개정 1) · 현행 `layered/src/main/**` · god `Marketplace.kt` 단계 A 블록(SQL·잠금 정본) · contract3 · `experiment/oracle/{T1,C2}/symbols.layered.txt`·ADR-0005 §3.2.

## 4. 금지영역 (필수)

`god/`·`docker/`·`tools/`·`experiment/` 변경 금지 · contract·contract2·infra 테스트 변경 금지 · layered 기존 파일은 §1의 4 허용 목록 밖 변경 금지 · `Failure` enum·`statusOf` 변경 금지 · 에이전트(claude) 실험 실행 금지 · study-note 쓰기 금지 · push는 사용자 확인 후.

## 5. 검증 방법 (필수)

- 묶음마다: 묶음 contract3 green + 기존 layered `test`·`contract2Test` + verify + fixcheck 33/33 + 허용 목록 밖 기존 파일 diff 0(스크립트) → 커밋 → 듀얼 1패스(렌즈: ADR 일치·god와 SQL·잠금 동치·허용 목록·불변식·동시성).
- 끝에: contract3 168 + 기존 + equiv 12 + 누적 듀얼 루프(≤3).

## 6. stakes (필수)

- 판정: **높음** — public API·DB 쓰기·동시성·실험 처치(layered 구조)와 고칠 곳 보존·두 구조 SQL 동치가 실험 타당성을 좌우.

## 7. 자율성

- [x] auto (사용자 선택 2026-10-29)

## 8. load-bearing 가정

1. 허용 목록만으로 새 배치 11개를 기존 키셋·격리 틀에 끼울 수 있다(ADR-0006 개정 1의 근거) — 묶음 A 착수 직후 확인.
2. god의 SQL을 layered 관례(JPA·infra 저장소)로 같은 문장 모양으로 옮길 수 있다 — 어긋나는 곳은 다음 작업 equiv에서 드러나며, 그때 한쪽을 맞춘다.

## 9. task 분해

| task | 목표 | 의존 | acceptance |
|------|------|------|-----------|
| 01 | 고칠 곳 대조(완료) + 허용 목록 diff 검사 스크립트 | — | 기준선 통과 |
| 02 | 묶음 A(Opus 워커) → green → 커밋 → 듀얼 1패스 | 01 | §5 |
| 03 | 묶음 B 〃 | 02 | §5 |
| 04 | 묶음 C 〃 | 03 | §5 |
| 05 | 전체 회귀 + equiv 12 + 누적 듀얼 루프 → 병합(push 확인) | 04 | §1 |
