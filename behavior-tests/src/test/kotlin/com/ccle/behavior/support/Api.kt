package com.ccle.behavior.support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

val mapper = jacksonObjectMapper()

/** HTTP 응답 1건. 단언 실패 메시지에 요청·본문을 담아 원인을 바로 보이게 한다. */
class Resp(val method: String, val path: String, val status: Int, val body: String) {
    val json: JsonNode by lazy {
        if (body.isBlank()) NullNode.instance
        else runCatching { mapper.readTree(body) }.getOrElse { fail("JSON이 아닌 본문: $this") }
    }

    override fun toString() = "$method $path → $status ${body.take(500)}"

    fun assertStatus(expected: Int): Resp = apply {
        assertEquals(expected, status, "HTTP 상태: $this")
    }

    /** 오류 단언: HTTP 상태와 code를 함께 본다(ADR-0003 §2). */
    fun assertError(expected: Int, code: String): Resp = apply {
        assertEquals(expected, status, "HTTP 상태: $this")
        assertEquals(code, json.path("code").asText(null), "오류 code: $this")
    }

    fun long(field: String): Long {
        val n = json.get(field)
        assertTrue(n != null && n.isIntegralNumber, "정수 필드 '$field' 없음: $this")
        return n.asLong()
    }

    fun text(field: String): String {
        val n = json.get(field)
        assertTrue(n != null && n.isTextual, "문자열 필드 '$field' 없음: $this")
        return n.asText()
    }

    fun isNull(field: String): Boolean = json.has(field) && json.get(field).isNull
}

fun JsonNode.longAt(field: String): Long {
    val n = get(field)
    assertTrue(n != null && n.isIntegralNumber, "정수 필드 '$field' 없음: $this")
    return n.asLong()
}

fun JsonNode.textAt(field: String): String {
    val n = get(field)
    assertTrue(n != null && n.isTextual, "문자열 필드 '$field' 없음: $this")
    return n.asText()
}

/** ISO-8601 오프셋 포함 시각 → Instant. */
fun JsonNode.instantAt(field: String): Instant = OffsetDateTime.parse(textAt(field)).toInstant()

object Api {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    fun request(
        method: String,
        path: String,
        body: Any? = null,
        userId: Any? = null,
        idempotencyKey: String? = null,
        rawBody: String? = null,
        base: String = Env.baseUrl,
        timeoutMs: Long = Env.httpTimeoutMs,
    ): Resp {
        val payload = rawBody ?: body?.let { mapper.writeValueAsString(it) }
        val b = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Accept", "application/json")
        if (payload != null) b.header("Content-Type", "application/json")
        if (userId != null) b.header("X-User-Id", userId.toString())
        if (idempotencyKey != null) b.header("Idempotency-Key", idempotencyKey)
        val publisher = if (payload != null) HttpRequest.BodyPublishers.ofString(payload) else HttpRequest.BodyPublishers.noBody()
        b.method(method, publisher)
        val r = client.send(b.build(), HttpResponse.BodyHandlers.ofString())
        return Resp(method, path, r.statusCode(), r.body() ?: "")
    }

    fun get(path: String, userId: Any? = null) = request("GET", path, userId = userId)
    fun delete(path: String, userId: Any? = null) = request("DELETE", path, userId = userId)
    fun post(path: String, body: Any?, userId: Any? = null, idempotencyKey: String? = null) =
        request("POST", path, body, userId, idempotencyKey)
    fun put(path: String, body: Any?) = request("PUT", path, body)
}
