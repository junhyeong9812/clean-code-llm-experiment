package com.ccle.behavior.support

import com.ccle.behavior.pg.FakePg

/**
 * 가짜 PG 카드 취소 제어·조회(ADR-0004 §7.2). 키는 `cancel-<orderItemId>`.
 *  - 조회 `GET /_control/cancellations/{key}`: 앱이 보낸 취소 요청 수·금액·승인 키와 기록.
 *  - 제어 `POST /_control/outcomes {"outcome": "UNAVAILABLE"}`: 그 키의 취소 요청에 503(기록 없음). APPROVE로 되돌린다.
 */
object PgCancel {
    data class Info(
        val requests: Int, val amounts: List<Long>, val approvalKeys: List<String>,
        val pgTxId: String?, val status: String?, val amount: Long?, val approvalKey: String?,
    )

    fun key(orderItemId: Long) = "cancel-$orderItemId"

    fun info(orderItemId: Long): Info {
        val r = Api.request("GET", "/_control/cancellations/${key(orderItemId)}", base = Flow.pgUrl())
        check(r.status == 200) { "가짜 PG 취소 조회 실패: $r" }
        val j = r.json
        val rec = j.get("record")?.takeIf { !it.isNull }
        return Info(
            requests = j.get("requests").asInt(),
            amounts = j.get("amounts").map { it.asLong() },
            approvalKeys = j.get("approvalKeys").map { it.asText() },
            pgTxId = rec?.get("pgTxId")?.asText(),
            status = rec?.get("status")?.asText(),
            amount = rec?.get("amount")?.asLong(),
            approvalKey = rec?.get("approvalKey")?.asText(),
        )
    }

    fun unavailable(orderItemId: Long) = Flow.pgOutcome(key(orderItemId), FakePg.Outcome.UNAVAILABLE)

    /** 지정을 풀어 승인으로 되돌린다(취소 키에는 UNAVAILABLE 밖의 결과가 모두 승인이다). */
    fun approve(orderItemId: Long) = Flow.pgOutcome(key(orderItemId), FakePg.Outcome.APPROVE)
}
