# D3 설계 — 발송 기한 값이 기능마다 어긋남(bug, 조사·보고 후 수정)

> ADR-0009 §4 D3 행이 정본. 채택 조건은 ADR-0008 §4.3 1·2번(찾기 비용·symbols 조건은 ADR-0009가 걸지 않는다 — symbols·groups 파일 없음).
> 작성 기준: 브랜치 `delegation-measure`, HEAD `81d7983`(앱 소스는 `ddd3d56`·`8389a02`와 같다 — 줄 번호는 이 HEAD의 `god/`·`layered/` 기준).
> 줄 번호: 주입은 두 구조 모두 **줄 수를 바꾸지 않는다**(주석 1줄 → 1줄, 코드 1줄 → 1줄). 원본 = 주입 사본 좌표다.

## 1. 의도

- 규칙: 발송 기한 = 결제 시각(`order_items.paid_at`) + 3일, 그 정각부터 기한이 지난 것. 이 값을 쓰는 기능은 넷이다.
  - 발송 등록(§3): 현재 < 기한이어야 등록(아니면 409 `DEADLINE_PASSED`).
  - `shipment-deadline`(ADR-0004 §5): 기한 ≤ 현재인 PAID 항목을 판매자 귀책 취소(대상 SQL + 잠근 뒤 재확인).
  - `deadline-reminder` `SHIPMENT_DUE_SOON`(ADR-0007 §7.1): 기한 − 1일 ≤ 현재 < 기한(결제 + 2일 ≤ 현재 < 결제 + 3일)에 판매자에게 알림.
  - `delivery-delay-scan` `SHIPMENT_OVERDUE`(ADR-0007 §12.2): 기한 ≤ 현재인 PAID 항목에 지연 기록 1행.
- 주입: 두 곳을 **하루 늦게**(기한을 4일로 본 것처럼) — 지연 기록 3일 → 4일, 알림 창 [2일, 3일) → [3일, 4일). 두 구조 같은 의미.
- 유형: 조사·보고. 과제 문안은 지연 기록 증상만 말하고 "기한을 쓰는 곳을 모두 찾아 맞추라"고 한다. 알림 창 어긋남은 제보하지 않는다 — 스스로 찾아야 하는 부분(탐색량의 핵심). 보고는 판정하지 않는다(판정은 코드 결과만).

### 1.1 발송 기한을 쓰는 곳(전수 — `grep -rn "ofDays(\|paid_at\|SHIP_WITHIN\|SHIPMENT_FROM\|SHIPMENT_UNTIL\|cutoff("` 두 구조 `src/main`)

| 기능 | god `Marketplace.kt` | layered |
|---|---|---|
| 발송 등록 | :822 `item.paidAt!!.plus(Duration.ofDays(3))` | `domain/Order.kt:280` `paidAt!!.plus(SHIP_WITHIN)` ← 상수 `:396` |
| shipment-deadline 대상 SQL | :1551 `now.minus(Duration.ofDays(3))` | `infra/KeysetBatchTargets.kt:40` `OrderItem.shipmentOverdueCutoff(it)` ← `Order.kt:401` ← `:396` |
| shipment-deadline 잠근 뒤 재확인 | :1556 `item.paidAt!!.plus(Duration.ofDays(3))` | `Order.kt:338` `isShipmentOverdue` ← `:396` (호출 `service/BatchService.kt:256`) |
| 마감 임박 알림 창 | :2463-2464(SQL + 파라미터 `ofDays(2)`·`ofDays(3)`) **주입** | `domain/Notification.kt:93-94` `SHIPMENT_FROM`·`SHIPMENT_UNTIL` **주입** ← 사용 `infra/ReminderSql.kt:42-43` |
| 지연 기록 | :2878 `now.minus(Duration.ofDays(3))` **주입** | `domain/Tracking.kt:88` `SHIPMENT_OVERDUE(…, Duration.ofDays(3))` **주입** ← 사용 `cutoff`(:94) ← `infra/DelaySql.kt:12` |

- god = 값 자리 5줄(:822·:1551·:1556·:2464·:2878), layered = 상수 3개(`Order.kt:396`·`Notification.kt:93-94`·`Tracking.kt:88`) — layered가 고칠 자리·확인할 자리 모두 적다. 파일럿 탐색량 게이트(ADR-0009 §4)가 잰다(위험 기록만).
- 두 구조 `src/test`에는 앱 스모크 테스트뿐이고 관련 낱말이 없다.

