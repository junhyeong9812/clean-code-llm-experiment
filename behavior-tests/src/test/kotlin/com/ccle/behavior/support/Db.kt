package com.ccle.behavior.support

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

// ---- 조회 행 (ADR-0002 §6, docker/schema/tables.sql) ----
data class UserRow(val id: Long, val grade: String, val purchaseRestrictedUntil: Instant?, val saleRestrictedUntil: Instant?)
data class BidRow(
    val id: Long, val userId: Long, val productSizeId: Long, val side: String, val price: Long,
    val status: String, val expiresAt: Instant, val createdAt: Instant,
)
data class BidEventRow(val id: Long, val bidId: Long, val fromStatus: String?, val toStatus: String, val tradeId: Long?, val createdAt: Instant)
data class TradeRow(
    val id: Long, val buyBidId: Long, val sellBidId: Long, val buyerId: Long, val sellerId: Long, val productSizeId: Long,
    val price: Long, val status: String, val matchedAt: Instant, val paymentDueAt: Instant,
)
data class WarningRow(val id: Long, val userId: Long, val tradeId: Long, val createdAt: Instant)
data class OrderRow(
    val id: Long, val buyerId: Long, val status: String, val itemTotal: Long, val buyerFeeTotal: Long, val shippingFee: Long,
    val pointUsed: Long, val cardAmount: Long, val idempotencyKey: String, val createdAt: Instant, val paidAt: Instant?,
)
data class OrderItemRow(
    val id: Long, val orderId: Long, val tradeId: Long, val price: Long, val buyerFee: Long, val cardShare: Long,
    val pointShare: Long, val shippingShare: Long, val status: String, val paidAt: Instant?,
)
data class PgTxRow(
    val id: Long, val orderId: Long, val kind: String, val amount: Long, val status: String, val pgTxId: String?,
    val idempotencyKey: String, val requestedAt: Instant, val respondedAt: Instant?,
)
data class LedgerRow(
    val id: Long, val userId: Long, val kind: String, val status: String, val amount: Long, val remaining: Long?,
    val expiresAt: Instant?, val orderItemId: Long?, val orderId: Long?, val createdAt: Instant,
)
data class AllocationRow(val id: Long, val entryId: Long, val earnEntryId: Long, val amount: Long)
data class BalanceRow(val userId: Long, val available: Long, val pending: Long, val updatedAt: Instant)

/** 테스트가 만든 포인트 묶음(EARN AVAILABLE). */
data class Bundle(val earnId: Long, val amount: Long, val expiresAt: Instant, val confirmedAt: Instant)

/**
 * JDBC 도우미. 테스트 자기 행을 만들고(id는 시퀀스), 판정용 행을 조회한다.
 * 연결 1개를 자동 커밋으로 쓴다(앱이 즉시 볼 수 있게). 테스트는 직렬이므로 공유해도 된다.
 */
object Db {
    /** 시드의 현재 시각(ADR-0003 §7 CLOCK_START 기본값). */
    val SEED_NOW: OffsetDateTime = OffsetDateTime.parse("2026-10-01T00:00:00+09:00")

    private val conn: Connection by lazy {
        DriverManager.getConnection(Env.dbUrl, Env.dbUser, Env.dbPassword).apply { autoCommit = true }
    }

    // ---- 저수준 ----
    private fun bind(ps: PreparedStatement, params: Array<out Any?>) {
        params.forEachIndexed { i, p ->
            when (p) {
                is Instant -> ps.setObject(i + 1, p.atOffset(ZoneOffset.UTC))
                else -> ps.setObject(i + 1, p)
            }
        }
    }

    fun <T> query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
        conn.prepareStatement(sql).use { ps ->
            bind(ps, params)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }

    fun update(sql: String, vararg params: Any?): Int =
        conn.prepareStatement(sql).use { ps -> bind(ps, params); ps.executeUpdate() }

    fun insertId(sql: String, vararg params: Any?): Long =
        query("$sql RETURNING id", *params) { it.getLong(1) }.single()

