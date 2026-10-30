# log — 단계 A 보충 구현 (ADR-0007 §20 A13~A17)

> 작업 폴더: `docs/plans/2026-10-30/stage-a-supplement/` · 브랜치 `impl/stage-a-supplement`(main `1cbe468`) · 산출물 유형: 실행물

## 타임라인

| 시각 | 사건 | 결과 |
|---|---|---|
| 2026-10-30 06:34 | 계약 채택·push(`1cbe468`) 뒤 인터뷰 3문항(한 작업에서 차례로 · blind Opus 테스트 · auto) → spec 합의 → set-state(SPEC=1·auto) | task 01 위임 |
| 2026-10-30 06:35 | task 01 위임: blind Opus 2 병렬 — sp-01a(A13·A14, BundleDSupportA) ∥ sp-01b(A15·A16·A17, BundleDSupportB). god·layered 소스 열람 금지, ADR-0007 §20만, 새 파일만, 컴파일만(red 실행은 메인) | 대기 |
| 2026-10-30 06:44 | sp-01a 회수: A13ReviewEditTest 17·A14PriceAlertRenewTest 17·A14PriceAlertRearmTest 10 = 44 + BundleDSupportA(awaitTriggerWait — advisory 대기자 폴링). 컴파일 exit 0. T1(확정은 S2.toConfirmed, 소급 없음)·C2(판매 제한 미사용, 구매 제한은 입찰 없는 주인에만) 준수 보고. 모호 7(재등록 본문 400 미단언, 픽스처 전용 상태 1건, 순서 분기 단언 2건 등) | sp-01b 대기 |
| 2026-10-30 06:45 | sp-01b 회수: A15InquiryReopenTest 10·A15AdminInquiryTest 6·A16InboundRescheduleTest 11·A17NotificationBulkTest 6·A17NotificationPurgeTest 3 = 36 + BundleDSupportB(SDB.failOnDelete — DELETE 실패 주입 추가). 컴파일 exit 0. 모호 6(409 1~4끼리 순서는 같은 code라 구분 불가 등), CHECKED_IN·NO_SHOW 변경 409 미작성. 보충 합계 80 → contract3 248 예정 | red 확인 |
| 2026-10-30 06:56 | red 확인(두 앱 contract3 248): 기존 168 모두 통과, 새 80 모두 실패 — 사유는 NO_ROUTE·METHOD_NOT_ALLOWED·없는 배치 66 + 경합 미관측·트리거 미정지 14(대상 엔드포인트 부재로 경합 자체가 없음). 테스트 결함으로 볼 사유 0. 커밋 `a0f4171` | task 02 god |
| 2026-10-30 06:56 | task 02 위임: god 구현(sp-02, Opus) — 단계 A god 브리핑을 §20용으로 고친 scratchpad sp/brief-god.md(Marketplace.kt 로직·기존 22 테이블 쓰기 금지·fixcheck 25/25·§20.8 잠금 순서·A16 flush·재무장 CANCELLED·EXPIRED만, 248 전부 green) | 대기 |
| 2026-10-30 07:14 | sp-02 회수: god contract3 **248/248 첫 실행 green**(보충 80 + 기존 168), test 150·contract2 89, verify 120, fixcheck 25/25, 테스트-ADR 어긋남 0, 판단 5(A15 상세는 inquiryDetail 재사용·목록 slaBreached coalesce 바인드 등). **god 5,678줄(+450)** — 추정 +510~590보다 적지만 기준 5,500 넘음(ADR-0006 §6.1-7 반복 불필요). 메인 교차 확인: fixcheck 25/25, 지운 줄은 컨트롤러 KDoc 첫 줄 4개뿐. 커밋 `97cb1f5` | task 03 layered ∥ god 1패스 |
| 2026-10-30 07:15 | 병렬: task 03 layered(sp-03, Opus — 개정 2 해석으로 단계 A 파일 메서드 추가 허용, god 97cb1f5 문장 복사, allowcheck·fixcheck 33/33) ∥ god+테스트 듀얼 1패스(미러 sp/gmirror — Opus ∥ codex high, 렌즈: §20 문장·잠금 고리·불변식·blind 테스트 그린 위장·T1/C2 픽스처). (메인 실수: 커밋 해시를 97cc1da로 잘못 적어 첫 미러 생성 실패 → 97cb1f5로 정정) | 대기 |
| 2026-10-30 07:20 | god 1패스 codex 회수(high, try1 bwrap 1이나 판정 완료): god blocker·major 0, 잠금·불변식·5xx 지적 없음, 테스트 minor 1(재무장·정리 배치 processed 정확값 미단언 — 항상 1을 돌려주는 틀린 구현 통과) | Opus 대기 |
| 2026-10-30 07:26 | god 1패스 Opus 회수: god blocker·major 0(§20 문장 대조·잠금 표 8경로 고리 없음·불변식·5xx 지적 없음), 테스트 major 1·minor 3, ADR 문구 minor 1. 테스트 수정은 layered 워커(sp-03)가 behavior-tests를 컴파일·실행 중이라 회수 뒤에 | layered 대기 |
| 2026-10-30 07:35 | sp-03 회수: layered contract3 **248/248 첫 실행 green**, test 150·contract2 89, verify 120, fixcheck 33/33, allowcheck 0(95cc1da 시점 파일은 허용 3개에 추가만, 단계 A 파일 23개에 메서드 추가), 새 파일 0, 테스트-ADR 어긋남 0. layered 9,367 → 9,970(+603). 메인 교차 확인 fixcheck·allowcheck → 커밋 `293dc32` | 테스트 수정(SG-1~4) |
| 2026-10-30 07:36 | 병렬: 테스트 수정 sp-04(SG-1~4 + 음성 대조 2종 — god 잠금 순서 뒤집기·재확인 빼기 → 새 테스트 실패 확인 후 되돌림, docker 단독) ∥ layered 듀얼 1패스(미러 sp/lmirror — Opus ∥ codex high, god 문장 단위 대응표) | 대기 |
| 2026-10-30 07:42 | layered 1패스 codex 회수(high, bwrap 1이나 판정 완료): 지적 없음 — 기능별 대응표 13행 god와 WHERE·바인드·정렬·LIMIT·잠금·flush·판정별 질의 실행 여부 일치, 구조·불변식·DTO·400 메시지 지적 없음 | Opus 대기 |
| 2026-10-30 07:43 | layered 1패스 Opus 회수: blocker 0·major 0·minor 3 — 문장 대응표 31행 모두 동치(rearm 대상 조건 리터럴 diff SAME, 판정 실패 시 뒤 질의 미실행 동치, A16 flush 동치), 구조·불변식·동시성·DTO·메시지 지적 없음. minor: Inbound.kt RESERVE_BEFORE private → internal(단계 A 파일 — 허용), DTO 클래스 이름 InboundRescheduled vs InboundRescheduledView(JSON 같음), import 정렬 | 테스트 수정 대기 |
| 2026-10-30 07:59 | sp-04 회수: SG-1~4 반영(새 테스트 3 → contract3 **251**), god·layered 251/251 green. 음성 대조 ① god 재등록·재무장 잠금 순서 뒤집기(사용자 FU → 사이즈 KS) → SG-2 새 테스트 2개만 실패(재등록 400 UNEXPECTED_ERROR, 재무장 failed 1, app log deadlock detected) ② rearm 재확인 제거 → SG-3 새 테스트 1개만 실패(그 전엔 재확인을 덮는 테스트가 없었음). 되돌림 확인(god·layered diff 0). 커밋 `bd0971f` | equiv + 기준 수 |
| 2026-10-30 08:00 | 판정 기준 수 contract3 168 → 251(judge.py·test_contract3_stage.py), 러너 테스트 OK, ADR-0005 §4.1 숫자 — 커밋 `f1652f7`(code)·`4be02bb`(ADR). task 04 equiv 위임: sp-05(sa-d — A13~A17 + 새 배치 2·격리 1, A16 PAID 아닌 경로 포함(SG-5), 음성 대조: PAID 판정을 잠금 뒤로 옮긴 layered jar) | 대기 |
| 2026-10-30 08:47 | sp-05 회수: sa-d(A13~A17 직렬 + rearm·purge 각 격리 + lock_seq 4구간 — A16 UPDATE → INSERT 순서 포함) 단독 exit 0(응답 268·행 129·sql 5,886·pgstmt 8,800 양쪽 같음), `all` **17/17 같음 exit 0**, 앱 차이 0·수정 0·새 정규화 0. 음성 대조(layered A16 PAID 판정을 슬롯 잠금 뒤로) → exit 1, sql·pgstmt에 예약·슬롯 FU 추가 + 응답 메시지 차이 → SG-5 실증. 커밋 `e54111c`. §20.11 시드 NOW 대상 0은 데이터 실측(contract-b)으로 갈음 — 배치를 NOW에 실제로 돌린 확인은 안 함(생략한 검증) | 누적 루프 |
| 2026-10-30 08:47 | 누적 루프 1회차 시작(1cbe468..e54111c, 45파일 +3,994/−33, 미러 sp/zmirror): Opus(3자 일치·기준 수 251 실측·T1/C2 정답 패치 충돌 hunk 표) ∥ codex high | 대기 |
| 2026-10-30 08:54 | 누적 1회차 codex 회수(bwrap 0): blocker·major 0, 신규 minor 1(SZ-1: 보충 뒤 크기 실측·판정이 ADR-0006 §6.1-7에 없음). @Test 251 실측 = 기준 일치, T1·C2 정답 패치 대조 지적 없음, sa-d 지적 없음 | Opus 대기 |
| 2026-10-30 08:59 | 누적 1회차 Opus 회수: blocker·major 0, T1·C2 정답 패치 hunk 18개 대조 충돌 0, @Test 251 실측 일치, 신규 minor 4(SZ-2~5). SZ-1~5 메인 수정: ADR-0006 §6.1-7에 보충 뒤 실측(god 5,678 / 비공백·비주석 4,555 / Marketplace.kt 3,667, layered 9,970 / 7,457, +450은 추정 하한보다 60 적음, 여유 178줄 3.2% → 8로)·기준 수 갱신 기록, ADR-0005 상태 줄 251, sa-d 절대 단언 2(리뷰 수정 lock_seq·not-paid 구간 예약·슬롯 FU 0) → sa-d 같음 exit 0. 커밋 `c951cd9`(equiv)·`e0a3f3a`(ADR). 1회차 신규 5건 모두 minor·문서/검사 보강 → 2회차 | 2회차 |
| 2026-10-30 09:06 | 누적 2회차 Opus 회수: SZ-1~5 재점검 지적 없음(숫자·awk 구간·로그 실패 처리·lock_seq 패턴), 5개 테스트 본문 한 줄 대조 — 신규 minor 2: SZ-6(A13 수정 ∥ 분쟁을 한 방향만 — 분쟁이 먼저 주문을 쥔 순서 없음, 잠근 뒤 항목 재조회를 빠뜨린 구현 통과) · SZ-7(ADR-0007 §20.12·§20.14·README 미갱신 — 메인 수정 `4484618`) | codex·all 대기 |
| 2026-10-30 09:09 | 누적 2회차 codex 회수(try2): 재점검 지적 없음, 신규 minor 1 = SZ-7과 같은 지적(ADR-0007:734 '예고만' — 이미 4484618에서 수정) | SZ-6 테스트(equiv all 뒤) |
| 2026-10-30 09:44 | equiv `all` 17/17 같음 exit 0(e0a3f3a). SZ-6 메인 수정: A13 역방향 테스트(분쟁 제기를 order_items UPDATE 트리거에서 멈춘 사이 수정 → 분쟁 201·수정 409·리뷰 무변경) → god·layered contract3 **252/252**. 음성 대조: god editReview의 em.refresh(item) 제거 → 새 테스트만 실패(수정 200) → 되돌림·god diff 0. 기준 수 251 → 252(judge.py·러너 테스트 OK·ADR-0005/0006/0007·README). 커밋 `f84d8a7`(test)·`57298fe`(judge)·`115998a`(docs) | 3회차 |
| 2026-10-30 09:46 | 누적 3회차 codex 회수(bwrap 0): 지적 없음 — SZ-6 트리거가 분쟁의 주문 잠금 이후(god Marketplace.kt:966·layered ItemLocker.kt:18), @Test 252·문서/코드 숫자 일치, 신규 0 | Opus 대기 |
| 2026-10-30 09:49 | 누적 3회차 Opus 회수: SZ-6·숫자 252 재점검 지적 없음(경합 관측이 수정의 주문 대기임을 SlowTrigger 코드로 확인), 신규 minor 1(SZ-8 역방향 테스트가 '리뷰 행 그대로'를 rating만 단언) + 관찰 1(judge.py:190 옛 날짜 주석 — 비차단). **루프 상한 3 도달** — SZ-8은 content 단언 1줄 추가 후 두 앱 contract3 재실행(셀프 확인) | 마감 |
| 2026-10-30 09:58 | SZ-8 확인: god·layered contract3 252/252, verify 120. 커밋 `7d95aab` | 완료 요약 |
| 2026-10-30 10:51 | 사용자 결정 '병합·push 후 다음으로' → main fast-forward 1cbe468..2262d25 | push |

