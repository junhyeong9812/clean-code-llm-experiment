package com.ccle.layered.service

import com.ccle.layered.domain.BatchTarget
import com.ccle.layered.domain.BatchTargets
import com.ccle.layered.domain.DeadlineReminder
import com.ccle.layered.domain.MarketClock
import com.ccle.layered.domain.Notification
import com.ccle.layered.domain.NotificationRepository
import com.ccle.layered.domain.notFound
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/** A4 알림 조회·읽음(ADR-0007 §7.2)·일괄 읽음·안 읽은 수(§20.6). 사용자 확인(401)은 부르는 쪽이 한다. */
@Service
class NotificationService(
    private val clock: MarketClock,
    private val tx: TransactionTemplate,
    private val notifications: NotificationRepository,
) {
    /** `GET /me/notifications`: 본인 알림 notificationId 오름차순. unread는 "true"·"false"만(§19의 3), true면 read_at NULL만. */
    fun list(userId: Long, unread: String?): List<Notification> = notifications.listOf(userId, Notification.unreadOnly(unread))

    /** 읽음: 404 → 403(남의 알림) → 알림 행 FOR UPDATE → 409(이미 읽음). read_at = 현재. */
    fun read(userId: Long, notificationId: Long): Notification {
        val now = clock.now()
        return tx.execute {
            val notification = notifications.find(notificationId) ?: notFound("없는 알림: $notificationId")
            notification.requireOwner(userId)
            notifications.lock(notificationId)
            notifications.reload(notification)
            notification.markRead(now)
            notification
        }!!
    }

    /**
     * 일괄 읽음(§20.6.1): 내 안 읽은 알림을 id 오름차순으로 FOR UPDATE(같은 사용자의 두 요청이 교착하지 않게) → 잠근 뒤에도 안 읽은 행만
     * read_at = 현재. 반환 = 바꾼 수(0건도 성공).
     */
    fun readAll(userId: Long): Long {
        val now = clock.now()
        return tx.execute {
            notifications.lockUnreadOf(userId).count { notifications.find(it)!!.markReadIfUnread(now) }.toLong()
        }!!
    }

    /** `GET /me/notifications/unread-count`(§20.6.2): 내 알림 중 read_at NULL 수. */
    fun unreadCount(userId: Long): Long = notifications.countUnread(userId)
}

/**
 * notification-purge(ADR-0007 §20.6.3): read_at IS NOT NULL이고 read_at ≤ 현재 − 30일(id 순). 알림 1건 = DELETE 한 문장의 트랜잭션 하나
 * (잠금 없음 — 문장이 행 잠금과 조건 재평가를 함께 한다). 안 읽은 알림은 지우지 않는다. processed = 지운 행 수.
 */
@Component
class NotificationPurgeBatch(
    private val targets: BatchTargets,
    private val tx: TransactionTemplate,
    private val notifications: NotificationRepository,
) {
    fun run(now: Instant): BatchResult {
        val due = Notification.purgeCutoff(now)
        return targets.forEachIsolated("notification-purge", BatchTarget.PURGEABLE_NOTIFICATIONS, now) { notificationId ->
            tx.execute { notifications.purge(notificationId, due) }!!
        }
    }
}

/**
 * deadline-reminder(ADR-0007 §7.1): 여섯 종류를 표의 행 순서대로 하나씩 끝까지 훑는다(종류마다 그 표의 id 순).
 * 대상 1건 = 알림 1행 INSERT 트랜잭션 하나(잠금 없음). 기한·제한은 저장된 열을 비교만 한다.
 * processed = 만든 알림 수.
 */
@Component
class DeadlineReminderBatch(
    private val targets: BatchTargets,
    private val tx: TransactionTemplate,
    private val notifications: NotificationRepository,
) {
    fun run(now: Instant): BatchResult {
        var processed = 0
        var failed = 0
        for (reminder in DeadlineReminder.values()) {
            val result = targets.forEachIsolated("deadline-reminder", reminder.target, now) { refId ->
                tx.execute { notifications.addReminder(reminder, refId, now) }!!
            }
            processed += result.processed
            failed += result.failed
        }
        return BatchResult(processed, failed)
    }
}
