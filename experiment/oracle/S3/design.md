# S3 — 판매 제한 중인 판매자의 판매 입찰 감추기 (change, 흩어진 규칙)

> 정본: ADR-0008 §4.2 S3 행·§4.3·§5.3. 좌표는 기준 사본(주입 없음 — 원본 `god/`·`layered/`와 같다). 작성 기준 HEAD 6d02f1f.

## 의도

같은 새 규칙을 원시 SQL 네 벌(시세·체결 상대·위시리스트·가격 알림)에 빠짐없이 넣어야 하는 과제다(ADR-0008 유형 '흩어진 규칙'). 주 지표는 **모든 고칠 곳 도달 턴**(§5.3). 주입은 없다(TYPE=change, `inject.*.patch` 없음 — C2와 같다). 기준 상태 = 제한 중 판매자의 판매 입찰이 네 곳 모두에 그대로 잡힌다 → 숨은 테스트 실패.

두 함정을 함께 둔다.
- **방향:** 시세 쿼리는 BUY·SELL을 한 문장으로 읽는다(`max(BUY)`·`min(SELL)`) — WHERE에 조건을 넣으면 최고 구매가까지 바뀐다. 체결 상대 쿼리는 새 입찰 방향에 따라 상대 쪽이 바뀐다 — 상대가 SELL(새 입찰이 BUY)일 때만 조건이 붙어야 한다.
- **상태:** 입찰을 CANCELLED로 바꾸면(C2 식) 제한이 끝난 뒤 다시 나타나지 않는다.

## 계약 (TASK.md 요약)

- 판매 제한 중 = `users.sale_restricted_until > 현재`(같으면 제한 아님 — 등록 403 기준과 같다).
- 그런 판매자의 SELL 입찰은 ① `GET /product-sizes/{id}/book` 최저 판매가 ② `GET /me/wishlist` 항목 최저 판매가 ③ `price-alert-scan` 발동 후보 ④ 새 구매 입찰·즉시 구매의 체결 상대에서 빠진다. 제한이 끝나면(현재 ≥ 제한 끝) 다시 포함.
- 입찰 상태·유효기간·이벤트는 그대로. 구매 쪽(최고 구매가, 새 판매 입찰·즉시 판매의 상대 — 판매 제한 중인 사용자의 구매 입찰 포함, 구매 제한 규칙)은 그대로.
- 정하지 않은 것(판정 밖): `payment-due`가 되살린 판매 입찰의 재매칭(god `:1493`, layered `BatchService.kt:197`)은 판매 입찰이 새 입찰 쪽이라 상대가 BUY다 — 정답은 바꾸지 않고(조건이 `side == "BUY"`일 때만 붙는다) 숨은 테스트도 보지 않는다.

## 구조별 고칠 곳 (정답 — 그룹 4개, ADR-0008 §5.3)

조건 조각(두 구조 같은 문자열): `NOT EXISTS (SELECT 1 FROM users u WHERE u.id = bids.user_id AND u.sale_restricted_until > :now)`. 네 쿼리 모두 별칭 없는 `FROM bids`이고 `:now`를 이미 바인드한다.

| 그룹 | god `Marketplace.kt` (fix 줄) | layered (fix 줄) | 변경 |
|---|---|---|---|
| book | `getBook` `:281` | `infra/JpaBidRepositories.kt` `book` `:69` | `min(price) FILTER (WHERE side = 'SELL' AND <조건>)` — `max(BUY)`와 WHERE는 그대로 |
| match | `findBestCounterBid` `:312-313`(삽입 앵커 = `order` 줄)·`:316` | `bestCounter` `:50-51`·`:54` | `val sellerOpen = if (side == "BUY") " AND <조건>" else ""`를 쿼리에 붙임 — 상대가 SELL일 때만 |
| wishlist | `wishlist` `:2628` | `infra/JpaNotificationRepositories.kt` `bestSell` `:71` | `" AND <조건>"` 덧붙임 |
| alert | `scanPriceAlerts` `:2706-2707`(조건 줄 삽입) | `triggeringSell` `:121-122` | 〃 |

