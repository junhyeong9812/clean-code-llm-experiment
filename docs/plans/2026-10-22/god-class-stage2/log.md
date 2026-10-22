# log — 갓 클래스 구현 2단계 (god)

> 작업 폴더: `docs/plans/2026-10-22/god-class-stage2/` · 기준: main `f7dde74`

## 타임라인

| 시각 | 사건 | 결과 |
|------|------|------|
| 2026-10-22 | 착수 — 사용자 "다음 진행해줘" → NEXT N1 | 작업 폴더 생성, 인터뷰 시작 |
| 2026-10-22 | 인터뷰 6문항 응답, 명세 합의(auto) | SPEC=1·MODE=auto, 브랜치 feat/god-class-stage2 |
| 2026-10-22 | task 01(§8.1 적립률) Opus 워커 착수 | 기준 c3d7c22 |
| 2026-10-22 11:27 | task 01 회수 — ea0ff42(Marketplace.kt +23/−1, 네이티브 SQL 1문장) | contract2 3/82(적립률 3개), 1단계 exit 0(infra 28·contract 117·verify 0). 메인 교차 확인: approveOrder 호출처 2곳(동기 357·pg-inquiry 964), 기동 전환은 UNKNOWN 전환만 → 승인은 pg-inquiry 경유 |
| 2026-10-22 | task 02(엔티티·§2·항목 조회·§3 PASS까지) Opus 워커 착수 | 기준 ea0ff42, 검수 FAIL은 task 03으로 |
| 2026-10-22 11:35 | task 02 회수 — dc106a4(+214/−4, 5파일) | contract2 8/82(메인 XML 집계 일치, skip 0), 1단계 exit 0, gradlew build 통과. 실패 74 전부 미구현 경로. 검수 FAIL은 임시로 400 UNEXPECTED_ERROR(롤백) |
| 2026-10-22 | task 03 Opus 워커 착수 — 1단계: 잠금·트랜잭션·PG 위치·금액·포인트 계획만(설계 선검증) | 기준 dc106a4 |
| 2026-10-22 11:41 | task 03 계획 회수(설계 선검증) | 잠금: 주문 → [PG] → 사용자(id 오름차순), PG는 주문 잠금 보유 중 한 트랜잭션, PG 전 쓰기 없음 → 롤백으로 무변경. 금액 공식 §12 A1·A2·E1~E7 손계산 일치 |
| 2026-10-22 11:43 | 메인 판정: 승인 + 수정 2건 | A: 항목 행 FOR UPDATE가 없어 실효 순서가 주문 → 사용자 → 항목 — 정산(항목 잠금 + users FK KEY SHARE)과 교착 가능 → lockOrderOf에 항목 FOR UPDATE 추가(§9.1 순서). B: 분쟁 제기 응답 4필드(§4.3). REVOKE order_id=주문(ADR 우선, 생성기와 다름), RESTORE 뒤 EXPIRE의 order 열 NULL, 만료 미처리 묶음도 REVOKE 승인 |
| 2026-10-22 11:52 | task 03 회수 — 8c397ce(+413/−7, 6파일) | contract2 49/82(메인 XML: fail 33 전부 배치 미구현 32 + /me/settlements 1, skip 0), 1단계 exit 0·verify 0. 수정 A·B 반영 확인(lockOrderOf 항목 FOR UPDATE, DisputeOpened 4필드) |
| 2026-10-22 | task 04(배치 4종) Opus 워커 착수 | 기준 8c397ce, 배치 소요 시간 측정 포함 |
| 2026-10-22 12:03 | task 04 회수 — f375b3e(+129/−2) | contract2 69/82(메인 XML 확인: 실패 13 = weekly-settlement 12 + /me/settlements 1), 1단계 exit 0. 배치 최장 point-expiry 26.7s(processed 2,162)·grade-recalc 4.2s. grade-recalc 후보 사전 필터 한계는 §8.5 '실행 시점 상태' 범위로 수용 |
| 2026-10-22 | task 05(주간 정산) Opus 워커 착수 — 1단계 계획만(설계 선검증: 주 단위 트랜잭션·잠금·소요 시간) | 기준 f375b3e |
| 2026-10-22 12:10 | task 05 계획 회수(설계 선검증) | 한 주 = tx 1개: 대상 order_items id 순 FOR UPDATE → 같은 조건 재평가 → 패널티·이월 → 판매자별 persist. 정산은 사용자 잠금(FK KEY SHARE) 전에 항목을 모두 잠그고, 항목 처리는 항목 1개만 쥠 → 순환 없음. S1~S4 손계산 일치. 주당 40~80ms 추정(30~40주 ≈ 3s). 워커가 프로브 DB(ccle-plan-probe, 15499)를 조회·EXPLAIN용으로 띄우고 삭제 |
| 2026-10-22 12:11 | 메인 판정: 승인 | 컨트롤러는 OrderController에. 500k 성능 위험(CONFIRMED 안티조인·행 단위 persist)은 ADR 그대로 두고 NEXT에 기록. 0건 분기 스모크는 메인이 task 06에서 |
| 2026-10-22 12:19 | task 05 회수 — 3637456(+223/−5, 4파일) | contract2 82/82(메인 XML 확인), 1단계 exit 0·verify 0, build 통과. weekly-settlement 최장 2,416ms(processed 114) |
| 2026-10-22 12:20 | task 06 착수 — 검증 백그라운드(god contract2 2회차 → layered 1단계) | 로그 scratchpad/t6-c2b.log·t6-lay.log |
| 2026-10-22 12:23 | 리뷰 루프 1 packet — BASE c3d7c22, OUT=scratchpad/rv.CcSu, MIRROR=scratchpad/mirror.23IK | 누적 diff 7파일 +984/−8, untracked 0, 보안 스캔 시크릿 0(로컬 경로 1건 = 기존 문서, 유지). Opus ∥ codex 병렬 착수 |
| 2026-10-22 | codex 리뷰 1회차 실패 — bwrap `Failed RTM_NEWADDR`로 미러 읽기 불가, exit 0으로 '판정 불가' 출력 | 출력 내용으로 실패 판정하도록 바꿔 재시도(최대 3회) |
| 2026-10-22 | codex 리뷰 재시도 — try1 bwrap 재발, try2 정상 | findings 0, open question 0, 렌즈 8개 전부 applicable·확인(근거 file:line 있음) |
| 2026-10-22 12:25 | 검증 2회차·layered | god contract2 82/82(2회 연속 green, skip 0) · layered 1단계 rc 0(infra 28·contract 117·verify 0) — layered 무변경 확인 |
| 2026-10-22 12:33 | 수동 스모크 ① 기동 전환 적립률(scratchpad/smoke1.sh) | 1회차 무효: 구매자 선택 쿼리 버그로 주문일 등급이 이미 VIP인 사용자(판별 불가) → 쿼리 수정. 2회차: buyer 6(주문일 10-08 최신 이력 GOLD, 10-09 VIP 이력 + users.grade=VIP 주입) → 결제 중 kill -9 → PAYING → 재기동 UNKNOWN(+APPROVE UNKNOWN 행) → pg-inquiry PAID, EARN 2,000 = GOLD 2%(VIP면 3,000). **통과** |
| 2026-10-22 12:45 | 수동 스모크 ② 정산 0건 분기(scratchpad/smoke2.sh, 정산 행·연결 비운 1k DB) | [A] 10-08 → 09-28 주 195행(processed 195, 과거 미정산 패널티 175건 전부 이 주에 연결 — ADR 문자대로), 재실행 0, 시계 10-19 00:00 → 10-05 주 31행만. 10-12 주 미생성은 대상 0·음수 이월 0·미정산 패널티 0 확인 → §10.2 '행 없음'(내 기대값 오류). paid_at 불일치 0 · [B] 10-05 00:00 정각 → 09-28 주 · [C] 10-04 23:59:59 → 09-21 주 · 앱 ERROR 0. **통과** |
| 2026-10-22 12:40 | Opus 리뷰 루프 1 회수 | findings 2(F1 0원 패널티 CHECK 위반이 PG 취소 뒤, F2 배치 정지) + open question 5 |
| 2026-10-22 12:46 | 메인 종합 → codex 감사 착수 | R1-1(F1+F2) 채택·도달 가능(Marketplace.kt:128 price>0만) — ADR 공백이라 재합의 필요, R1-2(PG 무응답 뒤 다른 항목 환불로 금액 변경) 사용자 결정, R1-3 기각·R1-4 NEXT·R1-5·R1-6 도달 불가 기각 |
| 2026-10-22 12:52 | codex 종합 감사 회수(try 1 정상) | R1-1 결론 동의(근거 조항은 §2.1이 아니라 §2.3으로 정정), F2는 병합 대신 배치 포이즌 대상 방침 질문으로 분리, R1-3은 '영향 없음' 근거 부족 → open question으로만. R1-2·R1-4~6 동의 |
| 2026-10-22 12:58 | 사용자 결정(재합의 1) | R1-1 "계약 밖으로 기록", R1-2 "기존 '계약 밖'에 포함" → ADR-0004 §6·§7.2 개정(코드 변경 없음), spec §4 재합의 1 기록 |

