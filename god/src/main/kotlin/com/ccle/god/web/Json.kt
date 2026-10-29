package com.ccle.god.web

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializerProvider
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

// 요청 본문 파싱과 응답 시각 형식. 규칙은 없다(값이 있는지·범위 안인지는 Marketplace가 본다).
//
// 헤더가 필요한 엔드포인트는 본문을 문자열로 받아 X-User-Id 확인(401) 뒤에 여기서 파싱한다(판정 순서 401 → 400, ADR-0003 §2).
// Jackson 바인딩을 쓰지 않으므로 "100000"(문자열)·100000.5(실수)가 숫자로 바뀌어 들어오는 일이 없다: 정수 JSON 숫자만 받는다.

// 응답 시각은 KST 오프셋을 붙인 ISO-8601(초는 0이어도 쓴다). ADR-0003 §2.
private val KST: ZoneOffset = ZoneOffset.ofHours(9)

fun iso(t: Instant): String = OffsetDateTime.ofInstant(t, KST).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

/** 응답 DTO의 Instant 필드를 iso()로 쓴다. */
@Configuration
class JsonConfig {
    @Bean
    fun instantAsKst(): Jackson2ObjectMapperBuilderCustomizer = Jackson2ObjectMapperBuilderCustomizer { builder ->
        builder.serializerByType(Instant::class.java, object : JsonSerializer<Instant>() {
            override fun serialize(value: Instant, gen: JsonGenerator, serializers: SerializerProvider) {
                gen.writeString(iso(value))
            }
        })
    }
}

fun invalid(message: String): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message)

private val reader = ObjectMapper()

/** 본문이 JSON 객체여야 한다. */
fun parseObject(body: String?): JsonNode {
    if (body.isNullOrBlank()) invalid("요청 본문이 없다")
    val node = try {
        reader.readTree(body)
    } catch (e: JsonProcessingException) {
        invalid("JSON 형식 오류: ${e.originalMessage}")
    }
    if (node == null || !node.isObject) invalid("요청 본문은 JSON 객체여야 한다")
    return node
}

/** 정수 필드. 없거나 null이면 null, 정수 JSON 숫자가 아니면 400. */
fun JsonNode.longOrNull(field: String): Long? {
    val n = get(field)
    if (n == null || n.isNull) return null
    if (!n.isIntegralNumber || !n.canConvertToLong()) invalid("$field 는 정수여야 한다")
    return n.longValue()
}

/** 문자열 필드. 없거나 null이면 null, 문자열이 아니면 400. */
fun JsonNode.textOrNull(field: String): String? {
    val n = get(field)
    if (n == null || n.isNull) return null
    if (!n.isTextual) invalid("$field 는 문자열이어야 한다")
    return n.textValue()
}

/** 불리언 필드. 없거나 null이면 null, true/false가 아니면 400. */
fun JsonNode.booleanOrNull(field: String): Boolean? {
    val n = get(field)
    if (n == null || n.isNull) return null
    if (!n.isBoolean) invalid("$field 는 true 또는 false여야 한다")
    return n.booleanValue()
}

/** 시각 필드(ADR-0007 §19의 5). 없거나 null이면 null, 오프셋 포함 ISO-8601 문자열이 아니면 400. */
fun JsonNode.instantOrNull(field: String): Instant? {
    val text = textOrNull(field) ?: return null
    return try {
        OffsetDateTime.parse(text).toInstant()
    } catch (e: DateTimeParseException) {
        invalid("$field 는 오프셋 포함 ISO-8601 시각이어야 한다: $text")
    }
}

/** 정수 배열 필드. 없거나 null이면 null, 정수 배열이 아니면 400. */
fun JsonNode.longListOrNull(field: String): List<Long>? {
    val n = get(field)
    if (n == null || n.isNull) return null
    if (!n.isArray) invalid("$field 는 정수 배열이어야 한다")
    return n.map { e ->
        if (!e.isIntegralNumber || !e.canConvertToLong()) invalid("$field 의 원소는 정수여야 한다")
        e.longValue()
    }
}

/** 경로의 id. 정수가 아니면 400. */
fun pathId(raw: String): Long = raw.toLongOrNull() ?: invalid("id는 정수여야 한다: $raw")
