# S2 설계 — 판매 제한 기준(bug, 용어 불일치)

> ADR-0008 §4.2 S2 행이 정본. 작성 기준: 브랜치 `search-cost-tasks`, HEAD `16fb920`(앱 소스는 `8449d1e` 이후 변경 없음 — 줄 번호는 이 HEAD의 `god/`·`layered/` 기준).
> 줄 번호: 주입은 두 구조 모두 **줄 수를 바꾸지 않는다**(주석 2줄 → 2줄, 코드 1줄 → 1줄). 그래서 원본 = 주입 사본 좌표다. symbols도 같은 좌표.

## 1. 의도

- 규칙(ADR-0004 §6): 판매자 귀책(검수 불합격·발송 기한 초과)마다 패널티 1행. 최근 30일 안(`created_at > 현재 − 30일`)이면서 마지막 제한을 건 시각(`sale_restricted_until − 30일`) 뒤의 패널티가 **3건 이상**이면 `sale_restricted_until = 현재 + 30일`. 판매 제한 중에는 새 판매 입찰이 403 `SALE_RESTRICTED`.
- 주입: 기준 **3 → 2**(두 구조 같은 의미). god는 리터럴, layered는 상수 — 구조 그대로 둔다.
- 유형: 용어 불일치. 과제 낱말은 **'판매 정지'·'판매자 때문에 취소된 거래'**이고, 코드 낱말은 '판매 제한·패널티·귀책·restrict·penalty'다. 과제 문구에 코드 낱말을 쓰지 않는다.
- 오진 유도: 형제 규칙(구매 경고 → 구매 제한, `>= 3`·`WARNINGS_TO_RESTRICT = 3`)이 같은 모양이다. layered는 같은 companion에서 3줄 위(`User.kt:79`), god는 450줄 떨어져 있다(`Marketplace.kt:1478`) — **구조 차이로 표시**(ADR-0008 §4.2 처치 표시).

## 2. 주입(`inject.{god,layered}.patch`)

| 구조 | 코드 | 줄 |
|---|---|---|
| god | `Marketplace.kt` `sellerFault`: `if (recentPenalties >= 3)` → `>= 2` | :1028 |
| layered | `domain/User.kt` companion `PENALTIES_TO_RESTRICT = 3` → `2`(사용처 `restrictIfPenalized` :73은 그대로) | :82 |

### 2.1 규칙 서술 주석 — 중립 문장(줄 수 유지)

전수 grep: 두 구조 `src/`(main·test)에서 `grep -rn "3건\|세 건\|3회\|>= 3\|three"` → 판매 제한 기준을 서술한 주석은 아래 2곳뿐이다(나머지는 구매 경고 :1468·`User.kt:56` — 형제 규칙이라 그대로, 그리고 코드 줄). `src/test`에는 앱 스모크 테스트 1개뿐이고 관련 낱말이 없다.

| 구조 | 위치 | 원문 → 주입 후 |
|---|---|---|
| god | `Marketplace.kt:1013-1014`(sellerFault KDoc) | "… → 판매 제한: / 최근 30일 안이면서 마지막 제한을 건 시각(sale_restricted_until − 30일) 뒤의 패널티가 3건 이상이면 현재 + 30일." → "… → 판매 제한 판정: / 최근 패널티(마지막 제한을 건 뒤의 것)로 sale_restricted_until을 정한다." |
| layered | `domain/User.kt:66-67`(restrictIfPenalized KDoc) | "판매자 패널티에 따른 판매 제한(ADR-0004 §6): 최근 30일 안(…)이면서 마지막 제한이 걸린 시각 / (sale_restricted_until − 30일) 뒤의 패널티가 3건 이상이면 sale_restricted_until = 현재 + 30일. 구매 경고와 같은 해석이다." → "판매자 패널티에 따른 판매 제한(ADR-0004 §6): 최근 패널티(마지막 제한이 걸린 뒤의 것)로 / sale_restricted_until을 정한다. 구매 경고와 같은 해석이다." |

