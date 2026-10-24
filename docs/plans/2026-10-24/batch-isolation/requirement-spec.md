# 요구사항 명세서 — 계약 변경 2건 구현 (오류 문구 고정 · 배치 건별 격리)

> 작성일: 2026-10-24 · 작업 폴더: `docs/plans/2026-10-24/batch-isolation/` · 브랜치 `feat/batch-isolation`(main `4276972`)

---

## 0. 요구사항 원문 (인터뷰 기록)

- 원문: "계속 진행해줘" → NEXT N1(ADR-0003 §12의 1·2, ADR-0004 §15의 1 — **[변경 — 구현 전]** 2건 구현)
- Q/A:
  - 실패 주입(행동 테스트) → **임시 DB 트리거**: 테스트가 일회용 DB에 특정 행 id에서만 예외를 내는 트리거를 만들고 끝나면 지운다(스키마 파일은 무변경)
  - 배치 응답 → **`failed` 필드 추가**: `200 {"processed": n, "failed": m}`
  - `weekly-settlement`가 멈출 때 → **오류 응답 유지**(400 `UNEXPECTED_ERROR`, 고정 문구)
  - 고정 문구 → **"예상하지 못한 서버 오류"**
  - equiv → **격리 시나리오 1개 추가** + 기존 11개 재실행
  - stakes → **높음** · 진행 → **auto** · 병합 → **ff 병합 후 main·브랜치 푸시**(승인 창)

## 1. 목표·대상 (필수)

god·layered가 ADR-0003 §12의 1·2와 ADR-0004 §15의 1을 똑같이 구현하고, ADR의 "구현 전" 표시를 확정 문장으로 바꾼다.

- **오류 문구:** 계약 밖 서버 오류(두 앱 `ErrorHandling.kt`의 unexpected 경로 전부 — 처리 못 한 예외, `/error` 대체, Spring 5xx 변환)는 `400 {"code": "UNEXPECTED_ERROR", "message": "예상하지 못한 서버 오류"}`. 원인(요청 메서드·URI·예외)은 서버 ERROR 로그에만.
- **배치 격리:** 대상 1건의 처리가 예외로 끝나면 그 대상만 롤백하고 ERROR 로그(배치 이름·대상 id·예외)를 남긴 뒤 다음 대상을 계속한다. 적용: `bid-expiry`·`payment-due`·`pg-inquiry`·`shipment-deadline`·`auto-confirm`(단독)·`point-expiry`·`grade-recalc`.
- **응답:** `POST /admin/batches/{name}` → `200 {"processed": n, "failed": m}`. `failed` = 그 실행에서 커밋되지 못한 대상 수 = 예외로 롤백된 대상 + 카드 취소 실패(PG_UNAVAILABLE)로 건너뛴 대상. `processed` 의미는 그대로(상태가 바뀐 주 대상 수).
- **격리하지 않는 곳(현행 유지):** `weekly-settlement`(앞 `auto-confirm`이 1건이라도 실패하면 정산하지 않고, 한 주 정산이 실패하면 뒤 주를 정산하지 않는다 — 둘 다 400 `UNEXPECTED_ERROR`로 끝남. 앞 `auto-confirm`의 성공분은 커밋된 채), 기동 시 전환(예외 = 기동 실패).
- ADR-0003 §7 배치 응답 형식·§12의 1·2, ADR-0004 §5·§15의 1을 위 내용으로 갱신.
- 행동 테스트 추가(별도 워커가 ADR만 보고 작성) + equiv 시나리오 `iso` 추가.

## 2. 경계·불변식 (필수)

- **I1 (격리):** 격리 배치에서 실패한 대상의 DB 행은 실행 전과 같다(부분 반영 없음). 같은 실행의 다른 대상은 실패한 대상이 처리되지 않은 상태를 기준으로 정상 처리된다(대상끼리 영향이 있는 배치 — 경고 수·패널티 수·마지막 환불 — 가 있으므로 "실패 없는 실행과 같다"가 아니다. 새 행 id는 롤백된 INSERT도 IDENTITY를 전진시키므로 비교 대상 아님). *(설계 선검증 F1로 보정 18:40)*
- **I2 (재실행):** 실패한 대상은 다음 실행의 대상으로 남고, 원인이 없어지면 처리된다.
- **I3 (정산):** `weekly-settlement`는 실패를 넘어 마감을 전진시키지 않는다(§5:104 소급 확정 불변식·§10.1 마지막 마감).
- **I4 (가시성):** 실패는 응답(`failed` 또는 400)과 서버 로그 양쪽에 드러난다 — 예외 롤백·계약 밖 오류는 ERROR, 카드 취소 실패 건너뜀은 WARN(ADR-0004 §7.2 현행, ADR-0003 §12의 2). 200 + `failed: 0`이면 실패가 없었다. *(리뷰 루프 1 open question으로 ADR 문구에 맞춤)*
- **I5 (동치):** 두 앱은 같은 입력에 같은 응답·DB·SQL을 낸다(equiv 12개 같음). 성공 경로의 SQL은 변경 전과 같다(기존 11개 시나리오 판정 불변).
- 정상 경로 동작 무변경: 기존 행동 테스트(1·2단계) 전부 green, verify.sql 위반 0.

