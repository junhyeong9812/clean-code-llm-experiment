# T1 설계 — 포인트 사용 배분 순서(bug)

> ADR-0005(개정 1) §3.2 T1 행. 작성 기준 커밋 `3304c68`(feat/tasks-a-judge). 줄 번호는 별도 표시가 없으면 원본(= inject 적용 후와 같음 — 주입은 줄 수를 바꾸지 않는다).

## 1. 의도

- 규칙: 결제에서 포인트를 쓰면(예약) 사용 가능한 적립 묶음을 **만료가 이른 것부터**, 만료 시각이 같으면 id 순으로 차감한다(ADR-0003 §5.2·ADR-0002 §9.6 FIFO).
- 주입: 배분 정렬 `(expires_at, id)` → `(created_at, id)`. 두 구조 모두 같은 한 줄이다.
- 증상: 적립 순서와 만료 순서가 다르면(먼저 결제한 주문을 나중에 확정) 유효기간이 많이 남은 묶음을 먼저 쓴다. 만료가 이른 묶음은 남고, 그 만료 때 소멸된다.
- 기존 계약 테스트는 이 주입을 잡지 못한다. `PointRuleTest` FIFO 두 테스트는 `Db.createPointBundle` 픽스처를 쓴다. 이 픽스처는 `paid = confirmedAt − 9일 + 1분`이고 `expires_at = confirmedAt + 365일`이다. 그래서 생성 순서와 만료 순서가 늘 같다(behavior-tests `support/Db.kt:149-166`).

## 2. 구조별 원인 위치

| 구조 | 고칠 곳(fix) | 경로 |
|---|---|---|
| god | `Marketplace.kt:1108` `" ORDER BY created_at, id"` | `payOrder`(:596) → `reservePoints`(:1092-1123)의 묶음 조회 |
| layered | `infra/JpaPointRepositories.kt:37` `" ORDER BY created_at, id"` | `PaymentService`(:115) → `PointBook.reserve`(:22-26) → `PointRepository.usableBundles`(선언 `domain/Point.kt:289`) → `JpaPointRepository.usableBundles`(:33-39) → `PointLedgerEntry.allocate`(`domain/Point.kt:38-51`, 받은 순서대로 차감) |

- 고칠 곳 수: 1 대 1.
- 그대로 두는 같은 규칙의 해제 쪽: 복구 정렬 `expires_at DESC, id DESC`(god `:1215`, layered `infra/JpaPointRepositories.kt:73`). 해제(`releasePoints` god `:1126`, layered `PointBook.release` `:29`)는 배분 행을 그대로 되돌리므로 정렬이 없다.

## 3. 정답

- `fix.god.patch`·`fix.layered.patch`: 위 한 줄을 `" ORDER BY expires_at, id"`로 되돌린다. 주석은 되돌리지 않는다. 코드만 바꾸면 정답이다.
- **대안 정답이 통과해야 하는 이유:** 숨은 테스트는 묶음별 `remaining`, `/me/points`, 소멸 배치 뒤 잔액만 본다. SQL·정렬 문구·배분 행 순서는 보지 않는다. 그래서 다음도 통과한다.
  - 정렬 `expires_at, created_at`·`expires_at, id DESC`처럼 만료가 같을 때의 순서를 달리한 것. 숨은 테스트의 세 묶음은 만료 시각이 모두 다르다.
  - 메모리에서 정렬하기(예: layered `allocate` 앞에서 `sortedWith(compareBy({ it.expiresAt }, { it.id }))`, god `bundles.sortedBy`).
  - 정렬을 서비스나 도메인으로 옮긴 리팩터링.
- 만료가 같을 때 id 순을 깨는 대안은 숨은 테스트를 통과한다. 그러나 회귀 `PointRuleTest."FIFO - 만료 시각이 같으면 id 순"`에서 실패한다. 이 계약은 ADR에 있으므로 정상적인 판정이다.

## 4. 숨은 테스트(`hidden/T1HiddenTest.kt`)

자연 흐름은 다음과 같다. 모두 HTTP로 진행한다. 사용자·상품만 DB에 직접 넣는다.

