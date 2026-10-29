package com.ccle.layered.infra

import com.ccle.layered.domain.OrderItem
import com.ccle.layered.domain.Review
import com.ccle.layered.domain.ReviewRepository
import com.ccle.layered.domain.SellerRating
import com.ccle.layered.domain.SellerRatingRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class JpaReviewRepository(private val em: EntityManager) : ReviewRepository {

    override fun add(review: Review) = em.persist(review)

    override fun find(id: Long): Review? = em.find(Review::class.java, id)

    override fun lock(id: Long) {
        em.createNativeQuery("SELECT id FROM reviews WHERE id = :id FOR UPDATE").setParameter("id", id).resultList
    }

    override fun reload(review: Review) = em.refresh(review)

    override fun countOfItem(orderItemId: Long): Long =
        em.createQuery("SELECT count(r) FROM Review r WHERE r.orderItemId = :item", Long::class.javaObjectType)
            .setParameter("item", orderItemId).singleResult

    override fun keyShareParties(buyerId: Long, sellerId: Long) {
        em.createNativeQuery("SELECT id FROM users WHERE id IN (:a, :b) ORDER BY id FOR KEY SHARE")
            .setParameter("a", buyerId).setParameter("b", sellerId).resultList
    }

    override fun lockOfSeller(sellerId: Long): Set<Long> =
        em.createNativeQuery("SELECT id FROM reviews WHERE seller_id = :seller ORDER BY id FOR UPDATE")
            .setParameter("seller", sellerId).resultList.map { (it as Number).toLong() }.toSet()

    override fun listOfSeller(sellerId: Long): List<Review> =
        em.createQuery("SELECT r FROM Review r WHERE r.sellerId = :seller ORDER BY r.id", Review::class.java)
            .setParameter("seller", sellerId).resultList

    override fun visibleOfSeller(sellerId: Long): List<Review> =
        em.createQuery(
            "SELECT r FROM Review r WHERE r.sellerId = :seller AND r.status = 'VISIBLE' ORDER BY r.id",
            Review::class.java,
        ).setParameter("seller", sellerId).resultList

    override fun reviewableItemsOf(buyerId: Long, cutoff: Instant): List<OrderItem> =
        em.createQuery(
            "SELECT i FROM OrderItem i WHERE i.status = 'CONFIRMED' AND i.confirmedAt >= :due" +
                " AND i.orderId IN (SELECT o.id FROM PurchaseOrder o WHERE o.buyerId = :user)" +
                " AND NOT EXISTS (SELECT r FROM Review r WHERE r.orderItemId = i.id) ORDER BY i.id",
            OrderItem::class.java,
        ).setParameter("due", cutoff).setParameter("user", buyerId).resultList

    override fun listOfBuyer(buyerId: Long): List<Review> =
        em.createQuery("SELECT r FROM Review r WHERE r.buyerId = :user ORDER BY r.id", Review::class.java)
            .setParameter("user", buyerId).resultList
}

@Component
class JpaSellerRatingRepository(private val em: EntityManager) : SellerRatingRepository {

    override fun find(sellerId: Long): SellerRating? = em.find(SellerRating::class.java, sellerId)

    override fun add(rating: SellerRating) = em.persist(rating)
}