## 3. 기준소스 (필수)

- 계약: `docs/adr/0003-http-api-stage1.md` §2·§7·§9의 7·§12, `docs/adr/0004-http-api-stage2.md` §5·§7.2·§9.2·§10.1·§15.
- 현재 코드: god `Marketplace.kt`(`runBatch` :1268, `forEachTarget` :1292, 배치 함수 :1311-1600, `convertStalePayingOrders` :1709), `web/ErrorHandling.kt`, 배치 컨트롤러 / layered `service/BatchService.kt`, `infra/KeysetBatchTargets.kt`, `domain/BatchTargets`(포트), `service/StalePaymentRecovery.kt`, `api/ErrorHandling.kt`, `api/AdminController.kt:42`.
- 테스트·도구: `behavior-tests/`(contract·contract2, support/Db·Flow·Stage2), `tools/equiv/`(lib.sh·run.sh·README·scenarios).

## 4. 금지영역 (필수)

- `docker/`(스키마), `tools/seedgen/`, 가짜 PG(`behavior-tests/src/main/kotlin/com/ccle/behavior/pg/`), 루트 gradle 파일, ADR-0001·0002 — 수정 금지.
- 기존 행동 테스트의 단언 약화 금지(응답 형식 변경에 맞춘 최소 수정만, 사유 log 기록). 기존 equiv 시나리오·정규화 규칙 변경 금지.
- layered 계층 규칙(service → domain 포트 ← infra) 유지 — 격리 정책은 service, 키셋 순회는 infra.
- study-note 쓰기 금지. main 직접 작업 금지. push는 사용자 확인 후.

## 5. 검증 방법 (필수)

- **설계 선검증:** 동작 불변식 I1~I4는 코드베이스에 없던 것 → 구현 전 설계(공유 지점·트랜잭션 경계·`failed` 집계 위치)를 Opus 워커로 검증.
- **blind 테스트 워커:** 구현 diff 미열람, ADR(갱신본) + 기존 테스트 support만 보고 작성. 대상: 배치마다 트리거로 1건 실패 → 그 대상 불변 + 다음 대상 처리 + `failed ≥ 1` + 트리거 제거 뒤 재실행 처리(I1·I2·I4), weekly-settlement 앞 auto-confirm 실패 → 400 + 마감 미전진(I3), 계약 밖 오류 → 400 고정 문구(예외 문구 미포함).
- 두 앱에 행동 테스트 전체(1·2단계) 실행 + verify.sql. 스모크: 실행 중인 앱에 트리거 주입 → 배치 응답·로그 직접 확인.
- equiv `run.sh all` 2회(12개 같음) + `iso` 단독 1회 + 변이 1건(layered 격리를 끈 jar → `iso` exit 1).
- 리뷰: 듀얼 리뷰 루프(≤3, Opus ∥ codex → 종합 → 감사). 금지영역 diff 0.

## 6. stakes (필수)

- 판정: **높음** — 계약 표면(응답 형식) 변경 + 부분 실패 처리(조용한 실패 위험). 데이터 불가역 없음(일회용 DB).

---

## 자율성

- **auto** (사용자 선택)

## load-bearing 가정

1. 두 앱 모두 대상 1건 = `tx.execute` 하나이고 키셋 순회가 한 곳(god `forEachTarget`, layered `KeysetBatchTargets.forEach`)이라, 격리는 순회 지점의 예외 처리로 끝난다(롤백된 트랜잭션 뒤 같은 EntityManager로 계속 진행 가능). — 착수 직후 트리거 스모크로 실증.
2. 임시 트리거(`BEFORE UPDATE … WHEN (OLD.id = X) RAISE`)가 Hibernate flush 또는 커밋에서 예외로 올라오고, 실행 중인 앱의 커넥션에도 즉시 적용된다. — 같은 스모크로 실증.
