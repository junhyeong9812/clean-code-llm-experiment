package com.ccle.layered.api

import com.ccle.layered.service.BatchService
import com.ccle.layered.service.ClockService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** 관리 엔드포인트(ADR-0003 §7, 헤더 불필요). */
@RestController
@RequestMapping("/admin")
class AdminController(private val clock: ClockService, private val batches: BatchService) {

    @GetMapping("/clock")
    fun clock(): Map<String, String> = mapOf("now" to iso(clock.now()))

    @PutMapping("/clock")
    fun moveClock(@RequestBody body: Map<String, Any?>): Map<String, String> {
        val raw = body["now"] as? String ?: invalid("now(오프셋 포함 ISO-8601 문자열)가 필요하다")
        val target = try {
            OffsetDateTime.parse(raw).toInstant()
        } catch (e: DateTimeParseException) {
            invalid("now 형식 오류: $raw")
        }
        return mapOf("now" to iso(clock.moveTo(target)))
    }

    @PostMapping("/batches/{name}")
    fun runBatch(@PathVariable name: String): Map<String, Int> = mapOf("processed" to batches.run(name))
}
