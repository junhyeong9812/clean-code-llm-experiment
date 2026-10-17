# log — god-class-stage1

## 타임라인

| 시각 | 사건 | 결과/결정 |
|------|------|----------|
| 2026-10-17 | 착수: NEXT N1. 브랜치 `feat/god-class-stage1`(main `88aad98`에서). ADR-0001·0002 §1·§8·0003 전문, god 모듈(빈 앱), 스키마 UNIQUE 확인 | 인터뷰 시작 |
| 2026-10-17 | 인터뷰 2회(8문항): JPA+SQL 혼합·자연 절차지향·계약 조항 순서·Opus 워커·god만·1단계 테이블만·행위 테스트로 충분·자연 크기 | 명세 합의, auto → SPEC=1·MODE=auto |
| 2026-10-17 | 설계 선검증 생략: 동작 불변식 신설 없음 — 전부 ADR-0002/0003 정의, 강제 위치 `tools/seedgen/verify.sql`(76개)·`behavior-tests/src/test/kotlin/com/ccle/behavior/contract/*`(117) | |
| 2026-10-17 09:08~09:16 | task 01 워커(Opus): 엔티티 14·FrozenClock·AdminController·ErrorHandling·Marketplace 뼈대(411줄) | 가정 2 실증: validate 통과(반증: 열 이름 바꾸면 실패). run.sh god: infra 18/18, contract 3/117(시계 3), 나머지 114 = 라우트 없음 404+code, verify 0 |
| 2026-10-17 | 메인 교차 확인(XML 집계 일치, 벽시계 호출 0) → 커밋. 판단: 계약 밖 UNEXPECTED_ERROR(400)·METHOD_NOT_ALLOWED(405) 유지(§2는 5xx 금지만 정함, 원인 로그) / task 02 브리핑: 본문은 requireUser 뒤 파싱(401→400), Jackson 강제 변환 끔, 시계 마이크로초 절삭 | |
| 2026-10-17 09:17~09:25 | task 02 워커(같은 Opus 재개): 입찰·체결·호가·체결 조회·제한, Json.kt(401 뒤 파싱·정수만), 시계 마이크로초 절삭 | run.sh god 2회 동일: infra 18/18, contract 58/117(Bid 26·Matching 16·Restriction 8/9·TradeQuery 5/11·시계 3), 동시성 4종 2회 green, verify 0, 실패 59 전부 /orders·/me/points·/admin/batches 404 |
| 2026-10-17 | 메인 확인(XML 집계 일치, placeBid·cancelBid·matchBids·잠금 코드 열람) → 커밋. 결정: TradeQuery 6·Restriction 1의 acceptance는 task 03·04 시점 판정으로 이월(결제·배치 엔드포인트 의존) | |
| 2026-10-17 09:27~09:36 | task 03 워커: 결제·포인트·주문 조회, PgClient(JDK HttpClient), 구매자 행 FOR UPDATE로 결제 직렬화, 트랜잭션 1(주문·예약) 커밋 → PG → 트랜잭션 2 | run.sh god 2회 동일: infra 18/18, contract 100/117(Idem 15·Point 14·Restriction 9 전부, Payment 8/11·TradeQuery 9/11), verify 0, ERROR 0. 실패 17 전부 /admin/batches 404 |
| 2026-10-17 | 메인 확인(XML 집계 일치, payOrder 열람) → 커밋. ADR 공백 발견(워커 보고): PG 대기 중 PAYING 주문의 체결을 payment-due가 만료시킬 수 있음 → 사용자 판단 요청 | |
| 2026-10-17 | 사용자: "PAYING도 만료에서 제외" → 재합의 1: ADR-0003 §7 표·§9의 6 개정, 명세 §4 기록 | task 04 브리핑에 반영 |
| 2026-10-17 14:15~14:24 | task 04 워커: 배치 3종(재합의 1 반영), PgClient.inquire, 자기 점검(밖으로 샌 규칙·미사용 코드·테스트 분기 0) | run.sh god 2회 exit 0: infra 18/18, contract 117/117, verify 0, ERROR 0. 루트 build 통과. 배치 최대 bid-expiry 12.3초(시드 1,156건) |
| 2026-10-17 | 메인: XML 집계 일치 → 커밋 d3c88f4. run.sh layered 87초: contract 117 실패·verify 0(이전과 동일), 금지영역 diff(88aad98..) 0 | 리뷰 루프로. 워커 보고: 경고 식 경계(같은 시각 제한 직후 경고)에서 ADR 원문(엄격 >)과 생성기 해석이 다름 — 테스트·verify 영향 없음, 리뷰 판단에 넘김 |
| 2026-10-17 | 리뷰 루프 1: Opus ∥ codex 병렬 시작 | |
| 2026-10-17 | codex L1 1차 bwrap 오류(RTM_NEWADDR, 파일 0개 읽음) → 재시도 성공(F1~F3). Opus L1 F1~F6 + OQ4 | 메인 종합: 채택 9·기각 1·범위 밖 2. 라우트 404 code 단언 테스트 없음 확인(grep). codex 종합 감사 시작(입력에 Opus 원문 대신 채택표 요지 — 절차 편차) |
| 2026-10-17 | codex 종합 감사 L1: A1(크래시 PAYING 복구 누락 판정 오류)·A2(대기 상한 시 동작 미정)·A3(수수료 price*3 조용한 넘침 누락) | 3건 채택. A1·A2는 ADR 범위 → 사용자: "기동 시 UNKNOWN 전환 + 진 요청 대기" → 재합의 2(ADR-0003 §9의 7) |
| 2026-10-17 23:10~23:21 | L1 수정 워커: 채택 11건 수정(mulDiv BigInteger·addExact, waitWhilePaying 폴링(상한 PG 타임아웃×2+5초), StartupRecovery(SmartInitializingSingleton — 포트 열기 전), callPg 전 예외 → UNKNOWN, pgResult 분류 이동, forEachTarget 키셋 500·upTo 고정, NO_ROUTE, 엔티티 2 삭제, em.flush) | run.sh god 2회 exit 0(117/117·verify 0·ERROR 0). 수동: 4e18원 결제 201·적립 4e16 일치, kill -9 뒤 기동 전환 → pg-inquiry로 PAID(paid_at = created_at), 같은 키 동시 2건 둘 다 UNKNOWN(2.4초 대기), NO_ROUTE vs NOT_FOUND. 미실행: tx2 실패 재기록 경로·Long 초과 금액 |
| 2026-10-17 | 메인: XML 집계 일치, 잔여 컨테이너·포트 없음 → 커밋 | 루프 2로 |
| 2026-10-17 | 리뷰 루프 2: Opus(새 워커) ∥ codex 병렬 시작 | |
| 2026-10-17 | L2 결과: codex F1·F2 + OQ2, Opus F1·F2 + OQ6(주 경로 계약 일치·경계 위반 없음 판정) | 메인 종합: 신규 채택 3, 범위 밖 1(적립 등급 시점 — 2단계), open question 다수. codex 종합 감사 시작(이번엔 Opus 원문 포함) |
| 2026-10-17 | codex 종합 감사 L2: A1(상한 초과 시 400도 계약 위반)·A2(트랜잭션 밖 em.find 낡은 값) | A1 부분 채택: 상한을 넉넉히(결과 반영 포함), 초과 시 성공 위장 금지(계약 밖 오류+log.error), 무한 대기 불택 → 잔여 리스크 보고. A2 기각: open-in-view false + 트랜잭션 밖 → 공유 EM이 호출마다 새 EM(application.yml:12), 수동 검증 (d)에서 진 요청이 UNKNOWN 수신 |
| 2026-10-17 23:31~23:39 | L2 수정 워커: 재기록 잠금 뒤 PAYING·APPROVE 0 재확인, 커밋 값 재조회 뒤에만 201(아니면 IllegalStateException → 400 UNEXPECTED+log.error), 대기 상한 PG 타임아웃×2+10초·초과/인터럽트는 오류, 기동 전환 재확인 | run.sh god 2회 exit 0(117/117·verify 0). 장애 주입(일회용 컨테이너 트리거): 재기록이 기존 결과 보고 쓰지 않음(APPROVE 1행), 반영·재기록 모두 실패 → 400 UNEXPECTED·PAYING 잔류 → 재기동 전환. 미실행: 대기 상한 초과·인터럽트 |
| 2026-10-17 | 메인: XML 집계 일치, 잔여 컨테이너 없음 → 커밋 | 루프 3으로 |
| 2026-10-17 | 리뷰 루프 3(마지막): Opus(새 워커) ∥ codex 병렬 시작 | |
| 2026-10-17 | L3 결과: codex 1·2차 bwrap 오류 → 45초 뒤 3차 성공(F1~F3, 절차 편차: 재시도 2회) / Opus F1·F2 + OQ4 | 종합: 신규 채택 2(L3-1 응답 스냅샷, L3-2 교차 구매자 같은 키 예외 타입 — 검증 조건부) → 3루프 종료 조건 미충족(review unresolved). codex 종합 감사 시작, 사용자 판단 요청 |
| 2026-10-18 | codex 종합 감사 L3: A1(등급 시점 범위 밖 판정 오류 — 보존하거나 ADR에 불변 전제 명시). 감사는 _packet 읽기 실패(bwrap), 제공 원문만으로 판정 | 사용자: "타깃 수정 + 재점검 1회 후 종료", 등급은 "2단계 착수 항목으로" → ADR-0003 §10에 1단계 등급 불변 전제·2단계 착수 조건 명시(무음 이연 방지). L3 수정 워커 시작 |
| 2026-10-18 00:37~00:47 | L3 수정 워커: orderView를 snapshotTx(REPEATABLE READ·읽기 전용)로, 재시도를 orders_idempotency_key_key 위반만으로 한정 | L3-2 재현 30회×2(수정 전·후): 전부 201+409 IDEMPOTENCY_KEY_REUSED·주문 1건, 예외 = Hibernate ConstraintViolationException(persist 즉시) → 원 지적 미재현. L3-1: DB 문장 로그로 한 RR 읽기 전용 트랜잭션·연결 격리 복원 확인. run.sh god 2회 exit 0. 정보: 교차 구매자 경합마다 Hibernate SqlExceptionHelper ERROR 1줄(응답 정상) |
| 2026-10-18 | 메인: XML 집계 일치 → 커밋 f101fa5. codex post-fix 재점검(수정 hunk만) 시작 | |
| 2026-10-18 | codex post-fix 재점검(L3 수정 hunk·인접 호출부): 1차 bwrap 오류 → 재시도 성공, 신규 0 | 리뷰 종료: 3루프 종료 조건 미충족(L3 신규 채택 2) → 사용자 결정 "타깃 수정 + 재점검 1회 후 종료", 재점검 clean. 미러 전부 삭제 |
| 2026-10-18 | 마감 회귀: run.sh layered 91초 — contract 117 실패·verify 0(이전과 동일), 금지영역 diff c5324b2..f101fa5 0. 루트 build는 L3 수정 워커가 00:47경 통과 | 완료 요약 초안(Opus) 반영 |

