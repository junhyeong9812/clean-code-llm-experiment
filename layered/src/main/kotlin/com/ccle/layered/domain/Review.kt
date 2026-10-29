package com.ccle.layered.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Duration
import java.time.Instant

// A1 리뷰·평점(ADR-0007 §4). 오류 code는 기존 Failure만 쓴다(§2.2 — 새 테이블 행의 상태 충돌도 ITEM_STATE_CONFLICT).

/** 리뷰 작성 요청의 값(§4.1): rating 1~5 정수, content는 앞뒤 공백을 뺀 값이 1~1,000자. 저장은 받은 그대로. */
class ReviewInput private constructor(val rating: Short, val content: String) {
    companion object {
        fun of(rating: Long?, content: String?): ReviewInput {
            if (rating == null) invalidRequest("rating이 필요하다")
            if (rating !in 1L..5L) invalidRequest("rating은 1~5: $rating")
            if (content == null) invalidRequest("content가 필요하다")
            if (textLength(content) !in 1..1_000) invalidRequest("content는 1~1,000자")
            return ReviewInput(rating.toShort(), content)
        }
    }
}

@Entity
@Table(name = "reviews")
class Review(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var orderItemId: Long,
    var buyerId: Long,
    var sellerId: Long,
    var rating: Short,
    var content: String,
    status: String,
    hiddenReason: String? = null,
    var createdAt: Instant,
    updatedAt: Instant,
) {
    var status: String = status
        private set

    var hiddenReason: String? = hiddenReason
        private set

    var updatedAt: Instant = updatedAt
        private set

    fun isVisible(): Boolean = status == "VISIBLE"

    /** 작성자만(§4.2 — 아니면 403). */
    fun requireAuthor(userId: Long) {
        if (buyerId != userId) throw MarketException(Failure.NOT_OWNER, "작성자가 아닌 리뷰: $id")
    }

    /** 삭제(§4.2): VISIBLE·HIDDEN → DELETED(hidden_reason NULL). DELETED면 409. 리뷰 행을 잠근 뒤 다시 읽고 부른다. */
    fun delete(now: Instant) {
        if (status == "DELETED") throw MarketException(Failure.ITEM_STATE_CONFLICT, "이미 지운 리뷰: $id")
        status = "DELETED"
        hiddenReason = null
        updatedAt = now
    }

    /** 관리자 숨김(§4.3): VISIBLE만 → HIDDEN·ADMIN. 리뷰 행을 잠근 뒤 다시 읽고 부른다. */
    fun hideByAdmin(now: Instant) {
        if (status != "VISIBLE") throw MarketException(Failure.ITEM_STATE_CONFLICT, "VISIBLE이 아닌 리뷰: $id ($status)")
        status = "HIDDEN"
        hiddenReason = "ADMIN"
        updatedAt = now
    }

    /**
     * 수정(§20.2.1): 주문 행·리뷰 행을 잠근 뒤 다시 읽은 리뷰·항목으로 부른다. 409 순서는 1 리뷰 VISIBLE 아님 → 2 항목 CONFIRMED 아님
     * → 3 현재 > confirmed_at + 30일(DEADLINE_PASSED). rating·content = 받은 값, updated_at = 현재(같은 값이어도 쓴다).
     */
    fun edit(item: OrderItem, input: ReviewInput, now: Instant) {
        if (status != "VISIBLE") throw MarketException(Failure.ITEM_STATE_CONFLICT, "VISIBLE이 아닌 리뷰: $id ($status)")
        if (item.status != "CONFIRMED") {
            throw MarketException(Failure.ITEM_STATE_CONFLICT, "CONFIRMED가 아닌 주문 항목: ${item.id} (${item.status})")
        }
        val deadline = deadlineOf(item.confirmedAt!!)
        if (now.isAfter(deadline)) throw MarketException(Failure.DEADLINE_PASSED, "리뷰 수정 기간($deadline)이 지났다: $id")
        rating = input.rating
        content = input.content
        updatedAt = now
    }

    /**
     * rating-recalc의 분쟁 반영(§4.5의 1·2). 1 VISIBLE이고 항목이 DISPUTED·REFUNDED_AFTER_CONFIRM → HIDDEN·DISPUTE.
     * 2 HIDDEN·DISPUTE이고 항목이 CONFIRMED(기각) → VISIBLE. ADMIN 숨김과 REFUNDED_AFTER_CONFIRM은 풀지 않는다. 반환 = 바꿨는가.
     */
    fun followDispute(itemStatus: String, now: Instant): Boolean {
        if (status == "VISIBLE" && (itemStatus == "DISPUTED" || itemStatus == "REFUNDED_AFTER_CONFIRM")) {
            status = "HIDDEN"
            hiddenReason = "DISPUTE"
            updatedAt = now
            return true
        }
        if (status == "HIDDEN" && hiddenReason == "DISPUTE" && itemStatus == "CONFIRMED") {
            status = "VISIBLE"
            hiddenReason = null
            updatedAt = now
            return true
        }
        return false
    }

    companion object {
        private val WRITE_WITHIN: Duration = Duration.ofDays(30)

        /** 작성·수정 기한(§4.1·§20.2): confirmed_at + 30일 — 비교와 표시에만 쓴다(저장하지 않는다). */
        fun deadlineOf(confirmedAt: Instant): Instant = confirmedAt.plus(WRITE_WITHIN)

        /** 쓸 수 있는 항목(§20.2.2)의 확정 시각 하한: 현재 ≤ confirmed_at + 30일 ⇔ confirmed_at ≥ 현재 − 30일. */
        fun writableCutoff(now: Instant): Instant = now.minus(WRITE_WITHIN)

        /**
         * 작성(§4.1): 항목이 CONFIRMED이고 리뷰가 아직 없고(어떤 상태든) 현재 ≤ confirmed_at + 30일. 순서는 상태 → 중복 → 기한.
         * 주문 행을 잠근 뒤의 항목·리뷰 수로 부른다. sellerId = 체결 판매자.
         */
        fun write(item: OrderItem, existingReviews: () -> Long, buyerId: Long, input: ReviewInput, now: Instant, sellerId: () -> Long): Review {
            if (item.status != "CONFIRMED") {
                throw MarketException(Failure.ITEM_STATE_CONFLICT, "CONFIRMED가 아닌 주문 항목: ${item.id} (${item.status})")
            }
            if (existingReviews() > 0) throw MarketException(Failure.ITEM_STATE_CONFLICT, "리뷰가 이미 있는 주문 항목: ${item.id}")
            val deadline = item.confirmedAt!!.plus(WRITE_WITHIN)
            if (now.isAfter(deadline)) throw MarketException(Failure.DEADLINE_PASSED, "리뷰 작성 기간($deadline)이 지났다: ${item.id}")
            return Review(
                orderItemId = item.id!!,
                buyerId = buyerId,
                sellerId = sellerId(),
                rating = input.rating,
                content = input.content,
                status = "VISIBLE",
                createdAt = now,
                updatedAt = now,
            )
        }
    }
}

