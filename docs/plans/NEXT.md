# NEXT — 다음 작업 전망

> 프로젝트 롤링 단일 문서. 착수는 새 작업 폴더의 requirement-spec부터. 다음 spec에 옮길 때 실코드로 재확인한다.

## 기준

- 마지막 갱신: 2026-10-22 · 직전 완료 작업: `docs/plans/2026-10-22/god-class-stage2/` · 기준 커밋: `3637456` + docs · 병합 상태: 대기

## 다음 작업 후보 (우선순위순)

### N1. 레이어드 2단계 — 우선순위: 높음 · god 2단계(contract2 82/82)를 layered로 분리

- **왜 다음인가:** god 2단계가 끝났다(1단계와 같은 순서 god → layered). layered는 2단계가 없어 contract2 82개 전부 red다.
- layered 명세에서 "변경 여부·값을 정하는 것은 도메인, 조회·저장 순서는 service" 기준을 먼저 적는다(1단계 루프 원인). equiv 2단계 시나리오(환불·정산·등급) 추가를 이 작업에 포함할지 착수 때 정한다.
- **god 2단계에서 넘어온 것(근거: `docs/plans/2026-10-22/god-class-stage2/log.md`):**
  - §8.1 적립률: god는 등급 이력 네이티브 쿼리라 Hibernate auto-flush로 `update orders`가 앞당겨진다 — equiv `fail` 시나리오의 point_ledger id 1 밀림이 그 결과(예상 차이). layered가 같은 SQL 순서를 내는지가 동치 비교의 쟁점이 된다.
  - 잠금 순서: 주문 → 항목(FOR UPDATE) → [PG] → 사용자 id 오름차순. 정산은 대상 항목을 모두 잠근 뒤 판매자 FK. layered도 같은 순서여야 교착 분석이 유지된다.
  - 검수 reason 빈 값 판정: god는 Kotlin `isNullOrBlank`(유니코드 공백 포함). ADR은 문자 집합을 정하지 않았다 — layered가 같은 함수를 쓰지 않으면 동치 차이가 난다.
- **규모:** god main 1,672 → 2,648줄(2단계 뒤, Marketplace.kt 1,735줄). ADR-0001 조건(구현당 1만 줄)과 아직 멀다 — layered 2단계 뒤 다시 잰다.
- **발생 가능한 문제:** 2단계 god diff(+984줄)를 그대로 옮기면 service가 비대해진다 — 1단계처럼 리뷰 3루프 상한에 닿을 수 있다.

### N2. 1·2단계 open question 정리 — 우선순위: 낮음 · 계약 공백(사용자 판단)

- god 리뷰가 남긴 질문(근거: `docs/plans/2026-10-17/god-class-stage1/log.md`): 계약 밖 서버 측 실패를 400 `UNEXPECTED_ERROR`로 내는 것 · 배치 대상 1건 실패 시 배치 전체 중단 · PAYING인데 APPROVE 행이 있으면 로그 없이 건너뜀 · 요청 시각을 잠금 전에 읽음 · 포인트 예약이 묶음 전체 적재 · 교차 구매자 같은 키 경합마다 Hibernate ERROR 로그 · 구매 경고 계수 경계(ADR 원문 엄격 >와 생성기 해석 차이). layered는 god와 같은 동작이라 같은 질문을 그대로 갖는다.
- `UNEXPECTED_ERROR` message에 예외 클래스·DB 오류 문구가 그대로 나간다(layered 1단계 트리거 실험에서 관측) — 내부 정보 노출 여부를 이 정리에서 함께 판단.
- 500k 규모에서 두 앱 배치를 돌린 적이 없다. 1k 시드 bid-expiry 1,132건이 약 13.7초(행마다 트랜잭션), 상태·만료 시각 인덱스 없음(스키마 금지 범위).
- **god 2단계 리뷰가 남긴 질문(근거: `docs/plans/2026-10-22/god-class-stage2/log.md` 리뷰 ledger):** 2단계 배치(shipment-deadline 등)도 PG_UNAVAILABLE 외 예외면 배치 전체 중단 — 늘 실패하는 대상 하나가 뒤 대상을 막는다(위 '배치 대상 1건 실패 시 중단'과 같은 질문) · 늦게 승인된 주문(pg-inquiry·기동 전환)은 승인 시점에 이미 `paid_at + 3일`이 지나 있을 수 있어 다음 shipment-deadline이 발송 기회 없이 판매자 귀책(ADR 문자대로) · PG 취소 성공 뒤 커밋 예외(연결 끊김)는 §7.2 계약 밖과 같은 종류로 해석 · 검수 reason 공백 문자 집합.
- **규모:** 정산 대상 조건(`CONFIRMED` 분기 OR)은 주마다 order_items 순차 스캔 + 행 단위 persist — 1k에서 최장 2.4s지만 500k(order_items 490만)에서 수십 주 몰아 처리하면 120초를 넘을 수 있다. point-expiry는 1k 최장 26.7s(묶음당 ~12ms). 좁히는 최적화는 ADR 조건 문구를 벗어나므로 별도 판단.

## 보류·이월

- 동치 하네스 한계(근거: `docs/plans/2026-10-19/equiv-harness/log.md`): 폴링 접기는 반복 단위가 통째로 늘어나는 변경을 못 잡음(재합의 2) · docker 데몬 오류 시 정리 잔여 확인이 "없음"으로 처리(리뷰 N2) · 직렬 시나리오는 준비 문장 이름까지 비교(부하 큰 호스트에서 흔들리면 재합의) · docker 로그 회전 없음 가정 · 재개 조건: 해당 증상이 실제로 관측될 때

- study-note 이슈 아카이브 — study-note 쓰기를 하지 않기로 함(god·layered·equiv-harness·behavior-tests-stage2·god-class-stage2 다섯 작업 모두 보류) · 재개 조건: 사용자가 아카이브를 요청할 때
- 리뷰 도구: codex bwrap 오류(`Failed RTM_NEWADDR`)가 god 작업 4+1회, layered 작업 1회, god 2단계 2회 — 재시도로 회복. god 2단계에서는 exit 0으로 '판정 불가'를 출력해 종료 코드로는 실패를 못 잡았다(출력 내용으로 판정하게 바꿈) · 재개 조건: 재시도로도 실패하면 원인 조사
- bid-expiry layered +0.9%(god 대비) — 노이즈 범위로 보고 프로파일링 안 함 · 재개 조건: 과제 러너에서 성능을 지표로 쓸 때

## 완료 이력

- 완료 → `docs/plans/2026-10-22/god-class-stage2/`
- 완료 → `docs/plans/2026-10-20/behavior-tests-stage2/`
- 완료 → `docs/plans/2026-10-19/equiv-harness/`
- 완료 → `docs/plans/2026-10-18/layered-stage1/`
- 완료 → `docs/plans/2026-10-17/god-class-stage1/`
- 완료 → `docs/plans/2026-10-15/behavior-tests-stage1/`
- 완료 → `docs/plans/2026-10-15/schema-and-seed-data/`
- 완료 → `docs/plans/2026-10-13/project-setup/`
