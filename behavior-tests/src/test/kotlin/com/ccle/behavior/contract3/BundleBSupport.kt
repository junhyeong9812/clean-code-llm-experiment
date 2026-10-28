package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.instantOrNull
import com.ccle.behavior.support.Api
import com.ccle.behavior.support.Db3
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.Resp
import com.ccle.behavior.support.Row3
import com.ccle.behavior.support.instantAt
import com.ccle.behavior.support.longAt
import com.ccle.behavior.support.textAt
import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 묶음 B(ADR-0007 §7 A4 알림 · §8 A5 위시리스트·가격 알림 · §12.2 A11 배송 추적) 엔드포인트·조회·단언 도우미. */
object SB {
    // ---- A4 알림 (§7.2) ----
    fun notifications(user: Any?, unread: String? = null): Resp =
        Api.get("/me/notifications" + (unread?.let { "?unread=$it" } ?: ""), user)

    fun read(id: Long, user: Any?): Resp = Api.post("/me/notifications/$id/read", null, user)

    /** dedupe_key로 알림 행(UNIQUE — 0 또는 1행). */
    fun notifByKey(key: String): Row3? = Db3.rows("notifications", "dedupe_key = ?", key).singleOrNull()

    fun notifCount(key: String): Long = Db3.count("notifications", "dedupe_key = ?", key)

    /**
     * §7.1 알림 1행: 받는 사람·kind·ref_id·dedupe_key·created_at = 그 배치의 현재·read_at NULL. 반환: 그 행.
     */
    fun assertNotif(key: String, user: Long, kind: String, refId: Long, at: Instant): Row3 {
        val rows = Db3.rows("notifications", "dedupe_key = ?", key)
        assertEquals(1, rows.size, "dedupe_key '$key' 알림 1행: $rows")
        val n = rows.single()
        assertEquals(user, n.long("user_id"), "받는 사람: $n")
        assertEquals(kind, n.text("kind"), "kind: $n")
        assertEquals(refId, n.long("ref_id"), "ref_id: $n")
        assertEquals(at, n.instant("created_at"), "created_at = 배치의 현재: $n")
        assertEquals(null, n.instantOrNull("read_at"), "read_at NULL: $n")
        return n
    }

    /** §7.2 알림 모양 = DB 행. */
    fun assertNotifBody(j: JsonNode, row: Row3) {
        for (f in listOf("notificationId", "kind", "refId", "createdAt", "readAt")) assertTrue(j.has(f), "알림 필드 '$f' 없음: $j")
        assertEquals(row.id, j.longAt("notificationId"), "notificationId: $j")
        assertEquals(row.text("kind"), j.textAt("kind"), "kind: $j")
        assertEquals(row.long("ref_id"), j.longAt("refId"), "refId: $j")
        assertEquals(row.instant("created_at"), j.instantAt("createdAt"), "createdAt: $j")
        assertEquals(row.instantOrNull("read_at"), j.instantOrNull("readAt"), "readAt: $j")
    }

    /** `<E>` = 제한 끝 시각의 epoch 초 내림(§7.1). */
    fun epochFloor(t: Instant): Long = Math.floorDiv(t.toEpochMilli(), 1000L)

    // ---- A5 위시리스트 (§8.1) ----
    fun wishAdd(user: Any?, size: Any?): Resp = Api.post("/me/wishlist", mapOf("productSizeId" to size), user)
    fun wishDel(user: Any?, size: Long): Resp = Api.delete("/me/wishlist/$size", user)
    fun wishlist(user: Any?): Resp = Api.get("/me/wishlist", user)

    fun wishRows(user: Long, size: Long): List<Row3> = Db3.rows("wishlists", "user_id = ? AND product_size_id = ?", user, size)

    // ---- A5 가격 알림 (§8.2) ----
    fun alertAdd(user: Any?, size: Any?, target: Any?): Resp =
        Api.post("/me/price-alerts", mapOf("productSizeId" to size, "targetPrice" to target), user)

    fun alertCancel(user: Any?, id: Long): Resp = Api.delete("/me/price-alerts/$id", user)
    fun alerts(user: Any?): Resp = Api.get("/me/price-alerts", user)

