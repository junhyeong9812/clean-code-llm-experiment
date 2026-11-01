# S4 설계 — 관리자 분쟁 목록 기본 20건(change, 영향 범위)

> ADR-0008 §4.2 S4 행이 정본. 작성 기준: 브랜치 `search-cost-tasks`, HEAD `e65ca93`(앱 소스는 `8449d1e` 이후 변경 없음 — 줄 번호는 이 HEAD의 `god/`·`layered/` 기준). 주입 없음(TYPE=change) — 기준 소스 좌표 = 판정 좌표.

## 1. 의도

- 새 규칙: `GET /admin/disputes`만 limit 생략 시 **20건**. limit을 주면 지금처럼 1~100(정수 아님·범위 밖 400 `INVALID_REQUEST`). 다른 관리자 목록 6개의 기본 50 포함 그 밖은 그대로.
- 유형: 영향 범위. 기본값은 관리자 목록 7개가 함께 쓰는 공유 함수에 한 번 있다. 공유 기본값을 바꾸면(가장 찾기 쉬운 자리) 나머지 6개 목록이 같이 바뀐다 — 고칠 곳은 **분쟁 호출 지점**이다.
- 찾기 비용 조건(ADR-0008 §4.1)의 **예외**(사용자 결정 2026-11-01 — 찾은 뒤의 비용을 본다). §3 표는 그대로 적는다.

## 2. 공유 기본값과 호출처 7곳(실코드 확정)

| 구조 | 공유 기본값 | 호출처(7곳) | 고칠 곳 |
|---|---|---|---|
| god | `Marketplace.kt:3214-3220` `page(limit, after)`, 기본 `:3215` `if (limit == null) 50`, 최대 `:3216` | `:3104` adminInquiries · `:3244` restrictedUsers · `:3274` userPenalties · `:3286` userWarnings · `:3304` adminSettlements · **`:3324` adminDisputes** · `:3343` auditLogs | `:3324` |
| layered | `domain/AdminQuery.kt:14` `DEFAULT_LIMIT = 50`, 사용 `:17`, 최대 `:18`, 시그니처 `:16` `KeysetPage.of(limit, after)` | `service/AdminQueryService.kt:40` restrictedUsers · `:54` penalties · `:61` warnings · `:69` settlements · **`:75` disputes** · `:81` auditLogs · `service/InquiryService.kt:136` adminList | `AdminQueryService.kt:75` |

- 7곳이 두 구조 같은 목록이다(관리자 문의 목록이 god는 Marketplace, layered는 InquiryService에 있다는 차이만).
- 경로: god `web/AdminQueryController.kt:49-54` → `Marketplace.adminDisputes`(:3322). layered `api/AdminQueryController.kt:116-121` → `AdminQueryService.disputes`(:73) → `KeysetPage.of`.

## 3. 찾기 단서 자체 측정(ADR-0008 §4.1 — S4는 예외, 표만)

측정: 기준 `src/main`에 `grep -rn -F -- "<낱말>"`(대소문자 구분). ±40 창: god `Marketplace.kt` fix∪alt {3214, 3324} → 3174-3364, layered `AdminQueryService.kt` {75} → 35-115(S:) + `AdminQuery.kt` alt {16, 17} → 1-57(Q:). 직접 = 결과 ≤ 10줄이고 ±40 적중이 있음. 낱말 출처는 TASK.md·공개 테스트(작성자 추출 — 최종 판정은 리뷰어).