    fun long(sql: String, vararg params: Any?): Long = query(sql, *params) { it.getLong(1) }.single()

    fun <T> tx(block: () -> T): T {
        conn.autoCommit = false
        try {
            val r = block()
            conn.commit()
            return r
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    private fun ResultSet.instant(col: String): Instant? = getObject(col, OffsetDateTime::class.java)?.toInstant()
    private fun ResultSet.longOrNull(col: String): Long? = getLong(col).takeUnless { wasNull() }

    // ---- 만들기 ----
    private fun uniq() = UUID.randomUUID().toString().replace("-", "").take(16)

    /** 사용자. 등급·제한 시각을 정할 수 있다. 이메일은 UUID로 겹치지 않게 한다. */
    fun createUser(
        grade: String = "BRONZE",
        purchaseRestrictedUntil: Instant? = null,
        saleRestrictedUntil: Instant? = null,
        createdAt: Instant = SEED_NOW.toInstant().minus(Duration.ofDays(400)),
    ): Long {
        val u = uniq()
        return insertId(
            "INSERT INTO users(email, name, grade, purchase_restricted_until, sale_restricted_until, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            "bt-$u@behavior.test", "bt-$u", grade, purchaseRestrictedUntil, saleRestrictedUntil, createdAt,
        )
    }

    fun setRestrictions(userId: Long, purchaseRestrictedUntil: Instant?, saleRestrictedUntil: Instant?) {
        update(
            "UPDATE users SET purchase_restricted_until = ?, sale_restricted_until = ? WHERE id = ?",
            purchaseRestrictedUntil, saleRestrictedUntil, userId,
        )
    }

    /** 상품 1개 + 사이즈 1개(기존 카테고리 사용). 반환: product_size id. */
    fun createProductSize(categoryName: String? = null): Long {
        val categoryId = if (categoryName == null) long("SELECT min(id) FROM categories")
        else long("SELECT id FROM categories WHERE name = ?", categoryName)
        val u = uniq()
        val productId = insertId(
            "INSERT INTO products(category_id, brand, name, model_no, release_price) VALUES (?, ?, ?, ?, ?)",
            categoryId, "bt-brand", "bt-product-$u", "BT-$u", 100_000L,
        )
        return insertId("INSERT INTO product_sizes(product_id, size) VALUES (?, ?)", productId, "270")
    }

    /** 마지막 주간 정산 마감 시각(verify.sql v_global.last_cutoff). 없으면 null. */
    fun lastSettlementCutoff(): Instant? =
        query("SELECT max(((period_end + 1)::timestamp AT TIME ZONE 'Asia/Seoul')) FROM settlements") { it.instant("max") }.single()

    /**
     * 포인트 묶음 1개(EARN AVAILABLE, remaining = amount, expires_at = confirmedAt + 365일)와 point_balances.available 증가.
     *
     * verify.sql을 통과하도록 묶음의 출처(구매 확정된 과거 거래 1건)를 함께 만든다: 판매자 사용자·상품 사이즈·
     * 판매/구매 입찰(MATCHED)·bid_events·체결(PAID)·주문(PAID, 포인트 0)·항목(CONFIRMED)·PG 승인·검수(PASS).
     * 체결가 = amount × 100 → 적립 = floor(카드 몫 중 체결가 부분 × 1%) = amount (verify `earn_amount_rate`).
     * confirmedAt은 마지막 정산 마감 이후여야 한다(verify `settlement_inv8`: 그 전이면 정산 행이 필요하다).
     * 이 거래는 묶음 사용자가 구매자인 체결·주문으로 남으므로, 구매자 단위 목록을 단언하는 테스트는 묶음을 쓰지 않는다.
     */
    fun createPointBundle(userId: Long, amount: Long, confirmedAt: Instant): Bundle {
        require(amount > 0)
        lastSettlementCutoff()?.let { cutoff ->
            require(!confirmedAt.isBefore(cutoff)) { "confirmedAt($confirmedAt)은 마지막 정산 마감($cutoff) 이후여야 한다" }
        }
        val seller = createUser()
        val size = createProductSize()
        val price = amount * 100
        val fee = maxOf(price * 3 / 100, 1000L)
        val ship = if (price >= 100_000) 0L else 3000L
        val total = price + fee + ship
        val tSell = confirmedAt.minus(Duration.ofDays(10))
        val tBuy = confirmedAt.minus(Duration.ofDays(9))
        val paid = tBuy.plus(Duration.ofMinutes(1))
        val shipped = paid.plus(Duration.ofDays(1))
        val inspected = paid.plus(Duration.ofDays(2))
        val delivered = paid.plus(Duration.ofDays(3))
        val expiresAt = confirmedAt.plus(Duration.ofDays(365))
        val key = "fixture-${UUID.randomUUID()}"
        return tx {
            val bidSql = "INSERT INTO bids(user_id, product_size_id, side, price, status, expires_at, created_at) VALUES (?, ?, ?, ?, 'MATCHED', ?, ?)"
            val sellBid = insertId(bidSql, seller, size, "SELL", price, tSell.plus(Duration.ofDays(30)), tSell)
            val buyBid = insertId(bidSql, userId, size, "BUY", price, tBuy.plus(Duration.ofDays(30)), tBuy)
            val evSql = "INSERT INTO bid_events(bid_id, from_status, to_status, trade_id, created_at) VALUES (?, ?, ?, ?, ?)"
            update(evSql, sellBid, null, "ACTIVE", null, tSell)
            update(evSql, buyBid, null, "ACTIVE", null, tBuy)
            val trade = insertId(
                "INSERT INTO trades(buy_bid_id, sell_bid_id, buyer_id, seller_id, product_size_id, price, status, matched_at, payment_due_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, 'PAID', ?, ?)",
                buyBid, sellBid, userId, seller, size, price, tBuy, tBuy.plus(Duration.ofMinutes(30)),
            )
            update(evSql, buyBid, "ACTIVE", "MATCHED", trade, tBuy)
            update(evSql, sellBid, "ACTIVE", "MATCHED", trade, tBuy)
            val order = insertId(
                "INSERT INTO orders(buyer_id, status, item_total, buyer_fee_total, shipping_fee, point_used, card_amount, idempotency_key, created_at, paid_at) " +
                    "VALUES (?, 'PAID', ?, ?, ?, 0, ?, ?, ?, ?)",
                userId, price, fee, ship, total, key, paid, paid,
            )
            val item = insertId(
                "INSERT INTO order_items(order_id, trade_id, price, buyer_fee, card_share, point_share, shipping_share, status, " +
                    "paid_at, shipped_at, inspected_at, delivered_at, confirmed_at) VALUES (?, ?, ?, ?, ?, 0, ?, 'CONFIRMED', ?, ?, ?, ?, ?)",
                order, trade, price, fee, total, ship, paid, shipped, inspected, delivered, confirmedAt,
            )
            update(
                "INSERT INTO pg_transactions(order_id, kind, amount, status, pg_tx_id, idempotency_key, requested_at, responded_at) " +
                    "VALUES (?, 'APPROVE', ?, 'APPROVED', ?, ?, ?, ?)",
                order, total, "fixture-tx-${uniq()}", key, paid, paid,
            )
            update("INSERT INTO inspections(order_item_id, result, inspected_at) VALUES (?, 'PASS', ?)", item, inspected)
            val earn = insertId(
                "INSERT INTO point_ledger(user_id, kind, status, amount, remaining, expires_at, order_item_id, order_id, created_at) " +
                    "VALUES (?, 'EARN', 'AVAILABLE', ?, ?, ?, ?, NULL, ?)",
                userId, amount, amount, expiresAt, item, paid,
            )
            update(
                "INSERT INTO point_balances(user_id, available, pending, updated_at) VALUES (?, ?, 0, ?) " +
                    "ON CONFLICT (user_id) DO UPDATE SET available = point_balances.available + EXCLUDED.available, updated_at = EXCLUDED.updated_at",
                userId, amount, confirmedAt,
            )
            Bundle(earn, amount, expiresAt, confirmedAt)
        }
    }

    // ---- 조회 ----
    /** 없는 id(그 테이블 최대 id보다 충분히 큰 값). */
    fun missingId(table: String): Long {
        require(table.matches(Regex("[a-z_]+")))
        return long("SELECT coalesce(max(id), 0) + 1000000 FROM $table")
    }

    fun user(id: Long): UserRow = query("SELECT * FROM users WHERE id = ?", id) {
        UserRow(it.getLong("id"), it.getString("grade"), it.instant("purchase_restricted_until"), it.instant("sale_restricted_until"))
    }.single()

    private fun bidRow(rs: ResultSet) = BidRow(
        rs.getLong("id"), rs.getLong("user_id"), rs.getLong("product_size_id"), rs.getString("side"), rs.getLong("price"),
        rs.getString("status"), rs.instant("expires_at")!!, rs.instant("created_at")!!,
    )

    fun bid(id: Long): BidRow = query("SELECT * FROM bids WHERE id = ?", id, map = ::bidRow).single()
    fun bidsOfUser(userId: Long): List<BidRow> = query("SELECT * FROM bids WHERE user_id = ? ORDER BY id", userId, map = ::bidRow)
    fun bidsOfSize(sizeId: Long): List<BidRow> = query("SELECT * FROM bids WHERE product_size_id = ? ORDER BY id", sizeId, map = ::bidRow)

    fun bidEvents(bidId: Long): List<BidEventRow> = query("SELECT * FROM bid_events WHERE bid_id = ? ORDER BY id", bidId) {
        BidEventRow(it.getLong("id"), it.getLong("bid_id"), it.getString("from_status"), it.getString("to_status"), it.longOrNull("trade_id"), it.instant("created_at")!!)
    }

    private fun tradeRow(rs: ResultSet) = TradeRow(
        rs.getLong("id"), rs.getLong("buy_bid_id"), rs.getLong("sell_bid_id"), rs.getLong("buyer_id"), rs.getLong("seller_id"),
        rs.getLong("product_size_id"), rs.getLong("price"), rs.getString("status"), rs.instant("matched_at")!!, rs.instant("payment_due_at")!!,
    )

    fun trade(id: Long): TradeRow = query("SELECT * FROM trades WHERE id = ?", id, map = ::tradeRow).single()
    fun tradesOfSize(sizeId: Long): List<TradeRow> = query("SELECT * FROM trades WHERE product_size_id = ? ORDER BY id", sizeId, map = ::tradeRow)
    fun liveTradesOfBid(bidId: Long): List<TradeRow> =
        query("SELECT * FROM trades WHERE (buy_bid_id = ? OR sell_bid_id = ?) AND status <> 'PAYMENT_EXPIRED' ORDER BY id", bidId, bidId, map = ::tradeRow)

    fun warnings(userId: Long): List<WarningRow> = query("SELECT * FROM buyer_warnings WHERE user_id = ? ORDER BY id", userId) {
        WarningRow(it.getLong("id"), it.getLong("user_id"), it.getLong("trade_id"), it.instant("created_at")!!)
    }

    fun warningsOfTrade(tradeId: Long): List<WarningRow> = query("SELECT * FROM buyer_warnings WHERE trade_id = ? ORDER BY id", tradeId) {
        WarningRow(it.getLong("id"), it.getLong("user_id"), it.getLong("trade_id"), it.instant("created_at")!!)
    }

    private fun orderRow(rs: ResultSet) = OrderRow(
        rs.getLong("id"), rs.getLong("buyer_id"), rs.getString("status"), rs.getLong("item_total"), rs.getLong("buyer_fee_total"),
        rs.getLong("shipping_fee"), rs.getLong("point_used"), rs.getLong("card_amount"), rs.getString("idempotency_key"),
        rs.instant("created_at")!!, rs.instant("paid_at"),
    )

    fun order(id: Long): OrderRow = query("SELECT * FROM orders WHERE id = ?", id, map = ::orderRow).single()
    fun ordersByKey(key: String): List<OrderRow> = query("SELECT * FROM orders WHERE idempotency_key = ? ORDER BY id", key, map = ::orderRow)

    /** 그 체결을 담은 주문들(테스트 자기 체결 기준으로 주문을 찾는다). */
    fun ordersOfTrade(tradeId: Long): List<OrderRow> = query(
        "SELECT o.* FROM orders o WHERE o.id IN (SELECT order_id FROM order_items WHERE trade_id = ?) ORDER BY o.id", tradeId, map = ::orderRow,
    )

    private fun itemRow(rs: ResultSet) = OrderItemRow(
        rs.getLong("id"), rs.getLong("order_id"), rs.getLong("trade_id"), rs.getLong("price"), rs.getLong("buyer_fee"),
        rs.getLong("card_share"), rs.getLong("point_share"), rs.getLong("shipping_share"), rs.getString("status"), rs.instant("paid_at"),
    )

    fun orderItems(orderId: Long): List<OrderItemRow> = query("SELECT * FROM order_items WHERE order_id = ? ORDER BY id", orderId, map = ::itemRow)
    fun orderItemsOfTrade(tradeId: Long): List<OrderItemRow> = query("SELECT * FROM order_items WHERE trade_id = ? ORDER BY id", tradeId, map = ::itemRow)

    fun pgTransactions(orderId: Long): List<PgTxRow> = query("SELECT * FROM pg_transactions WHERE order_id = ? ORDER BY id", orderId) {
        PgTxRow(
            it.getLong("id"), it.getLong("order_id"), it.getString("kind"), it.getLong("amount"), it.getString("status"),
            it.getString("pg_tx_id"), it.getString("idempotency_key"), it.instant("requested_at")!!, it.instant("responded_at"),
        )
    }

    private fun ledgerRow(rs: ResultSet) = LedgerRow(
        rs.getLong("id"), rs.getLong("user_id"), rs.getString("kind"), rs.getString("status"), rs.getLong("amount"),
        rs.longOrNull("remaining"), rs.instant("expires_at"), rs.longOrNull("order_item_id"), rs.longOrNull("order_id"), rs.instant("created_at")!!,
    )

    fun ledger(userId: Long): List<LedgerRow> = query("SELECT * FROM point_ledger WHERE user_id = ? ORDER BY id", userId, map = ::ledgerRow)
    fun ledgerEntry(id: Long): LedgerRow = query("SELECT * FROM point_ledger WHERE id = ?", id, map = ::ledgerRow).single()
    fun useEntries(orderId: Long): List<LedgerRow> =
        query("SELECT * FROM point_ledger WHERE kind = 'USE' AND order_id = ? ORDER BY id", orderId, map = ::ledgerRow)
    fun earnOfItem(orderItemId: Long): List<LedgerRow> =
        query("SELECT * FROM point_ledger WHERE kind = 'EARN' AND order_item_id = ? ORDER BY id", orderItemId, map = ::ledgerRow)

    private fun allocRow(rs: ResultSet) = AllocationRow(rs.getLong("id"), rs.getLong("entry_id"), rs.getLong("earn_entry_id"), rs.getLong("amount"))
    fun allocations(entryId: Long): List<AllocationRow> = query("SELECT * FROM point_allocations WHERE entry_id = ? ORDER BY id", entryId, map = ::allocRow)
    fun allocationsOfEarn(earnId: Long): List<AllocationRow> = query("SELECT * FROM point_allocations WHERE earn_entry_id = ? ORDER BY id", earnId, map = ::allocRow)

    fun balance(userId: Long): BalanceRow? = query("SELECT * FROM point_balances WHERE user_id = ?", userId) {
        BalanceRow(it.getLong("user_id"), it.getLong("available"), it.getLong("pending"), it.instant("updated_at")!!)
    }.singleOrNull()
}
