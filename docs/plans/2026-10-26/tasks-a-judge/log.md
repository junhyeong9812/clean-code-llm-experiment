# log — 과제 A: 판정 단계 + T1·C2 + 러너 소수정 + 파일럿

## 타임라인

| 시각 | 사건 | 결과 |
|---|---|---|
| 2026-10-26 12:14(date) | 인터뷰 2회(6문항) → 명세 작성. 브랜치 `feat/tasks-a-judge`(main `7a0d218`) | 사용자 합의 대기 |
| 2026-10-26 12:16(date) | 사용자 "합의, auto 진행" → SPEC=1·MODE=auto | 착수 |
| 2026-10-26 12:20(date) | task 02·03 골격: `templates/checks`(Kotlin JUnit 모듈 + 지원 코드 Env·Api·Clock·Batches·Db — 규칙 상수 없음), 공통 `test.sh`(매 실행 DB를 템플릿 `ccle_tpl`에서 재생성 → 앱 빌드·기동 :8099 → `:checks:test`), `judge.py`(변조 → 빌드(network none) → 공개·숨은·contract·contract2 단계마다 새 DB·앱·가짜 PG, 판정 컨테이너 --internal·프록시 없음, verify-exclude는 RAISE 메시지 파싱), `judge-stage.sh`, `check` 명령(에이전트 없이 패치 + 판정), 탐침 과제 `_probe` | 실측 대기 |
| 2026-10-26 12:32(date) | 가정 1 실측 1차: 판정 컨테이너·앱 기동·verify 위반 0은 동작, 테스트 0건 — **JUnit 실행용 의존성(엔진)이 RO 캐시에 없음**(testClasses는 컴파일 구성만 받음) → prep에 모든 해석 가능 구성 다운로드 init script(`resolve-all.gradle`) 추가, RO 183M → 213M | 수정 |
| 2026-10-26 12:44(date) | task 01 러너 소수정: sed 범위 읽기 = Read 규칙(덮음 ∧ 폭 ≤ 400, 넓으면 청크), usage_limit 근거 = stderr + result·system 이벤트(`limit_evidence`), 예열 주석 실측값. ADR §5.2·§6.4 '변경 예정' → 반영 문구. 테스트 171(+9 test_tasks_a) | 통과 |
| 2026-10-26 12:47(date) | **가정 1 성립**: `check _probe god none` — 공개 1·contract 122·contract2 89 전부 통과, verify 위반 0(두 회귀 단계), 빌드 성공, 판정 1회 452초. 코드 커밋 | T1 설계 |
| 2026-10-26 12:49(date) | 워커 3개 병렬(Opus): T1 작성·C2 작성(공통 브리핑 `worker-brief.md` + 과제별 메인 설계), blind 판정 테스트(계약 문서 scratchpad `blind-judge-contract.md`, 구현 열람 금지) | 회수 대기 |
| 2026-10-26 12:53(date) | blind 판정 테스트 회수: 106개 중 실패 2 — ① tampered가 `checks/.gradle`을 보호 대상으로 봄(restore는 복사 안 함 → 복원 뒤에도 변조) ② verify 위반 목록을 ', '로만 분리(공백 없으면 이름 오파싱 — 조용한 오류) → 둘 다 수정 | fixed |
| 2026-10-26 13:45(date) | **C2 워커 회수**: 계약 전부 TASK.md, 공개 2·숨은 6(자연 흐름 검수 FAIL로 패널티, 정렬 동률·시드 판매자 대조·정산 전후·분리·401), fix god 3파일·layered 5파일(신규 1), naive 4. 실측: fix 두 구조 전 단계 success(contract 122·contract2 89·verify 0), none·naive-1~4 숨은 실패. **T1 워커 회수**: inject = 정렬 1곳(두 구조) + 규칙 주석 삭제(god 1·layered 3), naive 3(DESC·id·LIFO), 숨은 = 결제 A·B·C → B 확정 → +1일 → A → +1일 → C(만료 B<A<C, 생성 A<B<C), D12(T1) 두 구조 같은 시각 결정 확인. 실측: fix 두 구조 전 단계 success, none·naive 숨은 실패. 메인 검수: TASK.md 누출 없음, 해제 쪽 '만료 늦은 것부터' 주석은 유지(ADR 삭제 범위 = 주입 규칙 서술 — design.md에 단서로 기록), 공개 테스트 '+364일'은 증상 일부로 허용. 판정 행에 실패 메시지 기록 추가(`failure_messages`) | 반복 검사 |
| 2026-10-26 14:05(date) | **C2 fix 5회 반복**(public·hidden): god 5/5·layered 5/5 success, 회당 143~177초 → C2 채택 조건 충족. T1 5회 반복 착수 | 진행 |
| 2026-10-26 14:06(date) | 과제·판정 보완 커밋 `bd00839`. 리뷰 루프 1 착수: BASE `7a0d218`, OUT=`scratchpad/tmp.M7IpqB4O46`, mirror=`scratchpad/tmp.Knfv1WiM5G`, 렌즈에 과제 공정성(어느 구조에 유리한가)·판정 결함 추가. Opus ∥ codex | 리뷰 대기 |
| 2026-10-26 14:19(date) | 루프 1 회수(codex F1~F4·OQ, Opus F1~F9·Q1~5) → 종합 L1-1~L1-11 채택(아래 ledger). **메인 판정 오류 1:** C2 공개 테스트 금액 상수(5,000)를 '기존 규칙 결과라 누출 아님'으로 허용했으나 명세 §2는 공개 테스트의 규칙 상수·기대값을 금지 → 철회·수정(T1 '+364일'도 같은 기준으로 수정). 판정 수정 중 함수 구간 교체가 다른 함수까지 덮을 뻔함(파일 미기록, 즉시 재적용 — 메인 실수). T1 5회 반복은 수정 전 시작분이라 중단(정답 4/4 통과 상태) → 수정 뒤 재실측 착수(T1·C2 병렬). 테스트 282 | 재실측 |
| 2026-10-26 14:34(date) | **C2 재실측(수정 후)**: naive-5(동률 tie-break 누락) 두 구조 숨은 실패 — 실패 지점이 동률 단언(같은 시각 b·c가 id 오름차순으로 나옴, 두 구조 같음), none 공개 실패, fix 공개·숨은 통과(두 구조) | C2 채택 |
| 2026-10-26 15:09(date) | **T1 재실측(수정 후)**: none 두 구조 공개·숨은 실패, fix 두 구조 전 단계 success 1 + 공개·숨은 4 = **5/5**(god·layered), naive-1~3은 숨은 테스트 불변이라 앞 실측 유효 → T1 채택. 커밋 | 루프 2 |
| 2026-10-26 15:10(date) | 수정 커밋 `4fdfca2`. 리뷰 루프 2 착수: OUT=`scratchpad/tmp.si7h5uPHvV`, mirror=`scratchpad/tmp.IxGlFAu09I`, packet에 채택 조건 실측 요약(checks-summary 54행) 추가 | 리뷰 대기 |
| 2026-10-26 15:23(date) | 루프 2 회수(codex F1~F2·OQ, Opus F1~F8·OQ1~6) → L2-1~L2-12 채택(ledger). 수정 후 테스트 287. 수정 전 코드로 돌던 C2 4회 반복은 중단·정리(잔여 디렉터리 1 삭제). **최종 코드로 채택 조건 재실측 착수**(T1·C2 병렬 — fix 전 단계 1 + 공개·숨은 4, none, C2는 숨은 테스트 순서 고정으로 naive-1~5도 다시) | 재실측 |
| 2026-10-26 16:34(date) | **최종 코드 채택 재실측 완료**: T1 — fix 두 구조 전 단계(공개·숨은·회귀 122/89) 1 + 공개·숨은 4 = 5/5, none 두 구조 공개·숨은 실패. C2 — fix 두 구조 5/5(전 단계 1 포함), none 두 구조 공개·숨은 실패, naive-1~5 두 구조 숨은 실패(naive-5 실패 지점 = 동률 id 내림차순 단언, 두 구조 같음). tampered·build_files_changed 0, 잔여 컨테이너 0 → **T1·C2 채택**(L2-2 해소). 루프 2 수정 코드 커밋 | 루프 3 |
| 2026-10-26 16:35(date) | 루프 2 수정 코드 커밋 `c006d5c`(테스트 287 OK). 리뷰 루프 3(마지막) 착수: OUT=`scratchpad/tmp.XBaZQFCea1`, mirror=`scratchpad/tmp.hIupBam1Qw`, packet = spec·누적 diff·루프 2 수정 diff·ledger(재지적 방지)·최종 실측 35행. codex ∥ Opus(general-purpose, opus) | 리뷰 대기 |
| 2026-10-26 16:54(date) | 루프 3 회수(codex F1·OQ1·ledger 검증, Opus F1~F8·OQ1~4) → L3-1~L3-10 채택(ledger). 핵심: 판정 단계 앱이 쓰기 가능한 /work·HOME 에서 테스트와 같은 컨테이너로 돌던 구조(두 리뷰어 공통) → 테스트는 원본 재료만 담은 판정 전용 작업 공간, 앱은 jar 하나만 읽기 전용으로 받는 별도 컨테이너로 분리. 테스트 296 OK, 실 docker 스모크(C2 layered fix 공개·숨은) 통과·잔여 0. 수정 커밋 `93e37b7`. 이 코드로 채택 조건 최종 재실측 착수(T1·C2 병렬 — fix 전 단계 1 + 공개·숨은 4, none, naive 전부; 행에 code_sha 기록) | 재실측 |
| 2026-10-26 17:04(date) | post-fix 재점검 회수(Opus 1, mirror `scratchpad/tmp.ephHAHFpSa`): L3-1~L3-10 전부 해소(앱 인자·app_died mock 확인, C2 hunk 범위 직접 대조 일치, JUDGE_FIELDS 누락 0). 새 결함 R3-1 [medium] judge_unresolved 키가 done·held 양쪽에서 빠져 같은 cmd_run 에서 묻지 않고 에이전트 재실행 → 수정 예정(재실측 체인 끝난 뒤 — 체인 행의 code_dirty 오염 방지, check 경로 무관). R3-2 [low] T1 fix 규칙(바뀐 줄)≠C2(hunk) → 같은 규칙으로 맞춤 예정. OQ: 앱·테스트 컨테이너 loopback 공유(가짜 PG 는 수정 전과 같음, 실해 낮음 — 수용·기록), app_died 180초 지연(시간만 — 수용), docker 스모크는 C2 layered fix 공개·숨은 통과로 일부 확인·회귀 단계는 체인으로 확인 | 체인 대기 |
| 2026-10-26 18:37(date) | **최종 채택 재실측 완료(code_sha 93e37b7, dirty 0 — 40행)**: T1 fix 두 구조 5/5(전 단계 1 — 회귀 포함 새 판정 구조에서 통과), none 실패, naive-1~3 두 구조 숨은 실패(실패 지점 = '만료가 가장 이른 묶음(B)부터 다 써야 한다' 단언, Q3-1 해소). C2 fix 두 구조 5/5, none 실패, naive-1~5 두 구조 숨은 실패(naive-5 = 동률 id 내림차순 단언). 잔여 컨테이너 0 → **T1·C2 채택 확정**. R3-1(judge_unresolved 종단 — done_keys)·R3-2(T1 symbols hunk 규칙) 수정 `12a4805`, 테스트 296 OK(check 경로 무관이라 재실측 불요) | 파일럿 |
| 2026-10-26 18:37(date) | 파일럿 착수: `run --model sonnet --cases T1,C2 --reps 1 --parallel 2 --results-dir experiment/results-pilot`(본 표본과 분리, 승인 4회) | 실행 중 |
| 2026-10-26 19:19(date) | **파일럿 완료(4/4, results-pilot)**: 전부 valid·success·회귀 통과·위반 0·변조 0·censored 0. 턴(message.id) T1 god 6·layered 6, C2 god 6·layered 5 → **구조별 턴 중앙값 god 6·layered 5.5 < 10 → ADR §7 게이트 미달**(나머지 3조건 충족). 토큰 T1 104,402/113,839(god/layered), C2 150,572/118,834. 명목 비용 합 $0.366(0.052·0.078·0.128·0.108), 벽시계 2.3~4분. 관찰: 4실행 모두 **Bash 만** 사용(Read·Edit 0 — sed -n/cat 로 읽고 sed -i·python heredoc 으로 편집) → first_edit_tool_turn·first_edit_hit 전부 None, 첫 겨냥 턴은 T1 god 만 2(나머지 None — sed -n 범위가 symbols 를 덮지 못하거나 cat 전체 읽기). 기전 지표가 Edit 도구 전제라 이 행동에서 정의되지 않음. test.sh 는 자동 백그라운드 후 sleep 100 으로 회수. 예산 상한($5) 미도달이라 --max-budget-usd 중단 동작은 미확인 | 사용자 결정 대기 |
| 2026-10-26 20:40(date) | 사용자 결정: 게이트 미달 → **과제 재설계(권장안)** · 편집 지표 → **Bash 편집도 인식(권장안)**. 둘 다 합의 범위 변경이라 새 작업 폴더에서 spec부터(core §1). 이 작업은 파일럿 게이트 판정까지로 마감: NEXT N0 갱신·measurement-log 1행·study-note 이슈 아카이브는 명세 금지영역(study-note 쓰기 안 함)이라 보류 | 마감 |

