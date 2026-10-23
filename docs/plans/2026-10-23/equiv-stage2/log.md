# log — equiv 2단계 시나리오

> 명세: `requirement-spec.md` · 브랜치 `feat/equiv-stage2`(main `640ef48`)

## 타임라인

| 시각 | 사건 | 결과 |
|---|---|---|
| 2026-10-23 | 인터뷰 8문항 응답 → 명세 작성 | 합의 대기 |
| 2026-10-23 23:36 | 명세 합의(사용자: "합의, auto 진행") | SPEC=1·MODE=auto |
| 2026-10-23 23:37 | task 01 시나리오 4개 작성·실행 워커 위임(Opus, 기준 640ef48) | 진행 중 |
| 2026-10-23 23:5x | task 01 워커 회수: ful 109·refund 117·s2batch 76·settle 74줄 신규(기존 파일 변경 0, lib.sh 무변경). 새 4개 각 2회 rc 0, 4층 같음(ful sql 424·pgstmt 573 / refund 1158·1446 / s2batch 2519·3254 / settle 1878·2300줄). 새 비결정·새 정규화 규칙 0 → 가정 1 실증 | 반대 순서 3곳 god·layered 같은 SQL 순서(INSERT 먼저, UPDATE는 flush 시점) |
| 2026-10-23 | 워커 보고: 시드 이미지 `ccle-postgres-seed:1k-s42`가 docker에 없어 `tools/seedgen/build-image.sh ~/ccle-csv/1k 1k-s42`로 재빌드(verify 위반 0). 500k 이미지도 목록에 없음(디스크 6.7G→54G 여유 — 이 세션 밖 정리로 추정) | 기존 7개는 재빌드 이미지로 run.sh all에서 재확인 |
| 2026-10-23 | 메인 교차 확인: 파일 4개·`git status`(untracked만)·ful sql 208 insert inspections → 210 update order_items 실파일 확인 | 일치 |
| 2026-10-24 00:01 | 변이 jar 3개(infra `add`에 persist 전 `em.flush()` — 검수·분쟁·배분) 빌드 후 소스 되돌림, `git status` 깨끗 | 실행 중 |
| 2026-10-24 00:05 | 변이 실증 3건(`LAYERED_JAR`=변이 jar): 검수 → ful rc=1(sql 37·pgstmt 39줄 diff, `update order_items`가 `insert into inspections` 앞), 분쟁 → ful rc=1(sql·pgstmt 각 23줄, `insert into disputes` 위치 이동), 배분 → s2batch rc=1(sql 265·pgstmt 317줄, `update point_ledger` 앞당김). 세 건 모두 응답·행 층은 같음(순서 차이만 잡힘) | 가정 2 실증. 정리 잔여 0 |
| 2026-10-24 00:08 | README 시나리오 표 4행 추가, all 문구 11종(settle은 PG 거래 덤프 없음 → 규칙 '공통만') | run.sh all ×2 시작 |
| 2026-10-24 00:07 | code 커밋 9a84f3f(tools/equiv 5파일 +381/−1). 리뷰 packet: OUT=scratchpad/rv.kwP2, 미러=scratchpad/mirror.ME9K(base 640ef48, untracked spec 1, 시크릿 스캔 0) → Opus ∥ codex 1패스 시작 | 진행 중 |
| 2026-10-24 00:1x | 리뷰 1패스 회수: codex(try 1, 판정 불가 아님) 2건 + OQ 1 / Opus 5건 + OQ 4 | 종합 ↓ |

## 리뷰 ledger (stakes 중간 — 듀얼 1패스)

