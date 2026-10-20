#!/usr/bin/env bash
# 시나리오 fail — 결제 결과 반영 실패 주입(DB 트리거):
#   A 반영 트랜잭션의 첫 INSERT(APPROVE APPROVED) 실패 → 재기록이 UNKNOWN 1행
#   B 반영은 진행되다 커밋 flush의 orders UPDATE(PAID)에서 실패 → 전체 롤백 → 재기록 UNKNOWN
#   C 반영·재기록 모두 실패 → PAYING 잔류, 400 UNEXPECTED_ERROR. 같은 키 재요청은 대기 상한(약 14초) 뒤 400
#   → 정상 종료·재기동(PAYING → UNKNOWN) → pg-inquiry
# 사용: scenarios/fail.sh <god|layered> <jar> <outdir>  (보통은 tools/equiv/run.sh fail)
source "$(dirname "$0")/../lib.sh"
app_begin "$@"
# C의 같은 키 대기는 50ms마다 다시 읽어 반복 횟수가 시간에 따라 다르다 → 그 구간만 접는다
norm_logs() { norm_poll_fold same-key-wait same-key-done; }
start_db

# 픽스처: 새 사이즈 1개, 사용자 s·b(포인트 5000), 주입용 트리거 함수
q "INSERT INTO product_sizes(product_id,size) SELECT min(id),'EXP' FROM products" > /dev/null
for u in s b; do q "INSERT INTO users(email,name,grade,created_at) VALUES ('$u@exp','$u','BRONZE','2026-09-01T00:00:00+09:00')" > /dev/null; done
size=$(q "SELECT max(id) FROM product_sizes"); base=$(q "SELECT max(id)-1 FROM users"); S_=$base B_=$((base+1))
q "INSERT INTO point_ledger(user_id,kind,status,amount,remaining,expires_at,created_at) VALUES ($B_,'EARN','AVAILABLE',5000,5000,'2027-06-01T00:00:00+09:00','2026-09-02T00:00:00+09:00')" > /dev/null
q "INSERT INTO point_balances(user_id,available,pending,updated_at) VALUES ($B_,5000,0,'2026-09-02T00:00:00+09:00')" > /dev/null
mo=$(q "SELECT max(id) FROM orders"); mp=$(q "SELECT max(id) FROM pg_transactions"); ml=$(q "SELECT max(id) FROM point_ledger"); mt=$(q "SELECT max(id) FROM trades")
qs <<'SQL'
CREATE FUNCTION fail_inject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected by %', TG_NAME; END $$;
SQL
stage() { # 단계 이름 — 그 시점의 주문·PG 거래·항목·체결·포인트를 한 파일로
  { echo "# orders"; q "SELECT id-$mo,status,point_used,card_amount,idempotency_key,created_at,paid_at FROM orders WHERE id>$mo ORDER BY id"
    echo "# pg"; q "SELECT id-$mp,order_id-$mo,kind,amount,status,$(norm_pg_tx_id),idempotency_key,requested_at,responded_at FROM pg_transactions WHERE id>$mp ORDER BY id"
    echo "# items"; q "SELECT order_id-$mo,trade_id-$mt,status,paid_at FROM order_items WHERE order_id>$mo ORDER BY id"
    echo "# trades"; q "SELECT id-$mt,status FROM trades WHERE id>$mt ORDER BY id"
    echo "# points"; q "SELECT id-$ml,kind,status,amount,remaining,order_id-$mo,order_item_id IS NOT NULL,created_at FROM point_ledger WHERE id>=$ml ORDER BY id"
    echo "# alloc"; q "SELECT count(*), coalesce(sum(a.amount),0) FROM point_allocations a JOIN point_ledger l ON l.id=a.entry_id WHERE l.user_id=$B_"
    echo "# balance"; q "SELECT available,pending,updated_at FROM point_balances WHERE user_id=$B_"
  } | dump_text "stage-$1"; }

start_pg 3000
start_app 2000
for p in 50000 60000 70000; do call POST /bids $S_ "{\"productSizeId\":$size,\"side\":\"SELL\",\"price\":$p,\"validDays\":30}"; call POST /bids $B_ "{\"productSizeId\":$size,\"side\":\"BUY\",\"price\":$p,\"validDays\":30}"; done
t=$(q "SELECT string_agg(id::text, ' ' ORDER BY id) FROM trades WHERE id>$mt"); read -r T1 T2 T3 <<< "$t"
[ -n "$T3" ] || die "체결 3건이 아님: $t"

qs <<'SQL'
CREATE TRIGGER inject_a BEFORE INSERT ON pg_transactions FOR EACH ROW WHEN (NEW.kind = 'APPROVE' AND NEW.status <> 'UNKNOWN') EXECUTE FUNCTION fail_inject();
SQL
call POST /orders $B_ "{\"tradeIds\":[$T1],\"pointToUse\":1000}" KA
qs <<'SQL'
DROP TRIGGER inject_a ON pg_transactions;
SQL
stage A

qs <<'SQL'
CREATE TRIGGER inject_b BEFORE UPDATE ON orders FOR EACH ROW WHEN (NEW.status = 'PAID') EXECUTE FUNCTION fail_inject();
SQL
call POST /orders $B_ "{\"tradeIds\":[$T2]}" KB
qs <<'SQL'
DROP TRIGGER inject_b ON orders;
SQL
stage B

qs <<'SQL'
CREATE TRIGGER inject_c BEFORE INSERT ON pg_transactions FOR EACH ROW WHEN (NEW.kind = 'APPROVE') EXECUTE FUNCTION fail_inject();
SQL
call POST /orders $B_ "{\"tradeIds\":[$T3],\"pointToUse\":2000}" KC
mark same-key-wait
call POST /orders $B_ "{\"tradeIds\":[$T3],\"pointToUse\":2000}" KC
mark same-key-done
stage C
qs <<'SQL'
DROP TRIGGER inject_c ON pg_transactions;
SQL
{ grep -c '결제 결과 반영 실패' "$out/app.log" || true; grep -c 'UNKNOWN 기록도 실패' "$out/app.log" || true; grep -c 'UNEXPECTED_ERROR\|예상 못 한 오류' "$out/app.log" || true; } | dump_text log-counts

stop_app
start_app 2000
stage restart
norm_startup_log < "$out/app.log" | dump_text startup-log
call POST /admin/batches/pg-inquiry
stage inquiry
app_end
