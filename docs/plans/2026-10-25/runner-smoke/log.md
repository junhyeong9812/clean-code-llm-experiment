# log — 러너 + 컨테이너 이미지 + 스모크

## 타임라인

| 시각 | 사건 | 결과 |
|---|---|---|
| 2026-10-25 18:55(date) | 인터뷰 3회(9문항 — 로그인 질문은 용어 설명 후 재질문) → 명세 작성. 브랜치 `tools/runner-smoke`(main `f4dbdf6`) | 사용자 합의 대기 |
| 2026-10-25 18:55~19:25(시각 미기록 — 합의 직후) | 사용자 "합의, auto 진행" → set-state SPEC=1·MODE=auto | 착수 |
| 2026-10-25 19:25(파일 시각) | task 01 이미지 `ccle-agent:cli-2.1.308` 빌드(temurin 21 noble + nodejs/npm + CLI npm 고정 + gradle 8.14.4 zip + uid 1000 agent). **가정 1 실증(Haiku 1/3회)**: 복사한 로그인 파일로 인증, init·assistant·user·rate_limit_event·result 이벤트, `result.modelUsage` 있음(haiku 6/1180/48614/10397, $0.0032), 하네스 질문 응답 NONE, init `mcp_servers=[]`·플러그인은 내장 2개뿐·memory_paths는 격리 HOME, 샌드박스 로그인 파일 갱신 없음 | 가정 1 성립 |
| 2026-10-25 19:27(date) | 설계 발견: 가짜 PG가 `127.0.0.1`에만 바인드(`FakePg.kt:63`, behavior-tests는 금지영역) → 에이전트 컨테이너가 가짜 PG 컨테이너의 네트워크 네임스페이스를 공유(`--network container:`)하고 DB는 실행 전용 네트워크의 별칭 `db`로 접속 | 설계 확정 |
| 2026-10-25 19:35(date) | task 01·02: runner.py·events.py·creds.py·templates·더미 과제 `_smoke` 작성, `prep`(가짜 PG jar 8개 · Gradle RO 캐시 177M). 인프라 스모크(셸 모드) god: uid 1000, 호스트 HOME 없음, 마운트 `/work rw`·`/home/agent rw`·`/gradle-ro ro` 3개뿐, DB `db` users 1000, 가짜 PG `127.0.0.1:15480` 응답, 외부 HTTPS 응답(404), 오프라인 `bootJar` 성공, test.sh FAIL(예상) | 통과 |
| 2026-10-25 19:44(date) | 발견: 새 HOME 첫 빌드 126초(CPU는 데몬 쪽), 같은 HOME 두 번째 20초 — Gradle 자체 캐시(`caches/8.14.4` 298M) 재생성 비용. 대처: prep이 네트워크 없는 컨테이너에서 god→layered를 빌드한 `.gradle`(wrapper·caches/8.14.4)을 예열 템플릿(448M)으로 만들고 실행마다 격리 HOME에 복사(두 구조 같은 템플릿, 앱 컴파일 결과는 사본 build/에만) | 반영 |
| 2026-10-25 19:50(date) | **가정 2 실증**: `--network none` + RO 캐시로 god·layered 오프라인 빌드 성공(prep 예열 단계) | 가정 2 성립 |
| 2026-10-25 19:52(date) | 병렬 3(god·layered·god) 셸: 이름·네트워크 충돌 없음, 빌드 66~73초(동시 3), 종료 후 컨테이너·네트워크 0. SIGINT: 7초 안에 정리, 행 `interrupted`, 잔여 0, 실행 디렉터리 삭제. 시간 상한 15초: `timed_out=True`·rc 137, 출력 꼬리 보존, 잔여 0 | 통과 |
| 2026-10-25 19:54(date) | blind 테스트 워커(Opus) 착수 — 계약 문서(scratchpad `blind-contract.md`)만, 구현 열람 금지 | 진행 |
| 2026-10-25 19:55(date) | **에이전트 스모크 ②(Haiku 2/3)** `_smoke` god: valid·normal, 턴 4(result num_turns 4와 일치), modelUsage 83,240, $0.0029, Bash 3회(편집은 `printf > notes/greeting.txt` — Bash 편집 판정 → first_edit_turn 3·hit None), 감사 0, diff 1줄 변경 보관, 로그인 원본 만료 전후 같음(되쓰기 없음). **③(Haiku 3/3)** layered `--timeout 8`: timeout·censored·valid, rc 137, result 없음 → 부분 토큰 63,055(3턴, partial_tokens), 잔여 0. 스모크 에이전트 실행 3회 소진 | 통과 |
| 2026-10-25 ≤23:20(시각 미기록 — 질문 응답 대기 중 수 시간 경과) | blind 테스트 회수: 100개 중 실패 6(전부 감사 URL 호스트 판정 — `localhost@evil.com` userinfo 우회·대문자 scheme 미인식·IPv6 괄호 오탐). 모호 11건 보고 | 수정 대상 |
| 2026-10-25 ≤23:20 | URL 호스트를 `urlsplit().hostname`(대소문자 무시 scheme)으로 수정 → blind 100/100 | fixed |
| 2026-10-25 ≤23:20 | 사용자 결정: 형식 캡처용 Haiku 1회 추가 허용(Read offset·Grep·Edit 결과 형식이 스모크에 없었음) | 승인 |
| 2026-10-25 23:20(date) | **결함:** 캡처 실행이 시작되지 않고 멈춤 — 원본 만료까지 29분 < 고정 45분 기준이라 `wait_fresh` 대기, 만료 시 호스트 갱신 경로 없음(앞 실험엔 있었음), 대기 중 SIGINT 무시. 중단 시 `pkill -f` 패턴이 내 셸까지 맞아 편집 명령이 함께 죽음(메인 실수) — 그 시도는 runs.jsonl 행이 남지 않음(무음 누락) | 수정 |
| 2026-10-25 23:25(date) | 수정: 대기 기준 = 실행 시간 상한 + 10분, 만료(left<0)면 호스트 `claude -p ok`로 갱신(러너만 넘김), 대기 10초 단위 + 중단 플래그 확인 → 예외 → 행 남김. **사고:** 재실행한 테스트가 만료 가짜 파일로 `wait_fresh`를 불러 기본값 `host_refresh`가 실제 호스트 claude(Haiku 'ok')를 띄움 → 즉시 kill, 실제 로그인 파일 무변경(mtime 15:50 그대로) 확인 → refresh 기본값 None으로(실제 호출은 명시적으로만). blind 계약 1건 갱신(대기 60→10초) | fixed |
| 2026-10-25 23:26(date) | **형식 캡처(Haiku 4/4, 승인분)** `_smoke-tools`: valid, 8턴, 171,886 토큰. 발견 ① **2.1.308에는 Grep 도구가 없다**(ToolSearch `select:Grep` 없음 → 에이전트가 Bash `grep -n -C 1`) ② Edit 결과 텍스트는 "updated successfully"뿐 — 줄 번호 없음(첫 편집 적중이 False로 잘못 나옴) ③ user 이벤트 `tool_use_result`에 구조화 결과(Read `file.startLine·numLines`, Edit `structuredPatch`·`originalFile`·`oldString`) | 파서 수정 |
| 2026-10-25 23:28(date) | 파서 수정: Call.meta(tool_use_result), Read 범위 = 실제 반환 범위, Edit 범위 = structuredPatch의 '-' 줄(맥락 줄 제외)→originalFile 위치→스니펫 순, Bash 읽기 겨냥(grep/rg -n 결과 줄 · `sed -n 'A,Bp'`), Bash `cat 파일` = 전체 읽기. 캡처 재파싱: 첫 겨냥 3턴(Bash grep)·첫 편집 적중 True. 테스트 120개(blind 100 + 구현자 20) 통과 | 통과 |
| 2026-10-25 23:29(date) | 대기 중 SIGINT: 3초 안에 종료, 행 `interrupted`(사유 문구 남음). **고아 시도 처리 추가**(러너가 SIGKILL 등으로 finally 없이 죽는 경우): 시도마다 `attempt.json`+flock, `run` 시작·`cleanup`에서 잠금 풀린 시도 = orphan 행 기록 + 컨테이너·네트워크·디렉터리(로그인 사본 포함) 삭제. kill -9 시험: 컨테이너 3 남음 → cleanup → orphan 행 1·잔여 0. 장치 이전 23:10 시도 디렉터리(로그인 사본 복사 전 대기 중이었음 — 사본 없음, 에이전트 미시작)는 대상 확인 후 수동 삭제 | 통과 |
| 2026-10-25 23:30(date) | 코드 커밋 `f6094db`(30파일, docs 별도). 리뷰 루프 1 착수: BASE `f4dbdf6`, OUT=`scratchpad/tmp.JfT6k4eWt8`, mirror=`scratchpad/tmp.p6yoQnewd8`, packet = spec + ADR-0005 + 누적 diff + untracked 1(spec) + 심볼 grep. 보안 스캔: 테스트 가짜 값 2건(`sk-ant-oat01-SECRETVALUE` 등) 오탐 판정. Opus 워커 ∥ codex 병렬 | 리뷰 대기 |
| 2026-10-25 23:38(date) | 루프 1 회수: Opus F1~F11·OQ1~7. codex 1회차 bwrap(`RTM_NEWADDR`)으로 파일 미열람 → "finding 없음"(rc 0) — 출력 판정으로 실패 처리, 재시도 2·3 실패, 4회째 성공(F1~F8·OQ2). 종합: 아래 ledger L1-1~L1-19 채택 | 수정 |
| 2026-10-25 23:46(date) | 수정 반영 + fix verification 테스트(test_fix_loop1) → 131개 통과. 수정 확인 스모크(셸): 정상(layered users 1000), 기동 중 SIGINT 1초 → `interrupted`(서비스 기동 중 중단), `--keep` 보관에도 로그인 사본 없음·attempt.json 없음, 실행 중 SIGINT 1초 → `interrupted`, kill -9 → cleanup → orphan 1행·잔여 0. **스모크 중 결함 1**: make_home이 지운 `host_refresh`를 참조 → 3시도 모두 infra 행(행은 정상으로 남음) → 수정. prep 재실행(F9 확인)은 백그라운드 | 확인 중 |
| 2026-10-25 23:49(date) | prep 재실행 실패: 새 호스트 GRADLE_USER_HOME에서 wrapper 배포판 다운로드 중 네트워크 읽기 오류(첫 단계라 다른 산출물 무변경) → prep이 호스트 Gradle·JDK를 아예 쓰지 않게 변경: tracked 파일 사본을 이미지 컨테이너에서 seed 홈으로 `fakePgClasspath` 빌드. 1차 재시도는 없는 소스셋 출력 디렉터리(`classes/java/main`)를 오류 처리 → 건너뛰게 수정. 실패한 호스트 시도의 `~/.cache/ccle-runner/gradle-host`(111M) 삭제 | 수정 |
| 2026-10-25 23:55(date) | prep 성공: 가짜 PG jar 8개(이전과 같은 목록), RO 183M, 예열 448M(network none으로 두 구조 빌드), **`~/.gradle` 변경 파일 0**. 셸 확인: 가짜 PG 응답, 새 사본 첫 빌드 57초. 테스트 131 통과 | 통과 |
| 2026-10-25 23:56(date) | 수정 커밋 `244cb06`. 루프 2 착수: OUT=`scratchpad/tmp.ATHd2qv8GS`, mirror=`scratchpad/tmp.CEzynYxNDh`, packet = 누적 diff(`f4dbdf6`..) + 루프 1 수정 diff(참고) + spec + ADR + grep. 보안 스캔: 테스트 가짜 값 외 0 | 리뷰 대기 |
| 2026-10-26 ≤00:00(시각 미기록) | 루프 2 회수: codex 1회차 성공 F1~F5·OQ2, Opus F1~F10·OQ5. 종합 → L2-1~L2-15 채택. **정지 규칙 점검**: 감사 휴리스틱은 같은 영역 2회째 수정(루프 1 fetch_hosts 추가가 새 오탐) → 정규식 덧대기 대신 셸 토큰화로 재설계 + 실제 누출 경로(공개 GitHub 원격)는 호스트 이름으로 도구 무관 검출. diff는 GNU diff 옵션(--no-dereference)이 한쪽만 있는 링크·FIFO에서 rc 2 → Python 트리 비교로 교체 | 수정 |
| 2026-10-26 00:08(date) | 수정 커밋 `479df78`, 테스트 145 통과(blind 계약 갱신 4: 되쓰기 토큰 길이·첫 편집 적중 기준·오류 result 무효·python heredoc 비편집). 셸 스모크: 사본 안 `/etc/passwd`·로그인 파일 링크 → diff에 `<link: …>`만, FIFO 정상, 로그인 파일 복사·출력 → raw·diff·tail 토큰 값 마스킹 4곳(키 이름만 남음), settings deny 10개 반영 | 통과 |
| 2026-10-26 00:09(date) | 명세 §2 격리 문구 보정(마운트 4 → 3, 이벤트는 stdout — Q1-2). 루프 3(최대) 착수: OUT=`scratchpad/tmp.jUb0CjgrpK`, mirror=`scratchpad/tmp.0z2BC65FE5`, packet = 누적 diff + 루프 2 수정 diff(참고) + spec + ADR + grep | 리뷰 대기 |
| 2026-10-26 00:20(date) | 루프 3 회수: Opus F1~F8·OQ1~6, codex 1회차 성공 F1~F7·OQ1. 신규 채택 다수 → **종료 조건 미충족(review unresolved, 최대 3루프)**. 메인 확인: 예열 Gradle 캐시에 구조 흔적(god·layered·/work·패키지 경로) 없음(Opus OQ3 해소 — strings 검색 0, 'ransformingod'는 TransformingMethod 조각) | 사용자 판단 요청 |
| 2026-10-26 ≤10:38(시각 미기록 — 응답 대기) | 사용자 결정 3건: ① 외부 접속 = **허용 목록 프록시**(입력 감사는 보조) — 명세 §2 격리 보정, ADR-0005 §6.1 '네트워크 열림'과 달라짐(ADR은 이 작업 금지영역 → 후속 문서 작업) ② 겨냥 Read = **덮음 + 폭 상한**(400줄, 넓으면 청크 읽기) — ADR §5.2 '덮거나'에 맞춤(Q1-1 겹침 유지 결정 철회) ③ 리뷰 = **수정 후 타깃 재점검 1회 → user override 종료** | 결정 |
| 2026-10-26 10:38(date) | 프록시 이미지 `ccle-proxy:1`(alpine 3.20 + tinyproxy, FilterDefaultDeny, 허용 `^api\.anthropic\.com$`). 수동 확인: --internal 네트워크 직접 접속 000, 프록시 경유 API 401(인증 없음 — 도달), github.com·http 거부 + 로그 `Proxying refused on filtered domain` | 동작 |
| 2026-10-26 10:42(date) | L3-1~L3-12 반영(테스트 159 — blind 계약 갱신: 덮음·폭 상한, 로컬 nc 허용). 셸 스모크(layered): 직접 차단·API 경유·GitHub 거부 기록, DB 1000, 가짜 PG, 빌드 56초, 셸 HOME에 로그인 사본 없음, 잔여 0, egress 네트워크만 유지 | 통과 |
| 2026-10-26 10:44(date) | **Haiku 스모크(프록시, 승인분)** `_smoke-tools` layered: CLI 프록시 경유 정상, 7턴 122,561, 겨냥 3턴·첫 Edit 적중 True·새 파일 Write는 created_paths. **발견:** CLI가 스스로 `mcp-proxy.anthropic.com`(계정 MCP 커넥터 경로로 보임) 접속 → 거부 → `network` 무효 판정. 차단은 하네스 격리상 맞음 → `CLI_EXPECTED_REFUSED`로 무효 사유에서 제외(기록은 유지). 커밋 `278f44f` | 수정 |
| 2026-10-26 10:48(date) | codex post-fix 타깃 재점검(OUT=`scratchpad/tmp.HxKI5zNl7s`, mirror=`scratchpad/tmp.dKjV0rtruQ`): 12항목 중 해소 8, 부분 4(직접 접속 시도 미기록·대기 중 원본 소실·버전 None 허용·cleanup 조회 순서 경합), 신규 2(nc 옵션 값을 호스트로·메타 없는 Read를 요청 폭으로) | 수정 |
| 2026-10-26 10:51(date) | 반영 커밋 `3a4d384`(테스트 162). 직접 접속 시도는 --internal로 **차단되지만 기록되지 않음** → 한계로 남김(잔여 리스크). cleanup은 자원 목록 → 잠금 순서로 바꿈, 실행 중 cleanup: live_skipped 1·진행 중 컨테이너 유지·그 실행 정상 종료 확인. **리뷰 종료 = user override**(3루프 상한 + 타깃 재점검 1회 — 사용자 결정 2026-10-26) | 리뷰 종료 |
| 2026-10-26 10:53(date) | 완료 요약(Opus 워커, 스니펫 실파일 대조 3건)·NEXT·측정로그 → docs 커밋 | 병합·push 승인 대기 |

