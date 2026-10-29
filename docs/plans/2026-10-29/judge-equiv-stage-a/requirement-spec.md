# 요구사항 명세서 — 판정기 contract3 연결 + equiv 단계 A 시나리오

> 작성일: 2026-10-29 · 작업 폴더: `docs/plans/2026-10-29/judge-equiv-stage-a/` · 브랜치 `impl/judge-equiv-stage-a`(main `b889129`)
> 산출물 유형: **실행물**(판정기·러너 템플릿·equiv 시나리오) + ADR-0005 §4.1 개정(실행 효력 있는 계약 문서 — 실행물 규칙). ADR-0006 §6.1의 5·6.

## 0. 요구사항 원문 (인터뷰 기록)

- 원문: "병합·push 후 다음으로" → NEXT 다음 착수 = 판정기 contract3 연결 + equiv 새 시나리오.
- Q/A:
  - 나누기 → **한 작업에서 차례로**: 판정기 연결 → equiv 시나리오, 끝에 함께 리뷰.
  - 회귀 단계 → **별도 단계 `contract3` 추가**(STAGES·REGRESSION_BASELINE contract3 = 168·결과 경로 contract3Test, 단계마다 새 시드 DB).
  - 시나리오 단위 → **묶음별 3개 + 동시 잠금 1개**: 묶음 A·B·C(기능별 성공·주요 오류 경로·배치 실행) + §19의 9-1·9-2·10-1·10-2 사전 KEY SHARE 경로 잠금 시나리오. 리뷰가 지목한 우선 확인처(가격 알림 TRIGGERED의 INSERT→UPDATE 순서, deadline-reminder dedupe 식) 포함.
  - 차이 처리 → **layered를 god에 맞춤**(god = SQL 정본). god가 ADR과 어긋난 경우만 god를 고치고 ADR 근거를 log에. 고친 쪽은 contract3 168 재확인. auto, 판정·수정 목록 보고.

## 1. 목표·대상 (필수)

다음이 되면 끝:
1. **판정기**: `experiment/runner/judge.py`의 회귀에 `contract3` 단계(기준 168)가 들어가고, 결과 수집·`regression-exclude`·부분 판정(`--stages`)·success 결합이 contract3까지 일관되게 동작. `templates/judge-stage.sh`·`tools/behavior/run.sh` 필요한 곳 연결. 러너 테스트(`experiment/runner/tests/`) 통과 + contract3 단계 테스트 추가. ADR-0005 §4.1의 4 개정(회귀 = contractTest + contract2Test + contract3Test).
2. **실판정 스모크**: 수정 없는 사본(god·layered 각 1)으로 판정 단계 contract·contract2·contract3을 실제 실행해 기준 122·89·168 통과(claude 실행 없음).
3. **equiv**: `tools/equiv/scenarios/`에 새 시나리오 4개(이름 예: `sa-a`·`sa-b`·`sa-c`·`sa-lock`) + README 표 갱신. `tools/equiv/run.sh all`이 16/16 같음(기존 12 + 새 4). 차이가 나오면 layered를 고쳐 같게 만든다(god가 ADR과 어긋난 경우만 god).
4. 새 시나리오가 실제로 경로를 지났는지(`need`류 확인)와 각 층이 비지 않았는지 확인.

대상: `experiment/runner/{judge.py,runner.py,templates/judge-stage.sh,tests/**}`, `tools/behavior/run.sh`, `tools/equiv/{scenarios/**,README.md,lib.sh(필요 시 정규화 규칙 추가)}`, `docs/adr/0005-*.md` §4.1, 차이 수정 시 `layered/src/main/**`(필요 시 `god/src/main/**`).

## 2. 경계·불변식 (필수)

