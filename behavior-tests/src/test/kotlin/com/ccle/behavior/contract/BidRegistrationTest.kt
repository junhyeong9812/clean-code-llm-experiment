package com.ccle.behavior.contract

import com.ccle.behavior.support.Api
import com.ccle.behavior.support.ContractTest
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.instantAt
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ADR-0003 §3.1 등록, §3.2 취소, §3.4 조회. */
@ContractTest
class BidRegistrationTest {

    @ParameterizedTest
    @ValueSource(strings = ["BUY", "SELL"])
    fun `일반 입찰 등록 - 201 ACTIVE, bids 1행, bid_events NULL→ACTIVE`(side: String) {
        val now = Flow.now()
        val user = Db.createUser()
        val size = Db.createProductSize()

        val r = Flow.bid(user, size, side, 250_000, 30).assertStatus(201)
        assertEquals("ACTIVE", r.text("status"))
        assertTrue(r.isNull("tradeId"), "tradeId null: $r")
        assertTrue(r.isNull("tradePrice"), "tradePrice null: $r")

        val bid = Db.bid(r.long("bidId"))
        assertEquals(user, bid.userId)
        assertEquals(size, bid.productSizeId)
        assertEquals(side, bid.side)
        assertEquals(250_000L, bid.price)
        assertEquals("ACTIVE", bid.status)
        assertEquals(now, bid.createdAt)
        assertEquals(now.plus(Duration.ofDays(30)), bid.expiresAt)

        val ev = Db.bidEvents(bid.id).single()
        assertEquals(null, ev.fromStatus)
        assertEquals("ACTIVE", ev.toStatus)
        assertEquals(null, ev.tradeId)
        assertEquals(now, ev.createdAt)
        assertEquals(listOf(bid.id), Db.bidsOfUser(user).map { it.id })
    }

