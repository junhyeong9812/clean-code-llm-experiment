# S1 설계 — 배치 대상 순회의 id 상한(bug, 증상 버그)

> ADR-0008 §4.2 S1 행이 정본. 작성 기준: 브랜치 `search-cost-tasks`, HEAD `16fb920`. 줄 번호는 **주입 사본** 기준이다 — 주입은 줄을 더하거나 빼지 않아 원본과 좌표가 같다.

## 1. 의도

- 규칙(ADR-0003 §7·§12의 2): 배치는 키셋 페이지(500개씩)로 대상 id를 id 순으로 읽고, **배치를 시작할 때의 최대 id까지(포함)** 본다. 실행 중에 새로 생긴 행(재매칭 체결 등)은 다음 실행의 대상이다.
- 주입: 공통 순회의 상한 비교 `t.id <= :upTo` → `t.id < :upTo`. 결과: 대상 테이블의 **마지막 행(id = max)** 이 조건을 만족해도 그 실행에서 빠진다. 그 뒤 같은 테이블에 행이 하나 더 생기면 다음 실행에서 처리된다.
- 영향 배치: 공통 순회를 쓰는 배치 전부(bid-expiry·payment-due·pg-inquiry·shipment-deadline·auto-confirm·point-expiry·… 기동 시 전환 포함). 증상은 payment-due 하나로 서술한다.
- 난이도 수단: **분리**(증상은 payment-due, 고칠 곳은 모든 배치가 공유하는 순회) · **오진 유도**(payment-due 대상 조건·결제 기한 시각 비교 `payment_due_at <= :now`·재확인 `isAfter(now)`, 결제 기한 코드 KDoc) · **찾기 비용**(증상 낱말이 고칠 곳 근처에 없음 — §3).

## 2. 주입(`inject.{god,layered}.patch`)

| 구조 | 고칠 곳(주입 줄) | 상한 계산(alt-1 자리) | 상한 바인딩(alt-2 자리) |
|---|---|---|---|
| god | `Marketplace.kt:1388` `forEachTarget`의 조회 SQL `… AND t.id < :upTo …` | `:1382` `val upTo = … max(id) …` | `:1389` `.setParameter("upTo", upTo)` |
| layered | `infra/KeysetBatchTargets.kt:108` `forEach`의 조회 SQL(같은 문자열) | `:103` | `:109` |

- 1 대 1, SQL 문자열 동일(god `$table`/`$condition`, layered `${spec.table}`/`${spec.condition}`만 다름). 두 사본 모두 경고 없이 컴파일된다(§10 build=True).

### 2.1 규칙 서술 주석 — 중립 문장으로 바꿈

전수 grep: 두 구조 `src/`에서 `grep -rn "max(id)\|최대 id\|새로 생긴\|다음 실행의 대상\|시작할 때\|upTo"`. 상한 규칙("최대 id까지만 본다")을 서술한 주석만 골랐다. 나머지 "다음 실행의 대상" 적중(격리 실패·카드 취소 실패·리뷰 잠금)은 다른 규칙이라 그대로 둔다.

| 구조 | 위치 | 원문 → 주입 후 |
|---|---|---|
| god | `Marketplace.kt:1369`(forEachTarget KDoc) | "배치를 시작할 때의 최대 id까지만 본다: 실행 중에 새로 생긴 행(재매칭 체결·새 주문 등)은 다음 실행의 대상이다." → "읽을 대상은 배치를 시작할 때 정한다." |
| layered | `domain/BatchTargets.kt:92`(BatchTargets.forEach KDoc) | 같은 원문 → 같은 문장 |
| layered | `infra/KeysetBatchTargets.kt:18`(클래스 KDoc) | "… 시작할 때의 max(id)까지만 본다." → "… 읽을 대상은 시작할 때 정한다." |

