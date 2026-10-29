# log — 판정기 contract3 연결 + equiv 단계 A 시나리오

> 작업 폴더: `docs/plans/2026-10-29/judge-equiv-stage-a/` · 브랜치 `impl/judge-equiv-stage-a`(main `b889129`) · 산출물 유형: 실행물(판정기·equiv) + ADR-0005 §4.1 개정

## 타임라인

| 시각 | 사건 | 결과 |
|---|---|---|
| 2026-10-30 01:37 | layered 단계 A 병합·push(`b889129`) 뒤 인터뷰 4문항(한 작업에서 차례로 · 별도 단계 contract3 · 묶음별 3 + 잠금 1 · layered를 god에 맞춤) → spec 합의 → set-state(SPEC=1·auto) | task 01·03 착수 |
| 2026-10-30 01:37 | 병렬 위임: je-01(판정기 contract3 단계 + 러너 테스트 + ADR-0005 §4.1 — docker 미사용, 실판정 스모크 명령만 보고) ∥ je-03(equiv sa-a·sa-b·sa-c·sa-lock + README, docker 단독 사용, 차이 시 layered를 god에 맞춤 + 재검증) | 대기 |
| 2026-10-30 01:41 | je-01 회수: STAGES+contract3, REGRESSION_TASKS 매핑(결과 XML 경로·verify 조건 공용), BASELINE contract3 168, regression_pass = 회귀 3단계 AND, judge-stage.sh `task=${stage}Test`, 새 테스트 19(test_contract3_stage — 결과 경로 섞임 없음·infra/fail/count_mismatch·부분 판정·셸 실제 실행), contract2 grep 전수 표 누락 0. 메인 재실행 443 OK. 커밋 code·ADR 분리. 실판정 스모크는 equiv(je-03)의 docker 사용 뒤 메인이 실행 | 스모크 대기 |
| 2026-10-30 02:32 | je-03 회수: sa-a(§4·5·6·10·2.7 — 배치 5종·계산서 정정·지급 계좌·리뷰·감사)·sa-b(§7·8·12.2 — 6종 알림·가격 알림 TRIGGERED/EXPIRED/제한·추적·지연 2종)·sa-c(§9·11·12.1·12.3 — 프로필·슬롯·예약 판정 순서·문의·자동 종결·시세·관리자 7종 키셋)·sa-lock(§19의 9-1·9-2·10-1·10-2 — 직렬 lock_seq 순서 단언 + 동시 쌍 × CONC_N 5, 교착 0). need 단언으로 경로 통과 확인(TRIGGERED의 INSERT 바로 다음 UPDATE 포함). **첫 비교부터 앱 차이 0 — 앱 수정 없음**, 새 정규화 규칙 0(sa-lock 동시 구간은 기존 norm_set_from_mark·norm_conc_pair 재사용). 시나리오 자체 결함 4건은 god 실행 exit 2로 잡혀 시나리오 쪽에서 고침. sa-lock 단독 3회 같음. **`all` 16/16 같음 exit 0**(메인 all.out 확인). 커밋 `e9c0d7e` | 스모크·음성 대조 |
| 2026-10-30 02:34 | 리뷰 1회차 시작(미러 je/mirror, diff b889129..e9c0d7e): Opus jr1-judge ∥ Opus jr1-equiv ∥ codex. codex 회수(bwrap 0): 판정기 지적 없음 · equiv major 1(배치 재실행 0건 미단언 — batch2가 두 번째 응답 processed=0을 need로 확인 안 함) · minor 1(TRIGGERED 순서 검사가 대상 알림에 묶이지 않음) · 기존 판정·12개 불변 지적 없음 | Opus 대기 |
| 2026-10-30 02:37 | 1회차 Opus jr1-judge 회수: blocker 0·major 1·minor 3·nit 3 — contract3 누락 지점 grep 전수 지적 없음, 결합 ADR §4.1 일치, 기존 valid 행 오인 없음. 메인 확인: `experiment/results*/held` 디렉토리 없음(JR-2 영향 대상 0) | equiv Opus·스모크 대기 |
| 2026-10-30 02:40 | 1회차 Opus jr1-equiv 회수: blocker 0·major 1(=JR-8, 첫 실행 failed도 미확인 — dedupe 식 어긋남은 UNIQUE 위반이 격리 실패로 숨어 행 수 단언을 통과)·minor 7. 커버리지 표: A1~A12·배치 11/11·§19 4경로 밟음, 빠진 곳 = 새 배치 격리(§2.6)·§19-2·5·6 입력 일부·9-2/10-2 교착 상대 동시 쌍·A10 휴가 키 생략 PUT. M2: 계획한 음성 대조(TRIGGERED 순서)는 sa-b need가 layered에서 die → exit 2(차이 층 검출이 아님) — 차이 층 실증용 대조를 따로 | 스모크 대기 |
| 2026-10-30 03:03 | 실판정 스모크(_smoke, --patch none, contract·contract2·contract3, claude 없음): god·layered 모두 122·89·168 pass, verify ok, success True·regression_pass True(각 약 695초) → load-bearing 가정 1 실증. 깬 사본(JR-1 — layered 문의 SLA 48h→47h, contract3만): outcome fail·tests 168·실패 14(A6 13·A9 audit-logs 1)·failure_messages 10·success False·regression_pass False → 실패 집계 실증. 첫 음성 대조 시도는 worktree 빌드의 JAVA_HOME 미설정(Java 8)으로 빌드 실패 → je-04에 재위임. JR-3(단계별 기준 수 — 테스트 +1·기존 테스트 강화)·JR-5·JR-6 메인 수정, 러너 테스트 444 OK, 커밋 `a7369dd`(code)·`b636ce5`(README·ADR) | je-04 위임 |
| 2026-10-30 03:51 | je-04 회수: JR-8~21 반영(lib.sh 새 함수 6개만 — batch_run·inject_on/off·int_or_die 등, 기존 함수 무변경), 앱 차이 0·앱 수정 0. 음성 대조 (a) TRIGGERED 순서 뒤집은 layered jar → sa-b exit 2(need 'TRIGGERED 문장 순서' die) (b) 문의 답변에 조회 1문 추가 jar → sa-c exit 1, sql·pgstmt 층 diff(+4줄 — 답변 4건), 응답·행 층 같음 → 차이 층 검출 실증. 단독 4개 같음, `all` 16/16 exit 0(메인 all.out 확인), worktree 정리 확인. 커밋 `22dd2d4` | 리뷰 2회차 |
| 2026-10-30 03:55 | 리뷰 2회차(미러 je/mirror2, fix diff e9c0d7e..22dd2d4): codex 회수(bwrap 0) — 1회차 채택 항목 재점검 지적 없음(batch_run 형식·격리 주입 제거·excl_touches·새 동시 쌍 기대 조합 유일), 기각 3건 근거 타당, 신규 0 | Opus 대기 |
| 2026-10-30 04:01 | 2회차 Opus jr2-opus 회수: blocker 0·major 0·minor 3(재점검 2·기각 근거 재검토 1) — batch_run 정규식·inject 제거·int_or_die·새 동시 쌍 기대 조합 유일·excl_touches 판정 확인, 신규 결함 0. JR-22·JR-24 메인 수정(새 격리 단계 3곳에 iso 식 로그 건수 단언 + log-counts 덤프, sa-lock 문의 덤프에 order_item_id 오프셋), JR-23은 NEXT 기록 → 시나리오 4개 재실행 | 재실행 대기 |
| 2026-10-30 04:08 | 재실행: sa-a·sa-b·sa-c·sa-lock 모두 같음 exit 0, log-counts god(=layered) sa-a·b·c '1 1'(격리 실패 로그 1 = 응답 failed 합 1), sa-lock '0 0'. 커밋 `821f68e` → 리뷰 3회차(마지막) | - |
| 2026-10-30 04:15 | 리뷰 3회차 codex 회수(try1, bwrap 1이나 판정 완료): 재점검 지적 없음(JR-22·JR-24 확인, 기각 근거 타당), 신규 minor 1(sa-lock n_unexp 미단언 + 직렬 오류 요청 기대 코드 미확인 → 두 앱이 함께 400 UNEXPECTED_ERROR면 통과) | Opus·all 대기 |
| 2026-10-30 04:15 | 3회차 Opus jr3-opus 회수: 재점검 지적 없음(JR-22·24·8·10·13·16·3·9 확인, 기각 근거 타당 — JR-14 근거인 contract3 동시성 테스트 실재 확인), nit 1(JR-26 test docstring 수정 전 의미), 신규 minor 1(JR-27 ADR-0006 §6.1-5 '1·2회차 비교용 contract·contract2만 본 성공' 보조가 판정기에 없음). JR-26·JR-27 메인 수정 — success_stage12 + JUDGE_FIELDS + 테스트 1 + ADR-0005 §4.1-3 보조 문장, 러너 테스트 445 OK, 커밋 `dc97a39`(code)·`ec02f65`(ADR). JR-25는 all 실행 뒤 sa-lock 수정 | all 대기 |
| 2026-10-30 04:37 | 최종 `all` 16/16 같음 exit 0(821f68e 기준). JR-25 메인 수정(sa-lock 직렬 오류 요청 4곳 404 NOT_FOUND need + 예상 못 한 오류 0 need) → sa-lock 단독 같음 exit 0, 커밋 `5e26134`. 3루프 상한 도달 — 3회차 수정분(JR-25·26·27)은 post-fix 재점검 1회(codex, diff 821f68e..5e26134) | 재점검 |
| 2026-10-30 04:40 | post-fix 재점검(codex, 821f68e..5e26134, bwrap 0): JR-25·26 지적 없음, JR-27 수정분 major 1(JR-28: 빌드 실패·jar 없음·테스트 컴파일 실패 조기 반환에 success_stage12 없음) → 메인 수정(조기 반환 4곳에 success_without_regression과 같이 기록) + 행동 테스트(가짜 빌드 rc 1 compile → False·rc 125 infra → None), 변이 확인(수정 되돌리면 테스트 실패), 러너 테스트 446 OK. 커밋 `f77c536`. 루프 상한 도달 — JR-28 수정은 셀프 확인(변이 테스트)으로 종료 | 마감 |
| 2026-10-30 05:26 | 사용자 결정 '병합·push 후 다음으로' → main fast-forward b889129..4a1bfe0 | push |

