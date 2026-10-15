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
| 2026-10-15 | 브랜치 `feat/behavior-tests-stage1`(main `19d74d8`). task 02: build-image.sh 정리 규칙 "같은 태그의 이전 빌드(dangling)만" → 1k 재생성 4.5초·이미지 60초 561MB, 500k와 공존 | |
| 2026-10-15 | 루트 build.gradle.kts의 Spring 설정을 god·layered에만(`configure(listOf(...))`), settings에 behavior-tests. `tools/behavior/run.sh` 작성. 워커용 1k DB `ccle-bt-dev`(:15434) | |
| 2026-10-15 | 테스트 모듈 Opus 워커(model opus, ADR·스키마·명세만 입력, 앱 소스 미열람) | 계약 117·인프라 16. 워커 자체 반증: 고정 응답 스텁에서 그린 위장 11개 발견 → 라우트 대조 요청 추가로 0개(시계 ISO 형식 1개는 200 스텁에서 통과 — 형식 전용). 보조 제어 `_control/inquiry-outcomes` 추가(ADR 밖, 재조회 응답 없음 경로용) |
| 2026-10-15 | `run.sh god`: 94초, 계약 117 실패(전부 404 — POST /bids 68, GET /admin/clock 35 …), 인프라 16 통과, verify 위반 0. `run.sh layered`: 88초, 같은 분포 | 가정 1 실증(실행당 약 1.5분). 집계 스크립트 실수(빈 failure 요소를 거짓으로 평가 → 전부 통과로 오집계)를 바로잡음 |
| 2026-10-15 | 커밋 `651acde` | |
| 2026-10-15 | 테스트 코드 듀얼 리뷰 루프 1회차(높음, ≤3): packet base `19d74d8`, OUT=`scratchpad/rv.4fqa`, 미러 `mirror.s3eN`, untracked 0. 보안 스캔: 개발 기본 비밀번호 2건(오탐) | codex 1차 bwrap 오류 → 재시도 5건. Opus 7건 + OQ3 |
| 2026-10-15 | Opus F1 확인: verify.sql:517-544 교차 검사가 expires_at 무시 + 테이커 예외가 이벤트 id 간격 ≤2(동시 요청에서 깨짐) → 금지영역이라 멈추고 보고. 사용자: "이번 범위에 verify 수정 포함" | 명세 §4 재합의 기록 |
| 2026-10-15 | verify 수정: 반증 5종(1k 시드 위). 새 verify: 정상 3종(만료 뒤 교차·끼어든 이벤트·만료된 우선 입찰) 대상 검사 0, 결함 2종(살아 있는 교차 → active_book_no_cross·book_never_crossed, 살아 있는 우선 입찰 건너뜀 → trade_price_time_priority) 검출. 이전 verify는 정상 3종을 모두 위반으로 판정(수정 필요성 실증). 표본의 bid_validity_period 위반은 표본 만료일을 30/60/90일로 안 맞춘 탓(대상 밖) | |
| 2026-10-15 | 시드 회귀: 1k 위반 0(39초, 이전 verify 30초), 2만 생성+이미지 빌드 4분 45초 위반 0 → 20k 이미지 삭제 | |
| 2026-10-15 | build-image 태그 라벨: 1k 2회 재빌드 → 2회차에 같은 태그 이전 빌드만 지움, 500k 유지. 라벨 없던 옛 1k 이미지는 수동 삭제(개발 DB 컨테이너가 쓰고 있어 컨테이너 삭제 뒤) | |
| 2026-10-15 | 테스트 워커(같은 Opus) 수정 B3~B8·B10·B12: infraTest 18 green, 앱 없이 contract 117 red, PG_EXTERNAL 경로 확인(PG 꺼지면 8 클래스 모두 명시 실패) | |
| 2026-10-15 | run.sh 재실행: god 96초·layered 98초, infra 18 통과, contract 117 실패(116 HTTP 상태 404, 1 오류 code 없음), verify 위반 0, 가짜 PG 프로세스 잔존 0 | |
| 2026-10-15 | 루프 1 종합 감사(codex) ∥ 루프 2 리뷰(Opus ∥ codex): 미러 `mirror.VbOM`, OUT=`scratchpad/rv.YIJv` | 감사 2, codex 3, Opus 3 + 참고 2. verify 이벤트 순서 문제가 같은 검사 3번째 수정 → core §3 정지 규칙: 다듬기 대신 전제(이벤트 순서 보장)를 계약으로 결정 → 사용자 "사이즈 단위 직렬화를 계약에" |
| 2026-10-15 | 반증 2판 9종(만료 = 등록 + 30일로 표본 정리): 정상 4종 위반 0(만료 시각 교차·다른 사이즈 끼어듦·직렬화된 진 요청·만료된 우선 입찰), 결함 5종 검출(살아 있는 교차·같은 사이즈 끼어듦·취소된 최우선 건너뜀·비직렬 진 요청·살아 있는 우선 입찰) | |
| 2026-10-15 | 테스트 워커 C3·C4·C6. 참고: 포인트 묶음 도우미는 확정 시각이 마지막 정산 마감(시드 09-28) 뒤여야 해 CLOCK_START < 약 09-30이면 포인트 테스트 준비가 실패 — 테스트 고정 데이터 제약(run.sh는 CLOCK_START를 쓰지 않음) | |
| 2026-10-15 | 회귀: 1k 재빌드(이전 같은 태그 이미지 정리, 중복 "지움" 출력 → id 중복 제거 후 1줄), 2만 빌드 verify 위반 0(이미지 삭제), run.sh god 109초·layered 95초: infra 18 통과·contract 117 실패·verify 0 | |
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
| B1 | opus F1 | MatchingTest.kt:223-242, verify.sql:517-544 | verify 교차 검사가 expires_at 무시 → 올바른 구현도 실패 | 채택(사용자 재합의) | fixed L1: book_never_crossed·active_book_no_cross·trade_price_time_priority에 만료 조건 |
| B2 | opus OQ1(+메인 확장) | verify.sql:520 | 테이커 예외가 이벤트 id 간격 ≤2 → 동시 요청·배치 이벤트 순서에서 깨짐 | 채택 | fixed L1: 테이커(v_match.taker_id) + 같은 시각으로 판정 |
| B3 | opus F2 | Flow.kt:37 | 시계 문자열 초 생략 | 채택 | fixed L1: ISO_OFFSET_DATE_TIME |
| B4 | opus F3 | build.gradle.kts, VerifySqlTest | verify 판정 대상이 클래스 순서에 의존 | 채택 | fixed L1: test = infraTest → contractTest, 앱 행 verify는 run.sh 끝 |
| B5 | opus F4 · codex F5 | FakePgTest.kt:46 | 정상 응답 시간 상한 단언 | 채택 | fixed L1: 제거 |
| B6 | opus F5 | run.sh:41 | verify 실패 진단 소실 | 채택 | fixed L1: verify.log + ERROR/NOTICE 출력 |
| B7 | opus F6 | FakePg.kt:120 | 재조회 키 이중 디코딩 | 채택 | fixed L1 |
| B8 | opus F7 | DbHelperTest.kt:75 | 정산 없으면 빈 통과 | 채택 | fixed L1: assertNotNull |
| B9 | codex F1 | build-image.sh:44 | 다른 태그의 dangling도 지움 | 채택 | fixed L1: LABEL ccle.seed.tag, 같은 태그만 |
| B10 | codex F2 | run.sh:29 | 앱이 가짜 PG보다 먼저 기동 | 채택 | fixed L1: PG 별도 프로세스 먼저, PG_EXTERNAL, 카운터 HTTP 노출 |
| B11 | codex F3 | run.sh:31 | 포트 점유 시 다른 앱을 판정 | 채택 | fixed L1: 포트 사전 검사, health 뒤 pid 확인 |
| B12 | codex F4 | FakePg.kt:96 | amount 누락에도 승인 | 채택 | fixed L1: 400, 결제 테스트 전부 PG 수신 금액 = card_amount 단언 |
| B13 | opus OQ2 | ADR-0003 §8 | 예시 산문 floor 값 오기(119,963) | 채택 | fixed L1(docs) |
| B14 | opus OQ3 | run.sh | 고정 포트로 동시 실행 불가 | 기각(의도: ADR-0002 §7 동시 실행 금지) | run.sh 주석으로 명시 |
| C1 | 감사 F1 · codex L2 F1 · opus L2 F1 (main-synthesis: 같은 뿌리) | verify.sql:510-532 | 이벤트 id 순서를 실제 순서로 가정 — 동시 요청에서 정상 위반(진 요청 ACTIVE)·결함 통과(취소된 최우선 건너뜀) | 채택(사용자: "사이즈 단위 직렬화를 계약에") | fixed L2: ADR-0003 §3.3 직렬화 조항, 테이커 예외에 "같은 사이즈 다른 이벤트 없음" |
| C2 | opus L2 F2 | verify.sql:525 | 새 예외에 간격 상한 없음(지연 매칭 통과) | 채택 | fixed L2(C1과 같은 수정) |
| C3 | 감사 F2 | PaymentTest.kt:183·324 | 재결제 경로 PG 금액 미검증 | 채택 | fixed L2: Flow.pay 201마다 PG 수신 금액 = cardAmount 공통 단언 |
| C4 | codex L2 F2 | BatchAndClockTest.kt:25 | 시작 시계 ≥ 시드 시각 단언(CLOCK_START 허용과 충돌) | 채택 | fixed L2 |
| C5 | codex L2 F3 | build-image.sh:45 | 라벨 도입 전 같은 태그 이미지 미정리 | 채택 | fixed L2: 커밋 전 같은 태그 이미지 id 보관 → 정리 |
| C6 | opus L2 F3 | behavior-tests/build.gradle.kts:57 | 루트 build·test가 환경 없이 행위 테스트 실행 | 채택 | fixed L2: env DB_URL·BASE_URL 있을 때만, 건너뜀 사유 로그 |
| C7 | opus L2 참고 | spec §4 | 재합의 문구 "2개" vs 실제 3곳+보조 뷰 | 채택 | fixed L2(docs) |

## 생략한 검증

## 완료 요약
