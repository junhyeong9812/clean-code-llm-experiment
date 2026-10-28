# log — contract3 행동 테스트(ADR-0007 A1~A12)

> 작업 폴더: `docs/plans/2026-10-29/contract3-tests/` · 브랜치 `test/contract3`(main `a025ed9`) · 산출물 유형: 실행물(테스트 코드)

## 타임라인

| 시각 | 사건 | 결과 |
|---|---|---|
| 2026-10-29 01:06 | ADR-0007 채택·push(`a025ed9`, 사용자 "이대로 채택하고 push한 뒤 계속 진행해줘") · 브랜치 `test/contract3` 생성 · 작업 폴더 생성 | 인터뷰 시작 |
| 2026-10-29 01:36 | 인터뷰 4문항(밀도 기존과 같음·Opus 워커 3 병렬·테스트 + gradle만·auto) → requirement-spec 작성 → 사용자 합의 → set-state spec-approved·mode auto | task 01 착수 |
| 2026-10-29 01:36 | 설계 선검증 생략 근거: spec ②의 동작 불변식은 모두 ADR-0007(직전 작업에서 선검증·듀얼 리뷰 완료)의 것 — 이 작업이 새로 만드는 동작 불변식 없음(기존 테스트 집합 불변은 범위 제약) | - |
| 2026-10-29 01:40 | task 01: `support/Contract3.kt`(@Contract3Test — 기존 FakePgExtension.kt 대신 새 파일) · `support/Db3.kt`(새 테이블 범용 조회·INSERT, 열 이름은 테스트가 문자열로) · gradle `contract3Test` 등록(+ behaviorTaskNames·env 검사·contractTest에서 contract3 제외) | 스모크 |
| 2026-10-29 01:40 | 스모크(가정 1): 임시 ZzSmokeTest 2개를 `run.sh god contract3Test`로 실행 → 2/2 실패, 사유 = 404 `NO_ROUTE`(리뷰 엔드포인트 없음)·PSQLException `relation "reviews" does not exist` — 예상한 red 사유. 실행 뒤 verify 76 통과. 임시 테스트 삭제. 참고: 셸에서 직접 `./gradlew` 시 JAVA_HOME 미설정으로 루트 플러그인 해석 실패 → `JAVA_HOME=~/.sdkman/candidates/java/21.0.5-tem` 필요(run.sh는 설정함) | task 02 위임 |
| 2026-10-29 01:41 | task 02 위임: Opus 워커 3 병렬(c3-02a 정산 계열 A1·A2·A3·A7 / c3-02b 알림·스캔 A4·A5·A11 / c3-02c 기타 A6·A8·A9·A10·A12), 공통 브리핑 scratchpad `c7/brief-common.md`(blind — god/layered 열람 금지, 자기 행 단언, 시계 상대 계산, T1·C2 회귀 회피, 모호하면 테스트 대신 보고) | 대기 |
| 2026-10-29 01:57 | task 02 회수 3/3: A(c3-02a) 54개·1,819줄 · B(c3-02b) 49개·1,341줄 · C(c3-02c) 48개·1,570줄 = **151개**, 23파일. 각자 자기 파일 컴파일 확인(B·C는 사본에서 자기 파일만). 메인 전체 컴파일 exit 0. 워커들이 백틱 이름의 `[` `<` `>` 컴파일 오류를 각자 수정(C는 전각 문자로) | task 03 실행 |
| 2026-10-29 01:57 | 워커 '모호' 보고 17건(A 6·B 4·C 7) — 테스트로 만들지 않음. 해석이 들어간 단언 4건(C). 아래 '모호 목록'에 기록, 리뷰에서 ADR 보완 여부 판정 | - |
| 2026-10-29 02:11 | task 03 실행(run.sh 직렬, 결과 scratchpad `c7/runs/`): **contract3Test god 151/151 실패·layered 151/151 실패** — 사유 분류 NO_ROUTE 90 · 테이블 없음 28 · 알 수 없는 배치 400 `INVALID_REQUEST` 33(새 배치 이름 없음 — 예상 사유) · 그 밖 0. **무회귀**: 두 앱 `test` = infra 28 + contract 122 통과, `contract2Test` 89 통과(122 + 89 = 211 그대로). 6회 모두 verify 76 통과 | task 04 리뷰 |
| 2026-10-29 02:12 | 리뷰 루프 1 ⓪: BASE `a025ed9`, `$OUT` = scratchpad `c7/rv2`, 미러 `c7/tmirror`(log.md 제외, untracked 26 = 테스트 23·support 2·spec). 보안 스캔 매칭 4 = 직전과 같은 오탐 → 통과. ① Opus(c3-rv1-opus) ∥ codex 시작 | 대기 |
| 2026-10-29 02:27 | ① 회수: codex(exit 0, bwrap 0 — major 1·minor 3, 본문 전부는 일부 파일만) · Opus(blocker 0·major 0·minor 8, 151개 전부 대조, 수치 재계산 일치) → ② 종합(ledger L1-1~L1-12) | 수정 |
| 2026-10-29 02:27 | ADR 보완(문서): 새 테이블 수 계수 오류 20 → **19**(Opus 참고 — ADR-0007 §3·§17·ADR-0006·README·NEXT·measurement-log 정정, 메인 재계수 19 확인) + ADR-0007 개정 1 §19(모호 M1~M16 해소 14항목 [제안] — M9는 계약 밖, M17은 L1-1로 해소) | 워커에 수정 위임 |
| 2026-10-29 02:27 | spec 차이 기록: `@Contract3Test`를 spec §1의 `FakePgExtension.kt` 추가 대신 새 파일 `support/Contract3.kt`에 둠(기존 파일 무변경 쪽 — Opus 지적) · 새 공용 도우미 `support/SlowTrigger.kt`(경합 보장 — L1-4) · Db3 `orderBy` 형식 검사(L1-9) 메인 작성 | - |
| 2026-10-29 02:34 | ④ 루프 1 수정 회수: A 56(+2, month 정렬은 과거 달 픽스처로) · B 51(+2, delays 두 종류 동시는 계약상 불가 — 생략 사유 타당) · C 51(+3) = **158개**. 전체 컴파일 exit 0. 재실행 `c7/runs2/`: 두 앱 158/158 실패 — NO_ROUTE 92·테이블 없음 33·알 수 없는 배치 33·그 밖 0, verify 76 통과 | 루프 2 |
| 2026-10-29 02:34 | 루프 2 ⓪: 누적 packet `c7/rv3`, 미러 `c7/tmirror2`(+ `_packet/loop1-fix-diff.patch` 참고용), 보안 스캔 같은 오탐 4 → 통과. ① Opus(c3-rv2-opus) ∥ codex | 대기 |
| 2026-10-29 02:41 | 루프 2 codex 1차: exit 0이지만 bwrap `Failed RTM_NEWADDR` 4회로 테스트 본문 0개 열람 → '판정 미확인' 출력(실패로 판정) → 1회 재시도 | 재시도 |
| 2026-10-29 02:46 | 루프 2 codex 재시도 성공(exit 0, bwrap 1회 — 본문 일부 열람, 범위 한계 명시): major 1 — A12 체크인 테스트 `live(slot)` 기대 2 → 실제 계약상 3(CHECKED_IN 2 + RESERVED 1). 메인 코드 대조로 확인 → L2-1 채택, 메인이 수정(3 + 상태별 단언) | Opus 대기 |
| 2026-10-29 02:50 | 루프 2 Opus 회수(158개·§19 전수 대조, blocker 0·major 2·minor 4 — L2-1은 놓침) → ② 종합: 6건 전부 채택(L2-2~L2-7), ADR §19의 3(본문 enum 포함)·9(형식 검사 먼저) 보완, 수정 위임 | 루프 3 예정 |
| 2026-10-29 02:55 | 루프 2 수정 회수: A 56(승인 비문자열 reason 400, 결정성 점검 고칠 곳 0) · B 53(+2: SELL 만료 경계·DISPUTE_WINDOW 상태 제외, '없음' 단언 10여 곳 종류로 좁힘) · C 52(+1: 입고 예약 SALE_RESTRICTED 미발생, 주석) = **161개**. 재실행 `c7/runs3/`: 두 앱 161/161 실패 — NO_ROUTE 93·테이블 없음 34·알 수 없는 배치 34·그 밖 0, verify 76 통과 | 루프 3 |
| 2026-10-29 02:55 | 루프 3(마지막) ⓪: packet `c7/rv4`, 미러 `c7/tmirror3`(+ loop2-fix-diff), 같은 오탐 → 통과. ① Opus(c3-rv3-opus — 브리핑에 '상태 수 산수를 단언마다 다시 세라' 추가: 루프 2에서 Opus가 놓친 유형) ∥ codex(bwrap 실패 시 1회 자동 재시도) | 대기 |
| 2026-10-29 03:06 | 루프 3 회수: codex(재시도 없이 성공, bwrap 1 — 새 결함 0, 렌즈 6 전부 verified) · Opus(161개·산수 전수 재계산 — **blocker 1**: A10 intro 경계 PUT이 기본 shopName으로 상점명을 덮어써 :92·:99 단언이 정답 구현에서도 실패 · minor 1: 같은 항목 동시 예약 경합 테스트 없음) → 둘 다 채택(L3-1·L3-2). L3-1은 메인 코드 대조로 확인 후 메인 수정(intro 호출에 shopName 명시), L3-2는 C 워커 위임 | 루프 상한 도달 |
| 2026-10-29 03:06 | **종료 조건 미충족**: 루프 3에서 신규 채택 2건 → 최대 3루프 규칙상 `review unresolved` 처리 — 수정 뒤 타깃 재점검(codex) 1회로 수정분만 확인하고, 병합은 사용자 결정에 맡긴다(playbook review.md §1 종료 조건) | - |
| 2026-10-29 03:21 | L3-2 회수(C: A12 같은 항목 동시 예약 테스트 +1 → **162개**). 타깃 재점검(codex — 1차 bwrap으로 읽기 실패, 재시도 성공): L3-1 해소(PUT 순서대로 행 값 추적 일치), L3-2 기대값 ADR 일치·잠금 순서 맞음, 단 **SlowTrigger 0.5초가 두 번째 요청의 잠금 대기 도달을 관측하지 않아 순차 처리여도 통과**(major — 같은 한계가 SlowTrigger 쓰는 동시성 테스트 전부에 해당) → 루프 상한이라 수정하지 않고 잔여 위험 R-1로 기록(L3-3 open) | 사용자 결정 대기 |
| 2026-10-29 03:21 | 최종 실행 `c7/runs4/`: contract3 두 앱 162/162 실패 — NO_ROUTE 93·테이블 없음 35·알 수 없는 배치 34·그 밖 0 · 무회귀 두 앱 infra 28 + contract 122 + contract2 89 = 239 통과 · verify 76 통과 ×6 | 커밋(브랜치만) |
| 2026-10-29 03:25 | 사이클 마감(병합 전 docs): NEXT·README·measurement-log 갱신 · 완료 요약(Opus 워커 c3-sum, 실파일 복사) · CS 이슈 아카이브 = study-note 쓰기 금지라 보류 · **병합·push 하지 않음**(review unresolved — 사용자 결정) | docs 커밋 |