## 리뷰 ledger

| ID | 출처 | 지적 | 판정 | 처리 |
|---|---|---|---|---|
| JR-1 | opus major | ADR-0006 §6.1-5가 요구하는 '일부러 깨뜨린 사본'으로 contract3 실패 집계 확인이 계획에 없음(스모크는 무수정, 음성 대조는 equiv용) | 채택 | fixed — 깬 사본 실판정 fail·실패 14·success False·regression_pass False |
| JR-2 | opus minor | held 재판정이 현재 STAGES(5단계)로 돌아 단계 A 이전 사본을 contract3 실패로 확정할 수 있음 | 채택(기록) | held 디렉토리 0 — 영향 대상 없음. 다음 파일럿 전 held 비어 있음 전제를 NEXT에 |
| JR-3 | opus minor | regression-exclude가 하나라도 있으면 contract3 기준 수 검사까지 꺼짐(C2는 contract2 메서드 하나만 제외) | 채택 | fixed a7369dd — excl_touches(다른 회귀 단계 패키지로 시작하는 패턴만 비접촉), ADR §4.1-4 명시 |
| JR-4 | opus minor | SlowTrigger 경합 관측(8초) 테스트 19개가 병렬 판정 부하에서 타이밍 실패 → 확정 fail로 셀 위험 | 기각(분류 변경) — 기록 | '경합 미관측'을 infra로 돌리면 잠금을 없앤 패치(진짜 회귀)도 무효 처리됨. 파일럿에서 회귀 실패 메시지를 보고 실측(NEXT) |
| JR-5 | opus nit | test_contract3_stage.py:239 `assertIn("-I", calls)` 약한 단언 | 채택 | fixed a7369dd |
| JR-6 | opus nit | README.md:101 contract3 162개(red) — 현재 168 green | 채택 | fixed b636ce5 |
| JR-7 | opus nit | 부분 판정 --stages contract,contract2 의 regression_pass가 True→None | 기록 | 사용처 없음(문서 예는 public,hidden·hidden) |
| JR-8 | codex major | 배치 재실행 0건 미단언(sa-a·sa-b·sa-c batch2) | 채택 | fixed — batch_run 첫 failed 0·재실행 0 0 |
| JR-9 | codex minor | TRIGGERED 순서 검사가 대상 알림에 묶이지 않음 | 채택 | fixed — DB 문장 로그 바인드(dedupe 키·WHERE id)로 대상 한정 |
| JR-10 | opus major(M1 확장) | 첫 배치 응답 failed 미확인 — dedupe 어긋남이 격리 실패로 숨음 | 채택 | fixed(JR-8과 함께) |
| JR-11 | opus minor(커버리지) | 새 배치 11개 건별 격리(ADR-0007 §2.6)가 equiv에 없음(iso.sh는 기존 배치만) | 채택(일부) | fixed — monthly-statement·price-alert-scan·inquiry-auto-close 격리(failed 1 → 재처리 1 0 → 0 0) |
| JR-12 | opus minor(커버리지) | §19-2 `5.0`·§19-5 확장 연도·§19-6 NBSP, A10 휴가 키 생략 PUT(§19-14) 없음 | 채택 | fixed — 5.0·NBSP·확장/음수 연도·휴가 키 생략 null |
| JR-13 | opus minor(커버리지) | §19 9-2·10-2의 교착 상대(판매자 귀책 경로·입찰 등록)와의 동시 쌍 없음 | 채택 | fixed — review-fault(200 201)·wish-bid(201 201), 교착 0 |
| JR-14 | opus minor(커버리지) | 슬롯 생성·정원·프로필 첫 생성·지급 계좌 요청 동시 경합, 잠근 뒤 재확인 건너뜀 | 기각 — 기록 | contract3 동시성 테스트가 응답·DB를 단언, 같은 잠금 문장은 직렬 구간 SQL 층에서 비교됨. 추가 비용 대비 낮음 |
| JR-15 | opus minor m1 | sa-lock.sh:88·121 docker logs 명령 치환 실패가 0건 통과로 바뀜 | 채택 | fixed |
| JR-16 | opus minor m2 | 시드 id(seed_size·set_seller·initial_user) 미검증, INITIAL 경로 미확인 | 채택 | fixed — int_or_die, INITIAL need |
| JR-17 | opus minor m3 | 응답에만 걸린 경로(I5 DEADLINE_PASSED·정각 취소 200·맞는 입찰 없음) need 없음 | 채택 | fixed |
| JR-18 | opus minor m5 | sa-a 정산 픽스처가 계약상 만들 수 없는 상태(다른 판매자 항목·기간 전 P3) | 채택(기록) | fixed — 머리 주석·README |
| JR-19 | opus minor m6 | sa-lock 동시 쌍 norm_conc_pair가 orderItemId 기호화 | 기각 — 기록(→ JR-24로 문의 보강) | 리뷰·환불·패널티는 덤프가 받침, 문의는 JR-24 |
| JR-20 | opus minor m7 | README norm_set_from_mark 설명이 conc 전용 문구, 'need로 확인' 과장 | 채택 | fixed |
| JR-21 | opus minor M2 | 음성 대조가 need die(exit 2)로 끝나 차이 층 검출 실증이 아님 | 채택 | fixed — (b) exit 1·sql·pgstmt diff |
| JR-22 | opus minor(JR-11 재점검) | 새 격리 단계가 §12의 2 ERROR 로그(응답 failed 합 = 격리 실패 로그 수)를 확인 안 함, README는 iso와 같은 수준처럼 서술 | 채택 | fixed 821f68e — 1 = 1 |
| JR-23 | opus minor(JR-4 재검토) | 기각 근거가 'infra 분류 ↔ 확정 fail' 둘만 비교 — '재실행 1회 뒤 둘 다 실패해야 fail' 재시도 규칙이면 결정적 회귀는 fail로 남음, 기본 --parallel 3이 부하 조건 | 채택(기록) | NEXT: 파일럿 전 무수정 사본 parallel 3 판정으로 SlowTrigger류 실패율 측정 + 재시도 규칙 대안(ADR-0005 개정 필요 — 이번 범위 밖) |
| JR-24 | opus minor(JR-19 재검토) | JR-19 기각 근거가 리뷰엔 맞지만 문의 덤프는 order_item_id IS NOT NULL뿐이라 동시 문의 응답의 항목 id가 기호로만 걸림 | 채택 | fixed 821f68e |
| JR-25 | codex minor(신규) | sa-lock.sh:141 예상 못 한 오류 로그 n_unexp 0 미단언, 직렬 구간 없는 항목·사이즈 요청의 기대 상태 코드 미확인 | 채택 | fixed 5e26134 |
| JR-26 | opus nit(JR-3 재점검) | test_contract3_stage.py 모듈·Expected docstring이 수정 전 의미('있으면 기준 수 없음') | 채택 | fixed dc97a39 |
| JR-27 | opus minor(신규) | ADR-0006 §6.1-5의 '1·2회차 비교용 contract·contract2만으로 본 성공' 보조 필드가 판정기·ADR-0005에 없음 | 채택 | fixed dc97a39·ec02f65 — success_stage12 |
| JR-28 | codex major(post-fix — JR-27 수정분) | success_stage12가 단계 판정 경로에서만 설정 — 빌드 실패 등 조기 반환 행에 필드 없음 | 채택 | fixed f77c536 — 조기 반환 4곳, 행동 테스트 + 변이 확인 |