## 리뷰 ledger

(루프 1 — BASE `f4dbdf6`, 대상 `f6094db`)

| id | loop | source | 근거 | 내용 | disposition | status |
|---|---|---|---|---|---|---|
| L1-1 | 1 | opus F1 · codex F2 | creds.py:36-40 · runner.py:152 | 만료 시 호스트 `claude -p` 자동 호출 — 호스트 ~/.claude 쓰기(spec §4)·스모크 상한 밖 호출·병렬 동시 회전 | 채택 | fixed(제거 — 대기만, 안내 문구) |
| L1-2 | 1 | opus F2 · codex F5 | runner.py:344 · 368-380 · sweep | 예외·고아 경로에서 로그인 사본 되쓰기 누락 → 회전된 refresh 토큰 손실 | 채택 | fixed(finally에서 컨테이너 정리 뒤 write_back, sweep도 되쓰기) |
| L1-3 | 1 | opus F3 | events.py:139-151 | 부분 토큰의 output이 최종값의 1~5%(실측) — 하한임을 표시 안 함 | 채택 | fixed(docstring·tokens_source, 분석 규칙: partial 행 output 계열 불사용) |
| L1-4 | 1 | opus F4 · codex F4 | events.py:133-138 · runner.py:285 | result에 modelUsage 없으면 0토큰 valid | 채택 | fixed(assistant usage 부분 합으로 대체 + 0이면 `no_tokens` 무효) |
| L1-5 | 1 | opus F5 | events.py:233-241 | offset=1 전체 Read·실패 Read도 겨냥으로 셈(포화 우회) | 채택 | fixed(whole_file_read → 전체 읽기, is_error 제외) |
| L1-6 | 1 | opus F6 | runner.py:330·340-342·511 | 기동 단계 SIGINT가 실행을 못 멈춤 | 채택 | fixed(기동 대기·생성 전후·attach 1초 폴링에서 _stop) |
| L1-7 | 1 | opus F7 · codex F8 | runner.py:377-380 · 586 | 잠금 해제 순서·--keep → 가짜 orphan 행, --keep이 로그인 사본 보관 | 채택 | fixed(행 기록 뒤 잠금 쥔 채 attempt.json 삭제, keep도 사본 삭제) |
| L1-8 | 1 | opus F8 · codex F1 | runner.py:299-365 · raw 저장 | 에이전트가 로그인 파일을 출력하면 토큰 원문이 raw·tail에 보관 | 채택 | fixed(사본의 access·refresh 값으로 raw·stderr 치환, tail은 치환 뒤) |
| L1-9 | 1 | opus F9 | runner.py:419-421 | prep 호스트 gradlew가 ~/.gradle 사용(spec §4) | 채택 | fixed(GRADLE_USER_HOME=~/.cache/ccle-runner/gradle-host) — 이전 prep 2회는 ~/.gradle을 썼음(위반 사실 기록) |
| L1-10 | 1 | opus F10 | creds.py:79-82 | 임시 파일 umask 권한·fsync 없음 | 채택 | fixed(O_CREAT 0600 + fsync + 디렉터리 fsync) |
| L1-11 | 1 | opus F11 | runner.py:244 | 깨진 runs.jsonl 줄이 재개를 막음 | 채택 | fixed(건너뛰고 개수 알림) |
| L1-12 | 1 | codex F3 | events.py:20-26 | scheme 없는 `curl evil.com/x` 통과 | 채택 | fixed(curl·wget 위치 인자 호스트 검사, `$VAR`·로컬·파일 경로 제외) |
| L1-13 | 1 | codex F6 | runner.py:311-324 | 표식 생성 전 실패는 행 없음 | 채택 | fixed(시도 식별 직후 try) |
| L1-14 | 1 | codex F7 | runner.py:168-171 | docker 정리 실패 무시 | 채택 | fixed(정리 후 라벨로 재조회, 남으면 cleanup_left + 무효) |
| L1-15 | 1 | opus OQ2 | events.py:163 · runner.py:542 | result 없이 한도 오류로 끝나면 no_result(재시도 안 됨) | 채택 | fixed(stderr 한도 문구 → usage_limit) |
| L1-16 | 1 | opus OQ3 | events.py:18 | 감사 `/tmp/claude-\d+`가 컨테이너 안 CLI 임시 경로 정상 사용을 무효화 | 채택 | fixed(패턴 제거, blind 계약 갱신) |
| L1-17 | 1 | opus OQ5 | runner.py:285 | 실행 중 DB·가짜 PG 사망 미감지 | 채택 | fixed(실행 뒤 Running 확인 → infra 무효) |
| L1-18 | 1 | opus OQ6 | ADR §8 "과제별 직렬" · runner.py:252 | 같은 과제 두 구조 동시 실행 | 채택 | fixed(과제별 잠금) |
| L1-19 | 1 | opus OQ7 | runner.py:325 | 디스크 가드가 HOME fs만 | 채택 | fixed(캐시·결과·docker data-root 중 최소) |
| Q1-1 | 1 | opus OQ1 | ADR §5.2 "덮거나" · events.py `_overlap` | 겨냥 = 겹침인가 포함인가 | open question → 메인 결정: **겹침 유지**(Grep·Edit 판정과 같은 기준, span이 함수 범위면 포함 기준은 부분 읽기를 놓침) — 사용자 보고 | — |
| Q1-2 | 1 | opus OQ4 · codex OQ1 | spec §2 마운트 4개 vs 코드 3개 | 이벤트는 stdout 캡처(마운트 없음) — 더 엄격 | 범위 밖(명세 문구 보정) | — |
| Q1-3 | 1 | codex OQ2 | spec §5 | 병렬·SIGINT·외부 접속 확인 근거가 packet에 없음 | 범위 밖(log 타임라인에 실행 기록 — 리뷰어에게는 ledger 비공개 원칙) | — |
| L2-1 | 2 | codex F1 · opus F5 | runner.py:400·447-456 | 마스킹 비밀값을 실행 전 사본에서만 — 회전된 새 토큰 미마스킹 | 채택 | fixed(실행 뒤 사본·원본 값 합집합) |
| L2-2 | 2 | codex F2 | events.py:170-188 | result 있는데 modelUsage 없으면 부분 합으로 valid | 채택 | fixed(no_modelusage 무효, 부분 합은 시간 상한 전용) |
| L2-3 | 2 | codex F3 · opus F3 | events.py:399·424-435 | Bash 편집(tee·로그 리다이렉트 포함)이 첫 편집을 선점해 적중 None | 채택 | fixed(첫 편집 적중 = 첫 Edit 호출, Bash 편집은 사본 안 대상만·/tmp·/dev·.log·--dry-run 제외) |
| L2-4 | 2 | codex F4 | runner.py:476-510 | sweep의 되쓰기 예외가 행·정리를 막음 | 채택 | fixed(되쓰기·마스킹 오류를 행에 담고 계속) |
| L2-5 | 2 | codex F5 | runner.py:307-323 | 첫 응답 전 시간 상한이 no_tokens 무효 | 채택 | fixed(시간 상한은 0이어도 valid·censored) |
| L2-6 | 2 | opus F1 · F2 | events.py:30·47-59·224 | fetch_hosts 오탐(리다이렉트·여러 줄·-sX·--json), 비 fetch 명령의 URL 문자열도 위반 | 채택 | fixed(셸 토큰화 재설계 — 정지 규칙 적용, 원격 저장소 호스트는 입력 전체에서) |
| L2-7 | 2 | opus F4 | runner.py:286-294·417 | diff가 링크를 따라감·timeout 없음·diff 미마스킹 | 채택 | fixed(Python 트리 diff — 링크·특수 파일 미개방, diff 마스킹) |
| L2-8 | 2 | opus F6 | creds.py:72-79 | 되쓰기가 expiresAt만 보고 원본 교체 | 채택 | fixed(토큰 형식·원본 최상위 키 보존·expiresAt 형식 검증, 실패 시 원본 유지) |
| L2-9 | 2 | opus F7 | events.py:206 · runner.py:313 | 오류 result가 valid·uncensored, 한도 문구 누락 | 채택 | fixed(agent_error 무효, 한도 문구 확대) |
| L2-10 | 2 | opus F8 | runner.py:80 | 격리 밖 도구(RemoteTrigger·Cron·ScheduleWakeup·Workflow·SendMessage·ListAgents) 미차단, 다중 result | 채택 | fixed(deny + 감사, result_count 기록) |
| L2-11 | 2 | opus F9 | runner.py:671 | 중단 뒤 미시작 job도 interrupted 행 | 채택 | fixed(행 없이 반환, 출력에 '시작 안 함') |
| L2-12 | 2 | opus F10 | events.py:348 | limit만 준 Read를 전체 읽기로 | 채택 | fixed(결과 범위로 판정, 부분 읽기는 겨냥 대상) |
| L2-13 | 2 | opus OQ1 | runner.py:254-283 | 같은 결과 디렉터리 러너 2개 → 중복 valid | 채택 | fixed(결과 디렉터리 flock) |
| L2-14 | 2 | opus OQ3 · codex OQ2 | events.py:441 | 하네스 미로드 자동 판정 없음 | 채택 | fixed(harness_problems — MCP·비내장 플러그인·HOME 밖 메모리·cwd → 무효) |
| L2-15 | 2 | opus OQ5 | runner.py:379·457 | 신호 처리기가 같은 Lock 재획득 교착 | 채택 | fixed(RLock) |
| Q2-1 | 2 | opus OQ4 | creds write_back | 원본의 다른 최상위 키 보존 | L2-8로 흡수 | — |
| Q2-2 | 2 | opus OQ2 · codex OQ1 | spec §2 마운트 4 vs 3 | Q1-2와 같음 | 범위 밖(명세 문구 보정) | — |
| L3-1 | 3 | codex F1 · opus F3 · opus F4 | events.py:96·271-277 | 감사 우회(`curl "$u"`·`timeout curl`·`sh -c`)와 오탐(Write 내용·heredoc·인용 문자열) — 입력 문자열 감사의 근본 한계(3루프 연속 지적) | 채택 → 사용자 결정(프록시) | fixed(`278f44f` — --internal + 허용 목록 프록시, 감사는 래퍼·sh -c 벗기기·편집 내용 제외) |
| L3-2 | 3 | opus F2 · codex F2 | events.py:326-335 | 겨냥 Read 폭 상한 없음(청크 Read로 god 포화), limit만 있는 Read 겨냥 인정 — ADR §5.2 '덮거나'·'offset을 준' | 채택 → 사용자 결정(덮음 + 폭 400) | fixed(`278f44f`) |
| L3-3 | 3 | opus F1 | runner.py:363-376 | 시간 상한 실행이 한도·과부하·무활동이어도 valid | 채택 | fixed(`278f44f`) |
| L3-4 | 3 | opus F5 | creds.py:18-49 | 원본 없음·깨짐에도 6시간 대기 | 채택 | fixed(`278f44f`) |
| L3-5 | 3 | opus F6 | runner.py:736-740 | case_lock 대기 job이 중단 뒤 interrupted 행 | 채택 | fixed(`278f44f`) |
| L3-6 | 3 | opus F7 | runner.py:428 | CLI 버전 실측 대조 없음 | 채택 | fixed(`278f44f`) |
| L3-7 | 3 | opus F8 | events.py:509-517 | 새 파일 Write가 첫 편집 적중을 False로 | 채택 | fixed(`278f44f`) |
| L3-8 | 3 | codex F3 | runner.py:688 | cleanup이 다른 러너의 진행 중 실행 자원까지 삭제 | 채택 | fixed(`278f44f`) |
| L3-9 | 3 | codex F4 | runner.py:524 | --keep이 작업 사본 보관(spec §2 diff만) | 채택 | fixed(`278f44f`) |
| L3-10 | 3 | codex F5 | runner.py:145 | 셸(무료 인프라) 모드가 로그인 파일에 의존 | 채택 | fixed(`278f44f`) |
| L3-11 | 3 | codex F6 | creds.py:66 | os.write 부분 쓰기 미확인 후 교체 | 채택 | fixed(`278f44f`) |
| L3-12 | 3 | codex F7 | events.py:533 · runner.py:488 | init 이벤트 없으면 하네스 검사 통과 | 채택 | fixed(`278f44f`) |
| Q3-1 | 3 | opus OQ1 | creds.py:75-82 | 호스트 CLI 갱신과 되쓰기 경합(러너 잠금 밖) | open question — 창 작음, 수용·문서화 제안 | — |
| Q3-2 | 3 | opus OQ2 | creds.py:91 | 적대적 에이전트가 늦은 만료 + 가짜 토큰을 쓰면 되쓰기 | open question — 위협 모델 결정 | — |
| Q3-3 | 3 | opus OQ3 | runner.py:48-51 | 예열 캐시에 다른 구조 흔적 | 메인 확인으로 해소(흔적 0) | — |
| Q3-4 | 3 | opus OQ6 | events.py:495 | lines_read 하위 에이전트 합산 규칙 | 분석 규칙(과제 작업) | — |
| R-1 | 재점검 | codex 항목 1 | runner.py:535 | 프록시를 거치지 않은 직접 접속 시도는 차단되지만 기록 없음 | 범위 밖(OS 한계 — 잔여 리스크로 보고, 필요하면 패킷 로그) | user-deferred |
| R-2 | 재점검 | codex 항목 4 | creds.py:39 | 대기 중 원본 소실 시 계속 대기 | 채택 | fixed(`3a4d384`) |
| R-3 | 재점검 | codex 항목 6 | runner.py:533 | init.cli_version None 허용 | 채택 | fixed(`3a4d384`) |
| R-4 | 재점검 | codex 항목 8 | runner.py:749 | cleanup 조회 순서 경합 | 채택 | fixed(`3a4d384` — 자원 목록 먼저, 잠금 나중) |
| R-5 | 재점검 | codex F1 | events.py:105 | `nc -w 2 db` 옵션 값을 호스트로 | 채택 | fixed(`3a4d384`, sudo -u 등 래퍼 옵션 값도) |
| R-6 | 재점검 | codex F2 | events.py:366 · 215 | 메타 없는 Read를 요청 폭으로 → 겨냥 누락 | 채택 | fixed(`3a4d384` — 결과 텍스트 줄 번호가 offset과 맞으면 그 범위) |