## 리뷰 ledger

(루프 1 — BASE `7a0d218`, 대상 `bd00839`)

| id | loop | source | 근거 | 내용 | disposition | status |
|---|---|---|---|---|---|---|
| L1-1 | 1 | codex F1 · opus F1 | judge.py:245 · runner.py:554 | 판정 불가(success None)가 valid로 남아 재개 대상에서 빠짐 | 채택 | fixed(judge_infra 무효 → 재개 시 다시 돔) |
| L1-2 | 1 | codex F2 · opus F3 | judge.py:191 | 빌드 인프라 오류와 컴파일 실패 미구분 | 채택 | fixed(rc ≥ 125·의존성·메모리·디스크 패턴 = infra, Kotlin `e:` = compile, 시간 초과 = infra) |
| L1-3 | 1 | codex F3 · opus F6 | judge.py:216 · runner.py:181·763 | 판정 정리 잔여가 결과에 미반영·판정 컨테이너 정리 누락·cleanup이 진행 중 판정·check 자원 삭제 | 채택 | fixed(잔여 → 단계 infra, remove_run에 -judge·-jbuild, 라벨 접두 일치 live 판정, check도 attempt.lock) |
| L1-4 | 1 | codex F4 · opus F7 | C2Test.kt:45 · T1Test.kt:44 | 공개 테스트의 규칙 상수(패널티 5,000·유효기간 365) | 채택(메인 판정 철회) | fixed(C2 양의 정수 확인, T1 DB 만료 시각 사이로 이동, TASK 예시 금액 변경) |
| L1-5 | 1 | opus F2 | judge-stage.sh:18-25 | 앱 사망과 기동 시간 초과 미구분, 앱 로그 없음 | 채택 | fixed(app_died = 확정 실패, app_start_timeout(180초) = infra, app_log_tail) |
| L1-6 | 1 | opus F4 | judge.py:15·43 | 루트 빌드 파일·wrapper 미복원 → 빌드 스크립트로 테스트를 꺼 판정 불가로 빠짐 | 채택 | fixed(판정 전 원본 복원 + build_files_changed 기록) |
| L1-7 | 1 | opus F5 | judge.py:209-257 | 단계 예외가 판정 전체를 None으로 → 확정 실패 소실 | 채택 | fixed(run_stage 단계별 격리, stages 즉시 기록, 확정 실패 우선) |
| L1-8 | 1 | opus F8 | oracle/C2/symbols.* | C2 fix 범위가 import 편집 누락·god만 좁은 창(첫 편집 적중 비대칭) | 채택 | fixed(두 구조 파일 단위, design.md에 'C2 첫 편집 적중은 god에서 정의상 거의 참 — 비교 지표로 안 씀') |
| L1-9 | 1 | opus F9 | runner.py:494-561 | judged=false·사유 누락 행 | 채택 | fixed(행 기본값 judged False·judge_skip '미도달') |
| L1-10 | 1 | opus Q2 | oracle/C2/hidden | 정렬 동률 tie-break 누락 오답 미실측 | 채택 | fixed(naive-5 — 두 구조 동률 단언에서 실패 실측) |
| L1-11 | 1 | opus Q5 | judge.py:127 | rc ≠ 0·실패 테스트 0 → 확정 실패 | 채택 | fixed(infra, blind 계약 갱신 2) |
| L2-1 | 2 | codex F1 · opus OQ6 | judge.py:172·195 | 원본에 없는 빌드 스크립트(app/build.gradle.kts·buildSrc·gradle.properties·versions.toml)로 테스트를 끄고 결과 XML 위조 가능 | 채택 | fixed(판정 전 삭제·기록 + 단계별 기대 실행 수 대조 — 공개·숨은 = 소스 @Test 수, 회귀 = 기준 122·89(제외 목록 없을 때), 불일치 = 확정 실패) |
| L2-2 | 2 | codex F2 · opus F8 | checks-summary | C2 공개 테스트 수정 뒤 5회 반복 미충족(+ 판정 변경 뒤 전 단계 재확인) | 채택 | 해소(최종 코드 재실측 5/5) |
| L2-3 | 2 | opus F1 | runner.py:557·591 | 판정 불가면 사본을 버리고 에이전트 재실행(비용·생존 편향), 앱 기동 지연·시간 초과가 infra로 흡수 | 채택 | fixed(사본 보관 results/held → run 시작 때 에이전트 없이 재판정, 보관 키는 재실행 안 함, 기동 지연·단계 시간 초과는 1회 재시도 후 반복되면 timeout_fail 확정 실패) |
| L2-4 | 2 | opus F2 | oracle/C2/symbols.god.txt | 파일 단위 fix가 god 첫 겨냥 턴을 grep 한 번으로 포화 | 채택 | fixed(edit 종류 = 첫 편집 적중 전용·겨냥 제외, fix = 줄 단위 넣을 자리 복원) |
| L2-5 | 2 | opus F3 | judge.py:256 | 다른 단계 확정 실패 + 회귀 판정 불가 → valid 인데 회귀 결측 | 채택 | fixed(어느 단계든 infra면 재판정 대상) |
| L2-6 | 2 | opus F4 | judge.py:60 | 깨진 XML = 확정 실패 | 채택 | fixed(broken 목록 → infra, blind 계약 갱신 2) |
| L2-7 | 2 | opus F5 | judge.py:69·128 | 전부 skipped = pass(fail-open) | 채택 | fixed(실행 − skipped = 0 → no_results, blind 계약 갱신 1) |
| L2-8 | 2 | opus F6 | judge.py:195·46 | 호스트 판정 준비가 사본의 링크를 따라 사본 밖 읽기·쓰기 | 채택 | fixed(사본 밖·절대 경로 링크 삭제·기록) |
| L2-9 | 2 | opus F7 | T1Test.kt:46 | 공개 테스트 SQL이 원인 줄 옆 조건 문구(`kind = 'EARN'`) 노출 | 채택 | fixed(`expires_at`만 쓰는 최소 형태, design.md 갱신) |
| L2-10 | 2 | opus OQ1 | C2HiddenTest.kt | 숨은 테스트 메서드 순서 의존 | 채택 | fixed(@TestMethodOrder(MethodName)) |
| L2-11 | 2 | opus OQ2 | judge.py run_stage | 판정 단계 앱(에이전트 코드)이 RW /work로 숨은 테스트·단계 스크립트·제외 목록 변경 가능 | 채택 | fixed(판정 자산 읽기 전용 겹침 마운트) |
| L2-12 | 2 | opus OQ3 | runner.py run_one | 판정 중 에이전트 실행 서비스가 살아 있음(부하 → 기동 지연) | 채택 | fixed(판정 전 remove_run) |
| Q2-1 | 2 | codex OQ | C2HiddenTest.kt:46 | 동률 반환 순서 비보장 | 부분 해소 — naive-5가 두 구조 같은 지점에서 실패 실측, 정답은 tie-break가 있어 순서 결정적 | — |
| Q2-2 | 2 | opus OQ4 | T1 TASK.md | 증상 조건 문장이 기전을 거의 말함 | 범위 밖(증상·재현 범위 — 두 구조 같음) | — |
| Q2-3 | 2 | opus OQ5 | T1 design §8 | 해제 쪽 주석 잔여 god 2·layered 3 | 메인 결정 기록됨(design.md 리뷰 루프 1 반영) | — |
| Q1-1 | 1 | codex OQ · opus Q1 | design.md | 정답 5회 반복 증거 | 메인 실측(C2 10/10 완료, T1 재실측 중) | — |
| Q1-2 | 1 | opus Q3 | judge-stage.sh | verify를 회귀 단계에서만 | 의도(ADR §4.1-4) — 범위 밖 | — |
| Q1-3 | 1 | opus Q4 | runner.py run_one | 판정이 에이전트 서비스 정리 전에 돎(자원·되쓰기 지연) | 파일럿 관찰 항목 | — |

