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
| 2026-10-15 | 루프 2 종합 감사(codex) ∥ 루프 3 리뷰(Opus ∥ codex): 미러 `mirror.oDXN`, OUT=`scratchpad/rv.ifzl` | 감사 1, codex 4, Opus 3 + OQ1 → 채택 7·기각 1. 3회차에도 신규 채택이 있어 종료 조건 미충족(최대 3루프) → review unresolved: 수정 후 사용자 판단 |
| 2026-10-15 | 테스트 워커 D2·D3·D8: env 없이 루트 build 건너뜀·직접 호출 실패·opt-in만 있고 BASE_URL 없으면 실패 확인. 반증 3판 12종: 정상 5종 위반 0(검사 76개), 결함 7종 검출(체결 이벤트 사이 끼어듦·만료 뒤 복구 포함) | |
| 2026-10-15 | 회귀: 1k·2만 verify 76개 위반 0, run.sh god 113초·layered 115초: infra 18 통과·contract 117 실패(116 HTTP 404, 1 code 없음)·verify 0. 500k 이미지는 옛 verify(75개)로 빌드 → 새 verify를 500k 컨테이너에 백그라운드 실행 | |
| 2026-10-15 | 사용자: "타깃 재점검 1회 후 종료". codex post-fix(3회차 수정 hunk만): 1차 bwrap 오류 → 재시도 2건(E1·E2) | 워커 수정: 축약 호출·infraTest DB_URL 확인표 전부 기대대로 |
| 2026-10-15 | run.sh god 117초·layered 110초: infra 18 통과, contract 117 실패, verify 76개 위반 0 | |
| 2026-10-16 | 500k 새 verify가 13시간 넘게 v_result에서 끝나지 않음 → 취소. EXPLAIN: book_never_crossed 테이커 예외가 사이즈→입찰(약 219)→이벤트로 풀려 테이커 490만 건마다 수백 회 조회, 교차 EXISTS가 1,300만 구간 전부에 먼저 실행 | 실측 id 간격(테이커 ACTIVE→마지막 MATCHED) 중앙값 2·최대 291 |
| 2026-10-16 | 1차 수정(사이 이벤트를 PK 범위 구동) → 단독 실행 60분 제한 초과. 2% 표본 분해: 교차 46초·예외 10초·옛 검사 39초 → 결합 순서가 원인 | |
| 2026-10-16 | 2차 수정: 예외를 MATERIALIZED CTE로 먼저 거름, 반대편을 등식(side CHECK BUY·SELL)으로 해시 키에 포함 → 500k 단독 28분·위반 0. 반증 12종 결과 수정 전과 동일(falsify3=falsify5), run.sh god 132초: contract 117 실패·verify 76 위반 0 | 커밋 fix(verify) |
| 2026-10-16 | 500k 전체 verify 재실행: 1회차는 호스트 메모리 부족(다른 프로젝트 ES 재시작 루프)으로 셸이 중단 → 남은 백엔드 취소. 컨테이너 안 분리 실행으로 재실행 | 76개 위반 0, 4,377초(73분 — 이전 75개 83분) |

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
| D1 | 감사 L2 F1 | verify.sql:527 | 직렬화 검사가 등록~테이커 MATCHED만 봄(두 MATCHED 사이 끼어듦 놓침) | 채택 | fixed L3: 범위 = 등록 ~ 그 체결의 마지막 MATCHED |
| D2 | codex L3 F1 · opus L3 F2 | behavior-tests/build.gradle.kts:27·54·64 | env 없으면 0건 성공(red 오인) / 앱 키 DB_URL로 판정 → 개발 DB 오염 | 채택 | fixed L3: BEHAVIOR_TESTS=1 opt-in, 직접 호출 시 없으면 실패, 루트 집계만 건너뜀 |
| D3 | codex L3 F2 | Flow.kt:94 | 테스트가 PG_URL 대신 PG_PORT로 주소 구성 | 채택 | fixed L3 |
| D4 | codex L3 F3 | build-image.sh:46 | 같은 규모 다른 시드 태그(1k-s42→1k-s43) 미정리 | 기각 | 시드가 다르면 다른 데이터 세트 — 정리 단위 = 전체 태그(명세 산출물 5 "규모 태그") |
| D5 | codex L3 F4 | build-image.sh:49 | 이전 이미지 id 강제 삭제가 다른 태그까지 지움 | 채택 | fixed L3: 남은 RepoTags 있으면 남김 |
| D6 | opus L3 F1 | PaymentTest.kt:272·316 | INQUIRY pg_tx_id 단언이 계약 밖 | 채택 | fixed L3: ADR-0003 §7에 APPROVED = PG 값·DECLINED = NULL 명시(생성기와 같음), 단언 유지 |
| D7 | opus L3 F3 | verify.sql:133-141 | 만료된 판매 입찰의 ACTIVE 복구 미검출 | 채택 | fixed L3: bid_restore_within_validity(검사 76개) |
| D8 | opus L3 OQ1 | Env.kt:89 | 1년 이동 뒤 배치가 15초 타임아웃에 걸릴 수 있음 | 채택 | fixed L3: 배치만 BATCH_TIMEOUT_MS(기본 120초) |
| E1 | codex post-fix F1 | behavior-tests/build.gradle.kts:35 | Gradle 축약 호출(contractT·cT)은 직접 호출로 인정 안 됨 → opt-in 없이 0건 성공 | 채택 | fixed: camelCase·하이픈 접두 규칙으로 해석, 그래프에 있는 태스크만 |
| E2 | codex post-fix F2 | behavior-tests/build.gradle.kts:62 | opt-in만 있으면 infraTest가 기본 DB 주소에 행을 씀 | 채택 | fixed: infraTest도 DB_URL 필수 |