## 리뷰 ledger

- review packet L1: base `c5324b2` (HEAD `d3c88f4`) / OUT `scratchpad/rv.kPBj` / mirror `scratchpad/mirror.HjOB` / 보안 스캔: 개발 기본 비밀번호 `ccle` 2건(application.yml, 공개 기본값 — 오탐)
- review packet L2: base `c5324b2` (HEAD `2a89797`) / OUT `scratchpad/rv.Hr4A` / mirror `scratchpad/mirror.zJLq` / 보안 스캔 동일 오탐 2건 / 참고 diff `d3c88f4..HEAD`
- review packet L3: base `c5324b2` (HEAD `883d785`) / OUT `scratchpad/rv.ypND` / mirror `scratchpad/mirror.xflJ` / 보안 스캔 동일 오탐 2건 / 참고 diff `2a89797..HEAD`

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| L1-1 | 1 | codex F1 | Marketplace.kt:414 | 채택 — 카드 몫·적립 중간 곱셈 Long 넘침(유효 금액에서 400 UNEXPECTED) | fixed |  1 |
| L1-2 | 1 | codex F2 · opus OQ1 | Marketplace.kt:319·365-373 | 채택 — 같은 키 동시 요청 진 쪽이 PAYING을 받음(ADR-0003 §5.1 "이긴 쪽의 결과") | fixed |  1 |
| L1-3 | 1 | codex F3 | Marketplace.kt:665·693·760 | 채택(메모리) — 배치 대상 전체 적재 → 키셋 페이지. 인덱스 부재는 스키마 금지로 범위 밖 | fixed |  1 |
| L1-4 | 1 | opus F1 | Marketplace.kt:321-350, PgClient.kt:62-68 | 채택 — PG 호출 비IO 예외·tx2 실패 시 PAYING 영구 잔류 | fixed |  1 |
| L1-5 | 1 | opus F2 | Marketplace.kt:693-699·716-719 | 채택 — order_items 조회가 부분 인덱스 못 씀(잠금 보유 중 스캔) | fixed |  1 |
| L1-6 | 1 | opus F3 | ErrorHandling.kt:35·77 | 채택 — 라우트 404도 NOT_FOUND(ADR-0003 §2 구분 무력화) | fixed |  1 |
| L1-7 | 1 | opus F4 | PgClient.kt:24·61-81 | 채택 — PG 결과 해석이 갓 클래스 밖(spec §2) | fixed |  1 |
| L1-8 | 1 | opus F5 | Entities.kt:29-49 | 채택 — Category·Product 미사용 | fixed |  1 |
| L1-9 | 1 | opus F6 | Marketplace.kt:722·749-750 | 채택 — 재매칭 전 옛 체결 반영이 암묵 auto-flush 의존 | fixed |  1 |
| L1-10 | 1 | 감사 A3 | Marketplace.kt:395-399 | 채택 — 수수료 price*3 조용한 넘침(L1-1과 함께 수정) | fixed |  1 |
| L1-11 | 1 | 감사 A1·A2 | Marketplace.kt:310-323·365-373·759-760 | 채택 — 크래시 잔류 PAYING 복구·진 요청 대기 동작(재합의 2) | fixed |  1 |
| L2-1 | 2 | codex F1 | Marketplace.kt:386·324-325 | 채택 — 대기 상한 초과·인터럽트 시 200 PAYING | fixed |  2 |
| L2-2 | 2 | codex F2 | Marketplace.kt:365·374 | 채택 — UNKNOWN 재기록 실패해도 201 PAYING | fixed |  2 |
| L2-3 | 2 | opus F1 | Marketplace.kt:354-369 | 채택 — 재기록이 PAYING·APPROVE 부재 재확인 없이 덮어씀(PAID→UNKNOWN, pg-inquiry 영구 정지) | fixed |  2 |
| L2-4 | 2 | 감사 A1 | Marketplace.kt:386 | 부분 채택 — 대기 상한이 결과 반영 시간을 보장 못 함 → 상한 확대, 초과 시 성공 위장 금지 | fixed |  2 |
| L2-x2 | 2 | 감사 A2 | Marketplace.kt:386 | 기각 — 트랜잭션 밖 공유 EM은 호출마다 새 EM(open-in-view false), 수동 검증 (d) | — | |
| L2-x1 | 2 | opus F2 · codex OQ2 | Marketplace.kt:569 | 범위 밖 — 적립률 등급 시점(1단계에 등급 변경 경로 없음) → NEXT 2단계 | — | |
| L3-1 | 3 | opus F1 | Marketplace.kt:651-674 | 채택 — orderView가 주문·항목을 다른 스냅샷에서 읽음(UNKNOWN+PAID 응답 가능) | fixed |  3 |
| L3-2 | 3 | codex F1 | Marketplace.kt:312-319 | 채택(검증 조건부) — 교차 구매자 같은 키 동시 요청 제약 위반 예외가 catch 밖일 수 있음 | fixed(미재현 — 재시도 범위 축소) |  3 |
| L3-x1 | 3 | codex F2 · opus F2 · 감사 A1 | Marketplace.kt:592 | user-deferred — 사용자 "2단계 착수 항목으로", ADR-0003 §10에 전제 명시 | user-deferred | |
| L3-x2 | 3 | codex F3 | Marketplace.kt:704 | open question — 포인트 묶음 전체 적재(상한 근거 없음) | — | |
| M-1 | 마감 | main | Marketplace.kt 경고 식(task 04 보고) | 판정 — 같은 시각 제한 직후 경고 계수: ADR-0003 §7 원문(엄격 >) 유지, 생성기 해석과 차이 → NEXT N3 기록(테스트·verify 영향 없음) | — | |
| L1-x1 | 1 | opus OQ2 | ErrorHandling.kt:28-31 | 기각 — 계약 밖 오류 400 UNEXPECTED_ERROR는 기존 결정(ADR §2는 5xx 금지만) | — | |
| L1-x2 | 1 | opus OQ4·codex OQ (2건 1행) | Marketplace.kt:269-291 | 범위 밖 — ADR에 페이지네이션 없음 / 실행 로그는 packet 대상 아님 | — | |

