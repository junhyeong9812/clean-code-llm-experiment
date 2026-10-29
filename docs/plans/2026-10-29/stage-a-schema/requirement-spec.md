# 요구사항 명세서 — 단계 A 스키마·verify·시드 후처리 (ADR-0007 §3·§14·§15)

> 작성일: 2026-10-29 · 작업 폴더: `docs/plans/2026-10-29/stage-a-schema/` · 브랜치 `data/stage-a-schema`(main `7e10896`)
> 산출물 유형: **실행물**(DDL·시드 생성기·verify.sql·적재/이미지 스크립트 설정·태그 설정). ADR-0006 §6.1의 3.

## 0. 요구사항 원문 (인터뷰 기록)

- 원문: "보강하고 병합 후 push한 뒤 계속 진행해줘" → NEXT 다음 착수 = 스키마·verify·seedgen 후처리.
- Q/A:
  - 범위 → **DDL·verify·시드 후처리·새 이미지까지 한 번에**.
  - 태그 전환 → **다섯 곳 모두**(runner.py `DB_IMAGE`·`tools/behavior/run.sh`·`tools/equiv/lib.sh`·`tools/equiv/README.md`·`README.md`) `1k-s42-v2`로. 기존 `1k-s42` 이미지는 남긴다.
  - 자율성 → **auto**.

## 1. 목표·대상 (필수)

다음이 모두 되면 끝:
1. `docker/schema/tables.sql`·`constraints.sql` 끝에 ADR-0007 §3의 새 테이블 19개(CHECK·UNIQUE·부분 UNIQUE 4·FK 27·인덱스)가 있다.
2. `tools/seedgen/seedgen.py`에 후처리 패스(별도 RNG `seed ^ 0x5CA1E`, 닫힌 22개 CSV 재독)가 있어 ADR §14 규칙대로 새 테이블 CSV를 만들고 manifest에 넣는다. 기존 22개 CSV는 바이트 그대로.
3. `tools/seedgen/verify.sql`: FK 수 34 → 61, ADR §15의 새 검사(테스트 뒤에도 성립하는 것 + `seed_build` 변수로 켜는 시드 빌드 전용), 디스크 여유 상수 갱신.
4. `1k-s42-v2` 이미지가 빌드되고(verify 통과), 다섯 곳 태그가 바뀐다.
5. 회귀: v2에서 기존 행동 테스트 239(infra 28·contract 122·contract2 89)·equiv 12 시나리오가 그대로 통과, contract3의 '테이블 없음' 사유가 사라진다(다른 red 사유로 바뀜).

## 2. 경계·불변식 (필수)

- **기존 22개 테이블 DDL·CHECK·FK·인덱스 무변경**(새 정의는 파일 끝에 덧붙임). 기존 verify 검사 식은 FK 수 34만 바꾼다(ADR-0006 §3의 2, ADR-0007 §15).
- **시드 결정성**: 같은 seed로 두 번 생성하면 새 CSV도 바이트 동일. 기존 22개 CSV의 sha256 = 현 manifest 값(`~/ccle-csv/1k/manifest.json`).
- 새 테이블 시드 행은 **NOW 시점에 계약대로 돌았다면 남았을 행**(ADR §14) — verify 새 검사 전부 통과.
- 앱(god·layered)·행동 테스트·`experiment/` 과제 파일 무변경(runner.py는 `DB_IMAGE` 한 줄만).
- `1k-s42`·`500k-s42` 이미지와 `~/ccle-csv/1k`·`500k` 원본 CSV를 지우거나 덮어쓰지 않는다(새 CSV는 새 디렉터리 `~/ccle-csv/1k-v2`).

## 3. 기준소스 (필수)

ADR-0007(§3·§14·§15·§19 개정 1) · ADR-0006 §3의 2·4 · 현행 `docker/schema/*.sql`·`tools/seedgen/{seedgen.py,verify.sql,load.sh,build-image.sh}` · `~/ccle-csv/1k/manifest.json`(기존 sha 기준값) · contract3 `support/Db3.kt`(열 이름을 쓰는 쪽).

## 4. 금지영역 (필수)

`god/`·`layered/`·`behavior-tests/`·`experiment/`(runner.py `DB_IMAGE` 한 줄 제외) 변경 금지 · 기존 이미지·CSV 삭제·덮어쓰기 금지 · 500k 재빌드 금지(ADR-0006 §3의 4) · 에이전트(claude) 실험 실행 금지 · study-note 쓰기 금지 · push는 사용자 확인 후.

## 5. 검증 방법 (필수)

- **분리 작성(blind):** DDL은 메인이 ADR §3에서 먼저 쓴다. 그 뒤 verify 새 검사(워커 V — ADR §15·§14 규칙만 보고, seedgen 후처리 코드 미열람)와 seedgen 후처리(워커 G — ADR §14만 보고, verify 새 검사 미열람)를 서로 다른 워커가 병렬로 쓴다. 생성 결과가 독립 작성된 검사를 통과해야 한다.
- **record-level:** 새 테이블별 행 수·표본·고아(FK는 제약이 막음)·ADR §14 예상 행 수 대비 기록. 기존 22개 sha 대조. 같은 seed 2회 생성 sha 동일.
- **verify 검출력:** 새 검사마다 위반 행 1개를 넣은 임시 DB에서 그 검사가 위반을 세는지(변이 1회씩).
- **회귀:** v2에서 두 앱 `test`·`contract2Test`·`contract3Test`(사유 분류)·equiv 12 시나리오.
- **리뷰:** 높음 — 듀얼 리뷰 루프(Opus ∥ codex, ≤3).

## 6. stakes (필수)

- 판정: **높음** — DB 스키마·시드 데이터(public 계약의 저장 표면, 이후 모든 실행의 기반). 기존 데이터 바이트 동일성과 verify 통과가 실험 재현성을 좌우.

## 7. 자율성

- [x] auto (사용자 선택 2026-10-29)

## 8. load-bearing 가정

1. 후처리가 닫힌 CSV만 읽어 ADR §14의 모든 파생 규칙(특히 seller_grades의 restricted·fee_rebates·monthly_statements·tax_invoices·price_stats_daily)을 계산할 수 있다 — 착수 직후 1k CSV로 행 수 스모크.
2. `load.sh`·`build-image.sh`가 manifest의 새 키만으로 새 테이블을 적재한다(packet c7-01b) — 스모크로 확인.

## 9. task 분해

| task | 목표 | 의존 | acceptance |
|------|------|------|-----------|
| 01 | DDL(19 테이블) + 개발 DB 적재 스모크 + contract3 열 이름 대조 | — | 빈 테이블로 기존 1k 적재·기존 verify 76 통과(FK 수 갱신 전 61 확인) |
| 02 | verify 새 검사(워커 V) ∥ seedgen 후처리(워커 G) | 01 | 각자 packet |
| 03 | `~/ccle-csv/1k-v2` 생성(2회 sha 대조) → `1k-s42-v2` 빌드 → verify 통과 → 변이 검출력 | 02 | §5 |
| 04 | 태그 5곳 전환 + 회귀(두 앱 3태스크 + equiv 12) | 03 | §1의 5 |
| 05 | 듀얼 리뷰 루프 → 커밋·병합(push 확인) | 04 | 종료 조건 |
