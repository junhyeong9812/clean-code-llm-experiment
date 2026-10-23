#!/usr/bin/env bash
# 시나리오 settle — 주간 정산(시드 전체, 좁히지 않음): 10-08에 09-28 주 1회(앞서 auto-confirm 수행, 미정산 패널티 연결, HELD) →
# 재실행 0 → 정산 조회 → 정산 뒤 분쟁 인정(CLAWBACK)·분쟁 기각(다음 주 INCLUDED)·이월(carry_in) → 10-19에 두 주(10-05·10-12) →
# 재실행 0 → 정산 조회. 픽스처 판매자 4명이 ADR-0004 §12 정산 예 S1~S4 모양을 만든다.
# 사용: scenarios/settle.sh <god|layered> <jar> <outdir>  (보통은 tools/equiv/run.sh settle)
# 시드 판매자의 새 정산 행·항목과 바뀐 시드 행은 정렬 덤프의 md5로, 픽스처 판매자의 행은 오프셋 줄 단위로 비교한다.
source "$(dirname "$0")/../lib.sh"
app_begin "$@"
start_db

# 픽스처: 카테고리별 새 사이즈(SHOES·APPAREL·BAGS·TECH), 판매자 s1~s4, 구매자 b
for c in SHOES APPAREL BAGS TECH; do q "INSERT INTO product_sizes(product_id,size) SELECT min(p.id),'EXP' FROM products p JOIN categories c ON c.id=p.category_id WHERE c.name='$c'" > /dev/null; done
read -r SHOES APPAREL BAGS TECH <<< "$(q "SELECT string_agg(id::text, ' ' ORDER BY id) FROM (SELECT id FROM product_sizes ORDER BY id DESC LIMIT 4) x")"
for u in s1 s2 s3 s4 b; do q "INSERT INTO users(email,name,grade,created_at) VALUES ('$u@exp','$u','BRONZE','2026-09-01T00:00:00+09:00')" > /dev/null; done
base=$(q "SELECT max(id)-4 FROM users"); S1=$base S2=$((base+1)) S3=$((base+2)) S4=$((base+3)) B_=$((base+4))
mo=$(q "SELECT max(id) FROM orders"); mi=$(q "SELECT max(id) FROM order_items"); mt=$(q "SELECT max(id) FROM trades")
ms=$(q "SELECT max(id) FROM settlements"); msi=$(q "SELECT max(id) FROM settlement_items"); mx=$(q "SELECT max(id) FROM seller_penalties"); mr=$(q "SELECT max(id) FROM refunds")
md=$(q "SELECT max(id) FROM disputes"); mn=$(q "SELECT max(id) FROM inspections"); mp=$(q "SELECT max(id) FROM pg_transactions"); ml=$(q "SELECT max(id) FROM point_ledger"); ma=$(q "SELECT max(id) FROM point_allocations")

# open_dispute <항목 id>: 이 시나리오에서 만든 그 항목의 OPEN 분쟁 id(없으면 실패 — 제기가 실패한 채 다른 분쟁을 판정하지 않게)
open_dispute() { local d; d=$(q "SELECT id FROM disputes WHERE id>$md AND order_item_id=$1 AND status='OPEN'"); [[ $d =~ ^[0-9]+$ ]] || die "분쟁 준비 실패: 항목 $1 → '$d'"; echo "$d"; }
# need <이름> <건수>: 시드 경로를 실제로 지났는지 — 0이면 실패(두 앱이 함께 0이어도 같음으로 끝나지 않게)
need() { [[ $2 =~ ^[0-9]+$ ]] && [ "$2" -gt 0 ] || die "경로를 지나지 않음: $1 = '$2'"; }
start_pg 3000
start_app 2000
# buy <판매자> <사이즈> <가격> <주문 키>: 체결 1건을 주문 1건으로 결제하고 항목 id를 변수 이름 = 키로 둔다
buy() {
  call POST /bids "$1" "{\"productSizeId\":$2,\"side\":\"SELL\",\"price\":$3,\"validDays\":30}"
  call POST /bids $B_ "{\"productSizeId\":$2,\"side\":\"BUY\",\"price\":$3,\"validDays\":30}"
  call POST /orders $B_ "{\"tradeIds\":[$(q "SELECT max(id) FROM trades")]}" "$4"
  printf -v "$4" '%s' "$(q "SELECT i.id FROM order_items i JOIN orders o ON o.id=i.order_id WHERE o.id>$mo AND o.idempotency_key='$4'")"
  [[ ${!4} =~ ^[0-9]+$ ]] || die "항목 준비 실패: $4=${!4}"
}
inspecting() { call POST "/order-items/$1/shipment" "$2"; call POST "/admin/order-items/$1/receipt"; }
delivered() { inspecting "$1" "$2"; call POST "/admin/order-items/$1/inspection" "" '{"result":"PASS"}'; call POST "/admin/order-items/$1/delivery"; }

