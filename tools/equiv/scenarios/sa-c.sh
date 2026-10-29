#!/usr/bin/env bash
# 시나리오 sa-c — 단계 A 묶음 C(ADR-0007 §9 A6 · §11 A8·A9 · §12.1 A10 · §12.3 A12):
#   A10 판매자 프로필 PUT 생성·갱신·조회(onVacation), A12 입고 슬롯 생성(시드·픽스처 중복 409, 정시·미래·정원·오프셋 400)·조회,
#   예약(판정 순서: 404 → 403 → PAID 아님 409 → 살아 있는 예약 409 → 정원 409 → 기한 409, 정원과 기한이 함께면 정원)·취소(정각 허용·1초 뒤 409)·
#   체크인(시작 전 409·시작 정각 200·+1시간 409)·inbound-no-show, A6 문의 작성(항목 당사자·제3자 403)·추가 메시지·답변·종결·목록·상세·SLA·
#   inquiry-auto-close(두 시점), A8 price-stats(아래 중앙값)·시세 조회(기간·형식 400)·최근 체결, A9 관리자 조회 7종(키셋 nextAfter → 다음 페이지).
#   배치는 첫 실행 failed 0 · 같은 시각 재실행 processed 0·failed 0까지 단언한다. inquiry-auto-close(첫 시점)는 건별 격리(§2.6)도 밟는다 —
#   문의 Q1의 UPDATE만 트리거로 실패시켜 200 failed 1·나머지 종결 → 트리거를 지우고 재실행하면 Q1만 processed 1.
#   입력 규칙(§19): 확장 연도 날짜 400(19-5)·NBSP·전각 공백뿐인 shopName 400(19-6)·휴가 키 생략 = null(19-14). 오류 경로 400·401·403·404·409.
# 사용: scenarios/sa-c.sh <god|layered> <jar> <outdir>  (보통은 tools/equiv/run.sh sa-c)
# 배치는 시드 전체가 대상이다(시드 RESERVED 예약·ANSWERED 문의). 관리자 조회는 시드 행을 그대로 읽는다. 바뀐 시드 행은 md5로 비교한다.
source "$(dirname "$0")/../lib.sh"
app_begin "$@"
start_db

# 픽스처: 새 사이즈 1개, 사용자 s(판매)·b(구매)·c(제3자)
q "INSERT INTO product_sizes(product_id,size) SELECT min(id),'EXP' FROM products" > /dev/null
for u in s b c; do q "INSERT INTO users(email,name,grade,created_at) VALUES ('$u@exp','$u','BRONZE','2026-09-01T00:00:00+09:00')" > /dev/null; done
size=$(q "SELECT max(id) FROM product_sizes"); base=$(q "SELECT max(id)-2 FROM users")
S_=$base B_=$((base+1)) C_=$((base+2))
# 제한 사용자 3명(관리자 제한 목록의 키셋 페이지 — 시나리오 끝 10-10에 시드의 판매 제한은 남아 있지 않다)
for u in r1 r2 r3; do q "INSERT INTO users(email,name,grade,purchase_restricted_until,sale_restricted_until,created_at) VALUES ('$u@exp','$u','BRONZE','2026-11-30T00:00:00+09:00','2026-11-30T00:00:00+09:00','2026-09-01T00:00:00+09:00')" > /dev/null; done
mo=$(q "SELECT max(id) FROM orders"); mi=$(q "SELECT max(id) FROM order_items"); mt=$(q "SELECT max(id) FROM trades")
mq=$(q "SELECT max(id) FROM inquiries"); mm=$(q "SELECT max(id) FROM inquiry_messages"); mps=$(q "SELECT max(id) FROM price_stats_daily")
msl=$(q "SELECT max(id) FROM inbound_slots"); mr=$(q "SELECT max(id) FROM inbound_reservations"); mu=$(q "SELECT max(id) FROM admin_audit_logs")
seed_size=$(q "SELECT product_size_id FROM price_stats_daily WHERE stat_date BETWEEN '2026-09-01' AND '2026-09-30' GROUP BY 1 ORDER BY count(*) DESC, 1 LIMIT 1")
pen_user=$(q "SELECT seller_id FROM seller_penalties GROUP BY 1 ORDER BY count(*) DESC, 1 LIMIT 1")
warn_user=$(q "SELECT user_id FROM buyer_warnings GROUP BY 1 ORDER BY count(*) DESC, 1 LIMIT 1")
set_seller=$(q "SELECT seller_id FROM settlements WHERE period_start BETWEEN '2026-09-01' AND '2026-09-30' GROUP BY 1 ORDER BY count(*) DESC, 1 LIMIT 1")
for v in seed_size pen_user warn_user set_seller; do int_or_die "$v" "${!v}"; done

