# C2 — 제한이 걸리면 그쪽 활성 입찰 취소 (change)

## 의도

본보기가 없는 새 규칙을 두 구조의 흐름 두 곳(판매 제한·구매 제한)에 같이 넣는 과제다. 주입은 없다(기준 상태 = 제한이 걸려도 ACTIVE 입찰이 그대로 → 숨은 테스트 실패). 효과는 입찰 상태·이벤트·호가·매칭에서 보이지만 고칠 곳은 검수·발송 기한·결제 기한 흐름 안이다(분리). ADR-0005 §3.2 개정 2 행.

## 계약 (TASK.md 전문의 요약)

- 판매 제한(판매자 귀책 패널티 — 검수 불합격 FAIL 또는 `shipment-deadline`)이 **새로 걸리거나 갱신되는 순간** 그 판매자의 ACTIVE SELL 입찰 전부 → CANCELLED. 구매 제한(`payment-due`의 경고)이면 그 구매자의 ACTIVE BUY 입찰 전부.
- 대상 = `status = 'ACTIVE'`인 그쪽 입찰 전부(유효기간이 지났지만 bid-expiry 전인 것 포함). 반대쪽·MATCHED·EXPIRED·CANCELLED는 그대로.
- 취소마다 `bid_events` 1행: ACTIVE → CANCELLED, trade_id NULL, created_at = 그 처리의 현재.
- 제한 시각을 바꾸지 않는 패널티·경고는 아무것도 취소하지 않는다. 제한 판정식(30일 안, 마지막 제한 뒤로 다시 센 3건)은 그대로.
- `payment-due`에서는 판매 입찰 복구·재매칭보다 먼저.
- 기존 "제한은 새 입찰 등록에만" 규칙(ADR-0003 §3.1 [기본값], god `Marketplace.kt:138` 주석, layered `domain/User.kt:38` KDoc)과의 충돌은 TASK.md 문장("제한이 걸리는 순간 이미 있는 그쪽 ACTIVE 입찰…을 취소한다. 그 밖의 … 기존 규칙은 그대로다")으로 해소했다. 두 주석은 그대로 둔다(등록 시 403의 서술이고 여전히 맞다 — 두 구조 같음).
- 잠금: "입찰 상태를 바꿀 때는 기존 규칙대로 그 입찰의 사이즈를 잠근다. 판정은 순차 시나리오만 본다."(TASK.md)

## 구조별 고칠 곳 (정답 — 기준 커밋 0fe3f36, 앱 코드는 5131584와 같음)

### god — 1파일 (`fix.god.patch`)

| 위치 | 변경 |
|---|---|
| `Marketplace.kt:193` 뒤(`cancelBid` 다음) | `private fun cancelActiveBids(userId, side, now)` — JPQL `status = 'ACTIVE'`(사용자·쪽, 사이즈 id·입찰 id 순) → 입찰마다 `lockProductSize` → `em.refresh` → ACTIVE면 CANCELLED + `BidEvent(ACTIVE→CANCELLED, null)` |
| `Marketplace.kt:966` (`sellerFault` 끝) | `if (recentPenalties >= 3) { 제한; cancelActiveBids(sellerId, "SELL", now) }` |
| `Marketplace.kt:1403` (`expireUnpaidTrade`, 재매칭 `:1416-1419` 전) | `if (recentWarnings >= 3) { 제한; cancelActiveBids(trade.buyerId, "BUY", now) }` |

### layered — 6파일 (`fix.layered.patch`, 새 파일 없음)