## 모호 목록 (워커 보고 — ADR-0007이 정하지 않아 테스트하지 않음)

| ID | 출처 | 절 | 내용 |
|---|---|---|---|
| M1 | A | §10.3 | 정정 응답 `cancelled`가 원본(CANCELLED)인지 새 CANCEL 행인지 |
| M2 | A | §10.3 | "시각은 모두 현재"가 원본 issued_at도 바꾸는지 |
| M3 | A | §6.2 | 반려 요청에 reason 키가 없을 때 = null인지 |
| M4 | A | §6.1 | accountNo를 JSON 숫자로 보낼 때·앞뒤 공백 |
| M5 | A | §4.1 | rating 5.0(실수 표기) |
| M6 | A | §4.4 | 헤더 불필요 GET에 잘못된 X-User-Id |
| M7 | B | §7.2 | unread=TRUE·빈 값 |
| M8 | B | §12.2 | 오프셋 없는 occurredAt |
| M9 | B | §8.1 | DELETE 경로 변수 정수 아님 → 400/404 |
| M10 | B | §8.1 | activeAlert = 그 사용자·그 사이즈의 ACTIVE 알림(해석으로 단언) |
| M11 | C | §9.1 | ANSWERED → 추가 메시지로 OPEN일 때 answered_at 유지/삭제 |
| M12 | C | §11.3 | summary sellerGrade가 여러 행일 때 어느 것 |
| M13 | C | §11.3 | settlements sellerId가 없는 사용자 → 빈 목록/404 |
| M14 | C | §12.1 | intro null·누락, vacation 키 누락 vs null |
| M15 | C | §12.3 | GET /inbound-slots date 누락 |
| M16 | C | §2.1 | 앞뒤 공백 범위(U+3000·NBSP) |
| M17 | C | §2.5 | 기본 limit 테스트가 시드 감사 로그 ≥ 100행에 기댐 |