- 바꾼 개수: **god 1, layered 2**. 원본 layered가 같은 규칙을 포트(domain)와 구현(infra) 두 층에 적었다. 줄 수 유지.
- 중립 문장은 포함/배타를 말하지 않는다. "범위"는 god에서 고칠 곳 ±40에 걸려 버렸고(첫 판), "대상은"(god 1·layered 2, 고칠 곳 근처는 god :1369 하나)로 바꿨다. 과제 문구에 없는 낱말이다.
- 둘째 절("새로 생긴 행 … 다음 실행의 대상")도 상한 규칙의 근거 서술이라 함께 지웠다. 남겼다면 god는 고칠 곳 19줄 위, layered는 다른 파일이라 "다음 실행"·"새로 생긴" grep이 god에서만 고칠 곳에 닿는다(비대칭).

### 2.2 남는 단서(올바른 구현 잔여물)

| 구조 | 잔여물 |
|---|---|
| god | `val upTo = max(id)`(:1382)와 `< :upTo`(:1388)가 6줄 간격 — "최대 id 미만"이 코드에서 바로 읽힌다 |
| layered | `:103`과 `:108`이 5줄 간격 — 같은 모양 |

대칭이다. 순회를 읽기만 하면 찾을 수 있는 결함이고, 비용은 그 순회에 닿기까지(찾기)에 있다.

## 3. 찾기 비용 조건(ADR-0008 §4.1) — 작성자 자체 측정

TASK.md·공개 테스트(`pub/S1Test.kt`)에서 뽑은 낱말(한국어 그대로 + 영어 추측)을 주입 사본 `app/src/main`에 `grep -rnF`. 형식: 줄 수(고칠 곳 ±40 안의 줄). 고칠 곳 = god `Marketplace.kt:1388`(±40 = 1348–1428), layered `KeysetBatchTargets.kt:108`(±40 = 68–148).

| 낱말 | god | layered | 직접 단서? |
|---|---|---|---|
| `payment-due` | 4 (없음 — :1338 Δ50, :1434 Δ46, :1446 Δ58, :1326) | 8 (없음 — 같은 파일 :20 Δ88) | 아니오 |
| `payment_due` | 3 (없음) | 7 (없음) | 아니오 |
| `paymentDue` | 7 (없음) | 9 (없음) | 아니오 |
| 결제 기한 | 1 (없음) | 6 (없음) | 아니오 |
| 결제 대기 / 나중 / 만들어진 / 한 번에 / 가장 최근 / newest / overdue / unpaid | 0 | 0 | 아니오 |
| 미결제 | 0 | 1 (없음) | 아니오 |
| 만료 | 10 (없음) | 21 | 아니오 |
| 가장 | 7 (없음) | 8 (없음) | 아니오 |
| 마지막 | 11 | 16 | 아니오(>10) |
| 최근 | 4 (없음) | 6 (없음) | 아니오 |
| 최신 | 3 (없음) | 2 (없음) | 아니오 |
| 배치 | 12 (:1358·1361·1368·1369·1371) | 13 (없음) | 아니오(>10) |
| 체결 | 45 (:1422) | 71 | 아니오(>10) |
| TRADE_NOT_PAYABLE | 2 (없음) | 4 (없음) | 아니오 |
| PAYMENT_EXPIRED | 3 (없음) | 5 (없음) | 아니오 |
| AWAITING_PAYMENT | 9 (없음) | 17 | 아니오 |
| CANCELLED | 21 | 32 | 아니오 |
| 구매 입찰 / 재매칭 / 구매 경고 | 2·2·1 (없음) | 6·3·2 (없음) | 아니오 |
| expire | 57 (:1415–1423) | 75 | 아니오(>10) |
| latest | 3 (없음) | 6 (없음) | 아니오 |
| last | 22 (:1411) | 40 (:116) | 아니오(>10) |
| max | 18 (:1382) | 20 (:103) | 아니오(>10) |
| batch / Batch | 2 (없음) / 32 | 7 (없음) / 180 | 아니오 |
| 시점 | 1 (없음) | 3 (없음) | 아니오 |

