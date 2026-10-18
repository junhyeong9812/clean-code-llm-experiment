# log — layered-stage1

## 타임라인

| 시각 | 사건 | 결과/결정 |
|------|------|----------|
| 2026-10-18 19:00 | 착수: NEXT N1. 브랜치 `feat/layered-stage1`(main `900c454`에서). layered 현황 = 빈 앱(LayeredApplication·application.yml·스모크 1). ADR-0001 §1(계층 4개·구현 순서), 루트 build.gradle.kts(두 모듈 같은 의존성) 확인 | 인터뷰 시작 |
| 2026-10-18 19:02 | 인터뷰 3회(11문항): god 코드 출발·풍부한 도메인·JPA 엔티티=도메인·기술 선택 god와 동일·계약 조항 순서·행위 테스트로 충분·Opus 워커·장애 주입 재수행·domain이 저장소 인터페이스 소유·Spring Data JPA+네이티브·규모 비교 완료 시 1회 | 명세 합의, auto → SPEC=1·MODE=auto |
| 2026-10-18 19:02 | 설계 선검증 생략: 동작 불변식 신설 없음 — 전부 ADR-0002/0003 정의, 강제 위치 `tools/seedgen/verify.sql`(76개)·`behavior-tests/src/test/kotlin/com/ccle/behavior/contract/*`(117). 계층 규칙은 구조 범위 제약 | |
| 2026-10-18 19:03~19:10 | task 01 워커(Opus): 계층 4패키지 14파일 528줄 — 엔티티 12(god와 같은 매핑, domain 5파일), MarketClock(domain)+FrozenClock(infra), UserService.requireUser(401 판정 service 단일), Failure enum+MarketException(domain) → api statusOf(else 없는 when), Json·ErrorHandling 이식 | build 통과(스모크 validate), run.sh layered: infra 18/18, contract 3/117(시계 3), 실패 114 전부 NO_ROUTE, verify 0. import grep 0. 미실행: requireUser(쓰는 라우트 없음) |
| 2026-10-18 | 메인 교차 확인(XML 집계 117/114/skip 0 일치, import grep 0, 금지영역 diff 0) → 커밋. 결정: service·domain 값 검증은 Failure에 INVALID_REQUEST를 필요할 때 추가 / 상태 필드는 도메인 메서드로만 바꾸도록 캡슐화(private set) — 풍부한 도메인 합의 | task 02 브리핑 |
| 2026-10-18 19:11~19:28 | task 02 워커(같은 Opus 재개): 입찰·체결·호가·체결 조회·제한. 도메인 메서드 BidRequest.of·User.requireCanBid·Bid.place/cancel/matched/requireOwner/isAheadOf·Match·TradeFilter.of, service PlaceBid·CancelBid·MatchRecorder·조회 2, infra 네이티브(잠금·최우선 상대·호가) + em.persist(merge 회피) | run.sh layered 3회 동일: infra 18/18, contract 58/117(Bid 26·Matching 16·Restriction 8/9·TradeQuery 5/11·시계 3), 실패 59 전부 NO_ROUTE, verify 0, build 통과 |
| 2026-10-18 19:28 | 가정 1 실증(워커, 일회용 1k-s42 DB에 god·layered 차례로, 직렬 요청 18개): 응답·bids 10·bid_events 19·trades 4행 id까지 동일, Hibernate SQL 101문 순서 동일(existsById→findById로 맞춘 뒤). 잠금 시점·IDENTITY INSERT 순서 동일 | 가정 1 성립(직렬 한정 — 동시성은 run.sh 4건 3회 green) |
| 2026-10-18 | 메인 교차 확인(XML 58/117·skip 0, import grep 0, 금지영역 diff 0, PlaceBidService 판정 순서 god와 대조) → 커밋. 판단: god에 없는 도메인 check(ACTIVE·반대쪽) 허용 — 잠금 아래 도달 불가 경로의 불변식 단언, 리뷰에 표시 / 상태 아닌 필드 public var 유지(churn 회피) / task 03은 TransactionTemplate으로 트랜잭션 분할 | task 03 브리핑 |
| 2026-10-18 19:29~19:44 | task 03 워커(같은 Opus 재개): 결제·포인트·주문 조회·PgHttpClient. 도메인 OrderRequest·PayableTrades·OrderAmounts·PurchaseOrder 전이·PointLedgerEntry allocate/confirm/release·PgResult.of(응답 해석), service PaymentService(TransactionTemplate tx1 → PG → tx2 → 실패 시 recordUnknown → RR 재조회)·PaymentOutcomes·PgCaller·OrderReader | run.sh layered 2회 동일: infra 18/18, contract 100/117(Idem 15·Point 14·Restriction 9 전부, Payment 8/11·TradeQuery 9/11), 실패 17 전부 /admin/batches NO_ROUTE, verify 0, build 통과 |
| 2026-10-18 19:44 | 결제 동치 비교(워커, 일회용 DB·가짜 PG 지연 3초/앱 타임아웃 2초, 직렬 13시나리오): 응답 25·orders 4·order_items 5·pg_transactions 4·point_ledger 8·allocations 3·balances 1·trades 5 동일, SQL 240문 순서 동일 | 미비교: 반영 실패 재기록·같은 키 동시 대기 → task 05 장애 주입 |
| 2026-10-18 | 메인 교차 확인(XML 100/117·skip 0, import grep 0, 금지영역 diff 0, PaymentService 트랜잭션 경계 god payOrder와 대조) → 커밋. 판단: Payment 3·TradeQuery 2는 배치 의존이라 task 04 판정(god와 같은 이월) / 상태 전이 check 추가분 리뷰 표시 / @Repository 예외 변환·트랜잭션 밖 findById의 readOnly tx는 SQL 동일·결과 동일로 수용, 리뷰 표시 | task 04 브리핑 |
| 2026-10-18 19:47~20:14 | task 04 워커(같은 Opus 재개): 배치 3종(BatchService 디스패처·BidExpiry·PaymentDue·PgInquiry, KeysetBatchTargets LIMIT 500·upTo 고정), StartupRecovery(service 패키지 — infra→service 의존 회피), 도메인 Bid.expire/cancelForUnpaid/reopenForUnpaid·Trade.isExpirableUnpaid/expireUnpaid·BuyerWarning.issue·User.restrictIfWarned·PgTransaction.inquiry | run.sh layered 2회 exit 0: infra 18/18, contract 117/117, verify 0, build 통과. bid-expiry 1k 시드 1,156건 13.3초·12.2초(god 기록 12.3초) |
| 2026-10-18 20:14 | 동치 비교(워커): 배치 시나리오 8단계 — 응답 27·bids 12·bid_events 544·trades 6·시드 상태 md5·warnings 5·users 1004·orders 2·items 2·pg 4·ledger 4·alloc 1·balances 816 동일, SQL 2,839문 순서 동일 / 기동 복구(PG 지연 60초 중 kill -9 → 재기동 → pg-inquiry): 기동 SQL 15문·전환·조회 후 행 동일 | 차이 0. 처음 동시 전송 시 주문 id 순서만 달랐음 = 하네스 비결정(순서 고정 후 동일) |
| 2026-10-18 | 메인 교차 확인(XML 117/117·skip 0, import grep 0, 금지영역 diff 0) → 커밋 42609b2. 판단: StartupRecovery를 service에 둔 것 수용 — 명세 §2 infra 예시("기동 복구 훅")보다 같은 §2의 의존 방향(api→service→domain←infra)이 우선, 훅은 트랜잭션 조율이라 service 성격 / 기동 시 clock.now 전달은 조건 미사용으로 수용 | task 05: 장애 주입(워커) ∥ 리뷰 루프 1 |
| 2026-10-18 20:20 | task 05 시작: 장애 주입 검증(같은 워커, 코드 수정 금지 — 결함은 보고만) ∥ 리뷰 루프 1(Opus 새 워커 ∥ codex) | |
| 2026-10-18 20:26 | Opus L1: F1~F4 + OQ4(정확성 결함 0 판정) / codex L1 1차 성공: F1·F2 + OQ1 | 종합(아래 ledger): 채택 6·기각 1·open question 처리. 명세 충돌 2건 사용자 판단 요청 |
| 2026-10-18 20:27 | task 05 장애 주입(워커, 42609b2 재빌드, 코드 수정 없음): (1) 반영 실패 A(APPROVE INSERT 실패)·B(PAID UPDATE flush 실패)·C(재기록도 실패 → 400 UNEXPECTED, PAYING 잔류, 같은 키 재요청 14초 대기 뒤 400, 재기동 전환, pg-inquiry PAID) god·layered 응답·덤프·로그 건수 동일 (2) 같은 키 동시 30회 — 같은 구매자 {201 PAID, 200 PAID}·다른 구매자 {201, 409} 30/30, 키당 주문 1, 5xx·UNEXPECTED 0, 두 앱 동일 (3) bid-expiry 1k 시드 1,132건: god 13.4·13.1초, layered 14.0·13.7초(+4%, 원인 미확정) | 결함 0. 1c(반영 커밋 후 예외 → 재기록) 트리거로 재현 불가 → 가드 조건 코드 대조(layered PaymentService.kt:129 ↔ god Marketplace.kt:374)로 대체, 생략한 검증에 기록. 4%는 규모 비교에 기록, 프로파일링은 NEXT |
| 2026-10-18 | 사용자: 기동 훅 "앱 루트 패키지에 훅" → 재합의 1(명세 §2) / 상태 단언 "유지하고 차이로 기록" → 재합의 2(명세 §2 허용 차이) | codex 종합 감사로 |
| 2026-10-18 | codex 종합 감사 L1 시작. 미러 `_packet/spec.md`를 재합의 반영본으로 교체. 절차 편차: 감사 입력의 Opus 원문은 finding 4건 원문 + 렌즈·OQ 절은 file:line 유지한 축약 | |
| 2026-10-18 | codex 종합 감사 L1: A1(L1-2 범위 과다 — 잔액 행 조회·생성·저장은 저장 조율, 값 변경은 이미 PointBalance.change) · A2(완료 게이트 증거 미기재) | A1 채택: L1-2에서 잔액 행 생성/변경 분기 제외(service 조율로 유지). A2 채택: 마감 시 §5 게이트별 결과(infra 18·verify 76·skip 0·god 회귀·build·금지영역 diff·재점검)를 log에 명시. L1 수정 브리핑 |
| 2026-10-18 20:44~21:00 | L1 수정 워커(같은 Opus): PurchaseOrder.abandonPayment(UNKNOWN 기록 판정 단일화), OrderItem.markPaid→EARN 행·PointLedgerEntry.pendingTotal, PgOutcome enum(문자열 분기 제거), isPaying/isUnknown, Bid.isMatchable, 앱 루트 StartupRecovery → service StalePaymentRecovery, ProductSizeRepository.lock 없으면 404, ClockService 이동 후 now(), KeysetBatchTargets @Component | run.sh layered 2회 exit 0(infra 18·contract 117·skip 0·verify 0), build 통과, import grep 0(앱 루트 → service 1건 = 재합의 1). god 동치 회귀 run/pay/batch/recov/fail 응답·덤프·SQL 동일(fail SQL 670 vs 666 = 같은 키 대기 폴링 횟수, 폴링 제외 190문 동일), conc 10회×2 동일. 41파일 2,762줄 |
| 2026-10-18 | 메인 교차 확인(XML 117/117, import grep, 금지영역 diff 0) → 커밋 b476a38. 판단: isMatchable 수용(복구 직후 ACTIVE와 같은 뜻) / earns 비었을 때 잔액 미갱신 분기는 저장 조율로 service 유지 / 영속 예외가 API까지 오면 UNEXPECTED_ERROR message·로그 예외 타입만 god와 다름 — ADR-0003 §2는 code·상태만 계약(message는 설명), 관찰 차이로 기록 | 루프 2 |
| 2026-10-18 21:05 | 리뷰 루프 2: Opus(새 워커) ∥ codex 시작. 루프 1 미러 삭제 대상 | |
| 2026-10-18 21:12 | L2 결과: codex finding 0 + OQ(실행 기록) / Opus F1·F2 + OQ4 | 종합: 신규 채택 2(L2-1 사용자 결정 "infra에서 em.find로 맞춤" → 명세 재합의 3, L2-2). L1-x2 기각 번복. codex 종합 감사 시작 |
| 2026-10-18 | codex 종합 감사 L2: A1(L2-x1 범위 밖 판정 근거 부족) · A2(codex OQ를 범위 밖으로 닫은 표기 부정확) | A1 채택 → L2-3. A2 채택: §5 실행 증거는 "마감 게이트 미검증"으로 두고 마감 시 항목별 기록 후 닫음. L2 수정 브리핑 |
| 2026-10-18 21:32~21:48 | L2 수정 워커(같은 Opus): infra findById 7곳·체결 목록 파생 쿼리 4개 → em.find/em.createQuery(god JPQL), JpaRepository 인터페이스 7개 삭제(Spring Data import 0), BidRequest.instant 삭제, MarketClock.normalize/next(도메인 판정) + FrozenClock 동기화만 | run.sh layered 2회 exit 0(117/117·skip 0·verify 0), build 통과, grep 0. DB 문장 로그(log_statement=all) 비교: run 148문·pay 348문 god와 완전 동일(수정 전 180·402문 — BEGIN READ ONLY/COMMIT 17·28쌍 추가였음). 동치 회귀 run/pay/batch/recov/fail 동일. bid-expiry god 13.69초 vs layered 13.81초(+0.9%, 노이즈 범위). 41파일 2,756줄. 하네스 변경: run-one·pay-one에 PGLOG·JAR 옵션(기본값 동작 동일) |
| 2026-10-19 | 메인 교차 확인(XML 117/117, import·Spring Data grep 0, 금지영역 diff 0) → 커밋. 사용자: Spring Data 미사용 결과 확인 → "이대로(EntityManager)" → 명세 §0 재합의 3 보충. 판단: infra 5파일 반복 주석 2줄은 유지(파일별 독해 단위) | 루프 3(마지막) |
| 2026-10-19 | 리뷰 루프 3(마지막): Opus(새 워커) ∥ codex 시작 | |
| 2026-10-19 | L3 결과: codex 1차 bwrap 오류(RTM_NEWADDR) → 45초 뒤 2차 성공 F1 / Opus F1·F2 + OQ3 | 종합: 신규 채택 2 → 3루프 종료 조건 미충족(review unresolved). 사용자: "타깃 수정 + 재점검 1회 후 종료". codex 종합 감사 ∥ L3 수정 워커 |
| 2026-10-19 | codex 종합 감사 L3: A1(L3-2 부분 채택 누락 — 0 포인트·0 적립 가드도 변경 여부 규칙) · A2(L3-x1 기각 근거 오류 — 응답 message에 예외 클래스 노출) | 둘 다 채택: L3-2 확대, L3-3 신규(루프 1 L1-6 판단 "message 차이 관찰 기록"도 함께 정정). 수정 워커에 추가 전달 |
| 2026-10-19 00:30~00:48 | L3 수정 워커(같은 Opus): PlaceBid·CancelBid TransactionTemplate(검증 → now → tx, @Transactional 0), BalanceDelta·reservationDelta·release Δ·pendingDelta(없으면 null)·PointBalance.apply/opened·OrderAmounts.pointReservation(0이면 null), 저장소 어댑터 11개 @Component(@Repository 0) | run.sh layered 2회 exit 0(117/117·skip 0·verify 0), build 통과, grep 0. 동치 회귀 run/pay(DB 문장 148·348 동일)/batch/recov/fail(폴링 240 vs 240)/conc 10×2 동일. l3-one(형식 오류 400 5·404 2·트리거 영속 예외 2): 응답 본문·DB 문장 82문 god와 동일, 직전 커밋은 영속 예외 message만 다름(JpaSystemException vs GenericJDBCException). L3-1은 DB 관측 차이 원래 없었음(pgjdbc 지연 BEGIN) — 구조 정합 수정. 41파일 2,783줄 |
| 2026-10-19 | 메인 교차 확인(XML 117/117, grep, 금지영역 diff 0) → 커밋. 판단: 잔액 행 없음 → 생성 분기는 저장 조율로 service 유지(값은 PointBalance.opened) | codex post-fix 재점검 |
| 2026-10-19 | codex post-fix 재점검(L3 수정 hunk·인접 호출부): 신규 0 | 리뷰 종료: 3루프 종료 조건 미충족(L3 신규 채택 3) → 사용자 결정 "타깃 수정 + 재점검 1회 후 종료", 재점검 clean |
| 2026-10-19 | 마감 회귀(메인 직접): run.sh god exit 0(infra 18/18·contract 117/117·skip 0·verify 76 위반 0) / run.sh layered exit 0(같은 수치) / 루트 build exit 0 / 금지영역 diff a85de22..bbd0148 0 / 잔여 컨테이너 ccle-postgres-1뿐 | 완료 요약 초안(Opus) 반영, 스니펫 3곳 실파일 대조 |
| 2026-10-19 | 사이클 마감 ②: study-note 이슈 아카이브 — 금지영역(study-note 읽기·쓰기 금지)이라 보류(NEXT 보류 항목 유지) | |