- 기준값만이 아니라 **같은 문장의 30일 창·제한 기간 서술도 함께 뺐다**(두 구조 같게). 이유: 한 문장 안의 규칙 서술이라 기준만 지우면 문장이 깨지고, 남기면 '30일'이 god에서 고칠 줄 14줄 위 적중(7줄 이하 결과)이 된다. 창·기간 규칙은 코드(`Duration.ofDays(30)`·`PENALTY_WINDOW`)에 그대로 있다.
- 중립 문장에 '기간'·'세는'·'횟수' 같은 과제 후보 낱말이 들어가지 않게 골랐다(첫 판 '세어 … 기간을 정한다'가 god '기간' 9줄 중 :1014 적중을 새로 만들어 고쳤다).
- 그대로 둔 주석: god :1012 "판매자 귀책(검수 불합격·발송 기한 초과 공용)" — 규칙 서술이 아니고 구조의 일부다. 대신 과제 문구에서 '검수'·'발송'·'귀책'을 쓰지 않는다(§3). layered `ItemRefunder.kt:68` "→ 판매 제한 판정"(기준 없음), `User.kt:87` "판매 제한 판정에 세는 패널티의 시작"(창 시작 함수).
- 주입 사본은 두 구조 모두 컴파일된다(§8 none 행).

## 3. 찾기 비용 조건(ADR-0008 §4.1) — 작성자 자체 측정

측정: 주입 사본 `app/src/main`에 `grep -rn -- "<낱말>"`(대소문자 구분). 고칠 줄 ±40: god `Marketplace.kt:988-1068`(:1028), layered `User.kt:33-122`(fix :82 ∪ alt :73, 보수적으로 합집합). **직접 단서** = 결과 ≤ 10줄이고 그중 하나가 ±40 안. 최종 판정은 별도 리뷰어 몫이다.

| 낱말(출처) | god 줄 수 | god ±40 적중 | layered 줄 수 | layered ±40 적중 | 직접 단서 |
|---|---|---|---|---|---|
| 판매 정지 / 정지 (TASK) | 0 / 0 | — | 0 / 0 | — | 아님 |
| suspend / suspended (영어 추측) | 0 / 0 | — | 0 / 0 | — | 아님 |
| 거래 취소 / 취소된 (TASK) | 0 / 1 | — | 0 / 1 | — | 아님 |
| 취소 (TASK) | 27 | :1035·1036·1037·1042 | 48 | — | 아님(>10) |
| 두 번 / 세 번째 (TASK) | 0 / 0 | — | 0 / 0 | — | 아님 |
| twice / third (영어) | 0 / 0 | — | 0 / 0 | — | 아님 |
| 판매자 때문 (TASK) | 0 | — | 0 | — | 아님 |
| 보내지 (TASK) | 0 | — | 0 | — | 아님 |
| 기한 (TASK) | 20 | :1012 | 44 | — | 아님(>10) |
| 새 판매 입찰 / 판매 입찰 (TASK) | 0 / 3 | — | 0 / 7 | — | 아님 |
| 구매 입찰 (TASK) | 2 | — | 6 | — | 아님 |
| 거절 (TASK) | 2 | — | 6 | — | 아님 |
| reject (영어) | 4 | — | 7 | — | 아님 |
| 체결 / 결제 (TASK) | 46 / 20 | :1013 / :1039 | 72 / 32 | :38 / — | 아님(>10) |
| 3일 (TASK) / ofDays(3) (공개 테스트) | 3 / 5 | — | 8 / 3 | — | 아님 |
| 사이즈 / 시계 / 응답 (TASK) | 31 / 4 / 36 | — | 50 / 7 / 49 | — | 아님 |
| shipment-deadline (TASK·공개 테스트) | 4 | — | 7 | — | 아님 |
| 배치 (TASK) | 12 | — | 12 | — | 아님 |
| 201 / /bids / /orders (TASK·공개 테스트) | 2 / 3 / 2 | — | 2 / 3 / 2 | — | 아님 |
| SELL (공개 테스트) | 15 | :1013·1017·1066 | 26 | :43 | 아님(>10) |
| order_items / CANCELLED (공개 테스트) | 19 / 21 | — / :1068 | 23 / 32 | — | 아님(>10) |
| cancel (영어) | 44 | :1035·1064 | 62 | — | 아님 |
| fault (영어 — '판매자 때문'의 추측) | 15 | 여러 줄 | 20 | — | 아님(>10) |
| **Fault / sellerFault** (영어 식별자 추측) | **3** | **:1016** | 19 / 4 | — | **god만 직접**(아래) |
| seller / sale (영어) | 159 / 18 | 여러 줄 | 221 / 29 | 여러 줄 | 아님(>10) |

