#!/usr/bin/env bash
# 시나리오 batch — 배치: bid-expiry, payment-due(미결제 경고 3회 → 구매 제한·판매 입찰 복구와 재매칭·UNKNOWN 제외),
# pg-inquiry(UNKNOWN 승인 확정·404 → 거절과 포인트 해제), 대상 없음, 제한 사용자 입찰 403, 없는 배치 이름.
# 사용: scenarios/batch.sh <god|layered> <jar> <outdir>  (보통은 tools/equiv/run.sh batch)
source "$(dirname "$0")/../lib.sh"
app_begin "$@"
start_db

# 픽스처: 새 사이즈 1개, 사용자 s(판매)·b(구매, 포인트 5000)·c·w(미결제 구매자)
q "INSERT INTO product_sizes(product_id,size) SELECT min(id),'EXP' FROM products" > /dev/null
for u in s b c w; do q "INSERT INTO users(email,name,grade,created_at) VALUES ('$u@exp','$u','SILVER','2026-09-01T00:00:00+09:00')" > /dev/null; done
size=$(q "SELECT max(id) FROM product_sizes"); base=$(q "SELECT max(id)-3 FROM users")
S_=$base B_=$((base+1)) C_=$((base+2)) W_=$((base+3))
q "INSERT INTO point_ledger(user_id,kind,status,amount,remaining,expires_at,created_at) VALUES ($B_,'EARN','AVAILABLE',5000,5000,'2027-06-01T00:00:00+09:00','2026-09-02T00:00:00+09:00')" > /dev/null
q "INSERT INTO point_balances(user_id,available,pending,updated_at) VALUES ($B_,5000,0,'2026-09-02T00:00:00+09:00')" > /dev/null
mb=$(q "SELECT max(id) FROM bids"); me=$(q "SELECT max(id) FROM bid_events"); mt=$(q "SELECT max(id) FROM trades"); mw=$(q "SELECT coalesce(max(id),0) FROM buyer_warnings")
mo=$(q "SELECT max(id) FROM orders"); mi=$(q "SELECT max(id) FROM order_items"); mp=$(q "SELECT max(id) FROM pg_transactions"); ml=$(q "SELECT max(id) FROM point_ledger"); ma=$(q "SELECT coalesce(max(id),0) FROM point_allocations")

start_pg 3000
start_app 2000
bid() { call POST /bids "$1" "{\"productSizeId\":$size,\"side\":\"$2\",\"price\":$3,\"validDays\":$4}"; }
bid $C_ BUY 20000 30                                     # 30일 뒤 bid-expiry 대상
for p in 60000 61000 62000; do bid $S_ SELL $p 60; bid $W_ BUY $p 30; done   # w의 체결 3건(미결제 → 경고 3건 → 제한)
bid $S_ SELL 99000 30; bid $B_ BUY 99000 30               # t4: UNKNOWN(승인 기록됨)
bid $S_ SELL 30000 30; bid $B_ BUY 30000 30               # t5: UNKNOWN(기록 안 됨)
adv 60
bid $C_ BUY 70000 90                                      # 복구된 판매 입찰(60000)과 재매칭될 상대
t=$(q "SELECT string_agg(id::text, ' ' ORDER BY id) FROM trades WHERE id>$mt"); read -r _ _ _ T4 T5 <<< "$t"
[ -n "$T5" ] || die "체결 5건이 아님: $t"
outcome K4 TIMEOUT_APPROVED; call POST /orders $B_ "{\"tradeIds\":[$T4],\"pointToUse\":1000}" K4
outcome K5 TIMEOUT_NOT_RECEIVED; call POST /orders $B_ "{\"tradeIds\":[$T5],\"pointToUse\":2000}" K5
adv 1800
call POST /admin/batches/payment-due                      # w 체결 3건 만료(경고 3 → 제한), 재매칭, t4·t5 제외
call POST /admin/batches/pg-inquiry                       # K4 승인(paid_at = 요청 시각), K5 404 → 거절·해제
call POST /admin/batches/pg-inquiry                       # 대상 없음
call POST /admin/batches/payment-due                      # t5(거절, 기한 지남) 만료
call POST /bids $W_ "{\"productSizeId\":$size,\"side\":\"BUY\",\"price\":10000,\"validDays\":30}"   # 구매 제한 403
adv 2592000
call POST /admin/batches/bid-expiry
call POST /admin/batches/payment-due
call POST /admin/batches/no-such
call GET "/orders/$((mo+1))" $B_; call GET /me/points $B_

dump bids "SELECT id-$mb,user_id-$base,side,price,status,expires_at,created_at FROM bids WHERE id>$mb ORDER BY id"
dump bid_events "SELECT id-$me,bid_id-$mb,from_status,to_status,trade_id-$mt,created_at FROM bid_events WHERE id>$me ORDER BY id"
q "SELECT id,status FROM bids WHERE id<=$mb ORDER BY id" | md5sum | dump_text seed_bids.md5            # 시드 입찰 상태(bid-expiry 대상 포함)
dump trades "SELECT id-$mt,buy_bid_id-$mb,sell_bid_id-$mb,buyer_id-$base,seller_id-$base,price,status,matched_at,payment_due_at FROM trades WHERE id>$mt ORDER BY id"
q "SELECT id,status FROM trades WHERE id<=$mt ORDER BY id" | md5sum | dump_text seed_trades.md5
dump buyer_warnings "SELECT id-$mw,user_id-$base,trade_id-$mt,created_at FROM buyer_warnings WHERE id>$mw ORDER BY id"
dump users "SELECT id,purchase_restricted_until,sale_restricted_until FROM users ORDER BY id"
dump orders "SELECT id-$mo,buyer_id-$base,status,item_total,buyer_fee_total,shipping_fee,point_used,card_amount,idempotency_key,created_at,paid_at FROM orders WHERE id>$mo ORDER BY id"
dump order_items "SELECT id-$mi,order_id-$mo,trade_id-$mt,card_share,point_share,status,paid_at FROM order_items WHERE id>$mi ORDER BY id"
dump pg_transactions "SELECT id-$mp,order_id-$mo,kind,amount,status,$(norm_pg_tx_id),idempotency_key,requested_at,responded_at FROM pg_transactions WHERE id>$mp ORDER BY id"
dump point_ledger "SELECT id-$ml,user_id-$base,kind,status,amount,remaining,order_item_id-$mi,order_id-$mo,created_at FROM point_ledger WHERE id>$ml-1 ORDER BY id"
dump point_allocations "SELECT id-$ma,entry_id-$ml,earn_entry_id-$ml,amount FROM point_allocations WHERE id>$ma ORDER BY id"
dump point_balances "SELECT user_id,available,pending,updated_at FROM point_balances ORDER BY user_id"
app_end