1. 결제 A(100,000) → +1분 → 결제 B(60,000) → +1분 → 결제 C(80,000). 각각 포인트 없이 결제한다.
2. 셋 다 발송·입고·검수 PASS·배송 완료.
3. B 확정 → +1일 → A 확정 → +1일 → C 확정.
4. 생성 순서는 A < B < C(id도 같음), 만료 순서는 B < A < C가 된다.

단언(결과만):

- 준비 확인(D12 실측 장치): 세 묶음의 `created_at`·id·`expires_at` 순서가 위와 같다. 사용액 1,000이 B보다 크고, A+B보다 작고, C보다 크다. 적립액은 상수로 두지 않고 DB에서 읽는다.
- 1,000P 사용 뒤: B `remaining` = 0, A = A − (1,000 − B), C = C 그대로. 사용 가능액·available은 1,000만큼 줄어든다.
- 시계를 B의 `expires_at`으로 옮기고 `point-expiry`를 돌린다. 그 사용자의 EXPIRE 행은 0이고 available·usable은 그대로다.
- 시계를 A의 `expires_at`으로 옮기고 `point-expiry`를 돌린다. available = usable = C 전부.

세 묶음을 쓰는 이유는 생성 역순(LIFO, naive-3)을 가려내기 위해서다. 묶음이 둘(A·B)이면 생성 역순도 B부터 써서 정답과 결과가 같다.

### D12 실측(T1 부분) — 적립 묶음의 `created_at`·`expires_at`이 정해지는 때

| | `created_at` | `expires_at` |
|---|---|---|
| god | 승인 반영 때 `paidAt`(`Marketplace.kt:674`, `approveOrder` :641) | 확정 때 `confirmedAt + 365일`(`:1154`). `confirmedAt = min(현재, delivered_at + 7일)`(`:876-877`), auto-confirm은 `delivered_at + 7일`(:1508) |
| layered | `PointLedgerEntry.pendingEarn(paidAt)`(`domain/Point.kt:180-190`, 호출 `domain/Order.kt:253`) | `confirmEarn` → `confirmedAt + EARN_VALID_FOR(365일)`(`domain/Point.kt:84-90`). `confirmationTime = min(현재, delivered_at + 7일)`(`domain/Order.kt:324-328`), 자동 확정 `autoConfirmationTime`(:344-348) |

- 두 구조 모두 생성 시각은 결제 승인 시각, 만료 시각은 확정 시각 + 365일이다. 같은 흐름이 같은 결과를 낸다.
- 실측: 주입 상태 두 구조에서 숨은 테스트의 준비 확인(순서 3개)이 모두 통과했다. 실패는 그다음 첫 결과 단언 `만료가 가장 이른 묶음(B)부터 다 써야 한다 ==> expected: <0> but was: <600>`에서 났다(god·layered 같음, 진단 실행).
- 확정 사이에 시계를 옮기지 않으면 결과가 같아진다. 같은 요청 시각에 확정한 묶음은 `expires_at`이 같다. 그러면 정답 `(expires_at, id)`가 id 순, 곧 생성 순과 같아지고, 주입 `(created_at, id)`와 배분이 같아진다. 이는 코드에서 도출한 결론이다(`confirmedAt = 현재`). 이 변형은 따로 돌리지 않았다.
- 결제 사이 +1분은 필수가 아니다. 같은 시각이면 `created_at`이 같아 id로 갈리고, 주입 결과는 여전히 A부터다. 준비 확인에서 `created_at` 엄격 순서를 보려고 넣었다.

## 5. 공개 테스트(`cases/T1/checks/.../pub/T1Test.kt`)

- 묶음 2개(A 먼저 결제·나중 확정 +2일, B 나중 결제·먼저 확정) → 1,000P 사용 → +364일(B만 만료) → `point-expiry` → `/me/points`의 usable·available이 사용 전 − 1,000이다.
- 주입 상태 실측: `expected: <600> but was: <0>`(B 600 소멸, A는 이미 다 씀).
- DB 직접 조회는 준비(사용자·상품 INSERT, 판매자 id)에만 쓴다. 단언은 HTTP 응답만 본다. naive-3(생성 역순)은 공개 테스트를 통과한다. 이는 의도한 것이며, 숨은 테스트가 잡는다.