- **쓰지 않은 낱말(측정해서 뺐다):** '검수'(god 5줄 중 :1012) · '발송'·'발송 기한'(god 8·5줄 중 :1012) · '귀책'(god 7줄 중 :1009·1012) · '판매 제한'(god·layered 모두 직접) · '패널티'(god 7줄 중 :1013) · '3건'(두 구조 직접) · '30일 안'(layered 1줄 = `User.kt:55` 구매 경고 KDoc, 고칠 줄 27줄 위) · '한 달'(layered `User.kt:102` "속한 달"이 걸림) · '기간'(god 원 KDoc :1014) · '세는'·'횟수'·'센다'(layered `User.kt:56·84·87`) · '30일'(TASK에서 뺐다 — 일수를 말하지 않아도 과제가 성립하고, 숫자를 주면 `Duration.ofDays(30)` 추측 grep이 god 10줄·layered 5줄로 두 구조 모두 고칠 줄 ±40에 닿는다).
  - 그래서 TASK.md는 일수를 말하지 않고 "언제까지의 취소를 함께 치는지 … 지금과 같다"로 쓴다. 30일 창은 숨은 테스트 C가 본다.
- **남은 비대칭(처치로 표시):** 'sellerFault'·'Fault'는 god의 private 함수 이름(:1016, 고칠 줄 12줄 위)이다. 과제 낱말 '판매자 때문에'를 영어 식별자로 추측해 대소문자를 맞춰 grep할 때만 걸린다(소문자 'fault'는 15줄로 직접 아님). layered에서 같은 추측('SellerFault' 3줄)은 `service/ItemRefunder.kt`의 `SellerFaults`로 가고, 거기서 `restrictIfPenalized`로 한 단계 더 가야 한다. god가 규칙을 귀책 처리 함수 안에 두고 layered가 도메인(`User`)으로 뺀 구조 결과라 처치로 본다. 리뷰어가 이 낱말을 뽑으면 god 직접 단서 1개 — 그때는 메인 판단.
- **실행 출력 단서(측정 대상 밖, 기록):** 공개 테스트가 실패하면 단언 메시지는 "새 판매 입찰의 응답 상태 expected 201 but was 403"만 보인다(응답 본문을 메시지에 넣지 않았다 — `SALE_RESTRICTED`·"판매 제한 중" 문구가 실행 출력으로 새지 않게). 에이전트가 직접 요청을 보내 본문을 보면 `SALE_RESTRICTED`(god 3줄 — :206 등, 고칠 줄 밖 / layered 10줄 — `User.kt:44`가 :82에서 38줄)를 얻는다. layered만 ±40에 닿는 셈이지만, 측정 정의(TASK·공개 테스트 소스) 밖이고 얻으려면 한 단계 더 필요하다.
- **낱말 대칭:** TASK·공개 테스트 낱말 중 한 구조에만 직접 단서인 것은 위 Fault 1건(처치)뿐이다.

## 4. 정답

