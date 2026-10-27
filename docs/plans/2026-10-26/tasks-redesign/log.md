# log — 과제 재설계(난이도) + Bash 편집 인식

## 타임라인

| 시각 | 사건 | 결과 |
|---|---|---|
| 2026-10-26 20:56(date) | 착수: 직전 `tasks-a-judge` 파일럿 게이트 미달(턴 중앙값 god 6·layered 5.5 < 10, 4회 모두 Bash만) → 사용자 결정(과제 재설계 · Bash 편집 인식). 브랜치 `feat/tasks-redesign`(main `5131584`). 인터뷰 시작 | 인터뷰 |
| 2026-10-27 00:51(date) | 명세 합의("합의, auto 진행") → set-state spec-approved · mode auto | task 01 착수 |
| 2026-10-27 00:53(date) | task 01 가정 1 스모크: 시제품 파서(heredoc 본문 분리 + cwd 추적(`cd`) + sed -i·리다이렉트·python ast(open 쓰기 모드·write_text, 변수 대입·함수 인자 추적))를 파일럿 이벤트 4개에 적용 | **실증** — 4/4 편집 턴 4, 대상 파일 집합이 실행 diff `+++` 목록과 정확히 일치(T1 각 1파일, C2 god 3·layered 5(새 파일 포함)), unknown 0. 부수 발견: ①현 `bash_edit`는 heredoc 본문 줄을 셸로 파싱해 오탐 위험(C2 first_edit_turn=4는 우연 일치) ②읽기 지표(`sed -n`·`cat`·`grep` 경로)도 `cd` 뒤 상대 경로를 못 풀어 first_targeted_turn·full_read_turns 가 3/4 빈 값 — 명세 범위(편집) 밖이라 사용자 확인 |
| 2026-10-27 00:57(date) | 사용자 결정: 읽기 지표에도 cwd 추적 적용("읽기에도 적용 (권장)") → 명세 §1-2 범위 확장 기록 | 구현 착수 |
| 2026-10-27 01:03(date) | task 01 구현 커밋 `c11ecc7`(events.py: heredoc 분리·cwd 추적·bash_writes·py_write_targets·metrics(existing) / runner.base_files / 옛 계약 테스트 3개 갱신 + test_bash_edits 12개). 파일럿 4개 재계산: 편집 턴 4·first_edit_hit True·via Bash 4/4, C2 layered 새 파일 1개 created_paths, unknown 0, first_targeted_turn 4/4 값(이전 1/4), full_read_turns 3/4 값(이전 0/4) | 단위 308 OK · 실데이터 손 대조 일치 |
| 2026-10-27 01:07(date) | blind 테스트 워커(Opus, 구현 미열람) 회수: 93개 중 92 통과, 실결함 1 — `patch -i 파일`의 입력 파일을 편집 대상으로 셈 → `PATCH_VALUE_OPTS`로 수정, 커밋. 모호점 1: Bash `sed -n` 겨냥은 결과 출력이 있어야 셈(개정 1부터의 기존 동작, 빈 출력 = 범위 없음) → 유지, 수용 | 401 OK |
| 2026-10-27 01:07(date) | ADR-0005 §5.2 첫 편집 적중 문단 개정 2 초안(Bash 쓰기 대상·작업 위치·파일 단위 적중·새 파일·모름) — 커밋은 task 04 | 문서 |
| 2026-10-27 01:09(date) | task 02 T1 후보 탐색 워커(Opus) 회수: 4개 — ①확정 시각 상한(구매자 확정 min(now, 배송+7일)·자동 확정 소급, 2 대 2, 증상=정산 주·분쟁 기한, 오진 미끼=정산 주 +14일·cutoff 배타) ②만료 포인트 사용 가능(2 대 2, 옛 T1 쿼리와 겹침) ③만료 입찰 매칭(약한 미끼) ④정산 cutoff(비추천). 메인 교차 확인: ①의 god `:876-877`·`:1504-1508`, layered `Order.kt:324-327`·`:344-347` 실코드 일치. 쟁점: layered 두 지점이 같은 클래스 20줄 간격·상수 공유, god 630줄 간격(T2와 같은 거리 비대칭) | C2 후보 대기 |
| 2026-10-27 01:13(date) | C2 후보 탐색 워커(Opus) 회수: ①제한 시 활성 입찰 취소(추천) ②카테고리별 발송 기한 ③만료 임박 입찰 매칭 제외. 메인 교차 확인: god `:954-966`·`:1395-1418`, layered `User.kt:59-74`·`BatchService.kt:160-172`·`ItemRefunder.kt:78-85` 실코드 일치. 사용자 결정: T1 = 확정 시각 상한("채택, 비대칭은 기록만") · C2 = 제한 시 입찰 취소(처치 효과 과제 표시 유지) | 설계 브리프 작성 |
| 2026-10-27 01:13(date) | 설계 선검증 착수: 브리프(scratchpad `design-brief.md` — 수단 배치 표·주입·naive·숨은 테스트) → 읽기 전용 미러(HEAD 0fe3f36 git archive, docs/plans·measurement-log 제외, 보안 스캔 = 테스트 가짜 토큰만) → codex(high) ∥ Opus 워커 병렬 | 회수 대기 |
| 2026-10-27 01:20(date) | 설계 선검증 회수: Opus F1~F8(major 5·minor 3) · codex R1~R7(blocker 2·major 4·minor 1). 종합 → 브리프 '선검증 반영' 절 추가(T1 합류점 대안 정답·주석 중립화·분쟁 시계 순서 / C2 숨은 시나리오 S1·S2·B·N·R 확정, 이벤트 4필드·만료 ACTIVE 포함, PenaltyTest:90 제외 + 숨은 테스트로 계약 보존, TASK 계약 문장(기존 제한 규칙과 관계·잠금), naive 9개). blocker R3(잠금)은 설계 결정으로 해소: TASK에 사이즈 잠금 명시 + 교착 가능성은 판정 불가 → 위협 기록(두 구조 같은 조건) | ledger 기록 |
| 2026-10-27 01:21(date) | task 03 착수: 작성 워커 2개(Opus) 병렬 — T1·C2 각각 공개·oracle·숨은 테스트·naive 작성 + check 채택 실측(워커 내부 직렬). 선검증 미러 삭제 | 회수 대기 |
| 2026-10-27 01:33(date) | task 04 ADR-0005 개정 2 초안(머리말 · §3.2 T1·C2 옛 행 취소선 + 새 행 · §5.2 첫 편집 적중 · §7.3 2회차 파일럿·재미달 시 결론 · §9 위협 9 주석·12 T1 지점 거리·13 C2 잠금). 채택 실측 결과(줄 번호·naive 수)는 워커 회수 뒤 맞춘다 | 미커밋 |
| 2026-10-27 03:40(date) | 메인 누출 점검(주입 사본 grep — TASK.md 핵심어): T1 layered `Order.kt:321`·`:340` KDoc에 '확정 시각' 꼬리표가 남아 그 낱말 grep이 두 고칠 곳에 바로 닿음, god는 같은 grep에 고칠 곳 없음 → 비대칭 단서(layered 유리). C2 TASK.md 위치 단서 없음 | M-1 등록, T1 워커 회수 뒤 inject 주석 수정 |
| 2026-10-27 03:44(date) | T1 작성 워커 회수: 두 구조 none 숨은 실패 · fix 5/5(1회 전 단계 contract 122·contract2 89·verify 0) · naive-1~5 숨은 실패(naive-2·4는 공개 통과) · alt-1(합류점) 통과. code_sha 0fe3f36 dirty. 워커 제기: naive-5는 weekly-settlement 400으로 실패(주입 남아 A도 실패 → 수용), layered markConfirmed만 자르는 변형은 적립 만료가 틀린 채 숨은 통과 가능 → M-2 채택. 후속 지시(SendMessage): M-1 주석 중립화 · 숨은 A·B에 EARN expires_at 단언 · naive-6 · 부분 재확인 | 후속 대기 |
| 2026-10-27 03:55(date) | C2 작성 워커 회수: 두 구조 none 숨은 4/6 실패(N·재계수는 기준에서도 통과 — 설계대로) · fix 5/5(1회 전 단계 contract 122·contract2 88(제외 1)·verify 0) · naive-1~10 전부 숨은 실패(두 구조 같은 단언). layered 6파일(god 1) — 처치 효과 표시. 메인 확인: regression-exclude 1메서드·사유, god fix(`cancelActiveBids` 사이즈 잠금→refresh→ACTIVE만 취소, `:966`·`:1403` 호출, 재매칭 전) 실파일 일치 | T1 후속 대기 |
| 2026-10-27 04:21(date) | T1 후속 회수: M-1 layered 주석 3곳 중립화(핵심어 grep 두 구조 0 — '7일'은 가드 서술로 god 3·layered 7, 비대칭 기록) · M-2 숨은 A·B에 EARN expires_at 단언 + naive-6(두 구조 숨은 실패, 공개 통과) · none/fix/alt-1/naive-4 재확인 기대대로 → M-1·M-2 fixed. 과제 커밋 `42fd0b1`(56 files) | — |
| 2026-10-27 04:21(date) | task 05 리뷰 루프 1 착수: BASE `5131584` vs 작업트리(ADR 미커밋 포함), packet = spec 원문 + 누적 diff 6,710줄 + 관련 심볼 grep 원시, 미러 `scratchpad/rv1/mirror`(ledger·measurement-log·NEXT·작업 폴더 제외, 시크릿 스캔 0). codex(high) ∥ Opus 병렬 | 회수 대기 |
| 2026-10-27 04:31(date) | 리뷰 루프 1 회수·종합: codex blocker 2(재채택·파일럿 — 계획된 다음 단계, 범위 밖)·major 3 / Opus major 4·minor 5. 채택 8(파서 5·문서 2·실데이터 테스트 1) → 코드 `47e7d49`(409 OK, 수정 확인 테스트 8 + 파일럿 명령 고정본), ADR §5.2 모름·해석 주의·§9-12 T1 제외 민감도 | 루프 2 착수 |
| 2026-10-27 04:33(date) | 루프 1 codex 종합 감사(medium): R1-6 'fixed' → 승인된 예외로 재분류 지적 1건 → 반영(user-deferred). 그 밖 이상 없음 | 루프 2 착수 |
| 2026-10-27 20:19(date) | 리뷰 루프 2 회수·종합: codex blocker 1(재채택·파일럿 — 범위 밖)·major 3·minor 2 / Opus major 3·minor 2. 사용자 결정: T1 합류점 함정 비대칭 "기록만". 파서 경계 사례가 루프마다 새로 나옴(같은 결함 재발 아님 — 정지 규칙 비해당) → 개별 수정 + 구조적 안전망(실행 뒤 diff 대조 missed_edit_paths) 도입. 코드 `f4a5b8b`(416 OK), 파일럿 4회 재계산 편집 턴 4·모름 0·누락 0 | 루프 2 감사 |
| 2026-10-27 20:22(date) | 루프 2 codex 종합 감사(medium) 재검토 후보 5 → R2-6 채택(가드 서술 중립화, 커밋 T1 oracle) · R2-7 규칙 유지 + ADR 명시 · R2-8 상태 '탐지 안전망' · R2-10/R1-9/R1-10 '후속 단계 대기(open)' · R2-1 보수 처리 유지(잔여 위험 기록). T1 패치 16개 새 주입에 dry-run 적용 OK | 루프 3 착수 |
| 2026-10-27 20:32(date) | 리뷰 루프 3 회수·종합: codex major 2·minor 2 / Opus major 3·minor 1 — 과제(T1·C2) 쪽 새 결함 없음(codex minor: T1 이른 확정 숨은 사례), 나머지 전부 Bash 해석·적중 정의. 채택 → 코드 `8eeea6b`(423 OK), ADR §5.2(first_edit_hit_fix·갈래 쓰기·모르는 위치 유지), T1 이른 확정 숨은 사례 + naive-7 은 T1 워커에 위임(진행 중). 루프 상한 3 도달 | post-fix 재점검 |
| 2026-10-27 20:37(date) | post-fix 재점검(codex high, 루프 3 수정분): 회귀 2(산술 경로·case 안 서브셸) → 수정·테스트(424 OK) 커밋. 루프 상한 3 도달 — 종료식 미충족(루프 3 신규 채택 7, open R3-6 위임 중) → review.md 규칙상 'review unresolved' 보고 대상: 남은 위험 = Bash 해석 휴리스틱의 긴 꼬리(missed_edit_paths·edit_unknown_turns 로 가시화), 과제 쪽은 루프 3 에서 신규 결함 0(R3-6 제외) | 사용자 보고 |
| 2026-10-27 22:20(date) | 사용자 결정(리뷰 상한): "잔여 위험 수용, 진행" → 리뷰 종료 상태 = review unresolved 를 사용자가 잔여 위험 수용으로 종결(Bash 해석 휴리스틱의 긴 꼬리 — missed_edit_paths·edit_unknown_turns 로 가시화). T1 워커 회수(R3-6 fixed) → 커밋 `ae6deda`. 최종 채택 실측 착수: SHA `ae6deda` 고정(실측 끝까지 커밋 금지), 큐 2개 병렬(T1: none·fix 1 전 단계 + 4 공개·숨은·alt-1·naive-1~7 × 2구조 = 28, C2: none·fix 5·naive-1~10 × 2구조 = 32) | 실측 중 |
| 2026-10-28 00:50(date) | **최종 채택 재실측 완료(code_sha ae6deda, dirty 0 — 60행, 22:24~00:49)**: T1 두 구조 none 숨은 2/2 실패 · fix 5/5(전 단계 1: contract 122·contract2 89·verify 0) · alt-1 통과 · naive-1~7 전부 숨은 실패. C2 두 구조 none 숨은 4/6 실패 · fix 5/5(contract2 88) · naive-1~10 전부 숨은 실패. 변조 0·build 실패 0·잔여 컨테이너 0, naive마다 두 구조 숨은 실패 수 동일 → **T1·C2 채택 확정**(R1-9·R2-10 재채택 부분 닫힘). design.md 표 추가 | 파일럿 |
| 2026-10-28 00:50(date) | task 06 파일럿 착수: `run --model sonnet --cases T1,C2 --reps 1 --parallel 2 --results-dir experiment/results-pilot2`(본 표본과 분리, 승인 4회, 코드 b511652) | 실행 중 |
| 2026-10-28 01:27(date) | **2회차 파일럿 완료(4/4, results-pilot2, 00:50~01:25)**: 전부 valid·success·회귀 통과(contract 122·contract2 89/88)·위반 0·변조 0·censored 0·end normal. 턴(message.id) T1 god 6·layered 5, C2 god 5·layered 6 → **구조별 턴 중앙값 god 5.5·layered 5.5 < 10 → ADR §7 게이트 재미달**(나머지 3조건 충족). 토큰 T1 102,288/94,534(god/layered), C2 98,924/165,724. 명목 비용 합 $0.416(T1 0.053·0.110, C2 0.103·0.151), 실행당 벽시계 11~12분(판정 포함, 에이전트 135~155초). 도구 호출 4~5회, 전부 Bash. 편집 지표: first_edit_turn 4~5 · via Bash · first_edit_hit True 4/4 · first_edit_hit_fix None(Edit 도구 0) · 모름 0 · **누락(missed_edit_paths) 0** · C2 layered 6파일(새 파일 1 created). 첫 겨냥 턴 4/4 = 2. 관찰: 4실행 모두 TASK.md·test.sh → 키워드 grep 1회(`confirm`·`restrict`) → sed -n/cat 로 지점 읽기 → python heredoc 한 번에 편집 → 종료(test.sh 미실행). '분리'·'오진 유도'가 키워드 grep 한 번에 무력화(T1 두 확정 지점이 같은 grep 결과에 나옴). T1 god 은 도구 이름 `bash` 오기 1회(오류 → 재호출). `--max-budget-usd` 미도달($5) — 중단 동작 계속 미확인 | 결론 분기 |
| 2026-10-28 01:27(date) | 사이클 마감: ADR §7.3 개정 2 결과 1줄 · NEXT N0 → '1차 결론 작성 + 규모 확장 여부 결정' · measurement-log 1행. study-note 이슈 아카이브는 명세 금지영역(study-note 쓰기 안 함)이라 보류(후보: Bash 명령 정적 해석의 한계 — 휴리스틱 긴 꼬리를 실행 뒤 diff 대조로 가시화) | 완료 요약 워커 |