| 위치 | 변경 |
|---|---|
| `domain/User.kt:59-63`·`:70-74` | `restrictIfWarned`·`restrictIfPenalized`가 `Boolean`(제한을 새로 걸거나 갱신했으면 true)을 돌려준다 — '새로 걸림/갱신' 신호 |
| `domain/Bid.kt:159` 뒤(포트 `BidRepository`) | `fun activeOf(userId, side): List<Bid>` |
| `infra/JpaBidRepositories.kt:41` 뒤(어댑터) | 같은 JPQL |
| `service/CancelBidService.kt` 끝 | `@Component RestrictedBidCanceller(sizes, bids, events).cancelActive(userId, side, now)` — 입찰마다 `sizes.lock` → `bids.reload` → ACTIVE면 `events.add(bid.cancel(now))`(`Bid.cancel` `domain/Bid.kt:89` 재사용) |
| `service/ItemRefunder.kt:84` (`SellerFaults.apply` 끝) + 생성자 `:75` | `if (seller.restrictIfPenalized(...)) bidCanceller.cancelActive(sellerId, "SELL", now)` |
| `service/BatchService.kt:164` (`PaymentDueBatch.expire`, 재매칭 `:170-173` 전) + 생성자 `:145` | `if (buyer.restrictIfWarned(...)) bidCanceller.cancelActive(trade.buyerId, "BUY", now)` |

관례: god는 `Marketplace` 안 private 도우미 + JPQL(같은 파일의 `cancelBid`·`expireBids` 모양). layered는 도메인 메서드가 결과를 돌려주고(상태 필드가 `private set`이라 서비스가 전후 값을 비교하기보다 신호를 받는 편이 이 코드베이스의 방식), 포트 → JPA 어댑터 → 서비스 컴포넌트(`MatchRecorder`·`ItemLocker`처럼 여러 서비스가 주입받는 `@Component`).

## 수단 배치 (두 구조 같은 의미)

| 수단 | god | layered | 대칭 판단 |
|---|---|---|---|
| 본보기 없음 | 가장 가까운 본보기 = bid-expiry 배치 `expireBids` `:1341-1355`(ACTIVE 입찰을 사이즈 잠금 뒤 상태 변경 + 이벤트). 사용자 단건 취소 `cancelBid` `:176-191` | 같은 본보기 `BidExpiryBatch` `service/BatchService.kt:104-127`, 단건 `CancelBidService` + `Bid.cancel` | 같다. 일괄 선택 기준(사용자·쪽)·제한 트리거·재매칭 순서 결합은 양쪽 어디에도 없다 |
| 여러 지점 | 규칙 지점 2: `:966`, `:1403`(+ 재매칭 순서) | 규칙 지점 2: `ItemRefunder.kt:84`, `BatchService.kt:164`(+ 재매칭 순서) | 같다(2 대 2). 판매 쪽은 두 진입(검수·발송 기한)이 공용 지점 하나로 모인다 — 양쪽 같음 |
| 분리 | 증상: 매칭·호가(`getBook` `:215`)·입찰 상태. 고칠 곳: `sellerFault`·`expireUnpaidTrade` | 증상: `JpaBidRepository.book`·`bestCounter`. 고칠 곳: `SellerFaults`·`PaymentDueBatch` | 같다 |
| 그럴듯한 오답 | 매칭 쿼리 `findBestCounterBid` `:243-262`·호가에서 제한 사용자를 거르기 | `JpaBidRepository.bestCounter` `:43-64`·`book` `:66-75` 같은 SQL | 같다(같은 SQL 문자열) |

**처치 효과로 표시(ADR §5.3·§9 8):** 고칠 파일 수 god 1 / layered 6(정답 hunk god 3 / layered 9 — 그중 import 1). 규칙 지점 수는 2 대 2로 같다. god는 1,772줄 `Marketplace.kt` 한 파일이라 첫 편집 적중이 정의상 거의 항상 참이다(옛 C2와 같은 해석 주의 — 구조 비교 지표로 쓰지 않고 표시만).

## 대안 정답 (숨은 테스트가 결과만 보므로 통과해야 함)