## 생략한 검증

- 없음. 긴급 수정이 아니며 §4 안전선과 높음 stakes 리뷰를 수행했다. 리뷰는 3루프 상한에서 종료했다(사용자 결정 "타깃 재점검 1회 후 종료", 재점검 2건은 수정·검증).

## 완료 요약

### 결론

ADR-0003(HTTP API 계약 1단계: 입찰·체결·결제)을 검증하는 블랙박스 행위 테스트 모듈 `behavior-tests`를 추가했다. 계약 테스트 117개, 인프라 테스트 18개, 가짜 PG로 구성된다. `tools/behavior/run.sh <god|layered>` 하나로 1k 시드 DB → 가짜 PG → 앱 → 테스트 → `verify.sql` 순서로 실행된다. 명세의 끝의 정의를 두 앱 모두에서 충족했다. 아직 구현이 없는 god·layered에서 인프라 18개는 통과하고, 계약 117개는 전부 실패하며 실패 원인은 엔드포인트 없음(404) 류다(1건은 404 응답에 `code`가 없어 실패). verify 76개 검사에서 위반은 0이다. 리뷰 중에 `verify.sql` 호가 이력·교차 검사의 결함 두 가지가 드러났다. 하나는 만료 시각을 무시한 것이고, 다른 하나는 이벤트 id 간격 ≤2로 "등록 즉시 체결"을 판정한 것이다. 사용자 재합의 2회(verify 수정 범위 포함, ADR-0003 §3.3에 사이즈 단위 직렬화 추가)를 거쳐 고쳤다. 반증 표본 12종에서 정상 5종은 위반 0, 결함 7종은 모두 검출됐다. 이전 verify는 정상 표본 3종을 위반으로 잘못 판정했다(1판 표본 기준). 리뷰는 듀얼 루프 3회를 돈 뒤 사용자 지시로 타깃 post-fix 재점검 1회를 하고 종료했다.

기준: main `19d74d8`(ADR-0003 채택) → HEAD `ffd3523`, 브랜치 `feat/behavior-tests-stage1`. docs를 뺀 변경은 24개 파일, +3338/−13이다.

### 산출물

