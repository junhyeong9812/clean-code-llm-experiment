package com.ccle.layered.infra

import com.ccle.layered.domain.Settlement
import com.ccle.layered.domain.TaxInvoice
import com.ccle.layered.domain.TaxInvoiceRepository
import com.ccle.layered.domain.TaxInvoiceSettlements
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component

@Component
class JpaTaxInvoiceRepository(private val em: EntityManager) : TaxInvoiceRepository {

    override fun add(invoice: TaxInvoice) = em.persist(invoice)

    override fun find(id: Long): TaxInvoice? = em.find(TaxInvoice::class.java, id)

    override fun lock(id: Long) {
        em.createNativeQuery("SELECT id FROM tax_invoices WHERE id = :id FOR UPDATE").setParameter("id", id).resultList
    }

    override fun reload(invoice: TaxInvoice) = em.refresh(invoice)

    override fun flush() = em.flush()

    override fun countOfSettlement(settlementId: Long): Long =
        em.createQuery("SELECT count(v) FROM TaxInvoice v WHERE v.settlementId = :s", Long::class.javaObjectType)
            .setParameter("s", settlementId).singleResult

    override fun listOf(sellerId: Long): List<TaxInvoice> =
        em.createQuery("SELECT v FROM TaxInvoice v WHERE v.sellerId = :seller ORDER BY v.id", TaxInvoice::class.java)
            .setParameter("seller", sellerId).resultList
}

@Component
class JpaTaxInvoiceSettlements(private val em: EntityManager) : TaxInvoiceSettlements {

    override fun lock(settlementId: Long) {
        em.createNativeQuery("SELECT id FROM settlements WHERE id = :id FOR UPDATE").setParameter("id", settlementId).resultList
    }

    override fun find(settlementId: Long): Settlement = em.find(Settlement::class.java, settlementId)
}
