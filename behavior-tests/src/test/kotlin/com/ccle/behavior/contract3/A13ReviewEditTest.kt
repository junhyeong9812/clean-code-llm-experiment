package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.HOUR
import com.ccle.behavior.contract2.Line
import com.ccle.behavior.contract2.Purchase
import com.ccle.behavior.contract2.S2
import com.ccle.behavior.contract2.SEC
import com.ccle.behavior.contract2.days
import com.ccle.behavior.support.Contract3Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db2
import com.ccle.behavior.support.Db3
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.Resp
import com.ccle.behavior.support.SlowTrigger
import com.ccle.behavior.support.longAt
import com.ccle.behavior.support.textAt
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-0007 §20.2 A13: 리뷰 수정 `PATCH /reviews/{id}`(둘 다 필수, 401 → 400 → 404 → 403 → 주문 FU → 리뷰 FU → 409 리뷰 상태 →
 * 항목 상태 → 기한, 같은 값도 200, 동시 수정 둘 다 200) · 쓸 수 있는 항목 `GET /me/reviewable-items` · 내 리뷰 `GET /me/reviews`.
 * 확정은 배송 완료 직후 구매자 확정으로 만든다(§18·§20.12 — T1에 묶이지 않게). 판매 제한·입찰 조합은 쓰지 않는다(C2 무관).
 */
@Contract3Test
class A13ReviewEditTest {

    /** 구매자 buyer가 판매자 seller의 항목 1개를 사서 확정까지. */
    private fun confirmedBy(buyer: Long, seller: Long = Db.createUser()): Purchase {
        val p = S2.purchase(listOf(Line(100_000, null, seller)), buyer)
        S2.toConfirmed(p.item, seller, buyer)
        return p
    }

    private fun reviewOf(p: Purchase, rating: Int = 3, content: String = "처음"): Long =
        SA.writeReview(p.item, p.buyer, rating, content).assertStatus(201).long("reviewId")

    // ---- §20.2.1 수정 ----

    @Test
    fun `§20_2_1 수정 - 200 리뷰 모양, rating·content = 받은 값, updated_at = 현재, created_at·status·hidden_reason·당사자 그대로, 항목·감사 무변경`() {
        val seller = Db.createUser()
        val p = SA.confirmedSale(seller, 100_000)
        val id = reviewOf(p, 3, "처음")
        val before = SA.review(id)
        val audit = SDA.maxAuditId()
        val now = Flow.advance(HOUR)

        val r = SDA.patchReview(id, p.buyer, 5, "  고침  ").assertStatus(200)
        val row = SA.review(id)
        SA.assertReviewBody(r.json, row)
        assertEquals(5L, row.long("rating"))
        assertEquals("  고침  ", row.text("content"), "저장은 받은 그대로(§2.1)")
        assertEquals(now, row.instant("updated_at"))
        assertEquals(before.instant("created_at"), row.instant("created_at"))
        assertEquals("VISIBLE", row.text("status"))
        assertNull(row.textOrNull("hidden_reason"))
        assertEquals(p.item, row.long("order_item_id"))
        assertEquals(p.buyer, row.long("buyer_id"))
        assertEquals(seller, row.long("seller_id"))
        assertEquals(1, SA.reviewsOfItem(p.item).size, "행을 늘리지 않는다")
        assertEquals("CONFIRMED", Db2.item(p.item).status, "기존 테이블은 바꾸지 않는다(§20.1)")
        SDA.assertNoAuditSince(audit)
    }

    @Test
    fun `§20_2_1 같은 값 수정도 200이고 updated_at을 현재로 쓴다(멱등 처리 없음)`() {
        val p = SA.confirmedSale(Db.createUser(), 100_000)
        val id = reviewOf(p, 4, "같음")
        val now = Flow.advance(HOUR)
        SDA.patchReview(id, p.buyer, 4, "같음").assertStatus(200)
        val row = SA.review(id)
        assertEquals(4L, row.long("rating"))
        assertEquals("같음", row.text("content"))
        assertEquals(now, row.instant("updated_at"))
    }

