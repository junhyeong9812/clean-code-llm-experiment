package com.ccle.layered.service

import com.ccle.layered.domain.PaymentGateway
import com.ccle.layered.domain.PgReply
import com.ccle.layered.domain.PgResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** PG 호출과 해석. 어떤 예외든 응답 없음으로 본다(ADR-0003 §9의 7). 트랜잭션·잠금 밖에서 부른다. */
@Component
class PgCaller(private val pg: PaymentGateway) {
    private val log = LoggerFactory.getLogger(PgCaller::class.java)

    fun approve(idempotencyKey: String, amount: Long): PgResult =
        interpret(call("승인 $idempotencyKey") { pg.postApproval(idempotencyKey, amount) }, inquiry = false)

    fun inquire(idempotencyKey: String): PgResult =
        interpret(call("재조회 $idempotencyKey") { pg.getApproval(idempotencyKey) }, inquiry = true)

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