## 생략한 검증

- 긴급 경로 아님(DEBT 없음). 실행으로 확인하지 못하고 코드 검토로만 확인한 경로: 같은 키 대기 상한 초과·인터럽트, 유일 제약이 아닌 PersistenceException 재던짐, L3-1의 스냅샷 경합 타이밍(격리 수준은 DB 문장 로그로 확인), Long 범위를 넘는 금액(ArithmeticException).
- 사이클 마감 ②(study-note 이슈 아카이브): 보류 — 이 세션에서 study-note 쓰기 금지(NEXT 보류·이월).

## 완료 요약

## 완료 요약

### 결론

`god` 모듈에 ADR-0003의 1단계(입찰·체결·결제·포인트·배치)를 갓 클래스 하나(`Marketplace.kt`, 984줄)로 구현했다. 명세 §1의 완료 조건을 충족했다. 최종 커밋 `f101fa5`에서 `tools/behavior/run.sh god`를 2회 연속 실행해 둘 다 exit 0이었고, infra 18/18 통과, contract 117/117 통과, verify 76개 검사 위반 0이었다. 단계별 contract green 수는 task 01 3 → task 02 58 → task 03 100 → task 04 117이다. 명세 가정 1("블라인드 계약 테스트 117개가 ADR-0003과 맞다")은 117개 전부가 green이 되면서 실증됐다. 가정 2(엔티티가 `validate`로 붙는다)는 task 01에서 실증했다. 반증으로 열 이름을 바꾸면 기동이 실패하는 것도 확인했다.

