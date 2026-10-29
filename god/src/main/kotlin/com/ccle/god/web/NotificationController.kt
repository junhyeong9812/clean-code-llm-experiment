package com.ccle.god.web

import com.ccle.god.Marketplace
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 알림 조회·읽음(ADR-0007 §7.2)·일괄 읽음·안 읽은 수(§20.6), 위시리스트·가격 알림(§8.1·§8.2)·목표가 변경·재등록(§20.3). */
@RestController
class NotificationController(private val marketplace: Marketplace) {

    @GetMapping("/me/notifications")
    fun notifications(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @RequestParam(required = false) unread: String?,
    ): List<NotificationView> {
        val userId = marketplace.requireUser(userHeader)
        return marketplace.notifications(userId, unread)
    }

    @PostMapping("/me/notifications/{notificationId}/read")
    fun read(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable notificationId: String,
    ): NotificationView {
        val userId = marketplace.requireUser(userHeader)
        return marketplace.readNotification(userId, pathId(notificationId))
    }

    @PostMapping("/me/notifications/read-all")
    fun readAll(@RequestHeader("X-User-Id", required = false) userHeader: String?): ReadAllView =
        marketplace.readAllNotifications(marketplace.requireUser(userHeader))

    @GetMapping("/me/notifications/unread-count")
    fun unreadCount(@RequestHeader("X-User-Id", required = false) userHeader: String?): UnreadCountView =
        marketplace.unreadNotificationCount(marketplace.requireUser(userHeader))

    @PostMapping("/me/wishlist")
    fun addWish(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @RequestBody(required = false) body: String?,
    ): ResponseEntity<WishlistEntryView> {
        val userId = marketplace.requireUser(userHeader)
        val json = parseObject(body)
        val entry = marketplace.addWish(userId, json.longOrNull("productSizeId"))
        return ResponseEntity.status(HttpStatus.CREATED).body(entry)
    }

    @DeleteMapping("/me/wishlist/{productSizeId}")
    fun removeWish(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable productSizeId: String,
    ): WishlistEntryView {
        val userId = marketplace.requireUser(userHeader)
        return marketplace.removeWish(userId, pathId(productSizeId))
    }

    @GetMapping("/me/wishlist")
    fun wishlist(@RequestHeader("X-User-Id", required = false) userHeader: String?): List<WishlistItemView> =
        marketplace.wishlist(marketplace.requireUser(userHeader))

    @PostMapping("/me/price-alerts")
    fun addAlert(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @RequestBody(required = false) body: String?,
    ): ResponseEntity<PriceAlertView> {
        val userId = marketplace.requireUser(userHeader)
        val json = parseObject(body)
        val alert = marketplace.addPriceAlert(userId, json.longOrNull("productSizeId"), json.longOrNull("targetPrice"))
        return ResponseEntity.status(HttpStatus.CREATED).body(alert)
    }

    @DeleteMapping("/me/price-alerts/{alertId}")
    fun cancelAlert(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable alertId: String,
    ): PriceAlertView {
        val userId = marketplace.requireUser(userHeader)
        return marketplace.cancelPriceAlert(userId, pathId(alertId))
    }

    @PatchMapping("/me/price-alerts/{alertId}")
    fun changeAlert(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable alertId: String,
        @RequestBody(required = false) body: String?,
    ): PriceAlertView {
        val userId = marketplace.requireUser(userHeader)
        val json = parseObject(body)
        return marketplace.changePriceAlertTarget(userId, pathId(alertId), json.longOrNull("targetPrice"))
    }

    @PostMapping("/me/price-alerts/{alertId}/renew")
    fun renewAlert(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable alertId: String,
    ): ResponseEntity<PriceAlertView> {
        val userId = marketplace.requireUser(userHeader)
        return ResponseEntity.status(HttpStatus.CREATED).body(marketplace.renewPriceAlert(userId, pathId(alertId)))
    }

    @GetMapping("/me/price-alerts")
    fun alerts(@RequestHeader("X-User-Id", required = false) userHeader: String?): List<PriceAlertView> =
        marketplace.priceAlerts(marketplace.requireUser(userHeader))
}