## 생략한 검증

- JR-28 수정분(조기 반환 4곳에 필드 기록, +20줄 테스트)은 리뷰 3루프·post-fix 재점검 1회 상한 뒤라 외부 리뷰 없이 셀프 확인(행동 테스트 + 변이 — 수정을 되돌리면 테스트 실패)으로 종료.

## 완료 요약

### 결과

| spec §1 | 충족 | 근거 |
|---|---|---|
| 1. 판정기 contract3 연결 | 충족 | STAGES·REGRESSION_TASKS·BASELINE에 contract3(168), 결과 XML 경로·verify·regression_pass(회귀 3단계 AND)·부분 판정·단계별 기준 수(`excl_touches`), 보조 성공 `success_stage12`(ADR-0006 §6.1-5). `judge-stage.sh` `task=${stage}Test`. 러너 테스트 424 → 446 OK. ADR-0005 개정 3 |
| 2. 실판정 스모크 | 충족 | `--patch none`(claude 없음): god·layered 122·89·168 pass, verify ok, success·regression_pass True(각 약 695초). 깬 사본(문의 SLA 48h → 47h): contract3 fail·실패 14·success False·regression_pass False |
| 3. equiv 시나리오 4개 + README | 충족 | sa-a·sa-b·sa-c·sa-lock, `all` 12 → 16/16 같음 exit 0. **앱 수정 0**(첫 비교부터 두 앱 차이 0), 새 정규화 규칙 0 |
| 4. 경로 통과·층 비지 않음 | 충족 | need(TRIGGERED 순서를 대상 알림 바인드로 한정, INITIAL, DEADLINE_PASSED, §19 입력 등), 배치 첫 실행 failed 0·재실행 0 0, 격리 3곳 실패 로그 = 응답 failed('1 1'). 음성 대조 (a) TRIGGERED 순서 뒤집은 jar → sa-b exit 2(need die) (b) 문의 답변에 조회 1문 → sa-c exit 1, sql·pgstmt diff |

