package com.ccle.layered.service

import com.ccle.layered.domain.AdminAuditLog
import com.ccle.layered.domain.AdminAuditLogRepository
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * 관리자 감사 로그(ADR-0007 §2.7·§11.3). 새 `/admin` 쓰기 요청(배치 제외)이 성공 경로의 같은 트랜잭션 안에서 부른다
 * (실패하면 함께 롤백된다). 기존 `/admin` 요청은 부르지 않는다.
 */
@Service
class AdminAuditService(private val logs: AdminAuditLogRepository) {

    /** target_id = 바뀐 행 id, detail = action별 값(§11.3), created_at = 요청 시각. */
    fun record(action: String, targetId: Long, detail: String?, now: Instant) {
        logs.add(AdminAuditLog(action = action, targetId = targetId, detail = detail, createdAt = now))
    }
}
