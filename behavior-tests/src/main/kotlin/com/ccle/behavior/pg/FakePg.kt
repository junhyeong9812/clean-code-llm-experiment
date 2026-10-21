package com.ccle.behavior.pg

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 가짜 PG (ADR-0003 §6).
 *
 * 앱 → PG
 *  - POST /approvals {"idempotencyKey","amount"} → 200 {"pgTxId","status": APPROVED|DECLINED}
 *  - GET  /approvals/{idempotencyKey}            → 200 {"pgTxId","status"} | 404 (기록 없음)
 *  - POST /cancellations {"idempotencyKey","approvalKey","amount"} → 200 {"pgTxId","status": APPROVED,"amount"}
 *    (ADR-0004 §7.2 카드 취소). 같은 키의 두 번째 요청은 첫 기록(첫 금액)을 그대로 돌려준다.
 * 테스트 → PG
 *  - POST /_control/outcomes {"idempotencyKey","outcome"}: APPROVE | DECLINE | TIMEOUT_APPROVED | TIMEOUT_NOT_RECEIVED.
 *    지정이 없는 키는 APPROVE. UNAVAILABLE(ADR-0004 §7.2) = 그 키의 요청에 즉시 503, 기록하지 않음.
 *    취소 요청(`cancel-<id>` 키)에는 UNAVAILABLE만 의미가 있고, 그 밖의 결과·지정 없음은 승인이다.
 *  - GET  /_control/requests/{idempotencyKey} → {"approvals": n, "inquiries": m, "amounts": [...], "record": {"pgTxId","status"}|null}
 *    (ADR 밖 보조 조회: 별도 프로세스로 띄운 PG의 카운터를 테스트가 HTTP로 단언한다)
 *  - POST /_control/inquiry-outcomes {"idempotencyKey","outcome": "TIMEOUT"} (ADR 밖 보조 제어):
 *    그 키의 재조회(GET)를 delayMs 뒤에 응답한다. ADR-0003 §7 pg-inquiry의 "응답 없음" 경로를 만들기 위해서다.
 *  - GET  /_control/cancellations/{idempotencyKey} → {"requests": n, "amounts": [...], "approvalKeys": [...],
 *    "record": {"pgTxId","status","amount","approvalKey"}|null} (ADR 밖 보조 조회: 받은 취소 요청·기록된 취소)
 *
 * TIMEOUT 계열은 앱의 PG_TIMEOUT_MS(기본 2000)보다 늦게(delayMs, 기본 3000) 응답한다.
 * 같은 키로 두 번째 승인 요청이 오면 첫 기록을 그대로 돌려준다(기록이 없으면 지정된 결과를 다시 적용한다).
 */
class FakePg(val port: Int, val delayMs: Long = DEFAULT_DELAY_MS) : AutoCloseable {

    enum class Outcome { APPROVE, DECLINE, TIMEOUT_APPROVED, TIMEOUT_NOT_RECEIVED, UNAVAILABLE }

    data class Record(val pgTxId: String, val status: String)

    /** 받은 승인 요청 1건(테스트가 "PG 호출 없음"·금액을 단언하는 데 쓴다). */
    data class ApprovalRequest(val idempotencyKey: String, val amount: Long)

    /** 기록된 카드 취소 1건(ADR-0004 §7.2). amount = 첫 요청의 금액. */
    data class CancelRecord(val pgTxId: String, val status: String, val amount: Long, val approvalKey: String)

    /** 받은 취소 요청 1건(형식이 맞는 것만, UNAVAILABLE로 거절된 것 포함). */
    data class CancelRequest(val idempotencyKey: String, val approvalKey: String, val amount: Long)

