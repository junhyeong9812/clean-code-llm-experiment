# log — 레이어드 분리 2단계 (layered)

> 작업 폴더: `docs/plans/2026-10-22/layered-stage2/` · 기준: main `a0228d8`

## 타임라인

| 시각 | 사건 | 결과 |
|------|------|------|
| 2026-10-22 | 착수 — 사용자 "지금 하던 프로젝트 이어서 진행하자" → NEXT N1(layered 2단계) | 작업 폴더 생성, 인터뷰 시작 |
| 2026-10-22 | 인터뷰 6문항 응답(구조 1단계와 동일·동치 SQL/id까지·equiv 2단계 별도·스모크 2건 재사용·stakes 동일), 명세 합의(auto) | SPEC=1·MODE=auto, 브랜치 feat/layered-stage2 |
| 2026-10-22 | 설계 선검증 판정 | 신설 동작 불변식 없음 — 잠금 순서·주 단위 트랜잭션의 기존 강제 위치: god Marketplace.kt `lockOrderOf`(822-828)·`refundItem`(1001-1003)·`settleWeeks`/`settleWeek`(1575-1605). 잠금·정산 task는 계획 대조만 |
| 2026-10-22 | task 01(§8.1, equiv all exit 0 포함) Opus 워커 착수 | 기준 579e248. 디스크 여유 ~9.8G(앞서 100% — docker 빌드 캐시 5.1G 정리·study-note 워크트리 6개 제거) |
| 2026-10-22 14:12 | task 01 회수 — f4d925b(4파일 +43/−10) | contract2 3/82(적립률 3), 1단계 exit 0, **equiv all exit 0 — 7개 시나리오 4계층 전부 같음(메인 확인: 14:00~14:11 실행분 diff 파일 0)** → 가정 1 실증. domain의 spring·infra import 0 |
| 2026-10-22 14:13 | 메인 판정: 등급 선택 3단 규칙이 infra 네이티브 쿼리에 있음(워커 의문) | 수용 — 'god와 같은 SQL 문장' 합의 조건이 위치를 강제, 1단계도 조회 기준(배치 대상 등)은 infra 쿼리. 날짜(KST)·적립률 매핑은 domain. 리뷰 판단 대상으로 남김 |
| 2026-10-22 | task 02 Opus 워커 착수 | god 최종 코드(HEAD, 항목 FOR UPDATE 포함) 기준으로 이식 — god task 02→03의 lockOrderOf 수정 이력을 되풀이하지 않게 |
| 2026-10-22 14:23 | task 02 회수 — d7c01fa | contract2 8/82(god task 02와 같은 8개, 메인 XML 확인), 1단계 exit 0, build 통과, 계층 import 위반 0. 검수 FAIL 임시 400 UNEXPECTED_ERROR(god dc106a4와 같은 위치) |
| 2026-10-22 14:24 | 메인 판정: 2단계 경로의 SQL 동치는 실행으로 확인할 수단이 없음 | 명세 §5의 equiv는 1단계 7개 시나리오뿐(2단계 시나리오는 사용자 결정으로 별도 작업). 2단계 SQL 순서 동치는 리뷰(god 코드 대조)로만 판정하고, 별도 equiv 2단계 작업에서 실측 — 워커 의문(검수 PASS의 INSERT/UPDATE 순서 추론)도 여기에 포함. 마감 때 사용자에게 한계로 보고 |
| 2026-10-22 | task 03 Opus 워커 착수 — 1단계 계획(계층 배치·문장 순서 대조) | 기준 d7c01fa |
| 2026-10-22 14:30 | task 03 계획 회수 | 근거: Hibernate 6.6.26 sources — 네이티브 쿼리는 전체 flush, JPQL은 쿼리 공간이 겹칠 때만, UPDATE 순서 = 영속성 컨텍스트 진입 순서(order_updates false), IDENTITY persist 즉시 INSERT. 동치 조건 3개(호출 순서·PC 진입 순서·flush 지점 대비 변경 시점). 위험 지점 = 잠금 네이티브 쿼리 앞 도메인 변경 → 전이를 '확인·계산 → 잠금·PG → 변경'으로 분리. refundItem F1~F11·경로별 순서 대조표 |
| 2026-10-22 14:32 | 메인 판정: 승인 | 추가 check(1단계 재합의 2 범위)·판매자 잠금 분기 service·체결가<10 domain check 없음·inspect 3분할·DisputeDecision.of 승인, Refund 파일 이동은 churn이라 금지. 구현 뒤 경로별 '변경이 잠금/네이티브 쿼리 뒤' self-review 요구 |
| 2026-10-22 14:55 | task 03 회수 — 7f07849 | contract2 49/82(2회 연속, god task 03과 같은 분포), 1단계 exit 0, equiv all 7개 같음(메인: diff 파일 0 확인), build·import grep 통과. 추가 check 목록·경로별 '변경이 잠금/네이티브 쿼리 뒤' self-review 전 경로 통과 기록. PG 실패 로그 문자열은 god와 다름(로그만) |
| 2026-10-22 | task 04(배치 4종) Opus 워커 착수 | 기준 7f07849 |
| 2026-10-22 15:17 | task 04 회수 — eeec991(9파일 +276/−8) | contract2 69/82(god task 04와 같은 분포, 메인 XML 확인), 1단계 exit 0, equiv all 7개 같음(diff 0, 15:05~15:16), build·import 통과. 배치 최장 layered/god: point-expiry 26.5s/26.7s·grade-recalc 4.2s/4.1s, processed·호출 수 같음. point-expiry의 remaining 변경 시점이 god와 다름(배분 persist 직전/직후) — IDENTITY persist가 flush하지 않으므로 같은 문장이라는 추론, 2단계 SQL 실측 한계에 포함 |
| 2026-10-22 | task 05(주간 정산) Opus 워커 착수 — 1단계 계획 | 기준 eeec991 |
| 2026-10-22 15:20 | task 05 계획 회수·승인 | 문장 대조 A~H(커밋 UPDATE 순서 = 패널티 JPQL의 p.id 진입 순 — 그 JPQL이 유일한 진입 경로여야 함, 도메인 변경은 음수 이월 네이티브 뒤). 선택: 합계 layered 관례(addExact — 넘침에서만 차이, 재합의 2 범위), 분류 REFUNDED_AFTER_CONFIRM 명시 + check, 자리표 0 미사용, SellerStatement domain, BatchService.kt 같은 파일 |
| 2026-10-22 15:44 | task 05 회수 — 6500ee0 | contract2 82/82 두 번 연속(메인 XML 확인), 1단계 exit 0, equiv all 7개 같음(15:31~15:43, diff 0), build·import 통과. 2회차 배치 호출 62건의 (현재 시각·processed) 순서열이 god 로그(12:22)와 한 줄도 다르지 않음(2단계 동치의 간접 증거) |
| 2026-10-22 15:45 | 측정 정정 | weekly-settlement 최장은 테스트 순서에 따라 다름: processed 5,029 순서에서 layered 17.7s / god 18.3s(god 12:22 실행). god 2단계 log·NEXT의 '최장 2,416ms'는 한 순서의 값이었음 → 마감 때 NEXT 수치 정정(120s 제한 안은 그대로) |
| 2026-10-22 15:47 | task 06 착수 — 백그라운드(god 2단계·1단계 회귀 → 스모크 2건 god·layered → build) · 리뷰 루프 1 packet BASE 579e248, OUT=scratchpad/rv.Td5Y, MIRROR=scratchpad/mirror.3OE2 | 누적 32파일 +1952/−28, untracked 0, 보안 스캔 0 |
| 2026-10-22 | codex 루프 1 회수(try 1 정상) | findings 0, OQ 1(분쟁 제기·검수 PASS의 '항목 변경 → persist' 순서가 god와 반대 — IDENTITY 즉시 INSERT·flush 없음 전제면 같은 SQL, 실증 못 함). 렌즈 8 applicable·확인 |
| 2026-10-22 | Opus 루프 1 회수 → 메인 종합 → codex 감사 착수 | Opus findings 2(F1 등급 service 직접 대입·F2 환불 판정 분기 3개 service — 둘 다 동작·SQL은 god와 같고 spec §2 규칙 위치 위반) + OQ 4. 종합: R1-1·R1-2 채택(구조가 실험 대상·착수 전 명시 조항·SQL 불변 수정), R1-3 실측 이월(equiv 2단계), R1-4 도달 불가·R1-5 조항 무관·R1-6 승인된 허용 차이 기각 |
| 2026-10-22 | codex 감사 회수(try 1) | ①R1-2 일부 과잉 — card>0·point>0은 domain 계산값에 따른 호출 조율, 위반은 :60 '분쟁이면 회수/아니면 예정 취소' 선택뿐 → 범위 축소 ②R1-4 사유 오류 — SELLER의 INSPECTING은 검수 FAIL 경로에 필요, shipment-deadline은 service가 PAID만 넘겨 도달 불가(기각 유지·사유 정정) ③R1-6 근거 없음 — spec이 허용한 차이는 상태 전이 check뿐, addExact 차이는 메인이 task 05 계획에서 임의로 '재합의 2 범위'로 승인한 것(사용자 승인 없음) → 메인 판정 오류 인정, god와 같은 일반 연산으로 되돌려 차이 제거(채택) ④R1-1 사유 정정 — 값·변경 여부는 GradeReview가 정하고 남는 쟁점은 service의 상태 전이 직접 수행, grade 선언은 User.kt:24 |
| 2026-10-22 16:13 | 루프 1 수정 회수 — bc8bcfb(5파일 +21/−11) | R1-1 User.regrade + grade private set, R1-2 RefundFault.revokesEarn, R1-6 sumOf·일반 뺄셈(god :1639-1643과 같은 식). contract2 82/82, 1단계 exit 0, equiv all 7개 같음(메인 diff 0 확인), build·import 통과 |
| 2026-10-22 16:30 | Opus 루프 2 회수 | findings 0. 2단계 전 경로를 god와 줄 단위 대조(SQL 문자열 글자 단위·PC 진입 순서·flush 시점·INSERT 순서·판정 순서·오류 code/message 같음). god와 코드 순서가 다른 곳 3곳(검수 PASS·분쟁 제기·회수/소멸 배분)은 Hibernate 규칙상 같은 문장. OQ: ①spec §2 '등급·정산 금액은 domain'과 'SQL 문장 동치' 충돌(등급 선택·경계·정산 대상 조건·수수료식이 infra SQL) — codex OQ와 같음, 사용자 판단 ②환불 사유 문자열이 service ③2단계 SQL 실측(=R1-3) |
| 2026-10-22 16:31 | **리뷰 종료(루프 2)** | 종료식: open(채택·미수정) 0 · 루프 2 신규 채택 0 · 대칭 부담 — 렌즈 8개 전부 applicable, opus·codex 양쪽 file:line 근거로 verified(비대칭 아님). OQ ①은 사용자 결정 대기, ②는 ①과 함께 질문 |