## 2. 주입(`inject.{god,layered}.patch`)

| 구조 | 코드 | 줄 |
|---|---|---|
| god | 알림 창: `"t.status = 'PAID' AND t.paid_at <= :before2d AND t.paid_at > :before3d"` → `:paidUntil`·`:paidSince`, 파라미터 `"before2d" to now.minus(ofDays(2)), "before3d" to now.minus(ofDays(3))` → `"paidUntil" to now.minus(ofDays(3)), "paidSince" to now.minus(ofDays(4))` | :2463·:2464 |
| god | 지연 기록: `now.minus(Duration.ofDays(3))` → `ofDays(4)` | :2878 |
| layered | `DeadlineReminder.SHIPMENT_FROM` `ofDays(2)` → `ofDays(3)`, `SHIPMENT_UNTIL` `ofDays(3)` → `ofDays(4)` | `Notification.kt:93`·`:94` |
| layered | `infra/ReminderSql.kt` 파라미터 이름 `before2d`·`before3d` → `paidUntil`·`paidSince`(SQL·map 두 줄, 값 식은 그대로) | `:42`·`:43` |
| layered | `ShipmentDelayKind.SHIPMENT_OVERDUE` `Duration.ofDays(3)` → `ofDays(4)` | `Tracking.kt:88` |

- 파라미터 이름 중립화: `before2d`/`before3d`가 주입한 값(3일·4일)과 어긋나 보이는 단서라 두 구조 모두 `paidUntil`/`paidSince`로 바꿨다(layered는 이름이 `ReminderSql.kt`에 있다). 정답은 이 이름을 되돌리지 않는다. 같은 모양의 `DISPUTE_WINDOW_CLOSING` `before12d`/`before14d`(god :2469-2470, layered `ReminderSql.kt:48-49`)는 그대로.

### 2.1 기한 서술 주석 — 중립 문장(줄 수 유지)

전수 grep: 두 구조 `src/main`에서 `grep -rn "발송 기한\|paid_at + 3일\|paid_at + 2일"` → 22줄. 그중 **주입한 두 기능의 값을 숫자로 서술한 주석**만 바꿨다(D2와 같은 원칙 — 주입 자리와 어긋나는 숫자 서술이 단서가 되므로). 주입하지 않은 기능(발송 등록·shipment-deadline)의 "paid_at + 3일" 주석은 코드와 맞으므로 그대로 둔다(god :813·:1543, layered `Order.kt:277`·`:337`·`BatchTargets.kt:19`).

| 구조 | 위치 | 원문 → 주입 후 |
|---|---|---|
| god | `Marketplace.kt:2872`(scanDeliveryDelays KDoc) | "SHIPMENT_OVERDUE = PAID이고 paid_at + 3일 ≤ 현재," → "SHIPMENT_OVERDUE = PAID이고 발송 기한이 지난 항목," |
| layered | `domain/Tracking.kt:87` | "PAID이고 paid_at + 3일 ≤ 현재(발송 기한 지남 — shipment-deadline이 아직 돌지 않은 항목)." → "PAID이고 발송 기한이 지난 항목(shipment-deadline이 아직 돌지 않은 항목)." |
| layered | `domain/BatchTargets.kt:67` | "… PAID이고 paid_at + 3일 ≤ 현재, 같은 (항목, kind) 행 없음." → "… PAID이고 발송 기한이 지난 항목, 같은 (항목, kind) 행 없음." |
| layered | `domain/BatchTargets.kt:49` | "… PAID, paid_at + 2일 ≤ now < paid_at + 3일, 같은 dedupe_key 알림 없음." → "… PAID, 결제 후 알림 창 안, 같은 dedupe_key 알림 없음." |
| layered | `domain/Notification.kt:72` | "order_items: PAID, paid_at + 2일 ≤ now < paid_at + 3일 → 판매자." → "order_items: PAID, 결제 후 알림 창(SHIPMENT_FROM·SHIPMENT_UNTIL) 안 → 판매자." |
| layered | `domain/Notification.kt:92` | "발송 기한 알림 창: 결제 + 2일부터 결제 + 3일 전까지." → "알림 창: 결제 시각 기준." |