(루프 3 — 기준 `7a0d218`, 대상 `c006d5c`, 마지막 루프)

| id | loop | source | 근거 | 내용 | disposition | status |
|---|---|---|---|---|---|---|
| L3-1 | 3 | codex F1 · opus F1 | judge.py run_stage_once, judge-stage.sh | 판정 단계 앱(에이전트 코드)이 쓰기 가능한 /work·공유 HOME 과 같은 컨테이너·uid 에서 돌아 지원 코드·빌드 설정·init.d·결과 XML 을 바꿀 수 있음(수 대조로 못 잡음) | 채택 | 수정(테스트 작업 공간 jd/tests = 원본 checks+숨은+behavior-tests+판정 자산, 앱 모듈 없음 · 앱 = 별도 컨테이너, jar 만 :ro · 테스트용 HOME 분리 · jbuild 는 bootJar 만) |
| L3-2 | 3 | opus F2 | symbols.layered.txt | C2 fix 범위가 구조마다 다른 손 규칙(layered 클래스 본문 전체) → 무관 grep 도 layered 만 겨냥 | 채택 | 수정(두 구조 공통: 정답 hunk 원래 쪽 범위, import·주석만 hunk 제외) |
| L3-3 | 3 | opus F3 | runner.py hold_for_rejudge | 보관 사본이 토큰 마스킹 없이 남음 | 채택 | 수정(finally 에서 회전 값까지 mask_tree) |
| L3-4 | 3 | opus F4 | judge.py run_stage | 직후 재시도 2회 시간 초과를 확정 실패로 — 같은 부하 창이라 인프라 흡수 | 채택 | 수정(첫 판정 = 판정 불가·보관, 다른 시점 재판정에서만 timeout_fail) |
| L3-5 | 3 | opus F5 | runner.py rejudge_held | 재판정 종단 없음·잠금 없음·깨진 row.json 에 cmd_run 중단 · 사본 예약 경로 FileExistsError | 채택 | 수정(상한 2 → judge_unresolved 행, attempt.lock, 깨진 행 건너뜀; 예약 경로는 L3-1 로 테스트 공간이 새로 만들어져 소멸) |
| L3-6 | 3 | opus F6 | runner.py rejudge_held | 재판정 행에 첫 판정 낡은 필드 | 채택 | 수정(JUDGE_FIELDS 전부 교체 + finished_at) |
| L3-7 | 3 | opus F7 | tests | 새 판정 경로 테스트 0건 | 채택 | 수정(test_fix_tasks_loop3 9건 — 작업 공간 분리·재시도·수 대조·재판정 상한·병합·마스킹) |
| L3-8 | 3 | opus F8 | T1Test.kt | 사용 결제 뒤 expires_at 행 = 적립뿐 단언(대안 정답 탈락) | 채택 | 수정(사용 전으로 조회 이동) |
| L3-9 | 3 | opus OQ1 · codex ledger | checks.jsonl | 실측 행이 어느 코드인지 증거 없음 | 채택 | 수정(code_sha·code_dirty). codex 'L2-2 최종본 미입증'은 기각 — 재실측은 커밋과 같은 작업 트리(수정 후 코드 변경 없음, 로그만)였음; 이번부터 행에 SHA |
| L3-10 | 3 | codex OQ | runner.py run_one | 판정 전 정리 반환값 무시 | 채택 | 수정(잔여 있으면 판정 않고 보관) |
| Q3-1 | 3 | opus OQ2 | T1 naive | 최종 코드로 T1 naive 재실측·실패 지점 | 메인 실측 | 해소(두 구조 의도한 배분 단언에서 실패) |
| Q3-2 | 3 | opus OQ3 | judge.py prepare | regression-exclude 가 비지 않으면 회귀 수 확인 꺼짐 | 이연(과제 B — T1·C2 는 제외 목록 비어 있음) | — |
| Q3-3 | 3 | opus OQ4 | — | 숨은 테스트 요구 경로가 두 구조에서 실제 호출되는지 실측 근거 | 이연(fix 가 두 구조 숨은 통과·none/naive 실패로 간접 근거; 직접 호출 추적은 과제 B 설계 때) | — |