| 파일 | 역할 |
|---|---|
| `behavior-tests/build.gradle.kts` | 모듈 정의. `infraTest`(태그 infra)·`contractTest`(태그 contract)를 직렬로 실행하고, `test` = infraTest 다음 contractTest. `BEHAVIOR_TESTS=1` opt-in이 없으면 루트 집계에서는 건너뛰고, 직접 호출(축약 포함)하면 실패시킨다. 가짜 PG 클래스패스 태스크도 여기 있다 |
| `behavior-tests/src/main/.../pg/FakePg.kt` | 가짜 PG(JDK 내장 HTTP 서버, ADR-0003 §6). 멱등 키별로 결과를 제어하고, amount가 없으면 400. 보조 제어 `_control/inquiry-outcomes`(ADR 밖, 재조회 응답 없음 경로용) |
| `behavior-tests/src/test/.../contract/*Test.kt` (8개 클래스) + `Amounts.kt` | ADR-0003 계약 테스트 117개: 입찰 등록, 체결, 결제, 포인트, 멱등·결제 가능 여부, 제한·동시성, 배치·시계, 체결 조회·오류. 금액 기대값은 ADR-0003 §8 표 기준 |
| `behavior-tests/src/test/.../infra/` (`DbHelperTest`·`FakePgTest`·`VerifySqlTest`) | 인프라 자체 테스트 18개: 가짜 PG, DB 도우미, 시드와 도우미 행에 대한 verify.sql |
| `behavior-tests/src/test/.../support/` (`Api`·`Db`·`Env`·`Flow`·`FakePgExtension`) | HTTP 클라이언트, DB 직접 조회·INSERT 도우미, env(`BASE_URL`·`DB_URL`·`PG_URL`·`BATCH_TIMEOUT_MS` 등), 공통 흐름(결제 201마다 PG 수신 금액 = cardAmount 단언), 가짜 PG 연결 |
| `tools/behavior/run.sh` | 1회 실행 스크립트. 포트 사전 검사 → 1k 시드 컨테이너 → 가짜 PG(별도 프로세스, 앱보다 먼저) → 앱 jar → health 확인 → gradle → verify.sql(실패하면 종료 코드 3) → 정리 |
| `tools/seedgen/verify.sql` | 호가 이력·교차 검사 3곳(`book_never_crossed`·`active_book_no_cross`·`trade_price_time_priority`)과 보조 뷰(`v_act`·`v_match`)에 만료 조건과 사이즈 단위 직렬화 전제를 반영했다. 검사 `bid_restore_within_validity`를 새로 추가해 총 76개 |
| `tools/seedgen/build-image.sh` | 시드 이미지를 규모 태그마다 1개 유지한다(`LABEL ccle.seed.tag`). 같은 태그의 이전 빌드만 지우고, 다른 태그가 붙어 있거나 사용 중인 이미지는 남긴다 |
| `build.gradle.kts`·`settings.gradle.kts` | 루트 Spring 설정은 god·layered에만 적용(`configure(listOf(...))`), `behavior-tests` include |
| `docs/adr/0003-http-api-stage1.md` (docs) | §3.3 사이즈 단위 직렬화, §7 payment-due 이벤트 순서와 INQUIRY `pg_tx_id` 규칙, §8 금액 예시의 floor 값 정정 |

### 핵심 diff

**1. `verify.sql` `book_never_crossed`: 등록 즉시 체결 예외와 만료 조건**

before (`19d74d8:tools/seedgen/verify.sql:517-527`):

```sql
UNION ALL -- [§4.1] 호가창은 어느 순간에도 교차하지 않는다: 입찰이 ACTIVE가 되는 순간(등록·복구) 반대편에 교차하는
          -- 다른 사용자의 ACTIVE 입찰이 있으면 그 자리에서 체결됐어야 한다. 바로 체결된 테이커 순간(같은 시각, 전역 이벤트 2개 안에 MATCHED)은 제외
SELECT 'book_never_crossed', count(*) FROM v_act x
 WHERE NOT (x.e_eid IS NOT NULL AND x.e_eid - x.s_eid <= 2
            AND EXISTS (SELECT 1 FROM bid_events be JOIN bid_events bs ON bs.id = x.s_eid
                        WHERE be.id = x.e_eid AND be.to_status = 'MATCHED' AND be.created_at = bs.created_at))
   AND EXISTS (SELECT 1 FROM v_act z
               WHERE z.product_size_id = x.product_size_id AND z.side <> x.side
                 AND z.s_eid < x.s_eid AND (z.e_eid IS NULL OR z.e_eid > x.s_eid)
                 AND z.user_id <> x.user_id
                 AND CASE x.side WHEN 'BUY' THEN x.price >= z.price ELSE x.price <= z.price END)
```

after (`tools/seedgen/verify.sql:523-549`):

