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
| 2026-10-19 | packet: base `3f0f8ff` · OUT `scratchpad/rv.CjPG` · 미러 `scratchpad/mirror.je0n` · diff 1015줄·related 165줄·spec 89줄, untracked 0. 보안 스캔: packet 0건, 미러 `application.yml` `${DB_PASSWORD:ccle}` 2건 = 일회용 로컬 DB 기본값(오탐) | Opus 리뷰 워커 ∥ codex 병렬 시작 |
| 2026-10-19 | 리뷰 회수: codex 3건(F1 conc 응답 축약·F2 recov PAYING 미단언·F3 컨테이너 삭제 실패 무시), Opus 8건+OQ 5. 메인 종합 R1~R10 채택·Q1~Q4. codex 감사(1회 성공) A1 R2 id 정규화는 대응 보존 · A2 정리 후 프로세스 생존 확인 · A3 health curl 시간 제한 · A4 시각 오프셋 명세 불일치 | 원문·종합표 `scratchpad/rv.CjPG/results/` |
| 2026-10-20 | 사용자: "왜 자꾸 고치기만 하는거야" → 메인이 도달 가능성 재분류(거짓 통과 R1·R2·R5 / 나머지 7건은 거짓 통과 아님) 보고. 사용자 결정: **"10건을 수정"** + 우선순위 요청. A4는 명세를 구현대로 정정(재합의 5) | 수정 순서 R1→R2(+A1)→R5→R4→R8(+A3)→R6·R7(+A2)→R3→R9→R10. 워커에 브리핑 |
| 2026-10-20 16:32~16:56 | task 04-fix 워커(Opus): R1~R10 반영(lib.sh +137 등 7파일). R3 로거 이름은 지시와 달리 제거(god `Marketplace` vs layered `StalePaymentRecovery` — 분리로 생긴 구조 차이) → 메인 수용 | R1 `EQ_SQL_LOG=0 run.sh errors` exit 2 · R2 진 요청 items 비운 변조 jar conc exit 1(예전 축약이면 못 잡음) · SIGINT 2회 130·정리 0 · before-L2 exit 1 · 잘못된 jar exit 2 · HEAD all exit 0(663초). 메인 확인: 금지영역 diff 0·잔여 0 → 코드 커밋 72ddf59 |
| 2026-10-20 | post-fix 재점검(codex, packet base `ee3b675`, OUT `scratchpad/rv.4D01`, 미러 `mirror.Iqan`): R1·R3·R4·R5·R8·R9·R10 해소. 신규 N1(높음) `norm_conc_pair`가 tradeId까지 기호화 — 엉뚱한 체결 id도 #tradeId1 · N2(중간) docker 데몬 오류 시 잔여 확인이 "없음"으로 처리 · N3(낮음) 차이(1)+정리 실패면 1 유지 | 메인 판정: N1 채택(거짓 통과 계열) — 메인 직접 수정: 쌍이 보낸 체결이면 `#sent`, 아니면 원값(conc 줄에 trades= 추가). N3 = README 문구 정정(1 유지는 의도 — 차이 결과가 더 유용). N2 = 보류(데몬 장애에서만, 다음 실행의 포트·컨테이너 확인이 드러냄 — 거짓 통과 아님) |
| 2026-10-20 17:02~17:08 | N1 수정 검증(메인): 규칙 단위 입력(보낸 체결 → #sent, 밖 id 99 → 원값) · HEAD conc exit 0(응답 240·SQL 1550·DB 문장 1933, #sent 60줄) · R2 변조 jar conc exit 1(응답 층만) · 잔여 0 | 코드 커밋 eb5b78d. 리뷰 종료(中: 채택 전부 fixed, N2 보류 = 사용자 결정 "10건 수정" 범위 밖 신규 — 메인 판정, 거짓 통과 아님) |
| 2026-10-20 | 사이클 마감: NEXT 갱신(N1 완료 이력으로, 2단계에 동치 확인 수단·하네스 한계 보류 항목) · README 동치 비교 절·진행 행 · measurement-log 1행 · CS 이슈 아카이브는 study-note 쓰기 보류 방침으로 0건 처리 | 완료 요약: Opus 워커 초안(실파일·git show 복사) → 메인이 스니펫 1건 실파일 대조 후 반영 |

## 리뷰 ledger

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| R1 | 1 | opus | lib.sh compare_layers `[ -s ]` + 추출기 MARK 출력 | 채택 | fixed | 1 |
| R2 | 1 | codex+opus | conc.sh outcome_of 본문 축약 | 채택 | fixed | 1 |
| R3 | 1 | opus | fail.sh:74·recov.sh:39 이름 없는 sed | 채택 | fixed(로거도 제거 — 구조 차이) | 1 |
| R4 | 1 | opus | conc.sh pair echo 안 명령 치환 | 채택 | fixed | 1 |
| R5 | 1 | codex+opus | recov.sh:29 PAYING=2 미단언 | 채택 | fixed | 1 |
| R6 | 1 | codex | lib.sh app_cleanup docker rm 실패 무시 | 채택 | fixed | 1 |
| R7 | 1 | opus | lib.sh app_cleanup 정리 중 두 번째 신호 | 채택 | fixed | 1 |
| R8 | 1 | opus+감사 A3 | curl `--max-time` 없음(health 포함) | 채택 | fixed | 1 |
| R9 | 1 | opus | run.sh·measure-expiry.sh 중복 | 채택 | fixed | 1 |
| R10 | 1 | opus OQ | README poll_fold 한계·DETAIL·로그 회전 | 채택 | fixed | 1 |
| A1 | 1 | 감사 | R2 id 정규화는 대응 보존 | 채택(R2에 병합) | fixed | 1 |
| A2 | 1 | 감사 | 정리 뒤 프로세스 생존 확인 | 채택(R6에 병합) | fixed | 1 |
| A4 | 1 | 감사 | 명세 "시각 오프셋" vs 구현 | 채택(문서) | fixed — 재합의 5 | 1 |
| Q1 | 1 | opus OQ | lib.sh docker logs 회전 | open question | README 가정으로 기록 | — |
| Q3 | 1 | codex OQ | §5 실행 결과 미러에 없음 | 범위 밖(실행 증거는 log 소유) | — | — |
| Q4 | 1 | opus | 명세 §3 FakePg 경로(test → main) | 문서 정정 | fixed | 1 |
| N1 | recheck | codex | lib.sh norm_conc_pair tradeId 기호화 | 채택 | fixed(eb5b78d) | recheck |
| N2 | recheck | codex | lib.sh cleanup_left docker 데몬 오류 = 잔여 없음 | 보류 | user-deferred 아님 — 메인 보류(데몬 장애 한정, 거짓 통과 아님) | — |
| N3 | recheck | codex | 차이(1)+정리 실패 → 1 유지 | 채택(문서) | fixed — README 문구 | recheck |

## 생략한 검증

- (없음)

## 완료 요약

**결과.** `tools/equiv/run.sh all`은 god·layered의 7개 시나리오(run·pay·batch·recov·fail·conc·errors)를 응답·새 행·Hibernate SQL·DB 문장 로그 4층으로 비교해 판정한다. 결과는 0(같음)·1(차이)·2(비교 불성립)·130(중단)이다. HEAD에서는 차이 0, exit 0이다. 층별로 일부러 만든 차이는 exit 1과 차이 파일로, 실행 실패와 빈 비교는 exit 2로 잡는다. 듀얼 리뷰 1패스와 post-fix 재점검까지 마쳤고, 채택한 지적은 모두 고쳤다. 보류는 N2 1건이다.

### 게이트 (명세 §5)

| §5 항목 | 결과 (log 실측) |
|---|---|
| `run.sh all` 2회 exit 0, 층별 파일·줄 수 출력 | task 02: 2회 모두 exit 0(10분 24초·25초), 7종 4층 같음, 빈 비교 0. 수정 뒤 HEAD all exit 0: 656초(03b), 663초(04-fix) |
| 응답·행 차이 검출 | 응답 m3(message) exit 1 · 행 m4(createdAt) exit 1 |
| Hibernate SQL 차이 검출 | fail m1(접는 구간 밖 +1문) exit 1 |
| DB 문장 로그 차이 검출 | `layered-before-L2.jar` exit 1, DB 문장 층만 차이. task 01에서 run 시나리오 +BEGIN READ ONLY 17·+COMMIT 17, task 03에서 141→175 |
| 오류 경로 message | `layered-6f5a3a7.jar` errors exit 1 |
| 실패 경로 → exit ≠ 0 | 잘못된 jar·가짜 PG kill·빈 층(`EQ_SQL_LOG=0`)·덤프 0줄 각 exit 2. 수정 뒤(R1) `EQ_SQL_LOG=0 run.sh errors` exit 2 |
| 정리(정상·실패·SIGINT) | SIGINT exit 130, 정리 확인(11초). 수정 뒤 SIGINT 2회 모두 130, 잔여 0. 매 실행 뒤 `ccle-equiv-*` 컨테이너·java 잔여 0 |
| behavior 불변·금지영역 | `tools/behavior/run.sh` god·layered exit 0(135, verify 위반 0). 금지영역 diff 0(task 02·03·03b·04-fix마다 확인) |
| conc 집합 비교 검출(재합의 3·4) | m2a jar로 conc exit 1(sql·pgstmt 각 +1줄, approveCount만). HEAD conc 2회 exit 0(동시 구간 집합 sql 20·pgstmt 30줄) |
| conc 응답 본문 비교(R2·N1) | 진 요청의 items를 비운 변조 jar로 conc exit 1(예전 축약이면 못 잡음). N1 수정 뒤 HEAD conc exit 0(응답 240·SQL 1550·DB 문장 1933줄, #sent 60줄), 같은 변조 jar는 exit 1(응답 층만) |
| 리뷰(중간 = 듀얼 1패스) | Opus 워커와 codex 병렬 → 메인 종합 R1~R10 → codex 감사 A1~A4 → post-fix 재점검(codex) N1~N3. 채택분 전부 fixed, N2 보류 |

### 규모

| 파일 | 줄 수 (`wc -l`) |
|---|---|
| `tools/equiv/lib.sh` | 368 |
| `tools/equiv/README.md` | 94 |
| `tools/equiv/run.sh` | 56 |
| `tools/equiv/measure-expiry.sh` | 33 |
| `tools/equiv/scenarios/fail.sh` | 77 |
| `tools/equiv/scenarios/batch.sh` | 57 |
| `tools/equiv/scenarios/conc.sh` | 57 |
| `tools/equiv/scenarios/errors.sh` | 52 |
| `tools/equiv/scenarios/pay.sh` | 49 |
| `tools/equiv/scenarios/recov.sh` | 46 |
| `tools/equiv/scenarios/run.sh` | 41 |
| **tools/equiv 합계** | **930** |

커밋: `ac4d909` 명세·log · `7083347` 공통 lib·진입점·입찰 시나리오 · `04703a6` 나머지 6종·측정·README · `483fa6e` 재합의 1~3 · `18a976f` conc 집합 비교·준비 문장 이름 · `ee3b675` 재합의 4 · `72ddf59` 리뷰 수정 10건 · `eb5b78d` N1(tradeId) — 그 뒤 마감 docs 커밋.

### 핵심 diff

**1. 빈 층 판정 — 표식만 남은 층도 실패 (R1)**

before (`git show 18a976f:tools/equiv/lib.sh`):
```bash
    gl=$(cat "$d/god/norm/$layer"/* | wc -l); ll=$(cat "$d/layered/norm/$layer"/* | wc -l)
    for f in "$d/god/norm/$layer"/* "$d/layered/norm/$layer"/*; do
      [ -s "$f" ] || { echo "[$sc] ${EQ_LAYER_NAME[$layer]}: 빈 파일 ${f#"$d"/} — 비교하지 않은 것으로 실패" >&2; res=2; }
    done
    rc=0; diff -ruN "$d/god/norm/$layer" "$d/layered/norm/$layer" > "$d/diff/$layer.diff" || rc=$?
```
after (HEAD `tools/equiv/lib.sh`):
```bash
    # 줄 수는 mark 표식(== MARK)을 뺀 내용 줄만 센다 — 표식만 남은 층은 비교한 것이 없다
    gl=$(cat "$d/god/norm/$layer"/* | grep -vc '^== MARK' || true); ll=$(cat "$d/layered/norm/$layer"/* | grep -vc '^== MARK' || true)
    gm=$(cat "$d/god/norm/$layer"/* | grep -c '^== MARK' || true); lm=$(cat "$d/layered/norm/$layer"/* | grep -c '^== MARK' || true)
    for f in "$d/god/norm/$layer"/* "$d/layered/norm/$layer"/*; do
      [ -s "$f" ] || { echo "[$sc] ${EQ_LAYER_NAME[$layer]}: 빈 파일 ${f#"$d"/} — 비교하지 않은 것으로 실패" >&2; res=2; }
    done
    if [ "$gl" -eq 0 ] || [ "$ll" -eq 0 ]; then
      echo "[$sc] ${EQ_LAYER_NAME[$layer]}: 내용 줄 0(god $gl / layered $ll, 표식 제외) — 비교하지 않은 것으로 실패" >&2; res=2
    fi
```

**2. conc 응답 — 상태 코드:status 축약에서 본문 전체 비교로 (R2)**

before (`git show 04703a6:tools/equiv/scenarios/conc.sh`):
```bash
outcome_of() {
  local body code
  body=$(cat "$1.body"); code=$(cat "$1.code")
  if [[ $body =~ \"code\":\"([A-Z_]+)\" ]] || [[ $body =~ \"status\":\"([A-Z_]+)\" ]]; then echo "$code:${BASH_REMATCH[1]}"; else echo "$code:?"; fi
}
```
after (HEAD `tools/equiv/scenarios/conc.sh`):
```bash
response_of() { # <파일 접두> → "<상태 코드> <본문 전체>"
  local code body
  code=$(cat "$1.code"); body=$(cat "$1.body")
  echo "$code $body"
}
```
비결정 값은 lib.sh의 `norm_conc_pair` 규칙 하나에서만 바꾼다(두 응답 정렬, orderId·orderItemId 기호화, tradeId는 보낸 체결 여부).

**3. R2 수정이 만든 N1 — tradeId 기호화를 보낸 체결 여부로 좁힘** (`git show eb5b78d -- tools/equiv/lib.sh`)
```diff
         k = m; sub(/":.*/, "", k); sub(/^"/, "", k); v = m; sub(/.*":/, "", v)
+        if (k == "tradeId") { out = out pre "\"tradeId\":" ((v in sent) ? "#sent" : v); continue }
         if (!((k, v) in seen)) seen[k, v] = k (++cnt[k])
         out = out pre "\"" k "\":#" seen[k, v]
 ...
-      split("", seen); split("", cnt)
+      split("", seen); split("", cnt); split("", sent)
+      tr = $1; sub(/.*trades=/, "", tr); n = split(tr, ts, ","); for (i = 1; i <= n; i++) sent[ts[i]] = 1
```

### 교훈

- **명세 재합의 5회**(빈 문장, poll_fold 한계, conc 집합 비교, 동시 구간 준비 문장 이름, 시각 문구). 1~4는 실행해 보고서야 드러난 비결정·한계다. 정규화를 "확인된 비결정만"으로 닫아 두고 새 비결정이 보이면 워커가 규칙을 붙이지 않고 멈추게 한 방식이 규칙의 무음 확장을 막았다(task 03 마지막 all exit 1에서 지시대로 멈춤).
- **리뷰 지적은 "거짓 통과에 닿는가"로 먼저 분류해야 했다.** 사용자 지적("왜 자꾸 고치기만 하는거야") 뒤에야 도달 가능성으로 재분류했고, 거짓 통과는 R1·R2·R5뿐이었다. 이 분류가 수정 순서와 N2 보류의 기준이 됐다.
- **수정이 새 거짓 통과 경로를 만들었다(N1).** 축약을 없앤 자리에서 tradeId 기호화로 새 구멍이 생겼고, post-fix 재점검이 잡았다.
- **정보를 버리는 정규화(접기·집합화)는 일부러 만든 차이로 반증해 봐야 한다.** 가정 2는 m2b에서 exit 0으로 부분 반증됐다.

### 남은 한계

- poll_fold m2b: 접는 구간 안에서 반복 단위가 통째로 늘어나는 변경은 못 잡는다(재합의 2 수용). 구간 안 연속 동일 블록도 폴링이 아니어도 접힌다.
- N2 보류: docker 데몬 오류 시 `cleanup_left`가 잔여 없음으로 처리. 데몬 장애 한정, 다음 실행의 포트·컨테이너 확인에서 드러나 거짓 통과 아님.
- 직렬 구간의 준비 문장 이름은 그대로 비교한다. 연결 배정이 달라지면 이름 차이만으로 실패할 수 있다(지금까지 관측 0).
- docker 로그 회전 없음 가정(README 명시).
