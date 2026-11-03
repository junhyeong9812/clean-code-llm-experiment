# D1 — 구매·판매 제한을 끝 시각 정각까지 (change, 교차 기능 변경)

> 정본: ADR-0009 §4 D1 행·함정·채택 조건(ADR-0008 §4.3의 1·2, ADR-0005 §3.3). 찾기 비용 조건·symbols·groups는 ADR-0009가 걸지 않아 만들지 않았다.
> 좌표는 기준 사본(원본 + `inject.*.patch` — 주석만 바꾸고 줄 수 유지라 원본과 같은 좌표). 작성 기준 HEAD `81d7983`(브리프 기준 `8389a02` — 그 뒤 커밋은 docs만, 앱·behavior-tests·러너 변경 없음). 작성 DEL-T1 2026-11-03.

## 의도

규칙 하나(제한 중 = 끝 시각 ≥ 현재, 지금은 > 현재)를 **서로 다른 기능 다섯 영역의 여덟 곳**에 빠짐없이 넣어야 하는 과제다(ADR-0009 '큰 탐색'). 고칠 곳은 Kotlin 비교(`isAfter(now)`)와 SQL·JPQL 문자열(`> :now`) 두 형태로 흩어져 있고, layered는 6파일(도메인 3·infra 3)이다. 동작 주입은 없다(TYPE=change). `inject.*.patch`는 바뀌는 규칙(`> 현재`)을 서술한 주석만 중립 문장으로 바꾼다(S3 전례).

함정: ① 입찰 403만 고침(공개 테스트가 그것만 봄) ② 구매 쪽만 ③ SQL·JPQL 쪽(제한 알림·재무장·관리자 목록) 누락 ④ 구매 경고·판매 패널티 **재계수 기준**(`lastRestrictedAt` 뒤 = `isAfter`)까지 `≥`로 바꿈 — 그것은 제한 여부 판정이 아니라 '마지막 제한을 건 시각 뒤' 판정이라 바뀌면 안 된다(TASK.md "경고·패널티를 세는 방법 포함" 그대로).

## 계약 (TASK.md 요약)

- 구매 제한 중 = `purchase_restricted_until ≥ 현재`, 판매 제한 중 = `sale_restricted_until ≥ 현재`. 끝 < 현재·없음 = 제한 아님.
- 따르는 기능(TASK.md가 "전부"라고 나열 — 고칠 곳 파일·함수 이름은 쓰지 않음): 새 입찰 등록 · 가격 알림 발동과 재무장 · 판매자 등급 산정 · 제한 알림 · 관리자 제한 사용자 목록.
- 바꾸지 않음: 제한을 거는 규칙(경고·패널티 세는 방법 포함)·제한 기간·저장 끝 시각·알림 중복 방지 키·응답 형식.

## 구조별 고칠 곳 (정답 — 전수)

전수 확인: 두 구조 `src/main` 전체에 `grep -rni "restrict"`(열 이름·필드·SQL·DTO 모두 포함) → 제한 끝 시각을 **현재와 비교하는** 줄은 아래 8곳뿐(나머지는 값 저장·응답 DTO·재계수 기준 계산·enum/주석). god `Marketplace.kt` 1파일 8곳 / layered 6파일 8곳(브리프의 "9곳"은 호출처 `service/WishlistService.kt:162`를 센 것 — 고칠 줄 아님).

| 영역 | god `Marketplace.kt` | layered | 형태 | 정답 |
|---|---|---|---|---|
| 새 입찰 BUY | `:202` (`placeBid`) | `domain/User.kt:40` (`requireCanBid`) | Kotlin | `purchaseRestrictedUntil?.isAfter(now) == true` → `?.isBefore(now) == false` |
| 새 입찰 SELL | `:205` | `domain/User.kt:43` | Kotlin | `saleRestrictedUntil` 같은 변경 |
| 판매자 등급 | `:2110` (`recalcSellerGrades`) | `domain/SellerGrade.kt:70` (`assess`) | Kotlin | 같은 변경 |
| 제한 알림(구매) | `:2480` (`remindDeadlines`) | `infra/ReminderSql.kt:59` | SQL | `t.purchase_restricted_until > :now` → `>=` |
| 제한 알림(판매) | `:2484` | `infra/ReminderSql.kt:63` | SQL | `t.sale_restricted_until > :now` → `>=` |
| 가격 알림 발동 중지 | `:2704` (`scanPriceAlerts`) | `domain/Wishlist.kt:135` (`isPausedFor`, 호출 `service/WishlistService.kt:162`) | Kotlin | 같은 변경 |
| 가격 알림 재무장 | `:2787` (`rearmPriceAlerts` condition — 대상 조회·잠근 뒤 재확인이 같은 문자열) | `infra/JpaNotificationRepositories.kt:138` (`PriceAlertRearmSql.CONDITION`) | SQL | `u.purchase_restricted_until > :now` → `>=` |
| 관리자 제한 사용자 목록 | `:3246` (`restrictedUsers`) | `infra/JpaAdminQueryRepository.kt:26` | JPQL | `u.$column > :now` → `>=` (purchase·sale 공용 한 줄) |

