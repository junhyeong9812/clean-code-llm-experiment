#!/usr/bin/env bash
# 시나리오 sa-b — 단계 A 묶음 B(ADR-0007 §7 A4 · §8 A5 · §12.2 A11):
#   A5 위시리스트 등록·중복·삭제·조회(bestSell·activeAlert), 가격 알림 등록(구매 제한 중 허용)·중복·취소·조회,
#   price-alert-scan(TRIGGERED = 알림 INSERT → 가격 알림 UPDATE · 만료 EXPIRED · 구매 제한 중 그대로 · 맞는 입찰 없음 그대로),
#   A4 deadline-reminder 6종(PAYMENT_DUE_SOON·SETTLEMENT_PAID·PURCHASE/SALE_RESTRICTED → SHIPMENT_DUE_SOON → DISPUTE_WINDOW_CLOSING,
#   각 재실행 0 = dedupe, 제한 연장 뒤 새 <E>의 알림), 알림 조회(unread)·읽음, A11 배송 추적 기록·조회(오프셋 없는 시각 400 등),
#   delivery-delay-scan(SHIPMENT_OVERDUE → DELIVERY_LATE, 각 재실행 0). 오류 경로 400·401·403·404·409.
#   각 배치는 첫 실행 failed 0 · 같은 시각 재실행 processed 0·failed 0까지 단언한다. price-alert-scan은 건별 격리(§2.6)도 밟는다 —
#   TRIGGERED 대상 알림의 UPDATE만 트리거로 실패시켜 200 failed 1(알림 INSERT도 롤백)·만료 처리 → 트리거를 지우고 재실행하면 그 알림만 processed 1.
# 사용: scenarios/sa-b.sh <god|layered> <jar> <outdir>  (보통은 tools/equiv/run.sh sa-b)
# 배치는 시드 전체가 대상이다(시드 체결·항목·정산·제한 사용자·ACTIVE 가격 알림). 새 행은 오프셋 줄 단위, 바뀐 시드 행은 md5로 비교한다.
source "$(dirname "$0")/../lib.sh"
app_begin "$@"
start_db

# 픽스처: 새 사이즈 3개(위시·알림 EXP·EXP2, 체결 EXP3), 사용자 s(판매)·b(구매)·c(제3자)·r(구매 제한 10-20까지)·x(판매 제한 10-25까지)
for z in EXP EXP2 EXP3; do q "INSERT INTO product_sizes(product_id,size) SELECT min(id),'$z' FROM products" > /dev/null; done
for u in s b c; do q "INSERT INTO users(email,name,grade,created_at) VALUES ('$u@exp','$u','BRONZE','2026-09-01T00:00:00+09:00')" > /dev/null; done
q "INSERT INTO users(email,name,grade,purchase_restricted_until,created_at) VALUES ('r@exp','r','BRONZE','2026-10-20T00:00:00+09:00','2026-09-01T00:00:00+09:00')" > /dev/null
q "INSERT INTO users(email,name,grade,sale_restricted_until,created_at) VALUES ('x@exp','x','BRONZE','2026-10-25T00:00:00.5+09:00','2026-09-01T00:00:00+09:00')" > /dev/null
Z3=$(q "SELECT max(id) FROM product_sizes"); Z2=$((Z3-1)) Z1=$((Z3-2)); base=$(q "SELECT max(id)-4 FROM users")
S_=$base B_=$((base+1)) C_=$((base+2)) R_=$((base+3)) X_=$((base+4))
mo=$(q "SELECT max(id) FROM orders"); mi=$(q "SELECT max(id) FROM order_items"); mt=$(q "SELECT max(id) FROM trades"); mb=$(q "SELECT max(id) FROM bids")
mn=$(q "SELECT max(id) FROM notifications"); mw=$(q "SELECT max(id) FROM wishlists"); ma=$(q "SELECT max(id) FROM price_alerts")
me=$(q "SELECT max(id) FROM shipment_tracking_events"); mdl=$(q "SELECT coalesce(max(id),0) FROM shipment_delays"); mu=$(q "SELECT max(id) FROM admin_audit_logs")
ms=$(q "SELECT max(id) FROM settlements")
# 지급 알림용 정산(10-01 00:10 지급) · 만료된 ACTIVE 가격 알림(b, EXP2 — 09-30 만료)
q "INSERT INTO settlements(seller_id,period_start,period_end,gross,fee_total,penalty_total,carry_in,net,payout,carry_out,status,paid_at) VALUES
  ($S_,'2026-09-21','2026-09-27',100000,5000,0,0,95000,95000,0,'PAID','2026-10-01T00:10:00+09:00')" > /dev/null