## 리뷰 ledger

| id | first_seen_loop | source | 근거 | disposition | status | fixed_in_loop |
|----|-----------------|--------|------|-------------|--------|---------------|
| R1-1 | 1 | opus(+감사 근거 정정) | Marketplace.kt:956(패널티 persist가 :1001 PG 뒤), tables.sql:248, Marketplace.kt:128(price > 0만) — 체결가 1~9원 → 0원 패널티 CHECK 위반 → 카드만 취소 | 채택(도달 가능, 단 시드·테스트·verify 밖) | fixed(ADR §6 계약 밖 개정 — 사용자 결정) | 1 |
| R1-2 | 1 | opus | Marketplace.kt:994-1001, :1055-1072, ADR-0004 §7.2 — PG 기록 후 무응답 사이 다른 항목 환불로 금액 변경 → 409 반복 | open question → 사용자 결정 | fixed(ADR §7.2 계약 밖 확장) | 1 |
| R1-F2 | 1 | opus(+감사) | Marketplace.kt:1292-1306, :1441-1458 — PG_UNAVAILABLE 외 예외면 배치 전체 정지 | open question | user-deferred(NEXT N2 기존 질문 '배치 대상 1건 실패 시 전체 중단'에 2단계 배치 포함으로 기록) | — |
| R1-3 | 1 | opus | Marketplace.kt:787 isNullOrBlank vs "앞뒤 공백" 문자 집합 미정 | open question | user-deferred(NEXT N1 — layered가 같은 판정을 쓰는지 확인) | — |
| R1-4 | 1 | opus | Marketplace.kt:1563-1605 OR 조건 순차 스캔 — 500k 정산 시간 | 범위 밖(spec 가정 2는 1k) | user-deferred(NEXT) | — |
| R1-5 | 1 | opus | Marketplace.kt:1606, 1617 CLAWBACK fee 재계산 | 기각(율 변경 경로 없음 — 도달 불가) | — | — |
| R2-OQ2 | 2 | opus | Marketplace.kt:1423-1427, :1442 — 늦은 승인 주문의 발송 기한이 승인 시점에 이미 경과 가능 | open question | user-deferred(NEXT N2) | — |
| R2-OQ3 | 2 | opus | Marketplace.kt:1001-1039 — PG 취소 성공 뒤 커밋 예외 | open question(§7.2 계약 밖과 같은 종류로 해석) | user-deferred(NEXT N2) | — |
| R1-6 | 1 | opus | Marketplace.kt:1601 vs 1605 잠금·재평가 스냅샷 차이 | 기각(auto-confirm 선행·기각 항목은 HELD로 잠김 — 도달 불가, 감사 동의) | — | — |

