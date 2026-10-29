package com.ccle.layered.service

import com.ccle.layered.domain.BatchTarget
import com.ccle.layered.domain.BatchTargets
import com.ccle.layered.domain.MarketClock
import com.ccle.layered.domain.MyReview
import com.ccle.layered.domain.OrderItemRepository
import com.ccle.layered.domain.OrderRepository
import com.ccle.layered.domain.Review
import com.ccle.layered.domain.ReviewInput
import com.ccle.layered.domain.ReviewRepository
import com.ccle.layered.domain.ReviewableItem
import com.ccle.layered.domain.SellerRating
import com.ccle.layered.domain.SellerRatingRepository
import com.ccle.layered.domain.SellerReviews
import com.ccle.layered.domain.TradeRepository
import com.ccle.layered.domain.UserRepository
import com.ccle.layered.domain.notFound
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * A1 리뷰(ADR-0007 §4): 작성·삭제·관리자 숨김·판매자 리뷰 조회. 사용자 확인(401)은 부르는 쪽이 본문 파싱 전에 한다.
 * 리뷰 행을 바꾸는 요청은 그 행을 FOR UPDATE로 잠그고 다시 읽은 뒤 확인한다(§2.3).
 */
@Service
class ReviewService(
    private val clock: MarketClock,
    private val tx: TransactionTemplate,
    private val users: UserRepository,
    private val orders: OrderRepository,
    private val items: OrderItemRepository,
    private val trades: TradeRepository,
    private val reviews: ReviewRepository,
    private val ratings: SellerRatingRepository,
    private val audit: AdminAuditService,
) {
    private val snapshotTx = TransactionTemplate(tx.transactionManager!!).apply {
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        isReadOnly = true
    }

    /**
     * 작성(§4.1). 순서: 400 → 404(항목) → 403(구매자 아님) → 주문 행 FOR UPDATE → 409(상태 → 중복 → 기한).
     * 주문 행 잠금으로 같은 항목의 동시 작성이 줄을 선다(분쟁 제기도 그 주문 행을 잠근다). 항목 행은 잠그지 않는다.
     */
    fun write(userId: Long, itemId: Long, rating: Long?, content: String?): Review {
        val input = ReviewInput.of(rating, content)
        val now = clock.now()
        return tx.execute {
            val item = items.find(itemId) ?: notFound("없는 주문 항목: $itemId")
            item.requireBuyer(orders.find(item.orderId)!!, userId)
            orders.lock(item.orderId)
            items.reload(item)
            val review = Review.write(item, { reviews.countOfItem(itemId) }, userId, input, now) { trades.find(item.tradeId)!!.sellerId }
            reviews.keyShareParties(userId, review.sellerId)
            reviews.add(review)
            review
        }!!
    }

    /** 삭제(§4.2). 404 → 403(작성자 아님) → 리뷰 행 FOR UPDATE → 409(이미 DELETED). */
    fun delete(userId: Long, reviewId: Long): Review {
        val now = clock.now()
        return tx.execute {
            val review = reviews.find(reviewId) ?: notFound("없는 리뷰: $reviewId")
            review.requireAuthor(userId)
            reviews.lock(reviewId)
            reviews.reload(review)
            review.delete(now)
            review
        }!!
    }

    /** 관리자 숨김(§4.3). VISIBLE만 → HIDDEN·ADMIN + 감사 REVIEW_HIDE(detail null). */
    fun hide(reviewId: Long): Review {
        val now = clock.now()
        return tx.execute {
            val review = reviews.find(reviewId) ?: notFound("없는 리뷰: $reviewId")
            reviews.lock(reviewId)
            reviews.reload(review)
            review.hideByAdmin(now)
            audit.record("REVIEW_HIDE", reviewId, null, now)
            review
        }!!
    }

    /**
     * 수정(§20.2.1). 순서: 400 → 404 → 403(작성자 아님) → 주문 행 FOR UPDATE → 리뷰 행 FOR UPDATE → 409(리뷰 상태 → 항목 상태 → 기한).
     * 주문 행으로 분쟁 제기와, 리뷰 행으로 삭제·숨김·rating-recalc·다른 수정과 줄을 선다. 평점 캐시는 다음 rating-recalc가 반영한다.
     */
    fun edit(userId: Long, reviewId: Long, rating: Long?, content: String?): Review {
        val input = ReviewInput.of(rating, content)
        val now = clock.now()
        return tx.execute {
            val review = reviews.find(reviewId) ?: notFound("없는 리뷰: $reviewId")
            review.requireAuthor(userId)
            val item = items.find(review.orderItemId)!!
            orders.lock(item.orderId)
            reviews.lock(reviewId)
            reviews.reload(review)
            items.reload(item)
            review.edit(item, input, now)
            review
        }!!
    }

    /** `GET /me/reviewable-items`(§20.2.2): 작성이 409 없이 받는 항목, orderItemId 오름차순. 한 스냅샷에서 읽는다. */
    fun reviewableItems(userId: Long): List<ReviewableItem> = snapshotTx.execute {
        val now = clock.now()
        reviews.reviewableItemsOf(userId, Review.writableCutoff(now)).map { item ->
            ReviewableItem(item.id!!, trades.find(item.tradeId)!!.sellerId, item.confirmedAt!!)
        }
    }!!

    /** `GET /me/reviews`(§20.2.3): 내가 쓴 리뷰 전부(세 상태), reviewId 오름차순. 한 스냅샷에서 읽는다. */
    fun myReviews(userId: Long): List<MyReview> = snapshotTx.execute {
        reviews.listOfBuyer(userId).map { MyReview(it, items.find(it.orderItemId)!!.confirmedAt!!) }
    }!!

    /** 판매자 리뷰 조회(§4.4, 헤더 불필요). 없는 사용자 404. 평점과 VISIBLE 리뷰를 한 스냅샷에서 읽는다. */
    fun sellerReviews(sellerId: Long): SellerReviews = snapshotTx.execute {
        users.find(sellerId) ?: notFound("없는 사용자: $sellerId")
        val rating = ratings.find(sellerId)
        SellerReviews(sellerId, rating, reviews.visibleOfSeller(sellerId))
    }!!
}

