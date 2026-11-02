# 작업 로그 — ADR-0009 탐색 위임 측정

| 시각 | 사건 | 결과 |
|---|---|---|
| 2026-11-03 00:29 | 인터뷰(2026-11-02 2묶음 + 11-03 1묶음, NEXT N-DEL 중간 기록) + 사용자 부하 제한 요청 → spec 작성 → 합의 "그대로 진행", auto. 브랜치 delegation-measure(search-cost-tasks 0628fff). free 가용 28G. 순서 조정: load-bearing 가정 1(헤드리스 CLI가 .claude/agents/explorer.md model로 서브에이전트 기동·modelUsage 분리)을 ADR 전에 스모크로 실증(core §4) | 러너 조사 |
| 2026-11-03 00:30 | 러너 위임 방식 구현(커밋 948e347): ARMS·DELEGATE_RULE·EXPLORER_MD(격리 HOME .claude/agents — 작업 사본 불변), run_id·row.arm, done_keys/held_keys 방식별 필터(기본 direct = 종전 키), --arm. 단위 테스트 +6 → 471 OK. 가정 1 스모크 착수: S1 layered, opus, explore-haiku, parallel 1, results-del-smoke | 스모크 |
| 2026-11-03 00:34 | 스모크 1차: 로그인 파일 남은 47분 < 필요 55분(timeout 2700+600)으로 creds 대기 → SIGTERM(interrupted 행, 스모크 폴더 삭제) → --timeout 1800으로 재시도 | 스모크 |
| 2026-11-03 00:52 | **가정 1 성립**(스모크 S1 layered opus explore-haiku, 197s, success): explorer 1회 호출, sub 11 Bash 전부 claude-haiku-5-5, modelUsage 모델별 costUSD 합 = total_cost_usd 0.22717(Opus 0.21704 + Haiku 0.01013), runs.jsonl subagent_calls 1·lines_read.sub 1015. 결정: 실험 --timeout 1800. ADR-0009 초안에 반영 | ADR 리뷰 |
| 2026-11-03 01:11 | ADR 초안 듀얼 1패스 + 과제 후보 조사 DEL-W1(Opus, 기준 ddd3d56): 미러 /tmp/tmp.B2jcb3XgcT(log·NEXT·측정로그 제외, 스모크 원자료 _packet/smoke). codex medium 2회 bwrap RTM_NEWADDR → 인라인 입력(spec·ADR 전문·runner diff·스모크 요약)으로 3회째 성공: 높음 3·중간 6. Opus: 높음 3·중간 10·낮음 3(스모크 수치 전부 일치, 판정 13.5분 누락으로 시간 추정 3~5배 과소). DEL-W1: D1-1(제한 끝 시각)·D2-1(포인트 차감 순서)·D3-2(발송 기한 어긋남) 권장, 계약 제외 6/0/0 | 사용자 결정 |
| 2026-11-03 01:11 | 사용자 결정 3: 본 실행 판정 public·hidden만 · 호스트 로그인 유지 루프 · 두 축(모델 효과 ÷explore-opus 주, 위임 효과 explore-opus÷solo 보조) + 위임 차단 기준(solo) | 반영 |
| 2026-11-03 01:11 | 러너 2차(커밋 af01559): solo 방식(settings deny Agent·Task), events.sub_info(subagent_types·sub_edit_paths), cost_by_model, run --stages·--min-mem-gb(wait_mem·MemAvailable), keepalive.py(만료 문턱 아래면 creds 잠금 안에서 host claude -p ok), del_schedule.py(칸 셔플 시드 20261103·방식 회전·--passes 3). 테스트 483 OK(+12: run_one 방식별 argv·HOME·run_id·arm·판정 단계, deny, sub_info, cost_by_model, wait_mem, 스케줄 168·균형·결정성, keepalive). 호스트 갱신 시점 측정: 13분 남음에서 claude -p 호출로 갱신 안 됨 → 1분 간격 프로브 진행 중 | ADR 재작성 |
| 2026-11-03 01:11 | ADR-0009 재작성(리뷰 반영 + 사용자 결정 + D1~D3 구체화) | 재점검 |

## 리뷰 ledger