- codex 루프 1: findings 0, 렌즈 8개 applicable·확인. 루프 2: codex findings 0(OQ = R1-4), Opus findings 0. codex 실행: 1회차 bwrap 실패(exit 0 '판정 불가') → 재시도 try1 bwrap, try2 정상.
| 2026-10-22 13:00 | 리뷰 루프 2 packet — BASE c3d7c22, OUT=scratchpad/rv.np6E, MIRROR=scratchpad/mirror.iYB9(루프 1 미러 삭제) · equiv all 착수 | 누적 diff 9파일 +987/−9(ADR 개정 포함), untracked 0. Opus ∥ codex 병렬 |
| 2026-10-22 13:08 | codex 루프 2 회수(try 1 정상, 보안 스캔 0) | findings 0, OQ 1(대규모 정산 시간 — R1-4와 같음), 렌즈 8 applicable·확인 |
| 2026-10-22 13:15 | Opus 루프 2 회수 | findings 0, OQ 4: ①공백 문자 집합(=R1-3) ②늦게 승인된 주문(pg-inquiry·기동 전환)은 승인 시점에 이미 paid_at+3일 경과 가능 → 다음 shipment-deadline이 발송 기회 없이 판매자 귀책(ADR 문자대로) ③PG 취소 성공 뒤 커밋 실패(§7.2 계약 밖과 같은 종류) ④0행 주에서 마지막 마감 미전진(스모크 ② 4회차 processed 0으로 실측 — 닫힘). 렌즈 8 applicable·verified |
| 2026-10-22 13:16 | **리뷰 종료(루프 2)** | 종료식: open(채택·미수정) 0 · 루프 2 신규 채택 0 · 대칭 부담 — 렌즈 8개 전부 applicable, opus·codex 양쪽 모두 file:line 근거로 verified(비대칭 아님). OQ ②③은 NEXT N2에 사용자 판단 항목으로 |
| 2026-10-22 12:50 | equiv all(scratchpad/t6-equiv.log) rc 1 — 같음: run·errors / 차이: batch·pay·recov·conc·fail | **응답 계층 7개 시나리오 전부 같음.** SQL·pg 문장 차이는 전부 §8.1: users 엔티티 조회 → 등급 이력 네이티브 쿼리, 그 쿼리의 auto-flush로 `update orders`가 앞당겨짐(batch·pay·recov·conc는 이 세 패턴 외 차이 0줄, conc 1줄 = god 등급 쿼리 1회 — layered는 컨텍스트의 사용자 재사용). fail: 단계 B 트리거(`fail.sh:50` BEFORE UPDATE orders PAID)가 god에선 auto-flush 시점에 터져 EARN INSERT 전 실패, layered는 커밋 flush라 EARN INSERT(시퀀스 소비) 뒤 롤백 → 새 행 계층에서 point_ledger id가 1 밀림(stage C·restart·inquiry), 금액·상태·건수 같음. **예상 차이로 분류**(spec: 하네스 무수정). layered 2단계 때 §8.1을 넣으면 SQL 패턴은 그쪽 구현에 따라 다시 비교 |
| 2026-10-22 13:40 | 마감 문서 — 완료 요약(Opus 워커 초안, 메인이 스니펫 1개 실파일 대조·NEXT 번호 반영), NEXT·README·measurement-log | 시작 줄 수는 1,672(c3d7c22 추적 13파일 — 메인이 사용자에게 1,690이라 말한 것은 정정) |

