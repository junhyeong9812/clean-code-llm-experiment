# tools/equiv — god·layered 동치 비교 하네스

god와 layered에 같은 요청 순서를 보내고 결과를 비교한다. 비교 층은 응답, 새 행, Hibernate SQL, DB 문장 로그다.
두 앱은 각자 새 일회용 1k 시드 DB를 쓴다. 정규화는 아래 목록의 이름 붙은 규칙으로만 하고, 목록 밖의 차이는 전부 실패로 판정한다.

```bash
tools/equiv/run.sh run           # 시나리오 하나
tools/equiv/run.sh all           # 16종 전부(약 27분)
LAYERED_JAR=/path/old.jar tools/equiv/run.sh pay   # jar 지정(GOD_JAR도 같음). 지정하지 않은 앱은 bootJar를 빌드한다
CONC_N=10 tools/equiv/run.sh conc                  # 동시 요청 쌍 수(기본 30. sa-lock도 CONC_N을 읽는다 — 기본 5회)
tools/equiv/measure-expiry.sh 3  # bid-expiry 소요 시간 측정. god·layered를 교대로 3회(기본 2), 판정 없음
tools/equiv/measure-batches.sh 2 # 배치 8종(평상시·몰아 처리)·포인트 예약 소요 시간 측정. EXPLAIN 전용 0회차 god + god·layered 교대 2회, 판정 없음(시드 MEASURE_SEED, 기본 500k-s42)
```

`EQ_SQL_LOG`·`EQ_PG_LOG`는 측정 도구 전용이다. run.sh에 0으로 주면 비교할 층이 비므로 exit 2로 거부한다.

## 종료 코드

| 코드 | 뜻 |
|---|---|
| 0 | 모든 시나리오가 4층 모두 같음 |
| 1 | 차이 있음. `build/equiv/<시나리오>/diff/<층>.diff` |
| 2 | 실행 실패로 비교가 성립하지 않음: 포트 점유, jar 없음, 앱·가짜 PG 기동 실패, 시나리오 중 명령 실패(curl·psql 오류, curl 상한 120초 초과, 앱 종료), 덤프 0줄, 빈 층(표식 `== MARK`만 남은 층 포함), 정리 실패(컨테이너·프로세스가 남음 — 원래 결과가 0일 때만 2로 바뀐다. 이미 차이(1)면 1을 유지하고 경고만 출력) |
| 130 | 중단(Ctrl-C). 컨테이너와 앱·가짜 PG 프로세스를 정리한 뒤 끝난다. 정리 중의 두 번째 Ctrl-C는 무시한다 |

`all`은 한 시나리오가 실패해도 다음 시나리오를 계속 돌리고, 가장 나쁜 코드로 끝난다. 층마다 파일 수와 줄 수(표식 `== MARK` 줄은 빼고 센다)를 출력하므로 비교가 비었는지 바로 보인다.

## 시나리오 (`scenarios/<이름>.sh`)

시나리오 스크립트에는 픽스처, 요청 순서, 덤프 쿼리, 그리고 필요할 때만 정규화 묶음(`norm_logs`·`norm_responses`)이 들어 있다.

