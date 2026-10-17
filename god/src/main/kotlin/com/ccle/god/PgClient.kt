package com.ccle.god

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * PG가 돌려준 승인 기록(ADR-0003 §6). status는 PG 값 그대로(APPROVED|DECLINED).
 * 재조회에서 PG가 404(승인 요청이 기록되지 않음)를 주면 status = NOT_FOUND. 해석은 Marketplace가 한다.
 */
data class PgReply(val status: String, val pgTxId: String?)

/**
 * PG HTTP 호출만 한다(ADR-0003 §6). 결과 해석·재시도·상태 반영은 Marketplace에 있다.
 * 응답 없음(PG_TIMEOUT_MS 초과·연결 실패·200이 아닌 응답·읽을 수 없는 본문)은 null.
 */
@Component
class PgClient(
    @Value("\${PG_URL:http://127.0.0.1:15480}") pgUrl: String,
    @Value("\${PG_TIMEOUT_MS:2000}") timeoutMs: Long,
    private val json: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(PgClient::class.java)
    private val baseUrl = pgUrl.trimEnd('/')
    private val timeout = Duration.ofMillis(timeoutMs)
    private val http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(timeout)
        .build()

    /** 승인 요청: `POST {PG_URL}/approvals {"idempotencyKey","amount"}`. */
    fun approve(idempotencyKey: String, amount: Long): PgReply? {
        val body = json.writeValueAsString(mapOf("idempotencyKey" to idempotencyKey, "amount" to amount))
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/approvals"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return send(request, "승인 $idempotencyKey")
    }

    /** 재조회: `GET {PG_URL}/approvals/{idempotencyKey}`. 404면 NOT_FOUND. */
    fun inquire(idempotencyKey: String): PgReply? {
        val path = URLEncoder.encode(idempotencyKey, StandardCharsets.UTF_8).replace("+", "%20")
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/approvals/$path"))
            .timeout(timeout)
            .GET()
            .build()
        return send(request, "재조회 $idempotencyKey")
    }

    private fun send(request: HttpRequest, what: String): PgReply? {
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            // 타임아웃(HttpTimeoutException)·연결 실패
            log.warn("PG 응답 없음({}): {}", what, e.toString())
            return null
        }
        if (response.statusCode() == 404 && request.method() == "GET") return PgReply("NOT_FOUND", null)
        if (response.statusCode() != 200) {
            log.warn("PG 응답 없음({}): HTTP {}", what, response.statusCode())
            return null
        }
        return try {
            val node = json.readTree(response.body())
            val status = node.path("status").asText(null) ?: throw IOException("status 없음")
            PgReply(status, node.path("pgTxId").asText(null))
        } catch (e: IOException) {
            log.warn("PG 응답을 읽을 수 없음({}): {} — 응답 없음으로 본다", what, response.body())
            null
        }
    }
}
