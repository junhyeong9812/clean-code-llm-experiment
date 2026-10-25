# log — 500k 규모 측정 (배치 8종 + 포인트 예약)

## 타임라인

| 시각 | 사건 | 결과 |
|---|---|---|
| 2026-10-25 ~04:00 | 사전 확인(메인): 500k CSV 6.2GB 있음·이미지 없음, 여유 50GB·빌드 캐시 8.5GB, 측정 도구는 measure-expiry.sh(1k 고정 — `lib.sh` `start_db`가 1k-s42 하드코딩, `EQ_CURL_MAX=120`) | — |
| 2026-10-25 ~04:05 | 인터뷰 2회(8문항): 측정만 · 배치 8종 + 포인트 예약 · 평상시 + 몰아 처리 · 각 2회 교대 · 빌드 캐시 정리 · tools에 커밋 · EXPLAIN + 시간 · 중간 · auto · ff+push | 명세 작성, 브랜치 `feat/measure-500k`(main `a4d0193`) |
| 2026-10-25 ~04:10 | 합의("합의, auto 진행") → set-state spec-approved·mode auto | SPEC=1·MODE=auto |
| 2026-10-25 04:11 | `docker builder prune -f`(빌드 캐시만, 사용자 승인) 8.567GB 회수 → 여유 63GB. `build-image.sh ~/ccle-csv/500k 500k-s42` 백그라운드 시작(scratchpad `build-500k.log`) | 진행 중 |
| 2026-10-25 ~04:15 | `lib.sh` `start_db`에 `EQ_SEED_TAG`(기본 1k-s42) + `tools/equiv/measure-batches.sh` 작성. 1k 스모크(`MEASURE_SEED=1k-s42 … 1`): 두 앱 각 17행(배치 8×2 + 예약 1) 표 완성, 교차 확인 bid-expiry ok(19·1113)·weekly-settlement ok(0·185), 포인트 예약 201(묶음 10개·배분 10행), EXPLAIN 16개 생성. pipefail에서 앱 로그 grep 0건 종료 방어 1곳 수정 | 도구 동작 |
| 2026-10-25 ~04:18 | 회귀: `run.sh iso` 같음(108초)·`run.sh errors` 같음(58초) — 빌드와 동시 실행이라 시간은 참고치 | 기존 판정 불변 |
| 2026-10-25 ~04:18 | 절차 조정: 500k 측정(수 시간) 전에 스크립트 듀얼 1패스를 먼저 — 측정 뒤 결함이 나오면 측정 전체를 다시 해야 하므로. 결과 해석은 측정 뒤 별도 점검(추가) | — |
| 2026-10-25 ~04:24 | 스크립트 듀얼 1패스 회수: codex F1~F5(시계 설정 미확인·비 200 측정치·예약 실패 누락·디스크 가드 범위·정산 EXPLAIN FOR UPDATE 없음) + Opus F1~F5·Q1~Q7(실패 시 app_ms 앞 단계 줄·EXPLAIN 예열 편향·정산할 주 없는데 EXPLAIN·rm -rf·남은 칸 누락). 종합 → 아래 ledger, 전면 수정 | — |
| 2026-10-25 ~04:25 | 명세 보정(방법론, 의미 축소 아님): EXPLAIN을 "1회차 god"에서 **전용 0회차 god(시간 비교 제외, 캐시 예열 겸용)**로 분리 — 1회차 god만 EXPLAIN으로 데워지는 편향(R-O2)과 첫 실행 냉캐시 편향(Q2) 완화. 비용 실행 1회 추가 | 사용자에게 보고 |
| 2026-10-25 ~04:30 | codex post-fix 재점검(`results/recheck.md`): 11건 해소 확인 + 신규 4(`(one) \|\| bad=1`이면 하위 셸 errexit 꺼짐·기동 실패 시 17칸 누락·감시 하위 셸 미정리·같은 초 디렉터리 충돌) → 수정(백그라운드 + wait, on_exit가 남은 칸 채움, 감시 pid 정리, mktemp 디렉터리) | — |
| 2026-10-25 04:26–04:36 | 실패 경로 실행 확인: ① `MEASURE_MIN_FREE_GB=100000` → 3실행 각 실패 1·미실행 16, rc 2 ② 없는 시드 태그 → 기동 실패 rc 125를 칸에 기록, 3실행 51칸, rc 2 ③ INT(setsid로 잘못 보낸 1회차 시험 뒤 재시험): 1차는 on_exit의 `( exit rc )`가 errexit로 셸을 끝내 app_cleanup 미실행 → 앱·가짜 PG 고아 2개(이 시험이 띄운 것 — pgid 확인 후 종료) → `set +e` 수정, 2차 rc 130·컨테이너·프로세스 0·칸 채움 | 실패 경로 동작 |
| 2026-10-25 04:40 | 1k 정상 경로 재스모크: 52행(0·1 god, 1 layered × 17), 비 200/201 0, 교차 확인 ok 12, EXPLAIN 16 / 커밋: 도구·lib.sh, README | 500k 대기 |
| 2026-10-25 05:39 | 500k 이미지 빌드 완료(rc 0, 1:28:48): verify 76 검사 위반 0, `ccle-postgres-seed:500k-s42` 16.6GB, 빌드 컨테이너 제거, 여유 48GB. verify 중 shm 사용 6.4MB/1GB(지난 빌드의 shm 오류 재현 없음) | 이미지 준비 |
| 2026-10-25 05:40 | 500k 측정 시작(`measure-batches.sh 2`, scratchpad `measure-500k.log`). 가정 1 확인: DB 준비 2초, shm 2.6MB/1GB — 공유 메모리 문제 없음. 가정 2: 평상시 3배치 만에 여유 47 → 39GB(측정 컨테이너 쓰기층 12.8GB) — 감시 중 | — |
| 2026-10-25 ~06:00 | 규모 확산 발견(승격 트리거 — 예상보다 확산): 평상시 단계만 shipment-deadline 106초·auto-confirm 80초. 몰아 처리 대상 = bid-expiry 323,425·point-expiry 146,291·DELIVERED 44,635·PAID 12,369 → 실행 1회 몰아 처리 4~5시간, 5실행 20~25시간 추정(인터뷰 때 "1회 수십 분" 안내와 다름) → 사용자에게 질문(응답은 ~12:00 — 그 사이 측정은 계속) | — |
| 2026-10-25 ~12:00 | 사용자 결정: **0회차 + god·layered 각 1회(3실행)로 축소**. 실행 중인 스크립트는 그대로 두고 2회차 god 시작 시 INT로 멈춤(중단 경로 04:26–04:36 시험 완료 — 2회차 god 칸은 중단으로 남음) | 명세 재합의(횟수) |
| 2026-10-25 12:17 | **log 시각 정정:** 이 작업의 01:00~06:35 행 시각은 메인이 실제 시각을 확인하지 않고 추정해 적은 값이었다(사후 재구성과 같은 결함). 근거로 정정: 직전 작업 병합 커밋 03:58, 실패 경로 시험 디렉터리 04:26:31·04:33:49·04:34:00·04:35:49·04:36:35, 도구 커밋 04:40:16, 빌드 로그 마지막 쓰기 05:39:53(소요 1:28:48 → 시작 04:11), 측정 디렉터리 05:40:28. 근거가 구간뿐인 행은 `~`. 이후 행은 `date`로 찍는다 | 정정 |
| 2026-10-25 12:17 | 추정 정정: "실행 1회 몰아 처리 4~5시간"은 틀림 — 실측 실행 1회 ≈ 2.6시간(0회차 god 9,346초·1회차 god 9,257초, 몰아 처리 ≈ 2.1시간). 잘못된 추정으로 결정을 받았으므로 재질문 → 사용자: **3실행 유지** | 재합의 확인 |
| 2026-10-25 13:25 | 측정 종료: 0회차 god·1회차 god·1회차 layered 완료(각 51칸 중 실패 0), 2회차 god 시작 직후 INT(13:24:37 → 13:24:49 종료, 컨테이너·프로세스 0, rc 130, 2회차 칸은 실패 1·미실행 16). 여유 48GB. 가정 2: 실행 중 최저 여유 34GB(10GB 가드 미발동) | 측정 완료 |
| 2026-10-25 13:33 | 결과 해석 듀얼 1패스 회수(codex F1~F5, Opus F1~F7·OQ4) → 핵심 주장 실파일 대조(FakePg 승인 즉시 응답·order_items_status_idx 존재·grade SubPlan loops 4604·정산 SubPlan loops 4,371,693·payment-due Planning 4,490ms 모두 확인) → 해석 전면 수정 | — |
| 2026-10-25 13:41 | codex 종합 감사(두 패스 — 스크립트 패스의 빠진 감사 포함, `tmp.UuFCj0QR1K/results/audit.md`): 판정 오류 5(I1·I3·I5·I7 해석 과장, S4 fixed 판정 틀림) + 누락 2(정산 trades 해시·MISMATCH 무판정) → 해석·ledger 수정, 스크립트 MISMATCH → 끝에 exit 2(1k 재스모크 rc 0·52행·ok 12). codex post-fix 재점검: A1~A7 해소 + 문구 2(A8·A9) 수정 | 리뷰 종료(open 0) |
| 2026-10-25 13:42 | 사이클 마감: NEXT N1 교체(대처 후보 a~e 비교표·측정 한계 승계), 시드 이미지 보류 항목 갱신, measurement-log 1행, 직전 작업 log 시각 주의 1행, 측정 도구 MISMATCH 수정 커밋 | — |

