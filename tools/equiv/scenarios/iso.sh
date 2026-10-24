#!/usr/bin/env bash
# 시나리오 iso — 배치 대상 1건 실패의 격리(ADR-0003 §12의 2, ADR-0004 §15의 1): 트리거로 대상 A 하나만 실패시킨다.
# point-expiry·auto-confirm(단독)·bid-expiry는 A만 롤백하고 B를 처리한 뒤 200 failed=1, 트리거를 지우면 A를 처리한다.
# weekly-settlement는 격리하지 않는다: 앞 auto-confirm이 A에서 실패하면 400 UNEXPECTED_ERROR(고정 문구)이고 정산 행이 생기지 않는다.
# 사용: scenarios/iso.sh <god|layered> <jar> <outdir>  (보통은 tools/equiv/run.sh iso)
# 배치는 시드 전체가 대상이다. 바뀐 시드 행은 정렬 덤프의 md5로 비교한다.
source "$(dirname "$0")/../lib.sh"
app_begin "$@"
start_db

# 픽스처: 새 사이즈 1개, 사용자 s(판매)·b1·b2(구매 입찰만)·c1·c2(구매 — 주문 하나씩)·p(포인트: A 3000·B 2000, 10-02 만료), 주입용 트리거 함수
q "INSERT INTO product_sizes(product_id,size) SELECT min(id),'EXP' FROM products" > /dev/null
for u in s b1 b2 c1 c2 p; do q "INSERT INTO users(email,name,grade,created_at) VALUES ('$u@exp','$u','BRONZE','2026-09-01T00:00:00+09:00')" > /dev/null; done
size=$(q "SELECT max(id) FROM product_sizes"); base=$(q "SELECT max(id)-5 FROM users")
S_=$base B1_=$((base+1)) B2_=$((base+2)) C1_=$((base+3)) C2_=$((base+4)) P_=$((base+5))
mb=$(q "SELECT max(id) FROM bids"); mo=$(q "SELECT max(id) FROM orders"); mi=$(q "SELECT max(id) FROM order_items"); mt=$(q "SELECT max(id) FROM trades")
ml=$(q "SELECT max(id) FROM point_ledger"); ma=$(q "SELECT max(id) FROM point_allocations"); ms=$(q "SELECT coalesce(max(id),0) FROM settlements")
q "INSERT INTO point_ledger(user_id,kind,status,amount,remaining,expires_at,created_at) VALUES ($P_,'EARN','AVAILABLE',3000,3000,'2026-10-02T00:00:00+09:00','2026-09-02T00:00:00+09:00'),($P_,'EARN','AVAILABLE',2000,2000,'2026-10-02T00:00:00+09:00','2026-09-03T00:00:00+09:00')" > /dev/null
q "INSERT INTO point_balances(user_id,available,pending,updated_at) VALUES ($P_,5000,0,'2026-09-03T00:00:00+09:00')" > /dev/null
pa=$((ml+1))
qs <<'SQL'
CREATE FUNCTION fail_inject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected by %', TG_NAME; END $$;
SQL

# need <이름> <값> <기대>: 실패 주입이 실제로 그 경로를 지났는지 — 두 앱이 함께 지나치지 않았는데 같음으로 끝나지 않게
need() { [ "$2" = "$3" ] || die "경로를 지나지 않음: $1 = '$2' (기대 '$3')"; }
start_pg 3000
start_app 2000
last() { q "SELECT max(id) FROM $1"; }
item_of() { q "SELECT i.id FROM order_items i JOIN orders o ON o.id=i.order_id WHERE o.id>$mo AND o.idempotency_key='$1'"; }
to_delivered() { call POST "/order-items/$1/shipment" $S_; call POST "/admin/order-items/$1/receipt"; call POST "/admin/order-items/$1/inspection" "" '{"result":"PASS"}'; call POST "/admin/order-items/$1/delivery"; }

mark fixtures
call POST /bids $B1_ "{\"productSizeId\":$size,\"side\":\"BUY\",\"price\":10000,\"validDays\":30}"; bid_a=$(last bids)
call POST /bids $B2_ "{\"productSizeId\":$size,\"side\":\"BUY\",\"price\":11000,\"validDays\":30}"; bid_b=$(last bids)
[ "$bid_a" -gt "$mb" ] && [ "$bid_b" -gt "$bid_a" ] || die "입찰 준비 실패: $bid_a $bid_b"   # 만료 10-31
for c in $C1_ $C2_; do
  call POST /bids $S_ "{\"productSizeId\":$size,\"side\":\"SELL\",\"price\":50000,\"validDays\":30}"
  call POST /bids $c "{\"productSizeId\":$size,\"side\":\"BUY\",\"price\":50000,\"validDays\":30}"
  call POST /orders $c "{\"tradeIds\":[$(last trades)]}" "K$c"
done
item_a=$(item_of "K$C1_"); item_b=$(item_of "K$C2_")
[[ $item_a =~ ^[0-9]+$ && $item_b =~ ^[0-9]+$ ]] || die "주문 항목 준비 실패: $item_a $item_b"
to_delivered "$item_a"; to_delivered "$item_b"                 # delivered_at = 10-01 → 자동 확정 기한 10-08

call PUT /admin/clock "" '{"now":"2026-10-03T00:00:00+09:00"}'
mark point-expiry-fail
q "CREATE TRIGGER inject_point BEFORE UPDATE ON point_ledger FOR EACH ROW WHEN (OLD.id = $pa) EXECUTE FUNCTION fail_inject()" > /dev/null
call POST /admin/batches/point-expiry                          # 묶음 A 실패, 같은 사용자의 묶음 B 소멸
need "묶음 A 실패 뒤 remaining" "$(q "SELECT remaining FROM point_ledger WHERE id=$pa")" 3000
q "DROP TRIGGER inject_point ON point_ledger" > /dev/null
mark point-expiry-retry
call POST /admin/batches/point-expiry
need "묶음 A 재실행 뒤 remaining" "$(q "SELECT remaining FROM point_ledger WHERE id=$pa")" 0   # A가 대상이었음
call GET /me/points $P_

