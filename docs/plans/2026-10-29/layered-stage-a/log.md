# log — layered 단계 A 구현 (ADR-0007 A1~A12)

> 작업 폴더: `docs/plans/2026-10-29/layered-stage-a/` · 브랜치 `impl/layered-stage-a`(main `95cc1da`) · 산출물 유형: 실행물(layered 앱)

## 타임라인

| 시각 | 사건 | 결과 |
|---|---|---|
| 2026-10-29 21:57 | god 단계 A 병합·push(`95cc1da`, 사용자 "병합·push 후 layered로 진행") · 브랜치 `impl/layered-stage-a` 생성 · 작업 폴더 생성. layered 현재 4,756줄. 고칠 곳 대조 스크립트 scratchpad `l7/fixcheck-layered.py`(T1·C2 symbols.layered + T2·C1·P1·P2 지점 33개 범위) 기준선 33/33 | 인터뷰 |
| 2026-10-29 22:04 | 인터뷰 1문항(god와 같은 방식 — 묶음 순차·듀얼·테스트 결함 Claude 판정·auto, god SQL·잠금 정본 맞춤, equiv 새 시나리오는 다음 작업) → spec → set-state. 허용 목록 검사 스크립트 `l7/allowcheck.sh`(기존 파일 변경이 BatchService·BatchTargets·KeysetBatchTargets 밖이면 실패 + 삭제 줄 출력) 기준선 통과. 공통 브리핑 `l7/brief-layered.md` | 묶음 A 위임 |
| 2026-10-29 22:25 | 묶음 A(ls-02a) 회수: 8개 클래스 57/57 첫 실행 통과, test 150·contract2 89·verify 120, fixcheck 33/33, allowcheck(허용 파일 3개만, 삭제 줄은 forEachIsolated private 1줄), 새 파일 17, layered 6,378줄(+1,622), god 문장 복사·잠금 순서 맞춤(차이 가능 4곳 보고), 테스트-ADR 어긋남 0. 메인 교차 확인 fixcheck·allowcheck → 커밋 `0100ca6` | - |
| 2026-10-29 22:25 | 병렬: 묶음 B 구현(ls-02b, 기준 0100ca6) ∥ 묶음 A 듀얼 1패스(미러 `l7/amirror`, diff 95cc1da..0100ca6 — Opus ls-rva-opus ∥ codex, 렌즈 1 = god와 SQL·잠금 동치) | 대기 |
| 2026-10-29 22:30 | 묶음 A 듀얼 1패스 회수: codex(bwrap 0 — major 1) · Opus(god 대조 표 26행 — minor 1)가 같은 차이: 리뷰 작성이 `countOfItem`을 인자로 먼저 평가해 상태 409 경로에서 count가 god보다 먼저 나감(god는 상태 검사 뒤) → LA-1 채택, 메인 수정(existingReviews를 지연 람다로 — 상태 검사 뒤 호출). 그 밖 SQL·잠금·바인드·flush·격리 god와 같음 | B 회수 뒤 실행 확인 |
| 2026-10-29 22:48 | 묶음 B(ls-02b) 회수: 7개 클래스 55/55 첫 실행 통과(A4 11·6, A5 9·8·10, A11 7·4), 묶음 A 그대로, test·contract2·verify·fixcheck 33/33·allowcheck 0, 새 파일 12(ReminderSql·DelaySql에 god 조건 문자열·바인드 그대로), layered 7,511줄, 테스트-ADR 어긋남 0. 커밋 `7289d69`(LA-1)·`559087c`(B). 메인 재실행: contract3 16개 클래스 통과·C 5 red, verify 120 → LA-1 fixed 확인 | 묶음 C ∥ B 리뷰 |
| 2026-10-29 22:51 | 묶음 B 듀얼 1패스 codex 회수(bwrap 0): 지적 없음 — ReminderSql 여섯 알림 WHERE·바인드·dedupe·INSERT…SELECT, 가격 알림 순서, 추적·지연, 10-1·10-2 잠금 순서 god·ADR §19와 일치 | Opus 대기 |
| 2026-10-29 22:53 | 묶음 B Opus 회수: blocker 0·major 0·minor 3, god 대조 표 18행 모두 같음(equiv로 볼 곳: price-alert TRIGGERED의 INSERT→UPDATE 순서, ReminderSql dedupe 식 공유). minor 3건(LB-1~3)은 사본 확산 방지 다듬기 → 묶음 C 워커(ls-02c)에게 전달해 같이 처리(작성 중인 파일과 충돌 방지) | C 회수 대기 |
| 2026-10-29 23:10 | 묶음 C(ls-02c) 회수: contract3 전체 168/168 첫 실행(A6 17·A8 6·A9 8·A10 8·A12 15), test 150·contract2 89·verify 120, 새 파일 25, LB-1~3 반영(StageAText·StageAParse·DelaySql), 테스트-ADR 어긋남 0, layered 9,367줄. 메인 교차 확인 fixcheck 33/33·allowcheck(허용 3파일, 삭제 줄 forEachIsolated 1줄) → 커밋 `c9e8bc1`. 병렬: 전체 회귀(layered test·contract2·contract3 + equiv all, scratchpad l7/reg) ∥ 묶음 C 듀얼 1패스(미러 l7/cmirror, diff 559087c..c9e8bc1). god는 이 브랜치에서 무변경(main 95cc1da 결과 그대로) | 대기 |
| 2026-10-29 23:14 | 묶음 C codex 회수(try1, bwrap 1이나 렌즈 6개 판정 완료): 지적 없음 — 9-1 잠금 순서·시세 KST 반열린·EXISTS·바인드·정렬, 관리자 키셋·LIMIT, 슬롯 ON CONFLICT, 예약 주문→슬롯 FU, 리팩터 3건 본문 동일 | Opus 대기 |
| 2026-10-29 23:17 | 묶음 C Opus 회수: blocker 0·major 0·minor 2·정보 3, god 대조 표 약 45행 모두 같음. m1 = 엔티티 열 집합·응답 DTO 필드 순서·컨트롤러 파싱 순서를 대조하지 못함(리뷰어가 god entity/·web/이 미러에 없다고 보고 — 메인 확인 결과 미러에 있음(web 17파일) → 리뷰어 누락). m2·i1~i3은 SQL·응답 영향 없음(clock 읽는 시점, 영속화 안 한 응답 객체, DTO 파일 위치) | LC-1 누적 루프로 |
| 2026-10-29 23:18 | 누적 듀얼 루프 1회차 시작(diff 95cc1da..c9e8bc1, 57파일 +4,612/−1, 미러 l7/cmirror + l7/rz1): Opus lz1-entity(엔티티·JPA — LC-1의 Hibernate 층) ∥ Opus lz1-web(컨트롤러·DTO·400 판정 — LC-1의 응답 층) ∥ codex(묶음 사이 일관성·잠금 그래프·허용 목록·배치 14개·표본 매핑) | 대기 |
| 2026-10-29 23:21 | 누적 1회차 lz1-entity 회수: 지적 없음 — 새 엔티티 19개의 테이블·엔티티 이름·프로퍼티 이름·타입·nullable·@Id 전략 1:1 같음, SQL 모양 바꾸는 주석 양쪽 다 없음, JPQL 문자열 글자 단위 같음. 유일한 차이 = 본문 `private set` 선언 순서(1·2단계 엔티티도 같은 패턴으로 equiv 12 통과) → 영향 없음 판정. 서비스 쪽 persist·flush 순서는 렌즈 밖(묶음별 리뷰에서 대조함) | web·codex 대기 |
| 2026-10-29 23:23 | 누적 1회차 codex 회수(bwrap 0): 지적 없음 — 묶음 사이 규칙·잠금 순서 역전 없음, 새 배치 11개 이름·격리·집계 god와 일치, 기존 파일 변경 허용 3파일·BatchTarget 기존 값 순서 유지, 엔티티·DTO 표본 4종 필드 차이 없음. 동적 판정(equiv·교착 실행)은 정적 리뷰 밖 | web 대기 |
| 2026-10-29 23:23 | 누적 1회차 lz1-web 회수: 지적 없음 — 단계 A DTO 34/34 필드 이름·순서·타입 같음(스크립트 대조), @JsonUnwrapped 양쪽, 파싱·직렬화 도우미·ErrorHandling·401 메시지 같음, 엔드포인트 전부 판정 순서·400/404/403/409 메시지 글자 같음, 배치 이름 11개 같음. → LC-1 해소. **누적 1회차 신규 지적 0 → 루프 종료**(review.md 신규성 기준) | 회귀 대기 |
| 2026-10-29 23:38 | 전체 회귀(c9e8bc1, 메인 실행 l7/reg): layered test rc 0(infra 28·contract 122), contract2 89/89, contract3 168/168(실패 0), verify 120 ×3, equiv all rc 0 같음 12/12. god는 이 브랜치 무변경(95cc1da의 god 168·239 그대로). §4 안전선: ①스모크=contract3 실행 ②테스트+불변식(fixcheck 33/33·allowcheck) ③diff self-review(누적 57파일 — 기존 파일은 허용 3파일만) ④rollback=브랜치 미병합·새 파일만이라 revert 쉬움 ⑤public contract=새 엔드포인트만(기존 21 무변경, equiv 12) ⑥반증: 동시 경로(잠금 그래프 누적 리뷰 지적 0)·빈 데이터(시드 shipment_delays 0 — 테스트가 직접 만듦)·권한 없는 사용자(403 경로 contract3) | 완료 요약 |
| 2026-10-29 23:55 | 사용자 결정 '병합·push 후 다음으로' → main fast-forward 95cc1da..6a62f81 | push |

