package com.ccle.layered.service

import com.ccle.layered.domain.BatchTarget
import com.ccle.layered.domain.BatchTargets
import com.ccle.layered.domain.MarketClock
import com.ccle.layered.domain.NotificationRepository
import com.ccle.layered.domain.PriceAlert
import com.ccle.layered.domain.PriceAlertInput
import com.ccle.layered.domain.PriceAlertRepository
import com.ccle.layered.domain.UserRepository
import com.ccle.layered.domain.Wishlist
import com.ccle.layered.domain.WishlistItem
import com.ccle.layered.domain.WishlistRepository
import com.ccle.layered.domain.invalidRequest
import com.ccle.layered.domain.notFound
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * A5 위시리스트(ADR-0007 §8.1). 사용자 확인(401)은 부르는 쪽이 한다.
 * 등록 잠금 순서(§19의 10-2): 사이즈 KEY SHARE → 사용자 행 FOR UPDATE(기존 입찰·결제 만료 경로와 같은 "사이즈 → 사용자").
 */
@Service
class WishlistService(
    private val clock: MarketClock,
    private val tx: TransactionTemplate,
    private val users: UserRepository,
    private val wishes: WishlistRepository,
    private val alerts: PriceAlertRepository,
) {
    private val snapshotTx = TransactionTemplate(tx.transactionManager!!).apply {
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        isReadOnly = true
    }

    /** 등록: 400 → 없는 사이즈 404 → 사용자 행 FOR UPDATE → 이미 있으면 409. */
    fun add(userId: Long, productSizeId: Long?): Wishlist {
        if (productSizeId == null) invalidRequest("productSizeId가 필요하다")
        val now = clock.now()
        return tx.execute {
            if (!wishes.keyShareSize(productSizeId)) notFound("없는 사이즈: $productSizeId")
            users.lock(userId)
            val wish = Wishlist.add(userId, productSizeId, wishes.find(userId, productSizeId), now)
            wishes.add(wish)
            wish
        }!!
    }

    /** 삭제(§19의 10-1): 사용자 행 FOR UPDATE 뒤 다시 찾는다 — 내 위시리스트에 없으면 404(동시 삭제는 하나만 200). 행을 지운다. */
    fun remove(userId: Long, productSizeId: Long): Wishlist = tx.execute {
        users.lock(userId)
        val wish = wishes.find(userId, productSizeId) ?: notFound("위시리스트에 없는 사이즈: $productSizeId")
        wishes.remove(wish)
        wish
    }!!

    /** 조회: productSizeId 오름차순, 한 스냅샷에서 bestSell·activeAlert(§19의 10)까지 읽는다. */
    fun list(userId: Long): List<WishlistItem> = snapshotTx.execute {
        val now = clock.now()
        wishes.listOf(userId).map { wish ->
            WishlistItem(wish, wishes.bestSell(wish.productSizeId, now), alerts.activeOf(userId, wish.productSizeId))
        }
    }!!
}

/** A5 가격 알림 등록·취소·조회(ADR-0007 §8.2). 사용자 확인(401)은 부르는 쪽이 한다. */
@Service
class PriceAlertService(
    private val clock: MarketClock,
    private val tx: TransactionTemplate,
    private val users: UserRepository,
    private val wishes: WishlistRepository,
    private val alerts: PriceAlertRepository,
) {
    /** 등록: 400 → 없는 사이즈 404(KEY SHARE, §19의 10-2) → 사용자 행 FOR UPDATE → 같은 사이즈 ACTIVE가 있으면 409. */
    fun open(userId: Long, productSizeId: Long?, targetPrice: Long?): PriceAlert {
        val input = PriceAlertInput.of(productSizeId, targetPrice)
        val now = clock.now()
        return tx.execute {
            if (!wishes.keyShareSize(input.productSizeId)) notFound("없는 사이즈: ${input.productSizeId}")
            users.lock(userId)
            val alert = PriceAlert.open(userId, input, alerts.countActive(userId, input.productSizeId), now)
            alerts.add(alert)
            alert
        }!!
    }

    /** 취소: 404 → 403(남의 알림) → 알림 행 FOR UPDATE → 409(ACTIVE 아님). */
    fun cancel(userId: Long, alertId: Long): PriceAlert {
        val now = clock.now()
        return tx.execute {
            val alert = alerts.find(alertId) ?: notFound("없는 가격 알림: $alertId")
            alert.requireOwner(userId)
            alerts.lock(alertId)
            alerts.reload(alert)
            alert.cancel(now)
            alert
        }!!
    }

    /** 조회: 본인 가격 알림 전부, alertId 오름차순. */
    fun list(userId: Long): List<PriceAlert> = alerts.listOf(userId)
}

/**
 * price-alert-scan(ADR-0007 §8.3): ACTIVE 가격 알림(id 순). 알림 1건 = 그 행을 잠근 트랜잭션 하나, 잠근 뒤 다시 확인한다.
 * 1 만료 → EXPIRED. 2 주인이 구매 제한 중이면 그대로(세지 않음). 3 맞는 SELL 입찰이 있으면 TRIGGERED + 알림 PRICE_ALERT. 입찰은 읽기만 한다.
 * processed = 1 또는 3으로 바뀐 알림 수.
 */
@Component
class PriceAlertScanBatch(
    private val targets: BatchTargets,
    private val tx: TransactionTemplate,
    private val users: UserRepository,
    private val alerts: PriceAlertRepository,
    private val notifications: NotificationRepository,
) {
    fun run(now: Instant): BatchResult = targets.forEachIsolated("price-alert-scan", BatchTarget.ACTIVE_PRICE_ALERTS, now) { alertId ->
        tx.execute {
            alerts.lock(alertId)
            val alert = alerts.find(alertId)!!
            if (!alert.isActive()) return@execute false
            if (alert.isExpired(now)) {
                alert.expire(now)
                return@execute true
            }
            if (alert.isPausedFor(users.find(alert.userId)!!, now)) return@execute false
            val bidId = alerts.triggeringSell(alert.productSizeId, alert.targetPrice, now) ?: return@execute false
            notifications.add(alert.trigger(bidId, now))
            true
        }!!
    }
}
