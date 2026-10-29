#!/usr/bin/env bash
# 시나리오 sa-a — 단계 A 묶음 A(ADR-0007 §4 A1 · §5 A2 · §6 A3 · §10 A7 · §2.7 감사 로그):
#   A2 seller-grade-recalc(문턱 등급·판매 제한 STANDARD)·rebate-settlement(PAID·시드 ZERO)·조회, A3 monthly-statement(이월·HELD 수)·
#   지급 계좌 요청·판정·조회(INITIAL·REQUEST), A7 tax-invoice-issue(ISSUE·HOLD·지급일 전 보류 → 지급일 뒤 발행)·정정(연쇄)·조회,
#   A1 리뷰 작성·삭제·숨김·판매자 조회, rating-recalc(분쟁 숨김 → 기각 해제 → 인정 뒤 유지), 리뷰 기한(30일 정각 허용·1초 뒤 409).
#   각 배치는 같은 시각 재실행(0건)까지. 오류 경로 401·400·403·404·409(ITEM_STATE_CONFLICT·DEADLINE_PASSED).
# 사용: scenarios/sa-a.sh <god|layered> <jar> <outdir>  (보통은 tools/equiv/run.sh sa-a)
# 정산은 픽스처로 직접 넣는다(판매자 s: 09-07 PAID·09-14 CARRIED(HELD 1)·09-28 PAID(지급 10-05), 판매자 s2: 09-07 PAID·판매 제한 중).
# 09-28 정산이 시드 마지막 마감(09-21 + 7일)을 넘겨 9월이 닫힌다(§6.4) — rebate-settlement·monthly-statement는 시드 판매자 전체가 대상이다.
# 바뀐 시드 행은 정렬 덤프의 md5로, 새 행은 오프셋 줄 단위로 비교한다.
source "$(dirname "$0")/../lib.sh"
app_begin "$@"
start_db

# 픽스처: 새 사이즈 1개, 사용자 s(판매·정산)·b(구매·리뷰)·c(제3자)·s2(판매 제한 중 판매자)
q "INSERT INTO product_sizes(product_id,size) SELECT min(id),'EXP' FROM products" > /dev/null
for u in s b c; do q "INSERT INTO users(email,name,grade,created_at) VALUES ('$u@exp','$u','BRONZE','2026-09-01T00:00:00+09:00')" > /dev/null; done
q "INSERT INTO users(email,name,grade,sale_restricted_until,created_at) VALUES ('s2@exp','s2','BRONZE','2026-10-20T00:00:00+09:00','2026-09-01T00:00:00+09:00')" > /dev/null
size=$(q "SELECT max(id) FROM product_sizes"); base=$(q "SELECT max(id)-3 FROM users")
S_=$base B_=$((base+1)) C_=$((base+2)) S2_=$((base+3))
mo=$(q "SELECT max(id) FROM orders"); mi=$(q "SELECT max(id) FROM order_items"); mt=$(q "SELECT max(id) FROM trades")
md=$(q "SELECT max(id) FROM disputes"); mr=$(q "SELECT max(id) FROM refunds")
ms=$(q "SELECT max(id) FROM settlements"); msi=$(q "SELECT max(id) FROM settlement_items")
mf=$(q "SELECT max(id) FROM fee_rebates"); mm=$(q "SELECT max(id) FROM monthly_statements")
mv=$(q "SELECT max(id) FROM tax_invoices"); mq=$(q "SELECT max(id) FROM payout_account_requests"); mw=$(q "SELECT max(id) FROM reviews")
mu=$(q "SELECT max(id) FROM admin_audit_logs")
seed_item=$(q "SELECT min(id) FROM order_items")
# 정산 픽스처(ADR-0004 §10의 식을 만족: net = gross − fee − penalty + carry_in, payout/carry_out, PAID ⇔ payout > 0)
q "INSERT INTO settlements(seller_id,period_start,period_end,gross,fee_total,penalty_total,carry_in,net,payout,carry_out,status,paid_at) VALUES
  ($S_,'2026-09-07','2026-09-13',1200000,60000,0,0,1140000,1140000,0,'PAID','2026-09-14T00:00:00+09:00'),
  ($S_,'2026-09-14','2026-09-20',10000,1000,30000,0,-21000,0,-21000,'CARRIED',NULL),
  ($S_,'2026-09-28','2026-10-04',200000,10000,0,-21000,169000,169000,0,'PAID','2026-10-05T00:00:00+09:00'),
  ($S2_,'2026-09-07','2026-09-13',9000000,450000,0,0,8550000,8550000,0,'PAID','2026-09-14T00:00:00+09:00')" > /dev/null
