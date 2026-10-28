package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.HOUR
import com.ccle.behavior.support.Contract3Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db3
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.SlowTrigger
import com.ccle.behavior.support.instantAt
import com.ccle.behavior.support.longAt
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-0007 §8.1 위시리스트: 등록(없는 사이즈 404, 이미 있으면 409 — 사용자 행 잠금으로 동시 중복도 409, §2.3),
 * 삭제(없으면 404, 행 삭제, 200 모양), 조회(productSizeId 오름차순, bestSell = ACTIVE·만료 전 SELL 최저가, activeAlert).
 */
@Contract3Test
class A5WishlistTest {

    @Test
    fun `§8_1 등록 - 201 productSizeId·createdAt = 현재, 1행, 조회에 bestSell·activeAlert null`() {
        val u = Db.createUser()
        val size = Db.createProductSize()
        val now = Flow.now()
        val r = SB.wishAdd(u, size).assertStatus(201)
        assertEquals(size, r.long("productSizeId"))
        assertEquals(now, r.json.instantAt("createdAt"))
        val row = SB.wishRows(u, size).single()
        assertEquals(now, row.instant("created_at"))

        val l = SB.wishlist(u).assertStatus(200)
        assertEquals(1, l.json.size(), "목록: $l")
        val j = l.json[0]
        assertEquals(size, j.longAt("productSizeId"))
        assertEquals(now, j.instantAt("createdAt"))
        assertTrue(j.has("bestSell") && j.get("bestSell").isNull, "SELL 입찰 없으면 bestSell null: $l")
        assertTrue(j.has("activeAlert") && j.get("activeAlert").isNull, "가격 알림 없으면 activeAlert null: $l")
    }

    @Test
    fun `§8_1 등록 - 이미 있으면 409 ITEM_STATE_CONFLICT(행 1개 유지), 같은 사이즈라도 다른 사용자는 201`() {
        val u = Db.createUser()
        val size = Db.createProductSize()
        SB.wishAdd(u, size).assertStatus(201)
        Flow.advance(HOUR)
        SB.wishAdd(u, size).assertError(409, "ITEM_STATE_CONFLICT")
        assertEquals(1, SB.wishRows(u, size).size)
        SB.wishAdd(Db.createUser(), size).assertStatus(201)
    }

    @Test
    fun `§8_1 등록 오류 - 없는 사이즈 404, productSizeId 누락·정수 아님 400, 헤더 없음·없는 사용자 401, 행 없음`() {
        val u = Db.createUser()
        SB.wishAdd(u, Db.missingId("product_sizes")).assertError(404, "NOT_FOUND")
        SB.wishAdd(u, null).assertError(400, "INVALID_REQUEST")
        SB.wishAdd(u, "abc").assertError(400, "INVALID_REQUEST")
        SB.wishAdd(null, Db.createProductSize()).assertError(401, "UNKNOWN_USER")
        SB.wishAdd(Db.missingId("users"), Db.createProductSize()).assertError(401, "UNKNOWN_USER")
        assertEquals(0, Db3.count("wishlists", "user_id = ?", u))
    }

    @Test
    fun `§8_1 삭제 - 200 productSizeId·createdAt(등록 시각), 행 삭제, 다시 삭제하면 404, 다시 등록하면 201`() {
        val u = Db.createUser()
        val size = Db.createProductSize()
        val addedAt = Flow.now()
        SB.wishAdd(u, size).assertStatus(201)
        Flow.advance(HOUR)

        val r = SB.wishDel(u, size).assertStatus(200)
        assertEquals(size, r.long("productSizeId"))
        assertEquals(addedAt, r.json.instantAt("createdAt"), "createdAt = 지운 행의 등록 시각")
        assertTrue(SB.wishRows(u, size).isEmpty(), "행 삭제")
        SB.wishDel(u, size).assertError(404, "NOT_FOUND")

        val again = Flow.now()
        SB.wishAdd(u, size).assertStatus(201)
        assertEquals(again, SB.wishRows(u, size).single().instant("created_at"))
    }