| ID | 출처 | 지적 | 판정 | 처리 |
|---|---|---|---|---|
| DM-1 | opus F1 | direct에도 Task·내장 Explore 있음, subagent_calls가 종류 무시 | 채택 | solo(deny Agent·Task) + subagent_types 기록, 게이트 2 explorer만 |
| DM-2 | opus F2 | 기준이 하네스 현행(explore-opus)과 어긋남 | 채택(사용자) | 두 축 — 모델 효과 ÷explore-opus 주 |
| DM-3 | opus F3·codex 02 | 비열등 1/42가 좁고 비대칭(같은 p에도 22~34% 열등), 칸 상쇄 | 채택 | 성공률 차 구간(마진 −0.10) + 칸 바닥(막힘) |
| DM-4 | opus F4·codex 01 | 구간 조건 행 1만, R_D 구간·부트스트랩 절차 없음 | 채택 | 모든 행 대칭 구간, 독립 복원 추출·2,000·시드·백분위 |
| DM-5 | opus F5·codex 09 | 시간 추정에 판정 13.5분 누락(47h) | 채택(사용자) | 본 실행 public·hidden, 재추정 |
| DM-6 | opus F6 | 무인 실행 로그인 갱신 경로 없음 | 채택(사용자) | keepalive.py — 문턱은 실측 |
| DM-7 | opus F7 | spec(작업 사본 주입)과 구현(격리 HOME) 다름 | 채택 | spec 정정 기록(아래) |
| DM-8 | opus F8 | 모델별 비용 미기록 | 채택 | cost_by_model |
| DM-9 | opus F9·codex 07 | 방식별 argv·direct 회귀 테스트 없음 | 채택 | RunOneArms 3 |
| DM-10 | opus F10·codex 04 | 탐색량 문턱 단위 불일치 | 채택 | 파일럿 solo S1~S4 같은 정의 기준 |
| DM-11 | opus F11 | 방식 블록 순서 캐시 혼입 | 채택 | 칸 단위 방식 회전 스케줄 + 캐시 쓰기 공변량 |
| DM-12 | opus F12·codex 08 | explorer 쓰기 구분 없음 | 채택 | sub_edit_paths + 서술 정정 |
| DM-13 | opus F13·codex 05 | 결측·재실행 상한 없음 | 채택 | §7.4 |
| DM-14 | opus F14 | 부하 제한이 절차뿐 | 채택 | --min-mem-gb·스케줄 고정 인자 |
| DM-15 | opus F15·F16 | Grep·Glob 없음, main 187에 요약 48 | 채택 | 서술 정정 |
| DM-16 | codex 03 | 접미·정의 혼입 | 채택 | 주 질문은 explore끼리라 해당 없음, 보조는 방식 전체 효과로 한정 |
| DM-17 | codex 06 | explore-opus 채택 규칙 | 채택 | 모델 효과의 기준, 위임 효과 별도 표 |
| 2026-11-03 01:15 | post-fix 재점검(codex medium, 인라인 — 처음부터 인라인): 해소 7·부분 10, 새 결함 N-1~N-11(높음 6). 반영: N-1 비열등 Newcombe 점수 구간(메인 재계산 42/42 [−0.084,0.084]·40/42 [−0.158,0.043]·18/18 [−0.176,0.176]), 42실행 전체로만 / N-2 공통 유효 칸(≥2회), R ≥12/14·R_D ≥5/6 아니면 판정 불가 / N-3 둘째 행 R_D 작은 쪽 / N-4 비용 손해·성공 손해 행 분리 / N-5 스케줄 rc 전파·미완료 키 / N-6 --cap-usd(누적 비용 중지) / N-7 러너 cost_check / N-8 keepalive --below-min 필수·갱신 여부 반환 / N-9 sub_edit_paths = 쓰기 시도 추정 / N-10 wait_mem 중단 시 None·시작 안 함 / N-11 게이트 8(판정 갈림 ≤1) + 게이트 7(solo Agent 호출 0, DM-1 실증). 테스트 484 OK | 로그인 문턱 대기 |
| 2026-11-03 01:17 | 호스트 로그인 갱신 실측: 13.0~5.2분 남음에서 호출해도 갱신 없음, 4.2분 남음에서 갱신 → 새 유효 479.9분(약 8h). keepalive 문턱 5분 확정(ADR §8·§9). 8시간마다 러너 대기 약 35분 | 채택 질문 |
