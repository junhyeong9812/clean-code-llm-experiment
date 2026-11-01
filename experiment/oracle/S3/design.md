# S3 — 판매 제한 중인 판매자의 판매 입찰 감추기 (change, 흩어진 규칙)

> 정본: ADR-0008 §4.2 S3 행·§4.3·§5.3. 좌표는 기준 사본(원본 + `inject.*.patch` — 주석만 바꾸고 줄 수 유지라 원본과 같은 좌표). 작성 기준 HEAD 6d02f1f.

## 의도

같은 새 규칙을 원시 SQL 네 벌(시세·체결 상대·위시리스트·가격 알림)에 빠짐없이 넣어야 하는 과제다(ADR-0008 유형 '흩어진 규칙'). 주 지표는 **모든 고칠 곳 도달 턴**(§5.3). 동작 주입은 없다(TYPE=change). `inject.*.patch`는 바뀌는 규칙을 서술한 주석만 중립 문장으로 바꾼다(아래 '주석 처리 방침' — 메인 결정 2026-11-01, 러너 `build_work`는 TYPE과 무관하게 inject를 적용한다 `runner.py:147-149`). 기준 상태 = 제한 중 판매자의 판매 입찰이 네 곳 모두에 그대로 잡힌다 → 숨은 테스트 실패.

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
- 판정 범위 줄 수(§5.2, fix ∪ alt): god **11줄**(fix 7 + alt-1 2 + alt-2 2 — `:283`·`:315`) / layered **11줄**(fix 7 + alt-1 2 + alt-2 2 — `JpaBidRepositories.kt:53`·`:71`). alt-2 등록 전 9 / 9.
- `groups.json`은 alt-2 등록에도 **바꾸지 않았다** — alt-2 줄(god `:283`·`:315`, layered `JpaBidRepositories.kt:71`·`:53`)이 이미 book·match 그룹 범위(`:279-285`·`:305-326`, `:66-75`·`:43-64`) 안에 있고, wishlist·alert의 alt-2 줄은 fix 줄과 같다.

## 대안 정답

- **alt-1 (공통 조건 조각 한 곳):** 성립한다. god는 파일 최상위 `private const val SELLER_NOT_RESTRICTED`(import 뒤 `:116-117` 삽입), layered는 `JpaBidRepositories.kt` 끝에 `internal object SaleRestrictionSql { const val SELLER_OPEN }`(`:81-82` 삽입 — 같은 패키지의 `JpaNotificationRepositories.kt`가 쓴다, 기존 `PriceAlertRearmSql`과 같은 모양). 네 쿼리는 문자열 템플릿으로 그 상수를 붙인다 — 사용처 줄은 정답과 같은 줄이라 각 그룹에 그대로 속한다. **정의 줄(god `:116-117`, layered `:81-82`)은 어느 그룹에도 넣지 않는다** — 정의만 하고 사용처를 안 고치면 아무 그룹에도 닿지 않아야 하므로(첫 정답 편집 턴에는 센다 — §5.2 alt 범위).
  - 재생기 주의: god 정의 삽입은 빈 줄 경계라 재생기(difflib)가 앵커를 `:115`로 잡는다(패치 범위는 `:116-117`). 정의만 먼저 넣는 실행은 첫 정답 편집으로 잡히지 않을 수 있다 — 그룹 도달(주 지표)에는 영향 없음.
- **alt-2 (WHERE 줄 형태, 듀얼 리뷰 지적 2026-11-01 등록):** 성립한다. 시세·체결 상대 쿼리의 WHERE 줄 끝에 `AND (side = 'BUY' OR <조건>)`(god `:283`·`:315`, layered `JpaBidRepositories.kt:71`·`:53`), 위시리스트·가격 알림은 WHERE 줄(= 쿼리 줄 god `:2628`·`:2706`, layered `JpaNotificationRepositories.kt:71`·`:121`) 끝에 `AND <조건>`. 의미: 시세는 BUY 행을 모두 남기고 제한 판매자의 SELL 행만 빼므로 `max(BUY)`는 그대로·`min(SELL)`만 걸러진다(fix의 FILTER 형태와 같은 결과). 체결 상대는 행의 `side`(= 상대 쪽)로 가리므로 상대가 SELL일 때만 조건이 걸린다(fix의 `if (side == "BUY")`와 같은 결과). 두 구조 같은 문자열·같은 위치 — 의미 차이 없음.
  - **WHERE 줄 뒤에 새 줄을 넣는 변형**(god `:283` 뒤 `AND (side = 'BUY' OR …)`, `:315` 뒤 `" AND (side = 'BUY' OR …)" +`; layered `:71`·`:53` 뒤)은 별도 패치로 등록하지 않았다 — 재생기(`FileMap.apply`)가 순수 삽입의 앵커를 WHERE 줄로 잡아 alt-2 범위에 그대로 닿는다(스크래치 변형 패치로 확인: 두 구조 4/4).
