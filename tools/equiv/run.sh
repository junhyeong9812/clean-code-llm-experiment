#!/usr/bin/env bash
# god·layered 동치 비교: 같은 시나리오를 god → layered 순서로 일회용 시드 DB에서 돌리고,
# 응답·새 행·Hibernate SQL·DB 문장 로그 4층을 정규화해 diff 한다.
# 사용: tools/equiv/run.sh <시나리오|all>        시나리오 = scenarios/<이름>.sh
#   GOD_JAR=… LAYERED_JAR=…  jar 지정(도구 검증용). 지정하지 않은 앱은 bootJar를 빌드해 쓴다.
# 결과: build/equiv/<시나리오>/{god,layered}/(원본 + norm/), diff/<층>.diff(차이 있을 때만)
# 종료 코드: 0 전부 같음 · 1 차이 있음 · 2 실행 실패(포트·jar·기동·시나리오 명령·빈 비교) · 130 중단
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
source "$here/lib.sh"

[ $# -eq 1 ] || die "사용: tools/equiv/run.sh <$(cd "$here/scenarios" && ls | sed 's/\.sh$//' | paste -sd '|')|all>"
if [ "$1" = all ]; then
  scenarios=$(cd "$here/scenarios" && ls | sed 's/\.sh$//')
else
  [ -f "$here/scenarios/$1.sh" ] || die "시나리오 없음: $1"
  scenarios=$1
fi

# 중단·실패 때 앱 실행(자식)이 자기 trap으로 정리한다. 여기서는 남은 컨테이너만 한 번 더 지운다.
trap 'echo "equiv: 중단됨" >&2; exit 130' INT; trap 'exit 143' TERM; trap 'exit 2' ERR   # 예상 못 한 명령 실패는 '차이(1)'가 아니라 실행 실패(2)

ports_free   # 다른 실행이 쓰는 중이면 그 컨테이너를 지우지 않도록 EXIT trap보다 먼저
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

overall=0
for sc in $scenarios; do
  d=$root/build/equiv/$sc
  rm -rf "$d"
  ok=1
  for app in god layered; do
    echo "[$sc] $app 실행…"
    rc=0; bash "$here/scenarios/$sc.sh" "$app" "${jar[$app]}" "$d/$app" || rc=$?
    [ $rc -ne 130 ] || exit 130
    if [ $rc -ne 0 ] || [ ! -f "$d/$app/done" ]; then
      echo "[$sc] $app 실행 실패(exit $rc) — 비교하지 않음. 로그: $d/$app" >&2; ok=0; break
    fi
    extract_layers "$d/$app"
  done
  if [ $ok = 1 ]; then
    rc=0; compare_layers "$sc" "$d" || rc=$?
  else
    rc=2
  fi
  case $rc in 0) echo "[$sc] 판정: 같음" ;; 1) echo "[$sc] 판정: 차이 있음 → $d/diff/" ;; *) echo "[$sc] 판정: 실패(비교 불성립)" ;; esac
  [ $rc -le $overall ] || overall=$rc
done
exit $overall
