#!/usr/bin/env bash
# 시나리오 ful — 2단계 이행: 발송 → 입고 → 검수 PASS → 배송 → 구매 확정, 검수 FAIL(판매자 귀책 환불·패널티·포인트 복구),
# 분쟁 제기 → 기각 → 다시 제기 → 인정(환불·적립 회수), 상태 오류 409(ITEM_STATE_CONFLICT·DEADLINE_PASSED·DISPUTE_NOT_OPEN)·
# 권한 403·없는 항목/분쟁 404·형식 오류 400, 항목·주문 조회. 배치는 부르지 않는다(시드 행을 바꾸지 않음).
# 사용: scenarios/ful.sh <god|layered> <jar> <outdir>  (보통은 tools/equiv/run.sh ful)
source "$(dirname "$0")/../lib.sh"
app_begin "$@"
start_db

# 픽스처: 새 사이즈 1개, 사용자 s(판매)·b(구매, 포인트 5000)·c(제3자)
q "INSERT INTO product_sizes(product_id,size) SELECT min(id),'EXP' FROM products" > /dev/null
for u in s b c; do q "INSERT INTO users(email,name,grade,created_at) VALUES ('$u@exp','$u','BRONZE','2026-09-01T00:00:00+09:00')" > /dev/null; done
size=$(q "SELECT max(id) FROM product_sizes"); base=$(q "SELECT max(id)-2 FROM users")
S_=$base B_=$((base+1)) C_=$((base+2))
q "INSERT INTO point_ledger(user_id,kind,status,amount,remaining,expires_at,created_at) VALUES ($B_,'EARN','AVAILABLE',5000,5000,'2027-06-01T00:00:00+09:00','2026-09-02T00:00:00+09:00')" > /dev/null
q "INSERT INTO point_balances(user_id,available,pending,updated_at) VALUES ($B_,5000,0,'2026-09-02T00:00:00+09:00')" > /dev/null
mo=$(q "SELECT max(id) FROM orders"); mi=$(q "SELECT max(id) FROM order_items"); mp=$(q "SELECT max(id) FROM pg_transactions"); mt=$(q "SELECT max(id) FROM trades")
ml=$(q "SELECT max(id) FROM point_ledger"); ma=$(q "SELECT max(id) FROM point_allocations"); mr=$(q "SELECT max(id) FROM refunds")
mn=$(q "SELECT max(id) FROM inspections"); md=$(q "SELECT max(id) FROM disputes"); mx=$(q "SELECT max(id) FROM seller_penalties")

start_pg 3000
start_app 2000
trade() { call POST /bids $S_ "{\"productSizeId\":$size,\"side\":\"SELL\",\"price\":$1,\"validDays\":30}"; call POST /bids $B_ "{\"productSizeId\":$size,\"side\":\"BUY\",\"price\":$1,\"validDays\":30}"; }
trade 120000; trade 45000; trade 30000; trade 80000
t=$(q "SELECT string_agg(id::text, ' ' ORDER BY id) FROM trades WHERE id>$mt"); read -r T1 T2 T3 T4 <<< "$t"
[ -n "$T4" ] || die "체결 4건이 아님: $t"
call POST /orders $B_ "{\"tradeIds\":[$T1,$T2,$T3],\"pointToUse\":3000}" F1   # 3항목 주문(포인트 3000)
call POST /orders $B_ "{\"tradeIds\":[$T4]}" F2                                 # 발송 기한 초과용
o1=$(q "SELECT id FROM orders WHERE id>$mo AND idempotency_key='F1'"); o2=$(q "SELECT id FROM orders WHERE id>$mo AND idempotency_key='F2'")
i=$(q "SELECT string_agg(id::text, ' ' ORDER BY id) FROM order_items WHERE order_id=$o1"); read -r I1 I2 I3 <<< "$i"
I4=$(q "SELECT id FROM order_items WHERE order_id=$o2")
[ -n "$I3" ] && [ -n "$I4" ] || die "주문 항목이 4건이 아님: $i / $I4"