## 리뷰 ledger

| ID | 출처 | 지적 | 판정 | 처리 |
|---|---|---|---|---|
| L1-1 | codex major·opus | A9 기본 limit 테스트가 시드 감사 로그 ≥100행 전제(A9AdminQueryTest:81) — 자기 행 원칙 위반(M17) | 채택 | C: 자기 감사 행(슬롯 생성 51·101건)으로 → fixed |
| L1-2 | codex·opus | A3 조회 테스트가 요청 없는 시드 계좌 사용자 전제(A3PayoutAccountTest:206) | 채택 | A: 새 사용자 + seller_payout_accounts 픽스처 → fixed |
| L1-3 | opus | A9 settlements가 정산 3건 이상 시드 판매자 전제(A9:218) | 채택 | C: 자기 정산(주간 정산 API) → fixed |
| L1-4 | codex | 동시성 래치가 경합 자체를 보장하지 않음(Bundle*Support concurrently) | 채택 | SlowTrigger로 잠금 쥔 채 멈춤 — 세 묶음 동시성 테스트 → fixed |
| L1-5 | codex·opus | §2.7 감사 같은 트랜잭션 실패 경로·새 엔드포인트 UNEXPECTED_ERROR 없음 | 채택 | A: 리뷰 숨김 + admin_audit_logs INSERT 실패 → 400 UNEXPECTED_ERROR·리뷰 무변경 → fixed |
| L1-6 | opus | §11.3 summary 전체 기간 수가 최근 N일 구현과 구별 안 됨(A9:145) | 채택 | C: 31일+ 이동 후 재계수 → fixed |
| L1-7 | opus | 기존 /admin 감사 미기록·새 엔드포인트 SALE_RESTRICTED 미발생 테스트 없음 | 채택 | C: 기존 admin 호출 뒤 감사 무증가 · A: 판매 제한 사용자 계좌 요청 201 → fixed |
| L1-8 | opus | 작은 경계 누락(inbound-slots date 누락·가격 알림 TRIGGERED/EXPIRED 취소 409·같은 가격 다른 created_at 순서·disputes ACCEPTED 실행·여러 달 정렬·delays 2종 순서) | 채택 | B·C·A 분담 → fixed |
| L1-9 | opus | Db3 orderBy 검사 없음 | 채택 | 메인 수정 → fixed |
| L1-10 | opus | spec §1 애노테이션 위치 차이 | 채택 | log 기록(코드 무변경) → fixed |
| L1-11 | opus(참고) | ADR-0007 새 테이블 20 ≠ 표 19 | 채택 | 문서 정정 → fixed |
| L1-12 | main-synthesis | 모호 M1~M16 — ADR 보완으로 기대값 확정 후 테스트 추가 | 채택 | ADR §19 + 세 묶음 테스트 → fixed |
| L2-1 | codex | A12InboundTest 체크인 테스트 live(slot) 기대값 2 — CHECKED_IN 2 + RESERVED 1 = 3이어야 함(§12.3) | 채택 | fixed(메인, 상태별 단언 추가) |
| L2-2 | opus major | A4DeadlineReminderTest:199 구매자 알림 없음 단언 — 시계 위치에 따라 DISPUTE_WINDOW_CLOSING이 정상 생겨 정답 구현도 실패 | 채택 | B: SETTLEMENT_PAID 종류로 좁힘 → fixed |
| L2-3 | opus major | SELL 입찰 expires_at > 현재 경계(§8.1 bestSell·§8.3의 3) 테스트 없음 | 채택 | B: validDays 30 + 30일 이동 → fixed |
| L2-4 | opus minor | A11 carrier "cj" → 400 단언이 문서 근거 없음(§19의 3은 쿼리만) | 채택 | ADR §19의 3을 본문 enum까지 확장(테스트 유지) → fixed |
| L2-5 | opus minor | §6.2 승인 요청의 비문자열 reason 기대값 두 갈래 | 채택 | ADR §19의 9 보완 + A 테스트 1줄 → fixed |
| L2-6 | opus minor | A12:105 슬롯 동시 생성 주석이 잠금 경합으로 오기(ON CONFLICT·BEFORE 트리거 동시 수면) | 채택 | C: 주석 수정 → fixed |
| L2-7 | opus minor | 누락: DISPUTE_WINDOW_CLOSING의 DISPUTED·REFUNDED 제외, 입고 예약의 SALE_RESTRICTED 미발생 | 채택 | B·C 테스트 추가 → fixed |
| L3-1 | opus blocker | A10SellerProfileTest:87~99 intro 경계 PUT이 SC.putProfile 기본 shopName("bt 상점")으로 상점명을 덮어씀 → :92 "x"·:99 NBSP 값 단언이 §12.1대로 구현해도 실패 | 채택 | fixed(메인 — intro 호출에 shopName 명시) |
| L3-2 | opus minor | §12.3 같은 항목 동시 예약(주문 행 직렬) 경합 테스트 없음 | 채택 | fixed(C 워커, 재점검 기대값 일치) |
| L3-3 | 재점검(codex) | SlowTrigger 지연이 두 번째 요청의 잠금 대기 도달을 관측하지 않음 — 순차 처리여도 동시성 테스트 통과(A12 새 테스트 + SlowTrigger 쓰는 테스트 전부) | 채택 | **open — 루프 상한, 사용자 결정** (제안: pg_stat_activity의 wait_event = Lock 관측 뒤 풀어 주는 방식) |

