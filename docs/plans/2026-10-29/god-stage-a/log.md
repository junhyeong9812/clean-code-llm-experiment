# log — god 단계 A 구현 (ADR-0007 A1~A12)

> 작업 폴더: `docs/plans/2026-10-29/god-stage-a/` · 브랜치 `impl/god-stage-a`(main `8663122`) · 산출물 유형: 실행물(god 앱)

## 타임라인

| 시각 | 사건 | 결과 |
|---|---|---|
| 2026-10-29 15:06 | 스키마·시드 병합·push(`8663122`, 사용자 "병합하고 push한 뒤 계속 진행해줘") · 브랜치 `impl/god-stage-a` 생성 · 작업 폴더 생성. god 현재 2,692줄(Marketplace.kt 1,772) | 인터뷰 시작 |
| 2026-10-29 15:15 | 인터뷰 3문항(묶음 A→B→C 순차·테스트 결함은 Claude 판정+목록 보고·auto) → spec → 사용자 합의 → set-state | task 01 |
| 2026-10-29 15:15 | 설계 선검증 생략 근거: 동작 불변식은 ADR-0007(선검증·리뷰 완료) — 이 작업 고유 불변식(고칠 곳 무변경)은 범위 제약이며 스크립트로 기계 확인. blind 테스트 = contract3(이미 별도 워커가 ADR만 보고 작성) | - |
| 2026-10-29 15:15 | task 01: 고칠 곳 대조 스크립트 scratchpad `g7/fixcheck.py` — 기준 `8663122`의 Marketplace.kt 25개 범위(T1 fix·read 8 — 주입 사본 줄 +1 보정, C2 fix·read 11, T2·C1 2·P1·P2 2·기동 전환)가 현재 파일에 글자 그대로 있는지. 기준선 25/25 | task 02 |
| 2026-10-29 15:37 | 묶음 A(gs-02a) 회수: 8개 클래스 첫 실행 전부 통과(A1 19·4, A2 8·2, A3 14·2, A7 4·3) · 기존 test 150·contract2 89 · verify 120 · fixcheck 25/25 · 테스트-ADR 어긋남 0 · 판단 7건(리뷰 작성은 orders 행만 잠금 등). god 3,572줄(+880). 메인 교차 확인: diff 삽입만(삭제 0), 기존 테이블 쓰기 SQL 0, fixcheck 25/25 → 커밋 | 묶음 B ∥ A 리뷰 |
| 2026-10-29 15:37 | 커밋 `6ead58f`(묶음 A). 병렬: 묶음 B 구현(gs-02b, 기준 6ead58f) ∥ 묶음 A 듀얼 1패스(미러 scratchpad `g7/amirror` = git archive 6ead58f, log·NEXT·measurement 제외 · packet diff 8663122..6ead58f · Opus gs-rva-opus ∥ codex) | 대기 |
| 2026-10-29 15:42 | 묶음 A 듀얼 1패스 회수: codex(bwrap 0, 지적 없음) · Opus(blocker 0·major 0·minor 3) → GA-1 rating-recalc가 잠금 쿼리와 별개 쿼리로 리뷰를 다시 읽어 잠기지 않은 새 행을 고칠 수 있음(채택 — 묶음 B 구현이 같은 파일을 쓰는 중이라 B 회수 뒤 메인 수정) · GA-2 정정 금액 오버플로 code 미정 → ADR §19의 8-1(계약 밖) 추가 · GA-3 새 목록 GET 4개가 snapshotTx 밖(채택 — 관례 통일, B 뒤 수정) | B 대기 |
| 2026-10-29 15:58 | 묶음 B(gs-02b) 회수: 7개 클래스 53/53(A4 11·6, A5 7·8·10, A11 7·4), 묶음 A 그대로 통과, test 150·contract2 89·verify 120·fixcheck 25/25, 테스트-ADR 어긋남 0, 판단 6건. god 4,138줄. 커밋 `bcb3e7c` | - |
| 2026-10-29 15:58 | GA-1 수정(메인): recalcRatings가 잠금 쿼리 결과 id 집합으로 걸러 처리(대상·조회 SQL 무변경). GA-3 재판정 → **기각**: 기존 `listTrades`(Marketplace.kt:327)도 트랜잭션 밖 단일 쿼리 — 관례가 원래 섞여 있어 '어긋남' 전제가 틀림. 확인: contract3 클래스 16 통과(A 8·B 7·자체 검증)·C 5 red, verify 120, fixcheck 25/25 → 커밋 | 묶음 C ∥ B 리뷰 |
| 2026-10-29 16:03 | 묶음 B 듀얼 1패스 회수: codex(bwrap 0, 지적 없음) · Opus(major 1·minor 1) → GB-1 위시리스트 삭제에 잠금·재확인 없음 → 동시 삭제 시 StaleState 예외가 400 UNEXPECTED_ERROR(ADR §8.1이 잠금을 안 적음) → 채택: ADR §19의 10-1(사용자 행 FOR UPDATE 뒤 재조회, 동시 삭제는 200 하나·404 하나) + god 수정(C 회수 뒤 메인) · GB-2 대상 조건이 ADR 식을 대수 변형(`paid_at <= :before2d` 등, 기존 shipment-deadline 관례)한 꼴 → layered가 같은 SQL을 내도록 브리핑에 god 조건 문자열·파라미터 이름 전달(기록) | C 대기 |
| 2026-10-29 16:22 | 묶음 C(gs-02c) 회수: 5개 클래스 53/53(A6 16·A8 6·A9 8·A10 8·A12 15) → **contract3 164/164**, test 150·contract2 89·verify 120·fixcheck 25/25, 테스트-ADR 어긋남 0, 판단 12건. diff 삭제 0줄. 커밋 `4796669`. **god 5,204줄**(ADR-0006 크기 기준 5,500 미만 — 크기 확인 단계(§6.1의 7)에서 판정) | GB-1 |
| 2026-10-29 16:22 | GB-1 수정(메인): removeWish에 lockUser 추가 · contract3에 동시 삭제 테스트 1(200·404 — 잠금 없는 이전 코드면 200·400) + SlowTrigger DELETE 지원(RETURN coalesce(NEW, OLD)) · contract3 **165/165**, verify 120, fixcheck 25/25 → 커밋 3개(fix·test·ADR §19의 10-1) | 묶음 C 리뷰 |
| 2026-10-29 16:28 | 묶음 C 듀얼 1패스 회수: codex(bwrap 0, 지적 없음) · Opus(minor 2) → GC-1 날짜 파서(`dateParam`·`dateOrNull`, LocalDate.parse)가 `+10000-01-01`·음수 연도를 받음 → 400 대신 200 저장·`+999999999-12-31`은 plusDays에서 UNEXPECTED_ERROR(채택 — 회귀 끝난 뒤 메인 수정 + §19) · GC-2 inquiry-auto-close·inbound-no-show·price-stats 대상 SQL이 ADR 식의 동치 변형(:due 바인드·KST 반열린 구간·EXISTS) — GB-2와 같은 종류(기록 — layered 브리핑) | 회귀 대기 |
| 2026-10-29 17:04 | 전체 회귀(`g7/runs-full/`, 기준 b5994cb): god test 150·contract2 89·**contract3 165** 모두 통과, layered test·contract2 통과, **equiv 12/12 같음**(기존 경로 god SQL 무변경), verify 120 ×5 | GC-1 |
| 2026-10-29 17:04 | GC-1 수정(메인): dateParam·dateOrNull에 `[0-9]{4}-[0-9]{2}-[0-9]{2}` 검사 + 테스트 2줄(슬롯 `%2B999999999-12-31`·휴가 `+10000-01-01` → 400) + ADR §19의 5 보완. contract3 165/165, verify 120, fixcheck 25/25 → 커밋 3개 | 누적 리뷰 루프 |
| 2026-10-29 17:07 | 누적 리뷰 루프 1 착수(미러 `g7/fmirror` = HEAD 488097a, diff 8663122..HEAD) — Opus(gs-rvf1-opus) ∥ codex. 사용자에게 남은 일정(파일럿 A까지 약 1.5~2일 추정) 보고 → 사용자 "그럼 끝까지 진행하자" | 대기 |
| 2026-10-29 17:22 | 누적 루프 1 회수: codex(1차 bwrap 2 실패 → 2차 성공, 지적 없음) · Opus(major 1·minor 2) → GF-1 addWish·addPriceAlert가 사용자 FOR UPDATE 뒤 INSERT의 FK로 사이즈 KEY SHARE(사용자 → 사이즈) — 기존 placeBid·expireUnpaidTrade(사이즈 → 사용자)와 교착 · GF-2 시각 입력의 timestamptz 범위 밖 값 처리 미정 · GF-3 ADR §11.1 '잠금 없음' 이유 문구 부정확 | 수정 |
| 2026-10-29 17:22 | GF-1 수정(메인): `keyShareSize` = `SELECT id FROM product_sizes WHERE id = ? FOR KEY SHARE`(없으면 404)를 lockUser 앞에(두 곳) + ADR §19의 10-2 + 테스트(위시 등록이 트리거에서 멈춘 사이 같은 사용자·사이즈 BUY 입찰 → 둘 다 201). **음성 대조**: addWish만 옛 코드로 되돌리면 그 테스트에서 기존 `POST /bids`가 400 UNEXPECTED_ERROR(앱 로그 deadlock 5줄) → 결함 실증·테스트 검출력 확인 후 원복. GF-2 → §19의 5에 'timestamptz 범위 밖 시각은 계약 밖' · GF-3 → §11.1 문구 정정. contract3 **166/166**, verify 120, fixcheck 25/25 → 커밋 3개 | 루프 2 |
| 2026-10-29 17:39 | 누적 루프 2 회수(진행 중이던 회귀는 수정이 섞이지 않게 중단): Opus(경로 18개 표 — major F1·minor F2) · codex(1차 bwrap 3 → 2차 성공 — 같은 두 건을 독립 발견, 둘 다 major) → GF-4 항목 문의 작성(사용자 → 항목 KEY SHARE)과 기존 항목 절차(주문 → 항목 FU → 사용자 FU) 교착 · GF-5 리뷰 작성의 구매자 → 판매자 KEY SHARE(id 순 아님)와 판매자 귀책 lockUsers(id 순 FU) 교착 | 수정 |
| 2026-10-29 17:39 | 수정(메인): createInquiry에 항목 `FOR KEY SHARE`를 INSERT 전에 · writeReview에 `users IN (a,b) ORDER BY id FOR KEY SHARE`를 INSERT 전에 · ADR §19의 9-1·9-2(기존 체결 INSERT의 같은 패턴은 기존 경로라 잔여 위험 기록) · 문의 교착 테스트 1(문의가 트리거에서 멈춘 사이 같은 항목 구매자 취소 → 201·200; 옛 코드면 경합 미관측으로 실패). GF-5는 한 문장 안 창이라 결정적 테스트 불가 — 코드 대조로만. contract3 **167/167**, verify 120, fixcheck 25/25 → 커밋 3개 | 루프 3 |
| 2026-10-29 18:15 | 회귀(`g7/runs-full3/`, 루프 2 수정 HEAD 8e1ad20): god test·contract2·contract3 통과, equiv 12/12 같음, verify 120 | - |
| 2026-10-29 18:15 | 누적 루프 3 회수: codex(bwrap 1, 남은 역전 0 확인 — minor 1: 9-2 전용 테스트 없음) · Opus(minor 3: GF-6 9-1 수정이 만든 3자 고리(문의 항목 KS → 사용자 / pg-inquiry 사용자 FU → 주문 KS / 항목 절차 주문 FU → 항목 FU), GF-7 입고 예약 같은 종류 고리(상태 판정이 슬롯 잠금 뒤), GF-8 = codex와 같은 9-2 테스트 공백) → 3건 채택·메인 수정: 문의는 주문 → 항목 KEY SHARE(주문은 KEY SHARE여야 — FU면 pg-inquiry와 2자 교착), 예약은 requireItemStatus를 슬롯 잠금 앞, 리뷰 사전 잠금 테스트(판매자 id < 구매자 id, 다른 주문 검수 FAIL 겹침 → 201·200) + ADR §19의 9-1·§12.3. contract3 **168/168**, verify 120, fixcheck 25/25 → 커밋 3개. **루프 3에 신규 채택 → 종료 조건 미충족(review unresolved)** | 타깃 재점검 |
| 2026-10-29 18:45 | 타깃 재점검(codex, bwrap 0): GF-6·7·8 해소, **신규 0**(문의 경로의 2자·3자 순환 없음, 예약 판정 순서 = ADR, 리뷰 테스트는 사전 잠금 없으면 실패하는 구조) | - |
| 2026-10-29 18:45 | 최종 회귀(`g7/runs-full4/`, HEAD d298178): god test 150·contract2 89·**contract3 168** 통과, **equiv 12/12 같음**, verify 120 ×3 · god **5,228줄**(+2,536) | 마감·사용자 결정 |