| 2026-10-22 18:48 | 최종 검증(t6b) | 8c2afd4 기준 layered contract2 82/82·1단계 rc 0(infra 28·contract 117·verify 0)·equiv all rc 0(7개 같음, 18:32~18:47). 마감 문서 — 완료 요약(Opus 워커 초안, 자리표만 메인이 채움), NEXT·README·measurement-log |
## 리뷰 ledger

| id | first_seen_loop | source | 근거 | disposition | status | fixed_in_loop |
|----|-----------------|--------|------|-------------|--------|---------------|
| R1-1 | 1 | opus(+감사 사유 정정) | BatchService.kt:280 service가 users.grade 직접 대입(User.kt:24 public var) — spec §2 상태 전이는 domain | 채택 | fixed | 1 |
| R1-2 | 1 | opus(감사로 축소) | ItemRefunder.kt:60 분쟁이면 회수/아니면 적립 예정 취소 선택이 service — spec §2 '변경 여부는 domain' (card>0·point>0 분기는 조율이라 제외) | 채택 | fixed | 1 |
| R1-3 | 1 | opus+codex | 검수 PASS(FulfillmentService.kt:81-87)·분쟁 제기(BuyerItemService.kt:74-79)의 '항목 변경 → persist' 순서가 god(:784-799, :903-915)와 반대 — Hibernate 규칙상 같은 문장으로 판단, 실측 수단 없음 | open question | user-deferred(NEXT equiv 2단계 시나리오에 검수 PASS·분쟁 제기·포인트 소멸 포함) | — |
| R1-4 | 1 | opus(감사 사유 정정) | RefundAmounts.kt:11-12 SELLER {PAID, INSPECTING} 경로 무구분 — INSPECTING은 검수 FAIL에 필요, shipment-deadline은 service가 PAID만 전달 | 기각(도달 불가) | — | — |
| R1-5 | 1 | opus | snapshotTx 생성 3곳 중복 | 기각(spec·ADR 조항 무관) | — | — |
| R1-6 | 1 | opus+감사 | Settlement.kt:78-81 addExact vs god :1639-1643 sumOf — spec 허용 차이(check) 밖, 메인이 임의 승인 | 채택(메인 판정 오류 정정) | fixed | 1 |
| 2026-10-22 15:54 | 회귀·스모크(t6) | god contract2 82/82·1단계 rc 0(infra 28·contract 117·verify 0) → god 무변경. 스모크 ① god·layered 모두 EARN 2,000(GOLD, VIP면 3,000). 스모크 ② god·layered 정산 digest 동일(10-08: 6bc83e…, 10-19 뒤: 981b80…), [A] 195/0/31/0·[B] 195·[C] 193 동일, paid_at 불일치 0, ERROR 0. build rc 0 |
| 2026-10-22 16:14 | 리뷰 루프 2 packet — OUT=scratchpad/rv.ZKqo, MIRROR=scratchpad/mirror.Jp3q(루프 1 미러 삭제) | 누적 32파일 +1963/−29, 보안 스캔 0. Opus ∥ codex |
| 2026-10-22 | codex 루프 2 회수(try 1) | findings 0, OQ 1(등급 CASE·수수료식이 infra SQL — task 01 14:13 판정과 같은 사항: 'god와 같은 SQL 문장' 조건이 위치를 강제, 마감 보고에 구조상 허용으로 명시), 렌즈 8 적용·확인 |
| R2-OQ1 | 2 | opus+codex | infra/JpaUserRepository.kt:16-21·42-56(등급 경계·선택), JpaSettlementRepository.kt:20-25·38(정산 대상·수수료) — spec §2 두 조항 충돌 | open question | 사용자 결정 대기 | — |
| R2-OQ2 | 2 | opus | BuyerItemService.kt:68·113, BatchService.kt:202 환불 사유 문자열이 service | open question | 사용자 결정 대기(R2-OQ1과 함께) | — |