## 완료 요약

### 결과

`god`가 ADR-0004(2단계)와 §8.1 적립률을 구현했다. `run.sh god contract2Test` 82/82(2회 연속 green, skip 0). 1단계 `run.sh god`는 exit 0(infra 28·contract 117·verify 위반 0). `run.sh layered`는 rc 0에 같은 수치라 layered 무변경. 루트 `./gradlew build` 통과. 수동 스모크 2건 통과: ①기동 전환 적립률(EARN 2,000 = 주문일 등급 GOLD 2%) ②정산 0건 분기(첫 주 195행·재실행 0·경계 시각 2건). 카드 취소 무응답은 재현할 수 없어 코드 리뷰로 판정했다. `equiv all`은 응답 계층 7개 시나리오가 모두 같다. SQL·pg 문장과 fail 시나리오의 point_ledger id 1칸 밀림은 전부 §8.1(등급 이력 네이티브 쿼리와 그 쿼리의 auto-flush)로 설명되어 예상 차이로 분류했다. 듀얼 리뷰는 루프 2에서 끝났다(open 채택 0, 루프 2 신규 채택 0, 렌즈 8개 양쪽 verified).

### task별

| task | 커밋 | 파일 +/− | contract2 누적 green | 핵심 |
|---|---|---|---|---|
| 01 | ea0ff42 | 1파일 +23/−1 | 3/82 | §8.1 적립률 등급 = 주문 생성일(KST) 등급 이력(네이티브 SQL 1문장), 승인 경로 3개 공용 |
| 02 | dc106a4 | 5파일 +214/−4 | 8/82 | 2단계 엔티티, `GET /order-items/{id}`, 발송·입고·검수 PASS·배송 완료 |
| 03 | 8c397ce | 6파일 +413/−7 | 49/82 | 확정·취소·분쟁·판정, 판매자 귀책·패널티, 환불·카드 취소·포인트 복구·회수. 설계 선검증에서 수정 A(항목 FOR UPDATE)·B(분쟁 응답 4필드) |
| 04 | f375b3e | 1파일 +127/−2 | 69/82 | 배치 shipment-deadline·auto-confirm·point-expiry·grade-recalc(최장 point-expiry 26.7s) |
| 05 | 3637456 | 4파일 +218/−5 | 82/82 | weekly-settlement(한 주 = 트랜잭션 1개)·`GET /me/settlements`(최장 2,416ms) |

