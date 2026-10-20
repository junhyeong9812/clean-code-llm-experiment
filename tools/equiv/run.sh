#!/usr/bin/env bash
# god·layered 동치 비교: 같은 시나리오를 god → layered 순서로 일회용 시드 DB에서 돌리고,
# 응답·새 행·Hibernate SQL·DB 문장 로그 4층을 정규화해 diff 한다.
# 사용: tools/equiv/run.sh <시나리오|all>        시나리오 = scenarios/<이름>.sh
#   GOD_JAR=… LAYERED_JAR=…  jar 지정(도구 검증용). 지정하지 않은 앱은 bootJar를 빌드해 쓴다.
# 결과: build/equiv/<시나리오>/{god,layered}/(원본 + norm/), diff/<층>.diff(차이 있을 때만). 자세한 것은 README.md
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

# EQ_SQL_LOG·EQ_PG_LOG는 측정 도구 전용 — 판정에서 로그를 끄면 비교할 층이 비므로 받지 않는다
for v in EQ_SQL_LOG EQ_PG_LOG; do
  [ -z "${!v:-}" ] || [ "${!v}" = 1 ] || die "$v=${!v}: 측정 도구(measure-expiry.sh) 전용 옵션이라 판정에는 쓸 수 없다"
done

# 중단·실패 때 앱 실행(자식)이 자기 trap으로 정리한다. 여기서는 남은 컨테이너를 한 번 더 지우고 확인한다.
entry_begin
# 가짜 PG가 필요한 시나리오(start_pg를 부르는 것)가 있을 때만 그 클래스패스를 만든다
extra=()
for sc in $scenarios; do
  if grep -q '^ *start_pg' "$here/scenarios/$sc.sh"; then extra+=(:behavior-tests:fakePgClasspath); break; fi
done
prepare_jars "${extra[@]}"

overall=0
for sc in $scenarios; do
  d=$root/build/equiv/$sc
  rm -rf "$d"
  ok=1 t0=$SECONDS
  for app in god layered; do
    echo "[$sc] $app 실행…"
    rc=0; bash "$here/scenarios/$sc.sh" "$app" "${jar[$app]}" "$d/$app" || rc=$?
    [ $rc -ne 130 ] || exit 130
    if [ $rc -ne 0 ] || [ ! -f "$d/$app/done" ]; then
      echo "[$sc] $app 실행 실패(exit $rc) — 비교하지 않음. 로그: $d/$app" >&2; ok=0; break
    fi
  done
  if [ $ok = 1 ]; then
    rc=0; compare_layers "$sc" "$d" || rc=$?
  else
    rc=2
  fi
  case $rc in 0) v="같음" ;; 1) v="차이 있음 → $d/diff/" ;; *) v="실패(비교 불성립)" ;; esac
  echo "[$sc] 판정: $v ($((SECONDS - t0))초)"
  [ $rc -le $overall ] || overall=$rc
done
exit $overall