| 낱말 | god 줄 수 | god ±40 적중 | god 직접 | layered 줄 수 | layered ±40 적중 | layered 직접 |
|---|---|---|---|---|---|---|
| `disputes` | 6 | 3321 | **Y** | 19 | S:73·76 | - |
| `admin/disputes` | 1 | 3321 | **Y** | 0 | — | - |
| `/disputes` | 4 | 3321 | **Y** | 3 | — | - |
| `분쟁` | 19 | 3323 | - | 35 | — | - |
| `분쟁 목록` | 0 | — | - | 1 | — | - |
| `Dispute` / `dispute` | 28 / 24 | 3322… / 3321·3332 | - | 63 / 39 | S:73 / S:73·74·76 | - |
| `disputeId` | 10 | 3332 | **Y** | 8 | — | - |
| `REJECTED`(공개 테스트) | 6 | 3321·3323 | **Y** | 8 | — | - |
| `limit` | 51 | 다수 | - | 58 | 다수 | - |
| `기본` | 2 | 3211 | **Y** | 2 | Q:8 | **Y** |
| `50` / `20` | 14 / 44 | 3211·3215 / 3200·3206 | - | 14 / 76 | Q:8·14 / — | - |
| `페이지` | 14 | 다수 | - | 13 | 다수 | - |
| `nextAfter` | 2 | — | - | 6 | Q:10·27·28 | **Y** |
| `INVALID_REQUEST` | 5 | — | - | 6 | — | - |
| `1~100` / `1..100` | 6 / 2 | 3211·3216 / 3216 | **Y / Y** | 6 / 2 | Q:8·18 / Q:18 | **Y / Y** |
| `관리자` | 13 | 3209 | - | 26 | Q:6·47 | - |
| `after` / `status` / `items` | 55 / 312 / 69 | 다수 | - | 59 / 347 / 106 | 다수 | - |
| `default` / `DEFAULT`·`DEFAULT_LIMIT` | 0 / 0 | — | - | 0 / 2 | — / Q:14·17 | - / **Y** |
| `page(` / `KeysetPage` | 8 / 0 | 3214…3343 | **Y** / - | 0 / 28 | — / S:40…81·Q:9… | - / - |
| `order_items` | 19 | — | - | 23 | — | - |

- 예외 과제답게 직접 단서가 많다(god 9 · layered 6). **fix 줄(god :3324 / layered :75)만의 ±40**으로 좁히면 god 직접 = `disputes`·`admin/disputes`·`/disputes`·`disputeId`·`REJECTED`·`page(`, layered 직접 = 0 — layered의 직접 단서는 모두 공유 `AdminQuery.kt`(alt·오답 자리 근처)로 간다.
- **처치 표시(ADR-0008 §4.2):** god는 고칠 줄 3줄 위 KDoc `:3321`에 경로 문자열 `/admin/disputes`가 있고, layered는 컨트롤러(`AdminQueryController.kt:116`)에서 한 단계 간다 — 구조 차이로 표시, 민감도 요약에서 뺀다. 주석은 건드리지 않는다(§6).

## 4. 정답

- `fix.{god,layered}.patch`: 분쟁 호출 지점에서 limit 생략 시 `"20"`을 넘긴다. god `:3324` `page(limit ?: "20", after)`, layered `:75` `KeysetPage.of(limit ?: "20", after)`. 공유 함수·다른 호출처 무변경. limit을 주면 그대로 같은 검사(1~100·정수)를 탄다.
- `alt-1.{god,layered}.patch`(ADR §4.2): 공유 함수에 기본값 인자를 더하고 분쟁 호출에서만 20.
  - layered(자연스러운 형태): `AdminQuery.kt:16` `of(limit, after, defaultLimit: Int = DEFAULT_LIMIT)` · `:17` `if (limit == null) defaultLimit` · `AdminQueryService.kt:75` `KeysetPage.of(limit, after, defaultLimit = 20)`. `DEFAULT_LIMIT`(:14)은 그대로.
  - god: 자연스러운 형태(`:3214` 시그니처 + `:3215` `if (limit == null) defaultLimit`)는 **`:3215`를 바꾼다** — 오답 naive-1·2의 바뀐 줄과 겹친다(§7 표 alt-natural 행). god는 기본값 리터럴이 사용 줄 안에 있어(layered는 상수가 따로) 피할 수 없는 구조 차이다. 그래서 등록한 god alt-1은 **시그니처 줄만 바꾸고 바로 아래에 한 줄을 넣는 형태**다: `:3214` `page(limit, after, defaultLimit: Int = 50)` + 삽입 `if (limit == null) return page(defaultLimit.toString(), after)` + `:3324` `page(limit, after, defaultLimit = 20)`. 변경 덩어리가 `-`줄(3214)을 가지므로 판정 범위는 {3214}만(삽입 줄은 같은 덩어리). 원래 `:3215`의 `50` 분기는 남지만 닿지 않는다.
  - 실제 에이전트가 god 자연 형태로 고치면 같은 편집에서 `:3214` 시그니처도 바뀌므로 등록 범위({3214, 3324})에 적중한다(시그니처보다 `:3215`를 먼저 따로 고친 편집만 비적중 — 그 편집은 오답 편집과 구별할 수 없다).
