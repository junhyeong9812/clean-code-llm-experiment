# C2 — 판매자 패널티 조회 API (change)

## 의도

새 읽기 API `GET /me/penalties`를 각 구조의 관례대로 추가하는 과제다. 주입은 없다(기준 상태에는 API가 없어 라우트 없음 404 `NO_ROUTE`). ADR-0005 §3.2가 정한 대로 **구조 차이가 의도대로 드러나는 과제**다 — god는 3파일, layered는 5파일(새 파일 1개 포함)을 고친다. 결과 표에서는 편향이 아니라 표시 대상이다.

계약(TASK.md 전문): 인증 401 `UNKNOWN_USER`(없음·숫자 아님·없는 사용자), 200 배열 `[{penaltyId, orderItemId, amount, createdAt, settled}]`, 본인(판매자) 패널티만, `createdAt` 내림차순 + 같으면 `penaltyId` 내림차순, 빈 목록 `[]`. `settled` = 주간 정산에 반영됨(`seller_penalties.settlement_id IS NOT NULL`). 시각 형식·오류 본문은 ADR-0003 §2 그대로 — 두 구조 모두 Instant 응답 필드를 KST ISO-8601로 쓰는 Jackson 설정(god `web/Json.kt:29-40`, layered `api/Json.kt`)과 `ApiException`/`MarketException` → `{"code","message"}` 변환을 이미 갖고 있어 새 코드는 Instant 필드를 두고 기존 `requireUser`를 부르기만 하면 된다.

## 구조별 고칠 곳(정답 — 실제 고친 파일 수)

줄 번호는 기준 커밋 3304c68 사본(inject 없음) 기준.

### god — 3파일 (`fix.god.patch`)

| 파일 | 위치 | 변경 |
|---|---|---|
| `web/OrderController.kt` | `:49-53`(`/me/settlements`) 뒤 | `@GetMapping("/me/penalties")` — `marketplace.requireUser` → `marketplace.listPenalties` |
| `web/Dtos.kt` | `:110` 뒤(파일 끝) | `data class PenaltyView(penaltyId, orderItemId, amount, createdAt: Instant, settled: Boolean)` |
| `Marketplace.kt` | `:33` import, `:1737`(`listSettlements` 끝) 뒤 | `listPenalties(userId)` — JPQL `WHERE p.sellerId = :seller ORDER BY p.createdAt DESC, p.id DESC` → `PenaltyView` (`settled = settlementId != null`) |

관례: 조회는 `listTrades`(`Marketplace.kt:297`)처럼 트랜잭션 없이 쿼리 1개 + 뷰 매핑(`listSettlements`의 스냅샷 트랜잭션은 여러 테이블을 한 스냅샷에서 읽기 위한 것이라 단일 쿼리에는 불필요).

### layered — 5파일 (`fix.layered.patch`, 새 파일 1개)

| 파일 | 위치 | 변경 |
|---|---|---|
| `domain/Dispute.kt` | `:94-102` 포트 `SellerPenaltyRepository` | `fun listOf(sellerId: Long): List<SellerPenalty>` (KDoc에 정렬) |
| `infra/JpaDisputeRepositories.kt` | `:22-37` 어댑터 `JpaSellerPenaltyRepository` | 같은 JPQL 구현 |
| `service/PenaltyQueryService.kt` | **새 파일** | `@Service PenaltyQueryService(penalties).list(sellerId)` — `TradeQueryService`(`service/QueryServices.kt`)처럼 위임만 |
| `api/Dtos.kt` | `:2-16` import, `:210` 뒤 | `PenaltyView` + `constructor(penalty: SellerPenalty)`(다른 DTO와 같은 보조 생성자 관례) |
| `api/OrderController.kt` | `:19-25` 생성자 주입, `:59-63` 뒤 | `@GetMapping("/me/penalties")` — `users.requireUser(userIdOf(...))` → `penaltyQuery.list` → `PenaltyView` |