ADR-0003은 사용자 재합의로 두 번 개정했다. 둘 다 §9에 있다.

- **§9의 6 (재합의 1, task 03 뒤):** payment-due는 PAYING 주문이 있는 체결도 만료하지 않는다. task 03 워커가 공백을 발견했다. PG 응답을 기다리는 중에 배치가 체결을 만료시키면, 그 뒤 승인이 돌아와 만료된 체결이 PAID가 될 수 있었다. 사용자 결정: "PAYING도 만료에서 제외".
- **§9의 7 (재합의 2, 리뷰 루프 1의 감사 A1·A2):** PAYING 주문을 정리하는 규칙 세 가지다. 앱은 기동할 때 남은 PAYING 주문을 UNKNOWN으로 바꾸고 APPROVE UNKNOWN 행을 1개 쓴다. 같은 키로 동시에 들어온 요청 중 진 쪽은 이긴 쪽 결과가 나올 때까지 기다린다. PG 호출 중 난 예외는 종류와 상관없이 응답 없음으로 처리한다. 사용자 결정: "기동 시 UNKNOWN 전환 + 진 요청 대기". 앱 인스턴스가 하나라는 전제다.

사용자 결정 2건은 다음과 같다.

- **리뷰 종료 방식:** 루프 3에서 새로 채택한 지적이 2건 있어 3루프 종료 조건을 채우지 못했다(review unresolved). 사용자 결정: "타깃 수정 + 재점검 1회 후 종료". codex가 post-fix 재점검을 했고 새 지적은 0건이었다.
- **적립 등급 시점의 2단계 이연:** 적립률을 결제 시각의 등급으로 읽지 않는 문제다(L2·L3에서 codex·Opus·감사가 지적). 사용자 결정: "2단계 착수 항목으로". 조용히 미루지 않도록 ADR-0003 §10에 두 가지를 적었다. 하나는 "1단계에는 `users.grade`를 바꾸는 경로가 없다"는 전제이고, 다른 하나는 2단계 착수 조건(적립률을 `user_grade_history` 기준으로 읽게 바꾸기)이다(`dd573f9`).

기준: 명세 합의 `c5324b2` → 코드 최종 `f101fa5`, 브랜치 `feat/god-class-stage1`(main `88aad98`에서 분기). god 변경은 12개 파일, +1661줄이다(`git diff c5324b2 f101fa5 --stat -- god`). 금지영역(`layered/`·`behavior-tests/`·`tools/`·`docker/schema/`)의 diff는 0이다(task 04 시점 `88aad98..` 확인). god 밖에서 바뀐 것은 docs뿐이다. ADR-0003 +7/−1, 명세 +2, log.

### 산출물

줄 수는 `wc -l` 실측값이다(작업 트리 = `f101fa5`).

| 파일 | 줄 | 역할 |
|---|---|---|
| `god/src/main/kotlin/com/ccle/god/Marketplace.kt` | 984 | 갓 클래스. 절차·규칙·저장을 모두 담는다. 사용자 확인, 시계 이동, 입찰 등록·즉시·취소·조회, 사이즈 잠금 체결, 호가·체결 조회, 결제(멱등·금액·포인트 규칙·PG 결과 해석·결과 반영과 재기록·같은 키 대기), 포인트 예약·해제·적립 예정, 주문·포인트 조회, 배치 3종(키셋 페이지), 기동 시 PAYING 전환. 줄 수 추이: 뼈대 411 → `d3c88f4` 809 → 984 |
| `god/src/main/kotlin/com/ccle/god/entity/Entities.kt` | 176 | 1단계 테이블의 JPA 엔티티 12개(users·product_sizes·bids·bid_events·trades·buyer_warnings·orders·order_items·pg_transactions·point_ledger·point_allocations·point_balances). 처음 14개에서 쓰지 않는 Category·Product를 L1-8로 삭제 |
| `god/src/main/kotlin/com/ccle/god/web/Json.kt` | 93 | 응답 시각 형식(`iso`), 본문 파싱 도우미. 401 확인 뒤에 파싱하고, 숫자는 정수 JSON만 받으며, 형식이 틀리면 400 INVALID_REQUEST |
| `god/src/main/kotlin/com/ccle/god/web/ErrorHandling.kt` | 80 | `ApiException`과 오류 응답 변환. 계약 밖 오류는 400 UNEXPECTED_ERROR로 내고 원인은 로그에 남긴다. 라우트 404는 NO_ROUTE(L1-6). `/error` 대체 컨트롤러 포함 |
| `god/src/main/kotlin/com/ccle/god/PgClient.kt` | 59 | PG HTTP 호출만 하는 얇은 빈(JDK HttpClient). 응답(상태 코드·본문)을 그대로 돌려준다. 결과 해석은 L1-7에서 Marketplace로 옮겼다 |
| `god/src/main/kotlin/com/ccle/god/web/Dtos.kt` | 59 | 요청·응답 DTO(입찰·호가·체결·주문·결제 결과·포인트) |
| `god/src/main/kotlin/com/ccle/god/web/BidController.kt` | 56 | `/bids`·`/product-sizes/{id}/book`. 헤더 확인 → 파싱 → 위임만 한다 |
| `god/src/main/kotlin/com/ccle/god/web/OrderController.kt` | 48 | `/orders`·`/orders/{id}`·`/me/points` |
| `god/src/main/kotlin/com/ccle/god/web/AdminController.kt` | 37 | `/admin/clock`(GET·PUT), `/admin/batches/{name}` |
| `god/src/main/kotlin/com/ccle/god/FrozenClock.kt` | 30 | 멈춘 시계 빈(`CLOCK_START`, 앞으로만 이동, 마이크로초 절삭) |
| `god/src/main/kotlin/com/ccle/god/web/TradeController.kt` | 22 | `/trades` |
| `god/src/main/kotlin/com/ccle/god/StartupRecovery.kt` | 17 | 기동 훅(`SmartInitializingSingleton`). 포트가 열리기 전에 `convertStalePayingOrders()`를 호출한다 |
| `docs/adr/0003-http-api-stage1.md` (docs) | — | §7 payment-due 표의 PAYING 제외, §9의 6·7 추가, §10에 1단계 등급 불변 전제 추가 |

