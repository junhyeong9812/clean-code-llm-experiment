# 요구사항 명세서 — 레이어드 분리 1단계 (layered)

> 작성일: 2026-10-18 · 작업 폴더: `docs/plans/2026-10-18/layered-stage1/` · 브랜치 `feat/layered-stage1`(main `900c454`)

---

## 0. 요구사항 원문 (인터뷰 기록)

- 원문: "다음 진행헤보자" → NEXT N1(레이어드 분리 1단계 — god의 1단계 구현을 `layered`로 분리해 같은 계약 117을 green으로)
- Q/A:
  - 출발점 → **god 코드를 옮기며 분리**(NEXT 방안 A): 동작 차이를 줄여 실험 변수를 구조 하나로 좁힌다
  - 도메인 규칙 위치 → **풍부한 도메인 객체**: 상태 전이·금액 계산·검증은 도메인 객체 메서드, 서비스는 트랜잭션·순서 조율
  - 엔티티 → **JPA 엔티티가 곧 도메인 객체**(domain 패키지 클래스에 JPA 어노테이션)
  - 기술 선택 → **god와 같게**: 잠금 순서(사이즈 → 구매자), 트랜잭션 1 커밋 → PG(트랜잭션 없음) → 트랜잭션 2, REPEATABLE READ 읽기 전용 주문 조회, 키셋 배치(LIMIT 500·시작 시점 max(id)), 기동 시 PAYING → UNKNOWN 전환, 같은 키 진 요청 대기, 멱등 키 유일 제약 위반만 재시도, BigInteger 기반 금액 계산
  - 진행 순서 → **계약 조항 순서**: 시계·관리 → 입찰·체결 → 결제·포인트 → 배치, 단계마다 run.sh layered green 수 기록
  - 모듈 자체 테스트 → **행위 테스트로 충분**(기존 스모크만 유지 — god와 조건 동일)
  - 구현 주체 → **Opus 워커 구현**(단계별 순차), 메인은 브리핑·게이트·리뷰 종합·검증
  - 장애 주입 → **다시 한다**: 결제 중 kill -9 후 재기동, DB 쓰기 실패, 같은 키 동시 요청 30회
  - 의존 방향 → **domain이 저장소 인터페이스 소유**: api → service → domain ← infra
  - 저장소 → **Spring Data JPA + 네이티브 쿼리**: 단순 조회·저장은 JpaRepository, 잠금·호가·배치는 `@Query` 네이티브 또는 EntityManager(infra 안)
  - 규모 비교 → **이번 완료 시 1회**(줄 수·파일 수·클래스 수, 줄 수 채우기 금지)

---

## 1. 목표·대상 (필수)

`layered` 모듈에 ADR-0003(1단계)을 god 코드를 출발점으로 계층 분리해 구현하고, `tools/behavior/run.sh layered`가 **infraTest 18 + contractTest 117 전부 통과, verify.sql 76개 위반 0, exit 0**으로 끝나면 완료.

## 2. 경계·불변식 (필수)

- **계층(ADR-0001):** 패키지 `com.ccle.layered.{api, service, domain, infra}`.
  - `api`: 컨트롤러·요청/응답 DTO·JSON 파싱·오류 응답 변환. 파싱·위임·직렬화만 하고 규칙을 갖지 않는다. `service`만 부른다(`domain` 타입은 응답 변환에 읽기만 허용, `infra` 의존 금지).
  - `service`: 유스케이스별 클래스. 트랜잭션 경계·잠금 순서·호출 순서 조율. 규칙은 도메인 객체에 위임.
  - `domain`: 엔티티(=도메인 객체)·값 계산·상태 전이·저장소/PG/시계 인터페이스. `org.springframework.*`·`infra` 의존 금지(`jakarta.persistence` 어노테이션만 예외).
  - `infra`: 저장소 구현(Spring Data JPA 인터페이스·네이티브 쿼리·EntityManager), PG HTTP 클라이언트, 시계 구현, 기동 복구 훅 같은 프레임워크 접착.
- **계약(ADR-0003):** 응답 모양·HTTP 상태·`code`·판정 순서(401→400→멱등→404→403→409→422), 어떤 요청도 5xx 없음, 같은 요청 순서 → 같은 DB 결과. 재합의 1·2(§9의 6·7)와 §10 전제 포함.
- **god와 같은 동작:** §0의 기술 선택을 그대로 따른다. 경합·장애 경로(동시 요청·크래시·PG 지연)에서도 god와 같은 결과가 나와야 한다. 쓰기 순서가 바뀌어 `bid_events`·`point_transactions` 등의 id 순서가 달라지는 것도 차이로 본다(verify가 id 순서를 쓴다).
- **동시성(§3.3):** 사이즈 입찰 상태 변경은 `product_sizes FOR UPDATE` 트랜잭션 안에서, `bid_events`는 잠근 뒤 쓴다. 같은 멱등 키 동시 요청 → 주문 1건.
- **결제(§5.1):** 주문 행은 PG 호출 전 커밋. PG 응답 없음 → UNKNOWN(5xx 아님). `pg_transactions`는 결과 확정 뒤 1행.
- **금액·시각·스키마:** ADR-0002 §5·§9.2·ADR-0003 §8 표 그대로. 주입된 시계만 사용(`now()`·`Instant.now()`·DB `now()` 금지, 요청 하나의 시각은 같은 값). `ddl-auto: validate`, 스키마 무변경.
- **동작 불변식 신설 여부:** 동작 불변식은 전부 ADR-0002/0003에 있고 `verify.sql`·behavior-tests가 강제한다. 계층 규칙은 범위 제약(구조)이다 → 설계 선검증 대상 아님(log 1행 기록).