/**
 * rating-recalc(ADR-0007 §4.5): 리뷰가 있는 판매자(users id 순). 판매자 1명 = 그 판매자의 리뷰 행을 id 순으로 잠근 트랜잭션 하나.
 * 잠근 행만 처리한다 — 두 문장 사이에 커밋된 새 리뷰(잠기지 않음)는 다음 실행의 대상이다.
 * 1·2 분쟁 반영(항목 상태는 읽기만 — 기존 분쟁 경로에 SQL을 넣지 않는다) 3 평점 캐시. processed = 1~3 중 하나라도 쓴 판매자 수.
 */
@Component
class RatingRecalcBatch(
    private val targets: BatchTargets,
    private val tx: TransactionTemplate,
    private val items: OrderItemRepository,
    private val reviews: ReviewRepository,
    private val ratings: SellerRatingRepository,
) {
    fun run(now: Instant): BatchResult = targets.forEachIsolated("rating-recalc", BatchTarget.REVIEWED_SELLERS, now) { sellerId ->
        tx.execute {
            val locked = reviews.lockOfSeller(sellerId)
            val own = reviews.listOfSeller(sellerId).filter { it.id in locked }
            var wrote = false
            for (review in own) {
                if (review.followDispute(items.find(review.orderItemId)!!.status, now)) wrote = true
            }
            val visible = own.filter { it.isVisible() }
            val count = visible.size
            val sum = visible.sumOf { it.rating.toInt() }
            val rating = ratings.find(sellerId)
            if (rating == null) {
                val first = SellerRating.firstOf(sellerId, count, sum, now)
                if (first != null) {
                    ratings.add(first)
                    wrote = true
                }
            } else if (rating.update(count, sum, now)) {
                wrote = true
            }
            wrote
        }!!
    }
}
