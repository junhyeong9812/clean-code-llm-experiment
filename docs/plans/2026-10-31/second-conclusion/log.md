# log — 2차 결론 작성

> 명세: `requirement-spec.md` · 브랜치 `docs/second-conclusion`(main `2ad189e`)

## 타임라인

| 시각 | 사건 | 결과 |
|---|---|---|
| 2026-10-31 00:14 | 파일럿 A 병합·push(`2ad189e`) 뒤 사용자 선택 "2차 결론 문서화" → 1차 결론 작업의 형식·절차를 그대로 따르는 spec 작성(L0 보고 문서). 초안 위임: sc-draft(Opus — 원자료 재추출, 1차 결론 형식) | 초안 대기 |
| 2026-10-31 00:27 | sc-draft 회수: 근거 문서 작성, README는 워커 세션에서 gate-guard 차단(새 작업 폴더 생성으로 SPEC=0 — 우회 없이 제안 patch만) → 사용자 spec 합의(auto) → set-state → 메인이 patch 적용 | 대조 |
| 2026-10-31 00:27 | 메인 수치 대조(원자료 재계산): 12실행 턴·도구 호출·총 토큰·캐시 읽기 비율(`by_model.cacheReadInputTokens`÷`total_tokens`)·비용 전부 문서 표와 일치, 중앙값 6/5.5·5.5/5.5·6.5/6.0, 대기 턴 제외 5.5/5.0·오기 제외 5.0/5.0, 'moved to the background' 3/4·0/4·4/4, 시드 이미지 `1k-s42`·`1k-s42-v2` 존재 | 리뷰 |
| 2026-10-31 00:27 | 듀얼 1패스 착수: packet OUT=/tmp/tmp.GOPvlJNcnv · 미러 /tmp/tmp.df6uqdz21D(base `2ad189e`, ledger·NEXT·측정 로그 제외, gitignore 원자료 runs.jsonl·raw를 `_packet/rawdata/`에 원본 그대로 추가) · 보안 스캔: 걸린 것은 runner 테스트의 가짜 토큰 값(공개 저장소 tracked)뿐 · Opus ∥ codex high | 대기 |
| 2026-10-31 00:44 | 듀얼 1패스 회수: codex(high) major 1·minor 2 / Opus major 2·minor 4(12행 수치 전부 일치 확인). 메인 확인: 2회차 T1 god tools `bash` 1(runs.jsonl), C2 layered cat 5파일(raw). 종합: 겹침 2·고유 5 → 7건 채택·반영(SC-1~7, README·근거 문서·ADR-0006 같은 문장 포함) + NEXT 갱신 | 재점검 |
| 2026-10-31 00:44 | post-fix 재점검(codex high — 수정 diff + 갱신 미러 /tmp/tmp.4HfqBhcMOZ): 1차 bwrap `RTM_NEWADDR`로 파일 못 읽음(부분 판정) → 규칙대로 1회 재시도 → 7건 해소·신규 0, 보정 중앙값 원자료 재계산 일치. 미러 2개 삭제 | 커밋 |
| 2026-10-31 00:44 | 사이클 마감: NEXT 갱신 · CS 이슈 아카이브는 study-note 쓰기 금지(spec ④)로 보류 | 병합 확인 |

## 리뷰 ledger

| ID | 출처 | 지적 | 판정 | 처리 |
|---|---|---|---|---|
| SC-1 | codex·opus | 턴 증가를 대기 턴(과 오기 턴) 탓으로 단정 — 2회차에도 오기 1회, C2 layered 6→6 | 채택 | 관찰값(보정 중앙값 양 회차 대칭)으로, 원인 분리 안 함 — README·근거 §3.3·ADR-0006 |
| SC-2 | codex·opus | '턴이 늘지 않았다'가 실측(+1.0·+0.5)과 반대, 단계 B 위험 칸이 사후 관찰을 근거로 씀 | 채택 | '탐색 호출 수'로 한정, 위험 칸 문장 삭제 |
| SC-3 | codex | README 범위에 layered 9,970 누락(spec §2) | 채택 | 추가 |
| SC-4 | opus | 1회차가 다른 과제 정의(개정 1)라는 단서 없음 | 채택 | 표 주석·§3.3 |
| SC-5 | opus | 463 출처를 README 진행 표로 — 표는 117·82 | 채택 | ADR-0005 §4.1의 4 + 시점 단서 |
| SC-6 | opus | C2 layered 통째 cat 4 → 실제 5(ItemLocker.kt) | 채택 | 근거 문서·ADR-0006 |
| SC-7 | opus | 범위에 ADR-0005 §9 위협 12·13 누락 | 채택 | 범위 1줄 |

## 생략한 검증

(없음)

## 완료 요약

**결론:** README `## 2차 결론 (2026-10-30)` 절과 근거 문서 `docs/results/second-conclusion.md`를 썼다. 결론 문장은 ADR-0006 §6.3 넷째 행 사전 등록 문장에 실측값(2,692 → 5,678, 2.11배)만 넣은 것이고, 구조 효과는 주장하지 않으며, 사후 관찰은 따로 표시했다. 다음 결정 후보 4개는 권장 없이 나열했다.

README 새 절 머리(실파일에서 복사):
```markdown
## 2차 결론 (2026-10-30)

> **god 코드를 2,692줄에서 5,678줄로 2.11배 키워도 Sonnet의 턴이 사전 등록한 증가 기준(구조별 중앙값 7.5)에 못 미쳐, 이 방식의 규모 확장으로는 구조 효과를 볼 만큼 과제를 어렵게 만들기 어렵다.**
```

- 변경: README(새 절 + 진행 표 6단계), `docs/results/second-conclusion.md`(신규 — 12실행 표·게이트·사후 관찰·후보·한계), ADR-0006 파일럿 A 해석 문장 정정(SC-1·SC-6), NEXT.
- 검증: 메인 원자료 재계산(12행 전부 일치) · 듀얼 1패스(codex major 1·minor 2 / Opus major 2·minor 4) → 채택 7 → post-fix codex 재점검 7건 해소·신규 0(1차 bwrap 실패 → 재시도).
- 남긴 것: 다음 결정은 사용자(① 종료 ② 과제 난이도 수단 ③ 다른 모델 ④ 단계 B — ②~④는 결과를 본 뒤의 선택).