- 고칠 줄 수: god 8 / layered 8. layered는 도메인 3파일(Kotlin 4줄)·infra 3파일(SQL·JPQL 4줄)로 나뉜다.
- 고르지 않은 비슷한 자리(바꾸면 오답): 재계수 기준 god `:1027`(판매 패널티)·`:1477`(구매 경고), layered `User.kt:61`·`:72` — `it.isAfter(lastRestrictedAt)`(naive-4 둘 다, naive-5 판매 쪽만). 제한을 거는 대입 god `:1028`·`:1478`, layered `User.kt:62`·`:73`. 응답 DTO(관리자 사용자 요약의 끝 시각)는 비교가 없다.
- S1~S4 고칠 줄과 겹침 없음. god `:2704`는 S3 alert 범위(`:2705-2708`) 바로 앞 줄.
- 앱의 '현재' 출처: 두 구조 모두 멈춘 시계(`FrozenClock` — 마이크로초 절단, `PUT /admin/clock`로만 앞으로)이고, SQL도 앱이 `:now`를 바인드한다(DB `now()` 미사용). 그래서 테스트가 `Clock.now()`를 끝 시각으로 넣으면 '끝 = 현재'가 정확히 성립한다(load-bearing — fix 숨은 테스트 통과로 실증).

## 주석 처리 (inject — 줄 수 유지, 두 구조 같은 방식)

전수 grep(`restricted_until`·`제한 끝`·`제한 중`·`RestrictedUntil` 주석 줄, `> 현재`·`> now`): 바뀌는 규칙(비교 `>`)을 적은 주석만 중립화. "제한 중이면"처럼 경계를 쓰지 않은 주석(god `:200`·`:2690`·`:2777`, layered `Wishlist.kt:134`·`WishlistService.kt:142`·`:172`)과 재계수 주석(god `:1014`·`:1468`, layered `User.kt:55-56`·`:66-67`)은 그대로.

| 구조 | 위치 | 전 | 후 |
|---|---|---|---|
| god | `Marketplace.kt:2086` | 판매 제한 중(sale_restricted_until > 현재)이면 | 판매 제한 중(sale_restricted_until 기준)이면 |
| god | `Marketplace.kt:3236` | 그 제한 끝 > 현재인 사용자 | 그 제한 중인 사용자 |
| layered | `domain/User.kt:38` | 구매는 purchase_restricted_until > 현재이면 403, 판매는 sale_restricted_until. | 구매는 purchase_restricted_until, 판매는 sale_restricted_until 기준 제한 중이면 403. |
| layered | `domain/SellerGrade.kt:66` | 판매 제한 중(저장된 sale_restricted_until > 현재)이면 | 판매 제한 중(저장된 sale_restricted_until 기준)이면 |
| layered | `domain/BatchTargets.kt:58`·`:61` | purchase_restricted_until > now, / sale_restricted_until > now, | … 기준 제한 중, |
| layered | `domain/Notification.kt:81`·`:84` | purchase_restricted_until > now → / sale_… | … 기준 제한 중 → |
| layered | `service/AdminQueryService.kt:37` | 그 제한 끝 > 현재. | 그 제한 중인 사용자. |

- 비대칭(처치로 표시): god 2줄, layered 7줄 — layered가 대상 열거형·enum·서비스마다 KDoc을 따로 둔 구조 차이. 정답·alt·오답 패치는 이 주석 줄을 건드리지 않고 inject 뒤 사본에 그대로 적용된다.

## 대안 정답 (alt-1 — 공통 판정 함수 + `NOT (… < :now)`)

