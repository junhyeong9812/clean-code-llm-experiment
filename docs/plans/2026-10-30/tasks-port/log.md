# log — T1·C2 이식·재채택 (ADR-0006 §5·§6.1-8)

> 작업 폴더: `docs/plans/2026-10-30/tasks-port/` · 브랜치 `impl/tasks-port-stage-a`(main `042edf2`) · 산출물 유형: 실행물(oracle 패치·symbols) + 판정 실측

## 타임라인

| 시각 | 사건 | 결과 |
|---|---|---|
| 2026-10-30 11:33 | 단계 A 보충 병합·push(`042edf2`) 뒤 인터뷰 4문항(정답·alt 전 단계·none/naive 숨은 단계만 · 3 동시 · 이식 충돌은 멈추고 보고 · auto) → spec 합의 → set-state | task 01 |
| 2026-10-30 11:34 | 메인 dry-run 시도 실패(스크립트가 사본 구조 app/src·inject 순서를 무시 — 결과 무효). task 01·02 위임: tp-01(Opus — build_work 사본으로 dry-run, 줄 위치만 재생성 + 옛/새 패치 +/− 내용 대조, 의미 변경 필요 시 멈춤, symbols 재생성·대조, 주석 grep) | 대기 |
| 2026-10-30 11:40 | tp-01 회수: 패치 42개 중 31개 줄 위치만 재생성(god 21 전부 offset +62/+75, layered 10 — BatchService +26), 옛/새 +/−·문맥·hunk 수·적용 결과 트리 42/42 같음, 의미 변경 0·멈춤 0, 새 기준 dry-run 42개 offset 0·fuzz 0. symbols 4파일 재생성 — 59항목 블록 sha1 옛=새, 새 기능 read 미포함. 주석 grep: T1·C2 규칙 서술 0, 경계 2건(layered Review.kt:115 '확정 시각' — TASK 핵심어 적중이 god 0/layered 1로 비대칭, NotificationService.kt:73 '입찰·체결·항목에는 아무것도 하지 않는다' — god 짝에 없음) → 메인이 주석만 정리(동작 무변경). fixcheck 25/25·33/33. 커밋 `bfa047a`(oracle)·`33a4fc3`(주석). 남김: oracle design.md의 줄 번호 서술은 옛 기준(비공개 문서 — 러너 미사용) | 듀얼 1패스 |
| 2026-10-30 11:41 | 병렬: 재채택 실측 행렬 시작(SHA 33a4fc3, check 3레인 — 전 단계 22회(T1 fix 5×2·alt-1×2, C2 fix 5×2), 숨은 단계만 38회(none·naive), 결과 scratchpad tp/checks/checks.jsonl) ∥ 이식 듀얼 1패스(미러 tp/mirror — Opus ∥ codex high: @@ 외 변경·symbols 블록·독립 주석 grep·핵심어 대칭) | 대기 |
| 2026-10-30 11:45 | 이식 1패스 codex 회수(high, bwrap 0): 네 렌즈 지적 없음 — @@ 외 변경 0, symbols fix·read 범위 일치(edit 1-3666·Order.kt 1-534는 inject 한 줄 삭제 뒤 길이와 맞음), 규칙 서술 주석은 inject가 교체·C2는 원본에 없음, 새 본보기 없음 | Opus 대기 |
| 2026-10-30 11:50 | 이식 1패스 Opus 회수: blocker 0·major 0·minor 3 — 패치 42개(hunk 156)를 메모리에서 정확 위치 적용 확인, edit N·fix 범위 실제 줄 수·hunk와 일치, inject 뒤 사본 grep 규칙 서술 0. TP-1(layered 새 기능 주석의 제한·입찰 낱말 비대칭 4곳)은 판정 행렬이 작업 트리로 사본을 만드는 중이라 앱 소스를 지금 바꾸면 고정 SHA 판정이 깨짐 → 기각·기록(규칙 서술 아님, 구조 차이 비율 안) — 파일럿 전 낱말 대칭 점검 항목으로 NEXT. TP-2 design.md 좌표는 실측 뒤 갱신(지금 oracle을 바꾸면 code_dirty). TP-3 fixcheck는 메인 실행 25/25·33/33(symbols 정합은 블록 sha1 대조로) | 실측 대기 |
| 2026-10-30 13:51 | 판정 행렬이 13:47경 메모리 부족으로 하네스에 의해 중단됨(명령 결함 아님 — 재시작은 사용자 지시 시만). 완료 37/60: 전 단계 22회 전부 success(T1 fix 5×2·alt-1×2·C2 god fix 5 — contract3 252/252, 타이밍 실패 0; C2 contract2 88은 기존 regression-exclude 1건), 숨은 단계 none·naive 15회 전부 hidden fail(T1 god none·naive1~5, T1 layered none·naive1~4, C2 god none·naive1~8). 미완료 23: T1 god naive-6·7, T1 layered naive-5~7, C2 god naive-9·10, C2 layered fix 5·none·naive1~10 | 사용자 보고 |
| 2026-10-30 20:05 | 사용자 결정: 남은 23회를 2레인으로 재개(lane4 = C2 layered fix 5 + 숨은 5, lane5 = 숨은 13), 같은 SHA 33a4fc3·같은 결과 파일 | 실측 대기 |
| 2026-10-30 21:39 | 재채택 행렬 완료 60/60(SHA 33a4fc3 단일·code_dirty False 전부, 누락·중복 0). 전 단계 22회 전부 success — T1 god·layered fix 5/5·alt-1 1/1, C2 god·layered fix 5/5, 회귀 contract 122·contract2 89(C2는 기존 exclude 1로 88)·contract3 252 전부 통과·verify 위반 0. 숨은 단계만 38회 전부 hidden fail·success False — T1 none·naive-1~7 ×2, C2 none·naive-1~10 ×2. **ADR-0006 §5 재채택 조건 두 구조 모두 충족.** JR-23: 3·2 동시 부하에서 contract3 SlowTrigger 등 타이밍 실패 0/22. 소요 중앙값 전 단계 975초·숨은 단계 276초. 위협 재측정: 12 — god 두 지점 :938→:1582 = 644줄(옛 631), layered :326→:346 = 20줄 그대로. 13 — 정답 잠금 순서 그대로, 단계 A 보충의 사이즈 KEY SHARE → 사용자 FOR UPDATE 경로 2개(가격 알림 재등록·price-alert-rearm)가 판매 쪽 일괄 취소(사용자 → 사이즈)와 엇갈림 쌍에 더해짐(등록 §19의 10-2와 같은 모양, 두 구조 같음, 판정은 순차라 무영향) | task 05 |
| 2026-10-30 21:41 | task 05: design.md 재채택 절·좌표 기준 표시 커밋 `fbd3f51` · ADR-0006 §5 재채택 결과·§6.1-8 완료 표시, ADR-0005 §9 위협 12·13 재측정 덧붙임 · NEXT 갱신(다음 = 파일럿 A, JR-23 측정 완료, TP-1 낱말 대칭 점검·메모리 주의 추가). 리뷰: 이식 듀얼 1패스(앞 행) + 실측 해석 셀프체크(60행 SHA 단일·누락 0·숨은 실패 개수 두 구조·지난 채택과 일치) — spec §5대로 | 완료 요약 위임 |
| 2026-10-30 21:43 | 완료 요약 회수(Opus). 기록 정정 2건(워커 지적, 메인 재계산으로 확인): ① 13:51 행의 중단 전 개수 '전 단계 22회·숨은 15회'는 오기 — 실제 **전 단계 17·숨은 단계 20**(합 37은 맞음, 사용자 보고에도 22로 말함) ② 21:39 행·ADR-0006의 소요 중앙값 975·276초는 짝수 개에서 위쪽 값을 집은 계산 — statistics.median **973.3·263.35초**로 ADR 고침 | 커밋·병합 확인 |
| 2026-10-30 21:43 | 사이클 마감: NEXT 갱신 완료 · CS 이슈 아카이브는 study-note 쓰기 금지(spec ④)로 보류(NEXT 보류 항목과 같은 경로) | 병합 확인 |