### 바뀐 것

| 대상 | 내용 | 커밋 |
|---|---|---|
| `experiment/runner/{judge.py,templates/judge-stage.sh,tests/}` | contract3 단계 / 단계별 기준 수·약한 단언 보강 / success_stage12 / 조기 반환에도 기록 | `15c3adf` · `a7369dd` · `dc97a39` · `f77c536` |
| ADR-0005·README | 개정 3(contract3Test, 단계별 기준 수, 보조 성공), README 진행 상황 | `e53c2aa` · `b636ce5` · `ec02f65` |
| `tools/equiv/scenarios/sa-{a,b,c,lock}.sh`·README | 신규 / 배치 응답·재실행 0·격리·§19 입력·교착 상대 동시 쌍 / 격리 로그 건수·문의 항목 id / sa-lock 상태 코드·예상 못 한 오류 0 | `e9c0d7e` · `22dd2d4` · `821f68e` · `5e26134` |
| `tools/equiv/lib.sh` | 경로 확인 함수 6개 추가만(기존 함수·정규화 무변경) | `22dd2d4` |

12 files, +1249/−27.

### 핵심 diff

**① judge.py** (`git diff b889129 f77c536`)
```diff
-STAGES = ("public", "hidden", "contract", "contract2")
+STAGES = ("public", "hidden", "contract", "contract2", "contract3")
+# 회귀 단계 → behavior-tests Gradle 태스크(결과 XML 디렉터리 이름과 같다). judge-stage.sh 의 매핑과 맞춘다
+REGRESSION_TASKS = {"contract": "contractTest", "contract2": "contract2Test", "contract3": "contract3Test"}
+REGRESSION_STAGES = tuple(REGRESSION_TASKS)
```
```diff
-REGRESSION_BASELINE = {"contract": 122, "contract2": 89}
+# contract3 = 단계 A 계약(ADR-0007) 168(SlowTriggerSelfTest 포함 — ADR-0005 §4.1 개정 3)
+REGRESSION_BASELINE = {"contract": 122, "contract2": 89, "contract3": 168}
 TEST_ANNOTATION = re.compile(r"@Test\b")
 
 
+def excl_touches(excl: list[str], stage: str) -> bool:
+    """regression-exclude 패턴이 그 회귀 단계의 테스트에 닿을 수 있나. 다른 회귀 단계 패키지(com.ccle.behavior.<패키지>.)로
+    시작하는 패턴만 닿지 않는다고 본다 — 와일드카드 등 다른 모양은 보수적으로 닿는다(그 단계 기준 수 검사를 끈다)."""
+    pkg = {s: f"com.ccle.behavior.{t[:-len('Test')]}." for s, t in REGRESSION_TASKS.items()}
+    others = [v for s, v in pkg.items() if s != stage]
+    return any(not any(p.startswith(o) for o in others) for p in excl)
```
```diff
-                **({k: v for k, v in REGRESSION_BASELINE.items()} if not excl else {})}
+                **{k: v for k, v in REGRESSION_BASELINE.items() if not excl_touches(excl, k)}}
```

