package com.ccle.behavior.support

import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/** 새 테이블 1행(ADR-0007 §3). 열 이름 → 값: timestamptz = Instant, date = LocalDate, 정수 = Long, 문자열·boolean·null 그대로. */
class Row3(val table: String, private val cols: Map<String, Any?>) {
    operator fun get(col: String): Any? {
        require(col in cols) { "$table 행에 열 '$col' 없음: $this" }
        return cols[col]
    }

    fun long(col: String): Long = get(col) as? Long ?: error("$table.$col 정수 아님: $this")
    fun longOrNull(col: String): Long? = get(col) as Long?
    fun text(col: String): String = get(col) as? String ?: error("$table.$col 문자열 아님: $this")
    fun textOrNull(col: String): String? = get(col) as String?
    fun instant(col: String): Instant = get(col) as? Instant ?: error("$table.$col 시각 아님: $this")
    fun instantOrNull(col: String): Instant? = get(col) as Instant?
    fun date(col: String): LocalDate = get(col) as? LocalDate ?: error("$table.$col 날짜 아님: $this")
    fun bool(col: String): Boolean = get(col) as? Boolean ?: error("$table.$col boolean 아님: $this")
    val id: Long get() = long("id")

    override fun toString() = "$table$cols"
}

/**
 * 단계 A 새 테이블 JDBC 도우미(ADR-0007 §3). Db의 연결을 그대로 쓴다.
 * 새 테이블은 아직 DDL이 없어(다음 작업) 열 이름은 ADR-0007 §3 표가 정본이다 — 여기서는 열 목록을 하드코딩하지 않고
 * 테스트가 열 이름을 문자열로 쓴다. 그래서 DDL이 ADR과 다르면 이 도우미가 아니라 그 열을 쓰는 테스트가 실패해 위치가 드러난다.
 */
object Db3 {
    /** ADR-0007 §3의 새 테이블 이름(오타 방지 — 이 밖의 이름은 거부). */
    val TABLES = setOf(
        "reviews", "seller_ratings", "seller_grades", "fee_rebates", "payout_account_requests", "monthly_statements",
        "notifications", "wishlists", "price_alerts", "inquiries", "inquiry_messages", "tax_invoices", "price_stats_daily",
        "admin_audit_logs", "seller_profiles", "shipment_tracking_events", "shipment_delays", "inbound_slots", "inbound_reservations",
    )

    private val IDENT = Regex("[a-z_][a-z0-9_]*")
    private val ORDER_BY = Regex("[a-z_][a-z0-9_]*( (ASC|DESC))?(, [a-z_][a-z0-9_]*( (ASC|DESC))?)*")

    private fun checkTable(table: String) = require(table in TABLES) { "ADR-0007 §3에 없는 테이블: $table" }

    private fun value(rs: ResultSet, i: Int): Any? {
        val o = rs.getObject(i) ?: return null
        return when (o) {
            is java.sql.Timestamp -> rs.getObject(i, OffsetDateTime::class.java).toInstant()
            is java.sql.Date -> rs.getObject(i, LocalDate::class.java)
            is Int -> o.toLong()
            is Short -> o.toLong()
            is java.math.BigDecimal -> o
            else -> o
        }
    }

    private fun toRow(table: String, rs: ResultSet): Row3 {
        val md = rs.metaData
        val m = LinkedHashMap<String, Any?>()
        for (i in 1..md.columnCount) m[md.getColumnLabel(i)] = value(rs, i)
        return Row3(table, m)
    }

    /** table에서 where(SQL 조각, ? 자리표시)를 만족하는 행, orderBy 순(기본 id). */
    fun rows(table: String, where: String = "TRUE", vararg params: Any?, orderBy: String = "id"): List<Row3> {
        checkTable(table)
        require(orderBy.matches(ORDER_BY)) { "orderBy는 열 이름[ ASC|DESC] 목록만: $orderBy" }
        return Db.query("SELECT * FROM $table WHERE $where ORDER BY $orderBy", *params) { toRow(table, it) }
    }

    fun row(table: String, id: Long): Row3 = rows(table, "id = ?", id).singleOrNull() ?: error("$table id=$id 없음")

    /** 기본 키가 user_id/seller_id인 테이블(seller_ratings·seller_profiles)의 1행 또는 null. */
    fun rowBy(table: String, keyCol: String, key: Long): Row3? {
        require(keyCol.matches(IDENT))
        return rows(table, "$keyCol = ?", key, orderBy = keyCol).singleOrNull()
    }

    fun count(table: String, where: String = "TRUE", vararg params: Any?): Long {
        checkTable(table)
        return Db.long("SELECT count(*) FROM $table WHERE $where", *params)
    }

    /**
     * 픽스처 1행 INSERT(테스트가 API로 만들 수 없는 상태 — 지난 달 등급 행·시드 같은 과거 행 등). 반환: id(없는 테이블이면 null).
     * 값: Instant → timestamptz, LocalDate → date. 앱이 쓰는 행 모양(ADR-0007 §3 CHECK)을 지켜야 한다.
     */
    fun insert(table: String, values: Map<String, Any?>): Long? {
        checkTable(table)
        require(values.keys.all { it.matches(IDENT) })
        val cols = values.keys.joinToString(", ")
        val qs = values.keys.joinToString(", ") { "?" }
        val sql = "INSERT INTO $table($cols) VALUES ($qs)"
        val hasId = table !in setOf("seller_ratings", "seller_profiles")
        return if (hasId) Db.insertId(sql, *values.values.toTypedArray()) else { Db.update(sql, *values.values.toTypedArray()); null }
    }

    /** 감사 로그(§2.7): 그 action·target_id의 행, id 순. */
    fun audits(action: String, targetId: Long): List<Row3> = rows("admin_audit_logs", "action = ? AND target_id = ?", action, targetId)

    /** 알림(§7): 그 사용자의 행, id 순. */
    fun notificationsOf(userId: Long): List<Row3> = rows("notifications", "user_id = ?", userId)
}
