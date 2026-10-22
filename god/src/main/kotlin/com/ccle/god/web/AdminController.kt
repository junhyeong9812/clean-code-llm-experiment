package com.ccle.god.web

import com.ccle.god.Marketplace
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** 관리 엔드포인트(ADR-0003 §7, 헤더 불필요)와 센터 절차(ADR-0004 §3). */
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

    @PostMapping("/batches/{name}")
    fun runBatch(@PathVariable name: String): Map<String, Int> = mapOf("processed" to marketplace.runBatch(name))

    @PostMapping("/order-items/{itemId}/receipt")
    fun receive(@PathVariable itemId: String): ItemView = marketplace.receiveItem(pathId(itemId))

    @PostMapping("/order-items/{itemId}/inspection")
    fun inspect(@PathVariable itemId: String, @RequestBody(required = false) body: String?): ItemView {
        val json = parseObject(body)
        return marketplace.inspectItem(pathId(itemId), json.textOrNull("result"), json.textOrNull("reason"))
    }

    @PostMapping("/order-items/{itemId}/delivery")
    fun deliver(@PathVariable itemId: String): ItemView = marketplace.deliverItem(pathId(itemId))
}