## 리뷰 ledger

| ID | 출처 | 지적 | 판정 | 처리 |
|---|---|---|---|---|
| SG-1 | codex minor(테스트) | A14 rearm 첫 실행 processed ≥ 1만, A17 purge 격리 실행 processed 미단언 — 처리 수를 틀리게 세는 구현 통과 | 채택 | 공유 DB라 고정값 대신 실행 직전 대상 수(같은 조건 SQL)와 processed 일치 단언 — fixed bd0971f |
| SG-2 | opus major(테스트) | A14 재등록·재무장 ∥ 입찰 등록 동시성 테스트가 새 경로를 먼저 멈춰 역순 구현(사용자 FU → 사이즈 KS)도 통과 — §19의 10-2 교착을 반증 못 함 | 채택 | 역방향 인터리빙 추가: 입찰을 bids INSERT 트리거에서 먼저 멈추고(사이즈 FU 보유) 재등록·재무장 → 정답은 둘 다 성공 — fixed bd0971f |
| SG-3 | opus minor(테스트) | 재무장 '잠근 뒤 재확인'을 덮는 테스트 없음 | 채택 | 위시 삭제를 사용자 FU 쥔 채 멈춘 사이 rearm → 해제 뒤 새 알림 없음 — fixed bd0971f |
| SG-4 | opus minor(테스트) | A16 변경 ∥ 구매자 취소 테스트가 Thread.sleep(300) 고정 지연 | 채택 | SDA.awaitTriggerWait()로 — fixed bd0971f |
| SG-5 | opus minor(테스트) | A16 'PAID 판정이 슬롯·예약 잠금보다 먼저'(§20.5.1의 1·GF-7)를 구분하는 테스트 없음 | 기각(테스트) — equiv로 이관 | 응답으로 구분 불가(같은 409) → equiv sa-d에 PAID 아닌 경로 + 음성 대조(sp-05) |
| SG-6 | opus minor(ADR) | §20.8 A13·A16 행의 scan 서술이 실제 순서(알림 FU → users KS(IDENTITY INSERT) → bids KS(커밋 UPDATE))와 다르고 줄 번호(:2596) 낡음 | 채택 | fixed 6abe4d9 |
| SL-1 | opus minor | layered domain/Inbound.kt RESERVE_BEFORE private → internal(기존 줄 가시성 변경) | 기록 | 단계 A 파일이라 개정 2 허용 범위, 동작 무변경 |
| SL-2 | opus minor | 응답 DTO 클래스 이름 god InboundRescheduled / layered InboundRescheduledView | 기각 — 기록 | JSON 같음, layered 관례(View 접미사) |
| SL-3 | opus minor | WishlistService.kt import 정렬 | 기각 | lint 없음, 동작 무관 |
| SZ-1 | codex minor(누적) | 보충 뒤 크기 실측(god 5,678·layered 9,970)과 '5,500 이상 → §6.1-8 진행' 판정이 ADR-0006 §6.1-7에 없음 | 채택 | fixed e0a3f3a |
| SZ-2 | opus minor(누적) | ADR-0005 상태 줄에 옛 기준 168 | 채택 | fixed e0a3f3a |
| SZ-3 | opus minor(누적) | ADR-0006 §6.1-7 '예고' 문장 미갱신 | 채택 | fixed e0a3f3a |
| SZ-4 | opus minor(누적, SZ-1과 같은 계열) | 보충 뒤 실측·비공백 수·추정과의 차이 미기록 | 채택 | fixed e0a3f3a |
| SZ-5 | opus minor(누적) | sa-d PAID 아닌 경로·리뷰 수정 잠금 순서가 두 앱 비교에만 기댐(함께 틀리면 놓침) | 채택 | fixed c951cd9 — 절대 단언 2 |
| SZ-6 | opus minor(누적 2, 신규) | A13 수정 ∥ 분쟁 제기 테스트가 수정 먼저 순서만 — 분쟁이 주문 FU를 쥔 사이 수정(→ 409) 순서 없음 | 채택 | fixed f84d8a7 — 음성 대조로 검출 확인, 기준 252 |
| SZ-7 | opus minor(누적 2, 신규) | ADR-0007 §20.12 '예고'·§20.14 추정만, README 168·16종 | 채택 | fixed 4484618 |
| SZ-8 | opus minor(누적 3, 신규) | A13 역방향 테스트가 리뷰 행 무변경을 rating으로만 단언(docstring은 '행 그대로') | 채택 | fixed 7d95aab — 두 앱 252/252 |
| SZ-9 | opus 관찰 | judge.py:190 '2026-10-26 … behavior-tests 바뀌지 않는다' 주석이 옛 문맥 | 기각 — 기록 | :191에 contract3 날짜·근거 별도, 틀린 내용 아님 |

