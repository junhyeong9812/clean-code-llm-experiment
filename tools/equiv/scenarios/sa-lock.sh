#!/usr/bin/env bash
# 시나리오 sa-lock — ADR-0007 §19의 사전 잠금 경로 4개:
#   9-1 문의 작성(orderItemId 있음): 주문 → 항목 FOR KEY SHARE 뒤 INSERT
#   9-2 리뷰 작성: 주문 FOR UPDATE 뒤 구매자·판매자 users id 순 FOR KEY SHARE, 그 뒤 INSERT
#   10-1 위시리스트 삭제: 사용자 FOR UPDATE 뒤 다시 찾기(없으면 404)
#   10-2 위시리스트·가격 알림 등록: 사이즈 FOR KEY SHARE(없으면 404)가 사용자 FOR UPDATE보다 먼저
# 사용: [CONC_N=…] scenarios/sa-lock.sh <god|layered> <jar> <outdir>  (보통은 tools/equiv/run.sh sa-lock)
# 두 구간으로 나눈다.
#  - 직렬 구간: 각 경로를 한 번씩 밟는다. SQL 두 층은 줄 단위로 비교하므로 잠금 문장의 순서가 그대로 비교된다. 시나리오 안의 `need`는
#    각 경로의 잠금 문장이 그 순서로 실제로 나갔는지(DB 문장 로그) 확인한다 — 두 앱이 함께 잠금을 빼먹었는데 같음으로 끝나지 않게.
#  - 동시 구간(mark concurrent 뒤): 같은 대상에 동시 2요청 × CONC_N회(기본 5) — 위시 등록(201·409)·위시 삭제(200·404)·
#    가격 알림 등록(201·409)·같은 항목 리뷰(201·409)·같은 항목 문의 2건(201·201, KEY SHARE끼리 막지 않음)·
#    항목 확정 ∥ 그 항목 문의(200·201, 주문 FOR UPDATE가 문의의 KEY SHARE를 막는다). 어느 요청이 먼저 잠그는지·SQL이 섞이는
#    순서가 실행마다 달라, conc와 같은 규칙으로 비교한다: 응답은 쌍마다 두 응답 정렬(norm_conc_pair — 이 시나리오의 쌍에는 체결이 없어
#    trades=-, 응답의 orderItemId는 쌍 안 기호가 된다), SQL 두 층은 표식 뒤 문장 종류 집합(norm_set_from_mark). 쌍의 상태 코드 조합은
#    시나리오 안에서 기대값과 대조한다(교착·UNIQUE 위반이면 400이 섞여 exit 2). 행은 배정 순서와 무관한 집계로 덤프한다.
source "$(dirname "$0")/../lib.sh"
app_begin "$@"
N=${CONC_N:-5}
norm_logs() { norm_set_from_mark concurrent; }
norm_responses() { norm_conc_pair; }
start_db

# 픽스처: 사이즈 1 + N개(동시 위시·알림용), 사용자 s(판매)·b(구매)·c(제3자)
for k in $(seq 0 "$N"); do q "INSERT INTO product_sizes(product_id,size) SELECT min(id),'L$k' FROM products" > /dev/null; done
for u in s b c; do q "INSERT INTO users(email,name,grade,created_at) VALUES ('$u@exp','$u','BRONZE','2026-09-01T00:00:00+09:00')" > /dev/null; done
zmax=$(q "SELECT max(id) FROM product_sizes"); Z0=$((zmax-N)); base=$(q "SELECT max(id)-2 FROM users")
S_=$base B_=$((base+1)) C_=$((base+2))
mo=$(q "SELECT max(id) FROM orders"); mi=$(q "SELECT max(id) FROM order_items"); mt=$(q "SELECT max(id) FROM trades")
mq=$(q "SELECT max(id) FROM inquiries"); mm=$(q "SELECT max(id) FROM inquiry_messages"); mw=$(q "SELECT max(id) FROM reviews")
mwl=$(q "SELECT max(id) FROM wishlists"); ma=$(q "SELECT max(id) FROM price_alerts")

