# 요구사항 명세서 — god·layered 동치 비교 하네스 (tools/equiv)

> 작성일: 2026-10-19 · 작업 폴더: `docs/plans/2026-10-19/equiv-harness/` · 브랜치 `feat/equiv-harness`(main `3f0f8ff`)

---

## 0. 요구사항 원문 (인터뷰 기록)

- 원문: "다음 작업 진행하자" → NEXT N1(레이어드 1단계에서 쓴 동치 비교 스크립트를 저장소로)
- 출발 코드: 세션 임시 폴더 `scratchpad/exp/`의 스크립트 9개(386줄 — lib·run-one·pay-one·batch-one·recov-one·fail-one·conc-one·l3-one·expiry-one). 레이어드 1단계 log `docs/plans/2026-10-18/layered-stage1/log.md`에 용도와 결과가 기록돼 있다.
- Q/A:
  - 범위 → **동치 7종 + 측정 1종**: 동치 비교(입찰·결제·배치·기동 복구·반영 실패 주입·같은 키 동시·오류 경로)는 판정 도구, bid-expiry 시간 측정은 별도 측정 도구
  - 판정 → **한 명령으로 자동 판정**: `tools/equiv/run.sh <시나리오|all>`이 god·layered를 차례로 돌리고, 정규화 후 diff, 차이가 있으면 exit ≠ 0, 차이 내용은 파일로
  - 비교 단위 → **응답·새 행·Hibernate SQL·DB 문장 로그(BEGIN/COMMIT/SET 포함) 전부**
  - 도구 검증 → **현재 코드 통과 + 일부러 만든 차이 검출**: HEAD에서 all 통과, 보관된 이전 layered jar와 임시 변조(작업 트리만, 커밋 안 함)로 각 비교 층의 차이를 잡아 실패하는지
  - 형태 → **bash 유지, 정리만**(공통 함수로 중복 축소)
  - 비결정 → **규칙을 코드로 명시**(동시 요청은 응답 분포·키당 주문 수로, 대기 폴링은 반복 문장 접기 등), README에 목록화
  - 주체·리뷰 → **Opus 워커 + stakes 중간(듀얼 1패스)**, 앱 코드 무변경

---

## 1. 목표·대상 (필수)

`tools/equiv/`에 동치 비교 하네스를 두어, `tools/equiv/run.sh all`이 현재 HEAD의 god·layered에 대해 **7개 시나리오 전부 차이 0, exit 0**으로 끝나고, 일부러 만든 차이(층별)에는 **exit ≠ 0과 차이 파일**을 내면 완료. bid-expiry 측정 도구(`tools/equiv/measure-expiry.sh` 류)는 두 앱의 소요 시간을 출력한다.

## 2. 경계·불변식 (필수)

- **판정의 정직성(핵심):** 비교가 실제로 일어나지 않았는데 통과로 끝나는 경로가 없어야 한다 — 앱 기동 실패·덤프가 비었음·SQL/DB 로그 0줄·시나리오 중간 실패는 전부 실패(exit ≠ 0). 비교 대상 파일 수·줄 수를 출력해 빈 비교를 드러낸다.
- **정규화는 명시 규칙만:** 정규화(무시·접기·치환)는 스크립트 안에 이름 붙은 규칙으로만 하고 README에 같은 목록을 둔다. 규칙 밖의 차이는 전부 실패. 레이어드 1단계에서 확인된 비결정만 대상: 가짜 PG `pg_tx_id` 값, 같은 키 대기 폴링 반복 횟수, 동시 요청의 응답 순서·주문 id 배정 순서. 시각·id는 오프셋 정규화(시나리오 시작 시 max(id) 기준)로 비교한다.
- **격리:** 일회용 시드 DB 컨테이너만 쓰고 끝나면 지운다(정상·실패·중단 모두). 포트·컨테이너 이름이 `tools/behavior/run.sh`·개발 DB(15432)와 겹치지 않는다. 시작 전 포트 점유 확인(run.sh와 같은 방식).
- **앱은 jar로 받는다:** 기본은 각 모듈의 bootJar를 빌드해 쓰고, 옵션으로 jar 경로를 바꿀 수 있다(도구 검증용).
- **앱·테스트·스키마 무변경.**

## 3. 기준소스 (필수)