```sql
UNION ALL -- [§4.1] 호가창은 어느 순간에도 교차하지 않는다: 입찰이 ACTIVE가 되는 순간(등록·복구) 반대편에 교차하는
          -- 다른 사용자의 체결 가능한(만료 시각 전) ACTIVE 입찰이 있으면 그 자리에서 체결됐어야 한다.
          -- 예외: 그 구간이 같은 시각의 체결로 끝났고 이 입찰이 그 체결의 테이커이며, 등록(복구)과 체결 이벤트 2행 사이에
          --       같은 사이즈의 다른 이벤트가 없다(등록·복구 즉시 체결). 같은 사이즈 안의 이벤트 id 순서는 실제 처리
          --       순서다(ADR-0003 §3.3 사이즈 단위 직렬화). 다른 사이즈의 이벤트는 사이에 끼어도 된다
SELECT 'book_never_crossed', count(*) FROM (
  -- 즉시 체결된 테이커 순간을 먼저 걸러 낸 뒤(MATERIALIZED로 순서 고정) 남은 구간만 교차를 확인한다.
  -- 순서를 플래너에 맡기면 1,300만 구간 전부에 교차 확인을 먼저 돌려 본 규모에서 끝나지 않는다(실측: 50만 시드 60분 초과)
  WITH cand AS MATERIALIZED (
    SELECT x.* FROM v_act x
     WHERE NOT EXISTS (SELECT 1 FROM v_match m
                       WHERE m.taker_id = x.bid_id AND x.e_eid IN (m.m_eid, m.m_eid2) AND m.m_at = x.s_at
                         -- 등록 ~ 그 체결의 마지막 MATCHED 사이 이벤트를 id 범위(PK)로 먼저 훑고 사이즈는 행마다 확인한다.
                         -- 같은 시각 안의 범위라 짧다(사이즈 → 입찰 → 이벤트 순은 테이커마다 입찰 수백 개를 훑는다)
                         AND NOT EXISTS (SELECT 1 FROM bid_events be
                                         WHERE be.id > x.s_eid AND be.id < m.m_eid2
                                           AND be.trade_id IS DISTINCT FROM m.trade_id
                                           AND (SELECT bb.product_size_id FROM bids bb WHERE bb.id = be.bid_id)
                                               = x.product_size_id)))
  SELECT 1 FROM cand x
   WHERE EXISTS (SELECT 1 FROM v_act z
                 WHERE z.product_size_id = x.product_size_id
                   AND z.side = CASE x.side WHEN 'BUY' THEN 'SELL' ELSE 'BUY' END   -- 반대편(등식이라 해시 키에 들어간다)
                   AND z.s_eid < x.s_eid AND (z.e_eid IS NULL OR z.e_eid > x.s_eid)
                   AND z.user_id <> x.user_id
                   AND z.expires_at > x.s_at
                   AND CASE x.side WHEN 'BUY' THEN x.price >= z.price ELSE x.price <= z.price END)) bnc
```

이전에는 전역 이벤트 id 간격 ≤2로 판정해서, 동시 요청이 사이에 끼면 정상인 경우도 위반으로 잡았다. 이제는 테이커인지, 같은 시각인지, 같은 사이즈의 다른 이벤트가 없는지로 판정하고, 만료 시각이 지난 상대 입찰은 제외한다. 50만 규모에서 끝나도록, 예외는 MATERIALIZED CTE로 먼저 거르고, 사이 이벤트는 id 범위(PK)로 훑으며, 반대편은 등식으로 해시한다(교차 검사 28분).

**2. `behavior-tests/build.gradle.kts`: opt-in 판정 핵심부** (`behavior-tests/build.gradle.kts:76-94`, 신규 파일)

```kotlin
gradle.taskGraph.whenReady {
    val direct = directlyRequested(this)
    if (!behaviorOptIn && direct.isNotEmpty()) {
        throw GradleException(
            "행위 테스트 ${direct}를 직접 호출했지만 env BEHAVIOR_TESTS=1 이 없다. " +
                "건너뛰면 0건 성공으로 보이므로 실패시킨다. tools/behavior/run.sh 를 쓰거나 " +
                "BEHAVIOR_TESTS=1 BASE_URL=... DB_URL=... 로 호출하라."
        )
    }
    if (behaviorOptIn && hasTask("${project.path}:infraTest") && System.getenv("DB_URL").isNullOrBlank()) {
        throw GradleException("infraTest에는 env DB_URL 이 명시돼야 한다(코드 기본 주소로 붙어 픽스처 행을 쓰지 않게).")
    }
    if (behaviorOptIn && hasTask("${project.path}:contractTest")) {
        val missing = listOf("BASE_URL", "DB_URL").filter { System.getenv(it).isNullOrBlank() }
        if (missing.isNotEmpty()) {
            throw GradleException("contractTest에는 env $missing 가 명시돼야 한다(코드 기본값으로 엉뚱한 앱·DB를 보지 않게).")
        }
    }
}
```

