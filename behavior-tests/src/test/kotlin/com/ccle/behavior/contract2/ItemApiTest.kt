package com.ccle.behavior.contract2

import com.ccle.behavior.support.Api
import com.ccle.behavior.support.Contract2Test
import com.ccle.behavior.support.Db
import com.ccle.behavior.support.Db2
import com.ccle.behavior.support.Flow
import com.ccle.behavior.support.textAt
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * ADR-0004 §2 공통: 새 오류 코드(§2.1), 403 NOT_OWNER·404 NOT_FOUND·401, 판정 순서(401 → 400 → 404 → 403 → 409),
 * 주문 항목 응답 모양(§2.2), GET /orders의 2단계 상태.
 * 오류 단언이 라우트 없는 404로 통과하지 않게, 오류를 보는 테스트마다 같은 엔드포인트 계열의 성공 경로를 먼저 단언한다.
 */
@Contract2Test
class ItemApiTest {

    @Test
    fun `항목 조회 - 구매자·판매자 200, 결제 직후 모양(paidAt만, refund null), 다른 사용자 403 NOT_OWNER`() {
        val p = S2.purchase(70_000)
        val asBuyer = S2.item(p.item, p.buyer).assertStatus(200)
        val row = assertItemBody(asBuyer, p.item)
        assertEquals("PAID", row.status)
        assertEquals(p.paidAt, row.paidAt)
        assertEquals("PAID", asBuyer.text("status"))
        listOf("shippedAt", "inspectedAt", "deliveredAt", "confirmedAt", "cancelledAt", "refund").forEach {
            assertNull(asBuyer.json.instantOrNullOrNode(it), "결제 직후 $it = null: $asBuyer")
        }

        val asSeller = S2.item(p.item, p.seller).assertStatus(200)
        assertItemBody(asSeller, p.item)

        S2.item(p.item, Db.createUser()).assertError(403, "NOT_OWNER")
    }

    @Test
    fun `없는 주문 항목 404 NOT_FOUND - 조회·구매자·판매자·센터 엔드포인트`() {
        val p = S2.purchase(70_000)
        S2.item(p.item, p.buyer).assertStatus(200)           // 라우트가 있다는 대조군
        val missing = Db.missingId("order_items")
        val user = p.buyer
        S2.item(missing, user).assertError(404, "NOT_FOUND")
        S2.cancel(missing, user).assertError(404, "NOT_FOUND")
        S2.confirm(missing, user).assertError(404, "NOT_FOUND")
        S2.dispute(missing, user).assertError(404, "NOT_FOUND")
        S2.ship(missing, p.seller).assertError(404, "NOT_FOUND")
        S2.receive(missing).assertError(404, "NOT_FOUND")
        S2.inspect(missing, "PASS").assertError(404, "NOT_FOUND")
        S2.deliver(missing).assertError(404, "NOT_FOUND")
    }

    @Test
    fun `헤더 401 UNKNOWN_USER - 없음·숫자 아님·없는 사용자, 404보다 먼저, 상태 무변경`() {
        val p = S2.purchase(70_000)
        S2.item(p.item, p.buyer).assertStatus(200)
        val ghost = Db.missingId("users")
        for (h in listOf(null, "abc", ghost)) {
            S2.item(p.item, h).assertError(401, "UNKNOWN_USER")
            S2.cancel(p.item, h).assertError(401, "UNKNOWN_USER")
            S2.ship(p.item, h).assertError(401, "UNKNOWN_USER")
            S2.settlements(h).assertError(401, "UNKNOWN_USER")
        }
        // 401 → 404: 없는 항목이어도 헤더 오류가 먼저
        S2.cancel(Db.missingId("order_items"), null).assertError(401, "UNKNOWN_USER")
        assertEquals("PAID", Db2.item(p.item).status)
        // 대조군: 올바른 헤더면 본인 조회가 된다
        S2.settlements(p.seller).assertStatus(200)
    }

