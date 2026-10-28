package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.HOUR
import com.ccle.behavior.contract2.Line
import com.ccle.behavior.contract2.S2
import com.ccle.behavior.contract2.SEC
import com.ccle.behavior.contract2.days
import com.ccle.behavior.support.Api
import com.ccle.behavior.support.Contract3Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db2
import com.ccle.behavior.support.Db3
import com.ccle.behavior.support.FailTrigger
import com.ccle.behavior.support.SlowTrigger
import com.ccle.behavior.support.assertUnexpectedError
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.longAt
import com.ccle.behavior.support.textAt
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-0007 §4.1 리뷰 작성 · §4.2 삭제 · §4.3 관리자 숨김(+ §2.7 감사 REVIEW_HIDE) · §4.4 판매자 리뷰 조회(평점 캐시 없는 상태).
 * 리뷰를 쓸 항목은 배송 완료 직후 구매자 확정으로 만든다(§18 — confirmed_at = 확정 요청 시각).
 */
@Contract3Test
class A1ReviewTest {

    // ---- §4.1 작성 ----

    @Test
    fun `§4_1 작성 - 구매자, CONFIRMED, 201 리뷰 모양, reviews 1행 VISIBLE(seller_id = 체결 판매자, created_at = updated_at = 현재)`() {
        val seller = Db.createUser()
        val p = SA.confirmedSale(seller, 100_000)
        val now = Flow.advance(HOUR)

        val r = SA.writeReview(p.item, p.buyer, 4, "좋아요").assertStatus(201)
        val row = SA.reviewsOfItem(p.item).single()
        SA.assertReviewBody(r.json, row)
        assertEquals("VISIBLE", r.text("status"))
        assertEquals(p.item, row.long("order_item_id"))
        assertEquals(p.buyer, row.long("buyer_id"))
        assertEquals(seller, row.long("seller_id"))
        assertEquals(4L, row.long("rating"))
        assertEquals("좋아요", row.text("content"))
        assertEquals("VISIBLE", row.text("status"))
        assertNull(row.textOrNull("hidden_reason"))
        assertEquals(now, row.instant("created_at"))
        assertEquals(now, row.instant("updated_at"))
        assertEquals("CONFIRMED", Db2.item(p.item).status, "기존 테이블은 바꾸지 않는다(§2.3)")
    }

    @Test
    fun `§4_1·§19의 2 rating 경계 - 1과 5는 201, 0·6·소수·5_0·문자열 "5"·null·누락은 400 INVALID_REQUEST(행 없음)`() {
        val seller = Db.createUser()
        val ok1 = SA.confirmedSale(seller, 100_000)
        val ok5 = SA.confirmedSale(seller, 100_000)
        SA.writeReview(ok1.item, ok1.buyer, 1, "a").assertStatus(201)
        SA.writeReview(ok5.item, ok5.buyer, 5, "a").assertStatus(201)
        assertEquals(1L, SA.reviewsOfItem(ok1.item).single().long("rating"))
        assertEquals(5L, SA.reviewsOfItem(ok5.item).single().long("rating"))

        val p = SA.confirmedSale(seller, 100_000)
        for (body in listOf(
            """{"rating":0,"content":"a"}""",
            """{"rating":6,"content":"a"}""",
            """{"rating":3.5,"content":"a"}""",
            """{"rating":"abc","content":"a"}""",
            """{"rating":5.0,"content":"a"}""",
            """{"rating":"5","content":"a"}""",
            """{"rating":null,"content":"a"}""",
            """{"content":"a"}""",
            """not-json""",
        )) {
            SA.writeReviewRaw(p.item, p.buyer, body).assertError(400, "INVALID_REQUEST")
        }
        assertTrue(SA.reviewsOfItem(p.item).isEmpty())
    }