- codex 루프 1: findings 0(try 1). 루프 2: codex findings 0(try 1), Opus findings 0.
| 2026-10-22 16:33 | 사용자 결정(재합의 1) | R2-OQ1·OQ2: "SQL 동치 우선, 예외로 기록" → spec §2 재합의 1 기록(SQL 안 조건·계산은 infra 예외), 고정 환불 사유 문구는 domain으로 이동(작은 수정 → 타깃 재점검) |
| 2026-10-22 18:33 | 환불 사유 상수화 — 메인 직접 수정 8c2afd4(RefundAmounts.kt +5, BuyerItemService·BatchService 리터럴 → RefundFault 상수, import 1) | :layered:build rc 0. 최종 검증(contract2·1단계·equiv all) + codex 타깃 재점검 백그라운드 착수 |
| 2026-10-22 | codex 타깃 재점검 회수(try1 bwrap, try2 정상) | 신규 finding 0 — 사유 문구 god와 글자 단위 같음, 의존 방향 위반 0, 검수 FAIL 경로 무영향 |

## 완료 요약

### 결과

`layered`에 god 2단계(ADR-0004 + §8.1 적립률)를 계층 분리해 구현했다. `run.sh layered contract2Test` 82/82(2회 연속 green, skip 0). 1단계 `run.sh layered`는 exit 0(infra·contract 117·verify 위반 0). `equiv all`은 task 01·03·04·05와 루프 1 수정(bc8bcfb) 뒤에 매번 7개 시나리오 4계층이 모두 같았다(메인이 diff 파일 0 확인). god는 `god contract2Test` 82/82, 1단계 rc 0(infra 28·contract 117·verify 0)이라 무변경. god 2단계 수동 스모크 2건을 layered에서 다시 돌려 god와 같은 결과를 얻었다. ①기동 전환 적립률: 양쪽 EARN 2,000(GOLD 2%, VIP면 3,000) ②정산 0건 분기: 정산 digest가 같고(10-08: 6bc83e…, 10-19 뒤: 981b80…) [A] 195/0/31/0·[B] 195·[C] 193, paid_at 불일치 0, ERROR 0. build rc 0, 계층 의존 방향 grep 위반 0. 듀얼 리뷰는 루프 2에서 끝났다(open 채택 0, 루프 2 신규 채택 0, 렌즈 8개 양쪽 verified). 루프 2의 open question 2건은 사용자 재합의 1로 정리했다. 그 결정에 따른 수정(8c2afd4) 뒤 codex 타깃 재점검의 신규 finding은 0이었다. 최종 검증: 최종 코드(8c2afd4) 기준 18:32~18:47 재실행: contract2 82/82, 1단계 infra 28·contract 117·verify 0, equiv all 7개 같음.