    @Test
    fun `§20_2_1·§19의 1·2 입력 - rating·content 둘 다 필수, rating 1~5 정수, content 앞뒤 공백 뺀 1~1,000자, 아니면 400(행 무변경)`() {
        val p = SA.confirmedSale(Db.createUser(), 100_000)
        val id = reviewOf(p, 3, "처음")
        Flow.advance(HOUR)
        val before = SA.review(id).toString()
        for (body in listOf(
            """{"rating":0,"content":"a"}""",
            """{"rating":6,"content":"a"}""",
            """{"rating":3.5,"content":"a"}""",
            """{"rating":5.0,"content":"a"}""",
            """{"rating":"5","content":"a"}""",
            """{"rating":null,"content":"a"}""",
            """{"content":"a"}""",
            """{"rating":5}""",
            """{"rating":5,"content":null}""",
            """{"rating":5,"content":5}""",
            """{"rating":5,"content":""}""",
            """{"rating":5,"content":"   "}""",
            """{}""",
            """not-json""",
        )) {
            SDA.patchReviewRaw(id, p.buyer, body).assertError(400, "INVALID_REQUEST")
        }
        SDA.patchReview(id, p.buyer, 5, "😀".repeat(1_001)).assertError(400, "INVALID_REQUEST")
        SDA.patchReview(id, p.buyer, 5, "a" + " ".repeat(999) + "a").assertError(400, "INVALID_REQUEST")
        assertEquals(before, SA.review(id).toString(), "400은 아무것도 바꾸지 않는다")

        // 경계 안: rating 1·5, content 1자·1,000자(코드 포인트, 앞뒤 공백 제외)
        SDA.patchReview(id, p.buyer, 1, " x ").assertStatus(200)
        assertEquals(1L, SA.review(id).long("rating"))
        val max = " " + "😀".repeat(1_000) + "  "
        SDA.patchReview(id, p.buyer, 5, max).assertStatus(200)
        assertEquals(5L, SA.review(id).long("rating"))
        assertEquals(max, SA.review(id).text("content"))
    }

    @Test
    fun `§20_2_1 401·404·403 - 헤더 없음·숫자 아님·없는 사용자 401, 없는 리뷰 404, 판매자·남은 403 NOT_OWNER(행 무변경)`() {
        val seller = Db.createUser()
        val p = SA.confirmedSale(seller, 100_000)
        val id = reviewOf(p)
        Flow.advance(HOUR)
        val before = SA.review(id).toString()
        SDA.patchReview(id, null, 5, "a").assertError(401, "UNKNOWN_USER")
        SDA.patchReview(id, "abc", 5, "a").assertError(401, "UNKNOWN_USER")
        SDA.patchReview(id, Db.missingId("users"), 5, "a").assertError(401, "UNKNOWN_USER")
        SDA.patchReview(Db.missingId("reviews"), p.buyer, 5, "a").assertError(404, "NOT_FOUND")
        SDA.patchReview(id, seller, 5, "a").assertError(403, "NOT_OWNER")
        SDA.patchReview(id, Db.createUser(), 5, "a").assertError(403, "NOT_OWNER")
        assertEquals(before, SA.review(id).toString())
    }

    @Test
    fun `§20_2_1 판정 순서 - 401 → 400 → 404 → 403 → 409`() {
        val p = SA.confirmedSale(Db.createUser(), 100_000)
        val id = reviewOf(p)
        SA.hideReview(id).assertStatus(200)                                    // 본인이면 409
        val missing = Db.missingId("reviews")

        SDA.patchReview(missing, null, 0, "").assertError(401, "UNKNOWN_USER")        // 401이 400보다
        SDA.patchReview(missing, p.buyer, 0, "a").assertError(400, "INVALID_REQUEST") // 400이 404보다
        SDA.patchReview(id, p.buyer, 9, "a").assertError(400, "INVALID_REQUEST")      // 400이 409보다
        SDA.patchReview(id, Db.createUser(), 9, "a").assertError(400, "INVALID_REQUEST") // 400이 403보다
        SDA.patchReview(missing, Db.createUser(), 5, "a").assertError(404, "NOT_FOUND")
        SDA.patchReview(id, Db.createUser(), 5, "a").assertError(403, "NOT_OWNER")    // 403이 409보다
        SDA.patchReview(id, p.buyer, 5, "a").assertError(409, "ITEM_STATE_CONFLICT")
    }