## 생략한 검증

- `--max-budget-usd` 가 구독 인증에서 실제로 중단하는지(ADR §7 파일럿 확인 항목) — 파일럿 4회가 예산($5)에 닿지 않아 미확인. 재설계 파일럿에서 다시 본다.
- blind 테스트 워커: 루프 1 전에 blind 계약 테스트(test_blind_judge)를 썼고, 이후 루프 수정분 테스트(test_fix_*)는 메인이 작성 — 강도표 '별도 워커' 기준은 초기 계약에만 적용.

## 완료 요약

(작성: Opus 워커 — 실파일·git 출력 기준, 메인이 스니펫 2개를 git diff 로 대조 확인)

### 1. 결과

판정 단계(`experiment/runner/judge.py`·`templates/judge-stage.sh`·`check` 명령), 공개 테스트 모듈(`templates/checks` + 지원 코드), 과제 T1(포인트 배분 순서)·C2(판매자 패널티 조회 API)의 공개 자산과 oracle(fix·inject·naive 패치, 숨은 테스트, symbols), 러너 소수정 3건 + ADR §5.2·§6.4 문단 갱신을 만들었다(커밋 6개 `3304c68`..`12a4805`, 59 files +4111/−40). 듀얼 리뷰 3루프(L1-1~11, L2-1~12, L3-1~10, post-fix R3-1·R3-2 전부 fixed) 뒤 최종 코드 `93e37b7`로 채택 조건을 다시 재(40행, dirty 0) T1·C2 둘 다 채택 확정. 파일럿(Sonnet, 2과제 × 2구조 × 1회)은 4/4 valid·success(회귀 통과, 위반 0, 변조 0, censored 0)였지만 구조별 턴 중앙값 god 6·layered 5.5 < 10으로 **ADR §7 게이트 미달**(나머지 3조건 충족). 4실행 모두 Bash만 써(Read·Edit 0) Edit 도구 전제 기전 지표(first_edit_*)가 정의되지 않았다. 사용자 결정: **과제 재설계 + Bash 편집 인식** — 새 작업 폴더.