## 생략한 검증

- SZ-8 수정(테스트 단언 1줄)은 누적 루프 상한(3) 뒤라 외부 리뷰 없이 두 앱 contract3 재실행으로 셀프 확인.
- §20.11 '1k 시드에 두 새 배치를 NOW로 돌려 processed 0' — 배치 실행 대신 같은 대상 조건의 데이터 실측(TRIGGERED 0·read_at ≤ NOW−30일 0, contract-b log)으로 갈음.

## 완료 요약

### 결과 (spec §1)

| # | 목표 | 충족 | 수치 |
|---|---|---|---|
| 1 | contract3 A13~A17 테스트 | 충족 | blind Opus 2명 80개 → 리뷰 반영 +3(SG-1~3)·+1(SZ-6) = 보충 84. god·layered 모두 **252/252** |
| 2 | god·layered §20 구현 | 충족 | test 150·contract2 89·verify 120 그대로, fixcheck 25/25·33/33, allowcheck 0(95cc1da 시점 파일은 허용 3개에 추가만, 단계 A 파일 23개에 메서드만, 새 파일 0). 두 앱 모두 첫 실행 248/248 |
| 3 | equiv 보충 | 충족 | sa-d(A13~A17 직렬·rearm/purge 격리·lock_seq 4구간 + 절대 단언 2), `all` **17/17 같음**, 앱 차이 0·새 정규화 0 |
| 4 | 판정 기준 수 | 충족 | contract3 168 → **252**, 러너 테스트 OK, ADR-0005 §4.1 |
| 5 | god 실측 | 충족 | god 5,228 → **5,678(+450)**(비공백·비주석 4,555, Marketplace.kt 3,667) ≥ 5,500 → ADR-0006 §6.1-8로. layered 9,367 → 9,970(+603) |
| 6 | 시드 NOW 새 배치 0 | 갈음 | 배치 실행 대신 같은 대상 조건 데이터 실측(TRIGGERED 0, read_at ≤ NOW−30일 0) — 생략한 검증에 기록 |