## 리뷰 ledger

| ID | 출처 | 지적 | 판정 | 처리 |
|---|---|---|---|---|
| TP-1 | opus minor | layered 새 기능 주석에만 제한·입찰 낱말(SellerProfile.kt:9 '입찰·체결·발송 기한에 영향 없음', Wishlist.kt:134·152, JpaNotificationRepositories.kt:130) — 낱말 대칭(ADR-0006 §3의 5) | 기각 — 기록 | 규칙 서술 아님·주석 줄 수 비율(≈2배)이 구조 차이와 같음, 실측 중 소스 변경은 고정 SHA 판정을 깸 → 파일럿 전 낱말 대칭 점검(NEXT) |
| TP-2 | opus minor | oracle design.md 좌표가 옛 기준 | 채택 | 실측 뒤 처리(`fbd3f51`) — 두 design.md 머리에 '본문 좌표 = 첫 채택 `ae6deda` 기준, 현재 좌표 정본 = symbols' 표시 + 재채택 절 추가(좌표 전면 갱신 대신 — 러너 미사용 비공개 문서) |
| TP-3 | opus minor | fixcheck 정합 미검증(미러에 스크립트 없음) | 해소 | 메인 실행 25/25·33/33, symbols는 블록 sha1 대조 |

## 생략한 검증

