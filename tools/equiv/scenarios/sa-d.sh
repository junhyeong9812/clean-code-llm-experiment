#!/usr/bin/env bash
# 시나리오 sa-d — 단계 A 보충 A13~A17(ADR-0007 §20):
#   A13 리뷰 수정(200·같은 값 200·400·401·404·403·409 리뷰 HIDDEN·DELETED·항목 DISPUTED·30일 정각 200/1초 뒤 DEADLINE_PASSED)·
#       쓸 수 있는 항목(30일 정각 포함·1초 뒤 빠짐)·내 리뷰,
#   A14 목표가 변경(ACTIVE 200·CANCELLED·TRIGGERED 409)·재등록(TRIGGERED·EXPIRED 201, 409 ACTIVE·CANCELLED·더 큰 id·7일 1초 뒤 DEADLINE_PASSED, 7일 정각 201)·
#       price-alert-rearm(발동 입찰 CANCELLED·EXPIRED → 재무장, MATCHED·ACTIVE 비대상, 위시 없음·구매 제한 건너뜀, 같은 시각 재실행 0 0),
#   A15 문의 재개(USER·AUTO 종결, 7일 정각 200/1초 뒤 409, OPEN 409)·관리자 문의함(키셋 nextAfter·status·slaBreached 필터·상세),
#   A16 예약 변경(성공 — UPDATE → INSERT 문장 순서를 DB 문장 로그에서 확인, 판정 순서의 각 409, PAID 아닌 항목, 기한 2종 정각/1초 뒤)·슬롯·예약 상세,
#   A17 일괄 읽음(0건 포함)·안 읽은 수·notification-purge(읽은 지 30일 정각 지움/1초 모자람 남김, 안 읽은 알림 남김).
#   배치는 첫 실행 failed 0 · 같은 시각 재실행 processed 0·failed 0까지 단언한다. 새 배치 둘 다 건별 격리(§2.6)를 밟는다 —
#   price-alert-rearm은 그 알림의 새 알림 INSERT만, notification-purge는 알림 하나의 DELETE만 트리거로 실패시켜 failed 1 → 트리거를 지우고
#   재실행하면 그 대상만 processed 1. 끝에 응답 failed 합 = 격리 실패 ERROR 로그 수를 단언한다(sa-a와 같은 식).
# 사용: scenarios/sa-d.sh <god|layered> <jar> <outdir>  (보통은 tools/equiv/run.sh sa-d)
# 시각: 10-01 00:00(시드 NOW) → 10-02 → 10-08 → 10-10 → 10-14 → 10-25 → 10-31. 배치(scan·auto-close·bid-expiry·purge)는 시드 전체가 대상이다.
# 새 행은 오프셋 줄 단위, 바뀐 시드 행은 정렬 덤프의 md5로 비교한다. 구매 제한은 픽스처로 직접 두고 그 사용자에게 입찰을 두지 않는다(C2 픽스처 규칙).
source "$(dirname "$0")/../lib.sh"
app_begin "$@"
start_db

# 픽스처: 사이즈 11개(체결 D0, 가격 알림 경우별 하나씩), 사용자 s(판매)·b(구매)·c(제3자)·r(가격 알림 주인 — 발동 뒤 구매 제한)
mz=$(q "SELECT max(id) FROM product_sizes")
for z in D0 DC DM DA DN DR DT DE1 DE2 DX DK; do q "INSERT INTO product_sizes(product_id,size) SELECT min(id),'$z' FROM products" > /dev/null; done
declare -A Z
for z in D0 DC DM DA DN DR DT DE1 DE2 DX DK; do Z[$z]=$(q "SELECT id FROM product_sizes WHERE id>$mz AND size='$z'"); int_or_die "size $z" "${Z[$z]}"; done
for u in s b c r; do q "INSERT INTO users(email,name,grade,created_at) VALUES ('$u@exp','$u','BRONZE','2026-09-01T00:00:00+09:00')" > /dev/null; done
base=$(q "SELECT max(id)-3 FROM users"); S_=$base B_=$((base+1)) C_=$((base+2)) R_=$((base+3))
mo=$(q "SELECT max(id) FROM orders"); mi=$(q "SELECT max(id) FROM order_items"); mt=$(q "SELECT max(id) FROM trades"); mb=$(q "SELECT max(id) FROM bids")
mw=$(q "SELECT max(id) FROM reviews"); md=$(q "SELECT max(id) FROM disputes"); ma=$(q "SELECT max(id) FROM price_alerts"); mwl=$(q "SELECT max(id) FROM wishlists")
mn=$(q "SELECT max(id) FROM notifications"); mq=$(q "SELECT max(id) FROM inquiries"); mm=$(q "SELECT max(id) FROM inquiry_messages")
msl=$(q "SELECT max(id) FROM inbound_slots"); mr=$(q "SELECT max(id) FROM inbound_reservations"); mu=$(q "SELECT max(id) FROM admin_audit_logs")
# 만료된 ACTIVE 가격 알림 2건(b, DE1·DE2 — 09-30 만료): 첫 scan에서 EXPIRED(closed_at 10-01 00:00) → 7일 정각·1초 뒤 재등록
q "INSERT INTO price_alerts(user_id,product_size_id,target_price,status,expires_at,created_at) VALUES
  ($B_,${Z[DE1]},70000,'ACTIVE','2026-09-30T00:00:00+09:00','2026-08-01T00:00:00+09:00'),
  ($B_,${Z[DE2]},70000,'ACTIVE','2026-09-30T00:00:00+09:00','2026-08-01T00:00:00+09:00')" > /dev/null
