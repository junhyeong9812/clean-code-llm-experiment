package com.ccle.layered.domain

import java.time.Duration

/**
 * PG 응답(ADR-0003 §6). JSON 본문은 HTTP 200일 때만 읽어 둔다: readable = 읽을 수 있었나,
 * status = 본문의 status 값(문자열로), pgTxId = 본문의 pgTxId(문자열일 때만). raw = 본문 그대로(로그용).
 */
class PgReply(val httpStatus: Int, val raw: String, val readable: Boolean, val status: String?, val pgTxId: String?)

/**
 * PG HTTP 호출(ADR-0003 §6). 응답을 그대로 돌려주고, 타임아웃·연결 실패 등은 예외를 그대로 던진다.
 * 결과 해석은 PgResult, 예외 = 응답 없음 처리와 재시도·상태 반영은 서비스가 한다.
 */
interface PaymentGateway {
    /** 요청 하나의 타임아웃. 연결에도 같은 값을 쓰므로 호출 하나는 최대 약 2배까지 걸릴 수 있다. */
    val timeout: Duration

    /** 승인 요청: `POST {PG_URL}/approvals {"idempotencyKey","amount"}`. */
    fun postApproval(idempotencyKey: String, amount: Long): PgReply

    /** 재조회: `GET {PG_URL}/approvals/{idempotencyKey}`. */
    fun getApproval(idempotencyKey: String): PgReply
}

/** PG 결과(ADR-0003 §5.1 표의 세 갈래). 이름이 pg_transactions.status 값이다. */
enum class PgOutcome { APPROVED, DECLINED, UNKNOWN }

/**
 * PG 응답 해석(ADR-0003 §5.1·§6·§7): outcome = 승인·거절·응답 없음, pgTxId는 승인일 때만 PG 값.
 * 응답 없음(null)·200이 아닌 코드·읽을 수 없는 본문·알 수 없는 status·pgTxId 없는 승인은 UNKNOWN.
 * 재조회의 404(승인 요청이 기록되지 않음)는 DECLINED. warning = 응답이 있었는데 UNKNOWN으로 본 이유(로그용).
 */
class PgResult private constructor(val outcome: PgOutcome, val pgTxId: String?, val warning: String?) {
    companion object {
        fun of(reply: PgReply?, inquiry: Boolean): PgResult {
            if (reply == null) return PgResult(PgOutcome.UNKNOWN, null, null)
            if (inquiry && reply.httpStatus == 404) return PgResult(PgOutcome.DECLINED, null, null)
            if (reply.httpStatus != 200) return PgResult(PgOutcome.UNKNOWN, null, "PG 응답 없음으로 본다: HTTP ${reply.httpStatus}")
            if (!reply.readable) return PgResult(PgOutcome.UNKNOWN, null, "PG 응답 본문을 읽을 수 없다: ${reply.raw}")
            return when (reply.status) {
                "APPROVED" -> if (reply.pgTxId != null) {
                    PgResult(PgOutcome.APPROVED, reply.pgTxId, null)
                } else {
                    PgResult(PgOutcome.UNKNOWN, null, "PG 승인 응답에 pgTxId가 없다: ${reply.raw}")
                }
                "DECLINED" -> PgResult(PgOutcome.DECLINED, null, null)
                else -> PgResult(PgOutcome.UNKNOWN, null, "PG가 알 수 없는 상태를 돌려줌: ${reply.raw}")
            }
        }
    }
}