- god 알림 창에는 숫자 서술 주석이 원래 없다(`remindDeadlines` KDoc :2446-2449는 일반 서술). layered가 주석 5곳, god가 1곳인 것은 원본 구조 차이다.
- `Notification.kt:92`는 처음에 "발송 기한 알림 창: 결제 시각 기준."으로 바꿨다가 리뷰 DT-4로 "알림 창: 결제 시각 기준."으로 고쳤다: god 알림 창 자리에는 '발송 기한' 낱말이 없는데 layered만 `grep "발송 기한"`이 알림 창 상수로 바로 이끄는 비대칭 단서였다. 줄 수 유지, 이 줄을 문맥으로 쓰는 fix·alt-1·naive-3·naive-4 layered 패치의 문맥 줄도 같이 바꿨다(패치 내용 변경 없음 — 기준 사본 + inject 위에 6개 패치 모두 `patch --dry-run` 적용 확인).
- 남는 단서(처치의 일부): 알림 종류 이름 `SHIPMENT_DUE_SOON`, 상수 이름 `SHIPMENT_FROM`/`SHIPMENT_UNTIL`, 지연 종류 `SHIPMENT_OVERDUE` — 두 구조 모두 있다.

## 3. 과제 문구(`cases/D3/TASK.md`)·공개 테스트

- 증상(결제 + 3일에 `delivery-delay-scan`을 돌려도 `SHIPMENT_OVERDUE` 지연 기록이 없고 하루 뒤에 생김)과 할 일(발송 기한을 쓰는 곳을 모두 찾아 결제 후 3일로 맞추고, 확인한 곳·고친 곳을 보고)만 쓴다. "기한이 어긋난 곳이 제보된 한 곳뿐이라고 가정하지 마라"를 넣었다. 알림·발송 등록·취소 배치·파일·함수 이름은 쓰지 않는다. 배치 이름은 재현에 필요한 `delivery-delay-scan`만(공개 테스트가 쓴다).
- 규칙 고정 문장: "기한 = 결제 시각 + 3일, 정각부터 지남" · "기한을 기준으로 정해진 다른 규칙(기한과의 시간 차, 정각 포함 여부 등)은 바꾸지 않는다"(알림 창을 기한 − 1일 ~ 기한으로 되돌릴 근거 — naive-3 배제).
- 공개(`pub/D3Test.kt`): 체결 1건을 API로 결제(포인트 0, 100,000원) → `paid_at` + 3일 − 1초에 `delivery-delay-scan` → 지연 기록 0 → 정각에 다시 → 1행, 항목 PAID. 주입 상태 실패 메시지: "발송 기한(결제 + 3일) 정각의 지연 기록 수 expected <1> but was <0>".

## 4. 정답

- `fix.{god,layered}.patch`: 주입 값 되돌림 — god :2464(3·4 → 2·3)·:2878(4 → 3), layered `Notification.kt:93-94`(3·4 → 2·3)·`Tracking.kt:88`(4 → 3). 파라미터 이름·주석은 되돌리지 않는다. inject + fix = 원본 코드(mk.py 단언: 원본과 차이는 중립화 주석·파라미터 이름뿐).
- `alt-1.{god,layered}.patch`: **발송 기한을 값 하나로 모으는** 형태(조사·보고 과제에서 나올 만한 정리). god는 `shipWithin = Duration.ofDays(3)` 속성을 :813 앞에 새로 두고 다섯 자리 모두 그것을 쓴다(알림 창 = `shipWithin.minusDays(1)`·`shipWithin`). layered는 `OrderItem.SHIP_WITHIN`을 공개(`Order.kt:396`)하고 `Notification.kt:93-94`(`SHIP_WITHIN.minusDays(1)`·`SHIP_WITHIN`)·`Tracking.kt:88`이 그것을 쓴다. 줄 수가 바뀐다(god +3, layered +1 — 이 ADR은 첫 편집 지표를 쓰지 않으므로 문제없다).

## 5. 순진한 오답(naive-N — 컴파일되고 그럴듯하며 숨은 테스트에서 실패해야 한다)

| N | 패치(두 구조 같은 의미) | 바꾼 줄 god / layered | 공개 | 기대 숨은 실패 |
|---|---|---|---|---|
| 1 | 제보된 지연 기록만 고침(알림 창 그대로) | :2878 / `Tracking.kt:88` | **통과** | C·D (2) |
| 2 | 반대 방향 통일: 발송 등록·shipment-deadline도 4일로(지연 기록·알림 창 그대로 — "4일이 다수"로 오판) | :822·:1551·:1556 / `Order.kt:396` | 실패 | A·B·C·D·E (5) |
| 3 | '4일'만 3으로: 지연 기록 + 알림 창의 4일 쪽 끝만(다른 끝 3일 그대로 → 창이 빈다) | :2464·:2878 / `Notification.kt:94`·`Tracking.kt:88` | **통과** | C·D (2) |
| 4 | 알림 창만 고침(지연 기록 그대로) | :2464 / `Notification.kt:93-94` | 실패 | E (1) |

