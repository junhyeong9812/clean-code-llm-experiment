# 동치 비교 하네스 공통 함수. run.sh(진입점)·measure-expiry.sh·scenarios/*.sh(앱 1회 실행)가 source 한다.
# 앱 1회 실행: app_begin → start_db → 픽스처(q) → [start_pg] → start_app → 요청(call·adv·mark) → dump → app_end.
# 실패하면 exit ≠ 0, 정리는 trap. app_end가 원본을 norm/<층>/으로 추출·정규화하고, run.sh가 compare_layers로 판정한다.
# 정규화 규칙(norm_*)의 목록·이유는 README.md — 여기와 같은 목록을 유지한다.

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
export JAVA_HOME=${JAVA_HOME_21:-$HOME/.sdkman/candidates/java/21.0.5-tem}
# tools/behavior/run.sh(8081·8082·15435·15480)·개발 DB(15432)와 겹치지 않는 고정 포트·이름. 한 번에 하나만 돌린다.
EQ_DB=ccle-equiv-db EQ_DB_PORT=15445 EQ_APP_PORT=18085 EQ_PG_PORT=15485
EQ_BASE=http://127.0.0.1:$EQ_APP_PORT
EQ_PG=http://127.0.0.1:$EQ_PG_PORT

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
  app_pid= pg_pid=
  ports_free
  trap 'exit 130' INT; trap 'exit 143' TERM; trap app_cleanup EXIT
  mkdir -p "$out/rows"
}

# 프로세스 종료: TERM 후 10초 안에 안 끝나면 -9
stop_pid() {
  kill "$1" 2>/dev/null || true
  for _ in $(seq 50); do kill -0 "$1" 2>/dev/null || break; sleep 0.2; done
  kill -9 "$1" 2>/dev/null || true
  wait "$1" 2>/dev/null || true
}

app_cleanup() {
  trap - EXIT INT TERM
  [ -z "${app_pid:-}" ] || stop_pid "$app_pid"
  [ -z "${pg_pid:-}" ] || stop_pid "$pg_pid"
  docker rm -f "$EQ_DB" > /dev/null 2>&1 || true
}

q() { docker exec -i "$EQ_DB" psql -U ccle -d ccle_seed -Atq -v ON_ERROR_STOP=1 -c "$1"; }
# 여러 문장(트리거 함수 등): 표준 입력의 SQL을 실행
qs() { docker exec -i "$EQ_DB" psql -U ccle -d ccle_seed -q -v ON_ERROR_STOP=1 > /dev/null; }

# 일회용 1k 시드 DB + DB 문장 로그(log_statement=all, 접두 = application_name). 픽스처(psql)는 접두로 비교에서 빠진다.
# EQ_PG_LOG=0 이면 문장 로그를 켜지 않는다(측정 도구 전용 — 비교에는 쓰지 않는다).
start_db() {
  docker rm -f "$EQ_DB" > /dev/null 2>&1 || true
  "$root/tools/seedgen/run-db.sh" 1k-s42 "$EQ_DB" "$EQ_DB_PORT" > /dev/null
  [ "${EQ_PG_LOG:-1}" = 1 ] || return 0
  q "ALTER SYSTEM SET log_statement = 'all'" > /dev/null
  q "ALTER SYSTEM SET log_line_prefix = '%a|'" > /dev/null
  q "SELECT pg_reload_conf()" > /dev/null
}

# 사용: start_pg [응답 지연 ms] — 가짜 PG(behavior-tests FakePg). run.sh가 fakePgClasspath를 빌드해 둔다.
start_pg() {
  local cp=$root/behavior-tests/build/fake-pg.classpath
  [ -s "$cp" ] || die "가짜 PG 클래스패스 없음: $cp"
  PG_PORT=$EQ_PG_PORT PG_DELAY_MS=${1:-3000} "$JAVA_HOME/bin/java" -cp "$(cat "$cp")" com.ccle.behavior.pg.FakePgKt > "$out/fake-pg.log" 2>&1 &
  pg_pid=$!
  for _ in $(seq 100); do
    kill -0 "$pg_pid" 2>/dev/null || die "가짜 PG 기동 실패: $out/fake-pg.log"
    grep -q "fake-pg ready" "$out/fake-pg.log" && return 0
    sleep 0.2
  done
  die "가짜 PG 대기 시간 초과: $out/fake-pg.log"
}