### 2. 명세 §5 검증 방법 충족

| 항목 | 결과 | 근거 |
|---|---|---|
| 과제 채택 조건 실측(정답 5회·주입·오답) | 충족 | log 18:37 · checks.jsonl code_sha=93e37b7 40행 |
| └ 주입·기준 상태에서 숨은 테스트 실패 | 충족(none 두 구조 공개·숨은 fail) | checks.jsonl |
| └ 정답 5/5(두 구조) | 충족(T1·C2 각 구조 5/5, 전 단계 1회 포함) | checks.jsonl |
| └ naive마다 숨은 테스트 실패 | 충족(T1 naive-1~3, C2 naive-1~5, 두 구조) | log 18:37(실패 지점 기록) |
| └ 회귀 통과·verify 위반 0 | 충족(contract 122·contract2 89, violations {}) | checks.jsonl 전 단계 행 4개 |
| └ 숨은 테스트 요구 경로가 두 구조에서 호출됨 | 간접 근거만(fix 통과 ∧ none·naive 실패) — 직접 추적 이연 | ledger Q3-3 |
| 판정 단위 테스트(sha·JUnit 파싱·단계 구분) | 충족(296 OK) | log 16:54·18:37 |
| 무료 스모크(에이전트 없이 패치 + 판정) | 충족 | log 12:47(`_probe`) · 16:54(C2 layered docker 스모크) |
| 러너 소수정(fix 테스트 + 기존 162개) | 충족(171 = +9 test_tasks_a) | log 12:44 |
| blind 테스트 워커 | 충족(106개, 실패 2 → 수정) | log 12:53 |
| 파일럿 ADR §7 3 게이트 | **미달**(턴 중앙값 < 10) — valid·파싱·성공은 충족 | log 19:19 |
| 듀얼 리뷰 루프(≤3, 공정성 렌즈) | 충족(3루프 + post-fix 재점검) | ledger |

