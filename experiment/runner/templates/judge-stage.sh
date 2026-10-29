#!/usr/bin/env bash
# 판정 단계 하나(판정 테스트 컨테이너 안 — 에이전트 실행이 끝난 뒤에만 뜬다). /work = 판정 전용 테스트 작업 공간.
# 앱은 별도 컨테이너(같은 네트워크 이름 공간)에서 이미 기동 중 → 준비 대기 → 테스트 → (회귀 단계면) verify.sql.
# 결과는 /work/.judge/out/<stage>.{status,rc,log,verify,verify.rc}
# 사용: judge-stage.sh <public|hidden|contract|contract2|contract3>
set -uo pipefail
stage=$1
cd /work
out=/work/.judge/out
mkdir -p "$out"
port=8099
export PGPASSWORD="$DB_PASSWORD"

ready=
for _ in $(seq 360); do
  curl -sf "http://127.0.0.1:$port/actuator/health" > /dev/null && { ready=1; break; }
  sleep 0.5
done
if [ -z "$ready" ]; then
  # 앱 프로세스가 죽었는지(확정 실패)·살아 있는데 안 뜨는지(재시도 대상)는 판정기가 앱 컨테이너 상태로 가른다
  echo app_start_timeout > "$out/$stage.status"; exit 0
fi

export BASE_URL="http://127.0.0.1:$port"
case "$stage" in
  public)    ./gradlew --console=plain :checks:test --tests 'pub.*' > "$out/$stage.log" 2>&1 ;;
  hidden)    ./gradlew --console=plain :checks:test --tests 'hidden.*' > "$out/$stage.log" 2>&1 ;;
  contract|contract2|contract3)
    task=${stage}Test  # contract→contractTest · contract2→contract2Test · contract3→contract3Test(judge.py REGRESSION_TASKS)
    BEHAVIOR_TESTS=1 PG_EXTERNAL=1 PG_PORT=15480 PG_DELAY_MS=3000 \
      ./gradlew --console=plain -I /work/.judge/exclude.gradle ":behavior-tests:$task" --rerun --continue \
      > "$out/$stage.log" 2>&1 ;;
  *) echo "알 수 없는 단계 $stage" > "$out/$stage.log"; false ;;
esac
echo $? > "$out/$stage.rc"
echo ran > "$out/$stage.status"

if [ "$stage" = contract ] || [ "$stage" = contract2 ] || [ "$stage" = contract3 ]; then
  psql -h db -U "$DB_USER" -d ccle_seed -v ON_ERROR_STOP=1 -q < /work/.judge/verify.sql > "$out/$stage.verify" 2>&1
  echo $? > "$out/$stage.verify.rc"
fi
exit 0