## 리뷰 ledger

- review packet L1: base `a85de22` (HEAD `42609b2`) / OUT `scratchpad/rv.lkxJ` / mirror `scratchpad/mirror.aTfk` / 보안 스캔: 개발 기본 비밀번호 `ccle` 2건(application.yml 공개 기본값 — 오탐) / untracked 0
- review packet L2: base `a85de22` (HEAD `b476a38`) / OUT `scratchpad/rv.9tY1` / mirror `scratchpad/mirror.hZD6` / 보안 스캔 동일 오탐 2건 / 참고 diff `42609b2..HEAD`
- review packet L3: base `a85de22` (HEAD `6f5a3a7`) / OUT `scratchpad/rv.eXua` / mirror `scratchpad/mirror.8ac4` / 보안 스캔 동일 오탐 2건 / 참고 diff `b476a38..HEAD`

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| L1-1 | 1 | opus F1 | service/PaymentService.kt:129, service/StartupRecovery.kt:41 | 채택 — "PAYING이고 APPROVE 행 없을 때만 UNKNOWN 기록" 규칙이 service 두 곳에 중복(spec §2 규칙은 도메인) | fixed | 1 |
| L1-2 | 1 | opus F2 | service/PaymentOutcomes.kt:36-43, PaymentService.kt:70-74·86, BatchService.kt:113·145-151, PointBook.kt:34-39 | 채택 — 업무 규칙(적립 행 생성 조건, PG 결과 분기, 결정됨 판정, 재매칭 필요 판정, 잔액 행 생성/변경)이 service에 남음(spec §2) — 감사 A1로 잔액 행 생성/변경 분기는 제외(저장 조율) | fixed | 1 |
| L1-3 | 1 | opus F3 · codex F1 | service/StartupRecovery.kt:11·24-31 | 채택 — 생명주기 훅이 service에. 재합의 1: 훅은 앱 루트, 전환 절차는 service 유스케이스 | fixed | 1 |
| L1-4 | 1 | opus F4 | service/CancelBidService.kt:28, BatchService.kt:60·95 | 채택 — lock() Boolean 무시(god lockProductSize는 없으면 404). FK로 현재 도달 불가지만 무음 | fixed | 1 |
| L1-5 | 1 | codex F2 | infra/FrozenClock.kt:27, service/ClockService.kt | 채택 — 시계 이동 응답을 잠금 안 current로 반환, god는 이동 후 now() 재조회(Marketplace.kt:84) → 동시 PUT에서 응답 다름(spec §2 god 동치) | fixed | 1 |
| L1-6 | 1 | opus OQ3 | infra/KeysetBatchTargets.kt:14 | 채택 — @Repository 프록시가 배치 핸들러 예외를 DataAccessException으로 변환(message 클래스명이 god와 다름). 영속 예외 변환이 필요 없는 컴포넌트 | fixed | 1 |
| L1-x1 | 1 | opus OQ2 | domain check 11곳 | 사용자 결정 — 유지, 허용 차이로 명세 기록(재합의 2) | — | |
| L1-x2 | 1 | opus OQ4 | UserService.kt:15 등 findById | 기각(루프 2에서 L2-1로 번복 — SQL 로그에 BEGIN/COMMIT 왕복이 안 보여 동일로 오판) — 트랜잭션 밖 findById가 readOnly tx를 엶. SQL·결과 동일(task 03·04 SQL 순서 비교), 성능 영향은 bid-expiry +4% 기록으로 관찰 | — | |
| L2-1 | 2 | opus F1 | infra/JpaUserRepository.kt:13, JpaBidRepositories.kt:24·39, JpaOrderRepositories.kt:22, JpaPointRepositories.kt:58 | 채택(사용자 결정, 재합의 3) — 트랜잭션 밖 findById가 호출마다 readOnly 트랜잭션(god는 autocommit em.find 1문) → infra를 em.find로 | fixed | 2 |
| L2-2 | 2 | opus F2 | domain/Bid.kt:41 | 채택 — BidRequest.instant 미사용(죽은 코드) | fixed | 2 |
| L2-x1 | 2 | opus OQ2 | infra/FrozenClock.kt:22-25 | 범위 밖(감사 A1로 번복 → L2-3) — CLOCK_BACKWARD 판정이 infra 구현에: 상태와 원자적 확인·변경이 필요(task 01 판단, god도 FrozenClock 안), domain 인터페이스 계약 주석 | — | |
| L2-x2 | 2 | opus OQ3 | domain/PaymentGateway.kt | 범위 밖 — PG 응답 해석(200·404)은 ADR-0003 PG 계약의 업무 규칙이라 domain(명세 §2·god 명세 "판단은 갓 클래스 안"과 같은 취지) | — | |
| L2-x3 | 2 | opus OQ4 | domain/Order.kt:32 | 기각 — 취향(동작 차이 없음, 리뷰어도 finding에서 제외) | — | |
| L2-3 | 2 | 감사 A1 (opus OQ2) | infra/FrozenClock.kt:22-27, domain/MarketClock.kt:12-13 | 채택 — 시계 앞 시각 거부 규칙이 infra에만(spec §0·§2 검증은 도메인). 원자성은 infra 동기화가 유지하고 판정은 도메인으로 | fixed | 2 |
| L3-1 | 3 | codex F1 · opus F1 | service/PlaceBidService.kt:31-34, CancelBidService.kt:21-23 | 채택 — @Transactional이라 값 검증(400)·시계 읽기가 트랜잭션 안(god Marketplace.kt:103-118은 밖) → 형식 오류에도 BEGIN/ROLLBACK, 시계 읽기 시점 다름. 다른 유스케이스처럼 TransactionTemplate. (task 02 OQ3에서 "관측 차이 없음"으로 수용했던 판단 번복) | fixed | 3 |
| L3-2 | 3 | opus F2 | service/PointBook.kt:15-40 | 부분 채택 — 잔액 변화량(예약 −, 해제 +)·첫 행 생성 값은 도메인으로(예약·해제 행이 Δ를 돌려주고 PointBalance 팩토리). 조회·저장 순서는 service(루프 1 감사 A1 판단 유지). 0 포인트·0 적립 가드는 저장 조율로 유지 → 감사 A1로 확대: 변경 필요 여부도 도메인이 결정 | fixed | 3 |
| L3-x1 | 3 | opus OQ1 | @Repository 예외 변환 | 기각 → 감사 A2로 번복(L3-3) — "결과 동일" 근거 오류: UNEXPECTED_ERROR 본문 message에 예외 클래스가 들어가 클라이언트 응답이 다름 | — | |
| L3-3 | 3 | 감사 A2 (opus OQ1) | infra 저장소 어댑터 @Repository, api/ErrorHandling.kt unexpected | 채택 — 영속 예외 변환으로 UNEXPECTED_ERROR 본문 message가 god와 다름 → 어댑터 @Component, 예외 변환 제거 | fixed | 3 |
| L3-x2 | 3 | opus OQ2 | infra/JpaPointRepositories.kt:17 | 기각 — 취향(동작 영향 없음) | — | |