### task별

| task | 커밋 | 파일 +/− | contract2 누적 green | 핵심 |
|---|---|---|---|---|
| 01 | f4d925b | 4파일 +43/−10 | 3/82 | §8.1 적립률 등급 = 주문 생성일(KST) 등급 이력. 등급 선택은 infra 네이티브 SQL 1문장(god와 같은 문자열), 날짜·적립률 매핑은 domain. equiv all 같음 → 가정 1 실증 |
| 02 | d7c01fa | 11파일 +393/−6 | 8/82 | 2단계 엔티티, `GET /order-items/{id}`, 발송·입고·검수 PASS·배송 완료. `ItemLocker`(주문 → 항목 FOR UPDATE)는 god 최종 코드 기준으로 이식했다 |
| 03 | 7f07849 | 21파일 +852/−18 | 49/82 | 확정·취소·분쟁·판정, 판매자 귀책·패널티, 환불·카드 취소·포인트 복구·회수. 계획 먼저 세움: 전이를 '확인·계산 → 잠금·PG → 변경'으로 나눴다 |
| 04 | eeec991 | 9파일 +276/−8 | 69/82 | 배치 shipment-deadline·auto-confirm·point-expiry·grade-recalc(최장 layered/god: point-expiry 26.5s/26.7s, grade-recalc 4.2s/4.1s) |
| 05 | 6500ee0 | 8파일 +405/−3 | 82/82 | weekly-settlement(한 주 = 트랜잭션 1개)·`GET /me/settlements`. 계획 먼저 세움(문장 대조 A~H). 2회차 배치 62건의 (시각·processed) 순서열이 god 로그와 같음 |
| 리뷰 수정 | bc8bcfb | 5파일 +21/−11 | 82/82 | R1-1 `User.regrade`(grade private set), R1-2 `RefundFault.revokesEarn`, R1-6 정산 합계 `addExact` → god와 같은 `sumOf`·일반 뺄셈 |
| 사유 상수 | 8c2afd4 | 3파일 +9/−3 | 82/82 | 재합의 1: 고정 환불 사유 문구 3개를 `RefundFault` 상수로(domain) |