## 생략한 검증

- 리뷰 종료 조건(높음: 신규 채택 0) 미충족 — 3루프 상한 + 타깃 재점검 후 **user override**(사용자 결정 2026-10-26). 재점검 반영분(`3a4d384`)은 리뷰어 재확인 없이 메인 테스트(162)·셸 스모크로만 확인.
- 직접 접속 시도(프록시 미경유) 기록 없음(R-1) — 차단은 됨.
- `--max-budget-usd` 실제 중단·사용량 한도 실제 오류 형식은 미실측(ADR §7 파일럿 확인 항목 그대로).

## 완료 요약

**결과:** `experiment/runner/runner.py`로 (과제, 구조, 모델, 반복) 한 개를 실행한다. 흐름은 다음과 같다.

1. 작업 사본 조립
2. `--internal` 실행 네트워크에 DB(`db`)·가짜 PG·허용 목록 프록시 기동
3. 에이전트 컨테이너 마운트를 허용 목록과 대조(다르면 시작 안 함)
4. `claude -p … stream-json` 실행(시간 상한 kill)
5. 파싱(modelUsage 토큰·턴·겨냥·첫 편집)과 감사
6. `runs.jsonl` 1행 기록(무효도 사유와 함께)
7. 정리

병렬·재개·고아 sweep·`cleanup` 포함. 커밋 `f6094db`·`244cb06`·`479df78`·`278f44f`·`3a4d384`(base `f4dbdf6`), 테스트 162 통과. 가정 1(복사한 로그인 파일로 인증·modelUsage)·가정 2(RO 캐시로 오프라인 빌드) 성립.