# need <이름> <값> [기대]: 경로를 실제로 지났는지(기대가 없으면 양의 정수)
need() {
  if [ $# -ge 3 ]; then [ "$2" = "$3" ] || die "경로를 지나지 않음: $1 = '$2' (기대 '$3')"
  else [[ $2 =~ ^[0-9]+$ ]] && [ "$2" -gt 0 ] || die "경로를 지나지 않음: $1 = '$2'"; fi
}
# lock_seq <표식> <정규식…>: 지금까지의 DB 문장 로그(앱 문장만)에서 그 표식 구간(다음 표식 전까지)에 정규식들이 이 순서로 나오는지(소문자 비교)
lock_seq() {
  local m=$1; shift
  local r; r=$(docker logs "$EQ_DB" 2>&1 | ext_pg_app_statements /dev/stdin | awk -v m="== MARK $m" -v pats="$(printf '%s\n' "$@")" '
    BEGIN { n = split(pats, p, "\n"); if (p[n] == "") n--; k = 1 }
    $0 == m { f = 1; next }
    f && /^== MARK / { exit }
    f && k <= n && tolower($0) ~ p[k] { k++ }
    END { print (k > n) ? "ok" : "멈춘 곳 " k ": " p[k] }')
  need "잠금 순서 $m" "$r" ok
}
start_pg 3000
start_app 2000
trade() { call POST /bids $S_ "{\"productSizeId\":$Z0,\"side\":\"SELL\",\"price\":$1,\"validDays\":30}"; call POST /bids $B_ "{\"productSizeId\":$Z0,\"side\":\"BUY\",\"price\":$1,\"validDays\":30}"; }
# order <키> <항목 수>: 새 체결 n건을 한 주문으로 결제하고 항목 id를 공백으로 출력
order() {
  local from t k
  from=$(q "SELECT max(id) FROM trades")
  for k in $(seq "$2"); do trade $((10000 + k * 100)); done
  t=$(q "SELECT string_agg(id::text, ',' ORDER BY id) FROM trades WHERE id>$from")
  call POST /orders $B_ "{\"tradeIds\":[$t]}" "$1"
  q "SELECT string_agg(i.id::text, ' ' ORDER BY i.id) FROM order_items i JOIN orders o ON o.id=i.order_id WHERE o.id>$mo AND o.idempotency_key='$1'"
}
to_delivered() { call POST "/order-items/$1/shipment" $S_; call POST "/admin/order-items/$1/receipt"; call POST "/admin/order-items/$1/inspection" "" '{"result":"PASS"}'; call POST "/admin/order-items/$1/delivery"; }

mark fixtures
read -r -a SER <<< "$(order K-SER 2)"; [ ${#SER[@]} = 2 ] || die "직렬 항목 준비 실패: ${SER[*]}"
for x in "${SER[@]}"; do to_delivered "$x"; call POST "/order-items/$x/confirm" $B_; done
read -r -a REV <<< "$(order K-REV "$N")"; read -r -a INQ <<< "$(order K-INQ "$N")"; read -r -a CNF <<< "$(order K-CNF "$N")"
[ ${#REV[@]} = "$N" ] && [ ${#INQ[@]} = "$N" ] && [ ${#CNF[@]} = "$N" ] || die "동시 항목 준비 실패: ${#REV[@]} ${#INQ[@]} ${#CNF[@]}"
for x in "${REV[@]}"; do to_delivered "$x"; call POST "/order-items/$x/confirm" $B_; done   # 리뷰 대상: CONFIRMED
for x in "${CNF[@]}"; do to_delivered "$x"; done                                          # 확정 ∥ 문의 대상: DELIVERED

mark lock-9-1
call POST /me/inquiries $B_ "{\"category\":\"ORDER\",\"title\":\"t\",\"body\":\"b\",\"orderItemId\":${SER[0]}}"
lock_seq lock-9-1 'from orders where id = .* for key share' 'from order_items where id = .* for key share' 'insert into inquiries'
mark lock-9-1-missing
call POST /me/inquiries $B_ '{"category":"ORDER","title":"t","body":"b","orderItemId":999999999}'   # 404(주문을 찾지 못함)
mark lock-9-2
call POST "/order-items/${SER[0]}/review" $B_ '{"rating":5,"content":"좋다"}'
lock_seq lock-9-2 'from orders where id = .* for update' 'from users where id in .* order by id for key share' 'insert into reviews'
mark lock-10-2-wish
call POST /me/wishlist $B_ "{\"productSizeId\":$Z0}"
lock_seq lock-10-2-wish 'from product_sizes where id = .* for key share' 'from users where id = .* for update' 'insert into wishlists'
mark lock-10-2-alert
call POST /me/price-alerts $B_ "{\"productSizeId\":$Z0,\"targetPrice\":1000}"
lock_seq lock-10-2-alert 'from product_sizes where id = .* for key share' 'from users where id = .* for update' 'insert into price_alerts'
mark lock-10-2-missing
call POST /me/wishlist $B_ '{"productSizeId":999999999}'       # 사이즈 KEY SHARE가 비어 404 — 사용자 잠금 전
call POST /me/price-alerts $B_ '{"productSizeId":999999999,"targetPrice":1000}'
need "없는 사이즈는 사용자를 잠그지 않음" "$(docker logs "$EQ_DB" 2>&1 | ext_pg_app_statements /dev/stdin | awk '$0 == "== MARK lock-10-2-missing" { f = 1; next } f && tolower($0) ~ /from users where id = .* for update/ { n++ } END { print n + 0 }')" 0
lock_seq lock-10-2-missing 'from product_sizes where id = .* for key share' 'rollback' 'from product_sizes where id = .* for key share' 'rollback'
mark lock-10-1
call DELETE "/me/wishlist/$Z0" $B_                             # 200
call DELETE "/me/wishlist/$Z0" $B_                             # 다시 찾기 → 404
lock_seq lock-10-1 'from users where id = .* for update' 'delete from wishlists' 'commit' 'from users where id = .* for update' 'rollback'

# ---- 동시 구간 ----
# 응답 1건을 <파일 접두>.code·.body로 받는다. 본문을 못 받으면(curl 오류) 시나리오 실패.
req() { # <파일 접두> <메서드> <경로> <사용자> [본문]
  curl -sS --max-time "$EQ_CURL_MAX" -o "$1.body" -w '%{http_code}' -X "$2" -H 'Content-Type: application/json' -H "X-User-Id: $4" ${5:+-d "$5"} "$EQ_BASE$3" > "$1.code"; }
# pair <종류> <회차> <기대 코드 조합(정렬)> <메서드1> <경로1> <사용자1> <본문1> <메서드2> <경로2> <사용자2> <본문2>
# 한 쌍 = responses.txt 한 줄(conc 형식 — norm_conc_pair가 두 응답을 정렬한다)
pair() {
  local r=$out/r/$1-$2 p1 p2 codes
  req "$r-1" "$4" "$5" "$6" "$7" & p1=$!; req "$r-2" "$8" "$9" "${10}" "${11}" & p2=$!
  wait $p1 || die "동시 요청 실패: $r-1"; wait $p2 || die "동시 요청 실패: $r-2"
  codes=$(printf '%s\n' "$(cat "$r-1.code")" "$(cat "$r-2.code")" | sort | paste -sd ' ')
  [ "$codes" = "$3" ] || die "동시 쌍 $1 $2: 상태 코드 $codes (기대 $3) — $(cat "$r-1.body") / $(cat "$r-2.body")"
  printf 'conc %s %s orders=- trades=-\t%s %s\t%s %s\n' "$1" "$2" "$(cat "$r-1.code")" "$(cat "$r-1.body")" "$(cat "$r-2.code")" "$(cat "$r-2.body")" >> "$out/responses.txt"
}
mkdir -p "$out/r"
mark concurrent
for i in $(seq 0 $((N-1))); do
  z=$((Z0 + 1 + i))
  pair wish-add "$i" "201 409" POST /me/wishlist $B_ "{\"productSizeId\":$z}" POST /me/wishlist $B_ "{\"productSizeId\":$z}"
  pair wish-del "$i" "200 404" DELETE "/me/wishlist/$z" $B_ "" DELETE "/me/wishlist/$z" $B_ ""
  pair alert-add "$i" "201 409" POST /me/price-alerts $B_ "{\"productSizeId\":$z,\"targetPrice\":1000}" POST /me/price-alerts $B_ "{\"productSizeId\":$z,\"targetPrice\":1000}"
  pair review "$i" "201 409" POST "/order-items/${REV[$i]}/review" $B_ '{"rating":4,"content":"동시"}' POST "/order-items/${REV[$i]}/review" $B_ '{"rating":4,"content":"동시"}'
  pair inquiry-2 "$i" "201 201" POST /me/inquiries $B_ "{\"category\":\"ORDER\",\"title\":\"t\",\"body\":\"b\",\"orderItemId\":${INQ[$i]}}" POST /me/inquiries $S_ "{\"category\":\"ORDER\",\"title\":\"t\",\"body\":\"b\",\"orderItemId\":${INQ[$i]}}"
  pair confirm-inquiry "$i" "200 201" POST "/order-items/${CNF[$i]}/confirm" $B_ "" POST /me/inquiries $B_ "{\"category\":\"ORDER\",\"title\":\"t\",\"body\":\"b\",\"orderItemId\":${CNF[$i]}}"
done

{ docker logs "$EQ_DB" 2>&1 | grep -c 'deadlock detected' || true; grep -c '예상 못 한 오류' "$out/app.log" || true; } | dump_text log-counts   # 교착·예상 못 한 오류
dump inquiries "SELECT user_id-$base, order_item_id IS NOT NULL, status, count(*) FROM inquiries WHERE id>$mq GROUP BY 1, 2, 3 ORDER BY 1, 2, 3"
dump inquiry_messages "SELECT author, body, count(*) FROM inquiry_messages WHERE id>$mm GROUP BY 1, 2 ORDER BY 1, 2"
dump reviews "SELECT order_item_id-$mi, buyer_id-$base, seller_id-$base, rating, content, status FROM reviews WHERE id>$mw ORDER BY order_item_id"
dump wishlists "SELECT count(*) FROM wishlists WHERE id>$mwl"
dump price_alerts "SELECT product_size_id-$Z0, user_id-$base, target_price, status FROM price_alerts WHERE id>$ma ORDER BY product_size_id"
dump order_items "SELECT id-$mi, status FROM order_items WHERE id>$mi ORDER BY id"
app_end
