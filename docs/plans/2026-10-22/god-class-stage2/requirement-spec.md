# 요구사항 명세서 — 갓 클래스 구현 2단계 (god)

> 작성일: 2026-10-22 · 작업 폴더: `docs/plans/2026-10-22/god-class-stage2/` · 브랜치 `feat/god-class-stage2`(main `f7dde74`)

---

## 0. 요구사항 원문 (인터뷰 기록)

- 원문: "다음 진행해줘" → NEXT N1(갓 클래스 2단계 — ADR-0004를 god에 구현해 contract2 82개를 green으로)
- Q/A:
  - 구조·주체 → **1단계와 동일**: 절차·규칙은 `Marketplace.kt` 한 클래스·한 파일에 추가, 엔티티·컨트롤러·DTO는 밖, 2단계 테이블 엔티티만 추가, Opus 워커가 task별 순차 구현, 메인은 브리핑·검증·리뷰 종합. 줄 수 채우기 금지(자연 크기).
  - 순서 → **ADR-0004 조항 순서**, 첫 task는 §8.1 적립률(2026-10-20 결정). 단계마다 contract2 green 수 기록.
  - 테스트 결함 → **멈추고 보고 → 재합의**. 구현으로 우회하지 않는다. 승인되면 테스트(또는 ADR)를 별도 커밋으로 고친다.
  - 미검증 경로 3개 → **수동 스모크로 확인·기록**(재기동·DB 조작·가짜 PG 제어). 저장소 테스트는 추가하지 않는다. 재현할 수 없는 것은 코드 리뷰로 판정하고 한계로 기록한다.
  - equiv → **실행해 차이만 기록**: 마감 때 `tools/equiv/run.sh all`을 실행한다. 차이가 §8.1·2단계 변경으로 설명되면 예상 차이로 기록하고, 설명되지 않으면 결함으로 처리한다. 하네스는 수정하지 않는다.
  - stakes → **높음, 루프 ≤3**

---

## 1. 목표·대상 (필수)

`god` 모듈에 ADR-0004(2단계)와 ADR-0003 §9의 8(적립률 등급), ADR-0002 §9.7을 구현한다. 완료 조건: `tools/behavior/run.sh god contract2Test`에서 **contract2 82개 전부 통과**, `tools/behavior/run.sh god`(1단계)도 **infra·contract 117 통과, verify 위반 0, exit 0**.

## 2. 경계·불변식 (필수)

- **구조(ADR-0001·ADR-0002 §8, 1단계와 같음):** 절차·규칙·저장은 `Marketplace.kt` 클래스 1개에 둔다. 컨트롤러·엔티티·DTO·오류 변환은 밖에 둔다(컨트롤러는 파싱·위임·직렬화만). 시계·PG HTTP 호출은 얇은 빈으로 둘 수 있으나 판단은 갓 클래스 안에 둔다.
- **계약(ADR-0004):** 엔드포인트·응답 모양·상태·`code`·판정 순서, 5xx 없음, §12 금액 예시.
- **동시성(§9.1):** 잠금 순서는 주문 → 항목 → 사용자(id 순). 같은 항목 경합은 한 쪽만 성공한다.
- **카드 취소(§7.2):** 키는 `cancel-<orderItemId>` 하나. PG 실패·비승인·금액 불일치 → 409 `PG_UNAVAILABLE`, DB 무변경.
- **정산(§10):** 한 주 = 한 트랜잭션, 미정산 주 전부 순서대로, `paid_at`은 수요일 00:00 KST, 정산 전 auto-confirm 선행.
- **시각·스키마:** 주입된 시계만 사용하고, 요청 하나의 시각은 같다. `ddl-auto: validate`, 스키마 무변경.
- **1단계 동작 유지:** 1단계 테스트 117·verify 0 유지. 단, §8.1이 1단계 적립률을 바꾸는 것은 계약 개정(ADR-0003 §9의 8)대로다.
- **설계 선검증:** §9.1 잠금 순서와 §10 주 단위 트랜잭션은 테스트가 직접 강제하지 않는 동작 불변식이다. 해당 task 착수 전에 워커가 잠금·트랜잭션 경계 계획을 적고, 메인이 ADR과 대조한 뒤 구현한다.

## 3. 기준소스 (필수)

- 정본 순서: ADR-0002(§9 우선) → `tools/seedgen/verify.sql` → ADR-0003·ADR-0004(§12 금액 예시가 기대값 정본) → `behavior-tests/`(contract·contract2, 블라인드 작성).
- 테스트 데이터: 시드 `ccle-postgres-seed:1k-s42`(run.sh가 띄움). 스키마: `docker/schema/`.

## 4. 금지영역 (필수)