| 이름 | 내용 | 가짜 PG | 쓰는 규칙 |
|---|---|---|---|
| `run` | 입찰·체결·취소: 지정가 교차, 즉시 구매·판매, 자기 입찰 건너뜀, 취소 409, 호가, 체결 목록 | 없음 | 공통만 |
| `pay` | 결제: 승인(포인트 배분), 같은 키 200·409, 거절과 해제, 응답 없음(UNKNOWN), 422·403·404, 조회 | 지연 3초 | + `norm_pg_tx_id` |
| `batch` | bid-expiry, payment-due(경고 → 제한·재매칭·UNKNOWN 제외), pg-inquiry(승인·404), 403, 없는 배치 | 지연 3초 | + `norm_pg_tx_id` |
| `recov` | PG 대기 중 kill -9 → 재기동 시 PAYING → UNKNOWN → pg-inquiry | 지연 60초 | + `norm_pg_tx_id`, `norm_startup_log` |
| `fail` | 반영 실패 주입(트리거 A·B·C), C의 같은 키 대기 상한, 정상 종료·재기동, pg-inquiry | 지연 3초 | + `norm_pg_tx_id`, `norm_poll_fold`, `norm_startup_log` |
| `conc` | 같은 키 동시 2요청 × N: (a) 같은 구매자(진 요청 대기) (b) 다른 구매자(409) | 지연 1초 | + `norm_conc_pair`, `norm_set_from_mark` |
| `errors` | 형식 오류 400, 404, 영속 예외(bids·orders INSERT 트리거 실패)의 응답 code·message | 지연 3초 | 공통만 |
| `ful` | 2단계 이행: 발송·입고·검수 PASS/FAIL(판매자 귀책·패널티·포인트 복구)·배송·구매 확정, 분쟁 제기·기각·인정(환불·적립 회수), 409·403·404·400 | 지연 3초 | + `norm_pg_tx_id` |
| `refund` | 2단계 환불: 구매자 취소(배송비 첫·마지막 항목), shipment-deadline(시드 PAID 포함), 패널티 3건 → 판매 제한, 분쟁 인정 회수, 카드 취소 DECLINE·UNAVAILABLE → 재시도, 계약 밖 2건(체결가 < 10원, 취소 기록 금액 불일치 409 — 하네스가 가짜 PG `POST /cancellations`에 같은 키·다른 금액의 기록을 먼저 만든다) | 지연 3초 | + `norm_pg_tx_id` |
| `s2batch` | auto-confirm(시드 포함), point-expiry(배분 있는 묶음), grade-recalc → 승급 뒤 적립률, 각 재실행 0건 | 지연 3초 | + `norm_pg_tx_id` |
| `settle` | weekly-settlement 시드 전체(좁히지 않음): 한 주 → 재실행 0 → 정산 뒤 분쟁 인정·기각·이월 → 두 주, 정산 조회 | 지연 3초 | + `norm_pg_tx_id` |
| `iso` | 배치 대상 1건 실패의 격리(트리거로 대상 하나만 실패): bid-expiry·point-expiry·auto-confirm(단독)은 200 `failed` 1 → 트리거 제거 뒤 재실행으로 처리, weekly-settlement는 앞 auto-confirm 실패에서 400 `UNEXPECTED_ERROR`(고정 문구)·정산 행 0 → 재실행으로 정산. 격리 실패 ERROR 로그 건수. 판정은 두 앱 비교다. 시나리오 안의 확인은 두 가지뿐이다 — 주입 경로를 지났는지(`need`), 앱마다 응답과 로그가 맞는지(카드 취소 대상이 없는 이 시나리오에서 응답 `failed` 합 = 격리 실패 ERROR 수, 400 수 ≤ 계약 밖 오류 로그 수 — 어긋나면 exit 2). 계약 자체는 행동 테스트 `BatchIsolationTest`가 단언한다 | 지연 3초 | 공통만 |
| `sa-a` | 단계 A 묶음 A(ADR-0007 §4·§5·§6·§10·§2.7): seller-grade-recalc(문턱 등급·판매 제한 STANDARD)·rebate-settlement(PAID·시드 ZERO)·monthly-statement(이월·HELD 수)·tax-invoice-issue(ISSUE·HOLD·지급일 전 보류 → 지급일 뒤 발행)와 각 재실행 0, 계산서 정정(연쇄·409), 지급 계좌 요청·판정·조회(INITIAL·REQUEST), 리뷰 작성·삭제·숨김·판매자 조회, rating-recalc(분쟁 숨김 → 기각 해제 → 인정 뒤 유지), 리뷰 기한(30일 정각 201·1초 뒤 409 `DEADLINE_PASSED`), 입력 규칙(§19-2 rating `5.0` 400·§19-6 NBSP·전각 공백뿐인 본문 400), monthly-statement 건별 격리(판매자 s의 정산서 INSERT만 트리거로 실패 → 200 `failed` 1·나머지 처리 → 트리거 제거 뒤 s만 `processed` 1), 감사 로그. 400·401·403·404·409. 정산은 픽스처로 넣는다(09-28 정산이 9월을 닫아 리베이트·정산서는 시드 판매자 전체가 대상). **정산 픽스처는 의도적 단순화다** — 항목이 전부 시드 항목 하나(다른 판매자의 항목, P3는 정산 기간 전 항목)라 계약 경로로는 만들 수 없는 상태이고, 배치의 대상 판정·집계 SQL을 같은 입력으로 비교하려고 넣는다(정산 계산 자체는 `settle`·contract2) | 지연 3초 | 공통만 |
| `sa-b` | 단계 A 묶음 B(§7·§8·§12.2): 위시리스트·가격 알림 등록·중복·삭제·조회, price-alert-scan(건별 격리: 대상 알림의 UPDATE만 트리거로 실패 → 200 `failed` 1·알림 INSERT도 롤백·만료는 처리 → 트리거 제거 뒤 그 알림만 `processed` 1 · TRIGGERED — DB 문장 로그에서 dedupe 키 바인드가 그 알림인 알림 INSERT 바로 다음 앱 문장이 바인드 `TRIGGERED`·WHERE id = 그 알림인 가격 알림 UPDATE인지 `need`로 확인 · 만료 · 구매 제한 중 그대로 · 맞는 입찰 없음 그대로), deadline-reminder 6종(시점 셋, 각 재실행 0 = dedupe, 제한 연장 뒤 새 `<E>` 알림, 소수 초 제한 끝의 초 내림 키), 알림 조회(unread)·읽음, 배송 추적 기록(오프셋 없는 시각·미래·enum 400, PAID 409)·조회, delivery-delay-scan 두 종류(각 재실행 0) | 지연 3초 | 공통만 |
| `sa-c` | 단계 A 묶음 C(§9·§11·§12.1·§12.3): 문의 작성(항목 당사자·제3자 403)·추가 메시지·답변·종결·목록·상세·SLA, inquiry-auto-close(두 시점, 첫 시점은 건별 격리: 문의 하나의 UPDATE만 트리거로 실패 → 200 `failed` 1·나머지 종결 → 트리거 제거 뒤 그 문의만 `processed` 1), price-stats(아래 중앙값, 재실행 0)·시세 조회(92일·93일·형식 400)·최근 체결, 관리자 조회 7종(키셋 `nextAfter` → 다음 페이지, 400), 판매자 프로필 PUT 생성·갱신·조회(onVacation, 휴가 키 생략 = null §19-14, 확장·음수 연도 날짜 400 §19-5, NBSP·전각 공백뿐인 shopName 400 §19-6), 입고 슬롯 생성(시드·픽스처 중복 409)·조회·예약(판정 순서의 각 409, 정원과 기한이 함께면 정원)·취소(정각 200·1초 뒤 409)·슬롯 조회 확장 연도 `date` 400·체크인(시작 전 409·정각 200·+1시간 409)·inbound-no-show | 지연 3초 | 공통만 |
| `sa-lock` | ADR-0007 §19의 9-1(문의: 주문 → 항목 KEY SHARE)·9-2(리뷰: users id 순 KEY SHARE)·10-1(위시 삭제: 사용자 잠금 뒤 다시 찾기)·10-2(위시·가격 알림 등록: 사이즈 KEY SHARE가 사용자 잠금보다 먼저, 없는 사이즈는 사용자를 잠그지 않음). 직렬 구간은 줄 단위 비교 + 잠금 문장 순서 `need`, 동시 구간(같은 대상 2요청 × `CONC_N`, 기본 5)은 위시 등록·삭제, 가격 알림 등록, 같은 항목 리뷰, 같은 항목 문의 2건, 확정 ∥ 문의, god에서 교착이던 두 조합(9-2 리뷰 작성 ∥ 같은 두 사용자의 다른 주문 검수 FAIL — 판매자 귀책의 id 순 FOR UPDATE, 10-2 위시 등록 ∥ 같은 사용자·사이즈 BUY 입찰 등록) — 쌍의 상태 코드 조합은 시나리오가 기대값과 대조하고(교착·UNIQUE 위반이면 400이 섞여 exit 2), 끝에 DB 로그의 `deadlock detected` 0건을 `need`로 확인 | 지연 3초 | + `norm_conc_pair`, `norm_set_from_mark` |

