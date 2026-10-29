package com.ccle.layered.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * 관리자 감사 로그 행(ADR-0007 §2.7·§11.3). 새 `/admin` 쓰기 요청(배치 제외)이 성공 경로의 같은 트랜잭션에서 1행 쓴다.
 * action = REVIEW_HIDE·PAYOUT_ACCOUNT_DECISION·INQUIRY_ANSWER·TAX_INVOICE_CORRECTION·TRACKING_EVENT·INBOUND_SLOT_CREATE·INBOUND_CHECK_IN,
 * target_id = 바뀐 행 id, detail = action별 값(§11.3), created_at = 요청 시각.
 */
@Entity
@Table(name = "admin_audit_logs")
class AdminAuditLog(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var action: String,
    var targetId: Long,
    var detail: String? = null,
    var createdAt: Instant,
)

interface AdminAuditLogRepository {
    fun add(log: AdminAuditLog)
}
