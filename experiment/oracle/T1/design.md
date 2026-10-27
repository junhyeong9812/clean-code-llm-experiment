# T1 설계 — 확정 시각 상한(bug)

> tasks-redesign task 02. 설계 브리프(T1 절 + "선검증 반영" 절 — 반영 절이 본문보다 우선)를 따른다. 옛 T1(포인트 사용 배분 순서)은 git `5131584`의 이력이다.
> 작성 기준: 브랜치 `feat/tasks-redesign`, HEAD `0fe3f36`. 앱 원본은 main `5131584`와 같다.
> 줄 번호: "원본"은 저장소의 `god/`·`layered/` 소스, 표시가 없으면 **주입 사본** 기준이다. 주입은 지점 1에서 한 줄을 지운다. 그래서 god `Marketplace.kt`의 원본 :877 뒤 줄과 layered `domain/Order.kt`의 원본 :327 뒤 줄은 주입 사본에서 원본 −1이다. 다른 파일은 줄 수가 그대로다. symbols도 주입 사본 기준이다.

## 1. 의도

- 규칙(ADR-0004 §4.1·§5, ADR-0002 §9.7):
  - 구매자 확정: `confirmed_at = min(현재, delivered_at + 7일)`.
  - 자동 확정(`auto-confirm`, `weekly-settlement`가 먼저 수행): DELIVERED이고 `delivered_at + 7일 ≤ 현재`이면 `confirmed_at = delivered_at + 7일`(소급).
  - 하류 효과: 정산 대상 주(`confirmed_at < cutoff`), 분쟁 기한(`confirmed_at + 14일`), 적립 만료(`confirmed_at + 365일`), 등급 산정 월.
- 주입: 두 지점 모두 확정 시각으로 "지금"을 기록한다. 두 구조가 같은 의미다.
- 증상: 7일 뒤 확정된 항목이 정산에서 늦은 주로 밀린다. 분쟁 기한도 늦게 끝난다.
- 난이도 수단: 분리(증상은 정산·분쟁에서 보이고, 고칠 곳은 확정) · 여러 지점(규칙 지점 2) · 오진 유도(정산 cutoff·주 루프).

## 2. 주입(`inject.{god,layered}.patch`)

| 구조 | 지점 1: 구매자 확정 | 지점 2: 자동 확정 |
|---|---|---|
| god | `Marketplace.kt` 원본 :876-877 → 주입 :876. `lastDay` 계산 줄을 지우고 `confirmItem(item, confirmedAt = now, now = now)` | 원본 :1508 → 주입 :1507. 가드 `due`(:1503-1504)는 그대로, `confirmItem(item, confirmedAt = now, now = now)` |
| layered | `domain/Order.kt` 원본 :326-327 → 주입 :326. `confirmationTime`: `requireStatus("DELIVERED"); return now`(lastDay 삭제) | 원본 :347 → 주입 :346. `autoConfirmationTime`: `return if (due.isAfter(now)) null else now` |

- 상수: layered `CONFIRM_WITHIN`(`Order.kt` 주입 :396)은 남는다. 가드(:345)와 `autoConfirmCutoff`(:403)에서 계속 쓴다. god는 `Duration.ofDays(7)` 리터럴이 자동 확정 가드 두 곳(:1498 대상 조건, :1503 `due`)에 남는다. 두 구조 모두 지점 2의 가드가 `delivered_at + 7일`을 계산해 둔 채 쓰지 않는 모양이다(대칭).
- 주입 사본은 두 구조 모두 경고 없이 컴파일된다(스모크 — §10).

### 2.1 규칙 서술 주석 — 중립 문장으로 바꿈(지우지 않음)

선검증 반영에 따라 주석은 지우지 않는다. 시각 규칙만 뺀 중립 문장으로 바꾼다(두 구조 같은 방식). 그래서 layered KDoc 자리만 비어 눈에 띄는 일이 없다.

전수 grep: 두 구조 `src/`에서 `grep -rn "7일\|delivered_at + 7\|소급\|confirmed_at\|confirmedAt\|확정 시각"`. 그 결과에서 확정 시각 규칙(`min(현재, …)`·`= delivered_at + 7일`·`소급`)을 서술한 주석만 골랐다.

