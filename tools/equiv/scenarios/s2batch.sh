#!/usr/bin/env bash
# 시나리오 s2batch — 2단계 배치: auto-confirm(소급 confirmed_at·적립 확정, 7일 뒤 구매자 확정은 소급), point-expiry(배분이 있는
# 묶음의 남은 양 소멸 = remaining 변경 + 배분 INSERT, 쓰지 않은 묶음), grade-recalc(11-01 기준 GOLD 승급·같은 달 재실행 0) →
# 승급 뒤 주문의 적립률(GOLD 2%)과 확정. 각 배치 재실행(대상 없음). 회수(REVOKE)는 ful·refund가 지나간다.
# 사용: scenarios/s2batch.sh <god|layered> <jar> <outdir>  (보통은 tools/equiv/run.sh s2batch)
# 배치는 시드 전체가 대상이다(시드 DELIVERED 항목 확정·시드 묶음 소멸·전 사용자 등급 재산정). 바뀐 시드 행은 정렬 덤프의 md5로 비교한다.
source "$(dirname "$0")/../lib.sh"
app_begin "$@"
start_db

# 픽스처: 새 사이즈 1개, 사용자 s(판매)·g(구매 326만 원 → GOLD)·p(포인트: P1 5000 10-10 만료·P2 2000 10-05 만료·P3 1000 10-03 만료)
q "INSERT INTO product_sizes(product_id,size) SELECT min(id),'EXP' FROM products" > /dev/null
for u in s g p; do q "INSERT INTO users(email,name,grade,created_at) VALUES ('$u@exp','$u','BRONZE','2026-09-01T00:00:00+09:00')" > /dev/null; done
size=$(q "SELECT max(id) FROM product_sizes"); base=$(q "SELECT max(id)-2 FROM users")
S_=$base G_=$((base+1)) P_=$((base+2))
q "INSERT INTO point_ledger(user_id,kind,status,amount,remaining,expires_at,created_at) VALUES ($P_,'EARN','AVAILABLE',5000,5000,'2026-10-10T00:00:00+09:00','2026-09-02T00:00:00+09:00'),($P_,'EARN','AVAILABLE',2000,2000,'2026-10-05T00:00:00+09:00','2026-09-03T00:00:00+09:00'),($P_,'EARN','AVAILABLE',1000,1000,'2026-10-03T00:00:00+09:00','2026-09-04T00:00:00+09:00')" > /dev/null
q "INSERT INTO point_balances(user_id,available,pending,updated_at) VALUES ($P_,8000,0,'2026-09-04T00:00:00+09:00')" > /dev/null
mo=$(q "SELECT max(id) FROM orders"); mi=$(q "SELECT max(id) FROM order_items"); mp=$(q "SELECT max(id) FROM pg_transactions"); mt=$(q "SELECT max(id) FROM trades")
ml=$(q "SELECT max(id) FROM point_ledger"); ma=$(q "SELECT max(id) FROM point_allocations"); mh=$(q "SELECT max(id) FROM user_grade_history")

start_pg 3000
start_app 2000
trade() { call POST /bids "$1" "{\"productSizeId\":$size,\"side\":\"SELL\",\"price\":$3,\"validDays\":30}"; call POST /bids "$2" "{\"productSizeId\":$size,\"side\":\"BUY\",\"price\":$3,\"validDays\":30}"; }
last_trade() { q "SELECT max(id) FROM trades"; }
items() { q "SELECT string_agg(i.id::text, ' ' ORDER BY i.id) FROM order_items i JOIN orders o ON o.id=i.order_id WHERE o.id>$mo AND o.idempotency_key='$1'"; }
to_delivered() { call POST "/order-items/$1/shipment" $S_; call POST "/admin/order-items/$1/receipt"; call POST "/admin/order-items/$1/inspection" "" '{"result":"PASS"}'; call POST "/admin/order-items/$1/delivery"; }

ts=()
for p in 800000 810000 820000 830000; do trade $S_ $G_ $p; ts+=("$(last_trade)"); done
call POST /orders $G_ "{\"tradeIds\":[$(IFS=,; echo "${ts[*]}")]}" G1                  # 4항목 326만 원(BRONZE 1% 적립 예정)
trade $S_ $P_ 50000; call POST /orders $P_ "{\"tradeIds\":[$(last_trade)],\"pointToUse\":6000}" P1   # 만료 순: P3 1000 + P2 2000 + P1 3000 사용(배분)
read -r g1 g2 g3 g4 <<< "$(items G1)"
[ -n "$g4" ] || die "G1 항목이 4건이 아님"
for i in $g1 $g2 $g3 $g4; do to_delivered "$i"; done
call POST "/order-items/$g1/confirm" $G_                       # 바로 확정