- 파일 +/−는 `git show --stat` 기준이다(타임라인의 task 04·05 수치는 워커 보고값 그대로 — 사후 수정하지 않는다). 누적 diff `c3d7c22..HEAD -- god/`는 7파일 +984/−8이다.

### 핵심 diff (실파일 발췌, HEAD 3637456 = Marketplace.kt 최종)

①적립률 등급. `god/src/main/kotlin/com/ccle/god/Marketplace.kt:647`(ea0ff42 당시 :628), 조회 함수는 `:620-634` `gradeOnOrderDate`:

```kotlin
         // 등급 적립률(ADR-0002 §5.2): BRONZE 1% · SILVER 1.5% · GOLD 2% · VIP 3%
-        val grade = em.find(User::class.java, order.buyerId).grade
+        val grade = gradeOnOrderDate(order)
```

```kotlin
    private fun gradeOnOrderDate(order: PurchaseOrder): String =
        em.createNativeQuery(
            """
            SELECT coalesce(
                       (SELECT h.grade FROM user_grade_history h
                         WHERE h.user_id = u.id AND h.effective_from <= :orderDate
                         ORDER BY h.effective_from DESC LIMIT 1),
                       (SELECT 'BRONZE' FROM user_grade_history h WHERE h.user_id = u.id LIMIT 1),
                       u.grade)
              FROM users u
             WHERE u.id = :user
            """,
        ).setParameter("orderDate", LocalDate.ofInstant(order.createdAt, ZoneOffset.ofHours(9)))
            .setParameter("user", order.buyerId)
            .singleResult as String
```

②주문 → 항목 잠금. `Marketplace.kt:822-828` `lockOrderOf`(dc106a4에서 만들고 8c397ce에서 수정 A로 항목 FOR UPDATE를 추가):

```kotlin
     private fun lockOrderOf(itemId: Long): OrderItem {
         val item = em.find(OrderItem::class.java, itemId) ?: notFound("없는 주문 항목: $itemId")
         em.createNativeQuery("SELECT id FROM orders WHERE id = :id FOR UPDATE").setParameter("id", item.orderId).resultList
+        em.createNativeQuery("SELECT id FROM order_items WHERE id = :id FOR UPDATE").setParameter("id", itemId).resultList
         em.refresh(item)
         return item
     }
```

③환불: PG를 부른 뒤 사용자를 잠근다. `Marketplace.kt:1001-1003` `refundItem`(8c397ce 신규, 이전 코드 없음). PG 호출 전에는 쓰기가 없어서 409면 롤백해도 DB가 바뀌지 않는다:

```kotlin
        val pgTxId = if (card > 0) cancelCard(order, item.id!!, card) else null

        if (fault == "SELLER") lockUsers(order.buyerId, em.find(Trade::class.java, item.tradeId).sellerId) else lockUser(order.buyerId)
```

