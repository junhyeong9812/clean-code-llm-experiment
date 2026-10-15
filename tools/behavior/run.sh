#!/usr/bin/env bash
# 행위 테스트 1회 실행: 1천 명 시드 DB 컨테이너 → 앱 jar → behavior-tests(가짜 PG는 테스트 JVM 안에서 뜬다) → verify.sql → 정리.
# 사용: tools/behavior/run.sh <god|layered> [gradle 태스크, 기본 test]
# 결과: behavior-tests/build/test-results, build/reports. 종료 코드 = 테스트 결과(실패면 ≠ 0), verify 실패면 3.
set -euo pipefail
app=$1 task=${2:-test}
case "$app" in god) port=8081 ;; layered) port=8082 ;; *) echo "앱은 god 또는 layered" >&2; exit 2 ;; esac
root=$(cd "$(dirname "$0")/../.." && pwd)
export JAVA_HOME=${JAVA_HOME_21:-$HOME/.sdkman/candidates/java/21.0.5-tem}
db=ccle-bt-$app db_port=15435 pg_port=15480
app_pid=

cleanup() {
  [ -n "$app_pid" ] && kill "$app_pid" 2>/dev/null && wait "$app_pid" 2>/dev/null || true
  docker rm -f "$db" > /dev/null 2>&1 || true
}
trap cleanup EXIT

cd "$root"
./gradlew -q ":$app:bootJar" :behavior-tests:testClasses
docker rm -f "$db" > /dev/null 2>&1 || true
"$root/tools/seedgen/run-db.sh" 1k-s42 "$db" "$db_port"
export DB_URL="jdbc:postgresql://127.0.0.1:$db_port/ccle_seed" DB_USER=ccle DB_PASSWORD=ccle
export PG_URL="http://127.0.0.1:$pg_port" PG_PORT=$pg_port PG_TIMEOUT_MS=2000
export BASE_URL="http://127.0.0.1:$port" SERVER_PORT=$port

log=$root/behavior-tests/build/app-$app.log
mkdir -p "$(dirname "$log")"
"$JAVA_HOME/bin/java" -jar "$app/build/libs/$app-0.0.1.jar" > "$log" 2>&1 &
app_pid=$!
for _ in $(seq 120); do
  curl -sf "$BASE_URL/actuator/health" > /dev/null && break
  kill -0 "$app_pid" 2>/dev/null || { echo "앱 기동 실패: $log" >&2; exit 2; }
  sleep 0.5
done
curl -sf "$BASE_URL/actuator/health" > /dev/null || { echo "앱 health 대기 시간 초과: $log" >&2; exit 2; }

rc=0
./gradlew ":behavior-tests:$task" --rerun || rc=$?
echo "불변식 검증(verify.sql)…"
docker exec -i "$db" psql -U ccle -d ccle_seed -v ON_ERROR_STOP=1 -q < "$root/tools/seedgen/verify.sql" 2>&1 | tail -1 || { echo "verify 실패" >&2; exit 3; }
exit "$rc"