| 구조 | 위치(주입 사본) | 원문 → 주입 후 |
|---|---|---|
| god | `Marketplace.kt:869` KDoc(confirmOrderItem) | "구매 확정(§4.1). 구매자만, DELIVERED만. confirmed_at = min(현재, delivered_at + 7일)(7일 뒤의 확정은 자동 확정과 같다) → §8.2." → "구매 확정(§4.1). 구매자만, DELIVERED만 → §8.2." |
| god | `Marketplace.kt:1490` KDoc(autoConfirmItems) | "…≤ 현재인 항목을 id 순으로 CONFIRMED, confirmed_at = delivered_at + 7일(소급) → §8.2." → "…≤ 현재인 항목을 id 순으로 CONFIRMED → §8.2." |
| god | `Marketplace.kt:1607` KDoc(settleWeeks) | "먼저 auto-confirm을 수행하고(소급 확정 시각이 이미 마감된 주에 들어가지 않게), 정산할 주를…" → "먼저 auto-confirm을 수행하고, 정산할 주를…" |
| layered | `domain/Order.kt:321-322` KDoc(confirmationTime) | "…확인과 확정 시각: DELIVERED만, confirmed_at = min(현재, delivered_at + 7일) / (7일 뒤의 확정은 자동 확정과 같다). 바꾸지 않는다 — …" → "구매 확정(ADR-0004 §4.1)의 확인: DELIVERED만. / 바꾸지 않는다 — …"(두 줄 유지) |
| layered | `domain/Order.kt:340` KDoc(autoConfirmationTime) | "자동 확정(ADR-0004 §5 auto-confirm)의 확정 시각: DELIVERED이고 delivered_at + 7일 ≤ 현재면 delivered_at + 7일(소급), 아니면 null." → "auto-confirm(ADR-0004 §5) 대상 확인: DELIVERED이고 delivered_at + 7일 ≤ 현재일 때만, 아니면 null." |
| layered | `domain/Order.kt:402` KDoc(autoConfirmCutoff — 가드, 규칙 서술 아님) | "…상한(이 시각 이하면 자동 확정)." → "…상한(이 시각 이하면 auto-confirm 대상)." — TASK.md 핵심어 "자동 확정"의 layered 전용 grep 적중을 없애려고 바꿨다(god에는 "자동 확정" 문구가 없다) |
| layered | `service/BuyerItemService.kt:52` KDoc(confirm) | "구매 확정. DELIVERED만, confirmed_at = min(현재, delivered_at + 7일)." → "구매 확정. DELIVERED만." |
| layered | `service/BatchService.kt:241` KDoc(AutoConfirmBatch) | "…CONFIRMED, confirmed_at = delivered_at + 7일(소급) → §8.2." → "…CONFIRMED → §8.2." |
| layered | `service/BatchService.kt:317` KDoc(WeeklySettlementBatch) | god :1607과 같은 괄호 삭제 |

- 바꾼 개수: **god 3, layered 6**(규칙 서술 5 + 핵심어 대칭용 가드 문구 1). 원본 layered는 같은 규칙을 더 여러 층(도메인 2·서비스 3)에 서술했다. ADR §3.1 본문은 "지운다"이지만, 선검증 반영 절이 "중립 문장으로 바꾼다"로 우선한다.
- **누출 점검(M-1 반영, 2026-10-27):** 처음 판은 layered `Order.kt:321`("확인과 확정 시각")·`:340`("자동 확정 …의 확정 시각")을 남겼다. TASK.md 핵심어 "확정 시각"으로 grep하면 layered만 고칠 두 지점에 바로 닿았다(god 적중 0). 위 표처럼 god 수준으로 중립화했다(줄 수 유지). 재점검: 두 구조의 주입 사본 `app/src` 전체를 grep한 결과는 다음과 같다.

  | 핵심어 | god | layered |
  |---|---|---|
  | 확정 시각 | 0 | 0 |
  | 자동 확정 | 0 | 0 |
  | 소급 | 0 | 0 |
  | 분쟁 접수 기한 | 0 | 0 |
  | 7일 | 3 — 구매 제한 `:1392`(무관), 자동 확정 가드 KDoc `:1490`(지점 2 바로 위), 정산 주 KDoc `:1608` | 7 — 구매 제한 `User.kt:55·56`(무관), 정산 주 `Settlement.kt:14·28`, 가드 `BatchTargets.kt:22`·`BatchService.kt:241`·`Order.kt:340`(지점 2 바로 위) |

  - "7일"은 두 구조 모두 지점 2 바로 위의 가드 KDoc 1곳에 닿는다(대칭). 지점 1 근처에는 두 구조 모두 적중이 없다. layered의 추가 적중(가드 2·제한 2·정산 2)은 원본이 같은 가드를 여러 층에 서술한 결과다. 지점 2로 이어지는 가드 서술이 layered에 2곳 더 있다는 것을 비대칭으로 기록한다.