- 숨은 테스트는 결과만 보므로 `LEFT JOIN`·`EXISTS` 대신 `user_id NOT IN (…)`·서비스 층 거르기 등도 통과한다. 판정 범위(fix ∪ alt-1 ∪ alt-2) 밖 줄만 고친 정답은 그 그룹 도달로 잡히지 않는다(§5.2 넓히는 규칙 없음 — 성공 + 미도달이면 fce_fallback).

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

## 주석 처리 방침 (ADR-0005 §3.1·ADR-0008 §2 — 메인 결정 2026-11-01)

- 바뀌는 규칙 = "체결 가능(상대·시세·위시리스트·알림에 잡히는 입찰) = ACTIVE이고 만료 시각 전"과 "제한은 새 입찰에만, 이미 걸린 입찰의 체결은 막지 않는다". 이를 서술한 주석을 두 구조 모두 `inject.{god,layered}.patch`로 **중립 문장**으로 바꾼다(줄 수 유지, 그 밖의 주석은 그대로). 처음 판단(지금 동작의 정확한 서술이라 둔다 — C2 식)은 메인 결정으로 바꿨다.
- 저장소 전체 grep(`체결 가능`·`만료 시각 전`·`expires_at > 현재`·`새 입찰에만`·`이미 걸`·`호가와 같은 기준`·`bestSell =`·`최저 판매가`)으로 찾은 대상과 바꾼 문장:

| 구조 | 위치 | 전 | 후 |
|---|---|---|---|
| god | `Marketplace.kt:200` | 제한은 새 입찰에만 건다. 이미 걸려 있는 입찰의 체결은 막지 않는다 | 새 입찰 제한(§3.1): 구매는 purchase_restricted_until, 판매는 sale_restricted_until이 현재보다 뒤이면 403 |
| god | `:276` | 호가(§3.4): 체결 가능한(ACTIVE, 만료 시각 전) 입찰의 최고 구매가·최저 판매가 | 호가(§3.4): 최고 구매가·최저 판매가 |
| god | `:301` | 반대편 최우선 체결 가능 입찰(§3.3). 체결 가능 = ACTIVE이고 expires_at > 현재. | 반대편 최우선 입찰(§3.3). |
| god | `:2620` | bestSell = ACTIVE이고 expires_at > 현재인 SELL 입찰 최저가(호가와 같은 기준) | bestSell = 그 사이즈의 SELL 최저가 |
| god | `:2690` | 3 그 사이즈의 체결 가능한 SELL 중 | 3 그 사이즈의 SELL 중 |
| god | `web/Dtos.kt:200` | bestSell = 체결 가능한 SELL 최저가 | bestSell = SELL 최저가 |
| layered | `domain/User.kt:38` | …판매는 sale_restricted_until. 이미 걸린 입찰의 체결은 막지 않는다. | …판매는 sale_restricted_until. |
| layered | `domain/Bid.kt:102` | 체결 가능(§3.3): ACTIVE이고 expires_at > 현재. 결제 기한 만료로 복구된 판매 입찰은 이것이면 바로 재매칭한다. | 결제 기한 만료로 복구된 판매 입찰을 바로 재매칭할지(§3.3). |
| layered | `domain/Bid.kt:162` | (god `:301`과 같은 문장) | 반대편 최우선 입찰(§3.3). |
| layered | `domain/Bid.kt:168` | 호가(§3.4): 체결 가능한 입찰의 최고 구매가·최저 판매가 | 호가(§3.4): 최고 구매가·최저 판매가 |
| layered | `domain/Wishlist.kt:32` | bestSell = 체결 가능한 SELL 최저가 | bestSell = SELL 최저가 |
| layered | `domain/Wishlist.kt:185` | 그 사이즈의 ACTIVE이고 expires_at > 현재인 SELL 입찰 최저가(호가와 같은 기준), 없으면 null | 그 사이즈의 SELL 최저가, 없으면 null |
| layered | `domain/Wishlist.kt:214` | 그 사이즈의 ACTIVE·expires_at > 현재·price ≤ target인 SELL 입찰 중… | 그 사이즈의 price ≤ target인 SELL 입찰 중… |