# 10-01(목): 09-28 주에 확정·패널티
buy $S1 $SHOES 120000 A1; buy $S1 $APPAREL 45000 A2; buy $S1 $TECH 50000 A3     # S1: 확정 2건, A3은 배송만(정산이 자동 확정)
buy $S2 $SHOES 30000 B1; buy $S2 $TECH 30000 B2                                    # S2: B1 검수 불합격(패널티 3000), B2는 다음 주 확정
buy $S3 $SHOES 120000 C1                                                           # S3: 확정 → 정산 뒤 분쟁 인정(CLAWBACK)
buy $S4 $BAGS 60000 D1; buy $S4 $BAGS 40000 D2                                     # S4: 둘 다 분쟁 중(HELD) → D1만 기각
for i in $A1 $A2 $C1 $D1 $D2; do
  s=$(q "SELECT t.seller_id FROM order_items i JOIN trades t ON t.id=i.trade_id WHERE i.id=$i")
  delivered "$i" "$s"; call POST "/order-items/$i/confirm" $B_
done
delivered "$A3" $S1
inspecting "$B1" $S2; call POST "/admin/order-items/$B1/inspection" "" '{"result":"FAIL","reason":"오염"}'
inspecting "$B2" $S2; call POST "/admin/order-items/$B2/inspection" "" '{"result":"PASS"}'   # 발송 기한 안에 검수까지(배송은 10-08)
call POST "/order-items/$D1/disputes" $B_; dD1=$(open_dispute "$D1")
call POST "/order-items/$D2/disputes" $B_

call PUT /admin/clock "" '{"now":"2026-10-08T01:00:00+09:00"}'
mark settle-1
call POST /admin/batches/weekly-settlement                     # auto-confirm(시드·A3, 소급 10-08 00:00) 뒤 09-28 주(시드 판매자 + S1~S4)
call POST /admin/batches/weekly-settlement                     # 정산할 주 없음 0
n=$(q "SELECT count(*) FROM settlements WHERE id>$ms AND seller_id<$base"); need "시드 판매자 정산" "$n"
mark reads-1
for u in $S1 $S2 $S3 $S4 $B_; do call GET /me/settlements "$u"; done   # b는 판매 없음 []
mark after-settle-1
call POST "/order-items/$C1/disputes" $B_; dC1=$(open_dispute "$C1")
call POST "/admin/disputes/$dC1/resolution" "" '{"accept":true}'   # 정산된 항목 분쟁 인정
call POST "/admin/disputes/$dD1/resolution" "" '{"accept":false}'                    # D1 기각 → CONFIRMED(confirmed_at 그대로)
call POST "/admin/order-items/$B2/delivery"; call POST "/order-items/$B2/confirm" $B_  # 10-08 배송·확정(10-05 주, 이월 −3000과 상계)

call PUT /admin/clock "" '{"now":"2026-10-19T00:00:00+09:00"}'
mark settle-2
call POST /admin/batches/weekly-settlement                     # 10-05 주·10-12 주(오래된 주부터, 주마다 트랜잭션 하나)
call POST /admin/batches/weekly-settlement                     # 0
mark reads-2
for u in $S1 $S2 $S3 $S4; do call GET /me/settlements "$u"; done