### 핵심 diff

**1. 사이즈 잠금과 체결** (신규, `68f60fe`. 현재 `god/src/main/kotlin/com/ccle/god/Marketplace.kt:214-219`, `:254-275`)

```kotlin
    private fun lockProductSize(productSizeId: Long) {
        val rows = em.createNativeQuery("SELECT id FROM product_sizes WHERE id = :id FOR UPDATE")
            .setParameter("id", productSizeId)
            .resultList
        if (rows.isEmpty()) notFound("없는 사이즈: $productSizeId")
    }
```

```kotlin
    private fun matchBids(a: Bid, b: Bid, now: Instant): Trade {
        val buy = if (a.side == "BUY") a else b
        val sell = if (a.side == "BUY") b else a
        val aFirst = a.createdAt.isBefore(b.createdAt) || (a.createdAt == b.createdAt && a.id!! < b.id!!)
        val trade = Trade(
            buyBidId = buy.id!!,
            sellBidId = sell.id!!,
            buyerId = buy.userId,
            sellerId = sell.userId,
            productSizeId = buy.productSizeId,
            price = if (aFirst) a.price else b.price,
            status = "AWAITING_PAYMENT",
            matchedAt = now,
            paymentDueAt = now.plus(Duration.ofMinutes(30)),
        )
        em.persist(trade)
        buy.status = "MATCHED"
        sell.status = "MATCHED"
        em.persist(BidEvent(bidId = buy.id!!, fromStatus = "ACTIVE", toStatus = "MATCHED", tradeId = trade.id, createdAt = now))
        em.persist(BidEvent(bidId = sell.id!!, fromStatus = "ACTIVE", toStatus = "MATCHED", tradeId = trade.id, createdAt = now))
        return trade
    }
```

`placeBid`(`:118-155`)는 `tx.execute` 안에서 가장 먼저 `lockProductSize`를 호출한 뒤 상대 입찰을 찾고 `bid_events`를 쓴다. 그래서 같은 사이즈 안에서는 이벤트 id 순서가 실제 처리 순서가 된다(ADR-0003 §3.3). 체결가는 (created_at, id)가 앞선 입찰의 가격이다. 이벤트는 구매 입찰 행을 먼저 쓴다.

**2. `payOrder`: PG 호출 뒤 결과 반영. 리뷰 L1·L2·L3를 거치며 바뀌었다**

before (`d3c88f4:god/src/main/kotlin/com/ccle/god/Marketplace.kt:309-351`):

```kotlin
        val created = try {
            tx.execute { createOrder(userId, idempotencyKey, tradeIds, point, now) }!!
        } catch (e: PersistenceException) {
            // 다른 구매자가 같은 키로 동시에 주문을 만들어 orders_idempotency_key_key에 걸린 경우.
            // (같은 구매자의 요청은 사용자 행 잠금으로 줄을 서므로 여기 오지 않는다.) 다시 하면 멱등 키 조회에서 판정된다.
            log.info("주문 생성 충돌, 멱등 키로 다시 조회: {} ({})", idempotencyKey, e.toString())
            tx.execute { createOrder(userId, idempotencyKey, tradeIds, point, now) }!!
        }
        val orderId = created.first
        val cardAmount = created.second
            ?: return PaymentResult(created = false, order = orderView(orderId))

        // PG 호출 중에는 트랜잭션·잠금을 잡지 않는다
        val reply = pg.approve(idempotencyKey, cardAmount)
        tx.execute {
            val status = when (reply?.status) {
                "APPROVED" -> "APPROVED"
                "DECLINED" -> "DECLINED"
                null -> "UNKNOWN"
                else -> {
                    log.warn("PG가 알 수 없는 상태를 돌려줌: {} — 응답 없음으로 본다", reply)
                    "UNKNOWN"
                }
            }
            em.persist(
                PgTransaction(
                    orderId = orderId,
                    kind = "APPROVE",
                    amount = cardAmount,
                    status = status,
                    pgTxId = if (status == "APPROVED") reply!!.pgTxId else null,
                    idempotencyKey = idempotencyKey,
                    requestedAt = now,
                    respondedAt = if (status == "UNKNOWN") null else now,
                ),
            )
            when (status) {
                "APPROVED" -> approveOrder(orderId, paidAt = now, now = now)
                "DECLINED" -> declineOrder(orderId, now)
                else -> em.find(PurchaseOrder::class.java, orderId).status = "UNKNOWN"
            }
        }
        return PaymentResult(created = true, order = orderView(orderId))
```

after (`god/src/main/kotlin/com/ccle/god/Marketplace.kt:320-339`, `:361-374`, `:394-400`):