    @Test
    fun `§20_2_1의 1 리뷰가 VISIBLE이 아니면 409 ITEM_STATE_CONFLICT - HIDDEN(ADMIN)·DELETED, 행 무변경`() {
        val seller = Db.createUser()
        val a = SA.confirmedSale(seller, 100_000)
        val b = SA.confirmedSale(seller, 100_000)
        val hidden = reviewOf(a)
        val deleted = reviewOf(b)
        SA.hideReview(hidden).assertStatus(200)
        SA.deleteReview(deleted, b.buyer).assertStatus(200)
        Flow.advance(HOUR)
        for ((id, buyer) in listOf(hidden to a.buyer, deleted to b.buyer)) {
            val before = SA.review(id).toString()
            SDA.patchReview(id, buyer, 5, "고침").assertError(409, "ITEM_STATE_CONFLICT")
            assertEquals(before, SA.review(id).toString(), "409는 아무것도 바꾸지 않는다")
        }
        // 403은 409보다 먼저(DELETED여도 남이면 403)
        SDA.patchReview(deleted, seller, 5, "고침").assertError(403, "NOT_OWNER")
    }

    @Test
    fun `§20_2_1의 2 항목이 CONFIRMED가 아니면 409 - rating-recalc 전이라 VISIBLE인 DISPUTED·REFUNDED_AFTER_CONFIRM 항목 리뷰, 기각(다시 CONFIRMED)은 200`() {
        val seller = Db.createUser()
        val disputed = SA.confirmedSale(seller, 100_000)
        val refunded = SA.confirmedSale(seller, 100_000)
        val rejected = SA.confirmedSale(seller, 100_000)
        val rd = reviewOf(disputed)
        val rr = reviewOf(refunded)
        val rj = reviewOf(rejected)
        S2.openDispute(disputed.item, disputed.buyer)
        S2.resolved(S2.openDispute(refunded.item, refunded.buyer), true)
        S2.resolved(S2.openDispute(rejected.item, rejected.buyer), false)
        assertEquals("DISPUTED", Db2.item(disputed.item).status)
        assertEquals("REFUNDED_AFTER_CONFIRM", Db2.item(refunded.item).status)
        assertEquals("CONFIRMED", Db2.item(rejected.item).status)
        for (id in listOf(rd, rr, rj)) assertEquals("VISIBLE", SA.review(id).text("status"), "준비: rating-recalc 전이라 VISIBLE")
        val now = Flow.advance(HOUR)

        for ((id, buyer) in listOf(rd to disputed.buyer, rr to refunded.buyer)) {
            val before = SA.review(id).toString()
            SDA.patchReview(id, buyer, 1, "고침").assertError(409, "ITEM_STATE_CONFLICT")
            assertEquals(before, SA.review(id).toString())
        }
        SDA.patchReview(rj, rejected.buyer, 1, "고침").assertStatus(200)
        assertEquals(1L, SA.review(rj).long("rating"))
        assertEquals(now, SA.review(rj).instant("updated_at"))
    }

