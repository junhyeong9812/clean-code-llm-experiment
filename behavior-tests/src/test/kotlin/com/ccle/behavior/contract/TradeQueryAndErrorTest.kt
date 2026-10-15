package com.ccle.behavior.contract

import com.ccle.behavior.support.Api
import com.ccle.behavior.support.ContractTest
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.instantAt
import com.ccle.behavior.support.longAt
import com.ccle.behavior.support.textAt
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ADR-0003 §4 체결 조회, §2 오류표의 401·헤더 불필요 엔드포인트. */
@ContractTest
class TradeQueryAndErrorTest {

    @Test
    fun `체결 조회 - role=buyer·seller, tradeId 오름차순, 필드, status 필터`() {
        val now = Flow.now()
        val buyer = Db.createUser()
        val seller = Db.createUser()
        val size = Db.createProductSize()
        val t1 = Flow.trade(buyer, seller, size, 70_000)
        Flow.advance(Duration.ofSeconds(1))
        val t2 = Flow.trade(buyer, seller, size, 80_000)
        val t3 = Flow.trade(Db.createUser(), seller, size, 90_000) // 판매자만 같음
        Flow.pay(buyer, listOf(t1), 0).assertStatus(201)

        val asBuyer = Api.get("/trades?role=buyer", buyer).assertStatus(200).json
        assertEquals(listOf(t1, t2), asBuyer.map { it.longAt("tradeId") })
        val first = asBuyer[0]
        assertEquals(size, first.longAt("productSizeId"))
        assertEquals(70_000L, first.longAt("price"))
        assertEquals("PAID", first.textAt("status"))
        assertEquals(now, first.instantAt("matchedAt"))
        assertEquals(now.plus(Duration.ofMinutes(30)), first.instantAt("paymentDueAt"))
        assertEquals("AWAITING_PAYMENT", asBuyer[1].textAt("status"))

        val asSeller = Api.get("/trades?role=seller", seller).assertStatus(200).json
        assertEquals(listOf(t1, t2, t3), asSeller.map { it.longAt("tradeId") })

        val awaiting = Api.get("/trades?role=buyer&status=AWAITING_PAYMENT", buyer).assertStatus(200).json
        assertEquals(listOf(t2), awaiting.map { it.longAt("tradeId") })
        val paid = Api.get("/trades?role=seller&status=PAID", seller).assertStatus(200).json
        assertEquals(listOf(t1), paid.map { it.longAt("tradeId") })

        assertEquals(0, Api.get("/trades?role=seller", buyer).assertStatus(200).json.size())
    }

    @Test
    fun `체결 조회 - role 누락·잘못된 role·알 수 없는 status 400, 헤더 없음 401`() {
        val user = Db.createUser()
        Api.get("/trades", user).assertError(400, "INVALID_REQUEST")
        Api.get("/trades?role=admin", user).assertError(400, "INVALID_REQUEST")
        Api.get("/trades?role=buyer&status=SHIPPED", user).assertError(400, "INVALID_REQUEST")
        Api.get("/trades?role=buyer").assertError(401, "UNKNOWN_USER")
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("headerEndpoints")
    fun `X-User-Id 없음·숫자 아님·없는 사용자 - 401 UNKNOWN_USER`(method: String, pathTemplate: String, bodyTemplate: String?) {
        fun fill(t: String) = t
            .replace("{SIZE}", Db.missingId("product_sizes").toString())
            .replace("{BID}", Db.missingId("bids").toString())
            .replace("{TRADE}", Db.missingId("trades").toString())
            .replace("{ORDER}", Db.missingId("orders").toString())
        val path = fill(pathTemplate)
        val body = bodyTemplate?.let(::fill)
        // 대조: 있는 사용자면 401이 아니다(라우트가 있고 헤더를 읽는다)
        val control = Api.request(method, path, rawBody = body, userId = Db.createUser(), idempotencyKey = Flow.newKey())
        assertTrue(control.status != 401 && control.status < 500, "있는 사용자는 401·5xx가 아니어야 한다: $control")
        val missingUser = Db.missingId("users")
        for (user in listOf(null, "abc", missingUser)) {
            Api.request(method, path, rawBody = body, userId = user, idempotencyKey = Flow.newKey())
                .assertError(401, "UNKNOWN_USER")
        }
    }

    @Test
    fun `헤더 불필요 - 호가·시계·배치`() {
        val book = Api.get("/product-sizes/${Db.createProductSize()}/book").assertStatus(200)
        assertTrue(book.isNull("bestBuy") && book.isNull("bestSell"), book.toString())
        Api.get("/admin/clock").assertStatus(200).json.instantAt("now")
        Flow.runBatch("bid-expiry")
    }

    @Test
    fun `알 수 없는 배치 이름 - 400 INVALID_REQUEST`() {
        Flow.runBatch("bid-expiry") // 대조: 아는 이름은 200
        Flow.batch("no-such-batch").assertError(400, "INVALID_REQUEST")
    }

    companion object {
        @JvmStatic
        fun headerEndpoints(): List<Arguments> = listOf(
            Arguments.of("POST", "/bids", """{"productSizeId":{SIZE},"side":"BUY","price":100000,"validDays":30}"""),
            Arguments.of("DELETE", "/bids/{BID}", null),
            Arguments.of("GET", "/bids/{BID}", null),
            Arguments.of("GET", "/trades?role=buyer", null),
            Arguments.of("POST", "/orders", """{"tradeIds":[{TRADE}],"pointToUse":0}"""),
            Arguments.of("GET", "/orders/{ORDER}", null),
            Arguments.of("GET", "/me/points", null),
        )
    }
}
