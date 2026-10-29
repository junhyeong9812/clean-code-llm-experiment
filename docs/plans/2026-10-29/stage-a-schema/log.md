# log — 단계 A 스키마·verify·시드 후처리 (ADR-0007 §3·§14·§15)

> 작업 폴더: `docs/plans/2026-10-29/stage-a-schema/` · 브랜치 `data/stage-a-schema`(main `7e10896`) · 산출물 유형: 실행물(스키마·시드 생성기·verify·이미지)

## 타임라인

| 시각 | 사건 | 결과 |
|---|---|---|
| 2026-10-29 11:01 | contract3 병합·push(`7e10896`, 사용자 "보강하고 병합 후 push한 뒤 계속 진행해줘") · 브랜치 `data/stage-a-schema` 생성 · 작업 폴더 생성. 디스크 여유 31G(94% 사용) | 인터뷰 시작 |
| 2026-10-29 11:03 | 인터뷰 3문항(범위 한 번에·태그 5곳 전환·auto) → requirement-spec → 사용자 합의 → set-state spec-approved·mode auto | task 01 DDL |
| 2026-10-29 11:03 | 설계 선검증 생략 근거: 동작 불변식은 ADR-0007(선검증·듀얼 리뷰 완료) 것 — 이 작업 고유 불변식(기존 CSV 바이트 동일·결정성)은 범위 제약이며 sha 대조로 직접 검증 | - |
| 2026-10-29 11:03 | stakes 높음의 blind 테스트 워커 = 워커 V(verify 새 검사를 ADR만 보고, 생성기 코드 미열람) — 생성기(워커 G)와 맥락 분리 | - |
| 2026-10-29 11:06 | task 01: DDL 19 테이블을 `tables.sql`·`constraints.sql` 끝에 추가(UNIQUE 9·부분 UNIQUE 4·FK 27·인덱스 21). 스모크(임시 컨테이너 ccle-smoke-a, 기존 `~/ccle-csv/1k` 적재): 적재 완료, public FK 61·테이블 41, 기존 verify는 `schema_constraints_present`=27(FK 34 하드코딩 — 예상)만 위반 → 가정 2(load.sh가 manifest 키로만 적재 — 새 테이블은 빈 채로 생성) 확인. 컨테이너 삭제 | task 02 위임 |
| 2026-10-29 11:07 | task 02 위임: 워커 V(sa-02v — verify 새 검사·seed_build 스위치·변이 검출력, seedgen 열람 금지) ∥ 워커 G(sa-02g — 후처리 패스, verify.sql 열람 금지). 그동안 메인: 태그 5곳 `1k-s42` → `1k-s42-v2`(run.sh·lib.sh 2줄·equiv README·runner.py DB_IMAGE·README 시드 이미지 줄) | 대기 |
| 2026-10-29 11:22 | 워커 G(sa-02g) 회수: `stage_a.py` 신규 531줄 + seedgen.py 3곳(호출·manifest rows·디스크 상수 11_000→14_000). 자체 확인: 2회 생성 41 CSV 바이트 동일 · 기존 22 sha = 기존 manifest · 임시 적재 CHECK·FK·UNIQUE 통과 · 자체 대조 SQL 29개 0. 메인 교차 확인: run1 manifest의 기존 22 sha 22/22 일치, 새 19 키. 행 수: reviews 1,788·seller_ratings 184·seller_grades 2,873·fee_rebates 2,493·payout 20·monthly 2,368·notifications 370·wishlists 370·price_alerts 189·inquiries 91·messages 278·tax_invoices 4,916·price_stats 6,161·audit 18,888·profiles 100·tracking 18,250·**shipment_delays 0**·slots 352·reservations 110 | V 대기 |
| 2026-10-29 11:22 | G 판단 10건 기록(판매자 = payout 계좌 200명, seller_grades 대상 = D 이전 정산, TRIGGERED 가격 알림·PRICE_ALERT 시드 0, 빈 intro 미생성 — CSV 빈 문자열이 COPY에서 NULL이 됨, shipment_delays 0 = NOW에 스캔 대상 없음 등). shipment_delays 0·reservations 110(예상 ≈250)·NO_SHOW 0은 §14 '근사'로 수용하고 리뷰에서 재확인 | - |
| 2026-10-29 11:50 | 워커 V(sa-02v) 회수: verify +411/−7 — FK 34→61, seed_build 플래그(`\\if :{?seed_build}` → seed_only), seed_now 변수(기본 2026-10-01 KST), 새 검사 39(항상 22·시드 빌드 전용 17 — packet은 41·24로 적었으나 실제 이름 39, 메인이 결과표 대조로 확인), build-image.sh verify 줄에 `-v seed_build=1`. 빈 새 테이블 1k에서: 꺼짐 통과, 켜짐 6개 검사가 셈(예상 — 완전성). 변이 39/39 검출. **독립 일치**: V가 빈 테이블로 센 기대 수(정산서 2,368·알림 370·시세 6,161)가 G 생성 행 수와 같음 | task 03 |
| 2026-10-29 11:50 | task 03: `~/ccle-csv/1k-v2` 생성(6.3초, manifest = G run1과 바이트 동일) → `build-image.sh ~/ccle-csv/1k-v2 1k-s42-v2`(1분 29초, 590MB) → **verify 115개(76 + 39) 모두 통과(seed_build=1)**. 별도 컨테이너에서 재확인 115 통과. 메인 재생성 1회 → 41 CSV + manifest `diff -rq` 동일(결정성) | 회귀 실행 |
| 2026-10-29 12:35 | task 04 회귀(v2, `c7/runs-v2/`): contract2 두 앱 89/89 · equiv **12/12 같음** · test 149/150 — **infra VerifySqlTest 실패**(`syntax error at or near ":"`): 그 테스트는 verify.sql을 JDBC로 돌리며 `\\` 줄만 빼서 psql `:seed_only`·`:'seed_now'` 치환이 남음(워커 V 방식의 결함 — 메인이 회귀에서 발견). contract3: '테이블 없음' 사유 사라짐 → NO_ROUTE 116·알 수 없는 배치 41·경합 미관측 5(동시성 테스트 — 엔드포인트가 없어 요청이 DB에 닿지 않음, 예상 red)·자체 검증 2 통과 | 수정 |
| 2026-10-29 12:35 | 수정(메인): 시드 빌드 스위치를 psql 변수 → 서버 설정 `ccle.seed_build`·`ccle.seed_now`(`v_flags` 임시 테이블, `current_setting(…, true)`)로, build-image.sh는 `PGOPTIONS="-c ccle.seed_build=1"`. `\\if` 블록 삭제, 결과 제목 줄 echo → SELECT. 이미지 재빌드 verify 115 통과(켜짐 1 확인) · 별도 컨테이너: 꺼짐 115 통과 · 변이(fee_rebates 1행 삭제) 켜짐 → a2_rebate_complete=1, 꺼짐 → 통과, 행 수 2,493 그대로(롤백). 재회귀 `c7/runs-v2b/`: 두 앱 test 150/150(infra 28 + contract 122), verify 115 통과 | 리뷰 |
| 2026-10-29 12:36 | 리뷰 루프 1 ⓪: BASE `7e10896`, packet `c7/rv5`, 미러 `c7/dmirror`(+ `_data/1k-v2` CSV 41·기존 manifest, log.md 제외), 보안 스캔 같은 오탐 4(CSV 매칭 0) → 통과. ① Opus(sa-rv1-opus) ∥ codex | 대기 |
| 2026-10-29 12:50 | ① 회수: codex(재시도 없이 성공 — major 2·minor 1, python3 -I 실행은 샌드박스 오류로 CSV 전수 재집계 미확인) · Opus(major 1·minor 6·nit 2, CSV 41개 독립 재계산 — 1k 시드 위반 0) → ② 종합 L1-1~L1-13: 채택 11·기각 1·기록 1 → V·G 각자 수정 위임(맥락 분리 유지), 메인 DDL 인덱스 2 추가 | 수정 대기 |
| 2026-10-29 13:13 | ④ 루프 1 수정 회수: G(500k 빈 범위 표본에서 제외·가입 전제 위반 시 RuntimeError, seller_grades·fee_rebates D 00:00 스냅샷 → 2,848·2,469행, `python3 -I` 대응, 500k 메모리 목록) · V(검사 120 — dedupe 현재 열 대조는 시드 전용 `a4_restricted_key_current`, 부분 UNIQUE를 indisunique·열·predicate로, NOTICE에 '시드 빌드 전용 켜짐/꺼짐' + build-image 단언, 새 검사 4, basis 스냅샷). **독립 일치 2회째**: V가 새 정의로 옛 데이터에서 센 위반(basis 875·complete 25)이 G가 지적·수정한 행 수와 같음 | 재생성 |
| 2026-10-29 13:13 | `~/ccle-csv/1k-v2` 재생성(`python3 -I`, 새 디렉터리로 만든 뒤 교체 — 대상은 이 작업이 만든 디렉터리) = G r2a와 `diff -rq` 동일, 기존 22 sha 22/22 · 이미지 재빌드 → `verify: 120 개 검사 모두 통과 (위반 0, 시드 빌드 전용 켜짐)` | 회귀 |
| 2026-10-29 13:43 | 사용자 "계속 진행해줘". 리뷰 루프 2 ⓪: packet `c7/rv6`, 미러 `c7/dmirror2`(+ 재생성 `_data/1k-v2`, `_packet/loop1-fix-diff.patch` — 루프 1 미러 사본 `c7/dmirror-l1` 대비), 같은 오탐 → 통과. ① Opus(sa-rv2-opus) ∥ codex — 회귀(`c7/runs-v2c/`)와 병렬 | 대기 |
| 2026-10-29 13:44 | 루프 2 codex: 1차·재시도 모두 exit 0이지만 bwrap `Failed RTM_NEWADDR`로 파일 0개 열람 → '미확인'(실패 판정). 규칙상 1회 재시도 소진 — 같은 환경 일시 오류라 3차 1회 더 시도(절차 이탈 기록), 그래도 실패면 대체 리뷰어로 계속하거나 review blocked | 3차 대기 |
| 2026-10-29 13:48 | 회귀(재빌드 v2, `c7/runs-v2c/`): 두 앱 test 150/150(infra 28 + contract 122) · contract2 89/89 · equiv **12/12 같음** · contract3 164 = red 162(NO_ROUTE 116·알 수 없는 배치 41·경합 미관측 5 — 엔드포인트 없음)·SlowTrigger 자체 검증 2 통과 · 실행 6회 verify `120 개 검사 모두 통과 (위반 0, 시드 빌드 전용 꺼짐)` | 리뷰 대기 |
| 2026-10-29 13:52 | 루프 2 codex 3차 성공(bwrap 0): major 1 — a2_seller_grade_complete 시작 달 = min(effective_from)이라 첫 달 통째 누락을 놓침(L2-1) · minor 1 — 500k 메모리(L1-11과 중복). L2-1 메인 수정: 시작 달을 §14의 2025-01로 고정. 확인: v2에서 켜짐 120 통과, 변이(2025-01 등급·리베이트 전부 삭제) → a2_seller_grade_complete=69 검출 | Opus 대기 |
| 2026-10-29 13:58 | 루프 2 Opus 회수(blocker 0·major 0·minor 4·기록 2, 항상 켜는 검사의 contract3 픽스처·API 흐름 거짓 양성 없음 — 코드 대조) → 종합: L2-3 inbound_slots CHECK가 세션 TZ 의존(date_trunc) → 메인이 `extract(epoch)%3600=0`으로 · L2-4 price_stats created_at = d+1인데 NOW 기준 PAID 집계 → G에 created_at = NOW 위임 · L2-5 = L2-1과 같음 + 범위 밖 등급 행도 위반(메인 추가) · L2-6 = L1-11 중복 · 기록 2(CHECKED_IN 6건 closed_at > shipped_at — §14 규칙 안, CANCELLED 가격 알림 3건 생존 중 매칭 입찰 — 스캔 NOW 1회 가정) | G 대기 |
| 2026-10-29 14:03 | 루프 2 수정 반영: G price_stats created_at = NOW(바뀐 CSV는 그 하나) → `1k-v2` 재생성(= G r3, 기존 22 sha 22/22) → 이미지 재빌드 verify 120 통과(켜짐). 확인: +05:30 세션에서 KST 정시 슬롯 INSERT 성공·:30 슬롯 CHECK 위반, 2024-12 등급 행 변이 → a2_seller_grade_complete 등 검출 | 회귀·루프 3 |
| 2026-10-29 14:03 | 회귀(`c7/runs-v2d/`) 백그라운드 ∥ 리뷰 루프 3(마지막) ⓪ packet `c7/rv7`, 미러 `c7/dmirror3`(+ loop2-fix-diff). ① Opus(sa-rv3-opus) ∥ codex(bwrap 실패 시 최대 3회) | 대기 |
| 2026-10-29 14:15 | 루프 3 회수: codex(1회 성공 — major 1·minor 1) · Opus(minor 2, CSV 독립 재계산 위반 0 — seller_grades·fee_rebates·정산서·시세·계산서 전부 일치) → L3-1 a6가 답변 시각을 ADMIN 메시지와 대조 안 함(codex major) · L3-2 정시 CHECK `::bigint` 반올림으로 소수 초 통과(codex·opus 공통) · L3-3 README 예시가 500k 원본·이미지를 덮어쓰는 경로(opus). 셋 다 메인 수정: a6에 first_answered_at = min(ADMIN)·answered_at = max(ADMIN), CHECK `mod(extract(epoch), 3600) = 0`, README 예시를 1k-v2·1k-s42-v2 + 덮어쓰기 경고. **루프 3에 신규 채택 → 종료 조건 미충족(review unresolved)** — 수정분 타깃 재점검 후 병합은 사용자 결정 | 회귀 대기 |
| 2026-10-29 14:22 | 사용자 "계속 진행해줘". 이전 회귀(runs-v2d — 이전 이미지)는 중단(결과 무효). 이미지 재빌드 verify 120 통과(켜짐). 확인: 10:00:00.4 슬롯 CHECK 위반 · +05:30 세션 KST 정시 통과 · answered_at −1초 변이 → a6_inquiry_messages=1(꺼짐에서도). 타깃 재점검(codex, bwrap 0): L3-1~3 해소·**신규 0**(§9.1 흐름·insertInquiry 픽스처에서 거짓 양성 없음). 최종 회귀 `c7/runs-final/` 백그라운드 | 회귀 대기 |
| 2026-10-29 14:56 | 최종 회귀(`c7/runs-final/`, 최종 이미지): 두 앱 test 150/150(infra 28 + contract 122) · contract2 89/89 · equiv **12/12 같음** · contract3 red 162(NO_ROUTE 116·알 수 없는 배치 41·경합 미관측 5)·자체 검증 2 통과 · verify `120 개 검사 모두 통과 (위반 0, 시드 빌드 전용 꺼짐)` ×6 | 마감 |
| 2026-10-29 14:56 | 사용자 "병합하고 push한 뒤 계속 진행해줘" — review unresolved(루프 3 신규 채택 3, 수정 + 타깃 재점검 신규 0) 상태에서 사용자 결정으로 병합 | 커밋·병합·push |
| 2026-10-29 15:06 | 사이클 마감: NEXT·measurement-log·README·equiv README · 완료 요약(Opus 워커 sa-sum, 최종 회귀 줄은 메인) · CS 이슈 아카이브 = study-note 쓰기 금지라 보류 · 코드 커밋 `4be86fe` | docs 커밋·병합·push |