| id | loop | source | 근거 | disposition | status | 내용 |
|---|---|---|---|---|---|---|
| R1 | 1 | codex·opus·감사 | `tools/equiv/scenarios/settle.sh:62-73` | 채택 | fixed(5a34620) | weekly-settlement 앞 auto-confirm(시드·A3 EARN 확정·잔액)과 정산 뒤 분쟁 인정(CANCEL·REVOKE·disputes)이 바꾸는 point_ledger·point_allocations·point_balances·pg_transactions·disputes·orders를 덤프하지 않음. SQL 두 층은 바인드 값을 비교하지 않아 값 차이를 놓침(spec §2 시드 digest·§1 4층) |
| R2 | 1 | opus | `settle.sh:41,51`, `ful.sh:74,80`, `refund.sh:75` | 채택 | fixed(5a34620) | 분쟁 id를 `max(id)`로 잡고 생성 성공을 확인하지 않음 — 제기가 실패하면 다른 분쟁(D2·시드)을 판정해 경로가 조용히 바뀜(§5 커버리지, §2 정직성). 두 앱에 같은 회귀가 있으면 exit 0 |
| R3 | 1 | opus | `settle.sh:69-73`, `refund.sh:110-115`, `s2batch.sh:70-73` | 채택 | fixed(5a34620) | 시드 md5는 0행이어도 한 줄이라 0줄 검사를 통과 — '시드 전체' 경로(§5)를 지났는지 하네스가 강제하지 않음 |
| R4 | 1 | opus | `settle.sh:71` | 채택 | fixed(5a34620) | `coalesce(settlement_id-$ms,0)`가 NULL과 `settlement_id=ms`를 같은 값으로 접음 — 목록 밖 정보 손실(§2) |
| R5 | 1 | opus·codex(렌즈 7) | `tools/equiv/README.md:41-44` | 채택 | fixed(5a34620) | 시드 md5는 refund·s2batch도 쓰는데 settle만 표기, refund의 가짜 PG 직접 취소 기록(하네스 조작)이 README에 없음 |
| R6 | 1 | codex·opus(OQ) | `refund.sh:40,63`, ADR-0004:131 | 기각 | — | 계약 밖 (b)가 ADR 노트의 자연 경로(응답 없는 취소 기록 + 다른 항목 환불로 '마지막 환불' 금액 변화)가 아니라 1원 기록 주입. 기각 근거: spec §0 Q/A·합의 질문에서 "하네스가 가짜 PG에 다른 금액의 취소 기록을 먼저 만들어 재현"으로 합의, 자연 경로는 FakePg 취소에 TIMEOUT이 없어 금지영역(§4 behavior-tests/) 변경 필요. 한계로 README·NEXT에 기록 |
| OQ1 | 1 | opus | `refund.sh` 계약 밖 (a) | open question | — | 체결가 < 10원을 검수 FAIL로만 재현 — shipment-deadline 배치 경로(그 밖의 예외는 배치 중단)는 미포함. spec 문구는 경로를 정하지 않음 → NEXT 기록 |
| OQ2 | 1 | opus | `s2batch.sh` 주석 '쓰지 않은 묶음' | open question | 해소 | 픽스처에 미사용 묶음 없음 — 시드 묶음 소멸 여부를 실행 결과로 확인 |
| A1 | 감사 | codex 감사 | `settle.sh` 덤프부(R1 범위) | 채택(R1 보강) | fixed(5a34620) | R1 채택 목록에 inspections 누락 — settle은 PASS·FAIL 검수 행을 만든다. 덤프 추가(8행) |
| A2 | 감사 | codex 감사 | R6 근거 | 판정 정정 | — | spec §0에는 주입 방식 합의가 적혀 있지 않다(합의 질문 본문에만 있었음). R6은 '§5 문구(취소 기록 금액 불일치 → 409)는 충족, ADR-0004 §7.2 노트의 자연 경로는 재현 안 함(FakePg 취소에 응답 없음 결과가 없어 §4 금지영역 변경 필요) — 한계로 README 기록'으로 정정 |

감사 '검증 결과 확인 누락' 지적: 단독 실행·변이 3건·all 결과는 타임라인에 있음(채택표에는 미기재였을 뿐) — 조치 없음.

## 타임라인 (계속)

