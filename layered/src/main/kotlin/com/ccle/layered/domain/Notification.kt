package com.ccle.layered.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Duration
import java.time.Instant

// A4 알림(ADR-0007 §7). 오류 code는 기존 Failure만 쓴다(§2.2 — 새 테이블 행의 상태 충돌도 ITEM_STATE_CONFLICT).

/**
 * 알림 행(§3 notifications). kind = PAYMENT_DUE_SOON·SHIPMENT_DUE_SOON·DISPUTE_WINDOW_CLOSING·SETTLEMENT_PAID·PURCHASE_RESTRICTED·
 * SALE_RESTRICTED(deadline-reminder, §7.1)·PRICE_ALERT(price-alert-scan, §8.3). UNIQUE(dedupe_key).
 */
@Entity
@Table(name = "notifications")
class Notification(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var userId: Long,
    var kind: String,
    var refId: Long,
    var dedupeKey: String,
    var createdAt: Instant,
    readAt: Instant? = null,
) {
    var readAt: Instant? = readAt
        private set

    /** 본인 것만(§7.2 — 아니면 403). */
    fun requireOwner(userId: Long) {
        if (this.userId != userId) throw MarketException(Failure.NOT_OWNER, "남의 알림: $id")
    }

    /** 읽음(§7.2): 이미 읽었으면 409(멱등 아님 — ADR-0004 §9.2와 같음). 알림 행을 잠근 뒤 다시 읽고 부른다. */
    fun markRead(now: Instant) {
        if (readAt != null) throw MarketException(Failure.ITEM_STATE_CONFLICT, "이미 읽은 알림: $id")
        readAt = now
    }

    /** 일괄 읽음(§20.6.1): 잠근 뒤에도 안 읽은 행만 read_at = 현재. 반환 = 바꿨는가(이미 읽었으면 409가 아니라 건너뜀). */
    fun markReadIfUnread(now: Instant): Boolean {
        if (readAt != null) return false
        readAt = now
        return true
    }

    companion object {
        private val KEEP_READ_FOR: Duration = Duration.ofDays(30)

        /** notification-purge(§20.6.3)의 읽은 시각 상한: read_at ≤ 현재 − 30일(정각 포함). */
        fun purgeCutoff(now: Instant): Instant = now.minus(KEEP_READ_FOR)

        /** `GET /me/notifications`의 unread(§7.2·§19의 3): "true"·"false"만(글자 그대로), 없으면 전체. 반환 = 안 읽은 것만인가. */
        fun unreadOnly(unread: String?): Boolean {
            if (unread != null && unread != "true" && unread != "false") invalidRequest("unread는 true 또는 false: $unread")
            return unread == "true"
        }
    }
}

/**
 * deadline-reminder의 여섯 종류(§7.1) — 선언 순서 = 표의 행 순서(종류 하나를 끝까지 훑은 뒤 다음 종류). kind = 이름.
 * 대상 조건과 dedupe_key 식은 SQL 한 식으로 둔다(infra) — 앱에서 키를 따로 계산하지 않는다.
 */
enum class DeadlineReminder(val target: BatchTarget) {
    /** trades: AWAITING_PAYMENT, now < payment_due_at ≤ now + 10분 → 구매자. */
    PAYMENT_DUE_SOON(BatchTarget.PAYMENT_DUE_SOON_TRADES),

    /** order_items: PAID, paid_at + 2일 ≤ now < paid_at + 3일 → 판매자. */
    SHIPMENT_DUE_SOON(BatchTarget.SHIPMENT_DUE_SOON_ITEMS),

    /** order_items: CONFIRMED, confirmed_at + 12일 ≤ now ≤ confirmed_at + 14일 → 구매자. */
    DISPUTE_WINDOW_CLOSING(BatchTarget.DISPUTE_WINDOW_CLOSING_ITEMS),

    /** settlements: PAID, now − 7일 < paid_at ≤ now → 판매자. */
    SETTLEMENT_PAID(BatchTarget.RECENTLY_PAID_SETTLEMENTS),

    /** users: purchase_restricted_until > now → 그 사용자. */
    PURCHASE_RESTRICTED(BatchTarget.PURCHASE_RESTRICTED_USERS),

    /** users: sale_restricted_until > now → 그 사용자. */
    SALE_RESTRICTED(BatchTarget.SALE_RESTRICTED_USERS),
    ;

    companion object {
        /** 결제 기한 알림 창: 기한 10분 전부터. */
        val PAYMENT_LEAD: Duration = Duration.ofMinutes(10)

        /** 발송 기한 알림 창: 결제 + 2일부터 결제 + 3일 전까지. */
        val SHIPMENT_FROM: Duration = Duration.ofDays(2)
        val SHIPMENT_UNTIL: Duration = Duration.ofDays(3)

        /** 분쟁 기간 알림 창: 확정 + 12일부터 확정 + 14일까지(양끝 포함). */
        val DISPUTE_FROM: Duration = Duration.ofDays(12)
        val DISPUTE_UNTIL: Duration = Duration.ofDays(14)

        /** 지급 알림 창: 최근 7일. */
        val SETTLEMENT_WITHIN: Duration = Duration.ofDays(7)
    }
}

interface NotificationRepository {
    fun add(notification: Notification)

    fun find(id: Long): Notification?

    /** 알림 행 FOR UPDATE(§2.3). */
    fun lock(id: Long)

    fun reload(notification: Notification)

    /** 그 사용자의 알림, id 순. unreadOnly면 read_at NULL만. */
    fun listOf(userId: Long, unreadOnly: Boolean): List<Notification>

    /** 일괄 읽음(§20.6.1): 그 사용자의 read_at NULL 행을 id 오름차순으로 FOR UPDATE 하고 잠근 id를 돌려준다(사용자 행은 잠그지 않는다). */
    fun lockUnreadOf(userId: Long): List<Long>

    /** 그 사용자의 read_at NULL 알림 수(§20.6.2). */
    fun countUnread(userId: Long): Long

    /** notification-purge(§20.6.3): 그 행이 아직 read_at ≤ due면 지운다(문장 하나 — 잠금·재확인을 함께 한다). 반환 = 지웠는가. */
    fun purge(id: Long, due: Instant): Boolean

    /** 대상 행(ref) 하나의 알림 1행을 INSERT … SELECT로 쓴다(받는 사람·dedupe_key는 SQL 식). 반환 = 1행 썼는가. */
    fun addReminder(reminder: DeadlineReminder, refId: Long, now: Instant): Boolean
}
