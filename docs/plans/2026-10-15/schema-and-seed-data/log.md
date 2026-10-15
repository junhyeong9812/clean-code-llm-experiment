# log — schema-and-seed-data

## 타임라인

| 시각 | 사건 | 결과 |
|---|---|---|
| 2026-10-15 | 사용자 "이대로 진행해보자" → ADR-0002 채택(기본값 13곳 그대로) | `62e4e5a` main 병합·푸시 |
| 2026-10-15 | 명세 작성, 인터뷰: Python 생성기, 2년, 소규모 → 본 규모 | SPEC=1·MODE=auto |
| 2026-10-15 | ADR 충돌 발견: 판매 입찰 복구 vs `sell_bid_id` UNIQUE. 사용자: 입찰 상태는 append-only로 따로 관리, 기한 만료 체결은 상태로 | ADR §9.1: `bid_events` + PAYMENT_EXPIRED 제외 부분 UNIQUE. 같은 이유로 `order_items.trade_id`도 PAYMENT_FAILED 제외 부분 UNIQUE |
| 2026-10-15 | ADR §9.2~9.4 금액·정산·상태 세부 구체화 | |
| 2026-10-15 | DDL: `docker/schema/tables.sql`(22 테이블, CHECK) + `constraints.sql`(FK·UNIQUE·인덱스). compose에 `/schema` 마운트, init `02-schema.sql` | 개발 볼륨(빈 DB 확인 후) 재생성 → 두 DB 22 테이블. 첫 조회 0은 init 중 healthcheck 통과로 인한 경합 |
| 2026-10-15 | verify.sql 워커(Opus) 위임 — ADR·DDL만 보고 생성기 미열람 | 68개 검사, 빈 DB 통과, 실패 경로 스모크 확인. ADR 모호점 17건 보고 |
| 2026-10-15 | 생성기 `seedgen.py` 작성. 1천 명: 4.0초 | 결함 수정: RELEASED USE 배분 행, heap 재삽입 튜플 손실, 미정산 패널티 누락, 잔액 시각 |
| 2026-10-15 | `load.sh`: 테이블 → COPY(manifest 행 수 대조) → 제약 → 시퀀스 → ANALYZE | setval 쿼리 평가 순서 오류 2회 → 테이블별 셸 루프로 교체. 제약 전부 통과 |
| 2026-10-15 | 메인 즉석 검사 6개(잔액·remaining·환불·호가 교차·이월·INCLUDED) | 위반 0 |
| 2026-10-15 | 결정성: 같은 시드 2회 → CSV 22개 sha256 동일 | |
| 2026-10-15 | 앱 빌드: 셸 기본 JVM이 Java 8이라 첫 실패(옛 테스트 리포트에 속을 뻔) → JAVA_HOME=21.0.5-tem | god·layered 스모크 tests=1 failures=0 (13:20 새 리포트) |
| 2026-10-15 | 독립 verify 1차: 위반 347 — `item_card_share_floor` 190(생성기: 잔차를 첫 항목에 몰아줌), `settlement_not_empty` 10(생성기: 빈 정산 행), `pg_approve_per_order` 57·`paid_item_has_approved_pg` 90(검증: UNKNOWN→INQUIRY 승인 미인정) | 생성기 2건 수정, verify 승인 정의 수정, ADR §9.5 세부 해석 추가 → 재생성·재적재·verify 68개 위반 0 |
| 2026-10-15 | 반증 표본(ccle_seedbad): 금액 1원·잔액 10P·체결 없는 MATCHED | verify가 4건으로 잡음(exit 3). 고아 FK·살아 있는 이중 체결은 제약이 거부 |
| 2026-10-15 | 시드 이미지 1k: 빌드 59초, 564MB, run-db 준비 1.3초(가정 2 실증) | 크기 추정: CSV 11MB → DB 34MB(×3.1). 50만은 CSV ≈5.5GB, DB ≈17GB, 커밋 순간 ×2 → wal_level=minimal·max_wal_size=2GB, 디스크 검사 CSV×7 |
| 2026-10-15 | 본 규모 생성 시작(`~/ccle-csv/500k`, 백그라운드) | |
| 2026-10-15 | 코드·docs 커밋 분리 | `1be51be` 코드, `f7d321e` docs |
| 2026-10-15 | 듀얼 1패스 리뷰: packet base `62e4e5a`, OUT=`scratchpad/rv.gCPK`, 미러 `mirror.LdmZ`. 보안 스캔: 개발 기본 비밀번호 `ccle` 2건(공개 기본값, 오탐) | Opus(model opus) 12건 + Q8, codex 8건 |
| 2026-10-15 | 첫 본 규모 생성은 리뷰 수정 전 코드라 중단(35분에 CSV 1.8GB ≈ 1/3 → 본 규모 약 100분 추정). pkill 패턴이 자기 셸도 잡아 exit 144 — 무해 | 부분 CSV 삭제 |
| 2026-10-15 | 채택 수정 → 1k 재생성: 결정성 manifest 동일, verify 2건(검증 쪽: CHECK 정의 문자열 패턴, 마지막 마감 뒤 분쟁 인정의 CLAWBACK 예외) → 수정·ADR §9.5 추가 → 71개 위반 0 | |
| 2026-10-15 | 반증 표본 2차(6종: 금액 1원·잔액·MATCHED·항목 간 환불 카드↔포인트 맞바꿈·cancelled_at 비움·주문 PAID 되돌림) | 9건으로 전부 검출 |
| 2026-10-15 | 수정 커밋 `7723a30`(코드), `6db6c82`(docs). codex post-fix 타깃 재점검 시작, 본 규모 생성 재시작 | |
| 2026-10-15 | codex post-fix 재점검: 신규 1건(디스크 검사 전에 manifest 삭제) | 채택, 순서 교체 |
| 2026-10-15 | R17 사용자 결정: "호가창 시뮬레이션으로 교체". 진행 중이던 본 규모 생성 중단 | 사이즈별 호가창(가격·시간 우선, 교차 즉시 체결, 체결가 = 먼저 등록된 입찰, 자기 체결 제외), 판매 입찰 복구 시 재매칭, 구매자별 결제 묶음 |
| 2026-10-15 | 보정(2만 명 ×25 환산): 1차 체결 24.5만/5만 명(목표 미달) → 흐름·가격 분포·취소율 조정 → 체결 약 485만·입찰 약 1,300만. 주문당 항목 1.12(판매자 즉시 판매 체결 43%가 단건) — ADR §7의 1.6은 추정치라 실측대로 둠 | |
| 2026-10-15 | verify: order_paid_within_due 3건 — 세션의 뒤 상품을 미래 시각에 미리 체결해 더 이른 결제 시도에 섞임(시간 역전) | 상품마다 그 시각 이벤트(buy_item)로 처리 → 73개 위반 0 |
| 2026-10-15 | verify에 `trade_price_time_priority`(이벤트 id 기준 ACTIVE 구간) + `trade_match_resolved` 추가 | 반증: 매칭 순간 ACTIVE였던 판매 입찰 가격을 메이커보다 싸게 → 6건 검출 |
| 2026-10-15 | 2만 명: 생성 146초, 적재 63초, verify 1차 실패 `could not resize shared memory segment`(개발 compose, R3 예측 그대로) → compose `shm_size: 1g` → verify 90초 통과 | |
| 2026-10-15 | 커밋 `23aadcb`(코드), `32053e5`(docs). 본 규모 생성 시작, codex 타깃 리뷰(호가창 변경) 시작 | |
| 2026-10-15 | codex 호가창 리뷰 1차: 샌드박스 오류(`bwrap: loopback: Failed RTM_NEWADDR`)로 파일을 못 읽음 → 1회 재시도 성공, 4건 | R21~R24 |
| 2026-10-15 | 수정 `0a0ea98`: ACTIVE 구간 시작 시점 무교차 검사, `data_not_empty`, `v_act` (product_size_id, side, s_eid) 인덱스, `expires <= NOW` 만료 처리 | 1k 재생성·verify 위반 0. 반증: 교차 데이터 → `book_never_crossed=1`, 빈 DB → `data_not_empty=6` |
| 2026-10-15 | codex post-fix 재점검 2건: 테이커 예외가 이벤트 수 기준이라 과거 교차를 놓칠 수 있음, 우선순위 검사 속도 | `512c9aa`: 예외를 같은 매칭 시각으로 한정. 속도는 본 규모 실측으로 판정 |
| 2026-10-15 | 본 규모 생성 완료: 72분, 최대 메모리 4.2GB, CSV 6.2GB. expires = NOW인 ACTIVE 입찰 0건이라 F4 수정 전후 출력 동일 | 행 수는 ADR §7 실측 표 |
| 2026-10-15 | 시드 이미지 빌드: 적재·제약·VACUUM 30분(DB 13GB, FK 34개) 성공 → verify 실패 `could not resize shared memory segment ... 268435456 bytes`(병렬 해시가 shm 1GB 초과) | 빌드 컨테이너를 살려 두고 verify에 `enable_parallel_hash = off` |
| 2026-10-15 | verify 재실행(같은 컨테이너): 83분, 75개 위반 0 | R19 판정: 단일 트랜잭션은 메모리 문제 없음(실패 원인은 shm). 83분은 이미지 빌드 1회 비용이라 허용 |
| 2026-10-15 | 나머지 빌드 단계 수동(CHECKPOINT → stop → commit 2분 → rm), 이전 이미지 1k-s42 삭제 | `ccle-postgres-seed:500k-s42` 16.6GB, 라벨 manifest sha256 `ee0996d0e5f77ae3b52cc67d85a194813fe7d607978b38df9028a90af45cc773` |
| 2026-10-15 | `run-db.sh 500k-s42`: 준비 1.7초. 행 수 일치(users 500,000 · trades 4,900,774 · bid_events 26,128,711). 조회만 했는데 쓰기층 4GB | 원인: PostgreSQL은 데이터 파일을 O_RDWR로 연다 → overlay2가 건드린 파일을 통째로 복사(`docker diff`에는 안 보이고 upperdir에 bid_events·trades와 인덱스). 새 컨테이너에서 `trades` 첫 조회 2.4초 → 0.5초. 실험은 시간이 아니라 토큰·정답률을 재고 컨테이너는 1회용이라 수용, ADR §7에 기록 |
| 2026-10-15 | 두 앱(JDK 21)을 `DB_URL=...:15433/ccle_seed`로 기동 | god·layered health UP, HikariPool 연결 확인. 실행 컨테이너 삭제 |
| 2026-10-15 | 진단용으로 받은 `alpine:latest` 이미지 삭제 | |
| 2026-10-15 | 커밋 `f08a892`(verify 병렬 해시) | |
| 2026-10-15 | 표본 원장 추적(ccle_seedtest 2만 명, 주문 2586: 항목 3, 포인트 1,990 사용, 항목 2835 구매자 취소) | 항목 합 963,000 + 수수료 28,890 = 포인트 1,990 + 카드 989,900. 항목별 카드·포인트 몫 합이 주문과 일치(포인트 483·523·984). 환불 카드 260,067 = PG CANCEL 260,067, 포인트 523 = RESTORE 523(같은 EARN 1117로 배분). 취소 항목 EARN은 CANCELLED, 나머지 적립 2,335 ≈ (234,000 − 포인트 몫) × 1% |
| 2026-10-15 | 사이클 마감: ADR §7 실측 표, README, measurement-log, NEXT.md. CS 이슈 아카이브는 보류(study-note 쓰기 안 함 — NEXT 보류 항목) | |