### 바뀐 것

| 영역 | 커밋 |
|---|---|
| 테스트 80 + 보강(SG-1~4 → 251, SZ-6 → 252, SZ-8) | `a0f4171` · `bd0971f` · `f84d8a7` · `7d95aab` |
| god(Marketplace.kt +325, 컨트롤러 4·Dtos) | `97cb1f5` |
| layered(단계 A 파일 메서드 추가, 새 파일 0) | `293dc32` |
| equiv sa-d + 절대 단언 | `e54111c` · `c951cd9` |
| 판정기 기준 251 → 252 | `f1652f7` · `57298fe` |
| ADR·README | `6abe4d9` · `4be02bb` · `e0a3f3a` · `4484618` · `115998a` |

47파일 +4,036/−38.

### 핵심 diff

**① god `rescheduleInbound`** — PAID 판정 → 잠금 → UPDATE → `em.flush` → INSERT (`git show 97cb1f5`)
```kotlin
+            val item = em.find(OrderItem::class.java, reservation.orderItemId)
+            em.createNativeQuery("SELECT id FROM orders WHERE id = :id FOR UPDATE").setParameter("id", item.orderId).resultList
+            em.refresh(item)
+            requireItemStatus(item, "PAID")
+            lockRow("inbound_reservations", reservationId)
+            em.refresh(reservation)
+            if (reservation.status != "RESERVED") stateConflict("RESERVED가 아닌 예약: $reservationId (${reservation.status})")
+            if (reservation.slotId == slotId) stateConflict("지금과 같은 슬롯: $slotId")
+            lockRow("inbound_slots", slotId)
+            if (liveReservationsOfSlot(slotId) >= slot.capacity) stateConflict("정원이 찬 슬롯: $slotId")
...
+            reservation.status = "CANCELLED"
+            reservation.closedAt = now
+            em.flush()
```

