#!/usr/bin/env bash
# 시나리오 pay — 결제: 승인(포인트 묶음 2개 배분)·같은 키 재요청 200/409·거절과 포인트 해제·새 키 재결제·
# 응답 없음(UNKNOWN, 예약 유지)·UNKNOWN 체결 409·포인트 규칙 422·남의 체결 403·없는 체결 404·주문/포인트 조회.
# 사용: scenarios/pay.sh <god|layered> <jar> <outdir>  (보통은 tools/equiv/run.sh pay)
source "$(dirname "$0")/../lib.sh"
app_begin "$@"
start_db

# 픽스처: 새 사이즈 1개, 사용자 s(판매)·b(구매)·c, b의 사용 가능 포인트 묶음 2개(만료가 이른 것부터 배분)
q "INSERT INTO product_sizes(product_id,size) SELECT min(id),'EXP' FROM products" > /dev/null
for u in s b c; do q "INSERT INTO users(email,name,grade,created_at) VALUES ('$u@exp','$u','BRONZE','2026-09-01T00:00:00+09:00')" > /dev/null; done
size=$(q "SELECT max(id) FROM product_sizes"); base=$(q "SELECT max(id)-2 FROM users")
S_=$base B_=$((base+1)) C_=$((base+2))
q "INSERT INTO point_ledger(user_id,kind,status,amount,remaining,expires_at,created_at) VALUES ($B_,'EARN','AVAILABLE',3000,3000,'2026-12-01T00:00:00+09:00','2026-09-02T00:00:00+09:00'),($B_,'EARN','AVAILABLE',4000,4000,'2026-11-01T00:00:00+09:00','2026-09-03T00:00:00+09:00')" > /dev/null
q "INSERT INTO point_balances(user_id,available,pending,updated_at) VALUES ($B_,7000,0,'2026-09-03T00:00:00+09:00')" > /dev/null
mo=$(q "SELECT coalesce(max(id),0) FROM orders"); mi=$(q "SELECT coalesce(max(id),0) FROM order_items"); mp=$(q "SELECT coalesce(max(id),0) FROM pg_transactions")
ml=$(q "SELECT max(id) FROM point_ledger"); ma=$(q "SELECT coalesce(max(id),0) FROM point_allocations"); mt=$(q "SELECT coalesce(max(id),0) FROM trades")

start_pg 3000
start_app 2000
trade() { call POST /bids $S_ "{\"productSizeId\":$size,\"side\":\"SELL\",\"price\":$1,\"validDays\":30}"; call POST /bids $B_ "{\"productSizeId\":$size,\"side\":\"BUY\",\"price\":$1,\"validDays\":30}"; }
trade 120000; trade 45000; trade 30000; trade 99000; trade 50000
t=$(q "SELECT string_agg(id::text, ' ' ORDER BY id) FROM trades WHERE id>$mt"); read -r T1 T2 T3 T4 T5 <<< "$t"
[ -n "$T5" ] || die "체결 5건이 아님: $t"
call POST /orders $B_ "{\"tradeIds\":[$T2,$T1],\"pointToUse\":5000}" K1          # 승인, 포인트 5000(묶음 두 개에 배분)
call POST /orders $B_ "{\"tradeIds\":[$T1,$T2],\"pointToUse\":5000}" K1          # 같은 키·같은 요청 200
call POST /orders $B_ "{\"tradeIds\":[$T1,$T2],\"pointToUse\":0}" K1             # 같은 키·다른 요청 409
call POST /orders $C_ "{\"tradeIds\":[$T3]}" K1                                  # 같은 키·다른 구매자 409
outcome K2 DECLINE
call POST /orders $B_ "{\"tradeIds\":[$T3],\"pointToUse\":1000}" K2              # 거절, 포인트 해제
call POST /orders $B_ "{\"tradeIds\":[$T3]}" K3                                  # 거절된 체결 새 키로 승인
outcome K4 TIMEOUT_APPROVED
call POST /orders $B_ "{\"tradeIds\":[$T4],\"pointToUse\":1000}" K4              # 응답 없음 → UNKNOWN, 예약 유지
call POST /orders $B_ "{\"tradeIds\":[$T4],\"pointToUse\":1000}" K4              # 같은 키 200 UNKNOWN
call POST /orders $B_ "{\"tradeIds\":[$T4]}" K5                                  # UNKNOWN 주문의 체결 409
call POST /orders $B_ "{\"tradeIds\":[$T5],\"pointToUse\":990}" K6               # 포인트 규칙 422
call POST /orders $C_ "{\"tradeIds\":[$T5]}" K7                                  # 남의 체결 403
call POST /orders $B_ "{\"tradeIds\":[$T5,999999999]}" K8                        # 없는 체결 404
o=$(q "SELECT min(id) FROM orders WHERE id>$mo")
call GET "/orders/$o" $B_; call GET "/orders/$o" $C_; call GET /me/points $B_

dump orders "SELECT id-$mo,buyer_id-$base,status,item_total,buyer_fee_total,shipping_fee,point_used,card_amount,idempotency_key,created_at,paid_at FROM orders WHERE id>$mo ORDER BY id"
dump order_items "SELECT id-$mi,order_id-$mo,trade_id-$mt,price,buyer_fee,card_share,point_share,shipping_share,status,paid_at FROM order_items WHERE id>$mi ORDER BY id"
dump pg_transactions "SELECT id-$mp,order_id-$mo,kind,amount,status,$(norm_pg_tx_id),idempotency_key,requested_at,responded_at FROM pg_transactions WHERE id>$mp ORDER BY id"
dump point_ledger "SELECT id-$ml,user_id-$base,kind,status,amount,remaining,expires_at,order_item_id-$mi,order_id-$mo,created_at FROM point_ledger WHERE id>$ml-2 ORDER BY id"
dump point_allocations "SELECT id-$ma,entry_id-$ml,earn_entry_id-$ml,amount FROM point_allocations WHERE id>$ma ORDER BY id"
dump point_balances "SELECT user_id-$base,available,pending,updated_at FROM point_balances WHERE user_id>=$base ORDER BY user_id"
dump trades "SELECT id-$mt,status FROM trades WHERE id>$mt ORDER BY id"
app_end
