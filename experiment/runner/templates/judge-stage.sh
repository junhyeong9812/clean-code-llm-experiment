#!/usr/bin/env bash
# 판정 단계 하나(판정 컨테이너 안 — 에이전트 실행이 끝난 뒤에만 뜬다).
# 앱 기동 → 테스트 → (회귀 단계면) verify.sql. 결과는 /work/.judge/out/<stage>.{status,rc,log,verify,verify.rc}
# 사용: judge-stage.sh <public|hidden|contract|contract2>
set -uo pipefail
stage=$1
cd /work
out=/work/.judge/out
mkdir -p "$out"
port=8099
export PGPASSWORD="$DB_PASSWORD"

jar=$(ls app/build/libs/*.jar 2>/dev/null | grep -v plain | head -1)
[ -n "$jar" ] || { echo no_jar > "$out/$stage.status"; exit 0; }

SERVER_PORT=$port java -jar "$jar" > "$out/$stage.app.log" 2>&1 &
app=$!
ready=
for _ in $(seq 180); do
  kill -0 $app 2>/dev/null || break
  curl -sf "http://127.0.0.1:$port/actuator/health" > /dev/null && { ready=1; break; }
  sleep 0.5
done
if [ -z "$ready" ]; then
  echo app_start_fail > "$out/$stage.status"
  kill $app 2>/dev/null; exit 0
fi

export BASE_URL="http://127.0.0.1:$port"
case "$stage" in
  public)    ./gradlew --console=plain :checks:test --tests 'pub.*' > "$out/$stage.log" 2>&1 ;;
  hidden)    ./gradlew --console=plain :checks:test --tests 'hidden.*' > "$out/$stage.log" 2>&1 ;;
  contract|contract2)
    task=contractTest; [ "$stage" = contract2 ] && task=contract2Test
    BEHAVIOR_TESTS=1 PG_EXTERNAL=1 PG_PORT=15480 PG_DELAY_MS=3000 \
      ./gradlew --console=plain -I /work/.judge/exclude.gradle ":behavior-tests:$task" --rerun --continue \
      > "$out/$stage.log" 2>&1 ;;
  *) echo "알 수 없는 단계 $stage" > "$out/$stage.log"; false ;;
esac
echo $? > "$out/$stage.rc"
echo ran > "$out/$stage.status"

if [ "$stage" = contract ] || [ "$stage" = contract2 ]; then
  psql -h db -U "$DB_USER" -d ccle_seed -v ON_ERROR_STOP=1 -q < /work/.judge/verify.sql > "$out/$stage.verify" 2>&1
  echo $? > "$out/$stage.verify.rc"
fi
kill $app 2>/dev/null
wait $app 2>/dev/null
exit 0