- 고칠 파일: god 1 / layered 2(같은 `infra` 패키지). 고칠 곳 4 대 4, SQL 문자열 같다(ADR §4.2).
- `groups.json`: 그룹마다 그 쿼리를 감싼 범위(god book `:279-285`·match `:305-326`·wishlist `:2627-2629`·alert `:2705-2708`, layered book `:66-75`·match `:43-64`·wishlist `:68-74`·alert `:118-125`). 도달 판정은 fce가 `(fix ∪ alt) ∩ 그룹`으로 하므로 실제로 세는 줄은 위 표의 fix 줄이다.
- 판정 범위 줄 수(§5.2, fix ∪ alt): god 9줄(fix 7 + alt 2) / layered 9줄(fix 7 + alt 2).

## 대안 정답

- **alt-1 (공통 조건 조각 한 곳):** 성립한다. god는 파일 최상위 `private const val SELLER_NOT_RESTRICTED`(import 뒤 `:116-117` 삽입), layered는 `JpaBidRepositories.kt` 끝에 `internal object SaleRestrictionSql { const val SELLER_OPEN }`(`:81-82` 삽입 — 같은 패키지의 `JpaNotificationRepositories.kt`가 쓴다, 기존 `PriceAlertRearmSql`과 같은 모양). 네 쿼리는 문자열 템플릿으로 그 상수를 붙인다 — 사용처 줄은 정답과 같은 줄이라 각 그룹에 그대로 속한다. **정의 줄(god `:116-117`, layered `:81-82`)은 어느 그룹에도 넣지 않는다** — 정의만 하고 사용처를 안 고치면 아무 그룹에도 닿지 않아야 하므로(첫 정답 편집 턴에는 센다 — §5.2 alt 범위).
  - 재생기 주의: god 정의 삽입은 빈 줄 경계라 재생기(difflib)가 앵커를 `:115`로 잡는다(패치 범위는 `:116-117`). 정의만 먼저 넣는 실행은 첫 정답 편집으로 잡히지 않을 수 있다 — 그룹 도달(주 지표)에는 영향 없음.
- 숨은 테스트는 결과만 보므로 `LEFT JOIN`·`EXISTS` 대신 `user_id NOT IN (…)`·서비스 층 거르기 등도 통과한다. 다만 판정 범위 밖 줄(예: 시세 WHERE를 `side = 'BUY' OR …`로 고치는 `:283`)만 고친 정답은 그 그룹 도달로 잡히지 않는다(§5.2 넓히는 규칙 없음 — 성공 + 미도달이면 fce_fallback).

## 순진한 오답 (naive-N)

| N | 오답 | 숨은 테스트에서 실패해야 하는 곳 |
|---|---|---|
| 1 | 시세만 | B2·B3·B4·C1 |
| 2 | 체결 상대만 | B1·B2·B3 |
| 3 | 시세·체결 상대만(위시리스트·가격 알림 빠짐) | B2·B3 |
| 4 | 구매 쪽까지 거름: 시세 WHERE 전체에 조건 + 체결 상대 양방향(상대 BUY의 주인이 판매 제한 중이어도 뺌), 위시리스트·알림은 정답 | B1(최고 구매가 사라짐)·B5(판매 제한 사용자의 구매 입찰과 거래 안 됨) |
| 5 | C2 식: 제한이 걸리는 순간 판매 입찰을 CANCELLED로(= C2 정답 패치 그대로) | 숨은 테스트는 제한을 SQL로 걸어 취소가 일어나지 않는다 → B1·B2·B3·B4·C1(기준 상태와 같다). 패널티 경로로 제한을 걸면 '상태는 그대로'·'제한이 끝나면 다시 포함'이 깨진다 |
| 6 | 경계: `sale_restricted_until >= 현재`(제한 끝 = 현재도 제한) | B1·B2·B3·B4(제한 끝 시각 단언) |

## 숨은 테스트 (`hidden/S3HiddenTest.kt` + `hidden/S3Fixtures.kt`, 7개)

사용자·빈 상품 사이즈·제한 시각만 SQL로 넣고 입찰·체결·결제·위시리스트·가격 알림·배치는 API로 만든다. 판매 제한은 판매 입찰을 건 **뒤** SQL로 건다(제한 중에는 판매 입찰 등록이 403). 시계는 앞으로만 상대 이동. 실행 순서는 이름순(순서에 기대는 단언 없음).