## 리뷰 ledger

### 설계 선검증(task 02, 1패스 — 설계 문서 유형: 듀얼 1패스 상한)

| id | source | 근거 | disposition | status | 처리 |
|---|---|---|---|---|---|
| D-1 | opus F1 | god `Marketplace.kt:882-888` confirmItem 공용, layered `BuyerItemService.kt:28-33`·`Order.kt:331` | 채택 | fixed | 합류점 1곳 수정 = 대안 정답, symbols edit/read 추가, 수단 서술 조정 |
| D-2 | opus F2 | layered `Order.kt:324` 흔적 함수·KDoc 공백 | 채택 | fixed | 주석 삭제 대신 중립 문장 치환(양쪽), 흔적은 위협 기록 |
| D-3 | opus F3 | god `:243-264`, layered `JpaBidRepositories.kt:44-63`(시드 상대 입찰) | 채택 | fixed | B를 빈 사이즈에서 |
| D-4 | opus F4 + codex R2 | god `:960-966`·`:1397-1403`, layered `User.kt:59-74` | 채택 | fixed | N·R 시나리오 필수, naive 7·8 |
| D-5 | opus F5 + codex R6 | verify.sql:499-511, 브리프 S·B 단언 부족 | 채택 | fixed | 이벤트 정확히 1행·4필드, 반대쪽 입찰 유지·이벤트 없음 단언 |
| D-6 | opus F6 + codex R3 | ADR-0003:69, god `:1004`·`:1380`, layered `ItemRefunder.kt:24-55`·`BatchService.kt:150-154` | 채택(설계 결정) | fixed | TASK에 사이즈 잠금 명시, 교착은 판정 불가 → 위협 기록 |
| D-7 | opus F7 + codex R4 | ADR-0003:55, god `:138`, layered `User.kt:38`, `PenaltyTest.kt:90-104` | 채택 | fixed | TASK 계약 문장으로 충돌 해소, PenaltyTest:90 제외 + 숨은 테스트로 계약 보존 |
| D-8 | opus F8 | god `:1340-1355`, layered `BatchService.kt:104`·`Bid.kt:110` | 채택 | fixed | 본보기 = bid-expiry로 정정 |
| D-9 | codex R1 | god `:785`·`:954`·`:1484`, layered `FulfillmentService.kt:90`·`BatchService.kt:233` | 채택 | fixed | S1(검수 FAIL)·S2(발송 기한) 분리 |
| D-10 | codex R5 | ADR-0003:63·:138 | 채택 | fixed | 만료 지난 ACTIVE 입찰 포함, naive 9 |
| M-1 | main | 주입 사본 layered `domain/Order.kt:321`·`:340`('확정 시각'), god `Marketplace.kt:868` 대응 문장 없음 | 채택 | fixed | layered KDoc 꼬리표 제거(god와 같은 중립 수준) — 핵심어 grep 두 구조 0 |
| M-2 | worker(T1) | layered `Order.markConfirmed`만 자르는 변형 → 적립 만료(confirmEarn 입력) 틀린 채 숨은 통과 | 채택 | fixed | 숨은 A·B에 EARN expires_at 단언 + naive-6(두 구조 숨은 실패) |
| D-11 | codex R7 | ADR-0003:133(시계 역행 불가) | 채택 | fixed | 분쟁 기한 전 → 후 순서 |

