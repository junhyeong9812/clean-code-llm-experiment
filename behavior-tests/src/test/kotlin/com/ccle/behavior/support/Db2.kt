package com.ccle.behavior.support

import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

// ---- 2단계 조회 행 (ADR-0004, docker/schema/tables.sql) ----
/** 주문 항목 전체(시각 열 포함). 1단계 OrderItemRow는 paid_at까지만 담는다. */
data class ItemRow(
    val id: Long, val orderId: Long, val tradeId: Long, val status: String,
    val price: Long, val buyerFee: Long, val cardShare: Long, val pointShare: Long, val shippingShare: Long,
    val paidAt: Instant?, val shippedAt: Instant?, val inspectedAt: Instant?, val deliveredAt: Instant?,
    val confirmedAt: Instant?, val cancelledAt: Instant?,
)
data class InspectionRow(val id: Long, val orderItemId: Long, val result: String, val reason: String?, val inspectedAt: Instant)
data class DisputeRow(val id: Long, val orderItemId: Long, val status: String, val openedAt: Instant, val resolvedAt: Instant?)
data class RefundRow(
    val id: Long, val orderItemId: Long, val cardAmount: Long, val pointAmount: Long, val buyerFeeAmount: Long,
    val shippingAmount: Long, val reason: String, val fault: String, val createdAt: Instant,
)
data class PenaltyRow(val id: Long, val sellerId: Long, val orderItemId: Long, val amount: Long, val settlementId: Long?, val createdAt: Instant)
data class SettlementRow(
    val id: Long, val sellerId: Long, val periodStart: LocalDate, val periodEnd: LocalDate,
    val gross: Long, val feeTotal: Long, val penaltyTotal: Long, val carryIn: Long, val net: Long,
    val payout: Long, val carryOut: Long, val status: String, val paidAt: Instant?,
)
data class SettlementItemRow(val id: Long, val settlementId: Long, val orderItemId: Long, val amount: Long, val fee: Long, val status: String)
data class GradeHistoryRow(val id: Long, val userId: Long, val grade: String, val effectiveFrom: LocalDate, val basisAmount: Long)

/**
 * 2단계 JDBC 도우미(ADR-0004). Db의 연결·query를 그대로 쓰고 조회만 더한다.
 * 만들기는 ADR이 "테스트가 넣는다"고 한 등급 이력(§8.1)뿐이다.
 */
object Db2 {
    private fun ResultSet.instant(col: String): Instant? = getObject(col, OffsetDateTime::class.java)?.toInstant()
    private fun ResultSet.date(col: String): LocalDate = getObject(col, LocalDate::class.java)
    private fun ResultSet.longOrNull(col: String): Long? = getLong(col).takeUnless { wasNull() }

    // ---- 만들기 ----
    /**
     * 등급 이력 1행 + users.grade를 그 사용자의 가장 최근(effective_from 최대) 이력 등급으로 맞춘다
     * (verify `user_grade_current`·`grade_history_threshold`: effective_from은 1일, basis는 등급 구간 안).
     */
    fun addGradeHistory(userId: Long, grade: String, effectiveFrom: LocalDate, basisAmount: Long): Long {
        require(effectiveFrom.dayOfMonth == 1) { "effective_from은 1일" }
        val id = Db.insertId(
            "INSERT INTO user_grade_history(user_id, grade, effective_from, basis_amount) VALUES (?, ?, ?, ?)",
            userId, grade, effectiveFrom, basisAmount,
        )
        Db.update(
            "UPDATE users SET grade = (SELECT grade FROM user_grade_history WHERE user_id = ? ORDER BY effective_from DESC LIMIT 1) WHERE id = ?",
            userId, userId,
        )
        return id
    }

    // ---- 조회 ----
    private fun itemRow(rs: ResultSet) = ItemRow(
        rs.getLong("id"), rs.getLong("order_id"), rs.getLong("trade_id"), rs.getString("status"),
        rs.getLong("price"), rs.getLong("buyer_fee"), rs.getLong("card_share"), rs.getLong("point_share"), rs.getLong("shipping_share"),
        rs.instant("paid_at"), rs.instant("shipped_at"), rs.instant("inspected_at"), rs.instant("delivered_at"),
        rs.instant("confirmed_at"), rs.instant("cancelled_at"),
    )

    fun item(id: Long): ItemRow = Db.query("SELECT * FROM order_items WHERE id = ?", id, map = ::itemRow).single()
    fun itemsOfOrder(orderId: Long): List<ItemRow> = Db.query("SELECT * FROM order_items WHERE order_id = ? ORDER BY id", orderId, map = ::itemRow)

    fun inspections(itemId: Long): List<InspectionRow> = Db.query("SELECT * FROM inspections WHERE order_item_id = ? ORDER BY id", itemId) {
        InspectionRow(it.getLong("id"), it.getLong("order_item_id"), it.getString("result"), it.getString("reason"), it.instant("inspected_at")!!)
    }