- `fix.{god,layered}.patch`: 기준 복원. god :1028 `>= 2` → `>= 3`, layered :82 `2` → `3`. 주석은 되돌리지 않는다. inject + fix = 원본 코드(주석만 중립 문장 — 실측: 원본과 주석 아닌 줄 diff 0).
- `alt-1.{god,layered}.patch`(ADR §4.2): 비교 형태 수정. god :1028 `>= 2` → `> 2`(같은 줄), layered 사용처 :73 `counted >= PENALTIES_TO_RESTRICT` → `counted > PENALTIES_TO_RESTRICT`(상수 2 유지). 의미는 3건 이상과 같다.
- 숨은 테스트는 응답 상태·code와 DB `sale_restricted_until`·`purchase_restricted_until`만 보므로 두 형태를 다 받는다.

## 5. 순진한 오답(naive-N — 바꾼 줄이 판정 범위 밖이어야 하고, 숨은 테스트에서 실패해야 한다)

| N | 패치(두 구조 같은 의미) | 바꾼 줄 god / layered | 기대 실패 |
|---|---|---|---|
| 1 | 오진(형제 규칙): 구매 경고 기준 3 → 4 | :1478 / `User.kt:79` | A·C(주입 그대로) + D(구매 경고 3건째에 제한 없음) |
| 2 | 오진(창): 판매 제한 30일 창 → 15일 | :1026 / `User.kt:80` | A·C(주입 그대로 — 같은 시각 2건에 이미 제한) |
| 3 | 그럴듯한 오답: 방금 쓴 패널티를 빼고 센다(`it.isBefore(now) &&` — "새 패널티가 두 번 세어진다"는 오진). 공개 테스트·A·C는 통과 | :1027 / `User.kt:72` | B(같은 시각 3건이 모두 '지금'이라 0건 → 제한 없음) |

- 판정 범위(§6) 비적중: god fix = {1028}, 오답 줄 {1478, 1026, 1027}. layered fix∪alt = {73, 82}, 오답 줄 {79, 80, 72}. 모두 겹치지 않는다(문맥 줄은 범위에 넣지 않는다 — ADR-0008 §5.2).
- 고려했으나 만들지 않은 것: 형제·대상 규칙 둘 다 `>` 로 바꾸는 과잉 수정 — 고칠 줄을 건드려 판정 범위 적중이 되므로 순진한 오답(비적중) 조건에 맞지 않는다(숨은 D가 잡기는 한다). 제한 기간(`now.plus(30일)`) 수정 — god에서 고칠 줄과 같은 줄이라 같은 이유로 뺐다.

## 6. symbols(판정 범위 — ADR-0008 §5.2)

- `fix`: 정답·alt 패치가 **실제로 바꾼 줄만**. god `Marketplace.kt:1028`(fix·alt-1 같은 줄) — **1줄**. layered `User.kt:82`(fix) + `User.kt:73`(alt-1) — **2줄**. layered `:79`(구매 경고 상수)는 오답 자리라 넣지 않는다.
- symbols 파서(`events.parse_symbols`)의 종류가 fix|read|edit뿐이라 alt 줄도 `fix`로 적고 메모에 alt-1을 표시했다.
- `edit`: 고칠 파일 전체(ADR-0005 §5.2 첫 편집 적중 전용 — ADR-0008 주 지표에는 쓰지 않는다). `read`: 판정 함수·상수·증상 위치(새 입찰 확인)·형제 규칙.

## 7. 공개·숨은 테스트