    @Test
    fun `§20_2_1의 3 기한 - confirmed_at + 30일 정각은 200, 1초 뒤는 409 DEADLINE_PASSED, 리뷰 상태·항목 상태 위반이 기한보다 먼저`() {
        val seller = Db.createUser()
        val edge = SA.confirmedSale(seller, 100_000)
        val late = SA.confirmedSale(seller, 100_000)
        val hiddenP = SA.confirmedSale(seller, 100_000)
        val disputedP = SA.confirmedSale(seller, 100_000)
        val confirmedAt = Db2.item(edge.item).confirmedAt!!
        for (p in listOf(late, hiddenP, disputedP)) assertEquals(confirmedAt, Db2.item(p.item).confirmedAt, "준비: 같은 확정 시각")
        val re = reviewOf(edge)
        val rl = reviewOf(late)
        val rh = reviewOf(hiddenP)
        val rd = reviewOf(disputedP)
        SA.hideReview(rh).assertStatus(200)
        S2.openDispute(disputedP.item, disputedP.buyer)

        val atEdge = Flow.moveTo(confirmedAt.plus(days(30)))
        SDA.patchReview(re, edge.buyer, 2, "정각").assertStatus(200)
        assertEquals(atEdge, SA.review(re).instant("updated_at"))

        Flow.moveTo(atEdge.plus(SEC))
        val before = SA.review(rl).toString()
        SDA.patchReview(rl, late.buyer, 2, "늦음").assertError(409, "DEADLINE_PASSED")
        assertEquals(before, SA.review(rl).toString())
        SDA.patchReview(rh, hiddenP.buyer, 2, "a").assertError(409, "ITEM_STATE_CONFLICT")
        SDA.patchReview(rd, disputedP.buyer, 2, "a").assertError(409, "ITEM_STATE_CONFLICT")
        SDA.patchReview(re, edge.buyer, 2, "또").assertError(409, "DEADLINE_PASSED")
    }

    @Test
    fun `§20_2_1 평점은 바로 고치지 않는다 - seller_ratings 그대로, 다음 rating-recalc가 새 rating으로 UPDATE`() {
        val seller = Db.createUser()
        val p = SA.confirmedSale(seller, 100_000)
        val id = reviewOf(p, 4, "처음")
        val t1 = Flow.advance(HOUR)
        S2.runBatch("rating-recalc")
        val r1 = Db3.rowBy("seller_ratings", "seller_id", seller) ?: kotlin.test.fail("준비: 평점 행")
        assertEquals(4L, r1.long("rating_sum"))

        Flow.advance(HOUR)
        SDA.patchReview(id, p.buyer, 2, "고침").assertStatus(200)
        val r2 = Db3.rowBy("seller_ratings", "seller_id", seller)!!
        assertEquals(1L, r2.long("review_count"))
        assertEquals(4L, r2.long("rating_sum"), "수정은 평점 캐시를 쓰지 않는다")
        assertEquals(t1, r2.instant("recalculated_at"))
        val q = SA.sellerReviews(seller).assertStatus(200)
        assertEquals(4L, q.long("ratingSum"), "조회 평점은 마지막 배치 결과")
        assertEquals(2L, q.json.get("reviews").single().longAt("rating"), "목록은 지금의 행")

        val t3 = Flow.advance(HOUR)
        S2.runBatch("rating-recalc")
        val r3 = Db3.rowBy("seller_ratings", "seller_id", seller)!!
        assertEquals(1L, r3.long("review_count"))
        assertEquals(2L, r3.long("rating_sum"))
        assertEquals(t3, r3.instant("recalculated_at"))
    }

    @Test
    fun `§20_2_1·§2_3 동시 수정 - 같은 리뷰에 값이 다른 두 수정이면 둘 다 200, 최종 값 ∈ 두 요청의 값, 행 1개`() {
        val p = SA.confirmedSale(Db.createUser(), 100_000)
        val id = reviewOf(p, 3, "처음")
        Flow.advance(HOUR)
        val values = listOf(2 to "가", 4 to "나")
        val rs = SlowTrigger.on("reviews", "UPDATE", condition = "OLD.id = $id") {
            SA.concurrently(2) { i -> SDA.patchReview(id, p.buyer, values[i].first, values[i].second) }
        }
        rs.forEachIndexed { i, r ->
            r.assertStatus(200)
            assertEquals(values[i].first.toLong(), r.long("rating"), "각 응답은 자기 값: $r")
            assertEquals(values[i].second, r.text("content"), "각 응답은 자기 값: $r")
        }
        val row = SA.reviewsOfItem(p.item).single()
        assertTrue(values.contains(row.long("rating").toInt() to row.text("content")), "최종 값 ∈ 두 요청의 값(짝 그대로): $row")
        assertEquals("VISIBLE", row.text("status"))
    }