## 리뷰 ledger

| id | source | 근거 | 내용 | disposition | status |
|---|---|---|---|---|---|
| R1 | opus F1 | seedgen.py 세션 시작·결제 즉시 처리 | 현재 시각에 AWAITING_PAYMENT 거의 없음 | 채택 | fixed: 세션을 하루 전체·이벤트화, NOW 직전 체결은 결제 전 상태로 |
| R2 | opus F2 | seedgen.py 재시도 조건 | 재시도 + UNKNOWN 조회가 결제 기한 넘김 | 채택 | fixed: resp+5분<due |
| R3 | opus F3 | build-image·run-db | /dev/shm 64MB에서 병렬 해시 | 채택 | fixed: --shm-size=1g |
| R4 | opus F4 · codex F8 | build-image.sh | 시드 이미지 1개 유지 미강제 | 채택 | fixed: 이전 태그·dangling 정리(사용 중이면 남김) |
| R5 | opus F5 | load.sh | VACUUM 없음 → 실행 컨테이너 autovacuum copy-up | 채택 | fixed: VACUUM (FREEZE, ANALYZE) |
| R6 | opus F6 | verify order_status_vs_items | REFUNDED_AFTER_CONFIRM 누락 | 채택 | fixed |
| R7 | opus F7 · codex F3·F5 | verify ledger_allocation_sum, item_status_timestamps | RELEASED 배분 0 / 확정 후 환불 cancelled_at | 채택 | fixed |
| R8 | opus F8 | verify schema_constraints_present | 음수 상쇄·CHECK 미확인 | 채택 | fixed: abs + CHECK 정의 확인 |
| R9 | opus F9 | verify inv8 예외 | ADR 근거 없는 완화 | 채택 | fixed: ADR §9.5 명시 |
| R10 | opus F10 · codex F7 | seedgen·load·build-image | 디스크 검사 누락·무력 | 채택 | fixed |
| R11 | opus F11 · codex F6 | seedgen manifest, load.sh | 옛 manifest 잔존, sha 미대조, seconds 비결정, assert | 채택 | fixed |
| R12 | opus F12 | seedgen 하루치 선생성 | 같은 날 생긴 제한 미반영 | 채택 | fixed: 생성 이벤트화 |
| R13 | opus Q5 | seedgen size_ref | 기준가 하한에서 호가 교차 가능 | 채택 | fixed: 기준가 ≥ 30,000 |
| R14 | codex F2 | verify pg 승인 | 단독 INQUIRY 승인 인정 | 채택 | fixed |
| R15 | codex F4 | verify 환불 | 항목 간 카드·포인트 맞바꿈 미검출 | 채택 | fixed: refund_item_card_point |
| R16 | opus Q7 | verify | CARRIED paid_at, 적립 만료 미검사 | 채택 | fixed |
| R17 | codex F1 · opus Q4·F12 | seedgen new_trade | 과거 시점 호가창 가격·시간 우선순위 미재현 | 채택(사용자 결정) | fixed: 호가창 시뮬레이션 + 우선순위 검증 |
| R18 | opus Q2·Q3·Q8 | spec·README | 테이블 수 오기, 실행당 단일 DB, 볼륨 재생성 | 채택 | fixed(docs) |
| R20 | codex post-fix | seedgen main | 디스크 검사 전 manifest 삭제 | 채택 | fixed |
| R19 | opus Q1 | verify 단일 UNION | 본 규모 메모리 | open question | fixed: 실패 원인은 병렬 해시의 shm → `enable_parallel_hash = off`, 83분 통과 |
| R21 | codex 호가창 F1 | verify trade_price_time_priority | 과거 시점의 교차(즉시 체결 누락) 미검사 | 채택 | fixed: `book_never_crossed`(ACTIVE 구간 시작 시점) |
| R22 | codex 호가창 F2 | verify trade_match_resolved | 체결 0건이면 통과 | 채택 | fixed: `data_not_empty` |
| R23 | codex 호가창 F3 · post-fix F2 | verify v_act | 본 규모 우선순위·교차 검사 비용 | 채택 | fixed: 인덱스 추가, 본 규모 실측 83분(빌드 1회 비용)으로 수용 |
| R24 | codex 호가창 F4 | seedgen flush_at_now | expires = NOW 입찰이 ACTIVE로 남음 | 채택 | fixed: `expires <= NOW` 만료 처리 |
| R25 | codex post-fix F1 | verify 테이커 예외 | 이벤트 수 기준 예외가 과거 교차를 통과시킴 | 채택 | fixed: 같은 매칭 시각으로 한정 |

