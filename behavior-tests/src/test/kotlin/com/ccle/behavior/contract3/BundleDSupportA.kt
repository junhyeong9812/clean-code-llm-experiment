package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.S2
import com.ccle.behavior.contract2.SEC
import com.ccle.behavior.contract2.days
import com.ccle.behavior.contract3.SA.textOrNullAt
import com.ccle.behavior.support.Api
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db3
import com.ccle.behavior.support.Env
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.Resp
import com.ccle.behavior.support.Row3
import com.ccle.behavior.support.instantAt
import com.ccle.behavior.support.longAt
import com.fasterxml.jackson.databind.JsonNode
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 묶음 D-A(ADR-0007 §20.2 A13 리뷰 수정·쓸 수 있는 항목·내 리뷰 · §20.3 A14 목표가 변경·재등록·`price-alert-rearm`)
 * 엔드포인트·준비·단언 도우미. 기대값은 ADR-0007 §20(+ §2·§4·§8·§19)에서만 온다.
 */
object SDA {
    // ---- A13 (§20.2) ----
    fun patchReview(id: Long, user: Any?, rating: Any?, content: Any?): Resp =
        Api.request("PATCH", "/reviews/$id", mapOf("rating" to rating, "content" to content), user)
    fun patchReviewRaw(id: Long, user: Any?, raw: String): Resp = Api.request("PATCH", "/reviews/$id", rawBody = raw, userId = user)
    fun reviewable(user: Any?): Resp = Api.get("/me/reviewable-items", user)
    fun myReviews(user: Any?): Resp = Api.get("/me/reviews", user)

    // ---- A14 (§20.3) ----
    fun patchAlert(user: Any?, id: Long, target: Any?): Resp =
        Api.request("PATCH", "/me/price-alerts/$id", mapOf("targetPrice" to target), user)
    fun patchAlertRaw(user: Any?, id: Long, raw: String): Resp = Api.request("PATCH", "/me/price-alerts/$id", rawBody = raw, userId = user)
    fun renew(user: Any?, id: Long): Resp = Api.post("/me/price-alerts/$id/renew", null, user)
    fun rearm(): Resp = S2.runBatch("price-alert-rearm")
    fun scan(): Resp = S2.runBatch("price-alert-scan")

    /**
     * §20.3.3 대상 SQL 문장 그대로(별칭 t = price_alerts)로 센 지금 대상 수. `:due = 현재 − 7일`·`:now = 현재`를 배치와 같은 시각으로 바인드한다.
     * 공유 DB라 processed 고정값 대신 실행 직전 이 수와 맞춰 본다(SG-1).
     */
    fun rearmTargetCount(now: Instant): Long = Db.long(
        "SELECT count(*) FROM price_alerts t WHERE " +
            "t.status = 'TRIGGERED' AND t.closed_at >= ? " +
            "AND EXISTS (SELECT 1 FROM bids b WHERE b.id = t.triggered_bid_id AND b.status IN ('CANCELLED', 'EXPIRED')) " +
            "AND EXISTS (SELECT 1 FROM wishlists w WHERE w.user_id = t.user_id AND w.product_size_id = t.product_size_id) " +
            "AND NOT EXISTS (SELECT 1 FROM price_alerts n WHERE n.user_id = t.user_id AND n.product_size_id = t.product_size_id " +
            "AND (n.id > t.id OR n.status = 'ACTIVE')) " +
            "AND NOT EXISTS (SELECT 1 FROM users u WHERE u.id = t.user_id AND u.purchase_restricted_until > ?)",
        now.minus(days(7)), now,
    )

    fun alertRow(id: Long): Row3 = Db3.row("price_alerts", id)
    fun alertsOf(user: Long, size: Long): List<Row3> = Db3.rows("price_alerts", "user_id = ? AND product_size_id = ?", user, size)

    // ---- 감사 로그 없음 (§20.1 — A13·A14는 새 /admin 쓰기가 아니다) ----
    fun maxAuditId(): Long = Db.long("SELECT coalesce(max(id), 0) FROM admin_audit_logs")
    fun assertNoAuditSince(before: Long) =
        assertEquals(0L, Db.long("SELECT count(*) FROM admin_audit_logs WHERE id > ?", before), "감사 로그를 쓰지 않는다(§20.1)")

    // ---- A13 모양 ----
    /** §20.2.3 내 리뷰 모양 = 리뷰 모양(§4.1) + hiddenReason·updatedAt·reviewDeadline(= confirmed_at + 30일). */
    fun assertMyReviewBody(j: JsonNode, row: Row3, confirmedAt: Instant) {
        SA.assertReviewBody(j, row)
        assertEquals(row.textOrNull("hidden_reason"), j.textOrNullAt("hiddenReason"), "hiddenReason: $j")
        assertEquals(row.instant("updated_at"), j.instantAt("updatedAt"), "updatedAt: $j")
        assertEquals(confirmedAt.plus(days(30)), j.instantAt("reviewDeadline"), "reviewDeadline = confirmed_at + 30일: $j")
    }

    /** §20.2.2 쓸 수 있는 항목 모양. */
    fun assertReviewableBody(j: JsonNode, item: Long, seller: Long, confirmedAt: Instant) {
        assertEquals(item, j.longAt("orderItemId"), "orderItemId: $j")
        assertEquals(seller, j.longAt("sellerId"), "sellerId = 체결 판매자: $j")
        assertEquals(confirmedAt, j.instantAt("confirmedAt"), "confirmedAt: $j")
        assertEquals(confirmedAt.plus(days(30)), j.instantAt("reviewDeadline"), "reviewDeadline = confirmed_at + 30일: $j")
    }

