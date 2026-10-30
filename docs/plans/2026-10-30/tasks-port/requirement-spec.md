# 요구사항 명세서 — T1·C2 이식·재채택 (ADR-0006 §5·§6.1-8)

> 작성일: 2026-10-30 · 작업 폴더: `docs/plans/2026-10-30/tasks-port/` · 브랜치 `impl/tasks-port-stage-a`(main `042edf2`)
> 산출물 유형: **실행물**(experiment/oracle 패치·symbols, 주석 정리 시 앱 소스) + 판정 실측.

## 0. 요구사항 원문 (인터뷰 기록)

- 원문: "병합·push 후 다음으로" → NEXT 다음 착수 = T1·C2 이식·재채택.
- Q/A:
  - 판정 범위 → **정답(fix)·alt-1은 전 단계, none·naive는 숨은 단계만**(ADR-0006 §5 조건에 맞춘 최소).
  - 병렬 → **check 3개 동시**(러너 기본 부하) — 정답 22회의 contract3 결과로 NEXT JR-23(SlowTrigger 경합 테스트 부하 실패율)을 함께 잰다. 실패는 결함/타이밍으로 판정해 기록.
  - 이식 충돌 → **멈추고 보고**(줄 위치 재조정만 이식, 의미 변경이 필요하면 사용자 결정).
  - 자율성 → **auto**.

## 1. 목표·대상 (필수)

다음이 되면 끝:
1. `experiment/oracle/{T1,C2}/`의 `inject.*`·`fix.*`·`alt-1.*`(T1)·`naive-*` 패치가 현재 main 코드(god 5,678·layered 9,970)에 의미 그대로 적용된다(줄 위치만 재조정).
2. `symbols.{god,layered}.txt`를 새 줄 번호로 다시 만든다(새 기능이 과제 규칙을 읽는 곳은 `read`에 넣지 않음). 고칠 곳 대조 스크립트(scratchpad `g7/fixcheck.py`·`l7/fixcheck-layered.py`)의 기준과 symbols가 맞는다.
3. 규칙 서술 주석 grep(ADR-0006 §3의 7·ADR-0005 §3.1) — 저장소 전체(새 코드 포함)에서 과제 규칙을 서술한 주석이 없다(있으면 지운다 — 앱 소스 변경은 주석만).
4. 재채택 실측(고정 SHA·code_dirty False, `runner.py check`, 결과는 scratchpad 결과 디렉토리 — `experiment/results*` 아님):

   | 과제 | 조건 |
   |---|---|
   | T1 | none 숨은 실패 · fix 5/5 전 단계 success · alt-1 전 단계 success · naive-1~7 숨은 실패 (두 구조 각각) |
   | C2 | none 숨은 실패 · fix 5/5 전 단계 success · naive-1~10 숨은 실패 (두 구조 각각) |

   정답의 회귀에는 contract3 252 포함. 정답이 contract3 기대값을 바꾸면 갱신 가능한 것은 갱신, 아니면 메서드 단위 사유로 `regression-exclude`(ADR-0005 §3.3).
5. ADR-0006 §8 위협 12(T1 거리 비대칭)·13(C2 잠금)을 새 줄 수로 다시 재서 기록. ADR-0005·0006에 재채택 결과 기록.
6. 부하 실패율(JR-23): 정답 22회 판정의 contract3 실패 중 SlowTrigger '경합 미관측' 등 타이밍 실패 건수.

## 2. 경계·불변식 (필수)

- `TASK.md`·공개 테스트·숨은 테스트(cases/*·oracle/*/hidden) 무변경(ADR-0006 §5).
- 패치 이식은 줄 위치 재조정만 — 의미(바꾸는 동작·고칠 곳 범위) 변경 금지. 필요해지면 멈추고 보고.
- 앱 소스(god·layered)는 규칙 서술 주석 삭제 외 변경 금지. 행동 테스트·equiv·스키마 무변경.
- 기존 결과 디렉토리(`experiment/results*`) 무변경·재판정 금지.

## 3. 기준소스 (필수)

ADR-0005(§3.1·§3.2·§3.3·§4.1) · ADR-0006 §3·§5·§6.1·§8 · `experiment/oracle/{T1,C2}/design.md` · 현행 main 코드 · 지난 채택 기록(`docs/plans/2026-10-26/tasks-redesign/`·`tasks-a-judge/` log).

## 4. 금지영역 (필수)

`experiment/cases/**`·`experiment/oracle/*/hidden/**`·`experiment/results*/**` · behavior-tests·tools/equiv·docker·seedgen · 앱 로직(주석 외) · 에이전트(claude) 실험 실행 금지(check는 claude 없음) · study-note 쓰기 금지 · push는 사용자 확인 후.

## 5. 검증 방법 (필수)

- 패치마다 `patch --dry-run` 적용 확인 → 이식 diff가 줄 위치 외에 바뀌지 않았는지 대조(옛 패치와 새 패치의 +/− 줄 내용 비교 스크립트).
- symbols: 새 줄 범위가 같은 함수·블록을 가리키는지(함수 시그니처·본문 해시 대조).
- 실측 행렬(§1의 4) 결과 JSON(checks.jsonl) 집계 표.
- 리뷰: 듀얼 1패스(이식 diff·symbols·주석 grep) + 실측 뒤 결과 해석 셀프체크.

## 6. stakes (필수)

- 판정: **높음** — 과제 정의(정답·오답 판별력)가 실험의 측정 도구.

## 7. 자율성

- [x] auto (사용자 선택 2026-10-30)

## 8. load-bearing 가정

1. 단계 A·보충은 고칠 곳 함수를 건드리지 않았으므로(fixcheck 25/25·33/33) 패치 hunk 문맥이 대부분 그대로 맞는다 — 첫 dry-run으로 확인.
2. 단계 A 새 기능이 T1·C2 정답 동작과 부딪히지 않는다(누적 리뷰 hunk 18개 대조 충돌 0) — 정답 실측의 contract3로 확인.

## 9. task 분해

| task | 목표 | 의존 | acceptance |
|------|------|------|-----------|
| 01 | 패치 dry-run·이식(줄 위치) + 의미 대조 | — | 전 패치 적용, 의미 diff 0 |
| 02 | symbols 재생성 + fixcheck 기준 정합 + 주석 grep | 01 | 대조 통과 |
| 03 | 듀얼 1패스 → 커밋 | 02 | 지적 반영 |
| 04 | 재채택 실측 행렬(3 동시) | 03 | §1의 4 |
| 05 | 위협 12·13 재측정·ADR 기록·완료 → 병합(push 확인) | 04 | §1 |