    private fun disputeRow(rs: ResultSet) =
        DisputeRow(rs.getLong("id"), rs.getLong("order_item_id"), rs.getString("status"), rs.instant("opened_at")!!, rs.instant("resolved_at"))

    fun disputes(itemId: Long): List<DisputeRow> = Db.query("SELECT * FROM disputes WHERE order_item_id = ? ORDER BY id", itemId, map = ::disputeRow)
    fun dispute(id: Long): DisputeRow = Db.query("SELECT * FROM disputes WHERE id = ?", id, map = ::disputeRow).single()

    private fun refundRow(rs: ResultSet) = RefundRow(
        rs.getLong("id"), rs.getLong("order_item_id"), rs.getLong("card_amount"), rs.getLong("point_amount"), rs.getLong("buyer_fee_amount"),
        rs.getLong("shipping_amount"), rs.getString("reason"), rs.getString("fault"), rs.instant("created_at")!!,
    )

    fun refunds(itemId: Long): List<RefundRow> = Db.query("SELECT * FROM refunds WHERE order_item_id = ? ORDER BY id", itemId, map = ::refundRow)
    fun refundsOfOrder(orderId: Long): List<RefundRow> = Db.query(
        "SELECT r.* FROM refunds r JOIN order_items oi ON oi.id = r.order_item_id WHERE oi.order_id = ? ORDER BY r.id", orderId, map = ::refundRow,
    )

    private fun penaltyRow(rs: ResultSet) = PenaltyRow(
        rs.getLong("id"), rs.getLong("seller_id"), rs.getLong("order_item_id"), rs.getLong("amount"), rs.longOrNull("settlement_id"), rs.instant("created_at")!!,
    )

    fun penaltiesOfItem(itemId: Long): List<PenaltyRow> = Db.query("SELECT * FROM seller_penalties WHERE order_item_id = ? ORDER BY id", itemId, map = ::penaltyRow)
    fun penaltiesOfSeller(sellerId: Long): List<PenaltyRow> = Db.query("SELECT * FROM seller_penalties WHERE seller_id = ? ORDER BY id", sellerId, map = ::penaltyRow)

    /** 판매자의 정산, period_start 오름차순. */
    fun settlementsOfSeller(sellerId: Long): List<SettlementRow> = Db.query("SELECT * FROM settlements WHERE seller_id = ? ORDER BY period_start", sellerId) {
        SettlementRow(
            it.getLong("id"), it.getLong("seller_id"), it.date("period_start"), it.date("period_end"),
            it.getLong("gross"), it.getLong("fee_total"), it.getLong("penalty_total"), it.getLong("carry_in"), it.getLong("net"),
            it.getLong("payout"), it.getLong("carry_out"), it.getString("status"), it.instant("paid_at"),
        )
    }

    private fun settlementItemRow(rs: ResultSet) = SettlementItemRow(
        rs.getLong("id"), rs.getLong("settlement_id"), rs.getLong("order_item_id"), rs.getLong("amount"), rs.getLong("fee"), rs.getString("status"),
    )

    fun settlementItems(settlementId: Long): List<SettlementItemRow> =
        Db.query("SELECT * FROM settlement_items WHERE settlement_id = ? ORDER BY order_item_id, id", settlementId, map = ::settlementItemRow)
    fun settlementItemsOfItem(itemId: Long): List<SettlementItemRow> =
        Db.query("SELECT * FROM settlement_items WHERE order_item_id = ? ORDER BY id", itemId, map = ::settlementItemRow)

    fun gradeHistory(userId: Long): List<GradeHistoryRow> = Db.query("SELECT * FROM user_grade_history WHERE user_id = ? ORDER BY effective_from", userId) {
        GradeHistoryRow(it.getLong("id"), it.getLong("user_id"), it.getString("grade"), it.date("effective_from"), it.getLong("basis_amount"))
    }

    private fun ledgerRow(rs: ResultSet) = LedgerRow(
        rs.getLong("id"), rs.getLong("user_id"), rs.getString("kind"), rs.getString("status"), rs.getLong("amount"),
        rs.longOrNull("remaining"), rs.instant("expires_at"), rs.longOrNull("order_item_id"), rs.longOrNull("order_id"), rs.instant("created_at")!!,
    )

    /** 원장 중 그 항목·종류(EARN·RESTORE·REVOKE 등)의 행, id 순. */
    fun ledgerOfItem(itemId: Long, kind: String): List<LedgerRow> =
        Db.query("SELECT * FROM point_ledger WHERE order_item_id = ? AND kind = ? ORDER BY id", itemId, kind, map = ::ledgerRow)

    /** 사용자의 원장 중 그 종류의 행, id 순. */
    fun ledgerOfUser(userId: Long, kind: String): List<LedgerRow> = Db.ledger(userId).filter { it.kind == kind }

    /** 그 주문의 pg_transactions 중 CANCEL 행. */
    fun pgCancels(orderId: Long): List<PgTxRow> = Db.pgTransactions(orderId).filter { it.kind == "CANCEL" }
}