- god: 파일 끝(`:3668-3670` 삽입)에 `private fun Instant?.restrictsAt(now: Instant): Boolean = this != null && !this.isBefore(now)`, Kotlin 4곳(`:202`·`:205`·`:2110`·`:2704`)은 `….restrictsAt(now)`, SQL·JPQL 4곳(`:2480`·`:2484`·`:2787`·`:3246`)은 `NOT (<열> < :now)`(NULL이면 NULL → 거짓, `>=`와 같은 결과).
- layered: `User`에 `isPurchaseRestricted(now)`·`isSaleRestricted(now)`(`User.kt:47-51` 삽입), `requireCanBid`(`:40`·`:43`)·`SellerGrade.kt:70`·`Wishlist.kt:135`가 그것을 부른다. SQL·JPQL 4곳은 god와 같은 `NOT (… < :now)`.
- 숨은 테스트는 결과만 보므로 `!isBefore`·`compareTo >= 0`·서비스 층 거르기 등도 통과한다.

## 순진한 오답 (naive-N — 모두 fix와 같은 정답 줄 문자열을 쓰고 일부만 고침)

| N | 오답 | 바꾼 줄 god / layered | 숨은 테스트에서 실패해야 하는 곳 |
|---|---|---|---|
| 1 | 새 입찰 403만(공개 테스트만 맞춤) | `:202`·`:205` / `User.kt:40`·`:43` | G1·N1·N2·P1·P2·Q1·Q2 (7) |
| 2 | 구매 쪽만(입찰 BUY·구매 제한 알림·발동·재무장 — 공용 관리자 목록 줄은 그대로) | `:202`·`:2480`·`:2704`·`:2787` / `User.kt:40`·`ReminderSql.kt:59`·`Wishlist.kt:135`·`JpaNotificationRepositories.kt:138` | B2·G1·N2·Q1·Q2 (5) |
| 3 | 코드 비교만, SQL·JPQL 누락 | `:202`·`:205`·`:2110`·`:2704` / `User.kt:40`·`:43`·`SellerGrade.kt:70`·`Wishlist.kt:135` | N1·N2·P2·Q1·Q2 (5) |
| 4 | 정답 + 구매·판매 재계수 기준까지 `≥`(`it.isAfter(lastRestrictedAt)` → `!it.isBefore(lastRestrictedAt)`) | fix 8줄 + `:1027`·`:1477` / fix 8줄 + `User.kt:61`·`:72` | R2·R3 (2) — 제한을 건 경고·패널티 3건(created_at = 마지막 제한 시각)이 다시 세져 뒤 2건만으로 제한이 다시 걸린다 |
| 5 | 정답 + **판매** 재계수 기준만 `≥`(구매 쪽 그대로) — 리뷰 DT-2로 추가 | fix 8줄 + `:1027` / fix 8줄 + `User.kt:72` | R3 (1) |

- naive-4는 구매·판매 재계수를 모두 바꾼다(패치 실파일 확인: god `:1027`·`:1477`, layered `User.kt:61`·`:72` 네 hunk). 처음 판은 구매 쪽 R2만 봐서 판매 쪽만 바꾼 오답이 통과했다(리뷰 DT-2) → R3 추가, naive-5(naive-4에서 구매 hunk만 뺀 것)로 R3가 판매 쪽을 따로 잡는 것을 확인했다.
- R1(다시 센다)은 naive-4에서도 통과한다: 새 경고 3건이 한 배치(같은 시각 t2)에서 생겨 최종 끝 시각이 t2 + 7일로 같다.

## regression-exclude · verify-exclude

- **무제외 실측으로 정했다**: regression-exclude 없이 fix를 contract·contract2·contract3로 돌려(2026-11-03 10:46 ~ 11:16) 실패한 메서드가 두 구조 모두 정확히 아래 8개였다(contract 122-3, contract2 89-0, contract3 252-5). verify 위반 0(세 단계·두 구조) → **verify-exclude 없음**(verify.sql의 제한 관련 검사 `a2_seller_grade_restricted`·`a4_reminder_complete`는 시드 빌드 전용 `seed_only`).
- 제외 8메서드(파일 참고, 메서드 단위 패턴 + 사유) → 이전한 숨은 테스트:

