package com.ccle.layered.service

import com.ccle.layered.domain.CardCancellation
import com.ccle.layered.domain.PaymentGateway
import com.ccle.layered.domain.PgReply
import com.ccle.layered.domain.PgResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * PG 호출과 해석. 어떤 예외든 응답 없음으로 본다(ADR-0003 §9의 7). 승인·재조회는 트랜잭션·잠금 밖에서 부른다.
 * 카드 취소만 주문·항목 잠금을 쥔 트랜잭션 안에서 부른다(ADR-0004 §2.3 — 같은 주문의 다른 환불이 마지막 환불 판정을 바꾸지 못하게).
 */
@Component
class PgCaller(private val pg: PaymentGateway) {
    private val log = LoggerFactory.getLogger(PgCaller::class.java)

    fun approve(idempotencyKey: String, amount: Long): PgResult =
        interpret(call("승인 $idempotencyKey") { pg.postApproval(idempotencyKey, amount) }, inquiry = false)

    fun inquire(idempotencyKey: String): PgResult =
        interpret(call("재조회 $idempotencyKey") { pg.getApproval(idempotencyKey) }, inquiry = true)

    /** 카드 취소(ADR-0004 §7.2). 반환 = PG의 pgTxId. 실패면 409 PG_UNAVAILABLE(호출자 트랜잭션은 롤백된다). */
    fun cancel(orderItemId: Long, approvalKey: String, amount: Long): String {
        val key = CardCancellation.keyFor(orderItemId)
        val reply = call("취소 $key") { pg.postCancellation(key, approvalKey, amount) }
        val result = CardCancellation.of(key, reply, amount)
        if (result.pgTxId == null) log.warn("카드 취소 실패({}, 요청 {}): {}", key, amount, reply?.let { "HTTP ${it.httpStatus} ${it.raw}" })
        return result.requireApproved()
    }

    private fun interpret(reply: PgReply?, inquiry: Boolean): PgResult {
        val result = PgResult.of(reply, inquiry)
        result.warning?.let { log.warn(it) }
        return result
    }

    /** 인터럽트는 플래그를 되살린다. */
    private fun call(what: String, call: () -> PgReply): PgReply? =
        try {
            call()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn("PG 응답 없음({}): 인터럽트", what)
            null
        } catch (e: Exception) {
            log.warn("PG 응답 없음({}): {}", what, e.toString())
            null
        }
}