**② layered 재무장 대상 조건** (`infra/JpaNotificationRepositories.kt`)
```kotlin
+internal object PriceAlertRearmSql {
+    const val CONDITION = "t.status = 'TRIGGERED' AND t.closed_at >= :due" +
+        " AND EXISTS (SELECT 1 FROM bids b WHERE b.id = t.triggered_bid_id AND b.status IN ('CANCELLED', 'EXPIRED'))" +
+        " AND EXISTS (SELECT 1 FROM wishlists w WHERE w.user_id = t.user_id AND w.product_size_id = t.product_size_id)" +
+        " AND NOT EXISTS (SELECT 1 FROM price_alerts n WHERE n.user_id = t.user_id AND n.product_size_id = t.product_size_id" +
+        " AND (n.id > t.id OR n.status = 'ACTIVE'))" +
+        " AND NOT EXISTS (SELECT 1 FROM users u WHERE u.id = t.user_id AND u.purchase_restricted_until > :now)"
```

**③ A13 역방향 인터리빙 테스트** (`f84d8a7` + `7d95aab`)
```kotlin
+        val rs = SlowTrigger.on("order_items", "UPDATE", condition = "OLD.id = ${p.item}") {
+            val pool = Executors.newSingleThreadExecutor()
+            try {
+                val dispute = pool.submit<Resp> { S2.dispute(p.item, p.buyer) }
+                SDA.awaitTriggerWait()
+                val patch = SDA.patchReview(id, p.buyer, 1, "분쟁 중 수정")
+                listOf(dispute.get(30, TimeUnit.SECONDS), patch)
+            } finally {
+                pool.shutdownNow()
+            }
+        }
+        rs[0].assertStatus(201)
+        rs[1].assertError(409, "ITEM_STATE_CONFLICT")
```