## 측정 결과 (500k-s42, 결과 원본 `build/equiv/measure-batches/20261025T054028-WRvw/` — 로컬(build/ 무시), 커밋 안 함)

시간 = curl 요청 시간(초). 앱 로그의 배치 ms와 모든 행에서 0.1초 안으로 일치. 처리 건수는 두 앱·세 실행이 모두 같고, 교차 확인(bid-expiry 이벤트 수·정산 행 수)은 세 실행 모두 ok(3424·323425·0·98910). `failed`는 모든 배치 0. 실행 합계: 0회차 god 9,144초 · 1회차 god 9,214초 · 1회차 layered 9,190초(요청 시간 합 — 실행 전체는 DB·앱 기동 포함 9,233~9,346초). 2회차는 사용자 결정으로 시작 직후 중단(INT, 정리 0).

| 단계 | 배치 | 처리 건수 | 0회차 god(EXPLAIN·예열 — 비교 제외) | 1회차 god | 1회차 layered | layered/god | 요청 평균 ÷ 건(1회차 god) | 첫 페이지 대상 조회 실행 시간(0회차 EXPLAIN) |
|---|---|---:|---:|---:|---:|---:|---:|---|
| normal | bid-expiry | 3424 | 61.7 | 64.1 | 64.3 | 1.004 | 18.7ms | 14.4초 |
| normal | payment-due | 94 | 3.9 | 8.3 | 10.6 | 1.273 | 88.8ms | 0.0초 |
| normal | pg-inquiry | 0 | 0.4 | 0.7 | 0.8 | - | - | 0.8초 |
| normal | shipment-deadline | 1415 | 106.1 | 106.5 | 104.7 | 0.983 | 75.3ms | 2.3초 |
| normal | auto-confirm | 6089 | 79.7 | 79.4 | 79.1 | 0.996 | 13.0ms | 6.1초 |
| normal | point-expiry | 973 | 39.0 | 41.1 | 38.1 | 0.928 | 42.2ms | 4.3초 |
| normal | grade-recalc | 73009 | 894.6 | 895.1 | 896.1 | 1.001 | 12.3ms | 13.9초 |
| normal | weekly-settlement | 0 | 6.5 | 6.5 | 7.0 | 1.076 | - | 없음(정산할 주 없음) |
| catchup | bid-expiry | 323425 | 2601.6 | 2601.8 | 2606.0 | 1.002 | 8.0ms | 14.1초 |
| catchup | payment-due | 4 | 0.1 | 0.2 | 0.1 | - | 38.2ms | 0.0초 |
| catchup | pg-inquiry | 0 | 0.4 | 0.4 | 0.4 | - | - | 0.4초 |
| catchup | shipment-deadline | 12369 | 824.3 | 823.9 | 823.4 | 0.999 | 66.6ms | 2.2초 |
| catchup | auto-confirm | 44635 | 515.9 | 522.7 | 522.1 | 0.999 | 11.7ms | 6.2초 |
| catchup | point-expiry | 146291 | 1531.5 | 1538.5 | 1548.8 | 1.007 | 10.5ms | 4.5초 |
| catchup | grade-recalc | 133819 | 1516.1 | 1548.6 | 1521.6 | 0.983 | 11.6ms | 9.0초 |
| catchup | weekly-settlement | 98910(정산 행) | 961.8 | 975.6 | 966.9 | 0.991 | (단위 다름) | 25.2초 + 29.7초 |
| catchup | point-reserve | 예약 1건(묶음 100·배분 100행) | 0.5 | 0.5 | 0.5 | - | - | - |