## 생략한 검증

- 결과 반영 트랜잭션이 커밋됐는데 앱이 예외를 받은 뒤 재기록하는 경로: DB 트리거로 만들 수 없음(커밋 전 실패만 가능). COMMIT 응답을 끊는 프록시·앱 내 주입은 하네스·코드 변경이라 하지 않음. 대체: 가드 조건 코드 대조(layered PaymentService.kt:129 ↔ god Marketplace.kt:374) + 실험 A·B·C에서 재기록 경로 실행

## 완료 요약

### §5 검증 게이트별 결과

| 게이트 | 결과 | 근거 |
|---|---|---|
| run.sh layered | exit 0 — infra 18/18, contract 117/117, skip 0, verify 76 위반 0. 워커 2회 연속 + 메인 1회(bbd0148) | 타임라인 L3 수정·마감 회귀 |
| run.sh god 불변 | exit 0 — 같은 수치, 금지영역 diff 0 | 마감 회귀 |
| 루트 build | exit 0(layered 스모크 validate) | 마감 회귀 |
| 그린 위장 | skip 0, 2회+ 연속 green, 테스트 분기·벽시계 grep 0 | 각 task 행 |
| 계층 경계 | domain → Spring·infra import 0, api·service → infra 0, infra → service·api 0, 앱 루트 훅 → service 1(재합의 1), `@Transactional`·`@Repository`·Spring Data 0 | L3 수정 행 |
| 장애 주입 | 반영 실패 A·B·C, kill -9 기동 전환, 같은 키 동시 30회×2 — god와 동일. 미실행: 반영 커밋 뒤 예외(생략한 검증) | task 05 행 |
| god 동치 | 응답·행 덤프·Hibernate SQL·DB 문장 로그(BEGIN/COMMIT/SET 포함) 동일. 차이는 같은 키 대기 폴링 횟수(타이밍)뿐 | L2·L3 수정 행 |
| 리뷰 | 듀얼 3루프 + 감사 3회 + post-fix 재점검 clean. 종료 조건 미충족분은 사용자 결정 | ledger |