- 두 구조 같은 방식: 같은 뜻의 문장을 같은 중립 문장으로 바꿨다(god `:301` = layered `Bid.kt:162`, god `:276` = layered `Bid.kt:168` 등). 중립 문장에 TASK.md 낱말을 새로 넣지 않는다 — 처음 안에서 `:301`을 "반대편 최우선 **상대** 입찰"로 쓰자 `상대`(TASK.md 낱말)가 god book·match에 새로 닿아 "반대편 최우선 입찰"로 고쳤다.
- 남긴 것: bid-expiry·point-expiry의 `expires_at ≤ 현재`(바뀌지 않는 규칙), god `:1486` 재매칭 주석(유효기간 판정 — 바뀌지 않음), layered `WishlistService.kt:142`("맞는 SELL 입찰" — 이미 중립).
- 위치 차이(god는 고칠 함수 바로 위 KDoc, layered는 포트 인터페이스 — 다른 파일)는 구조 차이(처치)라 맞추지 않는다.
- 정답·대안·오답 패치는 이 주석 줄을 건드리지 않는다. inject 뒤 사본에 offset 0·fuzz 0으로 적용된다(naive-5.layered = C2 정답은 `Bid.kt:162`가 문맥 줄이라 inject 위로 다시 만들었다 — 내용 같음).

## 찾기 비용 측정 — **S3 예외** (메인 결정 2026-11-01, S4와 같은 처리: 표만 남긴다)

**예외 — SELL·bids가 공개 API 낱말이라 4그룹 동시 도달.** 네 고칠 곳이 모두 "SELL 입찰을 읽는 SQL"이라 과제 대상 자체를 가리키는 낱말(`SELL`·`bids`, 그리고 `ACTIVE`·`status`·`price`·`expires_at`)이 두 구조 모두 4그룹에 닿는다. `SELL`은 공개 API의 `side` 값이라 TASK.md·공개 테스트에서 뺄 수 없고, 빼도 "판매 입찰"의 직역 추측으로 나온다. 그래서 ADR-0008 §4.1 S3 변형 조건(낱말마다 그룹 ≤ 2)은 적용하지 않고 측정표만 남긴다.

측정: 낱말마다 두 구조 기준 사본(원본 + inject)의 `src/main`에 `grep -rnF`(대소문자 구분), 결과 중 고칠 줄(fix 줄) ±40줄 안에 드는 것이 닿는 그룹. 낱말 = TASK.md·공개 테스트에 쓴 것 + 영어 직역 추측(작성자 측정 — 리뷰어 측정은 별도).

