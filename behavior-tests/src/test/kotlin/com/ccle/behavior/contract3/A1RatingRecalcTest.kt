package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.HOUR
import com.ccle.behavior.contract2.S2
import com.ccle.behavior.support.Contract3Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db3
import com.ccle.behavior.support.FailTrigger
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.Row3
import com.ccle.behavior.support.failed
import com.ccle.behavior.support.longAt
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-0007 §4.5 rating-recalc(분쟁 숨김·분쟁 해제·평점 캐시 쓰기 규칙·건별 격리 §2.6) + §4.4 조회의 평점 필드.
 * 배치는 시드·다른 테스트 판매자도 처리하므로 processed 정확값은 보지 않고 새 판매자의 행만 단언한다.
 */
@Contract3Test
class A1RatingRecalcTest {

    private fun rating(seller: Long): Row3? = Db3.rowBy("seller_ratings", "seller_id", seller)

    private fun assertRating(seller: Long, count: Long, sum: Long, at: Instant) {
        val r = rating(seller) ?: kotlin.test.fail("seller_ratings 행이 있어야 한다: $seller")
        assertEquals(count, r.long("review_count"), "review_count: $r")
        assertEquals(sum, r.long("rating_sum"), "rating_sum: $r")
        assertEquals(at, r.instant("recalculated_at"), "recalculated_at: $r")
    }

    /** 판매자 seller의 확정 항목에 rating 리뷰 1건. 반환: (구매, reviewId). */
    private fun reviewed(seller: Long, rating: Int): Pair<com.ccle.behavior.contract2.Purchase, Long> {
        val p = SA.confirmedSale(seller, 100_000)
        return p to SA.writeReview(p.item, p.buyer, rating, "r$rating").assertStatus(201).long("reviewId")
    }

    @Test
    fun `§4_5 평점 - VISIBLE 수·합 INSERT, 같으면 쓰지 않음(recalculated_at 그대로), 바뀌면 UPDATE, 조회 averageX100 = floor`() {
        val seller = Db.createUser()
        reviewed(seller, 5); reviewed(seller, 4); reviewed(seller, 4)
        assertNull(rating(seller))
        val t1 = Flow.advance(HOUR)
        S2.runBatch("rating-recalc")
        assertRating(seller, 3, 13, t1)
        val r1 = SA.sellerReviews(seller).assertStatus(200)
        assertEquals(3L, r1.long("reviewCount"))
        assertEquals(13L, r1.long("ratingSum"))
        assertEquals(433L, r1.long("averageX100"), "floor(1300 / 3)")

        Flow.advance(HOUR)
        S2.runBatch("rating-recalc")
        assertRating(seller, 3, 13, t1)                                         // 값이 같으면 쓰지 않는다

        reviewed(seller, 1)
        val r2 = SA.sellerReviews(seller).assertStatus(200)
        assertEquals(3L, r2.long("reviewCount"), "조회 평점은 마지막 배치 결과(행 상태와 시점이 다를 수 있다)")
        assertEquals(4, r2.json.get("reviews").size(), "목록은 지금의 VISIBLE")
        val t3 = Flow.advance(HOUR)
        S2.runBatch("rating-recalc")
        assertRating(seller, 4, 14, t3)
        assertEquals(350L, SA.sellerReviews(seller).assertStatus(200).long("averageX100"))
    }

    @Test
    fun `§4_5 평점 - 행이 없고 VISIBLE 0건이면 쓰지 않음, 행이 있는데 VISIBLE이 0이 되면 0·0으로 UPDATE`() {
        val none = Db.createUser()
        val (pn, rn) = reviewed(none, 5)
        SA.deleteReview(rn, pn.buyer).assertStatus(200)
        val hidden = Db.createUser()
        val (_, rh) = reviewed(hidden, 3)
        SA.hideReview(rh).assertStatus(200)
        Flow.advance(HOUR)
        S2.runBatch("rating-recalc")
        assertNull(rating(none), "리뷰는 있지만 VISIBLE 0건 → 행 없음")
        assertNull(rating(hidden))
        val q = SA.sellerReviews(none).assertStatus(200)
        assertEquals(0L, q.long("reviewCount"))
        SA.assertNullField(q.json, "averageX100")

        val drop = Db.createUser()
        val (pd, rd) = reviewed(drop, 4)
        Flow.advance(HOUR)
        S2.runBatch("rating-recalc")
        assertEquals(1L, rating(drop)!!.long("review_count"))
        SA.deleteReview(rd, pd.buyer).assertStatus(200)
        val t = Flow.advance(HOUR)
        S2.runBatch("rating-recalc")
        assertRating(drop, 0, 0, t)
        val r = SA.sellerReviews(drop).assertStatus(200)
        assertEquals(0L, r.long("reviewCount"))
        assertEquals(0L, r.long("ratingSum"))
        SA.assertNullField(r.json, "averageX100")
    }