- naive-1·3이 공개 테스트를 통과하는 함정이다. 3은 `grep "ofDays(4)"`로 찾은 4를 모두 3으로 바꾸는 흔한 형태다.

## 6. 숨은 테스트(`hidden/D3HiddenTest.kt` + `D3Fixtures.kt`)

- 준비 도우미 `D3Fx`: 사용자·빈 사이즈만 SQL, 입찰·체결·결제(포인트 0, 100,000원)·발송 등록·배치는 API. `paid_at`은 DB에서 읽는다.
- 각 테스트는 그 시작 시점의 현재 시계에서 새로 결제하고, 그 결제 시각에서 상대 시각으로만 움직인다(메서드 이름 순, 테스트마다 시계가 3~4일 앞으로 — 뒤 테스트는 새 결제 시각을 기준으로 하므로 엮이지 않음). 같은 순간에 두 결제가 필요하면 `advance(1)`로 1초 차이를 둔다(멈춘 시계 — 결제 시각 차를 단언으로 확인).
  - A 발송 등록: x·y 결제 → x + 3일 − 1초 발송 200·SHIPPED_TO_CENTER → y + 3일 정각 발송 409 `DEADLINE_PASSED`, PAID 그대로.
  - B shipment-deadline: x + 3일 − 1초 실행 → PAID → 정각 실행 → `CANCELLED_SELLER_FAULT`.
  - C 알림 시작: x + 2일 − 1초 `deadline-reminder` → 그 판매자 `SHIPMENT_DUE_SOON:<item>` 0 → + 2일 정각 → 1.
  - D 알림 끝: y, (1초 뒤) z 결제 → y + 3일 정각(z는 + 3일 − 1초) → y 0, z 1.
  - E 지연 기록: x, (1초 뒤) y 결제 → x + 3일 정각(y는 − 1초) `delivery-delay-scan` → x 1행, y 0, x PAID → 하루 뒤 재실행 → x 1행(중복 없음).
- A·B는 주입이 건드리지 않은 기능이라 none에서는 통과한다(naive-2 — 반대 방향 통일 — 를 잡는다).
- 기대 숨은 실패: none = C·D·E(3/5) · naive-1 = C·D(2) · naive-2 = A~E(5) · naive-3 = C·D(2) · naive-4 = E(1).

## 7. regression-exclude · verify-exclude

- 만들지 않았다(파일 없음 = 빈 목록). 정답 = 원본 코드라 의도적으로 바뀌는 계약 테스트가 없다. 주입 상태에서는 `contract3/A4DeadlineReminderTest`의 SHIPMENT_DUE_SOON 두 메서드와 `A11DeliveryDelayScanTest` SHIPMENT_OVERDUE가 깨진다(숨은 재료와 같은 규칙).

## 8. 채택 조건 실측(작성자 스모크 — 2회 재현·5회 결정성은 메인이 커밋 뒤)

모든 행: `code_sha` 66cbb30(12:19~16:24 시작) · 6139696(layered naive-4·god·layered alt-1) — 81d7983 위에 메인의 D2·D1 자산 커밋뿐(앱·behavior-tests·러너 변경 없음, `git diff --name-only 81d7983 HEAD`가 cases·oracle D1·D2뿐), `code_dirty` True(D3 산출물 미커밋), 2026-11-03 12:19~17:28, `flock /tmp/ccle-judge.lock` 1레인 순차(가용 메모리 12GB 미만이면 대기). 결과 행 = `experiment/results-del-tasks/checks.jsonl`(D1·D2 행과 같은 파일).

| 구조 | 패치 | 단계 | success | 공개 | 숨은 실패(5개 중) | 회귀 |
|---|---|---|---|---|---|---|
| god·layered | none | public,hidden | False | fail("발송 기한(결제 + 3일) 정각의 지연 기록 수 expected <1> but was <0>") | C·D·E (3) | — |
| god·layered | fix | 전 단계 | **True** | pass | 0 | contract 122 · contract2 89 · contract3 252 통과 |
| god·layered | alt-1 | 전 단계 | **True** | pass | 0 | 〃 |
| god·layered | naive-1(지연 기록만) | public,hidden | False | **pass** | C·D (2) | — |
| god·layered | naive-2(4일로 통일) | public,hidden | False | fail | A·B·C·D·E (5) | — |
| god·layered | naive-3(4일 쪽만 3으로 — 빈 창) | public,hidden | False | **pass** | C·D (2) | — |
| god·layered | naive-4(알림 창만) | public,hidden | False | fail | E (1) | — |