### 무엇이 됐나

- `layered`가 god와 같은 1단계 계약을 통과하고, 같은 요청에 같은 응답·같은 DB 행·같은 SQL·같은 트랜잭션 왕복을 낸다. 구조만 다르다.
- 규모(main, git 추적 `.kt`):

| | god | layered |
|---|---|---|
| 파일 | 13 | 41 |
| 줄(전체 / 공백·주석 제외) | 1,672 / 1,319 | 2,783 / 2,084 |
| class(enum 제외) / interface / enum | 36 / 0 / 0 | 82 / 14 / 3 |
| 가장 큰 파일 | Marketplace.kt 984 | domain/Order.kt 335 |

- layered 계층별: api 7파일 485줄 · service 14파일 750줄 · domain 10파일 1,071줄 · infra 8파일 448줄 · 루트 2파일 29줄.
- bid-expiry 1k 시드 1,132건: god 13.69초, layered 13.81초(+0.9%, L2 전 +4%).

### 핵심 diff (실파일 발췌, HEAD bbd0148)

결제 반영 실패 → UNKNOWN 재기록. god `god/src/main/kotlin/com/ccle/god/Marketplace.kt:370-388`(같은 판정이 :965 기동 전환에 한 번 더):

```kotlin
                tx.execute {
                    val order = em.find(PurchaseOrder::class.java, orderId)
                    lockUser(order.buyerId)
                    em.refresh(order)
                    if (order.status == "PAYING" && approveRowCount(orderId) == 0L) {
                        em.persist(
                            PgTransaction(
                                ...
                            )
                        )
                        order.status = "UNKNOWN"
                    }
                }
```

