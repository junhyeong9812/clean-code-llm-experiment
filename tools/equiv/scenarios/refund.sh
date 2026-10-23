#!/usr/bin/env bash
# 시나리오 refund — 2단계 환불: 구매자 취소(첫 항목·마지막 항목 배송비, 만료된 묶음으로 복구 → EXPIRE), 발송 기한 초과 배치
# (판매자 귀책·마지막 항목 배송비·시드 PAID 항목 포함·카드 취소 실패 대상 건너뜀 → 재실행), 판매자 패널티 3건 → 판매 제한·판매 입찰 403,
# 분쟁 인정(적립 일부 사용 뒤 회수 REVOKE), PG 카드 취소 DECLINE·UNAVAILABLE(반영 없음 409) → 재시도 성공,
# 계약 밖 2건: (a) 체결가 < 10원 판매자 귀책(패널티 0원), (b) 가짜 PG에 같은 키·다른 금액의 취소 기록이 이미 있음 → 409.
# 사용: scenarios/refund.sh <god|layered> <jar> <outdir>  (보통은 tools/equiv/run.sh refund)
# 배치는 시드 전체가 대상이다(시드 PAID 항목 14건이 발송 기한 초과로 취소된다). 바뀐 시드 행은 정렬 덤프의 md5로 비교한다.
source "$(dirname "$0")/../lib.sh"
app_begin "$@"
start_db

# 픽스처: 새 사이즈 1개, 사용자 s(판매)·s2(판매)·b(구매, 묶음 X 3000 10-02 만료·Y 10000)·b2(구매)·c(제3자)
q "INSERT INTO product_sizes(product_id,size) SELECT min(id),'EXP' FROM products" > /dev/null
for u in s s2 b b2 c; do q "INSERT INTO users(email,name,grade,created_at) VALUES ('$u@exp','$u','BRONZE','2026-09-01T00:00:00+09:00')" > /dev/null; done
size=$(q "SELECT max(id) FROM product_sizes"); base=$(q "SELECT max(id)-4 FROM users")
S_=$base S2_=$((base+1)) B_=$((base+2)) B2_=$((base+3)) C_=$((base+4))
q "INSERT INTO point_ledger(user_id,kind,status,amount,remaining,expires_at,created_at) VALUES ($B_,'EARN','AVAILABLE',3000,3000,'2026-10-02T00:00:00+09:00','2026-09-02T00:00:00+09:00'),($B_,'EARN','AVAILABLE',10000,10000,'2027-06-01T00:00:00+09:00','2026-09-03T00:00:00+09:00')" > /dev/null
q "INSERT INTO point_balances(user_id,available,pending,updated_at) VALUES ($B_,13000,0,'2026-09-03T00:00:00+09:00')" > /dev/null
mo=$(q "SELECT max(id) FROM orders"); mi=$(q "SELECT max(id) FROM order_items"); mp=$(q "SELECT max(id) FROM pg_transactions"); mt=$(q "SELECT max(id) FROM trades")
ml=$(q "SELECT max(id) FROM point_ledger"); ma=$(q "SELECT max(id) FROM point_allocations"); mr=$(q "SELECT max(id) FROM refunds")
mn=$(q "SELECT max(id) FROM inspections"); md=$(q "SELECT max(id) FROM disputes"); mx=$(q "SELECT max(id) FROM seller_penalties"); mb=$(q "SELECT max(id) FROM bids")

# open_dispute <항목 id>: 이 시나리오에서 만든 그 항목의 OPEN 분쟁 id(없으면 실패 — 제기가 실패한 채 다른 분쟁을 판정하지 않게)
open_dispute() { local d; d=$(q "SELECT id FROM disputes WHERE id>$md AND order_item_id=$1 AND status='OPEN'"); [[ $d =~ ^[0-9]+$ ]] || die "분쟁 준비 실패: 항목 $1 → '$d'"; echo "$d"; }
# need <이름> <건수>: 시드 경로를 실제로 지났는지 — 0이면 실패(두 앱이 함께 0이어도 같음으로 끝나지 않게)
need() { [[ $2 =~ ^[0-9]+$ ]] && [ "$2" -gt 0 ] || die "경로를 지나지 않음: $1 = '$2'"; }
start_pg 3000
start_app 2000
# trade <판매자> <구매자> <가격>: 체결 1건(호가창이 비어 있어 바로 체결된다)
trade() { call POST /bids "$1" "{\"productSizeId\":$size,\"side\":\"SELL\",\"price\":$3,\"validDays\":30}"; call POST /bids "$2" "{\"productSizeId\":$size,\"side\":\"BUY\",\"price\":$3,\"validDays\":30}"; }
last_trade() { q "SELECT max(id) FROM trades"; }
# items <주문 키>: 그 주문의 항목 id(id 순)
items() { q "SELECT string_agg(i.id::text, ' ' ORDER BY i.id) FROM order_items i JOIN orders o ON o.id=i.order_id WHERE o.id>$mo AND o.idempotency_key='$1'"; }
# pay <구매자> <키> <포인트> <체결…>
pay() { local b=$1 k=$2 p=$3; shift 3; call POST /orders "$b" "{\"tradeIds\":[$(IFS=,; echo "$*")],\"pointToUse\":$p}" "$k"; }
# to_inspecting <항목> <판매자>: 발송·입고
to_inspecting() { call POST "/order-items/$1/shipment" "$2"; call POST "/admin/order-items/$1/receipt"; }