(없음)

## 완료 요약

**결론:** T1·C2 패치 42개 중 31개와 symbols 4파일을 단계 A·보충 뒤 코드에 줄 위치만 다시 맞췄다(의미 변경 0). 고정 SHA `33a4fc3` 재채택 60행이 ADR-0006 §5 조건을 두 구조 모두 충족했다 — 정답 22회 전 단계 success(contract3 252 포함), none·naive 38회 전부 숨은 실패.

| 해시 | 내용 |
|---|---|
| `bfa047a` | chore(oracle): 패치·symbols 이식 — 35파일 +102/−102(@@ 줄·symbols 줄 번호만) |
| `33a4fc3` | chore(layered): 경계 주석 2곳 정리(Review.kt:115, NotificationService.kt:73), 동작 무변경 — 판정 고정 SHA |
| `fbd3f51` | docs(oracle): design.md 좌표 기준 표시 + 재채택 절 |
| (docs 커밋) | ADR-0005 §9 위협 12·13, ADR-0006 §5 재채택 결과·§6.1-8 완료, NEXT, 측정 로그, 이 log |

핵심 diff(`git diff 042edf2 HEAD`에서 복사):
```diff
-@@ -873,7 +873,8 @@
+@@ -935,7 +935,8 @@
-@@ -1504,7 +1505,7 @@
+@@ -1579,7 +1580,7 @@
```
```diff
-app/src/main/kotlin/com/ccle/god/Marketplace.kt:873-879 fix  # 지점 1: 구매자 확정 시각(confirmOrderItem)
-app/src/main/kotlin/com/ccle/god/Marketplace.kt:1504-1510 fix  # 지점 2: 자동 확정 시각(autoConfirmItems)
-app/src/main/kotlin/com/ccle/god/Marketplace.kt:1-1771 edit  # 정답 패치가 고치는 파일 전체(첫 편집 적중 전용)
+app/src/main/kotlin/com/ccle/god/Marketplace.kt:935-941 fix  # 지점 1: 구매자 확정 시각(confirmOrderItem)
+app/src/main/kotlin/com/ccle/god/Marketplace.kt:1579-1585 fix  # 지점 2: 자동 확정 시각(autoConfirmItems)
+app/src/main/kotlin/com/ccle/god/Marketplace.kt:1-3666 edit  # 정답 패치가 고치는 파일 전체(첫 편집 적중 전용)
```
```diff
- * 대상 1건 = 알림 1행 INSERT 트랜잭션 하나(잠금 없음). 기한·제한은 저장된 열을 비교만 한다 — 입찰·체결·항목에는 아무것도 하지 않는다.
+ * 대상 1건 = 알림 1행 INSERT 트랜잭션 하나(잠금 없음). 기한·제한은 저장된 열을 비교만 한다.
```

| 과제 | 구조 | 패치 | 결과 |
|---|---|---|---|
| T1 | god·layered | fix ×5 · alt-1 | success 전부(hidden 2/2, contract 122·contract2 89·contract3 252) |
| T1 | god·layered | none · naive-1~7 | 숨은 실패 2 · 1·1·2·2·2·2·1 (/2) |
| C2 | god·layered | fix ×5 | success 5/5(hidden 6/6, contract 122·contract2 88(기존 exclude 1)·contract3 252) |
| C2 | god·layered | none · naive-1~10 | 숨은 실패 4 · 2·3·3·1·4·5·2·1·2·2 (/6) |

- 60행 SHA 단일·dirty False·누락/중복 0·변조 0·build 60/60. 숨은 실패 개수가 두 구조·지난 채택(`ae6deda`)과 naive마다 같다. regression-exclude 추가 0.
- 위협 12: god 644줄(옛 631)·layered 20줄. 위협 13: 정답 잠금 순서 그대로, 엇갈림 쌍 +2(가격 알림 재등록·price-alert-rearm). JR-23: 부하 중 contract3 타이밍 실패 0/22. 소요 중앙값 전 단계 973초·숨은 단계 263초.
- 사건: 13:47 호스트 메모리 부족으로 3레인 강제 중단(37/60 — 전 단계 17·숨은 20, 전부 기대대로) → 사용자 결정으로 남은 23회 2레인 재개, 21:39 완료.
- 남긴 것(NEXT): 파일럿 A(사용자 승인 후 claude 실행) · 파일럿 전 held 비었는지·TP-1 낱말 대칭·메모리 확인 · design.md 본문 좌표는 옛 기준(정본 symbols).