## 리뷰 ledger

| ID | 출처 | 지적 | 판정 | 처리 |
|---|---|---|---|---|
| GA-1 | opus minor | recalcRatings: `SELECT id … FOR UPDATE` 뒤 별도 쿼리로 리뷰를 다시 읽어, 그 사이 커밋된 잠기지 않은 행을 HIDDEN 등으로 덮어쓸 수 있음(ADR §4.5) | 채택 | fixed(메인 — 잠근 id만 처리) |
| GA-2 | opus minor | §10.3 supplyAmount 오버플로 시 code(INVALID_REQUEST/UNEXPECTED_ERROR)가 문서로 미정 | 채택 | fixed — ADR §19의 8-1(계약 밖) |
| GA-3 | opus minor | 새 목록 GET 4개(sellerGrades·feeRebates·monthlyStatements·taxInvoices)가 snapshotTx 밖 — 기존 listSettlements 관례와 다름 | 기각 | 기존 listTrades(:327)도 트랜잭션 밖 단일 쿼리 — 관례가 원래 둘 다 있음, 단일 쿼리라 정확성 무관 |
| GB-1 | opus major | removeWish: 잠금·재확인 없이 em.remove — 동시 두 삭제에서 뒤 요청이 StaleState → 400 UNEXPECTED_ERROR(§2.3 위반), ADR §8.1에 잠금 문장 없음 | 채택 | fixed — ADR §19의 10-1 + god lockUser + 동시 삭제 테스트 |
| GB-2 | opus minor | 배치 대상 조건이 ADR 식의 대수 변형(`paid_at <= :before2d AND paid_at > :before3d` 등) — layered가 ADR 원문 식으로 쓰면 equiv SQL 층 차이 | 채택 | 기록 — layered 착수 브리핑에 god 조건 문자열·파라미터 이름 그대로 전달 |
| GC-1 | opus minor | 날짜 입력 파서(Marketplace dateParam·Json dateOrNull)가 ISO 확장 연도(+10000-…·-0001-…)를 받음 — ADR 날짜 형식 YYYY-MM-DD·§2.2 400 위반, 극단값은 400 UNEXPECTED_ERROR | 채택 | fixed — 엄격 형식 + 테스트 2 + ADR §19의 5 |
| GC-2 | opus minor | inquiry-auto-close·inbound-no-show·price-stats 대상 SQL이 ADR 식의 동치 변형(앱 계산 :due·KST 반열린 구간·EXISTS) | 채택 | 기록 — GB-2와 함께 layered 브리핑에 god 정본 형식 전달 |
| GF-1 | opus major | addWish·addPriceAlert 잠금 순서 사용자 → 사이즈(FK KEY SHARE) — 기존 placeBid·payment-due(사이즈 → 사용자)와 교착 → 기존 경로가 새 기능 때문에 실패 | 채택 | fixed — keyShareSize 먼저 + §19의 10-2 + 교착 테스트(음성 대조로 옛 코드 실패 확인) |
| GF-2 | opus minor | 시각 입력(occurredAt·slotStart)의 확장 연도·timestamptz 범위 밖 값 처리가 날짜와 다름 | 채택 | fixed — ADR §19의 5(범위 밖 시각은 계약 밖) |
| GF-3 | opus minor | ADR §11.1 '잠금 없음 — 사이즈 잠금과 경합하지 않게' 이유가 FK KEY SHARE 대기로 성립 안 함 | 채택 | fixed — 문구 정정 |
| GF-4 | opus major·codex major | createInquiry(orderItemId): INSERT FK가 사용자 → 항목 KEY SHARE — 기존 lockOrderOf 경로(주문 → 항목 FU → PG → 사용자 FU)와 교착 | 채택 | fixed — 항목 FOR KEY SHARE 선행 + §19의 9-1 + 교착 테스트 |
| GF-5 | opus minor·codex major | writeReview: reviews FK가 구매자 → 판매자 순 KEY SHARE — 다른 주문의 판매자 귀책 lockUsers(id 오름차순 FU)와 교착(판매자 id < 구매자 id) | 채택 | fixed — 두 사용자 id 순 FOR KEY SHARE 선행 + §19의 9-2(기존 trades INSERT의 같은 패턴은 잔여 위험 기록), 결정적 테스트 불가 |
| GF-6 | opus minor | §19의 9-1 수정(항목 KS 선행)이 3자 고리를 만듦: 문의(항목 KS→사용자 KS) · pg-inquiry/결제 재기록(사용자 FU→주문 KS) · 항목 절차(주문 FU→항목 FU) | 채택 | fixed — 주문 → 항목 KEY SHARE(주문 KS — FU 금지) |
| GF-7 | opus minor | reserveInbound: 주문 FU → 슬롯 FU 뒤에 상태 판정 — PAID 아닌 항목 요청이 슬롯을 기다리며 pg-inquiry와 3자 고리 | 채택 | fixed — requireItemStatus를 슬롯 잠금 전으로(ADR 판정 순서 불변) |
| GF-8 | opus minor·codex minor | §19의 9-2(리뷰 id 순 사용자 KS) 검증 테스트 없음 | 채택 | fixed — 사전 잠금 존재를 검증하는 SlowTrigger 테스트(교착 자체 재현은 불가) |