### 3. 핵심 diff

**(a) 판정 자리 → 판정 구현** — before `7a0d218:experiment/runner/runner.py`
```python
def judge(case: str, structure: str, work: Path) -> dict:
    """판정 자리(ADR §4) — 과제 작업에서 구현한다. 지금은 판정하지 않았다는 사실만 남긴다."""
    return {"judged": False}
```

**(b) 판정 테스트 작업 공간 분리(L3-1)** — `git diff c006d5c 93e37b7 -- experiment/runner/judge.py`(prepare)
```diff
+    tw = jd / "tests"
+    tw.mkdir()
+    for rel in BUILD_FILES + ("gradle.properties",):
+        if (pristine / rel).is_file() and rel not in ("build.gradle.kts", "settings.gradle.kts"):
+            (tw / rel).parent.mkdir(parents=True, exist_ok=True)
+            shutil.copy2(pristine / rel, tw / rel)
+    (tw / "build.gradle.kts").write_text(JUDGE_ROOT_BUILD)
+    (tw / "settings.gradle.kts").write_text(JUDGE_SETTINGS)
+    shutil.copytree(pristine / "checks", tw / "checks", ignore=shutil.ignore_patterns("build", ".gradle"))
     hidden = R.ORACLE / case / "hidden"
     if hidden.is_dir():
-        shutil.copytree(hidden, jw / "checks" / "src" / "test" / "kotlin" / "hidden", dirs_exist_ok=True)
-    shutil.copytree(R.ROOT / "behavior-tests", jw / "behavior-tests", ignore=shutil.ignore_patterns("build", ".gradle"))
-    s = (jw / "settings.gradle.kts").read_text()
-    (jw / "settings.gradle.kts").write_text(s.rstrip("\n") + '\ninclude("behavior-tests")\n')
-    j = jw / ".judge"
+        shutil.copytree(hidden, tw / "checks" / "src" / "test" / "kotlin" / "hidden")
+    shutil.copytree(R.ROOT / "behavior-tests", tw / "behavior-tests", ignore=shutil.ignore_patterns("build", ".gradle"))
+    j = tw / ".judge"
```

