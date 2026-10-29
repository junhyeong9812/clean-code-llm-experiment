package com.ccle.layered.api

import com.ccle.layered.domain.MyReview
import com.ccle.layered.domain.ReviewableItem
import com.ccle.layered.service.ReviewService
import com.ccle.layered.service.UserService
import com.fasterxml.jackson.annotation.JsonUnwrapped
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/** 쓸 수 있는 항목(ADR-0007 §20.2.2). reviewDeadline = confirmed_at + 30일(계산 값). */
data class ReviewableItemView(val orderItemId: Long, val sellerId: Long, val confirmedAt: Instant, val reviewDeadline: Instant) {
    constructor(i: ReviewableItem) : this(i.orderItemId, i.sellerId, i.confirmedAt, i.reviewDeadline)
}

/** 내 리뷰 모양(§20.2.3) = 리뷰 모양 + hiddenReason·updatedAt·reviewDeadline(수정 기한, 계산 값). */
data class MyReviewView(
    @get:JsonUnwrapped val review: ReviewView,
    val hiddenReason: String?,
    val updatedAt: Instant,
    val reviewDeadline: Instant,
) {
    constructor(m: MyReview) : this(ReviewView(m.review), m.review.hiddenReason, m.review.updatedAt, m.reviewDeadline)
}

/** 리뷰 작성·삭제·관리자 숨김·판매자 리뷰 조회(ADR-0007 §4), 수정·쓸 수 있는 항목·내 리뷰(§20.2). */
@RestController
class ReviewController(private val users: UserService, private val reviews: ReviewService) {

    @PostMapping("/order-items/{itemId}/review")
    fun write(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable itemId: String,
        @RequestBody(required = false) body: String?,
    ): ResponseEntity<ReviewView> {
        val userId = users.requireUser(userIdOf(userHeader))
        val json = parseObject(body)
        val review = reviews.write(userId, pathId(itemId), json.longOrNull("rating"), json.textOrNull("content"))
        return ResponseEntity.status(HttpStatus.CREATED).body(ReviewView(review))
    }

    @DeleteMapping("/reviews/{reviewId}")
    fun delete(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable reviewId: String,
    ): ReviewView {
        val userId = users.requireUser(userIdOf(userHeader))
        return ReviewView(reviews.delete(userId, pathId(reviewId)))
    }

    @PatchMapping("/reviews/{reviewId}")
    fun edit(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable reviewId: String,
        @RequestBody(required = false) body: String?,
    ): ReviewView {
        val userId = users.requireUser(userIdOf(userHeader))
        val json = parseObject(body)
        return ReviewView(reviews.edit(userId, pathId(reviewId), json.longOrNull("rating"), json.textOrNull("content")))
    }

    @GetMapping("/me/reviewable-items")
    fun reviewable(@RequestHeader("X-User-Id", required = false) userHeader: String?): List<ReviewableItemView> =
        reviews.reviewableItems(users.requireUser(userIdOf(userHeader))).map { ReviewableItemView(it) }

    @GetMapping("/me/reviews")
    fun mine(@RequestHeader("X-User-Id", required = false) userHeader: String?): List<MyReviewView> =
        reviews.myReviews(users.requireUser(userIdOf(userHeader))).map { MyReviewView(it) }

    @PostMapping("/admin/reviews/{reviewId}/hide")
    fun hide(@PathVariable reviewId: String): ReviewView = ReviewView(reviews.hide(pathId(reviewId)))

    /** 헤더 불필요 — X-User-Id를 읽지 않는다(ADR-0007 §19의 7). */
    @GetMapping("/sellers/{userId}/reviews")
    fun sellerReviews(@PathVariable userId: String): SellerReviewsView = SellerReviewsView(reviews.sellerReviews(pathId(userId)))
}
