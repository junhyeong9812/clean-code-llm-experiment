#!/usr/bin/env bash
# 시나리오 errors — 오류 경로: 형식 오류 입찰 400(가격 0·없는 side·validDays 45·즉시+가격·사이즈 없음)·없는 사이즈/입찰 404,
# 영속 예외가 API까지 오는 경로(bids·orders INSERT를 트리거로 실패시킴)의 응답 본문(code·message)과 DB 문장.
# 사용: scenarios/errors.sh <god|layered> <jar> <outdir>  (보통은 tools/equiv/run.sh errors)
source "$(dirname "$0")/../lib.sh"
app_begin "$@"
start_db

# 픽스처: 새 사이즈 1개, 사용자 s·b, 주입용 트리거 함수
q "INSERT INTO product_sizes(product_id,size) SELECT min(id),'EXP' FROM products" > /dev/null
for u in s b; do q "INSERT INTO users(email,name,grade,created_at) VALUES ('$u@exp','$u','BRONZE','2026-09-01T00:00:00+09:00')" > /dev/null; done
size=$(q "SELECT max(id) FROM product_sizes"); base=$(q "SELECT max(id)-1 FROM users"); S_=$base B_=$((base+1))
mb=$(q "SELECT max(id) FROM bids"); mo=$(q "SELECT max(id) FROM orders"); mt=$(q "SELECT max(id) FROM trades")
qs <<'SQL'
CREATE FUNCTION fail_inject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected by %', TG_NAME; END $$;
SQL

start_pg 3000
start_app 2000
mark invalid-bids
call POST /bids $B_ "{\"productSizeId\":$size,\"side\":\"BUY\",\"price\":0,\"validDays\":30}"
call POST /bids $B_ "{\"productSizeId\":$size,\"side\":\"HOLD\",\"price\":100000,\"validDays\":30}"
call POST /bids $B_ "{\"productSizeId\":$size,\"side\":\"BUY\",\"price\":100000,\"validDays\":45}"
call POST /bids $B_ "{\"productSizeId\":$size,\"side\":\"BUY\",\"instant\":true,\"price\":100000}"
call POST /bids $B_ "{\"side\":\"BUY\",\"price\":100000,\"validDays\":30}"
mark not-found
call POST /bids $B_ "{\"productSizeId\":999999999,\"side\":\"BUY\",\"price\":100000,\"validDays\":30}"
call DELETE /bids/999999999 $B_
mark trade
call POST /bids $S_ "{\"productSizeId\":$size,\"side\":\"SELL\",\"price\":50000,\"validDays\":30}"
call POST /bids $B_ "{\"productSizeId\":$size,\"side\":\"BUY\",\"price\":50000,\"validDays\":30}"
t=$(q "SELECT max(id) FROM trades"); [ "$t" -gt "$mt" ] || die "체결이 생기지 않음"
mark persist-bids
qs <<'SQL'
CREATE TRIGGER inject_bid BEFORE INSERT ON bids FOR EACH ROW EXECUTE FUNCTION fail_inject();
SQL
call POST /bids $B_ "{\"productSizeId\":$size,\"side\":\"BUY\",\"price\":40000,\"validDays\":30}"
qs <<'SQL'
DROP TRIGGER inject_bid ON bids;
CREATE TRIGGER inject_order BEFORE INSERT ON orders FOR EACH ROW EXECUTE FUNCTION fail_inject();
SQL
mark persist-orders
call POST /orders $B_ "{\"tradeIds\":[$t]}" KX
qs <<'SQL'
DROP TRIGGER inject_order ON orders;
SQL
mark end

dump bids "SELECT id-$mb,user_id-$base,side,price,status FROM bids WHERE id>$mb ORDER BY id"
dump orders "SELECT count(*) FROM orders WHERE id>$mo"
dump trades "SELECT id-$mt,status FROM trades WHERE id>$mt ORDER BY id"
app_end