- 파일 +/−는 `git show --stat` 기준이다. 누적 diff `579e248..HEAD -- layered/`는 32파일 +1,969/−29다(타임라인의 루프 2 packet 값 +1,963/−29는 8c2afd4 전의 값).

### 핵심 diff (실파일 발췌)

①적립률 등급. `layered/.../service/PaymentOutcomes.kt:32`(f4d925b). 등급 조회는 `infra/JpaUserRepository.kt:42-56` `gradeOn`(HEAD — f4d925b 당시 :21-35)이고, SQL 문자열은 god `gradeOnOrderDate`(Marketplace.kt:620-634)와 같다.

```kotlin
         users.lock(order.buyerId)
         order.approve(paidAt)
 
-        val rate = users.find(order.buyerId)!!.earnRatePermille()
+        val rate = User.earnRatePermille(users.gradeOn(order.buyerId, order.gradeDate()))
```

②환불 순서: 금액 계산 → PG → 사용자 잠금 → 변경. `layered/.../service/ItemRefunder.kt:45-63`(HEAD). 변경은 잠금 네이티브 쿼리(실행 전 flush) 뒤에만 한다. god 대응 위치는 `Marketplace.kt:1001-1003` `refundItem`:

```kotlin
    fun refund(item: OrderItem, fault: RefundFault, reason: String, now: Instant) {
        val order = orders.find(item.orderId)!!
        val amounts = RefundAmounts.of(order, items.listOf(item.orderId), item, fault) { refunds.shippingRefunded(item.orderId) }

        val pgTxId = if (amounts.card > 0) pgCaller.cancel(item.id!!, order.idempotencyKey, amounts.card) else null

        if (fault == RefundFault.SELLER) {
            users.lockInIdOrder(order.buyerId, trades.find(item.tradeId)!!.sellerId)
        } else {
            users.lock(order.buyerId)
        }
        item.markRefunded(fault, now)
        refunds.add(amounts.refund(item, reason, now))
        if (pgTxId != null) pgTransactions.add(PgTransaction.cancellation(order.id!!, item.id!!, amounts.card, pgTxId, now))
        // 회수(§8.4)가 복구(RESTORE)보다 먼저다
        if (fault.revokesEarn) pointBook.revokeEarn(item, now) else pointBook.cancelPendingEarn(item, now)
        if (amounts.point > 0) pointBook.restore(order, item, amounts.point, now)
        order.recordItemRefund(amounts.last)
    }
```

