# 작업 로그 — ADR-0009 탐색 위임 측정

| 시각 | 사건 | 결과 |
|---|---|---|
| 2026-11-03 00:29 | 인터뷰(2026-11-02 2묶음 + 11-03 1묶음, NEXT N-DEL 중간 기록) + 사용자 부하 제한 요청 → spec 작성 → 합의 "그대로 진행", auto. 브랜치 delegation-measure(search-cost-tasks 0628fff). free 가용 28G. 순서 조정: load-bearing 가정 1(헤드리스 CLI가 .claude/agents/explorer.md model로 서브에이전트 기동·modelUsage 분리)을 ADR 전에 스모크로 실증(core §4) | 러너 조사 |
| 2026-11-03 00:30 | 러너 위임 방식 구현(커밋 948e347): ARMS·DELEGATE_RULE·EXPLORER_MD(격리 HOME .claude/agents — 작업 사본 불변), run_id·row.arm, done_keys/held_keys 방식별 필터(기본 direct = 종전 키), --arm. 단위 테스트 +6 → 471 OK. 가정 1 스모크 착수: S1 layered, opus, explore-haiku, parallel 1, results-del-smoke | 스모크 |
| 2026-11-03 00:34 | 스모크 1차: 로그인 파일 남은 47분 < 필요 55분(timeout 2700+600)으로 creds 대기 → SIGTERM(interrupted 행, 스모크 폴더 삭제) → --timeout 1800으로 재시도 | 스모크 |
| 2026-11-03 00:52 | **가정 1 성립**(스모크 S1 layered opus explore-haiku, 197s, success): explorer 1회 호출, sub 11 Bash 전부 claude-haiku-5-5, modelUsage 모델별 costUSD 합 = total_cost_usd 0.22717(Opus 0.21704 + Haiku 0.01013), runs.jsonl subagent_calls 1·lines_read.sub 1015. 결정: 실험 --timeout 1800. ADR-0009 초안에 반영 | ADR 리뷰 |