| 제외 메서드 | 깨지는 이유 | 이전 |
|---|---|---|
| contract `RestrictionAndConcurrencyTest` "제한 경계 …" | 끝 = 현재 구매 입찰 201 단언 | B1 |
| contract `BatchAndClockTest` "구매 경고 - 제한을 건 경고는 …" | 준비가 끝 = 현재에 구매 입찰(403) | R1(끝 + 1초에 준비) |
| contract `BatchAndClockTest` "구매 경고 - 제한 뒤 경고 2건 …" | 같음 | R2(끝 + 1초에 준비) |
| contract3 `A2SellerGradeTest` "§5_1 판매 제한 …" | 끝 = 현재 → 제한 아님(MASTER) 단언 | G1 |
| contract3 `A4DeadlineReminderTest` "§7_1 제한 알림 …" | 끝 = now 대상 아님 단언 | N1(구매 + 재실행 무중복 + 연장 새 키)·N2(판매) |
| contract3 `A5PriceAlertScanTest` "§8_3의 2 …" | 끝 = 현재 → TRIGGERED 단언 | P1 |
| contract3 `A14PriceAlertRearmTest` "§20_3_3 구매 제한 중 …" | 끝 = 현재 → 재무장 단언 | P2 |
| contract3 `A9AdminQueryTest` "§11_3 users-restricted …" | 끝 = 현재 제외 단언 | Q1(purchase)·Q2(sale + 항목 모양·페이지·400) |

- 제외 패턴이 contract·contract3 패키지라 그 두 단계의 실행 수 기준 검사는 꺼지고 contract2(89)는 유지된다(judge `excl_touches`). 같은 이유로 실측 기대 실행 수: contract 119, contract2 89, contract3 247.
- G1 이전(리뷰 DT-1로 고침): 처음 판은 기준 금액 0(빈 정산 행)이라 등급이 늘 STANDARD여서 '제한이 풀리면 MASTER'를 보지 못했다. 지금은 정산 행(PAID) + INCLUDED 항목 8,000,000을 SQL로 넣어(period_start = 현재 달 1일(KST) − 7일, `order_item_id`는 FK만 맞추는 기존 항목 — 등급 산정은 항목 amount·status만 읽는다) 원본 A2 "§5_1 판매 제한"의 단언을 새 규칙으로 옮긴다: 끝 = 현재 + 1초·= 현재 → (restricted, STANDARD, basis 8,000,000), 끝 = 현재 − 1초 → (not restricted, MASTER, 8,000,000). 숨은 단계는 verify를 돌리지 않는다(verify는 회귀 단계만 — `judge.run_stage_once`). 원본 A2의 다른 판정(등급 문턱·기간)은 정답이 바꾸지 않는 줄이며 회귀의 나머지 A2 메서드가 그대로 본다.
- 정적 grep으로 찾은 그 밖의 제한 사용 테스트(PenaltyTest — 판매 입찰을 제한 전에 걸어 둠, A3·A12·A14Renew·A2Rebate·A4Notification·A5PriceAlert·BundleDSupportB `restrictionNotifs` — 끝이 현재 + 며칠, `BundleDSupportA.rearmTargetCount`(테스트 쪽 `>` 예측식 — 같은 시각 끝 = 현재인 대상이 없어 영향 없음))는 무제외 실측에서 실패하지 않았다.

## 숨은 테스트 (`hidden/D1HiddenTest.kt` + `hidden/D1Fixtures.kt`, 12개)

사용자·빈 상품 사이즈·제한 끝 시각·(등급용) 정산 행·INCLUDED 항목만 SQL로 넣고, 입찰·체결·결제·위시리스트·가격 알림·배치는 API로 만든다. 경계는 세 점: 끝 = 현재 + 1초(제한 — 그대로), 끝 = 현재(**새 규칙: 제한**), 끝 = 현재 − 1초(제한 아님). 멈춘 시계라 `Clock.now()`를 그대로 끝 시각으로 넣는다. 시계는 앞으로만 상대 이동, 테스트마다 새 사용자·사이즈, 실행 순서는 이름순(순서에 기대는 단언 없음).