- 남긴 가드 서술: 이 규칙은 바꾸지 않았으므로 그대로 둔다. god `:1490`의 "delivered_at + 7일 ≤ 현재인 항목"이 1곳이다. layered는 `domain/BatchTargets.kt:22`, `Order.kt:340`("≤ 현재일 때만"), `BatchService.kt:241`("배송 완료 7일이 지난")의 3곳이다.

### 2.2 남는 단서(올바른 구현 잔여물) — 위협으로 기록

| 구조 | 잔여물 | 개수 |
|---|---|---|
| god | 지점 2 가드의 `val due = …plus(Duration.ofDays(7))`(:1503)가 계산되고 비교에만 쓰인다. `confirmItem(…, confirmedAt = now, now = now)`처럼 확정 시각 인자가 `now`와 따로 있다(:876·:1507) | 코드 2 |
| layered | 지점 2 가드의 `val due`(:345)가 같은 모양으로 남는다. `confirmer.confirm(item, item.confirmationTime(now), now)`(`BuyerItemService.kt:57`)에서 인자가 따로 있다. **추가:** `confirmationTime(now)`가 `requireStatus` 뒤 `return now`만 남는다(`Order.kt:324-327`). 이름이 "확정 시각을 계산하는 함수"인데 계산이 없어 눈에 띈다 | 코드 3 |

- layered의 `confirmationTime` 흔적은 구조가 만든 차이다. 계산을 함수로 뽑아 둔 구조이기 때문에 생긴다. god에는 대응하는 함수가 없다. 처치 효과 과제로 표시하지 않고 결과 해석 때 기전 지표로 본다(ADR §9 위협).


### 2.3 가드 서술 중립화 (리뷰 루프 2 종합 감사 반영)

자동 확정 대상 조건(가드)을 적은 주석도 두 구조 모두 '기한이 지난 DELIVERED' 수준으로 바꿨다: god `Marketplace.kt:1490`, layered `domain/Order.kt:340`·`domain/BatchTargets.kt:22`·`service/BatchService.kt:241`. 가드 자체는 주입 규칙이 아니지만, layered가 같은 가드를 계층마다 적어 '7일' 검색이 layered에서만 자동 확정 경로로 여러 번 이어졌다. 주입 사본 핵심어 grep(앱 main): '확정 시각'·'자동 확정'·'소급'·'분쟁 접수 기한' 두 구조 0, '7일' god 1·layered 2 — 남은 것은 정산 주 창 서술(god `:1608`, layered `Settlement.kt:14`·`:28`, 오진 유도 영역 — 두 구조 같은 의미)이다.

## 3. 수단 배치와 대칭

| 수단 | god | layered | 대칭 판단 |
|---|---|---|---|
| 분리 | 증상: 정산 대상 SQL `:1600`(`settleWeek` :1634), 분쟁 기한 `:909`. 고칠 곳: `:876`·`:1507` | 증상: `infra/JpaSettlementRepository.kt:21`, 분쟁 기한 `domain/Order.kt:358`. 고칠 곳: `Order.kt:326`·`:346` | 같다(증상에서 확정까지 2홉) |
| 여러 지점 | 규칙 지점 2(`:876`, `:1507`), **631줄 떨어짐**, 각자 `Duration.ofDays(7)` | 규칙 지점 2(`:326`, `:346`), 한 클래스 **20줄 간격**, 상수 `CONFIRM_WITHIN` 공유 | 수 같음. 합류점 1곳의 대안 정답도 두 구조에 같은 조건으로 있다(§4). **거리 비대칭은 기록만 한다**(사용자 결정 2026-10-27 — 처치 효과 과제로 표시하지 않고 design.md·ADR §9 위협에 적는다) |
| 오진 유도 | 정산 주 루프 시작 `plusDays(14)` `:1615`, cutoff 배타 `<` `:1600`, 적립 만료 `+365` `:1153` | `domain/Settlement.kt:34` `plusDays(14)`, `JpaSettlementRepository.kt:21` `<`, 적립 만료 `domain/Point.kt:88` | 같다 |

