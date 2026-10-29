package com.ccle.layered.service

import com.ccle.layered.domain.AdminInquiryFilter
import com.ccle.layered.domain.BatchTarget
import com.ccle.layered.domain.BatchTargets
import com.ccle.layered.domain.Inquiry
import com.ccle.layered.domain.InquiryDetail
import com.ccle.layered.domain.InquiryInput
import com.ccle.layered.domain.InquiryMessage
import com.ccle.layered.domain.InquiryOrderItem
import com.ccle.layered.domain.InquiryRead
import com.ccle.layered.domain.InquiryRepository
import com.ccle.layered.domain.KeysetPage
import com.ccle.layered.domain.MarketClock
import com.ccle.layered.domain.OrderItemRepository
import com.ccle.layered.domain.PageOf
import com.ccle.layered.domain.TradeRepository
import com.ccle.layered.domain.inquiryMessageBody
import com.ccle.layered.domain.notFound
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * A6 고객 문의 작성·추가 메시지·답변(관리자)·종결·조회(ADR-0007 §9). 사용자 확인(401)은 부르는 쪽이 한다.
 * 판정 순서: 401 → 400(본문) → 404 → 403(작성자 아님) → 문의 행 FOR UPDATE → 409.
 */
@Service
class InquiryService(
    private val clock: MarketClock,
    private val tx: TransactionTemplate,
    private val inquiries: InquiryRepository,
    private val items: OrderItemRepository,
    private val trades: TradeRepository,
    private val audit: AdminAuditService,
) {
    private val snapshotTx = TransactionTemplate(tx.transactionManager!!).apply {
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        isReadOnly = true
    }

    /**
     * 작성: 400 → (orderItemId가 있으면) 주문 → 항목 KEY SHARE(§19의 9-1, 없는 항목 404) → 구매자·판매자가 아니면 403
     * → 문의 1행(OPEN) + USER 메시지 1행.
     */
    fun create(userId: Long, category: String?, title: String?, body: String?, orderItemId: Long?): InquiryRead {
        val input = InquiryInput.of(category, title, body)
        val now = clock.now()
        return tx.execute {
            if (orderItemId != null) {
                if (!inquiries.keyShareItem(orderItemId)) notFound("없는 주문 항목: $orderItemId")
                val item = items.find(orderItemId) ?: notFound("없는 주문 항목: $orderItemId")
                item.requireParty(trades.find(item.tradeId)!!, userId)
            }
            val inquiry = Inquiry.open(userId, orderItemId, input, now)
            inquiries.add(inquiry)
            inquiries.addMessage(InquiryMessage(inquiryId = inquiry.id!!, author = "USER", body = input.body, createdAt = now))
            InquiryRead(inquiry, now)
        }!!
    }

    /** 추가 메시지: 작성자만, OPEN·ANSWERED → USER 메시지 1행, 상태 OPEN. */
    fun addMessage(userId: Long, inquiryId: Long, body: String?): InquiryRead {
        val text = inquiryMessageBody(body)
        val now = clock.now()
        return tx.execute {
            val inquiry = lockOpen(inquiryId, authorId = userId)
            inquiries.addMessage(InquiryMessage(inquiryId = inquiryId, author = "USER", body = text, createdAt = now))
            inquiry.reopenByMessage()
            InquiryRead(inquiry, now)
        }!!
    }

    /** 운영자 답변: OPEN·ANSWERED → ADMIN 메시지 1행, ANSWERED + 감사 INQUIRY_ANSWER(detail null). */
    fun answer(inquiryId: Long, body: String?): InquiryRead {
        val text = inquiryMessageBody(body)
        val now = clock.now()
        return tx.execute {
            val inquiry = lockOpen(inquiryId, authorId = null)
            inquiries.addMessage(InquiryMessage(inquiryId = inquiryId, author = "ADMIN", body = text, createdAt = now))
            inquiry.answer(now)
            audit.record("INQUIRY_ANSWER", inquiryId, null, now)
            InquiryRead(inquiry, now)
        }!!
    }

    /** 종결: 작성자만, OPEN·ANSWERED → CLOSED(USER). */
    fun close(userId: Long, inquiryId: Long): InquiryRead {
        val now = clock.now()
        return tx.execute {
            val inquiry = lockOpen(inquiryId, authorId = userId)
            inquiry.closeByUser(now)
            InquiryRead(inquiry, now)
        }!!
    }

    /** 내 문의, inquiryId 오름차순. */
    fun list(userId: Long): List<InquiryRead> {
        val now = clock.now()
        return inquiries.listOf(userId).map { InquiryRead(it, now) }
    }

    /** 상세: 작성자만. 메시지 id 순, orderItem은 지금의 항목 상태와 OPEN 분쟁 유무(읽기만). 한 스냅샷에서 읽는다. */
    fun detail(userId: Long, inquiryId: Long): InquiryDetail = snapshotTx.execute {
        val now = clock.now()
        val inquiry = inquiries.find(inquiryId) ?: notFound("없는 문의: $inquiryId")
        inquiry.requireAuthor(userId)
        val messages = inquiries.messagesOf(inquiryId)
        val orderItem = inquiry.orderItemId?.let { itemId ->
            val openDisputes = inquiries.countOpenDisputes(itemId)
            InquiryOrderItem(itemId, items.find(itemId)!!.status, openDisputes > 0)
        }
        InquiryDetail(InquiryRead(inquiry, now), messages, orderItem)
    }!!

    /** 재개(§20.4.1): 400 → 404 → 403(작성자 아님) → 문의 행 FOR UPDATE → 409(CLOSED 아님 → 기한) → OPEN + USER 메시지 1행. */
    fun reopen(userId: Long, inquiryId: Long, body: String?): InquiryRead {
        val text = inquiryMessageBody(body)
        val now = clock.now()
        return tx.execute {
            val inquiry = inquiries.find(inquiryId) ?: notFound("없는 문의: $inquiryId")
            inquiry.requireAuthor(userId)
            inquiries.lock(inquiryId)
            inquiries.reload(inquiry)
            inquiry.reopen(now)
            inquiries.addMessage(InquiryMessage(inquiryId = inquiryId, author = "USER", body = text, createdAt = now))
            InquiryRead(inquiry, now)
        }!!
    }

    /** `GET /admin/inquiries`(§20.4.2·§2.5 페이지): 400(필터 → 페이지) → id 순 페이지. 한 스냅샷에서 읽는다. */
    fun adminList(status: String?, slaBreached: String?, limit: String?, after: String?): PageOf<InquiryRead> {
        val filter = AdminInquiryFilter.of(status, slaBreached)
        val page = KeysetPage.of(limit, after)
        return snapshotTx.execute {
            val now = clock.now()
            val items = inquiries.adminPage(filter, now, page).map { InquiryRead(inquiries.find(it)!!, now) }
            page.of(items) { it.inquiry.id!! }
        }!!
    }

    /** `GET /admin/inquiries/{id}`(§20.4.2): 상세(§9.2) — 없는 문의 404. 한 스냅샷에서 읽는다. */
    fun adminDetail(inquiryId: Long): InquiryDetail = snapshotTx.execute {
        val inquiry = inquiries.find(inquiryId) ?: notFound("없는 문의: $inquiryId")
        detail(inquiry.userId, inquiryId)
    }!!

    /** 상태를 바꾸는 요청의 공통 단계: 404 → (작성자 요청이면) 403 → 문의 행 FOR UPDATE 뒤 CLOSED면 409. authorId = null이면 운영자. */
    private fun lockOpen(inquiryId: Long, authorId: Long?): Inquiry {
        val inquiry = inquiries.find(inquiryId) ?: notFound("없는 문의: $inquiryId")
        if (authorId != null) inquiry.requireAuthor(authorId)
        inquiries.lock(inquiryId)
        inquiries.reload(inquiry)
        inquiry.requireNotClosed()
        return inquiry
    }
}

/**
 * inquiry-auto-close(ADR-0007 §9.3): ANSWERED이고 answered_at + 7일 ≤ 현재(id 순). 문의 1건 = 그 행을 잠근 트랜잭션 하나,
 * 잠근 뒤 다시 확인한다. → CLOSED(AUTO), closed_at = 현재(배치 시각). processed = 종결한 문의 수.
 */
@Component
class InquiryAutoCloseBatch(
    private val targets: BatchTargets,
    private val tx: TransactionTemplate,
    private val inquiries: InquiryRepository,
) {
    fun run(now: Instant): BatchResult =
        targets.forEachIsolated("inquiry-auto-close", BatchTarget.AUTO_CLOSABLE_INQUIRIES, now) { inquiryId ->
            tx.execute {
                inquiries.lock(inquiryId)
                val inquiry = inquiries.find(inquiryId)!!
                if (!inquiry.isAutoClosable(now)) return@execute false
                inquiry.closeAutomatically(now)
                true
            }!!
        }
}