- 대상 조회를 네이티브 SQL·`FOR UPDATE`로 해도, 잠금 순서를 사이즈 id 순이 아니라 입찰 id 순으로 해도, 이미 잠근 사이즈를 다시 잠그지 않아도.
- 신호를 도메인 반환값 대신 서비스에서 전후 `restricted_until`을 비교해 만들어도(god도 같은 방식 가능), 도우미를 `ItemRefunder.kt`·`BatchService.kt` 안에 따로 두어도, layered에서 새 서비스 파일을 만들어도.
- 구매 쪽 취소를 경고·제한 직후가 아니라 "체결의 구매 입찰 MATCHED→CANCELLED 뒤, 판매 입찰 복구 전"에 해도(재매칭보다 먼저면 같은 결과).
- 단건 취소의 도메인 동작(`Bid.cancel`/god의 같은 두 줄)을 재사용해 입찰마다 부르는 구현도, 같은 트랜잭션·같은 현재 시각이면 통과.

## 순진한 오답 (naive-N — 기준 사본 위에 정답과 같은 모양으로 결함 하나)

| N | 오답 | 숨은 테스트에서 실패해야 하는 곳 |
|---|---|---|
| 1 | 판매 흐름만(구매 제한 쪽 없음) | B(같은 사이즈 구매 입찰이 되살아난 판매 입찰과 체결), R 구매 |
| 2 | 구매 흐름만(판매 제한 쪽 없음) | S1·S2·R 판매 |
| 3 | 양쪽 입찰 모두 취소(쪽 조건 없음) | S1·S2(판매자의 BUY 그대로), B(구매자의 SELL 그대로) |
| 4 | `payment-due`에서 재매칭 뒤에 취소 | B(같은 사이즈 구매 입찰이 이미 MATCHED — 새 체결) |
| 5 | 이벤트 없이 상태만 CANCELLED | S1·S2·B·R(이벤트 정확히 1행) |
| 6 | 취소 대신 매칭·호가 쿼리에서 제한 사용자를 거름 | S1·S2·B·R(`bids.status`가 ACTIVE로 남음), N 판매 준비(제한 판매자의 기존 입찰이 체결되지 않음) |
| 7 | 패널티·경고 뒤 "제한 중이면" 취소(갱신 여부 무시) | N(제한 중 패널티 1건·경고 1건에 취소) |
| 8 | 처음 걸릴 때만 취소(이미 제한 중이던 갱신은 무시) | R 판매·구매 |
| 9 | `expires_at > 현재`(체결 가능)로 대상 선택 | S1·S2(유효기간이 지났지만 ACTIVE인 입찰이 남음) |
| 10 *(추가)* | 구매 흐름 정상 + 판매 흐름은 `shipment-deadline` 경로에만(공용 지점 대신) | S1·R 판매(검수 불합격 경로) |

브리프의 naive 1("판매 흐름만 — shipment-deadline 경로에만 넣은 것 포함")을 결함 하나씩으로 나눠 1(판매 흐름만)과 10(진입 경로 하나만)으로 두었다.

## 숨은 테스트 (`hidden/C2HiddenTest.kt` + `hidden/C2Fixtures.kt`, 6개)

사용자·빈 상품 사이즈·(N·R의) 제한 시각만 SQL로 넣고 나머지는 API(입찰·체결·결제·발송·입고·검수·배치)로 만든다. 시계는 앞으로만 상대 이동, bid-expiry는 돌리지 않는다. 이벤트 단언은 "준비 끝 시점의 `max(bid_events.id)` 뒤에 생긴 행"만 본다.