| 테스트 | 시나리오 | 단언 |
|---|---|---|
| B1 시세 | 제한 판매자 SELL 90,000 · 다른 판매자 SELL 100,000 · (판매 제한을 함께 건) 구매자 BUY 80,000 | 제한 중 (80,000, 100,000), 제한 끝 1초 전 같음, 제한 끝 = 현재 (80,000, 90,000). 두 입찰 ACTIVE·새 이벤트 없음 |
| B2 위시리스트 | 사이즈 A(제한 90,000 + 다른 100,000)·B(제한 70,000만) 위시 | 제한 중 {A: 100,000, B: null} → 제한 끝 {A: 90,000, B: 70,000}. 입찰 그대로 |
| B3 가격 알림 | A(제한 90,000·다른 100,000) 목표 95,000, B(제한 90,000·다른 93,000) 목표 95,000 → scan | A ACTIVE·발동 없음, B TRIGGERED(다른 판매자의 93,000). 제한 끝 뒤 scan → A TRIGGERED(제한 판매자의 90,000). 입찰 그대로 |
| B4 상대 | 제한 90,000 · 다른 100,000 | BUY 120,000 → 다른 판매자 100,000과 체결(체결가 100,000). BUY 95,000 → ACTIVE. 즉시 구매 → 409 NO_COUNTER_BID. 제한 입찰 그대로. 제한 끝 → 즉시 구매가 제한 판매자의 90,000과 체결 |
| B5 판매 쪽 그대로 | 판매 제한 사용자의 BUY 100,000 → 새 SELL 90,000, 그 사용자의 BUY 95,000(제한 중 등록 가능) → 즉시 판매 | 둘 다 그 사용자의 구매 입찰과 체결 |
| C1 이미 걸린 입찰 | `RestrictionAndConcurrencyTest` "제한은 새 입찰에만 - 이미 걸린 입찰의 체결과 결제…" 이전 | 제한된 구매자의 BUY는 새 SELL과 체결·결제 PAID(그대로), 제한된 판매자의 SELL은 새 BUY 130,000과 거래 안 됨(새 BUY ACTIVE, 판매 입찰 그대로) |
| C2 재계수 | `contract2/PenaltyTest` "제한을 건 패널티 뒤로 다시 센다" 이전(C2 숨은 '재계수'와 같은 준비 — 6개 모두 첫 제한 전에 결제·발송·입고) | t1+30일 → 그대로 → t3+30일 |

결정성: 단언은 테스트가 만든 사용자·사이즈·입찰·알림만 본다. `price-alert-scan`은 시드 알림도 함께 처리하지만 단언 대상과 겹치지 않는다(새 사이즈). 같은 시각에 대한 단언은 테스트가 정한 시각(`until`)과의 비교뿐이다.

### 숨은 테스트가 두 구조의 고칠 곳을 지나는지

| 테스트 | god | layered |
|---|---|---|
| B1 | `BidController` `/product-sizes/{id}/book` → `getBook` `:281` | `BidController.book` `:64` → `QueryServices.kt:35` → `JpaBidRepository.book` `:69` |
| B4·B5·C1 | `POST /bids` → `placeBid` `:210` → `findBestCounterBid` `:312-316` | `PlaceBidService.kt:43` → `bestCounter` `:50-54` |
| B2 | `GET /me/wishlist` → `wishlist` `:2628` | `WishlistService.kt:65` → `bestSell` `:71` |
| B3 | `price-alert-scan` → `scanPriceAlerts` `:2706` | `WishlistService.kt:163` → `triggeringSell` `:121` |

naive-1·2·3이 각각 해당 그룹 테스트에서만 실패하는 것으로 지점별 경유를 확인한다(아래 실측).

## 주석 처리 방침 (ADR-0005 §3.1·ADR-0008 §2)

