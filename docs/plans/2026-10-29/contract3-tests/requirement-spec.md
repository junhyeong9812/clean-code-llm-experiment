# 요구사항 명세서 — contract3 행동 테스트 (ADR-0007 A1~A12)

> 작성일: 2026-10-29 · 작업 폴더: `docs/plans/2026-10-29/contract3-tests/` · 브랜치 `test/contract3`(main `a025ed9`)
> 산출물 유형: **실행물**(테스트 코드·gradle 태스크·실행 스크립트). ADR-0006 §6.1의 2.

## 0. 요구사항 원문 (인터뷰 기록)

- 원문: "이대로 채택하고 push한 뒤 계속 진행해줘" → ADR-0006 §6.1 다음 단계 = `contract3` 행동 테스트.
- Q/A:
  - 밀도 → **기존과 같은 밀도**(엔드포인트·배치마다 성공, 오류 경로, 경계값, 잠금 경합, 배치 격리 — 약 100~130개 예상. contract2 = 82개·2,712줄).
  - 작성자 → **Opus 워커 3개 병렬**(묶음: 정산 계열 A1·A2·A3·A7 / 알림·스캔 계열 A4·A5·A11 / 기타 A6·A8·A9·A10·A12). 공용 도우미는 메인이 먼저 만든다.
  - 범위 → **테스트 + gradle 태스크만**(스키마·시드는 다음 작업. 지금은 새 테이블이 없어 red).
  - 자율성 → **auto**.

## 1. 목표·대상 (필수)

`behavior-tests`에 `contract3` 패키지가 생겨 ADR-0007의 모든 엔드포인트·배치 계약을 테스트하고, `contract3Test` gradle 태스크와 `tools/behavior/run.sh`로 돌릴 수 있으면 끝. 대상: `behavior-tests/src/test/kotlin/com/ccle/behavior/contract3/**`(새), `support/`에 새 도우미 파일(`Db3.kt` 등 — 새 파일만), `support/FakePgExtension.kt`의 태그 애노테이션 추가(`@Contract3Test`), `behavior-tests/build.gradle.kts`(태스크 등록·직접 호출 목록·env 검사·기존 태스크의 contract3 제외), 필요하면 `tools/behavior/run.sh` 사용법 주석.

## 2. 경계·불변식 (필수)

- **기존 테스트 무변경:** contract·contract2·infra 테스트 파일과 기존 support 도우미의 동작을 바꾸지 않는다(새 함수 추가는 새 파일에). 기존 `contractTest`·`contract2Test`·`infraTest`가 고른 테스트 집합이 그대로다(contract3를 잘못 집어가지 않는다).
- **테스트는 ADR-0007(+ ADR-0002~0004)만 근거로 쓴다**(blind — 아직 구현이 없다). 기대값은 문서가 유일하게 정한 값만 단언한다. 문서가 열어 둔 것(메시지 문구, `processed` 정확값)은 단언하지 않는다.
- **자기 행만 단언**·직렬 실행·시계는 앞으로만(기존 관례 — ADR-0003 §7, ADR-0004 §14). 테스트 끝 verify 1회는 기존 run.sh 흐름 그대로.
- **T1·C2 회귀에 묶이지 않게**(ADR-0007 §18): `confirmed_at`에 기대는 테스트는 `delivered_at + 7일` 안의 구매자 확정 또는 픽스처 직접 지정, 제한 + ACTIVE 입찰 조합 피하기(ADR-0006 §5).
- 앱(god·layered)·스키마·시드·verify·`experiment/`는 바꾸지 않는다.

## 3. 기준소스 (필수)

ADR-0007(채택 `a025ed9`) · ADR-0002~0004 · ADR-0006 §5·§6.1 · 현행 `behavior-tests/`(support·contract2 관례) · `docker/schema/tables.sql`(기존 테이블 픽스처) · `tools/behavior/run.sh`.

## 4. 금지영역 (필수)

`god/`·`layered/`·`docker/`·`tools/seedgen/`·`tools/equiv/`·`experiment/` 변경 금지 · 기존 테스트 파일 수정 금지(위 §1의 애노테이션 1개·gradle 설정만 예외) · 에이전트(claude) 실험 실행 금지 · study-note 쓰기 금지 · push는 사용자 확인 후.

## 5. 검증 방법 (필수)

- 컴파일: `./gradlew :behavior-tests:testClasses`.
- red 확인: `tools/behavior/run.sh god contract3Test`·`… layered contract3Test` — 모든 contract3 테스트가 실패(새 테이블·엔드포인트 없음). 실패 사유를 분류해 기록(테이블 없음 / 404 NO_ROUTE / 그 밖) — "그 밖"이 있으면 테스트 결함 후보로 조사.
- 무회귀: 두 앱에서 `test`(infra→contract)·`contract2Test`가 지금과 같은 수로 통과(211 = 122 + 89 중 해당분) — gradle 태스크 변경이 기존 집합을 바꾸지 않았음을 테스트 수로 확인.
- 테스트 정합성: ADR-0007 절별 커버리지 표(엔드포인트·배치·오류 code·경계값 → 테스트 이름)를 만들어 빠진 계약을 찾는다.
- 리뷰: 높음 — 듀얼 리뷰 루프(Opus ∥ codex, ≤3) + 종합 감사. 테스트 작성 자체가 blind 워커(구현 없음).

## 6. stakes (필수)

- 판정: **높음** — 이후 두 구조 구현의 정답 기준(public 계약의 실행형)이고, 잘못된 기대값은 두 구현을 같은 방향으로 틀리게 만든다. 리팩토링 아님.

## 7. 자율성

- [x] auto (사용자 선택 2026-10-29)

## 8. load-bearing 가정

1. 새 테이블 픽스처를 ADR-0007 §3의 열 이름으로 직접 INSERT하는 도우미(`Db3`)를 지금 써 두면, 다음 작업(DDL)이 그 이름에 맞춰 만들어진다 — DDL 작업 때 `Db3`가 컴파일·실행으로 처음 검증된다(지금은 red). 착수 직후 스모크: `Db3` + 테스트 1개를 god에 돌려 실패 사유가 '테이블 없음'인지 확인.
2. 기존 run.sh·gradle 흐름에 태스크 하나를 더하는 것만으로 contract3을 따로 돌릴 수 있다.

## 9. task 분해

| task | 목표 | 의존 | acceptance |
|------|------|------|-----------|
| 01 | 공용 도우미(`Db3`·`Stage3` 흐름 도우미)·`@Contract3Test`·gradle `contract3Test` + 스모크 테스트 1개 | — | 컴파일, god red 사유 '테이블 없음', 기존 태스크 테스트 수 불변 |
| 02 | 묶음별 테스트 작성(Opus 워커 3 병렬, blind) | 01 | 컴파일, 커버리지 표 |
| 03 | red 확인(두 앱) + 무회귀(두 앱 기존 태스크) | 02 | §5 |
| 04 | 듀얼 리뷰 루프 + 수정 → 커밋·병합(push 확인) | 03 | 종료 조건 |