- 몰아 처리의 정산은 15주(마감 2026-10-05 ~ 2027-01-11)를 한 요청에서 처리. 정산 EXPLAIN은 첫 주의 잠금 조회 + 대상 조회.
- 120초 초과: 평상시 grade-recalc, 몰아 처리의 bid-expiry·shipment-deadline·auto-confirm·point-expiry·grade-recalc·weekly-settlement(두 앱 모두).

### 해석 (리뷰 루프 반영본 — 근거·추론·미측정을 구분)

표 주의: "건당" 열은 **요청 전체 시간 ÷ processed**(조회 비용이 섞인 요청 평균). weekly-settlement의 processed는 판매자별 정산 행 수라 다른 배치와 단위가 다르다. "첫 페이지 대상 조회"는 EXPLAIN의 **실행 시간**만 — 평상시 payment-due는 실행 0.8ms인데 **Planning Time 4,490ms**(요청 3.9·8.3·10.6초가 흔들린 것과의 관계는 미확인).

1. **두 앱 차이 — 이 1회 비교로는 판단을 보류한다:** 10초 넘는 배치의 layered/god 비는 **0.928~1.007**(최저 평상시 point-expiry 38.1 대 41.1초). 같은 god의 0회차·1회차 차이는 최대 **5.3%**(평상시 point-expiry 39.0 대 41.1초 — 0회차는 EXPLAIN이 앞서 돌아 조건이 달라 반복 오차의 기준으로는 약함). 같은 조건의 반복이 없어 잡음 폭을 추정할 근거가 없다 — 관찰된 차이(최대 7.2%)가 앱 차이인지 실행 간 흔들림인지 이 자료로는 가를 수 없다. 몰아 처리의 긴 배치(500초 이상)는 비 0.983~1.007로 가깝다(관찰값일 뿐 유의성 판정 아님).
2. **배치별 병목 노드(0회차 EXPLAIN 판독):**
   - **bid-expiry·auto-confirm·shipment-deadline·point-expiry — PK 순서 스캔 + 행 수 오추정:** 플래너가 `ORDER BY id LIMIT 500` 때문에 PK를 따라 거른다. bids는 상태·만료 인덱스가 없고(`docker/schema/constraints.sql:56-58` — `bids_matching_idx`는 product_size_id가 앞), order_items는 `order_items_status_idx`(`:65`)가 있는데도 추정 rows가 실제보다 크게 나와(bid-expiry 추정 306,645 vs 실제 3,424, auto-confirm 46,661 vs 6,089) 일찍 끝날 PK 스캔을 골랐다. 첫 페이지 bid-expiry는 10,848,692행을 걸러 14.4초. 키셋은 앞으로만 나가고 범위 끝이 시작 시 max(id)라(`Marketplace.kt:1307,1313,1336`) 대상 고르기는 PK 범위를 대략 한 번 훑는다 — 첫 페이지 속도로 남은 범위를 외삽하면 bid-expiry 약 17초, point-expiry 약 13초(**외삽치 — 상한 아님**: 뒤 페이지의 계획·캐시 상태는 미측정).
   - **grade-recalc — 사용자별 등급 서브쿼리:** `grade <> (SubPlan 4)` 필터가 사용자마다 6개월 구매 합을 계산한다(첫 페이지 500명을 찾느라 4,604명 평가, 회당 2.79ms, shared read 134만 블록). 페이지를 넘기면 결국 **50만 명 전원을 평가**하고, 뽑힌 대상은 트랜잭션 안에서 다시 계산한다. 그래서 대상이 0명인 같은 달 재실행도 전원 평가 비용을 낼 **가능성**이 있다(재실행 계획·시간은 미측정 — 이력 NOT EXISTS가 먼저 걸러지는 계획이면 다르다). 실행 주기는 ADR-0004 §8.5에 정해져 있지 않다.
   - **weekly-settlement — 미정산 판정 서브쿼리 × 두 번:** 한 주 안에서 잠금 조회(FOR UPDATE)와 대상 조회(조인)가 같은 Bitmap Heap Scan + `NOT EXISTS settlement_items` SubPlan(loops 4,371,693)을 각각 돈다(첫 주 25.2초·29.7초, 각 52,984행). 대상 조회에는 trades 490만 행 해시 구성(3.3초, 64 batches 임시 파일)과 external merge 정렬도 있다. 한 주 = 트랜잭션 하나라 잠금 조회에서 잡은 약 5.3만 행 잠금을 그 주 커밋까지 쥔다 — 보유 시간은 미측정.
