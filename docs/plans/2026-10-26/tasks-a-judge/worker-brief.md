# 과제 작성 워커 브리핑 (T1·C2 공통)

## 목표

ADR-0005(개정 1) §3.2의 과제 하나를 **실행 가능한 형태로** 작성하고, 채택 조건을 `check` 명령으로 실측한다. 과제는 LLM 에이전트가 god·layered 두 구조에서 같은 문제를 푸는 실험 재료다.

## 배경 — 반드시 읽을 것(실파일)

- `docs/adr/0005-tasks-judging-runner.md` §3(원칙·과제 표·산출물), §4.1(판정), §5.2(symbols)
- `docs/plans/2026-10-26/tasks-a-judge/requirement-spec.md` §2(경계·불변식 — 채택 조건·누출 금지)
- 동작 계약: `docs/adr/0003-http-api-stage1.md`, `docs/adr/0004-http-api-stage2.md`, `docs/adr/0002-domain-and-database.md`(관련 절)
- 두 구조 코드: `god/src/main/kotlin/com/ccle/god/`(주로 `Marketplace.kt`), `layered/src/main/kotlin/com/ccle/layered/`
- 기존 계약 테스트와 지원 코드: `behavior-tests/src/test/kotlin/com/ccle/behavior/`(흐름·픽스처 작성법 참고)
- 러너·판정: `experiment/runner/runner.py`(`build_work`, `cmd_check`), `experiment/runner/judge.py`, `experiment/runner/templates/`(공개 테스트 모듈 `checks/`, 지원 코드 `checks/src/test/kotlin/support/Support.kt`, `test.sh`, `judge-stage.sh`)
- 예시 과제: `experiment/cases/_probe/`

## 파악한 것(메인)

- **작업 사본 구성**(`build_work`): `app/`(그 구조의 src) + 루트 gradle + `checks/`(템플릿) + `test.sh`(템플릿) + 과제 공개 파일(`experiment/cases/<ID>/`의 TYPE 제외 전부, 경로 그대로) + `inject.<구조>.patch`(있으면 `patch -p1`로 적용).
- **경로 기준:** 패치 경로는 작업 사본 루트 기준(`a/app/src/main/kotlin/...`)이다. `fix`·`naive-N` 패치는 inject를 적용한 사본 위에 적용된다.
- **공개 테스트**는 `experiment/cases/<ID>/checks/src/test/kotlin/pub/<ID>Test.kt`(package `pub`)에 둔다.
  - 지원 코드는 `support.*`(Api·Clock·Batches·Db·Env)만 쓴다.
  - 지원 코드 템플릿은 바꾸지 마라. 필요하면 공개 테스트 파일 안에 private 도우미를 둔다. 규칙 상수·기대 금액 공식을 도우미에 넣지 말고 테스트 본문에 증상으로 적는다.
- **숨은 테스트**는 `experiment/oracle/<ID>/hidden/<ID>HiddenTest.kt`(package `hidden`)에 둔다. 판정 때 `checks/src/test/kotlin/hidden/`으로 복사된다. 필요한 도우미는 hidden 패키지 안에 자유롭게 둔다(behavior-tests 지원 코드를 참고·복사해도 된다 — 에이전트는 못 본다).
- **test.sh**는 실행마다 DB를 1k 시드 템플릿에서 다시 만들고 앱을 새로 띄운다(시계 = 앱 기본 시작 시각). 판정은 단계마다 새 DB·앱이다. 한 단계 안의 테스트끼리는 DB·시계를 공유한다. 시계는 앞으로만 움직인다. 테스트 사이 간섭이 없게 써라(서로 다른 사용자, 시계는 상대 이동).
- **채택 조건 실측 명령**(저장소 루트에서, docker 사용 — 한 번에 1개씩, 판정 1회에 약 5~9분):
  - `python3 experiment/runner/runner.py check --case <ID> --structure <god|layered> --patch none --stages public,hidden` → 주입·기준 상태: 숨은 테스트 **실패**(공개도 실패가 정상)
  - `... --patch fix` (전 단계) → **success=True**(공개·숨은·회귀·verify 전부)
  - `... --patch naive-N --stages hidden` → 숨은 테스트 **실패**(오답마다)
  - 결과 행은 `experiment/results/checks/checks.jsonl`. 판정 단계의 실패 테스트·로그 꼬리가 행에 있다.

## 기대 형식(산출물)

`experiment/cases/<ID>/`:
- `TYPE`(bug|change)
- `TASK.md`(증상·기대 동작·재현 방법만 — 원인 위치(파일·클래스·함수·테이블 열 이름의 특정)는 쓰지 않는다. 한국어)
- `checks/src/test/kotlin/pub/<ID>Test.kt`(공개 — 증상 재현 1~3개)

`experiment/oracle/<ID>/`:
- `design.md` — 의도, 구조별 원인 위치(file:line), 정답, 대안 정답이 통과해야 하는 이유, 순진한 오답 목록(naive-N과 대응), 제외한 유사 지점과 사유, 지운 주석 목록과 남는 올바른 구현 잔여물의 구조별 개수, 두 구조 비대칭 메모
- `inject.god.patch`·`inject.layered.patch`(bug만)
- `fix.god.patch`·`fix.layered.patch`
- `naive-1.<구조>.patch` … (구조별)
- `hidden/<ID>HiddenTest.kt`
- `symbols.god.txt`·`symbols.layered.txt` — 줄 형식 `경로:시작-끝 fix|read  # 메모`. 경로는 작업 사본 기준(`app/src/main/kotlin/...`), 줄 번호는 **inject 적용 후** 사본 기준이다.
- `regression-exclude.txt`(정답이 의도적으로 깨는 기존 계약 테스트 — 메서드 단위 `fqcn.method  # 사유`, 없으면 빈 파일), `verify-exclude.txt`(없으면 빈 파일)

## 하지 말 것

- `god/`·`layered/`·`behavior-tests/`·`tools/`·`docker/`·`docs/adr/`·`experiment/runner/` 수정 금지. 러너·판정 결함을 찾으면 고치지 말고 보고하라.
- 다른 과제 디렉터리를 건드리지 마라. git 커밋 금지.
- claude(에이전트) 실행 금지 — `runner.py run` 금지. `check`·`cleanup`만.
- 숨은 테스트가 구현 방식(SQL 모양·키 문자열·클래스 이름)을 단언하지 마라. HTTP 응답·DB 결과만 단언한다.

## 보고(packet)

1. 산출물 파일 목록.
2. 채택 조건 실측 결과 표 — 명령, success, 단계 outcome, 실패 테스트 이름. 구조 × {none, fix, naive-N}.
3. 두 구조 비대칭과 "이 과제가 어느 구조에 유리한가"의 판단과 근거(file:line).
4. 미해결 질문.
5. 실제 읽은 파일, 실행한 명령과 결과 요약, 미완료 항목.