## 6. 순진한 오답(naive-N, 각각 숨은 테스트 실패 — 실측 표 §9)

| N | 패치(두 구조 같은 한 줄) | 사용 1,000 뒤 (A, B, C) remaining | 숨은 테스트 실패 지점 |
|---|---|---|---|
| 1 | `ORDER BY expires_at DESC, id DESC`(만료 늦은 것부터 — 복구 정렬을 그대로 베낌) | (800, 600, 0) | B remaining 0 아님 |
| 2 | `ORDER BY id`(id 순 — 생성 순과 같음) | (0, 600, 800) | 〃 |
| 3 | `ORDER BY created_at DESC, id DESC`(최근 적립부터, LIFO) | (1000, 400, 0) | 〃(공개는 통과) |

- 고려했으나 패치로 만들지 않은 오답: "사용은 그대로 두고 소멸 배치만 고치기". 예를 들어 point-expiry가 만료 묶음을 소멸하지 않거나, 다른 묶음에서 빼게 바꾸는 것이다. 숨은 테스트의 1,000P 사용 직후 묶음별 remaining 단언(B = 0)에서 소멸 배치를 돌리기 전에 실패하므로 통과할 수 없다.

## 7. 제외한 유사 지점과 사유

- 복구 정렬(god `:1215`, layered `infra/JpaPointRepositories.kt:73`): 같은 규칙의 반대쪽이다. 주입하면 복구 경로 계약 테스트(`RefundTest` 등)가 깨지고 고칠 곳이 2가 된다. ADR §3.2에 따라 그대로 둔다.
- 소멸 배치 대상 순서(god `:1514-1542` "id 순으로 소멸", layered `BatchService.kt:269`·`KeysetBatchTargets.kt:41`): 묶음마다 독립된 트랜잭션이라 순서는 결과에 영향이 없다. 손대지 않는다.
- 사용 가능액(`usablePoints` god `:1080`, layered `JpaPointRepository.usable` :25): 합계라 순서가 없다.
- 입찰 매칭 정렬 `created_at ASC, id ASC`(god `:255`, layered `infra/JpaBidRepositories.kt:55`): 다른 규칙이다. 주입 문구와 모양이 같아 에이전트가 참고할 수 있지만 고칠 대상이 아니다.

## 8. 지운 주석과 남는 올바른 구현 잔여물

지운 주석(inject 패치에 포함, 저장소 전체 grep `expires_at, id`·`만료.*(가까|이른|먼저)`·`FIFO`·`usableBundles`로 확인):

| 구조 | 위치(원본) | 원문 → 주입 후 |
|---|---|---|
| god | `Marketplace.kt:1089` KDoc | "사용 가능 묶음을 (expires_at, id) 순으로 차감해" → "사용 가능 묶음에서 차감해" |
| layered | `domain/Point.kt:35` KDoc(`allocate`) | "사용 가능 묶음을 (expires_at, id) 순으로 받아" → "사용 가능 묶음을 받아" |
| layered | `domain/Point.kt:288` KDoc(`PointRepository.usableBundles`) | "(remaining > 0), (expires_at, id) 순." → "(remaining > 0)." |
| layered | `service/PointBook.kt:21` KDoc(`reserve`) | "사용 가능 묶음 (expires_at, id) 순 차감·배분" → "사용 가능 묶음 차감·배분" |

지운 개수는 god 1, layered 3이다. 원본 layered는 같은 규칙을 세 층(서비스·도메인·포트)에 서술했다.

남는 올바른 구현 잔여물은 복구 쪽 "만료가 늦은 것부터(같으면 id 큰 것부터)"다. 이는 사용 규칙의 거울상이라 단서가 된다.

| 구조 | 위치 | 종류 | 개수 |
|---|---|---|---|
| god | `Marketplace.kt:1187` KDoc(restorePoints), `:1215` SQL | 주석 1 + 코드 1 | 2 |
| layered | `domain/Point.kt:208` KDoc(PointRestoration), `domain/Point.kt:303` KDoc(restorableBundles), `infra/JpaPointRepositories.kt:73` SQL | 주석 2 + 코드 1 | 3 |

