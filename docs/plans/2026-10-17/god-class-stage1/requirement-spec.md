# 요구사항 명세서 — 갓 클래스 구현 1단계 (god)

> 작성일: 2026-10-17 · 작업 폴더: `docs/plans/2026-10-17/god-class-stage1/` · 브랜치 `feat/god-class-stage1`(main `88aad98`)

---

## 0. 요구사항 원문 (인터뷰 기록)

- 원문: "다음 작업 진행하자" → NEXT N1(갓 클래스 구현 1단계 — 행위 테스트 1단계 계약 117을 god에서 green으로)
- Q/A:
  - 저장 방식 → **JPA 엔티티 + SQL 혼합**: 엔티티는 갓 클래스 밖, 갓 클래스가 EntityManager로 읽고 쓰며 잠금·호가 조회·배치는 네이티브 SQL
  - 코드 성격 → **자연스러운 절차지향**: 클래스 1개·파일 1개, 추상화·도메인 객체 없이 요청마다 절차를 쭉 적는다. 헬퍼 메서드는 사람이 자연스럽게 나눌 만큼만. 읽을 수는 있는 코드(일부러 나쁘게도, 일부러 깔끔하게도 쓰지 않는다)
  - 진행 순서 → **계약 조항 순서**: 시계·관리 → 입찰·체결 → 결제·포인트 → 배치, 단계마다 run.sh green 수 기록
  - 구현 주체 → **Opus 워커 구현**(단계별 순차), 메인은 브리핑·게이트·리뷰 종합·검증
  - 범위 → **god만**(layered는 분리 작업에서)
  - 엔티티 → **1단계가 쓰는 테이블만**
  - god 모듈 자체 테스트 → **행위 테스트로 충분**(기존 스모크만 유지)
  - 규모 → **1만 줄은 최종 목표, 지금은 자연 크기**(줄 수 채우기 금지)

---

## 1. 목표·대상 (필수)

`god` 모듈에 ADR-0003(1단계: 입찰·체결·결제·포인트·배치)을 구현해, `tools/behavior/run.sh god`가 **infraTest 18 + contractTest 117 전부 통과, verify.sql 76개 위반 0, exit 0**으로 끝나면 완료.

## 2. 경계·불변식 (필수)

- **구조(ADR-0001·ADR-0002 §8):** 절차·규칙·저장은 `god` 모듈의 클래스 1개, 파일 1개에 둔다. 컨트롤러·JPA 엔티티·요청/응답 DTO·오류 응답 변환은 그 밖에 둔다. 갓 클래스 밖의 코드는 절차·규칙을 갖지 않는다(컨트롤러는 파싱·위임·직렬화만).
- **예외:** 시계 상태(멈춘 시계, 앞으로만 이동)와 PG HTTP 호출은 갓 클래스 밖의 얇은 빈으로 둘 수 있다. 판단(결과 해석·재시도·상태 반영)은 갓 클래스 안에 둔다.
- **계약(ADR-0003):** 응답 모양·HTTP 상태·`code`·판정 순서(401→400→멱등→404→403→409→422), 어떤 요청도 5xx 없음, 같은 요청 순서 → 같은 DB 결과.
- **동시성(§3.3):** 사이즈의 입찰 상태를 바꾸는 처리는 `product_sizes` 행 `FOR UPDATE` 트랜잭션 안에서, `bid_events`는 잠근 뒤에 쓴다. 같은 멱등 키 동시 요청 → 주문 1건.
- **결제(§5.1):** 주문 행은 PG 호출 전에 커밋. PG 응답 없음(타임아웃·연결 실패·5xx) → UNKNOWN, 5xx 아님. `pg_transactions`는 결과가 정해진 뒤 1행.
- **금액(ADR-0002 §5·§9.2·ADR-0003 §8):** 표 A~D와 verify의 금액 검사를 그대로 만족.
- **시각:** 앱은 주입된 시계만 쓴다(`now()`·`Instant.now()`·DB `now()` 금지). 요청 하나의 모든 시각은 같은 값.
- **스키마:** `ddl-auto: validate` 유지. 스키마(`docker/schema/`)는 바꾸지 않는다.
- **동작 불변식 신설 여부:** 위 불변식은 전부 ADR-0002/0003에 정의되어 있고 `verify.sql`과 behavior-tests가 이미 강제한다 → 설계 선검증 대상 아님(log 1행 기록).

## 3. 기준소스 (필수)

- 정본 순서: 금액·상태 규칙 = ADR-0002(§9 우선) → 실행 판정 = `tools/seedgen/verify.sql` → HTTP 표면·세부 = ADR-0003 → 테스트 = `behavior-tests/`(ADR-0003만 보고 블라인드로 쓴 것).
- 테스트 데이터: 시드 이미지 `ccle-postgres-seed:1k-s42`(run.sh가 띄움). 스키마: `docker/schema/tables.sql`·`constraints.sql`.