- `layered/`, `behavior-tests/`, `tools/`, `docker/schema/`, ADR 본문은 **수정 금지**. 어긋나 보이면 멈추고 보고 → 재합의.
- **재합의 1 (2026-10-22, 리뷰 루프 1):** ADR-0004 §6·§7.2에 계약 밖 2건 추가 — 체결가 < 10원의 판매자 귀책(0원 패널티), PG 기록 후 무응답 사이 같은 주문 다른 항목 환불로 금액이 바뀌는 경우. 사용자: "계약 밖으로 기록", "기존 '계약 밖'에 포함". 코드 변경 없음. 그 외 ADR 수정 금지는 유지.
- 루트 `build.gradle.kts` 공통 의존성 변경 금지. god 전용 의존성 추가도 재합의.
- 테스트 전용 분기(테스트 감지·id 하드코딩) 금지.
- study-note 읽기·쓰기 금지. main 직접 작업 금지. push·원격 생성은 사용자 확인 후. 코드와 docs 커밋 분리.

## 5. 검증 방법 (필수)

- `tools/behavior/run.sh god contract2Test` → 82/82 통과. task마다 green 수를 log에 기록.
- `tools/behavior/run.sh god` → exit 0(infra·contract 117, verify 0).
- `tools/behavior/run.sh layered` → 1단계 결과 불변(layered 무변경 확인).
- 루트 `./gradlew build` 통과.
- 그린 위장 점검: skipped 0, contract2 두 번 연속 green, 테스트 전용 분기 grep.
- 수동 스모크(log에 명령·결과 기록): ①기동 전환 경로의 적립률(주문 PAYING 상태로 재기동 → pg-inquiry 승인 → 적립액이 주문 생성일 등급인지) ②정산 행 0건 분기(DB에서 settlements 비운 상태로 weekly-settlement) ③카드 취소 무응답은 재현 불가 → 코드 리뷰로 판정, 한계 기록.
- equiv: `tools/equiv/run.sh all` 실행, 차이를 예상/결함으로 분류해 기록.
- 갓 클래스 경계: 절차·규칙이 파일 밖에 없는지 diff 리뷰.
- 리뷰: 듀얼 리뷰 루프(≤3, Opus 워커 ∥ codex) + 수정 후 재점검. 블라인드 테스트 = 기존 contract2(구현 미열람 작성).

## 6. stakes (필수)

- **높음** — 결제 취소·환불·정산 금액, public API 계약, 동시성(잠금 순서·주 단위 트랜잭션). 데이터는 실행마다 버리는 컨테이너라 불가역 아님.

---

## 7. 자율성

- [x] auto (기본 권장) — 사용자: "합의, auto"
- [ ] lazy

## 8. load-bearing 가정

1. **contract2 82개가 ADR-0004와 맞다** — 구현 없이 red로만 확인했다. 실증: task 01(§8.1)에서 PointGrade 3개를 먼저 green으로 만들고, 이후 task마다 ADR로 설명되지 않는 실패는 즉시 보고한다.
2. **시계를 크게 옮긴 뒤의 weekly-settlement가 배치 제한 시간(120초) 안에 끝난다** — 수십 주 × 전 판매자. 실증: 정산 task에서 SettlementTest·GradeRecalc 뒤 정산 소요 시간을 측정한다. 넘으면 멈추고 보고(run.sh 제한은 금지영역).

## 9. task 분해

| task | 목표 | 의존 | acceptance |
|------|------|------|-----------|
| 01 | §8.1 적립률(주문 생성일 등급 이력, 세 승인 경로) | — | PointGrade 적립률 3개 green, 1단계 117 유지 |
| 02 | 2단계 엔티티, §2 공통(오류 코드·항목 응답·공통 순서), `GET /order-items/{id}`, §3 발송·입고·검수·배송 | 01 | ItemApi·Fulfillment green |
| 03 | §4 확정·취소·분쟁·판정, §6 판매자 귀책·패널티, §7 환불·카드 취소·포인트 복원, §8.2·§8.4 | 02 | BuyerAction·Refund·Penalty·SerialRace green |
| 04 | §5 배치(shipment-deadline·auto-confirm·point-expiry·grade-recalc), §8.3·§8.5 | 03 | Batch·GradeRecalc·PointGrade green |
| 05 | §10 주간 정산·`GET /me/settlements` | 04 | Settlement green, 정산 소요 시간 기록 |
| 06 | 전체 회귀·수동 스모크·equiv·리뷰 루프·마감 | 05 | §5 전부 |

---

## 승인 상태

- [x] 필수 6칸 전부 기입
- [x] 사용자 합의 → SPEC=1 (2026-10-22)
- [x] 자율성 선택 → MODE=auto
