#!/usr/bin/env bash
# 배치 8종·포인트 예약 소요 시간 측정(판정 없음). 실행 1회 = 새 시드 DB + 앱 1개에서
#   평상시(시계 2026-10-02T00:00+09:00) 배치 8종 → 몰아 처리(2027-01-14T06:34:02+09:00) 배치 8종 → 포인트 예약 1건.
# 배치 순서: bid-expiry → payment-due → pg-inquiry → shipment-deadline → auto-confirm → point-expiry → grade-recalc → weekly-settlement.
# 0회차 = god 1회, 각 배치 직전에 대상 조회(키셋 첫 페이지)·정산 대상 조회를 앱 밖 psql로 EXPLAIN (ANALYZE, BUFFERS) 한다.
#   EXPLAIN이 캐시를 데우므로 0회차 시간은 비교에 쓰지 않는다(예열 겸용). 그 뒤 1..N회차 god·layered 교대(기본 N=2).
# DB 문장 로그·Hibernate SQL 로그는 끈다. 요청 상한은 3600초(120초 초과는 표에 표시만).
# 실패(시계 설정·비 200/201 응답·curl 오류·여유 디스크 10GB 미만)는 그 칸에 사유를 쓰고, 그 실행의 남은 칸은 "미실행(앞 단계 실패)"으로
#   채운 뒤 다음 실행으로 넘어간다(다음 실행은 새 DB). 하나라도 실패하면 끝에 exit 2.
# 사용: tools/equiv/measure-batches.sh [N]      시드 태그 MEASURE_SEED(기본 500k-s42), GOD_JAR·LAYERED_JAR로 jar 지정
# 출력: build/equiv/measure-batches/<시작 시각>/results.tsv(+ latest 링크), <회차>-<앱>/explain/*.txt, <회차>-<앱>/app.log
# 종료 코드: 0 측정 완료 · 2 실행 실패(일부 칸 실패 또는 교차 확인 불일치 포함) · 130 중단
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
source "$here/lib.sh"
n=${1:-2}
[[ $n =~ ^[1-9][0-9]*$ ]] || die "사용: tools/equiv/measure-batches.sh [N]"
export EQ_SEED_TAG=${MEASURE_SEED:-500k-s42}
EQ_CURL_MAX=3600
MIN_FREE_GB=${MEASURE_MIN_FREE_GB:-10}   # 이 아래면 멈춘다(실패 경로 확인용으로만 바꾼다)
BATCHES=(bid-expiry payment-due pg-inquiry shipment-deadline auto-confirm point-expiry grade-recalc weekly-settlement)
NORMAL=2026-10-02T00:00:00+09:00 CATCHUP=2027-01-14T06:34:02+09:00
STEPS=()
for b in "${BATCHES[@]}"; do STEPS+=("normal $b"); done
for b in "${BATCHES[@]}"; do STEPS+=("catchup $b"); done
STEPS+=("catchup point-reserve")

entry_begin
prepare_jars :behavior-tests:fakePgClasspath

mkdir -p "$root/build/equiv/measure-batches"
base=$(mktemp -d "$root/build/equiv/measure-batches/$(date +%Y%m%dT%H%M%S)-XXXX"); ln -sfn "$base" "$root/build/equiv/measure-batches/latest"
printf 'run\tapp\tphase\tbatch\thttp\tprocessed\tfailed\tcurl_s\tapp_ms\tover120\tcheck\n' > "$base/results.tsv"

free_gb() { df -Pk "$(docker info -f '{{.DockerRootDir}}')" | awk 'NR==2{print int($4/1024/1024)}'; }
over120() { awk -v s="$1" 'BEGIN{print (s > 120) ? "Y" : "-"}'; }
iso_minus() { date -d "$1 -$2 days" --iso-8601=seconds; }   # <ISO> <일> — 앱이 넘기는 Instant 파라미터와 같은 값

