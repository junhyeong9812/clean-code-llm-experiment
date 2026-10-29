package com.ccle.layered.infra

import com.ccle.layered.domain.DeadlineReminder
import com.ccle.layered.domain.Notification
import com.ccle.layered.domain.NotificationRepository
import com.ccle.layered.domain.PriceAlert
import com.ccle.layered.domain.PriceAlertRepository
import com.ccle.layered.domain.Wishlist
import com.ccle.layered.domain.WishlistRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class JpaNotificationRepository(private val em: EntityManager) : NotificationRepository {

    override fun add(notification: Notification) = em.persist(notification)

    override fun find(id: Long): Notification? = em.find(Notification::class.java, id)

    override fun lock(id: Long) {
        em.createNativeQuery("SELECT id FROM notifications WHERE id = :id FOR UPDATE").setParameter("id", id).resultList
    }

    override fun reload(notification: Notification) = em.refresh(notification)

    override fun listOf(userId: Long, unreadOnly: Boolean): List<Notification> {
        val jpql = "SELECT n FROM Notification n WHERE n.userId = :user" +
            (if (unreadOnly) " AND n.readAt IS NULL" else "") + " ORDER BY n.id"
        return em.createQuery(jpql, Notification::class.java).setParameter("user", userId).resultList
    }

    override fun addReminder(reminder: DeadlineReminder, refId: Long, now: Instant): Boolean =
        em.createNativeQuery(ReminderSql.of(reminder).insert).setParameter("now", now).setParameter("id", refId).executeUpdate() == 1
}

@Component
class JpaWishlistRepository(private val em: EntityManager) : WishlistRepository {

    override fun add(wish: Wishlist) = em.persist(wish)

    override fun find(userId: Long, productSizeId: Long): Wishlist? =
        em.createQuery("SELECT w FROM Wishlist w WHERE w.userId = :user AND w.productSizeId = :size", Wishlist::class.java)
            .setParameter("user", userId).setParameter("size", productSizeId).resultList.firstOrNull()

    override fun remove(wish: Wishlist) = em.remove(wish)

    override fun listOf(userId: Long): List<Wishlist> =
        em.createQuery("SELECT w FROM Wishlist w WHERE w.userId = :user ORDER BY w.productSizeId", Wishlist::class.java)
            .setParameter("user", userId).resultList

    override fun keyShareSize(productSizeId: Long): Boolean =
        em.createNativeQuery("SELECT id FROM product_sizes WHERE id = :id FOR KEY SHARE").setParameter("id", productSizeId)
            .resultList.isNotEmpty()

    override fun bestSell(productSizeId: Long, now: Instant): Long? =
        (
            em.createNativeQuery(
                "SELECT min(price) FROM bids WHERE product_size_id = :size AND side = 'SELL' AND status = 'ACTIVE' AND expires_at > :now",
            ).setParameter("size", productSizeId).setParameter("now", now).singleResult as Number?
            )?.toLong()
}

@Component
class JpaPriceAlertRepository(private val em: EntityManager) : PriceAlertRepository {

    override fun add(alert: PriceAlert) = em.persist(alert)

    override fun find(id: Long): PriceAlert? = em.find(PriceAlert::class.java, id)

    override fun lock(id: Long) {
        em.createNativeQuery("SELECT id FROM price_alerts WHERE id = :id FOR UPDATE").setParameter("id", id).resultList
    }

    override fun reload(alert: PriceAlert) = em.refresh(alert)

    override fun countActive(userId: Long, productSizeId: Long): Long =
        em.createQuery(
            "SELECT count(a) FROM PriceAlert a WHERE a.userId = :user AND a.productSizeId = :size AND a.status = 'ACTIVE'",
            Long::class.javaObjectType,
        ).setParameter("user", userId).setParameter("size", productSizeId).singleResult

    override fun activeOf(userId: Long, productSizeId: Long): PriceAlert? =
        em.createQuery(
            "SELECT a FROM PriceAlert a WHERE a.userId = :user AND a.productSizeId = :size AND a.status = 'ACTIVE'",
            PriceAlert::class.java,
        ).setParameter("user", userId).setParameter("size", productSizeId).resultList.firstOrNull()

    override fun listOf(userId: Long): List<PriceAlert> =
        em.createQuery("SELECT a FROM PriceAlert a WHERE a.userId = :user ORDER BY a.id", PriceAlert::class.java)
            .setParameter("user", userId).resultList

    override fun triggeringSell(productSizeId: Long, targetPrice: Long, now: Instant): Long? =
        (
            em.createNativeQuery(
                "SELECT id FROM bids WHERE product_size_id = :size AND side = 'SELL' AND status = 'ACTIVE' AND expires_at > :now" +
                    " AND price <= :target ORDER BY price, created_at, id LIMIT 1",
            ).setParameter("size", productSizeId).setParameter("now", now).setParameter("target", targetPrice)
                .resultList.firstOrNull() as Number?
            )?.toLong()
}