| 테스트 | 시나리오 · 단언 | 지나는 고칠 곳 god / layered | 이전 |
|---|---|---|---|
| B1 구매 입찰 | 구매 끝 = 현재 → BUY·즉시 BUY 403 PURCHASE_RESTRICTED, 판매(끝 −1일) 201 · 끝 −1초 BUY 201 · 끝 +1초 BUY 403 | `:202` / `User.kt:40` | 제한 경계 |
| B2 판매 입찰 | 판매 끝 = 현재 → SELL·즉시 SELL 403 SALE_RESTRICTED, BUY 201 · 끝 −1초 SELL 201 · 끝 +1초 403 | `:205` / `User.kt:43` | |
| G1 판매자 등급 | 기준 금액 8,000,000(MASTER 문턱): 끝 +1초·= 현재 → (true, STANDARD, 8,000,000), 끝 −1초 → (false, **MASTER**, 8,000,000) | `:2110` / `SellerGrade.kt:70` | A2 §5_1 |
| N1 구매 제한 알림 | 끝 = 현재·+1초 → 키 `PURCHASE_RESTRICTED:<id>:<E>` 1행, −1초 → 없음, 재실행 무중복, 연장 → 새 키 1행·앞 행 그대로, 제한 읽기만 + (경계와 독립, DT-5) 끝 = 현재 + 1일 + 700ms(구매)·+ 2일 + 300ms(판매) 사용자 → 키의 E = epoch 초 내림, 알림 필드(user_id·kind·ref_id = 그 사용자·created_at = 배치의 현재·read_at NULL), 끝 = 현재 행도 같은 필드 | `:2480` / `ReminderSql.kt:59` | A4 §7_1 |
| N2 판매 제한 알림 | 같은 경계, `SALE_RESTRICTED` + 끝 = 현재 행 필드 | `:2484` / `ReminderSql.kt:63` | A4 §7_1 |
| P1 가격 알림 발동 | 끝 = 현재 +1초 → ACTIVE, 1초 뒤(끝 = 현재) ACTIVE, 1초 뒤 TRIGGERED, 제한 읽기만 | `:2704` / `Wishlist.kt:135` | A5 §8_3의 2 |
| P2 재무장 | 발동 → 판매자 취소 → 1시간 뒤 끝 = 현재 +1초: 새 알림 없음, = 현재: 없음, −1초: 새 ACTIVE 1행 | `:2787` / `JpaNotificationRepositories.kt:138` | A14 §20_3_3 |
| Q1 관리자 목록 purchase | `after` 이후 사용자 중 끝 +1일·= 현재만(−1초·판매만 제한 제외) | `:3246` / `JpaAdminQueryRepository.kt:26` | A9 §11_3 |
| Q2 관리자 목록 sale | 끝 +1초·= 현재만 + 항목 모양(userId·email·restrictedUntil)·limit=1 페이지·nextAfter·type 없음/알 수 없음 400 INVALID_REQUEST | 같음 | A9 §11_3 |
| R1 재계수 다시 센다 | 경고 3건 → 끝 = t1 + 7일, 끝 + 1초에 미결제 3건 → 한 배치에서 경고 6건, 끝 = t2 + 7일 | (바꾸면 안 되는 `:1477` / `User.kt:61` 경로) | BatchAndClock |
| R2 제한 뒤 경고 2건 | 경고 3건 → 끝, 끝 + 1초에 미결제 2건 → 경고 5건, 끝 그대로 | 같음 — naive-4를 잡는다 | BatchAndClock |
| R3 판매 패널티 재계수 (DT-2) | 판매자가 판매 입찰 6개를 미리 걸어 둠(제한 뒤에도 그 입찰의 체결·결제는 됨 — 끝 = 현재 경계에서 새 판매 입찰을 하지 않는다) → 3건 결제 → 결제 + 3일 `shipment-deadline` → 패널티 3건(created_at = t1), 끝 = t1 + 30일 → 2건 더(t1 + 3일) → 패널티 5건, 끝 그대로 → 1건 더 → 끝 = t3 + 30일 | (바꾸면 안 되는 `:1027` / `User.kt:72` 경로) — naive-4·naive-5를 잡는다 | contract2 PenaltyTest '다시 센다'와 같은 모양(제외 대상 아님 — 숨은 단계에서도 보게 함) |

결정성: 단언은 테스트가 만든 사용자·사이즈·알림만 본다(배치는 시드 행도 처리하지만 단언 대상과 겹치지 않음 — 관리자 목록은 `after = 테스트 시작 시 max(id)`). 시각 단언은 테스트가 정한 시각과의 비교뿐이다.

## 확인 결과 (`runner.py check`, 1레인 `flock /tmp/ccle-judge.lock`, 결과 `experiment/results-del-tasks/checks.jsonl`)

2026-11-03 11:17 ~ 15:45, 기준 HEAD `81d7983` → 중간에 `66cbb30`(메인의 D2 자산 커밋 — 앱·behavior-tests·러너·스키마 변경 없음 `git diff --stat 81d7983 66cbb30 -- god layered behavior-tests tools experiment/runner docker` 비어 있음), `code_dirty` True(D1 미커밋). 아래는 최종 숨은 테스트(11개)·최종 regression-exclude로 돈 행.