관례: 포트(도메인 인터페이스) → infra JPA 어댑터 → 서비스 → api DTO 생성자. 저장소 메서드 이름은 기존 `SettlementRepository.listOf(sellerId)`·`TradeRepository.listOf`를 따랐다. 레이어 경계상 서비스가 `SellerPenaltyRepository`(도메인 포트)만 보고, api는 도메인 엔티티를 DTO 생성자로 읽는다(`SettlementItemView(item: SettlementItem)`와 같다).

## 대안 정답이 통과해야 하는 이유

숨은 테스트는 HTTP 응답과 DB 행만 비교한다(쿼리 모양·클래스 이름·파일 위치를 보지 않는다).
- 정렬을 DB가 아니라 Kotlin에서 해도, 네이티브 SQL을 써도, 서비스에 읽기 전용·스냅샷 트랜잭션을 둬도, 새 컨트롤러 클래스를 만들어도, 기존 `SettlementQueryService`·`QueryServices.kt`에 메서드를 더해도 결과가 같으면 통과한다.
- 응답에 필드를 더 넣어도(예: `settlementId`) 통과한다 — 테스트는 필수 5필드의 존재·타입·값만 본다.
- `createdAt`은 오프셋 `+09:00`을 단언한다. TASK.md가 "다른 API와 같은 형식(KST 오프셋)"이라고 명시했고, 두 구조 모두 Instant 필드를 쓰면 기존 설정이 자동으로 KST로 쓴다.

## 숨은 테스트 (`hidden/C2HiddenTest.kt` + `hidden/C2Fixtures.kt`)

패널티는 자연 흐름으로만 만든다: 판매 입찰(미리) → 새 구매자 체결 → 결제(가짜 PG 승인) → 발송 → 입고 → 검수 FAIL(판매자 귀책 §6). 사용자·상품 사이즈만 SQL로 넣는다. 시계는 상대 이동만, 주간 정산은 한 테스트만 돌린다.

| 테스트 | 단언 |
|---|---|
| 401 | 헤더 없음·`abc`·없는 id → 401 `UNKNOWN_USER` + message 문자열 |
| 빈 목록 | 새 사용자 → 200 `[]` |
| 목록·정렬 | 판매자 1명 패널티 3건(t0에 a, t0+1h에 b·c 같은 시각) → 순서 `[c, b, a]`, 금액 `[1,234, 5,000, 3,333]`, createdAt `[t1, t1, t0]`, 전부 false, penaltyId = DB 행, DB 기대값과 동일. 그 구매자는 `[]` |
| 분리 | 판매자 x·y 각 1건 → 각자 자기 것 1건만(시드·남의 것 섞이지 않음) |
| 시드 | 시드에서 패널티가 가장 많은 판매자(시드 5번, 9건 전부 정산 반영) 목록 = DB 기대값. 반영·미반영이 섞인 시드 판매자(정산 전이면 310·880) 목록 = DB 기대값 |
| 정산 반영 | 시드 혼합 판매자(310) 정산 전 false 포함 확인 → 새 판매자: 마감 전 패널티 1건, 다음 월요일 00:00 KST + 1h로 이동 뒤 1건 → 둘 다 false → `weekly-settlement` → `[마감 뒤 false, 마감 전 true]`, id·시각 불변, DB와 같음. 시드 혼합 판매자는 전부 true로 바뀜 |

결정성: 기대값을 DB에서 계산하는 단언(`Pen.expected`)은 정렬 규칙으로만 정해지고, 시드 1k 템플릿(`ccle-postgres-seed:1k-s42`)의 패널티 175건(정산 반영 173, 미반영 2 — 310·880, 생성 2026-09-29)에 같은 created_at이 없어 시드 쪽 순서가 유일하다. 테스트 실행 순서가 바뀌어도 각 단언은 그 시점의 DB와 비교하거나 자기가 만든 행만 본다.

경로가 두 구조에서 실제로 호출되는지: 새 엔드포인트 하나뿐이고 정답 패치 두 벌이 숨은 테스트를 통과하는 것으로 확인(아래 실측).