3. **건수 비례분과 설명 안 되는 고정분(추론):** 평상시·몰아 처리 두 점으로 T = a + b·n을 맞추면 고정분 a가 bid-expiry 약 37초, point-expiry 약 31초 — 위 외삽치(17·13초)와 차이가 나지만 외삽치 자체가 근거가 약해 "설명 안 되는 몫"을 수치로 확정하지 않는다(가설). 대안 설명(배제 안 됨): 평상시 bid-expiry는 앱 기동 뒤 첫 요청(JIT·커넥션 풀·Hibernate 예열), 평상시 대상은 성기게 퍼져 건당 접근이 차가운 캐시에서 일어남. 몰아 처리에서 대상 고르기는 비중이 작아 보인다(bid-expiry 2,602초 중 첫 페이지 기준 외삽 약 17초). 요청 평균은 몰아 처리 bid-expiry 8.0ms, point-expiry 10.5ms, auto-confirm 11.7ms, grade-recalc 11.6ms(사용자 전원 평가 포함) — 이 값은 순수 트랜잭션 비용이 아니다(페이지별 조회 시간을 재지 않았다).
4. **shipment-deadline 67~75ms/건:** 판매자 귀책(환불·패널티·적립 취소)과 트랜잭션 안의 PG 카드 취소 HTTP 호출. 가짜 PG의 승인 경로는 **지연 없이 즉시 응답**(`behavior-tests/.../pg/FakePg.kt:127-128` — 3초 지연은 TIMEOUT 계열만)이므로 이 값에 PG 지연은 거의 없다 → 실제 PG라면 카드 취소 호출 수(카드 환불액 > 0인 항목만 — `Marketplace.kt:1002`) × PG 지연만큼 더 걸린다.
5. **정산 몰아 처리 976초의 구성(가정값):** 첫 주 두 조회 ≈ 55초를 15주에 그대로 적용하면 약 825초(≈85%) — 주마다 같은 비용이라는 **가정**(뒤 주의 상태·비용은 미측정, 방향도 모름). 나머지는 정산 행 98,910 + 정산 항목 INSERT, 정산 앞 auto-confirm(이미 단독 배치가 돈 뒤라 대상 0건 — 순회 비용만) 등이며 구성은 재지 않았다.
6. **포인트 예약:** 구매 가능 사용자 중 사용 가능 묶음이 가장 많은 사람(100개)으로 요청 전체 0.5초. **이 시드 상태에 한정** — 묶음이 계속 쌓이는 시나리오와 예약 단계만의 시간은 미측정이라 ADR-0003 §12의 7 "상한 없음"을 확정하는 근거로는 부족하다.
7. **규모의 의미:** 일 단위 평상시 배치는 grade-recalc(895초)를 빼면 2분 안. 몰아 처리(약 15주)는 배치마다 9~43분 — 동기 HTTP 요청 하나가 43분 열려 있다. 행동 테스트의 배치 타임아웃 120초(`behavior-tests/.../support/Env.kt:21`)는 1k 기준.

