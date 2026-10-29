"""단계 A 새 테이블 19개의 시드 후처리 패스(ADR-0007 §14, ADR-0006 §3의 4).

- 입력은 Gen 이 닫은 기존 22개 CSV를 다시 읽은 것뿐이다(Gen 의 메모리 상태를 쓰지 않는다).
- 난수는 random.Random(seed ^ 0x5CA1E) 하나. 기존 CSV는 건드리지 않는다.
- 각 행은 NOW(2026-10-01 00:00 KST)에 그 요청·배치가 ADR-0007 계약대로 돌았다면 남았을 모양이다.
"""
import calendar
import csv
import random
import time
from collections import defaultdict
from pathlib import Path

from seedgen import BANKS, DAY, HOUR, KST, MIN, NOW, START, date_str, kst_midnight, r1000, ts

NEW_TABLES = {
    "reviews": "id,order_item_id,buyer_id,seller_id,rating,content,status,hidden_reason,created_at,updated_at",
    "seller_ratings": "seller_id,review_count,rating_sum,recalculated_at",
    "seller_grades": "id,seller_id,grade,effective_from,basis_amount,restricted,created_at",
    "fee_rebates": "id,seller_id,month,grade,fee_base,rate_permille,amount,status,paid_at,created_at",
    "payout_account_requests": "id,user_id,bank_code,account_no_masked,status,reject_reason,requested_at,decided_at",
    "monthly_statements": "id,seller_id,month,settlement_count,gross,fee_total,penalty_total,payout_total,"
                          "carried_count,held_count,last_carry_out,created_at",
    "notifications": "id,user_id,kind,ref_id,dedupe_key,created_at,read_at",
    "wishlists": "id,user_id,product_size_id,created_at",
    "price_alerts": "id,user_id,product_size_id,target_price,status,expires_at,triggered_bid_id,closed_at,created_at",
    "inquiries": "id,user_id,order_item_id,category,title,status,close_reason,created_at,sla_due_at,"
                 "first_answered_at,answered_at,closed_at",
    "inquiry_messages": "id,inquiry_id,author,body,created_at",
    "tax_invoices": "id,settlement_id,seller_id,kind,status,supply_amount,vat_amount,total_amount,ref_invoice_id,issued_at",
    "price_stats_daily": "id,product_size_id,stat_date,trade_count,min_price,max_price,median_price,created_at",
    "admin_audit_logs": "id,action,target_id,detail,created_at",
    "seller_profiles": "user_id,shop_name,intro,vacation_from,vacation_to,created_at,updated_at",
    "shipment_tracking_events": "id,order_item_id,carrier,tracking_no,event_type,occurred_at,received_at",
    "shipment_delays": "id,order_item_id,kind,detected_at",
    "inbound_slots": "id,slot_start,capacity,created_at",
    "inbound_reservations": "id,slot_id,seller_id,order_item_id,status,created_at,closed_at",
}

SELLER_GRADE_MIN = [("MASTER", 8_000_000), ("PRO", 3_000_000), ("PLUS", 1_000_000)]   # §5.1
REBATE_PERMILLE = {"STANDARD": 0, "PLUS": 50, "PRO": 100, "MASTER": 150}             # §5.3
AUDIT_ORDER = ["INBOUND_SLOT_CREATE", "PAYOUT_ACCOUNT_DECISION", "REVIEW_HIDE", "INQUIRY_ANSWER",
               "TAX_INVOICE_CORRECTION", "TRACKING_EVENT", "INBOUND_CHECK_IN"]
REVIEW_TEXTS = ["정품이고 상태 좋아요.", "배송이 빨랐습니다.", "사이즈가 딱 맞네요.", "포장이 꼼꼼했어요.",
                "사진과 같아요. 만족합니다.", "가격 대비 괜찮습니다.", "검수 후 받아서 안심이에요.", "조금 아쉽지만 쓸 만해요."]
