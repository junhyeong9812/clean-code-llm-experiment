#!/usr/bin/env bash
# 과제 확인: DB 를 처음 상태로 되돌리고 → 앱을 빌드·기동 → checks 모듈의 테스트를 돌린다. 통과하면 exit 0.
# DB·PG 접속은 환경 변수(DB_URL·DB_USER·DB_PASSWORD·PG_URL). 앱은 이 스크립트가 PORT(기본 8099)에 따로 띄우고 끝나면 내린다.
set -euo pipefail
cd "$(dirname "$0")"
port=${PORT:-8099}
host=${DB_HOST:-db}
export PGPASSWORD="$DB_PASSWORD"

echo "[test.sh] DB 초기화"
psql -h "$host" -U "$DB_USER" -d postgres -v ON_ERROR_STOP=1 -q \
  -c "DROP DATABASE IF EXISTS ccle_seed WITH (FORCE)" -c "CREATE DATABASE ccle_seed TEMPLATE ccle_tpl"

echo "[test.sh] 앱 빌드"
./gradlew -q :app:bootJar
jar=$(ls app/build/libs/*.jar | grep -v plain | head -1)

echo "[test.sh] 앱 기동(:$port)"
log=$(mktemp)
SERVER_PORT=$port java -jar "$jar" > "$log" 2>&1 &
app=$!
trap 'kill $app 2>/dev/null || true; wait $app 2>/dev/null || true' EXIT
for _ in $(seq 120); do
  kill -0 $app 2>/dev/null || { echo "앱 기동 실패:"; tail -40 "$log"; exit 1; }
  curl -sf "http://127.0.0.1:$port/actuator/health" > /dev/null && break
  sleep 0.5
done
curl -sf "http://127.0.0.1:$port/actuator/health" > /dev/null || { echo "앱 health 대기 시간 초과:"; tail -40 "$log"; exit 1; }

echo "[test.sh] 테스트"
BASE_URL="http://127.0.0.1:$port" ./gradlew -q :checks:test
