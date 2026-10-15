#!/usr/bin/env python3
"""합성 데이터 생성기: ADR-0002(§9 개정 포함) 절차를 시간순으로 시뮬레이션해 상태가 일관된 CSV를 쓴다.

- 시드 고정: 같은 --seed·--users 면 바이트 단위로 같은 CSV.
- 기간: 2024-10-01 00:00 KST ~ 2026-10-01 00:00 KST(고정 현재 시각). 그 시점에 진행 중인 건도 남긴다.
- 행은 상태가 더 바뀌지 않을 때 쓰고, 진행 중인 건은 끝에서 현재 상태로 쓴다.
- 개인정보 없음: 이름·이메일·계좌는 규칙으로 만든 가짜 값.

사용: python3 seedgen.py --users 1000 --out /path/csv [--seed 42]
ponytail: 매칭 엔진의 가격·시간 우선순위는 체결 단위로만 맞춘다(체결가 = 먼저 등록된 입찰 가격).
         동시에 걸린 다른 입찰과의 우선순위까지는 재현하지 않는다. 필요하면 상품 사이즈별 호가창 시뮬레이션으로 바꾼다.
"""
import argparse
import csv
import hashlib
import heapq
import json
import os
import random
import time
from bisect import bisect_right, insort
from collections import deque
from pathlib import Path

KST = 9 * 3600
DAY = 86400
MIN = 60
HOUR = 3600


def kst_midnight(y, m, d):
    import calendar
    return calendar.timegm((y, m, d, 0, 0, 0)) - KST


START = kst_midnight(2024, 10, 1)
NOW = kst_midnight(2026, 10, 1)

TABLES = {
    "users": "id,email,name,grade,purchase_restricted_until,sale_restricted_until,created_at",
    "user_grade_history": "id,user_id,grade,effective_from,basis_amount",
    "seller_payout_accounts": "user_id,bank_code,account_no_masked",
    "categories": "id,name,sale_fee_rate",
    "products": "id,category_id,brand,name,model_no,release_price",
    "product_sizes": "id,product_id,size",
    "bids": "id,user_id,product_size_id,side,price,status,expires_at,created_at",
    "bid_events": "id,bid_id,from_status,to_status,trade_id,created_at",
    "trades": "id,buy_bid_id,sell_bid_id,buyer_id,seller_id,product_size_id,price,status,matched_at,payment_due_at",
    "buyer_warnings": "id,user_id,trade_id,created_at",
    "orders": "id,buyer_id,status,item_total,buyer_fee_total,shipping_fee,point_used,card_amount,idempotency_key,created_at,paid_at",
    "order_items": "id,order_id,trade_id,price,buyer_fee,card_share,point_share,shipping_share,status,"
                   "paid_at,shipped_at,inspected_at,delivered_at,confirmed_at,cancelled_at",
    "pg_transactions": "id,order_id,kind,amount,status,pg_tx_id,idempotency_key,requested_at,responded_at",
    "refunds": "id,order_item_id,card_amount,point_amount,buyer_fee_amount,shipping_amount,reason,fault,created_at",
    "inspections": "id,order_item_id,result,reason,inspected_at",
    "disputes": "id,order_item_id,status,opened_at,resolved_at",
    "point_ledger": "id,user_id,kind,status,amount,remaining,expires_at,order_item_id,order_id,created_at",
    "point_allocations": "id,entry_id,earn_entry_id,amount",
    "point_balances": "user_id,available,pending,updated_at",
    "settlements": "id,seller_id,period_start,period_end,gross,fee_total,penalty_total,carry_in,net,payout,carry_out,status,paid_at",
    "settlement_items": "id,settlement_id,order_item_id,amount,fee,status",
    "seller_penalties": "id,seller_id,order_item_id,amount,settlement_id,created_at",
}

# (이름, 판매 수수료 ‰, 상품 비중, 사이즈 목록)
CATEGORIES = [
    ("SHOES", 60, 50, [str(s) for s in range(220, 305, 5)]),
    ("APPAREL", 80, 25, ["XS", "S", "M", "L", "XL", "XXL"]),
    ("BAGS", 100, 15, ["ONE"]),
    ("TECH", 40, 10, ["64GB", "128GB", "256GB"]),
]
BRANDS = ["Arcadia", "Borealis", "Cobalt", "Dune", "Ember", "Fjord", "Granite", "Halo", "Ion", "Juniper",
          "Kestrel", "Lumen", "Monarch", "Nimbus", "Onyx", "Pinnacle", "Quartz", "Rook", "Sable", "Tundra"]
GRADES = ["BRONZE", "SILVER", "GOLD", "VIP"]
EARN_PERMILLE = {"BRONZE": 10, "SILVER": 15, "GOLD": 20, "VIP": 30}
GRADE_MIN = [("VIP", 5_000_000), ("GOLD", 2_000_000), ("SILVER", 500_000)]
BANKS = ["004", "011", "020", "081", "088", "090"]
INSPECT_FAIL_REASONS = ["오염", "사이즈 불일치", "가품 의심", "구성품 누락", "손상"]

# 이벤트 우선순위(같은 시각일 때): 배치가 먼저
P_BATCH, P_EVENT = 0, 1


