# D2 설계 — 포인트 차감 순서(bug, 원인 추적)

> ADR-0009 §4 D2 행이 정본. 채택 조건은 ADR-0008 §4.3 1·2번(찾기 비용·symbols 조건은 ADR-0009가 걸지 않는다 — symbols·groups 파일 없음).
> 작성 기준: 브랜치 `delegation-measure`, HEAD `8389a02`(앱 소스는 `ddd3d56` 이후 변경 없음 — 줄 번호는 이 HEAD의 `god/`·`layered/` 기준).
> 줄 번호: 주입은 두 구조 모두 **줄 수를 바꾸지 않는다**(주석 1줄 → 1줄, 코드 1줄 → 1줄). 원본 = 주입 사본 좌표다.

## 1. 의도

- 규칙(ADR-0003 §5.2·ADR-0002 §9.6): 결제 포인트 예약은 사용 가능 묶음(EARN·AVAILABLE·만료 전·remaining > 0)을 **(expires_at, id) 순**으로 차감한다. 소멸(ADR-0004 §8.3 `point-expiry`)은 만료된 묶음의 남은 양 전부. 복구(§7.3)는 반대로 **만료가 늦은 묶음부터**(같으면 id 큰 것부터).
- 주입: 차감 묶음 조회 `ORDER BY expires_at, id` → `ORDER BY expires_at DESC, id`(만료 늦은 것부터, 같은 만료끼리는 id 순 그대로).
- 유형: 원인 추적. 증상은 **소멸 배치**(`point-expiry`)에서 보이고, 원인은 **결제 경로의 예약 차감 순서**에 있다. 둘은 코드 호출로 이어지지 않고 데이터(`remaining`)로만 이어진다.
  - god: `POST /orders` → `payOrder` → `createOrder`(:564, 호출 :658) → `reservePoints`(:1154) → 조회 :1170. 증상 자리 `expirePoints`(:1594) 와 같은 파일, 420줄 떨어짐.
  - layered: `PaymentService.kt:115` → `PointBook.reserve`(:22) → `Point.allocate`(:38) + `JpaPointRepository.usableBundles`(:33) → 조회 :37. 증상 자리 `PointExpiryBatch`(`BatchService.kt:299`) → `PointBook.expire`(:65) → `Point.expiration`(:130), 대상 조회 `KeysetBatchTargets.kt:47`.
- 오진 유도: 같은 파일에 **복구 순서 `ORDER BY e.expires_at DESC, e.id DESC`**(god :1277 / layered `JpaPointRepositories.kt:73`)가 있다 — 원래 정상인 DESC라 "DESC를 고친다"는 탐색이 여기로 갈 수 있다. 복구 주석("만료가 늦은 묶음부터" — god :1249, layered `Point.kt:208`·`:303`)은 규칙 서술이지만 복구 규칙이라 그대로 둔다(두 구조 같음).

## 2. 주입(`inject.{god,layered}.patch`)

| 구조 | 코드 | 줄 |
|---|---|---|
| god | `Marketplace.kt` `reservePoints`: `" ORDER BY expires_at, id",` → `" ORDER BY expires_at DESC, id",` | :1170 |
| layered | `infra/JpaPointRepositories.kt` `usableBundles`: 같은 변경 | :37 |

### 2.1 규칙 서술 주석 — 중립 문장(줄 수 유지)

전수 grep: 두 구조 `src/`(main·test)에서 `grep -rn "FIFO\|expires_at, id\|이른\|먼저 만료\|만료가 빠른\|만료 순\|오래된 묶음\|먼저 적립\|순으로\|순 차감"` → 차감 순서를 서술한 주석은 아래 4곳뿐이다(나머지는 복구·입찰 만료·배치 id 순 서술과 코드 줄). `src/test`에는 앱 스모크 테스트뿐이고 관련 낱말이 없다.