## 생략한 검증

- 없음. 명세 §5 항목(소규모·본 규모 verify, 반증 표본, 결정성, record-level 행 수·합계·표본 원장 추적, 시드 컨테이너 기동 시간, 두 앱 기동)을 모두 실행했다.

## 완료 요약

**결론.** 브랜치 `feat/schema-and-seed-data`(기준 `62e4e5a` → HEAD `f08a892`, 코드·docs 9커밋)에서 ADR-0002 스키마를 DDL로 옮겼다. 22개 테이블과 CHECK는 `tables.sql`에, FK·UNIQUE·인덱스는 적재 뒤에 거는 `constraints.sql`에 있다. 그 위에 2년치 합성 데이터를 만드는 Python 생성기를 붙였다. 생성기는 사이즈별 호가창을 시뮬레이션해 가격·시간 우선으로 매칭하고, 교차하는 입찰은 바로 체결하며, 판매 입찰을 복구하면 다시 매칭하고, 결제는 구매자별로 묶는다. 이어서 적재(`load.sh`, manifest의 sha256·행 수 대조), 불변식 검증(`verify.sql`, 75개 검사), 시드 이미지 빌드·실행 스크립트를 만들었다. 본 규모(사용자 50만 명)에서 생성, 적재, 검증(위반 0), 이미지 빌드, 실행 컨테이너 기동까지 한 번에 통과했고, 실행 컨테이너는 1.7초 만에 준비됐으며 두 앱 health는 UP이다. 앞으로 실행할 때마다 CSV를 다시 적재하지 않고 이미지에서 바로 DB를 띄운다.

