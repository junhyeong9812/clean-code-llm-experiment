package com.ccle.layered.infra

import com.ccle.layered.domain.AdminInquiryFilter
import com.ccle.layered.domain.Inquiry
import com.ccle.layered.domain.InquiryMessage
import com.ccle.layered.domain.InquiryRepository
import com.ccle.layered.domain.KeysetPage
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class JpaInquiryRepository(private val em: EntityManager) : InquiryRepository {

    override fun find(id: Long): Inquiry? = em.find(Inquiry::class.java, id)

    override fun add(inquiry: Inquiry) = em.persist(inquiry)

    override fun lock(id: Long) {
        em.createNativeQuery("SELECT id FROM inquiries WHERE id = :id FOR UPDATE").setParameter("id", id).resultList
    }

    override fun reload(inquiry: Inquiry) = em.refresh(inquiry)

    override fun listOf(userId: Long): List<Inquiry> =
        em.createQuery("SELECT i FROM Inquiry i WHERE i.userId = :user ORDER BY i.id", Inquiry::class.java)
            .setParameter("user", userId).resultList

    override fun addMessage(message: InquiryMessage) = em.persist(message)

    override fun messagesOf(inquiryId: Long): List<InquiryMessage> =
        em.createQuery("SELECT m FROM InquiryMessage m WHERE m.inquiryId = :inquiry ORDER BY m.id", InquiryMessage::class.java)
            .setParameter("inquiry", inquiryId).resultList

    override fun keyShareItem(orderItemId: Long): Boolean {
        val orderId = em.createNativeQuery("SELECT order_id FROM order_items WHERE id = :id").setParameter("id", orderItemId)
            .resultList.firstOrNull() ?: return false
        em.createNativeQuery("SELECT id FROM orders WHERE id = :id FOR KEY SHARE").setParameter("id", orderId).resultList
        em.createNativeQuery("SELECT id FROM order_items WHERE id = :id FOR KEY SHARE").setParameter("id", orderItemId).resultList
        return true
    }

    override fun countOpenDisputes(orderItemId: Long): Long =
        em.createQuery("SELECT count(d) FROM Dispute d WHERE d.orderItemId = :item AND d.status = 'OPEN'", Long::class.javaObjectType)
            .setParameter("item", orderItemId).singleResult

    override fun adminPage(filter: AdminInquiryFilter, now: Instant, page: KeysetPage): List<Long> {
        val sql = "SELECT t.id FROM inquiries t WHERE t.id > :after" +
            (if (filter.status != null) " AND t.status = :status" else "") +
            (if (filter.slaBreached != null) " AND coalesce(t.first_answered_at > t.sla_due_at, t.sla_due_at < :now) = :breached" else "") +
            " ORDER BY t.id LIMIT :limit"
        val query = em.createNativeQuery(sql).setParameter("after", page.after).setParameter("limit", page.limit)
        if (filter.status != null) query.setParameter("status", filter.status)
        if (filter.slaBreached != null) query.setParameter("now", now).setParameter("breached", filter.slaBreached)
        return query.resultList.map { (it as Number).toLong() }
    }
}