| 구조 | 위치 | 원문 → 주입 후 |
|---|---|---|
| god | `Marketplace.kt:1151`(reservePoints KDoc) | "… 사용 가능 묶음을 (expires_at, id) 순으로 차감해 배분을 쓰고" → "… 사용 가능 묶음에서 차감해 배분을 쓰고" |
| layered | `service/PointBook.kt:21`(reserve KDoc) | "… → 사용 가능 묶음 (expires_at, id) 순 차감·배분 → …" → "… → 사용 가능 묶음에서 차감·배분 → …" |
| layered | `domain/Point.kt:35`(allocate KDoc) | "사용 가능 묶음을 (expires_at, id) 순으로 받아 앞에서부터 차감하고" → "사용 가능 묶음을 받아 앞에서부터 차감하고" |
| layered | `domain/Point.kt:288`(usableBundles 선언 KDoc) | "차감할 사용 가능 묶음(remaining > 0), (expires_at, id) 순." → "차감할 사용 가능 묶음(remaining > 0)." |

- 그대로 둔 주석: 복구 규칙(god :1249 · layered `Point.kt:208`·`:303` — 오진 유도 재료), `point-expiry` "id 순으로 소멸"(god :1590 · layered `BatchService.kt:295` — 대상 처리 순서, 차감 규칙 아님), 사용 가능액 정의(god :1141 · layered `Point.kt:11`·`:285`).
- 주입 사본은 두 구조 모두 컴파일되고 공개 테스트가 실패한다(§8 none 행).

## 3. 과제 문구(`cases/D2/TASK.md`)·공개 테스트

- 증상(소멸 배치에서 이미 썼어야 할 1,000P가 소멸)과 기대 동작(유효기간이 먼저 끝나는 적립분부터 나간다, 끝이 같으면 지금처럼 먼저 생긴 것부터)만 쓴다. 고칠 곳 이름·정렬·ORDER BY·FIFO·DESC·allocate·reserve 낱말 없음. 코드 낱말 '묶음'·'차감'·'배분' 대신 '적립분'·'나간다'를 쓴다.
- 소멸·사용 가능액 계산·취소 복구는 "지금과 같다"고 못박는다(naive-1·3·2·5를 과제 문구로도 배제).
- 공개(`pub/D2Test.kt`): 먼저 적립된 1,000P(이틀 전 확정)·나중 2,000P(하루 전 확정)를 SQL로 넣고(behavior-tests `Db.createPointBundle` 모양 — 출처 거래 1건 + EARN + 잔액 캐시), 체결 1건을 1,000P로 결제 → 먼저 적립된 쪽 만료 시각 **정각**으로 시계 이동 → `point-expiry` → 그 구매자의 EXPIRE 합 0, `GET /me/points` usable 2,000. 주입 상태 실패 메시지: "point-expiry가 소멸한 포인트 expected <0> but was <1000>".

## 4. 정답

- `fix.{god,layered}.patch`: 기준 복원 `DESC` 제거(god :1170, layered `JpaPointRepositories.kt:37`). 주석은 되돌리지 않는다. inject + fix = 원본 코드(실측: 원본과 diff는 중립화 주석 줄뿐).
- `alt-1.{god,layered}.patch`: 조회는 그대로 두고 **코드에서 (만료 시각, id) 순으로 다시 늘어놓는다**. god `reservePoints` 루프 :1174(`for (bundle in bundles.map { it as PointLedgerEntry }.sortedWith(compareBy({ it.expiresAt }, { it.id })))`, :1176 캐스트 줄 삭제) / layered `domain/Point.kt:42` `allocate` 루프(`bundles.sortedWith(compareBy({ it.expiresAt }, { it.id }))`). 다른 파일(layered 도메인)·다른 줄에서 고치는 형태다.
- 숨은 테스트는 배분 행·remaining·EXPIRE·`GET /me/points`만 보므로 두 형태를 다 받는다.

## 5. 순진한 오답(naive-N — 컴파일되고 그럴듯하며 숨은 테스트에서 실패해야 한다)

ADR-0009는 첫 편집 지표를 쓰지 않으므로 오답이 고칠 줄과 겹쳐도 된다(naive-4·5).