## 리뷰 ledger

| ID | 출처 | 지적 | 판정 | 처리 |
|---|---|---|---|---|
| L1-1 | codex major | verify a9_audit_target이 action별 detail 값을 검사 — ADR이 detail을 정하지 않았다는 주장 | 기각 | ADR-0007 §11.3 '감사 로그 detail [제안]' 목록이 action별 값을 정함(채택된 계약) — 검사가 계약과 같음 |
| L1-2 | codex major | 제한 알림 dedupe_key의 <E>를 숫자 여부만 검사 | 채택 | V → fixed |
| L1-3 | codex minor | a0_partial_unique_present가 이름만 확인 | 채택 | V → fixed |
| L1-4 | opus minor | FK 열 인덱스 누락 2(price_alerts.user_id·inbound_reservations.order_item_id) | 채택 | fixed(메인 DDL) |
| L1-5 | opus minor | seller_grades created_at = D인데 basis에 D 뒤 마감 정산 포함(875행)·D에 정산 없던 판매자 25행 | 채택 | G(스냅샷 정의) + V(a2_seller_grade_basis 스냅샷 한정) → fixed |
| L1-6 | opus minor | 행 수가 ADR §14 추정과 다름(seller_grades 2,873/≈3,900 등) | 채택 | 기록(아래 완료 요약·NEXT — [구현 검증] 결과) |
| L1-7 | opus minor | 시드에 TRIGGERED·NO_SHOW·지연·PRICE_ALERT 등 상태 0 → 일부 검사가 빈 집합으로 통과 | 범위 밖 | ADR §14 분포 그대로(TRIGGERED는 분포에 없음) — 검출력은 변이 테스트로 확인, 기록만 |
| L1-8 | opus minor | 시드 빌드 스위치 fail-open(꺼져도 같은 통과 NOTICE) | 채택 | V(NOTICE 표지 + build-image 단언) → fixed |
| L1-9 | opus minor | 한 방향 검사 공백(리마인더 반대 방향·restricted 대조·리베이트/정산서 created_at·예약 시점 PAID) | 채택 | V → fixed |
| L1-10 | opus major | `seedgen --users 500000`이 stage_a.py:231 randint 빈 범위로 크래시(분석 근거) | 채택 | G → fixed |
| L1-11 | opus minor | 500k 메모리(큰 테이블 전체 dict 적재 — 추정)·기존 500k CSV로 재빌드 시 a0_seed_tables_not_empty 실패 | 채택 | 기록(NEXT N1 재개 조건) + G 목록 보고 |
| L1-12 | opus nit | import stage_a가 sys.path 전제(python3 -I면 실패) | 채택 | G → fixed |
| L1-13 | opus nit | 현행 verify.sql은 새 테이블 참조 → 1k-s42(1차 재현) 이미지엔 이전 커밋 verify 필요 | 채택 | README 1행(메인) → fixed |
| L2-1 | codex major | verify a2_seller_grade_complete 시작 달이 데이터의 min(effective_from) — 첫 달 등급(·리베이트) 통째 누락을 놓침 | 채택 | fixed(메인 — 2025-01 고정, 변이 69 검출) |
| L2-2 | codex minor | 500k 후처리 메모리(전체 적재) 미확인 | 중복 | L1-11로 기록 |
| L2-3 | opus minor | inbound_slots CHECK `slot_start = date_trunc('hour', …)`가 세션 TimeZone 의존(정시 아닌 오프셋 세션에서 판정 달라짐) | 채택 | fixed(메인 — epoch % 3600) |
| L2-4 | opus minor | price_stats_daily created_at = d+1 00:00이나 집계는 NOW 기준 PAID → 그 시각엔 없었을 행 49 | 채택 | G(created_at = NOW) → fixed |
| L2-5 | opus minor | a2_seller_grade_complete 하한 min(effective_from) | 중복 | L2-1 + §14 범위 밖 등급 행 위반 추가(메인) |
| L2-6 | opus minor | 500k 메모리(1k 실측 41.8MB → 500k 약 21GB+ 추정) | 중복 | L1-11 기록에 실측 추가 |
| L3-1 | codex major | verify a6_inquiry_messages가 first_answered_at·answered_at을 ADMIN 메시지 시각과 대조하지 않음 | 채택 | fixed(메인 — min·max 대조) |
| L3-2 | codex·opus minor | inbound_slots CHECK `extract(epoch)::bigint % 3600`이 반올림으로 소수 초 허용 | 채택 | fixed(메인 — numeric mod) |
| L3-3 | opus minor | README 시드 예시 명령이 ~/ccle-csv/500k·500k-s42를 덮어씀(spec §2·§4 충돌) | 채택 | fixed(메인 — 1k-v2 예시 + 경고) |