# 사용: start_app [PG 타임아웃 ms]. 프로세스가 죽거나 health 대기 시간을 넘기면 실패. 재기동하면 app.log에 이어 쓴다.
# EQ_SQL_LOG=0 이면 Hibernate SQL 로그를 끈다(측정 도구 전용).
start_app() {
  local sqllog=--logging.level.org.hibernate.SQL=debug
  [ "${EQ_SQL_LOG:-1}" = 1 ] || sqllog=
  DB_URL="jdbc:postgresql://127.0.0.1:$EQ_DB_PORT/ccle_seed" DB_USER=ccle DB_PASSWORD=ccle SERVER_PORT=$EQ_APP_PORT \
    PG_URL=$EQ_PG PG_TIMEOUT_MS=${1:-2000} \
    "$JAVA_HOME/bin/java" -jar "$jar" $sqllog >> "$out/app.log" 2>&1 &
  app_pid=$!
  for _ in $(seq 120); do
    kill -0 "$app_pid" 2>/dev/null || die "$app 기동 실패: $out/app.log"
    curl -sf "$EQ_BASE/actuator/health" > /dev/null && return 0
    sleep 0.5
  done
  die "$app health 대기 시간 초과: $out/app.log"
}

# 앱 정상 종료(TERM) / 강제 종료(-9) — 재기동 시나리오용
stop_app() { stop_pid "$app_pid"; app_pid=; }
kill_app() { kill -9 "$app_pid"; wait "$app_pid" 2>/dev/null || true; app_pid=; }

# 사용: call <METHOD> <path> [X-User-Id] [JSON 본문] [Idempotency-Key]. 응답 본문·상태를 responses.txt에 한 줄로.
# 연결 실패 등 curl 오류는 시나리오 실패(set -e). HTTP 4xx·5xx는 비교 대상이라 실패가 아니다.
call() {
  set -- "$1" "$2" "${3:-}" "${4:-}" "${5:-}"
  local r
  r=$(curl -sS -X "$1" -H 'Content-Type: application/json' ${3:+-H "X-User-Id: $3"} ${5:+-H "Idempotency-Key: $5"} ${4:+-d "$4"} -w ' %{http_code}' "$EQ_BASE$2")
  echo "$1 $2 key=$5 → $r" >> "$out/responses.txt"
}

# 사용: outcome <멱등 키> <APPROVE|DECLINE|TIMEOUT_APPROVED|TIMEOUT_NOT_RECEIVED> — 가짜 PG의 다음 결과를 정한다
outcome() {
  curl -sSf -X POST -H 'Content-Type: application/json' -d "{\"idempotencyKey\":\"$1\",\"outcome\":\"$2\"}" "$EQ_PG/_control/outcomes" > /dev/null
}

# 사용: adv <초> — 앱 시계를 앞으로 옮긴다
adv() {
  local now nxt
  now=$(curl -sS "$EQ_BASE/admin/clock" | sed 's/.*"now":"\([^"]*\)".*/\1/')
  nxt=$(date -d "$now + $1 seconds" --iso-8601=seconds)
  call PUT /admin/clock "" "{\"now\":\"$nxt\"}"
}

# 사용: mark <이름> — 두 로그(Hibernate SQL·DB 문장)에 구간 표식을 남긴다. 추출 후 "== MARK <이름>" 한 줄.
mark() {
  q "SELECT 'MARK $1'" > /dev/null
  echo "### MARK $1" >> "$out/app.log"
}

# 사용: dump <이름> <SQL> — 새 행을 rows/<이름>.txt로. id는 시나리오 시작 시 max(id)를 빼서(오프셋) 쓴다. 0줄이면 실패.
dump() { q "$2" | dump_text "$1"; }
# 사용: … | dump_text <이름> — 표준 입력(여러 쿼리를 묶은 단계 덤프, 앱 로그 건수 등)을 rows/<이름>.txt로. 0줄이면 실패.
dump_text() {
  cat > "$out/rows/$1.txt"
  [ -s "$out/rows/$1.txt" ] || die "$app 덤프 0줄: $1"
}