### 한계

- 실행 1회씩(god는 조건이 다른 0회차 포함 2회), 순서 편향(god 먼저 — 0회차가 예열을 겸함).
- 페이지별 조회 시간·트랜잭션 내부 시간·정산 주별 시간은 재지 않았다 — 위 분리는 첫 페이지 EXPLAIN과 두 점 추정에 기댄 추론. 다음 측정은 `pg_stat_statements` 또는 `log_min_duration_statement`로 문장별 시간을 잴 것.
- EXPLAIN은 리터럴·첫 페이지(after = 0)의 custom plan이다. 앱은 바인드 파라미터를 쓰고 pgjdbc는 5회째부터 서버 prepare로 넘어가 generic plan이 될 수 있다 — 뒤 페이지 계획은 미확인.
- 호스트 단일(다른 프로세스 부하 미통제). 가짜 PG는 지연 없음(실 PG보다 낙관적).
- "실행 전체 9,233~9,346초"는 측정 로그(scratchpad `measure-500k.log` `[회차-앱] 끝 N초`)에서 옮긴 값.

## 리뷰 ledger

(스크립트 듀얼 1패스 — source codex·opus, loop 1)

| id | source | 근거 | 내용 | disposition | status |
|---|---|---|---|---|---|
| S1 | codex F1 | `measure-batches.sh` run_phase 시계 `call` | 시계 PUT이 거부돼도 진행 → 잘못된 시각의 측정 | 채택 | fixed(set_clock: PUT 200 + GET 시각 일치) |
| S2 | codex F2 | run_phase | 비 200·필드 없는 응답도 curl_s를 측정치로 남기고 다음 배치 진행 | 채택 | fixed(실패 칸 + 남은 칸 미실행, 다음 실행 계속) |
| S3 | codex F3·opus F5 | reserve·disk_guard·예약 대상 없음 | 실패 시 행 없이 die, HTTP 미검증 | 채택 | fixed(모든 실패가 fail → 칸 기록) |
| S4 | codex F4·opus Q5 | disk_guard | 배치 경계만 확인 — 3600초 요청 중·DB 기동·예약 전 미감시 | 채택 | fixed(기동 전·예약 전 검사 + 기동 뒤 30초 감시, 10GB 아래면 앱 정지 → 실패 칸에 사유. 기동 중 감시는 없음 — A5) |
| S5 | codex F5·opus Q3 | explain 정산 | 앱 잠금 조회는 FOR UPDATE, 조인 대상 조회 빠짐 | 채택 | fixed(BEGIN…ROLLBACK 안 FOR UPDATE + 조인 조회) |
| S6 | opus F1 | run_phase app_ms grep | 실패하면 앱이 배치 로그를 안 남겨 앞 단계 같은 배치 ms를 집음(§2 정직성) | 채택 | fixed(요청 뒤 새 줄에서만) |
| S7 | opus F2·Q2 | explain 위치 | 1회차 god만 EXPLAIN으로 캐시가 데워져 god 2회·1회차 비교가 기울어짐 | 채택 | fixed(전용 0회차 — 명세 보정) |
| S8 | opus F3 | first_cutoff | 정산할 주가 없는데 미래 주 EXPLAIN | 채택 | fixed(직전 월요일 비교, "정산할 주 없음") |
| S9 | opus F4 | `rm -rf "$base"` | 재실행이 수 시간 결과를 지움 | 채택 | fixed(시각+mktemp 디렉터리, latest 링크) |
| S10 | opus Q7 | target_sql | interval 식 vs 앱 리터럴 | 채택(저비용) | fixed |
| S11 | opus Q6·codex OQ | README | 산출물 README 1행 없음 | 채택 | fixed |
| S12 | 재점검(codex) | main `(one) \|\| bad=1` | 하위 셸 errexit가 꺼져 EXPLAIN·조회 실패가 성공 행으로 | 채택 | fixed(백그라운드 + wait) |
| S13 | 재점검(codex) | one 기동 | DB·PG·앱 기동 실패 시 17칸 누락 | 채택 | fixed(on_exit가 남은 칸 채움) |
| S14 | 재점검(codex) | 감시 하위 셸 | 정리 안 됨·pid 재사용 시 다른 프로세스 kill 가능 | 채택 | fixed(watch_pid 정리) |
| S15 | 재점검(codex) | 결과 디렉터리 | 같은 초 재실행 충돌 | 채택 | fixed(mktemp) |
| S16 | main(실행 시험) | on_exit `( exit rc )` | errexit로 셸이 끝나 app_cleanup 미실행 → 중단 시 앱 고아 | 채택 | fixed(set +e) — INT 재시험 정리 0 |
| SQ1 | opus Q1 | 실패 정책 | fail-stop vs 계속 | 결정: 그 실행만 중단(상태가 의도와 달라짐), 다음 실행 계속, 끝에 exit 2 | — |
| SQ2 | opus Q4 | 기동 대기 | 500k 기동이 run-db 60초·health 60초 안인가 | 첫 500k 실행에서 확인(넘으면 실패 칸으로 보임) | — |
| SQ3 | codex OQ | 결과 해석 | 0회차 캐시 영향 명시 | 해석 단계에서 0회차 제외 | — |