**② judge.py 결합**
```diff
         out["success_without_regression"] = conjunction(oc, ("public", "hidden"), out["tampered"])
-        out["regression_pass"] = conjunction(oc, ("contract", "contract2"), [])
+        out["regression_pass"] = conjunction(oc, REGRESSION_STAGES, [])
+        # 보조(ADR-0006 §6.1-5): 1·2회차와 비교용 — 회귀를 contract·contract2 로만 본 성공
+        out["success_stage12"] = conjunction(oc, ("public", "hidden", "contract", "contract2"), out["tampered"])
         out["success"] = conjunction(oc, STAGES, out["tampered"])
```

**③ lib.sh `batch_run`**
```bash
batch_run() {
  local r
  call POST "/admin/batches/$1"
  r=$(last_batch)
  [[ $r =~ $2 ]] || die "배치 $1: processed failed 상태 = '$r' (기대 /$2/)"
}
```

### 리뷰

| 회차 | 리뷰어 | 지적 | 처리 |
|---|---|---|---|
| 1 (b889129..e9c0d7e) | Opus 판정기 ∥ Opus equiv ∥ codex | 21(JR-1~21) | 반영 16 · 기록 3(JR-2·7·18) · 기각 2(JR-4·14) + JR-19 기각(→ JR-24 보강) |
| 2 (→22dd2d4) | codex 0 / Opus minor 3 | 3(JR-22~24) | 반영 2 · 기록 1(JR-23 → NEXT) |
| 3 (→821f68e) | codex minor 1 / Opus nit 1·minor 1 | 3(JR-25~27) | 반영 3 |
| post-fix (→5e26134) | codex | major 1(JR-28) | 반영 `f77c536`, 상한 뒤라 셀프 확인(행동 테스트 + 변이) |

