# log — behavior-tests-stage1

## 타임라인

| 시각 | 사건 | 결과 |
|---|---|---|
| 2026-10-15 | 인터뷰 1차: 범위 단계적, 1천 명 시드 + 직접 INSERT, Clock 빈 + 관리 엔드포인트, 공통 테스트 모듈 | |
| 2026-10-15 | ADR에 HTTP API 정의 없음 확인(ADR-0001·0002 grep) → 인터뷰 2차: ADR-0003 초안 → 합의, 가짜 PG, X-User-Id, HTTP + DB 판정 | |
| 2026-10-15 | 브랜치 `docs/adr-0003-api`(main `6be30b8`). ADR-0003 초안, 명세 초안 작성 | SPEC 리셋(새 작업 폴더) |
| 2026-10-15 | 설계 문서 리뷰(설계 선검증 + 듀얼 1패스): packet base `6be30b8`, OUT=`scratchpad/rv.bxiW`, 미러 `mirror.neEu`. 보안 스캔: 개발 기본 비밀번호 `ccle` 2건(공개 기본값, 오탐) | Opus 18건 + OQ4, codex 10건 + OQ3 |
| 2026-10-15 | 종합: 근거를 verify.sql·seedgen.py로 대조(예약 배분 시점, MATCHED→EXPIRED, 경고 재계수, PG 행 형식). 전부 채택 → ADR-0003 재작성, 금액 예시는 스크립트 계산 | A1~A23 |
| 2026-10-15 | codex 종합 감사 + post-fix 재점검(1회): 3건 | A24~A26 반영. 설계 문서 리뷰 상한(듀얼 1패스 + 재점검 1회) 도달 |
| 2026-10-15 | 사용자: ADR-0003 "채택", 명세 "합의, auto" | ADR-0003 채택, ADR-0002 §9.6 개정 2곳 |

## 리뷰 ledger

| id | source | 근거 | 내용 | disposition | status |
|---|---|---|---|---|---|
| A1 | opus F1 | ADR-0003 §3.3 | 체결가 두 갈래 정의(복구 입찰) | 채택 | fixed: (created_at,id) 앞선 입찰 가격, 복구 입찰 created_at 유지 |
| A2 | opus F2 · codex F8 | §7 payment-due | UNKNOWN 주문 체결 만료 규칙 모순, 기한 뒤 재조회 결과 | 채택 | fixed: UNKNOWN 있으면 만료 안 함, 재조회 승인 paid_at = APPROVE.requested_at, 거절은 다음 payment-due |
| A3 | opus F3 · codex F3 | §5.1 pg_transactions | append-only 충돌, 타임아웃·404 상태 미정 | 채택 | fixed: 결과 뒤 1행 INSERT, UNKNOWN 열 값, 404 = DECLINED |
| A4 | opus F4·F5 · codex F7 | §5.2 | 예약 잔액 반영·FIFO 동률·만료 묶음 | 채택 | fixed: 예약 시 배분·remaining 차감(verify.sql:376-389 해석), (expires_at,id), expires_at > 현재 |
| A5 | opus F6 · codex F6 | §5.1 | 주문 항목 순서 | 채택 | fixed: tradeId 오름차순 |
| A6 | opus F7 · codex F2·OQ2 | §5.1 멱등 | 같은 본문 정의·판정 순서·타 사용자·동시 | 채택 | fixed: 구매자+집합+포인트, 401→400 다음 조회, 현재 상태 200(ADR-0002 §4.3 "첫 결과" 해석은 §9-4) |
| A7 | opus F8 · codex OQ3 | §7, spec §2 | 전역 시계·배치 → 테스트 독립 | 채택 | fixed: 멈춘 시계·앞으로만, 직렬 실행, 자기 행만 단언, processed 정확값 단언 금지 |
| A8 | opus F9 | §6 | PG 제어 전역 한 칸 | 채택 | fixed: 멱등 키별 결과 |
| A9 | opus F10 | §3.3 | 만료 시각 지난 ACTIVE 입찰 매칭 | 채택 | fixed: expires_at > 현재만 체결 가능 |
| A10 | opus F11 · codex F10 | §7 | 배치 순서·경고 창·MATCHED→EXPIRED·bid_events | 채택 | fixed: id 순, 경고 식(생성기 해석), 전이·이벤트 명시, §9-2 |
| A11 | opus F12 | §7 | 재조회 승인 paid_at·등급 시점 | 채택 | fixed(A2와 같이) |
| A12 | opus F13 | §5.2 | 원장·잔액 열 값 | 채택 | fixed: USE·EARN·balances 열 표기(생성기 행 형식과 같음) |
| A13 | opus F14 | §3.3 | 동시 체결 진 쪽 응답 | 채택 | fixed: 201/409, 5xx 금지 |
| A14 | opus F15 · codex OQ1 | §2 | 빈 오류 경로, book 무헤더 | 채택 | fixed: 오류표 보강, book 헤더 불필요 |
| A15 | opus F16 · codex F9 | §3.4 | 복구 입찰 tradeId | 채택 | fixed: 살아 있는 체결만 |
| A16 | opus F17 | spec §5 | 금액 예시 없음 | 채택 | fixed: ADR-0003 §8 표(스크립트로 ADR-0002 공식 계산) |
| A17 | opus F18 | spec | 404 그린 위장 | 채택 | fixed: code 단언 의무 |
| A18 | codex F1 | §3.3 | "한 번만 체결" vs 복구 재체결 | 채택 | fixed: 살아 있는 체결 최대 1건 |
| A19 | codex F4 · opus OQ1 | §3.1 | 1,000원 단위 근거 없음 | 채택 | fixed: 삭제 |
| A20 | codex F5 | §5.1 | pointToUse 0이면 USE 행 없음 | 채택 | fixed |
| A21 | opus OQ2 | §5.1 | 트랜잭션 경계·PENDING 관측 | 채택 | fixed: 주문 행 PG 호출 전 커밋, PENDING 미사용(§9-5) |
| A22 | opus OQ3 | §3.1 | 제한 중 기존 입찰·결제 | 채택 | fixed: 새 입찰에만 적용 [기본값] |
| A23 | opus OQ4 | §7 | processed 정의 | 채택 | fixed: 주 대상 상태 변경 수 |
| A24 | codex 감사 F1 | ADR-0003 §5.1·§9-4 | 멱등 "현재 상태" vs ADR-0002 "첫 결과" 충돌 미해소 | 채택 | fixed: ADR-0002 개정 항목으로 격상(사용자 합의 필요), 예시 명시 |
| A25 | codex 감사 F2 | §5.2 /me/points | 캐시 잔액(만료 묶음 포함) vs 사용 가능액 | 채택 | fixed: available·pending = 캐시(verify 정의), usable 별도 |
| A26 | codex 감사 F3 | §9-1 | "verify가 이미 이 해석" 근거 부정확(생성기는 승인 때 배분) | 채택 | fixed: 개정 항목·이유·중간 상태 차이 명시, 테스트 직접 조회 |

## 생략한 검증

## 완료 요약
