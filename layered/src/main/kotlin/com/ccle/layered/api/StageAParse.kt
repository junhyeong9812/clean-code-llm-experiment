package com.ccle.layered.api

import com.ccle.layered.domain.dateInput
import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

// 단계 A(ADR-0007) 요청 본문의 시각·날짜 필드 파싱. Json.kt의 도우미와 같은 관례(형식 오류 = 400).

/** 시각 필드(ADR-0007 §19의 5). 없거나 null이면 null, 오프셋 포함 ISO-8601 문자열이 아니면 400. */
internal fun JsonNode.instantOrNull(field: String): Instant? {
    val text = textOrNull(field) ?: return null
    return try {
        OffsetDateTime.parse(text).toInstant()
    } catch (e: DateTimeParseException) {
        invalid("$field 는 오프셋 포함 ISO-8601 시각이어야 한다: $text")
    }
}

/** 날짜 필드(ADR-0004 §2.1 "YYYY-MM-DD", ADR-0007 §19의 5). 없거나 null이면 null, 그 형식의 실제 날짜 문자열이 아니면 400. */
internal fun JsonNode.dateOrNull(field: String): LocalDate? = dateInput(field, textOrNull(field))