A_E1=$((ma+1)) A_E2=$((ma+2))

# need <이름> <값> [기대]: 경로를 실제로 지났는지. 기대가 없으면 양의 정수여야 한다(두 앱이 함께 지나치지 않았는데 같음으로 끝나지 않게)
need() {
  if [ $# -ge 3 ]; then [ "$2" = "$3" ] || die "경로를 지나지 않음: $1 = '$2' (기대 '$3')"
  else [[ $2 =~ ^[0-9]+$ ]] && [ "$2" -gt 0 ] || die "경로를 지나지 않음: $1 = '$2'"; fi
}
# lock_seq <표식> <정규식…>: 지금까지의 DB 문장 로그(앱 문장만)에서 그 표식 구간(다음 표식 전까지)에 정규식들이 이 순서로 나오는지(소문자 비교, sa-lock과 같다)
lock_seq() {
  local m=$1; shift
  local r; r=$(docker logs "$EQ_DB" 2>&1 | ext_pg_app_statements /dev/stdin | awk -v m="== MARK $m" -v pats="$(printf '%s\n' "$@")" '
    BEGIN { n = split(pats, p, "\n"); if (p[n] == "") n--; k = 1 }
    $0 == m { f = 1; next }
    f && /^== MARK / { exit }
    f && k <= n && tolower($0) ~ p[k] { k++ }
    END { print (k > n) ? "ok" : "멈춘 곳 " k ": " p[k] }')
  need "문장 순서 $m" "$r" ok
}
body_of_last() { tail -1 "$out/responses.txt" | sed 's/^[A-Z]* [^ ]* key=[^ ]* → //'; }
next_after() { local n; n=$(tail -1 "$out/responses.txt" | sed -n 's/.*"nextAfter":\([0-9][0-9]*\).*/\1/p'); [[ $n =~ ^[0-9]+$ ]] || die "nextAfter 없음: $(tail -1 "$out/responses.txt" | cut -c1-200)"; echo "$n"; }
start_pg 3000
start_app 2000
# 실행(failed 0) + 같은 시각 재실행(대상 없음 — processed 0·failed 0)
batch2() { batch_run "$1" '^[0-9]+ 0 200$'; batch_run "$1" '^0 0 200$'; }
trade() { call POST /bids $S_ "{\"productSizeId\":${Z[D0]},\"side\":\"SELL\",\"price\":$1,\"validDays\":30}"; call POST /bids $B_ "{\"productSizeId\":${Z[D0]},\"side\":\"BUY\",\"price\":$1,\"validDays\":30}"; }
# order <키> <첫 가격> <항목 수>: 새 체결 n건을 한 주문으로 결제하고 항목 id를 공백으로 출력
order() {
  local from t k
  from=$(q "SELECT max(id) FROM trades")
  for k in $(seq "$3"); do trade $(($2 - k * 1000)); done
  t=$(q "SELECT string_agg(id::text, ',' ORDER BY id) FROM trades WHERE id>$from")
  call POST /orders $B_ "{\"tradeIds\":[$t]}" "$1"
  q "SELECT string_agg(i.id::text, ' ' ORDER BY i.id) FROM order_items i JOIN orders o ON o.id=i.order_id WHERE o.id>$mo AND o.idempotency_key='$1'"
}

# ======== 10-01 00:00 ========
mark fixtures
read -r -a E <<< "$(order K-REV 100000 6)"; [ ${#E[@]} = 6 ] || die "리뷰 항목 6건이 아님: ${E[*]}"
read -r -a P <<< "$(order K-INB 50000 9)"; [ ${#P[@]} = 9 ] || die "입고 항목 9건이 아님: ${P[*]}"
for x in "${E[@]}"; do
  call POST "/order-items/$x/shipment" $S_; call POST "/admin/order-items/$x/receipt"; call POST "/admin/order-items/$x/inspection" "" '{"result":"PASS"}'
  call POST "/admin/order-items/$x/delivery"; call POST "/order-items/$x/confirm" $B_
done
need "리뷰 항목 CONFIRMED" "$(q "SELECT count(*) FROM order_items WHERE id>$mi AND status='CONFIRMED'")" 6

mark review-edit
for k in 0 1 2 3 4; do call POST "/order-items/${E[$k]}/review" $B_ "{\"rating\":$((k + 1)),\"content\":\"처음 $k\"}"; done   # E5(E[5])는 쓰지 않는다
rv=$(q "SELECT string_agg(id::text, ' ' ORDER BY id) FROM reviews WHERE id>$mw"); read -r V1 V2 V3 V4 V5 <<< "$rv"; [ -n "$V5" ] || die "리뷰 5건이 아님: $rv"
edit() { call PATCH "/reviews/$2" "$1" "$3"; }
edit "" "$V1" '{"rating":4,"content":"x"}'                    # 401
edit $B_ "$V1" '{"rating":6,"content":"x"}'                   # 400
edit $B_ "$V1" '{"content":"x"}'                              # rating 누락 400(부분 갱신 없음)
edit $B_ "$V1" '{"rating":4}'                                 # content 누락 400
need "rating만 보낸 수정" "$(last_status)" "400 INVALID_REQUEST"
edit $B_ "$V1" '{"rating":"4","content":"x"}'                 # 문자열 400
edit $B_ "$V1" '{"rating":4.0,"content":"x"}'                 # 정수 자리의 4.0 400(§19-2)
need "rating 4.0" "$(last_status)" "400 INVALID_REQUEST"
edit $B_ "$V1" '{"rating":4,"content":" 　"}'        # NBSP·전각 공백뿐 400(§19-6)
need "NBSP 본문 수정" "$(last_status)" "400 INVALID_REQUEST"
edit $B_ 999999999 '{"rating":4,"content":"x"}'               # 404
edit $C_ "$V1" '{"rating":4,"content":"x"}'                   # 작성자 아님 403
edit $S_ "$V1" '{"rating":4,"content":"x"}'                   # 판매자도 403
mark review-edit-ok
edit $B_ "$V1" '{"rating":4,"content":" 고침 "}'              # 200
# 잠금 순서(§20.8): 주문 FU → 리뷰 FU → UPDATE — 두 앱이 함께 틀려도 잡히게 절대 순서로 확인
lock_seq review-edit-ok 'from orders where id = .* for update' 'from reviews where id = .* for update' 'update reviews'
need "리뷰 수정" "$(last_status) $(q "SELECT rating||' '||content||' '||status FROM reviews WHERE id=$V1")" "200 4  고침  VISIBLE"
edit $B_ "$V1" '{"rating":4,"content":" 고침 "}'              # 같은 값도 200(멱등 처리 없음)
call POST "/admin/reviews/$V2/hide"
edit $B_ "$V2" '{"rating":5,"content":"x"}'                   # HIDDEN 409 ITEM_STATE_CONFLICT
need "HIDDEN 수정" "$(last_status)" "409 ITEM_STATE_CONFLICT"
call DELETE "/reviews/$V3" $B_
edit $B_ "$V3" '{"rating":5,"content":"x"}'                   # DELETED 409
call POST "/order-items/${E[3]}/disputes" $B_                 # 항목 DISPUTED, 리뷰는 rating-recalc 전이라 VISIBLE
need "분쟁 뒤 리뷰 VISIBLE" "$(q "SELECT status FROM reviews WHERE id=$V4")" VISIBLE
edit $B_ "$V4" '{"rating":5,"content":"x"}'                   # 항목 CONFIRMED 아님 409
need "DISPUTED 항목 수정" "$(last_status)" "409 ITEM_STATE_CONFLICT"
call GET /me/reviews $B_; call GET /me/reviews $C_; call GET /me/reviews
call GET /me/reviewable-items $B_                             # E5 하나
need "쓸 수 있는 항목(10-01)" "$(body_of_last | grep -o '"orderItemId":[0-9]*' | paste -sd ' ')" "\"orderItemId\":${E[5]}"
call GET /me/reviewable-items $C_; call GET /me/reviewable-items

mark alert-setup
for z in DC DM DA DX; do call POST /me/wishlist $B_ "{\"productSizeId\":${Z[$z]}}"; done        # DN·DT는 위시 없음
call POST /me/wishlist $R_ "{\"productSizeId\":${Z[DR]}}"
for z in DC DM DA DN DT DK; do call POST /me/price-alerts $B_ "{\"productSizeId\":${Z[$z]},\"targetPrice\":60000}"; done
call POST /me/price-alerts $R_ "{\"productSizeId\":${Z[DR]},\"targetPrice\":60000}"
declare -A A BID
for z in DC DM DA DN DT DK; do A[$z]=$(q "SELECT id FROM price_alerts WHERE id>$ma AND user_id=$B_ AND product_size_id=${Z[$z]}"); int_or_die "alert $z" "${A[$z]}"; done
A[DR]=$(q "SELECT id FROM price_alerts WHERE id>$ma AND user_id=$R_"); int_or_die "alert DR" "${A[DR]}"
amax=$(q "SELECT max(id) FROM price_alerts")                 # 준비한 알림 뒤(재등록·재무장의 새 알림은 이보다 크다)
for z in DC DM DA DN DR DT DX; do
  call POST /bids $S_ "{\"productSizeId\":${Z[$z]},\"side\":\"SELL\",\"price\":50000,\"validDays\":30}"   # 10-31 00:00 만료
  BID[$z]=$(q "SELECT id FROM bids WHERE id>$mb AND product_size_id=${Z[$z]} AND side='SELL'"); int_or_die "bid $z" "${BID[$z]}"
done
mark alert-scan-1
batch2 price-alert-scan                                       # DC·DM·DA·DN·DR·DT TRIGGERED, DE1·DE2 EXPIRED + 시드 만료
need "발동 6건" "$(q "SELECT count(*) FROM price_alerts WHERE id>$ma AND status='TRIGGERED'")" 6
need "만료 2건" "$(q "SELECT string_agg(status, ' ' ORDER BY id) FROM price_alerts WHERE id IN ($A_E1,$A_E2)")" "EXPIRED EXPIRED"
call DELETE "/bids/${BID[DC]}" $S_; call DELETE "/bids/${BID[DN]}" $S_; call DELETE "/bids/${BID[DR]}" $S_   # 발동 입찰 CANCELLED
call POST /bids $C_ "{\"productSizeId\":${Z[DM]},\"side\":\"BUY\",\"price\":50000,\"validDays\":30}"           # DM 발동 입찰 MATCHED
need "발동 입찰 상태" "$(q "SELECT string_agg(status, ' ' ORDER BY id) FROM bids WHERE id IN (${BID[DC]},${BID[DM]},${BID[DA]},${BID[DN]},${BID[DR]})")" "CANCELLED MATCHED ACTIVE CANCELLED CANCELLED"
q "UPDATE users SET purchase_restricted_until='2026-11-30T00:00:00+09:00' WHERE id=$R_" > /dev/null   # 발동 뒤 구매 제한(입찰 없음)

mark alert-change
chg() { call PATCH "/me/price-alerts/$2" "$1" "$3"; }
chg "" "${A[DK]}" '{"targetPrice":1000}'                      # 401
chg $B_ "${A[DK]}" '{"targetPrice":0}'                        # 400
chg $B_ "${A[DK]}" '{}'                                       # 누락 400
chg $B_ "${A[DK]}" '{"targetPrice":"1000"}'                   # 문자열 400
chg $B_ 999999999 '{"targetPrice":1000}'                      # 404
chg $C_ "${A[DK]}" '{"targetPrice":1000}'                     # 남의 것 403
chg $B_ "${A[DK]}" '{"targetPrice":12345}'                    # 200
need "목표가 변경" "$(last_status) $(q "SELECT target_price||' '||status||' '||expires_at FROM price_alerts WHERE id=${A[DK]}")" "200 12345 ACTIVE 2026-11-29 15:00:00+00"
chg $B_ "${A[DK]}" '{"targetPrice":12345}'                    # 같은 값 200
chg $B_ "${A[DT]}" '{"targetPrice":1000}'                     # TRIGGERED 409
need "TRIGGERED 변경" "$(last_status)" "409 ITEM_STATE_CONFLICT"
call DELETE "/me/price-alerts/${A[DK]}" $B_
chg $B_ "${A[DK]}" '{"targetPrice":1000}'                     # CANCELLED 409

mark alert-renew
renew() { call POST "/me/price-alerts/$2/renew" "$1"; }
renew "" "${A[DT]}"                                           # 401
renew $B_ 999999999                                           # 404
renew $C_ "${A[DT]}"                                          # 403
renew $B_ "${A[DK]}"                                          # CANCELLED 409
need "CANCELLED 재등록" "$(last_status)" "409 ITEM_STATE_CONFLICT"
mark alert-renew-ok
renew $B_ "${A[DT]}"                                          # TRIGGERED 201 → 새 알림
need "TRIGGERED 재등록" "$(last_status) $(q "SELECT count(*)||' '||min(status)||' '||min(target_price)||' '||min(expires_at) FROM price_alerts WHERE id>${A[DT]} AND product_size_id=${Z[DT]}")" "201 1 ACTIVE 60000 2026-11-29 15:00:00+00"
lock_seq alert-renew-ok 'from product_sizes where id = .* for key share' 'from users where id = .* for update' 'insert into price_alerts'
A_T2=$(q "SELECT id FROM price_alerts WHERE id>${A[DT]} AND product_size_id=${Z[DT]}"); int_or_die A_T2 "$A_T2"
mark alert-renew-conflicts
renew $B_ "${A[DT]}"                                          # 더 큰 id 알림 있음 409
need "마지막 알림 아님" "$(last_status)" "409 ITEM_STATE_CONFLICT"
renew $B_ "$A_T2"                                             # ACTIVE 409
call GET /me/price-alerts $B_; call GET /me/price-alerts $R_

mark inquiry
inq() { call POST /me/inquiries "$1" "{\"category\":\"OTHER\",\"title\":\"$2\",\"body\":\"본문\"}"; }
inq $B_ q1; inq $B_ q2; inq $B_ q3; inq $B_ q4; inq $C_ q5
qv=$(q "SELECT string_agg(id::text, ' ' ORDER BY id) FROM inquiries WHERE id>$mq"); read -r Q1 Q2 Q3 Q4 Q5 <<< "$qv"; [ -n "$Q5" ] || die "문의 5건이 아님: $qv"
for x in $Q1 $Q3 $Q4; do call POST "/me/inquiries/$x/close" $B_; done        # USER 종결(10-01 00:00)
call POST "/admin/inquiries/$Q2/answer" "" '{"body":"답변"}'                   # 10-08 00:00에 자동 종결 대상
reopen() { call POST "/me/inquiries/$2/reopen" "$1" "$3"; }
reopen "" "$Q1" '{"body":"다시"}'                             # 401
reopen $B_ "$Q1" '{"body":"  "}'                              # 400
reopen $B_ "$Q1" '{}'                                         # 누락 400
reopen $B_ 999999999 '{"body":"다시"}'                        # 404
reopen $C_ "$Q1" '{"body":"다시"}'                            # 403
reopen $B_ "$Q1" '{"body":" 다시 열어요 "}'                   # USER 종결 재개 200
need "USER 재개" "$(last_status) $(q "SELECT status||' '||coalesce(close_reason,'null')||' '||coalesce(closed_at::text,'null') FROM inquiries WHERE id=$Q1")" "200 OPEN null null"
reopen $B_ "$Q1" '{"body":"또"}'                              # OPEN 409
need "OPEN 재개" "$(last_status)" "409 ITEM_STATE_CONFLICT"
reopen $B_ "$Q2" '{"body":"또"}'                              # ANSWERED 409
call GET "/me/inquiries/$Q1" $B_

mark inbound-setup
slot() { call POST /admin/inbound-slots "" "{\"slotStart\":\"$1\",\"capacity\":$2}"; }
slot 2026-10-03T03:00:00+09:00 5; slot 2026-10-04T03:00:00+09:00 5; slot 2026-10-04T04:00:00+09:00 1      # SA SB SF(정원 1)
slot 2026-10-10T03:00:00+09:00 5; slot 2026-10-12T03:00:00+09:00 5                                        # SC SE(취소 기한)
slot 2026-10-20T03:00:00+09:00 5; slot 2026-10-15T03:00:00+09:00 5                                        # SG SH(예약 기한)
sl=$(q "SELECT string_agg(id::text, ' ' ORDER BY id) FROM inbound_slots WHERE id>$msl"); read -r SA SB SF SC SE SG SH <<< "$sl"; [ -n "$SH" ] || die "슬롯 7개가 아님: $sl"
rsv() { call POST /me/inbound-reservations $S_ "{\"slotId\":$1,\"orderItemId\":$2}"; }
rsv "$SA" "${P[0]}"; rsv "$SA" "${P[1]}"; rsv "$SA" "${P[2]}"; rsv "$SA" "${P[3]}"; rsv "$SF" "${P[4]}"
rsv "$SC" "${P[5]}"; rsv "$SC" "${P[6]}"; rsv "$SG" "${P[7]}"; rsv "$SG" "${P[8]}"
rvs=$(q "SELECT string_agg(id::text, ' ' ORDER BY id) FROM inbound_reservations WHERE id>$mr"); read -r -a RV <<< "$rvs"; [ ${#RV[@]} = 9 ] || die "예약 9건이 아님: $rvs"
call POST "/order-items/${P[1]}/shipment" $S_                 # P2 → SHIPPED_TO_CENTER(예약은 RESERVED 그대로)
need "PAID 아닌 항목의 예약" "$(q "SELECT i.status||' '||r.status FROM order_items i JOIN inbound_reservations r ON r.order_item_id=i.id WHERE r.id=${RV[1]}")" "SHIPPED_TO_CENTER RESERVED"
mark inbound-reschedule-errors
resch() { call POST "/me/inbound-reservations/$2/reschedule" "$1" "$3"; }
resch "" "${RV[0]}" "{\"slotId\":$SB}"                        # 401
resch $S_ "${RV[0]}" '{}'                                     # 누락 400
resch $S_ "${RV[0]}" '{"slotId":"abc"}'                       # 형식 400
resch $S_ 999999999 "{\"slotId\":$SB}"                        # 예약 404
resch $S_ "${RV[0]}" '{"slotId":999999999}'                   # 슬롯 404
resch $C_ "${RV[0]}" '{"slotId":999999999}'                   # 슬롯 404가 403보다 먼저
need "슬롯 404 먼저" "$(last_status)" "404 NOT_FOUND"
resch $C_ "${RV[0]}" "{\"slotId\":$SB}"                       # 판매자 아님 403
resch $B_ "${RV[0]}" "{\"slotId\":$SB}"                       # 구매자도 403
# PAID 아닌 항목(리뷰 SG-5): 판정 1이 예약·슬롯 잠금보다 먼저다. 응답은 같은 409라 잠금 순서의 차이는 SQL 두 층의 줄 단위 비교가 잡는다
mark inbound-reschedule-not-paid
resch $S_ "${RV[1]}" "{\"slotId\":$SB}"
need "PAID 아님" "$(last_status)" "409 ITEM_STATE_CONFLICT"
resch $S_ "${RV[1]}" "{\"slotId\":$SA}"                       # PAID 아님 + 같은 슬롯 → 판정 1이 먼저(메시지로 비교)
# PAID 판정이 예약·슬롯 잠금보다 먼저(§20.5.1의 1·GF-7): 이 구간에 예약·슬롯 FOR UPDATE가 없어야 한다(두 앱이 함께 틀려도 잡히게)
pgl=$(docker logs "$EQ_DB" 2>&1)
n_np=$(ext_pg_app_statements /dev/stdin <<< "$pgl" | awk '$0 == "== MARK inbound-reschedule-not-paid" { f = 1; next } f && /^== MARK / { exit } f && tolower($0) ~ /from inbound_(reservations|slots) where id = .* for update/ { n++ } END { print n + 0 }')
grep -q "MARK inbound-reschedule-not-paid" <<< "$pgl" || die "DB 문장 로그에 not-paid 표식이 없음"
need "PAID 판정이 예약·슬롯 잠금보다 먼저" "$n_np" 0
mark inbound-reschedule-ok
resch $S_ "${RV[0]}" "{\"slotId\":$SB}"                       # 200 {cancelled, reserved}
need "예약 변경" "$(last_status) $(q "SELECT string_agg(status||'@'||(slot_id-$msl), ' ' ORDER BY id) FROM inbound_reservations WHERE order_item_id=${P[0]}")" "200 CANCELLED@1 RESERVED@2"
# 잠금·쓰기 순서: 주문 FU → 예약 FU → 새 슬롯 FU → 옛 예약 UPDATE → (flush) 새 예약 INSERT(부분 UNIQUE 때문에 UPDATE가 먼저 DB에 가야 한다)
lock_seq inbound-reschedule-ok 'from orders where id = .* for update' 'from inbound_reservations where id = .* for update' \
  'from inbound_slots where id = .* for update' 'update inbound_reservations' 'insert into inbound_reservations'
RN1=$(q "SELECT id FROM inbound_reservations WHERE id>$mr AND order_item_id=${P[0]} AND status='RESERVED'"); int_or_die RN1 "$RN1"
mark inbound-reschedule-conflicts
resch $S_ "${RV[0]}" "{\"slotId\":$SA}"                       # 옛 예약(CANCELLED) 409
need "RESERVED 아님" "$(last_status)" "409 ITEM_STATE_CONFLICT"
resch $S_ "${RV[2]}" "{\"slotId\":$SA}"                       # 같은 슬롯 409
resch $S_ "${RV[3]}" "{\"slotId\":$SF}"                       # 정원 1 참 409
need "정원" "$(last_status)" "409 ITEM_STATE_CONFLICT"
call GET "/inbound-slots/$SB"; call GET "/inbound-slots/$SF" abc; call GET /inbound-slots/999999999   # 헤더 불필요 · 404
call GET "/me/inbound-reservations/$RN1" $S_; call GET "/me/inbound-reservations/${RV[0]}" $S_
call GET "/me/inbound-reservations/$RN1" $C_                  # 403
call GET /me/inbound-reservations/999999999 $S_               # 404
call GET "/me/inbound-reservations/$RN1"                      # 401

mark notifications-read
call GET /me/notifications/unread-count $B_
need "b 안 읽은 수" "$(body_of_last)" '{"unreadCount":5} 200'
call POST /me/notifications/read-all $C_                      # 0건도 200
need "0건 일괄 읽음" "$(body_of_last)" '{"readCount":0} 200'
call POST /me/notifications/read-all $B_                      # 5건(read_at 10-01 00:00:00)
need "b 일괄 읽음" "$(body_of_last)" '{"readCount":5} 200'
lock_seq notifications-read 'from notifications where user_id = .* and read_at is null order by id for update' 'update notifications'
call POST /me/notifications/read-all $B_                      # 다시 0
call GET /me/notifications/unread-count $B_; call GET /me/notifications/unread-count; call POST /me/notifications/read-all   # 401 둘
adv 1
call POST /me/notifications/read-all $R_                      # 1건(read_at 10-01 00:00:01 — 정리 1초 모자람)
need "r 일괄 읽음" "$(body_of_last)" '{"readCount":1} 200'

# ======== 10-02 00:00 ========
call PUT /admin/clock "" '{"now":"2026-10-02T00:00:00+09:00"}'
mark alert-rearm-fail
inject_on price_alerts "BEFORE INSERT" "NEW.product_size_id = ${Z[DC]}"
batch_run price-alert-rearm '^0 1 200$'                       # 대상은 DC 하나 — 새 알림 INSERT 실패(롤백) → failed 1
need "격리 실패 뒤 DC 새 알림 없음" "$(q "SELECT count(*) FROM price_alerts WHERE id>$amax AND product_size_id=${Z[DC]}")" 0
inject_off price_alerts
mark alert-rearm
batch_run price-alert-rearm '^1 0 200$'                       # CANCELLED 발동 입찰 → 재무장. MATCHED·ACTIVE·위시 없음(DN)·구매 제한(DR)·새 알림 있음(DT)은 아님
batch_run price-alert-rearm '^0 0 200$'
need "DC 재무장" "$(q "SELECT count(*)||' '||min(status)||' '||min(target_price)||' '||min(expires_at)||' '||min(created_at) FROM price_alerts WHERE id>$amax AND user_id=$B_ AND product_size_id=${Z[DC]}")" "1 ACTIVE 60000 2026-11-30 15:00:00+00 2026-10-01 15:00:00+00"
need "비대상(MATCHED·ACTIVE·위시 없음·구매 제한)" "$(q "SELECT count(*) FROM price_alerts WHERE id>$amax AND product_size_id IN (${Z[DM]},${Z[DA]},${Z[DN]},${Z[DR]})")" 0
lock_seq alert-rearm 'from product_sizes where id = .* for key share' 'from users where id = .* for update' 'insert into price_alerts'
need "재무장은 알림 행을 만들지 않음" "$(q "SELECT count(*) FROM notifications WHERE id>$mn AND kind='PRICE_ALERT' AND ref_id>$amax")" 0
call GET /me/price-alerts $B_

# ======== 10-08 00:00 (종결 + 7일 정각) ========
call PUT /admin/clock "" '{"now":"2026-10-08T00:00:00+09:00"}'
mark day7
renew $B_ "$A_E1"                                             # EXPIRED(closed 10-01 00:00) 7일 정각 201
need "EXPIRED 재등록 정각" "$(last_status)" 201
reopen $B_ "$Q3" '{"body":"정각"}'                            # USER 종결 7일 정각 200
need "재개 정각" "$(last_status)" 200
mark inquiry-auto-close
batch2 inquiry-auto-close                                     # Q2(답변 + 7일 정각) + 시드 ANSWERED
need "Q2 자동 종결" "$(q "SELECT status||' '||close_reason FROM inquiries WHERE id=$Q2")" "CLOSED AUTO"
reopen $B_ "$Q2" '{"body":"자동 종결 뒤"}'                    # AUTO 종결 재개 200
need "AUTO 재개" "$(last_status) $(q "SELECT status FROM inquiries WHERE id=$Q2")" "200 OPEN"
adv 1
mark day7-late
renew $B_ "$A_E2"                                             # 7일 + 1초 409
need "재등록 기한" "$(last_status)" "409 DEADLINE_PASSED"
reopen $B_ "$Q4" '{"body":"늦음"}'                            # 7일 + 1초 409
need "재개 기한" "$(last_status)" "409 DEADLINE_PASSED"
mark admin-inquiries
call GET "/admin/inquiries?limit=3"; a=$(next_after); call GET "/admin/inquiries?limit=3&after=$a"
call GET "/admin/inquiries?after=$mq"
call GET "/admin/inquiries?after=$mq&status=OPEN"; call GET "/admin/inquiries?after=$mq&status=CLOSED"
call GET "/admin/inquiries?after=$mq&slaBreached=true"         # Q5(미답변·SLA 지남)
need "slaBreached=true에 Q5" "$(body_of_last | grep -c "\"inquiryId\":$Q5,")" 1
call GET "/admin/inquiries?after=$mq&slaBreached=false"        # Q2(SLA 안에 답변)
need "slaBreached=false에 Q2" "$(body_of_last | grep -c "\"inquiryId\":$Q2,")" 1
call GET "/admin/inquiries?status=CLOSED&slaBreached=false&limit=2"; a=$(next_after); call GET "/admin/inquiries?status=CLOSED&slaBreached=false&limit=2&after=$a"
call GET "/admin/inquiries?status=open"; call GET "/admin/inquiries?slaBreached=TRUE"; call GET "/admin/inquiries?slaBreached="   # 400
call GET "/admin/inquiries?limit=0"; call GET "/admin/inquiries?after=-1"                                                    # 400
call GET "/admin/inquiries/$Q2"; call GET "/admin/inquiries/$Q5"; call GET /admin/inquiries/999999999

# ======== 10-10 01:00 (SC − 2시간 정각) ========
call PUT /admin/clock "" '{"now":"2026-10-10T01:00:00+09:00"}'
mark inbound-cancel-deadline
resch $S_ "${RV[5]}" "{\"slotId\":$SE}"                       # 옛 슬롯 취소 기한 정각 200
need "취소 기한 정각" "$(last_status)" 200
adv 1
resch $S_ "${RV[6]}" "{\"slotId\":$SE}"                       # 1초 뒤 409(판정 5)
need "취소 기한 1초 뒤" "$(last_status)" "409 DEADLINE_PASSED"

# ======== 10-14 03:00 (SH − 24시간 정각) ========
call PUT /admin/clock "" '{"now":"2026-10-14T03:00:00+09:00"}'
mark inbound-reserve-deadline
resch $S_ "${RV[7]}" "{\"slotId\":$SH}"                       # 새 슬롯 예약 기한 정각 200
need "예약 기한 정각" "$(last_status)" 200
adv 1
resch $S_ "${RV[8]}" "{\"slotId\":$SH}"                       # 1초 뒤 409(판정 6)
need "예약 기한 1초 뒤" "$(last_status)" "409 DEADLINE_PASSED"
call GET /me/inbound-reservations $S_; call GET "/inbound-slots/$SH"

# ======== 10-25 00:00 ========
call PUT /admin/clock "" '{"now":"2026-10-25T00:00:00+09:00"}'
mark alert-scan-2
call POST /me/price-alerts $B_ "{\"productSizeId\":${Z[DX]},\"targetPrice\":60000}"   # DX 판매 입찰(10-31 00:00 만료)로 발동
A[DX]=$(q "SELECT id FROM price_alerts WHERE id>$ma AND user_id=$B_ AND product_size_id=${Z[DX]}"); int_or_die "alert DX" "${A[DX]}"
batch2 price-alert-scan
need "DX 발동" "$(q "SELECT status||' '||(triggered_bid_id=${BID[DX]}) FROM price_alerts WHERE id=${A[DX]}")" "TRIGGERED true"

# ======== 10-31 00:00 (확정·읽음 + 30일 정각, DX 입찰 만료) ========
call PUT /admin/clock "" '{"now":"2026-10-31T00:00:00+09:00"}'
mark day30
edit $B_ "$V5" '{"rating":3,"content":"정각"}'                # confirmed_at + 30일 정각 200
need "수정 기한 정각" "$(last_status)" 200
call GET /me/reviewable-items $B_                             # E5 그대로(정각 포함)
need "쓸 수 있는 항목(정각)" "$(body_of_last | grep -o '"orderItemId":[0-9]*' | paste -sd ' ')" "\"orderItemId\":${E[5]}"
mark bid-expiry
batch2 bid-expiry                                             # DX(·DA·DT) 판매 입찰 + 시드
need "DX 입찰 EXPIRED" "$(q "SELECT status FROM bids WHERE id=${BID[DX]}")" EXPIRED
mark alert-rearm-expired
batch_run price-alert-rearm '^1 0 200$'                       # EXPIRED 발동 입찰 → DX 재무장(DA·DT는 종결 7일 지남)
batch_run price-alert-rearm '^0 0 200$'
need "DX 재무장" "$(q "SELECT count(*)||' '||min(status) FROM price_alerts WHERE id>${A[DX]} AND product_size_id=${Z[DX]}")" "1 ACTIVE"
n_b1=$(q "SELECT min(id) FROM notifications WHERE id>$mn AND user_id=$B_ AND read_at IS NOT NULL"); int_or_die n_b1 "$n_b1"
n_r=$(q "SELECT id FROM notifications WHERE id>$mn AND user_id=$R_"); int_or_die n_r "$n_r"
seed_read=$(q "SELECT count(*) FROM notifications WHERE id<=$mn AND read_at IS NOT NULL"); need "시드 읽은 알림" "$seed_read"
mark notification-purge-fail
inject_on notifications "BEFORE DELETE" "OLD.id = $n_b1"
batch_run notification-purge '^[1-9][0-9]* 1 200$'            # n_b1만 실패, b의 나머지 4건·시드 읽은 알림 삭제
need "격리 실패 뒤 n_b1" "$(q "SELECT count(*) FROM notifications WHERE id=$n_b1")" 1
need "격리 실패 중 b 정리" "$(q "SELECT count(*) FROM notifications WHERE id>$mn AND user_id=$B_ AND read_at IS NOT NULL")" 1
need "격리 실패 중 시드 정리" "$(q "SELECT count(*) FROM notifications WHERE id<=$mn AND read_at IS NOT NULL")" 0
inject_off notifications
mark notification-purge
batch_run notification-purge '^1 0 200$'                      # 트리거 제거 뒤 n_b1만
batch_run notification-purge '^0 0 200$'
need "읽은 지 30일 1초 모자람 — 남김" "$(q "SELECT count(*) FROM notifications WHERE id=$n_r")" 1
need "안 읽은 알림 남김(DX)" "$(q "SELECT count(*) FROM notifications WHERE id>$mn AND user_id=$B_ AND read_at IS NULL AND ref_id=${A[DX]}")" 1
adv 1
mark day30-late
edit $B_ "$V5" '{"rating":2,"content":"늦음"}'                # 30일 + 1초 409
need "수정 기한 1초 뒤" "$(last_status)" "409 DEADLINE_PASSED"
call GET /me/reviewable-items $B_
need "쓸 수 있는 항목(1초 뒤)" "$(body_of_last)" "[] 200"
call GET /me/reviews $B_
mark notification-purge-late
batch_run notification-purge '^1 0 200$'                      # r의 알림(읽음 10-01 00:00:01)이 이제 30일 정각
batch_run notification-purge '^0 0 200$'
need "r 알림 정리" "$(q "SELECT count(*) FROM notifications WHERE id=$n_r")" 0
call GET /me/notifications/unread-count $B_; call GET /me/notifications $B_; call GET /me/notifications $R_
call POST /me/notifications/read-all $B_
need "안 읽은 알림 일괄 읽음(DX·재등록 DT)" "$(body_of_last)" '{"readCount":2} 200'
call GET /me/price-alerts $B_

dump reviews "SELECT id-$mw,order_item_id-$mi,buyer_id-$base,seller_id-$base,rating,content,status,hidden_reason,created_at,updated_at FROM reviews WHERE id>$mw ORDER BY id"
dump price_alerts "SELECT id-$ma,user_id-$base,product_size_id-$mz,target_price,status,expires_at,triggered_bid_id-$mb,closed_at,created_at FROM price_alerts WHERE id>$ma ORDER BY id"
dump wishlists "SELECT id-$mwl,user_id-$base,product_size_id-$mz,created_at FROM wishlists WHERE id>$mwl ORDER BY id"
dump bids "SELECT id-$mb,user_id-$base,product_size_id-$mz,side,price,status,expires_at FROM bids WHERE id>$mb ORDER BY id"
dump inquiries "SELECT id-$mq,user_id-$base,category,title,status,close_reason,created_at,sla_due_at,first_answered_at,answered_at,closed_at FROM inquiries WHERE id>$mq ORDER BY id"
dump inquiry_messages "SELECT id-$mm,inquiry_id-$mq,author,body,created_at FROM inquiry_messages WHERE id>$mm ORDER BY id"
dump inbound_slots "SELECT id-$msl,slot_start,capacity,created_at FROM inbound_slots WHERE id>$msl ORDER BY id"
dump inbound_reservations "SELECT id-$mr,slot_id-$msl,seller_id-$base,order_item_id-$mi,status,created_at,closed_at FROM inbound_reservations WHERE id>$mr ORDER BY id"
dump notifications "SELECT id-$mn,user_id-$base,kind,ref_id-$ma,dedupe_key,created_at,read_at FROM notifications WHERE id>$mn ORDER BY id"
dump order_items "SELECT id-$mi,status,confirmed_at FROM order_items WHERE id>$mi ORDER BY id"
dump disputes "SELECT id-$md,order_item_id-$mi,status,opened_at FROM disputes WHERE id>$md ORDER BY id"
dump admin_audit_logs "SELECT id-$mu,action,target_id,detail,created_at FROM admin_audit_logs WHERE id>$mu ORDER BY id"
# 시드 행(배치가 바꿀 수 있는 것): 정렬 덤프의 md5 + 정리 뒤 남은 시드 알림 수
{ q "SELECT id,status,triggered_bid_id,closed_at FROM price_alerts WHERE id<=$ma ORDER BY id" | md5sum
  q "SELECT id,read_at FROM notifications WHERE id<=$mn ORDER BY id" | md5sum
  q "SELECT id,status,close_reason,closed_at FROM inquiries WHERE id<=$mq ORDER BY id" | md5sum
  q "SELECT id,status FROM bids WHERE id<=$mb ORDER BY id" | md5sum
  q "SELECT count(*) FROM notifications WHERE id<=$mn"; } | dump_text seed.md5
# 격리 실패 로그(ADR-0003 §12의 2·ADR-0007 §2.6): 배치 응답 failed 합 = 격리 실패 ERROR 수(sa-a와 같은 식 — 이 시나리오에 카드 취소 대상 없음)
n_err=$(grep -c ': 대상 [0-9]* 실패(반영 안 됨' "$out/app.log" || true)
sum_failed=$({ grep -o '/admin/batches/[a-z-]* key= → {"processed":[0-9]*,"failed":[0-9]*' "$out/responses.txt" || true; } | awk -F'"failed":' '{n += $2} END {print n + 0}')
need "격리 실패 로그 = 응답 failed 합" "$n_err" "$sum_failed"
need "격리 실패 2건(rearm·purge)" "$sum_failed" 2
printf '%s\n' "$n_err" "$sum_failed" | dump_text log-counts
app_end