## 생략한 검증

- **리뷰 종료 조건 미충족(`review unresolved`)**: 누적 루프 3에 신규 채택 3(GF-6·7·8) → 수정 + 타깃 재점검(신규 0). 병합은 사용자 결정.
- GF-5(리뷰 두 사용자 KEY SHARE 순서)의 교착 자체는 결정적 재현 불가 — 사전 잠금 존재만 테스트(GF-8).
- 기존 체결 INSERT(trades 구매자·판매자 FK)의 같은 잔여 위험은 기존 경로라 바꾸지 않음(§19의 9-2 기록).

## 완료 요약

### 무엇을 했나
- ADR-0007 A1~A12를 god에 구현했다. 묶음 세 개를 차례로 진행: A(A1·A2·A3·A7, `6ead58f`), B(A4·A5·A11, `bcb3e7c`), C(A6·A8·A9·A10·A12, `4796669`). 그 뒤 리뷰 수정 커밋들이 이어진다(`git log --oneline 8663122..HEAD` 21개).
- contract3는 164/164로 시작해 168/168이 됐다. 리뷰에서 나온 테스트 4개를 더했다: 위시 동시 삭제, 위시 등록과 입찰 교착, 문의 교착, 리뷰 사전 잠금. 날짜 400 검사 2줄은 기존 테스트에 넣었다.
- god 줄 수는 2,692에서 **5,228**이 됐다(`cat $(find god/src/main -name '*.kt') | wc -l`). 고칠 곳 대조 25/25 그대로, equiv `all` 12/12 같음(기존 경로 SQL 무변경), verify 120 통과.
- 테스트와 ADR이 어긋난 곳은 0건이다. ADR이 정하지 않은 곳은 §19에 보완했다: 8-1, 9-1, 9-2, 10-1, 10-2, 5와 그 확장. §11.1 문구와 §12.3 판정 위치도 보완했다.

