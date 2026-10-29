package com.ccle.layered.api

import com.ccle.layered.service.NotificationService
import com.ccle.layered.service.PriceAlertService
import com.ccle.layered.service.UserService
import com.ccle.layered.service.WishlistService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 알림 조회·읽음(ADR-0007 §7.2), 위시리스트·가격 알림(§8.1·§8.2). */
@RestController
class NotificationController(
    private val users: UserService,
    private val notifications: NotificationService,
    private val wishes: WishlistService,
    private val alerts: PriceAlertService,
) {

    @GetMapping("/me/notifications")
    fun notifications(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @RequestParam(required = false) unread: String?,
    ): List<NotificationView> {
        val userId = users.requireUser(userIdOf(userHeader))
        return notifications.list(userId, unread).map { NotificationView(it) }
    }

    @PostMapping("/me/notifications/{notificationId}/read")
    fun read(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable notificationId: String,
    ): NotificationView {
        val userId = users.requireUser(userIdOf(userHeader))
        return NotificationView(notifications.read(userId, pathId(notificationId)))
    }

    @PostMapping("/me/wishlist")
    fun addWish(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @RequestBody(required = false) body: String?,
    ): ResponseEntity<WishlistEntryView> {
        val userId = users.requireUser(userIdOf(userHeader))
        val json = parseObject(body)
        val wish = wishes.add(userId, json.longOrNull("productSizeId"))
        return ResponseEntity.status(HttpStatus.CREATED).body(WishlistEntryView(wish))
    }

    @DeleteMapping("/me/wishlist/{productSizeId}")
    fun removeWish(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable productSizeId: String,
    ): WishlistEntryView {
        val userId = users.requireUser(userIdOf(userHeader))
        return WishlistEntryView(wishes.remove(userId, pathId(productSizeId)))
    }

    @GetMapping("/me/wishlist")
    fun wishlist(@RequestHeader("X-User-Id", required = false) userHeader: String?): List<WishlistItemView> =
        wishes.list(users.requireUser(userIdOf(userHeader))).map { WishlistItemView(it) }

    @PostMapping("/me/price-alerts")
    fun addAlert(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @RequestBody(required = false) body: String?,
    ): ResponseEntity<PriceAlertView> {
        val userId = users.requireUser(userIdOf(userHeader))
        val json = parseObject(body)
        val alert = alerts.open(userId, json.longOrNull("productSizeId"), json.longOrNull("targetPrice"))
        return ResponseEntity.status(HttpStatus.CREATED).body(PriceAlertView(alert))
    }

    @DeleteMapping("/me/price-alerts/{alertId}")
    fun cancelAlert(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable alertId: String,
    ): PriceAlertView {
        val userId = users.requireUser(userIdOf(userHeader))
        return PriceAlertView(alerts.cancel(userId, pathId(alertId)))
    }

    @GetMapping("/me/price-alerts")
    fun alerts(@RequestHeader("X-User-Id", required = false) userHeader: String?): List<PriceAlertView> =
        alerts.list(users.requireUser(userIdOf(userHeader))).map { PriceAlertView(it) }
}