    @Test
    fun `§4_1·§2_1 content 길이 - 앞뒤 공백을 뺀 코드 포인트 1~1,000자, 저장은 받은 그대로`() {
        val seller = Db.createUser()
        // 1,000자(이모지 = 코드 포인트 1개, UTF-16 2개) + 앞뒤 공백 → 201, 받은 값 그대로 저장
        val emoji = SA.confirmedSale(seller, 100_000)
        val emojiContent = "  " + "😀".repeat(1_000) + " "
        val r = SA.writeReview(emoji.item, emoji.buyer, 5, emojiContent).assertStatus(201)
        assertEquals(emojiContent, r.text("content"))
        assertEquals(emojiContent, SA.reviewsOfItem(emoji.item).single().text("content"))

        // 중간 공백은 센다: 앞 a + 공백 998 + 끝 a = 1,000자 → 201
        val mid = SA.confirmedSale(seller, 100_000)
        SA.writeReview(mid.item, mid.buyer, 5, "a" + " ".repeat(998) + "a").assertStatus(201)

        // 1자(앞뒤 공백 제외) → 201
        val one = SA.confirmedSale(seller, 100_000)
        SA.writeReview(one.item, one.buyer, 3, " x ").assertStatus(201)
        assertEquals(" x ", SA.reviewsOfItem(one.item).single().text("content"))

        val bad = SA.confirmedSale(seller, 100_000)
        SA.writeReview(bad.item, bad.buyer, 5, "😀".repeat(1_001)).assertError(400, "INVALID_REQUEST")
        SA.writeReview(bad.item, bad.buyer, 5, "a" + " ".repeat(999) + "a").assertError(400, "INVALID_REQUEST")
        SA.writeReview(bad.item, bad.buyer, 5, "   ").assertError(400, "INVALID_REQUEST")
        SA.writeReview(bad.item, bad.buyer, 5, "").assertError(400, "INVALID_REQUEST")
        SA.writeReview(bad.item, bad.buyer, 5, null).assertError(400, "INVALID_REQUEST")
        SA.writeReviewRaw(bad.item, bad.buyer, """{"rating":5}""").assertError(400, "INVALID_REQUEST")
        assertTrue(SA.reviewsOfItem(bad.item).isEmpty())
    }

    @Test
    fun `§4_1 401 - 헤더 없음·숫자 아님·없는 사용자`() {
        val p = SA.confirmedSale(Db.createUser(), 100_000)
        SA.writeReview(p.item, null, 5, "a").assertError(401, "UNKNOWN_USER")
        SA.writeReview(p.item, "abc", 5, "a").assertError(401, "UNKNOWN_USER")
        SA.writeReview(p.item, Db.missingId("users"), 5, "a").assertError(401, "UNKNOWN_USER")
        assertTrue(SA.reviewsOfItem(p.item).isEmpty())
    }

    @Test
    fun `§4_1 404·403 - 없는 항목 404, 판매자·남은 403 NOT_OWNER`() {
        val seller = Db.createUser()
        val p = SA.confirmedSale(seller, 100_000)
        SA.writeReview(Db.missingId("order_items"), p.buyer, 5, "a").assertError(404, "NOT_FOUND")
        SA.writeReview(p.item, seller, 5, "a").assertError(403, "NOT_OWNER")
        SA.writeReview(p.item, Db.createUser(), 5, "a").assertError(403, "NOT_OWNER")
        assertTrue(SA.reviewsOfItem(p.item).isEmpty())
    }

    @Test
    fun `§4_1 판정 순서 - 401 → 400 → 404 → 403 → 409`() {
        val seller = Db.createUser()
        val delivered = S2.purchase(listOf(Line(100_000, null, seller)))
        S2.toDelivered(delivered.item, seller)                      // CONFIRMED 아님 → 본인이면 409
        val missing = Db.missingId("order_items")

        SA.writeReview(missing, null, 0, "").assertError(401, "UNKNOWN_USER")                 // 401이 400보다
        SA.writeReview(missing, delivered.buyer, 0, "a").assertError(400, "INVALID_REQUEST")   // 400이 404보다
        SA.writeReview(delivered.item, delivered.buyer, 9, "a").assertError(400, "INVALID_REQUEST") // 400이 409보다
        SA.writeReview(delivered.item, Db.createUser(), 5, "a").assertError(403, "NOT_OWNER")  // 403이 409보다
        SA.writeReview(delivered.item, delivered.buyer, 5, "a").assertError(409, "ITEM_STATE_CONFLICT")
        assertTrue(SA.reviewsOfItem(delivered.item).isEmpty())
    }

