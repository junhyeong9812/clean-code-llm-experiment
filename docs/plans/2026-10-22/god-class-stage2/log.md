# log — 갓 클래스 구현 2단계 (god)

> 작업 폴더: `docs/plans/2026-10-22/god-class-stage2/` · 기준: main `f7dde74`

## 타임라인

| 시각 | 사건 | 결과 |
|------|------|------|
| 2026-10-22 | 착수 — 사용자 "다음 진행해줘" → NEXT N1 | 작업 폴더 생성, 인터뷰 시작 |
| 2026-10-22 | 인터뷰 6문항 응답, 명세 합의(auto) | SPEC=1·MODE=auto, 브랜치 feat/god-class-stage2 |
| 2026-10-22 | task 01(§8.1 적립률) Opus 워커 착수 | 기준 c3d7c22 |
| 2026-10-22 11:27 | task 01 회수 — ea0ff42(Marketplace.kt +23/−1, 네이티브 SQL 1문장) | contract2 3/82(적립률 3개), 1단계 exit 0(infra 28·contract 117·verify 0). 메인 교차 확인: approveOrder 호출처 2곳(동기 357·pg-inquiry 964), 기동 전환은 UNKNOWN 전환만 → 승인은 pg-inquiry 경유 |
| 2026-10-22 | task 02(엔티티·§2·항목 조회·§3 PASS까지) Opus 워커 착수 | 기준 ea0ff42, 검수 FAIL은 task 03으로 |
| 2026-10-22 11:35 | task 02 회수 — dc106a4(+214/−4, 5파일) | contract2 8/82(메인 XML 집계 일치, skip 0), 1단계 exit 0, gradlew build 통과. 실패 74 전부 미구현 경로. 검수 FAIL은 임시로 400 UNEXPECTED_ERROR(롤백) |
| 2026-10-22 | task 03 Opus 워커 착수 — 1단계: 잠금·트랜잭션·PG 위치·금액·포인트 계획만(설계 선검증) | 기준 dc106a4 |
| 2026-10-22 11:41 | task 03 계획 회수(설계 선검증) | 잠금: 주문 → [PG] → 사용자(id 오름차순), PG는 주문 잠금 보유 중 한 트랜잭션, PG 전 쓰기 없음 → 롤백으로 무변경. 금액 공식 §12 A1·A2·E1~E7 손계산 일치 |
| 2026-10-22 11:43 | 메인 판정: 승인 + 수정 2건 | A: 항목 행 FOR UPDATE가 없어 실효 순서가 주문 → 사용자 → 항목 — 정산(항목 잠금 + users FK KEY SHARE)과 교착 가능 → lockOrderOf에 항목 FOR UPDATE 추가(§9.1 순서). B: 분쟁 제기 응답 4필드(§4.3). REVOKE order_id=주문(ADR 우선, 생성기와 다름), RESTORE 뒤 EXPIRE의 order 열 NULL, 만료 미처리 묶음도 REVOKE 승인 |
| 2026-10-22 11:52 | task 03 회수 — 8c397ce(+413/−7, 6파일) | contract2 49/82(메인 XML: fail 33 전부 배치 미구현 32 + /me/settlements 1, skip 0), 1단계 exit 0·verify 0. 수정 A·B 반영 확인(lockOrderOf 항목 FOR UPDATE, DisputeOpened 4필드) |
| 2026-10-22 | task 04(배치 4종) Opus 워커 착수 | 기준 8c397ce, 배치 소요 시간 측정 포함 |
| 2026-10-22 12:03 | task 04 회수 — f375b3e(+129/−2) | contract2 69/82(메인 XML 확인: 실패 13 = weekly-settlement 12 + /me/settlements 1), 1단계 exit 0. 배치 최장 point-expiry 26.7s(processed 2,162)·grade-recalc 4.2s. grade-recalc 후보 사전 필터 한계는 §8.5 '실행 시점 상태' 범위로 수용 |
| 2026-10-22 | task 05(주간 정산) Opus 워커 착수 — 1단계 계획만(설계 선검증: 주 단위 트랜잭션·잠금·소요 시간) | 기준 f375b3e |
| 2026-10-22 12:10 | task 05 계획 회수(설계 선검증) | 한 주 = tx 1개: 대상 order_items id 순 FOR UPDATE → 같은 조건 재평가 → 패널티·이월 → 판매자별 persist. 정산은 사용자 잠금(FK KEY SHARE) 전에 항목을 모두 잠그고, 항목 처리는 항목 1개만 쥠 → 순환 없음. S1~S4 손계산 일치. 주당 40~80ms 추정(30~40주 ≈ 3s). 워커가 프로브 DB(ccle-plan-probe, 15499)를 조회·EXPLAIN용으로 띄우고 삭제 |
| 2026-10-22 12:11 | 메인 판정: 승인 | 컨트롤러는 OrderController에. 500k 성능 위험(CONFIRMED 안티조인·행 단위 persist)은 ADR 그대로 두고 NEXT에 기록. 0건 분기 스모크는 메인이 task 06에서 |
| 2026-10-22 12:19 | task 05 회수 — 3637456(+223/−5, 4파일) | contract2 82/82(메인 XML 확인), 1단계 exit 0·verify 0, build 통과. weekly-settlement 최장 2,416ms(processed 114) |
| 2026-10-22 12:20 | task 06 착수 — 검증 백그라운드(god contract2 2회차 → layered 1단계) | 로그 scratchpad/t6-c2b.log·t6-lay.log |
| 2026-10-22 12:23 | 리뷰 루프 1 packet — BASE c3d7c22, OUT=scratchpad/rv.CcSu, MIRROR=scratchpad/mirror.23IK | 누적 diff 7파일 +984/−8, untracked 0, 보안 스캔 시크릿 0(로컬 경로 1건 = 기존 문서, 유지). Opus ∥ codex 병렬 착수 |
| 2026-10-22 | codex 리뷰 1회차 실패 — bwrap `Failed RTM_NEWADDR`로 미러 읽기 불가, exit 0으로 '판정 불가' 출력 | 출력 내용으로 실패 판정하도록 바꿔 재시도(최대 3회) |
| 2026-10-22 | codex 리뷰 재시도 — try1 bwrap 재발, try2 정상 | findings 0, open question 0, 렌즈 8개 전부 applicable·확인(근거 file:line 있음) |
| 2026-10-22 12:25 | 검증 2회차·layered | god contract2 82/82(2회 연속 green, skip 0) · layered 1단계 rc 0(infra 28·contract 117·verify 0) — layered 무변경 확인 |
| 2026-10-22 12:33 | 수동 스모크 ① 기동 전환 적립률(scratchpad/smoke1.sh) | 1회차 무효: 구매자 선택 쿼리 버그로 주문일 등급이 이미 VIP인 사용자(판별 불가) → 쿼리 수정. 2회차: buyer 6(주문일 10-08 최신 이력 GOLD, 10-09 VIP 이력 + users.grade=VIP 주입) → 결제 중 kill -9 → PAYING → 재기동 UNKNOWN(+APPROVE UNKNOWN 행) → pg-inquiry PAID, EARN 2,000 = GOLD 2%(VIP면 3,000). **통과** |
| 2026-10-22 12:45 | 수동 스모크 ② 정산 0건 분기(scratchpad/smoke2.sh, 정산 행·연결 비운 1k DB) | [A] 10-08 → 09-28 주 195행(processed 195, 과거 미정산 패널티 175건 전부 이 주에 연결 — ADR 문자대로), 재실행 0, 시계 10-19 00:00 → 10-05 주 31행만. 10-12 주 미생성은 대상 0·음수 이월 0·미정산 패널티 0 확인 → §10.2 '행 없음'(내 기대값 오류). paid_at 불일치 0 · [B] 10-05 00:00 정각 → 09-28 주 · [C] 10-04 23:59:59 → 09-21 주 · 앱 ERROR 0. **통과** |
| 2026-10-22 12:40 | Opus 리뷰 루프 1 회수 | findings 2(F1 0원 패널티 CHECK 위반이 PG 취소 뒤, F2 배치 정지) + open question 5 |
| 2026-10-22 12:46 | 메인 종합 → codex 감사 착수 | R1-1(F1+F2) 채택·도달 가능(Marketplace.kt:128 price>0만) — ADR 공백이라 재합의 필요, R1-2(PG 무응답 뒤 다른 항목 환불로 금액 변경) 사용자 결정, R1-3 기각·R1-4 NEXT·R1-5·R1-6 도달 불가 기각 |
| 2026-10-22 12:52 | codex 종합 감사 회수(try 1 정상) | R1-1 결론 동의(근거 조항은 §2.1이 아니라 §2.3으로 정정), F2는 병합 대신 배치 포이즌 대상 방침 질문으로 분리, R1-3은 '영향 없음' 근거 부족 → open question으로만. R1-2·R1-4~6 동의 |
| 2026-10-22 12:58 | 사용자 결정(재합의 1) | R1-1 "계약 밖으로 기록", R1-2 "기존 '계약 밖'에 포함" → ADR-0004 §6·§7.2 개정(코드 변경 없음), spec §4 재합의 1 기록 |