### 산출물 (`git diff --stat 62e4e5a..HEAD -- ':!docs'` — 10 files, +2164)

| 파일 | 역할 |
|---|---|
| `docker/schema/tables.sql` (+251) | 22개 테이블 DDL과 행 단위 CHECK |
| `docker/schema/constraints.sql` (+78) | UNIQUE(살아 있는 체결·주문 항목에 거는 부분 UNIQUE 3개 포함), FK, 조회 인덱스. 적재할 때는 COPY 뒤에 건다 |
| `docker/init/02-schema.sql` (+8) | 빈 개발 DB인 `ccle_god`·`ccle_layered` 두 곳에 같은 스키마를 건다 |
| `docker/compose.yml` (+2) | `shm_size: 1g`, `./schema:/schema:ro` 마운트 |
| `tools/seedgen/seedgen.py` (+971) | 결정적(시드 고정) 합성 데이터 생성기. 이벤트 큐와 사이즈별 호가창으로 CSV 22개와 `manifest.json`(행 수·sha256)을 만든다 |
| `tools/seedgen/load.sh` (+57) | 대조(sha256) → 테이블 → COPY(행 수 대조) → 제약 → 시퀀스 → `VACUUM (FREEZE, ANALYZE)`. 개발 DB 이름에는 적재하지 않고, 적재 전에 디스크 여유를 검사한다 |
| `tools/seedgen/verify.sql` (+722) | 행 간·테이블 간 불변식 75개를 검사한다. 위반이 있거나 검사 결과가 비면 RAISE EXCEPTION으로 exit ≠ 0. 마지막에 ROLLBACK |
| `tools/seedgen/build-image.sh` (+48) | 적재·검증을 마친 PGDATA(`/pgseed/data`)를 `docker commit`해 `ccle-postgres-seed:<tag>` 이미지로 만든다(manifest sha256 LABEL) |
| `tools/seedgen/run-db.sh` (+14) | 시드 이미지로 1회용 DB 컨테이너를 띄우고, 준비 시간(ms)과 접속 정보를 출력한다 |
| `README.md` (+13) | 시드 데이터 사용법 |