| 시각 | 사건 | 결과 |
|---|---|---|
| 2026-10-24 00:24 | `run.sh all` 1회차(9a84f3f, 재빌드 시드 이미지): 11개 전부 같음 rc=0(batch 97·conc 179·errors 51·fail 128·ful 71·pay 58·recov 87·refund 93·run 53·s2batch 79·settle 83초 = 1,079초) | 기존 7개 재빌드 이미지에서 불변. 2회차는 수정 뒤로 미루고 중단(정리 잔여 0 확인 — 정리 중 `pkill -f`가 자기 셸을 맞혀 exit 144, 잔여 프로세스·컨테이너 0 재확인) |
| 2026-10-24 00:2x | OQ2 해소: s2batch god rows point_ledger EXPIRE 27건에 시드 사용자 행(user 204·361·522 …) | 시드 미사용 묶음 소멸 확인 |
| 2026-10-24 00:3x | R1~R5 수정 → 새 4개 단독 실행: ful·refund·s2batch·settle 모두 rc=0 같음(settle 새 행 14파일 74줄) | need·open_dispute 통과 |
| 2026-10-24 00:4x | codex 종합 감사(try 1): 판정 오류 1(A2)·누락 2(A1, 검증 기록) | A1 채택 → settle inspections 덤프, settle 재실행 rc=0(15파일 82줄) |
| 2026-10-24 00:5x | code 커밋 5a34620(5파일 +53/−12). `run.sh all` ×2 시작, codex post-fix 타깃 재점검 시작 | 진행 중 |
| 2026-10-24 01:0x | codex post-fix 타깃 재점검(try 1, recheck.qVKQ): 신규 finding 1 — settle seed.md5가 `id<=$ml`만 읽어 시드 사용자의 새 원장 행 제외, README와 불일치 | 판정: 비교 누락 아님(새 행은 `settle.sh:81` `id>$ml` 덤프로 줄 단위 비교 — md5보다 엄밀) → 결함 기각, README 문장만 정확히(6f47bc6). 나머지 확인 항목(open_dispute die 전파 inherit_errexit·need 건수·오프셋·정규화) 이상 없음 |
| 2026-10-24 01:3x | 최종 검증: `run.sh all` ×2(5a34620 — 이후 6f47bc6은 README만) 둘 다 rc=0, 11개 전부 같음(1회차 합 893초·2회차 904초). 새 4개 층별 줄 수 1패스와 같음(ful sql 424·pgstmt 573 / refund 1158·1446 / s2batch 2519·3254 / settle 1878·2300). 금지영역 diff(640ef48..HEAD -- god layered behavior-tests tools/behavior tools/seedgen docker docs/adr) 0, `ccle-equiv-*` 컨테이너·앱 프로세스 0, 디스크 54G | 검증 완료. 리뷰 미러 2개 삭제 |
| 2026-10-24 | 리뷰 종료 판정(中): 채택 R1~R5·A1 전부 fixed, post-fix 재점검 신규 채택 0(1건 기각 — 비교 누락 아님, README 문장 보정) | 종료 |

## 생략한 검증

- 없음. (변이 jar의 대상은 infra `add` 3곳의 flush 주입 — 앱 코드 원본은 매번 되돌림, `git status` 확인)
- CS 이슈 아카이브: study-note 쓰기 금지(spec §4) → 보류(NEXT 보류·이월 항목에 추가)

## 완료 요약

### 결과

`tools/equiv/scenarios/`에 2단계 시나리오 4개(`ful`·`refund`·`s2batch`·`settle`)를 추가했다. 기존 파일 변경은 README뿐이고 lib.sh는 그대로다. 새 비결정과 새 정규화 규칙은 0건이어서 가정 1이 실증됐다. 새 4개의 층별 줄 수는 ful sql 424·pgstmt 573, refund 1158·1446, s2batch 2519·3254, settle 1878·2300이다. 반대 순서 3곳(검수 PASS·분쟁 제기·포인트 소멸·회수)에서 god와 layered가 내보내는 SQL 순서가 같았다(INSERT가 먼저, UPDATE는 flush 시점). 변이 실증 3건은 모두 rc=1이었고 응답·행 층은 같았으며 sql·pgstmt 층에서만 차이가 났다. 그래서 가정 2도 실증됐다. 3건의 내용은 이렇다. ①검수: ful, sql 37·pgstmt 39줄 ②분쟁: ful, 각 23줄 ③배분: s2batch, sql 265·pgstmt 317줄. 최종 `run.sh all`은 2회 모두 rc=0이었고 11개 시나리오가 전부 같았다(893초·904초). 금지영역 diff는 0, `ccle-equiv-*` 컨테이너와 앱 프로세스 잔여도 0이다. 리뷰는 듀얼 1패스(codex 2건+OQ 1, Opus 5건+OQ 4) 뒤 codex 종합 감사(A1·A2), post-fix 타깃 재점검 순으로 진행했다. 채택한 R1~R5와 A1은 전부 고쳤고(5a34620), 재점검에서 새로 채택한 것은 0건이다(1건은 기각하고 README 문장만 보정, 6f47bc6). 이로써 layered-stage2 log의 R1-3(반대 순서 3곳을 코드 대조로만 판정한 부분)이 실측으로 닫혔다. 남은 한계는 둘이다. ①계약 밖 (b)는 가짜 PG에 1원 취소 기록을 주입해 재현했을 뿐, ADR 노트의 자연 경로는 재현하지 않았다(R6·A2). ②계약 밖 (a)는 검수 FAIL로만 재현했고 shipment-deadline 배치 경로는 넣지 않았다(OQ1). 둘 다 NEXT 대상이다.