`directlyRequested`(같은 파일 49-67행)는 요청 문자열을 Gradle 축약 규칙(camelCase·하이픈 접두, 예: `cT`·`contractT`)으로 해석한 뒤, 실제 태스크 그래프에 있는 태스크만 남긴다(E1). infraTest도 DB_URL을 요구하게 한 것은 E2다.

**3. `tools/behavior/run.sh`: 가짜 PG → 앱 기동** (`tools/behavior/run.sh:35-53`, 신규 파일)

```bash
# 가짜 PG: 앱보다 먼저 띄운다(앱이 기동 중 PG에 붙어도 되게)
"$JAVA_HOME/bin/java" -cp "$(cat behavior-tests/build/fake-pg.classpath)" com.ccle.behavior.pg.FakePgKt > "$logs/fake-pg.log" 2>&1 &
pg_pid=$!
for _ in $(seq 100); do
  grep -q "fake-pg ready" "$logs/fake-pg.log" && break
  kill -0 "$pg_pid" 2>/dev/null || { echo "가짜 PG 기동 실패: $logs/fake-pg.log" >&2; exit 2; }
  sleep 0.2
done
grep -q "fake-pg ready" "$logs/fake-pg.log" || { echo "가짜 PG 대기 시간 초과" >&2; exit 2; }

"$JAVA_HOME/bin/java" -jar "$app/build/libs/$app-0.0.1.jar" > "$logs/app-$app.log" 2>&1 &
app_pid=$!
ready=
for _ in $(seq 120); do
  kill -0 "$app_pid" 2>/dev/null || { echo "앱 기동 실패: $logs/app-$app.log" >&2; exit 2; }
  curl -sf "$BASE_URL/actuator/health" > /dev/null && { ready=1; break; }
  sleep 0.5
done
[ -n "$ready" ] && kill -0 "$app_pid" 2>/dev/null || { echo "앱 health 대기 시간 초과: $logs/app-$app.log" >&2; exit 2; }
```

**4. ADR-0003 §3.3: 사이즈 단위 직렬화 조항** (`docs/adr/0003-http-api-stage1.md:69`, 추가된 줄. 재합의 2)

```markdown
- **사이즈 단위 직렬화:** 한 사이즈의 입찰 상태를 바꾸는 처리(등록·즉시 주문·취소·체결, 배치의 만료·복구·재매칭)는 그 `product_sizes` 행을 잠근(`SELECT … FOR UPDATE`) 트랜잭션 안에서 하고, 잠금은 커밋까지 유지한다. `bid_events` 행은 잠근 뒤에 쓴다. 그래서 **같은 사이즈 안에서는 `bid_events.id` 순서가 실제 처리 순서**다. `verify.sql`의 호가 이력 검사(무교차·가격·시간 우선)가 이 순서에 기댄다. 등록이 교차하면 그 등록 이벤트 바로 뒤에 체결 이벤트 2행이 오고, 그 사이에 같은 사이즈의 다른 이벤트는 없다.
```

### 검증 결과