## 비교 층 (`build/equiv/<시나리오>/{god,layered}/norm/<층>/`)

| 층 | 원본 | 추출 |
|---|---|---|
| 응답 `responses` | `responses.txt`: 요청 1건이 한 줄(메서드, 경로, 멱등 키, 본문, 상태 코드) | 그대로. conc·sa-lock의 동시 쌍 줄만 `norm_conc_pair` |
| 새 행 `rows` | `rows/*.txt`: 시나리오의 `dump`·`dump_text` 결과. id는 시작 시 `max(id)`를 뺀 오프셋으로 쓴다. 단계별 덤프와 앱 로그 건수도 여기에 든다 | 그대로 |
| Hibernate SQL `sql` | `app.log`(`--logging.level.org.hibernate.SQL=debug`, 재기동하면 이어 쓴다) | `ext_hibernate_sql` |
| DB 문장 로그 `pgstmt` | `pg.log`(`log_statement=all`, 접두 `%a\|`, BEGIN·COMMIT·SET 포함) | `ext_pg_app_statements` → `norm_drop_pool_probe` |

응답 본문의 절대 id(bidId·orderId 등)는 정규화하지 않는다. 두 앱이 같은 시드에서 같은 순서로 요청하므로 같은 값이 나온다.

## 추출·정규화 규칙 전체 목록 (`lib.sh`)