    @Test
    fun `§4_1 상태 409 ITEM_STATE_CONFLICT - DELIVERED·DISPUTED·REFUNDED_AFTER_CONFIRM, 기각된 분쟁 항목(다시 CONFIRMED)은 201`() {
        val seller = Db.createUser()
        val delivered = S2.purchase(listOf(Line(100_000, null, seller)))
        S2.toDelivered(delivered.item, seller)
        SA.writeReview(delivered.item, delivered.buyer, 5, "a").assertError(409, "ITEM_STATE_CONFLICT")

        val disputed = SA.confirmedSale(seller, 100_000)
        S2.openDispute(disputed.item, disputed.buyer)
        SA.writeReview(disputed.item, disputed.buyer, 5, "a").assertError(409, "ITEM_STATE_CONFLICT")

        val refunded = SA.confirmedSale(seller, 100_000)
        S2.resolved(S2.openDispute(refunded.item, refunded.buyer), true)
        assertEquals("REFUNDED_AFTER_CONFIRM", Db2.item(refunded.item).status)
        SA.writeReview(refunded.item, refunded.buyer, 5, "a").assertError(409, "ITEM_STATE_CONFLICT")

        val rejected = SA.confirmedSale(seller, 100_000)
        S2.resolved(S2.openDispute(rejected.item, rejected.buyer), false)
        assertEquals("CONFIRMED", Db2.item(rejected.item).status)
        SA.writeReview(rejected.item, rejected.buyer, 5, "a").assertStatus(201)

        for (p in listOf(delivered, disputed, refunded)) assertTrue(SA.reviewsOfItem(p.item).isEmpty())
    }

    @Test
    fun `§4_1 중복 - 리뷰가 이미 있으면(VISIBLE·HIDDEN·DELETED 모두) 409 ITEM_STATE_CONFLICT, 행은 1개 그대로`() {
        val seller = Db.createUser()
        val visible = SA.confirmedSale(seller, 100_000)
        val hidden = SA.confirmedSale(seller, 100_000)
        val deleted = SA.confirmedSale(seller, 100_000)
        SA.writeReview(visible.item, visible.buyer, 5, "a").assertStatus(201)
        val h = SA.writeReview(hidden.item, hidden.buyer, 5, "a").assertStatus(201).long("reviewId")
        val d = SA.writeReview(deleted.item, deleted.buyer, 5, "a").assertStatus(201).long("reviewId")
        SA.hideReview(h).assertStatus(200)
        SA.deleteReview(d, deleted.buyer).assertStatus(200)
        Flow.advance(HOUR)

        for (p in listOf(visible, hidden, deleted)) {
            val before = SA.snap(SA.reviewsOfItem(p.item))
            SA.writeReview(p.item, p.buyer, 3, "다시").assertError(409, "ITEM_STATE_CONFLICT")
            assertEquals(before, SA.snap(SA.reviewsOfItem(p.item)), "지운 뒤에도 다시 쓸 수 없다")
        }
    }