④주간 정산: 한 주에 트랜잭션 하나, 대상 항목을 먼저 잠근다. `Marketplace.kt:1575-1603`(3637456 신규, 이전 코드 없음):

```kotlin
    private fun settleWeeks(now: Instant): Int {
        autoConfirmItems(now)
        val latest = LocalDate.ofInstant(now, kst).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val lastPeriodStart = em.createQuery("SELECT max(s.periodStart) FROM Settlement s", LocalDate::class.java).singleResult
        var cutoff = lastPeriodStart?.plusDays(14) ?: latest
        var processed = 0
        while (!cutoff.isAfter(latest)) {
            val week = cutoff
            processed += tx.execute { settleWeek(week) }!!
            cutoff = cutoff.plusDays(7)
        }
        return processed
    }
```

```kotlin
    private fun settleWeek(cutoffDate: LocalDate): Int {
        val cutoff = cutoffDate.atStartOfDay(kst).toInstant()
        val periodStart = cutoffDate.minusDays(7)
        em.createNativeQuery("SELECT i.id FROM order_items i WHERE $settlementTargetSql ORDER BY i.id FOR UPDATE")
            .setParameter("cutoff", cutoff).resultList
```

### 계약 개정 (2603394, ADR-0004 계약 밖 2건 — 재합의 1, 코드 변경 없음)

- §6: 체결가가 10원 미만인 항목의 판매자 귀책은 패널티가 0원이 되어 `amount > 0` CHECK·verify와 맞지 않는다. 이 경우는 계약 밖으로 두었다(R1-1).
- §7.2: PG가 취소를 기록한 뒤 응답하지 않은 사이에 같은 주문의 다른 항목이 환불되면 "마지막 환불" 여부가 바뀐다. 그러면 재요청 금액이 첫 기록과 달라 409가 반복된다. 이 경우를 기존 '계약 밖'에 포함했다(R1-2).

### 미해결·이월 (NEXT로)

- R1-F2: 배치 대상 1건에서 PG_UNAVAILABLE이 아닌 예외가 나면 배치 전체가 멈춘다(`:1292-1306`, `:1441-1458`). NEXT N2의 기존 질문에 2단계 배치를 포함했다.
- R1-3: 사유 판정 `isNullOrBlank`(`:787`)와 "앞뒤 공백"의 문자 집합이 정해지지 않았다. NEXT N1(layered 2단계)에서 layered가 같은 판정을 쓰는지 확인한다.
- R2-OQ2: pg-inquiry나 기동 전환으로 늦게 승인된 주문은 승인 시점에 이미 발송 기한이 지났을 수 있다. 그러면 다음 shipment-deadline에서 발송 기회 없이 판매자 귀책이 된다(ADR 문자대로, NEXT N2).
- R2-OQ3: PG 취소가 성공한 뒤 커밋에서 예외가 나는 경우. §7.2 계약 밖과 같은 종류로 해석했다(NEXT N2).
- 성능(R1-4): 정산 대상 SQL의 OR 조건이 순차 스캔이 된다(`:1563-1568`). 정산 행도 행 단위로 persist한다. 500k 시드에서의 정산 시간은 측정하지 않았다(spec 가정 2는 1k 기준). point-expiry는 1k에서 26.7s로 배치 중 가장 오래 걸려 500k에서 위험하다.
- 한계: 카드 취소 무응답(PG가 기록한 뒤 타임아웃되는 경로)은 저장소 테스트로도 수동 스모크로도 재현하지 못했다. 코드 리뷰로만 판정했다. 근거는 `refundItem` PG 호출 전 쓰기 0, `cancelCard` 금액 불일치 409, 키 `cancel-<id>` 단일이다.

### 규모 (god main `.kt`)

| | c3d7c22 | HEAD |
|---|---|---|
| 파일 | 13 | 14 (+ItemController.kt) |
| 줄 | 1,672 | 2,648 |
| Marketplace.kt | 984 | 1,735 |

### 사이클 마감

- NEXT 갱신(N1 = layered 2단계, N2 = 1·2단계 open question), README 진행 행, measurement-log 1행.
- CS 이슈 아카이브: study-note 쓰기 금지(금지영역)라 보류 — NEXT '보류·이월'에 기록.