### 핵심 diff (실파일 복사)
(a) `runBatch`: 새 이름 분기만 더했다(기존 8 배치 분기는 무변경, `git diff 8663122 HEAD` hunk).
```diff
             "weekly-settlement" -> BatchResult(settleWeeks(now), failed = 0)
+            "rating-recalc" -> recalcRatings(now)
+            "seller-grade-recalc" -> recalcSellerGrades(now)
+            "rebate-settlement" -> settleRebates(now)
+            "monthly-statement" -> writeMonthlyStatements(now)
+            "tax-invoice-issue" -> issueTaxInvoices(now)
+            "deadline-reminder" -> remindDeadlines(now)
+            "price-alert-scan" -> scanPriceAlerts(now)
+            "delivery-delay-scan" -> scanDeliveryDelays(now)
+            "inquiry-auto-close" -> autoCloseInquiries(now)
+            "price-stats" -> writePriceStats(now)
+            "inbound-no-show" -> markInboundNoShows(now)
             else -> badRequest("알 수 없는 배치: $name")
```
(b) `keyShareSize`(GF-1 수정, addWish·addPriceAlert에서 lockUser보다 먼저 부른다)
```kotlin
    private fun keyShareSize(productSizeId: Long) {
        em.createNativeQuery("SELECT id FROM product_sizes WHERE id = :id FOR KEY SHARE").setParameter("id", productSizeId)
            .resultList.ifEmpty { notFound("없는 사이즈: $productSizeId") }
    }
```
(c) `createInquiry`: 주문 → 항목 순서로 KEY SHARE(GF-4 → GF-6)
```kotlin
                // §19의 9-1: 주문 → 항목 KEY SHARE를 INSERT(사용자 FK가 먼저 발동)보다 먼저 — 기존 규약 "주문 → 항목 → 사용자"와 같은 순서.
                // 주문을 FOR UPDATE가 아니라 KEY SHARE로 잡는 이유: pg-inquiry 등이 사용자 FU를 쥔 채 주문에 KEY SHARE를 건다(충돌하면 교착)
                val orderId = em.createNativeQuery("SELECT order_id FROM order_items WHERE id = :id").setParameter("id", orderItemId)
                    .resultList.firstOrNull() ?: notFound("없는 주문 항목: $orderItemId")
                em.createNativeQuery("SELECT id FROM orders WHERE id = :id FOR KEY SHARE").setParameter("id", orderId).resultList
                em.createNativeQuery("SELECT id FROM order_items WHERE id = :id FOR KEY SHARE").setParameter("id", orderItemId).resultList
```
(d) `writeReview`: 두 사용자를 id 순으로 KEY SHARE(GF-5)
```kotlin
            val sellerId = em.find(Trade::class.java, item.tradeId).sellerId
            // §19의 9-2: INSERT의 FK 검사(구매자 → 판매자 순)보다 먼저 두 사용자를 id 오름차순 KEY SHARE — 판매자 귀책의 id 순 FOR UPDATE와 교착 방지
            em.createNativeQuery("SELECT id FROM users WHERE id IN (:a, :b) ORDER BY id FOR KEY SHARE")
                .setParameter("a", userId).setParameter("b", sellerId).resultList
```
(e) 교착 테스트(`A5WishlistTest`, §19의 10-2) 머리 부분
```kotlin
    fun `§19의 10-2·§2_3 위시 등록과 같은 사용자·사이즈 입찰 등록이 겹쳐도 교착 없이 둘 다 201`() {
        val u = Db.createUser()
        val size = Db.createProductSize()
        val rs = SlowTrigger.on("wishlists", "INSERT", condition = "NEW.user_id = $u") {
            val pool = Executors.newSingleThreadExecutor()
            try {
                val wish = pool.submit<Resp> { SB.wishAdd(u, size) }
                Thread.sleep(300)   // 위시 등록이 트리거에서 멈출 시간(그동안 입찰 등록은 아직 보내지 않는다)
                val bid = Flow.bid(u, size, "BUY", 100_000)
```