- 변경 과제라 주입 패치가 없고, 에이전트가 보는 사본 = 원본이다. 바뀌는 규칙을 서술한 주석은 두 구조 모두 **지금 동작의 정확한 서술**이다(규칙과 코드가 어긋나는 주입 버그의 '정답 누설' 주석과 다르다). 그래서 중립화하지 않는다 — C2와 같다(C2도 "제한은 새 입찰에만" 주석 2곳을 두었다).
- 저장소 전체 grep(`체결 가능`·`최저 판매가`·`expires_at > 현재`·`새 입찰에만`·`이미 걸린 입찰`):
  - god: `:276`(getBook KDoc "체결 가능한(ACTIVE, 만료 시각 전)… 최저 판매가"), `:301`(findBestCounterBid KDoc "체결 가능 = ACTIVE이고 expires_at > 현재"), `:2620`(wishlist KDoc "…SELL 입찰 최저가(호가와 같은 기준)"), `:2690`(scan KDoc "체결 가능한 SELL"), `:200`(placeBid "제한은 새 입찰에만… 이미 걸려 있는 입찰의 체결은 막지 않는다"), `web/Dtos.kt:200`.
  - layered: `domain/Bid.kt:102·162·168`, `domain/Wishlist.kt:32·185·214`(포트 KDoc), `domain/User.kt:38`("이미 걸린 입찰의 체결은 막지 않는다").
  - 대칭: 네 고칠 곳마다 같은 뜻의 KDoc이 두 구조 모두 있다. 위치만 다르다 — god는 고칠 함수 바로 위, layered는 포트 인터페이스(다른 파일). 이것은 구조 차이(처치)라 맞추지 않는다. 단서 효과는 아래 표의 `호가`·`체결`·`최저 판매가` 행(god만 그룹 적중)에 드러난다.
  - "이미 걸린 입찰의 체결은 막지 않는다"(god `:200`, layered `User.kt:38`)는 새 규칙과 부분적으로 어긋나게 되지만(제한 판매자의 기존 판매 입찰은 새 구매 입찰과 체결되지 않는다), 고칠 곳이 아닌 등록 403 지점을 가리키고 두 구조 모두 있어 그대로 둔다. 정답 패치도 이 주석을 고치지 않는다(판정 범위를 넓히지 않게).

## 찾기 비용 자체 측정 (ADR-0008 §4.1 S3 변형 — 최종 판정은 리뷰어)

낱말마다 두 구조 `src/main`에 `grep -rnF`(대소문자 구분), 결과 중 고칠 줄(위 fix 줄) ±40줄 안에 드는 것이 닿는 그룹. S3 변형 조건 = 낱말마다 그룹 ≤ 2. (TASK.md·공개 테스트에 실제로 쓴 낱말 + 영어 직역 추측. 측정 스크립트 `clue.py` — 보고 packet 참고.)

| 낱말 | god 줄 | god 그룹(±40) | layered 줄 | layered 그룹(±40) | >2그룹 |
|---|---|---|---|---|---|
| 판매 제한 | 4 | - | 6 | - | |
| 판매 제한 중 | 2 | - | 2 | - | |
| 판매자 | 48 | - | 91 | - | |
| 판매 입찰 | 3 | - | 7 | - | |
| 판매(SELL) 입찰 | 0 | - | 0 | - | |
| SELL | 15 | alert,book,match,wishlist | 26 | alert,book,match,wishlist | **넘음** |
| sale_restricted_until | 4 | - | 7 | - | |
| users.sale_restricted_until | 0 | - | 0 | - | |
| 현재 시각 | 2 | - | 7 | - | |
| 403 | 11 | - | 28 | - | |
| 시세 | 3 | - | 9 | - | |
| /product-sizes/ | 5 | - | 3 | - | |
| /book | 1 | - | 1 | - | |
| 최저 판매가 | 1 | book,match | 1 | - | |
| 위시리스트 | 9 | wishlist | 13 | - | |
| /me/wishlist | 4 | wishlist | 3 | - | |
| 가격 알림 | 19 | alert,wishlist | 22 | - | |
| price-alert-scan | 3 | alert | 8 | - | |
| 목표가 | 2 | alert | 4 | - | |
| 발동 | 2 | - | 2 | - | |
| 구매 입찰 | 2 | match | 6 | - | |
| 즉시 구매 | 0 | - | 0 | - | |
| POST /bids | 0 | - | 0 | - | |
| /bids | 3 | - | 3 | - | |
| 거래 | 1 | - | 0 | - | |
| 상대 | 2 | - | 6 | - | |
| 다음 순서 | 0 | - | 0 | - | |
| 제한이 끝나 | 0 | - | 0 | - | |
| 입찰 이벤트 | 1 | - | 1 | - | |
| 유효기간 | 1 | - | 3 | - | |
| 최고 구매가 | 1 | book,match | 1 | - | |
| 즉시 판매 | 0 | - | 0 | - | |
| 구매 제한 | 4 | alert | 6 | alert | |
| MATCHED | 10 | match | 12 | - | |
| tradeId | 57 | book,match | 66 | - | |
| sell_bid_id | 1 | book | 1 | - | |
| trades | 21 | book | 53 | - | |
| ACTIVE | 45 | alert,book,match,wishlist | 62 | alert,book,match,wishlist | **넘음** |
| bids | 10 | alert,book,match,wishlist | 26 | alert,book,match,wishlist | **넘음** |
| status | 312 | alert,book,match,wishlist | 347 | alert,book,match,wishlist | **넘음** |
| validDays | 6 | - | 12 | - | |
| price | 92 | alert,book,match,wishlist | 103 | alert,book,match,wishlist | **넘음** |
| 입찰 | 35 | alert,book,match,wishlist | 52 | - | **넘음** |
| 체결 | 46 | alert,book,match | 72 | - | **넘음** |
| 호가 | 4 | book,match,wishlist | 4 | - | **넘음** |
| bestSell | 7 | book,match,wishlist | 12 | book,match,wishlist | **넘음** |
| restrict | 22 | - | 35 | alert | |
| sale | 18 | - | 29 | - | |
| wishlist | 9 | wishlist | 7 | alert | |
| alert | 72 | alert,wishlist | 91 | alert,wishlist | |
| counter | 9 | book,match | 9 | book,match | |
| match | 19 | match | 24 | - | |
| instant | 7 | - | 10 | - | |
| book | 2 | - | 7 | book,match | |
| trigger | 5 | alert | 11 | alert | |