### 핵심 diff

**1. `docker/compose.yml` — 공유 메모리와 스키마 마운트** (`git diff 62e4e5a..HEAD -- docker/compose.yml`)

before (`62e4e5a:docker/compose.yml`):
```yaml
  postgres:
    image: postgres:17
    environment:
...
    volumes:
      - ./init:/docker-entrypoint-initdb.d:ro
      - ccle-pgdata:/var/lib/postgresql/data
```
after:
```yaml
  postgres:
    image: postgres:17
    shm_size: 1g          # 병렬 해시 조인(verify.sql) — 도커 기본 64MB로는 부족
    environment:
...
    volumes:
      - ./init:/docker-entrypoint-initdb.d:ro
      - ./schema:/schema:ro
      - ccle-pgdata:/var/lib/postgresql/data
```

**2. `docker/schema/constraints.sql:13-17` — 부분 UNIQUE 3개** (before: 없음 — 신규 파일)
```sql
-- 입찰 1개에 살아 있는 체결은 최대 1건(§9.1, 불변식 6)
CREATE UNIQUE INDEX trades_live_buy_bid_key  ON trades (buy_bid_id)  WHERE status <> 'PAYMENT_EXPIRED';
CREATE UNIQUE INDEX trades_live_sell_bid_key ON trades (sell_bid_id) WHERE status <> 'PAYMENT_EXPIRED';
-- 체결 1건에 결제 실패가 아닌 주문 항목은 최대 1건(§9.1)
CREATE UNIQUE INDEX order_items_live_trade_key ON order_items (trade_id) WHERE status <> 'PAYMENT_FAILED';
```