- 공개(`cases/S2/checks/.../pub/S2Test.kt`): 판매자 1명이 사이즈 2개에 판매 입찰 → 새 구매자 2명이 체결·결제 → 결제 + 3일에 `shipment-deadline` → 두 항목 취소 확인(`status` 가 CANCELLED로 시작) → 새 사이즈에 새 판매 입찰 201. 주입 상태에서 403으로 실패한다. 판매자 귀책을 검수 FAIL이 아니라 발송 기한 초과로 만든 것은 TASK 문구에서 '검수'를 피하기 위해서다(공개 테스트 소스도 측정 대상).
- 숨은(`hidden/S2HiddenTest.kt`) — `contract2/PenaltyTest` 문체(판매 입찰 미리 걸기 → 새 구매자 체결·결제 → shipment-deadline):
  - A: 같은 시각 2건 → `sale_restricted_until` NULL, 새 판매 입찰 201 / 하루 뒤 3건째 → 현재 + 30일, 새 판매 입찰 403 `SALE_RESTRICTED`.
  - B: 한 배치에서 같은 시각 3건 → 현재 + 30일, 403 `SALE_RESTRICTED`(방금 쓴 패널티도 센다 — naive-3).
  - C: 30일 창 — 판매자 둘이 t에 2건씩. t+29일의 3건째 → 제한(현재 + 30일). t+30일(정확히 30일 뒤)의 3건째 → 제한 없음, 새 판매 입찰 201.
  - D: 형제 규칙 그대로 — 구매 경고(payment-due) 2건 → `purchase_restricted_until` NULL, 새 구매 입찰 201 / 3건째 → 현재 + 7일, 403 `PURCHASE_RESTRICTED`.
  - 각 테스트는 현재 시계에서 출발한다(실행 순서 무관).
- 기대 숨은 실패: none = A·C(2/4) · naive-1 = A·C·D(3/4) · naive-2 = A·C(2/4) · naive-3 = B(1/4). 주입 상태에서 B는 통과한다(같은 시각 3건 → 2건째에 이미 현재 + 30일, 3건째는 마지막 제한 뒤가 아니라 그대로 — 결과 같음).

## 8. regression-exclude · verify-exclude

- 둘 다 비어 있다(주석 1줄). 정답 = 원본 코드라 의도적으로 바뀌는 계약 테스트가 없다. 주입 상태에서는 `contract2/PenaltyTest`가 깨진다(ADR §4.2 "PenaltyTest가 주입을 잡음").

## 9. 채택 조건 실측(작성자 스모크 — 채택 행렬은 메인이 고정 SHA에서)

모든 행: `code_sha` 6d02f1f, `code_dirty` True(S2 산출물 미커밋), 2026-11-01 10:41~12:29, 1레인 순차. 결과 행 = 작업 scratchpad `sct/S2-checks/checks.jsonl`. 첫 시도(10:36)는 숨은 테스트 도우미 SQL의 `paid_at` 모호성 오류로 버리고(테스트 코드 오류 — `o.paid_at`로 고침) 다시 돌렸다.

| 구조 | 패치 | 단계 | success | 공개 | 숨은 실패(4개 중) | 회귀 |
|---|---|---|---|---|---|---|
| god·layered | none | public,hidden | False | fail(201 기대, 403) | A·C (2) | — |
| god·layered | fix | 전 단계 | **True** | pass | 0 | contract 122 · contract2 89 · contract3 252 통과, verify 위반 0 |
| god·layered | alt-1 | 전 단계 | **True** | pass | 0 | 〃 |
| god·layered | naive-1(구매 경고 3→4) | public,hidden | False | fail | A·C·D (3) | — |
| god·layered | naive-2(창 30→15일) | public,hidden | False | fail | A·C (2) | — |
| god·layered | naive-3(지금 패널티 빼고 세기) | public,hidden | False | **pass** | B (1) | — |

- 숨은 실패 수·실패 테스트가 패치마다 두 구조 같다(ADR-0008 §4.3-2). 실패 메시지 예(none, god): A "2건 뒤 sale_restricted_until expected null but was 2026-11-08T16:02Z", C "t의 2건 뒤(창 안 판매자) expected null".
- 판정 범위 점검(`runner/fce.py` `patch_ranges` — fix·alt 패치에서 바로 계산): god fix∪alt = {1028}, layered = {73, 82}. naive-1·2·3 바꾼 줄 god {1478}·{1026}·{1027}, layered {79}·{80}·{72} — 모두 비적중.
- 남은 일(메인): 고정 SHA에서 fix 5회 결정성·판정 2회 재현, 리뷰어의 찾기 단서 독립 측정.