| 낱말 | god 줄 | god 그룹(±40) | layered 줄 | layered 그룹(±40) | >2그룹 |
|---|---|---|---|---|---|
| 판매 제한 | 4 | - | 6 | - | |
| 판매 제한 중 | 2 | - | 2 | - | |
| 판매자 | 48 | - | 91 | - | |
| 판매 입찰 | 3 | - | 7 | - | |
| 판매(SELL) 입찰 | 0 | - | 0 | - | |
| SELL | 15 | alert,book,match,wishlist | 26 | alert,book,match,wishlist | **넘음** |
| sale_restricted_until | 5 | - | 7 | - | |
| users.sale_restricted_until | 0 | - | 0 | - | |
| 현재 시각 | 2 | - | 7 | - | |
| 403 | 12 | - | 28 | - | |
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
| ACTIVE | 42 | alert,book,match,wishlist | 58 | alert,book,match,wishlist | **넘음** |
| bids | 10 | alert,book,match,wishlist | 26 | alert,book,match,wishlist | **넘음** |
| status | 312 | alert,book,match,wishlist | 347 | alert,book,match,wishlist | **넘음** |
| validDays | 6 | - | 12 | - | |
| price | 92 | alert,book,match,wishlist | 103 | alert,book,match,wishlist | **넘음** |
| 입찰 | 33 | alert,book,match,wishlist | 50 | - | **넘음** |
| 체결 | 41 | book,match | 67 | - | |
| 호가 | 3 | book,match | 3 | - | |
| bestSell | 7 | book,match,wishlist | 12 | book,match,wishlist | **넘음** |
| restrict | 23 | - | 35 | alert | |
| sale | 19 | - | 29 | - | |
| wishlist | 9 | wishlist | 7 | alert | |
| alert | 72 | alert,wishlist | 91 | alert,wishlist | |
| counter | 9 | book,match | 9 | book,match | |
| match | 19 | match | 24 | - | |
| instant | 7 | - | 10 | - | |
| book | 2 | - | 7 | book,match | |
| trigger | 5 | alert | 11 | alert | |
| 체결 가능 | 1 | - | 1 | - | |
| expires_at | 19 | alert,book,match,wishlist | 29 | alert,book,match,wishlist | **넘음** |
| 제한 중 | 5 | alert(`:2690` "구매 제한 중" KDoc — **직접 단서**, ≤10줄) | 6 | - | |
| product_sizes | 6 | book,match,wishlist(`:294` 잠금 SQL·`:2602` — **직접 단서**, ≤10줄) | 6 | match,wishlist(`JpaBidRepositories.kt:27`·`JpaNotificationRepositories.kt:65` — **직접 단서**) | **넘음**(god) |
| side | 22 | alert,book,match,wishlist | 33 | alert,book,match,wishlist | **넘음** |
| productSizeId | 89 | alert,book,match,wishlist | 127 | alert,book,match,wishlist | **넘음** |
| bidId | 31 | alert,book,match | 31 | - | **넘음**(god) |

- 추가 5낱말(`제한 중`·`product_sizes`·`side`·`productSizeId`·`bidId`)은 독립 측정이 작성자 표에서 빠졌다고 지적한 것이다(2026-11-01 15시 같은 방법으로 재측정 — 기준 사본 = 원본 + inject, ±40은 fix 줄 기준). `제한 중`은 god에서만 alert 그룹 직접 단서(`판매 제한 중` 2줄은 그룹 밖 — 닿는 것은 "구매 제한 중" 주석). `product_sizes`는 두 구조 모두 직접 단서(사이즈 잠금·존재 확인 SQL이 고칠 쿼리 옆에 있음) — god 3그룹, layered 2그룹으로 비대칭. `side`·`productSizeId`는 공개 API 필드 이름이라 4그룹(SELL·bids와 같은 성질 — S3 예외 근거와 같다). `bidId`는 god만 3그룹(placeBid 응답·알림 발동 코드가 고칠 함수 옆).
- TASK.md는 조정 가능한 낱말(`호가`·`체결`·`bestSell`·`ACTIVE`)을 쓰지 않는다("시세", "거래될 상대", "최저 판매가").
- 주석 중립화 효과(inject 전 → 후, god): `호가` 3 → 2그룹, `체결` 3 → 2그룹, `체결 가능` 0(전부 사라짐 — 남은 1줄은 예외 메시지 `:212`), `입찰`·`ACTIVE`는 여전히 4그룹(SQL·코드 자체).
- 비대칭(처치로 표시): `최저 판매가`·`최고 구매가`·`호가`·`체결`·`입찰`·`MATCHED`·`tradeId`는 god만 그룹에 닿는다(고칠 함수 근처 KDoc·코드). `restrict`·`book`·`wishlist`는 layered만 닿는다(어댑터 이름·인접 코드).

## regression-exclude · verify-exclude

