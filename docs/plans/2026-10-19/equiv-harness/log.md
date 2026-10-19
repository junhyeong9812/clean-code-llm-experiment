# log — equiv-harness

## 타임라인

| 시각 | 사건 | 결과/결정 |
|------|------|----------|
| 2026-10-19 01:05 | 착수: NEXT N1. 브랜치 `feat/equiv-harness`(main `3f0f8ff`에서). scratchpad/exp 스크립트 9개(386줄) 확인 — lib.sh 공용이나 run-one·pay-one 등은 자체 기동 코드 중복, 포트 15441·18090·15499 하드코딩, 루트 경로 하드코딩 | 인터뷰 시작 |
| 2026-10-19 | 인터뷰 2회(7문항): 동치 7+측정 1·한 명령 자동 판정·4층 비교·현재 통과+차이 검출 검증·bash 정리·비결정 규칙 명시·Opus 워커+중간 | 명세 합의, auto → SPEC=1·MODE=auto |
| 2026-10-19 | 설계 선검증 비대상(stakes 중간) | |
| 2026-10-19 01:10~01:32 | task 01 워커(Opus): lib.sh 162·run.sh 60·scenarios/run.sh 41줄. 포트 15445·18085·15485, 컨테이너 ccle-equiv-db, exit 0 같음/1 차이/2 실행 실패/130 중단, 층마다 파일·줄 수 출력·빈 파일 실패 | HEAD run 2회 exit 0(응답 18·행 33·SQL 101·DB 문장 141줄 같음). before-L2 jar → exit 1, DB 문장 층만 +BEGIN READ ONLY 17·+COMMIT 17(layered-stage1 기록과 일치). 없는 jar·망가진 jar·실행 중 kill·SIGINT(130, 정리 확인) 모두 실패 판정. 가정 1 성립 |
| 2026-10-19 | 워커 보고: 부하 시 god·layered DB 문장 147 대 148 — 풀 검사 빈 문장(앱 코드 무관). 출발 스크립트도 이 문장을 비교했음 | 사용자: "빈 문장 제거 규칙 추가" → 재합의 1(명세 §2). 메인 확인: compare_layers 빈 디렉터리·빈 파일 실패 경로 열람 → 커밋. 가정 2(폴링 접기)는 task 02 fail 시나리오에서 |
| 2026-10-19 09:12~10:08 | task 02 워커(Opus): pay·batch·recov·fail·conc·errors + measure-expiry.sh + README(844줄 합계). conc SIGPIPE(exit 141 — `sed\|head -1`, awk `exit`) 실행 중 수정 | HEAD all 2회 exit 0(10분 24·25초, 7종 4층 같음, 빈 비교 0, 정리 확인). pay SQL 240 = stage1 기록, DB 문장 341 = 348 − 빈 문장 7. measure N=1: god 13.51초·layered 13.47초(EXPIRED 1132 둘 다) |
| 2026-10-19 | 가정 2 실증(fail, 임시 변조 jar 3개 — layered 원복·`git status` 확인): m1 접는 구간 밖 +1문 → exit 1 · m2a 구간 안 블록에 +1문 → exit 1 · m2b 구간 안 스냅샷 읽기 2회 → **exit 0(못 잡음)** | **가정 2 부분 반증** — 반복 단위가 통째로 늘어나는 변경은 폴링 횟수와 구분 불가. README 한계로 기록. 메인 확인: 금지영역 diff 0, 잔여 컨테이너·java 0, README 규칙표 = lib.sh 규칙 함수 일치 → 코드 커밋 |
| 2026-10-19 | 워커 OQ: conc의 SQL 두 층을 동시 구간부터 잘라냄(`norm_cut_from_mark`) — 명세 §2 비결정 목록(응답 순서·주문 id 배정)보다 넓음 | 사용자 판단 요청(m2b 한계 수용 여부와 함께). conc N=30 기본 유지(메인 판단 — 시나리오 약 2분 50초) |
| 2026-10-19 | 사용자 결정: m2b 한계 "한계로 기록하고 수용" · conc SQL "문장 종류 집합 비교 추가" | 명세 §2 재합의 2·3. conc 집합 비교 구현을 task 03 앞에 붙여 같은 워커에 브리핑 |
| 2026-10-19 11:15~11:55 | task 02b·03 워커(Opus): `norm_cut_from_mark` → `norm_set_from_mark`(표식 뒤 `LC_ALL=C sort -u`). §5 검증 전부 기대대로 — 응답(m3 message)·행(m4 createdAt)·SQL(fail m1)·DB 문장(before-L2, 141→175)·errors message(6f5a3a7) 각 exit 1, 잘못된 jar·FakePg kill·빈 층(`EQ_SQL_LOG=0`)·덤프 0줄 각 exit 2, SIGINT 130(정리 확인, 11초), behavior run.sh god·layered exit 0(135, verify 위반 0), 금지영역 diff 0. 집합 비교 검출: m2a로 conc exit 1(+approveCount) | **마지막 HEAD all exit 1** — conc DB 문장 층만. 메인 확인 `build/equiv/conc/diff/pgstmt.diff`: 본문은 같고 pgjdbc 준비 문장 이름(`S_n`·`<unnamed>`)만 다름(연결별 prepareThreshold 배정이 동시 구간에서 실행마다 다름) → 워커는 규칙 추가 없이 멈춤(지시대로). 사용자 판단 요청 |
| 2026-10-19 | 사용자 결정: "동시 구간에서만 이름 지우기" | 명세 §2 재합의 4. 워커에 반영 + conc 2회 + all 1회 지시 |
| 2026-10-19 12:02~12:23 | task 03b 워커(Opus): `norm_set_from_mark` 표식 뒤에서만 `execute (S_n\|<unnamed>):` → `execute:` 후 `sort -u` | HEAD conc 2회 exit 0(동시 구간 집합 sql 20·pgstmt 30줄 같음), m2a jar conc exit 1(sql·pgstmt 각 +1줄 approveCount만), HEAD all exit 0(656초). 정리·금지영역 diff 0. 메인 확인: lib.sh diff 열람·잔여 java·컨테이너 0 → 코드 커밋. task 03 완료(§5 전부) |
| 2026-10-19 | task 04 리뷰 착수(stakes 중간 = 듀얼 1패스). base `3f0f8ff`(main), packet 경로는 아래 행 | |

## 리뷰 ledger

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|

## 생략한 검증

- (없음)

## 완료 요약