- **결과: 두 구조 모두 직접 단서 0개.** 최종 판정은 별도 리뷰어.
- 경계 주의(참고 — 과제 문구에 없는 코드 낱말): `max(` god **10**(:1382 포함 — 10줄 이하라 뽑히면 god만 직접 단서)·layered 12. `max(id)` 1/1(두 구조 모두 고칠 곳 근처), `upTo` 3/3, `id <` 2/2, `ORDER BY t.id` 4/4, `LIMIT` 6/8 — 모두 두 구조 고칠 곳 근처. SQL·코드 식별자는 TASK.md에 쓰지 않았고(브리핑 금지), 리뷰어가 이것을 추측 낱말로 뽑으면 걸린다. `max(`만 구조 비대칭(10 vs 12)이다 — 처치 결과가 아니라 파일 내 `max(` 사용 수 차이. 리뷰어 판단에 맡기고 표시만 한다.
- 낱말 대칭: 고칠 곳 근처에 한 구조에만 있는 단서는 `배치`(god :1358–1371 — god는 runBatch·forEachTarget이 한 파일에 붙어 있음, layered는 BatchService.kt와 KeysetBatchTargets.kt로 나뉨)·`키셋`(god 4, :1368 — 고칠 곳 KDoc; layered 6, 같은 파일 :18은 Δ90)·`체결`(:1422 expireBids 아래 KDoc). 셋 다 god 적중 수가 10을 넘거나(`배치` 12, `체결` 45) 과제 문구에 없다(`키셋`). 한 파일에 몰린 구조(처치)의 결과로 표시한다.

## 4. 정답

- `fix.{god,layered}.patch`: `t.id < :upTo` → `t.id <= :upTo`(원본 복원). 주석은 되돌리지 않는다(중립 문장 그대로). inject + fix = 원본 코드.
- **대안 정답 alt-1(상한 계산 쪽, ADR §4.2):** `val upTo = (… max(id) …).toLong() + 1`(god `:1382`, layered `:103`). 배타 비교 `< max+1`은 `<= max`와 같은 집합이다(id는 정수, 빈 테이블이면 coalesce 0 + 1 = 1 → `id < 1`은 공집합 — 원본과 같음). **성립한다** — 두 구조 모두 상한 계산이 순회 함수 안 한 줄이라 같은 형태로 고칠 수 있다.
- **대안 정답 alt-2(상한 바인딩 쪽, 듀얼 리뷰 지적 2026-11-01 등록):** `.setParameter("upTo", upTo + 1)`(god `:1389`, layered `:109` — SQL 바로 다음 줄). alt-1과 같은 값(`max+1`)을 바인딩 자리에서 넣는다 — 같은 집합, 빈 테이블도 같음. **성립한다** — 두 구조 모두 SQL 다음 줄이 같은 모양이다. 등록 전에는 판정 범위(fix ∪ alt-1) 밖이라 이 형태의 성공 실행이 `fce_fallback`으로 잡혔을 것이다.
- 숨은 테스트가 대안을 막지 않는 이유: 체결·입찰의 상태(DB), 결제 요청 응답 코드, `GET /bids/{id}` 상태만 본다. 비교 연산자나 상한 값은 보지 않는다.
- 고려한 다른 대안: 상한을 없애기(`AND t.id <= :upTo` 삭제) — 실행 중 재매칭으로 생긴 새 체결까지 같은 실행에서 처리하게 되어 원본 규칙과 다르다. 계약 테스트(payment-due 재매칭 — 새 체결은 AWAITING_PAYMENT로 남아야 함)가 기한 전 새 체결이라 대상 조건에서 걸러져 잡지 못할 수 있다. 정답으로 등록하지 않는다(판정에서 success면 `fce_fallback`으로 드러난다).

## 5. 순진한 오답(naive-N — 전부 숨은 테스트에서 실패해야 채택, 모두 fix ∪ alt 줄을 건드리지 않음)