### 묶음별 결과 (log 회수 행)
| 묶음 | 커밋 | contract3 클래스 | 테스트 수 | god 증가 줄(누적) | 판단 건수 |
|---|---|---|---|---|---|
| A (A1·A2·A3·A7) | `6ead58f` | 8 | 56 (A1 19·4, A2 8·2, A3 14·2, A7 4·3) | +880 (3,572) | 7 |
| B (A4·A5·A11) | `bcb3e7c` | 7 | 53 (A4 11·6, A5 7·8·10, A11 7·4) | +566 (4,138) | 6 |
| C (A6·A8·A9·A10·A12) | `4796669` | 5 | 53 (A6 16·A8 6·A9 8·A10 8·A12 15) | +1,066 (5,204) | 12 |
| 리뷰 수정 | 이후 커밋들 | — | +4 → 168 | +24 (5,228) | — |

SlowTrigger 자체 검증 2개를 더하면 56+53+53+2 = 164이다. 세 묶음 모두 첫 회수에서 green이었고 diff에 지운 줄은 0이다.

### 리뷰 요약
| 회차 | Opus | codex (bwrap 실패) | 채택 | 기각 | 처리 |
|---|---|---|---|---|---|
| 묶음 A 1패스 | minor 3 | 지적 없음 (0) | GA-1·GA-2 | GA-3 (기존 listTrades도 트랜잭션 밖) | GA-1 수정, GA-2 → §19의 8-1 |
| 묶음 B 1패스 | major 1·minor 1 | 지적 없음 (0) | GB-1·GB-2 | — | GB-1 수정+§19의 10-1+테스트, GB-2 기록 |
| 묶음 C 1패스 | minor 2 | 지적 없음 (0) | GC-1·GC-2 | — | GC-1 수정+§19의 5+테스트, GC-2 기록 |
| 누적 루프 1 | major 1·minor 2 | 지적 없음 (2 → 재시도 성공) | GF-1·2·3 | — | 수정+§19의 10-2·5·§11.1 |
| 누적 루프 2 | major 1·minor 1 | 같은 두 건 major (3 → 재시도 성공) | GF-4·5 | — | 수정+§19의 9-1·9-2 |
| 누적 루프 3 | minor 3 | minor 1 (1) | GF-6·7·8 | — | 수정+§19의 9-1·§12.3+테스트 |
| 타깃 재점검 | (log 미기록, 신규 0(codex, 루프 3 수정분)) | | | | |