## 생략한 검증

- **리뷰 종료 조건 미충족(`review unresolved`)**: 최대 3루프의 3루프째에 신규 채택 2(L3-1·L3-2) → 수정 + 타깃 재점검 1회. 재점검에서 L3-3(open) — 사용자 결정 필요.
- **green 미검증**: 구현·DDL이 없어 162개 기대값은 실행으로 확인되지 않았다(리뷰 3루프 정적 대조뿐). 다음 작업(DDL·god 구현)에서 처음 실행 검증된다 — 그때 테스트 결함이 나오면 테스트를 고치되, ADR과 대조해 테스트 쪽 결함인지 먼저 판정한다.

## 완료 요약

### 무엇을 했나
- 인터뷰 4문항은 모두 권장안으로 정했다(밀도는 기존과 같게, Opus 워커 3개 병렬, 범위는 테스트와 gradle만, 모드 auto). 메인이 공용 도우미(`support/Contract3.kt`·`Db3.kt`)와 gradle `contract3Test`를 먼저 만들고 스모크로 예상한 red 사유(NO_ROUTE·relation 없음)가 나오는 것을 확인했다.
- 워커 3개가 blind로 151개를 썼다. 리뷰 3루프를 거쳐 테스트는 **162개·23파일**(contract3 20개 + support 3개, 5,228줄 추가)이 됐다. 코드 커밋은 `f431877`(브랜치 `test/contract3`, 기준 `a025ed9`)이다.
- 리뷰에서 경합이 실제로 일어나게 하는 도우미 `support/SlowTrigger.kt`(L1-4)를 새로 만들고, `Db3.orderBy`에 형식 검사(L1-9)를 넣었다.
- 리뷰는 3루프(Opus ∥ codex)를 돌았다. 채택 12 + 7 + 2 = 21건이고 open 1건(L3-3)이 남았다. 마지막 루프에서도 새로 채택한 건이 나와 종료 조건을 채우지 못했다(`review unresolved`).
- ADR-0007 개정 1(미커밋 docs)에서는 §19에 모호 M1~M16을 해소하는 14항목을 **[제안]**으로 넣었다. 새 테이블 수 20은 계수 오류여서 19로 바로잡았다(ADR-0007 §3·§17, ADR-0006, README, NEXT, measurement-log).