mark ship
call POST "/order-items/$I1/shipment" $C_                     # 판매자 아님 403
call POST "/order-items/$I1/shipment" $B_                     # 구매자도 403
call POST "/order-items/$I1/shipment" $S_
call POST "/order-items/$I1/shipment" $S_                     # 이미 발송 409 ITEM_STATE_CONFLICT
call POST "/order-items/$I2/shipment" $S_
call POST "/order-items/$I3/shipment" $S_
call POST /order-items/999999999/shipment $S_                  # 없는 항목 404
mark receipt
call POST "/admin/order-items/$I4/receipt"                    # PAID 입고 409
call POST "/admin/order-items/$I1/receipt"
call POST "/admin/order-items/$I2/receipt"
call POST "/admin/order-items/$I3/receipt"
call POST "/admin/order-items/$I1/receipt"                    # 이미 INSPECTING 409
mark inspection-pass
call POST "/admin/order-items/$I1/inspection" "" '{"result":"PASS","reason":"무시되는 사유"}'
call POST "/admin/order-items/$I3/inspection" "" '{"result":"PASS","reason":null}'
mark inspection-fail
call POST "/admin/order-items/$I2/inspection" "" '{"result":"FAIL","reason":"  "}'           # 빈 사유 400
call POST "/admin/order-items/$I2/inspection" "" '{"result":"MAYBE"}'                        # 결과 값 400
call POST "/admin/order-items/$I2/inspection" "" '{"reason":"오염"}'                          # 결과 없음 400
call POST "/admin/order-items/$I2/inspection" "" '{"result":"FAIL","reason":" 오염 "}'        # 판매자 귀책(사유 그대로)
call POST "/admin/order-items/$I2/inspection" "" '{"result":"PASS"}'                         # 이미 취소 409
call POST "/admin/order-items/$I1/inspection" "" '{"result":"PASS"}'                         # 이미 DELIVERING 409
mark delivery
call POST "/order-items/$I1/confirm" $B_                      # DELIVERING 확정 409
call POST "/admin/order-items/$I1/delivery"
call POST "/admin/order-items/$I3/delivery"
call POST "/admin/order-items/$I4/delivery"                   # PAID 배송 409
mark confirm
call POST "/order-items/$I1/confirm" $C_                      # 구매자 아님 403
call POST "/order-items/$I1/confirm" $B_
call POST "/order-items/$I3/confirm" $B_
call POST "/order-items/$I1/confirm" $B_                      # 이미 확정 409
adv 86400
mark dispute-open
call POST "/order-items/$I1/disputes" $C_                     # 구매자 아님 403
call POST "/order-items/$I4/disputes" $B_                     # PAID 분쟁 409
call POST "/order-items/$I1/disputes" $B_                     # 201 OPEN
call POST "/order-items/$I1/disputes" $B_                     # DISPUTED 다시 제기 409
d1=$(q "SELECT max(id) FROM disputes")
mark dispute-reject
call POST "/admin/disputes/$d1/resolution" "" '{}'            # accept 없음 400
call POST /admin/disputes/999999999/resolution "" '{"accept":true}'   # 없는 분쟁 404
call POST "/admin/disputes/$d1/resolution" "" '{"accept":false}'
call POST "/admin/disputes/$d1/resolution" "" '{"accept":true}'       # 이미 판정 409 DISPUTE_NOT_OPEN
adv 3600
mark dispute-reopen
call POST "/order-items/$I1/disputes" $B_                     # 기각 뒤 다시 제기 201
d2=$(q "SELECT max(id) FROM disputes")
mark dispute-accept
call POST "/admin/disputes/$d2/resolution" "" '{"accept":true}'       # 인정: 환불(DISPUTE)·카드 취소·적립 회수
call POST "/order-items/$I1/disputes" $B_                     # REFUNDED_AFTER_CONFIRM 409
adv 259200
mark deadlines
call POST "/order-items/$I4/shipment" $S_                     # paid_at + 3일 지남 409 DEADLINE_PASSED
adv 1296000
call POST "/order-items/$I3/disputes" $B_                     # confirmed_at + 14일 지남 409 DEADLINE_PASSED
mark reads
call GET "/order-items/$I1" $B_; call GET "/order-items/$I2" $S_; call GET "/order-items/$I3" $C_   # 제3자 403
call GET /order-items/999999999 $B_
call GET "/orders/$o1" $B_; call GET /me/points $B_

dump orders "SELECT id-$mo,status,point_used,card_amount,idempotency_key,paid_at FROM orders WHERE id>$mo ORDER BY id"
dump order_items "SELECT id-$mi,order_id-$mo,trade_id-$mt,price,buyer_fee,card_share,point_share,shipping_share,status,paid_at,shipped_at,inspected_at,delivered_at,confirmed_at,cancelled_at FROM order_items WHERE id>$mi ORDER BY id"
dump inspections "SELECT id-$mn,order_item_id-$mi,result,reason,inspected_at FROM inspections WHERE id>$mn ORDER BY id"
dump disputes "SELECT id-$md,order_item_id-$mi,status,opened_at,resolved_at FROM disputes WHERE id>$md ORDER BY id"
dump refunds "SELECT id-$mr,order_item_id-$mi,card_amount,point_amount,buyer_fee_amount,shipping_amount,reason,fault,created_at FROM refunds WHERE id>$mr ORDER BY id"
dump seller_penalties "SELECT id-$mx,seller_id-$base,order_item_id-$mi,amount,settlement_id,created_at FROM seller_penalties WHERE id>$mx ORDER BY id"
dump pg_transactions "SELECT id-$mp,order_id-$mo,kind,amount,status,$(norm_pg_tx_id),idempotency_key,requested_at,responded_at FROM pg_transactions WHERE id>$mp ORDER BY id"
dump point_ledger "SELECT id-$ml,user_id-$base,kind,status,amount,remaining,expires_at,order_item_id-$mi,order_id-$mo,created_at FROM point_ledger WHERE id>$ml-1 ORDER BY id"
dump point_allocations "SELECT id-$ma,entry_id-$ml,earn_entry_id-$ml,amount FROM point_allocations WHERE id>$ma ORDER BY id"
dump point_balances "SELECT user_id-$base,available,pending,updated_at FROM point_balances WHERE user_id>=$base ORDER BY user_id"
dump users "SELECT id-$base,grade,purchase_restricted_until,sale_restricted_until FROM users WHERE id>=$base ORDER BY id"
dump trades "SELECT id-$mt,status FROM trades WHERE id>$mt ORDER BY id"
app_end