/** 판매자 평점 캐시(§4.5의 3): 마지막 rating-recalc가 센 VISIBLE 리뷰 수·rating 합. */
@Entity
@Table(name = "seller_ratings")
class SellerRating(
    @Id
    var sellerId: Long,
    reviewCount: Int,
    ratingSum: Int,
    recalculatedAt: Instant,
) {
    var reviewCount: Int = reviewCount
        private set

    var ratingSum: Int = ratingSum
        private set

    var recalculatedAt: Instant = recalculatedAt
        private set

    /** 값이 다를 때만 쓴다. 반환 = 썼는가. */
    fun update(count: Int, sum: Int, now: Instant): Boolean {
        if (reviewCount == count && ratingSum == sum) return false
        reviewCount = count
        ratingSum = sum
        recalculatedAt = now
        return true
    }

    companion object {
        /** 행이 없을 때: VISIBLE이 1건 이상이면 새 행, 0건이면 쓰지 않는다(조회는 행 없음 = 0·0). */
        fun firstOf(sellerId: Long, count: Int, sum: Int, now: Instant): SellerRating? =
            if (count > 0) SellerRating(sellerId = sellerId, reviewCount = count, ratingSum = sum, recalculatedAt = now) else null
    }
}

/** 판매자 리뷰 조회(§4.4): 평점(행이 없으면 0·0)과 지금의 VISIBLE 리뷰. averageX100 = floor(합 × 100 / 수), 0건이면 null. */
class SellerReviews(val sellerId: Long, rating: SellerRating?, val reviews: List<Review>) {
    val reviewCount: Long = rating?.reviewCount?.toLong() ?: 0L
    val ratingSum: Long = rating?.ratingSum?.toLong() ?: 0L
    val averageX100: Long? = if (reviewCount == 0L) null else Math.floorDiv(ratingSum * 100, reviewCount)
}

/** 쓸 수 있는 항목(§20.2.2): sellerId = 체결 판매자, reviewDeadline = confirmed_at + 30일(계산 값). */
class ReviewableItem(val orderItemId: Long, val sellerId: Long, val confirmedAt: Instant) {
    val reviewDeadline: Instant = Review.deadlineOf(confirmedAt)
}

/** 내 리뷰(§20.2.3): reviewDeadline = 그 항목의 confirmed_at + 30일(수정 기한, 계산 값). */
class MyReview(val review: Review, confirmedAt: Instant) {
    val reviewDeadline: Instant = Review.deadlineOf(confirmedAt)
}

interface ReviewRepository {
    fun add(review: Review)

    fun find(id: Long): Review?

    /** 리뷰 행 FOR UPDATE(§2.3). */
    fun lock(id: Long)

    fun reload(review: Review)

    /** 그 항목의 리뷰 수(상태 무관). */
    fun countOfItem(orderItemId: Long): Long

    /**
     * §19의 9-2: INSERT의 FK 검사(구매자 → 판매자 순)보다 먼저 두 사용자 행을 id 오름차순으로 KEY SHARE 잠근다
     * (id 순 FOR UPDATE를 쓰는 판매자 귀책과 교착하지 않게).
     */
    fun keyShareParties(buyerId: Long, sellerId: Long)

    /** 그 판매자의 리뷰 행을 id 순으로 FOR UPDATE 하고 잠근 id를 돌려준다(§4.5). */
    fun lockOfSeller(sellerId: Long): Set<Long>

    /** 그 판매자의 리뷰 전부, id 순. */
    fun listOfSeller(sellerId: Long): List<Review>

    /** 그 판매자의 VISIBLE 리뷰, id 순. */
    fun visibleOfSeller(sellerId: Long): List<Review>

    /** §20.2.2: 그 구매자의 CONFIRMED 항목 중 confirmed_at ≥ cutoff이고 리뷰 행이 없는 것(상태 무관), id 순. */
    fun reviewableItemsOf(buyerId: Long, cutoff: Instant): List<OrderItem>

    /** 그 구매자가 쓴 리뷰 전부(세 상태), id 순. */
    fun listOfBuyer(buyerId: Long): List<Review>
}

interface SellerRatingRepository {
    fun find(sellerId: Long): SellerRating?

    fun add(rating: SellerRating)
}