    @Test
    fun `§8_1 삭제 오류 - 내 위시리스트에 없는 사이즈 404(남의 행은 그대로), 없는 사이즈 404, 헤더 없음 401`() {
        val me = Db.createUser()
        val other = Db.createUser()
        val size = Db.createProductSize()
        SB.wishAdd(other, size).assertStatus(201)
        SB.wishDel(me, size).assertError(404, "NOT_FOUND")
        assertEquals(1, SB.wishRows(other, size).size, "남의 행은 지우지 않는다")
        SB.wishDel(me, Db.missingId("product_sizes")).assertError(404, "NOT_FOUND")
        SB.wishDel(null, size).assertError(401, "UNKNOWN_USER")
        assertEquals(1, SB.wishRows(other, size).size)
    }

    @Test
    fun `§8_1 조회 - productSizeId 오름차순·본인 것만, bestSell = ACTIVE SELL 최저가(취소·BUY 입찰 제외), activeAlert = ACTIVE 가격 알림 모양, 취소되면 null`() {
        val u = Db.createUser()
        val other = Db.createUser()
        val s1 = Db.createProductSize()
        val s2 = Db.createProductSize()
        assertTrue(s1 < s2)
        Flow.activeBid(Db.createUser(), s1, "SELL", 120_000)
        Flow.activeBid(Db.createUser(), s1, "SELL", 110_000)
        val cancelSeller = Db.createUser()
        val cancelled = Flow.activeBid(cancelSeller, s1, "SELL", 90_000)
        Flow.cancel(cancelSeller, cancelled).assertStatus(200)
        Flow.activeBid(Db.createUser(), s1, "BUY", 50_000)
        Flow.activeBid(Db.createUser(), s2, "BUY", 80_000)                 // s2: SELL 없음

        SB.wishAdd(u, s2).assertStatus(201)                                  // 등록 순서와 무관하게 사이즈 오름차순
        SB.wishAdd(u, s1).assertStatus(201)
        SB.wishAdd(other, s1).assertStatus(201)
        val alertId = SB.alert(u, s2, 70_000)

        val r = SB.wishlist(u).assertStatus(200)
        assertEquals(listOf(s1, s2), r.json.map { it.longAt("productSizeId") }, "본인 것만, productSizeId 오름차순: $r")
        assertEquals(110_000L, r.json[0].longAt("bestSell"), "취소된 SELL·BUY 입찰 제외 최저가: $r")
        assertTrue(r.json[0].get("activeAlert").isNull, "s1 가격 알림 없음: $r")
        assertTrue(r.json[1].get("bestSell").isNull, "s2 SELL 없음 → null: $r")
        SB.assertAlertBody(r.json[1].get("activeAlert"), Db3.row("price_alerts", alertId))

        Flow.advance(HOUR)
        SB.alertCancel(u, alertId).assertStatus(200)
        val after = SB.wishlist(u).assertStatus(200)
        assertTrue(after.json[1].get("activeAlert").isNull, "취소된 알림은 activeAlert 아님: $after")
    }

    @Test
    fun `§2_3 동시성 - 같은 사이즈 동시 등록은 하나만 201, 나머지 409(UNIQUE 위반 400 아님), 행 1개`() {
        val u = Db.createUser()
        val size = Db.createProductSize()
        val rs = SlowTrigger.on("wishlists", "INSERT", condition = "NEW.user_id = $u") {
            SB.concurrently(4) { SB.wishAdd(u, size) }
        }
        assertEquals(1, rs.count { it.status == 201 }, "201 하나: $rs")
        rs.filter { it.status != 201 }.forEach { it.assertError(409, "ITEM_STATE_CONFLICT") }
        assertEquals(1, SB.wishRows(u, size).size)
    }
}