    @Test
    fun `§4_5 분쟁 숨김·해제 - DISPUTED·REFUNDED_AFTER_CONFIRM의 VISIBLE → HIDDEN·DISPUTE, 기각되면 VISIBLE, ADMIN·DELETED·환불은 풀지 않는다`() {
        val seller = Db.createUser()
        val (pRej, rRej) = reviewed(seller, 5)      // 분쟁 → 숨김 → 기각 → 해제
        val (pAcc, rAcc) = reviewed(seller, 4)      // 분쟁 → 숨김 → 인정 → 그대로
        val (pRef, rRef) = reviewed(seller, 3)      // 배치 전 이미 인정 → 숨김
        val (pAdm, rAdm) = reviewed(seller, 2)      // 관리자 숨김 → 분쟁 → 기각: ADMIN 그대로
        val (pDel, rDel) = reviewed(seller, 1)      // 삭제 → 분쟁: DELETED 그대로
        val (_, rKeep) = reviewed(seller, 5)        // 그대로 VISIBLE

        val hiddenAt = Flow.advance(HOUR)
        SA.hideReview(rAdm).assertStatus(200)
        SA.deleteReview(rDel, pDel.buyer).assertStatus(200)
        val dRej = S2.openDispute(pRej.item, pRej.buyer)
        val dAcc = S2.openDispute(pAcc.item, pAcc.buyer)
        S2.resolved(S2.openDispute(pRef.item, pRef.buyer), true)
        val dAdm = S2.openDispute(pAdm.item, pAdm.buyer)
        S2.openDispute(pDel.item, pDel.buyer)

        val t1 = Flow.advance(HOUR)
        S2.runBatch("rating-recalc")
        for (id in listOf(rRej, rAcc, rRef)) {
            val row = SA.review(id)
            assertEquals("HIDDEN", row.text("status"), "분쟁 숨김: $row")
            assertEquals("DISPUTE", row.text("hidden_reason"))
            assertEquals(t1, row.instant("updated_at"))
        }
        SA.review(rAdm).let { assertEquals("HIDDEN", it.text("status")); assertEquals("ADMIN", it.text("hidden_reason")); assertEquals(hiddenAt, it.instant("updated_at")) }
        SA.review(rDel).let { assertEquals("DELETED", it.text("status")); assertEquals(hiddenAt, it.instant("updated_at")) }
        assertRating(seller, 1, 5, t1)
        assertEquals(listOf(rKeep), SA.sellerReviews(seller).assertStatus(200).json.get("reviews").map { it.longAt("reviewId") })
        SA.hideReview(rRej).assertError(409, "ITEM_STATE_CONFLICT")              // §4.3: VISIBLE만

        S2.resolved(dRej, false)
        S2.resolved(dAcc, true)
        S2.resolved(dAdm, false)
        val t2 = Flow.advance(HOUR)
        S2.runBatch("rating-recalc")
        SA.review(rRej).let {
            assertEquals("VISIBLE", it.text("status"), "기각(CONFIRMED) → 해제")
            assertNull(it.textOrNull("hidden_reason"))
            assertEquals(t2, it.instant("updated_at"))
        }
        for (id in listOf(rAcc, rRef)) SA.review(id).let {
            assertEquals("HIDDEN", it.text("status"), "REFUNDED_AFTER_CONFIRM은 풀지 않는다: $it")
            assertEquals("DISPUTE", it.text("hidden_reason"))
            assertEquals(t1, it.instant("updated_at"))
        }
        SA.review(rAdm).let { assertEquals("ADMIN", it.text("hidden_reason"), "ADMIN 숨김은 풀지 않는다"); assertEquals(hiddenAt, it.instant("updated_at")) }
        assertRating(seller, 2, 10, t2)
        assertEquals(listOf(rRej, rKeep), SA.sellerReviews(seller).assertStatus(200).json.get("reviews").map { it.longAt("reviewId") })
    }

    @Test
    fun `§4_5·§2_6 격리 - 판매자 A의 리뷰 갱신이 실패해도 A는 그대로(숨김·평점 모두 롤백), B는 처리, failed ≥ 1, 다시 돌리면 A 처리`() {
        val a = Db.createUser()
        val b = Db.createUser()
        assertTrue(a < b)
        val (paD, raD) = reviewed(a, 5); reviewed(a, 3)
        val (pbD, rbD) = reviewed(b, 5); reviewed(b, 3)
        S2.openDispute(paD.item, paD.buyer)
        S2.openDispute(pbD.item, pbD.buyer)

        val t1 = Flow.advance(HOUR)
        FailTrigger.on("reviews", "UPDATE", "OLD.seller_id = $a") {
            val r = S2.runBatch("rating-recalc")
            assertTrue(r.failed() >= 1, "A 실패를 failed에 센다: $r")
            assertTrue(r.long("processed") >= 1, "B는 커밋된다: $r")
        }
        assertEquals("VISIBLE", SA.review(raD).text("status"))
        assertNull(rating(a), "A의 평점도 쓰지 않았다(같은 트랜잭션)")
        assertEquals("HIDDEN", SA.review(rbD).text("status"))
        assertRating(b, 1, 3, t1)

        val t2 = Flow.advance(HOUR)
        S2.runBatch("rating-recalc")
        assertEquals("HIDDEN", SA.review(raD).text("status"))
        assertEquals(t2, SA.review(raD).instant("updated_at"))
        assertRating(a, 1, 3, t2)
        assertRating(b, 1, 3, t1)                                              // B는 다시 쓰지 않는다
    }
}