합계: 지적 15건 중 채택 14, 기각 1. codex bwrap 실패는 6회(루프 1에서 2, 루프 2에서 3, 루프 3에서 1)였고, 실패한 회차는 모두 재시도에서 성공했다.

**교착 계열(GF-1·4·5·6·7)**: 묶음별 1패스에서는 하나도 나오지 않았다. 누적 루프에서 처음 드러났는데, 리뷰가 새 경로와 기존 경로의 잠금 순서를 교차 대조했기 때문이다(루프 2의 Opus는 경로 18개를 표로 대조했다). 원인은 모두 INSERT가 FK 검사로 거는 암묵적 KEY SHARE였다. 이 잠금이 기존 규약(사이즈 → 사용자, 주문 → 항목 → 사용자, 사용자 id 오름차순)과 거꾸로 걸렸다. GF-4와 GF-5는 Opus와 codex가 따로따로 같은 결함을 찾았다. GF-6은 GF-4를 고친 수정이 pg-inquiry와 3자 고리를 새로 만든 경우이고, 수정이 다시 결함을 낳은 사례다. GF-7은 같은 종류의 고리가 입고 예약에도 있었던 경우다. GF-1은 **음성 대조**로 확인했다. addWish만 옛 순서(사용자 FU → INSERT FK의 사이즈 KS)로 되돌려 10-2 테스트를 돌렸더니 기존 `POST /bids`가 400 UNEXPECTED_ERROR로 실패했고, 앱 로그에 deadlock 5줄이 찍혔다. 결함이 실제로 있고 테스트가 그것을 잡아낸다는 것을 확인한 뒤 원복했다. GF-5는 한 문장 안의 짧은 창이라 교착을 결정적으로 재현할 수 없다. 그래서 코드 대조로 판정하고, 사전 잠금이 있는지만 테스트한다(GF-8).