(결과 해석 듀얼 1패스 — loop 1, packet OUT=`scratchpad/tmp.UuFCj0QR1K`, mirror=`scratchpad/tmp.xy1m6xWvuD`)

| id | source | 근거 | 내용 | disposition | status |
|---|---|---|---|---|---|
| I1 | codex F1·opus F4 | 해석 1, results.tsv 7·24·41행 | layered/god 범위 0.983~1.007은 틀림(평상시 point-expiry 0.928), god 반복 차이 최대 2.1%도 틀림(5.3%) — 잡음 폭 과소 | 채택 | fixed(0.928~1.007, 5.3%, ±7% 판단 안 함) |
| I2 | codex F2·opus F2·F3 | explain normal-grade-recalc.txt:8-17, constraints.sql:56-65 | "상태·만료 인덱스 없음"으로 묶음 — grade-recalc는 사용자별 SubPlan(4,604회), order_items는 status 인덱스가 있는데 오추정으로 PK 스캔, 정산은 NOT EXISTS SubPlan 437만 회 | 채택 | fixed(배치별 병목 노드) |
| I3 | opus F1 | Marketplace.kt:1307-1336, explain normal-bid-expiry.txt:4-6 | 평상시 bid-expiry 고정분 37초를 대상 고르기로 귀속 — 키셋 전체 비용 상한 약 17초, 나머지 원인 미상 | 채택 | fixed(상한·미설명분·대안 설명) |
| I4 | codex F4 | 표 건당 열 | 요청 평균 ÷ 건을 순수 처리 비용처럼 사용 | 채택 | fixed(열 이름·해석 3) |
| I5 | codex F3·opus F5 | explain catchup-weekly-settlement.txt | 정산 85%는 첫 주 가정값, 나머지 = INSERT 단정 틀림, 두 조회 같은 SubPlan 반복·잠금 1분·단위 다름 누락 | 채택 | fixed(해석 2·5, 표 단위) |
| I6 | codex F5·opus OQ | explain normal-payment-due.txt:23 | 실행 시간만 적어 Planning Time 4.49초 가림 | 채택 | fixed(표 주의) |
| I7 | opus F6 | FakePg.kt:127-128 | "가짜 PG 응답 시간 포함" 방향 반대 — 승인 경로 지연 0 | 채택 | fixed(해석 4·한계) |
| I8 | opus F7·codex OQ | 해석 4 | 포인트 예약 표본 1건 일반화 | 채택 | fixed(이 시드 한정) |
| I9 | opus F3 | 해석 5 | grade-recalc "월 1회" 축소 — 선택 = 전원 평가, 같은 달 재실행 미측정 | 채택 | fixed |
| A1 | 감사(codex) | 해석 1 | I1 수정 뒤에도 "잡음 폭 안·±7%"는 근거 없음(같은 조건 반복 없음) | 채택 | fixed(판단 보류로) |
| A2 | 감사(codex) | 해석 2·3 | 17·13초는 외삽치이지 상한이 아님, "설명 안 되는 20·18초"도 근거 부족 | 채택 | fixed(외삽·가설로) |
| A3 | 감사(codex) | 해석 2·5 | 잠금 보유 1분은 실측 아님, 뒤 주 비용 방향 근거 없음 | 채택 | fixed |
| A4 | 감사(codex) | 해석 4, Marketplace.kt:1002 | PG 지연 × processed 아님 — 카드 환불액 > 0일 때만 취소 호출 | 채택 | fixed |
| A5 | 감사(codex) | measure-batches.sh one() | S4 "기동 중 감시"는 틀림 — 30초 감시는 기동 뒤 시작(기동 전 1회 검사만) | 채택 — S4 판정 정정, 한계 기록(기동 수 초, 측정 중 최저 여유 34GB) | fixed(문서) |
| A6 | 감사(codex) | explain catchup-weekly-settlement.txt:55-58,104-106 | 정산 대상 조회의 trades 490만 행 해시(3.3초·64 batches 임시 파일)·external merge 누락 | 채택 | fixed(해석 2) |
| A7 | 감사(codex) | measure-batches.sh run_phase·check | 교차 확인 MISMATCH여도 성공 종료 | 채택 | fixed(그 행 기록 + 끝에 exit 2) |
| A8 | 재점검(codex) | 해석 3 | "상한 약 17초" 잔존 표현 | 채택 | fixed |
| A9 | 재점검(codex) | 해석 2 grade-recalc | 같은 달 재실행 전원 평가를 단정 | 채택 | fixed(가능성으로) |
| IQ1 | opus OQ | EXPLAIN 리터럴·첫 페이지 | 바인드·generic plan 차이 | 한계에 기록 | — |
| IQ2 | codex OQ | spec §1 | 후보 순위화엔 단계별 시간 부족 | NEXT 후보에 "확인 측정" 포함 | — |

