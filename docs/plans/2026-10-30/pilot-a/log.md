# log — 파일럿 A

> 명세: `requirement-spec.md` · 브랜치 `impl/pilot-a`(main `0744368`) · 모드 auto

## 타임라인

| 시각 | 사건 | 결과 |
|---|---|---|
| 2026-10-30 21:52 | tasks-port 병합·push(`0744368`) 뒤 인터뷰 4문항(parallel 2 · 낱말 점검 후 비대칭이면 주석 정리 · budget 5 · §6.3 판정·보고만) → spec 합의(claude 4회 + 재실행 1회 승인) → set-state | task 01 |
| 2026-10-30 21:55 | task 01 낱말 대칭 점검(규모 확장 추가분 `ae6deda..HEAD` src/main, TASK 핵심어 21개 × 주석/코드): layered 주석 적중이 god의 약 2배 — 추가분 주석 줄 수 비 1.83(549/300)·확장 전 1.98과 같은 수준이고, 적중 줄은 전부 다른 기능(입고 취소·문의 7일·분쟁 반영 등) 서술, 고칠 곳 파일 0·규칙 서술 0. TP-1 4곳 대조: god 짝에 없는 구절 2곳만 제거 — `SellerProfile.kt:9` '— 입찰·체결·발송 기한에 영향이 없다'(god :3358 '휴가는 표시만 한다'), `Wishlist.kt:152` '구매 제한 중에도 등록한다'(god :2638에 없음). 나머지 2곳(Wishlist.kt:134·JpaNotificationRepositories.kt:130)은 god에 같은 뜻 문장 있음 → 유지. 검증: numstat 1/1·1/1(줄 수 그대로), oracle 패치 42 dry-run CLEAN 42, fixcheck 25/25·33/33, layered compileKotlin 성공. 커밋 `6f73b8c` — 뒤 적중: 주석 제한 god 6·layered 11, 입찰 god 5·layered 7 | task 02 |
| 2026-10-30 21:55 | task 02 실행 전 고정: 기준 SHA `6f73b8c`, 작업 트리 깨끗(docs 작업 폴더만 미추적), god 5,678줄(`Marketplace.kt` 3,667)·layered 9,970줄, held 0, `results-pilot3` 없음, free 가용 19Gi(사용 16Gi), 컨테이너 ccle-postgres-1만. 명령 `runner.py run --model sonnet --cases T1,C2 --reps 1 --parallel 2 --results-dir experiment/results-pilot3`(budget 기본 5) | 실행 중 |
| 2026-10-30 23:39 | 실행 경과: 21:55 시작 → 러너가 로그인 파일 만료 임박(49분 < 실행 상한 2,700초 + 600초 = 55분, `runner.py:184`)으로 원본 갱신 대기 → 22:5x 갱신 뒤 실행(T1 layered 22:59 종료, T1 god·C2 god 23:18~19, C2 layered 23:38). rc 0. 가정 1(단계 A 코드에서 러너·에이전트 경로 정상) 실증 | task 03 |
| 2026-10-30 23:39 | **파일럿 A 결과(results-pilot3, Sonnet, CLI 2.1.308, 기준 `6f73b8c`):** 4/4 valid·end normal·censored 0·`bad_event_lines` 0·토큰 결측 0(modelUsage)·success 4/4(회귀 contract 122·contract2 89/88·contract3 252, 위반 0, 변조 0). 턴(message.id) T1 god 6·layered 6, C2 god 7·layered 6 — raw 손 대조 4/4 일치. 토큰 T1 116,403/118,217(god/layered), C2 168,865/173,101. 명목 비용 합 $0.484. 도구 Bash 5~6회(C2 god는 `bash` 오기 1). 편집: first_edit_turn 4~5·via Bash·first_edit_hit True 4/4·missed 0·unknown 0, 첫 겨냥 턴 4/4 = 2. 도구 출력 절단(위협 7) 0 — 4실행 모두 Read 없이 grep → `sed -n` 범위 읽기, god C2는 `wc -l Marketplace.kt` 뒤 `grep … \| head -150`(에이전트 스스로 자름). 흐름은 2회차와 같다: TASK·test.sh → 키워드 grep 1회 → 지점 범위 읽기 → python heredoc 편집 → `sleep 100` 뒤 test.sh 출력 확인 | 판정 |
| 2026-10-30 23:39 | **게이트(§6.2) 미달**: 4실행 valid ∧ 지표 전부 파싱 ∧ 성공 ≥1은 충족, **구조별 턴 중앙값 god 6.5·layered 6.0 < 10**. 재실행 대상 없음(무효·불완전 0). **§6.3 적용 행 = 넷째 행(미달, 두 구조 모두 < 7.5) → 2차 결론**: "god 코드를 2,692줄에서 5,678줄로 2.11배 키워도 Sonnet의 턴이 사전 등록한 증가 기준(구조별 중앙값 7.5)에 못 미쳐, 이 방식의 규모 확장으로는 구조 효과를 볼 만큼 과제를 어렵게 만들기 어렵다". 2회차(god 5.5·layered 5.5) 대비 +1.0·+0.5, 토큰 god +14~71%·layered +4~25%. 사용자 결정(spec): 판정·보고만, 다음 결정은 사용자 | task 04 |
| 2026-10-30 23:42 | 완료 요약 회수(Opus). 기록 정정 2건(워커 지적, 메인이 raw grep으로 확인): ① 결과 행의 '흐름은 2회차와 같다 … `sleep 100` 뒤 test.sh 출력 확인'은 틀림 — 'moved to the background' 결과가 파일럿 A 4/4, 2회차 0/4, 1회차 3/4. 편집+test.sh 호출이 CLI 120초 제한을 넘어 대기 턴이 1회씩 생겼다(duration 247~288초, 2회차 133~153초) → 턴 증가 +1.0·+0.5의 주원인, 빼면 중앙값 더 낮음 — 판정 행 불변 ② 'Read 없이 grep → sed -n'은 C2 layered가 작은 파일 4개를 cat 통째 읽기(full_read_turns 4) — Read 0·절단 0은 맞음. ADR-0006 해석 주의 덧붙임 | 커밋·보고 |
| 2026-10-30 23:42 | 사이클 마감: NEXT 갱신 · CS 이슈 아카이브는 study-note 쓰기 금지(spec ④)로 보류 | 병합 확인 |

