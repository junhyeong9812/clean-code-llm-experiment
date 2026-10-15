package com.ccle.behavior.infra

import com.ccle.behavior.pg.FakePg
import com.ccle.behavior.support.Api
import com.ccle.behavior.support.InfraTest
import com.ccle.behavior.support.PgClient
import com.ccle.behavior.support.Resp
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 가짜 PG 자체 테스트(ADR-0003 §6). 별도 인스턴스를 임의 포트·짧은 지연으로 띄운다. */
@InfraTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FakePgTest {
    private val delayMs = 400L
    private val pg = FakePg(0, delayMs).start()

    @AfterAll
    fun stop() = pg.close()

    private fun key() = "infra-" + UUID.randomUUID()
    private fun control(key: String, outcome: String): Resp =
        Api.request("POST", "/_control/outcomes", mapOf("idempotencyKey" to key, "outcome" to outcome), base = pg.url)
    private fun approve(key: String, amount: Long = 1000): Resp =
        Api.request("POST", "/approvals", mapOf("idempotencyKey" to key, "amount" to amount), base = pg.url)
    private fun inquire(key: String): Resp = Api.request("GET", "/approvals/$key", base = pg.url)

    private fun timed(block: () -> Resp): Pair<Resp, Long> {
        val t0 = System.nanoTime()
        val r = block()
        return r to (System.nanoTime() - t0) / 1_000_000
    }

    @Test
    fun `지정 없는 키는 APPROVE - 승인, 재조회 APPROVED`() {
        val k = key()
        val r = approve(k, 12345)
        assertEquals(200, r.status, r.toString())
        assertEquals("APPROVED", r.json["status"].asText())
        val tx = r.json["pgTxId"].asText()
        assertTrue(tx.isNotBlank())
        val q = inquire(k)
        assertEquals(200, q.status)
        assertEquals("APPROVED", q.json["status"].asText())
        assertEquals(tx, q.json["pgTxId"].asText())
        assertEquals(listOf(FakePg.ApprovalRequest(k, 12345L)), pg.approvalRequests(k))
    }

    @Test
    fun `DECLINE - 거절, 재조회 DECLINED`() {
        val k = key()
        assertEquals(200, control(k, "DECLINE").status)
        val r = approve(k)
        assertEquals(200, r.status)
        assertEquals("DECLINED", r.json["status"].asText())
        val q = inquire(k)
        assertEquals(200, q.status)
        assertEquals("DECLINED", q.json["status"].asText())
    }

    @Test
    fun `TIMEOUT_APPROVED - 지연 뒤 응답, 재조회 APPROVED`() {
        val k = key()
        control(k, "TIMEOUT_APPROVED")
        val (r, ms) = timed { approve(k) }
        assertTrue(ms >= delayMs, "지연(${delayMs}ms) 뒤 응답이어야 한다: ${ms}ms")
        assertEquals(200, r.status)
        assertEquals("APPROVED", r.json["status"].asText())
        val q = inquire(k)
        assertEquals(200, q.status)
        assertEquals("APPROVED", q.json["status"].asText())
        assertEquals(r.json["pgTxId"].asText(), q.json["pgTxId"].asText())
    }

    @Test
    fun `TIMEOUT_NOT_RECEIVED - 지연 뒤 응답, 기록 없음, 재조회 404`() {
        val k = key()
        control(k, "TIMEOUT_NOT_RECEIVED")
        val (r, ms) = timed { approve(k) }
        assertTrue(ms >= delayMs, "지연 뒤 응답이어야 한다: ${ms}ms")
        assertTrue(r.status >= 500, "승인 결과를 주지 않는다: $r")
        assertEquals(404, inquire(k).status)
        assertEquals(null, pg.record(k))
    }

    @Test
    fun `같은 키 두 번째 승인 요청은 첫 기록을 그대로 돌려준다`() {
        val k = key()
        val first = approve(k)
        control(k, "DECLINE") // 결과를 바꿔도 이미 기록된 키는 첫 기록
        val second = approve(k)
        assertEquals(first.json["pgTxId"].asText(), second.json["pgTxId"].asText())
        assertEquals("APPROVED", second.json["status"].asText())
        assertEquals(2, pg.approvalRequests(k).size)
    }

    @Test
    fun `결과는 멱등 키별 - 다른 키에 영향 없음`() {
        val a = key()
        val b = key()
        control(a, "DECLINE")
        assertEquals("DECLINED", approve(a).json["status"].asText())
        assertEquals("APPROVED", approve(b).json["status"].asText())
    }

    @Test
    fun `승인 요청이 없던 키 재조회는 404`() {
        assertEquals(404, inquire(key()).status)
    }

    @Test
    fun `제어 요청 형식 오류는 400`() {
        assertEquals(400, control(key(), "MAYBE").status)
        assertEquals(400, Api.request("POST", "/_control/outcomes", mapOf("outcome" to "APPROVE"), base = pg.url).status)
        assertEquals(400, Api.request("POST", "/approvals", rawBody = "{", base = pg.url).status)
    }

    @Test
    fun `승인 요청 형식 오류 - idempotencyKey·amount 누락 또는 정수 아님은 400, 기록·카운트 없음`() {
        val k = key()
        val bad = listOf(
            mapOf("idempotencyKey" to k),
            mapOf("idempotencyKey" to k, "amount" to "1000"),
            mapOf("idempotencyKey" to k, "amount" to 10.5),
            mapOf("amount" to 1000),
            mapOf("idempotencyKey" to 7, "amount" to 1000),
        )
        bad.forEach { assertEquals(400, Api.request("POST", "/approvals", it, base = pg.url).status, "거부되어야 한다: $it") }
        assertEquals(0, PgClient(pg.url).approvalCount(k))
        assertEquals(404, inquire(k).status)
    }

    @Test
    fun `요청 조회 제어 - 승인 수·재조회 수·받은 금액·기록`() {
        val k = key()
        val client = PgClient(pg.url)
        assertEquals(PgClient.Requests(0, 0, emptyList(), null, null), client.requests(k))
        val tx = approve(k, 5000).json["pgTxId"].asText()
        approve(k, 5000)
        inquire(k)
        assertEquals(PgClient.Requests(2, 1, listOf(5000L, 5000L), tx, "APPROVED"), client.requests(k))
    }

    @Test
    fun `재조회 지연 제어 - 지연 뒤 응답`() {
        val k = key()
        approve(k)
        val r = Api.request("POST", "/_control/inquiry-outcomes", mapOf("idempotencyKey" to k, "outcome" to "TIMEOUT"), base = pg.url)
        assertEquals(200, r.status)
        val (q, ms) = timed { inquire(k) }
        assertTrue(ms >= delayMs, "지연 뒤 응답이어야 한다: ${ms}ms")
        assertEquals(200, q.status)
        assertEquals(1, pg.inquiryCount(k))
    }

    @Test
    fun `기본 설정 - 포트 15480, 지연 3000ms (앱 PG_TIMEOUT_MS 2000보다 늦게)`() {
        assertEquals(15480, FakePg.DEFAULT_PORT)
        assertTrue(FakePg.DEFAULT_DELAY_MS > 2000)
        assertNotNull(pg.url)
    }
}