adv 608400                                                      # 10-08 01:00 — 배송 + 7일 지남
mark buyer-late-confirm
call POST "/order-items/$g4/confirm" $G_                       # 7일 뒤 구매자 확정 → confirmed_at = delivered_at + 7일
mark auto-confirm
call POST /admin/batches/auto-confirm                          # g2·g3 + 시드 DELIVERED 항목(소급 확정·적립 확정)
call POST /admin/batches/auto-confirm                          # 대상 없음
call GET "/order-items/$g2" $G_

call PUT /admin/clock "" '{"now":"2026-10-11T00:00:00+09:00"}'
mark point-expiry
call POST /admin/batches/point-expiry                          # P1(배분 있음, 남은 2000)·시드 묶음
call POST /admin/batches/point-expiry                          # 대상 없음
call GET /me/points $P_

call PUT /admin/clock "" '{"now":"2026-11-01T00:00:00+09:00"}'
mark grade-recalc
call POST /admin/batches/grade-recalc                          # 기준일 11-01, 기간 [05-01, 11-01): g GOLD + 시드 사용자
call POST /admin/batches/grade-recalc                          # 같은 달 재실행: 이력이 있어 0
mark earn-after-grade
trade $S_ $G_ 100000; call POST /orders $G_ "{\"tradeIds\":[$(last_trade)]}" G2           # 11-01 주문 → GOLD 2% 적립 예정
g5=$(items G2); [[ $g5 =~ ^[0-9]+$ ]] || die "G2 항목 준비 실패: $g5"
to_delivered "$g5"; call POST "/order-items/$g5/confirm" $G_  # 적립 확정
call GET /me/points $G_

dump orders "SELECT id-$mo,buyer_id-$base,status,item_total,buyer_fee_total,shipping_fee,point_used,card_amount,idempotency_key,created_at,paid_at FROM orders WHERE id>$mo ORDER BY id"
dump order_items "SELECT id-$mi,order_id-$mo,trade_id-$mt,price,card_share,point_share,status,paid_at,shipped_at,inspected_at,delivered_at,confirmed_at FROM order_items WHERE id>$mi ORDER BY id"
dump pg_transactions "SELECT id-$mp,order_id-$mo,kind,amount,status,$(norm_pg_tx_id),idempotency_key,requested_at,responded_at FROM pg_transactions WHERE id>$mp ORDER BY id"
dump point_ledger "SELECT id-$ml,user_id,kind,status,amount,remaining,expires_at,order_item_id-$mi,order_id-$mo,created_at FROM point_ledger WHERE id>$ml-3 ORDER BY id"
dump point_allocations "SELECT id-$ma,entry_id-$ml,earn_entry_id-$ml,amount FROM point_allocations WHERE id>$ma ORDER BY id"
dump point_balances "SELECT user_id-$base,available,pending,updated_at FROM point_balances WHERE user_id>=$base ORDER BY user_id"
dump users "SELECT id-$base,grade,purchase_restricted_until,sale_restricted_until FROM users WHERE id>=$base ORDER BY id"
dump user_grade_history "SELECT id-$mh,user_id,grade,effective_from,basis_amount FROM user_grade_history WHERE id>$mh ORDER BY id"
# 시드 행(배치가 바꾼 것 포함): 정렬 덤프의 md5
{ q "SELECT id,status,confirmed_at FROM order_items WHERE id<=$mi ORDER BY id" | md5sum
  q "SELECT id,status,remaining,expires_at FROM point_ledger WHERE id<=$ml-3 ORDER BY id" | md5sum
  q "SELECT user_id,available,pending,updated_at FROM point_balances WHERE user_id<$base ORDER BY user_id" | md5sum
  q "SELECT id,grade FROM users WHERE id<$base ORDER BY id" | md5sum; } | dump_text seed.md5
q "SELECT status,count(*) FROM order_items WHERE id<=$mi GROUP BY status ORDER BY status" | dump_text seed_item_status
q "SELECT grade,count(*) FROM users WHERE id<$base GROUP BY grade ORDER BY grade" | dump_text seed_user_grade
app_end
