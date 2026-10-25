# 요구사항 명세서 — 500k 규모 측정 (배치 8종 + 포인트 예약)

> 작성일: 2026-10-25 · 작업 폴더: `docs/plans/2026-10-25/measure-500k/` · 브랜치 `feat/measure-500k`(main `a4d0193`)

---

## 0. 요구사항 원문 (인터뷰 기록)

- 원문: "계속 진행해줘" → NEXT N1(500k 규모 측정 — 우선순위 낮음, 1k만 측정됨)
- 사전 사실: 500k CSV `~/ccle-csv/500k`(6.2GB) 있음, 시드 이미지 `ccle-postgres-seed:500k-s42` 없음(지난 빌드: 적재 ~30분 + verify 4,377초, 이미지 16.6GB). `build-image.sh` 디스크 검사 약 45GB 필요, 여유 50GB, docker 빌드 캐시 8.5GB(정리 가능). 기존 측정 도구는 `tools/equiv/measure-expiry.sh`(1k·bid-expiry 전용).
- Q/A:
  - 범위 → **측정만**: 앱 코드는 바꾸지 않고 시간·병목(EXPLAIN)을 측정해 결과와 대처 후보를 NEXT에 올린다
  - 대상 → **배치 8종 전부 + 포인트 예약**
  - 시나리오 → **평상시 + 몰아 처리** 두 가지
  - 횟수 → **god·layered 각 2회 교대** → *(2026-10-25 06:35 재합의: 0회차 god(EXPLAIN·예열) + god·layered 각 1회 — 몰아 처리 1회 4~5시간 추정)*
  - 디스크 → **docker 빌드 캐시 정리 후 진행**, 측정 끝에 500k 이미지는 유지
  - 도구 → **tools에 커밋**(`tools/equiv/measure-batches.sh`)
  - 병목 자료 → **대상 조회 EXPLAIN (ANALYZE, BUFFERS) + 시간**
  - stakes → **중간** · 진행 → **auto** · 병합 → **ff 병합 후 승인 창으로 push**
- 메인이 정한 세부(합의 대상):
  - 실행 1회 = 새 500k DB 1개 + 앱 1개. 한 DB에서 평상시 → 몰아 처리를 이어서 돈다(실제 운영처럼 상태가 이어짐).
  - 평상시 = 시계 2026-10-02T00:00+09:00(시드 현재 + 1일)에서 배치 8종을 이 순서로 1회씩: bid-expiry → payment-due → pg-inquiry → shipment-deadline → auto-confirm → point-expiry → grade-recalc → weekly-settlement.
  - 몰아 처리 = 시계 2027-01-14T06:34:02+09:00(기존 measure-expiry와 같은 시각 — 약 15주)으로 옮긴 뒤 같은 순서로 1회씩.
  - 포인트 예약 = 몰아 처리 뒤, 사용 가능 EARN 묶음이 가장 많은 사용자로 체결 1건을 만들고 `POST /orders`(pointToUse = 사용 가능액 전부 또는 규칙 상한)의 응답 시간과 묶음 수를 잰다.
  - 요청 상한: 측정 스크립트에서 curl 상한을 3600초로(배치가 120초를 넘는지 보는 것이 목적이라 잘리면 안 됨). 120초 초과 여부는 결과 표에 표시만 한다.
  - EXPLAIN은 1회차 god 실행에서만, 각 배치 직전의 대상 조회(키셋 첫 페이지)와 정산 대상 조회(첫 미정산 주)에 대해 앱 밖 psql로 실행한다(측정 시간에 포함하지 않음).
  - 가짜 PG 지연 3000ms·PG 타임아웃 2000ms(equiv와 같음). DB 문장 로그·Hibernate SQL 로그는 끈다(측정 왜곡 방지 — measure-expiry와 같음).

## 1. 목표·대상 (필수)

500k 시드(`ccle-postgres-seed:500k-s42`)에서 god·layered의 배치 8종(평상시·몰아 처리)과 포인트 예약의 소요 시간·처리 건수를 각 2회 교대로 재고, 느린 경로의 대상 조회 EXPLAIN과 함께 `docs/plans/2026-10-25/measure-500k/log.md`에 기록한 뒤 NEXT에 대처 후보(우선순위·방법 비교)를 올린다.

- 산출물: `tools/equiv/measure-batches.sh`(새 측정 도구, 판정 없음) + `tools/equiv/lib.sh`의 시드 태그 선택(기본 1k-s42 그대로) + README 1행 + 측정 결과 기록 + NEXT 갱신.
- 500k 시드 이미지 재빌드(`build-image.sh ~/ccle-csv/500k 500k-s42`, verify 위반 0 확인 포함).