### 핵심 구조

마운트 허용 목록 정확 일치 + privileged·CapAdd 거부(spec §2) — `runner.py`:
```python
def allowed_mounts(work: Path, home: Path) -> set:
    return {(str(work), AGENT_WORK, True), (str(home), AGENT_HOME, True), (str(GRADLE_RO), AGENT_GRADLE_RO, False)}


def check_mounts(inspected: list, allowed: set) -> list:
    """docker inspect .Mounts 가 허용 목록과 정확히 같은지. 차이 목록(빈 목록 = 통과)."""
    got = {(m.get("Source"), m.get("Destination"), bool(m.get("RW"))) for m in inspected}
    return sorted(f"extra:{x}" for x in got - allowed) + sorted(f"missing:{x}" for x in allowed - got)
```

외부 접속은 `--internal` + 프록시로만(L3-1, 사용자 결정 ①) — `runner.py`:
```python
def start_services(name: str) -> None:
    """--internal 실행 네트워크(외부로 나갈 경로 없음) + DB(db) + 가짜 PG + 허용 목록 프록시(proxy, egress 에도 연결)."""
    lab = ["--label", f"{LABEL}={name}"]
    ensure_egress()
    sh(["docker", "network", "create", "--internal", *lab, name])
    sh(["docker", "run", "-d", "--name", f"{name}-proxy", *lab, "--network", EGRESS_NET, PROXY_IMAGE])
    sh(["docker", "network", "connect", "--alias", "proxy", name, f"{name}-proxy"])
```