## 4. 정답

- `fix.{god,layered}.patch`: 두 지점의 코드를 원본으로 되돌린다. god는 `lastDay` 줄을 복원하고 `confirmedAt = due`로 바꾼다. layered는 `lastDay` 계산을 복원하고 `null else due`로 바꾼다. 주석은 되돌리지 않는다(중립 문장 그대로). 코드만 고치면 정답이다.
- **대안 정답(합류점):** 두 경로가 모이는 확정 반영에서 `min(confirmedAt, delivered_at + 7일)`로 자르는 1곳 수정도 정답이다. 두 구조가 같은 조건이다.
  - god `confirmItem`(:881-888): `item.confirmedAt = minOf(confirmedAt, item.deliveredAt!!.plus(Duration.ofDays(7)))`. 포인트 확정에도 그 값을 넘긴다.
  - layered `ItemConfirmer.confirm`(`service/BuyerItemService.kt:28-33`)에서 같은 자르기를 하고, 자른 값을 포인트 확정에도 넘긴다(alt-1). `Order.markConfirmed`(`Order.kt:330`) 안에서만 자르면 포인트 확정(`BuyerItemService.kt:32`)은 자르지 않은 값을 받아 적립 만료가 틀린다 — 그것은 naive-6이다.
  - 자동 확정 지점은 `service/BatchService.kt` `AutoConfirmBatch`(:258-262)에서 `due`(배송+7일)를 넘기는 수정도 정답이다(symbols `edit`에 BatchService.kt 포함 — god는 같은 위치가 `Marketplace.kt` 안이라 파일 전체 `edit`에 이미 든다).
  - **함정 비대칭(기록만 — 사용자 결정 2026-10-27):** 합류점에서 '확정 시각만' 자르는 오답(naive-6)은 god에서는 저장(`:886`)과 포인트 확정(`:888`)이 4줄 함수 안에 붙어 있어 빠지기 어렵고, layered에서는 두 고칠 곳 사이의 도메인 setter `markConfirmed`(`Order.kt:330`) 하나만 고치면 될 것처럼 보이며 포인트 확정은 다른 파일(`BuyerItemService.kt:32`)이다. 캡슐화가 파일을 나눈 구조 결과로 보고 ADR §9-12에 함께 적는다.
  - `alt-1.{god,layered}.patch`: confirmItem / ItemConfirmer 판. 포인트 확정에도 자른 값을 넘긴다.
  - symbols에서 합류점은 `fix`가 아니라 `edit`(첫 편집 적중 인정)와 `read`로 넣었다. god는 합류점이 고칠 파일과 같은 파일이라 `edit`가 이미 덮는다.
  - 저장 확정 시각만 자르고 포인트 확정에 자르지 않은 값을 넘기는 변형은 오답이다(naive-6). 적립 만료가 어긋나고, 숨은 테스트 A·B의 적립 `expires_at` 단언이 잡는다.
- **숨은 테스트가 대안을 막지 않는 이유:** 응답 `confirmedAt`·DB `confirmed_at`·분쟁 응답 코드·`GET /me/settlements`의 주만 본다. 계산 위치나 상수 이름은 보지 않는다.

## 5. 순진한 오답(naive-N — 전부 숨은 테스트에서 실패해야 채택)

