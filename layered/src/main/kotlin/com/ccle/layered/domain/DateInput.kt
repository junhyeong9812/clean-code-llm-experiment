package com.ccle.layered.domain

import java.time.LocalDate
import java.time.format.DateTimeParseException

private val YMD = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

/**
 * 날짜 입력(ADR-0004 §2.1 "YYYY-MM-DD", ADR-0007 §19의 5). 없으면 null, 정확히 그 형식의 실제 날짜가 아니면 400
 * (ISO 확장 연도 `+10000-01-01`·`-0001-01-01`과 없는 날짜 포함).
 */
fun dateInput(name: String, raw: String?): LocalDate? {
    if (raw == null) return null
    if (!YMD.matches(raw)) invalidRequest("$name 는 YYYY-MM-DD 날짜여야 한다: $raw")
    return try {
        LocalDate.parse(raw)
    } catch (e: DateTimeParseException) {
        invalidRequest("$name 는 YYYY-MM-DD 날짜여야 한다: $raw")
    }
}