### 리뷰 루프 1 (task 05, BASE `5131584`, packet 누적 diff 6,710줄 · codex high ∥ Opus)

| id | first_seen_loop | source | 근거 | disposition | status | fixed_in_loop | 처리 |
|---|---|---|---|---|---|---|---|
| R1-1 | 1 | opus | `events.py:56-62`(fd 숫자 토큰), `:778-793` — `cp /tmp/A.kt app/A.kt 2>/dev/null` → `2/A.kt` | 채택 | fixed | 1 | 토큰화 전 fd 번호 제거, 테스트 FdNumbers |
| R1-2 | 1 | opus | `events.py:781-786`·`:125-150`·`:51-63` — xargs/find -exec sed -i·`$(…)`·`\` 줄 이음·여러 줄 `python -c` 가 `([], [])` | 채택 | fixed | 1 | xargs·find·subst·파일 0개 sed/perl → 모름, 줄 이음·여러 줄 따옴표 합침 |
| R1-3 | 1 | opus | `events.py:491-506` — `(cd app && …); …` cd 누출 | 채택 | fixed | 1 | commands(marks) 괄호 표지 + cwd 스택, sh -c 하위 셸 |
| R1-4 | 1 | codex | `events.py:781`·`:633` — 글롭 `app/*.kt` 를 문자 그대로 대상 | 채택 | fixed | 1 | 글롭 → 모름('glob') |
| R1-5 | 1 | codex | `events.py:888`·`:900-902` — 대상 모르는 python 쓰기가 first_edit_turn 에서 빠짐 | 채택(부분) | fixed | 1 | 인라인 python 쓰기 확실 → 반영, 스크립트 파일·인터프리터 흔적은 쓰기 불확실 → 제외(UNSURE_WRITERS) |
| R1-6 | 1 | codex(major)+opus(minor 5) | `oracle/T1/design.md:56-64`, 주입 layered `Order.kt:324-327` 흔적 함수·가드 서술 3 대 1 | 채택(문서) | user-deferred | — | 승인된 예외·잔여 위협: 사용자 결정(2026-10-27 "채택, 비대칭은 기록만" — 같은 성격의 구조 결과로 취급) + ADR §9-12 기록·T1 제외 탐색적 민감도 요약. 단서 비대칭 자체는 남는다(종합 감사 지적) — 완료 보고에서 사용자에게 다시 알림 |
| R1-7 | 1 | opus | `symbols.god.txt` 파일 전체 edit + 파일 단위 적중 → god 포화(오진 경로도 같은 파일) | 채택(문서) | fixed | 1 | ADR §5.2 해석 주의: 수단별 보고, Bash 파일 단위 적중은 구조 비교 근거로 안 씀 |
| R1-8 | 1 | opus | 실데이터 대조가 테스트로 고정 안 됨 | 채택 | fixed | 1 | `tests/fixtures/pilot-bash-commands.json` + PilotRealData |
| R1-9 | 1 | codex+opus | T1·C2 design.md 채택표 code_dirty True, T1 후속 뒤 naive 일부 미재실행 | 채택(후속 단계 대기) | open | — | task 05 최종 재채택(SHA 고정)에서 전부 |
| R1-10 | 1 | codex | 2회차 파일럿·게이트 산출물 없음 | 채택(후속 단계 대기) | open | — | task 06 |
| R1-11 | 1 | opus | `C2HiddenTest.kt:102-135` — 잠금·'복구 뒤 재매칭 전' 순서는 순차 테스트로 판별 불가 | 범위 밖(기록됨) | — | — | TASK·design·ADR §9-13 명시 |
| R1-12 | 1 | opus | `events.py:634-636` — `> build.txt` 처럼 사본 안 출력 파일이 first_edit_turn 에 섞임 | 기각 | — | — | 사본 안 새 파일 생성도 편집(생성 포함 정의, ADR §5.2) — first_edit_tool_turn 은 생성 제외라 무영향 |

### 리뷰 루프 2 (BASE `5131584`, packet 누적 diff 7,061줄 + 루프 1 수정분 · codex high ∥ Opus)

| id | first_seen_loop | source | 근거 | disposition | status | fixed_in_loop | 처리 |
|---|---|---|---|---|---|---|---|
| R2-1 | 2 | opus | `events.py:943-944` `if c.is_error: continue` 가 bash_writes 앞 — `sed -i … && 빌드 실패` 쓰기 무음 소실 | 채택(보수적) | fixed | 2 | 오류 호출의 대상은 edited_paths, 턴은 모름(첫 편집 확정 안 함). 감사: 뒤 조각 실패면 실제 첫 편집이 늦게 잡힌다 → 잔여 위험(이벤트로 실행 여부 판별 불가) — 그 턴이 first_edit 보다 앞서면 분석에서 표시(ADR §5.2 '모름') |
| R2-2 | 2 | codex+opus | `events.py:666-668`·`:804-826`·`:531-539` — `"$F"`·`> $OUT` 대상 무음 소실, `cd $D`·`cd -`·`popd` 뒤 상대 경로 오확정 | 채택 | fixed | 2 | 변수 대상 → 모름('var', 쓰기 불확실), 풀 수 없는 cd → UNKNOWN_CWD, 뒤 상대 경로 쓰기 → 모름('cwd') |
| R2-3 | 2 | opus | `oracle/T1/design.md` §4 "또는 Order.markConfirmed" = naive-6 와 모순, 합류점 함정이 layered 에서 더 그럴듯 | 채택 | user-deferred | 2 | 서술 정정 + 함정 비대칭 기록(design.md·ADR §9-12) — 사용자 결정 "기록만" |
| R2-4 | 2 | opus | 파서 기타: `#` 주석·`perl -M`·`{ cd …; }`·따옴표 안 `<<`·`git -C`·`python <<<`·`ruby -pi`·따옴표 안 fd 번호 | 채택 | fixed | 2 | 각각 수정(test_fix_redesign_loop2.ParserMisc). `(( i > 5 ))`·`xargs -I{} sh -c` 는 수정 안 함 → 아래 R2-8 안전망으로 드러남 |
| R2-5 | 2 | opus | `symbols.layered.txt` — AutoConfirmBatch(BatchService.kt) 대안 정답이 edit 범위 밖 | 채택 | fixed | 2 | BatchService.kt 파일 전체 edit 추가 |
| R2-6 | 2 | codex | `design.md:46-57` '7일' 적중 god 3·layered 7(가드 서술 BatchTargets.kt:22 등) | 채택(감사 재검토) | fixed | 2 | 감사 지적 수용 — 가드 서술도 두 구조 '기한이 지난 DELIVERED' 로 중립화(god :1490, layered Order.kt:340·BatchTargets.kt:22·BatchService.kt:241), '7일' 잔여 god 1·layered 2 = 정산 주 창 서술(오진 영역, 같은 의미), 커밋 이후 행 참조. (원래 기각 사유: 가드 조건은 주입한 규칙이 아니고 god `:1490` 에도 지점 2 바로 위에 같은 서술이 있다. 개수 차이는 계층마다 같은 가드를 적은 구조 결과(ADR §3.1 '규칙 서술 주석'은 주입 규칙 대상) — design.md 에 기록돼 있음 |
| R2-7 | 2 | codex | `oracle/*/symbols` fix 범위에 패치 문맥 3줄 → 주변 줄 편집도 겨냥 | 기각(규칙 유지) + 명시 | fixed | 2 | tasks-a-judge 에서 정한 symbols 규칙(문맥 3줄 포함) 유지, 감사 제안대로 '주변 줄도 겨냥·적중' 을 ADR §5.2 에 명시 |
| R2-8 | 2 | main-synthesis | 루프 1·2 모두 파서 경계 사례가 새로 나옴(휴리스틱의 긴 꼬리) | 채택(탐지 안전망) | fixed | 2 | 감사: 누락 파일은 드러내지만 턴은 복원 못 함 → 상태를 '탐지 안전망'으로 — 값 있는 실행의 편집 턴 지표는 별도 확인 대상. 실행 뒤 diff 대조 `missed_edit_paths`(runner.changed_files) — 남은 누락을 실행마다 가시화, ADR §5.2 '누락 대조' |
| R2-9 | 2 | codex | `events.py:588-606` 한 호출에 단일 파일 grep 여러 개면 경로 없는 결과를 귀속 못 함 | 수용(한계) | — | — | 읽기 겨냥 누락(값 없음 방향, 오탐 아님) — 출력 경계 정보가 이벤트에 없다. 기록 |
| R2-10 | 2 | codex | 최종 재채택·파일럿 | 채택(후속 단계 대기) | open | — | 감사: 범위 밖 아님 — task 05 후반(SHA 고정 재채택)·06(파일럿)에서 닫는다(R1-9·R1-10 같음) |

### 리뷰 루프 3 (BASE `5131584`, packet 누적 diff 8,099줄 합계 + 루프 2 수정분 · codex high ∥ Opus)

| id | first_seen_loop | source | 근거 | disposition | status | fixed_in_loop | 처리 |
|---|---|---|---|---|---|---|---|
| R3-1 | 3 | opus | `events.py:530-556`·`:81` — `# Let's fix` 주석의 `'` 가 다음 줄을 합쳐 삼킴(쓰기·cd 무음 소실) | 채택 | fixed | 3 | split_heredocs 가 명령 줄마다 따옴표 밖 주석을 먼저 뗌(_prep) |
| R3-2 | 3 | codex+opus | `events.py:602`, `test_fix_redesign_loop2.py:35` — 모르는 위치가 다음 호출에서 /work 로 리셋 | 채택 | fixed | 3 | UNKNOWN_CWD 를 절대 경로 cd 까지 유지(테스트 계약 갱신) |
| R3-3 | 3 | codex | `events.py:137`·`:899` — `if false; then echo x > app/A.kt; fi` 를 확정 편집으로 셈 | 채택 | fixed | 3 | if·case 갈래 표지 → 갈래 안 쓰기는 대상만 남기고 모름 턴, case 패턴 ')' 는 서브셸 아님 |
| R3-4 | 3 | codex+opus | `echo "$(date)" > /tmp/log` → subst 모름·first_edit_turn, `$((n>3))`·`[[ $a > b ]]` → 가짜 대상, `perl -I lib -i -pe`, `cp -r` | 채택 | fixed | 3 | subst 표지 폐기(경로의 $·` 는 var), 산술·[[ ]] 전처리, perl 값 옵션·묶음 -pe, cp -r → 모름 |
| R3-5 | 3 | opus | `events.py:1002`·`:1049`, symbols god `Marketplace.kt:1-1771 edit` — Edit 도구 적중도 파일 단위, god 오진 편집도 적중 | 채택 | fixed | 3 | first_edit_hit_fix(fix 범위 줄 단위, Edit 계열만) 추가, ADR §5.2 구조 비교는 이 값 |
| R3-7 | 3 | codex(post-fix) | `events.py:89` 산술 확장을 0 으로 바꿔 `"app/$((1+2)).kt"` → app/0.kt · `:147`·`:618` case 갈래 안 서브셸 `(cd app)` 괄호까지 무시 → cd 누출 | 채택 | fixed | 3 | `$ARITH`(경로면 var 모름), 패턴 닫는 ')' 만 무시·패턴 낱말 건너뜀 — 424 OK, 커밋 이 행 직후 |
| R3-6 | 3 | codex | `T1HiddenTest.kt:35·71` — 이른 구매 확정(7일 안) 사례 없음, '항상 배송+7일' 오답은 회귀만 잡음 | 채택 | fixed | 3 | T1 숨은 A 에 7일 안 구매 확정(D+3일: 응답·DB·EARN expires_at) + naive-7(항상 배송+7일) — 두 구조 fix 통과·naive-7 숨은 실패, 커밋 `ae6deda` |

## 생략한 검증

- 리뷰 루프 종료 조건 미충족(루프 3 신규 채택 7) — 상한 3 도달, 사용자가 잔여 위험 수용으로 종결(2026-10-27). 남은 위험: Bash 편집 해석은 휴리스틱이라 새 경계 사례가 남아 있을 수 있다 → 실행마다 `missed_edit_paths`(diff 대조)·`edit_unknown_turns` 로 드러남, 파일럿 게이트(턴)와 무관.

## 완료 요약

T1(bug)·C2(change)를 새로 만들었다. T1은 확정 시각 상한 제거, C2는 제한 시 활성 입찰 취소다. 네 난이도 수단(분리·여러 지점·오진 유도·본보기 없음)을 두 구조에 넣었다. `events.py`는 이제 Bash 쓰기도 편집으로 센다. `cd`로 바뀐 작업 위치를 따라가고, 실행 뒤 diff와 대조해 놓친 파일을 `missed_edit_paths`로 남긴다. ADR-0005 개정 2에 기록했다. 고정 SHA `ae6deda`에서 채택 실측 60행을 돌려 T1·C2 채택을 확정했다. 2회차 파일럿 4회는 모두 valid·성공이었지만 턴 중앙값이 god 5.5·layered 5.5(< 10)라 **ADR §7 게이트를 다시 넘지 못했다**. 사전 결정대로 다음 작업은 1차 결론 작성이다. 브랜치 `feat/tasks-redesign`(`5131584..`).

**핵심 diff**

`experiment/runner/events.py:1049` — metrics 시그니처(before: 5131584 `:569`)
```diff
-def metrics(cs: list, spans: list) -> dict:
+def metrics(cs: list, spans: list, existing=None, changed=None) -> dict:
```
`experiment/runner/events.py:1137-1138` — 실행 뒤 diff 대조(신규)
```python
    if changed is not None:
        out["missed_edit_paths"] = sorted(set(changed) - set(out["edited_paths"]))
```
`experiment/runner/runner.py:561-562` — metrics 호출(before: `:542`)
```diff
-                   **events.metrics(cs, symbols(case, structure)), audit_violations=violations,
+                   **events.metrics(cs, symbols(case, structure), existing=base_files(rd / "base"),
+                                   changed=changed_files(rd / "base", rd / "work")), audit_violations=violations,
```
`experiment/oracle/T1/inject.god.patch` — 주입 지점 1(`Marketplace.kt:877` 부근)
```diff
-            val lastDay = item.deliveredAt!!.plus(Duration.ofDays(7))
-            confirmItem(item, confirmedAt = if (now.isBefore(lastDay)) now else lastDay, now = now)
+            confirmItem(item, confirmedAt = now, now = now)
```

**검증 결과**

- 단위 테스트 `python3 -m unittest discover -s tests` → 424 OK(시작 296). blind 테스트 93(실결함 1 `patch -i` 수정). 1회차 파일럿 실데이터 대조를 테스트로 고정(`tests/fixtures/pilot-bash-commands.json`).
- 채택 실측(code_sha `ae6deda`, dirty 0, 60행): T1 none 숨은 2/2 실패 · fix 5/5(contract 122·contract2 89·verify 0) · alt-1 통과 · naive-1~7 숨은 실패. C2 none 숨은 4/6 실패 · fix 5/5(contract2 88) · naive-1~10 숨은 실패. naive마다 두 구조 숨은 실패 수 같음, 변조 0·build 실패 0.
- 리뷰: 설계 선검증 1패스 · 듀얼 리뷰 루프 3(상한) · post-fix 재점검 1.
- 2회차 파일럿(`experiment/results-pilot2/runs.jsonl`, Sonnet, 4/4 valid·success·censored 0·위반 0):

| 과제·구조 | 턴 | 토큰 | 비용($) | first_edit_turn | via | hit | hit_fix | missed |
|---|---|---|---|---|---|---|---|---|
| T1 god | 6 | 102,288 | 0.053 | 5 | Bash | True | None | 0 |
| T1 layered | 5 | 94,534 | 0.110 | 4 | Bash | True | None | 0 |
| C2 god | 5 | 98,924 | 0.103 | 4 | Bash | True | None | 0 |
| C2 layered | 6 | 165,724 | 0.151 | 5 | Bash | True | None | 0 |

- 네 실행 모두 first_targeted_turn 2, edit_unknown_turns 비어 있음. C2 layered 6파일(새 파일 1). 비용 합 $0.416. 네 실행 모두 키워드 grep 한 번으로 고칠 곳에 닿고 python heredoc으로 한 번에 고쳤다 — '분리'·'오진 유도'가 효과를 내지 못했다.

**남은 것 / 빚**

- 리뷰 종료 조건 미충족(상한 3) → 사용자 잔여 위험 수용(2026-10-27). Bash 해석 경계 사례는 `missed_edit_paths`·`edit_unknown_turns`로 드러남.
- 기록만 한 비대칭: T1 지점 거리·합류점 함정(ADR §9-12). 판별 불가: C2 잠금 순서 교착(§9-13).
- `--max-budget-usd` 중단 동작 미확인($5 미도달). study-note 이슈 아카이브 보류(금지영역).
- 다음: 1차 결론 문서화 + 규모 확장 여부 결정(NEXT N0).