- 남긴 이유: 복구 규칙은 이 과제가 주입하는 규칙(사용 배분)이 아니다. 그 정렬 코드는 ADR §3.2가 그대로 두라고 정했다. 주석만 지우면 코드(DESC 정렬)가 남으므로 단서 제거 효과도 없다.
- 미해결(메인 판단 요청): 복구 쪽 주석 god 1·layered 2도 지울지 정해야 한다. 지우면 잔여물은 god 1, layered 1(SQL만)로 맞춰진다.

## 9. 두 구조 비대칭과 "어느 구조에 유리한가"

- **경로 길이:**
  - god: 결제에서 배분까지 한 파일, 한 함수다(`reservePoints` :1092-1123). 차감 루프와 정렬이 15줄 안에 있다.
  - layered: 서비스 → 도메인 → 포트 → infra로 4파일을 지난다. 도메인 `allocate`는 "받은 순서대로" 차감할 뿐이라 순서 결정이 infra SQL에 숨어 있다. 도메인부터 읽은 에이전트는 포트 구현까지 한 번 더 따라가야 한다.
  - 경로 길이는 god에 유리하다.
- **단서 근접성:**
  - layered: 거울 규칙 SQL(`:73`)이 고칠 곳(`:37`)과 같은 파일 36줄 아래에 있다. 포트 선언 주석(`Point.kt:303` 복구 순서)도 지운 주석 자리(`:288`) 15줄 아래에 있다.
  - god: 거울 SQL(`:1215`)·KDoc(`:1187`)이 고칠 곳에서 80~107줄 떨어져 있다. 1,772줄 파일을 offset으로 읽으면 같은 창에 들어올 수 있다.
  - 단서 근접성은 layered에 약간 유리하다.
- **증상에서 출발하는 탐색:** 증상 문장이 "소멸"을 말하므로 point-expiry 배치부터 볼 가능성이 크다. god는 같은 파일 `:1514`, layered는 `service/BatchService.kt:268`·`infra/KeysetBatchTargets.kt:41`이다. 거기서 결제 쪽으로 되돌아가는 거리는 god가 짧다.
- **판단:** 큰 편향은 없다. 경로는 god, 단서 밀도는 layered에 유리해 방향이 엇갈린다. 고칠 곳 1 대 1, 같은 의미의 한 줄이므로 처치 효과 과제(C2·T2·P2)로 표시하지 않는다. 결과 해석 때 layered의 포트 간접 1단(`usableBundles`)이 첫 겨냥 턴을 늦추는지 기전 지표로 본다.

## 10. regression-exclude · verify-exclude

- 둘 다 빈 파일이다. fix 상태는 원본 코드와 같고(inject + fix = 원본, 주석만 다름), 정답이 깨는 계약은 없다. 실측: fix 전 단계 표(아래).

## 11. 채택 조건 실측

`experiment/results/checks/checks.jsonl`의 T1 행(2026-10-26, 각 1회 — fix 5회 반복은 메인 몫):

| 구조 | 패치 | 단계 | success | outcome |
|---|---|---|---|---|
| god | none | public,hidden | False | public fail · hidden fail |
| layered | none | public,hidden | False | public fail · hidden fail |
| god | fix | 전 단계 | **True** | public·hidden pass, contract 122/122, contract2 89/89, verify 위반 0 |
| layered | fix | 전 단계 | **True** | 〃 |
| god | naive-1·2·3 | hidden | False ×3 | hidden fail |
| layered | naive-1·2·3 | hidden | False ×3 | hidden fail |

- 주입 상태 실패 메시지(진단 실행으로 확인): 숨은 테스트 `만료가 가장 이른 묶음(B)부터 다 써야 한다 ==> expected: <0> but was: <600>`(두 구조), 공개 테스트(god) `쓰고 남은 사용 가능 포인트 … expected: <600> but was: <0>`.
- naive의 실패 메시지는 check 행에 남지 않아 따로 보지 않았다. 준비 확인은 정렬과 무관하므로(주입 상태에서 통과) 실패 지점은 배분 단언이다(§6 표의 계산).