## 생략한 검증

- **리뷰 종료 조건 미충족(`review unresolved`)**: 루프 3에 신규 채택 3(L3-1~3) → 수정 + 타깃 재점검(신규 0) → 사용자 결정으로 병합(2026-10-29).
- **루프 2 codex 3회 시도**: 규칙상 재시도 1회 — 1·2차 bwrap으로 파일 0 열람, 3차 성공(절차 이탈 기록).
- **500k 경로 미실행**: 생성 빈 범위 크래시는 고쳤으나 실제 500k 생성·메모리(1k 실측 41.8MB → 약 21GB+ 추정)·verify 성능은 미측정 — 500k 재빌드는 범위 밖(ADR-0006 §3의 4).
- **contract3 green 미검증**: 앱 구현 전이라 새 테이블에 대한 기대값 실행 검증은 god 구현 작업에서.

## 완료 요약

### 무엇을 했나
- 인터뷰 3문항으로 정함: 범위는 DDL·verify·시드 후처리·새 이미지를 한 번에, 태그는 5곳 모두 `1k-s42-v2`로(기존 `1k-s42` 이미지는 그대로 둠), 자율성은 auto, stakes는 높음.
- DDL: `tables.sql`·`constraints.sql` 끝에 ADR-0007 §3의 새 테이블 19개를 덧붙임(UNIQUE 9·부분 UNIQUE 4·FK 27·인덱스 21+2 — 루프 1에서 FK 열 인덱스 2개 추가). 기존 22개 테이블 정의는 바꾸지 않음.
- 시드: `tools/seedgen/stage_a.py`(536줄, 신규)는 닫힌 기존 22개 CSV를 다시 읽고 별도 RNG `seed ^ 0x5CA1E`로 새 CSV 19개를 만듦. seedgen.py는 이 모듈을 호출하고 결과를 manifest에 넣음. 기존 22개 CSV의 sha는 22/22 그대로이고, 같은 seed로 두 번 생성하면 41개 모두 바이트가 같음.
- verify: FK 수 34→61, 새 검사 44개 추가로 총 120개(기존 76 + 44). 시드 빌드에서만 켜는 검사는 psql 변수를 버리고 서버 설정 `ccle.seed_build`·`ccle.seed_now`로 켬. psql 변수 방식은 VerifySqlTest(JDBC)를 깨뜨려서 바꿈.
- 분리 작성: verify는 워커 V(seedgen 코드 안 봄), 후처리는 워커 G(verify 안 봄)가 따로 씀. 두 결과가 독립적으로 맞아떨어진 일이 2회 있었음. ① V가 빈 테이블에서 센 기대 수(정산서 2,368·알림 370·시세 6,161)가 G가 만든 행 수와 같았음. ② 루프 1 뒤 V가 새 정의로 옛 데이터에서 센 위반 수(basis 875·complete 25)가 G가 지적하고 고친 행 수와 같았음.
- 산출물: `~/ccle-csv/1k-v2`(41 CSV) → 이미지 `1k-s42-v2` → 태그 5곳 전환(runner.py `DB_IMAGE`·behavior/run.sh·equiv/lib.sh 2줄·equiv/README·README).