## 4. 금지영역 (필수)

- `layered/` 전부, `behavior-tests/`, `tools/`(verify.sql·seedgen·run.sh), `docker/schema/`, ADR-0001~0003 본문 — **수정 금지**. 테스트·verify·ADR이 서로 어긋나 보이면 구현으로 우회하지 않고 멈추고 보고 → 사용자 재합의.
- **재합의 1 (2026-10-17):** ADR-0003 §7 payment-due 대상에서 PAYING 주문이 있는 체결도 제외(§9의 6 개정). 사용자: "PAYING도 만료에서 제외". 그 외 ADR·테스트·verify 수정 금지는 유지.
- 루트 `build.gradle.kts`의 공통 의존성 변경 금지(두 앱 같은 의존성 — ADR-0001). god 전용 의존성 추가도 재합의.
- study-note 읽기·쓰기 금지. main 직접 작업 금지. push·원격 생성은 사용자 확인 후.
- 테스트 통과를 위한 테스트 전용 분기(테스트 감지·특정 id 하드코딩) 금지.

## 5. 검증 방법 (필수)

- `tools/behavior/run.sh god` → exit 0: infra 18·contract 117 통과, verify 76 위반 0 (단계마다 green 수를 log에 기록).
- `tools/behavior/run.sh layered` → 결과 불변(infra 18 통과·contract 117 실패·verify 0 — layered 무변경 확인).
- 루트 `./gradlew build` 통과(god 스모크 포함 — 엔티티 validate가 개발 DB `ccle_god` 스키마와 맞는지).
- 그린 위장 점검: 테스트 건너뜀 0(XML skipped 0), 반복 실행 2회 연속 green(동시성 테스트 간헐 실패 확인), 테스트 전용 분기 grep.
- 갓 클래스 경계 확인: 절차·규칙 코드가 그 파일 밖에 없는지 diff 리뷰.
- 리뷰: stakes 높음 → 듀얼 리뷰 루프(≤3, Opus 워커 ∥ codex) + post-fix 재점검. 블라인드 테스트 = 기존 behavior-tests(구현 미열람 작성).

## 6. stakes (필수)

- 판정: **높음** — 결제·포인트 금액, public API 계약, 동시성(잠금·멱등). 데이터는 실행마다 버리는 컨테이너라 불가역 아님.

---

## 7. 자율성

- [x] auto (기본 권장) — 사용자: "합의, auto"
- [ ] lazy

## 8. load-bearing 가정

1. **블라인드 계약 테스트 117개가 ADR-0003과 맞다** — 한 번도 green이 된 적 없다. 틀린 테스트가 있으면 구현이 테스트를 쫓게 된다. 실증: task 01·02에서 시계·입찰 테스트를 먼저 green으로 만들고, 구현으로 풀 수 없는 실패(ADR과 충돌)는 즉시 보고.
2. **Kotlin JPA 엔티티가 기존 스키마에 `validate`로 붙는다**(text enum·timestamptz·bigserial·부분 UNIQUE). 실증: task 01에서 엔티티 전부 매핑 후 앱 기동 + 스모크.

## 9. task 분해

| task | 목표 | 의존 | acceptance |
|------|------|------|-----------|
| 01 | 기반: 엔티티(1단계 테이블), 시계 빈·`/admin/clock`, 오류 응답 변환, `X-User-Id` 확인, 갓 클래스 뼈대 | — | 앱 기동(validate), 시계 테스트 green, 404 라우트 오류에 `code` |
| 02 | 입찰 등록·즉시·취소·조회, 체결(§3.3 잠금·우선순위·체결가), 호가·체결 조회, 제한 | 01 | BidRegistration·Matching·TradeQueryAndError green |
| 03 | 결제(멱등·결제 가능·항목 순서·금액·PG·UNKNOWN), 포인트(규칙·예약·확정·해제·적립 예정·`/me/points`), 주문 조회 | 02 | Payment·PointRule·IdempotencyAndPayability green |
| 04 | 배치 bid-expiry·payment-due(경고·제한·복구·재매칭)·pg-inquiry | 03 | BatchAndClock·RestrictionAndConcurrency green, verify 0 |
| 05 | 전체 회귀·리뷰 루프·마감 | 04 | §5 전부 |

---

## 승인 상태

- [x] 필수 6칸 전부 기입
- [x] 사용자 합의 → SPEC=1 (2026-10-17)
- [x] 자율성 선택 → MODE=auto