### 핵심 diff

(a) `behavior-tests/build.gradle.kts`에 `contract3Test`를 등록한 부분(`git show f431877`). `contractTest`의 filter에도 `excludeTestsMatching("com.ccle.behavior.contract3.*")`를 추가했다.
```kotlin
+// 단계 A 계약 테스트(ADR-0007, 패키지 com.ccle.behavior.contract3, 태그 contract3). test·contract2Test에는 넣지 않는다.
+// 실행: tools/behavior/run.sh <god|layered> contract3Test
+val contract3Test by tasks.registering(Test::class) {
+    description = "ADR-0007 단계 A 계약 테스트 (패키지 contract3, 태그 contract3). BASE_URL의 앱을 대상으로 한다"
+    group = "verification"
+    testClassesDirs = testSourceSet.output.classesDirs
+    classpath = testSourceSet.runtimeClasspath
+    useJUnitPlatform { includeTags("contract3") }
+    filter { includeTestsMatching("com.ccle.behavior.contract3.*") }
+    behaviorDefaults()
+    requireOptIn()
+    mustRunAfter(infraTest, contractTest, contract2Test)
+}
```
(b) `support/SlowTrigger.kt`의 핵심 부분. 임시 BEFORE 트리거가 잠금을 쥔 채 `pg_sleep`하고, 끝나면 트리거를 지운다.
```kotlin
        Db.update(
            "CREATE FUNCTION $name() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN " +
                "PERFORM pg_sleep($seconds); RETURN NEW; END $$",
        )
        val result = try {
            Db.update("CREATE TRIGGER $name BEFORE $event ON $table FOR EACH ROW WHEN ($condition) EXECUTE FUNCTION $name()")
            block()
        } finally {
            Db.update("DROP TRIGGER IF EXISTS $name ON $table")
            Db.update("DROP FUNCTION IF EXISTS $name()")
        }
```
(c) L2-1(codex major): A12InboundTest 체크인 테스트의 살아 있는 예약 수. 정답대로 구현해도 실패하는 기대값이었다.
```kotlin
// before (루프 2 미러)
        assertEquals(2L, live(slot))
// after
        // 살아 있는 예약 = CHECKED_IN 2(ids[0]·ids[1]) + RESERVED 1(ids[2] — 체크인 창이 지나 남음), CANCELLED 1(ids[3])은 빠진다
        assertEquals(listOf("CHECKED_IN", "CHECKED_IN", "RESERVED", "CANCELLED"), ids.map { SC.reservationRow(it).text("status") })
        assertEquals(3L, live(slot))
```
(d) L3-1(Opus blocker): A10SellerProfileTest의 intro 경계 테스트. PUT이 기본 shopName으로 상점명을 덮어써서 뒤의 :92·:99 단언이 실패했다.
```kotlin
// before (루프 3 미러)
        SC.putProfile(user, intro = SC.cps(500)).assertStatus(200)
        SC.putProfile(user, intro = SC.cps(501)).assertError(400, "INVALID_REQUEST")
// after
        // intro 경계 — PUT은 본문 전체로 UPDATE하므로(§12.1) shopName을 마지막 성공 값 "x"로 함께 보낸다
        SC.putProfile(user, shopName = "x", intro = SC.cps(500)).assertStatus(200)
        SC.putProfile(user, shopName = "x", intro = SC.cps(501)).assertError(400, "INVALID_REQUEST")
```
(e) ADR-0007 §19의 첫 3항목(미커밋)
```markdown
1. 본문의 키가 **없는 것은 null과 같다**. 필수 값이면 400, null을 허용하는 값(예: `vacationFrom`)이면 null로 본다.
2. 문자열 자리는 JSON 문자열만, 정수 자리는 정수 JSON 숫자만 받는다(`5.0`·`"5"` → 400 — 기존 두 구현의 본문 파싱 관례와 같다). `accountNo`는 JSON 문자열이고 앞뒤 공백을 포함해 숫자 10~14자리여야 한다(공백이 있으면 400).
3. 쿼리와 본문의 enum·boolean 값은 문서에 적은 글자 그대로만 받는다(대소문자 구분 — 본문 `"cj"` → 400, 쿼리 빈 값 `?unread=` → 400).
```