| 함수 | 대상 | 하는 일 | 이유 |
|---|---|---|---|
| `ext_hibernate_sql` | sql 층 | 첫 기동 완료(`: Started `) 뒤의 org.hibernate.SQL 레코드 본문만 남긴다. 이어진 줄은 ⏎로 잇고, mark는 `== MARK <이름>` | 기동 중 SQL은 비교 대상이 아니다. 여러 줄 SQL을 버리지 않는다 |
| `ext_pg_app_statements` | pgstmt 층 | application_name이 `PostgreSQL JDBC Driver`인 줄만 남기고 DETAIL 줄은 모두 뺀다(바인드 값 `Parameters:`뿐 아니라 오류의 DETAIL, 예: 중복 키 값도 빠진다. 오류 자체는 ERROR·STATEMENT 줄로 비교된다). 탭으로 시작하는 이어진 줄은 ⏎로 잇고, 하네스의 mark 문장은 `== MARK <이름>` | 하네스(psql)의 픽스처·덤프 문장은 앱 동작이 아니다 |
| `norm_drop_pool_probe` | pgstmt 층(전 시나리오) | 빈 문장 `LOG:  statement: `을 지운다 | pgjdbc `isValid`의 빈 질의다. HikariCP가 500ms 넘게 쉰 연결을 빌려줄 때나 health 확인 때 나가서 요청 사이 간격(부하)에 따라 수가 바뀐다. 앱 문장은 비어 있을 수 없다(명세 §2 재합의 1) |
| `norm_pg_tx_id` | 덤프 SQL의 식 | `pg_tx_id IS NOT NULL`: 값 대신 있음/없음만 | 가짜 PG가 거래 id를 매번 새로 만든다 |
| `norm_poll_fold <시작> <끝>` | fail의 sql·pgstmt 층, 두 mark 사이만 | 같은 블록(1~16줄)이 연달아 2번 이상 나오면 한 번 + `⟳ 반복 접음(N줄 블록)`으로 바꾼다 | 같은 키 진 요청은 대기 상한까지 50ms마다 스냅샷을 다시 읽어서 반복 횟수가 시간에 따라 다르다. 블록의 내용·순서와 구간 밖은 그대로 비교한다. **한계:** 구간(두 번째 KC 요청 전체) 안에서 연달아 반복되는 동일 블록은 폴링이 아니어도 접힌다. 또 반복 단위가 통째로 늘어나는 변경(예: 대기 한 번에 같은 스냅샷 읽기를 2회)은 반복 횟수 차이와 구분되지 않아 잡지 못한다(실측: 아래 '가정 2 실증', 명세 재합의 2) |
| `norm_set_from_mark <표식>` | conc·sa-lock의 sql·pgstmt 층(표식은 둘 다 동시 구간 시작 `concurrent`) | 표식 앞은 그대로(줄 단위), 표식 뒤는 중복을 뺀 정렬 문장 집합(`sort -u`). 경계 줄 `== MARK <이름> (이후 집합 비교: 순서·횟수 무시)` | 동시 구간은 두 요청의 SQL이 섞이고 대기 폴링·충돌 재시도 횟수도 달라서 줄 단위 비교가 성립하지 않는다. 순서·횟수는 비교하지 않지만 한쪽에만 있는 문장 종류는 잡는다(명세 재합의 3). 집합에 넣기 전에 pgstmt의 준비 문장 이름 `execute S_<n>:`·`execute <unnamed>:`를 `execute:`로 바꾼다. pgjdbc가 연결별 사용 횟수에 따라 붙이는 이름이라, 동시 구간에서는 연결 배정에 따라 실행마다 달라진다(실측). 표식 앞은 이름을 그대로 비교한다(명세 재합의 4). 표식 앞 구간은 줄 단위로 비교한다 — conc는 직렬 픽스처 요청(입찰·체결 3N건), sa-lock은 픽스처(주문·이행)와 직렬 잠금 경로(9-1·9-2·10-1·10-2 각 한 번 — 잠금 문장 순서가 줄 단위로 비교된다) |
| `norm_conc_pair` | conc·sa-lock의 응답 층, `conc` 줄만 | 쌍마다 한 줄(`conc <a\|b> <회차> orders=<키당 주문 수> trades=<보낸 체결 2개>` + 두 응답의 상태 코드·본문 전체). 두 응답을 id를 가린 값으로 정렬하고, `orderId`·`orderItemId` 값을 쌍 안에서 처음 나온 순서대로 `#orderId1` 같은 기호로 바꾼다(같은 값은 같은 기호). `tradeId`는 그 쌍이 보낸 체결이면 `#sent`, 아니면 원래 값 | 어느 요청이 이기는지와 그에 따른 주문 id 배정(충돌로 소비된 번호 포함)이 실행마다 다르다. 기호는 대응 관계를 보존해 (a)에서 두 응답이 같은 주문을 가리키는지가 비교에 남는다. 체결 id는 결정적이라 보낸 체결 밖을 가리키면 차이로 잡힌다. 금액·status·message·items 구조·상태 코드는 그대로 비교한다 |
| `norm_startup_log` | recov·fail의 새 행 층 `startup-log` | 앱 로그의 `기동 시 PAYING` 줄을 `<레벨> <메시지>`로 남긴다(시각·pid·앱 이름·스레드·로거를 지운다) | 시각·pid는 실행마다 다르다. 로거는 두 앱의 클래스 구조가 달라(god `Marketplace` / layered `StalePaymentRecovery`, 명세 재합의 1의 기동 훅 구조 차이) 남기면 구조 차이만으로 실패한다 |

