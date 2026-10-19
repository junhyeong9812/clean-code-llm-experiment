#!/usr/bin/env bash
# 시나리오 run — 입찰·체결·취소(지정가 교차·즉시 구매/판매·자기 입찰 건너뜀·취소 409·호가·체결 목록). PG 호출 없음.
# 사용: scenarios/run.sh <god|layered> <jar> <outdir>  (보통은 tools/equiv/run.sh run)
source "$(dirname "$0")/../lib.sh"
app_begin "$@"
start_db

# 픽스처: 새 사이즈 1개, 사용자 7명(a…g)
q "INSERT INTO product_sizes(product_id,size) SELECT min(id),'EXP' FROM products" > /dev/null
for u in a b c d e f g; do q "INSERT INTO users(email,name,grade,created_at) VALUES ('$u@exp','$u','BRONZE','2026-09-01T00:00:00+09:00')" > /dev/null; done
size=$(q "SELECT max(id) FROM product_sizes"); base=$(q "SELECT max(id)-6 FROM users")
mb=$(q "SELECT max(id) FROM bids"); me=$(q "SELECT max(id) FROM bid_events"); mt=$(q "SELECT coalesce(max(id),0) FROM trades")

start_app
U() { echo $((base + $1)); }   # 0=a … 6=g
bid() { call POST /bids "$(U "$1")" "{\"productSizeId\":$size,\"side\":\"$2\",\"price\":$3,\"validDays\":30}"; }
inst() { call POST /bids "$(U "$1")" "{\"productSizeId\":$size,\"side\":\"$2\",\"instant\":true}"; }
bid 0 SELL 100000          # a ACTIVE
bid 1 SELL 95000           # b ACTIVE
adv 60
bid 2 BUY 90000            # c ACTIVE(교차 없음)
bid 3 BUY 96000            # d → b와 체결 95000
c_bid=$(q "SELECT id FROM bids WHERE user_id=$(U 2)")
call DELETE "/bids/$c_bid" "$(U 2)"   # c 취소
call DELETE "/bids/$c_bid" "$(U 2)"   # 409
inst 3 BUY                 # d 즉시 구매 → a 100000
inst 4 SELL                # 상대 없음 409
adv 60
bid 0 SELL 80000           # a ACTIVE
bid 4 BUY 85000            # e → a와 체결 80000(판매가 앞섬)
bid 5 SELL 70000           # f ACTIVE
bid 5 BUY 75000            # f 자기 입찰 건너뜀 → ACTIVE
bid 6 SELL 74000           # g → f의 구매와 체결 75000(구매가 앞섬)
call GET "/product-sizes/$size/book"
call GET "/trades?role=buyer" "$(U 3)"
call GET "/bids/$c_bid" "$(U 2)"

dump bids "SELECT id-$mb,user_id-$base,side,price,status,expires_at,created_at FROM bids WHERE id>$mb ORDER BY id"
dump bid_events "SELECT id-$me,bid_id-$mb,from_status,to_status,trade_id-$mt,created_at FROM bid_events WHERE id>$me ORDER BY id"
dump trades "SELECT id-$mt,buy_bid_id-$mb,sell_bid_id-$mb,buyer_id-$base,seller_id-$base,price,status,matched_at,payment_due_at FROM trades WHERE id>$mt ORDER BY id"
app_end