### 실행 결과 (최종 `c7/runs4/`, 03:21)

| 대상 | god | layered | 비고 |
|---|---|---|---|
| contract3Test | 162/162 실패 | 162/162 실패 | 실패 사유: NO_ROUTE 93 · 테이블 없음 35 · 알 수 없는 배치 400 34 · 그 밖 0 |
| 기존(infra 28 + contract 122 + contract2 89) | 239 통과 | 239 통과 | 무회귀(기존 테스트 수 그대로) |
| verify | 76 통과 | 76 통과 | 실행마다 확인(×6) |

### 리뷰 요약

| 루프 | codex | Opus | 메인 종합 | 채택 | 비고 |
|---|---|---|---|---|---|
| 1 | major 1 · minor 3 (L1-1·2·4·5 — 1·2·5는 Opus와 겹침) | minor 8 + 참고 1 (L1-3·6·7·8·9·10·11) | L1-12(모호 → §19) | 12 | 151 → 158개 |
| 2 | major 1 (L2-1) — 1차는 bwrap `RTM_NEWADDR`로 본문을 0개 읽어 실패, 재시도 성공 | major 2 · minor 4 (L2-2~7, L2-1은 놓침) | — | 7 | → 161개 |
| 3 | 새 결함 0 | blocker 1 (L3-1) · minor 1 (L3-2) | — | 2 | → 162개, 신규 채택이 있어 **종료 조건 미충족** |
| 타깃 재점검 | major 1 (L3-3) — 1차 bwrap 실패, 재시도 성공 | — | — | open | 루프 상한이어서 고치지 않음 |