주요 결함은 모두 도구 쪽: 배치 첫 응답 failed·재실행 0 미단언(dedupe 어긋남이 격리 실패로 숨어 행 수 단언 통과 가능 — JR-8·10), regression-exclude 하나로 contract3 기준 수 검사까지 꺼짐(JR-3), 계획한 음성 대조가 need die라 차이 층 미실증(JR-21), ADR-0006 보조 성공 누락과 조기 반환 누락(JR-27·28). 기각: JR-4(경합 미관측을 infra로 돌리면 잠금을 없앤 진짜 회귀도 무효), JR-14(contract3 동시성 테스트 + 직렬 SQL 층이 받침), JR-19(덤프가 받침, 문의는 JR-24).

### 남은 것·한계

- 실판정 스모크는 `e9c0d7e`(JR-3·27·28 이전)에서 실행 — 그 뒤 판정기 변경은 단위 테스트 446·변이로만 확인(결합 로직만 바뀜).
- JR-23: 파일럿 A 전 무수정 사본 `--parallel 3` 판정으로 SlowTrigger류 실패율 측정, 재시도 규칙은 ADR-0005 개정 사안(NEXT).
- JR-2: held 재판정이 현재 5단계로 돈다 — held 0 전제(NEXT).
- 9-2·10-2 동시 쌍의 교착 검출력은 음성 대조 미실증 — 확정 방어선은 직렬 `lock_seq`.
- T1·C2 이식 때 정답 패치의 contract3 회귀 영향 미확인(다음 작업).
- CS 이슈 아카이브: study-note 쓰기 금지(spec §4) → 보류.