**(c) 앱 별도 컨테이너(jar만 :ro)** — 같은 diff(run_stage_once)
```diff
         R.start_services(sn, proxy=False)
-        # 판정 자산은 읽기 전용으로 겹쳐 마운트 — 앞 단계에서 돈 에이전트 앱 코드가 숨은 테스트·단계 스크립트·
-        # 제외 목록·회귀 소스를 바꾸지 못하게(결과 폴더 .judge/out 만 쓰기 가능)
-        ro = []
-        for sub in (".judge/stage.sh", ".judge/verify.sql", ".judge/exclude.gradle", ".judge/regression-exclude.txt",
-                    "checks/src/test/kotlin/hidden", "behavior-tests/src"):
-            if (jw / sub).exists():
-                ro += ["-v", f"{jw / sub}:{R.AGENT_WORK}/{sub}:ro"]
+        aenv = [x for k in APP_ENV for x in ("-e", f"{k}={R.AGENT_ENV[k]}")] + ["-e", "SERVER_PORT=8099", "-e", "HOME=/tmp"]
+        a = subprocess.run(["docker", "run", "-d", "--name", f"{sn}-app", "--label", f"{R.LABEL}={sn}",
+                            "--network", f"container:{sn}-pg", *user, "-v", f"{appd}:/app:ro", *aenv, "-w", "/tmp",
+                            R.IMAGE, "java", "-jar", "/app/app.jar"], capture_output=True, text=True, timeout=120)
+        if a.returncode:
+            raise RuntimeError(f"앱 컨테이너 시작 실패: {(a.stdout + a.stderr)[-300:]}")
```