| N | 패치(두 구조 같은 의미) | 기대 실패 지점 |
|---|---|---|
| 1 | 부분: 구매자 확정만 고침(지점 1) | B — 정산 주 단언(그 항목이 W 주 정산에 없음) |
| 2 | 부분: 자동 확정만 고침(지점 2). 공개 테스트는 통과 | A — 응답 confirmedAt 단언 |
| 3 | 오진: 정산 cutoff `confirmed_at < :cutoff` → `<=`(god `:1600`, layered `JpaSettlementRepository.kt:21`) | A — confirmedAt. B — 정산 주(확정 시각 = 마감 + 1시간이라 `<=`로도 안 들어감) |
| 4 | 오진: 정산이 `LEAST(confirmed_at, delivered_at + 7일) < :cutoff`로 주를 고름(같은 두 곳). 공개 테스트는 통과 | A — confirmedAt. B — 정산은 맞고 DB `confirmed_at` 단언에서 실패 |
| 6 | 부분(합류점): 저장 확정 시각만 `min(…, delivered_at + 7일)`로 자르고 포인트 확정에는 자르지 않은 값을 넘김(god `confirmItem` :886만, `confirmEarn(…, confirmedAt, …)` 그대로 / layered `Order.markConfirmed` :333 안에서만) | A — 적립 expires_at(확정 시각 단언은 통과). B — 적립 expires_at(정산·확정 시각 단언은 통과) |
| 7 | 과잉 수정: 구매자 확정을 늘 `delivered_at + 7일`로 기록(god `:876` `confirmedAt = item.deliveredAt!!.plus(Duration.ofDays(7))`, layered `Order.kt:326` `return deliveredAt!!.plus(CONFIRM_WITHIN)`) + 자동 확정은 바르게 고침 | A — 7일 안 확정 항목의 응답 confirmedAt(확정한 시각이어야 함). B는 통과. 회귀에서는 `BuyerActionTest`도 잡는다 |
| 5 | 오진: 정산 주 루프 시작 `lastPeriodStart + 14` → `+ 7`(god `:1615`, layered `Settlement.kt:34`) | A — confirmedAt. B — 정산 주(지난 주를 한 번 더 정산할 뿐, 항목은 여전히 다음 주로 밀림) |

- 실측 실패 메시지는 §10 표에 있다.
- 고려했으나 패치로 만들지 않은 것: 분쟁 기한 쪽만 `delivered_at + 21일`로 바꾸기. A의 confirmedAt 단언에서 먼저 실패하므로 naive-4와 같은 부류다.

## 6. 숨은 테스트(`hidden/T1HiddenTest.kt`) — 결과만 단언, 결정적

- **A(구매자 확정):**
  - 같은 시각 D에 항목 셋을 배송 완료한다. 항목 3은 D+3일에 구매자 확정한다. 단언: 응답 `confirmedAt` = DB `confirmed_at` = D+3일(확정한 그 시각), EARN `expires_at` = D+3일+365일. 7일 안의 확정을 보는 단언이고, naive-7("늘 배송 완료 + 7일")을 잡는다.
  - 항목 1·2(같은 구매자, 같은 시각 D — D = 시드 시계):
  - 시계를 D+10일로 옮겨 둘 다 구매자 확정한다.
  - 단언: 응답 `confirmedAt` = DB `confirmed_at` = D+7일. 그 항목 EARN 행 `expires_at` = D+7일+365일.
  - 시계를 기한(D+7일+14일) 1시간 전으로 옮겨 항목 1에 분쟁을 접수한다. 201이어야 한다.
  - 시계를 기한 1시간 뒤로 옮겨 항목 2에 분쟁을 접수한다. 409 `DEADLINE_PASSED`이고 항목은 CONFIRMED 그대로여야 한다.
  - 시계는 뒤로 못 가므로 항목을 미리 셋 만들어 D+3일 확정 → D+10일 확정 → 기한 전 접수 → 기한 뒤 접수 순으로 실행한다.
- **B(자동 확정):**
  - 다음 화요일 정오(KST)에 배송 완료(D′)한다. 아무도 확정하지 않는다.
  - W = D′+7일이 속한 주다. W 마감(다음 월요일 00:00 KST) 1시간 뒤에 `weekly-settlement`를 돌린다.
  - 단언: 판매자 `GET /me/settlements`에서 그 항목이 들어간 정산은 period_start = W 월요일 하나뿐이고 INCLUDED다. DB `status` = CONFIRMED, `confirmed_at` = D′+7일, EARN `expires_at` = D′+7일+365일.