# need <이름> <값> [기대]: 경로를 실제로 지났는지. 기대가 없으면 양의 정수여야 한다(두 앱이 함께 지나치지 않았는데 같음으로 끝나지 않게)
need() {
  if [ $# -ge 3 ]; then [ "$2" = "$3" ] || die "경로를 지나지 않음: $1 = '$2' (기대 '$3')"
  else [[ $2 =~ ^[0-9]+$ ]] && [ "$2" -gt 0 ] || die "경로를 지나지 않음: $1 = '$2'"; fi
}
next_after() { local n; n=$(tail -1 "$out/responses.txt" | sed -n 's/.*"nextAfter":\([0-9][0-9]*\).*/\1/p'); [[ $n =~ ^[0-9]+$ ]] || die "nextAfter 없음: $(tail -1 "$out/responses.txt" | cut -c1-200)"; echo "$n"; }
start_pg 3000
start_app 2000
# 실행(failed 0) + 같은 시각 재실행(대상 없음 — processed 0·failed 0)
batch2() { batch_run "$1" '^[0-9]+ 0 200$'; batch_run "$1" '^0 0 200$'; }

mark seller-profile
prof() { call PUT /me/seller-profile "$1" "$2"; }
prof "" '{"shopName":"가게","intro":""}'                                               # 401
prof $S_ '{"shopName":"  ","intro":""}'                                               # 400
prof $S_ '{"shopName":"12345678901234567890123456789012345678901","intro":""}'        # 41자 400
prof $S_ '{"shopName":"가게","intro":null}'                                            # intro null 400
prof $S_ '{"shopName":"가게"}'                                                         # intro 누락 400
prof $S_ '{"shopName":"가게","intro":"","vacationFrom":"2026-10-01"}'                  # 한쪽만 400
prof $S_ '{"shopName":"가게","intro":"","vacationFrom":"2026-10-03","vacationTo":"2026-10-01"}'   # from > to 400
prof $S_ '{"shopName":"가게","intro":"","vacationFrom":"2026-02-30","vacationTo":"2026-03-01"}'   # 없는 날짜 400
prof $S_ '{"shopName":"가게","intro":"","vacationFrom":"+10000-01-01","vacationTo":"+10000-01-02"}'   # 확장 연도 400(§19-5)
need "확장 연도 휴가" "$(last_status)" "400 INVALID_REQUEST"
prof $S_ '{"shopName":"가게","intro":"","vacationFrom":"-0001-01-01","vacationTo":"2026-10-02"}'     # 음수 연도 400(§19-5)
need "음수 연도 휴가" "$(last_status)" "400 INVALID_REQUEST"
prof $S_ '{"shopName":"\u00a0\u3000","intro":""}'                                     # NBSP·전각 공백뿐 400(§19-6)
need "NBSP shopName" "$(last_status)" "400 INVALID_REQUEST"
prof $S_ '{"shopName":" 가게 ","intro":"","vacationFrom":"2026-10-01","vacationTo":"2026-10-02"}' # 생성 200(휴가 중)
prof $S_ '{"shopName":" 가게 ","intro":""}'                                             # 휴가 키 생략 = null(§19-14) 200
need "휴가 키 생략 → null" "$(last_status) $(q "SELECT coalesce(vacation_from::text,'null')||' '||coalesce(vacation_to::text,'null') FROM seller_profiles WHERE user_id=$S_")" "200 null null"
prof $S_ '{"shopName":"새 가게","intro":"소개","vacationFrom":null,"vacationTo":null}'   # 갱신 200
prof $S_ '{"shopName":"새 가게","intro":"소개","vacationFrom":"2026-10-02","vacationTo":"2026-10-02"}'   # 갱신 200(아직 휴가 전)
call GET "/sellers/$S_/profile"; call GET "/sellers/$C_/profile"; call GET /sellers/999999999/profile    # 200 · 프로필 없음 404 · 사용자 없음 404

mark inbound-fixtures
trade() { call POST /bids $S_ "{\"productSizeId\":$size,\"side\":\"SELL\",\"price\":$1,\"validDays\":30}"; call POST /bids $B_ "{\"productSizeId\":$size,\"side\":\"BUY\",\"price\":$1,\"validDays\":30}"; }
for p in 100000 90000 80000 70000 60000 50000; do trade $p; done
t=$(q "SELECT string_agg(id::text, ',' ORDER BY id) FROM trades WHERE id>$mt")
call POST /orders $B_ "{\"tradeIds\":[$t]}" K1
i=$(q "SELECT string_agg(i.id::text, ' ' ORDER BY i.id) FROM order_items i JOIN orders o ON o.id=i.order_id WHERE o.id>$mo AND o.idempotency_key='K1'")
read -r I1 I2 I3 I4 I5 I6 <<< "$i"; [ -n "$I6" ] || die "항목 6건이 아님: $i"
trade 10000                                                    # 결제하지 않는 체결(시세 통계에서 빠진다)
call POST "/order-items/$I6/shipment" $S_                      # I6은 PAID가 아님
mark inbound-slots
slot() { call POST /admin/inbound-slots "" "$1"; }
slot '{"slotStart":"2026-10-03T09:30:00+09:00","capacity":1}'  # 정시 아님 400
slot '{"slotStart":"2026-09-30T09:00:00+09:00","capacity":1}'  # 과거 400
slot '{"slotStart":"2026-10-01T00:00:00+09:00","capacity":1}'  # 현재 정각 400
slot '{"slotStart":"2026-10-03T09:00:00+09:00","capacity":0}'  # 정원 400
slot '{"slotStart":"2026-10-03T09:00:00+09:00","capacity":101}'
slot '{"slotStart":"2026-10-03T09:00:00","capacity":1}'        # 오프셋 없음 400
slot '{"slotStart":"2026-10-03T09:00:00+09:00"}'               # 누락 400
slot '{"slotStart":"2026-10-03T10:00:00+09:00","capacity":5}'  # 시드 슬롯과 같은 시각 409
slot '{"slotStart":"2026-10-03T00:00:00Z","capacity":1}'       # SL1 = 10-03 09:00 KST(UTC 표기) 201
slot '{"slotStart":"2026-10-03T09:00:00+09:00","capacity":3}'  # 같은 시각 409
slot '{"slotStart":"2026-10-03T08:00:00+09:00","capacity":2}'  # SL2 201
slot '{"slotStart":"2026-10-01T20:00:00+09:00","capacity":1}'  # SL3(예약 기한 지남) 201
slot '{"slotStart":"2026-10-04T09:00:00+09:00","capacity":5}'  # SL4(취소 기한 정각용) 201
sl=$(q "SELECT string_agg(id::text, ' ' ORDER BY id) FROM inbound_slots WHERE id>$msl"); read -r SL1 SL2 SL3 SL4 <<< "$sl"; [ -n "$SL4" ] || die "슬롯 4개가 아님: $sl"
call GET "/inbound-slots?date=2026-10-03"; call GET "/inbound-slots?date=2026-10-03" abc   # 헤더를 읽지 않는다
call GET "/inbound-slots?date=2026-10-3"; call GET /inbound-slots                          # 400 · 누락 400
call GET "/inbound-slots?date=%2B10000-01-01"                  # 확장 연도 400(§19-5)
need "확장 연도 date" "$(last_status)" "400 INVALID_REQUEST"
mark inbound-reserve
rsv() { call POST /me/inbound-reservations "$1" "{\"slotId\":$2,\"orderItemId\":$3}"; }
rsv $S_ 999999999 "$I1"                                        # 슬롯 404
rsv $S_ "$SL1" 999999999                                       # 항목 404
rsv $C_ "$SL1" "$I1"                                           # 판매자 아님 403
rsv $B_ "$SL1" "$I1"                                           # 구매자도 403
rsv $S_ "$SL1" "$I6"                                           # PAID 아님 409
call POST /me/inbound-reservations $S_ "{\"slotId\":$SL1}"     # 누락 400
rsv $S_ "$SL1" "$I1"                                           # R1 201
rsv $S_ "$SL2" "$I1"                                           # 살아 있는 예약 409
rsv $S_ "$SL1" "$I2"                                           # 정원 409
rsv $S_ "$SL3" "$I2"                                           # 기한(slot − 24시간) 지남 409 DEADLINE_PASSED
rsv $S_ "$SL2" "$I2"                                           # R2 201
rsv $S_ "$SL2" "$I3"                                           # R3 201 → 취소
rv=$(q "SELECT string_agg(id::text, ' ' ORDER BY id) FROM inbound_reservations WHERE id>$mr"); read -r R1 R2 R3 <<< "$rv"; [ -n "$R3" ] || die "예약 3건이 아님: $rv"
call DELETE "/me/inbound-reservations/$R3" $C_                 # 403
call DELETE /me/inbound-reservations/999999999 $S_             # 404
call DELETE "/me/inbound-reservations/$R3" $S_                 # 200 CANCELLED
call DELETE "/me/inbound-reservations/$R3" $S_                 # 409
rsv $S_ "$SL2" "$I3"                                           # 취소 뒤 다시 R4 201 (SL2 정원 2 참)
R4=$(q "SELECT max(id) FROM inbound_reservations WHERE id>$mr AND order_item_id=$I3")
rsv $S_ "$SL4" "$I5"                                           # R5 201
R5=$(q "SELECT max(id) FROM inbound_reservations WHERE id>$mr AND order_item_id=$I5")
call POST "/admin/inbound-reservations/$R1/check-in"           # 슬롯 시작 전 409 ITEM_STATE_CONFLICT
call POST /admin/inbound-reservations/999999999/check-in       # 404
call GET /me/inbound-reservations $S_; call GET /me/inbound-reservations $C_

mark inquiry
inq() { call POST /me/inquiries "$1" "$2"; }
long=$(printf '가%.0s' $(seq 2001))
inq "" '{"category":"OTHER","title":"t","body":"b"}'                                  # 401
inq $B_ '{"category":"other","title":"t","body":"b"}'                                 # category 400
inq $B_ '{"category":"OTHER","title":"  ","body":"b"}'                                # 빈 제목 400
inq $B_ '{"category":"OTHER","title":"t"}'                                            # 본문 누락 400
inq $B_ "{\"category\":\"OTHER\",\"title\":\"t\",\"body\":\"$long\"}"                 # 2,001자 400
inq $B_ '{"category":"ORDER","title":"t","body":"b","orderItemId":999999999}'         # 항목 404
inq $C_ "{\"category\":\"ORDER\",\"title\":\"t\",\"body\":\"b\",\"orderItemId\":$I1}" # 당사자 아님 403
inq $B_ '{"category":"OTHER","title":"배송 문의","body":" 언제 오나요 "}'                # Q1 201
inq $B_ "{\"category\":\"ORDER\",\"title\":\"항목 문의\",\"body\":\"구매자\",\"orderItemId\":$I1}"   # Q2 201 (주문 → 항목 KEY SHARE)
inq $S_ "{\"category\":\"SETTLEMENT\",\"title\":\"판매자 문의\",\"body\":\"판매자\",\"orderItemId\":$I1}"   # Q3 201 (판매자)
inq $C_ '{"category":"ACCOUNT","title":"계정","body":"답 없음"}'                         # Q4 201 (늦은 첫 답변)
qv=$(q "SELECT string_agg(id::text, ' ' ORDER BY id) FROM inquiries WHERE id>$mq"); read -r Q1 Q2 Q3 Q4 <<< "$qv"; [ -n "$Q4" ] || die "문의 4건이 아님: $qv"
call POST "/me/inquiries/$Q1/messages" $B_ '{"body":"추가"}'   # 200 OPEN
call POST "/me/inquiries/$Q1/messages" $C_ '{"body":"추가"}'   # 작성자 아님 403
call POST "/me/inquiries/$Q1/messages" $B_ '{"body":"  "}'     # 400
call POST /me/inquiries/999999999/messages $B_ '{"body":"x"}'  # 404
call POST "/admin/inquiries/$Q1/answer" "" '{"body":"답변 1"}' # ANSWERED + 감사
call POST "/admin/inquiries/$Q1/answer" "" '{}'                # 400
call POST /admin/inquiries/999999999/answer "" '{"body":"x"}'  # 404
call POST "/me/inquiries/$Q1/messages" $B_ '{"body":"다시"}'   # ANSWERED → OPEN(answered_at 유지)
adv 60
call POST "/admin/inquiries/$Q1/answer" "" '{"body":"답변 2"}' # 다시 ANSWERED(first_answered_at 유지)
call POST "/admin/inquiries/$Q2/answer" "" '{"body":"답변"}'
call POST "/me/inquiries/$Q3/close" $C_                        # 403
call POST "/me/inquiries/$Q3/close" $S_                        # 200 CLOSED·USER
call POST "/me/inquiries/$Q3/close" $S_                        # 409
call POST "/me/inquiries/$Q3/messages" $S_ '{"body":"x"}'      # 409
call POST "/admin/inquiries/$Q3/answer" "" '{"body":"x"}'      # 409
call GET /me/inquiries $B_; call GET "/me/inquiries/$Q2" $B_; call GET "/me/inquiries/$Q3" $S_
call GET "/me/inquiries/$Q2" $S_                               # 판매자라도 작성자 아님 403
call GET /me/inquiries/999999999 $B_                           # 404

call PUT /admin/clock "" '{"now":"2026-10-02T08:30:00+09:00"}'
mark price-stats
batch2 price-stats                                             # d = 10-01: 6건(PAID), 아래 중앙값 70000
need "시세 통계" "$(q "SELECT trade_count||' '||min_price||' '||median_price||' '||max_price FROM price_stats_daily WHERE id>$mps AND product_size_id=$size")" "6 50000 70000 100000"
ps() { call GET "/product-sizes/$1/price-stats?$2" abc; }      # 헤더를 읽지 않는다
ps "$size" "from=2026-10-01&to=2026-10-01"; ps "$seed_size" "from=2026-09-01&to=2026-09-30"
ps "$seed_size" "from=2026-07-02&to=2026-10-01"                # 92일 200
ps "$seed_size" "from=2026-07-01&to=2026-10-01"                # 93일 400
ps "$seed_size" "from=2026-09-30&to=2026-09-01"                # from > to 400
ps "$seed_size" "from=2026-09-01"                              # to 누락 400
ps "$seed_size" "from=2026-9-01&to=2026-09-30"                 # 형식 400
ps 999999999 "from=2026-09-01&to=2026-09-30"                   # 404
call GET "/product-sizes/$size/recent-trades"; call GET "/product-sizes/$seed_size/recent-trades"; call GET /product-sizes/999999999/recent-trades
mark inbound-deadlines
call GET "/sellers/$S_/profile"                                # 휴가 중(10-02)
rsv $S_ "$SL2" "$I4"                                           # SL2 정원 참 + 기한 지남 → 정원 409 ITEM_STATE_CONFLICT
call DELETE "/me/inbound-reservations/$R4" $S_                 # R4 취소 200(기한 전)
rsv $S_ "$SL2" "$I4"                                           # 정원 남음 + 기한 지남(10-02 08:00) → 409 DEADLINE_PASSED
adv 3600                                                       # 09:30 — SL1 기한(10-02 09:00)도 지남
rsv $S_ "$SL1" "$I4"                                           # 정원 참 + 기한 지남 → 정원 409

call PUT /admin/clock "" '{"now":"2026-10-03T01:00:00+09:00"}'
mark inquiry-late-answer
call GET "/me/inquiries/$Q4" $C_                               # 미답변·SLA 지남 → slaBreached
call POST "/admin/inquiries/$Q4/answer" "" '{"body":"늦은 답변"}'
call GET "/me/inquiries/$Q4" $C_                               # first_answered_at > sla_due_at → slaBreached
call PUT /admin/clock "" '{"now":"2026-10-03T07:00:01+09:00"}'
mark inbound-cancel-deadline
call DELETE "/me/inbound-reservations/$R1" $S_                 # slot − 2시간 + 1초 → 409 DEADLINE_PASSED
call PUT /admin/clock "" '{"now":"2026-10-03T09:00:00+09:00"}'
mark inbound-check-in
call POST "/admin/inbound-reservations/$R1/check-in"           # 시작 정각 200 + 감사
call POST "/admin/inbound-reservations/$R1/check-in"           # 409
call POST "/admin/inbound-reservations/$R2/check-in"           # SL2 + 1시간 정각 409 DEADLINE_PASSED
call DELETE "/me/inbound-reservations/$R1" $S_                 # CHECKED_IN 취소 409
mark inbound-no-show
batch2 inbound-no-show                                         # R2(SL2 + 1시간 정각) + 시드 RESERVED
need "체크인" "$(q "SELECT status FROM inbound_reservations WHERE id=$R1")" CHECKED_IN
need "노쇼" "$(q "SELECT status FROM inbound_reservations WHERE id=$R2")" NO_SHOW
need "시드 노쇼" "$(q "SELECT count(*) FROM inbound_reservations WHERE id<=$mr AND status='NO_SHOW' AND closed_at='2026-10-03T09:00:00+09:00'")"
call GET /me/inbound-reservations $S_; call GET "/inbound-slots?date=2026-10-03"
call PUT /admin/clock "" '{"now":"2026-10-04T07:00:00+09:00"}'
mark inbound-cancel-on-time
call DELETE "/me/inbound-reservations/$R5" $S_                 # slot − 2시간 정각 → 200
need "취소 기한 정각" "$(last_status) $(q "SELECT status FROM inbound_reservations WHERE id=$R5")" "200 CANCELLED"

call PUT /admin/clock "" '{"now":"2026-10-08T00:01:00+09:00"}'
mark inquiry-auto-close-fail
inject_on inquiries "BEFORE UPDATE" "OLD.id = $Q1"
batch_run inquiry-auto-close '^[1-9][0-9]* 1 200$'             # Q1만 실패(롤백), Q2·시드 ANSWERED 종결 → failed 1
need "격리 실패 뒤 Q1" "$(q "SELECT status FROM inquiries WHERE id=$Q1")" ANSWERED
need "격리 실패 중 Q2" "$(q "SELECT close_reason FROM inquiries WHERE id=$Q2")" AUTO
inject_off inquiries
mark inquiry-auto-close-1
batch_run inquiry-auto-close '^1 0 200$'                       # 트리거 제거 뒤 Q1(답변 10-01 00:01)만 남음
batch_run inquiry-auto-close '^0 0 200$'                       # 같은 시각 재실행 0건
need "Q1·Q2 자동 종결" "$(q "SELECT string_agg(close_reason, ' ' ORDER BY id) FROM inquiries WHERE id IN ($Q1,$Q2)")" "AUTO AUTO"
need "Q4 아직" "$(q "SELECT status FROM inquiries WHERE id=$Q4")" ANSWERED
call PUT /admin/clock "" '{"now":"2026-10-10T01:00:00+09:00"}'
mark inquiry-auto-close-2
batch2 inquiry-auto-close                                      # Q4(답변 + 7일 정각)
need "Q4 자동 종결" "$(q "SELECT close_reason FROM inquiries WHERE id=$Q4")" AUTO
call GET /me/inquiries $B_; call GET /me/inquiries $C_

mark admin-queries
call GET "/admin/users/restricted?type=purchase"
call GET "/admin/users/restricted?type=sale&limit=2"; a=$(next_after); call GET "/admin/users/restricted?type=sale&limit=2&after=$a"
call GET /admin/users/restricted                               # type 누락 400
call GET "/admin/users/restricted?type=PURCHASE"               # 400
call GET "/admin/users/restricted?type=sale&limit=0"           # 400
call GET "/admin/users/restricted?type=sale&limit=101"         # 400
call GET "/admin/users/restricted?type=sale&limit=abc"         # 400
call GET "/admin/users/restricted?type=sale&after=-1"          # 400
call GET "/admin/users/$pen_user/summary"; call GET "/admin/users/$S_/summary"; call GET /admin/users/999999999/summary
call GET "/admin/users/$pen_user/penalties?limit=1"; a=$(next_after); call GET "/admin/users/$pen_user/penalties?limit=1&after=$a"
call GET /admin/users/999999999/penalties
call GET "/admin/users/$warn_user/warnings?limit=1"; a=$(next_after); call GET "/admin/users/$warn_user/warnings?limit=1&after=$a"
call GET /admin/users/999999999/warnings
call GET "/admin/settlements?status=CARRIED&limit=3"; a=$(next_after); call GET "/admin/settlements?status=CARRIED&limit=3&after=$a"
call GET "/admin/settlements?sellerId=$set_seller&from=2026-09-01&to=2026-09-30"
call GET "/admin/settlements?sellerId=999999999"               # 없는 사용자 → 빈 목록
call GET "/admin/settlements?from=2026-09-30&to=2026-09-01"    # 400
call GET "/admin/settlements?status=HELD"                      # 400
call GET "/admin/settlements?sellerId=abc"                     # 400
call GET "/admin/disputes?status=OPEN"; call GET "/admin/disputes?status=open"; call GET "/admin/disputes?limit=100"
call GET "/admin/audit-logs?after=$mu"; call GET "/admin/audit-logs?after=$mu&action=INQUIRY_ANSWER&limit=2"
a=$(next_after); call GET "/admin/audit-logs?after=$a&action=INQUIRY_ANSWER&limit=2"
call GET "/admin/audit-logs?action=BAD"                        # 400

dump inquiries "SELECT id-$mq,user_id-$base,order_item_id-$mi,category,title,status,close_reason,created_at,sla_due_at,first_answered_at,answered_at,closed_at FROM inquiries WHERE id>$mq ORDER BY id"
dump inquiry_messages "SELECT id-$mm,inquiry_id-$mq,author,body,created_at FROM inquiry_messages WHERE id>$mm ORDER BY id"
dump price_stats_daily "SELECT id-$mps,product_size_id,stat_date,trade_count,min_price,max_price,median_price,created_at FROM price_stats_daily WHERE id>$mps ORDER BY id"
dump seller_profiles "SELECT user_id-$base,shop_name,intro,vacation_from,vacation_to,created_at,updated_at FROM seller_profiles WHERE user_id>=$base ORDER BY user_id"
dump inbound_slots "SELECT id-$msl,slot_start,capacity,created_at FROM inbound_slots WHERE id>$msl ORDER BY id"
dump inbound_reservations "SELECT id-$mr,slot_id-$msl,seller_id-$base,order_item_id-$mi,status,created_at,closed_at FROM inbound_reservations WHERE id>$mr ORDER BY id"
dump admin_audit_logs "SELECT id-$mu,action,target_id,detail,created_at FROM admin_audit_logs WHERE id>$mu ORDER BY id"
dump order_items "SELECT id-$mi,status FROM order_items WHERE id>$mi ORDER BY id"
# 시드 행(배치가 바꿀 수 있는 것): 정렬 덤프의 md5
{ q "SELECT id,status,closed_at FROM inbound_reservations WHERE id<=$mr ORDER BY id" | md5sum
  q "SELECT id,status,close_reason,closed_at FROM inquiries WHERE id<=$mq ORDER BY id" | md5sum; } | dump_text seed.md5
q "SELECT status,count(*) FROM inbound_reservations WHERE id<=$mr GROUP BY 1 ORDER BY 1" | dump_text seed_reservation_status
app_end