    /** 가격 알림을 API로 만들고 alertId를 돌려준다(준비). */
    fun alert(user: Long, size: Long, target: Long): Long {
        val r = alertAdd(user, size, target)
        assertEquals(201, r.status, "준비(A5) 가격 알림 등록: $r")
        return r.long("alertId")
    }

    val ALERT_FIELDS = listOf("alertId", "productSizeId", "targetPrice", "status", "expiresAt", "triggeredBidId", "closedAt", "createdAt")

    /** §8.2 가격 알림 모양 = DB 행. */
    fun assertAlertBody(j: JsonNode, row: Row3) {
        for (f in ALERT_FIELDS) assertTrue(j.has(f), "가격 알림 필드 '$f' 없음: $j")
        assertEquals(row.id, j.longAt("alertId"), "alertId: $j")
        assertEquals(row.long("product_size_id"), j.longAt("productSizeId"), "productSizeId: $j")
        assertEquals(row.long("target_price"), j.longAt("targetPrice"), "targetPrice: $j")
        assertEquals(row.text("status"), j.textAt("status"), "status: $j")
        assertEquals(row.instant("expires_at"), j.instantAt("expiresAt"), "expiresAt: $j")
        val tb = j.get("triggeredBidId")
        assertEquals(row.longOrNull("triggered_bid_id"), if (tb.isNull) null else tb.asLong(), "triggeredBidId: $j")
        assertEquals(row.instantOrNull("closed_at"), j.instantOrNull("closedAt"), "closedAt: $j")
        assertEquals(row.instant("created_at"), j.instantAt("createdAt"), "createdAt: $j")
    }

    // ---- A11 배송 추적 (§12.2) ----
    fun event(carrier: Any? = "CJ", trackingNo: Any? = "123456789012", eventType: Any? = "PICKED_UP", occurredAt: Any?): Map<String, Any?> =
        mapOf("carrier" to carrier, "trackingNo" to trackingNo, "eventType" to eventType, "occurredAt" to occurredAt)

    fun postEvent(item: Long, body: Map<String, Any?>): Resp = Api.post("/admin/order-items/$item/tracking-events", body)
    fun tracking(item: Long, user: Any?): Resp = Api.get("/order-items/$item/tracking", user)

    fun events(item: Long): List<Row3> = Db3.rows("shipment_tracking_events", "order_item_id = ?", item)
    fun delays(item: Long): List<Row3> = Db3.rows("shipment_delays", "order_item_id = ?", item)
    fun trackingAudits(): Long = Db3.count("admin_audit_logs", "action = 'TRACKING_EVENT'")

    val EVENT_FIELDS = listOf("eventId", "orderItemId", "carrier", "trackingNo", "eventType", "occurredAt", "receivedAt")

    /** §12.2 이벤트 모양 = DB 행. */
    fun assertEventBody(j: JsonNode, row: Row3) {
        for (f in EVENT_FIELDS) assertTrue(j.has(f), "이벤트 필드 '$f' 없음: $j")
        assertEquals(row.id, j.longAt("eventId"), "eventId: $j")
        assertEquals(row.long("order_item_id"), j.longAt("orderItemId"), "orderItemId: $j")
        assertEquals(row.text("carrier"), j.textAt("carrier"), "carrier: $j")
        assertEquals(row.text("tracking_no"), j.textAt("trackingNo"), "trackingNo: $j")
        assertEquals(row.text("event_type"), j.textAt("eventType"), "eventType: $j")
        assertEquals(row.instant("occurred_at"), j.instantAt("occurredAt"), "occurredAt: $j")
        assertEquals(row.instant("received_at"), j.instantAt("receivedAt"), "receivedAt: $j")
    }

    fun iso(t: Instant): String = Flow.iso(t)

    // ---- 동시성 (기존 RestrictionAndConcurrencyTest 방식) ----
    fun concurrently(n: Int, call: (Int) -> Resp): List<Resp> {
        val pool = Executors.newFixedThreadPool(n)
        val ready = CountDownLatch(n)
        val go = CountDownLatch(1)
        try {
            val futures = (0 until n).map { i ->
                pool.submit<Resp> {
                    ready.countDown()
                    go.await()
                    call(i)
                }
            }
            ready.await(10, TimeUnit.SECONDS)
            go.countDown()
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