- 두 메서드는 각자 현재 시계에서 출발하므로 실행 순서와 무관하다.
- 공개 테스트와의 관계: 공개 테스트는 자동 확정 경로만 본다. 목요일 15:00 배송 완료, 마감 34시간 뒤 정산, 정산 주만 단언한다. 그래서 naive-2·naive-4는 공개 테스트를 통과하고 숨은 테스트에서 실패한다(의도).
- 기존 계약 회귀: 정답이 원본 복원이라 regression-exclude가 없다. 주입 상태에서는 `BatchTest`·`BuyerActionTest`·`SettlementTest` 등이 깨질 수 있다. 판정은 정답 사본 기준이다.

### 6.1 숨은 테스트가 고칠 곳을 실제로 지나는 호출 경로(주입 사본 줄)

| 시나리오 | god | layered |
|---|---|---|
| A 확정 | `POST /order-items/{id}/confirm` → `web/ItemController.kt:34-40` → `Marketplace.confirmOrderItem` :870 → **지점 1 :876** → `confirmItem` :882(합류점) → `item.confirmedAt` :886 | `api/ItemController.kt:42-48` → `BuyerItemService.confirm` :53 → `item.confirmationTime(now)` :57 → `Order.confirmationTime` :324 → **지점 1 :326** → `ItemConfirmer.confirm` :28(합류점) → `Order.markConfirmed` :330 |
| A 분쟁 | `POST /order-items/{id}/disputes` → `web/ItemController.kt:52-58` → `openDispute` :903 → 기한 :909 | `api/ItemController.kt:60-66` → `BuyerItemService.openDispute` :74 → `Order.openDispute` :356 → 기한 :358 |
| B 자동 확정 | `POST /admin/batches/weekly-settlement` → `web/AdminController.kt:35-37` → `Marketplace.runBatch` :1270 → `"weekly-settlement"` :1281 → `settleWeeks` :1611 → `autoConfirmItems(now, null)` :1612 → :1494 → **지점 2 :1507** → `confirmItem` :882 | `api/AdminController.kt:41-43` → `BatchService.run` :82 → `"weekly-settlement"` :93 → `WeeklySettlementBatch.run` :328 → `autoConfirm.runStoppingOnFailure` :329 → `AutoConfirmBatch` :254 → `confirm` :256 → `item.autoConfirmationTime(now)` :258 → `Order.kt:343` → **지점 2 :346** → `confirmer.confirm` :262(합류점) |
| B 정산 | `settleWeeks` 주 루프 :1615-1621 → `settleWeek` :1634 → 대상 SQL :1600 → `GET /me/settlements` → `OrderController.kt:49` → `listSettlements` :1706 | `SettlementWeek.pending`(`domain/Settlement.kt:31`) → `settle` :343 → `settlements.targets(week.cutoff)` :345 → `JpaSettlementRepository.kt:20-21` → `GET /me/settlements` → `api/OrderController.kt:59-60` |

- 실측 근거: 주입(none)에서 A·B가 실패하고 정답(fix)에서 둘 다 통과한다. naive-1은 B만, naive-2는 A만 실패한다(§10). 이로써 A는 지점 1을, B는 지점 2를 지난다는 것이 두 구조에서 실행으로 확인된다.

## 7. 제외한 유사 지점과 사유

- 정산 cutoff·주 루프·적립 만료 `+365`·분쟁 `+14일`: 하류 규칙이라 그대로 둔다. 오진 유도 수단이다.
- 등급 산정 기준 금액 SQL(`confirmed_at ∈ [from, to)` — god `:1549`, layered `infra/JpaUserRepository.kt:18`): 하류이므로 손대지 않는다.
- 자동 확정 대상 선택(`delivered_at <= now − 7일` — god `:1497-1498`, layered `KeysetBatchTargets.kt:36`·`Order.autoConfirmCutoff` :403): 가드는 그대로 둔다. 주입하면 고칠 곳이 늘고 계약 테스트가 대상 수로 깨진다.

## 8. regression-exclude · verify-exclude