    @Test
    fun `§4_1 기한 - confirmed_at + 30일 정각은 201, 1초 뒤는 409 DEADLINE_PASSED, 상태 위반·중복이 기한보다 먼저`() {
        val seller = Db.createUser()
        val atEdge = SA.confirmedSale(seller, 100_000)
        val late = SA.confirmedSale(seller, 100_000)
        val disputed = SA.confirmedSale(seller, 100_000)
        val dup = SA.confirmedSale(seller, 100_000)
        val confirmedAt = Db2.item(atEdge.item).confirmedAt!!
        for (p in listOf(late, disputed, dup)) assertEquals(confirmedAt, Db2.item(p.item).confirmedAt, "준비: 같은 확정 시각")
        S2.openDispute(disputed.item, disputed.buyer)
        SA.writeReview(dup.item, dup.buyer, 5, "a").assertStatus(201)

        Flow.moveTo(confirmedAt.plus(days(30)))
        SA.writeReview(atEdge.item, atEdge.buyer, 5, "정각").assertStatus(201)

        Flow.moveTo(confirmedAt.plus(days(30)).plus(SEC))
        SA.writeReview(late.item, late.buyer, 5, "늦음").assertError(409, "DEADLINE_PASSED")
        SA.writeReview(disputed.item, disputed.buyer, 5, "a").assertError(409, "ITEM_STATE_CONFLICT")
        SA.writeReview(dup.item, dup.buyer, 5, "a").assertError(409, "ITEM_STATE_CONFLICT")
        assertTrue(SA.reviewsOfItem(late.item).isEmpty())
        assertTrue(SA.reviewsOfItem(disputed.item).isEmpty())
        assertEquals(1, SA.reviewsOfItem(dup.item).size)
    }

    @Test
    fun `§4_1·§2_3 동시 작성 - 같은 항목에 동시에 6건이면 201 1건·나머지 409 ITEM_STATE_CONFLICT, 행 1개`() {
        val p = SA.confirmedSale(Db.createUser(), 100_000)
        // 먼저 쓰는 요청이 주문 행 잠금을 쥔 채 INSERT 직전에 0.5초 멈춰 나머지가 그 잠금을 기다리게 한다
        val rs = SlowTrigger.on("reviews", "INSERT") { SA.concurrently(6) { i -> SA.writeReview(p.item, p.buyer, 1 + i % 5, "동시 $i") } }
        val win = SA.assertOneWins(rs, 201)
        val row = SA.reviewsOfItem(p.item).single()
        assertEquals(win.long("reviewId"), row.id)
    }

    // ---- §4.2 삭제 ----

    @Test
    fun `§4_2 삭제 - 작성자, VISIBLE → DELETED(updated_at = 현재), 200 리뷰 모양, 판매자 조회에서 빠진다`() {
        val seller = Db.createUser()
        val p = SA.confirmedSale(seller, 100_000)
        val createdAt = Flow.now()
        val id = SA.writeReview(p.item, p.buyer, 5, "a").assertStatus(201).long("reviewId")
        val now = Flow.advance(HOUR)

        val r = SA.deleteReview(id, p.buyer).assertStatus(200)
        val row = SA.review(id)
        SA.assertReviewBody(r.json, row)
        assertEquals("DELETED", row.text("status"))
        assertNull(row.textOrNull("hidden_reason"))
        assertEquals(now, row.instant("updated_at"))
        assertEquals(createdAt, row.instant("created_at"), "created_at 그대로")
        assertTrue(SA.sellerReviews(seller).assertStatus(200).json.get("reviews").none { it.longAt("reviewId") == id })
    }

    @Test
    fun `§4_2 삭제 - HIDDEN(ADMIN)도 DELETED, hidden_reason NULL`() {
        val p = SA.confirmedSale(Db.createUser(), 100_000)
        val id = SA.writeReview(p.item, p.buyer, 2, "a").assertStatus(201).long("reviewId")
        SA.hideReview(id).assertStatus(200)
        val now = Flow.advance(HOUR)
        val r = SA.deleteReview(id, p.buyer).assertStatus(200)
        assertEquals("DELETED", r.text("status"))
        val row = SA.review(id)
        assertEquals("DELETED", row.text("status"))
        assertNull(row.textOrNull("hidden_reason"))
        assertEquals(now, row.instant("updated_at"))
    }

