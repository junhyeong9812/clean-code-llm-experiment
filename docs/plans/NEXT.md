# NEXT — 다음 작업 전망

> 프로젝트 롤링 단일 문서. 착수는 새 작업 폴더의 requirement-spec부터. 다음 spec에 옮길 때 실코드로 재확인한다.

## 기준

- 마지막 갱신: 2026-10-30 · 직전 완료 작업: `docs/plans/2026-10-29/judge-equiv-stage-a/`(판정기 contract3 단계·보조 성공, equiv 16종 같음) · 병합 상태: 병합 대기(사용자 확인)

## 다음 작업 후보 (우선순위순)

### N0. 규모 확장 단계 A — 우선순위: **최우선** · 관측 신호: ADR-0006 채택(2026-10-28, 사용자 합의)

- 순서(ADR-0006 §6.1): 계약 ADR-0007(A1~A12 동작 계약) → `contract3` 행동 테스트 → 스키마·verify·seedgen 후처리·1k 새 태그 이미지 → god → layered(새 파일) → 판정기 `contract3` 연결 + ADR-0005 §4.1 개정 → equiv 새 시나리오·기존 12개 재확인 → 크기 확인(god ≥ 5,500) → T1·C2 이식·재채택 → 파일럿 A(`results-pilot3`) → §6.3 사전 등록 규칙 적용.
- 진행: 계약 ADR-0007 **채택**(2026-10-29, §16 [제안] 값 그대로 — 사용자 합의). 12개 한 번에, 기존 오류 code만, ADR-0006 §3의 1 개정 1(layered BatchTarget·KeysetBatchTargets.spec·forEachIsolated internal).
- 진행: `contract3` 행동 테스트 162개 작성(red — 두 앱 모두 라우트·테이블·배치 없음 사유로만 실패, 기존 239 통과). ADR-0007 개정 1 §19(입력 규칙·응답 보완 14항목 [제안]).
- 진행: 스키마·시드·verify 완료 — 새 테이블 19(FK 61)·`tools/seedgen/stage_a.py`(별도 RNG, 닫힌 CSV 재독, 기존 22 CSV 바이트 동일)·verify 120(새 44 — 시드 빌드 전용 17은 `ccle.seed_build` 설정으로만 켬, build-image가 켜짐을 단언)·이미지 `1k-s42-v2`(1k 실측 행 수는 작업 log — ADR §14 추정보다 seller_grades·fee_rebates·monthly_statements 약 65~75%, 예약 110/≈250, shipment_delays 0). contract3 '테이블 없음' 사유 사라짐.
- 진행: god 구현 완료 — contract3 168/168(테스트-ADR 어긋남 0, 리뷰로 동시성 테스트 +4·입력 테스트 +2), 기존 239·equiv 12/12 그대로, 고칠 곳 25/25. 리뷰가 찾은 결함 대부분은 **FK 검사의 KEY SHARE로 생기는 잠금 순서 역전**(교착)이었다 — ADR §19의 9-1·9-2·10-1·10-2.
- 진행: layered 구현 완료 — contract3 168/168(묶음 A·B·C 모두 첫 실행 green, 테스트-ADR 어긋남 0), 기존 239·equiv 12/12 그대로, 고칠 곳 33/33, 기존 파일 변경은 허용 3파일(BatchService·BatchTargets·KeysetBatchTargets)만. 리뷰는 god와의 문장 단위 대조(SQL·잠금·엔티티 19·DTO 34·400 메시지) — 차이 1건(리뷰 작성의 count가 상태 검사보다 먼저, LA-1) 수정, 교착류 결함 0(god에서 고친 잠금 순서를 그대로 옮김).
- 진행: 판정기 contract3 연결 + equiv 단계 A 완료 — 판정 회귀 contract3 단계(기준 168, ADR-0005 개정 3), 단계별 기준 수 검사(regression-exclude가 닿는 단계만 끔), 보조 성공 `success_stage12`(ADR-0006 §6.1-5), 실판정 스모크 두 앱 122·89·168 pass·깬 사본 contract3 fail 집계 확인. equiv 16종(새 sa-a·sa-b·sa-c·sa-lock — 배치 11·격리 3·§19 잠금 4경로·교착 상대 동시 쌍) 같음, **두 앱 차이 0**(정적 리뷰 판정이 동적으로 확인됨), 음성 대조 2종 검출.
- 다음 착수: **크기 확인(ADR-0006 §6.1의 7)** — god 5,228줄 < 5,500. 기능 추가 개정(약 300줄+ — 두 앱·contract3·equiv까지 같은 사이클)인지, 기준 재검토인지 결정부터(새 작업 폴더 spec). 그 뒤 T1·C2 이식·재채택(이식 때 정답 패치가 contract3 회귀를 깨지 않는지 `runner.py check --patch fix` 실측 — C2는 regression-exclude가 contract2 메서드 하나라 contract3 기준 168 검사는 유지됨).
- 파일럿 A 전 확인(이 작업 리뷰 JR-2·JR-4·JR-23): ① `experiment/results*/held`가 비어 있음(재판정이 현재 5단계로 돈다 — 2026-10-30 기준 0) ② 무수정 사본을 `--parallel 3`으로 판정해 contract3 SlowTrigger 경합 관측(8초) 테스트의 부하 실패율 측정 — 0이 아니면 '같은 테스트 재실행 1회 뒤 둘 다 실패해야 fail' 재시도 규칙을 ADR-0005 개정으로 검토(경합 미관측을 infra로 돌리면 잠금을 없앤 진짜 회귀까지 무효가 되므로 그 방식은 기각).
- 구현 때 확인할 [구현 검증]: 새 FK 27(verify 34 → 61) 재계수 · 시드 행 수(§14 표) · verify '시드 빌드에서만' 검사를 켜는 psql 변수 이름 · god 증가 실측(ADR-0007 추정 ≈ 3,390).
- 발생 가능 문제: contract3 162개는 실행으로 한 번도 green이 된 적 없다 — 구현 때 실패가 나오면 ADR과 대조해 테스트 결함인지 먼저 판정(리뷰 3루프에서 정답 구현도 실패할 결함이 루프마다 1~2개씩 나왔다: 예약 수 산수·'없음' 단언 범위·PUT 기본값 덮어쓰기) · contract3 픽스처가 T1·C2 회귀에 묶일 위험(ADR-0007 §18 지침) · 세금계산서 정정의 UPDATE→INSERT 순서(ORM flush, §10.3) · 줄 수 추정(≈3,230)이 외삽이라 5,500 미만이면 묶음 추가 개정 · 판정기 결과 경로가 contract2Test 고정(judge.py 결과 수집부) · Marketplace.kt 4천 줄 전후의 Read·출력 절단(ADR-0006 위협 7) · seedgen 후처리의 CSV 재독 비용(1k는 작음).
- 이월(1차 결론에서): `--max-budget-usd` 중단 미확인 · Bash 해석 휴리스틱 긴 꼬리 · Q3-2·Q3-3 · 앱·테스트 컨테이너 loopback 공유 · app_died 감지 지연.

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
- 단계 A 뒤 재개 조건 추가(2026-10-29 stage-a-schema): 500k를 다시 만들려면 새 CSV부터(새 테이블이 빈 CSV는 시드 빌드 verify 실패) · `stage_a.py`가 큰 테이블을 통째로 읽음(1k 41.8MB → 500k 약 21GB+ 추정 — bids·order_items 스트리밍 필요) · verify 새 검사의 500k 성능 미측정.
- 측정 한계(그대로 승계): 반복 1회·순서 편향, 페이지별 시간 미측정, EXPLAIN은 첫 페이지 리터럴 custom plan, 가짜 PG 지연 0(실 PG면 shipment-deadline이 카드 취소 호출 수 × 지연만큼 더), 같은 달 grade-recalc 재실행 비용 미측정, 포인트 묶음 누적 시나리오 미측정(ADR-0003 §12의 7은 "현 시드 최대 100개에서 0.5초"까지만 근거).