| 구조 | 패치 | 단계 | success | 결과 |
|---|---|---|---|---|
| god | fix | 전 단계 | True | public 1/1; hidden 11/11; contract 119/119·contract2 89/89·contract3 247/247, verify 위반 0(세 단계) |
| layered | fix | 전 단계 | True | 〃 |
| god | alt-1 | 전 단계 | True | 〃 |
| layered | alt-1 | 전 단계 | True | 〃 |
| god | none | public,hidden | False | public 0/1; hidden 2/11 (실패 9: B1·B2·G1·N1·N2·P1·P2·Q1·Q2) |
| layered | none | public,hidden | False | 〃 |
| god | naive-1 | public,hidden | False | public 1/1; hidden 4/11 (실패 7: G1·N1·N2·P1·P2·Q1·Q2) |
| layered | naive-1 | public,hidden | False | 〃 |
| god | naive-2 | public,hidden | False | public 1/1; hidden 6/11 (실패 5: B2·G1·N2·Q1·Q2) |
| layered | naive-2 | public,hidden | False | 〃 |
| god | naive-3 | public,hidden | False | public 1/1; hidden 6/11 (실패 5: N1·N2·P2·Q1·Q2) |
| layered | naive-3 | public,hidden | False | 〃 |
| god | naive-4 | public,hidden | False | public 1/1; hidden 10/11 (실패 1: R2) |
| layered | naive-4 | public,hidden | False | 〃 |

숨은 실패 수(두 구조 같음 — §4.3-2): none 9 · naive-1 7 · naive-2 5 · naive-3 5 · naive-4 1. 실패 이름도 두 구조 같다. tampered 0(모든 행).

- 무제외 회귀(regression-exclude 없음, fix, contract·contract2·contract3): god·layered 모두 contract 122-3·contract2 89-0·contract3 252-5, verify 위반 0 — 실패 8메서드가 위 제외 목록과 같다(10:46 ~ 11:16).
- 숨은 테스트를 N1(연장 새 키)·Q2(모양·페이지·400)로 보강하기 전 판(9 + 2 단언 적음)으로도 fix·none·naive-1~4 public/hidden을 돌렸고 실패 이름·수가 위와 같았다(08:49 ~ 10:46 — 보강 뒤 행이 최종).
- 판정 2회 재현·숨은 테스트 5회 결정성은 메인 몫(커밋 뒤 고정 SHA).

### 리뷰 반영 뒤 재확인 (DEL-F1 — DT-1·DT-2·DT-5, 숨은 테스트 12개)

2026-11-03 17:42 ~ 18:34, `code_sha` `01edabf`, `code_dirty` True(DEL-F1 수정 미커밋), `flock /tmp/ccle-judge.lock` 1레인 순차(가용 메모리 12GB 이상에서만 시작), `--stages hidden`(숨은 테스트만 바뀌었으므로 공개·회귀는 위 결과 그대로). 결과 행 = 같은 `checks.jsonl`.

| 패치 | god | layered | 숨은 실패(12개 중, 두 구조 같음) |
|---|---|---|---|
| fix | True | True | 0 |
| alt-1 | True | True | 0 |
| none | False | False | B1·B2·G1·N1·N2·P1·P2·Q1·Q2 (9) |
| naive-1 | False | False | G1·N1·N2·P1·P2·Q1·Q2 (7) |
| naive-2 | False | False | B2·G1·N2·Q1·Q2 (5) |
| naive-3 | False | False | N1·N2·P2·Q1·Q2 (5) |
| naive-4 | False | False | **R2·R3** (2) |
| naive-5 | False | False | **R3** (1) |

- tampered 0(모든 행). 실패 메시지 예: none G1 "끝 = 현재: 제한(basis는 그대로) expected <(true, STANDARD, 8000000)> but was <(false, MASTER, 8000000)>", naive-5 R3 "마지막 제한 뒤 패널티 2건 → 그대로 expected <t1 + 30일> but was <t1 + 33일>"(두 구조 같음).
- 새 N1 소수 초·필드 단언은 경계와 독립이다: N1이 통과한 fix·alt-1·naive-2·4·5에서 모두 통과했고, N1이 실패한 none·naive-1·3은 그보다 앞의 경계 단언 '끝 = 현재: 대상'에서 멈췄다(새 단언 때문에 실패 수가 바뀐 패치 없음).