- 기존 판정 동작 불변: contract·contract2 단계·변조 검사·빌드·공개·숨은 단계의 판정·결과 형식 그대로(기존 러너 테스트 전부 통과). 기존 결과 디렉토리(results-pilot·results-pilot2 등) 재판정 금지·무변경.
- 기존 equiv 12개 시나리오·정규화 규칙의 판정 불변(새 정규화 규칙은 이름 붙여 목록에 추가, 기존 규칙 의미 변경 금지). 새 정규화는 최소로 — 차이를 정규화로 덮지 않는다(차이는 코드로 맞춘다).
- 앱 수정(차이 처리)은 ADR-0006 §3 불변식 그대로: 고칠 곳 대조(god `g7/fixcheck.py`·layered `l7/fixcheck-layered.py`) 통과, layered 허용 목록 검사 통과, contract3 168·기존 239·verify 120.
- 판정 회귀 시간 증가는 contract3 한 단계분만.

## 3. 기준소스 (필수)

ADR-0005(§4.1·§6.4) · ADR-0006 §3·§6.1 · ADR-0007(+ §19) · 현행 `experiment/runner/**`·`tools/equiv/**` · god `Marketplace.kt` 단계 A(SQL 정본) · contract3 테스트(168) · `tools/equiv/README.md`(시나리오·정규화 관례).

## 4. 금지영역 (필수)

`experiment/cases/**`·`experiment/oracle/**`(T1·C2 이식은 다음 작업) · `experiment/results*/**` · `behavior-tests/**`(contract·contract2·contract3·infra 테스트) · `docker/**`·`tools/seedgen/**` · 에이전트(claude) 실험 실행 금지(판정 스모크는 claude 없이) · study-note 쓰기 금지 · push는 사용자 확인 후.

## 5. 검증 방법 (필수)

- 판정기: 러너 단위 테스트 전체 + 새 contract3 테스트 → 실판정 스모크(§1의 2) 결과 JSON에 contract3 단계 168 통과·success 결합 확인.
- equiv: 새 시나리오 각각 단독 실행(같음·층별 줄 수 > 0·경로 확인) → `all` 16/16. 음성 대조 1회: layered의 단계 A 문장 하나(예: 가격 알림 TRIGGERED 순서)를 임시로 바꾼 jar로 차이 검출 확인(커밋 안 함).
- 앱 수정이 생기면: contract3 168 + 기존 239 + verify + fixcheck + allowcheck.
- 리뷰: 듀얼 루프(≤3) — 렌즈: 판정 결합·결과 경로 누락, 기존 판정 불변, 시나리오의 경로 커버리지(ADR 기능·배치 11개·§19 잠금 4경로), 정규화가 차이를 덮지 않나.

## 6. stakes (필수)

- 판정: **높음** — 판정기는 실험 결과(success)를 정하는 계약이고, equiv는 두 구조 동치(실험 타당성)의 유일한 동적 근거.

## 7. 자율성

- [x] auto (사용자 선택 2026-10-29)

## 8. load-bearing 가정

1. contract3 단계를 기존 contract2와 같은 틀(새 시드 DB·앱·가짜 PG, 결과 XML 수집)로 끼울 수 있다 — 착수 직후 실판정 스모크 1회로 확인.
2. 단계 A 기능은 기존 equiv 정규화 규칙(공통 + `norm_pg_tx_id`)만으로 비교된다(새 행 id 오프셋 덤프로 충분) — 첫 시나리오 단독 실행으로 확인, 부족하면 이름 붙인 규칙을 추가하고 log에 근거.

## 9. task 분해

| task | 목표 | 의존 | acceptance |
|------|------|------|-----------|
| 01 | 판정기 contract3 단계 + 러너 테스트 + ADR-0005 §4.1 개정 | — | 러너 테스트 전부 + 새 테스트 |
| 02 | 실판정 스모크(god·layered 무수정 사본) | 01 | contract3 168·success 결합 |
| 03 | equiv 시나리오 sa-a·sa-b·sa-c·sa-lock + README | — | 각각 같음·층 비지 않음·경로 확인 |
| 04 | 차이 처리(있으면) + `all` 16/16 + 음성 대조 | 03 | §5 |
| 05 | 듀얼 리뷰 루프 → 병합(push 확인) | 02·04 | §1 |