```kotlin
        val created = try {
            tx.execute { createOrder(userId, idempotencyKey, tradeIds, point, now) }!!
        } catch (e: PersistenceException) {
            // 다른 구매자가 같은 키로 동시에 주문을 만들어 orders_idempotency_key_key에 걸린 경우만 다시 한다
            // (같은 구매자의 요청은 사용자 행 잠금으로 줄을 서므로 여기 오지 않는다). 다시 하면 멱등 키 조회에서 판정된다.
            // 주문 INSERT는 persist 시점(IDENTITY)에 실행되고 UNIQUE는 즉시 검사되므로 위반은 커밋이 아니라 여기서 올라온다.
            // 그 밖의 PersistenceException은 다시 하지 않고 그대로 올린다
            if (!isIdempotencyKeyConflict(e)) throw e
            log.info("주문 생성 충돌, 멱등 키로 다시 조회: {} ({})", idempotencyKey, e.toString())
            tx.execute { createOrder(userId, idempotencyKey, tradeIds, point, now) }!!
        }
        val orderId = created.first
        val cardAmount = created.second
        if (cardAmount == null) {
            // 같은 키의 기존 주문. 이긴 요청이 아직 PG를 기다리는 중(PAYING)이면 결과를 쓸 때까지 기다린다(ADR-0003 §9의 7)
            return PaymentResult(created = false, order = waitWhilePaying(orderId))
        }

        // PG 호출 중에는 트랜잭션·잠금을 잡지 않는다
        val (status, pgTxId) = pgResult(callPg("승인 $idempotencyKey") { pg.postApproval(idempotencyKey, cardAmount) }, inquiry = false)
```

```kotlin
        } catch (e: Exception) {
            log.error("결제 결과 반영 실패({} {}), UNKNOWN 기록을 다시 시도한다", orderId, status, e)
            false
        }
        if (!reflected) {
            // 결과 반영이 실패하면 응답 없음(UNKNOWN)으로라도 남긴다. 확정은 pg-inquiry가 한다.
            // 단 반영 트랜잭션이 DB에는 커밋됐는데 예외만 받았을 수 있으므로, 잠근 뒤 다시 읽어 아직 PAYING이고
            // APPROVE 행이 없을 때만 쓴다(결과가 정해진 뒤 1행, §5.1). 이것도 실패하면 PAYING으로 남고 다음 기동 때 전환된다
            try {
                tx.execute {
                    val order = em.find(PurchaseOrder::class.java, orderId)
                    lockUser(order.buyerId)
                    em.refresh(order)
                    if (order.status == "PAYING" && approveRowCount(orderId) == 0L) {
```

```kotlin
        val view = orderView(orderId)
        if (view.status == "PAYING") {
            throw IllegalStateException("주문 $orderId 의 결제 결과를 기록하지 못했다 — PAYING으로 남고 다음 기동 때 UNKNOWN으로 바뀐다")
        }
        return PaymentResult(created = true, order = view)
    }
```

바뀐 점을 지적별로 정리하면 다음과 같다.
- PG 결과 해석이 `PgClient`에서 갓 클래스의 `pgResult`로 옮겨졌다(`:454-479`, L1-7). PG 호출에서 어떤 예외가 나도 응답 없음으로 처리한다(`callPg` `:437-447`, L1-4).
- 같은 키 진 요청은 PAYING인 동안 기다린다(`waitWhilePaying`, 상한 = PG 타임아웃×2+10초, 상한을 넘으면 오류. L1-2·L2-1·L2-4).
- 결과 반영이 실패하면 잠근 뒤 다시 확인하고 나서 UNKNOWN을 다시 기록한다(L1-4·L2-3).
- 결과가 영속된 것을 다시 읽어 확인한 뒤에만 201을 준다(L2-2).
- 다시 시도하는 경우를 `orders_idempotency_key_key` 위반으로 좁혔다(L3-2).
- `orderView`는 읽기 전용 REPEATABLE READ 트랜잭션 하나에서 읽는다(`:670`, L3-1). before(`883d785:…/Marketplace.kt:651`)는 트랜잭션 없이 `em.find`와 항목 조회를 따로 했다.

**3. 기동 시 PAYING → UNKNOWN 전환** (신규, `2a89797`. 재합의 2. 재확인 조건은 `883d785`에서 추가)

`god/src/main/kotlin/com/ccle/god/StartupRecovery.kt:12-17`:

```kotlin
@Component
class StartupRecovery(private val marketplace: Marketplace) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        marketplace.convertStalePayingOrders()
    }
}
```

`god/src/main/kotlin/com/ccle/god/Marketplace.kt:958-983`:

```kotlin
    fun convertStalePayingOrders() {
        val converted = forEachTarget("orders", "t.status = 'PAYING'", emptyMap()) { orderId ->
            tx.execute {
                val order = em.find(PurchaseOrder::class.java, orderId)
                lockUser(order.buyerId)
                em.refresh(order)
                // 결제 결과 반영과 같은 원리: 잠근 뒤 다시 읽어 아직 PAYING이고 APPROVE 행이 없을 때만 쓴다
                if (order.status != "PAYING" || approveRowCount(orderId) > 0) return@execute false
                em.persist(
                    PgTransaction(
                        orderId = orderId,
                        kind = "APPROVE",
                        amount = order.cardAmount,
                        status = "UNKNOWN",
                        pgTxId = null,
                        idempotencyKey = order.idempotencyKey,
                        requestedAt = order.createdAt,
                        respondedAt = null,
                    ),
                )
                order.status = "UNKNOWN"
                true
            }!!
        }
        if (converted > 0) log.warn("기동 시 PAYING 주문 {}건을 UNKNOWN으로 바꿨다(확정은 pg-inquiry)", converted)
    }
```

**4. 금액 계산: 중간값 넘침** (L1-1·L1-10)

before (`d3c88f4:god/src/main/kotlin/com/ccle/god/Marketplace.kt:395`, `:399`, `:414`, `:488`):

```kotlin
        val fees = trades.map { maxOf(it.price * 3 / 100, 1_000L) }
```
```kotlin
        val total = itemTotal + feeTotal + shippingFee
```
```kotlin
        val cardShares = bases.map { Math.multiplyExact(cardAmount, it) / total }.toMutableList()
```
```kotlin
            val earn = Math.multiplyExact(item.cardShare, item.price) / base * ratePermille / 1000
```