| 테스트 | 시나리오 | 단언 |
|---|---|---|
| S1 | 판매자: stale SELL(30일, 31일 경과로 만료 시각 지남·ACTIVE) + 다른 사이즈 SELL 3개(한 사이즈에 2개) + 이미 취소한 SELL + 자기 BUY 1개 + 팔릴 SELL 3개 → 검수 불합격 2건 → 3번째 검수 불합격 | 2건 뒤 제한 없음·아무 이벤트 없음. 3번째 직후: `sale_restricted_until = 현재+30일`(준비), ACTIVE SELL 4개 전부 CANCELLED + 각 새 이벤트 정확히 1행(ACTIVE→CANCELLED, trade NULL, created_at = 현재), 이미 취소한 SELL·체결된 SELL·자기 BUY는 그대로·새 이벤트 없음, 호가에서 빠짐(BUY 사이즈는 40,000 남음) |
| S2 | S1과 같은 준비, 3번째 패널티 = 결제 +3일 + 60초에 `shipment-deadline` | S1과 같은 단언(현재 = 배치 시각) |
| B | 구매자: 자기 SELL 1개 + 다른 사이즈 BUY 1개 → 미결제 체결 2건 만료(경고 2) → 빈 사이즈에서 판매 입찰과 체결(미결제) + 같은 사이즈에 BUY 110,000(그 사이즈 유일한 BUY, 되살아날 판매가 100,000 이상) → `payment-due` | 경고 2건 뒤 그대로. 3번째 뒤: 구매 제한 = 현재+7일(준비), 같은 사이즈 BUY·다른 사이즈 BUY CANCELLED + 이벤트 1행(4필드), 자기 SELL 그대로·이벤트 없음, 되살아난 SELL ACTIVE·새 이벤트는 MATCHED→ACTIVE 1행뿐, 그 사이즈 체결 수 1, 호가 (null, 100,000) |
| N | 판매: SELL 2개 → DB로 `sale_restricted_until = 현재+10일` → 1개 팔림 → 검수 불합격 1건. 구매: BUY 1개 + 미결제 체결 → DB로 `purchase_restricted_until = 현재+3일` → `payment-due` 경고 1건 | 제한 시각 그대로(준비), SELL·BUY 그대로·이벤트 없음 |
| R | 판매: SELL 1개 + 팔릴 SELL 3개 → DB로 `sale_restricted_until = 현재+29일`(마지막 제한 1일 전) → 검수 불합격 3건. 구매: BUY 1개 + 미결제 체결 3건 → DB로 `purchase_restricted_until = 현재+6일` → `payment-due` 한 번에 경고 3건 | 판매: 2건 뒤 그대로, 3건째에 제한 갱신(준비) + SELL CANCELLED·이벤트 1행. 구매: 제한 갱신(준비) + BUY CANCELLED·이벤트 1행 |
| 재계수 | `contract2/PenaltyTest` "제한을 건 패널티 뒤로 다시 센다"의 계약. 판매 입찰 6개를 걸고 **첫 제한 전에 6개 모두 팔고 결제·발송·입고**해 둔 뒤 검수 불합격 3건(t1) → +1일 2건 → +1일 1건 | t1+30일 → 그대로 → t3+30일(패널티 수 5·6) |

결정성: 모든 단언이 테스트가 만든 사용자·사이즈·입찰만 보거나(mark 뒤 이벤트), 테스트가 정한 시각과 같은지만 본다. 같은 실행의 배치가 시드 대상을 함께 처리해도 단언 대상과 겹치지 않는다(새 사이즈엔 다른 입찰이 없다). 실행 순서는 이름순 고정(순서에 기대는 단언은 없다 — 시계만 앞으로 간다).

### 숨은 테스트가 두 구조의 고칠 곳을 지나는지 (호출 경로)