    @Test
    fun `§2_3 수정과 삭제가 겹치면 - 삭제는 200, 수정은 먼저면 200·나중이면 409(앞 전환이 남긴 DELETED), 최종 DELETED`() {
        val p = SA.confirmedSale(Db.createUser(), 100_000)
        val id = reviewOf(p, 3, "처음")
        Flow.advance(HOUR)
        val rs = SlowTrigger.on("reviews", "UPDATE", condition = "OLD.id = $id") {
            SA.concurrently(2) { i -> if (i == 0) SA.deleteReview(id, p.buyer) else SDA.patchReview(id, p.buyer, 5, "고침") }
        }
        val (del, patch) = rs
        del.assertStatus(200)
        val row = SA.review(id)
        assertEquals("DELETED", row.text("status"))
        assertNull(row.textOrNull("hidden_reason"))
        if (patch.status == 200) {
            assertEquals(5L, row.long("rating"), "수정이 먼저였다")
            assertEquals("고침", row.text("content"))
        } else {
            patch.assertError(409, "ITEM_STATE_CONFLICT")
            assertEquals(3L, row.long("rating"))
            assertEquals("처음", row.text("content"))
        }
    }

    /**
     * §20.12·§20.8: 수정이 주문 FU → 리뷰 FU를 쥔 채 UPDATE 직전에 멈춘 사이 같은 항목에 분쟁 제기(주문 → 항목 FU)가 오면,
     * 분쟁은 주문 행에서 수정을 기다린다 — 교착 없이 수정 200(그때 항목은 CONFIRMED)·분쟁 201.
     * (수정이 주문 행을 잠그지 않으면 분쟁이 막히지 않아 경합 미관측으로 실패한다.)
     */
    @Test
    fun `§20_12·§20_8 수정과 같은 항목의 분쟁 제기가 겹쳐도 교착 없이 수정 200·분쟁 201`() {
        val p = SA.confirmedSale(Db.createUser(), 100_000)
        val id = reviewOf(p, 3, "처음")
        Flow.advance(HOUR)
        val rs = SlowTrigger.on("reviews", "UPDATE", condition = "OLD.id = $id") {
            val pool = Executors.newSingleThreadExecutor()
            try {
                val patch = pool.submit<Resp> { SDA.patchReview(id, p.buyer, 1, "분쟁 전 수정") }
                SDA.awaitTriggerWait()
                val dispute = S2.dispute(p.item, p.buyer)
                listOf(patch.get(30, TimeUnit.SECONDS), dispute)
            } finally {
                pool.shutdownNow()
            }
        }
        rs[0].assertStatus(200)
        rs[1].assertStatus(201)
        assertEquals("DISPUTED", Db2.item(p.item).status)
        assertEquals(1L, SA.review(id).long("rating"))
        // 분쟁 뒤에는 2에서 막힌다
        SDA.patchReview(id, p.buyer, 2, "a").assertError(409, "ITEM_STATE_CONFLICT")
    }