    // ---- A14 준비 ----
    /** 발동한 가격 알림 1건(§8.3의 3 실제 경로). closedAt = 발동한 scan의 현재. */
    data class Triggered(val alertId: Long, val owner: Long, val size: Long, val bidId: Long, val seller: Long, val target: Long, val closedAt: Instant)

    /**
     * 새 사이즈에 (위시리스트) + 가격 알림 + 그 값의 SELL 입찰을 두고 price-alert-scan으로 TRIGGERED를 만든다.
     * 알림 주인은 입찰을 두지 않는다(§20.12 C2 — 구매 제한 테스트가 이 주인에게 제한을 걸 수 있다).
     */
    fun triggered(owner: Long = Db.createUser(), target: Long = 100_000, wish: Boolean = true): Triggered {
        val size = Db.createProductSize()
        if (wish) assertEquals(201, SB.wishAdd(owner, size).status, "준비(A5) 위시리스트")
        val alertId = SB.alert(owner, size, target)
        val seller = Db.createUser()
        val bid = Flow.activeBid(seller, size, "SELL", target)
        val now = Flow.advance(SEC)
        scan()
        val row = alertRow(alertId)
        assertEquals("TRIGGERED", row.text("status"), "준비(A5) 발동: $row")
        assertEquals(bid, row.longOrNull("triggered_bid_id"), "준비(A5) 발동 입찰: $row")
        return Triggered(alertId, owner, size, bid, seller, target, now)
    }

    /** 발동 입찰을 등록자가 취소한다(DELETE /bids — §20.12: 판매 제한으로 만들지 않는다). */
    fun cancelBid(t: Triggered) = assertEquals(200, Flow.cancel(t.seller, t.bidId).status, "준비(1단계) 입찰 취소")

    /**
     * EXPIRED 가격 알림 1건(§8.3의 1 실제 경로): expires_at = 현재 + 1초인 ACTIVE 행을 넣고 1초 뒤 scan.
     * 반환: (알림 id, closed_at = 그 scan의 현재). 여러 개를 같은 closed_at으로 만들려면 [expiredAlerts].
     */
    fun expiredAlerts(pairs: List<Pair<Long, Long>>, target: Long = 100_000): Pair<List<Long>, Instant> {
        val now0 = Flow.now()
        val ids = pairs.map { (user, size) ->
            Db3.insert(
                "price_alerts",
                mapOf(
                    "user_id" to user, "product_size_id" to size, "target_price" to target, "status" to "ACTIVE",
                    "expires_at" to now0.plus(SEC), "triggered_bid_id" to null, "closed_at" to null, "created_at" to now0.minus(days(59)),
                ),
            )!!
        }
        val now = Flow.advance(SEC)
        scan()
        ids.forEach { assertEquals("EXPIRED", alertRow(it).text("status"), "준비(A5) 만료: ${alertRow(it)}") }
        return ids to now
    }

    // ---- A14 단언 ----
    /** §20.3.2·§20.3.3 새 알림: ACTIVE, 같은 사용자·사이즈·target_price, expires_at = 현재 + 60일, created_at = 현재, closed·triggered NULL. */
    fun assertNewAlert(row: Row3, user: Long, size: Long, target: Long, at: Instant) {
        assertEquals(user, row.long("user_id"), "user_id: $row")
        assertEquals(size, row.long("product_size_id"), "product_size_id: $row")
        assertEquals(target, row.long("target_price"), "같은 target_price: $row")
        assertEquals("ACTIVE", row.text("status"), "ACTIVE: $row")
        assertEquals(at.plus(days(60)), row.instant("expires_at"), "expires_at = 현재 + 60일: $row")
        assertEquals(at, row.instant("created_at"), "created_at = 현재: $row")
        assertNull(row.instantOrNull("closed_at"), "closed_at NULL: $row")
        assertNull(row.longOrNull("triggered_bid_id"), "triggered_bid_id NULL: $row")
    }

    /** 재무장 1건: 그 쌍에 옛 알림보다 id가 큰 행이 정확히 1개이고 새 알림 모양이다. 반환: 새 행. */
    fun assertRearmed(t: Triggered, at: Instant): Row3 {
        val newer = alertsOf(t.owner, t.size).filter { it.id > t.alertId }
        assertEquals(1, newer.size, "재무장한 새 알림 1행: $newer")
        assertNewAlert(newer.single(), t.owner, t.size, t.target, at)
        return newer.single()
    }

    fun assertNotRearmed(t: Triggered, why: String) =
        assertTrue(alertsOf(t.owner, t.size).none { it.id > t.alertId }, "재무장 대상 아님($why): ${alertsOf(t.owner, t.size)}")

    // ---- 동시성 ----
    /** 경합 관측 보조 연결(테스트 연결 Db와 별개 — SlowTrigger가 그 연결로 관측하는 동안 다른 스레드에서 쓰지 않게). */
    private val watch: Connection by lazy { DriverManager.getConnection(Env.dbUrl, Env.dbUser, Env.dbPassword).apply { autoCommit = true } }

    /**
     * 먼저 보낸 요청이 SlowTrigger의 advisory 잠금을 기다리기 시작할 때까지 기다린다(고정 지연 대신 — 배치는 앞 대상을 처리하느라
     * 트리거에 늦게 닿을 수 있다). 그 뒤에 보내는 요청은 먼저 요청이 쥔 행 잠금과 만난다.
     */
    fun awaitTriggerWait(timeoutMs: Long = 6_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val n = watch.prepareStatement("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted").use { ps ->
                ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
            if (n >= 1) return
            Thread.sleep(20)
        }
        fail("먼저 보낸 요청이 트리거에서 멈추지 않았다(${timeoutMs}ms)")
    }
}