## 리뷰 ledger

| ID | 출처 | 지적 | 판정 | 처리 |
|---|---|---|---|---|
| LA-1 | codex major·opus minor | ReviewService: `Review.write(item, reviews.countOfItem(itemId), …)` — count가 상태 검사 전에 실행(god Marketplace.kt:1860·1861은 상태 뒤) → 상태 409 경로 SQL 차이(equiv) | 채택 | fixed — existingReviews 지연 람다(contract3 A 클래스 통과 확인) |
| LB-1 | opus minor | textLength가 domain/Review.kt:14·Tracking.kt:14에 file-private 사본 → C에서 더 늘 위험(§19의 6) | 채택 | fixed c9e8bc1 — domain/StageAText.kt internal |
| LB-2 | opus minor | instantOrNull이 api/TrackingController.kt:192 private — A12 slotStart에도 필요, api/Json.kt는 허용 목록 밖 | 채택 | fixed c9e8bc1 — api/StageAParse.kt internal(A11·A12 공용) |
| LB-3 | opus minor | DelaySql이 infra/ReminderSql.kt 안에 있음(이름 불일치) | 채택 | fixed c9e8bc1 — infra/DelaySql.kt |
| LC-1 | opus minor | 엔티티 열 집합·응답 DTO 필드 순서(@JsonUnwrapped 등)·컨트롤러 파싱 순서(400 메시지)가 god entity/·web/과 같은지 미대조 — equiv 응답·Hibernate 층 차이 후보 | 채택(미검증 영역) | 해소 — 누적 1회차 lz1-entity·lz1-web 대조 결과 차이 0(동적 확인은 다음 작업 equiv) |
| LC-2 | opus minor·정보 | InboundService clock.now() 검증 전 읽기, 영속화 안 한 슬롯 응답 객체, DTO 파일 위치 | 기각(SQL·응답 동일, 메모리 시계) | - |