## 리뷰 ledger

| ID | 출처 | 지적 | 판정 | 처리 |
|---|---|---|---|---|

## 생략한 검증

(없음)

## 완료 요약

**결론:** 파일럿 A(Sonnet, T1·C2 × god·layered × 1, 기준 `6f73b8c`)는 4/4 valid·success였지만 턴 중앙값 god 6.5·layered 6.0으로 §6.2 게이트(≥ 10) 미달 → §6.3 넷째 행(두 구조 모두 < 7.5) = **2차 결론**. 다음 결정은 사용자.

| 구분 | 내용 |
|---|---|
| 커밋 | `6f73b8c` chore(layered): 판매자 프로필·가격 알림 등록 주석을 god 짝과 같은 문장으로(2파일, 줄 수 1/1·1/1) |
| 실행 결과 | `experiment/results-pilot3/`(gitignore — 로컬) runs 4·raw 4·diffs 4 |
| 문서 | ADR-0006 §6.1-9 결과·판정·해석 주의, NEXT, 측정 로그, 이 작업 폴더 |

핵심 diff(`git show 6f73b8c`에서 복사):
```diff
-// A10 판매자 프로필(ADR-0007 §12.1). 휴가는 표시만 한다 — 입찰·체결·발송 기한에 영향이 없다.
+// A10 판매자 프로필(ADR-0007 §12.1). 휴가는 표시만 한다.
```
```diff
-        /** 등록(§8.2): 사용자 행을 잠근 뒤 센 같은 사이즈 ACTIVE 수가 0이어야 한다. expires_at = 현재 + 60일. 구매 제한 중에도 등록한다. */
+        /** 등록(§8.2): 사용자 행을 잠근 뒤 센 같은 사이즈 ACTIVE 수가 0이어야 한다. expires_at = 현재 + 60일. */
```

| 과제 | 구조 | 턴(2회차) | 토큰(2회차) | 비용 | 도구 호출 | first_edit_turn | 성공 |
|---|---|---|---|---|---|---|---|
| T1 | god | 6 (6) | 116,403 (102,288) | $0.067 | 5 | 4 | ✓ |
| T1 | layered | 6 (5) | 118,217 (94,534) | $0.116 | 5 | 4 | ✓ |
| C2 | god | 7 (5) | 168,865 (98,924) | $0.138 | 6(`bash` 오기 1) | 5 | ✓ |
| C2 | layered | 6 (6) | 173,101 (165,724) | $0.163 | 5 | 4 | ✓ |

- 합 $0.484 · valid 4/4 · censored 0 · `bad_event_lines` 0 · 회귀 contract 122·contract2 89/88·contract3 252 · 첫 겨냥 턴 2 · first_edit_hit True · missed 0.
- 판정: god (6+7)/2 = 6.5, layered (6+6)/2 = 6.0 < 10, 두 구조 모두 < 7.5 → 넷째 행. N1/N0 = 5,678/2,692 = 2.11배. 무효·불완전 0이라 재실행 없음.
- 관찰(사후): 흐름 = TASK·test.sh → 키워드 grep → `sed -n` 범위 읽기(C2 layered는 작은 파일 cat) → python heredoc 편집 + test.sh → 120초 초과 백그라운드 → `sleep 100` 결과 확인. 대기 턴이 4/4(2회차 0/4)라 턴 증가는 탐색이 아니라 대기 턴에서 왔다. 도구 출력 절단 0(Read 0, 가장 긴 출력 17,049자).
- 사건: 러너가 로그인 파일 만료 임박(49분 < 실행 상한 45분 + 10분 = 55분, `runner.py:184`)으로 약 1시간 원본 갱신을 기다림(의도된 보호 동작).
- 남긴 것(NEXT): 사용자 결정 대기 — ① 2차 결론 문서화 ② 과제 난이도 수단 재검토 ③ 다른 모델 탐색.