after (`god/src/main/kotlin/com/ccle/god/Marketplace.kt:523-527`, `:542`, `:584-585`, `:619-620`):

```kotlin
        val fees = trades.map { maxOf(mulDiv(it.price, 3, 100), 1_000L) }
        val itemTotal = trades.fold(0L) { sum, t -> Math.addExact(sum, t.price) }
        val feeTotal = fees.fold(0L) { sum, fee -> Math.addExact(sum, fee) }
        val shippingFee = if (itemTotal >= 100_000) 0L else 3_000L
        val total = Math.addExact(Math.addExact(itemTotal, feeTotal), shippingFee)
```
```kotlin
        val cardShares = bases.map { mulDiv(cardAmount, it, total) }.toMutableList()
```
```kotlin
    private fun mulDiv(a: Long, b: Long, c: Long): Long =
        BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)).divide(BigInteger.valueOf(c)).longValueExact()
```
```kotlin
            val base = Math.addExact(Math.addExact(item.price, item.buyerFee), item.shippingShare)
            val earn = mulDiv(mulDiv(item.cardShare, item.price, base), ratePermille, 1000)
```

before에서 `price * 3`는 Long 범위를 넘으면 조용히 틀린 값이 됐고(감사 A3), `multiplyExact`는 금액이 유효한데도 중간 곱이 넘쳐 400 UNEXPECTED를 냈다(codex F1). after는 중간 곱을 BigInteger로 계산하고, 합계는 `addExact`로 더해 넘치면 오류가 드러나게 했다. 수동 확인: 4e18원 결제가 201을 받았고 적립 4e16이 일치했다.

### 검증 결과

모든 수치는 log 원문 그대로다.

| 항목 | 결과 |
|---|---|
| `run.sh god` 최종 (`f101fa5`, L3 수정 뒤) | 2회 모두 exit 0. contract 117/117, verify 0. XML 집계를 메인이 교차 확인해 일치 |
| `run.sh god` 단계별 | task 01: infra 18/18, contract 3/117, verify 0 → task 02: 58/117(2회 같음, 동시성 4종 2회 green) → task 03: 100/117(2회 같음, ERROR 0) → task 04: 117/117(2회 exit 0, verify 0, ERROR 0) → L1 수정 뒤, L2 수정 뒤, L3 수정 뒤 각각 2회 exit 0, 117/117, verify 0 |
| `run.sh layered` 회귀 | task 04 뒤(`d3c88f4`) 87초, 리뷰 수정 뒤(`f101fa5`) 91초: 둘 다 contract 117 실패·verify 76개 위반 0으로 이전과 같다. 금지영역(layered·behavior-tests·tools·docker) diff `c5324b2..f101fa5` 0 |
| 루트 build | task 04에서 통과, L3 수정 뒤(`f101fa5`, 2026-10-18 00:47경) 워커 실행 BUILD SUCCESSFUL(god·layered 스모크) |
| 배치 시간 | bid-expiry 최대 12.3초(1k 시드 1,156건) |
| 수동 확인 (L1 수정 뒤) | 4e18원 결제 201, 적립 4e16 일치. kill -9 뒤 재기동하면 PAYING이 UNKNOWN으로 바뀌고, pg-inquiry로 PAID(paid_at = created_at). 같은 키 동시 2건은 둘 다 UNKNOWN(2.4초 대기). 라우트 404는 NO_ROUTE, 자원 없음은 NOT_FOUND로 구분됨 |
| 장애 주입 (L2 수정 뒤, 일회용 컨테이너 트리거) | 재기록이 기존 결과를 보고 쓰지 않았다(APPROVE 1행). 결과 반영과 재기록이 모두 실패하면 400 UNEXPECTED를 내고 PAYING이 남으며, 재기동하면 전환된다 |
| L3-2 재현 (L3 수정 전·후 각각 30회×2) | 전부 201+409 IDEMPOTENCY_KEY_REUSED, 주문 1건. 예외는 Hibernate ConstraintViolationException(persist 즉시)이었다. 원 지적은 재현되지 않았고, 다시 시도하는 범위만 좁혔다 |
| L3-1 확인 | DB 문장 로그로 한 RR 읽기 전용 트랜잭션에서 읽는 것과 연결 격리 수준이 복원되는 것을 확인 |
| 그린 위장·경계 | task 04 워커 자기 점검에서 밖으로 샌 규칙, 미사용 코드, 테스트 분기 모두 0. 벽시계 호출 0(task 01 메인 확인). 잔여 컨테이너·포트 없음(L1·L2) |

### 리뷰 요약

stakes 높음이라 듀얼 리뷰 루프(Opus 워커 ∥ codex)를 돌렸고, 루프마다 codex 종합 감사를 붙였다. 리뷰 기준점: L1 `d3c88f4`, L2 `2a89797`, L3 `883d785`. 모두 base는 `c5324b2`다.

