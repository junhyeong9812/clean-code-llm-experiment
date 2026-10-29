package com.ccle.god.web

import com.ccle.god.Marketplace
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 고객 문의 작성·추가 메시지·답변(관리자)·종결·조회(ADR-0007 §9), 재개·관리자 문의함(§20.4). */
@RestController
class InquiryController(private val marketplace: Marketplace) {

    @PostMapping("/me/inquiries")
    fun create(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @RequestBody(required = false) body: String?,
    ): ResponseEntity<InquiryView> {
        val userId = marketplace.requireUser(userHeader)
        val json = parseObject(body)
        val inquiry = marketplace.createInquiry(
            userId,
            json.textOrNull("category"),
            json.textOrNull("title"),
            json.textOrNull("body"),
            json.longOrNull("orderItemId"),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(inquiry)
    }

    @PostMapping("/me/inquiries/{inquiryId}/messages")
    fun message(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable inquiryId: String,
        @RequestBody(required = false) body: String?,
    ): InquiryView {
        val userId = marketplace.requireUser(userHeader)
        val json = parseObject(body)
        return marketplace.addInquiryMessage(userId, pathId(inquiryId), json.textOrNull("body"))
    }

    @PostMapping("/admin/inquiries/{inquiryId}/answer")
    fun answer(@PathVariable inquiryId: String, @RequestBody(required = false) body: String?): InquiryView {
        val json = parseObject(body)
        return marketplace.answerInquiry(pathId(inquiryId), json.textOrNull("body"))
    }

    @PostMapping("/me/inquiries/{inquiryId}/close")
    fun close(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable inquiryId: String,
    ): InquiryView {
        val userId = marketplace.requireUser(userHeader)
        return marketplace.closeInquiry(userId, pathId(inquiryId))
    }

    @PostMapping("/me/inquiries/{inquiryId}/reopen")
    fun reopen(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable inquiryId: String,
        @RequestBody(required = false) body: String?,
    ): InquiryView {
        val userId = marketplace.requireUser(userHeader)
        val json = parseObject(body)
        return marketplace.reopenInquiry(userId, pathId(inquiryId), json.textOrNull("body"))
    }

    /** 관리자 문의함(§20.4.2 — §2.5 키셋 페이지). 쿼리 값의 형식·범위는 Marketplace가 본다. */
    @GetMapping("/admin/inquiries")
    fun adminList(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) slaBreached: String?,
        @RequestParam(required = false) limit: String?,
        @RequestParam(required = false) after: String?,
    ): PageView<AdminInquiryView> = marketplace.adminInquiries(status, slaBreached, limit, after)

    @GetMapping("/admin/inquiries/{inquiryId}")
    fun adminDetail(@PathVariable inquiryId: String): AdminInquiryDetailView = marketplace.adminInquiryDetail(pathId(inquiryId))

    @GetMapping("/me/inquiries")
    fun list(@RequestHeader("X-User-Id", required = false) userHeader: String?): List<InquiryView> =
        marketplace.inquiries(marketplace.requireUser(userHeader))

    @GetMapping("/me/inquiries/{inquiryId}")
    fun detail(
        @RequestHeader("X-User-Id", required = false) userHeader: String?,
        @PathVariable inquiryId: String,
    ): InquiryDetailView {
        val userId = marketplace.requireUser(userHeader)
        return marketplace.inquiryDetail(userId, pathId(inquiryId))
    }
}