**3. `tools/seedgen/seedgen.py:274-293` — `try_match`(교차 즉시 체결, 체결가 = 먼저 등록된 입찰)** (before: 없음 — 신규 파일)
```python
    def try_match(self, bid, t):
        """bid 가 반대편 최우선 입찰과 교차하면 체결하고 체결을 돌려준다."""
        opp_side = "SELL" if bid["side"] == "BUY" else "BUY"
        opp = self.best(bid["size"], opp_side, bid["user"])
        if opp is None:
            return None
        if (bid["side"] == "BUY" and bid["price"] < opp["price"]) or (bid["side"] == "SELL" and bid["price"] > opp["price"]):
            return None
        first = min(bid, opp, key=lambda x: (x["created"], x["id"]))
        buy, sell = (bid, opp) if bid["side"] == "BUY" else (opp, bid)
        for x in (buy, sell):
            x["in_book"] = False
            self.active.pop(x["id"], None)
        tr = {"id": self.nid("trades"), "buy": buy, "sell": sell, "buyer": buy["user"], "seller": sell["user"],
              "size": bid["size"], "price": first["price"], "status": "AWAITING_PAYMENT", "matched": t,
              "due": t + 30 * MIN}
        self.bid_event(buy, "ACTIVE", "MATCHED", tr["id"], t)
        self.bid_event(sell, "ACTIVE", "MATCHED", tr["id"], t)
        self.on_trade(tr, t)
        return tr
```

**4. `tools/seedgen/verify.sql:703-720` — 판정(위반이나 빈 결과는 실패)** (before: 없음 — 신규 파일)
```sql
DO $$
DECLARE
  total bigint;
  n_checks int;
  failed text;
BEGIN
  SELECT sum(violations), count(*), string_agg(check_name || '=' || violations, ', ' ORDER BY check_name)
                                    FILTER (WHERE violations <> 0)
    INTO total, n_checks, failed
  FROM v_result;
  IF n_checks = 0 OR total IS NULL THEN
    RAISE EXCEPTION 'verify: 검사 결과가 비어 있음 (n_checks=%)', n_checks;
  END IF;
  IF total <> 0 THEN
    RAISE EXCEPTION 'verify: 불변식 위반 % 건 — %', total, failed;
  END IF;
  RAISE NOTICE 'verify: % 개 검사 모두 통과 (위반 0)', n_checks;
END $$;
```
같은 파일 17행(마지막 커밋 `f08a892`): `SET LOCAL enable_parallel_hash = off;   -- 병렬 해시는 /dev/shm(DSM)에 해시 테이블을 둔다: 본 규모에서 1GB를 넘어 실패(실측)`

### 검증 결과

| 규모 | 생성 | 적재 | verify | 기타 |
|---|---|---|---|---|
| 1천 명 | 3~4초 | — | 위반 0 | 같은 시드로 두 번 생성하면 manifest가 같다(결정성). 시드 이미지 빌드 59초, 564MB, run-db 준비 1.3초 |
| 2만 명 | 146초 | 63초 | 1차는 `could not resize shared memory segment`로 실패 → compose `shm_size: 1g` 적용 후 90초에 통과(호가창 리뷰 반영 `0a0ea98`·`512c9aa` 전의 검사 세트) | 호가창 보정에 썼다(×25 환산 시 체결 약 485만·입찰 약 1,300만) |
| 50만 명(본 규모) | 72분, CSV 6.2GB | 30분, DB 13GB | 83분, 75개 검사 위반 0 | 이미지 16.6GB, 실행 컨테이너 준비 1.7초, 두 앱 health UP |

반증 표본: 일부러 결함을 심은 표본으로 verify가 결함을 잡는지 확인했다. 2차에서는 6종을 심었고 verify가 9건으로 모두 검출했다. 호가 우선순위 반증에서는 6건을 검출했다. 3차(호가창 리뷰 반영 뒤)에서는 교차 데이터가 `book_never_crossed=1`로, 빈 DB가 `data_not_empty=6`으로 잡혔다. 고아 FK와 살아 있는 이중 체결은 제약이 거부했다.

표본 원장 추적(주문 2586)과 overlay2 첫 접근 복사 비용은 타임라인 참고. compose의 `shm_size: 1g` 주석("병렬 해시 조인")은 verify가 병렬 해시를 끈 뒤에도 병렬 쿼리 일반의 DSM 여유로 유효하다(개발 DB 2만 명 기준). 다음 작업 전망은 `docs/plans/NEXT.md`.