    /**
     * §20.2.1·§20.8(역방향): 분쟁 제기가 주문 → 항목 FU를 쥔 채 항목 UPDATE 직전에 멈춘 사이 같은 항목의 리뷰 수정이 오면,
     * 수정은 주문 행에서 기다렸다가 잠근 뒤 항목을 다시 읽어 DISPUTED를 본다 — 분쟁 201·수정 409, 리뷰 행 그대로.
     * (수정이 주문을 잠그기 전에 읽은 항목 상태로 판정하면 200이 나와 실패한다.)
     */
    @Test
    fun `§20_2_1·§20_8 분쟁 제기가 먼저 주문을 쥐고 멈춘 사이 수정이 오면 분쟁 201·수정 409(역방향)`() {
        val p = SA.confirmedSale(Db.createUser(), 100_000)
        val id = reviewOf(p, 3, "처음")
        Flow.advance(HOUR)
        val rs = SlowTrigger.on("order_items", "UPDATE", condition = "OLD.id = ${p.item}") {
            val pool = Executors.newSingleThreadExecutor()
            try {
                val dispute = pool.submit<Resp> { S2.dispute(p.item, p.buyer) }
                SDA.awaitTriggerWait()
                val patch = SDA.patchReview(id, p.buyer, 1, "분쟁 중 수정")
                listOf(dispute.get(30, TimeUnit.SECONDS), patch)
            } finally {
                pool.shutdownNow()
            }
        }
        rs[0].assertStatus(201)
        rs[1].assertError(409, "ITEM_STATE_CONFLICT")
        assertEquals("DISPUTED", Db2.item(p.item).status)
        assertEquals(3L, SA.review(id).long("rating"))
        assertEquals("처음", SA.review(id).text("content"))
    }

    // ---- §20.2.2 쓸 수 있는 항목 ----

    @Test
    fun `§20_2_2 쓸 수 있는 항목 - 내가 구매자·CONFIRMED·리뷰 없음(상태 무관)·기한 안만, orderItemId 오름차순, 모양, 판매자 쪽·남의 항목 제외`() {
        val me = Db.createUser()
        val sellerA = Db.createUser()
        val open1 = confirmedBy(me, sellerA)
        val open2 = confirmedBy(me)
        val visible = confirmedBy(me)
        val hidden = confirmedBy(me)
        val deleted = confirmedBy(me)
        val disputed = confirmedBy(me)
        val refunded = confirmedBy(me)
        val rejected = confirmedBy(me)
        val delivered = S2.purchase(listOf(Line(100_000)), me).also { S2.toDelivered(it.item, it.seller) }
        val paid = S2.purchase(listOf(Line(100_000)), me)
        // 내가 판매자인 확정 항목, 남이 구매자인 확정 항목
        val asSeller = SA.confirmedSale(me, 100_000)
        val others = SA.confirmedSale(Db.createUser(), 100_000)

        reviewOf(visible)
        SA.hideReview(reviewOf(hidden)).assertStatus(200)
        SA.deleteReview(reviewOf(deleted), me).assertStatus(200)
        S2.openDispute(disputed.item, me)
        S2.resolved(S2.openDispute(refunded.item, me), true)
        S2.resolved(S2.openDispute(rejected.item, me), false)

        val r = SDA.reviewable(me).assertStatus(200)
        assertTrue(r.json.isArray, "배열: $r")
        val expected = listOf(open1, open2, rejected).sortedBy { it.item }
        assertEquals(expected.map { it.item }, r.json.map { it.longAt("orderItemId") }, "대상만, orderItemId 오름차순: $r")
        r.json.forEachIndexed { i, j ->
            val p = expected[i]
            SDA.assertReviewableBody(j, p.item, p.seller, Db2.item(p.item).confirmedAt!!)
        }
        assertEquals(sellerA, r.json.first { it.longAt("orderItemId") == open1.item }.longAt("sellerId"))
        for (x in listOf(delivered, paid, asSeller, others)) assertTrue(r.json.none { it.longAt("orderItemId") == x.item })

        // 같은 집합: 목록의 항목은 작성이 201, 쓰고 나면 빠진다
        SA.writeReview(open1.item, me, 5, "a").assertStatus(201)
        assertEquals(listOf(open2, rejected).sortedBy { it.item }.map { it.item },
            SDA.reviewable(me).assertStatus(200).json.map { it.longAt("orderItemId") })
        assertEquals(0L, Db3.count("reviews", "order_item_id = ?", open2.item), "조회는 쓰지 않는다")
    }