INQUIRY_TITLES = {"ORDER": "주문 진행 문의", "PAYMENT": "결제 내역 확인 요청", "SETTLEMENT": "정산 금액 문의",
                  "ACCOUNT": "계정 정보 변경", "OTHER": "기타 문의"}
USER_BODIES = ["확인 부탁드립니다.", "언제쯤 처리될까요?", "추가로 여쭤봅니다.", "답변 감사합니다. 한 가지 더 궁금합니다."]
ADMIN_BODIES = ["확인 후 안내드리겠습니다.", "처리되었습니다. 이용에 참고 부탁드립니다.", "요청하신 내용을 반영했습니다."]
REJECT_REASONS = ["예금주 불일치", "계좌 확인 불가", "서류 미비"]
CARRIERS = ["CJ", "HANJIN", "LOTTE", "POST"]


# ---- 읽기 ----
def pt(s):
    """ts() 형식 'YYYY-MM-DD HH:MM:SS+09' → epoch 초. 빈 값은 None."""
    if not s:
        return None
    return calendar.timegm((int(s[0:4]), int(s[5:7]), int(s[8:10]), int(s[11:13]), int(s[14:16]), int(s[17:19]))) - KST


def pdate(s):
    return kst_midnight(int(s[0:4]), int(s[5:7]), int(s[8:10]))


