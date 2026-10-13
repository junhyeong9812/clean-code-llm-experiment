# log — project-setup

## 타임라인

| 시각 | 사건 | 결과 |
|---|---|---|
| 2026-10-13 | 사용자: graph-nav-experiment 결론을 SOLID와 연결해 후기로, 다음 실험(결제·입찰·구매·정산, 갓 클래스 vs 레이어드, Kotlin) 셋업 요청. 인터뷰: 새 저장소 바로 공개, Spring Boot + Kotlin, 구현당 1만 줄+, 갓 클래스 → 레이어드 분리, 클래스 1개·파일 1개, README 후기 푸시는 새 저장소 링크와 함께 | graph-nav README 후기 커밋(`docs/afterword`, 미푸시). 로컬 캐시: Spring Boot 3.5.5·Kotlin 2.2.20·Gradle 8.14.4·JDK 21 |
| 2026-10-13 | 명세 작성 → 추가 요구: 실제 PostgreSQL(도커), CSV 원본, 사용자 50만·거래 약 500만 합성 데이터, 실행마다 컨테이너, DB만 도커·앱은 호스트 | 명세 갱신. 데이터 생성·시드 이미지는 절차 정의 뒤 작업으로 분리. 환경: postgres:16/17/18 로컬 이미지, 디스크 여유 53GB, 가용 메모리 약 10GB |
| 2026-10-13 | 추가 요구: 사용자 포인트 소유(결제 금액 기반 적립·결제 시 사용) + DB 설계 | 도메인 범위에 포인트 추가. DB 설계는 다음 작업(절차 정의와 함께) |
| 2026-10-13 | 사용자 "우선 기본 세팅을 위한 2개의 기본 서버 구성 및 app이 뜨도록까지만" + "도메인 설계는 구동 확인 후 같이 진행, 기본 세팅이 ADR-1" → 범위 축소(공개 푸시·graph-nav 링크는 후속 확인) | SPEC=1·MODE=auto |
| 2026-10-13 | git init(main 초기 커밋: wrapper 8.14.4·.gitignore·README). 첫 커밋에 docs가 섞여 미푸시 상태에서 amend로 분리 | |
| 2026-10-13 | 멀티 모듈(god·layered, 루트 subprojects 공통 의존성), compose(postgres:17, 127.0.0.1:15432, init으로 ccle_god·ccle_layered) | |
| 2026-10-13 | 검증: compose healthy·DB 2개 확인. `./gradlew build --offline` 성공, 스모크 2개(tests=1 failures=0 ×2, DB 실접속). jar 기동 8081·8082 health UP(db UP) | load-bearing 가정 1 확인(오프라인 빌드) |
| 2026-10-13 | ADR-0001 기본 세팅, README(배경·가설·설계·실행·진행) | |

## 리뷰 ledger

## 생략한 검증

## 완료 요약

- 두 서버(god :8081, layered :8082)가 같은 의존성으로 뜨고 도커 PostgreSQL 17의 각자 DB에 접속한다. ADR-0001 기록. 공개 푸시는 사용자 확인 대기.