## 순진한 오답 (naive-N — 숨은 테스트가 실패해야 함)

| N | 오답 | 깨지는 숨은 테스트 |
|---|---|---|
| 1 | 판매자 조건 없이 모든 패널티 | 목록·분리·시드·정산 반영 |
| 2 | `settled` 항상 false | 시드(5번 판매자 전부 true)·정산 반영 |
| 3 | 정렬 반대(`createdAt`·id 오름차순) | 목록·시드·정산 반영 |
| 4 | 헤더 없음·숫자 아님이면 401 대신 `[]`(없는 사용자도 `[]`) | 401 |

naive 패치는 정답 패치와 같은 파일에 결함 하나만 바꾼 완전한 구현이다(기준 사본 위에 적용).

## 제외한 유사 지점과 사유

- `GET /me/settlements`의 `penaltyTotal`: 정산 단위 합계라 그대로 둔다(계약 변경 없음).
- 패널티 생성·정산 붙이기(god `:954-967`·`:1659-1701`, layered `ItemRefunder.kt:81`·`Settlement.kt:106`): 읽기만 — 바꾸지 않는다.
- 판매 제한 계산(T2 대상)과 겹치지 않는다: 이 과제는 패널티 행을 읽기만 한다.

## 주석

주입·규칙 변경이 없어 지운 주석은 0개(두 구조). 기존 주석 중 이 API를 서술하는 것은 없다(새 기능). 정답이 더한 KDoc은 정답 패치 안에만 있다.

## regression-exclude · verify-exclude

둘 다 빈 파일. 새 엔드포인트 추가라 기존 계약 테스트·verify가 바뀌지 않는다 — fix 상태 전 단계 실측으로 확인(아래).

## 두 구조 비대칭 메모

- **고칠 파일 수:** god 3 / layered 5(새 파일 1). 추가 줄 수: god 패치 +24줄, layered +31줄 내외(naive도 같은 크기).
- **찾기:** god는 `/me/settlements`가 있는 `OrderController`와 `Marketplace.listSettlements` 옆이 자연스러운 자리이고 패널티 엔티티·JPQL 예가 `Marketplace.kt` 한 파일(`:954-967`, `:1659-1662`)에 있다. layered는 `SellerPenalty`가 `domain/Dispute.kt`(이름으로 찾기 어려움 — 파일 이름이 `Dispute`)에 있고 포트·어댑터·서비스·DTO·컨트롤러를 각각 고쳐야 한다.
- **유리한 구조 판단:** 편집 지점 수·파일 수는 god가 적고(3 대 5), layered는 새 파일·생성자 주입이 필요해 수정 턴이 더 든다. 반면 god는 1,772줄 `Marketplace.kt`를 다뤄야 해 읽기 비용이 크다(§5.2 기전 지표로 볼 것). 사전 판단: **수정 비용은 god 유리, 읽기 비용은 layered 유리** — 토큰 합은 사전에 정하지 않는다(ADR 표시 과제).

## 채택 조건 실측 (2026-10-26, 기준 3304c68, `runner.py check` 1회씩)

| 구조 | 패치 | 단계 | success | 결과 |
|---|---|---|---|---|
| god | fix | 전 단계 | True | public 2/2, hidden 6/6, contract 122, contract2 89 통과, verify 위반 0 |
| layered | fix | 전 단계 | True | 같음 |
| god·layered | none | public,hidden | False | public 2개·hidden 6개 전부 실패(404 NO_ROUTE) |
| god·layered | naive-1 | hidden | False | 목록·빈 목록·분리·시드·정산 반영 실패 |
| god·layered | naive-2 | hidden | False | 시드·정산 반영 실패 |
| god·layered | naive-3 | hidden | False | 목록·시드·정산 반영 실패 |
| god·layered | naive-4 | hidden | False | 401 실패 |

fix의 5회 반복은 메인이 따로 돌린다.