| 시나리오 | god | layered |
|---|---|---|
| S1·R 판매·재계수 (검수 불합격) | `web/AdminController.kt:44-47` → `Marketplace.inspectItem` `:785` → `sellerFault` 호출 `:795` → `:954` → 판매 제한 `:966` | `api/AdminController.kt:50-53` → `FulfillmentService.inspect` `:81` → `sellerFaults.apply` `:90` → `ItemRefunder.kt:78` → 판매 제한 `:84`(`User.restrictIfPenalized` `User.kt:70`) |
| S2 (발송 기한 초과) | `web/AdminController.kt:35-37` → `Marketplace.runBatch` `:1271` → `"shipment-deadline"` `:1278` → `cancelUnshippedItems` `:1472` → `sellerFault` `:1484` → `:966` | `api/AdminController.kt:41-43` → `BatchService.run` `:82` → `"shipment-deadline"` `:89` → `ShipmentDeadlineBatch.run` `:227` → `sellerFaults.apply` `:233` → `ItemRefunder.kt:84` |
| B·N 구매·R 구매 (결제 기한) | `runBatch` → `"payment-due"` `:1276` → `expireUnpaidTrades` `:1364` → `expireUnpaidTrade` `:1372`→`:1378` → 구매 제한 `:1403` → 재매칭 `:1416-1419` | `BatchService.run` → `"payment-due"` `:87` → `PaymentDueBatch.run` `:147` → `expire` `:150` → 구매 제한 `:164`(`User.restrictIfWarned` `User.kt:59`) → 재매칭 `:170-173` |

경로 실측: 기준 사본은 S1·S2·B·R가 실패하고 정답 두 벌은 6개 모두 통과한다. naive-1(판매만)·naive-2(구매만)·naive-10(발송 기한 경로만)이 각각 해당 시나리오에서만 실패하는 것으로 지점별 경유를 확인했다(아래 실측).

## 잠금 결정과 위협

- 정답은 TASK.md대로 입찰마다 그 사이즈를 잠그고 다시 읽은 뒤 바꾼다(ADR-0003 §3.3 사이즈 단위 직렬화 — `bid_events.id` 순서 = 같은 사이즈 안 처리 순서, verify `book_never_crossed`·`trade_price_time_priority`가 기댐). 잠금 순서는 사이즈 id 오름차순.
- 위협(ADR §9 13): 판매 쪽은 주문 → 항목 → 사용자(구매자·판매자) → 사이즈들, 결제 기한 쪽은 체결 사이즈 → 구매자 → 다른 사이즈들 순으로 잠근다. 판매 쪽(사용자 → 사이즈 X)과 다른 처리(사이즈 X → 같은 사용자, 예: 그 판매자가 구매자인 체결의 결제 기한 만료)가 동시에 돌면 교착할 수 있다. 숨은 테스트·회귀는 순차라 판별하지 못한다 — 두 구조 같은 조건이고 TASK.md가 "판정은 순차 시나리오만 본다"고 밝힌다.
- 조회와 잠금 사이에 같은 사용자가 그쪽 새 입찰을 등록하면 놓칠 수 있다(제한은 이 트랜잭션 커밋 뒤에야 보인다) — 순차 판정 밖, 두 구조 같음.

## 제외한 유사 지점과 사유

- 입찰 등록의 제한 확인(god `:138-144`, layered `User.requireCanBid` `:39`): 그대로(새 입찰 403 규칙 불변).
- 체결의 결제(`/orders`)·제한 중 사용자의 MATCHED 입찰: 막지 않는다(TASK.md 명시, `RestrictionAndConcurrencyTest` "제한은 새 입찰에만 - 이미 걸린 입찰의 체결과 결제는 막지 않는다"가 회귀로 지킨다 — 매칭 필터 오답(naive-6)은 이 회귀도 깬다).
- bid-expiry·사용자 취소: 바꾸지 않는다(본보기로만 읽는다).
- 제한 판정식(T2 대상): 바꾸지 않는다. layered는 반환형만 바뀐다.

## 주석

주입·규칙 변경이 아니라 새 규칙 추가라 지운 주석 0개(두 구조). 기존 "제한은 새 입찰에만" 주석 2곳(god `:138`, layered `User.kt:38`)은 등록 시 403의 서술이라 그대로 남긴다(두 구조 같은 상태 — 단서 비대칭 없음). 정답이 더한 KDoc·주석은 정답 패치 안에만 있다.

## regression-exclude · verify-exclude