# 앱이 끝까지 살아 있었는지 확인하고 DB 문장 로그를 받아 추출·정규화한다. done 표식이 없으면 run.sh는 비교하지 않는다.
app_end() {
  kill -0 "$app_pid" 2>/dev/null || die "$app 이 시나리오 중 종료됨: $out/app.log"
  docker logs "$EQ_DB" > "$out/pg.log" 2>&1
  extract_layers "$out"
  touch "$out/done"
}

# ---- 추출 ----

# 추출 ext_hibernate_sql: 기동 완료(": Started ") 뒤 org.hibernate.SQL 레코드의 SQL 본문(재기동하면 두 번째 기동의 SQL도 포함).
# 날짜로 시작하지 않는 줄은 앞 레코드의 이어진 줄(⏎로 잇는다). mark 표식은 "== MARK <이름>".
ext_hibernate_sql() {
  awk '
    function flush() { if (keep) print rec; keep = 0 }
    /^### MARK / { flush(); print "== MARK " substr($0, 10); next }
    /^[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T/ {
      flush()
      if (started && index($0, " org.hibernate.SQL ")) { rec = $0; sub(/.*org\.hibernate\.SQL +: /, "", rec); keep = 1 }
      if (index($0, ": Started ")) started = 1
      next }
    { if (keep) rec = rec " ⏎ " $0 }
    END { flush() }' "$1"
}

# 추출 ext_pg_app_statements: DB 문장 로그 중 앱이 보낸 것(application_name "PostgreSQL JDBC Driver")만, DETAIL(바인드 값) 제외.
# PostgreSQL은 문장 안 줄바꿈을 탭으로 시작하는 이어진 줄로 쓴다 — 앞 레코드에 ⏎로 잇는다(버리지 않는다).
# 하네스(psql)의 mark 문장은 "== MARK <이름>"으로 남기고 나머지 psql 문장(픽스처·덤프)은 뺀다.
ext_pg_app_statements() {
  awk '
    function flush() { if (keep) print rec; keep = 0 }
    /^\t/ { if (keep) rec = rec " ⏎ " substr($0, 2); next }
    { flush()
      if (index($0, "PostgreSQL JDBC Driver|") == 1) { rec = substr($0, 24); keep = (rec !~ /^DETAIL:/) }
      else if (index($0, "psql|LOG:  statement: SELECT '\''MARK ") == 1) { m = substr($0, 36); sub(/'\''.*/, "", m); print "== MARK " m } }
    END { flush() }' "$1"
}

# ---- 정규화 규칙 (이름 붙은 함수로만. 규칙 밖의 차이는 전부 실패) ----

# 규칙 norm_drop_pool_probe: 빈 문장("LOG:  statement: " 뒤가 비었음)을 지운다. pgjdbc Connection.isValid가 보내는 빈 질의로,
# HikariCP가 빌려줄 때 유휴 500ms를 넘긴 연결을 검사하거나 health가 DB를 확인할 때 나간다 — 요청 사이 간격(부하)에 따라 수가 바뀐다.
# 앱 로직이 보내는 문장은 비어 있을 수 없어 이 규칙이 실제 차이를 가리지 않는다. (명세 §2 재합의 1)
norm_drop_pool_probe() { awk '$0 != "LOG:  statement: "'; }

# 규칙 norm_pg_tx_id: 덤프 SQL에 넣는 식. 가짜 PG가 매번 새로 만드는 pg_tx_id 값 대신 있음/없음만 비교한다.
norm_pg_tx_id() { echo "pg_tx_id IS NOT NULL"; }

# 규칙 norm_poll_fold <시작 표식> <끝 표식>: 두 mark 사이에서만, 같은 블록(1~16줄)이 연달아 2번 이상 나오면 한 번 + "⟳ 반복 접음"으로 바꾼다.
# 같은 키 진 요청의 대기(50ms마다 스냅샷 읽기, 대기 상한까지)는 반복 횟수가 시간에 따라 달라진다. 블록의 내용과 순서는 그대로 비교된다.
# 블록 길이는 덮는 줄 수가 가장 긴 것(같으면 짧은 것)을 고른다.
norm_poll_fold() {
  awk -v s="== MARK $1" -v e="== MARK $2" '
    function fold(   i, L, r, k, best, bestL, cover, same) {
      i = 1
      while (i <= n) {
        best = 0; bestL = 0
        for (L = 1; L <= 16 && i + 2 * L - 1 <= n; L++) {
          r = 1
          while (1) {
            same = (i + (r + 1) * L - 1 <= n)
            for (k = 0; same && k < L; k++) if (buf[i + r * L + k] != buf[i + k]) same = 0
            if (!same) break
            r++
          }
          if (r >= 2 && r * L > best) { best = r * L; bestL = L }
        }
        if (bestL) { for (k = 0; k < bestL; k++) print buf[i + k]; print "⟳ 반복 접음(" bestL "줄 블록)"; i += best }
        else { print buf[i]; i++ }
      }
      n = 0
    }
    $0 == s { print; inr = 1; n = 0; next }
    $0 == e && inr { fold(); print; inr = 0; next }
    inr { buf[++n] = $0; next }
    { print }
    END { if (inr) fold() }'
}

# 규칙 norm_set_from_mark <표식>: 표식 앞은 그대로(줄 단위 비교), 표식 뒤는 중복을 뺀 정렬 문장 집합(sort -u)으로 바꾼다.
# 표식 줄은 "== MARK <이름> (이후 집합 비교: 순서·횟수 무시)". 동시 구간은 두 요청의 SQL이 섞이고 대기 폴링·충돌 재시도
# 횟수가 실행마다 달라 순서·횟수는 비교할 수 없지만, 어느 한쪽에만 있는 문장 종류는 잡는다(명세 §2 재합의 3).
# 그 구간의 결과는 응답 분포(norm_conc_distribution)·행 집계로도 비교한다.
# 집합에 넣기 전에 DB 문장 로그의 준비 문장 이름(`execute S_<n>:`·`execute <unnamed>:`)을 `execute:`로 바꾼다. pgjdbc가 연결마다
# 사용 횟수(prepareThreshold)에 따라 이름을 붙여서, 어느 요청이 어느 연결을 썼는지에 따라 이름이 실행마다 달라진다(실측 —
# 본문은 같고 이름만 다른 줄이 god·layered 사이에 생겼다). 표식 앞(직렬 구간)은 이름을 그대로 둔다(명세 §2 재합의 4).
# Hibernate SQL 층에는 이 이름이 없어 영향이 없다.
norm_set_from_mark() {
  awk -v m="== MARK $1" '
    set { sub(/^LOG:  execute (S_[0-9]+|<unnamed>): /, "LOG:  execute: "); print | "LC_ALL=C sort -u"; next }
    $0 == m { print m " (이후 집합 비교: 순서·횟수 무시)"; fflush(); set = 1; next }
    { print }'
}

# 규칙 norm_conc_distribution: "conc " 로 시작하는 응답 줄(동시 요청 1쌍 = 두 응답 + 키당 주문 수)에서
# 두 응답의 순서를 정렬하고 회차 번호를 빼서 분포(건수)로 접는다. 어느 요청이 이기는지는 실행마다 다르다. 나머지 줄은 그대로.
norm_conc_distribution() {
  awk '
    $1 != "conc" { print; next }
    { a = $4; b = $5; if (a > b) { t = a; a = b; b = t }; c[$1 " " $2 " " a " " b " " $6]++ }
    END { for (k in c) print "분포 " c[k] "회:" k | "sort"; close("sort") }'
}

# 시나리오가 다시 정의하는 정규화 묶음(기본은 그대로). norm_logs는 Hibernate SQL·DB 문장 두 층에, norm_responses는 응답 층에 적용.
norm_logs() { cat; }
norm_responses() { cat; }

# 앱 1회 결과(원본) → <dir>/norm/{responses,rows,sql,pgstmt}/
extract_layers() {
  local d=$1
  rm -rf "$d/norm"; mkdir -p "$d/norm/responses" "$d/norm/rows" "$d/norm/sql" "$d/norm/pgstmt"
  norm_responses < "$d/responses.txt" > "$d/norm/responses/responses.txt"
  cp "$d"/rows/*.txt "$d/norm/rows/"
  ext_hibernate_sql "$d/app.log" | norm_logs > "$d/norm/sql/sql.txt"
  ext_pg_app_statements "$d/pg.log" | norm_drop_pool_probe | norm_logs > "$d/norm/pgstmt/pgstmt.txt"
}

# ---- 비교 ----

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