가정 2 실증(2026-10-19, fail 시나리오, layered를 작업 트리에서 임시로 바꿔 만든 jar를 `LAYERED_JAR`로 지정):

| 변경 | 위치 | 결과 |
|---|---|---|
| recordUnknown 트랜잭션에 `approveCount` 조회 1문 추가 | 접는 구간 밖 | exit 1, sql·pgstmt 층 차이(+3문) |
| 같은 키 대기 루프에 `approveCount` 조회 1문 추가 | 접는 구간 안 | exit 1, 접힌 블록 2→3줄(sql)·7→8줄(pgstmt) |
| 같은 키 대기 루프에서 스냅샷 읽기를 2회 | 접는 구간 안 | exit 0, **못 잡음**(위 한계) |

2단계 시나리오(`refund`·`s2batch`·`settle`)의 배치는 시드 전체가 대상이다. 배치가 바꾼 기존 시드 행은 정렬 덤프의 md5(`rows/seed.md5`)로, 픽스처 행과 새로 생긴 행(시드 사용자·항목의 것 포함 — 예: 시드 항목의 환불, 시드 사용자의 소멸 원장)은 오프셋 줄 단위로 비교한다. md5는 0행이어도 한 줄이 되므로, 시드 경로(발송 기한 초과·자동 확정·소멸·등급 재산정·시드 판매자 정산)를 지났는지는 시나리오 안의 `need`가 건수 0이면 실패(exit 2)로 확인한다. 분쟁 id는 그 항목의 OPEN 분쟁으로만 잡는다(`open_dispute`, 없으면 실패).

`refund`의 금액 불일치는 ADR-0004 §7.2 계약 밖 노트의 자연 경로(취소를 기록한 PG가 응답하지 않는 사이 같은 주문의 다른 항목이 환불되어 '마지막 환불' 금액이 바뀜)가 아니라, 그 결과 상태(같은 키에 다른 금액의 기록)를 하네스가 직접 만든 것이다. 가짜 PG 취소에는 응답 없음 결과가 없다.

conc의 행 층은 id·구매자 배정 순서와 무관한 집계로만 덤프한다(상태별 주문 수, 키 접두별 주문 수·금액, PG 거래 상태, 체결 상태, 재시도·예상 못 한 오류 로그 건수).