    @Test
    fun `§4_2 오류 - 401, 없는 리뷰 404, 작성자 아님 403(판매자 포함), DELETED 409, 판정 순서 404 → 403 → 409`() {
        val seller = Db.createUser()
        val p = SA.confirmedSale(seller, 100_000)
        val id = SA.writeReview(p.item, p.buyer, 5, "a").assertStatus(201).long("reviewId")

        SA.deleteReview(id, null).assertError(401, "UNKNOWN_USER")
        SA.deleteReview(id, "abc").assertError(401, "UNKNOWN_USER")
        SA.deleteReview(id, Db.missingId("users")).assertError(401, "UNKNOWN_USER")
        SA.deleteReview(Db.missingId("reviews"), p.buyer).assertError(404, "NOT_FOUND")
        SA.deleteReview(id, seller).assertError(403, "NOT_OWNER")
        SA.deleteReview(id, Db.createUser()).assertError(403, "NOT_OWNER")
        assertEquals("VISIBLE", SA.review(id).text("status"))

        SA.deleteReview(id, p.buyer).assertStatus(200)
        val updatedAt = SA.review(id).instant("updated_at")
        Flow.advance(HOUR)
        SA.deleteReview(id, seller).assertError(403, "NOT_OWNER")                  // 403이 409보다
        SA.deleteReview(id, p.buyer).assertError(409, "ITEM_STATE_CONFLICT")
        assertEquals(updatedAt, SA.review(id).instant("updated_at"), "409는 아무것도 바꾸지 않는다")
    }

    @Test
    fun `§4_2·§2_3 동시 삭제 - 같은 리뷰를 동시에 5번 지우면 200 1건·나머지 409`() {
        val p = SA.confirmedSale(Db.createUser(), 100_000)
        val id = SA.writeReview(p.item, p.buyer, 5, "a").assertStatus(201).long("reviewId")
        Flow.advance(HOUR)
        SA.assertOneWins(SlowTrigger.on("reviews", "UPDATE") { SA.concurrently(5) { SA.deleteReview(id, p.buyer) } }, 200)
        assertEquals("DELETED", SA.review(id).text("status"))
    }

    // ---- §4.3 관리자 숨김 ----

    @Test
    fun `§4_3·§2_7 숨김 - VISIBLE → HIDDEN·ADMIN(updated_at = 현재), 200 리뷰 모양, 감사 REVIEW_HIDE 1행(detail null)`() {
        val seller = Db.createUser()
        val p = SA.confirmedSale(seller, 100_000)
        val id = SA.writeReview(p.item, p.buyer, 1, "나쁨").assertStatus(201).long("reviewId")
        val now = Flow.advance(HOUR)

        val r = SA.hideReview(id).assertStatus(200)
        val row = SA.review(id)
        SA.assertReviewBody(r.json, row)
        assertEquals("HIDDEN", row.text("status"))
        assertEquals("ADMIN", row.text("hidden_reason"))
        assertEquals(now, row.instant("updated_at"))
        SA.assertOneAudit("REVIEW_HIDE", id, null, now)
        assertTrue(SA.sellerReviews(seller).assertStatus(200).json.get("reviews").none { it.longAt("reviewId") == id }, "VISIBLE만 보인다")
    }

    @Test
    fun `§4_3·§2_7 숨김 오류 - 없는 리뷰 404, HIDDEN·DELETED는 409 ITEM_STATE_CONFLICT, 실패는 감사 로그를 쓰지 않는다`() {
        val seller = Db.createUser()
        val a = SA.confirmedSale(seller, 100_000)
        val b = SA.confirmedSale(seller, 100_000)
        val hidden = SA.writeReview(a.item, a.buyer, 5, "a").assertStatus(201).long("reviewId")
        val deleted = SA.writeReview(b.item, b.buyer, 5, "b").assertStatus(201).long("reviewId")
        val hiddenAt = Flow.advance(HOUR)
        SA.hideReview(hidden).assertStatus(200)
        SA.deleteReview(deleted, b.buyer).assertStatus(200)
        Flow.advance(HOUR)

        val missing = Db.missingId("reviews")
        SA.hideReview(missing).assertError(404, "NOT_FOUND")
        SA.assertNoAudit("REVIEW_HIDE", missing)
        SA.hideReview(hidden).assertError(409, "ITEM_STATE_CONFLICT")
        SA.assertOneAudit("REVIEW_HIDE", hidden, null, hiddenAt)                // 첫 성공의 1행뿐
        assertEquals(hiddenAt, SA.review(hidden).instant("updated_at"))
        SA.hideReview(deleted).assertError(409, "ITEM_STATE_CONFLICT")
        SA.assertNoAudit("REVIEW_HIDE", deleted)
        assertEquals("DELETED", SA.review(deleted).text("status"))
    }