P1=$((ms+1)) P2=$((ms+2)) P3=$((ms+3)) P4=$((ms+4))
q "INSERT INTO settlement_items(settlement_id,order_item_id,amount,fee,status) VALUES
  ($P1,$seed_item,1200000,60000,'INCLUDED'),($P2,$seed_item,10000,1000,'INCLUDED'),($P2,$seed_item,0,0,'HELD'),
  ($P3,$seed_item,200000,10000,'INCLUDED'),($P4,$seed_item,9000000,450000,'INCLUDED')" > /dev/null
# 9월 등급 행(리베이트율의 근거 — §5.3): s PLUS, s2 MASTER
q "INSERT INTO seller_grades(seller_id,grade,effective_from,basis_amount,restricted,created_at) VALUES
  ($S_,'PLUS','2026-09-01',1100000,false,'2026-09-01T00:00:00+09:00'),($S2_,'MASTER','2026-09-01',8500000,false,'2026-09-01T00:00:00+09:00')" > /dev/null
mg=$(q "SELECT max(id) FROM seller_grades")   # 위 9월 픽스처 뒤
initial_user=$(q "SELECT min(user_id) FROM seller_payout_accounts")

# need <이름> <값> [기대]: 경로를 실제로 지났는지. 기대가 없으면 양의 정수여야 한다(두 앱이 함께 지나치지 않았는데 같음으로 끝나지 않게)
need() {
  if [ $# -ge 3 ]; then [ "$2" = "$3" ] || die "경로를 지나지 않음: $1 = '$2' (기대 '$3')"
  else [[ $2 =~ ^[0-9]+$ ]] && [ "$2" -gt 0 ] || die "경로를 지나지 않음: $1 = '$2'"; fi
}
start_pg 3000
start_app 2000
batch2() { call POST "/admin/batches/$1"; call POST "/admin/batches/$1"; }   # 실행 + 같은 시각 재실행(대상 없음)

mark seller-grade-recalc
batch2 seller-grade-recalc                                     # D = 10-01: s(기준 141만 → PLUS)·s2(제한 → STANDARD restricted)
need "s 10월 등급" "$(q "SELECT grade||' '||basis_amount||' '||restricted FROM seller_grades WHERE id>$mg AND seller_id=$S_")" "PLUS 1410000 false"
need "s2 10월 등급" "$(q "SELECT grade||' '||basis_amount||' '||restricted FROM seller_grades WHERE id>$mg AND seller_id=$S2_")" "STANDARD 9000000 true"
mark rebate-settlement
batch2 rebate-settlement                                       # M = 9월(닫힘): s 50‰ × 71,000 = 3,550 PAID · s2 150‰ · 시드 판매자
need "s 리베이트" "$(q "SELECT status||' '||amount FROM fee_rebates WHERE id>$mf AND seller_id=$S_")" "PAID 3550"
need "시드 리베이트 ZERO" "$(q "SELECT count(*) FROM fee_rebates WHERE id>$mf AND seller_id<$base AND status='ZERO'")"
need "시드 리베이트 PAID" "$(q "SELECT count(*) FROM fee_rebates WHERE id>$mf AND seller_id<$base AND status='PAID'")"
mark monthly-statement
batch2 monthly-statement                                       # 9월: s 3건(이월 1·HELD 1)·s2·시드 판매자
need "s 정산서" "$(q "SELECT settlement_count||' '||carried_count||' '||held_count FROM monthly_statements WHERE id>$mm AND seller_id=$S_")" "3 1 1"
need "시드 정산서" "$(q "SELECT count(*) FROM monthly_statements WHERE id>$mm AND seller_id<$base")"
mark tax-invoice-issue
batch2 tax-invoice-issue                                       # P1 ISSUE · P2 HOLD · P3 지급일 전 보류 · P4 ISSUE
need "계산서(첫 실행)" "$(q "SELECT string_agg(settlement_id-$ms||kind, ' ' ORDER BY id) FROM tax_invoices WHERE id>$mv")" "1ISSUE 2HOLD 4ISSUE"
v1=$(q "SELECT id FROM tax_invoices WHERE id>$mv AND settlement_id=$P1"); vh=$(q "SELECT id FROM tax_invoices WHERE id>$mv AND settlement_id=$P2")
mark tax-invoice-correction
call POST "/admin/tax-invoices/$v1/correction" "" '{"supplyAmount":-1}'      # 400
call POST "/admin/tax-invoices/$v1/correction" "" '{}'                       # 누락 400
call POST "/admin/tax-invoices/$v1/correction" "" '{"supplyAmount":"5"}'     # 문자열 400
call POST /admin/tax-invoices/999999999/correction "" '{"supplyAmount":1}'   # 404
call POST "/admin/tax-invoices/$vh/correction" "" '{"supplyAmount":1}'       # HOLD 409
call POST "/admin/tax-invoices/$v1/correction" "" '{"supplyAmount":55555}'   # 200: 원본 CANCELLED · CANCEL · 재발행
vc=$(q "SELECT id FROM tax_invoices WHERE id>$mv AND kind='CANCEL' AND ref_invoice_id=$v1"); vr=$(q "SELECT id FROM tax_invoices WHERE id>$mv AND kind='ISSUE' AND ref_invoice_id=$v1")
[[ $vc =~ ^[0-9]+$ && $vr =~ ^[0-9]+$ ]] || die "정정 실패: '$vc' '$vr'"
call POST "/admin/tax-invoices/$v1/correction" "" '{"supplyAmount":1}'       # 이미 정정된 원본 409
call POST "/admin/tax-invoices/$vc/correction" "" '{"supplyAmount":1}'       # CANCEL 409
call POST "/admin/tax-invoices/$vr/correction" "" '{"supplyAmount":0}'       # 재발행본 다시 정정 200
mark finance-reads
call GET /me/tax-invoices $S_; call GET /me/seller-grade $S_; call GET /me/seller-grade $S2_; call GET /me/seller-grade $C_
call GET /me/fee-rebates $S_; call GET /me/monthly-statements $S_; call GET /me/monthly-statements
call GET /me/fee-rebates abc                                   # 401

mark payout-account
call POST /me/payout-account-requests $S_ '{"bankCode":"999","accountNo":"1234567890"}'    # 은행 코드 400
call POST /me/payout-account-requests $S_ '{"bankCode":"004","accountNo":"12345"}'         # 자릿수 400
call POST /me/payout-account-requests $S_ '{"bankCode":"004","accountNo":" 1234567890"}'   # 공백 400
call POST /me/payout-account-requests $S_ '{"bankCode":"004","accountNo":1234567890}'      # 숫자 400
call POST /me/payout-account-requests $S_ '{"bankCode":"004","accountNo":"1234567890"}'    # 201
call POST /me/payout-account-requests $S_ '{"bankCode":"011","accountNo":"99998888777"}'   # PENDING 있음 409
call GET /me/payout-account $S_                                # 계좌 없음 + pending
r1=$(q "SELECT id FROM payout_account_requests WHERE id>$mq AND user_id=$S_ AND status='PENDING'"); [[ $r1 =~ ^[0-9]+$ ]] || die "요청 준비 실패: $r1"
call POST "/admin/payout-account-requests/$r1/decision" "" '{"reason":"x"}'                # approve 누락 400
call POST "/admin/payout-account-requests/$r1/decision" "" '{"approve":false}'             # 반려 사유 누락 400
call POST "/admin/payout-account-requests/$r1/decision" "" '{"approve":false,"reason":"  "}'   # 빈 사유 400
call POST "/admin/payout-account-requests/$r1/decision" "" '{"approve":true,"reason":5}'   # 사유 형식 400
call POST /admin/payout-account-requests/999999999/decision "" '{"approve":true}'         # 404
call POST "/admin/payout-account-requests/$r1/decision" "" '{"approve":true,"reason":"무시"}'  # 승인 200
call POST "/admin/payout-account-requests/$r1/decision" "" '{"approve":false,"reason":"늦음"}' # 409
call POST /me/payout-account-requests $S_ '{"bankCode":"088","accountNo":"11112222333344"}'
r2=$(q "SELECT id FROM payout_account_requests WHERE id>$mq AND user_id=$S_ AND status='PENDING'"); [[ $r2 =~ ^[0-9]+$ ]] || die "요청 준비 실패: $r2"
call GET /me/payout-account $S_                                # REQUEST + pending
call POST "/admin/payout-account-requests/$r2/decision" "" '{"approve":false,"reason":" 계좌 불일치 "}'   # 반려 200
call GET /me/payout-account $S_                                # REQUEST, pending null
call GET /me/payout-account "$initial_user"                    # INITIAL
call GET /me/payout-account $C_                                # 전부 null

mark review-fixtures
trade() { call POST /bids $S_ "{\"productSizeId\":$size,\"side\":\"SELL\",\"price\":$1,\"validDays\":30}"; call POST /bids $B_ "{\"productSizeId\":$size,\"side\":\"BUY\",\"price\":$1,\"validDays\":30}"; }
for p in 100000 90000 80000 70000 60000 50000 40000; do trade $p; done
t=$(q "SELECT string_agg(id::text, ',' ORDER BY id) FROM trades WHERE id>$mt"); [ "$(tr -cd , <<< "$t" | wc -c)" = 6 ] || die "체결 7건이 아님: $t"
call POST /orders $B_ "{\"tradeIds\":[$t]}" R1
i=$(q "SELECT string_agg(i.id::text, ' ' ORDER BY i.id) FROM order_items i JOIN orders o ON o.id=i.order_id WHERE o.id>$mo AND o.idempotency_key='R1'")
read -r I1 I2 I3 I4 I5 I6 I7 <<< "$i"; [ -n "$I7" ] || die "항목 7건이 아님: $i"
for x in $I1 $I2 $I3 $I4 $I5 $I6 $I7; do
  call POST "/order-items/$x/shipment" $S_; call POST "/admin/order-items/$x/receipt"; call POST "/admin/order-items/$x/inspection" "" '{"result":"PASS"}'; call POST "/admin/order-items/$x/delivery"
done
for x in $I1 $I2 $I3 $I4 $I5 $I7; do call POST "/order-items/$x/confirm" $B_; done       # I6은 DELIVERED로 둔다
mark review-write
call POST "/order-items/$I1/review" "" '{"rating":5,"content":"좋다"}'                    # 401
call POST "/order-items/$I1/review" $B_ '{"rating":6,"content":"좋다"}'                   # 400
call POST "/order-items/$I1/review" $B_ '{"rating":"5","content":"좋다"}'                 # 문자열 400
call POST "/order-items/$I1/review" $B_ '{"rating":5,"content":"  "}'                    # 빈 본문 400
call POST "/order-items/$I1/review" $B_ '{"rating":5}'                                   # 본문 누락 400
call POST /order-items/999999999/review $B_ '{"rating":5,"content":"좋다"}'               # 404
call POST "/order-items/$I1/review" $C_ '{"rating":5,"content":"좋다"}'                   # 구매자 아님 403
call POST "/order-items/$I1/review" $S_ '{"rating":5,"content":"좋다"}'                   # 판매자도 403
call POST "/order-items/$I6/review" $B_ '{"rating":5,"content":"좋다"}'                   # DELIVERED 409
call POST "/order-items/$I1/review" $B_ '{"rating":5,"content":" 아주 좋다 "}'            # 201
call POST "/order-items/$I1/review" $B_ '{"rating":1,"content":"다시"}'                   # 중복 409
call POST "/order-items/$I2/review" $B_ '{"rating":3,"content":"보통"}'
call POST "/order-items/$I3/review" $B_ '{"rating":4,"content":"괜찮다"}'
call POST "/order-items/$I4/review" $B_ '{"rating":2,"content":"별로"}'
rv=$(q "SELECT string_agg(id::text, ' ' ORDER BY id) FROM reviews WHERE id>$mw"); read -r R1 R2 R3 R4 <<< "$rv"; [ -n "$R4" ] || die "리뷰 4건이 아님: $rv"
mark review-delete-hide
call DELETE "/reviews/$R3" $C_                                 # 작성자 아님 403
call DELETE "/reviews/$R3" $B_                                 # 200 DELETED
call DELETE "/reviews/$R3" $B_                                 # 이미 지움 409
call DELETE /reviews/999999999 $B_                             # 404
call POST "/order-items/$I3/review" $B_ '{"rating":5,"content":"재작성"}'                # 지운 뒤 재작성 409
call POST "/admin/reviews/$R2/hide"                            # 200 HIDDEN·ADMIN + 감사
call POST "/admin/reviews/$R2/hide"                            # 409
call POST "/admin/reviews/$R3/hide"                            # DELETED 409
call POST /admin/reviews/999999999/hide                        # 404
call DELETE "/reviews/$R2" $B_                                 # HIDDEN → DELETED
call GET "/sellers/$S_/reviews"; call GET "/sellers/$S_/reviews" abc   # 헤더를 읽지 않는다
call GET /sellers/999999999/reviews                            # 404

mark rating-recalc-dispute
call POST "/order-items/$I1/disputes" $B_                      # I1 DISPUTED
d1=$(q "SELECT id FROM disputes WHERE id>$md AND order_item_id=$I1 AND status='OPEN'"); [[ $d1 =~ ^[0-9]+$ ]] || die "분쟁 준비 실패: $d1"
batch2 rating-recalc                                           # R1 → HIDDEN·DISPUTE, s 평점 INSERT(R4만 VISIBLE)
need "분쟁 숨김" "$(q "SELECT status||' '||hidden_reason FROM reviews WHERE id=$R1")" "HIDDEN DISPUTE"
need "s 평점(1차)" "$(q "SELECT review_count||' '||rating_sum FROM seller_ratings WHERE seller_id=$S_")" "1 2"
call GET "/sellers/$S_/reviews"
mark rating-recalc-reject
call POST "/admin/disputes/$d1/resolution" "" '{"accept":false}'   # 기각 → CONFIRMED
batch2 rating-recalc                                           # R1 → VISIBLE, 평점 UPDATE
need "분쟁 해제" "$(q "SELECT status FROM reviews WHERE id=$R1")" VISIBLE
need "s 평점(2차)" "$(q "SELECT review_count||' '||rating_sum FROM seller_ratings WHERE seller_id=$S_")" "2 7"
mark rating-recalc-accept
call POST "/order-items/$I4/disputes" $B_
d2=$(q "SELECT id FROM disputes WHERE id>$md AND order_item_id=$I4 AND status='OPEN'"); [[ $d2 =~ ^[0-9]+$ ]] || die "분쟁 준비 실패: $d2"
call POST "/admin/disputes/$d2/resolution" "" '{"accept":true}'    # 인정 → REFUNDED_AFTER_CONFIRM
batch2 rating-recalc                                           # R4 → HIDDEN·DISPUTE(풀리지 않음)
need "인정 뒤 숨김" "$(q "SELECT status||' '||hidden_reason FROM reviews WHERE id=$R4")" "HIDDEN DISPUTE"
call GET "/sellers/$S_/reviews"

call PUT /admin/clock "" '{"now":"2026-10-05T00:00:00+09:00"}'
mark tax-invoice-after-payday
batch2 tax-invoice-issue                                       # P3 지급일(10-05) 지남 → ISSUE
need "P3 발행" "$(q "SELECT kind FROM tax_invoices WHERE id>$mv AND settlement_id=$P3")" ISSUE
call GET /me/tax-invoices $S_

mark review-deadline
dl=$(q "SELECT to_char((confirmed_at + interval '30 days') AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD\"T\"HH24:MI:SS.US') || '+09:00' FROM order_items WHERE id=$I7")
call PUT /admin/clock "" "{\"now\":\"$dl\"}"
call POST "/order-items/$I7/review" $B_ '{"rating":4,"content":"정각"}'                   # confirmed_at + 30일 정각 201
adv 1
call POST "/order-items/$I5/review" $B_ '{"rating":4,"content":"늦음"}'                   # 409 DEADLINE_PASSED
call POST "/order-items/$I6/review" $B_ '{"rating":4,"content":"늦음"}'                   # 상태 위반이 먼저 409 ITEM_STATE_CONFLICT
need "기한 정각 리뷰" "$(q "SELECT count(*) FROM reviews WHERE id>$mw AND order_item_id=$I7")" 1
need "기한 지난 리뷰 없음" "$(q "SELECT count(*) FROM reviews WHERE id>$mw AND order_item_id=$I5")" 0
call GET "/sellers/$S_/reviews"
mark audit
call GET "/admin/audit-logs?after=$mu"

dump seller_grades "SELECT id-$mg,seller_id,grade,effective_from,basis_amount,restricted,created_at FROM seller_grades WHERE id>$mg ORDER BY id"
dump fee_rebates "SELECT id-$mf,seller_id,month,grade,fee_base,rate_permille,amount,status,paid_at,created_at FROM fee_rebates WHERE id>$mf ORDER BY id"
dump monthly_statements "SELECT id-$mm,seller_id,month,settlement_count,gross,fee_total,penalty_total,payout_total,carried_count,held_count,last_carry_out,created_at FROM monthly_statements WHERE id>$mm ORDER BY id"
dump tax_invoices "SELECT id-$mv,settlement_id-$ms,seller_id-$base,kind,status,supply_amount,vat_amount,total_amount,ref_invoice_id-$mv,issued_at FROM tax_invoices WHERE id>$mv ORDER BY id"
dump payout_account_requests "SELECT id-$mq,user_id-$base,bank_code,account_no_masked,status,reject_reason,requested_at,decided_at FROM payout_account_requests WHERE id>$mq ORDER BY id"
dump reviews "SELECT id-$mw,order_item_id-$mi,buyer_id-$base,seller_id-$base,rating,content,status,hidden_reason,created_at,updated_at FROM reviews WHERE id>$mw ORDER BY id"
dump seller_ratings "SELECT seller_id-$base,review_count,rating_sum,recalculated_at FROM seller_ratings WHERE seller_id>=$base ORDER BY seller_id"
dump admin_audit_logs "SELECT id-$mu,action,target_id,detail,created_at FROM admin_audit_logs WHERE id>$mu ORDER BY id"
dump order_items "SELECT id-$mi,status,confirmed_at FROM order_items WHERE id>$mi ORDER BY id"
dump disputes "SELECT id-$md,order_item_id-$mi,status,opened_at,resolved_at FROM disputes WHERE id>$md ORDER BY id"
dump refunds "SELECT id-$mr,order_item_id-$mi,card_amount,point_amount,reason,fault,created_at FROM refunds WHERE id>$mr ORDER BY id"
# 시드 행(배치가 바꿀 수 있는 것): 정렬 덤프의 md5
{ q "SELECT id,status,hidden_reason,updated_at FROM reviews WHERE id<=$mw ORDER BY id" | md5sum
  q "SELECT seller_id,review_count,rating_sum,recalculated_at FROM seller_ratings WHERE seller_id<$base ORDER BY seller_id" | md5sum
  q "SELECT id,status,paid_at FROM settlements WHERE id<=$ms ORDER BY id" | md5sum
  q "SELECT id,kind,status FROM tax_invoices WHERE id<=$mv ORDER BY id" | md5sum; } | dump_text seed.md5
app_end
