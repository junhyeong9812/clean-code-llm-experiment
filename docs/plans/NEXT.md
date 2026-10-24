# NEXT — 다음 작업 전망

> 프로젝트 롤링 단일 문서. 착수는 새 작업 폴더의 requirement-spec부터. 다음 spec에 옮길 때 실코드로 재확인한다.

## 기준

- 마지막 갱신: 2026-10-24 · 직전 완료 작업: `docs/plans/2026-10-24/batch-isolation/` · 기준 커밋: `4276972` + code·docs · 병합 상태: 대기

## 다음 작업 후보 (우선순위순)

### N1. 500k 규모 측정 — 우선순위: 낮음 · 관측 신호 없음(1k만 측정)

- 500k 이미지 재빌드(`tools/seedgen/build-image.sh`, 보류 항목 참조)부터. 배치 전부(1k bid-expiry 1,132건 ~13.7초, 정산 최장 ~18s, point-expiry 최장 26.7s)를 두 앱으로 측정.
- 정산 대상 조건(`CONFIRMED` 분기 OR)은 주마다 order_items 순차 스캔 + 행 단위 persist — 500k(order_items 490만)에서 여러 주를 몰아 처리하면 120초를 넘을 수 있다. 상태·만료 시각 인덱스 없음(스키마 금지 범위). 좁히는 최적화는 ADR 조건 문구를 벗어나므로 별도 판단.
- 포인트 예약의 묶음 전체 적재(ADR-0003 §12의 7)도 이 측정에서 다시 본다.

## 보류·이월

- **equiv 2단계가 남긴 것(근거: `docs/plans/2026-10-23/equiv-stage2/log.md` 리뷰 ledger R6·OQ1, 2026-10-24 batch-isolation 반영):** 체결가 < 10원 판매자 귀책을 shipment-deadline 배치로 밟는 경로는 시나리오에 없다(검수 FAIL로만 재현 — 응답은 이제 400 고정 문구, PG 취소는 기록된 채 DB는 롤백). 배치에서는 격리되어 그 항목만 매 실행 `failed`·ERROR로 남는다(ADR-0004 §15의 1 계약 밖 귀결) — 이 배치 경로의 두 앱 동치는 미측정(격리 래퍼는 SQL을 내지 않아 위험은 낮음). 금액 불일치(§7.2 계약 밖)는 결과 상태를 주입해 재현했고 자연 경로(취소 응답 없음 + 다른 항목 환불)는 가짜 PG에 취소 응답 없음 결과가 없어 재현 불가.

- **과제 러너 분석 메모 — layered 2단계가 남긴 것(근거: `docs/plans/2026-10-22/layered-stage2/log.md`):** 재합의 1 — 규칙 위치와 SQL 동치가 부딪히는 곳(§8.1 등급 선택·등급 경계·정산 대상 조건·수수료 floor)은 god SQL과 같게 infra에 둠. 실험 분석(과제 러너) 때 '구조 차이'로 다룰 항목.

- 시드 이미지: equiv 2단계 착수 시 docker에서 `ccle-postgres-seed:1k-s42`·500k 이미지가 모두 없었다(세션 밖 정리로 추정 — 디스크 6.7G → 54G). 1k는 `tools/seedgen/build-image.sh ~/ccle-csv/1k 1k-s42`로 재빌드(verify 위반 0, 기존 7개 equiv 판정 불변). 500k는 재빌드하지 않음 · 재개 조건: 500k 규모 측정을 할 때

- 동치 하네스 한계(근거: `docs/plans/2026-10-19/equiv-harness/log.md`): 폴링 접기는 반복 단위가 통째로 늘어나는 변경을 못 잡음(재합의 2) · docker 데몬 오류 시 정리 잔여 확인이 "없음"으로 처리(리뷰 N2) · 직렬 시나리오는 준비 문장 이름까지 비교(부하 큰 호스트에서 흔들리면 재합의) · docker 로그 회전 없음 가정 · 재개 조건: 해당 증상이 실제로 관측될 때

- **배치 격리가 남긴 것(근거: `docs/plans/2026-10-24/batch-isolation/log.md` 리뷰 ledger OQ2·OQ3·OQ8~OQ10):** ① DB 연결 장애·교착처럼 남은 대상이 모두 실패하는 상황에서 키셋 페이지(≤500)만큼 대상마다 ERROR 스택이 쌓인다(계약대로, 연속 실패 상한 없음) ② equiv `iso`는 bid-expiry·point-expiry·auto-confirm·weekly-settlement만 — payment-due·pg-inquiry·grade-recalc·shipment-deadline 트리거 격리의 SQL 동치는 행동 테스트(응답·DB)만 ③ `/error`·5xx 변환 경로 고정 문구, 한 주 정산 실패 뒤 400·뒤 주 미정산은 테스트 없음(코드 확인만) ④ shipment-deadline 테스트의 `failed ≥ 기저+1`은 기저 측정 뒤 생긴 제3자 실패로도 성립 가능 · 재개 조건: 운영형 관측(로그 폭주)이 필요해지거나 해당 경로를 바꿀 때

- study-note 이슈 아카이브 — study-note 쓰기를 하지 않기로 함(god·layered·equiv-harness·behavior-tests-stage2·god-class-stage2·layered-stage2·equiv-stage2·open-questions·batch-isolation 아홉 작업 모두 보류) · 재개 조건: 사용자가 아카이브를 요청할 때
- 리뷰 도구: codex bwrap 오류(`Failed RTM_NEWADDR`)가 god 작업 4+1회, layered 작업 1회, god 2단계 2회, layered 2단계 1회(타깃 재점검) — 재시도로 회복. god 2단계에서는 exit 0으로 '판정 불가'를 출력해 종료 코드로는 실패를 못 잡았다(출력 내용으로 판정하게 바꿈) · 재개 조건: 재시도로도 실패하면 원인 조사
- bid-expiry layered +0.9%(god 대비) — 노이즈 범위로 보고 프로파일링 안 함 · 재개 조건: 과제 러너에서 성능을 지표로 쓸 때

## 완료 이력

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