- 숨은 실패 수·실패 테스트 이름이 패치마다 두 구조 같다(ADR-0008 §4.3-2). tampered 0(모든 행 `[]`). 판정 시간: public,hidden 223~293초, 전 단계 765~893초.
- 실패 메시지 예(none, 두 구조 같음): C "결제 + 2일 정각 마감 임박 알림 수 expected <1> but was <0>", D "결제 + 3일 정각 마감 임박 알림 수 expected <0> but was <1>", E "결제 + 3일 정각 지연 기록 수 expected <1> but was <0>".
- 남은 일(메인): 고정 SHA에서 fix·alt 판정 2회 재현, fix 숨은 테스트 5회 결정성, none·naive 2회째.

### 리뷰 반영 뒤 재확인 (DEL-F1 — DT-4 layered 주석 중립화)

`inject.layered.patch`가 바뀌어(`Notification.kt:92` 주석 한 줄) 다시 돌렸다. 2026-11-03 18:48 ~ 20:15, `code_sha` `01edabf`, `code_dirty` True(미커밋), `flock /tmp/ccle-judge.lock` 1레인 순차(가용 메모리 12GB 이상에서만 시작 — 대기 0회). 바뀐 패치는 그 주석 줄이 든 inject(+줄)·fix·alt-1·naive-3·naive-4(문맥 줄)뿐이고, mk.py(주석 문자열만 고침)로 다시 만든 패치 14개가 손으로 고친 것과 바이트 단위로 같았다(mk.py의 inject + fix = 원본 단언 통과).

| 패치 | 단계 | god | layered | 공개 | 숨은 실패(5개 중, 두 구조 같음) | 회귀 |
|---|---|---|---|---|---|---|
| fix | 전 단계 | True | True | pass | 0 | contract 122 · contract2 89 · contract3 252 통과 |
| alt-1 | 전 단계 | True | True | pass | 0 | 〃 |
| none | public,hidden | False | False | fail | C·D·E (3) | — |
| naive-1 | public,hidden | False | False | **pass** | C·D (2) | — |
| naive-2 | public,hidden | False | False | fail | A·B·C·D·E (5) | — |
| naive-3 | public,hidden | False | False | **pass** | C·D (2) | — |
| naive-4 | public,hidden | False | False | fail | E (1) | — |

- 위 첫 실측과 패치마다 결과·실패 이름이 같다. tampered 0(모든 행).
- 위험(기록만, 리뷰 DT-3): TASK.md의 "확인한 곳·고친 곳을 보고한다"는 **판정하지 않는다** — 숨은 테스트·회귀는 동작만 본다. 보고 요구는 탐색 범위를 넓히게 하는 유도 문구일 뿐이고, 보고의 정확성·완전성은 이 과제의 성공 판정에 들어가지 않는다.
- 위험(기록만, 리뷰 DT-6): 알림 창 되돌림(2·3일)은 과제 문구가 직접 요구하지 않는다. 에이전트가 알림 창도 발송 기한을 쓰는 곳이라고 알아보는 근거는 알림 이름 `SHIPMENT_DUE_SOON`의 뜻과 "기한 뒤에 오는 마감 임박 알림은 무의미하다"는 추론뿐이다. TASK.md의 "기한을 기준으로 정해진 다른 규칙은 바꾸지 않는다"는 naive-3(빈 창)을 배제하는 근거는 되지만 **naive-1(알림 창을 그대로 둠)을 배제하지 못한다** — 알림 창을 '기한과 무관한 다른 규칙'으로 읽으면 naive-1도 문구에 맞는다. 그래서 숨은 C·D 실패가 '탐색 누락'인지 '문구 해석 차이'인지 결과만으로 가를 수 없다(보고 내용·궤적으로만 구분).
- 위험(기록만): layered는 고칠 자리가 상수 2곳(`Notification.kt:93-94`·`Tracking.kt:88`)이고 `SHIP_WITHIN`·`SHIPMENT_FROM/UNTIL`·`ShipmentDelayKind` 이름으로 바로 닿아, god(3,667줄 한 파일 안 5자리)보다 탐색량이 적을 수 있다 — ADR-0009 §4 탐색량 조건은 파일럿이 잰다.