**(d) 기동 지연 확정 시점(L3-4)** — 같은 diff(run_stage)
```diff
     if second.get("status") in RETRY_STATUSES:
-        second.update(outcome="fail", reason="timeout_fail(2회 연속 기동 지연·시간 초과)")
+        if final_timeout:
+            second.update(outcome="fail", reason="timeout_fail(다른 시점 재판정에서도 2회 연속 기동 지연·시간 초과)")
+        else:
+            second.update(outcome="infra", reason="timeout_suspect(2회 연속 — 다른 시점에 재판정)")
     return second
```

### 4. 채택 실측 요약(checks.jsonl, code_sha=93e37b7, code_dirty=False, 40행)

| 과제 | 구조 | fix(5회) | none | naive |
|---|---|---|---|---|
| T1 | god | 5/5 success(1회 전 단계: 공개 1·숨은 1·contract 122·contract2 89, verify 0 / 4회 공개·숨은) | 공개·숨은 fail | naive-1~3 숨은 fail 3/3 |
| T1 | layered | 5/5 success(같은 구성) | 공개·숨은 fail | naive-1~3 숨은 fail 3/3 |
| C2 | god | 5/5 success(1회 전 단계: 공개 2·숨은 6·122·89, verify 0) | 공개·숨은 fail | naive-1~5 숨은 fail 5/5 |
| C2 | layered | 5/5 success(같은 구성) | 공개·숨은 fail | naive-1~5 숨은 fail 5/5 |

40행 전부 tampered=[]·build_files_changed=[]. 판정 1회 벽시계 206.9~660.3초(전 단계 573.7~660.3초).

### 5. 파일럿(experiment/results-pilot/runs.jsonl)

| case | structure | valid | success | turns | total_tokens | cost_usd | tools | wall_s |
|---|---|---|---|---|---|---|---|---|
| T1 | god | True | True | 6 | 104,402 | 0.0516 | Bash 5 | 236 |
| T1 | layered | True | True | 6 | 113,839 | 0.0784 | Bash 5 | 243 |
| C2 | god | True | True | 6 | 150,572 | 0.1276 | Bash 5 | 240 |
| C2 | layered | True | True | 5 | 118,834 | 0.1078 | Bash 4 | 137 |

비용 합 **$0.3654**(명목) · censored 0 · regression_pass 4/4 · 턴 중앙값 god 6·layered 5.5 → 게이트 미달.

### 6. 남은 것·이연

- 다음 작업(사용자 결정): 과제 재설계(턴 확보) + Bash 편집 인식(first_edit_tool_turn·first_edit_hit 4행 모두 None, 첫 겨냥 턴은 T1 god(2)만 값) — NEXT N0.
- Q3-2(regression-exclude 가 비지 않으면 회귀 수 대조 꺼짐 — T1·C2 무영향) · Q3-3(숨은 테스트 요구 경로 직접 호출 추적) 이연.
- 수용: 앱·테스트 컨테이너 loopback 공유(가짜 PG 는 수정 전과 같음) · app_died 최대 180초 지연(시간만).
- 미확인: `--max-budget-usd` 중단 동작(예산 미도달). 파일럿 4행은 본 표본 밖(`results-pilot`).
