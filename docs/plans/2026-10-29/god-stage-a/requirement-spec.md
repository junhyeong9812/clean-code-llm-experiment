# 요구사항 명세서 — god 단계 A 구현 (ADR-0007 A1~A12)

> 작성일: 2026-10-29 · 작업 폴더: `docs/plans/2026-10-29/god-stage-a/` · 브랜치 `impl/god-stage-a`(main `8663122`)
> 산출물 유형: **실행물**(god 앱). ADR-0006 §6.1의 4(god).

## 0. 요구사항 원문 (인터뷰 기록)

- 원문: "병합하고 push한 뒤 계속 진행해줘" → NEXT 다음 착수 = god 구현.
- Q/A:
  - 나누기 → **묶음 3개를 한 작업에서 차례로**: 묶음 A(A1·A2·A3·A7) → B(A4·A5·A11) → C(A6·A8·A9·A10·A12). 묶음마다 그 묶음의 contract3가 green이면 커밋, 묶음마다 듀얼 1패스, 끝에 누적 듀얼 리뷰 루프.
  - 테스트 결함·ADR 모호 → **Claude가 판정·수정하고 목록 보고**: ADR과 대조해 테스트 결함이면 테스트를 고치고, ADR이 정하지 않은 곳이면 ADR-0007 §19에 보완([제안])하고 테스트를 맞춘다. 구현에 맞춰 테스트를 바꾸는 판정은 ADR 근거가 있을 때만. 전부 log에 남기고 완료 보고에 목록.
  - 자율성 → **auto**.

## 1. 목표·대상 (필수)

god 앱이 ADR-0007(+ §19)의 A1~A12를 구현해 다음이 되면 끝:
1. `tools/behavior/run.sh god contract3Test` 164/164 통과(SlowTrigger 자체 검증 2 포함).
2. 기존 god `test`(infra 28 + contract 122)·`contract2Test` 89 그대로 통과, 각 실행 끝 verify 120 통과.
3. equiv `all` 12/12 같음(기존 경로의 god SQL이 바뀌지 않았다는 확인 — layered는 아직 단계 A 없음).
4. 구조: 새 기능 로직은 `Marketplace.kt`, 컨트롤러·엔티티·DTO는 `web/`·`entity/`(ADR-0006 §3의 1). god 줄 수 실측을 log에 기록(목표 ≥ 5,500은 layered까지 끝난 뒤 크기 확인 단계에서 판정 — ADR-0006 §6.1의 7).

대상 파일: `god/src/main/**`(기존 파일 수정·새 파일 추가), contract3 테스트 결함 수정 시 `behavior-tests/src/test/kotlin/com/ccle/behavior/contract3/**`·`support/Db3.kt`, ADR 보완 시 `docs/adr/0007-stage-a-contract.md` §19.

## 2. 경계·불변식 (필수)

- **ADR-0006 §3 불변식 1~7**: 기존 22 테이블 쓰기 금지(새 테이블에만), 기존 21 엔드포인트·8 배치 경로에 SQL 추가 금지(`runBatch`에는 새 이름 분기만), 수정 과제 6개(T1·T2·C1·C2·P1·P2)의 고칠 곳·읽을 곳 함수 내부 무변경(호출만 — `experiment/oracle/{T1,C2}/symbols.god.txt` 범위와 ADR-0005 §3.2의 T2·C1·P1·P2 위치), 본보기 배제(C2 일괄 입찰 취소·T1 저장 시각 소급/상한·T2/C1 규칙 재구현 금지), 오류 code는 기존 것만.
- god 구조 유지: 로직은 `Marketplace.kt` 한 파일(새 서비스 클래스·파일로 로직을 빼지 않는다).
- 동작의 정답은 ADR-0007이다 — contract3는 그 실행형. layered 구현이 같은 SQL을 낼 수 있게 대상 조건·정렬·잠금 순서를 ADR대로(나중 equiv).
- layered·스키마·시드·verify·`experiment/` 무변경.

## 3. 기준소스 (필수)

ADR-0007(+ §19) · ADR-0006 §3 · ADR-0002~0004 · 현행 `god/src/main/**` · contract3 테스트 · `experiment/oracle/{T1,C2}/symbols.god.txt`·ADR-0005 §3.2 · `docker/schema/*.sql`(단계 A DDL).

## 4. 금지영역 (필수)

`layered/`·`docker/`·`tools/seedgen/`·`tools/equiv/`·`experiment/` 변경 금지 · contract·contract2·infra 테스트 변경 금지 · 수정 과제 고칠 곳 함수 내부 변경 금지 · 에이전트(claude) 실험 실행 금지 · study-note 쓰기 금지 · push는 사용자 확인 후.

## 5. 검증 방법 (필수)

- 묶음마다: 그 묶음 contract3 클래스 green + 기존 god `test`·`contract2Test` + verify 120 → 커밋 → 듀얼 1패스(Opus ∥ codex, 렌즈: ADR 일치·불변식(고칠 곳·기존 경로 SQL)·잠금·SQL 동치 가능성·오류 경로).
- 고칠 곳 무변경 기계 확인: 기준 SHA와 현재의 T1·C2 symbols 범위·T2·C1·P1·P2 지점 함수 본문을 줄 번호 무시하고 대조하는 스크립트(log에 결과).
- 기존 경로 SQL 무변경: equiv `all` 12/12.
- 끝에: 전체 contract3 164 + 기존 239 + equiv 12 + 누적 듀얼 리뷰 루프(≤3).
- 테스트 결함 판정은 근거(ADR 절)를 log ledger에 남긴다.

## 6. stakes (필수)

- 판정: **높음** — public API 12개 기능·DB 쓰기(새 테이블)·동시성 잠금·실험 처치(god 크기)와 과제 고칠 곳 보존이 실험 타당성을 좌우.

## 7. 자율성

- [x] auto (사용자 선택 2026-10-29)

## 8. load-bearing 가정

1. 기존 god의 공통 도구(시계·오류·JSON 파싱·키셋 순회 `forEachTarget`·사용자 잠금)를 새 기능이 재사용할 수 있고, 그 재사용이 고칠 곳 함수를 건드리지 않는다 — 묶음 A 착수 직후 고칠 곳 대조 스크립트로 확인.
2. contract3 테스트 대부분이 ADR대로 맞다(3루프 리뷰) — 실패의 대부분은 구현 쪽이고, 테스트 결함은 소수.

## 9. task 분해

| task | 목표 | 의존 | acceptance |
|------|------|------|-----------|
| 01 | 고칠 곳 대조 스크립트(기준 SHA 대비) | — | 기준 = 기준 일치 |
| 02 | 묶음 A 구현(Opus 워커) → green → 커밋 → 듀얼 1패스 → 수정 | 01 | §5 |
| 03 | 묶음 B 〃 | 02 | §5 |
| 04 | 묶음 C 〃 | 03 | §5 |
| 05 | 전체 회귀 + equiv + 누적 듀얼 루프 → 병합(push 확인) | 04 | §1 |