classify 무효 사유(no_modelusage — L1-4·L2-2, agent_error — L2-9, usage_limit·no_activity — L1-15·L3-3) — `runner.py`:
```python
    invalid = None
    if violations:
        invalid = "audit"
    elif reason in ("usage_limit", "no_result", "no_activity"):
        invalid = reason
    elif reason == "error":
        invalid = "agent_error"
    elif reason in ("normal", "budget") and tok is not None and tok.get("tokens_source") != "modelUsage":
        invalid = "no_modelusage"
    return {"end_reason": reason, "censored": reason in ("timeout", "budget"), "valid": invalid is None,
            "invalid_reason": invalid}
```

겨냥 Read = 범위를 전부 덮고 폭 ≤ 400줄(L3-2, 사용자 결정 ②) — `events.py`:
```python
READ_TARGET_MAX = 400
```
```python
    if c.name == "Read" and c.input.get("offset") is not None and not whole_file_read(c) and not chunk_read(c):
        p = norm_path(str(c.input.get("file_path", "")))
        s, e = read_range(c)
        hit = [sp for sp in spans if sp.path == p and s <= sp.start and sp.end <= e]
```

### 실측 스모크

| 실행 | 결과 |
|---|---|
| Haiku 1(가정 1) | 인증·`result.modelUsage`·`mcp_servers=[]`·내장 플러그인 2개뿐·로그인 파일 갱신 없음 |
| Haiku 2 `_smoke` god | valid·normal, 4턴, 83,240, 감사 0, 되쓰기 없음 |
| Haiku 3 layered `--timeout 8` | timeout·censored·valid, rc 137, 부분 토큰 63,055, 잔여 0 |
| Haiku 4 `_smoke-tools`(추가 승인) | valid, 8턴, 171,886 — 2.1.308에 Grep 도구 없음·Edit 결과에 줄 번호 없음 → `tool_use_result` 기반 파서 |
| Haiku 5 프록시(추가 승인) | 7턴, 122,561, 첫 Edit 적중 True — CLI 자체 `mcp-proxy.anthropic.com` 거부 → `CLI_EXPECTED_REFUSED` |
| 셸(무료) | uid 1000·마운트 3·DB 1000·가짜 PG·오프라인 bootJar · 병렬 3 충돌 없음·잔여 0 · SIGINT(기동·실행·대기) → interrupted · kill -9 → cleanup → orphan 1·잔여 0 · 프록시: 직접 000·API 401(도달)·github 거부 · 링크·FIFO·로그인 파일 복사 → diff·raw·tail 마스킹 |