판단:
- TASK.md 문구는 조건을 넘는 낱말(`호가`·`체결`·`bestSell`·`ACTIVE`)을 쓰지 않게 고쳤다 — "시세", "거래될 상대", "최저 판매가"로 썼다.
- **조건을 넘는 낱말이 남는다(해소 불가 — 메인 판단 필요):** `SELL`(4/4 두 구조), `bids`·`ACTIVE`·`status`·`price`(4/4 두 구조), `입찰`(god 4/layered 0). 네 고칠 곳이 모두 "SELL 입찰을 읽는 SQL"이라 과제 대상 자체를 가리키는 낱말이 네 곳 모두에 붙어 있다. `SELL`은 TASK.md의 "판매(SELL) 입찰"과 공개 테스트의 `"SELL"`에 있고, 빼도 "판매 입찰"의 직역 추측으로 나온다. `SELL` 결과는 god 15줄·layered 26줄이라 결과를 다 훑으면 네 곳에 닿는다 — ADR §4.2 S3 행이 예상한 단서(`expires_at > :now` 6/6 — 과제 문구에 없음)보다 강하다.
- 비대칭: `호가`·`체결`·`최저 판매가`·`최고 구매가`·`입찰`·`MATCHED`는 god만 그룹에 닿는다(KDoc이 고칠 함수 바로 위에 있음 — 처치). `restrict`·`book`·`wishlist`는 layered만 닿는다(어댑터 이름·인접 코드 — 처치).

## regression-exclude · verify-exclude