trade $S_ $B_ 30000; ta=$(last_trade); trade $S_ $B_ 20000; tb=$(last_trade); pay $B_ R1 5000 $ta $tb        # 예 E 모양(배송비 3000)
trade $S_ $B_ 30000; tc=$(last_trade); trade $S_ $B_ 20000; td=$(last_trade); pay $B_ R2 0 $tc $td
trade $S_ $B_ 60000; pay $B_ R3 0 "$(last_trade)"                                                           # PG 취소 실패 → 재시도
trade $S_ $B_ 70000; pay $B_ R4 0 "$(last_trade)"                                                           # 기한 초과 배치에서 건너뜀 → 재실행
trade $S2_ $B2_ 150000; pay $B2_ R5 0 "$(last_trade)"                                                       # 분쟁 인정(적립 회수)
trade $S2_ $B_ 5; pay $B_ R6 0 "$(last_trade)"                                                              # 계약 밖 (a) 체결가 5원
trade $S_ $B_ 40000; pay $B_ R7 0 "$(last_trade)"                                                           # 계약 밖 (b) 취소 기록 금액 불일치
trade $S_ $B_ 25000; pay $B_ R8 0 "$(last_trade)"                                                           # 검수 불합격(패널티 1)
read -r R1a R1b <<< "$(items R1)"; read -r R2a R2b <<< "$(items R2)"
R3a=$(items R3) R4a=$(items R4) R5a=$(items R5) R6a=$(items R6) R7a=$(items R7) R8a=$(items R8)
for v in R1a R1b R2a R2b R3a R4a R5a R6a R7a R8a; do [[ ${!v} =~ ^[0-9]+$ ]] || die "주문 항목 준비 실패: $v=${!v}"; done

adv 90000                                                       # 10-02 01:00 — 묶음 X 만료 뒤
mark buyer-cancel
call POST "/order-items/$R1a/cancel" $C_                       # 구매자 아님 403
call POST "/order-items/$R1a/cancel" $B_                       # 첫 항목 구매자 취소(배송비 부분 제외), 만료된 X로 복구 → EXPIRE
call POST "/order-items/$R1b/cancel" $B_                       # 마지막 항목(남은 배송비 실림) → CANCELLED
call POST "/order-items/$R1b/cancel" $B_                       # 이미 취소 409
call POST "/order-items/$R2a/cancel" $B_                       # R2 첫 항목 구매자 취소(포인트 없음)
call POST /order-items/999999999/cancel $B_                    # 없는 항목 404
mark pg-cancel-fail
outcome "cancel-$R3a" DECLINE
call POST "/order-items/$R3a/cancel" $B_                       # 거절 → 409 PG_UNAVAILABLE, 반영 없음
outcome "cancel-$R3a" UNAVAILABLE
call POST "/order-items/$R3a/cancel" $B_                       # 503 → 409 PG_UNAVAILABLE, 반영 없음
call GET "/order-items/$R3a" $B_                               # 아직 PAID·refund null
outcome "cancel-$R3a" APPROVE
call POST "/order-items/$R3a/cancel" $B_                       # 재시도 성공
mark out-of-contract-amount
# 계약 밖 (b): 같은 키(cancel-<항목>)로 다른 금액(1원)의 취소 기록을 가짜 PG에 먼저 만든다 → 앱의 취소는 첫 기록(1원)을 받는다
curl -sSf --max-time "$EQ_CURL_MAX" -X POST -H 'Content-Type: application/json' -d "{\"idempotencyKey\":\"cancel-$R7a\",\"approvalKey\":\"R7\",\"amount\":1}" "$EQ_PG/cancellations" > /dev/null
call POST "/order-items/$R7a/cancel" $B_                       # 응답 금액 ≠ 요청 금액 → 409 PG_UNAVAILABLE
call POST "/order-items/$R7a/cancel" $B_                       # 다시 보내도 409
mark dispute-accept
to_inspecting "$R5a" $S2_
call POST "/admin/order-items/$R5a/inspection" "" '{"result":"PASS"}'
call POST "/admin/order-items/$R5a/delivery"
call POST "/order-items/$R5a/confirm" $B2_                     # 적립 1500 AVAILABLE
trade $S2_ $B2_ 50000; pay $B2_ R9 1000 "$(last_trade)"        # 적립 묶음에서 1000 사용
call POST "/order-items/$R5a/disputes" $B2_
d=$(open_dispute "$R5a")
call POST "/admin/disputes/$d/resolution" "" '{"accept":true}' # 환불(DISPUTE) + 남은 500 회수(REVOKE)
mark inspection-fail
to_inspecting "$R8a" $S_
call POST "/admin/order-items/$R8a/inspection" "" '{"result":"FAIL","reason":"오염"}'   # 판매자 s 패널티 1
mark out-of-contract-penalty0
to_inspecting "$R6a" $S2_
call POST "/admin/order-items/$R6a/inspection" "" '{"result":"FAIL","reason":"오염"}'   # 계약 밖 (a) 패널티 floor(5 × 10%) = 0
call GET "/order-items/$R6a" $B_

