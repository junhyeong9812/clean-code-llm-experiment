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

## 리뷰 ledger

## 생략한 검증

## 완료 요약