| N | 패치(두 구조 같은 의미) | 바꾼 줄 god / layered | 공개 | 기대 숨은 실패 |
|---|---|---|---|---|
| 1 | 소멸 배치 오진: 만료 시각 정각에는 아직 소멸하지 않는다(대상 조건 `expires_at <= :now` → `<`) | :1597 / `KeysetBatchTargets.kt:47` | 실패(정각에 사용 가능액에서는 빠진다) | A·B·D (3) |
| 2 | 복구 순서 오진(정상 DESC를 고침): `ORDER BY e.expires_at, e.id` | :1277 / `JpaPointRepositories.kt:73` | 실패 | A·B·D (3) |
| 3 | 사용 가능액 오진: 만료 시각 정각의 묶음도 사용 가능액에 넣는다(`expires_at > :now` → `>=`) | :1145 / `JpaPointRepositories.kt:28` | 실패(소멸은 그대로 일어난다) | A·B·D (3) |
| 4 | "먼저 적립된 것부터" = 생성 순: `ORDER BY id` | :1170 / `JpaPointRepositories.kt:37` | **통과**(공개는 id 순 = 만료 순) | A (1) |
| 5 | 과잉 수정: 정답 + 복구도 이른 만료부터(두 ORDER BY를 같은 방향으로) | :1170·:1277 / `:37`·`:73` | **통과** | D (1) |

- naive-1·2·3은 주입을 그대로 두므로 배분(A)·소멸(B)·복구 준비 배분(D)에서 실패한다. naive-4·5가 공개 테스트를 통과하는 함정이다: 4는 id 순과 만료 순이 어긋나는 A만, 5는 복구 순서를 보는 D만 잡는다.
- naive-3 첫 판(만료 조건 `AND expires_at > :now`를 통째로 뺌)은 버렸다: 쓰이지 않는 `:now` 바인딩이 예외를 내 결제가 400 `UNEXPECTED_ERROR`로 끝났다(그럴듯한 오답이 아니라 깨진 코드 — check 행 god 10:37·layered 10:43, 숨은 A~D 4개 모두 '준비: 결제' 실패).
- 고려했으나 만들지 않은 것: 소멸 배치에서 "쓰인 적 있는 묶음은 소멸하지 않음"(공개 시나리오에서 이른 묶음이 안 쓰여 증상이 그대로 — 그럴듯함이 약하다).

## 6. 숨은 테스트(`hidden/D2HiddenTest.kt` + `D2Fixtures.kt`)

- 준비 도우미 `D2Fx`: 사용자·빈 사이즈·적립분(출처 거래 + EARN AVAILABLE + 잔액 캐시, 만료 = 확정 + 365일)은 SQL, 입찰·체결·결제·구매자 취소·배치는 API.
- 각 테스트는 그 시작 시점의 현재 시계에서 출발하고 새 사용자·사이즈를 쓴다(메서드 이름 순 실행, 시계는 B에서만 앞으로 약 363일 이동 — 뒤 테스트는 상대 시각만 쓰므로 영향 없음).
  - A 배분: 만료 늦은 1,000(id 앞)·만료 이른 600(id 뒤) → 1,000P 결제 → 배분 {이른 600, 늦은 400}, remaining 0·600.
  - B 소멸: 이른 1,000·늦은 2,000 → 1,000P 결제 → 이른 쪽 만료 + 1시간(늦은 쪽은 23시간 남음) → `point-expiry` → 그 구매자 EXPIRE 합 0, (usable, available) = (2,000, 2,000), remaining 0·2,000.
  - C 배분: 만료 같은 600·1,000 → 1,000P 결제 → {앞 id 600, 뒤 id 400}(주입·naive 모두 통과 — `expires_at, id DESC` 류 잘못된 정답 방지용). 두 적립분의 created_at은 id 순으로 1초 벌린다(`D2Fx.bundle(…, earnShift = 1초)` — 체결·결제·적립 시각만 옮기고 확정·만료는 그대로, 준비 단언으로 확인). 처음 판은 created_at이 같아 'created_at 순' 대안 정답에서 결과가 비결정적일 수 있었다(리뷰 DT-8).
  - D 복구: 이른 1,000·늦은 2,000 → 체결 2건 한 주문 2,000P → 배분 {1,000, 1,000} 확인 → id 큰 항목(배송비 없음)만 구매자 취소 → RESTORE 배분 = {늦은 묶음: 복구 양}(복구 양 1..1,000 준비 단언), remaining 이른 0·늦은 1,000 + 복구 양.