| N | 패치(두 구조 같은 의미) | 바꾸는 곳 | 기대 실패 |
|---|---|---|---|
| 1 | **payment-due만 고침:** 순회 전 `max(id) FROM trades`를 기억해 두고 순회 뒤 그 체결을 한 번 더 만료 시도 | god `expireUnpaidTrades`(:1439–1447) / layered `PaymentDueBatch.run`(BatchService.kt :173–174) + `TradeRepository.maxId()`(Trade.kt·JpaTradeRepository.kt) | **public pass**, hidden A pass, **B fail**(bid-expiry 마지막 입찰 ACTIVE) |
| 2 | 대상 조건: 결과 미정 주문 제외(`NOT EXISTS …`) 삭제 | god :1442–1444 / layered KeysetBatchTargets.kt :32–34 | public fail, hidden A·B fail |
| 3 | payment-due를 한 요청에서 두 번 돌림 | god runBatch :1338 / layered BatchService.run :100 | public fail(상한이 그대로라 둘째 실행도 마지막 행을 뺌), hidden A·B fail |
| 4 | 결제 기한 시각 비교에 1분 여유(대상 조건 파라미터 `now + 1분`, 재확인 비교도 `now + 1분`) | god :1445·:1462 / layered KeysetBatchTargets.kt :35(+import) · Trade.kt :46 | public fail, hidden A·B fail |

- naive-1은 ADR §4.2의 "payment-due 전용 수정" 오답을 대표한다(숨은 테스트 B가 존재하는 이유). layered는 포트에 메서드를 더해야 해 파일 수가 많다(god 1파일, layered 3파일) — 오답의 크기 차이는 판정에 영향 없음.
- 추출기 비적중(§4.3-4, S1 자체분): `fce.patch_ranges`(runner/fce.py, 6d02f1f)로 fix ∪ alt-1 ∪ alt-2(god {1382, 1388, 1389}, layered KeysetBatchTargets {103, 108, 109} — alt-2 줄은 2026-11-01 15시 재확인)과 naive 1~4의 바뀐 줄을 비교 — **8개 모두 비적중**(god naive-1 {1439,1440,1447,1448}·2 {1442–1444}·3 {1338}·4 {1445,1462}; layered naive-1 Trade.kt/JpaTradeRepository.kt/BatchService.kt {173,174}·2 KeysetBatchTargets {32–34}·3 BatchService {100}·4 KeysetBatchTargets {14,15,35}+Trade.kt {46}). 다른 과제의 오답에 대한 S1 범위 비적중은 메인 몫.

## 6. 숨은 테스트(`hidden/S1HiddenTest.kt`)

- **A(payment-due):** 새 사용자·새 사이즈로 체결 둘을 차례로 만든다(뒤의 것이 `trades` 최대 id — 준비 단언). 시계를 두 결제 기한 중 늦은 것 + 1분으로 옮기고 payment-due 1회. 두 체결 모두: DB `status = PAYMENT_EXPIRED`, `POST /orders` 409 `TRADE_NOT_PAYABLE`, `GET /bids/{구매 입찰}` CANCELLED.
- **B(bid-expiry):** 상대 없는 판매 입찰 둘(validDays 30)을 차례로 건다(뒤의 것이 `bids` 최대 id — 준비 단언). 시계를 두 expires_at 중 늦은 것 + 1분으로 옮기고 bid-expiry 1회. 두 입찰 모두 `GET /bids/{id}` EXPIRED, DB EXPIRED.
- 두 메서드는 각자 현재 시계에서 출발(순서 무관). 앞 행(마지막 아닌 체결·입찰)도 단언해 "아무것도 처리 안 함" 같은 오답과 구분한다.
- 공개 테스트와의 관계: 공개 테스트는 체결 1건(trades 마지막 행) + payment-due 1회만 본다. naive-1은 공개를 통과하고 숨은 B에서 실패한다(의도). TASK.md의 "같은 원인으로 생기는 다른 증상도 함께 없어져야 한다"가 B의 근거다(원인 위치는 쓰지 않음).

### 6.1 고칠 곳을 지나는 호출 경로