### 핵심 diff (기준 `7e10896`, 미커밋)

(a) `docker/schema/constraints.sql` — 부분 UNIQUE 4
```sql
CREATE UNIQUE INDEX payout_account_requests_live_user_key ON payout_account_requests (user_id) WHERE status = 'PENDING';
CREATE UNIQUE INDEX price_alerts_live_user_size_key       ON price_alerts (user_id, product_size_id) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX tax_invoices_live_settlement_key      ON tax_invoices (settlement_id) WHERE (kind = 'ISSUE' AND status = 'ISSUED') OR kind = 'HOLD';
CREATE UNIQUE INDEX inbound_reservations_live_item_key    ON inbound_reservations (order_item_id) WHERE status IN ('RESERVED','CHECKED_IN');
```

(b) `tools/seedgen/verify.sql` — 스위치(v_flags)와 FK 수
```sql
CREATE TEMP TABLE v_flags ON COMMIT DROP AS
SELECT (coalesce(current_setting('ccle.seed_build', true), '') = '1')::int AS seed_only,
       coalesce(nullif(current_setting('ccle.seed_now', true), ''), '2026-10-01 00:00:00+09')::timestamptz AS seed_now;
```
```diff
-       (abs(34 - (SELECT count(*) FROM pg_constraint
+       (abs(61 - (SELECT count(*) FROM pg_constraint
```

