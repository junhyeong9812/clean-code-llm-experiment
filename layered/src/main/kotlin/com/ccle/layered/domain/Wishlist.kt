package com.ccle.layered.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Duration
import java.time.Instant

// A5 위시리스트·가격 알림(ADR-0007 §8). 오류 code는 기존 Failure만 쓴다(§2.2).

/** 위시리스트 행(§3 wishlists). UNIQUE(user_id, product_size_id). */
@Entity
@Table(name = "wishlists")
class Wishlist(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var userId: Long,
    var productSizeId: Long,
    var createdAt: Instant,
) {
    companion object {
        /** 등록(§8.1): 사용자 행을 잠근 뒤 찾은 기존 행이 있으면 409. */
        fun add(userId: Long, productSizeId: Long, existing: Wishlist?, now: Instant): Wishlist {
            if (existing != null) throw MarketException(Failure.ITEM_STATE_CONFLICT, "이미 위시리스트에 있는 사이즈: $productSizeId")
            return Wishlist(userId = userId, productSizeId = productSizeId, createdAt = now)
        }
    }
}

/** 위시리스트 조회 1행(§8.1·§19의 10): bestSell = 체결 가능한 SELL 최저가(없으면 null), activeAlert = 그 사이즈의 내 ACTIVE 가격 알림. */
class WishlistItem(val wish: Wishlist, val bestSell: Long?, val activeAlert: PriceAlert?)

/** 가격 알림 등록 요청의 값(§8.2): productSizeId 필수, targetPrice 양의 정수. */
class PriceAlertInput private constructor(val productSizeId: Long, val targetPrice: Long) {
    companion object {
        fun of(productSizeId: Long?, targetPrice: Long?): PriceAlertInput {
            if (productSizeId == null) invalidRequest("productSizeId가 필요하다")
            if (targetPrice == null) invalidRequest("targetPrice가 필요하다")
            if (targetPrice <= 0) invalidRequest("targetPrice는 0보다 커야 한다: $targetPrice")
            return PriceAlertInput(productSizeId, targetPrice)
        }
    }
}

/** 가격 알림 행(§3 price_alerts). status = ACTIVE·TRIGGERED·EXPIRED·CANCELLED, 부분 UNIQUE(user_id, product_size_id) WHERE ACTIVE. */
@Entity
@Table(name = "price_alerts")
class PriceAlert(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var userId: Long,
    var productSizeId: Long,
    var targetPrice: Long,
    status: String,
    var expiresAt: Instant,
    triggeredBidId: Long? = null,
    closedAt: Instant? = null,
    var createdAt: Instant,
) {
    var status: String = status
        private set

    var triggeredBidId: Long? = triggeredBidId
        private set

    var closedAt: Instant? = closedAt
        private set

    fun isActive(): Boolean = status == "ACTIVE"

    /** 본인 것만(§8.2 — 아니면 403). */
    fun requireOwner(userId: Long) {
        if (this.userId != userId) throw MarketException(Failure.NOT_OWNER, "남의 가격 알림: $id")
    }

    /** 취소(§8.2): ACTIVE만 → CANCELLED. 알림 행을 잠근 뒤 다시 읽고 부른다. */
    fun cancel(now: Instant) {
        if (!isActive()) throw MarketException(Failure.ITEM_STATE_CONFLICT, "ACTIVE가 아닌 가격 알림: $id ($status)")
        status = "CANCELLED"
        closedAt = now
    }

    /** price-alert-scan의 1(§8.3): expires_at ≤ 현재. */
    fun isExpired(now: Instant): Boolean = !expiresAt.isAfter(now)

    fun expire(now: Instant) {
        status = "EXPIRED"
        closedAt = now
    }

    /** price-alert-scan의 2(§8.3): 주인이 구매 제한 중이면 멈춘다(ACTIVE 유지). 제한은 저장된 열을 읽기만 한다. */
    fun isPausedFor(owner: User, now: Instant): Boolean = owner.purchaseRestrictedUntil?.isAfter(now) == true

    /** price-alert-scan의 3(§8.3): → TRIGGERED + 알림 PRICE_ALERT(ref_id = 알림 id, 받는 사람 = 알림 주인). */
    fun trigger(bidId: Long, now: Instant): Notification {
        status = "TRIGGERED"
        triggeredBidId = bidId
        closedAt = now
        return Notification(userId = userId, kind = "PRICE_ALERT", refId = id!!, dedupeKey = "PRICE_ALERT:$id", createdAt = now)
    }

    companion object {
        private val VALID_FOR: Duration = Duration.ofDays(60)

        /** 등록(§8.2): 사용자 행을 잠근 뒤 센 같은 사이즈 ACTIVE 수가 0이어야 한다. expires_at = 현재 + 60일. 구매 제한 중에도 등록한다. */
        fun open(userId: Long, input: PriceAlertInput, activeCount: Long, now: Instant): PriceAlert {
            if (activeCount > 0) {
                throw MarketException(Failure.ITEM_STATE_CONFLICT, "같은 사이즈의 ACTIVE 가격 알림이 있다: ${input.productSizeId}")
            }
            return PriceAlert(
                userId = userId,
                productSizeId = input.productSizeId,
                targetPrice = input.targetPrice,
                status = "ACTIVE",
                expiresAt = now.plus(VALID_FOR),
                createdAt = now,
            )
        }
    }
}

interface WishlistRepository {
    fun add(wish: Wishlist)

    fun find(userId: Long, productSizeId: Long): Wishlist?

    fun remove(wish: Wishlist)

    /** 그 사용자의 위시리스트, productSizeId 순. */
    fun listOf(userId: Long): List<Wishlist>

    /**
     * §19의 10-2: 사이즈 존재 확인을 `FOR KEY SHARE`로 한다(사용자 행 잠금보다 먼저 — "사이즈 → 사용자" 순서). 반환 = 있는가.
     * 가격 알림 등록도 같은 판정을 쓴다.
     */
    fun keyShareSize(productSizeId: Long): Boolean

    /** 그 사이즈의 ACTIVE이고 expires_at > 현재인 SELL 입찰 최저가(호가와 같은 기준), 없으면 null. */
    fun bestSell(productSizeId: Long, now: Instant): Long?
}

interface PriceAlertRepository {
    fun add(alert: PriceAlert)

    fun find(id: Long): PriceAlert?

    /** 가격 알림 행 FOR UPDATE(§2.3). */
    fun lock(id: Long)

    fun reload(alert: PriceAlert)

    /** 그 사용자·사이즈의 ACTIVE 알림 수. */
    fun countActive(userId: Long, productSizeId: Long): Long

    /** 그 사용자·사이즈의 ACTIVE 알림(최대 1건 — 부분 UNIQUE). */
    fun activeOf(userId: Long, productSizeId: Long): PriceAlert?

    /** 그 사용자의 가격 알림 전부, id 순. */
    fun listOf(userId: Long): List<PriceAlert>

    /** 그 사이즈의 ACTIVE·expires_at > 현재·price ≤ target인 SELL 입찰 중 price, created_at, id 순 첫 번째 id(읽기 — 잠그지 않는다). */
    fun triggeringSell(productSizeId: Long, targetPrice: Long, now: Instant): Long?
}
