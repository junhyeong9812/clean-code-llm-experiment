# 요구사항 명세서 — 레이어드 분리 2단계 (layered)

> 작성일: 2026-10-22 · 작업 폴더: `docs/plans/2026-10-22/layered-stage2/` · 브랜치 `feat/layered-stage2`(main `a0228d8`)

---

## 0. 요구사항 원문 (인터뷰 기록)

- 원문: "지금 하던 프로젝트 이어서 진행하자" → NEXT N1(레이어드 2단계 — god 2단계를 layered로 분리해 contract2 82를 green으로)
- Q/A:
  - 구조·기술 → **1단계와 동일**(`docs/plans/2026-10-18/layered-stage1/requirement-spec.md` §0·§2 그대로): god 코드를 옮기며 분리, 풍부한 도메인 객체(= JPA 엔티티), api → service → domain ← infra, domain이 저장소 인터페이스 소유, 저장소 구현은 infra의 EntityManager(JPQL·네이티브), 트랜잭션 밖 단건 조회는 `em.find`. 잠금 순서·트랜잭션 경계·PG 호출 위치는 god 2단계와 같게. Opus 워커가 task별 순차 구현, 잠금·정산 task는 계획 먼저.
  - 동치 기준 → **SQL 순서·id까지 god와 같게**(1단계 기준과 같음). 예: god의 §8.1 등급 네이티브 쿼리 auto-flush로 `update orders`가 앞당겨지는 순서까지 맞춘다.
  - equiv 2단계 시나리오 → **별도 작업**(이번엔 기존 7개 시나리오만, NEXT에 등록).
  - 장애 확인 → **god 2단계 스모크 2건 재사용**(기동 전환 적립률·정산 0건 분기를 layered에서 실행해 god와 같은 결과). 1단계 장애 주입은 equiv recov·fail·conc가 덮는다.
  - stakes·리뷰·멈춤 → **god 2단계와 동일**(높음, 듀얼 리뷰 루프 ≤3, 테스트·ADR이 어긋나면 멈추고 보고 → 재합의).

---

## 1. 목표·대상 (필수)

`layered` 모듈에 god 2단계(ADR-0004 + ADR-0003 §9의 8 + ADR-0002 §9.7, 계약 밖 개정 포함)를 계층 분리해 구현한다. 완료 조건: `tools/behavior/run.sh layered contract2Test` **82/82**, `tools/behavior/run.sh layered` 1단계 **exit 0(infra·contract 117·verify 0)**, `tools/equiv/run.sh all` **exit 0(7개 시나리오 4계층 전부 같음)**.

## 2. 경계·불변식 (필수)

- **계층(ADR-0001, 1단계 §2 그대로):** `api`(파싱·위임·직렬화만, service만 호출), `service`(유스케이스별 클래스 — 트랜잭션 경계·잠금 순서·호출 순서 조율), `domain`(엔티티=도메인 객체·값 계산·상태 전이·저장소/PG/시계 인터페이스, `org.springframework.*`·`infra` 의존 금지, `jakarta.persistence`만 예외), `infra`(저장소 구현·PG HTTP·시계). 앱 루트는 조립·기동 훅만.
- **규칙 위치 기준(착수 전 적음 — 1단계 리뷰 루프 원인):** "변경 여부·값을 정하는 것(상태 전이 허용, 금액·환불·패널티·정산 금액, 등급, 기한 판정)은 domain, 무엇을 어떤 순서로 조회·잠금·저장하는지와 외부 호출 순서는 service."
- **god와 같은 동작:** 응답·DB 결과·Hibernate SQL·pg 문장 순서·id 순서까지(1단계 재합의 2의 허용 차이 — 도메인 상태 전이 단언 `check` — 는 유지, 정합한 데이터에서는 같아야 함). 잠금 순서 주문 → 항목(FOR UPDATE) → [PG] → 사용자 id 오름차순, 정산은 한 주 = 트랜잭션 1개·대상 항목 id 순 잠금 뒤 재평가, 카드 취소 키 `cancel-<orderItemId>`·금액 확인·실패 시 409 `PG_UNAVAILABLE` DB 무변경.
- **재합의 1 (2026-10-22, 리뷰 루프 2):** 규칙 위치 기준과 SQL 동치가 충돌하는 곳은 SQL 동치를 우선한다 — 조회 조건과 SQL 안 계산(§8.1 등급 선택, 등급 경계, 정산 대상 조건, 판매 수수료 floor)은 god와 같은 SQL 문장으로 infra에 둔다(domain은 그 결과로 판정). 실험 분석 때 구조 차이로 다룬다. 고정 환불 사유 문구는 domain으로 옮긴다. 사용자: "SQL 동치 우선, 예외로 기록".
- **계약:** ADR-0004(§6·§7.2 계약 밖 2건 포함), 5xx 없음, 판정 순서.
- **시각·스키마:** 주입 시계만, 요청 하나 = 한 시각. `ddl-auto: validate`, 스키마 무변경.
- **설계 선검증:** 동작 불변식(잠금 순서·주 단위 트랜잭션)은 god 2단계에서 선검증·구현됐고 god 코드가 기준이다. layered는 같은 순서를 계층에 나눠 놓는 것이라 신설 불변식은 없다 — 다만 잠금·정산 task는 착수 전 계획(어느 계층에서 무엇을 어떤 순서로)을 메인이 god 코드와 대조한다(log 1행).