## 생략한 검증

- 없음(긴급 아님, DEBT 0). 명세 §5 대비 조정: "측정 god·layered 각 2회 교대(4실행)" → 사용자 결정으로 0회차 god + 각 1회(3실행). "도구 스모크 1k"·"기존 하네스 회귀(iso·errors)"·"듀얼 1패스(감사·재점검 포함)"는 수행. 스크립트 마지막 수정(MISMATCH → exit 2) 뒤에는 1k 스모크만 — 500k 재측정 안 함(측정 결과 판정에 쓰지 않는 종료 코드 경로만 바뀜, 측정 중 MISMATCH 0).
- CS 이슈 아카이브: study-note 쓰기 금지(spec §4) — 보류(NEXT 보류 목록).

## 완료 요약

**결과:** 500k 시드(verify 위반 0)에서 0회차 god(EXPLAIN·예열), 1회차 god, 1회차 layered를 측정했다. 3실행(각 17칸, 합 51칸) 모두 실패 0이고 교차 확인도 모두 ok다. 측정 도구 `tools/equiv/measure-batches.sh`와 `lib.sh` 시드 태그 선택을 커밋했다(`8332854`·`9a40f67`·`67e90f7`). 대처 후보는 NEXT N1에 올렸다.

### 핵심 diff

`tools/equiv/lib.sh` start_db. 시드 태그를 고를 수 있게 했고 기본값 1k-s42는 그대로다.

before (`git show a4d0193:tools/equiv/lib.sh`, 113–120행):
```bash
start_db() {
  docker rm -f "$EQ_DB" > /dev/null 2>&1 || true
  "$root/tools/seedgen/run-db.sh" 1k-s42 "$EQ_DB" "$EQ_DB_PORT" > /dev/null
  [ "${EQ_PG_LOG:-1}" = 1 ] || return 0
  q "ALTER SYSTEM SET log_statement = 'all'" > /dev/null
  q "ALTER SYSTEM SET log_line_prefix = '%a|'" > /dev/null
  q "SELECT pg_reload_conf()" > /dev/null
}
```

after (`tools/equiv/lib.sh`, 113–121행):
```bash
# EQ_SEED_TAG로 시드 이미지 태그를 바꾼다(측정 도구 전용, 기본 1k-s42 — 비교 시나리오는 1k 기준 값을 덤프한다).
start_db() {
  docker rm -f "$EQ_DB" > /dev/null 2>&1 || true
  "$root/tools/seedgen/run-db.sh" "${EQ_SEED_TAG:-1k-s42}" "$EQ_DB" "$EQ_DB_PORT" > /dev/null
  [ "${EQ_PG_LOG:-1}" = 1 ] || return 0
  q "ALTER SYSTEM SET log_statement = 'all'" > /dev/null
  q "ALTER SYSTEM SET log_line_prefix = '%a|'" > /dev/null
  q "SELECT pg_reload_conf()" > /dev/null
}
```

`tools/equiv/measure-batches.sh`(새 파일)의 실패 처리 핵심. 실패한 칸에는 사유를 적고, 남은 칸은 "미실행"으로 채운다. 실행이 예상하지 못하게 끝나도 on_exit가 칸을 채운 뒤 정리한다. 실행마다 하위 셸을 백그라운드로 띄우고 wait의 종료 코드를 본다.

fail (46–52행):
```bash
fail() { # <회차> <앱> <사유> — 지금 칸에 사유, 남은 칸은 미실행. 하위 셸을 끝낸다(정리는 app_cleanup)
  local reason=$3
  [ -s "$out/disk-low" ] && reason+=" / 여유 디스크 $(cat "$out/disk-low")GB < ${MIN_FREE_GB}GB — 감시가 앱을 멈춤"
  [ "$step" -lt "${#STEPS[@]}" ] && row "$1" "$2" "실패" "" "" "" "" "" "$reason"
  while [ "$step" -lt "${#STEPS[@]}" ]; do row "$1" "$2" "미실행(앞 단계 실패)" "" "" "" "" "" ""; done
  die "$2 $1회차: $reason"
}
```