## 3. 기준소스 (필수)

- 정본 순서: ADR-0002(§9 우선) → `tools/seedgen/verify.sql` → ADR-0003 → `behavior-tests/`.
- 출발 코드: `god/src/main/kotlin/com/ccle/god/**`(main `900c454`, 계약 117 green). god와 ADR이 어긋나 보이면 ADR이 정본이고, 멈추고 보고한다.
- 테스트 데이터: 시드 이미지 `ccle-postgres-seed:1k-s42`(run.sh가 띄움). 스키마: `docker/schema/`.

## 4. 금지영역 (필수)

- `god/` 전부, `behavior-tests/`, `tools/`, `docker/`, ADR-0001~0003 본문 — **수정 금지**. 어긋남이 보이면 구현으로 우회하지 않고 멈추고 보고 → 재합의.
- 루트 `build.gradle.kts`·`settings.gradle.kts` 변경 금지(두 앱 같은 의존성 — ADR-0001). layered 전용 의존성 추가도 재합의.
- study-note 읽기·쓰기 금지. main 직접 작업 금지. push·원격 생성은 사용자 확인 후.
- 테스트 전용 분기(테스트 감지·특정 id 하드코딩) 금지. 줄 수 채우기 금지.

## 5. 검증 방법 (필수)

- `tools/behavior/run.sh layered` → exit 0: infra 18·contract 117 통과, verify 76 위반 0(단계마다 green 수 log 기록).
- `tools/behavior/run.sh god` → 결과 불변(117 green, verify 0 — god 무변경 확인). 금지영역 diff 0.
- 루트 `./gradlew build` 통과(layered 스모크 — 엔티티 validate가 `ccle_layered` 스키마와 맞는지).
- 그린 위장 점검: skipped 0, 2회 연속 green, 테스트 전용 분기 grep.
- 계층 경계 확인: import grep(domain에 `org.springframework`·`infra` 없음, api에 `infra` 없음) + diff 리뷰(규칙이 service·api에 남아 있지 않은지).
- 장애 주입(일회용 컨테이너): 결제 중 kill -9 → 재기동 후 PAYING → UNKNOWN 전환, DB 쓰기 실패 주입 시 재기록, 같은 키 동시 요청 30회 → 주문 1건·진 요청이 결과 수신. god와 같은 결과인지 비교.
- 리뷰: stakes 높음 → 듀얼 리뷰 루프(≤3, Opus 워커 ∥ codex) + post-fix 재점검. 렌즈에 "god와 동작 동치"를 포함. 블라인드 테스트 = 기존 behavior-tests.
- 완료 시 god·layered 규모 비교(줄 수·파일 수·클래스 수) 1회.

## 6. stakes (필수)

- 판정: **높음** — 결제·포인트 금액, public API 계약, 동시성(잠금·멱등). 데이터는 실행마다 버리는 컨테이너라 불가역 아님.

---

## 7. 자율성

- [x] auto (기본 권장) — 사용자: "합의, auto"
- [ ] lazy

## 8. load-bearing 가정

1. **god의 절차를 도메인 메서드·저장소로 옮겨도 쓰기 순서와 잠금 시점이 유지된다.** JPA 쓰기 지연·Spring Data `save`(merge)·flush 시점 때문에 INSERT/UPDATE 순서나 잠금 획득 시점이 god와 달라질 수 있다. 실증: task 02에서 입찰·체결을 옮긴 직후 run.sh layered verify 0 + god와 같은 요청 순서에서 `bid_events` id 순서 비교.
2. **domain이 Spring을 모르면서 트랜잭션·잠금 순서가 service에서 다 표현된다.** 네이티브 잠금 쿼리를 저장소 인터페이스 뒤로 숨겨도 잠금 순서(사이즈 → 구매자)가 service 호출 순서로 강제된다. 실증: task 01 뼈대에서 계층 import 규칙 grep, task 03에서 결제 동시 요청 스모크.

## 9. task 분해

| task | 목표 | 의존 | acceptance |
|------|------|------|-----------|
| 01 | 기반: 계층 패키지, 엔티티(=도메인, god와 같은 테이블), 시계(domain 인터페이스 + infra 구현)·`/admin/clock`, 오류 응답 변환, `X-User-Id` 확인 | — | 앱 기동(validate), 시계 테스트 green, 404 라우트 오류에 `code`, import 규칙 grep 통과 |
| 02 | 입찰 등록·즉시·취소·조회, 체결, 호가·체결 조회, 제한 | 01 | BidRegistration·Matching·TradeQueryAndError green, verify 0, 가정 1 실증 |
| 03 | 결제·포인트·주문 조회(멱등·같은 키 대기·UNKNOWN·REPEATABLE READ) | 02 | Payment·PointRule·IdempotencyAndPayability green |
| 04 | 배치 bid-expiry·payment-due·pg-inquiry, 기동 복구 | 03 | BatchAndClock·RestrictionAndConcurrency green, verify 0 |
| 05 | 전체 회귀·장애 주입·리뷰 루프·규모 비교·마감 | 04 | §5 전부 |

---

## 승인 상태

- [x] 필수 6칸 전부 기입
- [x] 사용자 합의 → SPEC=1 (2026-10-18)
- [x] 자율성 선택 → MODE=auto