- regression-exclude 2메서드(파일 참고): `contract/RestrictionAndConcurrencyTest` "제한은 새 입찰에만 - 이미 걸린 입찰의 체결…" → 숨은 C1, `contract2/PenaltyTest` "제한을 건 패널티 뒤로 다시 센다" → 숨은 C2. 판정기(judge.py)는 갱신본 테스트를 지원하지 않아 ADR §4.2의 '갱신본' 대신 메서드 제외 + 갱신 단언 숨은 테스트 이전으로 했다(브리프 지시).
- 전수 grep(contract1~3, `sale_restricted_until`·`saleRestricted`·`setRestrictions`·`createUser(saleRestrictedUntil`·`판매 제한`): 위 2메서드 밖의 판매 제한 사용자는 입찰이 없거나(`RestrictionAndConcurrencyTest` 403 테스트, `A3PayoutAccountTest`, `A12InboundTest`, `A4DeadlineReminderTest`, `A4Notification`·`A17*`(`restrictionNotifs` — 입찰 없는 사용자), `A9AdminQueryTest`), 제한 전에 판매를 끝냈거나(`A2SellerGradeTest` — `confirmedSale` 뒤 `setRestrictions`), 제한 뒤 새 판매 입찰 403만 본다(`PenaltyTest` 3건째 테스트). 패널티 누적으로 제한이 걸리는 경로는 정적 grep으로 다 가릴 수 없어 정답 전 단계 check(회귀 3단계 + verify)로 확인했다(아래).
- verify-exclude: 없음 — 정답(fix) 전 단계에서 세 회귀 단계 verify 위반 0(두 구조). 새 규칙은 원리상 `book_never_crossed`·`trade_price_time_priority`·`active_book_no_cross`(verify.sql은 판매 제한을 모른다)를 깰 수 있지만, 그런 장면(제한 판매자의 판매 입찰과 교차하는 새 구매 입찰)을 만드는 회귀 테스트는 제외한 `RestrictionAndConcurrencyTest` 메서드뿐이고 숨은 단계는 verify를 돌리지 않는다

## 채택 조건 실측 (스모크 — 채택 행렬은 메인이 고정 SHA에서)

2026-11-01 10:49 ~ 13:01, 기준 HEAD 6d02f1f(`code_dirty` True — experiment/ 미커밋: S1·S2·S3 작성 중), `runner.py check` 1레인 순차. 결과 행 = 스크래치 `S3-checks/checks.jsonl`(19행).

| 구조 | 패치 | 단계 | success | 결과 |
|---|---|---|---|---|
| god | fix | public,hidden | True | public 1/1; hidden 7/7 |
| god | fix | public,hidden,contract,contract2,contract3 | True | public 1/1; hidden 7/7; contract 121/121 verify 위반 0; contract2 88/88 verify 위반 0; contract3 252/252 verify 위반 0 |
| god | none | public,hidden | False | public 0/1; hidden 2/7 (실패: B1·B2·B3·B4·C1) |
| god | naive-1 | hidden | False | hidden 3/7 (실패: B2·B3·B4·C1) |
| god | naive-2 | hidden | False | hidden 4/7 (실패: B1·B2·B3) |
| god | naive-3 | hidden | False | hidden 5/7 (실패: B2·B3) |
| god | naive-4 | hidden | False | hidden 5/7 (실패: B1·B5) |
| god | naive-5 | hidden | False | hidden 2/7 (실패: B1·B2·B3·B4·C1) |
| god | naive-6 | hidden | False | hidden 3/7 (실패: B1·B2·B3·B4) |
| layered | fix | public,hidden,contract,contract2,contract3 | True | public 1/1; hidden 7/7; contract 121/121 verify 위반 0; contract2 88/88 verify 위반 0; contract3 252/252 verify 위반 0 |
| layered | none | public,hidden | False | public 0/1; hidden 2/7 (실패: B1·B2·B3·B4·C1) |
| layered | naive-1 | hidden | False | hidden 3/7 (실패: B2·B3·B4·C1) |
| layered | naive-2 | hidden | False | hidden 4/7 (실패: B1·B2·B3) |
| layered | naive-3 | hidden | False | hidden 5/7 (실패: B2·B3) |
| layered | naive-4 | hidden | False | hidden 5/7 (실패: B1·B5) |
| layered | naive-5 | hidden | False | hidden 2/7 (실패: B1·B2·B3·B4·C1) |
| layered | naive-6 | hidden | False | hidden 3/7 (실패: B1·B2·B3·B4) |
| god | alt-1 | public,hidden,contract,contract2,contract3 | True | public 1/1; hidden 7/7; contract 121/121 verify 위반 0; contract2 88/88 verify 위반 0; contract3 252/252 verify 위반 0 |
| layered | alt-1 | public,hidden,contract,contract2,contract3 | True | public 1/1; hidden 7/7; contract 121/121 verify 위반 0; contract2 88/88 verify 위반 0; contract3 252/252 verify 위반 0 |

숨은 실패 수(두 구조 같음 — §4.3-2):

