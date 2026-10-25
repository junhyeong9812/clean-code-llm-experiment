# log — 5단계 설계: 과제·판정·러너 (ADR-0005)

## 타임라인

| 시각 | 사건 | 결과 |
|---|---|---|
| 2026-10-25 ≤14:45(시각 미기록) | 사용자 질문 "결론은? 두 구조 격차가 실존해?" → README·ADR-0001 재확인: 본 질문(에이전트 토큰·정답률)은 5단계 미착수, 지금까지는 장치 구축(동작·SQL을 일부러 같게 맞춤). 메인이 그동안 상위 목적에 비춰 작업을 점검하지 않았음을 보고(500k 측정은 본 질문에 정보가 거의 없음, 1만 줄 목표 미달을 늦게 짚음) | 5단계 착수 |
| 2026-10-25 ≤14:45(시각 미기록) | 앞 실험 방법론 조사(Opus 워커, 읽기 전용): 러너 격리·호출(`claude -p --output-format stream-json --verbose --max-budget-usd 5 --permission-mode bypassPermissions`)·modelUsage 토큰·입력 감사·숨은 테스트 판정·블라인드 채점·÷B 기하평균·부트스트랩·교훈(고정 테스트 누출·토큰 과소 집계·디스크·죽은 경로 판정 결함·과제 하나 편중) | 근거 |
| 2026-10-25 14:45(date) | 인터뷰 3회(12문항) → 명세 작성. 사전 grep: 주입 후보 지점이 두 구조에 있음, layered가 SQL 동치를 위해 일부 규칙을 infra SQL에 둔 점(등급 경계·정산 대상·수수료)은 구조 차이를 줄이는 위협 | 브랜치 `docs/adr-0005-task-runner`(main `96d4f1e`) |
| 2026-10-25 15:09(date) | ADR-0005 초안 작성 → 설계 선검증(Opus, 읽기 전용) 회수: T1·T2·C2·Q1·Q2 성립, C1 수정(verify.sql 옛 규칙 하드코딩 → verify-exclude), P1 수정(주문 id 주입은 layered만 시그니처 변경 — PG 요청 키만 주입으로 1 대 1), P2 수정(호출·함수·로그·주석 함께, "영영" 사실 아님), blocker B2(지원 코드·FakePg 소스에 정답 규칙 — 새 최소 지원 코드 + jar), should-fix S1(ADR 사본 포함 시 문서 대조로 쉬워짐)·S2(주석)·S3(판정 단계마다 새 DB)·S4(결정성) | — |
| 2026-10-25 15:09(date) | 사용자 결정 2건: 작업 사본에서 문서(ADR 포함) **빼기**, 주입 지점 규칙 주석 **두 구조 모두 지우기** → ADR §3.1·§3.2·§3.3·§4·§6.2·§9 반영 | 리뷰 대기 |
| 2026-10-25 15:30(date) | ADR 듀얼 1패스 회수(codex F1~F5, Opus F1~F7·OQ7) → 종합. 누출 경계(두 리뷰 공통): bwrap 불가 확인(AppArmor `apparmor_restrict_unprivileged_userns = 1`) → 사용자 결정 **도커 컨테이너 안에서 에이전트 실행**. 나머지 채택 반영(아래 ledger) | 감사 대기 |
| 2026-10-25 15:32(date) | codex 종합 감사(`tmp.06Iy4PM1oe/results/audit.md`): 판정 오류 3(D4 미해소 2·D12 이름 fixed) + 누락 2(회귀 제외 단위·파일럿 합산 중앙값) → 수정 | 재점검 대기 |
| 2026-10-25 15:33(date) | codex post-fix 재점검(`results/recheck.md`): A1·A2·A4·A5 해소, 신규 2(R1·R2) → 수정. 리뷰 종료(open = D12 — 구현 작업 이월) | 사용자 합의 대기 |
| 2026-10-25 17:07(date) | 사용자 "합의, 계속 진행해줘" → ADR-0005 상태 채택, §3.2 과제 8개 확정. 완료 요약(Opus 워커)·NEXT·측정로그 갱신 → docs 커밋 | 병합·push 승인 대기 |

## 리뷰 ledger

(설계 선검증 — Opus, 2026-10-25: 과제별 판정은 타임라인 행. 채택 반영: T1 고칠 곳 1:1·자연 흐름 숨은 테스트, C1 verify-exclude, P1 PG 요청 키만 주입, P2 범위·"재기동 전까지", B2 새 최소 지원 코드 + FakePg jar, S1 → 사용자 결정(문서 빼기), S2 → 사용자 결정(주석 지우기), S3 단계별 새 DB, S4 결정성)