layered `service/PaymentService.kt:126-131` + `domain/Order.kt:176-180`(기동 전환 `service/StalePaymentRecovery.kt:36`도 같은 메서드):

```kotlin
            tx.execute {
                val order = orders.find(orderId)!!
                users.lock(order.buyerId)
                orders.reload(order)
                order.abandonPayment({ pgTransactions.approveCount(orderId) }, requestedAt = now)?.let(pgTransactions::add)
            }

    fun abandonPayment(approveRowCount: () -> Long, requestedAt: Instant): PgTransaction? {
        if (!isPaying() || approveRowCount() > 0) return null
        markUnknown()
        return PgTransaction.unknownApproval(this, requestedAt)
    }
```

포인트 예약. god `Marketplace.kt:579` `if (point > 0) reservePoints(...)` + `:712-742`(USE 생성·FIFO 배분·잔액 −amount를 한 메서드에서) → layered `service/PointBook.kt:16-20`:

```kotlin
    fun reserve(use: PointLedgerEntry, now: Instant) {
        points.add(use)
        use.allocate(points.usableBundles(use.userId, now)).forEach(points::addAllocation)
        changeBalance(use.userId, use.reservationDelta(), now)
    }
```

배분 규칙은 `domain/Point.kt:37-50` `allocate`, 잔액 영향은 `:59-62` `reservationDelta`, 0 포인트 판정은 `domain/Order.kt:69-70` `pointReservation`.