### 리뷰·검증

- 단계별 1패스: god+테스트(채택 SG-1~4·6, SG-5는 equiv로 이관), layered(대응표 13·31행 동치, minor 3 기록·기각). 누적 듀얼 루프 3회(상한) — 1회차 신규 5·2회차 2·3회차 1, 모두 minor, 전부 채택(SZ-9 관찰 기각). T1·C2 정답 패치 hunk 18개 대조 충돌 0, @Test 실측 = 기준 수.
- 음성 대조 4종(경로 5): ① god 재등록·재무장 잠금 역순 → SG-2 테스트 2개만 실패(deadlock detected) ② rearm 재확인 제거 → SG-3 테스트만 실패 ③ layered A16 PAID 판정을 슬롯 잠금 뒤로 → equiv sa-d exit 1 ④ god editReview 항목 재조회 제거 → SZ-6 테스트만 실패. 모두 되돌림 확인.
- 결함 분포: **구현 결함 0**(두 앱 첫 실행 green). 채택 지적은 전부 테스트·equiv·문서 — 특히 동시성 테스트가 한 방향 인터리빙만 시험해 잘못된 잠금 순서·재조회 누락을 통과시키는 꼴이 세 번(SG-2·SG-3·SZ-6) 나왔다.

### 남은 것

- T1·C2 이식(ADR-0006 §6.1-8): symbols·고칠 곳 줄 번호 재생성(Marketplace.kt 3,667줄), 정답·naive·none 실측(contract3 252 회귀 포함).
- C2 정답 코드의 잔여 교착 위험(ADR-0007 §20.8 — 재등록·재무장, ADR-0005 §9의 13).
- 파일럿 A 전 `--parallel 3` SlowTrigger 실패율 측정(NEXT).
- 크기 여유 178줄(3.2%) — 이식으로 god가 줄지 않는지 확인.
- CS 이슈 아카이브: study-note 쓰기 금지(spec §4) → 보류.
