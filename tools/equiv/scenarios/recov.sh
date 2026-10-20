#!/usr/bin/env bash
# 시나리오 recov — 기동 복구: PG 응답 대기 중인 주문 2건(K1 승인 기록됨·K2 기록 안 됨, K2는 포인트 예약)을 둔 채 kill -9 →
# 재기동 시 PAYING → UNKNOWN 전환 → pg-inquiry로 K1 승인·K2 거절(포인트 해제).
# 사용: scenarios/recov.sh <god|layered> <jar> <outdir>  (보통은 tools/equiv/run.sh recov)
# 강제 종료로 끊긴 두 결제 요청은 응답이 없어 응답 층에 없다(두 앱 모두). 두 기동의 SQL·DB 문장은 모두 비교한다.
source "$(dirname "$0")/../lib.sh"
app_begin "$@"
start_db

# 픽스처: 새 사이즈 1개, 사용자 s·b(포인트 3000)
q "INSERT INTO product_sizes(product_id,size) SELECT min(id),'EXP' FROM products" > /dev/null
for u in s b; do q "INSERT INTO users(email,name,grade,created_at) VALUES ('$u@exp','$u','BRONZE','2026-09-01T00:00:00+09:00')" > /dev/null; done
size=$(q "SELECT max(id) FROM product_sizes"); base=$(q "SELECT max(id)-1 FROM users"); S_=$base B_=$((base+1))
q "INSERT INTO point_ledger(user_id,kind,status,amount,remaining,expires_at,created_at) VALUES ($B_,'EARN','AVAILABLE',3000,3000,'2027-06-01T00:00:00+09:00','2026-09-02T00:00:00+09:00')" > /dev/null
q "INSERT INTO point_balances(user_id,available,pending,updated_at) VALUES ($B_,3000,0,'2026-09-02T00:00:00+09:00')" > /dev/null
mo=$(q "SELECT max(id) FROM orders"); mp=$(q "SELECT max(id) FROM pg_transactions"); ml=$(q "SELECT max(id) FROM point_ledger")

start_pg 60000          # 가짜 PG가 60초 동안 응답하지 않는다
start_app 60000         # 앱도 60초 기다린다 → 두 요청 모두 PAYING에서 멈춘 채 강제 종료
for p in 50000 80000; do call POST /bids $S_ "{\"productSizeId\":$size,\"side\":\"SELL\",\"price\":$p,\"validDays\":30}"; call POST /bids $B_ "{\"productSizeId\":$size,\"side\":\"BUY\",\"price\":$p,\"validDays\":30}"; done
t=$(q "SELECT string_agg(id::text, ' ' ORDER BY id) FROM trades WHERE buyer_id=$B_"); read -r T1 T2 <<< "$t"
[ -n "$T2" ] || die "체결 2건이 아님: $t"
outcome K1 TIMEOUT_APPROVED; outcome K2 TIMEOUT_NOT_RECEIVED
call POST /orders $B_ "{\"tradeIds\":[$T1]}" K1 2> /dev/null & c1=$!   # 강제 종료로 끊긴다(curl 오류는 기대한 것)
# K1 주문 행이 생긴 뒤에 K2를 보낸다(두 요청의 SQL 순서 고정)
for _ in $(seq 50); do [ "$(q "SELECT count(*) FROM orders WHERE id>$mo")" = 1 ] && break; sleep 0.1; done
[ "$(q "SELECT count(*) FROM orders WHERE id>$mo")" = 1 ] || die "K1 주문 행이 생기지 않음"
call POST /orders $B_ "{\"tradeIds\":[$T2],\"pointToUse\":1000}" K2 2> /dev/null & c2=$!
for _ in $(seq 50); do [ "$(q "SELECT count(*) FROM orders WHERE id>$mo AND status='PAYING'")" = 2 ] && break; sleep 0.1; done
[ "$(q "SELECT count(*) FROM orders WHERE id>$mo AND status='PAYING'")" = 2 ] || die "PAYING 주문이 2건이 되지 않음(강제 종료 전제)"
sleep 1
dump before-kill "SELECT id-$mo,status FROM orders WHERE id>$mo ORDER BY id"
kill_app
wait $c1 $c2 2>/dev/null || true

start_app 2000
dump orders-after-restart "SELECT id-$mo,buyer_id-$base,status,point_used,card_amount,idempotency_key,created_at,paid_at FROM orders WHERE id>$mo ORDER BY id"
dump pg-after-restart "SELECT id-$mp,order_id-$mo,kind,amount,status,$(norm_pg_tx_id),idempotency_key,requested_at,responded_at FROM pg_transactions WHERE id>$mp ORDER BY id"
dump points-after-restart "SELECT id-$ml,kind,status,amount,remaining,order_id-$mo FROM point_ledger WHERE id>=$ml ORDER BY id"
norm_startup_log < "$out/app.log" | dump_text startup-log
call POST /admin/batches/pg-inquiry
dump orders-after-inquiry "SELECT id-$mo,status,paid_at FROM orders WHERE id>$mo ORDER BY id"
dump pg-after-inquiry "SELECT id-$mp,order_id-$mo,kind,status,$(norm_pg_tx_id),requested_at,responded_at FROM pg_transactions WHERE id>$mp ORDER BY id"
dump points-after-inquiry "SELECT id-$ml,kind,status,amount,remaining,order_id-$mo,created_at FROM point_ledger WHERE id>=$ml ORDER BY id"
dump balance "SELECT available,pending,updated_at FROM point_balances WHERE user_id=$B_"
app_end
