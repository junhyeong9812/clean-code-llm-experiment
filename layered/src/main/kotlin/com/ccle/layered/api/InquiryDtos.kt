package com.ccle.layered.api

import com.ccle.layered.domain.InquiryDetail
import com.ccle.layered.domain.InquiryMessage
import com.ccle.layered.domain.InquiryOrderItem
import com.ccle.layered.domain.InquiryRead
import com.fasterxml.jackson.annotation.JsonUnwrapped
import java.time.Instant

// 응답 본문 모양(ADR-0007 §9 A6). Instant는 KST ISO-8601(Json.kt).

/** 문의 모양(§9.1). slaBreached는 계산 값. */
data class InquiryView(
    val inquiryId: Long,
    val category: String,
    val title: String,
    val status: String,
    val closeReason: String?,
    val orderItemId: Long?,
    val createdAt: Instant,
    val slaDueAt: Instant,
    val slaBreached: Boolean,
    val firstAnsweredAt: Instant?,
    val answeredAt: Instant?,
    val closedAt: Instant?,
) {
    constructor(r: InquiryRead) : this(
        r.inquiry.id!!, r.inquiry.category, r.inquiry.title, r.inquiry.status, r.inquiry.closeReason, r.inquiry.orderItemId,
        r.inquiry.createdAt, r.inquiry.slaDueAt, r.slaBreached, r.inquiry.firstAnsweredAt, r.inquiry.answeredAt, r.inquiry.closedAt,
    )
}

data class InquiryMessageView(val author: String, val body: String, val createdAt: Instant) {
    constructor(m: InquiryMessage) : this(m.author, m.body, m.createdAt)
}

/** 문의 상세의 주문 항목(§9.2). */
data class InquiryOrderItemView(val orderItemId: Long, val status: String, val openDispute: Boolean) {
    constructor(i: InquiryOrderItem) : this(i.orderItemId, i.status, i.openDispute)
}

/** 문의 상세(§9.2) = 문의 모양 + messages(id 순) + orderItem. */
data class InquiryDetailView(
    @get:JsonUnwrapped val inquiry: InquiryView,
    val messages: List<InquiryMessageView>,
    val orderItem: InquiryOrderItemView?,
) {
    constructor(d: InquiryDetail) : this(
        InquiryView(d.inquiry),
        d.messages.map { InquiryMessageView(it) },
        d.orderItem?.let { InquiryOrderItemView(it) },
    )
}