| 항목 | 결과 |
|---|---|
| `run.sh god` (최종) | 117초. infra 18 통과, contract 117 실패(엔드포인트 없음 404 류, 1건은 404 응답에 `code` 없음), verify 76개 위반 0 |
| `run.sh layered` (최종) | 110초. 결과는 god과 같다 |
| 실행 시간 추이 (god/layered) | 첫 실행 94/88초 → 루프1 뒤 96/98 → 루프2 뒤 109/95 → 루프3 뒤 113/115 → 최종 117/110. 가정 1("실행당 1분 안팎")은 부분 충족 — 실측 약 1.5~2분(빌드·앱 기동 포함). 실행이 직렬이고 과제당 1회라 허용 |
| 그린 위장 반증 (테스트 워커) | 고정 응답 스텁에서 그린 위장 11개 발견 → 라우트 대조 요청을 추가해 0개. 시계 ISO 형식 1개는 200 스텁에서 통과(형식만 보는 테스트) |
| verify 반증 표본 (3판, 12종) | 정상 5종 위반 0, 결함 7종 검출(체결 이벤트 사이 끼어듦, 만료 뒤 복구 포함). 1판(5종)에서 이전 verify는 정상 3종을 모두 위반으로 판정했다 |
| 시드 회귀 | 1k·2만 verify 76개 위반 0(2만 이미지는 확인 뒤 삭제) |
| opt-in 경로 | env 없이 루트 build 하면 건너뜀, 직접 호출하면 실패, opt-in만 있고 BASE_URL이 없으면 실패. 축약 호출과 infraTest DB_URL 확인표는 모두 기대대로 |
| 앱 없이 실행 | infraTest 18 green, contract 117 red. PG_EXTERNAL에서 PG가 꺼지면 8개 클래스 모두 명시적으로 실패 |
| build-image 태그 | 1k 재빌드 시 같은 태그의 이전 빌드만 지우고 500k는 유지 |
| 정리 | 가짜 PG 프로세스 잔존 0 |

### 리뷰 요약

| 단계 | 형태 | 지적 | 채택 | 기각 |
|---|---|---|---|---|
| A: ADR-0003 (설계 문서) | 설계 선검증 + 듀얼 1패스 + codex 감사·재점검 | 26 | 26 | 0 |
| B: 테스트 코드 루프 1 | Opus ∥ codex | 14 | 13 | 1 (B14 고정 포트: 동시 실행 금지가 의도) |
| C: 루프 2 | 루프 1 감사 ∥ Opus ∥ codex | 7 | 7 | 0 |
| D: 루프 3 | 루프 2 감사 ∥ Opus ∥ codex | 8 | 7 | 1 (D4 다른 시드 태그 정리: 정리 단위는 전체 태그) |
| E: post-fix 재점검 | codex, 3회차 수정 hunk만 | 2 | 2 | 0 |

- 루프 2에서 verify의 이벤트 순서 문제가 같은 검사에 대한 세 번째 수정이 됐다. 그래서 core §3 정지 규칙을 적용해, 검사를 더 다듬는 대신 그 전제(사이즈 단위 직렬화)를 계약으로 결정했다(C1, 사용자 재합의 2).
- 루프 3에서도 새로 채택한 지적이 있어 종료 조건을 채우지 못했다(review unresolved). 사용자 지시("타깃 재점검 1회 후 종료")에 따라 post-fix 재점검 E1·E2를 반영하고 종료했다.
- 시크릿 스캔에서는 개발 기본 비밀번호 `ccle` 2건이 잡혔으나 공개 기본값이라 오탐이다.

### 남은 한계

- **CLOCK_START 제약:** 포인트 묶음 도우미는 확정 시각이 마지막 정산 마감(시드 09-28) 뒤여야 한다. CLOCK_START가 약 09-30보다 이르면 포인트 테스트 준비가 실패한다. 테스트 고정 데이터의 제약이며, run.sh는 CLOCK_START를 쓰지 않는다.
- **500k 시드 이미지:** 옛 verify(75개)로 빌드한 이미지지만, 새 verify(76개)를 그 컨테이너에 따로 돌려 위반 0을 확인했다(73분). 처음 넣은 테이커 예외는 이 규모에서 13시간 넘게 끝나지 않아 실행 순서를 고쳤다(fix(verify) 커밋).
- **리뷰 종료 조건 미충족:** 3루프 상한에서 종료했다(위 리뷰 요약 참고).
- **ADR 밖 보조 제어:** 가짜 PG의 `_control/inquiry-outcomes`는 ADR-0003 밖에 있는 테스트 전용 제어다.
- **계약 테스트는 아직 전부 red:** 두 앱에 도메인 구현이 없기 때문에 의도한 상태다(다음 작업: NEXT.md N1 갓 클래스). 테스트가 green이 될 수 있는지, 즉 계약의 결정성(명세 가정 2)은 구현이 들어온 뒤에야 실증된다.