## 3. 기준소스 (필수)

- 동작 기준: god 2단계 코드(`god/src/main/kotlin/com/ccle/god/`, main `a0228d8`) → 정본 순서 ADR-0002 → `tools/seedgen/verify.sql` → ADR-0003·ADR-0004 → `behavior-tests/`(contract·contract2). god와 ADR이 어긋나 보이면 멈추고 보고.
- 테스트 데이터: 시드 `ccle-postgres-seed:1k-s42`. 스키마 `docker/schema/`.

## 4. 금지영역 (필수)

- `god/`, `behavior-tests/`, `tools/`(equiv 포함), `docker/schema/`, ADR 본문 **수정 금지**. 어긋나 보이면 멈추고 보고 → 재합의.
- 루트 `build.gradle.kts` 공통 의존성 변경 금지, layered 전용 의존성 추가도 재합의. 테스트 전용 분기 금지.
- study-note 쓰기 금지(이슈 아카이브 보류 유지). main 직접 작업 금지. push·원격 생성은 사용자 확인 후. 코드와 docs 커밋 분리.

## 5. 검증 방법 (필수)

- `tools/behavior/run.sh layered contract2Test` → 82/82, task마다 green 수 기록. 두 번 연속 green, skip 0.
- `tools/behavior/run.sh layered` → exit 0(infra·contract 117·verify 0).
- `tools/behavior/run.sh god` + `god contract2Test` → god 결과 불변(god 무변경 확인).
- `tools/equiv/run.sh all` → exit 0(7개 시나리오 전부 같음).
- 수동 스모크(god 2단계 스크립트 재사용, layered에서 실행): ①기동 전환 적립률 = 주문일 이력 등급 ②정산 0건 분기 [A]·[B]·[C] — god 결과와 같은 수치.
- `./gradlew build` 통과. 계층 의존 방향 grep(domain의 `org.springframework`·`infra` import 0, api의 infra import 0).
- 리뷰: 듀얼 리뷰 루프(≤3, Opus 워커 ∥ codex) + 감사 + 수정 후 재점검. 블라인드 테스트 = 기존 contract2.

## 6. stakes (필수)

- **높음** — 결제 취소·환불·정산 금액, public API 계약, 동시성(잠금 순서·주 단위 트랜잭션). 데이터는 실행마다 버리는 컨테이너라 불가역 아님.

---

## 7. 자율성

- [x] auto (기본 권장) — 사용자: "합의, auto"
- [ ] lazy

## 8. load-bearing 가정

1. **god 2단계 코드가 계층으로 나눠도 같은 SQL 순서를 낼 수 있다** — 특히 §8.1 네이티브 쿼리의 auto-flush 시점, 정산의 잠금 → 재평가 문장 순서. 실증: task 01(§8.1)을 끝내자마자 equiv `all`을 돌려 1단계 7개 시나리오가 다시 전부 같은지 확인한다.
2. **도메인 객체로 옮긴 규칙이 god와 같은 순서로 엔티티를 바꾼다**(Hibernate flush 순서 = 엔티티 변경 순서) — 실증: task 03 뒤 contract2와 equiv를 함께 확인.

## 9. task 분해

| task | 목표 | 의존 | acceptance |
|------|------|------|-----------|
| 01 | §8.1 적립률(주문 생성일 등급 이력) | — | contract2 적립률 3개 green, **equiv all exit 0** |
| 02 | 2단계 엔티티, §2 공통, `GET /order-items/{id}`, §3 발송·입고·검수 PASS·배송 | 01 | contract2 god task 02와 같은 8개 green |
| 03 | 계획 먼저 → §4 확정·취소·분쟁·판정, §6, §7, §8.2·§8.4 | 02 | contract2 49 green, equiv 유지 |
| 04 | §5 배치 4종, §8.3·§8.5 | 03 | contract2 69 green |
| 05 | 계획 먼저 → §10 주간 정산·`GET /me/settlements` | 04 | contract2 82 green |
| 06 | 전체 회귀·스모크·equiv·리뷰 루프·마감 | 05 | §5 전부 |

---

## 승인 상태

- [x] 필수 6칸 전부 기입
- [x] 사용자 합의 → SPEC=1 (2026-10-22)
- [x] 자율성 선택 → MODE=auto