- 둘 다 비어 있다(주석 1줄만). 정답은 원본 동작을 되돌린다(inject + fix = 원본 코드, 주석만 중립 문장). 정답이 의도적으로 바꾸는 계약 테스트·verify 검사가 없다. 실측은 §10의 fix 전 단계 행이다.

## 9. symbols

- `fix`: 정답 패치 hunk의 원래 쪽(주입 사본) 범위, 문맥 3줄 포함. god `Marketplace.kt:873-879`·`:1504-1510`, layered `Order.kt:323-329`·`:343-349`.
- `edit`: 고칠 파일 전체(god `Marketplace.kt:1-1771`, layered `Order.kt:1-534`)와 layered 합류점 파일(`BuyerItemService.kt:1-121`).
  - god는 거의 모든 로직이 `Marketplace.kt` 한 파일이라 `edit` 적중이 넓다. 옛 과제와 같은 정의에서 나오는 성질이다. 첫 편집 적중은 god에서 쉽게 참이 된다(해석 주의).
- `read`: 확정 두 지점·합류점·자동 확정 배치·분쟁 기한·정산 SQL·주 루프.

## 10. 채택 조건 실측

(check 결과 — 아래 표는 `experiment/results/checks/checks.jsonl`의 T1 행에서 옮김)

모든 행: `code_sha` 0fe3f36, `code_dirty` True(T1 산출물 미커밋). 2026-10-27, 순차 실행. 시각은 행의 `at`.

| 구조 | 패치 | 회차 | 단계 | success | 결과 | 숨은 테스트 실패 단언(실패 메시지 첫 단언) |
|---|---|---|---|---|---|---|
| god | none | 1 | public,hidden | False | public fail · hidden fail(2/2) | A: 응답 confirmedAt `expected 2026-10-07T15:00Z but was 2026-10-10T15:00Z` · B: 정산 주 `expected [2026-10-19] but was []` |
| layered | none | 1 | public,hidden | False | 〃 | 〃(같은 값) |
| god | fix | 1 | 전 단계 | **True** | public·hidden pass, contract 122/122, contract2 89/89, verify 위반 0(두 단계) | — |
| layered | fix | 1 | 전 단계 | **True** | 〃 | — |
| god | fix | 2~5 | public,hidden | True ×4 | pass | — |
| layered | fix | 2~5 | public,hidden | True ×4 | pass | — |
| god | naive-1(구매자만) | 1 | public,hidden | False | public fail · hidden fail(1/2) | B: 정산 주 `expected [2026-11-02] but was []`(A 통과) |
| layered | naive-1 | 1 | 〃 | False | 〃 | 〃 |
| god | naive-2(자동만) | 1 | 〃 | False | **public pass** · hidden fail(1/2) | A: 응답 confirmedAt(B 통과) |
| layered | naive-2 | 1 | 〃 | False | 〃 | 〃 |
| god | naive-3(`<=`) | 1 | 〃 | False | public fail · hidden fail(2/2) | A: confirmedAt · B: 정산 주 `[2026-10-19]` vs `[]` |
| layered | naive-3 | 1 | 〃 | False | 〃 | 〃 |
| god | naive-4(정산 LEAST) | 1 | 〃 | False | **public pass** · hidden fail(2/2) | A: confirmedAt · B: DB confirmed_at `expected 2026-10-20T03:00Z but was 2026-10-25T16:00Z`(정산 주 단언은 통과) |
| layered | naive-4 | 1 | 〃 | False | 〃 | 〃 |
| god | naive-5(루프 +7) | 1 | 〃 | False | public fail · hidden fail(2/2) | A: confirmedAt · B: `weekly-settlement → 400 UNEXPECTED_ERROR`(이미 정산한 주를 다시 정산하다 서버 오류 — 원인 로그는 보지 않음) |
| layered | naive-5 | 1 | 〃 | False | 〃 | 〃 |
| god | alt-1(합류점) | 1 | public,hidden | **True** | pass | — |
| layered | alt-1 | 1 | public,hidden | **True** | pass | — |