단계 A 시나리오(`sa-a`·`sa-b`·`sa-c`)도 배치가 시드 전체를 대상으로 하므로 같은 방식이다: 바뀔 수 있는 시드 행은 md5, 새 행(시드 판매자의 리베이트·정산서, 시드 항목의 알림·지연 표시 포함)은 오프셋 줄 단위. 주요 경로(배치 대상과 상태 전이, 응답에만 드러나는 경로 일부 — 리뷰 기한 409 `DEADLINE_PASSED`, 취소 기한 정각 200, 맞는 입찰 없는 가격 알림 유지, 지급 계좌 `INITIAL`, §19 입력 규칙의 400)를 지났는지는 `need`(값 또는 기대값 대조, 어긋나면 exit 2)로 확인한다. 모든 요청에 `need`가 있는 것은 아니다 — 나머지 요청은 두 앱 비교로만 걸린다(두 앱이 함께 틀리면 잡지 못한다). 배치는 모두 첫 실행 응답 `failed` 0과 같은 시각 재실행 `processed` 0·`failed` 0을 단언하고(lib.sh `batch_run`), 묶음마다 배치 하나(monthly-statement·price-alert-scan·inquiry-auto-close)는 `iso`와 같은 트리거 주입(lib.sh `inject_on`·`inject_off`)으로 건별 격리를 밟는다. 시드에서 뽑는 id는 정수인지 확인한다(`int_or_die`). `sa-lock`의 동시 구간 행은 conc처럼 배정 순서와 무관한 집계(사용자별 문의 수 등)로 덤프하고, 교착(`deadlock detected`)·예상 못 한 오류 로그 건수도 행 층에 넣는다. `sa-lock`의 직렬 구간 `need`는 실행 중 DB 문장 로그를 `ext_pg_app_statements`로 뽑아 표식 구간 안의 잠금 문장 순서를 본다(판정 규칙이 아니라 경로 확인).

## 결과 위치

- `build/equiv/<시나리오>/<앱>/`: 원본(`responses.txt`, `rows/`, `app.log`, `pg.log`, `fake-pg.log`), 정규화본 `norm/`, 완료 표식 `done`
- `build/equiv/<시나리오>/diff/<층>.diff`: 차이가 있는 층만 남는다
- `build/equiv/measure-expiry/<회차>-<앱>/`: 측정 원본과 `result.txt`

`build/`는 루트 .gitignore로 무시된다. 시나리오를 다시 돌리면 그 시나리오 폴더를 지우고 새로 만든다.

## 포트·이름, 주의

- DB 15445(컨테이너 `ccle-equiv-db`, 이미지 `ccle-postgres-seed:1k-s42-v2`), 앱 18085, 가짜 PG 15485.
  `tools/behavior/run.sh`(8081·8082·15435·15480)와 개발 DB(15432)와 겹치지 않는다.
- 시작 전에 세 포트가 비었는지 확인하고, 하나라도 쓰이고 있으면 exit 2로 끝난다.
  다른 프로세스가 그 포트를 쓰고 있으면 엉뚱한 상대를 판정하게 되고, 같은 이름의 컨테이너를 지우게 된다.
- **한 번에 하나만 돌린다.** 포트와 컨테이너 이름이 고정이다.
  `tools/behavior/run.sh`와는 포트가 겹치지 않지만, 같은 jar를 다시 빌드하면(bootJar) 실행 중인 앱의 jar가 바뀐다. CPU 부하도 타이밍에 기대는 시나리오(recov의 대기, conc의 지연)를 흔든다. 그래서 동시에 돌리지 않는다.
- 도구 검증으로 작업 트리를 임시로 바꿀 때는 bootJar 뒤에 jar를 복사해 `LAYERED_JAR`로 쓰고, 끝나면 되돌려서 `git status`가 깨끗한지 확인한다.
- curl은 한 번에 최대 120초(`EQ_CURL_MAX`, health 확인은 5초)까지 기다린다. 정상 경로에서 가장 긴 대기는 recov의 PG 대기 60초다.
- 가정: DB 컨테이너의 docker 로그는 회전되지 않는다(`docker logs`로 받은 `pg.log`가 실행 전체를 담는다). 회전 설정이 있는 docker에서는 DB 문장 층이 잘릴 수 있다.
- 중단(Ctrl-C)은 터미널에서는 그대로 동작한다. 스크립트로 시험할 때는 SIGINT가 기본 처리인 새 프로세스 그룹에서 띄워야 한다. 비대화형 셸의 `&` 백그라운드 작업은 SIGINT가 무시된 채 시작한다.