q "INSERT INTO price_alerts(user_id,product_size_id,target_price,status,expires_at,created_at) VALUES ($B_,$Z2,70000,'ACTIVE','2026-09-30T00:00:00+09:00','2026-08-01T00:00:00+09:00')" > /dev/null
A_exp=$((ma+1))

# need <이름> <값> [기대]: 경로를 실제로 지났는지. 기대가 없으면 양의 정수여야 한다(두 앱이 함께 지나치지 않았는데 같음으로 끝나지 않게)
need() {
  if [ $# -ge 3 ]; then [ "$2" = "$3" ] || die "경로를 지나지 않음: $1 = '$2' (기대 '$3')"
  else [[ $2 =~ ^[0-9]+$ ]] && [ "$2" -gt 0 ] || die "경로를 지나지 않음: $1 = '$2'"; fi
}
nk() { q "SELECT count(*) FROM notifications WHERE id>$mn AND kind='$1' AND ref_id=$2"; }   # 이 실행에서 만든 그 대상의 알림 수
start_pg 3000
start_app 2000
# 실행(failed 0) + 같은 시각 재실행(대상 없음 — processed 0·failed 0)
batch2() { batch_run "$1" '^[0-9]+ 0 200$'; batch_run "$1" '^0 0 200$'; }

mark wishlist
call POST /me/wishlist "" "{\"productSizeId\":$Z1}"            # 401
call POST /me/wishlist $B_ '{}'                                # 400
call POST /me/wishlist $B_ "{\"productSizeId\":\"$Z1\"}"       # 문자열 400
call POST /me/wishlist $B_ '{"productSizeId":999999999}'       # 404
call POST /me/wishlist $B_ "{\"productSizeId\":$Z1}"           # 201
call POST /me/wishlist $B_ "{\"productSizeId\":$Z1}"           # 중복 409
call POST /me/wishlist $B_ "{\"productSizeId\":$Z2}"           # 201
call DELETE "/me/wishlist/$Z2" $B_                             # 200
call DELETE "/me/wishlist/$Z2" $B_                             # 이미 지움 404
call DELETE /me/wishlist/999999999 $B_                         # 404
call DELETE "/me/wishlist/$Z1" $C_                             # c의 위시리스트에 없음 404
call GET /me/wishlist $B_
mark price-alert
call POST /me/price-alerts $B_ "{\"productSizeId\":$Z1,\"targetPrice\":0}"        # 400
call POST /me/price-alerts $B_ "{\"productSizeId\":$Z1,\"targetPrice\":\"1000\"}" # 400
call POST /me/price-alerts $B_ "{\"productSizeId\":$Z1}"                          # 누락 400
call POST /me/price-alerts $B_ '{"productSizeId":999999999,"targetPrice":1000}'  # 404
call POST /me/price-alerts $B_ "{\"productSizeId\":$Z1,\"targetPrice\":90000}"    # 201 (TRIGGERED 대상)
call POST /me/price-alerts $B_ "{\"productSizeId\":$Z1,\"targetPrice\":95000}"    # ACTIVE 중복 409
call POST /me/price-alerts $B_ "{\"productSizeId\":$Z2,\"targetPrice\":95000}"    # 만료 시각이 지났어도 ACTIVE면 409
call POST /me/price-alerts $C_ "{\"productSizeId\":$Z2,\"targetPrice\":1000}"     # 201 → 취소
a_c=$(q "SELECT id FROM price_alerts WHERE id>$ma AND user_id=$C_ AND status='ACTIVE'"); [[ $a_c =~ ^[0-9]+$ ]] || die "알림 준비 실패: $a_c"
call DELETE "/me/price-alerts/$a_c" $B_                        # 남의 것 403
call DELETE "/me/price-alerts/$a_c" $C_                        # 200 CANCELLED
call DELETE "/me/price-alerts/$a_c" $C_                        # 409
call DELETE /me/price-alerts/999999999 $C_                     # 404
call POST /me/price-alerts $C_ "{\"productSizeId\":$Z2,\"targetPrice\":1000}"     # 취소 뒤 다시 201 (맞는 입찰 없음 → 그대로)
a_c2=$(q "SELECT id FROM price_alerts WHERE id>$ma AND user_id=$C_ AND status='ACTIVE'"); int_or_die a_c2 "$a_c2"
call POST /me/price-alerts $R_ "{\"productSizeId\":$Z1,\"targetPrice\":100000}"   # 구매 제한 중에도 201 (스캔에서 그대로)
a_b=$(q "SELECT id FROM price_alerts WHERE id>$ma AND user_id=$B_ AND product_size_id=$Z1"); a_r=$(q "SELECT id FROM price_alerts WHERE id>$ma AND user_id=$R_")
int_or_die a_b "$a_b"; int_or_die a_r "$a_r"
call GET /me/price-alerts $B_; call GET /me/price-alerts $C_
mark book
call POST /bids $S_ "{\"productSizeId\":$Z1,\"side\":\"SELL\",\"price\":88000,\"validDays\":30}"
call POST /bids $S_ "{\"productSizeId\":$Z1,\"side\":\"SELL\",\"price\":85000,\"validDays\":30}"
call POST /bids $S_ "{\"productSizeId\":$Z1,\"side\":\"SELL\",\"price\":85000,\"validDays\":30}"   # 같은 값 — created_at·id 순 첫 번째가 뽑힌다
bid_t=$(q "SELECT min(id) FROM bids WHERE id>$mb AND price=85000")
call GET /me/wishlist $B_                                      # bestSell 85000 + activeAlert
mark price-alert-scan-fail
inject_on price_alerts "BEFORE UPDATE" "OLD.id = $a_b"
batch_run price-alert-scan '^[1-9][0-9]* 1 200$'               # a_b만 실패(알림 INSERT까지 롤백), 만료 알림은 처리 → failed 1
need "격리 실패 뒤 a_b" "$(q "SELECT status FROM price_alerts WHERE id=$a_b")" ACTIVE
need "격리 실패 뒤 PRICE_ALERT 알림 없음" "$(nk PRICE_ALERT "$a_b")" 0
need "격리 실패 중 만료" "$(q "SELECT status FROM price_alerts WHERE id=$A_exp")" EXPIRED
inject_off price_alerts
mark price-alert-scan
batch_run price-alert-scan '^1 0 200$'                         # 트리거 제거 뒤 a_b만 남음 → TRIGGERED
batch_run price-alert-scan '^0 0 200$'                         # 같은 시각 재실행 0건
need "TRIGGERED" "$(q "SELECT status||' '||triggered_bid_id FROM price_alerts WHERE id=$a_b")" "TRIGGERED $bid_t"
need "PRICE_ALERT 알림" "$(nk PRICE_ALERT "$a_b")" 1
need "구매 제한 중 그대로" "$(q "SELECT status FROM price_alerts WHERE id=$a_r")" ACTIVE
need "맞는 입찰 없음 그대로" "$(q "SELECT status FROM price_alerts WHERE id=$a_c2")" ACTIVE
# TRIGGERED 1건의 트랜잭션은 알림 INSERT → 가격 알림 UPDATE 순서다. DB 문장 로그(바인드 값 DETAIL 포함)의 price-alert-scan 표식 구간에서,
# 바인드 dedupe 키가 'PRICE_ALERT:<a_b>'인 notifications INSERT 바로 다음 앱 문장이 price_alerts UPDATE이고 그 바인드가
# 'TRIGGERED'·마지막 값(WHERE id) = a_b인지 본다 — 다른 알림의 문장이나 만료 UPDATE로는 맞지 않는다. 순서는 두 앱 비교로도 남는다.
pgl=$(docker logs "$EQ_DB" 2>&1)
ord=$(awk -v a="$a_b" '
  index($0, "psql|LOG:  statement: SELECT '\''MARK ") == 1 { f = (index($0, "'\''MARK price-alert-scan'\''") > 0); next }
  !f || index($0, "PostgreSQL JDBC Driver|") != 1 { next }
  { s = substr($0, 24) }
  s ~ /^DETAIL:  Parameters: / {
    if (st == "ins" && index(s, "'\''PRICE_ALERT:" a "'\''")) st = "ins-a"
    else if (st == "upd" && index(s, "'\''TRIGGERED'\''") && s ~ ("= '\''" a "'\''$")) ok = 1
    next }
  { st = (st == "ins-a" && s ~ /update price_alerts/) ? "upd" : (s ~ /insert into notifications/) ? "ins" : "" }
  END { print ok ? "insert-then-update" : "없음" }' <<< "$pgl")
need "TRIGGERED 문장 순서(알림 $a_b)" "$ord" insert-then-update
call GET /me/price-alerts $B_; call GET /me/wishlist $B_; call GET /me/price-alerts $R_

mark tracking-fixtures
trade() { call POST /bids $S_ "{\"productSizeId\":$Z3,\"side\":\"SELL\",\"price\":$1,\"validDays\":30}"; call POST /bids $B_ "{\"productSizeId\":$Z3,\"side\":\"BUY\",\"price\":$1,\"validDays\":30}"; }
last_trade() { q "SELECT max(id) FROM trades"; }
item_of() { q "SELECT i.id FROM order_items i JOIN orders o ON o.id=i.order_id WHERE o.id>$mo AND o.idempotency_key='$1'"; }
trade 30000; T_pay=$(last_trade)                               # 결제하지 않는 체결(PAYMENT_DUE_SOON)
trade 31000; call POST /orders $B_ "{\"tradeIds\":[$(last_trade)]}" K-SHIP; I_ship=$(item_of K-SHIP)   # PAID로 둔다
trade 32000; call POST /orders $B_ "{\"tradeIds\":[$(last_trade)]}" K-TRK; I_trk=$(item_of K-TRK)
trade 33000; call POST /orders $B_ "{\"tradeIds\":[$(last_trade)]}" K-CONF; I_conf=$(item_of K-CONF)
[[ $I_ship =~ ^[0-9]+$ && $I_trk =~ ^[0-9]+$ && $I_conf =~ ^[0-9]+$ ]] || die "항목 준비 실패: $I_ship $I_trk $I_conf"
call POST "/order-items/$I_trk/shipment" $S_                   # SHIPPED_TO_CENTER
call POST "/order-items/$I_conf/shipment" $S_; call POST "/admin/order-items/$I_conf/receipt"; call POST "/admin/order-items/$I_conf/inspection" "" '{"result":"PASS"}'
call POST "/admin/order-items/$I_conf/delivery"; call POST "/order-items/$I_conf/confirm" $B_
adv 600
mark tracking
ev() { call POST "/admin/order-items/$1/tracking-events" "" "$2"; }
ev "$I_trk" '{"carrier":"cj","trackingNo":"T1","eventType":"PICKED_UP","occurredAt":"2026-10-01T00:05:00+09:00"}'          # enum 대소문자 400
ev "$I_trk" '{"carrier":"CJ","trackingNo":"  ","eventType":"PICKED_UP","occurredAt":"2026-10-01T00:05:00+09:00"}'          # 빈 송장 400
ev "$I_trk" '{"carrier":"CJ","trackingNo":"1234567890123456789012345678901","eventType":"PICKED_UP","occurredAt":"2026-10-01T00:05:00+09:00"}'   # 31자 400
ev "$I_trk" '{"carrier":"CJ","trackingNo":"T1","eventType":"LOST","occurredAt":"2026-10-01T00:05:00+09:00"}'               # eventType 400
ev "$I_trk" '{"carrier":"CJ","trackingNo":"T1","eventType":"PICKED_UP","occurredAt":"2026-10-01T00:05:00"}'                # 오프셋 없음 400
ev "$I_trk" '{"carrier":"CJ","trackingNo":"T1","eventType":"PICKED_UP","occurredAt":"2026-10-01T00:10:01+09:00"}'          # 미래 400
ev "$I_trk" '{"carrier":"CJ","trackingNo":"T1","eventType":"PICKED_UP"}'                                                    # 누락 400
ev 999999999 '{"carrier":"CJ","trackingNo":"T1","eventType":"PICKED_UP","occurredAt":"2026-10-01T00:05:00+09:00"}'         # 404
ev "$I_ship" '{"carrier":"CJ","trackingNo":"T1","eventType":"PICKED_UP","occurredAt":"2026-10-01T00:05:00+09:00"}'         # PAID 409
ev "$I_trk" '{"carrier":"CJ","trackingNo":" T1 ","eventType":"IN_TRANSIT","occurredAt":"2026-10-01T00:08:00+09:00"}'       # 201
ev "$I_trk" '{"carrier":"HANJIN","trackingNo":"T1","eventType":"PICKED_UP","occurredAt":"2026-10-01T00:10:00+09:00"}'      # 201 (현재 정각)
ev "$I_trk" '{"carrier":"POST","trackingNo":"T1","eventType":"PICKED_UP","occurredAt":"2026-09-30T23:00:00+09:00"}'        # 201 (가장 이른 시각)
need "추적 이벤트" "$(q "SELECT count(*) FROM shipment_tracking_events WHERE id>$me")" 3
call POST "/admin/order-items/$I_trk/receipt"; call POST "/admin/order-items/$I_trk/inspection" "" '{"result":"PASS"}'   # DELIVERING, inspected_at 00:10
ev "$I_trk" '{"carrier":"LOTTE","trackingNo":"T2","eventType":"OUT_FOR_DELIVERY","occurredAt":"2026-10-01T00:10:00+09:00"}' # DELIVERING 201 (같은 시각은 id 순)
call GET "/order-items/$I_trk/tracking" $B_; call GET "/order-items/$I_trk/tracking" $S_
call GET "/order-items/$I_trk/tracking" $C_                    # 403
call GET /order-items/999999999/tracking $B_                   # 404

call PUT /admin/clock "" '{"now":"2026-10-01T00:21:00+09:00"}'
mark reminder-1
batch2 deadline-reminder                                       # PAYMENT_DUE_SOON(T_pay) · SETTLEMENT_PAID · PURCHASE/SALE_RESTRICTED + 시드
need "PAYMENT_DUE_SOON" "$(nk PAYMENT_DUE_SOON "$T_pay")" 1
need "SETTLEMENT_PAID" "$(nk SETTLEMENT_PAID $((ms+1)))" 1
need "PURCHASE_RESTRICTED" "$(nk PURCHASE_RESTRICTED $R_)" 1
need "SALE_RESTRICTED 키(초 내림)" "$(q "SELECT dedupe_key FROM notifications WHERE id>$mn AND kind='SALE_RESTRICTED' AND ref_id=$X_")" "SALE_RESTRICTED:$X_:$(q "SELECT floor(extract(epoch FROM timestamptz '2026-10-25T00:00:00+09:00'))::bigint")"
q "UPDATE users SET purchase_restricted_until='2026-10-22T00:00:00+09:00' WHERE id=$R_" > /dev/null   # 제한 연장 → 새 <E>
mark reminder-extended
batch2 deadline-reminder                                       # r만 새 알림
need "연장 뒤 PURCHASE_RESTRICTED" "$(nk PURCHASE_RESTRICTED $R_)" 2

call PUT /admin/clock "" '{"now":"2026-10-03T01:00:00+09:00"}'
mark reminder-2
batch2 deadline-reminder                                       # SHIPMENT_DUE_SOON(I_ship) + 시드
need "SHIPMENT_DUE_SOON" "$(nk SHIPMENT_DUE_SOON "$I_ship")" 1
call PUT /admin/clock "" '{"now":"2026-10-04T01:00:00+09:00"}'
mark delay-1
batch2 delivery-delay-scan                                     # SHIPMENT_OVERDUE(I_ship) + 시드
need "SHIPMENT_OVERDUE" "$(q "SELECT count(*) FROM shipment_delays WHERE id>$mdl AND order_item_id=$I_ship AND kind='SHIPMENT_OVERDUE'")" 1
call PUT /admin/clock "" '{"now":"2026-10-06T00:10:00+09:00"}'
mark delay-2
batch2 delivery-delay-scan                                     # DELIVERY_LATE(I_trk, inspected_at + 5일 정각) + 시드
need "DELIVERY_LATE" "$(q "SELECT count(*) FROM shipment_delays WHERE id>$mdl AND order_item_id=$I_trk AND kind='DELIVERY_LATE'")" 1
call GET "/order-items/$I_trk/tracking" $B_; call GET "/order-items/$I_ship/tracking" $S_
call PUT /admin/clock "" '{"now":"2026-10-13T00:10:00+09:00"}'
mark reminder-3
batch2 deadline-reminder                                       # DISPUTE_WINDOW_CLOSING(I_conf, confirmed_at + 12일) + 시드
need "DISPUTE_WINDOW_CLOSING" "$(nk DISPUTE_WINDOW_CLOSING "$I_conf")" 1
need "시드 DISPUTE_WINDOW_CLOSING" "$(q "SELECT count(*) FROM notifications WHERE id>$mn AND kind='DISPUTE_WINDOW_CLOSING' AND ref_id<=$mi")"

mark notifications
call GET /me/notifications $B_
call GET "/me/notifications?unread=true" $B_
call GET "/me/notifications?unread=TRUE" $B_                   # 400
call GET "/me/notifications?unread=" $B_                       # 400
call GET /me/notifications                                     # 401
n_b=$(q "SELECT min(id) FROM notifications WHERE id>$mn AND user_id=$B_"); [[ $n_b =~ ^[0-9]+$ ]] || die "알림 없음: $n_b"
call POST "/me/notifications/$n_b/read" $C_                    # 403
call POST "/me/notifications/$n_b/read" $B_                    # 200
call POST "/me/notifications/$n_b/read" $B_                    # 이미 읽음 409
call POST /me/notifications/999999999/read $B_                 # 404
call GET "/me/notifications?unread=true" $B_; call GET "/me/notifications?unread=false" $B_; call GET /me/notifications $R_
mark audit
call GET "/admin/audit-logs?after=$mu&action=TRACKING_EVENT"

dump notifications "SELECT id-$mn,user_id,kind,ref_id,dedupe_key,created_at,read_at FROM notifications WHERE id>$mn ORDER BY id"
dump wishlists "SELECT id-$mw,user_id-$base,product_size_id-$Z1,created_at FROM wishlists WHERE id>$mw ORDER BY id"
dump price_alerts "SELECT id-$ma,user_id-$base,product_size_id-$Z1,target_price,status,expires_at,triggered_bid_id-$mb,closed_at,created_at FROM price_alerts WHERE id>$ma ORDER BY id"
dump shipment_tracking_events "SELECT id-$me,order_item_id-$mi,carrier,tracking_no,event_type,occurred_at,received_at FROM shipment_tracking_events WHERE id>$me ORDER BY id"
dump shipment_delays "SELECT id-$mdl,order_item_id,kind,detected_at FROM shipment_delays WHERE id>$mdl ORDER BY id"
dump admin_audit_logs "SELECT id-$mu,action,target_id-$me,detail,created_at FROM admin_audit_logs WHERE id>$mu ORDER BY id"
dump order_items "SELECT id-$mi,status,paid_at,shipped_at,inspected_at,delivered_at,confirmed_at FROM order_items WHERE id>$mi ORDER BY id"
dump trades "SELECT id-$mt,status,matched_at,payment_due_at FROM trades WHERE id>$mt ORDER BY id"
# 시드 행(배치가 바꿀 수 있는 것): 정렬 덤프의 md5
{ q "SELECT id,status,triggered_bid_id,closed_at FROM price_alerts WHERE id<=$ma ORDER BY id" | md5sum
  q "SELECT id,read_at FROM notifications WHERE id<=$mn ORDER BY id" | md5sum
  q "SELECT id,status FROM order_items WHERE id<=$mi ORDER BY id" | md5sum; } | dump_text seed.md5
app_end