```kotlin
        val pgTxId = if (card > 0) cancelCard(order, item.id!!, card) else null

        if (fault == "SELLER") lockUsers(order.buyerId, em.find(Trade::class.java, item.tradeId).sellerId) else lockUser(order.buyerId)
```

③주간 정산: 잠금 → 재평가 조회 → 도메인 계산 → INSERT·패널티 연결. `layered/.../service/BatchService.kt:314-328` `WeeklySettlementBatch.settle`(HEAD). 엔티티 변경은 마지막 네이티브 조회(`negativeCarries`) 뒤에만 한다:

```kotlin
    private fun settle(week: SettlementWeek): Int {
        settlements.lockTargets(week.cutoff)
        val targets = settlements.targets(week.cutoff)
        val unsettled = penalties.unsettledBefore(week.cutoff)
        val carries = settlements.negativeCarries()
        val statements = SellerStatement.of(targets, unsettled, carries)
        for (statement in statements) {
            val settlement = statement.settlement(week)
            settlements.add(settlement)
            statement.items(settlement).forEach { settlements.addItem(it) }
            statement.attachPenalties(settlement)
        }
        return statements.size
    }
```

④같은 네이티브 SQL: 정산 대상 잠금. 대상 조건 문자열(god `settlementTargetSql` :1563-1568 = layered `targetCondition` :20-25)도 글자 단위로 같다.

god `god/.../Marketplace.kt:1601-1602`(`settleWeek`):