### 커밋

| 커밋 | 파일 +/− | 내용 |
|---|---|---|
| 9a84f3f | 5파일 +381/−1 | 시나리오 4종 신규(ful 109·refund 117·s2batch 76·settle 74줄) + README |
| 5a34620 | 5파일 +53/−12 | 리뷰 R1~R5·A1 수정(비교 범위·`open_dispute`·`need`) |
| 6f47bc6 | 1파일 +1/−1 | README: 시드 md5와 새 행의 줄 단위 비교를 구분 |

- 파일 +/−는 `git show --stat` 기준이다.

### 핵심 diff (실파일 발췌)

①분쟁 id 확정과 경로 통과 강제(R2·R3): `tools/equiv/scenarios/settle.sh:20-23`

```bash
# open_dispute <항목 id>: 이 시나리오에서 만든 그 항목의 OPEN 분쟁 id(없으면 실패 — 제기가 실패한 채 다른 분쟁을 판정하지 않게)
open_dispute() { local d; d=$(q "SELECT id FROM disputes WHERE id>$md AND order_item_id=$1 AND status='OPEN'"); [[ $d =~ ^[0-9]+$ ]] || die "분쟁 준비 실패: 항목 $1 → '$d'"; echo "$d"; }
# need <이름> <건수>: 시드 경로를 실제로 지났는지 — 0이면 실패(두 앱이 함께 0이어도 같음으로 끝나지 않게)
need() { [[ $2 =~ ^[0-9]+$ ]] && [ "$2" -gt 0 ] || die "경로를 지나지 않음: $1 = '$2'"; }
```

②`need` 사용처, 시드 묶음 소멸: `tools/equiv/scenarios/s2batch.sh:53`

```bash
n=$(q "SELECT count(*) FROM point_ledger WHERE id>$ml AND kind='EXPIRE' AND user_id<$base"); need "시드 묶음 소멸" "$n"
```

③검수 PASS 호출(반대 순서 1번째 경로): `tools/equiv/scenarios/ful.sh:52-54`

```bash
mark inspection-pass
call POST "/admin/order-items/$I1/inspection" "" '{"result":"PASS","reason":"무시되는 사유"}'
call POST "/admin/order-items/$I3/inspection" "" '{"result":"PASS","reason":null}'
```

④이 경로에서 god가 내보낸 SQL 순서: `build/equiv/ful/god/norm/sql/sql.txt:207-210`(120자에서 자름). `build/equiv/ful/layered/norm/sql/sql.txt:207-210`도 같은 내용이다. INSERT가 먼저 나가고 `update order_items`는 flush 시점에 나간다.

```text
select oi1_0.id,oi1_0.buyer_fee,oi1_0.cancelled_at,oi1_0.card_share,oi1_0.confirmed_at,oi1_0.delivered_at,oi1_0.inspecte
insert into inspections (inspected_at,order_item_id,reason,result) values (?,?,?,?)
select r1_0.id,r1_0.buyer_fee_amount,r1_0.card_amount,r1_0.created_at,r1_0.fault,r1_0.order_item_id,r1_0.point_amount,r1
update order_items set buyer_fee=?,cancelled_at=?,card_share=?,confirmed_at=?,delivered_at=?,inspected_at=?,order_id=?,p
```
