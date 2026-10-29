package com.ccle.layered.api

import com.ccle.layered.service.ReviewService
import com.ccle.layered.service.UserService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/** 리뷰 작성·삭제·관리자 숨김·판매자 리뷰 조회(ADR-0007 §4). */
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

    @PostMapping("/admin/reviews/{reviewId}/hide")
    fun hide(@PathVariable reviewId: String): ReviewView = ReviewView(reviews.hide(pathId(reviewId)))

    /** 헤더 불필요 — X-User-Id를 읽지 않는다(ADR-0007 §19의 7). */
    @GetMapping("/sellers/{userId}/reviews")
    fun sellerReviews(@PathVariable userId: String): SellerReviewsView = SellerReviewsView(reviews.sellerReviews(pathId(userId)))
}