# ---- 표 기록·실패 ----
step=0   # 지금 칸(STEPS 인덱스)
row() { # <회차> <앱> <http> <processed> <failed> <curl_s> <app_ms> <over120> <check> — 지금 칸을 쓰고 다음 칸으로
  local s=${STEPS[$step]}
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "${s% *}" "${s#* }" "$3" "$4" "$5" "$6" "$7" "$8" "$9" | tee -a "$base/results.tsv"
  step=$((step + 1))
}
fail() { # <회차> <앱> <사유> — 지금 칸에 사유, 남은 칸은 미실행. 하위 셸을 끝낸다(정리는 app_cleanup)
  local reason=$3
  [ -s "$out/disk-low" ] && reason+=" / 여유 디스크 $(cat "$out/disk-low")GB < ${MIN_FREE_GB}GB — 감시가 앱을 멈춤"
  [ "$step" -lt "${#STEPS[@]}" ] && row "$1" "$2" "실패" "" "" "" "" "" "$reason"
  while [ "$step" -lt "${#STEPS[@]}" ]; do row "$1" "$2" "미실행(앞 단계 실패)" "" "" "" "" "" ""; done
  die "$2 $1회차: $reason"
}
disk_guard() { local f; f=$(free_gb); [ "$f" -ge "$MIN_FREE_GB" ] || fail "$1" "$2" "여유 디스크 ${f}GB < ${MIN_FREE_GB}GB — 측정 중단"; }

# ---- 대상 조건·EXPLAIN ----
# 배치별 대상 조건(별칭 t) — god Marketplace.kt·layered KeysetBatchTargets.kt와 같은 식. 시각 파라미터는 리터럴로 넣는다.
target_sql() { # <배치> <현재 ISO>
  local now=$2 d from to
  case $1 in
    bid-expiry) echo "bids|t.status = 'ACTIVE' AND t.expires_at <= '$now'" ;;
    payment-due) echo "trades|t.status = 'AWAITING_PAYMENT' AND t.payment_due_at <= '$now' AND NOT EXISTS (SELECT 1 FROM order_items i JOIN orders o ON o.id = i.order_id WHERE i.trade_id = t.id AND i.status <> 'PAYMENT_FAILED' AND o.status IN ('UNKNOWN', 'PAYING'))" ;;
    pg-inquiry) echo "orders|t.status = 'UNKNOWN'" ;;
    shipment-deadline) echo "order_items|t.status = 'PAID' AND t.paid_at <= '$(iso_minus "$now" 3)'" ;;
    auto-confirm) echo "order_items|t.status = 'DELIVERED' AND t.delivered_at <= '$(iso_minus "$now" 7)'" ;;
    point-expiry) echo "point_ledger|t.kind = 'EARN' AND t.status = 'AVAILABLE' AND t.remaining > 0 AND t.expires_at <= '$now'" ;;
    grade-recalc)
      d=$(TZ=Asia/Seoul date -d "$now" +%Y-%m-01)
      from="$(date -d "$d -6 months" +%Y-%m-%d)T00:00:00+09:00" to="${d}T00:00:00+09:00"
      local basis="(SELECT coalesce(sum(i.price), 0) FROM order_items i JOIN orders o ON o.id = i.order_id WHERE o.buyer_id = t.id AND i.status IN ('CONFIRMED', 'DISPUTED') AND i.confirmed_at >= '$from' AND i.confirmed_at < '$to')"
      local grade="(SELECT CASE WHEN b.v >= 5000000 THEN 'VIP' WHEN b.v >= 2000000 THEN 'GOLD' WHEN b.v >= 500000 THEN 'SILVER' ELSE 'BRONZE' END FROM (SELECT $basis AS v) b)"
      echo "users|NOT EXISTS (SELECT 1 FROM user_grade_history h WHERE h.user_id = t.id AND h.effective_from = '$d') AND t.grade <> $grade" ;;
  esac
}

# 정산(§10.1): 직전 월요일(KST) = 마지막 정산할 마감. 첫 미정산 주 마감 = 정산 행이 있으면 max(period_start) + 14일, 없으면 직전 월요일
latest_monday() { local day; day=$(TZ=Asia/Seoul date -d "$1" +%Y-%m-%d); date -d "$day -$(( ($(date -d "$day" +%u) + 6) % 7 )) days" +%Y-%m-%d; }
first_cutoff() { local last; last=$(q "SELECT max(period_start) FROM settlements"); if [ -n "$last" ]; then date -d "$last +14 days" +%Y-%m-%d; else latest_monday "$1"; fi; }
settle_target() { # <마감 ISO> — god settlementTargetSql(별칭 i)
  echo "(i.status IN ('CONFIRMED', 'DISPUTED') AND i.confirmed_at < '$1' AND NOT EXISTS (SELECT 1 FROM settlement_items s WHERE s.order_item_id = i.id AND s.status = 'INCLUDED')) OR (i.status = 'REFUNDED_AFTER_CONFIRM' AND i.cancelled_at < '$1' AND (SELECT count(*) FILTER (WHERE s.status = 'INCLUDED') - count(*) FILTER (WHERE s.status = 'CLAWBACK') FROM settlement_items s WHERE s.order_item_id = i.id) > 0)"
}
explain_tx() { # 표준 입력의 EXPLAIN을 롤백할 트랜잭션 안에서(FOR UPDATE의 행 잠금이 남지 않게)
  { echo "BEGIN;"; cat; echo "ROLLBACK;"; } | docker exec -i "$EQ_DB" psql -U ccle -d ccle_seed -Atq -v ON_ERROR_STOP=1
}

