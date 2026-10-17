package com.ccle.god

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** PG HTTP 응답 그대로(상태 코드, 본문). */
data class PgResponse(val status: Int, val body: String)

/**
 * PG HTTP 호출만 한다(ADR-0003 §6). 응답을 그대로 돌려주고, 타임아웃·연결 실패 등은 예외를 그대로 던진다.
 * 결과 해석(200의 status·404·그 밖의 코드·예외 = 응답 없음)과 재시도·상태 반영은 Marketplace에 있다.
 */
@Component
class PgClient(
    @Value("\${PG_URL:http://127.0.0.1:15480}") pgUrl: String,
    @Value("\${PG_TIMEOUT_MS:2000}") timeoutMs: Long,
    private val json: ObjectMapper,
) {
    private val baseUrl = pgUrl.trimEnd('/')

    /** 요청 하나의 타임아웃. 연결에도 같은 값을 쓰므로 호출 하나는 최대 약 2배까지 걸릴 수 있다. */
    val timeout: Duration = Duration.ofMillis(timeoutMs)

    private val http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(timeout)
        .build()

    /** 승인 요청: `POST {PG_URL}/approvals {"idempotencyKey","amount"}`. */
    fun postApproval(idempotencyKey: String, amount: Long): PgResponse {
        val body = json.writeValueAsString(mapOf("idempotencyKey" to idempotencyKey, "amount" to amount))
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/approvals"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return PgResponse(response.statusCode(), response.body() ?: "")
    }

    /** 재조회: `GET {PG_URL}/approvals/{idempotencyKey}`. */
    fun getApproval(idempotencyKey: String): PgResponse {
        val path = URLEncoder.encode(idempotencyKey, StandardCharsets.UTF_8).replace("+", "%20")
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/approvals/$path"))
            .timeout(timeout)
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return PgResponse(response.statusCode(), response.body() ?: "")
    }
}