```kotlin
        em.createNativeQuery("SELECT i.id FROM order_items i WHERE $settlementTargetSql ORDER BY i.id FOR UPDATE")
            .setParameter("cutoff", cutoff).resultList
```

layered `layered/.../infra/JpaSettlementRepository.kt:30-33`(`lockTargets`):

```kotlin
    override fun lockTargets(cutoff: Instant) {
        em.createNativeQuery("SELECT i.id FROM order_items i WHERE $targetCondition ORDER BY i.id FOR UPDATE")
            .setParameter("cutoff", cutoff).resultList
    }
```

### 계층 배치 (god 함수 → layered)

| god (Marketplace.kt) | service (순서·잠금·PG 조율) | domain (값·전이 판정) | infra (SQL) |
|---|---|---|---|
| `gradeOnOrderDate`·`approveOrder` | `PaymentOutcomes` | `Order.gradeDate`·`User.earnRatePermille` | `JpaUserRepository.gradeOn`(등급 선택 SQL) |
| `lockOrderOf` | `ItemLocker.lock` | — | 주문·항목 FOR UPDATE |
| `shipItem`~`deliverItem`·`getOrderItem` | `FulfillmentService`·`OrderItemQueryService` | `OrderItem` 전이·`Inspection` | `JpaInspectionRepositories` |
| `confirmOrderItem`·`cancelOrderItem`·`openDispute`·`resolveDispute` | `BuyerItemService`·`ItemConfirmer`·`DisputeService` | `Dispute`·`DisputeDecision.of` | `JpaDisputeRepositories` |
| `refundItem`·`cancelCard`·`sellerFault` | `ItemRefunder`·`SellerFaults`·`PgCaller` | `RefundAmounts`·`RefundFault`·`SellerPenalty.issue`·`User.restrictIfPenalized` | `PgHttpClient` |
| `confirmEarn`·`cancelPendingEarn`·`revokeEarn`·`restorePoints` | `PointBook` | `Point.kt`(원장 행 전이·배분) | `JpaPointRepositories` |
| `cancelUnshippedItems`·`autoConfirmItems`·`expirePoints`·`recalcGrades` | `BatchService.kt`의 배치 클래스 | `BatchTargets`·`User.regrade`(GradeReview) | `KeysetBatchTargets`·`JpaUserRepository`(등급 경계 SQL) |
| `settleWeeks`·`settleWeek`·`listSettlements` | `WeeklySettlementBatch`·`SettlementQueryService` | `SettlementWeek`·`SettlementTarget`·`SellerStatement` | `JpaSettlementRepository`(대상 조건·수수료 floor SQL) |

- 원칙은 task 03 계획에서 정했다. Hibernate 규칙상 같은 SQL이 나오려면 세 가지가 god와 같아야 한다: 호출 순서, 영속성 컨텍스트 진입 순서, flush 지점에 대한 변경 시점. 그래서 엔티티 변경은 잠금·네이티브 쿼리 뒤에 둔다. task 05 계획도 같은 원칙이다(커밋 UPDATE 순서 = 패널티 JPQL의 p.id 진입 순서, 도메인 변경은 음수 이월 네이티브 조회 뒤).
- 새 파일 13개: api 1(`ItemController`), domain 4(`Dispute`·`Inspection`·`RefundAmounts`·`Settlement`), infra 3, service 5(`BuyerItemService`·`FulfillmentService`·`ItemLocker`·`ItemRefunder`·`SettlementQueryService`).

### 재합의 1과 허용 차이