| 시나리오 | god | layered |
|---|---|---|
| A | `POST /admin/batches/payment-due` → `runBatch` :1338 → `expireUnpaidTrades` :1439 → `forEachTarget` :1375 → **:1388** | `BatchService.run` :100 → `PaymentDueBatch.run` :173 → `forEachIsolated` :44 → `KeysetBatchTargets.forEach` :100 → **:108** |
| B | `runBatch` :1337 → `expireBids` :1416 → `forEachTarget` → **:1388** | `BidExpiryBatch.run` :139 → `forEachIsolated` → `forEach` → **:108** |

실측: none에서 A·B 모두 실패, fix에서 통과, naive-1은 B만 실패(§10) — 두 시나리오가 고칠 줄을 지난다는 것이 실행으로 확인된다.

## 7. 제외한 유사 지점과 사유

- `t.id > :after`(같은 줄의 하한) — 키셋 커서, 주입 대상 아님.
- payment-due 대상 조건·재확인·결제 기한 계산(체결 + 30분, god :330 KDoc) — 오진 유도 영역, 손대지 않음.
- 다른 배치의 자체 순회(리뷰 잠금 등, god :2027·layered ReviewService.kt:134) — 공통 순회를 쓰지 않으므로 주입 영향 없음.

## 8. 회귀 · regression-exclude · verify-exclude

- 정답은 원본 복원(inject + fix = 원본 코드, 주석만 중립) → **regression-exclude 0, verify-exclude 0**(파일 없음 — 판정기는 없는 파일을 빈 목록으로 읽는다). 실측: fix 전 단계 contract 122·contract2 89·contract3 252 모두 통과(§10).
- **주입 상태에서 깨지는 계약 테스트(ADR-0005 §3.1 — 에이전트 사본에는 계약 테스트가 없다):** §10의 none 전 단계 행에서 옮김.

- 두 구조 **같은 개수·같은 메서드**(이름 집합 일치, 2026-11-01 none 전 단계 1회): contract **17**/122, contract2 **18**/89, contract3 **67**/252.
  - contract: `BatchAndClockTest` 11(payment-due·구매 경고 전부), `BatchIsolationTest` 3(payment-due·pg-inquiry·bid-expiry 격리), `PaymentTest` 3(pg-inquiry).
  - contract2: `BatchIsolationTest` 5, `BatchTest` 3(auto-confirm·shipment-deadline), `PenaltyTest` 4, `PointGradeTest` 1, `RefundTest` 2, `SerialRaceTest` 1, `SettlementTest` 2.
  - contract3: `A14PriceAlertRearmTest` 12, `A14PriceAlertRenewTest` 10, `A4DeadlineReminderTest` 9, `A5PriceAlertScanTest` 8, `A4NotificationTest` 5, `A11DeliveryDelayScanTest` 4, `A17NotificationBulkTest` 4, `A15InquiryReopenTest` 3, `A17NotificationPurgeTest` 3, `A7TaxInvoiceCorrectionTest` 3, `A7TaxInvoiceIssueTest` 3, `A6InquiryTest` 1, `A8PriceStatsTest` 1, `A9AdminQueryTest` 1.
  - 대부분 "테스트가 방금 만든 행이 그 테이블의 마지막 행"인 배치 시나리오다(알림·문의·계산서 등은 배치가 만든 행을 뒤에서 읽어 연쇄 실패). 에이전트 사본에는 이 테스트들이 없다 — 있었다면 "배치마다 마지막 대상이 빠진다"가 바로 드러나 찾기 비용이 사라진다.

## 9. symbols