    private val mapper = jacksonObjectMapper()
    private val outcomes = ConcurrentHashMap<String, Outcome>()
    private val inquiryTimeouts = ConcurrentHashMap.newKeySet<String>()
    private val records = ConcurrentHashMap<String, Record>()
    private val approvalRequests = CopyOnWriteArrayList<ApprovalRequest>()
    private val inquiryRequests = CopyOnWriteArrayList<String>()
    private val cancelRecords = ConcurrentHashMap<String, CancelRecord>()
    private val cancelRequests = CopyOnWriteArrayList<CancelRequest>()
    private val seq = AtomicLong()
    private val executor = Executors.newCachedThreadPool { r -> Thread(r, "fake-pg").apply { isDaemon = true } }
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 64)

    /** 실제로 열린 포트(port = 0이면 임의 포트). */
    val boundPort: Int get() = server.address.port
    val url: String get() = "http://127.0.0.1:$boundPort"

    init {
        server.executor = executor
        server.createContext("/") { ex ->
            try {
                route(ex)
            } catch (e: Exception) {
                send(ex, 500, mapOf("error" to (e.message ?: e.javaClass.simpleName)))
            } finally {
                ex.close()
            }
        }
    }

    fun start(): FakePg = apply { server.start() }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    // ---- 테스트가 JVM 안에서 직접 보는 값 ----
    fun setOutcome(key: String, outcome: Outcome) { outcomes[key] = outcome }
    fun approvalRequests(key: String): List<ApprovalRequest> = approvalRequests.filter { it.idempotencyKey == key }
    fun inquiryCount(key: String): Int = inquiryRequests.count { it == key }
    fun record(key: String): Record? = records[key]
    fun cancelRequests(key: String): List<CancelRequest> = cancelRequests.filter { it.idempotencyKey == key }
    fun cancelRecord(key: String): CancelRecord? = cancelRecords[key]

    private fun route(ex: HttpExchange) {
        val path = ex.requestURI.path
        val method = ex.requestMethod
        when {
            method == "POST" && path == "/approvals" -> approve(ex)
            method == "GET" && path.startsWith("/approvals/") -> inquire(ex, path.removePrefix("/approvals/"))
            method == "POST" && path == "/cancellations" -> cancel(ex)
            method == "POST" && path == "/_control/outcomes" -> controlOutcome(ex)
            method == "POST" && path == "/_control/inquiry-outcomes" -> controlInquiry(ex)
            method == "GET" && path.startsWith("/_control/requests/") -> controlRequests(ex, path.removePrefix("/_control/requests/"))
            method == "GET" && path.startsWith("/_control/cancellations/") -> controlCancellations(ex, path.removePrefix("/_control/cancellations/"))
            else -> send(ex, 404, mapOf("error" to "no route $method $path"))
        }
    }

    private fun readJson(ex: HttpExchange): JsonNode? =
        runCatching { mapper.readTree(ex.requestBody.readAllBytes()) }.getOrNull()

    private fun approve(ex: HttpExchange) {
        val body = readJson(ex)
        val key = body?.get("idempotencyKey")?.takeIf { it.isTextual }?.asText()
        val amountNode = body?.get("amount")
        if (key.isNullOrEmpty() || amountNode == null || !amountNode.isIntegralNumber) {
            return send(ex, 400, mapOf("error" to "idempotencyKey(문자열)와 amount(정수) 필요"))
        }
        approvalRequests += ApprovalRequest(key, amountNode.asLong())

        records[key]?.let { return send(ex, 200, it.toBody()) }

        when (outcomes[key] ?: Outcome.APPROVE) {
            Outcome.APPROVE -> send(ex, 200, recordOnce(key, "APPROVED").toBody())
            Outcome.DECLINE -> send(ex, 200, recordOnce(key, "DECLINED").toBody())
            Outcome.TIMEOUT_APPROVED -> {
                val rec = recordOnce(key, "APPROVED")
                Thread.sleep(delayMs)
                send(ex, 200, rec.toBody())
            }
            Outcome.TIMEOUT_NOT_RECEIVED -> {
                Thread.sleep(delayMs)
                send(ex, 503, mapOf("error" to "not received"))
            }
            Outcome.UNAVAILABLE -> send(ex, 503, mapOf("error" to "unavailable"))
        }
    }

    /** 카드 취소(ADR-0004 §7.2). 기록이 있으면 첫 기록(첫 금액)을, UNAVAILABLE이면 503(기록 없음), 그 밖은 승인·기록. */
    private fun cancel(ex: HttpExchange) {
        val body = readJson(ex)
        val key = body?.get("idempotencyKey")?.takeIf { it.isTextual }?.asText()
        val approvalKey = body?.get("approvalKey")?.takeIf { it.isTextual }?.asText()
        val amountNode = body?.get("amount")
        if (key.isNullOrEmpty() || approvalKey.isNullOrEmpty() || amountNode == null || !amountNode.isIntegralNumber) {
            return send(ex, 400, mapOf("error" to "idempotencyKey·approvalKey(문자열)와 amount(정수) 필요"))
        }
        val amount = amountNode.asLong()
        cancelRequests += CancelRequest(key, approvalKey, amount)

        cancelRecords[key]?.let { return send(ex, 200, it.toBody()) }
        if (outcomes[key] == Outcome.UNAVAILABLE) return send(ex, 503, mapOf("error" to "unavailable"))
        val rec = cancelRecords.computeIfAbsent(key) {
            CancelRecord("fakepg-cancel-${seq.incrementAndGet()}-${System.nanoTime()}", "APPROVED", amount, approvalKey)
        }
        send(ex, 200, rec.toBody())
    }

    private fun recordOnce(key: String, status: String): Record =
        records.computeIfAbsent(key) { Record("fakepg-${seq.incrementAndGet()}-${System.nanoTime()}", status) }

    private fun inquire(ex: HttpExchange, key: String) {
        inquiryRequests += key
        if (key in inquiryTimeouts) Thread.sleep(delayMs)
        val rec = records[key] ?: return send(ex, 404, mapOf("error" to "not found"))
        send(ex, 200, rec.toBody())
    }

    private fun controlOutcome(ex: HttpExchange) {
        val body = readJson(ex)
        val key = body?.get("idempotencyKey")?.takeIf { it.isTextual }?.asText()
        val outcome = body?.get("outcome")?.asText()?.let { o -> Outcome.entries.firstOrNull { it.name == o } }
        if (key.isNullOrEmpty() || outcome == null) return send(ex, 400, mapOf("error" to "idempotencyKey and outcome required"))
        outcomes[key] = outcome
        send(ex, 200, mapOf("idempotencyKey" to key, "outcome" to outcome.name))
    }

    /** 그 키로 받은 요청 요약: 승인 요청 수·재조회 수·받은 금액(순서대로)·현재 기록. */
    private fun controlRequests(ex: HttpExchange, key: String) {
        val approvals = approvalRequests(key)
        send(ex, 200, mapOf(
            "approvals" to approvals.size,
            "inquiries" to inquiryCount(key),
            "amounts" to approvals.map { it.amount },
            "record" to records[key]?.toBody(),
        ))
    }

    /** 그 키로 받은 취소 요청 요약: 요청 수·받은 금액·승인 키(순서대로)·현재 기록. */
    private fun controlCancellations(ex: HttpExchange, key: String) {
        val reqs = cancelRequests(key)
        send(ex, 200, mapOf(
            "requests" to reqs.size,
            "amounts" to reqs.map { it.amount },
            "approvalKeys" to reqs.map { it.approvalKey },
            "record" to cancelRecords[key]?.let { it.toBody() + ("approvalKey" to it.approvalKey) },
        ))
    }

    private fun controlInquiry(ex: HttpExchange) {
        val body = readJson(ex)
        val key = body?.get("idempotencyKey")?.takeIf { it.isTextual }?.asText()
        val outcome = body?.get("outcome")?.asText()
        if (key.isNullOrEmpty() || outcome !in setOf("TIMEOUT", "NORMAL")) {
            return send(ex, 400, mapOf("error" to "idempotencyKey and outcome(TIMEOUT|NORMAL) required"))
        }
        if (outcome == "TIMEOUT") inquiryTimeouts += key else inquiryTimeouts -= key
        send(ex, 200, mapOf("idempotencyKey" to key, "outcome" to outcome))
    }

    private fun Record.toBody() = mapOf("pgTxId" to pgTxId, "status" to status)
    private fun CancelRecord.toBody() = mapOf("pgTxId" to pgTxId, "status" to status, "amount" to amount)

    private fun send(ex: HttpExchange, status: Int, body: Any) {
        val bytes = mapper.writeValueAsBytes(body)
        ex.responseHeaders.add("Content-Type", "application/json")
        try {
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        } catch (_: java.io.IOException) {
            // 클라이언트(앱)가 타임아웃으로 먼저 끊은 경우: 기록은 이미 끝났다
        }
    }

    companion object {
        const val DEFAULT_PORT = 15480
        const val DEFAULT_DELAY_MS = 3000L
    }
}

/** 단독 실행: env PG_PORT(기본 15480), PG_DELAY_MS(기본 3000). 준비되면 stdout에 `fake-pg ready <port>` 1줄. */
fun main() {
    val port = System.getenv("PG_PORT")?.toInt() ?: FakePg.DEFAULT_PORT
    val delay = System.getenv("PG_DELAY_MS")?.toLong() ?: FakePg.DEFAULT_DELAY_MS
    val pg = FakePg(port, delay).start()
    Runtime.getRuntime().addShutdownHook(Thread { pg.close() })
    // run.sh가 이 줄로 준비 완료를 판정한다
    println("fake-pg ready ${pg.boundPort}")
    System.out.flush()
    Thread.currentThread().join()
}
