package com.ccle.behavior.contract3

import com.ccle.behavior.contract2.S2
import com.ccle.behavior.support.Contract3Test
import com.ccle.behavior.support.Db2
import com.ccle.behavior.support.SlowTrigger
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 도우미 SlowTrigger 자체 검증 — 이미 구현된 기존 엔드포인트(ADR-0004 §4.2 구매자 취소, §9.1 주문 행 잠금)로
 * "경합이 실제로 관측되면 통과, 요청이 순서대로 처리되면 실패"를 확인한다. 새 기능이 없어도 green이어야 한다.
 */
@Contract3Test
class SlowTriggerSelfTest {
    @Test
    fun `동시 구매자 취소 2건 - 주문 행 잠금 대기를 관측하고 200 1건·409 1건`() {
        val p = S2.purchase(30_000)
        val rs = SlowTrigger.on("order_items", "UPDATE", condition = "OLD.id = ${p.item}") {
            SA.concurrently(2) { S2.cancel(p.item, p.buyer) }
        }
        assertEquals(listOf(200, 409), rs.map { it.status }.sorted(), "응답: $rs")
        assertEquals("CANCELLED_BY_BUYER", Db2.item(p.item).status)
    }

    @Test
    fun `순서대로 보낸 요청은 경합 미관측으로 실패한다`() {
        val p = S2.purchase(30_000)
        val e = assertFailsWith<AssertionError> {
            SlowTrigger.on("order_items", "UPDATE", condition = "OLD.id = ${p.item}") {
                listOf(S2.cancel(p.item, p.buyer), S2.cancel(p.item, p.buyer))
            }
        }
        assertTrue(e.message!!.contains("경합 미관측"), "실패 사유: ${e.message}")
        assertEquals("CANCELLED_BY_BUYER", Db2.item(p.item).status)
    }
}