- regression-exclude: `contract2/PenaltyTest` "판매 제한 - 제한을 건 패널티 뒤로 다시 센다…" 1메서드(패턴 `…다시 센다*`). 준비가 판매 입찰 6개를 미리 걸어 두고 3번째 패널티(제한) 뒤에 4~6번째를 팔아 패널티를 만드는데, 새 규칙이 그 입찰들을 제한 순간 취소한다. 같은 계약은 숨은 테스트 '재계수'로 옮겼다.
- verify-exclude: 빈 파일(실측 아래).

## 채택 조건 실측

2026-10-27, 기준 HEAD 0fe3f36(experiment/ 미커밋 변경 있음), `runner.py check` 순차 실행. 결과 행 = `experiment/results/checks/checks.jsonl`.

| 구조 | 패치 | 반복 | 단계 | success | 결과 |
|---|---|---|---|---|---|
| god | fix | 1 | 전 단계 | True | public 2/2, hidden 6/6, contract 122, contract2 88(89 − 제외 1) 통과, verify 위반 0(두 회귀 단계) |
| layered | fix | 1 | 전 단계 | True | 같음 |
| god·layered | fix | 2~5 (4회씩) | public,hidden | True ×8 | 전부 통과 |
| god·layered | none | 1 | public,hidden | False | public 2/2 실패, hidden 4/6 실패(S1·S2·R: 판매 입찰 ACTIVE로 남음, B: 같은 사이즈 구매 입찰 MATCHED) — N·재계수는 기준 상태에서도 통과(설계대로) |
| god·layered | naive-1 | 1 | hidden | False | B(같은 사이즈 구매 입찰 MATCHED), R(구매 제한 갱신: BUY ACTIVE) |
| god·layered | naive-2 | 1 | hidden | False | S1·S2·R(판매 입찰 ACTIVE) |
| god·layered | naive-3 | 1 | hidden | False | S1·S2(판매자의 BUY CANCELLED), B(구매자의 SELL CANCELLED) |
| god·layered | naive-4 | 1 | hidden | False | B(같은 사이즈 구매 입찰 MATCHED) |
| god·layered | naive-5 | 1 | hidden | False | S1·S2·B·R(새 이벤트 0행 ≠ 1) |
| god·layered | naive-6 | 1 | hidden | False | S1·S2·B(입찰 ACTIVE), N·R(준비 체결 실패 — 제한 판매자의 기존 입찰이 매칭에서 빠짐) |
| god·layered | naive-7 | 1 | hidden | False | N(제한 중 패널티 1건에 SELL CANCELLED), R(패널티 2건 뒤 이미 CANCELLED) |
| god·layered | naive-8 | 1 | hidden | False | R(판매 제한 갱신에 SELL ACTIVE) |
| god·layered | naive-9 | 1 | hidden | False | S1·S2(만료 시각 지난 ACTIVE 입찰이 ACTIVE로 남음) |
| god·layered | naive-10 | 1 | hidden | False | S1·R(검수 불합격 경로에서 SELL ACTIVE) |

naive마다 두 구조가 같은 테스트·같은 단언에서 실패한다(지점을 지나는 경로가 대칭임을 확인).

### 최종 재채택(고정 SHA, 2026-10-27 22:24 ~ 10-28 00:49)

모든 행: `code_sha` ae6deda, `code_dirty` False — 32행, 순차 실행.

| 구조 | 패치 | 결과 |
|---|---|---|
| god·layered | none | public fail · hidden 4/6 fail |
| god·layered | fix ×5 | True 5/5(1회 전 단계: contract 122, contract2 88, verify 위반 0 — 두 회귀 단계) |
| god·layered | naive-1~10 | 전부 hidden fail — 숨은 실패 개수 2·3·3·1·4·5·2·1·2·2(위 표와 같은 테스트 수), naive-3·4·7~10은 **public pass** |

- 변조 0, build 실패 0. 두 구조가 naive마다 같은 개수로 실패한다. **C2 채택 확정.** 위 표(dirty)는 이 표로 대체된다.