(c) `tools/seedgen/build-image.sh` — verify를 켜서 돌리고, 켜졌는지 단언(fail-open 차단)
```bash
verify_log=$(docker exec -i -e PGOPTIONS="-c ccle.seed_build=1" "$name" psql -U ccle -d ccle_seed -v ON_ERROR_STOP=1 -q \
  < "$here/verify.sql" 2>&1) || { printf '%s\n' "$verify_log" >&2; exit 1; }
printf '%s\n' "$verify_log"
grep -qE 'verify: [0-9]+ 개 검사 모두 통과 \(위반 0, 시드 빌드 전용 켜짐\)' <<< "$verify_log" \
  || { echo "verify: 시드 빌드 전용 검사가 켜지지 않았다(ccle.seed_build)" >&2; exit 1; }
```

(d) `tools/seedgen/stage_a.py` — RNG와 입력 재독
```python
def read(out, name):
    with open(out / f"{name}.csv", newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))
...
def run(out, seed):
    out = Path(out)
    r = random.Random(seed ^ 0x5CA1E)
    ...
    users = {int(u["id"]): u for u in read(out, "users")}
```

(e) `tools/seedgen/seedgen.py` — 호출부
```diff
+    import sys
+    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))   # python3 -I 로 실행해도 stage_a 를 찾게
+    import stage_a                                       # 단계 A 새 테이블: 닫힌 22개 CSV를 다시 읽어 만든다(ADR-0007 §14)
+    rows = {**g.counts, **stage_a.run(a.out, a.seed)}
     sums = {}
-    for name in TABLES:
+    for name in rows:
```