    @Test
    fun `§20_2_2 기한 - confirmed_at + 30일 정각은 포함, 1초 뒤는 제외, reviewDeadline은 계산 값`() {
        val me = Db.createUser()
        val p = confirmedBy(me)
        val confirmedAt = Db2.item(p.item).confirmedAt!!
        Flow.moveTo(confirmedAt.plus(days(30)))
        val r = SDA.reviewable(me).assertStatus(200)
        assertEquals(listOf(p.item), r.json.map { it.longAt("orderItemId") })
        SDA.assertReviewableBody(r.json[0], p.item, p.seller, confirmedAt)

        Flow.moveTo(confirmedAt.plus(days(30)).plus(SEC))
        val after = SDA.reviewable(me).assertStatus(200)
        assertTrue(after.json.isArray && after.json.size() == 0, "기한 지남 → 빈 배열: $after")
    }

    @Test
    fun `§20_2_2 빈 목록·401 - 항목 없는 사용자는 빈 배열, 헤더 없음·숫자 아님·없는 사용자 401`() {
        val r = SDA.reviewable(Db.createUser()).assertStatus(200)
        assertTrue(r.json.isArray && r.json.size() == 0, "빈 배열: $r")
        SDA.reviewable(null).assertError(401, "UNKNOWN_USER")
        SDA.reviewable("abc").assertError(401, "UNKNOWN_USER")
        SDA.reviewable(Db.missingId("users")).assertError(401, "UNKNOWN_USER")
    }

    // ---- §20.2.3 내 리뷰 ----

    @Test
    fun `§20_2_3 내 리뷰 - 내가 쓴 리뷰 세 상태 전부 reviewId 오름차순, 모양 = 리뷰 모양 + hiddenReason·updatedAt·reviewDeadline, 남의 리뷰 제외`() {
        val me = Db.createUser()
        val pv = confirmedBy(me)
        val ph = confirmedBy(me)
        val pd = confirmedBy(me)
        val rv = reviewOf(pv, 5, "보임")
        val rh = reviewOf(ph, 2, "숨김")
        val rd = reviewOf(pd, 1, "지움")
        SA.hideReview(rh).assertStatus(200)
        Flow.advance(HOUR)
        SA.deleteReview(rd, me).assertStatus(200)
        Flow.advance(HOUR)
        SDA.patchReview(rv, me, 4, "고침").assertStatus(200)
        val other = SA.confirmedSale(Db.createUser(), 100_000)
        reviewOf(other)
        // 내가 판매자인 항목의 리뷰(남이 씀)도 내 리뷰가 아니다
        val asSeller = SA.confirmedSale(me, 100_000)
        reviewOf(asSeller)

        val r = SDA.myReviews(me).assertStatus(200)
        assertEquals(listOf(rv, rh, rd), r.json.map { it.longAt("reviewId") }, "세 상태 전부, reviewId 오름차순: $r")
        val items = listOf(pv, ph, pd)
        r.json.forEachIndexed { i, j ->
            SDA.assertMyReviewBody(j, SA.review(j.longAt("reviewId")), Db2.item(items[i].item).confirmedAt!!)
        }
        assertEquals(listOf("VISIBLE", "HIDDEN", "DELETED"), r.json.map { it.textAt("status") })
        assertEquals("ADMIN", r.json[1].textAt("hiddenReason"))
        SA.assertNullField(r.json[0], "hiddenReason")
        SA.assertNullField(r.json[2], "hiddenReason")
        assertEquals(4L, r.json[0].longAt("rating"), "수정한 값")
    }

    @Test
    fun `§20_2_3 빈 목록·401`() {
        val r = SDA.myReviews(Db.createUser()).assertStatus(200)
        assertTrue(r.json.isArray && r.json.size() == 0, "빈 배열: $r")
        SDA.myReviews(null).assertError(401, "UNKNOWN_USER")
        SDA.myReviews("abc").assertError(401, "UNKNOWN_USER")
        SDA.myReviews(Db.missingId("users")).assertError(401, "UNKNOWN_USER")
    }
}