    @Test
    fun `§4_3·§2_7 감사는 같은 트랜잭션 - 감사 INSERT가 실패하면 400 UNEXPECTED_ERROR, 리뷰는 VISIBLE 그대로, 감사 행 0`() {
        val p = SA.confirmedSale(Db.createUser(), 100_000)
        val id = SA.writeReview(p.item, p.buyer, 1, "a").assertStatus(201).long("reviewId")
        val updatedAt = SA.review(id).instant("updated_at")
        Flow.advance(HOUR)
        FailTrigger.on("admin_audit_logs", "INSERT", "NEW.target_id = $id") { name ->
            SA.hideReview(id).assertUnexpectedError(name)
        }
        val row = SA.review(id)
        assertEquals("VISIBLE", row.text("status"))
        assertNull(row.textOrNull("hidden_reason"))
        assertEquals(updatedAt, row.instant("updated_at"))
        SA.assertNoAudit("REVIEW_HIDE", id)
    }

    // ---- §4.4 조회 ----

    @Test
    fun `§4_4 조회 - 헤더 불필요, 평점 행 없음 = 0·0·averageX100 null, reviews = VISIBLE만 reviewId 오름차순(배치 전에도 지금 상태)`() {
        val seller = Db.createUser()
        val sales = (1..4).map { SA.confirmedSale(seller, 100_000) }
        val ids = sales.mapIndexed { i, p -> SA.writeReview(p.item, p.buyer, i + 2, "r$i").assertStatus(201).long("reviewId") }
        SA.hideReview(ids[1]).assertStatus(200)
        SA.deleteReview(ids[2], sales[2].buyer).assertStatus(200)

        val r = SA.sellerReviews(seller).assertStatus(200)
        assertEquals(seller, r.long("sellerId"))
        assertEquals(0L, r.long("reviewCount"), "rating-recalc 전 = 행 없음 = 0")
        assertEquals(0L, r.long("ratingSum"))
        SA.assertNullField(r.json, "averageX100")
        val list = r.json.get("reviews")
        assertEquals(listOf(ids[0], ids[3]), list.map { it.longAt("reviewId") })
        list.forEach { SA.assertReviewBody(it, SA.review(it.longAt("reviewId"))) }
        assertTrue(list.all { it.textAt("status") == "VISIBLE" })
    }

    @Test
    fun `§4_4 조회 - 리뷰 없는 사용자는 0·0·null·빈 목록, 없는 사용자 404`() {
        val user = Db.createUser()
        val r = SA.sellerReviews(user).assertStatus(200)
        assertEquals(user, r.long("sellerId"))
        assertEquals(0L, r.long("reviewCount"))
        assertEquals(0L, r.long("ratingSum"))
        SA.assertNullField(r.json, "averageX100")
        assertEquals(0, r.json.get("reviews").size())
        SA.sellerReviews(Db.missingId("users")).assertError(404, "NOT_FOUND")
        // §19의 7: 헤더가 필요 없는 엔드포인트는 X-User-Id를 읽지 않는다(잘못된 값도 401 아님)
        assertEquals(user, Api.get("/sellers/$user/reviews", "abc").assertStatus(200).long("sellerId"))
        assertEquals(user, Api.get("/sellers/$user/reviews", Db.missingId("users")).assertStatus(200).long("sellerId"))
        assertEquals(0L, Db3.count("reviews", "seller_id = ?", user))
    }
}