### 새 테이블 1k 행 수 (`~/ccle-csv/1k-v2/manifest.json`, 최종 재생성분)

| 테이블 | 행 | 테이블 | 행 |
|---|---|---|---|
| reviews | 1,788 | inquiry_messages | 278 |
| seller_ratings | 184 | tax_invoices | 4,916 |
| seller_grades | 2,848 | price_stats_daily | 6,161 |
| fee_rebates | 2,469 | admin_audit_logs | 18,888 |
| payout_account_requests | 20 | seller_profiles | 100 |
| monthly_statements | 2,368 | shipment_tracking_events | 18,250 |
| notifications | 370 | shipment_delays | **0** |
| wishlists | 370 | inbound_slots | 352 |
| price_alerts | 189 | inbound_reservations | 110 |
| inquiries | 91 | | |

(seller_grades 2,873→2,848·fee_rebates 2,493→2,469는 루프 1의 D 00:00 스냅샷 정의 때문에 줄어든 것. price_stats_daily는 행 수는 같고 created_at만 NOW로 바뀜.)

### 실행 결과
- 이미지 `1k-s42-v2` 재빌드: `verify: 120 개 검사 모두 통과 (위반 0, 시드 빌드 전용 켜짐)`.
- 직전 회귀(`c7/runs-v2c/`, 루프 2 수정 전 이미지): 두 앱 test 150/150 · contract2 89/89 · equiv 12/12 같음 · contract3 164 중 red 162(NO_ROUTE 116·알 수 없는 배치 41·경합 미관측 5 — 엔드포인트가 없어서 생긴 것이고 '테이블 없음' 사유는 사라짐), 자체 검증 2개 통과.
- 변이 검출: 새 검사 39/39(루프 0) + 수정분 개별 변이(a2_rebate_complete=1·a2_seller_grade_complete=69·a6_inquiry_messages=1·슬롯 CHECK 소수 초·+05:30 세션).
- 최종 회귀(`c7/runs-final/`, 최종 이미지): 두 앱 test 150/150 · contract2 89/89 · equiv 12/12 같음 · contract3 red 162(NO_ROUTE 116·알 수 없는 배치 41·경합 미관측 5)·자체 검증 2 통과 · verify 120 통과(꺼짐) ×6