(ADR 듀얼 1패스 — loop 1, OUT=`scratchpad/tmp.06Iy4PM1oe`, mirror=`scratchpad/tmp.zkBkXhewGG`)

| id | source | 근거 | 내용 | disposition | status |
|---|---|---|---|---|---|
| D1 | codex F1 | ADR §4.1 | 회귀 실패 패치도 success | 채택 | fixed(success에 회귀 포함, 회귀 뺀 성공은 보조) |
| D2 | codex F2·opus F7 | ADR §6.1·§6.4 | 누출 경계가 OS 강제 아님(Bash 상대 경로·호스트 저장소·다른 실행 사본) | 채택 → 사용자 결정(컨테이너) | fixed(§6.1 재작성) |
| D3 | codex F3 | ADR §7 | 파일럿 게이트가 god 턴 수만 봄(선택 편향) | 채택 | fixed(두 구조 턴 중앙값, 파일럿 실행은 본 표본 밖) |
| D4 | codex F4·opus F5 | ADR §6.3·§6.4 | 예산·시간 상한 도달 처리 없음 | 채택 | fixed(censored, 포함·제외 두 비율) |
| D5 | codex F5·opus F6 | ADR §4.2 | 질문 점수에 근거 형식 혼입, 근거 위치 단일, 주석 인정 미정 | 채택 | fixed(사실·근거 점수 분리, 근거 목록, 주석 불인정) |
| D6 | opus F1 | FakePg.kt:20,155, Marketplace.kt:1067, PaymentGateway.kt:73, Marketplace.kt:1426 | 순진한 오답(P1 금액 비교 삭제·새 키, P2 상태만 변경)이 숨은 테스트 통과 | 채택 | fixed(오답 패치 실패를 채택 조건에, P1·P2 단언 강화) |
| D7 | opus F2 | Point.kt:35·288, PointBook.kt:21, PaymentGateway.kt:59, Order.kt:500 | 주석 "근처"만 지우면 layered에만 서술 잔존 | 채택 | fixed(저장소 전체 grep 삭제, 잔여물 개수 기록) |
| D8 | opus F3 | Marketplace.kt:1403·966, User.kt:62·73 | T2 "대칭"은 사실과 다름 | 채택 | fixed(비대칭 표시 — 처치 효과) |
| D9 | opus F4 | ADR §5.2 | 심볼 도달 지표가 god 전체 Read로 포화 | 채택 | fixed(겨냥 접근만, 전체 읽기 따로, 첫 편집 적중) |
| D10 | opus OQ6 | ADR §5.3 | 판정 기준 적용 대상·부트스트랩 범위·비대칭 과제 요약 포함 미정 | 채택 | fixed |
| D11 | opus OQ7·OQ5 | Order.kt:194, User.kt:82 | P2 layered 재사용 메서드·T2 상수 이름 단서 | 채택(처치 효과로 표시) | fixed(§3.2·§9) |
| D12 | opus OQ3·OQ4 | T1 시계, C1 회귀 | T1 확정 사이 시계 이동, C1 새 경계로 다른 테스트 영향 | 채택(구현 때 확인 항목) | **open(구현 작업으로 이월 — 감사 A3)** |
| A1 | 감사(codex) | ADR §5.3·§6.4 | D4 미해소: 시간 상한 종료 시 result 없음 → §6.4 무효와 충돌 | 채택 | fixed(종료 원인·부분 usage, valid 정의) |
| A2 | 감사(codex) | ADR §4.1·§5.3 | 상한 도달 일괄 실패는 근거 부족 | 채택 | fixed(패치 그대로 판정, 민감도) |
| A3 | 감사(codex) | ADR §3.2 C1 | D12 fixed는 이름 | 채택 | D12 open으로 |
| A4 | 감사(codex) | ADR §3.3·§4.1 | 회귀 제외가 클래스 단위로 넓음 | 채택 | fixed(메서드 단위·사유·갱신본 우선) |
| A5 | 감사(codex) | ADR §7 | 파일럿 합친 중앙값은 한 구조가 쉬워도 통과 | 채택 | fixed(구조별 중앙값) |
| R1 | 재점검(codex) | ADR §1·§5.3 | censored 포함·제외 중 주 분석 미정, 완료 0회 과제 비율 정의 안 됨 | 채택 | fixed(주 분석 = 포함, 0회는 "정의 안 됨"·요약 제외·개수 보고) |
| R2 | 재점검(codex) | spec §2 vs ADR §6.4 | 명세 "결과 없음 = 무효"와 ADR 시간 상한 valid 충돌 | 채택 | fixed(명세 보정) |
| DQ1 | opus OQ1·OQ2 | §6.1 | 컨테이너에서 Gradle 오프라인·budget 상한 실제 동작 | 파일럿 확인 항목(§7) | — |
| DQ2 | codex OQ | Q1·Q2 질문·체크리스트 | 아직 없음 — 구현 작업에서 작성·대조 | — | — |