## 생략한 검증

(없음)

## 완료 요약

### 결과

spec §1의 다섯 항목 가운데 1~4는 충족, 5는 정적 대조까지 충족(동적 판정은 다음 작업).
- ① `contract3Test` **168/168**(실패 0·오류 0).
- ② 기존 layered `test` = infraTest **28** + contractTest **122**, `contract2Test` **89** 그대로, 세 실행 모두 verify **120** 통과. god는 이 브랜치에서 무변경(`git diff 95cc1da c9e8bc1`의 `layered/src/main/` 밖 변경 0파일).
- ③ equiv `all` **12/12 같음**.
- ④ 구조: 바뀐 57파일 = 새 파일 54 + 허용 목록 기존 파일 3(BatchService·BatchTargets·KeysetBatchTargets). allowcheck rc 0, 지운 줄은 `forEachIsolated` private 한 줄. `Failure`·`statusOf` 무변경. fixcheck **33/33**.
- ⑤ SQL·잠금 동치: 묶음별 god 대조 표(A 26행·B 18행·C 약 45행)와 누적 1회차(엔티티 19개 1:1, DTO 34/34)에서 차이 0 — 발견된 차이 1건(LA-1)은 수정. 새 시나리오의 동적 판정은 남음.

layered 4,756 → **9,367줄**(+4,611), diff 57파일 +4,612/−1.