outcome "cancel-$R4a" UNAVAILABLE
adv 172800                                                      # 10-04 01:00 — R2b·R4·R7·시드 PAID 항목의 기한(paid_at + 3일) 지남
mark shipment-deadline
call POST /admin/batches/shipment-deadline                     # R2b(마지막, 배송비) 처리, R4(PG 불가)·R7(금액 불일치) 건너뜀, 시드 PAID 항목
outcome "cancel-$R4a" APPROVE
call POST /admin/batches/shipment-deadline                     # R4 처리 → 판매자 s 패널티 3건 → 판매 제한
call POST /admin/batches/shipment-deadline                     # R7만 남음(건너뜀)
call POST /bids $S_ "{\"productSizeId\":$size,\"side\":\"SELL\",\"price\":90000,\"validDays\":30}"   # 판매 제한 403
n=$(q "SELECT count(*) FROM refunds WHERE id>$mr AND order_item_id<=$mi AND fault='SELLER'"); need "시드 항목 발송 기한 초과" "$n"
mark reads
for k in R1 R2 R3 R4 R5 R6 R7 R8 R9; do
  o=$(q "SELECT id FROM orders WHERE id>$mo AND idempotency_key='$k'"); u=$(q "SELECT buyer_id FROM orders WHERE id>$mo AND idempotency_key='$k'")
  call GET "/orders/$o" "$u"
done
call GET /me/points $B_; call GET /me/points $B2_

dump orders "SELECT id-$mo,buyer_id-$base,status,item_total,buyer_fee_total,shipping_fee,point_used,card_amount,idempotency_key,paid_at FROM orders WHERE id>$mo ORDER BY id"
dump order_items "SELECT id-$mi,order_id-$mo,trade_id-$mt,price,buyer_fee,card_share,point_share,shipping_share,status,paid_at,shipped_at,inspected_at,delivered_at,confirmed_at,cancelled_at FROM order_items WHERE id>$mi ORDER BY id"
dump refunds "SELECT id-$mr,order_item_id-$mi,card_amount,point_amount,buyer_fee_amount,shipping_amount,reason,fault,created_at FROM refunds WHERE id>$mr ORDER BY id"
dump pg_transactions "SELECT id-$mp,order_id-$mo,kind,amount,status,$(norm_pg_tx_id),idempotency_key,requested_at,responded_at FROM pg_transactions WHERE id>$mp ORDER BY id"
dump seller_penalties "SELECT id-$mx,seller_id,order_item_id-$mi,amount,settlement_id,created_at FROM seller_penalties WHERE id>$mx ORDER BY id"
dump inspections "SELECT id-$mn,order_item_id-$mi,result,reason,inspected_at FROM inspections WHERE id>$mn ORDER BY id"
dump disputes "SELECT id-$md,order_item_id-$mi,status,opened_at,resolved_at FROM disputes WHERE id>$md ORDER BY id"
dump point_ledger "SELECT id-$ml,user_id,kind,status,amount,remaining,expires_at,order_item_id-$mi,order_id-$mo,created_at FROM point_ledger WHERE id>$ml-2 ORDER BY id"
dump point_allocations "SELECT id-$ma,entry_id-$ml,earn_entry_id-$ml,amount FROM point_allocations WHERE id>$ma ORDER BY id"
dump point_balances "SELECT user_id-$base,available,pending,updated_at FROM point_balances WHERE user_id>=$base ORDER BY user_id"
dump users "SELECT id-$base,grade,purchase_restricted_until,sale_restricted_until FROM users WHERE id>=$base ORDER BY id"
dump bids "SELECT id-$mb,user_id-$base,side,price,status FROM bids WHERE id>$mb ORDER BY id"
# 시드 행(배치가 바꾼 것 포함): 정렬 덤프의 md5
{ q "SELECT id,status,cancelled_at FROM order_items WHERE id<=$mi ORDER BY id" | md5sum
  q "SELECT id,status FROM orders WHERE id<=$mo ORDER BY id" | md5sum
  q "SELECT id,status,remaining,expires_at FROM point_ledger WHERE id<=$ml ORDER BY id" | md5sum
  q "SELECT user_id,available,pending,updated_at FROM point_balances WHERE user_id<$base ORDER BY user_id" | md5sum
  q "SELECT id,sale_restricted_until FROM users WHERE id<$base ORDER BY id" | md5sum
  q "SELECT id,settlement_id FROM seller_penalties WHERE id<=$mx ORDER BY id" | md5sum; } | dump_text seed.md5
q "SELECT status,count(*) FROM order_items WHERE id<=$mi GROUP BY status ORDER BY status" | dump_text seed_item_status
app_end