- 동작 정본: `scratchpad/exp/` 스크립트(레이어드 1단계에서 god·layered 동치를 판정한 것)와 그 결과 기록(`docs/plans/2026-10-18/layered-stage1/log.md`).
- 실행 방식 정본: `tools/behavior/run.sh`(시드 DB·가짜 PG·앱 기동·정리), `tools/seedgen/run-db.sh`.
- 계약: ADR-0003(가짜 PG `_control` 사용법은 `behavior-tests/src/test/kotlin/com/ccle/behavior/pg/FakePg.kt`).

## 4. 금지영역 (필수)

- `god/`, `layered/`, `behavior-tests/`, `tools/behavior/`, `tools/seedgen/`, `docker/`, `docs/adr/`, 루트 gradle 파일 — **수정 금지**. 도구 검증의 임시 변조는 작업 트리에서만 하고 반드시 되돌린다(커밋·스태시 금지, 끝나면 `git status` 깨끗함 확인).
- study-note 읽기·쓰기 금지. main 직접 작업 금지. push·원격 생성은 사용자 확인 후.

## 5. 검증 방법 (필수)

- `tools/equiv/run.sh all` 2회 → 둘 다 exit 0, 시나리오별 비교 파일 수·줄 수 출력(빈 비교 없음).
- 도구 검증(차이 검출) — 각각 exit ≠ 0과 해당 층 차이 파일:
  - 응답·행: 임시 변조(예: layered 오류 메시지 또는 금액 계산 1곳)
  - Hibernate SQL: 임시 변조(예: 조회 하나 추가)
  - DB 문장 로그: 보관 jar `scratchpad/jars/layered-before-L2.jar`(트랜잭션 밖 BEGIN READ ONLY 왕복)
  - 오류 경로 message: 보관 jar `scratchpad/jars/layered-6f5a3a7.jar`(영속 예외 변환)
  - 실패 경로: 앱 기동 실패(잘못된 jar 경로)·시나리오 중간 실패 → exit ≠ 0
- 정리 확인: 각 실행 뒤 `ccle-equiv-*` 컨테이너·앱 프로세스 0, 중단(SIGINT) 시에도 정리.
- `tools/behavior/run.sh god`·`layered` 결과 불변(무변경 확인) + 금지영역 diff 0.
- 리뷰: stakes 중간 → 듀얼 1패스(Opus 워커 ∥ codex) + 종합 감사 + post-fix 재점검.

## 6. stakes (필수)

- 판정: **중간** — 검증 도구(조용한 실패 = 거짓 동치 판정 위험), 스크립트·일회용 컨테이너만 다루고 앱·데이터는 안 바꾼다(불가역 없음).

---

## 7. 자율성

- [x] auto (기본 권장) — 사용자: "합의, auto"
- [ ] lazy

## 8. load-bearing 가정

1. **scratchpad 스크립트의 판정이 저장소로 옮겨도 그대로 재현된다.** 경로·포트·jar 위치가 하드코딩돼 있다. 실증: 첫 시나리오(run-one)를 옮긴 직후 HEAD에서 차이 0, 보관 jar로 차이 검출을 먼저 확인.
2. **정규화 규칙이 비결정만 지우고 실제 차이는 남긴다.** 실증: 폴링 접기 규칙이 있는 fail 시나리오에서 임시 변조 1건이 잡히는지 확인.

## 9. task 분해

| task | 목표 | 의존 | acceptance |
|------|------|------|-----------|
| 01 | 공통 lib(기동·정리·포트 확인·덤프·정규화·diff·판정) + run 시나리오 + `run.sh` 진입점, 가정 1·2 실증 | — | run 시나리오 HEAD 차이 0, before-L2 jar로 DB 문장 차이 검출 |
| 02 | 나머지 6종(pay·batch·recov·fail·conc·errors) + 측정 도구 + README | 01 | all 2회 exit 0 |
| 03 | 도구 검증(§5 차이 검출·실패 경로·정리) | 02 | §5 전부 |
| 04 | 리뷰(듀얼 1패스)·마감 | 03 | §5·리뷰 종료 |

---

## 승인 상태

- [x] 필수 6칸 전부 기입
- [x] 사용자 합의 → SPEC=1 (2026-10-19)
- [x] 자율성 선택 → MODE=auto