- **재합의 1(2026-10-22 16:33, 리뷰 루프 2의 R2-OQ1·OQ2):** spec §2의 규칙 위치 기준(등급·정산 금액은 domain)과 SQL 동치가 충돌하는 곳은 SQL 동치를 우선한다. 등급 선택·등급 경계·정산 대상 조건·판매 수수료 floor는 god와 같은 SQL 문장으로 infra에 둔다. domain은 그 결과로 판정한다. 실험 분석 때 구조 차이로 다룬다. 고정 환불 사유 문구는 domain으로 옮겼다(8c2afd4).
- **허용 차이(1단계 재합의 2 범위 — 도메인 상태 전이 `check`):** 정합한 데이터에서는 실행되지 않는다. 추가한 check는 다음과 같다.
  - 상태 전이 전제: 분쟁 OPEN, 주문 PAID·PARTIALLY_CANCELLED, 항목 DELIVERED(확정)·DISPUTED(기각)·CANCELLED_SELLER_FAULT(불합격 기록), 환불 귀책별 허용 상태
  - 포인트 원장 행 종류: EARN·REVOKE·EXPIRE·RESTORE, 회수량 = 남은 양, 복구 대상 부족 0
  - 정산 대상 분류: REFUNDED_AFTER_CONFIRM 명시
  - 등급 재산정: 실제로 등급이 바뀌는 값
- **제거한 차이:** task 05에서 메인이 정산 합계 `addExact`를 '재합의 2 범위'로 임의 승인했었다. 감사가 근거 없음으로 지적해 god와 같은 일반 연산으로 되돌렸다(R1-6, bc8bcfb).
- 로그만 다른 곳: PG 실패 로그 문자열이 god와 다르다(응답·DB 무관).

### 미해결·이월 (NEXT로)

- **2단계 경로 SQL은 실측하지 못했다(R1-3, user-deferred).** equiv는 1단계 7개 시나리오만 돈다. 2단계 SQL·pg 문장 순서의 동치는 리뷰(god 줄 단위 대조, SQL 문자열 글자 단위)로만 판정했다. god와 코드 순서가 반대인 곳이 3곳 있다: 검수 PASS(`FulfillmentService.kt:81-87` ↔ god :784-799), 분쟁 제기(`BuyerItemService.kt:74-79` ↔ god :903-915), 포인트 소멸·회수 배분(remaining 변경이 배분 persist 직전/직후). IDENTITY persist는 즉시 INSERT하고 flush하지 않는다는 Hibernate 규칙에 따라 같은 문장이라고 추론했다. 실측은 NEXT equiv 2단계 시나리오 작업에서 한다(검수 PASS·분쟁 제기·포인트 소멸 포함).
- **정산 배치 시간 정정:** weekly-settlement 최장 시간은 테스트 순서에 따라 다르다. processed 5,029 순서에서 god 18.3s / layered 17.7s였다. god 2단계 log·NEXT의 '최장 2,416ms'는 한 순서에서 잰 값이었다(120s 제한 안이라는 결론은 그대로) → NEXT 수치 정정.
- god 2단계에서 넘어온 항목(배치 비 PG 예외 시 전체 중단, 500k 정산·point-expiry 시간 등)은 이번 작업에서 바꾸지 않았다. NEXT N2 그대로.

### 규모 (layered main `.kt`, god와 비교)

| | 579e248 | HEAD |
|---|---|---|
| layered 파일 | 41 | 54 (+13) |
| layered 줄 | 2,783 | 4,723 (+1,940) |
| ├ api | 7파일 485 | 8파일 683 |
| ├ service | 14파일 750 | 19파일 1,367 |
| ├ domain | 10파일 1,071 | 14파일 1,922 |
| └ infra | 8파일 448 | 11파일 722 |
| god 파일 / 줄 (무변경) | 14 / 2,648 | 14 / 2,648 |

- 루트 2파일(`LayeredApplication.kt`·`StartupRecovery.kt`)은 계층 행에서 뺐다. 같은 기능을 layered는 god의 1.78배 줄로 구현했다(4,723 / 2,648). 2단계 증분만 보면 god +976(c3d7c22 1,672 → 2,648), layered +1,940이다.

### 사이클 마감

- NEXT 갱신(N1 = equiv 2단계 시나리오, N2 = 1·2단계 open question + layered 재합의 1 기록, 정산 시간 정정), README 진행 행, measurement-log 1행.
- CS 이슈 아카이브: 이번 작업 spec 금지영역(study-note 쓰기 금지)이라 보류 — NEXT '보류·이월'에 기록.
