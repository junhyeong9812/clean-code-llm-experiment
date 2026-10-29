package com.ccle.layered.api

import com.ccle.layered.service.InquiryService
import com.ccle.layered.service.UserService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/** 고객 문의 작성·추가 메시지·답변(관리자)·종결·조회(ADR-0007 §9). */
@RestController
class InquiryController(private val users: UserService, private val inquiries: InquiryService) {

    @PostMapping("/me/inquiries")
    fun create(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @RequestBody(required = false) body: String?,
    ): ResponseEntity<InquiryView> {
        val userId = users.requireUser(userIdOf(userHeader))
        val json = parseObject(body)
        val inquiry = inquiries.create(
            userId,
            json.textOrNull("category"),
            json.textOrNull("title"),
            json.textOrNull("body"),
            json.longOrNull("orderItemId"),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(InquiryView(inquiry))
    }

    @PostMapping("/me/inquiries/{inquiryId}/messages")
    fun message(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable inquiryId: String,
        @RequestBody(required = false) body: String?,
    ): InquiryView {
        val userId = users.requireUser(userIdOf(userHeader))
        val json = parseObject(body)
        return InquiryView(inquiries.addMessage(userId, pathId(inquiryId), json.textOrNull("body")))
    }

    @PostMapping("/admin/inquiries/{inquiryId}/answer")
    fun answer(@PathVariable inquiryId: String, @RequestBody(required = false) body: String?): InquiryView {
        val json = parseObject(body)
        return InquiryView(inquiries.answer(pathId(inquiryId), json.textOrNull("body")))
    }

    @PostMapping("/me/inquiries/{inquiryId}/close")
    fun close(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable inquiryId: String,
    ): InquiryView {
        val userId = users.requireUser(userIdOf(userHeader))
        return InquiryView(inquiries.close(userId, pathId(inquiryId)))
    }

    @GetMapping("/me/inquiries")
    fun list(@RequestHeader("X-User-Id", required = false) userHeader: String?): List<InquiryView> =
        inquiries.list(users.requireUser(userIdOf(userHeader))).map { InquiryView(it) }

    @GetMapping("/me/inquiries/{inquiryId}")
    fun detail(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable inquiryId: String,
    ): InquiryDetailView {
        val userId = users.requireUser(userIdOf(userHeader))
        return InquiryDetailView(inquiries.detail(userId, pathId(inquiryId)))
    }
}