입찰 등록: god `Marketplace.kt:103-147`(검증·잠금·제한·상대·생성이 한 메서드) → layered `service/PlaceBidService.kt:33-50`(검증 `BidRequest.of` → now → tx{잠금 → `User.requireCanBid` → 상대 → `Bid.place` → 저장 → `MatchRecorder`}).

### 배운 것 (관측 근거)

- Hibernate SQL 로그만으로는 트랜잭션 왕복 차이가 안 보인다. 같은 101·240문이었는데 `log_statement=all`에서 트랜잭션 밖 `findById`마다 `BEGIN READ ONLY … COMMIT`이 붙어 148→180·348→402문이었다. 루프 1에서 이것을 "SQL 동일"로 기각한 판단이 틀렸다(L1-x2 번복).
- `@Repository` 영속 예외 변환은 계약(code·상태)은 지키지만 `UNEXPECTED_ERROR` 응답 message를 바꾼다(`JpaSystemException` vs `GenericJDBCException`). 루프 1·3에서 "로그만 다름"으로 두 번 오판했고 감사가 잡았다.
- JPA 쓰기 순서는 em 호출 순서만 같으면 같다. god에 없던 SELECT 한 문장(`existsById` count, merge `save`, 객체를 받는 팩토리)이 순서를 바꾸는 유일한 원인이었다.
- 비결정은 앱이 아니라 하네스에서 나왔다(동시 전송한 결제의 주문 id 순서, 대기 폴링 횟수). 비교 전에 요청 순서를 고정하고 폴링 구간을 뺐다.
- 레이어드의 "규칙은 도메인" 경계는 리뷰마다 한 겹씩 더 드러났다(UNKNOWN 판정 → PG 결과 분기 → 시계 판정 → 잔액 변화량·0 가드). 기준을 처음부터 "변경 여부·값을 정하는 것은 도메인, 조회·저장 순서는 service"로 적었으면 루프가 줄었을 것이다.

### 남은 것

- god와의 허용 차이: 도메인 상태 단언(재합의 2 — 불변식이 깨진 데이터에서만 다름), 앱 루트 기동 훅(재합의 1, 구조).
- 생략한 검증 1건(반영 커밋 뒤 예외 → 재기록, 위).
- 동치 비교 하네스는 scratchpad(세션 임시)에만 있다 — 저장소로 옮길지는 NEXT.