    @Test
    fun `소유자 403 NOT_OWNER - 구매자 API는 구매자만, 발송은 판매자만, 상태 무변경`() {
        val p = S2.purchase(70_000)
        val other = Db.createUser()
        S2.cancel(p.item, p.seller).assertError(403, "NOT_OWNER")
        S2.cancel(p.item, other).assertError(403, "NOT_OWNER")
        S2.ship(p.item, p.buyer).assertError(403, "NOT_OWNER")
        S2.ship(p.item, other).assertError(403, "NOT_OWNER")
        assertEquals("PAID", Db2.item(p.item).status)
        assertNoRefund(p, p.item)
        // 대조군: 본인은 된다
        assertItemOk(S2.ship(p.item, p.seller), p.item, "SHIPPED_TO_CENTER")

        val q = S2.purchase(70_000)
        S2.toDelivered(q.item, q.seller)
        S2.confirm(q.item, q.seller).assertError(403, "NOT_OWNER")
        S2.confirm(q.item, other).assertError(403, "NOT_OWNER")
        assertEquals("DELIVERED", Db2.item(q.item).status)
        S2.ready("구매 확정", S2.confirm(q.item, q.buyer))
        S2.dispute(q.item, q.seller).assertError(403, "NOT_OWNER")
        S2.dispute(q.item, other).assertError(403, "NOT_OWNER")
        assertEquals("CONFIRMED", Db2.item(q.item).status)
        assertEquals(emptyList(), Db2.disputes(q.item))
    }

    @Test
    fun `판정 순서 - 400이 404보다, 404가 403보다, 403이 409보다 먼저`() {
        // 400 → 404: 검수 요청 본문 오류는 없는 항목에도 400
        val p = S2.purchase(70_000)
        S2.toInspecting(p.item, p.seller)
        S2.inspect(p.item, "FAIL", null).assertError(400, "INVALID_REQUEST")    // 대조군: 있는 항목
        val missing = Db.missingId("order_items")
        S2.inspect(missing, "FAIL", null).assertError(400, "INVALID_REQUEST")
        S2.inspect(missing, "MAYBE", null).assertError(400, "INVALID_REQUEST")

        // 404 → 403: 없는 항목은 남이어도 404
        S2.cancel(missing, Db.createUser()).assertError(404, "NOT_FOUND")

        // 403 → 409: 발송된 항목(취소 불가)을 남이 취소하면 403, 구매자가 취소하면 409
        val q = S2.purchase(70_000)
        S2.toShipped(q.item, q.seller)
        S2.cancel(q.item, Db.createUser()).assertError(403, "NOT_OWNER")
        S2.cancel(q.item, q.seller).assertError(403, "NOT_OWNER")
        S2.cancel(q.item, q.buyer).assertError(409, "ITEM_STATE_CONFLICT")
        // 남이 발송하면(이미 발송된 항목이어도) 403
        S2.ship(q.item, q.buyer).assertError(403, "NOT_OWNER")
        S2.ship(q.item, q.seller).assertError(409, "ITEM_STATE_CONFLICT")
        assertEquals("SHIPPED_TO_CENTER", Db2.item(q.item).status)
        assertNoRefund(q, q.item)
    }

    @Test
    fun `주문 조회 - items status·주문 status가 2단계 상태를 그대로 보인다`() {
        val p = S2.purchase(listOf(Line(70_000), Line(60_000)))
        S2.toShipped(p.items[0], p.sellers[0])
        S2.ready("구매자 취소", S2.cancel(p.items[1], p.buyer))

        val r = Api.get("/orders/${p.orderId}", p.buyer).assertStatus(200)
        assertEquals("PARTIALLY_CANCELLED", r.text("status"))
        assertEquals(listOf("SHIPPED_TO_CENTER", "CANCELLED_BY_BUYER"), r.json.get("items").map { it.textAt("status") })
        assertEquals("PARTIALLY_CANCELLED", Db.order(p.orderId).status)
    }
}

/** 시각 필드는 null이면 null, refund는 객체/ null. 결제 직후 null 판정용. */
private fun com.fasterxml.jackson.databind.JsonNode.instantOrNullOrNode(field: String): Any? =
    if (field == "refund") get(field)?.takeIf { !it.isNull } else instantOrNull(field)