### 리뷰 요약 (듀얼 루프, Opus ∥ codex)

| 루프 | 지적 | 채택 | 기각 | 중복 | 기록/범위 밖 | 비고 |
|---|---|---|---|---|---|---|
| 1 | L1-1~13 | 11 | 1 (L1-1 — ADR §11.3이 detail을 정함) | 0 | 1 (L1-7 시드에 없는 상태) | codex 재시도 없이 성공. 그중 L1-6·L1-11은 채택했지만 처리는 기록으로 함 |
| 2 | L2-1~6 | 3 (L2-1·3·4) | 0 | 3 (L2-2·5·6) | Opus 기록 2 (CHECKED_IN closed_at·CANCELLED 알림) | codex 1차·재시도 모두 bwrap `RTM_NEWADDR`로 열람 0 → 3차에 성공(절차 이탈, log에 기록) |
| 3 | L3-1~3 | 3 | 0 | 0 | 0 | codex 1회 성공. L3-2는 codex와 Opus가 같이 지적 |

- 루프 3(마지막)에서도 새로 채택한 지적이 나와 종료 조건을 채우지 못함(review unresolved). 수정분에 codex 타깃 재점검을 돌린 결과 L3-1~3은 해소됐고 새 지적은 0. 병합할지는 사용자가 정함.

### 남은 것
- **500k**: 후처리가 큰 테이블을 통째로 dict에 올림 — 1k 실측 41.8MB로 보면 500k는 약 21GB+로 추정. 기존 500k CSV로 다시 빌드하면 `a0_seed_tables_not_empty`가 실패함 → 메모리를 줄이는 것과 500k-v2 재생성 경로가 NEXT N1의 재개 조건(L1-11).
- **ADR §14 행 수 추정과 실측이 다름**([구현 검증] 결과): 예) seller_grades 2,848(추정 ≈3,900), inbound_reservations 110(≈250), shipment_delays 0.
- **시드에 0건인 상태 종류**: TRIGGERED 가격 알림·PRICE_ALERT 알림·NO_SHOW·배송 지연이 0이라 이와 관련된 검사는 빈 집합으로 통과함. 검출력은 변이 테스트로만 확인했음(L1-7).
- **contract3 green 미검증**: 이번에 확인한 것은 '테이블 없음' 사유가 사라졌다는 것뿐. 엔드포인트·배치를 구현해야 green 여부를 볼 수 있음.
- **다음 작업**: god 구현(contract3 대상). 새 작업 폴더의 spec부터 시작.
