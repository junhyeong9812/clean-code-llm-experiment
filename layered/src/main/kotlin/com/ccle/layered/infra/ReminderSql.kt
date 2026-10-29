package com.ccle.layered.infra

import com.ccle.layered.domain.BatchTarget
import com.ccle.layered.domain.DeadlineReminder
import java.time.Instant

/**
 * deadline-reminder 한 종류의 SQL(ADR-0007 §7.1 표의 한 행, 별칭 t = 훑는 표). condition = 대상 조건(dedupe 제외),
 * recipient = 받는 사람 식, dedupeKey = dedupe_key 식 — 대상 조건의 NOT EXISTS(KeysetBatchTargets)와 INSERT(JpaNotificationRepository)가
 * 같은 식을 쓴다(앱에서 키를 따로 계산하지 않는다).
 */
internal class ReminderSql private constructor(
    reminder: DeadlineReminder,
    val table: String,
    condition: String,
    val params: (Instant) -> Map<String, Any>,
    recipient: String,
    dedupeKey: String,
) {
    val targetCondition = "$condition AND NOT EXISTS (SELECT 1 FROM notifications n WHERE n.dedupe_key = $dedupeKey)"

    /** 파라미터 :now(created_at)·:id(대상 행). */
    val insert = "INSERT INTO notifications (user_id, kind, ref_id, dedupe_key, created_at)" +
        " SELECT $recipient, '${reminder.name}', t.id, $dedupeKey, :now FROM $table t WHERE t.id = :id"

    companion object {
        /** 제한 알림의 키: `<kind>:<사용자 id>:<E>`, E = 제한 끝 시각의 epoch 초 내림. */
        private fun restrictionKey(kind: String, column: String) =
            "'$kind:' || t.id || ':' || CAST(floor(extract(epoch FROM t.$column)) AS bigint)"

        fun of(target: BatchTarget): ReminderSql = of(DeadlineReminder.values().first { it.target == target })

        fun of(reminder: DeadlineReminder): ReminderSql = when (reminder) {
            DeadlineReminder.PAYMENT_DUE_SOON -> ReminderSql(
                reminder, "trades",
                "t.status = 'AWAITING_PAYMENT' AND t.payment_due_at > :now AND t.payment_due_at <= :after10m",
                { mapOf("now" to it, "after10m" to it.plus(DeadlineReminder.PAYMENT_LEAD)) },
                "t.buyer_id", "'PAYMENT_DUE_SOON:' || t.id",
            )
            DeadlineReminder.SHIPMENT_DUE_SOON -> ReminderSql(
                reminder, "order_items",
                "t.status = 'PAID' AND t.paid_at <= :before2d AND t.paid_at > :before3d",
                { mapOf("before2d" to it.minus(DeadlineReminder.SHIPMENT_FROM), "before3d" to it.minus(DeadlineReminder.SHIPMENT_UNTIL)) },
                "(SELECT tr.seller_id FROM trades tr WHERE tr.id = t.trade_id)", "'SHIPMENT_DUE_SOON:' || t.id",
            )
            DeadlineReminder.DISPUTE_WINDOW_CLOSING -> ReminderSql(
                reminder, "order_items",
                "t.status = 'CONFIRMED' AND t.confirmed_at <= :before12d AND t.confirmed_at >= :before14d",
                { mapOf("before12d" to it.minus(DeadlineReminder.DISPUTE_FROM), "before14d" to it.minus(DeadlineReminder.DISPUTE_UNTIL)) },
                "(SELECT o.buyer_id FROM orders o WHERE o.id = t.order_id)", "'DISPUTE_WINDOW_CLOSING:' || t.id",
            )
            DeadlineReminder.SETTLEMENT_PAID -> ReminderSql(
                reminder, "settlements",
                "t.status = 'PAID' AND t.paid_at > :before7d AND t.paid_at <= :now",
                { mapOf("before7d" to it.minus(DeadlineReminder.SETTLEMENT_WITHIN), "now" to it) },
                "t.seller_id", "'SETTLEMENT_PAID:' || t.id",
            )
            DeadlineReminder.PURCHASE_RESTRICTED -> ReminderSql(
                reminder, "users", "t.purchase_restricted_until > :now", { mapOf("now" to it) },
                "t.id", restrictionKey("PURCHASE_RESTRICTED", "purchase_restricted_until"),
            )
            DeadlineReminder.SALE_RESTRICTED -> ReminderSql(
                reminder, "users", "t.sale_restricted_until > :now", { mapOf("now" to it) },
                "t.id", restrictionKey("SALE_RESTRICTED", "sale_restricted_until"),
            )
        }
    }
}
