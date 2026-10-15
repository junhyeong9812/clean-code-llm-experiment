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

결정 기록: [ADR-0001 기본 세팅](docs/adr/0001-base-setup.md) · [ADR-0002 도메인 절차·규칙과 DB 설계](docs/adr/0002-domain-and-database.md)

## 실행

```bash
docker compose -f docker/compose.yml up -d     # PostgreSQL 17, 127.0.0.1:15432
./gradlew build                                 # JDK 21, 두 모듈 + 스모크 테스트(DB 접속)
java -jar god/build/libs/god-0.0.1.jar          # :8081
java -jar layered/build/libs/layered-0.0.1.jar  # :8082
curl localhost:8081/actuator/health
```

## 진행

| 단계 | 상태 |
|---|---|
| 1. 기본 세팅(두 서버 + 도커 PostgreSQL) | 완료 — ADR-0001 |
| 2. 도메인·절차 정의와 DB 설계 | 완료 — ADR-0002 |
| 3. DDL 확정 → 합성 데이터 생성·적재·검증 | 다음 |
| 4. 행위 테스트 → 갓 클래스 → 레이어드 분리 | |
| 5. 과제·판정·러너 → 실행·분석 | |