## 2. 경계·불변식 (필수)

- **앱·스키마 무변경:** god·layered·behavior-tests·docker·seedgen 무변경. 측정은 판정이 아니라 사실 기록이다.
- **기존 하네스 동작 무변경:** `lib.sh` 변경은 시드 태그를 고를 수 있게 하는 것뿐이고, 기본값은 1k-s42 — 기존 equiv 12개 시나리오 판정 불변(변경 뒤 `run.sh iso` 1회 + `run.sh errors` 1회로 확인).
- **측정값의 정직성:** 실행이 실패·중단·타임아웃이면 그 칸은 수치 대신 실패로 적는다(무음 누락 금지). 처리 건수는 응답의 `processed`·`failed`와 DB 행 수로 교차 확인한다(최소: bid-expiry EXPIRED 이벤트 수, weekly-settlement 정산 행 수).
- **자원 안전:** 측정 컨테이너는 실행마다 지우고(실행당 쓰기층 수 GB), 여유 디스크가 10GB 아래로 내려가면 측정을 멈춘다. 동시에 하나의 측정·equiv 실행만(고정 포트).

## 3. 기준소스 (필수)

- 시드: `~/ccle-csv/500k` + `tools/seedgen/build-image.sh`·`run-db.sh`·`verify.sql`.
- 앱: main `a4d0193`의 god·layered bootJar.
- 기존 도구: `tools/equiv/lib.sh`(start_db·start_pg·start_app·call), `tools/equiv/measure-expiry.sh`(측정 형식 선례).
- 대상 조회 SQL: god `Marketplace.kt` `forEachTarget`·각 배치 조건, layered `infra/KeysetBatchTargets.kt`, 정산 `settlementTargetSql`.
- 이전 수치: NEXT N1(1k bid-expiry 1,132건 ~13.7초, 정산 최장 ~18초, point-expiry 최장 26.7초).

## 4. 금지영역 (필수)

- `god/`, `layered/`, `behavior-tests/`, `docker/`, `tools/seedgen/`, 루트 gradle 파일, ADR — 수정 금지.
- 기존 equiv 시나리오·정규화 규칙 변경 금지. `lib.sh`는 시드 태그 선택만.
- 최적화(인덱스·쿼리·코드 변경) 금지 — 대처 후보는 NEXT에만.
- docker 정리는 `docker builder prune`(빌드 캐시)만 — 이미지·볼륨·다른 컨테이너 삭제 금지(이 작업이 만든 측정 컨테이너 제외). study-note 쓰기 금지. main 직접 작업 금지. push는 사용자 확인 후.

## 5. 검증 방법 (필수)

- 이미지: 빌드 로그의 verify 위반 0 + 행 수(users 500,000 · trades 4,900,774 · bid_events 26,128,711 — 지난 빌드 값)와 일치.
- 도구 스모크: 1k 시드로 `measure-batches.sh`를 1회(각 앱 1회) 돌려 표가 채워지고 처리 건수 교차 확인이 맞는지 본 뒤 500k로.
- 기존 하네스 회귀: `run.sh iso`·`run.sh errors` 같음.
- 측정: god·layered 각 2회 교대(4실행) 전부 완료 — 실패 칸이 있으면 원인 기록.
- 리뷰: 듀얼 1패스(Opus ∥ codex → 종합 → 감사 → post-fix 재점검). 대상: 측정 스크립트(측정이 측정하려는 것을 재나 — 시각·건수·타임아웃·정리), lib.sh 변경, 결과 해석(EXPLAIN 판독·대처 후보).

## 6. stakes (필수)

- 판정: **중간** — 앱 무변경·일회용 DB, 단 테스트 하네스 공유 파일(lib.sh) 변경과 측정 해석이 다음 결정(최적화 여부)의 입력. 디스크 빠듯(불가역 아님 — 빌드 캐시는 재생성 가능).

---

## 자율성

- **auto** (사용자 선택)

## load-bearing 가정

1. 500k 시드 컨테이너(`run-db.sh`, `--shm-size=1g`)에서 배치의 대상 조회·정산 조회가 공유 메모리 부족 없이 끝난다(지난 빌드의 verify가 shm 1g에서 `could not resize shared memory segment`로 실패한 전례) — 첫 500k 실행 직후 확인, 실패하면 멈추고 보고(run-db.sh는 금지영역이라 바꾸지 않는다).
2. 측정 1회(평상시 8 + 몰아 처리 8 + 예약)가 디스크 여유 10GB 안에서 끝난다(이미지 16.6GB + 실행 쓰기층) — 첫 실행 중 `df`로 확인.
