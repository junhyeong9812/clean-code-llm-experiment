# NEXT — 다음 작업 전망

> 프로젝트 롤링 단일 문서. 착수는 새 작업 폴더의 requirement-spec부터. 다음 spec에 옮길 때 실코드로 재확인한다.

## 기준

- 마지막 갱신: 2026-10-21 · 직전 완료 작업: `docs/plans/2026-10-20/behavior-tests-stage2/` · 기준 커밋: `839f11f` + docs · 병합 상태: 대기

## 다음 작업 후보 (우선순위순)

### N1. 갓 클래스 2단계 — 우선순위: 높음 · ADR-0004 계약을 god에 구현해 2단계 테스트 82개를 green으로

- **왜 다음인가:** 계약(ADR-0004)과 테스트(`run.sh <app> contract2Test`, 82개 전부 red)가 준비됐다. 1단계와 같은 순서(god → layered 분리).
- **첫 task(사용자 결정 2026-10-20):** 적립률을 결제 요청 날짜의 등급 이력으로 읽기(ADR-0003 §9의 8, ADR-0004 §8.1). 이것만으로 PointGradeTest 3개가 green이 된다.
- **미검증 경로(테스트 없음 — 구현 task에서 직접 확인):** §8.1 기동 전환 경로의 적립률(계약 테스트는 앱 재기동 불가, equiv recov는 적립액을 비교하지 않음) · §7.2 카드 취소 응답 없음(PG 기록 뒤 지연) · §10.1 정산 행이 하나도 없을 때 분기(근거: `docs/plans/2026-10-20/behavior-tests-stage2/log.md` "생략한 검증").
- **발생 가능한 문제:**
  - 테스트 기대값 자체는 구현이 없어 실행으로 확인한 적이 없다. 첫 green 과정에서 테스트 결함이 드러나면 ADR-0004와 대조해 테스트·ADR 중 어느 쪽인지 판정한다(구현에 맞춰 테스트를 고치지 않는다).
  - 시계를 1년·7개월 옮기는 테스트 뒤 첫 weekly-settlement가 수십 주를 몰아 처리한다 — 배치 시간 초과(120초) 후보.
  - 계약 리뷰에서 나온 항목: 한 주 정산 = 한 트랜잭션, 주문 → 항목 → 사용자(id 순) 잠금, 카드 취소는 항목당 키 하나·금액 확인.
- **동치 확인:** layered 2단계 때 `tools/equiv`에 2단계 시나리오(환불·정산·등급)를 추가해야 비교된다.
- **규모:** ADR-0001 조건(구현당 1만 줄)과의 거리를 god 2단계 뒤 다시 잰다(지금 god 1,672줄).

### N2. 레이어드 2단계 — 우선순위: 높음 · N1 뒤

- layered 명세에서 "변경 여부·값을 정하는 것은 도메인, 조회·저장 순서는 service" 기준을 먼저 적는다(1단계 루프 원인). equiv 2단계 시나리오 추가를 이 작업에 포함할지 착수 때 정한다.

### N3. 1단계 open question 정리 — 우선순위: 낮음 · 계약 공백(사용자 판단)

- god 리뷰가 남긴 질문(근거: `docs/plans/2026-10-17/god-class-stage1/log.md`): 계약 밖 서버 측 실패를 400 `UNEXPECTED_ERROR`로 내는 것 · 배치 대상 1건 실패 시 배치 전체 중단 · PAYING인데 APPROVE 행이 있으면 로그 없이 건너뜀 · 요청 시각을 잠금 전에 읽음 · 포인트 예약이 묶음 전체 적재 · 교차 구매자 같은 키 경합마다 Hibernate ERROR 로그 · 구매 경고 계수 경계(ADR 원문 엄격 >와 생성기 해석 차이). layered는 god와 같은 동작이라 같은 질문을 그대로 갖는다.
- `UNEXPECTED_ERROR` message에 예외 클래스·DB 오류 문구가 그대로 나간다(layered 1단계 트리거 실험에서 관측) — 내부 정보 노출 여부를 이 정리에서 함께 판단.
- 500k 규모에서 두 앱 배치를 돌린 적이 없다. 1k 시드 bid-expiry 1,132건이 약 13.7초(행마다 트랜잭션), 상태·만료 시각 인덱스 없음(스키마 금지 범위).

## 보류·이월

- 동치 하네스 한계(근거: `docs/plans/2026-10-19/equiv-harness/log.md`): 폴링 접기는 반복 단위가 통째로 늘어나는 변경을 못 잡음(재합의 2) · docker 데몬 오류 시 정리 잔여 확인이 "없음"으로 처리(리뷰 N2) · 직렬 시나리오는 준비 문장 이름까지 비교(부하 큰 호스트에서 흔들리면 재합의) · docker 로그 회전 없음 가정 · 재개 조건: 해당 증상이 실제로 관측될 때

- study-note 이슈 아카이브 — study-note 쓰기를 하지 않기로 함(god·layered·equiv-harness·behavior-tests-stage2 네 작업 모두 보류) · 재개 조건: 사용자가 아카이브를 요청할 때
- 리뷰 도구: codex bwrap 오류(`Failed RTM_NEWADDR`)가 god 작업 4+1회, layered 작업 1회 — 재시도로 회복 · 재개 조건: 재시도로도 실패하면 원인 조사
- bid-expiry layered +0.9%(god 대비) — 노이즈 범위로 보고 프로파일링 안 함 · 재개 조건: 과제 러너에서 성능을 지표로 쓸 때

## 완료 이력

- 완료 → `docs/plans/2026-10-20/behavior-tests-stage2/`
- 완료 → `docs/plans/2026-10-19/equiv-harness/`
- 완료 → `docs/plans/2026-10-18/layered-stage1/`
- 완료 → `docs/plans/2026-10-17/god-class-stage1/`
- 완료 → `docs/plans/2026-10-15/behavior-tests-stage1/`
- 완료 → `docs/plans/2026-10-15/schema-and-seed-data/`
- 완료 → `docs/plans/2026-10-13/project-setup/`
