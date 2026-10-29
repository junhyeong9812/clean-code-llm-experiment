#!/usr/bin/env bash
# 행위 테스트 1회 실행: 1천 명 시드 DB 컨테이너 → 가짜 PG → 앱 jar → behavior-tests → verify.sql → 정리.
# 사용: tools/behavior/run.sh <god|layered> [gradle 태스크, 기본 test(= infraTest 다음 contractTest)]
# 결과: behavior-tests/build/test-results, build/reports, build/run/*.log. 종료 코드 = 테스트 결과(실패면 ≠ 0), verify 실패면 3.
# 포트·컨테이너 이름이 고정이라 한 번에 하나만 돌린다(ADR-0002 §7: 실행 컨테이너를 동시에 여러 개 띄우지 않는다).
set -euo pipefail
app=$1 task=${2:-test}
case "$app" in god) port=8081 ;; layered) port=8082 ;; *) echo "앱은 god 또는 layered" >&2; exit 2 ;; esac
root=$(cd "$(dirname "$0")/../.." && pwd)
export JAVA_HOME=${JAVA_HOME_21:-$HOME/.sdkman/candidates/java/21.0.5-tem}
db=ccle-bt-$app db_port=15435 pg_port=15480
app_pid= pg_pid=
logs=$root/behavior-tests/build/run

cleanup() {
  for p in $app_pid $pg_pid; do kill "$p" 2>/dev/null && wait "$p" 2>/dev/null || true; done
  docker rm -f "$db" > /dev/null 2>&1 || true
}
trap cleanup EXIT

# 다른 프로세스가 포트를 쓰고 있으면 그 프로세스를 판정하게 되므로 시작하지 않는다
for p in "$port" "$pg_port" "$db_port"; do
  if (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null; then echo "포트 $p 를 이미 쓰는 프로세스가 있음" >&2; exit 2; fi
done

cd "$root"
mkdir -p "$logs"
./gradlew -q ":$app:bootJar" :behavior-tests:testClasses :behavior-tests:fakePgClasspath
docker rm -f "$db" > /dev/null 2>&1 || true
"$root/tools/seedgen/run-db.sh" 1k-s42-v2 "$db" "$db_port"
export DB_URL="jdbc:postgresql://127.0.0.1:$db_port/ccle_seed" DB_USER=ccle DB_PASSWORD=ccle
export PG_URL="http://127.0.0.1:$pg_port" PG_PORT=$pg_port PG_TIMEOUT_MS=2000 PG_DELAY_MS=3000 PG_EXTERNAL=1
export BASE_URL="http://127.0.0.1:$port" SERVER_PORT=$port BEHAVIOR_TESTS=1

# 가짜 PG: 앱보다 먼저 띄운다(앱이 기동 중 PG에 붙어도 되게)
"$JAVA_HOME/bin/java" -cp "$(cat behavior-tests/build/fake-pg.classpath)" com.ccle.behavior.pg.FakePgKt > "$logs/fake-pg.log" 2>&1 &
pg_pid=$!
for _ in $(seq 100); do
  grep -q "fake-pg ready" "$logs/fake-pg.log" && break
  kill -0 "$pg_pid" 2>/dev/null || { echo "가짜 PG 기동 실패: $logs/fake-pg.log" >&2; exit 2; }
  sleep 0.2
done
grep -q "fake-pg ready" "$logs/fake-pg.log" || { echo "가짜 PG 대기 시간 초과" >&2; exit 2; }

"$JAVA_HOME/bin/java" -jar "$app/build/libs/$app-0.0.1.jar" > "$logs/app-$app.log" 2>&1 &
app_pid=$!
ready=
for _ in $(seq 120); do
  kill -0 "$app_pid" 2>/dev/null || { echo "앱 기동 실패: $logs/app-$app.log" >&2; exit 2; }
  curl -sf "$BASE_URL/actuator/health" > /dev/null && { ready=1; break; }
  sleep 0.5
done
[ -n "$ready" ] && kill -0 "$app_pid" 2>/dev/null || { echo "앱 health 대기 시간 초과: $logs/app-$app.log" >&2; exit 2; }

rc=0
./gradlew ":behavior-tests:$task" --rerun --continue || rc=$?

echo "불변식 검증(verify.sql)…"
if docker exec -i "$db" psql -U ccle -d ccle_seed -v ON_ERROR_STOP=1 -q < "$root/tools/seedgen/verify.sql" > "$logs/verify.log" 2>&1; then
  grep -E 'NOTICE: +verify' "$logs/verify.log"
else
  grep -E 'ERROR|NOTICE' "$logs/verify.log" >&2; echo "verify 실패: $logs/verify.log" >&2; exit 3
fi
exit "$rc"
