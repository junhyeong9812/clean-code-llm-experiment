package com.ccle.layered.infra

import com.ccle.layered.domain.PaymentGateway
import com.ccle.layered.domain.PgReply
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

/** PG HTTP 호출만 한다(ADR-0003 §6). 타임아웃·연결 실패 등은 예외를 그대로 던진다. 200 본문은 JSON으로 읽어 둔다. */
@Component
class PgHttpClient(
    @Value("\${PG_URL:http://127.0.0.1:15480}") pgUrl: String,
    @Value("\${PG_TIMEOUT_MS:2000}") timeoutMs: Long,
    private val json: ObjectMapper,
) : PaymentGateway {
    private val baseUrl = pgUrl.trimEnd('/')

    override val timeout: Duration = Duration.ofMillis(timeoutMs)

    private val http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(timeout)
        .build()

    override fun postApproval(idempotencyKey: String, amount: Long): PgReply {
        val body = json.writeValueAsString(mapOf("idempotencyKey" to idempotencyKey, "amount" to amount))
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/approvals"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return reply(http.send(request, HttpResponse.BodyHandlers.ofString()))
    }

    override fun getApproval(idempotencyKey: String): PgReply {
        val path = URLEncoder.encode(idempotencyKey, StandardCharsets.UTF_8).replace("+", "%20")
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/approvals/$path"))
            .timeout(timeout)
            .GET()
            .build()
        return reply(http.send(request, HttpResponse.BodyHandlers.ofString()))
    }

    private fun reply(response: HttpResponse<String>): PgReply {
        val raw = response.body() ?: ""
        if (response.statusCode() != 200) return PgReply(response.statusCode(), raw, readable = false, status = null, pgTxId = null)
        val node = try {
            json.readTree(raw)
        } catch (e: Exception) {
            return PgReply(200, raw, readable = false, status = null, pgTxId = null)
        }
        return PgReply(
            httpStatus = 200,
            raw = raw,
            readable = true,
            status = node?.path("status")?.asText(),
            pgTxId = node?.path("pgTxId")?.takeIf { it.isTextual }?.asText(),
        )
    }
}