- 미등록 후보(기록): 컨트롤러 `@RequestParam(required = false, defaultValue = "20") limit`(god `AdminQueryController.kt:52`, layered `:119`)도 limit 생략 시 20이 된다. 다만 Spring `defaultValue`는 **빈 값(`limit=`)에도 적용**돼 지금의 400(정수 아님)을 200으로 바꾼다 — 규칙("정수가 아니면 지금처럼 400")과 다르다. 숨은 테스트는 빈 값을 보지 않으므로 이 형태는 success가 되고 `fce_fallback`으로 잡힌다. 등록 여부는 메인 판단.

## 5. 순진한 오답(naive-N — 바뀐 줄이 판정 범위 밖, 숨은 테스트에서 실패)

| N | 패치(두 구조 같은 의미) | 바뀐 줄 god / layered | 기대 숨은 실패 |
|---|---|---|---|
| 1 | 공유 기본값 50 → 20(ADR §4.2) | `:3215` / `AdminQuery.kt:14` | C(감사 로그 기본 50) |
| 2 | 공유 기본값 50 → 20 + 최대 100 → 20 | `:3215·3216` / `AdminQuery.kt:14·18` | B(limit=50 → 400) · C · D(limit=100 → 400) |
| 3 | 분쟁 목록에 20을 넣되 limit을 줘도 20으로 덮어씀(컨트롤러에서 `"20"` 전달) | `web/AdminQueryController.kt:54` / `api/AdminQueryController.kt:121` | B(limit=50 → 20건) · D(limit=0 → 200) |

- naive-3을 컨트롤러에 둔 이유: 서비스(고칠 줄)에서 덮어쓰면 고칠 줄을 건드려 판정 범위 적중이 된다(순진한 오답은 비적중이어야 한다).
- 오답 패치는 KDoc을 건드리지 않는다. 실제 에이전트가 오답과 함께 KDoc "기본 50"(god `:3211`, layered `AdminQuery.kt:8`)을 고쳐도 그 줄은 판정 범위 밖이라 비적중은 그대로다.

## 6. 주석 처리 방침(ADR-0005 §3.1, ADR-0008 §2·§4.2)

- 주입이 없으므로 중립화 hunk가 없다. 변경 과제는 정답이 주석을 바꾸지 않는다.
- 그대로 둔 주석: 공유 기본값 KDoc "limit 1~100(기본 50)"(god `:3211`, layered `AdminQuery.kt:8`) — 다른 6개 목록에 여전히 맞는 서술이라 변경 규칙이 아니다. god 분쟁 함수 KDoc `:3321`(경로 문자열 — 처치 표시). layered `AdminQuery.kt:61` "관리자 분쟁 목록 필터(§11.3): status(…) 선택" — 기본값을 말하지 않는다.
- 전수 grep(두 구조 `src/main`): `기본 50|기본 20|50건|20건|페이지 크기|DEFAULT_LIMIT|분쟁 목록|/disputes|admin/disputes` → 분쟁 목록의 기본값을 서술한 주석은 **없다**. "최근 20건"(god `:3200`, layered `PriceStatsService.kt:28`)은 다른 기능(recent-trades)이다.

## 7. 판정 범위(ADR-0008 §5.2) — `fce.py patch_ranges` 실측

| 패치 | god 바뀐 줄 | layered 바뀐 줄 | 판정 |
|---|---|---|---|
| fix | Marketplace.kt {3324} | AdminQueryService.kt {75} | 범위 |
| alt-1 | Marketplace.kt {3214, 3324} | AdminQuery.kt {16, 17} · AdminQueryService.kt {75} | 범위 |
| **범위 합(symbols fix)** | **{3214, 3324} — 2줄** | **{16, 17}·{75} — 3줄** | |
| naive-1 | Marketplace.kt {3215} | AdminQuery.kt {14} | 비적중 |
| naive-2 | Marketplace.kt {3215, 3216} | AdminQuery.kt {14, 18} | 비적중 |
| naive-3 | AdminQueryController.kt {54} | AdminQueryController.kt {121} | 비적중(다른 파일) |
| (기록) god alt 자연 형태 — 미등록 | Marketplace.kt {3214, **3215**, 3324} | — | naive-1·2와 **3215 겹침** → 등록하지 않음 |

- 정답·alt 범위 어디에도 공유 기본값 줄(god `:3215`, layered `AdminQuery.kt:14`)이 없다. layered alt의 `:17`(상수 사용 줄)은 오답 줄이 아니다.
- ADR §4.3-4: 정답·alt 적중, 오답 3개 모두 비적중(두 구조).

## 8. 공개·숨은 테스트