### 바뀐 것

| 묶음 | 기능 | 새 파일 | 커밋 |
|---|---|---|---|
| A | 리뷰·평점, 판매자 등급·리베이트, 지급 계좌·월간 정산서, 세금계산서, 감사 로그(A1·A2·A3·A7). contract3 8개 클래스 57/57 | 17 | `0100ca6` (+1,623/−1) |
| A 수정 | LA-1: 리뷰 작성 시 기존 리뷰 수를 상태 검사 뒤에 센다 | 0 | `7289d69` (+3/−3) |
| B | 알림(deadline-reminder 6종), 위시리스트·가격 알림, 배송 추적·지연(A4·A5·A11). 7개 클래스 55/55 | 12 | `559087c` (+1,133) |
| C | 문의·시세 통계·관리자 조회·판매자 프로필·입고 예약(A6·A8·A9·A10·A12) + LB-1~3 공용 도우미 정리. 전체 168/168 | 25 | `c9e8bc1` (+1,898/−42) |

새 배치 11개(rating-recalc, seller-grade-recalc, rebate-settlement, monthly-statement, tax-invoice-issue, deadline-reminder, price-alert-scan, delivery-delay-scan, inquiry-auto-close, price-stats, inbound-no-show), `BatchTarget` 값 +17. `c9e8bc1`의 −42는 모두 단계 A 새 파일 정리(기존 파일 줄 삭제 없음).

### 핵심 diff

**① 허용 목록 기존 파일 변경** (`git diff 95cc1da c9e8bc1`)

`service/BatchService.kt`:
```diff
-private fun BatchTargets.forEachIsolated(batch: String, target: BatchTarget, now: Instant, handle: (Long) -> Boolean): BatchResult {
+internal fun BatchTargets.forEachIsolated(batch: String, target: BatchTarget, now: Instant, handle: (Long) -> Boolean): BatchResult {
```
```diff
             "weekly-settlement" -> BatchResult(weeklySettlement.run(now), failed = 0)
+            "rating-recalc" -> ratingRecalc.run(now)
+            "seller-grade-recalc" -> sellerGradeRecalc.run(now)
+            "rebate-settlement" -> rebateSettlement.run(now)
+            "monthly-statement" -> monthlyStatement.run(now)
+            "tax-invoice-issue" -> taxInvoiceIssue.run(now)
+            "deadline-reminder" -> deadlineReminder.run(now)
+            "price-alert-scan" -> priceAlertScan.run(now)
+            "delivery-delay-scan" -> deliveryDelayScan.run(now)
+            "inquiry-auto-close" -> inquiryAutoClose.run(now)
+            "price-stats" -> priceStats.run(now)
+            "inbound-no-show" -> inboundNoShow.run(now)
             else -> invalidRequest("알 수 없는 배치: $name")
```

`infra/KeysetBatchTargets.kt`(일부):
```diff
         BatchTarget.GRADE_CHANGES -> Spec("users", "$noGradeHistoryAtD AND t.grade <> $gradeSql") { gradeParams(GradePeriod.of(it)) }
+        BatchTarget.REVIEWED_SELLERS -> Spec("users", "EXISTS (SELECT 1 FROM reviews r WHERE r.seller_id = t.id)") { emptyMap() }
...
+        BatchTarget.AUTO_CLOSABLE_INQUIRIES -> Spec("inquiries", "t.status = 'ANSWERED' AND t.answered_at <= :due") {
+            mapOf("due" to Inquiry.autoCloseCutoff(it))
+        }
+        BatchTarget.PRICE_STATS_SIZES -> Spec(
+            "product_sizes",
+            "EXISTS (SELECT 1 FROM trades tr WHERE tr.product_size_id = t.id AND tr.status = 'PAID'" +
+                " AND tr.matched_at >= :from AND tr.matched_at < :to)" +
+                " AND NOT EXISTS (SELECT 1 FROM price_stats_daily p WHERE p.product_size_id = t.id AND p.stat_date = :d)",
+        ) { PriceStatsDay.of(it).let { day -> mapOf("from" to day.from, "to" to day.to, "d" to day.date) } }
```