- 에이전트 실행 5회(명세 3 + 사용자 추가 승인 2). 사고 1: 23:25 테스트가 실제 호스트 claude(Haiku 'ok')를 띄움 → 즉시 kill, 로그인 파일 무변경, 이후 호스트 CLI 호출 경로 자체를 제거.

### 리뷰 경과

| 단계 | 채택 | open question·범위 밖 | 비고 |
|---|---|---|---|
| 루프 1 | 19 (L1-1~19) | 3 | |
| 루프 2 | 15 (L2-1~15) | 2 | 감사 정지 규칙 → 셸 토큰화 재설계 |
| 루프 3 | 12 (L3-1~12) | 4 | L3-1(프록시)·L3-2(덮음+폭)는 사용자 결정 |
| 타깃 재점검(codex) | 5 (R-2~6) | R-1 이월 | 12항목 해소 8·부분 4, 신규 2 |

채택 합계 51. 종료 = **user override**(높음 종료 조건 신규 0 미충족).

### 잔여 리스크·이월

- `3a4d384`(재점검 반영)는 리뷰어 재확인 없음 — 테스트 162·셸 스모크로만 확인.
- R-1 직접 접속 시도는 차단되나 기록 없음 · `--max-budget-usd` 실제 중단·사용량 한도 오류 형식 미실측(파일럿).
- Q3-1 호스트 CLI 갱신과 되쓰기 경합(창 작음) · Q3-2 적대적 에이전트의 가짜 토큰 되쓰기(위협 모델 결정 필요).
- ADR-0005 갱신 필요(NEXT N0 ①′): §6.1 프록시 · §5.2 겨냥 정의·Bash grep/sed 포함·첫 편집 적중 · §6.4 무효 사유 확장.

### 범위

`git diff --name-only f4dbdf6..HEAD` = `experiment/` 36 + `.gitignore` 1(+3,879). 금지영역(god·layered·behavior-tests·tools·docker·ADR) 무변경. CS 이슈 아카이브는 study-note 쓰기 보류 결정으로 보류(NEXT).