- 공개(`cases/S4/checks/.../pub/S4Test.kt`): 분쟁 25행을 DB에 넣고(판정이 끝난 REJECTED — 픽스처) `GET /admin/disputes?after=<넣기 전 최대 id>` → 20건·nextAfter = 20번째 id. 기준 상태에서 25건이라 실패한다.
- 숨은(`hidden/S4HiddenTest.kt`) — A9AdminQueryTest 문체(자기 행은 after로 좁힘):
  - A: 25행, limit 생략 → 앞 20건·nextAfter = 20번째, 다음 페이지 5건·null, status=REJECTED 필터 + limit 생략도 20건.
  - B: 105행, limit=50·100·21·1 → 그 수 그대로(nextAfter 포함).
  - C: 감사 로그 55행 직접 삽입, limit 생략 → 50건·nextAfter = 50번째(다른 목록의 기본 50 유지).
  - D: 분쟁 limit=0·101·abc·-1 → 400 `INVALID_REQUEST`, limit=100 → 200.
- 기대 숨은 실패(4개 중): none = A(1) · naive-1 = C(1) · naive-2 = B·C·D(3) · naive-3 = B·D(2).
- 행 삽입은 시드의 `min(order_items.id)`를 가리킨다(`disputes.order_item_id`는 FK만, 유일 제약 없음). 단계마다 DB를 처음 상태로 돌리므로 다른 단계에 영향 없다.

## 9. regression-exclude · verify-exclude

- 둘 다 없음(파일 생략). 정답이 의도적으로 바꾸는 계약 테스트가 없다 — `behavior-tests`의 `/admin/disputes` 목록 호출 6개는 모두 `contract3/A9AdminQueryTest`이고 분쟁 테스트는 3건이라 기본 20의 영향이 없다(limit=0 400 단언도 그대로). 공유 기본값을 바꾸는 naive-1은 A9의 "기본 limit 50"(감사 로그 101행)이 잡는다(ADR §4.2).

## 10. 채택 조건 실측(작성자 스모크)

2026-11-01 12:36~14:16, 1레인 순차(시작 전 가용 20GB — 매 실행 전 12GB 미만이면 대기, 대기 0회). `code_sha` god none~naive-3 = e65ca93, 나머지 = ac21422(그사이 메인 커밋 — 앱·계약 테스트·`patch_ranges` 무변경 확인), `code_dirty` True(S4 산출물 미커밋). 결과 행 = 작업 scratchpad `sct/S4-checks/checks.jsonl`.

| 구조 | 패치 | 단계 | success | 공개 | 숨은 실패(4개 중) | 회귀 |
|---|---|---|---|---|---|---|
| god·layered | none | public,hidden | False | fail(25건) | A (1) | — |
| god·layered | fix | 전 단계 | **True** | pass | 0 | contract 122 · contract2 89 · contract3 252 통과 |
| god·layered | alt-1 | 전 단계 | **True** | pass | 0 | 〃 |
| god·layered | naive-1(공유 기본 50→20) | public,hidden | False | pass | C (1) | — |
| god·layered | naive-2(공유 기본·최대 → 20) | public,hidden | False | pass | B·C·D (3) | — |
| god·layered | naive-3(컨트롤러에서 "20" 덮어쓰기) | public,hidden | False | pass | B·D (2) | — |

- 숨은 실패 수·실패 테스트가 패치마다 두 구조 같다(ADR-0008 §4.3-2). 패치 10개 모두 기준 사본에 offset·fuzz 없이 적용(`patch --dry-run`).
- 남은 일(메인): 고정 SHA에서 fix 5회 결정성·판정 2회 재현, 리뷰어 단서 측정.

## 11. 메인 판단(2026-11-01)

- **god alt-1 형태:** 시그니처 + 아래 한 줄 삽입 형태로 등록한다. 자연형(:3215 수정)은 naive-1·2와 범위가 겹친다. 에이전트가 시그니처와 :3215를 한 편집에서 고치면 :3214로 적중하므로, 이 형태로도 판정이 누락되지 않는다.
- **컨트롤러 `defaultValue="20"`:** alt로 등록하지 않는다. 빈 `limit=` 요청이 400에서 200으로 바뀌는 계약 변경이라 정답 자리로 보지 않는다. 숨은 테스트는 빈 `limit=`을 검사하지 않으므로 이 형태로 성공하면 `fce_fallback`(첫 편집 턴)으로 들어가고 표시된다. 파일럿 보고에서 fallback 건을 따로 센다.