## 생략한 검증

(생략한 검증 없음 — 문서 작업, 실제 에이전트 실행은 금지영역으로 구현 작업에 둠. CS 이슈 아카이브: study-note 쓰기 보류 결정에 따라 보류 — NEXT 보류·이월.)

## 완료 요약

**결과:** 5단계 실험 설계를 새 ADR `docs/adr/0005-tasks-judging-runner.md`(채택 2026-10-25)로 정했다. 내용은 조건(god·layered × Sonnet·Haiku·Opus × 과제 8 × 3회), 과제 8개와 구조별 주입 지점, 판정, 지표·통계, 도커 격리 러너, 파일럿 게이트, 타당성 위협 10개다. 문서만 바꿨고 앱·테스트·도구는 그대로다. 실제 에이전트 실행은 하지 않았다.

### 핵심 결정 (ADR 원문 인용 → 근거)

- §4.1 성공 정의: "**success = 빌드 성공 ∧ 공개 통과 ∧ 숨은 통과 ∧ 변조 없음 ∧ 회귀 통과**(아래 4 — 다른 동작을 깨뜨린 패치를 정답으로 세지 않는다)." → D1. 회귀를 뺀 성공은 보조 지표로 남겼다. 회귀 제외 단위는 A4로 메서드 단위로 좁혔다.
- §6.1 격리: "실험 저장소·oracle·다른 실행·사용자 HOME은 마운트하지 않는다." → D2. bwrap을 쓸 수 없어(AppArmor) 사용자가 도커 컨테이너를 결정했다.
- §5.3 censored: "**주 분석은 censored 포함**(모든 valid 실행 — §1 사전 기준은 이 계열에 적용), censored 제외는 민감도 분석이다." → D4, 이어서 A1·A2·R1로 보강했다.
- §5.2 지표: "파일 전체 Read(offset 없음)는 겨냥으로 세지 않고 \"전체 읽기 턴\"으로 따로 기록한다" → D9(god 전체 Read 1회로 지표가 포화됨). 첫 편집 적중 지표를 추가했다.
- §3.2 P1: "**PG 요청 키만** `cancel-<항목 id>` → `cancel-<주문 멱등 키>`" → 설계 선검증에서 정했다. 순진한 오답이 통과하지 않게 막은 것은 D6이다. T2·P2를 비대칭으로 표시한 것은 D8·D11이다.

### 검증 경과

- 설계 선검증(Opus): 과제 5개는 그대로 성립했고 C1·P1·P2 3개를 고쳤다. 지적은 blocker 1(B2), should-fix 4(S1~S4)이고 S1·S2는 사용자가 결정했다.
- 듀얼 1패스(codex F1~F5 ∥ Opus F1~F7·OQ): D1~D12를 채택했고, 그중 D1~D11은 fixed다. DQ 2건이 따로 있다.
- codex 감사: 판정 오류 3(A1~A3)과 누락 2(A4·A5)를 찾았다. A3에 따라 D12는 open으로 되돌렸다.
- codex 재점검: A1·A2·A4·A5가 해소됐다. 새로 나온 R1·R2를 고쳤고, R2는 명세 §2 보정이다. 이것으로 리뷰를 마쳤다.

### 남은 것 (구현 작업으로 이월 — NEXT N0)

- **D12(open):** T1 확정 사이의 시계 이동, C1 새 등급 경계가 다른 계약 테스트에 주는 영향.
- **DQ1:** 컨테이너 안 Gradle 오프라인 빌드, `--max-budget-usd`가 실제로 걸리는지 — 파일럿(§7)에서 확인.
- **DQ2:** Q1·Q2의 질문과 체크리스트 작성.

### 범위

커밋 전 `git status --short`는 `docs/adr/0005-tasks-judging-runner.md`와 `docs/plans/2026-10-25/task-runner-design/` 두 개뿐이었다. 그 뒤 NEXT.md와 measurement-log.md를 갱신해 변경은 모두 docs/ 아래다.