- `fix`: god `Marketplace.kt:1388`, layered `KeysetBatchTargets.kt:108` — 각 **1줄**(ADR-0008 §5.2 — 문맥 제외).
- `alt`: alt-1 god `:1382`, layered `:103` · alt-2 god `:1389`, layered `:109` — 각 1줄. symbols 파일에는 종류 `fix`로 적고 메모에 `[alt-1]`·`[alt-2]`(events.py 형식은 fix|read|edit만). 판정 범위 fix ∪ alt = 구조마다 **3줄**(alt-2 등록 전 2줄).
- `edit`(ADR-0005 보조 지표용): 고칠 파일 전체 — god `Marketplace.kt:1-3667`(사실상 모든 로직), layered `KeysetBatchTargets.kt:1-119`. god의 edit 적중이 넓은 것은 T1과 같은 성질(해석 주의).
- `read`: 순회·payment-due 대상 조건·재확인·bid-expiry·runBatch.

## 10. 채택 조건 스모크(작성자 — 채택 행렬은 메인이 고정 SHA에서)

`free -g`(시작 10:28): 총 36G, 가용 23G. 1레인 순차. 결과 행 = `<scratchpad>/sct/S1-checks/checks.jsonl`(16행). `code_dirty` True(S1 산출물 미커밋). `code_sha`는 실행 중 다른 워커 커밋으로 `6d02f1f` → `e65ca93`(12:32부터). 16fb920..e65ca93 사이 `god`·`layered`·`behavior-tests` 변경 0, 러너는 추출기 `fce.py`·그 테스트 추가뿐 — 판정 경로 영향 없음.

| 구조 | 패치 | 시각 | 단계 | success | 결과 | 숨은 실패 |
|---|---|---|---|---|---|---|
| god | fix | 10:48 | 전 단계 | **True** | public·hidden pass, contract 122·contract2 89·contract3 252 통과 | 0/2 |
| layered | fix | 11:05 | 전 단계 | **True** | 〃 | 0/2 |
| god | none | 11:21 | 전 단계 | False | public fail, contract 17·contract2 18·contract3 67 fail(§8) | **2/2** |
| layered | none | 11:37 | 전 단계 | False | 〃(같은 메서드) | **2/2** |
| god | alt-1 | 11:54 | 전 단계 | **True** | fix와 같음 | 0/2 |
| layered | alt-1 | 12:11 | 전 단계 | **True** | 〃 | 0/2 |
| god | naive-1 | 12:16 | public,hidden | False | **public pass** | **1/2**(B) |
| layered | naive-1 | 12:22 | 〃 | False | **public pass** | **1/2**(B) |
| god | naive-2 | 12:27 | 〃 | False | public fail | 2/2 |
| layered | naive-2 | 12:32 | 〃 | False | public fail | 2/2 |
| god | naive-3 | 12:37 | 〃 | False | public fail | 2/2 |
| layered | naive-3 | 12:42 | 〃 | False | public fail | 2/2 |
| god | naive-4 | 12:47 | 〃 | False | public fail | 2/2 |
| layered | naive-4 | 12:54 | 〃 | False | public fail(첫 판 12:49는 패치 컴파일 오류 — `Spec(…)` 괄호 누락. 패치 고쳐 재실행, 이 행이 유효) | 2/2 |
| god | alt-2 | 15:28 | 전 단계 | **True** | fix와 같음(contract 122·contract2 89·contract3 252 통과) — 결과 `<scratchpad>/sct/S1-checks-alt2/checks.jsonl`, code_sha 291176c(dirty) | 0/2 |
| layered | alt-2 | 15:44 | 전 단계 | **True** | 〃 | 0/2 |

- 숨은 실패 단언(두 구조 같은 값): A — "마지막 체결 상태(DB) expected PAYMENT_EXPIRED but was AWAITING_PAYMENT"(앞 체결 단언은 통과 — 마지막 행만 빠짐을 직접 보여 줌). B — "마지막 입찰 상태(HTTP) … status ACTIVE"(expected EXPIRED).
- **숨은 실패 수가 패치마다 두 구조 같다**(none 2·naive-1 1·naive-2~4 2). 변조 0, 빌드 실패 0(유효 행 기준).
- 아직 안 한 것(메인 채택 행렬 몫): fix 숨은 테스트 5회 결정성, 판정 2회 재현, 추출기 적중·비적중 실측.