- regression-exclude 2메서드(파일 참고): `contract/RestrictionAndConcurrencyTest` "제한은 새 입찰에만 - 이미 걸린 입찰의 체결…" → 숨은 C1, `contract2/PenaltyTest` "제한을 건 패널티 뒤로 다시 센다" → 숨은 C2. 판정기(judge.py)는 갱신본 테스트를 지원하지 않아 ADR §4.2의 '갱신본' 대신 메서드 제외 + 갱신 단언 숨은 테스트 이전으로 했다(브리프 지시).
- 전수 grep(contract1~3, `sale_restricted_until`·`saleRestricted`·`setRestrictions`·`createUser(saleRestrictedUntil`·`판매 제한`): 위 2메서드 밖의 판매 제한 사용자는 입찰이 없거나(`RestrictionAndConcurrencyTest` 403 테스트, `A3PayoutAccountTest`, `A12InboundTest`, `A4DeadlineReminderTest`, `A4Notification`·`A17*`(`restrictionNotifs` — 입찰 없는 사용자), `A9AdminQueryTest`), 제한 전에 판매를 끝냈거나(`A2SellerGradeTest` — `confirmedSale` 뒤 `setRestrictions`), 제한 뒤 새 판매 입찰 403만 본다(`PenaltyTest` 3건째 테스트). 패널티 누적으로 제한이 걸리는 경로는 정적 grep으로 다 가릴 수 없어 정답 전 단계 check(회귀 3단계 + verify)로 확인했다(아래).
- verify-exclude: 없음 — 정답(fix) 전 단계에서 세 회귀 단계 verify 위반 0(두 구조). 새 규칙은 원리상 `book_never_crossed`·`trade_price_time_priority`·`active_book_no_cross`(verify.sql은 판매 제한을 모른다)를 깰 수 있지만, 그런 장면(제한 판매자의 판매 입찰과 교차하는 새 구매 입찰)을 만드는 회귀 테스트는 제외한 `RestrictionAndConcurrencyTest` 메서드뿐이고 숨은 단계는 verify를 돌리지 않는다

## 채택 조건 실측 (스모크 — 채택 행렬은 메인이 고정 SHA에서)

2026-11-01 13:09 ~ 15:02(주석 중립화 inject 반영 뒤 재실행), 기준 HEAD ac21422(`code_dirty` True — experiment/ 미커밋), `runner.py check` 1레인 순차. 결과 행 = 스크래치 `S3-checks-v2/checks.jsonl`(18행). 앞 실행(inject 전, 13:01까지 19행 `S3-checks/`)도 같은 결과였다.

| 구조 | 패치 | 단계 | success | 결과 |
|---|---|---|---|---|
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
| god | alt-2 | public,hidden,contract,contract2,contract3 | True | public 1/1; hidden 7/7; contract 121/121 verify 위반 0; contract2 88/88 verify 위반 0; contract3 252/252 verify 위반 0 (2026-11-01 15:59, code_sha 291176c dirty, `<scratchpad>/sct/S3-checks-alt2/checks.jsonl`) |
| layered | alt-2 | public,hidden,contract,contract2,contract3 | True | 〃 (16:15) |

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

- 정답·alt-1·alt-2: 두 구조 전 단계 success(contract 121 = 122 − 제외 1, contract2 88 = 89 − 제외 1, contract3 252, verify 위반 0 세 단계). 회귀 제외 2메서드 밖에서 새 규칙에 걸리는 계약 테스트 없음(전수 grep 결론을 실측으로 확인).
- 숨은 테스트 5회 결정성·판정 2회 재현(§4.1)은 이 스모크에서 돌리지 않았다 — 메인의 고정 SHA 채택 행렬 몫.

## 그룹 도달 검사 (§4.3-4, `fce.patch_ranges` + `fce.FileMap.apply`)

기준 사본 = 원본 + inject(주석만 — 줄 수 유지라 inject 전 결과와 같다). 패치 하나를 편집 사건 하나로 보고, 재생기와 같은 줄 대응(`FileMap.apply`)으로 바뀐 줄을 구해 `(fix ∪ alt) ∩ 그룹`을 본다.