call PUT /admin/clock "" '{"now":"2026-10-09T00:00:00+09:00"}'
mark auto-confirm-fail
q "CREATE TRIGGER inject_item BEFORE UPDATE ON order_items FOR EACH ROW WHEN (OLD.id = $item_a) EXECUTE FUNCTION fail_inject()" > /dev/null
call POST /admin/batches/auto-confirm                          # 항목 A 실패, B·시드 DELIVERED 항목 확정
mark settlement-stop
call POST /admin/batches/weekly-settlement                     # 앞 auto-confirm이 A에서 실패 → 400, 정산하지 않음
need "정산 행(실패 뒤)" "$(q "SELECT count(*) FROM settlements WHERE id>$ms")" 0
q "DROP TRIGGER inject_item ON order_items" > /dev/null
mark settlement-retry
call POST /admin/batches/weekly-settlement                     # A 확정 → 마감 10-05 주 정산
call POST /admin/batches/auto-confirm                          # 대상 없음
need "항목 A 확정" "$(q "SELECT status FROM order_items WHERE id=$item_a")" CONFIRMED

call PUT /admin/clock "" '{"now":"2026-11-01T00:00:00+09:00"}'
mark bid-expiry-fail
q "CREATE TRIGGER inject_bid BEFORE UPDATE ON bids FOR EACH ROW WHEN (OLD.id = $bid_a) EXECUTE FUNCTION fail_inject()" > /dev/null
call POST /admin/batches/bid-expiry                            # A 실패(롤백), B·시드 입찰 만료 → failed 1
need "bid A 실패 뒤 상태" "$(q "SELECT status FROM bids WHERE id=$bid_a")" ACTIVE
q "DROP TRIGGER inject_bid ON bids" > /dev/null
mark bid-expiry-retry
call POST /admin/batches/bid-expiry                            # A만 남음 → processed 1, failed 0
need "bid A 재실행 뒤 상태" "$(q "SELECT status FROM bids WHERE id=$bid_a")" EXPIRED   # A가 대상이었음

mark end

dump bids "SELECT id-$mb,user_id-$base,side,price,status,expires_at FROM bids WHERE id>$mb ORDER BY id"
dump bid_events "SELECT bid_id-$mb,from_status,to_status,trade_id-$mt,created_at FROM bid_events WHERE bid_id>$mb ORDER BY id"
dump order_items "SELECT id-$mi,order_id-$mo,status,delivered_at,confirmed_at FROM order_items WHERE id>$mi ORDER BY id"
dump point_ledger "SELECT id-$ml,user_id-$base,kind,status,amount,remaining,expires_at,order_item_id-$mi,created_at FROM point_ledger WHERE id>$ml AND user_id>=$base ORDER BY id"
dump point_allocations "SELECT entry_id-$ml,earn_entry_id-$ml,amount FROM point_allocations WHERE id>$ma AND earn_entry_id>$ml ORDER BY id"
dump point_balances "SELECT user_id-$base,available,pending,updated_at FROM point_balances WHERE user_id>=$base ORDER BY user_id"
dump settlements "SELECT seller_id,period_start,gross,fee_total,penalty_total,carry_in,net,payout,carry_out,status,paid_at FROM settlements WHERE id>$ms ORDER BY period_start,seller_id"
# 시드 행(배치가 바꾼 것 포함): 정렬 덤프의 md5
{ q "SELECT id,status,expires_at FROM bids WHERE id<=$mb ORDER BY id" | md5sum
  q "SELECT id,status,confirmed_at FROM order_items WHERE id<=$mi ORDER BY id" | md5sum
  q "SELECT id,status,remaining FROM point_ledger WHERE id<=$ml ORDER BY id" | md5sum; } | dump_text seed.md5
# 격리된 실패는 ERROR(대상 id)로, 정산 중단은 계약 밖 오류 로그로 남는다(I4). 응답과 로그가 맞는지 확인한다:
# 배치 응답 failed 합 = 격리 실패 ERROR 수, 400 UNEXPECTED_ERROR 응답 수 ≤ 계약 밖 오류 로그 수. 이 시나리오에는 카드 취소 대상이 없어(failed에 드는 WARN 건너뜀 0)
# 첫 식이 성립한다. 격리를 끈 앱에서도 둘 다 성립하므로(0 = 0) 한쪽만 다른 회귀는 차이(1)로 남는다
n_err=$(grep -c ': 대상 [0-9]* 실패(반영 안 됨' "$out/app.log" || true); n_unexp=$(grep -c '예상 못 한 오류' "$out/app.log" || true)
sum_failed=$({ grep -o '/admin/batches/[a-z-]* key= → {"processed":[0-9]*,"failed":[0-9]*' "$out/responses.txt" || true; } | awk -F'"failed":' '{n += $2} END {print n + 0}')
n_400=$(grep -c '"code":"UNEXPECTED_ERROR"' "$out/responses.txt" || true)
[ "$sum_failed" = "$n_err" ] || die "응답 failed 합 $sum_failed인데 격리 실패 로그 $n_err건(I4)"
[ "$n_400" -le "$n_unexp" ] || die "계약 밖 오류 응답 $n_400건인데 로그 $n_unexp건"
printf '%s\n' "$n_err" "$n_unexp" "${sum_failed:-0}" "$n_400" | dump_text log-counts
app_end