**② LA-1 수정** (`git show 7289d69`)
```diff
-        fun write(item: OrderItem, existingReviews: Long, buyerId: Long, input: ReviewInput, now: Instant, sellerId: () -> Long): Review {
+        fun write(item: OrderItem, existingReviews: () -> Long, buyerId: Long, input: ReviewInput, now: Instant, sellerId: () -> Long): Review {
             if (item.status != "CONFIRMED") {
                 throw MarketException(Failure.ITEM_STATE_CONFLICT, "CONFIRMED가 아닌 주문 항목: ${item.id} (${item.status})")
             }
-            if (existingReviews > 0) throw MarketException(Failure.ITEM_STATE_CONFLICT, "리뷰가 이미 있는 주문 항목: ${item.id}")
+            if (existingReviews() > 0) throw MarketException(Failure.ITEM_STATE_CONFLICT, "리뷰가 이미 있는 주문 항목: ${item.id}")
```
```diff
-            val review = Review.write(item, reviews.countOfItem(itemId), userId, input, now) { trades.find(item.tradeId)!!.sellerId }
+            val review = Review.write(item, { reviews.countOfItem(itemId) }, userId, input, now) { trades.find(item.tradeId)!!.sellerId }
```
수정 전에는 count가 인자로 먼저 평가되어 상태 검사(409)보다 앞서 나갔다(god는 `requireItemStatus` 뒤 — `Marketplace.kt:1857-1861`).

**③ §19 9-1 잠금 순서** — layered `infra/JpaInquiryRepositories.kt`:
```kotlin
    override fun keyShareItem(orderItemId: Long): Boolean {
        val orderId = em.createNativeQuery("SELECT order_id FROM order_items WHERE id = :id").setParameter("id", orderItemId)
            .resultList.firstOrNull() ?: return false
        em.createNativeQuery("SELECT id FROM orders WHERE id = :id FOR KEY SHARE").setParameter("id", orderId).resultList
        em.createNativeQuery("SELECT id FROM order_items WHERE id = :id FOR KEY SHARE").setParameter("id", orderItemId).resultList
        return true
    }
```
god `Marketplace.kt:2720-2723`:
```kotlin
                val orderId = em.createNativeQuery("SELECT order_id FROM order_items WHERE id = :id").setParameter("id", orderItemId)
                    .resultList.firstOrNull() ?: notFound("없는 주문 항목: $orderItemId")
                em.createNativeQuery("SELECT id FROM orders WHERE id = :id FOR KEY SHARE").setParameter("id", orderId).resultList
                em.createNativeQuery("SELECT id FROM order_items WHERE id = :id FOR KEY SHARE").setParameter("id", orderItemId).resultList
```

### 리뷰

묶음 1패스 ×3: 채택 5(LA-1, LB-1~3, LC-1)·기각 1(LC-2) — ledger 참고. 누적 듀얼 루프 1회차(Opus 엔티티 ∥ Opus 웹 ∥ codex 묶음 사이) 신규 지적 0 → 종료. god 작업과 달리 교착류 결함 0 — god에서 고친 잠금 순서·사전 KEY SHARE 문장을 브리핑으로 넘겨 그대로 옮겼다.

### 남은 것·한계

- equiv 새 시나리오 미작성(다음 작업, 판정기 연결과 함께) — 지금의 12/12는 기존 경로 SQL 무변경만 보인다. 우선 확인처: price-alert TRIGGERED의 INSERT → UPDATE 순서, ReminderSql dedupe 식, 엔티티 본문 `private set` 선언 순서(1·2단계 같은 패턴이 equiv 통과 — 영향 없음 판정, 동적 미확인).
- 교착(잠금 그래프)은 정적 리뷰만 — 동시 실행 재현 없음(contract3 동시성 테스트는 통과).
- god 5,228줄 < 5,500(ADR-0006 §6.1의 7 크기 확인 단계).
- CS 이슈 아카이브: study-note 쓰기 금지(spec §4) → 보류(NEXT 보류 항목과 같은 경로).
