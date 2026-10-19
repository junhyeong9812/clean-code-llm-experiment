# 동치 비교 하네스 공통 함수. run.sh(진입점)와 scenarios/*.sh(앱 1회 실행)가 source 한다.
# 앱 1회 실행: app_begin → start_db → 픽스처(q) → start_app → 요청(call·adv) → dump → app_end. 실패하면 exit ≠ 0, 정리는 trap.
# 비교: extract_layers(원본 → norm/<층>/) → compare_layers(층별 diff·줄 수·판정).

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
export JAVA_HOME=${JAVA_HOME_21:-$HOME/.sdkman/candidates/java/21.0.5-tem}
# tools/behavior/run.sh(8081·8082·15435·15480)·개발 DB(15432)와 겹치지 않는 고정 포트·이름. 한 번에 하나만 돌린다.
EQ_DB=ccle-equiv-db EQ_DB_PORT=15445 EQ_APP_PORT=18085 EQ_PG_PORT=15485
EQ_BASE=http://127.0.0.1:$EQ_APP_PORT

die() { echo "equiv: $*" >&2; exit 2; }

# 다른 프로세스가 포트를 쓰고 있으면 그 프로세스를 판정하게 되므로 시작하지 않는다(tools/behavior/run.sh와 같은 방식)
ports_free() {
  local p
  for p in "$EQ_APP_PORT" "$EQ_PG_PORT" "$EQ_DB_PORT"; do
    if (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null; then die "포트 $p 를 이미 쓰는 프로세스가 있음"; fi
  done
}

# ---- 앱 1회 실행 ----

# 사용: app_begin <god|layered> <jar> <outdir>
app_begin() {
  set -euo pipefail; shopt -s inherit_errexit
  app=$1 jar=$2 out=$3
  [ -f "$jar" ] || die "jar 없음: $jar"
  app_pid=
  ports_free
  trap 'exit 130' INT; trap 'exit 143' TERM; trap app_cleanup EXIT
  mkdir -p "$out/rows"
}

app_cleanup() {
  trap - EXIT INT TERM
  if [ -n "${app_pid:-}" ]; then
    kill "$app_pid" 2>/dev/null || true
    for _ in $(seq 50); do kill -0 "$app_pid" 2>/dev/null || break; sleep 0.2; done
    kill -9 "$app_pid" 2>/dev/null || true
    wait "$app_pid" 2>/dev/null || true
  fi
  docker rm -f "$EQ_DB" > /dev/null 2>&1 || true
}

q() { docker exec -i "$EQ_DB" psql -U ccle -d ccle_seed -Atq -v ON_ERROR_STOP=1 -c "$1"; }

# 일회용 1k 시드 DB + DB 문장 로그(log_statement=all, 접두 = application_name). 픽스처(psql)는 접두로 비교에서 빠진다.
start_db() {
  docker rm -f "$EQ_DB" > /dev/null 2>&1 || true
  "$root/tools/seedgen/run-db.sh" 1k-s42 "$EQ_DB" "$EQ_DB_PORT" > /dev/null
  q "ALTER SYSTEM SET log_statement = 'all'" > /dev/null
  q "ALTER SYSTEM SET log_line_prefix = '%a|'" > /dev/null
  q "SELECT pg_reload_conf()" > /dev/null
}

# 사용: start_app [PG 타임아웃 ms]. 프로세스가 죽거나 health 대기 시간을 넘기면 실패.
start_app() {
  DB_URL="jdbc:postgresql://127.0.0.1:$EQ_DB_PORT/ccle_seed" DB_USER=ccle DB_PASSWORD=ccle SERVER_PORT=$EQ_APP_PORT \
    PG_URL=http://127.0.0.1:$EQ_PG_PORT PG_TIMEOUT_MS=${1:-2000} \
    "$JAVA_HOME/bin/java" -jar "$jar" --logging.level.org.hibernate.SQL=debug >> "$out/app.log" 2>&1 &
  app_pid=$!
  for _ in $(seq 120); do
    kill -0 "$app_pid" 2>/dev/null || die "$app 기동 실패: $out/app.log"
    curl -sf "$EQ_BASE/actuator/health" > /dev/null && return 0
    sleep 0.5
  done
  die "$app health 대기 시간 초과: $out/app.log"
}

# 사용: call <METHOD> <path> [X-User-Id] [JSON 본문] [Idempotency-Key]. 응답 본문·상태를 responses.txt에 한 줄로.
# 연결 실패 등 curl 오류는 시나리오 실패(set -e). HTTP 4xx·5xx는 비교 대상이라 실패가 아니다.
call() {
  set -- "$1" "$2" "${3:-}" "${4:-}" "${5:-}"
  local r
  r=$(curl -sS -X "$1" -H 'Content-Type: application/json' ${3:+-H "X-User-Id: $3"} ${5:+-H "Idempotency-Key: $5"} ${4:+-d "$4"} -w ' %{http_code}' "$EQ_BASE$2")
  echo "$1 $2 key=$5 → $r" >> "$out/responses.txt"
}

# 사용: adv <초> — 앱 시계를 앞으로 옮긴다
adv() {
  local now nxt
  now=$(curl -sS "$EQ_BASE/admin/clock" | sed 's/.*"now":"\([^"]*\)".*/\1/')
  nxt=$(date -d "$now + $1 seconds" --iso-8601=seconds)
  call PUT /admin/clock "" "{\"now\":\"$nxt\"}"
}

# 사용: dump <이름> <SQL> — 새 행을 rows/<이름>.txt로. id는 시나리오 시작 시 max(id)를 빼서(오프셋) 쓴다. 0줄이면 실패.
dump() {
  q "$2" > "$out/rows/$1.txt"
  [ -s "$out/rows/$1.txt" ] || die "$app 덤프 0줄: $1"
}

# 앱이 끝까지 살아 있었는지 확인하고 DB 문장 로그를 받아 둔다. done 표식이 없으면 run.sh는 비교하지 않는다.
app_end() {
  kill -0 "$app_pid" 2>/dev/null || die "$app 이 시나리오 중 종료됨: $out/app.log"
  docker logs "$EQ_DB" > "$out/pg.log" 2>&1
  touch "$out/done"
}

# ---- 추출·정규화 (규칙은 이름 붙은 함수로만) ----

# 추출 ext_hibernate_sql: 기동 완료(": Started ") 뒤 org.hibernate.SQL 레코드의 SQL 본문. 날짜로 시작하지 않는 줄은 앞 레코드의 이어진 줄(⏎로 잇는다).
ext_hibernate_sql() {
  awk '
    /^[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T/ {
      if (keep) print rec; keep = 0
      if (started && index($0, " org.hibernate.SQL ")) { rec = $0; sub(/.*org\.hibernate\.SQL +: /, "", rec); keep = 1 }
      if (index($0, ": Started ")) started = 1
      next }
    { if (keep) rec = rec " ⏎ " $0 }
    END { if (keep) print rec }' "$1"
}

# 추출 ext_pg_app_statements: DB 문장 로그 중 앱이 보낸 것(application_name "PostgreSQL JDBC Driver")만, DETAIL(바인드 값) 제외.
# PostgreSQL은 문장 안 줄바꿈을 탭으로 시작하는 이어진 줄로 쓴다 — 앞 레코드에 ⏎로 잇는다(버리지 않는다).
ext_pg_app_statements() {
  awk '
    /^\t/ { if (keep) rec = rec " ⏎ " substr($0, 2); next }
    { if (keep) print rec; keep = 0
      if (index($0, "PostgreSQL JDBC Driver|") == 1) { rec = substr($0, 24); keep = (rec !~ /^DETAIL:/) } }
    END { if (keep) print rec }' "$1"
}

# 규칙 norm_drop_pool_probe: 빈 문장("LOG:  statement: " 뒤가 비었음)을 지운다. pgjdbc Connection.isValid가 보내는 빈 질의로,
# HikariCP가 빌려줄 때 유휴 500ms를 넘긴 연결을 검사하거나 health가 DB를 확인할 때 나간다 — 요청 사이 간격(부하)에 따라 수가 바뀐다.
# 앱 로직이 보내는 문장은 비어 있을 수 없어 이 규칙이 실제 차이를 가리지 않는다.
norm_drop_pool_probe() { awk '$0 != "LOG:  statement: "'; }

# 앱 1회 결과(원본) → <dir>/norm/{responses,rows,sql,pgstmt}/
extract_layers() {
  local d=$1
  rm -rf "$d/norm"; mkdir -p "$d/norm/responses" "$d/norm/rows" "$d/norm/sql" "$d/norm/pgstmt"
  cp "$d/responses.txt" "$d/norm/responses/" &&
    cp "$d"/rows/*.txt "$d/norm/rows/" &&
    ext_hibernate_sql "$d/app.log" > "$d/norm/sql/sql.txt" &&
    ext_pg_app_statements "$d/pg.log" | norm_drop_pool_probe > "$d/norm/pgstmt/pgstmt.txt"
}

EQ_LAYERS="responses rows sql pgstmt"
declare -A EQ_LAYER_NAME=([responses]=응답 [rows]=새행 [sql]=HibernateSQL [pgstmt]=DB문장로그)

# 사용: compare_layers <시나리오> <시나리오 결과 dir>. 반환: 0 같음, 1 차이, 2 빈 비교·diff 오류.
# 층마다 파일 수·줄 수를 출력하고, 차이는 <dir>/diff/<층>.diff 로 남긴다.
compare_layers() {
  local sc=$1 d=$2 layer f gf lf gl ll rc res=0
  mkdir -p "$d/diff"
  for layer in $EQ_LAYERS; do
    gf=$(find "$d/god/norm/$layer" -type f | wc -l); lf=$(find "$d/layered/norm/$layer" -type f | wc -l)
    gl=$(cat "$d/god/norm/$layer"/* | wc -l); ll=$(cat "$d/layered/norm/$layer"/* | wc -l)
    for f in "$d/god/norm/$layer"/* "$d/layered/norm/$layer"/*; do
      [ -s "$f" ] || { echo "[$sc] ${EQ_LAYER_NAME[$layer]}: 빈 파일 ${f#"$d"/} — 비교하지 않은 것으로 실패" >&2; res=2; }
    done
    rc=0; diff -ruN "$d/god/norm/$layer" "$d/layered/norm/$layer" > "$d/diff/$layer.diff" || rc=$?
    case $rc in
      0) rm -f "$d/diff/$layer.diff"; printf '[%s] %-12s 파일 god %d / layered %d  줄 god %d / layered %d  같음\n' "$sc" "${EQ_LAYER_NAME[$layer]}" "$gf" "$lf" "$gl" "$ll" ;;
      1) printf '[%s] %-12s 파일 god %d / layered %d  줄 god %d / layered %d  차이 → %s\n' "$sc" "${EQ_LAYER_NAME[$layer]}" "$gf" "$lf" "$gl" "$ll" "$d/diff/$layer.diff"
         [ $res = 2 ] || res=1 ;;
      *) echo "[$sc] ${EQ_LAYER_NAME[$layer]}: diff 오류(exit $rc)" >&2; res=2 ;;
    esac
  done
  return $res
}
