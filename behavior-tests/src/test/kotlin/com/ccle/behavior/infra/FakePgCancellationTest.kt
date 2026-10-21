package com.ccle.behavior.infra

import com.ccle.behavior.pg.FakePg
import com.ccle.behavior.support.Api
import com.ccle.behavior.support.InfraTest
import com.ccle.behavior.support.Resp
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 가짜 PG 카드 취소 자체 테스트(ADR-0004 §7.2). 별도 인스턴스를 임의 포트로 띄운다. */
@InfraTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FakePgCancellationTest {
    private val pg = FakePg(0, 400L).start()

    @AfterAll
    fun stop() = pg.close()

    private fun key() = "cancel-infra-" + UUID.randomUUID()
    private fun control(key: String, outcome: String): Resp =
        Api.request("POST", "/_control/outcomes", mapOf("idempotencyKey" to key, "outcome" to outcome), base = pg.url)
    private fun cancel(key: String, amount: Long, approvalKey: String = "order-key-1"): Resp =
        Api.request("POST", "/cancellations", mapOf("idempotencyKey" to key, "approvalKey" to approvalKey, "amount" to amount), base = pg.url)
    private fun cancellations(key: String): Resp = Api.request("GET", "/_control/cancellations/$key", base = pg.url)

    @Test
    fun `지정 없는 키는 승인 - APPROVED, 요청 금액, 기록 조회`() {
        val k = key()
        val r = cancel(k, 7000, "order-a")
        assertEquals(200, r.status, r.toString())
        assertEquals("APPROVED", r.json["status"].asText())
        assertEquals(7000L, r.json["amount"].asLong())
        val tx = r.json["pgTxId"].asText()
        assertTrue(tx.isNotBlank())

        val c = cancellations(k)
        assertEquals(200, c.status)
        assertEquals(1, c.json["requests"].asInt())
        assertEquals(listOf(7000L), c.json["amounts"].map { it.asLong() })
        assertEquals(listOf("order-a"), c.json["approvalKeys"].map { it.asText() })
        assertEquals(tx, c.json["record"]["pgTxId"].asText())
        assertEquals("APPROVED", c.json["record"]["status"].asText())
        assertEquals(7000L, c.json["record"]["amount"].asLong())
        assertEquals("order-a", c.json["record"]["approvalKey"].asText())
        assertEquals(FakePg.CancelRecord(tx, "APPROVED", 7000L, "order-a"), pg.cancelRecord(k))
    }

    @Test
    fun `같은 키 두 번째 요청은 첫 기록(첫 금액)을 그대로 돌려준다`() {
        val k = key()
        val first = cancel(k, 7000)
        val second = cancel(k, 9000) // 금액이 달라도 첫 기록
        assertEquals(200, second.status)
        assertEquals(first.json["pgTxId"].asText(), second.json["pgTxId"].asText())
        assertEquals(7000L, second.json["amount"].asLong())
        assertEquals("APPROVED", second.json["status"].asText())
        val c = cancellations(k)
        assertEquals(2, c.json["requests"].asInt())
        assertEquals(listOf(7000L, 9000L), c.json["amounts"].map { it.asLong() })
        assertEquals(7000L, c.json["record"]["amount"].asLong())
    }

    @Test
    fun `DECLINE - 200 DECLINED·pgTxId null·요청 금액, 기록하지 않음, 해제하면 승인`() {
        val k = key()
        assertEquals(200, control(k, "DECLINE").status)
        val r = cancel(k, 7000)
        assertEquals(200, r.status, r.toString())
        assertEquals("DECLINED", r.json["status"].asText())
        assertTrue(r.json["pgTxId"] == null || r.json["pgTxId"].isNull, r.toString())
        assertEquals(7000L, r.json["amount"].asLong())
        assertEquals(null, pg.cancelRecord(k))
        val c = cancellations(k)
        assertEquals(1, c.json["requests"].asInt())
        assertTrue(c.json["record"].isNull, "기록이 없어야 한다: $c")

        assertEquals(200, control(k, "APPROVE").status)
        val ok = cancel(k, 7000)
        assertEquals("APPROVED", ok.json["status"].asText())
        assertEquals(7000L, pg.cancelRecord(k)?.amount)
    }

    @Test
    fun `전체 조회 - 받은 모든 취소 키의 승인 키·요청 수·기록, 기록 없는 키 포함`() {
        val approved = key()
        val refused = key()
        cancel(approved, 7000, "order-list")
        cancel(approved, 7000, "order-list")
        assertEquals(200, control(refused, "UNAVAILABLE").status)
        cancel(refused, 5000, "order-list")

        val r = Api.request("GET", "/_control/cancellations", base = pg.url)
        assertEquals(200, r.status, r.toString())
        val byKey = r.json.associateBy { it["key"].asText() }
        val a = byKey.getValue(approved)
        assertEquals("order-list", a["approvalKey"].asText())
        assertEquals(listOf("order-list"), a["approvalKeys"].map { it.asText() })
        assertEquals(2, a["requests"].asInt())
        assertEquals("APPROVED", a["record"]["status"].asText())
        assertEquals(7000L, a["record"]["amount"].asLong())
        val b = byKey.getValue(refused)
        assertEquals(1, b["requests"].asInt())
        assertTrue(b["record"].isNull, b.toString())
        assertEquals(listOf("order-list"), b["approvalKeys"].map { it.asText() })
    }

    @Test
    fun `UNAVAILABLE - 503, 기록하지 않음, 해제하면 승인`() {
        val k = key()
        assertEquals(200, control(k, "UNAVAILABLE").status)
        val r = cancel(k, 7000)
        assertEquals(503, r.status, r.toString())
        assertEquals(null, pg.cancelRecord(k))
        val c = cancellations(k)
        assertEquals(1, c.json["requests"].asInt())
        assertTrue(c.json["record"].isNull, "기록이 없어야 한다: $c")

        // 같은 키를 다시 보내도 지정이 남아 있으면 503
        assertEquals(503, cancel(k, 7000).status)
        // 지정을 승인으로 바꾸면 그때 기록(그 요청의 금액)
        control(k, "APPROVE")
        val ok = cancel(k, 6000)
        assertEquals(200, ok.status)
        assertEquals(6000L, ok.json["amount"].asLong())
        assertEquals(6000L, pg.cancelRecord(k)?.amount)
    }

    @Test
    fun `기록된 키에 UNAVAILABLE을 지정해도 첫 기록을 돌려준다`() {
        val k = key()
        val first = cancel(k, 7000)
        control(k, "UNAVAILABLE")
        val second = cancel(k, 7000)
        assertEquals(200, second.status)
        assertEquals(first.json["pgTxId"].asText(), second.json["pgTxId"].asText())
    }

    @Test
    fun `결과는 키별 - 다른 키 취소에 영향 없음`() {
        val a = key()
        val b = key()
        control(a, "UNAVAILABLE")
        assertEquals(503, cancel(a, 1000).status)
        assertEquals(200, cancel(b, 1000).status)
    }

    @Test
    fun `취소는 승인 기록과 분리 - 승인 재조회 404, 승인 요청 수 0`() {
        val k = key()
        cancel(k, 7000)
        assertEquals(404, Api.request("GET", "/approvals/$k", base = pg.url).status)
        assertEquals(0, pg.approvalRequests(k).size)
        assertEquals(null, pg.record(k))
    }

    @Test
    fun `승인 요청에 UNAVAILABLE - 즉시 503, 기록 없음`() {
        val k = "infra-" + UUID.randomUUID()
        control(k, "UNAVAILABLE")
        val r = Api.request("POST", "/approvals", mapOf("idempotencyKey" to k, "amount" to 1000), base = pg.url)
        assertEquals(503, r.status)
        assertEquals(null, pg.record(k))
        assertEquals(404, Api.request("GET", "/approvals/$k", base = pg.url).status)
    }

    @Test
    fun `취소 요청 형식 오류는 400, 요청·기록 없음`() {
        val k = key()
        val bad = listOf(
            mapOf("idempotencyKey" to k, "approvalKey" to "o"),
            mapOf("idempotencyKey" to k, "approvalKey" to "o", "amount" to "1000"),
            mapOf("idempotencyKey" to k, "approvalKey" to "o", "amount" to 10.5),
            mapOf("idempotencyKey" to k, "amount" to 1000),
            mapOf("approvalKey" to "o", "amount" to 1000),
            mapOf("idempotencyKey" to 7, "approvalKey" to "o", "amount" to 1000),
        )
        bad.forEach { assertEquals(400, Api.request("POST", "/cancellations", it, base = pg.url).status, "거부되어야 한다: $it") }
        assertEquals(400, Api.request("POST", "/cancellations", rawBody = "{", base = pg.url).status)
        val c = cancellations(k)
        assertEquals(0, c.json["requests"].asInt())
        assertTrue(c.json["record"].isNull)
    }
}