### 남은 것
- 최종 회귀(HEAD d298178): god test 150·contract2 89·contract3 168 통과, equiv 12/12 같음, verify 120 통과 · 타깃 재점검 신규 0
- god은 5,228줄로 ADR-0006 기준 5,500보다 작다. 판정은 layered까지 끝난 뒤 크기 확인 단계(§6.1의 7)에서 한다.
- layered를 구현할 때 god SQL 정본 형식을 브리핑으로 전달해야 한다(GB-2·GC-2). 대상 조건 문자열·파라미터 이름(`:before2d`·`:due`, KST 반열린 구간, EXISTS)이 그 대상이다. 맞추지 않으면 equiv SQL 층에서 차이가 난다.
- ADR §19 보완 목록: 8-1(오버플로는 계약 밖), 9-1(문의는 주문 → 항목 KS), 9-2(리뷰는 id 순 사용자 KS, 기존 trades INSERT의 같은 패턴은 잔여 위험), 10-1(위시 삭제 잠금), 10-2(등록은 사이즈 KS 먼저), 5(날짜 YYYY-MM-DD만, 범위 밖 시각은 계약 밖으로 확장). 그 밖에 §11.1 문구 정정과 §12.3 판정 위치.
- **종료 조건 미충족**: 루프 3에서도 새로 채택된 결함이 있었다(review unresolved). 루프 상한 3에 이르렀으므로 타깃 재점검 결과를 보고 계속할지 사용자가 결정해야 한다.
- 다음 작업은 layered 단계 A 구현이다.