## 보류·이월

- **equiv 2단계가 남긴 것(근거: `docs/plans/2026-10-23/equiv-stage2/log.md` 리뷰 ledger R6·OQ1, 2026-10-24 batch-isolation 반영):** 체결가 < 10원 판매자 귀책을 shipment-deadline 배치로 밟는 경로는 시나리오에 없다(검수 FAIL로만 재현 — 응답은 이제 400 고정 문구, PG 취소는 기록된 채 DB는 롤백). 배치에서는 격리되어 그 항목만 매 실행 `failed`·ERROR로 남는다(ADR-0004 §15의 1 계약 밖 귀결) — 이 배치 경로의 두 앱 동치는 미측정(격리 래퍼는 SQL을 내지 않아 위험은 낮음). 금액 불일치(§7.2 계약 밖)는 결과 상태를 주입해 재현했고 자연 경로(취소 응답 없음 + 다른 항목 환불)는 가짜 PG에 취소 응답 없음 결과가 없어 재현 불가.

- **과제 러너 분석 메모 — layered 2단계가 남긴 것(근거: `docs/plans/2026-10-22/layered-stage2/log.md`):** 재합의 1 — 규칙 위치와 SQL 동치가 부딪히는 곳(§8.1 등급 선택·등급 경계·정산 대상 조건·수수료 floor)은 god SQL과 같게 infra에 둠. 실험 분석(과제 러너) 때 '구조 차이'로 다룰 항목.

- 시드 이미지(2026-10-25 갱신): 500k 이미지 재빌드 완료(`ccle-postgres-seed:500k-s42` 16.6GB, verify 위반 0, 빌드 1:28:48) — 유지 중. 이전 기록: equiv 2단계 착수 시 docker에서 `ccle-postgres-seed:1k-s42`·500k 이미지가 모두 없었다(세션 밖 정리로 추정 — 디스크 6.7G → 54G). 1k는 `tools/seedgen/build-image.sh ~/ccle-csv/1k 1k-s42`로 재빌드(verify 위반 0, 기존 7개 equiv 판정 불변). 500k는 2026-10-25 재빌드됨