- 기대 숨은 실패: none = A·B·D(3/4) · naive-1·2·3 = A·B·D(3/4) · naive-4 = A(1/4) · naive-5 = D(1/4).

## 7. regression-exclude · verify-exclude

- 만들지 않았다(파일 없음 = 빈 목록, `judge.read_list`). 정답 = 원본 코드라 의도적으로 바뀌는 계약 테스트·verify 검사가 없다. 주입 상태에서는 `contract/PointRuleTest` "FIFO - 만료가 이른 묶음부터"가 깨진다(숨은 재료와 같은 규칙).

## 8. 채택 조건 실측(작성자 스모크 — 2회 재현·5회 결정성은 메인이 커밋 뒤)

모든 행: `code_sha` 81d7983(기준 8389a02 위에 메인의 docs 커밋 1개 — 앱·러너·experiment 변경 없음), `code_dirty` True(D2 산출물 미커밋), 2026-11-03 08:47~12:13, `flock /tmp/ccle-judge.lock` 1레인 순차. 결과 행 = `experiment/results-del-tasks/checks.jsonl`(D1 행과 같은 파일에 섞여 있다).

| 구조 | 패치 | 단계 | success | 공개 | 숨은 실패(4개 중) | 회귀 |
|---|---|---|---|---|---|---|
| god·layered | none | public,hidden | False | fail("point-expiry가 소멸한 포인트 expected <0> but was <1000>") | A·B·D (3) | — |
| god·layered | fix | 전 단계 | **True** | pass | 0 | contract 122 · contract2 89 · contract3 252 통과, verify 위반 0 |
| god·layered | alt-1 | 전 단계 | **True** | pass | 0 | 〃 |
| god·layered | naive-1(소멸 대상 `<=`→`<`) | public,hidden | False | fail | A·B·D (3) | — |
| god·layered | naive-2(복구 순서 이른 만료부터) | public,hidden | False | fail | A·B·D (3) | — |
| god·layered | naive-3(사용 가능액 `>`→`>=`) | public,hidden | False | fail | A·B·D (3) | — |
| god·layered | naive-4(`ORDER BY id`) | public,hidden | False | **pass** | A (1) | — |
| god·layered | naive-5(정답 + 복구도 이른 만료부터) | public,hidden | False | **pass** | D (1) | — |

- 숨은 실패 수·실패 테스트 이름이 패치마다 두 구조 같다(ADR-0008 §4.3-2). tampered 0(모든 행 `[]`).
- 실패 메시지 예(none, 두 구조 같음): A "결제 1,000P의 배분 expected <{이른=600, 늦은=400}> but was <{늦은=1000}>", B "point-expiry가 소멸한 포인트 expected <0> but was <1000>", D "결제 2,000P의 배분 expected <{1000, 1000}> but was <{늦은=2000}>".
- 남은 일(메인): 고정 SHA에서 fix·alt 판정 2회 재현, fix 숨은 테스트 5회 결정성, none·naive 2회째.

### 리뷰 반영 뒤 재확인 (DEL-F1 — DT-8)

2026-11-03 18:34 ~ 18:48, `code_sha` `01edabf`, `code_dirty` True(미커밋), `flock` 1레인 순차, `--stages hidden`: fix god·layered True, alt-1 god·layered True(숨은 4/4, tampered 0). C 준비 단언(created_at 차 1초) 포함 통과. none·naive는 C를 원래 통과하므로(주입은 같은 만료끼리의 순서를 바꾸지 않음) 다시 돌리지 않았다.
