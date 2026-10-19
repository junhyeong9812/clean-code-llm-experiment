#!/usr/bin/env bash
# bid-expiry 소요 시간 측정(판정 없음): 새 1k 시드 DB마다 앱을 띄우고 시계를 2027-01-14T06:34:02+09:00으로 옮긴 뒤
# bid-expiry를 1회 부른다. god·layered를 교대로 N회(기본 2). 측정을 흐리지 않도록 DB 문장 로그·Hibernate SQL 로그는 끈다.
# 사용: tools/equiv/measure-expiry.sh [N]      GOD_JAR=… LAYERED_JAR=… 로 jar 지정(기본은 bootJar 빌드)
# 출력: 회차·앱·처리 건수·curl 소요(초)·앱 로그의 배치 소요·EXPIRED 이벤트 수. 결과: build/equiv/measure-expiry/
# 종료 코드: 0 측정 완료 · 2 실행 실패 · 130 중단
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
source "$here/lib.sh"
n=${1:-2}
[[ $n =~ ^[1-9][0-9]*$ ]] || die "사용: tools/equiv/measure-expiry.sh [N]"

trap 'echo "equiv: 중단됨" >&2; exit 130' INT; trap 'exit 143' TERM; trap 'exit 2' ERR
ports_free
trap 'docker rm -f "$EQ_DB" > /dev/null 2>&1 || true' EXIT
declare -A jar
build=()
for app in god layered; do
  var=${app^^}_JAR
  if [ -n "${!var:-}" ]; then
    jar[$app]=$(realpath -e "${!var}" 2>/dev/null) || die "$var 없음: ${!var}"
  else
    jar[$app]=$root/$app/build/libs/$app-0.0.1.jar; build+=(":$app:bootJar")
  fi
done
if [ ${#build[@]} -gt 0 ]; then (cd "$root" && ./gradlew -q "${build[@]}"); fi
echo "god: ${jar[god]}"; echo "layered: ${jar[layered]}"

base=$root/build/equiv/measure-expiry
rm -rf "$base"
one() { # <회차> <앱> — 하위 셸에서 돈다(정리는 app_begin의 trap)
  app_begin "$2" "${jar[$2]}" "$base/$1-$2"
  EQ_PG_LOG=0 start_db
  EQ_SQL_LOG=0 start_app 2000
  call PUT /admin/clock "" '{"now":"2027-01-14T06:34:02+09:00"}'
  local t body batch events
  t=$(curl -sS -o "$out/body.txt" -w '%{time_total}' -X POST "$EQ_BASE/admin/batches/bid-expiry")
  body=$(cat "$out/body.txt")
  [[ $body == *'"processed"'* ]] || die "$2 bid-expiry 응답이 이상함: $body"
  batch=$(grep -h '배치 bid-expiry' "$out/app.log" | sed -E 's/.*processed=[0-9]+, //' | tail -1 || true)
  events=$(q "SELECT count(*) FROM bid_events WHERE to_status='EXPIRED' AND created_at='2027-01-13T21:34:02Z'")
  printf '%s\t%s\t%s\tcurl=%ss\t%s\tEXPIRED=%s\n' "$1" "$2" "$body" "$t" "${batch:-?}" "$events" | tee "$out/result.txt"
}
for i in $(seq "$n"); do
  for app in god layered; do (one "$i" "$app"); done
done