dump order_items "SELECT id-$mi,order_id-$mo,price,status,delivered_at,confirmed_at,cancelled_at FROM order_items WHERE id>$mi ORDER BY id"
dump settlements "SELECT id-$ms,seller_id-$base,period_start,period_end,gross,fee_total,penalty_total,carry_in,net,payout,carry_out,status,paid_at FROM settlements WHERE id>$ms AND seller_id>=$base ORDER BY id"
dump settlement_items "SELECT si.id-$msi,si.settlement_id-$ms,si.order_item_id-$mi,si.amount,si.fee,si.status FROM settlement_items si JOIN settlements s ON s.id=si.settlement_id WHERE si.id>$msi AND s.seller_id>=$base ORDER BY si.id"
dump seller_penalties "SELECT id-$mx,seller_id-$base,order_item_id-$mi,amount,settlement_id-$ms,created_at FROM seller_penalties WHERE id>$mx ORDER BY id"
dump refunds "SELECT id-$mr,order_item_id-$mi,card_amount,point_amount,shipping_amount,reason,fault,created_at FROM refunds WHERE id>$mr ORDER BY id"
dump orders "SELECT id-$mo,status,item_total,shipping_fee,point_used,card_amount,idempotency_key,paid_at FROM orders WHERE id>$mo ORDER BY id"
dump pg_transactions "SELECT id-$mp,order_id-$mo,kind,amount,status,$(norm_pg_tx_id),idempotency_key,requested_at,responded_at FROM pg_transactions WHERE id>$mp ORDER BY id"
dump inspections "SELECT id-$mn,order_item_id-$mi,result,reason,inspected_at FROM inspections WHERE id>$mn ORDER BY id"
dump disputes "SELECT id-$md,order_item_id-$mi,status,opened_at,resolved_at FROM disputes WHERE id>$md ORDER BY id"
dump point_ledger "SELECT id-$ml,user_id,kind,status,amount,remaining,expires_at,order_item_id-$mi,order_id-$mo,created_at FROM point_ledger WHERE id>$ml ORDER BY id"
dump point_allocations "SELECT id-$ma,entry_id-$ml,earn_entry_id-$ml,amount FROM point_allocations WHERE id>$ma ORDER BY id"
dump point_balances "SELECT user_id-$base,available,pending,updated_at FROM point_balances WHERE user_id>=$base ORDER BY user_id"
dump settlement_weeks "SELECT period_start,count(*),sum(gross),sum(fee_total),sum(penalty_total),sum(carry_in),sum(net),sum(payout),sum(carry_out) FROM settlements WHERE id>$ms GROUP BY period_start ORDER BY period_start"
# 시드 판매자의 새 정산 행·항목, 정산에 붙은 시드 패널티, 자동 확정된 시드 항목·적립·잔액: 정렬 덤프의 md5
{ q "SELECT id-$ms,seller_id,period_start,gross,fee_total,penalty_total,carry_in,net,payout,carry_out,status,paid_at FROM settlements WHERE id>$ms AND seller_id<$base ORDER BY id" | md5sum
  q "SELECT si.id-$msi,si.settlement_id-$ms,si.order_item_id,si.amount,si.fee,si.status FROM settlement_items si JOIN settlements s ON s.id=si.settlement_id WHERE si.id>$msi AND s.seller_id<$base ORDER BY si.id" | md5sum
  q "SELECT id,settlement_id-$ms FROM seller_penalties WHERE id<=$mx ORDER BY id" | md5sum
  q "SELECT id,status,confirmed_at FROM order_items WHERE id<=$mi ORDER BY id" | md5sum
  q "SELECT id,status,remaining,expires_at FROM point_ledger WHERE id<=$ml ORDER BY id" | md5sum
  q "SELECT user_id,available,pending,updated_at FROM point_balances WHERE user_id<$base ORDER BY user_id" | md5sum; } | dump_text seed.md5
q "SELECT count(*) FROM settlements WHERE id>$ms AND seller_id<$base" | dump_text seed_settlement_count
app_end
