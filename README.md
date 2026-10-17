# clean-code-llm-experiment

사람에게 좋은 설계(캡슐화·지엽성·레이어·클린 코드)는 LLM 코딩 에이전트에게도 좋은가를 재는 실험장이다.

## 배경

[graph-nav-experiment](https://github.com/junhyeong9812/graph-nav-experiment)에서 코드 지도는 에이전트의 토큰을 줄이지 못했다. 탐색 비용과 정답률을 정한 것은 코드 자신의 **아키텍처(지엽성)와 네이밍**이었다. 이는 SOLID·클린 코드가 말해 온 것과 생각보다 많이 겹친다.

그런데 그 실험의 모든 변형은 같은 레이어 구조였다. 이 저장소는 구조 자체를 바꿔 본다.

## 질문과 가설

- **질문:** 사람이 읽기 좋은 구조(레이어·캡슐화·도메인 모델)는 LLM 에이전트의 탐색 비용과 정답률에도 도움이 되는가?
- **가설:** LLM은 사람이 쓴 코드와 사람의 분석 기준으로 학습했다. 그러니 사람에게 좋은 추상화가 LLM에게도 유리할 것이다.

## 설계

- **도메인:** 결제·입찰·구매·정산 + 포인트(결제 금액 기반 적립, 결제 시 사용). "결제"라는 하나의 행위의 절차를 정의하고 그 절차를 푸는 코드를 쓴다.
- **두 구조, 같은 동작 (Kotlin · Spring Boot)**
  - `god/`: 갓 클래스. 절차의 시작과 끝을 클래스 1개, 파일 1개에 담는다.
  - `layered/`: `api · service · domain · infra` 계층.
  - 갓 클래스(절차지향)를 먼저 만들고, 그것을 레이어드로 분리하며 도메인을 끌어낸다. 두 모듈은 같은 HTTP 행위 테스트를 통과해야 한다.
- **DB:** 실제 PostgreSQL(같은 PC의 도커). 합성 데이터(사용자 50만, 거래 약 500만)를 CSV로 만들어 적재하고, 데이터 상태까지 검증한다.

결정 기록: [ADR-0001 기본 세팅](docs/adr/0001-base-setup.md) · [ADR-0002 도메인 절차·규칙과 DB 설계](docs/adr/0002-domain-and-database.md) · [ADR-0003 HTTP API 계약 1단계](docs/adr/0003-http-api-stage1.md)

## 실행

```bash
docker compose -f docker/compose.yml up -d     # PostgreSQL 17, 127.0.0.1:15432
./gradlew build                                 # JDK 21, 두 모듈 + 스모크 테스트(DB 접속)
java -jar god/build/libs/god-0.0.1.jar          # :8081
java -jar layered/build/libs/layered-0.0.1.jar  # :8082
curl localhost:8081/actuator/health
```

- 스키마(`docker/schema/`)는 빈 볼륨에서만 init으로 걸린다. 스키마가 바뀌면 `docker compose -f docker/compose.yml down -v` 뒤 다시 올린다.

### 행위 테스트

```bash
tools/behavior/run.sh god        # 1천 명 시드 DB → 가짜 PG → 앱 → infraTest·contractTest → verify.sql → 정리
tools/behavior/run.sh layered
```

- 같은 테스트(`behavior-tests/`)가 두 앱에 돈다. 계약은 ADR-0003, 금액 기대값은 그 §8 표.
- 루트 `./gradlew build`는 행위 테스트를 건너뛴다. 직접 돌릴 때는 `BEHAVIOR_TESTS=1`과 `BASE_URL`·`DB_URL`이 필요하다(run.sh가 넘긴다).

### 합성 데이터와 시드 이미지

```bash
python3 tools/seedgen/seedgen.py --users 500000 --out ~/ccle-csv/500k   # CSV + manifest.json(행 수·sha256), 시드 고정
tools/seedgen/build-image.sh ~/ccle-csv/500k 500k-s42                    # 적재 → 제약 → VACUUM FREEZE → verify.sql → 이미지
tools/seedgen/run-db.sh 500k-s42 ccle-run-001 15433                      # 실행 1회용 DB, DB_URL 출력
```

- 시드 이미지는 태그(규모)마다 하나씩 둔다(테스트용 `1k-s42`, 과제용 `500k-s42`). 이미지 안에는 DB `ccle_seed` 하나만 있다. 실행마다 컨테이너를 따로 띄우고, 앱은 `DB_URL`로 그 컨테이너에 붙는다.
- `verify.sql`은 ADR-0002의 불변식(§6.1·§9)을 검사하고, 위반이 있으면 실패한다.
- 실행 컨테이너는 처음 건드린 테이블 파일을 쓰기층에 통째로 복사한다(최대 약 14GB). 실행이 끝나면 `docker rm -f <이름>`으로 바로 지운다. 규모 실측은 [ADR-0002 §7](docs/adr/0002-domain-and-database.md).

## 진행

| 단계 | 상태 |
|---|---|
| 1. 기본 세팅(두 서버 + 도커 PostgreSQL) | 완료 — ADR-0001 |
| 2. 도메인·절차 정의와 DB 설계 | 완료 — ADR-0002 |
| 3. DDL 확정 → 합성 데이터 생성·적재·검증 | 완료 — 사용자 50만, 불변식 75개 위반 0, 시드 이미지 16.6GB |
| 4. 행위 테스트 → 갓 클래스 → 레이어드 분리 | 진행 — 행위 테스트 1단계(ADR-0003, 계약 117) · 갓 클래스 1단계 완료(god 계약 117/117, verify 0), 다음 레이어드 분리 |
| 5. 과제·판정·러너 → 실행·분석 | |