explain() { # <배치> <현재 ISO> <파일> — 앱 밖. 키셋 첫 페이지(시작 시 max(id)까지) / 정산은 첫 미정산 주의 잠금 조회·대상 조회
  local spec table cond upto cutoff where
  if [ "$1" = weekly-settlement ]; then
    cutoff=$(first_cutoff "$2")
    if [[ $cutoff > $(latest_monday "$2") ]]; then echo "-- 정산할 주 없음(첫 미정산 마감 $cutoff > 직전 월요일 — 앱은 대상 조회를 하지 않는다)" > "$3"; return; fi
    where=$(settle_target "${cutoff}T00:00:00+09:00")
    { echo "-- 첫 미정산 주 마감 ${cutoff}T00:00:00+09:00 · 잠금 조회(앱과 같은 FOR UPDATE)"
      explain_tx <<< "EXPLAIN (ANALYZE, BUFFERS) SELECT i.id FROM order_items i WHERE $where ORDER BY i.id FOR UPDATE;"
      echo "-- 대상 조회(체결·카테고리 조인)"
      q "EXPLAIN (ANALYZE, BUFFERS) SELECT i.id, t.seller_id, i.status, i.price, CAST(floor(i.price * c.sale_fee_rate) AS bigint) FROM order_items i JOIN trades t ON t.id = i.trade_id JOIN product_sizes ps ON ps.id = t.product_size_id JOIN products p ON p.id = ps.product_id JOIN categories c ON c.id = p.category_id WHERE $where ORDER BY i.id"
    } > "$3"
    return
  fi
  spec=$(target_sql "$1" "$2"); table=${spec%%|*} cond=${spec#*|}
  upto=$(q "SELECT coalesce(max(id), 0) FROM $table")
  q "EXPLAIN (ANALYZE, BUFFERS) SELECT t.id FROM $table t WHERE ($cond) AND t.id > 0 AND t.id <= $upto ORDER BY t.id LIMIT 500" > "$3"
}

# 처리 건수 교차 확인(DB 행): bid-expiry = 그 시각 ACTIVE → EXPIRED 이벤트 수, weekly-settlement = 새 정산 행 수. 나머지는 "-"
check() { # <배치> <현재 ISO> <processed> <정산 행 수(전)>
  local got
  case $1 in
    bid-expiry) got=$(q "SELECT count(*) FROM bid_events WHERE from_status = 'ACTIVE' AND to_status = 'EXPIRED' AND created_at = '$2'") ;;
    weekly-settlement) got=$(( $(q "SELECT count(*) FROM settlements") - $4 )) ;;
    *) echo -; return ;;
  esac
  [ "$got" = "$3" ] && echo "ok($got)" || echo "MISMATCH(db=$got)"
}

# ---- 실행 ----
set_clock() { # <회차> <앱> <ISO> — 설정 응답과 GET 결과가 그 시각이어야 한다
  local r got
  r=$(curl -sS --max-time 30 -o /dev/null -w '%{http_code}' -X PUT -H 'Content-Type: application/json' -d "{\"now\":\"$3\"}" "$EQ_BASE/admin/clock") || r=curl
  got=$(curl -sS --max-time 30 "$EQ_BASE/admin/clock" | sed -n 's/.*"now":"\([^"]*\)".*/\1/p') || got=
  [ "$r" = 200 ] && [ -n "$got" ] && [ "$(date -d "$got" +%s)" = "$(date -d "$3" +%s)" ] || fail "$1" "$2" "시계 설정 실패(PUT $r, 현재 '$got', 목표 $3)"
}

