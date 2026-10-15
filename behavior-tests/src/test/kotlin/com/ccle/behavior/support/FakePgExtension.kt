package com.ccle.behavior.support

import com.ccle.behavior.pg.FakePg
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * 가짜 PG 준비.
 *  - PG_EXTERNAL=1: 띄우지 않는다. 이미 떠 있는 127.0.0.1:PG_PORT에 HTTP로 붙고, 응답이 없으면 실패한다.
 *  - 그 밖(단독 개발용): 스위트 전체에서 1번만 JVM 안에 띄운다. 루트 저장소에 두어 스위트가 끝날 때 닫힌다.
 * 테스트는 어느 쪽이든 HTTP 제어 엔드포인트(PgClient)로만 단언한다.
 */
class FakePgExtension : BeforeAllCallback {
    override fun beforeAll(context: ExtensionContext) {
        context.root.getStore(NS).getOrComputeIfAbsent(KEY, { start() }, Any::class.java)
    }

    private fun start(): Any {
        if (Env.pgExternal) {
            val r = runCatching { PgClient.ping() }
            check(r.getOrDefault(false)) { "PG_EXTERNAL=1인데 가짜 PG(${Flow.pgUrl()})가 응답하지 않는다: ${r.exceptionOrNull()}" }
            return "external"
        }
        return Holder(FakePg(Env.pgPort, Env.pgDelayMs).start())
    }

    private class Holder(val pg: FakePg) : ExtensionContext.Store.CloseableResource {
        override fun close() = pg.close()
    }

    companion object {
        private val NS = ExtensionContext.Namespace.create(FakePgExtension::class.java)
        private const val KEY = "fake-pg"
    }
}

/** 가짜 PG의 HTTP 제어·조회 클라이언트(같은 JVM이든 별도 프로세스든 같은 방식으로 단언한다). */
class PgClient(private val base: String) {
    data class Requests(val approvals: Int, val inquiries: Int, val amounts: List<Long>, val pgTxId: String?, val status: String?)

    fun requests(key: String): Requests {
        val r = Api.request("GET", "/_control/requests/$key", base = base)
        check(r.status == 200) { "가짜 PG 조회 실패: $r" }
        val j = r.json
        val rec = j.get("record")
        return Requests(
            approvals = j.get("approvals").asInt(),
            inquiries = j.get("inquiries").asInt(),
            amounts = j.get("amounts").map { it.asLong() },
            pgTxId = rec?.takeIf { !it.isNull }?.get("pgTxId")?.asText(),
            status = rec?.takeIf { !it.isNull }?.get("status")?.asText(),
        )
    }

    fun approvalAmounts(key: String): List<Long> = requests(key).amounts
    fun approvalCount(key: String): Int = requests(key).approvals
    fun inquiryCount(key: String): Int = requests(key).inquiries
    fun pgTxId(key: String): String? = requests(key).pgTxId

    companion object {
        /** 스위트용 PG(127.0.0.1:PG_PORT). */
        val suite: PgClient get() = PgClient(Flow.pgUrl())
        fun ping(): Boolean = Api.request("GET", "/_control/requests/__ping__", base = Flow.pgUrl()).status == 200
    }
}

/** 계약 테스트: 태그 contract + 가짜 PG. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Tag("contract")
@ExtendWith(FakePgExtension::class)
annotation class ContractTest

/** 인프라 자체 테스트: 태그 infra. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Tag("infra")
annotation class InfraTest