on_exit (172–182행):
```bash
on_exit() {
  local rc=$?
  set +e   # 정리 중에는 errexit를 끈다(아래 `( exit "$rc" )`가 셸을 끝내 app_cleanup을 건너뛰지 않게)
  if [ "$step" -lt "${#STEPS[@]}" ]; then
    row "$run" "$app" "실패" "" "" "" "" "" "실행 중 종료(rc=$rc) — $out/app.log·stderr"
    while [ "$step" -lt "${#STEPS[@]}" ]; do row "$run" "$app" "미실행(앞 단계 실패)" "" "" "" "" "" ""; done
    [ "$rc" -ne 0 ] || rc=2
  fi
  [ -z "${watch_pid:-}" ] || { kill "$watch_pid" 2>/dev/null || true; wait "$watch_pid" 2>/dev/null || true; }
  ( exit "$rc" ); app_cleanup
}
```

measure (203–208행):
```bash
# 하위 셸을 `||` 왼쪽에 두면 그 안의 errexit가 꺼지므로, 백그라운드로 띄우고 wait의 종료 코드만 본다
# 백그라운드 하위 셸은 SIGINT를 무시하므로, 중단되면 TERM을 보내 그 실행의 정리(on_exit)를 기다린 뒤 끝낸다
bad=0 child=
stop_child() { [ -z "$child" ] || { kill -TERM "$child" 2>/dev/null || true; wait "$child" 2>/dev/null || true; }; }
trap 'stop_child; exit 130' INT; trap 'stop_child; exit 143' TERM
measure() { (one "$@") & child=$!; wait "$child" || bad=1; child=; }
```

### 측정 핵심 (위 "측정 결과"에서 옮김)

- 처리 건수는 두 앱·세 실행이 모두 같다. 교차 확인은 세 실행 모두 ok(3424·323425·0·98910)이고, `failed`는 모든 배치에서 0이다.
- 실행 합계(요청 시간 합): 0회차 god 9,144초 · 1회차 god 9,214초 · 1회차 layered 9,190초.
- 10초 넘는 배치의 layered/god 비는 0.928~1.007이다. 같은 조건을 반복하지 않아서 두 앱의 차이는 판단을 보류한다.
- 평상시 grade-recalc는 73009건에 895.1초(1회차 god)다. 몰아 처리는 bid-expiry 323425건 2601.8초, point-expiry 146291건 1538.5초, grade-recalc 133819건 1548.6초, weekly-settlement 98910행 975.6초다.
- 포인트 예약은 묶음 100·배분 100행에 0.5초다(이 시드 상태에 한정).

### 리뷰

- 스크립트 패스(500k 측정 전, codex ∥ Opus, codex post-fix 재점검): S1~S15를 채택해 fixed했다. 여기에 메인이 실행 시험에서 찾은 S16과 결정 사항 SQ1~SQ3이 있다.
- 결과 해석 패스(codex ∥ Opus): I1~I9를 채택해 fixed했다.
- codex 종합 감사(두 패스, 스크립트 패스에서 빠졌던 감사 포함):
  - 판정 오류 5건: I1·I3·I5·I7 해석 과장, S4 fixed 판정 틀림(30초 감시는 기동 뒤에 시작함)
  - 누락 2건: 정산 trades 해시·external merge(A6), MISMATCH 무판정(A7 → 끝에 exit 2)
  - 이 7건은 A1~A7로 기록했다. post-fix 재점검에서 A1~A7 해소를 확인했고 문구 2건(A8·A9)을 고쳤다. open 0.

### 사용자 결정

1. **0회차 분리:** EXPLAIN을 "1회차 god"에서 떼어 전용 0회차 god로 옮겼다. 0회차는 시간 비교에서 빼고 캐시 예열을 겸한다. 1회차 god만 데워지는 편향(S7)을 줄이려는 조정이다. 사용자에게 보고했다.
2. **3실행 축소와 재질문:** "몰아 처리 1회 4~5시간" 추정을 근거로 사용자가 0회차 + 각 1회(3실행)로 축소했다. 실측은 실행 1회 약 2.6시간이었다. 결정의 근거가 틀렸으므로 다시 질문했고, 사용자는 3실행을 유지했다.

### 메인 실수

- **log 시각 추정 기입:** 이 작업 앞부분 행의 시각을 실제 시각 확인 없이 추정해 적었다(사후 재구성과 같은 결함). 커밋·디렉터리·빌드 로그 시각으로 정정했고, 이후 행은 `date`로 찍는다.
- **잘못된 시간 추정:** 실행 1회 몰아 처리를 4~5시간으로 추정했으나 실측은 약 2.1시간(실행 1회 약 2.6시간)이었다. 이 추정으로 사용자 결정을 받았으므로 재질문했다.
- **on_exit errexit:** on_exit의 `( exit "$rc" )`가 errexit로 셸을 끝내 app_cleanup을 건너뛰었다. 그래서 INT 시험에서 앱·가짜 PG 고아 프로세스 2개가 남았다. `set +e`로 고쳤고(S16) 재시험에서 정리 0을 확인했다.

### 남은 한계

실행 1회씩·순서 편향, 페이지별·주별 시간 미측정, EXPLAIN은 리터럴·첫 페이지 custom plan이다. 대처 후보와 확인 측정은 `docs/plans/NEXT.md` N1에 있다.
