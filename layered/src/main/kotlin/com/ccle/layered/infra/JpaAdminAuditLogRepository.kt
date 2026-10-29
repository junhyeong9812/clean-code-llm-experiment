package com.ccle.layered.infra

import com.ccle.layered.domain.AdminAuditLog
import com.ccle.layered.domain.AdminAuditLogRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component

@Component
class JpaAdminAuditLogRepository(private val em: EntityManager) : AdminAuditLogRepository {

    override fun add(log: AdminAuditLog) = em.persist(log)
}