| 패치 | god | layered | 실패 테스트 |
|---|---|---|---|
| none | 5 | 5 | B1·B2·B3·B4·C1 |
| naive-1 | 4 | 4 | B2·B3·B4·C1 |
| naive-2 | 3 | 3 | B1·B2·B3 |
| naive-3 | 2 | 2 | B2·B3 |
| naive-4 | 2 | 2 | B1·B5 |
| naive-5 | 5 | 5 | B1·B2·B3·B4·C1 |
| naive-6 | 4 | 4 | B1·B2·B3·B4 |

- 정답·alt-1: 두 구조 전 단계 success(contract 121 = 122 − 제외 1, contract2 88 = 89 − 제외 1, contract3 252, verify 위반 0 세 단계). 회귀 제외 2메서드 밖에서 새 규칙에 걸리는 계약 테스트 없음(전수 grep 결론을 실측으로 확인).
- 숨은 테스트 5회 결정성·판정 2회 재현(§4.1)은 이 스모크에서 돌리지 않았다 — 메인의 고정 SHA 채택 행렬 몫.

## 그룹 도달 검사 (§4.3-4, `fce.patch_ranges` + `fce.FileMap.apply`)

패치 하나를 편집 사건 하나로 보고, 재생기와 같은 줄 대응(`FileMap.apply`)으로 바뀐 줄을 구해 `(fix ∪ alt) ∩ 그룹`을 본다.

| 구조 | 패치 | 바뀐 줄(재생기 FileMap) | 첫 정답 편집 적중 | 도달 그룹 |
|---|---|---|---|---|
| god | fix | Marketplace.kt:281,312,316,2628,2706 | 적중 | book,match,wishlist,alert (4/4) |
| god | alt-1 | Marketplace.kt:115,281,312,316,2628,2706 | 적중 | book,match,wishlist,alert (4/4) |
| god | naive-1 | Marketplace.kt:281 | 적중 | book |
| god | naive-2 | Marketplace.kt:312,316 | 적중 | match |
| god | naive-3 | Marketplace.kt:281,312,316 | 적중 | book,match |
| god | naive-4 | Marketplace.kt:283,312,316,2628,2706 | 적중 | match,wishlist,alert |
| god | naive-5 | Marketplace.kt:253,1028,1478 | 비적중 | - |
| god | naive-6 | Marketplace.kt:281,312,316,2628,2706 | 적중 | book,match,wishlist,alert (4/4) |
| layered | fix | JpaBidRepositories.kt:50,54,69; JpaNotificationRepositories.kt:71,121 | 적중 | book,match,wishlist,alert (4/4) |
| layered | alt-1 | JpaBidRepositories.kt:50,54,69,81; JpaNotificationRepositories.kt:71,121 | 적중 | book,match,wishlist,alert (4/4) |
| layered | naive-1 | JpaBidRepositories.kt:69 | 적중 | book |
| layered | naive-2 | JpaBidRepositories.kt:50,54 | 적중 | match |
| layered | naive-3 | JpaBidRepositories.kt:50,54,69 | 적중 | book,match |
| layered | naive-4 | JpaBidRepositories.kt:50,54,71; JpaNotificationRepositories.kt:71,121 | 적중 | match,wishlist,alert |
| layered | naive-5 | User.kt:57,59,62,68,70,73; Bid.kt:160; BatchService.kt:171,190; CancelBidService.kt:8,10,35; ItemRefunder.kt:75,84; JpaBidRepositories.kt:42 | 비적중 | - |
| layered | naive-6 | JpaBidRepositories.kt:50,54,69; JpaNotificationRepositories.kt:71,121 | 적중 | book,match,wishlist,alert (4/4) |

- fix·alt-1: 4그룹 모두 도달. 일부만 고친 naive-1·2·3: 미도달(1·1·2그룹). naive-4(구매 쪽까지): 시세 줄을 안 건드리고 WHERE(`:283`/`:71`)를 고쳐 3그룹 — 미도달이면서 숨은 테스트 실패. naive-6(경계): 4그룹 도달하지만 숨은 테스트 실패. naive-5(CANCELLED): 비적중.
- 첫 정답 편집(§5.1)으로는 naive-1~4·6이 적중이다 — S3의 주 지표는 모든 그룹 도달이라 §4.3-4의 S3 문장("일부만 고친 오답은 미도달")으로 판정한다.
