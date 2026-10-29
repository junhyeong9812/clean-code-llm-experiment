package com.ccle.layered.domain

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Duration
import java.time.Instant

// A6 고객 문의(ADR-0007 §9). 오류 code는 기존 Failure만 쓴다(§2.2 — 새 테이블 행의 상태 충돌도 ITEM_STATE_CONFLICT).

/** 문의 본문·추가 메시지·답변(§9.1): 앞뒤 공백을 뺀 값이 1~2,000자. 받은 그대로 저장한다. */
fun inquiryMessageBody(body: String?): String {
    if (body == null) invalidRequest("body가 필요하다")
    if (textLength(body) !in 1..2_000) invalidRequest("body는 1~2,000자")
    return body
}

/** 문의 작성 요청의 값(§9.1): category는 다섯 값 중 하나, title 1~100자, body 1~2,000자. */
class InquiryInput private constructor(val category: String, val title: String, val body: String) {
    companion object {
        private val CATEGORIES = setOf("ORDER", "PAYMENT", "SETTLEMENT", "ACCOUNT", "OTHER")

        fun of(category: String?, title: String?, body: String?): InquiryInput {
            if (category == null) invalidRequest("category가 필요하다")
            if (category !in CATEGORIES) invalidRequest("알 수 없는 category: $category")
            if (title == null) invalidRequest("title이 필요하다")
            if (textLength(title) !in 1..100) invalidRequest("title은 1~100자")
            return InquiryInput(category, title, inquiryMessageBody(body))
        }
    }
}

/** 문의 행(§3 inquiries). 상태 OPEN·ANSWERED·CLOSED, sla_due_at = created_at + 48시간. */
@Entity
@Table(name = "inquiries")
class Inquiry(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var userId: Long,
    var orderItemId: Long? = null,
    var category: String,
    var title: String,
    status: String,
    closeReason: String? = null,
    var createdAt: Instant,
    var slaDueAt: Instant,
    firstAnsweredAt: Instant? = null,
    answeredAt: Instant? = null,
    closedAt: Instant? = null,
) {
    var status: String = status
        private set

    var closeReason: String? = closeReason
        private set

    var firstAnsweredAt: Instant? = firstAnsweredAt
        private set

    var answeredAt: Instant? = answeredAt
        private set

    var closedAt: Instant? = closedAt
        private set

    /** 작성자만(§9.1 — 403). */
    fun requireAuthor(userId: Long) {
        if (this.userId != userId) throw MarketException(Failure.NOT_OWNER, "작성자가 아닌 문의: $id")
    }

    /** 상태를 바꾸는 요청의 허용 상태(§9.1): OPEN·ANSWERED. CLOSED면 409. */
    fun requireNotClosed() {
        if (status == "CLOSED") throw MarketException(Failure.ITEM_STATE_CONFLICT, "종결된 문의: $id")
    }

    /** 추가 메시지(§9.1·§19의 11): 상태 OPEN. answered_at·first_answered_at은 그대로. */
    fun reopenByMessage() {
        status = "OPEN"
    }

    /** 답변(§9.1): ANSWERED, answered_at = 현재, first_answered_at이 NULL이면 현재. */
    fun answer(now: Instant) {
        status = "ANSWERED"
        answeredAt = now
        if (firstAnsweredAt == null) firstAnsweredAt = now
    }

    /** 작성자 종결(§9.1): CLOSED, close_reason = USER, closed_at = 현재. */
    fun closeByUser(now: Instant) {
        status = "CLOSED"
        closeReason = "USER"
        closedAt = now
    }

    /** 자동 종결 대상(§9.3): ANSWERED이고 answered_at + 7일 ≤ 현재. */
    fun isAutoClosable(now: Instant): Boolean = status == "ANSWERED" && !answeredAt!!.isAfter(autoCloseCutoff(now))

    /** 자동 종결(§9.3): CLOSED, close_reason = AUTO, closed_at = 현재(배치 시각). */
    fun closeAutomatically(now: Instant) {
        status = "CLOSED"
        closeReason = "AUTO"
        closedAt = now
    }

    /** slaBreached(§9.1) = (미답변이고 현재 > sla_due_at) 또는 first_answered_at > sla_due_at — 계산 값. */
    fun slaBreached(now: Instant): Boolean {
        val first = firstAnsweredAt
        return if (first == null) now.isAfter(slaDueAt) else first.isAfter(slaDueAt)
    }

    companion object {
        private val SLA = Duration.ofHours(48)
        private val AUTO_CLOSE_AFTER = Duration.ofDays(7)

        /** 자동 종결 기준 시각 상한(§9.3): answered_at ≤ 현재 − 7일. */
        fun autoCloseCutoff(now: Instant): Instant = now.minus(AUTO_CLOSE_AFTER)

        /** 작성(§9.1): OPEN, sla_due_at = 현재 + 48시간. */
        fun open(userId: Long, orderItemId: Long?, input: InquiryInput, now: Instant) = Inquiry(
            userId = userId,
            orderItemId = orderItemId,
            category = input.category,
            title = input.title,
            status = "OPEN",
            createdAt = now,
            slaDueAt = now.plus(SLA),
        )
    }
}

/** 문의 메시지 행(§3 inquiry_messages). author = USER·ADMIN. */
@Entity
@Table(name = "inquiry_messages")
class InquiryMessage(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var inquiryId: Long,
    var author: String,
    var body: String,
    var createdAt: Instant,
)

/** 문의 상세의 주문 항목(§9.2): 지금의 항목 상태와 OPEN 분쟁 유무. */
class InquiryOrderItem(val orderItemId: Long, val status: String, val openDispute: Boolean)

/** 응답 시점의 문의(§9.1 문의 모양): slaBreached는 요청 시각으로 계산한 값. */
class InquiryRead(val inquiry: Inquiry, now: Instant) {
    val slaBreached: Boolean = inquiry.slaBreached(now)
}

/** 문의 상세(§9.2). messages는 id 순. */
class InquiryDetail(val inquiry: InquiryRead, val messages: List<InquiryMessage>, val orderItem: InquiryOrderItem?)

interface InquiryRepository {
    fun find(id: Long): Inquiry?

    fun add(inquiry: Inquiry)

    /** 문의 행 FOR UPDATE(§9.1). */
    fun lock(id: Long)

    fun reload(inquiry: Inquiry)

    /** 그 사용자의 문의, id 순. */
    fun listOf(userId: Long): List<Inquiry>

    fun addMessage(message: InquiryMessage)

    /** 그 문의의 메시지, id 순. */
    fun messagesOf(inquiryId: Long): List<InquiryMessage>

    /**
     * 문의에 붙일 항목의 주문 → 항목 행 FOR KEY SHARE(§19의 9-1 — INSERT의 FK 검사보다 먼저). 없는 항목이면 false.
     */
    fun keyShareItem(orderItemId: Long): Boolean

    /** 그 항목의 OPEN 분쟁 수. */
    fun countOpenDisputes(orderItemId: Long): Long
}