def read(out, name):
    with open(out / f"{name}.csv", newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def month_add(t, k):
    """KST 달 1일 00:00 인 t 에서 k 달 뒤의 1일 00:00."""
    y, m = int(date_str(t)[0:4]), int(date_str(t)[5:7])
    i = y * 12 + m - 1 + k
    return kst_midnight(i // 12, i % 12 + 1, 1)


def weekday_kst(t):
    return time.gmtime(t + KST).tm_wday


def last_monday(m):
    t = month_add(m, 1) - DAY
    while weekday_kst(t) != 0:
        t -= DAY
    return t


class Out:
    def __init__(self, out):
        self.out, self.counts = out, {}

    def write(self, name, rows):
        with open(self.out / f"{name}.csv", "w", newline="", encoding="utf-8") as f:
            w = csv.writer(f, lineterminator="\n")
            w.writerow(NEW_TABLES[name].split(","))
            w.writerows(rows)
        self.counts[name] = len(rows)


def b(x):
    return "true" if x else "false"


def run(out, seed):
    out = Path(out)
    r = random.Random(seed ^ 0x5CA1E)
    o = Out(out)
    audit = []                                  # (created_at, action, target_id, detail)

    users = {int(u["id"]): u for u in read(out, "users")}
    # 새 기능 행의 사용자 시각 하한: 가입 시각, 단 시드 기간 시작(START) 전 가입자는 START — 마켓 기록이 START 부터다
    user_created = {k: max(pt(u["created_at"]), START) for k, u in users.items()}
    late = [k for k, t in user_created.items() if t > NOW - DAY]
    if late:                                       # 아래 randint 범위들이 '가입 ≤ NOW − 1일'(Gen 의 가입 규칙)에 기댄다
        raise RuntimeError(f"NOW − 1일 뒤 가입 사용자 {len(late)}명 — stage_a 시각 범위 전제가 깨짐")
    sellers = sorted(int(x["user_id"]) for x in read(out, "seller_payout_accounts"))
    sizes = [int(x["id"]) for x in read(out, "product_sizes")]
    size_product = {int(x["id"]): int(x["product_id"]) for x in read(out, "product_sizes")}
    release = {int(x["id"]): int(x["release_price"]) for x in read(out, "products")}
    trades = {int(x["id"]): x for x in read(out, "trades")}
    orders = {int(x["id"]): x for x in read(out, "orders")}
    items = sorted(read(out, "order_items"), key=lambda x: int(x["id"]))
    for it in items:
        tr = trades[int(it["trade_id"])]
        it["_buyer"] = int(orders[int(it["order_id"])]["buyer_id"])
        it["_seller"] = int(tr["seller_id"])
        it["_order_created"] = pt(orders[int(it["order_id"])]["created_at"])
        for c in ("paid_at", "shipped_at", "inspected_at", "delivered_at", "confirmed_at"):
            it["_" + c] = pt(it[c])
    disputes = defaultdict(list)
    for d in read(out, "disputes"):
        disputes[int(d["order_item_id"])].append((pt(d["opened_at"]), pt(d["resolved_at"]) or NOW))
    settlements = sorted(read(out, "settlements"), key=lambda x: int(x["id"]))
    for s in settlements:
        s["_start"] = pdate(s["period_start"])
        s["_paid"] = pt(s["paid_at"])
    included, held = defaultdict(int), defaultdict(int)
    for si in read(out, "settlement_items"):
        if si["status"] == "INCLUDED":
            included[int(si["settlement_id"])] += int(si["amount"])
        elif si["status"] == "HELD":
            held[int(si["settlement_id"])] += 1
    bids = read(out, "bids")

    # ---- A1 reviews: NOW 에 CONFIRMED 인 항목의 30% ----
    reviews = []
    for it in items:
        if it["status"] != "CONFIRMED" or r.random() >= 0.30:
            continue
        conf = it["_confirmed_at"]
        span = min(30 * DAY, NOW - conf)
        wins = disputes.get(int(it["id"]), [])
        c = conf                                   # 분쟁 구간을 끝내 못 피하면 확정 시각(분쟁 제기는 확정 + 1시간 이후)
        for _ in range(20):
            x = conf + r.randint(0, span)
            if not any(a <= x <= z for a, z in wins):
                c = x
                break
        rating = r.choices((5, 4, 3, 2, 1), (45, 30, 15, 6, 4))[0]
        content = r.choice(REVIEW_TEXTS)
        x = r.random()
        status, reason, upd = "VISIBLE", None, c
        if x < 0.05:
            upd = c + r.randint(min(HOUR, NOW - c), NOW - c)
            if x < 0.02:
                status, reason = "HIDDEN", "ADMIN"
            else:
                status = "DELETED"
        rid = len(reviews) + 1
        reviews.append([rid, it["id"], it["_buyer"], it["_seller"], rating, content, status, reason, c, upd])
        if status == "HIDDEN":
            audit.append((upd, "REVIEW_HIDE", rid, None))
    o.write("reviews", [row[:8] + [ts(row[8]), ts(row[9])] for row in reviews])

    # seller_ratings: NOW 의 rating-recalc 결과 — VISIBLE 이 0건인 판매자는 행을 쓰지 않는다(§4.5의 3)
    agg = {}
    for row in reviews:
        cnt, sm = agg.get(row[3], (0, 0))
        if row[6] == "VISIBLE":
            cnt, sm = cnt + 1, sm + row[4]
        agg[row[3]] = (cnt, sm)
    o.write("seller_ratings", [[s, c, sm, ts(NOW)] for s, (c, sm) in sorted(agg.items()) if c > 0])

    # ---- A2 seller_grades: 2025-01 ~ 2026-10 매월 1일 D ----
    by_seller = defaultdict(list)                  # 판매자 → [(period_start, settlement)]
    for s in settlements:
        by_seller[int(s["seller_id"])].append(s)
    grade_rows, grade_at = [], {}
    D = kst_midnight(2025, 1, 1)
    while D <= NOW:
        lo = month_add(D, -3)
        for u in sorted(by_seller):
            ss = by_seller[u]
            ss = [s for s in ss if s["_start"] + 7 * DAY <= D]          # D 00:00 에 이미 있던 정산(마감 = period_end + 1일)
            if not ss:
                continue
            basis = sum(included[int(s["id"])] for s in ss if lo <= s["_start"] < D)
            sr = pt(users[u]["sale_restricted_until"])
            restricted = sr is not None and sr - 30 * DAY <= D < sr
            g = "STANDARD"
            if not restricted:
                for name, mn in SELLER_GRADE_MIN:
                    if basis >= mn:
                        g = name
                        break
            grade_rows.append([len(grade_rows) + 1, u, g, date_str(D), basis, b(restricted), ts(D)])
            grade_at[(u, D)] = g
        D = month_add(D, 1)
    o.write("seller_grades", grade_rows)

    # fee_rebates: 2025-01 ~ 2026-08(닫힌 달), 그 달 등급 행마다
    rebates = []
    M = kst_midnight(2025, 1, 1)
    while M <= kst_midnight(2026, 8, 1):
        closed = last_monday(M) + 7 * DAY
        nxt = month_add(M, 1)
        for u in sorted(by_seller):
            g = grade_at.get((u, M))
            if g is None:
                continue
            base = sum(int(s["fee_total"]) for s in by_seller[u] if M <= s["_start"] < nxt)
            rate = REBATE_PERMILLE[g]
            amt = max(base, 0) * rate // 1000
            paid = amt > 0
            rebates.append([len(rebates) + 1, u, date_str(M), g, base, rate, amt, "PAID" if paid else "ZERO",
                            ts(closed) if paid else None, ts(closed)])
        M = nxt
    o.write("fee_rebates", rebates)

    # ---- A3 payout_account_requests: 판매자 10% 가 1건 ----
    reqs = []
    lo = kst_midnight(2026, 1, 1)
    pool = [u for u in sellers if user_created[u] <= NOW - 72 * HOUR]   # 요청 + 최대 72시간 판정이 NOW 전이 되게
    for u in sorted(r.sample(pool, len(sellers) // 10)):
        t = r.randint(max(lo, user_created[u]), NOW - 72 * HOUR)
        x = r.random()
        st = "APPROVED" if x < 0.7 else "REJECTED" if x < 0.9 else "PENDING"
        dec = None if st == "PENDING" else t + r.randint(HOUR, 72 * HOUR)
        reqs.append([t, u, r.choice(BANKS), "****%04d" % r.randrange(10000), st,
                     r.choice(REJECT_REASONS) if st == "REJECTED" else None, dec])
    reqs.sort(key=lambda x: (x[0], x[1]))
    rows = []
    for i, (t, u, bank, acc, st, why, dec) in enumerate(reqs, 1):
        rows.append([i, u, bank, acc, st, why, ts(t), ts(dec)])
        if dec is not None:
            audit.append((dec, "PAYOUT_ACCOUNT_DECISION", i, st))
    o.write("payout_account_requests", rows)

    # monthly_statements: 2024-10 ~ 2026-08(닫힌 달), 그 달 정산이 있는 판매자
    stm = []
    M = kst_midnight(2024, 10, 1)
    while M <= kst_midnight(2026, 8, 1):
        closed = last_monday(M) + 7 * DAY
        nxt = month_add(M, 1)
        for u in sorted(by_seller):
            ss = [s for s in by_seller[u] if M <= s["_start"] < nxt]
            if not ss:
                continue
            last = max(ss, key=lambda s: s["_start"])
            stm.append([len(stm) + 1, u, date_str(M), len(ss), sum(int(s["gross"]) for s in ss),
                        sum(int(s["fee_total"]) for s in ss), sum(int(s["penalty_total"]) for s in ss),
                        sum(int(s["payout"]) for s in ss), sum(1 for s in ss if s["status"] == "CARRIED"),
                        sum(held[int(s["id"])] for s in ss), int(last["carry_out"]), ts(closed)])
        M = nxt
    o.write("monthly_statements", stm)

    # ---- A4 notifications ----
    notes = []                                     # [user, kind, ref, key, created, read]
    keys = set()
    for s in sorted((s for s in settlements if s["status"] == "PAID" and s["_paid"] is not None
                     and kst_midnight(2026, 9, 1) <= s["_paid"] <= NOW), key=lambda s: (s["_paid"], int(s["id"]))):
        t = s["_paid"]
        rd = t + r.randint(HOUR, 48 * HOUR) if t <= NOW - 7 * DAY else None
        notes.append([int(s["seller_id"]), "SETTLEMENT_PAID", int(s["id"]), f"SETTLEMENT_PAID:{s['id']}", t, rd])
    keys.update(n[3] for n in notes)

    def remind(user, kind, ref, key):
        if key not in keys:
            keys.add(key)
            notes.append([user, kind, ref, key, NOW, None])

    for tid in sorted(trades):                     # §7.1 표 순서대로, 종류마다 id 순
        tr = trades[tid]
        if tr["status"] == "AWAITING_PAYMENT" and NOW < pt(tr["payment_due_at"]) <= NOW + 10 * MIN:
            remind(int(tr["buyer_id"]), "PAYMENT_DUE_SOON", tid, f"PAYMENT_DUE_SOON:{tid}")
    for it in items:
        if it["status"] == "PAID" and it["_paid_at"] + 2 * DAY <= NOW < it["_paid_at"] + 3 * DAY:
            remind(it["_seller"], "SHIPMENT_DUE_SOON", int(it["id"]), f"SHIPMENT_DUE_SOON:{it['id']}")
    for it in items:
        if it["status"] == "CONFIRMED" and it["_confirmed_at"] + 12 * DAY <= NOW <= it["_confirmed_at"] + 14 * DAY:
            remind(it["_buyer"], "DISPUTE_WINDOW_CLOSING", int(it["id"]), f"DISPUTE_WINDOW_CLOSING:{it['id']}")
    for s in settlements:
        if s["status"] == "PAID" and s["_paid"] is not None and NOW - 7 * DAY < s["_paid"] <= NOW:
            remind(int(s["seller_id"]), "SETTLEMENT_PAID", int(s["id"]), f"SETTLEMENT_PAID:{s['id']}")
    for col, kind in (("purchase_restricted_until", "PURCHASE_RESTRICTED"), ("sale_restricted_until", "SALE_RESTRICTED")):
        for u in sorted(users):
            e = pt(users[u][col])
            if e is not None and e > NOW:
                remind(u, kind, u, f"{kind}:{u}:{e}")
    o.write("notifications", [[i, u, k, ref, key, ts(c), ts(rd)] for i, (u, k, ref, key, c, rd) in enumerate(notes, 1)])

    # ---- A5 wishlists · price_alerts ----
    best_sell, last_trade = {}, {}
    for x in bids:
        if x["side"] == "SELL" and x["status"] == "ACTIVE" and pt(x["expires_at"]) > NOW:
            sz, p = int(x["product_size_id"]), int(x["price"])
            if sz not in best_sell or p < best_sell[sz]:
                best_sell[sz] = p
    for tid in sorted(trades):
        last_trade[int(trades[tid]["product_size_id"])] = int(trades[tid]["price"])
    wish, alerts = [], []
    for u in sorted(users):
        if r.random() >= 0.2:
            continue
        for sz in sorted(r.sample(sizes, r.randint(1, 3))):
            wish.append([len(wish) + 1, u, sz, ts(r.randint(user_created[u], NOW - 1))])
            if r.random() >= 0.5:
                continue
            base = last_trade.get(sz) or release[size_product[sz]]
            x = r.random()
            st = "ACTIVE" if x < 0.6 else "EXPIRED" if x < 0.85 else "CANCELLED"
            if st == "EXPIRED" and user_created[u] > NOW - 61 * DAY:
                st = "CANCELLED"                   # 만료까지 갈 만큼 오래된 사용자가 아니다
            if st == "ACTIVE":
                c = r.randint(max(user_created[u], NOW - 60 * DAY + 1), NOW)
                bs = best_sell.get(sz)
                tgt = r1000((bs or base) * r.uniform(0.75, 0.97))
                if bs is not None and tgt >= bs:
                    tgt = bs - 1000
                closed = None
            elif st == "EXPIRED":
                c = r.randint(user_created[u], NOW - 61 * DAY)
                tgt = r1000(base * r.uniform(0.7, 1.0))
                closed = c + 60 * DAY + r.randint(0, DAY)
            else:
                c = r.randint(user_created[u], NOW - HOUR)
                tgt = r1000(base * r.uniform(0.7, 1.0))
                closed = c + r.randint(min(HOUR, NOW - c), min(59 * DAY, NOW - c))
            alerts.append([c, u, sz, tgt, st, c + 60 * DAY, closed])
    o.write("wishlists", wish)
    alerts.sort(key=lambda a: (a[0], a[1], a[2]))
    o.write("price_alerts", [[i, u, sz, tgt, st, ts(exp), None, ts(cl), ts(c)]
                             for i, (c, u, sz, tgt, st, exp, cl) in enumerate(alerts, 1)])

    # ---- A6 inquiries · inquiry_messages ----
    items_of = defaultdict(list)                   # 사용자 → 당사자인 항목(구매자·판매자)
    for it in items:
        items_of[it["_buyer"]].append(it)
        if it["_seller"] != it["_buyer"]:
            items_of[it["_seller"]].append(it)
    gap = lambda: r.randint(HOUR, 48 * HOUR)
    inqs = []                                      # [created, user, item, cat, status, reason, closed, msgs]
    for u in sorted(users):
        if r.random() >= 0.08:
            continue
        for _ in range(r.randint(1, 2)):
            x = r.random()
            st = "CLOSED" if x < 0.8 else "ANSWERED" if x < 0.9 else "OPEN"
            cat = r.choice(list(INQUIRY_TITLES))
            got = None
            for _ in range(10):
                if st == "ANSWERED":
                    authors = ["USER", "ADMIN"] if r.random() < 0.5 else ["USER", "ADMIN", "USER", "ADMIN"]
                    t = NOW - r.randint(HOUR, 7 * DAY - HOUR)       # 답변 + 7일이 아직 안 지나야 ANSWERED 로 남는다
                    times = [t]
                    for _ in authors[1:]:
                        times.append(times[-1] - gap())
                    times.reverse()
                else:
                    if st == "OPEN":
                        authors = ["USER"] * r.randint(2, 4) if r.random() < 0.5 else ["USER", "ADMIN", "USER"]
                    else:
                        authors = (["USER", "ADMIN"] * 2)[:r.randint(2, 4)]
                    times = [r.randint(user_created[u], NOW - DAY)]
                    for _ in authors[1:]:
                        times.append(times[-1] + gap())
                if times[0] < user_created[u] or times[-1] > NOW:
                    continue
                reason = closed = None
                if st == "CLOSED":
                    auto = times[-1] + 7 * DAY + r.randint(0, DAY)
                    if authors[-1] == "ADMIN" and auto <= NOW:
                        reason, closed = "AUTO", auto
                    else:
                        reason, closed = "USER", times[-1] + r.randint(HOUR, 72 * HOUR)
                        if closed > NOW:
                            continue
                got = (authors, times, reason, closed)
                break
            if got is None:
                continue
            authors, times, reason, closed = got
            item = None
            if cat in ("ORDER", "PAYMENT"):
                cands = [it for it in items_of.get(u, ()) if it["_order_created"] <= times[0]]
                if cands and r.random() < 0.8:
                    item = int(r.choice(cands)["id"])
            msgs = [(a, r.choice(USER_BODIES if a == "USER" else ADMIN_BODIES), t) for a, t in zip(authors, times)]
            inqs.append([times[0], u, item, cat, st, reason, closed, msgs])
    inqs.sort(key=lambda q: (q[0], q[1]))
    irows, mrows = [], []
    for i, (c, u, item, cat, st, reason, closed, msgs) in enumerate(inqs, 1):
        adm = [t for a, _, t in msgs if a == "ADMIN"]
        irows.append([i, u, item, cat, INQUIRY_TITLES[cat], st, reason, ts(c), ts(c + 48 * HOUR),
                      ts(adm[0]) if adm else None, ts(adm[-1]) if adm else None, ts(closed)])
        for k, (a, body, t) in enumerate(msgs):
            mrows.append((t, i, k, a, body))
            if a == "ADMIN":
                audit.append((t, "INQUIRY_ANSWER", i, None))
    mrows.sort()
    o.write("inquiries", irows)
    o.write("inquiry_messages", [[j, i, a, body, ts(t)] for j, (t, i, _, a, body) in enumerate(mrows, 1)])

    # ---- A7 tax_invoices: NOW 의 tax-invoice-issue + 0.5% 정정(NOW) ----
    inv = []                                       # [id, settlement, seller, kind, status, supply, vat, total, ref, at]
    for s in settlements:
        if s["status"] == "PAID" and s["_paid"] is not None and s["_paid"] <= NOW:
            sup = max(int(s["fee_total"]), 0)
            inv.append([len(inv) + 1, int(s["id"]), int(s["seller_id"]), "ISSUE", "ISSUED", sup, sup * 10 // 100,
                        sup + sup * 10 // 100, None, NOW])
        elif s["status"] == "CARRIED":
            inv.append([len(inv) + 1, int(s["id"]), int(s["seller_id"]), "HOLD", "DONE", 0, 0, 0, None, NOW])
    for orig in [x for x in inv if x[3] == "ISSUE"]:
        if r.random() >= 0.005:
            continue
        orig[4] = "CANCELLED"
        inv.append([len(inv) + 1, orig[1], orig[2], "CANCEL", "DONE", -orig[5], -orig[6], -orig[7], orig[0], NOW])
        sup = r.randint(0, orig[5])
        inv.append([len(inv) + 1, orig[1], orig[2], "ISSUE", "ISSUED", sup, sup * 10 // 100, sup + sup * 10 // 100,
                    orig[0], NOW])
        audit.append((NOW, "TAX_INVOICE_CORRECTION", orig[0], str(len(inv))))
    o.write("tax_invoices", [x[:9] + [ts(x[9])] for x in inv])

    # ---- A8 price_stats_daily: NOW 전 모든 날짜 × 사이즈, PAID 체결 ----
    day_prices = defaultdict(list)
    for tid in sorted(trades):
        tr = trades[tid]
        if tr["status"] == "PAID":
            day_prices[(tr["matched_at"][:10], int(tr["product_size_id"]))].append(int(tr["price"]))
    ps = []
    for (d, sz) in sorted(day_prices):
        p = sorted(day_prices[(d, sz)])
        ps.append([len(ps) + 1, sz, d, len(p), p[0], p[-1], p[(len(p) - 1) // 2], ts(NOW)])   # 시드가 NOW 에 채운 행(§11.1 지난 날짜는 시드가 채운다)
    o.write("price_stats_daily", ps)

    # ---- A10 seller_profiles: 판매자 50% ----
    prof = []
    for u in sorted(r.sample(sellers, len(sellers) // 2)):
        c = r.randint(user_created[u], NOW - 1)
        upd = r.randint(c, NOW)
        vf = vt = None
        if r.random() < 0.1:
            f = pdate(date_str(NOW)) + r.randint(-20, 20) * DAY
            vf, vt = date_str(f), date_str(f + r.randint(0, 14) * DAY)
        intro = r.choice(["정품만 취급합니다.", "빠른 발송을 약속드립니다.", "한정판 위주로 판매합니다."])   # 빈 문자열은 COPY 가 NULL 로 읽는다
        prof.append([u, f"상점{u:06d}", intro, vf, vt, ts(c), ts(upd)])
    o.write("seller_profiles", prof)

    # ---- A11 shipment_tracking_events ----
    ev = []                                        # (received, item, seq, carrier, no, type, occurred)
    for it in items:
        sh = it["_shipped_at"]
        if sh is None:
            continue
        carrier, no = r.choice(CARRIERS), "%012d" % r.randrange(10 ** 12)
        es = [("PICKED_UP", sh - r.randint(HOUR, min(12 * HOUR, sh - it["_paid_at"])))]
        dl = it["_delivered_at"]
        if dl is not None:
            es.append(("OUT_FOR_DELIVERY", dl - r.randint(HOUR, min(6 * HOUR, dl - it["_inspected_at"]))))
            es.append(("DELIVERED", dl))
        for k, (typ, occ) in enumerate(es):
            base = max(occ, sh)
            ev.append((base + r.randint(0, min(10 * MIN, NOW - base)), int(it["id"]), k, carrier, no, typ, occ))
    ev.sort()
    erows = []
    for i, (rc, item, _, carrier, no, typ, occ) in enumerate(ev, 1):
        erows.append([i, item, carrier, no, typ, ts(occ), ts(rc)])
        audit.append((rc, "TRACKING_EVENT", i, typ))
    o.write("shipment_tracking_events", erows)

    # shipment_delays: NOW 의 delivery-delay-scan
    dl_rows = []
    for kind, ok in (("SHIPMENT_OVERDUE", lambda it: it["status"] == "PAID" and it["_paid_at"] + 3 * DAY <= NOW),
                     ("DELIVERY_LATE", lambda it: it["status"] == "DELIVERING" and it["_inspected_at"] + 5 * DAY <= NOW)):
        for it in items:
            if ok(it):
                dl_rows.append([len(dl_rows) + 1, int(it["id"]), kind, ts(NOW)])
    o.write("shipment_delays", dl_rows)

    # ---- A12 inbound_slots · inbound_reservations ----
    slot_created = kst_midnight(2026, 8, 25)
    slots = []
    d = kst_midnight(2026, 9, 1)
    while d <= kst_midnight(2026, 10, 14):
        for h in range(10, 18):
            slots.append(d + h * HOUR)
        d += DAY
    o.write("inbound_slots", [[i, ts(s), 20, ts(slot_created)] for i, s in enumerate(slots, 1)])
    for i in range(1, len(slots) + 1):
        audit.append((slot_created, "INBOUND_SLOT_CREATE", i, None))
    used = [0] * len(slots)
    res = []                                       # (created, item, slot idx, seller, status, closed)
    ship_from = kst_midnight(2026, 9, 2)
    for it in items:
        sh, paid = it["_shipped_at"], it["_paid_at"]
        if not ((sh is not None and sh >= ship_from) or it["status"] == "PAID") or r.random() >= 0.4:
            continue
        hi = sh if sh is not None else None
        cands = [k for k, s in enumerate(slots) if used[k] < 20 and s >= paid + 24 * HOUR and (hi is None or s <= hi)]
        if not cands:
            continue
        k = r.choice(cands)
        s = slots[k]
        used[k] += 1
        c = r.randint(paid, min(s - 24 * HOUR, NOW))
        if sh is not None:
            st, cl = "CHECKED_IN", s + r.randrange(0, HOUR)
        elif s + HOUR <= NOW:
            st, cl = "NO_SHOW", s + HOUR
        else:
            st, cl = "RESERVED", None
        res.append((c, int(it["id"]), k, it["_seller"], st, cl))
    res.sort()
    rrows = []
    for i, (c, item, k, seller, st, cl) in enumerate(res, 1):
        rrows.append([i, k + 1, seller, item, st, ts(c), ts(cl)])
        if st == "CHECKED_IN":
            audit.append((cl, "INBOUND_CHECK_IN", i, None))
    o.write("inbound_reservations", rrows)

    # ---- A9 admin_audit_logs: 위 관리자 쓰기마다 1행, 시각 순 ----
    audit.sort(key=lambda a: (a[0], AUDIT_ORDER.index(a[1]), a[2]))
    o.write("admin_audit_logs", [[i, act, tgt, det, ts(t)] for i, (t, act, tgt, det) in enumerate(audit, 1)])

    return {name: o.counts[name] for name in NEW_TABLES}