- fix는 구조마다 5/5다(전 단계 1회 + public·hidden 4회). 주입 상태에서는 숨은 테스트가 실패한다. naive 5종은 두 구조 모두 숨은 테스트에서 실패한다. 실패 단언은 §5의 기대와 같다. 대안 정답 alt-1은 두 구조 모두 통과한다.
- naive-5는 의미 단언이 아니라 배치 서버 오류(400)로 실패한다. 결정적으로 실패하지만 실패 이유는 "주를 잘못 고른 결과"가 아니라 "중복 정산 오류"다. 메인 판정(2026-10-27): 받아들인다. 주입이 그대로 남아 있으므로 A의 confirmedAt 단언이 어차피 실패한다.
- 변경 이력: 첫 none 2회(01:40·01:44)와 fix 전 단계 2회(01:54·02:04)는 숨은 테스트 A의 confirmedAt 단언 메시지를 고치기 전에 돌렸다. 그 메시지에 응답 본문 전체를 넣어 300자 잘림으로 expected/actual이 안 보였다. 메시지만 바꿨고 단언 내용은 같다. 그 뒤 none을 두 구조에서 다시 돌렸다(위 표의 none 행 = 수정 뒤, 결과 같음).

### 10.1 후속 반영 뒤 재확인(M-1 누출 수정 + 적립 만료 단언 + naive-6, 2026-10-27 03:45~04:20)

바뀐 것: layered 주입 주석 3곳(§2.1), 숨은 테스트 A·B에 EARN `expires_at` 단언 추가, `naive-6` 추가. 패치를 다시 만든 뒤 public,hidden 단계만 1회씩 순차 실행했다. 고정 SHA에서의 전체 재채택(fix 5회 등)은 메인 몫이다.

| 구조 | 패치 | success | 결과 | 숨은 테스트 실패 단언 |
|---|---|---|---|---|
| god | none | False | public fail · hidden fail(2/2) | A: 응답 confirmedAt · B: 정산 주(이전과 같음) |
| layered | none | False | 〃 | 〃 |
| god | fix | True | pass | — |
| layered | fix | True | pass | — |
| god | alt-1 | True | pass | — |
| layered | alt-1 | True | pass | — |
| god | naive-6 | False | **public pass** · hidden fail(2/2) | A: 적립 유효기간 `expected 2027-10-07T15:00Z but was 2027-10-10T15:00Z` · B: 자동 확정 적립 유효기간 `expected 2027-10-20T03:00Z but was 2027-10-25T16:00Z` |
| layered | naive-6 | False | 〃 | 〃(같은 값) |
| god | naive-4 | False | public pass · hidden fail(2/2) | A: 응답 confirmedAt · B: DB confirmed_at — 첫 실행(§10)과 같은 단언·같은 값(결정적) |
| layered | naive-4 | False | 〃 | 〃 |

- §10 표의 행은 이 반영 전의 주입·숨은 테스트로 잰 것이다. naive-1·2·3·5의 실패 단언은 확정 시각·정산 단언에서 나고, 새 단언(적립 만료)은 그 뒤에 있으므로 결과가 바뀌지 않을 것으로 본다. 다시 돌리지는 않았다.

### 10.2 최종 재채택(고정 SHA, 2026-10-27 22:24 ~ 10-28 00:31)

모든 행: `code_sha` ae6deda, `code_dirty` False — 28행, 순차 실행, 결과 행 = `checks.jsonl`. 숨은 테스트는 R3-6 사례(7일 안 구매 확정) 추가본.

| 구조 | 패치 | 결과 |
|---|---|---|
| god·layered | none | public fail · hidden 2/2 fail |
| god·layered | fix ×5 | True 5/5(1회 전 단계: contract 122, contract2 89, verify 위반 0 — 두 회귀 단계) |
| god·layered | alt-1 | True |
| god·layered | naive-1·2·7 | hidden 1/2 fail(naive-2·7은 **public pass**) |
| god·layered | naive-3·4·5·6 | hidden 2/2 fail(naive-4·6은 **public pass**) |

- 변조 0, build 실패 0, 잔여 컨테이너 0. 두 구조의 숨은 실패 개수가 naive마다 같다. **T1 채택 확정.** 위 §10·§10.1의 dirty 기록은 이 표로 대체된다.