def ts(t):
    if t is None:
        return None
    g = time.gmtime(t + KST)
    return f"{g.tm_year:04d}-{g.tm_mon:02d}-{g.tm_mday:02d} {g.tm_hour:02d}:{g.tm_min:02d}:{g.tm_sec:02d}+09"


def date_str(t):
    g = time.gmtime(t + KST)
    return f"{g.tm_year:04d}-{g.tm_mon:02d}-{g.tm_mday:02d}"


def r1000(x):
    return max(10_000, int(x) // 1000 * 1000)


def buyer_fee(price):
    return max(price * 3 // 100, 1000)


class Gen:
    def __init__(self, users, seed, out):
        self.rng = random.Random(seed)
        self.n_users = users
        self.scale = users / 500_000
        self.out = Path(out)
        self.out.mkdir(parents=True, exist_ok=True)
        self.files, self.w, self.counts = {}, {}, {}
        for name, cols in TABLES.items():
            f = open(self.out / f"{name}.csv", "w", newline="", encoding="utf-8")
            self.files[name] = f
            self.w[name] = csv.writer(f, lineterminator="\n")
            self.w[name].writerow(cols.split(","))
            self.counts[name] = 0
        self.ids = {name: 0 for name in TABLES}
        self.heap, self.seq = [], 0

    # ---- 공통 ----
    def nid(self, table):
        self.ids[table] += 1
        return self.ids[table]

    def row(self, table, *vals):
        self.w[table].writerow(vals)
        self.counts[table] += 1

    def push(self, t, prio, kind, obj):
        self.seq += 1
        heapq.heappush(self.heap, (t, prio, self.seq, kind, obj))

    def key(self):
        return "%032x" % self.rng.getrandbits(128)

    # ---- 정적 데이터 ----
    def make_users(self):
        r, n = self.rng, self.n_users
        pre = int(n * 0.3)
        times = [START - DAY * 365 + r.randrange(DAY * 365) for _ in range(pre)]
        times += [START + r.randrange(NOW - START - DAY) for _ in range(n - pre)]
        times.sort()
        self.user_created = [0] + times                      # 1-based
        self.grade = ["BRONZE"] * (n + 1)
        self.purch_restr = [None] * (n + 1)                   # (시작, 끝)
        self.sale_restr = [None] * (n + 1)
        self.warn_times = {}
        self.fault_times = {}
        self.lots = {}                                        # user → deque[lot]
        self.pending_earn = {}                                # user → 합
        self.bal_touched = {}                                 # user → 마지막 변경 시각
        self.month_conf = {}                                  # user → {월 index: 확정 금액}
        for u in range(1, n + 1):
            if u % 5 == 0:
                self.row("seller_payout_accounts", u, r.choice(BANKS), "****" + "%04d" % r.randrange(10000))

    def joined(self, t):
        return bisect_right(self.user_created, t) - 1

    def make_products(self):
        r = self.rng
        cat_ids = []
        for i, (name, fee, _, _) in enumerate(CATEGORIES, 1):
            self.row("categories", i, name, "%.4f" % (fee / 1000))
            cat_ids.append(i)
        n_products = max(200, int(20_000 * self.scale))
        weights = [c[2] for c in CATEGORIES]
        self.size_ref, self.size_cat, self.prod_sizes = [0], [0], []
        for pid in range(1, n_products + 1):
            ci = r.choices(range(len(CATEGORIES)), weights)[0]
            name, fee, _, sizes = CATEGORIES[ci]
            release = r1000(min(2_000_000, 50_000 * r.lognormvariate(1.2, 0.6)))
            market = release * r.uniform(0.8, 2.2)
            brand = r.choice(BRANDS)
            self.row("products", pid, ci + 1, brand, f"{brand} {name.title()} {pid:05d}", f"MDL-{pid:06d}", release)
            ids = []
            for s in sizes:
                sid = self.nid("product_sizes")
                self.row("product_sizes", sid, pid, s)
                self.size_ref.append(r1000(market * r.uniform(0.9, 1.15)))
                self.size_cat.append(ci)
                ids.append(sid)
            self.prod_sizes.append(ids)

    def pick_size(self):
        r = self.rng
        p = self.prod_sizes[int(len(self.prod_sizes) * r.random() ** 2.5)]
        return p[r.randrange(len(p))]

    def restricted(self, restr, u, t):
        x = restr[u]
        return x is not None and x[0] <= t < x[1]

    def pick_buyer(self, t, not_user=0):
        r = self.rng
        for _ in range(20):
            j = self.joined(t)
            u = 1 + int(j * r.random() ** 2)
            if u != not_user and not self.restricted(self.purch_restr, u, t):
                return u
        return None

    def pick_seller(self, t, not_user=0):
        r = self.rng
        for _ in range(20):
            j = self.joined(t) // 5
            if j < 1:
                return None
            u = 5 * (1 + int(j * r.random() ** 1.5))
            if u > self.joined(t):
                continue
            if u != not_user and not self.restricted(self.sale_restr, u, t):
                return u
        return None

    # ---- 입찰 ----
    def new_bid(self, user, size, side, price, created, period_days):
        bid = {"id": self.nid("bids"), "user": user, "size": size, "side": side, "price": price,
               "created": created, "expires": created + period_days * DAY, "status": "ACTIVE"}
        self.bid_event(bid, None, "ACTIVE", None, created)
        return bid

    def bid_event(self, bid, frm, to, trade_id, t):
        self.row("bid_events", self.nid("bid_events"), bid["id"], frm, to, trade_id, ts(t))
        bid["status"] = to

    def write_bid(self, bid):
        self.row("bids", bid["id"], bid["user"], bid["size"], bid["side"], bid["price"], bid["status"],
                 ts(bid["expires"]), ts(bid["created"]))

    def close_unmatched(self, bid, t_from):
        """매칭되지 않을 입찰의 끝: 취소·만료·현재 ACTIVE 중 하나로 정하고 쓴다."""
        r = self.rng
        tc = t_from + r.randrange(HOUR, 10 * DAY)
        if r.random() < 0.4 and tc < bid["expires"] and tc < NOW:
            self.bid_event(bid, "ACTIVE", "CANCELLED", None, tc)
        elif bid["expires"] <= NOW:
            self.bid_event(bid, "ACTIVE", "EXPIRED", None, bid["expires"])
        self.write_bid(bid)

    def unmatched_bids(self, day_start):
        r = self.rng
        for _ in range(self.poisson(2300 * self.scale)):
            t = day_start + r.randrange(DAY)
            size = self.pick_size()
            ref = self.size_ref[size]
            if r.random() < 0.5:
                u = self.pick_buyer(t)
                side, price = "BUY", r1000(ref * r.uniform(0.6, 0.85))
            else:
                u = self.pick_seller(t)
                side, price = "SELL", r1000(ref * r.uniform(1.12, 1.5))
            if u is None:
                continue
            bid = self.new_bid(u, size, side, price, t, r.choice((30, 60, 90)))
            self.close_unmatched(bid, t)

    def poisson(self, lam):
        # 큰 λ 는 정규 근사
        return max(0, int(round(self.rng.gauss(lam, lam ** 0.5))))

    # ---- 체결·결제 세션 ----
    def start_sessions(self, day_start, day_idx):
        r = self.rng
        growth = 0.6 + 0.8 * day_idx / 730
        weekday = time.gmtime(day_start + KST).tm_wday
        wk = 1.15 if weekday >= 5 else 0.94
        for _ in range(self.poisson(4110 * self.scale * growth * wk)):
            t0 = day_start + r.randrange(DAY - HOUR)
            buyer = self.pick_buyer(t0 - 21 * DAY)
            if buyer is None or self.restricted(self.purch_restr, buyer, t0):
                continue
            n = r.choices((1, 2, 3, 4), (60, 25, 10, 5))[0]
            trades, t = [], t0
            for _ in range(n):
                tr = self.new_trade(buyer, t, None)
                if tr:
                    trades.append(tr)
                t += r.randrange(30, 5 * MIN)
            if trades:
                self.begin_payment(buyer, trades)

    def new_trade(self, buyer, t, restored_sell):
        """체결 1건. restored_sell 이 있으면 그 판매 입찰(복구된)이 maker."""
        r = self.rng
        if restored_sell:
            sell = restored_sell
            size, price, seller = sell["size"], sell["price"], sell["user"]
            if seller == buyer:
                return None
            buy = self.new_bid(buyer, size, "BUY", price, t, 30)
        else:
            size = self.pick_size()
            price = r1000(self.size_ref[size] * r.uniform(0.9, 1.1))
            maker_t = t - r.randrange(MIN, 20 * DAY)
            seller = self.pick_seller(maker_t - DAY, not_user=buyer)
            if seller is None or self.restricted(self.sale_restr, seller, maker_t) \
                    or self.restricted(self.sale_restr, seller, t):
                return None
            if r.random() < 0.75:   # 판매 입찰이 먼저 → 즉시 구매
                sell = self.new_bid(seller, size, "SELL", price, maker_t, r.choice((30, 60, 90)))
                buy = self.new_bid(buyer, size, "BUY", price, t, 30)
            else:                   # 구매 입찰이 먼저 → 즉시 판매
                if self.restricted(self.purch_restr, buyer, maker_t):
                    return None
                buy = self.new_bid(buyer, size, "BUY", price, maker_t, r.choice((30, 60, 90)))
                sell = self.new_bid(seller, size, "SELL", price, t, 30)
        tr = {"id": self.nid("trades"), "buy": buy, "sell": sell, "buyer": buyer, "seller": seller,
              "size": size, "price": price, "status": "AWAITING_PAYMENT", "matched": t, "due": t + 30 * MIN}
        self.bid_event(buy, "ACTIVE", "MATCHED", tr["id"], t)
        self.bid_event(sell, "ACTIVE", "MATCHED", tr["id"], t)
        return tr

    def write_trade(self, tr):
        self.row("trades", tr["id"], tr["buy"]["id"], tr["sell"]["id"], tr["buyer"], tr["seller"], tr["size"],
                 tr["price"], tr["status"], ts(tr["matched"]), ts(tr["due"]))

    def begin_payment(self, buyer, trades):
        r = self.rng
        first, last = trades[0]["matched"], trades[-1]["matched"]
        sess = {"buyer": buyer, "trades": trades, "due": trades[0]["due"]}
        for tr in trades:
            self.push(tr["due"], P_EVENT, "trade_due", tr)
        if r.random() < 0.02:
            return                                     # 결제하지 않음 → 기한 초과
        tp = min(last + r.randrange(MIN, 20 * MIN), first + 28 * MIN)
        self.push(max(tp, last + 1), P_EVENT, "pay_attempt", sess)

    def ev_pay_attempt(self, t, sess):
        r = self.rng
        trades = [tr for tr in sess["trades"] if tr["status"] == "AWAITING_PAYMENT"]
        if not trades or t >= sess["due"]:
            return
        buyer = sess["buyer"]
        # 금액
        items = []
        for tr in trades:
            items.append({"trade": tr, "price": tr["price"], "fee": buyer_fee(tr["price"])})
        item_total = sum(i["price"] for i in items)
        fee_total = sum(i["fee"] for i in items)
        ship = 0 if item_total >= 100_000 else 3000
        total = item_total + fee_total + ship
        self.expire_user(buyer, t)
        avail = self.available(buyer)
        use = 0
        if avail >= 1000 and r.random() < 0.5:
            use = int(min(avail, total // 2) * r.uniform(0.3, 1.0)) // 10 * 10
            if use < 1000:
                use = 0
        card = total - use
        o = {"id": self.nid("orders"), "buyer": buyer, "item_total": item_total, "fee_total": fee_total,
             "ship": ship, "point": use, "card": card, "key": self.key(), "created": t, "paid": None,
             "status": "PAYING", "items": items, "open": len(items), "refunded": 0,
             "ship_card": 0, "ship_point": 0, "ship_refunded": ship == 0, "allocs": []}
        # 항목 몫(§9.2)
        bases = [i["price"] + i["fee"] for i in items]
        bases[0] += ship
        shares = [card * b // total for b in bases]
        left = card - sum(shares)                      # 항목 수보다 작다: 앞 항목부터 1원씩
        while left:
            for k in range(len(shares)):
                if left and shares[k] < bases[k]:
                    shares[k] += 1
                    left -= 1
        for k, it in enumerate(items):
            it.update(id=self.nid("order_items"), order=o, b=bases[k], card=shares[k], point=bases[k] - shares[k],
                      ship=ship if k == 0 else 0, status="AWAITING_PAYMENT", paid=None, shipped=None,
                      inspected=None, delivered=None, confirmed=None, cancelled=None, earn=None, settled=False,
                      seller=it["trade"]["seller"], cat=self.size_cat[it["trade"]["size"]])
        if ship:
            it0 = items[0]
            sp = min(ship - ship * it0["card"] // it0["b"], it0["point"])
            o["ship_point"], o["ship_card"] = sp, ship - sp
        # 포인트 예약
        use_entry = None
        if use:
            use_entry = self.nid("point_ledger")
            o["use_entry"] = use_entry
            self.take_points(buyer, use, use_entry, t, o["allocs"])
        # PG
        outcome = r.random()
        resp = t + r.randrange(1, 8)
        if outcome < 0.03:
            self.pg(o, "APPROVE", card, "DECLINED", t, resp)
            self.pay_failed(o, resp, use_entry)
            if r.random() < 0.7 and resp + 3 * MIN < sess["due"]:
                self.push(resp + r.randrange(MIN, 3 * MIN), P_EVENT, "pay_attempt", sess)
        elif outcome < 0.04:
            self.pg(o, "APPROVE", card, "UNKNOWN", t, None)
            inq = t + 60 + r.randrange(30)
            if r.random() < 0.8:
                self.pg(o, "INQUIRY", card, "APPROVED", inq, inq + 1)
                self.pay_ok(o, inq + 1, use_entry)
            else:
                self.pg(o, "INQUIRY", card, "DECLINED", inq, inq + 1)
                self.pay_failed(o, inq + 1, use_entry)
        else:
            self.pg(o, "APPROVE", card, "APPROVED", t, resp)
            self.pay_ok(o, resp, use_entry)

    def pg(self, o, kind, amount, status, t, resp):
        pid = ("PG" + "%016x" % self.rng.getrandbits(64)) if status == "APPROVED" else None
        k = o["key"] if kind != "CANCEL" else self.key()
        self.row("pg_transactions", self.nid("pg_transactions"), o["id"], kind, amount, status, pid, k, ts(t), ts(resp))

    def pay_failed(self, o, t, use_entry):
        o["status"] = "FAILED"
        if use_entry:
            self.release_points(o, t)
        for it in o["items"]:
            it["status"] = "PAYMENT_FAILED"
            self.write_item(it)
        self.write_order(o)

    def pay_ok(self, o, t, use_entry):
        o["status"], o["paid"] = "PAID", t
        buyer = o["buyer"]
        if use_entry:
            self.row("point_ledger", use_entry, buyer, "USE", "CONFIRMED", o["point"], None, None, None, o["id"],
                     ts(o["created"]))
            for lot, x, _ in o["allocs"]:
                self.row("point_allocations", self.nid("point_allocations"), use_entry, lot["id"], x)
        rate = EARN_PERMILLE[self.grade[buyer]]
        for it in o["items"]:
            tr = it["trade"]
            tr["status"] = "PAID"
            self.write_trade(tr)
            for b in (tr["buy"], tr["sell"]):
                self.write_bid(b)
            it["status"], it["paid"] = "PAID", t
            earn = (it["card"] * it["price"] // it["b"]) * rate // 1000
            if earn > 0:
                it["earn"] = {"id": self.nid("point_ledger"), "user": buyer, "amount": earn, "remaining": earn,
                              "expires": None, "status": "PENDING", "item": it["id"], "created": t}
                self.pending_earn[buyer] = self.pending_earn.get(buyer, 0) + earn
                self.bal_touched[buyer] = t
            self.plan_item(it, t)

    # ---- 거래 기한 ----
    def ev_trade_due(self, t, tr):
        if tr["status"] != "AWAITING_PAYMENT":
            return
        r = self.rng
        tr["status"] = "PAYMENT_EXPIRED"
        self.write_trade(tr)
        buyer = tr["buyer"]
        self.row("buyer_warnings", self.nid("buyer_warnings"), buyer, tr["id"], ts(t))
        w = [x for x in self.warn_times.get(buyer, []) if x > t - 30 * DAY] + [t]
        if len(w) >= 3:
            self.purch_restr[buyer] = (t, t + 7 * DAY)
            w = []
        self.warn_times[buyer] = w
        buy, sell = tr["buy"], tr["sell"]
        self.bid_event(buy, "MATCHED", "CANCELLED", tr["id"], t)
        self.write_bid(buy)
        if sell["expires"] <= t:
            self.bid_event(sell, "MATCHED", "EXPIRED", tr["id"], t)
            self.write_bid(sell)
            return
        self.bid_event(sell, "MATCHED", "ACTIVE", tr["id"], t)
        if r.random() < 0.5:
            tm = t + r.randrange(10 * MIN, max(10 * MIN + 1, min(5 * DAY, sell["expires"] - t)))
            if tm < sell["expires"] and tm < NOW:
                self.push(tm, P_EVENT, "rematch", sell)
                return
        self.close_unmatched(sell, t)

    def ev_rematch(self, t, sell):
        buyer = self.pick_buyer(t, not_user=sell["user"])
        tr = self.new_trade(buyer, t, sell) if buyer else None
        if tr is None:
            self.close_unmatched(sell, t)
            return
        self.begin_payment(buyer, [tr])

    # ---- 주문 항목 진행 ----
    def plan_item(self, it, t):
        r = self.rng
        x = r.random()
        if x < 0.03:
            self.push(t + r.randrange(10 * MIN, 2 * DAY), P_EVENT, "buyer_cancel", it)
        elif x < 0.04:
            self.push(t + 3 * DAY, P_EVENT, "ship_deadline", it)
        else:
            self.push(t + r.randrange(2 * HOUR, 3 * DAY - HOUR), P_EVENT, "ship", it)

    def ev_buyer_cancel(self, t, it):
        if it["status"] != "PAID":
            return
        it["status"], it["cancelled"] = "CANCELLED_BY_BUYER", t
        self.refund(it, t, "BUYER", "구매자 취소", full=False)

    def ev_ship_deadline(self, t, it):
        if it["status"] != "PAID":
            return
        self.seller_fault(it, t, "발송 기한 초과")

    def ev_ship(self, t, it):
        if it["status"] != "PAID":
            return
        it["status"], it["shipped"] = "SHIPPED_TO_CENTER", t
        self.push(t + self.rng.randrange(DAY, 2 * DAY), P_EVENT, "arrive", it)

    def ev_arrive(self, t, it):
        it["status"] = "INSPECTING"
        self.push(t + self.rng.randrange(2 * HOUR, DAY), P_EVENT, "inspect", it)

    def ev_inspect(self, t, it):
        r = self.rng
        it["inspected"] = t
        if r.random() < 0.02:
            self.row("inspections", self.nid("inspections"), it["id"], "FAIL", r.choice(INSPECT_FAIL_REASONS), ts(t))
            self.seller_fault(it, t, "검수 불합격")
            return
        self.row("inspections", self.nid("inspections"), it["id"], "PASS", None, ts(t))
        it["status"] = "DELIVERING"
        self.push(t + r.randrange(DAY, 3 * DAY), P_EVENT, "deliver", it)

    def ev_deliver(self, t, it):
        r = self.rng
        it["status"], it["delivered"] = "DELIVERED", t
        if r.random() < 0.4:
            self.push(t + r.randrange(HOUR, 7 * DAY), P_EVENT, "confirm", it)
        else:
            self.push(t + 7 * DAY, P_EVENT, "confirm", it)

    def ev_confirm(self, t, it):
        r = self.rng
        it["status"], it["confirmed"] = "CONFIRMED", t
        buyer = it["order"]["buyer"]
        e = it["earn"]
        if e:
            e["status"], e["expires"] = "AVAILABLE", t + 365 * DAY
            self.pending_earn[buyer] -= e["amount"]
            self.expire_user(buyer, t)
            self.lots.setdefault(buyer, deque()).append(e)
            self.bal_touched[buyer] = t
        mi = self.month_index(t)
        mc = self.month_conf.setdefault(buyer, {})
        mc[mi] = mc.get(mi, 0) + it["price"]
        self.settle_queue(it["seller"])["items"].append(it)
        if r.random() < 0.01:
            self.push(t + r.randrange(HOUR, 14 * DAY), P_EVENT, "dispute_open", it)
        else:
            self.push(t + 14 * DAY, P_EVENT, "finalize", it)

    def ev_dispute_open(self, t, it):
        r = self.rng
        it["status"] = "DISPUTED"
        it["dispute"] = {"id": self.nid("disputes"), "opened": t}
        self.push(t + r.randrange(2 * DAY, 10 * DAY), P_EVENT, "dispute_resolve", it)

    def ev_dispute_resolve(self, t, it):
        d = it["dispute"]
        if self.rng.random() < 0.5:
            self.row("disputes", d["id"], it["id"], "ACCEPTED", ts(d["opened"]), ts(t))
            it["status"], it["cancelled"] = "REFUNDED_AFTER_CONFIRM", t
            buyer = it["order"]["buyer"]
            mc = self.month_conf.get(buyer, {})
            mi = self.month_index(it["confirmed"])
            if mi in mc:
                mc[mi] -= it["price"]
            self.revoke(it, t)
            q = self.settle_queue(it["seller"])
            if it["settled"]:
                q["clawbacks"].append(it)
            self.refund(it, t, "DISPUTE", "분쟁 인정", full=True)
        else:
            self.row("disputes", d["id"], it["id"], "REJECTED", ts(d["opened"]), ts(t))
            it["status"] = "CONFIRMED"
            self.close_item(it)

    def ev_finalize(self, t, it):
        if it["status"] == "CONFIRMED":
            self.close_item(it)

    def seller_fault(self, it, t, reason):
        it["status"], it["cancelled"] = "CANCELLED_SELLER_FAULT", t
        seller = it["seller"]
        self.settle_queue(seller)["penalties"].append(
            {"id": self.nid("seller_penalties"), "seller": seller, "item": it["id"], "amount": it["price"] // 10,
             "created": t})
        f = [x for x in self.fault_times.get(seller, []) if x > t - 30 * DAY] + [t]
        if len(f) >= 3:
            self.sale_restr[seller] = (t, t + 30 * DAY)
            f = []
        self.fault_times[seller] = f
        self.refund(it, t, "SELLER", reason, full=True)

    # ---- 환불(§9.2) ----
    def refund(self, it, t, fault, reason, full):
        o = it["order"]
        first = it is o["items"][0]
        card, point = it["card"], it["point"]
        ship_amt = 0
        if first and o["ship"]:
            if full:
                ship_amt = o["ship"]
                o["ship_refunded"] = True
            else:
                card -= o["ship_card"]
                point -= o["ship_point"]
        o["refunded"] += 1
        if o["refunded"] == len(o["items"]) and not o["ship_refunded"]:
            card += o["ship_card"]
            point += o["ship_point"]
            ship_amt = o["ship"]
            o["ship_refunded"] = True
        rid = self.nid("refunds")
        self.row("refunds", rid, it["id"], card, point, it["fee"], ship_amt, reason, fault, ts(t))
        if card:
            self.pg(o, "CANCEL", card, "APPROVED", t, t + 1)
        if point:
            self.restore_points(o, point, it, t)
        e = it["earn"]
        if e and e["status"] == "PENDING":
            e["status"] = "CANCELLED"
            self.pending_earn[o["buyer"]] -= e["amount"]
            self.bal_touched[o["buyer"]] = t
            self.write_earn(e)
        o["status"] = "CANCELLED" if o["refunded"] == len(o["items"]) else "PARTIALLY_CANCELLED"
        self.close_item(it)

    # ---- 포인트 ----
    def month_index(self, t):
        g = time.gmtime(t + KST)
        return g.tm_year * 12 + g.tm_mon - 1

    def available(self, u):
        return sum(l["remaining"] for l in self.lots.get(u, ()))

    def write_earn(self, e):
        self.row("point_ledger", e["id"], e["user"], "EARN", e["status"], e["amount"], e["remaining"],
                 ts(e["expires"]), e["item"], None, ts(e["created"]))

    def expire_user(self, u, t):
        dq = self.lots.get(u)
        while dq and dq[0]["expires"] <= t:
            lot = dq.popleft()
            if lot["remaining"]:
                eid = self.nid("point_ledger")
                self.row("point_ledger", eid, u, "EXPIRE", "DONE", lot["remaining"], None, None, None, None,
                         ts(lot["expires"]))
                self.row("point_allocations", self.nid("point_allocations"), eid, lot["id"], lot["remaining"])
                lot["remaining"] = 0
                self.bal_touched[u] = max(self.bal_touched.get(u, 0), lot["expires"])
            lot["flushed"] = True
            self.write_earn(lot)

    def take_points(self, u, amount, entry_id, t, allocs):
        need = amount
        for lot in self.lots[u]:
            if not need:
                break
            x = min(lot["remaining"], need)
            if x:
                lot["remaining"] -= x
                need -= x
                allocs.append([lot, x, 0])
        assert need == 0, "포인트 부족"
        self.bal_touched[u] = t

    def release_points(self, o, t):
        u = o["buyer"]
        self.row("point_ledger", o["use_entry"], u, "USE", "RELEASED", o["point"], None, None, None, o["id"],
                 ts(o["created"]))
        # 예약 해제: 같은 묶음으로 되돌린다. 배분 행은 승인된 USE 에만 쓴다.
        for lot, x, _ in o["allocs"]:
            lot["remaining"] += x
        o["allocs"] = []
        self.bal_touched[u] = t

    def restore_points(self, o, amount, it, t):
        u = o["buyer"]
        self.expire_user(u, t)
        rid = self.nid("point_ledger")
        self.row("point_ledger", rid, u, "RESTORE", "DONE", amount, None, None, it["id"], o["id"], ts(t))
        need = amount
        expired = []
        for a in sorted(o["allocs"], key=lambda a: (-a[0]["expires"], -a[0]["id"])):   # 만료 늦은 묶음부터
            if not need:
                break
            lot, x, back = a
            y = min(x - back, need)
            if y <= 0:
                continue
            a[2] += y
            need -= y
            self.row("point_allocations", self.nid("point_allocations"), rid, lot["id"], y)
            if lot.get("flushed"):
                expired.append((lot, y))
            else:
                lot["remaining"] += y
        assert need == 0, "복구 대상 부족"
        for lot, y in expired:
            eid = self.nid("point_ledger")
            self.row("point_ledger", eid, u, "EXPIRE", "DONE", y, None, None, None, None, ts(t))
            self.row("point_allocations", self.nid("point_allocations"), eid, lot["id"], y)
        self.bal_touched[u] = t

    def revoke(self, it, t):
        e = it["earn"]
        u = it["order"]["buyer"]
        self.expire_user(u, t)
        if not e or e.get("flushed") or e["status"] != "AVAILABLE" or not e["remaining"]:
            return
        x = e["remaining"]
        e["remaining"] = 0
        vid = self.nid("point_ledger")
        self.row("point_ledger", vid, u, "REVOKE", "DONE", x, None, None, it["id"], None, ts(t))
        self.row("point_allocations", self.nid("point_allocations"), vid, e["id"], x)
        self.bal_touched[u] = t

    # ---- 등급(매월 1일) ----
    def ev_grade(self, t, _):
        mi = self.month_index(t)
        eff = date_str(t)
        for u in list(self.month_conf):
            mc = self.month_conf[u]
            for k in [k for k in mc if k < mi - 6]:
                del mc[k]
            basis = sum(v for k, v in mc.items() if mi - 6 <= k < mi)
            g = "BRONZE"
            for name, lo in GRADE_MIN:
                if basis >= lo:
                    g = name
                    break
            if g != self.grade[u]:
                self.grade[u] = g
                self.row("user_grade_history", self.nid("user_grade_history"), u, g, eff, basis)
            if not mc and g == "BRONZE":
                del self.month_conf[u]

    # ---- 정산(매주 월요일, §9.3) ----
    def settle_queue(self, seller):
        q = self.sq.get(seller)
        if q is None:
            q = self.sq[seller] = {"items": [], "penalties": [], "clawbacks": [], "carry": 0}
        return q

    def ev_settle(self, t, _):
        start, end = date_str(t - 7 * DAY), date_str(t - DAY)
        for seller in sorted(self.sq):
            q = self.sq[seller]
            if not (q["items"] or q["penalties"] or q["clawbacks"] or q["carry"]):
                continue
            gross = fee_total = 0
            keep = []
            rows = []
            for it in q["items"]:
                if it["status"] == "CONFIRMED":
                    fee = it["price"] * CATEGORIES[it["cat"]][1] // 1000
                    rows.append((it["id"], it["price"], fee, "INCLUDED"))
                    gross += it["price"]
                    fee_total += fee
                    it["settled"] = True
                elif it["status"] == "DISPUTED":
                    rows.append((it["id"], 0, 0, "HELD"))
                    keep.append(it)
            for it in q["clawbacks"]:
                fee = it["price"] * CATEGORIES[it["cat"]][1] // 1000
                rows.append((it["id"], -it["price"], -fee, "CLAWBACK"))
                gross -= it["price"]
                fee_total -= fee
            pen = sum(p["amount"] for p in q["penalties"])
            if not rows and not pen and not q["carry"]:
                q.update(items=keep, clawbacks=[])        # 대기 항목이 모두 분쟁 인정으로 빠진 주
                continue
            sid = self.nid("settlements")
            net = gross - fee_total - pen + q["carry"]
            payout, carry_out = max(net, 0), min(net, 0)
            status = "PAID" if payout > 0 else "CARRIED"
            self.row("settlements", sid, seller, start, end, gross, fee_total, pen, q["carry"], net, payout, carry_out,
                     status, ts(t + 2 * DAY) if payout > 0 else None)
            for iid, amt, fee, st in rows:
                self.row("settlement_items", self.nid("settlement_items"), sid, iid, amt, fee, st)
            for p in q["penalties"]:
                self.row("seller_penalties", p["id"], seller, p["item"], p["amount"], sid, ts(p["created"]))
            q.update(items=keep, penalties=[], clawbacks=[], carry=carry_out)

    # ---- 쓰기 ----
    def close_item(self, it):
        if it.get("closed"):
            return
        it["closed"] = True
        self.write_item(it)
        o = it["order"]
        o["open"] -= 1
        if o["open"] == 0:
            self.write_order(o)

    def write_item(self, it):
        self.row("order_items", it["id"], it["order"]["id"], it["trade"]["id"], it["price"], it["fee"], it["card"],
                 it["point"], it["ship"], it["status"], ts(it["paid"]), ts(it["shipped"]), ts(it["inspected"]),
                 ts(it["delivered"]), ts(it["confirmed"]), ts(it["cancelled"]))
        if it["status"] == "DISPUTED":
            d = it["dispute"]
            self.row("disputes", d["id"], it["id"], "OPEN", ts(d["opened"]), None)

    def write_order(self, o):
        self.row("orders", o["id"], o["buyer"], o["status"], o["item_total"], o["fee_total"], o["ship"], o["point"],
                 o["card"], o["key"], ts(o["created"]), ts(o["paid"]))

    # ---- 실행 ----
    def run(self):
        self.sq = {}
        self.make_users()
        self.make_products()
        # 배치 일정: 매월 1일 등급, 매주 월요일 정산
        d = START
        while d < NOW:
            g = time.gmtime(d + KST)
            if g.tm_mday == 1 and d > START:
                self.push(d, P_BATCH, "grade", None)
            if g.tm_wday == 0:
                self.push(d, P_BATCH, "settle", None)
            d += DAY
        handlers = {k[3:]: getattr(self, k) for k in dir(self) if k.startswith("ev_")}
        day_idx, day = 0, START
        while day < NOW:
            self.unmatched_bids(day)
            self.start_sessions(day, day_idx)
            end = day + DAY
            while self.heap and self.heap[0][0] < min(end, NOW):
                t, _, _, kind, obj = heapq.heappop(self.heap)
                handlers[kind](t, obj)
            day, day_idx = end, day_idx + 1
        self.flush_at_now()
        for f in self.files.values():
            f.close()

    def flush_at_now(self):
        """현재 시각에 진행 중인 것들을 현재 상태로 쓴다."""
        pending_sessions = set()
        items, trades = {}, {}
        for t, _, _, kind, obj in self.heap:
            if kind == "trade_due":
                trades[obj["id"]] = obj
            elif kind == "pay_attempt":
                pending_sessions.add(id(obj))
                for tr in obj["trades"]:
                    trades[tr["id"]] = tr
            elif kind == "rematch":
                self.write_bid(obj)          # 복구되어 ACTIVE 인 판매 입찰
            elif kind in ("grade", "settle"):
                continue
            else:
                items[obj["id"]] = obj
        for tr in trades.values():
            if tr["status"] == "AWAITING_PAYMENT":
                self.write_trade(tr)
                self.write_bid(tr["buy"])
                self.write_bid(tr["sell"])
        for it in items.values():
            self.close_item(it)
        for u in list(self.lots):
            self.expire_user(u, NOW)
            for lot in self.lots[u]:
                self.write_earn(lot)
        # PENDING 적립은 아직 쓰지 않은 항목의 것 — 항목 객체를 거쳐 쓴다
        for it in items.values():
            e = it["earn"]
            if e and e["status"] == "PENDING":
                self.write_earn(e)
        for seller in sorted(self.sq):
            for p in self.sq[seller]["penalties"]:   # 아직 정산되지 않은 패널티
                self.row("seller_penalties", p["id"], seller, p["item"], p["amount"], None, ts(p["created"]))
        for u in sorted(set(self.bal_touched)):
            self.row("point_balances", u, self.available(u), self.pending_earn.get(u, 0), ts(self.bal_touched[u]))
        for u in range(1, self.n_users + 1):
            pr, sr = self.purch_restr[u], self.sale_restr[u]
            self.row("users", u, f"user{u:06d}@example.test", f"사용자{u:06d}", self.grade[u],
                     ts(pr[1]) if pr else None, ts(sr[1]) if sr else None, ts(self.user_created[u]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--users", type=int, required=True)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    t0 = time.time()
    g = Gen(a.users, a.seed, a.out)
    g.run()
    sums = {}
    for name in TABLES:
        h = hashlib.sha256()
        with open(Path(a.out) / f"{name}.csv", "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        sums[name] = h.hexdigest()
    manifest = {"seed": a.seed, "users": a.users, "start": ts(START), "now": ts(NOW),
                "rows": g.counts, "sha256": sums, "seconds": round(time.time() - t0, 1)}
    (Path(a.out) / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1) + "\n")
    print(json.dumps({"rows": g.counts, "seconds": manifest["seconds"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