| 루프 | 원 지적 | 채택 (ledger id) | 기각 | 범위 밖·이연 |
|---|---|---|---|---|
| L1 리뷰 | codex F1~F3, Opus F1~F6 + OQ4 | 9 (L1-1~L1-9) | 1 (L1-x1: 계약 밖 오류 400 UNEXPECTED_ERROR는 기존 결정) | log "범위 밖 2". ledger에는 L1-x2 1행(페이지네이션 ADR 밖, 실행 로그는 packet 대상 아님). L1-3의 인덱스 부재는 스키마 금지로 범위 밖 |
| L1 감사 | A1~A3 | 3 → ledger 2행 (L1-10 = A3, L1-11 = A1·A2 → 재합의 2) | 0 | — |
| L2 리뷰 | codex F1·F2 + OQ2, Opus F1·F2 + OQ6 | 3 (L2-1~L2-3) | 0 | 1 (L2-x1 적립 등급 시점 → 2단계) |
| L2 감사 | A1·A2 | A1 부분 채택 (L2-4: 상한을 늘리고, 넘으면 성공으로 위장하지 않음. 무한 대기는 택하지 않음) | A2 (L2-x2: open-in-view false라 트랜잭션 밖 공유 EM은 호출마다 새로 만들어진다. `application.yml:12`, 수동 검증 (d)) | — |
| L3 리뷰 | codex F1~F3, Opus F1·F2 + OQ4 | 2 (L3-1 응답 스냅샷, L3-2 검증 조건부·미재현) | 0 | L3-x1 user-deferred(등급 시점), L3-x2 open question(포인트 묶음 전체 적재) |
| L3 감사 | A1 (등급 시점을 범위 밖으로 본 판정이 틀렸다 → 보존하거나 ADR에 명시) | → 사용자 결정, ADR-0003 §10에 명시 (L3-x1) | — | — |
| post-fix 재점검 | codex, L3 수정 hunk와 인접 호출부 | 새 지적 0 | — | — |

- 수정 반영 건수: L1 11건(`2a89797`), L2 4건(`883d785`), L3 2건(`f101fa5`).
- **codex bwrap 오류:** 4회. L1 리뷰 1차, L3 리뷰 1·2차, post-fix 1차였고, 모두 재시도해 성공했다. 이와 별도로 L3 감사는 `_packet`을 읽지 못해(bwrap) 제공된 원문만 보고 판정했다.
- **절차 편차 (log 기록):** L1 감사 입력에 Opus 원문 대신 채택표 요지만 넣었다(L2부터는 원문을 포함). L3 codex 리뷰는 재시도를 2회 했다.
- **종료 방식:** L3에서 새로 채택한 지적이 2건이라 3루프 종료 조건을 채우지 못했다(review unresolved). 사용자 결정 "타깃 수정 + 재점검 1회 후 종료"에 따라 L3를 수정하고 codex post-fix 재점검을 했다. 결과는 clean이었고 그것으로 종료했다. 미러는 모두 삭제했다.
- 보안 스캔: 3루프 모두 개발 기본 비밀번호 `ccle` 2건(application.yml, 공개 기본값)이 잡혔고 오탐이다.

### 남은 한계

- **적립 등급 시점 (L3-x1, user-deferred):** 승인이 늦게 반영되면(pg-inquiry, 기동 전환) 적립률을 반영 시점의 `users.grade`로 읽는다. 1단계에는 등급을 바꾸는 경로가 없어 결과가 같다는 전제를 ADR-0003 §10에 적었다. 이 전제가 깨져도 verify `earn_amount_rate`는 네 비율을 모두 허용하므로 잡지 못한다. 2단계 착수 조건으로 남긴다.
- **포인트 묶음 전체 적재 (L3-x2, open question):** 포인트 예약이 묶음을 모두 메모리에 올린다. 상한 근거가 없다.
- **배치 대상 인덱스 부재 (L1-3):** 메모리 문제는 키셋 페이지로 고쳤다. 인덱스는 스키마 수정 금지라 범위 밖이다.
- **목록 페이지네이션 없음 (L1-x2):** ADR에 페이지네이션이 없다.
- **실행하지 않은 경로:** Long을 넘는 금액(L1), 대기 상한 초과와 인터럽트(L2). 대기 상한(PG 타임아웃×2+10초)을 넘으면 무한히 기다리지 않고 400 UNEXPECTED로 끝난다. L2 감사 A1의 잔여 리스크다.
- **L3-2 미재현:** 지적된 예외 경로는 재현되지 않았다. 다시 시도하는 범위를 좁히는 것으로만 대응했다.
- **교차 구매자 경합 로그:** 경합이 날 때마다 Hibernate SqlExceptionHelper ERROR가 1줄 찍힌다. 응답은 정상이다(L3 수정 워커 정보).
- **단일 인스턴스 전제:** 기동 시 전환과 같은 키 대기는 앱 인스턴스가 하나라는 전제다(ADR-0003 §9의 7).
- **경고 식 경계 해석:** 같은 시각에 제한이 걸린 직후 경고가 오는 경계에서, ADR 원문(엄격한 >)과 생성기의 해석이 다르다. task 04 워커가 보고했다. 두 리뷰어는 지적하지 않았고, 메인이 마감 때 'ADR 원문 유지, 생성기와의 차이는 NEXT N3에 기록'으로 판정했다(ledger M-1). 테스트·verify 영향 없음.
- **리뷰 종료 조건 미충족:** 3루프 상한에서 사용자 결정으로 종료했다(위 리뷰 요약).
- (해소) 리뷰 수정 뒤 회귀: `run.sh layered` 91초 결과 이전과 동일, 루트 build 통과(위 검증 표).

---

사용한 원천 파일·명령:
- `/home/jun/project/clean-code-llm-experiment/docs/plans/2026-10-17/god-class-stage1/requirement-spec.md`
- `/home/jun/project/clean-code-llm-experiment/docs/plans/2026-10-17/god-class-stage1/log.md` (작업 트리, HEAD 대비 미커밋 수정 포함)
- `/home/jun/project/clean-code-llm-experiment/docs/plans/2026-10-15/behavior-tests-stage1/log.md` (`## 완료 요약` 형식)
- `/home/jun/project/clean-code-llm-experiment/god/src/main/kotlin/com/ccle/god/**` (현재 = `f101fa5`, `wc -l`)
- `git diff c5324b2 f101fa5 --stat -- god`, `git diff c5324b2 HEAD -- docs/adr/0003-http-api-stage1.md`, `git log c5324b2..HEAD`
- `git show d3c88f4:god/src/main/kotlin/com/ccle/god/Marketplace.kt`, `git show d3c88f4:god/src/main/kotlin/com/ccle/god/PgClient.kt`, `git show 883d785:god/src/main/kotlin/com/ccle/god/Marketplace.kt`, `git show 2a89797 --stat`, `git show d3c88f4 --stat`