| 구조 | 패치 | 바뀐 줄(재생기 FileMap) | 첫 정답 편집 적중 | 도달 그룹 |
|---|---|---|---|---|
| god | fix | Marketplace.kt:281,312,316,2628,2706 | 적중 | book,match,wishlist,alert (4/4) |
| god | alt-1 | Marketplace.kt:115,281,312,316,2628,2706 | 적중 | book,match,wishlist,alert (4/4) |
| god | naive-1 | Marketplace.kt:281 | 적중 | book |
| god | naive-2 | Marketplace.kt:312,316 | 적중 | match |
| god | naive-3 | Marketplace.kt:281,312,316 | 적중 | book,match |
| god | alt-2 | Marketplace.kt:283,315,2628,2706 | 적중 | book,match,wishlist,alert (4/4) — 등록 전 wishlist,alert |
| god | alt-2 삽입 변형(스크래치) | Marketplace.kt:283,315,2628,2706 | 적중 | book,match,wishlist,alert (4/4) |
| god | naive-4 | Marketplace.kt:283,312,316,2628,2706 | 적중 | **book**,match,wishlist,alert (**4/4** — alt-2 등록 전 3그룹) |
| god | naive-5 | Marketplace.kt:253,1028,1478 | 비적중 | - |
| god | naive-6 | Marketplace.kt:281,312,316,2628,2706 | 적중 | book,match,wishlist,alert (4/4) |
| layered | fix | JpaBidRepositories.kt:50,54,69; JpaNotificationRepositories.kt:71,121 | 적중 | book,match,wishlist,alert (4/4) |
| layered | alt-1 | JpaBidRepositories.kt:50,54,69,81; JpaNotificationRepositories.kt:71,121 | 적중 | book,match,wishlist,alert (4/4) |
| layered | naive-1 | JpaBidRepositories.kt:69 | 적중 | book |
| layered | naive-2 | JpaBidRepositories.kt:50,54 | 적중 | match |
| layered | naive-3 | JpaBidRepositories.kt:50,54,69 | 적중 | book,match |
| layered | alt-2 | JpaBidRepositories.kt:53,71; JpaNotificationRepositories.kt:71,121 | 적중 | book,match,wishlist,alert (4/4) — 등록 전 wishlist,alert |
| layered | alt-2 삽입 변형(스크래치) | JpaBidRepositories.kt:53,71; JpaNotificationRepositories.kt:71,121 | 적중 | book,match,wishlist,alert (4/4) |
| layered | naive-4 | JpaBidRepositories.kt:50,54,71; JpaNotificationRepositories.kt:71,121 | 적중 | **book**,match,wishlist,alert (**4/4** — alt-2 등록 전 3그룹) |
| layered | naive-5 | User.kt:57,59,62,68,70,73; Bid.kt:160; BatchService.kt:171,190; CancelBidService.kt:8,10,35; ItemRefunder.kt:75,84; JpaBidRepositories.kt:42 | 비적중 | - |
| layered | naive-6 | JpaBidRepositories.kt:50,54,69; JpaNotificationRepositories.kt:71,121 | 적중 | book,match,wishlist,alert (4/4) |

- fix·alt-1·alt-2: 4그룹 모두 도달. 일부만 고친 naive-1·2·3: 미도달(1·1·2그룹 — alt-2 등록 전후 같음). **naive-4(구매 쪽까지): alt-2 등록으로 3 → 4그룹** — 시세 WHERE(`:283`/`:71`)에 조건을 거는 줄이 alt-2(WHERE 형태)와 같은 줄이라 범위로는 가를 수 없다(정답은 `side = 'BUY' OR …`, 오답은 그것이 없음 — 내용 차이). naive-6과 같은 처지: 4/4 도달이지만 숨은 테스트 실패라 실패 실행의 지표는 +inf(§5.1). naive-5: 비적중(전후 같음). naive-6(경계): 4그룹 도달하지만 숨은 테스트 실패. naive-5(CANCELLED): 비적중.
- 첫 정답 편집(§5.1)으로는 naive-1~4·6이 적중이다 — S3의 주 지표는 모든 그룹 도달이라 §4.3-4의 S3 문장("일부만 고친 오답은 미도달")으로 판정한다.