- 동치 하네스 한계(근거: `docs/plans/2026-10-19/equiv-harness/log.md`): 폴링 접기는 반복 단위가 통째로 늘어나는 변경을 못 잡음(재합의 2) · docker 데몬 오류 시 정리 잔여 확인이 "없음"으로 처리(리뷰 N2) · 직렬 시나리오는 준비 문장 이름까지 비교(부하 큰 호스트에서 흔들리면 재합의) · docker 로그 회전 없음 가정 · 재개 조건: 해당 증상이 실제로 관측될 때

- **배치 격리가 남긴 것(근거: `docs/plans/2026-10-24/batch-isolation/log.md` 리뷰 ledger OQ2·OQ3·OQ8~OQ10):** ① DB 연결 장애·교착처럼 남은 대상이 모두 실패하는 상황에서 키셋 페이지(≤500)만큼 대상마다 ERROR 스택이 쌓인다(계약대로, 연속 실패 상한 없음) ② equiv `iso`는 bid-expiry·point-expiry·auto-confirm·weekly-settlement만 — payment-due·pg-inquiry·grade-recalc·shipment-deadline 트리거 격리의 SQL 동치는 행동 테스트(응답·DB)만 ③ `/error`·5xx 변환 경로 고정 문구, 한 주 정산 실패 뒤 400·뒤 주 미정산은 테스트 없음(코드 확인만) ④ shipment-deadline 테스트의 `failed ≥ 기저+1`은 기저 측정 뒤 생긴 제3자 실패로도 성립 가능 · 재개 조건: 운영형 관측(로그 폭주)이 필요해지거나 해당 경로를 바꿀 때

- study-note 이슈 아카이브 — study-note 쓰기를 하지 않기로 함(god·layered·equiv-harness·behavior-tests-stage2·god-class-stage2·layered-stage2·equiv-stage2·open-questions·batch-isolation·measure-500k·task-runner-design·runner-smoke 열두 작업 모두 보류) · 재개 조건: 사용자가 아카이브를 요청할 때
- 리뷰 도구: codex bwrap 오류(`Failed RTM_NEWADDR`)가 god 작업 4+1회, layered 작업 1회, god 2단계 2회, layered 단계 A 1회(묶음 C — 판정은 완료), 판정기·equiv 단계 A 1회(3회차 — 판정 완료), layered 2단계 1회(타깃 재점검) — 재시도로 회복. god 2단계에서는 exit 0으로 '판정 불가'를 출력해 종료 코드로는 실패를 못 잡았다(출력 내용으로 판정하게 바꿈) · 재개 조건: 재시도로도 실패하면 원인 조사
- bid-expiry layered +0.9%(god 대비) — 노이즈 범위로 보고 프로파일링 안 함 · 재개 조건: 과제 러너에서 성능을 지표로 쓸 때

## 완료 이력

- 완료 → `docs/plans/2026-10-29/judge-equiv-stage-a/` (판정 회귀 contract3·success_stage12·실판정 스모크, equiv sa-a·b·c·lock — 두 앱 차이 0, 리뷰 3루프 + post-fix 채택 24·기각 3·기록 1)
- 완료 → `docs/plans/2026-10-29/layered-stage-a/` (layered A1~A12, contract3 168, 새 파일 54·허용 3파일, 리뷰 묶음 1패스 ×3 채택 5·기각 1 + 누적 1회차 지적 0으로 종료)
- 완료 → `docs/plans/2026-10-29/god-stage-a/` (god A1~A12, contract3 168, 리뷰 묶음 1패스 ×3 + 누적 3루프 채택 14·기각 1 — 교착 5건 수정)
- 완료 → `docs/plans/2026-10-29/stage-a-schema/` (스키마 19·시드 후처리·verify 120·`1k-s42-v2`·태그 5곳 — verify와 생성기를 맥락 분리 작성, 독립 일치 2회, 리뷰 3루프 채택 18·기각 1)
- 완료 → `docs/plans/2026-10-29/contract3-tests/` (contract3 행동 테스트 162개 red · contract3Test · Db3·SlowTrigger · ADR-0007 개정 1 §19 · 리뷰 3루프 채택 21 + 사용자 결정 보강 1 — SlowTrigger 경합 관측)
- 완료 → `docs/plans/2026-10-28/contract-a/` (ADR-0007 단계 A 동작 계약 채택 — 새 테이블 19·FK 27·배치 11·기존 오류 code만, ADR-0006 개정 1, 리뷰 반영 23)
- 완료 → `docs/plans/2026-10-28/scale-up-design/` (ADR-0006 규모 확장 — 기능 12개·불변식 7·T1·C2 이식·사전 등록 다음 단계 규칙, 리뷰 반영 20)
- 완료 → `docs/plans/2026-10-28/first-conclusion/` (1차 결론 — 사전 결정 문장, 파일럿 8회 관찰, 다음 결정 후보 3)
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
