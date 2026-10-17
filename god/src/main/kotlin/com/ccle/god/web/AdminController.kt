package com.ccle.god.web

import com.ccle.god.Marketplace
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

// 응답 시각은 KST 오프셋을 붙인 ISO-8601(초는 0이어도 쓴다). ADR-0003 §2.
private val KST: ZoneOffset = ZoneOffset.ofHours(9)

fun iso(t: Instant): String = OffsetDateTime.ofInstant(t, KST).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

/** 관리 엔드포인트(ADR-0003 §7, 헤더 불필요). */
@RestController
@RequestMapping("/admin")
class AdminController(private val marketplace: Marketplace) {

    @GetMapping("/clock")
    fun clock(): Map<String, String> = mapOf("now" to iso(marketplace.clockNow()))

    @PutMapping("/clock")
    fun moveClock(@RequestBody body: Map<String, Any?>): Map<String, String> {
        val raw = body["now"] as? String
            ?: throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "now(오프셋 포함 ISO-8601 문자열)가 필요하다")
        val target = try {
            OffsetDateTime.parse(raw).toInstant()
        } catch (e: DateTimeParseException) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "now 형식 오류: $raw")
        }
        return mapOf("now" to iso(marketplace.moveClock(target)))
    }
}
