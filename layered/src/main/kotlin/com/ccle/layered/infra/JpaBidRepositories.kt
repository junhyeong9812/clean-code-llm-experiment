package com.ccle.layered.infra

import com.ccle.layered.domain.Bid
import com.ccle.layered.domain.BidEvent
import com.ccle.layered.domain.BidEventRepository
import com.ccle.layered.domain.BidRepository
import com.ccle.layered.domain.Book
import com.ccle.layered.domain.ProductSize
import com.ccle.layered.domain.ProductSizeRepository
import com.ccle.layered.domain.notFound
import jakarta.persistence.EntityManager
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant

// 새 행은 Spring Data save(새 엔티티면 persist, 아니면 merge) 대신 em.persist로 넣는다: 늘 persist이고,
// IDENTITY라 INSERT가 그 자리에서 실행되어 id 순서가 호출 순서와 같다. 바뀐 행은 영속 상태의 변경 감지로 커밋 때 UPDATE된다.

interface ProductSizeJpaRepository : JpaRepository<ProductSize, Long>

@Repository
class JpaProductSizeRepository(private val jpa: ProductSizeJpaRepository, private val em: EntityManager) : ProductSizeRepository {

    override fun exists(id: Long): Boolean = jpa.findById(id).isPresent

    override fun lock(id: Long) {
        val rows = em.createNativeQuery("SELECT id FROM product_sizes WHERE id = :id FOR UPDATE")
            .setParameter("id", id)
            .resultList
        if (rows.isEmpty()) notFound("없는 사이즈: $id")
    }
}

interface BidJpaRepository : JpaRepository<Bid, Long>

@Repository
class JpaBidRepository(private val jpa: BidJpaRepository, private val em: EntityManager) : BidRepository {

    override fun find(id: Long): Bid? = jpa.findById(id).orElse(null)

    override fun add(bid: Bid) = em.persist(bid)

    override fun reload(bid: Bid) = em.refresh(bid)

    override fun bestCounter(productSizeId: Long, side: String, userId: Long, limitPrice: Long?, now: Instant): Bid? {
        val counterSide = if (side == "BUY") "SELL" else "BUY"
        val crossing = when {
            limitPrice == null -> ""
            side == "BUY" -> " AND price <= :limit"
            else -> " AND price >= :limit"
        }
        val order = if (side == "BUY") "price ASC" else "price DESC"
        val query = em.createNativeQuery(
            "SELECT * FROM bids" +
                " WHERE product_size_id = :size AND side = :side AND status = 'ACTIVE' AND expires_at > :now" +
                " AND user_id <> :user" + crossing +
                " ORDER BY $order, created_at ASC, id ASC LIMIT 1",
            Bid::class.java,
        )
            .setParameter("size", productSizeId)
            .setParameter("side", counterSide)
            .setParameter("now", now)
            .setParameter("user", userId)
        if (limitPrice != null) query.setParameter("limit", limitPrice)
        return query.resultList.firstOrNull() as Bid?
    }

    override fun book(productSizeId: Long, now: Instant): Book {
        val row = em.createNativeQuery(
            """
            SELECT max(price) FILTER (WHERE side = 'BUY'), min(price) FILTER (WHERE side = 'SELL')
              FROM bids
             WHERE product_size_id = :size AND status = 'ACTIVE' AND expires_at > :now
            """,
        ).setParameter("size", productSizeId).setParameter("now", now).singleResult as Array<*>
        return Book(bestBuy = (row[0] as Number?)?.toLong(), bestSell = (row[1] as Number?)?.toLong())
    }
}

@Repository
class JpaBidEventRepository(private val em: EntityManager) : BidEventRepository {
    override fun add(event: BidEvent) = em.persist(event)
}