## 리뷰 ledger

| id | first_seen_loop | source | 근거 | disposition | status | fixed_in_loop |
|----|-----------------|--------|------|-------------|--------|---------------|
| R1-1 | 1 | opus(+감사 근거 정정) | Marketplace.kt:956(패널티 persist가 :1001 PG 뒤), tables.sql:248, Marketplace.kt:128(price > 0만) — 체결가 1~9원 → 0원 패널티 CHECK 위반 → 카드만 취소 | 채택(도달 가능, 단 시드·테스트·verify 밖) | fixed(ADR §6 계약 밖 개정 — 사용자 결정) | 1 |
| R1-2 | 1 | opus | Marketplace.kt:994-1001, :1055-1072, ADR-0004 §7.2 — PG 기록 후 무응답 사이 다른 항목 환불로 금액 변경 → 409 반복 | open question → 사용자 결정 | fixed(ADR §7.2 계약 밖 확장) | 1 |
| R1-F2 | 1 | opus(+감사) | Marketplace.kt:1292-1306, :1441-1458 — PG_UNAVAILABLE 외 예외면 배치 전체 정지 | open question | user-deferred(NEXT N3 기존 질문 '배치 대상 1건 실패 시 전체 중단'에 2단계 배치 포함으로 기록) | — |
| R1-3 | 1 | opus | Marketplace.kt:787 isNullOrBlank vs "앞뒤 공백" 문자 집합 미정 | open question | user-deferred(NEXT — layered가 같은 판정을 쓰는지 N2에서 확인) | — |
| R1-4 | 1 | opus | Marketplace.kt:1563-1605 OR 조건 순차 스캔 — 500k 정산 시간 | 범위 밖(spec 가정 2는 1k) | user-deferred(NEXT) | — |
| R1-5 | 1 | opus | Marketplace.kt:1606, 1617 CLAWBACK fee 재계산 | 기각(율 변경 경로 없음 — 도달 불가) | — | — |
| R1-6 | 1 | opus | Marketplace.kt:1601 vs 1605 잠금·재평가 스냅샷 차이 | 기각(auto-confirm 선행·기각 항목은 HELD로 잠김 — 도달 불가, 감사 동의) | — | — |

- codex 루프 1: findings 0, 렌즈 8개 applicable·확인. codex 실행: 1회차 bwrap 실패(exit 0 '판정 불가') → 재시도 try1 bwrap, try2 정상.
