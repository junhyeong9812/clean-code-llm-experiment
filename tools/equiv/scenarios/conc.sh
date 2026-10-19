#!/usr/bin/env bash
# 시나리오 conc — 같은 멱등 키로 동시 결제 2요청 × N회(기본 30, CONC_N으로 변경):
#   (a) 같은 구매자·같은 체결 — PG 1초 지연 동안 진 요청이 대기했다가 이긴 주문(200)을 돌려받는다
#   (b) 다른 구매자 — 하나는 201, 다른 하나는 409 IDEMPOTENCY_KEY_REUSED(주문 생성 충돌 재시도 뒤)
# 사용: [CONC_N=…] scenarios/conc.sh <god|layered> <jar> <outdir>  (보통은 tools/equiv/run.sh conc)
# 동시 구간은 어느 요청이 이기는지·SQL이 섞이는 순서가 실행마다 달라, 응답은 분포로(norm_conc_distribution),
# 행은 id·구매자 배정과 무관한 집계로, SQL 두 층은 동시 구간 앞(직렬 픽스처 요청)까지만(norm_cut_from_mark) 비교한다.
source "$(dirname "$0")/../lib.sh"
app_begin "$@"
N=${CONC_N:-30}
norm_logs() { norm_cut_from_mark concurrent; }
norm_responses() { norm_conc_distribution; }
start_db

# 픽스처: 새 사이즈 1개, 사용자 s(판매)·b·c·d(구매)
q "INSERT INTO product_sizes(product_id,size) SELECT min(id),'EXP' FROM products" > /dev/null
for u in s b c d; do q "INSERT INTO users(email,name,grade,created_at) VALUES ('$u@exp','$u','BRONZE','2026-09-01T00:00:00+09:00')" > /dev/null; done
size=$(q "SELECT max(id) FROM product_sizes"); base=$(q "SELECT max(id)-3 FROM users"); S_=$base B_=$((base+1)) C_=$((base+2)) D_=$((base+3))
mo=$(q "SELECT max(id) FROM orders"); mt=$(q "SELECT max(id) FROM trades"); mp=$(q "SELECT max(id) FROM pg_transactions")

start_pg 1000           # TIMEOUT_APPROVED가 1초 뒤 APPROVED로 응답(타임아웃 2초 안) → 이긴 요청이 1초 동안 PAYING
start_app 2000
mk() { call POST /bids $S_ "{\"productSizeId\":$size,\"side\":\"SELL\",\"price\":10000,\"validDays\":30}"; call POST /bids "$1" "{\"productSizeId\":$size,\"side\":\"BUY\",\"price\":10000,\"validDays\":30}"; }
for _ in $(seq "$N"); do mk $B_; done
for _ in $(seq "$N"); do mk $C_; mk $D_; done
mapfile -t TB < <(q "SELECT id FROM trades WHERE id>$mt AND buyer_id=$B_ ORDER BY id")
mapfile -t TC < <(q "SELECT id FROM trades WHERE id>$mt AND buyer_id=$C_ ORDER BY id")
mapfile -t TD < <(q "SELECT id FROM trades WHERE id>$mt AND buyer_id=$D_ ORDER BY id")
[ ${#TB[@]} = "$N" ] && [ ${#TC[@]} = "$N" ] && [ ${#TD[@]} = "$N" ] || die "체결 수가 N=$N 과 다름: ${#TB[@]} ${#TC[@]} ${#TD[@]}"

# 응답 1건 → "<상태 코드>:<status 또는 오류 code>". 본문을 못 받으면(curl 오류) 시나리오 실패.
pay() { # <파일 접두> <사용자> <키> <체결>
  curl -sS -o "$1.body" -w '%{http_code}' -X POST -H 'Content-Type: application/json' -H "X-User-Id: $2" -H "Idempotency-Key: $3" -d "{\"tradeIds\":[$4]}" "$EQ_BASE/orders" > "$1.code"; }
outcome_of() {
  local body code
  body=$(cat "$1.body"); code=$(cat "$1.code")
  if [[ $body =~ \"code\":\"([A-Z_]+)\" ]] || [[ $body =~ \"status\":\"([A-Z_]+)\" ]]; then echo "$code:${BASH_REMATCH[1]}"; else echo "$code:?"; fi
}
pair() { # <a|b> <회차> <키> <사용자1> <체결1> <사용자2> <체결2>
  local r=$out/r/$1$2 p1 p2
  pay "$r-1" "$4" "$3" "$5" & p1=$!; pay "$r-2" "$6" "$3" "$7" & p2=$!
  wait $p1 || die "동시 요청 실패: $r-1"; wait $p2 || die "동시 요청 실패: $r-2"
  echo "conc $1 $2 $(outcome_of "$r-1") $(outcome_of "$r-2") orders=$(q "SELECT count(*) FROM orders WHERE idempotency_key='$3'")" >> "$out/responses.txt"
}
mkdir -p "$out/r"
mark concurrent
for i in $(seq 0 $((N-1))); do outcome "SA$i" TIMEOUT_APPROVED; pair a "$i" "SA$i" $B_ "${TB[$i]}" $B_ "${TB[$i]}"; done
for i in $(seq 0 $((N-1))); do pair b "$i" "SB$i" $C_ "${TC[$i]}" $D_ "${TD[$i]}"; done

{ grep -c '주문 생성 충돌' "$out/app.log" || true; grep -c '예상 못 한 오류' "$out/app.log" || true; } | dump_text log-counts   # 재시도 횟수·예상 못 한 오류
dump order-status "SELECT status, count(*) FROM orders WHERE id>$mo GROUP BY status ORDER BY status"
dump orders-per-key "SELECT left(idempotency_key, 2), count(*), count(DISTINCT idempotency_key), sum(card_amount) FROM orders WHERE id>$mo GROUP BY 1 ORDER BY 1"
dump pg-status "SELECT kind, status, count(*) FROM pg_transactions WHERE id>$mp GROUP BY 1, 2 ORDER BY 1, 2"
dump trade-status "SELECT status, count(*) FROM trades WHERE id>$mt GROUP BY status ORDER BY status"
app_end