    @ParameterizedTest
    @ValueSource(ints = [30, 60, 90])
    fun `validDays 30·60·90 - expires_at = 현재 + validDays일`(days: Int) {
        val now = Flow.now()
        val user = Db.createUser()
        val size = Db.createProductSize()
        val id = Flow.activeBid(user, size, "BUY", 100_000, days)
        assertEquals(now.plus(Duration.ofDays(days.toLong())), Db.bid(id).expiresAt)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"side":"BUY","price":100000,"validDays":30}""",                                  // productSizeId 누락
            """{"productSizeId":%S,"price":100000,"validDays":30}""",                           // side 누락
            """{"productSizeId":%S,"side":"HOLD","price":100000,"validDays":30}""",             // 알 수 없는 enum
            """{"productSizeId":%S,"side":"BUY","validDays":30}""",                             // price 누락
            """{"productSizeId":%S,"side":"BUY","price":0,"validDays":30}""",                   // price 0
            """{"productSizeId":%S,"side":"BUY","price":-1000,"validDays":30}""",               // price 음수
            """{"productSizeId":%S,"side":"BUY","price":100000}""",                             // validDays 누락
            """{"productSizeId":%S,"side":"BUY","price":100000,"validDays":45}""",              // validDays 범위 밖
            """{"productSizeId":%S,"side":"BUY","price":"abc","validDays":30}""",               // 형식 오류
            """{"productSizeId":%S,"side":"BUY","instant":true,"price":100000}""",              // 즉시 + price
            """{"productSizeId":%S,"side":"SELL","instant":true,"validDays":30}""",             // 즉시 + validDays
            """not-json""",
        ],
    )
    fun `등록 형식 오류 - 400 INVALID_REQUEST, 행 없음`(template: String) {
        val user = Db.createUser()
        val size = Db.createProductSize()
        Flow.activeBid(Db.createUser(), size, "SELL", 90_000) // 즉시 요청이 상대를 찾을 수 있게 해도 400이어야 한다
        val body = template.replace("%S", size.toString())
        Api.request("POST", "/bids", rawBody = body, userId = user).assertError(400, "INVALID_REQUEST")
        assertTrue(Db.bidsOfUser(user).isEmpty())
    }

    @Test
    fun `없는 사이즈 - 404 NOT_FOUND`() {
        val user = Db.createUser()
        Flow.bid(user, Db.createProductSize(), "BUY", 100_000).assertStatus(201) // 라우트가 있는 상태에서의 404인지
        Flow.bid(user, Db.missingId("product_sizes"), "BUY", 100_000).assertError(404, "NOT_FOUND")
        Flow.instant(user, Db.missingId("product_sizes"), "SELL").assertError(404, "NOT_FOUND")
        assertEquals(1, Db.bidsOfUser(user).size)
    }

    @Test
    fun `판정 순서 - 401이 400보다, 400이 404보다 먼저`() {
        val missingSize = Db.missingId("product_sizes")
        Api.request("POST", "/bids", rawBody = """{"productSizeId":$missingSize,"side":"BUY","price":0,"validDays":30}""")
            .assertError(401, "UNKNOWN_USER")
        Api.request("POST", "/bids", rawBody = """{"productSizeId":$missingSize,"side":"BUY","price":0,"validDays":30}""", userId = Db.createUser())
            .assertError(400, "INVALID_REQUEST")
    }

    // ---- §3.2 취소 ----

    @Test
    fun `취소 - 등록자, ACTIVE → 200 CANCELLED, bid_events ACTIVE→CANCELLED`() {
        val user = Db.createUser()
        val size = Db.createProductSize()
        val id = Flow.activeBid(user, size, "BUY", 100_000)
        val now = Flow.advance(Duration.ofMinutes(1))

        val r = Flow.cancel(user, id).assertStatus(200)
        assertEquals(id, r.long("bidId"))
        assertEquals("CANCELLED", r.text("status"))
        assertEquals("CANCELLED", Db.bid(id).status)
        val ev = Db.bidEvents(id)
        assertEquals(2, ev.size)
        assertEquals("ACTIVE", ev[1].fromStatus)
        assertEquals("CANCELLED", ev[1].toStatus)
        assertEquals(null, ev[1].tradeId)
        assertEquals(now, ev[1].createdAt)
    }

    @Test
    fun `취소 - 남의 입찰 403 NOT_OWNER, 상태 그대로`() {
        val owner = Db.createUser()
        val size = Db.createProductSize()
        val id = Flow.activeBid(owner, size, "SELL", 100_000)
        Flow.cancel(Db.createUser(), id).assertError(403, "NOT_OWNER")
        assertEquals("ACTIVE", Db.bid(id).status)
        assertEquals(1, Db.bidEvents(id).size)
    }

    @Test
    fun `취소 - ACTIVE 아닌 입찰(CANCELLED·MATCHED) 409 BID_NOT_ACTIVE`() {
        val user = Db.createUser()
        val size = Db.createProductSize()
        val cancelled = Flow.activeBid(user, size, "BUY", 100_000)
        Flow.cancel(user, cancelled).assertStatus(200)
        Flow.cancel(user, cancelled).assertError(409, "BID_NOT_ACTIVE")
        assertEquals(2, Db.bidEvents(cancelled).size)

        val seller = Db.createUser()
        val sell = Flow.activeBid(seller, size, "SELL", 90_000)
        Flow.bid(Db.createUser(), size, "BUY", 95_000).assertStatus(201)
        assertEquals("MATCHED", Db.bid(sell).status)
        Flow.cancel(seller, sell).assertError(409, "BID_NOT_ACTIVE")
        assertEquals("MATCHED", Db.bid(sell).status)
    }

    @Test
    fun `취소 - 없는 입찰 404, 남의 MATCHED 입찰은 403(403이 409보다 먼저)`() {
        val user = Db.createUser()
        Flow.cancel(user, Db.missingId("bids")).assertError(404, "NOT_FOUND")

        val size = Db.createProductSize()
        val seller = Db.createUser()
        val sell = Flow.activeBid(seller, size, "SELL", 90_000)
        Flow.bid(Db.createUser(), size, "BUY", 95_000).assertStatus(201)
        Flow.cancel(user, sell).assertError(403, "NOT_OWNER")
    }

    // ---- §3.4 조회 ----

    @Test
    fun `조회 - 등록자, 필드 전체`() {
        val now = Flow.now()
        val user = Db.createUser()
        val size = Db.createProductSize()
        val id = Flow.activeBid(user, size, "SELL", 123_000, 60)

        val r = Api.get("/bids/$id", user).assertStatus(200)
        assertEquals(id, r.long("bidId"))
        assertEquals(user, r.long("userId"))
        assertEquals(size, r.long("productSizeId"))
        assertEquals("SELL", r.text("side"))
        assertEquals(123_000L, r.long("price"))
        assertEquals("ACTIVE", r.text("status"))
        assertEquals(now, r.json.instantAt("createdAt"))
        assertEquals(now.plus(Duration.ofDays(60)), r.json.instantAt("expiresAt"))
        assertTrue(r.isNull("tradeId"), "tradeId null: $r")
    }

    @Test
    fun `조회 - 체결된 입찰의 tradeId = 살아 있는 체결`() {
        val size = Db.createProductSize()
        val seller = Db.createUser()
        val buyer = Db.createUser()
        val sell = Flow.activeBid(seller, size, "SELL", 100_000)
        val r = Flow.bid(buyer, size, "BUY", 100_000).assertStatus(201)
        val tradeId = r.long("tradeId")
        assertEquals(tradeId, Api.get("/bids/$sell", seller).assertStatus(200).long("tradeId"))
        assertEquals(tradeId, Api.get("/bids/${r.long("bidId")}", buyer).assertStatus(200).long("tradeId"))
        assertEquals("MATCHED", Api.get("/bids/$sell", seller).text("status"))
    }

    @Test
    fun `조회 - 남의 입찰 403, 없는 입찰 404, 헤더 없음 401`() {
        val owner = Db.createUser()
        val id = Flow.activeBid(owner, Db.createProductSize(), "BUY", 100_000)
        Api.get("/bids/$id", Db.createUser()).assertError(403, "NOT_OWNER")
        Api.get("/bids/${Db.missingId("bids")}", owner).assertError(404, "NOT_FOUND")
        Api.get("/bids/$id").assertError(401, "UNKNOWN_USER")
    }
}