run_phase() { # <회차> <앱> <단계> <현재 ISO> <EXPLAIN 여부>
  local b t body http processed failed ms chk before l0
  set_clock "$1" "$2" "$4"
  for b in "${BATCHES[@]}"; do
    disk_guard "$1" "$2"
    [ "$5" = 1 ] && explain "$b" "$4" "$out/explain/$3-$b.txt"
    before=$(q "SELECT count(*) FROM settlements"); l0=$(wc -l < "$out/app.log")
    t=$(curl -sS --max-time "$EQ_CURL_MAX" -o "$out/body.txt" -w '%{http_code} %{time_total}' -X POST "$EQ_BASE/admin/batches/$b") \
      || fail "$1" "$2" "curl 오류(상한 ${EQ_CURL_MAX}초 또는 연결 끊김)"
    http=${t%% *} t=${t#* }; body=$(cat "$out/body.txt")
    processed=$(sed -n 's/.*"processed":\([0-9]*\).*/\1/p' <<< "$body"); failed=$(sed -n 's/.*"failed":\([0-9]*\).*/\1/p' <<< "$body")
    # 이 요청 뒤에 생긴 로그 줄에서만(실패하면 앱이 배치 로그를 남기지 않으므로 앞 단계 줄을 집지 않게)
    ms=$({ tail -n +"$((l0 + 1))" "$out/app.log" | grep -h "배치 $b (현재" || true; } | tail -1 | sed -n 's/.*, \([0-9]*\)ms$/\1/p')
    if [ "$http" != 200 ] || [ -z "$processed" ] || [ -z "$failed" ]; then
      fail "$1" "$2" "응답 $http(${t}초): $(head -c 200 <<< "$body")"
    fi
    chk=$(check "$b" "$4" "$processed" "$before")
    row "$1" "$2" "$http" "$processed" "$failed" "$t" "${ms:-?}" "$(over120 "$t")" "$chk"
    [[ $chk != MISMATCH* ]] || echo "$1-$2 $b $chk" >> "$base/mismatch"   # 측정은 계속하되 끝에 exit 2
  done
}

# 포인트 예약(§5.2): 사용 가능 EARN 묶음이 가장 많은 구매 가능 사용자로 새 사이즈 체결 1건 → POST /orders(pointToUse = 사용 가능액, 10의 배수)
reserve() { # <회차> <앱> <현재 ISO>
  local now=$3 row u bundles usable s size trade use t http alloc
  disk_guard "$1" "$2"
  row=$(q "SELECT l.user_id, count(*), sum(l.remaining) FROM point_ledger l JOIN users u ON u.id = l.user_id
           WHERE l.kind = 'EARN' AND l.status = 'AVAILABLE' AND l.remaining > 0 AND l.expires_at > '$now'
             AND (u.purchase_restricted_until IS NULL OR u.purchase_restricted_until <= '$now')
           GROUP BY l.user_id HAVING sum(l.remaining) >= 1000 ORDER BY count(*) DESC, l.user_id LIMIT 1")
  IFS='|' read -r u bundles usable <<< "$row"
  [[ $u =~ ^[0-9]+$ ]] || fail "$1" "$2" "예약 대상 사용자 없음"
  s=$(q "SELECT min(id) FROM users WHERE id <> $u AND (sale_restricted_until IS NULL OR sale_restricted_until <= '$now')")
  q "INSERT INTO product_sizes(product_id,size) SELECT min(id),'MEASURE' FROM products" > /dev/null
  size=$(q "SELECT max(id) FROM product_sizes")
  call POST /bids "$s" "{\"productSizeId\":$size,\"side\":\"SELL\",\"price\":$(( usable * 2 + 100000 )),\"validDays\":30}"
  call POST /bids "$u" "{\"productSizeId\":$size,\"side\":\"BUY\",\"price\":$(( usable * 2 + 100000 )),\"validDays\":30}"
  trade=$(q "SELECT max(id) FROM trades WHERE product_size_id = $size")
  [[ $trade =~ ^[0-9]+$ ]] || fail "$1" "$2" "예약용 체결이 생기지 않음: $(tail -2 "$out/responses.txt" | tr '\n' ' ')"
  use=$(( usable / 10 * 10 ))
  t=$(curl -sS --max-time "$EQ_CURL_MAX" -o "$out/body.txt" -w '%{http_code} %{time_total}' -X POST -H 'Content-Type: application/json' \
      -H "X-User-Id: $u" -H "Idempotency-Key: measure-$1-$2" -d "{\"tradeIds\":[$trade],\"pointToUse\":$use}" "$EQ_BASE/orders") \
    || fail "$1" "$2" "curl 오류(상한 ${EQ_CURL_MAX}초 또는 연결 끊김)"
  http=${t%% *} t=${t#* }
  [ "$http" = 201 ] || fail "$1" "$2" "응답 $http(${t}초): $(head -c 200 "$out/body.txt")"
  alloc=$(q "SELECT count(*) FROM point_allocations a JOIN point_ledger l ON l.id = a.entry_id WHERE l.order_id = (SELECT id FROM orders WHERE idempotency_key = 'measure-$1-$2')")
  row "$1" "$2" "$http" "" "" "$t" "-" "$(over120 "$t")" "묶음 ${bundles}개·사용 ${use}원·배분 ${alloc}행"
}

# 실행 하위 셸의 EXIT: 칸을 다 쓰기 전에 끝났으면(기동 실패·예상 못 한 명령 실패) 남은 칸을 채운 뒤 감시를 멈추고 app_cleanup에 원래 코드를 넘긴다
on_exit() {
  local rc=$?
  set +e   # 정리 중에는 errexit를 끈다(아래 `( exit "$rc" )`가 셸을 끝내 app_cleanup을 건너뛰지 않게)
  if [ "$step" -lt "${#STEPS[@]}" ]; then
    row "$run" "$app" "실패" "" "" "" "" "" "실행 중 종료(rc=$rc) — $out/app.log·stderr"
    while [ "$step" -lt "${#STEPS[@]}" ]; do row "$run" "$app" "미실행(앞 단계 실패)" "" "" "" "" "" ""; done
    [ "$rc" -ne 0 ] || rc=2
  fi
  [ -z "${watch_pid:-}" ] || { kill "$watch_pid" 2>/dev/null || true; wait "$watch_pid" 2>/dev/null || true; }
  ( exit "$rc" ); app_cleanup
}

one() { # <회차> <앱> <EXPLAIN 여부> — 하위 셸에서 돈다(정리는 on_exit → app_cleanup)
  app_begin "$2" "${jar[$2]}" "$base/$1-$2"
  run=$1 watch_pid=; trap on_exit EXIT
  mkdir -p "$out/explain"; : > "$out/app.log"
  local s0; s0=$(date +%s)
  disk_guard "$1" "$2"
  EQ_PG_LOG=0 start_db
  echo "[$1-$2] DB 준비 $(( $(date +%s) - s0 ))초, 여유 $(free_gb)GB"
  start_pg 3000
  EQ_SQL_LOG=0 start_app 2000
  # 디스크 감시: 요청이 길어도 30초마다 확인, 10GB 아래면 앱을 멈춰 그 요청을 실패로 끝낸다(fail이 사유를 붙인다)
  ( while kill -0 "$app_pid" 2>/dev/null; do f=$(free_gb) || f=0; if [ "$f" -lt "$MIN_FREE_GB" ]; then echo "$f" > "$out/disk-low"; kill "$app_pid"; break; fi; sleep 30 & wait $!; done ) &
  watch_pid=$!
  run_phase "$1" "$2" normal "$NORMAL" "$3"
  run_phase "$1" "$2" catchup "$CATCHUP" "$3"
  reserve "$1" "$2" "$CATCHUP"
  echo "[$1-$2] 끝 $(( $(date +%s) - s0 ))초, 여유 $(free_gb)GB"
  kill -0 "$app_pid" 2>/dev/null || die "$2 이 측정 중 종료됨: $out/app.log"
}
# 하위 셸을 `||` 왼쪽에 두면 그 안의 errexit가 꺼지므로, 백그라운드로 띄우고 wait의 종료 코드만 본다
# 백그라운드 하위 셸은 SIGINT를 무시하므로, 중단되면 TERM을 보내 그 실행의 정리(on_exit)를 기다린 뒤 끝낸다
bad=0 child=
stop_child() { [ -z "$child" ] || { kill -TERM "$child" 2>/dev/null || true; wait "$child" 2>/dev/null || true; }; }
trap 'stop_child; exit 130' INT; trap 'stop_child; exit 143' TERM
measure() { (one "$@") & child=$!; wait "$child" || bad=1; child=; }
measure 0 god 1
for i in $(seq "$n"); do
  for app in god layered; do measure "$i" "$app" 0; done
done
echo "결과: $base/results.tsv"
[ "$bad" = 0 ] || die "실패한 실행이 있음 — results.tsv의 실패·미실행 칸"
[ ! -s "$base/mismatch" ] || die "처리 건수 교차 확인 불일치: $(tr '\n' ' ' < "$base/mismatch")"