- 정답 구현에서도 실패하는 테스트 결함이 루프마다 1~2개씩 나왔다(예약 수 산수 2→3, '없음' 단언이 다른 알림 종류에 걸림, PUT 기본값이 상점명을 덮어씀). 그래서 루프 3 Opus 브리핑에 '상태 수 산수를 단언마다 다시 세라'를 추가했다.
- 워커 모호 보고는 17건(M1~M17)이었다. M17은 L1-1로 해소됐고, M9는 계약 밖으로 판정했다(§19의 8). 나머지는 §19의 14항목으로 기대값을 정하고 테스트를 추가했다.

### 남은 것
- **L3-3(사용자 결정)**: SlowTrigger 0.5초 지연이 두 번째 요청이 실제로 잠금 대기에 들어갔는지 관측하지 않는다. 그래서 요청이 순차로 처리돼도 SlowTrigger를 쓰는 동시성 테스트가 전부 통과한다. 제안: `pg_stat_activity`의 `wait_event = Lock`을 관측한 뒤에 풀어 주는 방식. 병합 여부도 사용자가 결정한다(`review unresolved`).
- **ADR-0007 §19 [제안] 14항목**: 사용자 확인이 필요하다(§16과 같은 지위).
- **green 미검증**: DDL·구현이 없어서 162개 기대값은 정적 대조로만 확인됐다. 구현 때 실패가 나오면 ADR과 대조해 테스트 결함인지부터 판정한다.
- **다음 작업**: 스키마·verify·seedgen 후처리(1k-s42-v2). 새 작업 폴더 spec부터 시작한다. DDL을 넣은 뒤 '테이블 없음' 35개가 다음 사유(404·400)로 바뀌는지 먼저 확인한다(Db3 열 이름을 ADR §3 표와 대조).
